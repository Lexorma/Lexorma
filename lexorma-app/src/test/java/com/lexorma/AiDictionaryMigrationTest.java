package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AI-5 스키마 신규 설치 (H2 / PostgreSQL) : 업무 설명(TASK_EXPLN)은 비어 있고 원본 COMMENT 칸과 따로 있다.
 * 제안·이력·실행 테이블은 비어 있다 (자동으로 아무것도 만들지 않는다). 예제 물리 모델에 새 테이블과 업무 설명 컬럼이 있다.
 * (예전 V9 → V10 업그레이드 검증은 2026-11-15 Flyway 초기화로 V0·V1 만 남아 없어졌다)
 */
class AiDictionaryMigrationTest {

    @ParameterizedTest(name = "신규 설치 {0} : 업무 설명은 비어 있고 AI 제안 테이블도 비어 있다")
    @ValueSource(strings = {"h2", "postgresql"})
    void freshInstall(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "ai_dict_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false).load();
        try {
            flyway.migrate();
            JdbcTemplate db = new JdbcTemplate(dataSource);
            String s = "\"" + schema + "\".";
            assertThat(db.queryForObject("select count(*) from " + s + "TB_TBL where TASK_EXPLN is not null", Long.class)).isZero();
            assertThat(db.queryForObject("select count(*) from " + s + "TB_COL where TASK_EXPLN is not null", Long.class)).isZero();
            for (String t : new String[]{"TB_AI_EXPLN_SGSTN", "TB_AI_EXPLN_SGSTN_HSTRY", "TB_AI_EXPLN_CRT_EXCN", "TB_AI_EXPLN_EVL_TRGT", "TB_AI_EXPLN_EVL"}) {
                assertThat(db.queryForObject("select count(*) from " + s + t, Long.class)).as(t).isZero();
            }
            // 업무 설명 칸은 원본 COMMENT·DDL 설명과 다른 칸이다 (같은 행에 따로 쓸 수 있다)
            Long table = db.queryForObject("select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_TBL'", Long.class);
            db.update("update " + s + "TB_TBL set TASK_EXPLN = '업무 설명', SRC_ANTN_CN = 'source comment' where TBL_SN = ?", table);
            assertThat(db.queryForObject("select TBL_EXPLN from " + s + "TB_TBL where TBL_SN = ?", String.class, table)).isNotEqualTo("업무 설명");
            assertThat(db.queryForObject("select SRC_ANTN_CN from " + s + "TB_TBL where TBL_SN = ?", String.class, table)).isEqualTo("source comment");
            // 예제 : 새 테이블과 업무 설명 컬럼이 예제 물리 모델에 들어갔다
            assertThat(db.queryForObject("select count(*) from " + s + "TB_COL where PRJ_SN = 0 and COL_NM = 'TASK_EXPLN'", Long.class)).isEqualTo(2);
            // 제안·이력·실행 3개 + 품질 평가 대상·평가 2개 (V2)
            assertThat(db.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM like 'TB_AI_EXPLN_%'", Long.class)).isEqualTo(5);
        } finally {
            flyway.clean();
        }
    }
}
