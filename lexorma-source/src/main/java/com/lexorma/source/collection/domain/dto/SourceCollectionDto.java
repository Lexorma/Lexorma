package com.lexorma.source.collection.domain.dto;

import com.lexorma.source.collection.domain.entity.SourceChangeHistory;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.entity.SourceCollectionTarget;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 수집 실행·스냅샷·변경 이력의 응답 (AI-1c). 모두 Lexorma 에 저장된 원본 카탈로그를 읽은 것이다 :
 * 과거 시점 조회는 "그 반영 순서까지 Lexorma 가 확인해 둔 상태" 이고 원본 DB 를 그 시점으로 되돌린 것이 아니다.
 */
public final class SourceCollectionDto {

    private SourceCollectionDto() {
    }

    public record Execution(Long id, Long connectionId, String jobId, String executionType, String collectionType, String status, Integer reflectedOrder,
                            LocalDateTime startedAt, LocalDateTime endedAt, LocalDateTime reflectedAt, Integer tableCount, Integer columnCount,
                            Integer versionCount, Integer changeCount, String errorCode, String errorMessage) {
        public static Execution of(SourceCollectionExecution e) {
            return new Execution(e.getId(), e.getConnectionId(), e.getJobId(), e.getExecutionType().name(), e.getCollectionType().name(), e.getStatus().name(),
                    e.getReflectedOrder(), e.getStartedAt(), e.getEndedAt(), e.getReflectedAt(), e.getTableCount(), e.getColumnCount(), e.getVersionCount(),
                    e.getChangeCount(), e.getErrorCode(), e.getErrorMessage());
        }
    }

    public record Target(String pathKey, String catalogName, String schemaName, Long schemaId, String result, Integer tableCount, Integer columnCount, String errorCode) {
        public static Target of(SourceCollectionTarget t) {
            return new Target(t.getPathKey(), t.getCatalogName(), t.getSchemaName(), t.getSchemaId(), t.getResult().name(), t.getTableCount(), t.getColumnCount(),
                    t.getErrorCode());
        }
    }

    public record ExecutionDetail(Execution execution, List<Target> targets) {
    }

    /** @param before·after 바뀐 객체의 변경 전후 (정규화한 JSON 을 읽은 값) */
    public record Change(Long id, Integer reflectedOrder, String targetType, String targetKey, String targetName, Long schemaId, Long tableId, Integer creationCycle,
                         String changeType, List<String> changedFields, Object before, Object after) {
        @SuppressWarnings("unchecked")
        public static Change of(SourceChangeHistory h, java.util.function.Function<String, Object> read) {
            return new Change(h.getId(), h.getReflectedOrder(), h.getTargetType().name(), h.getTargetKey(), h.getTargetName(), h.getSchemaId(), h.getTableId(),
                    h.getCreationCycle(), h.getChangeType().name(), h.getChangedFields() == null ? List.of() : (List<String>) read.apply(h.getChangedFields()),
                    read.apply(h.getBefore()), read.apply(h.getAfter()));
        }
    }

    /**
     * @param versionOrder       이 상태를 만든 버전의 반영 순서
     * @param lastConfirmedOrder 이 이름 공간을 마지막으로 완전하게 수집한 반영 순서 (부분 수집 뒤에는 객체마다 다를 수 있다)
     */
    public record SchemaState(Long schemaId, String pathKey, String catalogName, String schemaName, Integer creationCycle, String changeType, Integer versionOrder,
                              Integer lastConfirmedOrder) {
    }

    public record TableState(Long tableId, Long schemaId, String pathKey, String name, String compareName, String type, Integer creationCycle, String changeType,
                             Integer versionOrder, Integer lastConfirmedOrder) {
    }

    /**
     * 반영 순서 R 에 Lexorma 가 알고 있던 연결의 카탈로그 (스키마 목록). R 이 없으면(반영된 수집이 없다) 비어 있다.
     */
    public record CatalogAt(Long connectionId, Integer reflectedOrder, Long executionId, LocalDateTime reflectedAt, List<SchemaState> schemas) {
    }

    /** 반영 순서 R 의 테이블 하나 : 정규화한 내용(컬럼·제약조건·인덱스·관계) */
    public record TableSnapshot(Long tableId, Integer reflectedOrder, Integer versionOrder, String changeType, Integer creationCycle, Integer lastConfirmedOrder,
                                Object content) {
    }

    public record TableVersion(Long id, Long executionId, Integer reflectedOrder, String changeType, Integer creationCycle, String contentHash) {
    }
}
