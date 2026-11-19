package com.lexorma.core.login.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.CustomFormException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.domain.entity.Role;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.core.user.service.UserService;
import com.lexorma.core.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * 최초 관리자 계정 만들기 : 계정이 하나도 없을 때만 (초기 데이터 V1 에는 계정이 없다 — 예전 기본 계정 admin/admin 은 2026-11-19 에 뺐다).
 * <p>
 * 만든 계정은 시스템 ADMIN 이고 비밀번호 변경이 필요 없으며, 모든 프로젝트의 활성 멤버가 되고 관리자가 없는 프로젝트의 관리자가 된다
 * (새 설치에서는 기본 프로젝트 LEXORMA 하나).
 * "계정 없음" 확인과 계정 생성 사이에 다른 계정이 생기지 않도록 ROLE_ADMIN 역할 행을 배타적으로 잠근다.
 * 회원가입도 같은 행을 잠근 뒤 확인하므로 최초 관리자보다 먼저 가입 계정(NOT_APPROVE)이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InitialSetupService {

    private static final Logger log = LoggerFactory.getLogger(InitialSetupService.class);

    static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    /** 계정이 하나도 없으면 true (비활성 계정도 계정으로 센다) */
    public boolean isRequired() {
        return userRepository.count() == 0;
    }

    /** 최초 관리자 계정 만들기 (username, password, name) */
    @Transactional
    public void createAdmin(String username, String password, String name) {
        Role adminRole = roleRepository.findByRoleNameForUpdate(ADMIN_ROLE);
        if (!isRequired()) {
            throw new CustomException(ErrorCode.SETUP_ALREADY_COMPLETED);
        }

        if (!AuthUtil.isValidUsername(username)) {
            throw new CustomFormException(ErrorCode.USERNAME_IS_NON_VALIDATOR);
        }
        if (password == null || !AuthUtil.isValidPassword(password)) {
            throw new CustomFormException(ErrorCode.PASSWORD_IS_NON_VALIDATOR);
        }
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new CustomFormException(ErrorCode.USER_NAME_REQUIRED);
        }

        Account account = new Account();
        account.setUsername(username);
        account.setPassword(passwordEncoder.encode(password));
        account.setName(trimmedName);
        account.setIsActive(true);
        account.setPasswdCheck(true);
        account.setUserRoles(new HashSet<>(Set.of(adminRole)));
        userRepository.save(account);

        // 계정이 없었으므로 어느 프로젝트에도 멤버가 없다 : 모든 프로젝트에 활성 멤버로 넣는다 (비활성 프로젝트는 복구 로그인으로 다시 켠다)
        projectRepository.findAll().stream()
                .sorted(Comparator.comparing(Project::getId))
                .forEach(project -> {
                    projectMemberRepository.save(ProjectMember.builder()
                            .project(project)
                            .account(account)
                            .isActive(true)
                            .build());
                    if (project.getAccount() == null) {
                        project.setAccount(account);
                    }
                });

        log.warn("최초 관리자 계정 생성 : username={}", username);
    }

    /** 회원가입 : 최초 관리자 계정이 없으면 막는다 (가입 계정은 NOT_APPROVE 라 권한을 줄 관리자가 없게 된다) */
    @Transactional
    public void signup(AccountDto accountDto) {
        roleRepository.findByRoleNameForUpdate(ADMIN_ROLE);
        if (isRequired()) {
            throw new CustomException(ErrorCode.SETUP_REQUIRED);
        }
        userService.createUser(accountDto);
    }
}
