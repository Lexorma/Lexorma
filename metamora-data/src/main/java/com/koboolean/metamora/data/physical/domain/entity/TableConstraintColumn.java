package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "table_constraint_column")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableConstraintColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "table_constraint_column_seq")
    @SequenceGenerator(name = "table_constraint_column_seq", sequenceName = "table_constraint_column_seq", allocationSize = 50)
    @Column(name = "table_constraint_column_id", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_constraint_id", nullable = false)
    private TableConstraint tableConstraint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "column_info_id", nullable = false)
    private ColumnInfo column;

    @Column(nullable = false)
    private Integer columnOrder;
}
