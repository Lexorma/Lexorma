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
 * 통합 V5 의 컬럼명 UNIQUE (M7-U01~U04 · M7-U09, docs/completed/column-name-uniqueness-m7.md) : V4 설치 → V5 업그레이드가 기존 데이터를 그대로 두고 (이름을 대문자로 고치지 않는다)
 * 생성 컬럼 COL_NM_CMPR_VL (앞뒤 공백 제거 + 로캘 중립 확장 대문자) 과 UX_COL_01 (TBL_SN, COL_NM_CMPR_VL) 을 만든다. 비교 이름이 같은 기존 데이터가 있으면 V5 는 그 목록을 담은 오류로
 * 스키마를 바꾸기 전에 멈추고 (데이터는 그대로), 사람이 정리한 뒤 다시 실행하면 적용된다. 필수값은 ColumnRequiredValuesMigrationTest, 유니코드·길이는 ColumnNameUnicodeMigrationTest.
 * H2 / PostgreSQL (LEXORMA_TEST_POSTGRES_URL).
 */
class ColumnNameUniqueMigrationTest {

    private record Db(Flyway v4, Flyway latest, JdbcTemplate jdbc, String s) {
    }

    private Db install(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "colkey_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v4 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target(MigrationVersion.fromVersion("4"))
                .cleanDisabled(false).load();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target(MigrationVersion.fromVersion("5"))
                .cleanDisabled(false).load();
        assertThat(v4.migrate().migrationsExecuted).isEqualTo(5);
        return new Db(v4, latest, new JdbcTemplate(dataSource), "\"" + schema + "\".");
    }

    private static void base(Db db) {
        db.jdbc().update("insert into " + db.s() + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000801, 'colkey', true, false, true, 'POSTGRESQL')");
        db.jdbc().update("insert into " + db.s() + "TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (900000802, 900000801, 'DEFAULT', true)");
        for (long t : new long[]{900000810, 900000811}) {
            db.jdbc().update("insert into " + db.s() + "TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, 900000801, 900000802, ?, false)", t, "T" + t);
        }
    }

    private static void column(Db db, long id, long table, String name) {
        db.jdbc().update("insert into " + db.s() + "TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN, MDFCN_DT) values (?, 900000801, ?, ?, ?, true, timestamp '2026-11-01 10:00:00')",
                id, table, "T" + table, name);
    }

