package com.koboolean.metamora.data.physical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 이름 있는 인덱스·복합 인덱스. 단일 컬럼 인덱스·고유값은 기존처럼 컬럼의 isIndex / isUnique 로도 정의할 수 있고,
 * DDL 은 같은 컬럼 구성의 인덱스를 한 번만 만든다.
 */
@Entity
@Table(name = "TB_TBL_IDX")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableIndex extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_IDX")
    @SequenceGenerator(name = "SQ_TBL_IDX", sequenceName = "SQ_TBL_IDX", allocationSize = 50)
    @Column(name = "IDX_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TBL_SN", nullable = false)
    private TableInfo tableInfo;

    @Column(name = "IDX_NM", nullable = false)
    private String indexName;

    /** 인덱스 방식 (예: BTREE, HASH, BITMAP) - 저장만 하고 DDL 에는 쓰지 않는다 (DBMS 별 문법이 달라 M-11 에서 검토) */
    @Column(name = "IDX_TYPE_NM")
    private String indexType;

    @Column(name = "UNQ_YN", nullable = false)
    private Boolean isUnique;

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @Builder.Default
    @OneToMany(mappedBy = "tableIndex", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<TableIndexColumn> columns = new ArrayList<>();
}
