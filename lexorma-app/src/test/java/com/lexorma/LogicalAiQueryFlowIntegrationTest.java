package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.ai.service.AiMetadataQueryService;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lexorma.MetadataToolIntegrationTest.answer;
import static com.lexorma.MetadataToolIntegrationTest.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-35 D35-FINAL : 내부 AI 질의 전체 흐름을 가짜 Ollama(결정적 스크립트)로 — 질문 → 질의 서비스 → 모델의 Tool 호출 → 논리 Tool 실행(실제 실행 경계·결과 변환) → Tool 결과를 담은
 * 다음 모델 호출 → 구조화 답변 → 근거 검증 → 결과. <b>실제 LLM 의 답변 능력을 검증하지 않는다</b> (계약·실행 흐름만 — 실제 모델 검증은 문서 27장 이후).
 * 토큰 예산은 기본 설정 그대로 (컨텍스트 16,384 · 출력 4,096 · 질의 100,000), 가짜 서버가 보고하는 사용량(10·5 토큰)은 실제가 아니라 추정값만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalAiQueryFlowIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String MODEL = "gemma4:e4b";

    @Autowired ApplicationContext context;
    @Autowired AiMetadataQueryService queries;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        lines.forEach(System.out::println);
        defaults.clear();
        for (Long id : createdProviders) providers.delete(id);
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        SecurityTestSupport.clear();
    }

    /** 가짜 Ollama + 허용 규칙 + 기본 Provider. 모델 정보는 실제 검증 모델과 같은 컨텍스트(131,072)·tools */
    private ScriptedOllama ollama() throws Exception {
        ScriptedOllama server = new ScriptedOllama();
        servers.add(server);
        server.show(List.of("completion", "tools"), 131_072);
        AiProviderDto.Provider provider = providers.create(new ProviderRequest("D35 " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", server.url(), MODEL, true, null));
        createdProviders.add(provider.id());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", server.port(), true, "D35 테스트")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider.id()));
        return server;
    }

    private static ExecutionContext user(LogicalD35Fixture x) {
        return ExecutionContext.user(AccountDto.builder().id(x.f.user.getId()).projectId(x.project()).build());
    }

    private static Map<String, Map<String, Object>> call(String tool, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put(tool, args);
        return calls;
    }

    private static Map<String, Object> evidence(EvidenceType type, long id, String name, Boolean usesDescription) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type.name());
        e.put("id", id);
        e.put("name", name);
        if (usesDescription != null) e.put("usesDescription", usesDescription);
        return e;
    }

    private static List<EvidenceVerifier.Status> statuses(AiMetadataQueryDto.QueryResult r) {
        return r.evidence().stream().map(EvidenceVerifier.Result::status).toList();
    }

    /** 요청마다 본문 크기·추정 토큰 (가짜 서버가 받은 실제 요청 본문 — TokenMeter 의 추정과 같은 규칙을 본문 전체에 적용한 참고값) */
    private void requestSizes(String label, ScriptedOllama server) {
        for (int i = 0; i < server.chats.size(); i++) {
            String body = server.chats.get(i);
            JsonNode node = JSON.readTree(body);
            int tools = node.path("tools").isArray() ? node.path("tools").size() : 0;
            lines.add("[D35-FINAL 측정] " + label + " 요청 " + (i + 1) + " | 본문 chars=" + body.length() + " | estTokens(본문)=" + TokenEstimator.estimate(body)
                    + " | 메시지 " + node.path("messages").size() + " | Tool 정의 " + tools + " | num_ctx=" + node.path("options").path("num_ctx").asInt()
                    + " | num_predict=" + node.path("options").path("num_predict").asInt());
        }
    }

    // ------------------------------------------------------------------ 시나리오 (계약·흐름)

    @Test
    @DisplayName("흐름 : 개요 → 영역 검색 → 상세(상속 주 식별자·직접/상속 속성) → 구조화 답변 → 근거 VERIFIED, Tool 결과가 다음 모델 요청에 실린다, 요청 크기·예산")
    void overviewSearchDetail() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalModelOverview", "modelId", x.f.modelId)))
                .then(toolCalls(MODEL, call("searchLogicalEntities", "modelId", x.f.modelId, "subjectAreaId", x.top)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(answer(MODEL, "개인고객의 주 식별자는 당사자에서 상속한 당사자 PK(당사자구분 → 당사자번호)입니다.", List.of(
                        evidence(EvidenceType.LOGICAL_MODEL, x.f.modelId, jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, x.f.modelId), null),
                        evidence(EvidenceType.SUBJECT_AREA, x.top, "고객 관리", null),
                        evidence(EvidenceType.LOGICAL_ENTITY, x.person, "개인고객", null),
                        evidence(EvidenceType.LOGICAL_IDENTIFIER, x.partyPk, "당사자 PK", null),
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", null),
                        evidence(EvidenceType.LOGICAL_RELATIONSHIP, x.relationship, "고객 주문", null),
                        evidence(EvidenceType.SUBTYPE_GROUP, x.customerGroup, "고객 유형", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객의 주 식별자는?"));
        assertThat(r.tools()).extracting(MetadataToolSession.Call::name).containsExactly("getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail");
        assertThat(r.tools()).extracting(MetadataToolSession.Call::status).containsOnly(MetadataToolSession.Status.OK);
        assertThat(statuses(r)).containsOnly(EvidenceVerifier.Status.VERIFIED).hasSize(7);
        assertThat(ai.chats.get(3)).as("상세 결과가 다음 요청의 tool 메시지로").contains("\"role\":\"tool\"", "INHERITED_PRIMARY", "당사자번호");
        JsonNode first = JSON.readTree(ai.chats.getFirst());
        assertThat(first.path("tools")).hasSize(13);
        assertThat(first.path("options").path("num_ctx").asInt()).isEqualTo(16_384);
        requestSizes("개요→검색→상세", ai);
        lines.add("[D35-FINAL 측정] 개요→검색→상세 사용량 | 모델 호출 " + r.usage().modelCalls() + " | 추정 입력 합 " + r.usage().estimatedInputTokens() + " | 컨텍스트 "
                + r.usage().contextTokens() + " | 출력 " + r.usage().maxOutputTokens() + " | 질의 예산 " + r.usage().totalBudget() + " (가짜 서버 보고 사용량은 실제 아님)");
    }

    @Test
    @DisplayName("흐름 : 설명 포함 조회 → usesDescription 근거 VERIFIED / 설명을 빼고 본 속성의 usesDescription → UNVERIFIABLE(DESCRIPTION_NOT_RETRIEVED) / 설명 없는 속성 → MISMATCH")
    void descriptionEvidence() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow-desc", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))   // 설명 없이 (기본)
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.party, "section", "ATTRIBUTES", "includeAttributeDescriptions", true)))
                .then(answer(MODEL, "당사자번호는 당사자를 구별하는 번호입니다.", List.of(
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", true),     // 당사자 ATTRIBUTES 에서 설명 조회 (등록됨)
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyKind, "당사자구분", true),   // 설명 조회했지만 등록 안 됨
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.birth, "생년월일", true),         // 개인고객 상세에서 설명 없이만 봄
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.birth, "생년월일", null))));      // 같은 대상 두 번째는 무시 (기존 규칙)
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("당사자번호의 업무 의미는?"));
        assertThat(r.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.VERIFIED, null),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.MISMATCH, EvidenceVerifier.Reason.DESCRIPTION_NOT_REGISTERED),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Reason.DESCRIPTION_NOT_RETRIEVED));
        assertThat(ai.chats.get(2)).contains("당사자를 구별하는 번호");
    }

    @Test
    @DisplayName("흐름 : 성공 호출 → RESULT_TOO_LARGE(설명 포함 ALL, 안내) → 안내대로 ATTRIBUTES 로 축소. 실패 호출에만 있던 대상의 근거는 거절, 앞 호출 근거는 유지")
    void tooLargeThenNarrow() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow-large", true);
        ScriptedOllama ai = ollama();
        long onlyInFailed = x.bigLeafAttributes.get(50); // ALL(100개)에는 있지만 축소 조회(처음 20개)에는 없는 속성
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "includeAttributeDescriptions", true)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "limit", 10)))
                .then(answer(MODEL, "대형말단의 속성 105개 중 처음 10개만 확인했습니다.", List.of(
                        evidence(EvidenceType.LOGICAL_MODEL, x.f.modelId, jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, x.f.modelId), null),
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.bigLeafAttributes.getFirst(), "루트속성0", true),
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, onlyInFailed, "중간속성10", null))));   // 실패한 호출(ALL)에만 있었던 대상
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("대형말단의 속성을 설명과 함께"));
        assertThat(r.tools()).extracting(MetadataToolSession.Call::status).containsExactly(MetadataToolSession.Status.OK, MetadataToolSession.Status.FAILED,
                MetadataToolSession.Status.OK);
        assertThat(ai.chats.get(2)).as("상한 초과 오류와 안내가 모델에게 간다").contains("RESULT_TOO_LARGE", "\\\"hint\\\"").doesNotContain("중간속성10");
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.UNVERIFIABLE);
        requestSizes("상한 초과→축소", ai);
    }

    @Test
    @DisplayName("흐름 : 같은 Tool 5번째 호출 → 실행하지 않고 Tool 단계 종료 → 전달된 4개 결과로 부분 답변 (AI 공통 실행 안정성 2차 B10 — 예전에는 AI_TOOL_CALL_LIMIT_EXCEEDED)")
    void sameToolLimit() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow-limit", false);
        ScriptedOllama same = ollama();
        for (int page = 0; page < 5; page++) same.then(toolCalls(MODEL, call("searchLogicalEntities", "modelId", x.f.modelId, "page", page, "limit", 1)));
        same.then(answer(MODEL, "조회 상한에 닿아 엔터티 4개만 확인했습니다."));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("엔터티를 하나씩 전부"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(AiMetadataQueryDto.CompletionStatus.PARTIAL,
                AiMetadataQueryDto.TerminationReason.SAME_TOOL_LIMIT_REACHED, 4));
        assertThat(r.tools()).as("5번째 Tool 은 실행하지 않는다").hasSize(4);
        assertThat(same.chats).as("4번 실행 + 모델 호출 5번 + 부분 구조화 1번").hasSize(6);
        assertThat(same.chats.get(5)).contains("same tool", "Tool results given below: 4");
        requestSizes("같은 Tool 상한", same);
    }

    @Test
    @DisplayName("흐름 : 전체 9번째 Tool 호출 → 실행하지 않고 8개 결과로 부분 답변 (Tool 3종을 3번씩 — 같은 Tool 상한 4 안, 2차 B11 — 예전에는 AI_TOOL_CALL_LIMIT_EXCEEDED)")
    void totalLimit() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow-total", false);
        ScriptedOllama total = ollama();
        String[] tools = {"getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail"};
        for (int i = 0; i < 9; i++) {
            String tool = tools[i % 3];
            total.then(toolCalls(MODEL, tool.equals("getLogicalModelOverview") ? call(tool, "modelId", x.f.modelId, "section", "SUBTYPE_GROUPS", "limit", 1)
                    : tool.equals("searchLogicalEntities") ? call(tool, "modelId", x.f.modelId, "page", i, "limit", 1)
                    : call(tool, "entityId", x.order, "section", "IDENTIFIERS")));
        }
        total.then(answer(MODEL, "조회 상한에 닿아 일부만 확인했습니다."));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("반복"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(AiMetadataQueryDto.CompletionStatus.PARTIAL,
                AiMetadataQueryDto.TerminationReason.TOTAL_TOOL_LIMIT_REACHED, 8));
        assertThat(total.chats).as("8번 실행 + 모델 호출 9번 + 부분 구조화 1번").hasSize(10);
        requestSizes("전체 상한", total);
    }

    @Test
    @DisplayName("흐름 : 속성 105개를 설명과 함께 limit 20 페이지로 이어 받기 — 기본 토큰 예산(입력 12,288 추정 토큰)을 넘는 요청은 보내지 않고, 전달된 페이지로 부분 답변 (2차 B09 — 예전에는 오류)")
    void descriptionPagesAgainstBudget() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow-budget", true);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "page", 0, "limit", 20)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "page", 1, "limit", 20)))
                .then(answer(MODEL, "처음 20개만 확인했습니다."));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("대형말단 속성 전부를 설명과 함께"));
        assertThat(r.completion().status()).isEqualTo(AiMetadataQueryDto.CompletionStatus.PARTIAL);
        assertThat(r.completion().reason()).isEqualTo(AiMetadataQueryDto.TerminationReason.INPUT_BUDGET_EXCEEDED);
        long results = JSON.readTree(ai.chats.get(ai.chats.size() - 2)).path("messages").findValues("role").stream().filter(n -> "tool".equals(n.asString())).count();
        lines.add("[2차 측정] 설명 포함 limit 20 이어 받기 : " + r.completion() + " | 모델 요청 " + ai.chats.size()
                + " | 마지막 Tool 단계 요청에 실린 Tool 결과 " + results + "페이지 (" + Math.min(105, results * 20) + "/105)");
        requestSizes("설명 페이지 예산", ai);
    }

    @Test
    @DisplayName("흐름 회귀 : 물리 모델 질문(예제 프로젝트 0 테이블 검색 → 컬럼)이 논리 Tool 이 더해진 뒤에도 같다 — 근거 VERIFIED, 논리 Tool 을 부르지 않는다")
    void physicalRegression() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-flow-physical", false);
        ScriptedOllama ai = ollama();
        Long tableId = jdbc.queryForObject("select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_AI_STNG'", Long.class);
        ai.then(toolCalls(MODEL, call("searchTables", "keyword", "TB_AI_STNG")))
                .then(toolCalls(MODEL, call("getTableColumns", "tableId", tableId)))
                .then(answer(MODEL, "TB_AI_STNG 은 AI 기본 설정 테이블입니다.", List.of(evidence(EvidenceType.TABLE, tableId, "TB_AI_STNG", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(ExecutionContext.user(AccountDto.builder().id(x.f.user.getId()).projectId(0L).build()),
                new AiMetadataQueryDto.QueryRequest("AI 설정 테이블 컬럼?"));
        assertThat(r.tools()).extracting(MetadataToolSession.Call::name).containsExactly("searchTables", "getTableColumns");
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED);
        requestSizes("물리 회귀", ai);
    }
}