    @ParameterizedTest(name = "V4 설치 → V5 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradeKeepsData(String vendor) {
        Db db = install(vendor);
        try {
            base(db);
            // 같은 테이블 안에서는 비교 이름이 모두 다르고, 다른 테이블끼리는 같다 · 소문자·앞뒤 공백 (NULL 이름·부모 없는 컬럼은 V5 의 필수값 검사 — ColumnRequiredValuesMigrationTest)
            column(db, 900000820, 900000810, "CUST_NO");
            column(db, 900000821, 900000810, "cust_nm");
            column(db, 900000822, 900000810, " Memo ");
            column(db, 900000823, 900000811, "cust_no");
            column(db, 900000824, 900000811, "CUST_NM");
            List<Map<String, Object>> before = db.jdbc().queryForList("select COL_SN, TBL_SN, COL_NM, APRV_YN, MDFCN_DT from " + db.s() + "TB_COL where PRJ_SN = 900000801 order by COL_SN");
            List<Map<String, Object>> checksums = db.jdbc().queryForList("select \"version\", \"checksum\" from " + db.s() + "\"flyway_schema_history\" where \"version\" is not null order by \"installed_rank\"");
            int exampleColumns = db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_COL where PRJ_SN = 0", Integer.class);

            assertThat(db.latest().migrate().migrationsExecuted).isOne();
            assertThat(db.latest().validateWithResult().validationSuccessful).isTrue();
            assertThat(db.jdbc().queryForList("select \"version\", \"checksum\" from " + db.s() + "\"flyway_schema_history\" where \"version\" is not null and \"version\" <> '5' order by \"installed_rank\""))
                    .as("V0~V4 체크섬 그대로").isEqualTo(checksums);
            // 기존 데이터 그대로 (이름을 고치지 않는다 — 승인·수정 일시도 그대로)
            assertThat(db.jdbc().queryForList("select COL_SN, TBL_SN, COL_NM, APRV_YN, MDFCN_DT from " + db.s() + "TB_COL where PRJ_SN = 900000801 order by COL_SN")).isEqualTo(before);
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000822", String.class)).isEqualTo("MEMO");
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000821", String.class)).isEqualTo("CUST_NM");
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000823", String.class)).isEqualTo("CUST_NO");
            // 예제 : 새 컬럼 1개 (+ 용어·속성·매핑·제약조건)
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_COL where PRJ_SN = 0", Integer.class)).isEqualTo(exampleColumns + 1);
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_TBL_CNSTRT k join " + db.s() + "TB_TBL t on t.TBL_SN = k.TBL_SN where k.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and k.CNSTRT_NM = 'UX_COL_01'", Integer.class)).isOne();
            // 제약조건이 실제로 있다 : 같은 테이블의 같은 비교 이름은 거절, 다른 테이블은 된다
            String schema = db.s().substring(1, db.s().length() - 2);
            assertThat(db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                    + " and upper(CONSTRAINT_NAME) = 'UX_COL_01' and CONSTRAINT_TYPE = 'UNIQUE'", Integer.class, schema)).isOne();
            assertThatThrownBy(() -> column(db, 900000830, 900000810, "cust_no ")).hasMessageContaining(vendor.equals("h2") ? "UX_COL_01" : "ux_col_01");
            column(db, 900000831, 900000811, " Memo ");
            // 기존 제약조건·인덱스는 그대로 (FK 2개 + PK)
            assertThat(db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                    + " and CONSTRAINT_TYPE in ('FOREIGN KEY', 'PRIMARY KEY')", Integer.class, schema)).isEqualTo(3);
            assertThat(db.latest().migrate().migrationsExecuted).as("다시 띄워도 실행할 것이 없다").isZero();
        } finally {
            db.latest().clean();
        }
    }

    @ParameterizedTest(name = "중복이 있는 V4 → V5 실패 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void duplicatesStopMigrationSafely(String vendor) {
        Db db = install(vendor);
        try {
            base(db);
            column(db, 900000840, 900000810, "CUST_NO");
            column(db, 900000841, 900000810, " cust_no");
            column(db, 900000842, 900000811, "CUST_NO");
            List<Map<String, Object>> before = db.jdbc().queryForList("select COL_SN, TBL_SN, COL_NM from " + db.s() + "TB_COL where PRJ_SN = 900000801 order by COL_SN");

            assertThatThrownBy(() -> db.latest().migrate()).hasStackTraceContaining("V5 컬럼 무결성 사전 검사").hasStackTraceContaining("새 기준 중복 1개 그룹")
                    .hasStackTraceContaining("[중복] TBL_SN=900000810 새 비교 값=CUST_NO : COL_SN 900000840 [CUST_NO] · COL_SN 900000841 [ cust_no]");
            // 데이터·스키마 그대로 : 생성 컬럼·제약조건이 없다
            assertThat(db.jdbc().queryForList("select COL_SN, TBL_SN, COL_NM from " + db.s() + "TB_COL where PRJ_SN = 900000801 order by COL_SN")).isEqualTo(before);
            String schema = db.s().substring(1, db.s().length() - 2);
            assertThat(db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL' and upper(COLUMN_NAME) = 'COL_NM_CMPR_VL'",
                    Integer.class, schema)).isZero();
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "\"flyway_schema_history\" where \"version\" = '5' and \"success\" = true", Integer.class)).isZero();

            // 사람이 데이터를 정리한 뒤 (여기서는 테스트가 만든 행) 다시 실행하면 적용된다 — 실패 이력이 남은 DBMS 는 repair 뒤
            db.jdbc().update("update " + db.s() + "TB_COL set COL_NM = 'CUST_NO2' where COL_SN = 900000841");
            db.latest().repair();
            assertThat(db.latest().migrate().migrationsExecuted).isOne();
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000841", String.class)).isEqualTo("CUST_NO2");
        } finally {
            db.latest().clean();
        }
    }
}
