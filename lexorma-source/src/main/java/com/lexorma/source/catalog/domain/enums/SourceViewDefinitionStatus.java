package com.lexorma.source.catalog.domain.enums;

/**
 * 원본 VIEW 정의를 읽은 결과 (DEC-16). COLLECTED : 정의를 읽었다 / NO_PRIVILEGE : 원본 DB 가 정의 조회를 허용하지 않았다 (예 : pg_get_viewdef 실행 권한 회수) /
 * FAILED : 그 밖의 이유로 읽지 못했다 / TOO_LARGE : 정의가 저장 한도보다 길어 원문을 받지 않았다. COLLECTED 가 아니면 이전에 읽은 원문·해시를 그대로 둔다.
 */
public enum SourceViewDefinitionStatus {
    COLLECTED, NO_PRIVILEGE, FAILED, TOO_LARGE
}
