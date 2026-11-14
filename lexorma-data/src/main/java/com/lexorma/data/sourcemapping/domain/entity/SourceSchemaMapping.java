package com.lexorma.data.sourcemapping.domain.entity;

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
 * 원본 스키마 ↔ 모델 스키마 (M-4c, 연결 안에서 1:1). 원본 쪽은 원본 스키마 행(TB_SRC_SCHM)의 일련번호와 확정할 때의 비교 경로·이름·생성 차수·반영 순서를 값으로 둔다.
 * 모델 스키마를 지우면 schemaId 가 비어(ON DELETE SET NULL) "모델 없음" 으로 남는다.
 */
@Entity
@Table(name = "TB_SRC_SCHM_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceSchemaMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_SCHM_MPNG")
    @SequenceGenerator(name = "SQ_SRC_SCHM_MPNG", sequenceName = "SQ_SRC_SCHM_MPNG", allocationSize = 50)
    @Column(name = "SRC_SCHM_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_SCHM_SN", nullable = false)
    private Long sourceSchemaId;

    @Column(name = "PATH_CN", length = 1000, nullable = false)
    private String pathKey;

    /** 확정할 때의 원본 스키마 이름 (원본이 준 그대로) */
    @Column(name = "SCHM_NM", nullable = false)
    private String sourceSchemaName;

    @Column(name = "CRT_CYCL", nullable = false)
    private Integer creationCycle;

    /** 확정에 쓴 수집의 반영 순서 */
    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    /** 모델 스키마 (지워졌으면 null) */
    @Column(name = "SCHM_SN")
    private Long schemaId;
}
