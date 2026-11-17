package com.lexorma.data.sourcemapping.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 원본 TABLE·컬럼 매핑 변경 이력 (DEC-06, V4). 추가만 한다 (수정·삭제 API 없음, setter 없음). 매핑을 해제해도 남도록 FK 를 걸지 않고 번호만 둔다.
 * 변경 전후는 JSON (원본·모델 객체의 이름과 번호). 프로젝트 삭제 때만 지운다 (ProjectDataCleanup).
 */
@Entity
@Table(name = "TB_SRC_MPNG_HSTRY")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceMappingHistory extends BaseEntity {

    public enum TargetType { TABLE, COLUMN }

    /** CONFIRM : 새로 확정 / CHANGE : 대상 바꾸기 / RENAME : 원본 이름 변경을 확정해 같은 매핑을 새 원본으로 옮김 / RELEASE : 해제 */
    public enum ChangeType { CONFIRM, CHANGE, RENAME, RELEASE }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_MPNG_HSTRY")
    @SequenceGenerator(name = "SQ_SRC_MPNG_HSTRY", sequenceName = "SQ_SRC_MPNG_HSTRY", allocationSize = 50)
    @Column(name = "SRC_MPNG_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_TBL_MPNG_SN", nullable = false)
    private Long tableMappingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TRGT_TYPE_CD", length = 20, nullable = false)
    private TargetType targetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_TYPE_CD", length = 20, nullable = false)
    private ChangeType changeType;

    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT")
    private String before;

    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT")
    private String after;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime changedAt;

    /** 확정·기록한 일시와 사용자 (화면 표시용) */
    public Audit audit() {
        return toAudit();
    }
}
