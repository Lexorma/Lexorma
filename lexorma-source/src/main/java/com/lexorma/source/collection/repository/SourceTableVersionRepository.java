package com.lexorma.source.collection.repository;

import com.lexorma.source.collection.domain.entity.SourceTableVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SourceTableVersionRepository extends JpaRepository<SourceTableVersion, Long> {

    /** 반영 순서 R 의 한 스키마의 테이블 : 테이블마다 R 이하의 마지막 버전 (REMOVED 포함, 부르는 쪽이 거른다) */
    @Query("select v from SourceTableVersion v where v.connectionId = :connectionId and v.schemaId = :schemaId and v.reflectedOrder = (select max(v2.reflectedOrder)"
            + " from SourceTableVersion v2 where v2.tableId = v.tableId and v2.reflectedOrder <= :order) order by v.compareName")
    List<SourceTableVersion> at(@Param("connectionId") Long connectionId, @Param("schemaId") Long schemaId, @Param("order") Integer order);

    /** 반영 순서 R 의 연결 전체 테이블 */
    @Query("select v from SourceTableVersion v where v.connectionId = :connectionId and v.reflectedOrder = (select max(v2.reflectedOrder)"
            + " from SourceTableVersion v2 where v2.tableId = v.tableId and v2.reflectedOrder <= :order) order by v.pathKey, v.compareName")
    List<SourceTableVersion> at(@Param("connectionId") Long connectionId, @Param("order") Integer order);

    Optional<SourceTableVersion> findFirstByTableIdAndReflectedOrderLessThanEqualOrderByReflectedOrderDesc(Long tableId, Integer order);

    List<SourceTableVersion> findAllByTableIdOrderByReflectedOrderAsc(Long tableId);

    /** 스키마들의 테이블마다 마지막 버전의 요약 : 테이블 ID, 버전 ID, 해시, 변경 유형, 형식 번호 (내용은 읽지 않는다) */
    @Query("select v.tableId, v.id, v.contentHash, v.changeType, v.formatVersion from SourceTableVersion v where v.connectionId = :connectionId and v.schemaId in :schemaIds"
            + " and v.reflectedOrder = (select max(v2.reflectedOrder) from SourceTableVersion v2 where v2.tableId = v.tableId)")
    List<Object[]> latest(@Param("connectionId") Long connectionId, @Param("schemaIds") Collection<Long> schemaIds);

    @Query("select v.id, v.content from SourceTableVersion v where v.id in :ids")
    List<Object[]> contents(@Param("ids") Collection<Long> ids);

    long countByExecutionId(Long executionId);
}
