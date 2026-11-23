package com.lexorma.data.mapping.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.logical.domain.entity.QLogicalModel;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.domain.entity.QAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QEntityTableMapping;
import com.lexorma.data.physical.domain.entity.QTableConstraint;
import com.lexorma.data.physical.domain.entity.QTableConstraintColumn;
import com.lexorma.data.physical.domain.entity.QTableIndex;
import com.lexorma.data.physical.domain.entity.QTableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.view.domain.entity.QPhysicalView;
import com.lexorma.data.view.domain.entity.QPhysicalViewReference;
import com.lexorma.data.view.domain.entity.QViewChangeRequest;
import com.lexorma.data.view.domain.entity.ViewChangeRequest;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.lexorma.data.column.repository.ColumnNameKeySql;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 물리명 추천 조회 (M-7 3차). 컬럼명 추천은 상태 조건까지 DB 에서 걸러 페이징한다 —
 * 추천명이 표준 용어의 영문약어(저장된 값)라서 추천 가능·일치·추천 불가·충돌을 SQL 로 판정할 수 있다.
 * 상태 판정식은 PhysicalNameRecommendationService 의 자바 판정과 같아야 한다 (통합 테스트가 건수를 맞춰 본다).
 * <p>
 * M7-NAME-1 (docs/completed/physical-name-recommendation-m7.md 12장) :
 * <ul>
 *   <li>'일치' 는 자바 {@code StandardNames.sameColumnName} 이 기준이다. SQL 은 인쇄 가능한 ASCII 로만 된 행({@link StandardNameSql#PLAIN_FUNCTION})에서만 판정하고
 *       (그 범위에서는 SQL trim + ASCII 대문자 = 자바 trim + equalsIgnoreCase), 그 밖의 행(탭·줄바꿈·비 ASCII)은 서비스가 자바로 판정한 상태를 {@link Overrides} 로 넘겨
 *       상태 조건·요약 건수가 그 판정을 따르게 한다 (보통은 그런 행이 없어 조건이 예전과 같다)</li>
 *   <li>물리 테이블 = <b>매핑된 컬럼이 실제로 속한 테이블</b>, 컬럼이 없으면 엔터티 매핑의 테이블 (NAME-P06). A형 서브타입 속성은 엔터티 매핑 없이 루트 통합 테이블의 컬럼에
 *       매핑되므로, 충돌·추천끼리 겹침을 그 통합 테이블 기준으로 본다 — 일괄 적용(컬럼의 실제 테이블)과 같은 기준</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class PhysicalNameRecommendationQueryRepository {

    private static final QLogicalModel model = QLogicalModel.logicalModel;
    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QEntityTableMapping mapping = QEntityTableMapping.entityTableMapping;
    private static final QAttributeColumnMapping attributeMapping = QAttributeColumnMapping.attributeColumnMapping;
    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QStandardTerm term = QStandardTerm.standardTerm;
    private static final QEntityTableMapping tableMapping = new QEntityTableMapping("tableMapping");
    private static final QLogicalEntity mappedEntity = new QLogicalEntity("mappedEntity");

    /** 추천 컬럼명 = 표준 용어의 영문약어 (앞뒤 공백 제거, 대소문자 무시 비교용 대문자) */
    private static final StringExpression RECOMMENDED = Expressions.stringTemplate(StandardNameSql.UPPER_FUNCTION + "(trim({0}))", term.commonStandardTermAbbreviation);
    private static final StringExpression CURRENT = Expressions.stringTemplate(StandardNameSql.UPPER_FUNCTION + "(trim({0}))", column.columnName.coalesce(""));
    /** 자바 판정과 SQL 판정이 같은 행 : 영문약어·컬럼명이 모두 인쇄 가능한 ASCII (또는 null) */
    private static final BooleanExpression PLAIN = Expressions.numberTemplate(Integer.class, StandardNameSql.PLAIN_FUNCTION + "({0})", term.commonStandardTermAbbreviation).eq(1)
            .and(Expressions.numberTemplate(Integer.class, StandardNameSql.PLAIN_FUNCTION + "({0})", column.columnName).eq(1));

    private static final BooleanExpression UNAVAILABLE = term.id.isNull()
            .or(term.commonStandardTermAbbreviation.isNull())
            .or(term.commonStandardTermAbbreviation.trim().isEmpty());
    private static final BooleanExpression MATCHED = column.id.isNotNull().and(CURRENT.eq(RECOMMENDED));
    /**
     * 매핑된 테이블에서 이 속성의 컬럼이 아닌 다른 컬럼이 추천명을 쓰고 있다 — 컬럼명 유일성 기준 (M7-U10 : DB 가 계산한 COL_NM_CMPR_VL 과 추천명의 비교 값을
     * 그 DB 의 식으로 비교, UX_COL_01 인덱스. {@code ColumnNameKeySql} 이 등록한 HQL 함수). 일치(MATCHED) 판정은 표준화 규칙 그대로다
     */
    private static final BooleanExpression CONFLICT = table.id.isNotNull().and(Expressions.numberTemplate(Integer.class,
            ColumnNameKeySql.TAKEN_FUNCTION + "({0}, {1}, {2})", table.id, term.commonStandardTermAbbreviation, column.id).eq(1));

    /** 그리드 정렬 필드 → 조회 컬럼 (목록에 없는 필드는 무시한다) */
    private static final Map<String, ComparableExpressionBase<?>> COLUMN_SORTS = Map.of(
            "entityName", entity.entityName,
            "logicalName", attribute.attributeName,
            "currentPhysicalName", column.columnName,
            "recommendedPhysicalName", term.commonStandardTermAbbreviation);

    /** 정렬할 수 있는 화면 필드 (PageableUtil 허용 목록 — 이 맵의 키, 공통 페이징 안정성 개선) */
    public static final java.util.Set<String> COLUMN_SORT_FIELDS = COLUMN_SORTS.keySet();

    private final JPAQueryFactory query;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /**
     * 이 DB 가 바인딩 변수 {@code IN} 목록을 행마다 처음부터 훑는가 (H2 2.4 — 상수 목록과 달리 해시 집합으로 바꾸지 않는다, M7-NAME-F02 측정 12.12).
     * 서비스가 덮어쓰기 목록과 전체 자바 판정 중 하나를 고르는 기준값을 DB 별로 정한다
     */
    public boolean scansBoundInListPerRow() {
        return entityManager.getEntityManagerFactory().unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class).getJdbcServices().getDialect()
                instanceof org.hibernate.dialect.H2Dialect;
    }

    /**
     * SQL 로 판정하지 않는 행(ASCII 밖의 글자가 있는 행)의 자바 판정 결과 : complex = 그 행들의 속성 ID 전체, byStatus = 자바가 정한 상태별 속성 ID.
     * 비어 있으면 상태 조건은 SQL 식 그대로다
     */
    public record Overrides(java.util.Set<Long> complex, Map<Status, java.util.Set<Long>> byStatus) {
        public static final Overrides NONE = new Overrides(java.util.Set.of(), Map.of());
    }

    /** 컬럼명 추천 원본 : 속성 1개 = 1행 (기본 정렬 엔터티명 → 속성 순서). 모델·엔터티·속성·컬럼·상태·이름 조건을 DB 에서 거른다 */
    public Page<ColumnSource> findColumnSources(Long projectId, ColumnSearch search, Pageable pageable) {
        return findColumnSources(projectId, search, pageable, Overrides.NONE);
    }

    public Page<ColumnSource> findColumnSources(Long projectId, ColumnSearch search, Pageable pageable, Overrides overrides) {
        BooleanBuilder where = columnWhere(projectId, search, overrides);
        List<ColumnSource> rows = columnJoins(query.select(SOURCE))
                .where(where)
                .orderBy(orders(pageable.getSort(), entity.entityName.asc(), entity.id.asc(), attribute.sortOrder.asc().nullsLast(), attribute.id.asc()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        return new PageImpl<>(rows, pageable, count(where));
    }

    /**
     * 상태 조건을 뺀 같은 조건·같은 정렬의 원본 전체 (쪽·건수 조회 없음, M7-NAME-F02). 서비스가 모든 행을 자바로 판정해 상태를 거르고 쪽을 자른다 —
     * 정렬 식이 {@link #findColumnSources} 와 같고 끝이 속성 ID 라 순서가 하나로 정해지므로, 거른 뒤 자른 쪽 = SQL 로 거르고 자른 쪽이다
     */
    public List<ColumnSource> findAllColumnSources(Long projectId, ColumnSearch search, Sort sort) {
        BooleanBuilder where = columnWhere(projectId, new ColumnSearch(search.modelId(), search.entityId(), search.attributeId(), search.columnId(), null,
                search.searchColumn(), search.searchQuery()), Overrides.NONE);
        return columnJoins(query.select(SOURCE))
                .where(where)
                .orderBy(orders(sort, entity.entityName.asc(), entity.id.asc(), attribute.sortOrder.asc().nullsLast(), attribute.id.asc()))
                .fetch();
    }

    /** 상태별 건수 (요약). 조건은 목록과 같은 식을 쓴다 */
    public long countColumns(Long projectId, Long modelId, Status status) {
        return countColumns(projectId, modelId, status, Overrides.NONE);
    }

    public long countColumns(Long projectId, Long modelId, Status status, Overrides overrides) {
        return count(columnWhere(projectId, new ColumnSearch(modelId, null, null, null, status, null, null), overrides));
    }

    /**
     * 프로젝트에 ASCII 밖의 글자가 있는 컬럼명·표준 용어 약어가 하나라도 있는가 (조인 없이 두 표만, 첫 행에서 멈춘다). 없으면(보통) 아래 무거운 조회를 건너뛴다 —
     * 측정 : PostgreSQL 9,000 속성 모델에서 그 조회가 약 18ms 였다 (docs/completed/physical-name-recommendation-m7.md 12.8)
     */
    public boolean hasComplexNames(Long projectId) {
        QColumnInfo c = new QColumnInfo("plainColumn");
        QStandardTerm t = new QStandardTerm("plainTerm");
        if (query.selectOne().from(c).where(c.projectId.eq(projectId),
                Expressions.numberTemplate(Integer.class, StandardNameSql.PLAIN_FUNCTION + "({0})", c.columnName).eq(0)).fetchFirst() != null) return true;
        return query.selectOne().from(t).where(t.projectId.eq(projectId),
                Expressions.numberTemplate(Integer.class, StandardNameSql.PLAIN_FUNCTION + "({0})", t.commonStandardTermAbbreviation).eq(0)).fetchFirst() != null;
    }

    /**
     * SQL 로 '일치' 를 판정하지 않는 행 (영문약어나 컬럼명에 탭·줄바꿈·비 ASCII 글자가 있다) — 상태 조건을 뺀 같은 조건. 서비스가 자바로 판정한다 (보통 0건, 투영만 읽는다)
     */
    public List<ColumnSource> findComplexColumnSources(Long projectId, ColumnSearch search) {
        return findComplexColumnSources(projectId, search, 0);
    }

    /** limit &gt; 0 이면 그 수까지만 읽는다 (서비스가 판정 방식을 고를 때 — 기준보다 많은지만 알면 된다, M7-NAME-F02) */
    public List<ColumnSource> findComplexColumnSources(Long projectId, ColumnSearch search, int limit) {
        BooleanBuilder where = columnWhere(projectId, new ColumnSearch(search.modelId(), search.entityId(), search.attributeId(), search.columnId(), null,
                search.searchColumn(), search.searchQuery()), Overrides.NONE);
        where.and(PLAIN.not());
        JPAQuery<ColumnSource> select = columnJoins(query.select(SOURCE)).where(where);
        return limit > 0 ? select.limit(limit).fetch() : select.fetch();
    }

    private long count(BooleanBuilder where) {
        Long total = columnJoins(query.select(attribute.count())).where(where).fetchOne();
        return total == null ? 0 : total;
    }

    private static final com.querydsl.core.types.ConstructorExpression<ColumnSource> SOURCE = Projections.constructor(ColumnSource.class,
            model.id, model.modelName, entity.id, entity.entityName, mapping.id,
            schema.id, schema.schemaName, table.id, table.tableName,
            attribute.id, attribute.attributeName, term.id, term.commonStandardTermName, term.commonStandardTermAbbreviation,
            attributeMapping.id, column.id, column.columnName, column.standardTerms.id, column.isApproval, table.isApproval);

    private static BooleanBuilder columnWhere(Long projectId, ColumnSearch search, Overrides overrides) {
        BooleanBuilder where = new BooleanBuilder(entity.projectId.eq(projectId));
        if (search.modelId() != null) where.and(model.id.eq(search.modelId()));
        if (search.entityId() != null) where.and(entity.id.eq(search.entityId()));
        if (search.attributeId() != null) where.and(attribute.id.eq(search.attributeId()));
        if (search.columnId() != null) where.and(column.id.eq(search.columnId()));
        if (search.status() != null) {
            BooleanExpression bySql = switch (search.status()) {
                case UNAVAILABLE -> UNAVAILABLE;
                case ALREADY_MATCHED -> UNAVAILABLE.not().and(MATCHED);
                case CONFLICT -> UNAVAILABLE.not().and(MATCHED.not()).and(CONFLICT);
                case AVAILABLE -> UNAVAILABLE.not().and(MATCHED.not()).and(CONFLICT.not());
            };
            if (!overrides.complex().isEmpty()) {
                // 자바로 판정한 행은 SQL 식에서 빼고, 자바가 이 상태로 정한 행만 더한다
                java.util.Set<Long> chosen = overrides.byStatus().getOrDefault(search.status(), java.util.Set.of());
                bySql = attribute.id.notIn(overrides.complex()).and(bySql);
                if (!chosen.isEmpty()) bySql = bySql.or(attribute.id.in(chosen));
            }
            where.and(bySql);
        }
        if (search.searchQuery() != null && !search.searchQuery().isBlank()) {
            String keyword = search.searchQuery().trim();
            where.and(switch (search.searchColumn() == null ? "" : search.searchColumn()) {
                case "logicalName" -> attribute.attributeName.containsIgnoreCase(keyword);
                case "currentPhysicalName" -> column.columnName.containsIgnoreCase(keyword);
                case "recommendedPhysicalName" -> term.commonStandardTermAbbreviation.containsIgnoreCase(keyword);
                default -> entity.entityName.containsIgnoreCase(keyword);
            });
        }
        return where;
    }

    private static <T> JPAQuery<T> columnJoins(JPAQuery<T> select) {
        return select.from(attribute)
                .join(attribute.entity, entity)
                .join(entity.model, model)
                .leftJoin(attribute.standardTerm, term)
                .leftJoin(mapping).on(mapping.entity.eq(entity))
                .leftJoin(attributeMapping).on(attributeMapping.attribute.eq(attribute))
                .leftJoin(attributeMapping.column, column)
                // 물리 테이블 = 매핑된 컬럼의 실제 테이블, 없으면 엔터티 매핑의 테이블 (NAME-P06 — A형 서브타입 속성은 루트 통합 테이블)
                .leftJoin(table).on(table.id.eq(column.tableInfo.id.coalesce(mapping.table.id)))
                .leftJoin(table.schema, schema);
    }

    // ------------------------------------------------------------------ 상속 컬럼 (M7-NAME-1, NAME-P07 — 읽기 전용)

    private static final com.lexorma.data.mapping.domain.entity.QSubtypeIdentifierColumnMapping keyLink =
            com.lexorma.data.mapping.domain.entity.QSubtypeIdentifierColumnMapping.subtypeIdentifierColumnMapping;
    private static final com.lexorma.data.mapping.domain.entity.QSubtypeAttributeColumnMapping attributeLink =
            com.lexorma.data.mapping.domain.entity.QSubtypeAttributeColumnMapping.subtypeAttributeColumnMapping;
    private static final QEntityTableMapping linkMapping = new QEntityTableMapping("linkMapping");
    private static final QLogicalEntity linkEntity = new QLogicalEntity("linkEntity");
    private static final QLogicalAttribute linkAttribute = new QLogicalAttribute("linkAttribute");
    private static final QLogicalEntity originEntity = new QLogicalEntity("originEntity");
    private static final QColumnInfo linkColumn = new QColumnInfo("linkColumn");
    private static final QTableInfo linkTable = new QTableInfo("linkTable");
    private static final QSchemaInfo linkSchema = new QSchemaInfo("linkSchema");
    private static final QStandardTerm linkTerm = new QStandardTerm("linkTerm");

    /**
     * 상속 매핑으로 이어진 물리 컬럼 1개 : KEY = 상속 PK 매핑 (B형 서브타입·C형 말단의 루트 PK 복제), ATTRIBUTE = C형 상속 일반 속성 매핑 (조상 일반 속성 복제).
     * entity = 그 컬럼의 테이블에 매핑된 엔터티(서브타입·말단), attribute = 원본 속성 (조상 엔터티 origin 의 속성)
     */
    public record InheritedSource(String kind, Long linkId, Long modelId, String modelName, Long entityId, String entityName, Long mappingId,
                                  Long schemaId, String schemaName, Long tableId, String tableName, Boolean tableApproval,
                                  Long originEntityId, String originEntityName, Long attributeId, String attributeName,
                                  Long termId, String termName, String termAbbreviation,
                                  Long columnId, String columnName, Long columnTermId, Boolean columnApproval) {
    }

    /**
     * 모델의 상속 컬럼 전체 (두 매핑 표를 한 번씩, 투영만 — 엔티티를 적재하지 않는다). 상속 컬럼은 서브타입마다 루트 PK 수 + C형 말단마다 조상 속성 수라
     * 모델 단위로 읽어 자바로 판정·정렬·페이징한다 (정합성 비교와 같은 범위)
     */
    public List<InheritedSource> findInheritedSources(Long projectId, Long modelId, Long entityId, Long columnId) {
        return findInheritedSources(projectId, modelId, entityId, columnId == null ? null : List.of(columnId));
    }

    /** columnIds 가 null 이 아니면 그 컬럼만 (일괄 적용 미리보기·적용의 읽기 전용 판정) */
    public List<InheritedSource> findInheritedSources(Long projectId, Long modelId, Long entityId, Collection<Long> columnIds) {
        if (columnIds != null && columnIds.isEmpty()) return List.of();
        List<InheritedSource> result = new ArrayList<>(inherited("KEY", keyLink.id, keyLink.mapping, keyLink.attribute, keyLink.column, projectId, modelId, entityId, columnIds));
        result.addAll(inherited("ATTRIBUTE", attributeLink.id, attributeLink.mapping, attributeLink.attribute, attributeLink.column, projectId, modelId, entityId, columnIds));
        return result;
    }

    private List<InheritedSource> inherited(String kind, com.querydsl.core.types.dsl.NumberPath<Long> linkId, QEntityTableMapping linkMappingPath,
                                            QLogicalAttribute linkAttributePath, QColumnInfo linkColumnPath, Long projectId, Long modelId, Long entityId,
                                            Collection<Long> columnIds) {
        com.querydsl.core.types.EntityPath<?> root = (com.querydsl.core.types.EntityPath<?>) linkId.getMetadata().getParent();
        BooleanBuilder where = new BooleanBuilder(linkEntity.projectId.eq(projectId));
        if (modelId != null) where.and(linkEntity.model.id.eq(modelId));
        if (entityId != null) where.and(linkEntity.id.eq(entityId));
        if (columnIds != null) where.and(linkColumn.id.in(columnIds));
        return query.select(Projections.constructor(InheritedSource.class, Expressions.constant(kind), linkId, model.id, model.modelName, linkEntity.id, linkEntity.entityName,
                        linkMapping.id, linkSchema.id, linkSchema.schemaName, linkTable.id, linkTable.tableName, linkTable.isApproval,
                        originEntity.id, originEntity.entityName, linkAttribute.id, linkAttribute.attributeName,
                        linkTerm.id, linkTerm.commonStandardTermName, linkTerm.commonStandardTermAbbreviation,
                        linkColumn.id, linkColumn.columnName, linkColumn.standardTerms.id, linkColumn.isApproval))
                .from(root)
                .join(linkMappingPath, linkMapping)
                .join(linkMapping.entity, linkEntity)
                .join(linkEntity.model, model)
                .join(linkAttributePath, linkAttribute)
                .join(linkAttribute.entity, originEntity)
                .leftJoin(linkAttribute.standardTerm, linkTerm)
                .join(linkColumnPath, linkColumn)
                .join(linkColumn.tableInfo, linkTable)
                .leftJoin(linkTable.schema, linkSchema)
                .where(where)
                .fetch();
    }

    /** 테이블에서 그 이름을 쓰는 컬럼 (충돌 사유 표시용, 한 쪽 분량을 한 번에) */

    /** 프로젝트에서 그 이름을 쓰는 테이블 (충돌은 같은 스키마만 — 서비스가 스키마 ID 로 거른다. 테이블·VIEW 이름 공간은 스키마 단위, M-15) */
    public record NamedTable(Long tableId, Long schemaId, String tableName, String mappedEntityName) {
    }

    public List<NamedTable> findTablesByName(Long projectId, Collection<String> upperNames) {
        if (upperNames.isEmpty()) return List.of();
        return query.select(Projections.constructor(NamedTable.class, table.id, table.schema.id, table.tableName, mappedEntity.entityName))
                .from(table)
                .leftJoin(tableMapping).on(tableMapping.table.eq(table))
                .leftJoin(tableMapping.entity, mappedEntity)
                .where(table.projectId.eq(projectId), table.tableName.coalesce("").trim().upper().in(upperNames))
                .fetch();
    }

    private static OrderSpecifier<?>[] orders(Sort sort, OrderSpecifier<?>... defaults) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            ComparableExpressionBase<?> path = COLUMN_SORTS.get(order.getProperty());
            if (path != null) {
                orders.add(order.isAscending() ? path.asc() : path.desc());
            }
        }
        orders.addAll(List.of(defaults));
        return orders.toArray(OrderSpecifier[]::new);
    }

    // ------------------------------------------------------------------ 일괄 적용 판정 (M-7 물리명 추천 일괄 적용 1차, 읽기 전용)

    private static final QPhysicalView view = QPhysicalView.physicalView;
    private static final QPhysicalViewReference viewReference = QPhysicalViewReference.physicalViewReference;
    private static final QViewChangeRequest viewRequest = QViewChangeRequest.viewChangeRequest;
    private static final QTableConstraint constraint = QTableConstraint.tableConstraint;
    private static final QTableConstraintColumn constraintColumn = QTableConstraintColumn.tableConstraintColumn;
    private static final QTableIndex index = QTableIndex.tableIndex;
    private static final QTableIndexColumn indexColumn = QTableIndexColumn.tableIndexColumn;
    private static final QAttributeColumnMapping candidateMapping = new QAttributeColumnMapping("candidateMapping");
    private static final QColumnInfo candidateColumn = new QColumnInfo("candidateColumn");
    private static final QLogicalAttribute candidateAttribute = new QLogicalAttribute("candidateAttribute");
    private static final QStandardTerm candidateTerm = new QStandardTerm("candidateTerm");
    private static final QEntityTableMapping candidateTableMapping = new QEntityTableMapping("candidateTableMapping");
    private static final QTableInfo candidateTable = new QTableInfo("candidateTable");
    private static final QLogicalEntity candidateEntity = new QLogicalEntity("candidateEntity");

    /** 프로젝트에서 그 이름(대문자 비교)을 쓰는 VIEW — 테이블과 VIEW 는 같은 스키마에서 이름을 나눠 쓴다 (ObjectNamespace) */
    public record NamedView(Long viewId, Long schemaId, String viewName) {
    }

    public List<NamedView> findViewsByName(Long projectId, Collection<String> upperNames) {
        if (upperNames.isEmpty()) return List.of();
        return query.select(Projections.constructor(NamedView.class, view.id, view.schema.id, view.viewName))
                .from(view)
                .where(view.projectId.eq(projectId), view.viewName.coalesce("").trim().upper().in(upperNames))
                .fetch();
    }

    /** 속성 매핑된 컬럼 1개와 그 속성의 표준 용어 영문약어 (추천끼리 충돌 판정의 후보 — 페이지와 관계없이 그 테이블 전체) */
    public record MappedColumn(Long tableId, Long columnId, String columnName, String termAbbreviation) {
    }

    public List<MappedColumn> findMappedColumns(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(MappedColumn.class, candidateColumn.tableInfo.id, candidateColumn.id, candidateColumn.columnName,
                        candidateTerm.commonStandardTermAbbreviation))
                .from(candidateMapping)
                .join(candidateMapping.column, candidateColumn)
                .join(candidateMapping.attribute, candidateAttribute)
                .leftJoin(candidateAttribute.standardTerm, candidateTerm)
                .where(candidateColumn.tableInfo.id.in(tableIds))
                .fetch();
    }

    /** 엔터티에 매핑된 테이블 1개 (추천끼리 테이블명 충돌 판정의 후보 — 같은 스키마의 매핑 전체) */
    public record MappedTable(Long entityId, String entityName, Long tableId, String tableName, Long schemaId) {
    }

    public List<MappedTable> findMappedTables(Long projectId, Collection<Long> schemaIds) {
        if (schemaIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(MappedTable.class, candidateEntity.id, candidateEntity.entityName, candidateTable.id, candidateTable.tableName,
                        candidateTable.schema.id))
                .from(candidateTableMapping)
                .join(candidateTableMapping.entity, candidateEntity)
                .join(candidateTableMapping.table, candidateTable)
                .where(candidateTable.projectId.eq(projectId), candidateTable.schema.id.in(schemaIds))
                .fetch();
    }

    /** 프로젝트의 모델 VIEW 정의 (스키마와 관계없이 — VIEW 는 다른 스키마의 테이블도 참조한다) */
    public record ViewDefinition(Long viewId, String viewName, String definition) {
    }

    public List<ViewDefinition> findViewDefinitions(Long projectId) {
        return query.select(Projections.constructor(ViewDefinition.class, view.id, view.viewName, view.definition))
                .from(view).where(view.projectId.eq(projectId)).fetch();
    }

    /** VIEW 참조 객체 메타데이터 (내부 참조만 : 테이블 ID 또는 VIEW ID) */
    public record ViewLink(Long viewId, Long tableId, Long referencedViewId) {
    }

    public List<ViewLink> findViewLinks(Long projectId) {
        return query.select(Projections.constructor(ViewLink.class, viewReference.view.id, viewReference.referencedTable.id, viewReference.referencedView.id))
                .from(viewReference)
                .where(viewReference.view.projectId.eq(projectId), viewReference.referencedTable.isNotNull().or(viewReference.referencedView.isNotNull()))
                .fetch();
    }

    /** 처리 전(REQUESTED) VIEW 변경 요청의 변경 후 데이터 (JSON — 승인하면 그 정의가 반영된다) */
    public record PendingViewRequest(Long viewId, String afterData) {
    }

    public List<PendingViewRequest> findPendingViewRequests(Long projectId) {
        return query.select(Projections.constructor(PendingViewRequest.class, viewRequest.view.id, viewRequest.afterData))
                .from(viewRequest)
                .where(viewRequest.projectId.eq(projectId), viewRequest.status.eq(ViewChangeRequest.Status.REQUESTED))
                .fetch();
    }

    /** 테이블의 CHECK 제약조건 식 */
    public record CheckExpression(Long constraintId, Long tableId, String constraintName, String expression) {
    }

    public List<CheckExpression> findCheckExpressions(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(CheckExpression.class, constraint.id, constraint.tableInfo.id, constraint.constraintName, constraint.checkExpression))
                .from(constraint)
                .where(constraint.tableInfo.id.in(tableIds), constraint.constraintType.eq(TableConstraint.Type.CHECK))
                .fetch();
    }

    /** CHECK 제약조건의 구성 컬럼 (constraintId, columnId) */
    public List<com.querydsl.core.Tuple> findCheckColumns(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(constraintColumn.tableConstraint.id, constraintColumn.column.id)
                .from(constraintColumn)
                .where(constraintColumn.tableConstraint.tableInfo.id.in(tableIds), constraintColumn.tableConstraint.constraintType.eq(TableConstraint.Type.CHECK))
                .fetch();
    }

    /** 인덱스의 조건(부분 인덱스) 또는 식 컬럼 1건 — 텍스트가 있는 것만 */
    public record IndexExpression(Long indexId, Long tableId, String indexName, String text) {
    }

    public List<IndexExpression> findIndexExpressions(Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        List<IndexExpression> result = new ArrayList<>(query.select(Projections.constructor(IndexExpression.class, index.id, index.tableInfo.id, index.indexName, index.condition))
                .from(index)
                .where(index.tableInfo.id.in(tableIds), index.condition.isNotNull())
                .fetch());
        result.addAll(query.select(Projections.constructor(IndexExpression.class, index.id, index.tableInfo.id, index.indexName, indexColumn.expression))
                .from(indexColumn).join(indexColumn.tableIndex, index)
                .where(index.tableInfo.id.in(tableIds), indexColumn.expression.isNotNull())
                .fetch());
        return result;
    }
}
