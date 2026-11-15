package com.lexorma.data.ai.domain.dto;

import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.data.ai.evidence.EvidenceVerifier;
import com.lexorma.data.ai.tool.MetadataToolSession;

import com.lexorma.data.ai.budget.TokenMeter;
import com.lexorma.data.ai.service.AiMetadataQueryService;
import java.util.List;

/**
 * AI 메타데이터 질의 (AI-4). 프로젝트는 요청에 두지 않는다 : 로그인 세션의 현재 프로젝트다.
 */
public final class AiMetadataQueryDto {

    private AiMetadataQueryDto() {
    }

    public record QueryRequest(String question) {
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
     */
    public record QueryResult(String answer, String model, List<MetadataToolSession.Call> tools, long durationMillis,
                              List<EvidenceVerifier.Result> evidence, EvidenceVerifier.Summary verification, int structuredRetries,
                              MetadataToolSession.Mode mode, AiMetadataQueryService.FallbackReason fallback, TokenMeter.Usage usage) {
    }
}
