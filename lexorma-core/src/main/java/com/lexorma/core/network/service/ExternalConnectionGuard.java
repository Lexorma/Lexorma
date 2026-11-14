package com.lexorma.core.network.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.ConnectionDecision;
import com.lexorma.core.network.ConnectionPin;
import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.HostResolver;
import com.lexorma.core.network.NetworkAddresses;
import com.lexorma.core.network.NetworkAddresses.Cidr;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Result;
import com.lexorma.core.network.domain.entity.ExternalConnectionRule;
import com.lexorma.core.network.repository.ExternalConnectionRuleHistoryRepository;
import com.lexorma.core.network.repository.ExternalConnectionRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 외부 연결 정책 판정 (SEC-1, docs/external-connection-security-sec1.md). JDBC(원본 DB)·AI HTTP 가 같이 쓴다.
 * <ol>
 *   <li>호스트 형식·메타데이터 이름·숫자 주소의 절대 차단</li>
 *   <li>그 연결 유형의 활성 규칙 중 포트가 같고, 호스트를 정했다면 호스트도 같은 것 (없으면 기본 차단)</li>
 *   <li>이름을 풀어(A·AAAA) 모든 주소가 절대 차단이 아니고 운영자 상한 안이며, <b>한 규칙</b>의 대역이 모든 주소를 담아야 허용 — 여러 규칙의 조건을 섞지 않는다.
 *       응답 일부만 허용되면 차단한다 (다른 주소로 다시 시도하지 않는다). 이름 풀기 실패·판정 중 오류도 차단</li>
 * </ol>
 * 허용이면 {@link ConnectionPin} 으로 실제 소켓을 판정한 주소·규칙에 고정한다 ({@link com.lexorma.core.network.PinnedSocket}).
 * 규칙은 판정마다 새 트랜잭션으로 읽는다 (캐시 없음) : 관리자가 바꾼 규칙은 다음 연결부터 바로 적용된다.
 */
@Slf4j
@Service
public class ExternalConnectionGuard {

    private final ExternalConnectionRuleRepository rules;
    private final ExternalConnectionRuleHistoryRepository changes;
    private final ExternalConnectionAuditWriter writer;
    private final HostResolver resolver;
    private final TransactionTemplate fresh;

    public ExternalConnectionGuard(ExternalConnectionRuleRepository rules, ExternalConnectionRuleHistoryRepository changes, ExternalConnectionAuditWriter writer,
                                   ObjectProvider<HostResolver> resolver, PlatformTransactionManager transactionManager) {
        this.rules = rules;
        this.changes = changes;
        this.writer = writer;
        this.resolver = resolver.getIfAvailable(() -> HostResolver.SYSTEM);
        this.fresh = new TransactionTemplate(transactionManager);
        this.fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.fresh.setReadOnly(true);
    }

    /** 판정만 한다 (기록하지 않는다 : 관리자 화면의 안내용) */
    public ConnectionDecision evaluate(ConnectionType type, String host, int port, List<Cidr> ceiling) {
        String requestId = UUID.randomUUID().toString();
        String normalized = NetworkAddresses.normalizeHost(host);
        try {
            return fresh.execute(status -> decide(type, normalized, port, ceiling == null ? List.of() : ceiling, requestId));
        } catch (RuntimeException e) {
            log.warn("외부 연결 판정 실패 : type={}, port={}, {}", type, port, e.getClass().getSimpleName());
            return denied(type, normalized, port, Reason.POLICY_ERROR, List.of(), 0, requestId);
        }
    }

    /** 판정하고 연결 시도를 남긴다. 기록하지 못하면 연결하지 않는다 (예외) */
    public ConnectionDecision authorize(ConnectionType type, String host, int port, List<Cidr> ceiling, ExecutionContext context) {
        ConnectionDecision decision = evaluate(type, host, port, ceiling);
        try {
            return decision.withAttempt(writer.begin(decision, context));
        } catch (RuntimeException e) {
            log.error("외부 연결 시도 기록 실패 : 연결하지 않는다 ({})", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.EXTERNAL_CONNECTION_AUDIT_FAILED);
        }
    }

    /** 허용된 연결의 결과. 기록하지 못하면 예외 — 부른 쪽은 연 연결을 닫는다 */
    public void finish(ConnectionDecision decision, Result result, Reason reason) {
        if (decision == null || decision.attemptId() == null) return;
        try {
            writer.finish(decision.attemptId(), result, reason);
        } catch (RuntimeException e) {
            log.error("외부 연결 결과 기록 실패 ({})", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.EXTERNAL_CONNECTION_AUDIT_FAILED);
        }
    }

