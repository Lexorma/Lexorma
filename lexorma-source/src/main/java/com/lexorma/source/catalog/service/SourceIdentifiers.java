package com.lexorma.source.catalog.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;

import java.util.Locale;

/**
 * 원본 식별자의 비교용 이름 (AI 로드맵 U-24). 원본이 준 이름은 그대로 저장하고, 같은 객체인지는 여기서 만든 값으로 판단한다.
 * <ul>
 *   <li><b>PostgreSQL</b> : 따옴표 없는 식별자는 소문자로 접힌다. 따옴표 식별자는 쓴 그대로다</li>
 *   <li><b>Oracle</b> : 따옴표 없는 식별자는 대문자로 접힌다. 따옴표 식별자는 쓴 그대로다</li>
 *   <li><b>MySQL·MariaDB</b> : 컬럼명은 항상 대소문자를 구분하지 않는다. 데이터베이스·테이블명은 서버 설정(lower_case_table_names)에 달려 있어
 *       연결에 정한 규칙을 따른다 — 구분하지 않는다고 정한 연결만 소문자로 접고, 모르면(DBMS_DEFAULT) 접지 않는다 (다른 객체를 임의로 합치지 않는다)</li>
 * </ul>
 * <b>따옴표 여부를 모를 때(null)</b> 는 이름을 접지 않는다. DBMS 의 카탈로그가 돌려주는 이름은 이미 접힌 뒤의 이름이므로
 * (PostgreSQL 의 {@code users}, Oracle 의 {@code USERS}) 그대로 비교하면 되고, 이름의 모양만 보고 따옴표 여부를 단정하지 않는다.
 * 수집 결과가 "따옴표 없이 쓴 이름" 이라고 알려 준 경우(false)에만 DBMS 규칙으로 접는다 : PostgreSQL 의 {@code USERS}(따옴표 없음)와 {@code users} 는 같은 객체다.
 */
public final class SourceIdentifiers {

    public enum Kind {
        /** 카탈로그(데이터베이스)·스키마 */
        NAMESPACE,
        /** 테이블·뷰 */
        TABLE,
        COLUMN
    }

    private SourceIdentifiers() {
    }

    /**
     * @param quoted 따옴표 식별자인지. 모르면 null
     */
    public static String compareName(DbType dbType, IdentifierComparison comparison, Kind kind, String name, Boolean quoted) {
        if (name == null) return null;
        return switch (dbType) {
            case POSTGRESQL -> Boolean.FALSE.equals(quoted) ? name.toLowerCase(Locale.ROOT) : name;
            case ORACLE -> Boolean.FALSE.equals(quoted) ? name.toUpperCase(Locale.ROOT) : name;
            case MYSQL, MARIADB -> kind == Kind.COLUMN || comparison == IdentifierComparison.CASE_INSENSITIVE ? name.toLowerCase(Locale.ROOT) : name;
        };
    }

    /**
     * 연결 안에서 이름 공간(카탈로그 + 스키마)을 가리키는 경로. 한쪽이 없어도 NULL 이 아닌 한 값이 되고,
     * 길이를 앞에 적어 서로 다른 조합이 같은 값이 되지 않는다 : (카탈로그 app, 스키마 없음) = {@code 3:app/0:}, (없음, app) = {@code 0:/3:app}.
     */
    public static String pathKey(String catalogCompareName, String schemaCompareName) {
        String catalog = catalogCompareName == null ? "" : catalogCompareName;
        String schema = schemaCompareName == null ? "" : schemaCompareName;
        return catalog.length() + ":" + catalog + "/" + schema.length() + ":" + schema;
    }

    /** 경로에서 카탈로그 부분을 꺼낸다 (없으면 null). 같은 카탈로그의 다른 스키마를 찾을 때 쓴다 */
    public static String catalogOf(String pathKey) {
        int colon = pathKey.indexOf(':');
        int length = Integer.parseInt(pathKey.substring(0, colon));
        return length == 0 ? null : pathKey.substring(colon + 1, colon + 1 + length);
    }

    /** 이 DBMS 에서 쓸 수 있는 비교 규칙인지 : 규칙을 고르는 것은 MySQL·MariaDB 뿐이다 */
    public static boolean supports(DbType dbType, IdentifierComparison comparison) {
        return comparison == IdentifierComparison.DBMS_DEFAULT || dbType == DbType.MYSQL || dbType == DbType.MARIADB;
    }
}
