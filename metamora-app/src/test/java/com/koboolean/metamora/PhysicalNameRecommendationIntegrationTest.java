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
import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardWord;
import com.koboolean.metamora.data.dictionary.repository.StandardTermRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardWordRepository;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Entity;
import com.koboolean.metamora.data.logical.domain.dto.LogicalModelDto.Model;
import com.koboolean.metamora.data.logical.service.LogicalModelService;
import com.koboolean.metamora.data.mapping.controller.ModelMappingRestController;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.EntityRow;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingDto.EntitySearch;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.koboolean.metamora.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.Source;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableRow;
import com.koboolean.metamora.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableSearch;
import com.koboolean.metamora.data.mapping.service.ModelMappingQualityService;
import com.koboolean.metamora.data.mapping.service.ModelMappingService;
import com.koboolean.metamora.data.mapping.service.PhysicalNameRecommendationService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-7 3차 : 표준 사전 기반 물리명 추천 (H2 / PostgreSQL, 실제 조회 쿼리).
 * 컬럼명(표준 용어 영문약어)·테이블명(표준 단어 + 접두어) 추천과 상태(추천 가능·일치·추천 불가·충돌), 요약 = 목록, 조건·정렬·페이징,
 * 정합성 명칭 진단과의 일치, 표준 용어 변경 후 갱신, 엔터티 ID 조건, 프로젝트 격리, 권한, 조회 후 데이터 불변.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameRecommendationIntegrationTest {

    private static final Pageable ALL = PageRequest.of(0, 500);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logicalService;
    @Autowired ModelMappingService mappingService;
    @Autowired ModelMappingQualityService qualityService;
    @Autowired PhysicalNameRecommendationService service;
    @Autowired ModelMappingRestController controller;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("물리명 추천 : 컬럼명·테이블명, 추천 가능·일치·추천 불가·충돌, 요약 = 목록, 조건·정렬·페이징, 명칭 진단과 일치, 용어 변경 반영, 격리, 권한, 데이터 불변")
    void recommendation() {
        AccountDto admin = loginAsAdmin("m7r-recommend", "TB_");
        long projectId = admin.getProjectId();
        word(projectId, "회원", "MBR", true);
        word(projectId, "등급", "GRD", true);
        word(projectId, "주문", "ORDR", false);
        StandardTerm memberNo = term(projectId, "회원_번호", "MBR_NO");
        StandardTerm memberName = term(projectId, "회원_명", "MBR_NM");
        StandardTerm joined = term(projectId, "가입_일시", "JOIN_DT");
        StandardTerm remark = term(projectId, "비고", null);
        StandardTerm grade = term(projectId, "회원_등급", " MBR_GRD ");

        Model model = logicalService.saveModel(admin, new Model(null, "회원 관리", null));
        Entity member = entity(admin, model, "회원");
        Long aNo = attribute(admin, member, "회원번호", memberNo);
        Long aName = attribute(admin, member, "회원명", memberName);
        Long aJoined = attribute(admin, member, "가입일시", joined);
        Long aRemark = attribute(admin, member, "비고", remark);
        Long aStatus = attribute(admin, member, "회원상태", null);
        Long aGrade = attribute(admin, member, "등급", grade); // 매핑하지 않는다
        Long aName2 = attribute(admin, member, "회원명2", memberName);
        Entity memberGrade = entity(admin, model, "회원 등급");
        Entity order = entity(admin, model, "주문"); // 미승인 단어, 매핑하지 않는다
        Long aOrderNo = attribute(admin, order, "주문번호", memberNo);
        attribute(admin, order, "주문메모", null);
        Entity delivery = entity(admin, model, "배송"); // 등록되지 않은 단어
        Entity gradeEntity = entity(admin, model, "등급"); // 매핑하지 않는다 + 같은 이름의 테이블이 이미 있다
        Entity doubleGrade = entity(admin, model, "등급_등급");
        Entity etc = entity(admin, model, "기타");

        TableInfo tbMember = table(projectId, "TB_MEMBER");
        TableInfo tbMemberGrade = table(projectId, "TB_MBR_GRD");
        table(projectId, "TB_GRD");
        TableInfo tbOld = table(projectId, "TB_OLD_GRADE");
        TableInfo tbDouble = table(projectId, "tb_grd_grd"); // 대소문자만 다른 이름도 같은 이름이다
        ColumnInfo cNo = column(projectId, tbMember, "MEMBER_NO", memberNo);
        ColumnInfo cName = column(projectId, tbMember, "mbr_nm", memberName);
        ColumnInfo cJoined = column(projectId, tbMember, "CREATED_AT", null);
        column(projectId, tbMember, "JOIN_DT", joined); // 매핑하지 않은 컬럼이 추천명을 쓰고 있다
        ColumnInfo cRemark = column(projectId, tbMember, "RMRK", remark);
        ColumnInfo cStatus = column(projectId, tbMember, "MBR_STTS", null);
        ColumnInfo cName2 = column(projectId, tbMember, "NM2", null);
        EntityMapping memberMapping = mappingService.saveEntityMapping(admin, new EntityMapping(null, member.id(), tbMember.getId()));
        mappingService.saveEntityMapping(admin, new EntityMapping(null, memberGrade.id(), tbMemberGrade.getId()));
        mappingService.saveEntityMapping(admin, new EntityMapping(null, doubleGrade.id(), tbOld.getId()));
        mappingService.saveEntityMapping(admin, new EntityMapping(null, etc.id(), tbDouble.getId()));
        map(admin, memberMapping, aNo, cNo);
        map(admin, memberMapping, aName, cName);
        map(admin, memberMapping, aJoined, cJoined);
        map(admin, memberMapping, aRemark, cRemark);
        map(admin, memberMapping, aStatus, cStatus);
        map(admin, memberMapping, aName2, cName2);
        String columnNames = columnNames();
        String tableNames = tableNames();

        // ---- 컬럼명 : 표준 용어의 영문약어
        List<ColumnRow> rows = columns(admin, new ColumnSearch(model.id(), null, null, null, null, null, null));
        assertThat(rows).extracting(ColumnRow::logicalName, ColumnRow::currentPhysicalName, ColumnRow::recommendedPhysicalName, ColumnRow::status).containsExactlyInAnyOrder(
                tuple("회원번호", "MEMBER_NO", "MBR_NO", Status.AVAILABLE),
                tuple("회원명", "mbr_nm", "MBR_NM", Status.ALREADY_MATCHED),
                tuple("가입일시", "CREATED_AT", "JOIN_DT", Status.CONFLICT),
                tuple("비고", "RMRK", null, Status.UNAVAILABLE),
                tuple("회원상태", "MBR_STTS", null, Status.UNAVAILABLE),
                tuple("등급", null, "MBR_GRD", Status.AVAILABLE),
                tuple("회원명2", "NM2", "MBR_NM", Status.CONFLICT),
                tuple("주문번호", null, "MBR_NO", Status.AVAILABLE),
                tuple("주문메모", null, null, Status.UNAVAILABLE));
        ColumnRow no = column(rows, "회원번호");
        assertThat(no).extracting(ColumnRow::logicalAttributeId, ColumnRow::physicalColumnId, ColumnRow::termId, ColumnRow::termName, ColumnRow::termAbbreviation, ColumnRow::source,
                        ColumnRow::entityName, ColumnRow::tableName, ColumnRow::schemaName, ColumnRow::modelName, ColumnRow::mappingId)
                .containsExactly(aNo, cNo.getId(), memberNo.getId(), "회원_번호", "MBR_NO", Source.STANDARD_TERM, "회원", "TB_MEMBER", "DEFAULT", "회원 관리", memberMapping.id());
        assertThat(no.reason()).isEqualTo("표준 용어의 영문약어 기준입니다. 현재 컬럼명과 다릅니다.");
        // 추천 불가 : 사유를 구분하고 이름을 만들지 않는다
        assertThat(column(rows, "회원상태")).extracting(ColumnRow::reason, ColumnRow::source).containsExactly("논리 속성에 표준 용어가 없어 기대 컬럼명을 알 수 없습니다.", null);
        assertThat(column(rows, "비고").reason()).isEqualTo("논리 속성의 표준 용어에 영문약어가 없어 기대 컬럼명을 알 수 없습니다.");
        // 충돌 : 같은 테이블의 다른 컬럼이 추천명을 쓰고 있다 (매핑하지 않은 컬럼 / 다른 속성에 매핑된 컬럼)
        assertThat(column(rows, "가입일시").reason()).isEqualTo("같은 테이블에 추천 컬럼명과 이름이 같은 컬럼이 이미 있습니다. 그 컬럼은 논리 속성에 매핑되지 않았습니다.");
        assertThat(column(rows, "회원명2").reason()).isEqualTo("같은 테이블에서 속성 회원명 에 매핑된 컬럼이 추천 컬럼명을 이미 쓰고 있습니다.");
        // 매핑하지 않은 속성·엔터티 : 현재 컬럼이 없어도 추천명은 나온다 (앞뒤 공백 제거)
        assertThat(column(rows, "등급")).extracting(ColumnRow::physicalColumnId, ColumnRow::tableName, ColumnRow::reason)
                .containsExactly(null, "TB_MEMBER", "표준 용어의 영문약어 기준입니다. 속성이 물리 컬럼에 매핑되지 않았습니다.");
        assertThat(column(rows, "주문번호")).extracting(ColumnRow::tableName, ColumnRow::reason).containsExactly(null, "표준 용어의 영문약어 기준입니다. 엔터티가 물리 테이블에 매핑되지 않았습니다.");
        assertThat(rows).extracting(ColumnRow::id).doesNotHaveDuplicates();

        // 요약 = 상태별 목록 건수 (DB 조건식과 자바 판정이 같다)
        ColumnSummary summary = service.selectColumnSummary(admin, model.id());
        assertThat(summary).extracting(ColumnSummary::total, ColumnSummary::available, ColumnSummary::alreadyMatched, ColumnSummary::unavailable, ColumnSummary::conflict)
                .containsExactly(9L, 3L, 1L, 3L, 2L);
        for (Status status : Status.values()) {
            List<ColumnRow> filtered = columns(admin, new ColumnSearch(model.id(), null, null, null, status, null, null));
            assertThat(filtered).as(status.name()).allMatch(row -> row.status() == status)
                    .hasSize((int) rows.stream().filter(row -> row.status() == status).count());
        }

        // 조건 : 엔터티, 속성, 컬럼(모델 없이), 이름 검색. 정렬·페이징은 DB 에서
        assertThat(columns(admin, new ColumnSearch(model.id(), order.id(), null, null, null, null, null))).extracting(ColumnRow::logicalName).containsExactly("주문번호", "주문메모");
        assertThat(columns(admin, new ColumnSearch(null, null, aJoined, null, null, null, null))).extracting(ColumnRow::logicalName, ColumnRow::status).containsExactly(tuple("가입일시", Status.CONFLICT));
        assertThat(columns(admin, new ColumnSearch(null, null, null, cNo.getId(), null, null, null))).extracting(ColumnRow::logicalName, ColumnRow::recommendedPhysicalName).containsExactly(tuple("회원번호", "MBR_NO"));
        assertThat(columns(admin, new ColumnSearch(model.id(), null, null, null, null, "entityName", "주"))).hasSize(2);
        assertThat(columns(admin, new ColumnSearch(model.id(), null, null, null, null, "logicalName", "회원명"))).extracting(ColumnRow::logicalName).containsExactlyInAnyOrder("회원명", "회원명2");
        assertThat(columns(admin, new ColumnSearch(model.id(), null, null, null, null, "currentPhysicalName", "member"))).extracting(ColumnRow::logicalName).containsExactly("회원번호");
        assertThat(columns(admin, new ColumnSearch(model.id(), null, null, null, Status.CONFLICT, "recommendedPhysicalName", "mbr_n"))).extracting(ColumnRow::logicalName).containsExactly("회원명2");
        Page<ColumnRow> first = service.selectColumnRows(admin, new ColumnSearch(model.id(), member.id(), null, null, null, null, null), PageRequest.of(0, 3));
        assertThat(first.getTotalElements()).isEqualTo(7);
        assertThat(first.getContent()).extracting(ColumnRow::logicalName).containsExactly("회원번호", "회원명", "가입일시");
        assertThat(service.selectColumnRows(admin, new ColumnSearch(model.id(), member.id(), null, null, null, null, null), PageRequest.of(2, 3)).getContent())
                .extracting(ColumnRow::logicalName).containsExactly("회원명2");
        // 정렬 : 허용한 필드만 받고(그 밖은 무시) 내림차순은 오름차순의 역순이다 (한글 정렬 순서 자체는 DB 정렬 규칙에 따른다)
        List<String> ascending = service.selectColumnRows(admin, new ColumnSearch(model.id(), order.id(), null, null, null, null, null),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "logicalName"))).getContent().stream().map(ColumnRow::logicalName).toList();
        assertThat(ascending).containsExactlyInAnyOrder("주문번호", "주문메모");
        assertThat(service.selectColumnRows(admin, new ColumnSearch(model.id(), order.id(), null, null, null, null, null),
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "logicalName", "unknown"))).getContent()).extracting(ColumnRow::logicalName).containsExactly(ascending.get(1), ascending.get(0));

        // 정합성의 명칭 진단과 같은 이름·같은 판정 : 일치 ↔ ALREADY_MATCHED, 비교 불가 ↔ UNAVAILABLE, 불일치 ↔ AVAILABLE·CONFLICT
        List<ModelMappingQualityDto.Row> names = qualityService.selectRows(admin, new ModelMappingQualityDto.Search(model.id(), null, CheckType.NAME, null, null, null), ALL).getContent();
        assertThat(names).hasSize(6);
        for (ModelMappingQualityDto.Row name : names) {
            ColumnRow row = column(rows, name.attributeName());
            assertThat(name.logicalValue()).as(name.attributeName()).isEqualTo(row.recommendedPhysicalName());
            assertThat(name.status()).as(name.attributeName()).isEqualTo(switch (row.status()) {
                case ALREADY_MATCHED -> CheckStatus.MATCH;
                case UNAVAILABLE -> CheckStatus.NOT_COMPARABLE;
                case AVAILABLE, CONFLICT -> CheckStatus.MISMATCH;
            });
        }

        // ---- 테이블명 : 엔터티명의 단어가 모두 승인된 표준 단어일 때 접두어 + 영문약어
        List<TableRow> tables = tables(admin, new TableSearch(model.id(), null, null, null));
        assertThat(tables).extracting(TableRow::logicalName, TableRow::currentPhysicalName, TableRow::recommendedPhysicalName, TableRow::status).containsExactlyInAnyOrder(
                tuple("회원", "TB_MEMBER", "TB_MBR", Status.AVAILABLE),
                tuple("회원 등급", "TB_MBR_GRD", "TB_MBR_GRD", Status.ALREADY_MATCHED),
                tuple("주문", null, null, Status.UNAVAILABLE),
                tuple("배송", null, null, Status.UNAVAILABLE),
                tuple("등급", null, "TB_GRD", Status.CONFLICT),
                tuple("등급_등급", "TB_OLD_GRADE", "TB_GRD_GRD", Status.CONFLICT),
                tuple("기타", "tb_grd_grd", null, Status.UNAVAILABLE));
        TableRow memberRow = table(tables, "회원");
        assertThat(memberRow).extracting(TableRow::logicalEntityId, TableRow::physicalTableId, TableRow::source, TableRow::tablePrefix, TableRow::schemaName)
                .containsExactly(member.id(), tbMember.getId(), Source.STANDARD_WORD, "TB_", "DEFAULT");
        assertThat(memberRow.words()).extracting(w -> w.word() + "=" + w.abbreviation()).containsExactly("회원=MBR");
        assertThat(table(tables, "주문").reason()).isEqualTo("엔터티명의 단어가 승인되지 않은 표준 단어입니다 : 주문");
        assertThat(table(tables, "배송").reason()).startsWith("엔터티명의 단어가 표준 단어로 등록되지 않았습니다 : 배송.");
        assertThat(table(tables, "등급").reason()).isEqualTo("추천 테이블명과 이름이 같은 테이블이 이미 있습니다. 그 테이블은 논리 엔터티에 매핑되지 않았습니다.");
        assertThat(table(tables, "등급_등급").reason()).isEqualTo("엔터티 기타 에 매핑된 테이블이 추천 테이블명을 이미 쓰고 있습니다.");
        assertThat(tables(admin, new TableSearch(null, memberGrade.id(), null, null))).extracting(TableRow::logicalName).containsExactly("회원 등급");
        assertThat(tables(admin, new TableSearch(model.id(), null, "logicalName", "등급"))).hasSize(3);
        assertThat(tables(admin, new TableSearch(model.id(), null, "currentPhysicalName", "tb_m"))).extracting(TableRow::logicalName).containsExactlyInAnyOrder("회원", "회원 등급");
        Page<TableRow> tablePage = service.selectTableRows(admin, new TableSearch(model.id(), null, null, null), PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "logicalName")));
        assertThat(tablePage.getTotalElements()).isEqualTo(7);
        // 정렬은 DB 가 한다 (한글 정렬 순서는 DB 정렬 규칙에 따라 다르다) : 같은 정렬의 전체 목록 앞 2건과 같다
        assertThat(tablePage.getContent()).hasSize(2).isEqualTo(service.selectTableRows(admin, new TableSearch(model.id(), null, null, null),
                PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "logicalName"))).getContent().subList(0, 2));
        assertThat(service.selectTableRows(admin, new TableSearch(model.id(), null, null, null), PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "logicalName"))).getContent())
                .extracting(TableRow::logicalName).last().isEqualTo(tablePage.getContent().get(0).logicalName());

        // 추천은 아무것도 바꾸지 않는다
        assertThat(columnNames()).isEqualTo(columnNames);
        assertThat(tableNames()).isEqualTo(tableNames);

        // 표준 용어·단어를 바꾸면 다음 조회에 반영된다 (저장하지 않는 계산이다)
        memberNo.setCommonStandardTermAbbreviation("MBR_ID");
        termRepository.save(memberNo);
        assertThat(column(columns(admin, new ColumnSearch(model.id(), null, aNo, null, null, null, null)), "회원번호").recommendedPhysicalName()).isEqualTo("MBR_ID");
        memberName.setCommonStandardTermAbbreviation("NM2");
        termRepository.save(memberName);
        assertThat(columns(admin, new ColumnSearch(model.id(), null, null, null, null, "logicalName", "회원명"))).extracting(ColumnRow::logicalName, ColumnRow::status)
                .containsExactlyInAnyOrder(tuple("회원명", Status.CONFLICT), tuple("회원명2", Status.ALREADY_MATCHED));

        // 엔터티 ID 조건 (매핑 목록) : 이름이 겹치는 엔터티가 있어도 그 엔터티 1건만
        assertThat(mappingService.selectEntityRows(admin, new EntitySearch(model.id(), null, null, null, null, member.id()), ALL).getContent())
                .extracting(EntityRow::entityName).containsExactly("회원");
        assertThat(mappingService.selectEntityRows(admin, new EntitySearch(model.id(), null, null, "entityName", "회원"), ALL).getContent()).hasSize(2);
        assertThat(mappingService.selectEntityRows(admin, new EntitySearch(model.id(), null, null, null, null, -1L), ALL).getContent()).isEmpty();

        // 프로젝트 격리 : 다른 프로젝트의 모델은 거절, 다른 프로젝트의 엔터티·속성·컬럼은 결과 없음
        AccountDto other = loginAsAdmin("m7r-other", null);
        Model otherModel = logicalService.saveModel(other, new Model(null, "회원 관리", null));
        Entity otherEntity = entity(other, otherModel, "회원");
        rejected(() -> service.selectColumnSummary(other, model.id()), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectColumnRows(other, new ColumnSearch(model.id(), null, null, null, null, null, null), ALL), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectTableRows(other, new TableSearch(model.id(), null, null, null), ALL), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectColumnRows(other, new ColumnSearch(null, null, null, null, null, null, null), ALL), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        rejected(() -> service.selectTableRows(other, new TableSearch(null, null, null, null), ALL), ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(columns(other, new ColumnSearch(null, null, aNo, null, null, null, null))).isEmpty();
        assertThat(columns(other, new ColumnSearch(null, null, null, cNo.getId(), null, null, null))).isEmpty();
        assertThat(columns(other, new ColumnSearch(otherModel.id(), member.id(), null, null, null, null, null))).isEmpty();
        assertThat(tables(other, new TableSearch(null, member.id(), null, null))).isEmpty();
        assertThat(mappingService.selectEntityRows(other, new EntitySearch(null, null, null, null, null, member.id()), ALL).getContent()).isEmpty();
        // 다른 프로젝트의 표준 단어는 쓰지 않는다 (같은 이름의 단어가 이 프로젝트에는 없다). 접두어도 프로젝트 설정을 따른다
        assertThat(tables(other, new TableSearch(otherModel.id(), null, null, null))).extracting(TableRow::logicalEntityId, TableRow::status, TableRow::tablePrefix)
                .containsExactly(tuple(otherEntity.id(), Status.UNAVAILABLE, null));
        word(other.getProjectId(), "회원", "MEMBER", true);
        assertThat(tables(other, new TableSearch(otherModel.id(), null, null, null))).extracting(TableRow::recommendedPhysicalName, TableRow::status).containsExactly(tuple("MEMBER", Status.AVAILABLE));
        assertThat(service.selectColumnSummary(other, otherModel.id()).total()).isZero();

        // 권한 : 조회는 VIEWER, 미승인 사용자는 불가 (컨트롤러 메서드 보안)
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "920", "ROLE_VIEWER");
        assertThat(controller.selectColumnRecommendationSummary(viewer, model.id()).getBody().total()).isEqualTo(9L);
        assertThat(controller.selectColumnRecommendations(viewer, 0, 10, null, null, null, model.id(), null, null, null, Status.UNAVAILABLE).getBody()).containsEntry("recordsTotal", 3L);
        assertThat(controller.selectTableRecommendations(viewer, 0, 10, null, null, null, model.id(), null).getBody()).containsEntry("recordsTotal", 7L);
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, projectId, "921", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectColumnRecommendationSummary(notApproved, model.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectColumnRecommendations(notApproved, 0, 10, null, null, null, model.id(), null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectTableRecommendations(notApproved, 0, 10, null, null, null, model.id(), null)).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ 보조

    private List<ColumnRow> columns(AccountDto account, ColumnSearch search) {
        return service.selectColumnRows(account, search, ALL).getContent();
    }

    private List<TableRow> tables(AccountDto account, TableSearch search) {
        return service.selectTableRows(account, search, ALL).getContent();
    }

    private static ColumnRow column(List<ColumnRow> rows, String logicalName) {
        return rows.stream().filter(row -> logicalName.equals(row.logicalName())).findFirst().orElseThrow();
    }

    private static TableRow table(List<TableRow> rows, String logicalName) {
        return rows.stream().filter(row -> logicalName.equals(row.logicalName())).findFirst().orElseThrow();
    }

    private String columnNames() {
        return String.join(",", jdbc.queryForList("select COL_NM from TB_COL order by COL_SN", String.class));
    }

    private String tableNames() {
        return String.join(",", jdbc.queryForList("select TBL_NM from TB_TBL order by TBL_SN", String.class));
    }

    private static void rejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private void word(long projectId, String name, String abbreviation, boolean approved) {
        wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName(name).commonStandardWordAbbreviation(abbreviation).isApproval(approved).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation).isApproval(true).build());
    }

    private Entity entity(AccountDto account, Model model, String name) {
        return logicalService.saveEntity(account, new Entity(null, model.id(), name, null, null, null, null));
    }

    private Long attribute(AccountDto account, Entity entity, String name, StandardTerm term) {
        return logicalService.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, false, null, term == null ? null : term.getId(), null, null, null)).id();
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

    private void map(AccountDto account, EntityMapping parent, Long attributeId, ColumnInfo column) {
        mappingService.saveAttributeMapping(account, new AttributeMapping(null, parent.id(), attributeId, column.getId()));
    }

    private AccountDto loginAsAdmin(String projectName, String tablePrefix) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).tablePrefix(tablePrefix).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
