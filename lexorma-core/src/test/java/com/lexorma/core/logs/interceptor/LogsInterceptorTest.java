package com.lexorma.core.logs.interceptor;

import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.policy.LogBodyPolicy;
import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.logs.service.LogsService;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 요청 로그의 본문 저장 정책 : 보호 대상 API 는 본문 없이 호출 기록만, 일반 API 는 마스킹한 본문 */
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
    private final LogsInterceptor interceptor = new LogsInterceptor(logsService);

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
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new Controller(), "plain");
        wrap(request, response, "{}");

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        verify(logsService, never()).saveLogs(org.mockito.ArgumentMatchers.any());
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
        org.mockito.Mockito.clearInvocations(logsService);
        AccountDto principal = AccountDto.builder().id("1").username("tester").name("테스터").roleName("User").projectId(7L).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        MockHttpServletRequest request = request(path, requestBody);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new Controller(), handlerMethod);
        wrap(request, response, responseBody);
        // @RestControllerAdvice 가 처리한 예외는 요청 속성으로 온다
        if (handled != null) request.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, handled);

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        ArgumentCaptor<Logs> captor = ArgumentCaptor.forClass(Logs.class);
        verify(logsService).saveLogs(captor.capture());
        return captor.getValue();
    }

    private static MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** CustomOncePerRequestFilter 가 하는 일 : 본문을 캐시하는 래퍼를 요청 속성에 둔다 (컨트롤러가 읽고 쓴 뒤의 상태로 만든다) */
    private static void wrap(MockHttpServletRequest request, MockHttpServletResponse response, String responseBody) throws Exception {
        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, Integer.MAX_VALUE);
        cachedRequest.getInputStream().readAllBytes();
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        cachedResponse.getOutputStream().write(responseBody.getBytes(StandardCharsets.UTF_8));
        request.setAttribute("CACHED_REQUEST", cachedRequest);
        request.setAttribute("CACHED_RESPONSE", cachedResponse);
    }
}
