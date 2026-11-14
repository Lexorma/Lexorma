package com.lexorma.data.sourcemapping.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.core.source.SourceCatalogReader.NameKind;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.sourcemapping.compare.CompareModel;
import com.lexorma.data.sourcemapping.compare.SourceModelComparator;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.TableReason;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.domain.entity.SourceSchemaMapping;
import com.lexorma.data.sourcemapping.repository.SourceCompareModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 원본 ↔ 물리 모델 비교 (M-4d, docs/source-compare-m4d.md). <b>읽기 전용</b> : 원본 DB 에 접속하지 않고 저장된 마지막 반영 수집만 읽으며, 물리 모델·매핑을 바꾸지 않고
 * 결과를 저장하지 않는다 (요청마다 계산).
 * <ul>
 *   <li>기준 : M-4c 의 확정 매핑(매핑 화면과 같은 상태 계산). 미매핑 TABLE 은 이어 보지 않고, 재확인 필요·충돌·원본 없음·모델 없음을 그대로 반영한다</li>
 *   <li>시점 : 기준 연결 + 마지막 반영 수집의 반영 순서 R 하나. 한 요청은 REPEATABLE READ 한 트랜잭션이라 매핑·모델·원본을 같은 시점에서 읽고,
 *       원본 하위 객체는 R 로 고정해 읽는다. 요청 사이에 기준·R 이 바뀌면 호출자가 보낸 connectionId·reflectedOrder 와 달라 SOURCE_COMPARE_STALE(409)</li>
 *   <li>권한 : VIEWER 이상, 로그인한 프로젝트만 (매핑 조회와 같다). 연결 행 잠금(lockConnection)은 잡지 않는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SourceCompareService {

    private static final int SIZE_MAX = 500;

    private final SourceMappingService mappingService;
    private final SourceCatalogReader reader;
    private final SourceCompareModelRepository modelRepository;

    public SourceCompareDto.Summary summary(AccountDto accountDto) {
        Comparison comparison = compare(accountDto.getProjectId(), null, null, null);
        if (comparison.state == null || comparison.state.connection == null) {
            return new SourceCompareDto.Summary(null, null, null, null, false, "NO_BASE", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of());
        }
        SourceCatalogReader.Snapshot snapshot = comparison.state.snapshot;
        List<SourceCompareDto.TableRow> rows = comparison.rows;
        Map<CompareStatus, Long> counts = counts(rows);
        Map<TableReason, Long> reasons = new EnumMap<>(TableReason.class);
        rows.forEach(r -> reasons.merge(r.reason(), 1L, Long::sum));
        int compared = (int) rows.stream().filter(r -> r.reason() == TableReason.COMPARED).count();
        int same = (int) rows.stream().filter(r -> r.reason() == TableReason.COMPARED && r.status() == CompareStatus.SAME).count();
        int different = (int) rows.stream().filter(r -> r.reason() == TableReason.COMPARED && r.status() == CompareStatus.MODIFIED).count();
        int unknown = (int) rows.stream().filter(r -> r.reason() == TableReason.COMPARED && r.status() == CompareStatus.UNKNOWN).count();
        int unmapped = (int) rows.stream().filter(r -> r.sourceTableId() != null && r.mappingStatus() == Status.UNMAPPED).count();
        int modelOnly = (int) rows.stream().filter(r -> r.reason() == TableReason.MODEL_ONLY).count();
        int conflicts = (int) rows.stream().filter(r -> r.status() == CompareStatus.CONFLICT).count();
        int recheck = (int) rows.stream().filter(r -> r.mappingStatus() == Status.RECHECK).count();
        int mapped = (int) rows.stream().filter(r -> r.mappingId() != null).count();
        int sourceTables = (int) snapshot.tables().stream().filter(t -> "TABLE".equals(t.type())).count();
        int views = (int) snapshot.tables().stream().filter(t -> !"TABLE".equals(t.type())).count();
        return new SourceCompareDto.Summary(SourceMappingService.connectionDto(comparison.state.connection), snapshot.reflectedOrder(), snapshot.executionId(),
                snapshot.reflectedAt(), comparison.notComparable == null, comparison.notComparable, sourceTables, views, mapped, compared, same, different, unknown, unmapped,
                modelOnly, conflicts, recheck, comparison.excluded, counts, reasons);
    }

    /** TABLE 비교 목록 : 원본 TABLE(VIEW 제외, 매핑된 VIEW 는 충돌로) + 모델에만 있는 TABLE. 상태·원본 스키마·이름으로 거르고 쪽으로 나눈다 */
    public SourceCompareDto.TablePage tables(AccountDto accountDto, Long connectionId, Integer reflectedOrder, Long sourceSchemaId, String status, String keyword,
                                             int page, int size) {
        Comparison comparison = compare(accountDto.getProjectId(), connectionId, reflectedOrder, null);
        if (!comparison.ready()) return new SourceCompareDto.TablePage(comparison.connectionId(), comparison.reflectedOrder(), List.of(), 0, Map.of());
        String word = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        CompareStatus wanted = parse(CompareStatus.class, status);
        List<SourceCompareDto.TableRow> scoped = comparison.rows.stream()
                .filter(r -> sourceSchemaId == null || sourceSchemaId.equals(r.sourceSchemaId()))
                .filter(r -> word.isEmpty() || contains(r.sourceTableName(), word) || contains(r.tableName(), word) || contains(r.tableKorName(), word))
                .toList();
        List<SourceCompareDto.TableRow> filtered = scoped.stream().filter(r -> wanted == null || r.status() == wanted).toList();
        return new SourceCompareDto.TablePage(comparison.connectionId(), comparison.reflectedOrder(), page(filtered, page, size), filtered.size(), counts(scoped));
    }

    /**
     * TABLE 하나의 상세 비교. key = "S" + 원본 TABLE ID 또는 "M" + 모델 TABLE ID (목록의 key). kind 를 주면 그 종류의 객체만
     * (COLUMN, PRIMARY_KEY, UNIQUE, CHECK, FOREIGN_KEY, INDEX 를 쉼표로).
     */
    public SourceCompareDto.TableDetail table(AccountDto accountDto, Long connectionId, Integer reflectedOrder, String key, String kind) {
        Long sourceTableId = key != null && key.startsWith("S") ? number(key.substring(1)) : null;
        Long modelTableId = key != null && key.startsWith("M") ? number(key.substring(1)) : null;
        if (sourceTableId == null && modelTableId == null) throw invalid("비교할 TABLE 을 골라주세요.");
        Comparison comparison = compare(accountDto.getProjectId(), connectionId, reflectedOrder, sourceTableId == null ? Set.of() : Set.of(sourceTableId));
        if (!comparison.ready()) throw invalid("비교할 수 있는 기준 연결·수집이 없습니다.");
        SourceCompareDto.TableRow row = comparison.rows.stream().filter(r -> key.equals(r.key())).findFirst()
                .orElseThrow(() -> invalid("비교 대상에 없는 TABLE 입니다."));
        SourceModelComparator.Result result = sourceTableId == null ? null : comparison.results.get(sourceTableId);
        if (result == null) return new SourceCompareDto.TableDetail(comparison.connectionId(), comparison.reflectedOrder(), row, List.of(), List.of(), comparison.warnings(row));
        Set<ObjectKind> kinds = kinds(kind);
        List<SourceCompareDto.ObjectDiff> objects = result.objects().stream().filter(o -> kinds.isEmpty() || kinds.contains(o.kind())).toList();
        List<SourceCompareDto.RenameCandidate> renames = result.renames().stream().filter(r -> kinds.isEmpty() || kinds.contains(r.kind())).map(r -> rename(row, r)).toList();
        List<SourceCompareDto.Warning> warnings = new ArrayList<>(comparison.warnings(row));
        warnings.addAll(result.warnings());
        return new SourceCompareDto.TableDetail(comparison.connectionId(), comparison.reflectedOrder(), row, objects, renames, List.copyOf(warnings));
    }

    /** 이름 변경 후보 (비교한 모든 TABLE). 제안일 뿐이다 */
    public SourceCompareDto.RenamePage renames(AccountDto accountDto, Long connectionId, Integer reflectedOrder, String kind, String keyword, int page, int size) {
        Comparison comparison = compare(accountDto.getProjectId(), connectionId, reflectedOrder, null);
        if (!comparison.ready()) return new SourceCompareDto.RenamePage(comparison.connectionId(), comparison.reflectedOrder(), List.of(), 0);
        Set<ObjectKind> kinds = kinds(kind);
        String word = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<SourceCompareDto.RenameCandidate> all = new ArrayList<>();
        for (SourceCompareDto.TableRow row : comparison.rows) {
            SourceModelComparator.Result result = row.sourceTableId() == null ? null : comparison.results.get(row.sourceTableId());
            if (result == null) continue;
            result.renames().stream().filter(r -> kinds.isEmpty() || kinds.contains(r.kind())).map(r -> rename(row, r))
                    .filter(r -> word.isEmpty() || contains(r.sourceTableName(), word) || contains(r.tableName(), word) || contains(r.sourceName(), word)
                            || contains(r.modelName(), word))
                    .forEach(all::add);
        }
        return new SourceCompareDto.RenamePage(comparison.connectionId(), comparison.reflectedOrder(), page(all, page, size), all.size());
    }

    // ------------------------------------------------------------------ 계산

    /** 한 요청의 비교 결과 */
    private final class Comparison {
        SourceMappingService.State state;
        String notComparable;
        List<SourceCompareDto.TableRow> rows = List.of();
        Map<Long, SourceModelComparator.Result> results = new HashMap<>();
        /** 매핑 예외 원본 TABLE 수 (비교 목록에는 없다) */
        int excluded;

        boolean ready() {
            return state != null && state.connection != null && state.snapshot.reflectedOrder() != null;
        }

        Long connectionId() {
            return state == null || state.connection == null ? null : state.connection.id();
        }

        Integer reflectedOrder() {
            return state == null || state.snapshot == null ? null : state.snapshot.reflectedOrder();
        }

        List<SourceCompareDto.Warning> warnings(SourceCompareDto.TableRow row) {
            if (row.reason() == TableReason.UNSUPPORTED_DBMS) {
                return List.of(new SourceCompareDto.Warning(SourceCompareDto.WarningCode.UNSUPPORTED_DBMS, List.of(state.connection.dbType())));
            }
            return List.of();
        }
    }

    /**
     * @param detailOf 하위 객체까지 비교할 원본 TABLE (null 이면 매핑된 모든 TABLE — 목록·요약은 상태를 거르기 위해 모두 계산한다)
     */
    private Comparison compare(Long projectId, Long expectedConnection, Integer expectedOrder, Set<Long> detailOf) {
        Comparison comparison = new Comparison();
        SourceMappingService.State state = mappingService.basis(projectId);
        comparison.state = state;
        if (state == null || state.connection == null) {
            if (expectedConnection != null) throw stale();
            comparison.notComparable = "NO_BASE";
            return comparison;
        }
        SourceCatalogReader.Connection connection = state.connection;
        Integer order = state.snapshot.reflectedOrder();
        if ((expectedConnection != null && !expectedConnection.equals(connection.id())) || (expectedOrder != null && !expectedOrder.equals(order))) throw stale();
        if (order == null) {
            comparison.notComparable = "NO_COLLECTION";
            return comparison;
        }
        boolean supported = "POSTGRESQL".equals(connection.dbType());
        if (!supported) comparison.notComparable = "UNSUPPORTED_DBMS";

        List<SourceMappingDto.TableRow> mappingRows = state.tableRows();
        comparison.excluded = (int) mappingRows.stream().filter(r -> r.status() == Status.EXCLUDED).count();
        Map<Long, Long> mappedModelBySource = new HashMap<>();
        for (SourceMappingDto.TableRow row : mappingRows) {
            if (row.status() == Status.MAPPED) mappedModelBySource.put(row.sourceTableId(), row.tableId());
        }

        // 하위 객체 비교 : 원본은 반영 순서 R 로 고정해 한 번에, 모델은 종류마다 한 번에 읽는다
        Set<Long> compareIds = new HashSet<>();
        if (supported) {
            for (SourceMappingDto.TableRow row : mappingRows) {
                if (row.status() == Status.MAPPED && (detailOf == null || detailOf.contains(row.sourceTableId()))) compareIds.add(row.sourceTableId());
            }
        }
        if (!compareIds.isEmpty()) {
            Map<Long, SourceCatalogReader.TableDetail> details = reader.tableDetails(projectId, connection.id(), order, compareIds);
            Set<Long> modelIds = new HashSet<>();
            compareIds.forEach(id -> {
                SourceMappingService.ModelTable table = state.modelTables.get(mappedModelBySource.get(id));
                if (table != null) modelIds.add(table.id());
            });
            SourceCompareModelRepository.Loaded model = modelRepository.load(projectId, modelIds);
            SourceModelComparator.Context context = context(state, mappedModelBySource, model);
            for (Long id : compareIds) {
                SourceCatalogReader.TableDetail detail = details.get(id);
                CompareModel.Table modelTable = model.tables().get(mappedModelBySource.get(id));
                SourceCatalogReader.Table sourceTable = state.sourceTables.get(id);
                if (detail != null && modelTable != null) {
                    comparison.results.put(id, SourceModelComparator.compare(detail, sourceTable == null ? null : sourceTable.name(), sourceTable == null ? null : sourceTable.comment(),
                            modelTable, context));
                }
            }
        }
        comparison.rows = rows(projectId, state, mappingRows, comparison.results, supported);
        return comparison;
    }

    private SourceModelComparator.Context context(SourceMappingService.State state, Map<Long, Long> mappedModelBySource, SourceCompareModelRepository.Loaded model) {
        SourceCatalogReader.Connection connection = state.connection;
        // 원본 FK 의 참조 대상 (원본이 준 스키마·TABLE 이름 그대로) → 원본 TABLE → 유효한 매핑의 모델 TABLE
        Map<String, Long> sourceByName = new HashMap<>();
        for (SourceCatalogReader.Table table : state.snapshot.tables()) {
            SourceCatalogReader.Schema schema = state.sourceSchemas.get(table.schemaId());
            if (schema != null) sourceByName.put(schema.schemaName() + "\u0000" + table.name(), table.id());
        }
        Map<String, String> folded = new HashMap<>();
        return new SourceModelComparator.Context() {
            @Override
            public String fold(String modelName) {
                return modelName == null ? null : folded.computeIfAbsent(modelName, n -> reader.compareName(connection, NameKind.COLUMN, n));
            }

            @Override
            public CompareModel.Column modelColumn(Long id) {
                return model.columns().get(id);
            }

            @Override
            public String modelTableName(Long tableId) {
                SourceMappingService.ModelTable table = tableId == null ? null : state.modelTables.get(tableId);
                if (table == null) return null;
                SchemaInfo schema = state.modelSchemaById.get(table.schemaId());
                return (schema == null ? "?" : schema.getSchemaName()) + "." + table.name();
            }

            @Override
            public SourceModelComparator.Target resolve(SourceCatalogReader.ForeignKey foreignKey) {
                Long sourceTable = sourceByName.get(foreignKey.referencedSchema() + "\u0000" + foreignKey.referencedTable());
                Long modelTable = sourceTable == null ? null : mappedModelBySource.get(sourceTable);
                return new SourceModelComparator.Target(modelTable, modelTable != null);
            }
        };
    }

    /** 원본 TABLE 행(매핑 상태 → 비교 상태) + 모델에만 있는 TABLE 행 */
    private List<SourceCompareDto.TableRow> rows(Long projectId, SourceMappingService.State state, List<SourceMappingDto.TableRow> mappingRows,
                                                 Map<Long, SourceModelComparator.Result> results, boolean supported) {
        List<SourceCompareDto.TableRow> rows = new ArrayList<>();
        Map<Long, List<String>> views = modelRepository.viewNames(projectId);
        Set<Long> represented = new HashSet<>();
        for (SourceMappingDto.TableRow row : mappingRows) {
            // 매핑 예외(M-4g)는 원본·모델 비교 대상이 아니다 (원본 수집·카탈로그 조회는 그대로). 요약에 건수만 보인다
            if (row.status() == Status.EXCLUDED) continue;
            if (row.tableId() != null) represented.add(row.tableId());
            if (row.candidate() != null) represented.add(row.candidate().id());
            CompareStatus status;
            TableReason reason;
            SourceModelComparator.Result result = null;
            switch (row.status()) {
                case MAPPED -> {
                    if (!supported) {
                        status = CompareStatus.UNKNOWN;
                        reason = TableReason.UNSUPPORTED_DBMS;
                    } else {
                        result = results.get(row.sourceTableId());
                        status = result == null ? null : result.status();
                        reason = TableReason.COMPARED;
                    }
                }
                case UNMAPPED -> {
                    SourceSchemaMapping schemaMapping = state.schemaMappingBySource.get(row.sourceSchemaId());
                    boolean schemaReady = schemaMapping != null && state.schemaStatus(schemaMapping) == Status.MAPPED;
                    if (!schemaReady) {
                        status = CompareStatus.UNKNOWN;
                        reason = TableReason.SCHEMA_UNMAPPED;
                    } else if (row.candidate() != null && row.candidate().taken()) {
                        status = CompareStatus.CONFLICT;
                        reason = TableReason.MODEL_NAME_TAKEN;
                    } else if (row.candidate() != null) {
                        status = CompareStatus.UNKNOWN;
                        reason = TableReason.MAPPING_CANDIDATE;
                    } else if (viewNameTaken(state, views.getOrDefault(schemaMapping.getSchemaId(), List.of()), row.sourceTableName())) {
                        status = CompareStatus.CONFLICT;
                        reason = TableReason.VIEW_NAME_TAKEN;
                    } else {
                        status = CompareStatus.ADDED_IN_SOURCE;
                        reason = TableReason.UNMAPPED;
                    }
                }
                case RECHECK -> {
                    status = CompareStatus.UNKNOWN;
                    reason = TableReason.RECHECK;
                }
                case CONFLICT -> {
                    status = CompareStatus.CONFLICT;
                    reason = TableReason.MAPPING_CONFLICT;
                }
                case SOURCE_MISSING -> {
                    status = CompareStatus.ADDED_IN_MODEL;
                    reason = TableReason.SOURCE_REMOVED;
                }
                default -> {
                    status = CompareStatus.ADDED_IN_SOURCE;
                    reason = TableReason.MODEL_DELETED;
                }
            }
            if (status == null && reason == TableReason.COMPARED) {
                // 상세를 계산하지 않은 행 (상세 요청에서 다른 TABLE) : 상태는 목록 요청에서 본다
                status = CompareStatus.UNKNOWN;
            }
            Map<ObjectKind, Integer> byKind = new EnumMap<>(ObjectKind.class);
            if (result != null) {
                result.objects().stream().filter(o -> o.status() != CompareStatus.SAME && o.status() != CompareStatus.UNKNOWN)
                        .forEach(o -> byKind.merge(o.kind(), 1, Integer::sum));
            }
            rows.add(new SourceCompareDto.TableRow("S" + row.sourceTableId(), row.sourceTableId(), row.sourceSchemaId(), row.sourceSchemaName(), row.sourceTableName(),
                    row.sourceComment(), row.mappingId(), row.status(), row.reason(), row.tableId(), row.schemaName(), row.tableName(), row.tableKorName(), status, reason,
                    result == null ? 0 : result.differences(), result == null ? 0 : result.unknowns(), byKind));
        }
        // 모델에만 있는 TABLE : 유효하게 매핑된 모델 스키마 안에서 어떤 원본 행에도 나오지 않는 모델 TABLE (매핑되지 않은 모델 스키마는 판단하지 않는다)
        Set<Long> mappedSchemas = state.schemaMappings.stream().filter(m -> state.schemaStatus(m) == Status.MAPPED).map(SourceSchemaMapping::getSchemaId)
                .collect(Collectors.toSet());
        state.modelTables.values().stream()
                .filter(t -> mappedSchemas.contains(t.schemaId()) && !represented.contains(t.id()))
                .sorted(Comparator.comparing((SourceMappingService.ModelTable t) -> schemaName(state, t.schemaId())).thenComparing(SourceMappingService.ModelTable::name))
                .forEach(t -> rows.add(new SourceCompareDto.TableRow("M" + t.id(), null, sourceSchemaOf(state, t.schemaId()), null, null, null, null, null, null, t.id(),
                        schemaName(state, t.schemaId()), t.name(), t.korName(), CompareStatus.ADDED_IN_MODEL, TableReason.MODEL_ONLY, 0, 0, Map.of())));
        return rows;
    }

    private boolean viewNameTaken(SourceMappingService.State state, List<String> viewNames, String sourceTableName) {
        if (viewNames.isEmpty() || sourceTableName == null) return false;
        String compare = reader.compareName(state.connection, NameKind.TABLE, sourceTableName);
        return viewNames.stream().anyMatch(v -> Objects.equals(reader.compareName(state.connection, NameKind.TABLE, v), compare) || v.equals(sourceTableName));
    }

    private static String schemaName(SourceMappingService.State state, Long schemaId) {
        SchemaInfo schema = state.modelSchemaById.get(schemaId);
        return schema == null ? null : schema.getSchemaName();
    }

    /** 모델 스키마에 매핑된 원본 스키마 (모델에만 있는 TABLE 을 원본 스키마로 거를 수 있게) */
    private static Long sourceSchemaOf(SourceMappingService.State state, Long schemaId) {
        SourceSchemaMapping mapping = state.schemaMappingByModel.get(schemaId);
        return mapping == null ? null : mapping.getSourceSchemaId();
    }

    private static SourceCompareDto.RenameCandidate rename(SourceCompareDto.TableRow row, SourceModelComparator.Rename rename) {
        return new SourceCompareDto.RenameCandidate(row.key(), row.sourceTableId(), row.sourceSchemaName(), row.sourceTableName(), row.tableId(), row.tableName(), rename.kind(),
                rename.sourceName(), rename.modelName(), rename.basis(), rename.signature(), rename.ambiguous());
    }

    private static Map<CompareStatus, Long> counts(List<SourceCompareDto.TableRow> rows) {
        Map<CompareStatus, Long> counts = new EnumMap<>(CompareStatus.class);
        rows.forEach(r -> counts.merge(r.status(), 1L, Long::sum));
        return counts;
    }

    private static <T> List<T> page(List<T> all, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), SIZE_MAX);
        int from = Math.min(Math.max(page, 0) * pageSize, all.size());
        return all.subList(from, Math.min(from + pageSize, all.size()));
    }

    private static Set<ObjectKind> kinds(String kind) {
        Set<ObjectKind> kinds = new HashSet<>();
        if (kind == null || kind.isBlank()) return kinds;
        for (String part : kind.split(",")) {
            ObjectKind parsed = parse(ObjectKind.class, part);
            if (parsed != null) kinds.add(parsed);
        }
        return kinds;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("알 수 없는 값입니다 : {0}", value);
        }
    }

    private static Long number(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean contains(String value, String word) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(word);
    }

    private static CustomException stale() {
        return new CustomException(ErrorCode.SOURCE_COMPARE_STALE, "기준 연결이 바뀌었거나 새 수집이 반영되었습니다. 비교 화면을 새로 고쳐주세요.");
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.SOURCE_MAPPING_INVALID, message, args);
    }
}
