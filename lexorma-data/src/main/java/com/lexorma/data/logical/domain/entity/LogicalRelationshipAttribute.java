package com.lexorma.data.logical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 관계의 속성 짝 (M-7 보강 3차, P1) : 부모(원본) 엔터티의 주 식별자 속성 → 자식(대상) 엔터티의 속성, 순서대로.
 * 관계 쪽에는 목록을 두지 않는다 (관계에서 이 행으로 가는 연관 없이 저장소로만 읽고 쓴다). 소속 엔터티·주 식별자 검사는 서비스가 한다 ({@code LogicalRelationshipPairs}).
 * 한 관계 안에서 원본 속성·대상 속성·순서는 각각 하나다 (UX_LGCL_REL_ATRB_01~03). 다른 관계는 같은 속성을 다시 쓸 수 있다
 */
@Entity
@Table(name = "TB_LGCL_REL_ATRB")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalRelationshipAttribute extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_REL_ATRB")
    @SequenceGenerator(name = "SQ_LGCL_REL_ATRB", sequenceName = "SQ_LGCL_REL_ATRB", allocationSize = 50)
    @Column(name = "LGCL_REL_ATRB_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_REL_SN", nullable = false)
    private LogicalRelationship relationship;

    /** 부모(원본) 엔터티의 속성 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ORGNL_ATRB_SN", nullable = false)
    private LogicalAttribute parentAttribute;

    /** 자식(대상) 엔터티의 속성 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TRGT_ATRB_SN", nullable = false)
    private LogicalAttribute childAttribute;

    @Column(name = "SORT_ORD", nullable = false)
    private Integer sortOrder;
}
