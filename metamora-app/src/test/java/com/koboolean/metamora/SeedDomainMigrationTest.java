package com.koboolean.metamora;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SeedDomainMigrationTest {

    @ParameterizedTest(name = "V0 자체 도메인 초기화 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void initializesDomainsAndTermAssignments(String vendor) {
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
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            flyway.clean();
        }
    }
}
