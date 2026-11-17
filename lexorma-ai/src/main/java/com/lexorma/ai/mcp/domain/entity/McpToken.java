package com.lexorma.ai.mcp.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 프로젝트 범위 MCP 접근 토큰 (DEC-31, docs/completed/embedded-mcp-dec31.md). 원문은 발급 응답에 한 번만 나가고 여기에는 SHA-256 해시와 앞부분만 남는다.
 * 토큰에 역할을 담지 않는다 : 쓸 때마다 발급한 계정의 지금 상태·멤버십·역할을 DB 로 다시 확인한다.
 */
@Entity
@Table(name = "TB_MCP_TKN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_MCP_TKN")
    @SequenceGenerator(name = "SQ_MCP_TKN", sequenceName = "SQ_MCP_TKN", allocationSize = 50)
    @Column(name = "MCP_TKN_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @Column(name = "ACNT_SN", nullable = false)
    private Long accountId;

    @Column(name = "TKN_NM", length = 100, nullable = false)
    private String name;

    @Column(name = "HASH_VL", nullable = false)
    private String hash;

    @Column(name = "TKN_PFX_NM", length = 20, nullable = false)
    private String prefix;

    @Column(name = "EXPRY_DT", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "DSCD_DT")
    private LocalDateTime revokedAt;

    @Column(name = "LAST_USE_DT")
    private LocalDateTime lastUsedAt;

    /** 발급한 사용자 (감사 컬럼의 등록자) */
    public String issuedBy() {
        return toAudit().createdBy();
    }

    public LocalDateTime issuedAt() {
        return toAudit().created();
    }
}
