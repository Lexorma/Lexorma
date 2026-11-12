package com.lexorma.source.collection.domain.enums;

/** 수집 범위 : 연결 전체 / 지정한 스키마만. 삭제 판정은 실행이 완전하게 수집한 범위 안에서만 한다 */
public enum CollectionType {
    /** 연결의 모든 스키마. 결과에 없는 스키마·테이블은 원본에서 사라진 것으로 본다 */
    FULL,
    /** 요청한 스키마만. 범위 밖의 스키마·테이블은 비교하지 않는다 */
    PARTIAL
}
