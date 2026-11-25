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
    private final com.lexorma.data.column.repository.ColumnInfoRepository columns;

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

    /**
     * 가벼운 판정 (M-7 4차 — 매핑 목록·상세·정합성 비교) : 같은 판정을 하되 테이블 PK 컬럼과 나가는 FK 의 컬럼 쌍은 투영으로 읽는다
     * (테이블 컬럼 전체·관계 엔티티를 적재하지 않는다). 계획 토큰을 만들지 않는 조회 전용 — 물리 생성은 {@link #create} 를 쓴다
     */
    SubtypePlanContext light(Long projectId, LogicalHierarchy hierarchy, DbType db) {
        return new SubtypePlanContext(hierarchy, db, new SubtypePlanContext.Loaders(
                ids -> entities.findAllByIds(ids, projectId),
                entityMappings::findAllByEntityIds,
                attributeMappings::findAllByAttributeIds,
                subtypeKeys::findAllByMappingIds,
                null, relations::findForeignKeyPairs, columns::findPrimaryKeyColumns), new HashMap<>(), new HashMap<>(), Map.of());
    }

    /**
     * {@link #light} 의 모델 전체용 (정합성 비교 — 모델의 매핑된 서브타입 전부를 한 번에 판정). 엔터티 수천 개의 긴 IN 목록은 DB 가 행마다 목록을 훑어 느려서
     * (측정 — 4차 31.9) 엔터티·매핑·상속 PK 매핑·FK 쌍·PK 를 모델 조건으로 한 번씩 읽고 요청한 ID 만 남긴다. 루트 주 식별자 직접 매핑은 루트 수만큼이라 그대로 IN
     */
    SubtypePlanContext lightForModel(Long projectId, Long modelId, LogicalHierarchy hierarchy, DbType db) {
        return new SubtypePlanContext(hierarchy, db, new SubtypePlanContext.Loaders(
                ids -> only(ids, entities.findAllByModel_IdOrderByEntityNameAscIdAsc(modelId), LogicalEntity::getId),
                ids -> only(ids, entityMappings.findAllByModelId(projectId, modelId), m -> m.getEntity().getId()),
                attributeMappings::findAllByAttributeIds,
                ids -> only(ids, subtypeKeys.findAllByModelId(projectId, modelId), k -> k.getMapping().getId()),
                null,
                ids -> only(ids, relations.findForeignKeyPairsByModel(projectId, modelId), row -> (Long) row[3]),
                ids -> only(ids, columns.findPrimaryKeyColumnsByModel(projectId, modelId), row -> (Long) row[0])), new HashMap<>(), new HashMap<>(), Map.of());
    }

    private static <T> java.util.List<T> only(java.util.Collection<Long> ids, java.util.List<T> rows, java.util.function.Function<T, Long> key) {
        java.util.Set<Long> wanted = new java.util.HashSet<>(ids);
        return rows.stream().filter(row -> wanted.contains(key.apply(row))).toList();
    }
}
