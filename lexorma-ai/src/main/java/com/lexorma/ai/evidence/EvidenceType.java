package com.lexorma.ai.evidence;

/**
 * AI 답변의 근거가 가리키는 메타데이터 종류 (AI-4 후속). 식별자는 각 테이블의 내부 PK 이고, 읽기 전용 Tool 결과(또는 서버가 모은 맥락)에 실린 ID 만 근거로 인정한다.
 * HISTORY 는 현재 메타데이터가 아니라 모델 변경 이력 한 건(과거 기록)이다 — 대상이 지금은 지워졌어도 이력은 있을 수 있다.
 * LOGICAL_* · SUBJECT_AREA · SUBTYPE_GROUP (D-35 D35-3b) : 논리 모델 Tool 결과의 대상. LOGICAL_ATTRIBUTE·LOGICAL_IDENTIFIER 는 원본 행의 ID 다 —
 * 서브타입에서 조회한 상속 속성·상속 식별자도 정의한 엔터티의 행을 가리킨다 (서브타입에 직접 정의된 것으로 기록하지 않는다).
 * RELATION 의 kind LOGICAL(물리 모델의 문서상 관계)과 LOGICAL_RELATIONSHIP(논리 모델의 엔터티 관계)는 다르다.
 */
public enum EvidenceType {
    SCHEMA, TABLE, COLUMN, RELATION, TERM, WORD, DOMAIN, HISTORY,
    LOGICAL_MODEL, SUBJECT_AREA, LOGICAL_ENTITY, LOGICAL_ATTRIBUTE, LOGICAL_IDENTIFIER, LOGICAL_RELATIONSHIP, SUBTYPE_GROUP
}
