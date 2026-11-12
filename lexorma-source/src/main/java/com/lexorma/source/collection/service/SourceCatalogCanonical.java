package com.lexorma.source.collection.service;

import com.lexorma.core.project.domain.enums.DbType;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 원본 객체의 정규화한 표현과 해시 (AI-1c). 버전 JSON·변경 판정·해시가 모두 이것 하나로 만들어진다.
 * <p>
 * 수집 결과(검증한 SourceCatalogSpec)와 현재 카탈로그 행이 <b>같은 값이면 같은 JSON</b> 이 되도록 저장 규칙과 맞춘다 :
 * <ul>
 *   <li>필드 순서는 고정 (아래 json() 의 순서). 컬럼은 원본 순서(같으면 비교 명), 제약조건·인덱스·관계는 비교 키 순서. 구성 컬럼 순서는 그대로 둔다</li>
 *   <li>문자열은 다듬지 않는다 : NULL 과 빈 문자열은 다르다. 단 저장할 때 빈 값을 NULL 로 바꾸는 항목(제약조건·인덱스·관계의 이름, 규칙)은 검증 단계에서 미리 바꾼다</li>
 *   <li>타입·기본값은 원본 표현 그대로 (다시 쓰지 않는다). 정밀도는 숫자 표기의 뒤쪽 0 을 지운 문자열 ({@code 64}, {@code 64.00} → "64")</li>
 *   <li>하위 객체가 가리키는 컬럼은 비교 명으로 적는다 (대소문자 접기 규칙은 SourceIdentifiers 그대로)</li>
 *   <li>Lexorma 의 일련번호, 감사 컬럼, 수집 실행·시각, 존재 여부는 넣지 않는다. 스키마 표기(원본 이름)는 스키마 버전에만 있다 : 테이블에는 비교 경로만</li>
 * </ul>
 * 해시 = {@link #HASH_PREFIX} + SHA-256(JSON). 규칙을 바꾸면 {@link #FORMAT} 과 접두사를 함께 올린다 : 접두사가 다른 해시는 비교하지 않고
 * 현재 행에서 새 규칙으로 다시 만들어 비교한다 (대량의 잘못된 변경을 막는다).
 */
public final class SourceCatalogCanonical {

    public static final int FORMAT = 1;
    public static final String HASH_PREFIX = "v1:";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Oracle 이 이름을 붙이지 않은 제약조건·인덱스에 만드는 이름 (SYS_C0012345 …) : 다시 만들면 바뀌므로 식별에 쓰지 않는다 */
    private static final Pattern ORACLE_SYSTEM_NAME = Pattern.compile("^SYS_[A-Z0-9_$#]+$");

    private SourceCatalogCanonical() {
    }

    public record Schema(String pathKey, String catalogName, String schemaName) {
        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", FORMAT);
            map.put("path", pathKey);
            map.put("catalog", catalogName);
            map.put("schema", schemaName);
            return map;
        }
    }

    public record Column(String name, String compareName, Boolean quoted, Integer ordinal, String dataType, Integer maxLength, String precision, Integer scale,
                         Boolean nullable, String defaultValue, String comment) {
        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("compareName", compareName);
            map.put("quoted", quoted);
            map.put("ordinal", ordinal);
            map.put("dataType", dataType);
            map.put("maxLength", maxLength);
            map.put("precision", precision);
            map.put("scale", scale);
            map.put("nullable", nullable);
            map.put("defaultValue", defaultValue);
            map.put("comment", comment);
            return map;
        }
    }

    /** 비교 키로 짝짓는 하위 객체 (제약조건·인덱스·관계) */
    public interface Keyed<T> {
        String key();

        Map<String, Object> json();

        T withKey(String key);
    }

    /** @param columns 구성 컬럼의 비교 명 (순서대로) */
    public record Constraint(String key, String name, String type, String checkExpression, List<String> columns) implements Keyed<Constraint> {
        @Override
        public Constraint withKey(String key) {
            return new Constraint(key, name, type, checkExpression, columns);
        }

        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("name", name);
            map.put("type", type);
            map.put("checkExpression", checkExpression);
            map.put("columns", columns);
            return map;
        }
    }

    public record Index(String key, String name, String indexType, boolean unique, List<String> columns) implements Keyed<Index> {
        @Override
        public Index withKey(String key) {
            return new Index(key, name, indexType, unique, columns);
        }

        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("name", name);
            map.put("indexType", indexType);
            map.put("unique", unique);
            map.put("columns", columns);
            return map;
        }
    }

    /** @param column 참조하는 컬럼의 비교 명, referencedColumn 참조되는 컬럼 (원본이 준 이름 그대로) */
    public record ColumnPair(String column, String referencedColumn) {
        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("column", column);
            map.put("referencedColumn", referencedColumn);
            return map;
        }
    }

    /** 참조 대상은 원본이 알려 준 이름 그대로 (Lexorma 가 이은 내부 참조는 넣지 않는다 : 원본에서 확인한 사실만) */
    public record Relation(String key, String name, String referencedCatalog, String referencedSchema, String referencedTable, String deleteRule, String updateRule,
                           List<ColumnPair> columns) implements Keyed<Relation> {
        @Override
        public Relation withKey(String key) {
            return new Relation(key, name, referencedCatalog, referencedSchema, referencedTable, deleteRule, updateRule, columns);
        }

        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("name", name);
            map.put("referencedCatalog", referencedCatalog);
            map.put("referencedSchema", referencedSchema);
            map.put("referencedTable", referencedTable);
            map.put("deleteRule", deleteRule);
            map.put("updateRule", updateRule);
            map.put("columns", columns.stream().map(ColumnPair::json).toList());
            return map;
        }
    }

    public record Table(String schemaPath, String name, String compareName, Boolean quoted, String type, String comment, List<Column> columns,
                        List<Constraint> constraints, List<Index> indexes, List<Relation> relations) {

        /** 정렬해 만든다 : 컬럼은 원본 순서, 하위 객체는 비교 키 순서 */
        public static Table of(String schemaPath, String name, String compareName, Boolean quoted, String type, String comment, List<Column> columns,
                               List<Constraint> constraints, List<Index> indexes, List<Relation> relations) {
            return new Table(schemaPath, name, compareName, quoted, type, comment,
                    columns.stream().sorted(Comparator.comparing(Column::ordinal).thenComparing(Column::compareName)).toList(),
                    constraints.stream().sorted(Comparator.comparing(Constraint::key)).toList(),
                    indexes.stream().sorted(Comparator.comparing(Index::key)).toList(),
                    relations.stream().sorted(Comparator.comparing(Relation::key)).toList());
        }

        /** 테이블 자체의 항목 (하위 객체 제외) : 테이블 수정 이력의 전후 */
        public Map<String, Object> ownJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("compareName", compareName);
            map.put("quoted", quoted);
            map.put("type", type);
            map.put("comment", comment);
            return map;
        }

        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", FORMAT);
            map.put("schemaPath", schemaPath);
            map.putAll(ownJson());
            map.put("columns", columns.stream().map(Column::json).toList());
            map.put("constraints", constraints.stream().map(Constraint::json).toList());
            map.put("indexes", indexes.stream().map(Index::json).toList());
            map.put("relations", relations.stream().map(Relation::json).toList());
            return map;
        }
    }

    public static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** JSON 문자열을 화면·API 로 돌려줄 값으로 읽는다 */
    public static Object read(String json) {
        return json == null ? null : JSON.readValue(json, Object.class);
    }

    public static String hash(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지금 규칙으로 만든 해시인지 */
    public static boolean current(String hash) {
        return hash != null && hash.startsWith(HASH_PREFIX);
    }

    public static String precision(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** 이름이 식별에 쓸 수 있는 것인지 : 없거나 DBMS 가 자동으로 붙인 이름이면 구조(서명)로 식별한다 */
    public static boolean stableName(DbType dbType, String name) {
        if (name == null) return false;
        return dbType != DbType.ORACLE || !ORACLE_SYSTEM_NAME.matcher(name).matches();
    }

    public static String constraintKey(DbType dbType, String type, String name, List<String> columns, String checkExpression) {
        if (stableName(dbType, name)) return type + ":name:" + name;
        return type + ":sig:" + String.join(",", columns) + (checkExpression == null ? "" : ":" + checkExpression);
    }

    public static String indexKey(DbType dbType, String name, boolean unique, List<String> columns) {
        if (stableName(dbType, name)) return "name:" + name;
        return "sig:" + (unique ? "U" : "N") + ":" + String.join(",", columns);
    }

    public static String relationKey(DbType dbType, String name, String referencedCatalog, String referencedSchema, String referencedTable, List<ColumnPair> columns) {
        if (stableName(dbType, name)) return "name:" + name;
        return "sig:" + String.join(",", columns.stream().map(ColumnPair::column).toList()) + "->" + referencedCatalog + "." + referencedSchema + "." + referencedTable
                + "(" + String.join(",", columns.stream().map(ColumnPair::referencedColumn).toList()) + ")";
    }

    /**
     * 같은 키가 둘 이상이면(이름 없는 같은 구성의 객체) #2, #3 … 을 붙인다. 입력 순서와 상관없이 같은 결과가 나오도록 (키, JSON) 순서로 정렬한 뒤 붙인다 :
     * 수집 결과와 현재 행이 같은 키를 갖게 된다.
     *
     * @return (원래 객체, 키를 정한 객체) 를 정렬한 순서대로
     */
    public static <T extends Keyed<T>> List<Map.Entry<T, T>> rekey(List<T> items) {
        List<T> sorted = items.stream().sorted(Comparator.comparing((T item) -> item.key()).thenComparing(item -> json(item.json()))).toList();
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<Map.Entry<T, T>> result = new ArrayList<>(sorted.size());
        for (T item : sorted) {
            int count = seen.merge(item.key(), 1, Integer::sum);
            result.add(Map.entry(item, count == 1 ? item : item.withKey(item.key() + "#" + count)));
        }
        return result;
    }

    public static <T extends Keyed<T>> List<T> unique(List<T> items) {
        return rekey(items).stream().map(Map.Entry::getValue).toList();
    }

    /** 두 JSON 객체에서 값이 다른 항목 (순서 그대로) */
    public static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        List<String> changed = new ArrayList<>();
        for (String field : after.keySet()) {
            if (!java.util.Objects.equals(before.get(field), after.get(field))) changed.add(field);
        }
        for (String field : before.keySet()) {
            if (!after.containsKey(field)) changed.add(field);
        }
        return changed;
    }
}
