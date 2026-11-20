package com.lexorma.data.logical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 서브타입 그룹의 구성원 (M-6 후속 5차) : 엔터티 하나는 이 행 하나에만 있다 (UX_LGCL_SBTYP_01 — 단일 상속, 그룹 하나).
 * 변경 이력은 그룹 이력의 subtypes 배열로 남는다 (구성원 추가·제거·분류 값·순서). 서브타입 엔터티 행은 바뀌지 않는다
 */
@Entity
@Table(name = "TB_LGCL_SBTYP")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalSubtype extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_SBTYP")
    @SequenceGenerator(name = "SQ_LGCL_SBTYP", sequenceName = "SQ_LGCL_SBTYP", allocationSize = 50)
    @Column(name = "LGCL_SBTYP_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 그룹·엔터티의 논리 모델 (조회 조건용 — 둘의 모델과 같다) */
    @Column(name = "LGCL_MDL_SN", nullable = false)
    private Long modelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_SBTYP_GROUP_SN", nullable = false)
    private LogicalSubtypeGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SBTYP_ENTT_SN", nullable = false)
    private LogicalEntity subtypeEntity;

    /** 분류 값 (선택, 메타데이터 — 실행하지 않는다) */
    @Column(name = "CLSF_VL")
    private String classificationValue;

    @Column(name = "SORT_ORD", nullable = false)
    private Integer sortOrder;
}
