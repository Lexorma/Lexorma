package com.lexorma.data.mapping.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import jakarta.persistence.*;
import lombok.*;

/**
 * 서브타입 속성 컬럼 매핑 = C형 상속 일반 속성 매핑 (M-7 상속 C형, V2, docs/completed/inheritance-strategy-ac-m7.md — D-AC-02).
 * 말단 서브타입 엔터티의 엔터티 ↔ 테이블 매핑 아래에서, 조상(루트·중간) 엔터티의 일반 속성(원본 속성 ID) ↔ 말단 테이블의 복제 컬럼을 연결한다.
 * <ul>
 *   <li>같은 원본 속성을 여러 말단 테이블의 컬럼이 각각 쓴다 — 속성 단독 UNIQUE 가 없다 ((매핑, 속성)·컬럼 UNIQUE)</li>
 *   <li>상속 PK 는 {@link SubtypeIdentifierColumnMapping}, 말단 자체 속성은 {@link AttributeColumnMapping} — 같은 컬럼을 세 매핑이 함께 쓰지 않는다 (서비스 검사)</li>
 *   <li>연결만 저장한다 — 물리 컬럼은 바꾸지 않고 정합성은 조회 때 계산한다</li>
 * </ul>
 */
@Entity
@Table(name = "TB_SBTYP_ATRB_COL_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubtypeAttributeColumnMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SBTYP_ATRB_COL_MPNG")
    @SequenceGenerator(name = "SQ_SBTYP_ATRB_COL_MPNG", sequenceName = "SQ_SBTYP_ATRB_COL_MPNG", allocationSize = 50)
    @Column(name = "SBTYP_ATRB_COL_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ENTT_TBL_MPNG_SN", nullable = false)
    private EntityTableMapping mapping;

    /** 원본 = 조상 엔터티의 일반 속성 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ATRB_SN", nullable = false)
    private LogicalAttribute attribute;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "COL_SN", nullable = false)
    private ColumnInfo column;
}
