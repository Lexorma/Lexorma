package com.lexorma.data.mapping.domain.dto;

import java.math.BigDecimal;

/** 논리·물리 정합성 비교 (M-7 2차, 읽기 전용). 조회할 때 계산하고 저장하지 않는다 */
public final class ModelMappingQualityDto {

    private ModelMappingQualityDto() {
    }

    /** 진단 유형. 선언 순서가 한 속성 안의 표시 순서다 */
    public enum CheckType {
        /** 엔터티 ↔ 테이블, 속성 ↔ 컬럼 매핑이 있는지 (없을 때만 결과가 나온다) */
        MAPPING,
        /** 양쪽 표준 용어 참조 (ID) */
        TERM,
        /** 컬럼명 = 논리 속성의 표준 용어 영문약어 */
        NAME,
        /** 논리 속성의 표준 도메인 타입 ↔ 컬럼 타입 */
        DATA_TYPE,
        /** 논리 속성의 표준 도메인 길이·정밀도·소수 자릿수 ↔ 컬럼 */
        LENGTH,
        /** 논리 필수 여부 ↔ 컬럼 NOT NULL */
        REQUIRED,
        /** 논리 주 식별자 ↔ 컬럼 PK */
        IDENTIFIER,
        /**
         * 상속 구조 (M-7 4차) : 매핑된 서브타입 1개의 상속 PK·FK 물리 구조 (엔터티 단위 1행 — 기존 부모 재사용과 같은 판정) + 지금 유효하지 않은 기존 상속 PK 매핑 (매핑마다 1행, MISMATCH).
         * 상속 PK 연결이 일부·없음 = UNMAPPED, 구조 불일치 = MISMATCH, 상위 정보 부족 = NOT_COMPARABLE. 일반 엔터티·루트에는 행이 없다 (대상 아님)
         */
        INHERITANCE,
        /** (M-7 구조 정합성) 논리 관계 ↔ 물리 FK : 관계마다 1행 (자식 엔터티의 행) */
        RELATIONSHIP,
        /** (M-7 구조 정합성) 논리 보조 식별자 ↔ 물리 UNIQUE : 보조 식별자마다 1행 */
        ALTERNATE_KEY,
        /** (M-7 구조 정합성, 물리 기준 역방향) 대응 논리 관계가 없거나 찾을 수 없는 물리 FK (상속 FK 제외 — INHERITANCE 가 본다) */
        PHYSICAL_FK,
        /** (M-7 구조 정합성, 물리 기준 역방향) 대응 보조 식별자가 없거나 찾을 수 없는 물리 UNIQUE */
        PHYSICAL_UNIQUE;

        /** 구조 진단 유형 (목록 기본 조회에는 들어가지 않는다 — group 또는 type 으로 고를 때만) */
        public boolean structure() {
            return this == RELATIONSHIP || this == ALTERNATE_KEY || this == PHYSICAL_FK || this == PHYSICAL_UNIQUE;
        }
    }

    /**
     * 진단 범위 (M-7 구조 정합성, 목록 조건 group). 없으면 예전 목록(속성·컬럼 진단 + 상속 구조) 그대로 — 기존 요약 건수·목록 계약을 바꾸지 않는다.
     * ATTRIBUTE = MAPPING~IDENTIFIER, INHERITANCE, RELATIONSHIP, ALTERNATE_KEY, PHYSICAL_ONLY = PHYSICAL_FK + PHYSICAL_UNIQUE, ALL = 전부
     */
    public enum CheckGroup {
        ATTRIBUTE, INHERITANCE, RELATIONSHIP, ALTERNATE_KEY, PHYSICAL_ONLY, ALL;

        public boolean includes(CheckType type) {
            return switch (this) {
                case ALL -> true;
                case ATTRIBUTE -> !type.structure() && type != CheckType.INHERITANCE;
                case INHERITANCE -> type == CheckType.INHERITANCE;
                case RELATIONSHIP -> type == CheckType.RELATIONSHIP;
                case ALTERNATE_KEY -> type == CheckType.ALTERNATE_KEY;
                case PHYSICAL_ONLY -> type == CheckType.PHYSICAL_FK || type == CheckType.PHYSICAL_UNIQUE;
            };
        }
    }

