package com.lexorma.source.collection.domain.entity;

import com.lexorma.source.collection.domain.enums.ChangeTargetType;
import com.lexorma.source.collection.domain.enums.SourceChangeType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원본 객체 하나의 추가·수정·삭제 (AI-1c). 수정은 바뀐 항목 목록과 그 객체의 전후 JSON, 추가는 이후만, 삭제는 이전만 담는다.
 * 대상은 비교용 식별자(스키마 경로·테이블·객체의 비교 키)로 가리킨다 : 제약조건·인덱스·관계는 적재 때마다 내부 ID 가 바뀌므로 ID 를 쓰지 않는다.
 * 스키마·테이블 ID 는 조회 편의로 함께 두지만 FK 는 걸지 않는다 (모델 변경 이력과 같은 방식). 처음 수집한 이름 공간(BASE)은 남기지 않는다.
 */
@Entity
@Table(name = "TB_SRC_CHG_HSTRY")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_CHG_HSTRY")
    @SequenceGenerator(name = "SQ_SRC_CHG_HSTRY", sequenceName = "SQ_SRC_CHG_HSTRY", allocationSize = 50)
    @Column(name = "SRC_CHG_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;

    @Column(name = "SRC_CLCT_EXCN_SN", nullable = false)
    private Long executionId;

    @Column(name = "RFLT_ORD", nullable = false)
    private Integer reflectedOrder;

    @Column(name = "SRC_SCHM_SN")
    private Long schemaId;

    @Column(name = "SRC_TBL_SN")
    private Long tableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TRGT_TYPE_CD", length = 20, nullable = false)
    private ChangeTargetType targetType;

    @Column(name = "TRGT_IDNTFR_CN", length = 2000, nullable = false)
    private String targetKey;

    @Column(name = "TRGT_NM")
    private String targetName;

    /** 변경이 일어난 스키마·테이블의 생성 차수 (사라졌다 다시 생긴 같은 이름의 객체를 구분한다) */
    @Column(name = "CRT_CYCL", nullable = false)
    private Integer creationCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_TYPE_CD", length = 20, nullable = false)
    private SourceChangeType changeType;

    /** 바뀐 항목 (JSON 배열) */
    @Column(name = "CHG_ARTCL_CN", columnDefinition = "TEXT")
    private String changedFields;

    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT")
    private String before;

    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT")
    private String after;
}
