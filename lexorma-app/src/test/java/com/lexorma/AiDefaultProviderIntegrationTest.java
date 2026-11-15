package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.dto.AiProviderDto.DefaultProviderRequest;
import com.lexorma.core.ai.domain.dto.AiProviderDto.DefaultStatus;
import com.lexorma.core.ai.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.core.ai.service.AiDefaultProviderService;
import com.lexorma.core.ai.service.AiProviderClients;
import com.lexorma.core.ai.service.AiProviderService;
import com.lexorma.core.execution.ExecutionContext;
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
import com.lexorma.system.ai.controller.AiDefaultProviderRestController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 시스템 기본 AI Provider (H2 / PostgreSQL) : 최대 하나·미지정 허용·명시 지정만, 지정 조건(활성·기본 모델), 비활성화·삭제 정책, 기본 모델 즉시 반영,
 * 다른 Provider 로 바꿔 부르지 않음, 관리 과정에서 외부 연결 없음, 동시 요청 정합성, 업그레이드(기존 Provider 가 있어도 미지정), ADMIN 권한.
 * AI 서버는 AiProviderIntegrationTest 의 가짜 Ollama 를 쓴다. 테스트 컨텍스트는 가장 흔한 구성(NONE + test)을 그대로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiDefaultProviderIntegrationTest {

    @Autowired AiDefaultProviderService defaults;
    @Autowired AiProviderService providers;
    @Autowired AiProviderClients clients;
    @Autowired AiDefaultProviderRestController api;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdProviders = new ArrayList<>();
    private final List<Long> createdRules = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();
    private ExecutionContext admin;

    @BeforeEach
    void login() {
        Account account = account("ROLE_ADMIN");
        Project project = project(account);
        AccountDto principal = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(account.getId()), "ROLE_ADMIN");
        admin = ExecutionContext.user(principal);
        defaults.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        defaults.clear();
        for (Long id : createdProviders) {
            try {
                providers.delete(id);
            } catch (CustomException ignored) {
                // 테스트에서 이미 지웠다
            }
        }
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("정책 : 미지정 허용(설정 행 하나), 등록·연결 성공으로 자동 지정 없음, 지정·변경·재지정(멱등)·해제, 지정 조건(없음·비활성·기본 모델 없음은 거부)")
    void selectionPolicy() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from TB_AI_STNG", Integer.class)).as("설정 행은 하나").isEqualTo(1);
        assertThat(defaults.get()).extracting(AiProviderDto.DefaultProvider::status, AiProviderDto.DefaultProvider::provider)
                .containsExactly(DefaultStatus.NOT_CONFIGURED, null);
        assertThatThrownBy(() -> defaults.resolve()).extracting("errorCode").isEqualTo(ErrorCode.AI_DEFAULT_PROVIDER_NOT_CONFIGURED);

        AiProviderIntegrationTest.FakeOllama ollama = ollama();
        ollama.reply("/api/tags", 200, AiProviderIntegrationTest.TAGS);
        rule(ollama.port());
        AiProviderDto.Provider a = create("A", ollama.url(), "llama3.2", true);
        assertThat(clients.testConnection(a.id(), admin).success()).isTrue();
        assertThat(clients.models(a.id(), admin).success()).isTrue();
        assertThat(defaults.get().status()).as("첫 등록·연결 테스트·모델 목록으로 자동 지정하지 않는다").isEqualTo(DefaultStatus.NOT_CONFIGURED);

        AiProviderDto.DefaultProvider set = defaults.set(new DefaultProviderRequest(a.id()));
        assertThat(set.status()).isEqualTo(DefaultStatus.READY);
        assertThat(set.provider().id()).isEqualTo(a.id());
        assertThat(defaults.set(new DefaultProviderRequest(a.id())).provider().id()).as("같은 Provider 재지정은 그대로 성공").isEqualTo(a.id());
        assertThat(defaults.resolve()).extracting(AiProviderDto.DefaultTarget::providerId, AiProviderDto.DefaultTarget::model, AiProviderDto.DefaultTarget::enabled)
                .containsExactly(a.id(), "llama3.2", true);

        AiProviderDto.Provider b = create("B", ollama.url(), "qwen3:8b", true);
        assertThat(defaults.get().provider().id()).as("다른 Provider 를 등록해도 기본은 그대로").isEqualTo(a.id());
        assertThat(defaults.set(new DefaultProviderRequest(b.id())).provider().id()).isEqualTo(b.id());
        assertThat(jdbc.queryForObject("select count(*) from TB_AI_STNG where BSC_AI_PRVDR_SN is not null", Integer.class)).isEqualTo(1);

        // 지정 조건
        AiProviderDto.Provider disabled = create("비활성", ollama.url(), "llama3.2", false);
        AiProviderDto.Provider noModel = create("모델 없음", ollama.url(), null, true);
        assertThatThrownBy(() -> defaults.set(new DefaultProviderRequest(disabled.id()))).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
        assertThatThrownBy(() -> defaults.set(new DefaultProviderRequest(noModel.id()))).extracting("errorCode").isEqualTo(ErrorCode.AI_DEFAULT_PROVIDER_MODEL_MISSING);
        assertThatThrownBy(() -> defaults.set(new DefaultProviderRequest(Long.MAX_VALUE))).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_NOT_FOUND);
        assertThatThrownBy(() -> defaults.set(new DefaultProviderRequest(null))).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_INVALID);
        assertThat(defaults.get().provider().id()).as("실패한 지정은 기존 기본을 바꾸지 않는다").isEqualTo(b.id());

        assertThat(defaults.clear().status()).isEqualTo(DefaultStatus.NOT_CONFIGURED);
        assertThat(defaults.clear().status()).as("해제도 멱등").isEqualTo(DefaultStatus.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("외부 연결 없음 : 조회·지정·해제·resolve 는 AI 서버에 연결하지 않고 SEC-1 판정도 하지 않는다 (서버가 꺼져 있고 허용 규칙이 없어도 지정된다). 허용 규칙도 바뀌지 않는다")
    void managementNeverConnects() throws Exception {
        AiProviderIntegrationTest.FakeOllama ollama = ollama();
        AiProviderDto.Provider a = create("연결 없음", ollama.url(), "llama3.2", true);
        AiProviderDto.Provider down = create("꺼진 서버", "http://127.0.0.1:9", "llama3.2", true);
        long attempts = attempts();
        int rulesBefore = rules.list("AI_HTTP", null).size();

        defaults.get();
        defaults.set(new DefaultProviderRequest(down.id()));
        defaults.resolve();
        defaults.set(new DefaultProviderRequest(a.id()));
        defaults.resolve();
        defaults.get();
        defaults.clear();

        assertThat(ollama.requests).isEmpty();
        assertThat(attempts()).as("연결 시도 기록이 없다").isEqualTo(attempts);
        assertThat(rules.list("AI_HTTP", null)).as("허용 규칙을 만들지 않는다").hasSize(rulesBefore);
    }

    @Test
    @DisplayName("callDefault : 기본 Provider·기본 모델로 부르고, 변경·모델 변경은 다음 요청부터. 비활성화하면 지정은 남고 호출만 막힌다 (다른 활성 Provider 로 바꾸지 않음), 다시 켜면 그대로. 모델을 지우면 실패")
    void callDefaultFollowsPolicy() throws Exception {
        AiProviderIntegrationTest.FakeOllama first = ollama();
        first.reply("/api/chat", 200, AiProviderIntegrationTest.chat("llama3.2", "from-A"));
        AiProviderIntegrationTest.FakeOllama second = ollama();
        second.reply("/api/chat", 200, AiProviderIntegrationTest.chat("qwen3:8b", "from-B"));
        rule(first.port());
        rule(second.port());
        AiProviderDto.Provider a = create("호출 A", first.url(), "llama3.2", true);
        AiProviderDto.Provider b = create("호출 B", second.url(), "qwen3:8b", true);

        assertThatThrownBy(() -> callDefault()).extracting("errorCode").isEqualTo(ErrorCode.AI_DEFAULT_PROVIDER_NOT_CONFIGURED);
        defaults.set(new DefaultProviderRequest(a.id()));
        assertThat(callDefault()).isEqualTo("from-A");
        assertThat(last(first)).contains("\"model\":\"llama3.2\"");

        providers.update(a.id(), new ProviderRequest(a.name(), "OLLAMA", first.url(), "llama3.2:3b", true, null));
        callDefault();
        assertThat(last(first)).as("기본 모델 변경은 다음 요청부터").contains("\"model\":\"llama3.2:3b\"");

        defaults.set(new DefaultProviderRequest(b.id()));
        assertThat(callDefault()).as("기본 Provider 변경은 재시작 없이 다음 요청부터").isEqualTo("from-B");

        long beforeA = first.count("/api/chat");
        long beforeB = second.count("/api/chat");
        providers.update(b.id(), new ProviderRequest(b.name(), "OLLAMA", second.url(), "qwen3:8b", false, null));
        assertThat(defaults.get()).extracting(AiProviderDto.DefaultProvider::status, d -> d.provider().id())
                .as("비활성화해도 지정은 남는다").containsExactly(DefaultStatus.DISABLED, b.id());
        assertThatThrownBy(() -> callDefault()).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
        assertThat(first.count("/api/chat")).as("활성인 A 로 바꿔 부르지 않는다").isEqualTo(beforeA);
        assertThat(second.count("/api/chat")).isEqualTo(beforeB);

        providers.update(b.id(), new ProviderRequest(b.name(), "OLLAMA", second.url(), "qwen3:8b", true, null));
        assertThat(callDefault()).as("다시 켜면 재지정 없이 쓴다").isEqualTo("from-B");

        providers.update(b.id(), new ProviderRequest(b.name(), "OLLAMA", second.url(), null, true, null));
        assertThat(defaults.get().status()).isEqualTo(DefaultStatus.MODEL_MISSING);
        assertThatThrownBy(() -> callDefault()).extracting("errorCode").isEqualTo(ErrorCode.AI_DEFAULT_PROVIDER_MODEL_MISSING);
        assertThat(second.count("/api/chat")).as("다른 모델을 골라 부르지 않는다").isEqualTo(beforeB + 1);
    }

    @Test
    @DisplayName("삭제 : 기본 Provider 는 지울 수 없다 (서비스 AI_PROVIDER_IN_USE, DB FK 도 막는다). 해제하거나 다른 Provider 를 지정한 뒤에는 지울 수 있다")
    void deletePolicy() throws Exception {
        AiProviderDto.Provider a = create("삭제 A", "http://127.0.0.1:11434", "llama3.2", true);
        AiProviderDto.Provider b = create("삭제 B", "http://127.0.0.1:11434", "llama3.2", true);
        defaults.set(new DefaultProviderRequest(a.id()));
        assertThatThrownBy(() -> providers.delete(a.id())).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_IN_USE);
        assertThat(providers.get(a.id()).id()).isEqualTo(a.id());
        assertThatThrownBy(() -> jdbc.update("delete from TB_AI_PRVDR where AI_PRVDR_SN = ?", a.id())).as("FK_AI_STNG_01")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into TB_AI_STNG(AI_STNG_SN, BSC_AI_PRVDR_SN) values (2, null)")).as("CK_AI_STNG_01 : 행은 하나")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        defaults.set(new DefaultProviderRequest(b.id()));
        providers.delete(a.id());
        defaults.clear();
        providers.delete(b.id());
        assertThat(defaults.get().status()).isEqualTo(DefaultStatus.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("동시성 : 서로 다른 지정·지정과 해제·지정과 삭제·지정과 비활성화를 동시에 보내도 기본은 최대 하나이고, 참조는 늘 있는 Provider 이며, 실패한 요청은 아무것도 남기지 않는다")
    void concurrentRequests() throws Exception {
        String url = "http://127.0.0.1:11434";
        AiProviderDto.Provider a = create("동시 A", url, "llama3.2", true);
        AiProviderDto.Provider b = create("동시 B", url, "llama3.2", true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 15; i++) {
                List<Object> results = race(pool, () -> defaults.set(new DefaultProviderRequest(a.id())), () -> defaults.set(new DefaultProviderRequest(b.id())));
                assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(AiProviderDto.DefaultProvider.class));
                assertThat(defaultId()).isIn(a.id(), b.id());
                assertSingleConsistent();
            }
            for (int i = 0; i < 15; i++) {
                race(pool, () -> defaults.set(new DefaultProviderRequest(a.id())), () -> defaults.clear());
                assertThat(defaultId()).satisfiesAnyOf(id -> assertThat(id).isNull(), id -> assertThat(id).isEqualTo(a.id()));
                assertSingleConsistent();
            }
            for (int i = 0; i < 8; i++) {
                defaults.clear();
                AiProviderDto.Provider victim = create("동시 삭제 " + i, url, "llama3.2", true);
                List<Object> results = race(pool, () -> defaults.set(new DefaultProviderRequest(victim.id())), () -> {
                    providers.delete(victim.id());
                    return "deleted";
                });
                boolean setWon = results.get(0) instanceof AiProviderDto.DefaultProvider;
                boolean deleteWon = "deleted".equals(results.get(1));
                assertThat(setWon ^ deleteWon).as("지정과 삭제 중 하나만 성공 : " + results).isTrue();
                if (setWon) {
                    assertThat(results.get(1)).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_IN_USE);
                    assertThat(defaultId()).isEqualTo(victim.id());
                } else {
                    assertThat(results.get(0)).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_NOT_FOUND);
                    assertThat(defaultId()).isNull();
                }
                assertSingleConsistent();
            }
            for (int i = 0; i < 8; i++) {
                defaults.clear();
                AiProviderDto.Provider target = create("동시 비활성 " + i, url, "llama3.2", true);
                List<Object> results = race(pool, () -> defaults.set(new DefaultProviderRequest(target.id())),
                        () -> providers.update(target.id(), new ProviderRequest(target.name(), "OLLAMA", url, "llama3.2", false, null)));
                assertThat(results.get(1)).as("비활성화는 늘 된다").isInstanceOf(AiProviderDto.Provider.class);
                if (results.get(0) instanceof AiProviderDto.DefaultProvider) {
                    assertThat(defaults.get()).extracting(d -> d.provider().id(), AiProviderDto.DefaultProvider::status)
                            .as("지정이 먼저면 지정은 남고 비활성으로 보인다").containsExactly(target.id(), DefaultStatus.DISABLED);
                } else {
                    assertThat(results.get(0)).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
                    assertThat(defaultId()).isNull();
                }
                assertSingleConsistent();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "업그레이드 {0} : V8 에 Provider 가 있어도 V9 뒤 기본 Provider 는 미지정, Provider 는 그대로")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradeStartsUnset(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "ai_default_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v8 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("8").cleanDisabled(false).load();
        try {
            v8.migrate();
            JdbcTemplate db = new JdbcTemplate(dataSource);
            String s = "\"" + schema + "\".";
            db.update("insert into " + s + "TB_AI_PRVDR(AI_PRVDR_SN, PRVDR_NM, PRVDR_TYPE_CD, BSC_URL_ADDR, BSC_MDL_NM, ACTVTN_YN) values (1, 'existing', 'OLLAMA', 'http://127.0.0.1:11434', 'llama3.2', true)");
            Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(db.queryForList("select BSC_AI_PRVDR_SN from " + s + "TB_AI_STNG", Long.class)).containsExactly((Long) null);
            assertThat(db.queryForObject("select PRVDR_NM from " + s + "TB_AI_PRVDR where AI_PRVDR_SN = 1", String.class)).isEqualTo("existing");
        } finally {
            v8.clean();
        }
    }

    @Test
    @DisplayName("권한 : 기본 Provider 조회·지정·해제는 ADMIN 만 (MANAGER·DBA·USER·VIEWER 는 403)")
    void adminOnly() {
        AiProviderDto.Provider a = create("권한", "http://127.0.0.1:11434", "llama3.2", true);
        assertThat(api.set(new DefaultProviderRequest(a.id())).getBody().provider().id()).isEqualTo(a.id());
        for (String role : List.of("ROLE_MANAGER", "ROLE_DBA", "ROLE_USER", "ROLE_VIEWER")) {
            Account other = account(role);
            SecurityTestSupport.loginAs(projectRepository, project(other).getId(), String.valueOf(other.getId()), role);
            assertThatThrownBy(() -> api.get()).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.set(new DefaultProviderRequest(a.id()))).as(role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> api.clear()).as(role).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(defaults.get().provider().id()).isEqualTo(a.id());
    }

    // ------------------------------------------------------------------ 준비

    /** 두 작업을 동시에 시작한다. 결과 또는 던진 예외를 순서대로 돌려준다 */
    private static List<Object> race(ExecutorService pool, Callable<Object> first, Callable<Object> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    return task.call();
                } catch (CustomException e) {
                    return e;
                }
            }));
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
        return results;
    }

    private Long defaultId() {
        return jdbc.queryForObject("select BSC_AI_PRVDR_SN from TB_AI_STNG where AI_STNG_SN = 1", Long.class);
    }

    /** 설정 행은 하나, 참조는 비었거나 있는 Provider */
    private void assertSingleConsistent() {
        assertThat(jdbc.queryForObject("select count(*) from TB_AI_STNG", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_AI_STNG s where s.BSC_AI_PRVDR_SN is not null"
                + " and not exists (select 1 from TB_AI_PRVDR p where p.AI_PRVDR_SN = s.BSC_AI_PRVDR_SN)", Integer.class)).isZero();
    }

    private String callDefault() {
        return clients.callDefault(admin, client -> client.prompt().user("hi").call().content());
    }

    private static String last(AiProviderIntegrationTest.FakeOllama server) {
        return server.requests.get(server.requests.size() - 1);
    }

    private long attempts() {
        return jdbc.queryForObject("select count(*) from TB_EXTRL_CNTN_HSTRY where CNTN_TYPE_CD = 'AI_HTTP'", Long.class);
    }

    private AiProviderIntegrationTest.FakeOllama ollama() throws Exception {
        AiProviderIntegrationTest.FakeOllama server = new AiProviderIntegrationTest.FakeOllama();
        servers.add(server);
        return server;
    }

    private AiProviderDto.Provider create(String name, String url, String model, boolean enabled) {
        AiProviderDto.Provider provider = providers.create(new ProviderRequest(name + " " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", url, model, enabled, null));
        createdProviders.add(provider.id());
        return provider;
    }

    private void rule(int port) {
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", port, true, "기본 Provider 테스트")).id());
    }

    private Account account(String role) {
        String username = "aid-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(Account owner) {
        Project project = projectRepository.save(Project.builder().projectName("aid-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        return project;
    }
}
