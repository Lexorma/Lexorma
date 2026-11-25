package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 컬럼명 UNIQUE (M7-U01~U04 · M7-U09, docs/completed/column-name-uniqueness-m7.md) — 2026-11-22 네 번째 합침으로 예전 V5 는 V0·V1 에 있다.
 * 새 설치의 생성 컬럼 COL_NM_CMPR_VL (앞뒤 공백 제거 + 로캘 중립 확장 대문자) 과 UX_COL_01 (TBL_SN, COL_NM_CMPR_VL) 을 본다 (예전 V4 → V5 업그레이드·사전 검사는 경로가 없어 뺐다).
 * 필수값은 ColumnRequiredValuesMigrationTest, 유니코드·길이는 ColumnNameUnicodeMigrationTest. 테스트 DB 는 TestDatabaseGuard.directDataSource (LEXORMA_TEST_POSTGRES_*).
 */
class ColumnNameUniqueMigrationTest {

    private record Db(Flyway latest, JdbcTemplate jdbc, String s) {
    }

    private Db install() {
        String schema = "colkey_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        // 2026-11-22 네 번째 합침 : 예전 V5(컬럼 데이터 무결성)는 V0·V1 에 있다 — 새 설치(V0·V1)의 제약을 본다
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(4); // V0~V3 (V3 : 2026-11-23 변환 전략 CHECK 재정의)
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        return new Db(latest, new JdbcTemplate(dataSource), "\"" + schema + "\".");
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

    @Test
    @DisplayName("새 설치 컬럼명 비교 값·UNIQUE PostgreSQL")
    void freshInstallEnforcesUniqueNames() {
        Db db = install();
        try {
            base(db);
            column(db, 900000820, 900000810, "CUST_NO");
            column(db, 900000821, 900000810, "cust_nm");
            column(db, 900000822, 900000810, " Memo ");
            column(db, 900000823, 900000811, "cust_no");
            column(db, 900000824, 900000811, "CUST_NM");
            // 저장 이름은 그대로, 비교 값은 앞뒤 공백 제거 + 대문자
            assertThat(db.jdbc().queryForObject("select COL_NM from " + db.s() + "TB_COL where COL_SN = 900000822", String.class)).isEqualTo(" Memo ");
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000822", String.class)).isEqualTo("MEMO");
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000821", String.class)).isEqualTo("CUST_NM");
            assertThat(db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = 900000823", String.class)).isEqualTo("CUST_NO");
            // 예제 : TB_COL 의 비교 값 컬럼과 UX_COL_01 제약조건 (예제 정합성은 V0SeedMigrationTest)
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_COL c join " + db.s() + "TB_TBL t on t.TBL_SN = c.TBL_SN where c.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and c.COL_NM = 'COL_NM_CMPR_VL'", Integer.class)).isOne();
            assertThat(db.jdbc().queryForObject("select count(*) from " + db.s() + "TB_TBL_CNSTRT k join " + db.s() + "TB_TBL t on t.TBL_SN = k.TBL_SN where k.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and k.CNSTRT_NM = 'UX_COL_01'", Integer.class)).isOne();
            // 제약조건이 실제로 있다 : 같은 테이블의 같은 비교 이름은 거절, 다른 테이블은 된다
            String schema = db.s().substring(1, db.s().length() - 2);
            assertThat(db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                    + " and upper(CONSTRAINT_NAME) = 'UX_COL_01' and CONSTRAINT_TYPE = 'UNIQUE'", Integer.class, schema)).isOne();
            assertThatThrownBy(() -> column(db, 900000830, 900000810, "cust_no ")).hasMessageContaining("ux_col_01");
            column(db, 900000831, 900000811, " Memo ");
            // FK 2개 + PK
            assertThat(db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                    + " and CONSTRAINT_TYPE in ('FOREIGN KEY', 'PRIMARY KEY')", Integer.class, schema)).isEqualTo(3);
            assertThat(db.latest().migrate().migrationsExecuted).as("다시 띄워도 실행할 것이 없다").isZero();
        } finally {
            db.latest().clean();
        }
    }
}
