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
@Table(name = "table_index")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableIndex extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "table_index_seq")
    @SequenceGenerator(name = "table_index_seq", sequenceName = "table_index_seq", allocationSize = 50)
    @Column(name = "table_index_id", nullable = false, unique = true)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_info_id", nullable = false)
    private TableInfo tableInfo;

    @Column(nullable = false)
    private String indexName;

    /** 인덱스 방식 (예: BTREE, HASH, BITMAP) - 저장만 하고 DDL 에는 쓰지 않는다 (DBMS 별 문법이 달라 M-11 에서 검토) */
    @Column
    private String indexType;

    @Column(nullable = false)
    private Boolean isUnique;

    @Column
    private Boolean isApproval;

    @Builder.Default
    @OneToMany(mappedBy = "tableIndex", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<TableIndexColumn> columns = new ArrayList<>();
}
