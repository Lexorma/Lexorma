package com.lexorma.data.logical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 주제 영역 (M-6 후속 4차, docs/completed/logical-subject-area.md) : 논리 모델 안의 업무 분류.
 * 상위 영역(parent)으로 계층을 이룬다 : 최상위 = 1단계, 최대 3단계 (서비스가 검사 — 깊이·순환·같은 모델·같은 상위 아래 이름 중복).
 * 엔터티는 소속 행(LogicalSubjectAreaEntity)으로 영역 하나에만 속한다. 영역은 엔터티의 정의·승인 상태를 바꾸지 않는다.
 */
@Entity
@Table(name = "TB_LGCL_SBJ_AREA")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalSubjectArea extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_SBJ_AREA")
    @SequenceGenerator(name = "SQ_LGCL_SBJ_AREA", sequenceName = "SQ_LGCL_SBJ_AREA", allocationSize = 50)
    @Column(name = "LGCL_SBJ_AREA_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_MDL_SN", nullable = false)
    private LogicalModel model;

    /** 상위 영역 (null = 최상위). 같은 논리 모델의 영역이어야 한다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "UP_SBJ_AREA_SN")
    private LogicalSubjectArea parent;

    @Column(name = "SBJ_AREA_NM", nullable = false)
    private String areaName;

    @Column(name = "SBJ_AREA_EXPLN", length = 1000)
    private String areaDescription;

    /** 같은 상위 영역 안의 표시 순서 */
    @Column(name = "SORT_ORD", nullable = false)
    private Integer sortOrder;
}
