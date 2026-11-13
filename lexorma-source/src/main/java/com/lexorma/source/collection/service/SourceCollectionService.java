package com.lexorma.source.collection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.logs.util.SensitiveDataMasker;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.source.catalog.service.SourceCatalogRules;
import com.lexorma.source.catalog.service.SourceIdentifiers;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.entity.SourceCollectionTarget;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.CollectionType;
import com.lexorma.source.collection.domain.enums.TargetResult;
import com.lexorma.source.collection.repository.SourceCollectionExecutionRepository;
import com.lexorma.source.collection.repository.SourceCollectionTargetRepository;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 원본 카탈로그 수집 실행 (AI-1c). 실행을 세 단계로 나눈다 :
 * <pre>
 * 1. 등록 (start, 짧은 트랜잭션)   프로젝트·요청자 확인 → 프로젝트 → 연결 잠금 → 같은 연결의 진행 중 실행 확인 → 실행 RUNNING
 * 2. 결과 준비 (트랜잭션 밖)        (AI-2 : 원본 DB 조회) → 입력 검증·범위·완결성·정규화·해시 ({@link SourceCollectionPreparer})
 * 3. 반영 (apply, 한 트랜잭션)      프로젝트 → 연결 잠금 → 재확인 → 비교 → 현재 카탈로그·버전·변경 이력·완료 ({@link SourceCatalogLoader})
 *    반영에 실패하면 모두 되돌아가고, 별도 트랜잭션에서 실행을 FAILED 로 남긴다 (연결·프로젝트가 지워졌으면 남기지 않는다)
 * </pre>
 * 원본 DB 에 접속하지 않는다 (수집 도구는 AI-2). 결과를 받는 공개 API 도 없다 : 서버 안의 수집 경로와 테스트만 부른다.
 * <p>
 * <b>작업 식별</b> : 작업 아이디는 서버가 만든다 (비동기·배치는 서버가 만든 실행 컨텍스트의 작업 ID). 같은 작업을 다시 보내면 같은 실행이고(멱등),
 * 다른 요청자·다른 프로젝트·다른 작업 컨텍스트로는 그 실행을 찾을 수 없다.
 * <b>중복 실행</b> : 같은 연결에서 진행 중인 실행은 하나뿐이다 (실행 행의 진행 접속 일련번호 UNIQUE). 멈춘 실행은 그 실행을 맡은 서버가 이 서버이면서 지금 돌고 있지 않거나,
 * 다른 서버의 실행인데 살아 있음 확인이 {@code lexorma.source.collection.stale-after} 동안 없을 때만 정리한다.
 */
@Service
public class SourceCollectionService {

    private static final Logger log = LoggerFactory.getLogger(SourceCollectionService.class);
    private static final Set<CollectionStatus> IN_PROGRESS = EnumSet.of(CollectionStatus.CREATED, CollectionStatus.RUNNING);

    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;
    private final SourceConnectionRepository connectionRepository;
    private final SourceCollectionExecutionRepository executionRepository;
    private final SourceCollectionTargetRepository targetRepository;
    private final SourceCollectionPreparer preparer;
    private final SourceCatalogLoader loader;
    private final SourceCollectionInstance instance;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final Duration staleAfter;

    @PersistenceContext
    private EntityManager entityManager;

    public SourceCollectionService(ExecutionContextVerifier verifier, ProjectWriteGuard projectWriteGuard, SourceConnectionRepository connectionRepository,
                                   SourceCollectionExecutionRepository executionRepository, SourceCollectionTargetRepository targetRepository,
                                   SourceCollectionPreparer preparer, SourceCatalogLoader loader, SourceCollectionInstance instance,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${lexorma.source.collection.stale-after:PT10M}") Duration staleAfter) {
        this.verifier = verifier;
        this.projectWriteGuard = projectWriteGuard;
        this.connectionRepository = connectionRepository;
        this.executionRepository = executionRepository;
        this.targetRepository = targetRepository;
        this.preparer = preparer;
        this.loader = loader;
        this.instance = instance;
        this.tx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.staleAfter = staleAfter;
    }

