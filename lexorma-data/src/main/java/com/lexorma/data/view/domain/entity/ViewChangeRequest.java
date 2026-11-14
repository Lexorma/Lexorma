package com.lexorma.data.view.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 승인된 VIEW 의 변경 요청 (M-15, 인덱스 변경 요청 M-14 D-20 과 같은 구조). 전후 정의는 VIEW 전체(기본 정보·정의 SQL·출력 컬럼·참조 객체) 이고,
 * 승인할 때 한 트랜잭션으로 모두 반영된다 (반려하면 그대로). 요청자·요청 일시 = 등록자·등록 일시, 처리자·처리 일시 = 수정자·수정 일시.
 * VIEW 가 삭제되면 함께 지워진다 (FK CASCADE).
 */
@Entity
@Table(name = "TB_VIEW_CHG_DMND")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ViewChangeRequest extends BaseEntity {

    public enum Status { REQUESTED, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_VIEW_CHG_DMND")
    @SequenceGenerator(name = "SQ_VIEW_CHG_DMND", sequenceName = "SQ_VIEW_CHG_DMND", allocationSize = 50)
    @Column(name = "VIEW_CHG_DMND_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "VIEW_SN", nullable = false)
    private PhysicalView view;

    /** 요청 시점의 승인된 정의 JSON ({@code ViewDto.Definition}) */
    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT", nullable = false)
    private String beforeData;

    /** 요청한 정의 JSON */
    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT", nullable = false)
    private String afterData;

    @Enumerated(EnumType.STRING)
    @Column(name = "DMND_STTS_CD", length = 20, nullable = false)
    private Status status;

    public Audit audit() {
        return toAudit();
    }
}
