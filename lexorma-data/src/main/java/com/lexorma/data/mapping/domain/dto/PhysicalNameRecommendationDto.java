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
        /**
         * 추천명을 다른 물리 객체가 이미 쓰고 있다 (컬럼 : 같은 테이블의 컬럼, 테이블 : 같은 스키마의 테이블·VIEW — 저장 경로 {@code ObjectNamespace} 와 같은 이름 공간).
         * 추천끼리의 충돌은 여기에 넣지 않는다 ({@link Applicability} 의 {@link ApplyCode#RECOMMENDATION_DUPLICATE})
         */
        CONFLICT
    }

    /**
     * 일괄 적용 판정 코드 (M-7 물리명 추천 일괄 적용 1차). 상태({@link Status})와 별개다 — 상태는 추천 자체, 이 코드는 "추천명으로 바꿔도 되는가".
     * {@link #WARNING_CODES}(권장 형식 아님·원본 매핑)만 경고(warnings)이고 나머지는 적용을 막는다(blockers).
     */
    public enum ApplyCode {
        /** 추천명이 없다 (상태 UNAVAILABLE) */
        NOT_RECOMMENDED,
        /** 현재 이름 = 추천명 (상태 ALREADY_MATCHED) — 바꿀 것이 없다 */
        NO_CHANGE,
        /** 이름을 바꿀 물리 객체가 없다 (엔터티·속성이 매핑되지 않음 — 새로 만드는 것은 물리 테이블 생성 기능) */
        NOT_MAPPED,
        /** 추천명을 다른 물리 객체가 지금 쓰고 있다 (상태 CONFLICT) */
        NAME_CONFLICT,
        /** 같은 이름 공간의 다른 추천 후보가 같은 새 이름을 받는다 (목록 : 전체 추천 후보 기준, 일괄 적용 미리보기·적용 : 선택 집합 기준 — P-12) */
        RECOMMENDATION_DUPLICATE,
        /** 추천명이 프로젝트 DBMS 의 비인용 식별자 문자·길이 규칙에 맞지 않는다 (DbIdentifierRules) */
        IDENTIFIER_INVALID,
        /** DBMS 는 받지만 Lexorma 권장 형식이 아니다 (경고) */
        IDENTIFIER_NOT_RECOMMENDED,
        /** 승인된 테이블 (승인 취소 뒤 수정 — 기존 정책). 컬럼 이름 변경도 소속 테이블이 승인되어 있으면 이 코드로 막는다 (P-10) */
        TABLE_APPROVED,
        /** 승인된 컬럼 */
        COLUMN_APPROVED,
        /** 컬럼의 표준 용어가 논리 속성의 표준 용어와 다르다 (P-03) */
        TERM_MISMATCH,
        /** 컬럼에 표준 용어가 연결되어 있지 않다 (P-09 확정 : 선택 불가) */
        COLUMN_TERM_MISSING,
        /** CHECK 제약조건(식 또는 구성 컬럼)이 옛 이름을 쓴다 */
        CHECK_REFERENCE,
        /** 인덱스 식·조건이 옛 이름을 쓴다 */
        INDEX_REFERENCE,
        /** VIEW 정의(또는 처리 전 변경 요청의 정의)가 옛 이름을 쓰거나 영향을 판정할 수 없다 (* · NATURAL) */
        VIEW_REFERENCE,
        /** SQL 을 낱말로 나눌 수 없어 참조 여부를 판정할 수 없다 */
        REFERENCE_UNVERIFIABLE,
        /** (2차, P-13) 이름을 바꾼 뒤 DDL 이 만드는 제약조건명(PK_·UK_·FK_)이 DBMS 길이를 넘어 잘린다 */
        CONSTRAINT_NAME_INVALID,
        /** (2차, P-13) 이름을 바꾼 뒤 DDL 의 최종 제약조건·인덱스 이름이 같은 DBMS 이름 공간의 다른 이름과 겹친다 */
        CONSTRAINT_NAME_CONFLICT,
        /** (2차) 요청이 보낸 추천 이름이 서버가 지금 계산한 추천 이름과 다르다 (목록을 다시 불러와야 한다) */
        RECOMMENDATION_CHANGED,
        /** (2차, P-15, 경고) 원본 DB 와 매핑된 테이블(또는 그 테이블의 컬럼)이다 — 원본 비교에서 이름이 달라 보일 수 있다 */
        SOURCE_MAPPED
    }

    /** 경고(warnings)로만 쓰는 코드 — 나머지는 적용을 막는다 */
    public static final java.util.Set<ApplyCode> WARNING_CODES = java.util.EnumSet.of(ApplyCode.IDENTIFIER_NOT_RECOMMENDED, ApplyCode.SOURCE_MAPPED);

    /** 판정 1건 : reason 은 화면 문구, related 는 관련 객체 이름 (VIEW·제약조건·인덱스·다른 후보) */
    public record ApplyIssue(ApplyCode code, String reason, List<String> related) {
    }

    /**
     * 일괄 적용 판정. selectable = blockers 가 비었다 (2차의 적용 대상으로 고를 수 있다). 판정은 조회 시점의 값이며 적용할 때 다시 판정한다 (P-04).
     * 추천끼리 충돌은 전체 추천 후보 기준이다 (선택 집합 기준 판정은 2차)
     */
    public record Applicability(boolean selectable, List<ApplyIssue> blockers, List<ApplyIssue> warnings) {
        public static Applicability of(List<ApplyIssue> issues) {
            List<ApplyIssue> blockers = issues.stream().filter(i -> !WARNING_CODES.contains(i.code())).toList();
            List<ApplyIssue> warnings = issues.stream().filter(i -> WARNING_CODES.contains(i.code())).toList();
            return new Applicability(blockers.isEmpty(), blockers, warnings);
        }
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
                               Long attributeMappingId, Long columnId, String columnName, Long columnTermId, Boolean columnApproval, Boolean tableApproval) {
    }

    /**
     * 컬럼명 추천 1건 = 논리 속성 1개. id 는 그리드 행 키.
     * recommendedPhysicalName 은 UNAVAILABLE 이면 null, currentPhysicalName·physicalColumnId 는 매핑된 컬럼이 없으면 null.
     * applicability : 일괄 적용 판정 (일괄 적용 1차 — 상태와 별개, 항상 있다)
     */
    public record ColumnRow(String id, Long modelId, String modelName, Long entityId, String entityName, Long mappingId,
                            Long schemaId, String schemaName, Long tableId, String tableName,
                            Long logicalAttributeId, String logicalName, Long termId, String termName, String termAbbreviation,
                            Long physicalColumnId, String currentPhysicalName, String recommendedPhysicalName,
                            Status status, Source source, String reason, Applicability applicability) {
    }

    /** 모델 1개의 컬럼명 추천 요약 : total = 모델의 속성 수 = 네 상태의 합 */
    public record ColumnSummary(Long modelId, long total, long available, long alreadyMatched, long unavailable, long conflict) {
    }

    /** 컬럼명 추천 조건. modelId 는 attributeId·columnId 가 없으면 필수. searchColumn : entityName / logicalName / currentPhysicalName / recommendedPhysicalName */
    public record ColumnSearch(Long modelId, Long entityId, Long attributeId, Long columnId, Status status, String searchColumn, String searchQuery) {
    }

    /**
     * 테이블명 추천 1건 = 논리 엔터티 1개. words 는 엔터티명을 나눈 단어별 표준 단어 변환 결과 (추천 근거),
     * tablePrefix 는 프로젝트 설정의 접두어 (없으면 null), applicability 는 일괄 적용 판정 (일괄 적용 1차, 항상 있다)
     */
    public record TableRow(String id, Long modelId, String modelName, Long logicalEntityId, String logicalName, Long mappingId,
                           Long schemaId, String schemaName, Long physicalTableId, String currentPhysicalName, String recommendedPhysicalName,
                           Status status, Source source, String reason, String tablePrefix, List<TableNameDto.Word> words, Applicability applicability) {
    }

    /** 테이블명 추천 조건. modelId 는 entityId 가 없으면 필수. searchColumn : entityName(logicalName) / tableName(currentPhysicalName) */
    public record TableSearch(Long modelId, Long entityId, String searchColumn, String searchQuery) {
    }
}
