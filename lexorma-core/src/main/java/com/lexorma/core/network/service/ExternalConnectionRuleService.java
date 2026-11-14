package com.lexorma.core.network.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.network.ConnectionDecision;
import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.ExternalConnectionTargets;
import com.lexorma.core.network.NetworkAddresses;
import com.lexorma.core.network.NetworkAddresses.Cidr;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.ChangeType;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Result;
import com.lexorma.core.network.domain.entity.ExternalConnectionAttempt;
import com.lexorma.core.network.domain.entity.ExternalConnectionRule;
import com.lexorma.core.network.domain.entity.ExternalConnectionRuleHistory;
import com.lexorma.core.network.repository.ExternalConnectionAttemptRepository;
import com.lexorma.core.network.repository.ExternalConnectionRuleHistoryRepository;
import com.lexorma.core.network.repository.ExternalConnectionRuleRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 외부 연결 허용 규칙 관리 (SEC-1) : 시스템 관리자(ADMIN, 컨트롤러가 확인) 전용. 규칙 변경과 변경 이력은 한 트랜잭션이다.
 * 서버 검증 : 연결 유형, 호스트 형식(와일드카드 없음), CIDR 형식·최소 폭(IPv4 /8, IPv6 /16 — 0.0.0.0/0·::/0 금지)·절대 차단 대역 안 금지,
 * 숫자 호스트는 대역 안·절대 차단 아님, 포트 1~65535, 같은 규칙 중복 금지.
 */
