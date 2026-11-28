package com.lexorma.data.dictionary.quality;

import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.NameToken;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.TableRow;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.TokenStatus;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.WordAbbreviation;
import com.lexorma.data.dictionary.quality.TableNameRules.TableIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** M-5 잔여 : 테이블명 표준 규칙 (D1 — 프로젝트 접두어 + 승인된 표준 단어 약어) */
class TableNameRulesTest {

    /** MBR·INFO 는 승인, HIST 는 미승인 단어만, CD 는 승인·미승인이 함께 있다 */
    private static final Map<String, TokenStatus> WORDS = TableNameRules.wordStatuses(List.of(
            new WordAbbreviation("MBR", true), new WordAbbreviation("info", true), new WordAbbreviation("HIST", false),
            new WordAbbreviation("CD", false), new WordAbbreviation("CD", true), new WordAbbreviation(" ", true)));

    @Test
    @DisplayName("같은 약어의 단어가 여럿이면 하나라도 승인되어 있으면 승인, 약어는 대소문자를 무시한다")
    void wordStatuses() {
        assertThat(WORDS).containsExactlyInAnyOrderEntriesOf(Map.of(
                "MBR", TokenStatus.APPROVED, "INFO", TokenStatus.APPROVED, "HIST", TokenStatus.NOT_APPROVED, "CD", TokenStatus.APPROVED));
    }

    @Test
    @DisplayName("접두어 : 대소문자를 무시하고, 경계는 접두어 끝의 '_' 다 (TB_ 에 TBL_·TBX 는 위반). 접두어가 없는 프로젝트는 보지 않는다")
    void prefix() {
        assertThat(rules("TB_", "TB_MBR", "tb_mbr_info", "Tb_Mbr")).isEmpty();
        assertThat(rules("tb", "TB_MBR")).as("예전 저장 값처럼 '_' 가 없어도 같은 경계").isEmpty();

        List<TableIssue> issues = diagnose("TB_", "TBL_MBR", "TBMBR", "MBR");
        assertThat(issues).filteredOn(issue -> issue.finding().rule() == StandardizationRule.TABLE_PREFIX_MISSING)
                .extracting(issue -> issue.table().tableName(), issue -> issue.finding().actualValue(), issue -> issue.finding().expectedValue())
                .containsExactly(tuple("TBL_MBR", "TBL_MBR", "TB_"), tuple("TBMBR", "TBMBR", "TB_"), tuple("MBR", "MBR", "TB_"));
        assertThat(rules(null, "MBR", "ANY_NAME")).doesNotContain(StandardizationRule.TABLE_PREFIX_MISSING);
        assertThat(rules(" ", "MBR")).isEmpty();
    }

    @Test
    @DisplayName("약어 : 접두어를 뗀 나머지를 '_' 로 나눠 미등록·미승인·빈 약어를 구분한다. 접두어가 없는 테이블은 이름 전체를 본다")
    void words() {
        TableIssue issue = only(diagnose("TB_", "TB_MBR_HIST_XYZ"));
        assertThat(issue.finding().rule()).isEqualTo(StandardizationRule.TABLE_WORD_NOT_STANDARD);
        assertThat(issue.tokens()).containsExactly(new NameToken("MBR", TokenStatus.APPROVED), new NameToken("HIST", TokenStatus.NOT_APPROVED),
                new NameToken("XYZ", TokenStatus.NOT_FOUND));
        assertThat(issue.finding().actualValue()).isEqualTo("HIST, XYZ");
        assertThat(issue.finding().evidence()).containsEntry("tokens", "HIST:NOT_APPROVED,XYZ:NOT_FOUND");

        assertThat(only(diagnose("TB_", "TB_MBR__INFO")).tokens()).extracting(NameToken::status)
                .containsExactly(TokenStatus.APPROVED, TokenStatus.EMPTY, TokenStatus.APPROVED);
        assertThat(only(diagnose("TB_", "TB_")).tokens()).extracting(NameToken::status).containsExactly(TokenStatus.EMPTY);

        // 접두어 없음 + 이름 전체의 약어 판정 (TBL 은 단어가 아니다)
        assertThat(diagnose("TB_", "TBL_MBR")).extracting(i -> i.finding().rule())
                .containsExactly(StandardizationRule.TABLE_PREFIX_MISSING, StandardizationRule.TABLE_WORD_NOT_STANDARD);
        assertThat(rules(null, "MBR_INFO", "mbr_cd")).as("접두어 미설정 + 승인된 약어").isEmpty();
    }

    @Test
    @DisplayName("fingerprint : 같은 문제는 같은 값, 프로젝트·대상·규칙·핵심 값이 다르면 다른 값")
    void fingerprint() {
        String first = only(diagnose("TB_", "TB_MBR_XYZ")).finding().fingerprint();
        assertThat(first).startsWith("v4:").hasSize(3 + 64);
        assertThat(only(diagnose("TB_", "TB_MBR_XYZ")).finding().fingerprint()).isEqualTo(first);
        // 승인된 다른 약어를 바꿔도 위반 약어가 같으면 같은 문제다
        assertThat(only(diagnose("TB_", "TB_INFO_XYZ")).finding().fingerprint()).isEqualTo(first);
        assertThat(only(diagnose("TB_", "TB_MBR_ABC")).finding().fingerprint()).isNotEqualTo(first);
        assertThat(TableNameRules.diagnose(2L, "TB_", List.of(table(1L, "TB_MBR_XYZ")), WORDS).get(0).finding().fingerprint()).isNotEqualTo(first);
        assertThat(TableNameRules.diagnose(1L, "TB_", List.of(table(2L, "TB_MBR_XYZ")), WORDS).get(0).finding().fingerprint()).isNotEqualTo(first);

        assertThat(FindingFingerprint.of(1, StandardizationRule.TERM_NAME_DUPLICATE, List.of(3L, 1L, 2L), List.of("a")))
                .as("구성원 ID 순서와 무관").isEqualTo(FindingFingerprint.of(1, StandardizationRule.TERM_NAME_DUPLICATE, List.of(1L, 2L, 3L), List.of("a")));
        assertThat(FindingFingerprint.of(1, StandardizationRule.TERM_NAME_DUPLICATE, List.of(1L), List.of("a", "b")))
                .as("핵심 값의 경계").isNotEqualTo(FindingFingerprint.of(1, StandardizationRule.TERM_NAME_DUPLICATE, List.of(1L), List.of("ab")));
    }

    // ------------------------------------------------------------------ 보조

    private static List<TableIssue> diagnose(String prefix, String... names) {
        List<TableRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            rows.add(table(i + 1L, names[i]));
        }
        return TableNameRules.diagnose(1L, prefix, rows, WORDS);
    }

    private static List<StandardizationRule> rules(String prefix, String... names) {
        return diagnose(prefix, names).stream().map(issue -> issue.finding().rule()).toList();
    }

    private static TableIssue only(List<TableIssue> issues) {
        assertThat(issues).hasSize(1);
        return issues.get(0);
    }

    private static TableRow table(long id, String name) {
        return new TableRow(id, 1L, "DEFAULT", name, null, true);
    }
}
