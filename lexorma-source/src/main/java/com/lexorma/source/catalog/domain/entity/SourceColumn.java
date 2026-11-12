package com.lexorma.source.catalog.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 원본 컬럼. dataType 은 DBMS 가 준 타입 표현 그대로 둔다 (다른 DBMS 타입으로 바꾸지 않는다). 표준 용어와 이어 두지 않는다.
 */
@Entity
@Table(name = "TB_SRC_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceColumn extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_COL")
    @SequenceGenerator(name = "SQ_SRC_COL", sequenceName = "SQ_SRC_COL", allocationSize = 50)
    @Column(name = "SRC_COL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_TBL_SN", nullable = false)
    private SourceTable sourceTable;

    @Column(name = "COL_NM", nullable = false)
    private String name;

    @Column(name = "CMPR_NM", nullable = false)
    private String compareName;

    @Column(name = "QUOT_YN")
    private Boolean quoted;

    /** 원본의 컬럼 순서 (1 부터) */
    @Column(name = "COL_ORD", nullable = false)
    private Integer ordinal;

    @Column(name = "DATA_TYPE_NM")
    private String dataType;

    @Column(name = "MAX_LEN")
    private Integer maxLength;

    @Column(name = "DATA_PRCSN", precision = 38, scale = 2)
    private BigDecimal precision;

    @Column(name = "DCPT_LEN")
    private Integer scale;

    @Column(name = "NULL_ALWNC_YN")
    private Boolean nullable;

    /** 기본값 표현식 원문 */
    @Column(name = "BSC_VL_CN", columnDefinition = "TEXT")
    private String defaultValue;

    @Column(name = "ANTN_CN", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "HASH_VL")
    private String contentHash;

    @Column(name = "EXST_YN", nullable = false)
    private Boolean present;
}
