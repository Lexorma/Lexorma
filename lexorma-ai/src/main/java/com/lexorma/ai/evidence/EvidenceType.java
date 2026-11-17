package com.lexorma.ai.evidence;

/**
 * AI 답변의 근거가 가리키는 메타데이터 종류 (AI-4 후속). 식별자는 각 테이블의 내부 PK 이고, 읽기 전용 Tool 결과(또는 서버가 모은 맥락)에 실린 ID 만 근거로 인정한다.
 * HISTORY 는 현재 메타데이터가 아니라 모델 변경 이력 한 건(과거 기록)이다 — 대상이 지금은 지워졌어도 이력은 있을 수 있다.
 */
public enum EvidenceType {
    SCHEMA, TABLE, COLUMN, RELATION, TERM, WORD, DOMAIN, HISTORY
}
