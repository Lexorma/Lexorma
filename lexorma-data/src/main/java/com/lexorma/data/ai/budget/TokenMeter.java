package com.lexorma.data.ai.budget;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.service.AiCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 한 질의의 토큰 예산 집행과 사용량 기록 (AI-4). ChatClient 요청에 {@link #advisor()} 를 넣으면 <b>모델 호출마다</b>(Tool 단계의 반복 호출 포함 —
 * Spring AI {@link ToolCallingAdvisor} 다음 순서라 그 반복 안에서 불린다) 다음을 한다.
 * <ol>
 *   <li>보내기 전 : 메시지(지시·질문·Tool 호출 인자·Tool 결과)와 Tool 정의의 토큰을 추정({@link TokenEstimator})해 입력 상한, 질의 누적 상한(추정 입력 + 출력 한도)을 넘으면
 *       보내지 않고 {@link ErrorCode#AI_TOKEN_BUDGET_EXCEEDED}</li>
 *   <li>받은 뒤 : AI 서버가 알려 준 사용량(실측)을 더한다. 없으면 실측 없음으로 세고 0 으로 치지 않는다 — 누적 판정에는 그 호출의 추정 입력 + 출력 한도를 쓴다.
 *       응답이 출력 한도에서 잘렸으면(finish reason length) 쓰지 않는다</li>
 * </ol>
 * 질의마다 만든다 (상태가 있다).
 */
@Slf4j
public final class TokenMeter {

    /**
     * 질의의 토큰 사용량 (응답용). 실측과 추정을 섞지 않는다.
     *
     * @param modelCalls           모델 호출 수
     * @param promptTokens         AI 서버가 알려 준 입력 토큰 합 (알려 준 호출만. 하나도 없으면 null)
     * @param completionTokens     AI 서버가 알려 준 출력 토큰 합 (같은 규칙)
     * @param unmeasuredCalls      사용량을 알려 주지 않은 호출 수
     * @param estimatedInputTokens 보내기 전 판정에 쓴 입력 추정치의 합 (실측이 아니다)
     * @param contextTokens        요청마다 정한 컨텍스트 길이
     * @param maxOutputTokens      요청마다 정한 응답 최대 토큰
     * @param totalBudget          질의 전체 누적 상한
     */
    public record Usage(int modelCalls, Integer promptTokens, Integer completionTokens, int unmeasuredCalls, int estimatedInputTokens,
                        int contextTokens, int maxOutputTokens, int totalBudget) {
    }

    /** 응답이 출력 한도에서 잘렸다는 종료 사유 (Ollama done_reason) */
    static final String LENGTH = "length";

    private final TokenBudget.Plan plan;
    private final AiCall call;
    private int calls;
    private Integer prompt;
    private Integer completion;
    private int unmeasured;
    private int estimated;
    /** 누적 판정용 : 실측 합 + 실측 없는 호출의 (추정 입력 + 출력 한도) */
    private long charged;

    public TokenMeter(TokenBudget.Plan plan, AiCall call) {
        this.plan = plan;
        this.call = call;
    }

    public TokenBudget.Plan plan() {
        return plan;
    }

    /** 요청 하나의 모델 옵션 (컨텍스트 길이·응답 최대 토큰) */
    public ToolCallingChatOptions options() {
        return call.options(plan.context(), plan.output());
    }

    public CallAdvisor advisor() {
        return new Advisor();
    }

    public synchronized Usage usage() {
        return new Usage(calls, prompt, completion, unmeasured, estimated, plan.context(), plan.output(), plan.total());
    }

    /** 보내기 전 판정. 넘으면 보내지 않는다 */
    synchronized int admit(ChatClientRequest request) {
        int input = estimate(request);
        if (input > plan.input()) {
            log.info("AI 토큰 예산 초과 (요청 입력) : 추정 {} > 상한 {} (컨텍스트 {}, 출력 {})", input, plan.input(), plan.context(), plan.output());
            throw new CustomException(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        }
        if (charged + input + plan.output() > plan.total()) {
            log.info("AI 토큰 예산 초과 (질의 누적) : 누적 {} + 추정 입력 {} + 출력 한도 {} > {}", charged, input, plan.output(), plan.total());
            throw new CustomException(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        }
        estimated += input;
        return input;
    }

    synchronized void record(int input, ChatResponse response) {
        calls++;
        AiProviderDto.TokenUsage used = call.usage(response);
        if (used.promptTokens() != null) prompt = (prompt == null ? 0 : prompt) + used.promptTokens();
        if (used.completionTokens() != null) completion = (completion == null ? 0 : completion) + used.completionTokens();
        if (used.promptTokens() == null || used.completionTokens() == null) unmeasured++;
        charged += (used.promptTokens() != null ? used.promptTokens() : input) + (used.completionTokens() != null ? used.completionTokens() : plan.output());
        Generation result = response == null ? null : response.getResult();
        if (result != null && LENGTH.equalsIgnoreCase(result.getMetadata().getFinishReason())) {
            log.info("AI 토큰 예산 초과 (응답이 출력 한도 {} 에서 잘림)", plan.output());
            throw new CustomException(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        }
    }

    /** 요청 입력의 추정 토큰 : 메시지 본문·Tool 호출 인자·Tool 결과 + Tool 정의(이름·설명·입력 스키마) */
    static int estimate(ChatClientRequest request) {
        long total = 0;
        for (Message message : request.prompt().getInstructions()) {
            total += TokenEstimator.PER_MESSAGE + TokenEstimator.estimate(message.getText());
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    total += TokenEstimator.estimate(toolCall.name()) + TokenEstimator.estimate(toolCall.arguments());
                }
            }
            if (message instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    total += TokenEstimator.PER_MESSAGE + TokenEstimator.estimate(response.name()) + TokenEstimator.estimate(response.responseData());
                }
            }
        }
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                ToolDefinition definition = callback.getToolDefinition();
                total += TokenEstimator.PER_MESSAGE + TokenEstimator.estimate(definition.name()) + TokenEstimator.estimate(definition.description())
                        + TokenEstimator.estimate(definition.inputSchema());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Tool 호출 반복 안쪽(모델 호출 바로 앞)에서 돈다 */
    private final class Advisor implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            int input = admit(request);
            ChatClientResponse response = chain.nextCall(request);
            record(input, response == null ? null : response.chatResponse());
            return response;
        }

        @Override
        public String getName() {
            return "lexorma-token-budget";
        }

        @Override
        public int getOrder() {
            return ToolCallingAdvisor.DEFAULT_ORDER + 1;
        }
    }
}
