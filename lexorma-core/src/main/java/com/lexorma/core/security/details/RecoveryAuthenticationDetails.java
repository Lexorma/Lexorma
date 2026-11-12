package com.lexorma.core.security.details;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 복구 로그인 요청 (POST /api/auth/login, recovery=true) 표시.
 * 세션 DB 에 이미 직렬화된 FormWebAuthenticationDetails 의 형태를 바꾸지 않도록 필드를 추가하지 않고 별도 클래스로 구분한다.
 */
public class RecoveryAuthenticationDetails extends FormWebAuthenticationDetails {

    public RecoveryAuthenticationDetails(HttpServletRequest request) {
        super(request);
    }
}
