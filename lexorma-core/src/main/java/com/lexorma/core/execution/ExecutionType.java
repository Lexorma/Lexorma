package com.lexorma.core.execution;

/** 실행 방식 */
public enum ExecutionType {
    /** 사용자 요청 : 로그인 세션의 사용자·프로젝트 */
    USER,
    /** 비동기 요청 : 요청한 시점의 사용자·프로젝트를 작업에 고정한 것 */
    ASYNC,
    /** 정기 배치 : 세션도 요청자도 없이 지정한 프로젝트 하나 */
    SCHEDULED
}
