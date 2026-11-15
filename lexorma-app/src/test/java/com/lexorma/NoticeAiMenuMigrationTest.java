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
 * V3 (안내 공지의 AI 절) 업그레이드 (H2 / PostgreSQL) : V2 까지 쓰던 DB 의 공지에서 "11. AI 기능" 절만 AI 관리 메뉴 문구로 바뀌고 나머지 본문은 그대로다.
 * 그 절이 예전 문구와 다르면(고친 공지) 바꾸지 않는다.
 */
class NoticeAiMenuMigrationTest {

    @ParameterizedTest(name = "업그레이드 {0} : V2 → V3 공지의 AI 절만 바뀐다, 고친 공지는 그대로")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgrade(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "notice_ai_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v2 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("2").cleanDisabled(false).load();
        try {
            v2.migrate();
            JdbcTemplate db = new JdbcTemplate(dataSource);
            String posts = "\"" + schema + "\".TB_PST";
            String before = db.queryForObject("select PST_CN from " + posts + " where PST_SN = 1", String.class);
            assertThat(before).contains("데이터 사전에서 테이블·컬럼의 업무 설명 초안").doesNotContain("AI 품질 평가");
            // 다른 게시물 하나 : 같은 문구가 있어도 공지 1번만 바꾼다
            db.update("insert into " + posts + "(PST_SN, PRJ_SN, CTGRY_CD, WRTR_ID, PST_TTL, PST_CN, DEL_YN) values (2, 0, 'NOTICE', 'admin', '다른 글', ?, 'N')", before);

            Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("3").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            String after = db.queryForObject("select PST_CN from " + posts + " where PST_SN = 1", String.class);
            assertThat(after).contains("AI 관리 › AI Provider 설정", "AI 관리 › AI 설명 제안", "**AI 품질 평가**").doesNotContain("데이터 사전에서 테이블·컬럼의 업무 설명 초안");
            // AI 절 앞뒤는 그대로
            assertThat(after.substring(0, after.indexOf("### 11."))).isEqualTo(before.substring(0, before.indexOf("### 11.")));
            assertThat(after.substring(after.indexOf("## 이용 안내"))).isEqualTo(before.substring(before.indexOf("## 이용 안내")));
            assertThat(db.queryForObject("select PST_CN from " + posts + " where PST_SN = 2", String.class)).isEqualTo(before);
        } finally {
            v2.clean();
        }

        // 공지를 고친 DB (AI 절이 예전 문구와 다르다) : 바꾸지 않는다
        String schema2 = schema + "b";
        Flyway edited = Flyway.configure().dataSource(new DriverManagerDataSource(vendor.equals("h2") ? "jdbc:h2:mem:" + schema2 + ";DB_CLOSE_DELAY=-1" : postgresUrl, "lexorma", "lexorma"))
                .schemas(schema2).locations("classpath:db/migration/" + vendor).target("2").cleanDisabled(false).load();
        try {
            edited.migrate();
            JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(vendor.equals("h2") ? "jdbc:h2:mem:" + schema2 + ";DB_CLOSE_DELAY=-1" : postgresUrl, "lexorma", "lexorma"));
            String posts = "\"" + schema2 + "\".TB_PST";
            db.update("update " + posts + " set PST_CN = '관리자가 고친 공지' where PST_SN = 1");
            Flyway.configure().dataSource(new DriverManagerDataSource(vendor.equals("h2") ? "jdbc:h2:mem:" + schema2 + ";DB_CLOSE_DELAY=-1" : postgresUrl, "lexorma", "lexorma"))
                    .schemas(schema2).locations("classpath:db/migration/" + vendor).load().migrate();
            assertThat(db.queryForObject("select PST_CN from " + posts + " where PST_SN = 1", String.class)).isEqualTo("관리자가 고친 공지");
        } finally {
            edited.clean();
        }
    }
}
