package com.lexorma.data.ai.dictionary.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 설명 평가 (AI-5 품질 평가, V2 TB_AI_EXPLN_EVL) : 평가 대상의 AI 설명 제안 하나를 사람(검토자)이 판정한 결과. 검토자당 하나.
 * 정확성은 평가 대상의 실제 의미(사람이 확인)와 견준 사람의 판정이다 — AI 의 확실성이나 근거 검증(VERIFIED)으로 채우지 않는다.
 * 모델·프롬프트 버전을 함께 남겨 같은 기준 데이터로 모델을 비교한다.
 */
@Entity
@Table(name = "TB_AI_EXPLN_EVL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiDescriptionEvaluation extends BaseEntity {

    /** 업무적 정확성 : 맞음 / 일부 맞음 / 틀림 / 평가할 수 없음 (실제 의미를 확인하지 못함 등) */
    public enum Judgment { CORRECT, PARTIAL, INCORRECT, NOT_EVALUABLE }

    /** 불확실성 처리 : 적절 / 알 수 없는 것을 단정 / 알 수 있는 것을 알 수 없다고 함 / 해당 없음 */
    public enum UncertaintyJudgment { APPROPRIATE, MISSED, EXCESSIVE, NOT_APPLICABLE }

    /** 표준 용어·도메인 추천 : 적절 / 부적절 / 추천 없음 / 평가할 수 없음 */
    public enum RecommendationJudgment { APPROPRIATE, INAPPROPRIATE, NO_RECOMMENDATION, NOT_EVALUABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_EXPLN_EVL")
    @SequenceGenerator(name = "SQ_AI_EXPLN_EVL", sequenceName = "SQ_AI_EXPLN_EVL", allocationSize = 50)
    @Column(name = "AI_EXPLN_EVL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "AI_EXPLN_EVL_TRGT_SN", nullable = false)
    private Long targetId;

    @Column(name = "AI_EXPLN_SGSTN_SN", nullable = false)
    private Long proposalId;

    @Column(name = "MDL_NM")
    private String model;

    @Column(name = "PRMPT_VER_NM", length = 30, nullable = false)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "JGMT_RSLT_CD", length = 20, nullable = false)
    private Judgment judgment;

    /** 메타데이터로 뒷받침되지 않는 주장이 있다 */
    @Column(name = "UNFND_CLM_YN", nullable = false)
    private Boolean unfoundedClaim;

    @Column(name = "OMSN_INFO_CN", columnDefinition = "TEXT")
    private String missingInformation;

    @Enumerated(EnumType.STRING)
    @Column(name = "UNCRT_JGMT_CD", length = 20, nullable = false)
    private UncertaintyJudgment uncertainty;

    @Enumerated(EnumType.STRING)
    @Column(name = "TRM_RCMDTN_JGMT_CD", length = 20, nullable = false)
    private RecommendationJudgment termRecommendation;

    @Enumerated(EnumType.STRING)
    @Column(name = "DMN_RCMDTN_JGMT_CD", length = 20, nullable = false)
    private RecommendationJudgment domainRecommendation;

    @Column(name = "MDFCN_NEED_CN", columnDefinition = "TEXT")
    private String requiredEdits;

    /** 검토에 든 시간 (분) */
    @Column(name = "RVW_REQ_HR")
    private Integer reviewMinutes;

    /** 사람이 처음부터 쓰는 데 든(든다고 본) 시간 (분) */
    @Column(name = "WRT_REQ_HR")
    private Integer writingMinutes;

    @Column(name = "RVWR_ID", nullable = false)
    private String reviewerId;

    @Column(name = "RVW_DT", nullable = false)
    private LocalDateTime reviewedAt;

    @Version
    @Column(name = "VER_NO", nullable = false)
    private Long version;
}
