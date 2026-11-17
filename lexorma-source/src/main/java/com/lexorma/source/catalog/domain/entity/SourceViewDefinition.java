package com.lexorma.source.catalog.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.source.catalog.domain.enums.SourceViewDefinitionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 원본 VIEW 의 정의 원문 (DEC-16, docs/completed/source-view-definition-dec16.md). 원문은 이 행에만 있다 : 카탈로그 버전·변경 이력·일반 카탈로그 API·AI·Export·MCP 에는
 * 해시까지만 간다. 원문은 키가 있으면 SecretCipher 로 암호화한다 (scope source-view-definition / 프로젝트 / 이 행 ID / definition).
 * 정의를 읽지 못한 수집(status ≠ COLLECTED)은 상태만 바꾸고 마지막 원문·해시를 남긴다.
 */
@Entity
@Table(name = "TB_SRC_VIEW_DFN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceViewDefinition extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_VIEW_DFN")
    @SequenceGenerator(name = "SQ_SRC_VIEW_DFN", sequenceName = "SQ_SRC_VIEW_DFN", allocationSize = 50)
    @Column(name = "SRC_VIEW_DFN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_TBL_SN", nullable = false)
    private Long tableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "DFN_STTS_CD", length = 20, nullable = false)
    private SourceViewDefinitionStatus status;

    /** 원문 또는 암호문 (encrypted). 화면·API 에는 SourceViewDefinitionService.read 로만 나간다 */
    @Column(name = "DFN_CN", columnDefinition = "TEXT")
    private String content;

    @Column(name = "ENCPT_YN", nullable = false)
    private Boolean encrypted;

    @Column(name = "HASH_VL")
    private String hash;

    /** 정의가 바뀌기 전의 해시 : 없으면 처음 수집한 정의다 */
    @Column(name = "BFR_HASH_VL")
    private String previousHash;

    @Column(name = "DFN_LEN")
    private Integer length;

    @Column(name = "CLCT_DT")
    private LocalDateTime collectedAt;

    /** 정의가 처음 수집되었거나 마지막으로 바뀐 수집의 반영 순서 */
    @Column(name = "CHG_RFLT_ORD")
    private Integer changedOrder;
}
