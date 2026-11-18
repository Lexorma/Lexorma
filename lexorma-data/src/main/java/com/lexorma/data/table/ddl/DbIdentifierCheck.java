package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.table.ddl.DbIdentifierRules.Code;
import com.lexorma.data.table.ddl.DbIdentifierRules.Issue;
import com.lexorma.data.table.ddl.DbIdentifierRules.Kind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 모델 전체(또는 생성 계획)의 DBMS 식별자 판정 (M-7 보강 1차). 값만 받아 판정하는 순수 함수다 — 표준화 품질 진단(M-5)과 논리 → 물리 생성 미리보기가 같이 쓴다.
 * <ul>
 *   <li>스키마(기본 스키마 제외 — DDL 에 이름이 나가지 않는다)·테이블·컬럼 이름 : {@link DbIdentifierRules#check}</li>
 *   <li>DDL 이 자동으로 만드는 제약조건명 : PK_테이블(PK 컬럼이 있을 때), UK_테이블_컬럼(PK 가 아닌 고유값 컬럼), FK_테이블_컬럼(참조 테이블명이 관계(REF_COLUMN)가 된 컬럼 —
 *       DDL 의 FK 조건과 같다). 잘리면 {@link Code#CONSTRAINT_NAME_TRUNCATED} (MySQL·MariaDB 의 PK 는 이름을 쓰지 않아 제외)</li>
 *   <li>충돌 : DDL 의 최종 이름(잘린 뒤, 대소문자 무시)이 같은 이름 공간({@link #sameNamespace})에서 겹친다. 자동 이름이나 잘린 이름이 낀 충돌만 알린다 —
 *       저장된 이름끼리(사용자 UNIQUE·CHECK·인덱스·FK 관계 이름)의 그대로 중복은 각 저장 화면이 다룬다</li>
 * </ul>
 * VIEW 는 대상이 아니다 (표준화 품질 진단의 테이블명 규칙과 같다). 예약어도 보지 않는다 (P13).
 */
public final class DbIdentifierCheck {

    public record Schema(Long id, String name, boolean isDefault) {
    }

    public record Table(Long id, Long schemaId, String name) {
    }

    /** referenceFk : 참조 테이블명이 REF_COLUMN 관계가 되어 DDL 이 FK 를 만드는 컬럼 */
    public record Column(Long id, Long tableId, String name, boolean pk, boolean unique, boolean referenceFk) {
    }

    /** DDL 이 이름을 그대로 쓰는 저장된 이름 : 사용자 UNIQUE·CHECK 제약, 인덱스, 사용자 FK 관계 (kind UNIQUE·CHECK·INDEX·FOREIGN_KEY) */
    public record StoredName(Kind kind, Long id, Long tableId, String name) {
    }

    public record Model(List<Schema> schemas, List<Table> tables, List<Column> columns, List<StoredName> storedNames) {
    }

    /**
     * DDL 에 나가는 제약조건·인덱스 이름 1개. generated : DDL 이 만드는 이름(PK_·UK_·FK_), 아니면 저장된 이름.
     * owner : 자동 이름은 PK = 테이블, UK·FK = 컬럼 ID / 저장된 이름은 그 객체 ID
     */
    public record DdlName(Kind kind, Long schemaId, Long tableId, Long columnId, Long storedId, String original, String ddlName, boolean generated) {
        public boolean truncated() {
            return !original.equals(ddlName);
        }
    }

    /** 판정 1건. tableId·columnId 는 대상 (스키마는 둘 다 null), others 는 충돌한 다른 이름 */
    public record Finding(Issue issue, Long schemaId, Long tableId, Long columnId, List<DdlName> others) {
    }

    private DbIdentifierCheck() {
    }

    /** 순서 : 스키마 → 테이블 (테이블 이름 → 그 테이블의 컬럼 → 제약조건명) 의 입력 순서 */
    public static List<Finding> check(DbType dbType, Model model) {
        DbType db = DbType.orDefault(dbType);
        List<Finding> findings = new ArrayList<>();
        for (Schema schema : model.schemas()) {
            if (schema.isDefault()) continue;
            DbIdentifierRules.check(db, Kind.SCHEMA, schema.name()).forEach(issue -> findings.add(new Finding(issue, schema.id(), null, null, List.of())));
        }
        Map<Long, List<Column>> columnsByTable = new HashMap<>();
        model.columns().forEach(c -> columnsByTable.computeIfAbsent(c.tableId(), id -> new ArrayList<>()).add(c));
        List<DdlName> names = ddlNames(db, model);
        Map<Long, List<DdlName>> generatedByTable = new HashMap<>();
        names.stream().filter(DdlName::generated).forEach(n -> generatedByTable.computeIfAbsent(n.tableId(), id -> new ArrayList<>()).add(n));
        Map<DdlName, List<DdlName>> collisions = collisions(db, names);

        for (Table table : model.tables()) {
            DbIdentifierRules.check(db, Kind.TABLE, table.name()).forEach(issue -> findings.add(new Finding(issue, table.schemaId(), table.id(), null, List.of())));
            for (Column column : columnsByTable.getOrDefault(table.id(), List.of())) {
                DbIdentifierRules.check(db, Kind.COLUMN, column.name()).forEach(issue -> findings.add(new Finding(issue, table.schemaId(), table.id(), column.id(), List.of())));
            }
            for (DdlName name : generatedByTable.getOrDefault(table.id(), List.of())) {
                boolean namedPk = !(name.kind() == Kind.PRIMARY_KEY && mysqlFamily(db));
                Issue truncated = namedPk ? DbIdentifierRules.truncation(db, name.kind(), name.original()) : null;
                if (truncated != null) findings.add(new Finding(truncated, name.schemaId(), name.tableId(), name.columnId(), List.of()));
                List<DdlName> others = collisions.get(name);
                if (others != null) {
                    findings.add(new Finding(new Issue(Code.CONSTRAINT_NAME_COLLISION, name.kind(), name.original(), name.ddlName(), null, null),
                            name.schemaId(), name.tableId(), name.columnId(), others));
                }
            }
        }
        // 저장된 이름이 잘려서 다른 이름과 겹친 경우 (자동 이름이 끼지 않은 충돌) : 잘린 쪽에 알린다
        for (DdlName name : names) {
            if (name.generated() || !name.truncated()) continue;
            List<DdlName> others = collisions.get(name);
            if (others != null) {
                findings.add(new Finding(new Issue(Code.CONSTRAINT_NAME_COLLISION, name.kind(), name.original(), name.ddlName(), null, null),
                        name.schemaId(), name.tableId(), null, others));
            }
        }
        return findings;
    }

    /** DDL 이 쓰는 제약조건·인덱스 이름 전체 (자동 이름 → 저장된 이름 순) */
    public static List<DdlName> ddlNames(DbType dbType, Model model) {
        DbType db = DbType.orDefault(dbType);
        Map<Long, Table> tables = new LinkedHashMap<>();
        model.tables().forEach(t -> tables.put(t.id(), t));
        Map<Long, List<Column>> columnsByTable = new HashMap<>();
        model.columns().forEach(c -> columnsByTable.computeIfAbsent(c.tableId(), id -> new ArrayList<>()).add(c));
        List<DdlName> names = new ArrayList<>();
        for (Table table : tables.values()) {
            List<Column> columns = columnsByTable.getOrDefault(table.id(), List.of());
            if (columns.stream().anyMatch(Column::pk)) {
                String name = DbIdentifierRules.primaryKeyName(table.name());
                names.add(new DdlName(Kind.PRIMARY_KEY, table.schemaId(), table.id(), null, null, name, DbIdentifierRules.ddlName(db, name), true));
            }
            for (Column column : columns) {
                if (column.unique() && !column.pk()) {
                    String name = DbIdentifierRules.uniqueName(table.name(), column.name());
                    names.add(new DdlName(Kind.UNIQUE, table.schemaId(), table.id(), column.id(), null, name, DbIdentifierRules.ddlName(db, name), true));
                }
                if (column.referenceFk()) {
                    String name = DbIdentifierRules.foreignKeyName(table.name(), column.name());
                    names.add(new DdlName(Kind.FOREIGN_KEY, table.schemaId(), table.id(), column.id(), null, name, DbIdentifierRules.ddlName(db, name), true));
                }
            }
        }
        for (StoredName stored : model.storedNames()) {
            Table table = tables.get(stored.tableId());
            if (table == null || stored.name() == null || stored.name().isBlank()) continue;
            names.add(new DdlName(stored.kind(), table.schemaId(), table.id(), null, stored.id(), stored.name(), DbIdentifierRules.ddlName(db, stored.name()), false));
        }
        return names;
    }

    /** 이름 → 같은 이름 공간에서 최종 이름이 같은 다른 이름들 (자동 이름이나 잘린 이름이 낀 것만) */
    static Map<DdlName, List<DdlName>> collisions(DbType db, List<DdlName> names) {
        Map<String, List<DdlName>> byName = new LinkedHashMap<>();
        names.forEach(n -> byName.computeIfAbsent(n.ddlName().toUpperCase(Locale.ROOT), key -> new ArrayList<>()).add(n));
        Map<DdlName, List<DdlName>> result = new IdentityHashMap<>(); // 값이 같은 두 이름도 따로 본다
        for (List<DdlName> group : byName.values()) {
            if (group.size() < 2) continue;
            for (DdlName name : group) {
                List<DdlName> others = group.stream().filter(other -> other != name && sameNamespace(db, name, other))
                        .filter(other -> name.generated() || name.truncated() || other.generated() || other.truncated())
                        .toList();
                if (!others.isEmpty()) result.put(name, others);
            }
        }
        return result;
    }

    /**
     * 두 이름이 같은 DBMS 이름 공간인가 (확인한 범위 — 문서 docs/completed/logical-physical-mapping-m7.md).
     * <ul>
     *   <li>PostgreSQL : PK·UNIQUE·인덱스는 인덱스(릴레이션)라 스키마 안에서 하나, 제약조건(PK·UNIQUE·FK·CHECK)은 테이블 안에서 하나</li>
     *   <li>Oracle : 제약조건은 스키마 안에서 하나, 인덱스(PK·UNIQUE 가 만드는 인덱스 포함)도 스키마 안에서 하나</li>
     *   <li>MySQL : PK 는 이름이 PRIMARY 로 고정이라 제외, FK·CHECK 는 스키마(데이터베이스) 안에서 하나, UNIQUE·인덱스는 테이블 안에서 하나</li>
     *   <li>MariaDB : PK 제외, FK 는 스키마 안에서 하나, UNIQUE·인덱스는 테이블 안에서 하나, CHECK 는 테이블 안에서 하나</li>
     * </ul>
     */
    public static boolean sameNamespace(DbType dbType, DdlName a, DdlName b) {
        boolean sameSchema = Objects.equals(a.schemaId(), b.schemaId());
        boolean sameTable = Objects.equals(a.tableId(), b.tableId());
        Set<Kind> indexLike = Set.of(Kind.PRIMARY_KEY, Kind.UNIQUE, Kind.INDEX);
        Set<Kind> constraint = Set.of(Kind.PRIMARY_KEY, Kind.UNIQUE, Kind.FOREIGN_KEY, Kind.CHECK);
        return switch (DbType.orDefault(dbType)) {
            case POSTGRESQL -> (sameSchema && indexLike.contains(a.kind()) && indexLike.contains(b.kind()))
                    || (sameTable && constraint.contains(a.kind()) && constraint.contains(b.kind()));
            case ORACLE -> sameSchema && ((constraint.contains(a.kind()) && constraint.contains(b.kind()))
                    || (indexLike.contains(a.kind()) && indexLike.contains(b.kind())));
            case MYSQL -> {
                if (a.kind() == Kind.PRIMARY_KEY || b.kind() == Kind.PRIMARY_KEY) yield false;
                Set<Kind> schemaWide = Set.of(Kind.FOREIGN_KEY, Kind.CHECK);
                Set<Kind> tableWide = Set.of(Kind.UNIQUE, Kind.INDEX);
                yield (sameSchema && schemaWide.contains(a.kind()) && schemaWide.contains(b.kind()))
                        || (sameTable && tableWide.contains(a.kind()) && tableWide.contains(b.kind()));
            }
            case MARIADB -> {
                if (a.kind() == Kind.PRIMARY_KEY || b.kind() == Kind.PRIMARY_KEY) yield false;
                Set<Kind> tableWide = Set.of(Kind.UNIQUE, Kind.INDEX);
                yield (sameSchema && a.kind() == Kind.FOREIGN_KEY && b.kind() == Kind.FOREIGN_KEY)
                        || (sameTable && tableWide.contains(a.kind()) && tableWide.contains(b.kind()))
                        || (sameTable && a.kind() == Kind.CHECK && b.kind() == Kind.CHECK);
            }
        };
    }

    private static boolean mysqlFamily(DbType db) {
        return db == DbType.MYSQL || db == DbType.MARIADB;
    }
}
