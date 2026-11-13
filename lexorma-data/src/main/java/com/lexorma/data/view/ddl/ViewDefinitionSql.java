package com.lexorma.data.view.ddl;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.enums.DbType;

import java.util.Locale;
import java.util.Set;

/**
 * VIEW 정의 SQL 의 정적 검사 (M-15 D-23). 실행하지 않고 파싱하지도 않는다 : 문자열·따옴표 식별자·주석을 건너뛰며 낱말과 ; ( ) 만 본다.
 * <ul>
 *   <li>확인하는 것 : 빈 값, 길이, 닫히지 않은 따옴표·주석, 괄호 짝, 문장 하나(끝의 ; 만 허용), SELECT·WITH 로 시작, CREATE VIEW 전체 문장,
 *       <b>문장이 시작할 수 있는 자리</b>의 조회가 아닌 명령 (맨 앞, 여는 괄호 바로 뒤 = 하위 질의·CTE 본문, WITH 의 CTE 목록이 끝난 뒤 = 본 문장),
 *       그리고 어디에서든 INTO (SELECT … INTO 는 테이블·변수에 쓴다)</li>
 *   <li>그 밖의 자리의 낱말은 보지 않는다 (M-15 안정화 D-27) : 별칭·컬럼명이 명령과 같은 이름(AS update), FOR UPDATE·CHARACTER SET 같은 절은 거절하지 않는다.
 *       FOR UPDATE 는 PostgreSQL 18 이 VIEW 정의로 받는다 (실행으로 확인). 다른 DBMS 에서 되는지는 DDL 을 실행할 때 DB 가 판단한다</li>
 *   <li>보장하지 않는 것 : 문법, 참조 테이블·컬럼의 존재, 출력 컬럼·타입, DBMS 호환성, 읽기 전용임의 완전한 증명 (함수 호출 안의 부작용 등)</li>
 * </ul>
 * 원문은 그대로 저장한다 (대소문자·공백·주석을 바꾸지 않는다). DDL 에는 끝의 ; 와 그 뒤를 뺀 본문({@link Checked#body})을 쓴다.
 */
public final class ViewDefinitionSql {

    public static final int MAX_LENGTH = 100_000;

    /**
     * 문장이 시작할 수 있는 자리에 오면 거절하는 명령. REPLACE 는 문자열 함수(괄호 바로 뒤에도 온다)와 같은 이름이라 넣지 않는다 (MySQL 의 REPLACE INTO 는 INTO 로 걸린다)
     */
    private static final Set<String> COMMANDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "DROP", "ALTER", "CREATE", "TRUNCATE",
            "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "CALL", "EXEC", "EXECUTE", "COPY", "LOCK", "VACUUM", "ANALYZE", "SET", "DO");
    /** 자리와 관계없이 거절하는 낱말 : SELECT … INTO (테이블을 만들거나 변수에 쓴다), INSERT·MERGE·REPLACE INTO */
    private static final String INTO = "INTO";

    private ViewDefinitionSql() {
    }

    /**
     * @param body                 DDL 에 넣을 본문 : 끝의 ; (와 그 뒤 공백·주석)와 뒤쪽 공백을 뺀 원문
     * @param endsWithLineComment  본문이 -- 주석으로 끝난다 (DDL 의 ; 를 다음 줄에 둬야 주석에 묻히지 않는다)
     */
    public record Checked(String body, boolean endsWithLineComment) {
    }

    /** 검사하고 DDL 본문을 돌려준다. 문제가 있으면 INVALID_PHYSICAL_MODEL */
    public static Checked check(DbType dbType, String sql) {
        if (sql == null || sql.isBlank()) throw invalid("정의 SQL 을 입력해주세요.");
        if (sql.length() > MAX_LENGTH) throw invalid("정의 SQL 은 {0}자까지 입력할 수 있습니다.", MAX_LENGTH);
        Scan scan = scan(dbType, sql);
        if (scan.unclosed) throw invalid("정의 SQL 의 따옴표나 주석(/* */)이 닫히지 않았습니다.");
        if (scan.unbalanced) throw invalid("정의 SQL 의 괄호 짝이 맞지 않습니다.");
        if (scan.extraStatement) throw invalid("정의 SQL 에는 문장 하나만 입력할 수 있습니다. (; 뒤에 다른 문장이 있습니다)");
        if (scan.firstWord == null) throw invalid("정의 SQL 을 입력해주세요.");
        if (scan.firstWord.equals("CREATE")) throw invalid("CREATE VIEW 문 전체가 아니라 AS 뒤의 조회 문(SELECT / WITH …)만 입력해주세요.");
        if (!scan.firstWord.equals("SELECT") && !scan.firstWord.equals("WITH")) throw invalid("정의 SQL 은 SELECT 또는 WITH 로 시작하는 조회 문이어야 합니다.");
        if (scan.forbidden != null) throw invalid("정의 SQL 에 조회가 아닌 명령({0})이 들어 있습니다. 조회 문만 입력해주세요.", scan.forbidden);

        String body = (scan.firstSemicolon < 0 ? sql : sql.substring(0, scan.firstSemicolon)).stripTrailing();
        return new Checked(body, scan(dbType, body).inLineCommentAtEnd);
    }

