package com.lexorma;

import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.util.AuthUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로젝트 접근 권한 회수 통합 테스트 : 실제 서버(랜덤 포트) + Security Filter Chain + Spring Session JDBC.
 * <p>
 * 관리자 API(멤버 승인취소 / 프로젝트 비활성화)로 권한을 바꾼 뒤, 로그인해 둔 사용자의 기존 세션 쿠키로
 * 보호된 API 를 호출해 ProjectAccessInterceptor 가 세션을 끊고 401(PROJECT_ACCESS_REVOKED)을 주는지 확인한다.
 * SmokeTest 가 admin 비밀번호를 바꾸므로 다른 테스트와 데이터를 나눈다 : H2 는 별도 인메모리 DB,
 * PostgreSQL(SPRING_DATASOURCE_URL 로 지정한 검증 DB)은 같은 DB 의 별도 스키마. 테스트마다 사용자 / 프로젝트를 새로 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectAccessRevokeTest {

    /**
     * 이 클래스 전용 DB 설정. 실행 환경이 정한 DB 종류를 따른다 (H2 주소를 고정하면 PostgreSQL 드라이버로 실행할 때 컨텍스트가 뜨지 않는다).
     * PostgreSQL 은 실행마다 새 스키마를 만들고(Flyway 가 생성) 끝나면 지운다.
     */
    private static final String CONFIGURED_URL = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
    private static final boolean POSTGRESQL = CONFIGURED_URL != null && CONFIGURED_URL.startsWith("jdbc:postgresql:");
    private static final String SCHEMA = "revoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Map<String, String> DB_PROPERTIES = POSTGRESQL
            ? Map.of("spring.datasource.url", CONFIGURED_URL + (CONFIGURED_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                     "spring.flyway.default-schema", SCHEMA)
            : Map.of("spring.datasource.url", "jdbc:h2:mem:lexorma-revoke;DB_CLOSE_DELAY=-1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB_PROPERTIES.forEach((key, value) -> registry.add(key, () -> value));
    }

    private static final String PASSWORD = "Revoke!Pass1";
    /** 보호된 API (NOT_APPROVE 이상) : 가입 직후 사용자도 호출할 수 있다 */
    private static final String PROTECTED_API = "/api/dashboard";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /*
     * AuthUtil 은 생성자에서 ProjectRepository 를 static 필드에 담는다 (운영은 JVM 당 컨텍스트 1개라 문제없음).
     * 이 클래스는 별도 컨텍스트(별도 DB)와 TC-07 의 두 번째 인스턴스를 띄우므로, 다른 테스트 클래스의 캐시된 컨텍스트가
     * 이 클래스의 DB 를 보지 않도록 컨텍스트 생성 전 값(클래스 로딩 시점)을 저장했다가 되돌린다.
     */
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

    @Autowired
    private ProjectRepository projectRepository;

    private Browser admin;

    @BeforeAll
    void adminLogin() throws Exception {
        admin = new Browser(port);
        assertThat(admin.postJson("/api/login/password",
                "{\"username\":\"admin\",\"password\":\"admin\",\"newPassword\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(200);
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
    }

    @AfterAll
    void restoreAuthUtil() throws Exception {
        if (PREVIOUS_AUTH_UTIL_REPOSITORY != null) AUTH_UTIL_REPOSITORY.set(null, PREVIOUS_AUTH_UTIL_REPOSITORY);
    }

    @AfterAll
    void dropSchema() {
        // 이 클래스가 만든 스키마만 지운다 (H2 인메모리 DB 는 JVM 과 함께 사라진다)
        if (POSTGRESQL) jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("TC-01 정상 프로젝트 접근 : 로그인 → 세션(DB) 생성 → 보호 API 200")
    void normalAccess() throws Exception {
        long p1 = createProject();
        String user = signup(p1);

        Browser browser = new Browser(port);
        HttpResponse<String> login = browser.login(user, p1);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(sessionCount(user)).isEqualTo(1);
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("TC-02/03 로그인 중 권한 회수 : 첫 요청은 401 PROJECT_ACCESS_REVOKED + 세션 삭제, 같은 쿠키 재사용은 일반 401")
    void revokeWhileLoggedIn() throws Exception {
        long p1 = createProject();
        String user = signup(p1);
        Browser browser = new Browser(port);
        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);
        String oldSession = browser.sessionId();

        setMemberActive(p1, user, false);

        HttpResponse<String> revoked = browser.get(PROTECTED_API);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(code(revoked)).isEqualTo("PROJECT_ACCESS_REVOKED");
        assertThat(sessionCount(user)).isZero();

        // TC-03 : 서버가 만료시킨 쿠키 값을 그대로 다시 보내도 인증이 복원되지 않는다 (회수 응답과 구분되는 일반 미인증 응답)
        Browser stale = new Browser(port);
        stale.setSessionCookie(oldSession);
        HttpResponse<String> reused = stale.get(PROTECTED_API);
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(code(reused)).isEqualTo("UNAUTHORIZED");
        assertThat(browser.get("/api/auth/me").body()).contains("\"authenticated\":false");
    }

    @Test
    @DisplayName("TC-04 권한 재부여 : 기존 세션은 복원되지 않고, 새 로그인으로 새 세션을 받아 접근한다")
    void regrantRequiresNewLogin() throws Exception {
        long p1 = createProject();
        String user = signup(p1);
        Browser browser = new Browser(port);
        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);
        String oldSession = browser.sessionId();

        setMemberActive(p1, user, false);
        assertThat(code(browser.get(PROTECTED_API))).isEqualTo("PROJECT_ACCESS_REVOKED");

        // 회수 중에는 해당 프로젝트로 로그인할 수 없다 (프로젝트 선택 목록에서도 빠진다)
        assertThat(code(browser.login(user, p1))).isEqualTo("PROJECT_ACCESS_DENIED");
        assertThat(browser.postJson("/api/login/projects", credentials(user)).body()).doesNotContain("\"id\":" + p1);

        setMemberActive(p1, user, true);

        Browser stale = new Browser(port);
        stale.setSessionCookie(oldSession);
        assertThat(code(stale.get(PROTECTED_API))).isEqualTo("UNAUTHORIZED");

        HttpResponse<String> relogin = browser.login(user, p1);
        assertThat(relogin.statusCode()).as(relogin.body()).isEqualTo(200);
        assertThat(browser.sessionId()).isNotEqualTo(oldSession);
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("TC-05 복수 프로젝트 : P1 만 회수하면 P1 세션은 끊기고 P2 로는 새로 로그인해 접근한다")
    void otherProjectKept() throws Exception {
        long p1 = createProject();
        long p2 = createProject();
        String user = signup(p1);
        addMember(p2, user);

        Browser browser = new Browser(port);
        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);

        setMemberActive(p1, user, false);
        assertThat(code(browser.get(PROTECTED_API))).isEqualTo("PROJECT_ACCESS_REVOKED");

        String projects = browser.postJson("/api/login/projects", credentials(user)).body();
        assertThat(projects).contains("\"id\":" + p2).doesNotContain("\"id\":" + p1);

        HttpResponse<String> login = browser.login(user, p2);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/auth/me").body()).contains("\"projectId\":" + p2);
    }

    @Test
    @DisplayName("TC-06 프로젝트 비활성화 : 멤버 회수와 같은 기준(PROJECT_ACCESS_REVOKED)으로 세션을 끊는다")
    void projectDeactivated() throws Exception {
        long p1 = createProject();
        String user = signup(p1);
        Browser browser = new Browser(port);
        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);

        setProjectActive(p1, false);

        HttpResponse<String> revoked = browser.get(PROTECTED_API);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(code(revoked)).isEqualTo("PROJECT_ACCESS_REVOKED");
        assertThat(sessionCount(user)).isZero();
        assertThat(code(browser.login(user, p1))).isEqualTo("PROJECT_ACCESS_DENIED");
    }

    @Test
    @DisplayName("TC-07 JDBC 세션 영속성 : 다른 앱 인스턴스(재시작)에서 만든 세션이 복원되고, 권한 회수 시 끊긴다")
    void jdbcSessionSurvivesRestart() throws Exception {
        long p1 = createProject();
        String user = signup(p1);
        Browser browser = new Browser(port);

        // 같은 DB 를 쓰는 다른 인스턴스에서 로그인한 뒤 그 인스턴스를 내린다
        try (ConfigurableApplicationContext other = new SpringApplicationBuilder(LexormaApplication.class)
                .profiles("test")
                .run(Stream.concat(Stream.of("--server.port=0"), DB_PROPERTIES.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()))
                        .toArray(String[]::new))) { // 명령행 인자 : application.yml·환경변수보다 우선
            Browser first = new Browser(other.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
            assertThat(first.login(user, p1).statusCode()).isEqualTo(200);
            browser.setSessionCookie(first.sessionId());
        } finally {
            new AuthUtil(projectRepository); // 두 번째 인스턴스가 바꾼 static 값을 이 컨텍스트로 되돌린다
        }
        assertThat(sessionCount(user)).isEqualTo(1);

        // 메모리가 아니라 JDBC 저장소에서 인증 정보가 복원된다
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);

        setMemberActive(p1, user, false);

        HttpResponse<String> revoked = browser.get(PROTECTED_API);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(code(revoked)).isEqualTo("PROJECT_ACCESS_REVOKED");
        assertThat(sessionCount(user)).isZero();
    }

    @Test
    @DisplayName("프로젝트 ID 위변조 : 멤버가 아닌 프로젝트 / 없는 프로젝트 ID 로는 로그인할 수 없다")
    void forgedProjectId() throws Exception {
        long p1 = createProject();
        long notMember = createProject();
        String user = signup(p1);
        Browser browser = new Browser(port);

        assertThat(code(browser.login(user, notMember))).isEqualTo("PROJECT_ACCESS_DENIED");
        assertThat(code(browser.login(user, 99_999L))).isEqualTo("PROJECT_ACCESS_DENIED");
        assertThat(sessionCount(user)).isZero();
    }

    @Test
    @DisplayName("역할 변경 : 로그인 중인 사용자의 세션을 끊어 예전 역할로 요청하지 못하게 하고, 다시 로그인하면 새 역할이 적용된다")
    void roleChangeEndsSession() throws Exception {
        long p1 = createProject();
        String user = signup(p1);
        Browser browser = new Browser(port);
        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/auth/me").body()).contains("\"table-manage\":false");

        // 같은 역할로 저장하면 (화면의 [저장] 은 바뀐 행만 보내지만) 세션은 그대로다
        setRole(user, "ROLE_NOT_APPROVE");
        assertThat(sessionCount(user)).isEqualTo(1);
        assertThat(browser.get(PROTECTED_API).statusCode()).isEqualTo(200);

        setRole(user, "ROLE_USER");
        assertThat(sessionCount(user)).isZero();
        HttpResponse<String> stale = browser.get(PROTECTED_API);
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(code(stale)).isEqualTo("UNAUTHORIZED");

        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/auth/me").body()).contains("\"physical-model-edit\":true");

        // 강등 : USER 로 로그인해 둔 세션으로는 더 이상 변경 API 를 부를 수 없다
        setRole(user, "ROLE_VIEWER");
        assertThat(browser.postJson("/api/schema", "{\"schemaName\":\"stale\"}").statusCode()).isEqualTo(401);
        assertThat(browser.login(user, p1).statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/auth/me").body()).contains("\"physical-model-edit\":false");
        assertThat(browser.postJson("/api/schema", "{\"schemaName\":\"viewer\"}").statusCode()).isEqualTo(403);
    }

    // ===== 관리자 API로 데이터 준비 / 권한 변경 =====

    /** 사용자 관리 화면의 권한 변경 */
    private void setRole(String username, String role) throws Exception {
        String accountId = null;
        for (JsonNode account : JSON.readTree(admin.get("/api/selectUser/data?page=0&size=1000").body()).path("data")) {
            if (username.equals(account.path("username").asString())) accountId = account.path("id").asString();
        }
        assertThat(accountId).isNotNull();
        HttpResponse<String> response = admin.send(HttpRequest.newBuilder(admin.uri("/api/saveUser/update"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", admin.csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("[{\"id\":\"" + accountId + "\",\"role\":\"" + role + "\"}]", StandardCharsets.UTF_8)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private long createProject() throws Exception {
        String name = "REVOKE_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(admin.postJson("/api/saveProject/project", """
                {"projectName":"%s","isActive":true,"isAutoActive":true,"isDicAbbrUsed":false,"tablePrefix":"","dbType":"POSTGRESQL"}
                """.formatted(name)).statusCode()).isEqualTo(200);
        for (JsonNode project : JSON.readTree(admin.get("/api/getProject/managed").body())) {
            if (name.equals(project.path("projectName").asString())) return project.path("id").asLong();
        }
        throw new AssertionError("생성한 프로젝트가 없습니다: " + name);
    }

    /** 회원가입 (자동 승인 프로젝트이므로 바로 멤버가 된다) */
    private String signup(long projectId) throws Exception {
        String username = "rv" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpResponse<String> response = new Browser(port).postJson("/api/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"회수테스트\",\"projectId\":" + projectId + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return username;
    }

    private void addMember(long projectId, String username) throws Exception {
        String accountId = null;
        for (JsonNode account : JSON.readTree(admin.get("/api/getProject/projectMember/" + projectId).body()).path("projectMember")) {
            if (username.equals(account.path("username").asString())) accountId = account.path("id").asString();
        }
        assertThat(accountId).isNotNull();
        assertThat(admin.postJson("/api/saveProject/projectMember/" + projectId, "{\"accountId\":\"" + accountId + "\"}").statusCode()).isEqualTo(200);
    }

    /** 프로젝트 관리 화면의 멤버 승인 / 승인취소 */
    private void setMemberActive(long projectId, String username, boolean active) throws Exception {
        long memberId = -1;
        for (JsonNode member : JSON.readTree(admin.get("/api/getProject/" + projectId + "/data?page=0&size=100").body()).path("data")) {
            if (username.equals(member.path("username").asString())) memberId = member.path("id").asLong();
        }
        assertThat(memberId).isNotNegative();
        assertThat(admin.postJson("/api/saveProject/active/" + active, "[{\"id\":" + memberId + "}]").statusCode()).isEqualTo(200);
    }

    private void setProjectActive(long projectId, boolean active) throws Exception {
        JsonNode project = JSON.readTree(admin.get("/api/getProject/" + projectId + "/getEditProjectData").body()).path("project");
        HttpResponse<String> response = admin.send(HttpRequest.newBuilder(admin.uri("/api/saveProject/project"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", admin.csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"id":%d,"projectName":"%s","projectManagerId":0,"isActive":%s,"isAutoActive":true,"isDicAbbrUsed":false,"tablePrefix":"","dbType":"POSTGRESQL"}
                        """.formatted(projectId, project.path("projectName").asString(), active), StandardCharsets.UTF_8)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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
    private static class Browser {
        private final int port;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client;
        private String csrf;

        Browser(int port) throws Exception {
            this.port = port;
            this.client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
            refreshCsrf();
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
            return client.send(builder.header("X-Requested-With", "XMLHttpRequest").build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        }

        HttpResponse<String> login(String username, long projectId) throws Exception {
            refreshCsrf();
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + PASSWORD + "&project_id=" + projectId)));
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
