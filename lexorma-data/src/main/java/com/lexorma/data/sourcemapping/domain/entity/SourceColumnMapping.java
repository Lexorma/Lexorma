package com.lexorma.data.sourcemapping.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 원본 컬럼 ↔ 모델 컬럼 (DEC-06, V4, docs/completed/source-rename-mapping-dec06.md). 사용자가 이름 변경 후보를 확인해 확정한 대응만 저장한다 — 이름이 같은 컬럼은
 * 저장하지 않고 비교할 때 이름으로 짝짓는다. 원본 쪽은 TABLE 매핑 안의 원본 컬럼 비교 이름(원본 컬럼은 버전 JSON 안에 있어 행 ID 가 없다),
 * 모델 쪽은 컬럼 ID (지우면 null : "모델 없음"). TABLE 매핑을 해제하면 DB FK(ON DELETE CASCADE)로 함께 지워진다. TABLE 매핑 안에서 양쪽 모두 1:1 (UX_SRC_COL_MPNG_01·02).
 */
@Entity
@Table(name = "TB_SRC_COL_MPNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceColumnMapping extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_COL_MPNG")
    @SequenceGenerator(name = "SQ_SRC_COL_MPNG", sequenceName = "SQ_SRC_COL_MPNG", allocationSize = 50)
    @Column(name = "SRC_COL_MPNG_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_TBL_MPNG_SN", nullable = false)
    private Long tableMappingId;

    /** 원본 컬럼의 비교 이름 (원본 DBMS 규칙으로 접은 이름) */
    @Column(name = "CMPR_NM", nullable = false)
    private String compareName;

    /** 확정할 때의 원본 컬럼 이름 (표시용) */
    @Column(name = "COL_NM", nullable = false)
    private String sourceColumnName;

    /** 모델 컬럼 (지워졌으면 null) */
    @Column(name = "COL_SN")
    private Long columnId;

    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    /** 확정·기록한 일시와 사용자 (화면 표시용) */
    public Audit audit() {
        return toAudit();
    }
}
