package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 저장된 SQL 조각(VIEW 정의·CHECK 식·인덱스 식/조건)에 <b>나올 수 있는</b> 식별자 낱말을 모은다 (M-7 물리명 추천 일괄 적용 1차 — 이름 변경 안전성 판정용).
 * 파서가 아니라 낱말 분리기다 : {@link com.lexorma.data.view.ddl.ViewDefinitionSql} 과 같은 규칙으로 문자열·주석을 건너뛰고, 남은 낱말과 따옴표 식별자를 대문자로 모은다.
 * <ul>
 *   <li>보수적이다 : 함수명·키워드·별칭·다른 테이블의 같은 이름도 모두 낱말로 남는다 → "이 이름이 나오지 않는다" 만 확실하게 말할 수 있다 (나오면 참조로 본다).</li>
 *   <li>건너뛰는 것 : 작은따옴표 문자열('' 이스케이프, MySQL·MariaDB 의 \ 이스케이프, PostgreSQL 의 E'…' \ 이스케이프·$태그$ 문자열, Oracle 의 q'[…]' 문자열),
 *       주석(-- 줄 끝까지, MySQL·MariaDB 의 #, /* *&#47; — PostgreSQL 은 겹쳐 쓸 수 있다)</li>
 *   <li>따옴표 식별자 "…"(MySQL·MariaDB 의 `…` 포함)는 내용을 대문자로 낱말에 넣는다 (대소문자를 구분하는 식별자도 같은 이름으로 본다 — 보수적).
 *       MySQL 의 "…" 는 기본 설정에서 문자열이지만 식별자로 본다 (보수적).</li>
 *   <li>판정할 수 없음({@link Tokens#opaque}) : 닫히지 않은 따옴표·주석, PostgreSQL 유니코드 이스케이프 식별자 U&amp;"…" (원래 이름을 알 수 없다).
 *       이 결과는 "참조하지 않는다" 를 보장하지 못하므로 쓰는 쪽이 적용 불가로 다뤄야 한다.</li>
 *   <li>{@link Tokens#star} : 문자열·주석 밖의 {@code *} (SELECT * 와 곱셈을 구분하지 않는다), {@link Tokens#natural} : NATURAL 낱말 (NATURAL JOIN 은 이름 없이 같은 이름의 컬럼으로 잇는다)</li>
 * </ul>
 * 값만 판정하고 아무것도 고치지 않는다.
 */
public final class SqlIdentifierTokens {

    /** identifiers : 대문자 낱말·따옴표 식별자 내용. opaque 이면 identifiers 를 믿을 수 없다 */
    public record Tokens(Set<String> identifiers, boolean star, boolean natural, boolean opaque) {
        public static final Tokens EMPTY = new Tokens(Set.of(), false, false, false);

        /** 이름(대소문자·앞뒤 공백 무시)이 낱말로 나오는가 */
        public boolean mentions(String name) {
            return name != null && !name.isBlank() && identifiers.contains(name.trim().toUpperCase(Locale.ROOT));
        }

        /** 두 결과를 합친다 (VIEW 의 지금 정의 + 처리 전 변경 요청 정의) */
        public Tokens merge(Tokens other) {
            Set<String> all = new HashSet<>(identifiers);
            all.addAll(other.identifiers);
            return new Tokens(Collections.unmodifiableSet(all), star || other.star, natural || other.natural, opaque || other.opaque);
        }
    }

    private SqlIdentifierTokens() {
    }

    public static Tokens scan(DbType dbType, String sql) {
        if (sql == null || sql.isBlank()) return Tokens.EMPTY;
        DbType db = DbType.orDefault(dbType);
        boolean mysql = db == DbType.MYSQL || db == DbType.MARIADB;
        boolean postgres = db == DbType.POSTGRESQL;
        boolean oracle = db == DbType.ORACLE;
        Set<String> identifiers = new HashSet<>();
        boolean star = false;
        boolean natural = false;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            // 주석
            if (c == '-' && next == '-' || mysql && c == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) break;
                i = end + 1;
                continue;
            }
            if (c == '/' && next == '*') {
                int nested = 1;
                i += 2;
                while (i < n && nested > 0) {
                    if (sql.startsWith("*/", i)) { nested--; i += 2; }
                    else if (postgres && sql.startsWith("/*", i)) { nested++; i += 2; }
                    else i++;
                }
                if (nested > 0) return opaque(identifiers, star, natural);
                continue;
            }
            // 문자열
            if (c == '\'') {
                int end = closing(sql, i, '\'', mysql);
                if (end < 0) return opaque(identifiers, star, natural);
                i = end + 1;
                continue;
            }
            if (postgres && c == '$' && (i == 0 || !isWordChar(sql.charAt(i - 1)))) {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd > 0) {
                    String tag = sql.substring(i, tagEnd + 1);
                    int end = sql.indexOf(tag, tagEnd + 1);
                    if (end < 0) return opaque(identifiers, star, natural);
                    i = end + tag.length();
                    continue;
                }
            }
            // 따옴표 식별자
            if (c == '"' || mysql && c == '`') {
                int end = closing(sql, i, c, false);
                if (end < 0) return opaque(identifiers, star, natural);
                String content = sql.substring(i + 1, end).replace(String.valueOf(c) + c, String.valueOf(c));
                identifiers.add(content.trim().toUpperCase(Locale.ROOT));
                i = end + 1;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && isWordChar(sql.charAt(i))) i++;
                String word = sql.substring(start, i).toUpperCase(Locale.ROOT);
                char after = i < n ? sql.charAt(i) : '\0';
                // 접두어가 붙은 문자열·식별자
                if (postgres && after == '&' && word.equals("U") && i + 1 < n && sql.charAt(i + 1) == '"') {
                    return opaque(identifiers, star, natural); // 유니코드 이스케이프 식별자 : 원래 이름을 알 수 없다
                }
                if (after == '\'') {
                    if (postgres && word.equals("E")) {
                        int end = closing(sql, i, '\'', true);
                        if (end < 0) return opaque(identifiers, star, natural);
                        i = end + 1;
                        continue;
                    }
                    if (oracle && (word.equals("Q") || word.equals("NQ"))) {
                        int end = oracleQuoteEnd(sql, i);
                        if (end < 0) return opaque(identifiers, star, natural);
                        i = end + 1;
                        continue;
                    }
                }
                identifiers.add(word);
                if (word.equals("NATURAL")) natural = true;
                continue;
            }
            if (c == '*') star = true;
            i++;
        }
        return new Tokens(Collections.unmodifiableSet(identifiers), star, natural, false);
    }

    private static Tokens opaque(Set<String> identifiers, boolean star, boolean natural) {
        return new Tokens(Collections.unmodifiableSet(identifiers), star, natural, true);
    }

    /** 따옴표가 닫히는 위치 (같은 따옴표 두 개는 이어진 글자, backslash : 문자열의 \ 이스케이프). 없으면 -1 — ViewDefinitionSql 과 같은 규칙 */
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

    /** Oracle q'X…X' (quote 는 ' 의 위치). 여는 글자 [ ( { < 는 짝 글자로 닫는다. 끝 ' 의 위치, 없으면 -1 */
    private static int oracleQuoteEnd(String sql, int quote) {
        if (quote + 1 >= sql.length()) return -1;
        char open = sql.charAt(quote + 1);
        char close = switch (open) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> open;
        };
        if (Character.isWhitespace(open)) return -1;
        int end = sql.indexOf(String.valueOf(close) + '\'', quote + 2);
        return end < 0 ? -1 : end + 1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }
}
