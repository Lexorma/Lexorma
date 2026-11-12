package com.lexorma.source.connection.repository;

import com.lexorma.source.connection.domain.entity.SourceConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceConnectionRepository extends JpaRepository<SourceConnection, Long> {

    /** 다른 프로젝트의 연결은 ID 를 알아도 없는 것과 같다 */
    Optional<SourceConnection> findByIdAndProjectId(Long id, Long projectId);

    /** 연결 행 잠금 : 연결 삭제와 그 연결로의 카탈로그 적재가 겹치지 않게 한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SourceConnection c where c.id = :id and c.projectId = :projectId")
    Optional<SourceConnection> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    List<SourceConnection> findAllByProjectIdOrderByNameAscIdAsc(Long projectId);

    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);

    boolean existsByProjectIdAndNameIgnoreCaseAndIdNot(Long projectId, String name, Long id);
}
