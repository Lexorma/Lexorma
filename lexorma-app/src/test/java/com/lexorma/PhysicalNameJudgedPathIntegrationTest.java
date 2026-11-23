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
 * M7-NAME-F02 : 자바로 판정할 행(비 ASCII·탭 등)이 DB 별 기준값({@code javaJudgeMinRows} — H2 작게, PostgreSQL 10,000) 바로 아래면 예전 SQL 덮어쓰기 경로,
 * 기준값이면 전체 자바 판정 경로를 쓴다. 서비스(두 경로)와 직접 부른 자바 판정 경로의 상태 필터 쪽·건수·요약이 예전 방식(저장소 SQL + 덮어쓰기 목록)과 행 ID·순서까지 같은지 — 상태 4 × 정렬 5 × 쪽 크기 10·100 ×
 * 첫·중간·마지막·빈 쪽, 이름 검색, 요약, 다른 프로젝트 격리.
 * 비 ASCII 행에는 일치(É ↔ é), 추천 가능, 충돌(같은 테이블의 다른 컬럼이 비 ASCII 추천명을 쓴다), 추천 불가(약어가 공백·탭뿐), 끝 탭 일치(F01)가 섞인다.
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
    @DisplayName("기준값 바로 아래(SQL 덮어쓰기)와 기준값(자바 판정) : 상태 필터 쪽·건수·요약이 예전 방식과 같다")
    void boundary() throws Exception {
        int min = (Integer) call("javaJudgeMinRows");
        // 이 DB 의 기준값이 작으면(H2) 기준값 - 1 · 기준값 둘 다 서비스로, 크면(PostgreSQL 10,000) 아래 쪽만 서비스로 보고 자바 판정 경로는 직접 호출해 비교한다
        // (PostgreSQL 기준값 양쪽은 측정 시험 PhysicalNameNonAsciiScaleTest 5,000 · 10,000 에서 예전 방식과 비교)
        int below = Math.min(min, 1_000) - 1;
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
        for (; next < patterns; next++) {
            Map<String, Object> row = mapped.get(mapped.size() - 1 - next);
            String abbreviation = (String) row.get("TRM_ENG_ABBR_NM");
            switch (next % 5) {
                case 0 -> { // 일치 (É ↔ é)
                    jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", abbreviation + "_É" + next, row.get("STD_TRM_SN"));
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", (abbreviation + "_é" + next).toLowerCase(), row.get("COL_SN"));
                }
                case 1 -> // 추천 가능 (컬럼명만 비 ASCII)
                        jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "한" + abbreviation + next, row.get("COL_SN"));
                case 2 -> { // 충돌 : 같은 테이블의 다른 컬럼(바로 앞 속성의 컬럼)이 비 ASCII 추천명을 쓴다
                    Map<String, Object> neighbour = mapped.get(mapped.size() - 1 - next + 1);
                    jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", "충돌_" + next, row.get("STD_TRM_SN"));
                    if (neighbour.get("TBL_SN").equals(row.get("TBL_SN"))) {
                        jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "충돌_" + next, neighbour.get("COL_SN"));
                    }
                }
                case 3 -> { // 추천 불가 : 약어가 공백·탭뿐, 컬럼명 비 ASCII
                    jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", " \t ", row.get("STD_TRM_SN"));
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "ü_" + next, row.get("COL_SN"));
                }
                default -> { // 끝 탭 일치 (F01 : 자바 trim)
                    jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", "TAB_" + next + "\t", row.get("STD_TRM_SN"));
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "tab_" + next, row.get("COL_SN"));
                }
            }
        }
        ColumnSearch model = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        // 기준값 - 1 까지 하나씩 (앞쪽 ASCII 행을 비 ASCII 추천 가능 행으로 — 위 끝쪽 구간과 겹치지 않는다)
        int complex = repository.findComplexColumnSources(projectId, model).size();
        int plainIndex = 0;
        while (complex < below) {
            jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "é_" + plainIndex, mapped.get(plainIndex).get("COL_SN"));
            plainIndex++;
            complex = repository.findComplexColumnSources(projectId, model).size();
        }
        assertThat(complex).isEqualTo(below);
        assertThat(javaJudged(projectId, model)).as("기준값 아래 = SQL 덮어쓰기").isFalse();
        compare(f, false);
        compare(f, true);
        if (below + 1 == min) {
            jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", "é_" + plainIndex, mapped.get(plainIndex).get("COL_SN"));
            assertThat(repository.findComplexColumnSources(projectId, model)).hasSize(min);
            assertThat(javaJudged(projectId, model)).as("기준값 = 자바 판정").isTrue();
            compare(f, false);
        } else {
            assertThat(min).as("PostgreSQL 기준값").isEqualTo(10_000);
        }
        // 다른 프로젝트 : 자기 행만 센다
        assertThat(recommendation.selectColumnSummary(other.user, other.modelId).total()).isEqualTo(100);
    }

    /** 서비스 결과 = 예전 방식 (저장소 SQL 상태 식 + 덮어쓰기 목록) */
    /** forced = 서비스 대신 자바 판정 경로(judgedPage · judgeStatuses)를 직접 */
    private void compare(GenerationFixture f, boolean forced) throws Exception {
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

        List<Sort> sorts = List.of(Sort.unsorted(), Sort.by(Sort.Direction.ASC, "currentPhysicalName"), Sort.by(Sort.Direction.DESC, "logicalName"),
                Sort.by(Sort.Direction.ASC, "recommendedPhysicalName"), Sort.by(Sort.Direction.DESC, "entityName"));
        for (Status status : Status.values()) {
            ColumnSearch search = new ColumnSearch(f.modelId, null, null, null, status, null, null);
            Overrides o = overrides(projectId, search);
            for (Sort sort : sorts) {
                for (int size : new int[]{10, 100}) {
                    long count = repository.findColumnSources(projectId, search, PageRequest.of(0, size, sort), o).getTotalElements();
                    int pages = (int) ((count + size - 1) / size);
                    for (int page : new int[]{0, pages / 2, Math.max(pages - 1, 0), pages + 1}) {
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
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(entities));
        generation.apply(f.user, new PhysicalGenerationDto.Request(entities, null, plan.planToken()));
        return f;
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
