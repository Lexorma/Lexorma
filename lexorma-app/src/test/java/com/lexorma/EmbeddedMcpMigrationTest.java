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
 * DEC-31 내장 MCP 설정·토큰·호출 이력 스키마 신규 설치 : 설정 행이 없어 모든 프로젝트의 MCP 가 꺼져 있고, 토큰도 없으며,
 * 프로젝트당 설정 한 행·토큰 해시 UNIQUE·프로젝트 FK 가 DB 제약으로 동작하는지, 예제(프로젝트 0)의 세 테이블이 표준 용어 컬럼(ACTVTN_YN 포함)으로 들어 있는지.
 * 다시 migrate 해도 아무것도 실행되지 않는다. H2 와 PostgreSQL 모두. (예전 V5 → V6 업그레이드 검증은 2026-11-18 세 번째 합침으로 없어졌다)
 */
class EmbeddedMcpMigrationTest {

    @ParameterizedTest(name = "신규 설치 {0} : MCP 설정·토큰 제약")
    @ValueSource(strings = {"h2", "postgresql"})
    void freshInstall(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "mcp_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            flyway.migrate();
            assertThat(flyway.migrate().migrationsExecuted).as("다시 띄워도 실행할 것이 없다").isZero();
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_PRJ_MCP_STNG", Integer.class)).as("설정 행 없음 = MCP 꺼짐").isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_MCP_TKN", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_PRJ_MCP_STNG', 'TB_MCP_TKN', 'TB_MCP_CALL_HSTRY')", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_PRJ_MCP_STNG' and c.STD_TRM_SN is null", String.class))
                    .as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_PRJ_MCP_STNG' and c.COL_NM = 'ACTVTN_YN'", Integer.class))
                    .isOne();

            // 사용자 데이터 : 계정 → 프로젝트
            jdbc.update("insert into " + s + "TB_ACNT(ACNT_SN, USER_ID, USER_NM, ACTVTN_YN, PSWD_CHG_YN) values (900000100, 'mcpuser', 'MCP 사용자', true, true)");
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, MNGR_ACNT_SN, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000101, 'mcp', 900000100, true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_PRJ_MCP_STNG(PRJ_MCP_STNG_SN, PRJ_SN, ACTVTN_YN) values (900000020, 900000101, true)");
            assertThatThrownBy(() -> jdbc.update("insert into " + s + "TB_PRJ_MCP_STNG(PRJ_MCP_STNG_SN, PRJ_SN, ACTVTN_YN) values (900000021, 900000101, false)"))
                    .as("프로젝트당 설정 한 행").isInstanceOf(DataIntegrityViolationException.class);
            String token = "insert into " + s + "TB_MCP_TKN(MCP_TKN_SN, PRJ_SN, ACNT_SN, TKN_NM, HASH_VL, TKN_PFX_NM, EXPRY_DT) values (?, ?, 900000100, 't', ?, 'lxm_abcd', current_timestamp)";
            jdbc.update(token, 900000030L, 900000101L, "h:1");
            assertThatThrownBy(() -> jdbc.update(token, 900000031L, 900000101L, "h:1")).as("토큰 해시 UNIQUE").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(token, 900000032L, 999999999L, "h:2")).as("없는 프로젝트").isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            flyway.clean();
        }
    }
}
