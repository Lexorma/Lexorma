package com.lexorma.core.security.provider;

import com.lexorma.core.security.domain.dto.AccountContext;
import com.lexorma.core.security.details.FormWebAuthenticationDetails;
import com.lexorma.core.security.details.RecoveryAuthenticationDetails;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.ProjectAccessException;
import com.lexorma.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component("authenticationProvider")
@RequiredArgsConstructor
public class FormAuthenticationProvider implements AuthenticationProvider {

    /** 복구 세션의 유일한 권한 (역할 계층에 넣지 않는다) */
    public static final String ROLE_RECOVERY = "ROLE_RECOVERY";

    private static final Logger log = LoggerFactory.getLogger(FormAuthenticationProvider.class);

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {

        String loginId = authentication.getName();
        String password = (String) authentication.getCredentials();

        AccountContext accountContext = (AccountContext)userDetailsService.loadUserByUsername(loginId);

        if (!passwordEncoder.matches(password, accountContext.getPassword())) {
            throw new BadCredentialsException("패스워드가 일치하지 않습니다.");
        }

        // 초기 비밀번호 사용 중이면 로그인 차단 (로그인 화면에서 비밀번호 변경 후 로그인)
        if (!accountContext.getAccountDto().isPasswdCheck()) {
            throw new CredentialsExpiredException("초기 비밀번호를 사용 중입니다. 비밀번호를 변경한 뒤 로그인해주세요.");
        }

        // 선택한 프로젝트가 접근 가능한 프로젝트인지 확인 (요청 값을 그대로 신뢰하지 않음)
        FormWebAuthenticationDetails details = (FormWebAuthenticationDetails) authentication.getDetails();
        if (details instanceof RecoveryAuthenticationDetails) {
            return recoveryAuthentication(accountContext);
        }
        Long projectId = details.getProjectId();

        boolean isAccessible = projectId != null && userService.selectProject(loginId).stream()
                .anyMatch(project -> projectId.equals(project.getId()));

        if (!isAccessible) {
            throw new ProjectAccessException("선택한 프로젝트에 접근할 수 없습니다.");
        }

        // Project ID 저장
        AccountDto accountDto = accountContext.getAccountDto();

        accountDto.setProjectId(projectId);

        List<Object> authorities = (ArrayList) accountContext.getAuthorities();

        accountDto.setRoleName(userService.getRoleName(authorities.get(0).toString()));
        accountDto.setPassword(null);

        return new UsernamePasswordAuthenticationToken(accountDto, null, accountContext.getAuthorities());
    }

    /**
     * 복구 로그인 : 활성 프로젝트가 하나도 없을 때 시스템 ADMIN 만, 프로젝트 없이 로그인한다.
     * 세션에는 ADMIN 대신 ROLE_RECOVERY 만 담는다 → 역할 계층에 없는 역할이라 일반 API 의 @PreAuthorize 를 하나도 통과하지 못하고,
     * SecurityConfig 가 /api/recovery/** 외의 API 를 막는다. 일반 세션으로 바꾸려면 로그아웃 후 정상 로그인해야 한다.
     */
    private Authentication recoveryAuthentication(AccountContext accountContext) {
        AccountDto accountDto = accountContext.getAccountDto();

        // 역할 계층(상위 역할)이 아니라 계정에 실제로 부여된 역할로 판단한다
        boolean isAdmin = accountContext.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!isAdmin || !userService.selectAllProjectsIsActive().isEmpty()) {
            log.warn("복구 로그인 거절 : username={}, admin={}", accountDto.getUsername(), isAdmin);
            // 실패 사유(관리자 아님 / 활성 프로젝트 있음)를 구분해 알리지 않는다 → RECOVERY_LOGIN_DENIED
            throw new InsufficientAuthenticationException("복구 로그인을 사용할 수 없습니다.");
        }

        accountDto.setProjectId(null);
        accountDto.setRoleName(userService.getRoleName("ROLE_ADMIN"));
        accountDto.setPassword(null);

        log.warn("복구 로그인 : username={}", accountDto.getUsername());
        return new UsernamePasswordAuthenticationToken(accountDto, null, List.of(new SimpleGrantedAuthority(ROLE_RECOVERY)));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.isAssignableFrom(UsernamePasswordAuthenticationToken.class);
    }
}
