package com.koboolean.metamora.data.physical.repository;

import com.koboolean.metamora.data.physical.domain.entity.TableConstraint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TableConstraintRepository extends JpaRepository<TableConstraint, Long> {

    Optional<TableConstraint> findByIdAndProjectId(Long id, Long projectId);

    List<TableConstraint> findAllByProjectIdAndTableInfo_Id(Long projectId, Long tableId);

    @Query("select distinct t from TableConstraint t join t.columns c where c.column.id in :ids")
    List<TableConstraint> findAllUsingColumns(@Param("ids") Collection<Long> columnIds);
}
