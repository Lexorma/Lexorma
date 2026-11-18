package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.PairInput;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.PairSave;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.RelationPlan;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 논리 관계 속성 짝 기반 FK 생성 (M-7 보강 4차, P1·P2·P4·P5·P7) : 일반 단일(참조 테이블명 → REF_COLUMN 관계)·자기 참조 단일·복합·자기 참조 복합(TableRelation, 미승인),
 * 같은 부모의 여러 관계, 순환, 이미 매핑된(다른 스키마 포함) 부모, 만들 수 없는 관계의 사유(짝 없음·부모 테이블 없음·키 불일치·타입·충돌·자기 키), 필수 FK 속성,
 * 미리보기 = 생성 결과 = DDL, planToken. AI Provider 를 등록하지 않은 상태로 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalGenerationForeignKeyIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(874_000_000L);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingService mappingService;
    @Autowired EntityTableMappingRepository entityMappings;
    @Autowired SchemaInfoRepository schemas;
    @Autowired TableDesignService tableDesign;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("일반 단일 FK : 자식 컬럼의 참조 테이블명 → 커밋 때 REF_COLUMN 관계, DDL FOREIGN KEY. 같은 부모의 두 관계는 FK 두 개(서로 다른 컬럼·이름)")
    void singleAndMultipleForeignKeys() {
        Ctx c = ctx("fk-single");
        long arpt = c.entity("공항");
        long arptCd = c.attr(arpt, "공항코드", true, "ARPT_CD");
        c.pk(arpt, arptCd);
        long flt = c.entity("항공편");
        long fltNo = c.attr(flt, "항공편번호", true, "FLT_NO");
        long dprt = c.attr(flt, "출발공항코드", true, "DPRT_ARPT_CD");
        long arvl = c.attr(flt, "도착공항코드", false, "ARVL_ARPT_CD");
        c.pk(flt, fltNo);
        LogicalModelDto.Relationship departure = c.rel("출발", arpt, flt, false, 1);
        LogicalModelDto.Relationship arrival = c.rel("도착", arpt, flt, false, 0);
        c.pairs(departure, new long[]{arptCd, dprt});
        c.pairs(arrival, new long[]{arptCd, arvl});

        PhysicalGenerationDto.Plan plan = generation.preview(c.user, c.request(List.of(arpt, flt)));
        assertThat(plan.creatable()).as(plan.toString()).isTrue();
        assertThat(plan.relations()).extracting(RelationPlan::fkStatus, RelationPlan::fkType, RelationPlan::refTableName, RelationPlan::constraintName)
                .containsExactly(tuple("READY", "SINGLE", "ARPT", "FK_FLT_DPRT_ARPT_CD"), tuple("READY", "SINGLE", "ARPT", "FK_FLT_ARVL_ARPT_CD"));
        assertThat(plan.relations()).allSatisfy(r -> assertThat(r.fkCreated()).isTrue());
        assertThat(plan.tables().get(1).columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName, PhysicalGenerationDto.ColumnPlan::refTableName)
                .containsExactly(tuple("FLT_NO", null), tuple("DPRT_ARPT_CD", "ARPT"), tuple("ARVL_ARPT_CD", "ARPT"));

        PhysicalGenerationDto.Result result = generation.apply(c.user, c.request(List.of(arpt, flt), plan.planToken()));
        assertThat(result.foreignKeys()).isEqualTo(2);
        long fltTable = result.tableIds().get(1);
        assertThat(jdbc.queryForList("select c.COL_NM from TB_TBL_REL r join TB_COL c on c.COL_SN = r.DRVN_COL_SN where r.ORGNL_TBL_SN = ? and r.REL_SRC_CD = 'REF_COLUMN' order by c.COL_NM",
                String.class, fltTable)).containsExactly("ARVL_ARPT_CD", "DPRT_ARPT_CD");
        String ddl = tableDesign.generateTableDdl(fltTable, c.user, null).ddl();
        assertThat(ddl).contains("ADD CONSTRAINT FK_FLT_DPRT_ARPT_CD FOREIGN KEY (DPRT_ARPT_CD) REFERENCES ARPT (ARPT_CD)")
                .contains("ADD CONSTRAINT FK_FLT_ARVL_ARPT_CD FOREIGN KEY (ARVL_ARPT_CD) REFERENCES ARPT (ARPT_CD)");
    }

    @Test
    @DisplayName("자기 참조 단일·복합 FK : TableRelation(원본 = 대상 테이블, 미승인)으로 만들고 DDL 이 빠뜨리지 않는다. 자식 속성이 부모 키 자신이면 UNSUPPORTED")
    void selfReferences() {
        Ctx c = ctx("fk-self");
        long emp = c.entity("직원");
        long empNo = c.attr(emp, "직원번호", true, "EMP_NO");
        long upr = c.attr(emp, "상위직원번호", false, "UPR_EMP_NO");
        c.pk(emp, empNo);
        c.pairs(c.rel("상위 직원", emp, emp, false, 0), new long[]{empNo, upr});
        long dept = c.entity("부서");
        long co = c.attr(dept, "회사코드", true, "CO_CD");
        long deptNo = c.attr(dept, "부서번호", true, "DEPT_NO");
        long uprCo = c.attr(dept, "상위회사코드", false, "UPR_CO_CD");
        long uprDept = c.attr(dept, "상위부서번호", false, "UPR_DEPT_NO");
        c.pk(dept, co, deptNo);
        c.pairs(c.rel("상위 부서", dept, dept, false, 0), new long[]{co, uprCo}, new long[]{deptNo, uprDept});
        long team = c.entity("팀");
        long teamNo = c.attr(team, "팀번호", true, "TEAM_NO");
        c.pk(team, teamNo);
        c.pairs(c.rel("자기 키", team, team, false, 0), new long[]{teamNo, teamNo});

        PhysicalGenerationDto.Plan plan = generation.preview(c.user, c.request(List.of(emp, dept, team)));
        Map<Long, RelationPlan> byChild = new HashMap<>();
        plan.relations().forEach(r -> byChild.put(r.childEntityId(), r));
        assertThat(byChild.get(emp)).extracting(RelationPlan::fkStatus, RelationPlan::fkType, RelationPlan::constraintName).containsExactly("READY", "SELF_SINGLE", "FK_EMP_UPR_EMP_NO");
        assertThat(byChild.get(emp).refTableName()).as("자기 참조 단일은 참조 테이블명을 쓰지 않는다").isNull();
        assertThat(byChild.get(dept)).extracting(RelationPlan::fkStatus, RelationPlan::fkType).containsExactly("READY", "SELF_COMPOSITE");
        assertThat(byChild.get(dept).columns()).extracting(PhysicalGenerationDto.FkColumn::parentColumnName, PhysicalGenerationDto.FkColumn::childColumnName)
                .containsExactly(tuple("CO_CD", "UPR_CO_CD"), tuple("DEPT_NO", "UPR_DEPT_NO"));
        assertThat(byChild.get(team)).extracting(RelationPlan::fkStatus, RelationPlan::fkCreated).containsExactly("UNSUPPORTED", false);
        assertThat(byChild.get(team).reasons()).first().isEqualTo("SELF_KEY_ONLY");

        PhysicalGenerationDto.Result result = generation.apply(c.user, c.request(List.of(emp, dept, team), plan.planToken()));
        assertThat(result.foreignKeys()).isEqualTo(2);
        long empTable = c.table(emp);
        long deptTable = c.table(dept);
        assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = TRGT_TBL_SN and ORGNL_TBL_SN in (?, ?) and REL_SRC_CD = 'USER' and REL_TYPE_CD = 'FK' and APRV_YN = false",
                empTable, deptTable)).as("자기 참조 관계 2개, 미승인 USER FK").isEqualTo(2);
        assertThat(tableDesign.generateTableDdl(empTable, c.user, null).ddl()).contains("ALTER TABLE EMP ADD CONSTRAINT FK_EMP_UPR_EMP_NO FOREIGN KEY (UPR_EMP_NO) REFERENCES EMP (EMP_NO)");
        assertThat(tableDesign.generateTableDdl(deptTable, c.user, null).ddl())
                .contains("ADD CONSTRAINT FK_DEPT_UPR_CO_CD FOREIGN KEY (UPR_CO_CD, UPR_DEPT_NO) REFERENCES DEPT (CO_CD, DEPT_NO)");
        assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", c.table(team))).isZero();
    }

    @Test
    @DisplayName("복합 FK : 짝 순서 = TableRelation 컬럼 순서 = DDL 의 FK·참조 컬럼 순서. 부모 PK 순서와 다르면 ORDER_DIFFERS 경고를 유지한다")
    void compositeOrder() {
        Ctx c = ctx("fk-composite");
        long ord = c.entity("주문");
        long ordNo = c.attr(ord, "주문번호", true, "ORD_NO");
        long dtlNo = c.attr(ord, "상세번호", true, "DTL_NO");
        c.pk(ord, ordNo, dtlNo);
        long hist = c.entity("주문 이력");
        long histNo = c.attr(hist, "이력번호", true, "HSTRY_NO");
        long srcDtl = c.attr(hist, "원본상세번호", true, "ORGNL_DTL_NO");
        long srcOrd = c.attr(hist, "원본주문번호", true, "ORGNL_ORD_NO");
        c.pk(hist, histNo);
        LogicalModelDto.Relationship rel = c.rel("주문 이력", ord, hist, false, 1);
        c.pairs(rel, new long[]{dtlNo, srcDtl}, new long[]{ordNo, srcOrd}); // 부모 PK 순서(주문번호, 상세번호)와 반대

        PhysicalGenerationDto.Plan plan = generation.preview(c.user, c.request(List.of(ord, hist)));
        RelationPlan relation = plan.relations().get(0);
        assertThat(relation).extracting(RelationPlan::fkStatus, RelationPlan::fkType).containsExactly("READY", "COMPOSITE");
        assertThat(relation.pairWarnings()).contains("ORDER_DIFFERS");
        assertThat(relation.columns()).extracting(PhysicalGenerationDto.FkColumn::order, PhysicalGenerationDto.FkColumn::parentColumnName, PhysicalGenerationDto.FkColumn::childColumnName)
                .containsExactly(tuple(1, "DTL_NO", "ORGNL_DTL_NO"), tuple(2, "ORD_NO", "ORGNL_ORD_NO"));
        PhysicalGenerationDto.Result result = generation.apply(c.user, c.request(List.of(ord, hist), plan.planToken()));
        long histTable = result.tableIds().get(1);
        assertThat(jdbc.queryForList("select oc.COL_NM || '>' || tc.COL_NM from TB_TBL_REL_COL x join TB_TBL_REL r on r.REL_SN = x.REL_SN join TB_COL oc on oc.COL_SN = x.ORGNL_COL_SN"
                + " join TB_COL tc on tc.COL_SN = x.TRGT_COL_SN where r.ORGNL_TBL_SN = ? order by x.COL_ORD", String.class, histTable))
                .containsExactly("ORGNL_DTL_NO>DTL_NO", "ORGNL_ORD_NO>ORD_NO");
        assertThat(tableDesign.generateTableDdl(histTable, c.user, null).ddl())
                .contains("FOREIGN KEY (ORGNL_DTL_NO, ORGNL_ORD_NO) REFERENCES ORD (DTL_NO, ORD_NO)");
    }

    @Test
    @DisplayName("순환 관계 (A → B, B → A) : 같은 요청에서 두 테이블을 먼저 만들고 FK 둘을 만든다 — 저장 순서 때문에 대상이 없다고 하지 않는다")
    void cycle() {
        Ctx c = ctx("fk-cycle");
        long a = c.entity("고객");
        long aNo = c.attr(a, "고객번호", true, "CUST_NO");
        long aRep = c.attr(a, "대표주문번호", false, "REP_ORD_NO");
        c.pk(a, aNo);
        long b = c.entity("주문");
        long bNo = c.attr(b, "주문번호", true, "ORD_NO");
        long bCust = c.attr(b, "고객번호", true, "ORD_CUST_NO");
        c.pk(b, bNo);
        c.pairs(c.rel("고객 주문", a, b, false, 1), new long[]{aNo, bCust});
        c.pairs(c.rel("대표 주문", b, a, false, 0), new long[]{bNo, aRep});
        PhysicalGenerationDto.Plan plan = generation.preview(c.user, c.request(List.of(a, b)));
        assertThat(plan.relations()).extracting(RelationPlan::fkStatus).containsOnly("READY");
        PhysicalGenerationDto.Result result = generation.apply(c.user, c.request(List.of(a, b), plan.planToken()));
        assertThat(result.foreignKeys()).isEqualTo(2);
        assertThat(tableDesign.generateTableDdl(c.table(a), c.user, null).ddl()).contains("FOREIGN KEY (REP_ORD_NO) REFERENCES ORD (ORD_NO)");
        assertThat(tableDesign.generateTableDdl(c.table(b), c.user, null).ddl()).contains("FOREIGN KEY (ORD_CUST_NO) REFERENCES CUST (CUST_NO)");
    }

    @Test
    @DisplayName("이미 매핑된 부모 : 다른 스키마면 참조 테이블명이 스키마명.테이블명, 기존 테이블은 바꾸지 않는다. 부모 PK 가 매핑 컬럼과 다르면 UNSUPPORTED, 매핑을 바꾸면 409")
    void existingParent() {
        Ctx c = ctx("fk-existing");
        SchemaInfo sales = tx.execute(s -> schemas.save(SchemaInfo.builder().projectId(c.user.getProjectId()).schemaName("SALES").isDefault(false).build()));
        long cust = c.entity("고객");
        long custNo = c.attr(cust, "고객번호", true, "CUST_NO");
        c.pk(cust, custNo);
        PhysicalGenerationDto.Plan parentPlan = generation.preview(c.user, new PhysicalGenerationDto.Request(List.of(cust), sales.getId(), null));
        generation.apply(c.user, new PhysicalGenerationDto.Request(List.of(cust), sales.getId(), parentPlan.planToken()));
        long custTable = c.table(cust);
        int custColumns = count("select count(*) from TB_COL where TBL_SN = ?", custTable);

        long ord = c.entity("주문");
        long ordNo = c.attr(ord, "주문번호", true, "ORD_NO");
        long ordCust = c.attr(ord, "고객번호", true, "ORD_CUST_NO");
        c.pk(ord, ordNo);
        c.pairs(c.rel("고객 주문", cust, ord, false, 1), new long[]{custNo, ordCust});
        PhysicalGenerationDto.Plan plan = generation.preview(c.user, c.request(List.of(cust, ord)));
        assertThat(plan.tables().get(0).status()).isEqualTo("SKIP");
        assertThat(plan.relations()).singleElement().satisfies(r -> {
            assertThat(r.fkStatus()).isEqualTo("READY");
            assertThat(r.parentTableId()).isEqualTo(custTable);
            assertThat(r.refTableName()).isEqualTo("SALES.CUST");
        });
        // 부모 매핑을 바꾸면(해제) 계획이 달라진다
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", custNo);
        assertThat(generation.preview(c.user, c.request(List.of(cust, ord))).relations().get(0).fkStatus()).isEqualTo("COLUMN_UNAVAILABLE");
        assertThatThrownBy(() -> generation.apply(c.user, c.request(List.of(cust, ord), plan.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        // 매핑을 되돌리고, 기존 부모 PK 를 매핑 컬럼과 다르게 → UNSUPPORTED (기존 테이블을 고치지 않는다)
        jdbc.update("insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN) select ?, m.ENTT_TBL_MPNG_SN, ?, c.COL_SN from TB_ENTT_TBL_MPNG m"
                + " join TB_COL c on c.TBL_SN = m.TBL_SN and c.COL_NM = 'CUST_NO' where m.LGCL_ENTT_SN = ?", IDS.incrementAndGet(), custNo, cust);
        jdbc.update("update TB_COL set PK_YN = false where TBL_SN = ?", custTable);
        assertThat(generation.preview(c.user, c.request(List.of(cust, ord))).relations().get(0)).satisfies(r -> {
            assertThat(r.fkStatus()).isEqualTo("UNSUPPORTED");
            assertThat(r.reasons()).first().isEqualTo("PARENT_KEY_NOT_TABLE_KEY");
        });
        jdbc.update("update TB_COL set PK_YN = true where TBL_SN = ? and COL_NM = 'CUST_NO'", custTable);
        PhysicalGenerationDto.Plan fresh = generation.preview(c.user, c.request(List.of(cust, ord)));
        PhysicalGenerationDto.Result result = generation.apply(c.user, c.request(List.of(cust, ord), fresh.planToken()));
        assertThat(result.tables()).isEqualTo(1);
        assertThat(count("select count(*) from TB_COL where TBL_SN = ?", custTable)).as("기존 부모 테이블은 그대로").isEqualTo(custColumns);
        assertThat(tableDesign.generateTableDdl(result.tableIds().get(0), c.user, null).ddl()).contains("FOREIGN KEY (ORD_CUST_NO) REFERENCES SALES.CUST (CUST_NO)");
    }

    @Test
    @DisplayName("만들 수 없는 관계 : 짝 없음·일부·부모 테이블 없음·자식 용어 없음(필수로 만들지 않음)·타입 불일치·같은 자식 속성(CONFLICT) — FK 없이 만들고 생성은 막지 않는다")
    void notReadyRelations() {
        Ctx c = ctx("fk-not-ready");
        long parent = c.entity("고객");
        long pNo = c.attr(parent, "고객번호", true, "CUST_NO");
        c.pk(parent, pNo);
        long orphan = c.entity("상품");
        long orphanNo = c.attr(orphan, "상품번호", true, "PRDT_NO");
        c.pk(orphan, orphanNo);
        long child = c.entity("주문");
        long cNo = c.attr(child, "주문번호", true, "ORD_NO");
        long cCust = c.attr(child, "고객번호", true, "ORD_CUST_NO");
        long cNum = c.attrTyped(child, "숫자고객번호", true, "ORD_CUST_NUM", "NUMERIC", 10);
        long cNoTerm = c.attrNoTerm(child, "임시고객번호");
        long cPrdt = c.attr(child, "상품번호", false, "ORD_PRDT_NO");
        c.pk(child, cNo);
        c.rel("짝 없음", parent, child, false, 0);
        c.pairs(c.rel("첫 관계", parent, child, false, 0), new long[]{pNo, cCust});
        c.pairs(c.rel("같은 자식 속성", parent, child, false, 0), new long[]{pNo, cCust});
        c.pairs(c.rel("타입", parent, child, false, 0), new long[]{pNo, cNum});
        c.pairs(c.rel("용어 없음", parent, child, false, 0), new long[]{pNo, cNoTerm});
        c.pairs(c.rel("부모 테이블 없음", orphan, child, false, 0), new long[]{orphanNo, cPrdt});

        PhysicalGenerationDto.Plan plan = generation.preview(c.user, new PhysicalGenerationDto.Request(List.of(parent, child), null, null, List.of(cNoTerm, cPrdt)));
        assertThat(plan.creatable()).as(plan.tables().toString()).isTrue();
        assertThat(plan.relations()).extracting(RelationPlan::relationshipName, RelationPlan::fkStatus, r -> r.reasons().get(0)).containsExactly(
                tuple("짝 없음", "NO_PAIRS", "ATTRIBUTE_PAIRS_MISSING"),
                tuple("첫 관계", "READY", "MULTIPLE_RELATIONSHIPS"),
                tuple("같은 자식 속성", "CONFLICT", "CHILD_COLUMN_IN_USE"),
                tuple("타입", "UNSUPPORTED", "TYPE_INCOMPATIBLE"),
                tuple("용어 없음", "COLUMN_UNAVAILABLE", "COLUMN_UNAVAILABLE"),
                tuple("부모 테이블 없음", "PARENT_TABLE_UNAVAILABLE", "PARENT_TABLE_MISSING"));
        // 만들 수 없는 관계의 자식 속성(용어 없음·부모 테이블 없음)은 뺄 수 있다
        assertThat(plan.tables().get(1).attributes()).filteredOn(a -> a.attributeId().equals(cNoTerm) || a.attributeId().equals(cPrdt))
                .allSatisfy(a -> { assertThat(a.foreignKey()).isFalse(); assertThat(a.excluded()).isTrue(); });
        PhysicalGenerationDto.Result result = generation.apply(c.user, new PhysicalGenerationDto.Request(List.of(parent, child), null, plan.planToken(), List.of(cNoTerm, cPrdt)));
        assertThat(result.foreignKeys()).isOne();
        assertThat(count("select count(*) from TB_COL where TBL_SN = ? and RFRNC_TBL_NM is not null", result.tableIds().get(1))).isOne();
    }

    @Test
    @DisplayName("P7 : 만들 FK 의 자식 속성은 빼달라고 해도 포함(FK 필수 표시·경고), P4·P5 경고는 그대로 보이고 PK·NULL 을 바꾸지 않는다, 짝을 바꾸면 409")
    void requiredForeignKeyAttributes() {
        Ctx c = ctx("fk-p7");
        long parent = c.entity("고객");
        long pNo = c.attr(parent, "고객번호", true, "CUST_NO");
        c.pk(parent, pNo);
        long child = c.entity("주문");
        long cNo = c.attr(child, "주문번호", true, "ORD_NO");
        long cCust = c.attr(child, "고객번호", false, "ORD_CUST_NO"); // 선택 속성 (P5)
        long cOther = c.attr(child, "다른고객번호", false, "OTHR_CUST_NO");
        c.pk(child, cNo);
        LogicalModelDto.Relationship rel = c.rel("식별", parent, child, true, 1); // 식별 + 필수 관계
        c.pairs(rel, new long[]{pNo, cCust});

        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(parent, child), null, null, List.of(cCust));
        PhysicalGenerationDto.Plan plan = generation.preview(c.user, request);
        PhysicalGenerationDto.TablePlan childPlan = plan.tables().get(1);
        assertThat(childPlan.attributes()).filteredOn(a -> a.attributeId().equals(cCust)).singleElement().satisfies(a -> {
            assertThat(a.foreignKey()).isTrue();
            assertThat(a.excluded()).isFalse();
            assertThat(a.excludeRequested()).isTrue();
        });
        assertThat(childPlan.warnings()).contains("FK 속성 고객번호 은(는) 제외할 수 없어 포함했습니다.");
        assertThat(plan.relations().get(0).pairWarnings()).contains("IDENTIFYING_CHILD_NOT_KEY", "REQUIRED_CHILD_NULLABLE");
        assertThat(childPlan.columns()).filteredOn(col -> col.attributeId().equals(cCust)).singleElement()
                .satisfies(col -> { assertThat(col.pk()).isFalse(); assertThat(col.nullable()).as("NULL 은 속성 기준").isTrue(); });
        // 정규화 : FK 속성 제외 요청과 요청하지 않은 계획은 같다
        assertThat(generation.preview(c.user, c.request(List.of(parent, child))).planToken()).isEqualTo(plan.planToken());

        // 짝 변경 → 409
        LogicalModelDto.RelationshipPairs current = logical.selectRelationshipPairs(c.user, rel.id());
        logical.saveRelationshipPairs(c.user, rel.id(), new PairSave(current.modifiedAt(), List.of(new PairInput(pNo, cOther))));
        assertThatThrownBy(() -> generation.apply(c.user, new PhysicalGenerationDto.Request(List.of(parent, child), null, plan.planToken(), List.of(cCust))))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        PhysicalGenerationDto.Plan changed = generation.preview(c.user, request);
        assertThat(changed.tables().get(1).attributes()).filteredOn(a -> a.attributeId().equals(cCust)).singleElement().satisfies(a -> assertThat(a.excluded()).isTrue());
        assertThat(generation.apply(c.user, new PhysicalGenerationDto.Request(List.of(parent, child), null, changed.planToken(), List.of(cCust))).foreignKeys()).isOne();
    }

    @Test
    @DisplayName("동시 반영 : 같은 FK 계획을 두 번 동시에 → 하나만 성공(다른 쪽 409), FK 관계는 한 벌")
    void concurrentApply() throws Exception {
        Ctx c = ctx("fk-race");
        long emp = c.entity("직원");
        long empNo = c.attr(emp, "직원번호", true, "EMP_NO");
        long upr = c.attr(emp, "상위직원번호", false, "UPR_EMP_NO");
        c.pk(emp, empNo);
        c.pairs(c.rel("상위", emp, emp, false, 0), new long[]{empNo, upr});
        PhysicalGenerationDto.Plan plan = generation.preview(c.user, c.request(List.of(emp)));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return generation.apply(SecurityTestSupport.loginAs(projectRepository, c.user.getProjectId(), c.user.getId(), "ROLE_ADMIN"), c.request(List.of(emp), plan.planToken()));
                } catch (Exception e) {
                    return e;
                } finally {
                    SecurityTestSupport.clear();
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (var f : futures) outcomes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();
        assertThat(outcomes).filteredOn(o -> o instanceof PhysicalGenerationDto.Result).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof CustomException e && e.getErrorCode() == ErrorCode.MODEL_CONFLICT).hasSize(1);
        assertThat(count("select count(*) from TB_TBL_REL where PRJ_SN = ?", c.user.getProjectId())).isOne();
    }

    // ------------------------------------------------------------------ 보조

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Ctx ctx(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        long p = project.getId();
        for (String[] w : new String[][]{{"고객", "CUST"}, {"주문", "ORD"}, {"직원", "EMP"}, {"부서", "DEPT"}, {"팀", "TEAM"}, {"공항", "ARPT"}, {"항공편", "FLT"},
                {"이력", "HSTRY"}, {"상품", "PRDT"}}) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", IDS.incrementAndGet(), p, w[0], w[1], w[1], w[0]);
        }
        LogicalModelDto.Model model = logical.saveModel(user, new LogicalModelDto.Model(null, name, null));
        return new Ctx(user, model.id());
    }

    private final class Ctx {
        final AccountDto user;
        final Long modelId;
        final Map<String, Long> domains = new HashMap<>();

        Ctx(AccountDto user, Long modelId) {
            this.user = user;
            this.modelId = modelId;
        }

        PhysicalGenerationDto.Request request(List<Long> entities) {
            return new PhysicalGenerationDto.Request(entities, null, null);
        }

        PhysicalGenerationDto.Request request(List<Long> entities, String token) {
            return new PhysicalGenerationDto.Request(entities, null, token);
        }

        long entity(String name) {
            return logical.saveEntity(user, new LogicalModelDto.Entity(null, modelId, name, null, null, null, null)).id();
        }

        long attr(long entity, String name, boolean required, String abbreviation) {
            return attrTyped(entity, name, required, abbreviation, "VARCHAR", 20);
        }

        long attrTyped(long entity, String name, boolean required, String abbreviation, String type, int length) {
            long domain = domains.computeIfAbsent(type + length, key -> {
                long id = IDS.incrementAndGet();
                jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                        + " values (?, ?, 1, '테스트', '테스트', ?, ?, ?, ?, null, true, current_timestamp, current_timestamp, 'test', 'test')", id, user.getProjectId(), key, key, type, length);
                return id;
            });
            long term = IDS.incrementAndGet();
            jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", term, user.getProjectId(), abbreviation, abbreviation, abbreviation, domain);
            return logical.saveAttribute(user, new LogicalModelDto.Attribute(null, entity, name, null, null, required, null, term, null, null, null)).id();
        }

        long attrNoTerm(long entity, String name) {
            return logical.saveAttribute(user, new LogicalModelDto.Attribute(null, entity, name, null, "문자", false, null, null, null, null, null)).id();
        }

        void pk(long entity, long... attributes) {
            List<Long> ids = new ArrayList<>();
            for (long a : attributes) ids.add(a);
            logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, entity, "식별자", true, ids, null));
        }

        LogicalModelDto.Relationship rel(String name, long parent, long child, boolean identifying, int sourceMin) {
            LogicalModelDto.Relationship saved = logical.saveRelationship(user, new LogicalModelDto.Relationship(null, modelId, name, null, parent, null, child, null,
                    identifying, sourceMin, 1, 0, null, null, null));
            jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", saved.id());
            return saved;
        }

        void pairs(LogicalModelDto.Relationship relationship, long[]... pairs) {
            List<PairInput> inputs = new ArrayList<>();
            for (long[] p : pairs) inputs.add(new PairInput(p[0], p[1]));
            logical.saveRelationshipPairs(user, relationship.id(), new PairSave(logical.selectRelationshipPairs(user, relationship.id()).modifiedAt(), inputs));
        }

        long table(long entity) {
            return entityMappings.findByEntity_Id(entity).orElseThrow().getTable().getId();
        }
    }
}
