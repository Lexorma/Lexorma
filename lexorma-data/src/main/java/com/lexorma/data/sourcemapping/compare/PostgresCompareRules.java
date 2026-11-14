package com.lexorma.data.sourcemapping.compare;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원본(PostgreSQL) ↔ 모델 비교의 타입·식 정규화 (M-4d, docs/source-compare-m4d.md 4장). PostgreSQL 원본 → PostgreSQL 대상 모델만 다룬다 (정책 5.8).
 * <ul>
 *   <li>타입 : 원본의 format_type 표현과 모델 타입을 PostgreSQL DDL 로 바꾼 표현(TableDdlGenerator.mapType)을 같은 규칙으로 (기본 타입, 길이, 정밀도, 소수 자릿수) 로 나눈다.
 *       별칭(int4·character varying …)만 같게 보고, 의미가 다른 타입(REAL ↔ DOUBLE PRECISION, TIMESTAMP ↔ TIMESTAMPTZ)은 다르게 본다. 모르는 타입은 known = false</li>
 *   <li>식(DEFAULT·CHECK·인덱스 식·조건) : 안전한 정규화만 한다 — 리터럴에 붙은 형 변환(::type), 숫자 리터럴을 감싼 괄호·따옴표, 바깥 괄호, 따옴표 밖의 대소문자·공백.
 *       그 밖의 차이(함수 이름, 연산자 모양)는 의미가 같은지 판단하지 않는다 : 부르는 쪽이 UNKNOWN 으로 둔다</li>
 * </ul>
 * 다른 DBMS 는 DBMS 확대 단계에서 같은 모양의 규칙을 더한다 (지금 비교는 PostgreSQL 연결만 한다).
 */
public final class PostgresCompareRules {

    private PostgresCompareRules() {
    }

    /** 자동 증가 : 시퀀스 기본값(nextval, SERIAL) · IDENTITY */
    public enum AutoIncrement { SEQUENCE, IDENTITY_ALWAYS, IDENTITY_BY_DEFAULT }

    /**
     * @param base      정규화한 기본 타입 (VARCHAR, NUMERIC, TIMESTAMPTZ …). 모르는 타입은 원문을 대문자로
     * @param known     별칭 규칙을 아는 타입인지 (모르면 원문 비교만 한다)
     * @param serial    SERIAL 계열 (정수 + 시퀀스 기본값)
     */
    public record PgType(String base, Integer length, Integer precision, Integer scale, int arrayDims, boolean known, boolean serial) {

        /** 화면에 보일 표현 */
        public String display() {
            StringBuilder text = new StringBuilder(base);
            if (length != null) {
                text.append('(').append(length).append(')');
            } else if (precision != null) {
                text.append('(').append(precision);
                if ("NUMERIC".equals(base) && scale != null && scale != 0) text.append(',').append(scale);
                text.append(')');
            }
            text.append("[]".repeat(arrayDims));
            return text.toString();
        }

        /** 기본 타입·배열 차원 */
        public String baseDisplay() {
            return base + "[]".repeat(arrayDims);
        }
    }

    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final Set<String> SERIALS = Set.of("serial", "serial4", "bigserial", "serial8", "smallserial", "serial2");

    static {
        alias("VARCHAR", "character varying", "varchar");
        alias("CHAR", "character", "char", "bpchar");
        alias("NUMERIC", "numeric", "decimal");
        alias("INTEGER", "integer", "int", "int4", "serial", "serial4");
        alias("BIGINT", "bigint", "int8", "bigserial", "serial8");
        alias("SMALLINT", "smallint", "int2", "smallserial", "serial2");
        alias("REAL", "real", "float4");
        alias("DOUBLE PRECISION", "double precision", "float8", "float");
        alias("BOOLEAN", "boolean", "bool");
        alias("TEXT", "text");
        alias("BYTEA", "bytea");
        alias("DATE", "date");
        alias("TIME", "time", "time without time zone");
        alias("TIMETZ", "timetz", "time with time zone");
        alias("TIMESTAMP", "timestamp", "timestamp without time zone");
        alias("TIMESTAMPTZ", "timestamptz", "timestamp with time zone");
        alias("INTERVAL", "interval");
        alias("UUID", "uuid");
        alias("JSON", "json");
        alias("JSONB", "jsonb");
        alias("XML", "xml");
        alias("INET", "inet");
        alias("CIDR", "cidr");
        alias("MACADDR", "macaddr");
        alias("MONEY", "money");
        alias("BIT", "bit");
        alias("VARBIT", "varbit", "bit varying");
    }

