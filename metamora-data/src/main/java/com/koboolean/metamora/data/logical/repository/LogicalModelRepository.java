package com.koboolean.metamora.data.logical.repository;

import com.koboolean.metamora.data.logical.domain.entity.LogicalModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LogicalModelRepository extends JpaRepository<LogicalModel, Long> {

    List<LogicalModel> findAllByProjectIdOrderByModelNameAscIdAsc(Long projectId);

    Optional<LogicalModel> findByIdAndProjectId(Long id, Long projectId);

    /** 모델 행 잠금 : 한 모델 아래의 변경(엔터티·속성·식별자·관계)을 직렬화해 이름 중복·주 식별자·삭제 검사가 동시 요청에도 맞게 한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from LogicalModel m where m.id = :id and m.projectId = :projectId")
    Optional<LogicalModel> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByProjectIdAndModelNameIgnoreCaseAndIdNot(Long projectId, String modelName, Long id);
}
