package com.lexorma.common.exception.domain;

import com.lexorma.common.i18n.Messages;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    /**
     * 알수없는 오류가 발생하였습니다.
     */
    UNKNOWN_EXCEPTION(HttpStatus.BAD_REQUEST, "UNKNOWN-EXCEPTION"),

    /**
     * 패스워드가 일치하지 않습니다.
     */
    PASSWORD_NOT_MATCHED(HttpStatus.NOT_FOUND, "PASSWORD-NOT-MATCHED"),


    /**
     * 엑셀 생성이 실패하였습니다.
     */
    CREATE_EXCEL_FAIL_EXCEPTION(HttpStatus.NOT_FOUND, "CREATE_EXCEL_FAIL_EXCEPTION"),

    /**
     * 등록된 아이디가 존재합니다.
     */
    USERNAME_IS_DUPLICATION(HttpStatus.BAD_REQUEST, "USERNAME_IS_DUPLICATION"),

    /**
     * 비밀번호는 8자 이상, 대문자 및 특수문자를 각각 하나 이상 포함해야 합니다.
     */
    PASSWORD_IS_NON_VALIDATOR(HttpStatus.BAD_REQUEST, "PASSWORD_IS_NON_VALIDATOR"),

    /**
     * 아이디는 5~20자 사이의 소문자, 숫자, 밑줄(_)로 구성할 수 있습니다. 단, 밑줄은 처음과 끝에 사용할 수 없으며 연속된 밑줄도 허용되지 않습니다.
     */
    USERNAME_IS_NON_VALIDATOR(HttpStatus.BAD_REQUEST, "USERNAME_IS_NON_VALIDATOR"),

    /**
     * 동일한 형식의 표준 용어와 표준 도메인이 존재합니다. 중복된 용어는 등록이 불가능합니다.
     */
    DUPLICATE_TERM_DATA(HttpStatus.BAD_REQUEST, "DUPLICATE_TERM_DATA"),


    /**
     * 해당 용어를 찾을 수 없습니다.
     */
    NOT_FOUND_TERM_DATA(HttpStatus.BAD_REQUEST, "NOT_FOUND_TERM_DATA"),

    /**
     * 등록 용어중 매핑되지 않는 단어가 존재합니다.
     */
    NOT_FOUND_WORD_DATA(HttpStatus.BAD_REQUEST, "NOT_FOUND_WORD_DATA"),

    /**
     * 표준단어명/영문약어명에는 '_' 를 쓸 수 없습니다. ('_' 는 표준용어의 단어 구분자)
     */
    WORD_UNDERSCORE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "WORD_UNDERSCORE_NOT_ALLOWED"),

    /**
     * 표준용어명과 영문약어명의 단어 수('_' 기준)가 다릅니다.
     */
    TERM_WORD_COUNT_MISMATCH(HttpStatus.BAD_REQUEST, "TERM_WORD_COUNT_MISMATCH"),

    /**
     * 테이블 코멘트 중 승인된 표준 단어가 아닌 단어가 존재합니다.
     */
    TABLE_WORD_NOT_STANDARD(HttpStatus.BAD_REQUEST, "TABLE_WORD_NOT_STANDARD"),

    /**
     * 테이블 코멘트를 입력해주세요.
     */
    TABLE_KOR_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "TABLE_KOR_NAME_REQUIRED"),

    /**
     * 테이블명 접두어 형식 오류
     */
    INVALID_TABLE_PREFIX(HttpStatus.BAD_REQUEST, "INVALID_TABLE_PREFIX"),

    /**
     * 승인된 정보는 삭제할 수 없습니다.
     */
    APPROVED_DATA_CANNOT_BE_DELETED(HttpStatus.BAD_REQUEST, "APPROVED_DATA_CANNOT_BE_DELETED"),

    /**
     * 사용 중인 정보는 삭제할 수 없습니다.
     */
    RELATION_EXISTS(HttpStatus.BAD_REQUEST, "RELATION_EXISTS"),

    /**
     * 해당 도메인을 찾을 수 없습니다.
     */
    NOT_FOUND_DOMAIN_DATA(HttpStatus.BAD_REQUEST, "NOT_FOUND_DOMAIN_DATA"),

    /**
     * 권한이 없어 작업을 수행할 수 없습니다.
     */
    DATA_CANNOT_BE_DELETED(HttpStatus.BAD_REQUEST, "DATA_CANNOT_BE_DELETED"),

    /**
     * 저장된 데이터가 존재합니다.
     */
    SAVED_DATA_EXISTS(HttpStatus.BAD_REQUEST, "SAVED_DATA_EXISTS"),

    /**
     * 업로드한 엑셀 안에 같은 테이블의 같은 컬럼명이 두 번 이상 있습니다.
     */
    COLUMN_DUPLICATE_IN_FILE(HttpStatus.BAD_REQUEST, "COLUMN_DUPLICATE_IN_FILE"),

    /**
     * 컬럼으로 등록된 정보가 존재하기 때문에 삭제가 불가능합니다.
     */
    COLUMN_DEPENDENCY_EXISTS(HttpStatus.BAD_REQUEST, "COLUMN_DEPENDENCY_EXISTS"),

    /**
     * 등록된 테이블 정보가 존재하지 않습니다.
     */
    TABLE_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "TABLE_IS_NOT_DEFINED"),

    /**
     * 등록된 컬럼 정보가 존재하지 않습니다 (현재 프로젝트에 그 컬럼이 없다).
     */
    COLUMN_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "COLUMN_IS_NOT_DEFINED"),

    /**
     * NULL 허용여부, 필수 입력 여부, 민감정보 여부, PK 여부, 고유값 여부, 인덱스 생성 여부, 암호화 필요 여부는 Y 또는 N 값이어야합니다.
     */
    COLUMN_BOOLEAN_TYPE_DEF(HttpStatus.BAD_REQUEST, "COLUMN_BOOLEAN_TYPE_DEF"),

    /**
     * 등록된 테이블 정보가 존재하지 않습니다.
     */
    TERM_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "TERM_IS_NOT_DEFINED"),

    /**
     * 승인된 정보는 수정이 불가능합니다.
     */
    APPROVED_DATA_CANNOT_BE_UPDATE(HttpStatus.BAD_REQUEST, "APPROVED_DATA_CANNOT_BE_UPDATE"),

    /**
     * 시스템이 등록한 공지사항은 수정이 불가능합니다.
     */
    SYSTEM_NOTICE_CANNOT_BE_UPDATE(HttpStatus.BAD_REQUEST, "SYSTEM_NOTICE_CANNOT_BE_UPDATE"),

    /**
     * 입력하신 표준도메인분류명을 찾을 수 없습니다.
     */
    DOMAIN_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "DOMAIN_IS_NOT_DEFINED"),

    /**
     * 프로젝트 관리자는 삭제가 불가능합니다.
     */
    MANAGER_NON_DELETED(HttpStatus.BAD_REQUEST, "MANAGER_NON_DELETED"),

    /**
     * 로그인 정보가 일치하지 않습니다. (아이디 존재 여부를 노출하지 않도록 동일 메시지 사용)
     */
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED"),

    /**
     * 로그인 가능한 프로젝트가 없습니다.
     */
    NO_ACCESSIBLE_PROJECT(HttpStatus.FORBIDDEN, "NO_ACCESSIBLE_PROJECT"),

    /**
     * 공통 컬럼 세트는 프로젝트 관리자(또는 ADMIN/MANAGER)만 바꿀 수 있습니다.
     */
    COMMON_COLUMN_PERMISSION_DENIED(HttpStatus.BAD_REQUEST, "COMMON_COLUMN_PERMISSION_DENIED"),

    /**
     * 공통 컬럼 세트에 같은 컬럼명(표준용어)이 두 번 들어 있습니다.
     */
    COMMON_COLUMN_DUPLICATED(HttpStatus.BAD_REQUEST, "COMMON_COLUMN_DUPLICATED"),

    /**
     * 공통 컬럼 세트에 등록할 수 있는 개수를 넘었습니다.
     */
    COMMON_COLUMN_TOO_MANY(HttpStatus.BAD_REQUEST, "COMMON_COLUMN_TOO_MANY"),

    /**
     * 초기 비밀번호(관리자 초기화 포함) 사용 중 : 비밀번호를 변경하기 전에는 로그인할 수 없습니다.
     */
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED"),

    /**
     * 스키마를 찾을 수 없습니다.
     */
    SCHEMA_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "SCHEMA_IS_NOT_DEFINED"),

    /**
     * 스키마명 형식 오류
     */
    INVALID_SCHEMA_NAME(HttpStatus.BAD_REQUEST, "INVALID_SCHEMA_NAME"),

    /**
     * 스키마 등록·수정·삭제는 승인 권한(관리자·매니저·프로젝트 관리자)만 할 수 있습니다.
     */
    SCHEMA_PERMISSION_DENIED(HttpStatus.BAD_REQUEST, "SCHEMA_PERMISSION_DENIED"),

    /**
     * 승인·승인취소는 승인 권한(관리자·매니저·프로젝트 관리자)만 할 수 있습니다.
     */
    APPROVAL_PERMISSION_DENIED(HttpStatus.BAD_REQUEST, "APPROVAL_PERMISSION_DENIED"),

    /**
     * 관계·인덱스·제약조건 정의 오류 (사유는 customMessage)
     */
    INVALID_PHYSICAL_MODEL(HttpStatus.BAD_REQUEST, "INVALID_PHYSICAL_MODEL"),

    /**
     * 논리 모델·엔터티·속성·식별자·관계 정의 오류 (사유는 customMessage)
     */
    INVALID_LOGICAL_MODEL(HttpStatus.BAD_REQUEST, "INVALID_LOGICAL_MODEL"),

    /**
     * 논리 모델·엔터티·속성·식별자·관계를 찾을 수 없음 (로그인 프로젝트 기준, 대상은 customMessage)
     */
    LOGICAL_MODEL_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "LOGICAL_MODEL_IS_NOT_DEFINED"),

    /**
     * 논리·물리 매핑 정의 오류 : 부모 매핑과 맞지 않는 속성·컬럼, 속성 매핑이 있는 매핑의 테이블 변경 (사유는 customMessage)
     */
    INVALID_MODEL_MAPPING(HttpStatus.BAD_REQUEST, "INVALID_MODEL_MAPPING"),

    /**
     * 논리·물리 매핑을 찾을 수 없음 (로그인 프로젝트 기준)
     */
    MODEL_MAPPING_IS_NOT_DEFINED(HttpStatus.BAD_REQUEST, "MODEL_MAPPING_IS_NOT_DEFINED"),

    /**
     * ERD 조회 범위·레이아웃 값 오류 (사유는 customMessage)
     */
    INVALID_ERD_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_ERD_REQUEST"),

    /**
     * ERD 레이아웃 저장 충돌 : 다른 탭·창에서 먼저 저장함
     */
    ERD_LAYOUT_CONFLICT(HttpStatus.CONFLICT, "ERD_LAYOUT_CONFLICT"),

    /**
     * 새 비밀번호가 현재 비밀번호 또는 아이디와 같습니다.
     */
    PASSWORD_NOT_CHANGED(HttpStatus.BAD_REQUEST, "PASSWORD_NOT_CHANGED"),

    /**
     * 프로젝트를 찾을 수 없습니다.
     */
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND"),

    /**
     * 모델 버전을 찾을 수 없습니다. (없는 ID, 다른 프로젝트의 버전)
     */
    MODEL_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "MODEL_VERSION_NOT_FOUND"),

    /**
     * 모델 버전 요청이 올바르지 않습니다. (버전명·설명 검증, 비교 대상)
     */
    INVALID_MODEL_VERSION(HttpStatus.BAD_REQUEST, "INVALID_MODEL_VERSION"),

    /**
     * 마지막 활성 프로젝트 비활성화 : 확인(confirmLastActive=true)을 받은 뒤에만 진행한다.
     */
    LAST_ACTIVE_PROJECT(HttpStatus.CONFLICT, "LAST_ACTIVE_PROJECT"),

    /**
     * 복구 로그인을 할 수 있는 활성 ADMIN 계정이 없어 시스템이 잠긴다 : 마지막 활성 프로젝트 비활성화 / 마지막 ADMIN 변경을 막는다.
     */
    NO_RECOVERY_ADMIN(HttpStatus.CONFLICT, "NO_RECOVERY_ADMIN"),

    /**
     * 프로젝트에 접근할 수 없다 (비활성 프로젝트, 멤버 아님·승인 취소, 비활성 계정). 로그인 실패 응답이 쓰던 코드와 같은 문구다.
     */
    PROJECT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED"),

    /**
     * 비밀값 암호화 키(LEXORMA_SECRET_KEYS)가 설정되지 않았다 : 비밀값을 저장·사용하는 기능만 쓸 수 없다.
     */
    SECRET_KEY_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "SECRET_KEY_NOT_CONFIGURED"),

    /**
     * 저장된 비밀값을 암호화한 키가 지금 설정에 없다 (키 분실·삭제) : 비밀값을 다시 등록해야 한다.
     */
    SECRET_KEY_NOT_FOUND(HttpStatus.CONFLICT, "SECRET_KEY_NOT_FOUND"),

    /**
     * 저장된 비밀값을 읽지 못했다 (변조, 다른 자리의 암호문, 키 불일치, 형식 오류). 이유는 구분해 알려 주지 않는다.
     */
    SECRET_DECRYPT_FAILED(HttpStatus.CONFLICT, "SECRET_DECRYPT_FAILED"),

    /**
     * 실행 컨텍스트의 요청자에게 필요한 권한이 없다 (역할 부족, 프로젝트 관리자·ADMIN 아님).
     */
    EXECUTION_PERMISSION_DENIED(HttpStatus.FORBIDDEN, "EXECUTION_PERMISSION_DENIED"),

    /**
     * 외부 DB 연결을 찾을 수 없습니다. (없는 ID, 다른 프로젝트의 연결)
     */
    SOURCE_CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "SOURCE_CONNECTION_NOT_FOUND"),

    /**
     * 외부 DB 연결 요청이 올바르지 않습니다.
     */
    INVALID_SOURCE_CONNECTION(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_CONNECTION"),

    /**
     * 원본 카탈로그의 객체를 찾을 수 없습니다. (없는 ID, 다른 프로젝트·다른 연결의 객체)
     */
    SOURCE_OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "SOURCE_OBJECT_NOT_FOUND"),

    /**
     * 원본 카탈로그에 저장할 내용이 올바르지 않습니다. (이름, 구성 컬럼)
     */
    INVALID_SOURCE_CATALOG(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_CATALOG"),

    /**
     * 원본 DB 도구를 실행할 수 없다 : 그런 도구가 없거나, 이 DBMS 를 지원하지 않거나, 연결이 사용 중지 상태이거나, 입력이 도구와 맞지 않는다.
     */
    SOURCE_TOOL_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "SOURCE_TOOL_NOT_AVAILABLE"),

    /**
     * 원본 카탈로그 수집 실행을 찾을 수 없다 (없는 작업, 다른 프로젝트·다른 연결의 작업).
     */
    SOURCE_COLLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "SOURCE_COLLECTION_NOT_FOUND"),

    /**
     * 같은 연결의 수집이 이미 진행 중이다.
     */
    SOURCE_COLLECTION_IN_PROGRESS(HttpStatus.CONFLICT, "SOURCE_COLLECTION_IN_PROGRESS"),

    /**
     * 진행 중인 수집이 아니다 (이미 끝났거나 실패·취소된 작업).
     */
    SOURCE_COLLECTION_NOT_RUNNING(HttpStatus.CONFLICT, "SOURCE_COLLECTION_NOT_RUNNING"),

    /**
     * 수집 결과가 완전하지 않아 반영하지 않았다 (일부 스키마 실패, 요청한 스키마 없음, 범위가 맞지 않음).
     */
    SOURCE_COLLECTION_INCOMPLETE(HttpStatus.BAD_REQUEST, "SOURCE_COLLECTION_INCOMPLETE"),

    /**
     * 실행하던 서버가 멈춰 끝나지 않은 수집을 실패로 정리했다 (실행 상태의 오류 코드로만 쓴다).
     */
    SOURCE_COLLECTION_ABANDONED(HttpStatus.CONFLICT, "SOURCE_COLLECTION_ABANDONED"),

    /**
     * 원본 DB 접속 시간이 초과되었다 (연결 타임아웃).
     */
    SOURCE_CONNECT_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "SOURCE_CONNECT_TIMEOUT"),

    /**
     * 원본 DB 에 접속하지 못했다 (주소·포트·네트워크). 원인 상세는 응답에 넣지 않는다.
     */
    SOURCE_CONNECT_FAILED(HttpStatus.BAD_GATEWAY, "SOURCE_CONNECT_FAILED"),

    /**
     * 원본 DB 가 계정·비밀번호를 거절했다.
     */
    SOURCE_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "SOURCE_AUTH_FAILED"),

    /**
     * 원본 DB 계정에 메타데이터 조회 권한이 없다.
     */
    SOURCE_PERMISSION_DENIED(HttpStatus.BAD_GATEWAY, "SOURCE_PERMISSION_DENIED"),

    /**
     * 원본 DB 와의 TLS 연결 또는 인증서 검증에 실패했다.
     */
    SOURCE_TLS_FAILED(HttpStatus.BAD_GATEWAY, "SOURCE_TLS_FAILED"),

    /**
     * 이 DBMS 의 JDBC 드라이버가 배포본에 없다.
     */
    SOURCE_DRIVER_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "SOURCE_DRIVER_NOT_AVAILABLE"),

    /**
     * 접속 대상이 외부 연결 정책(SEC-1)에 맞지 않는다 : 관리자 허용 규칙 없음·밖, 절대 차단 주소(링크 로컬·메타데이터·멀티캐스트 등), 운영자 상한 밖.
     */
    SOURCE_HOST_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "SOURCE_HOST_NOT_ALLOWED"),

    /**
     * 원본 DB 의 메타데이터 조회 시간이 초과되었다.
     */
    SOURCE_QUERY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "SOURCE_QUERY_TIMEOUT"),

    /**
     * 원본 DB 의 메타데이터 조회가 실패했다.
     */
    SOURCE_METADATA_FAILED(HttpStatus.BAD_GATEWAY, "SOURCE_METADATA_FAILED"),

    /**
     * 수집하는 동안 원본의 구조가 바뀌어 일관된 결과를 만들지 못했다.
     */
    SOURCE_CHANGED_DURING_COLLECTION(HttpStatus.CONFLICT, "SOURCE_CHANGED_DURING_COLLECTION"),

    /**
     * 사용자가 수집을 취소했다 (실행 상태의 오류 코드로만 쓴다).
     */
    SOURCE_CANCELLED(HttpStatus.CONFLICT, "SOURCE_CANCELLED"),

    /**
     * 전체 수집 제한 시간을 넘어 중단했다.
     */
    SOURCE_COLLECTION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "SOURCE_COLLECTION_TIMEOUT"),

    /**
     * 수집 작업 대기열이 가득 찼거나 서버가 종료 중이라 작업을 받지 못했다.
     */
    SOURCE_COLLECTION_REJECTED(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_COLLECTION_REJECTED"),

    /**
     * 연결 테스트를 너무 자주 요청했다.
     */
    SOURCE_TEST_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "SOURCE_TEST_RATE_LIMITED"),

    /**
     * 원본 매핑(M-4c)을 확정할 수 없다 : 기준 연결·반영된 수집이 없거나, 대상이 그 시점에 없거나, 스키마 매핑과 맞지 않는 요청.
     */
    SOURCE_MAPPING_INVALID(HttpStatus.BAD_REQUEST, "SOURCE_MAPPING_INVALID"),

    /**
     * 원본 매핑이 1:1 을 깨뜨린다 (이미 다른 원본에 매핑된 모델 스키마·테이블) 또는 확인이 필요한 변경 (기준 연결 변경으로 매핑이 남는다).
     */
    SOURCE_MAPPING_CONFLICT(HttpStatus.CONFLICT, "SOURCE_MAPPING_CONFLICT"),

    /**
     * 원본 비교(M-4d) 요청이 앞서 받은 비교 기준(기준 연결·반영 순서)과 다르다 : 그 사이 기준 연결이 바뀌었거나 새 수집이 반영되었다 (다른 시점의 결과를 섞지 않는다).
     */
    SOURCE_COMPARE_STALE(HttpStatus.CONFLICT, "SOURCE_COMPARE_STALE"),

    /**
     * 원본 메타데이터 가져오기(M-4e) 요청을 처리할 수 없다 : 고른 항목이 없거나, 적용할 수 없는 항목이 있거나, 미리보기 없이 확정했다.
     */
    SOURCE_IMPORT_INVALID(HttpStatus.BAD_REQUEST, "SOURCE_IMPORT_INVALID"),

    /**
     * 외부 연결 허용 규칙(SEC-1)이 올바르지 않다 : 형식·너무 넓은 대역·절대 차단 대역·중복·포트 범위.
     */
    CONNECTION_RULE_INVALID(HttpStatus.BAD_REQUEST, "CONNECTION_RULE_INVALID"),

    /**
     * 외부 연결 허용 규칙(SEC-1)을 찾을 수 없다.
     */
    CONNECTION_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "CONNECTION_RULE_NOT_FOUND"),

    /**
     * 외부 연결(SEC-1, AI HTTP 등)이 관리자 정책으로 차단되었다.
     */
    EXTERNAL_CONNECTION_BLOCKED(HttpStatus.BAD_REQUEST, "EXTERNAL_CONNECTION_BLOCKED"),

    /**
     * 외부 연결 시도를 감사 기록에 남기지 못해 연결하지 않았다 (SEC-1 : 기록 없는 연결을 하지 않는다).
     */
    EXTERNAL_CONNECTION_AUDIT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EXTERNAL_CONNECTION_AUDIT_FAILED"),

    /**
     * AI Provider(AI-3)를 찾을 수 없다.
     */
    AI_PROVIDER_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_PROVIDER_NOT_FOUND"),

    /**
     * AI Provider 설정 값이 올바르지 않다 (이름·주소·모델 ID 등).
     */
    AI_PROVIDER_INVALID(HttpStatus.BAD_REQUEST, "AI_PROVIDER_INVALID"),

    /**
     * 비활성화된 AI Provider 로는 AI 를 호출하지 않는다.
     */
    AI_PROVIDER_DISABLED(HttpStatus.CONFLICT, "AI_PROVIDER_DISABLED"),

    /**
     * 지원하지 않는 AI Provider 유형 또는 기능이다.
     */
    AI_PROVIDER_UNSUPPORTED(HttpStatus.BAD_REQUEST, "AI_PROVIDER_UNSUPPORTED"),

    /**
     * AI 서버 호출에 실패했다 (연결 거부·시간 초과·모델 없음·서버 오류·응답 처리 실패).
     */
    AI_PROVIDER_CALL_FAILED(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_CALL_FAILED"),

    /**
     * 시스템 기본 AI Provider 가 지정되지 않았다 : 기본 Provider 를 쓰는 AI 기능만 쓸 수 없다.
     */
    AI_DEFAULT_PROVIDER_NOT_CONFIGURED(HttpStatus.CONFLICT, "AI_DEFAULT_PROVIDER_NOT_CONFIGURED"),

    /**
     * 시스템 기본 AI Provider 에 기본 모델이 없다 (지정할 수 없고, 지정된 뒤 지워졌으면 호출하지 않는다).
     */
    AI_DEFAULT_PROVIDER_MODEL_MISSING(HttpStatus.CONFLICT, "AI_DEFAULT_PROVIDER_MODEL_MISSING"),

    /**
     * 시스템 기본 AI Provider 로 지정된 Provider 라 삭제할 수 없다.
     */
    AI_PROVIDER_IN_USE(HttpStatus.CONFLICT, "AI_PROVIDER_IN_USE"),

    /**
     * 모델이 Tool Calling 을 지원하지 않는다 (AI-4). 메타데이터 질의는 서버가 맥락을 모으는 대체 흐름으로 처리하므로 이 오류를 내지 않는다 —
     * 대체 흐름이 없는 호출({@code AiProviderClients.call})에서만 난다.
     */
    AI_TOOL_CALLING_UNSUPPORTED(HttpStatus.CONFLICT, "AI_TOOL_CALLING_UNSUPPORTED"),

    /**
     * 한 질의에서 AI 가 Tool 을 허용된 횟수·시간보다 많이 부르려 했다 (AI-4). 결과를 만들지 않고 끝낸다.
     */
    AI_TOOL_CALL_LIMIT_EXCEEDED(HttpStatus.BAD_GATEWAY, "AI_TOOL_CALL_LIMIT_EXCEEDED"),

    /**
     * AI 메타데이터 질의 입력이 올바르지 않다 (질문 없음·너무 김).
     */
    AI_QUERY_INVALID(HttpStatus.BAD_REQUEST, "AI_QUERY_INVALID"),

    /**
     * AI 의 최종 답변이 정해진 형식(JSON Schema)을 따르지 않았다. 한 번 다시 요청해도 맞지 않으면 이 오류로 끝낸다 (형식이 틀린 답변을 보이지 않는다).
     */
    AI_STRUCTURED_OUTPUT_INVALID(HttpStatus.BAD_GATEWAY, "AI_STRUCTURED_OUTPUT_INVALID"),

    /**
     * AI 설명 초안의 문구에 AI 의 자기 평가나 Lexorma 내부 ID 가 들어 있다. 한 번 다시 요청해도 남으면 제안을 만들지 않는다 (서버가 문구를 고치지 않는다, AI-5 프롬프트 v2).
     */
    AI_DESCRIPTION_TEXT_INVALID(HttpStatus.BAD_GATEWAY, "AI_DESCRIPTION_TEXT_INVALID"),

    /**
     * AI 요청이 토큰 예산(모델 입력 한도·응답 한도·질의 전체 누적)을 넘는다 (AI-4). 프롬프트를 잘라 보내지 않고 요청 전에 끝내거나,
     * 응답이 출력 한도에서 잘렸으면 결과를 쓰지 않는다. Provider 오류와 다르다.
     */
    AI_TOKEN_BUDGET_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "AI_TOKEN_BUDGET_EXCEEDED"),

    /**
     * AI 설명 제안이 없다 (다른 프로젝트의 제안도 없는 것과 같다, AI-5).
     */
    AI_PROPOSAL_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_PROPOSAL_NOT_FOUND"),

    /**
     * AI 설명 제안을 다른 사용자가 먼저 처리했다 (화면이 본 버전과 다르다, AI-5). 다시 열어 확인한다.
     */
    AI_PROPOSAL_CONFLICT(HttpStatus.CONFLICT, "AI_PROPOSAL_CONFLICT"),
    BIZ_DESCRIPTION_CONFLICT(HttpStatus.CONFLICT, "BIZ_DESCRIPTION_CONFLICT"),

    /**
     * 컬럼의 표준 용어 연결을 화면을 연 뒤 다른 사용자가 바꿨다 (COL-TERM). 다시 열어 확인한다.
     */
    COLUMN_TERM_CONFLICT(HttpStatus.CONFLICT, "COLUMN_TERM_CONFLICT"),

    /**
     * 지금 상태에서는 할 수 없는 검토다 (이미 승인·반려됨, 재검토 필요 제안의 승인 등, AI-5).
     */
    AI_PROPOSAL_INVALID_STATE(HttpStatus.CONFLICT, "AI_PROPOSAL_INVALID_STATE"),

    /**
     * 이 프로젝트에서 AI 설명 생성 실행이 이미 진행 중이다 (프로젝트당 하나, AI-5).
     */
    AI_JOB_ALREADY_RUNNING(HttpStatus.CONFLICT, "AI_JOB_ALREADY_RUNNING"),

    /**
     * AI 설명 생성 실행 요청이 올바르지 않다 (대상 범위·개수, AI-5).
     */
    AI_JOB_INVALID(HttpStatus.BAD_REQUEST, "AI_JOB_INVALID"),

    /**
     * 서버의 AI 설명 생성 실행기가 가득 찼다 (다른 프로젝트의 실행이 끝난 뒤 다시 시도, AI-5).
     */
    AI_JOB_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "AI_JOB_BUSY"),

    /**
     * AI 설명 평가 대상·판정을 찾을 수 없다 (다른 프로젝트 포함, AI-5 품질 평가).
     */
    AI_EVALUATION_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_EVALUATION_NOT_FOUND"),

    /**
     * 다른 사용자가 평가 대상·판정을 먼저 고쳤다 (버전 불일치, AI-5 품질 평가).
     */
    AI_EVALUATION_CONFLICT(HttpStatus.CONFLICT, "AI_EVALUATION_CONFLICT"),

    /**
     * 평가 요청이 올바르지 않다 (실제 의미를 확인하지 않은 대상의 정확성 판정, 다른 대상의 제안 등, AI-5 품질 평가).
     */
    AI_EVALUATION_INVALID(HttpStatus.BAD_REQUEST, "AI_EVALUATION_INVALID");

    private final HttpStatus httpStatus;
    private final String code;

    /**
     * 요청 언어에 맞는 메시지 : code 를 키로 i18n/errors_{ko,en}.properties 에서 찾는다
     */
    public String getMessage() {
        return Messages.error(code);
    }

}
