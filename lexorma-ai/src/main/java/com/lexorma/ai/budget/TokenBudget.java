package com.lexorma.ai.budget;

import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 메타데이터 질의의 토큰 예산 (AI-4). 문자 수 상한(질문·Tool 결과 등)은 그대로 두고, 모델 호출마다 다음을 더 지킨다.
 * <ul>
 *   <li><b>컨텍스트</b> : 요청마다 모델 컨텍스트 길이를 정해 보낸다 (Ollama num_ctx). 설정값과 모델이 알려 준 최대 길이 중 작은 것 —
 *       서버 기본값에 맡기면 넘친 앞부분을 서버가 말없이 잘라 의미가 바뀔 수 있다</li>
 *   <li><b>출력</b> : 응답 최대 토큰 (Ollama num_predict). 응답이 이 한도에서 잘리면 쓰지 않는다 ({@code AI_TOKEN_BUDGET_EXCEEDED})</li>
 *   <li><b>입력</b> : 컨텍스트 − 출력. 보내기 전에 {@link TokenEstimator} 의 보수적 추정으로 넘는지 본다 — 넘으면 보내지 않는다(잘라 보내지 않는다)</li>
 *   <li><b>질의 전체</b> : 한 질의의 모든 모델 호출(Tool 단계 반복·구조화·재요청)의 누적 토큰. 다음 호출의 추정 입력 + 출력 한도까지 더해 넘으면 보내지 않는다</li>
 * </ul>
 * 설정(선택, 없으면 기본값) : {@code lexorma.ai.query.context-tokens}(16384), {@code lexorma.ai.query.output-tokens}(4096), {@code lexorma.ai.query.total-tokens}(100000).
 * 모델마다 토큰을 세는 방식이 달라 서버는 정확한 토큰 수를 알 수 없다 — 입력 판정은 추정이고, 응답 후의 사용량은 AI 서버가 알려 준 값(실측)만 쓴다.
 */
@Component
public class TokenBudget {

    static final int MIN_CONTEXT = 2048;
    static final int MAX_CONTEXT = 262_144;
    static final int MIN_OUTPUT = 256;

    private final int contextTokens;
    private final int outputTokens;
    private final int totalTokens;

    public TokenBudget(@Value("${lexorma.ai.query.context-tokens:16384}") int contextTokens,
                       @Value("${lexorma.ai.query.output-tokens:4096}") int outputTokens,
                       @Value("${lexorma.ai.query.total-tokens:100000}") int totalTokens) {
        if (contextTokens < MIN_CONTEXT || contextTokens > MAX_CONTEXT) {
            throw new IllegalStateException("lexorma.ai.query.context-tokens 는 " + MIN_CONTEXT + "~" + MAX_CONTEXT + " 이어야 합니다 : " + contextTokens);
        }
        if (outputTokens < MIN_OUTPUT || outputTokens > contextTokens / 2) {
            throw new IllegalStateException("lexorma.ai.query.output-tokens 는 " + MIN_OUTPUT + "~(context-tokens/2) 이어야 합니다 : " + outputTokens);
        }
        if (totalTokens < contextTokens) throw new IllegalStateException("lexorma.ai.query.total-tokens 는 context-tokens 이상이어야 합니다 : " + totalTokens);
        this.contextTokens = contextTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
    }

    /**
     * 한 질의의 예산
     *
     * @param context 요청마다 보낼 컨텍스트 길이 (모델 최대 길이 이하)
     * @param output  응답 최대 토큰
     * @param input   한 요청의 입력 상한 (추정) = context − output
     * @param total   질의 전체 누적 상한
     * @param modelContext 모델이 알려 준 최대 컨텍스트 (모르면 null)
     */
    public record Plan(int context, int output, int input, int total, Integer modelContext) {
    }

    /** 모델 정보에 맞춘 예산. 모델 컨텍스트가 설정보다 작으면 그것에 맞추고, 출력은 컨텍스트의 1/4 을 넘지 않게 줄인다 */
    public Plan plan(AiProviderDto.ModelInfo model) {
        Integer known = model == null ? null : model.contextLength();
        int context = known == null ? contextTokens : Math.min(contextTokens, known);
        int output = context >= contextTokens ? outputTokens : Math.min(outputTokens, Math.max(context / 4, Math.min(MIN_OUTPUT, context / 2)));
        return new Plan(context, output, context - output, totalTokens, known);
    }
}
