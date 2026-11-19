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

public class PageableUtil {

    /**
     * 페이징 처리를 위한 Pageable을 생성한다.
     * sort = "필드,asc|desc" 를 ';' 로 이어 여럿 (방향은 대소문자 무시). 비어 있으면 order 내림차순.
     * 방향이 없거나 빈 항목·필드명이 빈 항목은 예전처럼 건너뛴다. 필드명이 허용되는지는 각 조회가 정한다 (여기서 거르지 않는다).
     * 잘못된 입력은 400 INVALID_PAGE_REQUEST : 음수 페이지, 1 미만 크기, asc·desc 가 아닌 방향 (예전에는 IllegalArgumentException 이 500 으로 나갔다 — M-6 후속 3차 보완)
     * @param page
     * @param size
     * @param sort
     * @return
     */
    public static Pageable getGridPageable(int page, int size, String sort, String order) {
        if (page < 0) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "페이지 번호는 0 이상이어야 합니다.");
        if (size < 1) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "페이지 크기는 1 이상이어야 합니다.");
        List<Sort.Order> orderList = Optional.ofNullable(sort)
                .filter(s -> !s.isBlank()) // 빈 문자열 방지
                .map(s -> Arrays.stream(s.split(";")) // 여러 정렬 조건 지원
                        .map(arr -> arr.split(","))
                        .filter(arr -> arr.length > 1 && !arr[0].isBlank() && !arr[1].isBlank()) // 올바른 데이터만 변환
                        .map(arr -> new Sort.Order(direction(arr[1]), arr[0].trim()))
                        .toList())
                .orElse(List.of(new Sort.Order(Sort.Direction.DESC, order))); // 기본 정렬 추가

        return PageRequest.of(page, size, by(orderList));
    }

    private static Sort.Direction direction(String value) {
        return Sort.Direction.fromOptionalString(value.trim())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_PAGE_REQUEST, "정렬 방향은 asc 또는 desc 만 쓸 수 있습니다."));
    }

    /**
     * 페이징 처리를 위한 Pageable을 생성한다.
     * Pageable의 default를 "id"로 지정한다.
     * @param page
     * @param size
     * @param sort
     * @return
     */
    public static Pageable getGridPageable(int page, int size, String sort) {
        return getGridPageable(page, size, sort, "id");
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
