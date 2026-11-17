package com.lexorma.ai.provider.service;

import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.entity.AiProvider;
import com.lexorma.ai.provider.adapter.AiProviderAdapter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * AI 호출 하나 (AI-4) : {@link AiProviderClients#callDefaultWith} 의 work 가 받는다. 같은 SEC-1 대상(연결 시도 하나)으로
 * 모델 호출({@link #client()})과 모델 정보 조회({@link #modelInfo()})를 한다. work 밖에서 쓰지 않는다.
 */
public final class AiCall {

    private final ChatClient client;
    private final String model;
    private final AiProvider provider;
    private final AiProviderAdapter adapter;
    private final ClientHttpRequestFactory requestFactory;
    private AiProviderDto.ModelInfo modelInfo;

    AiCall(ChatClient client, String model, AiProvider provider, AiProviderAdapter adapter, ClientHttpRequestFactory requestFactory) {
        this.client = client;
        this.model = model;
        this.provider = provider;
        this.adapter = adapter;
        this.requestFactory = requestFactory;
    }

    public ChatClient client() {
        return client;
    }

    /** 부르는 모델 (Provider 의 기본 모델) */
    public String model() {
        return model;
    }

    /**
     * 모델 정보 (Tool Calling 지원 여부·컨텍스트 길이). 호출 안에서 한 번만 묻는다. 서버가 알려 주지 않으면 알 수 없음(null)이다.
     * SEC-1 차단·연결 실패는 그대로 던진다 (알 수 없음으로 바꾸지 않는다)
     */
    public synchronized AiProviderDto.ModelInfo modelInfo() {
        if (modelInfo == null) modelInfo = adapter.modelInfo(provider, model, requestFactory);
        return modelInfo;
    }

    /** 요청 하나의 모델 옵션 : 컨텍스트 길이·응답 최대 토큰 (Provider 의 공식 옵션) */
    public ToolCallingChatOptions options(Integer contextTokens, Integer maxOutputTokens) {
        return adapter.chatOptions(model, contextTokens, maxOutputTokens);
    }

    /** 응답의 토큰 사용량 (AI 서버가 알려 준 값만, 없으면 null) */
    public AiProviderDto.TokenUsage usage(ChatResponse response) {
        return adapter.usage(response);
    }

    /** 이 실패가 "모델이 Tool Calling 을 지원하지 않는다" 는 AI 서버의 명시적 거절인지 (일반 400·연결 실패·SEC-1 차단은 아니다) */
    public boolean isToolCallingUnsupported(Throwable error) {
        return adapter.isToolCallingUnsupported(error);
    }
}
