package com.lexorma.data.ai.dictionary.domain.dto;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionJob;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import com.lexorma.data.ai.dictionary.service.ProposalVersions;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 데이터 사전 초안 (AI-5) API. 프로젝트는 요청에 두지 않는다 : 로그인 프로젝트다.
 */
public final class AiDictionaryDto {

    private AiDictionaryDto() {
    }

    /** 대상 하나 : 테이블(columnId 없음) 또는 컬럼 */
    public record Target(Long tableId, Long columnId) {
    }

    /** 단건 생성. regenerate 면 검토 대기 중인 제안을 새 제안으로 대체한다 (앞 제안·이력은 남는다) */
    public record GenerateRequest(Long tableId, Long columnId, Boolean regenerate) {
    }

    /** CREATED : 새 제안 · SKIPPED : 검토 대기 중인 제안이 있어 만들지 않음 (그 제안) */
    public record GenerateResult(String outcome, ProposalSummary proposal) {
    }

    public record ProposalSummary(Long id, AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String targetName, int cycle,
                                  AiDescriptionProposal.ReviewStatus status, AiDescriptionProposal.Certainty certainty, String proposedDescription,
                                  String confirmedDescription, String model, String promptVersion, AiDescriptionProposal.CreationMethod creationMethod,
                                  Long jobId, LocalDateTime createdAt, String reviewerId, LocalDateTime reviewedAt, String reviewReason, boolean recheckNeeded,
                                  long version, int evidenceTotal, int evidenceVerified) {
    }

    /** 대상의 지금 값 (비교용). exists=false 면 대상이 지워졌다 */
    public record Current(boolean exists, String name, String description, String sourceComment, String bizDescription, Boolean approved) {
    }

    public record HistoryItem(Long id, AiDescriptionProposalHistory.Action action, AiDescriptionProposal.ReviewStatus before,
                              AiDescriptionProposal.ReviewStatus after, String processorId, LocalDateTime processedAt, String reason, String confirmedDescription) {
    }

    /**
     * @param detail  이름 해석·추천(검증 결과 포함)·연관 객체·불확실한 부분·확인 질문 (저장한 JSON)
     * @param evidence 근거와 검증 결과 (저장한 JSON)
     * @param changes 생성 뒤 지금까지 제안에 영향을 주는 변경 (미리 보기 : 상태는 바꾸지 않는다)
     */
    public record ProposalDetail(ProposalSummary proposal, JsonNode detail, JsonNode evidence, Current current, List<ProposalVersions.Change> changes,
                                 List<HistoryItem> history) {
    }

    /** 검토 요청 : version 은 화면이 본 제안 버전 (다르면 충돌). 승인의 description 은 수정 후 승인 문구 (비면 제안 문구 그대로) */
    public record ReviewRequest(Long version, String description, String reason) {
    }

    /** APPROVED · REVIEW_REQUIRED(근거 변경 — changes 참고) · REJECTED · ON_HOLD · REVIEWED */
    public record ReviewResult(String outcome, ProposalSummary proposal, List<ProposalVersions.Change> changes) {
    }

    public record BulkItem(Long id, Long version) {
    }

    /** 일괄 검토 : APPROVE · REJECT · HOLD. 항목마다 따로 처리한다 (하나가 실패해도 나머지는 진행) */
    public record BulkReviewRequest(String action, List<BulkItem> items, String reason) {
    }

    /** outcome 은 ReviewResult 와 같고, 실패면 FAILED + 오류 코드 */
    public record BulkReviewItem(Long id, String outcome, String errorCode) {
    }

    /**
     * 비동기 생성 요청 : MISSING_TABLES(업무 설명 없는 테이블) · TABLE_COLUMNS(테이블의 컬럼, onlyMissing 이면 업무 설명 없는 것만) · SELECTED(고른 대상)
     */
    public record JobRequest(String scope, Long tableId, Boolean onlyMissing, List<Target> targets) {
    }

    public record JobError(String target, String code) {
    }

    public record JobSummary(Long id, AiDescriptionJob.Status status, int targetCount, int completedCount, int failedCount, int skippedCount,
                             LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime endedAt, List<JobError> errors) {
    }

    /** 생성 대상 후보 : 원본 COMMENT·업무 설명·제안이 있는지 구분해 보여 준다 */
    public record TargetRow(AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String name, String description,
                            boolean hasSourceComment, boolean hasBizDescription, Long proposalId, AiDescriptionProposal.ReviewStatus proposalStatus,
                            boolean recheckNeeded) {
    }

    /** 테이블·컬럼 화면·ERD 용 : 그 테이블과 컬럼의 업무 설명·최근 제안 상태 */
    public record ObjectStatus(AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String bizDescription, Long proposalId,
                               AiDescriptionProposal.ReviewStatus proposalStatus, boolean recheckNeeded) {
    }

    /** 재확인 결과 : 재검토 필요로 바꾼 검토 대기 제안 수, 다시 볼 필요가 생긴 승인 제안 수 */
    public record RecheckResult(int checked, int reviewRequired, int recheckFlagged) {
    }

    /**
     * 모델·프롬프트 버전별 품질 지표 (U-20). 비율의 분모가 0 이면 null. 업무적 정확성은 사람의 판정(기준 데이터)이 있어야 하므로 여기서 계산하지 않는다
     *
     * @param approvalRate          승인 / (승인 + 반려)
     * @param editRate              수정 후 승인 / 승인
     * @param rejectionRate         반려 / (승인 + 반려)
     * @param invalidEvidenceRate   든 근거 중 없는·범위 밖·불일치 근거의 비율 (존재하지 않는 객체 언급)
     * @param unsupportedRate       근거가 하나도 확인되지 않았는데 AI 가 VERIFIED·INFERRED 라고 한 제안의 비율 (근거 없는 단정)
     * @param invalidRelationRate   근거로 든 관계 중 확인되지 않은 비율 (잘못된 관계 추론)
     * @param unknownRate           확실성 UNKNOWN 비율
     * @param termMatchRate         표준 용어를 추천한 컬럼 중, 그 컬럼에 지금 연결된 승인 용어와 같은 비율 (용어가 연결된 컬럼만)
     * @param medianReviewMinutes   생성 → 첫 검토까지 걸린 시간의 중앙값 (분, 작성 시간 절감의 참고값 — 수작업 시간과 직접 비교한 값이 아니다)
     */
    public record ModelMetrics(String model, String promptVersion, int generated, int approved, int approvedEdited, int rejected, int onHold,
                               int reviewRequired, int superseded, int open, Double approvalRate, Double editRate, Double rejectionRate,
                               Double invalidEvidenceRate, Double unsupportedRate, Double invalidRelationRate, Double unknownRate,
                               Double termMatchRate, int termMatchBase, Double medianReviewMinutes) {
    }

    public record Metrics(List<ModelMetrics> models, String note) {
    }
}
