package com.lexorma.source.collection.repository;

import com.lexorma.source.collection.domain.entity.SourceCollectionTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SourceCollectionTargetRepository extends JpaRepository<SourceCollectionTarget, Long> {

    List<SourceCollectionTarget> findAllByExecutionIdOrderByPathKeyAsc(Long executionId);

    /** 연결에서 지금까지 완전하게 수집한 적이 있는 이름 공간 (반영된 실행만) */
    @Query("select distinct t.pathKey from SourceCollectionTarget t, SourceCollectionExecution e where e.id = t.executionId and e.connectionId = :connectionId"
            + " and e.status = com.lexorma.source.collection.domain.enums.CollectionStatus.COMPLETED and t.result = com.lexorma.source.collection.domain.enums.TargetResult.COMPLETED")
    List<String> observedPaths(@Param("connectionId") Long connectionId);

    /** 이름 공간별 마지막 확인 : 반영 순서 R 까지 그 이름 공간을 완전하게 수집한 마지막 실행의 반영 순서 */
    @Query("select t.pathKey, max(e.reflectedOrder) from SourceCollectionTarget t, SourceCollectionExecution e where e.id = t.executionId and e.connectionId = :connectionId"
            + " and e.status = com.lexorma.source.collection.domain.enums.CollectionStatus.COMPLETED and t.result = com.lexorma.source.collection.domain.enums.TargetResult.COMPLETED"
            + " and e.reflectedOrder <= :order group by t.pathKey")
    List<Object[]> lastConfirmed(@Param("connectionId") Long connectionId, @Param("order") Integer order);
}
