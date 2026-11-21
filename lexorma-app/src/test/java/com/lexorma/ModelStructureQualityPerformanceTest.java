package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
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
import org.springframework.data.domain.PageRequest;
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
 * 구조 정합성 진단 성능 (M-7 구조 정합성) — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다.
 * 엔터티 N개(속성 10, 앞 엔터티를 부모로 하는 관계 + 짝, 5번째마다 보조 식별자)를 논리 → 물리 생성으로 만든 뒤
 * 요약 · 예전 목록(그룹 없음) · 관계 범위 · 전체 범위 한 쪽(100행)의 시간(5회 중앙값)과 Hibernate 준비 문장 수, 이 스레드의 할당량을 잰다.
 * 결과는 build/reports/m7-structure-quality-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class ModelStructureQualityPerformanceTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingQualityService quality;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("엔터티 100 / 300 : 요약·예전 목록·관계 범위·전체 범위")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("entities | relationships | summary ms (stmts, MB) | legacy page ms (stmts) | relationship page ms (stmts) | all page ms (stmts)");
        for (int n : new int[]{100, 300}) {
            lines.add(run(n));
            System.out.println("PERF|" + lines.getLast());
        }
        Path out = Path.of("build/reports/m7-structure-quality-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(int n) {
        GenerationFixture f = GenerationFixture.create(context, "msqperf-" + n + "-" + System.nanoTime(), DbType.POSTGRESQL);
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
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        String summary = measure(stats, () -> quality.selectSummary(f.user, f.modelId), true);
        String legacy = measure(stats, () -> quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null), PageRequest.of(0, 100)), false);
        String rel = measure(stats, () -> quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.RELATIONSHIP), PageRequest.of(0, 100)), false);
        String all = measure(stats, () -> quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 100)), false);
        return n + " | " + (n - 1) + " | " + summary + " | " + legacy + " | " + rel + " | " + all;
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
