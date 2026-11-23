package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.InheritedKind;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Scope;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-NAME-1 상속 컬럼의 물리명 추천·명칭 진단 (docs/completed/physical-name-recommendation-m7.md 12장, NAME-T04~T06).
 * <ul>
 *   <li>A형 : 서브타입 속성 추천의 충돌·추천끼리 겹침을 루트 통합 테이블 기준으로 판정하고 (예전에는 테이블 없음으로 봐 충돌을 놓쳤다), 목록·미리보기·적용이 같다 (NAME-P06)</li>
 *   <li>B형 : 상속 PK 컬럼마다 NAME 행 (원본 = 루트 주 식별자 속성의 표준 용어), [상속 구조] 행은 그대로</li>
 *   <li>C형 : 말단마다 복제 컬럼의 NAME 행 (말단별 독립 판정, 매핑 없으면 UNMAPPED)</li>
 *   <li>B·C 상속 컬럼은 추천 목록 INHERITED 범위에서 읽기 전용 — 직접 매핑 목록에 섞이지 않고, 미리보기·적용은 INHERITED_READ_ONLY 로 거절 (NAME-P07)</li>
 * </ul>
 * H2·PostgreSQL 모두에서 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameInheritanceIntegrationTest {

    private static final Pageable ALL = PageRequest.of(0, 1000);

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired ModelMappingQualityService quality;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired PhysicalNameRenameService rename;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lexorma.data.mapping.service.MappingSuggestionService suggestions;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 회원(PK 회원 번호 MBR_NO, 회원 이름 MBR_NM, 회원 유형 코드 = 분류) → 개인 회원(생년월일 BRDT) · 기업 회원(사업자 번호 BRNO), 배타·완전 */
    record Tree(GenerationFixture f, long member, long memberNo, long memberName, long person, long birth, long corp, long bizNo) {
        List<Long> all() {
            return List.of(member, person, corp);
        }
    }

    Tree tree(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, name);
        for (String[] w : new String[][]{{"회원", "MBR"}, {"개인", "PSN"}, {"기업", "CORP"}}) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), w[0], w[1], w[1], w[0]);
        }
        long member = entity(f, "회원");
        long memberNo = f.attr(member, "회원 번호", true, "MBR_NO");
        f.pk(member, memberNo);
        long memberName = f.attr(member, "회원 이름", true, "MBR_NM");
        long type = f.attr(member, "회원 유형 코드", true, "MBR_TY_CD");
        long person = entity(f, "개인 회원");
        long birth = f.attr(person, "생년월일", false, "BRDT");
        long corp = entity(f, "기업 회원");
        long bizNo = f.attr(corp, "사업자 번호", false, "BRNO");
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "회원 구분", null, false, true, type,
                List.of(new MemberInput(person, "P"), new MemberInput(corp, "C")), null));
        return new Tree(f, member, memberNo, memberName, person, birth, corp, bizNo);
    }

    long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    void generate(Tree t, String strategy) {
        List<PhysicalGenerationDto.StrategyChoice> choices = strategy == null ? null : List.of(new PhysicalGenerationDto.StrategyChoice(t.member(), strategy));
        PhysicalGenerationDto.Plan plan = generation.preview(t.f().user, new PhysicalGenerationDto.Request(t.all(), null, null, null, choices));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(x -> x.errors().stream()).toList())).isTrue();
        generation.apply(t.f().user, new PhysicalGenerationDto.Request(t.all(), null, plan.planToken(), null, choices));
    }

    long column(long attributeId) {
        return jdbc.queryForObject("select COL_SN from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", Long.class, attributeId);
    }

    /** 상속 매핑 컬럼 : 그 엔터티 매핑 아래 원본 속성의 컬럼 (상속 PK 또는 C형 상속 일반 속성) */
    long inheritedColumn(String table, long entityId, long attributeId) {
        return jdbc.queryForObject("select k.COL_SN from " + table + " k join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = k.ENTT_TBL_MPNG_SN"
                + " where m.LGCL_ENTT_SN = ? and k.LGCL_ATRB_SN = ?", Long.class, entityId, attributeId);
    }

    void renameColumn(long columnId, String name) {
        jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", name, columnId);
    }

    String columnName(long columnId) {
        return jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, columnId);
    }

    List<ColumnRow> direct(Tree t) {
        return recommendation.selectColumnRows(t.f().user, new ColumnSearch(t.f().modelId, null, null, null, null, null, null), ALL).getContent();
    }

    List<ColumnRow> inherited(Tree t, Status status) {
        return recommendation.selectColumnRows(t.f().user, new ColumnSearch(t.f().modelId, null, null, null, status, null, null), ALL, Scope.INHERITED).getContent();
    }

    List<ModelMappingQualityDto.Row> qualityRows(Tree t) {
        return quality.selectRows(t.f().user, new ModelMappingQualityDto.Search(t.f().modelId, null, null, null, null, null), PageRequest.of(0, 100_000)).getContent();
    }

    static List<ApplyCode> blockers(ColumnRow row) {
        return row.applicability().blockers().stream().map(ApplyIssue::code).toList();
    }

    PhysicalNameRenameDto.Plan preview(Tree t, long columnId, String name) {
        return rename.preview(t.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, columnId, name, null)), null));
    }

    // ------------------------------------------------------------------ A형

    @Test
    @DisplayName("NAME-T04 A형 : 서브타입 속성의 추천 충돌·추천끼리 겹침·자기 컬럼 제외를 루트 통합 테이블 기준으로 — 목록 상태·필터·요약·미리보기·적용이 같다, 정상 항목은 적용되고 이력이 남는다")
    void singleTable() {
        Tree t = tree("name-a");
        generate(t, "SINGLE_TABLE");
        long root = t.f().table(t.member());
        long birthColumn = column(t.birth());
        long bizColumn = column(t.bizNo());
        long nameColumn = column(t.memberName());
        assertThat(jdbc.queryForObject("select TBL_SN from TB_COL where COL_SN = ?", Long.class, birthColumn)).as("서브타입 속성 컬럼 = 통합 테이블").isEqualTo(root);

        // 서브타입 속성 BRDT 를 다른 이름으로, 루트 속성(회원 이름) 컬럼을 BRDT 로 — 서브타입 속성의 추천명이 통합 테이블의 다른 컬럼과 충돌
        renameColumn(birthColumn, "O_BIRTH");
        renameColumn(nameColumn, "brdt");
        // 자기 자신 : 사업자 번호 컬럼을 소문자로 — 일치, 충돌 아님
        renameColumn(bizColumn, "brno");

        Map<Long, ColumnRow> rows = direct(t).stream().collect(Collectors.toMap(ColumnRow::logicalAttributeId, r -> r));
        ColumnRow birth = rows.get(t.birth());
        assertThat(birth.tableId()).as("행의 테이블 = 컬럼의 실제 테이블 (엔터티 매핑이 없어도)").isEqualTo(root);
        assertThat(birth.status()).isEqualTo(Status.CONFLICT);
        assertThat(blockers(birth)).contains(ApplyCode.NAME_CONFLICT);
        assertThat(rows.get(t.bizNo()).status()).as("자기 컬럼은 충돌로 보지 않는다").isEqualTo(Status.ALREADY_MATCHED);
        assertThat(rows.get(t.memberName()).status()).isEqualTo(Status.AVAILABLE);

        // 상태 필터·요약 = 행 상태
        for (Status status : Status.values()) {
            assertThat(recommendation.selectColumnRows(t.f().user, new ColumnSearch(t.f().modelId, null, null, null, status, null, null), ALL).getContent())
                    .extracting(ColumnRow::logicalAttributeId).as(status.name())
                    .containsExactlyInAnyOrderElementsOf(rows.values().stream().filter(r -> r.status() == status).map(ColumnRow::logicalAttributeId).toList());
        }
        ColumnSummary summary = recommendation.selectColumnSummary(t.f().user, t.f().modelId);
        assertThat(summary.conflict()).isEqualTo(rows.values().stream().filter(r -> r.status() == Status.CONFLICT).count());

        // 미리보기 = 목록 (충돌), 적용 거절 · 이름 그대로
        PhysicalNameRenameDto.Plan conflict = preview(t, birthColumn, "BRDT");
        assertThat(conflict.items().get(0).applicability().blockers()).extracting(ApplyIssue::code).contains(ApplyCode.NAME_CONFLICT);
        assertThatThrownBy(() -> rename.apply(t.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, birthColumn, "BRDT",
                conflict.items().get(0).modifiedAt())), conflict.planToken()))).isInstanceOf(CustomException.class);
        assertThat(columnName(birthColumn)).isEqualTo("O_BIRTH");

        // 서브타입 사이 추천끼리 겹침 : 사업자 번호 용어의 약어를 BRDT 로 바꾸면, 루트 테이블 안에서 두 서브타입 속성이 같은 새 이름을 받는다
        renameColumn(nameColumn, "MBR_NM");
        renameColumn(bizColumn, "O_BIZ");
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'BRDT' where STD_TRM_SN = (select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?)", t.bizNo());
        rows = direct(t).stream().collect(Collectors.toMap(ColumnRow::logicalAttributeId, r -> r));
        assertThat(rows.get(t.birth()).status()).isEqualTo(Status.AVAILABLE);
        assertThat(blockers(rows.get(t.birth()))).contains(ApplyCode.RECOMMENDATION_DUPLICATE);
        assertThat(blockers(rows.get(t.bizNo()))).contains(ApplyCode.RECOMMENDATION_DUPLICATE);
        PhysicalNameRenameDto.Plan both = rename.preview(t.f().user, new PhysicalNameRenameDto.Request(List.of(
                new PhysicalNameRenameDto.Item(TargetType.COLUMN, birthColumn, "BRDT", null), new PhysicalNameRenameDto.Item(TargetType.COLUMN, bizColumn, "BRDT", null)), null));
        assertThat(both.items()).allSatisfy(i -> assertThat(i.applicability().blockers()).extracting(ApplyIssue::code).contains(ApplyCode.RECOMMENDATION_DUPLICATE));

        // 정상 적용 : 생년월일 하나만 고르면 적용되고 RENAME 변경 이력이 남는다
        PhysicalNameRenameDto.Plan one = preview(t, birthColumn, "BRDT");
        assertThat(one.applicable()).as(String.valueOf(one.items())).isTrue();
        rename.apply(t.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, birthColumn, "BRDT", one.items().get(0).modifiedAt())),
                one.planToken()));
        assertThat(columnName(birthColumn)).isEqualTo("BRDT");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? and CHG_GROUP_ID like 'RENAME-%'",
                Integer.class, birthColumn)).isOne();

        // 승인 제한 : 통합 테이블 승인 → 서브타입 속성 컬럼도 TABLE_APPROVED
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", root);
        assertThat(blockers(direct(t).stream().filter(r -> r.logicalAttributeId().equals(t.bizNo())).findFirst().orElseThrow())).contains(ApplyCode.TABLE_APPROVED);
        assertThat(inherited(t, null)).as("A형에는 상속 매핑 컬럼이 없다").isEmpty();
    }

    // ------------------------------------------------------------------ B형

    @Test
    @DisplayName("NAME-T05 B형 : 상속 PK 컬럼마다 NAME 행 (일치·불일치·표준 용어 없음), [상속 구조] 행 유지, 추천 INHERITED 범위의 읽기 전용 행, 직접 목록과 겹치지 않음, 미리보기·적용 거절")
    void tablePerType() {
        Tree t = tree("name-b");
        generate(t, null);
        long personKey = inheritedColumn("TB_SBTYP_IDNTFR_COL_MPNG", t.person(), t.memberNo());
        long corpKey = inheritedColumn("TB_SBTYP_IDNTFR_COL_MPNG", t.corp(), t.memberNo());
        List<ModelMappingQualityDto.Row> before = qualityRows(t);
        List<ModelMappingQualityDto.Row> inheritanceBefore = before.stream().filter(r -> r.type() == CheckType.INHERITANCE).toList();
        assertThat(before).filteredOn(r -> r.type() == CheckType.NAME && "INHERITED_KEY".equals(r.code())).hasSize(2)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));

        renameColumn(personKey, "PSN_MBR_NO");
        List<ModelMappingQualityDto.Row> after = qualityRows(t);
        assertThat(after).filteredOn(r -> r.type() == CheckType.NAME && Long.valueOf(personKey).equals(r.columnId())).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.MISMATCH);
            assertThat(r.entityId()).isEqualTo(t.person());
            assertThat(r.attributeId()).isEqualTo(t.memberNo());
            assertThat(r.logicalValue()).isEqualTo("MBR_NO");
            assertThat(r.code()).isEqualTo("INHERITED_KEY");
        });
        assertThat(after).filteredOn(r -> r.type() == CheckType.NAME && Long.valueOf(corpKey).equals(r.columnId())).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        assertThat(after.stream().filter(r -> r.type() == CheckType.INHERITANCE).map(r -> r.id() + "|" + r.status()).toList()).as("[상속 구조] 행은 그대로")
                .containsExactlyElementsOf(inheritanceBefore.stream().map(r -> r.id() + "|" + r.status()).toList());
        // 같은 컬럼의 NAME 행은 하나
        assertThat(after.stream().filter(r -> r.type() == CheckType.NAME && r.columnId() != null).collect(Collectors.groupingBy(ModelMappingQualityDto.Row::columnId, Collectors.counting())))
                .allSatisfy((id, n) -> assertThat(n).as("컬럼 " + id).isOne());
        // ERD 매핑 표시 : 서브타입 상속 PK 의 불일치는 서브타입의 상속 PK 연결에만 — 루트 엔터티의 PK 속성 표시는 그대로 일치
        var overlays = suggestions.entityOverlays(t.f().user, t.f().modelId, List.of(t.member(), t.person()));
        assertThat(overlays.stream().filter(o -> o.entityId().equals(t.member())).findFirst().orElseThrow().attributes())
                .filteredOn(a -> a.attributeId().equals(t.memberNo())).singleElement()
                .satisfies(a -> assertThat(a.qualityStatus()).isEqualTo(CheckStatus.MATCH));
        assertThat(overlays.stream().filter(o -> o.entityId().equals(t.person())).findFirst().orElseThrow().inheritedKeys()).singleElement()
                .satisfies(a -> assertThat(a.qualityStatus()).isEqualTo(CheckStatus.MISMATCH));
        // 이름 검색 (컬럼명) 과 유형 조건
        assertThat(quality.selectRows(t.f().user, new ModelMappingQualityDto.Search(t.f().modelId, null, CheckType.NAME, CheckStatus.MISMATCH, "columnName", "psn_mbr"),
                PageRequest.of(0, 100)).getContent()).extracting(ModelMappingQualityDto.Row::columnId).containsExactly(personKey);

        // 추천 : 상속 범위의 읽기 전용 행, 직접 목록에는 없다
        assertThat(direct(t)).extracting(ColumnRow::physicalColumnId).doesNotContain(personKey, corpKey);
        List<ColumnRow> inherited = inherited(t, null);
        assertThat(inherited).extracting(ColumnRow::physicalColumnId).containsExactlyInAnyOrder(personKey, corpKey);
        ColumnRow person = inherited.stream().filter(r -> r.physicalColumnId().equals(personKey)).findFirst().orElseThrow();
        assertThat(person.status()).isEqualTo(Status.AVAILABLE);
        assertThat(person.recommendedPhysicalName()).isEqualTo("MBR_NO");
        assertThat(person.entityId()).isEqualTo(t.person());
        assertThat(person.inheritance().strategy()).isEqualTo("B");
        assertThat(person.inheritance().kind()).isEqualTo(InheritedKind.KEY);
        assertThat(person.inheritance().originEntityId()).isEqualTo(t.member());
        assertThat(person.applicability().selectable()).isFalse();
        assertThat(blockers(person)).contains(ApplyCode.INHERITED_READ_ONLY);
        assertThat(inherited(t, Status.ALREADY_MATCHED)).extracting(ColumnRow::physicalColumnId).containsExactly(corpKey);
        assertThat(recommendation.selectColumnSummary(t.f().user, t.f().modelId).inherited()).satisfies(s -> {
            assertThat(s.total()).isEqualTo(2);
            assertThat(s.available()).isOne();
            assertThat(s.alreadyMatched()).isOne();
        });
        // 컬럼 하나로 찾으면 (컬럼 상세의 추천 표시) 상속 행
        assertThat(recommendation.selectColumnRows(t.f().user, new ColumnSearch(null, null, null, personKey, null, null, null), PageRequest.of(0, 1)).getContent())
                .singleElement().satisfies(r -> assertThat(r.inheritance()).isNotNull());

        // 서버 적용 거절 : 미리보기 차단, 토큰을 받아도 적용은 400 — 이름 그대로
        PhysicalNameRenameDto.Plan plan = preview(t, personKey, "MBR_NO");
        assertThat(plan.items().get(0).applicability().blockers()).extracting(ApplyIssue::code).containsExactly(ApplyCode.INHERITED_READ_ONLY);
        assertThatThrownBy(() -> rename.apply(t.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, personKey, "MBR_NO",
                plan.items().get(0).modifiedAt())), plan.planToken())))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL));
        assertThat(columnName(personKey)).isEqualTo("PSN_MBR_NO");

        // 표준 용어 없음 : 루트 주 식별자 속성의 용어를 떼면 상속 PK NAME 은 비교 불가
        jdbc.update("update TB_LGCL_ATRB set STD_TRM_SN = null where LGCL_ATRB_SN = ?", t.memberNo());
        assertThat(qualityRows(t)).filteredOn(r -> r.type() == CheckType.NAME && "INHERITED_KEY".equals(r.code())).hasSize(2)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CheckStatus.NOT_COMPARABLE));
        assertThat(inherited(t, Status.UNAVAILABLE)).hasSize(2);
    }

    // ------------------------------------------------------------------ C형

    @Test
    @DisplayName("NAME-T06 C형 : 말단마다 루트 PK·조상 속성 복제 컬럼의 NAME 행 (말단별 독립, 매핑 없으면 UNMAPPED), 추천 INHERITED 읽기 전용, 적용 거절")
    void tablePerConcrete() {
        Tree t = tree("name-c");
        generate(t, "TABLE_PER_CONCRETE");
        long personKey = inheritedColumn("TB_SBTYP_IDNTFR_COL_MPNG", t.person(), t.memberNo());
        long personName = inheritedColumn("TB_SBTYP_ATRB_COL_MPNG", t.person(), t.memberName());
        long corpName = inheritedColumn("TB_SBTYP_ATRB_COL_MPNG", t.corp(), t.memberName());
        List<ModelMappingQualityDto.Row> before = qualityRows(t);
        assertThat(before).filteredOn(r -> r.type() == CheckType.NAME && r.attributeId() != null && r.attributeId().equals(t.memberName())).hasSize(2)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));

        // 말단 하나만 불일치
        renameColumn(corpName, "CORP_MBR_NM");
        List<ModelMappingQualityDto.Row> after = qualityRows(t);
        assertThat(after).filteredOn(r -> r.type() == CheckType.NAME && Long.valueOf(corpName).equals(r.columnId())).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.MISMATCH));
        assertThat(after).filteredOn(r -> r.type() == CheckType.NAME && Long.valueOf(personName).equals(r.columnId())).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        assertThat(after).filteredOn(r -> r.type() == CheckType.NAME && Long.valueOf(personKey).equals(r.columnId())).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        assertThat(after.stream().filter(r -> r.type() == CheckType.NAME && r.columnId() != null).collect(Collectors.groupingBy(ModelMappingQualityDto.Row::columnId, Collectors.counting())))
                .allSatisfy((id, n) -> assertThat(n).as("컬럼 " + id).isOne());
        assertThat(after).filteredOn(r -> r.type() == CheckType.NAME && r.attributeId() != null && r.attributeId().equals(t.memberName()))
                .extracting(ModelMappingQualityDto.Row::code).containsOnly("INHERITED_ATTRIBUTE");

        // 추천 : 말단별 행 (KEY · ATTRIBUTE), 읽기 전용
        List<ColumnRow> inherited = inherited(t, null);
        assertThat(inherited).allSatisfy(r -> {
            assertThat(r.inheritance().strategy()).isEqualTo("C");
            assertThat(r.applicability().selectable()).isFalse();
            assertThat(blockers(r)).contains(ApplyCode.INHERITED_READ_ONLY);
        });
        assertThat(inherited).filteredOn(r -> r.logicalAttributeId().equals(t.memberName())).extracting(ColumnRow::physicalColumnId, ColumnRow::status)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(personName, Status.ALREADY_MATCHED), org.assertj.core.groups.Tuple.tuple(corpName, Status.AVAILABLE));
        assertThat(inherited).filteredOn(r -> r.physicalColumnId().equals(personKey)).singleElement()
                .satisfies(r -> assertThat(r.inheritance().kind()).isEqualTo(InheritedKind.KEY));
        assertThat(inherited).filteredOn(r -> r.physicalColumnId().equals(corpName)).singleElement()
                .satisfies(r -> assertThat(r.inheritance().kind()).isEqualTo(InheritedKind.ATTRIBUTE));
        assertThat(direct(t)).extracting(ColumnRow::physicalColumnId).doesNotContain(personKey, personName, corpName);
        PhysicalNameRenameDto.Plan plan = preview(t, corpName, "MBR_NM");
        assertThat(plan.applicable()).isFalse();
        assertThat(plan.items().get(0).applicability().blockers()).extracting(ApplyIssue::code).containsExactly(ApplyCode.INHERITED_READ_ONLY);
        assertThatThrownBy(() -> rename.apply(t.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, corpName, "MBR_NM",
                plan.items().get(0).modifiedAt())), plan.planToken()))).isInstanceOf(CustomException.class);
        assertThat(columnName(corpName)).isEqualTo("CORP_MBR_NM");

        // 매핑 누락 : 기업 말단의 회원 이름 복제 매핑을 지우면 그 말단만 UNMAPPED (컬럼은 남고 추천 목록에서도 빠진다)
        jdbc.update("delete from TB_SBTYP_ATRB_COL_MPNG where COL_SN = ?", corpName);
        List<ModelMappingQualityDto.Row> missing = qualityRows(t);
        // 복제 매핑이 없으면 그 말단의 진단은 매핑 행 하나(UNMAPPED)뿐 — NAME 행을 만들지 않는다 (StrategyQualityRows, 기존 계약)
        assertThat(missing).filteredOn(r -> r.type() == CheckType.NAME && r.attributeId() != null && r.attributeId().equals(t.memberName()) && r.entityId().equals(t.corp())).isEmpty();
        assertThat(missing).filteredOn(r -> r.type() == CheckType.MAPPING && r.attributeId() != null && r.attributeId().equals(t.memberName()) && r.entityId().equals(t.corp()))
                .singleElement().satisfies(r -> {
                    assertThat(r.status()).isEqualTo(CheckStatus.UNMAPPED);
                    assertThat(r.code()).isEqualTo("INHERITED_ATTRIBUTE_MAPPING_MISSING");
                });
        assertThat(missing).filteredOn(r -> r.type() == CheckType.NAME && Long.valueOf(personName).equals(r.columnId())).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.MATCH));
        assertThat(inherited(t, null)).extracting(ColumnRow::physicalColumnId).doesNotContain(corpName);
    }
}
