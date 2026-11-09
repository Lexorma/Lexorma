package com.koboolean.metamora.core.security.conf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * 요청/응답 본문 캐싱 : LogsInterceptor 가 요청 로그(요청/응답 본문)를 남길 수 있도록 감싼다.
 * (CSRF 토큰은 GET /api/auth/csrf 로 받으므로 예전의 XSRF-TOKEN 쿠키 갱신 처리는 하지 않는다)
 */
@Component
public class CustomOncePerRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        // Only wrap if not already wrapped
        HttpServletRequest requestToUse = request;
        HttpServletResponse responseToUse = response;

        if (!(requestToUse instanceof ContentCachingRequestWrapper)) {
            // 캐시 크기 제한 없는 생성자는 deprecated(삭제 예정) : 기존 동작(제한 없음)을 유지하도록 최대값 지정
            requestToUse = new ContentCachingRequestWrapper(request, Integer.MAX_VALUE);
        }

        if (!(responseToUse instanceof ContentCachingResponseWrapper)) {
            responseToUse = new ContentCachingResponseWrapper(response);
        }

        // 요청 본문을 강제적으로 캐싱하지 않고 그대로 유지
        request.setAttribute("CACHED_REQUEST", requestToUse);
        request.setAttribute("CACHED_RESPONSE", responseToUse);

        // 필터 체인 실행 (중요: 감싼 요청과 응답을 사용해야 함)
        filterChain.doFilter(requestToUse, responseToUse);

        // 응답을 클라이언트로 다시 전달
        if (responseToUse instanceof ContentCachingResponseWrapper) {
            ((ContentCachingResponseWrapper) responseToUse).copyBodyToResponse();
        }
    }

    /**
     * 정적 리소스(React 화면, 이미지, 폰트)는 로그 대상이 아니므로 감싸지 않는다. (큰 파일을 메모리에 담지 않음)
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
