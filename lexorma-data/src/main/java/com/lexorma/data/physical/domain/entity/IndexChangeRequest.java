package com.lexorma.data.physical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 승인된 인덱스의 변경 요청 (M-14 D-20). 승인된 정의는 요청을 승인할 때만 바뀐다 (반려하면 그대로).
 * 요청자·요청 일시 = 등록자·등록 일시, 처리자·처리 일시 = 수정자·수정 일시. 인덱스가 삭제되면 함께 지워진다 (FK CASCADE).
 */
@Entity
@Table(name = "TB_IDX_CHG_DMND")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexChangeRequest extends BaseEntity {

    public enum Status { REQUESTED, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_IDX_CHG_DMND")
    @SequenceGenerator(name = "SQ_IDX_CHG_DMND", sequenceName = "SQ_IDX_CHG_DMND", allocationSize = 50)
    @Column(name = "IDX_CHG_DMND_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "IDX_SN", nullable = false)
    private TableIndex tableIndex;

    /** 요청 시점의 승인된 정의 JSON ({@code PhysicalModelDto.IndexDefinition}) */
    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT", nullable = false)
    private String beforeData;

    /** 요청한 정의 JSON */
    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT", nullable = false)
    private String afterData;

    @Enumerated(EnumType.STRING)
    @Column(name = "DMND_STTS_CD", length = 20, nullable = false)
    private Status status;

    /** 인덱스 관리 목록·상세의 등록·수정 정보 (bean getter 가 아니라 JSON 프로퍼티가 되지 않는다) */
    public Audit audit() {
        return toAudit();
    }
}
