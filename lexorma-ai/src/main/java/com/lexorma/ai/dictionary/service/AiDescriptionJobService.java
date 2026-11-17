package com.lexorma.ai.dictionary.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionJob;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionJob.Status;
import com.lexorma.ai.dictionary.repository.AiDescriptionJobRepository;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 설명 제안 대량 생성 (AI-5, U-15) : 단일 서버 안의 제한된 실행기(이 서비스 전용 — 스레드 하나라 AI 서버에 한 번에 한 요청, 대기 {@value #QUEUE}개, 가득 차면 AI_JOB_BUSY)
 * + DB 실행 상태. 별도 작업 큐 없음. 실행기를 Bean 으로 두지 않는다 (Spring Boot 의 기본 작업 실행기를 대신하지 않도록).
 * <ul>
 *   <li>대상은 요청할 때 서버가 정한다 (로그인 프로젝트에서만, 최대 {@value #MAX_TARGETS}개). 프로젝트당 진행 중 실행은 하나 (프로젝트 행을 잠그고 확인)</li>
 *   <li>대상마다 ASYNC 실행 컨텍스트(요청자·프로젝트 고정)로 단건 생성과 같은 경로({@link AiDescriptionDraftService#generate})를 탄다 — 권한·근거 검증·토큰 예산이 같다.
 *       검토 대기 중인 제안이 있으면 건너뛴다(중복 생성 없음). 재시도는 없다</li>
 *   <li>대상 하나의 실패(형식·예산·시간 초과·AI 서버 오류)는 그 대상만 실패로 세고 계속한다. 권한 회수·프로젝트 비활성·기본 Provider 미지정/비활성·SEC-1 차단은
 *       남은 대상도 할 수 없으므로 멈춘다. AI 서버 호출 실패가 {@value #CONSECUTIVE_FAILURES}번 이어져도 멈춘다 (서버가 꺼진 경우)</li>
 *   <li>이미 만든 제안은 실패·중단과 상관없이 남는다. 서버가 재시작되면 끝나지 않은 실행을 INTERRUPTED 로 닫는다 (자동 재개 없음 — 다시 요청하면 이미 만든 것은 건너뛴다)</li>
 *   <li>진행 상태는 대상마다 짧은 트랜잭션으로 저장한다 (AI 호출 동안 DB 트랜잭션을 잡지 않는다)</li>
 * </ul>
 */
@Slf4j
@Service
public class AiDescriptionJobService {

    static final int MAX_TARGETS = 100;
    static final int MAX_ERRORS = 50;
    static final int CONSECUTIVE_FAILURES = 3;
    static final int QUEUE = 10;
    private static final JsonMapper JSON = JsonMapper.shared();
    /** 남은 대상도 할 수 없는 오류 : 실행을 멈춘다 */
    private static final Set<ErrorCode> STOP = EnumSet.of(ErrorCode.PROJECT_ACCESS_DENIED, ErrorCode.PROJECT_NOT_FOUND, ErrorCode.EXECUTION_PERMISSION_DENIED,
            ErrorCode.AI_DEFAULT_PROVIDER_NOT_CONFIGURED, ErrorCode.AI_DEFAULT_PROVIDER_MODEL_MISSING, ErrorCode.AI_PROVIDER_DISABLED,
            ErrorCode.EXTERNAL_CONNECTION_BLOCKED, ErrorCode.AI_PROVIDER_UNSUPPORTED);

    private final AiDescriptionJobRepository jobs;
    private final AiDescriptionDraftService drafts;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate tx;

    private final com.lexorma.ai.dictionary.repository.AiDescriptionProposalRepository proposals;

    public AiDescriptionJobService(AiDescriptionJobRepository jobs, AiDescriptionDraftService drafts, ExecutionContextVerifier verifier,
                                   ProjectWriteGuard projectWriteGuard, TableInfoRepository tables, ColumnInfoRepository columns,
                                   PlatformTransactionManager transactionManager, com.lexorma.ai.dictionary.repository.AiDescriptionProposalRepository proposals) {
        this.proposals = proposals;
        this.jobs = jobs;
        this.drafts = drafts;
        this.verifier = verifier;
        this.projectWriteGuard = projectWriteGuard;
        this.tables = tables;
        this.columns = columns;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(QUEUE);
        pool.setThreadNamePrefix("ai-dictionary-");
        // 서버를 끌 때 기다리지 않는다 : 끝나지 않은 실행은 다음 기동 때 INTERRUPTED 로 닫는다
        pool.setWaitForTasksToCompleteOnShutdown(false);
        pool.initialize();
        this.executor = pool;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(30);
    }

    /** 실행을 등록하고(커밋 뒤) 실행기에 넘긴다 */
    public AiDictionaryDto.JobSummary start(ExecutionContext context, AiDictionaryDto.JobRequest request) {
        if (context == null || context.type() != ExecutionType.USER || context.accountId() == null) throw new CustomException(ErrorCode.EXECUTION_PERMISSION_DENIED);
        verifier.verify(context, "USER");
        Long projectId = context.projectId();
        AiDescriptionJob job = tx.execute(status -> {
            projectWriteGuard.lock(projectId);
            if (!jobs.findAllByProjectIdAndStatusIn(projectId, EnumSet.of(Status.PENDING, Status.RUNNING)).isEmpty()) {
                throw new CustomException(ErrorCode.AI_JOB_ALREADY_RUNNING);
            }
            List<AiDictionaryDto.Target> targets = runnable(resolve(projectId, request));
            AiDescriptionJob saved = jobs.save(AiDescriptionJob.builder().projectId(projectId).accountId(context.accountId()).status(Status.PENDING)
                    .targets(JSON.writeValueAsString(targets)).targetCount(targets.size()).completedCount(0).failedCount(0).skippedCount(0).build());
            Long id = saved.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(id);
                }
            });
            return saved;
        });
        return tx.execute(s -> summary(jobs.findById(job.getId()).orElseThrow()));
    }

    private void submit(Long id) {
        try {
            executor.execute(() -> run(id));
        } catch (TaskRejectedException e) {
            tx.executeWithoutResult(s -> jobs.findById(id).ifPresent(j -> {
                j.setStatus(Status.FAILED);
                j.setEndedAt(LocalDateTime.now());
                j.setErrors(JSON.writeValueAsString(List.of(new AiDictionaryDto.JobError(null, ErrorCode.AI_JOB_BUSY.name()))));
            }));
            log.warn("AI 설명 생성 실행 거절 (실행기 가득 참) : job={}", id);
        }
    }

    /** 실행기 스레드에서 돈다 */
    void run(Long id) {
        AiDescriptionJob job = tx.execute(s -> {
            AiDescriptionJob j = jobs.findById(id).orElse(null);
            if (j == null || j.getStatus() != Status.PENDING) return null;
            j.setStatus(Status.RUNNING);
            j.setStartedAt(LocalDateTime.now());
            return j;
        });
        if (job == null) return;
        ExecutionContext context = ExecutionContext.async(job.getProjectId(), job.getAccountId(), "ai-dictionary-" + id);
        List<AiDictionaryDto.Target> targets = JSON.readValue(job.getTargets(), new TypeReference<List<AiDictionaryDto.Target>>() {
        });
        List<AiDictionaryDto.JobError> errors = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        int consecutive = 0;
        String stopped = null;
        for (AiDictionaryDto.Target target : targets) {
            String label = target.columnId() == null ? "TABLE:" + target.tableId() : "COLUMN:" + target.columnId();
            try {
                AiDescriptionDraftService.Result result = drafts.generate(context, target.tableId(), target.columnId(), false, id);
                if (result.outcome() == AiDescriptionDraftService.Outcome.CREATED) completed++;
                else skipped++;
                consecutive = 0;
            } catch (CustomException e) {
                ErrorCode code = e.getErrorCode();
                if (code == ErrorCode.TABLE_IS_NOT_DEFINED || code == ErrorCode.COLUMN_IS_NOT_DEFINED) {
                    skipped++; // 요청 뒤 지워진 대상
                    error(errors, label, code.name());
                } else {
                    failed++;
                    error(errors, label, code.name());
                    consecutive = code == ErrorCode.AI_PROVIDER_CALL_FAILED ? consecutive + 1 : 0;
                    if (STOP.contains(code) || consecutive >= CONSECUTIVE_FAILURES) stopped = code.name();
                }
            } catch (RuntimeException e) {
                failed++;
                consecutive = 0;
                error(errors, label, "FAILED");
                log.warn("AI 설명 생성 실패 : job={} target={} {}", id, label, e.getClass().getName());
            }
            progress(id, completed, failed, skipped, errors, null);
            if (stopped != null) break;
        }
        Status end = stopped != null ? (completed + skipped == 0 ? Status.FAILED : Status.PARTIAL_FAILED)
                : failed == 0 ? Status.COMPLETED : (completed + skipped == 0 ? Status.FAILED : Status.PARTIAL_FAILED);
        progress(id, completed, failed, skipped, errors, end);
        log.info("AI 설명 생성 실행 끝 : job={} project={} status={} completed={} failed={} skipped={} stopped={}", id, job.getProjectId(), end, completed, failed, skipped, stopped);
    }

    private void progress(Long id, int completed, int failed, int skipped, List<AiDictionaryDto.JobError> errors, Status end) {
        tx.executeWithoutResult(s -> jobs.findById(id).ifPresent(j -> {
            j.setCompletedCount(completed);
            j.setFailedCount(failed);
            j.setSkippedCount(skipped);
            j.setErrors(errors.isEmpty() ? null : JSON.writeValueAsString(errors));
            if (end != null) {
                j.setStatus(end);
                j.setEndedAt(LocalDateTime.now());
            }
        }));
    }

    private static void error(List<AiDictionaryDto.JobError> errors, String target, String code) {
        if (errors.size() < MAX_ERRORS) errors.add(new AiDictionaryDto.JobError(target, code));
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** 서버가 재시작되면 끝나지 않은 실행을 닫는다 (실행 중이던 스레드는 없다) */
    @EventListener(ApplicationReadyEvent.class)
    public void interruptLeftovers() {
        tx.executeWithoutResult(s -> jobs.findAllByStatusIn(EnumSet.of(Status.PENDING, Status.RUNNING)).forEach(j -> {
            j.setStatus(Status.INTERRUPTED);
            j.setEndedAt(LocalDateTime.now());
            log.info("AI 설명 생성 실행 중단 처리 (서버 재시작) : job={} project={}", j.getId(), j.getProjectId());
        }));
    }

    public AiDictionaryDto.JobSummary get(ExecutionContext context, Long id) {
        verifier.verify(context, "VIEWER");
        return tx.execute(s -> summary(jobs.findByIdAndProjectId(id, context.projectId()).orElseThrow(() -> new CustomException(ErrorCode.AI_JOB_INVALID, "실행을 찾을 수 없습니다."))));
    }

    public List<AiDictionaryDto.JobSummary> recent(ExecutionContext context) {
        verifier.verify(context, "VIEWER");
        return tx.execute(s -> jobs.findAllByProjectIdOrderByIdDesc(context.projectId(), PageRequest.of(0, 10)).stream().map(AiDescriptionJobService::summary).toList());
    }

    /** 요청 범위를 대상 목록으로 (로그인 프로젝트에서만) */
    private List<AiDictionaryDto.Target> targets(Long projectId, AiDictionaryDto.JobRequest request) {
        String scope = request == null || request.scope() == null ? "" : request.scope().strip().toUpperCase(Locale.ROOT);
        List<AiDictionaryDto.Target> targets = new ArrayList<>();
        switch (scope) {
            case "MISSING_TABLES" -> tables.findAll((root, q, cb) -> cb.and(cb.equal(root.get("projectId"), projectId), cb.isNull(root.get("bizDescription"))))
                    .stream().sorted(Comparator.comparing(TableInfo::getTableName)).forEach(t -> targets.add(new AiDictionaryDto.Target(t.getId(), null)));
            case "TABLE_COLUMNS" -> {
                if (request.tableId() == null) throw new CustomException(ErrorCode.AI_JOB_INVALID, "테이블을 고르세요.");
                tables.findById(request.tableId()).filter(t -> projectId.equals(t.getProjectId())).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
                columns.findAllByTableInfo_IdIn(List.of(request.tableId())).stream()
                        .filter(c -> projectId.equals(c.getProjectId()))
                        .filter(c -> !Boolean.TRUE.equals(request.onlyMissing()) || c.getBizDescription() == null || c.getBizDescription().isBlank())
                        .sorted(Comparator.comparing((ColumnInfo c) -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()))
                        .forEach(c -> targets.add(new AiDictionaryDto.Target(request.tableId(), c.getId())));
            }
            case "SELECTED" -> {
                // 고른 테이블 : tables(기본 true) 면 그 테이블, columns 면 그 테이블의 컬럼도. 고른 컬럼 : 그 컬럼. onlyMissing 이면 업무 설명 있는 것은 뺀다(미리 보기에 DESCRIBED)
                if (request.targets() == null) throw new CustomException(ErrorCode.AI_JOB_INVALID, "대상을 고르세요.");
                boolean withTable = !Boolean.FALSE.equals(request.tables());
                boolean withColumns = Boolean.TRUE.equals(request.columns());
                for (AiDictionaryDto.Target t : request.targets()) {
                    if (t == null || (t.tableId() == null && t.columnId() == null)) continue;
                    if (t.columnId() != null) {
                        ColumnInfo c = columns.findAllByProjectIdAndId(projectId, t.columnId()).stream().findFirst()
                                .orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
                        targets.add(new AiDictionaryDto.Target(c.getTableInfo().getId(), c.getId()));
                        continue;
                    }
                    tables.findById(t.tableId()).filter(x -> projectId.equals(x.getProjectId())).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
                    if (withTable) targets.add(new AiDictionaryDto.Target(t.tableId(), null));
                    if (withColumns) {
                        columns.findAllByTableInfo_IdIn(List.of(t.tableId())).stream().filter(c -> projectId.equals(c.getProjectId()))
                                .sorted(Comparator.comparing((ColumnInfo c) -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()))
                                .forEach(c -> targets.add(new AiDictionaryDto.Target(t.tableId(), c.getId())));
                    }
                }
            }
            default -> throw new CustomException(ErrorCode.AI_JOB_INVALID, "대상 범위가 올바르지 않습니다.");
        }
        return targets;
    }

    /** 실행·미리 보기가 함께 쓰는 대상 판정 : 중복을 빼고, 대상마다 만든다 · 검토 대기 제안 있음(실행 때 건너뜀) · 업무 설명 있음(onlyMissing 이면 뺌) */
    private List<AiDictionaryDto.PreviewItem> resolve(Long projectId, AiDictionaryDto.JobRequest request) {
        boolean onlyMissing = Boolean.TRUE.equals(request == null ? null : request.onlyMissing());
        Set<String> seen = new LinkedHashSet<>();
        List<AiDictionaryDto.Target> unique = targets(projectId, request).stream().filter(t -> seen.add(t.tableId() + ":" + t.columnId())).toList();
        Set<Long> tableIds = unique.stream().map(AiDictionaryDto.Target::tableId).collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, TableInfo> tableById = new java.util.HashMap<>();
        tables.findAllById(tableIds).forEach(t -> tableById.put(t.getId(), t));
        java.util.Map<Long, ColumnInfo> columnById = new java.util.HashMap<>();
        columns.findAllByTableInfo_IdIn(tableIds).forEach(c -> columnById.put(c.getId(), c));
        Set<String> open = new java.util.HashSet<>();
        proposals.findAllByProjectIdAndStatusIn(projectId, EnumSet.of(AiDescriptionProposal.ReviewStatus.PROPOSED, AiDescriptionProposal.ReviewStatus.REVIEWED,
                AiDescriptionProposal.ReviewStatus.ON_HOLD, AiDescriptionProposal.ReviewStatus.REVIEW_REQUIRED)).forEach(p -> open.add(p.getTableId() + ":" + p.getColumnId()));
        List<AiDictionaryDto.PreviewItem> items = new ArrayList<>();
        for (AiDictionaryDto.Target t : unique) {
            TableInfo table = tableById.get(t.tableId());
            ColumnInfo column = t.columnId() == null ? null : columnById.get(t.columnId());
            String biz = column != null ? column.getBizDescription() : table == null ? null : table.getBizDescription();
            String name = table == null ? String.valueOf(t.tableId()) : column == null ? table.getTableName() : table.getTableName() + "." + column.getColumnName();
            String outcome = onlyMissing && biz != null && !biz.isBlank() ? "DESCRIBED" : open.contains(t.tableId() + ":" + t.columnId()) ? "OPEN_PROPOSAL" : "CREATE";
            items.add(new AiDictionaryDto.PreviewItem(column == null ? AiDescriptionProposal.TargetType.TABLE : AiDescriptionProposal.TargetType.COLUMN, t.tableId(),
                    t.columnId(), name, outcome));
        }
        return items;
    }

    private static List<AiDictionaryDto.Target> runnable(List<AiDictionaryDto.PreviewItem> items) {
        List<AiDictionaryDto.Target> targets = items.stream().filter(i -> !"DESCRIBED".equals(i.outcome())).map(i -> new AiDictionaryDto.Target(i.tableId(), i.columnId())).toList();
        if (targets.isEmpty()) throw new CustomException(ErrorCode.AI_JOB_INVALID, "만들 대상이 없습니다.");
        if (targets.size() > MAX_TARGETS) throw new CustomException(ErrorCode.AI_JOB_INVALID, "한 번에 {0}개까지 만들 수 있습니다. 범위를 좁혀주세요.", MAX_TARGETS);
        return targets;
    }

    /** 생성 대상 미리 보기 (실행과 같은 판정, 아무것도 만들지 않는다) */
    public AiDictionaryDto.JobPreview preview(ExecutionContext context, AiDictionaryDto.JobRequest request) {
        verifier.verify(context, "USER");
        List<AiDictionaryDto.PreviewItem> items = tx.execute(s -> resolve(context.projectId(), request));
        int create = (int) items.stream().filter(i -> "CREATE".equals(i.outcome())).count();
        int open = (int) items.stream().filter(i -> "OPEN_PROPOSAL".equals(i.outcome())).count();
        int described = (int) items.stream().filter(i -> "DESCRIBED".equals(i.outcome())).count();
        return new AiDictionaryDto.JobPreview(items.size() > 300 ? items.subList(0, 300) : items, create, open, described, create + open, MAX_TARGETS);
    }

    /** 오류 코드의 사용자 문구 (요청 언어, DEC-32) : 오류 코드 문구(errors_{ko,en}.properties)를 그대로 쓰고, 등록된 문구가 없는 코드는 일반 문구 */
    public static String errorMessage(String code) {
        if (code != null && Messages.hasError(code)) return Messages.error(code);
        return Messages.get("이 대상의 설명 초안을 만들지 못했습니다.");
    }

    static AiDictionaryDto.JobSummary summary(AiDescriptionJob j) {
        List<AiDictionaryDto.JobError> errors = j.getErrors() == null ? List.of()
                : JSON.readValue(j.getErrors(), new TypeReference<List<AiDictionaryDto.JobError>>() {
        }).stream().map(e -> new AiDictionaryDto.JobError(e.target(), e.code(), errorMessage(e.code()))).toList();
        return new AiDictionaryDto.JobSummary(j.getId(), j.getStatus(), j.getTargetCount(), j.getCompletedCount(), j.getFailedCount(), j.getSkippedCount(),
                j.audit() == null ? null : j.audit().created(), j.getStartedAt(), j.getEndedAt(), errors);
    }
}
