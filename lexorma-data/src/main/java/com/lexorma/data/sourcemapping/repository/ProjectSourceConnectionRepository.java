package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.sourcemapping.domain.entity.ProjectSourceConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectSourceConnectionRepository extends JpaRepository<ProjectSourceConnection, Long> {

    Optional<ProjectSourceConnection> findByProjectId(Long projectId);

    /** 기준 연결 ID 만 읽는다 (엔티티를 영속성 컨텍스트에 올리지 않아 뒤의 잠금 조회가 최신 행을 돌려준다) */
    @Query("select p.connectionId from ProjectSourceConnection p where p.projectId = :projectId")
    Optional<Long> findConnectionId(@Param("projectId") Long projectId);

    /** 매핑 변경의 직렬화 : 프로젝트의 기준 연결 행을 배타 잠금으로 읽는다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectSourceConnection p where p.projectId = :projectId")
    Optional<ProjectSourceConnection> findForUpdate(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from ProjectSourceConnection p where p.projectId = :projectId and p.connectionId = :connectionId")
    int deleteByConnection(@Param("projectId") Long projectId, @Param("connectionId") Long connectionId);
}
