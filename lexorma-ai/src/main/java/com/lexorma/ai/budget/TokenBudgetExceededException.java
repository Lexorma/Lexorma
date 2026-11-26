package com.lexorma.ai.budget;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;

/**
 * 토큰 예산 초과 ({@link ErrorCode#AI_TOKEN_BUDGET_EXCEEDED}, 오류 코드·HTTP 상태는 예전 그대로). {@link TokenMeter} 가 어느 판정에서 멈췄는지를 함께 싣는다 —
 * AI 메타데이터 질의는 Tool 단계에서 이 예외로 멈췄을 때만 부분 답변을 시도한다 (AI 공통 실행 안정성 2차, AES-D01·D03 — docs/completed/ai-execution-stability.md).
 * 예외로 끝내므로 Spring AI 의 Tool 반복은 확실히 끝나고, 가짜 응답·빈 메시지를 만들지 않는다.
 */
public class TokenBudgetExceededException extends CustomException {

    /** 멈춘 판정 */
    public enum Kind {
        /** 보내기 전 : 요청 하나의 추정 입력이 입력 상한(컨텍스트 − 출력)을 넘는다 */
        REQUEST_INPUT,
        /** 보내기 전 : 질의 누적 + 추정 입력 + 출력 한도가 질의 전체 상한을 넘는다 */
        QUERY_TOTAL,
        /** 받은 뒤 : 응답이 출력 한도에서 잘렸다 (finish reason length) */
        OUTPUT_LENGTH
    }

    private final Kind kind;
    private final boolean firstRequest;

    TokenBudgetExceededException(Kind kind, boolean firstRequest) {
        super(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        this.kind = kind;
        this.firstRequest = firstRequest;
    }

    /** 원인을 덧붙인 문구 (한국어 원문, {0}… 자리) — 내부 지시·Tool 결과·인증 정보는 넣지 않는다 */
    TokenBudgetExceededException(Kind kind, boolean firstRequest, String detail, Object... args) {
        super(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED, detail, args);
        this.kind = kind;
        this.firstRequest = firstRequest;
    }

    public Kind kind() {
        return kind;
    }

    /** 이 질의의 첫 모델 요청에서 멈췄다 (아직 아무것도 보내지 않았다) */
    public boolean firstRequest() {
        return firstRequest;
    }
}
