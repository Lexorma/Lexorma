package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.network.ConnectionDecision;
import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.HostResolver;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.http.ExternalHttpClients;
import com.lexorma.core.network.http.GuardedHttpTarget;
import com.lexorma.core.network.service.ExternalConnectionGuard;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.system.security.controller.ConnectionSecurityRestController;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-1 외부 연결 보안 (H2 / PostgreSQL) : 관리자 허용 규칙(기본 차단·유형 분리·한 규칙이 모든 조건), 절대 차단, DNS(혼합 응답·실패·재바인딩),
 * AI HTTP 어댑터(실제 연결·리다이렉트·프록시·TLS 검증), 관리자 권한, 정책 변경·연결 시도 감사. DNS 는 테스트 이름풀이(FakeResolver)로 흉내 낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1"})
@ActiveProfiles("test")
class ExternalConnectionSecurityIntegrationTest {

    /** 테스트 이름풀이 : 이름마다 응답 목록 (부를 때마다 다음 응답, 마지막 응답은 계속) */
    static final class FakeResolver implements HostResolver {
        final Map<String, List<String[]>> answers = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        void answer(String host, String[]... responses) {
            answers.put(host, new ArrayList<>(List.of(responses)));
            calls.put(host, new AtomicInteger());
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            List<String[]> list = answers.get(host);
            if (list == null) throw new UnknownHostException(host);
            int call = calls.get(host).getAndIncrement();
            String[] response = list.get(Math.min(call, list.size() - 1));
            InetAddress[] addresses = new InetAddress[response.length];
            for (int i = 0; i < response.length; i++) addresses[i] = InetAddress.getByName(response[i]);
            return addresses;
        }
    }

    @TestConfiguration
    static class Resolver {
        @Bean
        @Primary
        FakeResolver fakeResolver() {
            return new FakeResolver();
        }
    }

