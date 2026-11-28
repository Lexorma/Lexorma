package com.lexorma.data.dictionary.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.TableColumnRow;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto.TermCandidateRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ColumnRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.IssueType;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.TableRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.WordAbbreviation;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.NameRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.TermWordRow;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto.WordRow;
import com.lexorma.data.dictionary.quality.ColumnNameSuggestions;
import com.lexorma.data.dictionary.quality.Finding;
import com.lexorma.data.dictionary.quality.TableNameRules;
import com.lexorma.data.dictionary.quality.TermDuplicateRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-5 4차 : 공통 판정 함수로 바꾼 뒤에도 같은 입력의 진단 결과(규칙·대상·현재/권장 값·근거·문구·fingerprint)가 그대로인지 확인한다.
 * 기대값 {@code quality-golden/findings-v1.txt} 는 공통화 전 코드로 만든 결과다 (AI-6 2차에서 용어 그룹 규칙 7건의 fingerprint 만 v2 로 바뀌었다 — AI6-D11) — 판정을 일부러 바꾸는 작업이 아니면 이 파일을 고치지 않는다.
 * 실제 결과는 {@code build/quality-golden/findings-v1.txt} 에 남겨 차이를 비교할 수 있다.
 */
class StandardizationFindingGoldenTest {

    private static final String GOLDEN = "quality-golden/findings-v1.txt";

