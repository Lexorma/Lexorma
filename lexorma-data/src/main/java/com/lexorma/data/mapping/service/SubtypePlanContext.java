package com.lexorma.data.mapping.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.mapping.domain.entity.SubtypeIdentifierColumnMapping;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;

import java.util.*;
import java.util.function.Function;

/**
 * 슈퍼타입·서브타입 물리 구조 판정 (M-7 2차 계획, 3차 상속 PK 매핑 — docs/ongoing/subtype-physical-transformation.md 29·30장).
 * 요청 안에서만 쓰고 저장하지 않는다. 계층은 M-6 의 {@link LogicalHierarchy}(ID 투영), 엔터티·매핑·관계는 {@link #prepare} 가 한꺼번에 읽는다.
 * 물리 생성(기존 조상 재사용)과 상속 PK 매핑 조회(정합성 사유)가 같은 판정을 쓴다 (M7-D36 — 규칙을 서비스마다 복제하지 않는다).
 * <ul>
 *   <li>상속 PK = 루트 주 식별자 속성, 식별자 순서 그대로 (M7-D03). 논리 속성을 복제하지 않고 원본 속성 ID 로 가리킨다</li>
 *   <li>조상 검증 (M7-D16·D17·D18·D35) : 서브타입의 모든 조상은 이번 계획에서 새로 만들거나 유효한 물리 매핑이 있어야 한다.
 *       매핑된 루트 = 루트 주 식별자 속성마다 그 테이블의 직접 매핑 컬럼이 있고 그 컬럼 집합이 테이블 PK 전체와 같다.
 *       매핑된 서브타입(중간 엔터티) = 루트 주 식별자 속성마다 그 테이블의 상속 PK 매핑 컬럼이 있고(전체 — 일부만은 미완성), 그 컬럼 집합이 테이블 PK 전체와 같고,
 *       직계 부모의 키 컬럼과 순서대로 타입이 FK 로 묶일 수 있고({@link TableDdlGenerator#isReferenceTypeCompatible}), 직계 부모 테이블을 그 쌍 그대로 참조하는 FK 가 있고,
 *       직계 부모도 유효하다. 컬럼명·FK 이름으로 추론하지 않는다</li>
 * </ul>
 */
final class SubtypePlanContext {

    static final String STRATEGY = "TABLE_PER_TYPE";
    static final String ROOT_KEY_MISSING = "ROOT_KEY_MISSING";
    static final String ANCESTOR_MISSING = "ANCESTOR_MISSING";
    static final String ROOT_KEY_MAPPING_MISSING = "ROOT_KEY_MAPPING_MISSING";
    static final String PARENT_KEY_MISMATCH = "PARENT_KEY_MISMATCH";
    static final String INHERITED_KEY_MAPPING_MISSING = "INHERITED_KEY_MAPPING_MISSING";
    static final String INHERITED_KEY_TYPE_MISMATCH = "INHERITED_KEY_TYPE_MISMATCH";
    static final String INHERITANCE_FK_MISSING = "INHERITANCE_FK_MISSING";
    /** 직계 부모 쪽 문제 때문에 판정할 수 없다 (그 부모의 사유가 따로 나온다 — 목록에서는 중복으로 보이지 않게 뺀다) */
    static final String ANCESTOR_INVALID = "ANCESTOR_INVALID";
    /** 엔터티가 더 이상 서브타입이 아니다 (상속 PK 매핑 조회에서만) */
    static final String NOT_SUBTYPE = "NOT_SUBTYPE";

    /** 조상 하나의 상태 : 문제가 없으면 code null. table : 쓰는 기존 테이블 (새로 만들거나 없으면 null), keyColumns : 루트 주 식별자 순서의 키 컬럼 */
    record AncestorState(Long entityId, String code, TableInfo table, List<ColumnInfo> keyColumns) {
    }

    /**
     * 요청 안의 일괄 조회 (리포지토리 메서드). relationsBySourceTables 가 있으면 관계를 엔티티로 읽고(물리 생성 — 계획 토큰이 관계 엔티티를 쓴다),
     * 없으면 가벼운 판정(M-7 4차 — 매핑 목록·상세·정합성 비교) : foreignKeyPairs·primaryKeys 투영으로 같은 판정 재료(테이블 PK 컬럼, 나가는 FK 의 컬럼 쌍)만 읽는다
     */
    record Loaders(Function<Collection<Long>, List<LogicalEntity>> entities, Function<Collection<Long>, List<EntityTableMapping>> mappings,
                   Function<Collection<Long>, List<AttributeColumnMapping>> directByAttributes,
                   Function<Collection<Long>, List<SubtypeIdentifierColumnMapping>> inheritedByMappings,
                   Function<Collection<Long>, List<TableRelation>> relationsBySourceTables,
                   Function<Collection<Long>, List<Object[]>> foreignKeyPairs, Function<Collection<Long>, List<Object[]>> primaryKeys) {
        Loaders(Function<Collection<Long>, List<LogicalEntity>> entities, Function<Collection<Long>, List<EntityTableMapping>> mappings,
                Function<Collection<Long>, List<AttributeColumnMapping>> directByAttributes,
                Function<Collection<Long>, List<SubtypeIdentifierColumnMapping>> inheritedByMappings,
                Function<Collection<Long>, List<TableRelation>> relationsBySourceTables) {
            this(entities, mappings, directByAttributes, inheritedByMappings, relationsBySourceTables, null, null);
        }
    }

