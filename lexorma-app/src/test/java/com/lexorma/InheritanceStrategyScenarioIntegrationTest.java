package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.RelationPlan;
import com.lexorma.data.mapping.service.ForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.InheritanceForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.UniqueCorrectionService;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 상속 A형·C형 시나리오 (docs/completed/inheritance-strategy-ac-m7.md) : 다단계 회원 계층 + 외부 관계(지점 → 회원, 회원·기업 회원·외국 기업 회원 → 주문).
 * 생성 구조·관계 정책(D-AC-09·10·17)·정합성·매핑률(D-AC-16)·보정 차단(D-AC-13)·기록 삭제(D-AC-18)·혼합 차단(D-AC-11)·토큰 무효화·스냅샷 10·교환 7. H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InheritanceStrategyScenarioIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired ModelMappingQualityService quality;
    @Autowired ForeignKeyCorrectionService fkCorrection;
    @Autowired UniqueCorrectionService ukCorrection;
    @Autowired InheritanceForeignKeyCorrectionService inhCorrection;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired TableDesignService tableDesign;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    static final String A = "SINGLE_TABLE";
    static final String C = "TABLE_PER_CONCRETE";

    /** 회원(PK 회원번호, 회원명·회원아이디(AK)·회원유형코드(분류 1)·지점코드) → 개인 회원(생년월일 필수) · 기업 회원(사업자번호 AK, 기업유형코드 분류 2) → 국내(국내번호) · 외국(국가코드 필수, 외국등록번호 AK) */
    record Model(GenerationFixture f, long member, long memberNo, long branchCd, long person, long corp, long domestic, long foreign, long order, long branch,
                 long rBranch, long rMember, long rCorp, long rForeign, long g1, long g2, long memberId, long bizNo) {
        List<Long> all() {
            return List.of(member, person, corp, domestic, foreign, order, branch);
        }
    }

    long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    void word(GenerationFixture f, String word, String abbr) {
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), word, abbr, abbr, word);
    }

    void ak(GenerationFixture f, long entity, String name, long attribute) {
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, name, false, List.of(attribute), null));
    }

    Model model(String name, boolean total) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        word(f, "회원", "MBR");
        word(f, "국내", "DMST");
        long member = entity(f, "회원");
        long memberNo = f.attr(member, "회원 번호", true, "MBR_NO");
        f.pk(member, memberNo);
        f.attr(member, "회원 이름", true, "MBR_NM");
        long memberId = f.attr(member, "회원 아이디", true, "MBR_ID");
        ak(f, member, "회원 아이디 식별자", memberId);
        long type = f.attr(member, "회원 유형 코드", true, "MBR_TY_CD");
        long branchCd = f.attr(member, "지점 코드", false, "BRNCH_CD");
        long person = entity(f, "개인 회원");
        f.attr(person, "생년월일", true, "BRDT");
        long corp = entity(f, "기업 회원");
        long bizNo = f.attr(corp, "사업자 번호", false, "BRNO");
        ak(f, corp, "사업자 번호 식별자", bizNo);
        long corpType = f.attr(corp, "기업 유형 코드", true, "CORP_TY_CD");
        long domestic = entity(f, "국내 기업 회원");
        f.attr(domestic, "국내 번호", false, "DMST_NO");
        long foreign = entity(f, "외국 기업 회원");
        f.attr(foreign, "국가 코드", true, "CNTRY_CD");
        long foreignNo = f.attr(foreign, "외국 등록 번호", false, "FRGN_REG_NO");
        ak(f, foreign, "외국 등록 번호 식별자", foreignNo);
        long g1 = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "회원 구분", null, false, total, type,
                List.of(new MemberInput(person, "P"), new MemberInput(corp, "C")), null)).id();
        long g2 = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, corp, "기업 구분", null, false, total, corpType,
                List.of(new MemberInput(domestic, "D"), new MemberInput(foreign, "F")), null)).id();
        long branch = entity(f, "지점");
        long branchId = f.attr(branch, "지점 아이디", true, "BRNCH_ID");
        f.pk(branch, branchId);
        long order = entity(f, "주문");
        long orderNo = f.attr(order, "주문 번호", true, "ORD_NO");
        f.pk(order, orderNo);
        long orderMember = f.attr(order, "주문 회원 번호", false, "ORD_MBR_NO");
        long orderCorp = f.attr(order, "주문 기업 번호", false, "ORD_CORP_NO");
        long orderForeign = f.attr(order, "주문 외국 번호", false, "ORD_FRGN_NO");
        var rBranch = f.rel("지점 소속", branch, member, false, 0);
        f.pairs(rBranch, new long[]{branchId, branchCd});
        var rMember = f.rel("회원 주문", member, order, false, 0);
        f.pairs(rMember, new long[]{memberNo, orderMember});
        var rCorp = f.rel("기업 주문", corp, order, false, 0);
        f.pairs(rCorp, new long[]{memberNo, orderCorp});
        var rForeign = f.rel("외국 주문", foreign, order, false, 0);
        f.pairs(rForeign, new long[]{memberNo, orderForeign});
        return new Model(f, member, memberNo, branchCd, person, corp, domestic, foreign, order, branch, rBranch.id(), rMember.id(), rCorp.id(), rForeign.id(), g1, g2,
                memberId, bizNo);
    }

    static PhysicalGenerationDto.Request request(List<Long> entities, String token, long root, String strategy) {
        return new PhysicalGenerationDto.Request(entities, null, token, null, List.of(new PhysicalGenerationDto.StrategyChoice(root, strategy)));
    }

    Plan preview(Model m, String strategy) {
        return generation.preview(m.f().user, request(m.all(), null, m.member(), strategy));
    }

    PhysicalGenerationDto.Result generate(Model m, String strategy) {
        Plan plan = preview(m, strategy);
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        return generation.apply(m.f().user, request(m.all(), plan.planToken(), m.member(), strategy));
    }

    static List<RelationPlan> relations(Plan plan, long relationship) {
        return plan.relations().stream().filter(r -> r.relationshipId().equals(relationship)).toList();
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    List<ModelMappingQualityDto.Row> rows(Model m, ModelMappingQualityDto.CheckGroup group) {
        return quality.selectRows(m.f().user, new ModelMappingQualityDto.Search(m.f().modelId, null, null, null, null, null, group), PageRequest.of(0, 100_000)).getContent();
    }

    static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).as(e.getMessage()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ A형

    @Test
    @DisplayName("AC-A04·05·18·19·20·22 : A형 다단계 — 통합 테이블 1개(계층 5 엔터티의 직접 속성 11 컬럼), 그룹별 분류 컬럼·변환 기록 2건, AK UNIQUE 3, 루트 참조 FK 생성, 서브타입 참조 FK 제외, 서브타입·루트의 외부 참조 FK, 상속 FK 없음")
    void singleTableMultiLevel() {
        Model m = model("ac-a-multi", true);
        Plan plan = preview(m, A);
        assertThat(plan.strategies()).singleElement().satisfies(s -> {
            assertThat(s.tables()).isOne();
            assertThat(s.columns()).isEqualTo(11);
            assertThat(s.uniques()).isEqualTo(3);
            assertThat(s.mergedEntities()).hasSize(4);
        });
        assertThat(plan.conversions()).hasSize(2).allMatch(c -> c.classificationColumnName() != null);
        assertThat(relations(plan, m.rBranch())).singleElement().satisfies(r -> assertThat(r.fkCreated()).isTrue());
        assertThat(relations(plan, m.rMember())).singleElement().satisfies(r -> assertThat(r.fkCreated()).isTrue());
        assertThat(relations(plan, m.rCorp())).singleElement().satisfies(r -> {
            assertThat(r.fkStatus()).isEqualTo("STRATEGY_EXCLUDED");
            assertThat(r.reasons()).contains("SUBTYPE_SCOPE_NOT_ENFORCED");
        });
        assertThat(relations(plan, m.rForeign())).singleElement().satisfies(r -> assertThat(r.fkStatus()).isEqualTo("STRATEGY_EXCLUDED"));
        PhysicalGenerationDto.Result result = generation.apply(m.f().user, request(m.all(), plan.planToken(), m.member(), A));
        assertThat(result.tables()).isEqualTo(3);
        assertThat(result.conversions()).isEqualTo(2);
        assertThat(result.excludedRelationshipIds()).containsExactlyInAnyOrder(m.rCorp(), m.rForeign());
        long mbr = m.f().table(m.member());
        assertThat(count("select count(*) from TB_COL where TBL_SN = ?", mbr)).isEqualTo(11);
        assertThat(count("select count(*) from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", mbr)).isEqualTo(3);
        assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", mbr, m.f().table(m.branch()))).isOne();
        assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", m.f().table(m.order()))).as("주문 → 회원만 (서브타입 참조 제외)").isOne();
        assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ? and CNVR_TYPE_CD = 'SINGLE_TABLE' and TBL_SN = ?", m.f().user.getProjectId(), mbr)).isEqualTo(2);

        // 매핑 목록·매핑률 (D-AC-16) : 서브타입은 통합 매핑 (미매핑 아님) — 엔터티 7개 중 직접 3 + 통합 4 = 7 / 7
        ModelMappingDto.Status status = mappings.selectStatus(m.f().user, m.f().modelId).get(0);
        assertThat(status.mergedEntities()).isEqualTo(4);
        assertThat(status.mappedEntities()).isEqualTo(7);
        assertThat(status.totalEntities()).isEqualTo(7);
        var rows = mappings.selectEntityRows(m.f().user, new ModelMappingDto.EntitySearch(m.f().modelId, null, false, null, null), PageRequest.of(0, 100));
        assertThat(rows.getContent()).as("미매핑 필터에 통합 엔터티가 나오지 않는다").isEmpty();
        var foreign = mappings.selectEntityRows(m.f().user, new ModelMappingDto.EntitySearch(m.f().modelId, null, null, null, null, m.foreign()), PageRequest.of(0, 1)).getContent().get(0);
        assertThat(foreign.physical().kind()).isEqualTo("MERGED");
        assertThat(foreign.physical().valid()).isTrue();

        // 정합성 : 서브타입 필수 속성 = 통합 테이블 NULL 허용 → MATCH + 유형 안 필수 미강제, [상속 구조] 루트·구성원 MATCH
        List<ModelMappingQualityDto.Row> attribute = rows(m, ModelMappingQualityDto.CheckGroup.ATTRIBUTE);
        assertThat(attribute).filteredOn(r -> r.type() == ModelMappingQualityDto.CheckType.REQUIRED && "국가 코드".equals(r.attributeName())).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ModelMappingQualityDto.CheckStatus.MATCH);
            assertThat(r.notEnforced()).contains("CONDITIONAL_REQUIRED_NOT_ENFORCED");
        });
        assertThat(attribute).noneMatch(r -> r.status() == ModelMappingQualityDto.CheckStatus.MISMATCH || r.status() == ModelMappingQualityDto.CheckStatus.UNMAPPED);
        List<ModelMappingQualityDto.Row> inheritance = rows(m, ModelMappingQualityDto.CheckGroup.INHERITANCE);
        assertThat(inheritance).filteredOn(r -> r.code() != null).extracting(ModelMappingQualityDto.Row::status).containsOnly(ModelMappingQualityDto.CheckStatus.MATCH);
        assertThat(inheritance).filteredOn(r -> r.code() != null).hasSize(5);
        List<ModelMappingQualityDto.Row> relationship = rows(m, ModelMappingQualityDto.CheckGroup.RELATIONSHIP);
        assertThat(relationship).filteredOn(r -> r.structure().logicalId().equals(m.rCorp())).singleElement()
                .satisfies(r -> assertThat(r.structure().code()).isEqualTo("SUBTYPE_SCOPE_NOT_ENFORCED"));
        assertThat(relationship).filteredOn(r -> r.structure().logicalId().equals(m.rMember())).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(ModelMappingQualityDto.CheckStatus.MATCH));
        assertThat(rows(m, ModelMappingQualityDto.CheckGroup.PHYSICAL_ONLY)).as("통합 테이블의 FK·UNIQUE 를 물리 전용으로 보지 않는다").isEmpty();
        assertThat(rows(m, ModelMappingQualityDto.CheckGroup.ALTERNATE_KEY)).extracting(ModelMappingQualityDto.Row::status)
                .containsOnly(ModelMappingQualityDto.CheckStatus.MATCH);

        // 보정 차단 (D-AC-13) : UNIQUE 보정·FK 보정이 A형 계층 대상을 다루지 않는다
        assertThat(ukCorrection.status(m.f().user, m.f().modelId, List.of(jdbc.queryForObject(
                "select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and BSC_YN = false", Long.class, m.corp())))).singleElement()
                .satisfies(i -> assertThat(i.blockers()).anyMatch(b -> b.code() == com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Code.STRATEGY_EXCLUDED));
        assertThat(fkCorrection.status(m.f().user, m.f().modelId, List.of(m.rCorp()))).singleElement()
                .satisfies(i -> assertThat(i.blockers()).anyMatch(b -> b.code() == com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Code.STRATEGY_EXCLUDED));

        // 혼합 차단 (D-AC-11) : A형으로 만든 계층의 서브타입을 B형으로 다시 고르면 오류
        Plan mixed = generation.preview(m.f().user, new PhysicalGenerationDto.Request(List.of(m.person()), null, null));
        assertThat(mixed.creatable()).isFalse();
        assertThat(mixed.tables().get(0).errors()).anyMatch(e -> e.contains("이미"));
        // 같은 계층 재생성 차단 (D-AC-12)
        Plan again = preview(m, A);
        assertThat(again.creatable()).isFalse();

        // 수동 매핑 규칙 : 통합 서브타입은 따로 엔터티 매핑을 만들 수 없다
        error(() -> mappings.saveEntityMapping(m.f().user, new ModelMappingDto.EntityMapping(null, m.person(), m.f().table(m.order()))), ErrorCode.INVALID_MODEL_MAPPING);
    }

    @Test
    @DisplayName("D-AC-18 : 변환 기록은 관련 매핑이 있으면 지울 수 없고, 매핑을 모두 해제하면 지울 수 있다. 기록이 있는 그룹·통합 테이블·분류 컬럼은 지우지 않는다. 기록 삭제 뒤 그 계층을 다시 만들 수 있다")
    void conversionLifecycle() {
        Model m = model("ac-life", true);
        generate(m, A);
        AccountDto user = m.f().user;
        long mbr = m.f().table(m.member());
        error(() -> mappings.deleteConversions(user, m.member(), null), ErrorCode.RELATION_EXISTS);
        error(() -> subtypes.deleteGroup(user, m.g2(), jdbc.queryForObject("select MDFCN_DT from TB_LGCL_SBTYP_GROUP where LGCL_SBTYP_GROUP_SN = ?",
                java.sql.Timestamp.class, m.g2()).toLocalDateTime()), ErrorCode.RELATION_EXISTS);
        assertThat(mappings.selectConversions(user, m.f().modelId)).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(A);
            assertThat(t.deletable()).isFalse();
            assertThat(t.dependencies()).isNotEmpty();
        });
        // 매핑 해제 : 속성 매핑(통합 컬럼 포함) → 엔터티 매핑
        long mapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, m.member());
        for (Long id : jdbc.queryForList("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mapping)) mappings.deleteAttributeMapping(user, id, null);
        mappings.deleteEntityMapping(user, mapping, null);
        // 매핑만 해제한 상태 : 통합 엔터티는 정상 통합이 아니다 (미매핑으로 센다 — 위장하지 않는다)
        assertThat(mappings.selectStatus(user, m.f().modelId).get(0).mergedEntities()).isZero();
        // 테이블은 기록이 쓰고 있어 지울 수 없다 (컬럼이 있어 원래도 막히지만 기록 검사가 먼저 — 메시지)
        assertThat(mappings.deleteConversions(user, m.member(), null)).isEqualTo(2);
        assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ?", user.getProjectId())).isZero();
        assertThat(count("select count(*) from TB_TBL where TBL_SN = ?", mbr)).as("물리 테이블은 그대로").isOne();
    }

    @Test
    @DisplayName("planToken : 미리보기 뒤 분류 값이 바뀌면 409, 생성 실패 시 전체 롤백 (테이블·매핑·기록·버전 없음)")
    void tokenAndRollback() {
        Model m = model("ac-token", true);
        Plan plan = preview(m, A);
        AccountDto user = m.f().user;
        var group = subtypes.selectGroup(user, m.g1());
        subtypes.saveGroup(user, new LogicalSubtypeDto.Save(m.g1(), m.f().modelId, m.member(), group.groupName(), null, false, true, group.classificationAttributeId(),
                List.of(new MemberInput(m.person(), "P1"), new MemberInput(m.corp(), "C")), group.modifiedAt()));
        int versions = m.f().versions();
        error(() -> generation.apply(user, request(m.all(), plan.planToken(), m.member(), A)), ErrorCode.MODEL_CONFLICT);
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", user.getProjectId())).isZero();
        assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ?", user.getProjectId())).isZero();
        assertThat(m.f().versions()).isEqualTo(versions);
    }

    // ------------------------------------------------------------------ C형

    @Test
    @DisplayName("AC-T03·07·13·16·19·20·21·22·23·28 : C형 다단계 — 말단 3 테이블(루트·중간 없음), 조상 속성 복제, 상속 AK UNIQUE, 루트가 자식인 FK 말단마다 복제, 분산 엔터티 참조 FK 제외, 말단 참조 FK, 매핑률(분산 분모 제외), 복제 FK 하나 누락은 그 말단만 진단, 상속 FK 보정 대상 아님")
    void concreteMultiLevel() {
        Model m = model("ac-c-multi", true);
        Plan plan = preview(m, C);
        assertThat(plan.strategies()).singleElement().satisfies(s -> {
            assertThat(s.tables()).isEqualTo(3);
            assertThat(s.distributedEntities()).hasSize(2);
        });
        assertThat(relations(plan, m.rBranch())).as("회원 → 지점 FK 를 말단마다").hasSize(3).allMatch(RelationPlan::fkCreated);
        assertThat(relations(plan, m.rMember())).singleElement().satisfies(r -> assertThat(r.reasons()).contains("SUPERTYPE_DISTRIBUTED"));
        assertThat(relations(plan, m.rCorp())).singleElement().satisfies(r -> assertThat(r.reasons()).contains("SUPERTYPE_DISTRIBUTED"));
        assertThat(relations(plan, m.rForeign())).singleElement().satisfies(r -> assertThat(r.fkCreated()).isTrue());
        PhysicalGenerationDto.Result result = generation.apply(m.f().user, request(m.all(), plan.planToken(), m.member(), C));
        assertThat(result.tables()).isEqualTo(5);
        long foreign = m.f().table(m.foreign());
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? order by SORT_ORD", String.class, foreign))
                .containsExactly("MBR_NO", "MBR_NM", "MBR_ID", "MBR_TY_CD", "BRNCH_CD", "BRNO", "CORP_TY_CD", "CNTRY_CD", "FRGN_REG_NO");
        assertThat(count("select count(*) from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", foreign)).isEqualTo(3);
        assertThat(count("select count(*) from TB_TBL_REL where PRJ_SN = ? and TRGT_TBL_SN = ?", m.f().user.getProjectId(), m.f().table(m.branch())))
                .isEqualTo(3);
        assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", m.f().table(m.order()))).as("주문 → 외국 기업 회원만").isOne();
        assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN in (?, ?)", m.member(), m.corp())).isZero();
        // 매핑률 : 엔터티 7 - 분산 2 = 5, 직접 5
        ModelMappingDto.Status status = mappings.selectStatus(m.f().user, m.f().modelId).get(0);
        assertThat(status.distributedEntities()).isEqualTo(2);
        assertThat(status.totalEntities()).isEqualTo(5);
        assertThat(status.mappedEntities()).isEqualTo(5);
        // 정합성 : 분산 엔터티 = 비교 불가(INHERITANCE_DISTRIBUTED), 말단마다 복제 속성 행(전체 일치), 관계 행 말단마다
        List<ModelMappingQualityDto.Row> inheritance = rows(m, ModelMappingQualityDto.CheckGroup.INHERITANCE);
        assertThat(inheritance).filteredOn(r -> "INHERITANCE_DISTRIBUTED".equals(r.code())).extracting(ModelMappingQualityDto.Row::entityId)
                .containsExactlyInAnyOrder(m.member(), m.corp());
        assertThat(inheritance).filteredOn(r -> "INHERITANCE_CONCRETE".equals(r.code())).hasSize(3).allMatch(r -> r.status() == ModelMappingQualityDto.CheckStatus.MATCH);
        List<ModelMappingQualityDto.Row> attribute = rows(m, ModelMappingQualityDto.CheckGroup.ATTRIBUTE);
        assertThat(attribute).filteredOn(r -> r.type() == ModelMappingQualityDto.CheckType.TERM && "회원 이름".equals(r.attributeName())).hasSize(3)
                .allMatch(r -> r.status() == ModelMappingQualityDto.CheckStatus.MATCH);
        assertThat(attribute).noneMatch(r -> r.status() == ModelMappingQualityDto.CheckStatus.MISMATCH || r.status() == ModelMappingQualityDto.CheckStatus.UNMAPPED);
        assertThat(rows(m, ModelMappingQualityDto.CheckGroup.RELATIONSHIP)).filteredOn(r -> r.structure().logicalId().equals(m.rBranch())).hasSize(3)
                .allMatch(r -> r.status() == ModelMappingQualityDto.CheckStatus.MATCH);
        assertThat(rows(m, ModelMappingQualityDto.CheckGroup.PHYSICAL_ONLY)).as("복제 FK·UNIQUE 를 물리 전용으로 보지 않는다").isEmpty();

        // 복제 FK 하나 지우기 : 그 말단 행만 FK 누락, 나머지 두 말단은 일치 (첫 FK 하나로 전체를 일치로 보지 않는다)
        long person = m.f().table(m.person());
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where TBL_SN = ? and COL_NM = 'BRNCH_CD'", person);
        jdbc.update("delete from TB_TBL_REL_COL where REL_SN in (select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ?)", person);
        jdbc.update("delete from TB_TBL_REL where ORGNL_TBL_SN = ?", person);
        List<ModelMappingQualityDto.Row> after = rows(m, ModelMappingQualityDto.CheckGroup.RELATIONSHIP).stream().filter(r -> r.structure().logicalId().equals(m.rBranch())).toList();
        assertThat(after).hasSize(3);
        assertThat(after).filteredOn(r -> r.entityId().equals(m.person())).singleElement().satisfies(r -> assertThat(r.structure().code()).isEqualTo("FK_MISSING"));
        assertThat(after).filteredOn(r -> !r.entityId().equals(m.person())).allMatch(r -> r.status() == ModelMappingQualityDto.CheckStatus.MATCH);
        // 보정 차단 : 복제 FK 누락도 FK 보정이 만들지 않는다, 말단은 상속 FK 보정 대상이 아니다
        assertThat(fkCorrection.status(m.f().user, m.f().modelId, List.of(m.rBranch()))).singleElement()
                .satisfies(i -> assertThat(i.blockers()).anyMatch(b -> b.code() == com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Code.STRATEGY_EXCLUDED));
        assertThat(inhCorrection.status(m.f().user, m.f().modelId, List.of(m.foreign()))).singleElement()
                .satisfies(i -> assertThat(i.blockers()).anyMatch(b -> b.code() == com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Code.STRATEGY_EXCLUDED));

        // 상속 일반 속성 매핑 상세 : 외국 기업 회원 = 조상 속성 6개 (회원 4 + 기업 2, 루트 PK 제외) 모두 연결
        long foreignMapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, m.foreign());
        var detail = mappings.selectSubtypeAttributes(m.f().user, foreignMapping);
        assertThat(detail.concrete()).isTrue();
        assertThat(detail.attributes()).hasSize(6).allMatch(a -> "OK".equals(a.check()));
        // 분산 엔터티는 따로 매핑할 수 없다
        error(() -> mappings.saveEntityMapping(m.f().user, new ModelMappingDto.EntityMapping(null, m.corp(), m.f().table(m.order()))), ErrorCode.INVALID_MODEL_MAPPING);
    }

    @Test
    @DisplayName("AC-T17·18·A13 : C형은 Partial 그룹이 있으면, A형은 Overlapping 그룹이 있으면 막는다. A형 분류 값 중복(숫자형 1 = 01)·지원하지 않는 타입·같은 분류 속성 공유는 막는다")
    void blockingRules() {
        Model partial = model("ac-partial", false);
        assertThat(preview(partial, C).creatable()).isFalse();
        assertThat(preview(partial, A).creatable()).as("A형은 Partial 허용").isTrue();

        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ac-classify");
        word(f, "회원", "MBR");
        long member = entity(f, "회원");
        long no = f.attr(member, "회원 번호", true, "MBR_NO");
        f.pk(member, no);
        long numeric = f.attrTyped(member, "회원 유형 번호", true, "MBR_TY_NO", "NUMERIC", 3);
        long person = entity(f, "개인 회원");
        long corp = entity(f, "기업 회원");
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "구분", null, false, true, numeric,
                List.of(new MemberInput(person, "1"), new MemberInput(corp, "01")), null));
        Plan plan = generation.preview(f.user, request(List.of(member, person, corp), null, member, A));
        assertThat(plan.creatable()).isFalse();
        assertThat(plan.tables().stream().flatMap(t -> t.errors().stream())).anyMatch(e -> e.contains("같은 값"));
    }

    // ------------------------------------------------------------------ 버전·교환

    @Test
    @DisplayName("스냅샷 10 · 교환 7 : 변환 기록·상속 일반 속성 매핑을 담고, 다른 프로젝트로 가져와도 같은 구조·같은 판정 (가져온 객체는 미승인 — DEC-22). 모순된 파일은 전체 거절")
    void versionAndExchange() {
        Model a = model("ac-x-a", true);
        generate(a, A);
        Model c = model("ac-x-c", true);
        generate(c, C);
        for (Model m : List.of(a, c)) {
            ModelVersionDto.Summary now = versions.create(m.f().member(), new ModelVersionDto.Create("ac-" + System.nanoTime(), null));
            assertThat(now.formatVersion()).isEqualTo(10);
            assertThat(now.counts().get("SUBTYPE_CONVERSION").asInt()).isEqualTo(2);
            ModelExchangeDto.Document document = exchange.export(m.f().member());
            assertThat(document.formatVersion()).isEqualTo(7);
            GenerationFixture t = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ac-x-dst");
            AccountDto target = t.member();
            var plan = exchange.plan(target, new ModelExchangeDto.PlanRequest(target.getProjectId(), document, null));
            assertThat(plan.counts()).containsEntry("SUBTYPE_CONVERSION", 2);
            exchange.apply(target, new ModelExchangeDto.ApplyRequest(target.getProjectId(), document, null, plan.planToken(), plan.requestId()));
            assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ?", target.getProjectId())).isEqualTo(2);
            assertThat(count("select count(*) from TB_SBTYP_ATRB_COL_MPNG s join TB_ENTT_TBL_MPNG p on p.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where p.PRJ_SN = ?", target.getProjectId()))
                    .isEqualTo(count("select count(*) from TB_SBTYP_ATRB_COL_MPNG s join TB_ENTT_TBL_MPNG p on p.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where p.PRJ_SN = ?", m.f().user.getProjectId()));
            assertThat(count("select count(*) from TB_TBL where PRJ_SN = ? and APRV_YN = true", target.getProjectId())).isZero();
            Long targetModel = jdbc.queryForObject("select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = ? and LGCL_MDL_SN <> ?", Long.class, target.getProjectId(), t.modelId);
            ModelMappingDto.Status source = mappings.selectStatus(m.f().user, m.f().modelId).get(0);
            ModelMappingDto.Status copied = mappings.selectStatus(target, targetModel).get(0);
            assertThat(copied.mappedEntities()).isEqualTo(source.mappedEntities());
            assertThat(copied.totalEntities()).isEqualTo(source.totalEntities());

            // 모순 : C형 기록에 테이블 번호를 넣은 파일 → 거절, 아무것도 남지 않는다
            var conversions = new ArrayList<ModelExchangeDto.Item>();
            Long anyTable = document.model().get("PHYSICAL_TABLE").get(0).id();
            for (ModelExchangeDto.Item item : document.model().get("SUBTYPE_CONVERSION")) {
                var state = new java.util.LinkedHashMap<>(item.state());
                state.put("strategy", m == a ? "TABLE_PER_CONCRETE" : "SINGLE_TABLE");
                if (m == c) state.put("tableId", anyTable);
                conversions.add(new ModelExchangeDto.Item(item.id(), item.name(), state));
            }
            var model = new java.util.LinkedHashMap<>(document.model());
            model.put("SUBTYPE_CONVERSION", conversions);
            var bad = new ModelExchangeDto.Document(document.format(), 7, document.exportedAt(), document.dbType(), document.counts(), model, null);
            GenerationFixture u = SubtypePhysicalGenerationIntegrationTest.fixture(context, "ac-x-bad");
            AccountDto other = u.member();
            error(() -> exchange.plan(other, new ModelExchangeDto.PlanRequest(other.getProjectId(), bad, null)), ErrorCode.MODEL_EXCHANGE_INVALID);
            // 형식 6 파일에 기록을 넣으면 거절
            var v6 = new ModelExchangeDto.Document(document.format(), 6, document.exportedAt(), document.dbType(), document.counts(), document.model(), null);
            error(() -> exchange.plan(other, new ModelExchangeDto.PlanRequest(other.getProjectId(), v6, null)), ErrorCode.MODEL_EXCHANGE_INVALID);
            assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", other.getProjectId())).isZero();
        }
    }
}
