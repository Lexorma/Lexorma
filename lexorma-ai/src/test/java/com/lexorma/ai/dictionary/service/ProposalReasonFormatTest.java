package com.lexorma.ai.dictionary.service;

import com.lexorma.ai.evidence.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재검토 사유 문자열의 형식 계약 (AI-5-REASON) : 화면(`lexorma-web/.../dictionary/ai/reviewReason.ts`)은 저장된 사유를 이 형식으로 읽어 사용자 문구로 바꾼다.
 * `종류 유형:ID 이름 [필드, …]` 를 `; ` 로 잇고, 1000자를 넘으면 뒤 변경을 통째로 뺀다. 여기의 정규식은 화면 파서의 정규식과 같다 — 형식을 바꾸면 둘 다 바꾼다.
 */
class ProposalReasonFormatTest {

    /** reviewReason.ts 의 SEGMENT 와 같다 (종류 목록은 ReviewReasonContractTest 가 서버 소스와 대조한다) */
    private static final Pattern SEGMENT = Pattern.compile(
            "^(BIZ_DESCRIPTION_CHANGED|TERM_LINK_CHANGED|MODIFIED|MODIFIED_UNKNOWN|TARGET_DELETED|OBJECT_DELETED|SOURCE_CHANGED|SOURCE_TABLE_MISSING)"
                    + "(?: ([A-Z]+):(\\S+))?(?: +(.*?))?(?: \\[([^\\]]*)\\])?$");

    @Test
    @DisplayName("모든 종류 : 유형·ID·이름·필드가 정해진 자리에 있고 화면 파서가 같은 값으로 읽는다 (유형·이름·필드가 없어도)")
    void everyKindParses() {
        List<ProposalVersions.Change> changes = List.of(
                new ProposalVersions.Change("BIZ_DESCRIPTION_CHANGED", EvidenceType.COLUMN, 28L, "TB_PRJ.DB_TYPE_CD", List.of("bizDescription")),
                new ProposalVersions.Change("TERM_LINK_CHANGED", EvidenceType.COLUMN, 3L, "ORD_STTS_CD", List.of("standardTermsId", "standardTermsName")),
                new ProposalVersions.Change("MODIFIED", EvidenceType.TABLE, 4L, "TB_ORD", List.of("tableKorName", "schemaId")),
                new ProposalVersions.Change("MODIFIED_UNKNOWN", EvidenceType.TERM, 7L, "주문_상태_코드", List.of()),
                new ProposalVersions.Change("TARGET_DELETED", EvidenceType.COLUMN, 9L, null, List.of()),
                new ProposalVersions.Change("OBJECT_DELETED", EvidenceType.RELATION, 11L, "FK_ORD_CUST", List.of()),
                new ProposalVersions.Change("SOURCE_CHANGED", EvidenceType.TABLE, 4L, "TB_PAY", List.of("PAY_AMT:comment", "tableComment")),
                new ProposalVersions.Change("SOURCE_TABLE_MISSING", EvidenceType.TABLE, null, "TB_PAY", List.of()),
                new ProposalVersions.Change("SOURCE_TABLE_MISSING", null, null, "TB_X", List.of()));
        String reason = AiDescriptionReviewService.reason(changes);
        String[] segments = reason.split("; ");
        assertThat(segments).hasSize(changes.size());
        for (int i = 0; i < segments.length; i++) {
            ProposalVersions.Change c = changes.get(i);
            Matcher m = SEGMENT.matcher(segments[i]);
            assertThat(m.matches()).as(segments[i]).isTrue();
            assertThat(m.group(1)).isEqualTo(c.kind());
            assertThat(m.group(2)).isEqualTo(c.type() == null ? null : c.type().name());
            assertThat(m.group(4) == null ? null : m.group(4).strip()).isEqualTo(c.name());
            List<String> fields = m.group(5) == null ? List.of() : List.of(m.group(5).split(", "));
            assertThat(fields).isEqualTo(c.fields());
        }
    }

    @Test
    @DisplayName("1000자 : 넘치면 세그먼트 중간이 아니라 뒤 변경을 통째로 뺀다 (남은 세그먼트는 모두 읽힌다)")
    void truncatesWholeSegments() {
        List<ProposalVersions.Change> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(new ProposalVersions.Change("MODIFIED", EvidenceType.COLUMN, (long) i, "TB_LONG_TABLE.COLUMN_" + i, List.of("dataType", "columnDesc")));
        String reason = AiDescriptionReviewService.reason(many);
        assertThat(reason.length()).isLessThanOrEqualTo(AiDescriptionReviewService.REASON_MAX);
        for (String segment : reason.split("; ")) assertThat(SEGMENT.matcher(segment).matches()).as(segment).isTrue();
    }
}
