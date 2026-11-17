package com.lexorma.data.mcp.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 내장 MCP 설정·토큰 (DEC-31). 토큰 원문은 {@link Issued} 에만, 발급 응답 한 번 */
public final class McpDto {

    private McpDto() {
    }

    /**
     * @param serverEnabled  운영 설정 lexorma.mcp.enabled (꺼져 있으면 /mcp 가 없는 것처럼 404)
     * @param projectEnabled 이 프로젝트의 MCP 사용 여부
     * @param canManage      지금 사용자가 설정·토큰을 관리할 수 있는지 (프로젝트 관리자·ADMIN)
     * @param endpointPath   MCP 엔드포인트 경로 (Streamable HTTP, 같은 서버)
     * @param tokens         토큰 목록 (관리자에게만, 원문·해시 없음)
     */
    public record Settings(boolean serverEnabled, boolean projectEnabled, boolean canManage, String endpointPath, int maxDays, List<Token> tokens, List<Call> recentCalls) {
    }

    public record SettingRequest(Boolean enabled) {
    }

    /** @param status ACTIVE · EXPIRED · REVOKED */
    public record Token(Long id, String name, String prefix, String status, LocalDateTime expiresAt, LocalDateTime revokedAt, LocalDateTime lastUsedAt,
                        String issuedBy, LocalDateTime issuedAt) {
    }

    /** @param expiresAt 만료 일시 (필수, 지금부터 최대 {@code maxDays} 일) */
    public record IssueRequest(String name, LocalDateTime expiresAt) {
    }

    /** 발급 결과 : token 은 이 응답에서만 볼 수 있다 (저장하지 않는다) */
    public record Issued(Token info, String token) {
        @Override
        public String toString() {
            return "Issued[id=" + (info == null ? null : info.id()) + "]";
        }
    }

    public record Call(String toolName, String result, LocalDateTime calledAt) {
    }
}
