package com.lexorma.source.catalog.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 원본 테이블·뷰. 물리 모델의 TableInfo 와 다른 것이다 : 외부 DB 에서 수집한 사실이고 사용자가 고치지 않는다.
 * name 은 원본이 준 이름 그대로, compareName 은 DBMS 규칙으로 정규화한 비교용 이름이다 (SourceIdentifiers) —
 * 같은 스키마 안에서 compareName 이 겹치지 않는다. comment 는 원본 COMMENT 이고 물리 모델의 업무 설명과 무관하다.
 */
@Entity
@Table(name = "TB_SRC_TBL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceTable extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_TBL")
    @SequenceGenerator(name = "SQ_SRC_TBL", sequenceName = "SQ_SRC_TBL", allocationSize = 50)
    @Column(name = "SRC_TBL_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 스키마의 연결과 같은 값. 관계(FK)가 같은 연결의 테이블만 가리키도록 DB 가 함께 묶어 확인한다 */
    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_SCHM_SN", nullable = false)
    private SourceSchema schema;

    @Column(name = "TBL_NM", nullable = false)
    private String name;

    @Column(name = "CMPR_NM", nullable = false)
    private String compareName;

    /** 따옴표로 감싼 식별자인지. 수집 결과가 알려 주지 않으면 null (이름만 보고 단정하지 않는다) */
    @Column(name = "QUOT_YN")
    private Boolean quoted;

    @Enumerated(EnumType.STRING)
    @Column(name = "TBL_TYPE_CD", length = 20, nullable = false)
    private SourceTableType type;

    @Column(name = "ANTN_CN", columnDefinition = "TEXT")
    private String comment;

    /** 테이블과 하위 객체(컬럼·제약조건·인덱스·관계) 전체를 정규화한 내용의 해시 ({@code v1:} + SHA-256). 수집 적재가 채운다 (AI-1c) */
    @Column(name = "HASH_VL")
    private String contentHash;

    @Column(name = "EXST_YN", nullable = false)
    private Boolean present;

    /** 생성 차수 : 원본에서 사라졌다가(EXST_YN = false) 같은 이름으로 다시 생길 때마다 1 씩 는다 (AI-1c). 이전 생명주기의 버전·이력과 구분한다 */
    @Builder.Default
    @Column(name = "CRT_CYCL", nullable = false)
    private Integer creationCycle = 1;
}
