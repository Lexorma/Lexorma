package com.lexorma.data.erd.service;

import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.service.PhysicalCardinality.ColumnFlags;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 물리 관계 카디널리티·식별 관계 판단 (M-2 개선 2차 D-08~D-10) : 순수 함수라 DB 없이 규칙표를 그대로 확인한다.
 * 부모 P(PK P1, P2 · 단일 UNIQUE PU · UNIQUE 제약 (PA, PB)), 자식 C 의 컬럼 A·B·C·D 로 FK 를 만든다
 */
class PhysicalCardinalityTest {

    private static final long PARENT = 1, CHILD = 2;
    // 부모 컬럼
    private static final long P1 = 11, P2 = 12, PU = 13, PA = 14, PB = 15;
    // 자식 컬럼
    private static final long A = 21, B = 22, C = 23, D = 24;

    private final Map<Long, ColumnFlags> columns = new LinkedHashMap<>();
    private final Map<Long, List<Set<Long>>> uniqueKeys = new HashMap<>();

    PhysicalCardinalityTest() {
        parent(P1, true, false, null);
        parent(P2, true, false, null);
        parent(PU, false, true, true);
        parent(PA, false, false, true);
        parent(PB, false, false, true);
        uniqueKeys.put(PARENT, new ArrayList<>(List.of(Set.of(PA, PB))));
        child(A, false, false, false);
        child(B, false, false, false);
        child(C, false, false, false);
        child(D, false, false, false);
    }

    private void parent(long id, boolean pk, boolean unique, Boolean nullable) {
        columns.put(id, new ColumnFlags(id, PARENT, nullable, pk, unique, false));
    }

    private void child(long id, boolean pk, boolean unique, Boolean nullable) {
        columns.put(id, new ColumnFlags(id, CHILD, nullable, pk, unique, false));
    }

    private void set(long id, Boolean nullable, Boolean pk, Boolean unique, Boolean required) {
        ColumnFlags old = columns.get(id);
        columns.put(id, new ColumnFlags(id, old.tableId(), nullable, pk, unique, required));
    }

