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
    private final long deadlineNanos;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, EvidenceCatalog.Snapshot> seen = new ConcurrentHashMap<>();
    private final List<ToolResult> results = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.TOOL_CALLING;

    /** 실행된 Tool 의 결과 원문 (모델에게 보낸 그대로) */
    public record ToolResult(String name, String result) {
    }

    public MetadataToolSession(ExecutionContext context, String requestId, long timeoutMillis) {
        if (context == null) throw new IllegalArgumentException("실행 컨텍스트가 없습니다.");
        this.context = context;
        this.requestId = requestId;
        this.deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000;
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

    void record(String name, Status status, long durationMillis) {
        calls.add(new Call(name, status, durationMillis));
    }

    /** Tool 이 돌려준 메타데이터의 그 시점 스냅숏을 남긴다 (같은 대상은 처음 본 것을 둔다 : 질의 중의 변경은 검증에서 드러난다) */
    public void saw(Collection<EvidenceCatalog.Snapshot> snapshots) {
        for (EvidenceCatalog.Snapshot snapshot : snapshots) {
            if (context.projectId().equals(snapshot.projectId())) seen.putIfAbsent(key(snapshot.type(), snapshot.id()), snapshot);
        }
    }

    /** 이 질의에서 Tool 이 돌려준 근거 후보 (없으면 null) */
    public EvidenceCatalog.Snapshot seen(EvidenceType type, Long id) {
        return seen.get(key(type, id));
    }

    void result(String name, String result) {
        results.add(new ToolResult(name, result));
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
