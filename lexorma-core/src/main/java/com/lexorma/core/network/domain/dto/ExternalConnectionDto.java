package com.lexorma.core.network.domain.dto;

import com.lexorma.core.network.ConnectionType;

import java.time.LocalDateTime;
import java.util.List;

/** 외부 연결 보안 (SEC-1) : 허용 규칙·변경 이력·연결 시도 */
public final class ExternalConnectionDto {

    private ExternalConnectionDto() {
    }

    /**
     * 차단·실패 사유. 판정(정책) : NO_RULE · PORT_NOT_ALLOWED · HOST_NOT_ALLOWED · ADDRESS_NOT_ALLOWED · MIXED_ADDRESSES(DNS 응답 일부만 허용) · BLOCKED_HOST(메타데이터 이름) ·
     * BLOCKED_ADDRESS(절대 차단) · INVALID_HOST · DNS_FAILED · OPERATOR_LIMIT(운영자 상한) · POLICY_ERROR(판정 중 오류). 연결 : TARGET_CHANGED(연결 시점 목적지가 판정과 다름) · CONNECT_FAILED
     */
    public enum Reason {
        NO_RULE, PORT_NOT_ALLOWED, HOST_NOT_ALLOWED, ADDRESS_NOT_ALLOWED, MIXED_ADDRESSES, BLOCKED_HOST, BLOCKED_ADDRESS, INVALID_HOST, DNS_FAILED, OPERATOR_LIMIT,
        POLICY_ERROR, TARGET_CHANGED, CONNECT_FAILED
    }

    /** 연결 시도 결과 : 판정(ALLOWED·DENIED) → 허용된 것은 CONNECTED·FAILED·BLOCKED(연결 시점 차단) 로 고친다 */
    public enum Result { ALLOWED, DENIED, CONNECTED, FAILED, BLOCKED }

    public enum ChangeType { CREATE, UPDATE, DELETE, ENABLE, DISABLE }

    public record Rule(Long id, ConnectionType connectionType, String host, String cidr, Integer port, boolean enabled, String description, String createdBy,
                       LocalDateTime createdAt, String updatedBy, LocalDateTime updatedAt) {
    }

    /**
     * @param host 비우면 대역만 본다. 숫자 주소면 대역 안이어야 한다
     * @param cidr 필수. 0.0.0.0/0 처럼 너무 넓은 대역(IPv4 /8 미만, IPv6 /16 미만)·절대 차단 대역 안은 받지 않는다
     */
    public record RuleRequest(String connectionType, String host, String cidr, Integer port, Boolean enabled, String description) {
    }

    public record RuleChange(Long id, Long ruleId, ChangeType changeType, String before, String after, String requestId, String userId, LocalDateTime changedAt) {
    }

    public record Attempt(Long id, ConnectionType connectionType, String host, Integer port, String addresses, Long ruleId, Result result, Reason reason,
                          String executionType, String requestId, String userId, LocalDateTime attemptedAt, LocalDateTime updatedAt) {
    }

    public record Page<T>(List<T> rows, long total) {
    }

    /** 등록된 연결 중 지금 허용 규칙으로 열리지 않는 것 (기본 차단 전환 안내) */
    public record Unmatched(ConnectionType connectionType, String host, Integer port, String label, Reason reason) {
    }

    /** 화면 안내 : 절대 차단 대역 */
    public record Settings(List<String> blockedNetworks, List<String> blockedHosts) {
    }
}
