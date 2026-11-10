package com.koboolean.metamora;

import com.koboolean.metamora.core.logs.domain.entity.Logs;
import com.koboolean.metamora.core.logs.repository.LogsRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스모크 테스트 : 실제 서버(랜덤 포트)를 띄워 로그인(JSON) → React 화면 경로 / 주요 API → 시스템 로그 저장 → 로그아웃까지 확인한다.
 * <p>
 * 프레임워크 업그레이드(Boot 3.5 / 4.x) 시 테스트 자동 설정 모듈 변경의 영향을 받지 않도록
 * MockMvc/TestRestTemplate 대신 JDK HttpClient를 사용한다.
 * 동시 세션이 1개로 제한(maximumSessions(1))되어 있으므로 클래스 단위로 한 번만 로그인하고 종료 시 로그아웃한다.
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
        assertThat(projects.body()).contains("\"projectName\":\"META-MORA\"");

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
                .contains("\"projectName\":\"META-MORA\"")
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
    @DisplayName("로그인된 계정은 다른 곳에서 동시에 로그인할 수 없다 (maximumSessions=1, 세션 DB 기준)")
    void concurrentLoginBlocked() throws Exception {
        CookieManager otherCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient other = HttpClient.newBuilder().cookieHandler(otherCookies).followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> login = other.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", readCsrf(other))
                .POST(HttpRequest.BodyPublishers.ofString("username=" + USERNAME + "&password=" + PASSWORD + "&project_id=" + PROJECT_ID))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.body()).contains("\"code\":\"SESSION_LIMIT_EXCEEDED\"");
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
            "/", "/login", "/signup", "/account", "/notice", "/dataDictionary", "/tableManage", "/columnManage",
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
        assertThat(first.path("title").asString()).isEqualTo("[Notice] Welcome to Metamora");
        assertThat(first.path("isHit").asString()).isEqualTo("확인");

        HttpResponse<String> view = get("/api/selectNotice/view/1", true);
        assertThat(view.statusCode()).isEqualTo(200);
        var notice = mapper.readTree(view.body());
        assertThat(notice.path("username").asString()).isEqualTo("admin");
        assertThat(notice.path("content").asString()).contains("Physical ERD", "Getting started", "Questions and bug reports",
                "https://github.com/meta-mora/metamora/issues");
        assertThat(notice.path("accounts").size()).isEqualTo(1);
        assertThat(get("/api/selectNotice/view/1", true).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(get("/api/selectNotice/view/1", true).body()).path("accounts").size()).isEqualTo(1);
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

        assertThat(get("/api/selectNotice/projects?type=add", true).body()).contains("\"projectName\":\"META-MORA\"");
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
        com.koboolean.metamora.common.utils.ExcelUtils.readExcelRows(
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
