package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto.Code;
import com.lexorma.data.mapping.service.ForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.UniqueCorrectionService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.service.TableDesignService;
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
 * M7-AK-01~03 보조 식별자 UNIQUE 생성·누락 UNIQUE 보정·보조 식별자 참조 FK (docs/completed/alternate-key-unique-m7.md). H2 / PostgreSQL 같은 기대값.
 * 고객(PK 고객 번호) : 보조 식별자 AK_EMAIL(이메일 — 필수 아님 → NULL 경고), AK_BR(지점 코드, 고객 코드 — 복합), AK_DUP(이메일 — AK_EMAIL 과 같은 구성), AK_PK(고객 번호 — PK 와 같음).
 * 주문(PK 주문 번호) : 고객 이메일 → AK_EMAIL 참조 단일 FK, (주문 지점 코드, 주문 고객 코드) → AK_BR 참조 복합 FK.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AlternateKeyUniqueIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired UniqueCorrectionService uniqueCorrection;
    @Autowired ForeignKeyCorrectionService fkCorrection;
    @Autowired ModelMappingQualityService quality;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignService tableDesign;
    @Autowired LogicalModelService logical;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private record Fx(GenerationFixture f, Map<String, Long> e, Map<String, Long> a, Map<String, Long> k, Map<String, Long> r) {
        long table(String entity) {
            return f.table(e.get(entity));
        }
    }

    private long ak(GenerationFixture f, long entity, String name, Long... attributes) {
        return logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, name, false, List.of(attributes), null)).id();
    }

    /** 논리 모델만 만든다 (생성 전) */
    private Fx model(String name, boolean emailPairs, boolean reversedComposite) {
        GenerationFixture f = GenerationFixture.create(context, "ak-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        Map<String, Long> e = new HashMap<>();
        Map<String, Long> a = new HashMap<>();
        Map<String, Long> k = new HashMap<>();
        Map<String, Long> r = new HashMap<>();
        e.put("고객", f.entity("고객"));
        a.put("CUST_NO", f.attr(e.get("고객"), "고객 번호", true, "CUST_NO"));
        a.put("EMAIL", f.attr(e.get("고객"), "이메일", false, "EMAIL"));
        a.put("BR_CD", f.attr(e.get("고객"), "지점 코드", true, "BR_CD"));
        a.put("CUST_CD", f.attr(e.get("고객"), "고객 코드", true, "CUST_CD"));
        f.pk(e.get("고객"), a.get("CUST_NO"));
        k.put("AK_EMAIL", ak(f, e.get("고객"), "AK_EMAIL", a.get("EMAIL")));
        k.put("AK_BR", ak(f, e.get("고객"), "AK_BR", a.get("BR_CD"), a.get("CUST_CD")));
        k.put("AK_DUP", ak(f, e.get("고객"), "AK_DUP", a.get("EMAIL")));
        k.put("AK_PK", ak(f, e.get("고객"), "AK_PK", a.get("CUST_NO")));
        e.put("주문", f.entity("주문"));
        a.put("ORD_NO", f.attr(e.get("주문"), "주문 번호", true, "ORD_NO"));
        a.put("ORD_EMAIL", f.attr(e.get("주문"), "주문 고객 이메일", false, "ORD_EMAIL"));
        a.put("ORD_BR", f.attr(e.get("주문"), "주문 지점 코드", false, "ORD_BR"));
        a.put("ORD_CD", f.attr(e.get("주문"), "주문 고객 코드", false, "ORD_CD"));
        f.pk(e.get("주문"), a.get("ORD_NO"));
        if (emailPairs) {
            LogicalModelDto.Relationship email = f.rel("이메일 주문", e.get("고객"), e.get("주문"), false, 0);
            f.pairs(email, new long[]{a.get("EMAIL"), a.get("ORD_EMAIL")});
            r.put("이메일", email.id());
        }
        LogicalModelDto.Relationship branch = f.rel("지점 주문", e.get("고객"), e.get("주문"), false, 0);
        if (reversedComposite) f.pairs(branch, new long[]{a.get("CUST_CD"), a.get("ORD_CD")}, new long[]{a.get("BR_CD"), a.get("ORD_BR")});
        else f.pairs(branch, new long[]{a.get("BR_CD"), a.get("ORD_BR")}, new long[]{a.get("CUST_CD"), a.get("ORD_CD")});
        r.put("지점", branch.id());
        return new Fx(f, e, a, k, r);
    }

    private Fx generated(String name) {
        Fx x = model(name, true, false);
        List<Long> chosen = List.of(x.e().get("고객"), x.e().get("주문"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(chosen));
        generation.apply(x.f().user, x.f().request(chosen, plan.planToken()));
        return x;
    }

    private Row row(Fx x, CheckGroup group, CheckType type, long logicalId) {
        return quality.selectRows(x.f().user, new Search(x.f().modelId, null, null, null, null, null, group), PageRequest.of(0, 100)).getContent().stream()
                .filter(r -> r.type() == type && Objects.equals(r.structure().logicalId(), logicalId)).findFirst().orElseThrow();
    }

    private Row alternate(Fx x, String key) {
        return row(x, CheckGroup.ALTERNATE_KEY, CheckType.ALTERNATE_KEY, x.k().get(key));
    }

    private Row relationship(Fx x, String rel) {
        return row(x, CheckGroup.RELATIONSHIP, CheckType.RELATIONSHIP, x.r().get(rel));
    }

    /** UNIQUE 제약조건 : 이름:승인:컬럼(구성 순서) — H2·PostgreSQL 공통으로 Java 에서 잇는다 */
    private List<String> uniques(long table) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("select CNSTRT_SN, CNSTRT_NM, APRV_YN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE' order by CNSTRT_SN", table)) {
            List<String> cols = jdbc.queryForList("select col.COL_NM from TB_TBL_CNSTRT_COL cc join TB_COL col on col.COL_SN = cc.COL_SN where cc.CNSTRT_SN = ? order by cc.COL_ORD",
                    String.class, ((Number) c.get("CNSTRT_SN")).longValue());
            out.add(c.get("CNSTRT_NM") + ":" + c.get("APRV_YN") + ":" + String.join(",", cols));
        }
        return out;
    }

    /** FK 관계 : 출처:승인:자식>부모 컬럼 쌍(순서) */
    private List<String> relations(long child, long parent) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select REL_SN, REL_SRC_CD, APRV_YN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK' order by REL_SN",
                child, parent)) {
            List<String> pairs = jdbc.queryForList("select c.COL_NM || '>' || p.COL_NM from TB_TBL_REL_COL rc join TB_COL c on c.COL_SN = rc.ORGNL_COL_SN join TB_COL p on p.COL_SN = rc.TRGT_COL_SN"
                    + " where rc.REL_SN = ? order by rc.COL_ORD", String.class, ((Number) r.get("REL_SN")).longValue());
            out.add(r.get("REL_SRC_CD") + ":" + r.get("APRV_YN") + ":" + String.join(",", pairs));
        }
        return out;
    }

    private void dropUniques(long table) {
        for (Long id : jdbc.queryForList("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Long.class, table)) {
            jdbc.update("delete from TB_TBL_CNSTRT_COL where CNSTRT_SN = ?", id);
            jdbc.update("delete from TB_TBL_CNSTRT where CNSTRT_SN = ?", id);
        }
    }

    private void dropRelations(long child, long parent) {
        for (Long id : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK'", Long.class, child, parent)) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", id);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", id);
        }
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private static ErrorCode code(Runnable work) {
        try {
            work.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        return null;
    }

    // ------------------------------------------------------------------ AK-01 생성

    @Test
    @DisplayName("AK-G01~G08 : 새 테이블에 보조 식별자마다 명시적 UNIQUE (단일·복합, 식별자 순서, 미승인). PK 와 같은 구성·같은 구성의 두 번째 보조 식별자는 만들지 않고 (대표 = 작은 식별자 ID), NULL 허용은 경고만. 생성 뒤 진단 MATCH")
    void generationCreatesUniques() {
        Fx x = model("gen", true, false);
        List<Long> chosen = List.of(x.e().get("고객"), x.e().get("주문"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(chosen));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables())).isTrue();
        Map<String, PhysicalGenerationDto.UniquePlan> byName = new HashMap<>();
        plan.uniques().forEach(u -> byName.put(u.identifierName(), u));
        assertThat(byName.get("AK_EMAIL").status()).isEqualTo("CREATE");
        assertThat(byName.get("AK_EMAIL").columns()).containsExactly("EMAIL");
        assertThat(byName.get("AK_EMAIL").warnings()).containsExactly("NULLABLE_COLUMNS");
        assertThat(byName.get("AK_BR").status()).isEqualTo("CREATE");
        assertThat(byName.get("AK_BR").columns()).containsExactly("BR_CD", "CUST_CD");
        assertThat(byName.get("AK_BR").warnings()).isEmpty();
        assertThat(byName.get("AK_DUP").status()).isEqualTo("SKIP");
        assertThat(byName.get("AK_DUP").reason()).isEqualTo("DUPLICATE_ALTERNATE_KEY");
        assertThat(byName.get("AK_DUP").duplicateOfIdentifierId()).isEqualTo(x.k().get("AK_EMAIL"));
        assertThat(byName.get("AK_PK").reason()).isEqualTo("PRIMARY_KEY_DUPLICATE");
        // 같은 계획이면 같은 토큰, 보조 식별자가 바뀌면 토큰도 바뀐다 (v8)
        assertThat(generation.preview(x.f().user, x.f().request(chosen)).planToken()).isEqualTo(plan.planToken());

        PhysicalGenerationDto.Result result = generation.apply(x.f().user, x.f().request(chosen, plan.planToken()));
        assertThat(result.uniques()).isEqualTo(2);
        String customer = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.table("고객"));
        assertThat(uniques(x.table("고객"))).containsExactly("UK_" + customer + "_EMAIL:false:EMAIL", "UK_" + customer + "_BR_CD_CUST_CD:false:BR_CD,CUST_CD");
        // 필수 여부는 바꾸지 않는다 (D03)
        assertThat(jdbc.queryForObject("select cast(NULL_ALWNC_YN as varchar(5)) from TB_COL where COL_SN = ?", String.class, column(x.table("고객"), "EMAIL"))).isEqualToIgnoringCase("true");
        assertThat(alternate(x, "AK_EMAIL").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(alternate(x, "AK_BR").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(alternate(x, "AK_PK").structure().code()).isEqualTo("ALTERNATE_KEY_IS_PRIMARY_KEY");
    }

    @Test
    @DisplayName("AK-G09 : 보조 식별자 속성은 생성에서 제외할 수 없다 (경고 후 포함)")
    void alternateAttributesCannotBeExcluded() {
        Fx x = model("excl", false, false);
        List<Long> chosen = List.of(x.e().get("고객"), x.e().get("주문"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, new PhysicalGenerationDto.Request(chosen, null, null, List.of(x.a().get("BR_CD"))));
        PhysicalGenerationDto.TablePlan customer = plan.tables().stream().filter(t -> t.entityId().equals(x.e().get("고객"))).findFirst().orElseThrow();
        assertThat(customer.columns()).extracting(PhysicalGenerationDto.ColumnPlan::columnName).contains("BR_CD");
        assertThat(customer.warnings()).anyMatch(w -> w.contains("BR_CD") || w.contains("지점 코드"));
    }

    // ------------------------------------------------------------------ AK-03 보조 식별자 참조 FK (생성)

    @Test
    @DisplayName("AK-F01~F06 : 보조 식별자를 참조하는 단일·복합 FK 를 미승인 USER 관계로 만든다 (단일도 참조 테이블명이 아니다). FK 컬럼 순서 = UNIQUE 순서, 진단 MATCH, DDL 이 UNIQUE 를 참조")
    void generationCreatesAlternateForeignKeys() {
        Fx x = generated("afk");
        assertThat(relations(x.table("주문"), x.table("고객"))).containsExactlyInAnyOrder("USER:false:ORD_EMAIL>EMAIL", "USER:false:ORD_BR>BR_CD,ORD_CD>CUST_CD");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and RFRNC_TBL_NM is not null", Integer.class, x.table("주문"))).isZero();
        assertThat(relationship(x, "이메일").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(relationship(x, "이메일").structure().referenceKey()).isEqualTo("ALTERNATE");
        assertThat(relationship(x, "이메일").structure().referenceKeyId()).isEqualTo(x.k().get("AK_EMAIL"));
        assertThat(relationship(x, "지점").status()).isEqualTo(CheckStatus.MATCH);
        String ddl = tableDesign.generateTableDdl(x.table("주문"), x.f().user, null).ddl();
        assertThat(ddl).containsIgnoringCase("(ORD_BR, ORD_CD)").containsIgnoringCase("(BR_CD, CUST_CD)").containsIgnoringCase("(ORD_EMAIL)");
    }

    @Test
    @DisplayName("AK-F07 : 짝 순서가 보조 식별자 순서와 다르면 FK 를 만들지 않는다 (UNIQUE_ORDER_DIFFERS) — 테이블·UNIQUE 는 만든다")
    void reversedCompositeNotCreated() {
        Fx x = model("rev", false, true);
        List<Long> chosen = List.of(x.e().get("고객"), x.e().get("주문"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(chosen));
        PhysicalGenerationDto.RelationPlan branch = plan.relations().stream().filter(r -> r.relationshipId().equals(x.r().get("지점"))).findFirst().orElseThrow();
        assertThat(branch.fkCreated()).isFalse();
        assertThat(branch.reasons()).contains("UNIQUE_ORDER_DIFFERS");
        assertThat(branch.pairWarnings()).contains("ORDER_DIFFERS");
        generation.apply(x.f().user, x.f().request(chosen, plan.planToken()));
        assertThat(relations(x.table("주문"), x.table("고객"))).isEmpty();
        assertThat(uniques(x.table("고객"))).hasSize(2);
    }

    @Test
    @DisplayName("AK-F08·D06 : 기존 부모 테이블에 그 순서의 UNIQUE 가 없으면 FK 를 만들지 않는다 (PARENT_UNIQUE_MISSING) — 기존 테이블에 UNIQUE 를 더하지 않는다")
    void existingParentWithoutUnique() {
        Fx x = model("exist", true, false);
        PhysicalGenerationDto.Plan parentPlan = generation.preview(x.f().user, x.f().request(List.of(x.e().get("고객"))));
        generation.apply(x.f().user, x.f().request(List.of(x.e().get("고객")), parentPlan.planToken()));
        dropUniques(x.table("고객"));
        PhysicalGenerationDto.Plan plan = generation.preview(x.f().user, x.f().request(List.of(x.e().get("주문"))));
        assertThat(plan.relations()).allSatisfy(r -> {
            assertThat(r.fkCreated()).isFalse();
            assertThat(r.reasons()).contains("PARENT_UNIQUE_MISSING");
        });
        generation.apply(x.f().user, x.f().request(List.of(x.e().get("주문")), plan.planToken()));
        assertThat(uniques(x.table("고객"))).isEmpty();
        assertThat(relations(x.table("주문"), x.table("고객"))).isEmpty();
    }

    // ------------------------------------------------------------------ AK-02 누락 UNIQUE 보정

    @Test
    @DisplayName("AK-U01~U10 : UNIQUE 누락 보조 식별자를 미리보기 → 적용으로 보정. 미승인, 식별자 순서, UKFIX- 버전, 변경 이력 그룹, 토큰 순서 무관, 다시 보정하면 NOT_MISSING. 같은 구성의 두 번째 식별자는 막는다")
    void uniqueCorrection() {
        Fx x = generated("ukfix");
        dropRelations(x.table("주문"), x.table("고객"));
        dropUniques(x.table("고객"));
        assertThat(alternate(x, "AK_EMAIL").structure().code()).isEqualTo("UNIQUE_MISSING");
        assertThat(alternate(x, "AK_BR").structure().code()).isEqualTo("UNIQUE_MISSING");
        int versions = x.f().versions();

        List<UniqueCorrectionDto.Item> status = uniqueCorrection.status(x.f().user, x.f().modelId, List.of(x.k().get("AK_BR"), x.k().get("AK_EMAIL"), x.k().get("AK_DUP"), x.k().get("AK_PK")));
        Map<Long, UniqueCorrectionDto.Item> byId = new HashMap<>();
        status.forEach(i -> byId.put(i.identifierId(), i));
        assertThat(byId.get(x.k().get("AK_EMAIL")).correctable()).as(String.valueOf(byId.get(x.k().get("AK_EMAIL")).blockers())).isTrue();
        assertThat(byId.get(x.k().get("AK_EMAIL")).warnings()).containsExactly("NULLABLE_COLUMNS");
        assertThat(byId.get(x.k().get("AK_BR")).correctable()).isTrue();
        assertThat(byId.get(x.k().get("AK_DUP")).blockers()).extracting(UniqueCorrectionDto.Issue::code).contains(Code.DUPLICATE_ALTERNATE_KEY);
        assertThat(byId.get(x.k().get("AK_DUP")).duplicateOfIdentifierId()).isEqualTo(x.k().get("AK_EMAIL"));
        assertThat(byId.get(x.k().get("AK_PK")).blockers()).extracting(UniqueCorrectionDto.Issue::code).contains(Code.NOT_MISSING);
        assertThat(versions).isEqualTo(x.f().versions());

        UniqueCorrectionDto.Plan plan = uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_BR"), x.k().get("AK_EMAIL")), null));
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        assertThat(uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_EMAIL"), x.k().get("AK_BR")), null)).planToken())
                .isEqualTo(plan.planToken());
        UniqueCorrectionDto.Item br = plan.items().stream().filter(i -> i.identifierId().equals(x.k().get("AK_BR"))).findFirst().orElseThrow();
        assertThat(br.columns()).extracting(UniqueCorrectionDto.Column::columnName).containsExactly("BR_CD", "CUST_CD");

        UniqueCorrectionDto.Result result = uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_BR"), x.k().get("AK_EMAIL")), plan.planToken()));
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.versionName()).startsWith("UKFIX-");
        assertThat(x.f().versions()).isEqualTo(versions + 1);
        assertThat(uniques(x.table("고객"))).extracting(s -> s.substring(s.indexOf(':') + 1)).containsExactlyInAnyOrder("false:EMAIL", "false:BR_CD,CUST_CD");
        assertThat(alternate(x, "AK_EMAIL").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(alternate(x, "AK_BR").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?", Integer.class, x.f().user.getProjectId(), result.versionName())).isPositive();

        UniqueCorrectionDto.Plan again = uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_EMAIL")), null));
        assertThat(again.applicable()).isFalse();
        // 첫 사유 = 진단이 누락 아님. 같은 구성 UNIQUE·같은 이름도 함께 사유로 나온다 (중복 생성 없음)
        assertThat(again.items().get(0).blockers()).extracting(UniqueCorrectionDto.Issue::code).startsWith(Code.NOT_MISSING).contains(Code.UNIQUE_DUPLICATE);
        assertThat(code(() -> uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_EMAIL")), again.planToken()))))
                .isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    @Test
    @DisplayName("AK-U11~U16 : 승인된 테이블·컬럼은 막는다 (관리자도), 계획이 바뀌면 409, 요청 검사 (빈 선택·중복·101개·토큰 없음·다른 모델 식별자)")
    void uniqueCorrectionBlocked() {
        Fx x = generated("ukblk");
        dropUniques(x.table("고객"));
        dropRelations(x.table("주문"), x.table("고객"));
        long email = x.k().get("AK_EMAIL");
        UniqueCorrectionDto.Plan plan = uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(email), null));
        assertThat(plan.applicable()).isTrue();

        // 미리보기 뒤 구성 컬럼이 바뀌면 (필수 여부) 계획이 달라져 409
        jdbc.update("update TB_COL set NULL_ALWNC_YN = false where COL_SN = ?", column(x.table("고객"), "EMAIL"));
        assertThat(code(() -> uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(email), plan.planToken())))).isEqualTo(ErrorCode.MODEL_CONFLICT);
        assertThat(uniques(x.table("고객"))).isEmpty();

        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", column(x.table("고객"), "EMAIL"));
        assertThat(uniqueCorrection.status(x.f().user, x.f().modelId, List.of(email)).get(0).blockers()).extracting(UniqueCorrectionDto.Issue::code).contains(Code.APPROVED_COLUMN);
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.table("고객"));
        assertThat(uniqueCorrection.status(x.f().user, x.f().modelId, List.of(email)).get(0).blockers()).extracting(UniqueCorrectionDto.Issue::code).contains(Code.APPROVED_TABLE);

        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 101; i++) many.add(i);
        assertThat(code(() -> uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, many, null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(email, email), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(email), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(code(() -> uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.a().get("CUST_NO")), null)))).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    @Test
    @DisplayName("AK-U17·D15 : 일반 UNIQUE 등록은 같은 컬럼 집합(순서 무관)의 UNIQUE 를 다시 만들지 않는다 (SAVED_DATA_EXISTS), 기본 이름 = UK_테이블_컬럼")
    void saveConstraintRejectsSameSet() {
        Fx x = generated("dup");
        long customer = x.table("고객");
        long br = column(customer, "BR_CD");
        long cd = column(customer, "CUST_CD");
        assertThatThrownBy(() -> physicalModelService.saveConstraint(x.f().user, new PhysicalModelDto.Constraint(null, customer, null, "UNIQUE", null,
                List.of(new PhysicalModelDto.ColumnRef(cd, "CUST_CD"), new PhysicalModelDto.ColumnRef(br, "BR_CD")), null)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SAVED_DATA_EXISTS));
        dropRelations(x.table("주문"), customer);
        dropUniques(customer);
        PhysicalModelDto.Constraint saved = physicalModelService.saveConstraint(x.f().user, new PhysicalModelDto.Constraint(null, customer, null, "UNIQUE", null,
                List.of(new PhysicalModelDto.ColumnRef(br, "BR_CD"), new PhysicalModelDto.ColumnRef(cd, "CUST_CD")), null));
        String table = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, customer);
        assertThat(saved.constraintName()).isEqualTo("UK_" + table + "_BR_CD_CUST_CD");
    }

    // ------------------------------------------------------------------ AK-03 FK 보정 · DEC-09

    @Test
    @DisplayName("AK-F10~F16 : 누락된 보조 식별자 참조 FK 는 단일도 보정할 수 있다 (USER 관계, 보류 아님). 부모 UNIQUE 가 일치하지 않으면 PARENT_UNIQUE_MISSING, UNIQUE 보정 뒤 다시 보정 가능")
    void foreignKeyCorrectionForAlternateKeys() {
        Fx x = generated("fkfix");
        dropRelations(x.table("주문"), x.table("고객"));
        assertThat(relationship(x, "이메일").structure().code()).isEqualTo("FK_MISSING");
        long email = x.r().get("이메일");
        long branch = x.r().get("지점");
        ForeignKeyCorrectionDto.Plan plan = fkCorrection.preview(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(email, branch), null));
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        assertThat(plan.items()).allSatisfy(i -> {
            assertThat(i.representation()).isEqualTo(ForeignKeyCorrectionDto.Representation.USER_RELATION);
            assertThat(i.referenceKey()).isEqualTo("ALTERNATE");
        });
        fkCorrection.apply(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(email, branch), plan.planToken()));
        assertThat(relationship(x, "이메일").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(relationship(x, "지점").status()).isEqualTo(CheckStatus.MATCH);
        assertThat(relations(x.table("주문"), x.table("고객"))).containsExactlyInAnyOrder("USER:false:ORD_EMAIL>EMAIL", "USER:false:ORD_BR>BR_CD,ORD_CD>CUST_CD");

        // 부모 UNIQUE 가 없으면 막는다 → UNIQUE 보정 뒤 보정 가능
        dropRelations(x.table("주문"), x.table("고객"));
        dropUniques(x.table("고객"));
        ForeignKeyCorrectionDto.Item blocked = fkCorrection.status(x.f().user, x.f().modelId, List.of(email)).get(0);
        assertThat(blocked.correctable()).isFalse();
        assertThat(blocked.blockers()).extracting(ForeignKeyCorrectionDto.Issue::code).contains(ForeignKeyCorrectionDto.Code.PARENT_UNIQUE_MISSING);
        UniqueCorrectionDto.Plan uk = uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_EMAIL")), null));
        uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.k().get("AK_EMAIL")), uk.planToken()));
        assertThat(fkCorrection.status(x.f().user, x.f().modelId, List.of(email)).get(0).correctable()).isTrue();
    }

    @Test
    @DisplayName("AK-F20·D12 : FK 가 참조하는 UNIQUE 를 지워도 FK 는 지우지 않고 진단만 FK_KEY_INVALID (불일치). 일반 PK 단일 FK 보정은 계속 보류 (SINGLE_FK_HELD 회귀는 FK 보정 테스트)")
    void referencedUniqueDeleted() {
        Fx x = generated("dec09");
        long ukId = jdbc.queryForObject("select c.CNSTRT_SN from TB_TBL_CNSTRT c join TB_TBL_CNSTRT_COL cc on cc.CNSTRT_SN = c.CNSTRT_SN join TB_COL col on col.COL_SN = cc.COL_SN"
                + " where c.TBL_SN = ? and col.COL_NM = 'EMAIL'", Long.class, x.table("고객"));
        physicalModelService.delete(x.f().user, PhysicalModelService.Kind.CONSTRAINT, ukId);
        assertThat(relations(x.table("주문"), x.table("고객"))).hasSize(2);
        Row row = relationship(x, "이메일");
        assertThat(row.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(row.structure().code()).isEqualTo("FK_KEY_INVALID");
        assertThat(alternate(x, "AK_EMAIL").structure().code()).isEqualTo("UNIQUE_MISSING");
    }

    // ------------------------------------------------------------------ 논리 짝 저장 (D09)

    @Test
    @DisplayName("AK-G15~G17 : 짝은 부모 주 식별자 또는 보조 식별자 하나의 속성 전체만. 보조 식별자 일부·식별자 혼합은 저장 거절, 보조 식별자 전체는 COMPLETE ALTERNATE")
    void pairRules() {
        Fx x = model("pairs", false, false);
        long branch = x.r().get("지점");
        LogicalModelDto.RelationshipPairs current = logical.selectRelationshipPairs(x.f().user, branch);
        assertThat(current.state().status()).isEqualTo("COMPLETE");
        assertThat(current.state().keyType()).isEqualTo("ALTERNATE");
        assertThat(current.state().keyIdentifierId()).isEqualTo(x.k().get("AK_BR"));
        assertThat(current.parentKeys()).extracting(LogicalModelDto.ParentKey::keyType).containsExactly("PRIMARY", "ALTERNATE", "ALTERNATE", "ALTERNATE", "ALTERNATE");
        // 보조 식별자 일부만
        assertThat(code(() -> logical.saveRelationshipPairs(x.f().user, branch, new LogicalModelDto.PairSave(current.modifiedAt(),
                List.of(new LogicalModelDto.PairInput(x.a().get("BR_CD"), x.a().get("ORD_BR"))))))).isEqualTo(ErrorCode.INVALID_LOGICAL_MODEL);
        // 주 식별자 + 보조 식별자 혼합
        assertThat(code(() -> logical.saveRelationshipPairs(x.f().user, branch, new LogicalModelDto.PairSave(current.modifiedAt(),
                List.of(new LogicalModelDto.PairInput(x.a().get("CUST_NO"), x.a().get("ORD_BR")), new LogicalModelDto.PairInput(x.a().get("EMAIL"), x.a().get("ORD_CD"))))))).isNotNull();
        assertThat(logical.selectRelationshipPairs(x.f().user, branch).state().keyIdentifierId()).isEqualTo(x.k().get("AK_BR"));
    }

    // ------------------------------------------------------------------ D17 교환 형식 그대로

    @Test
    @DisplayName("AK-G18·D17 : 모델 교환(형식 그대로) — 보조 식별자를 참조하는 짝·생성한 UNIQUE·보조 식별자 참조 FK 가 다른 프로젝트로 그대로 옮겨지고, 진단도 같다")
    void exchangeRoundTrip() throws Exception {
        Fx x = generated("exch");
        com.lexorma.data.exchange.service.ModelExchangeService exchange = context.getBean(com.lexorma.data.exchange.service.ModelExchangeService.class);
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Document document = exchange.export(x.f().user);
        com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Document file = json.readValue(json.writeValueAsString(document),
                com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Document.class);
        GenerationFixture target = GenerationFixture.create(context, "ak-exch-target-" + System.nanoTime(), DbType.POSTGRESQL);
        Long project = target.user.getProjectId();
        com.lexorma.core.security.domain.dto.AccountDto member = target.member(); // 교환 대상은 구성원 검사를 한다
        var plan = exchange.plan(member, new com.lexorma.data.exchange.domain.dto.ModelExchangeDto.PlanRequest(project, file, null));
        assertThat(plan.applicable()).as(String.valueOf(plan.warnings())).isTrue();
        exchange.apply(member, new com.lexorma.data.exchange.domain.dto.ModelExchangeDto.ApplyRequest(project, file, null, plan.planToken(), plan.requestId()));
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where PRJ_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Integer.class, project)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ? and REL_SRC_CD = 'USER'", Integer.class, project)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_REL_ATRB a join TB_LGCL_REL r on r.LGCL_REL_SN = a.LGCL_REL_SN where r.PRJ_SN = ?", Integer.class, project)).isEqualTo(3);
    }
}
