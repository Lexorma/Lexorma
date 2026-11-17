package com.lexorma.core.logs.interceptor;

import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.policy.LogBodyPolicy;
import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.logs.service.LogsService;
import com.lexorma.core.logs.service.RequestLogRecorder;
import com.lexorma.core.security.conf.CustomOncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 요청 로그 : 본문 저장 정책(보호 대상 API 는 본문 없이 호출 기록만, 일반 API 는 마스킹한 본문)과 상태 코드.
 * 요청은 실제처럼 필터(CustomOncePerRequestFilter) → 체인 안에서 인터셉터 → 체인 뒤 Security 의 상태 변경 순서로 흘린다.
 */
class LogsInterceptorTest {

    private static final String SECRET = "Tiger!Secret9";

    static class Controller {
        public void plain() {
        }

        @ProtectedLogBody
        public void connection() {
        }
    }

    private final LogsService logsService = mock(LogsService.class);
    private final LogsInterceptor interceptor = new LogsInterceptor();
    private final CustomOncePerRequestFilter filter = new CustomOncePerRequestFilter(new RequestLogRecorder(logsService));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("일반 API : 본문을 남기되 비밀 필드와 값 안의 비밀은 가린다. 오류 메시지 안의 접속 정보도 가린다")
    void generalApi() throws Exception {
        Logs log = call("/api/saveTable", "plain", "{\"tableName\":\"TB_ORD\",\"tokenCount\":3,\"apiKey\":\"" + SECRET + "\"}",
                "{\"result\":true,\"url\":\"jdbc:postgresql://scott:" + SECRET + "@db/app\"}",
                new IllegalStateException("Connection to jdbc:postgresql://db/app?user=scott&password=" + SECRET + " refused"));

        assertThat(log.getRequestBody()).contains("\"tableName\":\"TB_ORD\"").contains("\"tokenCount\":3").contains("\"apiKey\":\"****\"").doesNotContain(SECRET);
        assertThat(log.getResponseBody()).contains("\"result\":true").contains("scott:****@db").doesNotContain(SECRET);
        assertThat(log.getErrorMessage()).contains("refused").contains("password=****").doesNotContain(SECRET);
        assertAudit(log, "/api/saveTable");
    }

    @Test
    @DisplayName("보호 대상 경로(원본 DB 연결·AI·인증·비밀번호) : 요청·응답 본문과 오류 메시지를 남기지 않고 호출 기록(누가·어느 프로젝트·무엇을·결과)은 남긴다")
    void protectedPath() throws Exception {
        for (String path : List.of("/api/ai/settings", "/api/source/connections", "/api/updatePwd", "/api/saveUser/password", "/api/auth/csrf")) {
            Logs log = call(path, "plain", "{\"endpoint\":\"http://10.0.0.5:11434\",\"memo\":\"" + SECRET + "\"}", "{\"prompt\":\"" + SECRET + "\"}",
                    new IllegalArgumentException("bad value " + SECRET));

            assertThat(log.getRequestBody()).as(path).isEqualTo(LogBodyPolicy.PROTECTED);
            assertThat(log.getResponseBody()).as(path).isEqualTo(LogBodyPolicy.PROTECTED);
            assertThat(log.getErrorMessage()).as(path).isEqualTo("IllegalArgumentException");
            assertThat(log.toString()).as(path).doesNotContain(SECRET);
            assertAudit(log, path);
        }
    }

    @Test
    @DisplayName("@ProtectedLogBody : 경로 목록에 없는 API 도 본문을 남기지 않는다 (필드 이름을 몰라도 새지 않는다)")
    void protectedByAnnotation() throws Exception {
        Logs log = call("/api/externalSecret", "connection", "{\"host\":\"db\",\"memo\":\"" + SECRET + "\"}", "{\"saved\":\"" + SECRET + "\"}", null);

        assertThat(log.getRequestBody()).isEqualTo(LogBodyPolicy.PROTECTED);
        assertThat(log.getResponseBody()).isEqualTo(LogBodyPolicy.PROTECTED);
        assertThat(log.getErrorMessage()).isNull();
        assertAudit(log, "/api/externalSecret");
    }

