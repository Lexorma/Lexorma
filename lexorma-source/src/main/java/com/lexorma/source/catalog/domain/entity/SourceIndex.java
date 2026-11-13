package com.lexorma.source.catalog.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 원본 인덱스 : 이름, 방식(원본 표현 그대로), 고유 여부, 부분 인덱스 조건, 구성 요소(컬럼 또는 표현식, 순서대로) */
@Entity
@Table(name = "TB_SRC_IDX")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_IDX")
    @SequenceGenerator(name = "SQ_SRC_IDX", sequenceName = "SQ_SRC_IDX", allocationSize = 50)
    @Column(name = "SRC_IDX_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_TBL_SN", nullable = false)
    private SourceTable sourceTable;

    @Column(name = "IDX_NM", nullable = false)
    private String name;

    @Column(name = "IDX_TYPE_NM")
    private String indexType;

    @Column(name = "UNQ_YN", nullable = false)
    private Boolean uniqueIndex;

    /** 부분 인덱스 조건 (원본 표현 그대로). 없으면 null */
    @Column(name = "CND_CN", columnDefinition = "TEXT")
    private String condition;

    @Builder.Default
    @OneToMany(mappedBy = "sourceIndex", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<SourceIndexColumn> columns = new ArrayList<>();
}