    /** 테이블 PK 컬럼 1개 (판정 재료 — 컬럼 순서) */
    record KeyCol(Long id, String name, Integer sortOrder) {
    }

    /** 테이블에서 나가는 FK 1개 (판정 재료 — 컬럼 쌍은 관계 컬럼 순서, 짝이 비면 null) */
    record Fk(Long id, String name, String source, Long targetTableId, String targetTableName, List<Long> childIds, List<Long> parentIds,
              List<String> childNames, List<String> parentNames) {
    }

    private final LogicalHierarchy hierarchy;
    private final DbType db;
    private final Loaders loaders;
    private final Map<Long, LogicalEntity> entities;
    private final Map<Long, Optional<EntityTableMapping>> mappings;
    private final Map<Long, AttributeColumnMapping> directMappings = new HashMap<>();
    /** 엔터티 매핑 ID → 원본 속성 ID → 상속 PK 매핑 */
    private final Map<Long, Map<Long, SubtypeIdentifierColumnMapping>> inherited = new HashMap<>();
    private final Map<Long, List<TableRelation>> relationsByTable = new HashMap<>();
    /** 테이블 ID → 나가는 FK / PK 컬럼 (두 방식이 같은 모양으로 채운다 — 판정은 이것만 본다) */
    private final Map<Long, List<Fk>> foreignKeys = new HashMap<>();
    private final Map<Long, List<KeyCol>> primaryKeys = new HashMap<>();
    private final Map<Long, LogicalSubtypeGroup> groups;
    private final Map<Long, AncestorState> states = new HashMap<>();

    SubtypePlanContext(LogicalHierarchy hierarchy, DbType db, Loaders loaders, Map<Long, LogicalEntity> entities,
                       Map<Long, Optional<EntityTableMapping>> mappings, Map<Long, LogicalSubtypeGroup> groups) {
        this.hierarchy = hierarchy;
        this.db = db;
        this.loaders = loaders;
        this.entities = entities;
        this.mappings = mappings;
        this.groups = groups;
    }

