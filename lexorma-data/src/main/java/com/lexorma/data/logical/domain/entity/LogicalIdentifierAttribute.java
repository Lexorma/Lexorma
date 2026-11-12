package com.lexorma.data.logical.domain.entity;

import jakarta.persistence.*;
import lombok.*;

/** 식별자를 이루는 속성 (순서대로). 같은 엔터티의 속성만 올 수 있다 (서비스에서 검증) */
@Entity
@Table(name = "TB_LGCL_IDNTFR_ATRB")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalIdentifierAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_IDNTFR_ATRB")
    @SequenceGenerator(name = "SQ_LGCL_IDNTFR_ATRB", sequenceName = "SQ_LGCL_IDNTFR_ATRB", allocationSize = 50)
    @Column(name = "LGCL_IDNTFR_ATRB_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_IDNTFR_SN", nullable = false)
    private LogicalIdentifier identifier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ATRB_SN", nullable = false)
    private LogicalAttribute attribute;

    @Column(name = "SORT_ORD", nullable = false)
    private Integer sortOrder;
}
