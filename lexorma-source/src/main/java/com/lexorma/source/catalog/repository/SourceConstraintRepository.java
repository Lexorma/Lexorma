package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceConstraint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceConstraintRepository extends JpaRepository<SourceConstraint, Long> {

    List<SourceConstraint> findAllBySourceTable_IdOrderByIdAsc(Long tableId);
}
