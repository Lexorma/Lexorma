package com.lexorma.data.erd.repository;

import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.service.PhysicalCardinality;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** 양 끝이 모두 주어진 테이블인 관계 (M-2 개선 2차, relationScope INNER) */
    List<PhysicalModelDto.Relation> findInnerRelations(Long projectId, Collection<Long> tableIds);

    /**
     * 주어진 테이블별 관계 끝 수 : 원본인 관계 수 + 대상인 관계 수 (관계 행 = FK 제약조건 1개, 컬럼 쌍은 세지 않는다. 자기 참조는 2). 0 이면 키가 없다.
     * 범위 밖 관계 수 = 이 값 − 양 끝이 범위 안인 관계의 끝 수 (서비스가 계산 — NOT IN 목록은 H2 에서 행마다 목록 전체를 비교해 느리다)
     */
    Map<Long, Long> countRelationEnds(Long projectId, Collection<Long> tableIds);

    /** 주어진 테이블이 원본인 FK 관계(relationType FK)의 원본 컬럼 ID (중복 없음) */
    List<Long> findFkColumnIds(Long projectId, Collection<Long> tableIds);

    /** 테이블 하나가 원본 또는 대상인 관계 수 (자기 참조 1개) */
    long countTableRelations(Long projectId, Long tableId);

    /** 테이블 하나의 관계 한 페이지 (관계 ID 순) */
    List<PhysicalModelDto.Relation> findTableRelations(Long projectId, Long tableId, long offset, int limit);

    /** 테이블 찾기 : 범위 조회와 같은 검색 조건·정렬, 스키마명 포함 */
    List<ErdDto.TableHit> findTableHits(Long projectId, ErdDto.Search search, int limit);

    /** 카디널리티 판단용 컬럼 값 (테이블의 모든 컬럼) */
    List<PhysicalCardinality.ColumnFlags> findColumnFlags(Long projectId, Collection<Long> tableIds);

    /** 테이블 ID → UNIQUE 제약과 조건 없는 고유 인덱스(키가 모두 컬럼)의 컬럼 ID 집합. INCLUDE 컬럼은 키가 아니다 */
    Map<Long, List<Set<Long>>> findUniqueKeys(Long projectId, Collection<Long> tableIds);

    /** 주어진 테이블과 관계로 직접 연결된 테이블 ID (자신 포함 가능) */
    Set<Long> findLinkedTableIds(Long projectId, Collection<Long> tableIds);

    /** 프로젝트에 실제로 있는 테이블 ID 만 */
    Set<Long> findExistingTableIds(Long projectId, Collection<Long> tableIds);
}
