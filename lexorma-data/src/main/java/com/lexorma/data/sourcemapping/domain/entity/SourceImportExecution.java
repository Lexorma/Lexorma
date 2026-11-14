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
 * 원본 메타데이터 가져오기 실행 (M-4e, V3). 성공한 가져오기만 남는다 (실패하면 모델 변경과 함께 롤백된다).
 * 객체·속성별 변경 전후는 모델 변경 이력(출처 IMPORT)에 같은 변경 그룹(changeGroupId = 요청 식별자)으로 남는다. 실행자·시각은 감사 컬럼이다.
 * 원본 쪽 ID(연결·수집 실행)는 값만 둔다 : 연결을 지워도 이력은 남는다.
 */
@Entity
@Table(name = "TB_SRC_IMPRT_EXCN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceImportExecution extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_IMPRT_EXCN")
    @SequenceGenerator(name = "SQ_SRC_IMPRT_EXCN", sequenceName = "SQ_SRC_IMPRT_EXCN", allocationSize = 50)
    @Column(name = "SRC_IMPRT_EXCN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    /** 적용한 수집 실행 (마지막 반영 수집) */
    @Column(name = "SRC_CLCT_EXCN_SN")
    private Long executionId;

    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    /** 요청 식별자 = 모델 변경 이력의 변경 그룹 (유니크 : 같은 요청을 두 번 적용하지 않는다) */
    @Column(name = "CHG_GROUP_ID", nullable = false)
    private String changeGroupId;

    /** 요약 JSON : 항목 종류별 수, 대상 TABLE */
    @Column(name = "IMPRT_CN", columnDefinition = "TEXT")
    private String summary;

    /** 실행자 (감사 컬럼) */
    public String appliedBy() {
        return getCreatedBy();
    }

    public java.time.LocalDateTime appliedAt() {
        return getCreated();
    }
}
