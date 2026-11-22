package com.lexorma.data.mapping.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Structure;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.StructureColumn;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.AttributeColumnRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.ColumnFlagRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.EntityRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.ForeignKeyRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.IdentifierRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.InheritedKeyRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.PairRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.RelationshipRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.UniqueRow;
import com.lexorma.data.table.ddl.DbIdentifierRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 논리·물리 구조 정합성 판정 (M-7 구조 정합성 — 순수 함수, 값만 받아 판정하고 아무것도 바꾸지 않는다. 정책 M7-C01~C06).
 * <ul>
 *   <li><b>RELATIONSHIP</b> (논리 관계 ↔ 물리 FK, 관계마다 1행) : 부모(source)·자식(target) 엔터티의 테이블 매핑과 관계 속성 짝(짝 순서)을 저장된 매핑으로 컬럼 쌍으로 바꿔
 *       자식 테이블의 FK(원본 = 자식, 대상 = 부모, 컬럼 순서)와 비교한다. 짝 판정은 {@code LogicalRelationshipPairs.evaluate} 와 같은 규칙(유효 주 식별자 = 루트의 주 식별자)이다.
 *       COMPLETE 짝이 아니면 비교 불가(물리 생성도 FK 를 만들지 않는 상태 — 생성 판정과 같은 기준), 매핑이 없으면 UNMAPPED, 같은 구성·순서의 FK 가 있으면 MATCH,
 *       순서만 다르면·참조 대상이 다르면·구성이 다르면 MISMATCH, 후보가 여럿이면 비교 불가, FK 가 없으면 UNMAPPED(FK_MISSING) — 단 부모 컬럼이 부모 테이블 PK 가 아니거나
 *       자기 키만 잇는 자기 참조처럼 생성 대상이 아닌 관계는 비교 불가로 둔다 (FK 가 없는 것을 오류로 단정하지 않는다).</li>
 *   <li><b>ALTERNATE_KEY</b> (보조 식별자 ↔ UNIQUE, 보조 식별자마다 1행) : 구성 속성(식별자 속성 순서 SORT_ORD — 순서가 저장되어 있어 순서도 비교한다)의 매핑 컬럼과
 *       그 테이블의 UNIQUE 제약조건(구성 컬럼 순서)·컬럼 단위 고유값(PK 아님, DDL 의 UK_테이블_컬럼)을 비교한다. UNIQUE 인덱스·식·부분 인덱스는 UNIQUE 로 보지 않는다.</li>
 *   <li><b>PHYSICAL_FK · PHYSICAL_UNIQUE</b> (물리 기준 역방향) : 위 판정이 대응시키지 않은(claim 하지 않은) FK·UNIQUE 만 1행씩 — 같은 쌍을 두 번 보이지 않는다.
 *       서브타입 테이블 → 직계 슈퍼타입 테이블 FK 는 상속 FK 라 [상속 구조] 진단(INHERITANCE)이 본다 (여기서 내지 않는다).</li>
 * </ul>
 * 범위 : 그 논리 모델의 엔터티·관계·식별자와, 그 엔터티에 매핑된 테이블의 FK(자식 쪽)·UNIQUE. 대응은 저장된 매핑으로만 정한다 — 이름이나 표준 용어로 짝을 짓지 않는다 (M7-C02).
 */
public final class ModelStructureQualityJudge {

    private ModelStructureQualityJudge() {
    }

    public record Input(Long modelId, String modelName, List<EntityRow> entities, List<RelationshipRow> relationships, List<PairRow> pairs,
                        List<IdentifierRow> identifiers, List<AttributeColumnRow> attributeColumns, List<InheritedKeyRow> inheritedKeys,
                        List<ForeignKeyRow> foreignKeys, List<UniqueRow> uniqueConstraints, List<ColumnFlagRow> uniqueColumns, List<ColumnFlagRow> primaryKeyColumns,
                        /* 엔터티 → 직계 슈퍼타입 (없으면 null) */ Function<Long, Long> supertypeOf,
                        /* 엔터티 → 상속 루트 (참여하지 않으면 자기 자신) */ Function<Long, Long> rootOf,
                        Set<Long> participants) {
    }

    private record Col(Long id, String name) {
    }

    private record FkCol(Long childId, String childName, Long parentId, String parentName) {
    }

    private record Fk(Long id, String name, Long childTableId, String childTableName, Long parentTableId, String parentTableName, List<FkCol> columns) {
        List<Long> childIds() {
            return columns.stream().map(FkCol::childId).toList();
        }

        String text() {
            return Objects.requireNonNullElse(name, "FK") + " (" + columns.stream().map(FkCol::childName).collect(Collectors.joining(", ")) + ") → "
                    + parentTableName + " (" + columns.stream().map(FkCol::parentName).collect(Collectors.joining(", ")) + ")";
        }
    }

