package com.lexorma.ai.tool;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.ai.evidence.EvidenceCatalog;
import com.lexorma.ai.evidence.EvidenceType;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메타데이터 질의 한 번의 서버 쪽 상태 (AI-4). Spring AI {@link ToolContext} 로 Tool 에 넘긴다 — 모델에게 보내지 않고, 모델이 바꿀 수 없다.
 * <ul>
 *   <li>조회 범위 : 서버가 만든 {@link ExecutionContext} 의 프로젝트 하나. Tool 인자에는 프로젝트·계정이 없다</li>
 *   <li>실제로 실행된 Tool 기록 : 응답의 "사용한 Tool" 은 모델의 주장이 아니라 이 기록이다</li>
 *   <li>마감 시각 : 이 시각이 지나면 Tool 을 더 실행하지 않는다 (Tool 호출 횟수 상한과 함께 한 질의의 전체 시간을 제한한다)</li>
 *   <li>조회 방식 ({@link Mode}) : 모델의 Tool 호출 또는 (Tool Calling 미지원 모델) 서버가 정한 조회</li>
 *   <li>근거 후보 (AI-4 후속) : Tool 이 실제로 돌려준 메타데이터의 그 시점 스냅숏(이름·소속·버전). 답변의 근거는 여기에 있는 것만 "조회한 근거" 로 본다</li>
 *   <li>Tool 결과 원문 : 구조화 응답을 다시 요청할 때 Tool 을 다시 실행하지 않고 넘긴다 (이 질의 안에서만, 저장하지 않는다)</li>
 * </ul>
 * 전역 저장소(ThreadLocal 등)에 두지 않고 요청마다 만든다.
 * <p>
 * AI 공통 실행 안정성 2차 (docs/completed/ai-execution-stability.md) : Tool 실행마다 그 직전의 근거 기록 위치를 함께 남기고({@link #executed}),
 * AI 서버가 응답한 요청에 실린 Tool 응답 수로 모델에게 전달된 범위를 센다({@link #delivered}). 부분 답변은 전달된 결과의 앞부분만 쓰고,
 * 쓰지 않는 결과가 남긴 근거는 {@link #limitEvidenceTo} 로 지운다 (같은 대상을 앞 결과가 남겼으면 그대로). 이 상태는 질의 안에서만 둔다.
 */
public final class MetadataToolSession {

    /** ToolContext 의 키 */
    public static final String KEY = "lexorma.ai.metadata-tool-session";

    public enum Status {
        /** 조회했다 (결과가 비었을 수 있다) */
        OK,
        /** 현재 프로젝트에 그 대상이 없다 (다른 프로젝트의 ID 도 여기) */
        NOT_FOUND,
        /** 인자가 형식·범위에 맞지 않아 조회하지 않았다 */
        INVALID_ARGUMENT,
        /** 허용 목록에 없는 Tool 이름이라 아무것도 실행하지 않았다 */
        REJECTED,
        /** 조회 중 오류 (내용은 내보내지 않는다) */
        FAILED
    }

    /**
     * 메타데이터를 누가 골라 조회했는가 (응답·근거 출처) : 모델이 Tool 을 불렀다 / Tool Calling 을 지원하지 않는 모델이라 서버가 질문에서 정해 조회했다.
     * 한 질의는 둘 중 하나다 (서버 맥락은 Tool 을 하나도 실행하지 않았을 때만 시작한다)
     */
    public enum Mode { TOOL_CALLING, SERVER_CONTEXT }

    /** 실행된 Tool 한 번 (인자·결과 원문은 남기지 않는다) */
    public record Call(String name, Status status, long durationMillis) {
    }

    private final ExecutionContext context;
    private final String requestId;
    private final ToolScope toolScope;
    private final long deadlineNanos;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, EvidenceCatalog.Snapshot> seen = new ConcurrentHashMap<>();
    /** 설명을 실제로 돌려준 논리 속성 (D35-FINAL D35-D25) : 키 → 그때의 버전·설명 등록 여부. 설명을 뺀 응답·실패한 호출은 기록하지 않는다 */
    private final Map<String, DescriptionView> described = new ConcurrentHashMap<>();
    private final List<ToolResult> results = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.TOOL_CALLING;
    /** 실행 순서대로 Tool 실행 한 번마다 : 그 전의 결과 수, 결과를 남겼는지, 그 직전의 근거 기록 위치 */
    private final List<Invocation> invocations = new ArrayList<>();
    /** AI 서버가 응답한 요청에 실린 Tool 응답 수 (실행 순서의 앞에서부터) */
    private int deliveredInvocations;
    /** Spring AI 호출 상한에 걸린 종류 (걸리지 않았으면 null) */
    private volatile ToolLimit toolLimit;

    /** Tool 호출 상한의 종류 : 같은 Tool {@value MetadataToolset#MAX_CALLS_PER_TOOL}번 / 전체 {@value MetadataToolset#MAX_TOOL_CALLS}번 */
    public enum ToolLimit { SAME_TOOL, TOTAL }

    private record Invocation(int resultsBefore, boolean hasResult, EvidenceCheckpoint before) {
    }

    /** 실행된 Tool 의 결과 원문 (모델에게 보낸 그대로) */
    public record ToolResult(String name, String result) {
    }

    public MetadataToolSession(ExecutionContext context, String requestId, long timeoutMillis) {
        this(context, requestId, timeoutMillis, ToolScope.METADATA);
    }

    /** @param toolScope 이 질의의 기능 범위 (AI-7) — 질의 동안 바뀌지 않는다. 범위 밖 Tool 이름을 거절할 때 쓸 수 있는 Tool 목록을 정한다 */
    public MetadataToolSession(ExecutionContext context, String requestId, long timeoutMillis, ToolScope toolScope) {
        if (context == null) throw new IllegalArgumentException("실행 컨텍스트가 없습니다.");
        if (toolScope == null) throw new IllegalArgumentException("기능 범위가 없습니다.");
        this.context = context;
        this.requestId = requestId;
        this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000;
        this.toolScope = toolScope;
    }

    /** 이 질의의 기능 범위 (AI-7) */
    public ToolScope toolScope() {
        return toolScope;
    }

    /** Spring AI 의 Tool 실행 컨텍스트에서 꺼낸다. 없으면(질의 서비스를 거치지 않은 직접 실행) 실행하지 않는다 */
    public static MetadataToolSession from(ToolContext toolContext) {
        Object session = toolContext == null ? null : toolContext.getContext().get(KEY);
        if (session instanceof MetadataToolSession found) return found;
        throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    public Map<String, Object> toolContext() {
        return Map.of(KEY, this);
    }

    public ExecutionContext context() {
        return context;
    }

    public String requestId() {
        return requestId;
    }

    public Mode mode() {
        return mode;
    }

    /** 서버 맥락 조회로 바꾼다 : 아직 아무 Tool 도 실행하지 않았을 때만 */
    public void serverContext() {
        if (!calls.isEmpty()) throw new IllegalStateException("이미 Tool 을 실행한 질의는 서버 맥락으로 바꿀 수 없습니다.");
        mode = Mode.SERVER_CONTEXT;
    }

    public boolean expired() {
        return System.nanoTime() - deadlineNanos > 0;
    }

    /**
     * 기존 조회 서비스에 넘길 조회 범위. 서비스는 {@code getProjectId()} 로 조회를 프로젝트에 묶는다 (로그인 세션이 없는 배치에서도 같다).
     * 프로젝트·계정은 서버가 만든 실행 컨텍스트에서만 온다
     */
    public AccountDto scope() {
        return AccountDto.builder()
                .id(context.accountId() == null ? null : String.valueOf(context.accountId()))
                .projectId(context.projectId())
                .build();
    }

    /**
     * Tool 실행 한 번 (허용 목록의 Tool 이 실행 경계를 지났거나, 목록 밖 이름을 거절했다) : 기록 · 결과 원문(거절은 null) · 그 직전의 근거 기록 위치를 함께 남긴다
     */
    synchronized void executed(String name, Status status, long durationMillis, String result, EvidenceCheckpoint before) {
        invocations.add(new Invocation(results.size(), result != null, before));
        calls.add(new Call(name, status, durationMillis));
        if (result != null) results.add(new ToolResult(name, result));
    }

    /**
     * 모델에게 전달된 범위 (AES-D04) : AI 서버가 응답을 돌려준 요청에 실린 Tool 응답 수(대화 전체). Spring AI 는 실행 순서대로 Tool 응답을 대화에 붙이므로
     * 실행 순서의 앞에서부터 이만큼이 전달됐다. 줄지 않는다
     */
    public synchronized void delivered(int toolResponses) {
        deliveredInvocations = Math.max(deliveredInvocations, Math.min(toolResponses, invocations.size()));
    }

    /** 모델에게 전달이 확인된 Tool 결과 수 (실행 순서의 앞에서부터. 거절한 이름은 결과가 없어 세지 않는다) */
    public synchronized int deliveredResults() {
        int count = 0;
        for (int i = 0; i < deliveredInvocations; i++) if (invocations.get(i).hasResult()) count++;
        return count;
    }

    /**
     * 최종 답변에 쓰는 결과를 앞의 count 개로 줄인다 : 그 뒤 결과의 실행 직전 근거 위치로 되돌린다 — 뒤 결과에만 있던 대상·설명 기록은 지우고,
     * 앞 결과가 이미 남긴 같은 대상은 그대로 둔다 (D-35 {@link #discardSince} 재사용). count 가 결과 수 이상이면 아무것도 하지 않는다
     */
    public synchronized void limitEvidenceTo(int count) {
        for (Invocation invocation : invocations) {
            if (invocation.hasResult() && invocation.resultsBefore() == count) {
                discardSince(invocation.before());
                return;
            }
        }
    }

    /** Spring AI 호출 상한에 걸렸다 (처음 것만 남긴다) */
    public void toolLimitReached(ToolLimit limit) {
        if (toolLimit == null) toolLimit = limit;
    }

    public ToolLimit toolLimit() {
        return toolLimit;
    }

    /**
     * 실제 실행 경로에 들어간 Tool 호출 수 (응답의 completion.executedTools) : 성공·실패·결과 상한 초과 포함, 허용 목록 밖 이름의 거절과 호출 상한으로 실행 전에 막힌 호출은 뺀다.
     * Spring AI 호출 상한 계산(대화의 Tool 응답 수)과는 다른 값이다
     */
    public int executedTools() {
        synchronized (calls) {
            return (int) calls.stream().filter(c -> c.status() != Status.REJECTED).count();
        }
    }

    /** Tool 이 돌려준 메타데이터의 그 시점 스냅숏을 남긴다 (같은 대상은 처음 본 것을 둔다 : 질의 중의 변경은 검증에서 드러난다) */
    public void saw(Collection<EvidenceCatalog.Snapshot> snapshots) {
        for (EvidenceCatalog.Snapshot snapshot : snapshots) {
            if (context.projectId().equals(snapshot.projectId())) seen.putIfAbsent(key(snapshot.type(), snapshot.id()), snapshot);
        }
    }

    /**
     * 속성 설명을 모델에게 돌려준 기록 (D35-D25). version = 돌려준 때 그 속성 행의 버전, registered = 설명이 등록돼 있었는지 (없으면 '설명 없음' 을 확인한 것).
     * 같은 속성을 다시 돌려주면 최신 기록으로 바꾼다 (버전이 다른 두 기록을 합치지 않는다)
     */
    public record DescriptionView(java.time.LocalDateTime version, boolean registered) {
    }

    /** 근거 기록의 지금 위치 : 대상 키와 설명 기록 (실패한 호출 뒤 되돌리는 데 쓴다) */
    public record EvidenceCheckpoint(java.util.Set<String> seen, Map<String, DescriptionView> described) {
    }

    /**
     * 근거 기록의 지금 위치 (D35-3b) : Tool 하나를 실행하기 전에 잡아 두고, 그 Tool 의 결과가 모델에게 가지 않으면(결과 상한 초과·조회 오류) 그 사이에 더한 근거를
     * {@link #discardSince} 로 지운다 — 돌려주지 않은 결과의 대상이 근거로 인정되지 않게 한다. 그 전의 성공한 호출이 남긴 근거는 같은 대상이어도 그대로다
     */
    public EvidenceCheckpoint evidenceCheckpoint() {
        return new EvidenceCheckpoint(java.util.Set.copyOf(seen.keySet()), Map.copyOf(described));
    }

    /** checkpoint 뒤에 기록한 근거를 지우고 설명 기록을 그때로 되돌린다 (그 전에 다른 Tool 결과로 기록된 근거는 그대로) */
    public void discardSince(EvidenceCheckpoint checkpoint) {
        seen.keySet().retainAll(checkpoint.seen());
        described.clear();
        described.putAll(checkpoint.described());
    }

    /** 속성 설명을 돌려줬다 (includeAttributeDescriptions 로 실제 응답에 실은 속성만, 이 프로젝트의 것만) */
    public void sawDescriptions(Collection<EvidenceCatalog.Snapshot> snapshots, java.util.Set<Long> registered) {
        for (EvidenceCatalog.Snapshot snapshot : snapshots) {
            if (!context.projectId().equals(snapshot.projectId())) continue;
            described.put(key(snapshot.type(), snapshot.id()), new DescriptionView(snapshot.version(), registered.contains(snapshot.id())));
        }
    }

    /** 이 질의에서 그 속성의 설명을 돌려준 기록 (없으면 null — 설명을 조회하지 않았다) */
    public DescriptionView described(EvidenceType type, Long id) {
        return described.get(key(type, id));
    }

    /** 이 질의에서 Tool 이 돌려준 근거 후보 (없으면 null) */
    public EvidenceCatalog.Snapshot seen(EvidenceType type, Long id) {
        return seen.get(key(type, id));
    }

    public List<ToolResult> results() {
        synchronized (results) {
            return List.copyOf(results);
        }
    }

    private static String key(EvidenceType type, Long id) {
        return type + ":" + id;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }
}
