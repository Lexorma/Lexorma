package com.koboolean.metamora.data.commoncolumn.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
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
 * 프로젝트 공통 컬럼 세트의 한 항목 (컬럼 일괄 편집 > 공통 컬럼 추가)
 * 컬럼명/설명/데이터 타입은 표준용어에서 가져오고, 여기에는 순서와 NULL 허용/기본값/인덱스만 둔다.
 * 표준용어가 지워질 수 있으므로 용어는 ID 로만 참조한다. (조회 시 없는 용어는 "용어 없음"으로 표시)
 */
@Entity
@Table(name = "TB_CMN_COL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommonColumn extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_CMN_COL")
    @SequenceGenerator(name = "SQ_CMN_COL", sequenceName = "SQ_CMN_COL", allocationSize = 50)
    @Column(name = "CMN_COL_SN")
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "STD_TRM_SN", nullable = false)
    private Long termId;

    @Column(name = "SORT_ORD")
    private Integer sortOrder;

    @Column(name = "NULL_ALWNC_YN")
    private Boolean isNullable;

    @Column(name = "IDX_YN")
    private Boolean isIndex;

    @Column(name = "BSC_VL")
    private String defaultValue;
}
