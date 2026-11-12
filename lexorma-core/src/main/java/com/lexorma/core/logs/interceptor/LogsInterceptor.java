package com.lexorma.core.logs.interceptor;

import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.service.LogsService;
import com.lexorma.core.logs.policy.LogBodyPolicy;
import com.lexorma.core.logs.util.SensitiveDataMasker;
import com.lexorma.core.security.domain.dto.AccountDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;

/**
 * Restful API 형식으로 들어오는 HTTP 요청단위별 동작을 위해 Interceptor에 로깅 기능 추가
 * - HTTP 요청(Request) 및 응답(Response) 로깅에 Interceptor 적용
 * - 모든 메서드 기반 로깅이 필요할 경우 AOP를 사용하여 적용
 * - 본문 저장 정책 : 인증 정보·원본 DB 연결·AI API 는 본문을 남기지 않고(LogBodyPolicy), 그 밖에는 비밀값을 마스킹한다(SensitiveDataMasker)
 */
@RequiredArgsConstructor
public class LogsInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LogsInterceptor.class);
    private final LogsService logsService;

    // 요청 정보를 저장할 ThreadLocal 변수 (각 요청마다 독립적인 데이터 저장)
    private final ThreadLocal<RequestDetails> requestDetailsThreadLocal = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 필터에서 저장한 요청 및 응답 객체 가져오기
        ContentCachingRequestWrapper wrappedRequest = (ContentCachingRequestWrapper) request.getAttribute("CACHED_REQUEST");
        ContentCachingResponseWrapper wrappedResponse = (ContentCachingResponseWrapper) request.getAttribute("CACHED_RESPONSE");

        if (wrappedRequest == null || wrappedResponse == null) {
            logger.error("wrappedRequest or wrappedResponse is NULL. 필터가 적용되지 않음.");
            return true;
        }

        // 요청 정보를 저장할 객체 생성 및 저장
        RequestDetails details = new RequestDetails();
        details.logUrl = request.getRequestURI();
        details.method = request.getMethod();
        details.ip = request.getRemoteAddr();
        details.userAgent = request.getHeader("User-Agent");
        details.wrappedRequest = wrappedRequest;
        details.wrappedResponse = wrappedResponse;

        // ThreadLocal에 저장하여 이후 후처리에서 사용
        requestDetailsThreadLocal.set(details);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        // 필요 시 추가 처리 가능
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Logs log = null;

        try {
            // preHandle에서 저장된 요청 정보를 가져옴
            RequestDetails details = requestDetailsThreadLocal.get();
            if (details == null || details.wrappedRequest == null || details.wrappedResponse == null) {
                logger.error("Request details not available in afterCompletion");
                return;
            }

            // 인증 정보·원본 DB 연결·AI API 는 본문을 남기지 않는다 (LogBodyPolicy). 그 밖의 API 는 비밀값을 마스킹한 본문을 남긴다
            boolean protectedBody = LogBodyPolicy.isProtected(details.logUrl, handler);
            String requestBody = protectedBody ? LogBodyPolicy.PROTECTED : SensitiveDataMasker.mask(getRequestBody(details.wrappedRequest));
            logger.debug("Captured Request Body: " + requestBody);

            String header = response.getHeader("Content-Disposition");

            // fileData 데이터 저장 시 크기 문제로 인한 File Data 출력
            String responseBody = protectedBody ? LogBodyPolicy.PROTECTED
                    : header != null && (header.contains("attachment") || header.contains("filename=")) ? "File Data" : SensitiveDataMasker.mask(getResponseBody(details.wrappedResponse));
            logger.debug("Captured Response Body: " + responseBody);

            int statusCode = response.getStatus(); // 응답 상태 코드

            // 현재 로그인한 사용자 정보 가져오기
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AccountDto) {
                AccountDto accountDto = (AccountDto) authentication.getPrincipal();

                // 로그 객체 생성 및 저장
                log = Logs.builder()
                        .logUrl(details.logUrl)
                        .method(details.method)
                        .ip(details.ip)
                        .projectId(accountDto.getProjectId())
                        .username(accountDto.getUsername())
                        .roleName(accountDto.getRoleName())
                        .statusCode(statusCode)
                        .requestBody(requestBody) // 요청 본문 저장
                        .responseBody(responseBody) // 응답 본문 저장
                        .userAgent(details.userAgent)
                        .build();

                log.setTimestamp();

                // 예외 메시지는 저장 전에 설정 (@RestControllerAdvice로 처리된 예외는 ex가 null이므로 요청 속성에서 가져옴)
                Throwable error = ex != null ? ex : (Throwable) request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
                if (error != null) {
                    // 오류 메시지에도 요청 값이 들어갈 수 있다 : 보호 대상은 오류 코드만, 그 밖에는 메시지 안의 비밀(URL 의 인증 정보 등)을 가린다
                    log.setErrorMessage(protectedBody ? LogBodyPolicy.errorSummary(error) : SensitiveDataMasker.maskText(error.getMessage()));
                }

                logger.debug("Saved Log Data: " + log);
                logsService.saveLogs(log);
            }
        } catch (com.lexorma.common.exception.CustomException e) {
            // 요청을 처리하는 사이 프로젝트가 지워졌다 (자기 프로젝트를 지운 요청 등) : 지워진 프로젝트에는 로그도 남기지 않는다
            if (e.getErrorCode() != com.lexorma.common.exception.domain.ErrorCode.PROJECT_NOT_FOUND) logger.error("Error saving request log", e);
        } catch (Exception e) {
            logger.error("Error saving request log", e);
        } finally {
            // ThreadLocal 데이터 제거 (메모리 누수 방지)
            requestDetailsThreadLocal.remove();

            if (ex != null) {
                // 보호 대상 API 의 예외는 메시지·스택에 요청 값이 섞일 수 있어 종류만 남긴다
                if (LogBodyPolicy.isProtected(request.getRequestURI(), handler)) logger.error("Exception Occurred: {}", LogBodyPolicy.errorSummary(ex));
                else logger.error("Exception Occurred: ", ex);
            }
        }
    }

    /**
     * 요청 본문을 가져오는 메서드
     * ContentCachingRequestWrapper에서 저장된 요청 데이터를 읽어옴
     */
    private String getRequestBody(ContentCachingRequestWrapper request) {
        byte[] buf = request.getContentAsByteArray();
        return (buf.length > 0) ? new String(buf, StandardCharsets.UTF_8) : "N/A";
    }

    /**
     * 응답 본문을 가져오는 메서드
     * ContentCachingResponseWrapper에서 저장된 응답 데이터를 읽어옴
     */
    private String getResponseBody(ContentCachingResponseWrapper response) {
        byte[] buf = response.getContentAsByteArray();
        return (buf.length > 0) ? new String(buf, StandardCharsets.UTF_8) : "N/A";
    }

    /**
     * 요청 정보를 저장할 내부 클래스
     */
    private static class RequestDetails {
        String logUrl;
        String method;
        String ip;
        String userAgent;
        ContentCachingRequestWrapper wrappedRequest;
        ContentCachingResponseWrapper wrappedResponse;
    }
}
