package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.controller.LogicalModelRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-6 후속 1차 : 논리 ERD 부분 조회 — 범위 조회(이름·설명 검색, 페이지), 이웃 탐색(출발·도착 모두, 1~3단계), 엔터티 찾기.
 * 서버가 고른 엔터티만 읽고, 관계는 양 끝이 모두 범위 안인 것만 준다 (범위 밖 관계는 엔터티별 수). 전체 조회(관리 화면)는 그대로. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalErdScopeIntegrationTest {

    @Autowired ApplicationContext context;

    @Autowired CurrentBase currentBase; // M8-C2 : 컨트롤러 호출의 지금 기준값
    @Autowired LogicalModelService logical;
    @Autowired LogicalModelRestController controller;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 회원 → 주문, 회원 → 배송, 등급 → 회원, 주문 → 주문(자기 참조), 주문 → 상품 ×2(복수 관계), 상품 → 회원(순환), 지점 → 상품, 고립 = 팀 */
    private record Shop(GenerationFixture f, long member, long order, long delivery, long grade, long product, long branch, long team, LogicalModelDto.Relationship paired) {}

    private Shop shop(String name) {
        GenerationFixture f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long member = f.entity("고객");
        long memberKey = f.attr(member, "고객 번호", true, "CUST_NO");
        f.pk(member, memberKey);
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        long orderMember = f.attr(order, "주문 고객 번호", true, "ORD_CUST_NO");
        long delivery = f.entity("배송");
        long grade = f.entity("직원"); // 등급 대신 등록된 단어
        long product = f.entity("상품");
        long branch = f.entity("지점");
        long team = f.entity("팀");
        LogicalModelDto.Relationship paired = f.rel("회원 주문", member, order, false, 1);
        f.pairs(paired, new long[]{memberKey, orderMember});
        f.rel("회원 배송", member, delivery, false, 1);
        f.rel("등급 회원", grade, member, false, 1);
        f.rel("주문 원주문", order, order, false, 0);
        f.rel("주문 상품", order, product, false, 1);
        f.rel("주문 대표 상품", order, product, false, 0);
        f.rel("상품 추천 회원", product, member, false, 0);
        f.rel("지점 상품", branch, product, false, 1);
        return new Shop(f, member, order, delivery, grade, product, branch, team, paired);
    }

    private LogicalModelDto.GraphPart neighbors(Shop s, long entityId, int depth) {
        return logical.selectNeighbors(s.f.user, s.f.modelId, entityId, depth);
    }

    private static Set<Long> ids(LogicalModelDto.GraphPart part) {
        return part.entities().stream().map(LogicalModelDto.GraphEntity::id).collect(Collectors.toSet());
    }

    /** 응답 관계는 모두 응답 엔터티 사이에 있고 중복이 없다 */
    private static void consistent(LogicalModelDto.GraphPart part) {
        Set<Long> entities = ids(part);
        assertThat(part.entities()).extracting(LogicalModelDto.GraphEntity::id).doesNotHaveDuplicates();
        assertThat(part.relationships()).extracting(LogicalModelDto.Relationship::id).doesNotHaveDuplicates();
        assertThat(part.relationships()).allSatisfy(r -> assertThat(entities).contains(r.sourceEntityId(), r.targetEntityId()));
    }

    @Test
    @DisplayName("이웃 탐색 : 출발·도착 모두, 자기 참조·순환·복수 관계·여러 경로를 한 번씩, 2단계는 더 멀리, 범위 밖 관계는 엔터티별 수, 고립 엔터티는 자신만")
    void neighbors() {
        Shop s = shop("lerd-neighbors");
        LogicalModelDto.GraphPart one = neighbors(s, s.member, 1);
        consistent(one);
        assertThat(ids(one)).containsExactlyInAnyOrder(s.member, s.order, s.delivery, s.grade, s.product);
        assertThat(one.relationships()).as("범위 안 관계 7개 (자기 참조·복수 관계 포함, 지점 → 상품 제외)").hasSize(7);
        assertThat(one.outsideRelationships()).containsExactly(Map.entry(s.product, 1));
        assertThat(one.truncated()).isFalse();
        assertThat(one.totalCount()).isEqualTo(5);
        assertThat(one.modelEntityCount()).isEqualTo(7);

        LogicalModelDto.GraphPart two = neighbors(s, s.member, 2);
        consistent(two);
        assertThat(ids(two)).containsExactlyInAnyOrder(s.member, s.order, s.delivery, s.grade, s.product, s.branch);
        assertThat(two.relationships()).hasSize(8);
        assertThat(two.outsideRelationships()).isEmpty();

        LogicalModelDto.GraphPart self = neighbors(s, s.order, 1);
        assertThat(ids(self)).containsExactlyInAnyOrder(s.order, s.member, s.product);
        assertThat(self.relationships()).extracting(LogicalModelDto.Relationship::relationshipName)
                .containsExactlyInAnyOrder("회원 주문", "주문 원주문", "주문 상품", "주문 대표 상품", "상품 추천 회원");

        LogicalModelDto.GraphPart alone = neighbors(s, s.team, 3);
        assertThat(ids(alone)).containsExactly(s.team);
        assertThat(alone.relationships()).isEmpty();
        assertThat(alone.outsideRelationships()).isEmpty();

        // 속성 짝·판정은 전체 조회와 같다
        LogicalModelDto.Relationship full = logical.selectGraph(s.f.user, s.f.modelId).relationships().stream().filter(r -> r.id().equals(s.paired.id())).findFirst().orElseThrow();
        assertThat(one.relationships()).filteredOn(r -> r.id().equals(s.paired.id())).singleElement().isEqualTo(full);
        assertThat(one.entities()).filteredOn(e -> e.id() == s.member).singleElement()
                .isEqualTo(logical.selectGraph(s.f.user, s.f.modelId).entities().stream().filter(e -> e.id() == s.member).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("이웃 탐색 거절 : 0·4단계, 없는 엔터티, 다른 모델·다른 프로젝트의 엔터티")
    void neighborsRejected() {
        Shop s = shop("lerd-neighbors-bad");
        Shop other = shop("lerd-neighbors-other");
        for (int depth : new int[]{0, 4}) {
            assertThatThrownBy(() -> neighbors(s, s.member, depth)).extracting("errorCode").isEqualTo(ErrorCode.INVALID_LOGICAL_MODEL);
        }
        assertThatThrownBy(() -> neighbors(s, 999_999_999L, 1)).extracting("errorCode").isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        GenerationFixture sameProjectOtherModel = s.f.otherModel("lerd-other-model-" + System.nanoTime());
        long elsewhere = sameProjectOtherModel.entity("팀");
        assertThatThrownBy(() -> neighbors(s, elsewhere, 1)).as("같은 프로젝트의 다른 모델").extracting("errorCode").isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThatThrownBy(() -> neighbors(s, other.member, 1)).as("다른 프로젝트").extracting("errorCode").isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThatThrownBy(() -> logical.selectGraphPart(s.f.user, other.f.modelId, null, 0, 100)).as("다른 프로젝트의 모델").extracting("errorCode").isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThatThrownBy(() -> logical.searchEntities(s.f.user, other.f.modelId, null, 20)).extracting("errorCode").isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
    }

    @Test
    @DisplayName("범위 조회 : 검색어 없음 = 모델 전체(이름순), 이름·설명 대소문자 무시, %·_ 는 글자 그대로, 결과 없음, 페이지·truncated, 한도·검색어 길이 거절, 빈 모델")
    void searchScope() {
        Shop s = shop("lerd-search");
        jdbc.update("update TB_LGCL_ENTT set ENTT_EXPLN = 'Shipping 50%_off' where LGCL_ENTT_SN = ?", s.delivery);
        LogicalModelDto.GraphPart all = logical.selectGraphPart(s.f.user, s.f.modelId, null, 0, 100);
        consistent(all);
        assertThat(all.entities()).hasSize(7);
        assertThat(all.totalCount()).isEqualTo(7);
        assertThat(all.truncated()).isFalse();
        assertThat(all.relationships()).hasSize(8);
        assertThat(all.outsideRelationships()).isEmpty();
        assertThat(all.entities()).extracting(LogicalModelDto.GraphEntity::name)
                .containsExactlyElementsOf(logical.selectGraph(s.f.user, s.f.modelId).entities().stream().map(LogicalModelDto.GraphEntity::name).toList());

        assertThat(ids(logical.selectGraphPart(s.f.user, s.f.modelId, " 주문 ", 0, 100))).containsExactly(s.order);
        assertThat(ids(logical.selectGraphPart(s.f.user, s.f.modelId, "SHIPPING", 0, 100))).as("설명, 대소문자 무시").containsExactly(s.delivery);
        assertThat(ids(logical.selectGraphPart(s.f.user, s.f.modelId, "50%_", 0, 100))).as("% _ 는 글자").containsExactly(s.delivery);
        assertThat(ids(logical.selectGraphPart(s.f.user, s.f.modelId, "%", 0, 100))).containsExactly(s.delivery);
        LogicalModelDto.GraphPart none = logical.selectGraphPart(s.f.user, s.f.modelId, "없는엔터티", 0, 100);
        assertThat(none.entities()).isEmpty();
        assertThat(none.totalCount()).isZero();
        assertThat(none.modelEntityCount()).isEqualTo(7);

        // 페이지 : 3개씩 — 범위 밖 관계 수가 따라온다
        LogicalModelDto.GraphPart first = logical.selectGraphPart(s.f.user, s.f.modelId, null, 0, 3);
        LogicalModelDto.GraphPart third = logical.selectGraphPart(s.f.user, s.f.modelId, null, 2, 3);
        consistent(first);
        assertThat(first.entities()).hasSize(3);
        assertThat(first.truncated()).isTrue();
        assertThat(third.entities()).hasSize(1);
        assertThat(third.truncated()).isFalse();
        assertThat(logical.selectGraphPart(s.f.user, s.f.modelId, null, 9, 3).entities()).isEmpty();
        int shownPlusOutside = first.relationships().size() * 2 + first.outsideRelationships().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(shownPlusOutside).as("범위 안 관계는 양 끝, 범위 밖 관계는 한 끝").isPositive();

        for (int[] bad : new int[][]{{-1, 10}, {0, 0}, {0, 301}}) {
            assertThatThrownBy(() -> logical.selectGraphPart(s.f.user, s.f.modelId, null, bad[0], bad[1])).extracting("errorCode").isEqualTo(ErrorCode.INVALID_LOGICAL_MODEL);
        }
        assertThatThrownBy(() -> logical.selectGraphPart(s.f.user, s.f.modelId, "가".repeat(101), 0, 10)).extracting("errorCode").isEqualTo(ErrorCode.INVALID_LOGICAL_MODEL);

        GenerationFixture empty = s.f.otherModel("lerd-empty-" + System.nanoTime());
        LogicalModelDto.GraphPart nothing = logical.selectGraphPart(empty.user, empty.modelId, null, 0, 100);
        assertThat(nothing.entities()).isEmpty();
        assertThat(nothing.modelEntityCount()).isZero();
    }

    @Test
    @DisplayName("엔터티 찾기 : ERD 범위와 무관하게 이름·설명, 이름순 최대 size 개 + 전체 수, 속성 없음, 1~50 밖은 거절")
    void entityHits() {
        Shop s = shop("lerd-hits");
        LogicalModelDto.EntityHits hits = logical.searchEntities(s.f.user, s.f.modelId, "", 2);
        assertThat(hits.items()).hasSize(2);
        assertThat(hits.totalCount()).isEqualTo(7);
        assertThat(logical.searchEntities(s.f.user, s.f.modelId, "상품", 20).items()).extracting(LogicalModelDto.EntityHit::id).containsExactly(s.product);
        assertThatThrownBy(() -> logical.searchEntities(s.f.user, s.f.modelId, null, 51)).extracting("errorCode").isEqualTo(ErrorCode.INVALID_LOGICAL_MODEL);
    }

    @Test
    @DisplayName("한도 : 허브 엔터티에 305개 이웃이면 300개에서 멈추고 truncated, 응답 관계는 그 300개 사이만 — 범위 조회도 300개까지")
    void limit() {
        Shop s = shop("lerd-limit");
        long relationshipId = 880_000_000L + (System.nanoTime() % 1_000_000L) * 1000;
        long entityId = relationshipId + 500;
        for (int i = 0; i < 305; i++) {
            long child = entityId + i;
            jdbc.update("insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM) values (?, ?, ?, ?)", child, s.f.user.getProjectId(), s.f.modelId, "허브 이웃 " + i);
            jdbc.update("insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, IDNTF_YN, ORGNL_MIN_CNT, TRGT_MIN_CNT)"
                    + " values (?, ?, ?, ?, ?, ?, false, 1, 0)", relationshipId + i, s.f.user.getProjectId(), s.f.modelId, s.team, child, "허브 " + i);
        }
        LogicalModelDto.GraphPart hub = neighbors(s, s.team, 1);
        consistent(hub);
        assertThat(hub.entities()).hasSize(300);
        assertThat(hub.truncated()).isTrue();
        assertThat(hub.outsideRelationships()).as("허브에서 범위 밖으로 간 관계").containsEntry(s.team, 6);
        LogicalModelDto.GraphPart page = logical.selectGraphPart(s.f.user, s.f.modelId, null, 0, 300);
        assertThat(page.entities()).hasSize(300);
        assertThat(page.totalCount()).isEqualTo(312);
        assertThat(page.truncated()).isTrue();
        consistent(page);
    }

    @Test
    @DisplayName("배치 : 부분 그래프만 저장해도 범위 밖 엔터티 좌표는 그대로 (기존 합치기), 오래된 version 은 409, 사용자·모델별로 따로")
    void partialLayoutKeepsOthers() {
        Shop s = shop("lerd-layout");
        LogicalModelDto.Layout first = logical.saveLayout(s.f.user, s.f.modelId, new LogicalModelDto.Layout(null,
                Map.of(s.member, new LogicalModelDto.Point(10, 20), s.branch, new LogicalModelDto.Point(900, 900)), new LogicalModelDto.Viewport(0, 0, 1)));
        // 회원 1단계 이웃만 보고 있는 화면 : 지점은 범위 밖
        Set<Long> shown = ids(neighbors(s, s.member, 1));
        assertThat(shown).doesNotContain(s.branch);
        LogicalModelDto.Layout second = logical.saveLayout(s.f.user, s.f.modelId, new LogicalModelDto.Layout(first.version(),
                Map.of(s.member, new LogicalModelDto.Point(30, 40), s.order, new LogicalModelDto.Point(300, 40)), new LogicalModelDto.Viewport(1, 1, 0.5)));
        assertThat(second.nodes()).containsEntry(s.branch, new LogicalModelDto.Point(900, 900))
                .containsEntry(s.member, new LogicalModelDto.Point(30, 40)).containsEntry(s.order, new LogicalModelDto.Point(300, 40));
        assertThatThrownBy(() -> logical.saveLayout(s.f.user, s.f.modelId, new LogicalModelDto.Layout(first.version(), Map.of(), new LogicalModelDto.Viewport(0, 0, 1))))
                .extracting("errorCode").isEqualTo(ErrorCode.ERD_LAYOUT_CONFLICT);
        GenerationFixture otherModel = s.f.otherModel("lerd-layout-other-" + System.nanoTime());
        assertThat(logical.selectLayout(s.f.user, otherModel.modelId).nodes()).as("모델별").isEmpty();
    }

    @Test
    @DisplayName("권한 : VIEWER 는 범위 조회·이웃·찾기·내 배치 저장을 할 수 있고, 논리 모델은 바꾸지 못한다 (기존 권한 그대로)")
    void viewer() {
        Shop s = shop("lerd-viewer");
        var viewer = SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), s.f.user.getProjectId(), "990077", "ROLE_VIEWER");
        assertThat(controller.selectGraphPart(viewer, s.f.modelId, null, 0, 100).getBody().entities()).hasSize(7);
        assertThat(controller.selectNeighbors(viewer, s.f.modelId, s.member, 1).getBody().entities()).hasSize(5);
        assertThat(controller.searchEntities(viewer, s.f.modelId, "주문", 20).getBody().items()).hasSize(1);
        assertThat(controller.saveLayout(viewer, s.f.modelId, new LogicalModelDto.Layout(null, Map.of(s.member, new LogicalModelDto.Point(1, 1)), new LogicalModelDto.Viewport(0, 0, 1)))
                .getBody().nodes()).containsKey(s.member);
        assertThatThrownBy(() -> controller.deleteEntity(viewer, s.team, currentBase.of(com.lexorma.data.logical.domain.entity.LogicalEntity.class, s.team), currentBase.entityComposition(viewer, s.team))).isInstanceOf(AccessDeniedException.class);
        assertThat(new HashSet<>(List.of(s.team))).isNotEmpty();
        assertThatThrownBy(() -> controller.selectGraphPart(viewer, s.f.modelId, null, 0, 301)).isInstanceOf(CustomException.class);
    }
}
