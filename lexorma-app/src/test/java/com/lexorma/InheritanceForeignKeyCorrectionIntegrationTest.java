package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.controller.ModelMappingRestController;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Code;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Item;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Plan;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Request;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Result;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.InheritanceForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-INH-03 기존 서브타입 누락 상속 FK 보정 (docs/completed/inheritance-reference-key-m7.md 5장, D-INH-06~10).
 * <pre>
 * 당사자 (PK 당사자 번호) ─ 조직 ─ 외국 조직       (단일 상속 PK — 생성기는 참조 테이블명 REF_COLUMN 으로 상속 FK 를 만든다)
 *                       └ 사람   └ 기업
 * 루트 (PK 키1, 키2) ─ 갑, 을                       (복합 상속 PK — 생성기는 USER 관계)
 * </pre>
 * 누락은 생성 뒤 JDBC 로 상속 FK 를 지워 만든다 (기존 테이블의 FK 누락). FK 생성 실패 주입은 일반 FK 보정 테스트의 훅을 그대로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(ForeignKeyCorrectionIntegrationTest.Hooks.class)
class InheritanceForeignKeyCorrectionIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired InheritanceForeignKeyCorrectionService correction;
    @Autowired ModelMappingQualityService quality;
    @Autowired PhysicalModelService physical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingRestController controller;
    @Autowired ProjectRepository projectRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        ForeignKeyCorrectionIntegrationTest.FAIL_AT.set(0);
        ForeignKeyCorrectionIntegrationTest.CALLS.set(0);
        SecurityTestSupport.clear();
    }

    record Fx(GenerationFixture f, Map<String, Long> e) {
        long table(String entity) {
            return f.table(e.get(entity));
        }

        long id(String entity) {
            return e.get(entity);
        }
    }

    private long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private void group(GenerationFixture f, long supertype, String name, long... members) {
        List<LogicalSubtypeDto.MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new LogicalSubtypeDto.MemberInput(m, null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, true, false, null, list, null));
    }

    Fx generated(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inhfk-" + name);
        Map<String, Long> e = new HashMap<>();
        for (String n : List.of("당사자", "조직", "사람", "외국 조직", "기업", "루트", "갑", "을")) e.put(n, entity(f, n));
        f.pk(e.get("당사자"), f.attr(e.get("당사자"), "당사자 번호", true, "PTY_NO"));
        f.attr(e.get("조직"), "조직 명", false, "ORG_NM");
        f.pk(e.get("루트"), f.attr(e.get("루트"), "루트 키1", true, "RK1"), f.attr(e.get("루트"), "루트 키2", true, "RK2"));
        group(f, e.get("당사자"), "당사자 구분", e.get("조직"), e.get("사람"));
        group(f, e.get("조직"), "조직 구분", e.get("외국 조직"), e.get("기업"));
        group(f, e.get("루트"), "루트 구분", e.get("갑"), e.get("을"));
        List<Long> chosen = List.of(e.get("당사자"), e.get("조직"), e.get("사람"), e.get("외국 조직"), e.get("기업"), e.get("루트"), e.get("갑"), e.get("을"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        assertThat(plan.creatable()).isTrue();
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        return new Fx(f, e);
    }

    /** 상속 FK 를 지운다 (단일이면 참조 테이블명도) — 기존 테이블의 상속 FK 누락 */
    void drop(Fx x, String child, String parent) {
        for (Long rel : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK'", Long.class, x.table(child), x.table(parent))) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);
        }
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where TBL_SN = ? and PK_YN = true", x.table(child));
    }

    long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    String tableName(long table) {
        return jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table);
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

    List<String> allRelations(Fx x) {
        return jdbc.queryForList("select REL_SN || ':' || ORGNL_TBL_SN || '>' || TRGT_TBL_SN || ':' || REL_SRC_CD || ':' || cast(APRV_YN as varchar(5)) from TB_TBL_REL where PRJ_SN = ? order by REL_SN",
                String.class, x.f().user.getProjectId());
    }

    /** 테이블·컬럼·제약조건·인덱스·매핑 (기존 물리 모델이 바뀌지 않았는지) */
    String physicalSnapshot(Fx x) {
        long p = x.f().user.getProjectId();
        return jdbc.queryForList("select TBL_SN || ':' || TBL_NM || ':' || cast(APRV_YN as varchar(5)) || ':' || cast(MDFCN_DT as varchar(40)) from TB_TBL where PRJ_SN = ? order by TBL_SN", String.class, p)
                + "|" + jdbc.queryForList("select COL_SN || ':' || COL_NM || ':' || DATA_TYPE_NM || ':' || cast(PK_YN as varchar(5)) || ':' || coalesce(RFRNC_TBL_NM, '-') || ':'"
                + " || cast(APRV_YN as varchar(5)) || ':' || cast(MDFCN_DT as varchar(40)) from TB_COL where PRJ_SN = ? order by COL_SN", String.class, p)
                + "|" + jdbc.queryForList("select CNSTRT_SN || ':' || CNSTRT_TYPE_CD from TB_TBL_CNSTRT where PRJ_SN = ? order by CNSTRT_SN", String.class, p)
                + "|" + jdbc.queryForList("select IDX_SN || ':' || IDX_NM from TB_TBL_IDX where PRJ_SN = ? order by IDX_SN", String.class, p)
                + "|" + jdbc.queryForList("select ENTT_TBL_MPNG_SN || ':' || LGCL_ENTT_SN || ':' || TBL_SN from TB_ENTT_TBL_MPNG where TBL_SN in (select TBL_SN from TB_TBL where PRJ_SN = ?) order by 1", String.class, p)
                + "|" + jdbc.queryForList("select SBTYP_IDNTFR_COL_MPNG_SN || ':' || COL_SN from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN in (select COL_SN from TB_COL where PRJ_SN = ?) order by 1", String.class, p);
    }

    Row inheritance(Fx x, String entity) {
        return quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null, CheckGroup.INHERITANCE), PageRequest.of(0, 100)).getContent().stream()
                .filter(r -> r.type() == CheckType.INHERITANCE && Objects.equals(r.entityId(), x.id(entity)) && r.attributeId() == null).findFirst().orElseThrow();
    }

    Plan preview(Fx x, String... entities) {
        return correction.preview(x.f().user, new Request(x.f().modelId, java.util.Arrays.stream(entities).map(x::id).toList(), null));
    }

    Result apply(Fx x, Plan plan) {
        return correction.apply(x.f().user, new Request(plan.modelId(), plan.items().stream().map(Item::subtypeEntityId).toList(), plan.planToken()));
    }

    Item item(Fx x, String entity) {
        return preview(x, entity).items().get(0);
    }

    List<Code> blockers(Fx x, String entity) {
        return item(x, entity).blockers().stream().map(b -> b.code()).toList();
    }

    static ErrorCode code(Runnable work) {
        try {
            work.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        return null;
    }

    int history(Fx x) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, x.f().user.getProjectId());
    }

    // ------------------------------------------------------------------ 보정 : 단일 · 복합 · 다단계

    @Test
    @DisplayName("INH-T17·T43·T44 단일 : 누락된 단일 상속 FK 를 미리보기 → 적용으로 미승인 USER 관계로 만든다. 진단 MATCH, INHFIX- 직전 버전·변경 이력 그룹, 기존 테이블·컬럼·매핑 그대로, 다시 보정하면 NOT_MISSING")
    void single() {
        Fx x = generated("single");
        drop(x, "조직", "당사자");
        Row before = inheritance(x, "조직");
        assertThat(before.status()).isEqualTo(CheckStatus.MISMATCH);
        Item status = correction.status(x.f().user, x.f().modelId, List.of(x.id("조직"))).get(0);
        assertThat(status.correctable()).isTrue();
        assertThat(status.parentFkDetail()).isEqualTo("INHERITANCE_FK_MISSING");
        assertThat(status.diagnosis()).isEqualTo("MISMATCH");
        int versions = x.f().versions();
        String physicalBefore = physicalSnapshot(x);
        List<String> relationsBefore = allRelations(x);

        Plan plan = preview(x, "조직");
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        Item item = plan.items().get(0);
        String org = tableName(x.table("조직")), party = tableName(x.table("당사자"));
        assertThat(item.childTableName()).isEqualTo(org);
        assertThat(item.parentTableName()).isEqualTo(party);
        assertThat(item.supertypeEntityId()).isEqualTo(x.id("당사자"));
        assertThat(item.groupName()).isEqualTo("당사자 구분");
        assertThat(item.representation()).isEqualTo("USER_RELATION");
        assertThat(item.relationName()).isEqualTo("FK_" + org + "_PTY_NO");
        assertThat(item.pairs()).extracting(p -> p.order() + ":" + p.childColumnName() + ">" + p.parentColumnName()).containsExactly("1:PTY_NO>PTY_NO");
        assertThat(preview(x, "조직").planToken()).isEqualTo(plan.planToken());
        assertThat(x.f().versions()).as("미리보기는 버전을 만들지 않는다").isEqualTo(versions);

        Result result = apply(x, plan);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.versionName()).startsWith("INHFIX-");
        assertThat(x.f().versions()).isEqualTo(versions + 1);
        assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
        assertThat(inheritance(x, "조직").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(physicalSnapshot(x)).isEqualTo(physicalBefore);
        assertThat(allRelations(x)).containsAll(relationsBefore).hasSize(relationsBefore.size() + 1);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?", Integer.class, x.f().user.getProjectId(), result.versionName()))
                .isPositive();

        // INH-T28 : 다시 보정하면 정상 상속 FK 가 있다 — 중복 생성 없음
        assertThat(blockers(x, "조직")).containsExactly(Code.NOT_MISSING);
        Plan again = preview(x, "조직");
        assertThat(code(() -> apply(x, again))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relations(x.table("조직"), x.table("당사자"))).hasSize(1);

        // INH-T45·D-INH-07 : 관계 동기화 뒤에도 미승인 그대로 (앱 재시작 뒤는 E2E 에서 확인)
        physical.syncReferenceRelations(x.f().user.getProjectId(), List.of(x.table("조직"), x.table("당사자")));
        assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
    }

    @Test
    @DisplayName("INH-T18 복합 : 누락된 복합 상속 FK 를 루트 주 식별자 순서로 만든다 (미승인 USER, MATCH)")
    void composite() {
        Fx x = generated("comp");
        assertThat(relations(x.table("갑"), x.table("루트"))).containsExactly("USER:false:RK1>RK1,RK2>RK2");
        drop(x, "갑", "루트");
        Plan plan = preview(x, "갑");
        assertThat(plan.applicable()).isTrue();
        assertThat(plan.items().get(0).pairs()).extracting(p -> p.order() + ":" + p.childColumnName() + ">" + p.parentColumnName()).containsExactly("1:RK1>RK1", "2:RK2>RK2");
        apply(x, plan);
        assertThat(relations(x.table("갑"), x.table("루트"))).containsExactly("USER:false:RK1>RK1,RK2>RK2");
        assertThat(inheritance(x, "갑").status()).isEqualTo(CheckStatus.MATCH);
    }

    @Test
    @DisplayName("INH-T19·T20 다단계 순차 보정 : 조직 → 당사자, 외국 조직 → 조직이 모두 없으면 외국 조직은 상위 FK 누락(ANCESTOR_FK_PENDING, 선행 대상 = 조직)으로 막힌다."
            + " 함께 고르면 계획 전체를 적용하지 않는다. 조직을 먼저 보정하면 외국 조직이 보정 가능해지고 직계 부모(조직)를 참조한다 (당사자를 직접 참조하지 않는다)")
    void multiLevel() {
        Fx x = generated("multi");
        drop(x, "조직", "당사자");
        drop(x, "외국 조직", "조직");
        Item lower = item(x, "외국 조직");
        assertThat(lower.blockers()).extracting(b -> b.code()).containsExactly(Code.ANCESTOR_FK_PENDING);
        assertThat(lower.pendingAncestorEntityId()).isEqualTo(x.id("조직"));
        assertThat(lower.pendingAncestorEntityName()).isEqualTo("조직");
        Plan both = preview(x, "조직", "외국 조직");
        assertThat(both.applicable()).isFalse();
        assertThat(both.correctable()).isEqualTo(1);
        assertThat(code(() -> apply(x, both))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(relations(x.table("조직"), x.table("당사자"))).isEmpty();

        apply(x, preview(x, "조직"));
        Plan next = preview(x, "외국 조직");
        assertThat(next.applicable()).as(String.valueOf(next.items())).isTrue();
        assertThat(next.items().get(0).parentTableId()).isEqualTo(x.table("조직"));
        apply(x, next);
        assertThat(relations(x.table("외국 조직"), x.table("조직"))).containsExactly("USER:false:PTY_NO>PTY_NO");
        assertThat(relations(x.table("외국 조직"), x.table("당사자"))).isEmpty();
        assertThat(inheritance(x, "외국 조직").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(inheritance(x, "조직").status()).isEqualTo(CheckStatus.MATCH);
    }

    // ------------------------------------------------------------------ 막는 경우 (D-INH-08·10)

    @Test
    @DisplayName("INH-T21~T26 구조 문제는 진단만 : 상위 구조 불일치(ANCESTOR_INVALID), 상속 PK 매핑 누락, 자식 PK 구성 불일치, 부모 PK 구성 불일치, 타입 불일치 — 자동으로 고치지 않는다")
    void structureProblems() {
        Fx x = generated("struct");
        drop(x, "외국 조직", "조직");
        // INH-T23 상속 PK 매핑 누락 (조직) → 외국 조직은 상위 경로의 다른 불일치 (INH-T21)
        Long orgMapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, x.id("조직"));
        List<Map<String, Object>> saved = jdbc.queryForList("select * from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", orgMapping);
        jdbc.update("delete from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", orgMapping);
        assertThat(blockers(x, "조직")).contains(Code.INHERITED_KEY_MAPPING_MISSING);
        assertThat(blockers(x, "외국 조직")).containsExactly(Code.ANCESTOR_INVALID);
        assertThat(item(x, "외국 조직").pendingAncestorEntityId()).isNull();
        for (Map<String, Object> row : saved) {
            jdbc.update("insert into TB_SBTYP_IDNTFR_COL_MPNG(SBTYP_IDNTFR_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, ?, ?, current_timestamp, current_timestamp, 'test', 'test')", row.get("SBTYP_IDNTFR_COL_MPNG_SN"), row.get("ENTT_TBL_MPNG_SN"),
                    row.get("LGCL_ATRB_SN"), row.get("COL_SN"));
        }
        assertThat(preview(x, "외국 조직").applicable()).isTrue();
        // INH-T25 자식 PK 구성 불일치 (상속 PK 가 아닌 컬럼을 PK 에 더한다)
        long frgn = x.table("외국 조직");
        jdbc.update("update TB_COL set PK_YN = true, NULL_ALWNC_YN = false where TBL_SN = ? and PK_YN = false", frgn);
        int extra = jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and PK_YN = true", Integer.class, frgn);
        if (extra > 1) assertThat(blockers(x, "외국 조직")).contains(Code.CHILD_KEY_MISMATCH);
        jdbc.update("update TB_COL set PK_YN = false where TBL_SN = ? and COL_NM <> 'PTY_NO'", frgn);
        // INH-T22 상속 PK 컬럼이 PK 가 아니다 (상속 PK 컬럼 누락)
        jdbc.update("update TB_COL set PK_YN = false where TBL_SN = ? and COL_NM = 'PTY_NO'", frgn);
        assertThat(blockers(x, "외국 조직")).contains(Code.CHILD_KEY_MISMATCH);
        jdbc.update("update TB_COL set PK_YN = true where TBL_SN = ? and COL_NM = 'PTY_NO'", frgn);
        // INH-T26 타입 불일치
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'DATE', MAX_LEN = null where TBL_SN = ? and COL_NM = 'PTY_NO'", frgn);
        assertThat(blockers(x, "외국 조직")).contains(Code.TYPE_INCOMPATIBLE);
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'VARCHAR', MAX_LEN = 20 where TBL_SN = ? and COL_NM = 'PTY_NO'", frgn);
        assertThat(preview(x, "외국 조직").applicable()).isTrue();
        // INH-T24 부모 PK 구성 불일치 (조직 테이블 PK 에 컬럼을 더한다) → 외국 조직은 상위 불일치
        jdbc.update("update TB_COL set PK_YN = true, NULL_ALWNC_YN = false where TBL_SN = ? and COL_NM = 'ORG_NM'", x.table("조직"));
        assertThat(blockers(x, "외국 조직")).containsExactly(Code.ANCESTOR_INVALID);
        assertThat(blockers(x, "조직")).contains(Code.CHILD_KEY_MISMATCH);
        assertThat(relations(frgn, x.table("조직"))).isEmpty();
    }

    @Test
    @DisplayName("INH-T27·T50·T51 기존 잘못된 상속 FK 는 고치지 않고 막는다 : 복합 순서 불일치(FK_ORDER), 부모 컬럼 불일치(FK_COLUMNS), 다른 테이블 참조(FK_TARGET). 정합성 진단은 MISMATCH")
    void wrongExistingForeignKeys() {
        Fx x = generated("wrong");
        long gap = x.table("갑"), root = x.table("루트");
        drop(x, "갑", "루트");
        // 순서만 다른 상속 FK
        physical.saveRelation(x.f().user, rel(gap, root, new long[]{column(gap, "RK2"), column(root, "RK2")}, new long[]{column(gap, "RK1"), column(root, "RK1")}));
        assertThat(blockers(x, "갑")).containsExactly(Code.FK_ORDER);
        assertThat(item(x, "갑").parentFkDetail()).isEqualTo("INHERITANCE_FK_ORDER");
        assertThat(inheritance(x, "갑").status()).isEqualTo(CheckStatus.MISMATCH);
        drop(x, "갑", "루트");
        // 부모 컬럼이 엇갈린 FK
        physical.saveRelation(x.f().user, rel(gap, root, new long[]{column(gap, "RK1"), column(root, "RK2")}, new long[]{column(gap, "RK2"), column(root, "RK1")}));
        assertThat(blockers(x, "갑")).containsExactly(Code.FK_COLUMNS);
        drop(x, "갑", "루트");
        // 직계 부모가 아닌 테이블 참조 (을 테이블의 PK 와 같은 구성)
        long eul = x.table("을");
        physical.saveRelation(x.f().user, rel(gap, eul, new long[]{column(gap, "RK1"), column(eul, "RK1")}, new long[]{column(gap, "RK2"), column(eul, "RK2")}));
        assertThat(blockers(x, "갑")).containsExactly(Code.FK_TARGET);
        assertThat(relations(gap, root)).isEmpty();
    }

    private PhysicalModelDto.Relation rel(long child, long parent, long[]... pairs) {
        List<PhysicalModelDto.ColumnPair> list = new ArrayList<>();
        for (long[] p : pairs) list.add(new PhysicalModelDto.ColumnPair(p[0], null, p[1], null));
        return new PhysicalModelDto.Relation(null, null, "FK", null, child, null, parent, null, list, null);
    }

    @Test
    @DisplayName("INH-T29~T35 승인·충돌 : 승인된 자식 테이블·상속 PK 컬럼은 관리자도 막는다, 부모 승인은 막지 않는다, FK 이름 충돌·참조 테이블명 충돌·같은 구성의 기존 관계(LOGICAL)는 막는다")
    void approvalAndConflicts() {
        Fx x = generated("appr");
        drop(x, "조직", "당사자");
        long org = x.table("조직"), party = x.table("당사자");
        // INH-T29 자식 테이블 승인 (픽스처 로그인은 ADMIN — INH-T32)
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", org);
        assertThat(blockers(x, "조직")).containsExactly(Code.CHILD_TABLE_APPROVED);
        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", org);
        // INH-T30 상속 PK 컬럼 승인
        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", column(org, "PTY_NO"));
        assertThat(blockers(x, "조직")).containsExactly(Code.CHILD_COLUMN_APPROVED);
        jdbc.update("update TB_COL set APRV_YN = false where COL_SN = ?", column(org, "PTY_NO"));
        // INH-T34 참조 테이블명 충돌 (동기화가 관계를 못 만든 이름)
        jdbc.update("update TB_COL set RFRNC_TBL_NM = 'NO_SUCH_TABLE' where COL_SN = ?", column(org, "PTY_NO"));
        assertThat(blockers(x, "조직")).containsExactly(Code.REF_COLUMN_CONFLICT);
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where COL_SN = ?", column(org, "PTY_NO"));
        // INH-T33 FK 이름 충돌 (같은 스키마의 다른 FK — 사람 → 당사자 상속 FK 의 이름을 같게 만든다)
        String name = "FK_" + tableName(org) + "_PTY_NO";
        jdbc.update("update TB_TBL_REL set REL_NM = ? where PRJ_SN = ? and ORGNL_TBL_SN = ?", name, x.f().user.getProjectId(), x.table("사람"));
        assertThat(blockers(x, "조직")).containsExactly(Code.NAME_CONFLICT);
        jdbc.update("update TB_TBL_REL set REL_NM = 'FK_OTHER_NAME' where PRJ_SN = ? and ORGNL_TBL_SN = ?", x.f().user.getProjectId(), x.table("사람"));
        // INH-T35 같은 구성의 기존 관계 (LOGICAL 관계)
        physical.saveRelation(x.f().user, new PhysicalModelDto.Relation(null, "LG_ORG", "LOGICAL", null, org, null, party, null,
                List.of(new PhysicalModelDto.ColumnPair(column(org, "PTY_NO"), null, column(party, "PTY_NO"), null)), null));
        assertThat(blockers(x, "조직")).containsExactly(Code.RELATION_EXISTS);
        jdbc.update("delete from TB_TBL_REL_COL where REL_SN in (select REL_SN from TB_TBL_REL where REL_NM = 'LG_ORG')");
        jdbc.update("delete from TB_TBL_REL where REL_NM = 'LG_ORG'");
        // INH-T31 부모 테이블·컬럼 승인은 막지 않는다
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", party);
        jdbc.update("update TB_COL set APRV_YN = true where TBL_SN = ?", party);
        Plan plan = preview(x, "조직");
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        apply(x, plan);
        assertThat(relations(org, party)).containsExactly("USER:false:PTY_NO>PTY_NO");
    }

    // ------------------------------------------------------------------ 계획 토큰 · 원자성

    @Test
    @DisplayName("INH-T41·51장 : 토큰 발급 뒤 자식 승인·컬럼 이름 변경·상속 PK 매핑 변경·동일 FK 선행 생성·FK 이름 충돌·상위 FK 상태 변경·상속 그룹 변경은 409 (아무것도 만들지 않는다). 무관한 설명 변경은 토큰을 바꾸지 않는다")
    void staleToken() {
        Fx x = generated("tok");
        drop(x, "조직", "당사자");
        long org = x.table("조직"), party = x.table("당사자");
        Plan plan = preview(x, "조직");
        int versions = x.f().versions();
        // 무관한 설명 변경
        jdbc.update("update TB_TBL set TBL_EXPLN = '설명 바꿈' where TBL_SN = ?", org);
        jdbc.update("update TB_LGCL_ENTT set ENTT_EXPLN = '설명' where LGCL_ENTT_SN = ?", x.id("조직"));
        assertThat(preview(x, "조직").planToken()).isEqualTo(plan.planToken());
        assertThat(code(() -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(x.id("조직")), "bad")))).isEqualTo(ErrorCode.MODEL_CONFLICT);

        List<Runnable[]> changes = List.of(
                new Runnable[]{() -> jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", org), () -> jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", org)},
                new Runnable[]{() -> jdbc.update("update TB_COL set COL_NM = 'PTY_NO_X' where COL_SN = ?", column(org, "PTY_NO")),
                        () -> jdbc.update("update TB_COL set COL_NM = 'PTY_NO' where TBL_SN = ? and COL_NM = 'PTY_NO_X'", org)},
                new Runnable[]{() -> jdbc.update("update TB_COL set DATA_TYPE_NM = 'DATE', MAX_LEN = null where COL_SN = ?", column(party, "PTY_NO")),
                        () -> jdbc.update("update TB_COL set DATA_TYPE_NM = 'VARCHAR', MAX_LEN = 20 where COL_SN = ?", column(party, "PTY_NO"))},
                new Runnable[]{() -> jdbc.update("update TB_TBL_REL set REL_NM = ? where PRJ_SN = ? and ORGNL_TBL_SN = ?", "FK_" + tableName(org) + "_PTY_NO", x.f().user.getProjectId(), x.table("사람")),
                        () -> jdbc.update("update TB_TBL_REL set REL_NM = 'FK_RESTORED' where PRJ_SN = ? and ORGNL_TBL_SN = ?", x.f().user.getProjectId(), x.table("사람"))});
        for (Runnable[] change : changes) {
            change[0].run();
            assertThat(code(() -> apply(x, plan))).as("변경 뒤 적용").isIn(ErrorCode.MODEL_CONFLICT, ErrorCode.INVALID_PHYSICAL_MODEL);
            assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.MODEL_CONFLICT);
            change[1].run();
        }
        // 상속 PK 매핑 변경 (지움)
        Long mapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, x.id("조직"));
        List<Map<String, Object>> saved = jdbc.queryForList("select * from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", mapping);
        jdbc.update("delete from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN = ?", mapping);
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        for (Map<String, Object> row : saved) {
            jdbc.update("insert into TB_SBTYP_IDNTFR_COL_MPNG(SBTYP_IDNTFR_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, ?, ?, current_timestamp, current_timestamp, 'test', 'test')", row.get("SBTYP_IDNTFR_COL_MPNG_SN"), row.get("ENTT_TBL_MPNG_SN"),
                    row.get("LGCL_ATRB_SN"), row.get("COL_SN"));
        }
        assertThat(preview(x, "조직").planToken()).isEqualTo(plan.planToken());
        // 동일 FK 선행 생성 (다른 사용자가 같은 FK 를 먼저 만들었다)
        physical.saveRelation(x.f().user, rel(org, party, new long[]{column(org, "PTY_NO"), column(party, "PTY_NO")}));
        assertThat(code(() -> apply(x, plan))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(relations(org, party)).hasSize(1);
        assertThat(x.f().versions()).isEqualTo(versions);

        // 상위 FK 상태 변경 : 외국 조직 계획(상위 대기) 뒤 조직을 보정하면 외국 조직의 계획이 바뀐다
        drop(x, "조직", "당사자");
        drop(x, "외국 조직", "조직");
        Plan lower = preview(x, "외국 조직");
        apply(x, preview(x, "조직"));
        assertThat(code(() -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(x.id("외국 조직")), lower.planToken())))).isEqualTo(ErrorCode.MODEL_CONFLICT);

        // 상속 그룹 변경 : 외국 조직을 그룹에서 뺀다 (서브타입이 아니게 된다)
        Plan frgn = preview(x, "외국 조직");
        assertThat(frgn.applicable()).isTrue();
        Long orgGroupId = jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from TB_LGCL_SBTYP_GROUP where LGCL_MDL_SN = ? and SBTYP_GROUP_NM = '조직 구분'", Long.class, x.f().modelId);
        LogicalSubtypeDto.Group orgGroup = subtypes.selectGroup(x.f().user, orgGroupId);
        long indv = entity(x.f(), "개인");
        subtypes.saveGroup(x.f().user, new LogicalSubtypeDto.Save(orgGroup.id(), x.f().modelId, x.id("조직"), "조직 구분", null, true, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(x.id("기업"), null), new LogicalSubtypeDto.MemberInput(indv, null)), orgGroup.modifiedAt()));
        assertThat(code(() -> apply(x, frgn))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(blockers(x, "외국 조직")).containsExactly(Code.NOT_SUBTYPE);
    }

    @Test
    @DisplayName("INH-T42 전체 원자성 : 두 번째 상속 FK 생성이 실패하면 첫 FK·모델 버전·변경 이력까지 모두 롤백")
    void atomic() {
        Fx x = generated("atom");
        drop(x, "조직", "당사자");
        drop(x, "갑", "루트");
        drop(x, "을", "루트");
        Plan plan = preview(x, "조직", "갑", "을");
        assertThat(plan.applicable()).isTrue();
        List<String> before = allRelations(x);
        int versions = x.f().versions();
        int history = history(x);
        ForeignKeyCorrectionIntegrationTest.FAIL_AT.set(2);
        assertThatThrownBy(() -> apply(x, plan)).hasMessageContaining("FK 생성 실패");
        assertThat(allRelations(x)).isEqualTo(before);
        assertThat(x.f().versions()).isEqualTo(versions);
        assertThat(history(x)).isEqualTo(history);
        ForeignKeyCorrectionIntegrationTest.FAIL_AT.set(0);
        assertThat(apply(x, plan).created()).isEqualTo(3);
        assertThat(inheritance(x, "갑").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(inheritance(x, "을").status()).isEqualTo(CheckStatus.MATCH);
    }

    // ------------------------------------------------------------------ 요청 검사 · 권한 · 격리

    @Test
    @DisplayName("INH-T36~T40 요청 검사 : 빈 선택·중복·101개 400, 100개는 처리, 다른 모델·다른 프로젝트 엔터티 거절, 슈퍼타입만인 엔터티는 NOT_SUBTYPE, VIEWER 는 상태만 — 미리보기·적용 403")
    void validationAndAuthorization() {
        Fx x = generated("auth");
        drop(x, "조직", "당사자");
        long org = x.id("조직");
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, List.of(), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, List.of(org, org), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 101; i++) many.add(i);
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, many, null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> correction.preview(x.f().user, new Request(x.f().modelId, List.of(Long.MAX_VALUE), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(org), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        // 100개 : 모델의 엔터티 100개 (대부분 서브타입이 아니다)
        List<Long> hundred = new ArrayList<>(x.e().values());
        while (hundred.size() < 100) hundred.add(x.f().entity("당사자 " + hundred.size()));
        List<Item> items = correction.status(x.f().user, x.f().modelId, hundred);
        assertThat(items).hasSize(100);
        assertThat(items.stream().filter(i -> i.subtypeEntityId().equals(x.id("당사자"))).findFirst().orElseThrow().blockers())
                .extracting(b -> b.code()).containsExactly(Code.NOT_SUBTYPE);
        assertThat(items.stream().filter(i -> i.subtypeEntityId().equals(org)).findFirst().orElseThrow().correctable()).isTrue();
        // 같은 프로젝트 다른 모델 · 다른 프로젝트
        GenerationFixture other = x.f().otherModel("inhfk-other-" + System.nanoTime());
        assertThat(code(() -> correction.preview(x.f().user, new Request(other.modelId, List.of(org), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        Fx stranger = generated("auth2");
        assertThat(code(() -> correction.preview(stranger.f().user, new Request(x.f().modelId, List.of(org), null)))).isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        assertThat(code(() -> correction.preview(stranger.f().user, new Request(stranger.f().modelId, List.of(org), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        Plan plan = preview(x, "조직");
        assertThat(code(() -> correction.apply(stranger.f().user, new Request(x.f().modelId, List.of(org), plan.planToken())))).isEqualTo(ErrorCode.LOGICAL_MODEL_IS_NOT_DEFINED);
        // 권한 (컨트롤러의 @PreAuthorize)
        var viewer = SecurityTestSupport.loginAs(projectRepository, x.f().user.getProjectId(), x.f().user.getId(), "ROLE_VIEWER");
        assertThat(controller.inheritanceFkStatus(viewer, x.f().modelId, List.of(org)).getBody()).singleElement().satisfies(i -> assertThat(i.correctable()).isTrue());
        assertThatThrownBy(() -> controller.inheritanceFkPreview(new Request(x.f().modelId, List.of(org), null), viewer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.inheritanceFkApply(new Request(x.f().modelId, List.of(org), plan.planToken()), viewer)).isInstanceOf(AccessDeniedException.class);
        assertThat(relations(x.table("조직"), x.table("당사자"))).isEmpty();
        var user = SecurityTestSupport.loginAs(projectRepository, x.f().user.getProjectId(), x.f().user.getId(), "ROLE_USER");
        assertThat(controller.inheritanceFkApply(new Request(x.f().modelId, List.of(org), plan.planToken()), user).getBody().created()).isEqualTo(1);
    }
}
