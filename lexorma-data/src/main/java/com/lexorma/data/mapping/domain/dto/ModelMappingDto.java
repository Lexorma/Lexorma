package com.lexorma.data.mapping.domain.dto;

import java.util.List;

/** 논리·물리 매핑 조회·저장 DTO. 저장 요청은 id 가 없으면 등록, 있으면 변경이다 */
public final class ModelMappingDto {

    private ModelMappingDto() {
    }

    /** 엔터티 ↔ 테이블 매핑 저장. 변경(id 있음)은 tableId 만 바꾼다 (entityId 는 무시) */
    public record EntityMapping(Long id, Long entityId, Long tableId) {
    }

    /** 속성 ↔ 컬럼 매핑 저장. mappingId = 부모(엔터티 ↔ 테이블 매핑). 변경(id 있음)은 columnId 만 바꾼다 */
    public record AttributeMapping(Long id, Long mappingId, Long attributeId, Long columnId) {
    }

    /**
     * 엔터티 기준 매핑 목록 1행. 매핑하지 않은 엔터티는 mappingId·schema·table 이 null 이다.
     * 승인 상태는 표시만 한다 (매핑은 승인 상태를 바꾸지 않는다).
     */
    public record EntityRow(Long mappingId, Long modelId, String modelName, Long entityId, String entityName, Boolean entityApproval,
                            Long schemaId, String schemaName, Long tableId, String tableName, Boolean tableApproval,
                            Long attributeCount, Long mappedAttributeCount, Inheritance inheritance) {
        /** 조회 투영 (상속 요약은 목록 서비스가 페이지 단위로 채운다 — M-7 4차) */
        public EntityRow(Long mappingId, Long modelId, String modelName, Long entityId, String entityName, Boolean entityApproval,
                         Long schemaId, String schemaName, Long tableId, String tableName, Boolean tableApproval,
                         Long attributeCount, Long mappedAttributeCount) {
            this(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    attributeCount, mappedAttributeCount, null);
        }

        public EntityRow withInheritance(Inheritance value) {
            return new EntityRow(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    attributeCount, mappedAttributeCount, value);
        }
    }

    /**
     * 엔터티의 상속 역할과 상속 PK 매핑 현황 (M-7 4차, 조회 때 계산 — 저장하지 않는다). 상속 구조에 참여하지 않고 남은 상속 PK 매핑도 없으면 null (일반 엔터티).
     * supertype : 서브타입 그룹이 있다(여러 그룹이어도 true), subtype : 직계 슈퍼타입이 있다 — 둘 다면 중간 엔터티. role : ROOT · INTERMEDIATE · SUBTYPE (참여하지 않으면 null).
     * 직접 속성 매핑 수(attributeCount·mappedAttributeCount)와 따로 센다 : requiredKeys = 지금 루트 주 식별자 수(서브타입만), mappedKeys = 그중 상속 PK 매핑된 수(≤ requiredKeys),
     * invalidKeys = 지금 루트 주 식별자가 아닌 속성의 기존 매핑(또는 서브타입이 아닌데 남은 매핑) 수.
     * status : 매핑된 서브타입의 구조 판정 (VALID · 사유 코드 · NOT_SUBTYPE), result : 정합성 비교 상태 (MATCH · MISMATCH · UNMAPPED · NOT_COMPARABLE) — 매핑 없는 엔터티는 둘 다 null
     */
    public record Inheritance(String role, boolean supertype, boolean subtype, Long parentEntityId, String parentEntityName, Long rootEntityId, String rootEntityName,
                              int requiredKeys, int mappedKeys, int invalidKeys, String status, String result) {
    }

    /**
     * 엔터티 목록 조건. mapped : true 매핑된 엔터티만 / false 매핑 안 된 엔터티만(매핑 후보) / null 전체. 검색 : entityName / tableName.
     * entityId : 그 엔터티 1건만 (다른 화면에서 넘어온 엔터티 — 이름 검색으로 대신하지 않는다)
     */
    public record EntitySearch(Long modelId, Long schemaId, Boolean mapped, String searchColumn, String searchQuery, Long entityId) {
        public EntitySearch(Long modelId, Long schemaId, Boolean mapped, String searchColumn, String searchQuery) {
            this(modelId, schemaId, mapped, searchColumn, searchQuery, null);
        }
    }

    /** 매핑된 엔터티의 속성 1행. 매핑하지 않은 속성은 mappingId·column 이 null 이다. 표준 용어는 비교용 조회 값이다 */
    public record AttributeRow(Long mappingId, Long attributeId, String attributeName, Long attributeTermId, String attributeTermName,
                               Long columnId, String columnName, Long columnTermId, String columnTermName) {
    }

    /** 매핑 후보 컬럼 : 매핑된 테이블의 컬럼 중 아직 속성에 연결하지 않은 것 */
    public record ColumnCandidate(Long columnId, String columnName, String columnDesc, Long termId, String termName) {
    }

    public record Detail(EntityRow mapping, List<AttributeRow> attributes, List<ColumnCandidate> unmappedColumns) {
    }

    /** 매핑 후보 테이블 : 아직 엔터티에 연결하지 않은 테이블 */
    public record TableCandidate(Long tableId, Long schemaId, String schemaName, String tableName, String tableKorName, Boolean isApproval) {
    }

