package com.lexorma;

import com.lexorma.data.column.service.ColumnNameKey;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 유니코드 정규화 (M7-U06 B3 · M7-U09, docs/completed/column-name-uniqueness-m7.md 14·15장) : COL_NM_CMPR_VL = 앞뒤 U+0020 제거 + 로캘 중립 확장 대문자, varchar(765).
 * 2026-11-22 네 번째 합침으로 예전 V5 는 V0·V1 에 있다 — 새 설치(V0·V1)의 최종 스키마, 로캘 무관 비교, 최대 길이·바이트 입력 저장과 같은 입력 거절을 본다
 * (예전 V4 → V5 업그레이드·사전 검사·복구 시나리오는 경로가 없어 뺐다). H2 / PostgreSQL (LEXORMA_TEST_POSTGRES_URL — libc · ICU und · ICU tr-TR 기본 로캘 DB 로 따로 실행해 확인).
 */
class ColumnNameUnicodeMigrationTest {

    private record Db(JdbcTemplate jdbc, String s, String schema, DriverManagerDataSource ds, String vendor) {
        Flyway flyway(String target) {
            var config = Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false);
            return (target == null ? config : config.target(MigrationVersion.fromVersion(target))).load();
        }

        boolean h2() {
            return vendor.equals("h2");
        }
    }

    private Db install(String vendor, String target, int expected) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "coluni_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var ds = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Db db = new Db(new JdbcTemplate(ds), "\"" + schema + "\".", schema, ds, vendor);
        assertThat(db.flyway(target).migrate().migrationsExecuted).isEqualTo(expected);
        db.jdbc().update("insert into " + db.s() + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900001001, 'coluni', true, false, true, 'POSTGRESQL')");
        db.jdbc().update("insert into " + db.s() + "TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (900001002, 900001001, 'DEFAULT', true)");
        for (long t : new long[]{900001010, 900001011}) {
            db.jdbc().update("insert into " + db.s() + "TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, 900001001, 900001002, ?, false)", t, "T" + t);
        }
        return db;
    }

    private static void column(Db db, long id, long table, String name) {
        db.jdbc().update("insert into " + db.s() + "TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, APRV_YN, MDFCN_DT) values (?, 900001001, ?, ?, ?, true, timestamp '2026-11-01 10:00:00')",
                id, table, "T" + table, name);
    }

    private static String key(Db db, long id) {
        return db.jdbc().queryForObject("select COL_NM_CMPR_VL from " + db.s() + "TB_COL where COL_SN = ?", String.class, id);
    }

    private static List<Map<String, Object>> rows(Db db) {
        return db.jdbc().queryForList("select COL_SN, TBL_SN, COL_NM, APRV_YN, MDFCN_DT from " + db.s() + "TB_COL where PRJ_SN = 900001001 order by COL_SN");
    }

    private static Integer keyLength(Db db) {
        List<Integer> length = db.jdbc().queryForList("select CHARACTER_MAXIMUM_LENGTH from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                + " and upper(COLUMN_NAME) = 'COL_NM_CMPR_VL'", Integer.class, db.schema());
        return length.isEmpty() ? null : length.getFirst();
    }

    private static int constraint(Db db, String name, String type) {
        return db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                + " and upper(CONSTRAINT_NAME) = ? and CONSTRAINT_TYPE = ?", Integer.class, db.schema(), name, type);
    }


    /** 최종 스키마 : 생성 컬럼 765 · UX_COL_01 · CK_COL_01 · NOT NULL · FK 2 + PK, PostgreSQL 은 ICU 대문자 + collate "C" */
    private static void assertFinalSchema(Db db) {
        assertThat(keyLength(db)).isEqualTo(765);
        assertThat(constraint(db, "UX_COL_01", "UNIQUE")).isOne();
        assertThat(constraint(db, "CK_COL_01", "CHECK")).isOne();
        assertThat(db.jdbc().queryForObject("select count(*) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                + " and CONSTRAINT_TYPE in ('FOREIGN KEY', 'PRIMARY KEY')", Integer.class, db.schema())).isEqualTo(3);
        assertThat(db.jdbc().queryForList("select IS_NULLABLE from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and upper(TABLE_NAME) = 'TB_COL'"
                + " and upper(COLUMN_NAME) in ('TBL_SN', 'COL_NM')", String.class, db.schema())).containsExactly("NO", "NO");
        if (db.h2()) {
            assertThat(db.jdbc().queryForObject("select GENERATION_EXPRESSION from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ? and TABLE_NAME = 'TB_COL' and COLUMN_NAME = 'COL_NM_CMPR_VL'",
                    String.class, db.schema())).contains("FN_UPPER_ROOT");
        } else {
            assertThat(db.jdbc().queryForObject("select pg_get_expr(d.adbin, d.adrelid) from pg_attrdef d join pg_attribute a on a.attrelid = d.adrelid and a.attnum = d.adnum"
                    + " where d.adrelid = '" + db.s().replace(".", "") + ".tb_col'::regclass and a.attname = 'col_nm_cmpr_vl'", String.class)).contains("und-x-icu");
            assertThat(db.jdbc().queryForObject("select c.collname from pg_attribute a join pg_collation c on c.oid = a.attcollation where a.attrelid = '"
                    + db.s().replace(".", "") + ".tb_col'::regclass and a.attname = 'col_nm_cmpr_vl'", String.class)).isEqualTo("C");
        }
        // 예제 : 도메인 값V765 · 용어 · 컬럼 길이 765
        assertThat(db.jdbc().queryForObject("select c.MAX_LEN || ':' || d.DMN_NM || ':' || d.DATA_LEN from " + db.s() + "TB_COL c join " + db.s() + "TB_STD_TRM t on t.STD_TRM_SN = c.STD_TRM_SN join "
                + db.s() + "TB_STD_DMN d on d.STD_DMN_SN = t.STD_DMN_SN where c.PRJ_SN = 0 and c.TBL_NM = 'TB_COL' and c.COL_NM = 'COL_NM_CMPR_VL'", String.class)).isEqualTo("765:값V765:765");
    }

    @ParameterizedTest(name = "신규 설치 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void freshInstall(String vendor) {
        Db db = install(vendor, null, 2);
        try {
            assertFinalSchema(db);
            column(db, 900001020, 900001010, "STRAßE");
            assertThat(key(db, 900001020)).isEqualTo("STRASSE");
            assertThatThrownBy(() -> column(db, 900001021, 900001010, " strasse ")).hasMessageContaining(db.h2() ? "UX_COL_01" : "ux_col_01");
            column(db, 900001022, 900001011, "strasse");
            // JVM 기본 로캘과 무관 : 터키어 로캘에서도 i 는 I (H2 의 UPPER 였다면 İ)
            Locale previous = Locale.getDefault();
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            try {
                column(db, 900001023, 900001010, "i_y");
                assertThat(key(db, 900001023)).isEqualTo("I_Y");
                assertThatThrownBy(() -> column(db, 900001024, 900001010, "I_Y")).isInstanceOf(DataAccessException.class);
            } finally {
                Locale.setDefault(previous);
            }
            assertThat(db.flyway(null).migrate().migrationsExecuted).isZero();
        } finally {
            db.flyway(null).clean();
        }
    }

    @ParameterizedTest(name = "최대 길이·바이트 경계 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void maximumLengthAndBytes(String vendor) {
        Db db = install(vendor, null, 2);
        try {
            // 압축하기 어려운 입력 : 대문자가 4바이트 이상이 되는 BMP 글자(ΐ ΰ ᾳ ﬓ և ŉ …)를 섞은 255자. H2 의 varchar(255) 는 UTF-16 단위라 BMP 만 쓴다
            List<Integer> wide = new ArrayList<>();
            for (int cp = 0x80; cp <= 0xFFFF; cp++) {
                if (cp >= 0xD800 && cp <= 0xDFFF) continue;
                String up = new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
                if (up.getBytes(StandardCharsets.UTF_8).length >= 4 && !up.equals(new String(Character.toChars(cp)))) wide.add(cp);
            }
            Random random = new Random(20261122);
            StringBuilder mixed = new StringBuilder();
            while (mixed.length() < 255) mixed.appendCodePoint(wide.get(random.nextInt(wide.size())));
            List<Object[]> inputs = List.of(
                    new Object[]{"ASCII 255", "a".repeat(255)}, new Object[]{"한글 255", "가".repeat(255)}, new Object[]{"ß 200", "ß".repeat(200)},
                    new Object[]{"ß 255", "ß".repeat(255)}, new Object[]{"ﬃ 255 (3글자)", "ﬃ".repeat(255)}, new Object[]{"ΐ 255 (3글자·6바이트)", "ΐ".repeat(255)},
                    new Object[]{"ᾳ 255 (2글자)", "ᾳ".repeat(255)}, new Object[]{"확장 글자 혼합 255", mixed.toString()},
                    new Object[]{"다국어 혼합 255", ("고객顧客客户ＡΣß" + "é").repeat(26).substring(0, 255)});
            List<Object[]> all = new ArrayList<>(inputs);
            if (!db.h2()) all.add(new Object[]{"보조 평면 255 (4바이트)", "𐐨".repeat(255)}); // 𐐨 → 𐐀
            long id = 900001100;
            long table = 900001010;
            int maxBytes = 0;
            for (Object[] input : all) {
                String name = (String) input[1];
                String label = (String) input[0];
                column(db, id, table, name);
                String key = key(db, id);
                assertThat(key).as(label).isEqualTo(ColumnNameKey.of(name));
                assertThat(db.jdbc().queryForObject("select COL_NM from " + db.s() + "TB_COL where COL_SN = ?", String.class, id)).as(label + " 이름 그대로").isEqualTo(name);
                int bytes = key.getBytes(StandardCharsets.UTF_8).length;
                maxBytes = Math.max(maxBytes, bytes);
                System.out.printf("[M7-U06 경계] %s %s : 이름 %d글자(UTF-16 %d) → 비교 값 %d코드 포인트 · UTF-16 %d · UTF-8 %d바이트%n", vendor, label,
                        name.codePointCount(0, name.length()), name.length(), key.codePointCount(0, key.length()), key.length(), bytes);
                long again = id + 500;
                long sameTable = table;
                assertThatThrownBy(() -> column(db, again, sameTable, name)).as(label + " 같은 입력 거절").isInstanceOf(DataAccessException.class)
                        .satisfies(e -> assertThat(ColumnNameKey.isNameConflict(e)).isTrue());
                id++;
                table = table == 900001010 ? 900001011 : 900001010;
            }
            // 대소문자만 다른 최대 길이 입력 (é·É 255) 도 같은 이름
            column(db, 900001200, 900001010, "é".repeat(255));
            assertThatThrownBy(() -> column(db, 900001201, 900001010, "É".repeat(255))).isInstanceOf(DataAccessException.class);
            // 255자를 넘는 이름은 예전처럼 COL_NM 에서 거절 (허용 길이는 바뀌지 않았다)
            assertThatThrownBy(() -> column(db, 900001202, 900001010, "a".repeat(256))).isInstanceOf(DataAccessException.class);
            assertThat(maxBytes).as("비교 값 최대 바이트 (255글자 × 6바이트 = 1,530)").isLessThanOrEqualTo(1530);
            if (!db.h2()) {
                System.out.printf("[M7-U06 경계] block_size=%s, 혼합 입력 index 저장 크기=%s%n", db.jdbc().queryForObject("show block_size", String.class),
                        db.jdbc().queryForObject("select pg_column_size(COL_NM_CMPR_VL) from " + db.s() + "TB_COL where COL_SN = 900001107", Integer.class));
            }
        } finally {
            db.flyway(null).clean();
        }
    }
}
