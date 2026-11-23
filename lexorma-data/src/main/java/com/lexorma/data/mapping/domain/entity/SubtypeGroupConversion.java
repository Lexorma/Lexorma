package com.lexorma.data.mapping.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

/**
 * 서브타입 그룹 변환 = 상속 그룹의 물리 변환 기록 (M-7 상속 A·C형, V2, docs/completed/inheritance-strategy-ac-m7.md — D-AC-02·D-AC-18).
 * <ul>
 *   <li>그룹 하나에 0~1행. 행이 없으면 B형(TABLE_PER_TYPE) 또는 아직 만들지 않은 그룹이다 — 행이 없다는 이유로 B형 생성 완료로 보지 않는다</li>
 *   <li>A형(SINGLE_TABLE) : 통합 테이블(루트 엔터티의 직접 매핑 테이블)·분류 컬럼(그룹 분류 속성의 매핑 컬럼)이 반드시 있다. C형(TABLE_PER_CONCRETE) : 둘 다 없다 (CK_SBTYP_GROUP_CNVR_02)</li>
 *   <li>한 상속 계층의 그룹은 같은 전략·같은 통합 테이블이어야 한다 — 서비스가 논리 모델 행 잠금 안에서 검사한다</li>
 *   <li>관련 매핑이 남아 있으면 지우지 않는다 (D-AC-18). 물리 테이블·컬럼을 바꾸지 않는다</li>
 * </ul>
 */
@Entity
@Table(name = "TB_SBTYP_GROUP_CNVR")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubtypeGroupConversion extends BaseEntity {

    /** 물리 변환 전략 (기록하는 값 — B형 TABLE_PER_TYPE 은 기록하지 않는다) */
    public enum Strategy {
        /** A형 : 상속 계층을 루트 테이블 하나로 통합 */
        SINGLE_TABLE,
        /** C형 : 말단 서브타입마다 테이블, 조상 속성 복제 */
        TABLE_PER_CONCRETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SBTYP_GROUP_CNVR")
    @SequenceGenerator(name = "SQ_SBTYP_GROUP_CNVR", sequenceName = "SQ_SBTYP_GROUP_CNVR", allocationSize = 50)
    @Column(name = "SBTYP_GROUP_CNVR_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_SBTYP_GROUP_SN", nullable = false)
    private LogicalSubtypeGroup group;

    @Enumerated(EnumType.STRING)
    @Column(name = "CNVR_TYPE_CD", nullable = false, length = 20)
    private Strategy strategy;

    /** A형 통합 테이블 (C형 null) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TBL_SN")
    private TableInfo table;

    /** A형 분류 컬럼 = 그룹 분류 속성의 매핑 컬럼 (C형 null) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CLSF_COL_SN")
    private ColumnInfo classificationColumn;
}
