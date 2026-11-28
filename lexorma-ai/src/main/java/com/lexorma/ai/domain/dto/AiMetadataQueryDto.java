package com.lexorma.ai.domain.dto;

import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.tool.MetadataToolSession;

import com.lexorma.ai.budget.TokenMeter;
import com.lexorma.ai.service.AiMetadataQueryService;
import java.util.List;

/**
 * AI 메타데이터 질의 (AI-4). 프로젝트는 요청에 두지 않는다 : 로그인 세션의 현재 프로젝트다.
 */
public final class AiMetadataQueryDto {

    private AiMetadataQueryDto() {
    }

    /**
     * 질의 요청. scope = 기능 범위 (AI-7 AI7-D24·D26) — 생략(null) = METADATA, 알 수 없는 값은 요청 본문을 읽을 때 거절된다 (400, 조용히 METADATA 로 바꾸지 않는다).
     * Tool 이름·목록은 받지 않는다 (범위별 목록은 서버가 정한다)
     */
    public record QueryRequest(String question, com.lexorma.ai.tool.ToolScope scope) {

        /** 범위 없이 (= METADATA) */
        public QueryRequest(String question) {
            this(question, null);
        }
    }

    /** 질의를 쓸 수 있는지 (시스템 기본 Provider 의 상태만. Provider 주소·이름 등 관리 정보는 담지 않는다). 외부에 연결하지 않고 판단한다 */
    public record Availability(AiProviderDto.DefaultStatus status) {
    }

    /**
     * @param answer         AI 의 최종 답변 (근거는 tools 의 조회 결과. 답변 문장 자체는 AI 의 정리다)
     * @param model          답한 모델
     * @param tools          서버에서 실제로 실행된 Tool (순서대로. 모델이 답변에서 주장한 것이 아니다)
     * @param durationMillis 질의 전체 시간
     * @param evidence       AI 가 든 근거와 서버의 검증 결과 (AI-4 후속)
     * @param verification   답변 전체의 근거 검증 상태 (VERIFIED · PARTIAL · UNVERIFIED · NO_EVIDENCE)
     * @param structuredRetries 구조화 응답을 다시 요청한 횟수 (0 또는 1)
     * @param mode           조회 방식 : 모델의 Tool 호출(TOOL_CALLING) / Tool Calling 을 지원하지 않는 모델이라 서버가 조회한 맥락(SERVER_CONTEXT)
     * @param fallback       서버 맥락으로 바꾼 이유 (TOOL_CALLING 이면 null)
     * @param usage          토큰 사용량 (AI 서버가 알려 준 실측과 서버의 추정을 나눠 담는다)
     * @param completion     실행 완료 상태 (AI 공통 실행 안정성 2차 — 서버가 정한다. 근거 검증 상태 verification 과 따로다)
     */
    public record QueryResult(String answer, String model, List<MetadataToolSession.Call> tools, long durationMillis,
                              List<EvidenceVerifier.Result> evidence, EvidenceVerifier.Summary verification, int structuredRetries,
                              MetadataToolSession.Mode mode, AiMetadataQueryService.FallbackReason fallback, TokenMeter.Usage usage,
                              Completion completion) {
    }

    /**
     * 완료 상태 (AES-D05) : COMPLETE = 실행 제한으로 멈추지 않고 정해진 절차를 끝냈다 (모든 메타데이터를 빠짐없이 조회했다거나 답이 사실이라는 뜻이 아니다) /
     * PARTIAL = 허용된 사유로 추가 조회·초안 생성이 멈췄고, 그때까지 모델에게 전달된 결과 중 최종 답변 요청에 넣을 수 있는 앞부분으로 답했다
     */
    public enum CompletionStatus { COMPLETE, PARTIAL }

    /** 부분 답변의 종료 사유 (서버가 정한다. 모델이 정하지 않는다) */
    public enum TerminationReason {
        /** 다음 요청(또는 최종 답변 요청)의 추정 입력이 요청 입력 상한을 넘었다 */
        INPUT_BUDGET_EXCEEDED,
        /** 질의 전체 누적 토큰 상한에 닿았다 */
        QUERY_BUDGET_EXCEEDED,
        /** 질의당 전체 Tool 호출 상한(8)에 닿았다 */
        TOTAL_TOOL_LIMIT_REACHED,
        /** 같은 Tool 호출 상한(4)에 닿았다 */
        SAME_TOOL_LIMIT_REACHED,
        /** Tool 단계의 초안 답변이 출력 한도에서 잘렸다 (잘린 초안은 쓰지 않는다) */
        DRAFT_OUTPUT_LIMIT_REACHED,
        /** 최종 구조화 요청의 조회 결과 원문 24,000자 상한으로 결과 일부를 넣지 못했다 (AES-D13 — 개별 Tool 의 RESULT_TOO_LARGE 와 다르다) */
        CONTEXT_RESULT_LIMIT_REACHED
    }

    /**
     * @param status        COMPLETE · PARTIAL
     * @param reason        PARTIAL 의 종료 사유 (COMPLETE 면 null)
     * @param executedTools 실제 실행 경로에 들어간 Tool 호출 수 (성공·실패·결과 상한 초과 포함, 허용 목록 밖 이름 거절·상한으로 실행 전에 막힌 호출 제외).
     *                      성공 결과 수·근거 수가 아니다
     */
    public record Completion(CompletionStatus status, TerminationReason reason, int executedTools) {
    }
}