    private record Uq(String key, Long physicalId, String name, Long tableId, List<Col> columns, boolean columnLevel) {
        List<Long> ids() {
            return columns.stream().map(Col::id).toList();
        }

        String text() {
            return name + " (" + columns.stream().map(Col::name).collect(Collectors.joining(", ")) + ")";
        }
    }

    /** 판정 결과 : 진단 행 (조회 순서 = 엔터티명 → 관계 → 보조 식별자 → 물리 전용) */
    public static List<Row> judge(Input in) {
        Context c = new Context(in);
        List<Row> rows = new ArrayList<>();
        rows.addAll(c.relationships());
        rows.addAll(c.alternateKeys());
        rows.addAll(c.physicalForeignKeys());
        rows.addAll(c.physicalUniques());
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < in.entities().size(); i++) order.putIfAbsent(in.entities().get(i).entityId(), i);
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(java.util.Comparator.comparing((Row r) -> order.getOrDefault(r.entityId(), Integer.MAX_VALUE)));
        return sorted;
    }

    private static final class Context {
        final Input in;
        final Map<Long, EntityRow> entityById = new LinkedHashMap<>();
        final Map<Long, EntityRow> entityByTable = new HashMap<>();
        final Map<Long, AttributeColumnRow> attributeColumn = new HashMap<>();
        final Map<String, InheritedKeyRow> inheritedKey = new HashMap<>();
        final Map<Long, String> attributeOfColumn = new HashMap<>();
        final Map<Long, List<Long>> primaryKey = new HashMap<>();
        final Map<Long, Fk> foreignKeys = new LinkedHashMap<>();
        final Map<Long, List<Fk>> foreignKeysByChildTable = new HashMap<>();
        final Map<String, Uq> uniques = new LinkedHashMap<>();
        final Map<Long, List<Uq>> uniquesByTable = new HashMap<>();
        final Map<Long, Set<Long>> pkByTable = new HashMap<>();
        final Set<Long> claimedForeignKeys = new HashSet<>();
        final Set<String> claimedUniques = new HashSet<>();
        final Map<Long, String> attributeName = new HashMap<>();

        Context(Input in) {
            this.in = in;
            in.entities().forEach(e -> {
                entityById.putIfAbsent(e.entityId(), e);
                if (e.tableId() != null) entityByTable.putIfAbsent(e.tableId(), e);
            });
            in.attributeColumns().forEach(a -> attributeColumn.put(a.attributeId(), a));
            in.inheritedKeys().forEach(k -> inheritedKey.put(k.entityId() + ":" + k.attributeId(), k));
            in.attributeColumns().forEach(a -> attributeName.put(a.attributeId(), a.attributeName()));
            in.inheritedKeys().forEach(k -> attributeName.put(k.attributeId(), k.attributeName()));
            in.identifiers().forEach(i -> attributeName.put(i.attributeId(), i.attributeName()));
            in.pairs().forEach(p -> {
                attributeName.put(p.parentAttributeId(), p.parentAttributeName());
                attributeName.put(p.childAttributeId(), p.childAttributeName());
            });
            in.attributeColumns().forEach(a -> attributeOfColumn.put(a.columnId(), attributeName.getOrDefault(a.attributeId(), String.valueOf(a.attributeId()))));
            in.inheritedKeys().forEach(k -> attributeOfColumn.putIfAbsent(k.columnId(), attributeName.getOrDefault(k.attributeId(), String.valueOf(k.attributeId()))));
            in.identifiers().stream().filter(i -> Boolean.TRUE.equals(i.primary()))
                    .forEach(i -> primaryKey.computeIfAbsent(i.entityId(), k -> new ArrayList<>()).add(i.attributeId()));
            for (ForeignKeyRow r : in.foreignKeys()) {
                Fk fk = foreignKeys.computeIfAbsent(r.relationId(), id -> new Fk(id, r.relationName(), r.childTableId(), r.childTableName(), r.parentTableId(),
                        r.parentTableName(), new ArrayList<>()));
                if (r.childColumnId() != null) fk.columns().add(new FkCol(r.childColumnId(), r.childColumnName(), r.parentColumnId(), r.parentColumnName()));
            }
            foreignKeys.values().forEach(fk -> foreignKeysByChildTable.computeIfAbsent(fk.childTableId(), k -> new ArrayList<>()).add(fk));
            for (UniqueRow r : in.uniqueConstraints()) {
                Uq uq = uniques.computeIfAbsent("C" + r.constraintId(), key -> new Uq(key, r.constraintId(), r.constraintName(), r.tableId(), new ArrayList<>(), false));
                uq.columns().add(new Col(r.columnId(), r.columnName()));
            }
            for (ColumnFlagRow r : in.uniqueColumns()) {
                uniques.put("U" + r.columnId(), new Uq("U" + r.columnId(), r.columnId(), DbIdentifierRules.uniqueName(r.tableName(), r.columnName()), r.tableId(),
                        List.of(new Col(r.columnId(), r.columnName())), true));
            }
            uniques.values().forEach(u -> uniquesByTable.computeIfAbsent(u.tableId(), k -> new ArrayList<>()).add(u));
            in.primaryKeyColumns().forEach(p -> pkByTable.computeIfAbsent(p.tableId(), k -> new HashSet<>()).add(p.columnId()));
        }

        /** 엔터티 문맥의 속성 → 컬럼 : 직접 속성 매핑(그 엔터티 테이블의 컬럼) → 상속 PK 매핑 (이름으로 추론하지 않는다) */
        Col columnFor(Long entityId, Long attributeId) {
            EntityRow e = entityById.get(entityId);
            if (e == null || e.tableId() == null) return null;
            AttributeColumnRow direct = attributeColumn.get(attributeId);
            if (direct != null && Objects.equals(direct.tableId(), e.tableId())) return new Col(direct.columnId(), direct.columnName());
            InheritedKeyRow inherited = inheritedKey.get(entityId + ":" + attributeId);
            if (inherited != null && Objects.equals(inherited.tableId(), e.tableId())) return new Col(inherited.columnId(), inherited.columnName());
            return null;
        }

        String entityName(Long id) {
            EntityRow e = entityById.get(id);
            return e == null ? String.valueOf(id) : e.entityName();
        }

        boolean inheritance(Long... entityIds) {
            for (Long id : entityIds) if (in.participants().contains(id)) return true;
            return false;
        }

        // ------------------------------------------------------------------ 논리 관계 ↔ 물리 FK

        private record Rel(RelationshipRow r, List<PairRow> pairs) {
        }

        List<Row> relationships() {
            Map<Long, List<PairRow>> pairsByRelationship = in.pairs().stream().collect(Collectors.groupingBy(PairRow::relationshipId, LinkedHashMap::new, Collectors.toList()));
            List<Rel> rels = in.relationships().stream().map(r -> new Rel(r, pairsByRelationship.getOrDefault(r.relationshipId(), List.of()))).toList();
            Map<Long, Row> result = new LinkedHashMap<>();
            // 1단계 : 구성·순서가 같은 FK 를 먼저 대응시킨다 (앞 관계의 불일치 판정이 뒤 관계의 정확한 FK 를 가져가지 않게)
            for (Rel rel : rels) {
                Expected x = expected(rel);
                if (x.columns == null) continue;
                Fk exact = foreignKeysByChildTable.getOrDefault(x.child.tableId(), List.of()).stream()
                        .filter(fk -> !claimedForeignKeys.contains(fk.id()) && Objects.equals(fk.parentTableId(), x.parent.tableId()) && sameOrdered(fk, x.columns))
                        .findFirst().orElse(null);
                if (exact != null) {
                    claimedForeignKeys.add(exact.id());
                    result.put(rel.r.relationshipId(), relationshipRow(rel, x, CheckStatus.MATCH, "FK_MATCH", exact, List.of(), null));
                }
            }
            // 2단계 : 나머지
            for (Rel rel : rels) {
                if (result.containsKey(rel.r.relationshipId())) continue;
                result.put(rel.r.relationshipId(), judgeRelationship(rel));
            }
            return new ArrayList<>(result.values());
        }

        /** 관계의 기대 구성 : 자식·부모 엔터티, 짝 상태, 짝별 (자식 컬럼, 부모 컬럼) — 매핑이 없으면 해당 값 null */
        private final class Expected {
            EntityRow child;
            EntityRow parent;
            String pairState;
            List<Col[]> columns; // [자식, 부모], 모두 정해졌을 때만
            final List<Col[]> partial = new ArrayList<>();
        }

        Expected expected(Rel rel) {
            Expected x = new Expected();
            x.child = entityById.get(rel.r.childEntityId());
            x.parent = entityById.get(rel.r.parentEntityId());
            List<Long> key = primaryKey.getOrDefault(in.rootOf().apply(rel.r.parentEntityId()), List.of());
            List<Long> parents = rel.pairs.stream().map(PairRow::parentAttributeId).toList();
            x.pairState = rel.pairs.isEmpty() ? "NONE" : key.isEmpty() ? "PARENT_KEY_MISSING" : !key.containsAll(parents) ? "PARENT_KEY_MISMATCH"
                    : parents.size() == key.size() ? "COMPLETE" : "PARTIAL";
            boolean all = x.child != null && x.parent != null && x.child.tableId() != null && x.parent.tableId() != null;
            for (PairRow p : rel.pairs) {
                Col childCol = x.child == null ? null : columnFor(x.child.entityId(), p.childAttributeId());
                Col parentCol = x.parent == null ? null : columnFor(x.parent.entityId(), p.parentAttributeId());
                x.partial.add(new Col[]{childCol, parentCol});
                if (childCol == null || parentCol == null) all = false;
            }
            if (all && "COMPLETE".equals(x.pairState)) x.columns = x.partial;
            return x;
        }

        static boolean sameOrdered(Fk fk, List<Col[]> expected) {
            if (fk.columns().size() != expected.size()) return false;
            for (int i = 0; i < expected.size(); i++) {
                FkCol c = fk.columns().get(i);
                if (!Objects.equals(c.childId(), expected.get(i)[0].id()) || !Objects.equals(c.parentId(), expected.get(i)[1].id())) return false;
            }
            return true;
        }

        static Set<String> pairSet(List<FkCol> columns) {
            return columns.stream().map(c -> c.childId() + ">" + c.parentId()).collect(Collectors.toSet());
        }

        Row judgeRelationship(Rel rel) {
            Expected x = expected(rel);
            if (x.child == null || x.parent == null || x.child.tableId() == null || x.parent.tableId() == null) {
                String side = x.child == null || x.child.tableId() == null ? entityName(rel.r.childEntityId()) : entityName(rel.r.parentEntityId());
                return relationshipRow(rel, x, CheckStatus.UNMAPPED, "ENTITY_UNMAPPED", null, List.of(),
                        Messages.get("엔터티 {0} 이(가) 물리 테이블에 매핑되지 않아 FK 와 비교할 수 없습니다.", side));
            }
            List<Fk> between = foreignKeysByChildTable.getOrDefault(x.child.tableId(), List.of()).stream()
                    .filter(fk -> !claimedForeignKeys.contains(fk.id()) && Objects.equals(fk.parentTableId(), x.parent.tableId())).toList();
            if (!"COMPLETE".equals(x.pairState)) {
                between.forEach(fk -> claimedForeignKeys.add(fk.id())); // 이 관계와 관련된 FK — 물리 전용으로 따로 보이지 않는다
                String code = switch (x.pairState) {
                    case "NONE" -> "PAIRS_MISSING";
                    case "PARENT_KEY_MISSING" -> "PARENT_KEY_MISSING";
                    case "PARTIAL" -> "PAIRS_INCOMPLETE";
                    default -> "PAIRS_INVALID";
                };
                String reason = switch (code) {
                    case "PAIRS_MISSING" -> Messages.get("관계 속성 짝(부모 식별자 ↔ 자식 속성)이 없어 FK 컬럼과 비교할 수 없습니다.");
                    case "PARENT_KEY_MISSING" -> Messages.get("부모 엔터티에 주 식별자가 없어 FK 와 비교할 수 없습니다.");
                    case "PAIRS_INCOMPLETE" -> Messages.get("관계 속성 짝이 부모 주 식별자의 일부만 이어 FK 와 비교할 수 없습니다.");
                    default -> Messages.get("관계 속성 짝이 지금 부모 주 식별자와 맞지 않아 FK 와 비교할 수 없습니다.");
                };
                return relationshipRow(rel, x, CheckStatus.NOT_COMPARABLE, code, between.size() == 1 ? between.get(0) : null, between, reason);
            }
            if (x.columns == null) {
                between.forEach(fk -> claimedForeignKeys.add(fk.id()));
                return relationshipRow(rel, x, CheckStatus.UNMAPPED, "ATTRIBUTE_UNMAPPED", between.size() == 1 ? between.get(0) : null, between,
                        Messages.get("관계 속성 짝의 일부 속성이 물리 컬럼에 매핑되지 않아 FK 와 비교할 수 없습니다."));
            }
            List<Fk> fromChild = foreignKeysByChildTable.getOrDefault(x.child.tableId(), List.of()).stream().filter(fk -> !claimedForeignKeys.contains(fk.id())).toList();
            // 같은 FK 가 이미 다른 관계에 정확히 대응했다
            boolean shared = foreignKeysByChildTable.getOrDefault(x.child.tableId(), List.of()).stream()
                    .anyMatch(fk -> claimedForeignKeys.contains(fk.id()) && Objects.equals(fk.parentTableId(), x.parent.tableId()) && sameOrdered(fk, x.columns));
            Set<String> expectedPairs = x.columns.stream().map(p -> p[0].id() + ">" + p[1].id()).collect(Collectors.toSet());
            Set<Long> expectedChild = x.columns.stream().map(p -> p[0].id()).collect(Collectors.toSet());
            Fk order = between.stream().filter(fk -> fk.columns().size() == x.columns.size() && pairSet(fk.columns()).equals(expectedPairs)).findFirst().orElse(null);
            if (order != null) {
                claimedForeignKeys.add(order.id());
                return relationshipRow(rel, x, CheckStatus.MISMATCH, "FK_ORDER", order, List.of(), Messages.get("FK 의 컬럼 순서가 관계 속성 짝 순서와 다릅니다."));
            }
            Fk target = fromChild.stream().filter(fk -> !Objects.equals(fk.parentTableId(), x.parent.tableId()) && new HashSet<>(fk.childIds()).equals(expectedChild)).findFirst()
                    .orElse(null);
            if (target != null) {
                claimedForeignKeys.add(target.id());
                return relationshipRow(rel, x, CheckStatus.MISMATCH, "FK_TARGET", target, List.of(),
                        Messages.get("같은 컬럼의 FK 가 부모 엔터티의 테이블 {0} 이(가) 아닌 {1} 을(를) 참조합니다.", x.parent.tableName(), target.parentTableName()));
            }
            if (between.size() == 1) {
                Fk other = between.get(0);
                claimedForeignKeys.add(other.id());
                return relationshipRow(rel, x, CheckStatus.MISMATCH, "FK_COLUMNS", other, List.of(), Messages.get("부모 테이블로의 FK 가 있지만 컬럼 구성이 관계 속성 짝과 다릅니다."));
            }
            if (between.size() > 1) {
                between.forEach(fk -> claimedForeignKeys.add(fk.id()));
                return relationshipRow(rel, x, CheckStatus.NOT_COMPARABLE, "FK_AMBIGUOUS", null, between,
                        Messages.get("부모 테이블로의 FK 가 여러 개이고 관계 속성 짝과 같은 것이 없어 어느 FK 에 대응하는지 정할 수 없습니다."));
            }
            if (shared) {
                return relationshipRow(rel, x, CheckStatus.NOT_COMPARABLE, "FK_SHARED", null, List.of(),
                        Messages.get("같은 구성의 FK 가 다른 논리 관계에 이미 대응합니다. 같은 엔터티 사이에 같은 속성 짝의 관계가 둘 이상입니다."));
            }
            // FK 가 없다 : 물리 생성이 FK 를 만들 수 있는 모양일 때만 FK 누락으로 본다 (부모 컬럼 = 부모 테이블 PK, 자기 키만 잇는 자기 참조가 아님 — 생성 판정과 같은 기준)
            Set<Long> parentColumns = x.columns.stream().map(p -> p[1].id()).collect(Collectors.toSet());
            boolean selfKeyOnly = Objects.equals(rel.r.parentEntityId(), rel.r.childEntityId())
                    && rel.pairs.stream().allMatch(p -> p.parentAttributeId().equals(p.childAttributeId()));
            Set<Long> pk = pkByTable.getOrDefault(x.parent.tableId(), Set.of());
            if (selfKeyOnly || pk.isEmpty() || !pk.equals(parentColumns)) {
                return relationshipRow(rel, x, CheckStatus.NOT_COMPARABLE, "FK_NOT_EXPECTED", null, List.of(), selfKeyOnly
                        ? Messages.get("자기 참조 관계의 자식 속성이 부모 키 속성 자신이라 물리 FK 를 만들 대상이 아닙니다.")
                        : Messages.get("관계 속성 짝의 부모 컬럼이 부모 테이블의 PK 와 같지 않아 물리 FK 를 만들 대상이 아닙니다 (FK 가 없는 것을 오류로 보지 않습니다)."));
            }
            return relationshipRow(rel, x, CheckStatus.UNMAPPED, "FK_MISSING", null, List.of(),
                    Messages.get("관계 속성 짝에 대응하는 물리 FK 가 없습니다. 논리 → 물리 생성이나 물리 모델에서 FK 를 만들 수 있습니다 (자동으로 만들지 않습니다)."));
        }

        Row relationshipRow(Rel rel, Expected x, CheckStatus status, String code, Fk fk, List<Fk> candidates, String reason) {
            List<StructureColumn> columns = new ArrayList<>();
            for (int i = 0; i < rel.pairs.size(); i++) {
                PairRow p = rel.pairs.get(i);
                Col[] e = i < x.partial.size() ? x.partial.get(i) : new Col[2];
                FkCol f = fk != null && i < fk.columns().size() ? fk.columns().get(i) : null;
                columns.add(new StructureColumn(i + 1, p.childAttributeId(), p.childAttributeName(), p.parentAttributeId(), p.parentAttributeName(),
                        e[0] == null ? null : e[0].id(), e[0] == null ? null : e[0].name(), e[1] == null ? null : e[1].id(), e[1] == null ? null : e[1].name(),
                        f == null ? null : f.childId(), f == null ? null : f.childName(), f == null ? null : f.parentId(), f == null ? null : f.parentName()));
            }
            if (fk != null) {
                for (int i = rel.pairs.size(); i < fk.columns().size(); i++) {
                    FkCol f = fk.columns().get(i);
                    columns.add(new StructureColumn(i + 1, null, null, null, null, null, null, null, null, f.childId(), f.childName(), f.parentId(), f.parentName()));
                }
            }
            EntityRow child = x.child != null ? x.child : entityById.get(rel.r.childEntityId());
            EntityRow parent = x.parent != null ? x.parent : entityById.get(rel.r.parentEntityId());
            String name = Objects.requireNonNullElse(rel.r.relationshipName(), "");
            String logical = entityName(rel.r.parentEntityId()) + " → " + entityName(rel.r.childEntityId()) + (name.isEmpty() ? "" : " : " + name)
                    + (rel.pairs.isEmpty() ? "" : " (" + rel.pairs.stream().map(p -> p.parentAttributeName() + " → " + p.childAttributeName()).collect(Collectors.joining(", ")) + ")");
            Structure structure = new Structure(CheckType.RELATIONSHIP, code, rel.r.relationshipId(), name.isEmpty() ? null : name,
                    parent == null ? null : parent.entityId(), parent == null ? null : parent.entityName(), parent == null ? null : parent.tableId(), parent == null ? null : parent.tableName(),
                    fk == null ? null : fk.id(), fk == null ? null : fk.name(), columns, candidates.stream().map(Fk::text).toList());
            return row(child, "R" + rel.r.relationshipId(), CheckType.RELATIONSHIP, status, logical, fk == null ? (candidates.isEmpty() ? "-" : String.join(" / ", candidates.stream().map(Fk::text).toList())) : fk.text(),
                    reason, inheritance(rel.r.parentEntityId(), rel.r.childEntityId()), structure);
        }

        // ------------------------------------------------------------------ 보조 식별자 ↔ UNIQUE

        List<Row> alternateKeys() {
            Map<Long, List<IdentifierRow>> byIdentifier = in.identifiers().stream().filter(i -> !Boolean.TRUE.equals(i.primary()))
                    .collect(Collectors.groupingBy(IdentifierRow::identifierId, LinkedHashMap::new, Collectors.toList()));
            Map<Long, Row> result = new LinkedHashMap<>();
            Map<Long, List<Col>> expected = new HashMap<>();
            for (Map.Entry<Long, List<IdentifierRow>> entry : byIdentifier.entrySet()) {
                IdentifierRow first = entry.getValue().get(0);
                List<Col> cols = new ArrayList<>();
                for (IdentifierRow r : entry.getValue()) cols.add(columnFor(first.entityId(), r.attributeId()));
                expected.put(entry.getKey(), cols);
                EntityRow e = entityById.get(first.entityId());
                if (e == null || e.tableId() == null || cols.contains(null)) continue;
                List<Long> ids = cols.stream().map(Col::id).toList();
                Uq exact = uniquesByTable.getOrDefault(e.tableId(), List.of()).stream().filter(u -> !claimedUniques.contains(u.key()) && u.ids().equals(ids)).findFirst().orElse(null);
                if (exact != null) {
                    claimedUniques.add(exact.key());
                    result.put(entry.getKey(), alternateKeyRow(entry.getValue(), cols, CheckStatus.MATCH, "UNIQUE_MATCH", exact, List.of(), null));
                }
            }
            for (Map.Entry<Long, List<IdentifierRow>> entry : byIdentifier.entrySet()) {
                if (result.containsKey(entry.getKey())) continue;
                result.put(entry.getKey(), judgeAlternateKey(entry.getValue(), expected.get(entry.getKey())));
            }
            return new ArrayList<>(result.values());
        }

        Row judgeAlternateKey(List<IdentifierRow> rows, List<Col> cols) {
            IdentifierRow first = rows.get(0);
            EntityRow e = entityById.get(first.entityId());
            if (e == null || e.tableId() == null) {
                return alternateKeyRow(rows, cols, CheckStatus.UNMAPPED, "ENTITY_UNMAPPED", null, List.of(), Messages.get("엔터티가 물리 테이블에 매핑되지 않아 UNIQUE 와 비교할 수 없습니다."));
            }
            if (cols.contains(null)) {
                return alternateKeyRow(rows, cols, CheckStatus.UNMAPPED, "ATTRIBUTE_UNMAPPED", null, List.of(),
                        Messages.get("보조 식별자의 일부 속성이 물리 컬럼에 매핑되지 않아 UNIQUE 와 비교할 수 없습니다."));
            }
            List<Long> ids = cols.stream().map(Col::id).toList();
            Set<Long> idSet = new HashSet<>(ids);
            List<Uq> available = uniquesByTable.getOrDefault(e.tableId(), List.of()).stream().filter(u -> !claimedUniques.contains(u.key())).toList();
            Uq order = available.stream().filter(u -> u.ids().size() == ids.size() && new HashSet<>(u.ids()).equals(idSet)).findFirst().orElse(null);
            if (order != null) {
                claimedUniques.add(order.key());
                return alternateKeyRow(rows, cols, CheckStatus.MISMATCH, "UNIQUE_ORDER", order, List.of(), Messages.get("UNIQUE 의 컬럼 순서가 보조 식별자의 속성 순서와 다릅니다."));
            }
            int best = 0;
            List<Uq> bestList = new ArrayList<>();
            for (Uq u : available) {
                int overlap = (int) u.ids().stream().filter(idSet::contains).count();
                if (overlap == 0) continue;
                if (overlap > best) {
                    best = overlap;
                    bestList.clear();
                }
                if (overlap == best) bestList.add(u);
            }
            if (bestList.size() > 1) {
                bestList.forEach(u -> claimedUniques.add(u.key()));
                return alternateKeyRow(rows, cols, CheckStatus.NOT_COMPARABLE, "UNIQUE_AMBIGUOUS", null, bestList,
                        Messages.get("보조 식별자와 일부 컬럼이 겹치는 UNIQUE 가 여러 개라 어느 것에 대응하는지 정할 수 없습니다."));
            }
            if (bestList.size() == 1) {
                Uq u = bestList.get(0);
                claimedUniques.add(u.key());
                Set<Long> uSet = new HashSet<>(u.ids());
                String code = idSet.containsAll(uSet) ? "UNIQUE_COLUMNS_MISSING" : uSet.containsAll(idSet) ? "UNIQUE_COLUMNS_EXTRA" : "UNIQUE_COLUMNS_DIFFER";
                String reason = switch (code) {
                    case "UNIQUE_COLUMNS_MISSING" -> Messages.get("UNIQUE 에 보조 식별자의 일부 컬럼이 빠져 있습니다.");
                    case "UNIQUE_COLUMNS_EXTRA" -> Messages.get("UNIQUE 에 보조 식별자에 없는 컬럼이 더 있습니다.");
                    default -> Messages.get("UNIQUE 의 컬럼 구성이 보조 식별자와 다릅니다.");
                };
                return alternateKeyRow(rows, cols, CheckStatus.MISMATCH, code, u, List.of(), reason);
            }
            if (idSet.equals(pkByTable.getOrDefault(e.tableId(), Set.of()))) {
                return alternateKeyRow(rows, cols, CheckStatus.NOT_COMPARABLE, "ALTERNATE_KEY_IS_PRIMARY_KEY", null, List.of(),
                        Messages.get("보조 식별자의 컬럼이 테이블 PK 와 같아 UNIQUE 와 따로 비교하지 않습니다."));
            }
            return alternateKeyRow(rows, cols, CheckStatus.UNMAPPED, "UNIQUE_MISSING", null, List.of(),
                    Messages.get("보조 식별자에 대응하는 UNIQUE 제약조건이 없습니다 (UNIQUE 인덱스는 비교하지 않습니다, 자동으로 만들지 않습니다)."));
        }

        Row alternateKeyRow(List<IdentifierRow> rows, List<Col> cols, CheckStatus status, String code, Uq uq, List<Uq> candidates, String reason) {
            IdentifierRow first = rows.get(0);
            EntityRow e = entityById.get(first.entityId());
            List<StructureColumn> columns = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                IdentifierRow r = rows.get(i);
                Col expected = i < cols.size() ? cols.get(i) : null;
                Col physical = uq != null && i < uq.columns().size() ? uq.columns().get(i) : null;
                columns.add(new StructureColumn(i + 1, r.attributeId(), r.attributeName(), null, null, expected == null ? null : expected.id(), expected == null ? null : expected.name(),
                        null, null, physical == null ? null : physical.id(), physical == null ? null : physical.name(), null, null));
            }
            if (uq != null) {
                for (int i = rows.size(); i < uq.columns().size(); i++) {
                    Col physical = uq.columns().get(i);
                    columns.add(new StructureColumn(i + 1, null, null, null, null, null, null, null, null, physical.id(), physical.name(), null, null));
                }
            }
            String logical = first.identifierName() + " (" + rows.stream().map(IdentifierRow::attributeName).collect(Collectors.joining(", ")) + ")";
            Structure structure = new Structure(CheckType.ALTERNATE_KEY, code, first.identifierId(), first.identifierName(), null, null, null, null,
                    uq == null ? null : uq.physicalId(), uq == null ? null : uq.name(), columns, candidates.stream().map(Uq::text).toList());
            return row(e, "A" + first.identifierId(), CheckType.ALTERNATE_KEY, status, logical,
                    uq == null ? (candidates.isEmpty() ? "-" : String.join(" / ", candidates.stream().map(Uq::text).toList())) : uq.text(), reason,
                    inheritance(first.entityId()), structure);
        }

        // ------------------------------------------------------------------ 물리 기준 역방향

        List<Row> physicalForeignKeys() {
            List<Row> rows = new ArrayList<>();
            for (Fk fk : foreignKeys.values()) {
                if (claimedForeignKeys.contains(fk.id())) continue;
                EntityRow child = entityByTable.get(fk.childTableId());
                EntityRow parent = entityByTable.get(fk.parentTableId());
                if (child == null) continue;
                // 상속 FK (서브타입 테이블 → 직계 슈퍼타입 테이블) : [상속 구조] 진단이 본다
                if (parent != null && Objects.equals(in.supertypeOf().apply(child.entityId()), parent.entityId())) continue;
                List<StructureColumn> columns = new ArrayList<>();
                for (int i = 0; i < fk.columns().size(); i++) {
                    FkCol f = fk.columns().get(i);
                    columns.add(new StructureColumn(i + 1, null, attributeOfColumn.get(f.childId()), null, attributeOfColumn.get(f.parentId()), null, null, null, null,
                            f.childId(), f.childName(), f.parentId(), f.parentName()));
                }
                CheckStatus status = parent == null ? CheckStatus.NOT_COMPARABLE : CheckStatus.UNMAPPED;
                String code = parent == null ? "FK_PARENT_UNMAPPED" : "PHYSICAL_ONLY_FK";
                String reason = parent == null ? Messages.get("참조받는 테이블 {0} 이(가) 이 논리 모델의 엔터티에 매핑되지 않아 대응 논리 관계를 찾을 수 없습니다.", fk.parentTableName())
                        : Messages.get("이 FK 에 대응하는 논리 관계({0} → {1})가 없습니다. 물리에만 있는 구조입니다 (오류로 보지 않으며 자동으로 지우지 않습니다).", parent.entityName(), child.entityName());
                Structure structure = new Structure(CheckType.PHYSICAL_FK, code, null, null, parent == null ? null : parent.entityId(), parent == null ? null : parent.entityName(),
                        fk.parentTableId(), fk.parentTableName(), fk.id(), fk.name(), columns, List.of());
                rows.add(row(child, "F" + fk.id(), CheckType.PHYSICAL_FK, status, "-", fk.text(), reason,
                        inheritance(child.entityId()) || (parent != null && inheritance(parent.entityId())), structure));
            }
            return rows;
        }

        List<Row> physicalUniques() {
            List<Row> rows = new ArrayList<>();
            for (Uq uq : uniques.values()) {
                if (claimedUniques.contains(uq.key())) continue;
                EntityRow e = entityByTable.get(uq.tableId());
                if (e == null) continue;
                boolean mapped = uq.columns().stream().allMatch(col -> attributeOfColumn.containsKey(col.id()));
                List<StructureColumn> columns = new ArrayList<>();
                for (int i = 0; i < uq.columns().size(); i++) {
                    Col col = uq.columns().get(i);
                    columns.add(new StructureColumn(i + 1, null, attributeOfColumn.get(col.id()), null, null, null, null, null, null, col.id(), col.name(), null, null));
                }
                CheckStatus status = mapped ? CheckStatus.UNMAPPED : CheckStatus.NOT_COMPARABLE;
                String code = mapped ? "PHYSICAL_ONLY_UNIQUE" : "UNIQUE_COLUMN_UNMAPPED";
                String reason = mapped ? Messages.get("이 UNIQUE 에 대응하는 논리 보조 식별자가 없습니다. 물리에만 있는 구조입니다 (오류로 보지 않으며 자동으로 지우지 않습니다).")
                        : Messages.get("UNIQUE 의 일부 컬럼이 논리 속성에 매핑되지 않아 대응 보조 식별자를 찾을 수 없습니다.");
                Structure structure = new Structure(CheckType.PHYSICAL_UNIQUE, code, null, null, null, null, null, null, uq.physicalId(), uq.name(), columns, List.of());
                rows.add(row(e, "U" + uq.key(), CheckType.PHYSICAL_UNIQUE, status, "-", uq.text(), reason, inheritance(e.entityId()), structure));
            }
            return rows;
        }

        Row row(EntityRow e, String key, CheckType type, CheckStatus status, String logicalValue, String physicalValue, String reason, boolean inheritance, Structure structure) {
            return new Row(type + ":" + (e == null ? "" : e.entityId()) + ":" + key, in.modelId(), in.modelName(), e == null ? null : e.mappingId(), e == null ? null : e.entityId(),
                    e == null ? null : e.entityName(), null, null, e == null ? null : e.schemaId(), e == null ? null : e.schemaName(), e == null ? null : e.tableId(),
                    e == null ? null : e.tableName(), null, null, type, status, logicalValue, physicalValue, reason, inheritance, structure);
        }
    }
}