    /**
     * 1. 수집 등록. 같은 작업(실행 컨텍스트의 작업 ID)을 다시 등록하면 같은 실행을 돌려준다.
     *
     * @throws CustomException SOURCE_COLLECTION_IN_PROGRESS : 같은 연결의 수집이 진행 중이다
     */
    public SourceCollectionDto.Execution start(ExecutionContext context, Long connectionId, SourceCollectionSpec.Request request) {
        SourceCatalogLoader.verify(verifier, context);
        if (request == null || request.type() == null) throw SourceCatalogRules.invalid("수집 범위가 없습니다.");
        List<SourceCollectionSpec.Namespace> schemas = request.schemas() == null ? List.of() : request.schemas();
        if (request.type() == CollectionType.FULL && !schemas.isEmpty()) throw SourceCatalogRules.invalid("전체 수집에는 스키마를 지정하지 않습니다.");
        if (request.type() == CollectionType.PARTIAL && schemas.isEmpty()) throw SourceCatalogRules.invalid("부분 수집할 스키마가 없습니다.");
        LocalDateTime now = now();
        SourceCollectionExecution execution = tx.execute(status -> {
            Long projectId = context.projectId();
            projectWriteGuard.lock(projectId);
            SourceConnection connection = connectionRepository.findForUpdate(connectionId, projectId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
            SourceCatalogLoader.verify(verifier, context);
            if (!Boolean.TRUE.equals(connection.getEnabled())) throw new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "사용 중지된 연결입니다.");

            String jobId = context.jobId() != null ? context.jobId() : UUID.randomUUID().toString();
            SourceCollectionExecution existing = executionRepository.findByJobId(jobId).orElse(null);
            if (existing != null) {
                if (!owned(existing, context) || !existing.getConnectionId().equals(connectionId)) throw notFound();
                return existing;
            }
            SourceCollectionExecution running = executionRepository.findByProgressConnectionId(connectionId).orElse(null);
            if (running != null && !abandonIfStale(running, now)) throw new CustomException(ErrorCode.SOURCE_COLLECTION_IN_PROGRESS);

            Map<String, SourceCollectionTarget> targets = new LinkedHashMap<>();
            for (SourceCollectionSpec.Namespace namespace : schemas) {
                if (namespace == null) throw SourceCatalogRules.invalid("카탈로그와 스키마 중 하나는 있어야 합니다.");
                String catalogName = SourceCatalogRules.optional(namespace.catalog());
                String schemaName = SourceCatalogRules.optional(namespace.schema());
                if (catalogName == null && schemaName == null) throw SourceCatalogRules.invalid("카탈로그와 스키마 중 하나는 있어야 합니다.");
                String path = SourceIdentifiers.pathKey(SourceCatalogRules.compare(connection, Kind.NAMESPACE, namespace.catalog()),
                        SourceCatalogRules.compare(connection, Kind.NAMESPACE, namespace.schema()));
                if (targets.put(path, SourceCollectionTarget.builder().projectId(projectId).pathKey(path).catalogName(catalogName).schemaName(schemaName)
                        .result(TargetResult.SKIPPED).build()) != null) {
                    throw SourceCatalogRules.invalid("같은 스키마가 두 번 있습니다 : {0}", catalogName == null ? schemaName : schemaName == null ? catalogName : catalogName + "." + schemaName);
                }
            }
            SourceCollectionExecution created = SourceCollectionExecution.builder().projectId(projectId).connectionId(connectionId).jobId(jobId)
                    .executionType(context.type()).accountId(context.accountId()).collectionType(request.type()).status(CollectionStatus.RUNNING)
                    .progressConnectionId(connectionId).serverId(instance.serverId()).startedAt(now).lastIdentifiedAt(now).build();
            try {
                executionRepository.saveAndFlush(created);
            } catch (DataIntegrityViolationException e) {
                // 연결 행 잠금으로 막히지 않은 경로(다른 인스턴스의 직접 등록 등)도 DB 의 UNIQUE 가 막는다
                throw new CustomException(ErrorCode.SOURCE_COLLECTION_IN_PROGRESS);
            }
            for (SourceCollectionTarget target : targets.values()) {
                target.setExecutionId(created.getId());
                entityManager.persist(target);
            }
            return created;
        });
        if (execution.getStatus().inProgress() && instance.serverId().equals(execution.getServerId())) instance.register(execution.getId());
        return SourceCollectionDto.Execution.of(execution);
    }

