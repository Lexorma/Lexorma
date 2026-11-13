package com.lexorma.core.source;

/**
 * 원본 연결 삭제 직전에 발행되는 이벤트 (M-4c). 연결에 기대는 다른 모듈의 데이터(원본 매핑·기준 연결)를 정리한다.
 * 동기 리스너로 처리되므로 연결 삭제와 같은 트랜잭션이다 : 리스너가 실패하면 연결 삭제도 롤백된다 (ProjectDeletedEvent 와 같은 방식).
 */
public record SourceConnectionDeletedEvent(Long projectId, Long connectionId) {
}
