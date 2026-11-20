package com.lexorma.data.erd.service;

import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;

import java.util.*;

/**
 * 물리 관계의 카디널리티·식별 관계 판단 (M-2 개선 2차 D-08~D-10, docs/completed/physical-erd-m2-improvement.md 21장).
 * 저장된 물리 제약조건(PK·UNIQUE·조건 없는 컬럼 UNIQUE 인덱스·NULL 허용·필수)만 근거로 조회 때 계산하는 순수 함수 — 논리 모델의 참여 수를 쓰지 않고 결과를 저장하지 않는다.
 * <ul>
 *   <li>부모 최대 : FK 가 부모의 키(PK 와 같은 컬럼 집합 · 단일 UNIQUE 컬럼 · UNIQUE 제약 · 고유 인덱스)를 가리키면 1 — DDL 이 FK 를 만드는 조건과 같다 (TableDdlGenerator.referencesKey).
 *       키가 아니면(무효 FK) 카디널리티를 모두 판단하지 않는다</li>
 *   <li>부모 최소 : FK 컬럼이 모두 NOT NULL 이면 1, 모두 NULL 허용이면 0. NOT NULL 판단은 DDL 과 같다 (PK · 필수 · NULL 불허 — TableDdlGenerator.isNotNull).
 *       NULL 허용을 입력하지 않은 컬럼이 있으면, 또는 복합 FK 에 NOT NULL 과 NULL 허용이 섞여 있으면(MATCH 방식을 저장하지 않는다) 판단하지 않는다</li>
 *   <li>자식 최대 : 자식의 유일성 키(PK · 단일 UNIQUE · UNIQUE 제약 · 조건 없고 표현식 없는 고유 인덱스) 중 하나가 FK 컬럼 집합의 부분집합이면 1, 없으면 N (순서 무관).
 *       N 은 기록된 제약조건이 여러 건을 막지 않는다는 뜻이다</li>
 *   <li>식별 : FK 컬럼이 모두 자식 PK 이면 IDENTIFYING, 일부면 PARTIAL, 아니면(PK 없음 포함) NON_IDENTIFYING</li>
 *   <li>LOGICAL 관계(DB FK 아님)·컬럼 쌍이 없는 관계는 판단하지 않는다</li>
 * </ul>
 * PK_YN·UNQ_YN 이 비어 있으면 PK·UNIQUE 가 아닌 것으로 본다 (DDL·모델 구조 진단과 같다).
 */
public final class PhysicalCardinality {

    /** 판단에 쓰는 컬럼 값 (ErdDto.Column 또는 별도 조회에서) */
    public record ColumnFlags(Long id, Long tableId, Boolean isNullable, Boolean isPk, Boolean isUnique, Boolean isRequired) {
        boolean notNull() {
            return Boolean.TRUE.equals(isPk) || Boolean.TRUE.equals(isRequired) || Boolean.FALSE.equals(isNullable);
        }
    }

    public static final String LOGICAL_RELATION = "LOGICAL_RELATION";
    public static final String INVALID_REFERENCE = "INVALID_REFERENCE";
    public static final String COLUMNS_MISSING = "COLUMNS_MISSING";
    public static final String NULLABLE_UNKNOWN = "NULLABLE_UNKNOWN";
    public static final String NULLABLE_MIXED = "NULLABLE_MIXED";

    private PhysicalCardinality() {
    }

    /**
     * 판단 근거 : 관계 양쪽 테이블의 모든 컬럼(컬럼 ID → 값, 테이블별 PK 는 한 번만 모은다)과
     * 테이블 ID → UNIQUE 제약·고유 인덱스의 컬럼 ID 집합 (조건부·표현식 인덱스는 넣지 않는다)
     */
    public record Context(Map<Long, ColumnFlags> columns, Map<Long, Set<Long>> primaryKeys, Map<Long, List<Set<Long>>> uniqueKeys) {
        public static Context of(Collection<ColumnFlags> columns, Map<Long, List<Set<Long>>> uniqueKeys) {
            Map<Long, ColumnFlags> byId = new HashMap<>();
            Map<Long, Set<Long>> pks = new HashMap<>();
            columns.forEach(c -> {
                byId.put(c.id(), c);
                if (Boolean.TRUE.equals(c.isPk())) pks.computeIfAbsent(c.tableId(), k -> new HashSet<>()).add(c.id());
            });
            return new Context(byId, pks, uniqueKeys);
        }