    /**
     * 엔터티와 그 조상(루트까지)을 판정할 수 있게 한꺼번에 읽는다 : 엔터티 행, 엔터티 매핑, 매핑된 루트의 주 식별자 직접 매핑, 매핑된 서브타입의 상속 PK 매핑,
     * 매핑된 서브타입 테이블에서 나가는 관계. 이미 읽은 것은 다시 읽지 않는다 (조회 수가 엔터티 수에 비례하지 않는다)
     */
    void prepare(Collection<Long> entityIds) {
        Set<Long> chain = new TreeSet<>();
        for (Long id : entityIds) {
            if (!hierarchy.participates(id)) continue;
            chain.add(id);
            chain.addAll(hierarchy.ancestors(id));
        }
        if (chain.isEmpty()) return;
        List<Long> missing = chain.stream().filter(id -> !entities.containsKey(id)).toList();
        if (!missing.isEmpty()) loaders.entities().apply(missing).forEach(e -> entities.put(e.getId(), e));
        List<Long> unmapped = chain.stream().filter(id -> !mappings.containsKey(id)).toList();
        if (!unmapped.isEmpty()) {
            unmapped.forEach(id -> mappings.put(id, Optional.empty()));
            loaders.mappings().apply(unmapped).forEach(m -> mappings.put(m.getEntity().getId(), Optional.of(m)));
        }
        Set<Long> keyAttributes = new TreeSet<>();
        Set<Long> subtypeMappings = new TreeSet<>();
        Set<Long> subtypeTables = new TreeSet<>();
        Set<Long> keyTables = new TreeSet<>();
        for (Long id : chain) {
            Optional<EntityTableMapping> mapping = mappings.get(id);
            if (mapping.isEmpty() || !entities.containsKey(hierarchy.root(id))) continue;
            Long tableId = mapping.get().getTable().getId();
            if (!primaryKeys.containsKey(tableId)) keyTables.add(tableId);
            if (isSubtype(id)) {
                if (!inherited.containsKey(mapping.get().getId())) subtypeMappings.add(mapping.get().getId());
                if (!foreignKeys.containsKey(tableId)) subtypeTables.add(tableId);
            } else {
                rootKey(id).stream().map(LogicalAttribute::getId).filter(a -> !directMappings.containsKey(a)).forEach(keyAttributes::add);
            }
        }
        if (!keyAttributes.isEmpty()) loaders.directByAttributes().apply(keyAttributes).forEach(m -> directMappings.put(m.getAttribute().getId(), m));
        if (!subtypeMappings.isEmpty()) {
            subtypeMappings.forEach(id -> inherited.put(id, new HashMap<>()));
            loaders.inheritedByMappings().apply(subtypeMappings).forEach(m -> inherited.get(m.getMapping().getId()).put(m.getAttribute().getId(), m));
        }
        if (!subtypeTables.isEmpty()) {
            subtypeTables.forEach(id -> foreignKeys.put(id, new ArrayList<>()));
            if (loaders.relationsBySourceTables() != null) {
                subtypeTables.forEach(id -> relationsByTable.put(id, new ArrayList<>()));
                loaders.relationsBySourceTables().apply(subtypeTables).forEach(r -> {
                    relationsByTable.get(r.getSourceTable().getId()).add(r);
                    if (r.getRelationType() == TableRelation.Type.FK) foreignKeys.get(r.getSourceTable().getId()).add(fk(r));
                });
            } else {
                // 투영 1행 = 관계 컬럼 1쌍 (관계 ID·컬럼 순서 정렬) : 0 관계 ID, 1 이름, 2 출처, 3 원본 테이블, 4 대상 테이블, 5 대상 테이블명, 6·7 원본 컬럼 ID·이름, 8·9 대상 컬럼 ID·이름, 10 관계 컬럼 ID (없으면 쌍 없음)
                Map<Long, Fk> byId = new LinkedHashMap<>();
                for (Object[] row : loaders.foreignKeyPairs().apply(subtypeTables)) {
                    Fk fk = byId.get((Long) row[0]);
                    if (fk == null) {
                        fk = new Fk((Long) row[0], (String) row[1], row[2] == null ? null : row[2].toString(), (Long) row[4], (String) row[5],
                                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
                        byId.put(fk.id(), fk);
                        foreignKeys.get((Long) row[3]).add(fk);
                    }
                    if (row[10] == null) continue; // 컬럼 쌍이 없는 관계
                    fk.childIds().add((Long) row[6]);
                    fk.childNames().add((String) row[7]);
                    fk.parentIds().add((Long) row[8]);
                    fk.parentNames().add((String) row[9]);
                }
            }
        }
        if (!keyTables.isEmpty() && loaders.primaryKeys() != null) {
            keyTables.forEach(id -> primaryKeys.put(id, new ArrayList<>()));
            // 투영 1행 = PK 컬럼 1개 : 0 테이블 ID, 1 컬럼 ID, 2 컬럼명, 3 순서
            for (Object[] row : loaders.primaryKeys().apply(keyTables)) primaryKeys.get((Long) row[0]).add(new KeyCol((Long) row[1], (String) row[2], (Integer) row[3]));
        }
    }

    private static Fk fk(TableRelation relation) {
        Fk fk = new Fk(relation.getId(), relation.getRelationName(), relation.getRelationSource() == null ? null : relation.getRelationSource().name(),
                relation.getTargetTable() == null ? null : relation.getTargetTable().getId(), relation.getTargetTable() == null ? null : relation.getTargetTable().getTableName(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (TableRelationColumn pair : relation.getColumns()) {
            fk.childIds().add(pair.getSourceColumn() == null ? null : pair.getSourceColumn().getId());
            fk.childNames().add(pair.getSourceColumn() == null ? null : pair.getSourceColumn().getColumnName());
            fk.parentIds().add(pair.getTargetColumn() == null ? null : pair.getTargetColumn().getId());
            fk.parentNames().add(pair.getTargetColumn() == null ? null : pair.getTargetColumn().getColumnName());
        }
        return fk;
    }

    /** 테이블 PK 컬럼 (컬럼 순서, 같으면 ID) — 투영으로 읽었으면 그것, 아니면 테이블 컬럼에서 (물리 생성) */
    private List<KeyCol> primaryKey(TableInfo table) {
        return primaryKeys.computeIfAbsent(table.getId(), id -> table.getColumns().stream().filter(c -> Boolean.TRUE.equals(c.getIsPk()))
                .map(c -> new KeyCol(c.getId(), c.getColumnName(), c.getSortOrder())).toList()).stream()
                .sorted(Comparator.comparing(KeyCol::sortOrder, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(KeyCol::id)).toList();
    }

    LogicalHierarchy hierarchy() {
        return hierarchy;
    }

    boolean participates(Long entityId) {
        return hierarchy.participates(entityId);
    }

    boolean isSubtype(Long entityId) {
        return hierarchy.parent(entityId) != null;
    }

    /** 미리 읽은 엔터티 (루트·조상). 없으면 한 번 읽어 둔다 — 고른 엔터티와 무관한 관계 부모의 루트 */
    LogicalEntity entity(Long entityId) {
        LogicalEntity known = entities.get(entityId);
        if (known != null) return known;
        loaders.entities().apply(List.of(entityId)).forEach(e -> entities.put(e.getId(), e));
        LogicalEntity loaded = entities.get(entityId);
        if (loaded == null) throw new IllegalStateException("entity " + entityId);
        return loaded;
    }

    Optional<EntityTableMapping> mapping(Long entityId) {
        return mappings.getOrDefault(entityId, Optional.empty());
    }

    /** 매핑된 서브타입의 상속 PK 매핑 : 원본 속성 ID → 매핑 ({@link #prepare} 로 읽은 것만) */
    Map<Long, SubtypeIdentifierColumnMapping> inheritedKeys(Long mappingId) {
        return inherited.getOrDefault(mappingId, Map.of());
    }

    String role(Long entityId) {
        if (!isSubtype(entityId)) return "ROOT";
        return hierarchy.groupsOf(entityId).isEmpty() ? "SUBTYPE" : "INTERMEDIATE";
    }

    /** 루트 주 식별자 속성 (식별자 속성 순서, 같거나 없으면 식별자 속성 ID — 일반 생성의 PK 순서와 같은 규칙). 루트에 주 식별자가 없으면 빈 목록 */
    List<LogicalAttribute> rootKey(Long entityId) {
        LogicalEntity root = entity(hierarchy.root(entityId));
        return root.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst()
                .map(identifier -> identifier.getAttributes().stream()
                        .sorted(Comparator.comparing(LogicalIdentifierAttribute::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(LogicalIdentifierAttribute::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(LogicalIdentifierAttribute::getAttribute).toList())
                .orElse(List.of());
    }

    LogicalSubtypeGroup group(Long groupId) {
        return groups.get(groupId);
    }

    Collection<LogicalSubtypeGroup> groups() {
        return groups.values();
    }

    /**
     * 조상 하나의 상태 (계획 안에서 같은 조상은 한 번만 판정). planned : 이번 계획에서 새로 만드는 엔터티(고르고 SKIP 이 아닌 것)
     */
    AncestorState ancestor(Long ancestorId, Set<Long> planned) {
        AncestorState known = states.get(ancestorId);
        if (known != null) return known;
        AncestorState state = judge(ancestorId, planned);
        states.put(ancestorId, state);
        return state;
    }

    private AncestorState judge(Long id, Set<Long> planned) {
        if (planned.contains(id)) return new AncestorState(id, null, null, List.of());
        Optional<EntityTableMapping> mapping = mapping(id);
        if (mapping.isEmpty()) return new AncestorState(id, ANCESTOR_MISSING, null, List.of());
        TableInfo table = mapping.get().getTable();
        List<LogicalAttribute> key = rootKey(id);
        if (key.isEmpty()) return new AncestorState(id, ROOT_KEY_MISSING, table, List.of());
        return isSubtype(id) ? judgeSubtype(id, mapping.get(), table, key, planned) : judgeRoot(id, table, key);
    }

    private AncestorState judgeRoot(Long id, TableInfo table, List<LogicalAttribute> key) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (LogicalAttribute attribute : key) {
            AttributeColumnMapping direct = directMappings.get(attribute.getId());
            ColumnInfo column = direct == null ? null : direct.getColumn();
            if (!inTable(column, table)) return new AncestorState(id, ROOT_KEY_MAPPING_MISSING, table, List.of());
            columns.add(column);
        }
        if (!isTableKey(table, columns)) return new AncestorState(id, PARENT_KEY_MISMATCH, table, List.of());
        return new AncestorState(id, null, table, columns);
    }

    private AncestorState judgeSubtype(Long id, EntityTableMapping mapping, TableInfo table, List<LogicalAttribute> key, Set<Long> planned) {
        Map<Long, SubtypeIdentifierColumnMapping> keys = inheritedKeys(mapping.getId());
        List<ColumnInfo> columns = new ArrayList<>();
        for (LogicalAttribute attribute : key) {
            SubtypeIdentifierColumnMapping link = keys.get(attribute.getId());
            ColumnInfo column = link == null ? null : link.getColumn();
            if (!inTable(column, table)) return new AncestorState(id, INHERITED_KEY_MAPPING_MISSING, table, List.of());
            columns.add(column);
        }
        if (!isTableKey(table, columns)) return new AncestorState(id, PARENT_KEY_MISMATCH, table, List.of());
        AncestorState parent = ancestor(hierarchy.parent(id), planned);
        if (parent.code() != null) return new AncestorState(id, ANCESTOR_INVALID, table, List.of());
        // 부모를 이번에 새로 만든다면 기존 테이블이 그 부모를 참조하는 FK 를 가질 수 없다
        if (parent.table() == null) return new AncestorState(id, INHERITANCE_FK_MISSING, table, List.of());
        for (int i = 0; i < columns.size(); i++) {
            if (!TableDdlGenerator.isReferenceTypeCompatible(db, columns.get(i), parent.keyColumns().get(i))) {
                return new AncestorState(id, INHERITED_KEY_TYPE_MISMATCH, table, List.of());
            }
        }
        if (!hasForeignKey(table, parent.table(), columns, parent.keyColumns())) return new AncestorState(id, INHERITANCE_FK_MISSING, table, List.of());
        return new AncestorState(id, null, table, columns);
    }

    /** 테이블에서 부모 테이블로, 키 컬럼 쌍(자식 i → 부모 i)과 정확히 같은 FK 가 있는가 (출처는 묻지 않는다 — 참조 테이블명에서 만든 관계도 같은 FK) */
    private boolean hasForeignKey(TableInfo table, TableInfo parent, List<ColumnInfo> columns, List<ColumnInfo> parentColumns) {
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) expected.add(columns.get(i).getId() + ">" + parentColumns.get(i).getId());
        for (Fk fk : foreignKeys.getOrDefault(table.getId(), List.of())) {
            if (fk.targetTableId() == null || !fk.targetTableId().equals(parent.getId())) continue;
            Set<String> pairs = new HashSet<>();
            for (int i = 0; i < fk.childIds().size(); i++) {
                if (fk.childIds().get(i) == null || fk.parentIds().get(i) == null) continue;
                pairs.add(fk.childIds().get(i) + ">" + fk.parentIds().get(i));
            }
            if (pairs.equals(expected) && fk.childIds().size() == expected.size()) return true;
        }
        return false;
    }

    /** 부모 FK 판정에 쓴 관계 (계획 근거 — 물리 생성만, 관계를 엔티티로 읽은 경우) */
    List<TableRelation> relations(Long tableId) {
        if (loaders.relationsBySourceTables() == null) throw new IllegalStateException("relations are not loaded as entities");
        return relationsByTable.getOrDefault(tableId, List.of());
    }

    private static boolean inTable(ColumnInfo column, TableInfo table) {
        return column != null && column.getTableInfo() != null && column.getTableInfo().getId().equals(table.getId());
    }

    /** 키 컬럼 집합이 테이블 PK 전체와 같다 (같은 컬럼을 두 번 쓰지 않는다) */
    private boolean isTableKey(TableInfo table, List<ColumnInfo> columns) {
        Set<Long> tableKey = new HashSet<>();
        primaryKey(table).forEach(c -> tableKey.add(c.id()));
        Set<Long> mapped = new HashSet<>();
        columns.forEach(c -> mapped.add(c.getId()));
        return !tableKey.isEmpty() && tableKey.equals(mapped) && mapped.size() == columns.size();
    }

    /** 서브타입의 조상 문제 (직계 부모 → 루트 순, 조상마다 첫 문제) — 문제가 없으면 빈 목록. 부모 문제의 결과(ANCESTOR_INVALID)는 원인이 따로 나오므로 뺀다 */
    List<AncestorState> problems(Long entityId, Set<Long> planned) {
        List<AncestorState> result = new ArrayList<>();
        for (Long ancestorId : hierarchy.ancestors(entityId)) {
            AncestorState state = ancestor(ancestorId, planned);
            if (state.code() != null && !ANCESTOR_INVALID.equals(state.code())) result.add(state);
        }
        return result;
    }

    // ------------------------------------------------------------------ 구조 정합성 상세 (M-7 4차 — 매핑 화면·정합성 비교)

    /** 상세 점검 1건. level : OK · ERROR(구조 불일치) · MISSING(필요한 상속 PK 매핑 없음) · UNKNOWN(판단할 정보 부족) · WARNING(구조는 맞음 — 참고) */
    record Check(String code, String level, java.util.function.Supplier<String> text) {
        /** 문구는 읽을 때 만든다 (목록·정합성 비교는 대부분 읽지 않는다 — 서브타입마다 문구를 만들면 큰 모델에서 비싸다) */
        String message() {
            return text.get();
        }
    }

    /** 상속 PK 1개의 점검 : 원본(루트 주 식별자) 속성 기준. state : OK · UNMAPPED · OTHER_TABLE · NOT_PK · TYPE_MISMATCH · LENGTH_DIFFERS */
    record KeyState(Long attributeId, String state, ColumnInfo column, ColumnInfo parentColumn) {
    }

    /**
     * 매핑된 서브타입 1개의 구조 정합성 상세 (조회 때 계산). code = {@link #ancestor} 의 판정 그대로 (기존 부모 재사용과 같은 결과 — null 이면 유효).
     * checks 는 같은 보조 판정을 항목별로 나눈 것이다 : code 가 null ⇔ ERROR·MISSING·UNKNOWN 점검이 없다 ({@code SubtypeMappingUiIntegrationTest} 가 확인한다).
     * result : 정합성 비교 상태 — ERROR → MISMATCH, 그 밖에 MISSING → UNMAPPED, UNKNOWN → NOT_COMPARABLE, 아니면 MATCH (WARNING 은 바꾸지 않는다)
     */
    record Inspection(String code, String result, TableInfo table, TableInfo parentTable, Long parentMappingId, List<KeyCol> tableKey,
                      List<ColumnInfo> parentKey, List<KeyState> keys, List<Fk> foreignKeys, List<Check> checks) {
    }

    static final String OK = "OK";
    static final String ERROR = "ERROR";
    static final String MISSING = "MISSING";
    static final String UNKNOWN = "UNKNOWN";
    static final String WARNING = "WARNING";
    /** 상위 쪽 정보가 없어 판단할 수 없는 사유 (불일치가 아니다) */
    private static final Set<String> INFO_MISSING = Set.of(ANCESTOR_MISSING, ROOT_KEY_MISSING, ROOT_KEY_MAPPING_MISSING);

    /** 매핑된 서브타입의 구조 정합성 상세. {@link #prepare} 로 그 엔터티를 읽은 뒤 부른다 (기존 매핑만 — 이번 계획에서 만드는 조상은 없다) */
    Inspection inspect(Long id) {
        EntityTableMapping mapping = mapping(id).orElseThrow(() -> new IllegalStateException("mapping " + id));
        TableInfo table = mapping.getTable();
        AncestorState judged = ancestor(id, Set.of());
        List<Check> checks = new ArrayList<>();
        List<KeyCol> tableKey = primaryKey(table);
        Long parentId = hierarchy.parent(id);
        Optional<EntityTableMapping> parentMapping = mapping(parentId);
        TableInfo parentTable = parentMapping.map(EntityTableMapping::getTable).orElse(null);
        Long parentMappingId = parentMapping.map(EntityTableMapping::getId).orElse(null);
        List<LogicalAttribute> key = rootKey(id);
        String parentName = entity(parentId).getEntityName();
        List<Fk> outgoing = foreignKeys.getOrDefault(table.getId(), List.of());
        if (key.isEmpty()) {
            checks.add(new Check(ROOT_KEY_MISSING, UNKNOWN, () -> Messages.get("루트 엔터티 {0} 에 주 식별자가 없어 상속 PK 를 판단할 수 없습니다.", entity(hierarchy.root(id)).getEntityName())));
            return new Inspection(judged.code(), result(checks), table, parentTable, parentMappingId, tableKey, List.of(), List.of(), outgoing, checks);
        }
        Map<Long, SubtypeIdentifierColumnMapping> links = inheritedKeys(mapping.getId());
        AncestorState parent = ancestor(parentId, Set.of());
        List<ColumnInfo> parentKey = parent.code() == null ? parent.keyColumns() : List.of();

        // 1. 연결 완전성 · 컬럼 소속
        List<ColumnInfo> columns = new ArrayList<>();
        int linked = 0;
        boolean owned = true;
        for (LogicalAttribute attribute : key) {
            SubtypeIdentifierColumnMapping link = links.get(attribute.getId());
            if (link == null) continue;
            linked++;
            if (!inTable(link.getColumn(), table)) owned = false;
            columns.add(link.getColumn());
        }
        boolean complete = linked == key.size();
        int linkedCount = linked;
        boolean ownedAll = owned;
        checks.add(new Check("KEY_COMPLETE", complete ? OK : MISSING, () -> complete ? Messages.get("루트 주 식별자 {0}개가 모두 상속 PK 매핑되어 있습니다.", key.size())
                : Messages.get("루트 주 식별자 {0}개 중 {1}개만 상속 PK 매핑되어 있습니다. 매핑하지 않은 {2}개를 등록해야 구조를 확인할 수 있습니다.", key.size(), linkedCount, key.size() - linkedCount)));
        checks.add(new Check("COLUMN_OWNER", owned ? OK : ERROR, () -> ownedAll ? Messages.get("매핑된 컬럼이 모두 테이블 {0} 의 컬럼입니다.", table.getTableName())
                : Messages.get("테이블 {0} 에 속하지 않은 컬럼이 상속 PK 매핑에 있습니다.", table.getTableName())));
        long stale = links.keySet().stream().filter(a -> key.stream().noneMatch(k -> k.getId().equals(a))).count();
        if (stale > 0) {
            checks.add(new Check("STALE_MAPPING", WARNING, () -> Messages.get("지금 루트 주 식별자에 없는 속성의 기존 상속 PK 매핑이 {0}건 있습니다. 구조 판정에는 쓰지 않으며 해제할 수 있습니다.", stale)));
        }

        // 2. 물리 PK 구성 · 순서 · NOT NULL (연결이 완전할 때만)
        boolean keyOk = complete && owned;
        if (!keyOk) {
            checks.add(new Check("PK_SET", UNKNOWN, () -> Messages.get("상속 PK 매핑이 완전하지 않아 물리 PK 구성을 비교하지 않았습니다.")));
        } else {
            boolean set = isTableKey(table, columns);
            checks.add(new Check("PK_SET", set ? OK : ERROR, () -> set ? Messages.get("테이블 PK 가 상속 PK 매핑 컬럼과 같습니다.")
                    : Messages.get("테이블 {0} 의 PK 구성({1})이 상속 PK 매핑 컬럼({2})과 다릅니다.", table.getTableName(), keyNames(tableKey), names(columns))));
            if (set) {
                boolean ordered = tableKey.stream().map(KeyCol::id).toList().equals(columns.stream().map(ColumnInfo::getId).toList());
                checks.add(new Check("PK_ORDER", ordered ? OK : WARNING, () -> ordered ? Messages.get("PK 컬럼 순서가 루트 주 식별자 순서와 같습니다.")
                        : Messages.get("PK 컬럼 순서({0})가 루트 주 식별자 순서({1})와 다릅니다. 상속 FK 는 식별자 순서로 짝지으므로 구조 판정은 바꾸지 않습니다.", keyNames(tableKey), names(columns))));
            }
            List<String> nullable = columns.stream().filter(c -> !TableDdlGenerator.isNotNull(c)).map(ColumnInfo::getColumnName).toList();
            checks.add(new Check("NOT_NULL", nullable.isEmpty() ? OK : ERROR, () -> nullable.isEmpty() ? Messages.get("상속 PK 컬럼이 모두 NOT NULL 입니다.")
                    : Messages.get("상속 PK 컬럼 {0} 이(가) NULL 을 허용합니다.", String.join(", ", nullable))));
        }

        // 3. 직계 부모 · 상위 상속 경로
        String parentCause = null;
        if (parentMapping.isEmpty()) {
            checks.add(new Check("PARENT_TABLE", UNKNOWN, () -> Messages.get("직계 상위 엔터티 {0} 이(가) 물리 테이블에 매핑되지 않아 상속 FK 를 확인할 수 없습니다.", parentName)));
        } else {
            checks.add(new Check("PARENT_TABLE", OK, () -> Messages.get("직계 상위 엔터티 {0} 은(는) 테이블 {1} 에 매핑되어 있습니다.", parentName, parentTable.getTableName())));
            if (parent.code() == null) {
                checks.add(new Check("ANCESTOR_PATH", OK, () -> Messages.get("상위 상속 경로가 유효합니다.")));
            } else {
                List<AncestorState> causes = problems(id, Set.of());
                parentCause = causes.isEmpty() ? parent.code() : causes.get(0).code();
                checks.add(new Check("ANCESTOR_PATH", INFO_MISSING.contains(parentCause) ? UNKNOWN : ERROR, () ->
                        causes.isEmpty() ? parent.code() : message(causes.get(0), entity(id))));
            }
        }

        // 4. 타입·길이 · 부모 FK (내 키와 부모 키를 모두 알 때만)
        List<KeyState> states = new ArrayList<>();
        boolean compare = keyOk && parent.code() == null && parentTable != null;
        boolean typesOk = true;
        List<String> lengths = new ArrayList<>();
        for (int i = 0; i < key.size(); i++) {
            SubtypeIdentifierColumnMapping link = links.get(key.get(i).getId());
            ColumnInfo column = link == null ? null : link.getColumn();
            ColumnInfo parentColumn = parentKey.size() == key.size() ? parentKey.get(i) : null;
            // 타입은 PK 여부와 따로 본다 (PK 아님이 타입 불일치를 가리지 않게) — 키 하나의 표시는 첫 문제만
            boolean typeOk = column == null || parentColumn == null || TableDdlGenerator.isReferenceTypeCompatible(db, column, parentColumn);
            boolean sameType = typeOk && (column == null || parentColumn == null || TableDdlGenerator.mappedType(db, column).equals(TableDdlGenerator.mappedType(db, parentColumn)));
            String state;
            if (column == null) state = "UNMAPPED";
            else if (!inTable(column, table)) state = "OTHER_TABLE";
            else if (!Boolean.TRUE.equals(column.getIsPk())) state = "NOT_PK";
            else if (!typeOk) state = "TYPE_MISMATCH";
            else if (!sameType) state = "LENGTH_DIFFERS";
            else state = OK;
            if (!typeOk) typesOk = false;
            if (typeOk && !sameType) lengths.add(column.getColumnName());
            states.add(new KeyState(key.get(i).getId(), state, column, parentColumn));
        }
        if (!compare) {
            checks.add(new Check("KEY_TYPE", UNKNOWN, () -> Messages.get("상속 PK 또는 직계 상위 테이블의 키를 확정할 수 없어 타입과 부모 FK 를 비교하지 않았습니다.")));
        } else {
            boolean typesAll = typesOk;
            checks.add(new Check("KEY_TYPE", typesOk ? OK : ERROR, () -> typesAll ? Messages.get("상속 PK 컬럼 타입이 직계 상위 테이블의 키와 FK 로 묶일 수 있습니다.")
                    : Messages.get("상속 PK 컬럼 타입이 직계 상위 테이블 {0} 의 키와 FK 로 묶일 수 없습니다.", parentTable.getTableName())));
            if (typesOk && !lengths.isEmpty()) {
                checks.add(new Check("KEY_LENGTH", WARNING, () -> Messages.get("상속 PK 컬럼 {0} 의 길이·정밀도가 상위 키와 다릅니다. FK 로는 묶일 수 있습니다.", String.join(", ", lengths))));
            }
            if (typesOk) {
                boolean fk = hasForeignKey(table, parentTable, columns, parentKey);
                checks.add(new Check("PARENT_FK", fk ? OK : ERROR, () -> fk ? Messages.get("직계 상위 테이블 {0} 을(를) 상속 PK 그대로 참조하는 FK 가 있습니다.", parentTable.getTableName())
                        : foreignKeyProblem(table, parentTable, columns, outgoing)));
            }
        }
        // 판정과 항목 점검이 어긋나지 않게 판정 결과를 기준으로 남긴다 (항목에 잡히지 않은 판정 사유가 있으면 그 사유를 오류로 더한다)
        if (judged.code() != null && checks.stream().noneMatch(c -> ERROR.equals(c.level()) || MISSING.equals(c.level()) || UNKNOWN.equals(c.level()))) {
            checks.add(new Check(judged.code(), ERROR, () -> judged.code()));
        }
        return new Inspection(judged.code(), result(checks), table, parentTable, parentMappingId, tableKey, parentKey, states, outgoing, checks);
    }

    /** 부모 FK 가 없을 때 이유 : 부모를 참조하는 FK 의 컬럼 쌍이 다르거나 일부만 있음 · 상속 PK 컬럼이 다른 테이블을 참조 · FK 없음 */
    private static String foreignKeyProblem(TableInfo table, TableInfo parent, List<ColumnInfo> columns, List<Fk> outgoing) {
        Set<Long> keyIds = new HashSet<>();
        columns.forEach(c -> keyIds.add(c.getId()));
        for (Fk fk : outgoing) {
            if (fk.targetTableId() != null && fk.targetTableId().equals(parent.getId())) {
                return Messages.get("테이블 {0} 에서 {1} 을(를) 참조하는 FK 가 있지만 컬럼 쌍이 상속 PK 전체와 같지 않습니다 (일부 누락 또는 다른 컬럼).", table.getTableName(), parent.getTableName());
            }
        }
        for (Fk fk : outgoing) {
            boolean usesKey = fk.childIds().stream().anyMatch(id -> id != null && keyIds.contains(id));
            if (usesKey && fk.targetTableId() != null) {
                return Messages.get("상속 PK 컬럼이 직계 상위 테이블 {0} 이(가) 아닌 테이블 {1} 을(를) 참조합니다.", parent.getTableName(), fk.targetTableName());
            }
        }
        return Messages.get("테이블 {0} 에 직계 상위 테이블 {1} 을(를) 참조하는 FK 가 없습니다.", table.getTableName(), parent.getTableName());
    }

    private static String result(List<Check> checks) {
        if (checks.stream().anyMatch(c -> ERROR.equals(c.level()))) return "MISMATCH";
        if (checks.stream().anyMatch(c -> MISSING.equals(c.level()))) return "UNMAPPED";
        if (checks.stream().anyMatch(c -> UNKNOWN.equals(c.level()))) return "NOT_COMPARABLE";
        return "MATCH";
    }

    private static String names(List<ColumnInfo> columns) {
        return String.join(", ", columns.stream().map(ColumnInfo::getColumnName).toList());
    }

    private static String keyNames(List<KeyCol> columns) {
        return String.join(", ", columns.stream().map(KeyCol::name).toList());
    }

    String message(AncestorState state, LogicalEntity subtype) {
        String ancestor = entity(state.entityId()).getEntityName();
        String table = state.table() == null ? "" : state.table().getTableName();
        return switch (state.code()) {
            case ANCESTOR_MISSING -> Messages.get("상위 엔터티 {0} 의 물리 테이블이 없습니다. {0} 을(를) 함께 고르거나 먼저 만들어주세요. (서브타입 {1})", ancestor, subtype.getEntityName());
            case ROOT_KEY_MISSING -> Messages.get("루트 엔터티 {0} 에 주 식별자가 없어 상속 PK 를 만들 수 없습니다.", ancestor);
            case ROOT_KEY_MAPPING_MISSING -> Messages.get("루트 엔터티 {0} 의 주 식별자 속성이 테이블 {1} 의 컬럼에 매핑되지 않아 상속 FK 의 참조 컬럼을 정할 수 없습니다.", ancestor, table);
            case PARENT_KEY_MISMATCH -> Messages.get("테이블 {0} 의 PK 가 엔터티 {1} 의 주 식별자 매핑 컬럼과 같지 않아 상속 FK 로 참조할 수 없습니다. 기존 테이블은 고치지 않습니다.", table, ancestor);
            case INHERITED_KEY_MAPPING_MISSING -> Messages.get("상위 엔터티 {0} 는 테이블 {1} 에 매핑되어 있지만 루트 주 식별자 전체의 상속 PK 매핑이 없어 그 테이블의 PK 를 확인할 수 없습니다. 상속 PK 매핑을 먼저 등록해주세요.", ancestor, table);
            case INHERITED_KEY_TYPE_MISMATCH -> Messages.get("테이블 {0} 의 상속 PK 컬럼 타입이 상위 테이블의 키 컬럼과 FK 로 묶일 수 없습니다. 기존 테이블은 고치지 않습니다. (엔터티 {1})", table, ancestor);
            case INHERITANCE_FK_MISSING -> Messages.get("테이블 {0} 에 직계 상위 테이블을 상속 PK 그대로 참조하는 FK 가 없어 상속 경로를 확인할 수 없습니다. 기존 테이블은 고치지 않습니다. (엔터티 {1})", table, ancestor);
            default -> state.code();
        };
    }
}
