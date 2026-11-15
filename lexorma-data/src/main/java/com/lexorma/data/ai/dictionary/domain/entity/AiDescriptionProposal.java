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
 * AI 설명 제안 (AI-5, V10 TB_AI_EXPLN_SGSTN) : 물리 테이블·컬럼(내부 ID) 하나에 대해 AI 가 만든 업무 설명 초안.
 * <ul>
 *   <li>승인 전에는 업무 설명(TB_TBL·TB_COL.TASK_EXPLN)을 바꾸지 않는다. 원본 COMMENT·DDL COMMENT(설명)는 어느 단계에서도 바꾸지 않는다</li>
 *   <li>출처는 늘 AI, 확실성({@link Certainty})과 검토 상태({@link ReviewStatus})는 따로 둔다. 승인 뒤 근거가 바뀐 것은 {@link #recheckNeeded} 로 따로 표시한다</li>
 *   <li>대상이 지워져도 남는다 (모델 테이블에 FK 없음). 같은 대상에 새로 만들면 차수({@link #cycle})가 늘고 앞 제안·이력은 그대로다</li>
 *   <li>상태 변경은 낙관적 잠금({@link #version}, VER_NO)으로 — 화면이 본 버전과 다르면 충돌이다</li>
 * </ul>
 */
@Entity
@Table(name = "TB_AI_EXPLN_SGSTN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiDescriptionProposal extends BaseEntity {

    public enum TargetType { TABLE, COLUMN }

    /** 확실성 : 근거로 확인 / 추론 / 알 수 없음. 서버가 근거 검증 결과로 낮출 수 있다 (AI 의 주장보다 높이지 않는다) */
    public enum Certainty { VERIFIED, INFERRED, UNKNOWN }

    /**
     * 검토 상태. PROPOSED → (REVIEWED) → APPROVED · REJECTED · ON_HOLD, 근거가 바뀌면 REVIEW_REQUIRED.
     * SUPERSEDED : 같은 대상을 다시 만들어 새 제안으로 대체됨 (반려와 구분해 반려율을 왜곡하지 않는다). APPROVED·REJECTED·SUPERSEDED 는 끝 상태다
     */
    public enum ReviewStatus {
        PROPOSED, REVIEWED, APPROVED, REJECTED, ON_HOLD, REVIEW_REQUIRED, SUPERSEDED;

        public boolean open() {
            return this == PROPOSED || this == REVIEWED || this == ON_HOLD || this == REVIEW_REQUIRED;
        }
    }

    /** 메타데이터 조회 방식 (AI-4) : 서버가 정한 조회. 모델이 Tool 을 고르지 않는다 */
    public enum CreationMethod { TOOL_CALLING, SERVER_CONTEXT }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_AI_EXPLN_SGSTN")
    @SequenceGenerator(name = "SQ_AI_EXPLN_SGSTN", sequenceName = "SQ_AI_EXPLN_SGSTN", allocationSize = 50)
    @Column(name = "AI_EXPLN_SGSTN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TRGT_TYPE_CD", length = 20, nullable = false)
    private TargetType targetType;

    @Column(name = "TBL_SN", nullable = false)
    private Long tableId;

    /** 컬럼 대상일 때만 */
    @Column(name = "COL_SN")
    private Long columnId;

    /** 생성 시점의 대상 이름 (컬럼은 TABLE.COLUMN) — 대상이 지워져도 알아볼 수 있게 */
    @Column(name = "TRGT_NM", nullable = false)
    private String targetName;

    @Column(name = "SGSTN_CYCL", nullable = false)
    private Integer cycle;

    @Column(name = "SGSTN_EXPLN", columnDefinition = "TEXT", nullable = false)
    private String proposedDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "CRTNTY_CD", length = 20, nullable = false)
    private Certainty certainty;

    @Enumerated(EnumType.STRING)
    @Column(name = "RVW_STTS_CD", length = 20, nullable = false)
    private ReviewStatus status;

    /** 상세 JSON : 이름 해석·표준 용어/도메인 추천(검증 결과 포함)·연관 객체·불확실한 부분·확인 질문·AI 가 주장한 확실성 */
    @Column(name = "SGSTN_DTL_CN", columnDefinition = "TEXT")
    private String detail;

    /** 근거 JSON : 근거별 종류·ID·이름·검증 상태·출처 */
    @Column(name = "EVDNC_CN", columnDefinition = "TEXT")
    private String evidence;

    /** 기준 버전 JSON : 생성 시점의 대상·근거 버전, 그때의 업무 설명, 원본 카탈로그 값 (승인할 때 비교) */
    @Column(name = "CRTR_VER_CN", columnDefinition = "TEXT", nullable = false)
    private String baseVersions;

    @Column(name = "AI_PRVDR_SN")
    private Long providerId;

    @Column(name = "MDL_NM")
    private String model;

    @Column(name = "PRMPT_VER_NM", length = 30, nullable = false)
    private String promptVersion;

    @Column(name = "PRMPT_HASH_VL", nullable = false)
    private String promptHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "CRT_MTHD_CD", length = 20, nullable = false)
    private CreationMethod creationMethod;

    /** 비동기 생성 실행 (단건 생성은 null) */
    @Column(name = "AI_EXPLN_CRT_EXCN_SN")
    private Long jobId;

    @Column(name = "RVWR_ID")
    private String reviewerId;

    @Column(name = "RVW_DT")
    private LocalDateTime reviewedAt;

    /** 승인으로 업무 설명에 반영한 문구 (수정 후 승인이면 사용자가 고친 문구) */
    @Column(name = "CFMTN_EXPLN", columnDefinition = "TEXT")
    private String confirmedDescription;

    @Column(name = "RVW_RSN_CN", length = 1000)
    private String reviewReason;

    /** 승인 뒤 근거·원본 COMMENT 가 바뀌어 업무 설명을 다시 볼 필요가 있다 (상태는 그대로, 업무 설명도 그대로) */
    @Builder.Default
    @Column(name = "RRVW_NEED_YN", nullable = false)
    private Boolean recheckNeeded = false;

    @Version
    @Column(name = "VER_NO", nullable = false)
    private Long version;

    public Audit audit() {
        return toAudit();
    }
}
