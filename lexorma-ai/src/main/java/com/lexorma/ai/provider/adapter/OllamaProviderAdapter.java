package com.lexorma.ai.provider.adapter;

import com.lexorma.ai.provider.AiProviderType;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.entity.AiProvider;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ollama (AI-3) : Spring AI 의 {@link OllamaApi} · {@link OllamaChatModel}.
 * <ul>
 *   <li>일반 호출(/api/chat)·모델 목록(/api/tags)은 Spring AI 가 RestClient 로 보낸다 → SEC-1 요청 팩토리를 넣는다</li>
 *   <li>스트리밍(/api/chat stream)·모델 내려받기(/api/pull)는 Spring AI 가 WebClient 로 보낸다. SEC-1 의 주소 고정 전송은 동기(classic) 클라이언트뿐이라
 *       WebClient 에는 <b>모든 요청을 거부하는 교환 함수</b>를 넣는다 — 기본 커넥터(JDK HttpClient 등)로 SEC-1 을 거치지 않고 나가는 경로를 막는다.
 *       스트리밍은 AI-3 에서 지원하지 않는다 (docs/completed/ai-provider-ai3.md)</li>
 *   <li>모델을 자동으로 내려받지 않는다 (PullModelStrategy.NEVER), 자동 재시도 없음</li>
 * </ul>
 */
@Component
public class OllamaProviderAdapter implements AiProviderAdapter {

