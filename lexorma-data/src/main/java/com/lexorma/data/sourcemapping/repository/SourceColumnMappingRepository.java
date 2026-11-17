package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.sourcemapping.domain.entity.SourceColumnMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceColumnMappingRepository extends JpaRepository<SourceColumnMapping, Long> {

    List<SourceColumnMapping> findAllByProjectIdAndConnectionId(Long projectId, Long connectionId);

    List<SourceColumnMapping> findAllByProjectIdAndTableMappingIdOrderByIdAsc(Long projectId, Long tableMappingId);

    Optional<SourceColumnMapping> findByIdAndProjectId(Long id, Long projectId);

    /** 모델 컬럼이 속한 TABLE (컬럼 매핑의 유효성 : 매핑된 모델 TABLE 의 컬럼인지). [컬럼 ID, TABLE ID, 컬럼 이름] */
    @Query("select c.id, c.tableInfo.id, c.columnName from ColumnInfo c where c.projectId = :projectId and c.id in :ids")
    List<Object[]> modelColumns(@Param("projectId") Long projectId, @Param("ids") List<Long> ids);

    /** 모델 TABLE 들의 컬럼 [컬럼 ID, TABLE ID, 컬럼 이름, 승인 여부] : 이름 변경 후보의 근거·표시 */
    @Query("select c.id, c.tableInfo.id, c.columnName, c.isApproval from ColumnInfo c where c.projectId = :projectId and c.tableInfo.id in :tableIds")
    List<Object[]> columnsOfTables(@Param("projectId") Long projectId, @Param("tableIds") List<Long> tableIds);
}
