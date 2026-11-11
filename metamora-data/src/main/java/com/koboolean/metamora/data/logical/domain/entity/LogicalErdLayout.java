package com.koboolean.metamora.data.logical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 ERD 레이아웃 (사용자 × 논리 모델 1건). 물리 ERD 레이아웃(ErdLayout)과 따로 저장한다 — ID 가 가리키는 대상이 다르다.
 * nodePositions : {"논리 엔터티 ID": {"x", "y"}} JSON. 저장해도 논리 모델은 바뀌지 않는다.
 */
@Entity
@Table(name = "TB_LGCL_ERD_LYT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalErdLayout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_ERD_LYT")
    @SequenceGenerator(name = "SQ_LGCL_ERD_LYT", sequenceName = "SQ_LGCL_ERD_LYT", allocationSize = 50)
    @Column(name = "LGCL_ERD_LYT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "LGCL_MDL_SN", nullable = false)
    private Long modelId;

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
