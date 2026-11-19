package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.logical.domain.entity.QLogicalRelationship;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 논리 모델 관리 목록 (M-6 후속 3차) : 검색·정렬·페이징과 속성/관계 수를 DB 에서 한다 (엔터티마다 따로 세지 않는다 — 페이지 엔터티를 묶어 센다).
 * 검색 조건은 논리 ERD 부분 조회(LogicalEntityRepository.ERD_SEARCH)와 같다 : 이름·설명, 소문자, '!' 이스케이프.
 */
@Repository
@RequiredArgsConstructor
public class LogicalModelQueryRepository {

    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QLogicalRelationship relationship = QLogicalRelationship.logicalRelationship;

    /** 정렬할 수 있는 필드 (목록에 없는 필드는 무시한다). 같은 값이면 엔터티 ID 순 — 페이지 사이에 행이 겹치거나 빠지지 않는다 */
    private static final Map<String, ComparableExpressionBase<?>> ENTITY_SORTS = Map.of(
            "entityName", entity.entityName,
            "entityDescription", entity.entityDescription,
            "isApproval", entity.isApproval);

    /** 정렬할 수 있는 화면 필드 (PageableUtil 허용 목록 — 이 맵의 키, 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> ENTITY_SORT_FIELDS = ENTITY_SORTS.keySet();

    /** 관계 목록 정렬 필드 (M-6 후속 3차 보완). 출발·도착은 그 엔터티의 이름. 이름 없는 관계는 방향과 무관하게 뒤로 (H2·PostgreSQL 같은 순서), 같은 값은 관계 ID 순 */
    private static final QLogicalEntity source = new QLogicalEntity("source");
    private static final QLogicalEntity target = new QLogicalEntity("target");
    private static final Map<String, ComparableExpressionBase<?>> RELATIONSHIP_SORTS = Map.of(
            "relationshipName", relationship.relationshipName,
            "sourceEntityName", source.entityName,
            "targetEntityName", target.entityName);

    /** 정렬할 수 있는 화면 필드 (PageableUtil 허용 목록 — 이 맵의 키, 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> RELATIONSHIP_SORT_FIELDS = RELATIONSHIP_SORTS.keySet();

    private final JPAQueryFactory query;

    /**
     * 페이지의 엔터티를 먼저 고르고(정렬·OFFSET·LIMIT 만), 그 엔터티들의 수를 한 번씩 묶어 센다 — 조회 수는 페이지 크기·모델 크기와 무관하게 5번.
     * 목록 쿼리에 상관 서브쿼리로 세면 PostgreSQL 이 OFFSET 으로 건너뛰는 행까지 계산해 엔터티 3,000개 모델의 마지막 페이지가 830ms 걸렸다 (측정, 기록 m6 15장)
     */
    public Page<LogicalModelDto.EntityRow> findEntityRows(Long modelId, String pattern, Pageable pageable) {
        BooleanExpression where = entity.model.id.eq(modelId)
                .and(entity.entityName.lower().like(pattern, '!').or(entity.entityDescription.lower().like(pattern, '!')));
        List<Tuple> page = query.select(entity.id, entity.entityName, entity.entityDescription, entity.isApproval, entity.updated)
                .from(entity)
                .where(where)
                .orderBy(orders(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = query.select(entity.count()).from(entity).where(where).fetchOne();
        List<Long> ids = page.stream().map(row -> row.get(entity.id)).toList();
        Map<Long, Long> attributes = counts(ids.isEmpty() ? List.of() : query.select(attribute.entity.id, attribute.count()).from(attribute)
                .where(attribute.entity.id.in(ids)).groupBy(attribute.entity.id).fetch());
        // 관계 수 : 출발이 이 엔터티 + 도착만 이 엔터티 (자기 참조를 두 번 세지 않는다). 출발·도착 인덱스를 각각 쓴다
        Map<Long, Long> outgoing = counts(ids.isEmpty() ? List.of() : query.select(relationship.sourceEntity.id, relationship.count()).from(relationship)
                .where(relationship.sourceEntity.id.in(ids)).groupBy(relationship.sourceEntity.id).fetch());
        Map<Long, Long> incoming = counts(ids.isEmpty() ? List.of() : query.select(relationship.targetEntity.id, relationship.count()).from(relationship)
                .where(relationship.targetEntity.id.in(ids), relationship.sourceEntity.id.ne(relationship.targetEntity.id)).groupBy(relationship.targetEntity.id).fetch());
        List<LogicalModelDto.EntityRow> rows = page.stream().map(row -> {
            Long id = row.get(entity.id);
            return new LogicalModelDto.EntityRow(id, row.get(entity.entityName), row.get(entity.entityDescription), row.get(entity.isApproval),
                    attributes.getOrDefault(id, 0L), outgoing.getOrDefault(id, 0L) + incoming.getOrDefault(id, 0L), row.get(entity.updated));
        }).toList();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /**
     * 관계 목록 페이지의 관계 ID (M-6 후속 3차 보완) : 검색어(관계명·출발 엔터티명·도착 엔터티명 중 하나라도, 엔터티 검색과 같은 규칙)와 식별 여부(null = 전체)로 거르고
     * 허용 목록 정렬(없으면 예전처럼 관계 ID 순) 뒤 페이지만. 양 끝 엔터티는 관계마다 하나씩이라 조인해도 행이 늘지 않는다 (한 관계는 한 번만 나온다)
     */
    public Page<Long> findRelationshipIds(Long modelId, String pattern, Boolean identifying, Pageable pageable) {
        BooleanExpression where = relationship.model.id.eq(modelId)
                .and(relationship.relationshipName.lower().like(pattern, '!')
                        .or(source.entityName.lower().like(pattern, '!'))
                        .or(target.entityName.lower().like(pattern, '!')));
        if (identifying != null) where = where.and(relationship.isIdentifying.eq(identifying));
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            ComparableExpressionBase<?> path = RELATIONSHIP_SORTS.get(order.getProperty());
            if (path != null) orders.add((order.isAscending() ? path.asc() : path.desc()).nullsLast());
        }
        orders.add(relationship.id.asc());
        List<Long> ids = query.select(relationship.id)
                .from(relationship)
                .join(relationship.sourceEntity, source)
                .join(relationship.targetEntity, target)
                .where(where)
                .orderBy(orders.toArray(OrderSpecifier[]::new))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = query.select(relationship.count())
                .from(relationship)
                .join(relationship.sourceEntity, source)
                .join(relationship.targetEntity, target)
                .where(where)
                .fetchOne();
        return new PageImpl<>(ids, pageable, total == null ? 0 : total);
    }

    /** (엔터티 ID, 수) 행 → 맵 */
    private static Map<Long, Long> counts(List<Tuple> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Tuple row : rows) counts.put(row.get(0, Long.class), row.get(1, Long.class));
        return counts;
    }

    private static OrderSpecifier<?>[] orders(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = ENTITY_SORTS.get(order.getProperty());
            if (path != null) orders.add(order.isAscending() ? path.asc() : path.desc());
        }
        if (orders.isEmpty()) orders.add(entity.entityName.asc());
        orders.add(entity.id.asc());
        return orders.toArray(OrderSpecifier[]::new);
    }
}
