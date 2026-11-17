package com.lexorma.data.mcp.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** MCP 호출 감사 (DEC-31, 추가만 한다) : 도구·메서드 이름, 결과, 일시만. 입력값·응답·토큰 원문은 남기지 않는다 */
@Entity
@Table(name = "TB_MCP_CALL_HSTRY")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpCallHistory extends BaseEntity {

    public enum Result { OK, DENIED, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_MCP_CALL_HSTRY")
    @SequenceGenerator(name = "SQ_MCP_CALL_HSTRY", sequenceName = "SQ_MCP_CALL_HSTRY", allocationSize = 50)
    @Column(name = "MCP_CALL_HSTRY_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "MCP_TKN_SN", nullable = false)
    private Long tokenId;

    @Column(name = "TOOL_NM", length = 100, nullable = false)
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "RSLT_CD", length = 20, nullable = false)
    private Result result;

    @Column(name = "CALL_DT", nullable = false)
    private LocalDateTime calledAt;
}
