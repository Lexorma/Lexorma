package com.koboolean.metamora.core.logs.conf;

import com.koboolean.metamora.core.logs.interceptor.LogsInterceptor;
import com.koboolean.metamora.core.logs.repository.LogsRepository;
import com.koboolean.metamora.core.logs.service.LogsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class LogsConfig implements WebMvcConfigurer {

    private final LogsService logsService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LogsInterceptor(logsService))
                .addPathPatterns("/api/**")
                // ERD : 응답(그래프)·요청(배치 좌표)이 크고 물리 모델을 바꾸지 않는다
                .excludePathPatterns("/api/updatePwd", "/api/**/data", "/api/**/column", "/api/erd", "/api/erd/**");
    }
}
