package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "TB_TBL_CNSTRT_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableConstraintColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_CNSTRT_COL")
    @SequenceGenerator(name = "SQ_TBL_CNSTRT_COL", sequenceName = "SQ_TBL_CNSTRT_COL", allocationSize = 50)
    @Column(name = "CNSTRT_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CNSTRT_SN", nullable = false)
    private TableConstraint tableConstraint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "COL_SN", nullable = false)
    private ColumnInfo column;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;
}
