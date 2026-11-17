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

    /**
     * @param columns   구성 컬럼 (표현식 요소는 빼고 순서대로)
     * @param members   구성 요소 전체 (컬럼 또는 표현식, 정렬)
     * @param predicate 부분 인덱스 조건
     */
    public record Index(Long id, String name, String indexType, boolean unique, List<String> columns, List<IndexMember> members, String predicate) {
    }

    /** @param sort 기본(오름차순)이면 null */
    public record IndexMember(String columnName, String expression, String sort) {
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

    /**
     * @param referencedBy   이 테이블을 참조하는 FK (다른 스키마의 테이블일 수 있다)
     * @param viewDefinition VIEW 정의의 수집 상태 (DEC-16, 원문 없음). TABLE 이면 null
     */
    public record TableDetail(Table table, Schema schema, List<Column> columns, List<Constraint> constraints, List<Index> indexes,
                              List<Relation> relations, List<Relation> referencedBy, ViewDefinition viewDefinition) {
    }

    /** 이전 수집 대비 정의 : NEW 처음 수집 / CHANGED 바뀜 / SAME 같음 (마지막 반영 수집 기준) / UNKNOWN 아직 읽은 정의가 없음 */
    public enum DefinitionChange { NEW, CHANGED, SAME, UNKNOWN }

    /**
     * VIEW 정의의 상태만 (DEC-16). 원문·해시는 넣지 않는다 : 원문은 프로젝트 관리자·ADMIN 이 /api/source/tables/{id}/viewDefinition 으로만 본다.
     *
     * @param status       마지막 수집에서 정의를 읽은 결과. 아직 정의를 읽은 수집이 없으면 null
     * @param available    볼 수 있는 원문이 있는지 (마지막으로 읽은 원문 — 이번 수집이 실패했어도 남아 있다)
     * @param change       마지막 반영 수집에서 정의가 처음 수집·바뀌었는지
     * @param canRead      지금 사용자가 원문을 볼 수 있는지 (프로젝트 관리자·ADMIN)
     */
    public record ViewDefinition(String status, boolean available, Integer length, java.time.LocalDateTime collectedAt, Integer changedOrder, DefinitionChange change,
                                 boolean canRead) {
    }

    /** 정의 원문 (권한 확인·확인 이력을 남긴 뒤에만). 실행하지 않는 메타데이터다 */
    public record ViewDefinitionText(Long tableId, String schemaName, String viewName, String definition, String status, Integer length,
                                     java.time.LocalDateTime collectedAt) {
        /** 기록·예외에 원문이 섞이지 않게 */
        @Override
        public String toString() {
            return "ViewDefinitionText[tableId=" + tableId + ", length=" + length + "]";
        }
    }

    /** 정의 원문 확인 이력 (원문 없음) */
    public record ViewDefinitionAccess(Long id, String accountId, java.time.LocalDateTime accessedAt) {
    }
}
