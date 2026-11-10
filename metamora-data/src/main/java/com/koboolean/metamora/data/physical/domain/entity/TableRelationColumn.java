package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

/** 관계의 컬럼 쌍 (복합 FK 는 columnOrder 순서대로 원본 컬럼 → 대상 컬럼) */
@Entity
@Table(name = "table_relation_column")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableRelationColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "table_relation_column_seq")
    @SequenceGenerator(name = "table_relation_column_seq", sequenceName = "table_relation_column_seq", allocationSize = 50)
    @Column(name = "table_relation_column_id", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_relation_id", nullable = false)
    private TableRelation relation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_column_id", nullable = false)
    private ColumnInfo sourceColumn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_column_id", nullable = false)
    private ColumnInfo targetColumn;

    @Column(nullable = false)
    private Integer columnOrder;
}
