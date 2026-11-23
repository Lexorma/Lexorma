package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
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
 * M-7 상속 A·C 후속 정책 (docs/completed/inheritance-strategy-ac-m7.md 8장, 2026-11-23 확정 AC-P01~P03). H2·PostgreSQL 모두에서 돈다.
 * <ul>
 *   <li>AC-P01 : A형 계층의 여러 그룹이 같은 분류 속성(ID)을 쓰면 생성 차단 — 컬럼명 충돌·값 오류와 구분, 다른 프로젝트 속성은 다른 오류</li>
 *   <li>AC-P02 : A형·C형 루트 주 식별자 필수 (AC-PK01~10) — B형 정책은 그대로</li>
 *   <li>AC-P03 : 생성 뒤 논리 변경은 기존 정책대로 허용·차단, 물리 구조·매핑·기록은 자동으로 바꾸지 않고 정합성에 차이가 나온다 (AC-Q01~10)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InheritanceStrategyPolicyIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingQualityService quality;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    static final String A = "SINGLE_TABLE";
    static final String C = "TABLE_PER_CONCRETE";

    /** 회원(PK 회원번호, 회원명, 회원유형코드 = 분류 1) → 개인 회원(생년월일) · 기업 회원(사업자번호 AK, 기업유형코드 = 분류 2) → 국내 · 외국. 지점 → 회원 관계 (짝 지점아이디 → 지점코드) */
    record Model(GenerationFixture f, long member, long memberNo, long memberName, long type, long branchCd, long person, long corp, long corpType, long bizNo,
                 long domestic, long foreign, long branch, long branchId, long rBranch, long g1, long g2) {
        List<Long> tree() {
            return List.of(member, person, corp, domestic, foreign);
        }

        List<Long> all() {
            return List.of(member, person, corp, domestic, foreign, branch);
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

    Model model(String name, boolean withPk) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        word(f, "회원", "MBR");
        word(f, "국내", "DMST");
        word(f, "특수", "SPCL");
        word(f, "기타", "ETC");
        long member = entity(f, "회원");
        long memberNo = f.attr(member, "회원 번호", true, "MBR_NO");
        if (withPk) f.pk(member, memberNo);
        long memberName = f.attr(member, "회원 이름", true, "MBR_NM");
        long type = f.attr(member, "회원 유형 코드", true, "MBR_TY_CD");
        long branchCd = f.attr(member, "지점 코드", false, "BRNCH_CD");
        long person = entity(f, "개인 회원");
        f.attr(person, "생년월일", true, "BRDT");
        long corp = entity(f, "기업 회원");
        long bizNo = f.attr(corp, "사업자 번호", false, "BRNO");
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, corp, "사업자 번호 식별자", false, List.of(bizNo), null));
        long corpType = f.attr(corp, "기업 유형 코드", true, "CORP_TY_CD");
        long domestic = entity(f, "국내 기업 회원");
        f.attr(domestic, "국내 번호", false, "DMST_NO");
        long foreign = entity(f, "외국 기업 회원");
        f.attr(foreign, "국가 코드", true, "CNTRY_CD");
        long g1 = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "회원 구분", null, false, true, type,
                List.of(new MemberInput(person, "P"), new MemberInput(corp, "C")), null)).id();
        long g2 = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, corp, "기업 구분", null, false, true, corpType,
                List.of(new MemberInput(domestic, "D"), new MemberInput(foreign, "F")), null)).id();
        long branch = entity(f, "지점");
        long branchId = f.attr(branch, "지점 아이디", true, "BRNCH_ID");
        f.pk(branch, branchId);
        var rBranch = f.rel("지점 소속", branch, member, false, 0);
        f.pairs(rBranch, new long[]{branchId, branchCd});
        return new Model(f, member, memberNo, memberName, type, branchCd, person, corp, corpType, bizNo, domestic, foreign, branch, branchId, rBranch.id(), g1, g2);
    }

    static PhysicalGenerationDto.Request request(List<Long> entities, String token, long root, String strategy) {
        return new PhysicalGenerationDto.Request(entities, null, token, null, List.of(new PhysicalGenerationDto.StrategyChoice(root, strategy)));
    }

    Plan preview(Model m, String strategy) {
        return generation.preview(m.f().user, request(m.all(), null, m.member(), strategy));
    }

    void generate(Model m, String strategy) {
        Plan plan = preview(m, strategy);
        assertThat(plan.creatable()).as(String.valueOf(errors(plan))).isTrue();
        generation.apply(m.f().user, request(m.all(), plan.planToken(), m.member(), strategy));
    }

    static List<String> errors(Plan plan) {
        return plan.tables().stream().flatMap(t -> t.errors().stream()).toList();
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    List<Row> rows(Model m, CheckGroup group) {
        return quality.selectRows(m.f().user, new ModelMappingQualityDto.Search(m.f().modelId, null, null, null, null, null, group), PageRequest.of(0, 100_000)).getContent();
    }

    Row inheritance(Model m, long entity) {
        return rows(m, CheckGroup.INHERITANCE).stream().filter(r -> r.entityId().equals(entity) && r.attributeId() == null).findFirst().orElseThrow();
    }

    /** 프로젝트의 물리 상태 지문 : 테이블·컬럼·PK 컬럼·제약·FK·매핑·변환 기록 수 */
    String physical(Model m) {
        long p = m.f().user.getProjectId();
        return List.of(
                count("select count(*) from TB_TBL where PRJ_SN = ?", p),
                count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ?", p),
                count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ? and c.PK_YN = true", p),
                count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ? and c.NULL_ALWNC_YN = false", p),
                count("select count(*) from TB_TBL_CNSTRT c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ?", p),
                count("select count(*) from TB_TBL_CNSTRT_COL k join TB_TBL_CNSTRT c on c.CNSTRT_SN = k.CNSTRT_SN join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ?", p),
                count("select count(*) from TB_TBL_REL where PRJ_SN = ?", p),
                count("select count(*) from TB_TBL_REL_COL k join TB_TBL_REL r on r.REL_SN = k.REL_SN where r.PRJ_SN = ?", p),
                count("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = ?", p),
                count("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?", p),
                count("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG a join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?", p),
                count("select count(*) from TB_SBTYP_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG e on e.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where e.PRJ_SN = ?", p),
                count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ?", p),
                count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ? and CNVR_TYPE_CD = ?", p, A)).toString();
    }

    LogicalSubtypeDto.Group group(Model m, long id) {
        return subtypes.selectGroup(m.f().user, id);
    }

    void saveGroup(Model m, long id, Long classification, List<MemberInput> members) {
        var g = group(m, id);
        subtypes.saveGroup(m.f().user, new LogicalSubtypeDto.Save(id, m.f().modelId, g.supertypeEntityId(), g.groupName(), null, g.overlapping(), g.total(),
                classification == null ? g.classificationAttributeId() : classification, members, g.modifiedAt()));
    }

    static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).as(e.getMessage()).isEqualTo(code));
    }

    long primaryIdentifier(long entity) {
        return jdbc.queryForObject("select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = ? and BSC_YN = true", Long.class, entity);
    }

    // ------------------------------------------------------------------ AC-P02 : 루트 PK

    @Test
    @DisplayName("AC-PK01·02·08·09·10 : 루트 주 식별자가 없으면 A형·C형 미리보기 생성 불가, 적용 API 직접 호출도 거절, 실패 뒤 테이블·매핑·기록·버전 잔존 없음. B형 일반 엔터티는 경고만 (기존 정책)")
    void rootKeyRequired() {
        Model m = model("ac-pk-none", false);
        AccountDto user = m.f().user;
        Plan a = preview(m, A);
        assertThat(a.creatable()).isFalse();
        assertThat(errors(a)).anyMatch(e -> e.contains("A형 통합 테이블의 PK"));
        assertThat(a.tables()).flatExtracting(PhysicalGenerationDto.TablePlan::warnings).noneMatch(w -> w.contains("PK 없이"));
        Plan c = preview(m, C);
        assertThat(c.creatable()).isFalse();
        assertThat(errors(c)).anyMatch(e -> e.contains("말단 테이블의 PK"));

        int versions = m.f().versions();
        String before = physical(m);
        for (String strategy : List.of(A, C)) {
            Plan plan = preview(m, strategy);
            assertThatThrownBy(() -> generation.apply(user, request(m.all(), plan.planToken(), m.member(), strategy))).isInstanceOf(CustomException.class);
            assertThatThrownBy(() -> generation.apply(user, request(m.all(), null, m.member(), strategy))).isInstanceOf(CustomException.class);
        }
        assertThat(physical(m)).as("AC-PK10 : 잔존 없음").isEqualTo(before);
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", user.getProjectId())).isZero();
        assertThat(m.f().versions()).isEqualTo(versions);

        // AC-PK08 : B형 일반 엔터티(상속 없음)는 주 식별자가 없어도 경고만 내고 만든다 — 바꾸지 않은 정책
        long alone = entity(m.f(), "특수");
        m.f().attr(alone, "특수 이름", false, "SPCL_NM");
        Plan b = generation.preview(user, new PhysicalGenerationDto.Request(List.of(alone), null, null));
        assertThat(b.creatable()).isTrue();
        assertThat(b.tables().get(0).warnings()).anyMatch(w -> w.contains("PK 없이"));
    }

    @Test
    @DisplayName("AC-PK03·04·05·06·07 : 단일·복합 PK 는 식별자 순서로 생성, 구성 속성이 없거나 루트 소속이 아니면 차단, 미리보기 뒤 PK 삭제는 409 (오래된 planToken 으로 PK 없는 통합 테이블을 만들지 않는다)")
    void rootKeyShapes() {
        // AC-PK03 : 단일
        Model single = model("ac-pk-single", true);
        generate(single, A);
        long mbr = single.f().table(single.member());
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and PK_YN = true order by SORT_ORD", String.class, mbr)).containsExactly("MBR_NO");
        assertThat(inheritance(single, single.member()).status()).isEqualTo(CheckStatus.MATCH);
        // 이미 있는 PK 없는 통합 테이블(정책 확정 전 생성·PK 수동 제거)은 자동으로 고치지 않고 [상속 구조] 루트 행에 불일치로 보인다
        jdbc.update("update TB_COL set PK_YN = false where TBL_SN = ?", mbr);
        assertThat(inheritance(single, single.member())).satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.MISMATCH);
            assertThat(r.reason()).contains("PK 가 없습니다");
        });
        jdbc.update("update TB_COL set PK_YN = true where TBL_SN = ? and COL_NM = 'MBR_NO'", mbr);

        // AC-PK04 : 복합 (식별자 순서 = 회원 이름, 회원 번호)
        Model composite = model("ac-pk-composite", false);
        composite.f().pk(composite.member(), composite.memberName(), composite.memberNo());
        generate(composite, A);
        long table = composite.f().table(composite.member());
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and PK_YN = true order by SORT_ORD", String.class, table)).containsExactly("MBR_NM", "MBR_NO");

        // AC-PK05 : 주 식별자에 구성 속성 없음 (저장 경로는 막는다 — 다른 경로로 들어온 데이터를 흉내 내 DB 에서 직접 만든다)
        Model empty = model("ac-pk-empty", true);
        jdbc.update("delete from TB_LGCL_IDNTFR_ATRB where LGCL_IDNTFR_SN = ?", primaryIdentifier(empty.member()));
        for (String strategy : List.of(A, C)) {
            Plan plan = preview(empty, strategy);
            assertThat(plan.creatable()).isFalse();
            assertThat(errors(plan)).anyMatch(e -> e.contains("구성 속성이 없어"));
        }

        // AC-PK06 : 주 식별자에 루트가 아닌 엔터티(개인 회원)의 속성
        Model foreignAttr = model("ac-pk-owner", true);
        long birth = jdbc.queryForObject("select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ?", Long.class, foreignAttr.person());
        jdbc.update("update TB_LGCL_IDNTFR_ATRB set LGCL_ATRB_SN = ? where LGCL_IDNTFR_SN = ?", birth, primaryIdentifier(foreignAttr.member()));
        for (String strategy : List.of(A, C)) {
            Plan plan = preview(foreignAttr, strategy);
            assertThat(plan.creatable()).isFalse();
            assertThat(errors(plan)).anyMatch(e -> e.contains("루트에 속하지 않는 속성 생년월일"));
        }

        // AC-PK07 : 미리보기(생성 가능) 뒤 루트 PK 삭제 → 적용 409, 아무것도 남지 않는다
        for (String strategy : List.of(A, C)) {
            Model stale = model("ac-pk-stale-" + strategy, true);
            Plan plan = preview(stale, strategy);
            assertThat(plan.creatable()).isTrue();
            logical.deleteIdentifier(stale.f().user, primaryIdentifier(stale.member()));
            int versions = stale.f().versions();
            error(() -> generation.apply(stale.f().user, request(stale.all(), plan.planToken(), stale.member(), strategy)), ErrorCode.MODEL_CONFLICT);
            assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", stale.f().user.getProjectId())).isZero();
            assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ?", stale.f().user.getProjectId())).isZero();
            assertThat(stale.f().versions()).isEqualTo(versions);
            assertThat(preview(stale, strategy).creatable()).isFalse();
        }
    }

    // ------------------------------------------------------------------ AC-P01 : 분류 속성 공유

    @Test
    @DisplayName("AC-P01 : 다단계에서 같은 분류 속성 ID 공유는 차단(충돌 그룹·속성 표시, 한 번만), 다른 속성이 같은 컬럼명이면 컬럼명 충돌로 따로, 값 타입 불일치, 미리보기 뒤 분류 속성 변경은 409, 다른 프로젝트 속성은 저장 거절·유효 속성 아님")
    void classificationSharing() {
        // 같은 분류 속성(회원 유형 코드)을 회원 구분·기업 구분이 함께 쓴다 (기업 회원의 유효 속성이라 논리 등록은 허용)
        Model shared = model("ac-share", true);
        saveGroup(shared, shared.g2(), shared.type(), List.of(new MemberInput(shared.domestic(), "D"), new MemberInput(shared.foreign(), "F")));
        Plan plan = preview(shared, A);
        assertThat(plan.creatable()).isFalse();
        List<String> sharing = errors(plan).stream().filter(e -> e.contains("여러 그룹")).toList();
        assertThat(sharing).singleElement().satisfies(e -> assertThat(e).contains("회원 유형 코드", "회원 구분(회원)", "기업 구분(기업 회원)"));
        assertThat(errors(plan)).noneMatch(e -> e.contains("같은 컬럼명"));
        assertThat(group(shared, shared.g2()).classificationAttributeId()).as("자동 변경 없음").isEqualTo(shared.type());
        assertThat(preview(shared, C).creatable()).as("C형은 분류 컬럼이 없어 상관없다").isTrue();

        // 서로 다른 속성이 같은 컬럼명 : 공유 오류가 아니라 컬럼명 충돌
        Model sameName = model("ac-share-name", true);
        long other = sameName.f().attr(sameName.corp(), "기업 회원 유형 코드", true, "MBR_TY_CD");
        saveGroup(sameName, sameName.g2(), other, List.of(new MemberInput(sameName.domestic(), "D"), new MemberInput(sameName.foreign(), "F")));
        Plan named = preview(sameName, A);
        assertThat(named.creatable()).isFalse();
        assertThat(errors(named)).anyMatch(e -> e.contains("같은 컬럼명(MBR_TY_CD)"));
        assertThat(errors(named)).noneMatch(e -> e.contains("여러 그룹"));

        // 값 타입 불일치 : 숫자형 분류 속성에 문자 값
        Model typed = model("ac-share-type", true);
        long numeric = typed.f().attrTyped(typed.corp(), "기업 유형 번호", true, "CORP_TY_NO", "NUMERIC", 3);
        saveGroup(typed, typed.g2(), numeric, List.of(new MemberInput(typed.domestic(), "1"), new MemberInput(typed.foreign(), "X")));
        assertThat(errors(preview(typed, A))).anyMatch(e -> e.contains("숫자형 분류 속성"));

        // 미리보기 뒤 분류 속성 변경 → 409
        Model stale = model("ac-share-stale", true);
        Plan before = preview(stale, A);
        assertThat(before.creatable()).isTrue();
        long another = stale.f().attr(stale.corp(), "기업 구분 코드", true, "CORP_SE_CD");
        saveGroup(stale, stale.g2(), another, List.of(new MemberInput(stale.domestic(), "D"), new MemberInput(stale.foreign(), "F")));
        error(() -> generation.apply(stale.f().user, request(stale.all(), before.planToken(), stale.member(), A)), ErrorCode.MODEL_CONFLICT);
        assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", stale.f().user.getProjectId())).isZero();

        // 다른 프로젝트의 분류 속성 ID : 그룹 저장이 거절, DB 에 직접 넣어도 미리보기가 '유효 속성 아님' 으로 막는다 (공유 오류로 보지 않는다)
        Model mine = model("ac-share-mine", true);
        Model theirs = model("ac-share-theirs", true);
        assertThatThrownBy(() -> saveGroup(mine, mine.g2(), theirs.corpType(), List.of(new MemberInput(mine.domestic(), "D"), new MemberInput(mine.foreign(), "F"))))
                .isInstanceOf(CustomException.class);
        jdbc.update("update TB_LGCL_SBTYP_GROUP set CLSF_ATRB_SN = ? where LGCL_SBTYP_GROUP_SN = ?", theirs.corpType(), mine.g2());
        Plan cross = preview(mine, A);
        assertThat(cross.creatable()).isFalse();
        assertThat(errors(cross)).anyMatch(e -> e.contains("유효 속성이 아닙니다"));
        assertThat(errors(cross)).noneMatch(e -> e.contains("여러 그룹"));
    }

    // ------------------------------------------------------------------ AC-P03 : 생성 뒤 논리 변경

    @Test
    @DisplayName("AC-Q01·02·03·04 : A형 생성 뒤 구성원 추가·제거, 분류 값·분류 속성 변경은 허용 — 물리 구조·매핑·기록은 그대로, [상속 구조] 에 변환 당시와의 차이(CONVERSION_BASIS_CHANGED), 되돌리면 다시 일치")
    void singleTableAfterGeneration() {
        Model m = model("ac-q-a", true);
        generate(m, A);
        String physical = physical(m);
        assertThat(inheritance(m, m.member()).status()).isEqualTo(CheckStatus.MATCH);
        assertThat(inheritance(m, m.member()).reason()).doesNotContain("비교하지 않았습니다");

        // AC-Q03 : 분류 값 P → P1 (분류 컬럼이 있다는 이유로 일치로 두지 않는다)
        saveGroup(m, m.g1(), null, List.of(new MemberInput(m.person(), "P1"), new MemberInput(m.corp(), "C")));
        Row changed = inheritance(m, m.member());
        assertThat(changed.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(changed.code()).isEqualTo("CONVERSION_BASIS_CHANGED");
        assertThat(changed.reason()).contains("개인 회원", "P", "P1");
        assertThat(physical(m)).isEqualTo(physical);
        // 되돌리면 (A → B → A) 차이가 없다
        saveGroup(m, m.g1(), null, List.of(new MemberInput(m.person(), "P"), new MemberInput(m.corp(), "C")));
        assertThat(inheritance(m, m.member()).status()).isEqualTo(CheckStatus.MATCH);

        // AC-Q01 : 구성원 추가 → 루트 행 차이, 새 구성원 속성은 통합 테이블 컬럼이 없어 미매핑, 테이블·컬럼 자동 추가 없음
        long special = entity(m.f(), "특수 회원");
        long specialAttr = m.f().attr(special, "특수 이름", false, "SPCL_NM");
        saveGroup(m, m.g1(), null, List.of(new MemberInput(m.person(), "P"), new MemberInput(m.corp(), "C"), new MemberInput(special, "S")));
        Row added = inheritance(m, m.member());
        assertThat(added.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(added.reason()).contains("특수 회원", "추가");
        assertThat(rows(m, CheckGroup.ATTRIBUTE)).filteredOn(r -> Long.valueOf(specialAttr).equals(r.attributeId()))
                .isNotEmpty().allMatch(r -> r.status() != CheckStatus.MATCH);
        assertThat(physical(m)).isEqualTo(physical);

        // AC-Q02 : 매핑 없는 구성원 제거는 허용 → 차이 사라짐. 통합 컬럼에 매핑된 구성원(개인 회원) 제거는 정책 결과대로 — 우회하지 않는다
        saveGroup(m, m.g1(), null, List.of(new MemberInput(m.person(), "P"), new MemberInput(m.corp(), "C")));
        assertThat(inheritance(m, m.member()).status()).isEqualTo(CheckStatus.MATCH);
        saveGroup(m, m.g1(), null, List.of(new MemberInput(m.person(), "P"), new MemberInput(m.corp(), "C"), new MemberInput(special, "S")));
        // 기존 정책상 구성원 제거는 허용된다 (상속 PK 매핑과 같은 계약 : 남은 매핑은 지우지 않고 불일치로 보인다). D-AC-18 기록 삭제 보호는 그대로
        saveGroup(m, m.g1(), null, List.of(new MemberInput(m.corp(), "C"), new MemberInput(special, "S")));
        Row removed = inheritance(m, m.member());
        assertThat(removed.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(removed.reason()).contains("개인 회원", "빠졌습니다");
        assertThat(rows(m, CheckGroup.ATTRIBUTE)).filteredOn(r -> r.entityId().equals(m.person()) && r.attributeId() != null).singleElement().satisfies(r -> {
            assertThat(r.status()).as("통합 매핑 아래 남은 속성 매핑은 미매핑이 아니라 유효하지 않은 매핑").isEqualTo(CheckStatus.MISMATCH);
            assertThat(r.columnName()).isEqualTo("BRDT");
            assertThat(r.code()).isEqualTo("STRATEGY_STRUCTURE_MISMATCH");
        });
        error(() -> context.getBean(com.lexorma.data.mapping.service.ModelMappingService.class).deleteConversions(m.f().user, m.member()), ErrorCode.RELATION_EXISTS);
        assertThat(physical(m)).isEqualTo(physical);

        // AC-Q04 : 기업 구분의 분류 속성 변경 → 기업 회원 행 차이 (분류 속성 이름), 루트 행은 분류 컬럼이 분류 속성 매핑 컬럼과 달라 불일치
        long another = m.f().attr(m.corp(), "기업 구분 코드", true, "CORP_SE_CD");
        saveGroup(m, m.g2(), another, List.of(new MemberInput(m.domestic(), "D"), new MemberInput(m.foreign(), "F")));
        Row corp = inheritance(m, m.corp());
        assertThat(corp.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(corp.reason()).contains("기업 유형 코드", "기업 구분 코드");
        assertThat(physical(m)).isEqualTo(physical);
        System.out.println("[AC-Q] A 최종 [상속 구조] : " + rows(m, CheckGroup.INHERITANCE).stream().map(r -> r.entityName() + "=" + r.status() + "/" + r.code()).toList());
    }

    @Test
    @DisplayName("AC-Q07·08·10 : 생성 뒤 루트 PK·자체 AK·관계 짝 변경 — 물리 PK·UNIQUE·FK 자동 변경 없음, 정합성에 차이")
    void keysAfterGeneration() {
        for (String strategy : List.of(A, C)) {
            Model m = model("ac-q-key-" + strategy, true);
            generate(m, strategy);
            String physical = physical(m);
            List<String> before = statuses(m);

            // AC-Q07 : 루트 PK 에 회원 이름 추가 (복합)
            var pk = logical.selectEntity(m.f().user, m.member()).identifiers().stream().filter(i -> Boolean.TRUE.equals(i.isPrimary())).findFirst().orElseThrow();
            logical.saveIdentifier(m.f().user, new LogicalModelDto.Identifier(pk.id(), m.member(), pk.identifierName(), true, List.of(m.memberNo(), m.memberName()), null,
                    pk.modifiedAt()));
            assertThat(physical(m)).isEqualTo(physical);
            List<String> afterPk = statuses(m);
            System.out.println("[AC-Q07 " + strategy + "] " + diff(before, afterPk));
            assertThat(afterPk).as("PK 변경이 정합성에 드러난다").isNotEqualTo(before);

            // AC-Q08 : 기업 회원 AK(사업자 번호)에 기업 유형 코드 추가
            var ak = logical.selectEntity(m.f().user, m.corp()).identifiers().stream().filter(i -> !Boolean.TRUE.equals(i.isPrimary())).findFirst().orElseThrow();
            logical.saveIdentifier(m.f().user, new LogicalModelDto.Identifier(ak.id(), m.corp(), ak.identifierName(), false, List.of(m.bizNo(), m.corpType()), null, ak.modifiedAt()));
            assertThat(physical(m)).isEqualTo(physical);
            assertThat(rows(m, CheckGroup.ALTERNATE_KEY)).filteredOn(r -> r.structure() != null && r.structure().logicalId().equals(ak.id()))
                    .isNotEmpty().allMatch(r -> r.status() != CheckStatus.MATCH);

            // AC-Q10 : 지점 소속 관계 짝의 자식 속성을 지점 코드 → 회원 이름 으로
            var pairs = logical.selectRelationshipPairs(m.f().user, m.rBranch());
            logical.saveRelationshipPairs(m.f().user, m.rBranch(), new com.lexorma.data.logical.domain.dto.LogicalModelDto.PairSave(pairs.modifiedAt(),
                    List.of(new com.lexorma.data.logical.domain.dto.LogicalModelDto.PairInput(m.branchId(), m.memberName()))));
            assertThat(physical(m)).isEqualTo(physical);
            assertThat(rows(m, CheckGroup.RELATIONSHIP)).filteredOn(r -> r.structure().logicalId().equals(m.rBranch()))
                    .isNotEmpty().allMatch(r -> r.status() != CheckStatus.MATCH);
        }
    }

    @Test
    @DisplayName("AC-Q05·06·09 : C형 생성 뒤 말단 구성원 추가(테이블 자동 생성 없음, 미매핑·차이), 매핑된 말단 제거는 정책 결과대로, 조상 일반 속성 추가·필수 변경은 말단마다 차이")
    void concreteAfterGeneration() {
        Model m = model("ac-q-c", true);
        generate(m, C);
        String physical = physical(m);
        assertThat(inheritance(m, m.corp()).status()).isEqualTo(CheckStatus.NOT_COMPARABLE);

        // AC-Q05 : 기업 구분에 '기타 기업 회원' 추가
        long etc = entity(m.f(), "기타 기업 회원");
        saveGroup(m, m.g2(), null, List.of(new MemberInput(m.domestic(), "D"), new MemberInput(m.foreign(), "F"), new MemberInput(etc, "E")));
        assertThat(physical(m)).as("새 말단 테이블을 만들지 않는다").isEqualTo(physical);
        Row corp = inheritance(m, m.corp());
        assertThat(corp.status()).isNotIn(CheckStatus.MATCH, CheckStatus.NOT_COMPARABLE);
        assertThat(corp.reason()).contains("기타 기업 회원", "추가");
        assertThat(inheritance(m, etc).status()).isEqualTo(CheckStatus.UNMAPPED);
        // 분류 값만 바꾸는 것은 C형 물리 구조와 무관하다 (분류 컬럼이 없다) — 차이로 보지 않는다
        saveGroup(m, m.g2(), null, List.of(new MemberInput(m.domestic(), "D"), new MemberInput(m.foreign(), "F")));
        assertThat(inheritance(m, m.corp()).status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
        saveGroup(m, m.g2(), null, List.of(new MemberInput(m.domestic(), "D2"), new MemberInput(m.foreign(), "F")));
        assertThat(inheritance(m, m.corp()).status()).isEqualTo(CheckStatus.NOT_COMPARABLE);

        // AC-Q06 : 매핑된 말단(국내 기업 회원) 제거 — 정책 결과대로 (우회하지 않는다)
        saveGroup(m, m.g2(), null, List.of(new MemberInput(m.domestic(), "D"), new MemberInput(m.foreign(), "F"), new MemberInput(etc, "E")));
        saveGroup(m, m.g2(), null, List.of(new MemberInput(m.foreign(), "F"), new MemberInput(etc, "E")));
        assertThat(inheritance(m, m.corp()).reason()).contains("국내 기업 회원", "빠졌습니다");
        List<Row> stale = rows(m, CheckGroup.INHERITANCE).stream().filter(r -> r.entityId().equals(m.domestic()) && r.attributeId() != null).toList();
        assertThat(stale).as("남은 상속 PK 매핑 1 + 상속 일반 속성 매핑 5 (회원 이름·회원 유형 코드·지점 코드·사업자 번호·기업 유형 코드) 모두 불일치").hasSize(6)
                .allMatch(r -> r.status() == CheckStatus.MISMATCH);
        assertThat(stale).extracting(Row::columnName).contains("MBR_NO", "MBR_NM", "MBR_TY_CD", "BRNCH_CD", "BRNO", "CORP_TY_CD");
        error(() -> context.getBean(com.lexorma.data.mapping.service.ModelMappingService.class).deleteConversions(m.f().user, m.member()), ErrorCode.RELATION_EXISTS);
        assertThat(physical(m)).isEqualTo(physical);

        // AC-Q09 : 조상(회원) 일반 속성 추가 → 말단마다 복제 매핑 부족, 컬럼 자동 추가 없음. 필수 여부 변경 → 말단마다 필수 불일치
        long memo = m.f().attr(m.member(), "회원 이름 별칭", false, "MBR_ALS");
        assertThat(physical(m)).isEqualTo(physical);
        assertThat(rows(m, CheckGroup.INHERITANCE)).filteredOn(r -> "INHERITED_ATTRIBUTE_MAPPING_MISSING".equals(r.code())).isNotEmpty();
        assertThat(rows(m, CheckGroup.ATTRIBUTE)).filteredOn(r -> Long.valueOf(memo).equals(r.attributeId())).isNotEmpty().allMatch(r -> r.status() != CheckStatus.MATCH);
        var name = logical.selectEntity(m.f().user, m.member()).attributes().stream().filter(a -> a.id().equals(m.memberName())).findFirst().orElseThrow();
        logical.saveAttribute(m.f().user, new LogicalModelDto.Attribute(name.id(), m.member(), name.attributeName(), name.attributeDescription(), name.dataType(), false,
                name.sortOrder(), name.termId(), null, null, null, name.modifiedAt()));
        assertThat(rows(m, CheckGroup.ATTRIBUTE)).filteredOn(r -> r.type() == ModelMappingQualityDto.CheckType.REQUIRED && Long.valueOf(m.memberName()).equals(r.attributeId()))
                .hasSizeGreaterThanOrEqualTo(2).allMatch(r -> r.status() == CheckStatus.MISMATCH);
        assertThat(physical(m)).isEqualTo(physical);
    }

    List<String> statuses(Model m) {
        List<String> all = new ArrayList<>();
        rows(m, CheckGroup.ALL).forEach(r -> all.add(r.type() + "|" + r.entityName() + "|" + r.attributeName() + "|" + r.columnName() + "|" + r.status() + "|" + r.code()));
        rows(m, CheckGroup.INHERITANCE).forEach(r -> all.add(r.type() + "|" + r.entityName() + "|" + r.attributeName() + "|" + r.status() + "|" + r.code()));
        return all.stream().sorted().toList();
    }

    static List<String> diff(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        b.stream().filter(x -> !a.contains(x)).forEach(x -> out.add("+" + x));
        a.stream().filter(x -> !b.contains(x)).forEach(x -> out.add("-" + x));
        return out;
    }
}
