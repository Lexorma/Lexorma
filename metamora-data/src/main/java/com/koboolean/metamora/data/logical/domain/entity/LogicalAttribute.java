package com.koboolean.metamora.data.logical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import jakarta.persistence.*;
import lombok.*;

/**
 * 논리 속성. 표준 용어를 ID 로 참조할 수 있고, 표준 도메인은 용어를 거쳐서만 연결된다 (용어 ↔ 도메인 연결을 다시 저장하지 않는다).
 * 물리 컬럼은 참조하지 않는다.
 */
@Entity
@Table(name = "TB_LGCL_ATRB")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalAttribute extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_ATRB")
    @SequenceGenerator(name = "SQ_LGCL_ATRB", sequenceName = "SQ_LGCL_ATRB", allocationSize = 50)
    @Column(name = "LGCL_ATRB_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_ENTT_SN", nullable = false)
    private LogicalEntity entity;

    @Column(name = "ATRB_NM", nullable = false)
    private String attributeName;

    @Column(name = "ATRB_EXPLN", length = 1000)
    private String attributeDescription;

    /** 논리 데이터 유형 (문자·숫자·일시 등 자유 입력). DBMS 타입이 아니다 */
    @Column(name = "DATA_TYPE_NM")
    private String dataType;

    @Column(name = "ESNTL_YN")
    private Boolean isRequired;

    @Column(name = "SORT_ORD")
    private Integer sortOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "STD_TRM_SN")
    private StandardTerm standardTerm;
}
