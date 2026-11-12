package com.lexorma.source.catalog.repository;

import com.lexorma.source.catalog.domain.entity.SourceRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceRelationRepository extends JpaRepository<SourceRelation, Long> {

    /** 이 테이블이 참조하는 관계 (나가는 FK) */
    List<SourceRelation> findAllBySourceTable_IdOrderByIdAsc(Long tableId);

    /** 이 테이블을 참조하는 관계 (들어오는 FK) */
    List<SourceRelation> findAllByReferencedTable_IdOrderByIdAsc(Long tableId);
}
