package com.lexorma;

import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.repository.LogsRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스모크 테스트 : 실제 서버(랜덤 포트)를 띄워 로그인(JSON) → React 화면 경로 / 주요 API → 시스템 로그 저장 → 로그아웃까지 확인한다.
 * <p>
 * 프레임워크 업그레이드(Boot 3.5 / 4.x) 시 테스트 자동 설정 모듈 변경의 영향을 받지 않도록
 * MockMvc/TestRestTemplate 대신 JDK HttpClient를 사용한다.
 * 동시 세션이 1개로 제한(maximumSessions(1), 나중 로그인 우선)되어 있으므로 클래스 단위로 한 번만 로그인하고 종료 시 로그아웃한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SmokeTest {

    private static final String USERNAME = "admin";
    /** 초기 비밀번호 (V0 초기 데이터) : 변경 전에는 로그인할 수 없다 */
    private static final String INITIAL_PASSWORD = "admin";
    /** 로그인 전 비밀번호 변경 단계에서 바꾼 비밀번호 */
    private static final String PASSWORD = "Smoke!Pass1";
    private static final long PROJECT_ID = 0L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private LogsRepository logsRepository;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private CookieManager cookieManager;
    private HttpClient client;
    private String csrfToken;

    @BeforeAll
    void login() throws Exception {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 1. 로그인 화면(React) : 로그인 전에는 세션을 만들지 않는다 (CSRF 토큰은 쿠키 저장소)
        assertThat(get("/login", false).statusCode()).isEqualTo(200);
        assertThat(refreshCsrf()).isNotBlank();
        assertThat(get("/api/auth/me", true).body()).contains("\"authenticated\":false");

        // 2. 초기 비밀번호(admin/admin)로는 프로젝트 조회 단계에서 차단 → 비밀번호 변경 요구
        HttpResponse<String> blocked = postJson("/api/login/projects",
                "{\"username\":\"" + USERNAME + "\",\"password\":\"" + INITIAL_PASSWORD + "\"}");
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(blocked.body()).contains("PASSWORD_CHANGE_REQUIRED");

        // 화면을 거치지 않고 로그인 API 를 직접 호출해도 차단된다
        HttpResponse<String> blockedLogin = postForm("/api/auth/login",
                "username=" + USERNAME + "&password=" + INITIAL_PASSWORD + "&project_id=" + PROJECT_ID);
        assertThat(blockedLogin.statusCode()).isEqualTo(401);
        assertThat(blockedLogin.body()).contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\"");

        // 3. 로그인 전 비밀번호 변경 (규칙 위반은 거부)
        assertThat(postJson("/api/login/password", "{\"username\":\"" + USERNAME + "\",\"password\":\"" + INITIAL_PASSWORD
                + "\",\"newPassword\":\"weak\"}").statusCode()).isEqualTo(400);
        HttpResponse<String> changed = postJson("/api/login/password", "{\"username\":\"" + USERNAME + "\",\"password\":\"" + INITIAL_PASSWORD
                + "\",\"newPassword\":\"" + PASSWORD + "\"}");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);

        // 4. 새 비밀번호로 로그인 가능 프로젝트 조회
        HttpResponse<String> projects = postJson("/api/login/projects",
                "{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(projects.statusCode()).isEqualTo(200);
        assertThat(projects.body()).contains("\"projectName\":\"LEXORMA\"");

        // 5. 로그인 (선택한 프로젝트 포함) : JSON 응답 + 세션 쿠키(HttpOnly)
        HttpResponse<String> login = postForm("/api/auth/login",
                "username=" + USERNAME + "&password=" + PASSWORD + "&project_id=" + PROJECT_ID);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertThat(login.body()).contains("\"authenticated\":true");
        assertThat(login.headers().allValues("Set-Cookie")).anyMatch(c -> c.contains("HttpOnly") && c.contains("SameSite=Lax"));

        // 로그인하면 CSRF 토큰이 바뀐다 (세션 고정 / 토큰 고정 방지) : 다시 받는다
        refreshCsrf();
    }

    @AfterAll
    void logout() throws Exception {
        if (client != null && get("/api/auth/me", true).body().contains("\"authenticated\":true")) {
            postJson("/api/auth/logout", "");
        }
    }

    @Test
    @Order(1)
    @DisplayName("로그인 정보 : 권한 계층 적용으로 ADMIN 은 모든 메뉴가 보이고 승인 권한이 있다")
    void me() throws Exception {
        HttpResponse<String> me = get("/api/auth/me", true);

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body())
                .contains("\"authenticated\":true")
                .contains("\"username\":\"admin\"")
                .contains("\"projectName\":\"LEXORMA\"")
                .contains("\"approvalAvailable\":true")
                .doesNotContain("\":false"); // 모든 메뉴 true
    }

    @Test
    @Order(1)
    @DisplayName("세션은 Spring Session JDBC 로 DB(SPRING_SESSION)에 저장된다")
    void sessionStoredInDatabase() {
        Integer sessions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Integer.class, USERNAME);
        assertThat(sessions).isEqualTo(1);

        Integer attributes = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES a JOIN SPRING_SESSION s"
                + " ON a.SESSION_PRIMARY_ID = s.PRIMARY_ID WHERE s.PRINCIPAL_NAME = ? AND a.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'", Integer.class, USERNAME);
        assertThat(attributes).isEqualTo(1);
    }

    @Test
    @Order(1)
    @DisplayName("비로그인 요청 : API 는 JSON 401, 화면 경로는 React index.html")
    void anonymousAccess() throws Exception {
        HttpClient anonymous = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> api = anonymous.send(HttpRequest.newBuilder(uri("/api/selectTable/data?page=0&size=10")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(401);
        assertThat(api.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(api.body()).contains("\"code\":\"UNAUTHORIZED\"");
        // 401 응답 때문에 익명 세션(DB 행)을 만들지 않는다
        assertThat(api.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));

        // 메시지는 Accept-Language 를 따른다 : 없으면 한국어, 한국어가 아니면 영어 (Security 필터 단계 / 컨트롤러 예외 모두)
        assertThat(api.body()).contains("로그인이 필요합니다.");
        HttpResponse<String> english = anonymous.send(HttpRequest.newBuilder(uri("/api/selectTable/data?page=0&size=10"))
                .header("Accept-Language", "en").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(english.body()).contains("Login is required.");
        HttpResponse<String> notFound = anonymous.send(HttpRequest.newBuilder(uri("/images/not-exists.png"))
                .header("Accept-Language", "en-US,en;q=0.9").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(notFound.body()).contains("The requested resource could not be found.");

        HttpResponse<String> page = anonymous.send(HttpRequest.newBuilder(uri("/tableManage")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("id=\"root\"");
    }

    @Test
    @Order(2)
    @DisplayName("접근 권한이 없는 프로젝트로는 로그인할 수 없다 / CSRF 토큰 없는 로그인은 거부")
    void loginWithInaccessibleProject() throws Exception {
        CookieManager otherCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient other = HttpClient.newBuilder().cookieHandler(otherCookies)
                .followRedirects(HttpClient.Redirect.NEVER).build();

        String body = "username=" + USERNAME + "&password=" + PASSWORD + "&project_id=99999";

        // CSRF 토큰 없음 → 403
        HttpResponse<String> noToken = other.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noToken.statusCode()).isEqualTo(403);
        assertThat(noToken.body()).contains("\"code\":\"CSRF_INVALID\"");

        String token = readCsrf(other);
        HttpResponse<String> login = other.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.body()).contains("\"code\":\"PROJECT_ACCESS_DENIED\"");
    }

    @Test
    @Order(3)
    @DisplayName("다른 곳에서 로그인하면 먼저 로그인한 세션이 중복 로그인 안내(401)를 받고 로그아웃된다 (maximumSessions=1, 세션 DB 기준)")
    void laterLoginExpiresEarlierSession() throws Exception {
        CookieManager otherCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient other = HttpClient.newBuilder().cookieHandler(otherCookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String form = "username=" + USERNAME + "&password=" + PASSWORD + "&project_id=" + PROJECT_ID;

        HttpResponse<String> login = other.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", readCsrf(other))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);

        // 먼저 로그인한 쪽 : 다음 요청에서 안내를 받고, 그 뒤로는 로그인하지 않은 상태다
        HttpResponse<String> expired = get("/api/selectTable/data?page=0&size=10", true);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.body()).contains("\"code\":\"DUPLICATE_LOGIN\"");
        assertThat(get("/api/auth/me", true).body()).contains("\"authenticated\":false");

        // 나머지 테스트가 쓰는 세션으로 다시 로그인한다 (이번에는 other 쪽이 만료된다)
        refreshCsrf();
        assertThat(postForm("/api/auth/login", form).statusCode()).isEqualTo(200);
        refreshCsrf();
        HttpResponse<String> otherExpired = other.send(HttpRequest.newBuilder(uri("/api/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(otherExpired.body()).contains("\"code\":\"DUPLICATE_LOGIN\"");
    }

    @Test
    @Order(4)
    @DisplayName("변경 요청은 CSRF 토큰이 있어야 한다")
    void csrfRequired() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/updateName"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"관리자\"}", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"CSRF_INVALID\"");
    }

    @ParameterizedTest(name = "화면 {0}")
    @ValueSource(strings = {
            "/", "/login", "/signup", "/account", "/notice", "/dataDictionary", "/schemaManage", "/tableManage", "/columnManage", "/physicalErd",
            "/userManage", "/systemLog", "/projectManage", "/help", "/denied"
    })
    @Order(10)
    @DisplayName("React 화면 경로 : 직접 열거나 새로고침해도 index.html (no-cache)")
    void pages(String path) throws Exception {
        HttpResponse<String> response = get(path, false);

        assertThat(response.statusCode()).as(path).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).as(path).contains("text/html");
        assertThat(response.body()).as(path).contains("id=\"root\"").contains("/assets/");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).as(path).contains("no-cache");
    }

    @ParameterizedTest(name = "없는 경로 {0}")
    @ValueSource(strings = {"/api/not-exists", "/api/selectTable/not/exists", "/assets/not-exists.js", "/images/none.png"})
    @Order(10)
    @DisplayName("없는 API / 정적 파일은 React HTML 이 아니라 JSON 404")
    void notFound(String path) throws Exception {
        HttpResponse<String> response = get(path, true);

        assertThat(response.statusCode()).as(path).isEqualTo(404);
        assertThat(response.body()).as(path).doesNotContain("id=\"root\"").contains("\"status\":404");
    }

    @Test
    @Order(11)
    @DisplayName("V0 기본 안내 공지의 목록·상세·작성자·조회 상태를 확인한다")
    void welcomeNotice() throws Exception {
        HttpResponse<String> list = get("/api/selectNotice/data?page=0&size=10&sort=id,asc", true);
        assertThat(list.statusCode()).isEqualTo(200);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var first = mapper.readTree(list.body()).path("data").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(1L);
        assertThat(first.path("title").asString()).isEqualTo("[안내] Lexorma에 오신 것을 환영합니다.");
        assertThat(first.path("systemNotice").asBoolean()).isTrue();
        assertThat(first.path("isHit").asString()).isEqualTo("확인");

        HttpResponse<String> view = get("/api/selectNotice/view/1", true);
        assertThat(view.statusCode()).isEqualTo(200);
        var notice = mapper.readTree(view.body());
        assertThat(notice.path("username").asString()).isEqualTo("admin");
        assertThat(notice.path("content").asString()).contains("물리 ERD", "이용 안내", "문의 및 오류 신고",
                "https://github.com/Lexorma/Lexorma/issues");
        assertThat(notice.path("accounts").size()).isEqualTo(1);
        assertThat(get("/api/selectNotice/view/1", true).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(get("/api/selectNotice/view/1", true).body()).path("accounts").size()).isEqualTo(1);

        // 영어 화면(Accept-Language)에는 영어판으로 나가고, 시스템이 등록한 공지는 수정할 수 없다.
        var english = mapper.readTree(client.send(HttpRequest.newBuilder(uri("/api/selectNotice/view/1"))
                .header("Accept-Language", "en").GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(english.path("title").asString()).isEqualTo("[Notice] Welcome to Lexorma");
        assertThat(english.path("content").asString()).contains("Physical ERD", "Getting started", "Questions and bug reports");
        HttpResponse<String> update = client.send(HttpRequest.newBuilder(uri("/api/saveNotice"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .PUT(HttpRequest.BodyPublishers.ofString("{\"id\":1,\"title\":\"t\",\"content\":\"c\",\"projectIds\":[" + PROJECT_ID + "]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(update.statusCode()).isEqualTo(400);
        assertThat(update.body()).contains("SYSTEM_NOTICE_CANNOT_BE_UPDATE");
    }

    @Test
    @Order(11)
    @DisplayName("공지사항 등록 → 상세(조회자 기록) / 대상 프로젝트 / 관리 권한 API")
    void noticeApis() throws Exception {
        String title = "smoke-notice-" + UUID.randomUUID();
        HttpResponse<String> save = postJson("/api/saveNotice",
                "{\"title\":\"" + title + "\",\"content\":\"공지 내용\",\"projectIds\":[" + PROJECT_ID + "]}");
        assertThat(save.statusCode()).isEqualTo(200);

        HttpResponse<String> list = get("/api/selectNotice/data?page=0&size=100", true);
        String noticeId = null;
        for (tools.jackson.databind.JsonNode row : tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(list.body()).path("data")) {
            if (title.equals(row.path("title").asString())) {
                noticeId = row.path("id").asString();
            }
        }
        assertThat(noticeId).as("등록한 공지사항이 목록에 있어야 한다").isNotBlank();

        HttpResponse<String> view = get("/api/selectNotice/view/" + noticeId, true);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(view.body()).contains(title).contains("\"username\":\"admin\"");

        assertThat(get("/api/selectNotice/projects?type=add", true).body()).contains("\"projectName\":\"LEXORMA\"");
        assertThat(get("/api/selectNotice/projects?type=modify&id=" + noticeId, true).body()).contains("\"isSelected\":true");
        assertThat(get("/api/selectNotice/permission", true).body()).contains("\"isProjectManager\":true");
    }

    @Test
    @Order(12)
    @DisplayName("정적 리소스 캐시 : 해시가 붙은 /assets 는 1년(immutable), 이미지는 7일, API 는 캐시하지 않음")
    void staticCache() throws Exception {
        String index = get("/", false).body();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("/assets/[^\"]+\\.js").matcher(index);
        assertThat(matcher.find()).as("index.html 의 JS").isTrue();

        HttpResponse<String> js = get(matcher.group(), false);
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.headers().firstValue("Cache-Control").orElse("")).contains("max-age=31536000").contains("immutable").doesNotContain("no-store");

        HttpResponse<String> image = get("/images/favicon.svg", false);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Cache-Control").orElse("")).contains("max-age=604800");

        assertThat(get("/api/auth/me", true).headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        // 엑셀 템플릿(classpath:/static/excel)은 URL 로 바로 열리지 않는다 (다운로드 API 로만)
        assertThat(get("/excel/template.xlsx", false).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest(name = "그리드 {0}")
    @ValueSource(strings = {
            "/api/getStandardWords", "/api/getStandardTerms", "/api/getStandardDomains",
            "/api/selectTable", "/api/selectColumn",
            "/api/selectNotice", "/api/selectUser", "/api/getSystemLog", "/api/getProject/" + PROJECT_ID
    })
    @Order(20)
    @DisplayName("그리드 데이터 API")
    void gridApis(String dataUrl) throws Exception {
        assertThat(get(dataUrl + "/column", true).statusCode()).isEqualTo(404);

        HttpResponse<String> data = get(dataUrl + "/data?page=0&size=10", true);
        assertThat(data.statusCode()).as(dataUrl + "/data").isEqualTo(200);
        assertThat(data.body()).as(dataUrl + "/data").contains("\"recordsTotal\"");
    }

    @ParameterizedTest(name = "표준용어 도메인 정렬 {0}")
    @ValueSource(strings = {"commonStandardDomainName", "allowedValues", "storageFormat", "displayFormat"})
    @Order(20)
    @DisplayName("표준용어의 도메인 필드 정렬은 검색 유무와 관계없이 동작한다")
    void standardTermDomainSort(String field) throws Exception {
        for (String direction : List.of("asc", "desc")) {
            for (String query : List.of("", "&searchColumn=commonStandardDomainName&searchQuery=V")) {
                HttpResponse<String> response = get("/api/getStandardTerms/data?page=0&size=10&sort="
                        + field + "," + direction + ";id,asc" + query, true);
                assertThat(response.statusCode()).as(field + "," + direction + query).isEqualTo(200);
                assertThat(response.body()).contains("\"recordsTotal\"");
            }
        }
    }

    @Test
    @Order(20)
    @DisplayName("M-3 스키마 관리 API : 목록(테이블 수·등록 정보) → 등록 → 이름 변경 → 스키마별 테이블 조회 → 삭제, 기본 스키마 보호")
    void schemaManagement() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var defaultSchema = mapper.readTree(get("/api/schema", true).body()).get(0);
        assertThat(defaultSchema.path("isDefault").asBoolean()).isTrue();
        assertThat(defaultSchema.path("schemaName").asString()).isEqualTo("DEFAULT");
        assertThat(defaultSchema.path("tableCount").asLong()).as("V0 예제 테이블").isPositive();
        assertThat(defaultSchema.path("created").asString()).isNotBlank();
        long defaultId = defaultSchema.path("id").asLong();

        String name = "smoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        HttpResponse<String> created = postJson("/api/schema", "{\"schemaName\":\"" + name + "\",\"schemaDescription\":\"스모크\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        long id = mapper.readTree(created.body()).path("id").asLong();
        assertThat(postJson("/api/schema", "{\"schemaName\":\"" + name.toUpperCase() + "\"}").body()).contains("SAVED_DATA_EXISTS");

        HttpResponse<String> renamed = postJson("/api/schema", "{\"id\":" + id + ",\"schemaName\":\"" + name + "_v2\"}");
        assertThat(mapper.readTree(renamed.body()).path("id").asLong()).as("이름을 바꿔도 ID 는 그대로").isEqualTo(id);
        assertThat(get("/api/schema", true).body()).contains("\"schemaName\":\"" + name + "_v2\"").contains("\"tableCount\":0");

        // 테이블 목록 : 스키마별 조회 (빈 스키마는 0건, 기본 스키마는 예제 테이블), 테이블 1건 조회
        assertThat(get("/api/selectTable/data?page=0&size=10&schemaId=" + id, true).body()).contains("\"recordsTotal\":0");
        var tables = mapper.readTree(get("/api/selectTable/data?page=0&size=10&sort=schemaName,asc&schemaId=" + defaultId, true).body());
        assertThat(tables.path("recordsTotal").asLong()).isEqualTo(defaultSchema.path("tableCount").asLong());
        var table = tables.path("data").get(0);
        assertThat(table.path("schemaName").asString()).isEqualTo("DEFAULT");
        var detail = mapper.readTree(get("/api/selectTable/detail/" + table.path("id").asLong(), true).body());
        assertThat(detail.path("tableName").asString()).isEqualTo(table.path("tableName").asString());
        assertThat(get("/api/selectTable/detail/999999999", true).body()).contains("TABLE_IS_NOT_DEFINED");

        // 컬럼 목록에는 소속 스키마와 테이블 ID, ERD 컬럼에는 표준 도메인명이 함께 내려온다
        assertThat(get("/api/selectColumn/data?page=0&size=1", true).body()).contains("\"schemaName\":\"DEFAULT\"").contains("\"tableInfoId\":");
        assertThat(get("/api/erd?page=0&size=5", true).body()).contains("\"domainName\":");

        // 기본 스키마 : 이름 변경·삭제 불가
        assertThat(postJson("/api/schema", "{\"id\":" + defaultId + ",\"schemaName\":\"main\"}").body()).contains("INVALID_SCHEMA_NAME");
        assertThat(delete("/api/schema/" + defaultId).statusCode()).isEqualTo(400);
        assertThat(delete("/api/schema/" + id).statusCode()).isEqualTo(200);
        assertThat(get("/api/schema", true).body()).doesNotContain(name);
    }

    @Test
    @Order(20)
    @DisplayName("M-5 사용 현황 API : 목록의 사용 건수, 용어·도메인 → 컬럼, 단어 → 용어, 조건, 없는 ID")
    void dictionaryUsage() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        // 목록 3종에 사용 건수가 내려온다 (V0 예제 : 표준 용어 1 을 쓰는 컬럼이 있다)
        var term = mapper.readTree(get("/api/getStandardTerms/data?page=0&size=1&sort=id,asc", true).body()).path("data").get(0);
        assertThat(term.path("id").asLong()).isEqualTo(1L);
        long termUsage = term.path("usageCount").asLong();
        assertThat(termUsage).isPositive();
        assertThat(get("/api/getStandardWords/data?page=0&size=1", true).body()).contains("\"usageCount\":");
        assertThat(get("/api/getStandardDomains/data?page=0&size=1", true).body()).contains("\"usageCount\":");

        // 용어 → 컬럼 : 건수가 목록의 사용 건수와 같고, 이동에 필요한 ID 가 함께 온다
        var columns = mapper.readTree(get("/api/dictionaryUsage/term/1/data?page=0&size=5", true).body());
        assertThat(columns.path("recordsTotal").asLong()).isEqualTo(termUsage);
        var column = columns.path("data").get(0);
        assertThat(column.path("tableId").asLong()).isPositive();
        assertThat(column.path("schemaName").asString()).isEqualTo("DEFAULT");
        assertThat(column.path("termName").asString()).isEqualTo(term.path("commonStandardTermName").asString());
        long tableId = column.path("tableId").asLong();
        long schemaId = column.path("schemaId").asLong();

        // 조건 : 스키마(있는 것 / 다른 프로젝트·없는 것), 승인 상태, 검색
        assertThat(mapper.readTree(get("/api/dictionaryUsage/term/1/data?page=0&size=5&schemaId=" + schemaId, true).body()).path("recordsTotal").asLong()).isEqualTo(termUsage);
        assertThat(get("/api/dictionaryUsage/term/1/data?page=0&size=5&schemaId=999999999", true).body()).contains("\"recordsTotal\":0");
        assertThat(get("/api/dictionaryUsage/term/1/data?page=0&size=5&isApproval=false&searchColumn=tableName&searchQuery=TB_", true).statusCode()).isEqualTo(200);

        // 도메인 → 컬럼 (용어를 거친 간접 참조), 단어 → 용어 (용어별 사용 컬럼 수)
        long domainId = jdbcTemplate.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = 1", Long.class);
        assertThat(mapper.readTree(get("/api/dictionaryUsage/domain/" + domainId + "/data?page=0&size=5", true).body()).path("recordsTotal").asLong())
                .isGreaterThanOrEqualTo(termUsage);
        var terms = mapper.readTree(get("/api/dictionaryUsage/word/12/data?page=0&size=100", true).body());
        assertThat(terms.path("recordsTotal").asLong()).isPositive();
        assertThat(terms.path("data").get(0).has("columnCount")).isTrue();

        // 없는 표준 항목
        assertThat(get("/api/dictionaryUsage/term/999999999/data?page=0&size=5", true).body()).contains("NOT_FOUND_TERM_DATA");
        assertThat(get("/api/dictionaryUsage/domain/999999999/data?page=0&size=5", true).body()).contains("NOT_FOUND_DOMAIN_DATA");
        assertThat(get("/api/dictionaryUsage/word/999999999/data?page=0&size=5", true).body()).contains("NOT_FOUND_WORD_DATA");

        // 컬럼 관리 이동 : 테이블 ID 로 그 테이블의 컬럼만
        var tableColumns = mapper.readTree(get("/api/selectColumn/data?page=0&size=100&tableId=" + tableId, true).body()).path("data");
        assertThat(tableColumns.size()).isPositive();
        for (var tableColumn : tableColumns) {
            assertThat(tableColumn.path("tableInfoId").asLong()).isEqualTo(tableId);
        }
    }

    @Test
    @Order(20)
    @DisplayName("M-5 표준화 품질 진단 API : 요약과 목록의 건수가 같고, 유형 조건·잘못된 유형·메뉴 권한")
    void standardizationQuality() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        var summary = mapper.readTree(get("/api/standardizationQuality/summary", true).body());
        assertThat(summary.path("totalColumns").asLong()).as("V0 예제 물리 모델").isPositive();
        // V0 예제는 용어의 도메인을 실제 컬럼 정의에 맞춰 두었다 (docs/db-naming-standard.md) : 진단 결과가 없어야 한다
        assertThat(summary.path("issueColumns").asLong()).as(get("/api/standardizationQuality/data?page=0&size=20", true).body()).isZero();
        long byType = 0;
        for (var type : Map.of("TERM_NOT_LINKED", "termNotLinked", "COLUMN_NAME_MISMATCH", "columnNameMismatch",
                "DOMAIN_DEFINITION_MISMATCH", "domainDefinitionMismatch").entrySet()) {
            var issues = mapper.readTree(get("/api/standardizationQuality/data?page=0&size=5&type=" + type.getKey(), true).body());
            assertThat(issues.path("recordsTotal").asLong()).as(type.getKey()).isEqualTo(summary.path(type.getValue()).asLong());
            for (var issue : issues.path("data")) {
                assertThat(issue.path("type").asString()).isEqualTo(type.getKey());
                assertThat(issue.path("tableId").asLong()).isPositive();
                assertThat(issue.path("schemaName").asString()).isEqualTo("DEFAULT");
            }
            byType += issues.path("recordsTotal").asLong();
        }
        assertThat(mapper.readTree(get("/api/standardizationQuality/data?page=0&size=5", true).body()).path("recordsTotal").asLong()).isEqualTo(byType);
        assertThat(summary.path("issueColumns").asLong()).isBetween(byType == 0 ? 0L : 1L, Math.min(byType, summary.path("totalColumns").asLong()));

        assertThat(get("/api/standardizationQuality/data?page=0&size=5&schemaId=999999999", true).body()).contains("\"recordsTotal\":0");
        assertThat(get("/api/standardizationQuality/data?page=0&size=5&type=UNKNOWN", true).statusCode()).isEqualTo(400);
        assertThat(get("/api/standardizationQuality/data", true).statusCode()).as("page·size 없음").isEqualTo(400);
        assertThat(get("/api/auth/me", true).body()).contains("\"standardization-quality\":true");
    }

    @Test
    @Order(20)
    @DisplayName("M-6 논리 모델 API : 모델 → 엔터티 → 속성(표준 용어) → 식별자 → 관계 → 논리 ERD, 오류 코드, 삭제 순서")
    void logicalModel() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String name = "smoke-" + UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> created = postJson("/api/logical/model", "{\"modelName\":\"" + name + "\",\"modelDescription\":\"스모크\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        long modelId = mapper.readTree(created.body()).path("id").asLong();
        assertThat(postJson("/api/logical/model", "{\"modelName\":\"" + name.toUpperCase() + "\"}").body()).contains("SAVED_DATA_EXISTS");
        assertThat(get("/api/logical/model", true).body()).contains(name);
        assertThat(get("/api/logical/model/" + modelId, true).body()).contains("스모크");

        long entityId = mapper.readTree(postJson("/api/logical/entity", "{\"modelId\":" + modelId + ",\"entityName\":\"계정\"}").body()).path("id").asLong();
        // V0 예제의 표준 용어 1 (계정_일련번호) 을 연결한다 : 도메인이 용어를 거쳐 내려온다
        var attribute = mapper.readTree(postJson("/api/logical/attribute", "{\"entityId\":" + entityId + ",\"attributeName\":\"계정번호\",\"isRequired\":true,\"termId\":1}").body());
        assertThat(attribute.path("termName").asString()).isEqualTo("계정_일련번호");
        assertThat(attribute.path("domainName").asString()).isNotBlank();
        long attributeId = attribute.path("id").asLong();
        long identifierId = mapper.readTree(postJson("/api/logical/identifier",
                "{\"entityId\":" + entityId + ",\"identifierName\":\"계정 식별자\",\"isPrimary\":true,\"attributeIds\":[" + attributeId + "]}").body()).path("id").asLong();
        var relationship = mapper.readTree(postJson("/api/logical/relationship", "{\"modelId\":" + modelId + ",\"relationshipName\":\"추천\",\"sourceEntityId\":" + entityId
                + ",\"targetEntityId\":" + entityId + ",\"sourceMin\":0,\"sourceMax\":1,\"targetMin\":0}").body());
        assertThat(relationship.path("cardinality").asString()).isEqualTo("1:N");
        long relationshipId = relationship.path("id").asLong();

        var graph = mapper.readTree(get("/api/logical/model/" + modelId + "/erd", true).body());
        assertThat(graph.path("entities").size()).isEqualTo(1);
        assertThat(graph.path("entities").get(0).path("attributes").get(0).path("primaryIdentifier").asBoolean()).isTrue();
        assertThat(graph.path("relationships").size()).isEqualTo(1);
        assertThat(get("/api/logical/entity/" + entityId, true).body()).contains("계정 식별자");
        assertThat(get("/api/logical/model/" + modelId + "/entity", true).body()).contains("계정");
        assertThat(get("/api/logical/entity/" + entityId + "/attribute", true).body()).contains("계정번호");
        assertThat(get("/api/logical/entity/" + entityId + "/identifier", true).body()).contains("계정 식별자");
        assertThat(get("/api/logical/model/" + modelId + "/relationship", true).body()).contains("추천");

        // M-6 2차 : 내 논리 ERD 배치 (모델별) 저장 → 조회, 오래된 version 은 409, 메뉴 권한
        String layoutUrl = "/api/logical/model/" + modelId + "/erd/layout";
        assertThat(get(layoutUrl, true).body()).contains("\"version\":null");
        HttpResponse<String> layoutSaved = putJson(layoutUrl, "{\"version\":null,\"nodes\":{\"" + entityId + "\":{\"x\":12.5,\"y\":-3}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}");
        assertThat(layoutSaved.statusCode()).as(layoutSaved.body()).isEqualTo(200);
        assertThat(get(layoutUrl, true).body()).contains("\"" + entityId + "\":{\"x\":12.5,\"y\":-3.0}");
        HttpResponse<String> layoutStale = putJson(layoutUrl, "{\"version\":null,\"nodes\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}");
        assertThat(layoutStale.statusCode()).isEqualTo(409);
        assertThat(layoutStale.body()).contains("ERD_LAYOUT_CONFLICT");
        assertThat(get("/api/auth/me", true).body())
                .contains("\"logical-model\":true", "\"logical-erd\":true", "\"logical-model-edit\":true", "\"logical-modeling\":true", "\"physical-modeling\":true")
                .contains("\"data-dictionary\":true", "\"standard-domain\":true", "\"standard-word\":true", "\"standard-term\":true", "\"data-quality\":true")
                .doesNotContain("data-management", "data-modeling", "operation-modeling");

        // 오류 : 400 + 코드
        assertThat(get("/api/logical/model/999999999/erd", true).body()).contains("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(postJson("/api/logical/entity", "{\"modelId\":" + modelId + ",\"entityName\":\" \"}").body()).contains("INVALID_LOGICAL_MODEL");
        // 관리자(승인 권한자)가 등록한 엔터티·관계는 바로 승인된다 → 승인 취소 전에는 지울 수 없다
        assertThat(get("/api/logical/entity/" + entityId, true).body()).contains("\"isApproval\":true");
        assertThat(relationship.path("isApproval").asBoolean()).isTrue();
        assertThat(delete("/api/logical/entity/" + entityId).body()).contains("APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(delete("/api/logical/relationship/" + relationshipId).body()).contains("APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(patch("/api/logical/entity/" + entityId + "/approval/false").statusCode()).isEqualTo(200);
        assertThat(patch("/api/logical/relationship/" + relationshipId + "/approval/false").statusCode()).isEqualTo(200);
        HttpResponse<String> blocked = delete("/api/logical/entity/" + entityId);
        assertThat(blocked.statusCode()).isEqualTo(400);
        assertThat(blocked.body()).contains("RELATION_EXISTS");
        assertThat(delete("/api/logical/attribute/" + attributeId).body()).contains("RELATION_EXISTS");
        assertThat(delete("/api/logical/model/" + modelId).body()).contains("RELATION_EXISTS");

        // 정리 : 관계 → 식별자 → 엔터티(속성 포함) → 모델
        assertThat(delete("/api/logical/relationship/" + relationshipId).statusCode()).isEqualTo(200);
        assertThat(delete("/api/logical/identifier/" + identifierId).statusCode()).isEqualTo(200);
        assertThat(delete("/api/logical/entity/" + entityId).statusCode()).isEqualTo(200);
        assertThat(delete("/api/logical/model/" + modelId).statusCode()).isEqualTo(200);
        assertThat(get("/api/logical/model", true).body()).doesNotContain(name);
    }

    /** 화면의 그리드 컬럼 정의(lexorma-web 의 features 별 columns.ts) → 그리드 데이터 API */
    private static final Map<String, String> GRID_DATA_URLS = Map.ofEntries(
            // 사용 현황 (V0 예제 : 표준 용어 1, 표준 단어 12)
            Map.entry("usageColumnColumns", "/api/dictionaryUsage/term/1"),
            Map.entry("usageTermColumns", "/api/dictionaryUsage/word/12"),
            Map.entry("qualityIssueColumns", "/api/standardizationQuality"),
            Map.entry("mappingEntityColumns", "/api/modelMapping/entity"),
            Map.entry("modelHistoryColumns", "/api/modelHistory"),
            Map.entry("modelVersionColumns", "/api/modelVersion"),
            // modelId 가 필수라 조회할 때 예제 모델 ID 를 붙인다 (gridSortAndSearch)
            Map.entry("mappingQualityColumns", "/api/modelMapping/quality"),
            Map.entry("recommendationColumnColumns", "/api/modelMapping/recommendation/column"),
            Map.entry("recommendationTableColumns", "/api/modelMapping/recommendation/table"),
            Map.entry("standardTermsColumns", "/api/getStandardTerms"),
            Map.entry("standardWordsColumns", "/api/getStandardWords"),
            Map.entry("standardDomainsColumns", "/api/getStandardDomains"),
            Map.entry("tableColumns", "/api/selectTable"),
            Map.entry("columnColumns", "/api/selectColumn"),
            Map.entry("indexColumns", "/api/physical/index"),
            Map.entry("viewColumns", "/api/physical/view"),
            Map.entry("noticeColumns", "/api/selectNotice"),
            Map.entry("userColumns", "/api/selectUser"),
            Map.entry("projectColumns", "/api/getProject/" + PROJECT_ID),
            Map.entry("systemLogColumns", "/api/getSystemLog"));

    @Test
    @Order(20)
    @DisplayName("M-7 논리·물리 매핑 API : 예제는 전부 매핑(V4) → 계정 매핑 해제 → 후보 → 엔터티 매핑 → 속성 매핑 → 상세, 오류 코드, 삭제 순서 → 예제 매핑 복원")
    void modelMapping() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        // V1 : 예제의 논리·물리 모델은 전부 매핑되어 있다
        var status = mapper.readTree(get("/api/modelMapping/status", true).body()).get(0);
        assertThat(status.path("modelName").asString()).isEqualTo("LEXORMA");
        long entities = status.path("totalEntities").asLong();
        long attributes = status.path("totalAttributes").asLong();
        assertThat(status.path("mappedEntities").asLong()).isEqualTo(entities).isPositive();
        assertThat(status.path("mappedAttributes").asLong()).isEqualTo(attributes).isPositive();

        // 검색은 부분 일치라 여러 건이 나온다 (이름순은 DB 정렬 규칙에 따라 다르다) : 이름이 같은 행을 고른다
        String accountRows = "/api/modelMapping/entity/data?page=0&size=50&searchColumn=entityName&searchQuery=" + java.net.URLEncoder.encode("계정", java.nio.charset.StandardCharsets.UTF_8);
        var seeded = java.util.stream.StreamSupport.stream(mapper.readTree(get(accountRows, true).body()).path("data").spliterator(), false)
                .filter(row -> row.path("entityName").asString().equals("계정")).findFirst().orElseThrow();
        assertThat(seeded.path("tableName").asString()).isEqualTo("TB_ACNT");
        assertThat(seeded.path("mappedAttributeCount").asLong()).isEqualTo(seeded.path("attributeCount").asLong()).isPositive();

        // 예제의 계정 ↔ TB_ACNT 매핑을 해제한다 (속성 매핑 → 엔터티 매핑). 끝에서 같은 짝으로 되돌린다
        var seededDetail = mapper.readTree(get("/api/modelMapping/entity/" + seeded.path("mappingId").asLong(), true).body());
        assertThat(seededDetail.path("unmappedColumns").size()).isZero();
        List<long[]> seededPairs = new ArrayList<>();
        for (var attribute : seededDetail.path("attributes")) {
            assertThat(attribute.path("attributeTermId").asLong()).as(attribute.path("attributeName").asString()).isEqualTo(attribute.path("columnTermId").asLong());
            seededPairs.add(new long[]{attribute.path("attributeId").asLong(), attribute.path("columnId").asLong()});
            assertThat(delete("/api/modelMapping/attribute/" + attribute.path("mappingId").asLong()).statusCode()).isEqualTo(200);
        }
        assertThat(delete("/api/modelMapping/entity/" + seeded.path("mappingId").asLong()).statusCode()).isEqualTo(200);
        assertThat(get("/api/modelMapping/status", true).body()).contains("\"mappedEntities\":" + (entities - 1), "\"mappedAttributes\":" + (attributes - seededPairs.size()));

        var entity = java.util.stream.StreamSupport.stream(mapper.readTree(get(accountRows + "&mapped=false", true).body()).path("data").spliterator(), false)
                .filter(row -> row.path("entityName").asString().equals("계정")).findFirst().orElseThrow();
        assertThat(entity.path("mappingId").isNull()).isTrue();
        var table = java.util.stream.StreamSupport.stream(mapper.readTree(get("/api/modelMapping/table/data?page=0&size=50&searchQuery=TB_ACNT", true).body()).path("data").spliterator(), false)
                .filter(row -> row.path("tableName").asString().equals("TB_ACNT")).findFirst().orElseThrow();
        String entityMapping = "{\"entityId\":" + entity.path("entityId").asLong() + ",\"tableId\":" + table.path("tableId").asLong() + "}";

        HttpResponse<String> created = postJson("/api/modelMapping/entity", entityMapping);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        long mappingId = mapper.readTree(created.body()).path("id").asLong();
        assertThat(postJson("/api/modelMapping/entity", entityMapping).body()).contains("SAVED_DATA_EXISTS");

        var detail = mapper.readTree(get("/api/modelMapping/entity/" + mappingId, true).body());
        assertThat(detail.path("mapping").path("tableName").asString()).isEqualTo("TB_ACNT");
        assertThat(detail.path("attributes").size()).isEqualTo(detail.path("unmappedColumns").size()).isPositive();
        long attributeId = seededPairs.get(0)[0];
        long columnId = seededPairs.get(0)[1];
        HttpResponse<String> attributeMapped = postJson("/api/modelMapping/attribute", "{\"mappingId\":" + mappingId + ",\"attributeId\":" + attributeId + ",\"columnId\":" + columnId + "}");
        assertThat(attributeMapped.statusCode()).as(attributeMapped.body()).isEqualTo(200);
        assertThat(get("/api/modelMapping/entity/" + mappingId, true).body()).contains("\"mappedAttributeCount\":1", "\"columnTermName\":");
        assertThat(get("/api/modelMapping/status", true).body()).contains("\"mappedEntities\":" + entities, "\"mappedAttributes\":" + (attributes - seededPairs.size() + 1));

        // 오류 : 400 + 코드
        assertThat(get("/api/modelMapping/entity/999999999", true).body()).contains("MODEL_MAPPING_IS_NOT_DEFINED");
        assertThat(postJson("/api/modelMapping/attribute", "{\"mappingId\":" + mappingId + ",\"attributeId\":999999999,\"columnId\":" + columnId + "}").body()).contains("INVALID_MODEL_MAPPING");
        HttpResponse<String> blocked = delete("/api/modelMapping/entity/" + mappingId);
        assertThat(blocked.statusCode()).isEqualTo(400);
        assertThat(blocked.body()).contains("RELATION_EXISTS");

        // 예제 매핑 복원 : 나머지 속성을 처음과 같은 컬럼에 다시 잇는다 (다른 테스트는 전부 매핑된 예제를 전제로 한다)
        for (long[] pair : seededPairs.subList(1, seededPairs.size())) {
            assertThat(postJson("/api/modelMapping/attribute", "{\"mappingId\":" + mappingId + ",\"attributeId\":" + pair[0] + ",\"columnId\":" + pair[1] + "}").statusCode()).isEqualTo(200);
        }
        assertThat(get("/api/modelMapping/status", true).body()).contains("\"mappedEntities\":" + entities, "\"mappedAttributes\":" + attributes);
    }

    @Test
    @Order(20)
    @DisplayName("M-7 2차 정합성 비교 API : 요약 = 상태별 목록 건수, 매핑 1건 조회, 필수 조건·잘못된 값 400, 메뉴 권한 (예제는 V1 로 전부 매핑)")
    void modelMappingQuality() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        long modelId = exampleModelId();
        var summary = mapper.readTree(get("/api/modelMapping/quality/summary?modelId=" + modelId, true).body());
        assertThat(summary.path("modelName").asString()).isEqualTo("LEXORMA");
        assertThat(summary.path("totalEntities").asLong()).isGreaterThan(0);
        assertThat(summary.path("unmapped").asLong()).isEqualTo(summary.path("unmappedEntities").asLong() + summary.path("unmappedAttributes").asLong());
        for (var entry : Map.of("MATCH", "match", "MISMATCH", "mismatch", "NOT_COMPARABLE", "notComparable", "UNMAPPED", "unmapped").entrySet()) {
            assertThat(mapper.readTree(get("/api/modelMapping/quality/data?page=0&size=1&modelId=" + modelId + "&status=" + entry.getKey(), true).body()).path("recordsTotal").asLong())
                    .as(entry.getKey()).isEqualTo(summary.path(entry.getValue()).asLong());
        }
        // V1 : 예제는 전부 매핑되어 있어 미매핑이 없고, 매핑된 속성마다 진단 유형 6건이 나온다
        assertThat(summary.path("unmapped").asLong()).isZero();
        assertThat(summary.path("match").asLong() + summary.path("mismatch").asLong() + summary.path("notComparable").asLong()).isEqualTo(summary.path("mappedAttributes").asLong() * 6);
        assertThat(get("/api/modelMapping/quality/data?page=0&size=1&modelId=" + modelId + "&type=MAPPING", true).body()).contains("\"recordsTotal\":0");
        var row = mapper.readTree(get("/api/modelMapping/quality/data?page=0&size=1&modelId=" + modelId + "&type=TERM", true).body()).path("data").get(0);
        assertThat(row.path("status").asString()).isEqualTo("MATCH");
        assertThat(row.path("columnName").asString()).isNotBlank();
        long entityId = row.path("entityId").asLong();
        assertThat(get("/api/modelMapping/entity/lookup?entityId=" + entityId, true).body()).contains("\"entityId\":" + entityId, "\"modelId\":" + modelId);
        assertThat(get("/api/modelMapping/entity/lookup?tableId=999999999", true)).extracting(HttpResponse::statusCode, HttpResponse::body).containsExactly(200, "");
        assertThat(get("/api/modelMapping/entity/lookup", true).body()).contains("INVALID_MODEL_MAPPING");

        assertThat(get("/api/modelMapping/quality/summary", true).statusCode()).as("modelId 없음").isEqualTo(400);
        assertThat(get("/api/modelMapping/quality/summary?modelId=999999999", true).body()).contains("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(get("/api/modelMapping/quality/data?page=0&size=5&modelId=999999999", true).body()).contains("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(get("/api/modelMapping/quality/data?page=0&size=5&modelId=" + modelId + "&status=UNKNOWN", true).statusCode()).isEqualTo(400);
        assertThat(get("/api/modelMapping/quality/data?page=0&size=5&modelId=" + modelId + "&type=UNKNOWN", true).statusCode()).isEqualTo(400);
        assertThat(get("/api/auth/me", true).body()).contains("\"model-mapping\":true", "\"model-mapping-edit\":true");
    }

    @Test
    @Order(20)
    @DisplayName("M-7 3차 물리명 추천 API : 예제는 컬럼명·테이블명이 모두 추천명과 같다, 요약 = 상태별 목록, 속성·컬럼·엔터티 조건, 필수 조건·잘못된 값 400")
    void physicalNameRecommendation() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        long modelId = exampleModelId();
        String columns = "/api/modelMapping/recommendation/column";
        var summary = mapper.readTree(get(columns + "/summary?modelId=" + modelId, true).body());
        // 예제의 컬럼명은 표준 용어의 영문약어이고(M-5 진단 0건) 속성은 같은 용어의 컬럼에 매핑되어 있다(V4)
        assertThat(summary.path("total").asLong()).isEqualTo(summary.path("alreadyMatched").asLong()).isPositive();
        for (var entry : Map.of("AVAILABLE", "available", "ALREADY_MATCHED", "alreadyMatched", "UNAVAILABLE", "unavailable", "CONFLICT", "conflict").entrySet()) {
            assertThat(mapper.readTree(get(columns + "/data?page=0&size=1&modelId=" + modelId + "&status=" + entry.getKey(), true).body()).path("recordsTotal").asLong())
                    .as(entry.getKey()).isEqualTo(summary.path(entry.getValue()).asLong());
        }
        var row = mapper.readTree(get(columns + "/data?page=0&size=1&modelId=" + modelId, true).body()).path("data").get(0);
        assertThat(row.path("recommendedPhysicalName").asString()).isEqualTo(row.path("currentPhysicalName").asString()).isEqualTo(row.path("termAbbreviation").asString());
        assertThat(row.path("source").asString()).isEqualTo("STANDARD_TERM");
        assertThat(row.path("reason").asString()).isNotBlank();
        // 속성·컬럼으로 1건 조회 (modelId 없이)
        assertThat(get(columns + "/data?page=0&size=5&attributeId=" + row.path("logicalAttributeId").asLong(), true).body())
                .contains("\"recordsTotal\":1", "\"physicalColumnId\":" + row.path("physicalColumnId").asLong());
        assertThat(get(columns + "/data?page=0&size=5&columnId=" + row.path("physicalColumnId").asLong(), true).body())
                .contains("\"recordsTotal\":1", "\"logicalAttributeId\":" + row.path("logicalAttributeId").asLong());

        // 테이블명 : 예제의 엔터티명은 테이블 한글명이라 추천 테이블명 = 현재 테이블명
        String tables = "/api/modelMapping/recommendation/table";
        var tableRows = mapper.readTree(get(tables + "/data?page=0&size=100&modelId=" + modelId, true).body());
        assertThat(tableRows.path("recordsTotal").asLong()).isEqualTo(tableRows.path("data").size()).isPositive();
        for (var table : tableRows.path("data")) {
            assertThat(table.path("status").asString()).as(table.path("logicalName").asString()).isEqualTo("ALREADY_MATCHED");
            assertThat(table.path("recommendedPhysicalName").asString()).isEqualTo(table.path("currentPhysicalName").asString());
        }
        long entityId = tableRows.path("data").get(0).path("logicalEntityId").asLong();
        assertThat(get(tables + "/data?page=0&size=5&entityId=" + entityId, true).body()).contains("\"recordsTotal\":1", "\"source\":\"STANDARD_WORD\"", "\"words\":[");
        // 매핑 목록의 엔터티 ID 조건
        assertThat(get("/api/modelMapping/entity/data?page=0&size=5&modelId=" + modelId + "&entityId=" + entityId, true).body()).contains("\"recordsTotal\":1", "\"entityId\":" + entityId);
        assertThat(get("/api/modelMapping/entity/data?page=0&size=5&modelId=" + modelId + "&entityId=999999999", true).body()).contains("\"recordsTotal\":0");

        assertThat(get(columns + "/summary", true).statusCode()).as("modelId 없음").isEqualTo(400);
        assertThat(get(columns + "/data?page=0&size=5", true).body()).contains("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(get(tables + "/data?page=0&size=5", true).body()).contains("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(get(columns + "/data?page=0&size=5&modelId=999999999", true).body()).contains("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(get(columns + "/data?page=0&size=5&modelId=" + modelId + "&status=UNKNOWN", true).statusCode()).isEqualTo(400);
    }

    /** V1 예제 논리 모델(LEXORMA)의 ID */
    private long exampleModelId() throws IOException, InterruptedException {
        var models = tools.jackson.databind.json.JsonMapper.builder().build().readTree(get("/api/logical/model", true).body());
        return java.util.stream.StreamSupport.stream(models.spliterator(), false)
                .filter(model -> "LEXORMA".equals(model.path("modelName").asString())).findFirst().orElseThrow().path("id").asLong();
    }

    @Test
    @Order(20)
    @DisplayName("그리드 정렬·검색 : 화면에 정의한 정렬 컬럼(처음 정렬되는 첫 컬럼 포함) × 검색 컬럼이 모두 오류 없이 조회된다")
    void gridSortAndSearch() throws Exception {
        Pattern grid = Pattern.compile("export const (\\w+): GridColumn\\[] = \\[(.*?)\\n];", Pattern.DOTALL);
        Pattern column = Pattern.compile("\\{[^}]*\\bcolumn: '(\\w+)'[^}]*}");
        List<String> sources;
        try (Stream<Path> files = Files.walk(Path.of("").toAbsolutePath().getParent().resolve("lexorma-web/src/features"))) {
            sources = files.filter(path -> path.getFileName().toString().equals("columns.ts")).map(path -> {
                try {
                    return Files.readString(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }

        List<String> failed = new ArrayList<>();
        int grids = 0;
        for (String source : sources) {
            Matcher gridMatcher = grid.matcher(source);
            while (gridMatcher.find()) {
                grids++;
                String dataUrl = GRID_DATA_URLS.get(gridMatcher.group(1));
                assertThat(dataUrl).as("GRID_DATA_URLS 에 없는 그리드 : " + gridMatcher.group(1)).isNotNull();
                String required = Set.of("mappingQualityColumns", "recommendationColumnColumns", "recommendationTableColumns").contains(gridMatcher.group(1)) ? "&modelId=" + exampleModelId() : "";

                Set<String> sorts = new LinkedHashSet<>();
                List<String> searches = new ArrayList<>(List.of(""));
                Matcher columnMatcher = column.matcher(gridMatcher.group(2));
                while (columnMatcher.find()) {
                    String definition = columnMatcher.group();
                    // Grid.tsx : 첫 컬럼은 처음 정렬 기준, 그 밖에는 isFilter 가 false 가 아니고 체크박스가 아닌 컬럼만 정렬할 수 있다.
                    if (sorts.isEmpty() || !(definition.contains("isFilter: false") || definition.contains("rowType: 'CHECKBOX'"))) {
                        sorts.add(columnMatcher.group(1));
                    }
                    if (definition.contains("isSearch: true")) {
                        searches.add("&searchColumn=" + columnMatcher.group(1) + "&searchQuery=a");
                    }
                }
                assertThat(sorts).as(dataUrl).isNotEmpty();
                for (String sort : sorts) {
                    for (String search : searches) {
                        for (String direction : List.of("asc", "desc")) {
                            String url = dataUrl + "/data?page=0&size=10&sort=" + sort + "," + direction + search + required;
                            HttpResponse<String> response = get(url, true);
                            if (response.statusCode() != 200 || !response.body().contains("\"recordsTotal\"")) {
                                failed.add(url + " → " + response.statusCode());
                            }
                        }
                    }
                }
            }
        }
        assertThat(grids).isEqualTo(GRID_DATA_URLS.size());
        assertThat(failed).isEmpty();

        // 숫자 컬럼(응답 상태코드) 검색은 부분 일치로 찾는다.
        assertThat(get("/api/getSystemLog/data?page=0&size=10&sort=id,desc&searchColumn=statusCode&searchQuery=20", true).body())
                .contains("\"statusCode\":200");
    }

    @ParameterizedTest(name = "API {0}")
    @ValueSource(strings = {"/api/dashboard", "/api/commonColumn", "/api/getProject/managed", "/api/selectUser/roles", "/api/auth/signup/projects", "/v3/api-docs"})
    @Order(21)
    @DisplayName("기타 주요 API")
    void apis(String path) throws Exception {
        assertThat(get(path, true).statusCode()).as(path).isEqualTo(200);
    }

    @Test
    @Order(22)
    @DisplayName("표준 단어 기준 테이블명 생성 API : 단어별 상태와 valid 를 JSON 으로 반환")
    void generateTableName() throws Exception {
        String korName = java.net.URLEncoder.encode("없는단어_테스트", java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/api/selectTable/tableName?tableKorName=" + korName, true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"valid\":false")
                .contains("\"tableKorName\":\"없는단어 테스트\"")
                .contains("\"status\":\"NOT_FOUND\"");
    }

    @Test
    @Order(1)
    @DisplayName("JPA : 지연 로딩 연관을 묶어 조회하도록 default_batch_fetch_size(100) 가 적용된다")
    void batchFetchSize() {
        assertThat(entityManagerFactory.getProperties().get("hibernate.default_batch_fetch_size"))
                .hasToString("100");
    }

    @Test
    @Order(24)
    @DisplayName("데이터사전 엑셀 출력 : 페이지 단위 조회 + 스트리밍 쓰기로 등록한 용어(도메인/약어 지연 로딩 포함)가 들어간다")
    void exportDictionary() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase().replaceAll("[^A-Z]", "X");
        String category = "스모크분류" + suffix;
        String domainName = "스모크도메인" + suffix;

        assertThat(postJson("/api/insertDataDictionary/standardDomains", """
                {"revisionNumber":1,"commonStandardDomainGroupName":"문자","commonStandardDomainCategory":"%s",
                 "commonStandardDomainName":"%s","commonStandardDomainDescription":"스모크","dataType":"VARCHAR",
                 "dataLength":100,"dataDecimalLength":0,"storageFormat":"","displayFormat":"","unit":"","allowedValues":""}
                """.formatted(category, domainName)).statusCode()).isEqualTo(200);
        assertThat(postJson("/api/insertDataDictionary/standardWords", """
                {"revisionNumber":1,"commonStandardWordName":"스모크%s","commonStandardWordAbbreviation":"SMK%s",
                 "commonStandardWordEnglishName":"Smoke","commonStandardWordDescription":"스모크","isFormatWord":"N",
                 "commonStandardDomainCategory":"-","synonyms":"","restrictedWords":""}
                """.formatted(suffix, suffix)).statusCode()).isEqualTo(200);
        assertThat(postJson("/api/insertDataDictionary/standardWords", """
                {"revisionNumber":1,"commonStandardWordName":"명칭%s","commonStandardWordAbbreviation":"NM%s",
                 "commonStandardWordEnglishName":"Name","commonStandardWordDescription":"스모크","isFormatWord":"N",
                 "commonStandardDomainCategory":"%s","synonyms":"","restrictedWords":""}
                """.formatted(suffix, suffix, category)).statusCode()).isEqualTo(200);
        assertThat(postJson("/api/insertDataDictionary/standardTerms", """
                {"revisionNumber":1,"commonStandardTermName":"스모크%s_명칭%s","commonStandardTermDescription":"스모크",
                 "commonStandardTermAbbreviation":"SMK%s_NM%s","commonStandardDomainName":"%s","synonyms":""}
                """.formatted(suffix, suffix, suffix, suffix, domainName)).statusCode()).isEqualTo(200);

        HttpResponse<byte[]> response = getBytes("/api/downloadTemplate/dictionary");
        assertThat(response.statusCode()).isEqualTo(200);

        List<Map<String, String>> terms = new java.util.ArrayList<>();
        com.lexorma.common.utils.ExcelUtils.readExcelRows(
                new org.springframework.mock.web.MockMultipartFile("file", "dictionary.xlsx", null, response.body()),
                0, List.of("id", "revision", "termName", "description", "abbreviation", "domainName"), 1, terms::add);

        assertThat(terms).anySatisfy(row -> {
            assertThat(row.get("termName")).isEqualTo("스모크" + suffix + "_명칭" + suffix);
            assertThat(row.get("abbreviation")).isEqualTo("SMK" + suffix + "_NM" + suffix);
            assertThat(row.get("domainName")).isEqualTo(domainName);
        });
    }

    @Test
    @Order(23)
    @DisplayName("컬럼명 중복 확인 API : 없는 컬럼명이면 duplicated=false")
    void checkColumnDuplicate() throws Exception {
        HttpResponse<String> response = get("/api/selectColumn/duplicate?tableInfoId=0&columnName=NOT_EXISTS_COLUMN", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"duplicated\":false");
    }

    @Test
    @Order(23)
    @DisplayName("컬럼 일괄 저장 API : JSON 요청을 받고, 없는 테이블이면 TABLE_IS_NOT_DEFINED(400)")
    void saveColumnsBulkUnknownTable() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/saveColumn/bulk"))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", csrfToken())
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"tableInfoId\":-1,\"rows\":[{\"key\":\"new-1\",\"termId\":-1,\"columnDesc\":\"설명\",\"dataType\":\"VARCHAR\",\"isPk\":\"N\"}],\"deletedIds\":[]}",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("TABLE_IS_NOT_DEFINED");
    }

    @Test
    @Order(23)
    @DisplayName("표준용어 일괄 찾기 API : 붙여넣은 줄마다 상태를 돌려준다 (없는 용어는 NOT_FOUND)")
    void resolveTerms() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/selectColumn/term/resolve"))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString("[\"없는 용어 테스트\",\"NOT_EXISTS_ABBR\",\" \"]", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"input\":\"없는 용어 테스트\"")
                .contains("\"input\":\"NOT_EXISTS_ABBR\"")
                .contains("\"status\":\"NOT_FOUND\"")
                .doesNotContain("\"input\":\" \"");
    }

    @Test
    @Order(23)
    @DisplayName("표준용어 중복 확인 API : 없는 약어/도메인이면 duplicated=false")
    void checkStandardTermDuplicate() throws Exception {
        String domain = java.net.URLEncoder.encode("없는도메인", java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/api/getStandardTerms/duplicate?abbreviation=NOT_EXISTS_ABBR&domainName=" + domain, true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"duplicated\":false");
    }

    @Test
    @Order(30)
    @DisplayName("API 호출 시 시스템 로그 저장 (민감정보 마스킹 포함)")
    void systemLogSaved() throws Exception {
        long before = countLogs("/api/updateName");

        HttpResponse<String> response = postJson("/api/updateName", "{\"name\":\"관리자\"}");
        assertThat(response.statusCode()).isEqualTo(200);

        // afterCompletion에서 저장되므로 응답 직후 약간의 지연이 있을 수 있다
        long deadline = System.currentTimeMillis() + 5_000;
        while (countLogs("/api/updateName") <= before && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(countLogs("/api/updateName")).isGreaterThan(before);

        // LogsConfig 제외 패턴(/api/**/data) : 앞 단계에서 호출한 그리드 API는 저장되지 않아야 한다
        assertThat(logsRepository.findAll())
                .extracting(Logs::getLogUrl)
                .isNotEmpty()
                .noneMatch(url -> url.endsWith("/data"));

        // 일반 API 는 지금까지처럼 본문이 남는다
        assertThat(logsRepository.findAll()).filteredOn(log -> "/api/updateName".equals(log.getLogUrl()))
                .allSatisfy(log -> {
                    assertThat(log.getRequestBody()).contains("관리자");
                    assertThat(log.getResponseBody()).isNotEqualTo("[PROTECTED]");
                    assertThat(log.getUsername()).isEqualTo(USERNAME);
                });
        // 인증 API (로그인 정보, CSRF 토큰) 는 호출 기록만 남고 요청·응답 본문은 남지 않는다 (AI-1a 본문 저장 정책)
        assertThat(logsRepository.findAll()).filteredOn(log -> log.getLogUrl().startsWith("/api/auth/"))
                .isNotEmpty()
                .allSatisfy(log -> {
                    assertThat(log.getRequestBody()).isEqualTo("[PROTECTED]");
                    assertThat(log.getResponseBody()).isEqualTo("[PROTECTED]");
                    assertThat(log.getUsername()).isEqualTo(USERNAME);
                    assertThat(log.getStatusCode()).isNotNull();
                });
        // CSRF 토큰 값이 어느 로그에도 없다
        assertThat(logsRepository.findAll()).noneMatch(log -> csrfToken != null && ((log.getResponseBody() != null && log.getResponseBody().contains(csrfToken))
                || (log.getRequestBody() != null && log.getRequestBody().contains(csrfToken))));

        // 로그인 프로젝트 조회 요청은 비밀번호가 포함되므로 저장되더라도 원문이 남지 않아야 한다
        assertThat(logsRepository.findAll())
                .extracting(Logs::getRequestBody)
                .noneMatch(body -> body != null && (body.contains("\"password\":\"" + PASSWORD + "\"")
                        || body.contains(PASSWORD))); // 로그인 전 비밀번호 변경의 newPassword 포함
    }

    @Test
    @Order(99)
    @DisplayName("로그아웃 : 세션 무효화(DB 행 삭제) 후 기존 세션으로는 API 를 쓸 수 없다")
    void logoutInvalidatesSession() throws Exception {
        HttpResponse<String> logout = postJson("/api/auth/logout", "");
        assertThat(logout.statusCode()).isEqualTo(204);

        assertThat(get("/api/auth/me", true).body()).contains("\"authenticated\":false");
        assertThat(get("/api/selectTable/data?page=0&size=10", true).statusCode()).isEqualTo(401);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", Integer.class, USERNAME)).isZero();

        // 로그아웃 후에도 새 CSRF 토큰을 받을 수 있다
        assertThat(refreshCsrf()).isNotBlank();
    }

    // ===== helpers =====

    private long countLogs(String url) {
        return logsRepository.findAll().stream().filter(log -> url.equals(log.getLogUrl())).count();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, boolean ajax) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (ajax) {
            builder.header("X-Requested-With", "XMLHttpRequest");
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path)).header("X-Requested-With", "XMLHttpRequest").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, String json) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", csrfToken())
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", csrfToken())
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", csrfToken())
                .DELETE()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrfToken() {
        return csrfToken;
    }

    /** React 와 같은 방식 : GET /api/auth/csrf 응답의 토큰을 메모리에 들고 X-XSRF-TOKEN 헤더로 보낸다 */
    private String refreshCsrf() throws IOException, InterruptedException {
        csrfToken = readCsrf(client);
        return csrfToken;
    }

    private String readCsrf(HttpClient httpClient) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
        assertThat(json.path("headerName").asString()).isEqualTo("X-XSRF-TOKEN");
        return json.path("token").asString();
    }
}
