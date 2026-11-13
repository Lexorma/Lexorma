package com.lexorma.data.sourcemapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.core.source.SourceCatalogReader.NameKind;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Candidate;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Reason;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.domain.entity.ProjectSourceConnection;
import com.lexorma.data.sourcemapping.domain.entity.SourceSchemaMapping;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.ProjectSourceConnectionRepository;
import com.lexorma.data.sourcemapping.repository.SourceSchemaMappingRepository;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 원본 DB 스키마·TABLE 매핑 (M-4c, docs/source-mapping-m4c.md · 정책 docs/m4-foundation.md 5장).
 * <ul>
 *   <li>프로젝트마다 가져오기 기준 원본 연결 하나. 매핑은 그 연결 기준이고, 기준을 바꾸면 이전 연결의 매핑은 지우지 않고 남긴다 (다시 고르면 쓰인다)</li>
 *   <li>스키마·TABLE 모두 연결 안에서 1:1. 후보(같은 이름·PostgreSQL public → DEFAULT)는 제안만 하고, 사용자가 확정해야 저장한다 — 자동 매핑 없음</li>
 *   <li>원본은 core 의 읽기 전용 계약(SourceCatalogReader)으로 마지막 반영 시점만 읽는다. 원본 DB·물리 모델은 바꾸지 않는다</li>
 *   <li>상태(재확인·충돌·원본 없음·모델 없음)는 저장하지 않고 매번 계산한다</li>
 *   <li>변경은 그 프로젝트의 관리자 또는 ADMIN (지금의 DB 기준). 프로젝트 행(공유) → 기준 연결 행(배타) 순서로 잠가 프로젝트 안에서 한 번에 하나씩 처리하고,
 *       1:1 은 DB 유니크 인덱스로도 막는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceMappingService {

    private static final int SIZE_MAX = 500;

    private final SourceCatalogReader reader;
    private final ProjectSourceConnectionRepository baseRepository;
    private final SourceSchemaMappingRepository schemaMappingRepository;
    private final SourceTableMappingRepository tableMappingRepository;
    private final SchemaInfoRepository schemaInfoRepository;
    private final ExecutionContextVerifier verifier;
    private final ProjectWriteGuard projectWriteGuard;

    // ------------------------------------------------------------------ 조회

    public SourceMappingDto.Overview overview(AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        List<SourceCatalogReader.Connection> connections = reader.connections(projectId);
        Optional<ProjectSourceConnection> base = baseRepository.findByProjectId(projectId);
        List<SourceMappingDto.Preserved> preserved = preserved(projectId, base.map(ProjectSourceConnection::getConnectionId).orElse(null), connections);
        List<SourceMappingDto.Connection> connectionDtos = connections.stream().map(SourceMappingService::connectionDto).toList();
        State state = base.map(b -> state(projectId, b.getConnectionId())).orElse(null);
        if (state == null || state.connection == null) {
            List<SourceMappingDto.ModelSchema> modelSchemas = schemaInfoRepository.findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(projectId).stream()
                    .map(s -> new SourceMappingDto.ModelSchema(s.getId(), s.getSchemaName(), Boolean.TRUE.equals(s.getIsDefault()), null)).toList();
            return new SourceMappingDto.Overview(null, connectionDtos, null, null, null, Map.of(), Map.of(), 0, preserved, List.of(), modelSchemas);
        }
        List<SourceMappingDto.SchemaRow> schemas = state.schemaRows();
        List<SourceMappingDto.TableRow> tables = state.tableRows();
        return new SourceMappingDto.Overview(connectionDto(state.connection), connectionDtos, state.snapshot.reflectedOrder(), state.snapshot.executionId(),
                state.snapshot.reflectedAt(), counts(schemas.stream().map(SourceMappingDto.SchemaRow::status).toList()),
                counts(tables.stream().map(SourceMappingDto.TableRow::status).toList()),
                (int) state.snapshot.tables().stream().filter(t -> !"TABLE".equals(t.type())).count(), preserved, schemas, state.modelSchemaDtos());
    }

    /** 원본 TABLE 목록 (상태·스키마·이름으로 거르고 쪽으로 나눈다). 기준 연결이 없으면 빈 쪽 */
    public SourceMappingDto.TablePage tables(AccountDto accountDto, Long sourceSchemaId, String status, String keyword, int page, int size) {
        Long projectId = accountDto.getProjectId();
        State state = baseRepository.findByProjectId(projectId).map(b -> state(projectId, b.getConnectionId())).orElse(null);
        if (state == null || state.connection == null) return new SourceMappingDto.TablePage(List.of(), 0, Map.of());
        List<SourceMappingDto.TableRow> all = state.tableRows();
        String word = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Status wanted = status == null || status.isBlank() ? null : Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        List<SourceMappingDto.TableRow> scoped = all.stream()
                .filter(r -> sourceSchemaId == null || sourceSchemaId.equals(r.sourceSchemaId()))
                .filter(r -> word.isEmpty() || contains(r.sourceTableName(), word) || contains(r.tableName(), word) || contains(r.tableKorName(), word))
                .toList();
        Map<Status, Long> counts = counts(scoped.stream().map(SourceMappingDto.TableRow::status).toList());
        List<SourceMappingDto.TableRow> filtered = scoped.stream().filter(r -> wanted == null || r.status() == wanted).toList();
        int pageSize = Math.min(Math.max(size, 1), SIZE_MAX);
        int from = Math.min(Math.max(page, 0) * pageSize, filtered.size());
        return new SourceMappingDto.TablePage(filtered.subList(from, Math.min(from + pageSize, filtered.size())), filtered.size(), counts);
    }

    /** 매핑할 모델 테이블 고르기 : 그 모델 스키마의 테이블 (이미 매핑된 것은 표시만) */
    public List<SourceMappingDto.ModelTable> modelTables(AccountDto accountDto, Long schemaId, String keyword) {
        Long projectId = accountDto.getProjectId();
        SchemaInfo schema = schemaInfoRepository.findByIdAndProjectId(schemaId, projectId).orElseThrow(() -> invalid("모델 스키마를 찾을 수 없습니다."));
        Long connectionId = baseRepository.findByProjectId(projectId).map(ProjectSourceConnection::getConnectionId).orElse(null);
        Map<Long, Long> mapped = connectionId == null ? Map.of() : tableMappingRepository.findAllByProjectIdAndConnectionId(projectId, connectionId).stream()
                .filter(m -> m.getTableId() != null).collect(Collectors.toMap(SourceTableMapping::getTableId, SourceTableMapping::getSourceTableId, (a, b) -> a));
        String word = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return tableMappingRepository.modelTables(projectId).stream()
                .filter(row -> schemaId.equals(row[2]))
                .filter(row -> word.isEmpty() || contains((String) row[1], word) || contains((String) row[3], word))
                .sorted(Comparator.comparing(row -> (String) row[1]))
                .limit(200)
                .map(row -> new SourceMappingDto.ModelTable((Long) row[0], (String) row[1], (String) row[3], schemaId, schema.getSchemaName(), mapped.get((Long) row[0])))
                .toList();
    }

    // ------------------------------------------------------------------ 기준 연결

    /**
     * 기준 연결을 바꾸거나(connectionId) 해제한다(null). 지금 기준 연결에 매핑이 있으면 acknowledge 없이는 SOURCE_MAPPING_CONFLICT 로 영향을 알린다.
     * 매핑은 지우지 않는다 : 그 연결의 매핑으로 남고(다른 연결에 옮기지 않는다) 그 연결을 다시 기준으로 고르면 다시 쓰인다.
     */
    @Transactional
    public SourceMappingDto.Overview setBase(AccountDto accountDto, SourceMappingDto.BaseRequest request) {
        Long projectId = manage(accountDto);
        Long target = request == null ? null : request.connectionId();
        if (target != null && reader.connection(projectId, target).isEmpty()) throw new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        Optional<ProjectSourceConnection> current = baseRepository.findForUpdate(projectId);
        Long currentId = current.map(ProjectSourceConnection::getConnectionId).orElse(null);
        if (Objects.equals(currentId, target)) return overview(accountDto);
        if (currentId != null && !Boolean.TRUE.equals(request == null ? null : request.acknowledge())) {
            long schemaCount = schemaMappingRepository.findAllByProjectIdAndConnectionId(projectId, currentId).size();
            long tableCount = tableMappingRepository.findAllByProjectIdAndConnectionId(projectId, currentId).size();
            if (schemaCount + tableCount > 0) {
                throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "지금 기준 연결의 매핑(스키마 {0}건, 테이블 {1}건)은 지우지 않고 그 연결의 매핑으로 남습니다. 확인 후 다시 요청해주세요.",
                        schemaCount, tableCount);
            }
        }
        try {
            if (target == null) {
                current.ifPresent(baseRepository::delete);
            } else if (current.isPresent()) {
                current.get().setConnectionId(target);
            } else {
                baseRepository.save(ProjectSourceConnection.builder().projectId(projectId).connectionId(target).build());
            }
            baseRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw concurrent();
        }
        return overview(accountDto);
    }

    // ------------------------------------------------------------------ 스키마 매핑

    /** 원본 스키마를 모델 스키마에 매핑(새로 또는 바꾸기). 마지막 반영 시점에 있는 원본 스키마만 */
    @Transactional
    public SourceMappingDto.SchemaRow mapSchema(AccountDto accountDto, SourceMappingDto.SchemaRequest request) {
        Long projectId = manage(accountDto);
        if (request == null || request.sourceSchemaId() == null || request.schemaId() == null) throw invalid("원본 스키마와 모델 스키마를 골라주세요.");
        Long connectionId = lockBase(projectId);
        State state = requireSnapshot(projectId, connectionId);
        SourceCatalogReader.Schema source = state.sourceSchemas.get(request.sourceSchemaId());
        if (source == null) throw invalid("마지막으로 반영된 수집에 없는 원본 스키마입니다.");
        SchemaInfo schema = schemaInfoRepository.findByIdAndProjectId(request.schemaId(), projectId).orElseThrow(() -> invalid("모델 스키마를 찾을 수 없습니다."));
        SourceSchemaMapping taken = state.schemaMappingByModel.get(schema.getId());
        if (taken != null && !taken.getSourceSchemaId().equals(source.id())) {
            throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "모델 스키마 {0} 은(는) 이미 원본 스키마 {1} 에 매핑되어 있습니다.", schema.getSchemaName(),
                    taken.getSourceSchemaName());
        }
        SourceSchemaMapping mapping = Optional.ofNullable(state.schemaMappingBySource.get(source.id()))
                .orElseGet(() -> SourceSchemaMapping.builder().projectId(projectId).connectionId(connectionId).sourceSchemaId(source.id()).build());
        mapping.setPathKey(source.pathKey());
        mapping.setSourceSchemaName(source.schemaName());
        mapping.setCreationCycle(source.creationCycle());
        mapping.setReflectedOrder(state.snapshot.reflectedOrder());
        mapping.setSchemaId(schema.getId());
        save(() -> schemaMappingRepository.saveAndFlush(mapping));
        return state(projectId, connectionId).schemaRows().stream().filter(r -> source.id().equals(r.sourceSchemaId())).findFirst().orElseThrow();
    }

    /** 스키마 매핑 해제 : 그 원본 스키마의 TABLE 매핑도 함께 해제한다 (스키마 매핑 없이 남은 TABLE 매핑은 충돌이 된다) */
    @Transactional
    public Map<String, Object> unmapSchema(AccountDto accountDto, Long sourceSchemaId) {
        Long projectId = manage(accountDto);
        Long connectionId = lockBase(projectId);
        SourceSchemaMapping mapping = schemaMappingRepository.findByProjectIdAndConnectionIdAndSourceSchemaId(projectId, connectionId, sourceSchemaId)
                .orElseThrow(() -> invalid("매핑되지 않은 원본 스키마입니다."));
        List<SourceTableMapping> tables = tableMappingRepository.findAllByProjectIdAndConnectionIdAndSourceSchemaId(projectId, connectionId, sourceSchemaId);
        tableMappingRepository.deleteAll(tables);
        schemaMappingRepository.delete(mapping);
        return Map.of("result", true, "releasedTables", tables.size());
    }

    // ------------------------------------------------------------------ TABLE 매핑

    /**
     * 원본 TABLE 을 모델 TABLE 에 매핑(새로 또는 바꾸기). 원본은 마지막 반영 시점에 있는 TABLE(VIEW 아님)이고, 그 스키마가 유효하게 매핑되어 있어야 하며,
     * 모델 테이블은 그 매핑된 모델 스키마에 있어야 한다. 모델 테이블이 이미 다른 원본에 매핑되어 있으면 거절한다.
     */
    @Transactional
    public SourceMappingDto.TableRow mapTable(AccountDto accountDto, SourceMappingDto.TableRequest request) {
        Long projectId = manage(accountDto);
        if (request == null || request.sourceTableId() == null || request.tableId() == null) throw invalid("원본 테이블과 모델 테이블을 골라주세요.");
        Long connectionId = lockBase(projectId);
        State state = requireSnapshot(projectId, connectionId);
        SourceCatalogReader.Table source = state.sourceTables.get(request.sourceTableId());
        if (source == null) throw invalid("마지막으로 반영된 수집에 없는 원본 테이블입니다.");
        if (!"TABLE".equals(source.type())) throw invalid("VIEW 는 매핑할 수 없습니다. TABLE 만 매핑합니다.");
        SourceSchemaMapping schemaMapping = state.schemaMappingBySource.get(source.schemaId());
        if (schemaMapping == null || state.schemaStatus(schemaMapping) != Status.MAPPED) {
            throw invalid("원본 스키마 {0} 의 스키마 매핑을 먼저 확정해주세요.", state.sourceSchemas.get(source.schemaId()).schemaName());
        }
        ModelTable table = state.modelTables.get(request.tableId());
        if (table == null) throw invalid("모델 테이블을 찾을 수 없습니다.");
        if (!table.schemaId().equals(schemaMapping.getSchemaId())) {
            throw invalid("모델 테이블 {0} 은(는) 원본 스키마 {1} 에 매핑된 모델 스키마에 있지 않습니다.", table.name(), state.sourceSchemas.get(source.schemaId()).schemaName());
        }
        SourceTableMapping taken = state.tableMappingByModel.get(table.id());
        if (taken != null && !taken.getSourceTableId().equals(source.id())) {
            SourceCatalogReader.Table other = state.sourceTables.get(taken.getSourceTableId());
            throw new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "모델 테이블 {0} 은(는) 이미 원본 테이블 {1} 에 매핑되어 있습니다.", table.name(),
                    other == null ? taken.getCompareName() : other.name());
        }
        SourceTableMapping mapping = Optional.ofNullable(state.tableMappingBySource.get(source.id()))
                .orElseGet(() -> SourceTableMapping.builder().projectId(projectId).connectionId(connectionId).sourceTableId(source.id()).build());
        mapping.setSourceSchemaId(source.schemaId());
        mapping.setPathKey(source.pathKey());
        mapping.setCompareName(source.compareName());
        mapping.setCreationCycle(source.creationCycle());
        mapping.setReflectedOrder(state.snapshot.reflectedOrder());
        mapping.setTableId(table.id());
        SourceTableMapping saved = save(() -> tableMappingRepository.saveAndFlush(mapping));
        return state(projectId, connectionId).tableRows().stream().filter(r -> saved.getId().equals(r.mappingId())).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> unmapTable(AccountDto accountDto, Long mappingId) {
        Long projectId = manage(accountDto);
        Long connectionId = lockBase(projectId);
        SourceTableMapping mapping = tableMappingRepository.findByIdAndProjectId(mappingId, projectId)
                .filter(m -> m.getConnectionId().equals(connectionId)).orElseThrow(() -> invalid("매핑을 찾을 수 없습니다."));
        tableMappingRepository.delete(mapping);
        return Map.of("result", true);
    }

    // ------------------------------------------------------------------ 내부

    /** 그 프로젝트의 관리자 또는 ADMIN (지금의 DB 기준). 프로젝트 행을 공유 잠금으로 먼저 잡는다 (잠금 순서 : 프로젝트 → 기준 연결 행) */
    private Long manage(AccountDto accountDto) {
        Long projectId = verifier.verifyProjectManager(ExecutionContext.user(accountDto)).projectId();
        projectWriteGuard.lock(projectId);
        return projectId;
    }

    /** 기준 연결 행을 배타 잠금으로 잡는다 (매핑 변경을 프로젝트 안에서 한 번에 하나씩) */
    private Long lockBase(Long projectId) {
        return baseRepository.findForUpdate(projectId).map(ProjectSourceConnection::getConnectionId).orElseThrow(() -> invalid("가져오기 기준 연결을 먼저 정해주세요."));
    }

    private State requireSnapshot(Long projectId, Long connectionId) {
        State state = state(projectId, connectionId);
        if (state.connection == null) throw new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        if (state.snapshot.reflectedOrder() == null) throw invalid("기준 연결에 완료된 수집이 없어 매핑을 확정할 수 없습니다. 먼저 메타데이터를 수집해주세요.");
        return state;
    }

    private <T> T save(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            throw concurrent();
        }
    }

    private static CustomException concurrent() {
        return new CustomException(ErrorCode.SOURCE_MAPPING_CONFLICT, "다른 사용자가 같은 매핑을 바꾸었습니다. 화면을 새로 고친 뒤 다시 시도해주세요.");
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.SOURCE_MAPPING_INVALID, message, args);
    }

    private List<SourceMappingDto.Preserved> preserved(Long projectId, Long baseId, List<SourceCatalogReader.Connection> connections) {
        Map<Long, long[]> counts = new LinkedHashMap<>();
        for (Object[] row : schemaMappingRepository.countByConnection(projectId)) counts.computeIfAbsent((Long) row[0], id -> new long[2])[0] = (Long) row[1];
        for (Object[] row : tableMappingRepository.countByConnection(projectId)) counts.computeIfAbsent((Long) row[0], id -> new long[2])[1] = (Long) row[1];
        Map<Long, String> names = connections.stream().collect(Collectors.toMap(SourceCatalogReader.Connection::id, SourceCatalogReader.Connection::name));
        return counts.entrySet().stream().filter(e -> !e.getKey().equals(baseId))
                .map(e -> new SourceMappingDto.Preserved(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1])).toList();
    }

    private static SourceMappingDto.Connection connectionDto(SourceCatalogReader.Connection c) {
        return new SourceMappingDto.Connection(c.id(), c.name(), c.dbType(), c.enabled(), c.collectable());
    }

    private static Map<Status, Long> counts(List<Status> statuses) {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        statuses.forEach(s -> counts.merge(s, 1L, Long::sum));
        return counts;
    }

    private static boolean contains(String value, String word) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(word);
    }

    private record ModelTable(Long id, String name, Long schemaId, String korName) {
    }

    private State state(Long projectId, Long connectionId) {
        Optional<SourceCatalogReader.Snapshot> snapshot = reader.latest(projectId, connectionId);
        List<SchemaInfo> schemas = schemaInfoRepository.findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(projectId);
        Map<Long, ModelTable> tables = new HashMap<>();
        for (Object[] row : tableMappingRepository.modelTables(projectId)) tables.put((Long) row[0], new ModelTable((Long) row[0], (String) row[1], (Long) row[2], (String) row[3]));
        return new State(snapshot.orElse(null), schemas, tables, schemaMappingRepository.findAllByProjectIdAndConnectionId(projectId, connectionId),
                tableMappingRepository.findAllByProjectIdAndConnectionId(projectId, connectionId));
    }

    /** 한 번의 조회에 필요한 원본(마지막 반영)·모델·매핑을 모아 상태와 후보를 계산한다 */
    private final class State {

        final SourceCatalogReader.Connection connection;
        final SourceCatalogReader.Snapshot snapshot;
        final Map<Long, SourceCatalogReader.Schema> sourceSchemas;
        final Map<Long, SourceCatalogReader.Table> sourceTables;
        final List<SchemaInfo> modelSchemas;
        final Map<Long, SchemaInfo> modelSchemaById;
        final Map<Long, ModelTable> modelTables;
        final List<SourceSchemaMapping> schemaMappings;
        final Map<Long, SourceSchemaMapping> schemaMappingBySource;
        final Map<Long, SourceSchemaMapping> schemaMappingByModel;
        final List<SourceTableMapping> tableMappings;
        final Map<Long, SourceTableMapping> tableMappingBySource;
        final Map<Long, SourceTableMapping> tableMappingByModel;

        State(SourceCatalogReader.Snapshot snapshot, List<SchemaInfo> modelSchemas, Map<Long, ModelTable> modelTables, List<SourceSchemaMapping> schemaMappings,
              List<SourceTableMapping> tableMappings) {
            this.snapshot = snapshot;
            this.connection = snapshot == null ? null : snapshot.connection();
            this.sourceSchemas = snapshot == null ? Map.of() : snapshot.schemas().stream().collect(Collectors.toMap(SourceCatalogReader.Schema::id, Function.identity()));
            this.sourceTables = snapshot == null ? Map.of() : snapshot.tables().stream().collect(Collectors.toMap(SourceCatalogReader.Table::id, Function.identity()));
            this.modelSchemas = modelSchemas;
            this.modelSchemaById = modelSchemas.stream().collect(Collectors.toMap(SchemaInfo::getId, Function.identity()));
            this.modelTables = modelTables;
            this.schemaMappings = schemaMappings;
            this.schemaMappingBySource = schemaMappings.stream().collect(Collectors.toMap(SourceSchemaMapping::getSourceSchemaId, Function.identity()));
            this.schemaMappingByModel = schemaMappings.stream().filter(m -> m.getSchemaId() != null).collect(Collectors.toMap(SourceSchemaMapping::getSchemaId, Function.identity()));
            this.tableMappings = tableMappings;
            this.tableMappingBySource = tableMappings.stream().collect(Collectors.toMap(SourceTableMapping::getSourceTableId, Function.identity()));
            this.tableMappingByModel = tableMappings.stream().filter(m -> m.getTableId() != null).collect(Collectors.toMap(SourceTableMapping::getTableId, Function.identity()));
        }

        Status schemaStatus(SourceSchemaMapping mapping) {
            if (mapping.getSchemaId() == null || !modelSchemaById.containsKey(mapping.getSchemaId())) return Status.MODEL_MISSING;
            SourceCatalogReader.Schema source = sourceSchemas.get(mapping.getSourceSchemaId());
            if (source == null) return Status.SOURCE_MISSING;
            if (source.creationCycle() != mapping.getCreationCycle()) return Status.RECHECK;
            return Status.MAPPED;
        }

        private Reason schemaReason(Status status) {
            return switch (status) {
                case MODEL_MISSING -> Reason.MODEL_DELETED;
                case SOURCE_MISSING -> Reason.REMOVED;
                case RECHECK -> Reason.RECREATED;
                default -> null;
            };
        }

        List<SourceMappingDto.SchemaRow> schemaRows() {
            List<SourceMappingDto.SchemaRow> rows = new ArrayList<>();
            Map<Long, int[]> counts = new HashMap<>();
            for (SourceCatalogReader.Table t : sourceTables.values()) counts.computeIfAbsent(t.schemaId(), id -> new int[2])["TABLE".equals(t.type()) ? 0 : 1]++;
            for (SourceCatalogReader.Schema source : snapshot.schemas()) {
                SourceSchemaMapping mapping = schemaMappingBySource.get(source.id());
                int[] count = counts.getOrDefault(source.id(), new int[2]);
                if (mapping == null) {
                    rows.add(new SourceMappingDto.SchemaRow(source.id(), source.schemaName(), count[0], count[1], null, null, null, false, null, Status.UNMAPPED,
                            null, schemaCandidate(source)));
                } else {
                    rows.add(schemaRow(mapping, source.schemaName(), count));
                }
            }
            // 원본에서 사라진 스키마의 매핑 : 확정 때의 이름으로
            for (SourceSchemaMapping mapping : schemaMappings) {
                if (!sourceSchemas.containsKey(mapping.getSourceSchemaId())) rows.add(schemaRow(mapping, mapping.getSourceSchemaName(), new int[2]));
            }
            return rows;
        }

        private SourceMappingDto.SchemaRow schemaRow(SourceSchemaMapping mapping, String sourceName, int[] count) {
            Status status = schemaStatus(mapping);
            SchemaInfo model = mapping.getSchemaId() == null ? null : modelSchemaById.get(mapping.getSchemaId());
            return new SourceMappingDto.SchemaRow(mapping.getSourceSchemaId(), sourceName, count[0], count[1], mapping.getId(), model == null ? null : model.getId(),
                    model == null ? null : model.getSchemaName(), model != null && Boolean.TRUE.equals(model.getIsDefault()), mapping.getReflectedOrder(), status,
                    schemaReason(status), null);
        }

        /** 같은 이름(원본 DBMS 규칙)의 모델 스키마, 없으면 원본 DBMS 의 기본 스키마(PostgreSQL public) → 모델 기본 스키마. 제안일 뿐이다 */
        private Candidate schemaCandidate(SourceCatalogReader.Schema source) {
            SchemaInfo match = modelSchemas.stream()
                    .filter(s -> !Boolean.TRUE.equals(s.getIsDefault()) && source.compareName().equals(reader.compareName(connection, NameKind.SCHEMA, s.getSchemaName())))
                    .findFirst()
                    .orElseGet(() -> source.compareName().equals(connection.defaultSchemaCompareName())
                            ? modelSchemas.stream().filter(s -> Boolean.TRUE.equals(s.getIsDefault())).findFirst().orElse(null) : null);
            if (match == null) return null;
            SourceSchemaMapping taken = schemaMappingByModel.get(match.getId());
            return new Candidate(match.getId(), match.getSchemaName(), taken != null && !taken.getSourceSchemaId().equals(source.id()));
        }

        List<SourceMappingDto.TableRow> tableRows() {
            List<SourceMappingDto.TableRow> rows = new ArrayList<>();
            Map<Long, Map<String, ModelTable>> byModelSchema = new HashMap<>();
            for (ModelTable table : modelTables.values()) {
                byModelSchema.computeIfAbsent(table.schemaId(), id -> new HashMap<>())
                        .putIfAbsent(reader.compareName(connection, NameKind.TABLE, table.name()), table);
            }
            List<SourceCatalogReader.Table> sources = snapshot.tables().stream().filter(t -> "TABLE".equals(t.type()) || tableMappingBySource.containsKey(t.id()))
                    .sorted(Comparator.comparing((SourceCatalogReader.Table t) -> sourceSchemas.get(t.schemaId()).schemaName()).thenComparing(SourceCatalogReader.Table::name))
                    .toList();
            for (SourceCatalogReader.Table source : sources) {
                SourceTableMapping mapping = tableMappingBySource.get(source.id());
                String schemaName = sourceSchemas.get(source.schemaId()).schemaName();
                if (mapping != null) {
                    rows.add(tableRow(mapping, source, schemaName));
                    continue;
                }
                SourceSchemaMapping schemaMapping = schemaMappingBySource.get(source.schemaId());
                boolean schemaReady = schemaMapping != null && schemaStatus(schemaMapping) == Status.MAPPED;
                Candidate candidate = null;
                if (schemaReady) {
                    ModelTable match = byModelSchema.getOrDefault(schemaMapping.getSchemaId(), Map.of()).get(source.compareName());
                    if (match != null) {
                        SourceTableMapping taken = tableMappingByModel.get(match.id());
                        candidate = new Candidate(match.id(), match.name(), taken != null);
                    }
                }
                rows.add(new SourceMappingDto.TableRow(source.id(), source.schemaId(), schemaName, source.name(), source.comment(), null, null, null, null,
                        null, null, null, Status.UNMAPPED, schemaReady ? null : Reason.SCHEMA_UNMAPPED, candidate));
            }
            for (SourceTableMapping mapping : tableMappings) {
                if (sourceTables.containsKey(mapping.getSourceTableId())) continue;
                SourceSchemaMapping schemaMapping = schemaMappingBySource.get(mapping.getSourceSchemaId());
                SourceCatalogReader.Schema schema = sourceSchemas.get(mapping.getSourceSchemaId());
                rows.add(tableRow(mapping, null, schema != null ? schema.schemaName() : schemaMapping != null ? schemaMapping.getSourceSchemaName() : null));
            }
            return rows;
        }

        private SourceMappingDto.TableRow tableRow(SourceTableMapping mapping, SourceCatalogReader.Table source, String schemaName) {
            ModelTable model = mapping.getTableId() == null ? null : modelTables.get(mapping.getTableId());
            SchemaInfo modelSchema = model == null ? null : modelSchemaById.get(model.schemaId());
            Status status;
            Reason reason = null;
            if (model == null) {
                status = Status.MODEL_MISSING;
                reason = Reason.MODEL_DELETED;
            } else if (source == null) {
                status = Status.SOURCE_MISSING;
                reason = Reason.REMOVED;
            } else if (source.creationCycle() != mapping.getCreationCycle()) {
                status = Status.RECHECK;
                reason = Reason.RECREATED;
            } else if (!"TABLE".equals(source.type())) {
                status = Status.CONFLICT;
                reason = Reason.NOT_TABLE;
            } else {
                SourceSchemaMapping schemaMapping = schemaMappingBySource.get(source.schemaId());
                if (schemaMapping == null || schemaStatus(schemaMapping) != Status.MAPPED) {
                    status = Status.CONFLICT;
                    reason = Reason.SCHEMA_UNMAPPED;
                } else if (!model.schemaId().equals(schemaMapping.getSchemaId())) {
                    status = Status.CONFLICT;
                    reason = Reason.SCHEMA_MISMATCH;
                } else {
                    status = Status.MAPPED;
                }
            }
            return new SourceMappingDto.TableRow(mapping.getSourceTableId(), mapping.getSourceSchemaId(), schemaName, source != null ? source.name() : mapping.getCompareName(),
                    source != null ? source.comment() : null, mapping.getId(), model == null ? null : model.id(), model == null ? null : model.name(),
                    model == null ? null : model.korName(), model == null ? null : model.schemaId(), modelSchema == null ? null : modelSchema.getSchemaName(),
                    mapping.getReflectedOrder(), status, reason, null);
        }

        List<SourceMappingDto.ModelSchema> modelSchemaDtos() {
            return modelSchemas.stream().map(s -> new SourceMappingDto.ModelSchema(s.getId(), s.getSchemaName(), Boolean.TRUE.equals(s.getIsDefault()),
                    schemaMappingByModel.containsKey(s.getId()) ? schemaMappingByModel.get(s.getId()).getSourceSchemaId() : null)).toList();
        }
    }
}
