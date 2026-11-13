package com.lexorma.source.collection.repository;

import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.CollectionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface SourceCollectionExecutionRepository extends JpaRepository<SourceCollectionExecution, Long> {

    Optional<SourceCollectionExecution> findByJobId(String jobId);

    Optional<SourceCollectionExecution> findByIdAndProjectId(Long id, Long projectId);

    /** 연결에서 진행 중인 실행 (UNIQUE 라 많아야 하나) */
    Optional<SourceCollectionExecution> findByProgressConnectionId(Long connectionId);

    Page<SourceCollectionExecution> findAllByProjectIdAndConnectionId(Long projectId, Long connectionId, Pageable pageable);

    @Query("select max(e.reflectedOrder) from SourceCollectionExecution e where e.connectionId = :connectionId")
    Integer maxReflectedOrder(@Param("connectionId") Long connectionId);

    boolean existsByConnectionIdAndCollectionTypeAndStatus(Long connectionId, CollectionType type, CollectionStatus status);

    Optional<SourceCollectionExecution> findByConnectionIdAndReflectedOrder(Long connectionId, Integer reflectedOrder);

    /** 이 실행 전에 반영된 마지막 같은 종류의 실행 (수집 결과 급감 진단) */
    Optional<SourceCollectionExecution> findFirstByConnectionIdAndStatusAndCollectionTypeAndIdNotOrderByReflectedOrderDesc(Long connectionId, CollectionStatus status,
                                                                                                        CollectionType type, Long id);

    /** 시각 → 그때까지 반영된 마지막 실행 */
    Optional<SourceCollectionExecution> findFirstByConnectionIdAndStatusAndReflectedAtLessThanEqualOrderByReflectedOrderDesc(Long connectionId, CollectionStatus status,
                                                                                                                        LocalDateTime at);

    /**
     * 멈춘 실행을 실패로 정리한다 : 읽은 뒤에 살아 있음 확인이 다시 들어왔으면(시각이 바뀌었으면) 정리하지 않는다.
     * 부르는 쪽이 연결 행을 잠근 상태에서 부른다
     */
    @Modifying(flushAutomatically = true)
    @Query("update SourceCollectionExecution e set e.status = com.lexorma.source.collection.domain.enums.CollectionStatus.FAILED, e.progressConnectionId = null,"
            + " e.endedAt = :now, e.errorCode = :code where e.id = :id and e.status in :statuses and e.lastIdentifiedAt = :seen")
    int abandon(@Param("id") Long id, @Param("seen") LocalDateTime seen, @Param("now") LocalDateTime now, @Param("code") String code,
                @Param("statuses") Collection<CollectionStatus> statuses);

    /** 살아 있음 확인 : 이 서버가 맡은 진행 중인 실행만 */
    @Modifying
    @Query("update SourceCollectionExecution e set e.lastIdentifiedAt = :now where e.id = :id and e.serverId = :serverId and e.status in :statuses")
    int touch(@Param("id") Long id, @Param("serverId") String serverId, @Param("now") LocalDateTime now, @Param("statuses") Collection<CollectionStatus> statuses);
}
