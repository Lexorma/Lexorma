package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.DefaultProviderRequest;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.DefaultStatus;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProjectProviderRequest;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.ai.provider.controller.ProjectAiProviderRestController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로젝트별 AI Provider 지정 (DEC-18) : 지정이 없으면 시스템 기본, 지정이 있으면 그 Provider·모델, 지정을 쓸 수 없으면 거절(시스템 기본으로 대신하지 않음),
 * 다른 프로젝트에 영향 없음, 지정된 Provider 삭제 거부, 권한(프로젝트 관리자·ADMIN만 지정, 다른 프로젝트 조회 거부), 해제하면 시스템 기본.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ProjectAiProviderIntegrationTest {

    @Autowired AiDefaultProviderService defaults;
    @Autowired AiProviderService providers;
    @Autowired ProjectAiProviderRestController api;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;

    private final List<Long> createdProviders = new ArrayList<>();
    private final List<Long> projects = new ArrayList<>();

    @BeforeEach
    void setUp() {
        defaults.clear();
    }

    @AfterEach
    void tearDown() {
        projects.forEach(defaults::clearProject);
        defaults.clear();
        for (Long id : createdProviders) {
            try {
                providers.delete(id);
            } catch (CustomException ignored) {
                // 이미 지웠다
            }
        }
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("지정 없음 → 시스템 기본 / 지정 → 그 Provider·모델 / 다른 프로젝트는 그대로 / 지정 Provider 비활성 → 거절(대신 부르지 않음) / 해제 → 시스템 기본")
    void resolution() {
        Account owner = account("ROLE_USER");
        Project mine = project(owner);
        Project other = project(owner);
        AiProviderDto.Provider system = create("system", "gemma3:4b");
        AiProviderDto.Provider project = create("project", "qwen3:8b");
        defaults.set(new DefaultProviderRequest(system.id()));

        assertThat(defaults.resolve(mine.getId()).providerId()).isEqualTo(system.id());
        AccountDto user = login(owner, mine);
        AiProviderDto.ProjectProvider set = api.set(mine.getId(), new ProjectProviderRequest(project.id(), "qwen3:14b"), user).getBody();
        assertThat(set.scope()).isEqualTo("PROJECT");
        assertThat(set.model()).isEqualTo("qwen3:14b");
        assertThat(set.choices()).extracting(AiProviderDto.ProviderChoice::id).contains(system.id(), project.id());
        assertThat(defaults.resolve(mine.getId())).satisfies(t -> {
            assertThat(t.providerId()).isEqualTo(project.id());
            assertThat(t.model()).isEqualTo("qwen3:14b");
        });
        assertThat(defaults.resolve(other.getId()).providerId()).as("다른 프로젝트는 시스템 기본").isEqualTo(system.id());
        assertThat(defaults.status(mine.getId())).isEqualTo(DefaultStatus.READY);

        // 지정한 Provider 를 비활성화하면 그 프로젝트의 AI 는 거절된다 — 시스템 기본으로 바꾸지 않는다
        providers.update(project.id(), new ProviderRequest(project.name(), "OLLAMA", project.baseUrl(), project.defaultModel(), false, null));
        assertThatThrownBy(() -> defaults.resolve(mine.getId())).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
        assertThat(defaults.status(mine.getId())).isEqualTo(DefaultStatus.DISABLED);
        // 지정된 Provider 는 지울 수 없다
        assertThatThrownBy(() -> providers.delete(project.id())).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_IN_USE);

        AiProviderDto.ProjectProvider cleared = api.clear(mine.getId(), user).getBody();
        assertThat(cleared.scope()).isEqualTo("SYSTEM");
        assertThat(defaults.resolve(mine.getId()).providerId()).isEqualTo(system.id());
    }

    @Test
    @DisplayName("권한 : 관리자가 아닌 USER 는 지정 불가(403), 다른 프로젝트 조회 불가, VIEWER 는 API 권한 없음 · 비활성·형식 오류 모델 거절")
    void permissions() {
        Account owner = account("ROLE_USER");
        Account member = account("ROLE_USER");
        Project mine = project(owner);
        Project foreign = project(account("ROLE_USER"));
        projectMemberRepository.save(ProjectMember.builder().project(mine).account(member).isActive(true).build());
        AiProviderDto.Provider provider = create("perm", "gemma3:4b");
        AiProviderDto.Provider disabled = providers.create(new ProviderRequest("off " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", "http://127.0.0.1:1", "m", false, null));
        createdProviders.add(disabled.id());

        AccountDto memberLogin = login(member, mine);
        assertDenied(() -> api.set(mine.getId(), new ProjectProviderRequest(provider.id(), null), memberLogin));
        assertThat(api.get(mine.getId(), memberLogin).getBody().canManage()).isFalse();
        assertThat(api.get(mine.getId(), memberLogin).getBody().choices()).as("관리할 수 없으면 목록을 주지 않는다").isEmpty();
        assertDenied(() -> api.get(foreign.getId(), memberLogin));

        AccountDto ownerLogin = login(owner, mine);
        assertThatThrownBy(() -> api.set(mine.getId(), new ProjectProviderRequest(disabled.id(), null), ownerLogin))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_DISABLED);
        assertThatThrownBy(() -> api.set(mine.getId(), new ProjectProviderRequest(provider.id(), "bad model id!"), ownerLogin))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_INVALID);
        assertDenied(() -> api.set(foreign.getId(), new ProjectProviderRequest(provider.id(), null), ownerLogin));

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, mine.getId(), String.valueOf(account("ROLE_VIEWER").getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> api.set(mine.getId(), new ProjectProviderRequest(provider.id(), null), viewer)).isInstanceOf(AccessDeniedException.class);
    }

    private void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    private AiProviderDto.Provider create(String name, String model) {
        AiProviderDto.Provider provider = providers.create(new ProviderRequest(name + " " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", "http://127.0.0.1:11434", model, true, null));
        createdProviders.add(provider.id());
        return provider;
    }

    private AccountDto login(Account account, Project project) {
        String role = account.getUserRoles().iterator().next().getRoleName();
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(account.getId()), role);
    }

    private Account account(String role) {
        String username = "pai-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(Account owner) {
        Project project = projectRepository.save(Project.builder().projectName("pai-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        projects.add(project.getId());
        return project;
    }
}
