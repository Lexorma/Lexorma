package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.ddl.service.DdlExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-INH-01·02 상속 공통 참조 키 (docs/completed/inheritance-reference-key-m7.md, D-INH-01~06·11·12). H2 / PostgreSQL 같은 기대값.
 * <pre>
 * 당사자 (루트, PK 당사자 번호 PTY_NO) : AK_CD (당사자 코드 PTY_CD), AK_NAT (국가 코드 NAT_CD, 등록 번호 REG_NO — 복합)
 *  └ 조직 (서브타입) : AK_BRNO (사업자 번호 BRNO), AK_BR (지점 A BR_A, 지점 B BR_B — 복합), 상위 당사자 번호 UP_PTY_NO
 *     └ 외국 조직 (서브타입의 서브타입) : AK_FRGN (외국 법인 번호 FRGN_NO)
 * 계약 (PK 계약 번호) : 위 키를 가리키는 자식 속성들
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InheritanceReferenceKeyIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingQualityService quality;
    @Autowired ForeignKeyCorrectionService fkCorrection;
    @Autowired PhysicalModelService physical;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired ColumnManageService columns;
    @Autowired DdlExportService ddlExport;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    record Fx(GenerationFixture f, Map<String, Long> e, Map<String, Long> a, Map<String, Long> k, Map<String, Long> r) {
        long table(String entity) {
            return f.table(e.get(entity));
        }
    }

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private long ak(GenerationFixture f, long entity, String name, Long... attributes) {
        return logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, name, false, List.of(attributes), null)).id();
    }

    private void group(GenerationFixture f, long supertype, String name, long... members) {
        List<LogicalSubtypeDto.MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new LogicalSubtypeDto.MemberInput(m, null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, true, false, null, list, null));
    }

    /** 논리 모델 (관계 없음) */
    Fx model(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inh-" + name);
        Map<String, Long> e = new HashMap<>();
        Map<String, Long> a = new HashMap<>();
        Map<String, Long> k = new HashMap<>();
        e.put("당사자", entity(f, "당사자"));
        e.put("조직", entity(f, "조직"));
        e.put("외국 조직", entity(f, "외국 조직"));
        e.put("계약", entity(f, "계약"));
        long party = e.get("당사자"), org = e.get("조직"), frgn = e.get("외국 조직"), contract = e.get("계약");
        a.put("PTY_NO", f.attr(party, "당사자 번호", true, "PTY_NO"));
        a.put("PTY_CD", f.attr(party, "당사자 코드", true, "PTY_CD"));
        a.put("NAT_CD", f.attr(party, "국가 코드", true, "NAT_CD"));
        a.put("REG_NO", f.attr(party, "등록 번호", true, "REG_NO"));
        f.pk(party, a.get("PTY_NO"));
        k.put("AK_CD", ak(f, party, "AK_CD", a.get("PTY_CD")));
        k.put("AK_NAT", ak(f, party, "AK_NAT", a.get("NAT_CD"), a.get("REG_NO")));
        a.put("BRNO", f.attr(org, "사업자 번호", true, "BRNO"));
        a.put("BR_A", f.attr(org, "지점 A", true, "BR_A"));
        a.put("BR_B", f.attr(org, "지점 B", true, "BR_B"));
        a.put("UP_PTY_NO", f.attr(org, "상위 당사자 번호", false, "UP_PTY_NO"));
        k.put("AK_BRNO", ak(f, org, "AK_BRNO", a.get("BRNO")));
        k.put("AK_BR", ak(f, org, "AK_BR", a.get("BR_A"), a.get("BR_B")));
        a.put("FRGN_NO", f.attr(frgn, "외국 법인 번호", true, "FRGN_NO"));
        k.put("AK_FRGN", ak(f, frgn, "AK_FRGN", a.get("FRGN_NO")));
        a.put("CNTRT_NO", f.attr(contract, "계약 번호", true, "CNTRT_NO"));
        for (String c : List.of("C_PTY_CD", "C_NAT_CD", "C_REG_NO", "C_BRNO", "C_BR_A", "C_BR_B", "C_FRGN_NO", "C_ORG_NO")) {
            a.put(c, f.attr(contract, "계약 " + c, false, c));
        }
        f.pk(contract, a.get("CNTRT_NO"));
        e.put("사람", entity(f, "사람"));
        e.put("기업", entity(f, "기업"));
        group(f, party, "당사자 구분", org, e.get("사람"));
        group(f, org, "조직 구분", frgn, e.get("기업"));
        return new Fx(f, e, a, k, new HashMap<>());
    }

    private LogicalModelDto.Relationship rel(Fx x, String name, String parent, String child, String... pairs) {
        LogicalModelDto.Relationship r = x.f().rel(name, x.e().get(parent), x.e().get(child), false, 0);
        if (pairs.length > 0) {
            long[][] list = new long[pairs.length / 2][];
            for (int i = 0; i < pairs.length; i += 2) list[i / 2] = new long[]{x.a().get(pairs[i]), x.a().get(pairs[i + 1])};
            x.f().pairs(r, list);
        }
        x.r().put(name, r.id());
        return r;
    }

    /** 자체 AK 참조 관계 7개 (T01~T04, T10·T11) + 서브타입 상속 PK 참조 1개 */
    Fx withRelationships(String name) {
        Fx x = model(name);
        rel(x, "당사자 코드", "당사자", "계약", "PTY_CD", "C_PTY_CD");
        rel(x, "당사자 국가", "당사자", "계약", "NAT_CD", "C_NAT_CD", "REG_NO", "C_REG_NO");
        rel(x, "조직 사업자", "조직", "계약", "BRNO", "C_BRNO");
        rel(x, "조직 지점", "조직", "계약", "BR_A", "C_BR_A", "BR_B", "C_BR_B");
        rel(x, "외국 법인", "외국 조직", "계약", "FRGN_NO", "C_FRGN_NO");
        rel(x, "조직 계약", "조직", "계약", "PTY_NO", "C_ORG_NO");
        return x;
    }

    Fx generated(String name) {
        Fx x = withRelationships(name);
        List<Long> chosen = List.of(x.e().get("당사자"), x.e().get("조직"), x.e().get("외국 조직"), x.e().get("계약"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(chosen));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables())).isTrue();
        generation.apply(x.f().user, x.f().request(chosen, plan.planToken()));
        return x;
    }

    Row row(Fx x, CheckGroup group, CheckType type, long logicalId) {
        return rows(x, group).stream().filter(r -> r.type() == type && r.structure() != null && Objects.equals(r.structure().logicalId(), logicalId)).findFirst().orElseThrow();
    }

    /** 상속 구조 진단의 서브타입 행 (엔터티 단위) */
    Row inheritance(Fx x, long entityId) {
        return rows(x, CheckGroup.INHERITANCE).stream().filter(r -> r.type() == CheckType.INHERITANCE && Objects.equals(r.entityId(), entityId) && r.attributeId() == null)
                .findFirst().orElseThrow();
    }

    List<Row> rows(Fx x, CheckGroup group) {
        return quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null, group), PageRequest.of(0, 100)).getContent();
    }

    Row relationship(Fx x, String name) {
        return row(x, CheckGroup.RELATIONSHIP, CheckType.RELATIONSHIP, x.r().get(name));
    }

    long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    /** FK 관계 : 출처:승인:자식>부모 컬럼 쌍(순서) */
    List<String> relations(long child, long parent) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select REL_SN, REL_SRC_CD, APRV_YN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK' order by REL_SN",
                child, parent)) {
            List<String> pairs = jdbc.queryForList("select c.COL_NM || '>' || p.COL_NM from TB_TBL_REL_COL rc join TB_COL c on c.COL_SN = rc.ORGNL_COL_SN join TB_COL p on p.COL_SN = rc.TRGT_COL_SN"
                    + " where rc.REL_SN = ? order by rc.COL_ORD", String.class, ((Number) r.get("REL_SN")).longValue());
            out.add(r.get("REL_SRC_CD") + ":" + r.get("APRV_YN") + ":" + String.join(",", pairs));
        }
        return out;
    }

    List<String> uniques(long table) {
        List<String> out = new ArrayList<>();
        for (Long c : jdbc.queryForList("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE' order by CNSTRT_SN", Long.class, table)) {
            out.add(String.join(",", jdbc.queryForList("select col.COL_NM from TB_TBL_CNSTRT_COL cc join TB_COL col on col.COL_SN = cc.COL_SN where cc.CNSTRT_SN = ? order by cc.COL_ORD",
                    String.class, c)));
        }
        return out;
    }

    void dropUnique(long table, String columns) {
        for (Long c : jdbc.queryForList("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Long.class, table)) {
            String cols = String.join(",", jdbc.queryForList("select col.COL_NM from TB_TBL_CNSTRT_COL cc join TB_COL col on col.COL_SN = cc.COL_SN where cc.CNSTRT_SN = ? order by cc.COL_ORD",
                    String.class, c));
            if (!cols.equals(columns)) continue;
            jdbc.update("delete from TB_TBL_CNSTRT_COL where CNSTRT_SN = ?", c);
            jdbc.update("delete from TB_TBL_CNSTRT where CNSTRT_SN = ?", c);
        }
    }

    static int count(String text, String word) {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + word.length())) n++;
        return n;
    }

    boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    /**
     * 44.1 : 생성된 DDL 을 격리 스키마(테스트용 임시 DB 안)에서 실제 실행하고 PK·UNIQUE·FK 수, 정상 입력·없는 부모 키 거절(단일·복합·AK 참조)을 확인한다. H2 실행에서는 건너뛴다
     */
    void executeOnPostgres(String ddl) {
        if (!postgres()) return;
        String schema = "inh_ddl_" + System.nanoTime();
        jdbc.execute((java.sql.Connection c) -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false); // 한 트랜잭션 안에서 실행하고 끝에 되돌린다 (스키마·데이터가 남지 않는다, SAVEPOINT 사용)
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("create schema " + schema + "; set local search_path to " + schema);
                try {
                    st.execute(ddl);
                    java.util.function.ToIntFunction<String> one = sql -> {
                        try (java.sql.ResultSet rs = st.executeQuery(sql)) {
                            rs.next();
                            return rs.getInt(1);
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    };
                    int pk = one.applyAsInt("select count(*) from information_schema.table_constraints where table_schema = '" + schema + "' and constraint_type = 'PRIMARY KEY'");
                    int uk = one.applyAsInt("select count(*) from information_schema.table_constraints where table_schema = '" + schema + "' and constraint_type = 'UNIQUE'");
                    int fk = one.applyAsInt("select count(*) from information_schema.table_constraints where table_schema = '" + schema + "' and constraint_type = 'FOREIGN KEY'");
                    assertThat(pk).isEqualTo(4);
                    assertThat(uk).isEqualTo(5);
                    assertThat(fk).isEqualTo(8);
                    st.execute("insert into pty(pty_no, pty_cd, nat_cd, reg_no) values ('P1', 'C1', 'KR', 'R1')");
                    st.execute("insert into org(pty_no, brno, br_a, br_b) values ('P1', 'B1', 'A', 'B')");
                    st.execute("insert into frgn_org(pty_no, frgn_no) values ('P1', 'F1')");
                    st.execute("insert into cntrt(cntrt_no, c_pty_cd, c_nat_cd, c_reg_no, c_brno, c_br_a, c_br_b, c_frgn_no, c_org_no) values ('K1', 'C1', 'KR', 'R1', 'B1', 'A', 'B', 'F1', 'P1')");
                    int rejected = 0;
                    for (String bad : List.of("insert into org(pty_no, brno, br_a, br_b) values ('NOPE', 'B2', 'A2', 'B2')",
                            "insert into frgn_org(pty_no, frgn_no) values ('NOPE', 'F2')",
                            "insert into cntrt(cntrt_no, c_brno) values ('K2', 'NOPE')",
                            "insert into cntrt(cntrt_no, c_nat_cd, c_reg_no) values ('K3', 'KR', 'NOPE')",
                            "insert into cntrt(cntrt_no, c_frgn_no) values ('K4', 'NOPE')")) {
                        st.execute("savepoint s");
                        try {
                            st.execute(bad);
                        } catch (java.sql.SQLException e) {
                            if ("23503".equals(e.getSQLState())) rejected++;
                        }
                        st.execute("rollback to savepoint s");
                    }
                    assertThat(rejected).isEqualTo(5);
                    System.out.println("PG-DDL-EXECUTED schema=" + schema + " pk=" + pk + " uk=" + uk + " fk=" + fk + " rejected=" + rejected);
                } finally {
                    c.rollback();
                    c.setAutoCommit(auto);
                }
            }
            return null;
        });
    }

    static ErrorCode code(Runnable work) {
        try {
            work.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        return null;
    }

    static String message(Runnable work) {
        try {
            work.run();
        } catch (CustomException e) {
            return e.getMessage();
        }
        return null;
    }

    // ------------------------------------------------------------------ 결함 재현 (수정 전 HEAD 에서 실패, 수정 뒤 통과)

    @Test
    @DisplayName("I-1·INH-T13 : 자체 보조 식별자를 참조하는 관계가 있는 엔터티를 슈퍼타입으로 지정해도 그 짝은 COMPLETE ALTERNATE 로 남는다 (PARENT_KEY_MISMATCH 가 되지 않는다)")
    void supertypeKeepsOwnAlternateReference() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inh-i1");
        long party = entity(f, "당사자");
        long org = entity(f, "조직");
        long contract = entity(f, "계약");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        long partyCd = f.attr(party, "당사자 코드", true, "PTY_CD");
        f.pk(party, partyNo);
        long akCd = ak(f, party, "AK_CD", partyCd);
        long contractNo = f.attr(contract, "계약 번호", true, "CNTRT_NO");
        long contractCd = f.attr(contract, "계약 당사자 코드", false, "C_PTY_CD");
        f.pk(contract, contractNo);
        LogicalModelDto.Relationship r = f.rel("당사자 코드", party, contract, false, 0);
        f.pairs(r, new long[]{partyCd, contractCd});
        assertThat(logical.selectRelationshipPairs(f.user, r.id()).state().status()).isEqualTo("COMPLETE");
        // 슈퍼타입으로 지정 (기존 관계의 부모가 상속 구조에 참여하게 된다)
        group(f, party, "당사자 구분", org, entity(f, "사람"));
        LogicalModelDto.RelationshipPairs pairs = logical.selectRelationshipPairs(f.user, r.id());
        assertThat(pairs.state().status()).isEqualTo("COMPLETE");
        assertThat(pairs.state().keyType()).isEqualTo("ALTERNATE");
        assertThat(pairs.state().keyIdentifierId()).isEqualTo(akCd);
        assertThat(pairs.parentKeys()).extracting(LogicalModelDto.ParentKey::identifierId).contains(akCd);
        // 생성 → FK(USER, 미승인)·UNIQUE, 정합성 MATCH
        List<Long> chosen = List.of(party, org, contract);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        PhysicalGenerationDto.RelationPlan relation = plan.relations().stream().filter(p -> p.relationshipId().equals(r.id())).findFirst().orElseThrow();
        assertThat(relation.fkStatus()).isEqualTo("READY");
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        Fx x = new Fx(f, Map.of("당사자", party, "조직", org, "계약", contract), Map.of(), Map.of(), Map.of("r", r.id()));
        assertThat(relations(x.table("계약"), x.table("당사자"))).containsExactly("USER:false:C_PTY_CD>PTY_CD");
        Row row = relationship(x, "r");
        assertThat(row.status()).isEqualTo(CheckStatus.MATCH);
        assertThat(row.structure().referenceKey()).isEqualTo("ALTERNATE");
    }

    @Test
    @DisplayName("I-3·INH-T47·FK-11 : 슈퍼타입 → 서브타입 일반 관계(상속 PK 가 아닌 자식 컬럼)는 상속 FK 를 후보로 잡지 않는다 — 상속 FK 가 있어도 FK_MISSING (예전 FK_COLUMNS)")
    void generalRelationshipIgnoresInheritanceForeignKey() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inh-i3");
        long party = entity(f, "당사자");
        long org = entity(f, "조직");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long upper = f.attr(org, "상위 당사자 번호", false, "UP_PTY_NO");
        group(f, party, "당사자 구분", org, entity(f, "사람"));
        List<Long> chosen = List.of(party, org);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        LogicalModelDto.Relationship up = f.rel("상위", party, org, false, 0);
        f.pairs(up, new long[]{partyNo, upper});
        Fx x = new Fx(f, Map.of("당사자", party, "조직", org), Map.of(), Map.of(), Map.of("상위", up.id()));
        Row row = relationship(x, "상위");
        assertThat(row.structure().code()).isEqualTo("FK_MISSING");
        assertThat(row.status()).isEqualTo(CheckStatus.UNMAPPED);
        // 일반 단일 PK FK 보정은 계속 보류 (M-8) — 상속 관계로 막지 않는다
        ForeignKeyCorrectionDto.Item item = fkCorrection.preview(f.user, new ForeignKeyCorrectionDto.Request(f.modelId, List.of(up.id()), null)).items().get(0);
        assertThat(item.blockers()).extracting(ForeignKeyCorrectionDto.Issue::code).containsExactly(ForeignKeyCorrectionDto.Code.SINGLE_FK_HELD);
        // 상속 구조 진단은 그대로 MATCH
        assertThat(inheritance(x, org).status()).isEqualTo(CheckStatus.MATCH);
    }

    @Test
    @DisplayName("I-4·INH-T48 : 서브타입 → 직계 슈퍼타입 테이블의 추가 일반 FK(상속 PK 쌍이 아닌 것)는 물리 기준 역방향 진단에 나온다. 상속 FK 자체는 나오지 않는다")
    void extraGeneralForeignKeyListedAsPhysicalOnly() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inh-i4");
        long party = entity(f, "당사자");
        long org = entity(f, "조직");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        f.attr(org, "상위 당사자 번호", false, "UP_PTY_NO");
        group(f, party, "당사자 구분", org, entity(f, "사람"));
        List<Long> chosen = List.of(party, org);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        long orgTable = f.table(org), partyTable = f.table(party);
        physical.saveRelation(f.user, new PhysicalModelDto.Relation(null, "FK_ORG_UP", "FK", null, orgTable, null, partyTable, null,
                List.of(new PhysicalModelDto.ColumnPair(column(orgTable, "UP_PTY_NO"), null, column(partyTable, "PTY_NO"), null)), null));
        Fx x = new Fx(f, Map.of("당사자", party, "조직", org), Map.of(), Map.of(), Map.of());
        List<Row> physicalOnly = rows(x, CheckGroup.PHYSICAL_ONLY).stream().filter(r -> r.type() == CheckType.PHYSICAL_FK).toList();
        assertThat(physicalOnly).hasSize(1);
        assertThat(physicalOnly.get(0).physicalValue()).contains("UP_PTY_NO");
    }

    @Test
    @DisplayName("I-6·INH-T53 : 사용자 단일 FK 가 있는 컬럼에 같은 대상의 참조 테이블명을 넣으면 같은 컬럼 쌍의 REF_COLUMN 관계를 만들지 않고 거절한다 (SAVED_DATA_EXISTS, 기존 USER 관계 유지)")
    void refColumnAfterUserRelationRejected() {
        Raw x = raw("i6");
        physical.saveRelation(x.f().user, new PhysicalModelDto.Relation(null, "FK_USER_REF", "FK", null, x.child(), null, x.parent(), null,
                List.of(new PhysicalModelDto.ColumnPair(x.ref(), null, x.parentPk(), null)), null));
        jdbc.update("update TB_TBL_REL set APRV_YN = false where ORGNL_TBL_SN = ?", x.child());
        ErrorCode error = code(() -> setRefTable(x, x.parentName()));
        assertThat(relations(x.child(), x.parent())).containsExactly("USER:false:C_REF>P_SN");
        assertThat(error).isEqualTo(ErrorCode.SAVED_DATA_EXISTS);
        assertThat(jdbc.queryForObject("select RFRNC_TBL_NM from TB_COL where COL_SN = ?", String.class, x.ref())).isNull();
    }

    // ------------------------------------------------------------------ M7-INH-01·02 참조 키 정책

    @Test
    @DisplayName("INH-T01~T04·T10·T11·D-INH-03·04 : 슈퍼타입·서브타입·다단계 최하위 엔터티 자체 단일·복합 AK 를 참조하는 짝은 COMPLETE ALTERNATE. 짝 편집 후보는 유효 주 식별자(정의 = 루트) + 부모 자체 AK 만 (상속받은 AK 없음 — D-INH-05)")
    void ownAlternateReferences() {
        Fx x = withRelationships("own");
        Map<String, String> keyOf = Map.of("당사자 코드", "AK_CD", "당사자 국가", "AK_NAT", "조직 사업자", "AK_BRNO", "조직 지점", "AK_BR", "외국 법인", "AK_FRGN");
        keyOf.forEach((rel, key) -> {
            LogicalModelDto.PairState state = logical.selectRelationshipPairs(x.f().user, x.r().get(rel)).state();
            assertThat(state.status()).as(rel).isEqualTo("COMPLETE");
            assertThat(state.keyType()).as(rel).isEqualTo("ALTERNATE");
            assertThat(state.keyIdentifierId()).as(rel).isEqualTo(x.k().get(key));
        });
        // 서브타입 부모의 상속 PK 참조 (유효 주 식별자 = 루트 주 식별자)
        assertThat(logical.selectRelationshipPairs(x.f().user, x.r().get("조직 계약")).state().keyType()).isEqualTo("PRIMARY");
        // 후보 : 조직 = 상속 주 식별자(정의 당사자) + 자체 AK_BRNO·AK_BR. 당사자의 AK_CD·AK_NAT 는 없다
        LogicalModelDto.RelationshipPairs org = logical.selectRelationshipPairs(x.f().user, x.r().get("조직 사업자"));
        assertThat(org.parentKeys()).extracting(k -> k.keyType() + ":" + k.identifierName() + "@" + k.ownerEntityName())
                .containsExactly("PRIMARY:식별자@당사자", "ALTERNATE:AK_BRNO@조직", "ALTERNATE:AK_BR@조직");
        LogicalModelDto.RelationshipPairs frgn = logical.selectRelationshipPairs(x.f().user, x.r().get("외국 법인"));
        assertThat(frgn.parentKeys()).extracting(LogicalModelDto.ParentKey::identifierName).containsExactly("식별자", "AK_FRGN");
        assertThat(frgn.parentCandidates()).extracting(LogicalModelDto.PairCandidate::attributeId).doesNotContain(x.a().get("BRNO"), x.a().get("PTY_CD"));
        // 엔터티별 관계 목록(ERD·상세)의 짝 판정도 같다
        assertThat(logical.selectEntityRelationships(x.f().user, x.e().get("계약"))).allSatisfy(r -> assertThat(r.attributePairs().status()).isEqualTo("COMPLETE"));
    }

    @Test
    @DisplayName("INH-T05~T08·T12 저장 거절 (API 로도 우회 불가) : 상속받은 AK(서브타입 → 조상 AK, 다단계 → 중간 엔터티 AK), AK 일부, PK·AK 혼합, 서로 다른 AK 혼합")
    void rejectedPairs() {
        Fx x = model("rej");
        LogicalModelDto.Relationship r = rel(x, "조직 계약", "조직", "계약");
        LogicalModelDto.Relationship lower = rel(x, "외국 계약", "외국 조직", "계약");
        java.util.function.BiFunction<LogicalModelDto.Relationship, long[][], String> save = (rel, pairs) -> message(() -> x.f().pairs(rel, pairs));
        String inherited = save.apply(r, new long[][]{{x.a().get("PTY_CD"), x.a().get("C_PTY_CD")}});
        assertThat(inherited).contains("상속받은 보조 식별자", "조직");
        assertThat(save.apply(lower, new long[][]{{x.a().get("BRNO"), x.a().get("C_BRNO")}})).contains("상속받은 보조 식별자", "외국 조직");
        assertThat(save.apply(lower, new long[][]{{x.a().get("NAT_CD"), x.a().get("C_NAT_CD")}, {x.a().get("REG_NO"), x.a().get("C_REG_NO")}})).contains("상속받은 보조 식별자");
        assertThat(save.apply(r, new long[][]{{x.a().get("BR_A"), x.a().get("C_BR_A")}})).contains("식별자 하나와 맞지 않습니다");
        assertThat(save.apply(r, new long[][]{{x.a().get("PTY_NO"), x.a().get("C_ORG_NO")}, {x.a().get("BRNO"), x.a().get("C_BRNO")}})).contains("식별자 하나와 맞지 않습니다");
        assertThat(save.apply(r, new long[][]{{x.a().get("BRNO"), x.a().get("C_BRNO")}, {x.a().get("BR_A"), x.a().get("C_BR_A")}})).contains("식별자 하나와 맞지 않습니다");
        // 다른 엔터티(계약)의 속성은 부모 속성이 아니다
        assertThat(save.apply(r, new long[][]{{x.a().get("CNTRT_NO"), x.a().get("C_BRNO")}})).contains("주 식별자 또는 보조 식별자 속성");
        assertThat(logical.selectRelationshipPairs(x.f().user, r.id()).state().status()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("INH-T09 : 같은 구성의 자체 AK 가 둘이면 식별자 ID 가 작은 것이 대표 (M7-AK-D05 그대로), 생성은 UNIQUE 를 하나만 만든다")
    void sameCompositionAlternate() {
        Fx x = model("dup");
        long second = ak(x.f(), x.e().get("조직"), "AK_BRNO2", x.a().get("BRNO"));
        rel(x, "조직 사업자", "조직", "계약", "BRNO", "C_BRNO");
        LogicalModelDto.PairState state = logical.selectRelationshipPairs(x.f().user, x.r().get("조직 사업자")).state();
        assertThat(state.keyIdentifierId()).isEqualTo(x.k().get("AK_BRNO")).isLessThan(second);
    }

    @Test
    @DisplayName("INH-T01~T04·T10·T11 생성 : 상속 참여 부모의 자체 AK 마다 새 테이블에 UNIQUE, 그 AK 를 참조하는 FK 는 단일도 미승인 USER 관계 (참조 테이블명 아님), 서브타입 상속 PK 참조 단일 FK 는 참조 테이블명. 정합성 모두 MATCH")
    void generation() {
        Fx x = generated("gen");
        long party = x.table("당사자"), org = x.table("조직"), frgn = x.table("외국 조직"), contract = x.table("계약");
        assertThat(uniques(party)).containsExactlyInAnyOrder("PTY_CD", "NAT_CD,REG_NO");
        assertThat(uniques(org)).containsExactlyInAnyOrder("BRNO", "BR_A,BR_B");
        assertThat(uniques(frgn)).containsExactly("FRGN_NO");
        assertThat(relations(contract, party)).containsExactlyInAnyOrder("USER:false:C_PTY_CD>PTY_CD", "USER:false:C_NAT_CD>NAT_CD,C_REG_NO>REG_NO");
        assertThat(relations(contract, org)).containsExactlyInAnyOrder("USER:false:C_BRNO>BRNO", "USER:false:C_BR_A>BR_A,C_BR_B>BR_B", "REF_COLUMN:true:C_ORG_NO>PTY_NO");
        assertThat(relations(contract, frgn)).containsExactly("USER:false:C_FRGN_NO>FRGN_NO");
        for (String r : x.r().keySet()) assertThat(relationship(x, r).status()).as(r).isEqualTo(CheckStatus.MATCH);
        assertThat(rows(x, CheckGroup.ALTERNATE_KEY)).hasSize(5).allSatisfy(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        assertThat(rows(x, CheckGroup.PHYSICAL_ONLY)).isEmpty();
        assertThat(inheritance(x, x.e().get("조직")).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(inheritance(x, x.e().get("외국 조직")).status()).isEqualTo(CheckStatus.MATCH);
        // DDL : UNIQUE 를 참조하는 FK 가 빠지거나 두 번 나오지 않는다 (상속 FK 포함)
        String ddl = ddlExport.export(x.f().user, null, "POSTGRESQL", null, false).ddl();
        assertThat(ddl).contains("REFERENCES PTY (PTY_CD)", "REFERENCES PTY (NAT_CD, REG_NO)", "REFERENCES ORG (BRNO)", "REFERENCES ORG (BR_A, BR_B)", "REFERENCES FRGN_ORG (FRGN_NO)",
                "REFERENCES ORG (PTY_NO)", "REFERENCES PTY (PTY_NO)");
        assertThat(count(ddl, "REFERENCES")).isEqualTo(count(ddl, ") REFERENCES ")).isEqualTo(8); // 계약 6 + 상속 2 (조직 → 당사자, 외국 조직 → 조직)
        executeOnPostgres(ddl);
    }

    @Test
    @DisplayName("INH-T14·T15 : 상속 계층 안의 자체 AK 자기 참조(조직 → 조직)와 교차 참조(조직 AK → 서브타입 외국 조직) — 일반 FK 로 만들고 상속 FK 와 섞이지 않는다 (MATCH)")
    void selfAndCrossReference() {
        Fx x = model("self");
        x.a().put("UP_BRNO", x.f().attr(x.e().get("조직"), "상위 사업자 번호", false, "UP_BRNO"));
        x.a().put("P_BRNO", x.f().attr(x.e().get("외국 조직"), "모 사업자 번호", false, "P_BRNO"));
        rel(x, "조직 상위", "조직", "조직", "BRNO", "UP_BRNO");
        rel(x, "모 조직", "조직", "외국 조직", "BRNO", "P_BRNO");
        List<Long> chosen = List.of(x.e().get("당사자"), x.e().get("조직"), x.e().get("외국 조직"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(chosen));
        generation.apply(x.f().user, x.f().request(chosen, plan.planToken()));
        long org = x.table("조직"), frgn = x.table("외국 조직");
        assertThat(relations(org, org)).containsExactly("USER:false:UP_BRNO>BRNO");
        assertThat(relations(frgn, org)).containsExactlyInAnyOrder("REF_COLUMN:true:PTY_NO>PTY_NO", "USER:false:P_BRNO>BRNO");
        assertThat(relationship(x, "조직 상위").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(relationship(x, "모 조직").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(inheritance(x, x.e().get("외국 조직")).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(rows(x, CheckGroup.PHYSICAL_ONLY)).isEmpty();
    }

    @Test
    @DisplayName("D-INH-02·14.3·INH-T59 : 기존 서브타입 부모에 그 순서의 UNIQUE 가 없으면 생성은 FK 를 만들지 않고(PARENT_UNIQUE_MISSING), FK 보정도 UNIQUE 보정을 먼저 요구한다. UNIQUE 보정 뒤 FK 보정 가능. 참조 UNIQUE 를 지우면 FK_KEY_INVALID (FK 유지)")
    void existingSubtypeParent() {
        Fx x = withRelationships("exist");
        List<Long> parents = List.of(x.e().get("당사자"), x.e().get("조직"), x.e().get("외국 조직"));
        PhysicalGenerationDto.Plan first = generation.preview(x.f().user, x.f().request(parents));
        generation.apply(x.f().user, x.f().request(parents, first.planToken()));
        long org = x.table("조직");
        dropUnique(org, "BRNO");
        List<Long> child = List.of(x.e().get("계약"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(child));
        PhysicalGenerationDto.RelationPlan brno = plan.relations().stream().filter(p -> p.relationshipId().equals(x.r().get("조직 사업자"))).findFirst().orElseThrow();
        assertThat(brno.fkStatus()).isEqualTo("UNSUPPORTED");
        assertThat(brno.reasons()).contains("PARENT_UNIQUE_MISSING");
        generation.apply(x.f().user, x.f().request(child, plan.planToken()));
        long contract = x.table("계약");
        assertThat(relations(contract, org)).doesNotContain("USER:false:C_BRNO>BRNO");
        assertThat(uniques(org)).containsExactly("BR_A,BR_B"); // 기존 테이블에 UNIQUE 를 더하지 않는다
        Row missing = relationship(x, "조직 사업자");
        assertThat(missing.structure().code()).isEqualTo("FK_MISSING");
        ForeignKeyCorrectionDto.Item item = fkCorrection.preview(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.r().get("조직 사업자")), null)).items().get(0);
        assertThat(item.blockers()).extracting(ForeignKeyCorrectionDto.Issue::code).containsExactly(ForeignKeyCorrectionDto.Code.PARENT_UNIQUE_MISSING);
        // UNIQUE 보정 → FK 보정 (상속 참여 부모라는 이유로 막지 않는다)
        var uniqueCorrection = context.getBean(com.lexorma.data.mapping.service.UniqueCorrectionService.class);
        var uniquePlan = uniqueCorrection.preview(x.f().user, new com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_BRNO")), null));
        assertThat(uniquePlan.applicable()).as(String.valueOf(uniquePlan.items())).isTrue();
        uniqueCorrection.apply(x.f().user, new com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_BRNO")), uniquePlan.planToken()));
        ForeignKeyCorrectionDto.Plan fkPlan = fkCorrection.preview(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.r().get("조직 사업자")), null));
        assertThat(fkPlan.applicable()).as(String.valueOf(fkPlan.items())).isTrue();
        assertThat(fkPlan.items().get(0).representation()).isEqualTo(ForeignKeyCorrectionDto.Representation.USER_RELATION);
        fkCorrection.apply(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.r().get("조직 사업자")), fkPlan.planToken()));
        assertThat(relations(contract, org)).contains("USER:false:C_BRNO>BRNO");
        assertThat(relationship(x, "조직 사업자").status()).isEqualTo(CheckStatus.MATCH);
        // INH-T59 : 참조 UNIQUE 를 지우면 FK 는 남고 진단만 FK_KEY_INVALID
        dropUnique(org, "BRNO");
        Row invalid = relationship(x, "조직 사업자");
        assertThat(invalid.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(invalid.structure().code()).isEqualTo("FK_KEY_INVALID");
        assertThat(relations(contract, org)).contains("USER:false:C_BRNO>BRNO");
    }

    @Test
    @DisplayName("INH-T16·45장 : 모델 교환 왕복(형식 그대로) — 상속 그룹·자체 AK 참조 짝·UNIQUE·AK 참조 USER FK·상속 FK 가 다른 프로젝트로 옮겨지고 정합성 결과·DDL 이 같다")
    void exchangeRoundTrip() throws Exception {
        Fx x = generated("exch");
        com.lexorma.data.exchange.service.ModelExchangeService exchange = context.getBean(com.lexorma.data.exchange.service.ModelExchangeService.class);
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        var document = exchange.export(x.f().user);
        var file = json.readValue(json.writeValueAsString(document), com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Document.class);
        GenerationFixture target = GenerationFixture.create(context, "inh-exch-target-" + System.nanoTime(), DbType.POSTGRESQL);
        Long project = target.user.getProjectId();
        var member = target.member();
        var plan = exchange.plan(member, new com.lexorma.data.exchange.domain.dto.ModelExchangeDto.PlanRequest(project, file, null));
        assertThat(plan.applicable()).as(String.valueOf(plan.warnings())).isTrue();
        exchange.apply(member, new com.lexorma.data.exchange.domain.dto.ModelExchangeDto.ApplyRequest(project, file, null, plan.planToken(), plan.requestId()));
        Long modelId = jdbc.queryForObject("select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = ? and MDL_NM = (select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?)",
                Long.class, project, x.f().modelId);
        // 구조·상속 진단만 비교 (속성 행의 용어·도메인은 교환 파일에 없다 — 대상 프로젝트에서 이름으로만 잇는다)
        java.util.function.Function<List<Row>, List<String>> summary = list -> list.stream().filter(r -> r.type().structure() || r.type() == CheckType.INHERITANCE)
                .map(r -> r.type() + "|" + r.entityName() + "|" + r.logicalValue() + "|" + r.physicalValue() + "|" + r.status() + "|" + (r.structure() == null ? "-" : r.structure().code()))
                .sorted().toList();
        List<Row> source = quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 100)).getContent();
        List<Row> copied = quality.selectRows(member, new Search(modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 100)).getContent();
        List<String> a = new ArrayList<>(summary.apply(source)), b = new ArrayList<>(summary.apply(copied));
        List<String> onlySource = new ArrayList<>(a); b.forEach(onlySource::remove);
        List<String> onlyCopy = new ArrayList<>(b); a.forEach(onlyCopy::remove);
        assertThat(onlyCopy).as("원본에만 : " + onlySource).isEmpty();
        assertThat(onlySource).isEmpty();
        assertThat(ddlExport.export(member, null, "POSTGRESQL", null, false).ddl()).isEqualTo(ddlExport.export(x.f().user, null, "POSTGRESQL", null, false).ddl());
        // 논리 관계 짝 : 상태·참조 키 유형이 같다
        List<String> pairsSource = jdbc.queryForList("select LGCL_REL_SN from TB_LGCL_REL where LGCL_MDL_SN = ? order by REL_NM", Long.class, x.f().modelId).stream()
                .map(id -> { var st = logical.selectRelationshipPairs(x.f().user, id).state(); return st.status() + ":" + st.keyType() + ":" + st.keyIdentifierName(); }).sorted().toList();
        List<String> pairsCopy = jdbc.queryForList("select LGCL_REL_SN from TB_LGCL_REL where LGCL_MDL_SN = ? order by REL_NM", Long.class, modelId).stream()
                .map(id -> { var st = logical.selectRelationshipPairs(member, id).state(); return st.status() + ":" + st.keyType() + ":" + st.keyIdentifierName(); }).sorted().toList();
        assertThat(pairsCopy).isEqualTo(pairsSource).hasSize(6);
    }

    @Test
    @DisplayName("INH-T13 확장 : 자체 AK 참조 관계가 있는 엔터티를 서브타입으로 넣어도(유효 주 식별자가 바뀜) 그 짝은 막지 않고 유지한다. 주 식별자를 쓰는 짝은 예전처럼 막는다")
    void subtypeKeepsOwnAlternateReference() {
        Fx x = model("sub");
        long extra = entity(x.f(), "개인");
        long indvCd = x.f().attr(extra, "개인 코드", true, "INDV_CD");
        long akIndv = ak(x.f(), extra, "AK_INDV", indvCd);
        long childAttr = x.f().attr(x.e().get("계약"), "계약 개인 코드", false, "C_INDV_CD");
        LogicalModelDto.Relationship r = x.f().rel("개인 코드", extra, x.e().get("계약"), false, 0);
        x.f().pairs(r, new long[]{indvCd, childAttr});
        // 기존 당사자 구분 그룹에 개인을 더한다 (개인의 유효 주 식별자 = 당사자 주 식별자)
        Long groupId = jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from TB_LGCL_SBTYP_GROUP where LGCL_MDL_SN = ? and SBTYP_GROUP_NM = '당사자 구분'", Long.class, x.f().modelId);
        LogicalSubtypeDto.Group g = subtypes.selectGroup(x.f().user, groupId);
        List<LogicalSubtypeDto.MemberInput> members = new ArrayList<>(g.subtypes().stream().map(m -> new LogicalSubtypeDto.MemberInput(m.entityId(), null)).toList());
        members.add(new LogicalSubtypeDto.MemberInput(extra, null));
        subtypes.saveGroup(x.f().user, new LogicalSubtypeDto.Save(groupId, x.f().modelId, x.e().get("당사자"), g.groupName(), null, true, false, null, members, g.modifiedAt()));
        LogicalModelDto.PairState state = logical.selectRelationshipPairs(x.f().user, r.id()).state();
        assertThat(state.status()).isEqualTo("COMPLETE");
        assertThat(state.keyIdentifierId()).isEqualTo(akIndv);
    }

    @Test
    @DisplayName("INH-T54 : 참조 테이블명(REF_COLUMN) 관계가 있는 컬럼에 같은 USER 관계를 등록하면 거절 (기존 규칙 — 참조 테이블명 컬럼은 관계 등록 불가)")
    void userAfterRefColumnRejected() {
        Raw x = raw("t54");
        setRefTable(x, x.parentName());
        assertThat(relations(x.child(), x.parent())).containsExactly("REF_COLUMN:true:C_REF>P_SN");
        assertThat(code(() -> physical.saveRelation(x.f().user, new PhysicalModelDto.Relation(null, "FK_DUP", "FK", null, x.child(), null, x.parent(), null,
                List.of(new PhysicalModelDto.ColumnPair(x.ref(), null, x.parentPk(), null)), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relations(x.child(), x.parent())).containsExactly("REF_COLUMN:true:C_REF>P_SN");
    }

    @Test
    @DisplayName("INH-T55·T57·T58 : 일괄 저장의 중복 거절은 요청 전체를 롤백한다. 다른 참조 키(AK)를 쓰는 USER 관계가 있으면 같은 컬럼의 PK 참조 테이블명은 허용 (부당한 차단 없음). 예전 중복 데이터는 다른 저장을 막지 않는다")
    void bulkRollbackAndDifferentKeys() {
        Raw x = raw("t55");
        // T55 : 다른 컬럼의 설명 변경 + 중복이 되는 참조 테이블명 — 전체 실패, 설명도 그대로
        physical.saveRelation(x.f().user, new PhysicalModelDto.Relation(null, "FK_USER_REF", "FK", null, x.child(), null, x.parent(), null,
                List.of(new PhysicalModelDto.ColumnPair(x.ref(), null, x.parentPk(), null)), null));
        java.time.LocalDateTime pkBase = jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", java.time.LocalDateTime.class, x.childPk());
        java.time.LocalDateTime refBase = jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", java.time.LocalDateTime.class, x.ref());
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(x.child());
        request.setRows(new ArrayList<>(List.of(
                ColumnBulkSaveDto.Row.builder().key("pk").id(x.childPk()).termId(x.term()).columnDesc("바꾼 설명").dataType("BIGINT").isPk("Y").isNullable("N").baseModifiedAt(pkBase).build(),
                ColumnBulkSaveDto.Row.builder().key("ref").id(x.ref()).termId(x.term()).columnDesc("설명").dataType("BIGINT").refTableName(x.parentName()).isPk("N").isNullable("Y")
                        .baseModifiedAt(refBase).build())));
        assertThat(code(() -> columns.saveColumnsBulk(x.f().user, request))).isEqualTo(ErrorCode.SAVED_DATA_EXISTS);
        assertThat(jdbc.queryForObject("select COL_EXPLN from TB_COL where COL_SN = ?", String.class, x.childPk())).isEqualTo("설명");
        assertThat(relations(x.child(), x.parent())).containsExactly("USER:true:C_REF>P_SN");

        // T57 : AK(고유값 컬럼 P_CD)를 참조하는 USER 관계와 PK 를 참조하는 참조 테이블명은 다른 관계 — 허용
        Raw y = raw("t57");
        long code = GenerationFixture.nextId();
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, PK_YN, UNQ_YN, NULL_ALWNC_YN, APRV_YN, STD_TRM_SN, SORT_ORD, MDFCN_DT)"
                + " values (?, ?, ?, ?, 'P_CD', '설명', 'BIGINT', false, true, false, false, ?, 2, timestamp '2026-11-01 10:00:00')", code, y.f().user.getProjectId(), y.parent(), y.parentName(), y.term());
        physical.saveRelation(y.f().user, new PhysicalModelDto.Relation(null, "FK_USER_AK", "FK", null, y.child(), null, y.parent(), null,
                List.of(new PhysicalModelDto.ColumnPair(y.ref(), null, code, null)), null));
        setRefTable(y, y.parentName());
        assertThat(relations(y.child(), y.parent())).containsExactlyInAnyOrder("USER:true:C_REF>P_CD", "REF_COLUMN:true:C_REF>P_SN");

        // T58 : 예전 중복(같은 쌍의 USER + REF_COLUMN, JDBC 로 만든다)은 다른 컬럼 저장·동기화를 막지 않는다 (자동 정리도 하지 않는다)
        Raw z = raw("t58");
        setRefTable(z, z.parentName());
        long legacy = GenerationFixture.nextId();
        jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, APRV_YN) values (?, ?, ?, ?, 'FK_LEGACY', 'FK', 'USER', false)",
                legacy, z.f().user.getProjectId(), z.child(), z.parent());
        jdbc.update("insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values (?, ?, ?, ?, 1)", GenerationFixture.nextId(), legacy, z.ref(), z.parentPk());
        java.time.LocalDateTime zBase = jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", java.time.LocalDateTime.class, z.childPk());
        ColumnBulkSaveDto other = new ColumnBulkSaveDto();
        other.setTableInfoId(z.child());
        other.setRows(new ArrayList<>(List.of(ColumnBulkSaveDto.Row.builder().key("pk").id(z.childPk()).termId(z.term()).columnDesc("바꾼 설명").dataType("BIGINT").isPk("Y")
                .isNullable("N").baseModifiedAt(zBase).build())));
        assertThat(columns.saveColumnsBulk(z.f().user, other).result()).isTrue();
        assertThat(relations(z.child(), z.parent())).hasSize(2);
    }

    // ------------------------------------------------------------------ 직접 만든 두 테이블 (REF_COLUMN·USER 중복)

    record Raw(GenerationFixture f, long parent, String parentName, long parentPk, long child, String childName, long childPk, long ref, long term) {
    }

    Raw raw(String tag) {
        GenerationFixture f = GenerationFixture.create(context, "inh-raw-" + tag + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long p = f.user.getProjectId();
        long schema = GenerationFixture.nextId(), parent = GenerationFixture.nextId(), child = GenerationFixture.nextId();
        String parentName = "TP_" + tag.toUpperCase(), childName = "TC_" + tag.toUpperCase();
        long term = f.term("INH_" + tag.toUpperCase(), "BIGINT", 0);
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S1', false)", schema, p);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, ?, ?, ?, true, timestamp '2026-11-01 10:00:00')", parent, p, schema, parentName);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, ?, ?, ?, true, timestamp '2026-11-01 10:00:00')", child, p, schema, childName);
        String col = "insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, PK_YN, NULL_ALWNC_YN, APRV_YN, STD_TRM_SN, SORT_ORD, MDFCN_DT)"
                + " values (?, ?, ?, ?, ?, '설명', 'BIGINT', ?, ?, false, ?, ?, timestamp '2026-11-01 10:00:00')";
        long parentPk = GenerationFixture.nextId(), childPk = GenerationFixture.nextId(), ref = GenerationFixture.nextId();
        jdbc.update(col, parentPk, p, parent, parentName, "P_SN", true, false, term, 1);
        jdbc.update(col, childPk, p, child, childName, "C_SN", true, false, term, 1);
        jdbc.update(col, ref, p, child, childName, "C_REF", false, true, term, 2);
        return new Raw(f, parent, parentName, parentPk, child, childName, childPk, ref, term);
    }

    /** 컬럼 일괄 저장으로 C_REF 의 참조 테이블명을 바꾼다 */
    void setRefTable(Raw x, String refTable) {
        java.time.LocalDateTime base = jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", java.time.LocalDateTime.class, x.ref());
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(x.child());
        request.setRows(new ArrayList<>(List.of(ColumnBulkSaveDto.Row.builder().key("ref").id(x.ref()).termId(x.term()).columnDesc("설명").dataType("BIGINT")
                .refTableName(refTable).isPk("N").isNullable("Y").baseModifiedAt(base).build())));
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(x.f().user, request);
        if (!result.result()) throw new IllegalStateException("FAIL:" + result.errors().stream().map(ColumnBulkSaveDto.RowError::message).toList());
    }
}
