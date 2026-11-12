package com.lexorma.source.collection.repository;

import com.lexorma.source.collection.domain.entity.SourceSchemaVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SourceSchemaVersionRepository extends JpaRepository<SourceSchemaVersion, Long> {

    /** 반영 순서 R 의 스키마 : 스키마마다 R 이하의 마지막 버전 (REMOVED 포함, 부르는 쪽이 거른다) */
    @Query("select v from SourceSchemaVersion v where v.connectionId = :connectionId and v.reflectedOrder = (select max(v2.reflectedOrder) from SourceSchemaVersion v2"
            + " where v2.schemaId = v.schemaId and v2.reflectedOrder <= :order) order by v.pathKey")
    List<SourceSchemaVersion> at(@Param("connectionId") Long connectionId, @Param("order") Integer order);

    long countByExecutionId(Long executionId);
}
