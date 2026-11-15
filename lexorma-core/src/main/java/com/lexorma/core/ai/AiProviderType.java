package com.lexorma.core.ai;

/**
 * AI Provider 유형 (AI-3). DB 에는 이름(문자열)으로 저장한다 — 유형을 더해도 테이블을 바꾸지 않는다.
 * 지금은 OLLAMA 만 있다. OpenAI 호환 API(InfraMesh·vLLM 등)는 11단계에서 OPENAI_COMPATIBLE 로 더하고, 같은 SEC-1 HTTP 전송을 쓴다.
 */
public enum AiProviderType {
    OLLAMA
}
