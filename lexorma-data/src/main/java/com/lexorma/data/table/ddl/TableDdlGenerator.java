package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.common.i18n.Messages;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테이블/컬럼 설계 정보로 대상 DB 의 CREATE TABLE DDL 을 만든다. (테이블 관리 > 테이블 상세 > DDL 보기)
 *
 * - 컬럼은 정렬 순서(sortOrder) 순, PK 는 CONSTRAINT PK_테이블명, 고유값은 UK_테이블명_컬럼명
 * - 인덱스는 물리 모델 인덱스만 쓴다 (컬럼의 인덱스 여부 = 출처 COLUMN 인덱스 IX_테이블명_컬럼명). 유형은 DBMS 별 문법({@link #indexStatement})
 * - 참조 테이블명이 있는 컬럼은 FK_테이블명_컬럼명 (참조 테이블의 단일 PK 를 참조, ALTER TABLE 로 생성)
 * - NOT NULL : NULL 비허용 / PK / 필수 입력 컬럼
 * - 주석 : PostgreSQL·Oracle 은 COMMENT ON, MySQL·MariaDB 는 컬럼/테이블 COMMENT 옵션
 * - 물리 모델(M-1) : 기본 스키마가 아닌 스키마의 테이블은 스키마명.테이블명, 복합 UNIQUE·CHECK 는 CREATE TABLE 안에,
 *   복합 인덱스는 CREATE INDEX, 사용자 정의 관계(복합 FK)는 ALTER TABLE 로 만든다. 같은 컬럼 구성의 키는 한 번만 만든다.
 * - 데이터 타입은 공통 타입명(VARCHAR, NUMBER, DATE, TIMESTAMP, BOOLEAN, CLOB, BLOB …)을 DB 별 타입으로 바꾼다.
 *   길이가 없어 DB 기본값으로 정한 항목은 warnings 로 알려준다.
 */
public final class TableDdlGenerator {

    private TableDdlGenerator() {
    }

    /**
     * @param ddl      생성된 DDL 스크립트
     * @param warnings 확인이 필요한 항목 (길이 미지정, 컬럼 없음 등)
     */
    public record Result(String ddl, List<String> warnings) {}

    /**
     * 컬럼 설정 밖의 물리 모델 (M-1)
     * @param relations 이 테이블이 원본인 사용자 정의 FK 관계 (참조 테이블명에서 만든 REF_COLUMN 관계는 컬럼의 참조 테이블명으로 이미 만든다)
     * @param targetUniqueKeys 관계 대상 테이블 ID → 그 테이블의 UNIQUE 제약·고유 인덱스 컬럼명 구성 (FK 가 참조할 수 있는 키인지 판단)
     * @param ambiguousRefTables 여러 스키마에 같은 이름이 있어 정할 수 없는 참조 테이블명 (컬럼에 적은 그대로, 대문자)
     */
    public record Extras(List<TableConstraint> constraints, List<TableIndex> indexes, List<TableRelation> relations,
                         Map<Long, List<Set<String>>> targetUniqueKeys, Set<String> ambiguousRefTables) {
        public static final Extras NONE = new Extras(List.of(), List.of(), List.of());

        public Extras(List<TableConstraint> constraints, List<TableIndex> indexes, List<TableRelation> relations) {
            this(constraints, indexes, relations, Map.of(), Set.of());
        }

        public Extras(List<TableConstraint> constraints, List<TableIndex> indexes, List<TableRelation> relations, Map<Long, List<Set<String>>> targetUniqueKeys) {
            this(constraints, indexes, relations, targetUniqueKeys, Set.of());
        }

        public Extras withAmbiguousRefTables(Set<String> ambiguous) {
            return new Extras(constraints, indexes, relations, targetUniqueKeys, ambiguous);
        }
    }

    private static final Pattern TYPE_PATTERN = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9_ ]*?)\\s*(?:\\(\\s*([0-9]+)\\s*(?:,\\s*([0-9]+)\\s*)?\\))?\\s*$");

    // 따옴표 없이 그대로 쓸 기본값 (함수/키워드)
    private static final Set<String> RAW_DEFAULTS = Set.of(
            "NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "SYSDATE", "SYSTIMESTAMP");

    private static final Pattern NUMERIC_DEFAULT = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");

    public static Result generate(DbType dbType, TableInfo table, List<ColumnInfo> columns) {
        return generate(dbType, table, columns, Map.of());
    }

    /**
     * @param refPkColumns 참조 테이블명(컬럼에 적은 그대로, 대문자 : 테이블명 또는 스키마명.테이블명) → 그 테이블의 PK 컬럼 (정렬 순서).
     *                     이름이 이 테이블 기준으로 정해지지 않은 것(미등록·모호)은 키가 없다.
     *                     컬럼의 참조 테이블명으로 FK 를 만들고, 참조 컬럼과 PK 의 타입이 같은지 확인할 때 쓴다.
     */
    public static Result generate(DbType dbType, TableInfo table, List<ColumnInfo> columns, Map<String, List<ColumnInfo>> refPkColumns) {
        return generate(dbType, table, columns, refPkColumns, Extras.NONE);
    }

    public static Result generate(DbType dbType, TableInfo table, List<ColumnInfo> columns, Map<String, List<ColumnInfo>> refPkColumns, Extras extras) {
        return generate(dbType, table, columns, refPkColumns, extras, null);
    }

    /**
     * @param defaultSchemaName 기본 스키마 테이블에 붙일 실제 스키마명 (DDL 보기의 "스키마명 포함"). null 이면 붙이지 않는다.
     *                          기본 스키마가 아닌 테이블은 이 값과 관계없이 자기 스키마명을 붙인다.
     */
    public static Result generate(DbType dbType, TableInfo table, List<ColumnInfo> columns, Map<String, List<ColumnInfo>> refPkColumns, Extras extras,
                                  String defaultSchemaName) {
        Parts parts = parts(dbType, table, columns, refPkColumns, extras, defaultSchemaName);
        StringBuilder sql = new StringBuilder(parts.header());
        if (!parts.hasColumns()) {
            sql.append("-- ").append(Messages.get("등록된 컬럼이 없습니다.")).append("\n");
            return new Result(sql.toString(), parts.warnings());
        }
        sql.append(parts.createTable());
        for (List<String> section : List.of(parts.indexes(), parts.foreignKeys(), parts.comments())) {
            if (!section.isEmpty()) sql.append("\n").append(String.join("\n", section)).append("\n");
        }
        return new Result(sql.toString(), parts.warnings());
    }

    /**
     * 테이블 하나의 DDL 을 문장 묶음으로 나눈 것. {@link #generate} 는 header · createTable · indexes · foreignKeys · comments 순서로 이어 붙이고,
     * 통합 Export(M-11)는 여러 테이블의 같은 묶음끼리 모은다 (모든 CREATE TABLE → 인덱스 → FK).
     *
     * @param header      "-- DBMS DDL : 테이블명 (한글명)" 한 줄
     * @param createTable CREATE TABLE 문 (; 와 줄바꿈 포함, MySQL·MariaDB 는 테이블 COMMENT 옵션 포함). 컬럼이 없으면 null
     * @param indexes     CREATE INDEX 문 (대상 DBMS 에서 만들 수 없는 인덱스는 -- 주석으로 낸 문장)
     * @param foreignKeys ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY 문
     * @param comments    COMMENT ON 문 (PostgreSQL·Oracle)
     */
    public record Parts(String header, String createTable, List<String> indexes, List<String> foreignKeys, List<String> comments, List<String> warnings) {
        public boolean hasColumns() {
            return createTable != null;
        }
    }

    public static Parts parts(DbType dbType, TableInfo table, List<ColumnInfo> columns, Map<String, List<ColumnInfo>> refPkColumns, Extras extras,
                              String defaultSchemaName) {
        DbType db = DbType.orDefault(dbType);
        String tableName = table.getTableName();
        String qualifiedName = qualifiedName(table, defaultSchemaName);
        List<String> warnings = new ArrayList<>();

        List<ColumnInfo> ordered = columns.stream()
                .sorted(Comparator.comparing(ColumnInfo::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        StringBuilder header = new StringBuilder();
        header.append("-- ").append(db.getLabel()).append(" DDL : ").append(tableName);
        if (notBlank(table.getTableKorName())) header.append(" (").append(table.getTableKorName()).append(")");
        header.append("\n");

        if (ordered.isEmpty()) {
            warnings.add(Messages.get("등록된 컬럼이 없습니다. 컬럼 관리에서 컬럼을 먼저 등록해주세요."));
            return new Parts(header.toString(), null, List.of(), List.of(), List.of(), warnings);
        }

        List<String> lines = new ArrayList<>();
        int nameWidth = ordered.stream().mapToInt(c -> c.getColumnName().length()).max().orElse(0);

        for (ColumnInfo column : ordered) {
            StringBuilder line = new StringBuilder("    ")
                    .append(pad(column.getColumnName(), nameWidth)).append(" ")
                    .append(mapType(db, column, warnings));

            if (notBlank(column.getDefaultValue())) {
                line.append(" DEFAULT ").append(defaultLiteral(column.getDefaultValue()));
            }
            if (isNotNull(column)) {
                line.append(" NOT NULL");
            }
            if (isMySqlFamily(db) && notBlank(column.getColumnDesc())) {
                line.append(" COMMENT ").append(quote(column.getColumnDesc()));
            }
            lines.add(line.toString());
        }

        List<String> pkColumns = ordered.stream().filter(c -> Boolean.TRUE.equals(c.getIsPk())).map(ColumnInfo::getColumnName).toList();
        // 이미 만든 키 구성 (같은 구성의 UNIQUE / 인덱스를 다시 만들지 않는다)
        Set<Set<String>> uniqueKeys = new HashSet<>();
        if (!pkColumns.isEmpty()) {
            uniqueKeys.add(Set.copyOf(pkColumns));
            lines.add("    CONSTRAINT " + constraintName(db, "PK_" + tableName) + " PRIMARY KEY (" + String.join(", ", pkColumns) + ")");
        } else {
            warnings.add(Messages.get("PK 컬럼이 없습니다."));
        }

        ordered.stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsUnique()) && !Boolean.TRUE.equals(c.getIsPk()))
                .forEach(c -> {
                    uniqueKeys.add(Set.of(c.getColumnName()));
                    lines.add("    CONSTRAINT " + constraintName(db, "UK_" + tableName + "_" + c.getColumnName()) + " UNIQUE (" + c.getColumnName() + ")");
                });

        for (TableConstraint constraint : extras.constraints()) {
            if (constraint.getConstraintType() == TableConstraint.Type.UNIQUE) {
                List<String> names = constraint.getColumns().stream().map(TableConstraintColumn::getColumn).map(ColumnInfo::getColumnName).toList();
                if (uniqueKeys.add(Set.copyOf(names))) {
                    lines.add("    CONSTRAINT " + constraintName(db, constraint.getConstraintName()) + " UNIQUE (" + String.join(", ", names) + ")");
                }
            } else {
                lines.add("    CONSTRAINT " + constraintName(db, constraint.getConstraintName()) + " CHECK (" + constraint.getCheckExpression() + ")");
                if (db == DbType.MYSQL) warnings.add(Messages.get("{0} : MySQL 8.0.16 미만은 CHECK 제약을 무시합니다.", constraint.getConstraintName()));
            }
        }

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(qualifiedName).append(" (\n")
                .append(String.join(",\n", lines))
                .append("\n)");

        String tableComment = tableComment(table);
        if (isMySqlFamily(db)) {
            sql.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            if (tableComment != null) sql.append(" COMMENT=").append(quote(tableComment));
        }
        sql.append(";\n");

        // 인덱스 : 모두 물리 모델 인덱스(TB_TBL_IDX)에서 만든다. 컬럼의 인덱스 여부는 출처 COLUMN 인덱스로 이미 들어 있다 (M-14 D-19)
        List<String> indexes = new ArrayList<>();
        Set<List<String>> indexKeys = new HashSet<>();
        Set<String> advancedKeys = new HashSet<>();
        for (TableIndex index : extras.indexes()) {
            boolean unique = Boolean.TRUE.equals(index.getIsUnique());
            boolean add;
            if (index.isPlain()) {
                List<String> names = index.getColumns().stream().map(TableIndexColumn::getColumn).map(ColumnInfo::getColumnName).toList();
                add = unique ? uniqueKeys.add(Set.copyOf(names)) : indexKeys.add(names);
            } else {
                // 표현식·정렬·INCLUDE·조건이 있는 인덱스는 같은 정의일 때만 한 번 (부분 UNIQUE 는 UNIQUE 제약과 같은 것이 아니다)
                add = advancedKeys.add(unique + "|" + members(index) + "|" + index.getCondition());
            }
            if (add) indexes.add(indexStatement(db, qualifiedName, index, warnings));
        }

        // 외래키 : 참조 테이블의 단일 PK 를 참조한다. 테이블 생성 순서와 관계없이 실행할 수 있도록 ALTER TABLE 로 따로 만든다.
        List<String> foreignKeys = foreignKeys(db, tableName, qualifiedName, ordered, refPkColumns, extras.ambiguousRefTables(), warnings, defaultSchemaName);
        foreignKeys.addAll(relationForeignKeys(db, qualifiedName, extras.relations(), extras.targetUniqueKeys(), warnings, defaultSchemaName));

        // 주석 (PostgreSQL / Oracle)
        List<String> comments = new ArrayList<>();
        if (!isMySqlFamily(db)) {
            if (tableComment != null) comments.add("COMMENT ON TABLE " + qualifiedName + " IS " + quote(tableComment) + ";");
            ordered.stream()
                    .filter(c -> notBlank(c.getColumnDesc()))
                    .forEach(c -> comments.add("COMMENT ON COLUMN " + qualifiedName + "." + c.getColumnName() + " IS " + quote(c.getColumnDesc()) + ";"));
        }

        return new Parts(header.toString(), sql.toString(), indexes, foreignKeys, comments, warnings);
    }

    // ------------------------------------------------------------------ 인덱스 (M-14 D-21)

    /** DBMS 별로 DDL 에 낼 수 있는 인덱스 유형 (저장값 = 대문자, '-'·'_'·공백 없음). 유형이 없으면 DBMS 기본(B-tree) */
    public static List<String> supportedIndexTypes(DbType dbType) {
        return switch (DbType.orDefault(dbType)) {
            case POSTGRESQL -> List.of("BTREE", "HASH", "GIN", "GIST", "SPGIST", "BRIN");
            case ORACLE -> List.of("BTREE", "BITMAP");
            case MYSQL, MARIADB -> List.of("BTREE", "HASH", "FULLTEXT", "SPATIAL");
        };
    }

    /** 저장·비교용 유형 값 : 대문자, '-'·'_'·공백 제거 (B-Tree → BTREE, sp_gist → SPGIST). 비어 있으면 null */
    public static String normalizeIndexType(String type) {
        if (!notBlank(type)) return null;
        return type.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    /**
     * 이 DBMS 에서 그 유형(과 고유 여부)의 인덱스를 만들 수 없는 이유. 만들 수 있으면 null.
     * 고유 인덱스 : PostgreSQL 은 B-tree 만, Oracle 은 BITMAP 불가, MySQL·MariaDB 는 FULLTEXT·SPATIAL 불가.
     */
    public static String indexTypeProblem(DbType dbType, String type, boolean unique) {
        DbType db = DbType.orDefault(dbType);
        String normalized = normalizeIndexType(type);
        if (normalized == null) return null;
        if (!supportedIndexTypes(db).contains(normalized)) {
            return Messages.get("{0} 에서 지원하지 않는 인덱스 유형입니다. ({1}, 지원 : {2})", db.getLabel(), type.trim(), String.join(", ", supportedIndexTypes(db)));
        }
        boolean uniqueAllowed = switch (db) {
            case POSTGRESQL -> normalized.equals("BTREE");
            case ORACLE -> !normalized.equals("BITMAP");
            case MYSQL, MARIADB -> !normalized.equals("FULLTEXT") && !normalized.equals("SPATIAL");
        };
        return unique && !uniqueAllowed ? Messages.get("{0} 에서 {1} 인덱스는 고유(UNIQUE)로 만들 수 없습니다.", db.getLabel(), normalized) : null;
    }

    /**
     * 표현식·정렬·INCLUDE·WHERE 조건(M-14 2차)과 DBMS·유형의 조합 문제. 문제가 없거나 고급 속성이 없으면 null.
     * PostgreSQL 만 지원한다. 정렬(ASC/DESC·NULLS)은 B-tree 만, INCLUDE 는 B-tree·GiST·SP-GiST 만 (PostgreSQL 문서 기준).
     */
    public static String advancedProblem(DbType dbType, String type, boolean expression, boolean sort, boolean include, boolean condition) {
        DbType db = DbType.orDefault(dbType);
        if (!expression && !sort && !include && !condition) return null;
        if (db != DbType.POSTGRESQL) return Messages.get("{0} 에서는 표현식·정렬·INCLUDE·WHERE 조건 인덱스를 지원하지 않습니다. (PostgreSQL 만)", db.getLabel());
        String method = normalizeIndexType(type) == null ? "BTREE" : normalizeIndexType(type);
        if (sort && !method.equals("BTREE")) return Messages.get("{0} 인덱스는 정렬(ASC/DESC·NULLS)을 지원하지 않습니다. (BTREE 만)", method);
        if (include && !Set.of("BTREE", "GIST", "SPGIST").contains(method)) return Messages.get("{0} 인덱스는 INCLUDE 를 지원하지 않습니다. (BTREE·GIST·SPGIST 만)", method);
        return null;
    }

    /** PostgreSQL HASH·SP-GiST 는 키가 하나만 된다 (저장 검증 전용 : 예전에 저장한 인덱스의 DDL 출력은 바꾸지 않는다) */
    public static String keyCountProblem(DbType dbType, String type, int keys) {
        String method = normalizeIndexType(type);
        if (DbType.orDefault(dbType) == DbType.POSTGRESQL && keys > 1 && ("HASH".equals(method) || "SPGIST".equals(method))) {
            return Messages.get("{0} 인덱스는 키를 하나만 가질 수 있습니다.", method);
        }
        return null;
    }

    /** 키 목록 (표현식은 괄호로 감싼다 : 어떤 식이든 PostgreSQL 인덱스 요소로 유효하다) */
    private static String members(TableIndex index) {
        return String.join(", ", index.keyMembers().stream()
                .map(c -> (c.getColumn() != null ? c.getColumn().getColumnName() : "(" + c.getExpression() + ")") + (c.getSort() == null ? "" : " " + c.getSort()))
                .toList());
    }

    /**
     * CREATE INDEX 문 하나. 유형은 PostgreSQL USING 방식, Oracle BITMAP, MySQL·MariaDB FULLTEXT·SPATIAL / USING BTREE·HASH 로 낸다.
     * 대상 DBMS 에서 만들 수 없는 유형(프로젝트 DBMS 를 바꾼 뒤의 예전 값 등)은 기본 유형으로 바꾸지 않고 주석으로 내고 경고한다.
     */
    public static String indexStatement(DbType dbType, String qualifiedName, TableIndex index, List<String> warnings) {
        DbType db = DbType.orDefault(dbType);
        boolean unique = Boolean.TRUE.equals(index.getIsUnique());
        String type = normalizeIndexType(index.getIndexType());
        List<String> includes = index.includeMembers().stream().map(TableIndexColumn::getColumn).map(ColumnInfo::getColumnName).toList();
        String columns = " (" + members(index) + ")";
        String tail = (includes.isEmpty() ? "" : " INCLUDE (" + String.join(", ", includes) + ")")
                + (index.getCondition() == null ? "" : " WHERE " + index.getCondition());
        String name = constraintName(db, index.getIndexName());

        String problem = indexTypeProblem(db, type, unique);
        if (problem == null) {
            problem = advancedProblem(db, type, index.keyMembers().stream().anyMatch(c -> c.getColumn() == null),
                    index.keyMembers().stream().anyMatch(c -> c.getSort() != null), !includes.isEmpty(), index.getCondition() != null);
        }
        if (problem != null) {
            warnings.add(index.getIndexName() + " : " + problem + " " + Messages.get("DDL 에 주석으로만 냈습니다."));
            return "-- CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + name + " ON " + qualifiedName + columns + tail + ";"
                    + (index.getIndexType() == null ? "" : " -- " + index.getIndexType());
        }
        String prefix = unique ? "UNIQUE " : "";
        String using = "";
        if (type != null) {
            switch (db) {
                case POSTGRESQL -> using = " USING " + type.toLowerCase(Locale.ROOT);
                case ORACLE -> { if (type.equals("BITMAP")) prefix = "BITMAP "; }
                case MYSQL, MARIADB -> {
                    if (type.equals("FULLTEXT") || type.equals("SPATIAL")) prefix = type + " ";
                    else using = " USING " + type;
                }
            }
        }
        return db == DbType.POSTGRESQL
                ? "CREATE " + prefix + "INDEX " + name + " ON " + qualifiedName + using + columns + tail + ";"
                : "CREATE " + prefix + "INDEX " + name + " ON " + qualifiedName + columns + using + ";";
    }

    /** 인덱스 하나의 DDL 미리보기 (인덱스 관리 화면) */
    public static Result indexDdl(DbType dbType, TableInfo table, TableIndex index, String defaultSchemaName) {
        List<String> warnings = new ArrayList<>();
        return new Result(indexStatement(dbType, qualifiedName(table, defaultSchemaName), index, warnings) + "\n", warnings);
    }

    // ------------------------------------------------------------------ 외래키

    /**
     * 참조 테이블명이 있는 컬럼마다 FK 를 만든다.
     * - 참조 테이블이 등록되어 있지 않거나, PK 가 없거나, 복합 PK 이면 만들지 않고 경고한다.
     * - 자기 자신을 참조(계층 구조)할 수도 있다.
     * - 참조 컬럼과 참조 PK 의 타입(대상 DB 타입으로 바꾼 값)이 다르면 FK 는 만들되 경고한다.
     */
    static List<String> foreignKeys(DbType db, String tableName, String qualifiedName, List<ColumnInfo> columns,
                                    Map<String, List<ColumnInfo>> refPkColumns, Set<String> ambiguous, List<String> warnings, String defaultSchemaName) {
        List<String> statements = new ArrayList<>();

        for (ColumnInfo column : columns) {
            if (!notBlank(column.getRefTableName())) continue;

            String refTable = column.getRefTableName().trim().toUpperCase(Locale.ROOT);
            List<ColumnInfo> pk = refPkColumns.get(refTable);

            if (pk == null && ambiguous.contains(refTable)) {
                warnings.add(Messages.get("{0} : 참조 테이블 {1} 이(가) 여러 스키마에 있어 FK 를 만들지 않았습니다. 스키마명.테이블명으로 지정해주세요.", column.getColumnName(), refTable));
            } else if (pk == null) {
                warnings.add(Messages.get("{0} : 참조 테이블 {1} 이(가) 테이블 관리에 등록되어 있지 않아 FK 를 만들지 않았습니다.", column.getColumnName(), refTable));
            } else if (pk.isEmpty()) {
                warnings.add(Messages.get("{0} : 참조 테이블 {1} 에 PK 가 없어 FK 를 만들지 않았습니다.", column.getColumnName(), refTable));
            } else if (pk.size() > 1) {
                warnings.add(Messages.get("{0} : 참조 테이블 {1} 의 PK 가 복합키({2})라 FK 를 만들지 않았습니다.", column.getColumnName(), refTable,
                        String.join(", ", pk.stream().map(ColumnInfo::getColumnName).toList())));
            } else if (isSelf(pk.get(0), column, refTable, tableName)) {
                warnings.add(Messages.get("{0} : 참조 테이블 {1} 의 PK 가 자기 자신이라 FK 를 만들지 않았습니다.", column.getColumnName(), refTable));
            } else {
                ColumnInfo refPk = pk.get(0);
                String refName = refPk.getTableInfo() == null ? refTable : qualifiedName(refPk.getTableInfo(), defaultSchemaName);
                statements.add("ALTER TABLE " + qualifiedName
                        + " ADD CONSTRAINT " + constraintName(db, "FK_" + tableName + "_" + column.getColumnName())
                        + " FOREIGN KEY (" + column.getColumnName() + ") REFERENCES " + refName + " (" + refPk.getColumnName() + ");");
                checkReferenceType(db, column, refTable, refPk, warnings);
            }
        }
        return statements;
    }

    /** 참조 PK 가 이 컬럼 자신인가 : 저장된 컬럼은 ID 로, ID 가 없으면(미리보기·테스트) 이름으로 */
    private static boolean isSelf(ColumnInfo refPk, ColumnInfo column, String refTable, String tableName) {
        if (refPk == column) return true;
        if (refPk.getId() != null || column.getId() != null) return Objects.equals(refPk.getId(), column.getId());
        return (refTable.equals(tableName) || refTable.endsWith("." + tableName)) && refPk.getColumnName().equals(column.getColumnName());
    }

    /**
     * 사용자 정의 관계(복합 FK 포함). 대상 컬럼 구성이 대상 테이블의 키({@link #referencesKey})일 때만 만들고, 아니면 경고한다.
     * 등록할 때 서버가 같은 기준으로 거절하지만, 등록 뒤에 대상의 PK·UNIQUE 가 바뀐 관계는 남아 있을 수 있다 (자동으로 지우지 않는다).
     * 컬럼 쌍 순서는 관계에 저장한 순서를 그대로 쓴다.
     */
    static List<String> relationForeignKeys(DbType db, String qualifiedName, List<TableRelation> relations,
                                            Map<Long, List<Set<String>>> targetUniqueKeys, List<String> warnings, String defaultSchemaName) {
        List<String> statements = new ArrayList<>();
        for (TableRelation relation : relations) {
            TableInfo target = relation.getTargetTable();
            List<ColumnInfo> sources = relation.getColumns().stream().map(c -> c.getSourceColumn()).toList();
            List<ColumnInfo> targets = relation.getColumns().stream().map(c -> c.getTargetColumn()).toList();
            List<Set<String>> uniqueKeys = target.getId() == null ? List.of() : targetUniqueKeys.getOrDefault(target.getId(), List.of());

            if (!referencesKey(target, targets, uniqueKeys)) {
                warnings.add(Messages.get("{0} : 대상 컬럼이 {1} 의 PK(또는 고유값 컬럼)가 아니라 FK 를 만들지 않았습니다.", relation.getRelationName(), target.getTableName()));
                continue;
            }
            statements.add("ALTER TABLE " + qualifiedName
                    + " ADD CONSTRAINT " + constraintName(db, relation.getRelationName())
                    + " FOREIGN KEY (" + String.join(", ", sources.stream().map(ColumnInfo::getColumnName).toList()) + ") REFERENCES "
                    + qualifiedName(target, defaultSchemaName) + " (" + String.join(", ", targets.stream().map(ColumnInfo::getColumnName).toList()) + ");");
            for (int i = 0; i < sources.size(); i++) {
                checkReferenceType(db, sources.get(i), target.getTableName(), targets.get(i), warnings);
            }
        }
        return statements;
    }

    /**
     * FK 가 참조할 수 있는 키인가 : 대상 컬럼 구성이 ① 대상 테이블 PK 전체 ② 고유값 컬럼 1개 ③ UNIQUE 제약·고유 인덱스 1개의 컬럼 전체와 같을 때.
     * 컬럼 각각이 어떤 키에 들어 있는 것만으로는 인정하지 않는다 (복합 키의 일부, 서로 다른 키의 컬럼 조합은 키가 아니다).
     * FK 등록 검증(물리 모델 서비스)과 DDL 생성이 같은 기준을 쓴다.
     *
     * @param uniqueKeys 대상 테이블의 UNIQUE 제약·고유 인덱스 컬럼명 구성
     */
    public static boolean referencesKey(TableInfo target, List<ColumnInfo> targets, List<Set<String>> uniqueKeys) {
        Set<String> referenced = new HashSet<>(targets.stream().map(ColumnInfo::getColumnName).toList());
        Set<String> pk = new HashSet<>(target.getColumns().stream().filter(c -> Boolean.TRUE.equals(c.getIsPk())).map(ColumnInfo::getColumnName).toList());
        if (!pk.isEmpty() && referenced.equals(pk)) return true;
        if (targets.size() == 1 && Boolean.TRUE.equals(targets.get(0).getIsUnique())) return true;
        return uniqueKeys.stream().anyMatch(referenced::equals);
    }

    /**
     * 기본 스키마가 아닌 스키마의 테이블은 스키마명을 붙인다 (기본 스키마 = 접속 계정의 기본 스키마).
     * 기본 스키마 테이블은 defaultSchemaName 을 받았을 때만 그 이름을 붙인다 (저장된 이름 DEFAULT 는 실제 스키마가 아니다).
     */
    static String qualifiedName(TableInfo table, String defaultSchemaName) {
        if (table.getSchema() == null || Boolean.TRUE.equals(table.getSchema().getIsDefault())) {
            return defaultSchemaName == null ? table.getTableName() : defaultSchemaName + "." + table.getTableName();
        }
        return table.getSchema().getSchemaName() + "." + table.getTableName();
    }

    /**
     * 참조 컬럼과 참조 PK 의 타입 비교 (대상 DB 타입 기준)
     * - 타입 자체가 다르면(VARCHAR ↔ NUMERIC, INTEGER ↔ BIGINT) DB 에 따라 FK 생성이 실패한다.
     * - 길이/정밀도만 다르면(VARCHAR(20) ↔ VARCHAR(30)) 생성은 되지만 값이 들어가지 않거나 잘릴 수 있다.
     */
    private static void checkReferenceType(DbType db, ColumnInfo column, String refTable, ColumnInfo refPk, List<String> warnings) {
        List<String> ignored = new ArrayList<>(); // 길이 미지정 경고는 컬럼 자체에서 이미 알려준다
        String type = mapType(db, column, ignored);
        String refType = mapType(db, refPk, ignored);
        if (type.equals(refType)) return;

        String pair = " (" + column.getColumnName() + " " + type + " ↔ " + refTable + "." + refPk.getColumnName() + " " + refType + ")";
        if (!baseType(type).equals(baseType(refType))) {
            warnings.add(Messages.get("{0} : 참조 PK 와 데이터 타입이 달라 FK 생성이 실패할 수 있습니다.", column.getColumnName()) + pair);
        } else {
            warnings.add(Messages.get("{0} : 참조 PK 와 길이/정밀도가 다릅니다. 값이 들어가지 않거나 잘리지 않도록 맞추는 것을 권장합니다.", column.getColumnName()) + pair);
        }
    }

    /**
     * FK 컬럼과 참조 컬럼의 타입이 대상 DB 에서 FK 로 묶일 수 있다고 확실히 말할 수 있는가 (대상 DB 타입으로 바꾼 값 기준).
     * 확실한 조합만 true 이고, 판단할 수 없는 조합(DB 고유 타입, 서로 다른 타입 계열)은 false 다.
     * <ul>
     *   <li>바꾼 타입이 완전히 같다 (길이·정밀도 포함) : 모든 DB</li>
     *   <li>문자 타입끼리 길이만 다르다 (VARCHAR·VARCHAR2 / CHAR) : 모든 DB</li>
     *   <li>NUMERIC·NUMBER 끼리 정밀도만 다르다 : PostgreSQL·Oracle. MySQL·MariaDB 는 고정 소수 타입의 크기가 같아야 한다</li>
     *   <li>SMALLINT·INTEGER·BIGINT 끼리 : PostgreSQL. (Oracle 은 모두 NUMBER 라 위 규칙, MySQL·MariaDB 는 정수 크기가 같아야 한다)</li>
     * </ul>
     */
    public static boolean isReferenceTypeCompatible(DbType dbType, ColumnInfo column, ColumnInfo referenced) {
        DbType db = DbType.orDefault(dbType);
        List<String> ignored = new ArrayList<>();
        String type = mapType(db, column, ignored);
        String refType = mapType(db, referenced, ignored);
        if (type.equals(refType)) return true;

        String base = baseType(type);
        String refBase = baseType(refType);
        if (base.equals(refBase)) {
            if (STRING_TYPES.contains(base)) return true;
            return (base.equals("NUMERIC") || base.equals("NUMBER")) && !isMySqlFamily(db);
        }
        return db == DbType.POSTGRESQL && INTEGER_TYPES.contains(base) && INTEGER_TYPES.contains(refBase);
    }

    /** 대상 DB 타입으로 바꾼 값 (FK 타입 검증의 오류 문구용) */
    public static String mappedType(DbType dbType, ColumnInfo column) {
        return mapType(DbType.orDefault(dbType), column, new ArrayList<>());
    }

    private static final Set<String> STRING_TYPES = Set.of("VARCHAR", "VARCHAR2", "CHAR");
    private static final Set<String> INTEGER_TYPES = Set.of("SMALLINT", "INTEGER", "BIGINT");

    public static String baseType(String type) {
        int paren = type.indexOf('(');
        return (paren < 0 ? type : type.substring(0, paren)).trim();
    }

    // ------------------------------------------------------------------ 데이터 타입

    /**
     * 공통 타입명을 DB 별 타입으로 바꾼다. 타입에 길이가 함께 적혀 있으면(VARCHAR(100)) 그 값을 먼저 쓴다.
     */
    public static String mapType(DbType db, ColumnInfo column, List<String> warnings) {
        String raw = column.getDataType() == null ? "" : column.getDataType().trim();
        Matcher matcher = TYPE_PATTERN.matcher(raw);

        String base;
        Integer length = column.getMaxLength();
        Integer precision = column.getPrecision() == null ? null : toInt(column.getPrecision());
        Integer scale = column.getScale();

        if (raw.isEmpty()) {
            warnings.add(Messages.get("{0} : 데이터 타입이 없어 문자열(VARCHAR)로 생성했습니다.", column.getColumnName()));
            base = "VARCHAR";
        } else if (matcher.matches()) {
            base = matcher.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (matcher.group(2) != null) {
                int first = Integer.parseInt(matcher.group(2));
                length = first;
                precision = first;
                if (matcher.group(3) != null) scale = Integer.parseInt(matcher.group(3));
            }
        } else {
            return raw; // 해석할 수 없는 타입은 그대로 쓴다 (예: ENUM('A','B'))
        }

        return switch (base) {
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHARACTER VARYING", "STRING" -> {
                Integer len = length != null ? length : defaultLength(db, column, warnings);
                yield switch (db) {
                    case ORACLE -> "VARCHAR2(" + len + ")";
                    default -> len == null ? "VARCHAR" : "VARCHAR(" + len + ")";
                };
            }
            case "CHAR", "NCHAR", "CHARACTER" -> "CHAR(" + (length != null ? length : 1) + ")";
            case "NUMBER", "NUMERIC", "DECIMAL", "DEC" -> {
                Integer p = precision != null ? precision : length;
                String args = p == null ? "" : "(" + p + (scale != null && scale > 0 ? "," + scale : "") + ")";
                yield switch (db) {
                    case ORACLE -> "NUMBER" + args;
                    case POSTGRESQL -> "NUMERIC" + args;
                    default -> "DECIMAL" + args;
                };
            }
            case "INT", "INTEGER", "INT4" -> db == DbType.ORACLE ? "NUMBER(10)" : (isMySqlFamily(db) ? "INT" : "INTEGER");
            case "BIGINT", "INT8", "LONG" -> db == DbType.ORACLE ? "NUMBER(19)" : "BIGINT";
            case "SMALLINT", "INT2" -> db == DbType.ORACLE ? "NUMBER(5)" : "SMALLINT";
            case "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL" -> switch (db) {
                case ORACLE -> "BINARY_DOUBLE";
                case POSTGRESQL -> "DOUBLE PRECISION";
                default -> "DOUBLE";
            };
            case "DATE" -> "DATE";
            case "DATETIME" -> switch (db) {
                case ORACLE -> "DATE";
                case POSTGRESQL -> "TIMESTAMP";
                default -> "DATETIME";
            };
            case "TIMESTAMP" -> isMySqlFamily(db) ? "DATETIME" : "TIMESTAMP";
            case "TIME" -> db == DbType.ORACLE ? "DATE" : "TIME";
            case "BOOLEAN", "BOOL", "BIT" -> switch (db) {
                case ORACLE -> "NUMBER(1)";
                case POSTGRESQL -> "BOOLEAN";
                default -> "TINYINT(1)";
            };
            case "CLOB", "TEXT", "LONGTEXT", "NCLOB" -> switch (db) {
                case ORACLE -> "CLOB";
                case POSTGRESQL -> "TEXT";
                default -> "LONGTEXT";
            };
            case "BLOB", "BYTEA", "LONGBLOB", "BINARY", "VARBINARY" -> switch (db) {
                case ORACLE -> "BLOB";
                case POSTGRESQL -> "BYTEA";
                default -> "LONGBLOB";
            };
            default -> raw.toUpperCase(Locale.ROOT); // 알 수 없는 타입은 입력 그대로 (DB 고유 타입 등)
        };
    }

    /**
     * 문자열 길이가 없을 때 : PostgreSQL 은 길이 없는 VARCHAR, Oracle 4000, MySQL/MariaDB 255
     */
    private static Integer defaultLength(DbType db, ColumnInfo column, List<String> warnings) {
        Integer len = switch (db) {
            case POSTGRESQL -> null;
            case ORACLE -> 4000;
            default -> 255;
        };
        warnings.add(Messages.get("{0} : 최대 길이가 없어 {1} 로 생성했습니다.", column.getColumnName(), len == null ? Messages.get("길이 없는 VARCHAR") : "VARCHAR(" + len + ")"));
        return len;
    }

    // ------------------------------------------------------------------ 보조

    /** DDL 에서 NOT NULL 이 되는지 : PK·필수·NULL 불허. 원본 비교(M-4d)도 같은 기준을 쓴다 */
    public static boolean isNotNull(ColumnInfo column) {
        return Boolean.TRUE.equals(column.getIsPk())
                || Boolean.TRUE.equals(column.getIsRequired())
                || Boolean.FALSE.equals(column.getIsNullable());
    }

    /**
     * 기본값 : 숫자·키워드·함수 호출·이미 따옴표로 감싼 값은 그대로, 나머지는 문자열로 감싼다.
     */
    public static String defaultLiteral(String value) {
        String v = value.trim();
        if (NUMERIC_DEFAULT.matcher(v).matches()
                || RAW_DEFAULTS.contains(v.toUpperCase(Locale.ROOT))
                || v.contains("(")
                || (v.length() >= 2 && v.startsWith("'") && v.endsWith("'"))) {
            return v;
        }
        return quote(v);
    }

    private static String tableComment(TableInfo table) {
        if (notBlank(table.getTableKorName())) return table.getTableKorName().trim();
        if (notBlank(table.getTableDescription())) return table.getTableDescription().trim();
        return null;
    }

    /**
     * 제약/인덱스 이름 : Oracle(12.1 이하 30자), MySQL(64자) 제한을 넘으면 자른다.
     */
    public static String objectName(DbType dbType, String name) {
        return constraintName(DbType.orDefault(dbType), name);
    }

    private static String constraintName(DbType db, String name) {
        int max = switch (db) {
            case ORACLE -> 30;
            case POSTGRESQL -> 63;
            default -> 64;
        };
        return name.length() > max ? name.substring(0, max) : name;
    }

    private static boolean isMySqlFamily(DbType db) {
        return db == DbType.MYSQL || db == DbType.MARIADB;
    }

    private static String quote(String value) {
        return "'" + value.trim().replace("'", "''") + "'";
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Integer toInt(BigDecimal value) {
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            return value.intValue();
        }
    }
}
