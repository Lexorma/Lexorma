package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V4 (DEC-06 출처 컬럼 매핑·매핑 이력) 의 증분 업그레이드 : V3 까지 적용된 DB 에 기존 TABLE 매핑이 있는 상태에서 V4 만 적용되고(체크섬 검증 통과), 기존 매핑 행이 그대로이며,
 * 컬럼 매핑의 1:1 제약(같은 TABLE 매핑 안의 같은 원본 컬럼·같은 모델 컬럼)·TABLE 매핑 해제 시 연쇄 삭제·재연결이 DB 제약으로 동작하는지. H2 와 PostgreSQL 모두.
 */
class V4SourceRenameMigrationTest {

    @ParameterizedTest(name = "V3 → V4 증분 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradesFromV3KeepingTableMappings(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "v4_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v3 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("3").cleanDisabled(false).load();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("4").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            assertThat(v3.migrate().migrationsExecuted).isEqualTo(4);
            var jdbc = new JdbcTemplate(dataSource);
            Long table = jdbc.queryForObject("select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_TBL'", Long.class);
            Long column = jdbc.queryForObject("select COL_SN from " + s + "TB_COL where TBL_SN = ? and COL_NM = 'TBL_NM'", Long.class, table);
            Long other = jdbc.queryForObject("select COL_SN from " + s + "TB_COL where TBL_SN = ? and COL_NM = 'TBL_KORN_NM'", Long.class, table);
            // V3 시점의 기존 TABLE 매핑 (연결 900000001 의 원본 TABLE 900000002 → 예제 TB_TBL)
            jdbc.update("insert into " + s + "TB_SRC_TBL_MPNG(SRC_TBL_MPNG_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, SRC_TBL_SN, PATH_CN, CMPR_NM, CRT_CYCL, RFLT_ORD, TBL_SN, MPNG_EXCL_YN)"
                    + " values (900000000, 0, 900000001, 900000003, 900000002, '6:public', 'tb_tbl', 1, 7, ?, false)", table);

            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            latest.validate();
            assertThat(jdbc.queryForObject("select CMPR_NM || ':' || RFLT_ORD || ':' || TBL_SN from " + s + "TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = 900000000", String.class))
                    .isEqualTo("tb_tbl:7:" + table);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_COL_MPNG", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_SRC_COL_MPNG', 'TB_SRC_MPNG_HSTRY')", Integer.class)).isEqualTo(2);

            String insert = "insert into " + s + "TB_SRC_COL_MPNG(SRC_COL_MPNG_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_MPNG_SN, CMPR_NM, COL_NM, COL_SN, RFLT_ORD) values (?, 0, 900000001, 900000000, ?, ?, ?, 7)";
            jdbc.update(insert, 900000010L, "table_name", "table_name", column);
            // 같은 TABLE 매핑 안에서 같은 원본 컬럼, 같은 모델 컬럼은 하나만
            assertThatThrownBy(() -> jdbc.update(insert, 900000011L, "table_name", "table_name", other)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(insert, 900000012L, "kor_name", "kor_name", column)).isInstanceOf(DataIntegrityViolationException.class);
            // TABLE 매핑 해제 → 컬럼 매핑도 지워진다 (FK ON DELETE CASCADE), 다시 매핑하면 다시 이을 수 있다
            jdbc.update("delete from " + s + "TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = 900000000");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_COL_MPNG", Integer.class)).isZero();
            jdbc.update("insert into " + s + "TB_SRC_TBL_MPNG(SRC_TBL_MPNG_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, SRC_TBL_SN, PATH_CN, CMPR_NM, CRT_CYCL, RFLT_ORD, TBL_SN, MPNG_EXCL_YN)"
                    + " values (900000000, 0, 900000001, 900000003, 900000002, '6:public', 'tb_tbl', 1, 8, ?, false)", table);
            jdbc.update(insert, 900000013L, "table_name", "table_name", column);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_COL_MPNG", Integer.class)).isOne();
            // 이력은 FK 가 없어 해제 뒤에도 남는다 (append-only 는 서비스 규칙)
            jdbc.update("insert into " + s + "TB_SRC_MPNG_HSTRY(SRC_MPNG_HSTRY_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_MPNG_SN, TRGT_TYPE_CD, CHG_TYPE_CD, CHG_DT) values (900000020, 0, 900000001, 123, 'TABLE', 'RELEASE', current_timestamp)");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_MPNG_HSTRY", Integer.class)).isOne();
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            latest.clean();
        }
    }
}
