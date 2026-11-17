package com.lexorma.source.collection.service;

import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 객체 급감 경고 (DEC-17) : 직전 정상 반영의 50% 미만일 때만, 테이블·컬럼 중 하나라도 */
class CollectionDropTest {

    @Test
    @DisplayName("테이블이나 컬럼이 직전의 절반 미만이면 급감 — 정확히 절반·증가·비교 불가(직전 없음, 수 없음)는 아니다")
    void drop() {
        SourceCollectionExecution previous = previous(10, 100);
        assertThat(SourceCollectionDto.Drop.of(previous, 4, 100)).isNotNull()
                .satisfies(d -> assertThat(d.previousTableCount()).isEqualTo(10));
        assertThat(SourceCollectionDto.Drop.of(previous, 10, 49)).as("컬럼만 급감").isNotNull();
        assertThat(SourceCollectionDto.Drop.of(previous, 5, 50)).as("정확히 절반").isNull();
        assertThat(SourceCollectionDto.Drop.of(previous, 12, 120)).isNull();
        assertThat(SourceCollectionDto.Drop.of(previous(3, 9), 1, 9)).as("3 → 1 : 정수 나눗셈으로 놓치지 않는다").isNotNull();
        assertThat(SourceCollectionDto.Drop.of(null, 1, 1)).isNull();
        assertThat(SourceCollectionDto.Drop.of(previous(0, 0), 0, 0)).as("직전이 비어 있었다").isNull();
        assertThat(SourceCollectionDto.Drop.of(previous, null, 3)).isNull();
    }

    private static SourceCollectionExecution previous(int tables, int columns) {
        return SourceCollectionExecution.builder().id(1L).tableCount(tables).columnCount(columns).build();
    }
}
