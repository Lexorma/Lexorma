package com.lexorma;

import com.lexorma.common.i18n.Messages;
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
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "seed_domains_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "metamora", "metamora");
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration/" + vendor).cleanDisabled(false).load();
        try {
            // V0 (스키마·COMMENT) + V1 (초기 데이터 : 기본 데이터, 예제 데이터 사전·물리 모델·논리 모델·매핑)
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            var jdbc = new JdbcTemplate(dataSource);
            // 초기 데이터에 예전 프로젝트 이름이 없다
            assertThat(oldNames(jdbc, "\"" + schema + "\".")).isZero();
            assertThat(jdbc.queryForObject("select PRJ_NM from \"" + schema + "\".TB_PRJ where PRJ_SN = 0", String.class)).isEqualTo("LEXORMA");
            // V1 : 물리 모델 예제와 같은 수의 엔터티·속성·관계, PK 가 있는 테이블마다 주 식별자, 고아 행 없음
            String s = "\"" + schema + "\".";
            // 실제 스키마의 TB_ 테이블·컬럼은 모두 LEXORMA 물리 모델 예제에 있고 (논리 예제는 아래에서 물리 예제와 수를 맞춘다),
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

            // 예제의 엔터티는 모두 한글명이 같은 테이블에, 속성은 모두 같은 표준 용어의 컬럼에 매핑되어 있다 (1:1, 빠진 것 없음)
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_ENTT_TBL_MPNG", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ENTT", Integer.class)).isPositive();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_ATRB_COL_MPNG", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ATRB", Integer.class)).isPositive();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_ENTT_TBL_MPNG mp join " + s + "TB_LGCL_ENTT e on e.LGCL_ENTT_SN = mp.LGCL_ENTT_SN join "
                    + s + "TB_TBL t on t.TBL_SN = mp.TBL_SN where mp.PRJ_SN <> 0 or t.PRJ_SN <> 0 or e.PRJ_SN <> 0 or t.TBL_KORN_NM <> e.ENTT_NM", Integer.class)).isZero();
            // 속성 매핑은 부모 매핑의 엔터티·테이블 안에서만, 같은 표준 용어끼리 이어진다
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_ATRB_COL_MPNG am join " + s + "TB_ENTT_TBL_MPNG mp on mp.ENTT_TBL_MPNG_SN = am.ENTT_TBL_MPNG_SN join "
                    + s + "TB_LGCL_ATRB a on a.LGCL_ATRB_SN = am.LGCL_ATRB_SN join " + s + "TB_COL c on c.COL_SN = am.COL_SN "
                    + "where a.LGCL_ENTT_SN <> mp.LGCL_ENTT_SN or c.TBL_SN <> mp.TBL_SN or a.STD_TRM_SN <> c.STD_TRM_SN or a.ATRB_NM <> c.COL_EXPLN", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA'", Integer.class)).isEqualTo(1);
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
                    .isEqualTo("[안내] Lexorma에 오신 것을 환영합니다.").matches(Messages::hasEnglish);
            assertThat(jdbc.queryForObject("select PST_CN from " + posts + " where PST_SN = 1", String.class))
                    .contains("데이터 사전", "테이블 및 컬럼", "물리 ERD", "프로젝트", "사용자 및 권한",
                            "이용 안내", "문의 및 오류 신고", "https://github.com/Lexorma/Lexorma/issues", "MIT 라이선스")
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

            // 재시작과 동일하게 별도 Flyway 인스턴스에서도 V0·V1 은 재실행되지 않는다.
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

    /** 초기 데이터가 들어가는 컬럼에 예전 프로젝트 이름이 남은 행 수 */
    private static int oldNames(JdbcTemplate jdbc, String s) {
        int count = 0;
        for (String[] target : new String[][]{{"TB_PRJ", "PRJ_NM"}, {"TB_LGCL_MDL", "MDL_NM"}, {"TB_LGCL_MDL", "MDL_EXPLN"}, {"TB_TBL", "TBL_EXPLN"},
                {"TB_LGCL_ENTT", "ENTT_EXPLN"}, {"TB_STD_DMN", "DMN_EXPLN"}, {"TB_PST", "PST_TTL"}, {"TB_PST", "PST_CN"}}) {
            // MetaMora / Metamora / META-MORA
            count += jdbc.queryForObject("select count(*) from " + s + target[0] + " where lower(" + target[1] + ") like '%meta' || 'mora%' or lower(" + target[1] + ") like '%meta-' || 'mora%'", Integer.class);
        }
        return count;
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
