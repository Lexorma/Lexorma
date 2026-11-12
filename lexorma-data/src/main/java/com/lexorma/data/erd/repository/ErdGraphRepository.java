package com.lexorma.data.erd.repository;

import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 물리 ERD 일괄 조회 (QueryDSL DTO 프로젝션 : 테이블·컬럼·관계를 각각 쿼리 1번, N+1 없음) */
public interface ErdGraphRepository {

    long countTables(Long projectId, ErdDto.Search search);

    /** 스키마 → 테이블명 순 */
    List<ErdDto.Table> findTables(Long projectId, ErdDto.Search search, long offset, int limit);

    List<ErdDto.Table> findTablesByIds(Long projectId, Collection<Long> tableIds);

    List<ErdDto.Column> findColumns(Long projectId, Collection<Long> tableIds);

    /** 주어진 테이블이 원본 또는 대상인 관계 (컬럼 쌍은 columnOrder 순) */
    List<PhysicalModelDto.Relation> findRelations(Long projectId, Collection<Long> tableIds);

    /** 주어진 테이블과 관계로 직접 연결된 테이블 ID (자신 포함 가능) */
    Set<Long> findLinkedTableIds(Long projectId, Collection<Long> tableIds);

    /** 프로젝트에 실제로 있는 테이블 ID 만 */
    Set<Long> findExistingTableIds(Long projectId, Collection<Long> tableIds);
}
