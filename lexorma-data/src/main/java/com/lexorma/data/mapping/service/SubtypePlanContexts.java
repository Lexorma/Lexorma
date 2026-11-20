package com.lexorma.data.mapping.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import com.lexorma.data.mapping.repository.SubtypeIdentifierColumnMappingRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** {@link SubtypePlanContext} 를 만든다 : 물리 생성과 상속 PK 매핑 조회가 같은 일괄 조회·판정을 쓴다 (M-7 3차) */
@Component
@RequiredArgsConstructor
class SubtypePlanContexts {

    private final LogicalEntityRepository entities;
    private final EntityTableMappingRepository entityMappings;
    private final AttributeColumnMappingRepository attributeMappings;
    private final SubtypeIdentifierColumnMappingRepository subtypeKeys;
    private final TableRelationRepository relations;

    /** known·mappings 는 호출한 쪽이 이미 읽은 것 (함께 채워진다) */
    SubtypePlanContext create(Long projectId, LogicalHierarchy hierarchy, DbType db, Map<Long, LogicalEntity> known,
                              Map<Long, Optional<EntityTableMapping>> mappings, Map<Long, LogicalSubtypeGroup> groups) {
        return new SubtypePlanContext(hierarchy, db, new SubtypePlanContext.Loaders(
                ids -> entities.findAllByIds(ids, projectId),
                entityMappings::findAllByEntityIds,
                attributeMappings::findAllByAttributeIds,
                subtypeKeys::findAllByMappingIds,
                relations::findAllBySourceTableIds), known, mappings, groups);
    }

    SubtypePlanContext create(Long projectId, LogicalHierarchy hierarchy, DbType db) {
        return create(projectId, hierarchy, db, new HashMap<>(), new HashMap<>(), Map.of());
    }
}
