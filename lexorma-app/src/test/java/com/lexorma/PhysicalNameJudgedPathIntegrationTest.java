package com.lexorma;

import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository.Overrides;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-NAME-F02 : 자바로 판정할 행(비 ASCII·탭 등)이 기준값({@code javaJudgeMinRows} = 10,000) 바로 아래면 예전 SQL 덮어쓰기 경로,
 * 기준값이면 전체 자바 판정 경로를 쓴다. 서비스(두 경로)와 직접 부른 자바 판정 경로의 상태 필터 쪽·건수·요약이 예전 방식(저장소 SQL + 덮어쓰기 목록)과 행 ID·순서까지 같은지 — 상태 4 × 정렬 5 × 쪽 크기 10·100 ×
 * 첫·중간·마지막·빈 쪽, 이름 검색, 요약, 다른 프로젝트 격리.
 * 비 ASCII 행에는 일치(É ↔ é), 추천 가능, 충돌(같은 테이블의 다른 컬럼이 비 ASCII 추천명을 쓴다), 추천 불가(약어가 공백·탭뿐), 끝 탭 일치(F01)가 섞인다.
 * DB-1 3차 (postgresql-only-migration.md 49장) : 예전에는 H2 의 작은 기준값(500)으로 서비스가 자바 판정 경로로 넘어가는 쪽을 확인했다. 지금은 PostgreSQL 에서
 * 운영 기준값 그대로 9,999 · 10,000 행을 만들어 서비스의 두 경로를 모두 실제로 지난다 (기준값·운영 코드는 바꾸지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameJudgedPathIntegrationTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired PhysicalNameRecommendationQueryRepository repository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("자바 판정 행 999 (기준값 아래 — SQL 덮어쓰기) : 서비스와 직접 부른 자바 판정 경로의 상태 필터 쪽·건수·요약이 예전 방식과 같다 (전체 비교)")
    void belowThreshold() throws Exception {
        int min = (Integer) call("javaJudgeMinRows");
        assertThat(min).as("운영 기준값 (이 테스트는 바꾸지 않고 그대로 쓴다)").isEqualTo(10_000);
        run(999, false);
    }

    @Test
    @DisplayName("운영 기준값 경계 9,999 · 10,000 : 서비스가 기준값에서 자바 판정 경로로 넘어가고 (경로 표시·요약·상태 필터), 쪽·건수가 예전 방식과 같다")
    void serviceSwitchesAtThreshold() throws Exception {
        run((Integer) call("javaJudgeMinRows") - 1, true);
    }

    /**
     * 자바 판정 행 below 개를 만들고 비교한다. atThreshold = below 가 기준값 - 1 : 그 상태(SQL 덮어쓰기)에서 요약을, 한 행 더해 기준값(자바 판정)에서 서비스의 쪽·건수를
     * 줄인 범위(상태 4 × 정렬 5 × 쪽 크기 100 × 첫·마지막·빈 쪽 + 검색 2)로 비교한다 — 10,000행이라 쪽 한 번이 약 1초, 전체 범위는 소규모(belowThreshold)에서 본다
     */
    private void run(int below, boolean atThreshold) throws Exception {
        int min = (Integer) call("javaJudgeMinRows");
        int entities = (below + 250) / 50 + 1;

        GenerationFixture f = model("judged-" + System.nanoTime(), entities);
        long projectId = f.user.getProjectId();
        // 다른 프로젝트에도 비 ASCII 행 (이 프로젝트의 판정·건수에 섞이지 않아야 한다)
        GenerationFixture other = model("judged-other-" + System.nanoTime(), 2);
        jdbc.update("update TB_COL set COL_NM = COL_NM || '_ü' where PRJ_SN = ?", other.user.getProjectId());

        // ASCII 행 : 2/3 추천 가능 (O_ 접두어), 1/3 일치 (생성된 이름 그대로)
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM where PRJ_SN = ? and mod(COL_SN, 3) <> 0", projectId);
        List<Map<String, Object>> mapped = jdbc.queryForList("select m.COL_SN, c.TBL_SN, m.LGCL_ATRB_SN, a.STD_TRM_SN, t.TRM_ENG_ABBR_NM from TB_ATRB_COL_MPNG m"
                + " join TB_COL c on c.COL_SN = m.COL_SN join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = m.LGCL_ATRB_SN"
                + " join TB_STD_TRM t on t.STD_TRM_SN = a.STD_TRM_SN where t.PRJ_SN = ? order by m.LGCL_ATRB_SN", projectId);
        // 끝쪽 행부터 비 ASCII 행을 섞는다
        int next = 0;
        int patterns = below - 50;
        // 같은 내용의 단건 UPDATE 를 순서대로 모아 배치로 보낸다 (행 수가 많아 — 문장·순서는 예전과 같다)
        List<Object[]> terms = new ArrayList<>();
        List<Object[]> cols = new ArrayList<>();
        for (; next < patterns; next++) {
            Map<String, Object> row = mapped.get(mapped.size() - 1 - next);
            String abbreviation = (String) row.get("TRM_ENG_ABBR_NM");
            switch (next % 5) {
                case 0 -> { // 일치 (É ↔ é)
                    terms.add(new Object[]{abbreviation + "_É" + next, row.get("STD_TRM_SN")});
                    cols.add(new Object[]{(abbreviation + "_é" + next).toLowerCase(), row.get("COL_SN")});
                }
                case 1 -> // 추천 가능 (컬럼명만 비 ASCII)
                        cols.add(new Object[]{"한" + abbreviation + next, row.get("COL_SN")});
                case 2 -> { // 충돌 : 같은 테이블의 다른 컬럼(바로 앞 속성의 컬럼)이 비 ASCII 추천명을 쓴다
                    Map<String, Object> neighbour = mapped.get(mapped.size() - 1 - next + 1);
                    terms.add(new Object[]{"충돌_" + next, row.get("STD_TRM_SN")});
                    if (neighbour.get("TBL_SN").equals(row.get("TBL_SN"))) {
                        cols.add(new Object[]{"충돌_" + next, neighbour.get("COL_SN")});
                    }
                }
                case 3 -> { // 추천 불가 : 약어가 공백·탭뿐, 컬럼명 비 ASCII
                    terms.add(new Object[]{" \t ", row.get("STD_TRM_SN")});
                    cols.add(new Object[]{"ü_" + next, row.get("COL_SN")});
                }
                default -> { // 끝 탭 일치 (F01 : 자바 trim)
                    terms.add(new Object[]{"TAB_" + next + "\t", row.get("STD_TRM_SN")});
                    cols.add(new Object[]{"tab_" + next, row.get("COL_SN")});
                }
            }
        }
        jdbc.batchUpdate("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", terms);
        jdbc.batchUpdate("update TB_COL set COL_NM = ? where COL_SN = ?", cols);
        ColumnSearch model = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        // 기준값 - 1 까지 하나씩 (앞쪽 ASCII 행을 비 ASCII 추천 가능 행으로 — 위 끝쪽 구간과 겹치지 않는다)
        int complex = repository.findComplexColumnSources(projectId, model).size();
        int plainIndex = 0;
        // 앞쪽 ASCII 행 하나 = 자바 판정 행 하나 : 모자란 만큼 한 번에 바꾸고, 남는 차이는 예전처럼 하나씩 다시 센다
        List<Object[]> front = new ArrayList<>();
        for (; plainIndex < below - complex; plainIndex++) front.add(new Object[]{"é_" + plainIndex, mapped.get(plainIndex).get("COL_SN")});
        jdbc.batchUpdate("update TB_COL set COL_NM = ? where COL_SN = ?", front);
        complex = repository.findComplexColumnSources(projectId, model).size();
        while (complex < below) {
            jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "é_" + plainIndex, mapped.get(plainIndex).get("COL_SN"));
            plainIndex++;
            complex = repository.findComplexColumnSources(projectId, model).size();
        }
        assertThat(complex).isEqualTo(below);
        assertThat(javaJudged(projectId, model)).as("기준값 아래 = SQL 덮어쓰기").isFalse();
        if (!atThreshold) {
            compare(f, false, true);
            compare(f, true, true);
        } else {
            summary(f, false);
            jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "é_" + plainIndex, mapped.get(plainIndex).get("COL_SN"));
            assertThat(repository.findComplexColumnSources(projectId, model)).hasSize(min);
            assertThat(javaJudged(projectId, model)).as("기준값 = 자바 판정 (서비스 경로)").isTrue();
            compare(f, false, false);
        }
        // 다른 프로젝트 : 자기 행만 센다
        assertThat(recommendation.selectColumnSummary(other.user, other.modelId).total()).isEqualTo(100);
    }

    /** 서비스 결과 = 예전 방식 (저장소 SQL 상태 식 + 덮어쓰기 목록). forced = 서비스 대신 자바 판정 경로(judgedPage · judgeStatuses)를 직접, full = 쪽 크기 10·100 × 첫·중간·마지막·빈 쪽 (아니면 100 × 첫·마지막·빈 쪽) */
    private void compare(GenerationFixture f, boolean forced, boolean full) throws Exception {
        long projectId = f.user.getProjectId();
        summary(f, forced);

        List<Sort> sorts = List.of(Sort.unsorted(), Sort.by(Sort.Direction.ASC, "currentPhysicalName"), Sort.by(Sort.Direction.DESC, "logicalName"),
                Sort.by(Sort.Direction.ASC, "recommendedPhysicalName"), Sort.by(Sort.Direction.DESC, "entityName"));
        for (Status status : Status.values()) {
            ColumnSearch search = new ColumnSearch(f.modelId, null, null, null, status, null, null);
            Overrides o = overrides(projectId, search);
            for (Sort sort : sorts) {
                for (int size : full ? new int[]{10, 100} : new int[]{100}) {
                    long count = repository.findColumnSources(projectId, search, PageRequest.of(0, size, sort), o).getTotalElements();
                    int pages = (int) ((count + size - 1) / size);
                    for (int page : full ? new int[]{0, pages / 2, Math.max(pages - 1, 0), pages + 1} : new int[]{0, Math.max(pages - 1, 0), pages + 1}) {
                        same(f, search, o, PageRequest.of(page, size, sort), status + "/" + sort + " " + page + "×" + size, forced);
                    }
                }
            }
        }
        // 이름 검색 + 상태
        ColumnSearch search = new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, "currentPhysicalName", "é");
        same(f, search, overrides(projectId, search), PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "currentPhysicalName")), "검색 é", forced);
        ColumnSearch byEntity = new ColumnSearch(f.modelId, null, null, null, Status.ALREADY_MATCHED, "entityName", WORDS[0]);
        same(f, byEntity, overrides(projectId, byEntity), PageRequest.of(0, 10), "검색 엔터티", forced);
    }

    /** 요약(상태별 건수) = 예전 방식의 상태별 건수 */
    private void summary(GenerationFixture f, boolean forced) throws Exception {
        long projectId = f.user.getProjectId();
        ColumnSearch modelSearch = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        Overrides modelOverrides = overrides(projectId, modelSearch);
        Map<Status, Long> got;
        long summaryTotal;
        if (forced) {
            List<Status> statuses = call("judgeStatuses", repository.findAllColumnSources(projectId, modelSearch, Sort.unsorted()));
            got = statuses.stream().collect(java.util.stream.Collectors.groupingBy(st -> st, java.util.stream.Collectors.counting()));
            summaryTotal = statuses.size();
        } else {
            ColumnSummary summary = recommendation.selectColumnSummary(f.user, f.modelId);
            got = Map.of(Status.AVAILABLE, summary.available(), Status.ALREADY_MATCHED, summary.alreadyMatched(),
                    Status.UNAVAILABLE, summary.unavailable(), Status.CONFLICT, summary.conflict());
            summaryTotal = summary.total();
        }
        long total = 0;
        for (Status status : Status.values()) {
            long want = repository.countColumns(projectId, f.modelId, status, modelOverrides);
            assertThat(got.get(status)).as("요약 " + status).isEqualTo(want);
            assertThat(want).as("상태 " + status + " 행이 있어야 비교가 뜻이 있다").isPositive();
            total += want;
        }
        assertThat(summaryTotal).isEqualTo(total);
    }

    private void same(GenerationFixture f, ColumnSearch search, Overrides o, Pageable pageable, String label, boolean forced) {
        Page<ColumnSource> want = repository.findColumnSources(f.user.getProjectId(), search, pageable, o);
        List<Long> wantIds = want.getContent().stream().map(ColumnSource::attributeId).toList();
        if (forced) {
            Page<ColumnSource> got = call("judgedPage", f.user.getProjectId(), search, pageable);
            assertThat(got.getContent().stream().map(ColumnSource::attributeId).toList()).as("자바 판정 " + label).containsExactlyElementsOf(wantIds);
            assertThat(got.getTotalElements()).as("자바 판정 " + label + " 건수").isEqualTo(want.getTotalElements());
            assertThat(got.getTotalPages()).as("자바 판정 " + label + " 쪽 수").isEqualTo(want.getTotalPages());
            return;
        }
        Page<ColumnRow> got = recommendation.selectColumnRows(f.user, search, pageable);
        assertThat(got.getContent().stream().map(ColumnRow::logicalAttributeId).toList()).as(label).containsExactlyElementsOf(wantIds);
        assertThat(got.getTotalElements()).as(label + " 건수").isEqualTo(want.getTotalElements());
        assertThat(got.getTotalPages()).as(label + " 쪽 수").isEqualTo(want.getTotalPages());
        assertThat(got.getContent()).as(label + " 상태").allMatch(r -> r.status() == search.status());
    }

    @SuppressWarnings("unchecked")
    private <T> T call(String name, Object... args) {
        for (Method m : PhysicalNameRecommendationService.class.getDeclaredMethods()) {
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

    private Overrides overrides(long projectId, ColumnSearch search) throws Exception {
        Method m = PhysicalNameRecommendationService.class.getDeclaredMethod("overrides", Long.class, ColumnSearch.class);
        m.setAccessible(true);
        return (Overrides) m.invoke(recommendation, projectId, search);
    }

    private boolean javaJudged(long projectId, ColumnSearch search) throws Exception {
        Method m = PhysicalNameRecommendationService.class.getDeclaredMethod("statusPlan", Long.class, ColumnSearch.class);
        m.setAccessible(true);
        Object plan = m.invoke(recommendation, projectId, search);
        Method judged = plan.getClass().getDeclaredMethod("javaJudged");
        judged.setAccessible(true);
        return (Boolean) judged.invoke(plan);
    }

    private GenerationFixture model(String name, int entityCount) {
        GenerationFixture f = GenerationFixture.create(context, name, com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < entityCount; i++) {
            long entity = f.entity(name(i));
            entities.add(entity);
            f.pk(entity, f.attr(entity, "키", true, "K1"));
            for (int a = 1; a < 50; a++) f.attr(entity, "속성" + a, false, String.format("C%02d", a));
        }
        for (int from = 0; from < entities.size(); from += 100) { // 생성 요청 한 번의 엔터티 상한(100)
            List<Long> chunk = entities.subList(from, Math.min(entities.size(), from + 100));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chunk));
            generation.apply(f.user, new PhysicalGenerationDto.Request(chunk, null, plan.planToken()));
        }
        return f;
    }

    /** 서로 다른 엔터티 이름 : 한 단어 12 → 두 단어 132 → 세 단어 (PhysicalNameF02PocTest 와 같은 규칙) */
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
