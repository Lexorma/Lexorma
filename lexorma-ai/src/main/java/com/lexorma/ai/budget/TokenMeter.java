package com.lexorma.ai.budget;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.service.AiCall;
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
 * <p>
 * AI 공통 실행 안정성 2차 (docs/completed/ai-execution-stability.md) : 초과는 {@link TokenBudgetExceededException} 으로 어느 판정인지(입력·누적·출력)를 싣는다 (오류 코드는 그대로).
 * {@link #advisor(DeliveryListener)} 는 AI 서버가 응답을 돌려준 요청에 실린 Tool 결과 수를 알린다 — 판정 통과만으로는 전달로 보지 않는다 (AES-D04).
 * 구조화 요청 후보의 추정({@link #estimate(String, String)})과 판정({@link #fit(int)})은 보내기 전 판정과 같은 규칙이다.
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

    /** 구조화 요청 후보가 지금 보낼 수 있는지 : 된다 / 요청 입력 상한을 넘는다 / 질의 누적 상한을 넘는다 */
    public enum Fit { OK, REQUEST_INPUT, QUERY_TOTAL }

    /**
     * Tool 결과 전달 확인 (AES-D04) : 요청을 보내고 AI 서버가 응답을 돌려준 뒤에 그 요청에 실린 Tool 응답 수(대화 전체)를 알린다.
     * 보내기 전에 막힌 요청·AI 서버 호출이 실패한 요청은 알리지 않는다. 응답이 출력 한도에서 잘렸어도 요청은 처리됐으므로 알린 뒤 예외를 낸다
     */
    @FunctionalInterface
    public interface DeliveryListener {
        void delivered(int toolResponses);
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

    /**
     * 구조화 답변 요청의 모델 옵션 (AES-D09) : {@link #options()} 와 같고, 모델 정보가 생각(thinking) 기능을 알려 준 Ollama 모델이면 그 요청에서만 생각을 끈다.
     * 요청마다 새 옵션 객체다 (공유 설정을 바꾸지 않는다)
     */
    public ToolCallingChatOptions structuringOptions() {
        return call.structuringOptions(plan.context(), plan.output());
    }

    public CallAdvisor advisor() {
        return new Advisor(null, false);
    }

    /** Tool 단계용 : 응답을 받은 요청마다 실린 Tool 응답 수를 알린다 */
    public CallAdvisor advisor(DeliveryListener listener) {
        return new Advisor(listener, false);
    }

    /**
     * 구조화 단계용 (AI 공통 실행 안정성 3차 AES-D16) : {@link #advisor()} 와 같이 예산·사용량을 보고, 모델이 Tool 호출을 돌려주면 사용량을 기록한 뒤 그 자리에서
     * {@link ErrorCode#AI_STRUCTURED_OUTPUT_INVALID} 로 끝낸다 — Spring AI 반복이 거절 결과를 붙여 모델을 다시 부르지 않고, Tool 도 실행하지 않는다.
     * 구조화 스키마 재요청(형식 오류만)의 대상이 아니다 (예전에도 이 경로는 재요청하지 않았다)
     */
    public CallAdvisor structuringAdvisor() {
        return new Advisor(null, true);
    }

    /** 구조화 요청 후보(시스템 지시 + 사용자 메시지, Tool 정의 없음)의 추정 입력 — 보내기 전 판정({@link #estimate(ChatClientRequest)})과 같은 규칙 */
    public static int estimate(String system, String user) {
        return (int) Math.min(Integer.MAX_VALUE, 2L * TokenEstimator.PER_MESSAGE + TokenEstimator.estimate(system) + TokenEstimator.estimate(user));
    }

    /** 이 추정 입력의 요청을 지금 보낼 수 있는지 (보내기 전 판정과 같은 조건 — 추정이라 실제 성공을 보장하지 않는다) */
    public synchronized Fit fit(int input) {
        if (input > plan.input()) return Fit.REQUEST_INPUT;
        if (charged + input + plan.output() > plan.total()) return Fit.QUERY_TOTAL;
        return Fit.OK;
    }

    /** {@link #fit} 이 OK 가 아닐 때 보내기 전 판정이 냈을 예외 */
    public TokenBudgetExceededException exceeded(Fit fit) {
        return new TokenBudgetExceededException(fit == Fit.QUERY_TOTAL ? TokenBudgetExceededException.Kind.QUERY_TOTAL
                : TokenBudgetExceededException.Kind.REQUEST_INPUT, false);
    }

    public synchronized Usage usage() {
        return new Usage(calls, prompt, completion, unmeasured, estimated, plan.context(), plan.output(), plan.total());
    }

    /** 보내기 전 판정. 넘으면 보내지 않는다 */
    synchronized int admit(ChatClientRequest request) {
        int input = estimate(request);
        boolean first = calls == 0;
        if (input > plan.input()) {
            log.info("AI 토큰 예산 초과 (요청 입력{}) : 추정 {} > 상한 {} (모델 컨텍스트 {}, 적용 컨텍스트 {}, 출력 {})", first ? ", 첫 요청" : "", input, plan.input(),
                    plan.modelContext(), plan.context(), plan.output());
            // 첫 요청부터 못 보내면(작은 컨텍스트 모델 — 1차 B13) 원인을 숫자로 알린다. 지시·질문·Tool 정의 원문은 넣지 않는다
            if (first) throw new TokenBudgetExceededException(TokenBudgetExceededException.Kind.REQUEST_INPUT, true,
                    "AI 모델 컨텍스트 {0} 토큰에서 응답 몫 {1} 토큰을 뺀 요청 입력 상한 {2} 토큰이 첫 요청의 추정 입력 {3} 토큰보다 작아 질의를 시작하지 못했습니다. 관리자에게 이 AI 모델의 컨텍스트 길이와 Provider 모델 설정 확인을 요청하세요.",
                    plan.context(), plan.output(), plan.input(), input);
            throw new TokenBudgetExceededException(TokenBudgetExceededException.Kind.REQUEST_INPUT, false);
        }
        if (charged + input + plan.output() > plan.total()) {
            log.info("AI 토큰 예산 초과 (질의 누적) : 누적 {} + 추정 입력 {} + 출력 한도 {} > {}", charged, input, plan.output(), plan.total());
            throw new TokenBudgetExceededException(TokenBudgetExceededException.Kind.QUERY_TOTAL, first);
        }
        estimated += input;
        return input;
    }

    synchronized void record(int input, ChatResponse response) {
        boolean first = calls == 0;
        calls++;
        AiProviderDto.TokenUsage used = call.usage(response);
        if (used.promptTokens() != null) prompt = (prompt == null ? 0 : prompt) + used.promptTokens();
        if (used.completionTokens() != null) completion = (completion == null ? 0 : completion) + used.completionTokens();
        if (used.promptTokens() == null || used.completionTokens() == null) unmeasured++;
        charged += (used.promptTokens() != null ? used.promptTokens() : input) + (used.completionTokens() != null ? used.completionTokens() : plan.output());
        Generation result = response == null ? null : response.getResult();
        if (result != null && LENGTH.equalsIgnoreCase(result.getMetadata().getFinishReason())) {
            log.info("AI 토큰 예산 초과 (응답이 출력 한도 {} 에서 잘림)", plan.output());
            throw new TokenBudgetExceededException(TokenBudgetExceededException.Kind.OUTPUT_LENGTH, first);
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

    /** 요청에 실린 Tool 응답 수 (대화 전체 — Spring AI 는 Tool 반복마다 대화 전체를 다시 보낸다) */
    static int toolResponses(ChatClientRequest request) {
        int count = 0;
        for (Message message : request.prompt().getInstructions()) {
            if (message instanceof ToolResponseMessage tool) count += tool.getResponses().size();
        }
        return count;
    }

    /** Tool 호출 반복 안쪽(모델 호출 바로 앞)에서 돈다 */
    private final class Advisor implements CallAdvisor {
        private final DeliveryListener listener;
        private final boolean rejectToolCalls;

        Advisor(DeliveryListener listener, boolean rejectToolCalls) {
            this.listener = listener;
            this.rejectToolCalls = rejectToolCalls;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            int input = admit(request);
            ChatClientResponse response = chain.nextCall(request);
            // AI 서버가 이 요청에 응답했다 = 실린 Tool 결과가 모델에게 갔다 (사용량 기록·출력 잘림 판정 전에 알린다)
            if (listener != null && response != null && response.chatResponse() != null) listener.delivered(toolResponses(request));
            record(input, response == null ? null : response.chatResponse());
            if (rejectToolCalls && response != null && response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
                log.info("AI 구조화 응답에 Tool 호출 : 실행하지 않고 이 구조화 시도를 끝낸다");
                throw new CustomException(ErrorCode.AI_STRUCTURED_OUTPUT_INVALID);
            }
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
