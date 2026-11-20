package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalRelationshipAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogicalRelationshipAttributeRepository extends JpaRepository<LogicalRelationshipAttribute, Long> {

    /** 관계들의 속성 짝 (관계 ID → 순서). 속성은 함께 읽는다 (이름·소속 엔터티 확인) */
    @Query("select p from LogicalRelationshipAttribute p join fetch p.parentAttribute pa join fetch p.childAttribute ca where p.relationship.id in :ids order by p.relationship.id, p.sortOrder, p.id")
    List<LogicalRelationshipAttribute> findAllByRelationshipIds(@Param("ids") Collection<Long> ids);

    /** 속성을 쓰는 짝 하나 (부모·자식 어느 쪽이든) : 속성 삭제를 막을 때 */
    @Query("select p from LogicalRelationshipAttribute p join fetch p.relationship where p.parentAttribute.id = :attributeId or p.childAttribute.id = :attributeId order by p.id")
    List<LogicalRelationshipAttribute> findUsing(@Param("attributeId") Long attributeId);

    default Optional<LogicalRelationshipAttribute> findFirstUsing(Long attributeId) {
        return findUsing(attributeId).stream().findFirst();
    }

    /** 고른 엔터티가 부모(출발)인 관계의 속성 짝 (상속 변경으로 부모의 유효 주 식별자가 바뀌는지 판정 — M-6 후속 5차) */
    @Query("select p from LogicalRelationshipAttribute p join fetch p.relationship r join fetch r.sourceEntity join fetch r.targetEntity join fetch p.parentAttribute"
            + " where r.sourceEntity.id in :entityIds order by r.id, p.sortOrder, p.id")
    List<LogicalRelationshipAttribute> findAllBySourceEntityIds(@Param("entityIds") Collection<Long> entityIds);
}
