package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V2 상속 A·C 변환 저장 구조 (docs/completed/inheritance-strategy-ac-m7.md 1장) : V0·V1 로 만든 DB 를 V2 로 올리는 업그레이드. H2 와 PostgreSQL 모두.
 * 기존 행은 바뀌거나 지워지지 않고(승인 상태 포함 — 0.2), 새 두 테이블은 비어 있으며(예전 B형을 기록으로 추정하지 않는다), CHECK·UNIQUE 가 동작한다.
 */
class SubtypeStrategyConversionMigrationTest {

    /** 승인 상태가 있는 모델·사전 테이블과 그 일련번호 컬럼 */
    static final Map<String, String> APPROVAL = Map.of("TB_TBL", "TBL_SN", "TB_COL", "COL_SN", "TB_LGCL_ENTT", "LGCL_ENTT_SN", "TB_LGCL_REL", "LGCL_REL_SN", "TB_STD_DMN", "STD_DMN_SN", "TB_TBL_CNSTRT", "CNSTRT_SN",
            "TB_STD_TRM", "STD_TRM_SN", "TB_STD_WORD", "STD_WORD_SN", "TB_TBL_REL", "REL_SN");

    @ParameterizedTest(name = "V1 → V2 업그레이드 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgrade(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "sbtyp_cnvr_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v1 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("1").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            v1.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            // 승인 상태를 섞어 둔다 (V2 가 바꾸지 않는지 보려고)
            jdbc.update("update " + s + "TB_TBL set APRV_YN = false where TBL_SN in (select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 order by TBL_SN fetch first 3 rows only)");
            Map<String, Map<Long, Boolean>> before = new HashMap<>();
            for (var e : APPROVAL.entrySet()) before.put(e.getKey(), approvals(jdbc, s, e.getKey(), e.getValue()));
            int history = jdbc.queryForObject("select count(*) from " + s + "TB_MDL_CHG_HSTRY", Integer.class);

            Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false).load().migrate();
            assertThat(jdbc.queryForObject("select max(\"version\") from " + s + "\"flyway_schema_history\"", String.class)).isEqualTo("2");
            for (var e : APPROVAL.entrySet()) {
                Map<Long, Boolean> after = approvals(jdbc, s, e.getKey(), e.getValue());
                assertThat(after).as(e.getKey() + " : 기존 행의 승인 상태 그대로, 지워진 행 없음").containsAllEntriesOf(before.get(e.getKey()));
            }
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_MDL_CHG_HSTRY", Integer.class)).as("이력을 쓰지 않는다").isEqualTo(history);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SBTYP_GROUP_CNVR", Integer.class)).as("기존 그룹에 기록을 만들지 않는다").isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SBTYP_ATRB_COL_MPNG", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_SBTYP_GROUP_CNVR', 'TB_SBTYP_ATRB_COL_MPNG')", Integer.class))
                    .as("예제 물리 모델").isEqualTo(2);

            // 제약 : 유형 코드, A 는 테이블·분류 컬럼 필수, C 는 둘 다 없음, 그룹마다 1행
            List<Long> group = jdbc.queryForList("select LGCL_SBTYP_GROUP_SN from " + s + "TB_LGCL_SBTYP_GROUP order by LGCL_SBTYP_GROUP_SN fetch first 1 rows only", Long.class);
            if (!group.isEmpty()) {
                Long g = group.get(0);
                Long table = jdbc.queryForObject("select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 order by TBL_SN fetch first 1 rows only", Long.class);
                Long column = jdbc.queryForObject("select COL_SN from " + s + "TB_COL where TBL_SN = ? order by COL_SN fetch first 1 rows only", Long.class, table);
                String insert = "insert into " + s + "TB_SBTYP_GROUP_CNVR(SBTYP_GROUP_CNVR_SN, PRJ_SN, LGCL_SBTYP_GROUP_SN, CNVR_TYPE_CD, TBL_SN, CLSF_COL_SN) values (?, 0, ?, ?, ?, ?)";
                assertThatThrownBy(() -> jdbc.update(insert, 900000001L, g, "TABLE_PER_TYPE", null, null)).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update(insert, 900000002L, g, "SINGLE_TABLE", table, null)).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update(insert, 900000003L, g, "TABLE_PER_CONCRETE", table, null)).isInstanceOf(DataIntegrityViolationException.class);
                jdbc.update(insert, 900000004L, g, "SINGLE_TABLE", table, column);
                assertThatThrownBy(() -> jdbc.update(insert, 900000005L, g, "TABLE_PER_CONCRETE", null, null)).as("그룹마다 1행").isInstanceOf(DataIntegrityViolationException.class);
            }
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            v1.clean();
        }
    }

    static Map<Long, Boolean> approvals(JdbcTemplate jdbc, String s, String table, String key) {
        Map<Long, Boolean> result = new HashMap<>();
        jdbc.query("select " + key + ", APRV_YN from " + s + table, rs -> {
            Object v = rs.getObject(2);
            result.put(rs.getLong(1), v == null ? null : rs.getBoolean(2));
        });
        return result;
    }
}
