package com.lexorma.source.collection.domain.enums;

/** 수집 대상(이름 공간) 하나의 결과 */
public enum TargetResult {
    /** 완전하게 수집했다 : 이 범위 안에서만 삭제를 판정한다 */
    COMPLETED,
    FAILED,
    /** 요청한 스키마가 원본에 없다 (자동으로 삭제하지 않는다 : 스키마 삭제는 FULL 수집에서만 판정한다) */
    NOT_FOUND,
    /** 결과를 받지 못했다 (실행이 도중에 실패·취소됐다) */
    SKIPPED
}
