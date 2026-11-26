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
 * M-7 보강 3차 논리 관계 속성 짝 스키마 신규 설치 : 예제(프로젝트 0)의 논리 관계마다 물리 FK 그대로 부모 주 식별자 속성 ↔ 자식 속성 짝이 있고
 * (기본 키가 아닌 (일련번호, 출처 접속 일련번호) UNIQUE 를 가리키는 출처 카탈로그의 복합 FK 9건만 비어 있다 — 2026-11-18 세 번째 합침),
 * 관계별 부모·자식 속성·순서 UNIQUE 와 FK 가 DB 제약으로 동작하는지(다른 관계는 같은 속성을 다시 쓸 수 있다), 짝에서 쓰는 속성은 DB 도 지우지 못하는지.
 * (예전 V6 → V7 업그레이드 검증은 세 번째 합침으로 V0·V1 만 남아 없어졌다)
 */
class LogicalRelationshipAttributeMigrationTest {

    @Test
    @DisplayName("신규 설치 PostgreSQL : 논리 관계 속성 짝")
    void freshInstall() {
        String schema = "lgcl_rel_atrb_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        String s = "\"" + schema + "\".";
        try {
            flyway.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL_ATRB'", Integer.class)).isOne();
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM = 'TB_LGCL_REL_ATRB' and c.STD_TRM_SN is null", String.class))
                    .as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();

            // 예제 짝 : 물리 FK 하나에 논리 관계 하나, 짝이 없는 관계는 복합 FK(… , 출처 접속 일련번호) 뿐이다
            String noPairs = " from " + s + "TB_LGCL_REL r where r.PRJ_SN = 0 and not exists (select 1 from " + s + "TB_LGCL_REL_ATRB x where x.LGCL_REL_SN = r.LGCL_REL_SN)";
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL where PRJ_SN = 0", Integer.class))
                    .isEqualTo(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_REL where PRJ_SN = 0 and REL_TYPE_CD = 'FK'", Integer.class));
            assertThat(jdbc.queryForList("select r.REL_EXPLN" + noPairs, String.class)).hasSize(9).allMatch(explain -> explain.contains(", 출처 접속 일련번호 로 "));
            // 짝의 부모 속성은 관계 원본 엔터티의 주 식별자, 자식 속성은 관계 대상 엔터티의 속성이다
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_REL_ATRB x join " + s + "TB_LGCL_REL r on r.LGCL_REL_SN = x.LGCL_REL_SN"
                    + " join " + s + "TB_LGCL_ATRB ca on ca.LGCL_ATRB_SN = x.TRGT_ATRB_SN where r.PRJ_SN = 0 and (ca.LGCL_ENTT_SN <> r.TRGT_ENTT_SN or not exists (select 1 from "
                    + s + "TB_LGCL_IDNTFR i join " + s + "TB_LGCL_IDNTFR_ATRB ia on ia.LGCL_IDNTFR_SN = i.LGCL_IDNTFR_SN where i.LGCL_ENTT_SN = r.ORGNL_ENTT_SN and i.BSC_YN = true"
                    + " and ia.LGCL_ATRB_SN = x.ORGNL_ATRB_SN and ia.SORT_ORD = x.SORT_ORD))", Integer.class)).isZero();
            // 논리 속성 → 논리 관계 속성 관계가 둘(원본·대상 속성)이고 각각 다른 자식 속성을 쓴다
            assertThat(jdbc.queryForList("select ca.ATRB_NM from " + s + "TB_LGCL_REL_ATRB x join " + s + "TB_LGCL_ATRB pa on pa.LGCL_ATRB_SN = x.ORGNL_ATRB_SN join " + s
                    + "TB_LGCL_ATRB ca on ca.LGCL_ATRB_SN = x.TRGT_ATRB_SN join " + s + "TB_LGCL_ENTT ce on ce.LGCL_ENTT_SN = ca.LGCL_ENTT_SN"
                    + " where pa.ATRB_NM = '논리 속성 일련번호' and ce.ENTT_NM = '논리 관계 속성' order by ca.ATRB_NM", String.class))
                    .containsExactly("대상 속성 일련번호", "원본 속성 일련번호");

            // 사용자 논리 모델 : 계정 → 프로젝트 → 공항 → 항공편 관계 2개
            jdbc.update("insert into " + s + "TB_ACNT(ACNT_SN, USER_ID, USER_NM, ACTVTN_YN, PSWD_CHG_YN) values (900000200, 'pairuser', '짝 사용자', true, true)");
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, MNGR_ACNT_SN, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000201, 'pair', 900000200, true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM) values (900000210, 900000201, '짝 모델')");
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
            flyway.clean();
        }
    }
}
