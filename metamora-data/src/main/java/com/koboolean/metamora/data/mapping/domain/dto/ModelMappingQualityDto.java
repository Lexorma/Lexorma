package com.koboolean.metamora.data.mapping.domain.dto;

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
        IDENTIFIER
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
    public record Row(String id, Long modelId, String modelName, Long mappingId, Long entityId, String entityName,
                      Long attributeId, String attributeName,
                      Long schemaId, String schemaName, Long tableId, String tableName, Long columnId, String columnName,
                      CheckType type, CheckStatus status, String logicalValue, String physicalValue, String reason) {
    }

    /**
     * 모델 1개의 요약. 엔터티·속성 수는 매핑 현황(ModelMappingDto.Status)과 같은 값이다.
     * match·mismatch·notComparable 은 매핑된 속성의 진단 건수(속성 × 진단 유형), unmapped = unmappedEntities + unmappedAttributes
     */
    public record Summary(Long modelId, String modelName, long totalEntities, long mappedEntities, long unmappedEntities,
                          long totalAttributes, long mappedAttributes, long unmappedAttributes,
                          long match, long mismatch, long notComparable, long unmapped) {
    }

    /** 진단 목록 조건. modelId 는 필수. searchColumn : entityName / attributeName / columnName */
    public record Search(Long modelId, Long entityId, CheckType type, CheckStatus status, String searchColumn, String searchQuery) {
    }
}
