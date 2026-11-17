package com.lexorma.ai.mcp.controller;

import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.ai.mcp.service.McpServerService;
import com.lexorma.ai.mcp.service.McpTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

/**
 * 내장 MCP 엔드포인트 (DEC-31) : {@code POST /mcp} Streamable HTTP, JSON 응답만. 세션 로그인과 따로 Bearer 토큰으로만 인증한다 (세션 쿠키를 보지 않는다, CSRF 대상 아님 —
 * SecurityConfig). 운영 설정 lexorma.mcp.enabled 가 꺼져 있으면 모든 메서드가 404 (없는 것처럼).
 * Origin 이 있으면 이 서버와 같은 호스트일 때만 받는다 (DNS rebinding 방지). CORS 헤더를 주지 않는다. 외부에 열 때는 HTTPS(리버스 프록시) 뒤에 둔다.
 */
@RestController
@RequiredArgsConstructor
@ProtectedLogBody
public class McpEndpointController {

    static final int MAX_BODY = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpTokenService tokens;
    private final McpServerService server;

    @RequestMapping(value = McpTokenService.ENDPOINT, method = RequestMethod.POST)
    public ResponseEntity<?> post(HttpServletRequest request) {
        if (!tokens.serverEnabled()) return ResponseEntity.notFound().build();
        if (!sameOrigin(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(McpServerService.error(null, -32000, "Forbidden origin"));
        McpServerService.Caller caller;
        try {
            caller = server.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (McpServerService.Denied e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").contentType(MediaType.APPLICATION_JSON)
                    .body(McpServerService.error(null, -32001, "Unauthorized"));
        } catch (McpServerService.Limited e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "60").contentType(MediaType.APPLICATION_JSON)
                    .body(McpServerService.error(null, -32002, "Too many requests"));
        }
        Map<String, Object> message;
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) return ResponseEntity.status(413).body(McpServerService.error(null, -32600, "Request too large"));
            Object parsed = JSON.readValue(body, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) return ResponseEntity.badRequest().body(McpServerService.error(null, -32600, "Batch requests are not supported"));
            @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
            message = typed;
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.badRequest().body(McpServerService.error(null, -32700, "Parse error"));
        }
        // 응답(id 가 있는 JSON-RPC 응답)을 클라이언트가 보낸 경우 : 받기만 한다
        if (!message.containsKey("method")) return ResponseEntity.accepted().build();
        Map<String, Object> response = server.handle(caller, message);
        if (response == null) return ResponseEntity.accepted().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    /** 서버가 여는 SSE 스트림·세션 종료는 지원하지 않는다 */
    @RequestMapping(value = McpTokenService.ENDPOINT, method = {RequestMethod.GET, RequestMethod.DELETE, RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<Void> other() {
        if (!tokens.serverEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
    }

    /** Origin 이 없으면(브라우저가 아닌 클라이언트) 받는다. 있으면 호스트·포트가 요청의 Host 와 같아야 한다 */
    static boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return true;
        try {
            URI uri = URI.create(origin);
            String host = request.getHeader(HttpHeaders.HOST);
            if (uri.getHost() == null || host == null) return false;
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            String expected = host.contains(":") ? host : host + ":" + ("https".equalsIgnoreCase(request.getScheme()) ? 443 : 80);
            return expected.equalsIgnoreCase(uri.getHost() + ":" + port);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
