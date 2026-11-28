package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.ai.mcp.service.McpServerService;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.ai.service.AiMetadataQueryService;
import com.lexorma.ai.tool.MetadataToolset;
import com.lexorma.ai.tool.QualityToolDefinitions;
import com.lexorma.ai.tool.ToolScope;
import com.lexorma.common.exception.CustomException;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.service.InspectionQueryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-7 입력 예산 측정 (opt-in : {@code LEXORMA_AI7_MEASURE=true}, 일반 회귀에서는 돌지 않는다 — 범위별 필수 회귀는 QualityToolIntegrationTest·기존 AI 테스트).
 * 실제 운영 구성(METADATA 13 · QUALITY 9 · MCP 16)의 Spring AI Tool 정의와, 가짜 Ollama 가 받은 실제 요청 본문(실제 Tool 결과 포함)으로 요청별 추정 입력을 잰다.
 * 추정 토큰은 {@code TokenMeter} 의 보내기 전 판정 규칙({@link TokenEstimator}, 메시지·정의마다 8) — 실제 사용 토큰이 아니다.
 * 결과는 {@code build/ai7-measure/quality-tool-budget.txt} 와 표준 출력. 2차-A 의 '16개 상시 노출' 측정값은 설계 문서 21장에 보존한다 (그 구성은 운영 코드에 없다)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_AI7_MEASURE", matches = "true", disabledReason = "AI-7 예산 측정 : LEXORMA_AI7_MEASURE=true 일 때만")
class QualityToolBudgetMeasurementTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int PER = 8;
    private static final StringBuilder REPORT = new StringBuilder();
    private static QualityToolFixture.Data data;

    @Autowired ApplicationContext beans;
    @Autowired MetadataToolset toolset;
    @Autowired McpServerService mcp;
    @Autowired AiMetadataQueryService queries;
    @Autowired InspectionQueryService inspections;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        defaults.clear();
        SecurityTestSupport.clear();
        if (data == null) data = new QualityToolFixture(beans).create("ai7-measure");
    }

    @AfterEach
    void tearDown() throws Exception {
        defaults.clear();
        for (Long id : createdProviders) providers.delete(id);
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
    }

    @AfterAll
    static void write() throws IOException {
        if (REPORT.isEmpty()) return;
        Path out = Path.of("build", "ai7-measure", "quality-tool-budget.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, REPORT.toString(), StandardCharsets.UTF_8);
        System.out.println(REPORT);
    }

    @Test
    void definitionSizes() {
        line("== 1. 범위별 Tool 정의 (실제 Spring AI ToolDefinition) ==");
        Totals metadata = definitions("METADATA", toolset.callbacks(ToolScope.METADATA));
        Totals quality = definitions("QUALITY", toolset.callbacks(ToolScope.QUALITY));
        line("METADATA %d개 : %,d자 · 추정 %,d 토큰", toolset.callbacks(ToolScope.METADATA).size(), metadata.chars(), metadata.tokens());
        line("QUALITY %d개 : %,d자 · 추정 %,d 토큰 (METADATA 대비 %+,d)", toolset.callbacks(ToolScope.QUALITY).size(), quality.chars(), quality.tokens(),
                quality.tokens() - metadata.tokens());
        int rule = TokenEstimator.estimate("\n" + QualityToolDefinitions.PROMPT_RULE);
        line("규칙 9 : %,d자 · 추정 %,d 토큰 → QUALITY 고정 부분(정의+규칙) METADATA 대비 %+,d", QualityToolDefinitions.PROMPT_RULE.length() + 1, rule,
                quality.tokens() + rule - metadata.tokens());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) ReflectionTestUtils.invokeMethod(mcp, "tools");
        assertThat(listed).hasSize(16);
        int qualityMcp = 0;
        for (Map<String, Object> t : listed) if (QualityToolDefinitions.NAMES.contains((String) t.get("name"))) qualityMcp += JSON.writeValueAsString(t).length() + 1;
        line("MCP tools/list : %d개 · %,d자 (품질 3개 항목 %,d자) — MCP 클라이언트 쪽, 내부 AI 예산과 무관", listed.size(), JSON.writeValueAsString(listed).length(), qualityMcp);
    }

    @Test
    void firstRequests() throws Exception {
        line("== 2. 첫 요청 추정 입력 (모델이 바로 답함, 기본 컨텍스트 16,384 · 상한 12,288) ==");
        String[] questions = {"회원 정보를 담는 테이블의 컬럼을 알려줘", "Which tables store accounts and their roles?", "현재 프로젝트의 품질 진단 상태를 알려줘",
                "컬럼명 불일치(COLUMN_NAME_MISMATCH) 오류가 왜 생겼고 어떻게 고쳐야 해?"};
        for (ToolScope scope : ToolScope.values()) {
            for (String question : questions) {
                ScriptedOllama ollama = server(null);
                ollama.then(MetadataToolIntegrationTest.answer("llama3.2", "답"));
                AiMetadataQueryDto.QueryResult result = queries.query(data.viewerContext(), new AiMetadataQueryDto.QueryRequest(question, scope));
                JsonNode tree = JSON.readTree(ollama.chats.getFirst());
                assertThat(estimate(tree, toolset.callbacks(scope))).as("요청 본문 재계산 = 서버 추정").isEqualTo(result.usage().estimatedInputTokens());
                line("%s | %s | 추정 %,d | 남음 %,d | 요청 본문 %,d자 | tools JSON %,d자 (%d개)", scope, question, result.usage().estimatedInputTokens(),
                        12_288 - result.usage().estimatedInputTokens(), ollama.chats.getFirst().length(), tree.path("tools").toString().length(), tree.path("tools").size());
            }
        }
    }

    @Test
    void metadataScenarios() throws Exception {
        line("== 3. METADATA 기존 시나리오 (limitsAndUnsupportedModel 과 같은 반복 · 컨텍스트별 물리 2회) ==");
        ScriptedOllama repeat = server(null);
        repeat.then(MetadataToolIntegrationTest.toolCalls("llama3.2", Map.of("searchTables", Map.of("keyword", "TB"))));
        line("반복 : %s", flow(repeat, "반복", ToolScope.METADATA));
        for (int context : new int[]{6_144, 8_192, 16_384}) {
            ScriptedOllama o = server(context);
            o.then(MetadataToolIntegrationTest.toolCalls("llama3.2", Map.of("searchTables", Map.of("keyword", "계정"))));
            o.then(MetadataToolIntegrationTest.toolCalls("llama3.2", Map.of("getTableColumns", Map.of("tableName", "TB_ACNT"))));
            o.then(MetadataToolIntegrationTest.raw("llama3.2", "초안"));
            o.then(MetadataToolIntegrationTest.answer("llama3.2", "답"));
            line("컨텍스트 %,d · 물리 2회 : %s", context, flow(o, "계정 테이블의 컬럼을 알려줘", ToolScope.METADATA));
        }
    }

    @Test
    void qualityScenarios() throws Exception {
        long groupId = inspections.findings(data.projectId(), search(StandardizationRule.TERM_SAME_WORDS), PageRequest.of(0, 1)).getContent().getFirst().id();
        long columnFinding = inspections.findings(data.projectId(), search(StandardizationRule.TERM_NOT_LINKED), PageRequest.of(0, 1)).getContent().getFirst().id();
        record Flow(String name, String question, List<Map<String, Map<String, Object>>> calls) {
        }
        long tableId = data.table().getId();
        List<Flow> flows = List.of(
                new Flow("요약 단독", "품질 상태", List.of(Map.of(QualityToolDefinitions.OVERVIEW, Map.of()))),
                new Flow("요약+검색", "오류 등급 문제", List.of(Map.of(QualityToolDefinitions.OVERVIEW, Map.of()), Map.of(QualityToolDefinitions.SEARCH, Map.of("severity", "ERROR")))),
                new Flow("검색+상세", "컬럼 문제 설명", List.of(Map.of(QualityToolDefinitions.SEARCH, Map.of("rule", "TERM_NOT_LINKED")),
                        Map.of(QualityToolDefinitions.DETAIL, Map.of("findingId", columnFinding)))),
                new Flow("품질+물리", "테이블 문제와 현재 컬럼", List.of(Map.of(QualityToolDefinitions.SEARCH, Map.of("tableId", tableId)),
                        Map.of("getTableColumns", Map.of("tableId", tableId)))),
                new Flow("품질+사전", "용어 그룹 문제", List.of(Map.of(QualityToolDefinitions.DETAIL, Map.of("findingId", groupId)),
                        Map.of("searchStandardTerms", Map.of("keyword", "회원번호", "limit", 20)))),
                new Flow("실행 비교", "최근 검사 비교", List.of(Map.of(QualityToolDefinitions.OVERVIEW, Map.of()),
                        Map.of(QualityToolDefinitions.OVERVIEW, Map.of("runId", data.run1())), Map.of(QualityToolDefinitions.OVERVIEW, Map.of("runId", data.run2())))),
                new Flow("큰 그룹 근거", "용어 그룹 상세", List.of(Map.of(QualityToolDefinitions.DETAIL, Map.of("findingId", groupId)))),
                new Flow("누적 6회", "전부 설명", List.of(Map.of(QualityToolDefinitions.OVERVIEW, Map.of()), Map.of(QualityToolDefinitions.SEARCH, Map.of("limit", 20)),
                        Map.of(QualityToolDefinitions.SEARCH, Map.of("limit", 20, "page", 1)), Map.of(QualityToolDefinitions.DETAIL, Map.of("findingId", groupId)),
                        Map.of("getTableColumns", Map.of("tableId", tableId)), Map.of("searchStandardTerms", Map.of("keyword", "회원번호", "limit", 20)))));
        line("== 4. QUALITY 실제 Tool 결과 (가짜 Ollama 가 정한 순서로 부르고 서버가 실제 실행) ==");
        for (int context : new int[]{6_144, 8_192, 16_384}) {
            for (Flow flow : flows) {
                ScriptedOllama o = server(context);
                for (Map<String, Map<String, Object>> call : flow.calls()) o.then(MetadataToolIntegrationTest.toolCalls("llama3.2", call));
                o.then(MetadataToolIntegrationTest.raw("llama3.2", "초안"));
                o.then(MetadataToolIntegrationTest.answer("llama3.2", "답"));
                line("컨텍스트 %,d · %s : %s", context, flow.name(), flow(o, flow.question(), ToolScope.QUALITY));
            }
        }
    }

    // ------------------------------------------------------------------ 보조

    private static InspectionDto.Search search(StandardizationRule rule) {
        return new InspectionDto.Search(rule, null, null, null, null, null, null, null, null, null, null);
    }

    private String flow(ScriptedOllama ollama, String question, ToolScope scope) {
        String outcome;
        try {
            AiMetadataQueryDto.QueryResult result = queries.query(data.viewerContext(), new AiMetadataQueryDto.QueryRequest(question, scope));
            outcome = result.completion().status() + (result.completion().reason() == null ? "" : " " + result.completion().reason()) + " · 실행 Tool "
                    + result.completion().executedTools();
        } catch (CustomException e) {
            outcome = "오류 " + e.getErrorCode();
        }
        List<String> perRequest = new ArrayList<>();
        int maxTool = 0;
        String lastToolStage = null;
        for (String chat : ollama.chats) {
            JsonNode tree = JSON.readTree(chat);
            boolean toolStage = tree.path("tools").size() > 0;
            int estimate = estimate(tree, toolStage ? toolset.callbacks(scope) : List.of());
            if (toolStage) {
                maxTool = Math.max(maxTool, estimate);
                lastToolStage = chat;
            }
            perRequest.add((toolStage ? "T" : "S") + estimate);
        }
        List<String> results = new ArrayList<>();
        if (lastToolStage != null) {
            for (JsonNode message : JSON.readTree(lastToolStage).path("messages")) {
                if ("tool".equals(message.path("role").asString())) {
                    String content = message.path("content").asString();
                    results.add(content.length() + "자/" + TokenEstimator.estimate(content));
                }
            }
        }
        return outcome + " · 모델 호출 " + ollama.chats.size() + " · 요청별 [" + String.join(", ", perRequest) + "] · Tool 단계 최대 " + maxTool
                + " (상한까지 " + (12_288 - maxTool) + " — 기본 컨텍스트 기준) · 마지막 Tool 단계 요청의 결과 [" + String.join(", ", results) + "]";
    }

    private record Totals(int chars, int tokens) {
    }

    private Totals definitions(String label, List<ToolCallback> callbacks) {
        int chars = 0;
        int tokens = 0;
        for (ToolCallback callback : callbacks) {
            ToolDefinition d = callback.getToolDefinition();
            int c = d.name().length() + d.description().length() + d.inputSchema().length();
            int t = PER + TokenEstimator.estimate(d.name()) + TokenEstimator.estimate(d.description()) + TokenEstimator.estimate(d.inputSchema());
            chars += c;
            tokens += t;
            line("%s %s | 설명 %d | 스키마 %d | %d자 | 추정 %d", label, d.name(), d.description().length(), d.inputSchema().length(), c, t);
        }
        return new Totals(chars, tokens);
    }

    /** TokenMeter.estimate 와 같은 계산을 요청 본문으로 (메시지 8 + 본문, Tool 호출 이름 + 인자, Tool 응답 16 + 이름 + 본문, 정의 8 + 이름·설명·스키마) */
    private static int estimate(JsonNode request, List<ToolCallback> definitions) {
        long total = 0;
        // Ollama 의 tool 메시지에는 이름이 없다 : 바로 앞 Tool 호출의 이름을 순서대로 쓴다 (TokenMeter 는 응답 이름을 센다)
        java.util.ArrayDeque<String> called = new java.util.ArrayDeque<>();
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asString();
            String content = message.path("content").isMissingNode() || message.path("content").isNull() ? "" : message.path("content").asString();
            if ("tool".equals(role)) {
                String name = message.path("tool_name").asString(called.isEmpty() ? "" : called.poll());
                total += 2L * PER + TokenEstimator.estimate(name) + TokenEstimator.estimate(content);
                continue;
            }
            total += PER + TokenEstimator.estimate(content);
            for (JsonNode call : message.path("tool_calls")) {
                called.add(call.path("function").path("name").asString());
                total += TokenEstimator.estimate(call.path("function").path("name").asString()) + TokenEstimator.estimate(call.path("function").path("arguments").toString());
            }
        }
        for (ToolCallback callback : definitions) {
            ToolDefinition d = callback.getToolDefinition();
            total += PER + TokenEstimator.estimate(d.name()) + TokenEstimator.estimate(d.description()) + TokenEstimator.estimate(d.inputSchema());
        }
        return (int) total;
    }

    private static void line(String format, Object... args) {
        REPORT.append(String.format(format, args)).append('\n');
    }

    private ScriptedOllama server(Integer context) throws IOException {
        ScriptedOllama server = new ScriptedOllama();
        servers.add(server);
        if (context != null) server.show(List.of("completion", "tools"), context);
        AiProviderDto.Provider provider = providers.create(new ProviderRequest("AI-7 " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", server.url(), "llama3.2", true, null));
        createdProviders.add(provider.id());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", server.port(), true, "AI-7 측정")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider.id()));
        return server;
    }
}
