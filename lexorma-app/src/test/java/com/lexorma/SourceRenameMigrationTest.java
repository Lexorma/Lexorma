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
 * DEC-06 출처 컬럼 매핑·매핑 이력 스키마 신규 설치 : 두 테이블은 비어 있고 예제 물리 모델에 들어 있으며,
 * 컬럼 매핑의 1:1 제약(같은 TABLE 매핑 안의 같은 원본 컬럼·같은 모델 컬럼)·TABLE 매핑 해제 시 연쇄 삭제·재연결이 DB 제약으로 동작하는지.
 * (예전 V3 → V4 업그레이드 검증은 2026-11-18 세 번째 합침으로 V0·V1 만 남아 없어졌다)
 */
class SourceRenameMigrationTest {

    @Test
    @DisplayName("신규 설치 PostgreSQL : 출처 컬럼 매핑 제약")
    void freshInstall() {
        String schema = "src_rename_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            flyway.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_COL_MPNG", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_MPNG_HSTRY", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_SRC_COL_MPNG', 'TB_SRC_MPNG_HSTRY')", Integer.class)).isEqualTo(2);

            Long table = jdbc.queryForObject("select TBL_SN from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_TBL'", Long.class);
            Long column = jdbc.queryForObject("select COL_SN from " + s + "TB_COL where TBL_SN = ? and COL_NM = 'TBL_NM'", Long.class, table);
            Long other = jdbc.queryForObject("select COL_SN from " + s + "TB_COL where TBL_SN = ? and COL_NM = 'TBL_KORN_NM'", Long.class, table);
            // TABLE 매핑 (연결 900000001 의 원본 TABLE 900000002 → 예제 TB_TBL)
            String mapping = "insert into " + s + "TB_SRC_TBL_MPNG(SRC_TBL_MPNG_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, SRC_TBL_SN, PATH_CN, CMPR_NM, CRT_CYCL, RFLT_ORD, TBL_SN, MPNG_EXCL_YN)"
                    + " values (900000000, 0, 900000001, 900000003, 900000002, '6:public', 'tb_tbl', 1, ?, ?, false)";
            jdbc.update(mapping, 7, table);

            String insert = "insert into " + s + "TB_SRC_COL_MPNG(SRC_COL_MPNG_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_MPNG_SN, CMPR_NM, COL_NM, COL_SN, RFLT_ORD) values (?, 0, 900000001, 900000000, ?, ?, ?, 7)";
            jdbc.update(insert, 900000010L, "table_name", "table_name", column);
            // 같은 TABLE 매핑 안에서 같은 원본 컬럼, 같은 모델 컬럼은 하나만
            assertThatThrownBy(() -> jdbc.update(insert, 900000011L, "table_name", "table_name", other)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(insert, 900000012L, "kor_name", "kor_name", column)).isInstanceOf(DataIntegrityViolationException.class);
            // TABLE 매핑 해제 → 컬럼 매핑도 지워진다 (FK ON DELETE CASCADE), 다시 매핑하면 다시 이을 수 있다
            jdbc.update("delete from " + s + "TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = 900000000");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_COL_MPNG", Integer.class)).isZero();
            jdbc.update(mapping, 8, table);
            jdbc.update(insert, 900000013L, "table_name", "table_name", column);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_COL_MPNG", Integer.class)).isOne();
            // 이력은 FK 가 없어 해제 뒤에도 남는다 (append-only 는 서비스 규칙)
            jdbc.update("insert into " + s + "TB_SRC_MPNG_HSTRY(SRC_MPNG_HSTRY_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_MPNG_SN, TRGT_TYPE_CD, CHG_TYPE_CD, CHG_DT) values (900000020, 0, 900000001, 123, 'TABLE', 'RELEASE', current_timestamp)");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_SRC_MPNG_HSTRY", Integer.class)).isOne();
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            flyway.clean();
        }
    }
}
