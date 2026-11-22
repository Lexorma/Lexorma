package com.lexorma;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V4 서브타입 식별자 컬럼 매핑 (M-7 3차) : 이미 V3 까지 쓰던 설치(논리·물리 모델, 서브타입 그룹, 엔터티·속성 매핑, ERD 배치, 변경 이력, 모델 버전)를 올려도
 * 기존 행은 값까지 그대로이고 V0~V3 의 체크섬도 그대로다. 새 테이블은 비어 있다 (기존 상속 구조에서 매핑을 추정해 넣지 않는다 — 예제에도 행이 없다).
 * DB 제약 : (엔터티 매핑, 원본 속성)·컬럼 UNIQUE, 같은 원본 속성은 여러 서브타입 테이블에서 쓸 수 있다(속성 단독 UNIQUE 없음), 없는 매핑·속성·컬럼 금지(FK),
 * 매핑이 쓰는 엔터티 매핑·속성·컬럼은 DB 도 지우지 못한다. 직접 속성 매핑과의 교차 중복은 서비스 검사다 (DB 제약으로 표현할 수 없다). H2·PostgreSQL 모두.
 */
class SubtypeIdentifierColumnMappingMigrationTest {

    private static final String[] KEPT = {"TB_LGCL_MDL", "TB_LGCL_ENTT", "TB_LGCL_ATRB", "TB_LGCL_IDNTFR", "TB_LGCL_SBTYP_GROUP", "TB_LGCL_SBTYP", "TB_TBL", "TB_COL",
            "TB_ENTT_TBL_MPNG", "TB_ATRB_COL_MPNG", "TB_LGCL_ERD_LYT", "TB_MDL_CHG_HSTRY", "TB_MDL_VER"};

