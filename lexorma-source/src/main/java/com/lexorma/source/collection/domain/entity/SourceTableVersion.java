package com.lexorma.source.collection.domain.entity;

import com.lexorma.source.collection.domain.enums.SourceChangeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원본 테이블의 버전 (AI-1c, 테이블 단위 Copy-on-Write). 테이블이나 그 하위 객체(컬럼·제약조건·인덱스·관계)가 바뀐 실행에서만 1행을 더한다 :
 * 바뀌지 않은 테이블은 이전 버전이 계속 유효하다. 반영 순서 R 의 테이블 = 테이블마다 반영 순서가 R 이하인 마지막 버전 (REMOVED 면 그때 없던 것).
 * 만든 뒤 고치지 않는다 (setter 없음). 비교 경로·비교 명과 정규화한 JSON 을 함께 두어 현재 행에 기대지 않는다.
 */
@Entity
@Table(name = "TB_SRC_TBL_VER")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceTableVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_TBL_VER")
    @SequenceGenerator(name = "SQ_SRC_TBL_VER", sequenceName = "SQ_SRC_TBL_VER", allocationSize = 50)
    @Column(name = "SRC_TBL_VER_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_SCHM_SN", nullable = false)
    private Long schemaId;

    @Column(name = "SRC_TBL_SN", nullable = false)
    private Long tableId;

    @Column(name = "SRC_CLCT_EXCN_SN", nullable = false)
    private Long executionId;

    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    @Column(name = "PATH_CN", length = 1000, nullable = false)
    private String pathKey;

    @Column(name = "CMPR_NM", nullable = false)
    private String compareName;

    @Column(name = "CRT_CYCL", nullable = false)
    private Integer creationCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_TYPE_CD", length = 20, nullable = false)
    private SourceChangeType changeType;

    @Column(name = "HASH_VL", nullable = false)
    private String contentHash;

    @Column(name = "FRMT_VER_NO", nullable = false)
    private Integer formatVersion;

    @Column(name = "TBL_CN", columnDefinition = "TEXT", nullable = false)
    private String content;
}
