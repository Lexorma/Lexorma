package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V7 (M-7 보강 3차 논리 관계 속성 짝) 의 증분 업그레이드 : V6 까지 적용된 DB 에 사용자 논리 모델(엔터티·속성·주 식별자·관계)과 물리 테이블·매핑이 있는 상태에서 V7 만 적용되고(체크섬 통과),
 * 기존 행이 그대로이며 기존 관계에는 속성 짝이 생기지 않는지(추론하지 않는다), 관계별 부모·자식 속성·순서 UNIQUE 와 FK 가 DB 제약으로 동작하는지(다른 관계는 같은 속성을 다시 쓸 수 있다),
 * 예제(프로젝트 0)에 새 테이블이 표준 용어 컬럼·논리 엔터티·매핑·관계 3개와 그 관계들의 속성 짝으로 들어오는지. 다시 migrate 해도 아무것도 실행되지 않는다. H2 와 PostgreSQL 모두.
 */
class V7LogicalRelationshipAttributeMigrationTest {

    @ParameterizedTest(name = "V6 → V7 증분 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradesFromV6KeepingData(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "v7_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway v6 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("6").cleanDisabled(false).load();
        Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target("7").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            assertThat(v6.migrate().migrationsExecuted).isEqualTo(7);
            var jdbc = new JdbcTemplate(dataSource);
            // V6 시점의 사용자 데이터 : 계정 → 프로젝트 → 논리 모델(공항 → 항공편 관계 2개) + 물리 테이블·엔터티 매핑
            jdbc.update("insert into " + s + "TB_ACNT(ACNT_SN, USER_ID, USER_NM, ACTVTN_YN, PSWD_CHG_YN) values (900000200, 'v7user', 'V7 사용자', true, true)");
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, MNGR_ACNT_SN, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000201, 'v7', 900000200, true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM) values (900000210, 900000201, 'v7 모델')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM) values (900000211, 900000201, 900000210, '공항')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM) values (900000212, 900000201, 900000210, '항공편')");
            jdbc.update("insert into " + s + "TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, SORT_ORD) values (900000221, 900000211, '공항 코드', 1)");
            jdbc.update("insert into " + s + "TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, SORT_ORD) values (900000222, 900000212, '출발 공항 코드', 1)");
            jdbc.update("insert into " + s + "TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, SORT_ORD) values (900000223, 900000212, '도착 공항 코드', 2)");
            jdbc.update("insert into " + s + "TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN) values (900000231, 900000211, '공항 식별자', true)");
            jdbc.update("insert into " + s + "TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values (900000232, 900000231, 900000221, 1)");
            String relationship = "insert into " + s + "TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, IDNTF_YN, ORGNL_MIN_CNT, TRGT_MIN_CNT)"
                    + " values (?, 900000201, 900000210, 900000211, 900000212, ?, false, 1, 0)";
            jdbc.update(relationship, 900000241L, "출발");
            jdbc.update(relationship, 900000242L, "도착");
            int exampleTables = jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0", Integer.class);
            int exampleRelationships = jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL where PRJ_SN = 0", Integer.class);

            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            latest.validate();
            assertThat(latest.migrate().migrationsExecuted).as("다시 띄워도 실행할 것이 없다").isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL where PRJ_SN = 900000201", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select REL_NM from " + s + "TB_LGCL_REL where LGCL_REL_SN = 900000241", String.class)).isEqualTo("출발");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL_ATRB x join " + s + "TB_LGCL_REL r on r.LGCL_REL_SN = x.LGCL_REL_SN where r.PRJ_SN <> 0", Integer.class))
                    .as("기존 관계에는 속성 짝을 만들지 않는다 (추론하지 않는다)").isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL r where r.PRJ_SN = 0 and exists (select 1 from " + s + "TB_LGCL_REL_ATRB x where x.LGCL_REL_SN = r.LGCL_REL_SN)", Integer.class))
                    .as("예제는 새 테이블의 관계 3개에만 짝").isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL where PRJ_SN = 0", Integer.class)).isEqualTo(exampleRelationships + 3);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0", Integer.class)).isEqualTo(exampleTables + 1);
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_LGCL_REL_ATRB' and c.STD_TRM_SN is null", String.class))
                    .as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();
            // 예제 짝 : 논리 속성 → 논리 관계 속성 관계가 둘(원본·대상 속성)이고 각각 다른 자식 속성을 쓴다
            assertThat(jdbc.queryForList("select ca.ATRB_NM from " + s + "TB_LGCL_REL_ATRB x join " + s + "TB_LGCL_ATRB pa on pa.LGCL_ATRB_SN = x.ORGNL_ATRB_SN join " + s
                    + "TB_LGCL_ATRB ca on ca.LGCL_ATRB_SN = x.TRGT_ATRB_SN where pa.ATRB_NM = '논리 속성 일련번호' order by ca.ATRB_NM", String.class))
                    .containsExactly("대상 속성 일련번호", "원본 속성 일련번호");

            // 제약 : 출발·도착 관계가 같은 부모 속성을 각자 쓴다 (다른 관계는 같은 속성 가능), 한 관계 안에서는 부모·자식·순서가 하나
            String pair = "insert into " + s + "TB_LGCL_REL_ATRB(LGCL_REL_ATRB_SN, LGCL_REL_SN, ORGNL_ATRB_SN, TRGT_ATRB_SN, SORT_ORD) values (?, ?, ?, ?, ?)";
            jdbc.update(pair, 900000251L, 900000241L, 900000221L, 900000222L, 1);
            jdbc.update(pair, 900000252L, 900000242L, 900000221L, 900000223L, 1);
            assertThatThrownBy(() -> jdbc.update(pair, 900000253L, 900000241L, 900000221L, 900000223L, 2)).as("같은 관계의 부모 속성 중복").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(pair, 900000254L, 900000241L, 900000222L, 900000222L, 2)).as("같은 관계의 자식 속성 중복").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(pair, 900000255L, 900000241L, 900000222L, 900000223L, 1)).as("같은 관계의 순서 중복").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(pair, 900000256L, 999999999L, 900000221L, 900000222L, 1)).as("없는 관계").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(pair, 900000257L, 900000241L, 999999999L, 900000223L, 3)).as("없는 속성").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_LGCL_ATRB where LGCL_ATRB_SN = 900000222")).as("짝에서 쓰는 속성은 DB 도 지우지 못한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            // UUID로 만든 검증 전용 스키마만 정리한다.
            latest.clean();
        }
    }
}
