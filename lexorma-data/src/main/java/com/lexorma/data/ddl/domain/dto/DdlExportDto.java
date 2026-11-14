package com.lexorma.data.ddl.domain.dto;

import java.util.List;

/**
 * 통합 DDL Export (M-11) : 프로젝트 전체 또는 스키마 하나의 SCHEMA·TABLE·INDEX·FK·VIEW 를 한 스크립트로. 미리보기와 .sql 다운로드가 같은 응답을 쓴다.
 *
 * @param scope            PROJECT / SCHEMA
 * @param schemaName       스키마 단위일 때 그 스키마명
 * @param approvedOnly     승인 기준 모드 : 승인 대기 항목이 있으면 만들지 않는다 (errors)
 * @param ddl              생성된 스크립트. errors 가 있으면 빈 문자열 (불완전한 스크립트를 결과로 주지 않는다)
 * @param counts           스크립트에 들어간 문장 수
 * @param warnings         만들었지만 확인이 필요한 항목 (범위 밖 참조, 미확인 참조, DBMS 차이 등)
 * @param errors           만들 수 없는 이유
 * @param pendingApprovals 범위 안의 승인 대기 항목 (종류별 한 줄). 기본 모드는 포함해서 만들고 알리기만 한다
 */
public record DdlExportDto(String dbType, String dbTypeLabel, String scope, Long schemaId, String schemaName, boolean approvedOnly,
                           String ddl, Counts counts, List<String> warnings, List<String> errors, List<String> pendingApprovals) {

    /** @param indexes 실제 CREATE INDEX 문 수 (대상 DBMS 에서 만들 수 없어 주석으로 낸 인덱스는 commentedIndexes) */
    public record Counts(int schemas, int tables, int indexes, int commentedIndexes, int foreignKeys, int views) {}
}
