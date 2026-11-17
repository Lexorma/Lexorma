package com.lexorma.core.logs.interceptor;

import com.lexorma.core.logs.policy.LogBodyPolicy;
import com.lexorma.core.logs.service.RequestLogRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Restful API 형식으로 들어오는 HTTP 요청단위별 동작을 위해 Interceptor에 로깅 기능 추가
 * - 이 인터셉터는 로그 대상 경로(LogsConfig)의 핸들러와 핸들러 밖으로 나간 예외만 요청 속성에 남긴다
 * - 로그 저장은 요청 필터가 필터 체인을 다 거친 뒤 최종 응답 상태로 한다 ({@link RequestLogRecorder}) :
 *   여기(afterCompletion)는 {@code @PreAuthorize} 거부가 Security 에서 403 으로 바뀌기 전이라 상태가 아직 200 이다
 */
public class LogsInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LogsInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute("CACHED_REQUEST") == null || request.getAttribute("CACHED_RESPONSE") == null) {
            logger.error("wrappedRequest or wrappedResponse is NULL. 필터가 적용되지 않음.");
            return true;
        }
        request.setAttribute(RequestLogRecorder.HANDLER_ATTRIBUTE, handler);
        // 가장 먼저 도는 인터셉터 : 뒤의 인터셉터가 세션을 끊어도(프로젝트 접근 취소) 요청한 사용자로 남길 수 있게
        request.setAttribute(RequestLogRecorder.ACCOUNT_ATTRIBUTE, RequestLogRecorder.currentAccount());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (ex == null) return;
        request.setAttribute(RequestLogRecorder.ERROR_ATTRIBUTE, ex);
        // 권한 거부는 Security 가 403 으로 응답하는 정상 흐름이다 : 스택 없이 남긴다
        if (ex instanceof AccessDeniedException) logger.info("Access denied: {} {}", request.getMethod(), request.getRequestURI());
        // 보호 대상 API 의 예외는 메시지·스택에 요청 값이 섞일 수 있어 종류만 남긴다
        else if (LogBodyPolicy.isProtected(request.getRequestURI(), handler)) logger.error("Exception Occurred: {}", LogBodyPolicy.errorSummary(ex));
        else logger.error("Exception Occurred: ", ex);
    }
}
