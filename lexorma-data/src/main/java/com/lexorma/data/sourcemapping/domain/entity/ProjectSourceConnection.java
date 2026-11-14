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
 * 프로젝트의 가져오기 기준 원본 연결 (M-4c, 프로젝트마다 하나). 원본 연결은 lexorma-source 의 것이라 일련번호 값만 둔다 (FK 없음 — 연결 삭제는 이벤트로 정리).
 * 매핑을 바꾸는 트랜잭션은 이 행을 배타 잠금으로 잡아 프로젝트 안에서 한 번에 하나씩 처리한다.
 */
@Entity
@Table(name = "TB_PRJ_SRC_CNTN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectSourceConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PRJ_SRC_CNTN")
    @SequenceGenerator(name = "SQ_PRJ_SRC_CNTN", sequenceName = "SQ_PRJ_SRC_CNTN", allocationSize = 50)
    @Column(name = "PRJ_SRC_CNTN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "SRC_CNTN_SN", nullable = false)
    private Long connectionId;
}
