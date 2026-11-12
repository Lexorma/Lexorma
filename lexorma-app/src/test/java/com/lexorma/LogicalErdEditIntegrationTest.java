package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.logical.controller.LogicalModelRestController;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Graph;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.GraphAttribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.GraphEntity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Layout;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Point;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Relationship;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Viewport;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-8 1차 : 논리 ERD 편집이 쓰는 호출 순서 (H2 / PostgreSQL). 화면은 새 API 없이 논리 모델 관리와 같은 API 를 쓴다.
 * ERD 조회(Graph)가 엔터티·속성 변경을 그대로 돌려주는지, 모델 변경과 배치 저장이 서로 독립인지, 거절된 변경 뒤에 상태가 그대로인지,
 * 삭제·승인·매핑 보호와 권한·프로젝트 격리가 ERD 경로에서도 같은지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalErdEditIntegrationTest {

    private static final Viewport VIEWPORT = new Viewport(0, 0, 1);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService service;
    @Autowired LogicalModelRestController controller;
    @Autowired ModelMappingService mappingService;
    @Autowired PhysicalNameRecommendationService recommendationService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("ERD 편집 : 엔터티·속성 변경이 ERD 조회에 반영, 배치와 독립, 거절 뒤 상태 유지, 삭제·승인·매핑 보호, 표준 용어 변경 시 물리 불변, 권한·격리")
    void editFromErd() {
        AccountDto admin = loginAsAdmin("m8-erd-edit");
        long projectId = admin.getProjectId();
        StandardTerm memberNo = term(projectId, "회원_번호", "MBR_NO");
        StandardTerm memberId = term(projectId, "회원_아이디", "MBR_ID");
        Model model = service.saveModel(admin, new Model(null, "회원 관리", null));
        Entity member = entity(admin, model, "회원");
        Entity order = entity(admin, model, "주문");
        Layout saved = service.saveLayout(admin, model.id(), new Layout(null, Map.of(member.id(), new Point(100, 100), order.id(), new Point(500, 100)), VIEWPORT));

        // ---- 엔터티 생성 : ERD 조회에 나타나고, 저장된 배치는 그대로다 (새 노드 위치는 화면이 정해 [배치 저장] 때 보낸다)
        Entity grade = entity(admin, model, "등급");
        assertThat(entityNames(admin, model)).containsExactlyInAnyOrder("회원", "주문", "등급");
        assertThat(service.selectLayout(admin, model.id())).isEqualTo(saved);
        Layout withNew = service.saveLayout(admin, model.id(), new Layout(saved.version(), Map.of(grade.id(), new Point(300, 400)), VIEWPORT));
        assertThat(service.selectLayout(admin, model.id()).nodes()).containsOnly(
                Map.entry(member.id(), new Point(100, 100)), Map.entry(order.id(), new Point(500, 100)), Map.entry(grade.id(), new Point(300, 400)));
        // 거절 : 같은 이름(대소문자·공백 무시), 이름 없음 → ERD 는 그대로
        rejected(() -> entity(admin, model, " 등급 "), ErrorCode.SAVED_DATA_EXISTS);
        assertThatThrownBy(() -> entity(admin, model, " ")).isInstanceOf(CustomException.class);
        assertThat(entityNames(admin, model)).hasSize(3);

        // ---- 승인 정책 : 승인 권한자가 만든 엔터티는 승인 상태다. 엔터티명·설명 변경과 삭제는 승인취소 후에
        assertThat(graphEntity(admin, model, "등급").isApproval()).isTrue();
        rejected(() -> service.saveEntity(admin, new Entity(grade.id(), model.id(), "회원 등급", "설명", null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.deleteEntity(admin, grade.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        assertThat(entityNames(admin, model)).contains("등급");
        service.updateEntityApproval(admin, grade.id(), false);

        // ---- 엔터티 수정 : 노드에 보이는 이름·설명이 바뀌고 배치는 그대로다
        service.saveEntity(admin, new Entity(grade.id(), model.id(), "회원 등급", "회원의 등급", null, null, null));
        assertThat(graphEntity(admin, model, "회원 등급")).extracting(GraphEntity::id, GraphEntity::description, GraphEntity::isApproval).containsExactly(grade.id(), "회원의 등급", false);
        rejected(() -> service.saveEntity(admin, new Entity(grade.id(), model.id(), "회원", null, null, null, null)), ErrorCode.SAVED_DATA_EXISTS);
        assertThat(service.selectLayout(admin, model.id())).isEqualTo(withNew);

        // ---- 속성 추가·수정·순서·표준 용어 연결·변경·해제
        Long no = service.saveAttribute(admin, new Attribute(null, grade.id(), "등급번호", null, null, true, null, memberNo.getId(), null, null, null)).id();
        Long name = service.saveAttribute(admin, new Attribute(null, grade.id(), "등급명", "이름", "문자", false, null, null, null, null, null)).id();
        Long memo = service.saveAttribute(admin, new Attribute(null, grade.id(), "메모", null, null, false, null, null, null, null, null)).id();
        assertThat(graphEntity(admin, model, "회원 등급").attributes()).extracting(GraphAttribute::name, GraphAttribute::isRequired, GraphAttribute::termName, GraphAttribute::dataType)
                .containsExactly(tuple("등급번호", true, "회원_번호", null), tuple("등급명", false, null, "문자"), tuple("메모", false, null, null));
        rejected(() -> service.saveAttribute(admin, new Attribute(null, grade.id(), "등급명", null, null, false, null, null, null, null, null)), ErrorCode.SAVED_DATA_EXISTS);
        service.saveAttribute(admin, new Attribute(name, grade.id(), "등급 이름", "이름", "문자", true, null, memberId.getId(), null, null, null));
        service.reorderAttributes(admin, grade.id(), List.of(memo, name, no));
        assertThat(graphEntity(admin, model, "회원 등급").attributes()).extracting(GraphAttribute::name, GraphAttribute::isRequired, GraphAttribute::termName)
                .containsExactly(tuple("메모", false, null), tuple("등급 이름", true, "회원_아이디"), tuple("등급번호", true, "회원_번호"));
        service.saveAttribute(admin, new Attribute(name, grade.id(), "등급 이름", "이름", "문자", true, null, null, null, null, null));
        assertThat(attribute(admin, model, "회원 등급", "등급 이름").termId()).isNull();
        // 다른 엔터티의 속성을 섞은 순서 변경은 거절되고 순서는 그대로다
        Long orderNo = service.saveAttribute(admin, new Attribute(null, order.id(), "주문번호", null, null, true, null, null, null, null, null)).id();
        assertThatThrownBy(() -> service.reorderAttributes(admin, grade.id(), List.of(memo, name, orderNo))).isInstanceOf(CustomException.class);
        assertThat(graphEntity(admin, model, "회원 등급").attributes()).extracting(GraphAttribute::id).containsExactly(memo, name, no);
        assertThat(service.selectLayout(admin, model.id())).as("속성 편집은 배치를 바꾸지 않는다").isEqualTo(withNew);

        // ---- 삭제 보호 : 식별자에 쓰인 속성, 관계에 쓰인 엔터티, 매핑된 엔터티·속성 → 거절되고 ERD 는 그대로다
        Identifier identifier = service.saveIdentifier(admin, new Identifier(null, grade.id(), "등급 식별자", true, List.of(no), null));
        rejected(() -> service.deleteAttribute(admin, no), ErrorCode.RELATION_EXISTS);
        assertThat(attribute(admin, model, "회원 등급", "등급번호").primaryIdentifier()).isTrue();
        service.updateEntityApproval(admin, member.id(), false);
        Relationship relationship = service.saveRelationship(admin, new Relationship(null, model.id(), "보유", null, grade.id(), null, member.id(), null, false, 1, 1, 0, null, null, null));
        rejected(() -> service.deleteEntity(admin, grade.id()), ErrorCode.RELATION_EXISTS);
        TableInfo table = table(projectId, "TB_MBR_GRD");
        ColumnInfo column = column(projectId, table, "GRD_MEMO", null);
        EntityMapping mapping = mappingService.saveEntityMapping(admin, new EntityMapping(null, grade.id(), table.getId()));
        AttributeMapping attributeMapping = mappingService.saveAttributeMapping(admin, new AttributeMapping(null, mapping.id(), memo, column.getId()));
        rejected(() -> service.deleteAttribute(admin, memo), ErrorCode.RELATION_EXISTS);
        service.updateRelationshipApproval(admin, relationship.id(), false); // 승인 권한자가 만든 관계는 승인 상태다
        service.deleteRelationship(admin, relationship.id());
        rejected(() -> service.deleteEntity(admin, grade.id()), ErrorCode.RELATION_EXISTS);
        assertThat(graphEntity(admin, model, "회원 등급").attributes()).extracting(GraphAttribute::id).containsExactly(memo, name, no);

        // ---- 표준 용어를 바꿔도 물리 컬럼·매핑은 그대로고, 물리명 추천은 다음 조회에 바뀐 용어로 계산된다 (M-7)
        service.saveAttribute(admin, new Attribute(memo, grade.id(), "메모", null, null, false, null, memberId.getId(), null, null, null));
        assertThat(columnInfoRepository.findById(column.getId()).orElseThrow()).extracting(ColumnInfo::getColumnName, ColumnInfo::getStandardTerms).containsExactly("GRD_MEMO", null);
        assertThat(mappingService.selectEntityMapping(admin, mapping.id()).attributes()).filteredOn(a -> a.attributeId().equals(memo))
                .extracting(a -> a.mappingId(), a -> a.columnName(), a -> a.attributeTermName()).containsExactly(tuple(attributeMapping.id(), "GRD_MEMO", "회원_아이디"));
        assertThat(recommendationService.selectColumnRows(admin, new ColumnSearch(model.id(), null, memo, null, null, null, null), PageRequest.of(0, 5)).getContent())
                .extracting(ColumnRow::currentPhysicalName, ColumnRow::recommendedPhysicalName, ColumnRow::status).containsExactly(tuple("GRD_MEMO", "MBR_ID", Status.AVAILABLE));

        // ---- 권한 : VIEWER 는 조회·자기 배치 저장만, USER 는 편집하되 승인된 엔터티의 속성은 못 바꾼다, 미승인 사용자는 조회도 불가
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "930", "ROLE_VIEWER");
        assertThat(controller.selectGraph(viewer, model.id()).getBody().entities()).hasSize(3);
        assertThatThrownBy(() -> controller.saveEntity(viewer, new Entity(null, model.id(), "뷰어 엔터티", null, null, null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteEntity(viewer, order.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveAttribute(viewer, new Attribute(null, grade.id(), "뷰어 속성", null, null, false, null, null, null, null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteAttribute(viewer, name)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.reorderAttributes(viewer, grade.id(), List.of(no, name, memo))).isInstanceOf(AccessDeniedException.class);
        assertThat(controller.saveLayout(viewer, model.id(), new Layout(null, Map.of(grade.id(), new Point(1, 2)), VIEWPORT)).getBody().version()).isNotNull();
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "931", "ROLE_USER");
        Entity userEntity = controller.saveEntity(user, new Entity(null, model.id(), "사용자 엔터티", null, null, null, null)).getBody();
        assertThat(userEntity.isApproval()).as("승인 권한이 없는 사용자가 만든 엔터티는 승인 대기").isFalse();
        Long userAttribute = controller.saveAttribute(user, new Attribute(null, userEntity.id(), "속성", null, null, false, null, memberNo.getId(), null, null, null)).getBody().id();
        controller.deleteAttribute(user, userAttribute);
        controller.deleteEntity(user, userEntity.id());
        rejected(() -> controller.saveAttribute(user, new Attribute(null, order.id(), "승인된 엔터티의 속성", null, null, false, null, null, null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> controller.deleteEntity(user, order.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, projectId, "932", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectGraph(notApproved, model.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveEntity(notApproved, new Entity(null, model.id(), "미승인", null, null, null, null))).isInstanceOf(AccessDeniedException.class);

        // ---- 프로젝트 격리 : 다른 프로젝트의 모델·엔터티·속성은 만들 수도 고칠 수도 지울 수도 없다
        AccountDto other = loginAsAdmin("m8-erd-edit-other");
        rejected(() -> service.selectGraph(other, model.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> entity(other, model, "침입"), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveEntity(other, new Entity(grade.id(), model.id(), "침입", null, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteEntity(other, grade.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveAttribute(other, new Attribute(null, grade.id(), "침입", null, null, false, null, null, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteAttribute(other, name), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.reorderAttributes(other, grade.id(), List.of(no, name, memo)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // ---- 삭제 : 참조를 정리한 뒤에만 지워지고, ERD 조회와 배치 조회에서 빠진다 (다른 노드의 위치는 그대로)
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertThat(entityNames(admin, model)).containsExactlyInAnyOrder("회원", "주문", "회원 등급");
        mappingService.deleteAttributeMapping(admin, attributeMapping.id());
        mappingService.deleteEntityMapping(admin, mapping.id());
        service.deleteAttribute(admin, memo);
        service.deleteIdentifier(admin, identifier.id());
        service.deleteAttribute(admin, no);
        assertThat(graphEntity(admin, model, "회원 등급").attributes()).extracting(GraphAttribute::name).containsExactly("등급 이름");
        service.deleteEntity(admin, grade.id());
        Graph graph = service.selectGraph(admin, model.id());
        assertThat(graph.entities()).extracting(GraphEntity::name).containsExactlyInAnyOrder("회원", "주문");
        assertThat(graph.relationships()).isEmpty();
        assertThat(service.selectLayout(admin, model.id()).nodes()).containsOnly(Map.entry(member.id(), new Point(100, 100)), Map.entry(order.id(), new Point(500, 100)));
        assertThat(tableInfoRepository.findById(table.getId())).as("엔터티를 지워도 물리 테이블은 그대로").isPresent();
    }

    @Test
    @DisplayName("ERD 편집 2차 : 관계 생성·수정·삭제(방향·유형·참여 수, 자기 참조, 같은 쌍의 여러 관계), 식별자(주·보조·복합·순서), 관계와 식별자는 서로 자동으로 바꾸지 않는다, 배치와 독립, 승인·권한·격리")
    void relationshipsAndIdentifiersFromErd() {
        AccountDto admin = loginAsAdmin("m8-erd-relation");
        long projectId = admin.getProjectId();
        Model model = service.saveModel(admin, new Model(null, "주문 관리", null));
        Model otherModel = service.saveModel(admin, new Model(null, "다른 모델", null));
        Entity member = entity(admin, model, "회원");
        Entity order = entity(admin, model, "주문");
        Entity item = entity(admin, model, "주문상세");
        Entity foreign = entity(admin, otherModel, "외부");
        Long orderNo = attributeOf(admin, order, "주문번호");
        Long itemOrderNo = attributeOf(admin, item, "주문번호");
        Long itemProductNo = attributeOf(admin, item, "상품번호");
        Long itemQuantity = attributeOf(admin, item, "수량");
        Layout saved = service.saveLayout(admin, model.id(), new Layout(null, Map.of(member.id(), new Point(0, 0), order.id(), new Point(400, 0), item.id(), new Point(800, 0)), VIEWPORT));

        // ---- 관계 생성 : 연결한 방향 그대로가 아니라 폼에서 정한 출발(부모)·도착(자식)으로 저장된다. 승인 권한자가 만들면 승인 상태
        Relationship orders = relation(admin, model, null, "주문한다", member.id(), order.id(), false, 1, 1, 0, null);
        assertThat(orders).extracting(Relationship::sourceEntityName, Relationship::targetEntityName, Relationship::isIdentifying, Relationship::cardinality, Relationship::isApproval)
                .containsExactly("회원", "주문", false, "1:N", true);
        // 같은 두 엔터티 사이의 여러 관계, 자기 참조, 이름 없는 관계를 막지 않는다 (기존 정책)
        Relationship recommends = relation(admin, model, null, "추천한다", member.id(), order.id(), false, 0, 1, 0, null);
        Relationship reverse = relation(admin, model, null, null, order.id(), member.id(), false, 0, null, 0, 1);
        Relationship self = relation(admin, model, null, "추천인", member.id(), member.id(), false, 0, 1, 0, null);
        Relationship details = relation(admin, model, null, "포함한다", order.id(), item.id(), true, 1, 1, 1, null);
        assertThat(service.selectGraph(admin, model.id()).relationships()).extracting(Relationship::id)
                .containsExactlyInAnyOrder(orders.id(), recommends.id(), reverse.id(), self.id(), details.id());
        assertThat(reverse).extracting(Relationship::relationshipName, Relationship::cardinality).containsExactly(null, "N:1");
        // 거절 : 다른 모델의 엔터티, 없는 엔터티, 잘못된 참여 수 → 관계는 생기지 않는다
        rejected(() -> relation(admin, model, null, "외부 참조", member.id(), foreign.id(), false, 1, 1, 0, null), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> relation(admin, model, null, "없는 엔터티", member.id(), -1L, false, 1, 1, 0, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThatThrownBy(() -> relation(admin, model, null, "잘못된 참여 수", member.id(), order.id(), false, 2, 1, 0, null)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> relation(admin, model, null, "음수", member.id(), order.id(), false, -1, 1, 0, null)).isInstanceOf(CustomException.class);
        assertThat(service.selectGraph(admin, model.id()).relationships()).hasSize(5);
        assertThat(service.selectLayout(admin, model.id())).as("관계 변경은 배치를 바꾸지 않는다").isEqualTo(saved);

        // ---- 식별 관계를 만들어도 자식 엔터티의 식별자·속성은 자동으로 생기지 않는다
        assertThat(service.selectEntity(admin, item.id()).identifiers()).isEmpty();
        assertThat(service.selectEntity(admin, item.id()).attributes()).hasSize(3);

        // ---- 관계 수정 : 승인된 관계는 승인취소 후에. 이름·유형·참여 수·방향을 바꿀 수 있다
        rejected(() -> relation(admin, model, orders.id(), "주문", member.id(), order.id(), true, 1, 1, 1, null), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.deleteRelationship(admin, orders.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        service.updateRelationshipApproval(admin, orders.id(), false);
        Relationship changed = relation(admin, model, orders.id(), "주문 소유", member.id(), order.id(), true, 1, 1, 1, 5);
        assertThat(changed).extracting(Relationship::id, Relationship::relationshipName, Relationship::isIdentifying, Relationship::targetMin, Relationship::targetMax, Relationship::cardinality)
                .containsExactly(orders.id(), "주문 소유", true, 1, 5, "1:N");
        Relationship flipped = relation(admin, model, orders.id(), "주문 소유", order.id(), member.id(), false, 0, null, 1, 1);
        assertThat(flipped).extracting(Relationship::sourceEntityName, Relationship::targetEntityName, Relationship::cardinality).containsExactly("주문", "회원", "N:1");
        rejected(() -> relation(admin, model, orders.id(), "외부로", order.id(), foreign.id(), false, 1, 1, 0, null), ErrorCode.INVALID_LOGICAL_MODEL);
        assertThat(service.selectGraph(admin, model.id()).relationships()).filteredOn(r -> r.id().equals(orders.id()))
                .extracting(Relationship::sourceEntityName, Relationship::targetEntityName).containsExactly(tuple("주문", "회원"));

        // ---- 식별자 : 주·보조, 복합(순서대로), 구성·순서 변경, 다른 엔터티 속성 차단. 승인된 엔터티는 승인 권한자만 바꾼다
        Identifier primary = service.saveIdentifier(admin, new Identifier(null, item.id(), "주문상세 식별자", true, List.of(itemOrderNo, itemProductNo), null));
        assertThat(primary).extracting(Identifier::isPrimary, Identifier::attributeNames).containsExactly(true, List.of("주문번호", "상품번호"));
        Identifier alternate = service.saveIdentifier(admin, new Identifier(null, item.id(), "수량 식별자", false, List.of(itemQuantity), null));
        assertThat(graphEntity(admin, model, "주문상세").attributes()).extracting(GraphAttribute::name, GraphAttribute::identifier, GraphAttribute::primaryIdentifier)
                .containsExactly(tuple("주문번호", true, true), tuple("상품번호", true, true), tuple("수량", true, false));
        assertThatThrownBy(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "두 번째 주 식별자", true, List.of(itemQuantity), null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "다른 엔터티 속성", false, List.of(orderNo), null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "빈 식별자", false, List.of(), null))).isInstanceOf(CustomException.class);
        rejected(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "수량 식별자", false, List.of(itemProductNo), null)), ErrorCode.SAVED_DATA_EXISTS);
        Identifier reordered = service.saveIdentifier(admin, new Identifier(primary.id(), item.id(), "주문상세 PK", true, List.of(itemProductNo, itemOrderNo, itemQuantity), null));
        assertThat(reordered).extracting(Identifier::id, Identifier::identifierName, Identifier::attributeNames).containsExactly(primary.id(), "주문상세 PK", List.of("상품번호", "주문번호", "수량"));
        assertThat(service.selectEntity(admin, item.id()).identifiers()).extracting(Identifier::identifierName, Identifier::attributeIds)
                .containsExactlyInAnyOrder(tuple("주문상세 PK", List.of(itemProductNo, itemOrderNo, itemQuantity)), tuple("수량 식별자", List.of(itemQuantity)));
        // 식별자를 바꿔도 관계의 식별 여부는 그대로다 (서로 자동으로 맞추지 않는다)
        assertThat(service.selectGraph(admin, model.id()).relationships()).filteredOn(r -> r.id().equals(details.id())).extracting(Relationship::isIdentifying).containsExactly(true);
        // 식별자를 지워도 속성은 남는다
        service.deleteIdentifier(admin, alternate.id());
        service.deleteIdentifier(admin, primary.id());
        assertThat(graphEntity(admin, model, "주문상세").attributes()).extracting(GraphAttribute::name, GraphAttribute::identifier)
                .containsExactly(tuple("주문번호", false), tuple("상품번호", false), tuple("수량", false));
        assertThat(service.selectLayout(admin, model.id())).as("식별자 변경은 배치를 바꾸지 않는다").isEqualTo(saved);

        // ---- 권한 : VIEWER 조회만, USER 는 승인 대기 관계를 만들고 고친다. 승인된 엔터티의 식별자는 USER 가 못 바꾼다. 승인은 승인 권한자만
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "940", "ROLE_VIEWER");
        assertThat(controller.selectGraph(viewer, model.id()).getBody().relationships()).hasSize(5);
        assertThat(controller.selectEntity(viewer, item.id()).getBody().identifiers()).isEmpty();
        assertThatThrownBy(() -> controller.saveRelationship(viewer, new Relationship(null, model.id(), "뷰어", null, member.id(), null, order.id(), null, false, 1, 1, 0, null, null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteRelationship(viewer, self.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveIdentifier(viewer, new Identifier(null, item.id(), "뷰어", false, List.of(itemQuantity), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteIdentifier(viewer, 1L)).isInstanceOf(AccessDeniedException.class);
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "941", "ROLE_USER");
        Relationship byUser = controller.saveRelationship(user, new Relationship(null, model.id(), "사용자 관계", null, order.id(), null, item.id(), null, false, 1, 1, 0, null, null, null)).getBody();
        assertThat(byUser.isApproval()).as("승인 권한이 없는 사용자가 만든 관계는 승인 대기").isFalse();
        assertThat(controller.saveRelationship(user, new Relationship(byUser.id(), model.id(), "사용자 관계 수정", null, order.id(), null, item.id(), null, true, 1, 1, 1, null, null, null)).getBody().isIdentifying()).isTrue();
        rejected(() -> controller.saveRelationship(user, new Relationship(details.id(), model.id(), "승인된 관계", null, order.id(), null, item.id(), null, false, 1, 1, 0, null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> controller.deleteRelationship(user, details.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        rejected(() -> controller.saveIdentifier(user, new Identifier(null, item.id(), "사용자 식별자", false, List.of(itemQuantity), null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> controller.updateRelationshipApproval(user, byUser.id(), true), ErrorCode.APPROVAL_PERMISSION_DENIED);
        controller.deleteRelationship(user, byUser.id());
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, projectId, "942", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.saveRelationship(notApproved, new Relationship(null, model.id(), "미승인", null, member.id(), null, order.id(), null, false, 1, 1, 0, null, null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveIdentifier(notApproved, new Identifier(null, item.id(), "미승인", false, List.of(itemQuantity), null))).isInstanceOf(AccessDeniedException.class);

        // ---- 프로젝트 격리 : 다른 프로젝트의 엔터티로 관계를 만들거나, 다른 프로젝트의 관계·식별자를 고치거나 지울 수 없다
        AccountDto other = loginAsAdmin("m8-erd-relation-other");
        Model theirs = service.saveModel(other, new Model(null, "주문 관리", null));
        Entity theirEntity = entity(other, theirs, "회원");
        rejected(() -> relation(other, model, null, "침입", member.id(), order.id(), false, 1, 1, 0, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> relation(other, theirs, null, "침입", theirEntity.id(), member.id(), false, 1, 1, 0, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> relation(other, theirs, self.id(), "침입", theirEntity.id(), theirEntity.id(), false, 1, 1, 0, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteRelationship(other, self.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveIdentifier(other, new Identifier(null, item.id(), "침입", false, List.of(itemQuantity), null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveIdentifier(other, new Identifier(null, theirEntity.id(), "침입", false, List.of(itemQuantity), null)), ErrorCode.INVALID_LOGICAL_MODEL);

        // ---- 관계 삭제 : 그 관계만 지워지고 엔터티·속성·배치는 그대로다
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        service.deleteRelationship(admin, orders.id());
        service.updateRelationshipApproval(admin, details.id(), false);
        service.deleteRelationship(admin, details.id());
        Graph graph = service.selectGraph(admin, model.id());
        assertThat(graph.relationships()).extracting(Relationship::id).containsExactlyInAnyOrder(recommends.id(), reverse.id(), self.id());
        assertThat(graph.entities()).extracting(GraphEntity::name).containsExactlyInAnyOrder("회원", "주문", "주문상세");
        assertThat(graphEntity(admin, model, "주문상세").attributes()).hasSize(3);
        assertThat(service.selectLayout(admin, model.id())).isEqualTo(saved);
    }

    // ------------------------------------------------------------------ 보조

    private List<String> entityNames(AccountDto account, Model model) {
        return service.selectGraph(account, model.id()).entities().stream().map(GraphEntity::name).toList();
    }

    private GraphEntity graphEntity(AccountDto account, Model model, String name) {
        return service.selectGraph(account, model.id()).entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    private GraphAttribute attribute(AccountDto account, Model model, String entityName, String name) {
        return graphEntity(account, model, entityName).attributes().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static void rejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private StandardTerm term(long projectId, String name, String abbreviation) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation).isApproval(true).build());
    }

    private Long attributeOf(AccountDto account, Entity entity, String name) {
        return service.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, false, null, null, null, null, null)).id();
    }

    private Relationship relation(AccountDto account, Model model, Long id, String name, Long sourceId, Long targetId, boolean identifying,
                                  Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax) {
        return service.saveRelationship(account, new Relationship(id, model.id(), name, null, sourceId, null, targetId, null, identifying, sourceMin, sourceMax, targetMin, targetMax, null, null));
    }

    private Entity entity(AccountDto account, Model model, String name) {
        return service.saveEntity(account, new Entity(null, model.id(), name, null, null, null, null));
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").standardTerms(term).isApproval(false).build()));
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
