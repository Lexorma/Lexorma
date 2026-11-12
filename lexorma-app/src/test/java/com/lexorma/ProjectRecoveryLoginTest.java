package com.lexorma;

import com.lexorma.core.util.AuthUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * 프로젝트 복구 로그인 통합 테스트 : 실제 서버(랜덤 포트) + Security Filter Chain + Spring Session JDBC.
 * <p>
 * 활성 프로젝트가 하나도 없는 상태는 DB 전체에 영향을 주므로 ProjectAccessRevokeTest 처럼 전용 DB(H2 인메모리 / PostgreSQL 별도 스키마)를 쓴다.
 * 프로젝트 활성 상태 준비는 JDBC 로 직접 하고, 검증 대상(로그인·복구 API·비활성화)은 HTTP 로 호출한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectRecoveryLoginTest {

    private static final String CONFIGURED_URL = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
    private static final boolean POSTGRESQL = CONFIGURED_URL != null && CONFIGURED_URL.startsWith("jdbc:postgresql:");
    private static final String SCHEMA = "recovery_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Map<String, String> DB_PROPERTIES = POSTGRESQL
            ? Map.of("spring.datasource.url", CONFIGURED_URL + (CONFIGURED_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                     "spring.flyway.default-schema", SCHEMA)
            : Map.of("spring.datasource.url", "jdbc:h2:mem:lexorma-recovery;DB_CLOSE_DELAY=-1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB_PROPERTIES.forEach((key, value) -> registry.add(key, () -> value));
    }

    private static final String PASSWORD = "Recover!Pass1";
    private static final String PROTECTED_API = "/api/dashboard";
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

    @BeforeAll
    void changeAdminPassword() throws Exception {
        assertThat(new Browser().postJson("/api/login/password",
                "{\"username\":\"admin\",\"password\":\"admin\",\"newPassword\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(200);
    }

    /** 매 테스트 시작 상태 : 기본 프로젝트(0)만 활성, 세션 없음 */
    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");
        jdbcTemplate.update("UPDATE TB_PRJ SET ACTVTN_YN = (PRJ_SN = 0)");
    }

    @AfterAll
    void cleanup() throws Exception {
        if (PREVIOUS_AUTH_UTIL_REPOSITORY != null) AUTH_UTIL_REPOSITORY.set(null, PREVIOUS_AUTH_UTIL_REPOSITORY);
        if (POSTGRESQL) jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("활성 프로젝트가 있으면 기존 로그인만 : ADMIN 도 복구 로그인할 수 없고, 일반 ADMIN 세션은 복구 API 를 쓸 수 없다")
    void recoveryDeniedWhileActiveProjectExists() throws Exception {
        Browser admin = new Browser();
        assertThat(code(admin.recoveryLogin("admin", PASSWORD))).isEqualTo("RECOVERY_LOGIN_DENIED");
        assertThat(sessionCount("admin")).isZero();

        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/auth/me").body()).doesNotContain("recovery");
        assertThat(admin.get(PROTECTED_API).statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/recovery/projects").statusCode()).isEqualTo(403);
        assertThat(admin.postJson("/api/recovery/projects/0/activate", "").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("기본 프로젝트가 비활성이어도 다른 활성 프로젝트로 로그인한다 (복구 로그인은 불가)")
    void defaultProjectInactiveOtherActive() throws Exception {
        Browser admin = new Browser();
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        long other = createProject(admin);
        jdbcTemplate.update("UPDATE TB_PRJ SET ACTVTN_YN = false WHERE PRJ_SN = 0");

        Browser browser = new Browser();
        String projects = browser.postJson("/api/login/projects", credentials("admin")).body();
        assertThat(projects).contains("\"id\":" + other).doesNotContain("LEXORMA");
        assertThat(code(browser.login("admin", 0L))).isEqualTo("PROJECT_ACCESS_DENIED");
        assertThat(code(browser.recoveryLogin("admin", PASSWORD))).isEqualTo("RECOVERY_LOGIN_DENIED");
        assertThat(browser.login("admin", other).statusCode()).isEqualTo(200);
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("마지막 활성 프로젝트 비활성화 : 확인 없이는 409 LAST_ACTIVE_PROJECT, 확인하면 비활성화되고 일반 로그인은 막힌다")
    void lastActiveProjectNeedsConfirm() throws Exception {
        Browser admin = new Browser();
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        long other = createProject(admin);

        // 다른 활성 프로젝트가 있으면 확인 없이 비활성화된다
        assertThat(admin.putJson("/api/saveProject/project", projectBody(other, false)).statusCode()).isEqualTo(200);

        HttpResponse<String> blocked = admin.putJson("/api/saveProject/project", projectBody(0L, false));
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("LAST_ACTIVE_PROJECT");
        assertThat(activeProjectCount()).isEqualTo(1);

        assertThat(admin.putJson("/api/saveProject/project?confirmLastActive=true", projectBody(0L, false)).statusCode()).isEqualTo(200);
        assertThat(activeProjectCount()).isZero();

        // 일반 세션은 끊기고, 프로젝트 선택 단계도 통과하지 못한다
        assertThat(code(admin.get(PROTECTED_API))).isEqualTo("PROJECT_ACCESS_REVOKED");
        HttpResponse<String> step1 = admin.postJson("/api/login/projects", credentials("admin"));
        assertThat(step1.statusCode()).isEqualTo(403);
        assertThat(code(step1)).isEqualTo("NO_ACCESSIBLE_PROJECT");
        assertThat(admin.get("/api/auth/signup/projects").body()).isEqualTo("[]");
    }

    @Test
    @DisplayName("서로 다른 프로젝트를 동시에 비활성화해도 확인 없이 활성 프로젝트가 0개가 되지는 않는다")
    void concurrentDeactivation() throws Exception {
        Browser admin = new Browser();
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        long other = createProject(admin);

        List<CompletableFuture<HttpResponse<String>>> requests = List.of(
                admin.putJsonAsync("/api/saveProject/project", projectBody(0L, false)),
                admin.putJsonAsync("/api/saveProject/project", projectBody(other, false)));
        List<Integer> statuses = requests.stream().map(request -> request.join().statusCode()).toList();

        // 하나는 성공(200), 나머지는 마지막 활성 프로젝트 확인 요구(409) 또는 먼저 끝난 요청 때문에 세션이 끊김(401)
        assertThat(statuses).contains(200);
        assertThat(activeProjectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("복구 로그인은 시스템 ADMIN 만 : 다른 역할·프로젝트 관리자·잘못된 비밀번호·비활성 계정은 세션을 받지 못한다")
    void recoveryLoginOnlyForAdmin() throws Exception {
        Browser admin = new Browser();
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        String user = signup();
        String inactiveAdmin = signup();
        setRole(inactiveAdmin, "ROLE_ADMIN");
        jdbcTemplate.update("UPDATE TB_ACNT SET ACTVTN_YN = false WHERE USER_ID = ?", inactiveAdmin);
        // user 를 프로젝트 관리자로 만든다 (시스템 ADMIN 은 아니다)
        jdbcTemplate.update("UPDATE TB_PRJ SET MNGR_ACNT_SN = (SELECT ACNT_SN FROM TB_ACNT WHERE USER_ID = ?) WHERE PRJ_SN = ?", user, createProject(admin));
        jdbcTemplate.update("UPDATE TB_PRJ SET ACTVTN_YN = false");

        Browser browser = new Browser();
        assertThat(browser.get("/api/recovery/projects").statusCode()).isEqualTo(401);
        assertThat(browser.postJson("/api/recovery/projects/0/activate", "").statusCode()).isEqualTo(401);

        for (String role : List.of("ROLE_NOT_APPROVE", "ROLE_VIEWER", "ROLE_USER", "ROLE_DBA", "ROLE_MANAGER")) {
            setRole(user, role);
            HttpResponse<String> denied = browser.recoveryLogin(user, PASSWORD);
            assertThat(denied.statusCode()).as(role).isEqualTo(401);
            assertThat(code(denied)).as(role).isEqualTo("RECOVERY_LOGIN_DENIED");
            assertThat(browser.get("/api/recovery/projects").statusCode()).as(role).isEqualTo(401);
        }
        assertThat(sessionCount(user)).isZero();

        assertThat(code(browser.recoveryLogin("admin", "Wrong!Pass1"))).isEqualTo("INVALID_CREDENTIALS");
        assertThat(code(browser.recoveryLogin(inactiveAdmin, PASSWORD))).isEqualTo("INVALID_CREDENTIALS");
        assertThat(code(browser.recoveryLogin("no-such-user", PASSWORD))).isEqualTo("INVALID_CREDENTIALS");
        assertThat(activeProjectCount()).isZero();
    }

    @Test
    @DisplayName("복구 흐름 : 복구 로그인 → 일반 API 차단 → 프로젝트 활성화 → 로그아웃 → 정상 로그인")
    void recoveryFlow() throws Exception {
        Browser setup = new Browser();
        assertThat(setup.login("admin", 0L).statusCode()).isEqualTo(200);
        long other = createProject(setup);
        jdbcTemplate.update("UPDATE TB_PRJ SET ACTVTN_YN = false");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");

        Browser browser = new Browser();
        HttpResponse<String> login = browser.recoveryLogin("admin", PASSWORD);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(sessionCount("admin")).isEqualTo(1);

        JsonNode me = JSON.readTree(browser.get("/api/auth/me").body());
        assertThat(me.path("authenticated").asBoolean()).isTrue();
        assertThat(me.path("recovery").asBoolean()).isTrue();
        assertThat(me.path("user").path("projectId").isNull()).isTrue();
        assertThat(me.path("approvalAvailable").asBoolean()).isFalse();
        me.path("menus").forEach(menu -> assertThat(menu.asBoolean()).isFalse());

        // 일반 업무 API 는 ADMIN 이어도 모두 403 (세션은 끊기지 않는다)
        for (String path : List.of(PROTECTED_API, "/api/selectUser/data?page=0&size=10", "/api/getProject/managed", "/api/erd",
                "/api/selectTable/ddl/1", "/api/selectNotice/data?page=0&size=10",
                "/api/modelHistory/data?page=0&size=10", "/api/modelHistory/1", "/api/modelHistory/group/1", "/api/no-such-api")) {
            HttpResponse<String> denied = browser.get(path);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(code(denied)).as(path).isEqualTo("ACCESS_DENIED");
        }
        assertThat(browser.postJson("/api/saveProject/project", projectBody(null, true)).statusCode()).isEqualTo(403);
        assertThat(browser.putJson("/api/saveProject/project?confirmLastActive=true", projectBody(other, true)).statusCode()).isEqualTo(403);
        assertThat(browser.putJson("/api/saveUser/update", "[{\"id\":\"0\",\"role\":\"ROLE_USER\"}]").statusCode()).isEqualTo(403);
        assertThat(browser.send("PATCH", "/api/saveTable/true", "[]", true).statusCode()).isEqualTo(403);
        assertThat(browser.send("PATCH", "/api/approvalStandardWords/true", "[]", true).statusCode()).isEqualTo(403);
        assertThat(browser.putJson("/api/logical/model/1/erd/layout", "{}").statusCode()).isEqualTo(403);
        assertThat(browser.postJson("/api/updatePwd", "{}").statusCode()).isEqualTo(403);
        assertThat(sessionCount("admin")).isEqualTo(1);
        assertThat(activeProjectCount()).isZero();

        // 복구 API : 목록(비활성 포함)
        JsonNode projects = JSON.readTree(browser.get("/api/recovery/projects").body());
        assertThat(projects.size()).isGreaterThanOrEqualTo(2);
        assertThat(projects.get(0).path("id").asLong()).isZero();
        assertThat(projects.get(0).path("isDefault").asBoolean()).isTrue();
        projects.forEach(project -> assertThat(project.path("isActive").asBoolean()).isFalse());

        // CSRF 토큰이 없는 변경 요청 / 없는 프로젝트
        HttpResponse<String> noCsrf = browser.send("POST", "/api/recovery/projects/0/activate", "", false);
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(code(noCsrf)).isEqualTo("CSRF_INVALID");
        HttpResponse<String> missing = browser.postJson("/api/recovery/projects/99999/activate", "");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("PROJECT_NOT_FOUND");
        assertThat(activeProjectCount()).isZero();

        // 여러 프로젝트를 차례로 활성화, 같은 요청을 다시 보내도 안전하다. 이름·관리자는 그대로다
        String nameBefore = jdbcTemplate.queryForObject("SELECT PRJ_NM FROM TB_PRJ WHERE PRJ_SN = ?", String.class, other);
        assertThat(browser.postJson("/api/recovery/projects/0/activate", "").statusCode()).isEqualTo(200);
        assertThat(browser.postJson("/api/recovery/projects/0/activate", "").statusCode()).isEqualTo(200);
        assertThat(browser.postJson("/api/recovery/projects/" + other + "/activate", "").statusCode()).isEqualTo(200);
        assertThat(activeProjectCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT PRJ_NM FROM TB_PRJ WHERE PRJ_SN = ?", String.class, other)).isEqualTo(nameBefore);

        // 활성화한 뒤에도 복구 세션은 일반 세션이 되지 않는다
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(403);
        assertThat(browser.get("/api/auth/me").body()).contains("\"recovery\":true");

        // 복구 완료 = 로그아웃 : 예전 쿠키로는 복구 API 도 쓸 수 없다
        String oldSession = browser.sessionId();
        assertThat(browser.postJson("/api/auth/logout", "").statusCode()).isEqualTo(204);
        assertThat(sessionCount("admin")).isZero();
        Browser stale = new Browser();
        stale.setSessionCookie(oldSession);
        assertThat(stale.get("/api/recovery/projects").statusCode()).isEqualTo(401);

        // 정상 로그인 재진입 : 프로젝트 선택 → 일반 화면 데이터. 이제 복구 로그인은 다시 막힌다
        Browser normal = new Browser();
        assertThat(code(normal.recoveryLogin("admin", PASSWORD))).isEqualTo("RECOVERY_LOGIN_DENIED");
        assertThat(normal.postJson("/api/login/projects", credentials("admin")).body()).contains("LEXORMA").contains("\"id\":" + other);
        assertThat(normal.login("admin", 0L).statusCode()).isEqualTo(200);
        assertThat(normal.get(PROTECTED_API).statusCode()).isEqualTo(200);
        assertThat(normal.get("/api/auth/me").body()).doesNotContain("recovery").contains("\"projectId\":0");
    }

    @Test
    @DisplayName("복구 세션도 계정당 1개 : 나중 복구 로그인이 먼저 로그인한 복구 세션을 만료시킨다")
    void recoverySessionLimit() throws Exception {
        jdbcTemplate.update("UPDATE TB_PRJ SET ACTVTN_YN = false");

        Browser first = new Browser();
        assertThat(first.recoveryLogin("admin", PASSWORD).statusCode()).isEqualTo(200);
        Browser second = new Browser();
        assertThat(second.recoveryLogin("admin", PASSWORD).statusCode()).isEqualTo(200);

        HttpResponse<String> expired = first.get("/api/recovery/projects");
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(code(expired)).isEqualTo("DUPLICATE_LOGIN");
        assertThat(second.get("/api/recovery/projects").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("복구 세션 중 ADMIN 에서 강등되면 세션이 끊겨 복구 API 를 쓸 수 없다")
    void demotedAdminLosesRecoverySession() throws Exception {
        Browser admin = new Browser();
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        String admin2 = signup();
        setRole(admin2, "ROLE_ADMIN");
        jdbcTemplate.update("UPDATE TB_PRJ SET ACTVTN_YN = false");
        jdbcTemplate.update("DELETE FROM SPRING_SESSION");

        Browser recovery = new Browser();
        assertThat(recovery.recoveryLogin(admin2, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(recovery.postJson("/api/recovery/projects/0/activate", "").statusCode()).isEqualTo(200);

        // 프로젝트가 살아났으므로 admin 이 정상 로그인해 admin2 를 강등한다 (사용자 관리 화면의 권한 변경)
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        String accountId = jdbcTemplate.queryForObject("SELECT ACNT_SN FROM TB_ACNT WHERE USER_ID = ?", String.class, admin2);
        assertThat(admin.putJson("/api/saveUser/update", "[{\"id\":\"" + accountId + "\",\"role\":\"ROLE_USER\"}]").statusCode()).isEqualTo(200);

        assertThat(sessionCount(admin2)).isZero();
        assertThat(recovery.get("/api/recovery/projects").statusCode()).isEqualTo(401);
    }

    // ===== 데이터 준비 =====

    private long createProject(Browser admin) throws Exception {
        String name = "RECOVERY_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(admin.postJson("/api/saveProject/project", projectBody(null, true).replace("\"LEXORMA\"", "\"" + name + "\"")).statusCode()).isEqualTo(200);
        return jdbcTemplate.queryForObject("SELECT PRJ_SN FROM TB_PRJ WHERE PRJ_NM = ?", Long.class, name);
    }

    /** 프로젝트 관리 화면의 저장 요청 본문 (이름은 그대로 두고 활성 여부만 바꾼다) */
    private String projectBody(Long projectId, boolean active) {
        String name = projectId == null ? "LEXORMA" : jdbcTemplate.queryForObject("SELECT PRJ_NM FROM TB_PRJ WHERE PRJ_SN = ?", String.class, projectId);
        return """
                {"id":%s,"projectName":"%s","projectManagerId":0,"isActive":%s,"isAutoActive":true,"isDicAbbrUsed":false,"tablePrefix":"","dbType":"POSTGRESQL"}
                """.formatted(projectId, name, active);
    }

    /** 회원가입 (기본 프로젝트) : 복구 로그인은 프로젝트 멤버 여부와 무관하므로 계정만 있으면 된다 */
    private String signup() throws Exception {
        String username = "rc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpResponse<String> response = new Browser().postJson("/api/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"복구테스트\",\"projectId\":0}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return username;
    }

    private void setRole(String username, String role) {
        assertThat(jdbcTemplate.update("""
                UPDATE TB_ACNT_ROLE SET ROLE_SN = (SELECT ROLE_SN FROM TB_ROLE WHERE ROLE_NM = ?)
                WHERE ACNT_SN = (SELECT ACNT_SN FROM TB_ACNT WHERE USER_ID = ?)
                """, role, username)).isEqualTo(1);
    }

    private int activeProjectCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TB_PRJ WHERE ACTVTN_YN = true", Integer.class);
    }

    private int sessionCount(String username) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Integer.class, username);
    }

    private static String credentials(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static String code(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("code").asString();
    }

    /** 사용자 한 명의 브라우저 : 쿠키(SESSION, XSRF) + 메모리의 CSRF 토큰 (React 와 같은 방식) */
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

        private HttpRequest request(String method, String path, String json, boolean withCsrf) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/json")
                    .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (withCsrf) builder.header("X-XSRF-TOKEN", csrf);
            return builder.build();
        }

        HttpResponse<String> send(String method, String path, String json, boolean withCsrf) throws Exception {
            return client.send(request(method, path, json, withCsrf), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, false);
        }

        HttpResponse<String> postJson(String path, String json) throws Exception {
            return send("POST", path, json, true);
        }

        HttpResponse<String> putJson(String path, String json) throws Exception {
            return send("PUT", path, json, true);
        }

        CompletableFuture<HttpResponse<String>> putJsonAsync(String path, String json) {
            return client.sendAsync(request("PUT", path, json, true), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> login(String username, long projectId) throws Exception {
            return formLogin("username=" + username + "&password=" + PASSWORD + "&project_id=" + projectId);
        }

        HttpResponse<String> recoveryLogin(String username, String password) throws Exception {
            return formLogin("username=" + username + "&password=" + password + "&recovery=true");
        }

        private HttpResponse<String> formLogin(String form) throws Exception {
            refreshCsrf();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            refreshCsrf(); // 로그인하면 CSRF 토큰이 바뀐다
            return response;
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        }

        void setSessionCookie(String value) {
            java.net.HttpCookie cookie = new java.net.HttpCookie("SESSION", value);
            cookie.setPath("/");
            cookie.setVersion(0);
            cookies.getCookieStore().add(uri("/"), cookie);
        }

        private void refreshCsrf() throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            csrf = JSON.readTree(response.body()).path("token").asString();
        }
    }
}
