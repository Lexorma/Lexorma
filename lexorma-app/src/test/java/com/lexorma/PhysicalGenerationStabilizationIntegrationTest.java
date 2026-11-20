package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.ddl.service.DdlExportService;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.service.ErdService;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.RelationPlan;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 논리 → 물리 생성 통합 안정화 (M-7 보강 5차) : 논리 관계 → 속성 짝 → 미리보기 → 테이블·컬럼 → 물리 FK → DDL(4개 DBMS, PostgreSQL 실제 실행) → ERD 가 한 줄로 맞는지,
 * 일반 단일 FK 가 다시 동기화·재조회·재시도해도 하나뿐인지, 다른 스키마의 같은 이름 테이블, 3단 순환·자기 참조가 참조 동기화 뒤에도 남는지, 생성 전 버전·스냅샷·교환 형식 3,
 * planToken 이 바뀌어야 할 때와 아닐 때, 다른 작업과 동시에 반영할 때 (H2·PostgreSQL 모두에서 실행 — 잠금 경쟁은 PostgreSQL 실행이 기준).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalGenerationStabilizationIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired ProjectRepository projectRepository;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalModelService physicalModel;
    @Autowired ModelMappingService mappingService;
    @Autowired EntityTableMappingRepository entityMappings;
    @Autowired com.lexorma.data.mapping.repository.AttributeColumnMappingRepository attributeMappings;
    @Autowired SchemaInfoRepository schemas;
    @Autowired TableDesignService tableDesign;
    @Autowired DdlExportService exportService;
    @Autowired ErdService erd;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("일반 단일 FK 는 논리 관계 하나에 물리 FK 하나 : 참조 동기화·테이블 상세·ERD·DDL 재생성·같은 요청 재시도 뒤에도 늘지 않고, 같은 부모 관계 둘은 둘로 남는다")
    void singleForeignKeyIsNotDuplicated() {
        GenerationFixture f = fixture("stab-dup");
        long cust = f.entity("고객");
        long custNo = f.attr(cust, "고객번호", true, "CUST_NO");
        f.pk(cust, custNo);
        long ord = f.entity("주문");
        long ordNo = f.attr(ord, "주문번호", true, "ORD_NO");
        long buyer = f.attr(ord, "구매고객번호", true, "BUYER_CUST_NO");
        long payer = f.attr(ord, "결제고객번호", false, "PAYER_CUST_NO");
        f.pk(ord, ordNo);
        f.pairs(f.rel("구매", cust, ord, false, 1), new long[]{custNo, buyer});
        f.pairs(f.rel("결제", cust, ord, false, 0), new long[]{custNo, payer});

        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(cust, ord)));
        PhysicalGenerationDto.Result result = generation.apply(f.user, f.request(List.of(cust, ord), plan.planToken()));
        assertThat(result.foreignKeys()).isEqualTo(2).isEqualTo(f.foreignKeys());
        long custTable = f.table(cust);
        long ordTable = f.table(ord);

        tx.executeWithoutResult(s -> physicalModel.syncReferenceRelations(f.user.getProjectId(), List.of(ordTable, custTable)));
        tx.executeWithoutResult(s -> physicalModel.syncReferenceRelations(f.user.getProjectId(), List.of(ordTable)));
        tableDesign.selectTable(ordTable, f.user);
        ErdDto.Graph graph = erd.selectGraph(f.user, new ErdDto.Search(null, null, null, null), 0, 100);
        String ddl1 = tableDesign.generateTableDdl(ordTable, f.user, null).ddl();
        String ddl2 = tableDesign.generateTableDdl(ordTable, f.user, null).ddl();
        assertThat(f.foreignKeys()).isEqualTo(2);
        assertThat(ddl1).isEqualTo(ddl2);
        assertThat(occurrences(ddl1, "FOREIGN KEY (")).isEqualTo(2);
        assertThat(graph.relations()).filteredOn(r -> r.sourceTableId().equals(ordTable))
                .extracting(PhysicalModelDto.Relation::relationName, r -> r.columns().get(0).sourceColumnName(), PhysicalModelDto.Relation::targetTableId)
                .containsExactlyInAnyOrder(tuple("FK_ORD_BUYER_CUST_NO", "BUYER_CUST_NO", custTable), tuple("FK_ORD_PAYER_CUST_NO", "PAYER_CUST_NO", custTable));

        // 같은 요청 재시도 : 엔터티가 이미 매핑되어 계획이 달라졌다 → 409, 새 미리보기는 모두 건너뜀 → 만들 것 없음 400, 버전도 그대로
        int versionsBefore = f.versions();
        assertThatThrownBy(() -> generation.apply(f.user, f.request(List.of(cust, ord), plan.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        PhysicalGenerationDto.Plan again = generation.preview(f.user, f.request(List.of(cust, ord)));
        assertThat(again.creatable()).isFalse();
        assertThat(again.relations()).isEmpty();
        assertThatThrownBy(() -> generation.apply(f.user, f.request(List.of(cust, ord), again.planToken()))).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(f.foreignKeys()).isEqualTo(2);
        assertThat(f.versions()).isEqualTo(versionsBefore);
    }

    @Test
    @DisplayName("다른 스키마의 같은 이름 테이블 : SB.CUST 를 참조하는 SC.ORD — 계획·저장된 부모 ID·참조 테이블명·관계 대상·DDL·ERD 가 모두 SB.CUST (SA.CUST 가 아님)")
    void sameNameTablesInOtherSchemas() {
        GenerationFixture f = fixture("stab-schema");
        SchemaInfo sa = schema(f, "SA");
        SchemaInfo sb = schema(f, "SB");
        SchemaInfo sc = schema(f, "SC");
        GenerationFixture other = f.otherModel("다른 모델");
        long decoy = other.entity("고객");
        other.pk(decoy, other.attr(decoy, "고객번호", true, "CUST_NO"));
        PhysicalGenerationDto.Plan decoyPlan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(decoy), sa.getId(), null));
        generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(decoy), sa.getId(), decoyPlan.planToken()));

        long cust = f.entity("고객");
        long custNo = f.attr(cust, "고객번호", true, "CUST_NO");
        f.pk(cust, custNo);
        PhysicalGenerationDto.Plan parentPlan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(cust), sb.getId(), null));
        generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(cust), sb.getId(), parentPlan.planToken()));
        long saCust = f.table(decoy);
        long sbCust = f.table(cust);
        assertThat(saCust).isNotEqualTo(sbCust);

        long ord = f.entity("주문");
        long ordNo = f.attr(ord, "주문번호", true, "ORD_NO");
        long ordCust = f.attr(ord, "주문고객번호", true, "ORD_CUST_NO");
        f.pk(ord, ordNo);
        f.pairs(f.rel("고객 주문", cust, ord, false, 1), new long[]{custNo, ordCust});
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(ord), sc.getId(), null));
        RelationPlan relation = plan.relations().get(0);
        assertThat(relation).extracting(RelationPlan::fkStatus, RelationPlan::parentTableId, RelationPlan::parentTableName, RelationPlan::refTableName)
                .containsExactly("READY", sbCust, "CUST", "SB.CUST");
        generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(ord), sc.getId(), plan.planToken()));
        long scOrd = f.table(ord);

        assertThat(jdbc.queryForObject("select TRGT_TBL_SN from TB_TBL_REL where ORGNL_TBL_SN = ?", Long.class, scOrd)).isEqualTo(sbCust);
        assertThat(jdbc.queryForObject("select RFRNC_TBL_NM from TB_COL where TBL_SN = ? and COL_NM = 'ORD_CUST_NO'", String.class, scOrd)).isEqualTo("SB.CUST");
        assertThat(tableDesign.generateTableDdl(scOrd, f.user, null).ddl()).contains("REFERENCES SB.CUST (CUST_NO)").doesNotContain("REFERENCES SA.CUST");
        assertThat(erd.selectGraph(f.user, new ErdDto.Search(null, null, null, null), 0, 100).relations())
                .filteredOn(r -> r.sourceTableId().equals(scOrd)).singleElement().extracting(PhysicalModelDto.Relation::targetTableId).isEqualTo(sbCust);
        // 다시 동기화해도 같은 대상
        tx.executeWithoutResult(s -> physicalModel.syncReferenceRelations(f.user.getProjectId(), List.of(scOrd, saCust, sbCust)));
        assertThat(jdbc.queryForList("select TRGT_TBL_SN from TB_TBL_REL where ORGNL_TBL_SN = ?", Long.class, scOrd)).containsExactly(sbCust);
    }

    @Test
    @DisplayName("3단 순환 A → B → C → A, 같은 부모·자식 사이 관계 둘, 자기 참조 단일·복합 : 한 요청에 만들고 참조 동기화 뒤에도 자기 참조 FK 가 남는다. 결과 FK 수 = 저장된 FK 수")
    void cyclesAndSelfReferencesSurviveSync() {
        GenerationFixture f = fixture("stab-cycle");
        long a = f.entity("고객");
        long aNo = f.attr(a, "고객번호", true, "CUST_NO");
        long aBrnch = f.attr(a, "주거래지점번호", false, "MAIN_BRNCH_NO");
        long aUpr = f.attr(a, "추천고객번호", false, "RCMD_CUST_NO");
        f.pk(a, aNo);
        long b = f.entity("주문");
        long bNo = f.attr(b, "주문번호", true, "ORD_NO");
        long bCust = f.attr(b, "주문고객번호", true, "ORD_CUST_NO");
        long bCust2 = f.attr(b, "수령고객번호", false, "RCPT_CUST_NO");
        f.pk(b, bNo);
        long c = f.entity("지점");
        long cCo = f.attr(c, "회사코드", true, "CO_CD");
        long cNo = f.attr(c, "지점번호", true, "BRNCH_NO");
        long cOrd = f.attr(c, "대표주문번호", false, "REP_ORD_NO");
        long cUprCo = f.attr(c, "상위회사코드", false, "UPR_CO_CD");
        long cUprNo = f.attr(c, "상위지점번호", false, "UPR_BRNCH_NO");
        long aCo = f.attr(a, "주거래회사코드", false, "MAIN_CO_CD");
        f.pk(c, cCo, cNo);
        f.pairs(f.rel("주문 고객", a, b, false, 1), new long[]{aNo, bCust});         // A → B
        f.pairs(f.rel("수령 고객", a, b, false, 0), new long[]{aNo, bCust2});        // A → B 두 번째
        f.pairs(f.rel("대표 주문", b, c, false, 0), new long[]{bNo, cOrd});          // B → C
        f.pairs(f.rel("주거래 지점", c, a, false, 0), new long[]{cCo, aCo}, new long[]{cNo, aBrnch}); // C → A (복합)
        f.pairs(f.rel("추천", a, a, false, 0), new long[]{aNo, aUpr});              // 자기 참조 단일
        f.pairs(f.rel("상위 지점", c, c, false, 0), new long[]{cCo, cUprCo}, new long[]{cNo, cUprNo}); // 자기 참조 복합

        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(a, b, c)));
        assertThat(plan.relations()).extracting(RelationPlan::relationshipName, RelationPlan::fkStatus, RelationPlan::fkType).containsExactlyInAnyOrder(
                tuple("주문 고객", "READY", "SINGLE"), tuple("수령 고객", "READY", "SINGLE"), tuple("대표 주문", "READY", "SINGLE"),
                tuple("주거래 지점", "READY", "COMPOSITE"), tuple("추천", "READY", "SELF_SINGLE"), tuple("상위 지점", "READY", "SELF_COMPOSITE"));
        PhysicalGenerationDto.Result result = generation.apply(f.user, f.request(List.of(a, b, c), plan.planToken()));
        assertThat(result.foreignKeys()).isEqualTo(6);
        assertThat(f.foreignKeys()).as(String.valueOf(jdbc.queryForList("select r.REL_NM, r.REL_SRC_CD, o.TBL_NM, t.TBL_NM from TB_TBL_REL r join TB_TBL o on o.TBL_SN = r.ORGNL_TBL_SN"
                + " join TB_TBL t on t.TBL_SN = r.TRGT_TBL_SN where r.PRJ_SN = ?", f.user.getProjectId()))).isEqualTo(6);

        long ta = f.table(a);
        long tb = f.table(b);
        long tc = f.table(c);
        tx.executeWithoutResult(s -> physicalModel.syncReferenceRelations(f.user.getProjectId(), List.of(ta, tb, tc)));
        assertThat(f.foreignKeys()).as(String.valueOf(jdbc.queryForList("select r.REL_NM, r.REL_SRC_CD, o.TBL_NM, t.TBL_NM from TB_TBL_REL r join TB_TBL o on o.TBL_SN = r.ORGNL_TBL_SN"
                + " join TB_TBL t on t.TBL_SN = r.TRGT_TBL_SN where r.PRJ_SN = ?", f.user.getProjectId()))).isEqualTo(6);
        assertThat(f.count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = TRGT_TBL_SN and ORGNL_TBL_SN in (?, ?)", ta, tc)).isEqualTo(2);
        assertStoredMatchesPlan(f, plan);
        String all = exportService.export(f.user, null, null, null, false).ddl();
        assertThat(occurrences(all, "FOREIGN KEY (")).as(all).isEqualTo(6);
        assertThat(all).contains("FOREIGN KEY (MAIN_CO_CD, MAIN_BRNCH_NO) REFERENCES BRNCH (CO_CD, BRNCH_NO)")
                .contains("FOREIGN KEY (UPR_CO_CD, UPR_BRNCH_NO) REFERENCES BRNCH (CO_CD, BRNCH_NO)")
                .contains("FOREIGN KEY (RCMD_CUST_NO) REFERENCES CUST (CUST_NO)")
                .contains("FOREIGN KEY (REP_ORD_NO) REFERENCES ORD (ORD_NO)");
    }

    @Test
    @DisplayName("DDL 4개 DBMS : FK·참조 컬럼 순서와 제약조건명이 계획과 같고(Oracle 은 30자 이내), PostgreSQL 은 격리 스키마에서 실제로 실행되어 같은 FK 가 생긴다. P3 PK 순서 = 식별자 순서")
    void ddlForEveryDbms() {
        GenerationFixture f = fixture("stab-ddl");
        long dept = f.entity("부서");
        long deptNo = f.attr(dept, "부서번호", true, "DEPT_NO");     // 속성 순서는 부서번호 먼저
        long co = f.attr(dept, "회사코드", true, "CO_CD");
        long uprCo = f.attr(dept, "상위회사코드", false, "UPR_CO_CD");
        long uprNo = f.attr(dept, "상위부서번호", false, "UPR_DEPT_NO");
        f.pk(dept, co, deptNo);                                         // PK 순서는 회사코드 먼저 (P3)
        f.pairs(f.rel("상위 부서", dept, dept, false, 0), new long[]{co, uprCo}, new long[]{deptNo, uprNo});
        long emp = f.entity("직원");
        long empNo = f.attr(emp, "직원번호", true, "EMP_NO");
        long empCo = f.attr(emp, "소속회사코드", true, "BLNG_CO_CD");
        long empDept = f.attr(emp, "소속부서번호", true, "BLNG_DEPT_NO");
        long mgr = f.attr(emp, "관리직원번호", false, "MNGR_EMP_NO");
        f.pk(emp, empNo);
        f.pairs(f.rel("소속", dept, emp, false, 1), new long[]{deptNo, empDept}, new long[]{co, empCo}); // 부모 PK 순서와 반대 (ORDER_DIFFERS)
        f.pairs(f.rel("관리자", emp, emp, false, 0), new long[]{empNo, mgr});

        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(dept, emp)));
        assertThat(plan.tables().get(0).columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).startsWith("CO_CD", "DEPT_NO");
        generation.apply(f.user, f.request(List.of(dept, emp), plan.planToken()));
        assertStoredMatchesPlan(f, plan);
        assertThat(tableDesign.generateTableDdl(f.table(dept), f.user, null).ddl()).contains("PRIMARY KEY (CO_CD, DEPT_NO)");

        for (String db : List.of("POSTGRESQL", "ORACLE", "MYSQL", "MARIADB")) {
            String ddl = exportService.export(f.user, null, db, null, false).ddl();
            assertThat(ddl).as(db).containsPattern(fk("FK_EMP_BLNG_DEPT_NO", "BLNG_DEPT_NO, BLNG_CO_CD", "DEPT", "DEPT_NO, CO_CD"))
                    .containsPattern(fk("FK_DEPT_UPR_CO_CD", "UPR_CO_CD, UPR_DEPT_NO", "DEPT", "CO_CD, DEPT_NO"))
                    .containsPattern(fk("FK_EMP_MNGR_EMP_NO", "MNGR_EMP_NO", "EMP", "EMP_NO"));
            if ("ORACLE".equals(db)) {
                java.util.regex.Matcher m = Pattern.compile("CONSTRAINT (\\S+)").matcher(ddl);
                while (m.find()) assertThat(m.group(1).length()).as(m.group(1)).isLessThanOrEqualTo(30);
            }
        }

        String url = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(url != null, "PostgreSQL 검증 DB 미지정 — 실제 실행은 건너뜀");
        JdbcTemplate pg = new JdbcTemplate(new DriverManagerDataSource(url, "lexorma", "lexorma"));
        String target = "gen_ddl_" + UUID.randomUUID().toString().replace("-", "");
        pg.execute("create schema " + target);
        try {
            pg.execute(exportService.export(f.user, null, "POSTGRESQL", target, false).ddl());
            List<String> fks = pg.queryForList("""
                    select c.conname || ':' || (select string_agg(a.attname, ',' order by k.n) from unnest(c.conkey) with ordinality k(attnum, n)
                                                  join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum)
                         || '>' || (select string_agg(a.attname, ',' order by k.n) from unnest(c.confkey) with ordinality k(attnum, n)
                                                  join pg_attribute a on a.attrelid = c.confrelid and a.attnum = k.attnum)
                      from pg_constraint c join pg_namespace s on s.oid = c.connamespace
                     where s.nspname = ? and c.contype = 'f' order by 1""", String.class, target);
            assertThat(fks).containsExactly("fk_dept_upr_co_cd:upr_co_cd,upr_dept_no>co_cd,dept_no", "fk_emp_blng_dept_no:blng_dept_no,blng_co_cd>dept_no,co_cd",
                    "fk_emp_mngr_emp_no:mngr_emp_no>emp_no");
        } finally {
            pg.execute("drop schema " + target + " cascade");
        }
    }

    @Test
    @DisplayName("생성 전 모델 버전 : 미리보기·400·409 에는 없고, 성공한 반영 1번에 정확히 1개. 버전 스냅샷(형식 6)에 짝 순서·매핑이 있다")
    void versionOnlyOnSuccessfulApply() {
        GenerationFixture f = fixture("stab-version");
        long cust = f.entity("고객");
        long custNo = f.attr(cust, "고객번호", true, "CUST_NO");
        f.pk(cust, custNo);
        long ord = f.entity("주문");
        long ordNo = f.attr(ord, "주문번호", true, "ORD_NO");
        long noTerm = f.attrNoTerm(ord, "메모");
        long ordCust = f.attr(ord, "주문고객번호", true, "ORD_CUST_NO");
        f.pk(ord, ordNo);
        LogicalModelDto.Relationship rel = f.rel("고객 주문", cust, ord, false, 1);
        f.pairs(rel, new long[]{custNo, ordCust});

        PhysicalGenerationDto.Plan withError = generation.preview(f.user, f.request(List.of(cust, ord)));
        assertThat(withError.creatable()).isFalse();
        assertThat(f.versions()).isZero();
        assertThatThrownBy(() -> generation.apply(f.user, f.request(List.of(cust, ord), withError.planToken()))).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        PhysicalGenerationDto.Request request = new PhysicalGenerationDto.Request(List.of(cust, ord), null, null, List.of(noTerm));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, request);
        assertThatThrownBy(() -> generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(cust, ord), null, "x" + plan.planToken(), List.of(noTerm))))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(f.versions()).isZero();
        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(cust, ord), null, plan.planToken(), List.of(noTerm)));
        assertThat(f.versions()).isOne();
        assertThat(jdbc.queryForObject("select VER_NM from TB_MDL_VER where PRJ_SN = ?", String.class, f.user.getProjectId())).isEqualTo(result.versionName()).startsWith("GENERATE-");

        // 생성 전 버전에는 매핑이 없고, 생성 뒤 사용자 버전에는 매핑·짝(순서)이 있다
        JsonNode before = versions.snapshot(f.user, result.versionId()).objects();
        assertThat(before.has("TABLE_MAPPING") && !before.get("TABLE_MAPPING").isEmpty()).isFalse();
        AccountDto member = f.member();
        ModelVersionDto.Summary after = versions.create(member, new ModelVersionDto.Create("생성 후", null));
        JsonNode objects = versions.snapshot(member, after.id()).objects();
        assertThat(objects.get("TABLE_MAPPING")).hasSize(2);
        assertThat(objects.get("COLUMN_MAPPING")).as("제외한 메모는 매핑 없음").hasSize(3);
        JsonNode relationship = null;
        for (JsonNode o : objects.get("LOGICAL_RELATIONSHIP")) if (o.get("id").asLong() == rel.id()) relationship = o;
        assertThat(relationship).isNotNull();
        assertThat(relationship.toString()).contains("\"sortOrder\":1").contains(String.valueOf(ordCust));
        assertThat(jdbc.queryForObject("select FRMT_VER_NO from TB_MDL_VER where MDL_VER_SN = ?", Integer.class, after.id())).isEqualTo(8);
    }

    @Test
    @DisplayName("P7 정규화 : PK 이면서 FK 인 속성·복합 FK 의 일부 제외 요청은 무시하고 포함(미리보기 = 토큰 = 생성), 용어 없는 일반 속성은 빼야 만들 수 있다")
    void requiredAttributesAreNormalized() {
        GenerationFixture f = fixture("stab-p7");
        long dept = f.entity("부서");
        long co = f.attr(dept, "회사코드", true, "CO_CD");
        long deptNo = f.attr(dept, "부서번호", true, "DEPT_NO");
        f.pk(dept, co, deptNo);
        long team = f.entity("팀");
        long teamCo = f.attr(team, "팀회사코드", true, "TEAM_CO_CD");
        long teamDept = f.attr(team, "팀부서번호", true, "TEAM_DEPT_NO");
        long teamNo = f.attr(team, "팀번호", true, "TEAM_NO");
        long memo = f.attrNoTerm(team, "메모");
        f.pk(team, teamCo, teamDept, teamNo);
        f.pairs(f.rel("부서 팀", dept, team, true, 1), new long[]{co, teamCo}, new long[]{deptNo, teamDept});

        PhysicalGenerationDto.Request asked = new PhysicalGenerationDto.Request(List.of(dept, team), null, null, List.of(teamCo, teamDept, memo));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, asked);
        assertThat(plan.creatable()).isTrue();
        assertThat(plan.tables().get(1).attributes()).extracting(PhysicalGenerationDto.AttributeChoice::attributeId, PhysicalGenerationDto.AttributeChoice::primary,
                PhysicalGenerationDto.AttributeChoice::foreignKey, PhysicalGenerationDto.AttributeChoice::excluded).containsExactlyInAnyOrder(
                tuple(teamCo, true, true, false), tuple(teamDept, true, true, false), tuple(teamNo, true, false, false), tuple(memo, false, false, true));
        assertThat(generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(dept, team), null, null, List.of(memo))).planToken()).isEqualTo(plan.planToken());
        assertThat(generation.preview(f.user, f.request(List.of(dept, team))).creatable()).as("용어 없는 속성을 넣으면 오류").isFalse();
        PhysicalGenerationDto.Result result = generation.apply(f.user, new PhysicalGenerationDto.Request(List.of(dept, team), null, plan.planToken(), List.of(teamCo, teamDept, memo)));
        assertThat(result.columns()).isEqualTo(5);
        assertThat(result.foreignKeys()).isOne();
        assertStoredMatchesPlan(f, plan);
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and PK_YN = true order by SORT_ORD", String.class, f.table(team)))
                .containsExactly("TEAM_CO_CD", "TEAM_DEPT_NO", "TEAM_NO");
    }

    @Test
    @DisplayName("planToken v5 : 속성 용어·주 식별자·짝 순서·제외 속성·대상 엔터티가 바뀌면 다르고, 관계 설명·이름만 바꾸면 같다")
    void planTokenSensitivity() {
        GenerationFixture f = fixture("stab-token");
        long dept = f.entity("부서");
        long co = f.attr(dept, "회사코드", true, "CO_CD");
        long deptNo = f.attr(dept, "부서번호", true, "DEPT_NO");
        f.pk(dept, co, deptNo);
        long emp = f.entity("직원");
        long empNo = f.attr(emp, "직원번호", true, "EMP_NO");
        long empCo = f.attr(emp, "소속회사코드", true, "BLNG_CO_CD");
        long empDept = f.attr(emp, "소속부서번호", true, "BLNG_DEPT_NO");
        long memo = f.attr(emp, "비고", false, "RMRK");
        f.pk(emp, empNo);
        LogicalModelDto.Relationship rel = f.rel("소속", dept, emp, false, 1);
        f.pairs(rel, new long[]{co, empCo}, new long[]{deptNo, empDept});
        PhysicalGenerationDto.Request request = f.request(List.of(dept, emp));
        String base = generation.preview(f.user, request).planToken();

        // 표시만 바뀌는 변경 : 관계 설명·이름
        LogicalModelDto.Relationship current = logical.selectRelationships(f.user, f.modelId).stream().filter(r -> r.id().equals(rel.id())).findFirst().orElseThrow();
        logical.saveRelationship(f.user, new LogicalModelDto.Relationship(current.id(), current.modelId(), "소속(이름 변경)", "설명 추가", current.sourceEntityId(), null,
                current.targetEntityId(), null, current.isIdentifying(), current.sourceMin(), current.sourceMax(), current.targetMin(), current.targetMax(), null, null,
                current.modifiedAt(), null));
        assertThat(generation.preview(f.user, request).planToken()).isEqualTo(base);

        // 제외 속성·대상 엔터티
        assertThat(generation.preview(f.user, new PhysicalGenerationDto.Request(List.of(dept, emp), null, null, List.of(memo))).planToken()).isNotEqualTo(base);
        assertThat(generation.preview(f.user, f.request(List.of(emp))).planToken()).isNotEqualTo(base);

        // 짝 순서
        f.pairs(rel, new long[]{deptNo, empDept}, new long[]{co, empCo});
        String reordered = generation.preview(f.user, request).planToken();
        assertThat(reordered).isNotEqualTo(base);

        // 자식 FK 속성의 용어(→ 컬럼명·제약조건명)
        LogicalModelDto.Attribute attribute = logical.selectAttributes(f.user, emp).stream().filter(a -> a.id().equals(empDept)).findFirst().orElseThrow();
        logical.saveAttribute(f.user, new LogicalModelDto.Attribute(attribute.id(), emp, attribute.attributeName(), null, null, true, attribute.sortOrder(),
                f.term("ORG_DEPT_NO", "VARCHAR", 20), null, null, null, attribute.modifiedAt()));
        PhysicalGenerationDto.Plan renamed = generation.preview(f.user, request);
        assertThat(renamed.planToken()).isNotEqualTo(reordered);
        assertThat(renamed.relations().get(0).constraintName()).isEqualTo("FK_EMP_ORG_DEPT_NO");

        // 부모 주 식별자 (속성 하나를 빼면 짝이 식별자와 맞지 않는다)
        LogicalModelDto.Identifier identifier = logical.selectIdentifiers(f.user, dept).get(0);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifier.id(), dept, identifier.identifierName(), true, List.of(co), null, identifier.modifiedAt()));
        PhysicalGenerationDto.Plan mismatch = generation.preview(f.user, request);
        assertThat(mismatch.planToken()).isNotEqualTo(renamed.planToken());
        assertThat(mismatch.relations().get(0).fkStatus()).isEqualTo("IDENTIFIER_MISMATCH");
        assertThatThrownBy(() -> generation.apply(f.user, f.request(List.of(dept, emp), renamed.planToken())))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(f.count("select count(*) from TB_TBL where PRJ_SN = ?", f.user.getProjectId())).isZero();
    }

    @Test
    @DisplayName("교환 형식 3 → 대상 프로젝트에서 생성 : 자기 참조 복합·같은 부모 관계 둘·짝 순서가 새 속성 번호로 이어지고, 그 짝으로 같은 FK 가 생긴다")
    void exchangedModelGeneratesSameForeignKeys() {
        GenerationFixture f = fixture("stab-exchange-src");
        long dept = f.entity("부서");
        long co = f.attr(dept, "회사코드", true, "CO_CD");
        long deptNo = f.attr(dept, "부서번호", true, "DEPT_NO");
        long uprCo = f.attr(dept, "상위회사코드", false, "UPR_CO_CD");
        long uprNo = f.attr(dept, "상위부서번호", false, "UPR_DEPT_NO");
        f.pk(dept, co, deptNo);
        f.pairs(f.rel("상위 부서", dept, dept, false, 0), new long[]{deptNo, uprNo}, new long[]{co, uprCo});
        long emp = f.entity("직원");
        long empNo = f.attr(emp, "직원번호", true, "EMP_NO");
        long aCo = f.attr(emp, "소속회사코드", true, "BLNG_CO_CD");
        long aNo = f.attr(emp, "소속부서번호", true, "BLNG_DEPT_NO");
        long bCo = f.attr(emp, "겸직회사코드", false, "ADDL_CO_CD");
        long bNo = f.attr(emp, "겸직부서번호", false, "ADDL_DEPT_NO");
        f.pk(emp, empNo);
        f.pairs(f.rel("소속", dept, emp, false, 1), new long[]{co, aCo}, new long[]{deptNo, aNo});
        f.pairs(f.rel("겸직", dept, emp, false, 0), new long[]{co, bCo}, new long[]{deptNo, bNo});
        PhysicalGenerationDto.Plan sourcePlan = generation.preview(f.user, f.request(List.of(dept, emp)));

        ModelExchangeDto.Document document = exchange.export(f.user);
        assertThat(document.formatVersion()).isEqualTo(5); // 형식 5 (M-6 후속 5차) 에도 같다
        GenerationFixture t = fixture("stab-exchange-dst");
        AccountDto target = t.member();
        // 교환은 표준 용어를 이름으로만 잇는다 — 대상 프로젝트에 같은 이름의 도메인·용어를 먼저 둔다 (없으면 컬럼을 정할 수 없어 COLUMN_UNAVAILABLE)
        copyDictionary(f.user.getProjectId(), t.user.getProjectId());
        ModelExchangeDto.Plan importPlan = exchange.plan(target, new ModelExchangeDto.PlanRequest(t.user.getProjectId(), document, null));
        assertThat(importPlan.applicable()).as(importPlan.toString()).isTrue();
        exchange.apply(target, new ModelExchangeDto.ApplyRequest(t.user.getProjectId(), document, null, importPlan.planToken(), importPlan.requestId()));

        List<LogicalModelDto.Model> models = logical.selectModels(target);
        Long importedModel = models.stream().filter(m -> "stab-exchange-src".equals(m.modelName())).findFirst().orElseThrow().id();
        List<LogicalModelDto.Entity> entities = logical.selectEntities(target, importedModel);
        Long newDept = entities.stream().filter(e -> "부서".equals(e.entityName())).findFirst().orElseThrow().id();
        Long newEmp = entities.stream().filter(e -> "직원".equals(e.entityName())).findFirst().orElseThrow().id();
        assertThat(newDept).isNotEqualTo(dept);
        PhysicalGenerationDto.Plan targetPlan = generation.preview(target, new PhysicalGenerationDto.Request(List.of(newDept, newEmp), null, null));
        assertThat(targetPlan.relations()).extracting(RelationPlan::relationshipName, RelationPlan::fkStatus, RelationPlan::fkType, RelationPlan::constraintName,
                r -> r.columns().stream().map(c -> c.parentColumnName() + ">" + c.childColumnName()).toList())
                .containsExactlyInAnyOrderElementsOf(sourcePlan.relations().stream().map(r -> tuple(r.relationshipName(), r.fkStatus(), r.fkType(), r.constraintName(),
                        r.columns().stream().map(c -> c.parentColumnName() + ">" + c.childColumnName()).toList())).toList());
        assertThat(targetPlan.relations()).filteredOn(r -> "상위 부서".equals(r.relationshipName())).singleElement()
                .satisfies(r -> assertThat(r.columns()).extracting(PhysicalGenerationDto.FkColumn::childColumnName).containsExactly("UPR_DEPT_NO", "UPR_CO_CD"));
        PhysicalGenerationDto.Result result = generation.apply(target, new PhysicalGenerationDto.Request(List.of(newDept, newEmp), null, targetPlan.planToken()));
        assertThat(result.foreignKeys()).isEqualTo(3).isEqualTo(t.foreignKeys());
        assertThat(f.foreignKeys()).as("원본 프로젝트는 그대로").isZero();
    }

    @Test
    @DisplayName("프로젝트 격리 : 다른 프로젝트 사용자가 이 엔터티로 미리보기·반영하면 거절되고, 아무것도 만들어지지 않는다")
    void otherProjectIsRejected() {
        GenerationFixture f = fixture("stab-iso");
        long cust = f.entity("고객");
        f.pk(cust, f.attr(cust, "고객번호", true, "CUST_NO"));
        String token = generation.preview(f.user, f.request(List.of(cust))).planToken();
        GenerationFixture other = fixture("stab-iso-other");
        assertThatThrownBy(() -> generation.preview(other.user, other.request(List.of(cust)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> generation.apply(other.user, other.request(List.of(cust), token))).isInstanceOf(CustomException.class);
        assertThat(f.count("select count(*) from TB_TBL where PRJ_SN in (?, ?)", f.user.getProjectId(), other.user.getProjectId())).isZero();
        assertThat(other.versions() + f.versions()).isZero();
    }

    // ------------------------------------------------------------------ 동시성 (PostgreSQL 실행이 기준, H2 에서도 같은 불변식을 본다)

    @Test
    @DisplayName("동시 : 같은 모델의 서로 다른 엔터티 생성 둘 — 둘 다 성공하고(프로젝트 행에서 줄 선다) 각자 버전 1개, FK 는 각자 계획대로")
    void concurrentDifferentEntities() throws Exception {
        GenerationFixture f = fixture("stab-race-entities");
        long a = f.entity("고객");
        f.pk(a, f.attr(a, "고객번호", true, "CUST_NO"));
        long b = f.entity("상품");
        long bNo = f.attr(b, "상품번호", true, "PRDT_NO");
        long bUpr = f.attr(b, "상위상품번호", false, "UPR_PRDT_NO");
        f.pk(b, bNo);
        f.pairs(f.rel("상위 상품", b, b, false, 0), new long[]{bNo, bUpr});
        String ta = generation.preview(f.user, f.request(List.of(a))).planToken();
        String tb = generation.preview(f.user, f.request(List.of(b))).planToken();
        List<Object> outcomes = race(f, () -> generation.apply(login(f), f.request(List.of(a), ta)), () -> generation.apply(login(f), f.request(List.of(b), tb)));
        assertThat(outcomes).allMatch(o -> o instanceof PhysicalGenerationDto.Result);
        assertThat(f.versions()).isEqualTo(2);
        assertThat(f.foreignKeys()).isOne();
    }

    @Test
    @DisplayName("동시 : 생성 ↔ 같은 관계의 짝 수정 — 교착 없이 끝나고, 생성이 성공했다면 저장된 FK 는 미리보기 계획 그대로, 실패라면 409 이고 아무것도 없다")
    void concurrentWithPairSave() throws Exception {
        GenerationFixture f = fixture("stab-race-pairs");
        long emp = f.entity("직원");
        long empNo = f.attr(emp, "직원번호", true, "EMP_NO");
        long upr = f.attr(emp, "상위직원번호", false, "UPR_EMP_NO");
        long other = f.attr(emp, "멘토직원번호", false, "MNTR_EMP_NO");
        f.pk(emp, empNo);
        LogicalModelDto.Relationship rel = f.rel("상위", emp, emp, false, 0);
        f.pairs(rel, new long[]{empNo, upr});
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(emp)));
        List<Object> outcomes = race(f, () -> generation.apply(login(f), f.request(List.of(emp), plan.planToken())), () -> {
            AccountDto user = login(f);
            logical.saveRelationshipPairs(user, rel.id(), new LogicalModelDto.PairSave(logical.selectRelationshipPairs(user, rel.id()).modifiedAt(),
                    List.of(new LogicalModelDto.PairInput(empNo, other))));
            return "pairs";
        });
        assertOnlyConflicts(outcomes);
        if (outcomes.get(0) instanceof PhysicalGenerationDto.Result) {
            assertStoredMatchesPlan(f, plan);
            assertThat(f.versions()).isOne();
        } else {
            assertThat(f.count("select count(*) from TB_TBL where PRJ_SN = ?", f.user.getProjectId())).isZero();
            assertThat(f.versions()).isZero();
        }
    }

    @Test
    @DisplayName("동시 : 생성 ↔ 부모 키 속성 매핑 해제 — 교착 없이 끝나고, 생성 결과는 계획 그대로이거나 409 로 아무것도 없다")
    void concurrentWithParentMappingChange() throws Exception {
        GenerationFixture f = fixture("stab-race-mapping");
        long cust = f.entity("고객");
        long custNo = f.attr(cust, "고객번호", true, "CUST_NO");
        f.pk(cust, custNo);
        generation.apply(f.user, f.request(List.of(cust), generation.preview(f.user, f.request(List.of(cust))).planToken()));
        long ord = f.entity("주문");
        long ordNo = f.attr(ord, "주문번호", true, "ORD_NO");
        long ordCust = f.attr(ord, "주문고객번호", true, "ORD_CUST_NO");
        f.pk(ord, ordNo);
        f.pairs(f.rel("고객 주문", cust, ord, false, 1), new long[]{custNo, ordCust});
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(ord)));
        assertThat(plan.relations().get(0).fkStatus()).isEqualTo("READY");
        Long mappingId = attributeMappings.findByAttribute_Id(custNo).orElseThrow().getId();
        int versionsBefore = f.versions();
        List<Object> outcomes = race(f, () -> generation.apply(login(f), f.request(List.of(ord), plan.planToken())), () -> {
            mappingService.deleteAttributeMapping(login(f), mappingId);
            return "unmapped";
        });
        assertOnlyConflicts(outcomes);
        if (outcomes.get(0) instanceof PhysicalGenerationDto.Result) {
            assertStoredMatchesPlan(f, plan);
            assertThat(f.versions()).isEqualTo(versionsBefore + 1);
        } else {
            assertThat(entityMappings.findByEntity_Id(ord)).isEmpty();
            assertThat(f.versions()).isEqualTo(versionsBefore);
        }
    }

    @Test
    @DisplayName("동시 : 생성 ↔ 사용자 모델 버전 만들기 — 둘 다 끝나고(프로젝트 행에서 줄 선다) 버전 2개, 사용자 버전은 생성 전이나 후의 한 시점")
    void concurrentWithVersionCreate() throws Exception {
        GenerationFixture f = fixture("stab-race-version");
        long cust = f.entity("고객");
        long custNo = f.attr(cust, "고객번호", true, "CUST_NO");
        long rcmd = f.attr(cust, "추천고객번호", false, "RCMD_CUST_NO");
        f.pk(cust, custNo);
        f.pairs(f.rel("추천", cust, cust, false, 0), new long[]{custNo, rcmd});
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(cust)));
        AccountDto member = f.member();
        List<Object> outcomes = race(f, () -> generation.apply(login(f), f.request(List.of(cust), plan.planToken())), () -> {
            SecurityTestSupport.loginAs(projectRepository, member.getProjectId(), member.getId(), "ROLE_ADMIN");
            return versions.create(member, new ModelVersionDto.Create("동시 버전", null));
        });
        assertThat(outcomes.get(0)).isInstanceOf(PhysicalGenerationDto.Result.class);
        assertThat(outcomes.get(1)).isInstanceOf(ModelVersionDto.Summary.class);
        assertThat(f.versions()).isEqualTo(2);
        JsonNode snapshot = versions.snapshot(member, ((ModelVersionDto.Summary) outcomes.get(1)).id()).objects();
        int tables = snapshot.has("PHYSICAL_TABLE") ? snapshot.get("PHYSICAL_TABLE").size() : 0;
        int relations = snapshot.has("PHYSICAL_RELATION") ? snapshot.get("PHYSICAL_RELATION").size() : 0;
        assertThat(List.of(tables, relations)).as("생성 전(0, 0) 또는 후(1, 1)").isIn(List.of(0, 0), List.of(1, 1));
    }

    // ------------------------------------------------------------------ 보조

    private GenerationFixture fixture(String name) {
        return GenerationFixture.create(context, name, DbType.POSTGRESQL);
    }

    private void copyDictionary(Long from, Long to) {
        java.util.Map<Long, Long> domainIds = new java.util.HashMap<>();
        for (var row : jdbc.queryForList("select STD_DMN_SN, DMN_NM, DATA_TYPE_NM, DATA_LEN from TB_STD_DMN where PRJ_SN = ?", from)) {
            long id = GenerationFixture.nextId();
            domainIds.put(((Number) row.get("STD_DMN_SN")).longValue(), id);
            jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, '테스트', '테스트', ?, ?, ?, ?, null, true, current_timestamp, current_timestamp, 'test', 'test')", id, to, row.get("DMN_NM"), row.get("DMN_NM"), row.get("DATA_TYPE_NM"), row.get("DATA_LEN"));
        }
        for (var row : jdbc.queryForList("select TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN from TB_STD_TRM where PRJ_SN = ?", from)) {
            jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), to, row.get("TRM_NM"), row.get("TRM_NM"),
                    row.get("TRM_ENG_ABBR_NM"), domainIds.get(((Number) row.get("STD_DMN_SN")).longValue()));
        }
    }

    private SchemaInfo schema(GenerationFixture f, String name) {
        return tx.execute(s -> schemas.save(SchemaInfo.builder().projectId(f.user.getProjectId()).schemaName(name).isDefault(false).build()));
    }

    private AccountDto login(GenerationFixture f) {
        return SecurityTestSupport.loginAs(projectRepository, f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
    }

    /** 미리보기의 READY 관계가 저장된 물리 FK(부모·자식 테이블, 컬럼 쌍 순서, 이름)와 같다. SINGLE 은 커밋 때 참조 동기화가 만든 REF_COLUMN 관계 */
    private void assertStoredMatchesPlan(GenerationFixture f, PhysicalGenerationDto.Plan plan) {
        for (RelationPlan r : plan.relations()) {
            if (!r.fkCreated()) continue;
            long child = f.table(r.childEntityId());
            long parent = r.parentTableId() != null ? r.parentTableId() : f.table(r.parentEntityId());
            String expected = String.join(",", r.columns().stream().map(c -> c.childColumnName() + ">" + c.parentColumnName()).toList());
            List<String> stored = jdbc.queryForList("""
                    select r.REL_NM || ':' || r.REL_SRC_CD || ':' || (select string_agg(oc.COL_NM || '>' || tc.COL_NM, ',' order by x.COL_ORD)
                                                                         from TB_TBL_REL_COL x join TB_COL oc on oc.COL_SN = x.ORGNL_COL_SN join TB_COL tc on tc.COL_SN = x.TRGT_COL_SN
                                                                        where x.REL_SN = r.REL_SN)
                      from TB_TBL_REL r where r.ORGNL_TBL_SN = ? and r.TRGT_TBL_SN = ? and r.REL_TYPE_CD = 'FK'""", String.class, child, parent);
            String source = "SINGLE".equals(r.fkType()) ? "REF_COLUMN" : "USER";
            assertThat(stored).as(r.relationshipName()).contains(r.constraintName() + ":" + source + ":" + expected);
        }
    }

    private List<Object> race(GenerationFixture f, Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                } finally {
                    SecurityTestSupport.clear();
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) outcomes.add(future.get(90, TimeUnit.SECONDS));
        pool.shutdownNow();
        return outcomes;
    }

    /** 결과는 성공 또는 409(MODEL_CONFLICT) 뿐 — 교착·타임아웃·500 이 없다 */
    private static void assertOnlyConflicts(List<Object> outcomes) {
        for (Object o : outcomes) {
            if (o instanceof Throwable t) {
                assertThat(t).as(String.valueOf(t)).isInstanceOf(CustomException.class);
                assertThat(((CustomException) t).getErrorCode()).as(t.getMessage()).isEqualTo(ErrorCode.MODEL_CONFLICT);
            }
        }
    }

    private static Pattern fk(String name, String columns, String table, String refColumns) {
        return Pattern.compile("CONSTRAINT " + name + "\\s+FOREIGN KEY \\(" + Pattern.quote(columns) + "\\) REFERENCES \\S*" + table + " \\(" + Pattern.quote(refColumns) + "\\)");
    }

    private static int occurrences(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + 1)) n++;
        return n;
    }
}
