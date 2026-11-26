package com.lexorma;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2 주제 영역 (M-6 후속 4차) : 이미 V1 까지 쓰던 설치를 올려도 기존 논리 모델·엔터티·배치는 그대로이고(수정 일시 포함) 주제 영역·소속·영역 배치는 하나도 생기지 않는다 (미분류).
 * 새 테이블의 DB 제약 : 자기 자신을 상위로 금지(CHECK), 엔터티 단일 소속(UNIQUE), 없는 모델·영역·엔터티 금지(FK), 상위가 있는 형제의 같은 이름(대소문자 같음) 금지 —
 * 대소문자 무시·최상위끼리의 중복·깊이·순환·같은 모델은 서비스 검사(모델 행 잠금)가 맡는다.
 */
class LogicalSubjectAreaMigrationTest {

    @Test
    @DisplayName("새 설치 (V0·V1, 예전 V2 합침) PostgreSQL")
    void upgradeKeepsExistingModelsUnclassified() {
        String schema = "sbj_area_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = TestDatabaseGuard.directDataSource();
        String s = "\"" + schema + "\".";
        Flyway v1 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        try {
            // 2026-11-22 네 번째 합침 : 예전 V2(주제 영역)는 V0·V1 에 있다 — 새 설치의 제약·예제를 본다 (예전 V1 → V2 업그레이드 검증은 더 이상 경로가 없다)
            assertThat(v1.migrate().migrationsExecuted).isEqualTo(4); // V0~V3
            assertThat(v1.validateWithResult().validationSuccessful).isTrue();
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000301, 'upgrade', true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM, MDFCN_DT) values (900000310, 900000301, '기존 모델', timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000311, 900000301, 900000310, '고객', true, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000312, 900000301, 900000310, '주문', false, timestamp '2026-11-01 10:00:00')");

            // 새 설치에는 주제 영역·소속·영역 배치가 없다 (예제 프로젝트에도 주제 영역 데이터는 없다, D-18)
            for (String table : List.of("TB_LGCL_SBJ_AREA", "TB_LGCL_SBJ_AREA_ENTT", "TB_LGCL_SBJ_AREA_LYT")) {
                assertThat(jdbc.queryForObject("select count(*) from " + s + table, Integer.class)).as(table).isZero();
            }
            // 예제 : 새 테이블 3개의 물리·논리 모델 (예제 정합성은 V0SeedMigrationTest 가 본다)
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_LGCL_SBJ_AREA', 'TB_LGCL_SBJ_AREA_ENTT', 'TB_LGCL_SBJ_AREA_LYT')", Integer.class)).isEqualTo(3);
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
