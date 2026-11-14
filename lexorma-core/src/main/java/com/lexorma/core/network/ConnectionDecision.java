package com.lexorma.core.network;

import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;

import java.net.InetAddress;
import java.util.List;

/**
 * 외부 연결 판정 결과 (SEC-1). 허용이면 {@link #pin()} 으로 실제 연결을 고정한다 (Boolean 만 넘기면 연결 단계에서 검증 정보를 잃는다).
 * 비밀번호·API Key·인증 헤더는 담지 않는다.
 *
 * @param ruleId         적용한 규칙 (차단이면 null)
 * @param addresses      판정 때 풀어 검증한 주소
 * @param reason         차단 사유 (허용이면 null)
 * @param policyRevision 판정 때의 정책 변경 식별자 (마지막 규칙 변경 이력 번호)
 * @param requestId      이 연결 시도의 식별자 (감사 기록과 같다)
 * @param attemptId      연결 시도 기록 ID (기록하지 않은 판정이면 null)
 */
public record ConnectionDecision(ConnectionType connectionType, String host, int port, boolean allowed, Long ruleId, List<InetAddress> addresses, Reason reason,
                                 long policyRevision, String requestId, Long attemptId, ConnectionPin pin) {

    public ConnectionDecision withAttempt(Long id) {
        return new ConnectionDecision(connectionType, host, port, allowed, ruleId, addresses, reason, policyRevision, requestId, id, pin);
    }

    public String addressText() {
        return String.join(",", addresses.stream().map(NetworkAddresses::text).toList());
    }
}
