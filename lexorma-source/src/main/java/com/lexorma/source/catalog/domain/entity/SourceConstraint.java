package com.lexorma.source.catalog.domain.entity;

import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 원본 제약조건 : PK·UNIQUE·CHECK. 이름과 구성 컬럼(순서대로)을 그대로 둔다. CHECK 는 조건식 원문 */
@Entity
@Table(name = "TB_SRC_CNSTRT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceConstraint {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_CNSTRT")
    @SequenceGenerator(name = "SQ_SRC_CNSTRT", sequenceName = "SQ_SRC_CNSTRT", allocationSize = 50)
    @Column(name = "SRC_CNSTRT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_TBL_SN", nullable = false)
    private SourceTable sourceTable;

    @Column(name = "CNSTRT_NM")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "CNSTRT_TYPE_CD", length = 20, nullable = false)
    private SourceConstraintType type;

    @Column(name = "INSP_CND_CN", columnDefinition = "TEXT")
    private String checkExpression;

    @Builder.Default
    @OneToMany(mappedBy = "sourceConstraint", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<SourceConstraintColumn> columns = new ArrayList<>();
}
