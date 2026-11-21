package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
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
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 자동 매핑 후보·ERD 매핑 표시 성능 (M-7 자동 매핑 후보·ERD 연계) — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다.
 * 엔터티 N개(속성 10, 앞 엔터티를 부모로 하는 관계 + 짝, 5번째마다 보조 식별자)를 논리 → 물리 생성으로 만든 뒤 엔터티 1개의 매핑과 다른 엔터티 1개의 속성 매핑 절반을 지우고
 * 테이블 후보 · 컬럼 후보 · 논리 ERD 표시(엔터티 전부, 최대 300) · 물리 ERD 표시(테이블 전부, 최대 300)의 시간(5회 중앙값)과 Hibernate 준비 문장 수를 잰다.
 * 결과는 build/reports/m7-mapping-suggestion-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class MappingSuggestionPerformanceTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired com.lexorma.data.mapping.service.MappingSuggestionService suggestions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("엔터티 100 / 300 : 테이블 후보·컬럼 후보·논리 ERD 표시·물리 ERD 표시")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("entities | table suggestions ms (stmts) | column suggestions ms (stmts) | logical overlay ms (stmts) | physical overlay ms (stmts)");
        for (int n : new int[]{100, 300}) {
            lines.add(run(n));
            System.out.println("PERF|" + lines.getLast());
        }
        Path out = Path.of("build/reports/m7-mapping-suggestion-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(int n) {
        GenerationFixture f = GenerationFixture.create(context, "msgperf-" + n + "-" + System.nanoTime(), DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        List<Long> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long e = f.entity(name(i));
            entities.add(e);
            long key = f.attr(e, "키", true, "K00");
            f.pk(e, key);
            keys.add(key);
            List<Long> attrs = new ArrayList<>();
            for (int a = 1; a < 9; a++) attrs.add(f.attr(e, "속성" + a, false, String.format("C%02d", a)));
            if (i > 0) {
                long ref = f.attr(e, "부모 키", false, "PK0");
                LogicalModelDto.Relationship rel = f.rel("부모", entities.get(i - 1), e, false, 0);
                f.pairs(rel, new long[]{keys.get(i - 1), ref});
            }
            if (i % 5 == 0) context.getBean(com.lexorma.data.logical.service.LogicalModelService.class)
                    .saveIdentifier(f.user, new LogicalModelDto.Identifier(null, e, "보조", false, List.of(attrs.get(0), attrs.get(1)), null));
        }
        for (int from = 0; from < n; from += 100) {
            List<Long> chunk = entities.subList(from, Math.min(n, from + 100));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chunk));
            generation.apply(f.user, f.request(chunk, plan.planToken()));
        }
        // 엔터티 0 : 매핑을 지워 테이블 후보 대상으로, 엔터티 1 : 속성 매핑 절반을 지워 컬럼 후보 대상으로
        long target = entities.get(0);
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN in (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ?)", target);
        jdbc.update("delete from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", target);
        long half = entities.get(1);
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN in (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ? and SORT_ORD > 4)", half);
        Long mappingId = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, half);
        List<Long> shownEntities = entities.subList(0, Math.min(300, n));
        List<Long> shownTables = jdbc.queryForList("select TBL_SN from TB_TBL where PRJ_SN = ? order by TBL_SN", Long.class, f.user.getProjectId());
        List<Long> tables = shownTables.subList(0, Math.min(300, shownTables.size()));
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        String table = measure(stats, () -> suggestions.tableSuggestions(f.user, target, null), false);
        String column = measure(stats, () -> suggestions.attributeSuggestions(f.user, mappingId), false);
        String logical = measure(stats, () -> suggestions.entityOverlays(f.user, f.modelId, shownEntities), false);
        String physical = measure(stats, () -> suggestions.tableOverlays(f.user, tables), false);
        return n + " | " + table + " | " + column + " | " + logical + " | " + physical;
    }

    private static String measure(Statistics stats, Supplier<Object> call, boolean memory) {
        call.get();
        long[] times = new long[5];
        long statements = 0;
        long allocated = 0;
        for (int i = 0; i < times.length; i++) {
            stats.clear();
            long a0 = ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            call.get();
            times[i] = (System.nanoTime() - t0) / 1_000_000;
            allocated = ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes() - a0;
            statements = stats.getPrepareStatementCount();
        }
        Arrays.sort(times);
        return times[2] + " (" + statements + (memory ? ", " + allocated / (1024 * 1024) : "") + ")";
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
