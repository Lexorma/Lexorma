package com.lexorma.data.inspection.domain;

/**
 * 저장 품질 검사(AI-6 2차)의 코드 값. DB 의 CHECK 제약(V4 {@code CK_INSP_*})과 같은 값만 쓴다 — 값을 바꾸면 새 마이그레이션이 필요하다.
 * 설계 : {@code docs/completed/ai-metadata-quality-diagnosis.md}.
 */
public final class InspectionCodes {

    private InspectionCodes() {
    }

    /** 검사 실행 상태 ({@code TB_INSP_EXCN.EXCN_STTS_CD}) : PENDING → RUNNING → COMPLETED / FAILED, PENDING·RUNNING → INTERRUPTED(재시작), PENDING → FAILED(실행기 거절) */
    public enum RunStatus {
        PENDING, RUNNING, COMPLETED, FAILED, INTERRUPTED;

        public boolean active() {
            return this == PENDING || this == RUNNING;
        }
    }

    /** 실행에 적용한 규칙의 판정 상태 ({@code TB_INSP_EXCN_RULE.EXCN_STTS_CD}) */
    public enum RuleRunStatus { RUNNING, COMPLETED, FAILED }

    /** 실제 문제 상태 ({@code TB_INSP_RSLT.STTS_CD}) — 예외 여부와 따로다 (AI6-D24) */
    public enum FindingStatus { OPEN, RESOLVED }

    /** 마지막 판정에서의 변화 ({@code TB_INSP_RSLT.LAST_CHG_TYPE_CD}) */
    public enum LastChange { NEW, PERSISTING, REOPENED, RESOLVED }

    /** 해결 사유 ({@code END_RSN_CD}) : 실제 개선과 대상 삭제를 나눈다 (AI6-D13·D25) */
    public enum EndReason { RULE_SATISFIED, TARGET_DELETED }

    /** 실행 × 규칙별 건수의 변화 유형 ({@code TB_INSP_EXCN_RSLT.CHG_TYPE_CD}). NEW·PERSISTING·REOPENED 의 합 = 발견 수, EXCLUDED = 발견 중 예외 */
    public enum RunChange { NEW, PERSISTING, REOPENED, RULE_SATISFIED, TARGET_DELETED, EXCLUDED }

    /** 결과 이력의 사건 ({@code TB_INSP_RSLT_HSTRY.CHG_TYPE_CD}) : 사람의 예외 처리·해제와 검사가 찾은 재발 (AI6-D15) */
    public enum HistoryType { EXCLUDE, UNEXCLUDE, REOPEN }

    /** 실패·중단 사유 코드 ({@code TB_INSP_EXCN.ERR_CD}) */
    public static final String ERR_SERVER_RESTARTED = "SERVER_RESTARTED";
    public static final String ERR_EXECUTOR_BUSY = "INSPECTION_EXECUTOR_BUSY"; // ErrorCode.INSPECTION_EXECUTOR_BUSY 와 같은 이름
    /** 판정 단계 실패 (스냅숏·기존 결과 읽기 등 묶음 밖에서) : 실행 FAILED, 결과는 그대로 */
    public static final String ERR_FAILED = "INSPECTION_FAILED";
    /** 부분 실패 : 확정한 실행(COMPLETED)에서 일부 규칙 묶음을 판정하지 못했다 (그 규칙은 TB_INSP_EXCN_RULE 에 FAILED, 결과는 그대로) — AI6-2-02 */
    public static final String ERR_RULE_FAILED = "RULE_FAILED";
    /** 전체 판정 실패 : 적용 규칙이 있는데 끝까지 판정한 묶음이 하나도 없다 → 실행 FAILED, 결과·이력은 그대로 (AI6-2-02) */
    public static final String ERR_ALL_RULES_FAILED = "ALL_RULES_FAILED";
    /** 결과 저장 실패 : 저장 트랜잭션 전체 롤백 → 실행 FAILED, 이전 결과 그대로 (AI6-D33) */
    public static final String ERR_STORE_FAILED = "STORE_FAILED";
}
