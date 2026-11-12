package com.lexorma.data.physical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블 제약조건 중 UNIQUE(복합 포함)·CHECK.
 * PRIMARY KEY 는 컬럼의 isPk, FOREIGN KEY 는 {@link TableRelation} 이 기준이라 여기에 두지 않는다 (같은 제약을 두 곳에 저장하지 않는다).
 */
@Entity
@Table(name = "TB_TBL_CNSTRT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableConstraint extends BaseEntity {

    public enum Type { UNIQUE, CHECK }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_CNSTRT")
    @SequenceGenerator(name = "SQ_TBL_CNSTRT", sequenceName = "SQ_TBL_CNSTRT", allocationSize = 50)
    @Column(name = "CNSTRT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TBL_SN", nullable = false)
    private TableInfo tableInfo;

    @Column(name = "CNSTRT_NM", nullable = false)
    private String constraintName;

    @Enumerated(EnumType.STRING)
    @Column(name = "CNSTRT_TYPE_CD", length = 20, nullable = false)
    private Type constraintType;

    /** CHECK 조건식 (예: AMT >= 0) - DDL 에 그대로 쓴다 */
    @Column(name = "INSP_EXPR_CN", length = 1000)
    private String checkExpression;

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @Builder.Default
    @OneToMany(mappedBy = "tableConstraint", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<TableConstraintColumn> columns = new ArrayList<>();
}
