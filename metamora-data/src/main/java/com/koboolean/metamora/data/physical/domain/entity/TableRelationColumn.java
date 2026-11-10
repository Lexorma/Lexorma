package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

/** 관계의 컬럼 쌍 (복합 FK 는 columnOrder 순서대로 원본 컬럼 → 대상 컬럼) */
@Entity
@Table(name = "TB_TBL_REL_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableRelationColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_REL_COL")
    @SequenceGenerator(name = "SQ_TBL_REL_COL", sequenceName = "SQ_TBL_REL_COL", allocationSize = 50)
    @Column(name = "REL_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "REL_SN", nullable = false)
    private TableRelation relation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ORGNL_COL_SN", nullable = false)
    private ColumnInfo sourceColumn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TRGT_COL_SN", nullable = false)
    private ColumnInfo targetColumn;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;
}
