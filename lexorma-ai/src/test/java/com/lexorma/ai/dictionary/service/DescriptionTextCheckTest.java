package com.lexorma.ai.dictionary.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 설명 문구 검사 (AI-5 프롬프트 v2) : 명백한 자기 평가와 메타데이터 내부 ID 만 잡고, 업무 표현은 잡지 않는다.
 */
class DescriptionTextCheckTest {

    private static final Set<Long> IDS = Set.of(4L, 7001L, 6401L, 2401L, 15L);

    @ParameterizedTest(name = "자기 평가 : {0}")
    @ValueSource(strings = {
            "등록 시각을 나타냅니다. (신뢰도: 확정됨)",
            "결제 금액입니다 (신뢰도 95%)",
            "확실성: 높음. 결제 금액입니다.",
            "해당 레코드가 등록된 시각입니다. (메타데이터에서 확인됨)",
            "수정 일시입니다 (확인됨)",
            "AI가 검증한 사실입니다.",
            "AI 가 확인한 내용에 따르면 결제 금액입니다.",
            "이 설명의 정확성이 보장됩니다.",
            "100% 확실합니다.",
            "This is the payment amount (verified).",
            "Verified by AI: the payment amount.",
            "Confidence: high. The payment amount."})
    void selfAssessment(String text) {
        assertThat(DescriptionTextCheck.check(draft(text), IDS, "")).extracting(DescriptionTextCheck.Finding::issue)
                .contains(DescriptionTextCheck.Issue.SELF_ASSESSMENT);
    }

    @ParameterizedTest(name = "업무 표현은 통과 : {0}")
    @ValueSource(strings = {
            "확정된 업무 설명에 따르면 결제 승인 금액입니다.",
            "신뢰할 수 있는 거래처인지 나타내는 코드입니다.",
            "검증 규칙의 결과 코드입니다. 원본 COMMENT 에 따르면 '검증 완료 여부' 를 뜻합니다.",
            "위치 정확도: 미터 단위로 추정됩니다.",
            "주문 ID 를 담는 컬럼입니다. 고객 식별자와 함께 씁니다.",
            "버전 2 부터 쓰입니다. 길이 4 의 코드입니다.",
            "최대 100% 까지 할인율을 나타냅니다.",
            "확인이 필요한 부분은 질문으로 남깁니다."})
    void businessWording(String text) {
        assertThat(DescriptionTextCheck.check(draft(text), IDS, "")).isEmpty();
    }

    @Test
    @DisplayName("내부 ID : 메타데이터의 ID 가 ID 라는 말 뒤에 나오면 잡고, 메타데이터에 없는 숫자·근거 필드의 ID 는 통과")
    void internalIds() {
        assertThat(issues("표준 용어 '제공자_명'(ID 7001)과 도메인 '명V100'(ID 4)을 따릅니다.")).containsExactly(DescriptionTextCheck.Issue.INTERNAL_ID,
                DescriptionTextCheck.Issue.INTERNAL_ID);
        assertThat(issues("(Term ID 6401에 따르면 JDBC 와 AI_HTTP 를 포함합니다.)")).containsExactly(DescriptionTextCheck.Issue.INTERNAL_ID);
        assertThat(issues("tableId=2401 인 테이블의 컬럼입니다.")).containsExactly(DescriptionTextCheck.Issue.INTERNAL_ID);
        assertThat(issues("식별자: 15 인 스키마에 있습니다.")).containsExactly(DescriptionTextCheck.Issue.INTERNAL_ID);
        assertThat(issues("주문 ID 9999 처럼 업무 번호를 담습니다.")).as("메타데이터에 없는 숫자").isEmpty();
        // 근거·추천 필드의 ID 는 문구가 아니라 검사하지 않는다
        DescriptionDraft withEvidence = new DescriptionDraft("COLUMN", 7001L, "제공자 이름입니다.", "INFERRED", null,
                new DescriptionDraft.Recommendation(7001L, "제공자_명", "ID 7001 의 용어"), null, List.of(), List.of(), List.of(), List.of());
        assertThat(DescriptionTextCheck.check(withEvidence, IDS, "")).isEmpty();
    }

    @Test
    @DisplayName("이름 해석·불확실한 부분·확인 질문도 본다. 같은 표현이 서버가 준 메타데이터(확정 업무 정보)에 있으면 자기 평가로 보지 않는다")
    void fieldsAndContext() {
        DescriptionDraft d = new DescriptionDraft("COLUMN", 1L, "결제 금액입니다.", "INFERRED", "PAY(결제) AMT(금액), ID 7001 참고", null, null, List.of(), List.of(),
                List.of("통화 단위 (신뢰도: 낮음)"), List.of("부가세가 포함되나요?"));
        assertThat(DescriptionTextCheck.check(d, IDS, "")).extracting(DescriptionTextCheck.Finding::field).containsExactlyInAnyOrder("nameInterpretation", "unknowns");
        String context = "{\"businessDescription\":\"정산 담당자가 확정한 금액 (확인됨)\"}";
        assertThat(DescriptionTextCheck.check(draft("정산 담당자가 확정한 금액 (확인됨) 입니다."), IDS, context)).isEmpty();
    }

    @Test
    @DisplayName("메타데이터 ID 모으기 : id 와 *Id 필드의 정수만 (이름·길이 같은 숫자는 아니다)")
    void collectIds() {
        assertThat(DescriptionTextCheck.ids(List.of("{\"tableId\":10,\"columns\":[{\"id\":11,\"maxLength\":100,\"termId\":12}],\"relations\":[{\"relationId\":13}]}", "not json")))
                .containsExactlyInAnyOrder(10L, 11L, 12L, 13L);
    }

    private static List<DescriptionTextCheck.Issue> issues(String text) {
        return DescriptionTextCheck.check(draft(text), IDS, "").stream().map(DescriptionTextCheck.Finding::issue).toList();
    }

    private static DescriptionDraft draft(String description) {
        return new DescriptionDraft("COLUMN", 1L, description, "INFERRED", null, null, null, List.of(), List.of(), List.of(), List.of());
    }
}
