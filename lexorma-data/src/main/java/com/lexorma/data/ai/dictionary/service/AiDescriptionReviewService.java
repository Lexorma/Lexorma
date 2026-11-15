package com.lexorma.data.ai.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal.ReviewStatus;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory.Action;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalHistoryRepository;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * AI 설명 제안 검토 (AI-5) : 승인 · 수정 후 승인 · 반려 · 보류 · 검토함 표시, 일괄 처리, 근거 변경 재확인.
 * <ul>
 *   <li>권한 : 로그인 사용자(USER 실행)만. 승인 권한 = 기존 {@code AuthUtil.isApprovalAvailable()} 와 같은 기준(ADMIN·MANAGER·프로젝트 관리자)을 DB 에서 다시 확인 + USER 역할.
 *       화면 버튼 숨김에 기대지 않는다</li>
 *   <li>동시성 : 제안 행을 잠그고(PESSIMISTIC_WRITE) 화면이 본 버전(VER_NO)과 비교한다 — 다르면 {@link ErrorCode#AI_PROPOSAL_CONFLICT}. 승인은 대상 행(테이블·컬럼)도 잠근다
 *       (잠금 순서 : 제안 → 대상). 상태 변경·업무 설명 반영·이력은 한 트랜잭션이다</li>
 *   <li>승인 시 재검증 : 업무 설명이 생성 때와 다르면(다른 사용자의 수정), 대상이 지워졌으면, 근거로 쓴 객체·매핑된 원본 값이 바뀌었으면({@link ProposalVersions})
 *       반영하지 않고 REVIEW_REQUIRED 로 바꿔 변경 내용을 돌려준다. 근거와 무관한 변경은 승인을 막지 않는다</li>
 *   <li>승인은 업무 설명(TASK_EXPLN)만 쓴다 : 객체의 승인 여부와 무관하고(사용자 결정 2026-11-15), 원본 COMMENT·DDL COMMENT(설명)·표준 용어 연결은 바꾸지 않는다.
 *       표준 용어·도메인 추천은 참고로만 보이고 반영하지 않는다 (기존 컬럼 화면의 용어 연결로 바꾼다)</li>
 * </ul>
 */
@Slf4j
@Service
public class AiDescriptionReviewService {

    static final int REASON_MAX = 1000;
    static final int DESCRIPTION_MAX = 4000;
    static final int BULK_MAX = 100;
    static final int RECHECK_MAX = 500;
    private static final Set<ReviewStatus> APPROVABLE = EnumSet.of(ReviewStatus.PROPOSED, ReviewStatus.REVIEWED, ReviewStatus.ON_HOLD);

    private final AiDescriptionProposalRepository proposals;
    private final AiDescriptionProposalHistoryRepository histories;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final ProposalVersions versions;
    private final ExecutionContextVerifier verifier;
    private final TransactionTemplate tx;
    private final ModelChangeRecorder recorder;

    public AiDescriptionReviewService(AiDescriptionProposalRepository proposals, AiDescriptionProposalHistoryRepository histories, TableInfoRepository tables,
                                      ColumnInfoRepository columns, ProposalVersions versions, ExecutionContextVerifier verifier,
                                      PlatformTransactionManager transactionManager, ModelChangeRecorder recorder) {
        this.recorder = recorder;
        this.proposals = proposals;
        this.histories = histories;
        this.tables = tables;
        this.columns = columns;
        this.versions = versions;
        this.verifier = verifier;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(30);
    }

    /** 승인 (description 이 있으면 수정 후 승인). 근거가 바뀌었으면 반영하지 않고 REVIEW_REQUIRED */
    public AiDictionaryDto.ReviewResult approve(ExecutionContext context, Long id, AiDictionaryDto.ReviewRequest request) {
        VerifiedExecution approver = approver(context);
        String edited = request == null || request.description() == null || request.description().isBlank() ? null : request.description().strip();
        if (edited != null && edited.length() > DESCRIPTION_MAX) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "업무 설명은 {0}자까지 입력할 수 있습니다.", DESCRIPTION_MAX);
        return tx.execute(status -> {
            AiDescriptionProposal p = locked(context.projectId(), id, request == null ? null : request.version());
            if (!APPROVABLE.contains(p.getStatus())) throw new CustomException(ErrorCode.AI_PROPOSAL_INVALID_STATE);
            Object target = lockTarget(p);
            ProposalVersions.Base base = ProposalVersions.read(p.getBaseVersions());
            List<ProposalVersions.Change> changes = target == null
                    ? List.of(new ProposalVersions.Change("TARGET_DELETED", base.target().type(), base.target().id(), base.target().name(), List.of()))
                    : versions.changes(context.projectId(), base, bizDescription(target), true);
            LocalDateTime now = LocalDateTime.now();
            if (!changes.isEmpty()) {
                transition(p, ReviewStatus.REVIEW_REQUIRED, Action.REVIEW_REQUIRED, approver.username(), now, reason(changes), null);
                log.info("AI 설명 제안 재검토 필요 (승인 시 변경 감지) : project={} proposal={} changes={}", context.projectId(), id, changes);
                return new AiDictionaryDto.ReviewResult("REVIEW_REQUIRED", ProposalMapper.summary(p), changes);
            }
            String finalText = edited != null ? edited : p.getProposedDescription();
            boolean modified = edited != null && !edited.equals(p.getProposedDescription().strip());
            // 모델 변경 이력에 "AI 제안 승인" 으로 남긴다 (사람이 업무 설명을 직접 고친 것은 USER)
            recorder.markAiApproval();
            setBizDescription(target, finalText);
            p.setConfirmedDescription(finalText);
            p.setRecheckNeeded(false);
            transition(p, ReviewStatus.APPROVED, modified ? Action.APPROVE_EDITED : Action.APPROVE, approver.username(), now, null, finalText);
            return new AiDictionaryDto.ReviewResult("APPROVED", ProposalMapper.summary(p), List.of());
        });
    }

    public AiDictionaryDto.ReviewResult reject(ExecutionContext context, Long id, AiDictionaryDto.ReviewRequest request) {
        String reason = reasonOf(request);
        if (reason == null) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "반려 사유를 입력해주세요.");
        return simple(context, id, request, EnumSet.of(ReviewStatus.PROPOSED, ReviewStatus.REVIEWED, ReviewStatus.ON_HOLD, ReviewStatus.REVIEW_REQUIRED),
                ReviewStatus.REJECTED, Action.REJECT, reason);
    }

    public AiDictionaryDto.ReviewResult hold(ExecutionContext context, Long id, AiDictionaryDto.ReviewRequest request) {
        return simple(context, id, request, EnumSet.of(ReviewStatus.PROPOSED, ReviewStatus.REVIEWED, ReviewStatus.REVIEW_REQUIRED),
                ReviewStatus.ON_HOLD, Action.HOLD, reasonOf(request));
    }

    /**
     * 종결 (적용하지 않음) : 업무 설명을 직접 고쳤거나 대상이 없어지는 등 더 쓸 데가 없는 제안을 사유와 함께 닫는다. 업무 설명은 건드리지 않는다.
     * 반려(제안이 틀림)와 구분해 품질 지표(승인율·반려율)에 넣지 않는다
     */
    public AiDictionaryDto.ReviewResult close(ExecutionContext context, Long id, AiDictionaryDto.ReviewRequest request) {
        String reason = reasonOf(request);
        if (reason == null) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "종결 사유를 입력해주세요.");
        return simple(context, id, request, EnumSet.of(ReviewStatus.PROPOSED, ReviewStatus.REVIEWED, ReviewStatus.ON_HOLD, ReviewStatus.REVIEW_REQUIRED),
                ReviewStatus.CLOSED, Action.CLOSE, reason);
    }

    /**
     * 근거 재검증 (REVIEW_REQUIRED) : 생성 때 기준과 지금 메타데이터를 다시 비교한다 (승인 때와 같은 비교 — 대상·근거 객체의 존재·변경 이력, 원본 값, 업무 설명).
     * <ul>
     *   <li>남은 변경이 없으면 검토 중(REVIEWED)으로 — 승인은 따로 한다 (자동 승인 없음)</li>
     *   <li>대상·근거·원본 테이블이 없어졌거나 업무 설명을 누가 고쳤으면 그대로 둔다 → 다시 만들거나 종결</li>
     *   <li>내용 변경뿐이면 검토자가 확인(acknowledge + 사유)했을 때만 기준 버전을 지금으로 옮기고 검토 중으로 (그 뒤 바뀌면 승인 때 다시 걸린다)</li>
     * </ul>
     * AI 서버를 부르지 않으므로 Provider 가 없거나 멈춰도 된다. 권한은 검토(승인) 권한
     */
    public AiDictionaryDto.RevalidateResult revalidate(ExecutionContext context, Long id, AiDictionaryDto.RevalidateRequest request) {
        VerifiedExecution approver = approver(context);
        boolean acknowledge = request != null && Boolean.TRUE.equals(request.acknowledge());
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        if (reason != null && reason.length() > REASON_MAX) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "사유는 {0}자까지 입력할 수 있습니다.", REASON_MAX);
        if (acknowledge && reason == null) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "확인한 변경과 계속 검토하는 이유를 입력해주세요.");
        return tx.execute(status -> {
            AiDescriptionProposal p = locked(context.projectId(), id, request == null ? null : request.version());
            if (p.getStatus() != ReviewStatus.REVIEW_REQUIRED) throw new CustomException(ErrorCode.AI_PROPOSAL_INVALID_STATE);
            Object target = lockTarget(p);
            ProposalVersions.Base base = ProposalVersions.read(p.getBaseVersions());
            List<ProposalVersions.Change> changes = target == null
                    ? List.of(new ProposalVersions.Change("TARGET_DELETED", base.target().type(), base.target().id(), base.target().name(), List.of()))
                    : versions.changes(context.projectId(), base, bizDescription(target), true);
            LocalDateTime now = LocalDateTime.now();
            if (changes.isEmpty()) {
                transition(p, ReviewStatus.REVIEWED, Action.REVALIDATE, approver.username(), now, "근거 재검증 : 생성 때와 달라진 근거가 없습니다", null);
                return new AiDictionaryDto.RevalidateResult("REVIEWABLE", false, ProposalMapper.summary(p), List.of());
            }
            boolean acknowledgeable = changes.stream().noneMatch(c -> ProposalVersions.BLOCKING.contains(c.kind()));
            if (acknowledgeable && acknowledge) {
                var rebased = versions.rebase(context.projectId(), base, bizDescription(target));
                if (rebased.isPresent()) {
                    p.setBaseVersions(ProposalVersions.write(rebased.get()));
                    String note = ("근거 재검증 : 변경 확인 후 계속 검토 (" + reason(changes) + ") — " + reason);
                    transition(p, ReviewStatus.REVIEWED, Action.REVALIDATE, approver.username(), now, note.length() > REASON_MAX ? note.substring(0, REASON_MAX) : note, null);
                    log.info("AI 설명 제안 근거 재검증 (변경 확인) : project={} proposal={} changes={}", context.projectId(), id, changes.size());
                    return new AiDictionaryDto.RevalidateResult("REVIEWABLE", false, ProposalMapper.summary(p), changes);
                }
                acknowledgeable = false;
            }
            return new AiDictionaryDto.RevalidateResult("STILL_REQUIRED", acknowledgeable, ProposalMapper.summary(p), changes);
        });
    }

    /** 검토함 표시 (PROPOSED → REVIEWED). 보류한 제안을 다시 검토 대기로 돌릴 때도 쓴다 */
    public AiDictionaryDto.ReviewResult markReviewed(ExecutionContext context, Long id, AiDictionaryDto.ReviewRequest request) {
        return simple(context, id, request, EnumSet.of(ReviewStatus.PROPOSED, ReviewStatus.ON_HOLD), ReviewStatus.REVIEWED, Action.REVIEW, reasonOf(request));
    }

    /** 일괄 검토 : 항목마다 따로(각자의 트랜잭션) 처리하고 결과를 모은다. 승인도 항목마다 재검증한다 */
    public List<AiDictionaryDto.BulkReviewItem> bulk(ExecutionContext context, AiDictionaryDto.BulkReviewRequest request) {
        approver(context);
        if (request == null || request.items() == null || request.items().isEmpty()) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "처리할 제안을 고르세요.");
        if (request.items().size() > BULK_MAX) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "한 번에 {0}개까지 처리할 수 있습니다.", BULK_MAX);
        String action = request.action() == null ? "" : request.action().strip().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT", "HOLD").contains(action)) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "일괄 처리는 승인·반려·보류만 할 수 있습니다.");
        List<AiDictionaryDto.BulkReviewItem> results = new ArrayList<>();
        for (AiDictionaryDto.BulkItem item : request.items()) {
            AiDictionaryDto.ReviewRequest each = new AiDictionaryDto.ReviewRequest(item.version(), null, request.reason());
            try {
                AiDictionaryDto.ReviewResult result = switch (action) {
                    case "APPROVE" -> approve(context, item.id(), each);
                    case "REJECT" -> reject(context, item.id(), each);
                    default -> hold(context, item.id(), each);
                };
                results.add(new AiDictionaryDto.BulkReviewItem(item.id(), result.outcome(), null));
            } catch (CustomException e) {
                // 권한이 없어졌으면 나머지도 할 수 없다
                if (e.getErrorCode() == ErrorCode.PROJECT_ACCESS_DENIED || e.getErrorCode() == ErrorCode.EXECUTION_PERMISSION_DENIED
                        || e.getErrorCode() == ErrorCode.APPROVAL_PERMISSION_DENIED) throw e;
                results.add(new AiDictionaryDto.BulkReviewItem(item.id(), "FAILED", e.getErrorCode().name()));
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                results.add(new AiDictionaryDto.BulkReviewItem(item.id(), "FAILED", ErrorCode.AI_PROPOSAL_CONFLICT.name()));
            }
        }
        return results;
    }

    /**
     * 근거 변경 재확인 (선택적 재검토) : 검토 대기 제안 중 근거·대상·원본 값이 바뀐 것만 REVIEW_REQUIRED 로, 승인된 제안 중 그 뒤 근거가 바뀐 것은
     * "재검토 필요" 표시만 한다 (업무 설명·승인 상태·이력은 그대로). 제안마다 짧은 트랜잭션이다. USER 이상이면 할 수 있다 (상태 판단은 서버가 한다)
     */
    public AiDictionaryDto.RecheckResult recheck(ExecutionContext context) {
        VerifiedExecution execution = verifier.verify(context, "USER");
        Long projectId = context.projectId();
        List<Long> ids = tx.execute(s -> proposals.findAllByProjectIdAndStatusIn(projectId,
                EnumSet.of(ReviewStatus.PROPOSED, ReviewStatus.REVIEWED, ReviewStatus.ON_HOLD, ReviewStatus.APPROVED)).stream()
                .filter(p -> p.getStatus() != ReviewStatus.APPROVED || !Boolean.TRUE.equals(p.getRecheckNeeded()))
                .map(AiDescriptionProposal::getId).limit(RECHECK_MAX).toList());
        int required = 0;
        int flagged = 0;
        for (Long id : ids) {
            String outcome = tx.execute(s -> {
                AiDescriptionProposal p = proposals.findForUpdate(id, projectId).orElse(null);
                if (p == null) return null;
                ProposalVersions.Base base = ProposalVersions.read(p.getBaseVersions());
                boolean approved = p.getStatus() == ReviewStatus.APPROVED;
                // 승인된 제안은 승인으로 업무 설명이 바뀌었으므로 업무 설명은 비교하지 않는다
                List<ProposalVersions.Change> changes = versions.changes(projectId, base, null, false);
                if (changes.isEmpty()) return null;
                LocalDateTime now = LocalDateTime.now();
                if (approved) {
                    p.setRecheckNeeded(true);
                    histories.save(ProposalWriter.history(p, Action.RECHECK_FLAGGED, ReviewStatus.APPROVED, execution.username(), now, reason(changes), null));
                    return "FLAGGED";
                }
                transition(p, ReviewStatus.REVIEW_REQUIRED, Action.REVIEW_REQUIRED, execution.username(), now, reason(changes), null);
                return "REQUIRED";
            });
            if ("REQUIRED".equals(outcome)) required++;
            if ("FLAGGED".equals(outcome)) flagged++;
        }
        log.info("AI 설명 제안 재확인 : project={} checked={} reviewRequired={} flagged={}", projectId, ids.size(), required, flagged);
        return new AiDictionaryDto.RecheckResult(ids.size(), required, flagged);
    }

    // ------------------------------------------------------------------ 공통

    private AiDictionaryDto.ReviewResult simple(ExecutionContext context, Long id, AiDictionaryDto.ReviewRequest request, Set<ReviewStatus> from, ReviewStatus to,
                                                Action action, String reason) {
        VerifiedExecution approver = approver(context);
        return tx.execute(status -> {
            AiDescriptionProposal p = locked(context.projectId(), id, request == null ? null : request.version());
            if (!from.contains(p.getStatus())) throw new CustomException(ErrorCode.AI_PROPOSAL_INVALID_STATE);
            transition(p, to, action, approver.username(), LocalDateTime.now(), reason, null);
            return new AiDictionaryDto.ReviewResult(to.name(), ProposalMapper.summary(p), List.of());
        });
    }

    /** 승인 권한 : 로그인 사용자 + USER 역할 + (ADMIN·MANAGER 역할 또는 프로젝트 관리자) — 지금 DB 기준 */
    VerifiedExecution approver(ExecutionContext context) {
        if (context == null || context.type() != ExecutionType.USER) throw new CustomException(ErrorCode.EXECUTION_PERMISSION_DENIED);
        VerifiedExecution execution = verifier.verify(context, "USER");
        if (!(execution.manager() || execution.hasRole("MANAGER"))) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        return execution;
    }

    private AiDescriptionProposal locked(Long projectId, Long id, Long expectedVersion) {
        AiDescriptionProposal p = proposals.findForUpdate(id, projectId).orElseThrow(() -> new CustomException(ErrorCode.AI_PROPOSAL_NOT_FOUND));
        if (expectedVersion == null || !expectedVersion.equals(p.getVersion())) throw new CustomException(ErrorCode.AI_PROPOSAL_CONFLICT);
        return p;
    }

    private void transition(AiDescriptionProposal p, ReviewStatus to, Action action, String user, LocalDateTime at, String reason, String confirmed) {
        ReviewStatus before = p.getStatus();
        p.setStatus(to);
        p.setReviewerId(user);
        p.setReviewedAt(at);
        p.setReviewReason(reason);
        proposals.saveAndFlush(p);
        histories.save(ProposalWriter.history(p, action, before, user, at, reason, confirmed));
    }

    /** 대상 행을 잠그고 돌려준다 (지워졌으면 null) */
    private Object lockTarget(AiDescriptionProposal p) {
        if (p.getColumnId() != null) {
            return columns.findForUpdate(p.getColumnId(), p.getProjectId()).filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(p.getTableId())).orElse(null);
        }
        return tables.findForUpdate(p.getTableId(), p.getProjectId()).orElse(null);
    }

    private static String bizDescription(Object target) {
        return target instanceof ColumnInfo c ? c.getBizDescription() : ((TableInfo) target).getBizDescription();
    }

    /** 업무 설명만 쓴다 (객체 승인 상태와 무관 — 다른 속성의 승인 잠금은 그대로) */
    private static void setBizDescription(Object target, String text) {
        if (target instanceof ColumnInfo c) c.setBizDescription(text);
        else ((TableInfo) target).setBizDescription(text);
    }

    private static String reasonOf(AiDictionaryDto.ReviewRequest request) {
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();
        if (reason != null && reason.length() > REASON_MAX) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "사유는 {0}자까지 입력할 수 있습니다.", REASON_MAX);
        return reason;
    }

    /** 변경 요약 (사유 칸, 1000자 이내) */
    static String reason(List<ProposalVersions.Change> changes) {
        StringBuilder sb = new StringBuilder();
        for (ProposalVersions.Change c : changes) {
            String one = c.kind() + " " + (c.type() == null ? "" : c.type() + ":" + c.id()) + (c.name() == null ? "" : " " + c.name())
                    + (c.fields().isEmpty() ? "" : " " + c.fields());
            if (sb.length() + one.length() + 2 > REASON_MAX) break;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(one);
        }
        return sb.toString();
    }

    static boolean same(String a, String b) {
        return Objects.equals(a == null || a.isBlank() ? null : a.strip(), b == null || b.isBlank() ? null : b.strip());
    }
}
