package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.ddl.service.DdlExportService;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.service.ErdService;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.TablePlan;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticDto;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticService;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-7 3차 : 슈퍼타입·서브타입 B형 실제 물리 생성과 상속 PK 매핑 (docs/ongoing/subtype-physical-transformation.md 30장, M7-D39~D43).
 * 생성(단일·복합·3단계·자기 속성 없는 서브타입)·기존 부모 재사용·일반 관계의 엔터티 문맥·수동 상속 PK 매핑 API·교차 무결성·생명주기·계획 토큰·분할 생성·
 * DDL·구조 진단·ERD 카디널리티·변경 이력·스냅샷 9·교환 6·프로젝트 삭제. 저장된 행을 직접 확인한다 (응답만 보지 않는다). H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SubtypePhysicalGenerationIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired EntityTableMappingRepository entityMappings;
    @Autowired ColumnManageService columns;
    @Autowired PhysicalModelService physical;
    @Autowired TableDesignService tableDesign;
    @Autowired DdlExportService ddlExport;
    @Autowired ModelStructureDiagnosticService diagnostics;
    @Autowired ErdService erd;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired com.lexorma.system.project.service.ProjectManageService projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    static final String[][] WORDS = {{"기업", "CORP"}, {"개인", "INDV"}, {"당사자", "PTY"}, {"조직", "ORG"}, {"사람", "PRSN"}, {"계약", "CNTRT"}, {"루트", "ROOT"},
            {"외국", "FRGN"}, {"갑", "GAP"}, {"을", "EUL"}};

    static GenerationFixture fixture(ApplicationContext context, String name) {
        GenerationFixture f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        for (String[] w : WORDS) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), w[0], w[1], w[1], w[0]);
        }
        return f;
    }

    private GenerationFixture fixture(String name) {
        return fixture(context, name);
    }

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private LogicalSubtypeDto.Group group(GenerationFixture f, long supertype, String name, boolean overlapping, boolean total, long... members) {
        List<MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new MemberInput(m, null));
        return subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, overlapping, total, null, list, null));
    }

    private PhysicalGenerationDto.Result generate(GenerationFixture f, Long... entities) {
        Plan plan = generation.preview(f.user, f.request(List.of(entities)));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        return generation.apply(f.user, f.request(List.of(entities), plan.planToken()));
    }

    private static TablePlan table(Plan plan, long entity) {
        return plan.tables().stream().filter(t -> t.entityId().equals(entity)).findFirst().orElseThrow();
    }

    private static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).as(e.getMessage()).isEqualTo(code));
    }

    private long mappingId(long entity) {
        return entityMappings.findByEntity_Id(entity).orElseThrow().getId();
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    /** 테이블에서 나가는 FK : 대상 테이블명:출처:자식>부모,... (컬럼 순서) */
    private List<String> fks(long table) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select r.REL_SN, t.TBL_NM, r.REL_SRC_CD from TB_TBL_REL r join TB_TBL t on t.TBL_SN = r.TRGT_TBL_SN"
                + " where r.ORGNL_TBL_SN = ? and r.REL_TYPE_CD = 'FK' order by r.REL_SN", table)) {
            List<String> pairs = jdbc.queryForList("select oc.COL_NM || '>' || tc.COL_NM from TB_TBL_REL_COL x join TB_COL oc on oc.COL_SN = x.ORGNL_COL_SN"
                    + " join TB_COL tc on tc.COL_SN = x.TRGT_COL_SN where x.REL_SN = ? order by x.COL_ORD", String.class, r.get("REL_SN"));
            result.add(r.get("TBL_NM") + ":" + r.get("REL_SRC_CD") + ":" + String.join(",", pairs));
        }
        return result;
    }

    /** 엔터티 매핑의 상속 PK 매핑 : 원본 속성명@원본 엔터티명>컬럼명 (ID 순) */
    private List<String> keyMappings(long entity) {
        return jdbc.queryForList("select a.ATRB_NM || '@' || e.ENTT_NM || '>' || c.COL_NM from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN"
                + " join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = s.LGCL_ATRB_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = a.LGCL_ENTT_SN join TB_COL c on c.COL_SN = s.COL_SN"
                + " where m.LGCL_ENTT_SN = ? order by s.SBTYP_IDNTFR_COL_MPNG_SN", String.class, entity);
    }

    private int directMappings(long entity) {
        return jdbc.queryForObject("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where m.LGCL_ENTT_SN = ?", Integer.class, entity);
    }

    private ModelMappingDto.SubtypeKeyDetail keys(GenerationFixture f, long entity) {
        return mappings.selectSubtypeKeys(f.user, mappingId(entity));
    }

    private ModelMappingDto.SubtypeKeyMapping link(GenerationFixture f, long entity, long attribute, long column) {
        return mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(null, mappingId(entity), attribute, column));
    }

    private int history(GenerationFixture f, String type, String action) {
        return f.count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = ? and CHG_TYPE_CD = ?", f.user.getProjectId(), type, action);
    }

    // ------------------------------------------------------------------ 생성

    @Test
    @DisplayName("2단계 생성 : 루트 = 일반 테이블, 서브타입 = 상속 PK(PK·NOT NULL·참조 테이블) + 직접 속성, 자기 속성 없는 서브타입도 테이블, 상속 PK 매핑·REF_COLUMN 상속 FK 하나, DDL·진단·ERD·이력·버전")
    void twoLevel() {
        GenerationFixture f = fixture("sg-two");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        f.attr(customer, "고객 이름", false, "CUST_NM");
        long company = entity(f, "기업 고객");
        f.attr(company, "사업자 번호", false, "BIZ_NO");
        long person = entity(f, "개인 고객"); // 자기 속성 없음
        group(f, customer, "고객 구분", false, true, company, person);
        int versionsBefore = f.versions();

        PhysicalGenerationDto.Result result = generate(f, customer, company, person);
        assertThat(result.tables()).isEqualTo(3);
        assertThat(result.inheritanceForeignKeys()).isEqualTo(2);
        assertThat(result.inheritedKeyMappings()).isEqualTo(2);
        assertThat(result.columns()).isEqualTo(2 + 2 + 1);
        long root = f.table(customer);
        long corp = f.table(company);
        long indv = f.table(person);
        String rootName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, root);
        // 서브타입 컬럼 : 상속 PK 먼저 (PK·NOT NULL·필수·참조 테이블 = 루트), 상속 일반 속성(고객 이름)은 없다
        assertThat(jdbc.queryForList("select COL_NM, PK_YN, NULL_ALWNC_YN, RFRNC_TBL_NM from TB_COL where TBL_SN = ? order by SORT_ORD", corp).stream()
                .map(r -> r.get("COL_NM") + ":" + r.get("PK_YN") + ":" + r.get("NULL_ALWNC_YN") + ":" + (r.get("RFRNC_TBL_NM") == null ? "-" : r.get("RFRNC_TBL_NM"))).toList())
                .containsExactly("CUST_NO:true:false:" + rootName, "BIZ_NO:false:true:-");
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? order by SORT_ORD", String.class, indv)).containsExactly("CUST_NO");
        // 매핑 : 상속 PK 는 상속 PK 매핑에만 (원본 = 루트 주 식별자 속성), 직접 속성 매핑은 직접 속성만
        assertThat(keyMappings(company)).containsExactly("고객 번호@고객>CUST_NO");
        assertThat(keyMappings(person)).containsExactly("고객 번호@고객>CUST_NO");
        assertThat(keyMappings(customer)).isEmpty();
        assertThat(directMappings(company)).isOne();
        assertThat(directMappings(person)).isZero();
        assertThat(directMappings(customer)).isEqualTo(2);
        // FK : 서브타입마다 루트로 하나 (참조 테이블명 → REF_COLUMN), 중복 없음
        assertThat(fks(corp)).containsExactly(rootName + ":REF_COLUMN:CUST_NO>CUST_NO");
        assertThat(fks(indv)).containsExactly(rootName + ":REF_COLUMN:CUST_NO>CUST_NO");
        assertThat(fks(root)).isEmpty();

        // DDL : PK 이면서 FK, 트리거·CHECK·구분 컬럼 없음
        String ddl = tableDesign.generateTableDdl(corp, f.user, null).ddl();
        assertThat(ddl).contains("PRIMARY KEY (CUST_NO)").contains("FOREIGN KEY (CUST_NO) REFERENCES").doesNotContainIgnoringCase("TRIGGER").doesNotContain("CHECK (");
        assertThat(ddl.split("FOREIGN KEY", -1)).hasSize(2);
        // 구조 진단 : 상속 FK 가 FK 오류가 아니다
        ModelStructureDiagnosticDto.Result diagnosed = diagnostics.diagnose(f.user, null);
        assertThat(diagnosed.issues()).noneMatch(i -> i.type().name().startsWith("FK_"));
        // ERD : 식별 관계, 자식 → 부모 정확히 하나, 부모 → 특정 서브타입 최대 하나 (최소 참여는 추론하지 않는다 — Total 이어도)
        ErdDto.Graph graph = erd.selectGraph(f.user, new ErdDto.Search(null, null, null, null), 0, 100, ErdDto.RelationScope.INNER);
        Long relationId = jdbc.queryForObject("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, corp, root);
        assertThat(graph.cardinalities().get(relationId)).satisfies(c -> {
            assertThat(c.identifying()).isEqualTo("IDENTIFYING");
            assertThat(c.parentMin()).isEqualTo("1");
            assertThat(c.parentMax()).isEqualTo("1");
            assertThat(c.childMax()).isEqualTo("1");
        });
        // 이력 : 상속 PK 매핑 등록 2건, 버전 : 생성 직전 1개 (그 스냅샷에는 아직 없다), 지금 버전(형식 9)에는 있다
        assertThat(history(f, "SUBTYPE_KEY_MAPPING", "CREATE")).isEqualTo(2);
        assertThat(f.versions()).isEqualTo(versionsBefore + 1);
        ModelVersionDto.Snapshot generated = versions.snapshot(f.member(), result.versionId());
        assertThat(generated.objects().has("SUBTYPE_KEY_MAPPING") ? generated.objects().get("SUBTYPE_KEY_MAPPING").size() : 0).isZero();
        ModelVersionDto.Summary now = versions.create(f.member(), new ModelVersionDto.Create("sg-two-" + System.nanoTime(), null));
        assertThat(now.formatVersion()).isEqualTo(9);
        assertThat(now.counts().get("SUBTYPE_KEY_MAPPING").asInt()).isEqualTo(2);
        ModelVersionDto.Comparison diff = versions.compare(f.member(), result.versionId(), now.id());
        assertThat(diff.changes()).filteredOn(c -> "SUBTYPE_KEY_MAPPING".equals(c.targetType())).extracting(ModelVersionDto.Change::change).containsOnly("ADDED").hasSize(2);

        // 재실행 : 모두 SKIP, 아무것도 바뀌지 않는다
        String before = String.join("|", jdbc.queryForList("select TBL_NM from TB_TBL where PRJ_SN = ? order by TBL_SN", String.class, f.user.getProjectId()))
                + keyMappings(company) + fks(corp);
        Plan again = generation.preview(f.user, f.request(List.of(customer, company, person)));
        assertThat(again.tables()).extracting(TablePlan::status).containsOnly("SKIP");
        assertThat(again.creatable()).isFalse();
        assertThat(String.join("|", jdbc.queryForList("select TBL_NM from TB_TBL where PRJ_SN = ? order by TBL_SN", String.class, f.user.getProjectId()))
                + keyMappings(company) + fks(corp)).isEqualTo(before);
    }

    @Test
    @DisplayName("3단계·복합 PK : 루트 식별자 순서 그대로 PK(A, B) → 중간 PK(A, B)·FK(A, B) → 말단 PK(A, B)·FK(A, B) 는 직계 부모로, FK 하나씩 (단일 FK 로 쪼개지 않는다), 말단 → 루트 FK 없음")
    void threeLevelComposite() {
        GenerationFixture f = fixture("sg-three");
        long party = entity(f, "당사자");
        long orgCd = f.attr(party, "기관 코드", true, "ORG_CD");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo, orgCd); // 식별자 순서 = PTY_NO, ORG_CD
        long org = entity(f, "조직");
        f.attr(org, "조직 이름", false, "ORG_NM");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", false, false, dept, team);

        PhysicalGenerationDto.Result result = generate(f, party, org, human, dept, team);
        assertThat(result.inheritanceForeignKeys()).isEqualTo(4);
        assertThat(result.inheritedKeyMappings()).isEqualTo(8);
        String partyName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, f.table(party));
        String orgName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, f.table(org));
        for (long e : new long[]{org, human, dept, team}) {
            assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and PK_YN = true order by SORT_ORD", String.class, f.table(e))).containsExactly("PTY_NO", "ORG_CD");
            assertThat(keyMappings(e)).containsExactly("당사자 번호@당사자>PTY_NO", "기관 코드@당사자>ORG_CD");
        }
        assertThat(fks(f.table(org))).containsExactly(partyName + ":USER:PTY_NO>PTY_NO,ORG_CD>ORG_CD");
        assertThat(fks(f.table(dept))).containsExactly(orgName + ":USER:PTY_NO>PTY_NO,ORG_CD>ORG_CD");
        assertThat(fks(f.table(team))).containsExactly(orgName + ":USER:PTY_NO>PTY_NO,ORG_CD>ORG_CD");
        String ddl = tableDesign.generateTableDdl(f.table(dept), f.user, null).ddl();
        assertThat(ddl).contains("PRIMARY KEY (PTY_NO, ORG_CD)").contains("FOREIGN KEY (PTY_NO, ORG_CD) REFERENCES").contains(orgName + " (PTY_NO, ORG_CD)");
        assertThat(ddl.split("FOREIGN KEY", -1)).as("복합 FK 하나").hasSize(2);
        assertThat(ddl).doesNotContain("REFERENCES " + partyName).doesNotContain("REFERENCES DEFAULT." + partyName);
        assertThat(diagnostics.diagnose(f.user, null).issues()).noneMatch(i -> i.type().name().startsWith("FK_"));
        ErdDto.Graph graph = erd.selectGraph(f.user, new ErdDto.Search(null, null, null, null), 0, 100, ErdDto.RelationScope.INNER);
        Long deptFk = jdbc.queryForObject("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ?", Long.class, f.table(dept));
        assertThat(graph.cardinalities().get(deptFk)).extracting(ErdDto.Cardinality::identifying, ErdDto.Cardinality::childMax).containsExactly("IDENTIFYING", "1");
        assertThat(graph.relations()).filteredOn(r -> r.id().equals(deptFk)).singleElement().satisfies(r -> assertThat(r.columns()).hasSize(2));
    }

    @Test
    @DisplayName("기존 부모 재사용 : 루트만 → 중간·손자 / 루트·중간 → 손자 — 기존 부모는 SKIP 없이 참조만 하고 고치지 않는다. 일반 관계의 기존 서브타입 부모는 상속 PK 매핑의 컬럼(루트 컬럼이 아님)")
    void reuseExistingParents() {
        GenerationFixture f = fixture("sg-reuse");
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);

        generate(f, party);
        long partyTable = f.table(party);
        String partyBefore = jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", String.class, partyTable)
                + jdbc.queryForList("select COL_NM, PK_YN, MDFCN_DT from TB_COL where TBL_SN = ? order by COL_SN", partyTable);
        // 루트 기존 + 중간 신규 + 손자 신규
        Plan plan = generation.preview(f.user, f.request(List.of(org, dept)));
        assertThat(plan.inheritanceForeignKeys()).extracting(PhysicalGenerationDto.InheritanceForeignKeyPlan::childEntityId, PhysicalGenerationDto.InheritanceForeignKeyPlan::parentTableId)
                .containsExactly(tuple(org, partyTable), tuple(dept, null));
        generation.apply(f.user, f.request(List.of(org, dept), plan.planToken()));
        // 루트·중간 기존 + 손자 신규 (중간의 상속 PK 매핑으로 유효성 확인)
        assertThat(keys(f, org).status()).isEqualTo("VALID");
        Plan leaf = generation.preview(f.user, f.request(List.of(team)));
        assertThat(table(leaf, team).status()).isEqualTo("CREATE");
        assertThat(leaf.inheritanceForeignKeys()).singleElement().satisfies(k -> {
            assertThat(k.status()).isEqualTo("READY");
            assertThat(k.parentTableId()).isEqualTo(f.table(org));
        });
        generation.apply(f.user, f.request(List.of(team), leaf.planToken()));
        String orgName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, f.table(org));
        assertThat(fks(f.table(team))).containsExactly(orgName + ":REF_COLUMN:PTY_NO>PTY_NO");
        assertThat(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", String.class, partyTable)
                + jdbc.queryForList("select COL_NM, PK_YN, MDFCN_DT from TB_COL where TBL_SN = ? order by COL_SN", partyTable)).as("기존 루트 테이블 그대로").isEqualTo(partyBefore);

        // 일반 관계 : 기존 서브타입(조직) → 계약. 짝의 부모 속성은 루트의 당사자 번호지만 조직 테이블의 상속 PK 컬럼을 참조한다
        long contract = entity(f, "계약");
        f.pk(contract, f.attr(contract, "계약 번호", true, "CNTRT_NO"));
        long owner = f.attr(contract, "소유 조직 번호", false, "OWNR_ORG_NO");
        LogicalModelDto.Relationship rel = f.rel("조직 계약", org, contract, false, 0);
        f.pairs(rel, new long[]{partyNo, owner});
        Plan relationPlan = generation.preview(f.user, f.request(List.of(contract)));
        PhysicalGenerationDto.RelationPlan r = relationPlan.relations().getFirst();
        assertThat(r.fkStatus()).isEqualTo("READY");
        assertThat(r.parentTableId()).isEqualTo(f.table(org));
        generation.apply(f.user, f.request(List.of(contract), relationPlan.planToken()));
        assertThat(fks(f.table(contract))).containsExactly(orgName + ":REF_COLUMN:OWNR_ORG_NO>PTY_NO");
    }

    @Test
    @DisplayName("기존 중간 엔터티 판정 : 상속 PK 매핑 없음 → PK 불일치 → 부모 FK 없음 → 유효(손자 생성 가능) → 타입 불일치, 복합 키 일부만 매핑은 미완성. 컬럼명·FK 로 추론하지 않는다")
    void existingIntermediate() {
        GenerationFixture f = fixture("sg-intermediate");
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = entity(f, "조직");
        long orgKey = f.attr(org, "조직 당사자 번호", false, "PTY_NO"); // 일반 엔터티일 때 만든 같은 이름 컬럼 — 이름만으로는 상속 PK 가 아니다
        generate(f, party, org);
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        // 조직의 속성을 지우려면 매핑부터 — 속성은 그대로 둔다 (상속 PK 와 이름이 겹치지 않게 그대로 두는 게 아니라 다른 이름)
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);
        long orgTable = f.table(org);
        long orgColumn = column(orgTable, "PTY_NO");

        assertThat(table(generation.preview(f.user, f.request(List.of(dept))), dept).inheritance().reasons()).containsExactly("INHERITED_KEY_MAPPING_MISSING");
        assertThat(keys(f, org)).satisfies(d -> {
            assertThat(d.status()).isEqualTo("INHERITED_KEY_MAPPING_MISSING");
            assertThat(d.complete()).isFalse();
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::attributeId, ModelMappingDto.SubtypeKeyRow::columnId).containsExactly(tuple(partyNo, null));
        });
        // 같은 컬럼이 직접 속성 매핑에 쓰이면 상속 PK 매핑에 쓸 수 없다 (교차 중복)
        error(() -> link(f, org, partyNo, orgColumn), ErrorCode.SAVED_DATA_EXISTS);
        long direct = jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, orgColumn);
        mappings.deleteAttributeMapping(f.user, direct);
        link(f, org, partyNo, orgColumn);
        assertThat(keys(f, org).status()).as("일반 엔터티일 때 만든 컬럼은 PK 가 아니다").isEqualTo("PARENT_KEY_MISMATCH");
        jdbc.update("update TB_COL set PK_YN = true, NULL_ALWNC_YN = false where COL_SN = ?", orgColumn);
        assertThat(keys(f, org).status()).isEqualTo("INHERITANCE_FK_MISSING");
        assertThat(table(generation.preview(f.user, f.request(List.of(dept))), dept).inheritance().reasons()).containsExactly("INHERITANCE_FK_MISSING");
        String partyName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, f.table(party));
        jdbc.update("update TB_COL set RFRNC_TBL_NM = ? where COL_SN = ?", partyName, orgColumn);
        tx.executeWithoutResult(s -> physical.syncReferenceRelations(f.user.getProjectId(), List.of(orgTable)));
        assertThat(keys(f, org).status()).isEqualTo("VALID");
        Plan ok = generation.preview(f.user, f.request(List.of(dept)));
        assertThat(table(ok, dept).status()).isEqualTo("CREATE");
        // 타입 불일치 (FK 로 묶일 수 없는 타입)
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'DATE', MAX_LEN = null where COL_SN = ?", orgColumn);
        assertThat(keys(f, org).status()).isEqualTo("INHERITED_KEY_TYPE_MISMATCH");
        assertThat(table(generation.preview(f.user, f.request(List.of(dept))), dept).status()).isEqualTo("ERROR");
        error(() -> generation.apply(f.user, f.request(List.of(dept), ok.planToken())), ErrorCode.MODEL_CONFLICT);
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'VARCHAR', MAX_LEN = 20 where COL_SN = ?", orgColumn);

        // 복합 루트 키의 일부만 매핑 = 미완성 (유효한 부모가 아니다)
        GenerationFixture g = fixture("sg-partial");
        long root = entity(g, "당사자");
        long a = g.attr(root, "당사자 번호", true, "PTY_NO");
        long b = g.attr(root, "기관 코드", true, "ORG_CD");
        g.pk(root, a, b);
        long mid = entity(g, "조직");
        g.attr(mid, "조직 당사자 번호", false, "M_PTY_NO");
        g.attr(mid, "조직 기관 코드", false, "M_ORG_CD");
        generate(g, root, mid);
        long other = entity(g, "사람");
        group(g, root, "구분", true, false, mid, other);
        long midTable = g.table(mid);
        long midColumn = column(midTable, "M_PTY_NO");
        mappings.deleteAttributeMapping(g.user, jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, midColumn));
        link(g, mid, a, midColumn);
        assertThat(keys(g, mid)).satisfies(d -> {
            assertThat(d.complete()).isFalse();
            assertThat(d.status()).isEqualTo("INHERITED_KEY_MAPPING_MISSING");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::order, ModelMappingDto.SubtypeKeyRow::current, ModelMappingDto.SubtypeKeyRow::columnId)
                    .containsExactly(tuple(1, true, midColumn), tuple(2, true, null));
        });
    }

    // ------------------------------------------------------------------ 수동 상속 PK 매핑

    @Test
    @DisplayName("수동 매핑 : 등록·변경·해제, 서브타입·루트 주 식별자·그 테이블 컬럼만, (매핑, 속성)·컬럼 중복과 속성 매핑과의 교차 중복 거절(양방향), 후보 컬럼, 같은 값 변경은 이력 없음, 다른 프로젝트 거절")
    void manualMappings() {
        GenerationFixture f = fixture("sg-manual");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        long customerName = f.attr(customer, "고객 이름", false, "CUST_NM");
        long company = entity(f, "기업 고객");
        long ref = f.attr(company, "기업 고객 번호", false, "CORP_CUST_NO");
        long biz = f.attr(company, "사업자 번호", false, "BIZ_NO");
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));
        generate(f, customer, company, product);
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", true, false, company, person);
        long corp = f.table(company);
        long refColumn = column(corp, "CORP_CUST_NO");
        long bizColumn = column(corp, "BIZ_NO");
        long refDirect = jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, refColumn);

        // 후보 : 속성 매핑에 쓰인 컬럼은 빠진다
        assertThat(mappings.selectSubtypeKeyCandidates(f.user, mappingId(company), null, null, PageRequest.of(0, 20)).getContent()).isEmpty();
        error(() -> link(f, company, customerNo, refColumn), ErrorCode.SAVED_DATA_EXISTS);
        mappings.deleteAttributeMapping(f.user, refDirect);
        assertThat(mappings.selectSubtypeKeyCandidates(f.user, mappingId(company), null, "corp", PageRequest.of(0, 20)).getContent())
                .extracting(ModelMappingDto.KeyColumnCandidate::columnId).containsExactly(refColumn);
        // 잘못된 요청
        error(() -> link(f, company, customerName, refColumn), ErrorCode.INVALID_MODEL_MAPPING); // 루트 주 식별자 아님
        error(() -> link(f, company, ref, refColumn), ErrorCode.INVALID_MODEL_MAPPING); // 자기 속성
        error(() -> link(f, company, customerNo, column(f.table(customer), "CUST_NO")), ErrorCode.INVALID_MODEL_MAPPING); // 다른 테이블 컬럼
        error(() -> link(f, product, customerNo, column(f.table(product), "PRDT_NO")), ErrorCode.INVALID_MODEL_MAPPING); // 서브타입 아님
        error(() -> link(f, customer, customerNo, column(f.table(customer), "CUST_NM")), ErrorCode.INVALID_MODEL_MAPPING); // 루트
        int historyBefore = history(f, "SUBTYPE_KEY_MAPPING", "CREATE");

        ModelMappingDto.SubtypeKeyMapping saved = link(f, company, customerNo, refColumn);
        assertThat(keyMappings(company)).containsExactly("고객 번호@고객>CORP_CUST_NO");
        assertThat(history(f, "SUBTYPE_KEY_MAPPING", "CREATE")).isEqualTo(historyBefore + 1);
        error(() -> link(f, company, customerNo, bizColumn), ErrorCode.SAVED_DATA_EXISTS); // 같은 매핑의 같은 속성
        // 교차 중복 (반대 방향) : 상속 PK 매핑에 쓰인 컬럼은 속성 매핑 후보·등록에서 빠진다
        assertThat(mappings.selectEntityMapping(f.user, mappingId(company)).unmappedColumns()).extracting(ModelMappingDto.ColumnCandidate::columnId).doesNotContain(refColumn);
        error(() -> mappings.saveAttributeMapping(f.user, new ModelMappingDto.AttributeMapping(null, mappingId(company), ref, refColumn)), ErrorCode.SAVED_DATA_EXISTS);
        // 변경 : 컬럼만 — 속성 매핑에 쓰인 컬럼은 안 되고, 해제 뒤에는 된다. 같은 컬럼으로 다시 저장하면 이력 없음
        error(() -> mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(saved.id(), null, null, bizColumn)), ErrorCode.SAVED_DATA_EXISTS);
        mappings.deleteAttributeMapping(f.user, jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, bizColumn));
        assertThat(mappings.selectSubtypeKeyCandidates(f.user, mappingId(company), saved.id(), null, PageRequest.of(0, 20)).getContent())
                .extracting(ModelMappingDto.KeyColumnCandidate::columnId).containsExactlyInAnyOrder(refColumn, bizColumn);
        mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(saved.id(), null, customerName, bizColumn));
        assertThat(keyMappings(company)).as("속성은 바뀌지 않는다").containsExactly("고객 번호@고객>BIZ_NO");
        int updates = history(f, "SUBTYPE_KEY_MAPPING", "UPDATE");
        assertThat(updates).isOne();
        mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(saved.id(), null, null, bizColumn));
        assertThat(history(f, "SUBTYPE_KEY_MAPPING", "UPDATE")).as("같은 값").isEqualTo(updates);
        // 물리 구조는 바꾸지 않는다 (PK·FK 그대로 — 정합성 사유로만 보인다)
        assertThat(jdbc.queryForObject("select PK_YN from TB_COL where COL_SN = ?", Boolean.class, bizColumn)).isFalse();
        assertThat(keys(f, company).status()).isEqualTo("PARENT_KEY_MISMATCH");
        // 승인 상태는 매핑과 무관하다 (속성 매핑과 같은 계약) — 승인된 테이블에도 등록·해제할 수 있다
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", corp);
        mappings.deleteSubtypeKeyMapping(f.user, saved.id());
        assertThat(keyMappings(company)).isEmpty();
        assertThat(history(f, "SUBTYPE_KEY_MAPPING", "DELETE")).isOne();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, corp)).as("컬럼은 그대로").isEqualTo(2);

        // 다른 프로젝트 로그인
        GenerationFixture other = fixture("sg-manual-other");
        ModelMappingDto.SubtypeKeyMapping again = link(f, company, customerNo, refColumn);
        error(() -> mappings.selectSubtypeKeys(other.user, mappingId(company)), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        error(() -> mappings.saveSubtypeKeyMapping(other.user, new ModelMappingDto.SubtypeKeyMapping(null, mappingId(company), customerNo, bizColumn)), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        error(() -> mappings.deleteSubtypeKeyMapping(other.user, again.id()), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        error(() -> mappings.selectSubtypeKeyCandidates(other.user, mappingId(company), null, null, PageRequest.of(0, 20)), ErrorCode.MODEL_MAPPING_IS_NOT_DEFINED);
        assertThat(keyMappings(company)).hasSize(1);
    }

    // ------------------------------------------------------------------ 생명주기

    @Test
    @DisplayName("생명주기 : 엔터티 매핑 해제·매핑 컬럼 삭제(개별·일괄)·원본 속성 삭제는 거절, 루트 식별자·물리 PK·상속 구조가 바뀌어도 매핑은 남고 불일치로 보이며 명시적으로 해제할 수 있다")
    void lifecycle() {
        GenerationFixture f = fixture("sg-life");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        LogicalSubtypeDto.Group g = group(f, customer, "고객 구분", true, false, company, person);
        generate(f, customer, company, person);
        long corp = f.table(company);
        long keyColumn = column(corp, "CUST_NO");

        error(() -> mappings.deleteEntityMapping(f.user, mappingId(company)), ErrorCode.RELATION_EXISTS);
        error(() -> mappings.saveEntityMapping(f.user, new ModelMappingDto.EntityMapping(mappingId(company), company, f.table(person))), ErrorCode.INVALID_MODEL_MAPPING);
        ColumnInfoDto target = new ColumnInfoDto();
        target.setId(keyColumn);
        error(() -> columns.deleteColumn(f.user, List.of(target)), ErrorCode.RELATION_EXISTS);
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", corp); // 일괄 저장은 승인된 테이블만 받는다
        com.lexorma.data.column.domain.dto.ColumnBulkSaveDto bulk = new com.lexorma.data.column.domain.dto.ColumnBulkSaveDto();
        bulk.setTableInfoId(corp);
        bulk.setDeletedIds(List.of(keyColumn));
        error(() -> columns.saveColumnsBulk(f.user, bulk), ErrorCode.RELATION_EXISTS);
        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", corp);
        error(() -> logical.deleteAttribute(f.user, customerNo), ErrorCode.RELATION_EXISTS);
        assertThat(keyMappings(company)).hasSize(1);

        // 물리 PK 변경 → 매핑 그대로, 불일치
        jdbc.update("update TB_COL set PK_YN = false where COL_SN = ?", keyColumn);
        assertThat(keys(f, company).status()).isEqualTo("PARENT_KEY_MISMATCH");
        jdbc.update("update TB_COL set PK_YN = true where COL_SN = ?", keyColumn);
        assertThat(keys(f, company).status()).isEqualTo("VALID");
        // 루트 주 식별자 변경 → 매핑 그대로, 새 식별자 속성은 미매핑
        long extra = f.attr(customer, "고객 구분 코드", true, "CUST_SE_CD");
        Long identifierId = jdbc.queryForObject("select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and BSC_YN = true", Long.class, customer);
        LocalDateTime identifierAt = jdbc.queryForObject("select MDFCN_DT from TB_LGCL_IDNTFR where LGCL_IDNTFR_SN = ?", LocalDateTime.class, identifierId);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifierId, customer, "식별자", true, List.of(customerNo, extra), null, identifierAt));
        assertThat(keys(f, company)).satisfies(d -> {
            assertThat(d.status()).isEqualTo("INHERITED_KEY_MAPPING_MISSING");
            assertThat(d.keys()).extracting(ModelMappingDto.SubtypeKeyRow::attributeId, ModelMappingDto.SubtypeKeyRow::current).containsExactly(tuple(customerNo, true), tuple(extra, true));
        });
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(identifierId, customer, "식별자", true, List.of(extra), null,
                jdbc.queryForObject("select MDFCN_DT from TB_LGCL_IDNTFR where LGCL_IDNTFR_SN = ?", LocalDateTime.class, identifierId)));
        assertThat(keys(f, company).keys()).extracting(ModelMappingDto.SubtypeKeyRow::attributeId, ModelMappingDto.SubtypeKeyRow::current)
                .as("지금 주 식별자가 아닌 기존 매핑도 숨기지 않는다").containsExactly(tuple(extra, true), tuple(customerNo, false));
        assertThat(keyMappings(company)).hasSize(1);
        // 상속 구조 해제 → 매핑 그대로 (NOT_SUBTYPE), 명시적으로 해제
        subtypes.deleteGroup(f.user, g.id(), subtypes.selectGroup(f.user, g.id()).modifiedAt());
        ModelMappingDto.SubtypeKeyDetail detached = keys(f, company);
        assertThat(detached.subtype()).isFalse();
        assertThat(detached.status()).isEqualTo("NOT_SUBTYPE");
        assertThat(detached.keys()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where TBL_SN = ?", Integer.class, corp)).as("테이블을 지우지 않는다").isOne();
        error(() -> link(f, company, extra, keyColumn), ErrorCode.INVALID_MODEL_MAPPING); // 서브타입이 아니면 새로 등록할 수 없다
        mappings.deleteSubtypeKeyMapping(f.user, detached.keys().getFirst().id());
        assertThat(keyMappings(company)).isEmpty();
        // 해제 뒤에는 엔터티 매핑을 해제할 수 있다 (상속 PK 매핑을 자동으로 지우지 않는다 — 먼저 해제한 것이다)
        mappings.deleteEntityMapping(f.user, mappingId(company));
        assertThat(entityMappings.findByEntity_Id(company)).isEmpty();
    }

    @Test
    @DisplayName("프로젝트 삭제 : 상속 PK 매핑까지 지우고, 다른 프로젝트의 매핑은 그대로")
    void projectDeletion() {
        GenerationFixture keep = fixture("sg-keep");
        long kc = entity(keep, "고객");
        keep.pk(kc, keep.attr(kc, "고객 번호", true, "CUST_NO"));
        long k1 = entity(keep, "기업 고객");
        long k2 = entity(keep, "개인 고객");
        group(keep, kc, "고객 구분", true, false, k1, k2);
        generate(keep, kc, k1, k2);
        GenerationFixture f = fixture("sg-delete");
        long c = entity(f, "고객");
        f.pk(c, f.attr(c, "고객 번호", true, "CUST_NO"));
        long s1 = entity(f, "기업 고객");
        long s2 = entity(f, "개인 고객");
        group(f, c, "고객 구분", true, false, s1, s2);
        generate(f, c, s1, s2);
        int total = jdbc.queryForObject("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG", Integer.class);
        Long projectId = f.user.getProjectId();
        projects.deleteProject(projectId, f.user);
        assertThat(jdbc.queryForObject("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG", Integer.class)).isEqualTo(total - 2);
        assertThat(keyMappings(k1)).hasSize(1);
        assertThat(keyMappings(k2)).hasSize(1);
    }

    // ------------------------------------------------------------------ 계획 토큰·분할 생성

    @Test
    @DisplayName("계획 토큰 : 같은 상태면 같고, 기존 부모의 상속 PK 매핑 해제·등록·키 컬럼 변경·부모 FK 변경이면 달라져 예전 토큰은 409. 형식이 다른 토큰도 409, 다른 프로젝트 변경은 영향 없음")
    void planToken() {
        GenerationFixture f = fixture("sg-token");
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);
        generate(f, party, org);
        long orgTable = f.table(org);
        long orgKey = column(orgTable, "PTY_NO");
        List<Long> chosen = List.of(dept);
        String t1 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(generation.preview(f.user, f.request(chosen)).planToken()).isEqualTo(t1);
        error(() -> generation.apply(f.user, f.request(chosen, "v6-" + t1)), ErrorCode.MODEL_CONFLICT);

        // 상속 PK 매핑 해제 → 등록 (같은 컬럼이어도 매핑 ID 가 바뀐다)
        long linkId = jdbc.queryForObject("select SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN = ?", Long.class, orgKey);
        mappings.deleteSubtypeKeyMapping(f.user, linkId);
        String t2 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(t2).isNotEqualTo(t1);
        link(f, org, partyNo, orgKey);
        String t3 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(t3).isNotEqualTo(t1).isNotEqualTo(t2);
        error(() -> generation.apply(f.user, f.request(chosen, t1)), ErrorCode.MODEL_CONFLICT);
        // 부모 키 컬럼 타입·PK 여부 (수정 시각)
        jdbc.update("update TB_COL set MAX_LEN = 30, MDFCN_DT = current_timestamp where COL_SN = ?", orgKey);
        String t4 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(t4).isNotEqualTo(t3);
        // 부모 FK (관계 수정 시각)
        jdbc.update("update TB_TBL_REL set MDFCN_DT = current_timestamp where ORGNL_TBL_SN = ?", orgTable);
        String t5 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(t5).isNotEqualTo(t4);
        error(() -> generation.apply(f.user, f.request(chosen, t4)), ErrorCode.MODEL_CONFLICT);
        assertThat(entityMappings.findByEntity_Id(dept)).isEmpty();
        // 다른 프로젝트 변경은 영향 없음
        GenerationFixture other = fixture("sg-token-other");
        long x = entity(other, "고객");
        other.pk(x, other.attr(x, "고객 번호", true, "CUST_NO"));
        generate(other, x);
        assertThat(generation.preview(f.user, f.request(chosen)).planToken()).isEqualTo(t5);
        assertThat(generation.apply(f.user, f.request(chosen, t5)).inheritedKeyMappings()).isOne();
    }

    @Test
    @DisplayName("분할 생성 : 서브타입 1,001개 그룹 — 루트 + 99개, 다음 100개(루트는 기존 매핑 재사용), 마지막 구성원까지 나눠 만들 수 있다. 요청당 100개 한도, 그룹 구성원 한도 없음")
    void splitLargeGroup() {
        GenerationFixture f = fixture("sg-split");
        long customer = entity(f, "고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        // 서브타입 엔터티 1,001개 : 테이블명이 겹치지 않게 표준 단어 1,001개 (엔터티명 = 단어)
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            String word = "갑" + i;
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), word, "S" + i, "S" + i, word);
            long id = GenerationFixture.nextId();
            jdbc.update("insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values (?, ?, ?, ?, false, current_timestamp, current_timestamp, 'test', 'test')",
                    id, f.user.getProjectId(), f.modelId, word);
            members.add(id);
        }
        group(f, customer, "대량 구분", true, false, members.stream().mapToLong(Long::longValue).toArray());
        List<Long> first = new ArrayList<>(List.of(customer));
        first.addAll(members.subList(0, 99));
        assertThat(generate(f, first.toArray(Long[]::new)).tables()).isEqualTo(100);
        Plan second = generation.preview(f.user, f.request(new ArrayList<>(members.subList(99, 199))));
        assertThat(second.inheritanceForeignKeys()).allMatch(k -> "READY".equals(k.status()) && k.parentTableId().equals(f.table(customer)));
        assertThat(generation.apply(f.user, f.request(new ArrayList<>(members.subList(99, 199)), second.planToken())).tables()).isEqualTo(100);
        assertThat(generate(f, members.subList(901, 1001).toArray(Long[]::new)).tables()).isEqualTo(100);
        assertThat(f.count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", f.user.getProjectId()))
                .isEqualTo(299);
        assertThat(f.count("select count(*) from TB_TBL where PRJ_SN = ?", f.user.getProjectId())).as("루트는 한 번만").isEqualTo(300);
        List<Long> tooMany = new ArrayList<>(members.subList(199, 300));
        error(() -> generation.preview(f.user, f.request(tooMany)), ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    // ------------------------------------------------------------------ 교환 6

    @Test
    @DisplayName("교환 형식 6 : 상속 PK 매핑을 새 매핑·속성·컬럼 ID 로 옮기고 왕복해도 같다. 형식 5·잘못된 참조·교차 중복·중복은 파일 전체 거절, 아무것도 남지 않는다")
    void exchangeRoundTrip() {
        GenerationFixture f = fixture("sg-exchange");
        long party = entity(f, "당사자");
        long a = f.attr(party, "당사자 번호", true, "PTY_NO");
        long b = f.attr(party, "기관 코드", true, "ORG_CD");
        f.pk(party, a, b);
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);
        generate(f, party, org, human, dept, team);
        ModelExchangeDto.Document document = exchange.export(f.member());
        assertThat(document.formatVersion()).isEqualTo(6);
        assertThat(document.model().get("SUBTYPE_KEY_MAPPING")).hasSize(8);

        GenerationFixture t = fixture("sg-exchange-dst");
        AccountDto target = t.member();
        ModelExchangeDto.Selection selection = null; // 전부 (TABLE·모델을 함께 가져와야 매핑이 생긴다)
        var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, selection));
        assertThat(plan.counts()).containsEntry("SUBTYPE_KEY_MAPPING", 8);
        var result = exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, selection, plan.planToken(), plan.requestId()));
        assertThat(result.created()).containsEntry("SUBTYPE_KEY_MAPPING", 8);
        // 새 ID 로 다시 잇는다 : 원본 속성은 대상 프로젝트의 루트 속성, 컬럼은 대상 프로젝트의 서브타입 테이블 컬럼
        assertThat(jdbc.queryForList("select e.ENTT_NM || ':' || a.ATRB_NM || '@' || ae.ENTT_NM || '>' || c.COL_NM from TB_SBTYP_IDNTFR_COL_MPNG s"
                        + " join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = m.LGCL_ENTT_SN"
                        + " join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = s.LGCL_ATRB_SN join TB_LGCL_ENTT ae on ae.LGCL_ENTT_SN = a.LGCL_ENTT_SN join TB_COL c on c.COL_SN = s.COL_SN"
                        + " join TB_TBL ct on ct.TBL_SN = c.TBL_SN where m.PRJ_SN = ? and ae.PRJ_SN = ? and ct.PRJ_SN = ? and ct.TBL_SN = m.TBL_SN", String.class,
                target.getProjectId(), target.getProjectId(), target.getProjectId()))
                .hasSize(8).contains("부서:당사자 번호@당사자>PTY_NO", "부서:기관 코드@당사자>ORG_CD", "조직:당사자 번호@당사자>PTY_NO");
        long importedOrg = jdbc.queryForObject("select LGCL_ENTT_SN from TB_LGCL_ENTT where PRJ_SN = ? and ENTT_NM = '조직'", Long.class, target.getProjectId());
        assertThat(mappings.selectSubtypeKeys(target, jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, importedOrg)).status())
                .as("가져온 구조도 같은 판정").isEqualTo("VALID");
        ModelExchangeDto.Document again = exchange.export(target);
        assertThat(again.model().get("SUBTYPE_KEY_MAPPING")).hasSize(8);

        // 거절 : 아무것도 만들지 않는다
        List<ModelExchangeDto.Item> keys = document.model().get("SUBTYPE_KEY_MAPPING");
        ModelExchangeDto.Item first = keys.getFirst();
        Long otherColumn = document.model().get("PHYSICAL_COLUMN").stream().filter(c -> !java.util.Objects.equals(((Number) c.state().get("tableInfoId")).longValue(),
                tableOf(document, ((Number) first.state().get("mappingId")).longValue()))).findFirst().orElseThrow().id();
        Long otherAttribute = document.model().get("LOGICAL_ATTRIBUTE").stream().filter(x -> !x.id().equals(((Number) first.state().get("attributeId")).longValue()))
                .filter(x -> !"당사자 번호".equals(x.state().get("attributeName")) && !"기관 코드".equals(x.state().get("attributeName"))).findFirst().map(ModelExchangeDto.Item::id).orElse(-1L);
        GenerationFixture r = fixture("sg-exchange-refused");
        AccountDto refused = r.member();
        int before = r.count("select count(*) from TB_TBL where PRJ_SN = ?", refused.getProjectId());
        List<ModelExchangeDto.Document> bad = List.of(
                withVersion(document, 5),
                withKey(document, first, "columnId", otherColumn),
                withKey(document, first, "attributeId", otherAttribute),
                withKey(document, first, "mappingId", 999_999_999L),
                withExtraKey(document, first),
                withDirectColumn(document, first));
        for (ModelExchangeDto.Document d : bad) {
            assertThatThrownBy(() -> exchange.plan(refused, new ModelExchangeDto.PlanRequest(refused.getProjectId(), d, selection)))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID));
        }
        assertThat(r.count("select count(*) from TB_TBL where PRJ_SN = ?", refused.getProjectId())).isEqualTo(before);
        // 형식 5 파일(상속 PK 매핑 없음)은 그대로 가져온다 — 매핑만 없다
        GenerationFixture old = fixture("sg-exchange-old");
        AccountDto oldTarget = old.member();
        Map<String, List<ModelExchangeDto.Item>> withoutKeys = new LinkedHashMap<>(document.model());
        withoutKeys.remove("SUBTYPE_KEY_MAPPING");
        ModelExchangeDto.Document format5 = new ModelExchangeDto.Document(document.format(), 5, document.exportedAt(), document.dbType(), document.counts(), withoutKeys, null);
        var oldPlan = exchange.plan(oldTarget, new ModelExchangeDto.PlanRequest(oldTarget.getProjectId(), format5, selection));
        assertThat(oldPlan.counts()).doesNotContainKey("SUBTYPE_KEY_MAPPING");
        exchange.apply(oldTarget, new ModelExchangeDto.ApplyRequest(oldTarget.getProjectId(), format5, selection, oldPlan.planToken(), oldPlan.requestId()));
        assertThat(old.count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", oldTarget.getProjectId())).isZero();
    }

    private static Long tableOf(ModelExchangeDto.Document document, long mappingId) {
        return document.model().get("TABLE_MAPPING").stream().filter(m -> m.id() == mappingId).findFirst().map(m -> ((Number) m.state().get("tableId")).longValue()).orElseThrow();
    }

    private static ModelExchangeDto.Document withVersion(ModelExchangeDto.Document d, int version) {
        return new ModelExchangeDto.Document(d.format(), version, d.exportedAt(), d.dbType(), d.counts(), d.model(), null);
    }

    private static ModelExchangeDto.Document withKey(ModelExchangeDto.Document d, ModelExchangeDto.Item key, String field, Object value) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(d.model());
        model.put("SUBTYPE_KEY_MAPPING", model.get("SUBTYPE_KEY_MAPPING").stream().map(item -> {
            if (!item.id().equals(key.id())) return item;
            Map<String, Object> state = new LinkedHashMap<>(item.state());
            state.put(field, value);
            return new ModelExchangeDto.Item(item.id(), item.name(), state);
        }).toList());
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), d.counts(), model, null);
    }

    /** 같은 (매핑, 속성) 을 한 번 더 — 컬럼도 같아 중복 */
    private static ModelExchangeDto.Document withExtraKey(ModelExchangeDto.Document d, ModelExchangeDto.Item key) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(d.model());
        List<ModelExchangeDto.Item> keys = new ArrayList<>(model.get("SUBTYPE_KEY_MAPPING"));
        keys.add(new ModelExchangeDto.Item(999_999_998L, key.name(), key.state()));
        model.put("SUBTYPE_KEY_MAPPING", keys);
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), d.counts(), model, null);
    }

    /** 상속 PK 매핑 컬럼을 속성 매핑에도 넣는다 (교차 중복) */
    private static ModelExchangeDto.Document withDirectColumn(ModelExchangeDto.Document d, ModelExchangeDto.Item key) {
        Map<String, List<ModelExchangeDto.Item>> model = new LinkedHashMap<>(d.model());
        List<ModelExchangeDto.Item> direct = new ArrayList<>(model.getOrDefault("COLUMN_MAPPING", List.of()));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mappingId", key.state().get("mappingId"));
        state.put("attributeId", key.state().get("attributeId"));
        state.put("columnId", key.state().get("columnId"));
        direct.add(new ModelExchangeDto.Item(999_999_997L, null, state));
        model.put("COLUMN_MAPPING", direct);
        return new ModelExchangeDto.Document(d.format(), d.formatVersion(), d.exportedAt(), d.dbType(), d.counts(), model, null);
    }
}
