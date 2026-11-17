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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 설명 제안의 생성·검토 이력 (AI-5, V10 TB_AI_EXPLN_SGSTN_HSTRY). 추가만 하고 고치거나 지우지 않는다 (프로젝트 삭제 때만 함께 지운다).
 * 품질 평가(승인·수정·반려 비율)는 이 이력과 제안에서 계산한다.
 */
@Entity
@Table(name = "TB_AI_EXPLN_SGSTN_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AiDescriptionProposalHistory extends BaseEntity {

    /**
     * GENERATE 생성 · REVIEW 검토함 표시 · APPROVE 승인 · APPROVE_EDITED 수정 후 승인 · REJECT 반려 · HOLD 보류 ·
     * REVIEW_REQUIRED 근거 변경으로 재검토 필요 · RECHECK_FLAGGED 승인 뒤 근거 변경 표시 · SUPERSEDE 새 제안으로 대체
     */
    /** REVALIDATE : 재검토 필요 제안의 근거를 다시 확인해 검토할 수 있게 함 (변경을 확인했으면 기준 버전을 지금으로 옮긴다) · CLOSE : 적용하지 않고 종결 */
    public enum Action { GENERATE, REVIEW, APPROVE, APPROVE_EDITED, REJECT, HOLD, REVIEW_REQUIRED, RECHECK_FLAGGED, SUPERSEDE, REVALIDATE, CLOSE, CLOSE_UNCONFIRMABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_EXPLN_SGSTN_HSTRY")
    @SequenceGenerator(name = "SQ_AI_EXPLN_SGSTN_HSTRY", sequenceName = "SQ_AI_EXPLN_SGSTN_HSTRY", allocationSize = 50)
    @Column(name = "AI_EXPLN_SGSTN_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "AI_EXPLN_SGSTN_SN", nullable = false)
    private Long proposalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "PRCS_TYPE_CD", length = 20, nullable = false)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "BFR_STTS_CD", length = 20)
    private AiDescriptionProposal.ReviewStatus before;

    @Enumerated(EnumType.STRING)
    @Column(name = "AFTR_STTS_CD", length = 20, nullable = false)
    private AiDescriptionProposal.ReviewStatus after;

    /** 처리한 사용자 아이디 (서버 처리는 SYSTEM) */
    @Column(name = "PRCR_ID")
    private String processorId;

    @Column(name = "PRCS_DT", nullable = false)
    private LocalDateTime processedAt;

    @Column(name = "PRCS_RSN_CN", length = 1000)
    private String reason;

    /** 승인 때 반영한 문구 */
    @Column(name = "CFMTN_EXPLN", columnDefinition = "TEXT")
    private String confirmedDescription;
}
