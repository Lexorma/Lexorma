package com.lexorma.core.project.service;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 프로젝트에 대한 권한 판정 (모든 모듈 공용). 세션이 아니라 <b>지금의 DB</b> 를 본다 : 로그인한 뒤 바뀐 관리자·멤버·역할·활성 상태가 반영된다.
 * <p>
 * 역할은 계정 단위 전역이고(프로젝트별 역할은 없다) 프로젝트 관리자는 프로젝트의 관리자 계정(MNGR_ACNT_SN) 한 명이다.
 * 프로젝트 관리 화면(lexorma-system)의 관리자 확인도 여기를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class ProjectAuthority {

    private static final String ADMIN = "ROLE_ADMIN";

    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final RoleHierarchy roleHierarchy;

    /**
     * 프로젝트 하나에 대해 계정이 지금 가진 것. 없는 프로젝트·계정이면 해당 항목이 모두 false 다
     *
     * @param accessible  로그인할 때와 같은 기준 : 활성 프로젝트 + 활성 계정 + 승인된(활성) 멤버
     * @param manager     프로젝트의 관리자 계정인지 (프로젝트가 비활성이어도 관리자는 관리자다)
     * @param authorities 계정의 권한 (역할 계층 적용, ROLE_ 접두사 포함). 비활성 계정은 비어 있다
     */
    public record Access(boolean projectExists, boolean projectActive, boolean accountActive, boolean accessible, boolean manager,
                         String username, String name, Set<String> authorities) {

        public boolean admin() {
            return authorities.contains(ADMIN);
        }

        /**
         * 프로젝트 설정을 바꿀 수 있는지 : 그 프로젝트에 접근할 수 있고(활성 프로젝트의 승인된 멤버) 프로젝트 관리자이거나 ADMIN.
         * ADMIN 이라도 멤버가 아닌 프로젝트는 바꿀 수 없다 — 다른 프로젝트로 넘어가는 길을 만들지 않는다
         */
        public boolean canManage() {
            return accessible && (manager || admin());
        }
    }

    @Transactional(readOnly = true)
    public Access access(Long projectId, Long accountId) {
        Project project = projectId == null ? null : projectRepository.findById(projectId).orElse(null);
        Account account = accountId == null ? null : accountRepository.findById(accountId).orElse(null);
        boolean projectActive = project != null && Boolean.TRUE.equals(project.getIsActive());
        boolean accountActive = account != null && Boolean.TRUE.equals(account.getIsActive());
        boolean manager = project != null && account != null && project.getAccount() != null && project.getAccount().getId().equals(account.getId());
        boolean accessible = projectActive && accountActive && projectRepository.isAccessibleProject(account.getUsername(), projectId);
        Set<String> authorities = accountActive ? authorities(account) : Set.of();
        return new Access(project != null, projectActive, accountActive, accessible, manager,
                account == null ? null : account.getUsername(), account == null ? null : account.getName(), authorities);
    }

    /** 프로젝트의 관리자 계정인지. 없는 프로젝트·계정이면 false */
    @Transactional(readOnly = true)
    public boolean isManager(Long projectId, Long accountId) {
        if (projectId == null || accountId == null) return false;
        return projectRepository.findById(projectId)
                .map(project -> project.getAccount() != null && project.getAccount().getId().equals(accountId))
                .orElse(false);
    }

    /** 프로젝트 설정을 바꿀 수 있는지 ({@link Access#canManage}) */
    @Transactional(readOnly = true)
    public boolean canManage(Long projectId, Long accountId) {
        return access(projectId, accountId).canManage();
    }

    private Set<String> authorities(Account account) {
        Set<SimpleGrantedAuthority> granted = account.getUserRoles().stream().map(role -> new SimpleGrantedAuthority(role.getRoleName())).collect(Collectors.toSet());
        return roleHierarchy.getReachableGrantedAuthorities(granted).stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
    }
}
