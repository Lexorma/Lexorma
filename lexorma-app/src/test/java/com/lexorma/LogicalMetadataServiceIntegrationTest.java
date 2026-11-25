package com.lexorma;

import com.lexorma.ai.tool.LogicalMetadataDto;
import com.lexorma.ai.tool.LogicalMetadataDto.EntityRef;
import com.lexorma.ai.tool.LogicalMetadataDto.EntitySearch;
import com.lexorma.ai.tool.LogicalMetadataDto.ModelRef;
import com.lexorma.ai.tool.LogicalMetadataService;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Assignment;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.AssignmentItem;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * D-35 D35-2 : 논리 모델 AI·MCP 공통 조회 ({@link LogicalMetadataService}) — 모델 목록·개요(주제 영역 계층·그룹), 엔터티 검색(영역·하위 포함·미분류·검색어·페이지),
 * 엔터티 상세(상속 구조·유효 속성·상속 주 식별자 순서·보조 식별자·직접/상속 관계), 대상 해석(ID·이름 일치·불일치), 프로젝트·모델 격리, 로그인 세션 없는 조회,
 * 상속 계산 공통화 뒤 화면 응답(editable 포함) 호환, 직렬화 칸.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalMetadataServiceIntegrationTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalSubjectAreaService areas;
    @Autowired LogicalMetadataService metadata;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 준비 : 3단계 상속 + 3단계 주제 영역 + 복합 주 식별자 + 보조 식별자 + 관계

    /** 당사자(복합 PK 구분·번호 — 속성 순서와 다른 순서) → 고객(보조 식별자 이메일)·거래처 → 개인고객·법인고객(자체 보조 식별자), 주문(미상속) */
    private static final class Fixture {
        GenerationFixture f;
        long party, customer, vendor, person, corp, order;
        long partyNo, partyKind, regDate, grade, email, birth, bizNo, orderNo, orderPartyKind, orderPartyNo;
        long top, mid, leaf, orderArea;
        long relationship;
        long partyGroup, customerGroup;

        Long project() {
            return f.user.getProjectId();
        }
    }

    private Fixture fixture(String name) {
        Fixture x = new Fixture();
        x.f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        GenerationFixture f = x.f;
        x.party = entity(f, "당사자", "거래 상대 전체");
        x.customer = entity(f, "고객", null);
        x.vendor = entity(f, "거래처", null);
        x.person = entity(f, "개인고객", null);
        x.corp = entity(f, "법인고객", null);
        x.order = entity(f, "주문", null);
        x.partyNo = attr(f, x.party, "당사자번호", true);
        x.partyKind = attr(f, x.party, "당사자구분", true);
        x.regDate = attr(f, x.party, "등록일자", false);
        x.grade = attr(f, x.customer, "고객등급", false);
        x.email = attr(f, x.customer, "고객이메일", false);
        attr(f, x.vendor, "거래처등급", false);
        x.birth = attr(f, x.person, "생년월일", false);
        x.bizNo = attr(f, x.corp, "사업자번호", false);
        x.orderNo = attr(f, x.order, "주문번호", true);
        x.orderPartyKind = attr(f, x.order, "주문당사자구분", true);
        x.orderPartyNo = attr(f, x.order, "주문당사자번호", true);
        identifier(f, x.party, "당사자 PK", true, x.partyKind, x.partyNo);
        identifier(f, x.order, "주문 PK", true, x.orderNo);
        identifier(f, x.customer, "고객 이메일 AK", false, x.email);
        identifier(f, x.corp, "사업자번호 AK", false, x.bizNo);
        x.partyGroup = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, x.party, "당사자 구분", null, false, true, x.partyKind,
                List.of(new MemberInput(x.customer, "C"), new MemberInput(x.vendor, "V")), null)).id();
        x.customerGroup = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, x.customer, "고객 유형", null, false, false, null,
                List.of(new MemberInput(x.person, null), new MemberInput(x.corp, null)), null)).id();
        LogicalModelDto.Relationship r = f.rel("고객 주문", x.customer, x.order, false, 1);
        f.pairs(r, new long[]{x.partyKind, x.orderPartyKind}, new long[]{x.partyNo, x.orderPartyNo});
        x.relationship = r.id();
        x.top = area(f, null, "고객 관리");
        x.mid = area(f, x.top, "개인 고객 관리");
        x.leaf = area(f, x.mid, "우수 고객");
        x.orderArea = area(f, null, "주문 관리");
        assign(f, x.top, x.party, x.customer);
        assign(f, x.mid, x.person);
        assign(f, x.leaf, x.corp);
        assign(f, x.orderArea, x.order);
        // 승인 : 루트만 승인 (상속 속성의 승인 상태는 정의한 엔터티의 것이어야 한다)
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", x.party);
        return x;
    }

    private long entity(GenerationFixture f, String name, String description) {
        long id = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, name, description, null, null, null)).id();
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private long attr(GenerationFixture f, long entity, String name, boolean required) {
        return logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, entity, name, null, "문자", required, null, null, null, null, null)).id();
    }

    private void identifier(GenerationFixture f, long entity, String name, boolean primary, long... attributes) {
        List<Long> ids = new ArrayList<>();
        for (long a : attributes) ids.add(a);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, name, primary, ids, null));
    }

    private long area(GenerationFixture f, Long parent, String name) {
        return areas.save(f.user, new LogicalSubjectAreaDto.Save(null, f.modelId, parent, name, name + " 영역", null, null)).id();
    }

    private void assign(GenerationFixture f, long area, long... entities) {
        List<AssignmentItem> items = new ArrayList<>();
        for (long e : entities) items.add(new AssignmentItem(e, JSON.nullNode()));
        areas.assign(f.user, f.modelId, new Assignment(JSON.numberNode(area), items));
    }

    private static void error(Supplier<?> call, ErrorCode code) {
        assertThatThrownBy(call::get).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static List<Long> ids(LogicalMetadataDto.EntityList list) {
        return list.entities().stream().map(LogicalMetadataDto.EntityItem::entityId).toList();
    }

    private EntitySearch search(Fixture x, String keyword, Long area, Boolean includeChildren, Boolean unclassified, Integer page, Integer limit) {
        return new EntitySearch(x.f.modelId, null, keyword, area, includeChildren, unclassified, page, limit);
    }

    // ------------------------------------------------------------------ 모델 목록·개요

    @Test
    @DisplayName("모델 목록 : 이 프로젝트의 모델만, 모델별 엔터티·관계·영역·그룹 수, 페이지(전체 수·추가 결과·다음 페이지), 잘못된 page")
    void models() {
        Fixture x = fixture("d35-models");
        GenerationFixture empty = x.f.otherModel("빈 모델-" + System.nanoTime());
        Fixture stranger = fixture("d35-models-other");

        LogicalMetadataDto.ModelList list = metadata.models(x.project(), null, null);
        assertThat(list.models()).extracting(LogicalMetadataDto.ModelItem::modelId).containsExactlyInAnyOrder(x.f.modelId, empty.modelId)
                .doesNotContain(stranger.f.modelId);
        assertThat(list.models()).filteredOn(m -> m.modelId().equals(x.f.modelId)).singleElement().satisfies(m -> {
            assertThat(m.entityCount()).isEqualTo(6);
            assertThat(m.relationshipCount()).isEqualTo(1);
            assertThat(m.subjectAreaCount()).isEqualTo(4);
            assertThat(m.subtypeGroupCount()).isEqualTo(2);
        });
        assertThat(list.models()).filteredOn(m -> m.modelId().equals(empty.modelId)).singleElement()
                .satisfies(m -> assertThat(List.of(m.entityCount(), m.relationshipCount(), m.subjectAreaCount(), m.subtypeGroupCount())).containsOnly(0L));
        assertThat(list.paging()).isEqualTo(LogicalMetadataDto.Paging.of(0, 10, 2, 2));

        // 페이지 : 1건씩 — 이름 → ID 순으로 겹치지 않고, 마지막에 추가 결과 없음. limit 은 기존 규칙대로 1~20 로 맞춘다
        LogicalMetadataDto.ModelList first = metadata.models(x.project(), 0, 1);
        LogicalMetadataDto.ModelList second = metadata.models(x.project(), 1, 0);
        assertThat(first.paging()).isEqualTo(LogicalMetadataDto.Paging.of(0, 1, 1, 2));
        assertThat(second.paging()).isEqualTo(LogicalMetadataDto.Paging.of(1, 1, 1, 2));
        assertThat(List.of(first.models().getFirst().modelId(), second.models().getFirst().modelId()))
                .isEqualTo(logical.selectModels(x.f.user).stream().map(LogicalModelDto.Model::id).toList());
        assertThat(metadata.models(x.project(), 9, 1).models()).isEmpty();
        assertThat(metadata.models(x.project(), 0, 500).paging().limit()).isEqualTo(20);
        error(() -> metadata.models(x.project(), -1, null), ErrorCode.AI_QUERY_INVALID);
    }

    @Test
    @DisplayName("모델 개요 : 주제 영역 계층(경로·직접·하위 포함 수)·미분류 수·그룹 요약, 영역·상속 없는 모델, 모델 지정 규칙(ID·이름·불일치·다른 프로젝트)")
    void overview() {
        Fixture x = fixture("d35-overview");
        LogicalMetadataDto.ModelOverview o = metadata.overview(x.project(), new ModelRef(x.f.modelId, null));
        assertThat(o.entityCount()).isEqualTo(6);
        assertThat(o.relationshipCount()).isEqualTo(1);
        assertThat(o.unclassifiedEntityCount()).isEqualTo(1);
        assertThat(o.subjectAreasPaging().totalCount()).isEqualTo(4);
        assertThat(o.subjectAreasPaging().complete()).isTrue();
        assertThat(o.subjectAreasPaging().next()).isNull();
        assertThat(o.subjectAreas()).extracting(LogicalMetadataDto.SubjectAreaItem::subjectAreaId, LogicalMetadataDto.SubjectAreaItem::parentId,
                        LogicalMetadataDto.SubjectAreaItem::depth, LogicalMetadataDto.SubjectAreaItem::path, LogicalMetadataDto.SubjectAreaItem::directEntityCount,
                        LogicalMetadataDto.SubjectAreaItem::totalEntityCount)
                .containsExactlyInAnyOrder(tuple(x.top, null, 1, List.of("고객 관리"), 2L, 4L), tuple(x.mid, x.top, 2, List.of("고객 관리", "개인 고객 관리"), 1L, 2L),
                        tuple(x.leaf, x.mid, 3, List.of("고객 관리", "개인 고객 관리", "우수 고객"), 1L, 1L), tuple(x.orderArea, null, 1, List.of("주문 관리"), 1L, 1L));
        // 영역 순서·수는 화면 트리와 같다 (selectTree 재사용)
        assertThat(o.subjectAreas()).extracting(LogicalMetadataDto.SubjectAreaItem::subjectAreaId)
                .containsExactlyElementsOf(areas.selectTree(x.f.user, x.f.modelId).areas().stream().map(LogicalSubjectAreaDto.Area::id).toList());
        assertThat(o.subtypeGroupsPaging().totalCount()).isEqualTo(2);
        assertThat(o.subtypeGroups()).extracting(LogicalMetadataDto.SubtypeGroupItem::groupId, LogicalMetadataDto.SubtypeGroupItem::supertypeEntityId,
                        LogicalMetadataDto.SubtypeGroupItem::subtypeCount, LogicalMetadataDto.SubtypeGroupItem::overlapping, LogicalMetadataDto.SubtypeGroupItem::total,
                        LogicalMetadataDto.SubtypeGroupItem::classificationAttributeId)
                .containsExactlyInAnyOrder(tuple(x.partyGroup, x.party, 2L, false, true, x.partyKind), tuple(x.customerGroup, x.customer, 2L, false, false, null));

        // 이름으로도 같은 모델, ID 와 이름이 다른 모델이면 거절 (한쪽을 고르지 않는다)
        String name = logical.selectModel(x.f.user, x.f.modelId).modelName();
        assertThat(metadata.overview(x.project(), new ModelRef(null, name)).modelId()).isEqualTo(x.f.modelId);
        assertThat(metadata.overview(x.project(), new ModelRef(null, name.toUpperCase(java.util.Locale.ROOT))).modelId()).isEqualTo(x.f.modelId);
        assertThat(metadata.overview(x.project(), new ModelRef(x.f.modelId, " " + name + " ")).modelId()).isEqualTo(x.f.modelId);
        GenerationFixture empty = x.f.otherModel("빈 모델-" + System.nanoTime());
        error(() -> metadata.overview(x.project(), new ModelRef(empty.modelId, name)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.overview(x.project(), new ModelRef(null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.overview(x.project(), new ModelRef(null, "없는 모델")), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> metadata.overview(x.project(), new ModelRef(-1L, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // 영역·상속 없는 모델 : 빈 목록, 미분류 = 전체
        long lone = logical.saveEntity(x.f.user, new LogicalModelDto.Entity(null, empty.modelId, "단독", null, null, null, null)).id();
        LogicalMetadataDto.ModelOverview plain = metadata.overview(x.project(), new ModelRef(empty.modelId, null));
        assertThat(plain.subjectAreas()).isEmpty();
        assertThat(plain.subtypeGroups()).isEmpty();
        assertThat(plain.unclassifiedEntityCount()).isEqualTo(plain.entityCount()).isEqualTo(1);
        assertThat(lone).isPositive();

        // 다른 프로젝트 : 모델 ID·이름 모두 '없음' (존재를 드러내지 않는다)
        Fixture stranger = fixture("d35-overview-other");
        error(() -> metadata.overview(x.project(), new ModelRef(stranger.f.modelId, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        String strangerName = logical.selectModel(stranger.f.user, stranger.f.modelId).modelName();
        error(() -> metadata.overview(x.project(), new ModelRef(null, strangerName)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
    }

    // ------------------------------------------------------------------ 엔터티 검색

    @Test
    @DisplayName("엔터티 검색 : 전체·영역(하위 포함·직접만)·미분류·검색어+영역, 모순 조건 거절, 다른 모델·프로젝트 영역 거절, 상속 요약·영역 경로")
    void searchEntities() {
        Fixture x = fixture("d35-search");
        LogicalMetadataDto.EntityList all = metadata.searchEntities(x.project(), search(x, null, null, null, null, null, 20));
        assertThat(ids(all)).containsExactlyElementsOf(logical.selectEntityRows(x.f.user, x.f.modelId, null, 0, 20, null).getContent().stream()
                .map(LogicalModelDto.EntityRow::id).toList());
        assertThat(all.paging()).isEqualTo(LogicalMetadataDto.Paging.of(0, 20, 6, 6));
        assertThat(ids(metadata.searchEntities(x.project(), search(x, null, x.top, null, null, null, 20)))).containsExactlyInAnyOrder(x.party, x.customer, x.person, x.corp);
        assertThat(ids(metadata.searchEntities(x.project(), search(x, null, x.top, false, null, null, 20)))).containsExactlyInAnyOrder(x.party, x.customer);
        assertThat(ids(metadata.searchEntities(x.project(), search(x, null, x.mid, true, null, null, 20)))).containsExactlyInAnyOrder(x.person, x.corp);
        assertThat(ids(metadata.searchEntities(x.project(), search(x, null, null, null, true, null, 20)))).containsExactly(x.vendor);
        LogicalMetadataDto.EntityList keyword = metadata.searchEntities(x.project(), search(x, "고객", x.top, null, null, null, 20));
        assertThat(ids(keyword)).containsExactlyInAnyOrder(x.customer, x.person, x.corp);
        assertThat(keyword.scope()).isEqualTo(new LogicalMetadataDto.EntityScope("고객", x.top, true, false));

        error(() -> metadata.searchEntities(x.project(), search(x, null, x.top, null, true, null, 20)), ErrorCode.INVALID_LOGICAL_MODEL);
        GenerationFixture other = x.f.otherModel("다른 모델-" + System.nanoTime());
        long otherArea = area(other, null, "다른 영역");
        error(() -> metadata.searchEntities(x.project(), search(x, null, otherArea, null, null, null, 20)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        Fixture stranger = fixture("d35-search-other");
        error(() -> metadata.searchEntities(x.project(), search(x, null, stranger.top, null, null, null, 20)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> metadata.searchEntities(x.project(), new EntitySearch(stranger.f.modelId, null, null, null, null, null, null, null)),
                ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // 행 : 직접 정의 속성 수, 영역 경로, 상속 요약 (유효 속성·식별자는 목록에 없다)
        var byId = all.entities().stream().collect(java.util.stream.Collectors.toMap(LogicalMetadataDto.EntityItem::entityId, e -> e));
        assertThat(byId.get(x.person).subjectArea()).isEqualTo(new LogicalMetadataDto.SubjectAreaRef(x.mid, "개인 고객 관리", List.of("고객 관리", "개인 고객 관리")));
        assertThat(byId.get(x.vendor).subjectArea()).isNull();
        assertThat(byId.get(x.person).directAttributeCount()).isEqualTo(1);
        assertThat(byId.get(x.customer).relationshipCount()).isEqualTo(1);
        assertThat(byId.get(x.person).relationshipCount()).as("상속 관계는 세지 않는다").isZero();
        assertThat(byId.get(x.customer).inheritance()).satisfies(i -> {
            assertThat(i.participates()).isTrue();
            assertThat(i.level()).isEqualTo(2);
            assertThat(i.supertype()).isEqualTo(new LogicalMetadataDto.EntityName(x.party, "당사자"));
            assertThat(i.parentGroupId()).isEqualTo(x.partyGroup);
            assertThat(i.parentGroupName()).isEqualTo("당사자 구분");
            assertThat(i.subtypeGroups()).singleElement().satisfies(g -> {
                assertThat(g.groupId()).isEqualTo(x.customerGroup);
                assertThat(g.subtypesPaging().totalCount()).isEqualTo(2);
                assertThat(g.subtypes()).extracting(LogicalMetadataDto.EntityName::entityId).containsExactly(x.person, x.corp);
                assertThat(g.subtypesPaging().complete()).isTrue();
            });
        });
        assertThat(byId.get(x.person).inheritance()).satisfies(i -> {
            assertThat(i.level()).isEqualTo(3);
            assertThat(i.supertype().entityId()).isEqualTo(x.customer);
            assertThat(i.subtypeGroups()).isEmpty();
        });
        assertThat(byId.get(x.order).inheritance()).isEqualTo(new LogicalMetadataDto.InheritanceSummary(false, 1, null, null, null, List.of()));
        assertThat(byId.get(x.party).approved()).isTrue();
        assertThat(byId.get(x.customer).approved()).isFalse();
    }

    @Test
    @DisplayName("엔터티 검색 페이지 : 결정적 정렬(이름 → ID), 페이지 경계에서 겹침·빠짐 없음, 전체 수·추가 결과·다음 페이지, limit 최대 20, 음수 page 거절")
    void searchPaging() {
        Fixture x = fixture("d35-paging");
        List<Long> expected = ids(metadata.searchEntities(x.project(), search(x, null, null, null, null, null, 20)));
        List<Long> collected = new ArrayList<>();
        Integer page = 0;
        int calls = 0;
        while (page != null) {
            LogicalMetadataDto.EntityList part = metadata.searchEntities(x.project(), search(x, null, null, null, null, page, 4));
            assertThat(part.paging().totalCount()).isEqualTo(6);
            assertThat(part.paging().hasMore()).isEqualTo(part.paging().nextPage() != null);
            collected.addAll(ids(part));
            page = part.paging().nextPage();
            calls++;
        }
        assertThat(calls).isEqualTo(2);
        assertThat(collected).containsExactlyElementsOf(expected);
        assertThat(metadata.searchEntities(x.project(), search(x, null, null, null, null, 5, 4)).entities()).isEmpty();
        assertThat(metadata.searchEntities(x.project(), search(x, null, null, null, null, null, 999)).paging().limit()).isEqualTo(20);
        assertThat(metadata.searchEntities(x.project(), search(x, null, null, null, null, null, null)).paging().limit()).isEqualTo(10);
        error(() -> metadata.searchEntities(x.project(), search(x, null, null, null, null, -1, 4)), ErrorCode.AI_QUERY_INVALID);
    }

    // ------------------------------------------------------------------ 엔터티 상세

    @Test
    @DisplayName("엔터티 상세 : 3단계 서브타입의 상속 경로·유효 속성(정의 엔터티·깊이·승인)·복합 주 식별자 순서·상속 보조 식별자·상속 관계")
    void multiLevelDetail() {
        Fixture x = fixture("d35-detail");
        LogicalMetadataDto.EntityDetail d = metadata.entityDetail(x.project(), new EntityRef(x.person, null, null, null));
        assertThat(d.modelId()).isEqualTo(x.f.modelId);
        assertThat(d.subjectArea().subjectAreaId()).isEqualTo(x.mid);
        assertThat(d.inheritance().participates()).isTrue();
        assertThat(d.inheritance().level()).isEqualTo(3);
        assertThat(d.inheritance().root()).isEqualTo(new LogicalMetadataDto.EntityName(x.party, "당사자"));
        assertThat(d.inheritance().ancestors()).extracting(LogicalMetadataDto.AncestorItem::entityId, LogicalMetadataDto.AncestorItem::level,
                LogicalMetadataDto.AncestorItem::approved).containsExactly(tuple(x.party, 1, true), tuple(x.customer, 2, false));
        assertThat(d.inheritance().parentGroup().groupId()).isEqualTo(x.customerGroup);
        assertThat(d.inheritance().subtypeGroups()).isEmpty();
        assertThat(d.attributes()).extracting(LogicalMetadataDto.AttributeItem::attributeId, LogicalMetadataDto.AttributeItem::definedEntityId,
                        LogicalMetadataDto.AttributeItem::depth, LogicalMetadataDto.AttributeItem::inherited, LogicalMetadataDto.AttributeItem::approved,
                        LogicalMetadataDto.AttributeItem::primaryKeyOrder)
                .containsExactly(tuple(x.partyNo, x.party, 2, true, true, 2), tuple(x.partyKind, x.party, 2, true, true, 1), tuple(x.regDate, x.party, 2, true, true, null),
                        tuple(x.grade, x.customer, 1, true, false, null), tuple(x.email, x.customer, 1, true, false, null), tuple(x.birth, x.person, 0, false, false, null));
        assertThat(d.attributesPaging().totalCount()).isEqualTo(6);
        assertThat(d.attributesPaging().complete()).isTrue();
        assertThat(d.attributeDescriptions()).isEqualTo("EXCLUDED");
        // 상속 주 식별자 : 루트에 정의, 구성 순서 = 식별자 순서 (구분 → 번호), 이 엔터티의 직접 식별자가 아니다
        assertThat(d.identifiers()).extracting(LogicalMetadataDto.IdentifierItem::kind, LogicalMetadataDto.IdentifierItem::definedEntityId,
                        i -> i.attributes().stream().map(LogicalMetadataDto.IdentifierPart::attributeId).toList(),
                        i -> i.attributes().stream().map(LogicalMetadataDto.IdentifierPart::order).toList())
                .containsExactly(tuple("INHERITED_PRIMARY", x.party, List.of(x.partyKind, x.partyNo), List.of(1, 2)),
                        tuple("INHERITED_ALTERNATE", x.customer, List.of(x.email), List.of(1)));
        assertThat(d.relationships()).singleElement().satisfies(r -> {
            assertThat(r.relationshipId()).isEqualTo(x.relationship);
            assertThat(r.inherited()).isTrue();
            assertThat(r.inheritedFromEntityId()).isEqualTo(x.customer);
            assertThat(r.role()).isEqualTo("SOURCE");
            assertThat(r.attributePairs()).isNull();
        });

        // 같은 계산 : 화면 상속 정보와 속성·식별자 순서가 같다
        LogicalSubtypeDto.Inheritance screen = subtypes.selectInheritance(x.f.user, x.person);
        assertThat(d.attributes()).extracting(LogicalMetadataDto.AttributeItem::attributeId)
                .containsExactlyElementsOf(screen.attributes().stream().map(LogicalSubtypeDto.EffectiveAttribute::attributeId).toList());
        assertThat(d.identifiers()).extracting(LogicalMetadataDto.IdentifierItem::identifierId)
                .containsExactlyElementsOf(screen.identifiers().stream().map(LogicalSubtypeDto.EffectiveIdentifier::identifierId).toList());
    }

    @Test
    @DisplayName("엔터티 상세 : 루트·중간·자체 보조 식별자·상속 없는 엔터티·직접 관계(속성 짝)·미분류")
    void otherDetails() {
        Fixture x = fixture("d35-detail2");
        LogicalMetadataDto.EntityDetail party = metadata.entityDetail(x.project(), new EntityRef(x.party, null, null, null));
        assertThat(party.approved()).isTrue();
        assertThat(party.inheritance().participates()).isTrue();
        assertThat(party.inheritance().level()).isOne();
        assertThat(party.inheritance().ancestors()).isEmpty();
        assertThat(party.inheritance().descendantCount()).isEqualTo(4);
        assertThat(party.inheritance().subtypeGroups()).singleElement().satisfies(g -> {
            assertThat(g.classificationAttributeId()).isEqualTo(x.partyKind);
            assertThat(g.subtypes()).extracting(LogicalMetadataDto.MemberItem::entityId, LogicalMetadataDto.MemberItem::classificationValue)
                    .containsExactly(tuple(x.customer, "C"), tuple(x.vendor, "V"));
        });
        assertThat(party.identifiers()).extracting(LogicalMetadataDto.IdentifierItem::kind, LogicalMetadataDto.IdentifierItem::inherited).containsExactly(tuple("PRIMARY", false));
        assertThat(party.description()).isEqualTo("거래 상대 전체");

        LogicalMetadataDto.EntityDetail customer = metadata.entityDetail(x.project(), new EntityRef(x.customer, null, null, null));
        assertThat(customer.identifiers()).extracting(LogicalMetadataDto.IdentifierItem::kind).containsExactly("INHERITED_PRIMARY", "ALTERNATE");
        assertThat(customer.relationships()).singleElement().satisfies(r -> {
            assertThat(r.inherited()).isFalse();
            assertThat(r.sourceEntityId()).isEqualTo(x.customer);
            assertThat(r.targetEntityId()).isEqualTo(x.order);
            assertThat(r.cardinality()).isEqualTo("1:N");
            assertThat(r.pairStatus()).isEqualTo("COMPLETE");
            assertThat(r.attributePairs()).extracting(LogicalMetadataDto.PairItem::parentAttributeId, LogicalMetadataDto.PairItem::childAttributeId)
                    .containsExactly(tuple(x.partyKind, x.orderPartyKind), tuple(x.partyNo, x.orderPartyNo));
        });

        LogicalMetadataDto.EntityDetail corp = metadata.entityDetail(x.project(), new EntityRef(x.corp, null, null, null));
        assertThat(corp.identifiers()).extracting(LogicalMetadataDto.IdentifierItem::kind, LogicalMetadataDto.IdentifierItem::definedEntityId)
                .containsExactly(tuple("INHERITED_PRIMARY", x.party), tuple("INHERITED_ALTERNATE", x.customer), tuple("ALTERNATE", x.corp));
        assertThat(corp.subjectArea().path()).containsExactly("고객 관리", "개인 고객 관리", "우수 고객");

        LogicalMetadataDto.EntityDetail order = metadata.entityDetail(x.project(), new EntityRef(x.order, null, null, null));
        assertThat(order.inheritance()).isEqualTo(new LogicalMetadataDto.InheritanceDetail(false, 1, new LogicalMetadataDto.EntityName(x.order, "주문"), List.of(), null,
                List.of(), LogicalMetadataDto.Paging.of(0, 20, 0, 0), 0, false));
        assertThat(order.attributes()).allSatisfy(a -> {
            assertThat(a.inherited()).isFalse();
            assertThat(a.depth()).isZero();
        });
        assertThat(order.relationships()).singleElement().satisfies(r -> assertThat(r.inherited()).isFalse());

        assertThat(metadata.entityDetail(x.project(), new EntityRef(x.vendor, null, null, null)).subjectArea()).isNull();
    }

    @Test
    @DisplayName("엔터티 지정 : ID·모델+이름, ID 와 이름·모델 불일치 거절, 이름만으로는 거절, 다른 프로젝트·없는 엔터티는 '없음'")
    void entityResolution() {
        Fixture x = fixture("d35-resolve");
        String model = logical.selectModel(x.f.user, x.f.modelId).modelName();
        assertThat(metadata.entityDetail(x.project(), new EntityRef(null, null, model, "개인고객")).entityId()).isEqualTo(x.person);
        assertThat(metadata.entityDetail(x.project(), new EntityRef(null, x.f.modelId, null, "개인고객")).entityId()).isEqualTo(x.person);
        assertThat(metadata.entityDetail(x.project(), new EntityRef(x.person, x.f.modelId, model, "개인고객")).entityId()).isEqualTo(x.person);
        error(() -> metadata.entityDetail(x.project(), new EntityRef(x.person, null, null, "법인고객")), ErrorCode.AI_QUERY_INVALID);
        GenerationFixture other = x.f.otherModel("다른 모델-" + System.nanoTime());
        error(() -> metadata.entityDetail(x.project(), new EntityRef(x.person, other.modelId, null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), new EntityRef(null, null, null, "개인고객")), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), new EntityRef(null, null, null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), new EntityRef(null, x.f.modelId, null, "없는 엔터티")), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> metadata.entityDetail(x.project(), new EntityRef(-1L, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        Fixture stranger = fixture("d35-resolve-other");
        error(() -> metadata.entityDetail(x.project(), new EntityRef(stranger.person, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        error(() -> subtypes.selectInheritanceView(x.project(), stranger.person), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        // 다른 프로젝트의 그룹 ID 는 같은 모델이 아니므로 빠진다 (모델은 이 프로젝트의 것만)
        assertThat(subtypes.selectGroups(x.project(), x.f.modelId, List.of(stranger.partyGroup, x.partyGroup))).extracting(LogicalSubtypeDto.Group::id).containsExactly(x.partyGroup);
        error(() -> subtypes.selectGroups(x.project(), stranger.f.modelId, List.of(stranger.partyGroup)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
    }

    // ------------------------------------------------------------------ D35-3a : 항목별 조회 · 속성 설명 · 페이지 · 입력 검증

    private static EntityRef section(long entity, String section, Boolean descriptions, Long group, Integer page, Integer limit) {
        return new EntityRef(entity, null, null, null, section, descriptions, group, page, limit);
    }

    @Test
    @DisplayName("D35-3a 속성 설명 : 기본 제외(EXCLUDED·hasDescription 으로 등록 여부), 요청하면 포함(INCLUDED), 미등록과 제외를 구분")
    void attributeDescriptions() {
        Fixture x = fixture("d35-desc");
        logical.saveAttribute(x.f.user, new LogicalModelDto.Attribute(x.birth, x.person, "생년월일", "태어난 날", "문자", false, null, null, null, null, null,
                logical.selectAttributes(x.f.user, x.person).getFirst().modifiedAt()));
        LogicalMetadataDto.EntityDetail plain = metadata.entityDetail(x.project(), new EntityRef(x.person, null, null, null));
        assertThat(plain.attributeDescriptions()).isEqualTo("EXCLUDED");
        assertThat(plain.attributes()).allSatisfy(a -> assertThat(a.description()).isNull());
        assertThat(plain.attributes()).filteredOn(a -> a.attributeId().equals(x.birth)).singleElement().satisfies(a -> assertThat(a.hasDescription()).isTrue());
        assertThat(plain.attributes()).filteredOn(a -> a.attributeId().equals(x.grade)).singleElement().satisfies(a -> assertThat(a.hasDescription()).isFalse());
        LogicalMetadataDto.EntityDetail full = metadata.entityDetail(x.project(), new EntityRef(x.person, null, null, null, null, true, null, null, null));
        assertThat(full.attributeDescriptions()).isEqualTo("INCLUDED");
        assertThat(full.attributes()).filteredOn(a -> a.attributeId().equals(x.birth)).singleElement().satisfies(a -> assertThat(a.description()).isEqualTo("태어난 날"));
        assertThat(full.attributes()).filteredOn(a -> a.attributeId().equals(x.grade)).singleElement().satisfies(a -> {
            assertThat(a.hasDescription()).isFalse();
            assertThat(a.description()).isNull();
        });
        // 화면 속성 조회의 설명은 그대로
        assertThat(logical.selectAttributes(x.f.user, x.person).getFirst().attributeDescription()).isEqualTo("태어난 날");
    }

    @Test
    @DisplayName("D35-3a 항목별 조회 : ATTRIBUTES·IDENTIFIERS·RELATIONS·INHERITANCE 는 그 항목만(나머지 칸 없음), 페이지 합집합 = 전체(순서 그대로), next 로 이어 받기")
    void sections() throws Exception {
        Fixture x = fixture("d35-sections");
        LogicalMetadataDto.EntityDetail all = metadata.entityDetail(x.project(), new EntityRef(x.person, null, null, null));
        assertThat(all.section()).isEqualTo("ALL");
        // 속성 2개씩 : 3페이지, 순서·정의 엔터티 그대로, 마지막 페이지에 next 없음
        List<Long> collected = new ArrayList<>();
        LogicalMetadataDto.Paging paging = null;
        int page = 0;
        do {
            LogicalMetadataDto.EntityDetail part = metadata.entityDetail(x.project(), section(x.person, "ATTRIBUTES", null, null, page, 2));
            assertThat(part.section()).isEqualTo("ATTRIBUTES");
            assertThat(part.identifiers()).isNull();
            assertThat(part.relationships()).isNull();
            assertThat(part.inheritance()).isNull();
            assertThat(part.subjectArea()).isNull();
            paging = part.attributesPaging();
            assertThat(paging.returnedCount()).isEqualTo(part.attributes().size());
            if (paging.hasMore()) assertThat(paging.next()).isEqualTo(new LogicalMetadataDto.NextCall("getLogicalEntityDetail", null, x.person, "ATTRIBUTES", null, null, page + 1, 2));
            else assertThat(paging.next()).isNull();
            part.attributes().forEach(a -> collected.add(a.attributeId()));
            page++;
        } while (paging.hasMore());
        assertThat(page).isEqualTo(3);
        assertThat(collected).containsExactlyElementsOf(all.attributes().stream().map(LogicalMetadataDto.AttributeItem::attributeId).toList());
        assertThat(paging.complete()).isFalse();
        assertThat(metadata.entityDetail(x.project(), section(x.person, "ATTRIBUTES", null, null, 9, 2)).attributes()).isEmpty();

        // 식별자 1개씩 : 구성 속성은 나누지 않는다 (복합 PK 순서 그대로)
        LogicalMetadataDto.EntityDetail id0 = metadata.entityDetail(x.project(), section(x.person, "IDENTIFIERS", null, null, 0, 1));
        assertThat(id0.attributes()).isNull();
        assertThat(id0.identifiers()).singleElement().satisfies(i -> assertThat(i.attributes()).extracting(LogicalMetadataDto.IdentifierPart::attributeId)
                .containsExactly(x.partyKind, x.partyNo));
        assertThat(id0.identifiersPaging().totalCount()).isEqualTo(2);
        assertThat(metadata.entityDetail(x.project(), section(x.person, "IDENTIFIERS", null, null, 1, 1)).identifiers()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo("INHERITED_ALTERNATE"));

        // 관계 : 직접 → 상속 순서
        LogicalMetadataDto.EntityDetail relations = metadata.entityDetail(x.project(), section(x.customer, "RELATIONS", null, null, null, null));
        assertThat(relations.relationshipsPaging().limit()).isEqualTo(20);
        assertThat(relations.relationships()).singleElement().satisfies(r -> assertThat(r.inherited()).isFalse());

        // 상속 : 그룹 목록, 그룹 하나의 직접 서브타입 1개씩
        LogicalMetadataDto.EntityDetail inheritance = metadata.entityDetail(x.project(), section(x.party, "INHERITANCE", null, null, null, null));
        assertThat(inheritance.attributes()).isNull();
        assertThat(inheritance.inheritance().subtypeGroups()).singleElement().satisfies(g -> assertThat(g.groupId()).isEqualTo(x.partyGroup));
        assertThat(inheritance.inheritance().subtypeGroupsPaging().complete()).isTrue();
        List<Long> members = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            LogicalMetadataDto.EntityDetail one = metadata.entityDetail(x.project(), section(x.party, "INHERITANCE", null, x.partyGroup, p, 1));
            assertThat(one.inheritance().subtypeGroupsPaging()).isNull();
            LogicalMetadataDto.GroupDetail g = one.inheritance().subtypeGroups().getFirst();
            g.subtypes().forEach(m -> members.add(m.entityId()));
            if (p == 0) assertThat(g.subtypesPaging().next()).isEqualTo(new LogicalMetadataDto.NextCall("getLogicalEntityDetail", null, x.party, "INHERITANCE", x.partyGroup, null, 1, 1));
        }
        assertThat(members).containsExactly(x.customer, x.vendor);
        // 이 엔터티가 슈퍼타입이 아닌 그룹은 '없음'
        error(() -> metadata.entityDetail(x.project(), section(x.party, "INHERITANCE", null, x.customerGroup, 0, 1)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // 직렬화 : 조회하지 않은 항목은 칸이 없다 (빈 목록과 구분)
        String json = mapper.writeValueAsString(metadata.entityDetail(x.project(), section(x.order, "IDENTIFIERS", null, null, null, null)));
        assertThat(json).contains("\"section\":\"IDENTIFIERS\"", "\"identifiers\":[").doesNotContain("\"attributesPaging\"", "\"relationships\"", "\"inheritance\"",
                "\"attributeDescriptions\"");
        assertThat(mapper.writeValueAsString(metadata.entityDetail(x.project(), section(x.order, "INHERITANCE", null, null, null, null))))
                .contains("\"subtypeGroups\":[]");
    }

    @Test
    @DisplayName("D35-3a 입력 검증 : 잘못된 section·ALL 과 page/limit·관계없는 선택 인자·음수·넘치는 page → AI_QUERY_INVALID, limit 은 기존 규칙대로 맞춤")
    void sectionValidation() {
        Fixture x = fixture("d35-validate");
        error(() -> metadata.entityDetail(x.project(), section(x.person, "COLUMNS", null, null, null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), section(x.person, null, null, null, 0, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), section(x.person, "ALL", null, null, null, 5)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), section(x.person, "RELATIONS", true, null, null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), section(x.person, "ATTRIBUTES", null, x.partyGroup, null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), section(x.person, "ATTRIBUTES", null, null, -1, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.entityDetail(x.project(), section(x.person, "ATTRIBUTES", null, null, Integer.MAX_VALUE, 100)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.overview(x.project(), new LogicalMetadataDto.OverviewRequest(x.f.modelId, null, "ENTITIES", null, null)), ErrorCode.AI_QUERY_INVALID);
        error(() -> metadata.overview(x.project(), new LogicalMetadataDto.OverviewRequest(x.f.modelId, null, null, 1, null)), ErrorCode.AI_QUERY_INVALID);
        // 소문자 section 허용, limit 은 그 항목 상한으로 (속성 100, 관계 50, 서브타입 20)
        assertThat(metadata.entityDetail(x.project(), section(x.person, "attributes", false, null, null, 999)).attributesPaging().limit()).isEqualTo(100);
        assertThat(metadata.entityDetail(x.project(), section(x.person, "RELATIONS", null, null, null, 999)).relationshipsPaging().limit()).isEqualTo(50);
        assertThat(metadata.entityDetail(x.project(), section(x.party, "INHERITANCE", null, null, null, 999)).inheritance().subtypeGroupsPaging().limit()).isEqualTo(20);
        assertThat(metadata.entityDetail(x.project(), section(x.person, "IDENTIFIERS", null, null, null, 0)).identifiersPaging().limit()).isEqualTo(1);
        // 개요 항목 조회 : 영역 2개씩, 계층(parentId·path)은 그대로, 페이지 결과는 complete 아님
        LogicalMetadataDto.ModelOverview areas = metadata.overview(x.project(), new LogicalMetadataDto.OverviewRequest(x.f.modelId, null, "SUBJECT_AREAS", 1, 2));
        assertThat(areas.subtypeGroups()).isNull();
        assertThat(areas.subjectAreas()).hasSize(2).allSatisfy(a -> assertThat(a.path()).isNotEmpty());
        assertThat(areas.subjectAreasPaging()).satisfies(pg -> {
            assertThat(pg.complete()).isFalse();
            assertThat(pg.hasMore()).isFalse();
            assertThat(pg.totalCount()).isEqualTo(4);
        });
        LogicalMetadataDto.ModelOverview groups = metadata.overview(x.project(), new LogicalMetadataDto.OverviewRequest(null, logical.selectModel(x.f.user, x.f.modelId).modelName(),
                "SUBTYPE_GROUPS", 0, 1));
        assertThat(groups.subjectAreas()).isNull();
        assertThat(groups.subtypeGroupsPaging().next()).isEqualTo(new LogicalMetadataDto.NextCall("getLogicalModelOverview", x.f.modelId, null, "SUBTYPE_GROUPS", null, null, 1, 1));
    }

    // ------------------------------------------------------------------ 세션 없는 조회 · 화면 호환 · 직렬화

    @Test
    @DisplayName("로그인 세션 없이 (MCP·비동기) : 승인된 엔터티의 상속 정보·상세·목록·개요가 AuthUtil 없이 조회된다")
    void withoutSession() {
        Fixture x = fixture("d35-nosession");
        SecurityTestSupport.clear();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 승인된 루트 : 화면 경로는 editable 계산에 AuthUtil(로그인 세션)을 쓴다 — 공통 계산은 쓰지 않는다
        LogicalSubtypeDto.Inheritance view = subtypes.selectInheritanceView(x.project(), x.party);
        assertThat(view.isApproval()).isTrue();
        assertThat(view.attributes()).allSatisfy(a -> assertThat(a.editable()).isFalse());
        assertThat(metadata.entityDetail(x.project(), new EntityRef(x.party, null, null, null)).approved()).isTrue();
        assertThat(metadata.entityDetail(x.project(), new EntityRef(x.corp, null, null, null)).attributes()).hasSize(6);
        assertThat(metadata.searchEntities(x.project(), search(x, null, null, null, null, null, 20)).entities()).hasSize(6);
        assertThat(metadata.overview(x.project(), new ModelRef(x.f.modelId, null)).subjectAreas()).hasSize(4);
        assertThat(metadata.models(x.project(), null, null).models()).isNotEmpty();
        error(() -> metadata.models(null, null, null), ErrorCode.PROJECT_ACCESS_DENIED);
    }

    @Test
    @DisplayName("화면 호환 : 공통화 뒤에도 화면 상속 정보·유효 속성의 editable 이 지금 사용자 기준 (승인 권한자·일반 사용자·승인된 후손), 계산 결과는 공통 경로와 같다")
    void screenCompatibility() {
        Fixture x = fixture("d35-screen");
        // 관리자(승인 권한자) : 승인된 루트의 직접 속성도 editable, 상속 속성은 아님
        LogicalSubtypeDto.Inheritance admin = subtypes.selectInheritance(x.f.user, x.party);
        assertThat(admin.attributes()).extracting(LogicalSubtypeDto.EffectiveAttribute::editable).containsOnly(true);
        assertThat(subtypes.selectEffectiveAttributes(x.f.user, x.party).attributes()).extracting(LogicalSubtypeDto.EffectiveAttribute::editable).containsOnly(true);
        assertThat(subtypes.selectInheritance(x.f.user, x.person).attributes()).extracting(LogicalSubtypeDto.EffectiveAttribute::editable)
                .containsExactly(false, false, false, false, false, true);
        // 일반 사용자(승인 권한 없음) : 승인된 엔터티의 직접 속성은 editable 아님
        AccountDto user = SecurityTestSupport.loginAs(projects, x.project(), "987654321", "ROLE_USER");
        assertThat(subtypes.selectInheritance(user, x.party).attributes()).extracting(LogicalSubtypeDto.EffectiveAttribute::editable).containsOnly(false);
        assertThat(subtypes.selectInheritance(user, x.customer).attributes()).filteredOn(a -> !a.inherited())
                .extracting(LogicalSubtypeDto.EffectiveAttribute::editable).containsOnly(true);
        // 승인된 후손이 있으면 관리자도 editable 아님
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", x.person);
        SecurityTestSupport.loginAs(projects, x.project(), x.f.user.getId(), "ROLE_ADMIN");
        assertThat(subtypes.selectInheritance(x.f.user, x.customer).attributes()).filteredOn(a -> !a.inherited())
                .extracting(LogicalSubtypeDto.EffectiveAttribute::editable).containsOnly(false);

        // editable 을 뺀 나머지는 화면 경로와 공통 경로가 같다
        for (long entity : new long[]{x.party, x.customer, x.vendor, x.person, x.corp, x.order}) {
            LogicalSubtypeDto.Inheritance screen = subtypes.selectInheritance(x.f.user, entity);
            LogicalSubtypeDto.Inheritance view = subtypes.selectInheritanceView(x.project(), entity);
            assertThat(withoutEditable(view)).isEqualTo(withoutEditable(screen));
        }
    }

    private static LogicalSubtypeDto.Inheritance withoutEditable(LogicalSubtypeDto.Inheritance i) {
        List<LogicalSubtypeDto.EffectiveAttribute> attributes = i.attributes().stream().map(a -> new LogicalSubtypeDto.EffectiveAttribute(a.attributeId(), a.contextEntityId(),
                a.definedEntityId(), a.definedEntityName(), a.inherited(), a.depth(), false, a.attributeName(), a.attributeDescription(), a.dataType(), a.isRequired(),
                a.sortOrder(), a.termId(), a.termName(), a.domainName(), a.primary(), a.keyOrder(), a.modifiedAt())).toList();
        return new LogicalSubtypeDto.Inheritance(i.entityId(), i.entityName(), i.isApproval(), i.modifiedAt(), i.level(), i.rootEntityId(), i.rootEntityName(), i.parent(),
                i.ancestors(), i.groups(), i.descendantCount(), i.rootPrimaryKeyMissing(), attributes, i.identifiers(), i.inheritedRelationships());
    }

    @Test
    @DisplayName("직렬화 : 화면 전용 칸(editable·수정 일시·정렬 순서·좌표)·계정 정보가 없고, 빈 목록은 []·없는 값은 생략, 긴 설명은 300자로 잘라 표시")
    void serialization() throws Exception {
        Fixture x = fixture("d35-json");
        String longText = "가".repeat(400);
        jdbc.update("update TB_LGCL_ENTT set ENTT_EXPLN = ? where LGCL_ENTT_SN = ?", longText, x.order);
        String detail = mapper.writeValueAsString(metadata.entityDetail(x.project(), new EntityRef(x.person, null, null, null)));
        String list = mapper.writeValueAsString(metadata.searchEntities(x.project(), search(x, null, null, null, null, null, 20)));
        String overview = mapper.writeValueAsString(metadata.overview(x.project(), new ModelRef(x.f.modelId, null)));
        String models = mapper.writeValueAsString(metadata.models(x.project(), null, null));
        for (String json : List.of(detail, list, overview, models)) {
            assertThat(json).doesNotContain("\"editable\"", "\"modifiedAt\"", "\"sortOrder\"", "\"contextEntityId\"", "\"position\"", "\"x\":", "password", "token",
                    "\"projectId\"", "\"username\"");
        }
        assertThat(detail).contains("\"subtypeGroups\":[]", "\"inheritedFromEntityId\"", "\"primaryKeyOrder\":1");
        assertThat(list).doesNotContain("\"attributes\"", "\"identifiers\"");
        LogicalMetadataDto.EntityDetail order = metadata.entityDetail(x.project(), new EntityRef(x.order, null, null, null));
        assertThat(order.description()).hasSize(301).endsWith("…");
        assertThat(mapper.writeValueAsString(order)).doesNotContain("\"parentGroup\"");
        // 문서(docs/completed/ai-logical-model-query-d35.md)의 응답 예시 : 테스트 보고서의 표준 출력으로 남긴다
        System.out.println("[D35-2 예시] overview " + overview);
        System.out.println("[D35-2 예시] search " + mapper.writeValueAsString(metadata.searchEntities(x.project(), search(x, "고객", x.top, null, null, 0, 2))));
        System.out.println("[D35-2 예시] detail " + detail);
        System.out.println("[D35-2 예시] detailNoInheritance " + mapper.writeValueAsString(metadata.entityDetail(x.project(), new EntityRef(x.order, null, null, null))));
        System.out.println("[D35-2 예시] models " + models);
    }
}
