package com.lexorma;

import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.mcp.domain.dto.McpDto;
import com.lexorma.ai.mcp.service.McpTokenService;
import com.lexorma.ai.tool.LogicalMetadataDto;
import com.lexorma.ai.tool.LogicalMetadataService;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-35 D35-3b : 내장 MCP 의 논리 모델 Tool — 실제 서버(랜덤 포트)에 JSON-RPC(tools/list·tools/call) 를 보낸다. 공유 Tool 3개 추가(기존 목록 유지)·입력 스키마,
 * 내부 AI 와 같은 결과(공통 서비스), 로그인 세션 없는 상속 조회, getLogicalModel 호환 보완(기존 칸 그대로 + 직접 속성 표시·상속 여부·직접 슈퍼타입·안내),
 * 결과 상한(공유 Tool 의 안내·getLogicalModel 의 대체 Tool 안내), 잘못된 입력·다른 프로젝트, 감사, 토큰 폐기·MCP 끔. 크기 실측(HTTP 본문·안쪽 결과 문자열)을 남긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "lexorma.mcp.enabled=true")
@ActiveProfiles("test")
class McpLogicalToolIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> lines = new ArrayList<>();

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired McpTokenService tokens;
    @Autowired LogicalMetadataService metadata;
    @Autowired LogicalModelService logical;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        lines.forEach(System.out::println);
        SecurityTestSupport.clear();
    }

    private HttpResponse<String> post(String token, Map<String, Object> message) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp")).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> rpc(int id, String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        return message;
    }

    private record Call(HttpResponse<String> response, JsonNode result, String text, boolean error) {
    }

    private Call call(String token, int id, String tool, Map<String, Object> arguments) throws Exception {
        HttpResponse<String> response = post(token, rpc(id, "tools/call", Map.of("name", tool, "arguments", arguments)));
        JsonNode result = JSON.readTree(response.body()).path("result");
        String text = result.path("content").path(0).path("text").asString();
        return new Call(response, result, text, result.path("isError").asBoolean());
    }

    private void measure(String label, Call c) {
        lines.add("[D35-3 측정] MCP " + label + " | 안쪽 결과 chars=" + c.text().length() + " | HTTP 본문 chars=" + c.response().body().length()
                + " | HTTP 본문 utf8Bytes=" + c.response().body().getBytes(StandardCharsets.UTF_8).length + " | 안쪽 estTokens=" + TokenEstimator.estimate(c.text()));
    }

    @Test
    @DisplayName("MCP 논리 Tool : 목록 10 → 13, 같은 구현 결과, 세션 없는 상속 조회, getLogicalModel 호환 보완, 상한·안내, 잘못된 입력·격리, 감사, 폐기·끔")
    void logicalTools() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-mcp", true);
        LogicalD35Fixture other = LogicalD35Fixture.create(context, "d35-mcp-other", false);
        // getLogicalModel 상한 초과용 : 설명 1,000자 엔터티 30개 (getLogicalModel 은 엔터티 설명을 자르지 않는다 — 기존 계약)
        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), x.project(), x.f.user.getId(), "ROLE_ADMIN");
        for (int i = 0; i < 30; i++) logical.saveEntity(x.f.user, new LogicalModelDto.Entity(null, x.f.modelId, "긴설명" + i, "가".repeat(1000), null, null, null));
        McpDto.Issued issued = tokens.issue(x.f.user, new McpDto.IssueRequest("d35", LocalDateTime.now().plusDays(1)));
        tokens.setEnabled(x.f.user, new McpDto.SettingRequest(true));
        String token = issued.token();
        SecurityTestSupport.clear(); // MCP 호출은 로그인 세션 없이 토큰만으로

        // tools/list : 기존 10개 그대로 + 논리 3개
        HttpResponse<String> listResponse = post(token, rpc(1, "tools/list", Map.of()));
        JsonNode tools = JSON.readTree(listResponse.body()).path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).containsExactlyInAnyOrder("listSchemas", "searchTables", "getTableDetail", "getTableColumns", "getTableRelations", "searchStandardTerms",
                "searchStandardWords", "searchStandardDomains", "getProjectInfo", "getLogicalModel", "getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail");
        int newTools = 0;
        for (JsonNode t : tools) {
            if (t.path("name").asString().contains("Logical")) {
                newTools += JSON.writeValueAsString(t).length();
                assertThat(t.path("inputSchema").path("properties").has("projectId")).isFalse();
            }
        }
        lines.add("[D35-3 측정] MCP tools/list 본문 chars=" + listResponse.body().length() + " (D35-1 기준 10개 6,692) | 논리 3개 도구 정의 chars=" + newTools
                + " | estTokens=" + TokenEstimator.estimate(listResponse.body()));
        assertThat(tools.findValues("description").stream().filter(d -> d.asString().contains("getLogicalEntityDetail for effective attributes")).count()).isOne();

        // 같은 구현 : MCP 결과 = 공통 서비스 결과 (내부 AI 도 같은 서비스 — LogicalMetadataToolIntegrationTest)
        Call detail = call(token, 2, "getLogicalEntityDetail", Map.of("entityId", x.person));
        assertThat(detail.error()).isFalse();
        assertThat(JSON.readTree(detail.text())).isEqualTo(JSON.readTree(JSON.writeValueAsString(metadata.entityDetail(x.project(),
                new LogicalMetadataDto.EntityRef(x.person, null, null, null)))));
        assertThat(JSON.readTree(detail.text()).path("attributes")).hasSize(6); // 상속 속성 5 + 직접 1 (로그인 세션 없이)
        measure("상세 기본 (3단계 서브타입)", detail);
        Call bigDetail = call(token, 3, "getLogicalEntityDetail", Map.of("entityId", x.bigLeaf));
        assertThat(bigDetail.error()).isFalse();
        measure("상세 기본 (대형 말단, 속성 100 표시)", bigDetail);
        Call overview = call(token, 4, "getLogicalModelOverview", Map.of("modelId", x.f.modelId));
        measure("모델 개요", overview);
        Call search = call(token, 5, "searchLogicalEntities", Map.of("modelId", x.f.modelId, "limit", 20));
        assertThat(search.error()).isFalse();
        measure("엔터티 목록 20건", search);
        Call attributes = call(token, 6, "getLogicalEntityDetail", Map.of("entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "limit", 20));
        assertThat(attributes.error()).isFalse();
        measure("ATTRIBUTES + 설명 limit 20", attributes);

        // 결과 상한 : 공유 Tool 은 실행 경계의 오류(안내 포함), getLogicalModel 은 대체 Tool 안내 — 서버가 다른 Tool 을 대신 부르지 않는다
        Call tooLarge = call(token, 7, "getLogicalEntityDetail", Map.of("entityId", x.bigLeaf, "includeAttributeDescriptions", true));
        assertThat(tooLarge.error()).isTrue();
        assertThat(tooLarge.text()).contains("RESULT_TOO_LARGE", "\"hint\"", "ATTRIBUTES");
        measure("RESULT_TOO_LARGE (설명 포함 ALL)", tooLarge);
        Call wholeModel = call(token, 8, "getLogicalModel", Map.of("modelName", jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, x.f.modelId)));
        assertThat(wholeModel.error()).isTrue();
        assertThat(wholeModel.text()).contains("RESULT_TOO_LARGE", "getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail", "alternatives");
        measure("getLogicalModel RESULT_TOO_LARGE", wholeModel);

        // getLogicalModel 호환 보완 (상한 안의 모델) : 기존 칸 그대로 + 직접 속성 표시·상속 여부·직접 슈퍼타입·단계·안내
        String otherName = jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, other.f.modelId);
        Call ownModelList = call(token, 9, "getLogicalModel", Map.of());
        assertThat(JSON.readTree(ownModelList.text()).path("models").get(0).has("entities")).isTrue();
        assertThat(ownModelList.text()).doesNotContain(otherName);
        assertThat(call(token, 10, "getLogicalModel", Map.of("modelName", otherName)).text()).contains("NOT_FOUND");
        // 같은 프로젝트에 상한 안의 모델을 하나 더 만들어 호환 칸을 본다 (토큰 프로젝트 = x)
        LogicalD35Fixture small = x;
        long modelId = jdbc.queryForObject("select count(*) from TB_LGCL_MDL where PRJ_SN = ?", Long.class, small.project());
        assertThat(modelId).isOne();
        jdbc.update("update TB_LGCL_ENTT set ENTT_EXPLN = null where LGCL_MDL_SN = ? and ENTT_NM like '긴설명%'", small.f.modelId);
        Call compat = call(token, 11, "getLogicalModel", Map.of("modelName", jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class,
                small.f.modelId)));
        assertThat(compat.error()).as(compat.text().length() + "자").isFalse();
        measure("getLogicalModel 상세 (호환 보완)", compat);
        JsonNode model = JSON.readTree(compat.text());
        assertThat(model.path("attributeScope").asString()).isEqualTo("DIRECT");
        assertThat(model.path("hasInheritance").asBoolean()).isTrue();
        assertThat(model.path("note").asString()).contains("defines directly", "getLogicalEntityDetail");
        JsonNode person = null, order = null;
        for (JsonNode e : model.path("entities")) {
            if (e.path("entityName").asString().equals("개인고객")) person = e;
            if (e.path("entityName").asString().equals("주문")) order = e;
        }
        assertThat(person).isNotNull();
        // 기존 칸 그대로 : 직접 정의 속성만, 자체 식별자만 (상속 주 식별자를 직접 식별자로 넣지 않는다)
        List<String> personKeys = new ArrayList<>();
        person.propertyNames().forEach(personKeys::add);
        assertThat(personKeys).contains("entityName", "description", "approved", "attributes", "identifiers", "supertype", "inheritanceLevel");
        assertThat(person.path("attributes")).hasSize(1);
        assertThat(person.path("attributes").get(0).path("attributeName").asString()).isEqualTo("생년월일");
        assertThat(person.path("identifiers")).isEmpty();
        assertThat(person.path("supertype").asString()).isEqualTo("고객");
        assertThat(person.path("inheritanceLevel").asInt()).isEqualTo(3);
        assertThat(order.has("supertype")).isFalse();
        assertThat(order.has("inheritanceLevel")).isFalse();
        assertThat(model.path("relationships").get(0).has("parent")).isTrue();

        // 잘못된 입력·다른 프로젝트 : 오류 결과 (내용을 드러내지 않는다)
        assertThat(call(token, 12, "getLogicalEntityDetail", Map.of("entityId", x.person, "section", "COLUMNS")).text()).contains("INVALID_ARGUMENT");
        assertThat(call(token, 13, "getLogicalEntityDetail", Map.of("entityId", other.person)).text()).contains("NOT_FOUND").doesNotContain("개인고객");
        assertThat(call(token, 14, "searchLogicalEntities", Map.of("modelId", other.f.modelId)).text()).contains("NOT_FOUND");

        // 감사 : 도구·결과만
        assertThat(jdbc.queryForList("select TOOL_NM || ':' || RSLT_CD from TB_MCP_CALL_HSTRY where MCP_TKN_SN = ? order by MCP_CALL_HSTRY_SN", String.class, issued.info().id()))
                .contains("getLogicalEntityDetail:OK", "getLogicalModelOverview:OK", "searchLogicalEntities:OK", "getLogicalEntityDetail:ERROR", "getLogicalModel:ERROR",
                        "getLogicalModel:OK", "searchLogicalEntities:ERROR");
        assertThat(jdbc.queryForObject("select count(*) from TB_MCP_CALL_HSTRY where MCP_TKN_SN = ? and (TOOL_NM like '%COLUMNS%' or TOOL_NM like '%entityId%')", Integer.class,
                issued.info().id())).isZero();

        // 프로젝트 MCP 끔·토큰 폐기 → 401 (논리 Tool 도 같은 인증)
        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), x.project(), x.f.user.getId(), "ROLE_ADMIN");
        tokens.setEnabled(x.f.user, new McpDto.SettingRequest(false));
        assertThat(post(token, rpc(15, "tools/call", Map.of("name", "getLogicalEntityDetail", "arguments", Map.of("entityId", x.person)))).statusCode()).isEqualTo(401);
        tokens.setEnabled(x.f.user, new McpDto.SettingRequest(true));
        assertThat(call(token, 16, "getLogicalEntityDetail", Map.of("entityId", x.person)).error()).isFalse();
        tokens.revoke(x.f.user, issued.info().id());
        assertThat(post(token, rpc(17, "tools/call", Map.of("name", "getLogicalEntityDetail", "arguments", Map.of("entityId", x.person)))).statusCode()).isEqualTo(401);
    }
}
