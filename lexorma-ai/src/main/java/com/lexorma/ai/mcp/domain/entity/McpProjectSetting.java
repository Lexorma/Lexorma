package com.lexorma.ai.mcp.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 프로젝트별 내장 MCP 사용 여부 (DEC-31). 행이 없으면 꺼져 있다. 운영 설정 lexorma.mcp.enabled 도 켜져 있어야 한다 */
@Entity
@Table(name = "TB_PRJ_MCP_STNG")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpProjectSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PRJ_MCP_STNG")
    @SequenceGenerator(name = "SQ_PRJ_MCP_STNG", sequenceName = "SQ_PRJ_MCP_STNG", allocationSize = 50)
    @Column(name = "PRJ_MCP_STNG_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "ACTVTN_YN", nullable = false)
    private Boolean enabled;
}
