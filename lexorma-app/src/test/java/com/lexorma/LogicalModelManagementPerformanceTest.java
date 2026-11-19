package com.lexorma;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.service.LogicalModelService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 논리 모델 관리 조회 성능 측정 (M-6 후속 3차) — LEXORMA_PERF=true 일 때만 실행한다 (일반 빌드에서는 건너뜀).
 * 데이터는 LogicalErdPerformanceTest.seed (엔터티 100·1,000·3,000개, 속성 8개·주 식별자 1개, 사슬 + 허브 관계)에 자기 참조(50번째마다)와
 * 같은 쌍의 두 번째 관계(10번째마다, 식별 관계)를 더한다. 예전 화면(전체 그래프 + 상세가 모델 관계 전체)과 지금 화면(요약 + 엔터티 페이지 + 상세가 그 엔터티의 관계)을
 * 화면 단위로 비교한다 : 시간·Hibernate 준비 문장 수·JSON 크기(화면이 받는 형식)·할당 바이트. 같은 호출을 세 번 해 두 번째·세 번째 평균 (첫 번째는 준비).
 * 결과는 build/reports/m6-logical-model-management-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class LogicalModelManagementPerformanceTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("규모별 예전 화면(전체 그래프) vs 지금 화면(요약·페이지·엔터티 관계) (엔터티 100·1,000·3,000)")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("entities | call | requests | ms | statements | JSON KB | alloc MB");
        for (int size : new int[]{100, 1000, 3000}) {
            lines.addAll(run(size));
        }
        lines.forEach(line -> System.out.println("PERF|" + line));
        Path out = Path.of("build/reports/m6-logical-model-management-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private List<String> run(int entityCount) {
        GenerationFixture f = GenerationFixture.create(context, "lmng-perf-" + entityCount + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long base = LogicalErdPerformanceTest.seed(f, jdbc, entityCount);
        long projectId = f.user.getProjectId();
        List<Object[]> extra = new ArrayList<>();
        for (int i = 1; i < entityCount; i++) {
            if (i % 50 == 0) extra.add(new Object[]{base + 90_000 + i, projectId, f.modelId, base + i, base + i, "자기 " + i, false});
            if (i % 10 == 0) extra.add(new Object[]{base + 95_000 + i, projectId, f.modelId, base + i - 1, base + i, "사슬 둘째 " + i, true}); // 식별 관계 (식별 필터 측정)
        }
        jdbc.batchUpdate("insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, IDNTF_YN, ORGNL_MIN_CNT, TRGT_MIN_CNT)"
                + " values (?, ?, ?, ?, ?, ?, ?, 1, 0)", extra);

        int lastPage = (entityCount - 1) / 10;
        int lastRelationPage = lastRelationPage(f); // 재는 동안 요약을 부르지 않도록 미리
        List<String> lines = new ArrayList<>();
        lines.add(measure(entityCount, "예전 첫 화면 : 전체 그래프", () -> List.of(logical.selectGraph(f.user, f.modelId))));
        lines.add(measure(entityCount, "지금 첫 화면 : 요약 + 엔터티 10", () -> List.of(logical.selectSummary(f.user, f.modelId), grid(logical.selectEntityRows(f.user, f.modelId, null, 0, 10, null)))));
        lines.add(measure(entityCount, "엔터티 페이지 100", () -> List.of(grid(logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null)))));
        lines.add(measure(entityCount, "엔터티 마지막 페이지 (10)", () -> List.of(grid(logical.selectEntityRows(f.user, f.modelId, null, lastPage, 10, null)))));
        lines.add(measure(entityCount, "검색 \"0042\" (10)", () -> List.of(grid(logical.selectEntityRows(f.user, f.modelId, "0042", 0, 10, null)))));
        lines.add(measure(entityCount, "설명 내림차순 (10)", () -> List.of(grid(logical.selectEntityRows(f.user, f.modelId, null, 0, 10, "entityDescription,desc")))));
        lines.add(measure(entityCount, "관계 페이지 10", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, 0, 10)))));
        lines.add(measure(entityCount, "관계 검색 \"사슬 1\" (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, "사슬 1", null, 0, 10, null)))));
        lines.add(measure(entityCount, "관계 검색 출발·도착명 \"엔터티 0042\" (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, "엔터티 0042", null, 0, 10, null)))));
        lines.add(measure(entityCount, "관계 식별 필터 (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, null, true, 0, 10, null)))));
        lines.add(measure(entityCount, "관계 검색 + 비식별 (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, "허브", false, 0, 10, null)))));
        lines.add(measure(entityCount, "관계명 내림차순 (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 10, "relationshipName,desc")))));
        lines.add(measure(entityCount, "출발 엔터티명 오름차순 (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 10, "sourceEntityName,asc")))));
        lines.add(measure(entityCount, "도착 엔터티명 내림차순 마지막 페이지 (10)", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, null, null, lastRelationPage, 10, "targetEntityName,desc")))));
        lines.add(measure(entityCount, "관계 페이지 100", () -> List.of(grid(logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, "relationshipName,asc")))));
        lines.add(measure(entityCount, "예전 상세 (허브) : 엔터티 + 모델 관계 전체", () -> List.of(logical.selectEntity(f.user, base), logical.selectRelationships(f.user, f.modelId))));
        lines.add(measure(entityCount, "지금 상세 (허브) : 엔터티 + 엔터티 관계", () -> List.of(logical.selectEntity(f.user, base), logical.selectEntityRelationships(f.user, base))));
        if (entityCount == 3000) lines.addAll(plans(f.modelId, base, lastPage * 10));
        return lines;
    }

    /** PostgreSQL 실행 계획 (목록 쿼리와 같은 모양으로 손으로 쓴 SQL, 마지막 페이지 기준). H2 에서는 남기지 않는다 */
    private List<String> plans(long modelId, long base, int offset) {
        if (!jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName()).toLowerCase().contains("postgres")) return List.of();
        String ids = java.util.stream.LongStream.range(base + offset, base + offset + 10).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
        List<String> lines = new ArrayList<>();
        for (String sql : List.of(
                "select e.LGCL_ENTT_SN, e.ENTT_NM from TB_LGCL_ENTT e where e.LGCL_MDL_SN = " + modelId
                        + " and (lower(e.ENTT_NM) like '%%' escape '!' or lower(e.ENTT_EXPLN) like '%%' escape '!') order by e.ENTT_NM, e.LGCL_ENTT_SN offset " + offset + " limit 10",
                "select a.LGCL_ENTT_SN, count(*) from TB_LGCL_ATRB a where a.LGCL_ENTT_SN in (" + ids + ") group by a.LGCL_ENTT_SN",
                "select r.ORGNL_ENTT_SN, count(*) from TB_LGCL_REL r where r.ORGNL_ENTT_SN in (" + ids + ") group by r.ORGNL_ENTT_SN",
                "select r.TRGT_ENTT_SN, count(*) from TB_LGCL_REL r where r.TRGT_ENTT_SN in (" + ids + ") and r.ORGNL_ENTT_SN <> r.TRGT_ENTT_SN group by r.TRGT_ENTT_SN",
                // 관계 목록 (후속 3차 보완) : 검색 + 비식별 + 도착 엔터티명 내림차순 마지막 페이지
                "select r.LGCL_REL_SN from TB_LGCL_REL r join TB_LGCL_ENTT s on s.LGCL_ENTT_SN = r.ORGNL_ENTT_SN join TB_LGCL_ENTT t on t.LGCL_ENTT_SN = r.TRGT_ENTT_SN"
                        + " where r.LGCL_MDL_SN = " + modelId + " and (lower(r.REL_NM) like '%허브%' escape '!' or lower(s.ENTT_NM) like '%허브%' escape '!' or lower(t.ENTT_NM) like '%허브%' escape '!')"
                        + " and r.IDNTF_YN = false order by t.ENTT_NM desc nulls last, r.LGCL_REL_SN offset 140 limit 10",
                "select r.LGCL_REL_SN from TB_LGCL_REL r join TB_LGCL_ENTT s on s.LGCL_ENTT_SN = r.ORGNL_ENTT_SN join TB_LGCL_ENTT t on t.LGCL_ENTT_SN = r.TRGT_ENTT_SN"
                        + " where r.LGCL_MDL_SN = " + modelId + " order by s.ENTT_NM asc nulls last, r.LGCL_REL_SN offset 3400 limit 10")) {
            lines.add("PLAN | " + sql.replaceAll("in \\([^)]*\\)", "in (10 ids)"));
            jdbc.queryForList("explain analyze " + sql, String.class).forEach(line -> lines.add("PLAN |   " + line));
        }
        return lines;
    }

    private int lastRelationPage(GenerationFixture f) {
        return (int) ((logical.selectSummary(f.user, f.modelId).relationshipCount() - 1) / 10);
    }

    /** 화면이 받는 형식 ({ data, recordsTotal, recordsFiltered }) */
    private static Object grid(Page<?> page) {
        return PageableUtil.getGridPageableMap(page).getBody();
    }

    private String measure(int entityCount, String call, Supplier<List<Object>> action) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        action.get(); // 준비
        long ms = 0, statements = 0, allocated = 0;
        List<Object> result = List.of();
        for (int i = 0; i < 2; i++) {
            stats.clear();
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            result = action.get();
            ms += (System.nanoTime() - start) / 1_000_000;
            allocated += threads.getThreadAllocatedBytes(thread) - allocatedBefore;
            statements += stats.getPrepareStatementCount();
        }
        long bytes = 0;
        for (Object response : result) bytes += JSON.writeValueAsBytes(response).length;
        return entityCount + " | " + call + " | " + result.size() + " | " + ms / 2 + " | " + statements / 2 + " | " + String.format("%.1f", bytes / 1024.0) + " | " + allocated / 2 / (1024 * 1024);
    }
}
