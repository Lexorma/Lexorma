package com.lexorma.core.login.controller;

import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.core.security.service.MenuAuthorization;
import com.lexorma.core.user.service.UserService;
import com.lexorma.core.util.AuthUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * React 화면의 인증 상태 API
 * <p>
 * 로그인/로그아웃 자체는 Security 필터가 처리한다. (SecurityConfig)
 * <ul>
 *     <li>POST /api/auth/login  : username, password, project_id (form-urlencoded) → 200 / 401 {code, message}</li>
 *     <li>POST /api/auth/logout : 204</li>
 * </ul>
 * 로그인 전 프로젝트 조회 / 초기 비밀번호 변경은 LoginRestController (/api/login/projects, /api/login/password)
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
@Tag(name = "Auth Rest API", description = "로그인 상태 / CSRF 토큰 / 회원가입 API")
public class AuthRestController {

    private final MenuAuthorization menuAuthorization;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ProjectAuthority projectAuthority;

    @Operation(summary = "현재 로그인 정보", description = "로그인하지 않았으면 authenticated=false 를 반환합니다. 메뉴 노출 여부와 승인 권한을 함께 반환합니다.")
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AccountDto accountDto, HttpServletRequest request) {
        if (accountDto == null) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        // 그 사이 프로젝트 접근 권한이 없어졌으면 세션을 끊는다 (ProjectAccessInterceptor 와 같은 기준)
        // 복구 세션 : 프로젝트가 없고 메뉴·승인 권한이 모두 false 다 (화면은 recovery 로 복구 전용 화면만 보여 준다)
        boolean recovery = menuAuthorization.hasRole("RECOVERY");
        if (!recovery && (accountDto.getProjectId() == null || !projectRepository.isAccessibleProject(accountDto.getUsername(), accountDto.getProjectId()))) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            return ResponseEntity.ok(Map.of("authenticated", false, "code", "PROJECT_ACCESS_REVOKED"));
        }

        // 이름은 내 정보에서 바꿀 수 있으므로 세션에 담긴 값이 아니라 DB 값을 내려준다
        var account = userRepository.findByUsername(accountDto.getUsername());

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", accountDto.getId());
        user.put("username", accountDto.getUsername());
        user.put("name", account != null ? account.getName() : accountDto.getName());
        user.put("role", accountDto.getRole());
        user.put("roleName", accountDto.getRoleName());
        user.put("projectId", accountDto.getProjectId());
        user.put("projectName", recovery ? null : projectRepository.findById(accountDto.getProjectId()).map(p -> p.getProjectName()).orElse(null));

        // 사이드바 / 대시보드 메뉴 노출 (서버 권한 기준과 같은 값, 화면 숨김은 보안을 대신하지 않는다)
        Map<String, Boolean> menus = new LinkedHashMap<>();
        Stream.concat(MenuAuthorization.MENU_ROLES.keySet().stream(), MenuAuthorization.MENU_GROUPS.keySet().stream())
                .sorted()
                .forEach(menuId -> menus.put(menuId, menuAuthorization.canView(menuId)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        if (recovery) body.put("recovery", true); // 일반 세션의 응답은 그대로 둔다
        body.put("user", user);
        body.put("menus", menus);
        // 그리드의 소문자 버튼(승인 등) / 관리자 전용 동작 노출 여부.
        // 승인·관리 API 는 모두 USER 이상이라, 역할이 VIEWER 이하인 프로젝트 관리자에게는 버튼을 보이지 않는다 (눌러도 403)
        body.put("approvalAvailable", menuAuthorization.hasRole("USER") && AuthUtil.isApprovalAvailable());
        // 프로젝트 설정(원본 DB 연결·수집 등)을 바꿀 수 있는지 : 그 프로젝트의 관리자 또는 ADMIN (ProjectAuthority.canManage, 서버 확인과 같은 기준).
        // 역할 조건(USER 이상)은 버튼마다 menus 의 …-manage 로 함께 본다
        body.put("projectManageable", !recovery && projectAuthority.canManage(accountDto.getProjectId(), Long.valueOf(accountDto.getId())));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "CSRF 토큰", description = "변경 요청(POST/PUT/PATCH/DELETE)의 헤더(headerName)에 token 을 넣어 보냅니다. 로그인/로그아웃 후에는 다시 받아야 합니다.")
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok(Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken()
        ));
    }

    @Operation(summary = "회원가입 가능 프로젝트", description = "활성화된 프로젝트의 id / 프로젝트명")
    @PreAuthorize("hasRole('ANONYMOUS')")
    @GetMapping("/signup/projects")
    public ResponseEntity<List<Map<String, Object>>> signupProjects() {
        return ResponseEntity.ok(userService.selectAllProjectsIsActive().stream()
                .map(project -> Map.<String, Object>of("id", project.getId(), "projectName", project.getProjectName()))
                .toList());
    }

    @Operation(summary = "회원가입", description = "username, password, name, projectId. 가입한 계정은 NOT_APPROVE 권한이며 프로젝트 설정에 따라 승인 후 사용합니다.")
    @PreAuthorize("hasRole('ANONYMOUS')")
    @PostMapping("/signup")
    public ResponseEntity<Map<String, Boolean>> signup(@RequestBody AccountDto accountDto) {
        AccountDto request = AccountDto.builder()
                .username(accountDto.getUsername())
                .password(accountDto.getPassword())
                .name(accountDto.getName())
                .projectId(accountDto.getProjectId())
                .build();
        userService.createUser(request);
        return ResponseEntity.ok(Map.of("result", true));
    }
}