    @ParameterizedTest(name = "새 설치 (V0·V1, 예전 V4 합침) {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradeKeepsExistingData(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "sbtypkey_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        String s = "\"" + schema + "\".";
        Flyway v3 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor)
                .cleanDisabled(false).load();
        try {
            // 2026-11-22 네 번째 합침 : 예전 V4(상속 PK 컬럼 매핑)는 V0·V1 에 있다 — 새 설치에 사용자 데이터를 넣고 제약·예제를 본다
            assertThat(v3.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(v3.validateWithResult().validationSuccessful).isTrue();
            var jdbc = new JdbcTemplate(dataSource);
            // 사용자 데이터 : 루트·서브타입(그룹)·엔터티 매핑·속성 매핑·물리 테이블·컬럼(PK·참조)·배치·이력·버전
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000501, 'upgrade', true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (900000502, 900000501, 'DEFAULT', true)");
            jdbc.update("insert into " + s + "TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM, MDFCN_DT) values (900000510, 900000501, '기존 모델', timestamp '2026-11-01 10:00:00')");
            for (long[] e : new long[][]{{900000511, 1}, {900000512, 0}, {900000513, 0}}) {
                jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (?, 900000501, 900000510, ?, ?, timestamp '2026-11-01 10:00:00')",
                        e[0], "엔터티" + e[0], e[1] == 1);
            }
            jdbc.update("insert into " + s + "TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ESNTL_YN, SORT_ORD, MDFCN_DT) values (900000514, 900000511, '고객 번호', true, 1, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN) values (900000515, 900000511, '고객 식별자', true)");
            jdbc.update("insert into " + s + "TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values (900000516, 900000515, 900000514, 1)");
            jdbc.update("insert into " + s + "TB_LGCL_SBTYP_GROUP(LGCL_SBTYP_GROUP_SN, PRJ_SN, LGCL_MDL_SN, SPRTYP_ENTT_SN, SBTYP_GROUP_NM, DPCN_PSBLTY_YN, TOTL_YN, SORT_ORD) values (900000517, 900000501, 900000510, 900000511, '구분', false, true, 1)");
            jdbc.update("insert into " + s + "TB_LGCL_SBTYP(LGCL_SBTYP_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBTYP_GROUP_SN, SBTYP_ENTT_SN, SORT_ORD) values (900000518, 900000501, 900000510, 900000517, 900000512, 1)");
            jdbc.update("insert into " + s + "TB_LGCL_SBTYP(LGCL_SBTYP_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBTYP_GROUP_SN, SBTYP_ENTT_SN, SORT_ORD) values (900000519, 900000501, 900000510, 900000517, 900000513, 2)");
            for (long[] t : new long[][]{{900000520, 900000511}, {900000521, 900000512}, {900000522, 900000513}}) {
                jdbc.update("insert into " + s + "TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, 900000501, 900000502, ?, false, timestamp '2026-11-01 10:00:00')", t[0], "T" + t[0]);
                jdbc.update("insert into " + s + "TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, PK_YN, NULL_ALWNC_YN, RFRNC_TBL_NM, APRV_YN, MDFCN_DT) values (?, 900000501, ?, ?, 'CUST_NO', true, false, ?, false, timestamp '2026-11-01 10:00:00')",
                        t[0] + 100, t[0], "T" + t[0], t[0] == 900000520 ? null : "T900000520");
                jdbc.update("insert into " + s + "TB_ENTT_TBL_MPNG(ENTT_TBL_MPNG_SN, PRJ_SN, LGCL_ENTT_SN, TBL_SN) values (?, 900000501, ?, ?)", t[0] + 200, t[1], t[0]);
            }
            jdbc.update("insert into " + s + "TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN) values (900000530, 900000720, 900000514, 900000620)");
            jdbc.update("insert into " + s + "TB_LGCL_ERD_LYT(LGCL_ERD_LYT_SN, PRJ_SN, LGCL_MDL_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO)"
                    + " values (900000531, 900000501, 900000510, 1, '{\"900000511\":{\"x\":1.0,\"y\":2.0}}', 0, 0, 1, 3)");
            jdbc.update("insert into " + s + "TB_MDL_CHG_HSTRY(MDL_CHG_HSTRY_SN, PRJ_SN, CHG_GROUP_ID, TRGT_TYPE_CD, TRGT_SN, TRGT_NM, CHG_TYPE_CD, CHG_SRC_CD, USER_ID, CHG_DT)"
                    + " values (900000532, 900000501, 'g1', 'SUBTYPE_GROUP', 900000517, '구분', 'CREATE', 'USER', 'u', timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_MDL_VER(MDL_VER_SN, PRJ_SN, VER_NM, FRMT_VER_NO, MDL_CNT_CN, MDL_CN, RGTR_ID, REG_DT) values (900000533, 900000501, 'v3', 8, '{}', '{}', 'u', timestamp '2026-11-01 10:00:00')");
            // 새 테이블은 비어 있다 (상속 구조에서 추정해 넣지 않는다 — 예제에도 매핑 행 없음)
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SBTYP_IDNTFR_COL_MPNG", Integer.class)).isZero();
            // 예제 : TB_SBTYP_IDNTFR_COL_MPNG 의 물리·논리 모델·인덱스 3개, 새 용어 1개 (새 단어 없음)
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_IDX i join " + s + "TB_TBL t on t.TBL_SN = i.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_SBTYP_IDNTFR_COL_MPNG'", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ENTT e join " + s + "TB_ENTT_TBL_MPNG m on m.LGCL_ENTT_SN = e.LGCL_ENTT_SN join " + s + "TB_TBL t on t.TBL_SN = m.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_SBTYP_IDNTFR_COL_MPNG'", Integer.class)).isOne();
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_SBTYP_IDNTFR_COL_MPNG' and c.STD_TRM_SN is null",
                    String.class)).as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();
            assertThat(jdbc.queryForObject("select TRM_ENG_ABBR_NM from " + s + "TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '서브타입_식별자_컬럼_매핑_일련번호'", String.class)).isEqualTo("SBTYP_IDNTFR_COL_MPNG_SN");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_STD_WORD where PRJ_SN = 0 and WORD_ENG_ABBR_NM = 'IDNTFR'", Integer.class)).as("기존 단어 그대로 (새로 만들지 않는다)").isOne();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SBTYP_IDNTFR_COL_MPNG s join " + s + "TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = s.ENTT_TBL_MPNG_SN where m.PRJ_SN = 0",
                    Integer.class)).as("예제에도 매핑 행은 없다").isZero();

            // DB 제약
            String key = "insert into " + s + "TB_SBTYP_IDNTFR_COL_MPNG(SBTYP_IDNTFR_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN) values (?, ?, ?, ?)";
            jdbc.update(key, 900000540L, 900000721L, 900000514L, 900000621L);
            jdbc.update(key, 900000541L, 900000722L, 900000514L, 900000622L); // 같은 원본 속성을 다른 서브타입 테이블이 쓴다
            assertThatThrownBy(() -> jdbc.update(key, 900000542L, 900000721L, 900000514L, 900000622L)).as("같은 (매핑, 속성)").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(key, 900000543L, 900000720L, 900000514L, 900000621L)).as("같은 컬럼").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(key, 900000544L, 999999999L, 900000514L, 900000620L)).as("없는 엔터티 매핑").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(key, 900000545L, 900000720L, 999999999L, 900000620L)).as("없는 속성").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(key, 900000546L, 900000720L, 900000514L, 999999999L)).as("없는 컬럼").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(key, null, 900000720L, 900000514L, 900000620L)).as("필수 값").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_COL where COL_SN = 900000621")).as("매핑된 컬럼").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_ENTT_TBL_MPNG where ENTT_TBL_MPNG_SN = 900000721")).as("엔터티 매핑").isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("delete from " + s + "TB_ATRB_COL_MPNG where ATRB_COL_MPNG_SN = 900000530");
            jdbc.update("delete from " + s + "TB_LGCL_IDNTFR_ATRB where LGCL_IDNTFR_ATRB_SN = 900000516");
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_LGCL_ATRB where LGCL_ATRB_SN = 900000514")).as("원본 속성").isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            v3.clean(); // UUID 로 만든 검증 전용 스키마만 정리한다
        }
    }
}
