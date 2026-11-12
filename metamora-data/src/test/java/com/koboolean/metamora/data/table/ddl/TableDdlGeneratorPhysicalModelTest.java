package com.koboolean.metamora.data.table.ddl;

import com.koboolean.metamora.core.project.domain.enums.DbType;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.physical.domain.entity.*;
import com.koboolean.metamora.data.schema.domain.entity.SchemaInfo;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-1 물리 모델 DDL : 기존 출력 유지(골든 파일) + 스키마·복합 UNIQUE·CHECK·복합 인덱스·복합 FK
 */
class TableDdlGeneratorPhysicalModelTest {

    @Test
    @DisplayName("기존 입력(컬럼 설정·참조 테이블명)의 DDL 은 M-1 이전과 글자까지 같다 (4개 DBMS, 골든 파일은 변경 전 코드로 생성)")
    void legacyOutputUnchanged() throws IOException {
        for (Map.Entry<String, String> e : LegacyDdlFixtures.outputs().entrySet()) {
            try (InputStream in = getClass().getResourceAsStream("/ddl-golden/" + e.getKey() + ".sql")) {
                assertThat(in).as(e.getKey()).isNotNull();
                assertThat(e.getValue()).as(e.getKey()).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    // ------------------------------------------------------------------ 고정 데이터

    private static final SchemaInfo DEFAULT_SCHEMA = SchemaInfo.builder().schemaName("DEFAULT").isDefault(true).build();
    private static final SchemaInfo SALES = SchemaInfo.builder().schemaName("sales").isDefault(false).build();
    private static final SchemaInfo MASTER = SchemaInfo.builder().schemaName("mst").isDefault(false).build();

    private static TableInfo table(String name, SchemaInfo schema, ColumnInfo... columns) {
        TableInfo table = TableInfo.builder().tableName(name).schema(schema).columns(new ArrayList<>()).build();
        for (ColumnInfo c : columns) {
            c.setTableInfo(table);
            table.getColumns().add(c);
        }
        return table;
    }

    private static ColumnInfo col(String name, String type, boolean pk, int order) {
        return ColumnInfo.builder().columnName(name).dataType(type).isPk(pk).sortOrder(order).build();
    }

    // 대상 : mst.TB_CODE (GRP_CD, CD) 복합 PK
    private final ColumnInfo grpCd = col("GRP_CD", "VARCHAR(10)", true, 1);
    private final ColumnInfo cd = col("CD", "VARCHAR(10)", true, 2);
    private final TableInfo code = table("TB_CODE", MASTER, grpCd, cd, col("CD_NM", "VARCHAR(100)", false, 3));

    // 원본 : sales.TB_ORDER
    private final ColumnInfo orderNo = col("ORDER_NO", "BIGINT", true, 1);
    private final ColumnInfo statGrp = col("STAT_GRP_CD", "VARCHAR(10)", false, 2);
    private final ColumnInfo statCd = col("STAT_CD", "VARCHAR(20)", false, 3);
    private final ColumnInfo custNo = col("CUST_NO", "BIGINT", false, 4);
    private final ColumnInfo orderDt = col("ORDER_DT", "DATE", false, 5);
    private final ColumnInfo amt = col("AMT", "NUMBER(12,2)", false, 6);
    private final TableInfo order = table("TB_ORDER", SALES, orderNo, statGrp, statCd, custNo, orderDt, amt);

    private TableRelation relation(String name, TableInfo source, TableInfo target, ColumnInfo[] sources, ColumnInfo[] targets) {
        TableRelation r = TableRelation.builder().relationName(name).sourceTable(source).targetTable(target)
                .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).build();
        for (int i = 0; i < sources.length; i++) {
            r.getColumns().add(TableRelationColumn.builder().relation(r).sourceColumn(sources[i]).targetColumn(targets[i]).columnOrder(i + 1).build());
        }
        return r;
    }

    private TableConstraint unique(String name, ColumnInfo... columns) {
        TableConstraint c = TableConstraint.builder().constraintName(name).constraintType(TableConstraint.Type.UNIQUE).build();
        for (int i = 0; i < columns.length; i++) {
            c.getColumns().add(TableConstraintColumn.builder().tableConstraint(c).column(columns[i]).columnOrder(i + 1).build());
        }
        return c;
    }

    private TableIndex index(String name, boolean isUnique, ColumnInfo... columns) {
        TableIndex idx = TableIndex.builder().indexName(name).isUnique(isUnique).build();
        for (int i = 0; i < columns.length; i++) {
            idx.getColumns().add(TableIndexColumn.builder().tableIndex(idx).column(columns[i]).columnOrder(i + 1).build());
        }
        return idx;
    }

    private TableDdlGenerator.Extras extras() {
        return new TableDdlGenerator.Extras(
                List.of(unique("UK_TB_ORDER_CUST_DT", custNo, orderDt),
                        TableConstraint.builder().constraintName("CK_TB_ORDER_1").constraintType(TableConstraint.Type.CHECK).checkExpression("AMT >= 0").build()),
                List.of(index("IX_TB_ORDER_DT_CUST", false, orderDt, custNo), index("UX_TB_ORDER_STAT", true, statGrp, statCd)),
                List.of(relation("FK_TB_ORDER_STAT", order, code, new ColumnInfo[]{statGrp, statCd}, new ColumnInfo[]{grpCd, cd})));
    }

    // ------------------------------------------------------------------ 신규 표현

    @Test
    @DisplayName("PostgreSQL : 스키마 접두어, 복합 UNIQUE·CHECK, 복합 인덱스·고유 인덱스, 복합 FK(다른 스키마 참조)")
    void postgresql() {
        TableDdlGenerator.Result result = TableDdlGenerator.generate(DbType.POSTGRESQL, order, order.getColumns(), Map.of(), extras());

        assertThat(result.ddl())
                .contains("CREATE TABLE sales.TB_ORDER (")
                .contains("    CONSTRAINT PK_TB_ORDER PRIMARY KEY (ORDER_NO)")
                .contains("    CONSTRAINT UK_TB_ORDER_CUST_DT UNIQUE (CUST_NO, ORDER_DT)")
                .contains("    CONSTRAINT CK_TB_ORDER_1 CHECK (AMT >= 0)")
                .contains("CREATE INDEX IX_TB_ORDER_DT_CUST ON sales.TB_ORDER (ORDER_DT, CUST_NO);")
                .contains("CREATE UNIQUE INDEX UX_TB_ORDER_STAT ON sales.TB_ORDER (STAT_GRP_CD, STAT_CD);")
                .contains("ALTER TABLE sales.TB_ORDER ADD CONSTRAINT FK_TB_ORDER_STAT FOREIGN KEY (STAT_GRP_CD, STAT_CD) REFERENCES mst.TB_CODE (GRP_CD, CD);");
        // 길이가 다른 컬럼 쌍은 FK 는 만들되 경고
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("STAT_CD : 참조 PK 와 길이/정밀도가 다릅니다."));
    }

    @Test
    @DisplayName("Oracle·MySQL·MariaDB 도 같은 구조, 제약명 길이 제한, MySQL 은 CHECK 무시 버전 경고")
    void otherDbms() {
        for (DbType db : List.of(DbType.ORACLE, DbType.MYSQL, DbType.MARIADB)) {
            TableDdlGenerator.Result result = TableDdlGenerator.generate(db, order, order.getColumns(), Map.of(), extras());
            assertThat(result.ddl()).as(db.name())
                    .contains("CREATE TABLE sales.TB_ORDER (")
                    .contains("UNIQUE (CUST_NO, ORDER_DT)")
                    .contains("CHECK (AMT >= 0)")
                    .contains("FOREIGN KEY (STAT_GRP_CD, STAT_CD) REFERENCES mst.TB_CODE (GRP_CD, CD);");
            assertThat(result.warnings().stream().anyMatch(w -> w.contains("CHECK 제약을 무시"))).as(db.name()).isEqualTo(db == DbType.MYSQL);
        }
    }

    @Test
    @DisplayName("기본 스키마 테이블은 스키마명을 붙이지 않는다")
    void defaultSchemaUnqualified() {
        TableInfo plain = table("TB_PLAIN", DEFAULT_SCHEMA, col("ID", "BIGINT", true, 1));
        String ddl = TableDdlGenerator.generate(DbType.POSTGRESQL, plain, plain.getColumns(), Map.of(), TableDdlGenerator.Extras.NONE).ddl();
        assertThat(ddl).contains("CREATE TABLE TB_PLAIN (").doesNotContain("DEFAULT.");
    }

    @Test
    @DisplayName("대상 컬럼이 PK 전체가 아니면 FK 를 만들지 않고 경고한다 (PK 가 바뀐 경우)")
    void relationToNonKey() {
        TableDdlGenerator.Extras partial = new TableDdlGenerator.Extras(List.of(), List.of(),
                List.of(relation("FK_TB_ORDER_GRP", order, code, new ColumnInfo[]{statGrp}, new ColumnInfo[]{grpCd})));
        TableDdlGenerator.Result result = TableDdlGenerator.generate(DbType.POSTGRESQL, order, order.getColumns(), Map.of(), partial);

        assertThat(result.ddl()).doesNotContain("FOREIGN KEY");
        assertThat(result.warnings()).contains("FK_TB_ORDER_GRP : 대상 컬럼이 TB_CODE 의 PK(또는 고유값 컬럼)가 아니라 FK 를 만들지 않았습니다.");
    }

    @Test
    @DisplayName("참조 키 판단 : PK 전체 / 고유값 컬럼 1개 / UNIQUE 키 1개의 컬럼 전체만 키다 (키의 일부, 서로 다른 키의 조합은 아니다)")
    void referencesKey() {
        ColumnInfo nm = code.getColumns().get(2);
        ColumnInfo alt1 = col("ALT_1", "VARCHAR(10)", false, 4);
        ColumnInfo alt2 = col("ALT_2", "VARCHAR(10)", false, 5);
        ColumnInfo email = col("EMAIL", "VARCHAR(100)", false, 6);
        email.setIsUnique(true);
        TableInfo target = table("TB_T", MASTER, grpCd, cd, nm, alt1, alt2, email);
        List<Set<String>> keys = List.of(Set.of("ALT_1", "ALT_2"));

        assertThat(TableDdlGenerator.referencesKey(target, List.of(grpCd, cd), List.of())).as("복합 PK").isTrue();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(cd, grpCd), List.of())).as("쌍 순서는 달라도 구성이 같으면 키").isTrue();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(grpCd), List.of())).as("복합 PK 의 일부").isFalse();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(email), List.of())).as("고유값 컬럼").isTrue();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(nm), keys)).as("일반 컬럼").isFalse();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(alt1, alt2), keys)).as("복합 UNIQUE").isTrue();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(alt1), keys)).as("복합 UNIQUE 의 일부").isFalse();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(alt1, email), keys)).as("서로 다른 키의 컬럼 조합").isFalse();
        assertThat(TableDdlGenerator.referencesKey(target, List.of(grpCd, cd, alt1), keys)).as("키보다 많은 컬럼").isFalse();
        assertThat(TableDdlGenerator.referencesKey(table("TB_NOPK", MASTER, nm), List.of(), List.of())).as("PK 없는 테이블").isFalse();
    }

    @Test
    @DisplayName("대상 테이블의 복합 UNIQUE 키를 참조하는 FK 를 만든다")
    void relationToCompositeUnique() {
        ColumnInfo alt1 = col("ALT_1", "VARCHAR(10)", false, 4);
        ColumnInfo alt2 = col("ALT_2", "VARCHAR(10)", false, 5);
        TableInfo target = TableInfo.builder().id(7L).tableName("TB_ALT").schema(MASTER).columns(new ArrayList<>()).build();
        for (ColumnInfo c : List.of(col("ID", "BIGINT", true, 1), alt1, alt2)) {
            c.setTableInfo(target);
            target.getColumns().add(c);
        }
        List<TableRelation> relations = List.of(relation("FK_TB_ORDER_ALT", order, target, new ColumnInfo[]{statGrp, statCd}, new ColumnInfo[]{alt1, alt2}));

        TableDdlGenerator.Result keyed = TableDdlGenerator.generate(DbType.POSTGRESQL, order, order.getColumns(), Map.of(),
                new TableDdlGenerator.Extras(List.of(), List.of(), relations, Map.of(7L, List.of(Set.of("ALT_1", "ALT_2")))));
        assertThat(keyed.ddl()).contains("FOREIGN KEY (STAT_GRP_CD, STAT_CD) REFERENCES mst.TB_ALT (ALT_1, ALT_2);");

        TableDdlGenerator.Result notKeyed = TableDdlGenerator.generate(DbType.POSTGRESQL, order, order.getColumns(), Map.of(),
                new TableDdlGenerator.Extras(List.of(), List.of(), relations, Map.of(7L, List.of(Set.of("ALT_1")))));
        assertThat(notKeyed.ddl()).doesNotContain("FOREIGN KEY");
        assertThat(notKeyed.warnings()).anyMatch(w -> w.startsWith("FK_TB_ORDER_ALT : 대상 컬럼이 TB_ALT"));
    }

    @Test
    @DisplayName("FK 타입 호환 : 확실한 조합만 인정한다 (DB 별)")
    void referenceTypeCompatibility() {
        record Case(String type, String refType, boolean postgresql, boolean oracle, boolean mysql) {}
        List<Case> cases = List.of(
                new Case("VARCHAR(10)", "VARCHAR(10)", true, true, true),
                new Case("VARCHAR(20)", "VARCHAR(10)", true, true, true),      // 문자 길이 차이
                new Case("VARCHAR2(20)", "VARCHAR(10)", true, true, true),     // 공통 타입명만 다르고 같은 DB 타입
                new Case("CHAR(2)", "CHAR(4)", true, true, true),
                new Case("CHAR(10)", "VARCHAR(10)", false, false, false),
                new Case("BIGINT", "BIGINT", true, true, true),
                new Case("INTEGER", "BIGINT", true, true, false),              // PostgreSQL 정수 계열, Oracle NUMBER(10)↔NUMBER(19), MySQL 은 크기가 같아야 함
                new Case("NUMBER(10)", "NUMBER(12)", true, true, false),       // MySQL·MariaDB 는 DECIMAL 크기가 같아야 함
                new Case("NUMBER(12,2)", "NUMBER(12,2)", true, true, true),
                new Case("BIGINT", "NUMBER(19)", false, true, false),          // Oracle 만 둘 다 NUMBER(19)
                new Case("BIGINT", "VARCHAR(20)", false, false, false),
                new Case("DATE", "TIMESTAMP", false, false, false),
                new Case("DATE", "DATE", true, true, true),
                new Case("TEXT", "VARCHAR(10)", false, false, false),
                new Case("GEOMETRY", "GEOMETRY", true, true, true),            // DB 고유 타입은 같을 때만
                new Case("GEOMETRY", "POINT", false, false, false));
        for (Case c : cases) {
            ColumnInfo column = col("A", c.type(), false, 1);
            ColumnInfo referenced = col("B", c.refType(), true, 1);
            String name = c.type() + " ↔ " + c.refType();
            assertThat(TableDdlGenerator.isReferenceTypeCompatible(DbType.POSTGRESQL, column, referenced)).as("PostgreSQL " + name).isEqualTo(c.postgresql());
            assertThat(TableDdlGenerator.isReferenceTypeCompatible(DbType.ORACLE, column, referenced)).as("Oracle " + name).isEqualTo(c.oracle());
            assertThat(TableDdlGenerator.isReferenceTypeCompatible(DbType.MYSQL, column, referenced)).as("MySQL " + name).isEqualTo(c.mysql());
            assertThat(TableDdlGenerator.isReferenceTypeCompatible(DbType.MARIADB, column, referenced)).as("MariaDB " + name).isEqualTo(c.mysql());
        }
    }

    @Test
    @DisplayName("컬럼 설정과 같은 구성의 UNIQUE·인덱스는 한 번만 만든다")
    void deduplicate() {
        custNo.setIsUnique(true);
        orderDt.setIsIndex(true);
        TableDdlGenerator.Extras duplicates = new TableDdlGenerator.Extras(
                List.of(unique("UK_DUP", custNo)), List.of(index("IX_DUP", false, orderDt), index("UX_DUP", true, orderNo)), List.of());

        String ddl = TableDdlGenerator.generate(DbType.POSTGRESQL, order, order.getColumns(), Map.of(), duplicates).ddl();

        assertThat(ddl).contains("CONSTRAINT UK_TB_ORDER_CUST_NO UNIQUE (CUST_NO)")
                .contains("CREATE INDEX IX_TB_ORDER_ORDER_DT ON sales.TB_ORDER (ORDER_DT);")
                .doesNotContain("UK_DUP").doesNotContain("IX_DUP").doesNotContain("UX_DUP"); // UX_DUP = PK 와 같은 구성
    }
}
