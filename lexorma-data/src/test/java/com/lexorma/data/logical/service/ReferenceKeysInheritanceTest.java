package com.lexorma.data.logical.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** M7-INH-01 참조 키 후보 정책 (D-INH-01~05) : 부모 자체 보조 식별자만 (상속 참여 무관), 조상의 것은 상속받은 AK 로 따로 */
class ReferenceKeysInheritanceTest {

    private final Map<Long, List<ReferenceKeys.Key>> own = Map.of(
            1L, List.of(new ReferenceKeys.Key(12L, "AK_B", false, List.of(102L)), new ReferenceKeys.Key(11L, "AK_A", false, List.of(101L))),
            2L, List.of(new ReferenceKeys.Key(21L, "AK_C", false, List.of(201L))));

    @Test
    @DisplayName("referenceable = 그 엔터티 자체의 보조 식별자 (식별자 ID 순), inherited = 조상(직계 → 루트)의 것. 서브타입(2) 후보에 루트(1) AK 는 없다")
    void candidates() {
        assertThat(ReferenceKeys.referenceable(1L, id -> own.getOrDefault(id, List.of()))).extracting(ReferenceKeys.Key::identifierName).containsExactly("AK_A", "AK_B");
        assertThat(ReferenceKeys.referenceable(2L, id -> own.getOrDefault(id, List.of()))).extracting(ReferenceKeys.Key::identifierName).containsExactly("AK_C");
        assertThat(ReferenceKeys.referenceable(3L, id -> own.getOrDefault(id, List.of()))).isEmpty();
        assertThat(ReferenceKeys.referenceable(null, id -> own.getOrDefault(id, List.of()))).isEmpty();
        assertThat(ReferenceKeys.inherited(List.of(1L), id -> own.getOrDefault(id, List.of()))).extracting(ReferenceKeys.Key::identifierName).containsExactly("AK_A", "AK_B");
        // 서브타입 2 가 부모 : 상속받은 AK_A 속성(101)은 해석되지 않는다 (PARENT_KEY_MISMATCH), 자체 AK_C 는 ALTERNATE
        List<ReferenceKeys.Key> candidates = ReferenceKeys.referenceable(2L, id -> own.getOrDefault(id, List.of()));
        assertThat(ReferenceKeys.resolve(List.of(101L), 1L, "PK", List.of(100L), candidates).status()).isEqualTo("PARENT_KEY_MISMATCH");
        assertThat(ReferenceKeys.resolve(List.of(201L), 1L, "PK", List.of(100L), candidates).type()).isEqualTo(ReferenceKeys.KeyType.ALTERNATE);
        assertThat(ReferenceKeys.resolve(List.of(100L), 1L, "PK", List.of(100L), candidates).type()).isEqualTo(ReferenceKeys.KeyType.PRIMARY);
    }
}