    /**
     * 3. 수집 결과 반영. 결과 준비(검증·정규화)는 트랜잭션 밖에서, 반영은 한 트랜잭션에서 한다.
     * 이미 반영된 작업을 다시 보내면 아무것도 바꾸지 않고 그 실행을 돌려준다 (같은 결과가 두 번 반영되지 않는다).
     *
     * @throws CustomException SOURCE_COLLECTION_INCOMPLETE : 일부 스키마가 완전하지 않다 (실행은 FAILED, 현재 카탈로그·버전·이력은 그대로)
     */
    public SourceCollectionDto.Execution apply(ExecutionContext context, String jobId, SourceCollectionSpec.Result result) {
        SourceCollectionExecution execution = find(context, jobId);
        if (execution.getStatus() == CollectionStatus.COMPLETED) return SourceCollectionDto.Execution.of(execution);
        if (execution.getStatus() != CollectionStatus.RUNNING) throw new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_RUNNING);
        try {
            SourceConnection connection = connectionRepository.findByIdAndProjectId(execution.getConnectionId(), execution.getProjectId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
            Map<String, PreparedCollection.Target> requested = new LinkedHashMap<>();
            for (SourceCollectionTarget target : targetRepository.findAllByExecutionIdOrderByPathKeyAsc(execution.getId())) {
                requested.put(target.getPathKey(), new PreparedCollection.Target(target.getPathKey(), target.getCatalogName(), target.getSchemaName(), target.getResult(),
                        target.getErrorCode(), 0, 0));
            }
            PreparedCollection prepared;
            try {
                prepared = preparer.prepare(connection, execution.getCollectionType(), requested, result);
            } catch (RuntimeException e) {
                recordFailure(execution.getId(), List.of(), e);
                throw e;
            }
            if (!prepared.complete()) {
                CustomException incomplete = SourceCollectionPreparer.incomplete(prepared.incompleteReason());
                recordFailure(execution.getId(), prepared.targets(), incomplete);
                throw incomplete;
            }
            try {
                SourceCatalogLoader.Outcome outcome = tx.execute(status -> loader.apply(context, execution.getId(), prepared, now()));
                return SourceCollectionDto.Execution.of(outcome.execution());
            } catch (RuntimeException e) {
                recordFailure(execution.getId(), prepared.targets(), e);
                throw e;
            }
        } finally {
            instance.release(execution.getId());
        }
    }

    /** 결과를 만들지 못한 수집을 실패로 남긴다 (AI-2 의 도구 오류, 시간 초과 등). 오류 코드는 정해진 모양만 받는다 */
    public SourceCollectionDto.Execution fail(ExecutionContext context, String jobId, String errorCode) {
        SourceCollectionExecution execution = find(context, jobId);
        try {
            String code = Objects.requireNonNullElse(SourceCollectionPreparer.errorCode(errorCode), ErrorCode.SOURCE_COLLECTION_INCOMPLETE.name());
            try {
                SourceCatalogLoader.verify(verifier, context);
            } catch (CustomException denied) {
                // 수집 도중 권한이 회수된 실행도 끝낸다 (진행 중으로 남으면 그 연결의 수집을 다시 시작할 수 없다). 남기는 코드는 권한 오류다
                finish(execution.getId(), CollectionStatus.FAILED, denied.getErrorCode().name(), null, List.of());
                throw denied;
            }
            finish(execution.getId(), CollectionStatus.FAILED, code, null, List.of());
        } finally {
            instance.release(execution.getId());
        }
        return SourceCollectionDto.Execution.of(executionRepository.findById(execution.getId()).orElse(execution));
    }

