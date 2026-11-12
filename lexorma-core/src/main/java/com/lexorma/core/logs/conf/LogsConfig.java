package com.lexorma.core.logs.conf;

import com.lexorma.core.logs.interceptor.LogsInterceptor;
import com.lexorma.core.logs.repository.LogsRepository;
import com.lexorma.core.logs.service.LogsService;
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
                // 비밀번호 변경(/api/updatePwd)은 빼지 않는다 : 본문 없이 호출 기록만 남긴다 (LogBodyPolicy)
                .excludePathPatterns("/api/**/data", "/api/erd", "/api/erd/**");
    }
}
