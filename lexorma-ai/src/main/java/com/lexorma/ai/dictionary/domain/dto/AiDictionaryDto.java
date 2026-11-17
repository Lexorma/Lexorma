package com.lexorma.ai.dictionary.domain.dto;

import com.lexorma.ai.dictionary.domain.entity.AiDescriptionJob;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposalHistory;
import com.lexorma.ai.dictionary.service.ProposalVersions;
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

    /**
     * replacesBizDescription : 생성 때 대상에 이 제안과 다른 업무 설명이 있었다 — 승인하면 그 설명을 교체한다 (생성 뒤 바뀌었으면 승인이 막힌다).
     * blind : 평가 대상의 실제 의미를 확인하기 전이라 AI 가 만든 내용(확실성·제안·확정 문구·근거 수·교체 여부)을 비워 보냈다 (AI-5-EVAL 2차)
     */
    public record ProposalSummary(Long id, AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String targetName, int cycle,
                                  AiDescriptionProposal.ReviewStatus status, AiDescriptionProposal.Certainty certainty, String proposedDescription,
                                  String confirmedDescription, String model, String promptVersion, AiDescriptionProposal.CreationMethod creationMethod,
                                  Long jobId, LocalDateTime createdAt, String reviewerId, LocalDateTime reviewedAt, String reviewReason, boolean recheckNeeded,
                                  long version, int evidenceTotal, int evidenceVerified, boolean replacesBizDescription, boolean blind) {
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
     * @param baseBizDescription 제안을 만들 때(AI 를 부르기 전) 대상의 업무 설명 — 승인은 지금 값이 이 값과 같을 때만 반영한다
     */
    /**
     * evaluationHold : 평가 대상으로 등록돼 평가를 마치기 전이라 승인할 수 없는 사유 (MEANING_PENDING · MEANING_UNCONFIRMABLE · NOT_JUDGED, 아니면 null).
     * 블라인드(proposal.blind)면 detail·evidence 는 null, 이력의 확정 문구는 비운다. bizDescriptionHidden : 블라인드인데 지금 업무 설명이 승인된 AI 제안 문구라
     * 그 값(현재 값·생성 당시 값)을 비우고 그 값이 든 변경 전후는 뺐다 — 평가 상세와 같은 규칙
     */
    public record ProposalDetail(ProposalSummary proposal, JsonNode detail, JsonNode evidence, Current current, List<ProposalVersions.Change> changes,
                                 List<HistoryItem> history, String baseBizDescription, String evaluationHold, boolean bizDescriptionHidden) {
    }

    /** 검토 요청 : version 은 화면이 본 제안 버전 (다르면 충돌). 승인의 description 은 수정 후 승인 문구 (비면 제안 문구 그대로) */
    public record ReviewRequest(Long version, String description, String reason) {
    }

    /** APPROVED · REVIEW_REQUIRED(근거 변경 — changes 참고) · REJECTED · ON_HOLD · REVIEWED · CLOSED */
    public record ReviewResult(String outcome, ProposalSummary proposal, List<ProposalVersions.Change> changes) {
    }

    /**
     * 근거 재검증 (재검토 필요 제안). acknowledge = 남은 변경(내용 변경만)을 검토자가 확인했다 — 사유 필수, 기준 버전을 지금으로 옮긴다
     */
    public record RevalidateRequest(Long version, Boolean acknowledge, String reason) {
    }

    /**
     * @param outcome         REVIEWABLE (검토 중으로 — 승인은 따로) · STILL_REQUIRED (그대로 재검토 필요)
     * @param acknowledgeable 남은 변경이 내용 변경뿐이라 확인 후 계속 검토할 수 있다 (대상·근거·원본 테이블 삭제, 업무 설명 수정이 있으면 false — 다시 만들거나 종결)
     * @param changes         지금 남아 있는 변경 (변경 전후 포함)
     */
    public record RevalidateResult(String outcome, boolean acknowledgeable, ProposalSummary proposal, List<ProposalVersions.Change> changes) {
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
    public record JobRequest(String scope, Long tableId, Boolean onlyMissing, List<Target> targets, Boolean tables, Boolean columns) {
        public JobRequest(String scope, Long tableId, Boolean onlyMissing, List<Target> targets) {
            this(scope, tableId, onlyMissing, targets, null, null);
        }
    }

    /**
     * 생성 대상 미리 보기 (실행과 같은 규칙) : 대상마다 CREATE(만든다) · OPEN_PROPOSAL(검토 대기 제안이 있어 실행 때 건너뛴다) · DESCRIBED(업무 설명이 있어 뺀다)
     *
     * @param targets 실행에 넣을 대상 수 (CREATE + OPEN_PROPOSAL, 최대 limit)
     */
    public record JobPreview(List<PreviewItem> items, int create, int openProposal, int described, int targets, int limit) {
    }

    public record PreviewItem(AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String name, String outcome) {
    }

    /**
     * 일괄 생성 오류 하나. code 는 오류 코드(저장 값), message 는 요청 언어의 사용자 문구 (DEC-32) — 저장하지 않고 응답할 때 code 로 만든다.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record JobError(String target, String code, String message) {
        public JobError(String target, String code) {
            this(target, code, null);
        }
    }

    public record JobSummary(Long id, AiDescriptionJob.Status status, int targetCount, int completedCount, int failedCount, int skippedCount,
                             LocalDateTime requestedAt, LocalDateTime startedAt, LocalDateTime endedAt, List<JobError> errors) {
    }

    /** 생성 대상 후보 : 원본 COMMENT·업무 설명·제안이 있는지 구분해 보여 준다 */
    public record TargetRow(AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String name, String description,
                            boolean hasSourceComment, boolean hasBizDescription, Long proposalId, AiDescriptionProposal.ReviewStatus proposalStatus,
                            boolean recheckNeeded, TargetDetail detail) {
    }

    /**
     * 대상 상세 (테이블을 펼쳤을 때만 채운다 — 목록에는 개수만). 비어 있는 설명은 null
     *
     * @param logicalName      테이블 : 테이블 코멘트(한글명) · 컬럼 : 연결된 표준 용어명
     * @param modelDescription 물리 모델 설명 (TBL_EXPLN · COL_EXPLN)
     * @param columnCount      테이블만 : 컬럼 수 · missingColumnCount : 업무 설명 없는 컬럼 수
     */
    public record TargetDetail(String schemaName, String physicalName, String logicalName, String modelDescription, String sourceComment, String bizDescription,
                               String dataType, Boolean pk, Boolean fk, String termName, String domainName, Integer columnCount, Integer missingColumnCount) {
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
                               int reviewRequired, int superseded, int closed, int open, Double approvalRate, Double editRate, Double rejectionRate,
                               Double invalidEvidenceRate, Double unsupportedRate, Double invalidRelationRate, Double unknownRate,
                               Double termMatchRate, int termMatchBase, Double medianReviewMinutes) {
    }

    public record Metrics(List<ModelMetrics> models, String note) {
    }
}
