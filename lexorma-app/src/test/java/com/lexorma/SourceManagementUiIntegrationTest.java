package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.login.controller.AuthRestController;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.source.collection.controller.SourceCollectionRestController;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 DB 관리 화면 (M-4a) 이 보는 권한 값 : /api/auth/me 의 menus(source-*) 와 projectManageable.
 * 화면의 관리 버튼 = menus.source-manage(역할 USER 이상) && projectManageable(그 프로젝트의 관리자 또는 ADMIN) — 서버의 연결·수집 API 와 같은 기준인지 본다.
 * 전역 MANAGER 역할만으로는 다른 사람이 관리하는 프로젝트의 연결을 바꿀 수 없다 (프로젝트 설정은 프로젝트 관리자·ADMIN).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceManagementUiIntegrationTest {

    @Autowired AuthRestController auth;
    @Autowired SourceConnectionRestController connections;
    @Autowired SourceCollectionRestController collections;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("/api/auth/me : 원본 DB 메뉴는 VIEWER 부터, 관리 버튼은 USER 이상 + 프로젝트 관리자·ADMIN. 전역 MANAGER 는 관리 버튼이 꺼지고 API 도 거절한다")
    @SuppressWarnings("unchecked")
    void meSourceFlags() {
        Account owner = account("m4a-owner", "ROLE_USER");
        Account manager = account("m4a-manager", "ROLE_MANAGER");
        Account viewer = account("m4a-viewer", "ROLE_VIEWER");
        Account admin = account("m4a-admin", "ROLE_ADMIN");
        Project project = project("m4a", owner, manager, viewer, admin);

        // 프로젝트 관리자 (역할 USER) : 메뉴·관리 버튼 모두 보이고 연결 등록도 된다
        Map<String, Object> ownerMe = me(project, owner, "ROLE_USER");
        assertThat(ownerMe).containsEntry("projectManageable", true);
        assertThat((Map<String, Boolean>) ownerMe.get("menus")).containsEntry("source-management", true).containsEntry("source-connection", true)
                .containsEntry("source-collection", true).containsEntry("source-catalog", true).containsEntry("source-manage", true);
        SourceConnectionDto.Response created = connections.create(login(project, owner, "ROLE_USER"), request("m4a-conn")).getBody();
        assertThat(created).isNotNull();
        assertThat(created.passwordConfigured()).isFalse();

        // 전역 MANAGER 이지만 이 프로젝트의 관리자가 아니다 : 메뉴의 역할 조건은 맞지만 projectManageable 이 false, 서버도 거절한다
        Map<String, Object> managerMe = me(project, manager, "ROLE_MANAGER");
        assertThat(managerMe).containsEntry("projectManageable", false);
        assertThat((Map<String, Boolean>) managerMe.get("menus")).containsEntry("source-manage", true).containsEntry("source-connection", true);
        AccountDto managerLogin = login(project, manager, "ROLE_MANAGER");
        rejected(() -> connections.create(managerLogin, request("m4a-denied")), ErrorCode.EXECUTION_PERMISSION_DENIED);
        rejected(() -> collections.refresh(managerLogin, created.id(), new SourceCollectionDto.RefreshRequest("FULL", null)), ErrorCode.EXECUTION_PERMISSION_DENIED);
        // 조회는 VIEWER 이상이면 된다
        assertThat(connections.list(managerLogin).getBody()).extracting(SourceConnectionDto.Response::id).containsExactly(created.id());

        // VIEWER : 조회 메뉴만. 관리 버튼(역할)도 projectManageable 도 꺼진다. 변경 API 는 역할에서 막힌다
        Map<String, Object> viewerMe = me(project, viewer, "ROLE_VIEWER");
        assertThat(viewerMe).containsEntry("projectManageable", false);
        assertThat((Map<String, Boolean>) viewerMe.get("menus")).containsEntry("source-connection", true).containsEntry("source-collection", true)
                .containsEntry("source-catalog", true).containsEntry("source-manage", false);
        AccountDto viewerLogin = login(project, viewer, "ROLE_VIEWER");
        assertThat(connections.list(viewerLogin).getBody()).hasSize(1);
        assertThatThrownBy(() -> connections.delete(viewerLogin, created.id())).isInstanceOf(AccessDeniedException.class);

        // ADMIN 멤버 : 관리자가 아니어도 관리할 수 있다
        assertThat(me(project, admin, "ROLE_ADMIN")).containsEntry("projectManageable", true);
    }

    @Test
    @DisplayName("다른 프로젝트의 연결은 목록에 없고, ID 를 알아도 상세·수집 이력·삭제가 되지 않는다")
    void otherProjectHidden() {
        Account owner = account("m4a-a", "ROLE_USER");
        Account other = account("m4a-b", "ROLE_USER");
        Project mine = project("m4a-mine", owner);
        Project theirs = project("m4a-theirs", other);
        Long id = connections.create(login(mine, owner, "ROLE_USER"), request("m4a-private")).getBody().id();

        AccountDto outsider = login(theirs, other, "ROLE_USER");
        assertThat(connections.list(outsider).getBody()).isEmpty();
        rejected(() -> connections.detail(outsider, id), ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        rejected(() -> collections.executions(outsider, id, 0, 10), ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        rejected(() -> connections.delete(outsider, id), ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
    }

    // ------------------------------------------------------------------ 보조

    private Map<String, Object> me(Project project, Account account, String role) {
        return auth.me(login(project, account, role), new MockHttpServletRequest()).getBody();
    }

    /** 실제 아이디로 로그인한다 : /api/auth/me 는 아이디로 프로젝트 접근 권한을 다시 확인한다 */
    private AccountDto login(Project project, Account account, String role) {
        new AuthUtil(projectRepository);
        AccountDto accountDto = AccountDto.builder().id(String.valueOf(account.getId())).username(account.getUsername()).projectId(project.getId()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(accountDto, null, List.of(new SimpleGrantedAuthority(role))));
        return accountDto;
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    /** manager 가 관리자이자 승인된 멤버인 프로젝트 */
    private Project project(String name, Account manager, Account... members) {
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true)
                .account(manager).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(manager).isActive(true).build());
        for (Account member : members) {
            if (!member.getId().equals(manager.getId())) projectMemberRepository.save(ProjectMember.builder().project(project).account(member).isActive(true).build());
        }
        return project;
    }

    /** 비밀번호 없는 PostgreSQL 연결 (접속하지 않는다) */
    private static SourceConnectionDto.Request request(String name) {
        return new SourceConnectionDto.Request(name, "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null, null, null);
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }
}
