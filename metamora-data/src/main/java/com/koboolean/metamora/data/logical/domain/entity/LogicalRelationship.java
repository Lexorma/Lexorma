package com.koboolean.metamora.data.logical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 관계 (원본 엔터티 → 대상 엔터티, 같은 논리 모델 안에서만. 자기 참조 가능).
 * 카디널리티는 양쪽 참여 수로 저장한다 : 원본 쪽 = 대상 1건에 연결되는 원본 건수, 대상 쪽 = 원본 1건에 연결되는 대상 건수.
 * 최소 0 = 선택, 1 이상 = 필수. 최대 null = 다(N).
 * 예) 부서(원본) 1 : N 사원(대상), 사원은 부서가 필수 → 원본 1..1, 대상 0..N
 */
@Entity
@Table(name = "TB_LGCL_REL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalRelationship extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_REL")
    @SequenceGenerator(name = "SQ_LGCL_REL", sequenceName = "SQ_LGCL_REL", allocationSize = 50)
    @Column(name = "LGCL_REL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_MDL_SN", nullable = false)
    private LogicalModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ORGNL_ENTT_SN", nullable = false)
    private LogicalEntity sourceEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TRGT_ENTT_SN", nullable = false)
    private LogicalEntity targetEntity;

    @Column(name = "REL_NM")
    private String relationshipName;

    @Column(name = "REL_EXPLN", length = 1000)
    private String relationshipDescription;

    /** 식별 관계 : 대상 엔터티의 식별자가 원본 엔터티의 식별자에 의존한다 */
    @Column(name = "IDNTF_YN", nullable = false)
    private Boolean isIdentifying;

    @Column(name = "ORGNL_MIN_CNT", nullable = false)
    private Integer sourceMin;

    @Column(name = "ORGNL_MAX_CNT")
    private Integer sourceMax;

    @Column(name = "TRGT_MIN_CNT", nullable = false)
    private Integer targetMin;

    @Column(name = "TRGT_MAX_CNT")
    private Integer targetMax;

    @Column(name = "APRV_YN")
    private Boolean isApproval;
}
