package com.lexorma.core.ai.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.dto.AiProviderDto.Failure;
import com.lexorma.core.ai.domain.entity.AiProvider;
import com.lexorma.core.ai.provider.AiProviderAdapter;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.PinnedSocket;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.http.ExternalConnectionBlockedException;
import com.lexorma.core.network.http.ExternalHttpClients;
import com.lexorma.core.network.http.GuardedHttpTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Provider 호출 (AI-3) : 연결 테스트 · 모델 목록 · 테스트 프롬프트, 그리고 이후 AI 기능이 쓸 {@link #call}.
 * <ol>
 *   <li>Provider 설정을 DB 에서 읽는다 (캐시 없음 — 수정·비활성화·삭제가 다음 요청부터 적용된다)</li>
 *   <li>SEC-1 로 연결을 판정·기록하고 주소를 고정한 HTTP 대상을 연다 ({@link ExternalHttpClients#open}, 연결 유형 AI_HTTP). 차단이면 연결하지 않는다</li>
 *   <li>그 대상의 요청 팩토리로 Spring AI 모델 객체를 만들어 호출하고, 끝나면 대상을 닫는다 (요청 한 번 = 연결 시도 한 번)</li>
 * </ol>
 * DB 트랜잭션 밖에서 부른다 (AI 응답을 기다리는 동안 DB 연결을 잡지 않는다). 오류는 {@link Failure} 로 나누고, AI 서버의 응답 원문·주소는 화면·로그에 내지 않는다.
 */
@Slf4j
@Service
public class AiProviderClients {

    /** 프롬프트 최대 길이 (관리자 테스트) */
    static final int PROMPT_MAX = 4000;
    /** 응답에서 화면에 돌려줄 최대 길이 */
    static final int CONTENT_MAX = 20000;
    private static final Pattern STATUS = Pattern.compile("^(\\d{3}) - ");

    private final AiProviderService providers;
    private final ExternalHttpClients http;
    private final int testTimeoutSeconds;

    public AiProviderClients(AiProviderService providers, ExternalHttpClients http,
                             @Value("${lexorma.ai.test-timeout-seconds:15}") int testTimeoutSeconds) {
        this.providers = providers;
        this.http = http;
        this.testTimeoutSeconds = testTimeoutSeconds;
    }

    /**
     * 연결 테스트 : 실제 AI 서버의 응답을 받아 해석해야 성공이다 (Ollama : 모델 목록 /api/tags). 비활성화된 Provider 도 시험할 수 있다 (활성화 전 확인).
     * 짧은 응답 제한 시간(lexorma.ai.test-timeout-seconds, 기본 15초)을 쓴다.
     */
    public AiProviderDto.ConnectionTest testConnection(Long id, ExecutionContext context) {
        AiProvider provider = providers.find(id);
        AiProviderAdapter adapter = providers.adapter(provider.getType());
        long started = System.nanoTime();
        if (!adapter.supportsModelList()) {
            return new AiProviderDto.ConnectionTest(false, 0, null, null, error(Failure.UNSUPPORTED, null, null, Messages.get("이 Provider 는 연결 테스트를 지원하지 않습니다.")));
        }
        try (GuardedHttpTarget target = open(provider, context, testTimeoutSeconds)) {
            List<AiProviderDto.Model> models = adapter.models(provider, target.requestFactory());
            Boolean present = provider.getDefaultModel() == null ? null : models.stream().anyMatch(m -> sameModel(m.name(), provider.getDefaultModel()));
            return new AiProviderDto.ConnectionTest(true, millis(started), models.size(), present, null);
        } catch (IOException | RuntimeException e) {
            return new AiProviderDto.ConnectionTest(false, millis(started), null, null, classify(e, false));
        }
    }

    /** 모델 목록 (요청할 때만 조회한다). 비활성화된 Provider 도 조회할 수 있다 (기본 모델을 고르기 위해) */
    public AiProviderDto.Models models(Long id, ExecutionContext context) {
        AiProvider provider = providers.find(id);
        AiProviderAdapter adapter = providers.adapter(provider.getType());
        if (!adapter.supportsModelList()) {
            return new AiProviderDto.Models(false, List.of(), error(Failure.UNSUPPORTED, null, null, Messages.get("이 Provider 는 모델 목록 조회를 지원하지 않습니다. 모델 ID 를 직접 입력하세요.")));
        }
        try (GuardedHttpTarget target = open(provider, context, testTimeoutSeconds)) {
            return new AiProviderDto.Models(true, adapter.models(provider, target.requestFactory()), null);
        } catch (IOException | RuntimeException e) {
            return new AiProviderDto.Models(false, List.of(), classify(e, false));
        }
    }

    /** 관리자 테스트 프롬프트 : 활성화된 Provider 만 ({@link #call} 과 같은 경로). 대화 이력은 남기지 않는다 */
    public AiProviderDto.PromptResult prompt(Long id, AiProviderDto.PromptRequest request, ExecutionContext context) {
        String prompt = request == null || request.prompt() == null ? "" : request.prompt().strip();
        if (prompt.isEmpty()) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "프롬프트를 입력해주세요.");
        if (prompt.length() > PROMPT_MAX) throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "프롬프트는 {0}자까지 입력할 수 있습니다.", PROMPT_MAX);
        AiProvider provider = enabled(id);
        String model = model(provider, request.model());
        long started = System.nanoTime();
        if (model == null) {
            return new AiProviderDto.PromptResult(false, null, null, 0, null, null, error(Failure.MODEL_REQUIRED, null, null, Messages.get("기본 모델이 없습니다. 모델을 지정하세요.")));
        }
        try {
            ChatResponse response = run(provider, model, context, client -> client.prompt().user(prompt).call().chatResponse());
            if (response == null || response.getResult() == null) throw new IllegalStateException("empty chat response");
            String content = response.getResult().getOutput().getText();
            if (content != null && content.length() > CONTENT_MAX) content = content.substring(0, CONTENT_MAX);
            Usage usage = response.getMetadata().getUsage();
            String answered = response.getMetadata().getModel();
            return new AiProviderDto.PromptResult(true, answered == null || answered.isBlank() ? model : answered, content, millis(started),
                    usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(), null);
        } catch (IOException | RuntimeException e) {
            return new AiProviderDto.PromptResult(false, model, null, millis(started), null, null, classify(e, true));
        }
    }

    /**
     * 이후 AI 기능(AI-4~)이 쓰는 호출 경로 : 활성화된 Provider 의 Spring AI {@link ChatClient} 로 work 를 실행한다. ChatClient 는 work 안에서만 쓴다 (끝나면 연결을 닫는다).
     * 실패는 {@link ErrorCode#AI_PROVIDER_CALL_FAILED} (분류한 문구), 지원하지 않는 기능(스트리밍 등)은 {@link ErrorCode#AI_PROVIDER_UNSUPPORTED},
     * SEC-1 차단은 {@link ErrorCode#EXTERNAL_CONNECTION_BLOCKED}.
     *
     * @param model 비우면 Provider 의 기본 모델
     */
    public <T> T call(Long providerId, String model, ExecutionContext context, Function<ChatClient, T> work) {
        AiProvider provider = enabled(providerId);
        String chosen = model(provider, model);
        if (chosen == null) throw new CustomException(ErrorCode.AI_PROVIDER_CALL_FAILED, "기본 모델이 없습니다. 모델을 지정하세요.");
        try {
            return run(provider, chosen, context, work);
        } catch (ExternalConnectionBlockedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (e instanceof CustomException custom) throw custom;
            AiProviderDto.Error error = classify(e, true);
            throw new CustomException(error.failure() == Failure.UNSUPPORTED ? ErrorCode.AI_PROVIDER_UNSUPPORTED : ErrorCode.AI_PROVIDER_CALL_FAILED, "{0}", error.message());
        }
    }

    /**
     * 모델 호출 : SEC-1 대상은 첫 HTTP 요청 때 연다 ({@link LazyTarget}). 스트리밍처럼 SEC-1 전송으로 보낼 수 없는 요청은 HTTP 요청을 만들지 않으므로
     * 판정·연결 시도 기록(ALLOWED) 자체가 생기지 않는다 — 지원하지 않는 기능이 외부 연결로 기록되지 않는다.
     */
    private <T> T run(AiProvider provider, String model, ExecutionContext context, Function<ChatClient, T> work) throws IOException {
        AiProviderAdapter adapter = providers.adapter(provider.getType());
        try (LazyTarget target = new LazyTarget(provider, context)) {
            return work.apply(ChatClient.create(adapter.chatModel(provider, model, target.requestFactory())));
        }
    }

    /**
     * 첫 요청 때 한 번 여는 SEC-1 HTTP 대상 (호출 하나 = 대상 하나, 같은 호출 안의 다음 요청은 그 대상을 다시 쓴다). 닫으면 연 대상만 닫는다.
     * 판정·시도 기록·주소 고정은 {@link ExternalHttpClients#open} 그대로다.
     */
    private final class LazyTarget implements AutoCloseable {
        private final AiProvider provider;
        private final ExecutionContext context;
        private GuardedHttpTarget target;
        private ClientHttpRequestFactory factory;

        LazyTarget(AiProvider provider, ExecutionContext context) {
            this.provider = provider;
            this.context = context;
        }

        ClientHttpRequestFactory requestFactory() {
            return (uri, method) -> opened().createRequest(uri, method);
        }

        private synchronized ClientHttpRequestFactory opened() {
            if (factory == null) {
                target = open(provider, context, Integer.MAX_VALUE);
                factory = target.requestFactory();
            }
            return factory;
        }

        @Override
        public synchronized void close() throws IOException {
            if (target != null) target.close();
        }
    }

    private GuardedHttpTarget open(AiProvider provider, ExecutionContext context, int responseTimeoutSeconds) {
        return http.open(ConnectionType.AI_HTTP, URI.create(provider.getBaseUrl()), context, responseTimeoutSeconds);
    }

    private AiProvider enabled(Long id) {
        AiProvider provider = providers.find(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) throw new CustomException(ErrorCode.AI_PROVIDER_DISABLED);
        return provider;
    }

    private String model(AiProvider provider, String requested) {
        String model = requested == null || requested.isBlank() ? provider.getDefaultModel() : requested.strip();
        if (model != null && !providers.adapter(provider.getType()).isValidModelId(model)) {
            throw new CustomException(ErrorCode.AI_PROVIDER_INVALID, "모델 ID 형식이 올바르지 않습니다.");
        }
        return model;
    }

    /** Ollama 는 태그를 빼면 :latest 로 본다 */
    private static boolean sameModel(String listed, String model) {
        if (listed == null) return false;
        return listed.equals(model) || listed.equals(model + ":latest") || model.equals(listed + ":latest");
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /**
     * 호출 실패 분류. 감사 기록 실패(EXTERNAL_CONNECTION_AUDIT_FAILED) 같은 서버 쪽 업무 오류는 분류하지 않고 그대로 던진다.
     *
     * @param chat 모델 호출이었는지 (404 를 '모델 없음' 으로 본다. 모델 목록의 404 는 Ollama 가 아닌 서버로 본다)
     */
    static AiProviderDto.Error classify(Throwable e, boolean chat) {
        if (e instanceof ExternalConnectionBlockedException blocked) {
            return error(Failure.BLOCKED, blocked.reason(), null, blocked.reasonText());
        }
        if (e instanceof CustomException custom) throw custom;
        Integer status = status(e);
        if (status != null) {
            if (status >= 300 && status < 400) {
                return error(Failure.SERVER_ERROR, null, status, Messages.get("AI 서버가 다른 주소로 이동하라는 응답({0})을 보냈습니다. 리다이렉트는 따라가지 않으니 실제 주소를 등록하세요.", status));
            }
            if (status == 404 && chat) return error(Failure.MODEL_NOT_FOUND, null, status, Messages.get("AI 서버에 그 모델이 없습니다. 모델 목록을 확인하거나 서버에서 모델을 내려받으세요."));
            if (status == 404) return error(Failure.SERVER_ERROR, null, status, Messages.get("AI 서버가 요청한 API 를 찾지 못했습니다 (404). 주소와 Provider 유형을 확인하세요."));
            return error(Failure.SERVER_ERROR, null, status, Messages.get("AI 서버가 오류로 응답했습니다 (HTTP {0}).", status));
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnsupportedOperationException) return error(Failure.UNSUPPORTED, null, null, Messages.get("이 Provider 에서 지원하지 않는 AI 기능입니다 (스트리밍 등)."));
            if (cause instanceof IOException io && PinnedSocket.isBlocked(io)) {
                return error(Failure.BLOCKED, Reason.TARGET_CHANGED, null, Messages.get("연결 시점의 목적지가 확인한 주소와 다릅니다"));
            }
            if (cause instanceof ConnectException) return error(Failure.CONNECTION_REFUSED, null, null, Messages.get("AI 서버가 연결을 거부했습니다. 서버가 실행 중인지, 주소와 포트가 맞는지 확인하세요."));
            if (cause instanceof InterruptedIOException) return error(Failure.TIMEOUT, null, null, Messages.get("AI 서버 응답 시간이 초과되었습니다."));
            if (cause instanceof SSLException) return error(Failure.CONNECTION_FAILED, null, null, Messages.get("AI 서버와 TLS 연결을 맺지 못했습니다 (인증서·호스트명 확인)."));
        }
        if (e instanceof IOException || e.getCause() instanceof IOException) {
            return error(Failure.CONNECTION_FAILED, null, null, Messages.get("AI 서버에 연결하지 못했습니다."));
        }
        if (!(e instanceof RestClientException) && !(e instanceof IllegalStateException) && !(e instanceof NullPointerException)) {
            log.warn("AI 호출 실패 : {}", e.getClass().getName());
        }
        return error(Failure.INVALID_RESPONSE, null, null, Messages.get("AI 서버의 응답을 처리하지 못했습니다."));
    }

    /** Spring AI 의 오류 응답 처리(RetryUtils)가 만든 예외의 HTTP 상태 ("404 - 본문") */
    private static Integer status(Throwable e) {
        if (e instanceof RestClientResponseException response) return response.getStatusCode().value();
        if (e instanceof NonTransientAiException || e instanceof TransientAiException) {
            Matcher matcher = STATUS.matcher(e.getMessage() == null ? "" : e.getMessage());
            if (matcher.find()) return Integer.valueOf(matcher.group(1));
        }
        return null;
    }

    private static AiProviderDto.Error error(Failure failure, Reason reason, Integer status, String message) {
        return new AiProviderDto.Error(failure, reason, status, message);
    }
}
