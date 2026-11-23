package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 상속 A·C 변환 저장 구조 마이그레이션 (docs/completed/inheritance-strategy-ac-m7.md 1장·9장). H2 와 PostgreSQL 모두.
 * <ul>
 *   <li>V1 → V2 : 기존 행은 바뀌거나 지워지지 않고(승인 상태 포함 — 0.2), 새 두 테이블은 비어 있다 (예전 B형을 기록으로 추정하지 않는다)</li>
 *   <li>V2 → V3 (2026-11-23 긴급 수정) : V2 의 CK_SBTYP_GROUP_CNVR_01 (IN 식) 은 H2 2.4.240 에서 제약을 만든 연결이 닫히면 평가 자체가 실패했다
 *       ('Check constraint invalid' 23514 / 내부 90098). V3 은 같은 이름의 CASE 식으로 다시 만든다. V2 상태의 변환 기록은 그대로 남는다</li>
 *   <li>CHECK 검증은 <b>제약을 만든 연결이 닫힌 뒤 새 연결로</b> 한다 (DriverManagerDataSource : Flyway 연결은 마이그레이션 뒤 닫힌다).
 *       정상 값은 들어가고, 잘못된 값은 '평가 오류'가 아닌 진짜 CHECK 위반으로 거절되어야 한다 — 예외 종류 하나로 성공 처리하지 않는다
 *       (H2 : 위반 23513 · 평가 오류 23514 / PostgreSQL : 위반 23514). 예전 테스트는 예제에 상속 그룹이 없어 이 검사 블록이 실행되지 않았다</li>
 * </ul>
 */
class SubtypeStrategyConversionMigrationTest {

    /** 승인 상태가 있는 모델·사전 테이블과 그 일련번호 컬럼 */
    static final Map<String, String> APPROVAL = Map.of("TB_TBL", "TBL_SN", "TB_COL", "COL_SN", "TB_LGCL_ENTT", "LGCL_ENTT_SN", "TB_LGCL_REL", "LGCL_REL_SN", "TB_STD_DMN", "STD_DMN_SN", "TB_TBL_CNSTRT", "CNSTRT_SN",
            "TB_STD_TRM", "STD_TRM_SN", "TB_STD_WORD", "STD_WORD_SN", "TB_TBL_REL", "REL_SN");

    static final String V2_EXAMPLE = "((cnvr_type_cd)::text = ANY ((ARRAY['SINGLE_TABLE'::character varying, 'TABLE_PER_CONCRETE'::character varying])::text[]))";
    static final String V3_EXAMPLE = "(CASE WHEN ((cnvr_type_cd)::text = 'SINGLE_TABLE'::text) THEN true WHEN ((cnvr_type_cd)::text = 'TABLE_PER_CONCRETE'::text) THEN true ELSE false END)";

    /** 이 테스트가 넣는 행의 ID (예제·시퀀스와 겹치지 않는 음수) */
    final AtomicLong ids = new AtomicLong(-900_000L);

    static String postgresUrl() {
        return System.getenv("LEXORMA_TEST_POSTGRES_URL");
    }

