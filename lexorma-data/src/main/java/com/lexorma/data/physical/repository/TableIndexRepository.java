package com.lexorma.data.physical.repository;

import com.lexorma.data.physical.domain.entity.TableIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TableIndexRepository extends JpaRepository<TableIndex, Long>, JpaSpecificationExecutor<TableIndex> {

    Optional<TableIndex> findByIdAndProjectId(Long id, Long projectId);

    List<TableIndex> findAllByProjectIdAndTableInfo_Id(Long projectId, Long tableId);

    /** 컬럼 인덱스 동기화 : 여러 테이블의 인덱스를 한 번에 */
    List<TableIndex> findAllByProjectIdAndTableInfo_IdIn(Long projectId, Collection<Long> tableIds);

    @Query("select distinct i from TableIndex i join i.columns c where c.column.id in :ids")
    List<TableIndex> findAllUsingColumns(@Param("ids") Collection<Long> columnIds);
}
