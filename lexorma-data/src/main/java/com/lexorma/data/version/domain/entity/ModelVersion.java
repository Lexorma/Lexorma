package com.lexorma.data.version.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 모델 버전 (M-9 3차) : 사용자가 명시적으로 만든 시점의 프로젝트 모델 전체 구조.
 * 변경 이력(ModelChangeHistory)이 개별 변경의 기록이라면, 버전은 그 시점 상태의 보존이다. 추가만 한다 :
 * 수정·삭제 API 가 없고 {@link Immutable} 이라 엔티티를 바꿔도 UPDATE 가 나가지 않는다. 현재 모델이 바뀌거나 지워져도 남도록 모델 테이블에 FK 를 걸지 않는다.
 */
@Entity
@Immutable
@Table(name = "TB_MDL_VER")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_MDL_VER")
    @SequenceGenerator(name = "SQ_MDL_VER", sequenceName = "SQ_MDL_VER", allocationSize = 50)
    @Column(name = "MDL_VER_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 사용자가 붙인 이름 (프로젝트 안에서 하나뿐, 형식은 정하지 않는다) */
    @Column(name = "VER_NM", length = 100, nullable = false)
    private String versionName;

    @Column(name = "VER_EXPLN", length = 1000)
    private String description;

    /** 모델 내용(JSON)의 형식 번호 : 담는 구조가 바뀌면 올린다 */
    @Column(name = "FRMT_VER_NO", nullable = false)
    private Integer formatVersion;

    /** 대상 종류별 객체 수 JSON (목록에서 모델 내용을 읽지 않고 규모를 보여 준다) */
    @Column(name = "MDL_CNT_CN", columnDefinition = "TEXT", nullable = false)
    private String counts;

    /** 모델 내용 JSON : 대상 종류 → [{id, name, state}] (state 는 변경 이력의 상태와 같은 모양) */
    @Column(name = "MDL_CN", columnDefinition = "TEXT", nullable = false)
    private String snapshot;

    @Column(name = "RGTR_ID")
    private String createdBy;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime createdAt;
}
