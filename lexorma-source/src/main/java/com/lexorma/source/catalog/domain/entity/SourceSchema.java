package com.lexorma.source.catalog.domain.entity;

import com.lexorma.source.connection.domain.entity.SourceConnection;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 원본 카탈로그의 이름 공간 : 카탈로그(데이터베이스)와 스키마. DBMS 마다 둘 중 하나만 있거나 둘 다 있으므로 한 행에 함께 둔다
 * (PostgreSQL : 데이터베이스 + 스키마, Oracle : 스키마, MySQL·MariaDB : 데이터베이스 = 카탈로그).
 * pathKey 는 연결 안에서 이 이름 공간을 가리키는 비교용 경로다 : 없는 쪽이 있어도(NULL) 겹침을 막을 수 있게 NULL 이 아닌 한 값으로 만든다.
 */
@Entity
@Table(name = "TB_SRC_SCHM")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceSchema extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_SCHM")
    @SequenceGenerator(name = "SQ_SRC_SCHM", sequenceName = "SQ_SRC_SCHM", allocationSize = 50)
    @Column(name = "SRC_SCHM_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SRC_CNTN_SN", nullable = false)
    private SourceConnection connection;

    /** 원본이 준 이름 그대로 */
    @Column(name = "CTLG_NM")
    private String catalogName;

    @Column(name = "SCHM_NM")
    private String schemaName;

    @Column(name = "PATH_CN", length = 1000, nullable = false)
    private String pathKey;

    /** 마지막 수집에서 원본에 있었는지 (없어져도 지우지 않고 표시만 한다 : AI-1c) */
    @Column(name = "EXST_YN", nullable = false)
    private Boolean present;
}
