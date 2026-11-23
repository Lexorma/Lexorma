package com.lexorma.data.mapping.domain.dto;

import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 논리·물리 매핑 후보 추천과 ERD 매핑 표시 (M-7 자동 매핑 후보·ERD 연계, docs/completed/logical-physical-mapping-m7.md 18장).
 * 추천은 조회 전용이다 — 저장은 사용자가 고른 후보를 기존 매핑 저장 API(POST /api/modelMapping/entity · /attribute)로 보낸다.
 * 점수·확률은 쓰지 않는다 : 근거(reasons)와 근거 수준(level)만 규칙으로 정한다.
 */
public final class MappingSuggestionDto {

    private MappingSuggestionDto() {
    }

    /**
     * 근거 수준. STANDARD : 표준 용어 ID 기반 근거(승인된 같은 표준 용어 · 엔터티 주 식별자 용어 = 테이블 PK 컬럼 용어).
     * REFERENCE : 이름·명명 규칙·승인되지 않은 용어 같은 참고 근거 — 확정 근거로 보이지 않는다 (M7-R07)
     */
    public enum Level { STANDARD, REFERENCE }

    // ------------------------------------------------------------------ 엔터티 ↔ 테이블

    /**
     * 엔터티 하나의 테이블 후보. mapped : 이미 매핑됨(후보를 내지 않는다 — 변경은 기존 흐름, M7-R05).
     * available false 면 unavailableReason(ALREADY_MAPPED · NO_CANDIDATE). recommendedTableName : 표준 단어로 만든 테이블명(못 만들면 null).
     * ambiguous : 후보가 둘 이상 (자동으로 고르지 않는다, M7-R04)
     */
    public record TableSuggestions(Long modelId, Long entityId, String entityName, boolean mapped, Long mappingId, Long tableId, String tableName,
                                   String recommendedTableName, int keyTermCount, boolean available, String unavailableReason, boolean ambiguous,
                                   List<TableSuggestion> candidates) {
    }

    /**
     * 테이블 후보 1개. reasons (우선순위 순) : KEY_TERMS(주 식별자 용어 = PK 컬럼 용어) · TABLE_NAME(표준 단어 테이블명과 같음) · TABLE_COMMENT(테이블 코멘트 = 엔터티명).
     * matchedTerms / termAttributes : 엔터티 속성의 표준 용어 중 이 테이블 컬럼에도 있는 수 / 표준 용어가 있는 속성 수 (보여만 준다)
     */
    public record TableSuggestion(Long tableId, Long schemaId, String schemaName, String tableName, String tableKorName, Boolean isApproval,
                                  Level level, List<String> reasons, int matchedTerms, int termAttributes) {
    }

    // ------------------------------------------------------------------ 속성 ↔ 컬럼

    public record AttributeSuggestions(Long mappingId, Long modelId, Long entityId, String entityName, Long tableId, String schemaName, String tableName,
                                       List<AttributeSuggestion> attributes) {
    }

    /**
     * 속성 1개. mapped 면 columnId·columnName 은 지금 매핑이고 후보는 없다. available false 면 unavailableReason(ALREADY_MAPPED · NO_CANDIDATE).
     * notes : NO_TERM(표준 용어 없음 — 용어 근거를 쓸 수 없다) · TERM_NOT_APPROVED(용어가 승인되지 않음 — 같은 용어라도 참고 근거).
     * conflicts : MULTIPLE_STANDARD(같은 용어 후보 여럿) · REASONS_DIFFER(근거마다 다른 컬럼) · SHARED(최우선 후보가 다른 속성의 최우선 후보이기도 함)
     */
    public record AttributeSuggestion(Long attributeId, String attributeName, Boolean attributeRequired, Long termId, String termName, Boolean termApproval,
                                      boolean mapped, Long columnId, String columnName, boolean available, String unavailableReason,
                                      List<String> notes, List<String> conflicts, List<ColumnSuggestion> candidates) {
    }

    /**
     * 컬럼 후보 1개. reasons (우선순위 순) : STANDARD_TERM(승인된 같은 표준 용어) · STANDARD_TERM_UNAPPROVED(같은 용어지만 미승인) ·
     * COLUMN_NAME(컬럼명 = 속성 용어의 영문약어) · LOGICAL_NAME(속성명 = 컬럼 논리명(설명)).
     * sharedWith : 이 컬럼이 후보인 다른 속성 이름. differences : 이 짝을 매핑했을 때 정합성 비교(기존 판정)가 불일치로 볼 항목
     */
    public record ColumnSuggestion(Long columnId, String columnName, String columnDesc, String dataType, Integer maxLength, BigDecimal precision, Integer scale,
                                   Boolean isPk, Boolean isNullable, Long termId, String termName, Boolean termApproval,
                                   Level level, List<String> reasons, List<String> sharedWith, List<Difference> differences) {
    }

    /** 정합성 비교 진단 유형(ModelMappingQualityDto.CheckType) 1건의 불일치 : 논리 값 · 물리 값 · 사유 */
    public record Difference(String type, String logicalValue, String physicalValue, String reason) {
    }

    // ------------------------------------------------------------------ ERD 매핑 표시

    /**
     * ERD 표시 상태 (M7-E02, 정합성 비교 상태와 같은 뜻) : MAPPED(매핑됨 — 판정이 모두 일치) · UNMAPPED(매핑 없음) · MISMATCH(불일치가 하나라도 있음) ·
     * NOT_COMPARABLE(불일치는 없고 비교 불가가 있음). 매핑된 엔터티 안의 미매핑 속성은 엔터티 상태를 바꾸지 않고 속성 수로 보인다
     */
    public enum OverlayStatus { MAPPED, UNMAPPED, MISMATCH, NOT_COMPARABLE }

