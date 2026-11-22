package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M7-U10 물리명 추천 컬럼명 충돌 판정 통일 (docs/completed/column-name-uniqueness-m7.md 15장) : 추천 목록(행 상태·적용 가능·상태 필터·요약)·미리보기·적용이
 * 같은 기준 — 저장된 COL_NM_CMPR_VL ↔ 추천명의 DB 비교 값 (그 DB 의 UNIQUE 와 같다). 표준화 일치(ALREADY_MATCHED) 판정·추천명은 그대로. H2 / PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameRecommendationConflictIntegrationTest {

    private static final Pageable ALL = PageRequest.of(0, 500);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logicalService;
    @Autowired ModelMappingService mappingService;
    @Autowired PhysicalNameRecommendationService service;
    @Autowired PhysicalNameRenameService rename;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 한 사례 = 테이블 하나 : 매핑된 컬럼(current, 용어 약어 = abbreviation → 추천명) + 같은 테이블의 다른 컬럼(other, 매핑 없음) */
    private record Case(String label, String abbreviation, String current, String other, boolean otherTable, Boolean conflict, Long attributeId, Long columnId) {
    }

    private boolean h2() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toUpperCase().contains("H2")));
    }

    @Test
    @DisplayName("충돌 판정 : 대소문자·확장 대문자(ß·합자)·앞뒤 공백은 충돌, 다른 테이블·자기 자신·결합 문자·전각은 아님 — 목록 상태·적용 가능·필터·요약·미리보기·적용이 같다")
    void sameRuleEverywhere() {
        AccountDto admin = loginAsAdmin("m7u10-" + System.nanoTime());
        long projectId = admin.getProjectId();
        SchemaInfo schema = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        Model model = logicalService.saveModel(admin, new Model(null, "충돌 판정", null));
        boolean icuKnows = !h2() && "Ƛ_CD".equals(jdbc.queryForObject("select upper('ƛ_CD' collate \"und-x-icu\")", String.class));
        List<Case> specs = List.of(
                new Case("대소문자", "CUSTOMER_ID", "O_CUSTOMER_ID", "customer_id", false, true, null, null),
                new Case("확장 대문자 ß", "ROLE_CLASS_CD", "O_ROLE_CLASS_CD", "role_claß_cd", false, true, null, null),
                new Case("합자 ﬁ", "FI_CD", "O_FI_CD", "ﬁ_cd", false, true, null, null),
                new Case("앞뒤 U+0020", "TRIM_CD", "O_TRIM_CD", " TRIM_CD ", false, true, null, null),
                new Case("다른 테이블", "OTHER_CD", "O_OTHER_CD", "OTHER_CD", true, false, null, null),
                new Case("결합 문자 é ↔ e+U+0301", "É_CD", "O_E_CD", "é_cd", false, false, null, null),
                new Case("전각 ↔ ASCII", "ＦＵＬＬ_CD", "O_FULL_CD", "FULL_CD", false, false, null, null),
                new Case("자기 자신 (STRAßE_CD → STRASSE_CD)", "STRASSE_CD", "STRAßE_CD", "UNRELATED_CD", false, false, null, null),
                // Java 21 이 모르는 대소문자 짝 (Unicode 16.0) : PostgreSQL ICU 는 같은 이름, H2(Java) 는 다른 이름 — 각 DB 의 UNIQUE 와 같게
                new Case("Unicode 판 차이 Ƛ ↔ ƛ", "Ƛ_CD", "O_LAMBDA_CD", "ƛ_CD", false, icuKnows, null, null));
        List<Case> cases = new ArrayList<>();
        for (Case c : specs) {
            TableInfo table = table(projectId, schema, "TB_" + Math.abs(c.label().hashCode()));
            Entity entity = mapped(admin, model, c.label(), table);
            StandardTerm term = term(projectId, "용어_" + Math.abs(c.label().hashCode()), c.abbreviation());
            ColumnInfo current = column(projectId, table, c.current(), term);
            Long attributeId = attr(admin, entity, "속성 " + c.label(), term, current);
            TableInfo otherTable = c.otherTable() ? table(projectId, schema, "TB_O" + Math.abs(c.label().hashCode())) : table;
            column(projectId, otherTable, c.other(), null);
            cases.add(new Case(c.label(), c.abbreviation(), c.current(), c.other(), c.otherTable(), c.conflict(), attributeId, current.getId()));
        }

        List<ColumnRow> rows = service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, null, null, null), ALL).getContent();
        Map<Long, ColumnRow> byAttribute = rows.stream().collect(Collectors.toMap(ColumnRow::logicalAttributeId, r -> r));
        Set<Long> conflictFilter = ids(service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, Status.CONFLICT, null, null), ALL).getContent());
        Set<Long> availableFilter = ids(service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, Status.AVAILABLE, null, null), ALL).getContent());
        Map<String, String> report = new LinkedHashMap<>();
        for (Case c : cases) {
            ColumnRow row = byAttribute.get(c.attributeId());
            boolean listConflict = row.status() == Status.CONFLICT;
            boolean listBlocked = row.applicability().blockers().stream().map(ApplyIssue::code).anyMatch(ApplyCode.NAME_CONFLICT::equals);
            PhysicalNameRenameDto.Plan plan = rename.preview(admin, request(c.columnId(), row.recommendedPhysicalName().toUpperCase(java.util.Locale.ROOT), null, null));
            boolean previewConflict = plan.items().get(0).applicability().blockers().stream().map(ApplyIssue::code).anyMatch(ApplyCode.NAME_CONFLICT::equals);
            report.put(c.label(), row.status() + " / 목록 차단=" + listBlocked + " / 미리보기 충돌=" + previewConflict);
            assertThat(listConflict).as(c.label() + " 목록 상태 " + row.status()).isEqualTo(c.conflict());
            assertThat(listBlocked).as(c.label() + " 목록 적용 가능").isEqualTo(c.conflict());
            assertThat(previewConflict).as(c.label() + " 미리보기").isEqualTo(c.conflict());
            assertThat(conflictFilter.contains(c.attributeId())).as(c.label() + " 충돌 필터").isEqualTo(c.conflict());
            if (c.conflict()) assertThat(availableFilter).as(c.label() + " 적용 가능 필터").doesNotContain(c.attributeId());
            // 자기 자신 사례의 '일치(ALREADY_MATCHED)' 는 표준화 규칙이다 (이번에 바꾸지 않음) : H2 목록 SQL 은 UPPER 가 ß → SS 라 일치로, 행 판정(Java equalsIgnoreCase)은 추천 가능으로 본다 — 15.4
            else if (!c.label().startsWith("자기 자신")) assertThat(availableFilter.contains(c.attributeId())).as(c.label() + " 적용 가능 필터").isEqualTo(row.status() == Status.AVAILABLE);
            if (c.conflict()) {
                PhysicalNameRenameDto.PlanItem item = plan.items().get(0);
                assertThatThrownBy(() -> rename.apply(admin, request(c.columnId(), item.recommendedName(), item.modifiedAt(), plan.planToken())))
                        .as(c.label() + " 적용 거절").isInstanceOf(CustomException.class);
                assertThat(name(c.columnId())).as(c.label() + " 이름 그대로").isEqualTo(c.current());
            }
        }
        System.out.println("[M7-U10 판정] " + (h2() ? "H2" : "PostgreSQL") + " " + report);
        // 요약 = 필터 건수 = 행 상태 수
        ColumnSummary summary = service.selectColumnSummary(admin, model.id());
        assertThat(summary.conflict()).isEqualTo(conflictFilter.size()).isEqualTo(rows.stream().filter(r -> r.status() == Status.CONFLICT).count());
        assertThat(summary.available()).isEqualTo(availableFilter.size());
        assertThat(conflictFilter).as("충돌 필터 = 충돌 사례").containsExactlyInAnyOrderElementsOf(cases.stream().filter(Case::conflict).map(Case::attributeId).toList());
        assertThat(summary.total()).isEqualTo(rows.size());
        // 페이지 경계 : 2건씩 나눠도 상태·전체 건수가 같다 (서버 페이징·필터 그대로)
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 6; page++) {
            var p = service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, Status.CONFLICT, null, null), PageRequest.of(page, 2));
            assertThat(p.getTotalElements()).isEqualTo(conflictFilter.size());
            p.getContent().forEach(r -> {
                assertThat(r.status()).isEqualTo(Status.CONFLICT);
                paged.add(r.logicalAttributeId());
            });
        }
        assertThat(paged).containsExactlyInAnyOrderElementsOf(conflictFilter);
        List<Long> allPaged = new ArrayList<>();
        for (int page = 0; page < 10; page++) service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, null, null, null), PageRequest.of(page, 2))
                .getContent().forEach(r -> allPaged.add(r.logicalAttributeId()));
        assertThat(allPaged).as("정렬·페이지 순서 = 한 번에 읽은 순서").containsExactlyElementsOf(rows.stream().map(ColumnRow::logicalAttributeId).toList());
        // 이름 검색
        assertThat(service.selectColumnRows(admin, new ColumnSearch(model.id(), null, null, null, null, "currentPhysicalName", "O_ROLE_CLASS"), ALL).getContent())
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(Status.CONFLICT));

        // 정상 항목 적용 : 전각 사례 (충돌 아님) → 추천명으로 바뀐다
        Case full = cases.stream().filter(c -> c.label().startsWith("전각")).findFirst().orElseThrow();
        PhysicalNameRenameDto.Plan ok = rename.preview(admin, request(full.columnId(), "ＦＵＬＬ_CD", null, null));
        assertThat(ok.applicable()).as(String.valueOf(ok.items())).isTrue();
        rename.apply(admin, request(full.columnId(), ok.items().get(0).recommendedName(), ok.items().get(0).modifiedAt(), ok.planToken()));
        assertThat(name(full.columnId())).isEqualTo("ＦＵＬＬ_CD");
        assertThat(name(cases.get(1).columnId())).as("충돌 쪽은 그대로").isEqualTo("O_ROLE_CLASS_CD");

        // 다른 프로젝트의 같은 이름은 보지 않는다
        AccountDto other = loginAsAdmin("m7u10-other-" + System.nanoTime());
        assertThat(service.selectColumnSummary(other, logicalService.saveModel(other, new Model(null, "빈 모델", null)).id()).total()).isZero();
    }

    @Test
    @DisplayName("미리보기 이후 생긴 충돌은 적용에서 거절, 함께 고른 추천끼리 확장 대문자로 겹치면 미리보기·적용 거절, 맞바꾸기·순환은 기존처럼 거절")
    void laterConflictsAndSelections() {
        AccountDto admin = loginAsAdmin("m7u10-b-" + System.nanoTime());
        long projectId = admin.getProjectId();
        SchemaInfo schema = tx.execute(s -> schemaService.resolveSchema(projectId, null));
        Model model = logicalService.saveModel(admin, new Model(null, "충돌 뒤", null));
        TableInfo table = table(projectId, schema, "TB_LATE");
        Entity entity = mapped(admin, model, "늦은 충돌", table);
        StandardTerm late = term(projectId, "늦은_코드", "LATE_CLASS_CD");
        ColumnInfo lateColumn = column(projectId, table, "O_LATE_CLASS_CD", late);
        attr(admin, entity, "늦은 코드", late, lateColumn);
        PhysicalNameRenameDto.Plan plan = rename.preview(admin, request(lateColumn.getId(), "LATE_CLASS_CD", null, null));
        assertThat(plan.applicable()).isTrue();
        column(projectId, table, "late_claß_cd", null); // 미리보기 뒤에 확장 대문자로 같은 이름이 생긴다
        PhysicalNameRenameDto.PlanItem item = plan.items().get(0);
        assertThatThrownBy(() -> rename.apply(admin, request(lateColumn.getId(), "LATE_CLASS_CD", item.modifiedAt(), plan.planToken()))).isInstanceOf(CustomException.class);
        assertThat(name(lateColumn.getId())).isEqualTo("O_LATE_CLASS_CD");

        // 함께 고른 추천끼리 : MULTI_SS 와 multi_ß 는 같은 비교 값
        TableInfo multi = table(projectId, schema, "TB_MULTI");
        Entity multiEntity = mapped(admin, model, "여럿", multi);
        StandardTerm t1 = term(projectId, "여럿_1", "MULTI_SS");
        StandardTerm t2 = term(projectId, "여럿_2", "multi_ß");
        ColumnInfo c1 = column(projectId, multi, "O_M1", t1);
        ColumnInfo c2 = column(projectId, multi, "O_M2", t2);
        attr(admin, multiEntity, "여럿 하나", t1, c1);
        attr(admin, multiEntity, "여럿 둘", t2, c2);
        PhysicalNameRenameDto.Plan both = rename.preview(admin, new PhysicalNameRenameDto.Request(List.of(
                new PhysicalNameRenameDto.Item(TargetType.COLUMN, c1.getId(), "MULTI_SS", null), new PhysicalNameRenameDto.Item(TargetType.COLUMN, c2.getId(), "MULTI_SS", null)), null));
        assertThat(both.applicable()).isFalse();
        assertThat(both.items()).allSatisfy(i -> assertThat(i.applicability().blockers()).extracting(ApplyIssue::code).contains(ApplyCode.RECOMMENDATION_DUPLICATE));

        // 맞바꾸기 (A ↔ B, 대소문자만 다르게) · 순환 (X → Y → Z → X)
        TableInfo swap = table(projectId, schema, "TB_SWAP");
        Entity swapEntity = mapped(admin, model, "맞바꾸기", swap);
        StandardTerm ta = term(projectId, "가_코드", "A_CD");
        StandardTerm tb = term(projectId, "나_코드", "B_CD");
        ColumnInfo ca = column(projectId, swap, "b_cd", ta);
        ColumnInfo cb = column(projectId, swap, "a_cd", tb);
        attr(admin, swapEntity, "가", ta, ca);
        attr(admin, swapEntity, "나", tb, cb);
        PhysicalNameRenameDto.Plan swapPlan = rename.preview(admin, new PhysicalNameRenameDto.Request(List.of(
                new PhysicalNameRenameDto.Item(TargetType.COLUMN, ca.getId(), "A_CD", null), new PhysicalNameRenameDto.Item(TargetType.COLUMN, cb.getId(), "B_CD", null)), null));
        assertThat(swapPlan.items()).allSatisfy(i -> assertThat(i.applicability().blockers()).extracting(ApplyIssue::code).contains(ApplyCode.NAME_CONFLICT));
        TableInfo cycle = table(projectId, schema, "TB_CYCLE");
        Entity cycleEntity = mapped(admin, model, "순환", cycle);
        StandardTerm tx1 = term(projectId, "엑스", "X_CD");
        StandardTerm ty1 = term(projectId, "와이", "Y_CD");
        StandardTerm tz1 = term(projectId, "제트", "Z_CD");
        ColumnInfo cx = column(projectId, cycle, "Y_CD", tx1);
        ColumnInfo cy = column(projectId, cycle, "z_cd", ty1);
        ColumnInfo cz = column(projectId, cycle, "X_CD", tz1);
        attr(admin, cycleEntity, "엑스", tx1, cx);
        attr(admin, cycleEntity, "와이", ty1, cy);
        attr(admin, cycleEntity, "제트", tz1, cz);
        PhysicalNameRenameDto.Plan cyclePlan = rename.preview(admin, new PhysicalNameRenameDto.Request(List.of(
                new PhysicalNameRenameDto.Item(TargetType.COLUMN, cx.getId(), "X_CD", null), new PhysicalNameRenameDto.Item(TargetType.COLUMN, cy.getId(), "Y_CD", null),
                new PhysicalNameRenameDto.Item(TargetType.COLUMN, cz.getId(), "Z_CD", null)), null));
        assertThat(cyclePlan.applicable()).isFalse();
        assertThat(cyclePlan.items()).allSatisfy(i -> assertThat(i.applicability().blockers()).extracting(ApplyIssue::code).contains(ApplyCode.NAME_CONFLICT));
        assertThat(List.of(name(cx.getId()), name(cy.getId()), name(cz.getId()))).containsExactly("Y_CD", "z_cd", "X_CD");
    }

    // ------------------------------------------------------------------ 도구

    private static Set<Long> ids(List<ColumnRow> rows) {
        return rows.stream().map(ColumnRow::logicalAttributeId).collect(Collectors.toSet());
    }

    private static PhysicalNameRenameDto.Request request(Long columnId, String name, java.time.LocalDateTime base, String token) {
        return new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, columnId, name, base)), token);
    }

    private String name(Long columnId) {
        return jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, columnId);
    }

    private StandardTerm term(long projectId, String name, String abbreviation) {
        return termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation).isApproval(true).build());
    }

    private TableInfo table(long projectId, SchemaInfo schema, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false)
                .schema(entityManager.find(SchemaInfo.class, schema.getId())).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").standardTerms(term).isApproval(false).build()));
    }

    private Entity mapped(AccountDto account, Model model, String name, TableInfo table) {
        Entity entity = logicalService.saveEntity(account, new Entity(null, model.id(), name, null, null, null, null));
        mappingService.saveEntityMapping(account, new EntityMapping(null, entity.id(), table.getId()));
        return entity;
    }

    private Long attr(AccountDto account, Entity entity, String name, StandardTerm term, ColumnInfo column) {
        Long id = logicalService.saveAttribute(account, new Attribute(null, entity.id(), name, null, null, false, null, term.getId(), null, null, null)).id();
        Long mappingId = mappingService.selectEntityRows(account, new com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntitySearch(null, null, null, null, null, entity.id()), ALL)
                .getContent().get(0).mappingId();
        mappingService.saveAttributeMapping(account, new AttributeMapping(null, mappingId, id, column.getId()));
        return id;
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).tablePrefix("TB_").account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }
}
