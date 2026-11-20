package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalSubjectAreaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogicalSubjectAreaEntityRepository extends JpaRepository<LogicalSubjectAreaEntity, Long> {

    Optional<LogicalSubjectAreaEntity> findByEntity_Id(Long entityId);

    /** 일괄 분류 : 고른 엔터티들의 지금 소속을 한 번에 (잠금은 모델 행 잠금이 한다) */
    @Query("select m from LogicalSubjectAreaEntity m where m.entity.id in :entityIds")
    List<LogicalSubjectAreaEntity> findAllByEntityIds(@Param("entityIds") Collection<Long> entityIds);

    /** 엔터티 ID → 영역 ID (목록 페이지의 엔터티만) */
    @Query("select m.entity.id, m.subjectArea.id from LogicalSubjectAreaEntity m where m.entity.id in :entityIds")
    List<Object[]> findAreaIds(@Param("entityIds") Collection<Long> entityIds);

    /** 영역별 직접 소속 엔터티 수 (영역마다 따로 세지 않는다) */
    @Query("select m.subjectArea.id, count(m) from LogicalSubjectAreaEntity m where m.modelId = :modelId group by m.subjectArea.id")
    List<Object[]> countByArea(@Param("modelId") Long modelId);

    boolean existsBySubjectArea_Id(Long subjectAreaId);

    long countBySubjectArea_Id(Long subjectAreaId);
}
