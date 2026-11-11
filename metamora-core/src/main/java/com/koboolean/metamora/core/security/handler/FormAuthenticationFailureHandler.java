package com.koboolean.metamora.core.security.handler;

import com.koboolean.metamora.common.exception.ProjectAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인 실패 (POST /api/auth/login) : 401 + code / message
 */
@Component("failureHandler")
public class FormAuthenticationFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(final HttpServletRequest request, final HttpServletResponse response, final AuthenticationException exception) throws IOException {

        if (exception instanceof CredentialsExpiredException) {
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "PASSWORD_CHANGE_REQUIRED");
        } else if (exception instanceof ProjectAccessException) {
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "PROJECT_ACCESS_DENIED");
        } else if (exception instanceof SessionAuthenticationException) {
            // maximumSessions(1) + maxSessionsPreventsLogin : 이미 로그인된 세션이 있다
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "SESSION_LIMIT_EXCEEDED");
        } else {
            // 아이디 없음 / 비밀번호 불일치는 아이디 존재 여부가 드러나지 않도록 같은 메시지 사용
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_CREDENTIALS");
        }
    }
}
