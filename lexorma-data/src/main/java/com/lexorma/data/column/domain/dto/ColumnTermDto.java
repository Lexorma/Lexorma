package com.lexorma.data.column.domain.dto;

import com.lexorma.data.dictionary.domain.dto.StandardTermDto;

/**
 * 기존 컬럼의 표준 용어 재연결 (COL-TERM). 연결만 바꾸고 컬럼의 이름·타입·길이·설명·업무 설명 등은 그대로 둔다.
 */
public final class ColumnTermDto {

    private ColumnTermDto() {
    }

    /**
     * @param termId     새로 연결할 표준 용어
     * @param baseTermId 화면이 읽은 지금 연결 (필수) — 그 사이 다른 사용자가 바꿨으면 COLUMN_TERM_CONFLICT
     */
    public record Request(Long termId, Long baseTermId) {
    }

    /** changed=false : 이미 같은 용어였다 (아무것도 바꾸지 않았다, 이력도 없다) */
    public record Result(Long columnId, boolean changed, StandardTermDto before, StandardTermDto after) {
    }
}
