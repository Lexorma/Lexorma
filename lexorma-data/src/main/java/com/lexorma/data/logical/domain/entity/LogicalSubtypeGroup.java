package com.lexorma.data.logical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 서브타입 그룹 (M-6 후속 5차, docs/completed/logical-subtype.md) : 슈퍼타입 엔터티를 한 기준으로 나눈 서브타입 묶음.
 * 슈퍼타입은 그룹을 여러 개 가질 수 있고, 그룹은 서브타입({@link LogicalSubtype}) 2개 이상으로 저장된다 (서비스가 검사).
 * 중복 허용·완전 여부는 필수값이고 기본값이 없다. 분류 속성은 슈퍼타입의 유효 속성(직접 또는 조상에서 상속)의 원본 속성이다.
 * 그룹에는 승인 상태가 없다 : 그룹 변경은 슈퍼타입·서브타입의 정의 변경으로 보고 엔터티 승인으로 막는다. 수정 일시가 DEC-08 기준값이다 (구성원만 바뀌어도 markModified)
 */
@Entity
@Table(name = "TB_LGCL_SBTYP_GROUP")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalSubtypeGroup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_SBTYP_GROUP")
    @SequenceGenerator(name = "SQ_LGCL_SBTYP_GROUP", sequenceName = "SQ_LGCL_SBTYP_GROUP", allocationSize = 50)
    @Column(name = "LGCL_SBTYP_GROUP_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_MDL_SN", nullable = false)
    private LogicalModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SPRTYP_ENTT_SN", nullable = false)
    private LogicalEntity supertypeEntity;

    @Column(name = "SBTYP_GROUP_NM", nullable = false)
    private String groupName;

    @Column(name = "SBTYP_GROUP_EXPLN", length = 1000)
    private String groupDescription;

    /** 참 = Overlapping (같은 인스턴스가 여러 서브타입에), 거짓 = Disjoint */
    @Column(name = "DPCN_PSBLTY_YN", nullable = false)
    private Boolean overlapping;

    /** 참 = Total (모든 슈퍼타입 인스턴스가 서브타입 하나 이상에), 거짓 = Partial */
    @Column(name = "TOTL_YN", nullable = false)
    private Boolean total;

    /** 분류 속성 (선택) : 슈퍼타입 또는 그 조상 엔터티의 원본 속성 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CLSF_ATRB_SN")
    private LogicalAttribute classificationAttribute;

    /** 같은 슈퍼타입 안의 표시 순서 */
    @Column(name = "SORT_ORD", nullable = false)
    private Integer sortOrder;
}
