package com.koboolean.metamora.data.logical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/** 논리 식별자 : 엔터티의 속성 1개 이상(복합 가능)으로 이뤄진다. 주 식별자는 엔터티당 최대 1개, 나머지는 대체 식별자 */
@Entity
@Table(name = "TB_LGCL_IDNTFR")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalIdentifier extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_IDNTFR")
    @SequenceGenerator(name = "SQ_LGCL_IDNTFR", sequenceName = "SQ_LGCL_IDNTFR", allocationSize = 50)
    @Column(name = "LGCL_IDNTFR_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ENTT_SN", nullable = false)
    private LogicalEntity entity;

    @Column(name = "IDNTFR_NM", nullable = false)
    private String identifierName;

    /** 주 식별자 여부 */
    @Column(name = "BSC_YN", nullable = false)
    private Boolean isPrimary;

    @Builder.Default
    @OneToMany(mappedBy = "identifier", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<LogicalIdentifierAttribute> attributes = new ArrayList<>();
}
