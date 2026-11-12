package com.lexorma.home.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * React Router(CSR) 경로 : 주소를 직접 열거나 새로고침해도 React 의 index.html 을 내려준다.
 * <p>
 * 3단계 깊이까지, 모든 구간에 점(.)이 없는 경로만 대상이다. (파일 경로는 제외 : /excel/template.xlsx 등)
 * API·정적 리소스·도구 경로는 제외한다. (없는 API 는 JSON 404 - GlobalExceptionHandler)
 * 화면 접근 권한은 React 가 /api/auth/me 로 판단하고, 데이터는 각 API 의 @PreAuthorize 로 보호된다.
 */
@Hidden
@RestController
public class SpaForwardController {

    private static final String EXCLUDED = "(?!(?:api|assets|images|jsons|swagger-ui|v3|h2-console|actuator|ws|error)$)";

    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    private static final String FIRST = "{p1:" + EXCLUDED + "[^.]*}";

    @GetMapping({"/", "/" + FIRST, "/" + FIRST + "/{p2:[^.]*}", "/" + FIRST + "/{p2:[^.]*}/{p3:[^.]*}"})
    public ResponseEntity<?> index() {
        if (!INDEX.exists()) {
            // 프론트엔드 빌드 없이 실행한 경우 (예: 테스트, -PskipFrontend) : 개발 중에는 Vite(5173)로 접속한다
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", 404, "code", "FRONTEND_NOT_BUILT",
                            "message", "React 빌드(index.html)가 없습니다. lexorma-web 을 빌드하거나 Vite 개발 서버(http://localhost:5173)로 접속하세요."));
        }

        Resource body = INDEX;
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noCache())
                .body(body);
    }
}