    @Test
    @DisplayName("공통화 전후 진단 결과 동일 : 컬럼 3종·테이블명 2종·용어 6종·컬럼명 개선 후보")
    void sameFindings() throws IOException {
        List<String> actual = new ArrayList<>();
        columns(actual);
        tables(actual);
        terms(actual);
        suggestions(actual);

        Path out = Path.of("build", GOLDEN);
        Files.createDirectories(out.getParent());
        Files.write(out, actual, StandardCharsets.UTF_8);

        try (InputStream golden = getClass().getClassLoader().getResourceAsStream(GOLDEN)) {
            assertThat(golden).as("기대값 파일 " + GOLDEN + " (실제 결과 : " + out.toAbsolutePath() + ")").isNotNull();
            List<String> expected = new String(golden.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertThat(actual).containsExactlyElementsOf(expected);
        }
    }

    // ------------------------------------------------------------------ 컬럼 (StandardizationQualityService.diagnose)

    private static void columns(List<String> out) {
        List<ColumnRow> rows = List.of(
                column(1, "USER_NM", null, null, "VARCHAR", 20, null, null, null),               // 미연결
                column(2, "USER_NM", 10L, "USER_NM", "VARCHAR", 20, 100L, "VARCHAR", 20),        // 정상
                column(3, " user_nm ", 10L, "USER_NM", "VARCHAR", 20, 100L, "VARCHAR", 20),      // 대소문자·공백만 다름
                column(4, "USR_NM", 10L, " USER_NM ", "VARCHAR", 20, 100L, "VARCHAR", 20),       // 불일치
                column(5, "USERNM", 10L, "user_nm", "VARCHAR", 30, 100L, "VARCHAR", 20),         // 불일치 + 길이
                column(6, "ANY", 11L, " ", "VARCHAR", 20, null, null, null),                     // 약어 공백 — 기대 이름 없음
                column(7, "ANY", 12L, null, "INT", null, 101L, "NUMBER", 10),                    // 약어 없음 + 타입
                column(8, null, 13L, "REG_DT", "DATE", null, 102L, "DATE", null),                // 컬럼명 null
                column(9, "", 13L, "REG_DT", "VARCHAR", 8, 102L, "DATE", 10));                   // 빈 컬럼명 + 타입
        for (DbType db : List.of(DbType.POSTGRESQL, DbType.ORACLE)) {
            for (Issue issue : StandardizationQualityService.diagnose(db, rows)) {
                out.add("COLUMN " + db + " " + issue.id() + " | " + issue.message() + " | " + issue.actualValue() + " | " + issue.expectedValue()
                        + " | " + issue.attributes() + " | " + finding(StandardizationQualityService.finding(1L, issue)));
            }
        }
    }

    private static ColumnRow column(long id, String name, Long termId, String abbreviation, String type, Integer length,
                                    Long domainId, String domainType, Integer domainLength) {
        return new ColumnRow(id, 7L, 1L, "DEFAULT", "TB_MBR", "회원", name, "회원 이름", type, length, (BigDecimal) null, null, (int) id, true,
                termId, termId == null ? null : "용어" + termId, abbreviation, domainId, domainId == null ? null : "도메인" + domainId,
                domainType, domainLength, null);
    }

    // ------------------------------------------------------------------ 테이블 (TableNameRules)

    private static void tables(List<String> out) {
        var words = TableNameRules.wordStatuses(List.of(new WordAbbreviation("MBR", true), new WordAbbreviation(" info ", true),
                new WordAbbreviation("HIST", false), new WordAbbreviation("CD", false), new WordAbbreviation("cd", true),
                new WordAbbreviation(null, true), new WordAbbreviation(" ", false)));
        List<TableRow> rows = List.of(table(1, "TB_MBR"), table(2, "tb_mbr_info"), table(3, "TBL_MBR"), table(4, "TBMBR"),
                table(5, "TB_MBR_HIST_XYZ"), table(6, "TB_MBR__INFO"), table(7, "TB_"), table(8, " TB_CD "), table(9, null), table(10, "MBR_CD"));
        for (String prefix : new String[]{"TB_", "tb", null, " "}) {
            for (TableNameRules.TableIssue issue : TableNameRules.diagnose(1L, prefix, rows, words)) {
                out.add("TABLE " + prefix + " " + issue.table().tableId() + " | " + issue.tokens() + " | " + finding(issue.finding()));
            }
        }
    }

    private static TableRow table(long id, String name) {
        return new TableRow(id, 1L, "DEFAULT", name, "테이블" + id, false);
    }

    // ------------------------------------------------------------------ 용어 (TermDuplicateRules)

    private static void terms(List<String> out) {
        List<WordRow> words = new ArrayList<>(List.of(new WordRow(1L, "회원", "MBR", true), new WordRow(2L, "이름", "NM", true),
                new WordRow(3L, "등록", "REG", true), new WordRow(4L, "일자", "DT", false), new WordRow(5L, "고객", "CUST", true),
                new WordRow(6L, "성명", "FLNM", true), new WordRow(7L, "멤버", "MEMB", true), new WordRow(8L, "Member", "MEMBER", true)));
        List<TermRow> terms = new ArrayList<>();
        List<TermWordRow> termWords = new ArrayList<>();
        term(terms, termWords, 1, "회원_이름", "MBR_NM", 10L, 1, 2);
        term(terms, termWords, 2, "회원명", " mbr_nm ", 10L);
        term(terms, termWords, 3, "회원_성명", "MBR_NM", 20L, 1, 6);
        term(terms, termWords, 4, "비고", "", 10L);
        term(terms, termWords, 5, "비고2", null, null);
        term(terms, termWords, 6, "회원 이름", "MBR_NAME", 20L);
        term(terms, termWords, 7, "Member_ID", "MBR_ID", 10L);
        term(terms, termWords, 8, " member\tid ", "MEMBER_ID", 10L);
        term(terms, termWords, 9, "회원_등록_일자", "MBR_REG_DT", 10L, 1, 3, 4);
        term(terms, termWords, 10, "등록_회원_일자", "REG_MBR_DT", 10L, 3, 1, 4);
        term(terms, termWords, 11, "성명_회원", "FLNM_MBR", 10L, 6, 1);
        term(terms, termWords, 12, "멤버_이름", "MEMB_NM", 10L, 7, 2);
        term(terms, termWords, 13, "멤버십등급", "MSHP_GRD", 10L);
        term(terms, termWords, 14, "멤버 등급", "MEMB_GRD", 10L);
        term(terms, termWords, 15, "고객_이름", "CUST_NM", 10L, 5, 2);
        term(terms, termWords, 16, null, "NULL_NM", 10L);
        List<NameRow> termSynonyms = List.of(new NameRow(1L, "회원명"), new NameRow(1L, " "), new NameRow(1L, ""), new NameRow(15L, "회원 이름"),
                new NameRow(99L, "고객명"));
        List<NameRow> wordSynonyms = List.of(new NameRow(2L, "성명"), new NameRow(1L, "고객"), new NameRow(1L, " "));
        List<NameRow> restricted = List.of(new NameRow(1L, "멤버"), new NameRow(2L, "이름"), new NameRow(5L, "회원"), new NameRow(8L, "member"));
        for (TermDuplicateRules.TermGroup group : TermDuplicateRules.diagnose(1L, terms, termWords, termSynonyms, words, wordSynonyms, restricted)) {
            out.add("TERM " + group.groupKey() + " | " + group.termIds() + " | " + group.notes() + " | " + finding(group.finding()));
        }
    }

    private static void term(List<TermRow> terms, List<TermWordRow> termWords, long id, String name, String abbreviation, Long domainId, long... wordIds) {
        terms.add(new TermRow(id, name, abbreviation, domainId, domainId == null ? null : "D" + domainId, id % 2 == 0));
        for (int i = 0; i < wordIds.length; i++) {
            termWords.add(new TermWordRow(id, wordIds[i], i + 1));
        }
    }

    // ------------------------------------------------------------------ 컬럼명 개선 후보 (ColumnNameSuggestions)

    private static void suggestions(List<String> out) {
        List<WordRow> words = List.of(new WordRow(1L, "회원", "MBR", true), new WordRow(2L, "이름", "NM", true),
                new WordRow(3L, "등급", "GRD", false), new WordRow(4L, "코드", "cd", true), new WordRow(5L, "코드", "CD", false));
        List<TermCandidateRow> terms = List.of(
                new TermCandidateRow(10L, "회원_이름", "MBR_NM", true, 100L, "이름", "VARCHAR", 20, null),
                new TermCandidateRow(11L, "회원_성명", " mbr_flnm ", true, null, null, null, null, null),
                new TermCandidateRow(12L, "회원_실명", "MBR_RNM", false, 101L, "숫자", "NUMBER", 10, null),
                new TermCandidateRow(13L, "고객_이름", "CUST_NM", true, null, null, null, null, null),
                new TermCandidateRow(14L, "회원_코드", "MBR_CD", true, null, null, null, null, null),
                new TermCandidateRow(15L, "빈약어", " ", true, null, null, null, null, null));
        List<NameRow> synonyms = List.of(new NameRow(12L, "회원성명"), new NameRow(12L, " "));
        List<TermWordRow> termWords = List.of(new TermWordRow(10L, 1L, 1), new TermWordRow(10L, 2L, 2), new TermWordRow(14L, 1L, 1),
                new TermWordRow(14L, 4L, 2));
        List<TableColumnRow> tableColumns = List.of(new TableColumnRow(1L, "MBR_NM", null, null), new TableColumnRow(2L, "mbr_flnm", 11L, "MBR_FLNM"),
                new TableColumnRow(3L, "X", 14L, "mbr_cd"), new TableColumnRow(4L, null, null, null));
        Map<Long, Long> sameDesc = Map.of(13L, 3L, 10L, 1L);

        record Case(String name, String desc, Long termId, String abbreviation, Set<IssueType> issues) {
        }
        List<Case> cases = List.of(
                new Case("MBR_NM", "회원 성명", null, null, EnumSet.of(IssueType.TERM_NOT_LINKED)),
                new Case(" mbr_nm ", "회원_이름", null, null, EnumSet.of(IssueType.TERM_NOT_LINKED)),
                new Case("MBR_GRD", "회원 등급", null, null, EnumSet.of(IssueType.TERM_NOT_LINKED)),
                new Case("MBR_CD", "회원 코드", null, null, EnumSet.of(IssueType.TERM_NOT_LINKED)),
                new Case("USR_NM", "회원 이름", 10L, "MBR_NM", EnumSet.of(IssueType.COLUMN_NAME_MISMATCH)),
                new Case("X", "회원 성명", 11L, " mbr_flnm ", EnumSet.of(IssueType.COLUMN_NAME_MISMATCH, IssueType.DOMAIN_DEFINITION_MISMATCH)),
                new Case(null, null, null, null, EnumSet.of(IssueType.TERM_NOT_LINKED)),
                new Case("MBR_NM", "회원 이름", 10L, "MBR_NM", EnumSet.of(IssueType.DOMAIN_DEFINITION_MISMATCH)));
        int index = 0;
        for (Case c : cases) {
            index++;
            ColumnRow column = new ColumnRow(100L + index, 7L, 1L, "DEFAULT", "TB_MBR", "회원", c.name(), c.desc(), "VARCHAR", 20, null, null, 1, true,
                    c.termId(), c.termId() == null ? null : "용어", c.abbreviation(), null, null, null, null, null);
            ColumnNameSuggestions.Result result = ColumnNameSuggestions.suggest(new ColumnNameSuggestions.Input(DbType.POSTGRESQL, column, c.issues(),
                    terms, synonyms, words, termWords, sameDesc, tableColumns));
            out.add("SUGGEST " + index + " truncated=" + result.truncated());
            for (var candidate : result.candidates()) {
                out.add("SUGGEST " + index + " " + candidate);
            }
        }
    }

    private static String finding(Finding finding) {
        return finding.rule() + " " + finding.targetIds() + " actual=" + finding.actualValue() + " expected=" + finding.expectedValue()
                + " evidence=" + finding.evidence() + " " + finding.fingerprint();
    }
}
