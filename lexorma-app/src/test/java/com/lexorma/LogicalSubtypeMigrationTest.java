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
 * V3 서브타입 (M-6 후속 5차) : 이미 V2 까지 쓰던 설치를 올려도 기존 논리 모델·엔터티·속성·식별자·관계·주제 영역·배치·매핑·승인 상태는 그대로이고(수정 일시 포함)
 * 서브타입 그룹은 하나도 생기지 않는다. 새 테이블의 DB 제약 : 단일 상속(서브타입 엔터티 UNIQUE), 없는 모델·엔터티·그룹·속성 금지(FK), 배타성·완전성 필수(기본값 없음),
 * 같은 슈퍼타입 아래 같은 그룹명(대소문자 같음) 금지 — 자기 상속·순환·깊이·최소 구성원·같은 모델·대소문자 무시 이름은 서비스 검사(모델 행 잠금)가 맡는다. H2·PostgreSQL 모두.
 */
class LogicalSubtypeMigrationTest {

    @ParameterizedTest(name = "새 설치 (V0·V1, 예전 V3 합침) {0}")
    @ValueSource(strings = {"h2", "postgresql"})
    void upgradeKeepsExistingModels(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "subtype_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        String s = "\"" + schema + "\".";
        Flyway v2 = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor)
                .cleanDisabled(false).load();
        try {
            // 2026-11-22 네 번째 합침 : 예전 V3(서브타입)은 V0·V1 에 있다 — 새 설치에 사용자 데이터를 넣고 제약·예제를 본다
            assertThat(v2.migrate().migrationsExecuted).isEqualTo(3);
            assertThat(v2.validateWithResult().validationSuccessful).isTrue();
            var jdbc = new JdbcTemplate(dataSource);
            // 사용자 데이터 : 논리 모델·엔터티(승인·미승인)·속성·주 식별자·관계·주제 영역·소속·배치
            jdbc.update("insert into " + s + "TB_PRJ(PRJ_SN, PRJ_NM, ACTVTN_YN, AUTO_APRV_YN, DIC_ABBR_USE_YN, DB_TYPE_CD) values (900000401, 'upgrade', true, false, true, 'POSTGRESQL')");
            jdbc.update("insert into " + s + "TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM, MDFCN_DT) values (900000410, 900000401, '기존 모델', timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000411, 900000401, 900000410, '고객', true, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000412, 900000401, 900000410, '개인 고객', false, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, APRV_YN, MDFCN_DT) values (900000413, 900000401, 900000410, '기업 고객', false, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ESNTL_YN, SORT_ORD, MDFCN_DT) values (900000414, 900000411, '고객 번호', true, 1, timestamp '2026-11-01 10:00:00')");
            jdbc.update("insert into " + s + "TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN) values (900000415, 900000411, '고객 식별자', true)");
            jdbc.update("insert into " + s + "TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values (900000416, 900000415, 900000414, 1)");
            jdbc.update("insert into " + s + "TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, IDNTF_YN, ORGNL_MIN_CNT, TRGT_MIN_CNT)"
                    + " values (900000417, 900000401, 900000410, 900000411, 900000412, false, 1, 0)");
            jdbc.update("insert into " + s + "TB_LGCL_SBJ_AREA(LGCL_SBJ_AREA_SN, PRJ_SN, LGCL_MDL_SN, SBJ_AREA_NM, SORT_ORD) values (900000418, 900000401, 900000410, '고객 영역', 1)");
            jdbc.update("insert into " + s + "TB_LGCL_SBJ_AREA_ENTT(LGCL_SBJ_AREA_ENTT_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBJ_AREA_SN, LGCL_ENTT_SN) values (900000419, 900000401, 900000410, 900000418, 900000411)");
            jdbc.update("insert into " + s + "TB_LGCL_ERD_LYT(LGCL_ERD_LYT_SN, PRJ_SN, LGCL_MDL_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO)"
                    + " values (900000420, 900000401, 900000410, 1, '{\"900000411\":{\"x\":1.0,\"y\":2.0}}', 0, 0, 1, 3)");
            // 서브타입 그룹은 없다 (자동 생성 없음 — 예제 프로젝트에도 그룹 데이터는 없다)
            for (String table : List.of("TB_LGCL_REL", "TB_LGCL_SBJ_AREA_ENTT", "TB_LGCL_IDNTFR_ATRB")) {
                assertThat(jdbc.queryForObject("select count(*) from " + s + table + " where " + (table.equals("TB_LGCL_IDNTFR_ATRB") ? "LGCL_IDNTFR_ATRB_SN = 900000416" : "PRJ_SN = 900000401"),
                        Integer.class)).as(table).isOne();
            }
            for (String table : List.of("TB_LGCL_SBTYP_GROUP", "TB_LGCL_SBTYP")) {
                assertThat(jdbc.queryForObject("select count(*) from " + s + table, Integer.class)).as(table).isZero();
            }
            // 예제 : 서브타입 테이블 2개의 물리·논리 모델·인덱스 6개 (예제 정합성은 V0SeedMigrationTest 가 본다), 새 단어
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL where PRJ_SN = 0 and TBL_NM in ('TB_LGCL_SBTYP_GROUP', 'TB_LGCL_SBTYP')", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_TBL_IDX i join " + s + "TB_TBL t on t.TBL_SN = i.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM like 'TB_LGCL_SBTYP%'", Integer.class)).isEqualTo(6);
            assertThat(jdbc.queryForList("select WORD_NM || ':' || WORD_ENG_ABBR_NM from " + s + "TB_STD_WORD where PRJ_SN = 0 and WORD_NM in ('슈퍼타입', '서브타입', '완전', '중복', '가능', '그룹', '분류')"
                    , String.class)).as("DB 정렬 규칙과 무관하게 비교").containsExactlyInAnyOrder("가능:PSBLTY", "그룹:GROUP", "분류:CLSF", "서브타입:SBTYP", "슈퍼타입:SPRTYP", "완전:TOTL", "중복:DPCN");
            assertThat(jdbc.queryForObject("select count(*) from " + s + "TB_STD_WORD where PRJ_SN = 0 and WORD_ENG_ABBR_NM in ('TOTL', 'SPRTYP', 'SBTYP', 'DPCN', 'PSBLTY')", Integer.class))
                    .as("새 약어는 한 단어씩만 (충돌 없음)").isEqualTo(5);
            assertThat(jdbc.queryForList("select c.COL_NM from " + s + "TB_COL c join " + s + "TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = 0 and t.TBL_NM like 'TB_LGCL_SBTYP%' and c.STD_TRM_SN is null",
                    String.class)).as("예제 컬럼은 모두 표준 용어에 연결").isEmpty();
            assertThat(jdbc.queryForList("select t.TRM_ENG_ABBR_NM || ':' || d.DMN_NM from " + s + "TB_STD_TRM t join " + s + "TB_STD_DMN d on d.STD_DMN_SN = t.STD_DMN_SN"
                    + " where t.PRJ_SN = 0 and t.TRM_ENG_ABBR_NM in ('DPCN_PSBLTY_YN', 'TOTL_YN', 'CLSF_VL', 'SPRTYP_ENTT_SN')", String.class))
                    .containsExactlyInAnyOrder("CLSF_VL:값V255", "DPCN_PSBLTY_YN:여부B", "SPRTYP_ENTT_SN:일련번호L", "TOTL_YN:여부B");
            assertThat(jdbc.queryForObject("select TRM_NM from " + s + "TB_STD_TRM where PRJ_SN = 0 and TRM_ENG_ABBR_NM = 'DPCN_PSBLTY_YN'", String.class)).as("공통표준용어 그대로").isEqualTo("중복_가능_여부");

            // DB 제약
            String group = "insert into " + s + "TB_LGCL_SBTYP_GROUP(LGCL_SBTYP_GROUP_SN, PRJ_SN, LGCL_MDL_SN, SPRTYP_ENTT_SN, SBTYP_GROUP_NM, DPCN_PSBLTY_YN, TOTL_YN, CLSF_ATRB_SN, SORT_ORD)"
                    + " values (?, 900000401, ?, ?, ?, false, true, ?, 1)";
            jdbc.update(group, 900000430L, 900000410L, 900000411L, "고객 유형", 900000414L);
            assertThatThrownBy(() -> jdbc.update(group, 900000431L, 900000410L, 900000411L, "고객 유형", null)).as("같은 슈퍼타입·같은 이름").isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(group, 900000432L, 900000410L, 900000411L, "고객 특성", null); // 슈퍼타입마다 그룹 여러 개
            assertThatThrownBy(() -> jdbc.update(group, 900000433L, 900000410L, 999999999L, "없는 슈퍼타입", null)).as("없는 슈퍼타입").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(group, 900000434L, 999999999L, 900000411L, "없는 모델", null)).as("없는 모델").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(group, 900000435L, 900000410L, 900000411L, "없는 속성", 999999999L)).as("없는 분류 속성").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into " + s + "TB_LGCL_SBTYP_GROUP(LGCL_SBTYP_GROUP_SN, PRJ_SN, LGCL_MDL_SN, SPRTYP_ENTT_SN, SBTYP_GROUP_NM, TOTL_YN, SORT_ORD)"
                    + " values (900000436, 900000401, 900000410, 900000411, '배타성 없음', true, 1)")).as("배타성(중복 가능 여부) 기본값 없음").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into " + s + "TB_LGCL_SBTYP_GROUP(LGCL_SBTYP_GROUP_SN, PRJ_SN, LGCL_MDL_SN, SPRTYP_ENTT_SN, SBTYP_GROUP_NM, DPCN_PSBLTY_YN, SORT_ORD)"
                    + " values (900000437, 900000401, 900000410, 900000411, '완전성 없음', false, 1)")).as("완전성(완전 여부) 기본값 없음").isInstanceOf(DataIntegrityViolationException.class);
            String member = "insert into " + s + "TB_LGCL_SBTYP(LGCL_SBTYP_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBTYP_GROUP_SN, SBTYP_ENTT_SN, CLSF_VL, SORT_ORD) values (?, 900000401, 900000410, ?, ?, ?, ?)";
            jdbc.update(member, 900000440L, 900000430L, 900000412L, "INDIVIDUAL", 1);
            jdbc.update(member, 900000441L, 900000430L, 900000413L, "CORPORATE", 2);
            assertThatThrownBy(() -> jdbc.update(member, 900000442L, 900000432L, 900000412L, null, 1)).as("단일 상속 : 엔터티 하나는 그룹 하나에만").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(member, 900000443L, 900000430L, 999999999L, null, 3)).as("없는 엔터티").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(member, 900000444L, 999999999L, 900000411L, null, 1)).as("없는 그룹").isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_LGCL_SBTYP_GROUP where LGCL_SBTYP_GROUP_SN = 900000430")).as("구성원이 있는 그룹은 DB 도 지우지 못한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from " + s + "TB_LGCL_ATRB where LGCL_ATRB_SN = 900000414")).as("분류 속성(·식별자 속성)은 DB 도 지우지 못한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            v2.clean(); // UUID 로 만든 검증 전용 스키마만 정리한다
        }
    }
}
