package com.lexorma;

import com.lexorma.common.i18n.Messages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V0SeedMigrationTest {

    @Test
    @DisplayName("V0 초기 데이터 검증 PostgreSQL")
    void initializesDictionaryAndWelcomeNotice() {
        String schema = "seed_domains_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        try {
            // V0 (스키마·COMMENT) + V1 (초기 데이터 : 기본 데이터, 예제 데이터 사전·물리 모델·논리 모델·매핑·인덱스).
            // 2026-11-13 에 예전 V2~V6, 2026-11-15 Flyway 초기화 때 예전 V2~V10 을, 같은 날 두 번째로 그 뒤의 V2~V5(AI 설명 품질 평가·안내 공지의 AI 절·예제 업무 설명·예제 코드 용어 정의)를,
            // 2026-11-18 세 번째로 그 뒤의 증분 V2~V7(판정 변경 이력·프로젝트 AI 설정·출처 컬럼 매핑·출처 VIEW 정의·내장 MCP·논리 관계 속성 짝)을 V0·V1 로 합쳤다.
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5); // V0·V1·V2·V3·V4 (V2 상속 A·C 저장 구조, V3 그 CHECK 재정의 — 2026-11-23, V4 저장 품질 검사 — AI-6 2차) — V0·V1 은 (2026-11-22 네 번째 합침 — 예전 V2 주제 영역 · V3 서브타입 · V4 서브타입 식별자 컬럼 매핑 · V5 컬럼 데이터 무결성 포함)
            var jdbc = new JdbcTemplate(dataSource);
            // V0·V2·V3·V4 의 create index 166개(컬럼 238개, V4 저장 품질 검사 7개 포함 — IX_INSP_RSLT_05 는 FINAL 최종 보완에서 제거)가 모두 승인된 USER 예제 인덱스로 들어가고 임시 테이블은 남지 않는다.
            // create index 를 추가하면 V1 에 예제 인덱스도 넣고 이 수를 고친다.
            String idx = "\"" + schema + "\".TB_TBL_IDX";
            assertThat(jdbc.queryForObject("select count(*) from " + idx + " where PRJ_SN = 0 and IDX_SRC_CD = 'USER' and APRV_YN = true", Integer.class)).isEqualTo(166);
            assertThat(jdbc.queryForObject("select count(*) from " + idx + "_COL x join " + idx + " i on i.IDX_SN = x.IDX_SN where i.PRJ_SN = 0", Integer.class)).isEqualTo(238);
            assertThat(jdbc.queryForObject("select count(*) from INFORMATION_SCHEMA.TABLES where upper(TABLE_NAME) = 'TMP_IDX_SEED'", Integer.class)).isZero();
            // 초기 데이터에 예전 프로젝트 이름이 없다
            assertThat(oldNames(jdbc, "\"" + schema + "\".")).isZero();
            assertThat(jdbc.queryForObject("select PRJ_NM from \"" + schema + "\".TB_PRJ where PRJ_SN = 0", String.class)).isEqualTo("LEXORMA");
            // V1 : 물리 모델 예제와 같은 수의 엔터티·속성·관계, PK 가 있는 테이블마다 주 식별자, 고아 행 없음
            String s = "\"" + schema + "\".";
            // 실제 스키마의 TB_ 테이블·컬럼은 모두 LEXORMA 물리 모델 예제에 있고 (논리 예제는 아래에서 물리 예제와 수를 맞춘다),
            // 테이블 COMMENT = 예제의 한글명, 컬럼 COMMENT = 예제의 컬럼 설명이다.
            // 테이블·컬럼을 추가하면 같은 마이그레이션에 COMMENT 와 예제(물리·논리)를 넣어야 이 검사를 통과한다.
            Map<String, String> tableComments = comments(jdbc, "select upper(c.relname), obj_description(c.oid) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    + "where n.nspname = ? and c.relkind = 'r' and c.relname like 'tb\\_%'", schema);
            Map<String, String> columnComments = comments(jdbc, "select upper(c.relname || '.' || a.attname), col_description(c.oid, a.attnum) from pg_attribute a join pg_class c on c.oid = a.attrelid "
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
            // 예제는 실제 스키마와 같다 (2026-11-14, V6) : FK·UNIQUE·CHECK 제약 이름이 예제의 관계·제약조건과 같고, 기본값이 있는 컬럼이 같다.
            // 테이블·컬럼을 더할 때 FK·UNIQUE·CHECK·DEFAULT 도 같은 마이그레이션에서 예제에 넣어야 이 검사를 통과한다 (값까지는 원본·모델 비교가 본다)
            String constraints = "select upper(TABLE_NAME) || '.' || upper(CONSTRAINT_NAME) from INFORMATION_SCHEMA.TABLE_CONSTRAINTS where TABLE_SCHEMA = ? and CONSTRAINT_TYPE = ?"
                    + " and upper(TABLE_NAME) like 'TB\\_%' escape '\\' and (upper(CONSTRAINT_NAME) like 'FK\\_%' escape '\\' or upper(CONSTRAINT_NAME) like 'UX\\_%' escape '\\'"
                    + " or upper(CONSTRAINT_NAME) like 'CK\\_%' escape '\\')";
            assertThat(new java.util.TreeSet<>(jdbc.queryForList(constraints, String.class, schema, "FOREIGN KEY")))
                    .isEqualTo(new java.util.TreeSet<>(jdbc.queryForList("select t.TBL_NM || '.' || upper(r.REL_NM) from " + s + "TB_TBL_REL r join " + s
                            + "TB_TBL t on t.TBL_SN = r.ORGNL_TBL_SN where r.PRJ_SN = 0 and r.REL_TYPE_CD = 'FK'", String.class))).isNotEmpty();
            java.util.Set<String> keys = new java.util.TreeSet<>(jdbc.queryForList(constraints, String.class, schema, "UNIQUE"));
            keys.addAll(jdbc.queryForList(constraints, String.class, schema, "CHECK"));
            assertThat(keys).isEqualTo(new java.util.TreeSet<>(jdbc.queryForList("select t.TBL_NM || '.' || upper(k.CNSTRT_NM) from " + s + "TB_TBL_CNSTRT k join " + s
                    + "TB_TBL t on t.TBL_SN = k.TBL_SN where k.PRJ_SN = 0", String.class))).isNotEmpty();
            assertThat(new java.util.TreeSet<>(jdbc.queryForList("select upper(TABLE_NAME) || '.' || upper(COLUMN_NAME) from INFORMATION_SCHEMA.COLUMNS where TABLE_SCHEMA = ?"
                    + " and upper(TABLE_NAME) like 'TB\\_%' escape '\\' and COLUMN_DEFAULT is not null and IS_IDENTITY <> 'YES'", String.class, schema)))
                    .isEqualTo(new java.util.TreeSet<>(jdbc.queryForList("select TBL_NM || '.' || COL_NM from " + s + "TB_COL where PRJ_SN = 0 and BSC_VL is not null", String.class)));
            // FK 삭제·수정 규칙과 IDENTITY 도 예제와 같다 (V7)
            assertThat(new java.util.TreeSet<>(jdbc.queryForList("select upper(tc.TABLE_NAME) || '.' || upper(rc.CONSTRAINT_NAME) || ':' || rc.DELETE_RULE || ':' || rc.UPDATE_RULE"
                    + " from INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc join INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc on tc.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA"
                    + " and tc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME where rc.CONSTRAINT_SCHEMA = ? and upper(tc.TABLE_NAME) like 'TB\\_%' escape '\\'", String.class, schema)))
                    .isEqualTo(new java.util.TreeSet<>(jdbc.queryForList("select t.TBL_NM || '.' || upper(r.REL_NM) || ':' || replace(coalesce(r.DEL_RULE_CD, 'NO_ACTION'), '_', ' ')"
                            + " || ':' || replace(coalesce(r.MDFCN_RULE_CD, 'NO_ACTION'), '_', ' ') from " + s + "TB_TBL_REL r join " + s
                            + "TB_TBL t on t.TBL_SN = r.ORGNL_TBL_SN where r.PRJ_SN = 0 and r.REL_TYPE_CD = 'FK'", String.class)));
            assertThat(new java.util.TreeSet<>(jdbc.queryForList("select upper(TABLE_NAME) || '.' || upper(COLUMN_NAME) || ':' || IDENTITY_GENERATION from INFORMATION_SCHEMA.COLUMNS"
                    + " where TABLE_SCHEMA = ? and upper(TABLE_NAME) like 'TB\\_%' escape '\\' and IS_IDENTITY = 'YES'", String.class, schema)))
                    .isEqualTo(new java.util.TreeSet<>(jdbc.queryForList("select TBL_NM || '.' || COL_NM || ':' || replace(AUTO_CRT_MTHD_CD, '_', ' ') from " + s
                            + "TB_COL where PRJ_SN = 0 and AUTO_CRT_MTHD_CD is not null", String.class))).isNotEmpty();
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
            // V1 의 29개 + V5 의 값V765 (컬럼명 비교 값 길이, M7-U06) + V4 의 코드V50·내용J (저장 품질 검사, AI-6 2차)
            assertThat(jdbc.queryForObject("select count(*) from " + domains, Integer.class)).isEqualTo(32);
            assertThat(jdbc.queryForObject("select DATA_LEN from " + domains + " where DMN_NM = '값V765'", Integer.class)).isEqualTo(765);
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
                    .contains("논리 모델", "논리·물리 매핑", "DDL 내보내기", "변경 이력과 모델 버전", "원본 DB 연결과 가져오기", "LEXORMA_SECRET_KEYS")
                    .contains("### 11. AI 기능 (선택)", "AI 도우미", "AI 설명 제안", "외부 연결 보안")
                    // 사이드바 AI 관리 메뉴에 맞춘 AI 절 (예전 V3 를 V1 본문에 합침)
                    .contains("AI 관리 › AI Provider 설정", "AI 관리 › AI 설명 제안", "**AI 품질 평가**").doesNotContain("데이터 사전에서 테이블·컬럼의 업무 설명 초안")
                    // 영어판(EnglishMessages)의 키와 한 글자도 다르지 않아야 영어 화면에 영어로 나온다.
                    .matches(Messages::hasEnglish)
                    .doesNotContain("<a", "mailto:");
            assertThat(jdbc.queryForObject("select count(*) from " + posts
                    + " where PRJ_SN = 0 and CTGRY_CD = 'NOTICE' and DEL_YN = 'N' and cardinality(PRJ_SN_LIST) = 1"
                    + " and PST_MDFCN_DT is not null and REG_DT is not null and MDFCN_DT is not null", Integer.class)).isEqualTo(1);
            // 새 설치에는 계정이 없다 (2026-11-19, 기본 계정 admin/admin 제거) : 최초 관리자 계정은 첫 화면에서 만든다.
            // 안내 공지의 작성자는 SYSTEM, 조회자·프로젝트 관리자·멤버는 없다
            assertThat(jdbc.queryForObject("select WRTR_ID from " + posts + " where PST_SN = 1", String.class)).isEqualTo("SYSTEM");
            assertThat(jdbc.queryForObject("select count(*) from " + accounts, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + readers, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from \"" + schema + "\".TB_ACNT_ROLE", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from \"" + schema + "\".TB_PRJ_MBR", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from \"" + schema + "\".TB_PRJ where MNGR_ACNT_SN is not null", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select nextval('" + schema + ".SQ_PST')", Long.class)).isEqualTo(51L);

            // 재시작과 동일하게 별도 Flyway 인스턴스에서도 V0·V1 은 재실행되지 않는다.
            Flyway restarted = Flyway.configure().dataSource(dataSource).schemas(schema)
                    .locations("classpath:db/migration/postgresql").load();
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
            // Lexorma / Lexorma / LEXORMA
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
