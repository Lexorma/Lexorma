package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V3 (M-14 D-19) 기존 데이터 이관 : V2 까지 적용된 DB 의 컬럼 인덱스 여부(IDX_YN)가 예전 DDL 규칙 그대로 출처 COLUMN 인덱스가 된다.
 * PostgreSQL 은 LEXORMA_TEST_POSTGRES_URL 이 있을 때만 (UUID 스키마를 만들고 지운다).
 */
class IndexMigrationV3Test {

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgresql"})
    @DisplayName("IDX_YN → COLUMN 인덱스 : PK·고유값·같은 컬럼의 사용자 일반 인덱스는 제외, 승인 상태·기존 인덱스·IDX_YN 값은 그대로. 이어서 V4 는 기존 인덱스를 바꾸지 않는다")
    void migratesColumnFlags(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "idx_v3_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration/" + vendor).cleanDisabled(false).target("2").load();
        try {
            flyway.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            String s = "\"" + schema + "\".";
            jdbc.update("insert into " + s + "TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values(900001, 0, (select SCHM_SN from " + s + "TB_SCHM where PRJ_SN = 0 and BSC_YN = true), 'TB_MIG', true)");
            // 컬럼 : SN, 이름, PK, 고유, 인덱스 여부, 승인
            Object[][] columns = {
                    {900001, "ID", true, false, true, true},       // PK : 만들지 않는다
                    {900002, "A_CD", false, false, true, true},    // → IX_TB_MIG_A_CD (승인)
                    {900003, "B_CD", false, true, true, true},     // 고유값 : 만들지 않는다
                    {900004, "C_CD", false, false, true, false},   // 같은 컬럼 하나의 사용자 일반 인덱스가 있다 : 만들지 않는다
                    {900005, "D_CD", false, false, true, false},   // 사용자 고유 인덱스만 있다 : 만든다 (예전 DDL 도 둘 다 냈다)
                    {900006, "E_CD", false, false, false, true},   // 인덱스 아님
                    {900007, "F_CD", false, false, true, false}};  // 복합 인덱스의 일부일 뿐 : 만든다
            for (Object[] c : columns) {
                jdbc.update("insert into " + s + "TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, PK_YN, UNQ_YN, IDX_YN, APRV_YN) values(?, 0, 900001, 'TB_MIG', ?, ?, ?, ?, ?)", c);
            }
            userIndex(jdbc, s, 900001, "IX_MY_C", false, 900004);
            userIndex(jdbc, s, 900002, "UX_MY_D", true, 900005);
            userIndex(jdbc, s, 900003, "IX_MY_F_A", false, 900007, 900002);

            Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("3").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);

            List<String> derived = jdbc.queryForList("select i.IDX_NM || ':' || i.APRV_YN || ':' || c.COL_SN || ':' || c.COL_ORD from " + s + "TB_TBL_IDX i join "
                    + s + "TB_TBL_IDX_COL c on c.IDX_SN = i.IDX_SN where i.TBL_SN = 900001 and i.IDX_SRC_CD = 'COLUMN' and i.DRVN_COL_SN = c.COL_SN order by i.IDX_NM", String.class);
            assertThat(derived).map(String::toLowerCase).containsExactly("ix_tb_mig_a_cd:true:900002:1", "ix_tb_mig_d_cd:false:900005:1", "ix_tb_mig_f_cd:false:900007:1");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_IDX where TBL_SN = 900001 and IDX_SRC_CD = 'USER' and UNQ_YN is not null", Integer.class))
                    .as("기존 인덱스는 USER 로 그대로").isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_COL where TBL_SN = 900001 and IDX_YN = true", Integer.class))
                    .as("IDX_YN 값은 바꾸지 않는다").isEqualTo(6);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_IDX i where IDX_SRC_CD = 'COLUMN' and not exists (select 1 from "
                    + s + "TB_TBL_IDX_COL c where c.IDX_SN = i.IDX_SN)", Integer.class)).as("구성 컬럼 없는 인덱스 없음").isZero();
            // 새 테이블은 비어 있고 같은 마이그레이션을 다시 돌리지 않는다
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_IDX_CHG_DMND", Integer.class)).isZero();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();

            // V4 (M-14 2차 고급 인덱스) : 기존 인덱스·구성·ID·승인 상태는 그대로, 새 속성(조건·표현식·정렬)은 비어 있다
            String allIndexes = "select i.IDX_SN || ':' || i.IDX_NM || ':' || i.UNQ_YN || ':' || coalesce(cast(i.APRV_YN as varchar(5)), '-') || ':' || i.IDX_SRC_CD || ':' || c.COL_SN || ':' || c.COL_ORD from "
                    + s + "TB_TBL_IDX i join " + s + "TB_TBL_IDX_COL c on c.IDX_SN = i.IDX_SN order by i.IDX_SN, c.COL_ORD";
            List<String> beforeV4 = jdbc.queryForList(allIndexes, String.class);
            Flyway v4 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).load();
            assertThat(v4.migrate().migrationsExecuted).isPositive();
            assertThat(jdbc.queryForList(allIndexes, String.class)).isEqualTo(beforeV4).isNotEmpty();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_IDX where CND_CN is not null", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_IDX_COL where EXPR_CN is not null or SORT_CD is not null or COL_SN is null", Integer.class)).isZero();
            assertThat(v4.validateWithResult().validationSuccessful).isTrue();
        } finally {
            flyway.clean();
        }
    }

    private static void userIndex(JdbcTemplate jdbc, String s, long id, String name, boolean unique, long... columnIds) {
        jdbc.update("insert into " + s + "TB_TBL_IDX(IDX_SN, PRJ_SN, TBL_SN, IDX_NM, UNQ_YN, APRV_YN) values(?, 0, 900001, ?, ?, true)", id, name, unique);
        for (int i = 0; i < columnIds.length; i++) {
            jdbc.update("insert into " + s + "TB_TBL_IDX_COL(IDX_COL_SN, IDX_SN, COL_SN, COL_ORD) values(?, ?, ?, ?)", id * 10 + i, id, columnIds[i], i + 1);
        }
    }
}
