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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 로그(TB_LOG)의 응답 상태 = 실제 HTTP 응답 상태 (7차 P0). 실제 서버(랜덤 포트, Security Filter Chain, 세션·CSRF)로 요청마다 로그가 한 건이고
 * 상태가 같은지 본다 : 일반 API 200, {@code @PreAuthorize} 거부 403 (예전에는 200 으로 남았다), 프로젝트 권한 거부(서비스) 403, VIEW 정의 원문 거부 403,
 * 모델 Import 권한 거부 403, 프로젝트 접근 취소 401, 처리되지 않은 예외 500. 로그인하지 않은 401 은 지금처럼 남기지 않는다.
 * DB 는 이 클래스 전용 (H2 별도 인메모리 / PostgreSQL 별도 스키마).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(RequestLogStatusHttpTest.FailingController.class)
class RequestLogStatusHttpTest {

    private static final String CONFIGURED_URL = System.getProperty("spring.datasource.url", System.getenv("SPRING_DATASOURCE_URL"));
    private static final boolean POSTGRESQL = CONFIGURED_URL != null && CONFIGURED_URL.startsWith("jdbc:postgresql:");
    private static final String SCHEMA = "reqlog_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Map<String, String> DB_PROPERTIES = POSTGRESQL
            ? Map.of("spring.datasource.url", CONFIGURED_URL + (CONFIGURED_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                     "spring.flyway.default-schema", SCHEMA)
            : Map.of("spring.datasource.url", "jdbc:h2:mem:lexorma-reqlog;DB_CLOSE_DELAY=-1");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB_PROPERTIES.forEach((key, value) -> registry.add(key, () -> value));
    }

    private static final String PASSWORD = "ReqLog!Pass12";
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

    /** 처리되지 않은 예외(5xx)를 만드는 테스트 전용 API — 이 클래스의 컨텍스트에만 있다 (운영 코드에는 없다) */
    @RestController
    static class FailingController {
        @GetMapping("/api/test/requestLog/unhandled")
        @PreAuthorize("hasRole('VIEWER')")
        public String unhandled() {
            throw new IllegalStateException("요청 로그 상태 확인용 실패");
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Browser admin;
    private long projectId;
    private long otherProjectId;
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
        // USER 를 다른 프로젝트의 일반 구성원으로도 넣는다 (관리자는 아니다)
        jdbcTemplate.update("insert into TB_PRJ_MBR(PRJ_MBR_SN, PRJ_SN, ACNT_SN, ACTVTN_YN) select 990001, ?, ACNT_SN, true from TB_ACNT where USER_ID = ?", otherProjectId, userName);
    }

