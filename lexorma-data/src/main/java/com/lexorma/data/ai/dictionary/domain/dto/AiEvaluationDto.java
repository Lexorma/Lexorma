package com.lexorma.data.ai.dictionary.domain.dto;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluation;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 설명 품질 평가 (AI-5 후속) API. 기준 데이터(평가 대상·실제 의미)와 판정은 사람이 적는다. 프로젝트는 로그인 프로젝트다.
 */
public final class AiEvaluationDto {

    private AiEvaluationDto() {
    }

    /** 평가 대상 후보 : 규칙으로 고른 범주 (사람이 고쳐 등록한다) */
    public record Candidate(AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String name, List<AiDescriptionEvaluationTarget.Category> categories,
                            boolean registered, int proposalCount) {
    }

    public record RegisterItem(Long tableId, Long columnId, List<String> categories) {
    }

    public record RegisterRequest(List<RegisterItem> items) {
    }

    /** created : 새로 등록 · skipped : 이미 등록된 대상 */
    public record RegisterResult(int created, int skipped) {
    }

    /** 실제 의미·확인 상태·범주 수정. status : PENDING · CONFIRMED(실제 의미 필수) · UNCONFIRMABLE */
    public record TargetUpdate(Long version, List<String> categories, String actualMeaning, String status) {
    }

    public record TargetRow(Long id, AiDescriptionProposal.TargetType targetType, Long tableId, Long columnId, String targetName, boolean exists,
                            List<AiDescriptionEvaluationTarget.Category> categories, AiDescriptionEvaluationTarget.MeaningStatus meaningStatus, boolean hasActualMeaning,
                            int proposalCount, int judgmentCount, long version) {
    }

    /** 평가 대상의 제안 하나 : 같은 메타데이터(등록 시점 버전)로 만든 제안인지와 판정 */
    public record ProposalItem(Long id, String model, String promptVersion, LocalDateTime createdAt, AiDescriptionProposal.ReviewStatus status, boolean edited,
                               AiDescriptionProposal.Certainty certainty, String proposedDescription, String confirmedDescription, JsonNode detail, JsonNode evidence,
                               boolean sameMetadata, int invalidReferences) {
    }

    public record JudgmentRow(Long id, Long proposalId, String model, String promptVersion, AiDescriptionEvaluation.Judgment judgment, boolean unfoundedClaim,
                              String missingInformation, AiDescriptionEvaluation.UncertaintyJudgment uncertainty,
                              AiDescriptionEvaluation.RecommendationJudgment termRecommendation, AiDescriptionEvaluation.RecommendationJudgment domainRecommendation,
                              String requiredEdits, Integer reviewMinutes, Integer writingMinutes, String reviewerId, LocalDateTime reviewedAt, long version) {
    }

    public record TargetDetail(TargetRow target, String actualMeaning, String meaningReviewerId, LocalDateTime meaningReviewedAt, List<ProposalItem> proposals,
                               List<JudgmentRow> judgments) {
    }

    /** 판정 저장 : 같은 검토자의 판정이 있으면 고친다 (version 필요) */
    public record JudgmentRequest(Long targetId, Long proposalId, Long version, String judgment, Boolean unfoundedClaim, String missingInformation, String uncertainty,
                                  String termRecommendation, String domainRecommendation, String requiredEdits, Integer reviewMinutes, Integer writingMinutes) {
    }

    /** 비율 하나 : 값(분모 0 이면 null)과 분자·분모 */
    public record Rate(Double value, int count, int base) {
    }

    /**
     * 모델·프롬프트 버전별 지표. 정확성 등은 사람의 판정, 허구 객체는 서버의 근거 검증(존재하지 않음·범위 밖·불일치)이다. AI 의 확실성은 쓰지 않는다
     *
     * @param judgments        판정 수 (제안·검토자 쌍)
     * @param proposals        판정한 제안 수
     * @param accuracy         맞음 / (맞음 + 일부 + 틀림) — 실제 의미를 확인한 대상만
     * @param partial          일부 맞음 / 같은 분모
     * @param notEvaluable     평가할 수 없음 판정 수 (정확성 분모에서 뺀다)
     * @param unfounded        근거 없는 주장이 있다고 판정 / 판정 수
     * @param fabricated       존재하지 않는·범위 밖·불일치 객체를 든 제안 / 판정한 제안 수 (서버 근거 검증)
     * @param termFit          적절 / (적절 + 부적절) — 추천 없음·평가 불가 제외
     * @param uncertainty      적절 / (판정 수 - 해당 없음)
     * @param missedUnknown    알 수 없는 것을 단정 / 같은 분모
     * @param excessiveUnknown 알 수 있는 것을 알 수 없다고 함 / 같은 분모
     * @param editRate         수정 후 승인 / 승인 (판정한 제안)
     * @param approvalRate     승인 / (승인 + 반려) (판정한 제안)
     * @param timeSaving       1 - (검토 시간 중앙값 / 직접 작성 시간 중앙값), 둘 다 적은 판정만
     * @param sameMetadata     등록 시점 메타데이터와 같은 버전으로 만든 제안의 판정 수 (나머지는 다른 시점 — 모델 비교에서 따로 본다)
     */
    public record ModelResult(String model, String promptVersion, int judgments, int proposals, int reviewers, Rate accuracy, Rate partial, int notEvaluable,
                              Rate unfounded, Rate fabricated, Rate termFit, Rate domainFit, Rate uncertainty, Rate missedUnknown, Rate excessiveUnknown,
                              Rate editRate, Rate approvalRate, Double medianReviewMinutes, Double medianWritingMinutes, int timeBase, Double timeSaving,
                              int sameMetadata) {
    }

    /** 기준 데이터 현황 : 대상 수, 실제 의미 확인 / 확인 불가 / 미확인, 범주별 대상 수 */
    public record Dataset(int targets, int confirmed, int unconfirmable, int pending, java.util.Map<String, Integer> categories) {
    }

    public record Metrics(Dataset dataset, List<ModelResult> models, boolean evaluated, String note) {
    }
}
