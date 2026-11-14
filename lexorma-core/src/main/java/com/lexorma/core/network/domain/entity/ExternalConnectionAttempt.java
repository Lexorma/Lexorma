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
 * 외부 연결 시도 (SEC-1, V4) : 판정(허용·차단)을 연결 전에 남기고, 허용된 연결은 결과(연결됨·실패·연결 시점 차단)로 고친다.
 * 비밀번호·인증 정보·URL 은 남기지 않는다 (유형·호스트·포트·검증한 주소·규칙·결과·사유만). 수행자·시각은 감사 컬럼이다.
 * 프로젝트 ID 를 두지 않는다 : 보안 감사라 프로젝트를 지워도 남는다.
 */
@Entity
@Table(name = "TB_EXTRL_CNTN_HSTRY")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalConnectionAttempt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_EXTRL_CNTN_HSTRY")
    @SequenceGenerator(name = "SQ_EXTRL_CNTN_HSTRY", sequenceName = "SQ_EXTRL_CNTN_HSTRY", allocationSize = 50)
    @Column(name = "EXTRL_CNTN_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "CNTN_TYPE_CD", nullable = false, length = 20)
    private ConnectionType connectionType;

    @Column(name = "HOST_NM", nullable = false)
    private String host;

    @Column(name = "PORT_NO", nullable = false)
    private Integer port;

    /** 판정 때 풀어 검증한 주소 (쉼표) */
    @Column(name = "CNTN_ADDR_CN", length = 1000)
    private String addresses;

    @Column(name = "EXTRL_CNTN_RULE_SN")
    private Long ruleId;

    /** ALLOWED · DENIED · CONNECTED · FAILED · BLOCKED */
    @Column(name = "RSLT_CD", nullable = false, length = 20)
    private String result;

    @Column(name = "RSN_CD", length = 20)
    private String reason;

    /** USER · ASYNC · SCHEDULED (실행 컨텍스트가 없으면 비어 있다) */
    @Column(name = "EXCN_TYPE_CD", length = 20)
    private String executionType;

    @Column(name = "DMND_ID")
    private String requestId;

    public Audit audit() {
        return toAudit();
    }
}
