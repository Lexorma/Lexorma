package com.lexorma;

import com.lexorma.core.util.AuthUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계정당 동시 로그인 1개 (나중 로그인 우선) + Spring Session JDBC 재로그인 (8차 P0). 실제 서버(랜덤 포트, 세션·CSRF)로 :
 * 같은 계정의 유효 세션이 여러 개 남아 있는 DB(동시 로그인 경쟁 등으로 생긴다)에서 이미 로그인한 브라우저가 다시 로그인하면, 예전에는 Spring Security 의
 * {@code ConcurrentSessionControlAuthenticationStrategy} 가 "남은 세션 수 − 1 + 1" 개 = 현재 세션까지 모두 만료시켜, 로그인 응답은 200 인데 새 세션이 인증되지 않았다.
 * 이제 로그인 성공한 세션은 언제나 인증된 상태이고, 같은 계정의 다른 세션은 모두 만료(DUPLICATE_LOGIN)된다.
 * DB 는 이 클래스 전용 (H2 별도 인메모리 / PostgreSQL 별도 스키마).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionReloginHttpTest {

    private static final String CONFIGURED_URL = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
    private static final boolean POSTGRESQL = CONFIGURED_URL != null && CONFIGURED_URL.startsWith("jdbc:postgresql:");
    private static final String SCHEMA = "relogin_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Map<String, String> DB_PROPERTIES = POSTGRESQL
            ? Map.of("spring.datasource.url", CONFIGURED_URL + (CONFIGURED_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                     "spring.flyway.default-schema", SCHEMA)
            : Map.of("spring.datasource.url", "jdbc:h2:mem:lexorma-relogin;DB_CLOSE_DELAY=-1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB_PROPERTIES.forEach((key, value) -> registry.add(key, () -> value));
    }

    private static final String PASSWORD = "Relogin!Pass12";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Spring Session 이 만료 표시로 세션에 두는 속성 (SpringSessionBackedSessionInformation.EXPIRED_ATTR) */
    private static final String EXPIRED_ATTR = "org.springframework.session.security.SpringSessionBackedSessionInformation.EXPIRED";

    /** AuthUtil 의 static 저장소를 이 클래스의 컨텍스트가 바꾸므로 끝나면 되돌린다 (ProjectAccessRevokeTest 와 같은 이유) */
    private static final java.lang.reflect.Field AUTH_UTIL_REPOSITORY;
    private static final Object PREVIOUS_AUTH_UTIL_REPOSITORY;

    static {
        try {
            AUTH_UTIL_REPOSITORY = AuthUtil.class.getDeclaredField("projectRepository");
            AUTH_UTIL_REPOSITORY.setAccessible(true);
            PREVIOUS_AUTH_UTIL_REPOSITORY = AUTH_UTIL_REPOSITORY.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Browser admin;
    private long projectId;
    private long otherProjectId;
    private long memberSeq;

    @BeforeAll
    void prepare() throws Exception {
        admin = new Browser();
        assertThat(admin.postJson("/api/login/password",
                "{\"username\":\"admin\",\"password\":\"admin\",\"newPassword\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(200);
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        projectId = createProject();
        otherProjectId = createProject();
    }

    @AfterAll
    void restore() throws Exception {
        if (PREVIOUS_AUTH_UTIL_REPOSITORY != null) AUTH_UTIL_REPOSITORY.set(null, PREVIOUS_AUTH_UTIL_REPOSITORY);
        if (POSTGRESQL) jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @ParameterizedTest(name = "유효 세션 {0}개가 남은 계정 : 이미 로그인한 브라우저의 재로그인·다른 브라우저 로그인 모두 인증 상태, 나머지는 만료")
    @ValueSource(ints = {0, 1, 5, 19})
    void reloginWithLeftoverSessions(int leftovers) throws Exception {
        String user = user();
        Browser mine = new Browser();
        assertThat(mine.login(user, projectId).statusCode()).isEqualTo(200);
        assertThat(mine.authenticated()).isTrue();
        // 남은 세션 : 다른 브라우저들의 로그인 (차례로 로그인하면 앞의 것이 만료 표시되므로, 경쟁으로 남은 것처럼 만료 표시를 지운다)
        List<Browser> others = new ArrayList<>();
        for (int i = 0; i < leftovers; i++) {
            Browser other = new Browser();
            assertThat(other.login(user, projectId).statusCode()).isEqualTo(200);
            others.add(other);
        }
        clearExpired(user);
        assertThat(validSessions(user)).isEqualTo(leftovers + 1);

        // 이미 로그인한 브라우저에서 다시 로그인 (다른 프로젝트로) : 200 이면 그 세션은 실제로 인증되어 있어야 한다
        HttpResponse<String> relogin = mine.login(user, otherProjectId);
        assertThat(relogin.statusCode()).isEqualTo(200);
        assertThat(mine.authenticated()).as("재로그인한 세션은 인증 상태").isTrue();
        assertThat(mine.me().path("user").path("projectId").asLong()).isEqualTo(otherProjectId);
        assertThat(mine.get("/api/schema").statusCode()).isEqualTo(200);
        for (Browser other : others) {
            HttpResponse<String> expired = other.get("/api/schema");
            assertThat(expired.statusCode()).isEqualTo(401);
            assertThat(code(expired)).isEqualTo("DUPLICATE_LOGIN");
        }
        assertThat(validSessions(user)).as("계정당 유효 세션 1개").isOne();

        // 다른 브라우저에서 새로 로그인 (유효 세션이 다시 여럿 남은 상태) : 새 브라우저만 인증, 앞의 것은 만료
        List<Browser> stale = new ArrayList<>();
        for (int i = 0; i < leftovers; i++) {
            Browser other = new Browser();
            other.login(user, projectId);
            stale.add(other);
        }
        clearExpired(user);
        Browser fresh = new Browser();
        assertThat(fresh.login(user, projectId).statusCode()).isEqualTo(200);
        assertThat(fresh.authenticated()).isTrue();
        assertThat(mine.get("/api/schema").statusCode()).isEqualTo(401);
        for (Browser other : stale) assertThat(other.get("/api/schema").statusCode()).isEqualTo(401);
        assertThat(validSessions(user)).isOne();
    }

    @Test
    @DisplayName("만료 표시된 세션과 유효 세션이 섞여 있어도 재로그인 세션은 인증 상태, 같은 브라우저에서 같은 프로젝트로 다시 로그인해도 같다")
    void mixedExpiredAndValid() throws Exception {
        String user = user();
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Browser browser = new Browser();
            browser.login(user, projectId);
            browsers.add(browser);
        }
        // 앞의 5개는 만료 표시된 상태(차례로 로그인), 그중 2개의 표시만 지워 유효로 만든다
        List<String> sessionIds = jdbcTemplate.queryForList("select SESSION_ID from SPRING_SESSION where PRINCIPAL_NAME = ? order by CREATION_TIME", String.class, user);
        assertThat(validSessions(user)).isOne();
        for (String id : sessionIds.subList(0, 2)) {
            jdbcTemplate.update("delete from SPRING_SESSION_ATTRIBUTES where ATTRIBUTE_NAME = ? and SESSION_PRIMARY_ID = (select PRIMARY_ID from SPRING_SESSION where SESSION_ID = ?)", EXPIRED_ATTR, id);
        }
        assertThat(validSessions(user)).isEqualTo(3);
        Browser last = browsers.getLast();
        assertThat(last.login(user, projectId).statusCode()).isEqualTo(200);
        assertThat(last.authenticated()).isTrue();
        assertThat(last.login(user, projectId).statusCode()).as("한 번 더").isEqualTo(200);
        assertThat(last.authenticated()).isTrue();
        assertThat(validSessions(user)).isOne();
        for (Browser browser : browsers.subList(0, 5)) assertThat(browser.get("/api/schema").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("동시 로그인 경쟁 : 8개 브라우저가 한꺼번에 로그인해도 모두 성공 응답의 세션 상태가 일관되고, 그 뒤 한 브라우저가 다시 로그인하면 그것만 인증 상태로 남는다")
    void concurrentLogins() throws Exception {
        String user = user();
        int count = 8;
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < count; i++) browsers.add(new Browser());
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Browser browser : browsers) {
                results.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    return browser.login(user, projectId).statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) assertThat(result.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        // 경쟁 직후 : 유효 세션은 1개 이상 (서로의 커밋 전 세션을 볼 수 없어 여럿 남을 수 있다). 남은 세션은 모두 실제로 인증되어 있다
        int valid = validSessions(user);
        assertThat(valid).isBetween(1, count);
        int authenticated = 0;
        for (Browser browser : browsers) if (browser.authenticated()) authenticated++;
        assertThat(authenticated).as("유효 세션 = 인증된 브라우저").isEqualTo(valid);
        // 한 브라우저가 다시 로그인하면 그것만 남는다
        Browser winner = browsers.getFirst();
        assertThat(winner.login(user, projectId).statusCode()).isEqualTo(200);
        assertThat(winner.authenticated()).isTrue();
        assertThat(validSessions(user)).isOne();
        for (Browser browser : browsers.subList(1, count)) assertThat(browser.authenticated()).isFalse();
    }

    // ---------- 준비

    private int validSessions(String user) {
        return jdbcTemplate.queryForObject("select count(*) from SPRING_SESSION s where s.PRINCIPAL_NAME = ? and not exists "
                + "(select 1 from SPRING_SESSION_ATTRIBUTES a where a.SESSION_PRIMARY_ID = s.PRIMARY_ID and a.ATTRIBUTE_NAME = ?)", Integer.class, user, EXPIRED_ATTR);
    }

    /** 경쟁으로 유효 세션이 여럿 남은 DB 를 흉내 낸다 : 이 계정 세션의 만료 표시를 지운다 */
    private void clearExpired(String user) {
        jdbcTemplate.update("delete from SPRING_SESSION_ATTRIBUTES where ATTRIBUTE_NAME = ? and SESSION_PRIMARY_ID in (select PRIMARY_ID from SPRING_SESSION where PRINCIPAL_NAME = ?)",
                EXPIRED_ATTR, user);
    }

    private String user() throws Exception {
        String username = "rl" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpResponse<String> response = new Browser().postJson("/api/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"재로그인\",\"projectId\":" + projectId + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String accountId = null;
        for (JsonNode account : JSON.readTree(admin.get("/api/selectUser/data?page=0&size=1000").body()).path("data")) {
            if (username.equals(account.path("username").asString())) accountId = account.path("id").asString();
        }
        assertThat(admin.put("/api/saveUser/update", "[{\"id\":\"" + accountId + "\",\"role\":\"ROLE_USER\"}]").statusCode()).isEqualTo(200);
        // 다른 프로젝트에도 구성원으로 (프로젝트를 바꿔 다시 로그인)
        // 번호는 시퀀스가 쓰지 않는 범위에서 (시퀀스가 미리 받은 번호와 겹치지 않게)
        jdbcTemplate.update("insert into TB_PRJ_MBR(PRJ_MBR_SN, PRJ_SN, ACNT_SN, ACTVTN_YN) select ?, ?, ACNT_SN, true from TB_ACNT where USER_ID = ?",
                900_000_000L + memberSeq++, otherProjectId, username);
        return username;
    }

    private long createProject() throws Exception {
        String name = "RELOGIN_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(admin.postJson("/api/saveProject/project", """
                {"projectName":"%s","isActive":true,"isAutoActive":true,"isDicAbbrUsed":false,"tablePrefix":"","dbType":"POSTGRESQL"}
                """.formatted(name)).statusCode()).isEqualTo(200);
        for (JsonNode project : JSON.readTree(admin.get("/api/getProject/managed").body())) {
            if (name.equals(project.path("projectName").asString())) return project.path("id").asLong();
        }
        throw new AssertionError("생성한 프로젝트가 없습니다: " + name);
    }

    private static String code(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("code").asString();
    }

    /** 브라우저 하나 : 쿠키(SESSION, XSRF) + 메모리의 CSRF 토큰 (React 와 같은 방식) */
    private class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        private String csrf;

        Browser() throws Exception {
            refreshCsrf();
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            return client.send(builder.header("X-Requested-With", "XMLHttpRequest").build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        JsonNode me() throws Exception {
            return JSON.readTree(get("/api/auth/me").body());
        }

        boolean authenticated() throws Exception {
            HttpResponse<String> response = get("/api/auth/me");
            return response.statusCode() == 200 && JSON.readTree(response.body()).path("authenticated").asBoolean();
        }

        HttpResponse<String> postJson(String path, String json) throws Exception {
            return send(json(path).POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        }

        HttpResponse<String> put(String path, String json) throws Exception {
            return send(json(path).PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        }

        private HttpRequest.Builder json(String path) {
            return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf);
        }

        HttpResponse<String> login(String username, long project) throws Exception {
            refreshCsrf();
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD + "&project_id=" + project)));
            refreshCsrf(); // 로그인하면 CSRF 토큰이 바뀐다
            return response;
        }

        private void refreshCsrf() throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            csrf = JSON.readTree(response.body()).path("token").asString();
        }
    }
}
