package com.lexorma.common.utils;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.springframework.data.domain.Sort.by;

/**
 * 공용 그리드 목록 조회의 페이지 요청 (docs/reference/list-query-policy.md).
 * 여기서는 형식만 본다 — 페이지 번호·크기·오프셋 범위, 정렬 방향, 정렬 필드가 그 조회가 넘긴 허용 목록에 있는지.
 * 허용 목록(화면 필드명)과 실제 정렬 경로는 각 조회(서비스·저장소)가 정한다. 잘못된 입력은 조회를 실행하기 전에 400 INVALID_PAGE_REQUEST.
 */
public class PageableUtil {

    /** 일반 관리 목록 한 번의 최대 행 수 (공용 그리드 선택지 10·25·50·100). 더 크면 줄이지 않고 거절한다 */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 페이징 처리를 위한 Pageable을 생성한다.
     * sort = "필드,asc|desc" 를 ';' 로 이어 여럿 (방향은 대소문자 무시). 비어 있으면 order 내림차순 (서버 기본 정렬 — 허용 목록과 무관).
     * 방향이 없거나 빈 항목·필드명이 빈 항목은 예전처럼 건너뛴다 (단 필드명이 있으면 허용 목록은 본다).
     * 400 INVALID_PAGE_REQUEST : 음수 페이지, 1~100 밖의 크기, JPA 가 표현할 수 없는 오프셋(int 초과), asc·desc 가 아닌 방향, sortable 에 없는 필드
     * (예전에는 방향·페이지 오류가 500, 모르는 필드는 화면에 따라 500 또는 무시였다 — M-6 후속 3차 보완·공통 페이징 안정성 개선)
     * @param sortable 정렬할 수 있는 화면 필드명 (각 조회의 허용 목록)
     */
    public static Pageable getGridPageable(int page, int size, String sort, String order, Set<String> sortable) {
        if (page < 0) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "페이지 번호는 0 이상이어야 합니다.");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "페이지 크기는 1~{0} 사이여야 합니다.", MAX_PAGE_SIZE);
        // JPA setFirstResult 는 int : 넘으면 Spring Data 가 500 을 냈다 (QueryDSL 은 빈 목록) → 어느 조회든 같은 400
        if ((long) page * size > Integer.MAX_VALUE) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "요청한 페이지가 조회할 수 있는 범위를 벗어났습니다.");
        List<Sort.Order> orderList = Optional.ofNullable(sort)
                .filter(s -> !s.isBlank()) // 빈 문자열 방지
                .map(s -> Arrays.stream(s.split(";")) // 여러 정렬 조건 지원
                        .map(arr -> arr.split(","))
                        .filter(arr -> arr.length > 0 && !arr[0].isBlank())
                        .peek(arr -> requireSortable(arr[0].trim(), sortable)) // 한 항목이라도 허용 목록 밖이면 요청 전체를 거절
                        .filter(arr -> arr.length > 1 && !arr[1].isBlank()) // 방향이 없는 항목은 건너뛴다 (기존)
                        .map(arr -> new Sort.Order(direction(arr[1]), arr[0].trim()))
                        .toList())
                .orElse(List.of(new Sort.Order(Sort.Direction.DESC, order))); // 기본 정렬 추가

        return PageRequest.of(page, size, by(orderList));
    }

    /**
     * 페이징 처리를 위한 Pageable을 생성한다.
     * Pageable의 default를 "id"로 지정한다.
     * @param sortable 정렬할 수 있는 화면 필드명 (각 조회의 허용 목록)
     */
    public static Pageable getGridPageable(int page, int size, String sort, Set<String> sortable) {
        return getGridPageable(page, size, sort, "id", sortable);
    }

    private static void requireSortable(String field, Set<String> sortable) {
        if (!sortable.contains(field)) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "허용하지 않는 정렬 항목입니다.");
    }

    private static Sort.Direction direction(String value) {
        return Sort.Direction.fromOptionalString(value.trim())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "정렬 방향은 asc 또는 desc 만 쓸 수 있습니다."));
    }

    /**
     * 페이지를위한 ResponseEntity를 생성한다.
     * @param page
     * @return
     */
    public static <T> ResponseEntity<Map<String, Object>> getGridPageableMap(Page<T> page) {
        Map<String, Object> response = new HashMap<>();
        response.put("data", page.getContent()); // 현재 페이지 데이터
        response.put("recordsTotal", page.getTotalElements()); // 전체 데이터 개수
        response.put("recordsFiltered", page.getTotalElements()); // 필터링된 데이터 개수
        return ResponseEntity.ok(response);
    }
}
