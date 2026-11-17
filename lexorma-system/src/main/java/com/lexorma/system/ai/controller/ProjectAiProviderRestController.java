package com.lexorma.system.ai.controller;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.service.AiDefaultProviderService;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트별 AI Provider 지정 (DEC-18). 시스템 기본 Provider 는 그대로 두고, 프로젝트가 다른 Provider·모델을 쓰도록 선택적으로 지정한다.
 * <ul>
 *   <li>조회 : 로그인한 프로젝트, 또는 그 프로젝트를 관리할 수 있는 사용자(관리자·ADMIN)</li>
 *   <li>지정·해제 : 프로젝트 관리자 또는 ADMIN (DB 에서 다시 확인 — {@link ProjectAuthority#canManage}). Provider 등록·수정은 여전히 ADMIN 만(/api/ai/providers)</li>
 *   <li>AI 서버에 연결하지 않는다. 지정한 Provider 를 쓸 수 없게 되면 그 프로젝트의 AI 기능은 거절되고 시스템 기본으로 바뀌지 않는다</li>
 * </ul>
 * 경로가 /api/ai/** 라 요청 로그에 본문을 남기지 않는다 (LogBodyPolicy).
 */
@Tag(name = "Project AI Provider API", description = "프로젝트별 AI Provider 지정 (DEC-18)")
@RestController
@RequestMapping("/api/ai/projects/{projectId}/provider")
@RequiredArgsConstructor
public class ProjectAiProviderRestController {

    private final AiDefaultProviderService service;
    private final ProjectAuthority projectAuthority;

    @Operation(summary = "프로젝트 AI Provider 조회", description = "scope PROJECT(지정) · SYSTEM(지정 없음 → 시스템 기본), 실제로 쓸 Provider 의 상태. 관리할 수 있으면 고를 수 있는 Provider 목록(주소 없음)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping
    public ResponseEntity<AiProviderDto.ProjectProvider> get(@PathVariable(value = "projectId") Long projectId, @AuthenticationPrincipal AccountDto accountDto) {
        boolean canManage = canManage(projectId, accountDto);
        if (!canManage && !projectId.equals(accountDto.getProjectId())) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
        return ResponseEntity.ok(service.project(projectId, canManage));
    }

    @Operation(summary = "프로젝트 AI Provider 지정·변경", description = "프로젝트 관리자·ADMIN. 활성·지원 유형·모델(지정 또는 Provider 기본 모델) 있는 Provider 만")
    @PreAuthorize("hasRole('USER')")
    @PutMapping
    public ResponseEntity<AiProviderDto.ProjectProvider> set(@PathVariable(value = "projectId") Long projectId, @RequestBody AiProviderDto.ProjectProviderRequest request,
                                                             @AuthenticationPrincipal AccountDto accountDto) {
        requireManage(projectId, accountDto);
        service.setProject(projectId, request);
        return ResponseEntity.ok(service.project(projectId, true));
    }

    @Operation(summary = "프로젝트 AI Provider 지정 해제", description = "프로젝트 관리자·ADMIN. 해제하면 시스템 기본 Provider 를 쓴다")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping
    public ResponseEntity<AiProviderDto.ProjectProvider> clear(@PathVariable(value = "projectId") Long projectId, @AuthenticationPrincipal AccountDto accountDto) {
        requireManage(projectId, accountDto);
        service.clearProject(projectId);
        return ResponseEntity.ok(service.project(projectId, true));
    }

    private boolean canManage(Long projectId, AccountDto accountDto) {
        return accountDto != null && accountDto.getId() != null && projectAuthority.canManage(projectId, Long.valueOf(accountDto.getId()));
    }

    private void requireManage(Long projectId, AccountDto accountDto) {
        if (!canManage(projectId, accountDto)) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
    }
}
