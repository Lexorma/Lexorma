package com.lexorma.data.logical.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** M7-AK-D09·D05 부모 참조 키 해석 (순수 함수) */
class ReferenceKeysTest {

    private static final List<Long> PK = List.of(1L, 2L);
    private static final ReferenceKeys.Key AK_B = new ReferenceKeys.Key(20L, "AK_B", false, List.of(3L, 4L));
    private static final ReferenceKeys.Key AK_A = new ReferenceKeys.Key(10L, "AK_A", false, List.of(4L, 3L));
    private static final ReferenceKeys.Key AK_PK = new ReferenceKeys.Key(5L, "AK_PK", false, List.of(2L, 1L));

    private static ReferenceKeys.Resolution resolve(List<Long> parents, List<ReferenceKeys.Key> alternates) {
        return ReferenceKeys.resolve(parents, 99L, "PK", PK, alternates);
    }

    @Test
    @DisplayName("짝 없음 = NONE, 같은 부모 속성 두 번 = MISMATCH")
    void noneAndDuplicates() {
        assertThat(resolve(List.of(), List.of(AK_A)).status()).isEqualTo("NONE");
        assertThat(resolve(List.of(3L, 3L), List.of(AK_A)).status()).isEqualTo("PARENT_KEY_MISMATCH");
    }

    @Test
    @DisplayName("주 식별자 집합이면 PRIMARY COMPLETE — 같은 구성의 보조 식별자보다 먼저 (순서 무관)")
    void primaryFirst() {
        ReferenceKeys.Resolution r = resolve(List.of(2L, 1L), List.of(AK_PK));
        assertThat(r.status()).isEqualTo("COMPLETE");
        assertThat(r.type()).isEqualTo(ReferenceKeys.KeyType.PRIMARY);
        assertThat(r.key().identifierId()).isEqualTo(99L);
        assertThat(r.keySize(PK)).isEqualTo(2);
    }

    @Test
    @DisplayName("보조 식별자 집합이면 ALTERNATE COMPLETE, 같은 집합이 여럿이면 식별자 ID 가 가장 작은 것 (D05) — 넘긴 순서와 무관")
    void alternateRepresentative() {
        ReferenceKeys.Resolution r = resolve(List.of(3L, 4L), List.of(AK_B, AK_A));
        assertThat(r.complete()).isTrue();
        assertThat(r.alternate()).isTrue();
        assertThat(r.key().identifierId()).isEqualTo(10L);
        assertThat(r.key().attributeIds()).containsExactly(4L, 3L);
        assertThat(ReferenceKeys.representative(List.of(AK_A, AK_B), Set.of(4L, 3L)).identifierId()).isEqualTo(10L);
        assertThat(ReferenceKeys.representative(List.of(AK_A), Set.of(3L))).isNull();
    }

    @Test
    @DisplayName("주 식별자 일부 = PRIMARY PARTIAL (기존 계약), 보조 식별자 일부·혼합 = MISMATCH, 주 식별자가 없으면 PARENT_KEY_MISSING (보조 식별자 전체는 그래도 COMPLETE)")
    void partialAndMismatch() {
        assertThat(resolve(List.of(1L), List.of(AK_A)).status()).isEqualTo("PARTIAL");
        assertThat(resolve(List.of(1L), List.of(AK_A)).type()).isEqualTo(ReferenceKeys.KeyType.PRIMARY);
        assertThat(resolve(List.of(3L), List.of(AK_A)).status()).isEqualTo("PARENT_KEY_MISMATCH");
        assertThat(resolve(List.of(1L, 3L), List.of(AK_A)).status()).isEqualTo("PARENT_KEY_MISMATCH");
        assertThat(ReferenceKeys.resolve(List.of(3L), null, null, List.of(), List.of(AK_A)).status()).isEqualTo("PARENT_KEY_MISSING");
        assertThat(ReferenceKeys.resolve(List.of(3L, 4L), null, null, List.of(), List.of(AK_A)).status()).isEqualTo("COMPLETE");
    }

    @Test
    @DisplayName("상속 참여 부모 (D16) : 부르는 쪽이 보조 식별자를 넘기지 않으면 보조 식별자 짝은 MISMATCH")
    void heldForInheritance() {
        assertThat(resolve(List.of(3L, 4L), List.of()).status()).isEqualTo("PARENT_KEY_MISMATCH");
    }
}
