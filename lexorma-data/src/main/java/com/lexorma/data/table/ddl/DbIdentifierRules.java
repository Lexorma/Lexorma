package com.lexorma.data.table.ddl;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.enums.DbType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DBMS 식별자 규칙 (M-7 보강 1차, P12·P13·P14). DDL 생성기({@link TableDdlGenerator})가 이름을 만들고 자르는 규칙도 여기에 둔다 — 검사와 DDL 이 같은 값을 쓴다.
 * <ul>
 *   <li>길이 : PostgreSQL 63바이트(UTF-8, 넘으면 DBMS 가 잘라서 만든다), Oracle 30바이트(12.1 이하 기준 — 12.2 이상은 128바이트지만 DDL 생성기와 같게 30, P14),
 *       MySQL·MariaDB 64자 (데이터베이스·테이블·컬럼·인덱스·제약조건 모두 64)</li>
 *   <li>문자 : DDL 은 이름을 따옴표 없이 쓰므로 DBMS 의 비인용 식별자 규칙으로 판정한다 ({@link Code#IDENTIFIER_INVALID_CHARACTER}).
 *       DBMS 는 받지만 Lexorma 권장 형식(영문 또는 _ 로 시작, 영문·숫자·_·$)과 다른 이름은 따로 알린다 ({@link Code#IDENTIFIER_NOT_RECOMMENDED})</li>
 *   <li>자르기 : DDL 생성기는 제약조건·인덱스 이름이 길이 기준을 넘으면 앞에서부터 자른다 ({@link #ddlName}, 문자 수 기준 — 지금 DDL 과 같다).
 *       자동으로 만드는 이름(PK_·UK_·FK_)이 잘리면 {@link Code#CONSTRAINT_NAME_TRUNCATED}, 잘린 최종 이름이 같은 이름 공간에서 겹치면 {@link Code#CONSTRAINT_NAME_COLLISION} ({@link DbIdentifierCheck})</li>
 * </ul>
 * 예약어는 검사하지 않는다 (P13). 값만 판정하고 아무것도 고치지 않는다. 판정 기준을 바꾸면 {@link #VERSION} 을 올린다 (생성 계획 planToken 에 들어간다).
 */
public final class DbIdentifierRules {

    public static final String VERSION = "1";

    /** 식별자 대상 유형 */
    public enum Kind { SCHEMA, TABLE, COLUMN, PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, INDEX }

    /** 규칙 코드 (화면·API·M-5 규칙 코드와 같은 이름) */
    public enum Code { IDENTIFIER_LENGTH_EXCEEDED, IDENTIFIER_INVALID_CHARACTER, IDENTIFIER_NOT_RECOMMENDED, CONSTRAINT_NAME_TRUNCATED, CONSTRAINT_NAME_COLLISION }

    /** max : 허용 길이, bytes : UTF-8 바이트로 센다 (false 면 문자 수) */
    public record Limit(int max, boolean bytes) {
    }

    /** 이름 하나의 판정 결과. length·maxLength 는 길이 규칙일 때만 (DBMS 기준의 단위) */
    public record Issue(Code code, Kind kind, String name, String ddlName, Integer length, Integer maxLength) {
    }

    /** Lexorma 권장 형식 (따옴표 없이 모든 대상 DBMS 에서 같은 이름이 된다) */
    private static final Pattern RECOMMENDED = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");
    /** PostgreSQL 비인용 식별자 : 문자(비라틴 포함) 또는 _ 로 시작, 문자·숫자(0-9)·_·$ */
    private static final Pattern POSTGRESQL = Pattern.compile("^[\\p{L}_][\\p{L}0-9_$]*$");
    /** Oracle 비인용 식별자 : 문자로 시작(_ 로 시작할 수 없다), 문자·숫자·_·$·# */
    private static final Pattern ORACLE = Pattern.compile("^\\p{L}[\\p{L}0-9_$#]*$");
    /** MySQL·MariaDB 비인용 식별자 : 0-9 a-z A-Z $ _ 와 U+0080..U+FFFF (숫자만으로는 안 된다) */
    private static final Pattern MYSQL = Pattern.compile("^[0-9A-Za-z$_\\u0080-\\uFFFF]+$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    private DbIdentifierRules() {
    }

    public static Limit limit(DbType dbType, Kind kind) {
        return switch (DbType.orDefault(dbType)) {
            case POSTGRESQL -> new Limit(63, true);
            case ORACLE -> new Limit(30, true);
            case MYSQL, MARIADB -> new Limit(64, false);
        };
    }

    /** DBMS 기준의 길이 (PostgreSQL·Oracle 은 UTF-8 바이트, MySQL·MariaDB 는 문자 수) */
    public static int length(DbType dbType, Kind kind, String name) {
        return limit(dbType, kind).bytes() ? name.getBytes(StandardCharsets.UTF_8).length : name.codePointCount(0, name.length());
    }

    /** DDL 에 쓰는 제약조건·인덱스 이름 : 길이 기준(문자 수)을 넘으면 앞에서부터 자른다 (DDL 생성기의 기존 동작) */
    public static String ddlName(DbType dbType, String name) {
        int max = limit(dbType, Kind.INDEX).max();
        return name.length() > max ? name.substring(0, max) : name;
    }

    /** DDL 이 만드는 PK 제약조건명 */
    public static String primaryKeyName(String tableName) {
        return "PK_" + tableName;
    }

    /** DDL 이 고유값 컬럼마다 만드는 UNIQUE 제약조건명 */
    public static String uniqueName(String tableName, String columnName) {
        return "UK_" + tableName + "_" + columnName;
    }

    /** DDL 이 참조 테이블명(단일 FK 약식 입력) 컬럼마다 만드는 FK 제약조건명 */
    public static String foreignKeyName(String tableName, String columnName) {
        return "FK_" + tableName + "_" + columnName;
    }

    /** 스키마·테이블·컬럼 이름의 길이·문자 판정 (비었으면 없음) */
    public static List<Issue> check(DbType dbType, Kind kind, String name) {
        List<Issue> issues = new ArrayList<>();
        if (name == null || name.isBlank()) return issues;
        DbType db = DbType.orDefault(dbType);
        if (!validUnquoted(db, name)) {
            issues.add(new Issue(Code.IDENTIFIER_INVALID_CHARACTER, kind, name, null, null, null));
        } else if (!RECOMMENDED.matcher(name).matches()) {
            issues.add(new Issue(Code.IDENTIFIER_NOT_RECOMMENDED, kind, name, null, null, null));
        }
        Limit limit = limit(db, kind);
        int length = length(db, kind, name);
        if (length > limit.max()) {
            issues.add(new Issue(Code.IDENTIFIER_LENGTH_EXCEEDED, kind, name, null, length, limit.max()));
        }
        return issues;
    }

    /** 자동으로 만든 제약조건명이 DDL 에서 잘리는가. 잘리면 판정 1건, 아니면 null */
    public static Issue truncation(DbType dbType, Kind kind, String generatedName) {
        String ddl = ddlName(dbType, generatedName);
        if (ddl.equals(generatedName)) return null;
        return new Issue(Code.CONSTRAINT_NAME_TRUNCATED, kind, generatedName, ddl, generatedName.length(), limit(dbType, kind).max());
    }

    /** DBMS 의 비인용 식별자로 쓸 수 있는가 */
    public static boolean validUnquoted(DbType dbType, String name) {
        return switch (DbType.orDefault(dbType)) {
            case POSTGRESQL -> POSTGRESQL.matcher(name).matches();
            case ORACLE -> ORACLE.matcher(name).matches();
            case MYSQL, MARIADB -> MYSQL.matcher(name).matches() && !DIGITS.matcher(name).matches();
        };
    }

    /** Lexorma 권장 형식인가 */
    public static boolean recommended(String name) {
        return name != null && RECOMMENDED.matcher(name).matches();
    }

    // ------------------------------------------------------------------ 화면 문구

    /** 길이 기준 설명 (예: "PostgreSQL : 63바이트 (UTF-8)") */
    public static String lengthBasis(DbType dbType) {
        DbType db = DbType.orDefault(dbType);
        return switch (db) {
            case POSTGRESQL -> Messages.get("PostgreSQL : 63바이트 (UTF-8, 넘으면 DBMS 가 잘라서 만듭니다)");
            case ORACLE -> Messages.get("Oracle : 30바이트 (12.1 이하 기준 — 12.2 이상은 128바이트, DDL 생성 기준과 같음)");
            case MYSQL -> Messages.get("MySQL : 64자");
            case MARIADB -> Messages.get("MariaDB : 64자");
        };
    }

    /** 문자 기준 설명 */
    public static String characterBasis(DbType dbType) {
        return switch (DbType.orDefault(dbType)) {
            case POSTGRESQL -> Messages.get("PostgreSQL 비인용 식별자 : 문자 또는 _ 로 시작, 문자·숫자·_·$");
            case ORACLE -> Messages.get("Oracle 비인용 식별자 : 문자로 시작, 문자·숫자·_·$·#");
            case MYSQL -> Messages.get("MySQL 비인용 식별자 : 영문·숫자·$·_·U+0080 이상 문자 (숫자만은 불가)");
            case MARIADB -> Messages.get("MariaDB 비인용 식별자 : 영문·숫자·$·_·U+0080 이상 문자 (숫자만은 불가)");
        };
    }

    /** 판정 근거 (규칙별) */
    public static String basis(DbType dbType, Code code) {
        return switch (code) {
            case IDENTIFIER_LENGTH_EXCEEDED, CONSTRAINT_NAME_TRUNCATED -> lengthBasis(dbType);
            case IDENTIFIER_INVALID_CHARACTER -> characterBasis(dbType);
            case IDENTIFIER_NOT_RECOMMENDED -> Messages.get("Lexorma 권장 형식 : 영문 또는 _ 로 시작, 영문·숫자·_·$");
            case CONSTRAINT_NAME_COLLISION -> namespaceBasis(dbType);
        };
    }

    /** 제약조건·인덱스 이름 공간 설명 ({@link DbIdentifierCheck#sameNamespace} 와 같은 기준) */
    public static String namespaceBasis(DbType dbType) {
        return switch (DbType.orDefault(dbType)) {
            case POSTGRESQL -> Messages.get("PostgreSQL : PK·UNIQUE·인덱스는 스키마 안에서, 제약조건은 테이블 안에서 이름이 하나여야 합니다");
            case ORACLE -> Messages.get("Oracle : 제약조건과 인덱스는 각각 스키마 안에서 이름이 하나여야 합니다 (PK·UNIQUE 는 같은 이름의 인덱스를 만듭니다)");
            case MYSQL -> Messages.get("MySQL : FK·CHECK 는 스키마 안에서, UNIQUE·인덱스는 테이블 안에서 이름이 하나여야 합니다 (PK 이름은 쓰지 않습니다)");
            case MARIADB -> Messages.get("MariaDB : FK 는 스키마 안에서, UNIQUE·인덱스·CHECK 는 테이블 안에서 이름이 하나여야 합니다 (PK 이름은 쓰지 않습니다)");
        };
    }

    /** 위반 사유 */
    public static String reason(DbType dbType, Issue issue, List<String> others) {
        String db = DbType.orDefault(dbType).getLabel();
        return switch (issue.code()) {
            case IDENTIFIER_LENGTH_EXCEEDED -> Messages.get("{0} 이(가) {1} 식별자 길이 기준({2})을 넘습니다. (현재 {3})", issue.name(), db, issue.maxLength(), issue.length());
            case IDENTIFIER_INVALID_CHARACTER -> Messages.get("{0} 은(는) {1} 에서 따옴표 없는 식별자로 쓸 수 없는 문자나 시작 문자가 있습니다.", issue.name(), db);
            case IDENTIFIER_NOT_RECOMMENDED -> Messages.get("{0} 은(는) {1} 에서 쓸 수 있지만 Lexorma 권장 형식과 다릅니다.", issue.name(), db);
            case CONSTRAINT_NAME_TRUNCATED -> Messages.get("DDL 의 제약조건명 {0} 이(가) {1} 길이 기준({2})을 넘어 {3} 으로 잘립니다.", issue.name(), db, issue.maxLength(), issue.ddlName());
            case CONSTRAINT_NAME_COLLISION -> Messages.get("DDL 의 최종 이름 {0} 이(가) 같은 이름 공간의 {1} 와(과) 겹칩니다.", issue.ddlName(), String.join(", ", others));
        };
    }

    /** 대상 유형 이름 */
    public static String kindLabel(Kind kind) {
        return switch (kind) {
            case SCHEMA -> Messages.get("스키마");
            case TABLE -> Messages.get("테이블");
            case COLUMN -> Messages.get("컬럼");
            case PRIMARY_KEY -> "PK";
            case UNIQUE -> "UNIQUE";
            case FOREIGN_KEY -> "FK";
            case CHECK -> "CHECK";
            case INDEX -> Messages.get("인덱스");
        };
    }

    /** 개선 안내 */
    public static String guide(Code code) {
        return switch (code) {
            case IDENTIFIER_LENGTH_EXCEEDED -> Messages.get("더 짧은 표준 단어 약어를 쓰거나 이름을 줄여주세요.");
            case IDENTIFIER_INVALID_CHARACTER, IDENTIFIER_NOT_RECOMMENDED -> Messages.get("영문으로 시작하고 영문·숫자·_ 만 쓰도록 이름(표준 단어·용어 약어)을 고쳐주세요.");
            case CONSTRAINT_NAME_TRUNCATED -> Messages.get("테이블명·컬럼명을 줄이면 잘리지 않습니다. 잘린 이름이 겹치지 않는지 확인해주세요.");
            case CONSTRAINT_NAME_COLLISION -> Messages.get("겹치는 테이블명·컬럼명이나 제약조건·인덱스 이름을 바꿔주세요. 이대로면 DDL 실행이 실패할 수 있습니다.");
        };
    }
}