    @Test
    @DisplayName("로그인하지 않은 요청은 지금까지처럼 로그를 남기지 않는다")
    void anonymous() throws Exception {
        MockHttpServletRequest request = request("/api/login/projects", "{\"username\":\"admin\",\"password\":\"" + SECRET + "\"}");
        filter.doFilter(request, new MockHttpServletResponse(), dispatch(new HandlerMethod(new Controller(), "plain"), "{}", null, null));
        // 로그인하지 않은 요청이 Security 에서 401 로 거절된 경우도 남기지 않는다
        filter.doFilter(request("/api/schema", "{}"), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(401));

        verify(logsService, never()).saveLogs(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("@PreAuthorize 거부 : 핸들러 밖으로 나간 거부가 Security 에서 403 이 된 뒤의 상태를 남긴다 (예전에는 200)")
    void deniedByPreAuthorize() throws Exception {
        Logs log = filtered("/api/modelExchange/apply", new HandlerMethod(new Controller(), "plain"), new AuthorizationDeniedException("Access Denied"), 403);
        assertThat(log.getStatusCode()).isEqualTo(403);
        assertThat(log.getErrorMessage()).isEqualTo("Access Denied");
        assertThat(log.getUsername()).isEqualTo("tester");
    }

    @Test
    @DisplayName("컨트롤러 전에 거절된 로그인 사용자 요청(URL 권한 403) · 인터셉터가 세션을 끊은 401 : 한 건, 컨트롤러 앞의 사용자로")
    void rejectedBeforeDispatcher() throws Exception {
        Logs log = filtered("/api/schema", null, null, 403);
        assertThat(log.getStatusCode()).isEqualTo(403);
        assertThat(log.getRequestBody()).isEqualTo("N/A"); // 본문을 읽기 전에 거절됐다

        login();
        org.mockito.Mockito.clearInvocations(logsService);
        HandlerMethod handler = new HandlerMethod(new Controller(), "plain");
        filter.doFilter(request("/api/schema", "{}"), new MockHttpServletResponse(), (req, res) -> {
            interceptor.preHandle((jakarta.servlet.http.HttpServletRequest) req, (HttpServletResponse) res, handler); // 가장 먼저 도는 인터셉터
            SecurityContextHolder.clearContext(); // 그 뒤 ProjectAccessInterceptor : 세션을 끊는다
            ((HttpServletResponse) res).setStatus(401);
        });
        ArgumentCaptor<Logs> captor = ArgumentCaptor.forClass(Logs.class);
        verify(logsService).saveLogs(captor.capture());
        assertThat(captor.getValue().getStatusCode()).isEqualTo(401);
        assertThat(captor.getValue().getUsername()).isEqualTo("tester");
    }

    @Test
    @DisplayName("한 요청은 한 건 : 필터가 두 번 걸려도(서블릿 필터 + Security 체인) 한 번만 남긴다. 컨트롤러 전 성공 응답(로그인 등)은 남기지 않는다")
    void onePerRequest() throws Exception {
        login();
        MockHttpServletRequest request = request("/api/saveTable", "{}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new Controller(), "plain");
        filter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, dispatch(handler, "{}", null, null)));
        verify(logsService, org.mockito.Mockito.times(1)).saveLogs(org.mockito.ArgumentMatchers.any());

        org.mockito.Mockito.clearInvocations(logsService);
        filter.doFilter(request("/api/auth/login", "{}"), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(200));
        verify(logsService, never()).saveLogs(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("로그에서 뺀 경로(ERD·/data)는 오류여도, 컨트롤러 전에 거절돼도 지금처럼 남기지 않는다")
    void excludedPath() throws Exception {
        login();
        for (String path : List.of("/api/erd", "/api/erd/layout", "/api/selectTable/data")) {
            filter.doFilter(request(path, "{}"), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(401));
        }
        verify(logsService, never()).saveLogs(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("처리되지 않은 예외 : 커밋 전 응답은 컨테이너가 500 으로 응답한다 — 500 으로 한 건 남기고 예외는 그대로 나간다")
    void unhandledException() throws Exception {
        login();
        HandlerMethod handler = new HandlerMethod(new Controller(), "plain");
        IllegalStateException boom = new IllegalStateException("boom");
        assertThatThrownBy(() -> filter.doFilter(request("/api/saveTable", "{}"), new MockHttpServletResponse(), (req, res) -> {
            interceptor.preHandle((jakarta.servlet.http.HttpServletRequest) req, (HttpServletResponse) res, handler);
            interceptor.afterCompletion((jakarta.servlet.http.HttpServletRequest) req, (HttpServletResponse) res, handler, boom);
            throw boom;
        })).isSameAs(boom);
        ArgumentCaptor<Logs> captor = ArgumentCaptor.forClass(Logs.class);
        verify(logsService).saveLogs(captor.capture());
        assertThat(captor.getValue().getStatusCode()).isEqualTo(500);
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("boom");
    }

    private static void assertAudit(Logs log, String path) {
        assertThat(log.getLogUrl()).isEqualTo(path);
        assertThat(log.getMethod()).isEqualTo("POST");
        assertThat(log.getUsername()).isEqualTo("tester");
        assertThat(log.getProjectId()).isEqualTo(7L);
        assertThat(log.getRoleName()).isEqualTo("User");
        assertThat(log.getStatusCode()).isEqualTo(200);
        assertThat(log.getTimestamp()).isNotNull();
    }

    private Logs call(String path, String handlerMethod, String requestBody, String responseBody, Exception handled) throws Exception {
        login();
        org.mockito.Mockito.clearInvocations(logsService);
        // @RestControllerAdvice 가 처리한 예외는 요청 속성으로 온다
        filter.doFilter(request(path, requestBody), new MockHttpServletResponse(), dispatch(new HandlerMethod(new Controller(), handlerMethod), responseBody, handled, null));
        ArgumentCaptor<Logs> captor = ArgumentCaptor.forClass(Logs.class);
        verify(logsService).saveLogs(captor.capture());
        return captor.getValue();
    }

    /** 핸들러 밖으로 예외가 나간 뒤 Security(ExceptionTranslationFilter)가 상태를 정하는 흐름. handler 가 null 이면 컨트롤러 전에 거절 */
    private Logs filtered(String path, HandlerMethod handler, Exception escaped, int securityStatus) throws Exception {
        login();
        org.mockito.Mockito.clearInvocations(logsService);
        FilterChain inner = handler == null ? (req, res) -> { } : dispatch(handler, "{}", null, escaped);
        filter.doFilter(request(path, "{}"), new MockHttpServletResponse(), (req, res) -> {
            try {
                inner.doFilter(req, res);
            } catch (jakarta.servlet.ServletException e) {
                // ExceptionTranslationFilter : 거부 예외를 받아 상태를 정한다
            }
            ((HttpServletResponse) res).setStatus(securityStatus);
        });
        ArgumentCaptor<Logs> captor = ArgumentCaptor.forClass(Logs.class);
        verify(logsService).saveLogs(captor.capture());
        return captor.getValue();
    }

    /** DispatcherServlet 이 하는 일 : 요청 본문을 읽고 인터셉터를 거쳐 응답을 쓴다. escaped 는 핸들러 밖으로 나가는 예외 */
    private FilterChain dispatch(HandlerMethod handler, String responseBody, Exception handled, Exception escaped) {
        return (req, res) -> {
            jakarta.servlet.http.HttpServletRequest raw = (jakarta.servlet.http.HttpServletRequest) req;
            if (handled != null) raw.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, handled);
            req.getInputStream().readAllBytes();
            interceptor.preHandle(raw, (HttpServletResponse) res, handler);
            res.getOutputStream().write(responseBody.getBytes(StandardCharsets.UTF_8));
            interceptor.afterCompletion(raw, (HttpServletResponse) res, handler, escaped);
            if (escaped != null) throw new jakarta.servlet.ServletException(escaped);
        };
    }

    private static void login() {
        AccountDto principal = AccountDto.builder().id("1").username("tester").name("테스터").roleName("User").projectId(7L).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

}
