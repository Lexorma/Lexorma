package com.lexorma.source.collection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.CollectionType;
import com.lexorma.source.collection.repository.SourceCollectionExecutionRepository;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.tool.SourceMetadata;
import com.lexorma.source.tool.SourceMetadata.Owned;
import com.lexorma.source.tool.SourceToolExecutor;
import com.lexorma.source.tool.SourceToolRegistry;
import com.lexorma.source.tool.SourceToolSession;
import com.lexorma.source.tool.SourceTools;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 원본 카탈로그 수동 최신화 (AI-2b) : 원본 DB 를 읽어 AI-1c 의 수집 실행으로 반영한다. 적재·버전·이력은 {@link SourceCollectionService} 그대로다.
 * <pre>
 * 요청 (사용자 세션)      권한·연결 확인 → start (실행 RUNNING, 작업 ID = 서버가 만든 UUID) → 작업 대기열에 넣고 202
 * 작업 (수집 스레드)      SourceToolExecutor.session : 실행 컨텍스트 재검증 → 원본 DB 접속(읽기 전용) → 스키마·테이블·컬럼·제약조건·인덱스·관계 도구를 차례로
 *                        → 세션 종료(연결 닫음) → apply (한 트랜잭션) / 실패하면 fail
 * 그동안               살아 있음 확인(heartbeat, 짧은 트랜잭션), 전체 제한 시간, 취소 : 멈추면 실행 중인 조회를 취소하고 연결을 끊는다
 * </pre>
 * <ul>
 *   <li>작업은 요청자의 비동기 실행 컨텍스트(ASYNC : 요청자·프로젝트·작업 ID)로 돈다. 관리자 권한으로 바꾸지 않는다 : 세션·반영 직전마다 권한을 다시 본다</li>
 *   <li>Lexorma DB 의 긴 트랜잭션이 없다 : 원본을 읽는 동안 Lexorma 쪽에서 열려 있는 것은 heartbeat 의 짧은 갱신뿐이다</li>
 *   <li>원본을 모두 읽은 스키마만 COMPLETED 로 넘긴다. 조회가 하나라도 실패하면 결과를 반영하지 않는다 (부분 반영 없음, 자동 재시도 없음)</li>
 *   <li>작업 스레드 {@code lexorma.source.collection.workers}(기본 2), 대기열 {@code queue-capacity}(기본 10). 가득 차면 실행을 FAILED(SOURCE_COLLECTION_REJECTED)로 닫고 503</li>
 * </ul>
 */
@Service
public class SourceRefreshService {

    private static final Logger log = LoggerFactory.getLogger(SourceRefreshService.class);

    private final SourceCollectionService collections;
    private final SourceToolExecutor tools;
    private final SourceToolRegistry registry;
    private final SourceConnectionRepository connectionRepository;
    private final SourceCollectionExecutionRepository executionRepository;
    private final Duration timeout;
    private final Duration heartbeat;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timers;
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();

