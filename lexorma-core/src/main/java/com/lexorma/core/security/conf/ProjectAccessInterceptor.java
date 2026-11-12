package com.lexorma.core.security.conf;

import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.handler.JsonResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 세션의 프로젝트 접근 권한 재확인
 * <p>
 * 프로젝트 ID 는 로그인할 때 확인해 세션(Principal)에 담는다. 세션은 DB 에 저장되어 재시작 후에도 남으므로,
 * 그 사이 프로젝트가 비활성화되거나 멤버에서 빠진(승인 취소) 경우 세션 값만으로 계속 접근하지 못하도록 API 요청마다 다시 확인한다.
 * 접근할 수 없으면 세션을 끊고 401(PROJECT_ACCESS_REVOKED)을 반환한다.
 */
@RequiredArgsConstructor
public class ProjectAccessInterceptor implements HandlerInterceptor {

    private final ProjectRepository projectRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountDto accountDto)) {
            return true; // 익명 요청은 Security(@PreAuthorize)가 처리한다
        }

        if (accountDto.getProjectId() != null
                && projectRepository.isAccessibleProject(accountDto.getUsername(), accountDto.getProjectId())) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();

        JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "PROJECT_ACCESS_REVOKED");
        return false;
    }
}
