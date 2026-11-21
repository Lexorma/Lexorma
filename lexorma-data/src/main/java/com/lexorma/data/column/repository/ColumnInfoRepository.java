package com.lexorma.data.column.repository;

import com.lexorma.data.column.domain.entity.ColumnInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ColumnInfoRepository extends JpaRepository<ColumnInfo, Long>, JpaSpecificationExecutor<ColumnInfo> {
    Page<ColumnInfo> findAllByProjectId(Long projectId, Pageable pageable);

    Page<ColumnInfo> findByProjectId(Long projectId, Pageable pageable);


    /** 같은 테이블 안의 같은 컬럼명 (컬럼 등록 중복 기준) */
    List<ColumnInfo> findAllByProjectIdAndTableInfo_IdAndColumnName(Long projectId, Long tableInfoId, String columnName);

    Page<ColumnInfo> findAllByProjectIdAndTableNameLike(Long projectId, String tableName, Pageable pageable);
    Page<ColumnInfo> findAllByProjectIdAndColumnNameLikeIgnoreCase(Long projectId, String columnName, Pageable pageable);

    Page<ColumnInfo> findAllByProjectIdAndColumnDescLike(Long projectId, String columnDesc, Pageable pageable);

    Page<ColumnInfo> findAllByProjectIdAndDataTypeLike(Long projectId, String dataType, Pageable pageable);

    ColumnInfo findByProjectIdAndId(Long projectId, Long id);

    List<ColumnInfo> findAllByProjectIdAndId(Long projectId, Long id);

    ColumnInfo findByIdAndProjectId(Long id, Long projectId);

    /** 스키마명 변경 : 참조 테이블명을 "스키마명.테이블명" 으로 적은 컬럼 (대문자 접두어) */
    List<ColumnInfo> findAllByProjectIdAndRefTableNameStartingWith(Long projectId, String prefix);

    /** 엑셀 업로드 : 청크에서 이미 등록된 (테이블 ID, 컬럼명) 조합을 한 번에 조회 → [tableInfoId, columnName] */
    @Query("select c.tableInfo.id, c.columnName from ColumnInfo c " +
           "where c.projectId = :projectId and c.tableInfo.id in :tableInfoIds and c.columnName in :columnNames")
    List<Object[]> findTableColumnKeys(@Param("projectId") Long projectId,
                                       @Param("tableInfoIds") Collection<Long> tableInfoIds,
                                       @Param("columnNames") Collection<String> columnNames);

    /** 관계 동기화 : 테이블들의 컬럼 */
    List<ColumnInfo> findAllByTableInfo_IdIn(Collection<Long> tableInfoIds);

    /** 관계 동기화 : 테이블들의 PK 컬럼 */
    List<ColumnInfo> findAllByTableInfo_IdInAndIsPkTrue(Collection<Long> tableInfoIds);

    /** 관계 동기화 : 참조 테이블명(대소문자·앞뒤 공백 무시)이 names 중 하나인 컬럼 */
    @Query("select c from ColumnInfo c where c.projectId = :projectId and upper(trim(c.refTableName)) in :names")
    List<ColumnInfo> findAllReferencing(@Param("projectId") Long projectId, @Param("names") Collection<String> names);

    /** 컬럼 행 잠금 : AI 설명 제안 승인이 업무 설명을 쓰기 전에 잡는다 (AI-5) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ColumnInfo c where c.id = :id and c.projectId = :projectId")
    Optional<ColumnInfo> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 업무 설명만 DB 에서 다시 읽는다 (스칼라 조회). 행은 {@link #findForUpdate} 로 먼저 잠근다 — TableInfoRepository.findBizDescription 과 같은 용도 */
    @Query("select c.bizDescription from ColumnInfo c where c.id = :id")
    String findBizDescription(@Param("id") Long id);

    /** 프로젝트의 테이블별 컬럼 수와 업무 설명이 없는 컬럼 수 [테이블 ID, 컬럼 수, 업무 설명 없는 수] (AI 설명 제안 대상 목록) — 업무 설명은 비면 null 로 저장한다 */
    @Query("select c.tableInfo.id, count(c), sum(case when c.bizDescription is null then 1 else 0 end) from ColumnInfo c where c.projectId = :projectId group by c.tableInfo.id")
    List<Object[]> countBizDescriptionsByTable(@Param("projectId") Long projectId);

    /** 같은 집계를 고른 테이블에만 (AI 설명 제안 대상 목록의 한 쪽) */
    @Query("select c.tableInfo.id, count(c), sum(case when c.bizDescription is null then 1 else 0 end) from ColumnInfo c"
            + " where c.projectId = :projectId and c.tableInfo.id in :tableIds group by c.tableInfo.id")
    List<Object[]> countBizDescriptionsByTables(@Param("projectId") Long projectId, @Param("tableIds") Collection<Long> tableIds);

    /** 여러 테이블의 PK 컬럼 (투영 — 테이블 ID·컬럼 ID·이름·순서, M-7 4차 상속 구조 정합성) */
    @org.springframework.data.jpa.repository.Query("select c.tableInfo.id, c.id, c.columnName, c.sortOrder from ColumnInfo c where c.tableInfo.id in :ids and c.isPk = true")
    java.util.List<Object[]> findPrimaryKeyColumns(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> tableIds);

    /** {@link #findPrimaryKeyColumns} 의 모델 단위 : 그 논리 모델의 엔터티에 매핑된 테이블의 PK 컬럼 (정합성 비교 — 긴 IN 목록 대신) */
    @org.springframework.data.jpa.repository.Query("select c.tableInfo.id, c.id, c.columnName, c.sortOrder from ColumnInfo c where c.isPk = true and c.tableInfo.id in "
            + "(select m.table.id from com.lexorma.data.mapping.domain.entity.EntityTableMapping m where m.projectId = :projectId and m.entity.model.id = :modelId)")
    java.util.List<Object[]> findPrimaryKeyColumnsByModel(@org.springframework.data.repository.query.Param("projectId") Long projectId,
                                                          @org.springframework.data.repository.query.Param("modelId") Long modelId);
}
