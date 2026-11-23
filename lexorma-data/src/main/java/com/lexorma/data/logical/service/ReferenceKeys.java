package com.lexorma.data.logical.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 논리 관계의 부모 참조 키 해석 (M7-AK-D09, docs/completed/alternate-key-unique-m7.md) — 순수 함수. 논리 짝 저장·판정({@link LogicalRelationshipPairs}),
 * 구조 정합성 판정(mapping/service/ModelStructureQualityJudge)·물리 생성·FK 보정이 같은 결과를 쓴다. 새 저장 구조 없이 짝의 부모 속성 집합으로 정한다.
 * <ol>
 *   <li>짝이 없으면 NONE</li>
 *   <li>부모 속성 집합 = 유효 주 식별자 집합 → PRIMARY COMPLETE (주 식별자와 같은 구성의 보조 식별자보다 먼저 — 기존 PK 참조 동작 유지)</li>
 *   <li>부모 속성 집합 = 부모 엔터티 자체의 보조 식별자 집합 → ALTERNATE COMPLETE. 같은 집합의 보조 식별자가 여럿이면 식별자 ID 가 가장 작은 것이 대표 (M7-AK-D05 —
 *       구조 정합성의 보조 식별자 진단도 식별자 ID 순으로 UNIQUE 를 대응시킨다)</li>
 *   <li>주 식별자가 없으면 PARENT_KEY_MISSING, 주 식별자의 일부면 PRIMARY PARTIAL (기존 계약 — 저장되고 FK 근거는 아니다), 그 밖은 PARENT_KEY_MISMATCH</li>
 * </ol>
 * 보조 식별자의 일부만·여러 식별자의 혼합은 PARENT_KEY_MISMATCH 다 (저장 경로는 거절한다).
 * <p>참조할 수 있는 보조 식별자 후보 (M7-INH-01, D-INH-01~05 — docs/completed/inheritance-reference-key-m7.md 3장) : 부모 엔터티가 <b>직접 정의한</b> 보조 식별자 전부다.
 * 상속 구조 참여 여부와 무관하다 (슈퍼타입·중간·최하위 서브타입 자체 AK 모두 허용 — 예전 M7-AK-D16 보류를 대체). 조상이 정의한 보조 식별자(상속받은 AK)는
 * 지금 물리 전략(B형 TABLE_PER_TYPE — 서브타입 테이블에 조상 AK 컬럼이 없다)에서 참조할 수 없다 : {@link #referenceable} 이 넣지 않고, 저장 경로는
 * {@link #inherited} 로 찾아 안내한다. 표시용 상속 식별자(effectiveIdentifiers)와 참조 후보는 다르다. A형·C형 물리 전략에서는 이 후보 정책을 그대로 쓸 수 없을 수 있다 (별도 검토).
 * 논리 해석은 물리 모델을 보지 않는다 — 실제 PK·UNIQUE 가 있는지는 물리 생성·FK 보정·구조 정합성이 따로 판정한다 (D-INH-02).</p>
 */
public final class ReferenceKeys {

    private ReferenceKeys() {
    }

    public enum KeyType { PRIMARY, ALTERNATE }

    /** 부모 식별자 후보 : attributeIds = 식별자 순서 */
    public record Key(Long identifierId, String identifierName, boolean primary, List<Long> attributeIds) {
    }

    /** status : NONE · COMPLETE · PARTIAL · PARENT_KEY_MISSING · PARENT_KEY_MISMATCH. key : 해석된 식별자 (COMPLETE·PARTIAL 일 때만) */
    public record Resolution(String status, KeyType type, Key key) {
        public boolean complete() {
            return "COMPLETE".equals(status);
        }

        public boolean alternate() {
            return type == KeyType.ALTERNATE;
        }

        /** 판정 기준이 된 키의 속성 수 (없으면 주 식별자 크기) */
        public int keySize(List<Long> primaryKey) {
            return key != null ? key.attributeIds().size() : primaryKey.size();
        }
    }

    /**
     * @param parents     짝의 부모 속성 ID (짝 순서)
     * @param primaryKey  부모의 유효 주 식별자 속성 ID (서브타입이면 루트의 것, 식별자 순서)
     * @param alternates  참조할 수 있는 보조 식별자 ({@link #referenceable} — 부모 엔터티 자체의 것)
     */
    public static Resolution resolve(List<Long> parents, Long primaryIdentifierId, String primaryIdentifierName, List<Long> primaryKey, List<Key> alternates) {
        if (parents == null || parents.isEmpty()) return new Resolution("NONE", null, null);
        Set<Long> set = new HashSet<>(parents);
        if (set.size() != parents.size()) return new Resolution("PARENT_KEY_MISMATCH", null, null);
        Key primary = new Key(primaryIdentifierId, primaryIdentifierName, true, primaryKey == null ? List.of() : primaryKey);
        if (!primary.attributeIds().isEmpty() && set.equals(new HashSet<>(primary.attributeIds()))) return new Resolution("COMPLETE", KeyType.PRIMARY, primary);
        Key alternate = representative(alternates, set);
        if (alternate != null) return new Resolution("COMPLETE", KeyType.ALTERNATE, alternate);
        if (primary.attributeIds().isEmpty()) return new Resolution("PARENT_KEY_MISSING", null, null);
        if (primary.attributeIds().containsAll(set)) return new Resolution("PARTIAL", KeyType.PRIMARY, primary);
        return new Resolution("PARENT_KEY_MISMATCH", null, null);
    }

    /**
     * 부모 엔터티가 참조할 수 있는 보조 식별자 (D-INH-03·04·05) : 그 엔터티 자체의 보조 식별자 (식별자 ID 순). 모든 짝 판정·저장·교환·생성·구조 정합성 경로가 이 함수로 후보를 정한다.
     * @param ownAlternates 엔터티 ID → 그 엔터티가 직접 정의한 보조 식별자
     */
    public static List<Key> referenceable(Long parentEntityId, java.util.function.Function<Long, List<Key>> ownAlternates) {
        if (parentEntityId == null) return List.of();
        List<Key> own = ownAlternates.apply(parentEntityId);
        if (own == null || own.isEmpty()) return List.of();
        return own.stream().filter(k -> !k.primary()).sorted(Comparator.comparing(Key::identifierId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    /**
     * 상속받은 보조 식별자 (참조 불가 — D-INH-05) : 조상(직계 슈퍼타입 → 루트)이 정의한 보조 식별자. 저장 경로가 "상속받은 AK 는 참조할 수 없다" 안내에 쓴다.
     * @param ancestors 엔터티의 조상 ID (직계 부모 → 루트, {@link LogicalHierarchy#ancestors})
     */
    public static List<Key> inherited(List<Long> ancestors, java.util.function.Function<Long, List<Key>> ownAlternates) {
        List<Key> result = new java.util.ArrayList<>();
        for (Long ancestor : ancestors) result.addAll(referenceable(ancestor, ownAlternates));
        return result;
    }

    /** 속성 집합이 같은 보조 식별자 중 대표 (식별자 ID 가 가장 작은 것). 없으면 null */
    public static Key representative(List<Key> alternates, Set<Long> attributeSet) {
        if (alternates == null) return null;
        return alternates.stream().filter(k -> !k.primary() && !k.attributeIds().isEmpty() && new HashSet<>(k.attributeIds()).equals(attributeSet))
                .min(Comparator.comparing(Key::identifierId, Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
    }
}
