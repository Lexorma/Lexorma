package com.lexorma.source.collection.domain.entity;

import com.lexorma.source.collection.domain.enums.TargetResult;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 수집 실행이 대상으로 한 이름 공간 하나와 그 결과 (AI-1c). PARTIAL 은 등록할 때 요청한 스키마로, FULL 은 반영할 때 결과의 스키마로 만든다.
 * 결과가 COMPLETED 인 대상은 "그 실행에서 완전하게 확인했다" 는 뜻이다 : 삭제 판정과 객체의 마지막 확인 시점이 이것을 근거로 한다.
 */
@Entity
@Table(name = "TB_SRC_CLCT_TRGT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceCollectionTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_CLCT_TRGT")
    @SequenceGenerator(name = "SQ_SRC_CLCT_TRGT", sequenceName = "SQ_SRC_CLCT_TRGT", allocationSize = 50)
    @Column(name = "SRC_CLCT_TRGT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CLCT_EXCN_SN", nullable = false)
    private Long executionId;

    @Column(name = "PATH_CN", length = 1000, nullable = false)
    private String pathKey;

    @Column(name = "CTLG_NM")
    private String catalogName;

    @Column(name = "SCHM_NM")
    private String schemaName;

    @Column(name = "SRC_SCHM_SN")
    private Long schemaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "CLCT_RSLT_CD", length = 20, nullable = false)
    private TargetResult result;

    @Column(name = "TBL_CNT")
    private Integer tableCount;

    @Column(name = "COL_CNT")
    private Integer columnCount;

    @Column(name = "ERR_CD", length = 100)
    private String errorCode;
}
