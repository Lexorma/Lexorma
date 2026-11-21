package com.lexorma.data.mapping.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import jakarta.persistence.*;
import lombok.*;

/**
 * 서브타입 식별자 컬럼 매핑 = 상속 PK 매핑 (M-7 3차, V4, docs/ongoing/subtype-physical-transformation.md 30장).
 * 서브타입 엔터티의 엔터티 ↔ 테이블 매핑 아래에서, 루트 주 식별자 속성(원본 속성 ID) ↔ 서브타입 테이블의 상속 PK 컬럼을 연결한다.
 * <ul>
 *   <li>같은 원본 속성을 여러 서브타입 테이블의 컬럼이 각각 쓴다 — 속성 단독 UNIQUE 가 없다 ((매핑, 속성)·컬럼 UNIQUE)</li>
 *   <li>직접 속성 매핑({@link AttributeColumnMapping})과 컬럼을 함께 쓰지 않는다 (서비스가 논리 모델 행 → 테이블 행 잠금 안에서 검사)</li>
 *   <li>연결만 저장한다 — 물리 PK·FK·컬럼과 논리 식별자는 바꾸지 않고, 정합성은 저장하지 않고 조회 때 계산한다</li>
 * </ul>
 */
@Entity
@Table(name = "TB_SBTYP_IDNTFR_COL_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubtypeIdentifierColumnMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SBTYP_IDNTFR_COL_MPNG")
    @SequenceGenerator(name = "SQ_SBTYP_IDNTFR_COL_MPNG", sequenceName = "SQ_SBTYP_IDNTFR_COL_MPNG", allocationSize = 50)
    @Column(name = "SBTYP_IDNTFR_COL_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ENTT_TBL_MPNG_SN", nullable = false)
    private EntityTableMapping mapping;

    /** 원본 = 루트 주 식별자 속성 (서브타입이 상속해 보는 속성의 원본 행) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ATRB_SN", nullable = false)
    private LogicalAttribute attribute;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "COL_SN", nullable = false)
    private ColumnInfo column;
}
