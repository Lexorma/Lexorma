package com.koboolean.metamora.data.logical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 모델 : 프로젝트의 논리 엔터티·관계를 묶는 관리 단위 (프로젝트 1 : 논리 모델 N).
 * 물리 모델(테이블·컬럼)과 독립이다 — 물리 객체를 참조하지 않는다 (매핑은 M-7).
 */
@Entity
@Table(name = "TB_LGCL_MDL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalModel extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_MDL")
    @SequenceGenerator(name = "SQ_LGCL_MDL", sequenceName = "SQ_LGCL_MDL", allocationSize = 50)
    @Column(name = "LGCL_MDL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "MDL_NM", nullable = false)
    private String modelName;

    @Column(name = "MDL_EXPLN", length = 1000)
    private String modelDescription;
}