    static Flyway flyway(javax.sql.DataSource dataSource, String schema, String vendor, String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false);
        if (target != null) config.target(target);
        return config.load();
    }

    @ParameterizedTest(name = "V1 → V2 → V3 업그레이드 (V2 상태의 변환 기록 보존) {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgrade(String vendor) throws Exception {
        assumeTrue(vendor.equals("h2") || postgresUrl() != null, "PostgreSQL 검증 DB 미지정");
        String schema = "sbtyp_cnvr_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl();
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        String s = "\"" + schema + "\".";
        Flyway cleaner = flyway(dataSource, schema, vendor, null);
        try {
            flyway(dataSource, schema, vendor, "1").migrate();
            var jdbc = new JdbcTemplate(dataSource);
            // 승인 상태를 섞어 둔다 (V2·V3 가 바꾸지 않는지 보려고)
            jdbc.update("update " + s + "TB_TBL set APRV_YN = false where TBL_SN in (select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 order by TBL_SN fetch first 3 rows only)");
            Map<String, Map<Long, Boolean>> before = new HashMap<>();
            for (var e : APPROVAL.entrySet()) before.put(e.getKey(), approvals(jdbc, s, e.getKey(), e.getValue()));
            int history = jdbc.queryForObject("select count(*) from " + s + "TB_MDL_CHG_HSTRY", Integer.class);

            // V2 적용 + V2 상태의 변환 기록 : V2 를 적용한 그 연결이 살아 있는 동안 넣는다 (운영에서 마이그레이션 연결이 교체되기 전 상태 — H2 에서 V2 제약은
            // 만든 연결이 닫히면 평가할 수 없으므로 같은 연결로 넣어야 V2 DB 에 기록이 있는 상태를 만들 수 있다)
            List<Map<String, Object>> v2Records;
            try (Connection holder = DriverManager.getConnection(url, "lexorma", "lexorma")) {
                var single = new SingleConnectionDataSource(holder, true);
                flyway(single, schema, vendor, "2").migrate();
                var v2 = new JdbcTemplate(single);
                assertThat(v2.queryForObject("select count(*) from " + s + "TB_SBTYP_GROUP_CNVR", Integer.class)).as("V2 는 기존 그룹에 기록을 만들지 않는다").isZero();
                assertThat(v2.queryForObject("select count(*) from " + s + "TB_SBTYP_ATRB_COL_MPNG", Integer.class)).isZero();
                insertRecord(v2, s, group(v2, s), "SINGLE_TABLE", true);
                insertRecord(v2, s, group(v2, s), "TABLE_PER_CONCRETE", false);
                v2Records = records(v2, s);
                assertThat(v2Records).hasSize(2);
                assertThat(example(v2, s)).isEqualTo(V2_EXAMPLE);
            }

            var v3 = flyway(dataSource, schema, vendor, null).migrate();
            assertThat(v3.migrationsExecuted).isOne();
            assertThat(jdbc.queryForObject("select max(\"version\") from " + s + "\"flyway_schema_history\"", String.class)).isEqualTo("3");
            assertThat(records(jdbc, s)).as("V2 상태의 변환 기록은 그대로").isEqualTo(v2Records);
            for (var e : APPROVAL.entrySet()) {
                Map<Long, Boolean> after = approvals(jdbc, s, e.getKey(), e.getValue());
                assertThat(after).as(e.getKey() + " : 기존 행의 승인 상태 그대로, 지워진 행 없음").containsAllEntriesOf(before.get(e.getKey()));
            }
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_MDL_CHG_HSTRY", Integer.class)).as("이력을 쓰지 않는다").isEqualTo(history);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_SBTYP_GROUP_CNVR', 'TB_SBTYP_ATRB_COL_MPNG')", Integer.class))
                    .as("예제 물리 모델").isEqualTo(2);
            assertThat(example(jdbc, s)).as("예제 제약조건 설명을 새 정의로").isEqualTo(V3_EXAMPLE);
            constraints(vendor, jdbc, s);
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            cleaner.clean();
        }
    }

    @ParameterizedTest(name = "새 설치 V0 → V3 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void freshInstall(String vendor) throws Exception {
        assumeTrue(vendor.equals("h2") || postgresUrl() != null, "PostgreSQL 검증 DB 미지정");
        String schema = "sbtyp_cnvr_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl();
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        String s = "\"" + schema + "\".";
        Flyway flyway = flyway(dataSource, schema, vendor, null);
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(example(jdbc, s)).isEqualTo(V3_EXAMPLE);
            constraints(vendor, jdbc, s);
            checkAlone(vendor, jdbc, s);
        } finally {
            flyway.clean();
        }
    }

    @Test
    void h2FileDatabaseKeepsCheckAcrossConnectionsAndRestart(@TempDir Path dir) throws Exception {
        // H2 파일 DB (h2-local·h2-docker 와 같은 저장 방식) : ① 같은 프로세스에서 마이그레이션 연결이 닫힌 뒤(다른 연결이 DB 를 열어 둔 채) ② DB 를 닫았다 다시 연 뒤
        String url = "jdbc:h2:file:" + dir.resolve("lexorma").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
        String s = "\"PUBLIC\".";
        try (Connection keepOpen = DriverManager.getConnection(url, "lexorma", "lexorma")) {
            assertThat(keepOpen.isValid(1)).isTrue();
            var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
            assertThat(flyway(dataSource, "PUBLIC", "h2", null).migrate().migrationsExecuted).isEqualTo(4);
            constraints("h2", new JdbcTemplate(dataSource), s);
        }
        // 모든 연결이 닫혀 DB 가 닫혔다 → 다시 연다 (재기동)
        var reopened = new DriverManagerDataSource(url, "lexorma", "lexorma");
        assertThat(flyway(reopened, "PUBLIC", "h2", null).migrate().migrationsExecuted).isZero();
        constraints("h2", new JdbcTemplate(reopened), s);
    }

    /**
     * 변환 기록 제약 검사 (새 연결로) : 정상 두 전략은 들어가고, 잘못된 전략·NULL·CK_02 위반은 진짜 위반으로 거절된다. 넣은 행은 지운다
     */
    void constraints(String vendor, JdbcTemplate jdbc, String s) {
        Long single = insertRecord(jdbc, s, group(jdbc, s), "SINGLE_TABLE", true);
        Long concrete = insertRecord(jdbc, s, group(jdbc, s), "TABLE_PER_CONCRETE", false);
        assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN in (?, ?)", Integer.class, single, concrete)).isEqualTo(2);

        // 잘못된 전략 값 (예전 B형 코드·임의 값) : CHECK 위반. 두 값 밖의 값은 CK_02 도 거절하므로 어느 제약이 먼저 보고되는지는 DB 가 정한다 (둘 다 진짜 위반이어야 한다).
        // CK_01 만의 거절은 checkAlone 이 따로 본다
        for (String bad : List.of("TABLE_PER_TYPE", "X")) {
            rejected(vendor, "CHECK", List.of("CK_SBTYP_GROUP_CNVR_01", "CK_SBTYP_GROUP_CNVR_02"), () -> insertRecord(jdbc, s, group(jdbc, s), bad, false));
        }
        // NULL : 컬럼 NOT NULL 이 먼저 거절한다 (CHECK 의 NULL 처리와 무관 — V3 머리말)
        rejected(vendor, "NOT_NULL", List.of("CNVR_TYPE_CD"), () -> insertRecord(jdbc, s, group(jdbc, s), null, false));
        // CK_02 는 그대로 : A형인데 통합 테이블·분류 컬럼 없음, C형인데 테이블 있음
        rejected(vendor, "CHECK", List.of("CK_SBTYP_GROUP_CNVR_02"), () -> insertRecord(jdbc, s, group(jdbc, s), "SINGLE_TABLE", false));
        rejected(vendor, "CHECK", List.of("CK_SBTYP_GROUP_CNVR_02"), () -> insertRecordWith(jdbc, s, group(jdbc, s), "TABLE_PER_CONCRETE", table(jdbc, s), null));
        // 그룹마다 1행 (UNIQUE 그대로)
        Long taken = jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN = ?", Long.class, concrete);
        rejected(vendor, "UNIQUE", List.of("UX_SBTYP_GROUP_CNVR_01"), () -> insertRecord(jdbc, s, taken, "TABLE_PER_CONCRETE", false));

        jdbc.update("delete from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN in (?, ?)", single, concrete);
    }

    /**
     * CK_SBTYP_GROUP_CNVR_01 만으로의 판정 : 이 테스트 전용 스키마에서만 CK_02 를 지우고 (다른 DB·스키마는 건드리지 않는다), 새 연결로 정상 두 값은 들어가고
     * 잘못된 값은 CK_01 위반으로 거절되는지 본다 — 두 값 밖의 값을 CK_02 가 먼저 거절해 CK_01 의 동작이 가려지지 않게
     */
    void checkAlone(String vendor, JdbcTemplate jdbc, String s) {
        jdbc.execute("alter table " + s + "TB_SBTYP_GROUP_CNVR drop constraint CK_SBTYP_GROUP_CNVR_02");
        Long a = insertRecord(jdbc, s, group(jdbc, s), "SINGLE_TABLE", true);
        Long c = insertRecord(jdbc, s, group(jdbc, s), "TABLE_PER_CONCRETE", false);
        for (String bad : List.of("TABLE_PER_TYPE", "X", "single_table")) {
            rejected(vendor, "CHECK", List.of("CK_SBTYP_GROUP_CNVR_01"), () -> insertRecord(jdbc, s, group(jdbc, s), bad, false));
        }
        jdbc.update("delete from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN in (?, ?)", a, c);
    }

    /**
     * 거절이 기대한 종류의 진짜 위반인가 : SQLState 와 메시지의 제약 이름으로 본다. H2 의 'Check constraint invalid'(23514 — 평가 자체의 오류)나
     * 'database has been closed'(90098) 가 원인 사슬에 있으면 실패다
     */
    static void rejected(String vendor, String kind, List<String> names, Runnable insert) {
        try {
            insert.run();
        } catch (RuntimeException e) {
            SQLException sql = null;
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException x) {
                    if (sql == null) sql = x;
                    assertThat(x.getErrorCode()).as("연결 종료 오류(90098)는 제약 위반이 아니다 : " + x.getMessage()).isNotEqualTo(90098);
                    assertThat(String.valueOf(x.getMessage())).as("제약 평가 오류는 위반이 아니다").doesNotContain("Check constraint invalid", "database has been closed");
                }
            }
            assertThat((Object) sql).as("SQL 예외가 아니다 : " + e).isNotNull();
            String expected = switch (kind) {
                case "CHECK" -> vendor.equals("h2") ? "23513" : "23514";
                case "NOT_NULL" -> "23502";
                default -> "23505";
            };
            assertThat(sql.getSQLState()).as(sql.getMessage()).isEqualTo(expected);
            String message = sql.getMessage().toUpperCase();
            assertThat(names).as("위반한 제약·컬럼 : " + sql.getMessage()).anyMatch(message::contains);
            return;
        }
        fail(kind + " " + names + " 위반으로 거절되어야 한다");
    }

    /** 새 상속 그룹 (예제 프로젝트 0 의 논리 엔터티를 슈퍼타입으로) */
    Long group(JdbcTemplate jdbc, String s) {
        Map<String, Object> entity = jdbc.queryForMap("select LGCL_ENTT_SN, LGCL_MDL_SN from " + s + "TB_LGCL_ENTT where PRJ_SN = 0 order by LGCL_ENTT_SN fetch first 1 rows only");
        long id = ids.decrementAndGet();
        jdbc.update("insert into " + s + "TB_LGCL_SBTYP_GROUP(LGCL_SBTYP_GROUP_SN, PRJ_SN, LGCL_MDL_SN, SPRTYP_ENTT_SN, SBTYP_GROUP_NM, DPCN_PSBLTY_YN, TOTL_YN, CLSF_ATRB_SN, SORT_ORD)"
                + " values (?, 0, ?, ?, ?, false, true, null, 1)", id, entity.get("LGCL_MDL_SN"), entity.get("LGCL_ENTT_SN"), "검증 그룹 " + (-id));
        return id;
    }

    static Long table(JdbcTemplate jdbc, String s) {
        return jdbc.queryForObject("select min(TBL_SN) from " + s + "TB_TBL where PRJ_SN = 0", Long.class);
    }

    Long insertRecord(JdbcTemplate jdbc, String s, Long group, String type, boolean withTable) {
        if (!withTable) return insertRecordWith(jdbc, s, group, type, null, null);
        Long table = table(jdbc, s);
        Long column = jdbc.queryForObject("select min(COL_SN) from " + s + "TB_COL where TBL_SN = ?", Long.class, table);
        return insertRecordWith(jdbc, s, group, type, table, column);
    }

    Long insertRecordWith(JdbcTemplate jdbc, String s, Long group, String type, Long table, Long column) {
        long id = ids.decrementAndGet();
        jdbc.update("insert into " + s + "TB_SBTYP_GROUP_CNVR(SBTYP_GROUP_CNVR_SN, PRJ_SN, LGCL_SBTYP_GROUP_SN, CNVR_TYPE_CD, TBL_SN, CLSF_COL_SN) values (?, 0, ?, ?, ?, ?)",
                id, group, type, table, column);
        return id;
    }

    static List<Map<String, Object>> records(JdbcTemplate jdbc, String s) {
        return jdbc.queryForList("select SBTYP_GROUP_CNVR_SN, PRJ_SN, LGCL_SBTYP_GROUP_SN, CNVR_TYPE_CD, TBL_SN, CLSF_COL_SN from " + s + "TB_SBTYP_GROUP_CNVR order by SBTYP_GROUP_CNVR_SN");
    }

    static String example(JdbcTemplate jdbc, String s) {
        return jdbc.queryForObject("select k.INSP_EXPR_CN from " + s + "TB_TBL_CNSTRT k join " + s + "TB_TBL t on t.TBL_SN = k.TBL_SN"
                + " where t.PRJ_SN = 0 and t.TBL_NM = 'TB_SBTYP_GROUP_CNVR' and k.CNSTRT_NM = 'CK_SBTYP_GROUP_CNVR_01'", String.class);
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
