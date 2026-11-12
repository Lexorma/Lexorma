package com.koboolean.metamora;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardDomain;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.dictionary.repository.StandardDomainRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardTermRepository;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Entity;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Identifier;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Model;
import com.koboolean.metamora.data.logical.service.LogicalModelService;
import com.koboolean.metamora.data.mapping.controller.ModelMappingRestController;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.EntityRow;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.Status;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.Summary;
import com.koboolean.metamora.data.mapping.service.ModelMappingQualityService;
import com.koboolean.metamora.data.mapping.service.ModelMappingService;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-7 2차 : 논리·물리 정합성 비교 (H2 / PostgreSQL, 실제 조회 쿼리).
 * 진단 유형별 판정(일치·불일치·비교 불가·미매핑), 요약 = 목록, 조건·정렬·페이징, 프로젝트 격리, 권한, 조회 후 데이터 불변, 매핑 1건 조회(lookup).
 * 판정 규칙의 DBMS 별 경우는 ModelMappingQualityServiceTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelMappingQualityIntegrationTest {

    private static final Pageable ALL = PageRequest.of(0, 500);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logicalService;
    @Autowired ModelMappingService mappingService;
    @Autowired ModelMappingQualityService service;
    @Autowired ModelMappingRestController controller;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("정합성 : 표준 용어·명칭·타입·길이·필수·식별자 판정, 미매핑, 요약 = 목록, 조건·정렬·페이징, 프로젝트 격리, 권한, 데이터 불변")
    void quality() {
        AccountDto admin = loginAsAdmin("m7q-quality");
        long projectId = admin.getProjectId();
        StandardDomain number = domain(projectId, "번호N10", "NUMERIC", 10, null);
        StandardDomain name = domain(projectId, "명V100", "VARCHAR", 100, null);
        StandardDomain dateTime = domain(projectId, "일시", "TIMESTAMP", null, null);
        StandardDomain amount = domain(projectId, "금액N15_2", "NUMERIC", 15, 2);
        StandardDomain noType = domain(projectId, "길이만20", null, 20, null);
        StandardTerm memberNo = term(projectId, "회원_번호", "MBR_NO", number);
        StandardTerm memberName = term(projectId, "회원_명", "MBR_NM", name);
        StandardTerm customerName = term(projectId, "고객_명", "CSTMR_NM", name);
        StandardTerm joined = term(projectId, "가입_일시", "JOIN_DT", dateTime);
        StandardTerm remark = term(projectId, "비고", null, null);
        StandardTerm price = term(projectId, "금액", "AMT", amount);
        StandardTerm code = term(projectId, "코드", "CD", noType);

        Model model = logicalService.saveModel(admin, new Model(null, "회원 관리", null));
        Entity member = entity(admin, model, "회원");
        Long aNo = attribute(admin, member, "회원번호", false, memberNo);
        Long aName = attribute(admin, member, "회원명", true, memberName);
        Long aJoined = attribute(admin, member, "가입일시", false, joined);
        Long aMemo = attribute(admin, member, "메모", false, null);
        Long aStatus = attribute(admin, member, "회원상태", false, null);
        Long aRemark = attribute(admin, member, "비고", false, remark);
        Long aCode = attribute(admin, member, "코드", false, code);
        Long aPrice = attribute(admin, member, "금액", false, price);
        attribute(admin, member, "회원등급", false, null); // 매핑하지 않는다
        logicalService.saveIdentifier(admin, new Identifier(null, member.id(), "회원 식별자", true, List.of(aNo), null));
        Entity order = entity(admin, model, "주문"); // 주 식별자 없음
        Long aOrderNo = attribute(admin, order, "주문번호", true, null);
        Entity delivery = entity(admin, model, "배송"); // 매핑하지 않는다
        attribute(admin, delivery, "배송번호", false, null);
        attribute(admin, delivery, "배송지", false, null);
        entity(admin, model, "빈엔터티"); // 속성도 매핑도 없다

        TableInfo tbMember = table(projectId, "TB_MEMBER");
        TableInfo tbOrder = table(projectId, "TB_ORDER");
        ColumnInfo cNo = column(projectId, tbMember, "MBR_NO", memberNo, "NUMERIC", 10, null, null, true, false);
        ColumnInfo cName = column(projectId, tbMember, "MBR_NM", customerName, "VARCHAR", 50, null, null, false, true);
        ColumnInfo cJoined = column(projectId, tbMember, "REG_DT", null, "BIGINT", null, null, null, false, false);
        ColumnInfo cMemo = column(projectId, tbMember, "MEMO", remark, "VARCHAR", 100, null, null, false, null);
        ColumnInfo cStatus = column(projectId, tbMember, "STTS", null, "CHAR", 1, null, null, true, false);
        ColumnInfo cRemark = column(projectId, tbMember, "RMRK", remark, "VARCHAR", 100, null, null, false, true);
        ColumnInfo cCode = column(projectId, tbMember, "CD", code, "VARCHAR", 20, null, null, false, true);
        ColumnInfo cPrice = column(projectId, tbMember, "AMT", price, "NUMERIC", null, BigDecimal.valueOf(15), 0, false, true);
        column(projectId, tbMember, "SPARE", null, "VARCHAR", 10, null, null, false, true);
        ColumnInfo cOrderNo = column(projectId, tbOrder, "ORDER_NO", null, "BIGINT", null, null, null, false, false);
        EntityMapping memberMapping = mappingService.saveEntityMapping(admin, new EntityMapping(null, member.id(), tbMember.getId()));
        EntityMapping orderMapping = mappingService.saveEntityMapping(admin, new EntityMapping(null, order.id(), tbOrder.getId()));
        map(admin, memberMapping, aNo, cNo);
        map(admin, memberMapping, aName, cName);
        map(admin, memberMapping, aJoined, cJoined);
        map(admin, memberMapping, aMemo, cMemo);
        map(admin, memberMapping, aStatus, cStatus);
        map(admin, memberMapping, aRemark, cRemark);
        map(admin, memberMapping, aCode, cCode);
        map(admin, memberMapping, aPrice, cPrice);
        map(admin, orderMapping, aOrderNo, cOrderNo);
        long mappingRows = count("TB_ENTT_TBL_MPNG") + count("TB_ATRB_COL_MPNG");

        List<Row> rows = rows(admin, new Search(model.id(), null, null, null, null, null));

        // 모두 일치 : 같은 용어, 컬럼명 = 약어, 도메인과 같은 타입·길이, 주 식별자 ↔ PK
        assertThat(of(rows, "회원번호")).extracting(Row::type, Row::status).containsExactly(
                tuple(CheckType.TERM, CheckStatus.MATCH), tuple(CheckType.NAME, CheckStatus.MATCH), tuple(CheckType.DATA_TYPE, CheckStatus.MATCH),
                tuple(CheckType.LENGTH, CheckStatus.MATCH), tuple(CheckType.REQUIRED, CheckStatus.MATCH), tuple(CheckType.IDENTIFIER, CheckStatus.MATCH));
        assertThat(of(rows, "회원번호")).allMatch(row -> row.reason() == null && "TB_MEMBER".equals(row.tableName()) && "MBR_NO".equals(row.columnName())
                && row.mappingId().equals(memberMapping.id()) && "DEFAULT".equals(row.schemaName()) && "회원 관리".equals(row.modelName()));

        // 표준 용어 : 서로 다른 용어 / 논리만 / 물리만 / 둘 다 없음
        assertThat(one(rows, "회원명", CheckType.TERM)).extracting(Row::status, Row::logicalValue, Row::physicalValue, Row::reason)
                .containsExactly(CheckStatus.MISMATCH, "회원_명", "고객_명", "논리 속성과 물리 컬럼의 표준 용어가 다릅니다.");
        assertThat(one(rows, "가입일시", CheckType.TERM)).extracting(Row::status, Row::reason).containsExactly(CheckStatus.MISMATCH, "논리 속성에만 표준 용어가 연결되어 있습니다.");
        assertThat(one(rows, "메모", CheckType.TERM)).extracting(Row::status, Row::reason).containsExactly(CheckStatus.MISMATCH, "물리 컬럼에만 표준 용어가 연결되어 있습니다.");
        assertThat(one(rows, "회원상태", CheckType.TERM).status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(one(rows, "비고", CheckType.TERM).status()).isEqualTo(CheckStatus.MATCH);

        // 명칭 : 컬럼명 = 논리 속성 용어의 영문약어. 논리명과 물리명이 다른 것은 불일치가 아니다 (회원명 ↔ MBR_NM)
        assertThat(one(rows, "회원명", CheckType.NAME).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(one(rows, "가입일시", CheckType.NAME)).extracting(Row::status, Row::logicalValue, Row::physicalValue).containsExactly(CheckStatus.MISMATCH, "JOIN_DT", "REG_DT");
        assertThat(one(rows, "메모", CheckType.NAME).status()).as("속성에 용어 없음").isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(one(rows, "비고", CheckType.NAME).status()).as("용어에 약어 없음").isEqualTo(CheckStatus.NOT_COMPARABLE);

        // 데이터 타입·길이 : 논리 속성의 도메인 ↔ 컬럼
        assertThat(one(rows, "회원명", CheckType.DATA_TYPE).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(one(rows, "회원명", CheckType.LENGTH)).extracting(Row::status, Row::logicalValue, Row::physicalValue).containsExactly(CheckStatus.MISMATCH, "VARCHAR(100)", "VARCHAR(50)");
        assertThat(one(rows, "가입일시", CheckType.DATA_TYPE)).extracting(Row::status, Row::logicalValue, Row::physicalValue).containsExactly(CheckStatus.MISMATCH, "TIMESTAMP", "BIGINT");
        assertThat(one(rows, "가입일시", CheckType.LENGTH).status()).as("타입이 다르면 길이는 비교하지 않는다").isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(one(rows, "메모", CheckType.DATA_TYPE).status()).as("속성에 용어 없음").isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(one(rows, "비고", CheckType.DATA_TYPE).status()).as("용어에 도메인 없음").isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(one(rows, "코드", CheckType.DATA_TYPE).status()).as("도메인에 타입 없음").isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(one(rows, "코드", CheckType.LENGTH).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(one(rows, "금액", CheckType.LENGTH)).extracting(Row::status, Row::logicalValue, Row::physicalValue).containsExactly(CheckStatus.MISMATCH, "NUMERIC(15,2)", "NUMERIC(15)");

        // 필수 여부 : 논리 필수 ↔ NOT NULL (양방향을 구분한다)
        assertThat(one(rows, "회원명", CheckType.REQUIRED)).extracting(Row::status, Row::physicalValue, Row::reason)
                .containsExactly(CheckStatus.MISMATCH, "NULL", "필수 속성인데 컬럼이 NULL 을 허용합니다.");
        assertThat(one(rows, "가입일시", CheckType.REQUIRED)).extracting(Row::status, Row::physicalValue, Row::reason)
                .containsExactly(CheckStatus.MISMATCH, "NOT NULL", "선택 속성인데 컬럼이 NOT NULL 입니다.");
        assertThat(one(rows, "메모", CheckType.REQUIRED).status()).as("NULL 허용 여부가 없는 컬럼은 NOT NULL 이 아니다 (DDL 기준)").isEqualTo(CheckStatus.MATCH);

        // 식별자 : 주 식별자 ↔ PK
        assertThat(one(rows, "회원상태", CheckType.IDENTIFIER)).extracting(Row::status, Row::reason).containsExactly(CheckStatus.MISMATCH, "PK 컬럼인데 속성이 주 식별자가 아닙니다.");
        assertThat(one(rows, "회원명", CheckType.IDENTIFIER).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(one(rows, "주문번호", CheckType.IDENTIFIER).status()).as("주 식별자가 없는 엔터티").isEqualTo(CheckStatus.NOT_COMPARABLE);

        // 미매핑 : 속성 1 (회원등급) + 엔터티 2 (배송, 빈엔터티) + 매핑하지 않은 엔터티의 속성 2
        List<Row> unmapped = rows.stream().filter(row -> row.status() == CheckStatus.UNMAPPED).toList();
        assertThat(unmapped).allMatch(row -> row.type() == CheckType.MAPPING && row.columnId() == null);
        assertThat(unmapped).extracting(Row::entityName, Row::attributeName).containsExactlyInAnyOrder(
                tuple("회원", "회원등급"), tuple("배송", null), tuple("배송", "배송번호"), tuple("배송", "배송지"), tuple("빈엔터티", null));
        assertThat(rows.stream().filter(row -> row.type() == CheckType.MAPPING)).as("MAPPING 은 미매핑일 때만").hasSize(5);
        assertThat(rows).extracting(Row::id).doesNotHaveDuplicates();

        // 요약 : 엔터티·속성 수 = 매핑 현황, 진단 건수 = 목록
        Summary summary = service.selectSummary(admin, model.id());
        Status status = mappingService.selectStatus(admin, model.id()).get(0);
        assertThat(summary).extracting(Summary::totalEntities, Summary::mappedEntities, Summary::unmappedEntities, Summary::totalAttributes, Summary::mappedAttributes, Summary::unmappedAttributes)
                .containsExactly(status.totalEntities(), status.mappedEntities(), status.unmappedEntities(), status.totalAttributes(), status.mappedAttributes(), status.unmappedAttributes());
        assertThat(summary).extracting(Summary::totalEntities, Summary::mappedEntities, Summary::totalAttributes, Summary::mappedAttributes).containsExactly(4L, 2L, 12L, 9L);
        assertThat(summary.unmapped()).isEqualTo(summary.unmappedEntities() + summary.unmappedAttributes()).isEqualTo(5L);
        assertThat(summary.match() + summary.mismatch() + summary.notComparable()).as("매핑된 속성 × 진단 유형 6").isEqualTo(summary.mappedAttributes() * 6);
        for (CheckStatus checkStatus : CheckStatus.values()) {
            long expected = switch (checkStatus) {
                case MATCH -> summary.match();
                case MISMATCH -> summary.mismatch();
                case NOT_COMPARABLE -> summary.notComparable();
                case UNMAPPED -> summary.unmapped();
            };
            assertThat(page(admin, new Search(model.id(), null, null, checkStatus, null, null), PageRequest.of(0, 1)).getTotalElements()).as(checkStatus.name()).isEqualTo(expected);
        }
        // 비교 불가는 불일치로 세지 않는다
        assertThat(summary.mismatch()).isEqualTo(rows.stream().filter(row -> row.status() == CheckStatus.MISMATCH).count()).isEqualTo(11L);
        assertThat(summary.notComparable()).isGreaterThan(0);

        // 조건 : 유형, 상태, 엔터티, 이름 검색 (속성명·컬럼명으로 찾으면 엔터티 단위 행은 나오지 않는다)
        assertThat(rows(admin, new Search(model.id(), null, CheckType.TERM, null, null, null))).hasSize(9).allMatch(row -> row.type() == CheckType.TERM);
        assertThat(rows(admin, new Search(model.id(), null, CheckType.TERM, CheckStatus.MISMATCH, null, null))).extracting(Row::attributeName).containsExactlyInAnyOrder("회원명", "가입일시", "메모");
        assertThat(rows(admin, new Search(model.id(), order.id(), null, null, null, null))).hasSize(6).allMatch(row -> "주문".equals(row.entityName()));
        assertThat(rows(admin, new Search(model.id(), null, null, null, "entityName", "배"))).extracting(Row::attributeName).containsExactlyInAnyOrder(null, "배송번호", "배송지");
        assertThat(rows(admin, new Search(model.id(), null, null, null, "attributeName", "배송"))).extracting(Row::attributeName).containsExactlyInAnyOrder("배송번호", "배송지");
        assertThat(rows(admin, new Search(model.id(), null, null, null, "columnName", "mbr_n"))).hasSize(12).extracting(Row::columnName).containsOnly("MBR_NO", "MBR_NM");
        assertThat(rows(admin, new Search(model.id(), null, null, null, "attributeName", "없는이름"))).isEmpty();

        // 페이징·정렬 : 기본은 엔터티명 → 속성 순서 → 진단 유형. 허용하지 않은 정렬 필드는 무시한다
        Page<Row> first = page(admin, new Search(model.id(), member.id(), null, null, null, null), PageRequest.of(0, 6));
        assertThat(first.getTotalElements()).isEqualTo(8 * 6 + 1);
        assertThat(first.getContent()).extracting(Row::attributeName).containsOnly("회원번호");
        assertThat(page(admin, new Search(model.id(), member.id(), null, null, null, null), PageRequest.of(8, 6)).getContent()).extracting(Row::attributeName).containsExactly("회원등급");
        assertThat(page(admin, new Search(model.id(), null, null, null, null, null), PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "status"))).getContent())
                .allMatch(row -> row.status() == CheckStatus.UNMAPPED);
        assertThat(page(admin, new Search(model.id(), null, null, null, null, null), PageRequest.of(0, 500, Sort.by("reason"))).getContent()).isEqualTo(rows);

        // 조회는 아무것도 바꾸지 않는다
        assertThat(count("TB_ENTT_TBL_MPNG") + count("TB_ATRB_COL_MPNG")).isEqualTo(mappingRows);
        assertThat(columnInfoRepository.findById(cName.getId()).orElseThrow().getStandardTerms().getId()).isEqualTo(customerName.getId());
        assertThat(logicalService.selectEntity(admin, member.id()).attributes()).filteredOn(a -> a.id().equals(aName)).extracting(Attribute::termId).containsExactly(memberName.getId());

        // 프로젝트 격리 : 다른 프로젝트의 모델은 조회할 수 없고, 다른 프로젝트·다른 모델의 엔터티는 결과가 없다
        AccountDto other = loginAsAdmin("m7q-other");
        Model otherModel = logicalService.saveModel(other, new Model(null, "회원 관리", null));
        Entity otherEntity = entity(other, otherModel, "회원");
        rejected(() -> service.selectSummary(other, model.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectRows(other, new Search(model.id(), null, null, null, null, null), ALL), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectSummary(other, null), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(rows(other, new Search(otherModel.id(), member.id(), null, null, null, null))).isEmpty();
        assertThat(rows(other, new Search(otherModel.id(), null, null, null, null, null))).extracting(Row::entityId, Row::status).containsExactly(tuple(otherEntity.id(), CheckStatus.UNMAPPED));
        assertThat(service.selectSummary(other, otherModel.id())).extracting(Summary::match, Summary::mismatch, Summary::notComparable, Summary::unmapped).containsExactly(0L, 0L, 0L, 1L);
        assertThat(rows(admin, new Search(model.id(), otherEntity.id(), null, null, null, null))).isEmpty();

        // 매핑 1건 조회 (다른 화면의 매핑 표시) : 엔터티는 매핑이 없어도 나오고, 테이블은 매핑된 것만
        assertThat(mappingService.selectEntityRow(admin, member.id(), null)).extracting(EntityRow::mappingId, EntityRow::tableName, EntityRow::attributeCount, EntityRow::mappedAttributeCount)
                .containsExactly(memberMapping.id(), "TB_MEMBER", 9L, 8L);
        assertThat(mappingService.selectEntityRow(admin, delivery.id(), null)).extracting(EntityRow::mappingId, EntityRow::entityName, EntityRow::modelId).containsExactly(null, "배송", model.id());
        assertThat(mappingService.selectEntityRow(admin, null, tbOrder.getId())).extracting(EntityRow::entityName, EntityRow::modelId).containsExactly("주문", model.id());
        TableInfo spare = table(projectId, "TB_SPARE");
        assertThat(mappingService.selectEntityRow(admin, null, spare.getId())).isNull();
        assertThat(mappingService.selectEntityRow(admin, -1L, null)).isNull();
        assertThat(mappingService.selectEntityRow(other, member.id(), null)).isNull();
        assertThat(mappingService.selectEntityRow(other, null, tbMember.getId())).isNull();
        rejected(() -> mappingService.selectEntityRow(admin, null, null), ErrorCode.INVALID_MODEL_MAPPING);
        rejected(() -> mappingService.selectEntityRow(admin, member.id(), tbMember.getId()), ErrorCode.INVALID_MODEL_MAPPING);

        // 권한 : 조회는 VIEWER, 미승인 사용자는 불가 (컨트롤러 메서드 보안)
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "910", "ROLE_VIEWER");
        assertThat(controller.selectQualitySummary(viewer, model.id()).getBody().mismatch()).isEqualTo(11L);
        assertThat(controller.selectQualityRows(viewer, 0, 10, null, null, null, model.id(), null, null, CheckStatus.UNMAPPED).getBody()).containsEntry("recordsTotal", 5L);
        assertThat(controller.selectEntityRow(viewer, member.id(), null).getBody().tableName()).isEqualTo("TB_MEMBER");
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, projectId, "911", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectQualitySummary(notApproved, model.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectQualityRows(notApproved, 0, 10, null, null, null, model.id(), null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectEntityRow(notApproved, member.id(), null)).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ 보조

    private List<Row> rows(AccountDto account, Search search) {
        return service.selectRows(account, search, ALL).getContent();
    }

    private Page<Row> page(AccountDto account, Search search, Pageable pageable) {
        return service.selectRows(account, search, pageable);
    }

    private static List<Row> of(List<Row> rows, String attributeName) {
        return rows.stream().filter(row -> attributeName.equals(row.attributeName())).toList();
    }

    private static Row one(List<Row> rows, String attributeName, CheckType type) {
        return rows.stream().filter(row -> attributeName.equals(row.attributeName()) && row.type() == type).findFirst().orElseThrow();
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static void rejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private StandardDomain domain(long projectId, String name, String dataType, Integer length, Integer decimalLength) {
        return domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName(name).dataType(dataType)
                .dataLength(length).dataDecimalLength(decimalLength).isApproval(true).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                .standardDomain(domain).isApproval(true).build());
    }

    private Entity entity(AccountDto account, Model model, String name) {
        return logicalService.saveEntity(account, new Entity(null, model.id(), name, null, null, null, null));
    }

    private Long attribute(AccountDto account, Entity entity, String name, boolean required, StandardTerm term) {
        return logicalService.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, required, null, term == null ? null : term.getId(), null, null, null)).id();
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term, String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                              boolean pk, Boolean nullable) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType(dataType).maxLength(maxLength).precision(precision).scale(scale)
                .isPk(pk).isNullable(nullable).standardTerms(term).isApproval(false).build()));
    }

    private void map(AccountDto account, EntityMapping parent, Long attributeId, ColumnInfo column) {
        mappingService.saveAttributeMapping(account, new AttributeMapping(null, parent.id(), attributeId, column.getId()));
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
