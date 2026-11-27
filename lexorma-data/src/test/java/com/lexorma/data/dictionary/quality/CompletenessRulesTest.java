package com.lexorma.data.dictionary.quality;

import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.CompletenessColumn;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.CompletenessTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** 완전성 4규칙의 판정 (AI-6 3차, 순수 함수 — CP01~CP14·CP24). 실행 경로·생명주기는 lexorma-app 의 CompletenessInspectionIntegrationTest */
class CompletenessRulesTest {

    private static List<InspectionItem> judge(List<CompletenessTable> tables, List<CompletenessColumn> columns) {
        return CompletenessRules.diagnose(7L, tables, columns);
    }

    private static CompletenessTable table(long id, String comment, String biz) {
        return new CompletenessTable(id, "S", "T" + id, comment, biz);
    }

    private static CompletenessColumn column(long id, String comment, String biz) {
        return new CompletenessColumn(id, 1L, "S", "T1", "C" + id, comment, biz);
    }

    @ParameterizedTest(name = "누락 : [{0}]")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", "\r\n", " \t\u000B\f\r\n "})
    @DisplayName("CP02·CP03·CP06·CP07·CP24 : 빈 문자열과 ASCII 공백류(스페이스·탭·LF·VT·FF·CR)만 있는 값은 누락 — 테이블·컬럼 코멘트 모두")
    void blankIsMissing(String value) {
        assertThat(CompletenessRules.missing(value)).isTrue();
        assertThat(judge(List.of(table(1, value, "업무")), List.of(column(2, value, "업무"))))
                .extracting(i -> i.finding().rule()).containsExactly(StandardizationRule.TABLE_COMMENT_MISSING, StandardizationRule.COLUMN_COMMENT_MISSING);
    }

    @Test
    @DisplayName("CP01·CP05 : null 은 누락. CP04·CP08·CP11 : 값이 있으면 충족 (앞뒤 공백·한 글자·NBSP·전각 공백도 내용으로 본다 — 기존 빈 이름 규칙 그대로)")
    void nullMissingAndPresentValues() {
        assertThat(CompletenessRules.missing(null)).isTrue();
        assertThat(judge(List.of(table(1, null, null)), List.of(column(2, null, null)))).hasSize(4);
        for (String present : List.of("고객", " 고객 ", "a", " ", "　", "-")) {
            assertThat(CompletenessRules.missing(present)).as("[" + present + "]").isFalse();
            assertThat(judge(List.of(table(1, present, present)), List.of(column(2, present, present)))).as("[" + present + "]").isEmpty();
        }
    }

    @Test
    @DisplayName("CP09·CP10·CP12~CP14 : 코멘트와 업무 설명은 따로 판정 — 하나만 누락이면 그 규칙만, 둘 다면 두 규칙")
    void commentAndBizAreIndependent() {
        List<InspectionItem> items = judge(
                List.of(table(1, "코멘트", null), table(2, null, "업무"), table(3, null, null)),
                List.of(column(11, "코멘트", " "), column(12, "", "업무"), column(13, null, "\t")));
        assertThat(items).extracting(i -> i.finding().rule(), InspectionItem::objectId).containsExactly(
                tuple(StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING, 1L),
                tuple(StandardizationRule.TABLE_COMMENT_MISSING, 2L),
                tuple(StandardizationRule.TABLE_COMMENT_MISSING, 3L),
                tuple(StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING, 3L),
                tuple(StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING, 11L),
                tuple(StandardizationRule.COLUMN_COMMENT_MISSING, 12L),
                tuple(StandardizationRule.COLUMN_COMMENT_MISSING, 13L),
                tuple(StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING, 13L));
        assertThat(items).extracting(i -> i.finding().rule().severity()).containsOnly(StandardizationRule.Severity.WARNING, StandardizationRule.Severity.INFO);
        assertThat(items).filteredOn(i -> i.finding().rule() == StandardizationRule.TABLE_COMMENT_MISSING)
                .allSatisfy(i -> assertThat(i.objectType()).isEqualTo(InspectionItem.ObjectType.TABLE));
        assertThat(items).filteredOn(i -> i.finding().rule() == StandardizationRule.COLUMN_COMMENT_MISSING)
                .allSatisfy(i -> {
                    assertThat(i.objectType()).isEqualTo(InspectionItem.ObjectType.COLUMN);
                    assertThat(i.tableId()).isEqualTo(1L);
                });
    }

    @Test
    @DisplayName("fingerprint 대상 식별 (단일 대상, 공통 인코딩 v4) : 같은 대상·규칙이면 이름이 바뀌어도 같은 값, 코멘트 누락과 업무 설명 누락은 다른 값, 대상 ID 가 다르면 다른 값. 근거에는 누락 항목만")
    void fingerprintAndEvidence() {
        InspectionItem before = judge(List.of(), List.of(new CompletenessColumn(5L, 1L, "S", "T1", "OLD_NAME", null, "업무"))).getFirst();
        InspectionItem renamed = judge(List.of(), List.of(new CompletenessColumn(5L, 1L, "S2", "T9", "NEW_NAME", null, "업무"))).getFirst();
        assertThat(before.finding().fingerprint()).startsWith("v4:").isEqualTo(renamed.finding().fingerprint());
        assertThat(renamed.targetName()).isEqualTo("S2.T9.NEW_NAME");
        List<InspectionItem> both = judge(List.of(), List.of(column(5, null, null)));
        assertThat(both).extracting(i -> i.finding().fingerprint()).doesNotHaveDuplicates().hasSize(2);
        assertThat(judge(List.of(), List.of(column(6, null, "업무"))).getFirst().finding().fingerprint()).isNotEqualTo(before.finding().fingerprint());
        assertThat(before.finding().evidence()).containsExactly(java.util.Map.entry("missing", CompletenessRules.COMMENT));
        assertThat(before.finding().actualValue()).isNull();
        assertThat(before.details()).containsEntry("columnName", "OLD_NAME").doesNotContainKey("bizDescription");
    }
}
