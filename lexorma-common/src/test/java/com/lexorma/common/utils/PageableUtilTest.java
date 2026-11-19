package com.lexorma.common.utils;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableUtilTest {

    /** 테스트용 허용 목록 (각 조회가 넘기는 화면 필드명) */
    private static final Set<String> SORTABLE = Set.of("id", "name");

    private static void rejected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PAGE_REQUEST);
    }

    @Test
    @DisplayName("정렬값이 없으면 기본 컬럼(id) 내림차순으로 정렬한다 (기본 정렬은 허용 목록과 무관)")
    void defaultSort() {
        Pageable pageable = PageableUtil.getGridPageable(2, 20, null, Set.of());

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("빈 정렬값은 지정한 기본 컬럼 내림차순으로 정렬한다")
    void blankSortUsesGivenDefault() {
        Pageable pageable = PageableUtil.getGridPageable(0, 10, "  ", "timestamp", SORTABLE);

        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("timestamp"));
    }

    @Test
    @DisplayName("'컬럼,방향' 을 ; 로 구분해 여러 정렬 조건을 순서대로 적용한다")
    void multiSort() {
        Pageable pageable = PageableUtil.getGridPageable(0, 10, "name, asc;id,DESC", SORTABLE);

        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("name"), Sort.Order.desc("id"));
    }

    @Test
    @DisplayName("방향이 없는 정렬 조건은 무시한다")
    void invalidSortEntriesIgnored() {
        Pageable pageable = PageableUtil.getGridPageable(0, 10, "name;id,asc", SORTABLE);

        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    @DisplayName("방향은 대소문자를 가리지 않고, 방향·필드명이 빈 항목과 셋째 칸은 예전처럼 무시한다")
    void lenientEntriesKept() {
        assertThat(PageableUtil.getGridPageable(0, 10, "name,Desc", SORTABLE).getSort()).containsExactly(Sort.Order.desc("name"));
        assertThat(PageableUtil.getGridPageable(0, 10, "name,ASC,extra", SORTABLE).getSort()).containsExactly(Sort.Order.asc("name"));
        assertThat(PageableUtil.getGridPageable(0, 10, "name,", SORTABLE).getSort().isSorted()).isFalse();
        assertThat(PageableUtil.getGridPageable(0, 10, "name, ;id,asc", SORTABLE).getSort()).as("빈 방향").containsExactly(Sort.Order.asc("id"));
        assertThat(PageableUtil.getGridPageable(0, 10, ",asc;id,asc", SORTABLE).getSort()).as("빈 필드명").containsExactly(Sort.Order.asc("id"));
        assertThat(PageableUtil.getGridPageable(0, 10, ";", SORTABLE).getSort().isSorted()).isFalse();
    }

    @Test
    @DisplayName("잘못된 방향·음수 페이지·1 미만 크기는 400 INVALID_PAGE_REQUEST (예전에는 IllegalArgumentException → 500)")
    void invalidInputRejected() {
        rejected(() -> PageableUtil.getGridPageable(0, 10, "name,up", SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(0, 10, "id,asc;name,descending", SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(-1, 10, null, SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(0, 0, null, SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(0, -1, "name,asc", SORTABLE));
        assertThat(ErrorCode.INVALID_PAGE_REQUEST.getHttpStatus().value()).isEqualTo(400);
    }

    @ParameterizedTest(name = "크기 {0} 허용")
    @ValueSource(ints = {1, 10, 25, 50, 100})
    @DisplayName("페이지 크기 1~100 은 그대로 (공용 그리드 선택지 10·25·50·100)")
    void pageSizeWithinLimit(int size) {
        assertThat(PageableUtil.getGridPageable(0, size, "name,asc", SORTABLE).getPageSize()).isEqualTo(size);
    }

    @ParameterizedTest(name = "크기 {0} 거절")
    @ValueSource(ints = {101, 1_000, 100_000, Integer.MAX_VALUE})
    @DisplayName("100 을 넘는 크기는 100 으로 줄이지 않고 400 (공통 페이징 안정성 개선)")
    void pageSizeOverLimitRejected(int size) {
        rejected(() -> PageableUtil.getGridPageable(0, size, "name,asc", SORTABLE));
        assertThat(PageableUtil.MAX_PAGE_SIZE).isEqualTo(100);
    }

    @Test
    @DisplayName("오프셋(페이지 × 크기)이 int 를 넘으면 400 — JPA 가 표현할 수 없다. 그 안의 큰 페이지는 그대로 (빈 목록은 조회가 돌려준다)")
    void offsetRange() {
        assertThat(PageableUtil.getGridPageable(21_474_836, 100, null, SORTABLE).getOffset()).isEqualTo(2_147_483_600L);
        assertThat(PageableUtil.getGridPageable(999, 10, null, SORTABLE).getPageNumber()).isEqualTo(999);
        rejected(() -> PageableUtil.getGridPageable(21_474_837, 100, null, SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(Integer.MAX_VALUE, 10, null, SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(300_000_000, 10, null, SORTABLE));
    }

    @Test
    @DisplayName("허용 목록 밖 필드·중첩 경로·정상 필드와 섞인 요청·방향 없는 모르는 필드는 400 (예전에는 화면에 따라 500 또는 무시)")
    void unknownSortFieldRejected() {
        rejected(() -> PageableUtil.getGridPageable(0, 10, "password,asc", SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(0, 10, "account.password,desc", SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(0, 10, "name,asc;project.owner.password,asc", SORTABLE));
        rejected(() -> PageableUtil.getGridPageable(0, 10, "Name,asc", SORTABLE)); // 필드명은 대소문자를 가린다 (엔티티·화면 필드명 그대로)
        rejected(() -> PageableUtil.getGridPageable(0, 10, "unknown", SORTABLE));
        assertThat(PageableUtil.getGridPageable(0, 10, "name", SORTABLE).getSort().isSorted()).as("허용 필드의 방향 없음은 예전처럼 건너뜀").isFalse();
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
