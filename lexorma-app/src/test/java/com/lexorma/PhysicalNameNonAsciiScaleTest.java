package com.lexorma;

import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-NAME-1 최종 보완 NAME-F02 (측정용 — LEXORMA_PERF=true 일 때만) : 11,000 속성 모델에서 비 ASCII 이름(자바 판정 덮어쓰기 대상)을 100 · 1,000 · 5,000 · 10,000 건으로 늘리며
 * 정확성(행 상태 = 상태 필터 = 요약, 페이지를 모두 모으면 빠짐·중복 없음)과 조회 시간(5회 중앙값·최댓값)·SQL 수·덮어쓰기 IN 목록 길이·바인딩 수·힙 변화를 잰다.
 * 비 ASCII 행의 절반은 일치(É ↔ é), 절반은 추천 가능. 결과는 build/reports/m7-name1-nonascii.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class PhysicalNameNonAsciiScaleTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired PhysicalNameRenameService rename;
    @Autowired com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository repository;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("비 ASCII 100·1,000·5,000·10,000 건 : 정확성과 조회 비용")
    void measure() throws IOException {
        GenerationFixture f = GenerationFixture.create(context, "nonascii-" + System.nanoTime(), com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < 220; i++) {
            long entity = f.entity(name(i));
            entities.add(entity);
            f.pk(entity, f.attr(entity, "키", true, "K1"));
            for (int a = 1; a < 50; a++) f.attr(entity, "속성" + a, false, String.format("C%02d", a));
        }
        for (int from = 0; from < entities.size(); from += 100) {
            List<Long> chunk = entities.subList(from, Math.min(entities.size(), from + 100));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chunk));
            generation.apply(f.user, new PhysicalGenerationDto.Request(chunk, null, plan.planToken()));
        }
        long projectId = f.user.getProjectId();
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM where PRJ_SN = ?", projectId);
        // 매핑된 컬럼 (속성 ID 순) : 비 ASCII 로 바꿀 순서
        List<Map<String, Object>> mapped = jdbc.queryForList("select m.COL_SN, m.LGCL_ATRB_SN, a.STD_TRM_SN, t.TRM_ENG_ABBR_NM from TB_ATRB_COL_MPNG m join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = m.LGCL_ATRB_SN"
                + " join TB_STD_TRM t on t.STD_TRM_SN = a.STD_TRM_SN where t.PRJ_SN = ? order by m.LGCL_ATRB_SN", projectId);

        List<String> lines = new ArrayList<>();
        lines.add("non-ascii | attributes | first p | last p | AVAILABLE p1 · total | ALREADY_MATCHED p1 · total | UNAVAILABLE p1 | CONFLICT p1 | summary | search | sort | preview 100"
                + " | override ids (max IN) | max binds | heap MB (after → peak) | path | SQL-override filter p1 | judged filter p1 | SQL-override summary | judged summary");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        int done = 0;
        for (int target : new int[]{100, 250, 400, 499, 500, 750, 1_000, 2_000, 5_000, 10_000}) {
            for (; done < target; done++) {
                Map<String, Object> row = mapped.get(done);
                String abbreviation = (String) row.get("TRM_ENG_ABBR_NM");
                if (done % 2 == 0) {
                    // 일치 (자바 equalsIgnoreCase : É ↔ é) — 약어는 그 속성만의 용어라 다른 행에 영향 없음
                    jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", abbreviation + "_É" + done, row.get("STD_TRM_SN"));
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", (abbreviation + "_é" + done).toLowerCase(), row.get("COL_SN"));
                } else {
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "O_" + abbreviation + "_é" + done, row.get("COL_SN"));
                }
            }
            lines.add(run(f, stats, target, mapped));
            System.out.println("NONASCII|" + lines.get(lines.size() - 1));
        }
        Path out = Path.of("build/reports/m7-name1-nonascii.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(GenerationFixture f, Statistics stats, int complex, List<Map<String, Object>> mapped) {
        ColumnSearch model = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long base = runtime.totalMemory() - runtime.freeMemory();
        long[] peak = {base};
        java.util.function.Consumer<Object> sample = x -> peak[0] = Math.max(peak[0], runtime.totalMemory() - runtime.freeMemory());

        // 정확성 : 행 상태(자바) = 상태 필터 = 요약, 페이지 모음 = 필터 결과 (빠짐·중복 없음)
        List<ColumnRow> all = recommendation.selectColumnRows(f.user, model, PageRequest.of(0, 20_000)).getContent();
        long attributes = all.size();
        Map<Status, Set<Long>> byStatus = all.stream().collect(Collectors.groupingBy(ColumnRow::status, Collectors.mapping(ColumnRow::logicalAttributeId, Collectors.toSet())));
        ColumnSummary summary = recommendation.selectColumnSummary(f.user, f.modelId);
        assertThat(summary.total()).isEqualTo(attributes);
        Map<Status, Long> counted = Map.of(Status.AVAILABLE, summary.available(), Status.ALREADY_MATCHED, summary.alreadyMatched(), Status.UNAVAILABLE, summary.unavailable(),
                Status.CONFLICT, summary.conflict());
        for (Status status : Status.values()) {
            Set<Long> want = byStatus.getOrDefault(status, Set.of());
            assertThat(counted.get(status)).as("요약 " + status).isEqualTo(want.size());
            List<Long> paged = new ArrayList<>();
            int page = 0;
            Page<ColumnRow> p;
            do {
                p = recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, status, null, null), PageRequest.of(page++, 1_000));
                assertThat(p.getTotalElements()).isEqualTo(want.size());
                p.getContent().forEach(r -> {
                    assertThat(r.status()).isEqualTo(status);
                    paged.add(r.logicalAttributeId());
                });
            } while (p.hasNext());
            assertThat(paged).as("페이지 모음 " + status).hasSize(want.size()).doesNotHaveDuplicates();
            assertThat(new HashSet<>(paged)).isEqualTo(want);
        }
        // 비 ASCII 행 판정 : 짝수 = 일치, 홀수 = 추천 가능 (자바 기준)
        for (int i = 0; i < complex; i++) {
            Long attribute = ((Number) mapped.get(i).get("LGCL_ATRB_SN")).longValue();
            assertThat(byStatus.get(i % 2 == 0 ? Status.ALREADY_MATCHED : Status.AVAILABLE)).as("속성 " + attribute).contains(attribute);
        }

        int lastPage = (int) ((attributes - 1) / 100);
        String first = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, model, PageRequest.of(0, 100)));
        String last = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, model, PageRequest.of(lastPage, 100)));
        String available = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, null, null), PageRequest.of(0, 100)))
                + " · " + byStatus.getOrDefault(Status.AVAILABLE, Set.of()).size();
        String matched = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.ALREADY_MATCHED, null, null), PageRequest.of(0, 100)))
                + " · " + byStatus.getOrDefault(Status.ALREADY_MATCHED, Set.of()).size();
        String unavailable = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.UNAVAILABLE, null, null), PageRequest.of(0, 100)));
        String conflict = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.CONFLICT, null, null), PageRequest.of(0, 100)));
        String sum = measure(stats, sample, () -> recommendation.selectColumnSummary(f.user, f.modelId));
        String search = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, "currentPhysicalName", "é"),
                PageRequest.of(0, 100)));
        String sort = measure(stats, sample, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, null, null),
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "currentPhysicalName"))));
        List<PhysicalNameRenameDto.Item> items = all.stream().filter(r -> r.status() == Status.AVAILABLE && r.currentPhysicalName() != null && r.currentPhysicalName().contains("é"))
                .limit(100).map(r -> new PhysicalNameRenameDto.Item(PhysicalNameRenameDto.TargetType.COLUMN, r.physicalColumnId(), r.recommendedPhysicalName(), null)).toList();
        String preview = measure(stats, sample, () -> rename.preview(f.user, new PhysicalNameRenameDto.Request(items, null)));
        // 덮어쓰기 목록 : 비 ASCII 속성 ID 전체(not in) + 한 상태로 판정된 ID (in) — 상태 조건 문장의 바인딩 = 둘의 합 + 모델·프로젝트 등
        int overrideIds = complex;
        int maxIn = Math.max(complex, Math.max(byStatusComplex(byStatus, mapped, complex, Status.ALREADY_MATCHED), byStatusComplex(byStatus, mapped, complex, Status.AVAILABLE)));
        int maxBinds = complex + Math.max(byStatusComplex(byStatus, mapped, complex, Status.ALREADY_MATCHED), byStatusComplex(byStatus, mapped, complex, Status.AVAILABLE)) + 4;
        // 예전 방식(저장소 SQL + 덮어쓰기 목록)과 같은 쪽·건수
        for (Status status : Status.values()) {
            ColumnSearch s = new ColumnSearch(f.modelId, null, null, null, status, null, null);
            com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository.Overrides o = call("overrides", f.user.getProjectId(), s);
            for (Sort order : List.of(Sort.unsorted(), Sort.by(Sort.Direction.DESC, "currentPhysicalName"))) {
                long total = byStatus.getOrDefault(status, Set.of()).size();
                for (int pg : new int[]{0, (int) (total / 100 / 2), (int) Math.max(0, (total - 1) / 100)}) {
                    PageRequest request = PageRequest.of(pg, 100, order);
                    Page<com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource> want = repository.findColumnSources(f.user.getProjectId(), s, request, o);
                    Page<ColumnRow> got = recommendation.selectColumnRows(f.user, s, request);
                    assertThat(got.getContent().stream().map(ColumnRow::logicalAttributeId).toList()).as("예전 방식과 같은 쪽 " + status + " " + order + " " + pg)
                            .containsExactlyElementsOf(want.getContent().stream().map(com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource::attributeId).toList());
                    assertThat(got.getTotalElements()).isEqualTo(want.getTotalElements());
                }
            }
        }

        // 두 판정 방식을 따로 (F02 기준값 근거) : SQL 덮어쓰기 = 덮어쓰기 목록 + 쪽·건수 SQL, 자바 판정 = 전체 원본 + 자바 판정 + 쪽 자르기 (행 만들기는 두 방식 공통이라 뺐다)
        ColumnSearch availableSearch = new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, null, null);
        String sqlFilter = measure(stats, sample, () -> repository.findColumnSources(f.user.getProjectId(), availableSearch, PageRequest.of(0, 100), call("overrides", f.user.getProjectId(), availableSearch)));
        String judgedFilter = measure(stats, sample, () -> call("judgedPage", f.user.getProjectId(), availableSearch, PageRequest.of(0, 100)));
        String sqlSummary = measure(stats, sample, () -> {
            com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository.Overrides o = call("overrides", f.user.getProjectId(), model);
            long n = 0;
            for (Status st : Status.values()) n += repository.countColumns(f.user.getProjectId(), f.modelId, st, o);
            return n;
        });
        String judgedSummary = measure(stats, sample, () -> call("judgeStatuses", repository.findAllColumnSources(f.user.getProjectId(), model, Sort.unsorted())));
        Object plan = call("statusPlan", f.user.getProjectId(), model);
        String path = plan.toString().contains("javaJudged=true") ? "JAVA" : "SQL";
        return String.join(" | ", String.valueOf(complex), String.valueOf(attributes), first, last, available, matched, unavailable, conflict, sum, search, sort, preview,
                overrideIds + " (" + maxIn + ")", String.valueOf(maxBinds), (base / 1_048_576) + " → " + (peak[0] / 1_048_576), path, sqlFilter, judgedFilter, sqlSummary, judgedSummary);
    }

    /** 서비스의 패키지 전용 메서드 (판정 방식별 측정) */
    @SuppressWarnings("unchecked")
    private <T> T call(String name, Object... args) {
        for (java.lang.reflect.Method m : PhysicalNameRecommendationService.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    m.setAccessible(true);
                    return (T) m.invoke(recommendation, args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static int byStatusComplex(Map<Status, Set<Long>> byStatus, List<Map<String, Object>> mapped, int complex, Status status) {
        Set<Long> ids = byStatus.getOrDefault(status, Set.of());
        int n = 0;
        for (int i = 0; i < complex; i++) if (ids.contains(((Number) mapped.get(i).get("LGCL_ATRB_SN")).longValue())) n++;
        return n;
    }

    /** 준비 1회 뒤 5회 : 중앙값/최댓값 ms (마지막 회 SQL 수) */
    private static String measure(Statistics stats, java.util.function.Consumer<Object> sample, Supplier<Object> work) {
        sample.accept(work.get());
        long[] times = new long[5];
        long statements = 0;
        for (int i = 0; i < 5; i++) {
            stats.clear();
            long start = System.nanoTime();
            Object result = work.get();
            times[i] = (System.nanoTime() - start) / 1_000_000;
            statements = stats.getPrepareStatementCount();
            sample.accept(result);
        }
        Arrays.sort(times);
        return times[2] + "/" + times[4] + " (" + statements + ")";
    }

    /** 엔터티명 = 서로 다른 표준 단어 1~3개 (PhysicalNameRecommendationPerformanceTest 와 같은 규칙 — 테이블명 생성이 단어를 찾는다) */
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
