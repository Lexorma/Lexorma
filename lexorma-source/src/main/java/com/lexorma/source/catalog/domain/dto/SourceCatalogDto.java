package com.lexorma.source.catalog.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/** 원본 카탈로그 조회 응답 (읽기 전용). 이름은 원본이 준 그대로다 */
public final class SourceCatalogDto {

    private SourceCatalogDto() {
    }

    public record Schema(Long id, Long connectionId, String catalogName, String schemaName, boolean present, long tableCount) {
    }

    /** @param comment 원본 COMMENT (물리 모델의 업무 설명과 무관하다) */
    public record Table(Long id, Long schemaId, String name, String type, Boolean quoted, String comment, boolean present) {
    }

    public record Column(Long id, String name, Boolean quoted, Integer ordinal, String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                         Boolean nullable, String defaultValue, String comment, boolean present) {
    }

    /** @param columns 구성 컬럼 (순서대로). CHECK 는 비어 있을 수 있다 */
    public record Constraint(Long id, String name, String type, String checkExpression, List<String> columns) {
    }

    public record Index(Long id, String name, String indexType, boolean unique, List<String> columns) {
    }

    /** @param referencedColumnId 참조되는 컬럼이 카탈로그에 없으면 null (이름은 남는다) */
    public record ColumnPair(Long columnId, String columnName, Long referencedColumnId, String referencedColumnName) {
    }

    /**
     * FK. tableId·tableName 이 참조하는 쪽이다.
     *
     * @param referencedTableId 참조되는 테이블이 카탈로그에 없으면(수집 범위 밖) null
     */
    public record Relation(Long id, String name, Long tableId, String tableName, Long referencedTableId, String referencedSchemaName, String referencedTableName,
                           String deleteRule, String updateRule, List<ColumnPair> columns) {
    }

    /** @param referencedBy 이 테이블을 참조하는 FK (다른 스키마의 테이블일 수 있다) */
    public record TableDetail(Table table, Schema schema, List<Column> columns, List<Constraint> constraints, List<Index> indexes,
                              List<Relation> relations, List<Relation> referencedBy) {
    }
}