    /** 속성·컬럼 매핑 종류 : DIRECT(속성 ↔ 컬럼) · INHERITED_KEY(상속 PK 매핑 — 루트 주 식별자 속성 ↔ 서브타입 테이블 컬럼) */
    public enum LinkKind { DIRECT, INHERITED_KEY, INHERITED_ATTRIBUTE }

    /**
     * 매핑 상태 (M7-F02-1) : 저장된 매핑 관계만으로 정한다 — MAPPED(매핑이 있다) · UNMAPPED(없다). 정합성 판정·이름·표준 용어와 무관하다.
     * 상속 PK 매핑도 MAPPED 이며 종류(LinkKind)로 구분한다
     */
    public enum MappingStatus {
        MAPPED, UNMAPPED,
        /** A형 통합 (M-7 상속 A) : 자기 매핑 없이 루트 통합 테이블에 — 유효한 통합만 (아니면 UNMAPPED) */
        MERGED,
        /** C형 분산 (M-7 상속 C) : 자기 테이블 없이 말단 테이블에 — 아래 말단이 모두 매핑된 것만 (아니면 UNMAPPED) */
        DISTRIBUTED
    }

    /**
     * 정합성 판정 1건 (M7-F02-2, 기존 정합성 비교의 진단 행 그대로 — 사유를 새로 만들지 않는다). status 는 MISMATCH · NOT_COMPARABLE (일치 행은 내지 않는다).
     * attributeName·columnName : 속성 단위 행이면 그 속성·컬럼 (엔터티 단위 행은 null)
     */
    public record Issue(String type, CheckStatus status, String reason, String attributeName, String columnName) {
    }

    /**
     * 논리 ERD 의 엔터티 1개. mapped false 면 table 값이 null. mismatches / notComparable : 이 엔터티의 정합성 비교 행 중 불일치 / 비교 불가 수.
     * attributes : 매핑된 속성(직접), inheritedKeys : 이 엔터티(서브타입) 테이블의 상속 PK 매핑.
     * status (예전 필드, 뜻 유지) : 매핑 여부와 정합성을 합친 표시 상태. M7-F02 부터 화면은 mappingStatus(매핑 여부)와 qualityStatus(정합성)를 따로 쓴다 —
     * qualityStatus : 매핑이 없으면 UNMAPPED, 있으면 엔터티 진단 행(속성·구조·상속)의 MISMATCH > NOT_COMPARABLE > MATCH (매핑된 엔터티 안의 미매핑 행은 수로만 보인다).
     * issues : 엔터티 단위 판정(속성에 붙지 않은 행 — 관계·보조 식별자·상속 구조 등) 중 불일치·비교 불가
     */
    public record EntityOverlay(Long entityId, boolean mapped, Long mappingId, Long schemaId, String schemaName, Long tableId, String tableName,
                                OverlayStatus status, int attributeCount, int mappedAttributeCount, int mismatches, int notComparable,
                                List<AttributeLink> attributes, List<AttributeLink> inheritedKeys,
                                MappingStatus mappingStatus, CheckStatus qualityStatus, List<Issue> issues, String strategy, List<ModelMappingDto.PhysicalTable> physicalTables) {
        public EntityOverlay(Long entityId, boolean mapped, Long mappingId, Long schemaId, String schemaName, Long tableId, String tableName,
                             OverlayStatus status, int attributeCount, int mappedAttributeCount, int mismatches, int notComparable,
                             List<AttributeLink> attributes, List<AttributeLink> inheritedKeys,
                             MappingStatus mappingStatus, CheckStatus qualityStatus, List<Issue> issues) {
            this(entityId, mapped, mappingId, schemaId, schemaName, tableId, tableName, status, attributeCount, mappedAttributeCount, mismatches, notComparable, attributes,
                    inheritedKeys, mappingStatus, qualityStatus, issues, null, List.of());
        }
    }

    /**
     * 속성 ↔ 컬럼 1개 (있는 매핑만 — 언제나 mappingStatus MAPPED). status (예전 필드) : 표시 상태. qualityStatus : 그 속성 진단 행의 MISMATCH > NOT_COMPARABLE > MATCH
     * (상속 PK 는 그 서브타입의 상속 구조 판정 중 불일치·비교 불가, 없으면 MATCH). issues : 그 판정의 불일치·비교 불가 행
     */
    public record AttributeLink(Long attributeId, String attributeName, Long columnId, String columnName, LinkKind kind, OverlayStatus status,
                                MappingStatus mappingStatus, CheckStatus qualityStatus, List<Issue> issues) {
    }

    /** 물리 ERD 의 테이블 1개. mapped false 면 엔터티 값이 null. 상태 필드는 EntityOverlay 와 같은 뜻 (판정은 매핑된 엔터티의 것) */
    public record TableOverlay(Long tableId, boolean mapped, Long mappingId, Long modelId, String modelName, Long entityId, String entityName,
                               OverlayStatus status, int columnCount, int mappedColumnCount, int mismatches, int notComparable,
                               List<AttributeLink> columns, MappingStatus mappingStatus, CheckStatus qualityStatus, List<Issue> issues, String strategy,
                               List<ModelMappingDto.PhysicalTable> mergedEntities) {
        public TableOverlay(Long tableId, boolean mapped, Long mappingId, Long modelId, String modelName, Long entityId, String entityName,
                            OverlayStatus status, int columnCount, int mappedColumnCount, int mismatches, int notComparable,
                            List<AttributeLink> columns, MappingStatus mappingStatus, CheckStatus qualityStatus, List<Issue> issues) {
            this(tableId, mapped, mappingId, modelId, modelName, entityId, entityName, status, columnCount, mappedColumnCount, mismatches, notComparable, columns, mappingStatus,
                    qualityStatus, issues, null, List.of());
        }
    }
}
