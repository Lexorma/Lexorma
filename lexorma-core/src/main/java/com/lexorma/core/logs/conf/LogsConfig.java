package com.lexorma.core.logs.conf;

import com.lexorma.core.logs.interceptor.LogsInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 요청 로그 인터셉터와 보관 기간 정리(DEC-14, {@link com.lexorma.core.logs.service.RequestLogRetention})의 스케줄링 */
@Configuration
@EnableScheduling
public class LogsConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 가장 먼저 : 다른 인터셉터(ProjectAccessInterceptor 의 401 등)가 preHandle 에서 끝낸 요청도 핸들러 정보가 남아 로그 대상이 된다
        registry.addInterceptor(new LogsInterceptor())
                .order(Ordered.HIGHEST_PRECEDENCE)
                .addPathPatterns("/api/**")
                // ERD : 응답(그래프)·요청(배치 좌표)이 크고 물리 모델을 바꾸지 않는다
                // 비밀번호 변경(/api/updatePwd)은 빼지 않는다 : 본문 없이 호출 기록만 남긴다 (LogBodyPolicy)
                .excludePathPatterns("/api/**/data", "/api/erd", "/api/erd/**");
    }

    /** 위 제외 패턴과 같은 규칙 : 컨트롤러 전에 거절된 요청(인터셉터를 거치지 않는다)도 같은 경로는 남기지 않는다 (RequestLogRecorder) */
    public static boolean isExcludedPath(String uri) {
        return uri != null && uri.startsWith("/api/") && (uri.endsWith("/data") || uri.equals("/api/erd") || uri.startsWith("/api/erd/"));
    }
}
