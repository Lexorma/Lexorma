package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M-14 2차 : 생성한 고급 인덱스 DDL 을 격리된 PostgreSQL 스키마에서 실제로 실행한다 (LEXORMA_TEST_POSTGRES_URL 이 있을 때만, UUID 스키마를 만들고 지운다).
 * 거절하는 조합은 PostgreSQL 도 실제로 거절하는지 확인한다 (규칙이 DB 보다 느슨하거나 엄격하지 않은지).
 */
class AdvancedIndexPostgresDdlTest {

    @Test
    @DisplayName("PostgreSQL 에서 생성 DDL 이 실행된다 : 표현식·부분·정렬·NULLS·INCLUDE·복합, BTREE·HASH·GIN·GIST·SPGIST·BRIN")
    void generatedDdlRunsOnPostgresql() {
        String url = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(url != null, "PostgreSQL 검증 DB 미지정");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "lexorma", "lexorma"));
        String schema = "idx_ddl_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        try {
            jdbc.execute("create table " + schema + ".TB_IDX_T (ID bigint, EMAIL varchar(100), NM text, ACTV_YN boolean, REG_DT timestamp, "
                    + "DOC jsonb, BODY tsvector)");
            TableInfo table = TableInfo.builder().tableName("TB_IDX_T").build();
            ColumnInfo id = col("ID"), email = col("EMAIL"), nm = col("NM"), regDt = col("REG_DT"), doc = col("DOC"), body = col("BODY");

            Map<String, TableIndex> cases = new LinkedHashMap<>();
            cases.put("expression", index("IX_A", null, false, null, m(null, "lower(EMAIL)", null)));
            cases.put("partial", index("IX_B", null, false, "ACTV_YN = true", m(email, null, null)));
            cases.put("sort", index("IX_C", "BTREE", false, null, m(regDt, null, "DESC NULLS LAST"), m(id, null, "NULLS FIRST"), m(nm, null, "DESC")));
            cases.put("unique include", index("UX_D", null, true, null, m(email, null, null), m(nm, null, "INCLUDE"), m(regDt, null, "INCLUDE")));
            cases.put("combined", index("IX_E", null, false, "ACTV_YN = true AND NM <> 'x'", m(null, "lower(EMAIL)", null), m(regDt, null, "DESC"), m(nm, null, "INCLUDE")));
            cases.put("partial unique", index("UX_F", null, true, "ACTV_YN", m(email, null, null)));
            cases.put("hash", index("IX_G", "HASH", false, "ACTV_YN", m(email, null, null)));
            cases.put("gin expression", index("IX_H", "GIN", false, "ACTV_YN", m(null, "DOC -> 'tags'", null), m(doc, null, null)));
            cases.put("gist include", index("IX_I", "GIST", false, null, m(body, null, null), m(nm, null, "INCLUDE")));
            cases.put("spgist include", index("IX_J", "SPGIST", false, null, m(nm, null, null), m(id, null, "INCLUDE")));
            cases.put("brin partial", index("IX_K", "BRIN", false, "REG_DT IS NOT NULL", m(regDt, null, null), m(id, null, null)));

            cases.forEach((label, index) -> {
                TableDdlGenerator.Result ddl = TableDdlGenerator.indexDdl(DbType.POSTGRESQL, table, index, schema);
                assertThat(ddl.warnings()).as(label).isEmpty();
                assertThat(ddl.ddl()).as(label).startsWith("CREATE ");
                jdbc.execute(ddl.ddl()); // 문법·접근 방식 지원 확인
            });
            assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = ?", Integer.class, schema)).isEqualTo(cases.size());

            // 거절하는 조합은 PostgreSQL 도 거절한다
            for (String rejected : List.of(
                    "create index X1 on %s.TB_IDX_T using gin (DOC desc)",                 // 정렬 : BTREE 만
                    "create index X2 on %s.TB_IDX_T using gist (BODY nulls first)",
                    "create index X3 on %s.TB_IDX_T using brin (REG_DT desc)",
                    "create index X4 on %s.TB_IDX_T using hash (EMAIL) include (NM)",     // INCLUDE : BTREE·GIST·SPGIST 만
                    "create index X5 on %s.TB_IDX_T using gin (DOC) include (NM)",
                    "create index X6 on %s.TB_IDX_T using brin (REG_DT) include (ID)",
                    "create index X7 on %s.TB_IDX_T using hash (EMAIL, NM)",              // 키 하나만
                    "create index X8 on %s.TB_IDX_T using spgist (NM, EMAIL)",
                    "create unique index X9 on %s.TB_IDX_T using hash (EMAIL)")) {      // 고유 : BTREE 만
                assertThatThrownBy(() -> jdbc.execute(rejected.formatted(schema))).as(rejected).isNotNull();
            }
            // 생성기·저장 검증 규칙도 같은 조합을 거절한다
            assertThat(TableDdlGenerator.advancedProblem(DbType.POSTGRESQL, "GIN", false, true, false, false)).isNotNull();
            assertThat(TableDdlGenerator.advancedProblem(DbType.POSTGRESQL, "GIST", false, true, false, false)).isNotNull();
            assertThat(TableDdlGenerator.advancedProblem(DbType.POSTGRESQL, "BRIN", false, true, false, false)).isNotNull();
            assertThat(TableDdlGenerator.advancedProblem(DbType.POSTGRESQL, "HASH", false, false, true, false)).isNotNull();
            assertThat(TableDdlGenerator.advancedProblem(DbType.POSTGRESQL, "GIN", false, false, true, false)).isNotNull();
            assertThat(TableDdlGenerator.advancedProblem(DbType.POSTGRESQL, "BRIN", false, false, true, false)).isNotNull();
            assertThat(TableDdlGenerator.keyCountProblem(DbType.POSTGRESQL, "HASH", 2)).isNotNull();
            assertThat(TableDdlGenerator.keyCountProblem(DbType.POSTGRESQL, "SPGIST", 2)).isNotNull();
            assertThat(TableDdlGenerator.indexTypeProblem(DbType.POSTGRESQL, "HASH", true)).isNotNull();
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }

    private static ColumnInfo col(String name) {
        return ColumnInfo.builder().columnName(name).build();
    }

    private static TableIndexColumn m(ColumnInfo column, String expression, String sort) {
        return TableIndexColumn.builder().column(column).expression(expression).sort(sort).build();
    }

    private static TableIndex index(String name, String type, boolean unique, String condition, TableIndexColumn... members) {
        TableIndex index = TableIndex.builder().indexName(name).indexType(type).isUnique(unique).condition(condition).build();
        for (int i = 0; i < members.length; i++) {
            members[i].setTableIndex(index);
            members[i].setColumnOrder(i + 1);
            index.getColumns().add(members[i]);
        }
        return index;
    }
}
