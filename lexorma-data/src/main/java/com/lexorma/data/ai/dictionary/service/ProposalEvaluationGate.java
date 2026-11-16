package com.lexorma.data.ai.dictionary.service;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluation.Judgment;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.MeaningStatus;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionEvaluationRepository;
import com.lexorma.data.ai.dictionary.repository.AiDescriptionEvaluationTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 평가 중 승인 제한 (AI-5-EVAL F4) : 평가 대상으로 등록된 테이블·컬럼의 제안은 평가를 마치기 전에 승인하지 않는다 — 승인이 업무 설명(TASK_EXPLN)을 바꾸면
 * 뒤에 만드는 제안의 맥락이 달라져 평가가 독립적이지 않다. 평가 대상이 아닌 제안은 그대로다.
 * <ul>
 *   <li>평가 완료 = 대상의 실제 의미가 확인(CONFIRMED)이고 그 제안에 정확성 판정(맞음·일부 맞음·틀림)이 하나 이상 있다. 판정의 나머지 필수 항목은 저장 때 모두 채워진다.
 *       '평가할 수 없음' 판정과 확인 불가 대상은 완료가 아니다 (사용자 결정 2026-11-16)</li>
 *   <li>같은 메타데이터 여부(sameMetadata)는 지표의 비교 조건일 뿐 승인 조건이 아니다. 대상 존재·근거 변경은 승인의 기존 재검증(ProposalVersions)이 본다</li>
 *   <li>승인은 평가 대상 행을 잠그고 본다 (잠금 순서 : 제안 → 모델 대상 → 평가 대상). 판정 저장·실제 의미 수정도 그 행을 잠가 서로 줄 선다.
 *       평가 대상 등록은 아직 평가 대상 행이 없으므로 승인과 같은 모델 대상 행(테이블·컬럼)을 먼저 잠근다 — 등록이 먼저 커밋되면 승인이 그 대상을 보고, 승인이 먼저면 등록이 기다린다</li>
 * </ul>
 * 블라인드 (AI-5-EVAL 2차) : 평가 대상의 실제 의미가 확인(CONFIRMED)되기 전에는 그 대상의 AI 제안 내용을 어느 조회에도 보내지 않는다.
 * 평가 상세와 일반 제안 조회(목록·상세·이력·생성/검토 응답)가 이 판단({@link #blind})을 같이 쓴다.
 */
@Component
@RequiredArgsConstructor
class ProposalEvaluationGate {

    /** 승인 제한 사유 : 실제 의미 미확인 / 확인 불가 / 정확성 판정 없음 */
    enum Hold { MEANING_PENDING, MEANING_UNCONFIRMABLE, NOT_JUDGED }

    private static final Set<Judgment> ACCURACY = EnumSet.of(Judgment.CORRECT, Judgment.PARTIAL, Judgment.INCORRECT);

    private final AiDescriptionEvaluationTargetRepository targets;
    private final AiDescriptionEvaluationRepository judgments;

    /** 승인을 막는 사유 (평가 대상이 아니거나 평가를 마쳤으면 null). lock 이면 평가 대상 행을 잠근다 (승인 트랜잭션) */
    Hold hold(AiDescriptionProposal p, boolean lock) {
        Optional<AiDescriptionEvaluationTarget> found = p.getColumnId() == null
                ? (lock ? targets.lockTableTarget(p.getProjectId(), p.getTableId()) : targets.findTableTarget(p.getProjectId(), p.getTableId()))
                : (lock ? targets.lockColumnTarget(p.getProjectId(), p.getTableId(), p.getColumnId()) : targets.findColumnTarget(p.getProjectId(), p.getTableId(), p.getColumnId()));
        if (found.isEmpty()) return null;
        AiDescriptionEvaluationTarget target = found.get();
        if (target.getMeaningStatus() == MeaningStatus.PENDING) return Hold.MEANING_PENDING;
        if (target.getMeaningStatus() == MeaningStatus.UNCONFIRMABLE) return Hold.MEANING_UNCONFIRMABLE;
        boolean judged = judgments.findAllByProjectIdAndProposalId(p.getProjectId(), p.getId()).stream()
                .anyMatch(j -> j.getTargetId().equals(target.getId()) && ACCURACY.contains(j.getJudgment()));
        return judged ? null : Hold.NOT_JUDGED;
    }

    /** 이 평가 대상의 제안은 블라인드인가 : 실제 의미가 확인되지 않았다 (미확인·확인 불가) */
    static boolean blind(AiDescriptionEvaluationTarget target) {
        return target.getMeaningStatus() != MeaningStatus.CONFIRMED;
    }

    /** 업무 설명이 그 대상의 승인된 AI 제안 확정 문구와 같다 (AI 에서 온 값 — 블라인드에는 보내지 않는다) */
    static boolean fromAi(String bizDescription, Collection<AiDescriptionProposal> ps) {
        String biz = bizDescription == null || bizDescription.isBlank() ? null : bizDescription.strip();
        return biz != null && ps.stream().anyMatch(p -> p.getStatus() == AiDescriptionProposal.ReviewStatus.APPROVED
                && p.getConfirmedDescription() != null && biz.equals(p.getConfirmedDescription().strip()));
    }

    /** 제안 하나가 블라인드인가 (평가 대상이 아니면 false) */
    boolean blind(AiDescriptionProposal p) {
        Optional<AiDescriptionEvaluationTarget> found = p.getColumnId() == null ? targets.findTableTarget(p.getProjectId(), p.getTableId())
                : targets.findColumnTarget(p.getProjectId(), p.getTableId(), p.getColumnId());
        return found.map(ProposalEvaluationGate::blind).orElse(false);
    }

    /** 제안들 중 블라인드인 것의 ID (목록 한 쪽 : 그 테이블들의 평가 대상만 한 번 읽는다) */
    Set<Long> blindIds(Long projectId, Collection<AiDescriptionProposal> ps) {
        if (ps.isEmpty()) return Set.of();
        List<Long> tableIds = ps.stream().map(AiDescriptionProposal::getTableId).distinct().toList();
        Set<String> hidden = targets.findAllByProjectIdAndTableIdIn(projectId, tableIds).stream().filter(ProposalEvaluationGate::blind)
                .map(t -> t.getTableId() + ":" + t.getColumnId()).collect(Collectors.toSet());
        if (hidden.isEmpty()) return Set.of();
        Set<Long> ids = new HashSet<>();
        for (AiDescriptionProposal p : ps) if (hidden.contains(p.getTableId() + ":" + p.getColumnId())) ids.add(p.getId());
        return ids;
    }
}