    /** 논리 모델별 매핑 현황. 미매핑 = 전체 - 매핑 */
    public record Status(Long modelId, String modelName, Long totalEntities, Long mappedEntities, Long unmappedEntities,
                         Long totalAttributes, Long mappedAttributes, Long unmappedAttributes) {
        public Status(Long modelId, String modelName, Long totalEntities, Long mappedEntities, Long totalAttributes, Long mappedAttributes) {
            this(modelId, modelName, totalEntities, mappedEntities, totalEntities - mappedEntities,
                    totalAttributes, mappedAttributes, totalAttributes - mappedAttributes);
        }
    }

    // ------------------------------------------------------------------ 상속 PK 매핑 (M-7 3차, docs/completed/subtype-physical-transformation.md 30장)

    /** 상속 PK 매핑 저장. mappingId = 서브타입의 엔터티 ↔ 테이블 매핑, attributeId = 루트 주 식별자 속성(원본). 변경(id 있음)은 columnId 만 바꾼다 */
    public record SubtypeKeyMapping(Long id, Long mappingId, Long attributeId, Long columnId) {
    }

    /** 상속 PK 매핑 후보 컬럼 : 매핑된 테이블의 컬럼 중 속성 매핑·다른 상속 PK 매핑에 쓰이지 않은 것 (PK·타입은 보여만 준다) */
    public record KeyColumnCandidate(Long columnId, String columnName, String columnDesc, String dataType, Integer maxLength, java.math.BigDecimal precision, Integer scale,
                                     Boolean isPk, Boolean isNullable, Long termId, String termName) {
    }

    /**
     * 상속 PK 매핑 1행. 지금의 루트 주 식별자 속성마다 1행(순서대로 — 매핑이 없으면 id·column 이 null), 그 뒤에 지금 주 식별자에 없는 속성의 기존 매핑(current false).
     * current : 지금 루트 주 식별자에 있는 속성인지 (아니면 정합하지 않은 기존 매핑 — 숨기지 않고 해제할 수 있다)
     */
    /**
     * check (M-7 4차) : 이 키의 점검 — OK · UNMAPPED · OTHER_TABLE · NOT_PK · TYPE_MISMATCH(상위 키와 FK 로 묶일 수 없음) · LENGTH_DIFFERS(길이만 다름) · STALE(current false).
     * 상위 키를 모르면 타입은 보지 않는다. attributeTermName·attributeDataType : 원본 속성의 표준 용어와 그 도메인 타입(없으면 속성의 데이터 유형), notNull : DDL 기준(PK·필수·NULL 비허용)
     */
    public record SubtypeKeyRow(Long id, int order, boolean current, Long attributeId, String attributeName, Long attributeEntityId, String attributeEntityName,
                                Long columnId, String columnName, String dataType, Integer maxLength, java.math.BigDecimal precision, Integer scale, Boolean isPk,
                                String attributeTermName, String attributeDataType, Boolean notNull, String parentColumnName, String check) {
    }

    /**
     * 서브타입 엔터티 매핑의 상속 PK 매핑과 정합성 (조회 때 계산, 저장하지 않는다). subtype false 면 지금은 서브타입이 아니다 (status NOT_SUBTYPE).
     * status : VALID 또는 사유 코드 (INHERITED_KEY_MAPPING_MISSING · PARENT_KEY_MISMATCH · INHERITED_KEY_TYPE_MISMATCH · INHERITANCE_FK_MISSING · ROOT_KEY_MISSING ·
     * ANCESTOR_MISSING · ROOT_KEY_MAPPING_MISSING · ANCESTOR_INVALID · NOT_SUBTYPE), complete : 루트 주 식별자 전체에 상속 PK 매핑이 있는지 (구조 판정과 별개)
     */
    public record SubtypeKeyDetail(EntityRow mapping, boolean subtype, Long parentEntityId, String parentEntityName, Long rootEntityId, String rootEntityName,
                                   String status, String statusMessage, boolean complete, List<SubtypeKeyRow> keys, Structure structure) {
        public SubtypeKeyDetail(EntityRow mapping, boolean subtype, Long parentEntityId, String parentEntityName, Long rootEntityId, String rootEntityName,
                                String status, String statusMessage, boolean complete, List<SubtypeKeyRow> keys) {
            this(mapping, subtype, parentEntityId, parentEntityName, rootEntityId, rootEntityName, status, statusMessage, complete, keys, null);
        }
    }

    /**
     * 상속 물리 구조 상세 (M-7 4차, 상세를 열 때만 계산). status 와 같은 판정을 항목별로 나눈 것 — result 는 정합성 비교 상태.
     * tableKey : 지금 테이블 PK (컬럼 순서), parentKey : 직계 상위 테이블의 키(상위가 유효할 때만 — 루트 주 식별자 순서), foreignKeys : 이 테이블에서 나가는 FK 전체
     */
    public record Structure(String result, Long tableId, String schemaName, String tableName, Long parentMappingId, Long parentTableId,
                            String parentSchemaName, String parentTableName, List<KeyColumn> tableKey, List<KeyColumn> parentKey, List<ForeignKey> foreignKeys,
                            List<StructureCheck> checks, String constraintNote) {
    }

    public record KeyColumn(Long columnId, String columnName, String dataType, boolean pk, boolean notNull) {
    }

    /** parentLink : 직계 상위 테이블을 상속 PK 그대로 참조하는 FK 인지 */
    public record ForeignKey(Long relationId, String relationName, String source, Long targetTableId, String targetTableName, List<String> childColumns,
                             List<String> parentColumns, boolean parentLink) {
    }

    /** level : OK · ERROR · MISSING · UNKNOWN · WARNING */
    public record StructureCheck(String code, String level, String message) {
    }
}
