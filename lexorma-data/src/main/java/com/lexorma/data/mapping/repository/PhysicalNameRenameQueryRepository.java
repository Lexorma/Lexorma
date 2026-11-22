package com.lexorma.data.mapping.repository;

import com.lexorma.data.column.domain.entity.QColumnInfo;
import com.lexorma.data.dictionary.domain.entity.QStandardTerm;
import com.lexorma.data.logical.domain.entity.QLogicalAttribute;
import com.lexorma.data.logical.domain.entity.QLogicalEntity;
import com.lexorma.data.mapping.domain.entity.QAttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.QEntityTableMapping;
import com.lexorma.data.schema.domain.entity.QSchemaInfo;
import com.lexorma.data.sourcemapping.domain.entity.QSourceTableMapping;
import com.lexorma.data.table.domain.entity.QTableInfo;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 물리명 추천 일괄 적용 대상 조회 (M-7 물리명 추천 일괄 적용 2차, 읽기 전용 투영 — 엔티티를 적재하지 않는다).
 * 대상 테이블·컬럼과 그 매핑(엔터티·속성·표준 용어)을 ID 목록으로 한 번에 읽는다. 다른 프로젝트의 ID 는 나오지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class PhysicalNameRenameQueryRepository {

    private static final QTableInfo table = QTableInfo.tableInfo;
    private static final QSchemaInfo schema = QSchemaInfo.schemaInfo;
    private static final QColumnInfo column = QColumnInfo.columnInfo;
    private static final QEntityTableMapping entityMapping = QEntityTableMapping.entityTableMapping;
    private static final QLogicalEntity entity = QLogicalEntity.logicalEntity;
    private static final QAttributeColumnMapping attributeMapping = QAttributeColumnMapping.attributeColumnMapping;
    private static final QLogicalAttribute attribute = QLogicalAttribute.logicalAttribute;
    private static final QStandardTerm term = QStandardTerm.standardTerm;
    private static final QSourceTableMapping sourceTable = QSourceTableMapping.sourceTableMapping;

    private final JPAQueryFactory query;

    /** 테이블 대상 : 매핑된 엔터티가 없으면 entityId·entityName·modelId 는 null */
    public record TableTarget(Long tableId, Long schemaId, String schemaName, String tableName, Boolean approval, LocalDateTime modifiedAt,
                              Long entityId, String entityName, Long modelId) {
    }

    public List<TableTarget> findTables(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(TableTarget.class, table.id, schema.id, schema.schemaName, table.tableName, table.isApproval, table.updated,
                        entity.id, entity.entityName, entity.model.id))
                .from(table)
                .join(table.schema, schema)
                .leftJoin(entityMapping).on(entityMapping.table.eq(table))
                .leftJoin(entityMapping.entity, entity)
                .where(table.projectId.eq(projectId), table.id.in(tableIds))
                .fetch();
    }

    /** 컬럼 대상 : 매핑된 속성이 없으면 attributeId·attributeName·attributeTermId·termAbbreviation·modelId 는 null */
    public record ColumnTarget(Long columnId, String columnName, Boolean approval, Long columnTermId, LocalDateTime modifiedAt,
                               Long tableId, String tableName, Boolean tableApproval, Long schemaId, String schemaName,
                               Long attributeId, String attributeName, Long attributeTermId, String termAbbreviation, Long modelId) {
    }

    public List<ColumnTarget> findColumns(Long projectId, Collection<Long> columnIds) {
        if (columnIds.isEmpty()) return List.of();
        return query.select(Projections.constructor(ColumnTarget.class, column.id, column.columnName, column.isApproval, column.standardTerms.id, column.updated,
                        table.id, table.tableName, table.isApproval, schema.id, schema.schemaName,
                        attribute.id, attribute.attributeName, term.id, term.commonStandardTermAbbreviation, entity.model.id))
                .from(column)
                .join(column.tableInfo, table)
                .join(table.schema, schema)
                .leftJoin(attributeMapping).on(attributeMapping.column.eq(column))
                .leftJoin(attributeMapping.attribute, attribute)
                .leftJoin(attribute.entity, entity)
                .leftJoin(attribute.standardTerm, term)
                .where(column.projectId.eq(projectId), column.id.in(columnIds))
                .fetch();
    }

    /** 원본 DB 테이블과 매핑된 모델 테이블 (P-15 경고 — 제외 표시한 매핑도 원본과 이어진 것으로 본다) */
    public Set<Long> findSourceMappedTables(Long projectId, Collection<Long> tableIds) {
        if (tableIds.isEmpty()) return Set.of();
        return new HashSet<>(query.select(sourceTable.tableId).from(sourceTable)
                .where(sourceTable.projectId.eq(projectId), sourceTable.tableId.in(tableIds)).fetch());
    }

    /** 테이블들의 컬럼 이름 (같은 테이블 이름 충돌 판정 — 대상 컬럼의 테이블 전체) : (tableId, columnId, columnName) */
}