    @Autowired FakeResolver dns;
    @Autowired ExternalConnectionGuard guard;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired ExternalHttpClients http;
    @Autowired ConnectionSecurityRestController api;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (Long id : created) {
            try {
                rules.delete(id);
            } catch (CustomException ignored) {
                // 테스트에서 이미 지웠다
            }
        }
        for (AutoCloseable server : servers) server.close();
        SecurityTestSupport.clear();
    }

    private Long rule(String type, String host, String cidr, int port) {
        Long id = rules.create(new RuleRequest(type, host, cidr, port, true, "SEC-1 테스트")).id();
        created.add(id);
        return id;
    }

    private ConnectionDecision evaluate(ConnectionType type, String host, int port) {
        return guard.evaluate(type, host, port, List.of());
    }

    @Test
    @DisplayName("규칙 판정 (SEC-01~09, 14, 15, 19, 20) : 기본 차단, 포트·호스트·대역이 한 규칙에서 모두 맞아야 허용, 비활성·다른 유형 규칙은 안 쓴다, 혼합 DNS·DNS 실패 차단, 규칙 변경 즉시 반영")
    void ruleEvaluation() {
        int port = 15000 + (int) (Math.random() * 1000);
        dns.answer("db.sec.test", new String[]{"10.10.1.5"});
        dns.answer("other.sec.test", new String[]{"10.10.1.6"});
        dns.answer("far.sec.test", new String[]{"10.20.0.1"});
        dns.answer("mixed.sec.test", new String[]{"10.10.1.7", "10.99.0.1"});
        dns.answer("gpu.sec.test", new String[]{"192.168.10.20"});

        assertThat(evaluate(ConnectionType.JDBC, "db.sec.test", port).reason()).as("SEC-01 규칙 없음").isEqualTo(Reason.NO_RULE);
        Long dbRule = rule("JDBC", "db.sec.test", "10.10.0.0/16", port);
        ConnectionDecision allowed = evaluate(ConnectionType.JDBC, "DB.sec.test.", port);
        assertThat(allowed.allowed()).as("SEC-02 정확히 일치 (호스트 표기 차이는 정규화)").isTrue();
        assertThat(allowed.ruleId()).isEqualTo(dbRule);
        assertThat(allowed.addresses()).extracting(InetAddress::getHostAddress).containsExactly("10.10.1.5");
        assertThat(allowed.pin().cidr()).hasToString("10.10.0.0/16");
        assertThat(evaluate(ConnectionType.JDBC, "db.sec.test", port + 1).reason()).as("SEC-03 포트").isEqualTo(Reason.PORT_NOT_ALLOWED);
        assertThat(evaluate(ConnectionType.JDBC, "other.sec.test", port).reason()).as("SEC-04 호스트").isEqualTo(Reason.HOST_NOT_ALLOWED);
        Long anyHost = rule("JDBC", null, "10.10.0.0/16", port);
        assertThat(evaluate(ConnectionType.JDBC, "other.sec.test", port).allowed()).as("호스트를 비운 규칙은 대역만 본다").isTrue();
        assertThat(evaluate(ConnectionType.JDBC, "far.sec.test", port).reason()).as("SEC-05 대역").isEqualTo(Reason.ADDRESS_NOT_ALLOWED);
        assertThat(evaluate(ConnectionType.JDBC, "mixed.sec.test", port).reason()).as("SEC-14 일부만 허용된 DNS 응답").isEqualTo(Reason.MIXED_ADDRESSES);
        assertThat(evaluate(ConnectionType.JDBC, "unknown.sec.test", port).reason()).as("SEC-15 DNS 실패").isIn(Reason.DNS_FAILED);
        assertThat(evaluate(ConnectionType.AI_HTTP, "db.sec.test", port).reason()).as("SEC-07 다른 연결 유형의 규칙").isEqualTo(Reason.NO_RULE);
        assertThat(evaluate(ConnectionType.JDBC, "db1.sec.test,db2.sec.test", port).reason()).as("SEC-19 다중 호스트 표기").isEqualTo(Reason.INVALID_HOST);

        // SEC-06·SEC-20 : 비활성 규칙은 안 쓰고, 바꾸면 다음 판정부터 바로 반영
        rules.update(dbRule, new RuleRequest("JDBC", "db.sec.test", "10.10.0.0/16", port, false, null));
        rules.update(anyHost, new RuleRequest("JDBC", null, "10.10.0.0/16", port, false, null));
        assertThat(evaluate(ConnectionType.JDBC, "db.sec.test", port).reason()).isEqualTo(Reason.NO_RULE);
        rules.update(dbRule, new RuleRequest("JDBC", "db.sec.test", "10.10.0.0/16", port, true, null));
        assertThat(evaluate(ConnectionType.JDBC, "db.sec.test", port).allowed()).isTrue();

        // SEC-08·SEC-09 : 명시적으로 허용한 루프백·사설망
        rule("AI_HTTP", "127.0.0.1", "127.0.0.1/32", port);
        assertThat(evaluate(ConnectionType.AI_HTTP, "127.0.0.1", port).allowed()).isTrue();
        assertThat(evaluate(ConnectionType.AI_HTTP, "127.0.0.2", port).allowed()).as("명시하지 않은 루프백").isFalse();
        rule("AI_HTTP", "gpu.sec.test", "192.168.10.0/24", port);
        assertThat(evaluate(ConnectionType.AI_HTTP, "gpu.sec.test", port).allowed()).isTrue();

        // 운영자 상한 : 상한이 있으면 그 밖의 사설 주소는 규칙이 있어도 막힌다
        assertThat(guard.evaluate(ConnectionType.AI_HTTP, "gpu.sec.test", port, List.of(com.lexorma.core.network.NetworkAddresses.Cidr.parse("10.0.0.0/8"))).reason())
                .isEqualTo(Reason.OPERATOR_LIMIT);
    }

    @Test
    @DisplayName("절대 차단 (SEC-10~13, 30) : 메타데이터·링크 로컬(IPv4·IPv6)·IPv4-mapped·NAT64 우회는 규칙이 있어도 막고, 그런 대역·이름·전체망 규칙은 등록할 수 없다")
    void absoluteBlocks() {
        int port = 16000 + (int) (Math.random() * 1000);
        // 겹치는 넓은 규칙(169.0.0.0/8)이 있어도 메타데이터 주소는 열리지 않는다
        rule("AI_HTTP", null, "169.0.0.0/8", port);
        dns.answer("meta.sec.test", new String[]{"169.254.169.254"});
        for (String host : List.of("169.254.169.254", "169.254.1.1", "fe80::1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "0.0.0.0", "::", "meta.sec.test")) {
            assertThat(evaluate(ConnectionType.AI_HTTP, host, port).reason()).as(host).isEqualTo(Reason.BLOCKED_ADDRESS);
        }
        assertThat(evaluate(ConnectionType.AI_HTTP, "metadata.google.internal", port).reason()).isEqualTo(Reason.BLOCKED_HOST);
        for (String[] bad : List.of(new String[]{null, "169.254.169.254/32"}, new String[]{null, "169.254.0.0/16"}, new String[]{null, "fe80::/64"},
                new String[]{null, "0.0.0.0/0"}, new String[]{null, "::/0"}, new String[]{null, "10.0.0.0/7"}, new String[]{"metadata.google.internal", "10.0.0.0/8"},
                new String[]{"169.254.169.254", "169.0.0.0/8"}, new String[]{"10.1.1.1", "10.2.0.0/16"}, new String[]{"*.example.com", "10.0.0.0/8"},
                new String[]{null, "10.0.0.0/33"}, new String[]{null, "db.internal/24"})) {
            assertThatThrownBy(() -> rules.create(new RuleRequest("JDBC", bad[0], bad[1], 5432, true, null))).as(String.join(" ", java.util.Arrays.stream(bad).map(String::valueOf).toList()))
                    .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);
        }
        assertThatThrownBy(() -> rules.create(new RuleRequest("FTP", null, "10.0.0.0/8", 21, true, null))).extracting("errorCode").isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);
        assertThatThrownBy(() -> rules.create(new RuleRequest("JDBC", null, "10.0.0.0/8", 70000, true, null))).extracting("errorCode").isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);
        // 중복
        rule("JDBC", null, "10.30.0.0/16", port);
        assertThatThrownBy(() -> rules.create(new RuleRequest("JDBC", null, "10.30.0.0/16", port, true, null))).extracting("errorCode").isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);
    }

    @Test
    @DisplayName("IPv6 숫자 호스트 (IP6-01~08) : 표기(축약·확장·대괄호·대소문자)가 달라도 같은 주소면 같은 호스트로 본다 (이름은 풀지 않는다). 예전에 비정규 표기로 저장된 규칙도 맞는다. 우회 표기·절대 차단은 그대로")
    void ipv6LiteralHosts() throws Exception {
        int port = 17000 + (int) (Math.random() * 1000);
        // IP6-01·02·05 : 확장 표기로 등록한 규칙 = ::1. 요청은 축약·확장·대괄호 어느 쪽이든 허용 (FakeResolver 에 답이 없으므로 이름을 풀었다면 DNS_FAILED)
        Long loopback = rule("AI_HTTP", "0:0:0:0:0:0:0:1", "::1/128", port);
        assertThat(rules.list("AI_HTTP", null).stream().filter(r -> r.id().equals(loopback)).findFirst().orElseThrow().host()).isEqualTo("::1");
        for (String host : List.of("::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]", "0000:0000:0000:0000:0000:0000:0000:0001")) {
            ConnectionDecision decision = evaluate(ConnectionType.AI_HTTP, host, port);
            assertThat(decision.allowed()).as(host).isTrue();
            assertThat(decision.host()).as(host).isEqualTo("::1");
            assertThat(decision.pin().host()).isEqualTo("::1");
        }
        assertThat(dns.calls.keySet()).as("IP6-05 숫자 주소는 이름풀이를 거치지 않는다").noneMatch(h -> h.contains(":"));
        // 같은 주소의 다른 표기로는 중복 등록할 수 없다
        assertThatThrownBy(() -> rules.create(new RuleRequest("AI_HTTP", "[::1]", "::1/128", port, true, null))).extracting("errorCode")
                .isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);
        // IP6-03·04 : 대소문자, 다른 주소
        rule("AI_HTTP", "FD12:0:0:0:0:0:0:A", "fd12::/48", port);
        assertThat(evaluate(ConnectionType.AI_HTTP, "fd12::a", port).allowed()).as("IP6-03").isTrue();
        assertThat(evaluate(ConnectionType.AI_HTTP, "[FD12::A]", port).allowed()).as("IP6-03").isTrue();
        assertThat(evaluate(ConnectionType.AI_HTTP, "fd12::b", port).reason()).as("IP6-04 다른 주소").isEqualTo(Reason.HOST_NOT_ALLOWED);
        // 예전(정규화 전)에 비정규 표기로 저장된 규칙 : 판정할 때 다시 맞춘다
        Long legacy = rule("AI_HTTP", "fd34::1", "fd34::/48", port);
        jdbc.update("update TB_EXTRL_CNTN_RULE set HOST_NM = 'FD34:0:0:0:0:0:0:1' where EXTRL_CNTN_RULE_SN = ?", legacy);
        assertThat(evaluate(ConnectionType.AI_HTTP, "[fd34::1]", port).ruleId()).as("저장된 비정규 표기").isEqualTo(legacy);
        assertThat(evaluate(ConnectionType.AI_HTTP, "fd34::1", port + 1).reason()).as("포트만 다르면 아는 호스트").isEqualTo(Reason.PORT_NOT_ALLOWED);

        // IP6-06·08 : IPv4-mapped 우회, 절대 차단 주소의 다른 표기는 겹치는 규칙이 있어도 계속 막힌다. zone 표기는 받지 않는다
        rule("AI_HTTP", null, "169.0.0.0/8", port);
        for (String host : List.of("::ffff:169.254.169.254", "::FFFF:A9FE:A9FE", "[::ffff:a9fe:a9fe]", "0:0:0:0:0:ffff:169.254.169.254",
                "FE80:0:0:0:0:0:0:1", "[fe80::1]", "0:0:0:0:0:0:0:0", "64:FF9B::A9FE:A9FE")) {
            assertThat(evaluate(ConnectionType.AI_HTTP, host, port).reason()).as(host).isEqualTo(Reason.BLOCKED_ADDRESS);
        }
        assertThat(evaluate(ConnectionType.AI_HTTP, "fe80::1%eth0", port).reason()).isEqualTo(Reason.INVALID_HOST);
        assertThatThrownBy(() -> rules.create(new RuleRequest("AI_HTTP", "FE80:0:0:0:0:0:0:1", "fe80::1/128", port + 2, true, null))).extracting("errorCode")
                .isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);

        // IP6-07 : 실제 연결. 규칙은 확장 표기, 주소는 [::1] → 판정·소켓 모두 같은 호스트로 보고 연결한다
        AtomicInteger hits = new AtomicInteger();
        HttpServer v6 = HttpServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        v6.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        v6.start();
        servers.add(() -> v6.stop(0));
        int v6Port = v6.getAddress().getPort();
        rule("AI_HTTP", "0:0:0:0:0:0:0:1", "::1/128", v6Port);
        try (GuardedHttpTarget target = http.open(ConnectionType.AI_HTTP, URI.create("http://[::1]:" + v6Port + "/api/tags"), null)) {
            ResponseEntity<Void> response = RestClient.builder().requestFactory(target.requestFactory()).build().get()
                    .uri("http://[0:0:0:0:0:0:0:1]:" + v6Port + "/api/tags").retrieve().toBodilessEntity();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
        assertThat(hits.get()).isEqualTo(1);
        assertThat(rules.attempts("AI_HTTP", null, "::1", null, null, null, 0, 10).rows()).extracting(ExternalConnectionDto.Attempt::result)
                .contains(ExternalConnectionDto.Result.CONNECTED);
    }

    @Test
    @DisplayName("MIXED_ADDRESSES 안내 : 사유 코드는 그대로, IPv4·IPv6 로 함께 풀려 막히면 그 원인과 숫자 주소 사용을 알려 준다 (일부만 허용하거나 한쪽 주소로 연결하지 않는다)")
    void mixedAddressGuidance() {
        int port = 18000 + (int) (Math.random() * 1000);
        dns.answer("dual.sec.test", new String[]{"127.0.0.1", "::1"});
        dns.answer("pair.sec.test", new String[]{"10.20.1.1", "10.99.1.1"});
        rule("AI_HTTP", "dual.sec.test", "127.0.0.0/8", port);
        rule("AI_HTTP", "pair.sec.test", "10.20.0.0/16", port);
        ConnectionDecision dual = evaluate(ConnectionType.AI_HTTP, "dual.sec.test", port);
        assertThat(dual.reason()).isEqualTo(Reason.MIXED_ADDRESSES);
        assertThat(ExternalConnectionGuard.reasonText(dual)).contains("IPv4와 IPv6", "127.0.0.1", "::1");
        ConnectionDecision pair = evaluate(ConnectionType.AI_HTTP, "pair.sec.test", port);
        assertThat(pair.reason()).isEqualTo(Reason.MIXED_ADDRESSES);
        assertThat(ExternalConnectionGuard.reasonText(pair)).contains("여러 주소").doesNotContain("IPv4와 IPv6");
        // 어댑터의 오류 문구에도 같은 안내, 감사에는 기존 코드
        assertThatThrownBy(() -> http.open(ConnectionType.AI_HTTP, URI.create("http://dual.sec.test:" + port + "/"), null))
                .isInstanceOf(CustomException.class).extracting("customMessage").asString().contains("IPv4와 IPv6");
        assertThat(rules.attempts("AI_HTTP", null, "dual.sec.test", null, null, null, 0, 10).rows()).singleElement()
                .satisfies(a -> assertThat(a).extracting(ExternalConnectionDto.Attempt::result, ExternalConnectionDto.Attempt::reason)
                        .containsExactly(ExternalConnectionDto.Result.DENIED, Reason.MIXED_ADDRESSES));
    }

    @Test
    @DisplayName("관리자 권한·정책 변경 감사 (SEC-21~25, 29) : ADMIN 만 규칙을 다룬다 (USER·MANAGER 는 403). 등록·수정·활성화·비활성화·삭제는 변경 전후와 함께 이력에 남는다")
    void adminAndPolicyAudit() {
        Account admin = account("adm", "ROLE_ADMIN");
        Project project = project(admin);
        SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        int port = 17000 + (int) (Math.random() * 1000);
        ExternalConnectionDto.Rule createdRule = api.create(new RuleRequest("JDBC", "db.audit.test", "10.40.0.0/16", port, true, "감사 테스트")).getBody();
        Long id = createdRule.id();
        assertThat(createdRule).extracting(ExternalConnectionDto.Rule::host, ExternalConnectionDto.Rule::cidr).containsExactly("db.audit.test", "10.40.0.0/16");
        String actor = createdRule.createdBy(); // 로그인 세션의 사용자 (요청 값이 아니다)
        assertThat(actor).isNotBlank();
        api.update(id, new RuleRequest("JDBC", "db.audit.test", "10.40.0.0/24", port, true, "감사 테스트"));
        api.update(id, new RuleRequest("JDBC", "db.audit.test", "10.40.0.0/24", port, false, "감사 테스트"));
        api.update(id, new RuleRequest("JDBC", "db.audit.test", "10.40.0.0/24", port, true, "감사 테스트"));
        api.delete(id);
        List<ExternalConnectionDto.RuleChange> changes = api.changes(0, 100).getBody().rows().stream().filter(c -> c.ruleId().equals(id)).toList();
        assertThat(changes).extracting(ExternalConnectionDto.RuleChange::changeType).containsExactly(ExternalConnectionDto.ChangeType.DELETE,
                ExternalConnectionDto.ChangeType.ENABLE, ExternalConnectionDto.ChangeType.DISABLE, ExternalConnectionDto.ChangeType.UPDATE, ExternalConnectionDto.ChangeType.CREATE);
        assertThat(changes.get(3).before()).contains("10.40.0.0/16");
        assertThat(changes.get(3).after()).contains("10.40.0.0/24");
        assertThat(changes).allSatisfy(c -> assertThat(c.userId()).isEqualTo(actor));
        assertThat(changes).allSatisfy(c -> assertThat(c.requestId()).isNotBlank());
        // SEC-29 : 잘못된 CIDR 은 400 (CustomException → 전역 처리기)
        assertThatThrownBy(() -> api.create(new RuleRequest("JDBC", null, "10.0.0.0/40", port, true, null))).extracting("errorCode").isEqualTo(ErrorCode.CONNECTION_RULE_INVALID);
        assertThat(ErrorCode.CONNECTION_RULE_INVALID.getHttpStatus().value()).isEqualTo(400);

        // SEC-22·SEC-23 : USER, MANAGER(그 프로젝트의 관리자여도) 는 시스템 정책을 바꿀 수 없다
        for (String role : List.of("ROLE_USER", "ROLE_MANAGER", "ROLE_DBA")) {
            Account other = account("u", role);
            Project own = project(other);
            SecurityTestSupport.loginAs(projectRepository, own.getId(), String.valueOf(other.getId()), role);
            assertThatThrownBy(() -> api.create(new RuleRequest("JDBC", null, "10.50.0.0/16", port, true, null))).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.rules(null, null)).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.attempts(null, null, null, null, null, null, 0, 10)).as(role).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("연결 시도 감사·등록 연결 안내 (SEC-26~28) : 차단은 사유와 함께, 허용은 결과(연결됨·실패)와 함께 남고 인증 정보는 없다. 규칙이 없는 등록 연결을 알려 준다")
    void connectionAuditAndUnmatched() throws Exception {
        Account manager = account("dba", "ROLE_DBA");
        Project project = project(manager);
        AccountDto managerDto = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA");
        String host = "src-" + UUID.randomUUID().toString().substring(0, 8) + ".sec.test";
        dns.answer(host, new String[]{"10.60.0.5"});
        // 원본 연결 테스트 : 규칙이 없어 차단 (사유가 응답에 있다)
        SourceConnectionDto.Request request = new SourceConnectionDto.Request(null, "POSTGRESQL", host, 5432, "app", null, "reader", "secret-pw", null, null, null, null);
        SourceConnectionDto.TestResult result = connectionApi.testDraft(managerDto, null, request).getBody();
        assertThat(result.errorCode()).isEqualTo("SOURCE_HOST_NOT_ALLOWED");
        assertThat(result.message()).contains("관리자가 허용한 규칙이 없습니다");
        // 등록한 연결은 "허용 규칙이 없는 등록 연결" 로 보인다
        connectionApi.create(managerDto, new SourceConnectionDto.Request("감사 연결", "POSTGRESQL", host, 5432, "app", null, "reader", "secret-pw", null, null, null, null));
        assertThat(rules.unmatched()).anySatisfy(u -> {
            assertThat(u.host()).isEqualTo(host);
            assertThat(u.reason()).isEqualTo(Reason.NO_RULE);
            assertThat(u.label()).contains("감사 연결");
        });
        // 규칙을 만들면 안내에서 빠지고, 연결 시도는 허용 → 실패(서버 없음)로 남는다
        rule("JDBC", host, "10.60.0.0/16", 5432);
        assertThat(rules.unmatched()).noneMatch(u -> u.host().equals(host));

        List<ExternalConnectionDto.Attempt> attempts = rules.attempts("JDBC", null, host, null, null, null, 0, 20).rows();
        assertThat(attempts).anySatisfy(a -> {
            assertThat(a.result()).isEqualTo(ExternalConnectionDto.Result.DENIED);
            assertThat(a.reason()).isEqualTo(Reason.NO_RULE);
            assertThat(a.userId()).isNotBlank();
            assertThat(a.executionType()).isEqualTo("USER");
        });
        // SEC-28 : 감사 기록에 비밀번호·계정이 없다
        String dump = String.join("|", jdbc.queryForList("select coalesce(HOST_NM, '') || coalesce(CNTN_ADDR_CN, '') || coalesce(RSN_CD, '') || coalesce(DMND_ID, '')"
                + " from TB_EXTRL_CNTN_HSTRY", String.class));
        assertThat(dump).doesNotContain("secret-pw").doesNotContain("reader");
        assertThat(rules.attempts(null, "DENIED", host, null, java.time.LocalDate.now(), java.time.LocalDate.now(), 0, 20).total()).isPositive();
    }

    @Test
    @DisplayName("AI HTTP 어댑터 (SEC-16~18, TLS) : 허용 대상에 실제 연결, 판정한 주소로만(DNS 재조회 없음), 리다이렉트를 따라가지 않고, 시스템 프록시를 거치지 않으며, 인증서 검증을 끄지 않는다")
    void httpAdapter() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        AtomicInteger proxyHits = new AtomicInteger();
        HttpServer b = server(hitsB, 200, null);
        HttpServer a = server(hitsA, 302, "http://127.0.0.1:" + b.getAddress().getPort() + "/");
        HttpServer proxy = server(proxyHits, 200, null);
        int portA = a.getAddress().getPort();
        rule("AI_HTTP", "api.sec.test", "127.0.0.1/32", portA);

        // 차단 : 규칙이 없는 대상 (시도는 기록된다)
        assertThatThrownBy(() -> http.open(ConnectionType.AI_HTTP, URI.create("http://127.0.0.1:" + b.getAddress().getPort() + "/"), null))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_CONNECTION_BLOCKED);
        assertThatThrownBy(() -> http.open(ConnectionType.AI_HTTP, URI.create("ftp://api.sec.test:" + portA + "/"), null)).extracting("errorCode")
                .isEqualTo(ErrorCode.EXTERNAL_CONNECTION_BLOCKED);
        assertThatThrownBy(() -> http.open(ConnectionType.AI_HTTP, URI.create("http://user:pw@api.sec.test:" + portA + "/"), null)).extracting("errorCode")
                .isEqualTo(ErrorCode.EXTERNAL_CONNECTION_BLOCKED);

        // SEC-16 : 판정 때 127.0.0.1, 그 뒤 DNS 가 10.0.0.1 을 준다 → 연결은 판정한 주소로만 (다시 풀지 않는다)
        dns.answer("api.sec.test", new String[]{"127.0.0.1"}, new String[]{"10.0.0.1"});
        Map<String, String> saved = new java.util.HashMap<>();
        for (String key : List.of("http.proxyHost", "http.proxyPort", "socksProxyHost", "socksProxyPort")) saved.put(key, System.getProperty(key));
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(proxy.getAddress().getPort()));
        System.setProperty("socksProxyHost", "203.0.113.1");
        System.setProperty("socksProxyPort", "1080");
        ResponseEntity<String> response;
        try (GuardedHttpTarget target = http.open(ConnectionType.AI_HTTP, URI.create("http://api.sec.test:" + portA + "/v1"), null)) {
            response = RestClient.builder().requestFactory(target.requestFactory()).build().get().uri("http://api.sec.test:" + portA + "/v1").retrieve().toEntity(String.class);
            // SEC-17 : 리다이렉트(허용하지 않은 다른 포트)를 따라가지 않는다
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            assertThat(target.decision().pin().addresses()).extracting(InetAddress::getHostAddress).containsExactly("127.0.0.1");
            // 같은 대상으로 다른 포트·다른 호스트에는 연결되지 않는다
            assertThatThrownBy(() -> RestClient.builder().requestFactory(target.requestFactory()).build().get()
                    .uri("http://api.sec.test:" + b.getAddress().getPort() + "/").retrieve().toEntity(String.class)).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> RestClient.builder().requestFactory(target.requestFactory()).build().get()
                    .uri("http://other.sec.test:" + portA + "/").retrieve().toEntity(String.class)).isInstanceOf(Exception.class);
        } finally {
            saved.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
        assertThat(hitsA.get()).isEqualTo(1);
        assertThat(hitsB.get()).as("리다이렉트 대상에 연결하지 않았다").isZero();
        assertThat(proxyHits.get()).as("SEC-18 : 시스템 프록시를 거치지 않았다").isZero();
        assertThat(dns.calls.get("api.sec.test").get()).as("판정 때 한 번만 풀었다").isEqualTo(1);
        assertThat(rules.attempts("AI_HTTP", null, "api.sec.test", null, null, null, 0, 10).rows()).extracting(ExternalConnectionDto.Attempt::result)
                .contains(ExternalConnectionDto.Result.CONNECTED);

        // TLS : 신뢰하지 않는 인증서면 실패한다 (검증을 끄지 않는다). 결과는 실패로 남는다
        HttpsServer tls = httpsServer();
        int tlsPort = tls.getAddress().getPort();
        dns.answer("tls.sec.test", new String[]{"127.0.0.1"});
        rule("AI_HTTP", "tls.sec.test", "127.0.0.1/32", tlsPort);
        try (GuardedHttpTarget target = http.open(ConnectionType.AI_HTTP, URI.create("https://tls.sec.test:" + tlsPort + "/"), null)) {
            assertThatThrownBy(() -> RestClient.builder().requestFactory(target.requestFactory()).build().get().uri("https://tls.sec.test:" + tlsPort + "/")
                    .retrieve().toEntity(String.class)).hasRootCauseInstanceOf(java.security.cert.CertPathBuilderException.class);
        }
        assertThat(rules.attempts("AI_HTTP", null, "tls.sec.test", null, null, null, 0, 10).rows()).extracting(ExternalConnectionDto.Attempt::result)
                .containsExactly(ExternalConnectionDto.Result.FAILED);
    }

    // ------------------------------------------------------------------ 준비

    private HttpServer server(AtomicInteger hits, int status, String location) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            if (location != null) exchange.getResponseHeaders().add("Location", location);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        servers.add(() -> server.stop(0));
        return server;
    }

    /** 자체 서명 인증서(신뢰 저장소에 없음)의 HTTPS 서버 : keytool 로 만든다 */
    private HttpsServer httpsServer() throws Exception {
        File store = Files.createTempFile("sec1-tls", ".p12").toFile();
        store.delete();
        Process keytool = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "sec1", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=tls.sec.test", "-ext", "SAN=dns:tls.sec.test", "-validity", "1", "-storetype", "PKCS12", "-keystore", store.getPath(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        assertThat(keytool.waitFor()).isZero();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(store)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        servers.add(() -> server.stop(0));
        servers.add(store::delete);
        return server;
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(Account owner) {
        Project project = projectRepository.save(Project.builder().projectName("sec1-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        return project;
    }
}
