package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Item;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.PlanItem;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Result;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import com.lexorma.data.version.service.ModelVersionService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 물리명 추천 일괄 적용 2차 성능 측정 — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다 (일반 빌드에서는 건너뜀).
 * 엔터티 60 × 속성 10 모델(테이블 60·컬럼 600)을 생성한 뒤 이름을 모두 O_ 로 바꾸고, 대상 1 / 100 / 500 (테이블 10% · 컬럼 90%)을 적용한다.
 * 잰 것 : 미리보기, 적용(커밋 전 — 잠금·재계획·버전·이름 변경·flush), 커밋(이력 저장 포함), 같은 모델의 버전 생성 단독(롤백), Hibernate 준비 문장 수, 이 스레드의 할당량(누적 — 상주 메모리가 아니다).
 * 결과는 build/reports/m7-rename-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class PhysicalNameRenamePerformanceTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRenameService rename;
    @Autowired ModelVersionService versions;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("대상 1 / 100 / 500 : 미리보기·적용·커밋·버전 생성 시간, SQL 수, 할당량")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("items | tables/columns | preview ms (stmts) | apply ms before commit (stmts) | commit ms | version alone ms | total ms | preview alloc MB | apply alloc MB");
        for (int size : new int[]{1, 100, 500}) {
            lines.add(run(size));
            System.out.println("PERF|" + lines.get(lines.size() - 1));
        }
        Path out = Path.of("build/reports/m7-rename-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(int size) {
        GenerationFixture f = GenerationFixture.create(context, "rnperf-" + size + "-" + System.nanoTime(), DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long e = f.entity(name(i));
            entities.add(e);
            f.pk(e, f.attr(e, "키", true, "K00"));
            for (int a = 1; a < 10; a++) f.attr(e, "속성" + a, false, String.format("C%02d", a));
        }
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(entities));
        generation.apply(f.user, f.request(entities, plan.planToken()));
        long projectId = f.user.getProjectId();
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM, TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);

        int tables = size == 1 ? 1 : size / 10;
        int columns = size - tables;
        List<Item> items = new ArrayList<>();
        List<Map<String, Object>> tableRows = jdbc.queryForList("select TBL_SN, TBL_NM from TB_TBL where PRJ_SN = ? order by TBL_SN", projectId);
        for (int i = 0; i < tables; i++) {
            items.add(new Item(TargetType.TABLE, ((Number) value(tableRows.get(i), "TBL_SN")).longValue(), ((String) value(tableRows.get(i), "TBL_NM")).substring(2), null));
        }
        List<Map<String, Object>> columnRows = jdbc.queryForList("select COL_SN, COL_NM from TB_COL where PRJ_SN = ? order by COL_SN", projectId);
        for (int i = 0; i < columns; i++) {
            items.add(new Item(TargetType.COLUMN, ((Number) value(columnRows.get(i), "COL_SN")).longValue(), ((String) value(columnRows.get(i), "COL_NM")).substring(2), null));
        }

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        rename.preview(f.user, new Request(items, null)); // 데우기
        stats.clear();
        long alloc0 = allocated();
        long t0 = System.nanoTime();
        Plan preview = rename.preview(f.user, new Request(items, null));
        long previewMs = ms(t0);
        long previewAlloc = allocated() - alloc0;
        long previewStatements = stats.getPrepareStatementCount();
        assertThat(preview.applicable()).as(preview.items().stream().filter(i -> !i.applicability().selectable()).limit(3).toList().toString()).isTrue();

        // 같은 모델의 버전 생성 단독 (롤백 — 적용 안에서 드는 시간의 근사)
        long tv = System.nanoTime();
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select PRJ_SN from TB_PRJ where PRJ_SN = ? for update", Long.class, projectId);
            versions.createBeforeModelChange(projectId, "PERF-" + System.nanoTime(), null, "perf");
            status.setRollbackOnly();
        });
        long versionMs = ms(tv);

        List<Item> withBase = new ArrayList<>();
        for (Item item : items) {
            PlanItem p = preview.items().stream().filter(i -> i.type() == item.type() && i.id().equals(item.id())).findFirst().orElseThrow();
            withBase.add(new Item(item.type(), item.id(), item.recommendedName(), p.modifiedAt()));
        }
        stats.clear();
        long alloc1 = allocated();
        long[] applyMs = new long[1];
        long t1 = System.nanoTime();
        Result result = tx.execute(status -> {
            long inner = System.nanoTime();
            Result r = rename.apply(f.user, new Request(withBase, preview.planToken()));
            applyMs[0] = ms(inner);
            return r;
        });
        long totalMs = ms(t1);
        long applyAlloc = allocated() - alloc1;
        assertThat(result.total()).isEqualTo(size);
        return size + " | " + tables + "/" + columns + " | " + previewMs + " (" + previewStatements + ") | " + applyMs[0] + " (" + stats.getPrepareStatementCount() + " incl. commit) | "
                + (totalMs - applyMs[0]) + " | " + versionMs + " | " + totalMs + " | " + previewAlloc / (1024 * 1024) + " | " + applyAlloc / (1024 * 1024);
    }

    private Object value(Map<String, Object> row, String column) {
        return row.containsKey(column) ? row.get(column) : row.get(column.toLowerCase());
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String name(int i) {
        int n = WORDS.length;
        if (i < n) return WORDS[i];
        int j = i - n;
        int a = j / (n - 1);
        int b = j % (n - 1);
        return WORDS[a] + " " + WORDS[b >= a ? b + 1 : b];
    }
}
