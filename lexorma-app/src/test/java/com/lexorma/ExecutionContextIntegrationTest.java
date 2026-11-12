package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.crypto.SecretScope;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextHolder;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.system.project.service.ProjectManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-1a 공통 기반 (H2 / PostgreSQL) : 실행 컨텍스트의 DB 재검증과 프로젝트 관리자 판정.
 * 로그인 세션 없이(SecurityContext 비움) 돌려, 비동기·정기 배치에서도 같은 확인이 되는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ExecutionContextIntegrationTest {

    @Autowired ExecutionContextVerifier verifier;
    @Autowired ProjectAuthority projectAuthority;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ProjectManageService projectManageService;
    @Autowired SecretCipher secretCipher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("프로젝트 범위·역할·관리자 : 세션 없이 DB 로 확인한다. 다른 프로젝트, 부족한 역할, 관리자가 아닌 요청자는 거절되고 ADMIN 도 멤버가 아닌 프로젝트는 바꿀 수 없다")
    void scopeRoleManager() {
        SecurityContextHolder.clearContext();
        Account admin = admin();
        Account manager = account("mgr", "ROLE_DBA");
        Account member = account("usr", "ROLE_USER");
        Account viewer = account("vwr", "ROLE_VIEWER");
        Project mine = project("ai1a-mine", manager, manager, member, viewer, admin);
        Project other = project("ai1a-other", manager, manager);

        // 사용자 요청 : 세션의 사용자·프로젝트 그대로. 권한은 DB 의 역할에 역할 계층을 적용한 것
        VerifiedExecution user = verifier.verify(ExecutionContext.user(principal(member, mine)));
        assertThat(user.projectId()).isEqualTo(mine.getId());
        assertThat(user.username()).isEqualTo(member.getUsername());
        assertThat(user.auditor()).isEqualTo(member.getName());
        assertThat(user.authorities()).contains("ROLE_USER", "ROLE_VIEWER", "ROLE_NOT_APPROVE").doesNotContain("ROLE_DBA", "ROLE_ADMIN");
        assertThat(user.manager()).isFalse();

        // 다른 프로젝트 : 멤버가 아니면 프로젝트 ID 를 알아도 실행할 수 없다 (비동기로 넘겨도 같다)
        assertThatThrownBy(() -> verifier.verify(ExecutionContext.async(other.getId(), member.getId(), "job-1"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(verifier.verify(ExecutionContext.async(mine.getId(), member.getId(), "job-1")).username()).isEqualTo(member.getUsername());

        // 역할 : 컨트롤러의 hasRole 과 같은 기준
        assertThat(verifier.verify(ExecutionContext.async(mine.getId(), member.getId(), "job-2"), "USER").hasRole("USER")).isTrue();
        assertThat(verifier.verify(ExecutionContext.async(mine.getId(), viewer.getId(), "job-2"), "VIEWER").hasRole("VIEWER")).isTrue();
        assertThatThrownBy(() -> verifier.verify(ExecutionContext.async(mine.getId(), viewer.getId(), "job-2"), "USER")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));

        // 프로젝트 관리자 또는 ADMIN : 프로젝트의 관리자 계정과 ADMIN 역할은 서로 다른 조건이다
        VerifiedExecution byManager = verifier.verifyProjectManager(ExecutionContext.async(mine.getId(), manager.getId(), "job-3"));
        assertThat(byManager.manager()).isTrue();
        assertThat(byManager.hasRole("ADMIN")).isFalse();
        VerifiedExecution byAdmin = verifier.verifyProjectManager(ExecutionContext.async(mine.getId(), admin.getId(), "job-3"));
        assertThat(byAdmin.manager()).isFalse();
        assertThat(byAdmin.hasRole("ADMIN")).isTrue();
        assertThatThrownBy(() -> verifier.verifyProjectManager(ExecutionContext.async(mine.getId(), member.getId(), "job-3"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        // ADMIN 이라도 멤버가 아닌 프로젝트는 접근 자체가 안 된다
        assertThatThrownBy(() -> verifier.verifyProjectManager(ExecutionContext.async(other.getId(), admin.getId(), "job-3"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));

        // 공용 판정 (프로젝트 관리 화면과 같은 기준)
        assertThat(projectAuthority.isManager(mine.getId(), manager.getId())).isTrue();
        assertThat(projectAuthority.isManager(mine.getId(), admin.getId())).isFalse();
        assertThat(projectAuthority.isManager(999_999_999L, manager.getId())).isFalse();
        assertThat(projectAuthority.isManager(mine.getId(), null)).isFalse();
        assertThat(projectAuthority.canManage(mine.getId(), manager.getId())).isTrue();
        assertThat(projectAuthority.canManage(mine.getId(), admin.getId())).isTrue();
        assertThat(projectAuthority.canManage(mine.getId(), member.getId())).isFalse();
        assertThat(projectAuthority.canManage(other.getId(), admin.getId())).isFalse();
        assertThat(projectAuthority.canManage(999_999_999L, admin.getId())).isFalse();
        assertThat(projectManageService.selectLoginProject(mine.getId(), String.valueOf(manager.getId()))).isTrue();
        assertThat(projectManageService.selectLoginProject(mine.getId(), String.valueOf(member.getId()))).isFalse();
    }

    @Test
    @DisplayName("요청한 뒤에 바뀐 것 : 역할 변경, 멤버 승인 취소, 계정 비활성화는 다음 확인에서 바로 거절된다 (세션·작업에 남은 값을 믿지 않는다)")
    void revoked() {
        SecurityContextHolder.clearContext();
        Account manager = account("mgr", "ROLE_DBA");
        Account member = account("usr", "ROLE_USER");
        Project project = project("ai1a-revoke", manager, manager, member);
        // 요청 시점에 고정한 컨텍스트 (비동기 작업에 저장해 둔 값)
        ExecutionContext queued = ExecutionContext.async(project.getId(), member.getId(), "job-9");
        assertThat(verifier.verify(queued, "USER").hasRole("USER")).isTrue();

        // 역할이 내려갔다
        tx.executeWithoutResult(s -> {
            Account account = accountRepository.findById(member.getId()).orElseThrow();
            account.getUserRoles().clear();
            account.getUserRoles().add(roleRepository.findByRoleName("ROLE_VIEWER"));
        });
        assertThatThrownBy(() -> verifier.verify(queued, "USER")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThat(verifier.verify(queued).hasRole("VIEWER")).isTrue();

        // 멤버 승인이 취소됐다
        tx.executeWithoutResult(s -> projectMemberRepository.findAllByProject_Id(project.getId()).stream()
                .filter(m -> m.getAccount().getId().equals(member.getId())).forEach(m -> m.setIsActive(false)));
        assertThatThrownBy(() -> verifier.verify(queued)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));

        // 관리자 계정이 비활성화됐다 : 관리자여도 실행할 수 없다
        ExecutionContext byManager = ExecutionContext.async(project.getId(), manager.getId(), "job-10");
        assertThat(verifier.verifyProjectManager(byManager).manager()).isTrue();
        tx.executeWithoutResult(s -> accountRepository.findById(manager.getId()).orElseThrow().setIsActive(false));
        assertThatThrownBy(() -> verifier.verifyProjectManager(byManager)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(projectAuthority.canManage(project.getId(), manager.getId())).isFalse();
        // 없는 계정
        assertThatThrownBy(() -> verifier.verify(ExecutionContext.async(project.getId(), 999_999_999L, "job-11"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
    }

    @Test
    @DisplayName("프로젝트 비활성화·삭제 : 사용자 요청·비동기·정기 배치 모두 실행이 막힌다. 정기 배치는 활성 프로젝트에서만 돌고 사람의 권한이 필요한 일은 못 한다")
    void projectLifecycle() {
        SecurityContextHolder.clearContext();
        Account manager = account("mgr", "ROLE_DBA");
        Project project = project("ai1a-life", manager, manager);
        ExecutionContext async = ExecutionContext.async(project.getId(), manager.getId(), "job-1");
        ExecutionContext scheduled = ExecutionContext.scheduled(project.getId(), "nightly");

        VerifiedExecution batch = verifier.verify(scheduled);
        assertThat(batch.username()).isNull();
        assertThat(batch.auditor()).isEqualTo(VerifiedExecution.SCHEDULER);
        assertThat(batch.authorities()).isEmpty();
        assertThat(verifier.verify(scheduled, "USER").projectId()).isEqualTo(project.getId());
        assertThatThrownBy(() -> verifier.verifyProjectManager(scheduled)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));

        // 비활성화 : 데이터는 남지만 실행은 안 된다. 관리자는 여전히 관리자다 (다시 활성화할 수 있다)
        tx.executeWithoutResult(s -> projectRepository.findById(project.getId()).orElseThrow().setIsActive(false));
        assertThatThrownBy(() -> verifier.verify(async)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThatThrownBy(() -> verifier.verify(scheduled)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(projectAuthority.isManager(project.getId(), manager.getId())).isTrue();
        assertThat(projectAuthority.canManage(project.getId(), manager.getId())).isFalse();
        tx.executeWithoutResult(s -> projectRepository.findById(project.getId()).orElseThrow().setIsActive(true));
        assertThat(verifier.verify(async).manager()).isTrue();

        // 영구 삭제 : 기다리던 작업이 실행되려는 순간 프로젝트가 없다
        AccountDto session = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA");
        projectManageService.deleteProject(project.getId(), session);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> verifier.verify(async)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_NOT_FOUND));
        assertThatThrownBy(() -> verifier.verify(scheduled)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_NOT_FOUND));
        assertThat(projectAuthority.isManager(project.getId(), manager.getId())).isFalse();
    }

    @Test
    @DisplayName("세션 없는 실행의 등록자 : 실행 컨텍스트의 요청자(정기 배치는 SCHEDULER)가 남고, 범위를 벗어나면 남지 않는다")
    void auditorWithoutSession() {
        SecurityContextHolder.clearContext();
        Account manager = account("mgr", "ROLE_DBA");
        Project project = project("ai1a-audit", manager, manager);

        try (ExecutionContextHolder.Scope scope = ExecutionContextHolder.open(verifier.verify(ExecutionContext.async(project.getId(), manager.getId(), "job-1")))) {
            assertThat(registrar(project("ai1a-audit-async", manager))).isEqualTo(manager.getName());
        }
        try (ExecutionContextHolder.Scope scope = ExecutionContextHolder.open(verifier.verify(ExecutionContext.scheduled(project.getId(), "nightly")))) {
            assertThat(registrar(project("ai1a-audit-batch", manager))).isEqualTo(VerifiedExecution.SCHEDULER);
        }
        assertThat(ExecutionContextHolder.current()).isEmpty();
        assertThat(registrar(project("ai1a-audit-none", manager))).isNull();

        // 로그인 세션이 있으면 지금까지처럼 세션의 사용자다 (실행 컨텍스트가 열려 있어도)
        AccountDto session = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA");
        try (ExecutionContextHolder.Scope scope = ExecutionContextHolder.open(verifier.verify(ExecutionContext.scheduled(project.getId(), "nightly")))) {
            assertThat(registrar(project("ai1a-audit-session", manager))).isEqualTo(session.getName());
        }
    }

    @Test
    @DisplayName("암호화 키 없이도 애플리케이션이 뜨고(이 테스트 컨텍스트), 비밀값 암호화를 쓰는 순간에만 실패한다")
    void startsWithoutSecretKey() {
        assertThat(secretCipher.isAvailable()).isFalse();
        assertThatThrownBy(() -> secretCipher.encrypt("secret", SecretScope.ofProject("source-connection", 1L, 1L, "password")))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
    }

    // ------------------------------------------------------------------ 준비

    private static Consumer<CustomException> code(ErrorCode expected) {
        return e -> assertThat(e.getErrorCode()).isEqualTo(expected);
    }

    private static AccountDto principal(Account account, Project project) {
        return AccountDto.builder().id(String.valueOf(account.getId())).username(account.getUsername()).name(account.getName()).projectId(project.getId()).build();
    }

    private Account admin() {
        return accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username + " 이름").password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(String name, Account manager, Account... members) {
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(manager).dbType(DbType.POSTGRESQL).build());
        for (Account member : members) projectMemberRepository.save(ProjectMember.builder().project(project).account(member).isActive(true).build());
        return project;
    }

    private String registrar(Project project) {
        return jdbc.queryForObject("select RGTR_ID from TB_PRJ where PRJ_SN = ?", String.class, project.getId());
    }
}
