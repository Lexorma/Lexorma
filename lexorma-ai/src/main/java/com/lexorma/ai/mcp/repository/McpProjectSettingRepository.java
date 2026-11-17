package com.lexorma.ai.mcp.repository;

import com.lexorma.ai.mcp.domain.entity.McpProjectSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpProjectSettingRepository extends JpaRepository<McpProjectSetting, Long> {

    Optional<McpProjectSetting> findByProjectId(Long projectId);
}
