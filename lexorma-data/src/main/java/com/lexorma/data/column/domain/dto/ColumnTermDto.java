package com.lexorma.data.column.domain.dto;

import com.lexorma.data.dictionary.domain.dto.StandardTermDto;

import java.util.List;

/**
 * 기존 컬럼의 표준 용어 연결 (COL-TERM 재연결, DEC-26 최초 연결·해제·일괄). 연결만 바꾸고 컬럼의 이름·타입·길이·설명·업무 설명 등은 그대로 둔다.
 */
public final class ColumnTermDto {

    private ColumnTermDto() {
    }

    /**
     * @param termId     새로 연결할 표준 용어 (해제면 null)
     * @param baseTermId 화면이 읽은 지금 연결 — 연결이 없던 컬럼(최초 연결)이면 null. 그 사이 다른 사용자가 바꿨으면 COLUMN_TERM_CONFLICT
     * @param unlink     true 면 연결 해제 (termId 는 비운다)
     */
    public record Request(Long termId, Long baseTermId, Boolean unlink) {
        public Request(Long termId, Long baseTermId) {
            this(termId, baseTermId, null);
        }
    }

    /** 일괄 연결·변경·해제 (DEC-26) : 항목 하나라도 거부되면 아무것도 바꾸지 않는다. 최대 500개 */
    public record BulkItem(Long columnId, Long termId, Long baseTermId, Boolean unlink) {
    }

    public record BulkRequest(List<BulkItem> items) {
    }

    /** changed=false : 이미 같은 용어였다 (아무것도 바꾸지 않았다, 이력도 없다). 해제·최초 연결이면 before·after 중 하나가 null */
    public record Result(Long columnId, boolean changed, StandardTermDto before, StandardTermDto after) {
    }
}
