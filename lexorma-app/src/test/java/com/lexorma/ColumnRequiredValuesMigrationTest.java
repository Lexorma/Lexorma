package com.lexorma;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 컬럼 필수값 (M7-U05 · M7-U09, docs/completed/column-name-uniqueness-m7.md 10·15장) : TBL_SN·COL_NM NOT NULL + CK_COL_01 (빈 이름 = 빈 문자열 또는 ASCII 공백류만).
 * 2026-11-22 네 번째 합침으로 예전 V5 는 V0·V1 에 있다 — 새 설치(V0·V1)의 제약과 예제를 본다 (예전 V4 → V5 업그레이드·사전 검사는 경로가 없어 뺐다).
 * 정규화·중복은 ColumnNameUniqueMigrationTest · ColumnNameUnicodeMigrationTest. H2 / PostgreSQL (LEXORMA_TEST_POSTGRES_URL).
 */
class ColumnRequiredValuesMigrationTest {

    private record Db(JdbcTemplate jdbc, String s, String schema, DriverManagerDataSource ds, String vendor) {
        Flyway flyway(String target) {
            var config = Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false);
            return (target == null ? config : config.target(MigrationVersion.fromVersion(target))).load();
        }
    }

    private Db install(String vendor, String target, int expected) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "colreq_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var ds = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Db db = new Db(new JdbcTemplate(ds), "\"" + schema + "\".", schema, ds, vendor);
        assertThat(db.flyway(target).migrate().migrationsExecuted).isEqualTo(expected);
        db.jdbc().update("insert into " + db.s() + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000901, 'colreq', true, false, true, 'POSTGRESQL')");
        db.jdbc().update("insert into " + db.s() + "TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (900000902, 900000901, 'DEFAULT', true)");
        db.jdbc().update("insert into " + db.s() + "TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (900000910, 900000901, 900000902, 'T1', false)");
        return db;
    }

    private static void column(Db db, long id, Long table, String name) {
        db.jdbc().update("insert into " + db.s() + "TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN, MDFCN_DT) values (?, 900000901, ?, 'T1', ?, true, timestamp '2026-11-01 10:00:00')",
                id, table, name);
    }

    private static String nullable(Db db, String column) {
        return db.jdbc().queryForObject("select IS_NULLABLE from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL' and upper(COLUMN_NAME) = ?",
                String.class, db.schema(), column);
    }

    private static int checks(Db db) {
        return db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                + " and upper(CONSTRAINT_NAME) = 'CK_COL_01' and CONSTRAINT_TYPE = 'CHECK'", Integer.class, db.schema());
    }

    @ParameterizedTest(name = "새 설치 필수값 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void freshInstallEnforces(String vendor) {
        Db db = install(vendor, null, 2);
        try {
            column(db, 900000920, 900000910L, "CUST_NO");
            column(db, 900000921, 900000910L, "고객번호");
            column(db, 900000922, 900000910L, "A\tB");      // 가운데 탭 : 빈 이름이 아니다
            column(db, 900000923, 900000910L, "\u00A0");     // NBSP 만 : 빈 이름으로 보지 않는다
            column(db, 900000924, 900000910L, "顧客番号");
            column(db, 900000925, 900000910L, "cust_nm");
            assertThat(db.jdbc().queryForList("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN in (900000920, 900000925) order by COL_SN", String.class))
                    .containsExactly("CUST_NO", "CUST_NM");
            assertThat(nullable(db, "TBL_SN")).isEqualTo("NO");
            assertThat(nullable(db, "COL_NM")).isEqualTo("NO");
            assertThat(checks(db)).isOne();
            // 직접 SQL : NULL 부모·NULL 이름·빈 이름(빈 문자열·공백·탭·개행류) 거절, 가운데 공백류·NBSP·비영문은 허용, UNIQUE 그대로
            for (Object[] bad : new Object[][]{{null, "X1"}, {900000910L, null}, {900000910L, ""}, {900000910L, "   "}, {900000910L, "\t"}, {900000910L, " \n\r\u000B\f "}}) {
                assertThatThrownBy(() -> column(db, 900000930, (Long) bad[0], (String) bad[1])).as(String.valueOf(bad[1])).isInstanceOf(org.springframework.dao.DataAccessException.class);
            }
            column(db, 900000931, 900000910L, "A B");
            column(db, 900000932, 900000910L, "\u3000");
            assertThatThrownBy(() -> column(db, 900000933, 900000910L, " cust_no ")).hasMessageContaining(vendor.equals("h2") ? "UX_COL_01" : "ux_col_01");
            // 예제 : TB_COL.TBL_SN·COL_NM NULL 불가·필수, 논리 속성 필수, 예제 CHECK
            assertThat(db.jdbc().queryForList("select COL_NM || ':' || case when NULL_ALWNC_YN then 'Y' else 'N' end || ':' || case when ESNTL_YN then 'Y' else 'N' end from " + db.s() + "TB_COL where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM in ('TBL_SN', 'COL_NM') order by COL_NM", String.class))
                    .containsExactly("COL_NM:N:Y", "TBL_SN:N:Y");
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_LGCL_ATRB a join " + db.s() + "TB_ATRB_COL_MPNG m on m.LGCL_ATRB_SN = a.LGCL_ATRB_SN join "
                    + db.s() + "TB_COL c on c.COL_SN = m.COL_SN where c.PRJ_SN = 0 and c.TBL_NM = 'TB_COL' and c.COL_NM in ('TBL_SN', 'COL_NM') and a.ESNTL_YN = true", Integer.class)).isEqualTo(2);
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_TBL_CNSTRT k join " + db.s() + "TB_TBL t on t.TBL_SN = k.TBL_SN where k.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and k.CNSTRT_NM = 'CK_COL_01'", Integer.class)).isOne();
            assertThat(db.flyway(null).migrate().migrationsExecuted).isZero();
        } finally {
            db.flyway(null).clean();
        }
    }
}
