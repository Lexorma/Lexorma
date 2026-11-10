package com.koboolean.metamora.data.physical.repository;

import com.koboolean.metamora.data.physical.domain.entity.TableIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TableIndexRepository extends JpaRepository<TableIndex, Long> {

    Optional<TableIndex> findByIdAndProjectId(Long id, Long projectId);

    List<TableIndex> findAllByProjectIdAndTableInfo_Id(Long projectId, Long tableId);

    @Query("select distinct i from TableIndex i join i.columns c where c.column.id in :ids")
    List<TableIndex> findAllUsingColumns(@Param("ids") Collection<Long> columnIds);
}