    /**
     * 구조 진단 상세 (RELATIONSHIP · ALTERNATE_KEY · PHYSICAL_FK · PHYSICAL_UNIQUE 행에만, 나머지는 null).
     * code : 상세 사유 코드 (FK_MATCH, FK_MISSING, FK_ORDER, FK_TARGET, FK_COLUMNS, FK_AMBIGUOUS, FK_SHARED, FK_NOT_EXPECTED, PAIRS_MISSING, PAIRS_INCOMPLETE, PAIRS_INVALID,
     * PARENT_KEY_MISSING, ENTITY_UNMAPPED, ATTRIBUTE_UNMAPPED, UNIQUE_MATCH, UNIQUE_MISSING, UNIQUE_ORDER, UNIQUE_COLUMNS_MISSING, UNIQUE_COLUMNS_EXTRA, UNIQUE_COLUMNS_DIFFER,
     * UNIQUE_AMBIGUOUS, ALTERNATE_KEY_IS_PRIMARY_KEY, PHYSICAL_ONLY_FK, FK_PARENT_UNMAPPED, PHYSICAL_ONLY_UNIQUE, UNIQUE_COLUMN_UNMAPPED).
     * logical* : 논리 관계 / 보조 식별자, parent* : 관계의 부모 엔터티·테이블 (FK 는 참조받는 쪽), physical* : 대응한 FK(관계 ID) / UNIQUE(제약조건 ID 또는 컬럼 단위 고유값의 컬럼 ID),
     * columns : 순서대로의 컬럼 구성 (복합 키 전체), candidates : 대응을 정하지 못한 후보 FK·UNIQUE
     */
    public record Structure(CheckType kind, String code, Long logicalId, String logicalName, Long parentEntityId, String parentEntityName,
                            Long parentTableId, String parentTableName, Long physicalId, String physicalName, java.util.List<StructureColumn> columns,
                            java.util.List<String> candidates, String referenceKey, Long referenceKeyId, String referenceKeyName) {
        /** referenceKey (관계 행만, M7-AK-D09) : 짝이 가리키는 부모 식별자 PRIMARY · ALTERNATE (해석되지 않으면 null), referenceKeyId·Name : 그 식별자 */
        public Structure(CheckType kind, String code, Long logicalId, String logicalName, Long parentEntityId, String parentEntityName,
                         Long parentTableId, String parentTableName, Long physicalId, String physicalName, java.util.List<StructureColumn> columns,
                         java.util.List<String> candidates) {
            this(kind, code, logicalId, logicalName, parentEntityId, parentEntityName, parentTableId, parentTableName, physicalId, physicalName, columns, candidates,
                    null, null, null);
        }
    }

    /**
     * 구성 1자리 (order 1부터). FK : logicalAttribute = 자식 속성, parentAttribute = 부모 속성, expected* = 매핑으로 정한 자식·부모 컬럼, physical* = 그 자리의 FK 자식·부모 컬럼.
     * UNIQUE : logicalAttribute = 보조 식별자 속성, expectedColumn = 매핑 컬럼, physicalColumn = 그 자리의 UNIQUE 컬럼. 역방향 행은 physical* 과 매핑된 속성 이름만 있다
     */
    public record StructureColumn(int order, Long logicalAttributeId, String logicalAttributeName, Long parentAttributeId, String parentAttributeName,
                                  Long expectedColumnId, String expectedColumnName, Long expectedParentColumnId, String expectedParentColumnName,
                                  Long physicalColumnId, String physicalColumnName, Long physicalParentColumnId, String physicalParentColumnName) {
    }

    /** 구조 진단 건수 (진단 범위별) */
    public record Counts(long total, long match, long mismatch, long notComparable, long unmapped) {
    }

