package com.lexorma.core.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 로그인 성공 (POST /api/auth/login) : 세션은 Security 가 만들고(세션 고정 보호 포함) 브라우저에는 세션 쿠키만 내려간다.
 * 화면은 이어서 GET /api/auth/me 로 사용자 정보를, GET /api/auth/csrf 로 새 CSRF 토큰을 받는다. (로그인하면 토큰이 바뀐다)
 */
@Component("successHandler")
public class FormAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(final HttpServletRequest request, final HttpServletResponse response, final Authentication authentication) throws IOException {
        // 초기 비밀번호 계정은 Provider 에서 로그인이 막히므로(PASSWORD_CHANGE_REQUIRED) 여기서는 항상 false
        JsonResponses.write(response, HttpServletResponse.SC_OK, Map.of(
                "authenticated", true,
                "passwordChangeRequired", false
        ));
    }
}
