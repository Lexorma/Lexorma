package com.koboolean.metamora;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2(M-1 물리 모델) 업그레이드 : V1 까지 적용된 DB 에 기존 형태의 데이터를 넣고 V2 를 적용한다.
 * - 프로젝트(삭제된 프로젝트의 테이블 포함)마다 기본 스키마, 모든 테이블에 스키마 지정
 * - ref_table_name 중 확정 가능한 것만 관계(REF_COLUMN)로, 나머지는 ref_table_name 만 남는다
 * - column_info / table_info 의 기존 값은 그대로
 * H2 는 항상, PostgreSQL 은 METAMORA_PG_MIGRATION_URL(빈 DB) 이 있을 때만 실행한다.
 */
class PhysicalModelMigrationTest {

    @Test
    @DisplayName("H2 : V1 → V2 업그레이드 데이터 이전")
    void h2Upgrade() throws SQLException {
        upgrade("jdbc:h2:mem:m1-upgrade-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "", "h2");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "METAMORA_PG_MIGRATION_URL", matches = ".+")
    @DisplayName("PostgreSQL : V1 → V2 업그레이드 데이터 이전 (빈 DB 필요)")
    void postgresqlUpgrade() throws SQLException {
        upgrade(System.getenv("METAMORA_PG_MIGRATION_URL"), System.getenv().getOrDefault("METAMORA_PG_USER", "metamora"),
                System.getenv().getOrDefault("METAMORA_PG_PASSWORD", "metamora"), "postgresql");
    }

    private void upgrade(String url, String user, String password, String vendor) throws SQLException {
        Flyway v1 = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration/" + vendor).target("1").load();
        v1.migrate();

        try (Connection conn = DriverManager.getConnection(url, user, password); Statement st = conn.createStatement()) {
            // 프로젝트 0(MAIN, 시드) / 10 / 99(삭제되어 project 행 없음)
            st.execute("insert into project(project_id, project_name, is_active, db_type) values (10, 'P10', true, 'ORACLE')");

            // 테이블 : (id, project, name)
            table(st, 1, 0, "TB_A");        // 단일 PK → 확정 가능
            table(st, 2, 0, "TB_COMP");     // 복합 PK
            table(st, 3, 0, "TB_NOPK");     // PK 없음
            table(st, 4, 0, "TB_SELF");     // 자기 참조
            table(st, 5, 0, "TB_DUP");      // 같은 이름 2개 → 모호
            table(st, 6, 0, "TB_DUP");
            table(st, 7, 0, "TB_EMPTY");    // 컬럼 없음
            table(st, 8, 0, "TB_CHILD");    // 참조하는 쪽
            table(st, 20, 10, "TB_A");      // 다른 프로젝트의 같은 이름
            table(st, 21, 10, "TB_CHILD");
            table(st, 30, 99, "TB_ORPHAN"); // project 행이 없는 테이블

            // 컬럼 : (id, project, table, name, pk, ref, unique, index)
            column(st, 101, 0, 1L, "A_ID", true, null, true, false);
            column(st, 102, 0, 2L, "GRP", true, null, true, false);
            column(st, 103, 0, 2L, "CD", true, null, false, false);
            column(st, 104, 0, 3L, "NOPK_VAL", false, null, false, true);
            column(st, 105, 0, 4L, "SELF_ID", true, "TB_SELF", true, false);   // 자기 PK 를 가리킴 → 관계 없음
            column(st, 106, 0, 4L, "PARENT_ID", false, "tb_self", false, true); // 자기 참조 → 관계
            column(st, 107, 0, 5L, "DUP_ID", true, null, false, false);
            column(st, 108, 0, 6L, "DUP_ID", true, null, false, false);
            column(st, 111, 0, 8L, "A_ID", false, " tb_a ", false, true);       // 공백·소문자 → TB_A(1)
            column(st, 112, 0, 8L, "COMP_ID", false, "TB_COMP", false, false);
            column(st, 113, 0, 8L, "NOPK_ID", false, "TB_NOPK", false, false);
            column(st, 114, 0, 8L, "NONE_ID", false, "TB_NONE", false, false);
            column(st, 115, 0, 8L, "DUP_ID", false, "TB_DUP", false, false);
            column(st, 116, 0, 8L, "BLANK", false, "  ", true, true);
            column(st, 117, 0, null, "NO_TABLE", false, "TB_A", false, false); // 소속 테이블 없음 (불완전)
            column(st, 201, 10, 20L, "A_ID", true, null, true, false);
            column(st, 211, 10, 21L, "A_ID", false, "TB_A", false, false);     // 프로젝트 10 의 TB_A(20)
            column(st, 301, 99, 30L, "ORPHAN_ID", true, null, true, false);
        }

        String columnsBefore = snapshot(url, user, password, "select * from column_info order by column_info_id");
        String tablesBefore = snapshot(url, user, password, "select table_info_id, project_id, table_name, is_approval from table_info order by table_info_id");

        Flyway latest = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration/" + vendor).load();
        latest.migrate();
        assertThat(latest.migrate().migrationsExecuted).as("다시 실행해도 적용할 것 없음").isZero();

        assertThat(snapshot(url, user, password, "select * from column_info order by column_info_id")).as("컬럼 데이터 보존").isEqualTo(columnsBefore);
        assertThat(snapshot(url, user, password, "select table_info_id, project_id, table_name, is_approval from table_info order by table_info_id"))
                .as("테이블 데이터 보존").isEqualTo(tablesBefore);

        assertThat(rows(url, user, password, "select project_id, schema_name, is_default from schema_info order by project_id"))
                .containsExactly("0|DEFAULT|true", "10|DEFAULT|true", "99|DEFAULT|true");
        assertThat(rows(url, user, password, "select count(*) from table_info t join schema_info s on s.schema_info_id = t.schema_info_id"
                + " where s.project_id = t.project_id and s.is_default = true")).containsExactly("11");

        // 관계 : (원본 컬럼 → 대상 컬럼, 원본 테이블 → 대상 테이블)
        assertThat(rows(url, user, password, "select rc.source_column_id, rc.target_column_id, r.source_table_id, r.target_table_id, r.relation_name, r.relation_type, r.relation_source, rc.column_order"
                + " from table_relation r join table_relation_column rc on rc.table_relation_id = r.table_relation_id order by rc.source_column_id"))
                .containsExactly(
                        "106|105|4|4|FK_TB_SELF_PARENT_ID|FK|REF_COLUMN|1",
                        "111|101|8|1|FK_TB_CHILD_A_ID|FK|REF_COLUMN|1",
                        "211|201|21|20|FK_TB_CHILD_A_ID|FK|REF_COLUMN|1");
        assertThat(rows(url, user, password, "select count(*) from table_index")).containsExactly("0");
        assertThat(rows(url, user, password, "select count(*) from table_constraint")).containsExactly("0");
    }

    private static void table(Statement st, long id, long projectId, String name) throws SQLException {
        st.execute("insert into table_info(table_info_id, project_id, table_name, is_approval) values (" + id + ", " + projectId + ", '" + name + "', true)");
    }

    private static void column(Statement st, long id, long projectId, Long tableId, String name, boolean pk, String ref, boolean unique, boolean index) throws SQLException {
        st.execute("insert into column_info(column_info_id, project_id, table_info_id, table_name, column_name, data_type, is_pk, is_unique, is_index, is_approval, ref_table_name)"
                + " values (" + id + ", " + projectId + ", " + tableId + ", 'T', '" + name + "', 'BIGINT', " + pk + ", " + unique + ", " + index + ", true, "
                + (ref == null ? "null" : "'" + ref + "'") + ")");
    }

    private static List<String> rows(String url, String user, String password, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= n; i++) values.add(String.valueOf(rs.getObject(i)).toLowerCase().equals("true") ? "true" : String.valueOf(rs.getObject(i)));
                rows.add(String.join("|", values));
            }
        }
        return rows;
    }

    /** 컬럼 이름 → 값 (컬럼 순서와 무관하게 비교) */
    private static String snapshot(String url, String user, String password, String sql) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, String> row = new TreeMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnName(i).toLowerCase(), String.valueOf(rs.getObject(i)));
                }
                rows.add(row);
            }
        }
        return rows.toString();
    }
}
