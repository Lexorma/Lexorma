package com.koboolean.metamora;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.dictionary.domain.dto.StandardTermDto;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardDomain;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.dictionary.repository.StandardDomainRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardTermRepository;
import com.koboolean.metamora.data.dictionary.service.StandardTermService;
import com.koboolean.metamora.data.erd.domain.dto.ErdDto;
import com.koboolean.metamora.data.erd.service.ErdService;
import com.koboolean.metamora.data.logical.controller.LogicalModelRestController;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Entity;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Graph;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.GraphAttribute;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.GraphEntity;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Identifier;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Model;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Relationship;
import com.koboolean.metamora.data.logical.service.LogicalModelService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-6 1차 : 논리 데이터 모델 (H2 / PostgreSQL, 실제 JPA 매핑·쿼리·제약).
 * 모델·엔터티·속성·식별자·관계의 등록·수정·삭제 정책, 표준 용어 연결, 논리 ERD, 프로젝트 격리, 권한, 동시 요청, DB 제약을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalModelIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired StandardTermService termService;
    @Autowired LogicalModelService service;
    @Autowired LogicalModelRestController controller;
    @Autowired ErdService erdService;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("논리 모델 : 등록·수정·삭제, 프로젝트 안 모델명 중복(대소문자 무시), 다른 프로젝트의 같은 이름, 프로젝트 격리")
    void models() {
        AccountDto admin = loginAsAdmin("m6-model");
        Model sales = service.saveModel(admin, new Model(null, " Sales ", "영업"));
        assertThat(sales.modelName()).isEqualTo("Sales");
        assertThat(service.saveModel(admin, new Model(sales.id(), "Sales", "영업 논리 모델")).modelDescription()).isEqualTo("영업 논리 모델");
        Model hr = service.saveModel(admin, new Model(null, "인사", null));
        // 이름순 조회지만 한글·영문 사이의 순서는 DB 정렬 규칙(H2 / PostgreSQL)에 따라 다르다
        assertThat(service.selectModels(admin)).extracting(Model::modelName).containsExactlyInAnyOrder("Sales", "인사");

        rejected(() -> service.saveModel(admin, new Model(null, "sales", null)), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveModel(admin, new Model(hr.id(), "SALES", null)), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveModel(admin, new Model(null, "  ", null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveModel(admin, new Model(null, "x".repeat(256), null)), ErrorCode.INVALID_LOGICAL_MODEL);

        // 엔터티가 있으면 삭제할 수 없다
        Entity customer = service.saveEntity(admin, new Entity(null, sales.id(), "고객", null, null, null, null));
        rejected(() -> service.deleteModel(admin, sales.id()), ErrorCode.RELATION_EXISTS);
        deleteEntity(admin, customer.id());
        service.deleteModel(admin, sales.id());
        assertThat(service.selectModels(admin)).extracting(Model::modelName).containsExactly("인사");

        // 다른 프로젝트 : 같은 이름을 쓸 수 있고, 남의 모델은 조회·수정·삭제할 수 없다
        AccountDto other = loginAsAdmin("m6-model-other");
        assertThat(service.saveModel(other, new Model(null, "인사", null)).id()).isNotEqualTo(hr.id());
        assertThat(service.selectModels(other)).hasSize(1);
        rejected(() -> service.selectModel(other, hr.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveModel(other, new Model(hr.id(), "탈취", null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteModel(other, hr.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveEntity(other, new Entity(null, hr.id(), "침입", null, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectGraph(other, hr.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(service.selectModel(admin, hr.id()).modelName()).isEqualTo("인사");
    }

    @Test
    @DisplayName("엔터티·속성 : 등록·수정·삭제, 이름 중복, 속성 순서, 표준 용어 연결(도메인은 용어의 도메인), 다른 프로젝트 용어 차단")
    void entitiesAndAttributes() {
        AccountDto admin = loginAsAdmin("m6-entity");
        long projectId = admin.getProjectId();
        Model model = service.saveModel(admin, new Model(null, "주문", null));
        Model second = service.saveModel(admin, new Model(null, "정산", null));

        Entity customer = service.saveEntity(admin, new Entity(null, model.id(), "고객", "주문하는 사람", null, null, null));
        assertThat(customer.isApproval()).as("승인 권한자가 등록하면 바로 승인된다").isTrue();
        rejected(() -> service.saveEntity(admin, new Entity(null, model.id(), " 고객 ", null, null, null, null)), ErrorCode.SAVED_DATA_EXISTS);
        assertThat(service.saveEntity(admin, new Entity(null, second.id(), "고객", null, null, null, null)).id()).as("다른 모델의 같은 엔터티명").isNotNull();
        // 수정 : 승인된 엔터티 자체는 승인 취소 후에만 바꾼다. 모델은 바뀌지 않는다
        rejected(() -> service.saveEntity(admin, new Entity(customer.id(), null, "구매 고객", null, null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        service.updateEntityApproval(admin, customer.id(), false);
        Entity renamed = service.saveEntity(admin, new Entity(customer.id(), second.id(), "구매 고객", null, null, null, null));
        assertThat(renamed.modelId()).isEqualTo(model.id());
        assertThat(service.selectEntities(admin, model.id())).extracting(Entity::entityName).containsExactly("구매 고객");
        // 아래 속성 변경은 승인된 엔터티에 승인 권한자가 하는 것이다 (승인 상태 그대로 편집)
        service.updateEntityApproval(admin, customer.id(), true);

        // 표준 용어 : 도메인은 용어를 거쳐 보인다
        StandardDomain domain = domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName("명V100").dataType("VARCHAR").dataLength(100).isApproval(true).build());
        StandardTerm term = termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName("고객_명").commonStandardTermAbbreviation("CSTMR_NM")
                .standardDomain(domain).isApproval(false).build());

        Attribute no = service.saveAttribute(admin, new Attribute(null, customer.id(), "고객번호", null, "숫자", true, null, null, null, null, null));
        Attribute name = service.saveAttribute(admin, new Attribute(null, customer.id(), "고객명", "이름", "문자", null, 99, term.getId(), null, null, null));
        Attribute grade = service.saveAttribute(admin, new Attribute(null, customer.id(), "등급", null, null, false, null, null, null, null, null));
        assertThat(List.of(no, name, grade)).extracting(Attribute::sortOrder).as("새 속성은 맨 뒤").containsExactly(1, 2, 3);
        assertThat(name).extracting(Attribute::termName, Attribute::domainName, Attribute::domainDataType, Attribute::isRequired)
                .containsExactly("고객_명", "명V100", "VARCHAR", false);
        rejected(() -> service.saveAttribute(admin, new Attribute(null, customer.id(), "고객명", null, null, null, null, null, null, null, null)), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveAttribute(admin, new Attribute(grade.id(), null, "고객번호", null, null, null, null, null, null, null, null)), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveAttribute(admin, new Attribute(null, customer.id(), "없는용어", null, null, null, null, 999_999_999L, null, null, null)), ErrorCode.NOT_FOUND_TERM_DATA);

        // 수정 : 용어 해제, 이름 변경 (순서는 그대로)
        Attribute edited = service.saveAttribute(admin, new Attribute(name.id(), null, "성명", null, "문자", true, 7, null, null, null, null));
        assertThat(edited).extracting(Attribute::attributeName, Attribute::termId, Attribute::sortOrder, Attribute::isRequired).containsExactly("성명", null, 2, true);
        service.saveAttribute(admin, new Attribute(name.id(), null, "성명", null, "문자", true, null, term.getId(), null, null, null));

        // 순서 변경 : 전체를 한 번씩
        assertThat(service.reorderAttributes(admin, customer.id(), List.of(grade.id(), no.id(), name.id()))).extracting(Attribute::attributeName).containsExactly("등급", "고객번호", "성명");
        assertThat(service.selectAttributes(admin, customer.id())).extracting(Attribute::attributeName, Attribute::sortOrder)
                .containsExactly(tuple("등급", 1), tuple("고객번호", 2), tuple("성명", 3));
        rejected(() -> service.reorderAttributes(admin, customer.id(), List.of(grade.id(), no.id())), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.reorderAttributes(admin, customer.id(), List.of(grade.id(), no.id(), no.id())), ErrorCode.INVALID_LOGICAL_MODEL);

        // 논리 속성이 쓰는 용어는 지울 수 없다
        rejected(() -> termService.deleteDataDictionaryStandardTerms(List.of(StandardTermDto.builder().id(term.getId()).build()), admin), ErrorCode.RELATION_EXISTS);

        service.deleteAttribute(admin, grade.id());
        assertThat(service.selectEntity(admin, customer.id()).attributes()).extracting(Attribute::attributeName).containsExactly("고객번호", "성명");

        // 다른 프로젝트 : 남의 엔터티·속성·용어를 쓸 수 없다
        AccountDto other = loginAsAdmin("m6-entity-other");
        Model otherModel = service.saveModel(other, new Model(null, "주문", null));
        Entity otherEntity = service.saveEntity(other, new Entity(null, otherModel.id(), "고객", null, null, null, null));
        rejected(() -> service.saveAttribute(other, new Attribute(null, otherEntity.id(), "성명", null, null, null, null, term.getId(), null, null, null)), ErrorCode.NOT_FOUND_TERM_DATA);
        rejected(() -> service.selectEntity(other, customer.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveEntity(other, new Entity(customer.id(), null, "탈취", null, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveAttribute(other, new Attribute(null, customer.id(), "침입", null, null, null, null, null, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveAttribute(other, new Attribute(no.id(), null, "침입", null, null, null, null, null, null, null, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteAttribute(other, no.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteEntity(other, customer.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(service.selectEntity(admin, customer.id()).attributes()).hasSize(2);
    }

    @Test
    @DisplayName("식별자 : 주 식별자 1개, 복합 식별자와 속성 순서, 다른 엔터티 속성·중복 속성 차단, 식별자에 쓰인 속성 삭제 차단")
    void identifiers() {
        AccountDto admin = loginAsAdmin("m6-identifier");
        Model model = service.saveModel(admin, new Model(null, "주문", null));
        Entity item = service.saveEntity(admin, new Entity(null, model.id(), "주문 상품", null, null, null, null));
        Entity product = service.saveEntity(admin, new Entity(null, model.id(), "상품", null, null, null, null));
        Long orderNo = attribute(admin, item, "주문번호");
        Long lineNo = attribute(admin, item, "순번");
        Long barcode = attribute(admin, item, "바코드");
        Long productNo = attribute(admin, product, "상품번호");

        // 복합 주 식별자 : 보낸 순서대로
        Identifier pk = service.saveIdentifier(admin, new Identifier(null, item.id(), "주문 상품 식별자", true, List.of(lineNo, orderNo), null));
        assertThat(pk).extracting(Identifier::isPrimary, Identifier::attributeIds, Identifier::attributeNames).containsExactly(true, List.of(lineNo, orderNo), List.of("순번", "주문번호"));
        Identifier alternate = service.saveIdentifier(admin, new Identifier(null, item.id(), "바코드 식별자", null, List.of(barcode), null));
        assertThat(alternate.isPrimary()).isFalse();

        rejected(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "두 번째 주 식별자", true, List.of(barcode), null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveIdentifier(admin, new Identifier(alternate.id(), null, "바코드 식별자", true, List.of(barcode), null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "바코드 식별자", false, List.of(barcode), null)), ErrorCode.SAVED_DATA_EXISTS);
        rejected(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "빈 식별자", false, List.of(), null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "중복 속성", false, List.of(barcode, barcode), null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveIdentifier(admin, new Identifier(null, item.id(), "남의 속성", false, List.of(barcode, productNo), null)), ErrorCode.INVALID_LOGICAL_MODEL);
        assertThat(service.selectIdentifiers(admin, item.id())).as("거절된 요청은 아무것도 남기지 않는다").hasSize(2);

        // 수정 : 구성과 순서를 바꾼다 (같은 속성을 다시 넣어도 UNIQUE 에 걸리지 않는다)
        Identifier reordered = service.saveIdentifier(admin, new Identifier(pk.id(), null, "주문 상품 식별자", true, List.of(orderNo, lineNo, barcode), null));
        assertThat(reordered.attributeNames()).containsExactly("주문번호", "순번", "바코드");
        assertThat(service.saveIdentifier(admin, new Identifier(pk.id(), null, "주문 상품 식별자", true, List.of(orderNo, lineNo), null)).attributeIds()).containsExactly(orderNo, lineNo);

        // 식별자에 쓰인 속성은 식별자에서 먼저 빼야 지울 수 있다
        rejected(() -> service.deleteAttribute(admin, barcode), ErrorCode.RELATION_EXISTS);
        service.deleteIdentifier(admin, alternate.id());
        service.deleteAttribute(admin, barcode);
        assertThat(service.selectEntity(admin, item.id()).identifiers()).extracting(Identifier::identifierName).containsExactly("주문 상품 식별자");

        // DB 제약 : 같은 식별자에 같은 속성을 두 번 넣을 수 없다
        assertThatThrownBy(() -> jdbc.update("insert into TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values(999999, ?, ?, 9)", pk.id(), orderNo))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 엔터티를 지우면 속성·식별자도 지워진다
        deleteEntity(admin, item.id());
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ?", Long.class, item.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_ATRB where LGCL_ENTT_SN = ?", Long.class, item.id())).isZero();
        assertThat(service.selectEntities(admin, model.id())).extracting(Entity::entityName).containsExactly("상품");
    }

    @Test
    @DisplayName("관계 : 1:1·1:N·N:M, 선택·필수, 식별·비식별, 자기 참조, 다른 모델 엔터티 차단, 관계가 있는 엔터티 삭제 차단, 승인")
    void relationships() {
        AccountDto admin = loginAsAdmin("m6-relationship");
        Model model = service.saveModel(admin, new Model(null, "조직", null));
        Model otherModel = service.saveModel(admin, new Model(null, "다른 모델", null));
        Entity dept = service.saveEntity(admin, new Entity(null, model.id(), "부서", null, null, null, null));
        Entity employee = service.saveEntity(admin, new Entity(null, model.id(), "사원", null, null, null, null));
        Entity project = service.saveEntity(admin, new Entity(null, model.id(), "프로젝트", null, null, null, null));
        Entity outsider = service.saveEntity(admin, new Entity(null, otherModel.id(), "외부", null, null, null, null));

        // 1:N 필수(사원은 부서 1개 필수, 부서는 사원 0..N), 비식별
        Relationship belongs = service.saveRelationship(admin, relationship(null, model, "소속", dept, employee, false, 1, 1, 0, null));
        assertThat(belongs).extracting(Relationship::cardinality, Relationship::isIdentifying, Relationship::sourceEntityName, Relationship::targetEntityName, Relationship::isApproval)
                .containsExactly("1:N", false, "부서", "사원", true); // 승인 권한자가 등록 → 바로 승인
        // 1:1 선택, 식별 / N:M / 자기 참조(상위 부서, 선택)
        assertThat(service.saveRelationship(admin, relationship(null, model, "부서장", employee, dept, true, 0, 1, 0, 1)).cardinality()).isEqualTo("1:1");
        Relationship assigned = service.saveRelationship(admin, relationship(null, model, null, employee, project, false, null, null, null, null));
        assertThat(assigned).extracting(Relationship::cardinality, Relationship::sourceMin, Relationship::targetMin, Relationship::relationshipName).containsExactly("N:M", 0, 0, null);
        Relationship parent = service.saveRelationship(admin, relationship(null, model, "상위 부서", dept, dept, false, 0, 1, 0, null));
        assertThat(parent.sourceEntityId()).isEqualTo(parent.targetEntityId());
        assertThat(service.selectRelationships(admin, model.id())).hasSize(4);

        // 수정 : 승인된 관계는 승인 취소 후에. N:1 로, 최대 참여 수를 숫자로
        rejected(() -> service.saveRelationship(admin, relationship(assigned.id(), model, "투입", employee, project, false, 1, 5, 1, 1)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        service.updateRelationshipApproval(admin, assigned.id(), false);
        assertThat(service.saveRelationship(admin, relationship(assigned.id(), model, "투입", employee, project, false, 1, 5, 1, 1)))
                .extracting(Relationship::cardinality, Relationship::sourceMax, Relationship::targetMin).containsExactly("N:1", 5, 1);

        // 잘못된 정의
        rejected(() -> service.saveRelationship(admin, relationship(null, model, "다른 모델", dept, outsider, false, 0, 1, 0, null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveRelationship(admin, relationship(null, otherModel, "다른 모델", dept, employee, false, 0, 1, 0, null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveRelationship(admin, relationship(null, model, "음수", dept, employee, false, -1, 1, 0, null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveRelationship(admin, relationship(null, model, "최대 0", dept, employee, false, 0, 0, 0, null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveRelationship(admin, relationship(null, model, "최소 > 최대", dept, employee, false, 2, 1, 0, null)), ErrorCode.INVALID_LOGICAL_MODEL);
        rejected(() -> service.saveRelationship(admin, new Relationship(null, model.id(), "없는 엔터티", null, dept.id(), null, 999_999_999L, null, false, 0, 1, 0, null, null, null)),
                ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(service.selectRelationships(admin, model.id())).as("거절된 요청은 저장되지 않는다").hasSize(4);
        // DB 제약 : 서비스를 거치지 않아도 잘못된 참여 수는 들어가지 않는다
        assertThatThrownBy(() -> jdbc.update("update TB_LGCL_REL set ORGNL_MAX_CNT = 0 where LGCL_REL_SN = ?", belongs.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 관계가 있는 엔터티는 삭제할 수 없다 (자기 참조 포함) → 관계를 정리한 뒤 삭제
        service.updateEntityApproval(admin, project.id(), false);
        service.updateEntityApproval(admin, dept.id(), false);
        rejected(() -> service.deleteEntity(admin, project.id()), ErrorCode.RELATION_EXISTS);
        service.deleteRelationship(admin, assigned.id());
        service.deleteEntity(admin, project.id());
        rejected(() -> service.deleteEntity(admin, dept.id()), ErrorCode.RELATION_EXISTS);

        // 승인 : 승인된 관계·엔터티 자체는 승인 취소 전에는 바꿀 수 없다. 승인된 엔터티의 속성·식별자는 승인 권한자만 바꾼다
        service.updateRelationshipApproval(admin, belongs.id(), true);
        rejected(() -> service.saveRelationship(admin, relationship(belongs.id(), model, "소속", dept, employee, true, 1, 1, 0, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.deleteRelationship(admin, belongs.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        service.updateEntityApproval(admin, employee.id(), true);
        rejected(() -> service.saveEntity(admin, new Entity(employee.id(), null, "직원", null, null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.deleteEntity(admin, employee.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        Attribute badge = service.saveAttribute(admin, new Attribute(null, employee.id(), "사번", null, null, null, null, null, null, null, null));
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "920", "ROLE_USER");
        rejected(() -> service.saveAttribute(user, new Attribute(null, employee.id(), "입사일", null, null, null, null, null, null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.saveAttribute(user, new Attribute(badge.id(), null, "사원번호", null, null, null, null, null, null, null, null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.reorderAttributes(user, employee.id(), List.of(badge.id())), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.saveIdentifier(user, new Identifier(null, employee.id(), "사원 식별자", true, List.of(badge.id()), null)), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        rejected(() -> service.deleteAttribute(user, badge.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        // 승인 권한이 없는 사용자가 등록한 엔터티·관계는 승인 대기로 들어간다
        Entity pending = service.saveEntity(user, new Entity(null, model.id(), "직급", null, null, null, null));
        assertThat(pending.isApproval()).isFalse();
        assertThat(service.saveRelationship(user, relationship(null, model, "직급 부여", pending, pending, false, 0, 1, 0, null)).isApproval()).isFalse();
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), admin.getId(), "ROLE_ADMIN");
        service.updateEntityApproval(admin, employee.id(), false);
        assertThat(service.saveEntity(admin, new Entity(employee.id(), null, "직원", null, null, null, null)).entityName()).isEqualTo("직원");

        // 다른 프로젝트 : 관계를 보거나 바꿀 수 없다
        AccountDto other = loginAsAdmin("m6-relationship-other");
        rejected(() -> service.selectRelationships(other, model.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.deleteRelationship(other, parent.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.updateRelationshipApproval(other, belongs.id(), false), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        Model theirs = service.saveModel(other, new Model(null, "조직", null));
        Entity theirEntity = service.saveEntity(other, new Entity(null, theirs.id(), "부서", null, null, null, null));
        rejected(() -> service.saveRelationship(other, relationship(null, theirs, "남의 엔터티", theirEntity, dept, false, 0, 1, 0, null)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
    }

    @Test
    @DisplayName("논리 ERD : 엔터티·속성(순서, 식별자 표시, 용어·도메인)·관계, 빈 모델, 물리 ERD 와 독립")
    void graph() {
        AccountDto admin = loginAsAdmin("m6-graph");
        long projectId = admin.getProjectId();
        Model empty = service.saveModel(admin, new Model(null, "빈 모델", null));
        assertThat(service.selectGraph(admin, empty.id())).extracting(Graph::modelName, Graph::entities, Graph::relationships).containsExactly("빈 모델", List.of(), List.of());

        StandardTerm term = termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName("주문_번호").commonStandardTermAbbreviation("ORDR_NO").isApproval(true).build());
        Model model = service.saveModel(admin, new Model(null, "주문", null));
        Entity order = service.saveEntity(admin, new Entity(null, model.id(), "주문", "고객의 주문", null, null, null));
        Entity customer = service.saveEntity(admin, new Entity(null, model.id(), "고객", null, null, null, null));
        Long orderNo = service.saveAttribute(admin, new Attribute(null, order.id(), "주문번호", null, "숫자", true, null, term.getId(), null, null, null)).id();
        Long orderDate = attribute(admin, order, "주문일시");
        Long slipNo = attribute(admin, order, "전표번호");
        attribute(admin, customer, "고객번호");
        service.saveIdentifier(admin, new Identifier(null, order.id(), "주문 식별자", true, List.of(orderNo), null));
        service.saveIdentifier(admin, new Identifier(null, order.id(), "전표 식별자", false, List.of(slipNo), null));
        service.saveRelationship(admin, relationship(null, model, "주문하다", customer, order, false, 1, 1, 0, null));

        Graph graph = service.selectGraph(admin, model.id());
        assertThat(graph.entities()).extracting(GraphEntity::name).containsExactly("고객", "주문");
        GraphEntity orderNode = graph.entities().get(1);
        assertThat(orderNode.description()).isEqualTo("고객의 주문");
        assertThat(orderNode.attributes()).extracting(GraphAttribute::id, GraphAttribute::name, GraphAttribute::identifier, GraphAttribute::primaryIdentifier, GraphAttribute::termName)
                .containsExactly(tuple(orderNo, "주문번호", true, true, "주문_번호"), tuple(orderDate, "주문일시", false, false, null), tuple(slipNo, "전표번호", true, false, null));
        assertThat(graph.relationships()).extracting(Relationship::relationshipName, Relationship::sourceEntityId, Relationship::targetEntityId, Relationship::cardinality)
                .containsExactly(tuple("주문하다", customer.id(), order.id(), "1:N"));

        // 물리 모델과 독립 : 논리 모델을 만들어도 물리 테이블·컬럼·물리 ERD 는 그대로다
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ?", Long.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Long.class, projectId)).isZero();
        ErdDto.Graph physical = erdService.selectGraph(admin, new ErdDto.Search(null, null, null, null), 0, ErdService.MAX_TABLES);
        assertThat(physical.tables()).isEmpty();
        assertThat(physical.relations()).isEmpty();
    }

    @Test
    @DisplayName("논리 ERD 레이아웃 : 저장·복원, 사용자별·모델별 격리, 다른 모델 엔터티·오래된 version·다른 프로젝트 차단, VIEWER 저장, 삭제된 엔터티 정리")
    void layout() {
        AccountDto admin = loginAsAdmin("m6-layout");
        Model model = service.saveModel(admin, new Model(null, "배치", null));
        Model otherModel = service.saveModel(admin, new Model(null, "다른 모델", null));
        Entity a = service.saveEntity(admin, new Entity(null, model.id(), "A", null, null, null, null));
        Entity b = service.saveEntity(admin, new Entity(null, model.id(), "B", null, null, null, null));
        Entity foreign = service.saveEntity(admin, new Entity(null, otherModel.id(), "C", null, null, null, null));
        LogicalModelDto.Viewport viewport = new LogicalModelDto.Viewport(10, 20, 1.5);

        // 저장한 적 없음 → 저장 → 복원, 일부만 다시 보내면 기존 좌표와 합친다
        assertThat(service.selectLayout(admin, model.id())).isEqualTo(new LogicalModelDto.Layout(null, Map.of(), null));
        LogicalModelDto.Layout saved = service.saveLayout(admin, model.id(),
                new LogicalModelDto.Layout(null, Map.of(a.id(), new LogicalModelDto.Point(100, 200), b.id(), new LogicalModelDto.Point(300, 400)), viewport));
        assertThat(saved.version()).isNotNull();
        LogicalModelDto.Layout merged = service.saveLayout(admin, model.id(),
                new LogicalModelDto.Layout(saved.version(), Map.of(b.id(), new LogicalModelDto.Point(-5, 6)), viewport));
        LogicalModelDto.Layout loaded = service.selectLayout(admin, model.id());
        assertThat(loaded.version()).isEqualTo(merged.version());
        assertThat(loaded.viewport()).isEqualTo(viewport);
        assertThat(loaded.nodes()).containsOnly(Map.entry(a.id(), new LogicalModelDto.Point(100, 200)), Map.entry(b.id(), new LogicalModelDto.Point(-5, 6)));

        // 거절 : 오래된 version, 다른 모델의 엔터티, 잘못된 좌표·배율, 내용 없음
        rejected(() -> service.saveLayout(admin, model.id(), new LogicalModelDto.Layout(saved.version(), Map.of(), viewport)), ErrorCode.ERD_LAYOUT_CONFLICT);
        rejected(() -> service.saveLayout(admin, model.id(), new LogicalModelDto.Layout(merged.version(), Map.of(foreign.id(), new LogicalModelDto.Point(0, 0)), viewport)),
                ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveLayout(admin, model.id(), new LogicalModelDto.Layout(merged.version(), Map.of(a.id(), new LogicalModelDto.Point(Double.NaN, 0)), viewport)),
                ErrorCode.INVALID_ERD_REQUEST);
        rejected(() -> service.saveLayout(admin, model.id(), new LogicalModelDto.Layout(merged.version(), Map.of(), new LogicalModelDto.Viewport(0, 0, 100))), ErrorCode.INVALID_ERD_REQUEST);
        rejected(() -> service.saveLayout(admin, model.id(), new LogicalModelDto.Layout(merged.version(), null, viewport)), ErrorCode.INVALID_ERD_REQUEST);

        // 모델별 격리 : 다른 모델의 배치는 비어 있고 따로 저장된다
        assertThat(service.selectLayout(admin, otherModel.id()).version()).isNull();
        service.saveLayout(admin, otherModel.id(), new LogicalModelDto.Layout(null, Map.of(foreign.id(), new LogicalModelDto.Point(1, 1)), viewport));
        assertThat(service.selectLayout(admin, model.id()).nodes()).doesNotContainKey(foreign.id());

        // 사용자별 격리 + VIEWER 도 자기 배치를 저장한다 (논리 모델은 바뀌지 않는다)
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "910", "ROLE_VIEWER");
        assertThat(controller.selectLayout(viewer, model.id()).getBody().version()).isNull();
        controller.saveLayout(viewer, model.id(), new LogicalModelDto.Layout(null, Map.of(a.id(), new LogicalModelDto.Point(7, 7)), viewport));
        assertThat(service.selectLayout(viewer, model.id()).nodes()).containsOnly(Map.entry(a.id(), new LogicalModelDto.Point(7, 7)));
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "911", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectLayout(notApproved, model.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveLayout(notApproved, model.id(), new LogicalModelDto.Layout(null, Map.of(), viewport))).isInstanceOf(AccessDeniedException.class);

        // 다른 프로젝트 : 조회·저장 모두 차단
        AccountDto other = loginAsAdmin("m6-layout-other");
        rejected(() -> service.selectLayout(other, model.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.saveLayout(other, model.id(), new LogicalModelDto.Layout(null, Map.of(), viewport)), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);

        // 엔터티를 지우면 그 좌표는 조회에서 빠지고, 모델을 지우면 모든 사용자의 배치가 함께 지워진다
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), admin.getId(), "ROLE_ADMIN");
        assertThat(service.selectLayout(admin, model.id()).nodes()).containsOnlyKeys(a.id(), b.id());
        deleteEntity(admin, b.id());
        assertThat(service.selectLayout(admin, model.id()).nodes()).containsOnlyKeys(a.id());
        deleteEntity(admin, a.id());
        service.deleteModel(admin, model.id());
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ?", Integer.class, model.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_ERD_LYT where LGCL_MDL_SN = ?", Integer.class, otherModel.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("권한 : 조회는 VIEWER, 변경은 USER, 승인은 승인 권한자만")
    void authorization() {
        AccountDto admin = loginAsAdmin("m6-role");
        long projectId = admin.getProjectId();
        Model model = service.saveModel(admin, new Model(null, "권한", null));
        Entity entity = service.saveEntity(admin, new Entity(null, model.id(), "엔터티", null, null, null, null));

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "900", "ROLE_VIEWER");
        assertThat(controller.selectModels(viewer).getBody()).hasSize(1);
        assertThat(controller.selectGraph(viewer, model.id()).getBody().entities()).hasSize(1);
        assertThatThrownBy(() -> controller.saveModel(viewer, new Model(null, "조회자", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.deleteEntity(viewer, entity.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.reorderAttributes(viewer, entity.id(), List.of())).isInstanceOf(AccessDeniedException.class);

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "901", "ROLE_USER");
        assertThat(controller.saveEntity(user, new Entity(null, model.id(), "사용자 엔터티", null, null, null, null)).getBody().id()).isNotNull();
        rejected(() -> controller.updateEntityApproval(user, entity.id(), true), ErrorCode.APPROVAL_PERMISSION_DENIED);

        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, projectId, "902", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectModels(notApproved)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectGraph(notApproved, model.id())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("동시 요청 : 같은 이름의 모델·엔터티, 주 식별자는 하나만 들어가고 나머지는 거절된다. 관계 생성과 엔터티 삭제가 엇갈려도 깨지지 않는다")
    void concurrency() throws Exception {
        AccountDto admin = loginAsAdmin("m6-concurrent");
        Model model = service.saveModel(admin, new Model(null, "동시", null));
        Entity entity = service.saveEntity(admin, new Entity(null, model.id(), "기준", null, null, null, null));
        Long a = attribute(admin, entity, "가");
        Long b = attribute(admin, entity, "나");

        assertThat(concurrently(4, i -> service.saveModel(admin, new Model(null, "같은 모델", null)).modelName())).containsOnlyOnce("같은 모델").contains("SAVED_DATA_EXISTS");
        assertThat(concurrently(4, i -> service.saveEntity(admin, new Entity(null, model.id(), "같은 엔터티", null, null, null, null)).entityName()))
                .containsOnlyOnce("같은 엔터티").contains("SAVED_DATA_EXISTS");
        assertThat(concurrently(4, i -> service.saveAttribute(admin, new Attribute(null, entity.id(), "같은 속성", null, null, null, null, null, null, null, null)).attributeName()))
                .containsOnlyOnce("같은 속성").contains("SAVED_DATA_EXISTS");
        assertThat(concurrently(4, i -> service.saveIdentifier(admin, new Identifier(null, entity.id(), "주 식별자 " + i, true, List.of(i % 2 == 0 ? a : b), null)).isPrimary().toString()))
                .containsOnlyOnce("true").contains("INVALID_LOGICAL_MODEL");
        assertThat(service.selectIdentifiers(admin, entity.id())).filteredOn(Identifier::isPrimary).hasSize(1);

        // 엔터티 삭제 ↔ 그 엔터티로 관계 생성 : 어느 쪽이 먼저든 고아 관계가 남지 않는다
        Entity target = service.saveEntity(admin, new Entity(null, model.id(), "삭제 대상", null, null, null, null));
        service.updateEntityApproval(admin, target.id(), false); // 승인된 엔터티는 지울 수 없으므로 승인 취소해 둔다
        List<String> results = concurrently(2, i -> {
            if (i == 0) {
                service.deleteEntity(admin, target.id());
                return "deleted";
            }
            return "related " + service.saveRelationship(admin, relationship(null, model, "엇갈림", entity, target, false, 0, 1, 0, null)).id();
        });
        boolean deleted = results.contains("deleted");
        long relations = jdbc.queryForObject("select count(*) from TB_LGCL_REL where TRGT_ENTT_SN = ?", Long.class, target.id());
        long remaining = jdbc.queryForObject("select count(*) from TB_LGCL_ENTT where LGCL_ENTT_SN = ?", Long.class, target.id());
        assertThat(deleted ? relations == 0 && remaining == 0 : relations == 1 && remaining == 1).as(results.toString()).isTrue();
    }

    // ------------------------------------------------------------------ 보조

    /** 승인 권한자가 등록한 엔터티는 승인 상태라 승인 취소 후에 지운다 */
    private void deleteEntity(AccountDto account, Long entityId) {
        service.updateEntityApproval(account, entityId, false);
        service.deleteEntity(account, entityId);
    }

    private static void rejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private Long attribute(AccountDto account, Entity entity, String name) {
        return service.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, null, null, null, null, null, null)).id();
    }

    private static Relationship relationship(Long id, Model model, String name, Entity source, Entity target, boolean identifying,
                                             Integer sourceMin, Integer sourceMax, Integer targetMin, Integer targetMax) {
        return new Relationship(id, model.id(), name, null, source.id(), null, target.id(), null, identifying, sourceMin, sourceMax, targetMin, targetMax, null, null);
    }

    interface Task {
        String run(int index) throws Exception;
    }

    /** 같은 로그인으로 동시에 실행한다. 거절되면 오류 코드를 결과로 돌려준다 (ModelMappingIntegrationTest 도 쓴다) */
    static List<String> concurrently(int threads, Task task) throws Exception {
        SecurityContext context = SecurityContextHolder.getContext();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<String> call = () -> {
                    SecurityContextHolder.setContext(context);
                    start.await();
                    try {
                        return task.run(index);
                    } catch (CustomException e) {
                        return e.getErrorCode().name();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
