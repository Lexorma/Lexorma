package com.lexorma.core.network.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.network.ConnectionType;
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

/**
 * 외부 연결 허용 규칙 (SEC-1, V4). 시스템 관리자(ADMIN)만 관리한다. 한 규칙이 연결 유형·포트·(호스트)·대역을 모두 만족해야 연결을 허용한다.
 * 규칙이 없으면 연결하지 않는다 (기본 차단). 변경은 {@link ExternalConnectionRuleHistory} 에 같은 트랜잭션으로 남는다.
 */
@Entity
@Table(name = "TB_EXTRL_CNTN_RULE")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalConnectionRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_EXTRL_CNTN_RULE")
    @SequenceGenerator(name = "SQ_EXTRL_CNTN_RULE", sequenceName = "SQ_EXTRL_CNTN_RULE", allocationSize = 50)
    @Column(name = "EXTRL_CNTN_RULE_SN", nullable = false, unique = true)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "CNTN_TYPE_CD", nullable = false, length = 20)
    private ConnectionType connectionType;

    /** 호스트명 또는 숫자 주소 (비교용 표기 : 소문자). 비우면 대역만 본다 */
    @Column(name = "HOST_NM")
    private String host;

    /** 허용 대역 (CIDR, 호스트 부분을 0 으로 맞춘 표기) */
    @Column(name = "ALWNC_ADDR", nullable = false)
    private String allowedAddress;

    @Column(name = "PORT_NO", nullable = false)
    private Integer port;

    @Column(name = "ACTVTN_YN", nullable = false)
    private Boolean enabled;

    @Column(name = "RULE_EXPLN", length = 1000)
    private String description;

    public Audit audit() {
        return toAudit();
    }
}
