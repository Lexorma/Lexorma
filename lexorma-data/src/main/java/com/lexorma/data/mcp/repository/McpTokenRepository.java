package com.lexorma.data.mcp.repository;

import com.lexorma.data.mcp.domain.entity.McpToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpTokenRepository extends JpaRepository<McpToken, Long> {

    Optional<McpToken> findByHash(String hash);

    Optional<McpToken> findByIdAndProjectId(Long id, Long projectId);

    List<McpToken> findAllByProjectIdOrderByIdDesc(Long projectId);
}
