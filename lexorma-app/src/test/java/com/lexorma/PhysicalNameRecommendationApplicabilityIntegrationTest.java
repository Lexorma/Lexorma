package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Applicability;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.TableSearch;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import com.lexorma.data.view.domain.entity.ViewChangeRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 물리명 추천 일괄 적용 1차 : 추천 행의 일괄 적용 판정 (H2 / PostgreSQL, 실제 조회 쿼리). 이름은 바꾸지 않는다.
 * 테이블·VIEW 이름 공간(같은 스키마), 다른 스키마, 추천끼리 충돌(페이지와 무관), 맞바꾸기, DBMS 식별자, 승인 상태, 표준 용어 일치·없음,
 * CHECK(식·구성 컬럼·리터럴)·인덱스 조건·VIEW(메타데이터·정의·* ·간접·처리 전 변경 요청)·판정 불가, 기존 상태·요약 불변, 프로젝트 격리, 데이터 불변.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameRecommendationApplicabilityIntegrationTest {

    private static final Pageable ALL = PageRequest.of(0, 500);
    private static final String LONG_ABBR = "A".repeat(64);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired SchemaService schemaService;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired LogicalModelService logicalService;
    @Autowired ModelMappingService mappingService;
    @Autowired PhysicalNameRecommendationService service;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("일괄 적용 판정 : 이름 공간·추천끼리 충돌·식별자·승인·용어·CHECK·인덱스·VIEW 참조, 기존 상태·요약 불변, 격리, 데이터 불변")
    void applicability() {
        AccountDto admin = loginAsAdmin("m7a-apply", "TB_");
        long projectId = admin.getProjectId();
        for (String[] w : new String[][]{{"회원", "MBR"}, {"고객", "MBR"}, {"주문", "ORDR"}, {"구매", "ORDR"}, {"등급", "GRD"}, {"상태", "STTS"}, {"비고", "RMRK"}, {"로그", "LOG"}, {"이력", "HST"}}) {
            word(projectId, w[0], w[1]);
        }
        StandardTerm tNo = term(projectId, "회원_번호", "MBR_NO");
        StandardTerm tName = term(projectId, "회원_명", "MBR_NM");
        StandardTerm tName2 = term(projectId, "이름2", "NM2");
        StandardTerm tAmt = term(projectId, "금액", "AMT");
        StandardTerm tStts = term(projectId, "상태", "STTS");
        StandardTerm tRmrk = term(projectId, "비고", "RMRK");
        StandardTerm tGrade = term(projectId, "회원_등급", "MBR_GRD");
        StandardTerm tQty = term(projectId, "수량", "QTY");
        StandardTerm tLong = term(projectId, "긴_이름", LONG_ABBR);
        StandardTerm tOrderNo = term(projectId, "주문_번호", "ORDR_NO");
        StandardTerm tOrderAmt = term(projectId, "주문_금액", "ORDR_AMT");
        StandardTerm tOrderDt = term(projectId, "주문_일시", "ORDR_DT");
        StandardTerm tLogNo = term(projectId, "로그_번호", "LOG_NO");
        StandardTerm tHstNo = term(projectId, "이력_번호", "HST_NO");

        SchemaInfo main = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        SchemaInfo other = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(projectId).schemaName("S2").isDefault(false).build()));
        Model model = logicalService.saveModel(admin, new Model(null, "일괄 적용", null));

        // ---- 테이블
        TableInfo tbMember = table(projectId, main, "TB_MEMBER", false);
        TableInfo tbOrder = table(projectId, main, "TB_ORDER", false);
        TableInfo tbGrade = table(projectId, main, "TB_GRADE", true);
        TableInfo tbMg = table(projectId, main, "TB_MG", false);
        TableInfo tbX1 = table(projectId, main, "TB_X1", false);
        TableInfo tbX2 = table(projectId, main, "TB_X2", false);
        TableInfo tbSttsNow = table(projectId, main, "TB_RMRK", false); // 맞바꾸기 : 상태 → TB_STTS, 비고 → TB_RMRK
        TableInfo tbRmrkNow = table(projectId, main, "TB_STTS", false);
        TableInfo tbLog = table(projectId, main, "TB_LOG_OLD", false);
        TableInfo tbHst = table(projectId, main, "TB_HIST", false);
        TableInfo tbCustomer = table(projectId, other, "TB_CUSTOMER", false); // 다른 스키마 : 고객 → TB_MBR (S2 에 이미 있다)
        table(projectId, other, "TB_MBR", false);
        Entity eMember = mapped(admin, model, "회원", tbMember);
        Entity eOrder = mapped(admin, model, "주문", tbOrder);
        mapped(admin, model, "등급", tbGrade);
        mapped(admin, model, "회원 등급", tbMg);
        mapped(admin, model, "주문 등급", tbX1);
        mapped(admin, model, "구매 등급", tbX2);
        mapped(admin, model, "상태", tbSttsNow);
        mapped(admin, model, "비고", tbRmrkNow);
        Entity eLog = mapped(admin, model, "로그", tbLog);
        Entity eHst = mapped(admin, model, "이력", tbHst);
        mapped(admin, model, "고객", tbCustomer);

        // ---- 컬럼 (TB_MEMBER)
        Long aNo = attr(admin, eMember, tbMember, "회원번호", tNo, column(projectId, tbMember, "MEMBER_NO", tNo, false));
        ColumnInfo cName = column(projectId, tbMember, "NAME", tName, false);
        Long aName = attr(admin, eMember, tbMember, "회원명", tName, cName);
        Long aName2 = attr(admin, eMember, tbMember, "이름2", tName2, column(projectId, tbMember, "NAME_2", tName2, false));
        Long aAmt = attr(admin, eMember, tbMember, "금액", tAmt, column(projectId, tbMember, "PRICE", tAmt, true));
        Long aStts = attr(admin, eMember, tbMember, "상태", tStts, column(projectId, tbMember, "STATUS", tRmrk, false));
        Long aRmrk = attr(admin, eMember, tbMember, "비고", tRmrk, column(projectId, tbMember, "NOTE", null, false));
        Long aG1 = attr(admin, eMember, tbMember, "등급1", tGrade, column(projectId, tbMember, "G1", tGrade, false));
        Long aG2 = attr(admin, eMember, tbMember, "등급2", tGrade, column(projectId, tbMember, "G2", tGrade, false));
        Long aQty = attr(admin, eMember, tbMember, "수량", tQty, column(projectId, tbMember, "CNT", tQty, false));
        Long aLong = attr(admin, eMember, tbMember, "긴이름", tLong, column(projectId, tbMember, "LONG_NM", tLong, false));
        Long aUnmapped = attribute(admin, eMember, "매핑없음", tOrderDt);
        // CHECK : 식(NAME), 구성 컬럼(CNT 는 인덱스로), 리터럴('NAME_2' 는 참조가 아니다)
        tx.executeWithoutResult(s -> {
            TableInfo t = entityManager.find(TableInfo.class, tbMember.getId());
            entityManager.persist(TableConstraint.builder().projectId(projectId).tableInfo(t).constraintName("CK_NAME").constraintType(TableConstraint.Type.CHECK)
                    .checkExpression("length(NAME) > 0").isApproval(false).build());
            entityManager.persist(TableConstraint.builder().projectId(projectId).tableInfo(t).constraintName("CK_LIT").constraintType(TableConstraint.Type.CHECK)
                    .checkExpression("STATUS <> 'NAME_2' -- NAME_2").isApproval(false).build());
            entityManager.persist(TableIndex.builder().projectId(projectId).tableInfo(t).indexName("IX_CNT").isUnique(false).condition("CNT > 0").isApproval(false).build());
        });

        // ---- 컬럼 (TB_ORDER : VIEW 정의 참조, 처리 전 변경 요청) / TB_LOG_OLD (SELECT *) / TB_HIST (CHECK 구성 컬럼)
        Long aOrderNo = attr(admin, eOrder, tbOrder, "주문번호", tOrderNo, column(projectId, tbOrder, "ORDER_NO", tOrderNo, false));
        Long aOrderAmt = attr(admin, eOrder, tbOrder, "주문금액", tOrderAmt, column(projectId, tbOrder, "ORDER_AMT", tOrderAmt, false));
        Long aOrderDt = attr(admin, eOrder, tbOrder, "주문일시", tOrderDt, column(projectId, tbOrder, "ORDER_DT", tOrderDt, false));
        Long aLogNo = attr(admin, eLog, tbLog, "로그번호", tLogNo, column(projectId, tbLog, "LOG_SEQ", tLogNo, false));
        ColumnInfo cHst = column(projectId, tbHst, "HIST_SEQ", tHstNo, false);
        Long aHstNo = attr(admin, eHst, tbHst, "이력번호", tHstNo, cHst);
        tx.executeWithoutResult(s -> {
            TableConstraint ck = TableConstraint.builder().projectId(projectId).tableInfo(entityManager.find(TableInfo.class, tbHst.getId())).constraintName("CK_HST")
                    .constraintType(TableConstraint.Type.CHECK).checkExpression("1 = 1").isApproval(false).build();
            ck.getColumns().add(TableConstraintColumn.builder().tableConstraint(ck).column(entityManager.find(ColumnInfo.class, cHst.getId())).columnOrder(1).build());
            entityManager.persist(ck);
        });
        PhysicalView vOrd = view(projectId, main, "V_ORD", "select o.order_no, o.qty -- order_amt\nfrom tb_order o where o.memo <> 'ORDER_AMT'", null);
        view(projectId, other, "V_LOG", "select * from tb_log_old", null);
        PhysicalView vMeta = view(projectId, main, "V_META", "select 1 as one", tbGrade);
        view(projectId, main, "TB_MBR_GRD", "select 1 as one", null); // 회원 등급 → TB_MBR_GRD : 같은 스키마의 VIEW 이름
        tx.executeWithoutResult(s -> entityManager.persist(ViewChangeRequest.builder().projectId(projectId).view(entityManager.find(PhysicalView.class, vMeta.getId()))
                .beforeData("{}").afterData("{\"definition\":\"select x.order_dt from tb_order x\",\"references\":[]}").status(ViewChangeRequest.Status.REQUESTED).build()));
        String columnNames = names("select COL_NM from TB_COL where PRJ_SN = " + projectId + " order by COL_SN");
        String tableNames = names("select TBL_NM from TB_TBL where PRJ_SN = " + projectId + " order by TBL_SN");

        // ================================================================ 테이블명
        List<TableRow> tables = service.selectTableRows(admin, new TableSearch(model.id(), null, null, null), ALL).getContent();
        assertThat(table(tables, "회원")).satisfies(r -> {
            assertThat(r.recommendedPhysicalName()).isEqualTo("TB_MBR");
            assertThat(r.status()).isEqualTo(Status.AVAILABLE); // 다른 스키마(S2)의 TB_MBR 는 충돌이 아니다
            assertThat(r.applicability().selectable()).isTrue();
            assertThat(r.applicability().blockers()).isEmpty();
        });
        assertThat(table(tables, "고객")).satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.CONFLICT); // 같은 스키마 S2 의 TB_MBR
            assertThat(codes(r.applicability())).containsExactly(ApplyCode.NAME_CONFLICT);
        });
        assertThat(table(tables, "회원 등급")).satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.CONFLICT); // 같은 스키마의 VIEW TB_MBR_GRD
            assertThat(r.reason()).isEqualTo("같은 스키마에 추천 테이블명과 이름이 같은 VIEW TB_MBR_GRD 이(가) 있습니다. 테이블과 VIEW 는 같은 이름을 쓸 수 없습니다.");
            assertThat(codes(r.applicability())).containsExactly(ApplyCode.NAME_CONFLICT);
        });
        assertThat(codes(table(tables, "주문 등급").applicability())).containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE);
        assertThat(table(tables, "주문 등급").applicability().blockers().get(0).related()).containsExactly("TB_X2");
        assertThat(table(tables, "구매 등급").applicability().blockers().get(0).related()).containsExactly("TB_X1");
        // 추천끼리 충돌은 페이지와 관계없다 : 한 건만 조회해도 다른 후보를 본다
        assertThat(codes(service.selectTableRows(admin, new TableSearch(model.id(), null, "logicalName", "주문 등급"), ALL).getContent().get(0).applicability()))
                .containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE);
        // 맞바꾸기는 지금 이름 충돌로 막힌다
        assertThat(table(tables, "상태").status()).isEqualTo(Status.CONFLICT);
        assertThat(codes(table(tables, "상태").applicability())).containsExactly(ApplyCode.NAME_CONFLICT);
        assertThat(codes(table(tables, "비고").applicability())).containsExactly(ApplyCode.NAME_CONFLICT);
        assertThat(codes(table(tables, "등급").applicability())).containsExactly(ApplyCode.TABLE_APPROVED, ApplyCode.VIEW_REFERENCE); // V_META 메타데이터 참조
        assertThat(codes(table(tables, "주문").applicability())).containsExactly(ApplyCode.VIEW_REFERENCE);
        assertThat(table(tables, "주문").applicability().blockers().get(0).related()).containsExactlyInAnyOrder("V_ORD", "V_META"); // 정의 · 처리 전 변경 요청
        assertThat(table(tables, "로그").applicability().blockers()).extracting(ApplyIssue::related).containsExactly(List.of("V_LOG")); // 다른 스키마의 VIEW
        assertThat(table(tables, "이력").applicability().selectable()).isTrue();

        // ================================================================ 컬럼명
        List<ColumnRow> rows = service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, null, null, null), ALL).getContent();
        assertThat(column(rows, aNo).applicability().selectable()).isTrue();
        assertThat(column(rows, aName2).applicability().selectable()).isTrue(); // CHECK 의 리터럴·주석은 참조가 아니다
        assertThat(codes(column(rows, aName).applicability())).containsExactly(ApplyCode.CHECK_REFERENCE);
        assertThat(codes(column(rows, aAmt).applicability())).containsExactly(ApplyCode.COLUMN_APPROVED);
        assertThat(codes(column(rows, aStts).applicability())).containsExactly(ApplyCode.TERM_MISMATCH, ApplyCode.CHECK_REFERENCE); // CK_LIT 의 STATUS
        assertThat(codes(column(rows, aRmrk).applicability())).containsExactly(ApplyCode.COLUMN_TERM_MISSING);
        assertThat(codes(column(rows, aG1).applicability())).containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE);
        assertThat(column(rows, aG1).applicability().blockers().get(0).related()).containsExactly("G2");
        assertThat(codes(column(rows, aQty).applicability())).containsExactly(ApplyCode.INDEX_REFERENCE);
        assertThat(codes(column(rows, aLong).applicability())).containsExactly(ApplyCode.IDENTIFIER_INVALID);
        assertThat(column(rows, aLong).applicability().blockers().get(0).reason()).contains("63");
        assertThat(codes(column(rows, aUnmapped).applicability())).containsExactly(ApplyCode.NOT_MAPPED);
        assertThat(column(rows, aOrderNo).applicability().blockers()).extracting(ApplyIssue::code, ApplyIssue::related)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ApplyCode.VIEW_REFERENCE, List.of("V_ORD")));
        assertThat(column(rows, aOrderAmt).applicability().selectable()).isTrue(); // V_ORD 의 주석·리터럴에만 있다
        assertThat(column(rows, aOrderDt).applicability().blockers()).extracting(ApplyIssue::related).containsExactly(List.of("V_META")); // 처리 전 변경 요청의 정의
        assertThat(codes(column(rows, aLogNo).applicability())).containsExactly(ApplyCode.VIEW_REFERENCE); // SELECT *
        assertThat(codes(column(rows, aHstNo).applicability())).containsExactly(ApplyCode.CHECK_REFERENCE); // CHECK 구성 컬럼
        // 추천끼리 충돌은 페이지와 관계없다
        assertThat(codes(service.selectColumnRows(admin, new ColumnSearch(null, null, aG2, null, null, null, null), ALL).getContent().get(0).applicability()))
                .containsExactly(ApplyCode.RECOMMENDATION_DUPLICATE);
        // 기존 상태·요약은 그대로 (판정은 별도 필드) : 요약 합계 = 상태별 목록
        ColumnSummary summary = service.selectColumnSummary(admin, model.id());
        assertThat(summary.total()).isEqualTo(rows.size());
        for (Status status : Status.values()) {
            assertThat(service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, status, null, null), ALL).getTotalElements())
                    .as(status.name()).isEqualTo(rows.stream().filter(r -> r.status() == status).count());
        }
        assertThat(rows).allSatisfy(r -> assertThat(r.applicability()).isNotNull());
        assertThat(rows).filteredOn(r -> r.status() == Status.ALREADY_MATCHED).allSatisfy(r -> assertThat(codes(r.applicability())).containsExactly(ApplyCode.NO_CHANGE));

        // 판정을 풀면 고를 수 있다 : 승인 취소·용어 연결은 다음 조회에 반영 (판정은 저장하지 않는 계산)
        jdbc.update("update TB_COL set APRV_YN = false where COL_NM = 'PRICE' and PRJ_SN = ?", projectId);
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_NM = 'NOTE' and PRJ_SN = ?", tRmrk.getId(), projectId);
        List<ColumnRow> again = service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, null, null, null), ALL).getContent();
        assertThat(column(again, aAmt).applicability().selectable()).isTrue();
        assertThat(column(again, aRmrk).applicability().selectable()).isTrue();

        // 판정은 아무것도 바꾸지 않는다
        assertThat(names("select TBL_NM from TB_TBL where PRJ_SN = " + projectId + " order by TBL_SN")).isEqualTo(tableNames);
        assertThat(names("select COL_NM from TB_COL where PRJ_SN = " + projectId + " order by COL_SN")).isEqualTo(columnNames);

        // 프로젝트 격리 : 다른 프로젝트의 VIEW·CHECK·같은 이름 테이블은 판정에 들어가지 않는다
        AccountDto otherAdmin = loginAsAdmin("m7a-other", "TB_");
        long otherProject = otherAdmin.getProjectId();
        word(otherProject, "회원", "MBR");
        StandardTerm oNo = term(otherProject, "회원_번호", "MBR_NO");
        SchemaInfo otherMain = tx.execute(s -> schemaService.resolveSchema(otherProject, null));
        TableInfo oMember = table(otherProject, otherMain, "TB_MEMBER", false);
        Model otherModel = logicalService.saveModel(otherAdmin, new Model(null, "일괄 적용", null));
        Entity oEntity = mapped(otherAdmin, otherModel, "회원", oMember);
        Long oAttr = attr(otherAdmin, oEntity, oMember, "회원번호", oNo, column(otherProject, oMember, "NAME", oNo, false));
        assertThat(service.selectTableRows(otherAdmin, new TableSearch(otherModel.id(), null, null, null), ALL).getContent().get(0).applicability().selectable()).isTrue();
        assertThat(column(service.selectColumnRows(otherAdmin, new ColumnSearch(otherModel.id(), null, null, null, null, null, null), ALL).getContent(), oAttr)
                .applicability().selectable()).isTrue(); // 첫 프로젝트의 CK_NAME·V_ORD 는 보지 않는다
    }

    // ------------------------------------------------------------------ 보조

    private static List<ApplyCode> codes(Applicability a) {
        List<ApplyCode> codes = new ArrayList<>();
        a.blockers().forEach(i -> codes.add(i.code()));
        return codes;
    }

    private static TableRow table(List<TableRow> rows, String logicalName) {
        return rows.stream().filter(r -> logicalName.equals(r.logicalName())).findFirst().orElseThrow();
    }

    private static ColumnRow column(List<ColumnRow> rows, Long attributeId) {
        return rows.stream().filter(r -> attributeId.equals(r.logicalAttributeId())).findFirst().orElseThrow();
    }

    private String names(String sql) {
        return String.join(",", jdbc.queryForList(sql, String.class));
    }

    private void word(long projectId, String name, String abbreviation) {
        wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName(name).commonStandardWordAbbreviation(abbreviation).isApproval(true).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation).isApproval(true).build());
    }

    private TableInfo table(long projectId, SchemaInfo schema, String name, boolean approved) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(entityManager.find(SchemaInfo.class, schema.getId())).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term, boolean approved) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").standardTerms(term).isApproval(approved).build()));
    }

    private PhysicalView view(long projectId, SchemaInfo schema, String name, String definition, TableInfo referenced) {
        return tx.execute(s -> {
            PhysicalView v = PhysicalView.builder().projectId(projectId).schema(entityManager.find(SchemaInfo.class, schema.getId())).viewName(name)
                    .definition(definition).isApproval(false).build();
            entityManager.persist(v);
            if (referenced != null) {
                entityManager.persist(PhysicalViewReference.builder().view(v).referenceType(PhysicalViewReference.Type.TABLE)
                        .referencedTable(entityManager.find(TableInfo.class, referenced.getId())).build());
            }
            return v;
        });
    }

    private Entity mapped(AccountDto account, Model model, String name, TableInfo table) {
        Entity entity = logicalService.saveEntity(account, new Entity(null, model.id(), name, null, null, null, null));
        mappingService.saveEntityMapping(account, new EntityMapping(null, entity.id(), table.getId()));
        return entity;
    }

    private Long attribute(AccountDto account, Entity entity, String name, StandardTerm term) {
        return logicalService.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, false, null, term == null ? null : term.getId(), null, null, null)).id();
    }

    private Long attr(AccountDto account, Entity entity, TableInfo table, String name, StandardTerm term, ColumnInfo column) {
        Long id = attribute(account, entity, name, term);
        Long mappingId = mappingService.selectEntityRows(account, new com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntitySearch(null, null, null, null, null, entity.id()), ALL)
                .getContent().get(0).mappingId();
        mappingService.saveAttributeMapping(account, new AttributeMapping(null, mappingId, id, column.getId()));
        return id;
    }

    private AccountDto loginAsAdmin(String projectName, String tablePrefix) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).tablePrefix(tablePrefix).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
