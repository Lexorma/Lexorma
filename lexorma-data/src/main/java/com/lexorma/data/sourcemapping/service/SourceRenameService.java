package com.lexorma.data.sourcemapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.core.source.SourceCatalogReader.NameKind;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto.ColumnStatus;
import com.lexorma.data.sourcemapping.domain.entity.SourceColumnMapping;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory.ChangeType;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory.TargetType;
import com.lexorma.data.sourcemapping.domain.entity.SourceSchemaMapping;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceColumnMappingRepository;
import com.lexorma.data.sourcemapping.repository.SourceMappingHistoryRepository;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 원본 이름 변경 매핑 (DEC-06, docs/completed/source-rename-mapping-dec06.md). 원본 DB 에 접속하지 않고 마지막 반영 수집만 읽으며, 원본 DB·물리 모델은 바꾸지 않는다
 * (매핑만 저장한다 — 모델 TABLE·컬럼의 이름·승인 상태는 그대로).
 * <ul>
 *   <li>후보는 제안이다 : TABLE 은 원본에서 사라진 TABLE 매핑 ↔ 같은 스키마 매핑의 미매핑 원본 TABLE (컬럼 이름이 겹치는 정도), 컬럼은 M-4d 비교의 이름 변경 후보.
 *       사용자가 확정해야 저장한다. 이름 유사도로 자동 확정하지 않는다</li>
 *   <li>TABLE 이름 변경 확정 = 같은 TABLE 매핑 행을 새 원본 TABLE 로 옮긴다 (원본 없음 상태인 매핑만, 같은 모델 TABLE·컬럼 매핑 유지). 새 원본은 미매핑·예외 아님이어야 한다</li>
 *   <li>컬럼 매핑 확정 : 매핑됨 상태인 TABLE 매핑 안에서, 지금 비교에서 원본에만 있는 원본 컬럼 ↔ 모델에만 있는 모델 컬럼만 (이미 짝지어진 컬럼과 부딪히지 않는다).
 *       TABLE 매핑 안에서 양쪽 모두 1:1 (DB 유니크 인덱스로도 막는다)</li>
 *   <li>잠금·권한은 매핑 변경과 같다 : 프로젝트 관리자·ADMIN, 프로젝트(공유) → 원본 연결(공유) → 기준 연결 행(배타). 요청의 기준(연결·반영 순서)이 지금과 다르면 409</li>
 *   <li>모든 변경은 TB_SRC_MPNG_HSTRY 에 이력을 남긴다 (같은 트랜잭션)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceRenameService {

    /** TABLE 이름 변경 후보로 보이는 최소 겹침 (겹친 컬럼 이름 수 / 컬럼이 더 많은 쪽의 수). 제안 기준일 뿐 확정 조건이 아니다 */
    static final double TABLE_SCORE_MIN = 0.5;
    private static final int PAGE_MAX = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SourceMappingService mappingService;
    private final SourceCompareService compareService;
    private final SourceCatalogReader reader;
    private final SourceTableMappingRepository tableMappingRepository;
    private final SourceColumnMappingRepository columnMappingRepository;
    private final SourceMappingHistoryRepository historyRepository;
    private final SourceMappingHistoryRecorder history;

    // ------------------------------------------------------------------ 조회

    /** 이름 변경 후보 (TABLE·컬럼) 와 후보가 없는 원본 없음 TABLE 매핑 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceRenameDto.Candidates candidates(AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        SourceMappingService.State state = mappingService.basis(projectId);
        if (state == null || state.connection == null || state.snapshot.reflectedOrder() == null) {
            return new SourceRenameDto.Candidates(state == null || state.connection == null ? null : state.connection.id(), null, List.of(), List.of(), List.of());
        }
        Integer order = state.snapshot.reflectedOrder();
        List<SourceRenameDto.TableCandidate> tables = tableCandidates(projectId, state);
        Set<Long> withCandidate = new HashSet<>();
        tables.forEach(t -> withCandidate.add(t.mappingId()));
        List<SourceRenameDto.RemovedTable> removed = new ArrayList<>();
        for (SourceTableMapping mapping : state.tableMappings) {
            if (mapping.isExclusion() || mapping.getTableId() == null || state.sourceTables.containsKey(mapping.getSourceTableId())) continue;
            if (state.modelTables.get(mapping.getTableId()) == null) continue;
            removed.add(new SourceRenameDto.RemovedTable(mapping.getId(), mapping.getTableId(), state.modelTableName(mapping.getTableId()), mapping.getCompareName(),
                    withCandidate.contains(mapping.getId())));
        }
        return new SourceRenameDto.Candidates(state.connection.id(), order, tables, columnCandidates(accountDto, state), removed);
    }

    /** 기준 연결의 컬럼 매핑 (tableMappingId 로 거르기) 과 상태 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SourceRenameDto.ColumnMapping> columnMappings(AccountDto accountDto, Long tableMappingId) {
        Long projectId = accountDto.getProjectId();
        SourceMappingService.State state = mappingService.basis(projectId);
        if (state == null || state.connection == null) return List.of();
        List<SourceColumnMapping> mappings = state.columnMappings.stream().filter(c -> tableMappingId == null || tableMappingId.equals(c.getTableMappingId()))
                .sorted(Comparator.comparing(SourceColumnMapping::getId)).toList();
        if (mappings.isEmpty()) return List.of();
        Map<Long, Status> tableStatus = new HashMap<>();
        state.tableRows().forEach(r -> {
            if (r.mappingId() != null) tableStatus.put(r.mappingId(), r.status());
        });
        Map<Long, Set<String>> sourceColumns = new HashMap<>();
        Integer order = state.snapshot.reflectedOrder();
        if (order != null) {
            Set<Long> sourceIds = new HashSet<>();
            for (SourceColumnMapping c : mappings) {
                SourceTableMapping table = state.tableMappingById.get(c.getTableMappingId());
                if (table != null && state.sourceTables.containsKey(table.getSourceTableId())) sourceIds.add(table.getSourceTableId());
            }
            reader.tableDetails(projectId, state.connection.id(), order, sourceIds).forEach((id, detail) -> {
                Set<String> names = new HashSet<>();
                detail.columns().forEach(col -> names.add(col.compareName()));
                sourceColumns.put(id, names);
            });
        }
        List<SourceRenameDto.ColumnMapping> rows = new ArrayList<>();
        for (SourceColumnMapping c : mappings) {
            SourceTableMapping table = state.tableMappingById.get(c.getTableMappingId());
            Object[] model = c.getColumnId() == null ? null : state.mappedModelColumns.get(c.getColumnId());
            ColumnStatus status;
            if (table == null || tableStatus.get(table.getId()) != Status.MAPPED) status = ColumnStatus.TABLE_INVALID;
            else if (model == null || !Objects.equals(model[1], table.getTableId())) status = ColumnStatus.MODEL_MISSING;
            else if (!sourceColumns.getOrDefault(table.getSourceTableId(), Set.of()).contains(c.getCompareName())) status = ColumnStatus.SOURCE_MISSING;
            else status = ColumnStatus.MAPPED;
            var audit = c.audit();
            rows.add(new SourceRenameDto.ColumnMapping(c.getId(), c.getTableMappingId(), table == null ? null : table.getSourceTableId(),
                    table == null ? null : state.sourceTableName(table), c.getSourceColumnName(), c.getCompareName(), table == null ? null : table.getTableId(),
                    table == null ? null : state.modelTableName(table.getTableId()), c.getColumnId(), state.modelColumnName(c.getColumnId()), c.getReflectedOrder(), status,
                    audit.created(), audit.createdBy(), c.modifiedAt()));
        }
        return rows;
    }

    /** 매핑 변경 이력 (최근 것부터, tableMappingId 로 거르기) */
    public SourceRenameDto.HistoryPage history(AccountDto accountDto, Long tableMappingId, int page, int size) {
        Long projectId = accountDto.getProjectId();
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), PAGE_MAX));
        Page<SourceMappingHistory> rows = tableMappingId == null ? historyRepository.findAllByProjectIdOrderByIdDesc(projectId, request)
                : historyRepository.findAllByProjectIdAndTableMappingIdOrderByIdDesc(projectId, tableMappingId, request);
        return new SourceRenameDto.HistoryPage(rows.getContent().stream().map(h -> new SourceRenameDto.History(h.getId(), h.getTableMappingId(), h.getTargetType(),
                h.getChangeType(), parse(h.getBefore()), parse(h.getAfter()), h.getChangedAt(), h.audit().createdBy())).toList(), rows.getTotalElements());
    }

    // ------------------------------------------------------------------ 변경

    /** TABLE 이름 변경 확정 : 원본 없음인 TABLE 매핑을 같은 스키마 매핑의 미매핑 원본 TABLE 로 옮긴다 */
    @Transactional
    public SourceRenameDto.Candidates confirmTable(AccountDto accountDto, SourceRenameDto.TableRenameRequest request) {
        Long projectId = mappingService.manage(accountDto);
        if (request == null || request.mappingId() == null || request.sourceTableId() == null) throw SourceMappingService.invalid("TABLE 매핑과 새 원본 테이블을 골라주세요.");
        Long connectionId = mappingService.lockBase(projectId);
        SourceMappingService.State state = basis(projectId, connectionId, request.connectionId(), request.reflectedOrder());
        SourceTableMapping mapping = state.tableMappingById.get(request.mappingId());
        if (mapping == null || mapping.isExclusion()) throw SourceMappingService.invalid("매핑을 찾을 수 없습니다.");
        if (state.sourceTables.containsKey(mapping.getSourceTableId())) {
            throw SourceMappingService.invalid("원본 테이블이 아직 있는 매핑은 이름 변경으로 옮길 수 없습니다. 원본에서 사라진(원본 없음) 매핑만 옮깁니다.");
        }
        SourceMappingService.ModelTable model = mapping.getTableId() == null ? null : state.modelTables.get(mapping.getTableId());
        if (model == null) throw SourceMappingService.invalid("모델 테이블이 지워진 매핑은 옮길 수 없습니다. 매핑을 해제해주세요.");
        SourceCatalogReader.Table source = state.sourceTables.get(request.sourceTableId());
        if (source == null) throw SourceMappingService.invalid("마지막으로 반영된 수집에 없는 원본 테이블입니다.");
        if (!"TABLE".equals(source.type())) throw SourceMappingService.invalid("VIEW 는 매핑할 수 없습니다. TABLE 만 매핑합니다.");
        SourceTableMapping taken = state.tableMappingBySource.get(source.id());
        if (taken != null) {
            throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "원본 테이블 {0} 은(는) 이미 매핑되었거나 매핑 예외입니다.", source.name());
        }
        SourceSchemaMapping schemaMapping = state.schemaMappingBySource.get(source.schemaId());
        if (schemaMapping == null || state.schemaStatus(schemaMapping) != Status.MAPPED || !model.schemaId().equals(schemaMapping.getSchemaId())) {
            throw SourceMappingService.invalid("새 원본 테이블 {0} 의 스키마가 모델 테이블 {1} 의 스키마에 매핑되어 있지 않습니다.", source.name(), model.name());
        }
        Map<String, Object> before = SourceMappingHistoryRecorder.table(mapping, mapping.getCompareName(), model.name());
        mapping.setSourceTableId(source.id());
        mapping.setSourceSchemaId(source.schemaId());
        mapping.setPathKey(source.pathKey());
        mapping.setCompareName(source.compareName());
        mapping.setCreationCycle(source.creationCycle());
        mapping.setReflectedOrder(state.snapshot.reflectedOrder());
        SourceTableMapping saved = mappingService.save(() -> tableMappingRepository.saveAndFlush(mapping));
        history.record(projectId, connectionId, saved.getId(), TargetType.TABLE, ChangeType.RENAME, before, SourceMappingHistoryRecorder.table(saved, source.name(), model.name()));
        return candidates(accountDto);
    }

    /**
     * 컬럼 매핑 확정·변경. 잠금을 잡은 뒤 같은 트랜잭션에서 그 TABLE 을 다시 비교해, 원본 컬럼이 원본에만 있고 모델 컬럼이 모델에만 있을 때만 저장한다
     * (같은 원본 컬럼의 기존 매핑을 바꾸는 경우는 그 매핑을 빼고 판단한다).
     */
    @Transactional
    public List<SourceRenameDto.ColumnMapping> confirmColumn(AccountDto accountDto, SourceRenameDto.ColumnRequest request) {
        Long projectId = mappingService.manage(accountDto);
        if (request == null || request.tableMappingId() == null || request.columnId() == null || request.sourceColumnName() == null || request.sourceColumnName().isBlank()) {
            throw SourceMappingService.invalid("원본 컬럼과 모델 컬럼을 골라주세요.");
        }
        Long connectionId = mappingService.lockBase(projectId);
        SourceMappingService.State state = basis(projectId, connectionId, request.connectionId(), request.reflectedOrder());
        SourceTableMapping table = state.tableMappingById.get(request.tableMappingId());
        if (table == null || table.isExclusion()) throw SourceMappingService.invalid("매핑을 찾을 수 없습니다.");
        Status tableStatus = state.tableRows().stream().filter(r -> table.getId().equals(r.mappingId())).map(r -> r.status()).findFirst().orElse(null);
        if (tableStatus != Status.MAPPED) throw SourceMappingService.invalid("매핑됨 상태인 TABLE 매핑에서만 컬럼 매핑을 확정할 수 있습니다.");
        Integer order = state.snapshot.reflectedOrder();
        SourceCatalogReader.TableDetail detail = reader.tableDetails(projectId, connectionId, order, Set.of(table.getSourceTableId())).get(table.getSourceTableId());
        SourceCatalogReader.Column source = detail == null ? null : detail.columns().stream().filter(c -> c.name().equals(request.sourceColumnName())).findFirst().orElse(null);
        if (source == null) throw SourceMappingService.invalid("마지막으로 반영된 수집의 원본 테이블에 컬럼 {0} 이(가) 없습니다.", request.sourceColumnName());
        Object[] model = columnMappingRepository.modelColumns(projectId, List.of(request.columnId())).stream().findFirst().orElse(null);
        if (model == null || !Objects.equals(model[1], table.getTableId())) throw SourceMappingService.invalid("매핑된 모델 테이블의 컬럼이 아닙니다.");
        String modelName = (String) model[2];
        if (source.compareName().equals(reader.compareName(state.connection, NameKind.COLUMN, modelName))) {
            throw SourceMappingService.invalid("이름이 같은 컬럼은 매핑하지 않아도 같은 컬럼으로 봅니다.");
        }
        List<SourceColumnMapping> existing = state.columnMappingsOf(table.getId());
        SourceColumnMapping bySource = existing.stream().filter(c -> c.getCompareName().equals(source.compareName())).findFirst().orElse(null);
        SourceColumnMapping byModel = existing.stream().filter(c -> request.columnId().equals(c.getColumnId())).findFirst().orElse(null);
        if (byModel != null && byModel != bySource) {
            throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "모델 컬럼 {0} 은(는) 이미 원본 컬럼 {1} 에 매핑되어 있습니다.", modelName, byModel.getSourceColumnName());
        }
        if (bySource != null && request.columnId().equals(bySource.getColumnId())) return columnMappings(accountDto, table.getId());
        requireUnpaired(accountDto, state, table, source, modelName, bySource);

        Map<String, Object> before = bySource == null ? null
                : SourceMappingHistoryRecorder.column(bySource, state.sourceTableName(table), state.modelTableName(table.getTableId()), state.modelColumnName(bySource.getColumnId()));
        SourceColumnMapping mapping = bySource != null ? bySource : SourceColumnMapping.builder().projectId(projectId).connectionId(connectionId).tableMappingId(table.getId())
                .compareName(source.compareName()).build();
        mapping.setSourceColumnName(source.name());
        mapping.setColumnId(request.columnId());
        mapping.setReflectedOrder(order);
        SourceColumnMapping saved = mappingService.save(() -> columnMappingRepository.saveAndFlush(mapping));
        history.record(projectId, connectionId, table.getId(), TargetType.COLUMN, bySource == null ? ChangeType.CONFIRM : ChangeType.CHANGE, before,
                SourceMappingHistoryRecorder.column(saved, state.sourceTableName(table), state.modelTableName(table.getTableId()), modelName));
        return columnMappings(accountDto, table.getId());
    }

    /** 컬럼 매핑 해제 : 행만 지운다 (모델 컬럼은 그대로, 다시 이름으로 짝짓는다) */
    @Transactional
    public Map<String, Object> releaseColumn(AccountDto accountDto, Long columnMappingId) {
        return releaseColumn(accountDto, columnMappingId, null);
    }

    /**
     * 컬럼 매핑 해제 (M8-C4c-1 C4c-D03) : 기준 연결 행 배타(매핑 쓰기 공통 잠금) → 매핑 행 → 화면이 본 수정 일시(modifiedAt) 비교 — 그 사이 다른 모델 컬럼으로 바뀌었으면 409
     * (같은 행을 고쳐 쓰므로 ID 만으로는 막지 못했다). 지우고 다시 만든 매핑은 새 ID 라 예전 요청은 '찾을 수 없음'. modifiedAt null = 내부 호출 (HTTP 는 필수)
     */
    @Transactional
    public Map<String, Object> releaseColumn(AccountDto accountDto, Long columnMappingId, java.time.LocalDateTime modifiedAt) {
        Long projectId = mappingService.manage(accountDto);
        Long connectionId = mappingService.lockBase(projectId);
        SourceColumnMapping mapping = columnMappingRepository.findByIdAndProjectId(columnMappingId, projectId).filter(m -> m.getConnectionId().equals(connectionId))
                .orElseThrow(() -> SourceMappingService.invalid("컬럼 매핑을 찾을 수 없습니다."));
        com.lexorma.common.jpa.ModelBase.verify(mapping, modifiedAt, mapping.getSourceColumnName());
        SourceMappingService.State state = mappingService.state(projectId, connectionId);
        SourceTableMapping table = state.tableMappingById.get(mapping.getTableMappingId());
        history.record(projectId, connectionId, mapping.getTableMappingId(), TargetType.COLUMN, ChangeType.RELEASE,
                SourceMappingHistoryRecorder.column(mapping, table == null ? null : state.sourceTableName(table), table == null ? null : state.modelTableName(table.getTableId()),
                        state.modelColumnName(mapping.getColumnId())), null);
        columnMappingRepository.delete(mapping);
        return Map.of("result", true);
    }

    // ------------------------------------------------------------------ 내부

    /** 잠근 뒤의 상태. 요청이 본 기준(연결·반영 순서)과 다르면 409 */
    private SourceMappingService.State basis(Long projectId, Long connectionId, Long expectedConnection, Integer expectedOrder) {
        SourceMappingService.State state = mappingService.requireSnapshot(projectId, connectionId);
        if ((expectedConnection != null && !expectedConnection.equals(connectionId)) || (expectedOrder != null && !expectedOrder.equals(state.snapshot.reflectedOrder()))) {
            throw new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "기준 연결이 바뀌었거나 새 수집이 반영되었습니다. 화면을 새로 고쳐주세요.");
        }
        return state;
    }

    /**
     * 원본 컬럼이 지금 비교에서 원본에만 있고(바꾸는 기존 매핑이 있으면 그 매핑의 모델 컬럼과 짝지어진 것은 괜찮다) 모델 컬럼이 모델에만 있는지.
     * 비교는 지금 저장된 컬럼 매핑을 반영한다 (M-4d 와 같은 계산, 같은 트랜잭션·잠금 뒤)
     */
    private void requireUnpaired(AccountDto accountDto, SourceMappingService.State state, SourceTableMapping table, SourceCatalogReader.Column source, String modelName,
                                 SourceColumnMapping changing) {
        SourceCompareDto.TableDetail detail = compareService.table(accountDto, state.connection.id(), state.snapshot.reflectedOrder(), "S" + table.getSourceTableId(), "COLUMN");
        String changingModel = changing == null ? null : state.modelColumnName(changing.getColumnId());
        boolean sourceFree = false;
        boolean modelFree = false;
        for (SourceCompareDto.ObjectDiff object : detail.objects()) {
            if (object.kind() != ObjectKind.COLUMN) continue;
            boolean onlySource = object.modelName() == null && (object.status() == CompareStatus.ADDED_IN_SOURCE || object.status() == CompareStatus.RENAME_CANDIDATE);
            boolean onlyModel = object.sourceName() == null && (object.status() == CompareStatus.ADDED_IN_MODEL || object.status() == CompareStatus.RENAME_CANDIDATE);
            if (source.name().equals(object.sourceName()) && (onlySource || (changingModel != null && changingModel.equals(object.modelName())))) sourceFree = true;
            if (modelName.equals(object.modelName()) && onlyModel) modelFree = true;
        }
        if (!sourceFree) throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "원본 컬럼 {0} 은(는) 이미 다른 모델 컬럼과 짝지어져 있습니다.", source.name());
        if (!modelFree) throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "모델 컬럼 {0} 은(는) 이미 다른 원본 컬럼과 짝지어져 있습니다.", modelName);
    }

    private List<SourceRenameDto.TableCandidate> tableCandidates(Long projectId, SourceMappingService.State state) {
        List<SourceTableMapping> removed = state.tableMappings.stream()
                .filter(m -> !m.isExclusion() && m.getTableId() != null && state.modelTables.containsKey(m.getTableId()) && !state.sourceTables.containsKey(m.getSourceTableId()))
                .sorted(Comparator.comparing(SourceTableMapping::getId)).toList();
        if (removed.isEmpty()) return List.of();
        // 후보 원본 : 매핑 행이 없는(미매핑, 예외 아님) TABLE 이고, 그 스키마가 모델 TABLE 의 스키마에 매핑되어 있다
        Map<Long, List<SourceCatalogReader.Table>> freeBySchema = new HashMap<>();
        for (SourceCatalogReader.Table table : state.snapshot.tables()) {
            if (!"TABLE".equals(table.type()) || state.tableMappingBySource.containsKey(table.id())) continue;
            SourceSchemaMapping schemaMapping = state.schemaMappingBySource.get(table.schemaId());
            if (schemaMapping == null || state.schemaStatus(schemaMapping) != Status.MAPPED) continue;
            freeBySchema.computeIfAbsent(schemaMapping.getSchemaId(), id -> new ArrayList<>()).add(table);
        }
        Set<Long> sourceIds = new HashSet<>();
        for (SourceTableMapping m : removed) {
            freeBySchema.getOrDefault(state.modelTables.get(m.getTableId()).schemaId(), List.of()).forEach(t -> sourceIds.add(t.id()));
        }
        if (sourceIds.isEmpty()) return List.of();
        Map<Long, SourceCatalogReader.TableDetail> details = reader.tableDetails(projectId, state.connection.id(), state.snapshot.reflectedOrder(), sourceIds);
        Map<Long, Set<String>> modelColumns = new HashMap<>();
        Map<Long, String> keys = state.columnKeys();
        List<Long> tableIds = removed.stream().map(SourceTableMapping::getTableId).distinct().toList();
        for (Object[] row : columnMappingRepository.columnsOfTables(projectId, tableIds)) {
            String key = keys.get((Long) row[0]);
            modelColumns.computeIfAbsent((Long) row[1], id -> new HashSet<>()).add(key != null ? key : reader.compareName(state.connection, NameKind.COLUMN, (String) row[2]));
        }
        List<SourceRenameDto.TableCandidate> found = new ArrayList<>();
        Map<Long, Integer> perMapping = new HashMap<>();
        Map<Long, Integer> perSource = new HashMap<>();
        for (SourceTableMapping m : removed) {
            SourceMappingService.ModelTable model = state.modelTables.get(m.getTableId());
            Set<String> modelNames = modelColumns.getOrDefault(model.id(), Set.of());
            for (SourceCatalogReader.Table candidate : freeBySchema.getOrDefault(model.schemaId(), List.of())) {
                SourceCatalogReader.TableDetail detail = details.get(candidate.id());
                if (detail == null) continue;
                Set<String> sourceNames = new HashSet<>();
                detail.columns().forEach(c -> sourceNames.add(c.compareName()));
                int matched = (int) sourceNames.stream().filter(modelNames::contains).count();
                int larger = Math.max(sourceNames.size(), modelNames.size());
                double score = larger == 0 ? 0 : (double) matched / larger;
                if (matched == 0 || score < TABLE_SCORE_MIN) continue;
                SchemaInfo schema = state.modelSchemaById.get(model.schemaId());
                found.add(new SourceRenameDto.TableCandidate(m.getId(), model.id(), schema == null ? null : schema.getSchemaName(), model.name(), model.korName(),
                        m.getCompareName(), candidate.id(), state.sourceSchemas.get(candidate.schemaId()).schemaName(), candidate.name(), matched, sourceNames.size(),
                        modelNames.size(), Math.round(score * 1000) / 1000.0, false));
                perMapping.merge(m.getId(), 1, Integer::sum);
                perSource.merge(candidate.id(), 1, Integer::sum);
            }
        }
        return found.stream().map(c -> perMapping.get(c.mappingId()) > 1 || perSource.get(c.sourceTableId()) > 1
                        ? new SourceRenameDto.TableCandidate(c.mappingId(), c.tableId(), c.schemaName(), c.tableName(), c.tableKorName(), c.removedSourceTableName(),
                        c.sourceTableId(), c.sourceSchemaName(), c.sourceTableName(), c.matchedColumns(), c.sourceColumns(), c.modelColumns(), c.score(), true) : c)
                .sorted(Comparator.comparing(SourceRenameDto.TableCandidate::tableName).thenComparing(SourceRenameDto.TableCandidate::score, Comparator.reverseOrder()))
                .toList();
    }

    private List<SourceRenameDto.ColumnCandidate> columnCandidates(AccountDto accountDto, SourceMappingService.State state) {
        Integer order = state.snapshot.reflectedOrder();
        List<SourceCompareDto.RenameCandidate> renames = new ArrayList<>();
        for (int page = 0; ; page++) {
            SourceCompareDto.RenamePage chunk = compareService.renames(accountDto, state.connection.id(), order, "COLUMN", null, page, 500);
            renames.addAll(chunk.rows());
            if (chunk.rows().size() < 500) break;
        }
        if (renames.isEmpty()) return List.of();
        List<Long> tableIds = renames.stream().map(SourceCompareDto.RenameCandidate::tableId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object[]>> columns = new HashMap<>();
        for (Object[] row : columnMappingRepository.columnsOfTables(accountDto.getProjectId(), tableIds)) {
            columns.computeIfAbsent((Long) row[1], id -> new LinkedHashMap<>()).put((String) row[2], row);
        }
        List<SourceRenameDto.ColumnCandidate> rows = new ArrayList<>();
        for (SourceCompareDto.RenameCandidate r : renames) {
            SourceTableMapping table = state.tableMappingBySource.get(r.sourceTableId());
            Object[] model = columns.getOrDefault(r.tableId(), Map.of()).get(r.modelName());
            if (table == null || model == null) continue;
            rows.add(new SourceRenameDto.ColumnCandidate(table.getId(), r.sourceTableId(), r.sourceTableName(), r.tableId(), r.tableName(), r.sourceName(), (Long) model[0],
                    r.modelName(), Boolean.TRUE.equals(model[3]), r.basis(), r.signature(), r.ambiguous()));
        }
        return rows;
    }

    private static Map<String, Object> parse(String json) {
        if (json == null) return null;
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }
}
