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
                            Long attributeCount, Long mappedAttributeCount, Inheritance inheritance, Physical physical) {
        public EntityRow(Long mappingId, Long modelId, String modelName, Long entityId, String entityName, Boolean entityApproval,
                         Long schemaId, String schemaName, Long tableId, String tableName, Boolean tableApproval,
                         Long attributeCount, Long mappedAttributeCount, Inheritance inheritance) {
            this(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    attributeCount, mappedAttributeCount, inheritance, null);
        }

        /** 조회 투영 (상속 요약은 목록 서비스가 페이지 단위로 채운다 — M-7 4차) */
        public EntityRow(Long mappingId, Long modelId, String modelName, Long entityId, String entityName, Boolean entityApproval,
                         Long schemaId, String schemaName, Long tableId, String tableName, Boolean tableApproval,
                         Long attributeCount, Long mappedAttributeCount) {
            this(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    attributeCount, mappedAttributeCount, null);
        }

        public EntityRow withInheritance(Inheritance value) {
            return new EntityRow(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    attributeCount, mappedAttributeCount, value, physical);
        }

        /** A형 루트 : 통합 테이블에 들어간 계층 전체의 직접 속성 수 (통합 속성 매핑이 루트 매핑 아래에 있으므로 분모도 같은 범위) */
        public EntityRow withAttributeCount(Long value) {
            return new EntityRow(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    value, mappedAttributeCount, inheritance, physical);
        }

        public EntityRow withPhysical(Physical value) {
            return new EntityRow(mappingId, modelId, modelName, entityId, entityName, entityApproval, schemaId, schemaName, tableId, tableName, tableApproval,
                    attributeCount, mappedAttributeCount, inheritance, value);
        }
    }

    /**
     * 엔터티의 물리 매핑 문맥 (M-7 상속 A·C, D-AC-16 — 조회 때 계산). kind : DIRECT(직접 매핑) · MERGED(A형 통합 — 루트 통합 테이블) · DISTRIBUTED(C형 분산 — 말단 테이블들) · UNMAPPED.
     * strategy : SINGLE_TABLE · TABLE_PER_CONCRETE (A·C 계층만, 아니면 null), valid : 통합·분산이 실제 매핑으로 성립하는지 (A형 = 루트가 기록의 통합 테이블에 매핑, C형 = 아래 말단이 모두 매핑 —
     * 아니면 미매핑으로 센다). tables : 통합 테이블(1) 또는 분산 엔터티의 말단 테이블 (이동용 — 대표 하나를 고르지 않는다), reason : 성립하지 않는 사유
     */
    public record Physical(String kind, String strategy, boolean valid, Long rootEntityId, String rootEntityName, List<PhysicalTable> tables, String reason) {
    }

    public record PhysicalTable(Long entityId, String entityName, Long mappingId, Long schemaId, Long tableId, String tableName) {
    }

    /** C형 상속 일반 속성 매핑 저장. mappingId = 말단 엔터티 매핑, attributeId = 조상 일반 속성(원본). 변경(id 있음)은 columnId 만 바꾼다 */
    public record SubtypeAttributeMapping(Long id, Long mappingId, Long attributeId, Long columnId) {
    }

    /**
     * C형 상속 일반 속성 매핑 1행 (조상 일반 속성마다 — 루트 → 직계 부모 순서, 그 뒤 지금 조상 속성이 아닌 기존 매핑(current false)). check : OK · UNMAPPED · OTHER_TABLE · STALE
     */
    public record SubtypeAttributeRow(Long id, boolean current, Long attributeId, String attributeName, Long ownerEntityId, String ownerEntityName, Long columnId, String columnName,
                                      String dataType, Integer maxLength, String attributeTermName, String attributeDataType, Boolean required, Boolean notNull, String check) {
    }

    /** C형 말단 매핑의 상속 일반 속성 매핑 상세. concrete false 면 지금 C형 계층의 말단이 아니다 (기존 매핑은 해제만) */
    public record SubtypeAttributeDetail(EntityRow mapping, boolean concrete, String strategy, List<SubtypeAttributeRow> attributes, String message) {
    }

    /** 변환 기록 1행 */
    public record Conversion(Long id, Long groupId, String groupName, Long supertypeEntityId, String supertypeEntityName, String strategy, Long tableId, String tableName,
                             Long classificationColumnId, String classificationColumnName) {
    }

    /**
     * 상속 계층 하나의 변환 기록 (D-AC-18). kind : SINGLE_TABLE · TABLE_PER_CONCRETE · INCONSISTENT. dependencies : 기록 삭제를 막는 매핑 (엔터티 매핑·속성 매핑·상속 PK·상속 일반 속성 —
     * 먼저 해제한다), deletable : dependencies 가 없다
     */
    public record ConversionTree(Long rootEntityId, String rootEntityName, String kind, List<Conversion> conversions, List<String> dependencies, boolean deletable) {
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
                               Long columnId, String columnName, Long columnTermId, String columnTermName, Long ownerEntityId, String ownerEntityName) {
        public AttributeRow(Long mappingId, Long attributeId, String attributeName, Long attributeTermId, String attributeTermName,
                            Long columnId, String columnName, Long columnTermId, String columnTermName) {
            this(mappingId, attributeId, attributeName, attributeTermId, attributeTermName, columnId, columnName, columnTermId, columnTermName, null, null);
        }
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
    /**
     * 매핑률 (D-AC-16) : (직접 매핑 + 유효한 통합 매핑) ÷ (전체 - 유효한 분산 엔터티). mappedEntities = 직접 + 통합, totalEntities = 전체 - 분산, unmapped = 그 차.
     * directEntities·mergedEntities·distributedEntities·logicalEntities 는 구분 수 (logicalEntities = 전체 논리 엔터티). 속성도 같은 규칙 (분산 엔터티의 속성은 분모에서 뺀다)
     */
    public record Status(Long modelId, String modelName, Long totalEntities, Long mappedEntities, Long unmappedEntities,
                         Long totalAttributes, Long mappedAttributes, Long unmappedAttributes, Long logicalEntities, Long directEntities, Long mergedEntities,
                         Long distributedEntities) {
        public Status(Long modelId, String modelName, Long totalEntities, Long mappedEntities, Long totalAttributes, Long mappedAttributes) {
            this(modelId, modelName, totalEntities, mappedEntities, totalEntities - mappedEntities,
                    totalAttributes, mappedAttributes, totalAttributes - mappedAttributes, totalEntities, mappedEntities, 0L, 0L);
        }

        public Status(Long modelId, String modelName, Long totalEntities, Long mappedEntities, Long unmappedEntities,
                      Long totalAttributes, Long mappedAttributes, Long unmappedAttributes) {
            this(modelId, modelName, totalEntities, mappedEntities, unmappedEntities, totalAttributes, mappedAttributes, unmappedAttributes, totalEntities, mappedEntities, 0L, 0L);
        }

        /** 통합·분산 반영 (분산 엔터티 수·그 속성 수를 분모에서 빼고, 통합 엔터티를 매핑에 더한다) */
        public Status withStrategies(long merged, long distributed, long distributedAttributes) {
            long total = logicalEntities - distributed;
            long mapped = directEntities + merged;
            long attributes = totalAttributes - distributedAttributes;
            return new Status(modelId, modelName, total, mapped, total - mapped, attributes, mappedAttributes, attributes - mappedAttributes, logicalEntities, directEntities,
                    merged, distributed);
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
    /** detail : 기계 판정용 상세 코드 (M7-INH-03 — PARENT_FK 의 INHERITANCE_FK_MISSING·_COLUMNS·_ORDER·_TARGET·_DUPLICATE 등, 없으면 null). 화면은 문구를 해석하지 않는다 */
    public record StructureCheck(String code, String level, String message, String detail) {
        public StructureCheck(String code, String level, String message) {
            this(code, level, message, null);
        }
    }
}
