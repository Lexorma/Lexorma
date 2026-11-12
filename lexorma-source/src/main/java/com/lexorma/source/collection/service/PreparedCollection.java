package com.lexorma.source.collection.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.collection.domain.enums.TargetResult;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;

import java.util.List;
import java.util.Map;

/**
 * 검증하고 정규화한 수집 결과 ({@link SourceCollectionPreparer} 가 트랜잭션 밖에서 만든다). 반영 트랜잭션은 이것만 받는다.
 *
 * @param dbType·comparison 비교용 이름을 만든 규칙. 반영할 때 연결의 지금 규칙과 같은지 다시 본다
 * @param targets           대상별 결과 (반영하지 못한 실행의 기록에도 쓴다)
 * @param schemas           완전하게 수집한 스키마 (complete 가 아니면 비어 있다)
 * @param complete          모든 대상이 COMPLETED 인지. 아니면 실행 전체를 반영하지 않는다
 */
record PreparedCollection(Long connectionId, DbType dbType, IdentifierComparison comparison, List<Target> targets, List<Schema> schemas, boolean complete,
                          String incompleteReason, int tableCount, int columnCount) {

    record Target(String pathKey, String catalogName, String schemaName, TargetResult result, String errorCode, int tableCount, int columnCount) {
    }

    record Schema(SourceCatalogCanonical.Schema canonical, String json, String hash, List<Table> tables) {
        String pathKey() {
            return canonical.pathKey();
        }
    }

    /**
     * @param columnHashes     컬럼 비교 명 → 컬럼 해시
     * @param relationTargets  관계 키 → 참조되는 테이블을 찾을 비교용 경로·이름 (수집 결과의 따옴표 정보로 만든 것)
     */
    record Table(SourceCatalogCanonical.Table canonical, String json, String hash, Map<String, String> columnHashes, Map<String, RelationTarget> relationTargets) {
        String compareName() {
            return canonical.compareName();
        }
    }

    /** @param referencedColumns 참조되는 컬럼의 비교 명 (구성 순서대로) */
    record RelationTarget(String pathKey, String tableCompareName, List<String> referencedColumns) {
    }
}
