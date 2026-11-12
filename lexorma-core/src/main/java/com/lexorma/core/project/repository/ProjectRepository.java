package com.lexorma.core.project.repository;

import com.lexorma.core.project.domain.entity.Project;
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

    /** 활성 프로젝트 행 잠금 : 서로 다른 프로젝트의 동시 비활성화를 직렬화해 "마지막 활성 프로젝트" 판단이 어긋나지 않게 한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.isActive = true")
    List<Project> findActiveForUpdate();

    /** 복구 화면 목록 (id, 프로젝트명, 활성 여부, 관리자명, 수정일) : 비활성 프로젝트 포함. 수정일(BaseEntity)은 getter 가 없어 조회로 꺼낸다 */
    @Query("select p.id, p.projectName, p.isActive, a.name, p.updated from Project p left join p.account a order by p.id")
    List<Object[]> findRecoveryProjects();
}
