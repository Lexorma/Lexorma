package com.lexorma.core.ai.repository;

import com.lexorma.core.ai.domain.entity.ProjectAiSetting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectAiSettingRepository extends JpaRepository<ProjectAiSetting, Long> {

    Optional<ProjectAiSetting> findByProjectId(Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ProjectAiSetting s where s.projectId = :projectId")
    Optional<ProjectAiSetting> lockByProjectId(@Param("projectId") Long projectId);

    boolean existsByProviderId(Long providerId);

    /** 프로젝트 영구 삭제 */
    @Modifying
    @Query("delete from ProjectAiSetting s where s.projectId = :projectId")
    int deleteAllByProjectId(@Param("projectId") Long projectId);
}
