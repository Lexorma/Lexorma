package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableSearch;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 물리명 추천 일괄 적용 1차 판정의 조회 성능 측정 — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다 (일반 빌드에서는 건너뜀).
 * 논리 → 물리 생성으로 규모별 모델(엔터티 × 속성)을 만든 뒤 테이블·컬럼 이름을 JDBC 로 바꿔(앞에 O_) 모든 행이 판정 대상(AVAILABLE)이 되게 하고,
 * 테이블마다 그 테이블을 참조하는 VIEW 하나와 CHECK 하나를 둔다. 추천 목록 한 쪽(100행)의 시간과 Hibernate 준비 문장 수를 잰다 (5회 중 중앙값).
 * 결과는 build/reports/m7-recommendation-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class PhysicalNameRecommendationPerformanceTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired EntityManagerFactory emf;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("규모별 추천 목록 한 쪽(100행)의 시간과 SQL 수 (10×10, 100×20, 300×30)")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("size | tables | columns | views | column p1 ms (stmts) | column last ms (stmts) | column 1 row ms (stmts) | table p1 ms (stmts) | table last ms (stmts) | selectable col/tbl (p1)");
        for (int[] size : new int[][]{{10, 10}, {100, 20}, {300, 30}}) {
            lines.add(run(size[0], size[1]));
            System.out.println("PERF|" + lines.get(lines.size() - 1));
        }
        Path out = Path.of("build/reports/m7-recommendation-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(int entityCount, int attributesPerEntity) {
        GenerationFixture f = GenerationFixture.create(context, "rperf-" + entityCount + "x" + attributesPerEntity + "-" + System.nanoTime(), DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            long entity = f.entity(name(i));
            entities.add(entity);
            f.pk(entity, new long[]{f.attr(entity, "키", true, "K1")});
            for (int a = 1; a < attributesPerEntity; a++) f.attr(entity, "속성" + a, false, String.format("C%02d", a));
        }
        for (int from = 0; from < entities.size(); from += 100) {
            List<Long> chunk = entities.subList(from, Math.min(entities.size(), from + 100));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chunk));
            generation.apply(f.user, new PhysicalGenerationDto.Request(chunk, null, plan.planToken()));
        }
        long projectId = f.user.getProjectId();
        // 모든 행을 판정 대상으로 : 현재 이름 ≠ 추천명
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM, TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        List<Long> tableIds = jdbc.queryForList("select TBL_SN from TB_TBL where PRJ_SN = ? order by TBL_SN", Long.class, projectId);
        tx.executeWithoutResult(s -> {
            int i = 0;
            for (Long tableId : tableIds) {
                TableInfo t = entityManager.find(TableInfo.class, tableId);
                SchemaInfo schema = t.getSchema();
                String col = jdbc.queryForObject("select min(COL_NM) from TB_COL where TBL_SN = ?", String.class, tableId);
                PhysicalView v = PhysicalView.builder().projectId(projectId).schema(schema).viewName("V_PERF_" + i)
                        .definition("select a." + col + ", 'x' as lit /* c */ from " + t.getTableName() + " a where a." + col + " is not null").isApproval(false).build();
                entityManager.persist(v);
                entityManager.persist(PhysicalViewReference.builder().view(v).referenceType(PhysicalViewReference.Type.TABLE).referencedTable(t).build());
                entityManager.persist(TableConstraint.builder().projectId(projectId).tableInfo(t).constraintName("CK_PERF_" + i).constraintType(TableConstraint.Type.CHECK)
                        .checkExpression("length(" + col + ") > 0").isApproval(false).build());
                i++;
            }
        });
        long columnCount = jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Long.class, projectId);

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        ColumnSearch allColumns = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        TableSearch allTables = new TableSearch(f.modelId, null, null, null);
        int lastColumnPage = (int) ((columnCount - 1) / 100);
        int lastTablePage = (tableIds.size() - 1) / 100;
        Long oneAttribute = recommendation.selectColumnRows(f.user, allColumns, PageRequest.of(lastColumnPage, 100)).getContent().get(0).logicalAttributeId();

        Measured<ColumnRow> c1 = measure(stats, () -> recommendation.selectColumnRows(f.user, allColumns, PageRequest.of(0, 100)));
        Measured<ColumnRow> cLast = measure(stats, () -> recommendation.selectColumnRows(f.user, allColumns, PageRequest.of(lastColumnPage, 100)));
        Measured<ColumnRow> cOne = measure(stats, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(null, null, oneAttribute, null, null, null, null), PageRequest.of(0, 100)));
        Measured<TableRow> t1 = measure(stats, () -> recommendation.selectTableRows(f.user, allTables, PageRequest.of(0, 100)));
        Measured<TableRow> tLast = measure(stats, () -> recommendation.selectTableRows(f.user, allTables, PageRequest.of(lastTablePage, 100)));
        assertThat(c1.page.getContent()).allSatisfy(r -> assertThat(r.applicability()).isNotNull());
        long selectableColumns = c1.page.getContent().stream().filter(r -> r.applicability().selectable()).count();
        long selectableTables = t1.page.getContent().stream().filter(r -> r.applicability().selectable()).count();
        return entityCount + "x" + attributesPerEntity + " | " + tableIds.size() + " | " + columnCount + " | " + tableIds.size() + " | " + c1 + " | " + cLast + " | " + cOne
                + " | " + t1 + " | " + tLast + " | " + selectableColumns + "/" + selectableTables;
    }

    private record Measured<T>(Page<T> page, long ms, long statements) {
        @Override
        public String toString() {
            return ms + " (" + statements + ")";
        }
    }

    /** 한 번 데운 뒤 5회 중앙값 시간과 마지막 회의 준비 문장 수 */
    private static <T> Measured<T> measure(Statistics stats, Supplier<Page<T>> call) {
        call.get();
        long[] times = new long[5];
        Page<T> page = null;
        long statements = 0;
        for (int i = 0; i < times.length; i++) {
            stats.clear();
            long t0 = System.nanoTime();
            page = call.get();
            times[i] = (System.nanoTime() - t0) / 1_000_000;
            statements = stats.getPrepareStatementCount();
        }
        java.util.Arrays.sort(times);
        return new Measured<>(page, times[2], statements);
    }

    private static String name(int i) {
        int n = WORDS.length;
        if (i < n) return WORDS[i];
        int j = i - n;
        if (j < n * (n - 1)) {
            int a = j / (n - 1);
            int b = j % (n - 1);
            return WORDS[a] + " " + WORDS[b >= a ? b + 1 : b];
        }
        j -= n * (n - 1);
        int a = j / ((n - 1) * (n - 2));
        int rest = j % ((n - 1) * (n - 2));
        List<String> others = new ArrayList<>(List.of(WORDS));
        String first = others.remove(a);
        String second = others.remove(rest / (n - 2));
        return first + " " + second + " " + others.get(rest % (n - 2));
    }
}
