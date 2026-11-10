package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "TB_TBL_IDX_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableIndexColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_IDX_COL")
    @SequenceGenerator(name = "SQ_TBL_IDX_COL", sequenceName = "SQ_TBL_IDX_COL", allocationSize = 50)
    @Column(name = "IDX_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "IDX_SN", nullable = false)
    private TableIndex tableIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "COL_SN", nullable = false)
    private ColumnInfo column;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;
}
