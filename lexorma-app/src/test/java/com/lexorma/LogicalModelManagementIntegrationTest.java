package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.controller.LogicalModelRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-6 후속 3차 : 논리 모델 관리 화면의 서버 조회 — 요약(모델 전체 엔터티·관계 수), 엔터티 목록 페이지(검색·정렬·속성/관계 수 집계),
 * 관계 목록 페이지(속성 짝 판정), 엔터티의 관계. 집계는 예전 화면이 전체 그래프(selectGraph)로 세던 값과 같아야 한다. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalModelManagementIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalModelRestController controller;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /**
     * 고객(속성 3, 복합 주 식별자 2) → 주문 ×3(복수, 첫 관계는 복합 속성 짝), 주문 → 고객(반대 방향), 고객 → 고객 ×2(자기 참조), 직원 → 직원(자기 참조, 속성 없음),
     * 주문 → 지점, 팀(관계 없음)
     */
    private record Model(GenerationFixture f, long customer, long order, long employee, long branch, long team, LogicalModelDto.Relationship paired) {}

    private Model model(String name) {
        GenerationFixture f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        long c1 = f.attr(customer, "고객 번호", true, "CUST_NO");
        long c2 = f.attr(customer, "고객 지점 번호", true, "CUST_BRNCH_NO");
        f.attr(customer, "고객 명", false, "CUST_NM");
        f.pk(customer, c1, c2);
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        long o1 = f.attr(order, "주문 고객 번호", true, "ORD_CUST_NO");
        long o2 = f.attr(order, "주문 고객 지점 번호", true, "ORD_CUST_BRNCH_NO");
        long employee = f.entity("직원");
        long branch = f.entity("지점");
        f.attr(branch, "지점 번호", true, "BRNCH_NO");
        long team = f.entity("팀");
        f.attr(team, "팀 번호", true, "TEAM_NO");
        f.attr(team, "팀 명", false, "TEAM_NM");
        LogicalModelDto.Relationship paired = f.rel("고객 주문", customer, order, false, 1);
        f.pairs(paired, new long[]{c1, o1}, new long[]{c2, o2});
        f.rel("고객 대표 주문", customer, order, false, 0);
        f.rel("고객 최근 주문", customer, order, false, 0);
        f.rel("주문 추천 고객", order, customer, false, 0);
        f.rel("고객 추천인", customer, customer, false, 0);
        f.rel("고객 대리인", customer, customer, false, 0);
        f.rel("직원 상급자", employee, employee, false, 0);
        f.rel("주문 지점", order, branch, false, 1);
        return new Model(f, customer, order, employee, branch, team, paired);
    }

    private Page<LogicalModelDto.EntityRow> rows(GenerationFixture f, String keyword, int page, int size, String sort) {
        return logical.selectEntityRows(f.user, f.modelId, keyword, page, size, sort);
    }

    private static Map<Long, LogicalModelDto.EntityRow> byId(Page<LogicalModelDto.EntityRow> page) {
        return page.getContent().stream().collect(Collectors.toMap(LogicalModelDto.EntityRow::id, r -> r));
    }

    @Test
    @DisplayName("집계 : 속성 수·관계 수(복수·반대 방향·자기 참조 1번)·요약이 예전 전체 그래프 계산과 같다. 엔터티별 관계 수 합 ≠ 모델 관계 수")
    void countsMatchGraph() {
        Model m = model("lmng-count");
        LogicalModelDto.Graph graph = logical.selectGraph(m.f.user, m.f.modelId);
        Map<Long, LogicalModelDto.EntityRow> rows = byId(rows(m.f, null, 0, 100, null));
        assertThat(rows).hasSize(graph.entities().size());
        for (LogicalModelDto.GraphEntity entity : graph.entities()) {
            LogicalModelDto.EntityRow row = rows.get(entity.id());
            long related = graph.relationships().stream().filter(r -> r.sourceEntityId().equals(entity.id()) || r.targetEntityId().equals(entity.id())).count();
            assertThat(row.attributeCount()).as("%s 속성 수", entity.name()).isEqualTo(entity.attributes().size());
            assertThat(row.relationshipCount()).as("%s 관계 수", entity.name()).isEqualTo(related);
            assertThat(row.isApproval()).isEqualTo(entity.isApproval());
        }
        assertThat(rows.get(m.customer).relationshipCount()).as("고객 : 주문 3 + 반대 1 + 자기 참조 2").isEqualTo(6);
        assertThat(rows.get(m.order).relationshipCount()).as("주문 : 고객 4 + 지점 1").isEqualTo(5);
        assertThat(rows.get(m.employee).relationshipCount()).as("자기 참조 1개는 1번").isEqualTo(1);
        assertThat(rows.get(m.employee).attributeCount()).isZero();
        assertThat(rows.get(m.team).relationshipCount()).isZero();
        assertThat(rows.get(m.customer).attributeCount()).isEqualTo(3);

        LogicalModelDto.Summary summary = logical.selectSummary(m.f.user, m.f.modelId);
        assertThat(summary.entityCount()).isEqualTo(graph.entities().size()).isEqualTo(5);
        assertThat(summary.relationshipCount()).isEqualTo(graph.relationships().size()).isEqualTo(8);
        assertThat(rows.values().stream().mapToLong(LogicalModelDto.EntityRow::relationshipCount).sum()).as("양 끝에서 한 번씩 세므로 다르다").isEqualTo(13);
    }

    @Test
    @DisplayName("변경 뒤 다시 조회 : 속성·관계 등록·삭제가 양쪽 엔터티의 수와 요약에 반영된다")
    void countsAfterChanges() {
        Model m = model("lmng-change");
        m.f.attr(m.employee, "직원 번호", true, "EMP_NO");
        LogicalModelDto.Relationship added = m.f.rel("팀 직원", m.team, m.employee, false, 1);
        Map<Long, LogicalModelDto.EntityRow> rows = byId(rows(m.f, null, 0, 100, null));
        assertThat(rows.get(m.employee).attributeCount()).isEqualTo(1);
        assertThat(rows.get(m.employee).relationshipCount()).isEqualTo(2);
        assertThat(rows.get(m.team).relationshipCount()).isEqualTo(1);
        assertThat(logical.selectSummary(m.f.user, m.f.modelId).relationshipCount()).isEqualTo(9);

        logical.deleteRelationship(m.f.user, added.id());
        logical.deleteRelationship(m.f.user, logical.selectEntityRelationships(m.f.user, m.employee).getFirst().id()); // 자기 참조
        rows = byId(rows(m.f, null, 0, 100, null));
        assertThat(rows.get(m.employee).relationshipCount()).isZero();
        assertThat(rows.get(m.team).relationshipCount()).isZero();
        assertThat(logical.selectSummary(m.f.user, m.f.modelId).relationshipCount()).isEqualTo(7);
        logical.updateEntityApproval(m.f.user, m.team, false); // 승인 권한자가 만든 엔터티는 바로 승인된다 — 삭제는 승인취소 뒤
        logical.deleteEntity(m.f.user, m.team);
        assertThat(logical.selectSummary(m.f.user, m.f.modelId).entityCount()).isEqualTo(4);
        assertThat(rows(m.f, null, 0, 100, null).getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("페이징 : 첫·마지막·범위 밖 페이지, 정확한 전체 수, 페이지를 모두 이어 붙이면 빠짐·겹침 없음, 잘못된 페이지·크기는 거절")
    void paging() {
        GenerationFixture f = GenerationFixture.create(context, "lmng-page-" + System.nanoTime(), DbType.POSTGRESQL);
        assertThat(rows(f, null, 0, 10, null).getTotalElements()).as("엔터티 없는 모델").isZero();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 23; i++) ids.add(f.entity("엔터티 " + String.format("%02d", 22 - i)));
        Page<LogicalModelDto.EntityRow> first = rows(f, null, 0, 10, null);
        assertThat(first.getTotalElements()).isEqualTo(23);
        assertThat(first.getContent()).hasSize(10);
        assertThat(first.getContent().getFirst().entityName()).as("기본 이름순").isEqualTo("엔터티 00");
        assertThat(rows(f, null, 2, 10, null).getContent()).as("마지막 페이지").hasSize(3);
        Page<LogicalModelDto.EntityRow> beyond = rows(f, null, 9, 10, null);
        assertThat(beyond.getContent()).as("범위 밖 페이지는 빈 목록 + 전체 수 (화면이 마지막 페이지로 옮긴다)").isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(23);
        List<Long> walked = new ArrayList<>();
        for (int page = 0; page * 7 < 23; page++) rows(f, null, page, 7, "entityName,desc").forEach(r -> walked.add(r.id()));
        assertThat(walked).hasSize(23).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        assertThat(rows(f, null, 0, 100, null).getContent()).hasSize(23);

        assertThatThrownBy(() -> rows(f, null, -1, 10, null)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> rows(f, null, 0, 0, null)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> rows(f, null, 0, 101, null)).as("최대 100 (공용 그리드 선택지와 같다)").isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(com.lexorma.common.exception.domain.ErrorCode.INVALID_PAGE_REQUEST);
        // 거절된 요청은 목록·수 조회를 하지 않는다 (모델 확인 1문장뿐)
        assertThat(statementsOrRejected(() -> rows(f, null, 0, 100_000, null))).isLessThanOrEqualTo(1);
        assertThat(statementsOrRejected(() -> rows(f, null, 0, 10, "password,asc"))).isLessThanOrEqualTo(1);
        assertThat(statementsOrRejected(() -> logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100_000, null))).isLessThanOrEqualTo(1);
        assertThatThrownBy(() -> rows(f, "가".repeat(101), 0, 10, null)).as("검색어 100자").isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("정렬 : 이름·설명·승인 상태 오름·내림, 같은 값은 ID 순, 허용하지 않은 필드는 400 (공통 페이징 안정성 개선 — 예전에는 무시하고 이름순)")
    void sorting() {
        GenerationFixture f = GenerationFixture.create(context, "lmng-sort-" + System.nanoTime(), DbType.POSTGRESQL);
        long b = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, "나", "같은 설명", null, null, null)).id();
        long a = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, "가", "같은 설명", null, null, null)).id();
        long c = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, "다", "앞 설명", null, null, null)).id();
        logical.updateEntityApproval(f.user, c, false);
        assertThat(ids(rows(f, null, 0, 10, "entityName,asc"))).containsExactly(a, b, c);
        assertThat(ids(rows(f, null, 0, 10, "entityName,desc"))).containsExactly(c, b, a);
        assertThat(ids(rows(f, null, 0, 10, "entityDescription,asc"))).as("같은 설명(가·나)은 ID 순, 앞 설명은 뒤").containsExactly(b, a, c);
        assertThat(ids(rows(f, null, 0, 10, "entityDescription,desc"))).as("내림차순에서도 같은 값은 ID 오름차순").containsExactly(c, b, a);
        assertThat(ids(rows(f, null, 0, 10, "isApproval,asc")).getFirst()).as("승인 대기(false) 먼저").isEqualTo(c);
        assertThatThrownBy(() -> rows(f, null, 0, 10, "LGCL_ENTT_SN,desc")).as("모르는 필드 → 400").isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(com.lexorma.common.exception.domain.ErrorCode.INVALID_PAGE_REQUEST);
        assertThat(ids(rows(f, null, 0, 10, null))).containsExactly(a, b, c);
    }

    private static List<Long> ids(Page<LogicalModelDto.EntityRow> page) {
        return page.getContent().stream().map(LogicalModelDto.EntityRow::id).toList();
    }

    @Test
    @DisplayName("검색 : 이름·설명, 대소문자 무시, %·_ 는 글자 그대로 (논리 ERD 검색과 같은 규칙), 검색 결과 수와 모델 전체 수는 따로")
    void search() {
        GenerationFixture f = GenerationFixture.create(context, "lmng-search-" + System.nanoTime(), DbType.POSTGRESQL);
        long alpha = f.entity("Alpha");
        long alpha1 = f.entity("alpha_1");
        long ax1 = f.entity("aX1");
        long sale = f.entity("50%할인");
        f.entity("500원"); // '50%' 가 와일드카드라면 걸린다
        long described = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, "설명 있음", "ALPHA 고객용", null, null, null)).id();
        assertThat(ids(rows(f, "ALPHA", 0, 10, null))).containsExactlyInAnyOrder(alpha, alpha1, described);
        assertThat(ids(rows(f, "a_1", 0, 10, null))).as("_ 는 한 글자 와일드카드가 아니다").containsExactly(alpha1);
        assertThat(ids(rows(f, "50%", 0, 10, null))).as("% 는 와일드카드가 아니다").containsExactly(sale);
        assertThat(ids(rows(f, "  aX1  ", 0, 10, null))).as("앞뒤 공백 무시").containsExactly(ax1);
        assertThat(rows(f, "없는 이름", 0, 10, null).getTotalElements()).isZero();
        assertThat(rows(f, "", 0, 10, null).getTotalElements()).as("빈 검색어 = 모두").isEqualTo(6);
        assertThat(rows(f, "alpha", 0, 2, null).getTotalElements()).as("검색 결과 수 (페이지와 무관)").isEqualTo(3);
        assertThat(logical.selectSummary(f.user, f.modelId).entityCount()).as("요약은 검색과 무관").isEqualTo(6);
    }

    @Test
    @DisplayName("관계 목록 페이지 : ID 순, 속성 짝 판정(복합 짝 COMPLETE), 범위 밖 페이지·잘못된 크기 / 엔터티의 관계 : 자기 참조 1번")
    void relationships() {
        Model m = model("lmng-rel");
        Page<LogicalModelDto.Relationship> first = logical.selectRelationshipRows(m.f.user, m.f.modelId, 0, 3);
        assertThat(first.getTotalElements()).isEqualTo(8);
        assertThat(first.getContent()).hasSize(3);
        assertThat(first.getContent().getFirst().id()).isEqualTo(m.paired.id());
        assertThat(first.getContent().getFirst().attributePairs().status()).isEqualTo("COMPLETE");
        assertThat(first.getContent().getFirst().attributePairs().pairs()).hasSize(2);
        assertThat(first.getContent().get(1).attributePairs().status()).isEqualTo("NONE");
        List<Long> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) logical.selectRelationshipRows(m.f.user, m.f.modelId, page, 3).forEach(r -> all.add(r.id()));
        assertThat(all).hasSize(8).isSorted().doesNotHaveDuplicates();
        assertThat(logical.selectRelationshipRows(m.f.user, m.f.modelId, 5, 3).getContent()).isEmpty();
        assertThatThrownBy(() -> logical.selectRelationshipRows(m.f.user, m.f.modelId, 0, 101)).isInstanceOf(CustomException.class);

        // 예전 상세 화면은 모델 전체 관계를 받아 걸렀다 — 같은 결과
        List<LogicalModelDto.Relationship> whole = logical.selectRelationships(m.f.user, m.f.modelId);
        for (long entity : new long[]{m.customer, m.order, m.employee, m.team}) {
            List<LogicalModelDto.Relationship> mine = logical.selectEntityRelationships(m.f.user, entity);
            assertThat(mine).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(whole.stream().filter(r -> r.sourceEntityId() == entity || r.targetEntityId() == entity).toList());
        }
        assertThat(logical.selectEntityRelationships(m.f.user, m.employee)).hasSize(1);
    }

    private Page<LogicalModelDto.Relationship> relationRows(Model m, String keyword, Boolean identifying, int page, int size, String sort) {
        return logical.selectRelationshipRows(m.f.user, m.f.modelId, keyword, identifying, page, size, sort);
    }

    private static List<Long> relationIds(Page<LogicalModelDto.Relationship> page) {
        return page.getContent().stream().map(LogicalModelDto.Relationship::id).toList();
    }

    /** 예전 전체 관계 목록에서 같은 조건으로 거른 기대값 (검색 = 관계명·출발·도착 엔터티명 중 하나라도, 대소문자 무시) */
    private List<Long> expected(Model m, String keyword, Boolean identifying) {
        String key = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        return logical.selectRelationships(m.f.user, m.f.modelId).stream()
                .filter(r -> identifying == null || identifying.equals(r.isIdentifying()))
                .filter(r -> (r.relationshipName() != null && r.relationshipName().toLowerCase(java.util.Locale.ROOT).contains(key))
                        || r.sourceEntityName().toLowerCase(java.util.Locale.ROOT).contains(key) || r.targetEntityName().toLowerCase(java.util.Locale.ROOT).contains(key))
                .map(LogicalModelDto.Relationship::id).toList();
    }

    @Test
    @DisplayName("관계 검색·필터 (후속 3차 보완) : 관계명·출발·도착 엔터티명, 대소문자 무시, %·_ 글자 그대로, 한 관계는 한 번, 식별 여부는 저장값, 결과 수 ≠ 모델 관계 수")
    void relationshipSearchAndFilter() {
        Model m = model("lmng-rel-search");
        LogicalModelDto.Relationship identifying = m.f.rel("고객 주문", m.customer, m.branch, true, 1); // 같은 이름 다른 관계, 식별
        LogicalModelDto.Relationship special = m.f.rel("할인_50%", m.team, m.team, false, 0);
        LogicalModelDto.Relationship unnamed = m.f.rel(null, m.team, m.employee, true, 1);
        for (String keyword : new String[]{"주문", "고객", "추천", "지점", "팀", "직원", "없는 이름", "", null}) {
            for (Boolean flag : new Boolean[]{null, true, false}) {
                Page<LogicalModelDto.Relationship> page = relationRows(m, keyword, flag, 0, 100, null);
                assertThat(relationIds(page)).as("'%s' / %s", keyword, flag).containsExactlyElementsOf(expected(m, keyword, flag)).doesNotHaveDuplicates();
                assertThat(page.getTotalElements()).isEqualTo(expected(m, keyword, flag).size());
            }
        }
        assertThat(relationIds(relationRows(m, "고객 추천인", null, 0, 10, null))).as("자기 참조 (출발·도착 모두 고객) 한 번").hasSize(1);
        assertThat(relationIds(relationRows(m, "CUST", null, 0, 10, null))).as("영문 약어는 검색 대상이 아니다").isEmpty();
        assertThat(relationIds(relationRows(m, "_5", null, 0, 10, null))).as("_ 는 글자 그대로").containsExactly(special.id());
        assertThat(relationIds(relationRows(m, "%", null, 0, 10, null))).as("% 는 글자 그대로").containsExactly(special.id());
        assertThat(relationIds(relationRows(m, "  할인_50%  ", null, 0, 10, null))).containsExactly(special.id());
        assertThat(relationIds(relationRows(m, null, true, 0, 10, null))).as("식별 = 저장값 true 만").containsExactly(identifying.id(), unnamed.id());
        assertThat(relationRows(m, "고객", false, 0, 2, null).getTotalElements()).as("검색 + 필터 결과 수 (페이지와 무관)").isEqualTo(expected(m, "고객", false).size());
        assertThat(logical.selectSummary(m.f.user, m.f.modelId).relationshipCount()).as("요약은 검색·필터와 무관").isEqualTo(11);
        assertThatThrownBy(() -> relationRows(m, "가".repeat(101), null, 0, 10, null)).isInstanceOf(CustomException.class);
        // 속성 짝 판정·응답 내용은 예전 전체 목록과 같다
        Map<Long, LogicalModelDto.Relationship> whole = logical.selectRelationships(m.f.user, m.f.modelId).stream().collect(Collectors.toMap(LogicalModelDto.Relationship::id, r -> r));
        for (LogicalModelDto.Relationship row : relationRows(m, "고객", null, 0, 100, "sourceEntityName,desc")) {
            assertThat(row).usingRecursiveComparison().isEqualTo(whole.get(row.id()));
        }
    }

    @Test
    @DisplayName("관계 정렬 (후속 3차 보완) : 관계명·출발·도착 엔터티명 오름·내림, 같은 값은 관계 ID 순, 이름 없는 관계는 뒤, 정렬 없음 = 예전 ID 순, 모르는 필드·허용 목록 밖은 400 (공통 페이징 안정성 개선), 페이지 사이 빠짐·겹침 없음")
    void relationshipSorting() {
        Model m = model("lmng-rel-sort");
        LogicalModelDto.Relationship unnamed = m.f.rel(null, m.team, m.employee, false, 1);
        List<LogicalModelDto.Relationship> whole = logical.selectRelationships(m.f.user, m.f.modelId);
        List<Long> byId = whole.stream().map(LogicalModelDto.Relationship::id).sorted().toList();
        assertThat(relationIds(relationRows(m, null, null, 0, 100, null))).as("정렬 없음 = 예전 ID 순").isEqualTo(byId);
        for (String unknown : new String[]{"LGCL_REL_SN,desc", "isIdentifying,desc", "sourceEntity.entityName,asc"}) {
            assertThatThrownBy(() -> relationRows(m, null, null, 0, 100, unknown)).as("%s → 400", unknown).isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(com.lexorma.common.exception.domain.ErrorCode.INVALID_PAGE_REQUEST);
        }
        java.util.Comparator<LogicalModelDto.Relationship> id = java.util.Comparator.comparing(LogicalModelDto.Relationship::id);
        Map<String, java.util.function.Function<LogicalModelDto.Relationship, String>> fields = Map.of(
                "relationshipName", LogicalModelDto.Relationship::relationshipName,
                "sourceEntityName", LogicalModelDto.Relationship::sourceEntityName,
                "targetEntityName", LogicalModelDto.Relationship::targetEntityName);
        for (var field : fields.entrySet()) {
            for (boolean desc : new boolean[]{false, true}) {
                // DB 정렬(엔터티명 콜레이션)과 비교 : 같은 값끼리 ID 오름차순인지, 이름 없는 관계가 맨 뒤인지, 값 순서가 DB 의 순서와 일치하는지
                List<LogicalModelDto.Relationship> sorted = relationRows(m, null, null, 0, 100, field.getKey() + (desc ? ",desc" : ",asc")).getContent();
                assertThat(sorted).hasSize(whole.size());
                for (int i = 1; i < sorted.size(); i++) {
                    String before = field.getValue().apply(sorted.get(i - 1)), after = field.getValue().apply(sorted.get(i));
                    if (java.util.Objects.equals(before, after)) assertThat(id.compare(sorted.get(i - 1), sorted.get(i))).as("%s 같은 값은 ID 순", field.getKey()).isNegative();
                    assertThat(before == null && after != null).as("%s 이름 없음은 뒤", field.getKey()).isFalse();
                }
                List<String> values = sorted.stream().map(field.getValue()).filter(java.util.Objects::nonNull).distinct().toList();
                List<String> reversed = new ArrayList<>(values);
                java.util.Collections.reverse(reversed);
                assertThat(relationRows(m, null, null, 0, 100, field.getKey() + (desc ? ",asc" : ",desc")).getContent().stream()
                        .map(field.getValue()).filter(java.util.Objects::nonNull).distinct().toList()).as("%s 반대 방향", field.getKey()).isEqualTo(reversed);
                List<Long> walked = new ArrayList<>();
                for (int page = 0; page * 3 < whole.size(); page++) relationRows(m, null, null, page, 3, field.getKey() + (desc ? ",desc" : ",asc")).forEach(r -> walked.add(r.id()));
                assertThat(walked).as("%s 3개씩 이어 붙이기", field.getKey()).isEqualTo(sorted.stream().map(LogicalModelDto.Relationship::id).toList());
            }
        }
        assertThat(relationIds(relationRows(m, null, null, 0, 100, "relationshipName,asc")).getLast()).isEqualTo(unnamed.id());
        assertThat(relationIds(relationRows(m, null, null, 0, 100, "relationshipName,desc")).getLast()).as("내림차순에서도 이름 없음은 뒤").isEqualTo(unnamed.id());
        // 이름 순서는 DB 콜레이션을 따른다 (H2 = 글자 코드 순, PostgreSQL = DB 로캘 — 엔터티 이름 정렬과 같다). 위치는 가정하지 않고, 출발이 고객인 5개가 붙어 있고 ID 순인지만 본다
        List<LogicalModelDto.Relationship> bySource = relationRows(m, null, null, 0, 100, "sourceEntityName,asc").getContent();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < bySource.size(); i++) if ("고객".equals(bySource.get(i).sourceEntityName())) positions.add(i);
        assertThat(positions).hasSize(5);
        assertThat(positions.getLast() - positions.getFirst()).as("출발이 고객인 5개(주문 3 + 자기 참조 2)는 붙어 있다").isEqualTo(4);
        assertThat(positions.stream().map(i -> bySource.get(i).id()).toList()).as("그 안은 ID 순").isSorted();
        assertThatThrownBy(() -> relationRows(m, null, null, 0, 10, "relationshipName,up")).as("잘못된 방향 = 400")
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(com.lexorma.common.exception.domain.ErrorCode.INVALID_PAGE_REQUEST);
    }

    @Test
    @DisplayName("SQL 수 : 페이지 크기·모델 크기와 무관하게 일정 (엔터티마다 세지 않는다 — N+1 없음)")
    void statementCount() {
        GenerationFixture f = GenerationFixture.create(context, "lmng-sql-" + System.nanoTime(), DbType.POSTGRESQL);
        long previous = 0;
        for (int i = 0; i < 30; i++) {
            long entity = f.entity("엔터티 " + String.format("%02d", i));
            f.attr(entity, "속성 " + i, true, "ATTR_" + i);
            if (i > 0) f.rel("사슬 " + i, previous, entity, false, 1);
            previous = entity;
        }
        long small = statements(() -> rows(f, null, 0, 5, null));
        long large = statements(() -> rows(f, null, 0, 30, null));
        assertThat(large).as("엔터티 5개·30개 페이지의 SQL 수 (모델 확인·페이지·전체 수·속성 수·출발 관계 수·도착 관계 수)").isEqualTo(small).isLessThanOrEqualTo(6);
        long relationsSmall = statements(() -> logical.selectRelationshipRows(f.user, f.modelId, 0, 5));
        long relationsLarge = statements(() -> logical.selectRelationshipRows(f.user, f.modelId, 0, 29));
        assertThat(relationsLarge).isEqualTo(relationsSmall).isLessThanOrEqualTo(6);
        long searchedSmall = statements(() -> logical.selectRelationshipRows(f.user, f.modelId, "엔터티", false, 0, 5, "targetEntityName,desc"));
        long searchedLarge = statements(() -> logical.selectRelationshipRows(f.user, f.modelId, "엔터티", false, 0, 29, "targetEntityName,desc"));
        assertThat(searchedLarge).as("검색·필터·정렬 관계 페이지 5개·29개의 SQL 수").isEqualTo(searchedSmall).isLessThanOrEqualTo(6);
        assertThat(statements(() -> logical.selectSummary(f.user, f.modelId))).isLessThanOrEqualTo(3);
    }

    /** 거절(CustomException)되는 호출의 준비 문장 수 */
    private long statementsOrRejected(Supplier<Object> action) {
        return statements(() -> {
            assertThatThrownBy(action::get).isInstanceOf(CustomException.class);
            return null;
        });
    }

    private long statements(Supplier<Object> action) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            action.get();
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(enabled);
        }
    }

    @Test
    @DisplayName("격리 : 다른 프로젝트 로그인은 요약·목록·관계·엔터티의 관계를 볼 수 없다 (없는 것과 같다). 집계에 다른 프로젝트 행이 섞이지 않는다")
    void isolation() {
        Model m = model("lmng-iso");
        Model other = model("lmng-iso-other");
        var stranger = other.f.user;
        assertThatThrownBy(() -> logical.selectSummary(stranger, m.f.modelId)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> logical.selectEntityRows(stranger, m.f.modelId, null, 0, 10, null)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> logical.selectRelationshipRows(stranger, m.f.modelId, 0, 10)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> logical.selectRelationshipRows(stranger, m.f.modelId, "고객", true, 0, 10, "relationshipName,asc")).as("검색·필터로도 수를 알 수 없다").isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> logical.selectEntityRelationships(stranger, m.customer)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> logical.selectEntityRelationships(m.f.user, 987_654_321L)).as("없는 엔터티").isInstanceOf(CustomException.class);
        assertThat(logical.selectSummary(m.f.user, m.f.modelId).relationshipCount()).isEqualTo(8);
        assertThat(byId(rows(m.f, null, 0, 100, null)).get(m.customer).relationshipCount()).isEqualTo(6);
        GenerationFixture otherModel = m.f.otherModel("lmng-iso-model-" + System.nanoTime());
        assertThat(logical.selectEntityRows(m.f.user, otherModel.modelId, null, 0, 10, null).getTotalElements()).as("같은 프로젝트 다른 모델").isZero();
    }

    @Test
    @DisplayName("권한 : VIEWER 는 요약·목록·관계·엔터티 관계를 볼 수 있고 바꾸지 못한다 (기존 권한 그대로)")
    void viewer() {
        Model m = model("lmng-viewer");
        var viewer = SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), m.f.user.getProjectId(), "990078", "ROLE_VIEWER");
        assertThat(controller.selectSummary(viewer, m.f.modelId).getBody().entityCount()).isEqualTo(5);
        assertThat((List<?>) controller.selectEntityRows(viewer, m.f.modelId, 0, 10, null, "주문").getBody().get("data")).hasSize(1);
        assertThat(controller.selectEntityRows(viewer, m.f.modelId, 0, 10, null, null).getBody().get("recordsTotal")).isEqualTo(5L);
        assertThat((List<?>) controller.selectRelationshipRows(viewer, m.f.modelId, 0, 10, null, null, null).getBody().get("data")).hasSize(8);
        assertThat((List<?>) controller.selectRelationshipRows(viewer, m.f.modelId, 0, 10, "sourceEntityName,desc", "주문", false).getBody().get("data")).as("VIEWER 검색·필터·정렬").hasSize(5);
        assertThat(controller.selectEntityRelationships(viewer, m.customer).getBody()).hasSize(6);
        assertThatThrownBy(() -> controller.saveEntity(viewer, new LogicalModelDto.Entity(null, m.f.modelId, "새 엔터티", null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteRelationship(viewer, m.paired.id())).isInstanceOf(AccessDeniedException.class);
    }
}
