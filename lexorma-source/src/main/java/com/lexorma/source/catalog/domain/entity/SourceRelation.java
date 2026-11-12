package com.lexorma.source.catalog.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 원본 FK. sourceTable 이 참조하는 쪽, referencedTable 이 참조되는 쪽이다 (다른 스키마일 수 있다).
 * 참조되는 테이블이 카탈로그에 없으면(수집 범위 밖) referencedTable 은 비우고 이름만 남긴다 — 원본의 사실을 버리지 않는다.
 * 두 테이블은 같은 연결의 것이어야 한다 : 연결 ID 를 함께 묶은 FK(FK_SRC_REL_01·02)로 DB 가 막는다.
 */
@Entity
@Table(name = "TB_SRC_REL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_REL")
    @SequenceGenerator(name = "SQ_SRC_REL", sequenceName = "SQ_SRC_REL", allocationSize = 50)
    @Column(name = "SRC_REL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_TBL_SN", nullable = false)
    private SourceTable sourceTable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RFRNC_SRC_TBL_SN")
    private SourceTable referencedTable;

    /** 참조되는 테이블의 스키마·이름 (원본이 준 그대로) */
    @Column(name = "RFRNC_SCHM_NM")
    private String referencedSchemaName;

    @Column(name = "RFRNC_TBL_NM", nullable = false)
    private String referencedTableName;

    @Column(name = "REL_NM")
    private String name;

    /** 삭제·수정 규칙 (원본 표현 그대로 : CASCADE, SET NULL, RESTRICT, NO ACTION …) */
    @Column(name = "DEL_RULE_CD", length = 20)
    private String deleteRule;

    @Column(name = "MDFCN_RULE_CD", length = 20)
    private String updateRule;

    @Builder.Default
    @OneToMany(mappedBy = "relation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<SourceRelationColumn> columns = new ArrayList<>();
}
