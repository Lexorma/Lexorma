package com.lexorma.data.mapping.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M7-INH-03 부모 FK 상세 코드 (SubtypePlanContext.foreignKeyDetail — 순수 판정). 자식 상속 PK (1, 2) → 부모 키 (5, 6), 부모 테이블 10 */
class SubtypeForeignKeyDetailTest {

    private static SubtypePlanContext.Fk fk(long id, Long target, List<Long> child, List<Long> parent) {
        return new SubtypePlanContext.Fk(id, "FK" + id, "USER", target, "T" + target, new ArrayList<>(child), new ArrayList<>(parent), new ArrayList<>(), new ArrayList<>());
    }

    private static String detail(SubtypePlanContext.Fk... fks) {
        return SubtypePlanContext.foreignKeyDetail(List.of(fks), 10L, List.of(1L, 2L), List.of(5L, 6L));
    }

    @Test
    @DisplayName("정상 1개 OK · 2개 DUPLICATE · 없음 MISSING · 같은 쌍 다른 순서 ORDER · 부모 컬럼 다름 COLUMNS · 다른 테이블 TARGET · 상속 PK 가 아닌 컬럼의 일반 FK 는 보지 않는다")
    void details() {
        assertThat(detail(fk(1, 10L, List.of(1L, 2L), List.of(5L, 6L)))).isEqualTo(SubtypePlanContext.FK_OK);
        assertThat(detail(fk(1, 10L, List.of(1L, 2L), List.of(5L, 6L)), fk(2, 10L, List.of(1L, 2L), List.of(5L, 6L)))).isEqualTo(SubtypePlanContext.FK_DUPLICATE);
        assertThat(detail()).isEqualTo(SubtypePlanContext.FK_MISSING);
        assertThat(detail(fk(1, 10L, List.of(2L, 1L), List.of(6L, 5L)))).isEqualTo(SubtypePlanContext.FK_ORDER);
        assertThat(detail(fk(1, 10L, List.of(1L, 2L), List.of(6L, 5L)))).isEqualTo(SubtypePlanContext.FK_COLUMNS);
        assertThat(detail(fk(1, 11L, List.of(1L, 2L), List.of(7L, 8L)))).isEqualTo(SubtypePlanContext.FK_TARGET);
        // 일반 FK (상속 PK 가 아닌 컬럼 3, 또는 상속 PK 일부만) 는 상속 FK 를 대신하지도 막지도 않는다
        assertThat(detail(fk(1, 10L, List.of(3L), List.of(5L)))).isEqualTo(SubtypePlanContext.FK_MISSING);
        assertThat(detail(fk(1, 10L, List.of(1L), List.of(5L)))).isEqualTo(SubtypePlanContext.FK_MISSING);
        assertThat(detail(fk(1, 10L, List.of(3L), List.of(5L)), fk(2, 10L, List.of(1L, 2L), List.of(5L, 6L)))).isEqualTo(SubtypePlanContext.FK_OK);
    }
}
