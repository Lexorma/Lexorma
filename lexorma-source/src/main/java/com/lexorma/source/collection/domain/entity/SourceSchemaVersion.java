package com.lexorma.source.collection.domain.entity;

import com.lexorma.source.collection.domain.enums.SourceChangeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원본 스키마(이름 공간)의 버전 (AI-1c). 처음 수집했을 때(BASE), 생기거나 이름 표기가 바뀌거나 사라졌을 때(REMOVED, 삭제 표시)만 1행을 더한다.
 * 만든 뒤 고치지 않는다 (setter 없음). 비교 경로와 정규화한 JSON 을 함께 두어 현재 행 없이도 그 시점의 스키마 목록을 재구성한다 (빈 스키마 포함).
 */
@Entity
@Table(name = "TB_SRC_SCHM_VER")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceSchemaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_SCHM_VER")
    @SequenceGenerator(name = "SQ_SRC_SCHM_VER", sequenceName = "SQ_SRC_SCHM_VER", allocationSize = 50)
    @Column(name = "SRC_SCHM_VER_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_SCHM_SN", nullable = false)
    private Long schemaId;

    @Column(name = "SRC_CLCT_EXCN_SN", nullable = false)
    private Long executionId;

    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    @Column(name = "PATH_CN", length = 1000, nullable = false)
    private String pathKey;

    @Column(name = "CRT_CYCL", nullable = false)
    private Integer creationCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_TYPE_CD", length = 20, nullable = false)
    private SourceChangeType changeType;

    @Column(name = "HASH_VL", nullable = false)
    private String contentHash;

    @Column(name = "FRMT_VER_NO", nullable = false)
    private Integer formatVersion;

    @Column(name = "SCHM_CN", columnDefinition = "TEXT", nullable = false)
    private String content;
}
