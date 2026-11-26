package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.ColumnPlan;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.InheritanceForeignKeyPlan;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto.TablePlan;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-7 2차 : 슈퍼타입·서브타입 B형(엔터티마다 테이블) 물리 생성 계획 — 상속 PK·상속 FK(단일·복합·3단계)·필요한 조상·기존 조상 테이블 검증·엔터티 문맥 관계·
 * 물리 미보장 제약 경고·계획 토큰 v7·일반 생성 회귀·격리 (docs/completed/subtype-physical-transformation.md 29장). 3차에 반영 차단을 풀었다 — 실제 생성은
 * {@link SubtypePhysicalGenerationIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SubtypePhysicalPlanIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 이 테스트의 엔터티명에 쓰는 표준 단어 (픽스처 기본 단어에 더한다 — 테이블명은 표준 단어 약어로 만든다) */
    private static final String[][] WORDS = {{"기업", "CORP"}, {"개인", "INDV"}, {"당사자", "PTY"}, {"조직", "ORG"}, {"사람", "PRSN"}, {"계약", "CNTRT"},
            {"루트", "ROOT"}, {"갑", "GAP"}, {"을", "EUL"}, {"외국", "FRGN"}};

    private GenerationFixture fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        for (String[] w : WORDS) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), w[0], w[1], w[1], w[0]);
        }
        return f;
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

    private static TablePlan table(Plan plan, long entity) {
        return plan.tables().stream().filter(t -> t.entityId().equals(entity)).findFirst().orElseThrow();
    }

    private static InheritanceForeignKeyPlan key(Plan plan, long child) {
        return plan.inheritanceForeignKeys().stream().filter(k -> k.childEntityId().equals(child)).findFirst().orElseThrow();
    }

    private static void error(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    /** 생성 반영이 남기는 모든 행 (테이블·컬럼·매핑·관계·버전) */
    private List<Integer> footprint(GenerationFixture f) {
        Long p = f.user.getProjectId();
        return List.of(f.count("select count(*) from TB_TBL where PRJ_SN = ?", p),
                f.count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ?", p),
                f.count("select count(*) from TB_ENTT_TBL_MPNG where PRJ_SN = ?", p),
                f.count("select count(*) from TB_ATRB_COL_MPNG a join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = a.ENTT_TBL_MPNG_SN where m.PRJ_SN = ?", p),
                f.count("select count(*) from TB_TBL_REL where PRJ_SN = ?", p),
                f.versions(),
                f.count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", p));
    }

    @Test
    @DisplayName("2단계 : 루트 = 일반 테이블, 서브타입 = 상속 PK(루트 주 식별자, PK·NOT NULL·참조 테이블) + 직접 속성만, 상속 FK 단일, 배타·완전 경고는 그룹당 1건, 반영 가능")
    void twoLevel() {
        GenerationFixture f = fixture("sp-two");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        f.attr(customer, "고객 이름", false, "CUST_NM");
        long company = entity(f, "기업 고객");
        long bizNo = f.attr(company, "사업자 번호", false, "BIZ_NO");
        long person = entity(f, "개인 고객");
        f.attr(person, "생년월일", false, "BRDT");
        group(f, customer, "고객 구분", false, true, company, person);

        Plan plan = generation.preview(f.user, f.request(List.of(customer, company, person)));
        assertThat(plan.creatable()).isTrue();
        assertThat(plan.executable()).as("3차부터 상속 계획도 반영 가능").isTrue();
        assertThat(plan.executionBlockedReason()).isNull();
        TablePlan root = table(plan, customer);
        assertThat(root.status()).isEqualTo("CREATE");
        assertThat(root.inheritance().role()).isEqualTo("ROOT");
        assertThat(root.inheritance().level()).isEqualTo(1);
        assertThat(root.columns()).extracting(ColumnPlan::columnName).containsExactly("CUST_NO", "CUST_NM");

        TablePlan sub = table(plan, company);
        assertThat(sub.status()).isEqualTo("CREATE");
        assertThat(sub.inheritance()).satisfies(i -> {
            assertThat(i.strategy()).isEqualTo("TABLE_PER_TYPE");
            assertThat(i.role()).isEqualTo("SUBTYPE");
            assertThat(i.level()).isEqualTo(2);
            assertThat(i.parentEntityId()).isEqualTo(customer);
            assertThat(i.rootEntityId()).isEqualTo(customer);
            assertThat(i.groupName()).isEqualTo("고객 구분");
            assertThat(i.reasons()).isEmpty();
        });
        // 상속 PK 먼저 (원본 속성 ID, 루트 표시), 상속 일반 속성(고객 이름)은 만들지 않는다
        assertThat(sub.columns()).extracting(ColumnPlan::attributeId, ColumnPlan::columnName, ColumnPlan::pk, ColumnPlan::nullable, ColumnPlan::inheritedFromEntityId)
                .containsExactly(tuple(customerNo, "CUST_NO", true, false, customer), tuple(bizNo, "BIZ_NO", false, true, null));
        assertThat(sub.columns().get(0).refTableName()).isEqualTo(root.tableName());
        assertThat(sub.warnings()).noneMatch(w -> w.contains("주 식별자가 없어"));

        assertThat(plan.inheritanceForeignKeys()).hasSize(2);
        InheritanceForeignKeyPlan k = key(plan, company);
        assertThat(k.status()).isEqualTo("READY");
        assertThat(k.fkType()).isEqualTo("SINGLE");
        assertThat(k.parentTableName()).isEqualTo(root.tableName());
        assertThat(k.parentTableId()).isNull();
        assertThat(k.refTableName()).isEqualTo(root.tableName());
        assertThat(k.columns()).extracting(PhysicalGenerationDto.FkColumn::parentColumnName, PhysicalGenerationDto.FkColumn::childColumnName).containsExactly(tuple("CUST_NO", "CUST_NO"));
        assertThat(k.constraintName()).isNotBlank();

        assertThat(plan.requiredAncestors()).isEmpty();
        assertThat(plan.constraintWarnings()).extracting(PhysicalGenerationDto.ConstraintWarning::constraint, PhysicalGenerationDto.ConstraintWarning::guarantee)
                .containsExactly(tuple("DISJOINT", "NOT_ENFORCED"), tuple("TOTAL", "NOT_ENFORCED"));
        // 상속 PK 컬럼이 여러 테이블에 있어도 식별자 진단이 원본 속성 ID 로 돌아온다 (임시 ID 가 결과에 새지 않는다)
        assertThat(plan.identifiers()).allMatch(i -> i.attributeId() == null || i.attributeId() > 0);
    }

    @Test
    @DisplayName("3단계·복합 키 : 중간 슈퍼타입은 한 테이블이 두 역할, 말단은 직계 부모(중간) PK 전체를 복합 FK 하나로 — 루트 식별자 순서")
    void threeLevelComposite() {
        GenerationFixture f = fixture("sp-three");
        long party = entity(f, "당사자");
        long a = f.attr(party, "기관 코드", true, "ORG_CD");
        long b = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, b, a); // 식별자 순서 = PTY_NO, ORG_CD
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", false, false, dept, team);

        Plan plan = generation.preview(f.user, f.request(List.of(party, org, human, dept, team)));
        assertThat(plan.creatable()).isTrue();
        assertThat(table(plan, org).inheritance().role()).isEqualTo("INTERMEDIATE");
        TablePlan leaf = table(plan, dept);
        assertThat(leaf.inheritance().level()).isEqualTo(3);
        assertThat(leaf.inheritance().parentEntityId()).isEqualTo(org);
        assertThat(leaf.inheritance().rootEntityId()).isEqualTo(party);
        assertThat(leaf.columns()).extracting(ColumnPlan::columnName, ColumnPlan::pk, ColumnPlan::refTableName).containsExactly(tuple("PTY_NO", true, null), tuple("ORG_CD", true, null));
        InheritanceForeignKeyPlan k = key(plan, dept);
        assertThat(k.status()).isEqualTo("READY");
        assertThat(k.fkType()).isEqualTo("COMPOSITE");
        assertThat(k.parentEntityId()).isEqualTo(org);
        assertThat(k.parentTableName()).isEqualTo(table(plan, org).tableName());
        assertThat(k.refTableName()).isNull();
        assertThat(k.columns()).extracting(PhysicalGenerationDto.FkColumn::order, PhysicalGenerationDto.FkColumn::parentColumnName).containsExactly(tuple(1, "PTY_NO"), tuple(2, "ORG_CD"));
        // 겹침 허용·부분 그룹은 경고 없음, 배타 그룹만 1건
        assertThat(plan.constraintWarnings()).extracting(PhysicalGenerationDto.ConstraintWarning::groupName, PhysicalGenerationDto.ConstraintWarning::constraint)
                .containsExactly(tuple("조직 구분", "DISJOINT"));
    }

    @Test
    @DisplayName("필요한 조상 : 서브타입만 고르면 오류 + 필요한 상위 엔터티 목록 (서버가 추가하지 않는다), 루트 주 식별자 없음")
    void requiredAncestors() {
        GenerationFixture f = fixture("sp-required");
        long party = entity(f, "당사자");
        f.pk(party, f.attr(party, "당사자 번호", true, "PTY_NO"));
        long org = entity(f, "조직");
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);

        Plan plan = generation.preview(f.user, f.request(List.of(dept, team)));
        assertThat(plan.creatable()).isFalse();
        assertThat(table(plan, dept).status()).isEqualTo("ERROR");
        assertThat(table(plan, dept).inheritance().reasons()).containsExactly("ANCESTOR_MISSING");
        assertThat(plan.requiredAncestors()).extracting(PhysicalGenerationDto.RequiredAncestor::entityId, PhysicalGenerationDto.RequiredAncestor::requiredBy)
                .containsExactly(tuple(party, List.of(dept, team)), tuple(org, List.of(dept, team)));
        assertThat(key(plan, dept).status()).isEqualTo("BLOCKED");
        assertThat(key(plan, dept).reason()).isEqualTo("ANCESTOR_MISSING");
        assertThat(plan.tables()).extracting(TablePlan::entityId).as("고른 엔터티만").containsExactly(dept, team);

        // 루트 주 식별자 없음
        GenerationFixture g = fixture("sp-nokey");
        long root = entity(g, "루트");
        g.attr(root, "이름", false, "NM");
        long x = entity(g, "갑");
        long y = entity(g, "을");
        group(g, root, "구분", true, false, x, y);
        Plan noKey = generation.preview(g.user, g.request(List.of(root, x, y)));
        assertThat(noKey.creatable()).isFalse();
        assertThat(table(noKey, x).inheritance().reasons()).containsExactly("ROOT_KEY_MISSING");
        assertThat(key(noKey, x).reason()).isEqualTo("ROOT_KEY_MISSING");
        assertThat(table(noKey, root).status()).as("루트 자신은 일반 규칙 (PK 없이 경고)").isEqualTo("CREATE");
    }

    @Test
    @DisplayName("기존 조상 : 유효하게 매핑된 루트는 SKIP 하고 그 테이블을 참조, PK 불일치는 오류(기존 테이블은 고치지 않는다), 상속 PK 매핑이 없는 매핑된 중간 엔터티는 오류 (추론하지 않는다)")
    void existingAncestors() {
        GenerationFixture f = fixture("sp-existing");
        long party = entity(f, "당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = entity(f, "조직");
        f.attr(org, "조직 이름", false, "ORG_NM");
        // 일반 엔터티일 때 루트·조직 테이블을 먼저 만든다
        Plan first = generation.preview(f.user, f.request(List.of(party, org)));
        generation.apply(f.user, f.request(List.of(party, org), first.planToken()));
        long partyTable = f.table(party);
        long human = entity(f, "사람");
        long dept = entity(f, "부서");
        long team = entity(f, "팀");
        group(f, party, "당사자 구분", true, false, org, human);
        group(f, org, "조직 구분", true, false, dept, team);

        // 사람 : 루트가 유효하게 매핑됨 → 루트 SKIP, 사람 CREATE, 상속 FK 가 기존 루트 테이블을 참조
        Plan plan = generation.preview(f.user, f.request(List.of(party, human)));
        assertThat(table(plan, party).status()).isEqualTo("SKIP");
        assertThat(table(plan, party).inheritance().role()).isEqualTo("ROOT");
        assertThat(table(plan, human).status()).isEqualTo("CREATE");
        InheritanceForeignKeyPlan k = key(plan, human);
        assertThat(k.status()).isEqualTo("READY");
        assertThat(k.parentTableId()).isEqualTo(partyTable);
        assertThat(k.columns()).extracting(PhysicalGenerationDto.FkColumn::parentColumnName).containsExactly("PTY_NO");
        assertThat(generation.preview(f.user, f.request(List.of(human))).tables()).extracting(TablePlan::status).as("루트를 고르지 않아도 기존 매핑이면 충분").containsExactly("CREATE");

        // 부서 : 중간(조직)은 매핑돼 있지만 상속 PK 매핑이 없다 → 추론하지 않고 오류
        Plan mid = generation.preview(f.user, f.request(List.of(dept)));
        assertThat(table(mid, dept).status()).isEqualTo("ERROR");
        assertThat(table(mid, dept).inheritance().reasons()).containsExactly("INHERITED_KEY_MAPPING_MISSING");
        assertThat(key(mid, dept).reason()).isEqualTo("INHERITED_KEY_MAPPING_MISSING");
        assertThat(mid.requiredAncestors()).isEmpty();

        // 루트 테이블 PK 가 매핑 컬럼과 달라지면 PARENT_KEY_MISMATCH (PK 를 지운다 — 기존 테이블을 고치지 않고 오류만)
        jdbc.update("update TB_COL set PK_YN = false where TBL_SN = ?", partyTable);
        Plan mismatch = generation.preview(f.user, f.request(List.of(human)));
        assertThat(table(mismatch, human).inheritance().reasons()).containsExactly("PARENT_KEY_MISMATCH");
        assertThat(mismatch.creatable()).isFalse();
        // 루트 주 식별자 속성 매핑이 없으면 ROOT_KEY_MAPPING_MISSING
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", partyNo);
        assertThat(table(generation.preview(f.user, f.request(List.of(human))), human).inheritance().reasons()).containsExactly("ROOT_KEY_MAPPING_MISSING");
    }

    @Test
    @DisplayName("관계 문맥 : 새 서브타입 부모의 상속 PK 를 참조하는 관계는 FK 계획, 상속 PK 매핑이 없는 기존 서브타입 테이블 부모는 FK 없이 (속성 ID 만으로 찾지 않는다)")
    void relationContext() {
        GenerationFixture f = fixture("sp-relation");
        long customer = entity(f, "고객");
        long customerNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, customerNo);
        long contract = entity(f, "계약");
        long contractNo = f.attr(contract, "계약 번호", true, "CNTRT_NO");
        f.pk(contract, contractNo);
        long holder = f.attr(contract, "계약자 번호", false, "HLDR_NO");
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        // 개인 고객은 일반 엔터티일 때 테이블을 만들어 둔다 (PK 없음 → 서브타입 연결 가능)
        f.attr(person, "생년월일", false, "BRDT");
        Plan pre = generation.preview(f.user, f.request(List.of(person)));
        generation.apply(f.user, f.request(List.of(person), pre.planToken()));
        group(f, customer, "고객 구분", true, false, company, person);

        // 기업 고객(새로 만들 서브타입) → 계약 : 짝의 부모 속성 = 상속받은 루트 주 식별자
        LogicalModelDto.Relationship toNew = f.rel("기업 계약", company, contract, false, 0);
        f.pairs(toNew, new long[]{customerNo, holder});
        Plan plan = generation.preview(f.user, f.request(List.of(customer, company, contract)));
        PhysicalGenerationDto.RelationPlan r = plan.relations().stream().filter(x -> x.relationshipId().equals(toNew.id())).findFirst().orElseThrow();
        assertThat(r.fkStatus()).isEqualTo("READY");
        assertThat(r.parentTableName()).isEqualTo(table(plan, company).tableName());
        assertThat(r.columns()).extracting(PhysicalGenerationDto.FkColumn::parentColumnName, PhysicalGenerationDto.FkColumn::childColumnName).containsExactly(tuple("CUST_NO", "HLDR_NO"));
        // 상속 FK 와 일반 FK 는 따로
        assertThat(plan.inheritanceForeignKeys()).extracting(InheritanceForeignKeyPlan::childEntityId).containsExactly(company);

        // 개인 고객(기존 테이블이 있는 서브타입) → 계약
        LogicalModelDto.Relationship toExisting = f.rel("개인 계약", person, contract, false, 0);
        long holder2 = f.attr(contract, "개인 계약자 번호", false, "PHLDR_NO");
        f.pairs(toExisting, new long[]{customerNo, holder2});
        Plan plan2 = generation.preview(f.user, f.request(List.of(contract)));
        PhysicalGenerationDto.RelationPlan r2 = plan2.relations().stream().filter(x -> x.relationshipId().equals(toExisting.id())).findFirst().orElseThrow();
        assertThat(r2.fkStatus()).isEqualTo("COLUMN_UNAVAILABLE");
        assertThat(r2.reasons()).contains("INHERITED_KEY_MAPPING_MISSING");
        assertThat(r2.fkCreated()).isFalse();
        assertThat(plan2.executable()).as("상속 엔터티를 만들지 않는 계획은 반영 가능").isTrue();
    }

    @Test
    @DisplayName("계획 토큰 v7 : 같은 상태면 같고, 그룹 제약·구성원·루트 식별자가 바뀌면 달라진다. 예전·바뀐 토큰은 409")
    void planToken() {
        GenerationFixture f = fixture("sp-token");
        long customer = entity(f, "고객");
        long no = f.attr(customer, "고객 번호", true, "CUST_NO");
        f.pk(customer, no);
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        LogicalSubtypeDto.Group g = group(f, customer, "고객 구분", true, false, company, person);
        List<Long> chosen = List.of(customer, company, person);
        String t1 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(generation.preview(f.user, f.request(chosen)).planToken()).isEqualTo(t1);

        LogicalSubtypeDto.Group now = subtypes.selectGroup(f.user, g.id());
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(now.id(), f.modelId, customer, "고객 구분", null, true, true, null,
                List.of(new MemberInput(company, null), new MemberInput(person, null)), now.modifiedAt()));
        String t2 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(t2).isNotEqualTo(t1);
        error(() -> generation.apply(f.user, f.request(chosen, t1)), ErrorCode.MODEL_CONFLICT);

        long other = entity(f, "외국 고객");
        now = subtypes.selectGroup(f.user, g.id());
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(now.id(), f.modelId, customer, "고객 구분", null, true, true, null,
                List.of(new MemberInput(company, null), new MemberInput(person, null), new MemberInput(other, null)), now.modifiedAt()));
        String t3 = generation.preview(f.user, f.request(chosen)).planToken();
        assertThat(t3).as("고른 엔터티의 구성 변화 없음 — 다른 구성원 추가는 그룹 수정 시각으로 반영").isNotEqualTo(t2);

        jdbc.update("update TB_LGCL_ATRB set MDFCN_DT = current_timestamp where LGCL_ATRB_SN = ?", no);
        assertThat(generation.preview(f.user, f.request(List.of(company, customer))).planToken()).isNotEqualTo(t3);
    }

    @Test
    @DisplayName("반영 허용 (3차) : 2차에 막던 상속 계획·일반 엔터티와 섞인 계획·루트만 고른 계획 모두 반영된다 — 일반 엔터티만의 계획은 그대로")
    void applyAllowed() {
        GenerationFixture f = fixture("sp-apply");
        long customer = entity(f, "고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", false, true, company, person);
        long product = entity(f, "상품");
        f.pk(product, f.attr(product, "상품 번호", true, "PRDT_NO"));

        Plan rootOnly = generation.preview(f.user, f.request(List.of(customer)));
        assertThat(rootOnly.executable()).isTrue();
        assertThat(generation.apply(f.user, f.request(List.of(customer), rootOnly.planToken())).tables()).isOne();
        Plan mixed = generation.preview(f.user, f.request(List.of(product, customer, company, person)));
        assertThat(table(mixed, customer).status()).isEqualTo("SKIP");
        PhysicalGenerationDto.Result result = generation.apply(f.user, f.request(List.of(product, customer, company, person), mixed.planToken()));
        assertThat(result.tables()).isEqualTo(3);
        assertThat(result.inheritanceForeignKeys()).isEqualTo(2);
        assertThat(footprint(f).get(0)).isEqualTo(4);

        long service = entity(f, "배송");
        f.pk(service, f.attr(service, "배송 번호", true, "DLVR_NO"));
        Plan normal = generation.preview(f.user, f.request(List.of(service)));
        assertThat(normal.executable()).isTrue();
        assertThat(normal.executionBlockedReason()).isNull();
        assertThat(normal.inheritanceForeignKeys()).isEmpty();
        assertThat(normal.constraintWarnings()).isEmpty();
        assertThat(table(normal, service).inheritance()).isNull();
        assertThat(generation.apply(f.user, f.request(List.of(service), normal.planToken())).tables()).isOne();
    }

    @Test
    @DisplayName("격리 : 다른 프로젝트 로그인으로 남의 상속 엔터티 계획을 볼 수 없다")
    void isolation() {
        GenerationFixture f = fixture("sp-iso");
        long customer = entity(f, "고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long company = entity(f, "기업 고객");
        long person = entity(f, "개인 고객");
        group(f, customer, "고객 구분", true, false, company, person);
        GenerationFixture other = fixture("sp-iso-other");
        assertThatThrownBy(() -> generation.preview(other.user, other.request(List.of(customer, company, person)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> generation.preview(other.user, new PhysicalGenerationDto.Request(List.of(company), null, null))).isInstanceOf(CustomException.class);
    }
}
