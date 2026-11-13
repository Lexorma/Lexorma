package com.lexorma.data.physical.domain.entity;

import com.lexorma.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.*;
import lombok.*;

/**
 * 인덱스 구성 요소 (M-14 2차) : 키 = 컬럼(column) 또는 표현식(expression) 중 하나, INCLUDE = 컬럼만.
 * sort 는 AI-2b 원본 카탈로그와 같은 표기 : null(ASC, NULLS 기본) / DESC / DESC NULLS LAST / NULLS FIRST / INCLUDE.
 * 키와 INCLUDE 는 한 columnOrder 순서를 쓰고 키가 먼저다.
 */
@Entity
@Table(name = "TB_TBL_IDX_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableIndexColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_IDX_COL")
    @SequenceGenerator(name = "SQ_TBL_IDX_COL", sequenceName = "SQ_TBL_IDX_COL", allocationSize = 50)
    @Column(name = "IDX_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "IDX_SN", nullable = false)
    private TableIndex tableIndex;

    /** 표현식 요소면 null */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "COL_SN")
    private ColumnInfo column;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;

    @Column(name = "EXPR_CN", columnDefinition = "TEXT")
    private String expression;

    @Column(name = "SORT_CD", length = 20)
    private String sort;

    public static final String INCLUDE = "INCLUDE";

    public boolean isInclude() {
        return INCLUDE.equals(sort);
    }
}
