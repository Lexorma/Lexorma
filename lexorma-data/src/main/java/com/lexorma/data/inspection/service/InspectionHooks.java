package com.lexorma.data.inspection.service;

/**
 * 검사 실행 중간 지점 (테스트 전용 확장점). 운영에는 이 빈이 없어 {@link #NONE} 을 쓴다.
 * 테스트는 이 빈을 등록해 스냅숏을 잡은 직후·저장 직전에 다른 트랜잭션을 끼워 넣는다 (스냅숏 일관성, 동시 예외 처리).
 */
public interface InspectionHooks {

    InspectionHooks NONE = new InspectionHooks() {
    };

    /** 판정 트랜잭션이 스냅숏을 잡은 직후 (아직 아무 규칙도 판정하지 않았다) */
    default void afterSnapshot(long runId) {
    }

    /** 규칙 묶음 하나를 판정하기 직전 (여기서 예외를 던지면 그 묶음만 실패한다) */
    default void beforeGroup(long runId, InspectionJudge.Group group) {
    }

    /** 규칙 묶음 하나의 판정이 끝난 직후, 그 결과를 실행 결과에 넣기 전 (여기서 예외를 던지면 판정을 마친 그 묶음의 결과도 버리고 그 묶음만 실패한다) */
    default void afterGroup(long runId, InspectionJudge.Group group, int items) {
    }

    /** 저장 트랜잭션 안 : 결과를 모두 쓰고 실행을 확정하기 직전 (여기서 예외를 던지면 저장 전체가 롤백된다) */
    default void beforeComplete(long runId) {
    }

    /** 실행이 끝난 뒤 (확정했을 때만) : 단계별 시간(ms)과 판정 건수 — 성능 측정용 */
    default void afterRun(long runId, java.util.Map<String, Long> phases) {
    }

    /** 판정이 끝나고 저장 트랜잭션을 열기 직전 */
    default void beforeStore(long runId) {
    }
}
