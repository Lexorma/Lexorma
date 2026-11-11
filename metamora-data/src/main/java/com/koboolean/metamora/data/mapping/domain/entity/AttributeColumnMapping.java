package com.koboolean.metamora.data.mapping.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.logical.domain.entity.LogicalAttribute;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 속성 ↔ 물리 컬럼 매핑 (M-7). 속성 1 : 컬럼 1 (양쪽 UNIQUE).
 * 부모(엔터티 ↔ 테이블 매핑)의 엔터티에 속한 속성과 테이블에 속한 컬럼만 연결한다 (서비스 검사).
 */
@Entity
@Table(name = "TB_ATRB_COL_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttributeColumnMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_ATRB_COL_MPNG")
    @SequenceGenerator(name = "SQ_ATRB_COL_MPNG", sequenceName = "SQ_ATRB_COL_MPNG", allocationSize = 50)
    @Column(name = "ATRB_COL_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ENTT_TBL_MPNG_SN", nullable = false)
    private EntityTableMapping mapping;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ATRB_SN", nullable = false)
    private LogicalAttribute attribute;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "COL_SN", nullable = false)
    private ColumnInfo column;
}
