package com.lexorma.source.catalog.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "TB_SRC_IDX_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceIndexColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_IDX_COL")
    @SequenceGenerator(name = "SQ_SRC_IDX_COL", sequenceName = "SQ_SRC_IDX_COL", allocationSize = 50)
    @Column(name = "SRC_IDX_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_IDX_SN", nullable = false)
    private SourceIndex sourceIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_COL_SN", nullable = false)
    private SourceColumn sourceColumn;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;
}
