package com.lexorma.source.collection.service;

import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;

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
     * @param definition       VIEW 정의를 읽은 결과 (DEC-16). 정의를 읽지 않는 경로면 null. 원문은 반영 단계에서 TB_SRC_VIEW_DFN 에만 쓴다
     */
    record Table(SourceCatalogCanonical.Table canonical, String json, String hash, Map<String, String> columnHashes, Map<String, RelationTarget> relationTargets,
                 SourceCatalogSpec.ViewDefinition definition) {
        String compareName() {
            return canonical.compareName();
        }

        /** 정의를 읽지 못한 VIEW : 지난번 정의 해시를 이어 써 원본 변경으로 보지 않는다 */
        Table withDefinitionHash(String definitionHash) {
            if (java.util.Objects.equals(canonical.definitionHash(), definitionHash)) return this;
            SourceCatalogCanonical.Table changed = canonical.withDefinitionHash(definitionHash);
            String changedJson = SourceCatalogCanonical.json(changed.json());
            return new Table(changed, changedJson, SourceCatalogCanonical.hash(changedJson), columnHashes, relationTargets, definition);
        }

        /** 이번 수집에서 정의를 읽은 VIEW 인지 */
        boolean definitionCollected() {
            return definition != null && definition.status() == com.lexorma.source.catalog.domain.enums.SourceViewDefinitionStatus.COLLECTED;
        }
    }

    /** @param referencedColumns 참조되는 컬럼의 비교 명 (구성 순서대로) */
    record RelationTarget(String pathKey, String tableCompareName, List<String> referencedColumns) {
    }
}