    /** Ollama 모델 이름 : [네임스페이스/]모델[:태그] (영문·숫자·. _ - : /) */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}");

    private static final RetryTemplate NO_RETRY = new RetryTemplate(RetryPolicy.withMaxRetries(0));

    /**
     * WebClient 경로(스트리밍·모델 내려받기)는 SEC-1 전송이 아니므로 보내지 않는다.
     * 커넥터도 직접 정한다 : 정하지 않으면 WebClient.Builder.build() 가 교환 함수와 상관없이 클래스패스의 기본 커넥터(JDK HttpClient)를
     * 만들어, 쓰지 않는 HTTP 클라이언트와 그 스레드가 요청마다 생긴다.
     */
    private static final WebClient.Builder NO_REACTIVE_HTTP = WebClient.builder()
            .clientConnector((method, uri, request) -> Mono.error(streamingNotSupported()))
            .exchangeFunction(request -> Mono.error(streamingNotSupported()));

    private static UnsupportedOperationException streamingNotSupported() {
        return new UnsupportedOperationException("Streaming is not supported : reactive HTTP does not go through SEC-1");
    }

    /**
     * Spring AI 의 오류 응답 처리(4xx → NonTransientAiException, 5xx → TransientAiException)에 3xx 를 더한다.
     * SEC-1 전송은 리다이렉트를 따라가지 않으므로 3xx 를 본문으로 해석하지 않고 오류로 돌려준다.
     */
    private static final ResponseErrorHandler ERRORS = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError() || response.getStatusCode().is3xxRedirection();
        }

        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            if (response.getStatusCode().is3xxRedirection()) throw new NonTransientAiException(response.getStatusCode().value() + " - redirect");
            RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER.handleError(url, method, response);
        }
    };

    @Override
    public AiProviderType type() {
        return AiProviderType.OLLAMA;
    }

    @Override
    public String defaultBaseUrl() {
        return "http://127.0.0.1:11434";
    }

    @Override
    public boolean isValidModelId(String model) {
        return model != null && MODEL_ID.matcher(model).matches();
    }

    @Override
    public ChatModel chatModel(AiProvider provider, String model, ClientHttpRequestFactory requestFactory) {
        return OllamaChatModel.builder()
                .ollamaApi(api(provider, requestFactory))
                .options(OllamaChatOptions.builder().model(model).build())
                .modelManagementOptions(ModelManagementOptions.defaults())
                .retryTemplate(NO_RETRY)
                .build();
    }

    @Override
    public boolean supportsModelList() {
        return true;
    }

    @Override
    public List<AiProviderDto.Model> models(AiProvider provider, ClientHttpRequestFactory requestFactory) {
        OllamaApi.ListModelResponse response = api(provider, requestFactory).listModels();
        if (response.models() == null) throw new IllegalStateException("Ollama model list has no models field");
        return response.models().stream().filter(Objects::nonNull).map(OllamaProviderAdapter::model).toList();
    }

    /** Ollama 는 Tool 을 지원하지 않는 모델에 tools 를 보내면 400 과 "... does not support tools" 로 거절한다 (본문은 판별에만 쓰고 내보내지 않는다) */
    @Override
    public boolean isToolCallingUnsupported(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof NonTransientAiException && message != null && message.startsWith("400 - ") && message.contains("does not support tools")) return true;
        }
        return false;
    }

    /**
     * /api/show 의 capabilities(tools 포함 여부)와 model_info 의 "&lt;아키텍처&gt;.context_length". 서버가 오류 상태(404 : 모델 없음, 오래된 Ollama 등)로 답하면
     * 알 수 없음으로 둔다 — 그 경우 Tool 호출을 시도해 보고 서버의 명시적 거절로만 미지원을 판단한다. 연결 실패·SEC-1 차단은 그대로 던진다
     */
    @Override
    public AiProviderDto.ModelInfo modelInfo(AiProvider provider, String model, ClientHttpRequestFactory requestFactory) {
        OllamaApi.ShowModelResponse show;
        try {
            show = api(provider, requestFactory).showModel(new OllamaApi.ShowModelRequest(model));
        } catch (NonTransientAiException e) {
            return AiProviderDto.ModelInfo.UNKNOWN;
        }
        if (show == null) return AiProviderDto.ModelInfo.UNKNOWN;
        Boolean tools = show.capabilities() == null ? null : show.capabilities().contains("tools");
        // 생각 기능 (Ollama capabilities 의 thinking) : 모델 이름으로 짐작하지 않는다. capabilities 가 없으면 알 수 없음
        Boolean thinking = show.capabilities() == null ? null : show.capabilities().contains("thinking");
        Integer context = null;
        if (show.modelInfo() != null) {
            for (Map.Entry<String, Object> entry : show.modelInfo().entrySet()) {
                if (entry.getKey().endsWith(".context_length") && entry.getValue() instanceof Number length && length.longValue() > 0) {
                    context = (int) Math.min(Integer.MAX_VALUE, length.longValue());
                }
            }
        }
        return new AiProviderDto.ModelInfo(tools, context, thinking);
    }

    /**
     * Ollama 응답의 prompt_eval_count·eval_count. Spring AI(2.0.2)는 없는 값을 0 으로 채우고 원본을 싣지 않으므로, 0 이면서 그 값의 소요 시간
     * (prompt_eval_duration·eval_duration — Ollama 는 센 값과 함께 보낸다)도 없으면 서버가 알려 주지 않은 것으로 본다
     */
    @Override
    public AiProviderDto.TokenUsage usage(ChatResponse response) {
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        if (metadata == null) return AiProviderDto.TokenUsage.UNKNOWN;
        return new AiProviderDto.TokenUsage(reported(metadata.get("prompt-eval-count"), metadata.get("prompt-eval-duration")),
                reported(metadata.get("eval-count"), metadata.get("eval-duration")));
    }

    private static Integer reported(Object count, Object duration) {
        if (!(count instanceof Number number)) return null;
        return number.intValue() == 0 && duration == null ? null : number.intValue();
    }

    /** Ollama 의 num_ctx(컨텍스트 길이)·num_predict(응답 최대 토큰) */
    @Override
    public ToolCallingChatOptions chatOptions(String model, Integer contextTokens, Integer maxOutputTokens) {
        return OllamaChatOptions.builder().model(model).numCtx(contextTokens).numPredict(maxOutputTokens).build();
    }

    /**
     * 구조화 답변 요청 (AES-D09) : 생각 기능이 있는 모델이면 요청 최상위 think=false. 1차 분석에서 구조화 단계의 출력 한도 초과(R4)는 생각 토큰이 num_predict 를
     * 써서였다. 생각 기능을 모르는·없는 모델에는 think 를 보내지 않는다 (예전 요청 그대로). 요청마다 새 옵션 객체다
     */
    @Override
    public ToolCallingChatOptions structuringOptions(String model, Integer contextTokens, Integer maxOutputTokens, boolean disableThinking) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder().model(model).numCtx(contextTokens).numPredict(maxOutputTokens);
        if (disableThinking) builder.thinkOption(ThinkOption.ThinkBoolean.DISABLED);
        return builder.build();
    }

    private static OllamaApi api(AiProvider provider, ClientHttpRequestFactory requestFactory) {
        return OllamaApi.builder()
                .baseUrl(provider.getBaseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(NO_REACTIVE_HTTP)
                .responseErrorHandler(ERRORS)
                .build();
    }

    private static AiProviderDto.Model model(OllamaApi.Model model) {
        OllamaApi.Model.Details details = model.details();
        LocalDateTime modified = model.modifiedAt() == null ? null : LocalDateTime.ofInstant(model.modifiedAt(), ZoneId.systemDefault());
        return new AiProviderDto.Model(model.name() != null ? model.name() : model.model(), model.size(),
                details == null ? null : details.family(), details == null ? null : details.parameterSize(), details == null ? null : details.quantizationLevel(),
                modified);
    }
}
