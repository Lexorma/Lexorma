package com.lexorma.data.logical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 엔터티의 주제 영역 소속 (M-6 후속 4차) : 엔터티당 0~1건 (UX_LGCL_SBJ_AREA_ENTT_01), 없으면 미분류.
 * 엔터티 행(TB_LGCL_ENTT)과 따로 저장하므로 분류를 바꿔도 엔터티의 수정 일시(DEC-08 기준값)·승인 상태는 그대로다.
 * 변경 이력은 엔터티가 아니라 이 행의 이력(SUBJECT_AREA_ENTITY)으로 남는다 : 지정 = CREATE, 다른 영역으로 이동 = UPDATE, 해제 = DELETE.
 */
@Entity
@Table(name = "TB_LGCL_SBJ_AREA_ENTT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalSubjectAreaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_SBJ_AREA_ENTT")
    @SequenceGenerator(name = "SQ_LGCL_SBJ_AREA_ENTT", sequenceName = "SQ_LGCL_SBJ_AREA_ENTT", allocationSize = 50)
    @Column(name = "LGCL_SBJ_AREA_ENTT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 영역·엔터티의 논리 모델 (조회 조건용 — 둘의 모델과 같다) */
    @Column(name = "LGCL_MDL_SN", nullable = false)
    private Long modelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_SBJ_AREA_SN", nullable = false)
    private LogicalSubjectArea subjectArea;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ENTT_SN", nullable = false)
    private LogicalEntity entity;
}
