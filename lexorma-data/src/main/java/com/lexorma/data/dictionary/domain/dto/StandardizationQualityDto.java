package com.lexorma.data.dictionary.domain.dto;

import com.lexorma.data.dictionary.quality.StandardizationRule;

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
        DOMAIN_DEFINITION_MISMATCH;

        /** 같은 이름의 표준화 규칙 (규칙 목록에 연결, 응답 값은 그대로) */
        public StandardizationRule rule() {
            return StandardizationRule.valueOf(name());
        }
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

    // ------------------------------------------------------------------ 모델 품질 진단 (컬럼 + 테이블, M-5 잔여)

    /** 테이블명 진단에 쓰는 테이블 1행 (VIEW 는 대상이 아니다 — D9) */
    public record TableRow(Long tableId, Long schemaId, String schemaName, String tableName, String tableKorName, Boolean isApproval) {
    }

    /** 완전성 판정용 테이블 1개 (AI-6 3차) : 테이블 코멘트(TBL_KORN_NM)·업무 설명(TASK_EXPLN) */
    public record CompletenessTable(Long tableId, String schemaName, String tableName, String tableKorName, String bizDescription) {
    }

    /** 완전성 판정용 컬럼 1개 (AI-6 3차) : 컬럼 설명(COL_EXPLN — DDL COMMENT)·업무 설명(TASK_EXPLN) */
    public record CompletenessColumn(Long columnId, Long tableId, String schemaName, String tableName, String columnName, String columnDesc,
                                     String bizDescription) {
    }

    /** 표준 단어의 영문약어와 승인 여부 (테이블명 약어 판정) */
    public record WordAbbreviation(String abbreviation, Boolean isApproval) {
    }

    /** 테이블명 약어 하나의 판정 : 승인된 단어 / 미승인 단어만 있음 / 등록된 단어 없음 / 빈 약어('__', 앞뒤 '_') */
    public enum TokenStatus { APPROVED, NOT_APPROVED, NOT_FOUND, EMPTY }

    public record NameToken(String token, TokenStatus status) {
    }

}
