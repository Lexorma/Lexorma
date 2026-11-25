package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEC-16 출처 VIEW 정의·확인 이력 스키마 신규 설치 : 정의는 비어 있고(다음 수집부터) 두 테이블이 예제 물리 모델에 있으며,
 * VIEW 하나에 정의 한 행(UNIQUE)·VIEW 행을 지우면 정의도 지워짐(FK ON DELETE CASCADE)·확인 이력은 남음이 DB 제약으로 동작하는지.
 * (예전 V4 → V5 업그레이드 검증은 2026-11-18 세 번째 합침으로 V0·V1 만 남아 없어졌다)
 */
class SourceViewDefinitionMigrationTest {

    @Test
    @DisplayName("신규 설치 PostgreSQL : 출처 VIEW 정의 제약")
    void freshInstall() {
        String schema = "src_view_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            flyway.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_VIEW_DFN", Integer.class)).as("정의는 수집해야 생긴다").isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_SRC_VIEW_DFN', 'TB_SRC_VIEW_DFN_IDNTY_HSTRY')", Integer.class)).isEqualTo(2);

            // 원본 카탈로그 : 연결 → 스키마 → VIEW
            jdbc.update("insert into " + s + "TB_SRC_CNTN(SRC_CNTN_SN, PRJ_SN, CNTN_NM, DB_TYPE_CD, HOST_NM, PORT_NO, DB_NM, DB_NM_TYPE_CD, CNTN_USER_NM, IDNTFR_CMPR_CD, ACTVTN_YN)"
                    + " values (900000001, 0, 'view', 'POSTGRESQL', '127.0.0.1', 5432, 'src', 'DATABASE', 'u', 'DBMS_DEFAULT', true)");
            jdbc.update("insert into " + s + "TB_SRC_SCHM(SRC_SCHM_SN, PRJ_SN, SRC_CNTN_SN, SCHM_NM, PATH_CN, EXST_YN, CRT_CYCL) values (900000002, 0, 900000001, 'app', '6:app', true, 1)");
            jdbc.update("insert into " + s + "TB_SRC_TBL(SRC_TBL_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, TBL_NM, CMPR_NM, TBL_TYPE_CD, HASH_VL, EXST_YN, CRT_CYCL)"
                    + " values (900000003, 0, 900000001, 900000002, 'v_a', 'v_a', 'VIEW', 'v1:x', true, 1)");

            String insert = "insert into " + s + "TB_SRC_VIEW_DFN(SRC_VIEW_DFN_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_SN, DFN_STTS_CD, DFN_CN, ENCPT_YN, HASH_VL) values (?, 0, 900000001, 900000003, 'COLLECTED', 'x', false, 'd1:x')";
            jdbc.update(insert, 900000010L);
            assertThatThrownBy(() -> jdbc.update(insert, 900000011L)).as("VIEW 하나에 정의 한 행").isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("insert into " + s + "TB_SRC_VIEW_DFN_IDNTY_HSTRY(SRC_VIEW_DFN_IDNTY_HSTRY_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_SN, HASH_VL, IDNTY_DT) values (900000020, 0, 900000001, 900000003, 'd1:x', current_timestamp)");
            jdbc.update("delete from " + s + "TB_SRC_TBL where SRC_TBL_SN = 900000003");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_VIEW_DFN", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_VIEW_DFN_IDNTY_HSTRY", Integer.class)).as("확인 이력은 FK 가 없어 남는다").isOne();
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            flyway.clean();
        }
    }
}
