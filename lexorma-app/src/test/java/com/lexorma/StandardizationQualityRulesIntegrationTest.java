package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.controller.StandardizationQualityRestController;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.Candidate;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Summary;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.dictionary.quality.StandardizationRule.TargetType;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.ColumnNameSuggestionService;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.dictionary.service.TermQualityService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.common.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.quality.TableNameRules;
import com.lexorma.data.dictionary.quality.TermDuplicateRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * M-5 잔여 : 테이블명 규칙 · 표준 용어 중복·유사어 · 컬럼명 개선 후보 (실제 JPA 쿼리).
 * 프로젝트 격리, 권한, 조회 전후 데이터 불변, 기존 컬럼 진단 요약이 그대로인지(D2) 확인한다. AI-6 3차에 실시간 요약·목록 API 를 지워 판정 결과는
 * 저장 검사가 쓰는 {@code inspect} 로 본다 (그 목록의 조건·페이징·정렬·사용 컬럼 수 검증은 화면과 함께 지웠다). AI Provider 를 등록하지 않은 상태로 실행한다.
 * 판정 규칙의 세부 사례는 lexorma-data 의 TableNameRulesTest·TermDuplicateRulesTest·ColumnNameSuggestionsTest 에 있다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StandardizationQualityRulesIntegrationTest {

    /** 완전성 규칙(AI-6 3차)을 뺀 모델 묶음 5규칙 */
    private static final java.util.Set<StandardizationRule> MODEL_RULES = java.util.EnumSet.of(StandardizationRule.TERM_NOT_LINKED, StandardizationRule.COLUMN_NAME_MISMATCH,
            StandardizationRule.DOMAIN_DEFINITION_MISMATCH, StandardizationRule.TABLE_PREFIX_MISSING, StandardizationRule.TABLE_WORD_NOT_STANDARD);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired SchemaService schemaService;
    @Autowired StandardizationQualityService qualityService;
    @Autowired TermQualityService termQualityService;
    @Autowired ColumnNameSuggestionService suggestionService;
    @Autowired StandardizationQualityRestController controller;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("모델 진단 : 테이블명(접두어·승인 단어 약어) + 기존 컬럼 진단의 판정 결과, 심각도·대상·근거·fingerprint, 기존 요약은 그대로")
    void model() {
        AccountDto admin = loginAsAdmin("m5-rules-model", "tb");
        long projectId = admin.getProjectId();
        word(projectId, "회원", "MBR", true);
        word(projectId, "이력", "HIST", false);
        word(projectId, "코드", "CD", false);
        word(projectId, "코드", "CD", true);   // 같은 약어에 승인된 단어가 있으면 승인

        TableInfo ok = table(projectId, "TB_MBR_CD");
        TableInfo lower = table(projectId, "tb_mbr");
        TableInfo noPrefix = table(projectId, "TBL_MBR");
        TableInfo words = table(projectId, "TB_MBR_HIST_XYZ");
        column(projectId, ok, "MBR_CD", null);

        // 판정 결과 (저장 검사의 모델 묶음 — AI-6 3차에 실시간 요약·목록 API 대신). 완전성 규칙은 따로 (CompletenessInspectionIntegrationTest)
        List<InspectionItem> issues = modelIssues(projectId);
        assertThat(issues).extracting(i -> i.finding().rule(), i -> i.finding().rule().targetType(), i -> i.finding().rule().severity(), i -> i.details().get("tableName"),
                        i -> i.finding().actualValue())
                .containsExactlyInAnyOrder(
                        tuple(StandardizationRule.TERM_NOT_LINKED, TargetType.COLUMN, Severity.WARNING, "TB_MBR_CD", null),
                        tuple(StandardizationRule.TABLE_WORD_NOT_STANDARD, TargetType.TABLE, Severity.ERROR, "TB_MBR_HIST_XYZ", "HIST, XYZ"),
                        tuple(StandardizationRule.TABLE_PREFIX_MISSING, TargetType.TABLE, Severity.ERROR, "TBL_MBR", "TBL_MBR"),
                        tuple(StandardizationRule.TABLE_WORD_NOT_STANDARD, TargetType.TABLE, Severity.ERROR, "TBL_MBR", "TBL"));
        assertThat(issues.stream().filter(i -> i.finding().rule().targetType() == TargetType.TABLE).map(InspectionItem::tableId).distinct()).hasSize(2);
        // 컬럼 진단 요약(대시보드 기준)은 그대로
        assertThat(qualityService.selectSummary(admin)).isEqualTo(new Summary(1, 1, 1, 0, 0));
        InspectionItem wordIssue = issues.stream().filter(i -> i.finding().rule() == StandardizationRule.TABLE_WORD_NOT_STANDARD && words.getId().equals(i.tableId()))
                .findFirst().orElseThrow();
        assertThat(wordIssue.objectType()).isEqualTo(InspectionItem.ObjectType.TABLE);
        assertThat(wordIssue.objectId()).isEqualTo(words.getId());
        assertThat(wordIssue.details().get("tokens").toString()).contains("XYZ").contains("HIST");
        assertThat(wordIssue.finding().fingerprint()).startsWith("v4:");
        assertThat(modelIssues(projectId)).extracting(i -> i.finding().fingerprint())
                .as("같은 데이터면 같은 fingerprint").containsExactlyElementsOf(issues.stream().map(i -> i.finding().fingerprint()).toList());
        assertThat(issues).extracting(InspectionItem::tableId).doesNotContain(lower.getId());
        assertThat(issues).filteredOn(i -> i.finding().rule() == StandardizationRule.TABLE_PREFIX_MISSING).extracting(InspectionItem::tableId).containsExactly(noPrefix.getId());

        // 접두어가 없는 프로젝트 : 접두어 규칙을 보지 않는다
        projectRepository.findById(projectId).ifPresent(project -> {
            project.setTablePrefix(null);
            projectRepository.save(project);
        });
        assertThat(modelIssues(projectId)).noneMatch(i -> i.finding().rule() == StandardizationRule.TABLE_PREFIX_MISSING);

        // 프로젝트 격리 : 빈 프로젝트에는 아무것도 없다
        AccountDto other = loginAsAdmin("m5-rules-model-other", "TB_");
        assertThat(qualityService.inspect(other.getProjectId())).isEmpty();
    }

    @Test
    @DisplayName("용어 진단 : 정확 중복(약어+도메인·용어명) · 같은 약어 다른 도메인 · 단어 구성 · 이음동의어 · 금칙어의 판정 결과, 격리, 조회 전용")
    void terms() {
        AccountDto admin = loginAsAdmin("m5-rules-terms", null);
        long projectId = admin.getProjectId();
        StandardDomain name10 = domain(projectId, "명V10");
        StandardDomain name20 = domain(projectId, "명V20");
        StandardWord member = word(projectId, "회원", "MBR", true);
        StandardWord nameWord = word(projectId, "이름", "NM", true);
        StandardWord fullName = word(projectId, "성명", "FLNM", true);
        StandardWord register = word(projectId, "등록", "REG", true);
        StandardWord date = word(projectId, "일자", "DT", true);
        tx.executeWithoutResult(s -> {
            StandardWord loaded = wordRepository.findById(nameWord.getId()).orElseThrow();
            loaded.setSynonymList(new ArrayList<>(List.of("성명")));
            StandardWord memberLoaded = wordRepository.findById(member.getId()).orElseThrow();
            memberLoaded.setRestrictedWords(new ArrayList<>(List.of("멤버")));
        });
        StandardWord memb = word(projectId, "멤버", "MEMB", true);

        StandardTerm a = term(projectId, "회원_이름", "MBR_NM", name10, List.of(), member, nameWord);
        StandardTerm b = term(projectId, "회원 이름", "mbr_nm", name10, List.of("회원명"));           // 약어+도메인 중복, 용어명 중복
        StandardTerm c = term(projectId, "회원_명칭", "MBR_NM", name20, List.of());                   // 같은 약어 다른 도메인
        StandardTerm d = term(projectId, "회원명", "MBRNM", name10, List.of());                       // b 의 이음동의어
        StandardTerm e = term(projectId, "회원_성명", "MBR_FLNM", name10, List.of(), member, fullName);  // 단어 이음동의어 → a 와 같은 구성
        StandardTerm f = term(projectId, "등록_일자", "REG_DT", name10, List.of(), register, date);
        StandardTerm g = term(projectId, "일자_등록", "DT_REG", name10, List.of(), date, register);     // f 와 같은 단어, 순서 다름
        StandardTerm h = term(projectId, "멤버_이름", "MEMB_NM", name10, List.of(), memb, nameWord);   // 금칙어
        TableInfo table = table(projectId, "TB_A");
        column(projectId, table, "MBR_NM", a);
        column(projectId, table, "MBR_NM2", a);

        // 판정 결과 (저장 검사의 용어 묶음 — AI-6 3차에 실시간 요약·목록 API 대신)
        List<InspectionItem> groups = termQualityService.inspect(projectId);
        assertThat(groups).extracting(x -> x.finding().rule(), x -> x.finding().targetIds())
                .containsExactlyInAnyOrder(
                        tuple(StandardizationRule.TERM_ABBR_DUPLICATE, List.of(a.getId(), b.getId())),
                        tuple(StandardizationRule.TERM_NAME_DUPLICATE, List.of(a.getId(), b.getId())),
                        tuple(StandardizationRule.TERM_ABBR_SHARED, List.of(a.getId(), b.getId(), c.getId())),
                        tuple(StandardizationRule.TERM_SAME_WORDS, List.of(f.getId(), g.getId())),
                        tuple(StandardizationRule.TERM_SYNONYM, List.of(a.getId(), e.getId())),
                        tuple(StandardizationRule.TERM_SYNONYM, List.of(b.getId(), d.getId())),
                        tuple(StandardizationRule.TERM_RESTRICTED_WORD, List.of(h.getId())));
        assertThat(groups.stream().filter(x -> x.finding().rule().severity() == Severity.ERROR).count()).isEqualTo(2);
        assertThat(groups.stream().filter(x -> x.finding().rule().severity() == Severity.WARNING).count()).isEqualTo(2);
        assertThat(groups.stream().filter(x -> x.finding().rule().severity() == Severity.INFO).count()).isEqualTo(3);
        InspectionItem restricted = groups.stream().filter(x -> x.finding().rule() == StandardizationRule.TERM_RESTRICTED_WORD).findFirst().orElseThrow();
        assertThat(restricted.details().get("members").toString()).contains("멤버").contains("회원");

        // 조회 전용 : 용어는 그대로 (병합·삭제·이름 변경 없음)
        assertThat(termRepository.findAllByProjectId(projectId)).hasSize(8);
        assertThat(termRepository.findById(b.getId()).orElseThrow().getCommonStandardTermAbbreviation()).isEqualTo("mbr_nm");

        // 격리
        AccountDto other = loginAsAdmin("m5-rules-terms-other", null);
        term(other.getProjectId(), "회원_이름", "MBR_NM", null, List.of());
        assertThat(termQualityService.inspect(other.getProjectId())).isEmpty();
    }

    @Test
    @DisplayName("컬럼명 개선 후보 : 미연결·불일치 컬럼만, 고정 순서, 충돌·도메인, 후보 없음, 다른 프로젝트 컬럼 거부, 조회 전용")
    void suggestions() {
        AccountDto admin = loginAsAdmin("m5-rules-suggest", null);
        long projectId = admin.getProjectId();
        StandardDomain code = domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName("코드V10")
                .dataType("VARCHAR").dataLength(10).isApproval(true).build());
        StandardWord member = word(projectId, "회원", "MBR", true);
        StandardWord grade = word(projectId, "등급", "GRD", true);
        word(projectId, "코드", "CD", true);
        StandardTerm memberGrade = term(projectId, "회원_등급", "MBR_GRD", code, List.of(), member, grade);
        StandardTerm gradeCode = term(projectId, "등급_코드", "GRD_CD", code, List.of());

        TableInfo table = table(projectId, "TB_MBR");
        ColumnInfo unlinked = column(projectId, table, "MBR_GRD", null, "VARCHAR", 20, "회원 등급");
        ColumnInfo mismatch = column(projectId, table, "GRADE_CD", gradeCode, "VARCHAR", 10, "등급 코드");
        ColumnInfo standard = column(projectId, table, "GRD_CD", gradeCode, "VARCHAR", 10, null);
        ColumnInfo nothing = column(projectId, table, "ZZZ", null, "VARCHAR", 10, "무엇");
        ColumnInfo unregistered = column(projectId, table, "GRD_MBR", null, "VARCHAR", 10, null);
        // 같은 논리명의 다른 테이블 컬럼이 회원_등급 을 쓴다
        column(projectId, table(projectId, "TB_ORDR"), "MBR_GRD", memberGrade, "VARCHAR", 10, "회원등급");

        ColumnSuggestionDto.Suggestions first = suggestionService.suggest(admin, unlinked.getId());
        assertThat(first.issueTypes()).contains(com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType.TERM_NOT_LINKED);
        assertThat(first.candidates()).extracting(Candidate::termId, Candidate::source, Candidate::action, Candidate::domainFit)
                .containsExactly(tuple(memberGrade.getId(), ColumnSuggestionDto.Source.ABBR_EXACT, ColumnSuggestionDto.Action.LINK_TERM, ColumnSuggestionDto.DomainFit.MISMATCH));
        assertThat(first.candidates().get(0).sources()).contains(ColumnSuggestionDto.Source.NAME_EXACT, ColumnSuggestionDto.Source.WORD_COMPOSE, ColumnSuggestionDto.Source.SAME_DESC_COLUMN);
        assertThat(first.candidates().get(0).reason()).isNotBlank();
        assertThat(suggestionService.suggest(admin, unlinked.getId())).as("같은 데이터면 같은 결과").isEqualTo(first);

        // 불일치 : 연결된 용어의 약어로 바꾸면 같은 테이블의 GRD_CD 와 충돌한다
        Candidate rename = suggestionService.suggest(admin, mismatch.getId()).candidates().get(0);
        assertThat(rename.source()).isEqualTo(ColumnSuggestionDto.Source.LINKED_TERM);
        assertThat(rename.recommendedColumnName()).isEqualTo("GRD_CD");
        assertThat(rename.nameConflict()).isTrue();
        assertThat(rename.conflictColumns()).containsExactly("GRD_CD");

        // 정상 컬럼 / 후보 없음 / 미등록 조합 : 빈 목록 + 사유 또는 새 용어 후보
        ColumnSuggestionDto.Suggestions normal = suggestionService.suggest(admin, standard.getId());
        assertThat(normal.candidates()).isEmpty();
        assertThat(normal.reason()).isNotBlank();
        ColumnSuggestionDto.Suggestions none = suggestionService.suggest(admin, nothing.getId());
        assertThat(none.candidates()).isEmpty();
        assertThat(none.reason()).isNotBlank();
        assertThat(suggestionService.suggest(admin, unregistered.getId()).candidates()).extracting(Candidate::source, Candidate::proposedTermName)
                .containsExactly(tuple(ColumnSuggestionDto.Source.UNREGISTERED, "등급_회원"));

        // 조회 전용
        tx.executeWithoutResult(s -> {
            assertThat(columnInfoRepository.findById(unlinked.getId()).orElseThrow().getStandardTerms()).isNull();
            assertThat(columnInfoRepository.findById(mismatch.getId()).orElseThrow().getColumnName()).isEqualTo("GRADE_CD");
        });
        assertThat(termRepository.findAllByProjectId(projectId)).hasSize(2);

        // 다른 프로젝트의 컬럼, 없는 컬럼은 오류
        AccountDto other = loginAsAdmin("m5-rules-suggest-other", null);
        assertThatThrownBy(() -> suggestionService.suggest(other, unlinked.getId())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> suggestionService.suggest(admin, -1L)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("권한 : 보조 조회(도메인 이름 중복·컬럼명 개선 후보)는 VIEWER 부터, 승인 전 사용자는 거절된다")
    void authorization() {
        Project project = projectRepository.save(Project.builder().projectName("m5-rules-role").isActive(true).account(admin()).build());

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, project.getId(), "900", "ROLE_VIEWER");
        assertThat(controller.selectDomainDuplicates(viewer).getStatusCode().value()).isEqualTo(200);
        assertThatThrownBy(() -> controller.selectColumnSuggestions(viewer, -1L)).isInstanceOf(CustomException.class);

        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, project.getId(), "901", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectDomainDuplicates(notApproved)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectColumnSuggestions(notApproved, -1L)).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * 예제는 이 클래스와 같은 DB 를 쓰는 다른 테스트가 프로젝트 0 에 테이블·용어를 더하므로, 새로 마이그레이션한 빈 DB 에서 판정 규칙을 직접 돌린다
     * (V0SeedMigrationTest 와 같은 방식, PostgreSQL 은 LEXORMA_TEST_POSTGRES_URL 이 있을 때). 서비스·쿼리 경로는 위 테스트들이 확인한다.
     */
    @Test
    @DisplayName("LEXORMA 예제 PostgreSQL : 테이블명 규칙 0건, 용어 진단은 오류·주의 0건과 참고 1건 (예제를 고치지 않는다)")
    void example() {
        String schema = "m5_example_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        try {
            flyway.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            String q = "\"" + schema + "\".";
            String prefix = jdbc.queryForObject("select TBL_PFX_NM from " + q + "TB_PRJ where PRJ_SN = 0", String.class);
            List<StandardizationQualityDto.TableRow> tables = jdbc.query("select TBL_SN, SCHM_SN, TBL_NM from " + q + "TB_TBL where PRJ_SN = 0",
                    (rs, i) -> new StandardizationQualityDto.TableRow(rs.getLong(1), rs.getLong(2), null, rs.getString(3), null, true));
            List<StandardizationQualityDto.WordAbbreviation> abbreviations = jdbc.query("select WORD_ENG_ABBR_NM, APRV_YN from " + q + "TB_STD_WORD where PRJ_SN = 0",
                    (rs, i) -> new StandardizationQualityDto.WordAbbreviation(rs.getString(1), rs.getBoolean(2)));
            assertThat(prefix).isEqualTo("TB_");
            assertThat(tables).hasSizeGreaterThan(50);
            assertThat(TableNameRules.diagnose(0L, prefix, tables, TableNameRules.wordStatuses(abbreviations))).isEmpty();

            List<TermQualityDto.TermRow> terms = jdbc.query("select STD_TRM_SN, TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN from " + q + "TB_STD_TRM where PRJ_SN = 0",
                    (rs, i) -> new TermQualityDto.TermRow(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4, Long.class), null, rs.getBoolean(5)));
            List<TermQualityDto.TermWordRow> termWords = jdbc.query("select m.STD_TRM_SN, m.STD_WORD_SN, m.SORT_ORD from " + q + "TB_STD_TRM_WORD m join " + q
                    + "TB_STD_TRM t on t.STD_TRM_SN = m.STD_TRM_SN where t.PRJ_SN = 0", (rs, i) -> new TermQualityDto.TermWordRow(rs.getLong(1), rs.getLong(2), rs.getInt(3)));
            List<TermQualityDto.WordRow> words = jdbc.query("select STD_WORD_SN, WORD_NM, WORD_ENG_ABBR_NM, APRV_YN from " + q + "TB_STD_WORD where PRJ_SN = 0",
                    (rs, i) -> new TermQualityDto.WordRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
            List<TermQualityDto.NameRow> termSynonyms = jdbc.query("select s.STD_TRM_SN, s.SYNM_NM from " + q + "TB_STD_TRM_SYNM s join " + q
                    + "TB_STD_TRM t on t.STD_TRM_SN = s.STD_TRM_SN where t.PRJ_SN = 0", (rs, i) -> new TermQualityDto.NameRow(rs.getLong(1), rs.getString(2)));
            List<TermQualityDto.NameRow> wordSynonyms = jdbc.query("select s.STD_WORD_SN, s.SYNM_NM from " + q + "TB_STD_WORD_SYNM s join " + q
                    + "TB_STD_WORD w on w.STD_WORD_SN = s.STD_WORD_SN where w.PRJ_SN = 0", (rs, i) -> new TermQualityDto.NameRow(rs.getLong(1), rs.getString(2)));
            List<TermQualityDto.NameRow> restricted = jdbc.query("select s.STD_WORD_SN, s.PRHBT_WORD_NM from " + q + "TB_STD_WORD_PRHBT s join " + q
                    + "TB_STD_WORD w on w.STD_WORD_SN = s.STD_WORD_SN where w.PRJ_SN = 0", (rs, i) -> new TermQualityDto.NameRow(rs.getLong(1), rs.getString(2)));
            assertThat(terms).hasSizeGreaterThan(300);

            // VIEW_RFRNC_SN(TB_VIEW_RFRNC 의 키, 뷰 참조 일련번호) ↔ RFRNC_VIEW_SN(참조되는 VIEW, 참조 뷰 일련번호) : 뜻이 다른 두 용어라 참고로만 나온다
            List<TermDuplicateRules.TermGroup> groups = TermDuplicateRules.diagnose(0L, terms, termWords, termSynonyms, words, wordSynonyms, restricted);
            assertThat(groups).extracting(group -> group.finding().rule(),
                            group -> group.termIds().stream().map(id -> terms.stream().filter(t -> t.termId().equals(id)).findFirst().orElseThrow().abbreviation()).sorted().toList())
                    .containsExactly(tuple(StandardizationRule.TERM_SAME_WORDS, List.of("RFRNC_VIEW_SN", "VIEW_RFRNC_SN")));
        } finally {
            flyway.clean();
        }
    }

    // ------------------------------------------------------------------ 보조

    private List<InspectionItem> modelIssues(long projectId) {
        return qualityService.inspect(projectId).stream().filter(i -> MODEL_RULES.contains(i.finding().rule())).toList();
    }

    private Account admin() {
        return accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
    }

    private AccountDto loginAsAdmin(String projectName, String tablePrefix) {
        Account admin = admin();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin)
                .tablePrefix(Project.normalizeTablePrefix(tablePrefix)).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private StandardDomain domain(long projectId, String name) {
        return domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName(name).dataType("VARCHAR").isApproval(true).build());
    }

    private StandardWord word(long projectId, String name, String abbreviation, boolean approved) {
        return wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName(name).commonStandardWordAbbreviation(abbreviation)
                .isApproval(approved).synonymList(new ArrayList<>()).restrictedWords(new ArrayList<>()).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain, List<String> synonyms, StandardWord... words) {
        return tx.execute(s -> {
            StandardTerm term = StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                    .standardDomain(domain).isApproval(true).synonymList(new ArrayList<>(synonyms)).termWordMappings(new ArrayList<>()).build();
            for (int i = 0; i < words.length; i++) {
                StandardTermWordMapping mapping = new StandardTermWordMapping();
                mapping.setStandardTerm(term);
                mapping.setStandardWord(wordRepository.getReferenceById(words[i].getId()));
                mapping.setOrderIndex(i + 1);
                term.getTermWordMappings().add(mapping);
            }
            return termRepository.save(term);
        });
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term) {
        return column(projectId, table, name, term, "VARCHAR", 10, null);
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term, String dataType, Integer maxLength, String desc) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).columnDesc(desc).dataType(dataType).maxLength(maxLength).standardTerms(term).isApproval(true).build()));
    }
}