    /** 구조 진단 요약 (M-7 구조 정합성) : 기존 요약의 match·mismatch·notComparable·unmapped 에 들어가지 않는다 (그 건수의 뜻을 바꾸지 않는다) */
    public record StructureSummary(Counts relationship, Counts alternateKey, Counts physicalOnly, long physicalForeignKeys, long physicalUniques) {
    }

    public enum CheckStatus {
        MATCH,
        MISMATCH,
        /** 비교 기준이 되는 정보가 없다. 불일치가 아니다 */
        NOT_COMPARABLE,
        /** 매핑이 없다. 속성 불일치와 따로 센다 */
        UNMAPPED
    }

    /**
     * 진단에 쓰는 1행 = 엔터티의 속성 1개 (속성이 없는 엔터티는 속성 값이 null 인 1행).
     * 매핑·표준 용어·도메인이 없으면 해당 값은 null 이다. 도메인은 논리 속성의 표준 용어의 도메인이다.
     */
    public record Source(Long modelId, String modelName, Long entityId, String entityName, Long mappingId,
                         Long schemaId, String schemaName, Long tableId, String tableName,
                         Long attributeId, String attributeName, Boolean attributeRequired,
                         Long attributeTermId, String attributeTermName, String attributeTermAbbreviation,
                         Long domainId, String domainName, String domainDataType, Integer domainLength, Integer domainDecimalLength,
                         Long attributeMappingId, Long columnId, String columnName,
                         String columnDataType, Integer columnMaxLength, BigDecimal columnPrecision, Integer columnScale,
                         Boolean columnNullable, Boolean columnRequired, Boolean columnPk,
                         Long columnTermId, String columnTermName) {
    }

    /**
     * 진단 결과 1건 = 매핑된 속성 1개의 진단 유형 1개, 또는 매핑하지 않은 엔터티·속성 1개 (MAPPING / UNMAPPED).
     * 엔터티 단위 행은 attributeId 가 null 이다. id 는 그리드 행 키. reason 은 일치일 때 null
     */
    /**
     * inheritance (M-6 후속 5차, D-48) : 엔터티가 슈퍼타입·서브타입 구조에 참여한다. 이 행의 판정은 직접 정의 속성 ↔ 매핑 컬럼만 본 것이고(판정은 그대로),
     * 상속 구조 전체(상속 속성·상속 주 식별자 ↔ 물리 PK·FK)의 물리 정합성은 검증하지 않았다는 뜻이다
     */
    public record Row(String id, Long modelId, String modelName, Long mappingId, Long entityId, String entityName,
                      Long attributeId, String attributeName,
                      Long schemaId, String schemaName, Long tableId, String tableName, Long columnId, String columnName,
                      CheckType type, CheckStatus status, String logicalValue, String physicalValue, String reason, boolean inheritance, Structure structure) {
        public Row(String id, Long modelId, String modelName, Long mappingId, Long entityId, String entityName,
                   Long attributeId, String attributeName,
                   Long schemaId, String schemaName, Long tableId, String tableName, Long columnId, String columnName,
                   CheckType type, CheckStatus status, String logicalValue, String physicalValue, String reason, boolean inheritance) {
            this(id, modelId, modelName, mappingId, entityId, entityName, attributeId, attributeName, schemaId, schemaName, tableId, tableName, columnId, columnName,
                    type, status, logicalValue, physicalValue, reason, inheritance, null);
        }

        public Row(String id, Long modelId, String modelName, Long mappingId, Long entityId, String entityName,
                   Long attributeId, String attributeName,
                   Long schemaId, String schemaName, Long tableId, String tableName, Long columnId, String columnName,
                   CheckType type, CheckStatus status, String logicalValue, String physicalValue, String reason) {
            this(id, modelId, modelName, mappingId, entityId, entityName, attributeId, attributeName, schemaId, schemaName, tableId, tableName, columnId, columnName,
                    type, status, logicalValue, physicalValue, reason, false, null);
        }

        public Row withInheritance(boolean value) {
            return new Row(id, modelId, modelName, mappingId, entityId, entityName, attributeId, attributeName, schemaId, schemaName, tableId, tableName, columnId, columnName,
                    type, status, logicalValue, physicalValue, reason, value, structure);
        }
    }

