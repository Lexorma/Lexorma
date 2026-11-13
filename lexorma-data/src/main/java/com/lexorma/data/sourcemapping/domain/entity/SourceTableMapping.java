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
 * 원본 TABLE ↔ 모델 TABLE (M-4c, 연결 안에서 1:1). 원본 쪽은 원본 테이블 행(TB_SRC_TBL, 버전 행이 아니다)의 일련번호와 확정할 때의 비교 경로·비교 이름·생성 차수·반영 순서.
 * 같은 이름으로 다시 생긴 원본(생성 차수가 다르다)은 같은 객체로 보지 않는다 : 재확인 대상이다. 모델 테이블을 지우면 tableId 가 비어 "모델 없음" 으로 남는다.
 */
@Entity
@Table(name = "TB_SRC_TBL_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceTableMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_TBL_MPNG")
    @SequenceGenerator(name = "SQ_SRC_TBL_MPNG", sequenceName = "SQ_SRC_TBL_MPNG", allocationSize = 50)
    @Column(name = "SRC_TBL_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_SCHM_SN", nullable = false)
    private Long sourceSchemaId;

    @Column(name = "SRC_TBL_SN", nullable = false)
    private Long sourceTableId;

    @Column(name = "PATH_CN", length = 1000, nullable = false)
    private String pathKey;

    @Column(name = "CMPR_NM", nullable = false)
    private String compareName;

    @Column(name = "CRT_CYCL", nullable = false)
    private Integer creationCycle;

    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    /** 모델 테이블 (지워졌으면 null) */
    @Column(name = "TBL_SN")
    private Long tableId;
}