    public SourceRefreshService(SourceCollectionService collections, SourceToolExecutor tools, SourceToolRegistry registry, SourceConnectionRepository connectionRepository,
                                SourceCollectionExecutionRepository executionRepository,
                                @Value("${lexorma.source.collection.timeout:PT10M}") Duration timeout,
                                @Value("${lexorma.source.collection.heartbeat-interval:PT30S}") Duration heartbeat,
                                @Value("${lexorma.source.collection.workers:2}") int workers,
                                @Value("${lexorma.source.collection.queue-capacity:10}") int queueCapacity) {
        this.collections = collections;
        this.tools = tools;
        this.registry = registry;
        this.connectionRepository = connectionRepository;
        this.executionRepository = executionRepository;
        this.timeout = timeout;
        this.heartbeat = heartbeat;
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), runnable -> {
            Thread thread = new Thread(runnable, "source-collection-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "source-collection-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 202 응답 : 진행 상태는 기존 수집 실행 조회 API 로 본다 */
    public record Accepted(Long executionId, String jobId, String status) {
    }

    /**
     * 최신화 요청을 받는다. 같은 연결의 수집이 진행 중이면 SOURCE_COLLECTION_IN_PROGRESS (start 의 규칙 그대로).
     *
     * @param schemas PARTIAL 의 대상 스키마 (원본 이름 그대로). FULL 은 비운다
     */
    public Accepted refresh(AccountDto accountDto, Long connectionId, CollectionType type, List<String> schemas) {
        ExecutionContext requester = ExecutionContext.user(accountDto);
        // 이 DBMS 의 수집 도구가 없으면 실행을 만들지 않는다
        connectionRepository.findByIdAndProjectId(connectionId, requester.projectId()).ifPresent(connection -> {
            if (registry.find(SourceTools.SCHEMA_DISCOVERY, connection.getDbType()).isEmpty()) {
                throw new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "이 DB 유형은 아직 수집할 수 없습니다 : {0}", connection.getDbType().name());
            }
        });
        List<SourceCollectionSpec.Namespace> namespaces = schemas == null ? List.of()
                : schemas.stream().map(schema -> new SourceCollectionSpec.Namespace(null, schema == null ? null : Name.of(schema))).toList();
        ExecutionContext context = ExecutionContext.async(requester.projectId(), requester.accountId(), UUID.randomUUID().toString());
        SourceCollectionDto.Execution execution = collections.start(context, connectionId, new SourceCollectionSpec.Request(type, namespaces));
        Job job = new Job(context, connectionId, execution.id(), type, namespaces);
        jobs.put(execution.id(), job);
        try {
            workers.execute(job);
        } catch (RejectedExecutionException e) {
            jobs.remove(execution.id());
            log.warn("원본 카탈로그 수집 작업을 받지 못했습니다 (대기열 가득 참 또는 종료 중) : executionId={}", execution.id());
            safely(() -> collections.fail(context, context.jobId(), ErrorCode.SOURCE_COLLECTION_REJECTED.name()));
            throw new CustomException(ErrorCode.SOURCE_COLLECTION_REJECTED);
        }
        return new Accepted(execution.id(), context.jobId(), execution.status());
    }

    /** 수집을 취소한다 : 실행을 CANCELLED 로 남기고, 이 서버에서 돌고 있으면 원본 조회를 멈춘다 (다른 서버의 작업은 다음 heartbeat 에서 멈춘다) */
    public SourceCollectionDto.Execution cancel(AccountDto accountDto, Long executionId) {
        ExecutionContext requester = ExecutionContext.user(accountDto);
        SourceCollectionExecution execution = executionRepository.findByIdAndProjectId(executionId, requester.projectId())
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        SourceCollectionDto.Execution cancelled = collections.cancel(requester, execution.getJobId());
        Job job = jobs.get(executionId);
        if (job != null) job.abort(ErrorCode.SOURCE_CANCELLED);
        return cancelled;
    }

    /** 서버 종료 : 새 작업을 받지 않고, 돌고 있는 작업의 원본 조회를 멈춘다 (작업은 실행을 FAILED 로 남기고 끝난다) */
    @PreDestroy
    void shutdown() {
        workers.shutdown();
        jobs.values().forEach(job -> job.abort(ErrorCode.SOURCE_COLLECTION_ABANDONED));
        try {
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        timers.shutdownNow();
    }

    /** 수집 작업 하나 */
    private final class Job implements Runnable {

        private final ExecutionContext context;
        private final Long connectionId;
        private final Long executionId;
        private final CollectionType type;
        private final List<SourceCollectionSpec.Namespace> namespaces;
        private final AtomicReference<ErrorCode> abortReason = new AtomicReference<>();
        private volatile SourceToolSession session;

        Job(ExecutionContext context, Long connectionId, Long executionId, CollectionType type, List<SourceCollectionSpec.Namespace> namespaces) {
            this.context = context;
            this.connectionId = connectionId;
            this.executionId = executionId;
            this.type = type;
            this.namespaces = namespaces;
        }

        void abort(ErrorCode reason) {
            abortReason.compareAndSet(null, reason);
            SourceToolSession current = session;
            if (current != null) current.abort(abortReason.get());
        }

        private void attach(SourceToolSession opened) {
            session = opened;
            if (abortReason.get() != null) opened.abort(abortReason.get());
        }

        @Override
        public void run() {
            long started = System.nanoTime();
            ScheduledFuture<?> deadline = null;
            ScheduledFuture<?> beat = null;
            try {
                deadline = timers.schedule(() -> abort(ErrorCode.SOURCE_COLLECTION_TIMEOUT), timeout.toMillis(), TimeUnit.MILLISECONDS);
                beat = timers.scheduleWithFixedDelay(this::beat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
                int[] statements = new int[1];
                SourceCollectionSpec.Result result = tools.session(context, connectionId, opened -> {
                    attach(opened);
                    SourceCollectionSpec.Result collected = collect(opened);
                    statements[0] = opened.statementCount();
                    return collected;
                });
                // 여기서 원본 연결은 닫혀 있다. 그 사이에 멈추라는 알림이 왔으면 반영하지 않는다
                if (abortReason.get() != null) throw new CustomException(abortReason.get());
                long read = System.nanoTime();
                SourceCollectionDto.Execution applied = collections.apply(context, context.jobId(), result);
                log.info("원본 카탈로그 수집 완료 : executionId={}, tables={}, columns={}, statements={}, readMs={}, applyMs={}, versions={}, changes={}", executionId,
                        applied.tableCount(), applied.columnCount(), statements[0], (read - started) / 1_000_000, (System.nanoTime() - read) / 1_000_000,
                        applied.versionCount(), applied.changeCount());
                diagnoseDrop(applied);
            } catch (CustomException e) {
                ErrorCode code = abortReason.get() != null ? abortReason.get() : e.getErrorCode();
                log.warn("원본 카탈로그 수집 실패 : executionId={}, code={}", executionId, code);
                safely(() -> collections.fail(context, context.jobId(), code.name()));
            } catch (RuntimeException e) {
                ErrorCode reason = abortReason.get();
                log.error("원본 카탈로그 수집 중 예상하지 못한 오류 : executionId={}, cause={}", executionId, e.getClass().getSimpleName());
                safely(() -> collections.fail(context, context.jobId(), reason != null ? reason.name() : "UNEXPECTED_ERROR"));
            } finally {
                if (deadline != null) deadline.cancel(false);
                if (beat != null) beat.cancel(false);
                jobs.remove(executionId);
            }
        }

        /** 살아 있음 확인. 실행이 끝났거나(다른 곳에서 취소·정리) 없어졌으면(연결 삭제) 원본 조회를 멈춘다 */
        private void beat() {
            try {
                if (!collections.heartbeat(context, context.jobId())) abort(ErrorCode.SOURCE_CANCELLED);
            } catch (CustomException e) {
                abort(ErrorCode.SOURCE_CANCELLED);
            } catch (RuntimeException e) {
                log.warn("원본 카탈로그 수집의 살아 있음 확인 실패 : executionId={}, cause={}", executionId, e.getClass().getSimpleName());
            }
        }

        /** 수집 범위의 스키마를 도구 7개로 읽어 수집 결과로 묶는다 (도구마다 조회 한 번) */
        private SourceCollectionSpec.Result collect(SourceToolSession opened) {
            List<String> discovered = opened.run(SourceTools.SCHEMA_DISCOVERY, null);
            // PARTIAL : 원본에 있는 스키마만 읽는다. 없는 스키마는 결과에서 빠져 NOT_FOUND 가 되고 실행 전체가 반영되지 않는다
            List<String> targets = type == CollectionType.FULL ? discovered
                    : namespaces.stream().map(namespace -> namespace.schema() == null ? null : namespace.schema().value()).filter(discovered::contains).distinct().toList();
            Map<String, Map<String, TableParts>> bySchema = new LinkedHashMap<>();
            for (String schema : targets) bySchema.put(schema, new LinkedHashMap<>());
            if (!targets.isEmpty()) {
                SourceMetadata.Scope scope = new SourceMetadata.Scope(targets);
                List<Owned<SourceCatalogSpec.Table>> tables = opened.run(SourceTools.TABLE_DISCOVERY, scope);
                for (Owned<SourceCatalogSpec.Table> table : tables) {
                    Map<String, TableParts> schema = bySchema.get(table.schema());
                    if (schema == null || schema.put(table.table(), new TableParts(table.item())) != null) throw changed();
                }
                List<Owned<SourceCatalogSpec.Column>> columns = opened.run(SourceTools.COLUMN_DISCOVERY, scope);
                columns.forEach(owned -> parts(bySchema, owned).columns.add(owned.item()));
                List<Owned<SourceCatalogSpec.Constraint>> constraints = opened.run(SourceTools.CONSTRAINT_DISCOVERY, scope);
                constraints.forEach(owned -> parts(bySchema, owned).constraints.add(owned.item()));
                List<Owned<SourceCatalogSpec.Index>> indexes = opened.run(SourceTools.INDEX_DISCOVERY, scope);
                indexes.forEach(owned -> parts(bySchema, owned).indexes.add(owned.item()));
                List<Owned<SourceCatalogSpec.Relation>> relations = opened.run(SourceTools.RELATIONSHIP_DISCOVERY, scope);
                relations.forEach(owned -> parts(bySchema, owned).relations.add(owned.item()));
            }
            opened.checkActive();
            List<SourceCollectionSpec.Schema> schemas = new ArrayList<>();
            bySchema.forEach((schema, tables) -> schemas.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema),
                    tables.values().stream().map(TableParts::spec).toList())));
            return new SourceCollectionSpec.Result(schemas);
        }

        /** 테이블 목록에 없는 테이블의 하위 객체 : 같은 스냅숏에서 읽었으므로 원본이 그사이 바뀐 것이다 */
        private TableParts parts(Map<String, Map<String, TableParts>> bySchema, Owned<?> owned) {
            Map<String, TableParts> schema = bySchema.get(owned.schema());
            TableParts parts = schema == null ? null : schema.get(owned.table());
            if (parts == null) throw changed();
            return parts;
        }

        /** 이전 같은 종류의 반영보다 객체가 크게 줄었으면 진단 로그만 남긴다 (차단하지 않는다 : 완결성은 수집 단계에서 확인했다) */
        private void diagnoseDrop(SourceCollectionDto.Execution applied) {
            if (applied.tableCount() == null || applied.columnCount() == null) return;
            executionRepository.findFirstByConnectionIdAndStatusAndCollectionTypeAndIdNotOrderByReflectedOrderDesc(connectionId, CollectionStatus.COMPLETED, type, executionId)
                    .filter(previous -> previous.getTableCount() != null && previous.getTableCount() > 0 && previous.getColumnCount() != null)
                    .filter(previous -> applied.tableCount() < previous.getTableCount() / 2 || applied.columnCount() < previous.getColumnCount() / 2)
                    .ifPresent(previous -> log.warn("원본 카탈로그 객체 급감 : executionId={}, tables {} → {}, columns {} → {}, tableDropRate={}%", executionId,
                            previous.getTableCount(), applied.tableCount(), previous.getColumnCount(), applied.columnCount(),
                            100 - applied.tableCount() * 100 / previous.getTableCount()));
        }
    }

    private static final class TableParts {
        private final SourceCatalogSpec.Table table;
        private final List<SourceCatalogSpec.Column> columns = new ArrayList<>();
        private final List<SourceCatalogSpec.Constraint> constraints = new ArrayList<>();
        private final List<SourceCatalogSpec.Index> indexes = new ArrayList<>();
        private final List<SourceCatalogSpec.Relation> relations = new ArrayList<>();

        TableParts(SourceCatalogSpec.Table table) {
            this.table = table;
        }

        SourceCollectionSpec.Table spec() {
            return new SourceCollectionSpec.Table(table, columns, constraints, indexes, relations);
        }
    }

    private static CustomException changed() {
        return new CustomException(ErrorCode.SOURCE_CHANGED_DURING_COLLECTION);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("원본 카탈로그 수집 상태를 기록하지 못했습니다 : cause={}", e.getClass().getSimpleName());
        }
    }
}
