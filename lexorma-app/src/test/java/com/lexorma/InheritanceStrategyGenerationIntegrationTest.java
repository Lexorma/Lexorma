package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.TablePlan;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 상속 A형(SINGLE_TABLE)·C형(TABLE_PER_CONCRETE) 물리 생성 (docs/completed/inheritance-strategy-ac-m7.md, D-AC-01~18). 저장된 행을 직접 확인한다. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InheritanceStrategyGenerationIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired TableDesignService tableDesign;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    static final String A = "SINGLE_TABLE";
    static final String C = "TABLE_PER_CONCRETE";

    /** 회원 모델 (AC-M01) : 회원(회원번호 PK, 회원명 필수, 회원아이디 AK, 회원유형코드 분류) / 개인회원(생년월일 필수) / 기업회원(사업자번호 AK, 기업명) */
    record Member(GenerationFixture f, long member, long memberNo, long memberName, long memberId, long type, long person, long birth, long corp, long bizNo,
                  long corpName, long groupId) {
    }

    Member memberModel(String name, boolean overlapping, boolean total, String personValue, String corpValue) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        insertWord(f, "회원", "MBR");
        long member = entity(f, "회원");
        long memberNo = f.attr(member, "회원 번호", true, "MBR_NO");
        f.pk(member, memberNo);
        long memberName = f.attr(member, "회원 이름", true, "MBR_NM");
        long memberId = f.attr(member, "회원 아이디", true, "MBR_ID");
        ak(f, member, "회원 아이디 식별자", memberId);
        long type = f.attr(member, "회원 유형 코드", true, "MBR_TY_CD");
        long person = entity(f, "개인 회원");
        long birth = f.attr(person, "생년월일", true, "BRDT");
        long corp = entity(f, "기업 회원");
        long bizNo = f.attr(corp, "사업자 번호", false, "BRNO");
        ak(f, corp, "사업자 번호 식별자", bizNo);
        long corpName = f.attr(corp, "기업 이름", false, "ENTP_NM");
        LogicalSubtypeDto.Group group = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "회원 구분", null, overlapping, total, type,
                List.of(new MemberInput(person, personValue), new MemberInput(corp, corpValue)), null));
        return new Member(f, member, memberNo, memberName, memberId, type, person, birth, corp, bizNo, corpName, group.id());
    }

    long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    void insertWord(GenerationFixture f, String word, String abbr) {
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), word, abbr, abbr, word);
    }

    void ak(GenerationFixture f, long entity, String name, long... attributes) {
        List<Long> ids = new ArrayList<>();
        for (long a : attributes) ids.add(a);
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, name, false, ids, null));
    }

    static PhysicalGenerationDto.Request request(List<Long> entities, String token, Long root, String strategy) {
        return new PhysicalGenerationDto.Request(entities, null, token, null, List.of(new PhysicalGenerationDto.StrategyChoice(root, strategy)));
    }

    Plan preview(GenerationFixture f, List<Long> entities, long root, String strategy) {
        return generation.preview(f.user, request(entities, null, root, strategy));
    }

    PhysicalGenerationDto.Result generate(GenerationFixture f, List<Long> entities, long root, String strategy) {
        Plan plan = preview(f, entities, root, strategy);
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        return generation.apply(f.user, request(entities, plan.planToken(), root, strategy));
    }

    static TablePlan table(Plan plan, long entity) {
        return plan.tables().stream().filter(t -> t.entityId().equals(entity)).findFirst().orElseThrow();
    }

    List<String> columns(long table) {
        return jdbc.queryForList("select COL_NM, PK_YN, NULL_ALWNC_YN from TB_COL where TBL_SN = ? order by SORT_ORD", table).stream()
                .map(r -> r.get("COL_NM") + ":" + (Boolean.TRUE.equals(r.get("PK_YN")) ? "PK" : Boolean.TRUE.equals(r.get("NULL_ALWNC_YN")) ? "NULL" : "NN")).toList();
    }

    List<String> uniques(long table) {
        return jdbc.queryForList("select k.CNSTRT_NM from TB_TBL_CNSTRT k where k.TBL_SN = ? and k.CNSTRT_TYPE_CD = 'UNIQUE' order by k.CNSTRT_NM", String.class, table);
    }

    // ------------------------------------------------------------------ A형

    @Test
    @DisplayName("AC-A01·02·07·08·15·16·22·23·28·29 : A형 — 테이블 1개(루트), 루트 PK, 루트 필수 NOT NULL·서브타입 필수 NULL 허용, 구성원 자체 AK UNIQUE, 상속 FK·CHECK 없음, 루트 직접 매핑 + 서브타입 속성은 루트 매핑 아래, 변환 기록")
    void singleTable() {
        Member m = memberModel("ac-a01", false, true, "P", "C");
        GenerationFixture f = m.f();
        List<Long> all = List.of(m.member(), m.person(), m.corp());
        Plan plan = preview(f, all, m.member(), A);
        assertThat(table(plan, m.member()).status()).isEqualTo("CREATE");
        assertThat(table(plan, m.person()).status()).isEqualTo("MERGED");
        assertThat(table(plan, m.corp()).status()).isEqualTo("MERGED");
        assertThat(plan.strategies()).singleElement().satisfies(s -> {
            assertThat(s.strategy()).isEqualTo(A);
            assertThat(s.status()).isEqualTo("READY");
            assertThat(s.tables()).isOne();
            assertThat(s.entities()).isEqualTo(3);
            assertThat(s.columns()).isEqualTo(7);
            assertThat(s.uniques()).isEqualTo(2);
        });
        assertThat(plan.conversions()).singleElement().satisfies(c -> {
            assertThat(c.strategy()).isEqualTo(A);
            assertThat(c.classificationColumnName()).isEqualTo("MBR_TY_CD");
        });

        PhysicalGenerationDto.Result result = generation.apply(f.user, request(all, plan.planToken(), m.member(), A));
        assertThat(result.tables()).isOne();
        assertThat(result.conversions()).isOne();
        assertThat(result.inheritanceForeignKeys()).isZero();
        long table = f.table(m.member());
        assertThat(columns(table)).containsExactly("MBR_NO:PK", "MBR_NM:NN", "MBR_ID:NN", "MBR_TY_CD:NN", "BRDT:NULL", "BRNO:NULL", "ENTP_NM:NULL");
        assertThat(uniques(table)).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", Integer.class, table)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'CHECK'", Integer.class, table)).isZero();
        // 매핑 : 서브타입은 엔터티 매핑 없음, 서브타입 속성은 루트 매핑 아래 직접 속성 매핑
        assertThat(jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN in (?, ?)", Integer.class, m.person(), m.corp())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG p on p.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where p.LGCL_ENTT_SN = ?",
                Integer.class, m.member())).isEqualTo(7);
        Map<String, Object> record = jdbc.queryForMap("select CNVR_TYPE_CD, TBL_SN, CLSF_COL_SN from TB_SBTYP_GROUP_CNVR where LGCL_SBTYP_GROUP_SN = ?", m.groupId());
        assertThat(record.get("CNVR_TYPE_CD")).isEqualTo(A);
        assertThat(((Number) record.get("TBL_SN")).longValue()).isEqualTo(table);
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, record.get("CLSF_COL_SN"))).isEqualTo("MBR_TY_CD");
        String ddl = tableDesign.generateTableDdl(table, f.user, null).ddl();
        assertThat(ddl).doesNotContain("CHECK (").doesNotContain("FOREIGN KEY");
    }

    @Test
    @DisplayName("AC-A09·10·11·13 : A형 차단 — 분류 값 없음·중복, Overlapping")
    void singleTableBlocked() {
        Member missing = memberModel("ac-a10", false, true, "P", null);
        Plan plan = preview(missing.f(), List.of(missing.member(), missing.person(), missing.corp()), missing.member(), A);
        assertThat(plan.creatable()).isFalse();
        assertThat(table(plan, missing.member()).errors()).anyMatch(e -> e.contains("분류 값이 없어"));

        Member overlapping = memberModel("ac-a13", true, false, "P", "C");
        plan = preview(overlapping.f(), List.of(overlapping.member(), overlapping.person(), overlapping.corp()), overlapping.member(), A);
        assertThat(plan.creatable()).isFalse();
        assertThat(table(plan, overlapping.member()).errors()).anyMatch(e -> e.contains("Overlapping"));
    }

    // ------------------------------------------------------------------ C형

    @Test
    @DisplayName("AC-T01·02·04·06·08·09·11·12·13·24 : C형 — 말단 테이블 2개, 루트 테이블 없음, 루트 PK 복제(상속 PK 매핑), 조상 속성 복제(상속 일반 속성 매핑), 필수 그대로, 자체·상속 AK UNIQUE, 상속 FK 없음")
    void concrete() {
        Member m = memberModel("ac-t01", false, true, "P", "C");
        GenerationFixture f = m.f();
        List<Long> all = List.of(m.member(), m.person(), m.corp());
        Plan plan = preview(f, all, m.member(), C);
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        assertThat(table(plan, m.member()).status()).isEqualTo("DISTRIBUTED");
        PhysicalGenerationDto.Result result = generation.apply(f.user, request(all, plan.planToken(), m.member(), C));
        assertThat(result.tables()).isEqualTo(2);
        assertThat(result.conversions()).isOne();
        long person = f.table(m.person());
        long corp = f.table(m.corp());
        assertThat(jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Integer.class, m.member())).isZero();
        assertThat(columns(person)).containsExactly("MBR_NO:PK", "MBR_NM:NN", "MBR_ID:NN", "MBR_TY_CD:NN", "BRDT:NN");
        assertThat(columns(corp)).containsExactly("MBR_NO:PK", "MBR_NM:NN", "MBR_ID:NN", "MBR_TY_CD:NN", "BRNO:NULL", "ENTP_NM:NULL");
        assertThat(uniques(person)).hasSize(1);
        assertThat(uniques(corp)).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN in (?, ?)", Integer.class, person, corp)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG s join TB_ENTT_TBL_MPNG p on p.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where p.LGCL_ENTT_SN in (?, ?)",
                Integer.class, m.person(), m.corp())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_SBTYP_ATRB_COL_MPNG s join TB_ENTT_TBL_MPNG p on p.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where p.LGCL_ENTT_SN in (?, ?)",
                Integer.class, m.person(), m.corp())).isEqualTo(6);
        assertThat(jdbc.queryForObject("select CNVR_TYPE_CD from TB_SBTYP_GROUP_CNVR where LGCL_SBTYP_GROUP_SN = ?", String.class, m.groupId())).isEqualTo(C);
    }

    @Test
    @DisplayName("AC-T17·18 : C형 차단 — Partial, Overlapping")
    void concreteBlocked() {
        Member partial = memberModel("ac-t17", false, false, "P", "C");
        Plan plan = preview(partial.f(), List.of(partial.member(), partial.person(), partial.corp()), partial.member(), C);
        assertThat(plan.creatable()).isFalse();
        assertThat(table(plan, partial.member()).errors()).anyMatch(e -> e.contains("Partial"));
    }

    @Test
    @DisplayName("D-AC-03 : 계층 일부만 고르면 막고 빠진 엔터티를 알린다, 루트가 아닌 엔터티에 전략 지정은 400")
    void wholeTreeRequired() {
        Member m = memberModel("ac-sel", false, true, "P", "C");
        Plan plan = preview(m.f(), List.of(m.member(), m.person()), m.member(), A);
        assertThat(plan.creatable()).isFalse();
        assertThat(plan.strategies().get(0).missingEntities()).extracting(PhysicalGenerationDto.EntityRef::entityId).containsExactly(m.corp());
        assertThatThrownBy(() -> preview(m.f(), List.of(m.member(), m.person(), m.corp()), m.person(), A))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL));
    }
}
