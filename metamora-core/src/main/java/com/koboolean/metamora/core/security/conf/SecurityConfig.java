package com.koboolean.metamora.core.security.conf;

import com.koboolean.metamora.core.security.handler.FormAuthenticationFailureHandler;
import com.koboolean.metamora.core.security.handler.FormAuthenticationSuccessHandler;
import com.koboolean.metamora.core.security.handler.JsonResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * 인증 : Spring Security 세션 인증 (세션은 Spring Session JDBC 로 DB 에 저장)
 * <p>
 * 화면은 React(CSR) 이므로 Security 는 리다이렉트 대신 JSON(401/403) 으로 응답한다.
 * <ul>
 *     <li>/api/** 만 인증이 필요하다. 그 외(React index.html, assets, 이미지, 폰트)는 공개 정적 리소스다.</li>
 *     <li>권한은 각 컨트롤러의 @PreAuthorize 로 제어한다. (@PreAuthorize 가 없는 /api 핸들러는 로그인한 모든 사용자에게 열린다)</li>
 *     <li>CSRF 는 유지한다 : 토큰은 GET /api/auth/csrf 로 받아 X-XSRF-TOKEN 헤더로 보낸다. (로그인/로그아웃 포함)</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationProvider authenticationProvider;
    private final AuthenticationDetailsSource<HttpServletRequest, WebAuthenticationDetails> authenticationDetailsSource;
    private final FormAuthenticationSuccessHandler successHandler;
    private final FormAuthenticationFailureHandler failureHandler;
    private final CustomOncePerRequestFilter customOncePerRequestFilter;
    // 웹 서버 없이 띄우는 테스트 컨텍스트(web-application-type=none)에는 세션 저장소가 없다
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 로그인 전 화면에서 호출 (익명 허용, 컨트롤러에서 @PreAuthorize로 제어)
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/auth/csrf", "/api/auth/signup/projects").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/login/projects", "/api/login/password", "/api/auth/signup").permitAll()

                        // 그 외 API 권한은 각 컨트롤러의 @PreAuthorize로 제어
                        .requestMatchers("/api/**").authenticated()

                        // React 화면(index.html / SPA 경로)과 정적 리소스, Swagger·H2 콘솔(로컬 프로필에서만 활성화)
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        // 화면이 없는 JSON 로그인 : POST /api/auth/login (username, password, project_id - form-urlencoded)
                        // loginPage 를 지정해 Security 기본 로그인 화면(GET /login)을 만들지 않는다. (/login 은 React 경로)
                        .loginPage("/login")
                        .loginProcessingUrl("/api/auth/login")
                        .authenticationDetailsSource(authenticationDetailsSource)
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll())
                .csrf(csrf -> csrf
                        // 토큰은 /api/auth/csrf 응답으로 받으므로 쿠키를 스크립트가 읽을 필요가 없다 (HttpOnly)
                        .csrfTokenRepository(new CookieCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                // 로그인 후 이동할 화면은 React 가 정한다. 401 응답마다 요청을 세션에 저장(익명 세션 생성)하지 않는다.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authenticationProvider(authenticationProvider)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED"))
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            if (accessDeniedException instanceof CsrfException) {
                                JsonResponses.error(response, HttpServletResponse.SC_FORBIDDEN, "CSRF_INVALID");
                            } else {
                                JsonResponses.error(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED");
                            }
                        }))
                .sessionManagement(session -> session
                        .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId)
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 계정당 세션 1개 : 새로 로그인하면 먼저 로그인한 세션을 만료시킨다 (나중 로그인 우선)
                        .maximumSessions(1)
                        .maxSessionsPreventsLogin(false)
                        // 동시 세션 제한도 세션 DB 기준 (재시작/여러 인스턴스에서도 같은 기준)
                        .sessionRegistry(sessionRegistry())
                        // 만료된 쪽은 다음 요청에서 안내를 받고 로그아웃된다
                        .expiredSessionStrategy(event -> JsonResponses.error(event.getResponse(), HttpServletResponse.SC_UNAUTHORIZED, "DUPLICATE_LOGIN")))
                .addFilterBefore(customOncePerRequestFilter, UsernamePasswordAuthenticationFilter.class)
                .logout(logout -> logout
                        // POST + CSRF 토큰. 세션을 무효화하면 Spring Session 이 세션 쿠키도 만료시킨다.
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .permitAll())
        ;

        return http.build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        FindByIndexNameSessionRepository<? extends Session> repository = sessionRepository.getIfAvailable();
        return repository != null ? new SpringSessionBackedSessionRegistry<>(repository) : new SessionRegistryImpl();
    }
}
