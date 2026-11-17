package com.lexorma;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** DEC-31 : 운영 설정 lexorma.mcp.enabled 의 기본값은 꺼짐이고, 꺼져 있으면 /mcp 는 어떤 메서드로도 없는 것처럼 404 다 (SPA 화면으로 넘기지도 않는다) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpDisabledIntegrationTest {

    @LocalServerPort int port;

    @Test
    @DisplayName("기본값 꺼짐 : POST·GET /mcp 모두 404, 토큰이 있어도 같다")
    void disabledByDefault() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + "/mcp");
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json").header("Authorization", "Bearer lxm_x")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(404);
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).doesNotContain("<div id=\"root\"");
    }
}
