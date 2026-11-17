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
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 설명 평가 대상 (AI-5 품질 평가, V2 TB_AI_EXPLN_EVL_TRGT) : 품질 평가의 기준 데이터 하나. 평가할 테이블·컬럼과 범주, 업무 담당자·검토자가 확인한 실제 업무 의미.
 * <ul>
 *   <li>실제 의미({@link #actualMeaning})는 사람이 적는다. AI 에 보내지 않고, AI 가 만들거나 채우지 않는다</li>
 *   <li>의미를 확인할 수 없으면 {@link MeaningStatus#UNCONFIRMABLE} — 정확성 평가에서 빼고 따로 센다</li>
 *   <li>등록 시점의 메타데이터 버전({@link #baseVersion})을 둬, 모델을 비교할 때 같은 메타데이터로 만든 제안인지 확인한다</li>
 * </ul>
 */
@Entity
@Table(name = "TB_AI_EXPLN_EVL_TRGT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiDescriptionEvaluationTarget extends BaseEntity {

    /** 실제 의미 확인 상태 : 미확인 / 확인 / 확인 불가 */
    public enum MeaningStatus { PENDING, CONFIRMED, UNCONFIRMABLE }

    /**
     * 평가 범주 (여러 개) : 의미가 명확함 · 이름만으로 판단 어려움 · 상태 코드 · 금액·통화 · 날짜·시간 · 표준 용어·도메인 연결 · FK/LOGICAL 관계 ·
     * 원본 COMMENT 없음 · 원본 COMMENT 와 확정 업무 설명이 다름
     */
    public enum Category { CLEAR, NAME_ONLY, STATUS_CODE, AMOUNT, DATE_TIME, STANDARD_LINKED, RELATION, NO_SOURCE_COMMENT, COMMENT_DIFFERS }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_EXPLN_EVL_TRGT")
    @SequenceGenerator(name = "SQ_AI_EXPLN_EVL_TRGT", sequenceName = "SQ_AI_EXPLN_EVL_TRGT", allocationSize = 50)
    @Column(name = "AI_EXPLN_EVL_TRGT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TRGT_TYPE_CD", length = 20, nullable = false)
    private AiDescriptionProposal.TargetType targetType;

    @Column(name = "TBL_SN", nullable = false)
    private Long tableId;

    @Column(name = "COL_SN")
    private Long columnId;

    /** 등록 시점의 대상 이름 (컬럼은 TABLE.COLUMN) */
    @Column(name = "TRGT_NM", nullable = false)
    private String targetName;

    /** 범주 JSON 배열 */
    @Column(name = "EVL_CTGRY_CN", columnDefinition = "TEXT")
    private String categories;

    @Column(name = "ACTL_MNNG_CN", columnDefinition = "TEXT")
    private String actualMeaning;

    @Enumerated(EnumType.STRING)
    @Column(name = "MNNG_IDNTY_STTS_CD", length = 20, nullable = false)
    private MeaningStatus meaningStatus;

    /** 등록 시점 대상의 메타데이터 버전 JSON (수정 일시 + 모델 변경 이력 마지막 ID) */
    @Column(name = "CRTR_VER_CN", columnDefinition = "TEXT", nullable = false)
    private String baseVersion;

    /** 실제 의미를 마지막으로 적은 사람 */
    @Column(name = "RVWR_ID")
    private String reviewerId;

    @Column(name = "RVW_DT")
    private LocalDateTime reviewedAt;

    @Version
    @Column(name = "VER_NO", nullable = false)
    private Long version;

    public Audit audit() {
        return toAudit();
    }
}
