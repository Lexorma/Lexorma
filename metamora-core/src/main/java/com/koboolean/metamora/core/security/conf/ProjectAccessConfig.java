package com.koboolean.metamora.core.security.conf;

import com.koboolean.metamora.core.project.repository.ProjectRepository;
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
                .excludePathPatterns("/api/auth/**", "/api/login/**")
                .order(-1); // 요청 로그(LogsInterceptor)보다 먼저
    }
}
