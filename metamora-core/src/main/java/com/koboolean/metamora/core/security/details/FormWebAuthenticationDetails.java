package com.koboolean.metamora.core.security.details;

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
        String projectIdParam = request.getParameter("project_id");
        projectId = (projectIdParam == null || projectIdParam.isBlank()) ? null : Long.parseLong(projectIdParam);
    }
}
