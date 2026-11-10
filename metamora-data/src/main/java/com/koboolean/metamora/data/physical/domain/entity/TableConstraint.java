package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블 제약조건 중 UNIQUE(복합 포함)·CHECK.
 * PRIMARY KEY 는 컬럼의 isPk, FOREIGN KEY 는 {@link TableRelation} 이 기준이라 여기에 두지 않는다 (같은 제약을 두 곳에 저장하지 않는다).
 */
@Entity
@Table(name = "table_constraint")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableConstraint extends BaseEntity {

    public enum Type { UNIQUE, CHECK }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "table_constraint_seq")
    @SequenceGenerator(name = "table_constraint_seq", sequenceName = "table_constraint_seq", allocationSize = 50)
    @Column(name = "table_constraint_id", nullable = false, unique = true)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_info_id", nullable = false)
    private TableInfo tableInfo;

    @Column(nullable = false)
    private String constraintName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Type constraintType;

    /** CHECK 조건식 (예: AMT >= 0) - DDL 에 그대로 쓴다 */
    @Column(length = 1000)
    private String checkExpression;

    @Column
    private Boolean isApproval;

    @Builder.Default
    @OneToMany(mappedBy = "tableConstraint", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<TableConstraintColumn> columns = new ArrayList<>();
}
