package com.lexorma.source.collection.domain.enums;

/**
 * 수집 실행 상태. COMPLETED 만 카탈로그에 반영된 실행이다 (반영 순서가 붙는다).
 * 일부라도 실패한 실행은 전체를 반영하지 않으므로 FAILED 다 (부분 반영 상태가 없다).
 */
public enum CollectionStatus {
    CREATED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean inProgress() {
        return this == CREATED || this == RUNNING;
    }
}