    private ConnectionDecision decide(ConnectionType type, String host, int port, List<Cidr> ceiling, String requestId) {
        long revision = changes.latestId();
        if (!NetworkAddresses.isValidHost(host) || port < 1 || port > 65535) return denied(type, host, port, Reason.INVALID_HOST, List.of(), revision, requestId);
        if (NetworkAddresses.isBlockedHostName(host)) return denied(type, host, port, Reason.BLOCKED_HOST, List.of(), revision, requestId);
        InetAddress literal = NetworkAddresses.literal(host);
        if (literal != null && NetworkAddresses.isBlocked(literal)) return denied(type, host, port, Reason.BLOCKED_ADDRESS, List.of(literal), revision, requestId);

        List<ExternalConnectionRule> candidates = rules.findAllByConnectionTypeAndEnabledTrueOrderByIdAsc(type);
        List<ExternalConnectionRule> byPort = candidates.stream().filter(rule -> rule.getPort() == port).toList();
        if (byPort.isEmpty()) {
            boolean hostKnown = candidates.stream().anyMatch(rule -> host.equals(NetworkAddresses.normalizeHost(rule.getHost())));
            return denied(type, host, port, hostKnown ? Reason.PORT_NOT_ALLOWED : Reason.NO_RULE, List.of(), revision, requestId);
        }
        List<ExternalConnectionRule> byHost = byPort.stream().filter(rule -> rule.getHost() == null || host.equals(NetworkAddresses.normalizeHost(rule.getHost()))).toList();
        if (byHost.isEmpty()) return denied(type, host, port, Reason.HOST_NOT_ALLOWED, List.of(), revision, requestId);

        List<InetAddress> addresses;
        if (literal != null) {
            addresses = List.of(literal);
        } else {
            try {
                addresses = Arrays.stream(resolver.resolve(host)).distinct().toList();
            } catch (UnknownHostException | SecurityException e) {
                return denied(type, host, port, Reason.DNS_FAILED, List.of(), revision, requestId);
            }
            if (addresses.isEmpty()) return denied(type, host, port, Reason.DNS_FAILED, List.of(), revision, requestId);
        }
        if (addresses.stream().anyMatch(NetworkAddresses::isBlocked)) return denied(type, host, port, Reason.BLOCKED_ADDRESS, addresses, revision, requestId);
        if (!ceiling.isEmpty() && addresses.stream().anyMatch(a -> NetworkAddresses.isPrivateOrLoopback(a) && ceiling.stream().noneMatch(n -> n.contains(a)))) {
            return denied(type, host, port, Reason.OPERATOR_LIMIT, addresses, revision, requestId);
        }
        List<InetAddress> resolved = addresses;
        ExternalConnectionRule rule = byHost.stream().filter(r -> resolved.stream().allMatch(cidr(r)::contains)).min(Comparator.comparing(ExternalConnectionRule::getId))
                .orElse(null);
        if (rule == null) {
            boolean partly = resolved.size() > 1 && byHost.stream().anyMatch(r -> resolved.stream().anyMatch(cidr(r)::contains));
            return denied(type, host, port, partly ? Reason.MIXED_ADDRESSES : Reason.ADDRESS_NOT_ALLOWED, resolved, revision, requestId);
        }
        ConnectionPin pin = new ConnectionPin(host, port, resolved, cidr(rule), ceiling);
        return new ConnectionDecision(type, host, port, true, rule.getId(), resolved, null, revision, requestId, null, pin);
    }

    private static Cidr cidr(ExternalConnectionRule rule) {
        return Cidr.parse(rule.getAllowedAddress());
    }

    private static ConnectionDecision denied(ConnectionType type, String host, int port, Reason reason, List<InetAddress> addresses, long revision, String requestId) {
        return new ConnectionDecision(type, host == null ? "" : host, port, false, null, addresses, reason, revision, requestId, null, null);
    }

    /** 차단 사유 (오류 문구) : IPv4·IPv6 둘 다로 풀려 막혔으면 그 원인과 숫자 주소 사용을 안내한다 */
    public static String reasonText(ConnectionDecision decision) {
        if (decision.reason() == Reason.MIXED_ADDRESSES && decision.addresses().stream().anyMatch(a -> a instanceof Inet6Address)
                && decision.addresses().stream().anyMatch(a -> !(a instanceof Inet6Address))) {
            return Messages.get("호스트가 IPv4와 IPv6 주소로 동시에 해석되어 현재 허용 규칙과 일치하지 않습니다. localhost 대신 127.0.0.1 또는 ::1과 같은 명시적인 IP 주소를 사용하세요.");
        }
        return reasonText(decision.reason());
    }

    /** 차단 사유 (화면·오류 문구) */
    public static String reasonText(Reason reason) {
        if (reason == null) return "";
        return switch (reason) {
            case NO_RULE -> Messages.get("관리자가 허용한 규칙이 없습니다");
            case PORT_NOT_ALLOWED -> Messages.get("허용하지 않은 포트입니다");
            case HOST_NOT_ALLOWED -> Messages.get("허용하지 않은 호스트입니다");
            case ADDRESS_NOT_ALLOWED -> Messages.get("허용 대역 밖의 주소입니다");
            case MIXED_ADDRESSES -> Messages.get("호스트가 여러 주소로 해석되었지만 그 주소를 모두 담는 허용 규칙이 없습니다. localhost 처럼 IPv4·IPv6 로 함께 해석되는 이름이면 127.0.0.1 또는 ::1 같은 명시적인 IP 주소를 사용하세요.");
            case BLOCKED_HOST -> Messages.get("클라우드 메타데이터 이름은 항상 차단됩니다");
            case BLOCKED_ADDRESS -> Messages.get("항상 차단되는 주소입니다 (링크 로컬·메타데이터·멀티캐스트·미지정 등)");
            case INVALID_HOST -> Messages.get("호스트 또는 포트 형식이 올바르지 않습니다");
            case DNS_FAILED -> Messages.get("호스트 이름을 풀지 못했습니다");
            case OPERATOR_LIMIT -> Messages.get("운영자가 정한 범위 밖의 사설·루프백 주소입니다");
            case POLICY_ERROR -> Messages.get("정책을 확인하지 못했습니다");
            case TARGET_CHANGED -> Messages.get("연결 시점의 목적지가 확인한 주소와 다릅니다");
            case CONNECT_FAILED -> Messages.get("연결하지 못했습니다");
        };
    }
}
