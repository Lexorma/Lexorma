package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 논리 → 물리 생성 성능 측정 (M-7 보강 5차) — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다 (일반 빌드에서는 건너뜀).
 * 규모별(엔터티 × 속성) 모델을 만들고 미리보기·반영의 시간과 Hibernate SQL 수(준비된 문장·엔터티 로드·JPQL)를 잰다. JdbcTemplate 으로 실행한 SQL 은 세지 않는다.
 * 관계 : 엔터티마다 바로 앞 엔터티를 부모로(3번째마다 복합 PK 부모 → 복합 FK), 7번째마다 같은 부모로 관계 하나 더, 10번째마다 자기 참조. 결과는 build/reports/m7-generation-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class PhysicalGenerationPerformanceTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("규모별 미리보기·반영 시간과 SQL 수 (10×10, 100×20, 300×30)")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("size | entities | columns | relations(READY) | preview ms | preview stmts | preview loads | preview queries | apply ms | apply stmts | preview heap MB | per chunk");
        for (int[] size : new int[][]{{10, 10}, {100, 20}, {300, 30}}) {
            lines.add(run(size[0], size[1]));
            System.out.println("PERF|" + lines.get(lines.size() - 1));
        }
        Path out = Path.of("build/reports/m7-generation-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(int entityCount, int attributesPerEntity) {
        GenerationFixture f = GenerationFixture.create(context, "perf-" + entityCount + "x" + attributesPerEntity + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long setupStart = System.nanoTime();
        List<Long> entities = new ArrayList<>();
        List<long[]> keys = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            long entity = f.entity(name(i));
            entities.add(entity);
            boolean composite = i % 3 == 0;
            long k1 = f.attr(entity, "키1", true, "K1");
            long[] key = composite ? new long[]{k1, f.attr(entity, "키2", true, "K2")} : new long[]{k1};
            f.pk(entity, key);
            keys.add(key);
            int used = key.length;
            if (i > 0) {
                long[] parentKey = keys.get(i - 1);
                long[][] pairs = new long[parentKey.length][];
                for (int k = 0; k < parentKey.length; k++) pairs[k] = new long[]{parentKey[k], f.attr(entity, "부모" + (k + 1), true, "FA" + (k + 1))};
                f.pairs(f.rel("부모", entities.get(i - 1), entity, false, 1), pairs);
                used += parentKey.length;
                if (i % 7 == 0) {
                    long[][] second = new long[parentKey.length][];
                    for (int k = 0; k < parentKey.length; k++) second[k] = new long[]{parentKey[k], f.attr(entity, "보조부모" + (k + 1), false, "FB" + (k + 1))};
                    f.pairs(f.rel("보조 부모", entities.get(i - 1), entity, false, 0), second);
                    used += parentKey.length;
                }
            }
            if (i % 10 == 0) {
                long[][] self = new long[key.length][];
                for (int k = 0; k < key.length; k++) self[k] = new long[]{key[k], f.attr(entity, "상위" + (k + 1), false, "UP" + (k + 1))};
                f.pairs(f.rel("상위", entity, entity, false, 0), self);
                used += key.length;
            }
            for (int a = used; a < attributesPerEntity; a++) f.attr(entity, "속성" + a, false, String.format("C%02d", a));
        }
        System.out.println("PERF|setup " + entityCount + "x" + attributesPerEntity + " " + (System.nanoTime() - setupStart) / 1_000_000 + " ms");

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        // 한 요청은 엔터티 100개까지 (기존 정책) — 큰 모델은 100개씩 차례로 (뒤 묶음의 첫 엔터티는 앞 묶음에서 만든 기존 부모를 참조한다)
        List<String> parts = new ArrayList<>();
        long previewTotal = 0, applyTotal = 0, previewStatements = 0, applyStatements = 0, previewLoads = 0, previewQueries = 0, heapMb = 0;
        int relations = 0, ready = 0, columns = 0;
        String breakdown = "";
        for (int from = 0; from < entities.size(); from += 100) {
            List<Long> chunk = entities.subList(from, Math.min(entities.size(), from + 100));
            PhysicalGenerationDto.Request request = f.request(chunk);
            generation.preview(f.user, request); // 준비 (첫 호출의 클래스 로딩·캐시)
            System.gc();
            long heapBefore = usedHeap();
            stats.clear();
            long t0 = System.nanoTime();
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
            long previewMs = (System.nanoTime() - t0) / 1_000_000;
            heapMb = Math.max(heapMb, Math.max(0, usedHeap() - heapBefore) / (1024 * 1024));
            previewStatements += stats.getPrepareStatementCount();
            previewLoads += stats.getEntityLoadCount() + stats.getCollectionLoadCount();
            previewQueries += stats.getQueryExecutionCount();
            if (from == 0) breakdown = topQueries(stats) + " loads " + topLoads(stats);
            assertThat(plan.creatable()).as(plan.tables().stream().flatMap(t -> t.errors().stream()).limit(5).toList().toString()).isTrue();
            long chunkReady = plan.relations().stream().filter(PhysicalGenerationDto.RelationPlan::fkCreated).count();
            relations += plan.relations().size();
            ready += (int) chunkReady;

            stats.clear();
            long t1 = System.nanoTime();
            PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(chunk, null, plan.planToken()));
            long applyMs = (System.nanoTime() - t1) / 1_000_000;
            applyStatements += stats.getPrepareStatementCount();
            assertThat(result.tables()).isEqualTo(chunk.size());
            assertThat(result.foreignKeys()).isEqualTo(chunkReady);
            columns += result.columns();
            previewTotal += previewMs;
            applyTotal += applyMs;
            parts.add(previewMs + "/" + applyMs);
        }
        assertThat(f.foreignKeys()).isEqualTo(ready);
        System.out.println("PERF|queries " + entityCount + "x" + attributesPerEntity + " (첫 묶음 미리보기) " + breakdown);
        return entityCount + "x" + attributesPerEntity + " | " + entityCount + " | " + columns + " | " + relations + "(" + ready + ") | " + previewTotal + " | "
                + previewStatements + " | " + previewLoads + " | " + previewQueries + " | " + applyTotal + " | " + applyStatements + " | " + heapMb + " | 묶음별 미리보기/반영 ms " + parts;
    }

    /** 엔터티·컬렉션 로드(fetch) 상위 5개 — 지연 로딩 반복 확인용 */
    private static String topLoads(Statistics stats) {
        List<String> top = new ArrayList<>();
        java.util.stream.Stream.concat(
                java.util.Arrays.stream(stats.getEntityNames()).map(n -> java.util.Map.entry("E " + n.substring(n.lastIndexOf('.') + 1), stats.getEntityStatistics(n).getFetchCount())),
                java.util.Arrays.stream(stats.getCollectionRoleNames()).map(n -> java.util.Map.entry("C " + n.substring(n.lastIndexOf('.', n.lastIndexOf('.') - 1) + 1), stats.getCollectionStatistics(n).getFetchCount())))
                .filter(e -> e.getValue() > 0).sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(5).forEach(e -> top.add(e.getValue() + "× " + e.getKey()));
        return top.toString();
    }

    /** 실행 횟수가 많은 JPQL·조회 상위 6개 (N+1 확인용) */
    private static String topQueries(Statistics stats) {
        List<String> top = new ArrayList<>();
        java.util.Arrays.stream(stats.getQueries()).map(q -> java.util.Map.entry(q, stats.getQueryStatistics(q).getExecutionCount()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6).forEach(e -> top.add(e.getValue() + "× " + abbreviate(e.getKey())));
        return top.toString();
    }

    private static String name(int i) {
        // 서로 다른 단어 조합 → 서로 다른 테이블명 (단어 3개까지, 순서 있음)
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

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static String abbreviate(String sql) {
        String s = sql.replaceAll("\\s+", " ");
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
