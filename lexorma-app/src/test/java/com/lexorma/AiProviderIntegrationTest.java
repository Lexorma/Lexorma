package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.dto.AiProviderDto.Failure;
import com.lexorma.core.ai.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.core.ai.service.AiProviderClients;
import com.lexorma.core.ai.service.AiProviderService;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
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
import com.lexorma.system.ai.controller.AiProviderRestController;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI Provider (AI-3, H2 / PostgreSQL) : Provider 가 없어도 기동하고 모델 Bean 이 없다, 설정 관리(검증·중복·유형), SEC-1 을 거치는 Spring AI Ollama 호출
 * (연결 테스트·모델 목록·테스트 프롬프트·이후 기능용 call), 오류 분류(차단·거부·시간 초과·모델 없음·서버 오류·리다이렉트·잘못된 응답), 재시도 없음,
 * 스트리밍 차단, 설정 변경의 즉시 적용, ADMIN 권한. AI 서버는 가짜 Ollama HTTP 서버(127.0.0.1), DNS 는 테스트 이름풀이(FakeResolver)로 흉내 낸다. 비밀값 키를 설정하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiProviderIntegrationTest {

    /** 가짜 Ollama : 경로마다 응답(상태·본문·지연). 받은 요청(경로 + 본문)을 남긴다 */
    static final class FakeOllama implements AutoCloseable {
        record Reply(int status, String body, long delayMillis, String location) {
        }

        final HttpServer server;
        final Map<String, Reply> replies = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();

        FakeOllama() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(exchange.getRequestURI().getPath() + " " + body);
                Reply reply = replies.getOrDefault(exchange.getRequestURI().getPath(), new Reply(404, "{\"error\":\"not found\"}", 0, null));
                try {
                    if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    if (reply.location() != null) exchange.getResponseHeaders().add("Location", reply.location());
                    exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // 클라이언트가 시간 초과로 끊었다
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String url() {
            return "http://127.0.0.1:" + port();
        }

        void reply(String path, int status, String body) {
            replies.put(path, new Reply(status, body, 0, null));
        }

        long count(String path) {
            return requests.stream().filter(r -> r.startsWith(path + " ")).count();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static final String TAGS = """
            {"models":[{"name":"llama3.2:latest","model":"llama3.2:latest","modified_at":"2026-10-01T09:00:00Z","size":2019393189,"digest":"a80c4f17acd5",
            "details":{"format":"gguf","family":"llama","families":["llama"],"parameter_size":"3.2B","quantization_level":"Q4_K_M"}},
            {"name":"qwen3:8b","model":"qwen3:8b","modified_at":"2026-10-02T09:00:00Z","size":5225388164,"digest":"500a1f067a9f","details":{"family":"qwen3"}}]}""";

    static String chat(String model, String content) {
        return """
                {"model":"%s","created_at":"2026-11-14T10:00:00Z","message":{"role":"assistant","content":"%s"},"done":true,"done_reason":"stop",
                "total_duration":1000000,"prompt_eval_count":7,"eval_count":3}""".formatted(model, content);
    }

    @TestConfiguration
    static class Resolver {
        @Bean
        @Primary
        ExternalConnectionSecurityIntegrationTest.FakeResolver aiFakeResolver() {
            return new ExternalConnectionSecurityIntegrationTest.FakeResolver();
        }
    }

    @Autowired ExternalConnectionSecurityIntegrationTest.FakeResolver dns;
    @Autowired ApplicationContext context;
    @Autowired AiProviderService service;
    @Autowired AiProviderClients clients;
    @Autowired AiProviderRestController api;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();
    private ExecutionContext admin;
    private AccountDto principal;

    @BeforeEach
    void login() {
        Account account = account("ROLE_ADMIN");
        Project project = project(account);
        principal = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(account.getId()), "ROLE_ADMIN");
        admin = ExecutionContext.user(principal);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Long id : createdProviders) {
            try {
                service.delete(id);
            } catch (CustomException ignored) {
                // 테스트에서 이미 지웠다
            }
        }
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("최소 설정 : AI 설정·비밀값 키 없이 기동하고, Spring AI 모델 Bean 이 없다. Provider 를 등록해도 저장만 하고 AI 서버에 연결하지 않는다")
    void startsWithoutProvider() throws Exception {
        assertThat(context.getBeanNamesForType(ChatModel.class)).as("자동 설정된 ChatModel 없음").isEmpty();
        assertThat(context.getBeanNamesForType(ChatClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ChatClient.Builder.class)).isEmpty();
        // Ollama 모듈은 lexorma-core 안에서만 쓴다 (테스트 컴파일 경로에 없다)
        assertThat(context.getBeanNamesForType(Class.forName("org.springframework.ai.ollama.api.OllamaApi"))).isEmpty();
        assertThat(context.getBeanNamesForType(Class.forName("org.springframework.ai.ollama.OllamaChatModel"))).isEmpty();
        assertThat(context.getEnvironment().getProperty("lexorma.secret.keys")).as("비밀값 키 없이 쓴다").isNullOrEmpty();

        FakeOllama ollama = ollama();
        long attempts = attempts(ollama);
        AiProviderDto.Provider provider = create("최소 설정", ollama.url(), "llama3.2");
        assertThat(provider.enabled()).isTrue();
        assertThat(ollama.requests).as("등록은 연결하지 않는다").isEmpty();
        assertThat(attempts(ollama)).as("연결 시도 기록도 없다").isEqualTo(attempts);
    }

    @Test
    @DisplayName("설정 관리 : 이름·유형·주소·모델 ID 검증, 이름 중복(대소문자 무시) 금지, 미지원 유형 거부, 주소 끝의 / 정리, 수정·삭제")
    void providerValidation() {
        String url = "http://127.0.0.1:11434";
        assertInvalid(new ProviderRequest(" ", "OLLAMA", url, null, true, null), ErrorCode.AI_PROVIDER_INVALID);
        assertInvalid(new ProviderRequest("x", null, url, null, true, null), ErrorCode.AI_PROVIDER_INVALID);
        assertInvalid(new ProviderRequest("x", "OPENAI_COMPATIBLE", url, null, true, null), ErrorCode.AI_PROVIDER_UNSUPPORTED);
        for (String bad : List.of("", "127.0.0.1:11434", "ftp://127.0.0.1", "http://user:pw@127.0.0.1:11434", "http://127.0.0.1:11434/?x=1", "http://127.0.0.1:11434/#a",
                "http://127.1:11434", "http://*.local:11434", "http://127.0.0.1:0", "http://" + "a".repeat(250) + ".com")) {
            assertInvalid(new ProviderRequest("x", "OLLAMA", bad, null, true, null), ErrorCode.AI_PROVIDER_INVALID);
        }
        assertInvalid(new ProviderRequest("x", "OLLAMA", url, "llama 3 ; rm", true, null), ErrorCode.AI_PROVIDER_INVALID);
        assertInvalid(new ProviderRequest("x", "OLLAMA", url, null, true, "a".repeat(1001)), ErrorCode.AI_PROVIDER_INVALID);

        String name = "로컬 Ollama " + UUID.randomUUID().toString().substring(0, 6);
        AiProviderDto.Provider created = api.create(new ProviderRequest(name, "ollama", "HTTP://127.0.0.1:11434/", " llama3.2:3b ", null, " 설명 ")).getBody();
        createdProviders.add(created.id());
        assertThat(created).extracting(AiProviderDto.Provider::type, AiProviderDto.Provider::baseUrl, AiProviderDto.Provider::defaultModel,
                AiProviderDto.Provider::enabled, AiProviderDto.Provider::description).containsExactly(com.lexorma.core.ai.AiProviderType.OLLAMA,
                "http://127.0.0.1:11434", "llama3.2:3b", true, "설명");
        assertThat(created.createdBy()).isNotBlank();
        assertInvalid(new ProviderRequest(name.toUpperCase(), "OLLAMA", url, null, true, null), ErrorCode.AI_PROVIDER_INVALID);

        AiProviderDto.Provider updated = api.update(created.id(), new ProviderRequest(name, "OLLAMA", "https://ollama.internal.test/base", "", false, null)).getBody();
        assertThat(updated).extracting(AiProviderDto.Provider::baseUrl, AiProviderDto.Provider::defaultModel, AiProviderDto.Provider::enabled)
                .containsExactly("https://ollama.internal.test/base", null, false);
        assertThat(api.types().getBody()).extracting(AiProviderDto.TypeInfo::type, AiProviderDto.TypeInfo::defaultBaseUrl)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(com.lexorma.core.ai.AiProviderType.OLLAMA, "http://127.0.0.1:11434"));

        api.delete(created.id());
        assertThatThrownBy(() -> api.get(created.id())).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_NOT_FOUND);
        assertThatThrownBy(() -> api.test(created.id(), principal)).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_NOT_FOUND);
    }

    @Test
    @DisplayName("SEC-1 : 허용 규칙이 없으면 연결하지 않고(차단 사유 NO_RULE, 시도 기록), 규칙을 등록하면 같은 요청이 실제 서버에 연결된다. 절대 차단 주소는 규칙으로도 열리지 않는다")
    void sec1AppliesToEveryCall() throws Exception {
        FakeOllama ollama = ollama();
        ollama.reply("/api/tags", 200, TAGS);
        ollama.reply("/api/chat", 200, chat("llama3.2:latest", "ok"));
        AiProviderDto.Provider provider = create("SEC-1", ollama.url(), "llama3.2");

        AiProviderDto.ConnectionTest blocked = clients.testConnection(provider.id(), admin);
        assertThat(blocked.success()).isFalse();
        assertThat(blocked.error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::blockReason).containsExactly(Failure.BLOCKED, Reason.NO_RULE);
        assertThat(clients.models(provider.id(), admin).error().failure()).isEqualTo(Failure.BLOCKED);
        assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin).error().blockReason()).isEqualTo(Reason.NO_RULE);
        assertThatThrownBy(() -> clients.call(provider.id(), null, admin, client -> client.prompt().user("hi").call().content()))
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_CONNECTION_BLOCKED);
        assertThat(ollama.requests).as("차단이면 서버에 아무 요청도 가지 않는다").isEmpty();
        assertThat(rules.attempts("AI_HTTP", "DENIED", "127.0.0.1", null, null, null, 0, 50).rows())
                .filteredOn(a -> a.port() == ollama.port()).hasSize(4).allSatisfy(a -> assertThat(a.reason()).isEqualTo(Reason.NO_RULE));

        // 다른 포트만 허용 → 포트 불일치로 차단
        rule("127.0.0.1", "127.0.0.1/32", ollama.port() == 65535 ? 65534 : ollama.port() + 1);
        assertThat(clients.testConnection(provider.id(), admin).error().blockReason()).isEqualTo(Reason.PORT_NOT_ALLOWED);
        // 다른 대역 → 대역 밖
        rule(null, "10.0.0.0/8", ollama.port());
        assertThat(clients.testConnection(provider.id(), admin).error().blockReason()).isEqualTo(Reason.ADDRESS_NOT_ALLOWED);
        assertThat(ollama.requests).isEmpty();

        // 규칙 등록 → 재시작 없이 다음 요청부터 연결
        rule("127.0.0.1", "127.0.0.1/32", ollama.port());
        AiProviderDto.ConnectionTest ok = clients.testConnection(provider.id(), admin);
        assertThat(ok.success()).isTrue();
        assertThat(ok.modelCount()).isEqualTo(2);
        assertThat(ok.defaultModelPresent()).as("태그 없는 기본 모델은 :latest").isTrue();
        assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin).success()).isTrue();
        assertThat(rules.attempts("AI_HTTP", null, "127.0.0.1", null, null, null, 0, 50).rows()).filteredOn(a -> a.port() == ollama.port())
                .extracting(ExternalConnectionDto.Attempt::result).contains(ExternalConnectionDto.Result.CONNECTED);

        // 절대 차단(링크 로컬·메타데이터) : 주소 자체가 차단되어 규칙을 볼 것도 없다
        AiProviderDto.Provider metadata = create("메타데이터", "http://169.254.169.254", null);
        assertThat(clients.testConnection(metadata.id(), admin).error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::blockReason)
                .containsExactly(Failure.BLOCKED, Reason.BLOCKED_ADDRESS);
        AiProviderDto.Provider metadataName = create("메타데이터 이름", "http://metadata.google.internal", null);
        assertThat(clients.testConnection(metadataName.id(), admin).error().blockReason()).isEqualTo(Reason.BLOCKED_HOST);
    }

    @Test
    @DisplayName("SEC-1 DNS : 판정 때 푼 주소로만 연결한다(재바인딩 — 다시 풀지 않는다). 다음 요청은 다시 판정한다. 혼합 응답(IPv4·IPv6)·이름 풀기 실패는 차단")
    void dnsPinning() throws Exception {
        FakeOllama ollama = ollama();
        ollama.reply("/api/tags", 200, TAGS);
        String host = "ollama-" + UUID.randomUUID().toString().substring(0, 6) + ".ai3.test";
        rule(host, "127.0.0.1/32", ollama.port());
        dns.answer(host, new String[]{"127.0.0.1"}, new String[]{"10.0.0.1"});
        AiProviderDto.Provider provider = create("DNS", "http://" + host + ":" + ollama.port(), "llama3.2");

        assertThat(clients.testConnection(provider.id(), admin).success()).as("판정한 127.0.0.1 로 연결").isTrue();
        assertThat(dns.calls.get(host).get()).as("Spring AI 의 HTTP 가 이름을 다시 풀지 않았다").isEqualTo(1);
        AiProviderDto.ConnectionTest rebound = clients.testConnection(provider.id(), admin);
        assertThat(rebound.error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::blockReason).containsExactly(Failure.BLOCKED, Reason.ADDRESS_NOT_ALLOWED);
        assertThat(ollama.count("/api/tags")).isEqualTo(1);

        String mixed = "mixed-" + UUID.randomUUID().toString().substring(0, 6) + ".ai3.test";
        rule(mixed, "127.0.0.1/32", ollama.port());
        dns.answer(mixed, new String[]{"127.0.0.1", "::1"});
        AiProviderDto.Provider both = create("혼합", "http://" + mixed + ":" + ollama.port(), null);
        assertThat(clients.testConnection(both.id(), admin).error().blockReason()).isEqualTo(Reason.MIXED_ADDRESSES);

        AiProviderDto.Provider unknown = create("이름 없음", "http://unknown-" + UUID.randomUUID().toString().substring(0, 6) + ".ai3.test:" + ollama.port(), null);
        rule(null, "127.0.0.1/32", ollama.port());
        assertThat(clients.testConnection(unknown.id(), admin).error().blockReason()).isEqualTo(Reason.DNS_FAILED);
        assertThat(ollama.count("/api/tags")).isEqualTo(1);
    }

    @Test
    @DisplayName("Spring AI Ollama : 모델 목록(/api/tags) 해석, ChatClient 테스트 프롬프트(/api/chat, stream=false, 기본 모델·지정 모델), 기본 모델 변경·비활성화·삭제가 재시작 없이 적용된다")
    void ollamaChat() throws Exception {
        FakeOllama ollama = ollama();
        ollama.reply("/api/tags", 200, TAGS);
        ollama.reply("/api/chat", 200, chat("llama3.2:latest", "안녕하세요"));
        rule("127.0.0.1", "127.0.0.1/32", ollama.port());
        AiProviderDto.Provider provider = create("채팅", ollama.url(), "llama3.2");

        AiProviderDto.Models models = api.models(provider.id(), principal).getBody();
        assertThat(models.success()).isTrue();
        assertThat(models.models()).extracting(AiProviderDto.Model::name).containsExactly("llama3.2:latest", "qwen3:8b");
        assertThat(models.models().get(0)).extracting(AiProviderDto.Model::family, AiProviderDto.Model::parameterSize, AiProviderDto.Model::quantization)
                .containsExactly("llama", "3.2B", "Q4_K_M");

        AiProviderDto.PromptResult answer = clients.prompt(provider.id(), new AiProviderDto.PromptRequest("  자기소개를 해 주세요  ", null), admin);
        assertThat(answer.success()).isTrue();
        assertThat(answer).extracting(AiProviderDto.PromptResult::content, AiProviderDto.PromptResult::model, AiProviderDto.PromptResult::promptTokens,
                AiProviderDto.PromptResult::completionTokens).containsExactly("안녕하세요", "llama3.2:latest", 7, 3);
        String request = ollama.requests.stream().filter(r -> r.startsWith("/api/chat ")).reduce((a, b) -> b).orElseThrow();
        assertThat(request).contains("\"model\":\"llama3.2\"", "\"stream\":false", "자기소개를 해 주세요");

        // 지정 모델
        clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", "qwen3:8b"), admin);
        assertThat(ollama.requests.get(ollama.requests.size() - 1)).contains("\"model\":\"qwen3:8b\"");
        // 이후 기능용 경로 : ChatClient 를 그대로 쓴다
        String content = clients.call(provider.id(), null, admin, client -> client.prompt().system("짧게").user("hi").call().content());
        assertThat(content).isEqualTo("안녕하세요");

        // 기본 모델 변경 → 다음 요청부터
        service.update(provider.id(), new ProviderRequest(provider.name(), "OLLAMA", ollama.url(), "qwen3:8b", true, null));
        clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin);
        assertThat(ollama.requests.get(ollama.requests.size() - 1)).contains("\"model\":\"qwen3:8b\"");
        // 기본 모델이 없으면 모델을 정해야 한다
        service.update(provider.id(), new ProviderRequest(provider.name(), "OLLAMA", ollama.url(), null, true, null));
        assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin).error().failure()).isEqualTo(Failure.MODEL_REQUIRED);
        assertThatThrownBy(() -> clients.prompt(provider.id(), new AiProviderDto.PromptRequest(" ", "qwen3:8b"), admin)).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROVIDER_INVALID);
        assertThatThrownBy(() -> clients.prompt(provider.id(), new AiProviderDto.PromptRequest("a".repeat(4001), "qwen3:8b"), admin)).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROVIDER_INVALID);

        // 비활성화 → 호출 차단 (연결 테스트·모델 목록은 활성화 전 확인용으로 허용)
        long before = ollama.count("/api/chat");
        service.update(provider.id(), new ProviderRequest(provider.name(), "OLLAMA", ollama.url(), "qwen3:8b", false, null));
        assertThatThrownBy(() -> clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin)).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
        assertThatThrownBy(() -> clients.call(provider.id(), null, admin, client -> client.prompt().user("hi").call().content())).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
        assertThat(ollama.count("/api/chat")).isEqualTo(before);
        assertThat(clients.testConnection(provider.id(), admin).success()).isTrue();

        // 삭제 → 호출 차단
        service.delete(provider.id());
        assertThatThrownBy(() -> clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin)).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROVIDER_NOT_FOUND);
    }

    @Test
    @DisplayName("오류 분류 : 모델 없음(404) · 서버 오류(500, 재시도 없음) · 리다이렉트(따라가지 않음) · 잘못된 응답 · 연결 거부 · 시간 초과. 서버 응답 원문은 문구에 넣지 않는다")
    void failures() throws Exception {
        FakeOllama ollama = ollama();
        rule("127.0.0.1", "127.0.0.1/32", ollama.port());
        AiProviderDto.Provider provider = create("오류", ollama.url(), "missing-model");

        ollama.reply("/api/chat", 404, "{\"error\":\"model 'missing-model' not found at /secret/path\"}");
        AiProviderDto.PromptResult missing = clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin);
        assertThat(missing.error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::status).containsExactly(Failure.MODEL_NOT_FOUND, 404);
        assertThat(missing.error().message()).doesNotContain("/secret/path");

        ollama.reply("/api/chat", 500, "{\"error\":\"out of memory /secret/path\"}");
        long before = ollama.count("/api/chat");
        AiProviderDto.PromptResult serverError = clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin);
        assertThat(serverError.error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::status).containsExactly(Failure.SERVER_ERROR, 500);
        assertThat(serverError.error().message()).doesNotContain("/secret/path");
        assertThat(ollama.count("/api/chat") - before).as("Spring AI 의 자동 재시도를 쓰지 않는다").isEqualTo(1);
        assertThatThrownBy(() -> clients.call(provider.id(), null, admin, client -> client.prompt().user("hi").call().content()))
                .extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_CALL_FAILED);

        // 모델 목록의 404 는 Ollama 가 아닌 서버로 본다
        ollama.reply("/api/tags", 404, "{}");
        assertThat(clients.testConnection(provider.id(), admin).error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::status)
                .containsExactly(Failure.SERVER_ERROR, 404);

        // 리다이렉트 : 따라가지 않는다
        FakeOllama target = ollama();
        target.reply("/api/tags", 200, TAGS);
        ollama.replies.put("/api/tags", new FakeOllama.Reply(302, "", 0, target.url() + "/api/tags"));
        assertThat(clients.testConnection(provider.id(), admin).error()).extracting(AiProviderDto.Error::failure, AiProviderDto.Error::status)
                .containsExactly(Failure.SERVER_ERROR, 302);
        assertThat(target.requests).isEmpty();

        // 응답을 해석하지 못함 : 연결 테스트는 실제 응답을 해석해야 성공이다
        ollama.reply("/api/tags", 200, "<html>proxy login</html>");
        assertThat(clients.testConnection(provider.id(), admin).error().failure()).isEqualTo(Failure.INVALID_RESPONSE);
        ollama.reply("/api/tags", 200, "{}");
        assertThat(clients.testConnection(provider.id(), admin).error().failure()).isEqualTo(Failure.INVALID_RESPONSE);

        // 시간 초과 : 연결 테스트는 짧은 제한 시간(테스트 1초)
        ollama.replies.put("/api/tags", new FakeOllama.Reply(200, TAGS, 2500, null));
        AiProviderDto.ConnectionTest slow = clients.testConnection(provider.id(), admin);
        assertThat(slow.error().failure()).isEqualTo(Failure.TIMEOUT);
        assertThat(slow.elapsedMillis()).isLessThan(2400);
        ollama.replies.put("/api/chat", new FakeOllama.Reply(200, chat("missing-model", "late"), 4000, null));
        assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin).error().failure()).as("호출 응답 제한(테스트 3초)")
                .isEqualTo(Failure.TIMEOUT);

        // 연결 거부 : 허용된 포트에 서버가 없다
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        rule("127.0.0.1", "127.0.0.1/32", closedPort);
        AiProviderDto.Provider refused = create("거부", "http://127.0.0.1:" + closedPort, null);
        assertThat(clients.testConnection(refused.id(), admin).error().failure()).isEqualTo(Failure.CONNECTION_REFUSED);
        assertThat(rules.attempts("AI_HTTP", null, "127.0.0.1", null, null, null, 0, 50).rows()).filteredOn(a -> a.port() == closedPort)
                .extracting(ExternalConnectionDto.Attempt::result).containsExactly(ExternalConnectionDto.Result.FAILED);
    }

    @Test
    @DisplayName("스트리밍 : Spring AI 의 WebClient 경로는 SEC-1 전송이 아니라 보내지 않는다 (기본 HTTP 클라이언트로 우회하지 않는다)")
    void streamingIsNotSent() throws Exception {
        FakeOllama ollama = ollama();
        ollama.reply("/api/chat", 200, chat("llama3.2", "x"));
        rule("127.0.0.1", "127.0.0.1/32", ollama.port());
        AiProviderDto.Provider provider = create("스트리밍", ollama.url(), "llama3.2");
        long attempts = attempts(ollama);
        assertThatThrownBy(() -> clients.call(provider.id(), null, admin, client -> client.prompt().user("hi").stream().content().collectList().block()))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_UNSUPPORTED);
        assertThat(ollama.requests).as("스트리밍 요청이 서버에 가지 않았다").isEmpty();
        assertThat(attempts(ollama)).as("외부 연결 준비(SEC-1 판정·시도 기록) 전에 막혀 ALLOWED 기록이 생기지 않는다").isEqualTo(attempts);
        // 같은 Provider 의 일반 호출(RestClient 경로)은 SEC-1 전송으로 나간다
        assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin).success()).isTrue();
        assertThat(ollama.requests).hasSize(1);
        assertThat(rules.attempts("AI_HTTP", null, "127.0.0.1", null, null, null, 0, 100).rows()).filteredOn(a -> a.port() == ollama.port())
                .extracting(ExternalConnectionDto.Attempt::result).as("일반 호출은 한 시도 한 행 (CONNECTED)").containsExactly(ExternalConnectionDto.Result.CONNECTED);
    }

    @Test
    @DisplayName("스트리밍 차단 분류 : 지원하지 않는 기능(UNSUPPORTED)으로 나눈다")
    void streamingFailureIsUnsupported() throws Exception {
        FakeOllama ollama = ollama();
        rule("127.0.0.1", "127.0.0.1/32", ollama.port());
        AiProviderDto.Provider provider = create("스트리밍 분류", ollama.url(), "llama3.2");
        assertThatThrownBy(() -> clients.call(provider.id(), null, admin, client -> client.prompt().user("hi").stream().content().blockLast()))
                .hasMessageContaining(com.lexorma.common.i18n.Messages.get("이 Provider 에서 지원하지 않는 AI 기능입니다 (스트리밍 등)."));
        assertThat(ollama.requests).isEmpty();

        // 허용 규칙이 없어도 스트리밍은 SEC-1 판정 전에 막힌다 (DENIED 기록도 생기지 않는다)
        FakeOllama unruled = ollama();
        AiProviderDto.Provider noRule = create("스트리밍 규칙 없음", unruled.url(), "llama3.2");
        assertThatThrownBy(() -> clients.call(noRule.id(), null, admin, client -> client.prompt().user("hi").stream().content().blockLast()))
                .extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_UNSUPPORTED);
        assertThat(attempts(unruled)).isZero();
        assertThat(clients.prompt(noRule.id(), new AiProviderDto.PromptRequest("hi", null), admin).error().failure()).as("일반 호출은 그대로 판정·차단된다")
                .isEqualTo(Failure.BLOCKED);
        assertThat(attempts(unruled)).isEqualTo(1);
    }

    @Test
    @DisplayName("리소스 : 반복 호출(정상·모델 목록·연결 테스트·연결 거부·시간 초과·설정 변경)에도 파일 디스크립터·HTTP 클라이언트 스레드가 쌓이지 않는다 (요청마다 연 연결을 닫는다)")
    void repeatedCallsReleaseResources() throws Exception {
        FakeOllama ollama = ollama();
        ollama.reply("/api/tags", 200, TAGS);
        ollama.reply("/api/chat", 200, chat("llama3.2:latest", "ok"));
        rule("127.0.0.1", "127.0.0.1/32", ollama.port());
        AiProviderDto.Provider provider = create("반복", ollama.url(), "llama3.2");
        FakeOllama slow = ollama();
        slow.replies.put("/api/tags", new FakeOllama.Reply(200, TAGS, 2500, null));
        rule("127.0.0.1", "127.0.0.1/32", slow.port());
        AiProviderDto.Provider timeout = create("반복 시간 초과", slow.url(), null);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        rule("127.0.0.1", "127.0.0.1/32", closedPort);
        AiProviderDto.Provider refused = create("반복 거부", "http://127.0.0.1:" + closedPort, null);

        // 첫 호출(클래스 적재 등) 뒤부터 잰다
        clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin);
        clients.testConnection(refused.id(), admin);
        Thread.sleep(300);
        long fdBefore = openFileDescriptors();
        long jdkClientsBefore = jdkHttpClientThreads();

        for (int i = 0; i < 30; i++) {
            assertThat(clients.testConnection(provider.id(), admin).success()).isTrue();
            assertThat(clients.models(provider.id(), admin).success()).isTrue();
            assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi " + i, null), admin).success()).isTrue();
            String content = clients.call(provider.id(), null, admin, client -> client.prompt().user("hi").call().content());
            assertThat(content).isEqualTo("ok");
            assertThat(clients.testConnection(refused.id(), admin).error().failure()).isEqualTo(Failure.CONNECTION_REFUSED);
        }
        for (int i = 0; i < 3; i++) assertThat(clients.testConnection(timeout.id(), admin).error().failure()).isEqualTo(Failure.TIMEOUT);
        service.update(provider.id(), new ProviderRequest(provider.name(), "OLLAMA", ollama.url(), "qwen3:8b", true, null));
        assertThat(clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin).success()).isTrue();
        service.update(provider.id(), new ProviderRequest(provider.name(), "OLLAMA", ollama.url(), "qwen3:8b", false, null));
        assertThatThrownBy(() -> clients.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), admin)).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);

        Thread.sleep(3000); // 시간 초과로 끊긴 요청의 서버 쪽 처리(2.5초)가 끝나 서버 소켓도 닫히도록
        assertThat(jdkHttpClientThreads() - jdkClientsBefore).as("쓰지 않는 기본 HTTP 클라이언트(JDK HttpClient)를 요청마다 만들지 않는다").isLessThanOrEqualTo(0);
        assertThat(openFileDescriptors() - fdBefore).as("요청 160여 번 뒤 열린 파일 디스크립터(소켓)가 쌓이지 않는다").isLessThan(20);
    }

    private static long openFileDescriptors() {
        return ((com.sun.management.UnixOperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getOpenFileDescriptorCount();
    }

    /** JDK HttpClient 는 클라이언트마다 SelectorManager 스레드를 띄운다 */
    private static long jdkHttpClientThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().matches("HttpClient-\\d+-SelectorManager")).count();
    }

    @Test
    @DisplayName("권한 : Provider 관리·연결 테스트·모델 목록·테스트 프롬프트는 ADMIN 만 (MANAGER·DBA·USER·VIEWER 는 403)")
    void adminOnly() {
        AiProviderDto.Provider provider = create("권한", "http://127.0.0.1:11434", null);
        for (String role : List.of("ROLE_MANAGER", "ROLE_DBA", "ROLE_USER", "ROLE_VIEWER")) {
            Account other = account(role);
            Project own = project(other);
            AccountDto principal = SecurityTestSupport.loginAs(projectRepository, own.getId(), String.valueOf(other.getId()), role);
            assertThatThrownBy(() -> api.list()).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.get(provider.id())).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.create(new ProviderRequest("x", "OLLAMA", "http://127.0.0.1:11434", null, true, null))).as(role)
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.update(provider.id(), new ProviderRequest("x", "OLLAMA", "http://127.0.0.1:11434", null, true, null))).as(role)
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.delete(provider.id())).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.test(provider.id(), principal)).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.models(provider.id(), principal)).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.prompt(provider.id(), new AiProviderDto.PromptRequest("hi", null), principal)).as(role)
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThat(service.get(provider.id()).name()).isEqualTo(provider.name());
    }

    // ------------------------------------------------------------------ 준비

    private FakeOllama ollama() throws IOException {
        FakeOllama server = new FakeOllama();
        servers.add(server);
        return server;
    }

    private AiProviderDto.Provider create(String name, String url, String model) {
        AiProviderDto.Provider provider = service.create(new ProviderRequest(name + " " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", url, model, true, null));
        createdProviders.add(provider.id());
        return provider;
    }

    private void rule(String host, String cidr, int port) {
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", host, cidr, port, true, "AI-3 테스트")).id());
    }

    private long attempts(FakeOllama ollama) {
        return rules.attempts("AI_HTTP", null, "127.0.0.1", null, null, null, 0, 100).rows().stream().filter(a -> a.port() == ollama.port()).count();
    }

    private void assertInvalid(ProviderRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.create(request)).as(String.valueOf(request)).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(code);
    }

    private Account account(String role) {
        String username = "ai3-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(Account owner) {
        Project project = projectRepository.save(Project.builder().projectName("ai3-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        return project;
    }
}
