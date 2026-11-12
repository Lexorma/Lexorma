package com.lexorma.data.mapping.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 엔터티 ↔ 물리 테이블 매핑 (M-7). 엔터티 1 : 테이블 1 (양쪽 UNIQUE).
 * 연결만 저장한다 — 매핑을 등록·삭제해도 엔터티·테이블은 바뀌지 않는다.
 */
@Entity
@Table(name = "TB_ENTT_TBL_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EntityTableMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_ENTT_TBL_MPNG")
    @SequenceGenerator(name = "SQ_ENTT_TBL_MPNG", sequenceName = "SQ_ENTT_TBL_MPNG", allocationSize = 50)
    @Column(name = "ENTT_TBL_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ENTT_SN", nullable = false)
    private LogicalEntity entity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TBL_SN", nullable = false)
    private TableInfo table;
}
