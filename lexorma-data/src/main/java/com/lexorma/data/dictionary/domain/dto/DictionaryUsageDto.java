package com.lexorma.data.dictionary.domain.dto;

/** 표준 항목 사용 현황 (읽기 전용, 엔티티를 거치지 않고 필요한 컬럼만 조회한다) */
public final class DictionaryUsageDto {

    private DictionaryUsageDto() {
    }

    /**
     * 표준 용어(또는 그 용어의 표준 도메인)를 쓰는 물리 컬럼. id 는 컬럼 ID.
     * 컬럼은 표준 용어를 ID 로 참조하고(TB_COL.STD_TRM_SN), 표준 도메인은 용어를 거쳐서만 연결된다.
     */
    public record ColumnUsage(Long id, Long tableId, Long schemaId, String schemaName, String tableName, String tableKorName,
                              String columnName, String columnDesc, String dataType, Integer maxLength,
                              Long termId, String termName, String domainName, Boolean isApproval) {
    }

    /** 표준 단어를 쓰는 표준 용어 (TB_STD_TRM_WORD 매핑 기준). id 는 용어 ID, columnCount 는 그 용어를 쓰는 물리 컬럼 수 */
    public record TermUsage(Long id, String termName, String termAbbreviation, String domainName, Boolean isApproval, Long columnCount) {
    }

    /** 사용 컬럼 조회 조건 (모두 선택). searchColumn : tableName / columnName */
    /**
     * 표준 도메인 승인 취소 전 영향 범위 (M8-C4c-1 C4c-D05) : 도메인을 직접 참조하는 표준 용어 수(그중 승인된 수)와, 그 용어를 참조하는 논리 속성·물리 컬럼 수 (간접 참조).
     * 승인 취소는 이 참조를 지우거나 그 승인 상태를 바꾸지 않는다 — 안내용 (같은 대상은 한 번만 센다)
     */
    public record DomainImpact(Long domainId, String domainName, Boolean isApproval, long termCount, long approvedTermCount, long attributeCount, long columnCount,
                               long approvedColumnCount) {
    }

    public record ColumnSearch(Long schemaId, Boolean isApproval, String searchColumn, String searchQuery) {
    }
}
