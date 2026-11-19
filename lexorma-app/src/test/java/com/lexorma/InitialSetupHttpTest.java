package com.lexorma;

import com.lexorma.core.util.AuthUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최초 관리자 계정 만들기 (2026-11-19 : V1 에서 기본 계정 admin/admin 제거) : 실제 서버(랜덤 포트) + Security Filter Chain.
 * <p>
 * 다른 통합 테스트는 테스트 전용 Flyway 콜백(db/test-account)으로 admin 계정을 넣지만, 이 테스트는 운영 마이그레이션만 적용한
 * 전용 DB(H2 인메모리 / PostgreSQL 별도 스키마)에서 계정이 하나도 없는 새 설치를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InitialSetupHttpTest {

    private static final String CONFIGURED_URL = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
    private static final boolean POSTGRESQL = CONFIGURED_URL != null && CONFIGURED_URL.startsWith("jdbc:postgresql:");
    private static final String SCHEMA = "setup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Map<String, String> DB_PROPERTIES = POSTGRESQL
            ? Map.of("spring.datasource.url", CONFIGURED_URL + (CONFIGURED_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                     "spring.flyway.default-schema", SCHEMA)
            : Map.of("spring.datasource.url", "jdbc:h2:mem:lexorma-setup;DB_CLOSE_DELAY=-1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB_PROPERTIES.forEach((key, value) -> registry.add(key, () -> value));
        // 운영 마이그레이션만 (테스트용 admin 계정 콜백 없음)
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/{vendor}");
    }

    private static final String PASSWORD = "Setup!Pass1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // AuthUtil 의 static ProjectRepository 를 다른 테스트 클래스의 컨텍스트 값으로 되돌린다 (ProjectAccessRevokeTest 참고)
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

    @AfterAll
    void cleanup() throws Exception {
        if (PREVIOUS_AUTH_UTIL_REPOSITORY != null) AUTH_UTIL_REPOSITORY.set(null, PREVIOUS_AUTH_UTIL_REPOSITORY);
        if (POSTGRESQL) jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("계정이 없으면 최초 관리자 계정만 만들 수 있다 : admin/admin 없음, 회원가입 409, 동시 요청은 하나만, 만든 계정은 ADMIN·기본 프로젝트 관리자")
    void firstAdministratorAccount() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.get("/api/auth/setup").body()).isEqualTo("{\"required\":true}");

        // 기본 계정은 없다
        assertThat(code(browser.postJson("/api/login/projects", "{\"username\":\"admin\",\"password\":\"admin\"}"))).isEqualTo("LOGIN_FAILED");
        // 최초 관리자보다 먼저 가입 계정(NOT_APPROVE)이 생기면 권한을 줄 관리자가 없다
        HttpResponse<String> signup = browser.postJson("/api/auth/signup",
                "{\"username\":\"early_user\",\"password\":\"" + PASSWORD + "\",\"name\":\"가입자\",\"projectId\":0}");
        assertThat(signup.statusCode()).isEqualTo(409);
        assertThat(code(signup)).isEqualTo("SETUP_REQUIRED");

        // 입력 규칙 (회원가입과 같은 아이디·비밀번호 규칙, 사용자명 필수)
        assertThat(code(browser.postJson("/api/auth/setup", setup("Bad-Name", PASSWORD, "관리자")))).isEqualTo("USERNAME_IS_NON_VALIDATOR");
        assertThat(code(browser.postJson("/api/auth/setup", setup("owner", "weak", "관리자")))).isEqualTo("PASSWORD_IS_NON_VALIDATOR");
        assertThat(code(browser.postJson("/api/auth/setup", setup("owner", PASSWORD, "  ")))).isEqualTo("USER_NAME_REQUIRED");
        assertThat(count("select count(*) from TB_ACNT")).isZero();

        // 동시에 두 번 만들어도 하나만 된다 (ROLE_ADMIN 행 잠금)
        Browser other = new Browser();
        CompletableFuture<HttpResponse<String>> first = browser.postJsonAsync("/api/auth/setup", setup("owner", PASSWORD, "관리자"));
        CompletableFuture<HttpResponse<String>> second = other.postJsonAsync("/api/auth/setup", setup("owner_two", PASSWORD, "관리자2"));
        List<HttpResponse<String>> responses = List.of(first.get(), second.get());
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409).map(InitialSetupHttpTest::code)).containsExactly("SETUP_ALREADY_COMPLETED");
        assertThat(count("select count(*) from TB_ACNT")).isEqualTo(1);
        String username = jdbcTemplate.queryForObject("select USER_ID from TB_ACNT", String.class);
        long accountId = jdbcTemplate.queryForObject("select ACNT_SN from TB_ACNT", Long.class);

        assertThat(browser.get("/api/auth/setup").body()).isEqualTo("{\"required\":false}");
        assertThat(code(browser.postJson("/api/auth/setup", setup("owner_three", PASSWORD, "관리자3")))).isEqualTo("SETUP_ALREADY_COMPLETED");

        // 시스템 ADMIN, 비밀번호 변경 요구 없음, 기본 프로젝트의 활성 멤버·관리자
        assertThat(jdbcTemplate.queryForList("select r.ROLE_NM from TB_ACNT_ROLE ar join TB_ROLE r on r.ROLE_SN = ar.ROLE_SN where ar.ACNT_SN = ?", String.class, accountId))
                .containsExactly("ROLE_ADMIN");
        assertThat(jdbcTemplate.queryForObject("select MNGR_ACNT_SN from TB_PRJ where PRJ_SN = 0", Long.class)).isEqualTo(accountId);
        assertThat(count("select count(*) from TB_PRJ_MBR where PRJ_SN = 0 and ACTVTN_YN = true and ACNT_SN = " + accountId)).isEqualTo(1);

        HttpResponse<String> projects = browser.postJson("/api/login/projects", "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(projects.statusCode()).as(projects.body()).isEqualTo(200);
        assertThat(projects.body()).contains("\"projectName\":\"LEXORMA\"");
        assertThat(browser.login(username, PASSWORD, 0L).statusCode()).isEqualTo(200);
        JsonNode me = JSON.readTree(browser.get("/api/auth/me").body());
        assertThat(me.path("menus").path("user-manage").asBoolean()).as("ADMIN 메뉴").isTrue();
        assertThat(me.path("projectManageable").asBoolean()).isTrue();
        // 로그인한 세션에서도 계정이 있으면 만들 수 없다 (역할 계층상 ANONYMOUS 는 모든 역할의 아래라 권한 검사는 통과한다 - 회원가입과 같다)
        assertThat(code(browser.postJson("/api/auth/setup", setup("owner_four", PASSWORD, "관리자4")))).isEqualTo("SETUP_ALREADY_COMPLETED");

        // 이제 회원가입은 평소대로
        assertThat(new Browser().postJson("/api/auth/signup",
                "{\"username\":\"later_user\",\"password\":\"" + PASSWORD + "\",\"name\":\"가입자\",\"projectId\":0}").statusCode()).isEqualTo(200);
    }

    private static String setup(String username, String password, String name) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"name\":\"" + name + "\"}";
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private static String code(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("code").asString();
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        private String csrf;

        Browser() throws Exception {
            refreshCsrf();
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        private HttpRequest request(String method, String path, String json) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/json")
                    .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (json != null) builder.header("X-XSRF-TOKEN", csrf);
            return builder.build();
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(request("GET", path, null), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postJson(String path, String json) throws Exception {
            return client.send(request("POST", path, json), HttpResponse.BodyHandlers.ofString());
        }

        CompletableFuture<HttpResponse<String>> postJsonAsync(String path, String json) {
            return client.sendAsync(request("POST", path, json), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String username, String password, long projectId) throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password + "&project_id=" + projectId)).build(),
                    HttpResponse.BodyHandlers.ofString());
            refreshCsrf(); // 로그인하면 CSRF 토큰이 바뀐다
            return response;
        }

        private void refreshCsrf() throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            csrf = JSON.readTree(response.body()).path("token").asString();
        }
    }
}
