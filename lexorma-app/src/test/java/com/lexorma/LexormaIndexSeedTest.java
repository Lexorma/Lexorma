package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LEXORMA 예제 인덱스 (V1 의 9)·10)) : 개수가 아니라 인덱스별 정의를 비교한다 (docs/index-management-m14.md 13).
 * 예전 V3~V6 은 2026-11-13 에 V0·V1 로 합쳤다 (예전 설치의 V4 → V5 업그레이드 경로는 더 없다).
 * 정의 = 인덱스명 → "테이블|UNIQUE 여부|컬럼,컬럼(순서대로)". 정규화는 식별자 대문자뿐이다.
 * PostgreSQL 은 LEXORMA_TEST_POSTGRES_URL 이 있을 때만 (UUID 스키마를 만들고 지운다).
 */
class LexormaIndexSeedTest {

    private static final Pattern CREATE_INDEX = Pattern.compile("(?im)^create (unique )?index (\\w+) on (TB_\\w+) \\(([^)]*)\\);");

    @ParameterizedTest(name = "신규 설치 {0} : 예제 인덱스 = 마이그레이션의 create index = 실제 스키마의 인덱스")
    @ValueSource(strings = {"h2", "postgresql"})
    void seededIndexesMatchRealSchema(String vendor) {
        var dataSource = dataSource(vendor);
        String schema = "idx_seed_" + UUID.randomUUID().toString().replace("-", "");
        String s = "\"" + schema + "\".";
        Flyway flyway = flyway(dataSource, schema, vendor, null);
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
            var jdbc = new JdbcTemplate(dataSource);
            Map<String, String> declared = declaredIndexes(vendor);
            Map<String, String> model = modelIndexes(jdbc, s);

            // 누락·추가·불일치 없이 같다 (102·145 는 지금의 기준값 : 정의 비교가 먼저다. VIEW 테이블 인덱스 7개·원본 매핑(V2) 10개·가져오기 실행(V3) 2개 포함,
            // TABLE·VIEW 이름은 스키마 단위라 UX_TBL_01·UX_VIEW_01 = 스키마 + 이름이고 IX_TBL_01·IX_VIEW_01 은 없다)
            assertThat(model).isEqualTo(declared).hasSize(102);
            assertThat(model).isEqualTo(realIndexes(jdbc, vendor, schema));
            assertThat(count(jdbc, "select count(*) from " + s + "TB_TBL_IDX_COL")).isEqualTo(145);
            // 중복 없음, 모두 프로젝트 LEXORMA 의 기본 스키마 테이블, 구성 컬럼은 그 테이블의 컬럼
            assertThat(count(jdbc, "select count(distinct IDX_NM) from " + s + "TB_TBL_IDX")).isEqualTo(102);
            assertThat(count(jdbc, "select count(*) from " + s + "TB_TBL_IDX i join " + s + "TB_TBL t on t.TBL_SN = i.TBL_SN join " + s + "TB_SCHM h on h.SCHM_SN = t.SCHM_SN "
                    + "where i.PRJ_SN = 0 and t.PRJ_SN = 0 and h.PRJ_SN = 0")).isEqualTo(102);
            // 시스템 초기 등록 : 직접 정의(USER)·승인·등록자 SYSTEM, 유형·고급 속성 없음(BTREE 기본), 변경 요청·변경 이력은 만들지 않는다
            assertThat(count(jdbc, "select count(*) from " + s + "TB_TBL_IDX where IDX_SRC_CD = 'USER' and APRV_YN = true and RGTR_ID = 'SYSTEM' and MDFR_ID = 'SYSTEM' "
                    + "and IDX_TYPE_NM is null and CND_CN is null and DRVN_COL_SN is null")).isEqualTo(102);
            assertThat(count(jdbc, "select count(*) from " + s + "TB_TBL_IDX_COL where EXPR_CN is not null or SORT_CD is not null or COL_SN is null")).isZero();
            assertThat(count(jdbc, "select count(*) from " + s + "TB_IDX_CHG_DMND")).isZero();
            assertThat(count(jdbc, "select count(*) from " + s + "TB_MDL_CHG_HSTRY where TRGT_TYPE_CD like '%INDEX%'")).isZero();

            // 재시작 : 다시 실행하지 않고 데이터도 그대로
            Flyway restarted = flyway(dataSource, schema, vendor, null);
            assertThat(restarted.migrate().migrationsExecuted).isZero();
            assertThat(restarted.validateWithResult().validationSuccessful).isTrue();
            assertThat(modelIndexes(jdbc, s)).isEqualTo(model);
        } finally {
            flyway.clean();
        }
    }

    /** 마이그레이션 파일의 create [unique] index … on TB_… (V0. 예제 인덱스는 V1 이 같은 이름·컬럼으로 넣는다) */
    static Map<String, String> declaredIndexes(String vendor) {
        Map<String, String> result = new TreeMap<>();
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/" + vendor + "/V*.sql")) {
                Matcher m = CREATE_INDEX.matcher(file.getContentAsString(StandardCharsets.UTF_8));
                while (m.find()) {
                    assertThat(result.put(m.group(2).toUpperCase(Locale.ROOT), definition(m.group(3), m.group(1) != null, m.group(4).replace(" ", ""))))
                            .as("인덱스 이름 중복 %s", m.group(2)).isNull();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private static String definition(String table, boolean unique, String columns) {
        return (table + "|" + (unique ? "UNIQUE" : "INDEX") + "|" + columns).toUpperCase(Locale.ROOT);
    }

    /** 프로젝트 LEXORMA 의 모델 인덱스. 구성 컬럼이 인덱스의 테이블 컬럼이 아니면 빠져서 비교에서 드러난다 */
    private static Map<String, String> modelIndexes(JdbcTemplate jdbc, String s) {
        return definitions(jdbc, "select i.IDX_NM, t.TBL_NM, i.UNQ_YN, c.COL_NM from " + s + "TB_TBL_IDX i join " + s + "TB_TBL t on t.TBL_SN = i.TBL_SN "
                + "left join " + s + "TB_TBL_IDX_COL x on x.IDX_SN = i.IDX_SN left join " + s + "TB_COL c on c.COL_SN = x.COL_SN and c.TBL_SN = i.TBL_SN "
                + "where i.PRJ_SN = 0 order by i.IDX_SN, x.COL_ORD");
    }

    /** 실제 스키마의 TB_ 테이블 인덱스 : PK 와 UNIQUE 제약이 만든 인덱스(H2 는 FK 가 만든 것도)는 뺀다 */
    private static Map<String, String> realIndexes(JdbcTemplate jdbc, String vendor, String schema) {
        if (vendor.equals("h2")) {
            return definitions(jdbc, "select i.INDEX_NAME, i.TABLE_NAME, i.INDEX_TYPE_NAME = 'UNIQUE INDEX', c.COLUMN_NAME from INFORMATION_SCHEMA.INDEXES i "
                    + "join INFORMATION_SCHEMA.INDEX_COLUMNS c on c.INDEX_SCHEMA = i.INDEX_SCHEMA and c.INDEX_NAME = i.INDEX_NAME "
                    + "where i.TABLE_SCHEMA = ? and i.TABLE_NAME like 'TB\\_%' escape '\\' and i.INDEX_TYPE_NAME <> 'PRIMARY KEY' and i.IS_GENERATED = false "
                    + "order by i.INDEX_NAME, c.ORDINAL_POSITION", schema);
        }
        String from = "from pg_index i join pg_class ic on ic.oid = i.indexrelid join pg_class t on t.oid = i.indrelid join pg_namespace n on n.oid = t.relnamespace "
                + "where n.nspname = ? and t.relname like 'tb\\_%' and not i.indisprimary and not exists (select 1 from pg_constraint c where c.conindid = i.indexrelid)";
        // 모델은 유형·조건·표현식을 비워 두었다 : 실제 인덱스도 모두 BTREE 컬럼 인덱스여야 한다
        assertThat(count(jdbc, "select count(*) " + from + " and (i.indpred is not null or i.indexprs is not null "
                + "or (select amname from pg_am where oid = ic.relam) <> 'btree' or i.indnkeyatts <> i.indnatts)", schema)).isZero();
        return definitions(jdbc, "select ic.relname, t.relname, i.indisunique, a.attname " + from.replaceFirst("where", "cross join lateral unnest(i.indkey) with ordinality k(attnum, ord) "
                + "join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.attnum where") + " order by ic.relname, k.ord", schema);
    }

    /** (인덱스, 테이블, UNIQUE, 컬럼) 행을 순서대로 읽어 인덱스명 → 정의 */
    private static Map<String, String> definitions(JdbcTemplate jdbc, String sql, Object... args) {
        Map<String, String> result = new TreeMap<>();
        jdbc.query(sql, rs -> {
            String column = rs.getString(4) == null ? "" : rs.getString(4).toUpperCase(Locale.ROOT);
            result.merge(rs.getString(1).toUpperCase(Locale.ROOT), definition(rs.getString(2), rs.getBoolean(3), column), (a, b) -> a + "," + column);
        }, args);
        return result;
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static DriverManagerDataSource dataSource(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        return new DriverManagerDataSource(vendor.equals("h2") ? "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1" : postgresUrl, "lexorma", "lexorma");
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String schema, String vendor, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false);
        return (target == null ? configuration : configuration.target(target)).load();
    }
}
