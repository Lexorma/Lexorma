package com.lexorma.common.utils;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableUtilTest {

    @Test
    @DisplayName("정렬값이 없으면 기본 컬럼(id) 내림차순으로 정렬한다")
    void defaultSort() {
        Pageable pageable = PageableUtil.getGridPageable(2, 20, null);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("빈 정렬값은 지정한 기본 컬럼 내림차순으로 정렬한다")
    void blankSortUsesGivenDefault() {
        Pageable pageable = PageableUtil.getGridPageable(0, 10, "  ", "timestamp");

        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("timestamp"));
    }

    @Test
    @DisplayName("'컬럼,방향' 을 ; 로 구분해 여러 정렬 조건을 순서대로 적용한다")
    void multiSort() {
        Pageable pageable = PageableUtil.getGridPageable(0, 10, "name, asc;id,DESC");

        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("name"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("방향이 없는 정렬 조건은 무시한다")
    void invalidSortEntriesIgnored() {
        Pageable pageable = PageableUtil.getGridPageable(0, 10, "name;id,asc");

        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    @DisplayName("방향은 대소문자를 가리지 않고, 방향·필드명이 빈 항목과 셋째 칸은 예전처럼 무시한다")
    void lenientEntriesKept() {
        assertThat(PageableUtil.getGridPageable(0, 10, "name,Desc").getSort()).containsExactly(Sort.Order.desc("name"));
        assertThat(PageableUtil.getGridPageable(0, 10, "name,ASC,extra").getSort()).containsExactly(Sort.Order.asc("name"));
        assertThat(PageableUtil.getGridPageable(0, 10, "name,").getSort().isSorted()).isFalse();
        assertThat(PageableUtil.getGridPageable(0, 10, "name, ;id,asc").getSort()).as("빈 방향").containsExactly(Sort.Order.asc("id"));
        assertThat(PageableUtil.getGridPageable(0, 10, ",asc;id,asc").getSort()).as("빈 필드명").containsExactly(Sort.Order.asc("id"));
        assertThat(PageableUtil.getGridPageable(0, 10, ";").getSort().isSorted()).isFalse();
        assertThat(PageableUtil.getGridPageable(0, 100_000, null).getPageSize()).as("크기 상한은 각 조회가 정한다").isEqualTo(100_000);
    }

    @Test
    @DisplayName("잘못된 방향·음수 페이지·1 미만 크기는 400 INVALID_PAGE_REQUEST (예전에는 IllegalArgumentException → 500)")
    void invalidInputRejected() {
        for (Runnable call : List.<Runnable>of(
                () -> PageableUtil.getGridPageable(0, 10, "name,up"),
                () -> PageableUtil.getGridPageable(0, 10, "id,asc;name,descending"),
                () -> PageableUtil.getGridPageable(-1, 10, null),
                () -> PageableUtil.getGridPageable(0, 0, null),
                () -> PageableUtil.getGridPageable(0, -1, "name,asc"))) {
            assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAGE_REQUEST);
        }
        assertThat(ErrorCode.INVALID_PAGE_REQUEST.getHttpStatus().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("그리드 응답은 data / recordsTotal / recordsFiltered 를 담는다")
    void gridPageableMap() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

        ResponseEntity<Map<String, Object>> response = PageableUtil.getGridPageableMap(page);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("data", List.of("a", "b"))
                .containsEntry("recordsTotal", 5L)
                .containsEntry("recordsFiltered", 5L);
    }
}
