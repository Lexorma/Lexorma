package com.lexorma.core.network;

/**
 * 외부 연결 유형 (SEC-1). 규칙은 유형마다 따로 둔다 : JDBC 로 허용한 서버가 AI HTTP 에서 저절로 허용되지 않는다.
 * <ul>
 *   <li>JDBC : 원본 DB 메타데이터 조회 (lexorma-source)</li>
 *   <li>AI_HTTP : AI Endpoint (Ollama·OpenAI 호환 서버, AI-3). 전송은 {@link com.lexorma.core.network.http.ExternalHttpClients} 만 쓴다</li>
 * </ul>
 */
public enum ConnectionType { JDBC, AI_HTTP }
