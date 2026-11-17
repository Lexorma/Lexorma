package com.lexorma.core.logs.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.logs.conf.LogsConfig;
import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.policy.LogBodyPolicy;
import com.lexorma.core.logs.util.SensitiveDataMasker;
import com.lexorma.core.security.domain.dto.AccountDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;

/**
 * 요청 로그(TB_LOG) 한 건을 남긴다. 저장 시점은 요청 필터({@code CustomOncePerRequestFilter})가 필터 체인을 다 거친 뒤다 —
 * 컨트롤러 뒤(인터셉터 afterCompletion)에는 {@code @PreAuthorize} 거부가 아직 403 으로 바뀌기 전이라(ExceptionTranslationFilter 가 나중에 바꾼다)
 * 상태가 200 으로 남았다 (7차 수정). 상태는 응답에 실제로 정해진 값만 쓴다.
 * <ul>
 *   <li>컨트롤러까지 온 요청({@link #HANDLER_ATTRIBUTE}, LogsInterceptor 가 남긴다) : 결과와 관계없이 한 건</li>
 *   <li>컨트롤러 전에 거절된 요청(URL 권한·프로젝트 접근 취소·만료 세션 등) : 로그인 사용자의 4xx·5xx 만 한 건 (성공한 로그인처럼 필터가 끝낸 요청은 지금처럼 남기지 않는다)</li>
 *   <li>로그인하지 않은 요청은 지금처럼 남기지 않는다</li>
 * </ul>
 * 본문 정책은 그대로다 : 보호 대상(LogBodyPolicy)은 본문 없이, 그 밖에는 비밀값을 가린 본문.
 */
@Component
@RequiredArgsConstructor
public class RequestLogRecorder {

    private static final Logger logger = LoggerFactory.getLogger(RequestLogRecorder.class);

    /** 컨트롤러까지 온 요청의 핸들러 (로그 대상 경로만, LogsInterceptor.preHandle) */
    public static final String HANDLER_ATTRIBUTE = RequestLogRecorder.class.getName() + ".handler";
    /** 컨트롤러 앞에서의 로그인 사용자 (LogsInterceptor.preHandle) : 요청 중에 세션이 끊긴 경우(ProjectAccessInterceptor 401)에 쓴다 */
    public static final String ACCOUNT_ATTRIBUTE = RequestLogRecorder.class.getName() + ".account";
    /** 핸들러 밖으로 나간 예외 (LogsInterceptor.afterCompletion) */
    public static final String ERROR_ATTRIBUTE = RequestLogRecorder.class.getName() + ".error";

    private final LogsService logsService;

    /** 지금 보안 컨텍스트의 로그인 사용자 (없으면 null) */
    public static AccountDto currentAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AccountDto account ? account : null;
    }

    /**
     * @param account 요청 뒤의 로그인 사용자, 요청 중에 세션이 끊겼으면 컨트롤러 앞에서의 사용자 ({@link #ACCOUNT_ATTRIBUTE})
     * @param status  응답에 정해진 최종 HTTP 상태
     */
    public void record(HttpServletRequest request, ContentCachingRequestWrapper wrappedRequest, ContentCachingResponseWrapper wrappedResponse,
                       AccountDto account, int status) {
        if (account == null) return;
        Object handler = request.getAttribute(HANDLER_ATTRIBUTE);
        // 핸들러가 없다 = 로그에서 뺀 경로(LogsConfig : ERD·/data)이거나 컨트롤러 전에 끝난 요청 : 뺀 경로와 성공 응답은 지금처럼 남기지 않는다
        if (handler == null && (LogsConfig.isExcludedPath(request.getRequestURI()) || status < 400)) return;
        try {
            String url = request.getRequestURI();
            // 인증 정보·원본 DB 연결·AI API 는 본문을 남기지 않는다 (LogBodyPolicy). 그 밖의 API 는 비밀값을 마스킹한 본문을 남긴다
            boolean protectedBody = LogBodyPolicy.isProtected(url, handler);
            String requestBody = protectedBody ? LogBodyPolicy.PROTECTED : SensitiveDataMasker.mask(body(wrappedRequest.getContentAsByteArray()));
            String header = wrappedResponse.getHeader("Content-Disposition");
            // fileData 데이터 저장 시 크기 문제로 인한 File Data 출력
            String responseBody = protectedBody ? LogBodyPolicy.PROTECTED
                    : header != null && (header.contains("attachment") || header.contains("filename=")) ? "File Data" : SensitiveDataMasker.mask(body(wrappedResponse.getContentAsByteArray()));

            Logs log = Logs.builder()
                    .logUrl(url)
                    .method(request.getMethod())
                    .ip(request.getRemoteAddr())
                    .projectId(account.getProjectId())
                    .username(account.getUsername())
                    .roleName(account.getRoleName())
                    .statusCode(status)
                    .requestBody(requestBody)
                    .responseBody(responseBody)
                    .userAgent(request.getHeader("User-Agent"))
                    .build();
            log.setTimestamp();

            // @RestControllerAdvice 로 처리된 예외는 요청 속성(EXCEPTION_ATTRIBUTE)으로, 처리되지 않은 예외는 인터셉터가 남긴 속성으로 온다
            Throwable error = (Throwable) request.getAttribute(ERROR_ATTRIBUTE);
            if (error == null) error = (Throwable) request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
            if (error != null) {
                // 오류 메시지에도 요청 값이 들어갈 수 있다 : 보호 대상은 오류 코드만, 그 밖에는 메시지 안의 비밀(URL 의 인증 정보 등)을 가린다
                log.setErrorMessage(protectedBody ? LogBodyPolicy.errorSummary(error) : SensitiveDataMasker.maskText(error.getMessage()));
            }
            logsService.saveLogs(log);
        } catch (CustomException e) {
            // 요청을 처리하는 사이 프로젝트가 지워졌다 (자기 프로젝트를 지운 요청 등) : 지워진 프로젝트에는 로그도 남기지 않는다
            if (e.getErrorCode() != ErrorCode.PROJECT_NOT_FOUND) logger.error("Error saving request log", e);
        } catch (Exception e) {
            logger.error("Error saving request log", e);
        }
    }

    private static String body(byte[] buf) {
        return buf.length > 0 ? new String(buf, StandardCharsets.UTF_8) : "N/A";
    }
}
