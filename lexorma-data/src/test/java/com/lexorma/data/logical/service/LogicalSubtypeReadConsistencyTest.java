package com.lexorma.data.logical.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-35 D35-2 (D35-D12) : 상속 정보 조회 중 계층(그룹 링크)과 사슬 엔터티 조회 사이에 엔터티가 사라진 경우만 '다시 조회' 오류 — 그 밖에는 통과.
 * 실제 경합은 두 쿼리 사이에 다른 트랜잭션의 커밋이 끼어야 해서 재현 테스트 대신 판정을 직접 확인한다
 */
class LogicalSubtypeReadConsistencyTest {

    @Test
    @DisplayName("사슬의 엔터티가 모두 읽혔으면 통과, 하나라도 없으면 MODEL_CONFLICT (다시 조회)")
    void requireLoaded() {
        assertThatCode(() -> LogicalSubtypeService.requireLoaded(List.of(1L, 2L, 3L), Set.of(1L, 2L, 3L))).doesNotThrowAnyException();
        assertThatCode(() -> LogicalSubtypeService.requireLoaded(List.of(7L), Set.of(7L))).doesNotThrowAnyException();
        assertThatThrownBy(() -> LogicalSubtypeService.requireLoaded(List.of(1L, 2L, 3L), Set.of(1L, 3L)))
                .isInstanceOfSatisfying(CustomException.class, e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_CONFLICT));
    }
}
