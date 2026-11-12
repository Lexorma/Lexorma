package com.lexorma.core.security.conf;

import com.lexorma.core.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class ProjectAccessConfig implements WebMvcConfigurer {

    private final ProjectRepository projectRepository;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 로그인 상태 조회(/api/auth/**)와 로그인 전 API(/api/login/**)는 제외 : me 가 401 대신 로그인 화면 판단에 쓰인다
        registry.addInterceptor(new ProjectAccessInterceptor(projectRepository))
                .addPathPatterns("/api/**")
                // 복구 세션(/api/recovery/**)은 프로젝트가 없다 : SecurityConfig 가 복구 세션만 들어오게 막는다
                .excludePathPatterns("/api/auth/**", "/api/login/**", "/api/recovery/**")
                .order(-1); // 요청 로그(LogsInterceptor)보다 먼저
    }
}
