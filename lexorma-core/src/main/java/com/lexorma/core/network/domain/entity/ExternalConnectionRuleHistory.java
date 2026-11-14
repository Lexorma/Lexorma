package com.lexorma.core.network.domain.entity;

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
 * 허용 규칙 변경 이력 (SEC-1, V4) : 등록·수정·삭제·활성화·비활성화. 규칙 변경과 같은 트랜잭션이라 변경이 커밋되면 이력도 남는다 (규칙을 지워도 이력은 남는다 — 값만 둔다).
 * 수행자·시각은 감사 컬럼이다.
 */
@Entity
@Table(name = "TB_EXTRL_CNTN_RULE_HSTRY")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExternalConnectionRuleHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_EXTRL_CNTN_RULE_HSTRY")
    @SequenceGenerator(name = "SQ_EXTRL_CNTN_RULE_HSTRY", sequenceName = "SQ_EXTRL_CNTN_RULE_HSTRY", allocationSize = 50)
    @Column(name = "EXTRL_CNTN_RULE_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "EXTRL_CNTN_RULE_SN", nullable = false)
    private Long ruleId;

    /** CREATE · UPDATE · DELETE · ENABLE · DISABLE */
    @Column(name = "CHG_TYPE_CD", nullable = false, length = 20)
    private String changeType;

    @Column(name = "CHG_BFR_CN", columnDefinition = "TEXT")
    private String before;

    @Column(name = "CHG_AFTR_CN", columnDefinition = "TEXT")
    private String after;

    @Column(name = "DMND_ID")
    private String requestId;

    public Audit audit() {
        return toAudit();
    }
}
