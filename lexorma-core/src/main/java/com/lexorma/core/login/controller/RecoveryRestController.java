package com.lexorma.core.login.controller;

import com.lexorma.core.login.service.LoginService;
import com.lexorma.core.security.domain.dto.AccountDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 복구 세션 전용 API : 활성 프로젝트가 하나도 없을 때 시스템 ADMIN 이 복구 로그인(POST /api/auth/login, recovery=true)한 세션만 쓸 수 있다.
 * 프로젝트 목록 조회와 활성화만 제공한다. 복구를 마치면 로그아웃(POST /api/auth/logout)한 뒤 정상 로그인한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/recovery")
@PreAuthorize("hasRole('RECOVERY')")
@Tag(name = "Recovery Rest API", description = "복구 세션의 프로젝트 재활성화 API")
public class RecoveryRestController {

    private final LoginService loginService;

    @Operation(summary = "복구 대상 프로젝트 목록", description = "비활성 프로젝트를 포함한 전체 프로젝트")
    @GetMapping("/projects")
    public ResponseEntity<List<Map<String, Object>>> projects() {
        return ResponseEntity.ok(loginService.selectRecoveryProjects());
    }

    @Operation(summary = "프로젝트 활성화", description = "프로젝트를 활성화합니다. 이미 활성화된 프로젝트는 그대로 둡니다.")
    @PostMapping("/projects/{projectId}/activate")
    public ResponseEntity<Map<String, Boolean>> activate(@PathVariable(name = "projectId") Long projectId, @AuthenticationPrincipal AccountDto accountDto) {
        loginService.activateProject(projectId, accountDto.getUsername());
        return ResponseEntity.ok(Map.of("result", true));
    }
}
