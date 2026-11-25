package com.lexorma.ai.tool;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.ai.tool.MetadataToolSession.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI-4 Tool 등록 범위와 실행 경계. 질의마다 {@link #callbacks()} 와 {@link #advisor()} 를 ChatClient 요청에 넣는다.
 * Tool 호출 프로토콜·반복 실행은 Spring AI({@link ToolCallingAdvisor}, {@link DefaultToolCallingManager})가 하고, 여기서는 다음만 더한다.
 * <ul>
 *   <li><b>허용 목록</b> : {@link MetadataTools#callbacks()} 의 13개만 (D-35 D35-3b 논리 모델 3개 포함) ({@link #NAMES}). 목록에 없는 이름을 모델이 부르면 아무것도 실행하지 않고 거절을 돌려준다</li>
 *   <li><b>호출마다 재검증</b> : {@link ExecutionContextVerifier} 로 프로젝트 상태·요청자의 멤버십·현재 역할(VIEWER 이상)을 DB 에서 다시 본다.
 *       실패하면 질의 전체를 끝낸다 (USER·ASYNC·SCHEDULED 모두 같은 경로, 프로젝트는 실행 컨텍스트에서만 온다)</li>
 *   <li><b>읽기 전용 트랜잭션</b> : 조회를 readOnly 트랜잭션에서 한다 (Hibernate flush 없음, PostgreSQL 은 DB 가 쓰기를 거절한다)</li>
 *   <li><b>상한</b> : 한 질의의 Tool 호출 {@value #MAX_TOOL_CALLS}번 (같은 Tool {@value #MAX_CALLS_PER_TOOL}번), 전체 {@value #TIMEOUT_SECONDS}초,
 *       Tool 결과 {@value #RESULT_MAX_CHARS}자. 횟수는 Spring AI 의 Tool 호출 상한(THROW)을 쓴다</li>
 *   <li><b>기록</b> : 실행된 Tool 이름·결과 상태·시간 (인자·결과 원문은 남기지 않는다)</li>
 * </ul>
 */
@Slf4j
@Component
public class MetadataToolset {

    static final int MAX_TOOL_CALLS = 8;
    static final int MAX_CALLS_PER_TOOL = 4;
    public static final int TIMEOUT_SECONDS = 180;
    static final int RESULT_MAX_CHARS = 24_000;
    /** Tool 은 VIEWER 가 화면에서 보는 메타데이터만 읽는다 (조회 API 와 같은 역할) */
    static final String REQUIRED_ROLE = "VIEWER";

    /** 등록하는 Tool (읽기 전용). 바꾸면 MetadataToolIntegrationTest 의 허용 목록 고정도 바꾼다 */
    public static final Set<String> NAMES = Set.of("listSchemas", "searchTables", "getTableDetail", "getTableColumns", "getTableRelations",
            "searchStandardTerms", "searchStandardWords", "searchStandardDomains", "findSimilarColumns", "getMetadataHistory",
            "getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail");

    private final ExecutionContextVerifier verifier;
    private final TransactionTemplate readOnly;
    private final List<ToolCallback> callbacks;
    private final ToolCallingAdvisor advisor;

    public MetadataToolset(MetadataTools tools, ExecutionContextVerifier verifier, PlatformTransactionManager transactionManager) {
        this.verifier = verifier;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(30);
        this.callbacks = tools.callbacks().stream().map(callback -> (ToolCallback) new Guarded(callback)).toList();
        Set<String> registered = callbacks.stream().map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
        if (!registered.equals(NAMES)) throw new IllegalStateException("AI Tool 목록이 허용 목록과 다릅니다 : " + registered);
        DefaultToolCallingManager manager = DefaultToolCallingManager.builder()
                .maxTotalToolCalls(MAX_TOOL_CALLS)
                .maxCallsPerTool(MAX_CALLS_PER_TOOL)
                .onLimitExceeded(ToolCallLimitBehavior.THROW)
                // 요청에 넣은 Tool 에 없는 이름 : 실행하지 않고 거절을 돌려준다 (Bean·메서드 이름으로 찾아 실행하는 경로 없음)
                .resolutionFallbackEnabled(true)
                .toolCallbackResolver(Rejected::new)
                .build();
        this.advisor = ToolCallingAdvisor.builder().toolCallingManager(new LimitAware(manager)).build();
    }

    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    public ToolCallingAdvisor advisor() {
        return advisor;
    }

    /**
     * 서버가 정한 조회 (Tool Calling 미지원 모델의 대체 흐름) : 허용 목록의 Tool 하나를 모델 없이 서버가 실행한다. 모델이 부를 때와 같은 경계
     * (실행 컨텍스트 재검증 · 읽기 전용 트랜잭션 · 결과 크기 · 기록 · 근거 스냅숏)를 지나고, 호출 횟수·마감 시각도 같은 상한을 쓴다.
     *
     * @return 모델에게 보낼 결과 (오류면 오류 JSON)
     */
    public String run(String name, Object input, MetadataToolSession session) {
        ToolCallback callback = callbacks.stream().filter(c -> c.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("허용 목록에 없는 Tool : " + name));
        if (session.calls().size() >= MAX_TOOL_CALLS) throw new CustomException(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED);
        return callback.call(JsonMapper.shared().writeValueAsString(input), new ToolContext(session.toolContext()));
    }

    /** 허용된 Tool 하나를 감싼다 : 실행 컨텍스트 재검증 → 읽기 전용 트랜잭션에서 실행 → 결과 크기 확인 → 기록 */
    private final class Guarded implements ToolCallback {
        private final ToolCallback delegate;

        Guarded(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            // ToolContext 없이(질의 서비스를 거치지 않고) 부르면 범위가 없으므로 실행하지 않는다
            throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            MetadataToolSession session = MetadataToolSession.from(toolContext);
            String name = getToolDefinition().name();
            if (session.expired()) throw new CustomException(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED);
            // 권한·범위 오류는 모델에게 돌려주지 않고 질의 전체를 끝낸다 (CustomException 은 Spring AI 를 지나 그대로 나간다)
            verifier.verify(session.context(), REQUIRED_ROLE);
            long started = System.nanoTime();
            Status status = Status.OK;
            String result;
            // 근거는 결과를 돌려줄 때만 남긴다 : 상한 초과·오류면 이 호출이 기록한 근거를 지운다 (D35-3b — 돌려주지 않은 대상을 근거로 인정하지 않는다)
            MetadataToolSession.EvidenceCheckpoint evidence = session.evidenceCheckpoint();
            try {
                result = readOnly.execute(tx -> delegate.call(toolInput, toolContext));
                if (result != null && result.length() > RESULT_MAX_CHARS) {
                    status = Status.FAILED;
                    result = tooLarge(name, result.length());
                }
            } catch (ToolExecutionException e) {
                status = status(e.getCause());
                result = error(status.name(), message(e.getCause()));
            } catch (RuntimeException e) {
                // 모델이 준 인자가 입력 형식(JSON·타입)에 맞지 않는다 : Spring AI 가 Tool 을 부르기 전에 실패한다. 그 밖의 예외는 그대로 나간다
                if (!jsonError(e)) throw e;
                status = Status.INVALID_ARGUMENT;
                result = error(status.name(), message(e));
            }
            if (status != Status.OK) session.discardSince(evidence);
            long millis = (System.nanoTime() - started) / 1_000_000;
            session.executed(name, status, millis, result, evidence);
            log.info("AI Tool 실행 : request={} project={} type={} tool={} status={} {}ms", session.requestId(), session.context().projectId(),
                    session.context().type(), name, status, millis);
            return result;
        }
    }

    /**
     * Spring AI 호출 관리자를 그대로 쓰고, 호출 상한(THROW)에 걸리면 그 종류(같은 Tool / 전체)만 질의 세션에 남긴다 (AI 공통 실행 안정성 2차 — 부분 답변의 종료 사유).
     * 상한·횟수 계산·예외 처리는 바꾸지 않는다 : 예외를 그대로 다시 던지고, Spring AI 가 예전처럼 상한 응답으로 반복을 끝낸다
     */
    private static final class LimitAware implements ToolCallingManager {
        private final ToolCallingManager delegate;

        LimitAware(ToolCallingManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return delegate.resolveToolDefinitions(chatOptions);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            try {
                return delegate.executeToolCalls(prompt, chatResponse);
            } catch (ToolCallLimitExceededException e) {
                if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null
                        && options.getToolContext().get(MetadataToolSession.KEY) instanceof MetadataToolSession session) {
                    session.toolLimitReached(e.getToolName() == null ? MetadataToolSession.ToolLimit.TOTAL : MetadataToolSession.ToolLimit.SAME_TOOL);
                }
                throw e;
            }
        }
    }

    /** 허용 목록에 없는 Tool 이름 : 실행하지 않는다. 모델에게는 거절과 쓸 수 있는 Tool 목록만 돌려준다 */
    private static final class Rejected implements ToolCallback {
        private final ToolDefinition definition;

        Rejected(String name) {
            String safe = name == null ? "" : name.length() > 64 ? name.substring(0, 64) : name;
            this.definition = ToolDefinition.builder().name(safe).description("rejected").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return error("UNKNOWN_TOOL", "Unknown tool. Available tools: " + String.join(", ", NAMES.stream().sorted().toList()));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            MetadataToolSession session = MetadataToolSession.from(toolContext);
            if (session.expired()) throw new CustomException(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED);
            session.executed(definition.name(), Status.REJECTED, 0, null, session.evidenceCheckpoint());
            log.info("AI Tool 거절 (허용 목록 밖) : request={} tool={}", session.requestId(), definition.name());
            return call(toolInput);
        }
    }

    private static boolean jsonError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException) return true;
        }
        return false;
    }

    private static Status status(Throwable cause) {
        if (jsonError(cause)) return Status.INVALID_ARGUMENT;
        if (cause instanceof CustomException custom) {
            ErrorCode code = custom.getErrorCode();
            if (code == ErrorCode.AI_QUERY_INVALID) return Status.INVALID_ARGUMENT;
            if (code.name().endsWith("_NOT_DEFINED") || code.name().endsWith("_NOT_FOUND")) return Status.NOT_FOUND;
        }
        return Status.FAILED;
    }

    /** 모델에게 돌려줄 오류 문구 : 업무 오류는 그 사유, 그 밖의 예외는 원문을 내지 않는다 */
    private static String message(Throwable cause) {
        if (jsonError(cause)) return "Tool arguments do not match the input schema of this tool.";
        if (cause instanceof CustomException custom) return custom.getCustomMessage();
        log.warn("AI Tool 조회 실패 : {}", cause == null ? null : cause.getClass().getName());
        return "Lookup failed.";
    }

    /**
     * 결과 상한 초과 (D35-D19) : 결과를 자르지 않고 오류로 돌려준다. 어떤 Tool 이 몇 자였는지와 범위를 줄이는 방법(Tool 별)을 함께 준다 — 오류 자체는 수백 자.
     * 같은 입력으로 다시 부르지 말라고 알린다
     */
    static String tooLarge(String tool, int chars) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "RESULT_TOO_LARGE");
        body.put("message", "The result was too large to send. Do not repeat the same call; narrow the request and try again.");
        body.put("tool", tool);
        body.put("resultChars", chars);
        body.put("maxChars", RESULT_MAX_CHARS);
        String hint = NARROWING.get(tool);
        if (hint != null) body.put("hint", hint);
        return JsonMapper.shared().writeValueAsString(body);
    }

    /** Tool 별 범위 줄이는 방법 (논리 모델 Tool — D35-3b) */
    private static final Map<String, String> NARROWING = Map.of(
            "getLogicalEntityDetail", "Call getLogicalEntityDetail again with section ATTRIBUTES, IDENTIFIERS, RELATIONS or INHERITANCE and a smaller limit "
                    + "(e.g. 20); leave includeAttributeDescriptions false unless the descriptions are needed.",
            "getLogicalModelOverview", "Call getLogicalModelOverview with section SUBJECT_AREAS or SUBTYPE_GROUPS and a smaller limit (e.g. 20).",
            "searchLogicalEntities", "Call searchLogicalEntities with a smaller limit, a keyword or a subjectAreaId.");

    private static String error(String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return JsonMapper.shared().writeValueAsString(body);
    }
}
