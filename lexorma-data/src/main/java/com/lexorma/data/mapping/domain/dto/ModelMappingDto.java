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
                            Long attributeCount, Long mappedAttributeCount) {
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

    // ------------------------------------------------------------------ 상속 PK 매핑 (M-7 3차, docs/ongoing/subtype-physical-transformation.md 30장)

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
    public record SubtypeKeyRow(Long id, int order, boolean current, Long attributeId, String attributeName, Long attributeEntityId, String attributeEntityName,
                                Long columnId, String columnName, String dataType, Integer maxLength, java.math.BigDecimal precision, Integer scale, Boolean isPk) {
    }

    /**
     * 서브타입 엔터티 매핑의 상속 PK 매핑과 정합성 (조회 때 계산, 저장하지 않는다). subtype false 면 지금은 서브타입이 아니다 (status NOT_SUBTYPE).
     * status : VALID 또는 사유 코드 (INHERITED_KEY_MAPPING_MISSING · PARENT_KEY_MISMATCH · INHERITED_KEY_TYPE_MISMATCH · INHERITANCE_FK_MISSING · ROOT_KEY_MISSING ·
     * ANCESTOR_MISSING · ROOT_KEY_MAPPING_MISSING · ANCESTOR_INVALID · NOT_SUBTYPE), complete : 루트 주 식별자 전체에 상속 PK 매핑이 있는지 (구조 판정과 별개)
     */
    public record SubtypeKeyDetail(EntityRow mapping, boolean subtype, Long parentEntityId, String parentEntityName, Long rootEntityId, String rootEntityName,
                                   String status, String statusMessage, boolean complete, List<SubtypeKeyRow> keys) {
    }
}
