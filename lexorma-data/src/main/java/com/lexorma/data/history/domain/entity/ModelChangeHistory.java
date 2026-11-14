package com.lexorma.data.history.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 모델 변경 이력 (M-9) : 물리 모델 객체 하나가 한 트랜잭션에서 실제로 바뀐 내용 1건.
 * 현재 모델은 기존 테이블이 원본이고, 이 테이블은 추가만 한다 (수정·삭제 API 없음). 대상이 삭제돼도 남도록 모델 테이블에 FK 를 걸지 않는다.
 */
@Entity
@Table(name = "TB_MDL_CHG_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ModelChangeHistory {

    /**
     * 변경 대상 종류 (컬럼 길이 20자 안에서 정한다).
     * 물리 : PK 는 컬럼의 PK 여부(PHYSICAL_COLUMN), FK 는 관계(PHYSICAL_RELATION)에 들어 있다.
     * 논리 (M-9 2차) : 모델·엔터티·속성·식별자·관계. 매핑 : 엔터티 ↔ 테이블(TABLE_MAPPING), 속성 ↔ 컬럼(COLUMN_MAPPING)
     */
    public enum TargetType {
        PHYSICAL_TABLE, PHYSICAL_COLUMN, PHYSICAL_RELATION, PHYSICAL_INDEX, PHYSICAL_CONSTRAINT, PHYSICAL_VIEW,
        LOGICAL_MODEL, LOGICAL_ENTITY, LOGICAL_ATTRIBUTE, LOGICAL_IDENTIFIER, LOGICAL_RELATIONSHIP,
        TABLE_MAPPING, COLUMN_MAPPING
    }

    /** APPROVE·REVOKE 는 승인 여부만 바뀐 경우 (다른 값도 함께 바뀌면 UPDATE 한 건) */
    public enum Action { CREATE, UPDATE, DELETE, APPROVE, REVOKE }

    /** AUTO_SYNC : 컬럼의 참조 테이블명에서 자동으로 맞춘 관계(REF_COLUMN)의 변경 */
    public enum Source { USER, AUTO_SYNC }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_MDL_CHG_HSTRY")
    @SequenceGenerator(name = "SQ_MDL_CHG_HSTRY", sequenceName = "SQ_MDL_CHG_HSTRY", allocationSize = 50)
    @Column(name = "MDL_CHG_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 같은 작업(트랜잭션)에서 함께 바뀐 이력을 묶는다 */
    @Column(name = "CHG_GROUP_ID", nullable = false)
    private String changeGroupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TRGT_TYPE_CD", length = 20, nullable = false)
    private TargetType targetType;

    @Column(name = "TRGT_SN", nullable = false)
    private Long targetId;

    /** 변경 시점의 이름 (삭제된 대상도 목록에서 알아볼 수 있게) */
    @Column(name = "TRGT_NM")
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_TYPE_CD", length = 20, nullable = false)
    private Action action;

    /** 변경 전 상태 JSON (CREATE 는 null) */
    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT")
    private String beforeData;

    /** 변경 후 상태 JSON (DELETE 는 null) */
    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT")
    private String afterData;

    @Enumerated(EnumType.STRING)
    @Column(name = "CHG_SRC_CD", length = 20, nullable = false)
    private Source source;

    /** 변경한 사용자의 아이디 (로그인 없이 실행된 변경은 null) */
    @Column(name = "USER_ID")
    private String userId;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime changedAt;
}
