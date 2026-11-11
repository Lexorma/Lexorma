package com.koboolean.metamora;

import com.koboolean.metamora.common.i18n.Messages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V0SeedMigrationTest {

    @ParameterizedTest(name = "V0 초기 데이터 검증 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void initializesDictionaryAndWelcomeNotice(String vendor) {
        String postgresUrl = System.getenv("METAMORA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "seed_domains_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "metamora", "metamora");
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration/" + vendor).cleanDisabled(false).load();
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            var jdbc = new JdbcTemplate(dataSource);
            String terms = "\"" + schema + "\".TB_STD_TRM";
            String domains = "\"" + schema + "\".TB_STD_DMN";
            assertThat(jdbc.queryForList("select t.TRM_NM || ':' || d.DMN_NM from " + terms + " t join " + domains
                    + " d on d.STD_DMN_SN = t.STD_DMN_SN where t.PRJ_SN = 0"
                    + " and t.TRM_NM in ('기본_값', '비밀번호', '프로젝트_일련번호_목록')", String.class))
                    .containsExactlyInAnyOrder("기본_값:값V255", "비밀번호:비밀번호V255", "프로젝트_일련번호_목록:목록A");
            assertThat(jdbc.queryForObject("select count(*) from " + terms
                    + " where PRJ_SN = 0 and STD_DMN_SN is null", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select DATA_LEN from " + domains + " where DMN_NM = '값V255'", Integer.class)).isEqualTo(255);
            assertThat(jdbc.queryForObject("select DATA_LEN from " + domains + " where DMN_NM = '비밀번호V255'", Integer.class)).isEqualTo(255);
            assertThat(jdbc.queryForObject("select DATA_TYPE_NM from " + domains + " where DMN_NM = '목록A'", String.class)).isEqualTo("BIGINT ARRAY");
            assertThat(jdbc.queryForObject("select count(*) from " + domains, Integer.class)).isEqualTo(22);
            String posts = "\"" + schema + "\".TB_PST";
            String readers = "\"" + schema + "\".TB_PST_ACNT";
            String accounts = "\"" + schema + "\".TB_ACNT";
            assertThat(jdbc.queryForObject("select count(*) from " + posts, Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select PST_TTL from " + posts + " where PST_SN = 1", String.class))
                    .isEqualTo("[안내] Metamora에 오신 것을 환영합니다.").matches(Messages::hasEnglish);
            assertThat(jdbc.queryForObject("select PST_CN from " + posts + " where PST_SN = 1", String.class))
                    .contains("데이터 사전", "테이블 및 컬럼", "물리 ERD", "프로젝트", "사용자 및 권한",
                            "이용 안내", "문의 및 오류 신고", "https://github.com/meta-mora/metamora/issues", "MIT 라이선스")
                    .contains("## 주요 기능", "### 1. 데이터 사전", "| 용도 | 링크 |")
                    // 영어판(EnglishMessages)의 키와 한 글자도 다르지 않아야 영어 화면에 영어로 나온다.
                    .matches(Messages::hasEnglish)
                    .doesNotContain("<a", "mailto:");
            assertThat(jdbc.queryForObject("select count(*) from " + posts
                    + " where PRJ_SN = 0 and CTGRY_CD = 'NOTICE' and DEL_YN = 'N' and cardinality(PRJ_SN_LIST) = 1"
                    + " and PST_MDFCN_DT is not null and REG_DT is not null and MDFCN_DT is not null", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select a.USER_ID from " + posts + " p join " + accounts
                    + " a on a.USER_ID = p.WRTR_ID where p.PST_SN = 1", String.class)).isEqualTo("admin");
            assertThat(jdbc.queryForObject("select a.USER_ID from " + readers + " r join " + accounts
                    + " a on a.ACNT_SN = r.ACNT_SN where r.PST_SN = 1", String.class)).isEqualTo("admin");
            String nextPostId = vendor.equals("h2") ? "next value for \"" + schema + "\".SQ_PST"
                    : "nextval('" + schema + ".SQ_PST')";
            assertThat(jdbc.queryForObject("select " + nextPostId, Long.class)).isEqualTo(51L);

            // 재시작과 동일하게 별도 Flyway 인스턴스에서도 V0는 재실행되지 않는다.
            Flyway restarted = Flyway.configure().dataSource(dataSource).schemas(schema)
                    .locations("classpath:db/migration/" + vendor).load();
            assertThat(restarted.migrate().migrationsExecuted).isZero();
            assertThat(restarted.validateWithResult().validationSuccessful).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from " + posts, Integer.class)).isEqualTo(1);
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            flyway.clean();
        }
    }
}
