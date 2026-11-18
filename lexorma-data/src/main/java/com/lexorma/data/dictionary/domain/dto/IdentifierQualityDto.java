package com.lexorma.data.dictionary.domain.dto;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.table.ddl.DbIdentifierRules.Kind;

import java.util.List;
import java.util.Map;

/**
 * DBMS 식별자 진단 (M-7 보강 1차 — M-5 D3, 읽기 전용). 조회할 때 계산하고 저장하지 않는다.
 * 기존 컬럼 진단 요약(대시보드)·모델 진단과 따로 조회·집계한다 — 그 값과 의미는 그대로다.
 */
public final class IdentifierQualityDto {

    private IdentifierQualityDto() {
    }

    /**
     * 진단 1건. id 는 그리드 행 키(규칙:종류:대상 ID:이름). kind : 스키마·테이블·컬럼이면 그 이름, 제약조건이면 PK·UNIQUE·FK (대상 ID = PK 는 테이블, UNIQUE·FK 는 컬럼 — 저장된 이름이 잘린 충돌이면 테이블).
     * name : 판정한 이름 (제약조건은 DDL 이 만드는 원래 이름), ddlName : DDL 에 실제로 나가는 이름 (잘림·충돌일 때),
     * length·maxLength : 길이 규칙일 때 DBMS 기준 단위의 값, related : 충돌한 다른 이름 ("이름 (종류, 테이블)"), fingerprint 는 {@code FindingFingerprint}
     */
    public record Issue(String id, StandardizationRule type, Severity severity, Kind kind, Long schemaId, String schemaName, Long tableId, String tableName,
                        Long columnId, String columnName, String name, String ddlName, Integer length, Integer maxLength,
                        String basis, String message, String guide, List<String> related, String fingerprint) {
    }

    /** rules : 식별자 규칙별 건수 (규칙 순서). lengthBasis : 프로젝트 DBMS 의 길이 기준 설명 */
    public record Summary(DbType dbType, String dbLabel, String lengthBasis, long totalSchemas, long totalTables, long totalColumns,
                          Map<StandardizationRule, Long> rules, long errors, long warnings, long infos) {
    }

    /** 목록 조건 (모두 선택). searchColumn : tableName / name */
    public record Search(StandardizationRule type, Severity severity, Kind kind, Long schemaId, String searchColumn, String searchQuery) {
    }
}
