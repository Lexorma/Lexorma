package com.lexorma.source.catalog.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 원본 VIEW 정의 원문을 확인한 감사 기록 (DEC-16, 추가만 한다). 확인한 사람은 감사 컬럼(등록자), 원문 대신 그때의 해시만 남긴다 */
@Entity
@Table(name = "TB_SRC_VIEW_DFN_IDNTY_HSTRY")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceViewDefinitionAccess extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_VIEW_DFN_IDNTY_HSTRY")
    @SequenceGenerator(name = "SQ_SRC_VIEW_DFN_IDNTY_HSTRY", sequenceName = "SQ_SRC_VIEW_DFN_IDNTY_HSTRY", allocationSize = 50)
    @Column(name = "SRC_VIEW_DFN_IDNTY_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_TBL_SN", nullable = false)
    private Long tableId;

    @Column(name = "HASH_VL")
    private String hash;

    @Column(name = "IDNTY_DT", nullable = false)
    private LocalDateTime accessedAt;

    /** 확인한 사용자 (감사 컬럼의 등록자) */
    public String accountId() {
        return toAudit().createdBy();
    }
}
