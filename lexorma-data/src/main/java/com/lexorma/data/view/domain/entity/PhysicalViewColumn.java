package com.lexorma.data.view.domain.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * VIEW 출력 컬럼 (M-15) : 사용자가 입력한 모델링 정보다. 정의 SQL 에서 추출하지 않고 실제 결과 컬럼과의 일치를 보장하지 않는다.
 * 데이터 타입은 비워 둘 수 있다. DDL 의 CREATE VIEW 에는 넣지 않는다.
 */
@Entity
@Table(name = "TB_VIEW_COL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalViewColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_VIEW_COL")
    @SequenceGenerator(name = "SQ_VIEW_COL", sequenceName = "SQ_VIEW_COL", allocationSize = 50)
    @Column(name = "VIEW_COL_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "VIEW_SN", nullable = false)
    private PhysicalView view;

    @Column(name = "COL_NM", nullable = false)
    private String columnName;

    @Column(name = "COL_KORN_NM")
    private String columnKorName;

    @Column(name = "DATA_TYPE_NM")
    private String dataType;

    @Column(name = "COL_ORD", nullable = false)
    private Integer columnOrder;

    @Column(name = "COL_EXPLN")
    private String columnDescription;
}
