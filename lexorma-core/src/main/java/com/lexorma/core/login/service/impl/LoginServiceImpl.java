package com.lexorma.core.login.service.impl;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.CustomFormException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.core.project.domain.dto.ProjectDto;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.login.service.LoginService;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.core.project.domain.entity.Project;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoginServiceImpl implements LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginServiceImpl.class);

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    /**
     * 로그인 전 단계 : 1. 로그인 정보(아이디/비밀번호)를 확인한 뒤 2. 접근 가능한 프로젝트를 조회한다.
     *
     * @param username
     * @param password
     * 초기 비밀번호(admin/admin, 관리자 초기화)를 사용 중이면 프로젝트를 조회하지 않고 PASSWORD_CHANGE_REQUIRED 로 막는다.
     *
     * @return 접근 가능한 프로젝트 목록 (1건 이상)
     */
    @Override
    public List<ProjectDto> selectLoginProjects(String username, String password) {

        Account account = authenticate(username, password);

        if (!account.isPasswdCheck()) {
            throw new CustomException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }

        List<ProjectDto> projects = projectRepository.findProjectsByUsername(username).stream()
                .map(ProjectDto::fromEntity)
                .toList();

        if (projects.isEmpty()) {
            throw new CustomException(ErrorCode.NO_ACCESSIBLE_PROJECT);
        }

        return projects;
    }

    /**
     * 로그인 전 초기 비밀번호 변경 : 아이디/현재 비밀번호를 다시 확인한 뒤 새 비밀번호로 변경한다.
     * 비밀번호 변경이 필요한 계정만 허용한다. (일반 계정의 비밀번호 변경은 로그인 후 내 정보에서 수행)
     */
    @Override
    @Transactional
    public void changeInitialPassword(String username, String password, String newPassword) {

        Account account = authenticate(username, password);

        if (account.isPasswdCheck()) {
            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }

        if (newPassword == null || newPassword.equals(password) || newPassword.equals(username)) {
            throw new CustomException(ErrorCode.PASSWORD_NOT_CHANGED);
        }

        if (!AuthUtil.isValidPassword(newPassword)) {
            throw new CustomException(ErrorCode.PASSWORD_IS_NON_VALIDATOR);
        }

        account.setPassword(passwordEncoder.encode(newPassword));
        account.setPasswdCheck(true);
    }

    /**
     * 아이디/비밀번호 확인 (활성 계정만). 실패 시 아이디 존재 여부가 드러나지 않도록 LOGIN_FAILED 로 통일한다.
     */
    private Account authenticate(String username, String password) {
        Account account = userRepository.findByUsernameAndIsActive(username, true);

        if (account == null || password == null || !passwordEncoder.matches(password, account.getPassword())) {
            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }
        return account;
    }

    /**
     * 패스워드 변경
     *
     * @param accountDto
     * @param accountData
     */
    @Transactional
    public void updatePwd(AccountDto accountDto, AccountDto accountData) {

        Account byUsername = userRepository.findByUsername(accountDto.getUsername());

        if(passwordEncoder.matches(accountData.getPassword(), byUsername.getPassword())) {

            if(!AuthUtil.isValidPassword(accountData.getCurrentPassword())){
                throw new CustomException(ErrorCode.PASSWORD_IS_NON_VALIDATOR);
            }

            byUsername.setPassword(passwordEncoder.encode(accountData.getCurrentPassword()));
            if(!byUsername.isPasswdCheck()){
                byUsername.setPasswdCheck(true);
            }
        }else{
            throw new CustomException(ErrorCode.PASSWORD_NOT_MATCHED);
        }
    }

    @Transactional
    public void updateName(AccountDto accountDto, String name) {
        userRepository.findByUsername(accountDto.getUsername()).setName(name);
    }

    /**
     * 복구 화면의 프로젝트 목록 (비활성 포함). 복구에 필요한 정보만 내려준다.
     */
    @Override
    public List<Map<String, Object>> selectRecoveryProjects() {
        return projectRepository.findRecoveryProjects().stream().map(row -> {
            Map<String, Object> project = new LinkedHashMap<>();
            project.put("id", row[0]);
            project.put("projectName", row[1]);
            project.put("isActive", Boolean.TRUE.equals(row[2]));
            // 기본 프로젝트 : 초기 데이터의 LEXORMA (id 0, 삭제 불가)
            project.put("isDefault", Long.valueOf(0L).equals(row[0]));
            project.put("projectManagerName", row[3]);
            project.put("updated", row[4]);
            return project;
        }).toList();
    }

    /**
     * 복구 세션의 프로젝트 활성화 : 활성 여부만 바꾼다 (이름·관리자·멤버는 건드리지 않는다). 이미 활성이면 그대로 둔다.
     */
    @Override
    @Transactional
    public void activateProject(Long projectId, String username) {
        // 세션은 로그인 시점의 역할을 담고 있으므로 DB 기준으로 다시 확인한다 (역할 변경·삭제는 세션도 끊지만 한 번 더 막는다)
        Account account = userRepository.findByUsernameAndIsActive(username, true);
        if (account == null || account.getUserRoles().stream().noneMatch(role -> "ROLE_ADMIN".equals(role.getRoleName()))) {
            throw new AccessDeniedException("시스템 관리자만 사용할 수 있습니다.");
        }

        Project project = projectRepository.findById(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
        if (!Boolean.TRUE.equals(project.getIsActive())) {
            project.setIsActive(true);
            log.warn("복구 세션 프로젝트 활성화 : projectId={}, projectName={}, username={}", projectId, project.getProjectName(), username);
        }
    }
}
