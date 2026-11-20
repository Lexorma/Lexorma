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
 * V2 주제 영역 (M-6 후속 4차) : 이미 V1 까지 쓰던 설치를 올려도 기존 논리 모델·엔터티·배치는 그대로이고(수정 일시 포함) 주제 영역·소속·영역 배치는 하나도 생기지 않는다 (미분류).
 * 새 테이블의 DB 제약 : 자기 자신을 상위로 금지(CHECK), 엔터티 단일 소속(UNIQUE), 없는 모델·영역·엔터티 금지(FK), 상위가 있는 형제의 같은 이름(대소문자 같음) 금지 —
 * 대소문자 무시·최상위끼리의 중복·깊이·순환·같은 모델은 서비스 검사(모델 행 잠금)가 맡는다. H2·PostgreSQL 모두.
 */
class LogicalSubjectAreaMigrationTest {

    @ParameterizedTest(name = "V1 설치 → V2 {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradeKeepsExistingModelsUnclassified(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "sbj_area_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        String s = "\"" + schema + "\".";
        Flyway v1 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target(MigrationVersion.fromVersion("1"))
                .cleanDisabled(false).load();
        try {
            assertThat(v1.migrate().migrationsExecuted).isEqualTo(2);
            var jdbc = new JdbcTemplate(dataSource);
            // V1 설치에서 쓰던 데이터 : 사용자 프로젝트의 논리 모델·엔터티(승인)·배치
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000301, 'upgrade', true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM, MDFCN_DT) values (900000310, 900000301, '기존 모델', timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000311, 900000301, 900000310, '고객', true, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000312, 900000301, 900000310, '주문', false, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ERD_LYT(LGCL_ERD_LYT_SN, PRJ_SN, LGCL_MDL_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO)"
                    + " values (900000313, 900000301, 900000310, 1, '{\"900000311\":{\"x\":1.0,\"y\":2.0}}', 0, 0, 1, 3)");
            List<Map<String, Object>> entitiesBefore = jdbc.queryForList("select LGCL_ENTT_SN, ENTT_NM, APRV_YN, MDFCN_DT from " + s + "TB_LGCL_ENTT where PRJ_SN = 900000301 order by LGCL_ENTT_SN");
            Map<String, Object> layoutBefore = jdbc.queryForMap("select NODE_PSTN_CN, VER_NO, MDFCN_DT from " + s + "TB_LGCL_ERD_LYT where LGCL_ERD_LYT_SN = 900000313");
            int exampleEntities = jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ENTT where PRJ_SN = 0", Integer.class);

            // V2 까지만 올린다 (V3 서브타입은 LogicalSubtypeMigrationTest 가 본다 — 이 검사의 예제 엔터티 수 +3 은 V2 의 것)
            Flyway latest = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).target(MigrationVersion.fromVersion("2")).load();
            assertThat(latest.migrate().migrationsExecuted).isOne();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();

            // 기존 데이터 그대로, 주제 영역·소속·영역 배치는 없다 (기본 영역·자동 분류·배치 복제 없음 — 예제 프로젝트에도 주제 영역 데이터는 없다, D-18)
            assertThat(jdbc.queryForList("select LGCL_ENTT_SN, ENTT_NM, APRV_YN, MDFCN_DT from " + s + "TB_LGCL_ENTT where PRJ_SN = 900000301 order by LGCL_ENTT_SN")).isEqualTo(entitiesBefore);
            assertThat(jdbc.queryForMap("select NODE_PSTN_CN, VER_NO, MDFCN_DT from " + s + "TB_LGCL_ERD_LYT where LGCL_ERD_LYT_SN = 900000313")).isEqualTo(layoutBefore);
            for (String table : List.of("TB_LGCL_SBJ_AREA", "TB_LGCL_SBJ_AREA_ENTT", "TB_LGCL_SBJ_AREA_LYT")) {
                assertThat(jdbc.queryForObject("select count(*) from " + s + table, Integer.class)).as(table).isZero();
            }
            // 예제 : 새 테이블 3개의 물리·논리 모델이 더해졌다 (예제 정합성은 V0SeedMigrationTest 가 본다)
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_LGCL_ENTT where PRJ_SN = 0", Integer.class)).isEqualTo(exampleEntities + 3);
            assertThat(jdbc.queryForList("select WORD_NM || ':' || WORD_ENG_ABBR_NM from " + s + "TB_STD_WORD where PRJ_SN = 0 and WORD_NM in ('주제', '영역', '상위') order by WORD_NM",
                    String.class)).containsExactly("상위:UP", "영역:AREA", "주제:SBJ");
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM like 'TB_LGCL_SBJ_AREA%' and c.STD_TRM_SN is null",
                    String.class)).as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();

            // DB 제약
            String area = "insert into " + s + "TB_LGCL_SBJ_AREA(LGCL_SBJ_AREA_SN, PRJ_SN, LGCL_MDL_SN, UP_SBJ_AREA_SN, SBJ_AREA_NM, SORT_ORD) values (?, 900000301, ?, ?, ?, 1)";
            jdbc.update(area, 900000320L, 900000310L, null, "고객 관리");
            jdbc.update(area, 900000321L, 900000310L, 900000320L, "회원");
            assertThatThrownBy(() -> jdbc.update(area, 900000322L, 900000310L, 900000322L, "자기")).as("자기 자신을 상위로").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(area, 900000323L, 900000310L, 900000999L, "없는 상위")).as("없는 상위").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(area, 900000324L, 999999999L, null, "없는 모델")).as("없는 모델").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(area, 900000325L, 900000310L, 900000320L, "회원")).as("같은 상위·같은 이름").isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(area, 900000326L, 900000310L, null, "고객 관리"); // 최상위끼리는 DB 가 막지 않는다 (NULL 상위) — 서비스 검사가 막는다
            jdbc.update(area, 900000327L, 900000310L, 900000320L, "회원 ".trim().toUpperCase() + "2"); // 다른 이름
            String member = "insert into " + s + "TB_LGCL_SBJ_AREA_ENTT(LGCL_SBJ_AREA_ENTT_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBJ_AREA_SN, LGCL_ENTT_SN) values (?, 900000301, 900000310, ?, ?)";
            jdbc.update(member, 900000330L, 900000321L, 900000311L);
            assertThatThrownBy(() -> jdbc.update(member, 900000331L, 900000320L, 900000311L)).as("엔터티 하나는 영역 하나").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(member, 900000332L, 900000320L, 999999999L)).as("없는 엔터티").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(member, 900000333L, 999999999L, 900000312L)).as("없는 영역").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_LGCL_SBJ_AREA where LGCL_SBJ_AREA_SN = 900000321")).as("소속이 있는 영역은 DB 도 지우지 못한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
            String layout = "insert into " + s + "TB_LGCL_SBJ_AREA_LYT(LGCL_SBJ_AREA_LYT_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBJ_AREA_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO)"
                    + " values (?, 900000301, 900000310, ?, ?, '{}', 0, 0, 1, 0)";
            jdbc.update(layout, 900000340L, 900000321L, 1L);
            jdbc.update(layout, 900000341L, 900000321L, 2L);
            assertThatThrownBy(() -> jdbc.update(layout, 900000342L, 900000321L, 1L)).as("사용자 × 영역 하나").isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            v1.clean(); // UUID 로 만든 검증 전용 스키마만 정리한다
        }
    }
}
