package com.lexorma.data.mcp.repository;

import com.lexorma.data.mcp.domain.entity.McpCallHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpCallHistoryRepository extends JpaRepository<McpCallHistory, Long> {

    List<McpCallHistory> findAllByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);
}
