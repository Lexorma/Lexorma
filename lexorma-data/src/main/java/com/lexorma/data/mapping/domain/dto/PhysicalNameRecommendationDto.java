package com.lexorma.data.mapping.domain.dto;

import com.lexorma.data.table.domain.dto.TableNameDto;

import java.util.List;

/** 표준 사전 기반 물리명 추천 (M-7 3차, 읽기 전용). 조회할 때 계산하고 저장하지 않으며, 테이블·컬럼의 이름을 바꾸지 않는다 */
public final class PhysicalNameRecommendationDto {

    private PhysicalNameRecommendationDto() {
    }

    public enum Status {
        /** 추천 이름이 있고 현재 이름과 다르다 (또는 매핑된 물리 객체가 없다) */
        AVAILABLE,
        /** 현재 물리명 = 추천명 (대소문자 무시). 바꿀 것이 없다 */
        ALREADY_MATCHED,
        /** 추천 근거가 없다 (표준 용어·영문약어 없음, 표준 단어로 나눌 수 없음). 이름을 만들지 않는다 */
        UNAVAILABLE,
        /** 추천명을 다른 물리 객체가 이미 쓰고 있다 (컬럼 : 같은 테이블, 테이블 : 같은 프로젝트) */
        CONFLICT
    }

    public enum Source {
        /** 논리 속성에 연결된 표준 용어의 영문약어 (컬럼명) */
        STANDARD_TERM,
        /** 엔터티명을 이루는 승인된 표준 단어의 영문약어 + 프로젝트 테이블 접두어 (테이블명) */
        STANDARD_WORD
    }

    /** 컬럼명 추천 원본 1행 = 논리 속성 1개. 매핑·표준 용어가 없으면 해당 값은 null */
    public record ColumnSource(Long modelId, String modelName, Long entityId, String entityName, Long mappingId,
                               Long schemaId, String schemaName, Long tableId, String tableName,
                               Long attributeId, String attributeName, Long termId, String termName, String termAbbreviation,
                               Long attributeMappingId, Long columnId, String columnName) {
    }

    /**
     * 컬럼명 추천 1건 = 논리 속성 1개. id 는 그리드 행 키.
     * recommendedPhysicalName 은 UNAVAILABLE 이면 null, currentPhysicalName·physicalColumnId 는 매핑된 컬럼이 없으면 null
     */
    public record ColumnRow(String id, Long modelId, String modelName, Long entityId, String entityName, Long mappingId,
                            Long schemaId, String schemaName, Long tableId, String tableName,
                            Long logicalAttributeId, String logicalName, Long termId, String termName, String termAbbreviation,
                            Long physicalColumnId, String currentPhysicalName, String recommendedPhysicalName,
                            Status status, Source source, String reason) {
    }

    /** 모델 1개의 컬럼명 추천 요약 : total = 모델의 속성 수 = 네 상태의 합 */
    public record ColumnSummary(Long modelId, long total, long available, long alreadyMatched, long unavailable, long conflict) {
    }

    /** 컬럼명 추천 조건. modelId 는 attributeId·columnId 가 없으면 필수. searchColumn : entityName / logicalName / currentPhysicalName / recommendedPhysicalName */
    public record ColumnSearch(Long modelId, Long entityId, Long attributeId, Long columnId, Status status, String searchColumn, String searchQuery) {
    }

    /**
     * 테이블명 추천 1건 = 논리 엔터티 1개. words 는 엔터티명을 나눈 단어별 표준 단어 변환 결과 (추천 근거),
     * tablePrefix 는 프로젝트 설정의 접두어 (없으면 null)
     */
    public record TableRow(String id, Long modelId, String modelName, Long logicalEntityId, String logicalName, Long mappingId,
                           Long schemaId, String schemaName, Long physicalTableId, String currentPhysicalName, String recommendedPhysicalName,
                           Status status, Source source, String reason, String tablePrefix, List<TableNameDto.Word> words) {
    }

    /** 테이블명 추천 조건. modelId 는 entityId 가 없으면 필수. searchColumn : entityName(logicalName) / tableName(currentPhysicalName) */
    public record TableSearch(Long modelId, Long entityId, String searchColumn, String searchQuery) {
    }
}
