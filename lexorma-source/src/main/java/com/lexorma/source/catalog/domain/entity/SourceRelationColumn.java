package com.lexorma.source.catalog.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FK 의 컬럼 쌍 (복합 FK 는 columnOrder 순서) */
@Entity
@Table(name = "TB_SRC_REL_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceRelationColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_REL_COL")
    @SequenceGenerator(name = "SQ_SRC_REL_COL", sequenceName = "SQ_SRC_REL_COL", allocationSize = 50)
    @Column(name = "SRC_REL_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_REL_SN", nullable = false)
    private SourceRelation relation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_COL_SN", nullable = false)
    private SourceColumn sourceColumn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RFRNC_SRC_COL_SN")
    private SourceColumn referencedColumn;

    @Column(name = "RFRNC_COL_NM", nullable = false)
    private String referencedColumnName;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;
}
