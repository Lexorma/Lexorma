package com.lexorma.source.collection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.service.SourceCatalogRules;
import com.lexorma.source.catalog.service.SourceIdentifiers;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.source.catalog.repository.SourceTableRepository;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.dto.SourceErdDto;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.entity.SourceSchemaVersion;
import com.lexorma.source.collection.domain.entity.SourceTableVersion;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.SourceChangeType;
import com.lexorma.source.collection.repository.SourceChangeHistoryRepository;
import com.lexorma.source.collection.repository.SourceCollectionExecutionRepository;
import com.lexorma.source.collection.repository.SourceCollectionTargetRepository;
import com.lexorma.source.collection.repository.SourceSchemaVersionRepository;
import com.lexorma.source.collection.repository.SourceTableVersionRepository;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수집 실행·변경 이력 조회와 과거 상태 재구성 (AI-1c, 읽기 전용). 로그인한 프로젝트의 연결만 본다.
 * <p>
 * <b>시점</b>은 연결별 반영 순서(1, 2, 3 …)다. 시각으로 물으면 그 시각까지 반영된 마지막 실행의 반영 순서로 바꾼다 (일련번호·등록 일시는 순서로 쓰지 않는다).
 * 반영 순서 R 의 상태 = 스키마·테이블마다 R 이하의 마지막 버전 (REMOVED 면 그때 없던 것). 버전만 읽는다 : 현재 카탈로그 행에 기대지 않는다.
 * 결과는 "R 시점에 Lexorma 가 확인해 둔 상태" 다 — 부분 수집 뒤에는 객체마다 마지막으로 확인한 실행이 다르고(lastConfirmedOrder), 원본 DB 의 그 시점 복원이 아니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceSnapshotService {

    private static final int SIZE_MAX = 200;

    private final SourceConnectionRepository connectionRepository;
    private final SourceTableRepository tableRepository;
    private final SourceCollectionExecutionRepository executionRepository;
    private final SourceCollectionTargetRepository targetRepository;
    private final SourceSchemaVersionRepository schemaVersionRepository;
    private final SourceTableVersionRepository tableVersionRepository;
    private final SourceChangeHistoryRepository historyRepository;

    public Page<SourceCollectionDto.Execution> executions(AccountDto accountDto, Long connectionId, int page, int size) {
        Long projectId = connection(accountDto, connectionId);
        return executionRepository.findAllByProjectIdAndConnectionId(projectId, connectionId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), SIZE_MAX), Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))))
                .map(SourceCollectionDto.Execution::of);
    }

    public SourceCollectionDto.ExecutionDetail execution(AccountDto accountDto, Long executionId) {
        SourceCollectionExecution execution = execution(accountDto.getProjectId(), executionId);
        return new SourceCollectionDto.ExecutionDetail(SourceCollectionDto.Execution.of(execution),
                targetRepository.findAllByExecutionIdOrderByPathKeyAsc(executionId).stream().map(SourceCollectionDto.Target::of).toList());
    }

    public Page<SourceCollectionDto.Change> changes(AccountDto accountDto, Long executionId, int page, int size) {
        SourceCollectionExecution execution = execution(accountDto.getProjectId(), executionId);
        return historyRepository.findAllByProjectIdAndExecutionId(execution.getProjectId(), executionId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), SIZE_MAX), Sort.by("id"))).map(change -> SourceCollectionDto.Change.of(change, SourceCatalogCanonical::read));
    }

    /** 반영 순서(또는 시각) R 의 스키마 목록. 둘 다 없으면 마지막 반영 */
    public SourceCollectionDto.CatalogAt catalogAt(AccountDto accountDto, Long connectionId, Integer order, LocalDateTime at) {
        connection(accountDto, connectionId);
        SourceCollectionExecution reflected = reflected(connectionId, order, at);
        if (reflected == null) return new SourceCollectionDto.CatalogAt(connectionId, null, null, null, List.of());
        Map<String, Integer> confirmed = lastConfirmed(connectionId, reflected.getReflectedOrder());
        List<SourceCollectionDto.SchemaState> schemas = schemaVersionRepository.at(connectionId, reflected.getReflectedOrder()).stream()
                .filter(version -> version.getChangeType() != SourceChangeType.REMOVED)
                .map(version -> schemaState(version, confirmed))
                // 비교 경로는 길이를 앞에 적은 값이라 이름 순서가 아니다 : 카탈로그·스키마 이름으로 정렬한다
                .sorted(java.util.Comparator.comparing(SourceCollectionDto.SchemaState::catalogName, java.util.Comparator.nullsFirst(java.util.Comparator.<String>naturalOrder()))
                        .thenComparing(SourceCollectionDto.SchemaState::schemaName, java.util.Comparator.nullsFirst(java.util.Comparator.<String>naturalOrder())))
                .toList();
        return new SourceCollectionDto.CatalogAt(connectionId, reflected.getReflectedOrder(), reflected.getId(), reflected.getReflectedAt(), schemas);
    }

    /** 반영 순서(또는 시각) R 의 한 스키마의 테이블 목록 */
    public List<SourceCollectionDto.TableState> tablesAt(AccountDto accountDto, Long connectionId, Long schemaId, Integer order, LocalDateTime at) {
        connection(accountDto, connectionId);
        SourceCollectionExecution reflected = reflected(connectionId, order, at);
        if (reflected == null) return List.of();
        Map<String, Integer> confirmed = lastConfirmed(connectionId, reflected.getReflectedOrder());
        return tableVersionRepository.at(connectionId, schemaId, reflected.getReflectedOrder()).stream()
                .filter(version -> version.getChangeType() != SourceChangeType.REMOVED)
                .map(version -> tableState(version, confirmed)).toList();
    }

    /** 반영 순서(또는 시각) R 의 테이블 하나. 그때 없던 테이블이면 SOURCE_OBJECT_NOT_FOUND */
    public SourceCollectionDto.TableSnapshot tableAt(AccountDto accountDto, Long tableId, Integer order, LocalDateTime at) {
        SourceTable table = table(accountDto, tableId);
        SourceCollectionExecution reflected = reflected(table.getConnectionId(), order, at);
        if (reflected == null) throw new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND);
        SourceTableVersion version = tableVersionRepository.findFirstByTableIdAndReflectedOrderLessThanEqualOrderByReflectedOrderDesc(tableId, reflected.getReflectedOrder())
                .filter(found -> found.getChangeType() != SourceChangeType.REMOVED).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        Integer confirmed = lastConfirmed(table.getConnectionId(), reflected.getReflectedOrder()).get(version.getPathKey());
        return new SourceCollectionDto.TableSnapshot(tableId, reflected.getReflectedOrder(), version.getReflectedOrder(), version.getChangeType().name(),
                version.getCreationCycle(), confirmed, SourceCatalogCanonical.read(version.getContent()));
    }

    public List<SourceCollectionDto.TableVersion> tableVersions(AccountDto accountDto, Long tableId) {
        table(accountDto, tableId);
        return tableVersionRepository.findAllByTableIdOrderByReflectedOrderAsc(tableId).stream()
                .map(v -> new SourceCollectionDto.TableVersion(v.getId(), v.getExecutionId(), v.getReflectedOrder(), v.getChangeType().name(), v.getCreationCycle(),
                        v.getContentHash())).toList();
    }

    public List<SourceCollectionDto.Change> tableChanges(AccountDto accountDto, Long tableId) {
        table(accountDto, tableId);
        return historyRepository.findAllByTableIdOrderByReflectedOrderAscIdAsc(tableId).stream().map(change -> SourceCollectionDto.Change.of(change, SourceCatalogCanonical::read)).toList();
    }

    /**
     * 원본 DB ERD (M-4b) : 반영 순서 R(없으면 마지막 반영)의 연결 전체 테이블·VIEW·컬럼·FK 를 한 번에 만든다.
     * <ul>
     *   <li>테이블마다 R 이하의 마지막 버전(REMOVED 제외)만 읽는다 : 그 시점에 없던 테이블·컬럼·FK 는 나오지 않고 최신 행을 섞지 않는다.
     *       부분 수집 뒤에도 범위 밖 객체는 그 앞 버전이 그대로 유효하다 (catalogAt·tableAt 와 같은 규칙)</li>
     *   <li>FK 의 참조 대상은 버전에 적힌 원본 이름을 연결의 비교 규칙으로 바꿔 (스키마 경로, 테이블 비교 이름)으로 찾는다 — 수집이 참조를 잇는 규칙과 같다.
     *       그 시점의 카탈로그에 없으면 targetTableId 가 null 이다. 이름이 비슷하다고 관계를 만들지 않는다</li>
     * </ul>
     */
    public SourceErdDto.Graph erd(AccountDto accountDto, Long connectionId, Integer order) {
        SourceConnection connection = connectionRepository.findByIdAndProjectId(connectionId, accountDto.getProjectId())
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        String dbType = connection.getDbType().name();
        String comparison = connection.getIdentifierComparison().name();
        SourceCollectionExecution reflected = reflected(connectionId, order, null);
        if (reflected == null) return new SourceErdDto.Graph(connectionId, dbType, comparison, null, null, null, List.of(), List.of(), List.of());
        int at = reflected.getReflectedOrder();

        // 스키마 : 이름은 버전 내용에서, 경로는 FK 대상을 찾는 데 쓴다
        Map<Long, Map<?, ?>> schemaContent = new LinkedHashMap<>();
        for (SourceSchemaVersion version : schemaVersionRepository.at(connectionId, at)) {
            if (version.getChangeType() != SourceChangeType.REMOVED) schemaContent.put(version.getSchemaId(), (Map<?, ?>) SourceCatalogCanonical.read(version.getContent()));
        }
        List<SourceTableVersion> versions = tableVersionRepository.at(connectionId, at).stream()
                .filter(version -> version.getChangeType() != SourceChangeType.REMOVED && schemaContent.containsKey(version.getSchemaId())).toList();
        Map<String, Long> byPath = new HashMap<>();
        versions.forEach(version -> byPath.put(version.getPathKey() + '\u0000' + version.getCompareName(), version.getTableId()));

        List<SourceErdDto.Table> tables = new ArrayList<>(versions.size());
        List<SourceErdDto.Relation> relations = new ArrayList<>();
        Map<Long, int[]> counts = new HashMap<>();
        for (SourceTableVersion version : versions) {
            Map<?, ?> content = (Map<?, ?>) SourceCatalogCanonical.read(version.getContent());
            Set<String> pk = new HashSet<>();
            for (Map<?, ?> constraint : maps(content.get("constraints"))) {
                if ("PRIMARY_KEY".equals(constraint.get("type"))) pk.addAll(strings(constraint.get("columns")));
            }
            Set<String> fk = new HashSet<>();
            Map<String, String> displayName = new HashMap<>();
            List<Map<?, ?>> columnContent = maps(content.get("columns"));
            columnContent.forEach(column -> displayName.put((String) column.get("compareName"), (String) column.get("name")));

            for (Map<?, ?> relation : maps(content.get("relations"))) {
                String referencedCatalog = (String) relation.get("referencedCatalog");
                String referencedSchema = (String) relation.get("referencedSchema");
                String referencedTable = (String) relation.get("referencedTable");
                String catalogCompare = referencedCatalog == null ? SourceIdentifiers.catalogOf(version.getPathKey())
                        : SourceCatalogRules.compare(connection, Kind.NAMESPACE, Name.of(referencedCatalog));
                String path = referencedCatalog == null && referencedSchema == null ? version.getPathKey()
                        : SourceIdentifiers.pathKey(catalogCompare, SourceCatalogRules.compare(connection, Kind.NAMESPACE, Name.of(referencedSchema)));
                Long target = byPath.get(path + '\u0000' + SourceCatalogRules.compare(connection, Kind.TABLE, Name.of(referencedTable)));
                List<SourceErdDto.ColumnPair> pairs = new ArrayList<>();
                for (Map<?, ?> pair : maps(relation.get("columns"))) {
                    String own = (String) pair.get("column");
                    String referenced = (String) pair.get("referencedColumn");
                    fk.add(own);
                    pairs.add(new SourceErdDto.ColumnPair(displayName.getOrDefault(own, own), own, referenced,
                            SourceCatalogRules.compare(connection, Kind.COLUMN, Name.of(referenced))));
                }
                relations.add(new SourceErdDto.Relation(version.getTableId() + ":" + relation.get("key"), (String) relation.get("name"), version.getTableId(), target,
                        referencedCatalog, referencedSchema, referencedTable, (String) relation.get("deleteRule"), (String) relation.get("updateRule"), pairs));
            }
            List<SourceErdDto.Column> columns = columnContent.stream().map(column -> {
                String key = (String) column.get("compareName");
                return new SourceErdDto.Column((String) column.get("name"), key, (String) column.get("dataType"), (Boolean) column.get("nullable"), pk.contains(key), fk.contains(key));
            }).toList();
            String type = (String) content.get("type");
            tables.add(new SourceErdDto.Table(version.getTableId(), version.getSchemaId(), (String) content.get("name"), version.getCompareName(), type,
                    (String) content.get("comment"), columns));
            counts.computeIfAbsent(version.getSchemaId(), id -> new int[2])["VIEW".equals(type) ? 1 : 0]++;
        }
        List<SourceErdDto.Schema> schemas = schemaContent.entrySet().stream()
                .map(entry -> new SourceErdDto.Schema(entry.getKey(), (String) entry.getValue().get("catalog"), (String) entry.getValue().get("schema"),
                        counts.getOrDefault(entry.getKey(), new int[2])[0], counts.getOrDefault(entry.getKey(), new int[2])[1]))
                .sorted(Comparator.comparing(SourceErdDto.Schema::catalogName, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                        .thenComparing(SourceErdDto.Schema::schemaName, Comparator.nullsFirst(Comparator.<String>naturalOrder())))
                .toList();
        return new SourceErdDto.Graph(connectionId, dbType, comparison, at, reflected.getId(), reflected.getReflectedAt(), schemas, tables, relations);
    }

    // ------------------------------------------------------------------ 내부

    private static List<Map<?, ?>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).<Map<?, ?>>map(Map.class::cast).toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private SourceCollectionExecution reflected(Long connectionId, Integer order, LocalDateTime at) {
        if (order != null) {
            return executionRepository.findByConnectionIdAndReflectedOrder(connectionId, order).filter(e -> e.getStatus() == CollectionStatus.COMPLETED)
                    .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        }
        LocalDateTime until = at == null ? LocalDateTime.now().plusYears(100) : at;
        return executionRepository.findFirstByConnectionIdAndStatusAndReflectedAtLessThanEqualOrderByReflectedOrderDesc(connectionId, CollectionStatus.COMPLETED, until)
                .orElse(null);
    }

    private Map<String, Integer> lastConfirmed(Long connectionId, Integer order) {
        Map<String, Integer> confirmed = new HashMap<>();
        for (Object[] row : targetRepository.lastConfirmed(connectionId, order)) confirmed.put((String) row[0], (Integer) row[1]);
        return confirmed;
    }

    private static SourceCollectionDto.SchemaState schemaState(SourceSchemaVersion version, Map<String, Integer> confirmed) {
        Map<?, ?> content = (Map<?, ?>) SourceCatalogCanonical.read(version.getContent());
        return new SourceCollectionDto.SchemaState(version.getSchemaId(), version.getPathKey(), (String) content.get("catalog"), (String) content.get("schema"),
                version.getCreationCycle(), version.getChangeType().name(), version.getReflectedOrder(), confirmed.get(version.getPathKey()));
    }

    private static SourceCollectionDto.TableState tableState(SourceTableVersion version, Map<String, Integer> confirmed) {
        Map<?, ?> content = (Map<?, ?>) SourceCatalogCanonical.read(version.getContent());
        return new SourceCollectionDto.TableState(version.getTableId(), version.getSchemaId(), version.getPathKey(), (String) content.get("name"), version.getCompareName(),
                (String) content.get("type"), version.getCreationCycle(), version.getChangeType().name(), version.getReflectedOrder(), confirmed.get(version.getPathKey()));
    }

    /** 다른 프로젝트의 연결은 ID 를 알아도 없는 것과 같다 */
    private Long connection(AccountDto accountDto, Long connectionId) {
        Long projectId = accountDto.getProjectId();
        connectionRepository.findByIdAndProjectId(connectionId, projectId).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        return projectId;
    }

    private SourceCollectionExecution execution(Long projectId, Long executionId) {
        return executionRepository.findByIdAndProjectId(executionId, projectId).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
    }

    private SourceTable table(AccountDto accountDto, Long tableId) {
        return tableRepository.findByIdAndProjectId(tableId, accountDto.getProjectId()).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
    }
}
