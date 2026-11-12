package com.lexorma.source.connection.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.enums.DatabaseNameType;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;
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
import lombok.Setter;
import lombok.ToString;

/**
 * 프로젝트의 외부 DB 연결 (테이블 이름의 "출처 접속"). 한 프로젝트가 여러 개를 가질 수 있다 (개발·운영·연계 DB).
 * <p>
 * Lexorma 가 원본 DB 에 직접(JDBC) 접속해 메타데이터를 읽는 데 필요한 것을 저장한다 : DBMS, 호스트, 포트, 데이터베이스명, 계정, 암호화한 비밀번호, 허용한 옵션.
 * <b>JDBC URL 은 저장하지 않는다</b> — 실행할 때 검증한 항목으로 서버가 만든다 (SourceJdbcSettings). 별도의 수집 서버를 거치지 않는다.
 * 원본 카탈로그(SourceSchema …)는 이 연결에 속하고, 연결을 지우면 함께 지워진다. 물리·논리 모델과는 이어져 있지 않다.
 */
@Entity
@Table(name = "TB_SRC_CNTN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(callSuper = false)
public class SourceConnection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SRC_CNTN")
    @SequenceGenerator(name = "SQ_SRC_CNTN", sequenceName = "SQ_SRC_CNTN", allocationSize = 50)
    @Column(name = "SRC_CNTN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 표시명 (프로젝트 안에서 하나뿐) */
    @Column(name = "CNTN_NM", length = 100, nullable = false)
    private String name;

    /** 원본 DBMS : JDBC 드라이버·URL 모양·식별자 비교 규칙이 여기에 따른다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "DB_TYPE_CD", length = 20, nullable = false)
    private DbType dbType;

    @Column(name = "HOST_NM", nullable = false)
    private String host;

    @Column(name = "PORT_NO", nullable = false)
    private Integer port;

    /** 데이터베이스명 (Oracle 은 서비스명 또는 SID — databaseNameType) */
    @Column(name = "DB_NM", nullable = false)
    private String databaseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "DB_NM_TYPE_CD", length = 20, nullable = false)
    private DatabaseNameType databaseNameType;

    /** 원본 DB 계정 (메타데이터를 읽을 수 있는 읽기 전용 계정을 권한다) */
    @Column(name = "CNTN_USER_NM", nullable = false)
    private String username;

    /** 암호화한 비밀번호 (SecretCipher 의 암호문, 자리 = source-connection / 프로젝트 / 이 연결의 ID / password). 응답·로그에 내지 않는다 */
    @ToString.Exclude
    @Column(name = "CNTN_PSWD_CN", columnDefinition = "TEXT")
    private String encryptedPassword;

    /** 허용 목록에 있는 연결 옵션만 (JSON 객체). 비밀값을 담는 옵션은 받지 않는다 */
    @Column(name = "CNTN_OPTN_CN", columnDefinition = "TEXT")
    private String options;

    @Enumerated(EnumType.STRING)
    @Column(name = "IDNTFR_CMPR_CD", length = 20, nullable = false)
    private IdentifierComparison identifierComparison;

    @Column(name = "CNTN_EXPLN", length = 1000)
    private String description;

    /** 사용 여부. 끄면 카탈로그는 그대로 두고 이 연결로 도구를 실행(접속)하는 것만 막는다 */
    @Column(name = "ACTVTN_YN", nullable = false)
    private Boolean enabled;
}