    private static void alias(String base, String... names) {
        for (String name : names) ALIASES.put(name, base);
    }

    /** 이름(인자)접미사 : timestamp(3) without time zone 처럼 인자가 가운데 있어도 된다 */
    private static final Pattern ARRAY_SUFFIX = Pattern.compile("(?is).*\\sarray$");
    private static final Pattern TYPE_ARGS = Pattern.compile("^(.*?)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(-?\\d+)\\s*)?\\)\\s*(.*)$");

    public static PgType type(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        int dims = 0;
        // 배열 : PostgreSQL 표기 bigint[] 와 SQL 표준 표기 BIGINT ARRAY (모델 DDL 이 쓰는 표기)는 같은 타입이다
        while (true) {
            if (text.endsWith("[]")) {
                text = text.substring(0, text.length() - 2).trim();
            } else if (ARRAY_SUFFIX.matcher(text).matches()) {
                text = text.substring(0, text.length() - "array".length()).trim();
            } else {
                break;
            }
            dims++;
        }
        String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Integer first = null;
        Integer second = null;
        String name = lower;
        Matcher matcher = TYPE_ARGS.matcher(lower);
        if (matcher.matches()) {
            name = (matcher.group(1) + " " + matcher.group(4)).trim().replaceAll("\\s+", " ");
            first = Integer.valueOf(matcher.group(2));
            second = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        }
        String base = ALIASES.get(name);
        if (base == null) return new PgType(text.toUpperCase(Locale.ROOT).replaceAll("\\s+", " "), null, null, null, dims, false, false);
        boolean serial = SERIALS.contains(name);
        return switch (base) {
            case "VARCHAR", "BIT", "VARBIT" -> new PgType(base, first, null, null, dims, true, false);
            case "CHAR" -> new PgType(base, first == null ? 1 : first, null, null, dims, true, false); // character = character(1)
            case "NUMERIC" -> new PgType(base, null, first, first == null ? null : second == null ? 0 : second, dims, true, false);
            // 초 소수 자릿수를 생략하면 PostgreSQL 은 최대(6)로 둔다 : timestamp 와 timestamp(6) 은 같다
            case "TIME", "TIMETZ", "TIMESTAMP", "TIMESTAMPTZ", "INTERVAL" -> new PgType(base, null, first == null ? 6 : first, null, dims, true, false);
            default -> first == null ? new PgType(base, null, null, null, dims, true, serial)
                    : new PgType((base + "(" + first + (second == null ? "" : "," + second) + ")"), null, null, null, dims, false, serial);
        };
    }

    /** 원본 기본값 칸의 IDENTITY 절 (수집기가 GENERATED … AS IDENTITY 로 적는다). 모델 기본값에 같은 절을 적은 경우도 같다 */
    public static AutoIncrement identity(String defaultValue) {
        if (defaultValue == null) return null;
        String text = defaultValue.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (text.startsWith("GENERATED ALWAYS AS IDENTITY")) return AutoIncrement.IDENTITY_ALWAYS;
        if (text.startsWith("GENERATED BY DEFAULT AS IDENTITY")) return AutoIncrement.IDENTITY_BY_DEFAULT;
        return null;
    }

    /** 시퀀스 기본값 (nextval(…)) */
    public static boolean sequenceDefault(String defaultValue) {
        return defaultValue != null && defaultValue.trim().toLowerCase(Locale.ROOT).startsWith("nextval(");
    }

