package com.lexorma.core.ai.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 프로젝트별 AI Provider 지정 (DEC-18, V3 TB_PRJ_AI_STNG). 프로젝트마다 0~1행 (UX_PRJ_AI_STNG_01).
 * 행이 있으면 그 프로젝트의 업무 AI 기능이 이 Provider(와 모델)를 쓰고, 없으면 시스템 기본 Provider 를 쓴다.
 * 지정한 Provider 를 쓸 수 없으면 시스템 기본으로 대신하지 않는다 (임의 Fallback 금지).
 */
@Entity
@Table(name = "TB_PRJ_AI_STNG")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAiSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PRJ_AI_STNG")
    @SequenceGenerator(name = "SQ_PRJ_AI_STNG", sequenceName = "SQ_PRJ_AI_STNG", allocationSize = 50)
    @Column(name = "PRJ_AI_STNG_SN")
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** TB_AI_PRVDR.AI_PRVDR_SN (FK_PRJ_AI_STNG_02 — 지정된 Provider 는 지울 수 없다) */
    @Column(name = "AI_PRVDR_SN", nullable = false)
    private Long providerId;

    /** 쓸 모델. 비면 Provider 의 기본 모델 */
    @Column(name = "MDL_NM")
    private String model;

    public Audit audit() {
        return toAudit();
    }
}