    /** pairs : 자식 컬럼, 부모 컬럼, 자식 컬럼, 부모 컬럼 ... */
    private ErdDto.Cardinality judge(String type, long... pairs) {
        List<PhysicalModelDto.ColumnPair> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) list.add(new PhysicalModelDto.ColumnPair(pairs[i], null, pairs[i + 1], null));
        var relation = new PhysicalModelDto.Relation(1L, "FK_T", type, "USER", CHILD, "C", PARENT, "P", list, false);
        return PhysicalCardinality.judge(relation, PhysicalCardinality.Context.of(columns.values(), uniqueKeys));
    }

    private ErdDto.Cardinality fk(long... pairs) {
        return judge("FK", pairs);
    }

    @Test
    @DisplayName("부모 최대 : PK 전체·단일 UNIQUE·UNIQUE 제약(순서 무관)을 가리키면 1, 키가 아니면(무효 FK) 모두 판단하지 않는다")
    void parentMax() {
        assertThat(fk(A, P1, B, P2).parentMax()).isEqualTo("1");
        assertThat(fk(A, P2, B, P1).parentMax()).as("PK 순서와 달라도 집합이 같으면 키").isEqualTo("1");
        assertThat(fk(A, PU).parentMax()).isEqualTo("1");
        assertThat(fk(A, PB, B, PA).parentMax()).isEqualTo("1");

        ErdDto.Cardinality partOfPk = fk(A, P1);
        assertThat(partOfPk.parentMax()).isNull();
        assertThat(partOfPk.parentMin()).isNull();
        assertThat(partOfPk.childMax()).isNull();
        assertThat(partOfPk.reasons()).containsExactly(PhysicalCardinality.INVALID_REFERENCE);
        assertThat(partOfPk.identifying()).as("식별 여부는 자식 쪽 구성이라 무효 FK 에도 준다").isEqualTo("NON_IDENTIFYING");
        assertThat(fk(A, PA).reasons()).as("UNIQUE 제약의 일부").containsExactly(PhysicalCardinality.INVALID_REFERENCE);
    }

    @Test
    @DisplayName("부모 최소 : 모두 NOT NULL(PK·필수·NULL 불허) → 1, 모두 NULL 허용 → 0, 섞임 → 판단 불가(MATCH 미저장), NULL 허용 미입력 → 판단 불가")
    void parentMin() {
        assertThat(fk(A, PU).parentMin()).isEqualTo("1");
        set(A, true, false, false, false);
        assertThat(fk(A, PU).parentMin()).as("단일 FK NULL 허용").isEqualTo("0");
        set(B, true, false, false, false);
        assertThat(fk(A, P1, B, P2).parentMin()).as("복합 FK 모두 NULL 허용").isEqualTo("0");

        set(B, false, false, false, false);
        ErdDto.Cardinality mixed = fk(A, P1, B, P2);
        assertThat(mixed.parentMin()).isNull();
        assertThat(mixed.parentMax()).as("다른 끝은 따로 판단한다").isEqualTo("1");
        assertThat(mixed.reasons()).containsExactly(PhysicalCardinality.NULLABLE_MIXED);

        set(A, null, false, false, false);
        ErdDto.Cardinality unknown = fk(A, PU);
        assertThat(unknown.parentMin()).isNull();
        assertThat(unknown.reasons()).containsExactly(PhysicalCardinality.NULLABLE_UNKNOWN);
        // NULL 허용이 비어 있어도 PK·필수 컬럼은 DDL 에서 NOT NULL 이다
        set(A, null, false, false, true);
        assertThat(fk(A, PU).parentMin()).isEqualTo("1");
        set(A, true, true, false, false);
        assertThat(fk(A, PU).parentMin()).as("PK 는 NULL 허용 값과 무관하게 NOT NULL").isEqualTo("1");
    }

    @Test
    @DisplayName("자식 최대 : 유일성 키 U 가 FK 컬럼 F 의 부분집합이면 1 — 표의 6가지 + PK·단일 UNIQUE, 일반 인덱스는 근거가 아니다")
    void childMax() {
        // FK (A) · U = (A)
        uniqueKeys.put(CHILD, new ArrayList<>(List.of(Set.of(A))));
        assertThat(fk(A, PU).childMax()).isEqualTo("1");
        // FK (A,B) · U = (A,B) / (B,A) : 집합이라 순서 무관
        uniqueKeys.put(CHILD, new ArrayList<>(List.of(Set.of(B, A))));
        assertThat(fk(A, P1, B, P2).childMax()).isEqualTo("1");
        // FK (A,B) · U = (A) : 부분집합
        uniqueKeys.put(CHILD, new ArrayList<>(List.of(Set.of(A))));
        assertThat(fk(A, P1, B, P2).childMax()).isEqualTo("1");
        // FK (A,B) · U = (A,B,C) : FK 보다 큰 키는 보장하지 않는다
        uniqueKeys.put(CHILD, new ArrayList<>(List.of(Set.of(A, B, C))));
        assertThat(fk(A, P1, B, P2).childMax()).isEqualTo("N");
        // 일반 인덱스·조건부·표현식 고유 인덱스는 저장소가 키로 넘기지 않는다 → 키 없음
        uniqueKeys.remove(CHILD);
        assertThat(fk(A, P1, B, P2).childMax()).isEqualTo("N");

        set(C, false, false, true, false);
        assertThat(fk(C, PU).childMax()).as("단일 UNIQUE 컬럼").isEqualTo("1");
        set(A, false, true, false, false);
        assertThat(fk(A, PU).childMax()).as("FK 가 자식 PK 전체 (1:1)").isEqualTo("1");
        set(B, false, true, false, false);
        assertThat(fk(A, PU).childMax()).as("FK 가 복합 PK 의 일부").isEqualTo("N");
        assertThat(fk(A, P1, B, P2).childMax()).isEqualTo("1");
    }

    @Test
    @DisplayName("식별 관계 : FK 전체가 자식 PK → IDENTIFYING, 일부 → PARTIAL, 별도 → NON_IDENTIFYING (PK 없음 포함), 복합 PK·FK")
    void identifying() {
        assertThat(fk(A, P1, B, P2).identifying()).as("자식 PK 없음").isEqualTo("NON_IDENTIFYING");
        set(A, false, true, false, false);
        set(B, false, true, false, false);
        set(C, false, true, false, false);
        assertThat(fk(A, P1, B, P2).identifying()).as("복합 PK (A,B,C) 안의 복합 FK").isEqualTo("IDENTIFYING");
        assertThat(fk(A, PU).identifying()).isEqualTo("IDENTIFYING");
        assertThat(fk(C, P1, D, P2).identifying()).isEqualTo("PARTIAL");
        assertThat(fk(D, PU).identifying()).isEqualTo("NON_IDENTIFYING");
    }

    @Test
    @DisplayName("LOGICAL 관계와 컬럼 쌍이 없는 관계는 카디널리티·식별 관계를 판단하지 않는다 (미확정 값을 0·N 으로 바꾸지 않는다)")
    void undetermined() {
        ErdDto.Cardinality logical = judge("LOGICAL", A, PU);
        assertThat(logical).isEqualTo(new ErdDto.Cardinality(null, null, null, null, List.of(PhysicalCardinality.LOGICAL_RELATION)));
        ErdDto.Cardinality empty = judge("FK");
        assertThat(empty.reasons()).containsExactly(PhysicalCardinality.COLUMNS_MISSING);
        assertThat(empty.parentMax()).isNull();
    }

    @Test
    @DisplayName("자기 참조 : 같은 테이블 안에서 같은 규칙 (부모 = 자식 테이블의 PK)")
    void selfReference() {
        columns.put(31L, new ColumnFlags(31L, 3L, false, true, false, false));
        columns.put(32L, new ColumnFlags(32L, 3L, true, false, false, false));
        var relation = new PhysicalModelDto.Relation(2L, "FK_SELF", "FK", "USER", 3L, "T", 3L, "T", List.of(new PhysicalModelDto.ColumnPair(32L, null, 31L, null)), false);
        ErdDto.Cardinality self = PhysicalCardinality.judge(relation, PhysicalCardinality.Context.of(columns.values(), uniqueKeys));
        assertThat(self).isEqualTo(new ErdDto.Cardinality("0", "1", "N", "NON_IDENTIFYING", List.of()));
    }
}