    /** 생성 컬럼 (GENERATED ALWAYS AS (식) STORED|VIRTUAL) */
    public static boolean generated(String defaultValue) {
        return defaultValue != null && defaultValue.trim().toUpperCase(Locale.ROOT).matches("^GENERATED ALWAYS AS \\(.*");
    }

    // ------------------------------------------------------------------ 식

    private static final String CAST_TYPE = "(?:character varying|double precision|bit varying|(?:timestamp|time)(?:\\s*\\(\\d+\\))?\\s+(?:with|without) time zone"
            + "|[a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)?)(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?(?:\\[\\])*";
    /** 리터럴(따옴표 문자열, 괄호로 감싼 숫자, 숫자)에 붙은 형 변환 */
    private static final Pattern LITERAL_CAST = Pattern.compile("('(?:[^']|'')*'|\\(\\s*-?\\d+(?:\\.\\d+)?\\s*\\)|(?<![\\w.])-?\\d+(?:\\.\\d+)?)\\s*::\\s*" + CAST_TYPE,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_NUMBER = Pattern.compile("(?<![\\w'])'(-?\\d+(?:\\.\\d+)?)'(?![\\w'])");
    private static final Pattern PAREN_NUMBER = Pattern.compile("(?<![\\w)\\]])\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
    private static final Pattern SIMPLE = Pattern.compile("^(?:-?\\d+(?:\\.\\d+)?|'(?:[^']|'')*'|true|false|null|current_timestamp|current_date|current_time|localtimestamp"
            + "|localtime|current_user|session_user)$");

    /** 안전한 정규화만 한 식. 비어 있으면 null */
    public static String expression(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        for (String before = null; !text.equals(before); ) {
            before = text;
            text = LITERAL_CAST.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        }
        text = outsideQuotes(text);
        for (String before = null; !text.equals(before); ) {
            before = text;
            text = QUOTED_NUMBER.matcher(text).replaceAll("$1");
            text = PAREN_NUMBER.matcher(text).replaceAll("$1");
            text = stripOuter(text);
        }
        return text;
    }

    /** 리터럴에 붙은 형 변환만 지운다 (대소문자·공백은 그대로) : 가져올 때 모델 기본값으로 쓸 표기 후보 ('NEW'::character varying → 'NEW') */
    public static String stripLiteralCasts(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        for (String before = null; !text.equals(before); ) {
            before = text;
            text = LITERAL_CAST.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        }
        return text;
    }

    /** 정규화한 식이 리터럴·키워드 하나인지 (둘 다 그렇고 다르면 값이 다르다고 말할 수 있다) */
    public static boolean simple(String normalized) {
        return normalized != null && SIMPLE.matcher(normalized).matches();
    }

    /** 따옴표(작은·큰) 밖 : 소문자, 공백 한 칸, 괄호·쉼표·연산자 옆 공백 없음 */
    private static String outsideQuotes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                if (space && !out.isEmpty() && !endsWithTight(out)) out.append(' ');
                space = false;
                quote = c;
                out.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                space = true;
                continue;
            }
            boolean tight = TIGHT.indexOf(c) >= 0;
            if (space && !out.isEmpty() && !tight && !endsWithTight(out)) out.append(' ');
            space = false;
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static boolean endsWithTight(StringBuilder out) {
        return TIGHT.indexOf(out.charAt(out.length() - 1)) >= 0;
    }

    /** 앞뒤 공백을 없애는 문자 : 괄호·쉼표·연산자 기호 (a >= 0 과 a>=0 을 같게) */
    private static final String TIGHT = "(),=<>!+-*/%|";

    /** 식 전체를 감싼 괄호 한 겹을 벗긴다 ((a) + (b) 는 벗기지 않는다) */
    private static String stripOuter(String text) {
        if (text.length() < 2 || text.charAt(0) != '(' || text.charAt(text.length() - 1) != ')') return text;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0 && i < text.length() - 1) return text;
        }
        return depth == 0 ? text.substring(1, text.length() - 1).trim() : text;
    }
}
