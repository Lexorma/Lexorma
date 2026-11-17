package com.lexorma.ai.dictionary.domain.entity;

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
 * AI 설명 평가 판정의 변경 이력 (DEC-27, V2 TB_AI_EXPLN_EVL_HSTRY). 판정을 저장·수정할 때마다 추가만 한다 — 고치거나 지우지 않는다 (프로젝트 삭제 때만).
 * 지표는 판정 테이블(최종 유효 판정)만 센다 — 이 이력은 집계하지 않는다.
 */
@Entity
@Table(name = "TB_AI_EXPLN_EVL_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AiDescriptionEvaluationHistory extends BaseEntity {

    /** INSERT 처음 판정 · UPDATE 판정 수정 (모델 변경 이력과 같은 값) */
    public enum ChangeType { INSERT, UPDATE }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_EXPLN_EVL_HSTRY")
    @SequenceGenerator(name = "SQ_AI_EXPLN_EVL_HSTRY", sequenceName = "SQ_AI_EXPLN_EVL_HSTRY", allocationSize = 50)
    @Column(name = "AI_EXPLN_EVL_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "AI_EXPLN_EVL_SN", nullable = false)
    private Long judgmentId;

    @Column(name = "AI_EXPLN_EVL_TRGT_SN", nullable = false)
    private Long targetId;

    @Column(name = "AI_EXPLN_SGSTN_SN", nullable = false)
    private Long proposalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_TYPE_CD", length = 20, nullable = false)
    private ChangeType changeType;

    /** 판정자 */
    @Column(name = "RVWR_ID", nullable = false)
    private String reviewerId;

    /** 고치기 전 판정 (JSON, 처음 판정이면 null) */
    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT")
    private String before;

    /** 저장한 판정 (JSON) */
    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT", nullable = false)
    private String after;

    /** 판정을 고친 사유 (수정에 필수) */
    @Column(name = "PRCS_RSN_CN", length = 1000)
    private String reason;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime changedAt;
}
