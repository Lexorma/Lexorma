package com.lexorma.data.mcp.repository;

import com.lexorma.data.mcp.domain.entity.McpProjectSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpProjectSettingRepository extends JpaRepository<McpProjectSetting, Long> {

    Optional<McpProjectSetting> findByProjectId(Long projectId);
}
