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
 * 보조 식별자의 일부만·여러 식별자의 혼합은 PARENT_KEY_MISMATCH 다 (저장 경로는 거절한다). 상속 구조에 참여하는 부모의 보조 식별자는 부르는 쪽이 후보로 넘기지 않는다 (M7-AK-D16 보류).
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
     * @param alternates  부모 엔터티 자체의 보조 식별자 (참조 가능한 것만 — 상속 참여 부모면 빈 목록)
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

    /** 속성 집합이 같은 보조 식별자 중 대표 (식별자 ID 가 가장 작은 것). 없으면 null */
    public static Key representative(List<Key> alternates, Set<Long> attributeSet) {
        if (alternates == null) return null;
        return alternates.stream().filter(k -> !k.primary() && !k.attributeIds().isEmpty() && new HashSet<>(k.attributeIds()).equals(attributeSet))
                .min(Comparator.comparing(Key::identifierId, Comparator.nullsLast(Comparator.naturalOrder()))).orElse(null);
    }
}
