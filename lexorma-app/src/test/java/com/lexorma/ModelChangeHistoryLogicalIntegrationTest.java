package com.lexorma;

import com.lexorma.common.exception.CustomException;
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
import com.lexorma.data.history.controller.ModelChangeHistoryRestController;
import com.lexorma.data.history.domain.dto.ModelChangeHistoryDto;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.Action;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.Source;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Relationship;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.controller.TableDesignRestController;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-9 2차 (H2 / PostgreSQL) : 논리 모델(모델·엔터티·속성·식별자·관계)과 논리 ↔ 물리 매핑의 변경 이력.
 * 화면이 부르는 서비스로 바꾸고, 물리 모델과 같은 기준(실제로 달라진 것만·한 트랜잭션 한 그룹·같은 트랜잭션)으로 남는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelChangeHistoryLogicalIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired ModelChangeHistoryRepository historyRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired TableDesignRestController tables;
    @Autowired ModelChangeHistoryRestController history;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("논리 모델·엔터티·속성 : 생성·수정·승인·삭제가 이름 스냅샷과 함께 남고, 같은 값 재저장·한 트랜잭션의 여러 수정·만들고 지운 것·롤백은 기준대로 처리된다")
    void logicalModelEntityAttribute() {
        AccountDto admin = loginAsAdmin("m9-logical");
        long projectId = admin.getProjectId();
        StandardTerm term = term(projectId, "회원_번호", "MBR_NO");

        Model model = logical.saveModel(admin, new Model(null, "회원 모델", null));
        ModelChangeHistory modelCreated = only(projectId);
        assertThat(modelCreated).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction, ModelChangeHistory::getUserId)
                .containsExactly(TargetType.LOGICAL_MODEL, model.id(), "회원 모델", Action.CREATE, admin.getUsername());

        // 같은 값 재저장 → 없음, 설명 변경 → UPDATE
        long mark = mark();
        logical.saveModel(admin, new Model(model.id(), "회원 모델", null));
        assertThat(since(projectId, mark)).isEmpty();
        logical.saveModel(admin, new Model(model.id(), "회원 모델", "회원 영역"));
        assertThat(changedFields(admin, onlySince(projectId, mark))).containsExactly("modelDescription");

        // 엔터티 : 승인 권한자가 만들어 승인 상태. 모델은 ID 와 그 시점의 이름이 함께 남는다
        mark = mark();
        Entity member = logical.saveEntity(admin, entity(null, model, "회원", null));
        ModelChangeHistory entityCreated = onlySince(projectId, mark);
        assertThat(entityCreated).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction)
                .containsExactly(TargetType.LOGICAL_ENTITY, "회원", Action.CREATE);
        JsonNode entityAfter = json(entityCreated.getAfterData());
        assertThat(entityAfter.get("modelId").asLong()).isEqualTo(model.id());
        assertThat(entityAfter.get("modelName").asString()).isEqualTo("회원 모델");
        assertThat(entityAfter.get("isApproval").asBoolean()).isTrue();

        // 승인된 엔터티 수정은 거절(이력 없음) → 승인취소(REVOKE) → 수정(UPDATE) → 같은 값(없음) → 승인(APPROVE)
        mark = mark();
        assertThatThrownBy(() -> logical.saveEntity(admin, entity(member.id(), model, "회원", "바뀜"))).isInstanceOf(CustomException.class);
        assertThat(since(projectId, mark)).isEmpty();
        logical.updateEntityApproval(admin, member.id(), false);
        logical.updateEntityApproval(admin, member.id(), false);
        logical.saveEntity(admin, entity(member.id(), model, "회원", "가입한 회원"));
        logical.saveEntity(admin, entity(member.id(), model, "회원", "가입한 회원"));
        logical.updateEntityApproval(admin, member.id(), true);
        assertThat(since(projectId, mark)).extracting(ModelChangeHistory::getAction).containsExactly(Action.REVOKE, Action.UPDATE, Action.APPROVE);

        // 속성 : 프로젝트 ID 가 없는 엔티티도 엔터티의 프로젝트로 남는다. 표준 용어는 ID + 이름
        mark = mark();
        Attribute number = logical.saveAttribute(admin, attribute(null, member, "회원 번호", "NUMBER", true, term.getId()));
        Attribute name = logical.saveAttribute(admin, attribute(null, member, "회원 이름", null, false, null));
        Attribute grade = logical.saveAttribute(admin, attribute(null, member, "등급", null, false, null));
        List<ModelChangeHistory> attributes = since(projectId, mark);
        assertThat(attributes).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction)
                .containsExactly(tuple(TargetType.LOGICAL_ATTRIBUTE, "회원.회원 번호", Action.CREATE), tuple(TargetType.LOGICAL_ATTRIBUTE, "회원.회원 이름", Action.CREATE),
                        tuple(TargetType.LOGICAL_ATTRIBUTE, "회원.등급", Action.CREATE));
        JsonNode numberAfter = json(attributes.get(0).getAfterData());
        assertThat(numberAfter.get("entityId").asLong()).isEqualTo(member.id());
        assertThat(numberAfter.get("entityName").asString()).isEqualTo("회원");
        assertThat(numberAfter.get("standardTermId").asLong()).isEqualTo(term.getId());
        assertThat(numberAfter.get("standardTermName").asString()).isEqualTo("회원_번호");
        assertThat(numberAfter.get("isRequired").asBoolean()).isTrue();
        assertThat(json(attributes.get(1).getAfterData()).get("standardTermName").isNull()).isTrue();

        // 수정 : 달라진 필드만. 같은 값 재저장 → 없음
        mark = mark();
        logical.saveAttribute(admin, attribute(name.id(), member, "회원 이름", "VARCHAR", false, null));
        logical.saveAttribute(admin, attribute(name.id(), member, "회원 이름", "VARCHAR", false, null));
        ModelChangeHistory typed = onlySince(projectId, mark);
        assertThat(typed.getAction()).isEqualTo(Action.UPDATE);
        assertThat(changedFields(admin, typed)).containsExactly("dataType");

        // 순서 변경 : 자리가 바뀐 속성만
        mark = mark();
        logical.reorderAttributes(admin, member.id(), List.of(name.id(), number.id(), grade.id()));
        logical.reorderAttributes(admin, member.id(), List.of(name.id(), number.id(), grade.id()));
        List<ModelChangeHistory> reordered = since(projectId, mark);
        assertThat(reordered).extracting(ModelChangeHistory::getTargetId).containsExactlyInAnyOrder(name.id(), number.id());
        assertThat(reordered).allSatisfy(h -> assertThat(changedFields(admin, h)).containsExactly("sortOrder"));
        assertThat(reordered).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(reordered.getFirst().getChangeGroupId());

        // 한 트랜잭션에서 같은 속성을 두 번 수정 → 처음 값 → 끝 값의 UPDATE 1건
        mark = mark();
        tx.executeWithoutResult(s -> {
            logical.saveAttribute(admin, attribute(grade.id(), member, "등급 임시", null, false, null));
            logical.saveAttribute(admin, attribute(grade.id(), member, "회원 등급", null, false, null));
        });
        ModelChangeHistory twice = onlySince(projectId, mark);
        assertThat(json(twice.getBeforeData()).get("attributeName").asString()).isEqualTo("등급");
        assertThat(json(twice.getAfterData()).get("attributeName").asString()).isEqualTo("회원 등급");

        // 한 트랜잭션에서 만들고 지우면 남지 않는다. 논리·물리 모델을 함께 바꾸면 한 그룹이다
        TableInfo table = table(projectId, "TB_MBR");
        mark = mark();
        tx.executeWithoutResult(s -> logical.deleteAttribute(admin, logical.saveAttribute(admin, attribute(null, member, "임시", null, false, null)).id()));
        assertThat(since(projectId, mark)).isEmpty();
        tx.executeWithoutResult(s -> {
            logical.saveAttribute(admin, attribute(grade.id(), member, "회원 등급", "CODE", false, null));
            tables.updateTable(TableInfoDto.builder().id(table.getId()).tableName("TB_MBR").tableDescription("회원").isMasterTable("N").build(), admin);
        });
        List<ModelChangeHistory> mixed = since(projectId, mark);
        assertThat(mixed).extracting(ModelChangeHistory::getTargetType).containsExactlyInAnyOrder(TargetType.LOGICAL_ATTRIBUTE, TargetType.PHYSICAL_TABLE);
        assertThat(mixed).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(mixed.getFirst().getChangeGroupId());

        // 롤백된 트랜잭션 → 없음
        mark = mark();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            logical.saveAttribute(admin, attribute(grade.id(), member, "롤백", null, false, null));
            throw new IllegalStateException("실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(since(projectId, mark)).isEmpty();

        // 이력 저장이 실패하면 논리 모델 변경도 롤백된다
        long count = historyRepository.count();
        jdbc.execute("alter table TB_MDL_CHG_HSTRY add constraint CK_MDL_CHG_HSTRY_TEST check (TRGT_NM <> '회원.막힘')");
        try {
            assertThatThrownBy(() -> logical.saveAttribute(admin, attribute(grade.id(), member, "막힘", null, false, null))).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("alter table TB_MDL_CHG_HSTRY drop constraint CK_MDL_CHG_HSTRY_TEST");
        }
        assertThat(historyRepository.count()).isEqualTo(count);
        assertThat(logical.selectAttributes(admin, member.id())).extracting(Attribute::attributeName).contains("회원 등급").doesNotContain("막힘");

        // 속성 삭제 → DELETE (삭제 전 상태)
        mark = mark();
        logical.deleteAttribute(admin, grade.id());
        ModelChangeHistory deleted = onlySince(projectId, mark);
        assertThat(deleted).extracting(ModelChangeHistory::getAction, ModelChangeHistory::getTargetName).containsExactly(Action.DELETE, "회원.회원 등급");
        assertThat(deleted.getAfterData()).isNull();
        assertThat(json(deleted.getBeforeData()).get("dataType").asString()).isEqualTo("CODE");

        // 엔터티 삭제 : 딸린 속성·식별자가 같은 그룹에 DELETE 로 남고, 지워진 엔터티의 이름·프로젝트를 그대로 갖는다
        logical.saveIdentifier(admin, new Identifier(null, member.id(), "회원 식별자", true, List.of(number.id()), null));
        logical.updateEntityApproval(admin, member.id(), false);
        mark = mark();
        logical.deleteEntity(admin, member.id());
        List<ModelChangeHistory> cascade = since(projectId, mark);
        assertThat(cascade).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getAction).containsExactlyInAnyOrder(
                tuple(TargetType.LOGICAL_ENTITY, Action.DELETE), tuple(TargetType.LOGICAL_ATTRIBUTE, Action.DELETE), tuple(TargetType.LOGICAL_ATTRIBUTE, Action.DELETE),
                tuple(TargetType.LOGICAL_IDENTIFIER, Action.DELETE));
        assertThat(cascade).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(cascade.getFirst().getChangeGroupId());
        assertThat(cascade).filteredOn(h -> h.getTargetType() == TargetType.LOGICAL_ATTRIBUTE).extracting(ModelChangeHistory::getTargetName)
                .containsExactlyInAnyOrder("회원.회원 번호", "회원.회원 이름");
        ModelChangeHistory identifierDeleted = cascade.stream().filter(h -> h.getTargetType() == TargetType.LOGICAL_IDENTIFIER).findFirst().orElseThrow();
        assertThat(identifierDeleted.getTargetName()).isEqualTo("회원.회원 식별자");
        assertThat(json(identifierDeleted.getBeforeData()).get("attributes").get(0).get("attributeName").asString()).isEqualTo("회원 번호");

        // 삭제된 엔터티·속성의 이력은 계속 조회된다
        assertThat(search(admin, "LOGICAL_ENTITY", member.id())).extracting(ModelChangeHistoryDto.Summary::action)
                .containsExactly("DELETE", "REVOKE", "APPROVE", "UPDATE", "REVOKE", "CREATE");
        assertThat(search(admin, "LOGICAL_ATTRIBUTE", number.id())).extracting(ModelChangeHistoryDto.Summary::action).containsExactly("DELETE", "UPDATE", "CREATE");

        // 엔터티가 없어진 모델 삭제 → DELETE
        mark = mark();
        logical.deleteModel(admin, model.id());
        assertThat(onlySince(projectId, mark)).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getAction).containsExactly(TargetType.LOGICAL_MODEL, Action.DELETE);
    }

    @Test
    @DisplayName("논리 식별자·관계 : 구성 속성(순서 포함)과 카디널리티·승인이 남는다. 구성이 그대로면 이력이 없다")
    void identifiersAndRelationships() {
        AccountDto admin = loginAsAdmin("m9-logical-rel");
        long projectId = admin.getProjectId();
        Model model = logical.saveModel(admin, new Model(null, "주문 모델", null));
        Entity order = logical.saveEntity(admin, entity(null, model, "주문", null));
        Entity item = logical.saveEntity(admin, entity(null, model, "주문 상품", null));
        Attribute no = logical.saveAttribute(admin, attribute(null, order, "주문 번호", null, true, null));
        Attribute seq = logical.saveAttribute(admin, attribute(null, order, "순번", null, true, null));
        Attribute date = logical.saveAttribute(admin, attribute(null, order, "주문 일자", null, false, null));

        // 식별자 생성 : 구성 속성이 순서대로, 이름과 함께
        long mark = mark();
        Identifier pk = logical.saveIdentifier(admin, new Identifier(null, order.id(), "주문 식별자", true, List.of(no.id(), seq.id()), null));
        ModelChangeHistory created = onlySince(projectId, mark);
        assertThat(created).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction)
                .containsExactly(TargetType.LOGICAL_IDENTIFIER, pk.id(), "주문.주문 식별자", Action.CREATE);
        JsonNode composition = json(created.getAfterData()).get("attributes");
        assertThat(composition).hasSize(2);
        assertThat(composition.get(0).get("attributeId").asLong()).isEqualTo(no.id());
        assertThat(composition.get(0).get("attributeName").asString()).isEqualTo("주문 번호");
        assertThat(composition.get(1).get("sortOrder").asInt()).isEqualTo(2);

        // 같은 구성으로 다시 저장 → 없음. 순서를 바꾸고 속성을 더하면 UPDATE (전후 구성이 모두 남는다)
        mark = mark();
        logical.saveIdentifier(admin, new Identifier(pk.id(), order.id(), "주문 식별자", true, List.of(no.id(), seq.id()), null));
        assertThat(since(projectId, mark)).isEmpty();
        logical.saveIdentifier(admin, new Identifier(pk.id(), order.id(), "주문 식별자", true, List.of(seq.id(), no.id(), date.id()), null));
        ModelChangeHistory recomposed = onlySince(projectId, mark);
        assertThat(recomposed.getAction()).isEqualTo(Action.UPDATE);
        assertThat(changedFields(admin, recomposed)).containsExactly("attributes");
        assertThat(json(recomposed.getBeforeData()).get("attributes")).isEqualTo(composition);
        assertThat(json(recomposed.getAfterData()).get("attributes")).extracting(n -> n.get("attributeName").asString()).containsExactly("순번", "주문 번호", "주문 일자");

        // 관계 : 이름이 없으면 "원본 → 대상" 으로 보인다. 승인 권한자가 만들어 승인 상태
        mark = mark();
        Relationship rel = logical.saveRelationship(admin, relationship(null, model, null, order, item, 1, 1, 0, null));
        ModelChangeHistory relCreated = onlySince(projectId, mark);
        assertThat(relCreated).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction, ModelChangeHistory::getSource)
                .containsExactly(TargetType.LOGICAL_RELATIONSHIP, "주문 → 주문 상품", Action.CREATE, Source.USER);
        JsonNode relAfter = json(relCreated.getAfterData());
        assertThat(relAfter.get("sourceEntityName").asString()).isEqualTo("주문");
        assertThat(relAfter.get("targetEntityId").asLong()).isEqualTo(item.id());
        assertThat(relAfter.get("targetMax").isNull()).isTrue();

        // 승인취소 → 카디널리티 수정 → 같은 값 → 승인 → (승인된 관계 삭제 거절) → 승인취소 → 삭제
        mark = mark();
        logical.updateRelationshipApproval(admin, rel.id(), false);
        logical.saveRelationship(admin, relationship(rel.id(), model, "주문-상품", order, item, 1, 1, 1, null));
        logical.saveRelationship(admin, relationship(rel.id(), model, "주문-상품", order, item, 1, 1, 1, null));
        logical.updateRelationshipApproval(admin, rel.id(), true);
        assertThatThrownBy(() -> logical.deleteRelationship(admin, rel.id())).isInstanceOf(CustomException.class);
        logical.updateRelationshipApproval(admin, rel.id(), false);
        logical.deleteRelationship(admin, rel.id());
        List<ModelChangeHistory> lifecycle = since(projectId, mark);
        assertThat(lifecycle).extracting(ModelChangeHistory::getAction).containsExactly(Action.REVOKE, Action.UPDATE, Action.APPROVE, Action.REVOKE, Action.DELETE);
        assertThat(changedFields(admin, lifecycle.get(1))).containsExactlyInAnyOrder("relationshipName", "targetMin");
        assertThat(lifecycle.get(4).getTargetName()).isEqualTo("주문-상품");
        assertThat(json(lifecycle.get(4).getBeforeData()).get("targetMin").asInt()).isEqualTo(1);

        // 식별자 삭제 → 삭제 전 구성이 남는다
        mark = mark();
        logical.deleteIdentifier(admin, pk.id());
        ModelChangeHistory removed = onlySince(projectId, mark);
        assertThat(removed.getAction()).isEqualTo(Action.DELETE);
        assertThat(json(removed.getBeforeData()).get("attributes")).hasSize(3);
    }

    @Test
    @DisplayName("매핑 : 엔터티 ↔ 테이블, 속성 ↔ 컬럼의 등록·대상 변경·해제가 전후 대상(ID + 이름)과 함께 남는다. 같은 매핑 재저장·거절된 삭제는 이력이 없고 다른 프로젝트에서는 보이지 않는다")
    void mappings() {
        AccountDto admin = loginAsAdmin("m9-mapping");
        long projectId = admin.getProjectId();
        Model model = logical.saveModel(admin, new Model(null, "회원 모델", null));
        Entity member = logical.saveEntity(admin, entity(null, model, "회원", null));
        Attribute number = logical.saveAttribute(admin, attribute(null, member, "회원 번호", null, true, null));
        TableInfo tbMember = table(projectId, "TB_MBR");
        ColumnInfo mbrNo = column(projectId, tbMember, "MBR_NO");
        ColumnInfo mbrId = column(projectId, tbMember, "MBR_ID");
        TableInfo tbSpare = table(projectId, "TB_SPARE");

        // 엔터티 ↔ 테이블 등록
        long mark = mark();
        EntityMapping mapping = mappings.saveEntityMapping(admin, new EntityMapping(null, member.id(), tbSpare.getId()));
        ModelChangeHistory created = onlySince(projectId, mark);
        assertThat(created).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction, ModelChangeHistory::getSource)
                .containsExactly(TargetType.TABLE_MAPPING, mapping.id(), "회원 ↔ TB_SPARE", Action.CREATE, Source.USER);
        assertThat(created.getBeforeData()).isNull();
        JsonNode after = json(created.getAfterData());
        assertThat(after.get("entityId").asLong()).isEqualTo(member.id());
        assertThat(after.get("entityName").asString()).isEqualTo("회원");
        assertThat(after.get("tableId").asLong()).isEqualTo(tbSpare.getId());
        assertThat(after.get("tableName").asString()).isEqualTo("TB_SPARE");

        // 같은 대상으로 다시 저장 → 없음. 대상 테이블 변경 → UPDATE : 이전 대상과 새 대상이 구분된다
        mark = mark();
        mappings.saveEntityMapping(admin, new EntityMapping(mapping.id(), member.id(), tbSpare.getId()));
        mappings.saveEntityMapping(admin, new EntityMapping(mapping.id(), member.id(), tbSpare.getId()));
        assertThat(since(projectId, mark)).isEmpty();
        mappings.saveEntityMapping(admin, new EntityMapping(mapping.id(), member.id(), tbMember.getId()));
        ModelChangeHistory repointed = onlySince(projectId, mark);
        assertThat(repointed).extracting(ModelChangeHistory::getAction, ModelChangeHistory::getTargetName).containsExactly(Action.UPDATE, "회원 ↔ TB_MBR");
        assertThat(json(repointed.getBeforeData()).get("tableName").asString()).isEqualTo("TB_SPARE");
        assertThat(json(repointed.getAfterData()).get("tableName").asString()).isEqualTo("TB_MBR");
        assertThat(changedFields(admin, repointed)).containsExactlyInAnyOrder("tableId", "tableName");

        // 속성 ↔ 컬럼 등록 : 프로젝트는 부모 매핑에서 물려받는다
        mark = mark();
        AttributeMapping attributeMapping = mappings.saveAttributeMapping(admin, new AttributeMapping(null, mapping.id(), number.id(), mbrId.getId()));
        ModelChangeHistory columnMapped = onlySince(projectId, mark);
        assertThat(columnMapped).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction)
                .containsExactly(TargetType.COLUMN_MAPPING, attributeMapping.id(), "회원 번호 ↔ MBR_ID", Action.CREATE);
        assertThat(json(columnMapped.getAfterData()).get("mappingId").asLong()).isEqualTo(mapping.id());
        assertThat(json(columnMapped.getAfterData()).get("attributeName").asString()).isEqualTo("회원 번호");

        // 같은 컬럼 재저장 → 없음, 컬럼 변경 → UPDATE
        mark = mark();
        mappings.saveAttributeMapping(admin, new AttributeMapping(attributeMapping.id(), mapping.id(), number.id(), mbrId.getId()));
        assertThat(since(projectId, mark)).isEmpty();
        mappings.saveAttributeMapping(admin, new AttributeMapping(attributeMapping.id(), mapping.id(), number.id(), mbrNo.getId()));
        ModelChangeHistory columnChanged = onlySince(projectId, mark);
        assertThat(columnChanged.getAction()).isEqualTo(Action.UPDATE);
        assertThat(json(columnChanged.getBeforeData()).get("columnName").asString()).isEqualTo("MBR_ID");
        assertThat(json(columnChanged.getAfterData()).get("columnId").asLong()).isEqualTo(mbrNo.getId());

        // 매핑된 엔터티·테이블·속성은 삭제가 거절되고(매핑이 연쇄로 지워지지 않는다), 속성 매핑이 있는 엔터티 매핑도 해제할 수 없다 → 이력 없음
        mark = mark();
        logical.updateEntityApproval(admin, member.id(), false);
        long afterRevoke = mark();
        assertThatThrownBy(() -> logical.deleteEntity(admin, member.id())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> logical.deleteAttribute(admin, number.id())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> tables.deleteTable(admin, List.of(TableInfoDto.builder().id(tbMember.getId()).build()))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> mappings.deleteEntityMapping(admin, mapping.id())).isInstanceOf(CustomException.class);
        assertThat(since(projectId, afterRevoke)).isEmpty();

        // 해제 : 속성 매핑 → 엔터티 매핑 순서로, 각각 DELETE (해제 전 대상이 남는다)
        mark = mark();
        mappings.deleteAttributeMapping(admin, attributeMapping.id());
        mappings.deleteEntityMapping(admin, mapping.id());
        List<ModelChangeHistory> released = since(projectId, mark);
        assertThat(released).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getAction, ModelChangeHistory::getTargetName)
                .containsExactly(tuple(TargetType.COLUMN_MAPPING, Action.DELETE, "회원 번호 ↔ MBR_NO"), tuple(TargetType.TABLE_MAPPING, Action.DELETE, "회원 ↔ TB_MBR"));
        assertThat(released).allMatch(h -> h.getAfterData() == null);

        // 매핑을 해제한 뒤 엔터티를 지워도, 지워진 대상과 이어졌던 매핑의 이력은 그대로 조회된다
        logical.deleteEntity(admin, member.id());
        assertThat(search(admin, "TABLE_MAPPING", mapping.id())).extracting(ModelChangeHistoryDto.Summary::action, ModelChangeHistoryDto.Summary::targetName)
                .containsExactly(tuple("DELETE", "회원 ↔ TB_MBR"), tuple("UPDATE", "회원 ↔ TB_MBR"), tuple("CREATE", "회원 ↔ TB_SPARE"));
        assertThat(history.detail(admin, search(admin, "COLUMN_MAPPING", attributeMapping.id()).getFirst().id()).getBody().before().get("attributeName").asString()).isEqualTo("회원 번호");

        // 다른 프로젝트 : 매핑·논리 모델 이력이 보이지 않는다
        AccountDto outsider = loginAsAdmin("m9-mapping-other");
        assertThat(search(outsider, "TABLE_MAPPING", mapping.id())).isEmpty();
        assertThat(search(outsider, "LOGICAL_ENTITY", member.id())).isEmpty();
        assertThat(search(outsider, null, null)).isEmpty();
    }

    // ------------------------------------------------------------------ 보조

    private long mark() {
        return historyRepository.findAll().stream().mapToLong(ModelChangeHistory::getId).max().orElse(0L);
    }

    private List<ModelChangeHistory> since(long projectId, long mark) {
        return historyRepository.findAll().stream().filter(h -> h.getProjectId() == projectId && h.getId() > mark)
                .sorted(Comparator.comparing(ModelChangeHistory::getId)).toList();
    }

    private ModelChangeHistory onlySince(long projectId, long mark) {
        List<ModelChangeHistory> found = since(projectId, mark);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private ModelChangeHistory only(long projectId) {
        return onlySince(projectId, 0);
    }

    private static JsonNode json(String value) {
        return JSON.readTree(value);
    }

    private List<String> changedFields(AccountDto account, ModelChangeHistory row) {
        return history.detail(account, row.getId()).getBody().summary().changedFields();
    }

    @SuppressWarnings("unchecked")
    private List<ModelChangeHistoryDto.Summary> search(AccountDto account, String targetType, Long targetId) {
        Map<String, Object> body = history.search(account, 0, 100, targetType, targetId, null, null, null, null, null).getBody();
        return (List<ModelChangeHistoryDto.Summary>) body.get("data");
    }

    private static Entity entity(Long id, Model model, String name, String description) {
        return new Entity(id, model.id(), name, description, null, null, null);
    }

    private static Attribute attribute(Long id, Entity entity, String name, String dataType, boolean required, Long termId) {
        return new Attribute(id, entity.id(), name, null, dataType, required, null, termId, null, null, null);
    }

    private static Relationship relationship(Long id, Model model, String name, Entity source, Entity target, int sourceMin, Integer sourceMax, int targetMin, Integer targetMax) {
        return new Relationship(id, model.id(), name, null, source.id(), null, target.id(), null, false, sourceMin, sourceMax, targetMin, targetMax, null, null);
    }

    private StandardTerm term(long projectId, String name, String abbreviation) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation).isApproval(true).build());
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false).isMasterTable(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnName(name).dataType("VARCHAR").maxLength(10).isApproval(false).build()));
    }
}
