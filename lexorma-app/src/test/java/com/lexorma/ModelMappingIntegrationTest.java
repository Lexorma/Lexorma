package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.controller.ModelMappingRestController;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeRow;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.ColumnCandidate;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.Detail;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityRow;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntitySearch;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.Status;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.TableCandidate;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.system.project.service.ProjectManageService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static com.lexorma.LogicalModelIntegrationTest.concurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-7 1차 : 논리·물리 모델 매핑 (H2 / PostgreSQL, 실제 JPA 매핑·쿼리·제약).
 * 엔터티 ↔ 테이블, 속성 ↔ 컬럼 매핑의 등록·조회·변경·삭제, 1:1 과 부모·자식 제약, 원본 삭제 정책, 현황·후보 조회,
 * 프로젝트 격리, 권한, 동시 요청, DB 제약을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelMappingIntegrationTest {

    private static final Pageable PAGE = PageRequest.of(0, 100);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnManageService columnManageService;
    @Autowired ProjectManageService projectManageService;
    @Autowired LogicalModelService logicalService;
    @Autowired ModelMappingService service;
    @Autowired ModelMappingRestController controller;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("엔터티 ↔ 테이블 : 등록·조회·변경·삭제, 엔터티·테이블 중복, 없는 ID, 다른 프로젝트, 원본은 그대로, 목록 조건·페이징, 후보 테이블")
    void entityMappings() {
        AccountDto admin = loginAsAdmin("m7-entity");
        long projectId = admin.getProjectId();
        Model model = logicalService.saveModel(admin, new Model(null, "회원", null));
        Model other = logicalService.saveModel(admin, new Model(null, "주문", null));
        Entity member = entity(admin, model, "회원");
        Entity grade = entity(admin, model, "등급");
        Entity order = entity(admin, other, "주문");
        logicalService.updateEntityApproval(admin, grade.id(), false);
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));
        TableInfo tbMember = table(projectId, "TB_MEMBER", null, true);
        TableInfo tbGrade = table(projectId, "TB_GRADE", null, false);
        TableInfo tbOrder = table(projectId, "TB_ORDER", sales.id(), true);
        TableInfo tbSpare = table(projectId, "TB_SPARE", sales.id(), true);

        EntityMapping mapping = service.saveEntityMapping(admin, new EntityMapping(null, member.id(), tbMember.getId()));
        assertThat(mapping).extracting(EntityMapping::entityId, EntityMapping::tableId).containsExactly(member.id(), tbMember.getId());
        // 미승인 엔터티·테이블도 매핑할 수 있고, 승인 상태는 바뀌지 않고 조회에만 나온다
        service.saveEntityMapping(admin, new EntityMapping(null, grade.id(), tbGrade.getId()));
        EntityMapping orderMapping = service.saveEntityMapping(admin, new EntityMapping(null, order.id(), tbOrder.getId()));

        // 1:1 : 같은 엔터티·같은 테이블은 한 번만
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(null, member.id(), tbSpare.getId())), ErrorCode.SAVED_DATA_EXISTS);
        Entity extra = entity(admin, model, "여분");
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(null, extra.id(), tbMember.getId())), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(orderMapping.id(), null, tbMember.getId())), ErrorCode.SAVED_DATA_EXISTS);
        // 없는 ID
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(null, -1L, tbSpare.getId())), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(null, null, tbSpare.getId())), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(null, extra.id(), -1L)), ErrorCode.TABLE_IS_NOT_DEFINED);
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(null, extra.id(), null)), ErrorCode.TABLE_IS_NOT_DEFINED);
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(-1L, null, tbSpare.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.selectEntityMapping(admin, -1L), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.deleteEntityMapping(admin, -1L), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);

        // 목록 : 엔터티 1행, 모델명 → 엔터티명 순. 조건 : 모델, 스키마, 매핑 여부, 엔터티명·테이블명 검색
        assertThat(rows(admin, new EntitySearch(model.id(), null, null, null, null)))
                .extracting(EntityRow::entityName, EntityRow::entityApproval, EntityRow::tableName, EntityRow::tableApproval, EntityRow::schemaName)
                .containsExactlyInAnyOrder(tuple("회원", true, "TB_MEMBER", true, "DEFAULT"), tuple("등급", false, "TB_GRADE", false, "DEFAULT"),
                        tuple("여분", true, null, null, null));
        assertThat(rows(admin, new EntitySearch(null, sales.id(), null, null, null))).extracting(EntityRow::entityName).containsExactly("주문");
        assertThat(rows(admin, new EntitySearch(null, null, true, null, null))).hasSize(3).allMatch(r -> r.mappingId() != null);
        assertThat(rows(admin, new EntitySearch(null, null, false, null, null))).extracting(EntityRow::entityName).containsExactly("여분");
        assertThat(rows(admin, new EntitySearch(null, null, null, "entityName", "회"))).extracting(EntityRow::entityName).containsExactly("회원");
        assertThat(rows(admin, new EntitySearch(null, null, null, "tableName", "tb_ord"))).extracting(EntityRow::tableName).containsExactly("TB_ORDER");
        var firstPage = service.selectEntityRows(admin, new EntitySearch(null, null, null, null, null), PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "tableName")));
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getContent()).hasSize(3);
        assertThat(service.selectEntityRows(admin, new EntitySearch(null, null, null, null, null), PageRequest.of(1, 3)).getContent()).hasSize(1);

        // 후보 테이블 : 매핑하지 않은 테이블만
        assertThat(tables(admin, null, null)).extracting(TableCandidate::tableName).containsExactly("TB_SPARE");
        assertThat(tables(admin, sales.id(), "spare")).extracting(TableCandidate::tableName, TableCandidate::schemaName).containsExactly(tuple("TB_SPARE", "sales"));
        assertThat(tables(admin, null, "member")).isEmpty();

        // 변경 : 테이블만 바뀐다 (entityId 는 무시)
        EntityMapping changed = service.saveEntityMapping(admin, new EntityMapping(mapping.id(), order.id(), tbSpare.getId()));
        assertThat(changed).extracting(EntityMapping::id, EntityMapping::entityId, EntityMapping::tableId).containsExactly(mapping.id(), member.id(), tbSpare.getId());
        assertThat(service.selectEntityMapping(admin, mapping.id()).mapping()).extracting(EntityRow::entityName, EntityRow::tableName, EntityRow::schemaName)
                .containsExactly("회원", "TB_SPARE", "sales");
        assertThat(tables(admin, null, null)).extracting(TableCandidate::tableName).containsExactly("TB_MEMBER");

        // 다른 프로젝트 : 남의 엔터티·테이블·매핑은 보이지도 쓰이지도 않는다
        AccountDto outsider = loginAsAdmin("m7-entity-other");
        Entity theirs = entity(outsider, logicalService.saveModel(outsider, new Model(null, "회원", null)), "회원");
        TableInfo theirTable = table(outsider.getProjectId(), "TB_MEMBER", null, true);
        rejected(() -> service.saveEntityMapping(outsider, new EntityMapping(null, member.id(), theirTable.getId())), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveEntityMapping(outsider, new EntityMapping(null, theirs.id(), tbMember.getId())), ErrorCode.TABLE_IS_NOT_DEFINED);
        rejected(() -> service.saveEntityMapping(outsider, new EntityMapping(mapping.id(), null, theirTable.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.selectEntityMapping(outsider, mapping.id()), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.deleteEntityMapping(outsider, mapping.id()), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        assertThat(rows(outsider, new EntitySearch(null, null, null, null, null))).extracting(EntityRow::entityId).containsExactly(theirs.id());
        assertThat(rows(outsider, new EntitySearch(model.id(), null, null, null, null))).isEmpty();
        assertThat(tables(outsider, null, null)).extracting(TableCandidate::tableId).containsExactly(theirTable.getId());

        // 삭제 : 매핑만 지워지고 엔터티·테이블은 남는다
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        service.deleteEntityMapping(admin, mapping.id());
        assertThat(rows(admin, new EntitySearch(model.id(), null, false, null, null))).extracting(EntityRow::entityName).contains("회원");
        assertThat(logicalService.selectEntity(admin, member.id()).entityName()).isEqualTo("회원");
        assertThat(tableInfoRepository.findById(tbSpare.getId())).isPresent();
    }

    @Test
    @DisplayName("속성 ↔ 컬럼 : 등록·조회·변경·삭제, 속성·컬럼 중복, 부모 매핑과 다른 엔터티·테이블, 다른 프로젝트, 표준 용어는 조회만, 후보 컬럼, 부모 변경·삭제 차단")
    void attributeMappings() {
        AccountDto admin = loginAsAdmin("m7-attribute");
        long projectId = admin.getProjectId();
        StandardTerm memberNo = termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName("회원_번호").commonStandardTermAbbreviation("MBR_NO").isApproval(true).build());
        StandardTerm memberId = termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName("회원_아이디").commonStandardTermAbbreviation("MBR_ID").isApproval(true).build());
        Model model = logicalService.saveModel(admin, new Model(null, "회원", null));
        Entity member = entity(admin, model, "회원");
        Entity order = entity(admin, model, "주문");
        Long no = logicalService.saveAttribute(admin, new Attribute(null, member.id(), "회원번호", null, null, null, null, memberNo.getId(), null, null, null)).id();
        Long name = attribute(admin, member, "회원명");
        Long joined = attribute(admin, member, "가입일시");
        Long orderNo = attribute(admin, order, "주문번호");
        TableInfo tbMember = table(projectId, "TB_MEMBER", null, true);
        TableInfo tbOrder = table(projectId, "TB_ORDER", null, true);
        TableInfo tbSpare = table(projectId, "TB_SPARE", null, true);
        ColumnInfo mbrId = column(projectId, tbMember, "MBR_ID", memberId);
        ColumnInfo mbrNm = column(projectId, tbMember, "MBR_NM", null);
        ColumnInfo regDt = column(projectId, tbMember, "REG_DT", null);
        ColumnInfo orderId = column(projectId, tbOrder, "ORDER_ID", null);
        EntityMapping parent = service.saveEntityMapping(admin, new EntityMapping(null, member.id(), tbMember.getId()));
        EntityMapping orderParent = service.saveEntityMapping(admin, new EntityMapping(null, order.id(), tbOrder.getId()));

        // 표준 용어가 서로 달라도 매핑하고, 어느 쪽 용어도 바꾸지 않는다
        AttributeMapping noMapping = service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), no, mbrId.getId()));
        assertThat(noMapping).extracting(AttributeMapping::mappingId, AttributeMapping::attributeId, AttributeMapping::columnId).containsExactly(parent.id(), no, mbrId.getId());
        Detail detail = service.selectEntityMapping(admin, parent.id());
        assertThat(detail.mapping()).extracting(EntityRow::attributeCount, EntityRow::mappedAttributeCount).containsExactly(3L, 1L);
        assertThat(detail.attributes()).extracting(AttributeRow::attributeName, AttributeRow::attributeTermName, AttributeRow::columnName, AttributeRow::columnTermName)
                .containsExactly(tuple("회원번호", "회원_번호", "MBR_ID", "회원_아이디"), tuple("회원명", null, null, null), tuple("가입일시", null, null, null));
        assertThat(detail.attributes().get(0).mappingId()).isEqualTo(noMapping.id());
        assertThat(detail.unmappedColumns()).extracting(ColumnCandidate::columnName).containsExactlyInAnyOrder("MBR_NM", "REG_DT");
        assertThat(columnInfoRepository.findById(mbrId.getId()).orElseThrow().getStandardTerms().getId()).isEqualTo(memberId.getId());

        // 1:1
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), no, mbrNm.getId())), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), name, mbrId.getId())), ErrorCode.SAVED_DATA_EXISTS);
        // 부모 매핑과 맞지 않는 속성·컬럼 : 회원 엔터티의 속성 ↔ 주문 테이블의 컬럼, 주문 엔터티의 속성 ↔ 회원 매핑
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), name, orderId.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), orderNo, mbrNm.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, orderParent.id(), name, orderId.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        // 없는 ID
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, -1L, name, mbrNm.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, null, name, mbrNm.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), -1L, mbrNm.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), name, null)), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(-1L, null, null, mbrNm.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.deleteAttributeMapping(admin, -1L), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);

        // 변경 : 컬럼만 바뀐다. 이미 쓰인 컬럼·다른 테이블의 컬럼으로는 못 바꾼다
        AttributeMapping nameMapping = service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), name, mbrNm.getId()));
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(nameMapping.id(), null, null, mbrId.getId())), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveAttributeMapping(admin, new AttributeMapping(nameMapping.id(), null, null, orderId.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        assertThat(service.saveAttributeMapping(admin, new AttributeMapping(nameMapping.id(), null, joined, regDt.getId())))
                .extracting(AttributeMapping::id, AttributeMapping::attributeId, AttributeMapping::columnId).containsExactly(nameMapping.id(), name, regDt.getId());
        assertThat(service.selectEntityMapping(admin, parent.id()).unmappedColumns()).extracting(ColumnCandidate::columnName).containsExactly("MBR_NM");

        // 속성 매핑이 있으면 부모 매핑의 테이블 변경·삭제를 막는다 (같은 테이블로 다시 저장하는 것은 변경이 아니다)
        rejected(() -> service.saveEntityMapping(admin, new EntityMapping(parent.id(), null, tbSpare.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.deleteEntityMapping(admin, parent.id()), ErrorCode.RELATION_EXISTS);
        assertThat(service.saveEntityMapping(admin, new EntityMapping(parent.id(), null, tbMember.getId())).tableId()).isEqualTo(tbMember.getId());
        assertThat(service.selectEntityMapping(admin, parent.id()).mapping().mappedAttributeCount()).isEqualTo(2);

        // 다른 프로젝트 : 남의 부모 매핑·속성 매핑은 찾을 수 없고, 자기 매핑에 남의 속성·컬럼을 넣을 수 없다
        AccountDto outsider = loginAsAdmin("m7-attribute-other");
        Entity theirs = entity(outsider, logicalService.saveModel(outsider, new Model(null, "회원", null)), "회원");
        Long theirAttribute = attribute(outsider, theirs, "회원번호");
        TableInfo theirTable = table(outsider.getProjectId(), "TB_MEMBER", null, true);
        ColumnInfo theirColumn = column(outsider.getProjectId(), theirTable, "MBR_ID", null);
        EntityMapping theirParent = service.saveEntityMapping(outsider, new EntityMapping(null, theirs.id(), theirTable.getId()));
        rejected(() -> service.saveAttributeMapping(outsider, new AttributeMapping(null, parent.id(), theirAttribute, theirColumn.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.saveAttributeMapping(outsider, new AttributeMapping(null, theirParent.id(), joined, theirColumn.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.saveAttributeMapping(outsider, new AttributeMapping(null, theirParent.id(), theirAttribute, mbrNm.getId())), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> service.saveAttributeMapping(outsider, new AttributeMapping(nameMapping.id(), null, null, theirColumn.getId())), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        rejected(() -> service.deleteAttributeMapping(outsider, nameMapping.id()), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);

        // 삭제 : 속성 매핑을 모두 해제하면 부모 매핑도 지울 수 있다. 속성·컬럼은 남는다
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        service.deleteAttributeMapping(admin, noMapping.id());
        service.deleteAttributeMapping(admin, nameMapping.id());
        assertThat(service.selectEntityMapping(admin, parent.id()).attributes()).hasSize(3).allMatch(a -> a.mappingId() == null && a.columnId() == null);
        service.deleteEntityMapping(admin, parent.id());
        assertThat(logicalService.selectAttributes(admin, member.id())).hasSize(3);
        assertThat(columnInfoRepository.findAllByTableInfo_IdIn(List.of(tbMember.getId()))).hasSize(3);
    }

    @Test
    @DisplayName("삭제 정책 : 매핑된 엔터티·테이블·속성·컬럼은 삭제할 수 없고 해제 후에는 지울 수 있다. 논리 모델·스키마·프로젝트 삭제에도 고아 매핑이 남지 않는다. DB 제약")
    void deletionPolicy() {
        AccountDto admin = loginAsAdmin("m7-delete");
        long projectId = admin.getProjectId();
        Model model = logicalService.saveModel(admin, new Model(null, "삭제", null));
        Entity entity = entity(admin, model, "회원");
        logicalService.updateEntityApproval(admin, entity.id(), false);
        Long attribute = attribute(admin, entity, "회원번호");
        SchemaDto schema = schemaService.saveSchema(admin, new SchemaDto(null, "member", null, null));
        TableInfo table = table(projectId, "TB_MEMBER", schema.id(), false);
        ColumnInfo column = column(projectId, table, "MBR_ID", null);
        ColumnInfo free = column(projectId, table, "MBR_NM", null);
        EntityMapping parent = service.saveEntityMapping(admin, new EntityMapping(null, entity.id(), table.getId()));
        AttributeMapping child = service.saveAttributeMapping(admin, new AttributeMapping(null, parent.id(), attribute, column.getId()));

        rejected(() -> logicalService.deleteEntity(admin, entity.id()), ErrorCode.RELATION_EXISTS);
        rejected(() -> logicalService.deleteAttribute(admin, attribute), ErrorCode.RELATION_EXISTS);
        rejected(() -> logicalService.deleteModel(admin, model.id()), ErrorCode.RELATION_EXISTS);
        rejected(() -> columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(column.getId()).build())), ErrorCode.RELATION_EXISTS);
        rejected(() -> service.deleteEntityMapping(admin, parent.id()), ErrorCode.RELATION_EXISTS);
        rejected(() -> schemaService.deleteSchema(admin, schema.id()), ErrorCode.RELATION_EXISTS);
        // 컬럼 일괄 저장의 삭제도 같은 검사를 거친다 (일괄 저장은 승인된 테이블에만 한다)
        tx.executeWithoutResult(s -> tableInfoRepository.findById(table.getId()).orElseThrow().setIsApproval(true));
        rejected(() -> columnManageService.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(table.getId()).deletedIds(List.of(column.getId())).build()), ErrorCode.RELATION_EXISTS);
        tx.executeWithoutResult(s -> tableInfoRepository.findById(table.getId()).orElseThrow().setIsApproval(false));
        // 매핑되지 않은 컬럼은 지울 수 있다
        columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(free.getId()).build()));

        // DB 제약 : 서비스 검사를 건너뛰어도 1:1 과 원본 삭제 차단이 지켜진다
        assertThatThrownBy(() -> jdbc.update("insert into TB_ENTT_TBL_MPNG(ENTT_TBL_MPNG_SN, PRJ_SN, LGCL_ENTT_SN, TBL_SN) values(-1, ?, ?, ?)", projectId, entity.id(), table.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN) values(-1, ?, ?, ?)", parent.id(), attribute, column.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from TB_COL where COL_SN = ?", column.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", attribute)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from TB_ENTT_TBL_MPNG where ENTT_TBL_MPNG_SN = ?", parent.id())).isInstanceOf(DataIntegrityViolationException.class);

        // 트랜잭션 롤백 : 매핑 변경 뒤 예외가 나면 변경이 남지 않는다
        TableInfo spare = table(projectId, "TB_SPARE", null, false);
        service.deleteAttributeMapping(admin, child.id());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            service.saveEntityMapping(admin, new EntityMapping(parent.id(), null, spare.getId()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.selectEntityMapping(admin, parent.id()).mapping().tableId()).isEqualTo(table.getId());

        // 속성 매핑 해제 → 속성·컬럼 삭제 가능. 컬럼이 없어도 엔터티 매핑이 있으면 테이블은 삭제할 수 없다
        logicalService.deleteAttribute(admin, attribute);
        columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(column.getId()).build()));
        rejected(() -> tableDesignService.deleteTable(List.of(TableInfoDto.builder().id(table.getId()).build()), admin), ErrorCode.RELATION_EXISTS);
        rejected(() -> logicalService.deleteEntity(admin, entity.id()), ErrorCode.RELATION_EXISTS);

        // 엔터티 매핑 해제 → 엔터티 → 논리 모델, 테이블 → 스키마 순으로 지울 수 있고 매핑 행은 남지 않는다
        service.deleteEntityMapping(admin, parent.id());
        logicalService.deleteEntity(admin, entity.id());
        logicalService.deleteModel(admin, model.id());
        tableDesignService.deleteTable(List.of(TableInfoDto.builder().id(table.getId()).build()), admin);
        schemaService.deleteSchema(admin, schema.id());
        assertThat(jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = ?", Integer.class, projectId)).isZero();

        // 프로젝트 삭제 : 기존 정책대로 프로젝트의 모델 데이터를 지우지 않는다 → 매핑도 양쪽 원본과 함께 남아 끊어진 참조가 생기지 않는다
        Entity kept = entity(admin, logicalService.saveModel(admin, new Model(null, "보존", null)), "보존");
        Long keptAttribute = attribute(admin, kept, "보존번호");
        ColumnInfo keptColumn = column(projectId, spare, "KEEP_NO", null);
        EntityMapping keptMapping = service.saveEntityMapping(admin, new EntityMapping(null, kept.id(), spare.getId()));
        service.saveAttributeMapping(admin, new AttributeMapping(null, keptMapping.id(), keptAttribute, keptColumn.getId()));
        projectManageService.deleteProject(projectId, admin);
        assertThat(projectRepository.findById(projectId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG m join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN join TB_TBL t on t.TBL_SN = m.TBL_SN "
                + "join TB_ATRB_COL_MPNG a on a.ENTT_TBL_MPNG_SN = m.ENTT_TBL_MPNG_SN join TB_LGCL_ATRB la on la.LGCL_ATRB_SN = a.LGCL_ATRB_SN join TB_COL c on c.COL_SN = a.COL_SN "
                + "where m.PRJ_SN = ?", Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    @DisplayName("현황 : 빈 모델, 매핑 없는 모델, 일부·전체 매핑, 속성만 일부 매핑, 모델별·프로젝트별 집계")
    void status() {
        AccountDto admin = loginAsAdmin("m7-status");
        long projectId = admin.getProjectId();
        Model empty = logicalService.saveModel(admin, new Model(null, "A 빈 모델", null));
        Model none = logicalService.saveModel(admin, new Model(null, "B 매핑 없음", null));
        Model partial = logicalService.saveModel(admin, new Model(null, "C 일부", null));
        Model full = logicalService.saveModel(admin, new Model(null, "D 전체", null));
        attribute(admin, entity(admin, none, "없음"), "속성");

        // 일부 : 엔터티 2개 중 1개 매핑, 그 엔터티의 속성 3개 중 1개 매핑 (+ 매핑 안 된 엔터티의 속성 1개)
        Entity mapped = entity(admin, partial, "매핑");
        Long a1 = attribute(admin, mapped, "하나");
        attribute(admin, mapped, "둘");
        attribute(admin, mapped, "셋");
        attribute(admin, entity(admin, partial, "미매핑"), "넷");
        TableInfo t1 = table(projectId, "TB_PARTIAL", null, true);
        EntityMapping m1 = service.saveEntityMapping(admin, new EntityMapping(null, mapped.id(), t1.getId()));
        service.saveAttributeMapping(admin, new AttributeMapping(null, m1.id(), a1, column(projectId, t1, "C1", null).getId()));

        // 전체 : 엔터티 2개·속성 2개 모두 매핑, 속성 없는 엔터티 1개도 매핑
        for (String name : List.of("가", "나")) {
            Entity e = entity(admin, full, name);
            Long a = attribute(admin, e, "속성");
            TableInfo t = table(projectId, "TB_FULL_" + name, null, true);
            EntityMapping m = service.saveEntityMapping(admin, new EntityMapping(null, e.id(), t.getId()));
            service.saveAttributeMapping(admin, new AttributeMapping(null, m.id(), a, column(projectId, t, "C1", null).getId()));
        }
        service.saveEntityMapping(admin, new EntityMapping(null, entity(admin, full, "속성 없음").id(), table(projectId, "TB_FULL_EMPTY", null, true).getId()));

        assertThat(service.selectStatus(admin, null)).containsExactly(
                new Status(empty.id(), "A 빈 모델", 0L, 0L, 0L, 0L, 0L, 0L),
                new Status(none.id(), "B 매핑 없음", 1L, 0L, 1L, 1L, 0L, 1L),
                new Status(partial.id(), "C 일부", 2L, 1L, 1L, 4L, 1L, 3L),
                new Status(full.id(), "D 전체", 3L, 3L, 0L, 2L, 2L, 0L));
        assertThat(service.selectStatus(admin, partial.id())).containsExactly(new Status(partial.id(), "C 일부", 2L, 1L, 1L, 4L, 1L, 3L));
        // 목록의 속성 수도 같은 기준이다
        assertThat(rows(admin, new EntitySearch(partial.id(), null, true, null, null))).extracting(EntityRow::attributeCount, EntityRow::mappedAttributeCount).containsExactly(tuple(3L, 1L));

        // 다른 프로젝트 : 자기 모델만 집계되고 남의 모델은 찾을 수 없다
        AccountDto outsider = loginAsAdmin("m7-status-other");
        assertThat(service.selectStatus(outsider, null)).isEmpty();
        Model theirs = logicalService.saveModel(outsider, new Model(null, "C 일부", null));
        assertThat(service.selectStatus(outsider, null)).containsExactly(new Status(theirs.id(), "C 일부", 0L, 0L, 0L, 0L, 0L, 0L));
        rejected(() -> service.selectStatus(outsider, partial.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
    }

    @Test
    @DisplayName("권한 : 조회는 VIEWER, 변경은 USER, 미승인 사용자는 조회도 불가. 매핑에는 승인 절차가 없다")
    void authorization() {
        AccountDto admin = loginAsAdmin("m7-role");
        long projectId = admin.getProjectId();
        Model model = logicalService.saveModel(admin, new Model(null, "권한", null));
        Entity entity = entity(admin, model, "엔터티");
        Long attribute = attribute(admin, entity, "속성");
        TableInfo table = table(projectId, "TB_ROLE_TEST", null, true);
        ColumnInfo column = column(projectId, table, "C1", null);
        EntityMapping mapping = service.saveEntityMapping(admin, new EntityMapping(null, entity.id(), table.getId()));

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "900", "ROLE_VIEWER");
        assertThat(controller.selectEntityRows(viewer, 0, 10, null, null, null, model.id(), null, null, null).getBody()).containsEntry("recordsTotal", 1L);
        assertThat(controller.selectEntityMapping(viewer, mapping.id()).getBody().attributes()).hasSize(1);
        assertThat(controller.selectUnmappedTables(viewer, 0, 10, null, null, null).getBody()).containsEntry("recordsTotal", 0L);
        assertThat(controller.selectStatus(viewer, model.id()).getBody()).hasSize(1);
        assertThatThrownBy(() -> controller.saveEntityMapping(viewer, new EntityMapping(mapping.id(), null, table.getId()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveAttributeMapping(viewer, new AttributeMapping(null, mapping.id(), attribute, column.getId()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteEntityMapping(viewer, mapping.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteAttributeMapping(viewer, 1L)).isInstanceOf(AccessDeniedException.class);

        // USER : 승인된 엔터티·테이블의 매핑도 등록·삭제한다 (매핑은 엔터티·테이블을 바꾸지 않는다)
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "901", "ROLE_USER");
        Long attributeMappingId = controller.saveAttributeMapping(user, new AttributeMapping(null, mapping.id(), attribute, column.getId())).getBody().id();
        assertThat(controller.deleteAttributeMapping(user, attributeMappingId).getBody()).containsEntry("result", true);
        assertThat(controller.deleteEntityMapping(user, mapping.id()).getBody()).containsEntry("result", true);
        assertThat(controller.saveEntityMapping(user, new EntityMapping(null, entity.id(), table.getId())).getBody().id()).isNotNull();
        assertThat(logicalService.selectEntity(user, entity.id()).isApproval()).isTrue();

        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, projectId, "902", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectEntityRows(notApproved, 0, 10, null, null, null, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectStatus(notApproved, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectUnmappedTables(notApproved, 0, 10, null, null, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("동시 요청 : 같은 엔터티·테이블·속성·컬럼은 하나만 매핑된다. 매핑 등록과 부모 매핑·엔터티·테이블 삭제가 엇갈려도 끊어진 매핑이 남지 않는다")
    void concurrency() throws Exception {
        AccountDto admin = loginAsAdmin("m7-concurrent");
        long projectId = admin.getProjectId();
        Model model = logicalService.saveModel(admin, new Model(null, "동시", null));
        Entity entity = entity(admin, model, "기준");
        List<TableInfo> tables = new ArrayList<>();
        List<Entity> entities = new ArrayList<>(); // 서로 다른 논리 모델의 엔터티 : 모델 잠금으로는 직렬화되지 않는다
        for (int i = 0; i < 4; i++) {
            tables.add(table(projectId, "TB_C" + i, null, false));
            entities.add(entity(admin, logicalService.saveModel(admin, new Model(null, "동시 " + i, null)), "엔터티"));
        }

        // 같은 엔터티 → 서로 다른 테이블
        assertThat(concurrently(4, i -> "ok " + service.saveEntityMapping(admin, new EntityMapping(null, entity.id(), tables.get(i).getId())).id()))
                .filteredOn(r -> r.startsWith("ok")).hasSize(1);
        assertThat(count("TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = " + entity.id())).isEqualTo(1);
        // 서로 다른 모델의 엔터티 → 같은 테이블
        TableInfo shared = table(projectId, "TB_SHARED", null, false);
        List<String> sameTable = concurrently(4, i -> "ok " + service.saveEntityMapping(admin, new EntityMapping(null, entities.get(i).id(), shared.getId())).id());
        assertThat(sameTable).filteredOn(r -> r.startsWith("ok")).hasSize(1);
        assertThat(sameTable).filteredOn(r -> !r.startsWith("ok")).containsOnly("SAVED_DATA_EXISTS");
        assertThat(count("TB_ENTT_TBL_MPNG where TBL_SN = " + shared.getId())).isEqualTo(1);

        // 같은 속성 → 서로 다른 컬럼, 서로 다른 속성 → 같은 컬럼
        EntityTarget target = mappedEntity(admin, model, "속성 대상", 4);
        List<String> sameAttribute = concurrently(4, i -> "ok " + service.saveAttributeMapping(admin,
                new AttributeMapping(null, target.mapping.id(), target.attributes.get(0), target.columns.get(i).getId())).id());
        assertThat(sameAttribute).filteredOn(r -> r.startsWith("ok")).hasSize(1);
        assertThat(sameAttribute).filteredOn(r -> !r.startsWith("ok")).containsOnly("SAVED_DATA_EXISTS");
        ColumnInfo sharedColumn = column(projectId, target.table, "SHARED", null);
        List<String> sameColumn = concurrently(3, i -> "ok " + service.saveAttributeMapping(admin,
                new AttributeMapping(null, target.mapping.id(), target.attributes.get(i + 1), sharedColumn.getId())).id());
        assertThat(sameColumn).filteredOn(r -> r.startsWith("ok")).hasSize(1);
        assertThat(sameColumn).filteredOn(r -> !r.startsWith("ok")).containsOnly("SAVED_DATA_EXISTS");
        assertThat(count("TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = " + target.mapping.id())).isEqualTo(2);

        // 부모 매핑 삭제 ↔ 속성 매핑 등록
        EntityTarget parent = mappedEntity(admin, model, "부모 삭제", 1);
        List<String> parentRace = concurrently(2, i -> {
            if (i == 0) {
                service.deleteEntityMapping(admin, parent.mapping.id());
                return "deleted";
            }
            return "ok " + service.saveAttributeMapping(admin, new AttributeMapping(null, parent.mapping.id(), parent.attributes.get(0), parent.columns.get(0).getId())).id();
        });
        long parents = count("TB_ENTT_TBL_MPNG where ENTT_TBL_MPNG_SN = " + parent.mapping.id());
        long children = count("TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = " + parent.mapping.id());
        assertThat(parentRace.contains("deleted") ? parents == 0 && children == 0 && parentRace.contains("MODEL_MAPPING_IS_NOT_DEFINED")
                : parents == 1 && children == 1 && parentRace.contains("RELATION_EXISTS")).as(parentRace.toString()).isTrue();

        // 엔터티 삭제 ↔ 그 엔터티의 매핑 등록
        Entity doomed = entity(admin, model, "삭제 대상");
        logicalService.updateEntityApproval(admin, doomed.id(), false);
        TableInfo forDoomed = table(projectId, "TB_DOOMED_ENTITY", null, false);
        List<String> entityRace = concurrently(2, i -> {
            if (i == 0) {
                logicalService.deleteEntity(admin, doomed.id());
                return "deleted";
            }
            return "ok " + service.saveEntityMapping(admin, new EntityMapping(null, doomed.id(), forDoomed.getId())).id();
        });
        long entityLeft = count("TB_LGCL_ENTT where LGCL_ENTT_SN = " + doomed.id());
        long entityMapped = count("TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = " + doomed.id());
        assertThat(entityRace.contains("deleted") ? entityLeft == 0 && entityMapped == 0 && entityRace.contains("LOGICAL_MODEL_IS_NOT_DEFINED")
                : entityLeft == 1 && entityMapped == 1 && entityRace.contains("RELATION_EXISTS")).as(entityRace.toString()).isTrue();

        // 테이블 삭제 ↔ 그 테이블의 매핑 등록
        Entity forTable = entity(admin, model, "테이블 삭제");
        TableInfo doomedTable = table(projectId, "TB_DOOMED_TABLE", null, false);
        List<String> tableRace = concurrently(2, i -> {
            if (i == 0) {
                tableDesignService.deleteTable(List.of(TableInfoDto.builder().id(doomedTable.getId()).build()), admin);
                return "deleted";
            }
            return "ok " + service.saveEntityMapping(admin, new EntityMapping(null, forTable.id(), doomedTable.getId())).id();
        });
        long tableLeft = count("TB_TBL where TBL_SN = " + doomedTable.getId());
        long tableMapped = count("TB_ENTT_TBL_MPNG where TBL_SN = " + doomedTable.getId());
        assertThat(tableRace.contains("deleted") ? tableLeft == 0 && tableMapped == 0 && tableRace.contains("TABLE_IS_NOT_DEFINED")
                : tableLeft == 1 && tableMapped == 1 && tableRace.contains("RELATION_EXISTS")).as(tableRace.toString()).isTrue();

        // 컬럼 삭제 ↔ 그 컬럼의 속성 매핑 등록
        EntityTarget columnTarget = mappedEntity(admin, model, "컬럼 삭제", 1);
        ColumnInfo doomedColumn = columnTarget.columns.get(0);
        List<String> columnRace = concurrently(2, i -> {
            if (i == 0) {
                columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(doomedColumn.getId()).build()));
                return "deleted";
            }
            return "ok " + service.saveAttributeMapping(admin, new AttributeMapping(null, columnTarget.mapping.id(), columnTarget.attributes.get(0), doomedColumn.getId())).id();
        });
        long columnLeft = count("TB_COL where COL_SN = " + doomedColumn.getId());
        long columnMapped = count("TB_ATRB_COL_MPNG where COL_SN = " + doomedColumn.getId());
        assertThat(columnRace.contains("deleted") ? columnLeft == 0 && columnMapped == 0 && columnRace.contains("INVALID_MODEL_MAPPING")
                : columnLeft == 1 && columnMapped == 1 && columnRace.contains("RELATION_EXISTS")).as(columnRace.toString()).isTrue();

        // 어느 경우에도 끊어진 매핑은 없다
        assertThat(count("TB_ENTT_TBL_MPNG m where not exists (select 1 from TB_LGCL_ENTT e where e.LGCL_ENTT_SN = m.LGCL_ENTT_SN) "
                + "or not exists (select 1 from TB_TBL t where t.TBL_SN = m.TBL_SN)")).isZero();
    }

    // ------------------------------------------------------------------ 보조

    private record EntityTarget(EntityMapping mapping, TableInfo table, List<Long> attributes, List<ColumnInfo> columns) {
    }

    /** 테이블에 매핑된 엔터티와 size 개의 속성·컬럼 (속성 매핑은 없다) */
    private EntityTarget mappedEntity(AccountDto account, Model model, String name, int size) {
        long projectId = account.getProjectId();
        Entity entity = entity(account, model, name);
        TableInfo table = table(projectId, "TB_" + name.replace(' ', '_'), null, false);
        List<Long> attributes = new ArrayList<>();
        List<ColumnInfo> columns = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            attributes.add(attribute(account, entity, "속성 " + i));
            columns.add(column(projectId, table, "C" + i, null));
        }
        return new EntityTarget(service.saveEntityMapping(account, new EntityMapping(null, entity.id(), table.getId())), table, attributes, columns);
    }

    private List<EntityRow> rows(AccountDto account, EntitySearch search) {
        return service.selectEntityRows(account, search, PAGE).getContent();
    }

    private List<TableCandidate> tables(AccountDto account, Long schemaId, String query) {
        return service.selectUnmappedTables(account, schemaId, query, PAGE).getContent();
    }

    private long count(String fromWhere) {
        return jdbc.queryForObject("select count(*) from " + fromWhere, Long.class);
    }

    private static void rejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private Entity entity(AccountDto account, Model model, String name) {
        return logicalService.saveEntity(account, new Entity(null, model.id(), name, null, null, null, null));
    }

    private Long attribute(AccountDto account, Entity entity, String name) {
        return logicalService.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, null, null, null, null, null, null)).id();
    }

    private TableInfo table(long projectId, String name, Long schemaId, boolean approved) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
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
