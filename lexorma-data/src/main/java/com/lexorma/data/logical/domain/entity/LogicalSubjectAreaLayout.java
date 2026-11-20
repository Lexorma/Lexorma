package com.lexorma.data.logical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 주제 영역별 논리 ERD 레이아웃 (M-6 후속 4차, D-07) : 사용자 × 주제 영역 1건. 모델 전체 배치(LogicalErdLayout)와 따로 저장하고 버전(충돌 검사)도 따로다.
 * nodePositions : {"논리 엔터티 ID": {"x", "y"}} JSON. 저장해도 논리 모델은 바뀌지 않는다. 영역을 지우면 함께 지운다.
 */
@Entity
@Table(name = "TB_LGCL_SBJ_AREA_LYT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalSubjectAreaLayout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_SBJ_AREA_LYT")
    @SequenceGenerator(name = "SQ_LGCL_SBJ_AREA_LYT", sequenceName = "SQ_LGCL_SBJ_AREA_LYT", allocationSize = 50)
    @Column(name = "LGCL_SBJ_AREA_LYT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "LGCL_MDL_SN", nullable = false)
    private Long modelId;

    @Column(name = "LGCL_SBJ_AREA_SN", nullable = false)
    private Long subjectAreaId;

    @Column(name = "ACNT_SN", nullable = false)
    private Long accountId;

    @Column(name = "NODE_PSTN_CN", nullable = false, columnDefinition = "TEXT")
    private String nodePositions;

    @Column(name = "SCRN_X_CRDNT", nullable = false)
    private Double viewportX;

    @Column(name = "SCRN_Y_CRDNT", nullable = false)
    private Double viewportY;

    @Column(name = "SCRN_MGNF", nullable = false)
    private Double viewportZoom;

    /** 다른 탭·창의 오래된 배치로 덮어쓰지 않도록 저장 요청의 version 과 비교한다 */
    @Version
    @Column(name = "VER_NO", nullable = false)
    private Long version;
}