    /** 한 번 훑은 결과 */
    private static final class Scan {
        boolean unclosed;
        boolean unbalanced;
        boolean extraStatement;
        boolean inLineCommentAtEnd;
        String firstWord;
        String forbidden;
        /** 맨 바깥의 첫 ; 위치 (없으면 -1) */
        int firstSemicolon = -1;
    }

    private static Scan scan(DbType dbType, String sql) {
        boolean mysql = dbType == DbType.MYSQL || dbType == DbType.MARIADB;
        boolean postgres = dbType == DbType.POSTGRESQL;
        Scan result = new Scan();
        int depth = 0;
        int n = sql.length();
        int i = 0;
        char previous = ' '; // 직전의 의미 있는 글자 (qualified 이름의 . 확인)
        boolean commandPosition = true; // 다음 낱말이 문장의 첫 낱말일 수 있는 자리 (맨 앞, '(' 바로 뒤, WITH 의 CTE 목록 끝)
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-' || mysql && c == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    result.inLineCommentAtEnd = true;
                    break;
                }
                i = end + 1;
                continue;
            }
            if (c == '/' && next == '*') {
                // PostgreSQL 은 블록 주석을 겹쳐 쓸 수 있다
                int nested = 1;
                i += 2;
                while (i < n && nested > 0) {
                    if (sql.startsWith("*/", i)) { nested--; i += 2; }
                    else if (postgres && sql.startsWith("/*", i)) { nested++; i += 2; }
                    else i++;
                }
                if (nested > 0) result.unclosed = true;
                continue;
            }
            if (c == '\'' || c == '"' || mysql && c == '`') {
                if (result.firstSemicolon >= 0) { result.extraStatement = true; break; }
                int end = closing(sql, i, c, mysql && c != '`');
                if (end < 0) { result.unclosed = true; break; }
                i = end + 1;
                previous = 'x';
                commandPosition = false;
                continue;
            }
            if (postgres && c == '$' && (i == 0 || !isWordChar(sql.charAt(i - 1)))) {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0) {
                    if (result.firstSemicolon >= 0) { result.extraStatement = true; break; }
                    String tag = sql.substring(i, tagEnd + 1);
                    int end = sql.indexOf(tag, tagEnd + 1);
                    if (end < 0) { result.unclosed = true; break; }
                    i = end + tag.length();
                    previous = 'x';
                    commandPosition = false;
                    continue;
                }
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (result.firstSemicolon >= 0 && c != ';') {
                // 끝의 ; 뒤에는 공백·주석만 둘 수 있다
                result.extraStatement = true;
                break;
            }
            if (c == ';') {
                if (depth != 0) result.unbalanced = true;
                if (result.firstSemicolon < 0) result.firstSemicolon = i;
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && isWordChar(sql.charAt(i))) i++;
                String word = sql.substring(start, i).toUpperCase(Locale.ROOT);
                if (result.firstWord == null) result.firstWord = word;
                if (result.forbidden == null && previous != '.' && (commandPosition && COMMANDS.contains(word) || word.equals(INTO))) result.forbidden = word;
                previous = 'x';
                commandPosition = false;
                continue;
            }
            if (c == '(') {
                depth++;
                commandPosition = true;
            } else if (c == ')') {
                if (--depth < 0) result.unbalanced = true;
                // WITH a AS (…), b AS (…) 다음의 본 문장
                commandPosition = depth == 0 && "WITH".equals(result.firstWord);
            } else {
                commandPosition = false;
            }
            previous = c;
            i++;
        }
        if (depth != 0) result.unbalanced = true;
        return result;
    }

    /** 따옴표가 닫히는 위치 (같은 따옴표 두 개는 이어진 글자, backslash : MySQL 문자열의 이스케이프). 없으면 -1 */
    private static int closing(String sql, int open, char quote, boolean backslash) {
        for (int i = open + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (backslash && c == '\\') { i++; continue; }
            if (c != quote) continue;
            if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { i++; continue; }
            return i;
        }
        return -1;
    }

    /** $태그$ 의 끝 $ 위치 (태그는 비거나 식별자). 달러 인용이 아니면 -1 */
    private static int dollarTagEnd(String sql, int start) {
        int i = start + 1;
        if (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        }
        return i < sql.length() && sql.charAt(i) == '$' ? i : -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }
}
