package com.lexorma.data.inspection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.HistoryType;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.repository.InspectionRepository.ExistingTarget;
import com.lexorma.data.inspection.repository.InspectionRepository.HistoryInsert;
import com.lexorma.data.inspection.repository.InspectionRepository.RunRow;
import com.lexorma.data.inspection.service.InspectionLifecycle.Existing;
import com.lexorma.data.inspection.service.InspectionLifecycle.Plan;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 저장 품질 검사의 실행 (AI-6 2차, AI6-D07·D08·D17·D18·D23·D27·D28·D32·D33).
 * <ol>
 * <li>요청 : 프로젝트 공유 잠금 → 진행 중 실행 확인 → PENDING 행(PRGRS_PRJ_SN = 프로젝트, UNIQUE 로 프로젝트마다 하나) → 커밋 뒤 실행기에 넘긴다</li>
 * <li>시작 (짧은 트랜잭션) : 실행 컨텍스트를 다시 확인하고(USER) RUNNING, 그때의 규칙 설정으로 적용 규칙을 확정한다 (AI6-D28)</li>
 * <li>판정 (읽기 전용 REPEATABLE READ 한 트랜잭션) : 16규칙을 한 스냅숏에서 판정하고, 찾지 못한 OPEN 결과의 대상이 그 스냅숏에 있는지 본다 (AI6-D27).
 *     기준 일시 = 스냅숏을 잡기 직전의 시각</li>
 * <li>저장 (쓰기 한 트랜잭션, AI6-D17) : 프로젝트 공유 잠금 → 실행 행 잠금(RUNNING 인지) → 컨텍스트 재확인 → 신규·유지·재발·해결 반영 + 재발 이력
 *     + 규칙별 건수 → COMPLETED</li>
 * </ol>
 * 실패 처리 (AI6-D32·D33, 2차 최종 보완에서 AI6-2-02 로 확정) — 상태값은 늘리지 않고 ERR_CD 로 나눈다 :
 * <ul>
 * <li>모든 적용 묶음 성공 → COMPLETED (ERR_CD 없음). 적용 규칙이 0개(모두 끔)인 실행도 COMPLETED 이며 실행 규칙 행이 없다 (빈 검사)</li>
 * <li>일부 묶음 실패 → COMPLETED + {@link InspectionCodes#ERR_RULE_FAILED} (부분 실패) : 성공한 묶음의 규칙만 생명주기를 적용하고,
 *     실패한 묶음의 규칙은 검사하지 않은 것으로 본다 — 그 규칙의 결과는 신규·유지·재발·해결·최종 실행·최종 확인 일시·예외 어느 것도 바꾸지 않는다.
 *     실패한 묶음이 중간까지 만든 결과도 버린다</li>
 * <li>적용 규칙이 있는데 성공한 묶음이 없다 → FAILED + {@link InspectionCodes#ERR_ALL_RULES_FAILED}, 결과·이력 그대로</li>
 * <li>판정 단계(묶음 밖) 실패 → FAILED + {@link InspectionCodes#ERR_FAILED}, 저장 트랜잭션 실패 → FAILED + {@link InspectionCodes#ERR_STORE_FAILED}
 *     (전체 롤백), 저장 직전 권한·프로젝트 확인 실패 → FAILED + 그 오류 코드. 어느 경우든 이전 결과가 그대로 남고 다시 실행하지 않는다</li>
 * <li>서버 중단 → 다음 기동 때 INTERRUPTED (확정하지 않은 결과는 반영되지 않는다)</li>
 * </ul>
 * 묶음 실패는 같은 연결의 JDBC 세이브포인트로 되돌린다. 판정 서비스(@Transactional readOnly)가 던진 예외는 바깥 트랜잭션을 rollback-only 로 만들지만,
 * 판정 트랜잭션은 읽기 전용이라 커밋 대신 롤백돼도 결과가 같다 — 실패한 묶음이 있을 때만 그 롤백을 받아들이고, 그 밖의 원인이면 판정 실패로 처리한다.
 * 실행기는 서버 전체에 스레드 하나 + 대기열(기본 10, {@code lexorma.inspection.queue-capacity})이다 (AI6-D23). 가득 차면 그 실행은 FAILED
 * ({@link InspectionCodes#ERR_EXECUTOR_BUSY}). 서버를 끌 때 기다리지 않으며, 끝나지 않은 실행은 다음 기동 때 INTERRUPTED 로 닫는다. 취소는 없다.
 */
@Service
public class InspectionRunService {

    private static final Logger log = LoggerFactory.getLogger(InspectionRunService.class);

    private final InspectionRepository repository;
    private final InspectionJudge judge;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;
    private final ObjectProvider<InspectionHooks> hooks;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;
    private final TransactionTemplate snapshot;

    public InspectionRunService(InspectionRepository repository, InspectionJudge judge, ExecutionContextVerifier verifier, ProjectWriteGuard projectWriteGuard,
                                ObjectProvider<InspectionHooks> hooks, PlatformTransactionManager transactionManager,
                                @Value("${lexorma.inspection.queue-capacity:10}") int queueCapacity) {
        this.repository = repository;
        this.judge = judge;
        this.verifier = verifier;
        this.projectWriteGuard = projectWriteGuard;
        this.hooks = hooks;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(Math.max(0, queueCapacity));
        pool.setThreadNamePrefix("quality-inspection-");
        pool.setWaitForTasksToCompleteOnShutdown(false);
        pool.initialize();
        this.executor = pool;
        this.tx = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------ 요청

    /** 로그인 프로젝트 전체 검사를 요청한다 (USER). 프로젝트에 대기·진행 중 실행이 있으면 409 INSPECTION_ALREADY_RUNNING */
    public long request(AccountDto principal) {
        ExecutionContext context = ExecutionContext.user(principal);
        VerifiedExecution execution = verifier.verify(context, "USER");
        long projectId = context.projectId();
        try {
            return tx.execute(status -> {
                projectWriteGuard.lock(projectId);
                if (repository.findActiveRun(projectId).isPresent()) throw new CustomException(ErrorCode.INSPECTION_ALREADY_RUNNING);
                long id = repository.insertRun(projectId, context.accountId(), LocalDateTime.now(), execution.auditor());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        submit(id, execution.auditor());
                    }
                });
                return id;
            });
        } catch (DuplicateKeyException e) {
            // 동시에 들어온 두 요청 : 둘 다 진행 중 실행이 없다고 보고 넣었다 — UNIQUE(PRGRS_PRJ_SN) 가 하나만 남긴다
            throw new CustomException(ErrorCode.INSPECTION_ALREADY_RUNNING);
        }
    }

    private void submit(long runId, String auditor) {
        try {
            executor.execute(() -> run(runId));
        } catch (TaskRejectedException e) {
            tx.executeWithoutResult(s -> repository.closeRun(runId, RunStatus.FAILED, InspectionCodes.ERR_EXECUTOR_BUSY, null, null, LocalDateTime.now(), auditor));
            log.warn("품질 검사 실행 거절 (실행기 가득 참) : run={}", runId);
        }
    }

    // ------------------------------------------------------------------ 실행 (실행기 스레드)

    private record Started(RunRow run, Set<StandardizationRule> rules, String auditor, ExecutionContext context) {
    }

    /** judgedRules = 끝까지 판정한 규칙 (해결 판정은 이 규칙만), failedRules = 판정하지 못한 규칙 (결과를 그대로 둔다, AI6-D32) */
    private record Judged(LocalDateTime basisAt, Set<StandardizationRule> judgedRules, Set<StandardizationRule> failedRules, List<StoredItem> items,
                          List<Existing> existing, Set<Long> deleted) {

        /** 저장 단계에 넘기는 값 (기존 결과·판정 목록·삭제 대상은 계획에 이미 반영했다) */
        Judged forStore() {
            return new Judged(basisAt, judgedRules, failedRules, List.of(), List.of(), Set.of());
        }
    }

    void run(long runId) {
        Started started;
        try {
            started = tx.execute(s -> start(runId));
        } catch (RuntimeException e) {
            fail(runId, null, e, InspectionCodes.ERR_FAILED);
            return;
        }
        if (started == null) return;
        long projectId = started.run().projectId();
        long begin = System.nanoTime();
        Map<String, Long> phases = new LinkedHashMap<>();
        LocalDateTime basisAt = null;
        boolean storing = false;
        try {
            Judged judged = judgeInSnapshot(runId, projectId, started.rules(), phases);
            int foundCount = judged.items().size();
            phases.put("snapshotTx", (System.nanoTime() - begin) / 1_000_000);
            basisAt = judged.basisAt();
            if (judged.judgedRules().isEmpty() && !judged.failedRules().isEmpty()) {
                // 전체 판정 실패 : 성공한 묶음이 없다 — 아무것도 반영하지 않고 실행만 FAILED (적용 규칙은 모두 FAILED)
                LocalDateTime failedBasis = basisAt;
                tx.executeWithoutResult(s -> repository.closeRun(runId, RunStatus.FAILED, InspectionCodes.ERR_ALL_RULES_FAILED, null, failedBasis,
                        LocalDateTime.now(), VerifiedExecution.SCHEDULER));
                log.warn("품질 검사 전체 판정 실패 : run={} project={} failedRules={}", runId, projectId, judged.failedRules());
                return;
            }
            long judgedAt = System.nanoTime();
            Set<Long> deleted = judged.deleted();
            Plan plan = InspectionLifecycle.plan(judged.judgedRules(), judged.items(), judged.existing(), row -> deleted.contains(row.id()));
            phases.put("plan", (System.nanoTime() - judgedAt) / 1_000_000);
            // 저장에는 계획만 쓴다 : 저장된 결과 전체(기존 목록)·판정 목록을 놓아 저장 트랜잭션 동안 들고 있지 않는다 (FINAL 메모리 개선)
            Judged decided = judged.forStore();
            judged = null;
            hook().beforeStore(runId);
            storing = true;
            long storeBegin = System.nanoTime();
            boolean stored = Boolean.TRUE.equals(tx.execute(s -> store(started, decided, plan, phases)));
            long end = System.nanoTime();
            phases.put("storeTx", (end - storeBegin) / 1_000_000);
            phases.put("total", (end - begin) / 1_000_000);
            log.info("품질 검사 끝 : run={} project={} stored={} rules={} failedRules={} found={} new={} persisting={} reopened={} resolved={} duplicates={} judge={}ms store={}ms",
                    runId, projectId, stored, decided.judgedRules().size(), decided.failedRules(), foundCount - plan.duplicates(), plan.inserts().size(), plan.persists().size(),
                    plan.reopens().size(), plan.resolves().size(), plan.duplicates(), (judgedAt - begin) / 1_000_000, (end - judgedAt) / 1_000_000);
            log.info("품질 검사 단계별 시간(ms) : run={} {}", runId, phases);
            hook().afterRun(runId, phases);
        } catch (RuntimeException e) {
            fail(runId, basisAt, e, storing ? InspectionCodes.ERR_STORE_FAILED : InspectionCodes.ERR_FAILED);
        }
    }

    /**
     * 판정 트랜잭션 (읽기 전용 REPEATABLE READ). 실패한 묶음이 판정 서비스의 트랜잭션 경계 안에서 예외를 던졌으면 바깥 트랜잭션이 rollback-only 가 되어
     * 커밋 대신 롤백되고 {@link UnexpectedRollbackException} 이 난다 — 읽기 전용이라 바뀐 것이 없으므로, 실패한 묶음이 있을 때만 그 판정 결과를 쓴다.
     */
    private Judged judgeInSnapshot(long runId, long projectId, Set<StandardizationRule> rules, Map<String, Long> phases) {
        AtomicReference<Judged> result = new AtomicReference<>();
        try {
            return snapshot.execute(s -> {
                Judged judged = judge(runId, projectId, rules, phases);
                result.set(judged);
                return judged;
            });
        } catch (UnexpectedRollbackException e) {
            Judged judged = result.get();
            if (judged == null || judged.failedRules().isEmpty()) throw e;
            log.info("품질 검사 판정 트랜잭션 롤백 (실패한 묶음 때문, 읽기 전용) : run={}", runId);
            return judged;
        }
    }

    private Started start(long runId) {
        RunRow run = repository.lockRun(runId).orElse(null);
        if (run == null || run.status() != RunStatus.PENDING) return null;
        ExecutionContext context = ExecutionContext.async(run.projectId(), run.accountId(), "quality-inspection-" + runId);
        VerifiedExecution execution = verifier.verify(context, "USER");
        LocalDateTime now = LocalDateTime.now();
        repository.startRun(runId, now, execution.auditor());
        Set<StandardizationRule> rules = EnumSet.allOf(StandardizationRule.class);
        rules.removeAll(repository.disabledRules(run.projectId()));
        repository.insertRunRules(runId, rules, now, execution.auditor());
        return new Started(run, rules, execution.auditor(), context);
    }

    private Judged judge(long runId, long projectId, Set<StandardizationRule> active, Map<String, Long> phases) {
        LocalDateTime basisAt = LocalDateTime.now();
        repository.jdbc().queryForObject("select 1", Integer.class); // 첫 문장이 REPEATABLE READ 스냅숏을 잡는다
        hook().afterSnapshot(runId);
        // 규칙 묶음마다 세이브포인트 : 한 묶음이 실패해도 같은 스냅숏으로 나머지 묶음을 판정한다. 실패한 묶음의 규칙은 아무것도 해결하지 않는다
        Set<StandardizationRule> rules = EnumSet.noneOf(StandardizationRule.class);
        Set<StandardizationRule> failed = EnumSet.noneOf(StandardizationRule.class);
        List<StoredItem> items = new ArrayList<>();
        Map<String, StoredItem> fingerprints = new java.util.HashMap<>();
        for (InspectionJudge.Group group : InspectionJudge.Group.values()) {
            Set<StandardizationRule> groupRules = EnumSet.noneOf(StandardizationRule.class);
            active.stream().filter(rule -> InspectionJudge.group(rule) == group).forEach(groupRules::add);
            if (groupRules.isEmpty()) continue;
            // 트랜잭션 관리자가 중첩 트랜잭션을 허용하지 않아, 이 트랜잭션에 묶인 같은 JDBC 연결에서 세이브포인트를 직접 쓴다
            Savepoint savepoint = repository.jdbc().execute((ConnectionCallback<Savepoint>) Connection::setSavepoint);
            long groupBegin = System.nanoTime();
            try {
                hook().beforeGroup(runId, group);
                // 판정 결과는 곧바로 저장 형태로 바꾼다 : 근거 JSON 을 한 번만 만들고 근거 Map·Finding 은 버린다 (FINAL 메모리 개선)
                List<StoredItem> groupItems = new ArrayList<>();
                judge.judge(projectId, group, groupRules, item -> groupItems.add(StoredItem.of(item)));
                phases.put("judge." + group, (System.nanoTime() - groupBegin) / 1_000_000);
                hook().afterGroup(runId, group, groupItems.size());
                // 같은 fingerprint 의 다른 내용 = 충돌 → 이 묶음 실패 (서로 다른 문제를 조용히 합치지 않는다). 내용까지 같으면 단순 중복
                int duplicates = InspectionLifecycle.checkFingerprints(groupItems, fingerprints);
                if (duplicates > 0) phases.merge("duplicates", (long) duplicates, Long::sum);
                repository.jdbc().execute((ConnectionCallback<Void>) connection -> {
                    connection.releaseSavepoint(savepoint);
                    return null;
                });
                // 묶음이 끝까지 성공했을 때만 결과를 더한다 (중간 결과를 남기지 않는다)
                items.addAll(groupItems);
                rules.addAll(groupRules);
            } catch (RuntimeException e) {
                // 세이브포인트로 되돌리지 못하면(연결 끊김 등) 트랜잭션을 복구할 수 없다 — 부분 실패로 보지 않고 판정 실패로 올린다
                repository.jdbc().execute((ConnectionCallback<Void>) connection -> {
                    connection.rollback(savepoint);
                    return null;
                });
                failed.addAll(groupRules);
                if (e instanceof InspectionLifecycle.FingerprintCollisionException collision) {
                    log.error("품질 검사 fingerprint 충돌 — 묶음 실패 : run={} group={} rule={}", runId, group, collision.rule());
                } else {
                    log.warn("품질 검사 규칙 묶음 실패 : run={} group={} cause={}", runId, group, e.getClass().getName());
                }
            }
        }
        // 찾은 fingerprint = 충돌 검사에 쓴 Map 의 키 (따로 모으지 않는다)
        Set<String> found = fingerprints.keySet();

        long existingBegin = System.nanoTime();
        List<Existing> existing = new ArrayList<>();
        Map<InspectionItem.ObjectType, Set<Long>> candidates = new EnumMap<>(InspectionItem.ObjectType.class);
        List<ExistingTarget> unresolved = new ArrayList<>();
        for (ExistingTarget target : repository.existing(projectId)) {
            Existing row = target.row();
            if (row.rule() == null) continue;
            existing.add(row);
            if (row.status() == FindingStatus.OPEN && rules.contains(row.rule()) && !found.contains(row.fingerprint())) {
                unresolved.add(target);
                candidates.computeIfAbsent(target.type(), type -> new HashSet<>()).addAll(targets(target));
            }
        }
        phases.put("loadExisting", (System.nanoTime() - existingBegin) / 1_000_000);
        long aliveBegin = System.nanoTime();
        Map<InspectionItem.ObjectType, Set<Long>> alive = new EnumMap<>(InspectionItem.ObjectType.class);
        candidates.forEach((type, ids) -> alive.put(type, repository.existingObjects(projectId, type, ids)));
        Set<Long> deleted = new HashSet<>();
        for (ExistingTarget target : unresolved) {
            Set<Long> ids = alive.getOrDefault(target.type(), Set.of());
            // 대상이 하나도 남지 않았을 때만 '대상 삭제' (용어 그룹은 구성원 중 하나라도 있으면 실제 해결로 본다)
            if (targets(target).stream().noneMatch(ids::contains)) deleted.add(target.row().id());
        }
        phases.put("targetCheck", (System.nanoTime() - aliveBegin) / 1_000_000);
        phases.put("items", (long) items.size());
        return new Judged(basisAt, rules, failed, items, existing, deleted);
    }

    private static List<Long> targets(ExistingTarget target) {
        return target.targetIds().isEmpty() ? List.of(target.targetId()) : target.targetIds();
    }

    private Boolean store(Started started, Judged judged, Plan plan, Map<String, Long> phases) {
        long projectId = started.run().projectId();
        long runId = started.run().id();
        projectWriteGuard.lock(projectId);
        RunRow run = repository.lockRun(runId).orElse(null);
        if (run == null || run.status() != RunStatus.RUNNING) return false; // 그사이 중단(재시작 정리)으로 닫혔다
        verifier.verify(started.context(), "USER");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime basisAt = judged.basisAt();
        String auditor = started.auditor();

        long t = System.nanoTime();
        repository.insertFindings(projectId, runId, plan.inserts(), basisAt, now, auditor);
        t = lap(phases, "store.insertNew", t);
        repository.updateFound(projectId, runId, plan.persists().stream().map(InspectionLifecycle.Persist::id).toList(),
                plan.persists().stream().map(InspectionLifecycle.Persist::item).toList(), false, basisAt, now, auditor);
        t = lap(phases, "store.updatePersisting", t);
        repository.updateFound(projectId, runId, plan.reopens().stream().map(InspectionLifecycle.Reopen::id).toList(),
                plan.reopens().stream().map(InspectionLifecycle.Reopen::item).toList(), true, basisAt, now, auditor);
        repository.insertHistory(projectId, plan.reopens().stream()
                .map(r -> new HistoryInsert(r.id(), runId, HistoryType.REOPEN, FindingStatus.OPEN, r.excluded(), null, r.previousReason(), r.previousEndedAt()))
                .toList(), now, auditor);
        t = lap(phases, "store.reopen", t);
        for (EndReason reason : EndReason.values()) {
            repository.resolve(projectId, runId, plan.resolves().stream().filter(r -> r.reason() == reason).map(InspectionLifecycle.Resolve::id).toList(),
                    reason, basisAt, now, auditor);
        }
        t = lap(phases, "store.resolve", t);
        Map<StandardizationRule, Integer> found = new EnumMap<>(StandardizationRule.class);
        judged.judgedRules().forEach(rule -> found.put(rule, plan.found(rule)));
        repository.completeRunRules(runId, found, now, auditor);
        repository.failRunRules(runId, judged.failedRules(), now, auditor);
        // 예외 건수는 저장 시점의 값 : 이 실행이 고친 행은 이 트랜잭션이 잠그고 있어, 그사이 예외 처리·해제는 커밋 뒤로 밀린다 (판정 스냅숏의 값을 쓰지 않는다)
        Map<StandardizationRule, Integer> excluded = repository.excludedFound(projectId, runId);
        plan.counts().forEach((rule, byChange) -> byChange.put(RunChange.EXCLUDED, excluded.getOrDefault(rule, 0)));
        repository.insertRunCounts(runId, plan.counts(), now, auditor);
        hook().beforeComplete(runId);
        repository.completeRun(runId, basisAt, judged.failedRules().isEmpty() ? null : InspectionCodes.ERR_RULE_FAILED, now, auditor);
        lap(phases, "store.counts", t);
        return true;
    }

    private static long lap(Map<String, Long> phases, String name, long since) {
        long now = System.nanoTime();
        phases.put(name, (now - since) / 1_000_000);
        return now;
    }

    private void fail(long runId, LocalDateTime basisAt, RuntimeException e, String defaultCode) {
        // 권한·프로젝트 상태 확인 실패(CustomException)는 그 코드, 그 밖에는 실패한 단계의 코드 (판정 / 저장)
        String code = e instanceof CustomException custom ? custom.getErrorCode().name() : defaultCode;
        // 원인 메시지에는 SQL·값이 들어 있을 수 있어 저장·기록하지 않는다 (예외 종류만)
        log.warn("품질 검사 실패 : run={} code={} cause={}", runId, code, e.getClass().getName());
        try {
            tx.executeWithoutResult(s -> repository.closeRun(runId, RunStatus.FAILED, code, e.getClass().getSimpleName(), basisAt, LocalDateTime.now(),
                    VerifiedExecution.SCHEDULER));
        } catch (RuntimeException closeFailure) {
            log.warn("품질 검사 실패 기록 실패 : run={} cause={}", runId, closeFailure.getClass().getName());
        }
    }

    /** 서버 기동 : 끝나지 않은 실행(이전 프로세스의 것)을 INTERRUPTED 로 닫는다 — 다시 실행하지 않는다 */
    @EventListener(ApplicationReadyEvent.class)
    public void interruptLeftovers() {
        List<Long> ids = tx.execute(s -> repository.activeRunIds());
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids) {
            tx.executeWithoutResult(s -> repository.closeRun(id, RunStatus.INTERRUPTED, InspectionCodes.ERR_SERVER_RESTARTED, null, null, LocalDateTime.now(),
                    VerifiedExecution.SCHEDULER));
        }
        log.info("품질 검사 : 끝나지 않은 실행 {}건을 중단으로 닫았다", ids.size());
    }

    // ------------------------------------------------------------------ 근거 JSON

    /**
     * 근거(JSONB) : 식별자 판, 대상 ID 목록, 현재·기대 값, 규칙 근거, 표시 정보. 규칙마다 구조가 달라도 이 바깥 모양은 같다 (AI6-D30).
     * 판정 결과에는 비밀값이 없다 (모델 이름·용어·DBMS 식별자 정보만). 직렬화는 {@link EvidenceJson} (FINAL).
     */
    static String evidence(InspectionItem item) {
        return EvidenceJson.of(item);
    }

    private InspectionHooks hook() {
        InspectionHooks hook = hooks.getIfAvailable();
        return hook == null ? InspectionHooks.NONE : hook;
    }
}
