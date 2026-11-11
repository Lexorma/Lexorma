package com.koboolean.metamora;

import com.koboolean.metamora.common.i18n.Messages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.TreeMap;
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
            // V0 (스키마 + 초기 데이터) + V1 (META-MORA 논리 모델 예제) + V2 (논리·물리 매핑 테이블, 데이터 없음) + V3 (매핑 테이블 예제, COMMENT)
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
            var jdbc = new JdbcTemplate(dataSource);
            // V1 : 물리 모델 예제와 같은 수의 엔터티·속성·관계, PK 가 있는 테이블마다 주 식별자, 고아 행 없음
            String s = "\"" + schema + "\".";
            // 실제 스키마의 TB_ 테이블·컬럼은 모두 META-MORA 물리 모델 예제에 있고 (논리 예제는 아래에서 물리 예제와 수를 맞춘다),
            // 테이블 COMMENT = 예제의 한글명, 컬럼 COMMENT = 예제의 컬럼 설명이다.
            // 테이블·컬럼을 추가하면 같은 마이그레이션에 COMMENT 와 예제(물리·논리)를 넣어야 이 검사를 통과한다.
            boolean h2 = vendor.equals("h2");
            Map<String, String> tableComments = comments(jdbc, h2
                    ? "select TABLE_NAME, REMARKS from INFORMATION_SCHEMA.TABLES where TABLE_SCHEMA = ? and TABLE_NAME like 'TB\\_%' escape '\\'"
                    : "select upper(c.relname), obj_description(c.oid) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = ? and c.relkind = 'r' and c.relname like 'tb\\_%'", schema);
            Map<String, String> columnComments = comments(jdbc, h2
                    ? "select TABLE_NAME || '.' || COLUMN_NAME, REMARKS from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and TABLE_NAME like 'TB\\_%' escape '\\'"
                    : "select upper(c.relname || '.' || a.attname), col_description(c.oid, a.attnum) from pg_attribute a join pg_class c on c.oid = a.attrelid "
                    + "join pg_namespace n on n.oid = c.relnamespace where n.nspname = ? and c.relkind = 'r' and c.relname like 'tb\\_%' and a.attnum > 0 and not a.attisdropped", schema);
            assertThat(tableComments).hasSizeGreaterThan(30)
                    .isEqualTo(comments(jdbc, "select TBL_NM, TBL_KORN_NM from " + s + "TB_TBL where PRJ_SN = 0"));
            assertThat(columnComments)
                    .isEqualTo(comments(jdbc, "select TBL_NM || '.' || COL_NM, COL_EXPLN from " + s + "TB_COL where PRJ_SN = 0"));

            // V2 : 매핑은 명시적으로 등록한 것만 있다 (예제의 논리·물리 모델을 자동으로 잇지 않는다)
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_ENTT_TBL_MPNG", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_ATRB_COL_MPNG", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ENTT", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0", Integer.class)).isPositive();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ATRB", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_COL where PRJ_SN = 0", Integer.class));
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_REL where PRJ_SN = 0", Integer.class));
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_IDNTFR_ATRB", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_COL where PRJ_SN = 0 and PK_YN = true", Integer.class));
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ATRB where STD_TRM_SN is null", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_COL where PRJ_SN = 0 and STD_TRM_SN is null", Integer.class));
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
            assertThat(jdbc.queryForObject("select count(*) from " + domains, Integer.class)).isEqualTo(29);
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

            // 재시작과 동일하게 별도 Flyway 인스턴스에서도 V0~V3 은 재실행되지 않는다.
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

    /** 이름 → 설명 (설명이 없으면 빈 문자열 : 빠진 COMMENT 가 비교에서 드러난다) */
    private static Map<String, String> comments(JdbcTemplate jdbc, String sql, Object... args) {
        Map<String, String> result = new TreeMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2));
        }, args);
        return result;
    }
}