    @AfterAll
    void restore() throws Exception {
        if (PREVIOUS_AUTH_UTIL_REPOSITORY != null) AUTH_UTIL_REPOSITORY.set(null, PREVIOUS_AUTH_UTIL_REPOSITORY);
        if (POSTGRESQL) jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("요청마다 로그 한 건, 로그의 상태 = 실제 HTTP 상태 : 200 · @PreAuthorize 403 · 서비스 권한 403 · VIEW 정의 403 · Import 403 · 접근 취소 401 · 500, 비로그인 401 은 남기지 않는다")
    void statusMatchesResponse() throws Exception {
        Browser user = new Browser(port);
        assertThat(user.login(userName, projectId).statusCode()).isEqualTo(200);
        Browser viewer = new Browser(port);
        assertThat(viewer.login(viewerName, projectId).statusCode()).isEqualTo(200);

        // 일반 API
        logged(user, user.get("/api/schema"), 200, userName);
        // Spring Security (@PreAuthorize) 거부 : VIEWER 는 모델 Import 계획을 만들 수 없다
        String plan = "{\"targetProjectId\":null,\"document\":{\"format\":\"lexorma-model\",\"formatVersion\":1,\"model\":{}},\"selection\":null}";
        logged(viewer, viewer.postJson("/api/modelExchange/plan", plan), 403, viewerName);
        // 프로젝트 권한 거부 (서비스가 DB 로 확인) : 구성원이지만 관리자가 아닌 프로젝트로 가져오기
        String foreign = plan.replace("\"targetProjectId\":null", "\"targetProjectId\":" + otherProjectId);
        logged(user, user.postJson("/api/modelExchange/plan", foreign), 403, userName);
        // VIEW 정의 원문 : 프로젝트 관리자·ADMIN 만 (USER 는 서비스에서, VIEWER 는 @PreAuthorize 에서 거부)
        logged(user, user.get("/api/source/tables/1/viewDefinition"), 403, userName);
        logged(viewer, viewer.get("/api/source/tables/1/viewDefinition"), 403, viewerName);
        // 처리되지 않은 예외 : 컨테이너가 500 으로 응답한다
        logged(user, user.get("/api/test/requestLog/unhandled"), 500, userName);

        // 로그인하지 않은 요청 : 401, 로그는 지금처럼 남기지 않는다
        int before = logCount();
        assertThat(new Browser(port).get("/api/schema").statusCode()).isEqualTo(401);
        assertThat(logCount()).isEqualTo(before);

        // 프로젝트 접근 취소 : 세션을 끊는 401 도 요청한 사용자로 한 건
        jdbcTemplate.update("update TB_PRJ_MBR set ACTVTN_YN = false where PRJ_SN = ? and ACNT_SN = (select ACNT_SN from TB_ACNT where USER_ID = ?)", projectId, viewerName);
        logged(viewer, viewer.get("/api/schema"), 401, viewerName);

        // 보호 대상 경로는 상태만 정확하고 본문은 그대로 보호한다
        assertThat(jdbcTemplate.queryForList("select distinct DMND_CN || '/' || RSPNS_CN from TB_LOG where DMND_URL_ADDR like '/api/source/%'", String.class))
                .containsExactly("[PROTECTED]/[PROTECTED]");
    }

    /** 응답 하나에 로그가 정확히 한 건 늘고 그 상태가 응답 상태와 같다 */
    private void logged(Browser client, HttpResponse<String> response, int expected, String username) {
        assertThat(response.statusCode()).as(response.uri() + " " + response.body()).isEqualTo(expected);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select USER_ID, RSPNS_STTS_CD from TB_LOG where DMND_URL_ADDR = ? and LOG_SN > ? order by LOG_SN", response.uri().getPath(), client.lastLogId);
        assertThat(rows).as(response.uri().getPath()).hasSize(1);
        assertThat(((Number) rows.getFirst().get("RSPNS_STTS_CD")).intValue()).as(response.uri().getPath()).isEqualTo(response.statusCode());
        assertThat(rows.getFirst().get("USER_ID")).isEqualTo(username);
        client.lastLogId = maxLogId();
    }

    private int logCount() {
        return jdbcTemplate.queryForObject("select count(*) from TB_LOG", Integer.class);
    }

    private long maxLogId() {
        return jdbcTemplate.queryForObject("select coalesce(max(LOG_SN), 0) from TB_LOG", Long.class);
    }

    // ---------- 준비 (ProjectAccessRevokeTest 와 같은 관리자 API)

    private void setRole(String username, String role) throws Exception {
        String accountId = null;
        // 사용자 목록은 한 번에 100명까지 (공통 페이징 안정성 개선) : 페이지를 넘기며 찾는다
        for (int page = 0; accountId == null; page++) {
            JsonNode data = JSON.readTree(admin.get("/api/selectUser/data?page=" + page + "&size=100").body()).path("data");
            if (data.isEmpty()) break;
            for (JsonNode account : data) {
                if (username.equals(account.path("username").asString())) accountId = account.path("id").asString();
            }
        }
        assertThat(accountId).isNotNull();
        assertThat(admin.put("/api/saveUser/update", "[{\"id\":\"" + accountId + "\",\"role\":\"" + role + "\"}]").statusCode()).isEqualTo(200);
    }

    private long createProject() throws Exception {
        String name = "REQLOG_" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(admin.postJson("/api/saveProject/project", """
                {"projectName":"%s","isActive":true,"isAutoActive":true,"isDicAbbrUsed":false,"tablePrefix":"","dbType":"POSTGRESQL"}
                """.formatted(name)).statusCode()).isEqualTo(200);
        for (JsonNode project : JSON.readTree(admin.get("/api/getProject/managed").body())) {
            if (name.equals(project.path("projectName").asString())) return project.path("id").asLong();
        }
        throw new AssertionError("생성한 프로젝트가 없습니다: " + name);
    }

    private String signup(long project) throws Exception {
        String username = "rl" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpResponse<String> response = new Browser(port).postJson("/api/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"로그테스트\",\"projectId\":" + project + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return username;
    }

    /** 사용자 한 명의 브라우저 : 쿠키(SESSION, XSRF) + 메모리의 CSRF 토큰 (React 와 같은 방식) */
    private class Browser {
        private final int port;
        private final HttpClient client;
        private String csrf;
        long lastLogId;

        Browser(int port) throws Exception {
            this.port = port;
            this.client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER).build();
            refreshCsrf();
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            lastLogId = maxLogId();
            return client.send(builder.header("X-Requested-With", "XMLHttpRequest").build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET());
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
