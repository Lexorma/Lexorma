package com.koboolean.metamora.home.conf;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * React 빌드 결과(metamora-web/dist → classpath:/static/) 정적 리소스
 * <p>
 * 기본 정적 매핑(/**)은 끈다(spring.web.resources.add-mappings=false). classpath:/static/excel 의 엑셀 템플릿은
 * 다운로드 API(/api/downloadTemplate)로만 내려주고 URL 로 바로 열리지 않게 한다.
 * <p>
 * Spring Security 는 모든 응답에 {@code Cache-Control: no-cache, no-store} 를 붙이지만
 * 애플리케이션이 Cache-Control 을 먼저 지정하면 덮어쓰지 않는다.
 * <ul>
 *     <li>/assets/** : Vite 가 파일명에 해시를 붙이므로 1년 캐시 (immutable) - JS/CSS/폰트</li>
 *     <li>/images/** : 7일 캐시</li>
 *     <li>/jsons/** : 매번 변경 여부 확인 (no-cache → 304)</li>
 *     <li>index.html : SpaForwardController 가 no-cache 로 내려준다 (배포 후 바로 새 assets 를 받도록)</li>
 * </ul>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

        registry.addResourceHandler("/jsons/**")
                .addResourceLocations("classpath:/static/jsons/")
                .setCacheControl(CacheControl.noCache());
    }
}
