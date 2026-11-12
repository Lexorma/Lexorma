package com.lexorma.source.collection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.repository.SourceTableRepository;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
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
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // ------------------------------------------------------------------ 내부

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
