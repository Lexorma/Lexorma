package com.koboolean.metamora.data.erd.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 물리 ERD 레이아웃 (사용자 × 프로젝트 1건). 물리 모델과 별개라 저장해도 테이블·컬럼은 바뀌지 않는다.
 * nodePositions : {"테이블 ID": {"x", "y"}} JSON
 */
@Entity
@Table(name = "TB_ERD_LYT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErdLayout extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_ERD_LYT")
    @SequenceGenerator(name = "SQ_ERD_LYT", sequenceName = "SQ_ERD_LYT", allocationSize = 50)
    @Column(name = "ERD_LYT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

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
