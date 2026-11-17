package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.ai.mcp.domain.dto.McpDto;
import com.lexorma.ai.mcp.service.McpTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-31 선택형 내장 MCP (docs/completed/embedded-mcp-dec31.md) : 실제 서버(랜덤 포트)에 JDK HttpClient 로 MCP Streamable HTTP(JSON-RPC) 를 보낸다.
 * 토큰 발급(원문 한 번·해시 저장)·initialize·tools/list·tools/call(공유 도구·MCP 전용 도구)·프로젝트 범위·만료·폐기·프로젝트 설정 끔·발급자 권한 회수 즉시 차단·
 * Origin 검사·CSRF 없이 동작·세션 쿠키로는 인증되지 않음·감사(입력·토큰 원문 없음)·로그에 토큰 원문 없음. 운영 설정이 꺼진 경우는 {@link McpDisabledIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "lexorma.mcp.enabled=true")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class McpEndpointIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired McpTokenService tokens;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("발급 → initialize·tools/list·tools/call (프로젝트 범위, 읽기 전용) → 감사 · 만료·폐기·프로젝트 끔·발급자 권한 회수는 즉시 401 · Origin · 로그에 토큰 없음")
    void endToEnd(CapturedOutput output) throws Exception {
        Env e = env("mcp-a");
        // 프로젝트 MCP 가 꺼져 있으면 토큰이 있어도 401
        McpDto.Issued issued = tokens.issue(e.manager, new McpDto.IssueRequest("claude", LocalDateTime.now().plusDays(30)));
        String token = issued.token();
        assertThat(token).startsWith("lxm_").hasSizeGreaterThan(40);
        assertThat(jdbc.queryForObject("select HASH_VL from TB_MCP_TKN where MCP_TKN_SN = ?", String.class, issued.info().id())).doesNotContain(token).hasSize(64);
        assertThat(tokens.settings(e.manager).tokens()).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo("ACTIVE");
            assertThat(JSON.writeValueAsString(t)).doesNotContain(token);
        });
        assertThat(post(token, rpc(1, "initialize", Map.of("protocolVersion", "2025-06-18"))).statusCode()).isEqualTo(401);
        tokens.setEnabled(e.manager, new McpDto.SettingRequest(true));

        // initialize · 알림 · tools/list
        HttpResponse<String> init = post(token, rpc(1, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1"))));
        assertThat(init.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> initResult = (Map<String, Object>) body(init).get("result");
        assertThat(initResult).containsEntry("protocolVersion", "2025-06-18").containsKey("serverInfo");
        assertThat(post(token, Map.of("jsonrpc", "2.0", "method", "notifications/initialized")).statusCode()).isEqualTo(202);
        Map<String, Object> list = body(post(token, rpc(2, "tools/list", Map.of())));
        @SuppressWarnings("unchecked") List<Map<String, Object>> tools = (List<Map<String, Object>>) ((Map<String, Object>) list.get("result")).get("tools");
        assertThat(tools).extracting(t -> t.get("name")).containsExactlyInAnyOrder("listSchemas", "searchTables", "getTableDetail", "getTableColumns", "getTableRelations",
                "searchStandardTerms", "searchStandardWords", "searchStandardDomains", "getProjectInfo", "getLogicalModel");

        // tools/call : 이 프로젝트의 TABLE 만 (다른 프로젝트의 같은 이름은 보이지 않는다)
        String result = text(post(token, rpc(3, "tools/call", Map.of("name", "searchTables", "arguments", Map.of("keyword", "MCP_ONLY")))));
        assertThat(result).contains("TB_MCP_ONLY_A").doesNotContain("TB_MCP_ONLY_B");
        // 검색 방식 (7차) : 기존 필드 그대로 + 일치 방식, 도구 설명에 근사 결과 안내
        assertThat(result).contains("\"keyword\":\"MCP_ONLY\"", "\"truncated\":", "\"matchMode\":\"PARTIAL\"", "\"approximate\":false", "\"resultCount\":1");
        assertThat(text(post(token, rpc(31, "tools/call", Map.of("name", "searchTables", "arguments", Map.of("keyword", "MCP 없는단어"))))))
                .contains("\"matchMode\":\"TOKEN_FALLBACK\"", "\"approximate\":true", "TB_MCP_ONLY_A").doesNotContain("TB_MCP_ONLY_B");
        assertThat(tools.stream().filter(t -> "searchTables".equals(t.get("name"))).findFirst().orElseThrow().get("description").toString())
                .contains("TOKEN_FALLBACK", "approximate");
        assertThat(text(post(token, rpc(4, "tools/call", Map.of("name", "getProjectInfo", "arguments", Map.of()))))).contains(e.project.getProjectName());
        assertThat(text(post(token, rpc(5, "tools/call", Map.of("name", "getLogicalModel", "arguments", Map.of()))))).contains("\"models\"");
        // 모르는 도구·메서드 : 실행하지 않는다
        assertThat(body(post(token, rpc(6, "tools/call", Map.of("name", "executeSql", "arguments", Map.of("sql", "drop table x")))))).containsKey("error");
        assertThat(body(post(token, rpc(7, "resources/list", Map.of())))).containsKey("error");
        // 감사 : 도구·결과만 (입력값 없음)
        assertThat(jdbc.queryForList("select TOOL_NM || ':' || RSLT_CD from TB_MCP_CALL_HSTRY where MCP_TKN_SN = ? order by MCP_CALL_HSTRY_SN", String.class, issued.info().id()))
                .contains("searchTables:OK", "getProjectInfo:OK", "getLogicalModel:OK", "unknown:ERROR");
        assertThat(jdbc.queryForObject("select count(*) from TB_MCP_CALL_HSTRY where TOOL_NM like '%drop%' or TOOL_NM like '%MCP_ONLY%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select LAST_USE_DT from TB_MCP_TKN where MCP_TKN_SN = ?", LocalDateTime.class, issued.info().id())).isNotNull();

        // 다른 Origin (DNS rebinding) 은 403, 토큰 없음·이상한 토큰은 401, 세션 쿠키로는 인증되지 않는다 (CSRF 없이도 토큰이면 된다)
        assertThat(http.send(HttpRequest.newBuilder(uri()).header("Authorization", "Bearer " + token).header("Origin", "http://evil.example")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(rpc(8, "ping", Map.of())))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(post(null, rpc(9, "ping", Map.of())).statusCode()).isEqualTo(401);
        assertThat(post("lxm_wrong", rpc(9, "ping", Map.of())).statusCode()).isEqualTo(401);
        assertThat(post(token, rpc(9, "ping", Map.of())).statusCode()).isEqualTo(200);
        assertThat(http.send(HttpRequest.newBuilder(uri()).GET().header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);

        // 발급자의 권한 회수 : 멤버십을 끄면 바로 401
        jdbc.update("update TB_PRJ_MBR set ACTVTN_YN = false where PRJ_SN = ? and ACNT_SN = ?", e.project.getId(), e.owner.getId());
        assertThat(post(token, rpc(10, "ping", Map.of())).statusCode()).isEqualTo(401);
        jdbc.update("update TB_PRJ_MBR set ACTVTN_YN = true where PRJ_SN = ? and ACNT_SN = ?", e.project.getId(), e.owner.getId());
        assertThat(post(token, rpc(11, "ping", Map.of())).statusCode()).isEqualTo(200);
        // 프로젝트 MCP 끔 → 401, 다시 켬
        tokens.setEnabled(e.manager, new McpDto.SettingRequest(false));
        assertThat(post(token, rpc(12, "ping", Map.of())).statusCode()).isEqualTo(401);
        tokens.setEnabled(e.manager, new McpDto.SettingRequest(true));
        // 만료 → 401
        jdbc.update("update TB_MCP_TKN set EXPRY_DT = ? where MCP_TKN_SN = ?", LocalDateTime.now().minusMinutes(1), issued.info().id());
        assertThat(post(token, rpc(13, "ping", Map.of())).statusCode()).isEqualTo(401);
        jdbc.update("update TB_MCP_TKN set EXPRY_DT = ? where MCP_TKN_SN = ?", LocalDateTime.now().plusDays(1), issued.info().id());
        // 폐기 → 401 (되돌릴 수 없다)
        tokens.revoke(e.manager, issued.info().id());
        assertThat(post(token, rpc(14, "ping", Map.of())).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForList("select TOOL_NM from TB_MCP_CALL_HSTRY where MCP_TKN_SN = ? and RSLT_CD = 'DENIED'", String.class, issued.info().id()))
                .contains("auth:ISSUER_NOT_MANAGER", "auth:PROJECT_DISABLED", "auth:EXPIRED", "auth:REVOKED");
        // MCP 는 /api 밖이라 요청 로그(TB_LOG)가 아니라 자기 호출 이력에만 남는다 (7차 : 401 이 다른 상태로 섞여 남지 않는다)
        assertThat(jdbc.queryForObject("select count(*) from TB_LOG where DMND_URL_ADDR like '/mcp%'", Integer.class)).isZero();

        // 발급·설정은 관리자만, 만료는 필수·최대 365일
        AccountDto member = member(e);
        assertThatThrownBy(() -> tokens.issue(member, new McpDto.IssueRequest("x", LocalDateTime.now().plusDays(1)))).isInstanceOf(CustomException.class);
        assertThat(tokens.settings(member).tokens()).isEmpty();
        assertThatThrownBy(() -> tokens.issue(e.manager, new McpDto.IssueRequest("x", null))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> tokens.issue(e.manager, new McpDto.IssueRequest("x", LocalDateTime.now().plusDays(400)))).isInstanceOf(CustomException.class);

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(token.substring(12));
    }

    // ------------------------------------------------------------------

    record Env(Project project, Account owner, AccountDto manager) {
    }

    private Env env(String name) {
        Account owner = account("mcp-own", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        Long other = projectRepository.save(Project.builder().projectName(name + "-other-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build()).getId();
        // 두 프로젝트에 이름이 비슷한 TABLE 하나씩 : 토큰의 프로젝트 것만 보여야 한다
        for (Object[] row : new Object[][]{{project.getId(), "TB_MCP_ONLY_A"}, {other, "TB_MCP_ONLY_B"}}) {
            Long id = (Long) row[0];
            jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'public', true)", 980_000_000L + id, id);
            jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, TBL_KORN_NM, MSTR_TBL_YN, APRV_YN, SORT_ORD) values (?, ?, ?, ?, '테스트', false, false, 1)",
                    980_000_000L + id, id, 980_000_000L + id, row[1]);
        }
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        return new Env(project, owner, manager);
    }

    private AccountDto member(Env e) {
        Account user = account("mcp-usr", "ROLE_USER");
        tx.executeWithoutResult(s -> projectMemberRepository.save(ProjectMember.builder().project(projectRepository.findById(e.project.getId()).orElseThrow()).account(user)
                .isActive(true).build()));
        return SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(user.getId()), "ROLE_USER");
    }

    private Account account(String prefix, String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username(prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).name(prefix).password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private URI uri() {
        return URI.create("http://localhost:" + port + "/mcp");
    }

    private HttpResponse<String> post(String token, Map<String, Object> message) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri()).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> rpc(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static String text(HttpResponse<String> response) {
        Map<String, Object> result = (Map<String, Object>) body(response).get("result");
        assertThat(result).as(response.body()).isNotNull().containsEntry("isError", false);
        return (String) ((List<Map<String, Object>>) result.get("content")).getFirst().get("text");
    }
}
