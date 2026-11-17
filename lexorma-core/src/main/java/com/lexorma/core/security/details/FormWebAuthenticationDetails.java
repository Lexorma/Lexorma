package com.lexorma.core.security.details;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * 로그인 폼의 project_id 를 함께 받는다.
 * ProviderManager 가 인증 결과에 details 를 복사해 세션 DB 에 직렬화하므로 직렬화할 수 없는 값(HttpServletRequest 등)은 두지 않는다.
 */
@Getter
public class FormWebAuthenticationDetails extends WebAuthenticationDetails {

    private final Long projectId;

    public FormWebAuthenticationDetails(HttpServletRequest request) {
        super(request);
        projectId = parse(request.getParameter("project_id"));
    }

    /** 숫자가 아니면 null : 선택하지 않은 것과 같이 "접근할 수 없는 프로젝트" 로 거절된다 (예전에는 NumberFormatException → 500) */
    private static Long parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