@Service
@RequiredArgsConstructor
public class ExternalConnectionRuleService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PAGE_MAX = 100;
    private static final int TARGET_MAX = 200;

    private final ExternalConnectionRuleRepository rules;
    private final ExternalConnectionRuleHistoryRepository changes;
    private final ExternalConnectionAttemptRepository attempts;
    private final ExternalConnectionGuard guard;
    private final ObjectProvider<ExternalConnectionTargets> targets;

    @Transactional(readOnly = true)
    public List<ExternalConnectionDto.Rule> list(String type, String keyword) {
        ConnectionType filter = type == null || type.isBlank() ? null : type(type);
        String word = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        return rules.findAllByOrderByIdAsc().stream().filter(rule -> filter == null || rule.getConnectionType() == filter)
                .filter(rule -> word == null || (rule.getHost() != null && rule.getHost().contains(word)) || rule.getAllowedAddress().contains(word)
                        || (rule.getDescription() != null && rule.getDescription().toLowerCase(Locale.ROOT).contains(word)))
                .map(ExternalConnectionRuleService::dto).toList();
    }

    @Transactional
    public ExternalConnectionDto.Rule create(ExternalConnectionDto.RuleRequest request) {
        ExternalConnectionRule rule = new ExternalConnectionRule();
        apply(rule, validated(request, null));
        rules.saveAndFlush(rule);
        record(rule.getId(), ChangeType.CREATE, null, state(rule));
        return dto(rule);
    }

    @Transactional
    public ExternalConnectionDto.Rule update(Long id, ExternalConnectionDto.RuleRequest request) {
        ExternalConnectionRule rule = rules.findById(id).orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_RULE_NOT_FOUND));
        Map<String, Object> before = state(rule);
        Validated next = validated(request, id);
        apply(rule, next);
        Map<String, Object> after = state(rule);
        if (before.equals(after)) return dto(rule);
        rules.saveAndFlush(rule);
        Map<String, Object> withoutEnabled = new LinkedHashMap<>(before);
        withoutEnabled.put("enabled", after.get("enabled"));
        ChangeType type = withoutEnabled.equals(after) ? (Boolean.TRUE.equals(after.get("enabled")) ? ChangeType.ENABLE : ChangeType.DISABLE) : ChangeType.UPDATE;
        record(rule.getId(), type, before, after);
        return dto(rule);
    }

    @Transactional
    public void delete(Long id) {
        ExternalConnectionRule rule = rules.findById(id).orElseThrow(() -> new CustomException(ErrorCode.CONNECTION_RULE_NOT_FOUND));
        Map<String, Object> before = state(rule);
        rules.delete(rule);
        rules.flush();
        record(id, ChangeType.DELETE, before, null);
    }

    @Transactional(readOnly = true)
    public ExternalConnectionDto.Page<ExternalConnectionDto.RuleChange> changes(int page, int size) {
        var found = changes.findAllByOrderByIdDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), PAGE_MAX)));
        return new ExternalConnectionDto.Page<>(found.getContent().stream().map(h -> new ExternalConnectionDto.RuleChange(h.getId(), h.getRuleId(),
                ChangeType.valueOf(h.getChangeType()), h.getBefore(), h.getAfter(), h.getRequestId(), h.audit().createdBy(), h.audit().created())).toList(), found.getTotalElements());
    }

    /** 연결 시도 : 유형·결과(ALLOWED 는 허용된 모든 결과, DENIED 는 차단·연결 시점 차단)·목적지·수행자·기간 */
    @Transactional(readOnly = true)
    public ExternalConnectionDto.Page<ExternalConnectionDto.Attempt> attempts(String type, String result, String keyword, String user, LocalDate from, LocalDate to,
                                                                             int page, int size) {
        ConnectionType filter = type == null || type.isBlank() ? null : type(type);
        Specification<ExternalConnectionAttempt> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (filter != null) where.add(cb.equal(root.get("connectionType"), filter));
            if ("ALLOWED".equals(result)) where.add(root.get("result").in(Result.ALLOWED.name(), Result.CONNECTED.name(), Result.FAILED.name()));
            else if ("DENIED".equals(result)) where.add(root.get("result").in(Result.DENIED.name(), Result.BLOCKED.name()));
            else if (result != null && !result.isBlank()) where.add(cb.equal(root.get("result"), result));
            if (keyword != null && !keyword.isBlank()) where.add(cb.like(cb.lower(root.get("host")), "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%"));
            if (user != null && !user.isBlank()) where.add(cb.equal(root.get("createdBy"), user.trim()));
            if (from != null) where.add(cb.greaterThanOrEqualTo(root.get("created"), from.atStartOfDay()));
            if (to != null) where.add(cb.lessThan(root.get("created"), to.plusDays(1).atStartOfDay()));
            return cb.and(where.toArray(Predicate[]::new));
        };
        var found = attempts.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), PAGE_MAX), Sort.by(Sort.Direction.DESC, "id")));
        return new ExternalConnectionDto.Page<>(found.getContent().stream().map(a -> new ExternalConnectionDto.Attempt(a.getId(), a.getConnectionType(), a.getHost(),
                a.getPort(), a.getAddresses(), a.getRuleId(), Result.valueOf(a.getResult()), a.getReason() == null ? null : Reason.valueOf(a.getReason()), a.getExecutionType(),
                a.getRequestId(), a.audit().createdBy(), a.audit().created(), a.audit().updated())).toList(), found.getTotalElements());
    }

    /** 등록된 연결 중 지금 규칙으로 열리지 않는 것 (이름을 풀어 판정한다. 기록하지 않는다) */
    public List<ExternalConnectionDto.Unmatched> unmatched() {
        Map<String, ExternalConnectionTargets.Target> distinct = new LinkedHashMap<>();
        targets.orderedStream().flatMap(provider -> provider.targets().stream()).limit(TARGET_MAX)
                .forEach(t -> distinct.putIfAbsent(t.connectionType() + "|" + NetworkAddresses.normalizeHost(t.host()) + "|" + t.port(), t));
        List<ExternalConnectionDto.Unmatched> result = new ArrayList<>();
        for (ExternalConnectionTargets.Target target : distinct.values()) {
            ConnectionDecision decision = guard.evaluate(target.connectionType(), target.host(), target.port(), List.of());
            if (!decision.allowed()) result.add(new ExternalConnectionDto.Unmatched(target.connectionType(), target.host(), target.port(), target.label(), decision.reason()));
        }
        return result;
    }

    public ExternalConnectionDto.Settings settings() {
        return new ExternalConnectionDto.Settings(NetworkAddresses.blockedNetworks().stream().map(Cidr::toString).toList(),
                List.of("metadata", "metadata.google.internal", "metadata.goog", "instance-data", "instance-data.ec2.internal"));
    }

    // ------------------------------------------------------------------ 검증

    private record Validated(ConnectionType type, String host, Cidr cidr, int port, boolean enabled, String description) {
    }

    private Validated validated(ExternalConnectionDto.RuleRequest request, Long id) {
        if (request == null) throw invalid("요청이 비어 있습니다.");
        ConnectionType type = type(request.connectionType());
        String host = request.host() == null || request.host().isBlank() ? null : NetworkAddresses.normalizeHost(request.host());
        if (host != null && (host.contains("*") || !NetworkAddresses.isValidHost(host))) throw invalid("호스트는 호스트명 또는 숫자 주소로 입력해주세요. 와일드카드는 쓸 수 없습니다.");
        if (host != null && NetworkAddresses.isBlockedHostName(host)) throw invalid("클라우드 메타데이터 이름은 허용할 수 없습니다.");
        if (request.cidr() == null || request.cidr().isBlank()) throw invalid("허용 대역(CIDR)을 입력해주세요.");
        Cidr cidr;
        try {
            cidr = Cidr.parse(request.cidr());
        } catch (IllegalArgumentException e) {
            throw invalid("허용 대역은 10.10.0.0/16 · 127.0.0.1/32 · fd12::/48 처럼 숫자 주소와 접두 길이로 입력해주세요.");
        }
        if (cidr.prefix() < (cidr.ipv4() ? 8 : 16)) throw invalid("너무 넓은 대역입니다. IPv4 는 /8, IPv6 는 /16 보다 좁게 정해주세요.");
        if (NetworkAddresses.blockedNetworks().stream().anyMatch(cidr::within) || NetworkAddresses.isBlocked(cidr.first()) && cidr.prefix() == (cidr.ipv4() ? 32 : 128)) {
            throw invalid("항상 차단되는 대역(링크 로컬·클라우드 메타데이터·멀티캐스트·미지정 등)은 허용할 수 없습니다.");
        }
        InetAddress literal = host == null ? null : NetworkAddresses.literal(host);
        if (literal != null && NetworkAddresses.isBlocked(literal)) throw invalid("항상 차단되는 주소는 허용할 수 없습니다.");
        if (literal != null && !cidr.contains(literal)) throw invalid("숫자 주소 호스트는 허용 대역 안이어야 합니다.");
        if (request.port() == null || request.port() < 1 || request.port() > 65535) throw invalid("포트는 1~65535 사이로 입력해주세요.");
        String description = request.description() == null || request.description().isBlank() ? null : request.description().trim();
        if (description != null && description.length() > 1000) throw invalid("설명은 1000자까지 입력할 수 있습니다.");
        boolean duplicate = rules.findAllByConnectionTypeAndAllowedAddressAndPort(type, cidr.toString(), request.port()).stream()
                .anyMatch(rule -> !Objects.equals(rule.getId(), id) && Objects.equals(NetworkAddresses.normalizeHost(rule.getHost()), host));
        if (duplicate) throw invalid("같은 연결 유형·호스트·대역·포트의 규칙이 이미 있습니다.");
        return new Validated(type, host, cidr, request.port(), request.enabled() == null || request.enabled(), description);
    }

    private static ConnectionType type(String value) {
        try {
            return ConnectionType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("연결 유형은 JDBC 또는 AI_HTTP 입니다.");
        }
    }

    private static CustomException invalid(String message) {
        return new CustomException(ErrorCode.CONNECTION_RULE_INVALID, message);
    }

    private static void apply(ExternalConnectionRule rule, Validated v) {
        rule.setConnectionType(v.type());
        rule.setHost(v.host());
        rule.setAllowedAddress(v.cidr().toString());
        rule.setPort(v.port());
        rule.setEnabled(v.enabled());
        rule.setDescription(v.description());
    }

    private static Map<String, Object> state(ExternalConnectionRule rule) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("connectionType", rule.getConnectionType().name());
        state.put("host", rule.getHost());
        state.put("cidr", rule.getAllowedAddress());
        state.put("port", rule.getPort());
        state.put("enabled", rule.getEnabled());
        state.put("description", rule.getDescription());
        return state;
    }

    private void record(Long ruleId, ChangeType type, Map<String, Object> before, Map<String, Object> after) {
        changes.saveAndFlush(ExternalConnectionRuleHistory.builder().ruleId(ruleId).changeType(type.name()).before(before == null ? null : JSON.writeValueAsString(before))
                .after(after == null ? null : JSON.writeValueAsString(after)).requestId(UUID.randomUUID().toString()).build());
    }

    private static ExternalConnectionDto.Rule dto(ExternalConnectionRule rule) {
        var audit = rule.audit();
        return new ExternalConnectionDto.Rule(rule.getId(), rule.getConnectionType(), rule.getHost(), rule.getAllowedAddress(), rule.getPort(),
                Boolean.TRUE.equals(rule.getEnabled()), rule.getDescription(), audit.createdBy(), audit.created(), audit.updatedBy(), audit.updated());
    }
}
