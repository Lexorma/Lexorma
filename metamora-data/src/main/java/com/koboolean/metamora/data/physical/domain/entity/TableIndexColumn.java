package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "table_index_column")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableIndexColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "table_index_column_seq")
    @SequenceGenerator(name = "table_index_column_seq", sequenceName = "table_index_column_seq", allocationSize = 50)
    @Column(name = "table_index_column_id", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_index_id", nullable = false)
    private TableIndex tableIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "column_info_id", nullable = false)
    private ColumnInfo column;

    @Column(nullable = false)
    private Integer columnOrder;
}
