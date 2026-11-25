package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 상속 A·C 변환 저장 구조 마이그레이션 (docs/completed/inheritance-strategy-ac-m7.md 1장·9장).
 * <ul>
 *   <li>V1 → V2 : 기존 행은 바뀌거나 지워지지 않고(승인 상태 포함 — 0.2), 새 두 테이블은 비어 있다 (예전 B형을 기록으로 추정하지 않는다)</li>
 *   <li>V2 → V3 (2026-11-23) : CK_SBTYP_GROUP_CNVR_01 을 같은 이름의 CASE 식으로 다시 만든다 (당시 H2 지원 때문의 수정 — 기록). V2 상태의 변환 기록은 그대로 남는다</li>
 *   <li>CHECK 검증은 <b>제약을 만든 연결이 닫힌 뒤 새 연결로</b> 한다 (DriverManagerDataSource : Flyway 연결은 마이그레이션 뒤 닫힌다).
 *       정상 값은 들어가고, 잘못된 값은 진짜 CHECK 위반(23514)으로 거절되어야 한다 — 예외 종류 하나로 성공 처리하지 않는다</li>
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

    static Flyway flyway(javax.sql.DataSource dataSource, String schema, String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false);
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    @DisplayName("V1 → V2 → V3 업그레이드 (V2 상태의 변환 기록 보존) PostgreSQL")
    void upgrade() throws Exception {
        String schema = "sbtyp_cnvr_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        String s = "\"" + schema + "\".";
        Flyway cleaner = flyway(dataSource, schema, null);
        try {
            flyway(dataSource, schema, "1").migrate();
            var jdbc = new JdbcTemplate(dataSource);
            // 승인 상태를 섞어 둔다 (V2·V3 가 바꾸지 않는지 보려고)
            jdbc.update("update " + s + "TB_TBL set APRV_YN = false where TBL_SN in (select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 order by TBL_SN fetch first 3 rows only)");
            Map<String, Map<Long, Boolean>> before = new HashMap<>();
            for (var e : APPROVAL.entrySet()) before.put(e.getKey(), approvals(jdbc, s, e.getKey(), e.getValue()));
            int history = jdbc.queryForObject("select count(*) from " + s + "TB_MDL_CHG_HSTRY", Integer.class);

            // V2 적용 + V2 상태의 변환 기록 (V3 가 기존 기록을 지키는지 보려고)
            List<Map<String, Object>> v2Records;
            {
                flyway(dataSource, schema, "2").migrate();
                var v2 = jdbc;
                assertThat(v2.queryForObject("select count(*) from " + s + "TB_SBTYP_GROUP_CNVR", Integer.class)).as("V2 는 기존 그룹에 기록을 만들지 않는다").isZero();
                assertThat(v2.queryForObject("select count(*) from " + s + "TB_SBTYP_ATRB_COL_MPNG", Integer.class)).isZero();
                insertRecord(v2, s, group(v2, s), "SINGLE_TABLE", true);
                insertRecord(v2, s, group(v2, s), "TABLE_PER_CONCRETE", false);
                v2Records = records(v2, s);
                assertThat(v2Records).hasSize(2);
                assertThat(example(v2, s)).isEqualTo(V2_EXAMPLE);
            }

            var v3 = flyway(dataSource, schema, null).migrate();
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
            constraints(jdbc, s);
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            cleaner.clean();
        }
    }

    @Test
    @DisplayName("새 설치 V0 → V3 PostgreSQL")
    void freshInstall() throws Exception {
        String schema = "sbtyp_cnvr_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        String s = "\"" + schema + "\".";
        Flyway flyway = flyway(dataSource, schema, null);
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(example(jdbc, s)).isEqualTo(V3_EXAMPLE);
            constraints(jdbc, s);
            checkAlone(jdbc, s);
        } finally {
            flyway.clean();
        }
    }

    /**
     * 변환 기록 제약 검사 (새 연결로) : 정상 두 전략은 들어가고, 잘못된 전략·NULL·CK_02 위반은 진짜 위반으로 거절된다. 넣은 행은 지운다
     */
    void constraints(JdbcTemplate jdbc, String s) {
        Long single = insertRecord(jdbc, s, group(jdbc, s), "SINGLE_TABLE", true);
        Long concrete = insertRecord(jdbc, s, group(jdbc, s), "TABLE_PER_CONCRETE", false);
        assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN in (?, ?)", Integer.class, single, concrete)).isEqualTo(2);

        // 잘못된 전략 값 (예전 B형 코드·임의 값) : CHECK 위반. 두 값 밖의 값은 CK_02 도 거절하므로 어느 제약이 먼저 보고되는지는 DB 가 정한다 (둘 다 진짜 위반이어야 한다).
        // CK_01 만의 거절은 checkAlone 이 따로 본다
        for (String bad : List.of("TABLE_PER_TYPE", "X")) {
            rejected("CHECK", List.of("CK_SBTYP_GROUP_CNVR_01", "CK_SBTYP_GROUP_CNVR_02"), () -> insertRecord(jdbc, s, group(jdbc, s), bad, false));
        }
        // NULL : 컬럼 NOT NULL 이 먼저 거절한다 (CHECK 의 NULL 처리와 무관 — V3 머리말)
        rejected("NOT_NULL", List.of("CNVR_TYPE_CD"), () -> insertRecord(jdbc, s, group(jdbc, s), null, false));
        // CK_02 는 그대로 : A형인데 통합 테이블·분류 컬럼 없음, C형인데 테이블 있음
        rejected("CHECK", List.of("CK_SBTYP_GROUP_CNVR_02"), () -> insertRecord(jdbc, s, group(jdbc, s), "SINGLE_TABLE", false));
        rejected("CHECK", List.of("CK_SBTYP_GROUP_CNVR_02"), () -> insertRecordWith(jdbc, s, group(jdbc, s), "TABLE_PER_CONCRETE", table(jdbc, s), null));
        // 그룹마다 1행 (UNIQUE 그대로)
        Long taken = jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN = ?", Long.class, concrete);
        rejected("UNIQUE", List.of("UX_SBTYP_GROUP_CNVR_01"), () -> insertRecord(jdbc, s, taken, "TABLE_PER_CONCRETE", false));

        jdbc.update("delete from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN in (?, ?)", single, concrete);
    }

    /**
     * CK_SBTYP_GROUP_CNVR_01 만으로의 판정 : 이 테스트 전용 스키마에서만 CK_02 를 지우고 (다른 DB·스키마는 건드리지 않는다), 새 연결로 정상 두 값은 들어가고
     * 잘못된 값은 CK_01 위반으로 거절되는지 본다 — 두 값 밖의 값을 CK_02 가 먼저 거절해 CK_01 의 동작이 가려지지 않게
     */
    void checkAlone(JdbcTemplate jdbc, String s) {
        jdbc.execute("alter table " + s + "TB_SBTYP_GROUP_CNVR drop constraint CK_SBTYP_GROUP_CNVR_02");
        Long a = insertRecord(jdbc, s, group(jdbc, s), "SINGLE_TABLE", true);
        Long c = insertRecord(jdbc, s, group(jdbc, s), "TABLE_PER_CONCRETE", false);
        for (String bad : List.of("TABLE_PER_TYPE", "X", "single_table")) {
            rejected("CHECK", List.of("CK_SBTYP_GROUP_CNVR_01"), () -> insertRecord(jdbc, s, group(jdbc, s), bad, false));
        }
        jdbc.update("delete from " + s + "TB_SBTYP_GROUP_CNVR where SBTYP_GROUP_CNVR_SN in (?, ?)", a, c);
    }

    /**
     * 거절이 기대한 종류의 진짜 위반인가 : SQLState 와 메시지의 제약 이름으로 본다
     */
    static void rejected(String kind, List<String> names, Runnable insert) {
        try {
            insert.run();
        } catch (RuntimeException e) {
            SQLException sql = null;
            for (Throwable t = e; t != null && sql == null; t = t.getCause()) {
                if (t instanceof SQLException x) sql = x;
            }
            assertThat((Object) sql).as("SQL 예외가 아니다 : " + e).isNotNull();
            String expected = switch (kind) {
                case "CHECK" -> "23514";
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
