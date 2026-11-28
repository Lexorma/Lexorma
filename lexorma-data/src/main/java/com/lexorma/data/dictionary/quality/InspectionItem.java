package com.lexorma.data.dictionary.quality;

import java.util.Map;

/**
 * 저장 검사(AI-6)에 넘기는 판정 결과 1건 : {@link Finding} + 저장·조회에 필요한 대상 정보. 판정 클래스(규칙)는 바꾸지 않고, 진단 서비스가 판정 결과에서 만든다.
 *
 * @param finding    판정 결과 (규칙·대상 ID 목록·현재/기대 값·근거·fingerprint)
 * @param objectType 대표 대상의 종류 — 식별자 규칙은 컬럼 → 테이블 → 스키마 순으로 정한 실제 객체
 * @param objectId   대표 대상 ID (용어 그룹은 가장 작은 구성원 ID)
 * @param tableId    대상이 속한 테이블 (컬럼·테이블·식별자 문제, 없으면 null)
 * @param targetName 판정 당시의 표시 이름 (스키마.테이블.컬럼, 용어 그룹 키 등) — 대상이 지워져도 결과에 남는다
 * @param details    근거에 함께 둘 표시 정보 (구성원 이름, 스키마·테이블 이름 등). 비밀값을 넣지 않는다
 */
public record InspectionItem(Finding finding, ObjectType objectType, long objectId, Long tableId, String targetName, Map<String, Object> details) {

    /** 결과가 가리키는 실제 객체의 종류 (대상 삭제 판정에 쓴다) */
    public enum ObjectType { COLUMN, TABLE, SCHEMA, TERM }
}
