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
 * 비어 있는 PK_YN·UNQ_YN (M-7 2차 M7-D38) : 화면·엑셀·원본 가져오기·생성은 늘 참/거짓을 넣으므로 비어 있으면 "기록되지 않은 값" 이다 (예전 데이터·직접 SQL·필드가 없는 교환 파일).
 * 자식 쪽은 근거가 확정된 것만 쓴다 — 최대 1 은 확정된 유일성(PK 플래그가 모두 기록된 테이블의 PK · 참인 UNQ_YN · UNIQUE 제약 · 고유 인덱스)으로만, N 은 자식 테이블 PK 플래그와
 * FK 컬럼의 UNQ_YN 이 모두 기록됐을 때만. 그 밖에는 판단하지 않는다 ({@link #KEY_METADATA_UNKNOWN}). 식별 여부도 FK 컬럼의 PK_YN 이 비어 있으면 판단하지 않는다.
 * 부모 쪽 참조 키 판단은 DDL·모델 구조 진단과 같게 비어 있으면 아닌 것으로 본다 (무효 FK 판정을 화면마다 다르게 하지 않는다).
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
    /** 자식 PK_YN·UNQ_YN 이 비어 있어 자식 쪽 유일성·식별 여부를 확정할 수 없다 (M-7 2차 M7-D38) */
    public static final String KEY_METADATA_UNKNOWN = "KEY_METADATA_UNKNOWN";

    private PhysicalCardinality() {
    }

    /**
     * 판단 근거 : 관계 양쪽 테이블의 모든 컬럼(컬럼 ID → 값, 테이블별 PK 는 한 번만 모은다)과
     * 테이블 ID → UNIQUE 제약·고유 인덱스의 컬럼 ID 집합 (조건부·표현식 인덱스는 넣지 않는다)
     */
    public record Context(Map<Long, ColumnFlags> columns, Map<Long, Set<Long>> primaryKeys, Map<Long, List<Set<Long>>> uniqueKeys, Set<Long> unknownPkTables) {
        public static Context of(Collection<ColumnFlags> columns, Map<Long, List<Set<Long>>> uniqueKeys) {
            Map<Long, ColumnFlags> byId = new HashMap<>();
            Map<Long, Set<Long>> pks = new HashMap<>();
            Set<Long> unknownPk = new HashSet<>();
            columns.forEach(c -> {
                byId.put(c.id(), c);
                if (Boolean.TRUE.equals(c.isPk())) pks.computeIfAbsent(c.tableId(), k -> new HashSet<>()).add(c.id());
                if (c.isPk() == null) unknownPk.add(c.tableId());
            });
            return new Context(byId, pks, uniqueKeys, unknownPk);
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
        boolean fkPkUnknown = fk.stream().anyMatch(id -> columns.get(id) == null || columns.get(id).isPk() == null);
        String identifying = fkPkUnknown ? null : childPk.containsAll(fk) && !childPk.isEmpty() ? "IDENTIFYING"
                : fk.stream().anyMatch(childPk::contains) ? "PARTIAL" : "NON_IDENTIFYING";

        if ("LOGICAL".equals(relation.relationType())) {
            return new ErdDto.Cardinality(null, null, null, null, List.of(LOGICAL_RELATION));
        }
        if (!referencesKey(referenced, context, relation.targetTableId())) {
            return new ErdDto.Cardinality(null, null, null, identifying, List.of(INVALID_REFERENCE));
        }

        List<String> reasons = new ArrayList<>();
        String parentMin = parentMin(fk, columns, reasons);
        String childMax = childMax(fk, context, relation.sourceTableId(), childPk);
        if (childMax == null || identifying == null) reasons.add(KEY_METADATA_UNKNOWN);
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
    /** 확정된 유일성이 있으면 "1", 근거가 모두 기록됐는데 없으면 "N", 기록되지 않은 값 때문에 정할 수 없으면 null */
    private static String childMax(Set<Long> fk, Context context, Long tableId, Set<Long> pk) {
        boolean pkKnown = !context.unknownPkTables().contains(tableId);
        if (unique(fk, context, tableId, pkKnown ? pk : Set.of())) return "1";
        boolean uniqueKnown = fk.stream().allMatch(id -> context.columns().get(id) != null && context.columns().get(id).isUnique() != null);
        return pkKnown && uniqueKnown ? "N" : null;
    }

    private static boolean unique(Set<Long> fk, Context context, Long tableId, Set<Long> pk) {
        if (!pk.isEmpty() && fk.containsAll(pk)) return true;
        for (Long id : fk) {
            ColumnFlags c = context.columns().get(id);
            if (c != null && Boolean.TRUE.equals(c.isUnique())) return true;
        }
        return context.uniqueKeys().getOrDefault(tableId, List.of()).stream().anyMatch(key -> !key.isEmpty() && fk.containsAll(key));
    }
}
