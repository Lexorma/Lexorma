package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto.CompletionStatus;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto.TerminationReason;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.provider.adapter.OllamaProviderAdapter;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.ai.service.AiMetadataQueryService;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.ai.tool.MetadataToolset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.lexorma.MetadataToolIntegrationTest.answer;
import static com.lexorma.MetadataToolIntegrationTest.raw;
import static com.lexorma.MetadataToolIntegrationTest.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * AI 공통 실행 안정성 2차 (docs/completed/ai-execution-stability.md) : 부분 답변·최종 답변 범위·전달 확인·근거 범위·종료 사유·thinking 옵션을
 * 가짜 Ollama(결정적 스크립트)로 질의 전체 흐름(질의 서비스 → Spring AI Tool 반복 → 실제 Tool 실행 경계 → 구조화 → 근거 검증)에서 확인한다.
 * <b>실제 LLM 의 답변 품질은 보지 않는다</b> (3차). 토큰 예산은 기본 설정 그대로 (컨텍스트 16,384 · 출력 4,096 · 질의 100,000) — 질의 누적은 가짜 서버가
 * 보고하는 prompt_eval_count 로 만든다. 1차 재현 시나리오 B09~B16 을 여기서, B17(24,000자) 은 {@link AiExecutionStabilityLargeContextIntegrationTest} 에서 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiExecutionStabilityIntegrationTest {

    static final JsonMapper JSON = JsonMapper.shared();
    static final String MODEL = "gemma4:e4b";
    static final List<String> THINKING_MODEL = List.of("completion", "tools", "thinking");

    @Autowired ApplicationContext context;
    @Autowired AiMetadataQueryService queries;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired OllamaProviderAdapter ollamaAdapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired MetadataToolset toolset;
    @Autowired SchemaInfoRepository schemaInfoRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TransactionTemplate tx;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();

    private final List<Long> projectSettings = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (Long projectId : projectSettings) defaults.clearProject(projectId);
        defaults.clear();
        for (Long id : createdProviders) providers.delete(id);
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        SecurityTestSupport.clear();
    }

    private ScriptedOllama ollama(List<String> capabilities, int contextLength) throws Exception {
        ScriptedOllama server = new ScriptedOllama();
        servers.add(server);
        server.show(capabilities, contextLength);
        AiProviderDto.Provider provider = providers.create(new ProviderRequest("AES " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", server.url(), MODEL, true, null));
        createdProviders.add(provider.id());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", server.port(), true, "AES 테스트")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider.id()));
        return server;
    }

    private ScriptedOllama ollama() throws Exception {
        return ollama(THINKING_MODEL, 131_072);
    }

    static ExecutionContext user(LogicalD35Fixture x) {
        return ExecutionContext.user(AccountDto.builder().id(x.f.user.getId()).projectId(x.project()).build());
    }

    static Map<String, Map<String, Object>> call(String tool, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put(tool, args);
        return calls;
    }

    static Map<String, Object> evidence(EvidenceType type, long id, String name, Boolean usesDescription) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type.name());
        e.put("id", id);
        e.put("name", name);
        if (usesDescription != null) e.put("usesDescription", usesDescription);
        return e;
    }

    /** Tool 호출 응답에 가짜 서버가 보고하는 입력 토큰을 바꾼다 (질의 누적 예산을 결정적으로 채운다) */
    static String reporting(String body, int promptTokens) {
        return body.replace("\"prompt_eval_count\":10", "\"prompt_eval_count\":" + promptTokens);
    }

    /** 출력 한도에서 잘린 응답 (done_reason length) */
    static String truncated(String content) {
        return JSON.writeValueAsString(Map.of("model", MODEL, "created_at", "2026-11-25T10:00:00Z", "message", Map.of("role", "assistant", "content", content),
                "done", true, "done_reason", "length", "prompt_eval_count", 30, "eval_count", 4096));
    }

    static List<EvidenceVerifier.Status> statuses(AiMetadataQueryDto.QueryResult r) {
        return r.evidence().stream().map(EvidenceVerifier.Result::status).toList();
    }

    private long attribute(LogicalD35Fixture x, long entity, String name) {
        return jdbc.queryForObject("select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ? and ATRB_NM = ?", Long.class, entity, name);
    }

    private void revoke(LogicalD35Fixture x) {
        jdbc.update("update TB_PRJ_MBR set ACTVTN_YN = false where PRJ_SN = ? and ACNT_SN = ?", x.project(), Long.valueOf(x.f.user.getId()));
    }

    private static boolean thinkOff(String body) {
        JsonNode think = JSON.readTree(body).path("think");
        return think.isBoolean() && !think.asBoolean();
    }

    /** 옵션 객체의 생각 설정 (Spring AI Ollama 옵션의 thinkOption 속성 직렬화 — 없으면 null) */
    private static String think(ToolCallingChatOptions options) {
        JsonNode node = JSON.readTree(JSON.writeValueAsString(options)).path("thinkOption");
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static boolean hasTools(String body) {
        return JSON.readTree(body).path("tools").isArray() && !JSON.readTree(body).path("tools").isEmpty();
    }

    // ------------------------------------------------------------------ 정상 완료 · Provider 옵션

    @Test
    @DisplayName("정상 : COMPLETE (사유 없음, executedTools = 실행한 Tool 수), 근거 VERIFIED. 생각 기능이 있는 모델은 구조화 요청에만 think=false, Tool 단계 요청은 예전 그대로")
    void completeAndThinkingOption() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-complete", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(raw(MODEL, "개인고객의 주 식별자는 당사자 PK 입니다."))
                .then(answer(MODEL, "개인고객의 주 식별자는 당사자 PK 입니다.", List.of(evidence(EvidenceType.LOGICAL_ENTITY, x.person, "개인고객", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객 주 식별자?"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.COMPLETE, null, 1));
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED);
        assertThat(ai.chats).hasSize(3);
        assertThat(ai.chats.subList(0, 2)).as("Tool 단계 : Tool 정의 13개, think 없음").allSatisfy(body -> {
            assertThat(hasTools(body)).isTrue();
            assertThat(JSON.readTree(body).has("think")).isFalse();
        });
        assertThat(hasTools(ai.chats.get(2))).as("구조화 : Tool 정의 없음").isFalse();
        assertThat(thinkOff(ai.chats.get(2))).as("구조화 : think=false").isTrue();
        assertThat(JSON.readTree(ai.chats.get(2)).path("options").path("num_predict").asInt()).as("출력 한도는 그대로").isEqualTo(4096);
        assertThat(r.usage().modelCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("Provider 옵션 : 생각 기능을 알려 주지 않는 모델(capabilities 에 thinking 없음)은 구조화 요청에도 think 를 보내지 않는다. 옵션 객체는 요청마다 새로 만들고 공유 설정을 바꾸지 않는다")
    void thinkingUnknownModelKeepsRequests() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-nothink", false);
        ScriptedOllama ai = ollama(List.of("completion", "tools"), 131_072);
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(raw(MODEL, "개인고객입니다."))
                .then(answer(MODEL, "개인고객입니다."));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?"));
        assertThat(r.completion().status()).isEqualTo(CompletionStatus.COMPLETE);
        assertThat(ai.chats).hasSize(3).allSatisfy(body -> assertThat(JSON.readTree(body).has("think")).isFalse());

        // 어댑터 : 구조화 옵션만 생각을 끄고, 일반 옵션·생각 끄지 않는 구조화 옵션은 think 가 없다. 매번 다른 객체 (공유 옵션을 바꾸지 않는다)
        ToolCallingChatOptions structuring = ollamaAdapter.structuringOptions(MODEL, 16_384, 4_096, true);
        ToolCallingChatOptions plain = ollamaAdapter.chatOptions(MODEL, 16_384, 4_096);
        ToolCallingChatOptions keep = ollamaAdapter.structuringOptions(MODEL, 16_384, 4_096, false);
        assertThat(think(structuring)).isEqualTo("false");
        assertThat(think(plain)).isNull();
        assertThat(think(keep)).isNull();
        assertThat(ollamaAdapter.structuringOptions(MODEL, 16_384, 4_096, true)).isNotSameAs(structuring);
        assertThat(think(ollamaAdapter.chatOptions(MODEL, 16_384, 4_096))).as("구조화 옵션을 만든 뒤에도 일반 옵션은 그대로").isNull();
    }

    // ------------------------------------------------------------------ 예산

    @Test
    @DisplayName("B09 입력 예산 : 설명 포함 1페이지 → 2페이지 실행 → 다음 요청이 입력 상한 초과(보내지 않음) → 전달된 1페이지로 PARTIAL(INPUT_BUDGET_EXCEEDED). 2페이지 근거·설명은 불인정, 사용량은 보낸 요청만")
    void inputBudgetPartial() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-input", true);
        ScriptedOllama ai = ollama();
        long first = x.bigLeafAttributes.getFirst();   // 1페이지
        long second = x.bigLeafAttributes.get(25);     // 2페이지에만
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "page", 0, "limit", 20)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "page", 1, "limit", 20)))
                .then(answer(MODEL, "조회가 중간에 멈춰 105개 중 처음 20개만 확인했습니다.", List.of(
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, first, "루트속성0", true),
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, second, "루트속성25", true))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("대형말단 속성 전부를 설명과 함께"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.INPUT_BUDGET_EXCEEDED, 2));
        assertThat(r.tools()).extracting(MetadataToolSession.Call::status).containsExactly(MetadataToolSession.Status.OK, MetadataToolSession.Status.OK);
        assertThat(ai.chats).as("Tool 요청 2번(3번째는 보내지 않음) + 부분 구조화 1번").hasSize(3);
        String structuring = ai.chats.get(2);
        assertThat(hasTools(structuring)).isFalse();
        assertThat(thinkOff(structuring)).isTrue();
        assertThat(structuring).contains("Why the answer is partial", "further lookups were stopped", "input token budget", "Tool results given below: 1", "루트속성0")
                .doesNotContain("루트속성25", "Draft answer");
        assertThat(r.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.VERIFIED, null),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Reason.NOT_RETRIEVED));
        assertThat(r.usage().modelCalls()).as("보내지 않은 요청은 사용량에 없다").isEqualTo(ai.chats.size());
        assertThat(r.usage().promptTokens()).isEqualTo(10 + 10 + 20);
    }

    @Test
    @DisplayName("질의 누적 예산 : 2번째 응답이 입력 90,000 을 보고 → 다음 요청을 보낼 수 없음 → PARTIAL(QUERY_BUDGET_EXCEEDED). 전달되지 않은 2번째 결과에만 있는 대상·설명은 불인정, 1번째 결과와 겹치는 대상은 유지")
    void queryBudgetPartialAndEvidenceRange() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-query", false);
        ScriptedOllama ai = ollama();
        long vendorGrade = attribute(x, x.vendor, "거래처등급");
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))                                                  // 당사자번호·구분 (설명 없이)
                .then(reporting(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.vendor, "includeAttributeDescriptions", true)), 90_000)) // 실행되지만 전달 안 됨
                .then(answer(MODEL, "확인한 범위에서 답합니다.", List.of(
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyKind, "당사자구분", null),     // 두 결과 모두에 — 1번째 결과로 유지
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", true),       // 설명은 전달 안 된 2번째 결과에만
                        evidence(EvidenceType.LOGICAL_ATTRIBUTE, vendorGrade, "거래처등급", null),     // 2번째 결과에만
                        evidence(EvidenceType.LOGICAL_ENTITY, x.vendor, "거래처", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객과 거래처 비교"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.QUERY_BUDGET_EXCEEDED, 2));
        assertThat(ai.chats).hasSize(3);
        assertThat(ai.chats.get(2)).contains("total token budget", "Tool results given below: 1").doesNotContain("거래처등급");
        assertThat(r.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.VERIFIED, null),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Reason.DESCRIPTION_NOT_RETRIEVED),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Reason.NOT_RETRIEVED),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Reason.NOT_RETRIEVED));
        assertThat(r.usage().promptTokens()).as("앞 호출의 실제 사용량을 지우지 않는다").isEqualTo(10 + 90_000 + 20);
    }

    @Test
    @DisplayName("최종 답변 요청 자체가 예산 초과 : 누적 95,000 → 1번째 결과만으로도 부분 구조화 요청을 보낼 수 없음 → 기존 AI_TOKEN_BUDGET_EXCEEDED (보내지 않는다)")
    void partialFinalRequestOverBudget() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-final-over", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(reporting(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.vendor)), 95_000))
                .then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("비교")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED));
        assertThat(ai.chats).hasSize(2);
    }

    @Test
    @DisplayName("유효 결과 없음 : Tool 은 실행했지만 결과를 실은 요청을 보내기 전에 누적 예산 초과(전달 확인 없음) → 기존 오류. 결과가 없는 첫 요청 뒤 입력 초과(컨텍스트 6,144)도 기존 오류")
    void noDeliveredResult() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-undelivered", false);
        ScriptedOllama ai = ollama();
        ai.then(reporting(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)), 97_000)).then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED));
        assertThat(ai.chats).as("Tool 결과를 실은 요청은 보내지 않았다").hasSize(1);

        ScriptedOllama small = ollama(THINKING_MODEL, 6_144);
        small.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED));
        assertThat(small.chats).hasSize(1);
    }

    @Test
    @DisplayName("B13 작은 컨텍스트 : 모델 컨텍스트 4,096 → 첫 요청 전에 멈추고(아무것도 보내지 않음) 원인을 숫자로 알린다 (같은 오류 코드, 한국어·영어, 지시·질문 원문 없음)")
    void smallContextMessage() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-small", false);
        ScriptedOllama ai = ollama(THINKING_MODEL, 4_096);
        ai.then(answer(MODEL, "보내지 않아야 하는 응답"));
        Throwable error = catchThrowable(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객 비밀질문")));
        assertThat(error).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
            assertThat(e.getCustomMessage()).contains("컨텍스트 4096", "응답 몫 1024", "상한 3072", "질의를 시작하지 못했습니다").doesNotContain("비밀질문", "metadata assistant");
        });
        assertThat(ai.chats).isEmpty();
        // 영어 (Accept-Language)
        org.springframework.mock.web.MockHttpServletRequest english = new org.springframework.mock.web.MockHttpServletRequest();
        english.addHeader("Accept-Language", "en");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(english));
        try {
            assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("small context")))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getCustomMessage()).contains("could not be started", "context 4096", "3072"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
        assertThat(ai.chats).isEmpty();
    }

    @Test
    @DisplayName("Provider 실패 : 판정을 통과해 보낸 요청이 AI 서버 오류(500) → 전달 확인 없음, 부분 답변으로 바꾸지 않고 기존 호출 실패")
    void providerFailureIsNotPartial() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-provider", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(500, "{\"error\":\"boom\"}", 0);
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_CALL_FAILED));
        assertThat(ai.chats).hasSize(2);
    }

    // ------------------------------------------------------------------ 호출 상한

    @Test
    @DisplayName("한 응답의 여러 Tool 호출 : 7번 실행 뒤 [8번째, 9번째] 를 한꺼번에 요청 → 8번째는 실행(executedTools 8)되지만 전달되지 않고 9번째는 실행 전에 막힘 → 전달된 7개로 PARTIAL(TOTAL_TOOL_LIMIT_REACHED)")
    void batchBoundaryAtTotalLimit() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-batch", false);
        ScriptedOllama ai = ollama();
        String[] tools = {"getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail"};
        for (int i = 0; i < 7; i++) {
            String tool = tools[i % 3];
            ai.then(toolCalls(MODEL, tool.equals("getLogicalModelOverview") ? call(tool, "modelId", x.f.modelId, "section", "SUBTYPE_GROUPS", "limit", 1)
                    : tool.equals("searchLogicalEntities") ? call(tool, "modelId", x.f.modelId, "page", i, "limit", 1)
                    : call(tool, "entityId", x.order, "section", "IDENTIFIERS")));
        }
        Map<String, Map<String, Object>> batch = new LinkedHashMap<>();
        batch.put("getLogicalEntityDetail#8", Map.of("entityId", x.vendor));
        batch.put("getLogicalEntityDetail#9", Map.of("entityId", x.corp));
        long vendorGrade = attribute(x, x.vendor, "거래처등급");
        ai.then(toolCalls(MODEL, batch))
                .then(answer(MODEL, "조회 상한에 닿아 일부만 확인했습니다.", List.of(evidence(EvidenceType.LOGICAL_ATTRIBUTE, vendorGrade, "거래처등급", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("반복"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.TOTAL_TOOL_LIMIT_REACHED, 8));
        assertThat(r.tools()).as("9번째는 실행하지 않았다").hasSize(8);
        assertThat(ai.chats).as("Tool 단계 8번 + 부분 구조화 1번").hasSize(9);
        assertThat(ai.chats.get(8)).contains("maximum number of tool calls", "Tool results given below: 7").doesNotContain("거래처등급");
        assertThat(statuses(r)).as("실행됐지만 전달되지 않은 8번째 결과의 근거").containsExactly(EvidenceVerifier.Status.UNVERIFIABLE);
    }

    @Test
    @DisplayName("부분 답변 생성 실패 : 상한 뒤 부분 구조화가 형식 오류 2번 / 출력 잘림 → 최초 사유(AI_TOOL_CALL_LIMIT_EXCEEDED) 유지, 자동 재호출 없음(스키마 재요청 1번만)")
    void partialFailureKeepsOriginalReason() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-partial-fail", false);
        ScriptedOllama ai = ollama();
        for (int page = 0; page < 5; page++) ai.then(toolCalls(MODEL, call("searchLogicalEntities", "modelId", x.f.modelId, "page", page, "limit", 1)));
        ai.then(raw(MODEL, "형식이 아닌 답")).then(raw(MODEL, "또 형식이 아닌 답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("전부")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED));
        assertThat(ai.chats).as("Tool 단계 5번 + 부분 구조화 2번(재요청 1번)").hasSize(7);

        ScriptedOllama cut = ollama();
        for (int page = 0; page < 5; page++) cut.then(toolCalls(MODEL, call("searchLogicalEntities", "modelId", x.f.modelId, "page", page, "limit", 1)));
        cut.then(truncated("{\"answer\": \"잘린"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("전부")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED));
        assertThat(cut.chats).hasSize(6);
    }

    // ------------------------------------------------------------------ 출력 한도

    @Test
    @DisplayName("B14 초안 출력 잘림 : 결과를 받은 뒤 초안이 출력 한도에서 잘림 → 잘린 초안 없이 전달된 결과로 PARTIAL(DRAFT_OUTPUT_LIMIT_REACHED)")
    void draftTruncatedPartial() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-draft", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(truncated("잘린 초안 TRUNCATED-DRAFT-TEXT"))
                .then(answer(MODEL, "초안이 끝나지 않아 조회 결과로만 답합니다.", List.of(evidence(EvidenceType.LOGICAL_ENTITY, x.person, "개인고객", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.DRAFT_OUTPUT_LIMIT_REACHED, 1));
        assertThat(ai.chats.get(2)).contains("draft answer was cut off", "Tool results given below: 1").doesNotContain("TRUNCATED-DRAFT-TEXT", "Draft answer:");
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED);
        assertThat(r.answer()).doesNotContain("TRUNCATED-DRAFT-TEXT");
    }

    @Test
    @DisplayName("B14b 구조화 출력 잘림 : 정상 흐름의 구조화 응답이 잘림 → 기존 AI_TOKEN_BUDGET_EXCEEDED (잘린 JSON 을 답으로 쓰지 않는다)")
    void structuringTruncatedKeepsError() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-struct-cut", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(raw(MODEL, "개인고객입니다."))
                .then(truncated("{\"answer\": \"개인고객"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED));
        assertThat(ai.chats).hasSize(3);
    }

    @Test
    @DisplayName("B16 구조화 단계 입력 : 모든 결과 + 긴 초안(8,000자)으로는 최종 요청이 입력 상한 초과 → 초안 없이 결과 범위로 PARTIAL(INPUT_BUDGET_EXCEEDED). 초안은 넣지 않는다")
    void structuringInputFallsBack() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-b16", true);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "page", 0, "limit", 20)))
                .then(raw(MODEL, "긴초안".repeat(3000)))
                .then(answer(MODEL, "처음 20개만 확인했습니다.", List.of(evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.bigLeafAttributes.getFirst(), "루트속성0", true))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("속성 설명"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.INPUT_BUDGET_EXCEEDED, 1));
        assertThat(ai.chats).hasSize(3);
        assertThat(ai.chats.get(2)).doesNotContain("긴초안긴초안").contains("Tool results given below: 1");
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED);
    }

    // ------------------------------------------------------------------ 결과 상한 초과 (기존 흐름)

    @Test
    @DisplayName("B12 RESULT_TOO_LARGE : 크기 초과 결과는 모델에게 오류·안내로 가고 모델이 범위를 줄여 다시 조회 → COMPLETE (부분 답변으로 바로 바꾸지 않는다), executedTools 는 실패 호출 포함")
    void tooLargeStaysComplete() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-large", true);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "includeAttributeDescriptions", true)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.bigLeaf, "section", "ATTRIBUTES", "limit", 10)))
                .then(raw(MODEL, "처음 10개입니다."))
                .then(answer(MODEL, "처음 10개입니다.", List.of(evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.bigLeafAttributes.getFirst(), "루트속성0", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("대형말단 속성"));
        assertThat(r.tools()).extracting(MetadataToolSession.Call::status).containsExactly(MetadataToolSession.Status.FAILED, MetadataToolSession.Status.OK);
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.COMPLETE, null, 2));
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED);
    }

    // ------------------------------------------------------------------ 권한

    @Test
    @DisplayName("B15 권한 : 마지막 Tool 뒤 멤버십 해제(부분 답변 전환 직전) → PROJECT_ACCESS_DENIED (부분 답변 아님, 구조화 요청 안 보냄) / 구조화 요청 중 해제 → 답을 받아도 돌려주지 않음")
    void accessLostIsNeverPartial() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-access", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person)))
                .then(truncated("잘린 초안"), () -> revoke(x))   // 이 응답 뒤로 Tool 은 없다 → 부분 답변 전환 직전 재검증에서 멈춘다
                .then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(ai.chats).hasSize(2);

        LogicalD35Fixture y = LogicalD35Fixture.create(context, "aes-access2", false);
        ScriptedOllama ai2 = ollama();
        ai2.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", y.person)))
                .then(raw(MODEL, "개인고객입니다."))
                .then(answer(MODEL, "개인고객입니다.", List.of(evidence(EvidenceType.LOGICAL_ENTITY, y.person, "개인고객", null))), () -> revoke(y));
        assertThatThrownBy(() -> queries.query(user(y), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(ai2.chats).hasSize(3);

        // 질의 중 Tool 실행 시점의 해제 (기존) : 다음 Tool 재검증에서 멈춘다 — 상한·예산 사유가 있어도 권한 오류가 앞선다
        LogicalD35Fixture z = LogicalD35Fixture.create(context, "aes-access3", false);
        ScriptedOllama ai3 = ollama();
        ai3.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", z.person)))
                .then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", z.vendor)), () -> revoke(z))
                .then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(z), new AiMetadataQueryDto.QueryRequest("비교")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED));
    }

    // ------------------------------------------------------------------ 구조화 단계 Tool 요청 (3차 AES-D16)

    @Test
    @DisplayName("AES-D16 구조화 단계 Tool 요청 : 허용 목록 Tool · 여러 Tool · 목록 밖 Tool 을 구조화 응답에서 부르면 그 응답에서 바로 끝남(AI_STRUCTURED_OUTPUT_INVALID) — "
            + "거절을 되돌려 다시 부르지 않음, Tool 실행 0, 재요청 없음, 그 호출의 사용량은 기록")
    void structuringToolRequestStopsAtOnce() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-d16", false);
        Map<String, Map<String, Object>> many = new LinkedHashMap<>();
        many.put("getLogicalEntityDetail#1", Map.of("entityId", x.person));
        many.put("searchLogicalEntities#2", Map.of("modelId", x.f.modelId));
        List<String> requests = List.of(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.vendor)), toolCalls(MODEL, many),
                toolCalls(MODEL, call("dropEverything", "really", true)));
        for (String structuringReply : requests) {
            ScriptedOllama ai = ollama();
            ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(raw(MODEL, "개인고객입니다.")).then(structuringReply)
                    .then(answer(MODEL, "보내지 않아야 하는 응답"));
            int before = jdbc.queryForObject("select count(*) from TB_LGCL_ENTT", Integer.class);
            assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_STRUCTURED_OUTPUT_INVALID));
            assertThat(ai.chats).as("Tool 단계 2번 + 구조화 1번 (거절 반복 없음, 재요청 없음)").hasSize(3);
            assertThat(hasTools(ai.chats.get(2))).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_ENTT", Integer.class)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("AES-D16 : 구조화 응답의 Tool 요청은 실행하지 않고 tools 기록·executedTools 에도 없다 / 형식 오류는 예전처럼 1번 재요청해 COMPLETE / 부분 구조화의 Tool 요청 → 최초 사유")
    void structuringRetryAndPartialToolRequest() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-d16b", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(raw(MODEL, "개인고객입니다."))
                .then(raw(MODEL, "형식이 아닌 답"))
                .then(answer(MODEL, "개인고객입니다.", List.of(evidence(EvidenceType.LOGICAL_ENTITY, x.person, "개인고객", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?"));
        assertThat(r.structuredRetries()).isEqualTo(1);
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.COMPLETE, null, 1));
        assertThat(r.tools()).extracting(MetadataToolSession.Call::status).containsExactly(MetadataToolSession.Status.OK);
        assertThat(r.usage().modelCalls()).isEqualTo(4);

        ScriptedOllama partial = ollama();
        for (int page = 0; page < 5; page++) partial.then(toolCalls(MODEL, call("searchLogicalEntities", "modelId", x.f.modelId, "page", page, "limit", 1)));
        partial.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("전부")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED));
        assertThat(partial.chats).as("Tool 단계 5번 + 부분 구조화 1번").hasSize(6);
    }

    // ------------------------------------------------------------------ 서버 맥락 (3차 AES-D15)

    /** 프로젝트에 물리 테이블 하나와 컬럼들 (서버 맥락 조회용). 컬럼 결과가 target 자 이상이 될 때까지 컬럼을 더한다 (개별 결과 상한 24,000자 안) */
    private TableInfo bigTable(LogicalD35Fixture x, String name, String text, int target) {
        Long projectId = x.project();
        TableInfo table = tx.execute(s -> {
            SchemaInfo schema = schemaInfoRepository.findAll().stream().filter(sc -> projectId.equals(sc.getProjectId())).findFirst()
                    .orElseGet(() -> schemaInfoRepository.save(SchemaInfo.builder().projectId(projectId).schemaName(SchemaInfo.DEFAULT_NAME).schemaDescription("기본 스키마")
                            .isDefault(true).build()));
            return tableInfoRepository.save(TableInfo.builder().projectId(projectId).schema(schema).tableName(name).tableKorName(name + " 표").tableDescription("서버 맥락 시험용")
                    .isApproval(false).isMasterTable(false).build());
        });
        for (int i = 0; i < 100; i++) {
            MetadataToolSession probe = new MetadataToolSession(user(x), "probe", 60_000);
            int order = i + 1;
            tx.executeWithoutResult(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableInfo(table).tableName(name).columnName("COL_" + order)
                    .columnDesc(text.substring(0, Math.min(250, text.length()))).bizDescription(text).dataType("VARCHAR(10)").sortOrder(order).isPk(false).isNullable(true)
                    .isApproval(false).build()));
            String result = toolset.run("getTableColumns", Map.of("tableId", table.getId()), probe);
            assertThat(result).doesNotContain("RESULT_TOO_LARGE");
            // 서버 맥락 요청의 결과 합 (검색 결과 + 컬럼 결과, 블록 머리 포함 근사)
            int combined = result.length() + toolset.run("searchTables", Map.of("keyword", name), new MetadataToolSession(user(x), "probe", 60_000)).length() + 82;
            if (combined >= target) break;
        }
        return table;
    }

    private ScriptedOllama noToolsModel() throws Exception {
        return ollama(List.of("completion", "thinking"), 131_072);
    }

    @Test
    @DisplayName("AES-D15 서버 맥락 : 정상 COMPLETE(기존 안내·근거) / 결과 원문 24,000자 → 뒤 결과를 빼고 PARTIAL(CONTEXT_RESULT_LIMIT_REACHED), 빠진 결과의 근거 불인정, "
            + "executedTools = 서버 조회 수(같은 Tool 실행 경계) / 설명은 조회하지 않아 usesDescription 불인정")
    void serverContextResultLimit() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-sc", false);
        TableInfo big = bigTable(x, "TB_AES_SC_BIG", "Plain ascii business description for the server context limit test. ".repeat(2).substring(0, 100), 24_050);
        Long firstColumn = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = 'COL_1'", Long.class, big.getId());

        ScriptedOllama ai = noToolsModel();
        ai.then(answer(MODEL, "TB_AES_SC_BIG 테이블만 확인했습니다.", List.of(evidence(EvidenceType.TABLE, big.getId(), "TB_AES_SC_BIG", null),
                evidence(EvidenceType.COLUMN, firstColumn, "COL_1", null), evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", true))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("TB_AES_SC_BIG 테이블의 컬럼을 알려줘"));
        assertThat(r.mode()).isEqualTo(MetadataToolSession.Mode.SERVER_CONTEXT);
        assertThat(r.fallback()).isEqualTo(AiMetadataQueryService.FallbackReason.MODEL_CAPABILITIES);
        assertThat(r.tools()).extracting(MetadataToolSession.Call::name).containsExactly("searchTables", "getTableColumns");
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.CONTEXT_RESULT_LIMIT_REACHED, 2));
        assertThat(ai.chats).hasSize(1);
        assertThat(hasTools(ai.chats.getFirst())).isFalse();
        assertThat(thinkOff(ai.chats.getFirst())).as("서버 맥락의 구조화 요청도 생각 끔").isTrue();
        String user = JSON.readTree(ai.chats.getFirst()).path("messages").get(1).path("content").asString();
        assertThat(user).contains("only the first 1 looked-up results", "result text limit").doesNotContain("COL_1", "[truncated");
        assertThat(r.evidence()).extracting(EvidenceVerifier.Result::status).containsExactly(EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.UNVERIFIABLE,
                EvidenceVerifier.Status.UNVERIFIABLE);

        // 같은 테이블이라도 결과가 들어가면 COMPLETE (작은 테이블)
        LogicalD35Fixture y = LogicalD35Fixture.create(context, "aes-sc-ok", false);
        TableInfo small = bigTable(y, "TB_AES_SC_SMALL", "짧은 설명", 0);
        ScriptedOllama ok = noToolsModel();
        ok.then(answer(MODEL, "컬럼 하나입니다.", List.of(evidence(EvidenceType.TABLE, small.getId(), "TB_AES_SC_SMALL", null))));
        AiMetadataQueryDto.QueryResult complete = queries.query(user(y), new AiMetadataQueryDto.QueryRequest("TB_AES_SC_SMALL 테이블의 컬럼을 알려줘"));
        assertThat(complete.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.COMPLETE, null, 2));
        assertThat(statuses(complete)).containsExactly(EvidenceVerifier.Status.VERIFIED);
        assertThat(JSON.readTree(ok.chats.getFirst()).path("messages").get(1).path("content").asString()).doesNotContain("Lookup status");

        // 해석할 수 없는 질문 : 조회 없음 → 결과 없이 COMPLETE (기존 안내)
        ok.then(answer(MODEL, "무엇을 찾을지 알 수 없습니다."));
        AiMetadataQueryDto.QueryResult nothing = queries.query(user(y), new AiMetadataQueryDto.QueryRequest("?"));
        assertThat(nothing.tools()).isEmpty();
        assertThat(nothing.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.COMPLETE, null, 0));
        assertThat(JSON.readTree(ok.chats.getLast()).path("messages").get(1).path("content").asString()).contains("could not tell which metadata");
    }

    @Test
    @DisplayName("AES-D15 서버 맥락 : 결과 원문은 24,000자 안이지만 요청 입력 추정 상한 초과 → PARTIAL(INPUT_BUDGET_EXCEEDED) / 구조화 중 권한 상실 → PROJECT_ACCESS_DENIED")
    void serverContextInputBudgetAndAccess() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-sc-in", false);
        TableInfo kor = bigTable(x, "TB_AES_SC_KOR", "한글업무설명".repeat(50), 14_000);
        ScriptedOllama ai = noToolsModel();
        ai.then(answer(MODEL, "테이블만 확인했습니다.", List.of(evidence(EvidenceType.TABLE, kor.getId(), "TB_AES_SC_KOR", null))));
        AiMetadataQueryDto.QueryResult r = queries.query(user(x), new AiMetadataQueryDto.QueryRequest("TB_AES_SC_KOR 테이블의 컬럼을 알려줘"));
        assertThat(r.completion()).isEqualTo(new AiMetadataQueryDto.Completion(CompletionStatus.PARTIAL, TerminationReason.INPUT_BUDGET_EXCEEDED, 2));
        assertThat(JSON.readTree(ai.chats.getFirst()).path("messages").get(1).path("content").asString()).contains("only the first 1 looked-up results", "input token budget");
        assertThat(statuses(r)).containsExactly(EvidenceVerifier.Status.VERIFIED);

        LogicalD35Fixture y = LogicalD35Fixture.create(context, "aes-sc-acc", false);
        bigTable(y, "TB_AES_SC_ACC", "설명", 0);
        ScriptedOllama lost = noToolsModel();
        lost.then(answer(MODEL, "보내지 않아야 하는 응답"), () -> revoke(y));
        assertThatThrownBy(() -> queries.query(user(y), new AiMetadataQueryDto.QueryRequest("TB_AES_SC_ACC 테이블의 컬럼을 알려줘")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED));
    }

    // ------------------------------------------------------------------ Provider 옵션 (3차)

    @Test
    @DisplayName("thinking 끄기 거부 : 생각 기능을 알려 준 모델이 구조화 요청(think=false)을 400 으로 거절 → 기존 AI 호출 실패 그대로 (숨기지 않고 자동 재호출 없음)")
    void thinkDisableRejected() throws Exception {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "aes-think-reject", false);
        ScriptedOllama ai = ollama();
        ai.then(toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", x.person))).then(raw(MODEL, "개인고객입니다."))
                .then(400, "{\"error\":\"think value \\\"false\\\" is not supported for this model\"}", 0)
                .then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(user(x), new AiMetadataQueryDto.QueryRequest("개인고객?")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_CALL_FAILED));
        assertThat(ai.chats).hasSize(3);
        assertThat(thinkOff(ai.chats.get(2))).isTrue();
    }

    @Test
    @DisplayName("동시 질의 옵션 격리 : 생각 기능 모델(시스템 기본)과 생각 기능 없는 모델(프로젝트 지정)로 두 질의를 동시에 — 각자 구조화 요청에만 think=false / 없음, Tool 단계는 think 없음, "
            + "사용량·근거·completion 은 질의마다 따로")
    void concurrentQueriesKeepOptions() throws Exception {
        LogicalD35Fixture a = LogicalD35Fixture.create(context, "aes-conc-a", false);
        LogicalD35Fixture b = LogicalD35Fixture.create(context, "aes-conc-b", false);
        ScriptedOllama thinking = ollama();          // 시스템 기본
        ScriptedOllama plain = new ScriptedOllama();
        servers.add(plain);
        plain.show(List.of("completion", "tools"), 131_072);
        AiProviderDto.Provider other = providers.create(new ProviderRequest("AES " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", plain.url(), MODEL, true, null));
        createdProviders.add(other.id());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", plain.port(), true, "AES 테스트")).id());
        defaults.setProject(b.project(), new AiProviderDto.ProjectProviderRequest(other.id(), null));
        projectSettings.add(b.project());
        for (ScriptedOllama server : List.of(thinking, plain)) {
            LogicalD35Fixture f = server == thinking ? a : b;
            server.script.add(new ScriptedOllama.Reply(200, toolCalls(MODEL, call("getLogicalEntityDetail", "entityId", f.person)), 300, null));
            server.script.add(new ScriptedOllama.Reply(200, raw(MODEL, "개인고객입니다."), 300, null));
            server.then(answer(MODEL, "개인고객입니다.", List.of(evidence(EvidenceType.LOGICAL_ENTITY, f.person, "개인고객", null))));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<AiMetadataQueryDto.QueryResult> ra = pool.submit(() -> queries.query(user(a), new AiMetadataQueryDto.QueryRequest("개인고객?")));
            java.util.concurrent.Future<AiMetadataQueryDto.QueryResult> rb = pool.submit(() -> queries.query(user(b), new AiMetadataQueryDto.QueryRequest("개인고객?")));
            AiMetadataQueryDto.QueryResult resultA = ra.get(60, java.util.concurrent.TimeUnit.SECONDS);
            AiMetadataQueryDto.QueryResult resultB = rb.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(thinking.chats).hasSize(3);
            assertThat(plain.chats).hasSize(3);
            assertThat(thinking.chats.subList(0, 2)).allSatisfy(body -> assertThat(JSON.readTree(body).has("think")).isFalse());
            assertThat(thinkOff(thinking.chats.get(2))).isTrue();
            assertThat(plain.chats).allSatisfy(body -> assertThat(JSON.readTree(body).has("think")).isFalse());
            assertThat(resultA.completion().status()).isEqualTo(CompletionStatus.COMPLETE);
            assertThat(resultB.completion().status()).isEqualTo(CompletionStatus.COMPLETE);
            assertThat(resultA.usage().modelCalls()).isEqualTo(3);
            assertThat(resultB.usage().modelCalls()).isEqualTo(3);
            assertThat(resultA.evidence()).extracting(EvidenceVerifier.Result::id).containsExactly(a.person);
            assertThat(resultB.evidence()).extracting(EvidenceVerifier.Result::id).containsExactly(b.person);
            assertThat(statuses(resultA)).containsExactly(EvidenceVerifier.Status.VERIFIED);
            assertThat(statuses(resultB)).containsExactly(EvidenceVerifier.Status.VERIFIED);
        } finally {
            pool.shutdownNow();
        }
    }
}