        Set<Long> primaryKey(Long tableId) {
            return primaryKeys.getOrDefault(tableId, Set.of());
        }
    }

    /** 관계 ID 순서를 유지한 관계별 판단 */
    public static Map<Long, ErdDto.Cardinality> judgeAll(Collection<PhysicalModelDto.Relation> relations, Context context) {
        Map<Long, ErdDto.Cardinality> result = new LinkedHashMap<>();
        relations.forEach(r -> result.put(r.id(), judge(r, context)));
        return result;
    }

    public static ErdDto.Cardinality judge(PhysicalModelDto.Relation relation, Context context) {
        Map<Long, ColumnFlags> columns = context.columns();
        List<PhysicalModelDto.ColumnPair> pairs = relation.columns() == null ? List.of() : relation.columns();
        if (pairs.isEmpty()) {
            return new ErdDto.Cardinality(null, null, null, null, List.of(COLUMNS_MISSING));
        }
        Set<Long> fk = new LinkedHashSet<>();
        Set<Long> referenced = new LinkedHashSet<>();
        pairs.forEach(p -> {
            fk.add(p.sourceColumnId());
            referenced.add(p.targetColumnId());
        });
        Set<Long> childPk = context.primaryKey(relation.sourceTableId());
        String identifying = childPk.containsAll(fk) && !childPk.isEmpty() ? "IDENTIFYING"
                : fk.stream().anyMatch(childPk::contains) ? "PARTIAL" : "NON_IDENTIFYING";

        if ("LOGICAL".equals(relation.relationType())) {
            return new ErdDto.Cardinality(null, null, null, null, List.of(LOGICAL_RELATION));
        }
        if (!referencesKey(referenced, context, relation.targetTableId())) {
            return new ErdDto.Cardinality(null, null, null, identifying, List.of(INVALID_REFERENCE));
        }

        List<String> reasons = new ArrayList<>();
        String parentMin = parentMin(fk, columns, reasons);
        String childMax = unique(fk, context, relation.sourceTableId(), childPk) ? "1" : "N";
        return new ErdDto.Cardinality(parentMin, "1", childMax, identifying, List.copyOf(reasons));
    }

    private static String parentMin(Set<Long> fk, Map<Long, ColumnFlags> columns, List<String> reasons) {
        int notNull = 0, nullable = 0;
        for (Long id : fk) {
            ColumnFlags c = columns.get(id);
            if (c != null && c.notNull()) {
                notNull++;
            } else if (c != null && Boolean.TRUE.equals(c.isNullable())) {
                nullable++;
            } else {
                reasons.add(NULLABLE_UNKNOWN);
                return null;
            }
        }
        if (notNull > 0 && nullable > 0) {
            reasons.add(NULLABLE_MIXED);
            return null;
        }
        return notNull > 0 ? "1" : "0";
    }

    /** 부모 쪽 : DDL 과 같은 조건 (PK 와 같은 집합 · 단일 UNIQUE 컬럼 · UNIQUE 제약/고유 인덱스와 같은 집합) */
    static boolean referencesKey(Set<Long> referenced, Context context, Long tableId) {
        Set<Long> pk = context.primaryKey(tableId);
        if (!pk.isEmpty() && referenced.equals(pk)) return true;
        if (referenced.size() == 1) {
            ColumnFlags only = context.columns().get(referenced.iterator().next());
            if (only != null && Boolean.TRUE.equals(only.isUnique())) return true;
        }
        return context.uniqueKeys().getOrDefault(tableId, List.of()).stream().anyMatch(referenced::equals);
    }

    /** 자식 쪽 : 유일성 키 하나라도 FK 컬럼 집합 안에 다 들어 있으면 같은 부모 값을 가진 자식은 1건뿐이다 */
    private static boolean unique(Set<Long> fk, Context context, Long tableId, Set<Long> pk) {
        if (!pk.isEmpty() && fk.containsAll(pk)) return true;
        for (Long id : fk) {
            ColumnFlags c = context.columns().get(id);
            if (c != null && Boolean.TRUE.equals(c.isUnique())) return true;
        }
        return context.uniqueKeys().getOrDefault(tableId, List.of()).stream().anyMatch(key -> !key.isEmpty() && fk.containsAll(key));
    }
}
