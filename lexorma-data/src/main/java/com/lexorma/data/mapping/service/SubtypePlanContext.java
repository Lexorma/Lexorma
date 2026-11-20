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

    /** 요청 안의 일괄 조회 (리포지토리 메서드) */
    record Loaders(Function<Collection<Long>, List<LogicalEntity>> entities, Function<Collection<Long>, List<EntityTableMapping>> mappings,
                   Function<Collection<Long>, List<AttributeColumnMapping>> directByAttributes,
                   Function<Collection<Long>, List<SubtypeIdentifierColumnMapping>> inheritedByMappings,
                   Function<Collection<Long>, List<TableRelation>> relationsBySourceTables) {
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
        for (Long id : chain) {
            Optional<EntityTableMapping> mapping = mappings.get(id);
            if (mapping.isEmpty() || !entities.containsKey(hierarchy.root(id))) continue;
            if (isSubtype(id)) {
                if (!inherited.containsKey(mapping.get().getId())) subtypeMappings.add(mapping.get().getId());
                if (!relationsByTable.containsKey(mapping.get().getTable().getId())) subtypeTables.add(mapping.get().getTable().getId());
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
            subtypeTables.forEach(id -> relationsByTable.put(id, new ArrayList<>()));
            loaders.relationsBySourceTables().apply(subtypeTables).forEach(r -> relationsByTable.get(r.getSourceTable().getId()).add(r));
        }
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
        for (TableRelation relation : relationsByTable.getOrDefault(table.getId(), List.of())) {
            if (relation.getRelationType() != TableRelation.Type.FK || relation.getTargetTable() == null || !relation.getTargetTable().getId().equals(parent.getId())) continue;
            Set<String> pairs = new HashSet<>();
            for (TableRelationColumn pair : relation.getColumns()) {
                if (pair.getSourceColumn() == null || pair.getTargetColumn() == null) continue;
                pairs.add(pair.getSourceColumn().getId() + ">" + pair.getTargetColumn().getId());
            }
            if (pairs.equals(expected) && relation.getColumns().size() == expected.size()) return true;
        }
        return false;
    }

    /** 부모 FK 판정에 쓴 관계 (계획 근거) */
    List<TableRelation> relations(Long tableId) {
        return relationsByTable.getOrDefault(tableId, List.of());
    }

    private static boolean inTable(ColumnInfo column, TableInfo table) {
        return column != null && column.getTableInfo() != null && column.getTableInfo().getId().equals(table.getId());
    }

    /** 키 컬럼 집합이 테이블 PK 전체와 같다 (같은 컬럼을 두 번 쓰지 않는다) */
    private static boolean isTableKey(TableInfo table, List<ColumnInfo> columns) {
        Set<Long> tableKey = new HashSet<>();
        table.getColumns().stream().filter(c -> Boolean.TRUE.equals(c.getIsPk())).forEach(c -> tableKey.add(c.getId()));
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
