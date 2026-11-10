package com.koboolean.metamora.core.project.repository;

import com.koboolean.metamora.core.project.domain.entity.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long>, ProjectRepositoryCustom {

    List<Project> findByIsActive(boolean isActive);

    List<Project> findAllByAccount_IdAndProjectMember_Account_Id(Long id, Long accountId);

    List<Project> findAllByProjectMember_Account_Id(Long accountId);

    /** 프로젝트 행 잠금 : 프로젝트당 1건만 만들어야 하는 데이터(기본 스키마 등)의 최초 생성을 직렬화한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") Long id);
}
