package com.lexorma.source.catalog.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "TB_SRC_CNSTRT_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceConstraintColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_CNSTRT_COL")
    @SequenceGenerator(name = "SQ_SRC_CNSTRT_COL", sequenceName = "SQ_SRC_CNSTRT_COL", allocationSize = 50)
    @Column(name = "SRC_CNSTRT_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_CNSTRT_SN", nullable = false)
    private SourceConstraint sourceConstraint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_COL_SN", nullable = false)
    private SourceColumn sourceColumn;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;
}
