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
 * V6 (DEC-31 내장 MCP 설정·토큰·호출 이력) 의 증분 업그레이드 : V5 까지 적용된 DB 에 프로젝트·계정·원본 VIEW 정의가 있는 상태에서 V6 만 적용되고(체크섬 검증 통과),
 * 기존 행이 그대로이며 설정 행이 없는 프로젝트는 MCP 가 꺼진 상태(행 없음)로 남는지, 프로젝트당 설정 한 행·토큰 해시 UNIQUE·프로젝트 FK 가 DB 제약으로 동작하는지,
 * 예제(프로젝트 0)의 세 테이블이 표준 용어 컬럼(ACTVTN_YN 포함)으로 들어오는지. 다시 migrate 해도 아무것도 실행되지 않는다. H2 와 PostgreSQL 모두.
 */
class V6EmbeddedMcpMigrationTest {

    @ParameterizedTest(name = "V5 → V6 증분 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradesFromV5KeepingData(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "v6_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v5 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("5").cleanDisabled(false).load();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("6").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            assertThat(v5.migrate().migrationsExecuted).isEqualTo(6);
            var jdbc = new JdbcTemplate(dataSource);
            // V5 시점의 사용자 데이터 : 계정 → 프로젝트 → 원본 연결·VIEW·정의
            jdbc.update("insert into " + s + "TB_ACNT(ACNT_SN, USER_ID, USER_NM, ACTVTN_YN, PSWD_CHG_YN) values (900000100, 'v6user', 'V6 사용자', true, true)");
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, MNGR_ACNT_SN, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000101, 'v6', 900000100, true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_SRC_CNTN(SRC_CNTN_SN, PRJ_SN, CNTN_NM, DB_TYPE_CD, HOST_NM, PORT_NO, DB_NM, DB_NM_TYPE_CD, CNTN_USER_NM, IDNTFR_CMPR_CD, ACTVTN_YN)"
                    + " values (900000001, 900000101, 'v6', 'POSTGRESQL', '127.0.0.1', 5432, 'src', 'DATABASE', 'u', 'DBMS_DEFAULT', true)");
            jdbc.update("insert into " + s + "TB_SRC_SCHM(SRC_SCHM_SN, PRJ_SN, SRC_CNTN_SN, SCHM_NM, PATH_CN, EXST_YN, CRT_CYCL) values (900000002, 900000101, 900000001, 'app', '6:app', true, 1)");
            jdbc.update("insert into " + s + "TB_SRC_TBL(SRC_TBL_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, TBL_NM, CMPR_NM, TBL_TYPE_CD, HASH_VL, EXST_YN, CRT_CYCL)"
                    + " values (900000003, 900000101, 900000001, 900000002, 'v_a', 'v_a', 'VIEW', 'v1:x', true, 1)");
            jdbc.update("insert into " + s + "TB_SRC_VIEW_DFN(SRC_VIEW_DFN_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_SN, DFN_STTS_CD, DFN_CN, ENCPT_YN, HASH_VL) values (900000010, 900000101, 900000001, 900000003, 'COLLECTED', 'select 1', false, 'd1:x')");
            int exampleTables = jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0", Integer.class);

            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            latest.validate();
            assertThat(latest.migrate().migrationsExecuted).as("다시 띄워도 실행할 것이 없다").isZero();
            assertThat(jdbc.queryForObject("select PRJ_NM || ':' || USER_ID from " + s + "TB_PRJ p join " + s + "TB_ACNT a on a.ACNT_SN = p.MNGR_ACNT_SN where p.PRJ_SN = 900000101", String.class))
                    .isEqualTo("v6:v6user");
            assertThat(jdbc.queryForObject("select DFN_CN || ':' || HASH_VL from " + s + "TB_SRC_VIEW_DFN where SRC_VIEW_DFN_SN = 900000010", String.class)).isEqualTo("select 1:d1:x");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_PRJ_MCP_STNG", Integer.class)).as("설정 행 없음 = MCP 꺼짐 (기존 프로젝트를 켜지 않는다)").isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_MCP_TKN", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0", Integer.class)).isEqualTo(exampleTables + 3);
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_PRJ_MCP_STNG' and c.STD_TRM_SN is null", String.class))
                    .as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_PRJ_MCP_STNG' and c.COL_NM = 'ACTVTN_YN'", Integer.class))
                    .isOne();

            jdbc.update("insert into " + s + "TB_PRJ_MCP_STNG(PRJ_MCP_STNG_SN, PRJ_SN, ACTVTN_YN) values (900000020, 900000101, true)");
            assertThatThrownBy(() -> jdbc.update("insert into " + s + "TB_PRJ_MCP_STNG(PRJ_MCP_STNG_SN, PRJ_SN, ACTVTN_YN) values (900000021, 900000101, false)"))
                    .as("프로젝트당 설정 한 행").isInstanceOf(DataIntegrityViolationException.class);
            String token = "insert into " + s + "TB_MCP_TKN(MCP_TKN_SN, PRJ_SN, ACNT_SN, TKN_NM, HASH_VL, TKN_PFX_NM, EXPRY_DT) values (?, ?, 900000100, 't', ?, 'lxm_abcd', current_timestamp)";
            jdbc.update(token, 900000030L, 900000101L, "h:1");
            assertThatThrownBy(() -> jdbc.update(token, 900000031L, 900000101L, "h:1")).as("토큰 해시 UNIQUE").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(token, 900000032L, 999999999L, "h:2")).as("없는 프로젝트").isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            latest.clean();
        }
    }
}
