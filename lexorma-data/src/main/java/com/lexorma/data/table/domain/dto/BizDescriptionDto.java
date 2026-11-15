package com.lexorma.data.table.domain.dto;

/**
 * 업무 설명(TASK_EXPLN) 직접 수정 (AI 없이). 테이블은 columnId 없이, 컬럼은 tableId + columnId.
 */
public final class BizDescriptionDto {

    private BizDescriptionDto() {
    }

    /**
     * @param bizDescription 새 업무 설명 (빈 문자열 = 지움)
     * @param base           화면이 읽은 업무 설명 (빈 문자열 = 없었음, 필수) — 그 사이 다른 사용자나 AI 제안 승인이 바꿨으면 BIZ_DESCRIPTION_CONFLICT
     */
    public record Request(Long tableId, Long columnId, String bizDescription, String base) {
    }

    /** 저장한 업무 설명 (지웠으면 null) */
    public record Result(Long tableId, Long columnId, String bizDescription) {
    }
}
