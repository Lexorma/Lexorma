package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 논리 ERD 조회 성능 측정 (M-6 후속 1차) — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다 (일반 빌드에서는 건너뜀).
 * 엔터티 100·1,000·3,000개 모델 (엔터티마다 속성 8개·주 식별자 1개, 관계 = 바로 앞 엔터티 → 다음 엔터티 사슬 + 허브(첫 엔터티)에서 20번째마다)을 JDBC 로 넣고,
 * 전체 조회(관리 화면이 쓰는 selectGraph)와 부분 조회(범위 100·300, 이웃 1·2단계, 엔터티 찾기)의 시간·Hibernate 준비 문장 수·JSON 크기·할당 바이트를 잰다.
 * 같은 호출을 세 번 해 두 번째·세 번째의 평균을 쓴다 (첫 번째는 준비). 결과는 build/reports/m6-logical-erd-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class LogicalErdPerformanceTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int ATTRIBUTES = 8;

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("규모별 전체 조회 vs 부분 조회 (엔터티 100·1,000·3,000)")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("entities | call | ms | statements | JSON KB | alloc MB | entities in response | relationships in response");
        for (int size : new int[]{100, 1000, 3000}) {
            lines.addAll(run(size));
        }
        lines.forEach(line -> System.out.println("PERF|" + line));
        Path out = Path.of("build/reports/m6-logical-erd-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private List<String> run(int entityCount) {
        GenerationFixture f = GenerationFixture.create(context, "lerd-perf-" + entityCount + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long projectId = f.user.getProjectId();
        long base = 890_000_000L + (System.nanoTime() % 100_000L) * 100_000L;
        long term = f.term("PERF_ATTR", "VARCHAR", 20);
        List<Object[]> entities = new ArrayList<>(), attributes = new ArrayList<>(), identifiers = new ArrayList<>(), identifierAttributes = new ArrayList<>(), relationships = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            long entity = base + i;
            entities.add(new Object[]{entity, projectId, f.modelId, "엔터티 " + String.format("%05d", i), "성능 측정 엔터티 " + i});
            for (int a = 0; a < ATTRIBUTES; a++) {
                attributes.add(new Object[]{base + 10_000 + (long) i * ATTRIBUTES + a, entity, "속성 " + a, a + 1, term});
            }
            identifiers.add(new Object[]{base + 50_000 + i, entity, "식별자", true});
            identifierAttributes.add(new Object[]{base + 60_000 + i, base + 50_000 + i, base + 10_000 + (long) i * ATTRIBUTES, 1});
            if (i > 0) relationships.add(new Object[]{base + 70_000 + i, projectId, f.modelId, base + i - 1, entity, "사슬 " + i});
            if (i > 0 && i % 20 == 0) relationships.add(new Object[]{base + 80_000 + i, projectId, f.modelId, base, entity, "허브 " + i});
        }
        jdbc.batchUpdate("insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, ENTT_EXPLN) values (?, ?, ?, ?, ?)", entities);
        jdbc.batchUpdate("insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, SORT_ORD, STD_TRM_SN) values (?, ?, ?, ?, ?)", attributes);
        jdbc.batchUpdate("insert into TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN) values (?, ?, ?, ?)", identifiers);
        jdbc.batchUpdate("insert into TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values (?, ?, ?, ?)", identifierAttributes);
        jdbc.batchUpdate("insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, IDNTF_YN, ORGNL_MIN_CNT, TRGT_MIN_CNT)"
                + " values (?, ?, ?, ?, ?, ?, false, 1, 0)", relationships);

        long middle = base + entityCount / 2;
        List<String> lines = new ArrayList<>();
        lines.add(measure(entityCount, "전체 selectGraph", () -> logical.selectGraph(f.user, f.modelId)));
        lines.add(measure(entityCount, "범위 100 (첫 페이지)", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 100)));
        lines.add(measure(entityCount, "범위 300 (첫 페이지)", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 300)));
        lines.add(measure(entityCount, "검색 \"0042\"", () -> logical.selectGraphPart(f.user, f.modelId, "0042", 0, 100)));
        lines.add(measure(entityCount, "이웃 1단계 (허브)", () -> logical.selectNeighbors(f.user, f.modelId, base, 1)));
        lines.add(measure(entityCount, "이웃 2단계 (가운데)", () -> logical.selectNeighbors(f.user, f.modelId, middle, 2)));
        lines.add(measure(entityCount, "이웃 3단계 (허브)", () -> logical.selectNeighbors(f.user, f.modelId, base, 3)));
        lines.add(measure(entityCount, "엔터티 찾기 20", () -> logical.searchEntities(f.user, f.modelId, "엔터티", 20)));
        return lines;
    }

    private String measure(int entityCount, String call, Supplier<Object> action) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        action.get(); // 준비
        long ms = 0, statements = 0, allocated = 0;
        Object result = null;
        for (int i = 0; i < 2; i++) {
            stats.clear();
            long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            result = action.get();
            ms += (System.nanoTime() - start) / 1_000_000;
            allocated += threads.getThreadAllocatedBytes(thread) - allocatedBefore;
            statements += stats.getPrepareStatementCount();
        }
        byte[] json = JSON.writeValueAsBytes(result);
        int shownEntities = result instanceof LogicalModelDto.Graph g ? g.entities().size() : result instanceof LogicalModelDto.GraphPart p ? p.entities().size()
                : ((LogicalModelDto.EntityHits) result).items().size();
        int shownRelationships = result instanceof LogicalModelDto.Graph g ? g.relationships().size() : result instanceof LogicalModelDto.GraphPart p ? p.relationships().size() : 0;
        assertThat(shownEntities).isPositive();
        return entityCount + " | " + call + " | " + ms / 2 + " | " + statements / 2 + " | " + json.length / 1024 + " | " + allocated / 2 / (1024 * 1024)
                + " | " + shownEntities + " | " + shownRelationships;
    }
}