    /** 진행 중인 수집을 취소한다 (반영하지 않는다) */
    public SourceCollectionDto.Execution cancel(ExecutionContext context, String jobId) {
        SourceCatalogLoader.verify(verifier, context);
        SourceCollectionExecution execution = find(context, jobId);
        if (!execution.getStatus().inProgress()) throw new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_RUNNING);
        try {
            finish(execution.getId(), CollectionStatus.CANCELLED, null, null, List.of());
        } finally {
            instance.release(execution.getId());
        }
        return SourceCollectionDto.Execution.of(executionRepository.findById(execution.getId()).orElse(execution));
    }

    /** 살아 있음 확인 : 긴 수집(AI-2)이 도는 동안 주기적으로 부른다. 이 서버가 맡은 진행 중인 실행만 갱신한다 */
    public boolean heartbeat(ExecutionContext context, String jobId) {
        SourceCollectionExecution execution = find(context, jobId);
        Integer updated = tx.execute(status -> executionRepository.touch(execution.getId(), instance.serverId(), now(), IN_PROGRESS));
        return updated != null && updated > 0;
    }

    /**
     * 멈춘 실행을 정리하는 내부 경로 (등록할 때도 같은 판단을 한다). 정리했으면 true.
     * 지금 이 서버에서 돌고 있는 실행, 살아 있음 확인이 아직 오래되지 않은 다른 서버의 실행은 정리하지 않는다.
     */
    public boolean recoverAbandoned(ExecutionContext context, Long connectionId) {
        SourceCatalogLoader.verify(verifier, context);
        Boolean recovered = tx.execute(status -> {
            projectWriteGuard.lock(context.projectId());
            connectionRepository.findForUpdate(connectionId, context.projectId()).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
            return executionRepository.findByProgressConnectionId(connectionId).map(running -> abandonIfStale(running, now())).orElse(false);
        });
        return Boolean.TRUE.equals(recovered);
    }

    // ------------------------------------------------------------------ 내부

    /** 연결 행을 잠근 트랜잭션 안에서 부른다 */
    private boolean abandonIfStale(SourceCollectionExecution running, LocalDateTime now) {
        boolean mine = instance.serverId().equals(running.getServerId());
        boolean stale = mine ? !instance.isActive(running.getId()) : running.getLastIdentifiedAt().isBefore(now.minus(staleAfter));
        if (!stale) return false;
        int updated = executionRepository.abandon(running.getId(), running.getLastIdentifiedAt(), now, ErrorCode.SOURCE_COLLECTION_ABANDONED.name(), IN_PROGRESS);
        entityManager.detach(running);
        if (updated > 0) log.warn("멈춘 원본 카탈로그 수집을 정리했습니다 : executionId={}, serverId={}", running.getId(), running.getServerId());
        return updated > 0;
    }

    /** 요청한 컨텍스트로 찾을 수 있는 실행 : 같은 프로젝트, 사람의 요청은 같은 요청자, 배치·비동기는 같은 작업 */
    private SourceCollectionExecution find(ExecutionContext context, String jobId) {
        if (context == null || jobId == null) throw notFound();
        return executionRepository.findByJobId(jobId).filter(execution -> owned(execution, context)).orElseThrow(SourceCollectionService::notFound);
    }

    private static boolean owned(SourceCollectionExecution execution, ExecutionContext context) {
        if (!execution.getProjectId().equals(context.projectId())) return false;
        if (context.jobId() != null && !context.jobId().equals(execution.getJobId())) return false;
        if (context.type() == ExecutionType.SCHEDULED) return execution.getExecutionType() == ExecutionType.SCHEDULED;
        return Objects.equals(execution.getAccountId(), context.accountId());
    }

    /** 반영하지 못한 실행을 FAILED 로 남긴다 (별도 트랜잭션). 이미 끝났거나 연결·프로젝트가 지워졌으면 아무것도 하지 않는다 */
    private void recordFailure(Long executionId, List<PreparedCollection.Target> targets, Throwable cause) {
        String code = cause instanceof CustomException custom ? custom.getErrorCode().name() : "UNEXPECTED_ERROR";
        // 오류 메시지 : 서버가 만든 문구만 남긴다 (예상하지 못한 오류는 종류만 : SQL·접속 정보가 섞여 들어올 수 있다)
        String message = cause instanceof CustomException custom ? custom.getCustomMessage() : cause.getClass().getSimpleName();
        log.warn("원본 카탈로그 수집을 반영하지 못했습니다 : executionId={}, code={}", executionId, code);
        try {
            finish(executionId, CollectionStatus.FAILED, code, message, targets);
        } catch (RuntimeException e) {
            log.warn("원본 카탈로그 수집 실패를 기록하지 못했습니다 : executionId={}, code={}", executionId, e.getClass().getSimpleName());
        }
    }

    /** 진행 중인 실행을 끝낸다 (프로젝트 → 연결 → 실행 순서로 잠근다) */
    private void finish(Long executionId, CollectionStatus status, String code, String message, List<PreparedCollection.Target> targets) {
        newTx.executeWithoutResult(s -> {
            SourceCollectionExecution execution = executionRepository.findById(executionId).orElse(null);
            if (execution == null) return;
            try {
                projectWriteGuard.lock(execution.getProjectId());
            } catch (CustomException gone) {
                return;
            }
            if (connectionRepository.findForUpdate(execution.getConnectionId(), execution.getProjectId()).isEmpty()) return;
            entityManager.refresh(execution);
            if (!execution.getStatus().inProgress()) return;
            LocalDateTime now = now();
            execution.setStatus(status);
            execution.setProgressConnectionId(null);
            execution.setEndedAt(now);
            execution.setLastIdentifiedAt(now);
            execution.setErrorCode(code);
            execution.setErrorMessage(message == null ? null : limit(SensitiveDataMasker.maskText(message)));
            Map<String, SourceCollectionTarget> existing = new HashMap<>();
            for (SourceCollectionTarget target : targetRepository.findAllByExecutionIdOrderByPathKeyAsc(executionId)) existing.put(target.getPathKey(), target);
            for (PreparedCollection.Target result : targets) {
                SourceCollectionTarget target = existing.get(result.pathKey());
                boolean created = target == null;
                if (created) target = SourceCollectionTarget.builder().projectId(execution.getProjectId()).executionId(executionId).pathKey(result.pathKey()).build();
                target.setCatalogName(result.catalogName());
                target.setSchemaName(result.schemaName());
                target.setResult(result.result());
                target.setTableCount(result.tableCount());
                target.setColumnCount(result.columnCount());
                target.setErrorCode(result.errorCode());
                if (created) entityManager.persist(target);
            }
        });
    }

    private static String limit(String value) {
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_FOUND);
    }
}
