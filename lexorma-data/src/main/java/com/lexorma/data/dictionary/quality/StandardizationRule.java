package com.lexorma.data.dictionary.quality;

/**
 * 표준화 규칙 목록 (M-5). 규칙 코드 = enum 이름이며 화면·API·fingerprint 가 이 값을 쓴다 — 이름을 바꾸지 않는다.
 * 규칙의 판정은 대상별 판정 클래스에 있고({@code StandardizationQualityService.diagnose}, {@link TableNameRules}, {@link TermDuplicateRules}),
 * 이 목록은 대상 유형과 기본 심각도만 정한다. 심각도는 3단계로 고정한다 (DEC D8, 프로젝트별 on/off 는 AI-6).
 * <p>판정은 조회할 때 계산하며 저장하지 않는다. AI-6 은 같은 판정 결과({@link Finding})를 저장·비교하는 데 쓴다.
 */
public enum StandardizationRule {

    // ---- 컬럼 (M-5 2차, 판정·메시지는 그대로)
    /** 컬럼이 표준 용어를 참조하지 않는다 */
    TERM_NOT_LINKED(TargetType.COLUMN, Severity.WARNING),
    /** 컬럼명 ≠ 연결된 표준 용어의 영문약어 (등록 규칙 위반) */
    COLUMN_NAME_MISMATCH(TargetType.COLUMN, Severity.ERROR),
    /** 컬럼 타입·길이·정밀도·소수 자릿수 ≠ 표준 도메인 정의 */
    DOMAIN_DEFINITION_MISMATCH(TargetType.COLUMN, Severity.WARNING),

    // ---- 테이블 (D1 : 프로젝트 접두어 + 승인된 표준 단어 약어)
    /** 프로젝트에 접두어가 있는데 테이블명이 그 접두어로 시작하지 않는다 */
    TABLE_PREFIX_MISSING(TargetType.TABLE, Severity.ERROR),
    /** 테이블명(접두어 제외)을 '_' 로 나눈 약어 중 승인된 표준 단어 약어가 아닌 것이 있다 */
    TABLE_WORD_NOT_STANDARD(TargetType.TABLE, Severity.ERROR),

    // ---- 표준 용어 (그룹 단위)
    /** 영문약어 + 도메인이 같은 용어가 여럿 (등록 규칙의 중복 기준) */
    TERM_ABBR_DUPLICATE(TargetType.TERM, Severity.ERROR),
    /** 공백·'_'·대소문자를 무시한 용어명이 같은 용어가 여럿 */
    TERM_NAME_DUPLICATE(TargetType.TERM, Severity.ERROR),
    /** 영문약어는 같고 도메인이 다른 용어 (등록은 허용, 컬럼명·엑셀 업로드에서 용어가 모호하다 — D6) */
    TERM_ABBR_SHARED(TargetType.TERM, Severity.WARNING),
    /** 구성 표준 단어(순서 무시)가 같은 용어 */
    TERM_SAME_WORDS(TargetType.TERM, Severity.INFO),
    /** 이음동의어로 같은 뜻이 되는 용어 (용어 이음동의어, 단어 이음동의어 치환) */
    TERM_SYNONYM(TargetType.TERM, Severity.INFO),
    /** 용어명의 단어가 다른 표준 단어의 금칙어다 */
    TERM_RESTRICTED_WORD(TargetType.TERM, Severity.WARNING),

    // ---- DBMS 식별자 (M-7 보강 1차 — M-5 D3. 판정 = DbIdentifierRules/DbIdentifierCheck, 조회·집계는 /identifiers/* 로 따로 — 기존 요약·모델 진단에 넣지 않는다)
    /** 스키마·테이블·컬럼 이름이 프로젝트 DBMS 의 식별자 길이 기준을 넘는다 */
    IDENTIFIER_LENGTH_EXCEEDED(TargetType.IDENTIFIER, Severity.ERROR),
    /** 프로젝트 DBMS 가 따옴표 없는 식별자로 받지 않는 문자·시작 문자 (DDL 은 따옴표를 쓰지 않는다) */
    IDENTIFIER_INVALID_CHARACTER(TargetType.IDENTIFIER, Severity.ERROR),
    /** DBMS 는 받지만 Lexorma 권장 형식(영문 또는 _ 로 시작, 영문·숫자·_·$)과 다르다 */
    IDENTIFIER_NOT_RECOMMENDED(TargetType.IDENTIFIER, Severity.WARNING),
    /** DDL 이 자동으로 만드는 제약조건명(PK_·UK_·FK_)이 길이 기준을 넘어 잘린다 */
    CONSTRAINT_NAME_TRUNCATED(TargetType.IDENTIFIER, Severity.WARNING),
    /** DDL 의 최종 제약조건·인덱스 이름이 같은 이름 공간에서 겹친다 (자동 이름·잘린 이름이 낀 것) */
    CONSTRAINT_NAME_COLLISION(TargetType.IDENTIFIER, Severity.ERROR);

    /** 진단 대상. WORD 는 AI-6 용으로 남겨 둔다 (지금 단어를 대상으로 하는 규칙은 없다). IDENTIFIER : DDL 이 쓰는 이름 (스키마·테이블·컬럼·제약조건 — 대상 종류는 진단 결과의 kind) */
    public enum TargetType { TABLE, COLUMN, TERM, WORD, IDENTIFIER }

    public enum Severity { ERROR, WARNING, INFO }

    private final TargetType targetType;
    private final Severity severity;

    StandardizationRule(TargetType targetType, Severity severity) {
        this.targetType = targetType;
        this.severity = severity;
    }

    public TargetType targetType() {
        return targetType;
    }

    public Severity severity() {
        return severity;
    }
}
