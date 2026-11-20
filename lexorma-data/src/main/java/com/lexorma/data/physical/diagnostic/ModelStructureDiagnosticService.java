package com.lexorma.data.physical.diagnostic;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticQuery.*;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.table.service.TableNameResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 물리 모델 구조 진단 (DEC-09·DEC-12). DDL 생성({@link TableDdlGenerator})이 FK 를 만들지 않는 조건을 그대로 진단으로 보인다.
 * <ul>
 *   <li>무효 FK (오류) : 참조 테이블명(REF_COLUMN)은 DDL 과 같은 해석({@link TableDesignService#referencedKeys} 과 같은 {@link TableNameResolver} 규칙), 사용자 정의 관계는
 *       {@link TableDdlGenerator#referencesKey}. 사후에 무효가 된 FK 도 모델 수정을 막지 않는다 — 진단·DDL 경고로만 알린다. 자기 자신 PK 참조는 DDL 이 건너뛰지만 오류는 아니다</li>
 *   <li>중복 인덱스 (경고) : 같은 정의(키·정렬·INCLUDE·조건), PK 와 같은 순서의 일반 인덱스(DEC-12 허용), 다른 인덱스·PK 의 앞부분과 같은 일반 인덱스.
 *       조건·INCLUDE·표현식이 있는 인덱스는 앞부분 비교에서 뺀다 (용도가 다를 수 있다)</li>
 * </ul>
 * 조회 (M-DIAG-PERF, docs/completed/physical-diagnostics-performance.md) : 진단에 쓰는 값을 프로젝트 단위로 종류마다 한 번 읽고({@link ModelStructureDiagnosticQuery}, 엔티티 적재 없음)
 * 요청 안에서만 쓰는 맵으로 판단한다 — 예전에는 테이블마다 참조 키·관계·제약조건·인덱스를 따로 읽었다. 규칙·결과·순서는 그대로다 (테이블 : 스키마명 → 테이블명,
 * 테이블 안 : 참조 테이블명 컬럼(컬럼 ID 순) → 사용자 정의 관계(관계 ID 순) → 인덱스(인덱스 ID 순)).
 */
@Service
@RequiredArgsConstructor
public class ModelStructureDiagnosticService {

    private final ModelStructureDiagnosticQuery query;

    @Transactional(readOnly = true)
    public ModelStructureDiagnosticDto.Result diagnose(AccountDto account, Long schemaId) {
        Snapshot model = Snapshot.load(query, account.getProjectId());
        List<TableRow> scope = model.tables.stream()
                .filter(t -> schemaId == null || t.schemaId().equals(schemaId))
                .sorted(Comparator.comparing(TableRow::schemaName).thenComparing(TableRow::tableName))
                .toList();
        List<ModelStructureDiagnosticDto.Issue> issues = new ArrayList<>();
        for (TableRow table : scope) {
            foreignKeys(model, table, issues);
            indexes(model, table, issues);
        }
        long errors = issues.stream().filter(i -> i.severity() == ModelStructureDiagnosticDto.Severity.ERROR).count();
        return new ModelStructureDiagnosticDto.Result(new ModelStructureDiagnosticDto.Summary(scope.size(), errors, issues.size() - errors), issues);
    }

    /**
     * 진단 한 번에 쓰는 프로젝트 값 (요청 안에서만, 전역에 두지 않는다). 테이블·컬럼·관계·인덱스를 ID 로 찾는 맵.
     * tablesByName : 테이블명(저장된 그대로) → 테이블 — 예전의 {@code findAllByProjectIdAndTableNameIn}(대문자 이름과 정확히 같은 이름) 과 같은 후보
     */
    private record Snapshot(List<TableRow> tables, Map<Long, TableRow> tableById, Map<String, List<TableRow>> tablesByName,
                            Map<Long, List<PkColumnRow>> pkByTable, Map<Long, List<RefColumnRow>> refsByTable, Map<Long, Long> derivedTargets,
                            Map<Long, List<Relation>> relationsBySource, Map<Long, List<Set<String>>> uniqueKeysByTable,
                            Map<Long, List<Index>> indexesByTable) {

        static Snapshot load(ModelStructureDiagnosticQuery query, Long projectId) {
            List<TableRow> tables = query.tables(projectId);
            Map<Long, TableRow> tableById = new HashMap<>();
            Map<String, List<TableRow>> byName = new HashMap<>();
            tables.forEach(t -> {
                tableById.put(t.id(), t);
                byName.computeIfAbsent(t.tableName(), k -> new ArrayList<>()).add(t);
            });
            Map<Long, List<PkColumnRow>> pk = group(query.pkColumns(projectId), PkColumnRow::tableId);
            Map<Long, List<RefColumnRow>> refs = group(query.refColumns(projectId), RefColumnRow::tableId);
            Map<Long, Long> derived = new HashMap<>();
            query.derivedTargets(projectId).forEach(r -> derived.put(r.derivedColumnId(), r.targetTableId()));

            Map<Long, Relation> relations = new LinkedHashMap<>();
            for (RelationPairRow row : query.foreignKeyRelations(projectId)) {
                Relation relation = relations.computeIfAbsent(row.relationId(), id -> new Relation(id, row.relationName(), row.sourceTableId(), row.targetTableId(), new ArrayList<>()));
                if (row.columnOrder() != null) relation.targets().add(new Target(row.targetColumnName(), row.targetColumnUnique()));
            }

            // FK 가 참조할 수 있는 UNIQUE 제약·고유 인덱스의 컬럼명 집합 (DDL 의 uniqueKeys 와 같은 규칙)
            Map<Long, List<Set<String>>> uniqueKeys = new HashMap<>();
            Map<Long, Set<String>> constraints = new LinkedHashMap<>();
            Map<Long, Long> constraintTable = new HashMap<>();
            for (UniqueMemberRow row : query.uniqueConstraintMembers(projectId)) {
                Set<String> names = constraints.computeIfAbsent(row.constraintId(), k -> new HashSet<>());
                constraintTable.put(row.constraintId(), row.tableId());
                if (row.columnName() != null) names.add(row.columnName());
            }
            constraints.forEach((id, names) -> uniqueKeys.computeIfAbsent(constraintTable.get(id), k -> new ArrayList<>()).add(names));

            Map<Long, List<IndexMemberRow>> members = group(query.indexMembers(projectId), IndexMemberRow::indexId);
            Map<Long, List<Index>> indexes = new HashMap<>();
            for (IndexRow row : query.indexes(projectId)) {
                Index index = new Index(row, members.getOrDefault(row.id(), List.of()));
                indexes.computeIfAbsent(row.tableId(), k -> new ArrayList<>()).add(index);
                List<IndexMemberRow> keys = index.keyMembers();
                if (TableDdlGenerator.referenceableUniqueIndex(row.isUnique(), row.condition(), keys.stream().allMatch(m -> m.columnId() != null))) {
                    uniqueKeys.computeIfAbsent(row.tableId(), k -> new ArrayList<>()).add(keys.stream().map(IndexMemberRow::columnName).collect(Collectors.toSet()));
                }
            }
            return new Snapshot(tables, tableById, byName, pk, refs, derived,
                    relations.values().stream().collect(Collectors.groupingBy(Relation::sourceTableId, HashMap::new, Collectors.toList())),
                    uniqueKeys, indexes);
        }

        List<PkColumnRow> pk(Long tableId) {
            return pkByTable.getOrDefault(tableId, List.of());
        }
    }

    private record Target(String columnName, Boolean isUnique) {
    }

    private record Relation(Long id, String name, Long sourceTableId, Long targetTableId, List<Target> targets) {
    }

    /** 인덱스와 구성 요소 (columnOrder 순). 키 = INCLUDE 가 아닌 것, INCLUDE = 컬럼만 */
    private record Index(IndexRow row, List<IndexMemberRow> members) {
        List<IndexMemberRow> keyMembers() {
            return members.stream().filter(m -> !"INCLUDE".equals(m.sort())).toList();
        }

        List<IndexMemberRow> includeMembers() {
            return members.stream().filter(m -> "INCLUDE".equals(m.sort())).toList();
        }
    }

    private static <T> Map<Long, List<T>> group(List<T> rows, java.util.function.Function<T, Long> key) {
        Map<Long, List<T>> map = new HashMap<>();
        rows.forEach(r -> map.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r));
        return map;
    }

    private static final TableNameResolver.View NO_VIEW = null;

    private static TableNameResolver.View view(TableRow t) {
        return new TableNameResolver.View(t.id(), true, t.schemaId(), t.schemaName(), t.tableName());
    }

    private static void foreignKeys(Snapshot model, TableRow table, List<ModelStructureDiagnosticDto.Issue> issues) {
        List<RefColumnRow> columns = model.refsByTable().getOrDefault(table.id(), List.of()).stream()
                .filter(c -> !c.refTableName().isBlank()).toList();
        if (!columns.isEmpty()) {
            // 참조 테이블명(대문자) → 참조 테이블 PK 수, 모호한 이름 : TableDesignService.referencedKeys 와 같다 (같은 이름의 첫 확정 컬럼이 정한다)
            Map<String, Integer> pkSize = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            for (RefColumnRow column : columns) {
                String key = column.refTableName().trim().toUpperCase();
                List<TableRow> candidates = model.tablesByName().getOrDefault(TableNameResolver.tableNameOf(column.refTableName()), List.of());
                TableNameResolver.Pick<TableRow> target = TableNameResolver.pick(column.refTableName(), true, table.schemaId(), candidates,
                        ModelStructureDiagnosticService::view, model.derivedTargets().get(column.id()));
                if (target.status() == TableNameResolver.Status.AMBIGUOUS) ambiguous.add(key);
                if (target.status() != TableNameResolver.Status.FOUND) continue;
                pkSize.putIfAbsent(key, model.pk(target.table().id()).size());
            }
            for (RefColumnRow column : columns) {
                String ref = column.refTableName().trim().toUpperCase(Locale.ROOT);
                Integer pk = pkSize.get(ref);
                ModelStructureDiagnosticDto.IssueType type;
                if (pk == null) type = ambiguous.contains(ref) ? ModelStructureDiagnosticDto.IssueType.FK_TARGET_AMBIGUOUS : ModelStructureDiagnosticDto.IssueType.FK_TARGET_NOT_FOUND;
                else if (pk == 0) type = ModelStructureDiagnosticDto.IssueType.FK_TARGET_NO_PK;
                else if (pk > 1) type = ModelStructureDiagnosticDto.IssueType.FK_TARGET_COMPOSITE_PK;
                else continue;
                issues.add(issue(type, ModelStructureDiagnosticDto.Severity.ERROR, table, "C" + column.id(), column.columnName(), column.refTableName().trim(), column.isApproval()));
            }
        }
        for (Relation relation : model.relationsBySource().getOrDefault(table.id(), List.of())) {
            TableRow target = model.tableById().get(relation.targetTableId());
            Set<String> referenced = relation.targets().stream().map(Target::columnName).collect(Collectors.toSet());
            Set<String> pk = model.pk(relation.targetTableId()).stream().map(PkColumnRow::columnName).collect(Collectors.toSet());
            boolean singleUnique = relation.targets().size() == 1 && Boolean.TRUE.equals(relation.targets().get(0).isUnique());
            if (TableDdlGenerator.referencesKey(referenced, pk, singleUnique, model.uniqueKeysByTable().getOrDefault(relation.targetTableId(), List.of()))) continue;
            issues.add(issue(ModelStructureDiagnosticDto.IssueType.FK_RELATION_NOT_KEY, ModelStructureDiagnosticDto.Severity.ERROR, table, "R" + relation.id(),
                    relation.name(), target.tableName() + " (" + relation.targets().stream().map(Target::columnName).collect(Collectors.joining(", ")) + ")", null));
        }
    }

    private static void indexes(Snapshot model, TableRow table, List<ModelStructureDiagnosticDto.Issue> issues) {
        List<Index> sorted = model.indexesByTable().getOrDefault(table.id(), List.of()).stream().sorted(Comparator.comparing((Index i) -> i.row().id())).toList();
        List<Long> pk = model.pk(table.id()).stream()
                .sorted(Comparator.comparing(PkColumnRow::sortOrder, Comparator.nullsLast(Comparator.naturalOrder()))).map(PkColumnRow::id).toList();
        Map<Long, String> signatures = new HashMap<>();
        Map<Long, List<Long>> plain = new HashMap<>();
        sorted.forEach(i -> {
            signatures.put(i.row().id(), signature(i));
            plain.put(i.row().id(), plainKeys(i));
        });
        Set<Long> reported = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            Index index = sorted.get(i);
            Long id = index.row().id();
            // 같은 정의 : 뒤에 등록된 쪽에 알린다
            for (int j = 0; j < i; j++) {
                if (signatures.get(sorted.get(j).row().id()).equals(signatures.get(id)) && reported.add(id)) {
                    issues.add(indexIssue(ModelStructureDiagnosticDto.IssueType.INDEX_SAME_DEFINITION, table, index, sorted.get(j).row().indexName()));
                }
            }
            if (reported.contains(id) || Boolean.TRUE.equals(index.row().isUnique()) || plain.get(id) == null) continue;
            List<Long> keys = plain.get(id);
            if (!pk.isEmpty() && keys.equals(pk)) {
                issues.add(indexIssue(ModelStructureDiagnosticDto.IssueType.INDEX_SAME_AS_PK, table, index, "PK"));
                continue;
            }
            String longer = isPrefix(keys, pk) ? "PK" : sorted.stream().filter(o -> o != index).filter(o -> isPrefix(keys, plain.get(o.row().id()))).map(o -> o.row().indexName()).findFirst().orElse(null);
            if (longer != null) issues.add(indexIssue(ModelStructureDiagnosticDto.IssueType.INDEX_PREFIX_OF_OTHER, table, index, longer));
        }
    }

    /** 앞부분 비교에 쓰는 키 (컬럼 ID 순서) : 오름차순 컬럼 키만이고 조건·INCLUDE 가 없을 때만, 아니면 null */
    private static List<Long> plainKeys(Index index) {
        if (index.row().condition() != null || !index.includeMembers().isEmpty()) return null;
        List<IndexMemberRow> keys = index.keyMembers();
        if (keys.stream().anyMatch(c -> c.columnId() == null || (c.sort() != null && !c.sort().equalsIgnoreCase("ASC")))) return null;
        return keys.stream().map(IndexMemberRow::columnId).toList();
    }

    private static boolean isPrefix(List<Long> shorter, List<Long> longer) {
        return longer != null && shorter.size() < longer.size() && longer.subList(0, shorter.size()).equals(shorter);
    }

    /** 등록 검사({@code PhysicalModelServiceImpl.signature})와 같은 기준 : 키(컬럼 ID 또는 표현식, 정렬) 순서·INCLUDE·조건 */
    private static String signature(Index index) {
        return index.keyMembers().stream().map(c -> (c.columnId() != null ? "C" + c.columnId() : "E" + c.expression()) + "/" + c.sort())
                .collect(Collectors.joining(",")) + "|" + index.includeMembers().stream().map(IndexMemberRow::columnId).toList() + "|" + index.row().condition();
    }

    private static ModelStructureDiagnosticDto.Issue indexIssue(ModelStructureDiagnosticDto.IssueType type, TableRow table, Index index, String related) {
        return issue(type, ModelStructureDiagnosticDto.Severity.WARNING, table, "I" + index.row().id(), index.row().indexName(), related, index.row().isApproval());
    }

    private static ModelStructureDiagnosticDto.Issue issue(ModelStructureDiagnosticDto.IssueType type, ModelStructureDiagnosticDto.Severity severity, TableRow table,
                                                           String key, String objectName, String related, Boolean isApproval) {
        return new ModelStructureDiagnosticDto.Issue(type + ":" + key, type, severity, table.schemaId(), table.schemaName(),
                table.id(), table.tableName(), objectName, related, isApproval);
    }
}
