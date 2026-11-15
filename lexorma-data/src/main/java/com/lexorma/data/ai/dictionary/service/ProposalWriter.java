package com.lexorma.data.ai.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal.ReviewStatus;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalHistoryRepository;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * AI 설명 제안 저장 (AI-5) : AI 호출이 끝난 뒤 짧은 쓰기 트랜잭션 하나로 저장한다 (AI 를 기다리는 동안 DB 쓰기 트랜잭션을 잡지 않는다).
 * 대상 행(테이블·컬럼)을 잠가 같은 대상의 동시 생성을 줄 세우고, 검토 대기 중인 제안이 있으면 새로 만들지 않는다 (재생성이면 그 제안을 SUPERSEDED 로 남기고 새 차수).
 * 업무 설명·원본 COMMENT·객체 승인 상태는 바꾸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ProposalWriter {

    private final AiDescriptionProposalRepository proposals;
    private final AiDescriptionProposalHistoryRepository histories;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;

    public record Saved(boolean created, AiDescriptionProposal proposal) {
    }

    /** 검토 대기 중인 제안 (읽기) */
    @Transactional(readOnly = true)
    public Optional<AiDescriptionProposal> open(Long projectId, Long tableId, Long columnId) {
        return proposals.findTarget(projectId, tableId, columnId).stream().filter(p -> p.getStatus().open()).findFirst();
    }

    @Transactional
    public Saved create(AiDescriptionProposal draft, boolean regenerate, String processor, String reason) {
        Long projectId = draft.getProjectId();
        lockTarget(projectId, draft.getTableId(), draft.getColumnId());
        List<AiDescriptionProposal> existing = proposals.findTarget(projectId, draft.getTableId(), draft.getColumnId());
        List<AiDescriptionProposal> open = existing.stream().filter(p -> p.getStatus().open()).toList();
        if (!open.isEmpty() && !regenerate) return new Saved(false, open.getFirst());
        LocalDateTime now = LocalDateTime.now();
        draft.setCycle(existing.stream().map(AiDescriptionProposal::getCycle).max(Comparator.naturalOrder()).orElse(0) + 1);
        AiDescriptionProposal saved = proposals.save(draft);
        // 대체한 제안과 새 제안을 이력으로 잇는다 (앞 제안·이력은 지우지 않는다)
        for (AiDescriptionProposal old : open) {
            ReviewStatus before = old.getStatus();
            old.setStatus(ReviewStatus.SUPERSEDED);
            histories.save(history(old, AiDescriptionProposalHistory.Action.SUPERSEDE, before, processor, now, "새 제안 #" + saved.getId() + " 으로 대체", null));
        }
        String replaced = open.isEmpty() ? null : "제안 #" + open.stream().map(o -> String.valueOf(o.getId())).collect(java.util.stream.Collectors.joining(", #")) + " 을 대체";
        String note = reason == null ? replaced : replaced == null ? reason : reason + " · " + replaced;
        histories.save(history(saved, AiDescriptionProposalHistory.Action.GENERATE, null, processor, now, note, null));
        return new Saved(true, saved);
    }

    /** 대상 행을 잠근다 (없거나 다른 프로젝트면 오류) */
    void lockTarget(Long projectId, Long tableId, Long columnId) {
        if (columnId == null) {
            tables.findForUpdate(tableId, projectId).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
        } else {
            columns.findForUpdate(columnId, projectId).filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(tableId))
                    .orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
        }
    }

    static AiDescriptionProposalHistory history(AiDescriptionProposal p, AiDescriptionProposalHistory.Action action, ReviewStatus before, String processor,
                                                LocalDateTime at, String reason, String confirmed) {
        return AiDescriptionProposalHistory.builder().projectId(p.getProjectId()).proposalId(p.getId()).action(action).before(before).after(p.getStatus())
                .processorId(processor).processedAt(at).reason(reason).confirmedDescription(confirmed).build();
    }
}