    /**
     * 모델 1개의 요약. 엔터티·속성 수는 매핑 현황(ModelMappingDto.Status)과 같은 값이다.
     * match·mismatch·notComparable 은 매핑된 속성의 진단 건수(속성 × 진단 유형), unmapped = unmappedEntities + unmappedAttributes
     */
    /** inheritanceEntities (M-6 후속 5차, D-48) : 슈퍼타입·서브타입 구조에 참여하는 엔터티 수 — 그 엔터티들의 상속 구조 전체 물리 정합성은 검증하지 않는다 */
    public record Summary(Long modelId, String modelName, long totalEntities, long mappedEntities, long unmappedEntities,
                          long totalAttributes, long mappedAttributes, long unmappedAttributes,
                          long match, long mismatch, long notComparable, long unmapped, long inheritanceEntities, InheritanceSummary inheritance,
                          StructureSummary structure) {
        public Summary(Long modelId, String modelName, long totalEntities, long mappedEntities, long unmappedEntities,
                       long totalAttributes, long mappedAttributes, long unmappedAttributes,
                       long match, long mismatch, long notComparable, long unmapped, long inheritanceEntities, InheritanceSummary inheritance) {
            this(modelId, modelName, totalEntities, mappedEntities, unmappedEntities, totalAttributes, mappedAttributes, unmappedAttributes, match, mismatch, notComparable, unmapped,
                    inheritanceEntities, inheritance, null);
        }

        public Summary(Long modelId, String modelName, long totalEntities, long mappedEntities, long unmappedEntities,
                       long totalAttributes, long mappedAttributes, long unmappedAttributes,
                       long match, long mismatch, long notComparable, long unmapped) {
            this(modelId, modelName, totalEntities, mappedEntities, unmappedEntities, totalAttributes, mappedAttributes, unmappedAttributes, match, mismatch, notComparable, unmapped, 0, null, null);
        }
    }

    /**
     * 상속 PK 현황 (M-7 4차). 직접 속성 매핑률(mappedAttributes / totalAttributes)과 따로 센다 — 상속 PK 는 그 분자·분모에 들어가지 않는다.
     * subtypes : 모델의 서브타입 엔터티 수, mappedSubtypes : 그중 테이블에 매핑된 수(상속 PK 매핑이 가능한 것), requiredKeys : 매핑된 서브타입의 루트 주 식별자 수 합,
     * mappedKeys : 그중 상속 PK 매핑된 수(≤ requiredKeys), unmappedKeys = requiredKeys - mappedKeys, invalidMappings : 유효하지 않은 기존 상속 PK 매핑 수 (mappedKeys 에 들어가지 않는다),
     * valid·mismatch·unmapped·notComparable : 매핑된 서브타입의 구조 판정 수 (합 = mappedSubtypes). 루트·일반 엔터티는 대상이 아니라 세지 않는다
     */
    public record InheritanceSummary(long subtypes, long mappedSubtypes, long requiredKeys, long mappedKeys, long unmappedKeys, long invalidMappings,
                                     long valid, long mismatch, long unmapped, long notComparable) {
    }

    /** 진단 목록 조건. modelId 는 필수. searchColumn : entityName / attributeName / columnName. group : 진단 범위 (없으면 예전 목록) */
    public record Search(Long modelId, Long entityId, CheckType type, CheckStatus status, String searchColumn, String searchQuery, CheckGroup group) {
        public Search(Long modelId, Long entityId, CheckType type, CheckStatus status, String searchColumn, String searchQuery) {
            this(modelId, entityId, type, status, searchColumn, searchQuery, null);
        }

        /** 구조 진단(관계·보조 식별자·물리 전용)을 계산해야 하는가 */
        public boolean wantsStructure() {
            return (group != null && group != CheckGroup.ATTRIBUTE && group != CheckGroup.INHERITANCE) || (type != null && type.structure());
        }
    }
}
