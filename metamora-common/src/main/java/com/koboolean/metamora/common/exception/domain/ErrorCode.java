package com.koboolean.metamora.common.exception.domain;

import com.koboolean.metamora.common.i18n.Messages;
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
     * 테이블 한글명 중 승인된 표준 단어가 아닌 단어가 존재합니다.
     */
    TABLE_WORD_NOT_STANDARD(HttpStatus.BAD_REQUEST, "TABLE_WORD_NOT_STANDARD"),

    /**
     * 테이블 한글명을 입력해주세요.
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
     * 관계·인덱스·제약조건 정의 오류 (사유는 customMessage)
     */
    INVALID_PHYSICAL_MODEL(HttpStatus.BAD_REQUEST, "INVALID_PHYSICAL_MODEL"),

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
    PASSWORD_NOT_CHANGED(HttpStatus.BAD_REQUEST, "PASSWORD_NOT_CHANGED");

    private final HttpStatus httpStatus;
    private final String code;

    /**
     * 요청 언어에 맞는 메시지 : code 를 키로 i18n/errors_{ko,en}.properties 에서 찾는다
     */
    public String getMessage() {
        return Messages.error(code);
    }

}
