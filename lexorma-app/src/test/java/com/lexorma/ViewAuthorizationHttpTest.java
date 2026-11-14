package com.lexorma;

import com.lexorma.core.util.AuthUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.IOException;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-15 안정화 : VIEW API 의 역할·승인 권한·프로젝트 격리를 실제 서버(랜덤 포트, Security Filter Chain, 세션·CSRF)로 확인한다.
 * USER = 조회·등록·미승인 수정·삭제·변경 요청·미리보기, 승인·반려 불가(400 APPROVAL_PERMISSION_DENIED).
 * VIEWER = 조회·DDL·ERD 만, 변경 API 는 역할에서 403 ACCESS_DENIED. 다른 프로젝트의 VIEW·요청·테이블 ID 는 찾을 수 없음(400). 로그인 없이는 401.
 * DB 는 ProjectAccessRevokeTest 와 같은 방식으로 이 클래스 전용 (H2 별도 인메모리 / PostgreSQL 별도 스키마).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewAuthorizationHttpTest {

    private static final String CONFIGURED_URL = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
    private static final boolean POSTGRESQL = CONFIGURED_URL != null && CONFIGURED_URL.startsWith("jdbc:postgresql:");
    private static final String SCHEMA = "viewauth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Map<String, String> DB_PROPERTIES = POSTGRESQL
            ? Map.of("spring.datasource.url", CONFIGURED_URL + (CONFIGURED_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                     "spring.flyway.default-schema", SCHEMA)
            : Map.of("spring.datasource.url", "jdbc:h2:mem:lexorma-viewauth;DB_CLOSE_DELAY=-1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB_PROPERTIES.forEach((key, value) -> registry.add(key, () -> value));
    }

    private static final String PASSWORD = "ViewAuth!Pass1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    private long tableId;
    private String userName;
    private String viewerName;

    @BeforeAll
    void prepare() throws Exception {
        admin = new Browser(port);
        assertThat(admin.postJson("/api/login/password",
                "{\"username\":\"admin\",\"password\":\"admin\",\"newPassword\":\"" + PASSWORD + "\"}").statusCode()).isEqualTo(200);
        assertThat(admin.login("admin", 0L).statusCode()).isEqualTo(200);
        projectId = createProject();
        otherProjectId = createProject();
        userName = signup(projectId);
        viewerName = signup(projectId);
        setRole(userName, "ROLE_USER");
        setRole(viewerName, "ROLE_VIEWER");

        // 관리자로 프로젝트에 들어가 기본 스키마·테이블·승인된 VIEW 를 만든다
        assertThat(admin.login("admin", projectId).statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/schema").statusCode()).isEqualTo(200); // 기본 스키마 생성
        long schemaId = jdbcTemplate.queryForObject("select SCHM_SN from TB_SCHM where PRJ_SN = ? and BSC_YN = true", Long.class, projectId);
        tableId = 990_000 + projectId;
        jdbcTemplate.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values(?, ?, ?, 'TB_AUTH', true)", tableId, projectId, schemaId);
    }

    @AfterAll
    void restore() throws Exception {
        if (PREVIOUS_AUTH_UTIL_REPOSITORY != null) AUTH_UTIL_REPOSITORY.set(null, PREVIOUS_AUTH_UTIL_REPOSITORY);
        if (POSTGRESQL) jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("USER : 조회·등록·미승인 수정·삭제·변경 요청·미리보기는 되고, 승인·반려·승인된 VIEW 삭제는 서버가 거절한다. VIEWER : 조회만, 변경 API 는 403. 다른 프로젝트 : 찾을 수 없음. 비로그인 : 401")
    void rolesAndIsolation() throws Exception {
        assertThat(admin.login("admin", projectId).statusCode()).isEqualTo(200);
        long approved = id(ok(admin.postJson("/api/physical/view", save("VW_AUTH", "SELECT 1", tableId))));

        // ---------- USER
        Browser user = new Browser(port);
        assertThat(user.login(userName, projectId).statusCode()).isEqualTo(200);
        JsonNode me = JSON.readTree(user.get("/api/auth/me").body());
        assertThat(me.path("menus").path("view-manage").asBoolean()).isTrue();
        assertThat(me.path("menus").path("physical-model-edit").asBoolean()).isTrue();
        assertThat(me.path("approvalAvailable").asBoolean()).isFalse();
        readable(user, approved);
        JsonNode created = ok(user.postJson("/api/physical/view", save("VW_BY_USER", "SELECT 2", null)));
        long own = created.path("id").asLong();
        assertThat(JSON.readTree(user.get("/api/physical/view/" + own).body()).path("isApproval").asBoolean()).as("승인 권한이 없으면 승인 대기로 등록").isFalse();
        assertThat(ok(user.put("/api/physical/view/" + own, save("VW_BY_USER", "SELECT 3", null))).path("applied").asBoolean()).isTrue();
        JsonNode request = ok(user.put("/api/physical/view/" + approved, save("VW_AUTH", "SELECT 9", tableId)));
        assertThat(request.path("applied").asBoolean()).isFalse();
        long requestId = request.path("request").path("id").asLong();
        assertThat(JSON.readTree(user.get("/api/physical/view/" + approved).body()).path("view").path("definition").asString()).as("대기 중 확정 정의 유지").isEqualTo("SELECT 1");
        error(user.patch("/api/physical/view/request/" + requestId + "/true"), 400, "APPROVAL_PERMISSION_DENIED");
        error(user.patch("/api/physical/view/request/" + requestId + "/false"), 400, "APPROVAL_PERMISSION_DENIED");
        error(user.patch("/api/physical/view/" + own + "/approval/true"), 400, "APPROVAL_PERMISSION_DENIED");
        error(user.delete("/api/physical/view/" + approved), 400, "APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(user.postJson("/api/physical/view/ddl/preview", save("VW_PREVIEW", "SELECT 1", null)).statusCode()).isEqualTo(200);
        assertThat(user.delete("/api/physical/view/" + own).statusCode()).isEqualTo(200);

        // ---------- VIEWER
        Browser viewer = new Browser(port);
        assertThat(viewer.login(viewerName, projectId).statusCode()).isEqualTo(200);
        me = JSON.readTree(viewer.get("/api/auth/me").body());
        assertThat(me.path("menus").path("view-manage").asBoolean()).isTrue();
        assertThat(me.path("menus").path("physical-model-edit").asBoolean()).isFalse();
        assertThat(me.path("approvalAvailable").asBoolean()).isFalse();
        readable(viewer, approved);
        error(viewer.postJson("/api/physical/view", save("VW_BY_VIEWER", "SELECT 1", null)), 403, "ACCESS_DENIED");
        error(viewer.put("/api/physical/view/" + approved, save("VW_AUTH", "SELECT 8", null)), 403, "ACCESS_DENIED");
        error(viewer.delete("/api/physical/view/" + approved), 403, "ACCESS_DENIED");
        error(viewer.patch("/api/physical/view/" + approved + "/approval/false"), 403, "ACCESS_DENIED");
        error(viewer.patch("/api/physical/view/request/" + requestId + "/true"), 403, "ACCESS_DENIED");
        error(viewer.postJson("/api/physical/view/ddl/preview", save("VW_X", "SELECT 1", null)), 403, "ACCESS_DENIED");

        // ---------- 다른 프로젝트 (관리자 역할이어도 로그인한 프로젝트 밖은 없는 것과 같다)
        Browser stranger = new Browser(port);
        assertThat(stranger.login("admin", otherProjectId).statusCode()).isEqualTo(200);
        for (HttpResponse<String> response : List.of(stranger.get("/api/physical/view/" + approved), stranger.get("/api/physical/view/" + approved + "/ddl"),
                stranger.put("/api/physical/view/" + approved, save("VW_AUTH", "SELECT 7", null)), stranger.delete("/api/physical/view/" + approved),
                stranger.patch("/api/physical/view/" + approved + "/approval/false"), stranger.patch("/api/physical/view/request/" + requestId + "/true"),
                stranger.postJson("/api/physical/view", save("VW_STEAL", "SELECT 1", tableId)))) {
            error(response, 400, "INVALID_PHYSICAL_MODEL");
        }
        assertThat(JSON.readTree(stranger.get("/api/physical/view/data?page=0&size=10").body()).path("recordsTotal").asLong()).isZero();
        assertThat(stranger.get("/api/physical/view/targets?keyword=TB_AUTH").body()).isEqualTo("[]");
        assertThat(stranger.get("/api/erd/views").body()).isEqualTo("[]");

        // ---------- 비로그인
        error(new Browser(port).get("/api/physical/view/data?page=0&size=10"), 401, null);

        // 관리자가 USER 의 요청을 승인하면 반영된다 (사용자당 세션 하나 : 마지막에 로그인한 브라우저로 프로젝트를 바꿔 들어간다)
        assertThat(stranger.login("admin", projectId).statusCode()).isEqualTo(200);
        assertThat(ok(stranger.patch("/api/physical/view/request/" + requestId + "/true")).path("status").asString()).isEqualTo("APPROVED");
        assertThat(JSON.readTree(stranger.get("/api/physical/view/" + approved).body()).path("view").path("definition").asString()).isEqualTo("SELECT 9");
    }

    /** 조회 API 는 모두 200 */
    private static void readable(Browser browser, long viewId) throws Exception {
        for (String path : List.of("/api/physical/view/data?page=0&size=10", "/api/physical/view/" + viewId, "/api/physical/view/" + viewId + "/ddl",
                "/api/physical/view/ddl", "/api/physical/view/targets?keyword=TB", "/api/erd/views")) {
            assertThat(browser.get(path).statusCode()).as(path).isEqualTo(200);
        }
        assertThat(browser.get("/api/physical/view/" + viewId).body()).contains("\"definition\":\"SELECT", "\"references\":[", "\"columns\":[");
    }

    private static String save(String name, String sql, Long tableId) {
        String references = tableId == null ? "[]" : "[{\"referenceType\":\"TABLE\",\"tableId\":" + tableId + "}]";
        return "{\"viewName\":\"" + name + "\",\"definition\":\"" + sql + "\",\"columns\":[{\"columnName\":\"C1\"}],\"references\":" + references + "}";
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static long id(JsonNode body) {
        return body.path("id").asLong();
    }

    private static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.uri() + " " + response.body()).isEqualTo(status);
        if (code != null) assertThat(JSON.readTree(response.body()).path("code").asString()).as(response.body()).isEqualTo(code);
    }

    // ---------- 준비 (ProjectAccessRevokeTest 와 같은 관리자 API)

    private void setRole(String username, String role) throws Exception {
        String accountId = null;
        for (JsonNode account : JSON.readTree(admin.get("/api/selectUser/data?page=0&size=1000").body()).path("data")) {
            if (username.equals(account.path("username").asString())) accountId = account.path("id").asString();
        }
        assertThat(accountId).isNotNull();
        assertThat(admin.put("/api/saveUser/update", "[{\"id\":\"" + accountId + "\",\"role\":\"" + role + "\"}]").statusCode()).isEqualTo(200);
    }

    private long createProject() throws Exception {
        String name = "VIEWAUTH_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(admin.postJson("/api/saveProject/project", """
                {"projectName":"%s","isActive":true,"isAutoActive":true,"isDicAbbrUsed":false,"tablePrefix":"","dbType":"POSTGRESQL"}
                """.formatted(name)).statusCode()).isEqualTo(200);
        for (JsonNode project : JSON.readTree(admin.get("/api/getProject/managed").body())) {
            if (name.equals(project.path("projectName").asString())) return project.path("id").asLong();
        }
        throw new AssertionError("생성한 프로젝트가 없습니다: " + name);
    }

    private String signup(long project) throws Exception {
        String username = "va" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpResponse<String> response = new Browser(port).postJson("/api/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"권한테스트\",\"projectId\":" + project + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return username;
    }

    /** 사용자 한 명의 브라우저 : 쿠키(SESSION, XSRF) + 메모리의 CSRF 토큰 (React 와 같은 방식) */
    private static class Browser {
        private final int port;
        private final HttpClient client;
        private String csrf;

        Browser(int port) throws Exception {
            this.port = port;
            this.client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
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
            return send(json(path).POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        }

        HttpResponse<String> put(String path, String json) throws IOException, InterruptedException {
            return send(json(path).PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        }

        HttpResponse<String> patch(String path) throws IOException, InterruptedException {
            return send(json(path).method("PATCH", HttpRequest.BodyPublishers.noBody()));
        }

        HttpResponse<String> delete(String path) throws IOException, InterruptedException {
            return send(json(path).DELETE());
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
