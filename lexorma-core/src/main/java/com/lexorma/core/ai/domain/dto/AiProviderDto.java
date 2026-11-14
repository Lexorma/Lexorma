package com.lexorma.core.ai.domain.dto;

import com.lexorma.core.ai.AiProviderType;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;

import java.time.LocalDateTime;
import java.util.List;

/** AI Provider (AI-3) : 설정·연결 테스트·모델 목록·테스트 프롬프트 */
public final class AiProviderDto {

    private AiProviderDto() {
    }

    public record Provider(Long id, String name, AiProviderType type, String baseUrl, String defaultModel, boolean enabled, String description,
                           String createdBy, LocalDateTime createdAt, String updatedBy, LocalDateTime updatedAt) {
    }

    /**
     * @param type         지금은 OLLAMA 만
     * @param baseUrl      http(s)://호스트[:포트][/경로] — 계정 정보·쿼리·프래그먼트 없음
     * @param defaultModel 비워도 된다 (모델 목록을 본 뒤 정할 수 있다)
     */
    public record ProviderRequest(String name, String type, String baseUrl, String defaultModel, Boolean enabled, String description) {
    }

    /** Provider 유형 안내 (화면의 선택지·기본 주소) */
    public record TypeInfo(AiProviderType type, String defaultBaseUrl, boolean modelListSupported) {
    }

    /**
     * AI 호출 실패 분류. BLOCKED : SEC-1 이 연결을 열지 않음 (사유는 {@code blockReason} — 허용 규칙 없음 · 절대 차단 · 이름 풀기 실패 등) ·
     * CONNECTION_REFUSED · TIMEOUT · CONNECTION_FAILED(그 밖의 연결 오류) · MODEL_NOT_FOUND · MODEL_REQUIRED(정한 모델이 없음) ·
     * SERVER_ERROR(AI 서버 오류 응답) · INVALID_RESPONSE(응답을 해석하지 못함) · UNSUPPORTED(이 Provider 가 지원하지 않는 기능)
     */
    public enum Failure {
        BLOCKED, CONNECTION_REFUSED, TIMEOUT, CONNECTION_FAILED, MODEL_NOT_FOUND, MODEL_REQUIRED, SERVER_ERROR, INVALID_RESPONSE, UNSUPPORTED
    }

    /** 실패 내용 : 화면 문구. 서버 응답 원문·인증 정보·내부 주소는 담지 않는다 */
    public record Error(Failure failure, Reason blockReason, Integer status, String message) {
    }

    /** AI 서버가 알려 준 모델 하나 (Ollama : /api/tags) */
    public record Model(String name, Long size, String family, String parameterSize, String quantization, LocalDateTime modifiedAt) {
    }

    /**
     * 연결 테스트 : 실제 AI 서버의 응답(모델 목록)을 받아 해석했을 때만 성공이다.
     *
     * @param modelCount          서버가 알려 준 모델 수 (모델 목록을 지원하지 않으면 null)
     * @param defaultModelPresent 기본 모델이 그 목록에 있는지 (기본 모델이 없거나 목록이 없으면 null)
     */
    public record ConnectionTest(boolean success, long elapsedMillis, Integer modelCount, Boolean defaultModelPresent, Error error) {
    }

    public record Models(boolean success, List<Model> models, Error error) {
    }

    /** @param model 비우면 Provider 의 기본 모델 */
    public record PromptRequest(String prompt, String model) {
    }

    /**
     * 시스템 기본 Provider 상태. READY : 쓸 수 있다 · NOT_CONFIGURED : 미지정 · DISABLED : 지정됐지만 비활성 (다시 활성화하면 그대로 쓴다) ·
     * MODEL_MISSING : 지정됐지만 기본 모델이 없다 · UNSUPPORTED : 지원하지 않는 유형. 연결 가능 여부(SEC-1·서버 상태)는 보지 않는다 (호출 때 확인)
     */
    public enum DefaultStatus { READY, NOT_CONFIGURED, DISABLED, MODEL_MISSING, UNSUPPORTED }

    /** 시스템 기본 Provider (관리자 화면) : provider 는 미지정이면 null */
    public record DefaultProvider(DefaultStatus status, Provider provider, String updatedBy, LocalDateTime updatedAt) {
    }

    /** 기본 Provider 지정·변경 */
    public record DefaultProviderRequest(Long providerId) {
    }

    /** 업무 AI 기능이 쓸 기본 Provider (검증한 값) : 모델은 그 Provider 의 기본 모델 */
    public record DefaultTarget(Long providerId, AiProviderType type, String baseUrl, String model, boolean enabled) {
    }

    public record PromptResult(boolean success, String model, String content, long elapsedMillis, Integer promptTokens, Integer completionTokens, Error error) {
    }
}
