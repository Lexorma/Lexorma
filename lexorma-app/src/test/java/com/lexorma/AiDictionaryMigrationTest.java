package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-5 스키마 신규 설치 : 업무 설명(TASK_EXPLN)은 비어 있고 원본 COMMENT 칸과 따로 있다.
 * 제안·이력·실행 테이블은 비어 있다 (자동으로 아무것도 만들지 않는다). 예제 물리 모델에 새 테이블과 업무 설명 컬럼이 있다.
 * (예전 V9 → V10, V3 → V4 업그레이드 검증은 2026-11-15 Flyway 초기화·두 번째 합침으로 V0·V1 만 남아 없어졌다)
 */
class AiDictionaryMigrationTest {

    @Test
    @DisplayName("신규 설치 PostgreSQL : 예제 테이블·컬럼 모두 업무 설명이 있고 AI 제안 테이블은 비어 있다")
    void freshInstall() {
        String schema = "ai_dict_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        try {
            flyway.migrate();
            JdbcTemplate db = new JdbcTemplate(dataSource);
            String s = "\"" + schema + "\".";
            // 예제(프로젝트 0)의 모든 테이블·컬럼에 업무 설명이 있다 (예전 V4 를 V1 에 합침). 다른 프로젝트 행은 없다
            assertThat(db.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TASK_EXPLN is null", Long.class)).isZero();
            assertThat(db.queryForObject("select count(*) from " + s + "TB_COL where PRJ_SN = 0 and TASK_EXPLN is null", Long.class)).isZero();
            assertThat(db.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN <> 0", Long.class)).isZero();
            // 공통 컬럼 설명보다 테이블별 설명이 앞선다 (TB_PRJ.PRJ_SN 은 기본키)
            assertThat(db.queryForObject("select TASK_EXPLN from " + s + "TB_COL where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'PRJ_SN'", String.class)).startsWith("프로젝트를 구분하는");
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
            // 제안·이력·실행 3개 + 품질 평가 대상·평가 2개 + 판정 변경 이력 1개(V2, DEC-27)
            assertThat(db.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM like 'TB_AI_EXPLN_%'", Long.class)).isEqualTo(6);
        } finally {
            flyway.clean();
        }
    }
}
