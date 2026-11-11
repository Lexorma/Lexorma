package com.koboolean.metamora.data.dictionary.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/** 표준화 품질 진단 (M-5 2차, 읽기 전용). 조회할 때 계산하고 저장하지 않는다 */
public final class StandardizationQualityDto {

    private StandardizationQualityDto() {
    }

    public enum IssueType {
        /** 컬럼이 표준 용어를 참조하지 않는다 */
        TERM_NOT_LINKED,
        /** 컬럼명이 연결된 표준 용어의 영문약어와 다르다 */
        COLUMN_NAME_MISMATCH,
        /** 컬럼의 타입·길이·정밀도·스케일이 연결된 표준 용어의 도메인 정의와 다르다 */
        DOMAIN_DEFINITION_MISMATCH
    }

    /** 도메인 정의와 다른 속성 */
    public enum Attribute {
        DATA_TYPE, LENGTH, PRECISION, SCALE
    }

    /** 진단에 쓰는 컬럼 1행 (컬럼 + 연결된 표준 용어·도메인). 용어·도메인이 없으면 해당 값은 null */
    public record ColumnRow(Long columnId, Long tableId, Long schemaId, String schemaName, String tableName, String tableKorName,
                            String columnName, String columnDesc, String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                            Integer sortOrder, Boolean isApproval,
                            Long termId, String termName, String termAbbreviation,
                            Long domainId, String domainName, String domainDataType, Integer domainLength, Integer domainDecimalLength) {
    }

    /**
     * 진단 결과 1건 = 컬럼 1개의 진단 유형 1개. id 는 그리드 행 키(유형:컬럼 ID).
     * attributes 는 도메인 정의 불일치일 때 다른 속성, 그 밖에는 빈 목록
     */
    public record Issue(String id, IssueType type, Long schemaId, String schemaName, Long tableId, String tableName, String tableKorName,
                        Long columnId, String columnName, String columnDesc, String termName, String domainName,
                        String actualValue, String expectedValue, List<Attribute> attributes, String message, Boolean isApproval) {
    }

    /** 문제 건수(유형별)와 문제가 있는 컬럼 수는 다르다 : 한 컬럼에 여러 문제가 있을 수 있다 */
    public record Summary(long totalColumns, long issueColumns, long termNotLinked, long columnNameMismatch, long domainDefinitionMismatch) {
    }

    /** 진단 목록 조건 (모두 선택). searchColumn : tableName / columnName */
    public record Search(IssueType type, Long schemaId, Boolean isApproval, String searchColumn, String searchQuery) {
    }
}
