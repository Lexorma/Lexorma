package com.lexorma.core.security.conf;

import com.lexorma.core.logs.service.RequestLogRecorder;
import com.lexorma.core.security.domain.dto.AccountDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/**
 * 요청/응답 본문 캐싱과 요청 로그 저장 : 필터 체인(인가 거부를 401·403 으로 바꾸는 ExceptionTranslationFilter 포함)을 다 거친 뒤
 * 최종 응답 상태로 요청 로그를 남긴다 ({@link RequestLogRecorder}).
 * (CSRF 토큰은 GET /api/auth/csrf 로 받으므로 예전의 XSRF-TOKEN 쿠키 갱신 처리는 하지 않는다)
 */
@Component
@RequiredArgsConstructor
public class CustomOncePerRequestFilter extends OncePerRequestFilter {

    private final RequestLogRecorder requestLogRecorder;

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

        // 체인 전에는 보안 컨텍스트를 읽지 않는다 : 여기서 세션의 컨텍스트를 미리 불러오면 로그인 중 다시 로그인(세션 고정 방지·동시 세션 1개)의
        // 세션 처리와 겹쳐 새 세션이 만료된다 (7차 E2E 에서 확인). 요청 중에 세션이 끊긴 경우의 사용자는 LogsInterceptor 가 남긴 값으로 쓴다
        boolean completed = false;
        try {
            // 필터 체인 실행 (중요: 감싼 요청과 응답을 사용해야 함)
            filterChain.doFilter(requestToUse, responseToUse);
            completed = true;
        } finally {
            AccountDto after = RequestLogRecorder.currentAccount();
            // 처리되지 않은 예외가 필터 밖으로 나가면 응답 상태는 아직 정해지기 전이다 : 커밋 전 응답이면 컨테이너가 500 으로 응답한다
            // (Tomcat StandardWrapperValve), 이미 커밋된 응답은 보낸 상태 그대로다
            int status = completed || response.isCommitted() ? responseToUse.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            requestLogRecorder.record(request, (ContentCachingRequestWrapper) requestToUse, (ContentCachingResponseWrapper) responseToUse,
                    after != null ? after : (AccountDto) request.getAttribute(RequestLogRecorder.ACCOUNT_ATTRIBUTE), status);
        }

        // 응답을 클라이언트로 다시 전달
        ((ContentCachingResponseWrapper) responseToUse).copyBodyToResponse();
    }

    /**
     * 정적 리소스(React 화면, 이미지, 폰트)는 로그 대상이 아니므로 감싸지 않는다. (큰 파일을 메모리에 담지 않음)
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
