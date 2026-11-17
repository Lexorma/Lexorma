package com.lexorma.ai.provider.adapter;

import com.lexorma.ai.provider.AiProviderType;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.entity.AiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.util.List;

/**
 * Provider 유형별로 Spring AI 모델 객체를 만든다 (AI-3). 모델 호출·프롬프트·Tool Calling 은 Spring AI(ChatModel·ChatClient)를 그대로 쓰고 여기서 다시 만들지 않는다.
 * <ul>
 *   <li>HTTP 는 넘겨받은 요청 팩토리({@link com.lexorma.core.network.http.GuardedHttpTarget#requestFactory()} : SEC-1 판정·주소 고정)로만 보낸다.
 *       Spring AI 의 기본 HTTP 클라이언트(RestClient·WebClient 기본 커넥터)로 나가는 경로를 남기지 않는다</li>
 *   <li>요청마다 새로 만들고 들고 있지 않는다 (재시작 없이 설정 변경이 적용된다)</li>
 *   <li>Spring AI 의 자동 재시도는 쓰지 않는다 (SEC-1 : 자동 재시도 없음. 연결 시도 하나 = 감사 기록 하나)</li>
 * </ul>
 * 새 유형(11단계 OPENAI_COMPATIBLE 등)은 이 인터페이스의 구현을 하나 더한다.
 */
public interface AiProviderAdapter {

    AiProviderType type();

    /** 화면 안내용 기본 주소 (자동으로 등록·연결하지 않는다) */
    String defaultBaseUrl();

    /** 연결 테스트·호출에 앞서 Provider 의 모델 ID 형식을 본다 */
    boolean isValidModelId(String model);

    /** model 을 기본 모델로 쓰는 ChatModel. 만들 때 서버에 연결하지 않는다 (모델 내려받기 등 없음) */
    ChatModel chatModel(AiProvider provider, String model, ClientHttpRequestFactory requestFactory);

    /** 모델 목록을 조회할 수 있는지. 모든 Provider 가 지원한다고 가정하지 않는다 (지원하지 않으면 모델 ID 를 직접 입력한다) */
    default boolean supportsModelList() {
        return false;
    }

    /** 서버에 있는 모델 목록. {@link #supportsModelList()} 가 false 면 부르지 않는다 */
    default List<AiProviderDto.Model> models(AiProvider provider, ClientHttpRequestFactory requestFactory) {
        throw new UnsupportedOperationException("model list");
    }

    /**
     * 호출 실패가 "이 모델은 Tool Calling 을 지원하지 않는다" 는 AI 서버의 거절인지 (AI-4). 서버마다 알리는 방식이 달라 유형별로 판별한다.
     * Tool 호출 요청·응답 자체는 Spring AI 가 처리하고 여기서 해석하지 않는다
     */
    default boolean isToolCallingUnsupported(Throwable error) {
        return false;
    }

    /**
     * 모델 정보 (Tool Calling 지원 여부·컨텍스트 길이, AI-4). 서버가 알려 주지 않는 유형은 {@link AiProviderDto.ModelInfo#UNKNOWN}.
     * 같은 요청 팩토리(SEC-1)로 보낸다
     */
    default AiProviderDto.ModelInfo modelInfo(AiProvider provider, String model, ClientHttpRequestFactory requestFactory) {
        return AiProviderDto.ModelInfo.UNKNOWN;
    }

    /**
     * 요청 하나의 모델 옵션 (AI-4 토큰 예산) : 컨텍스트 길이와 응답 최대 토큰을 그 유형의 공식 옵션으로 정한다. null 은 정하지 않는다(서버 기본값).
     * Tool 을 함께 넘길 수 있도록 {@link ToolCallingChatOptions} 를 돌려준다
     */
    /** 응답에 실린 토큰 사용량 (서버가 알려 준 값만. 없으면 null — Spring AI 의 Usage 는 없는 값을 0 으로 채우므로 유형별로 판별한다) */
    default AiProviderDto.TokenUsage usage(ChatResponse response) {
        return AiProviderDto.TokenUsage.UNKNOWN;
    }

    default ToolCallingChatOptions chatOptions(String model, Integer contextTokens, Integer maxOutputTokens) {
        return ToolCallingChatOptions.builder().model(model).maxTokens(maxOutputTokens).build();
    }
}
