package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 상속 A·C PostgreSQL 동시성 (docs/completed/inheritance-strategy-ac-m7.md 7장). PostgreSQL 에서만 (H2 는 행 잠금 강도가 다르다 — 건너뛴다).
 * 먼저 시작한 작업 A 를 같은 트랜잭션 안에서 서비스가 끝난 뒤(커밋 전 — 잠금을 쥔 채) 멈추고 작업 B 를 실행해, B 가 잠금을 기다리는지(pg_locks) 또는 기다리지 않고 409 인지 본 뒤 A 를 놓는다.
 * sleep 으로 순서를 정하지 않는다. 시나리오마다 방향별 3번. 허용하지 않는 결과 : 40P01, 중복 테이블·기록·매핑, 부분 적용, 혼합 전략.
 * 번호는 작업 지시(M-7 AC 후속 보완 7.2)의 AC-C01~C18 을 따른다 (2026-11-23 다시 매김 — 예전 C03 = C06, 예전 C08 = C15, 예전 C05·C06·C07 = 추가 시나리오 X01~X03).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InheritanceStrategyConcurrencyIntegrationTest {

    static volatile CountDownLatch paused;
    static volatile CountDownLatch release;

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired ModelMappingService mappings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    static final String A = "SINGLE_TABLE";
    static final String C = "TABLE_PER_CONCRETE";

    @AfterEach
    void tearDown() {
        if (release != null) release.countDown();
        SecurityTestSupport.clear();
    }

    boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    // ------------------------------------------------------------------ 준비

    /** 회원(PK 회원번호, 회원명, 회원유형코드 분류) → 개인 회원(생년월일) · 기업 회원(사업자번호, 기업명) — Disjoint·Total */
    record M(GenerationFixture f, long member, long person, long corp, long group, long memberNo, long memberName, long type, long bizNo, long corpName) {
        List<Long> all() {
            return List.of(member, person, corp);
        }
    }

    long entity(GenerationFixture f, String name) {
        long id = f.entity(name);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    M model(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "acc-" + name);
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '회원', 'MBR', 'MBR', '회원', false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId());
        long member = entity(f, "회원");
        long memberNo = f.attr(member, "회원 번호", true, "MBR_NO");
        f.pk(member, memberNo);
        long memberName = f.attr(member, "회원 이름", true, "MBR_NM");
        long type = f.attr(member, "회원 유형 코드", true, "MBR_TY_CD");
        long person = entity(f, "개인 회원");
        f.attr(person, "생년월일", true, "BRDT");
        long corp = entity(f, "기업 회원");
        long bizNo = f.attr(corp, "사업자 번호", false, "BRNO");
        long corpName = f.attr(corp, "기업 이름", false, "ENTP_NM");
        context.getBean(com.lexorma.data.logical.service.LogicalModelService.class).saveIdentifier(f.user,
                new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(null, corp, "사업자 번호 식별자", false, List.of(bizNo), null));
        long group = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "회원 구분", null, false, true, type,
                List.of(new MemberInput(person, "P"), new MemberInput(corp, "C")), null)).id();
        return new M(f, member, person, corp, group, memberNo, memberName, type, bizNo, corpName);
    }

    static PhysicalGenerationDto.Request request(M m, List<Long> entities, String token, String strategy) {
        return new PhysicalGenerationDto.Request(entities, null, token, null, strategy == null ? null : List.of(new PhysicalGenerationDto.StrategyChoice(m.member(), strategy)));
    }

    String token(M m, List<Long> entities, String strategy) {
        PhysicalGenerationDto.Plan plan = generation.preview(m.f().user, request(m, entities, null, strategy));
        assertThat(plan.creatable()).as(String.valueOf(plan.tables().stream().flatMap(t -> t.errors().stream()).toList())).isTrue();
        return plan.planToken();
    }

    String token(M m, String strategy) {
        PhysicalGenerationDto.Plan plan = generation.preview(m.f().user, request(m, m.all(), null, strategy));
        assertThat(plan.creatable()).isTrue();
        return plan.planToken();
    }

    Object generate(M m, String token, String strategy) {
        return generation.apply(m.f().user, request(m, m.all(), token, strategy));
    }

    Object changeValue(M m) {
        LogicalSubtypeDto.Group g = subtypes.selectGroup(m.f().user, m.group());
        return subtypes.saveGroup(m.f().user, new LogicalSubtypeDto.Save(m.group(), m.f().modelId, m.member(), g.groupName(), null, false, true, g.classificationAttributeId(),
                List.of(new MemberInput(m.person(), "P2"), new MemberInput(m.corp(), "C")), g.modifiedAt()));
    }

    Object deleteGroup(M m) {
        subtypes.deleteGroup(m.f().user, m.group(), subtypes.selectGroup(m.f().user, m.group()).modifiedAt());
        return null;
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    int tables(M m) {
        return count("select count(*) from TB_TBL where PRJ_SN = ?", m.f().user.getProjectId());
    }

    int records(M m) {
        return count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ?", m.f().user.getProjectId());
    }

    int versions(M m) {
        return count("select count(*) from TB_MDL_VER where PRJ_SN = ? and VER_NM like 'GENERATE-%'", m.f().user.getProjectId());
    }

    // ------------------------------------------------------------------ 실행 도구 (InheritanceConcurrencyIntegrationTest 와 같은 꼴)

    static String outcome(Supplier<Object> work) {
        try {
            work.get();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s && s.getSQLState() != null) return "SQLSTATE:" + s.getSQLState();
                if (t instanceof CustomException c) return c.getErrorCode().name();
            }
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    static void hold() {
        paused.countDown();
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    CompletableFuture<String> async(SecurityContext login, Supplier<Object> work) {
        return CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            try {
                return outcome(work);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    String lockWait() {
        List<String> w = jdbc.queryForList("select l.mode || ' ' || l.locktype || ' :: ' || left(a.query, 160) from pg_locks l join pg_stat_activity a on a.pid = l.pid"
                + " where not l.granted and a.datname = current_database()", String.class);
        return w.isEmpty() ? null : String.join(" / ", w);
    }

    /** [A, B, B 가 기다린 잠금, B 가 A 를 놓기 전에 끝났는지] */
    String[] during(Supplier<Object> a, Supplier<Object> b) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        paused = new CountDownLatch(1);
        release = new CountDownLatch(1);
        CompletableFuture<String> first = async(login, () -> tx.execute(status -> {
            Object result = a.get();
            hold();
            return result;
        }));
        assertThat(paused.await(30, TimeUnit.SECONDS)).as("A 가 대기점에 오지 않았다 : " + (first.isDone() ? first.get() : "")).isTrue();
        CompletableFuture<String> second = async(login, b);
        String waited = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!second.isDone() && System.nanoTime() < until) {
            waited = lockWait();
            if (waited != null) break;
            LockSupport.parkNanos(20_000_000);
        }
        boolean ended = second.isDone();
        release.countDown();
        String[] r = {first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS), waited, ended ? "B-ENDED-WHILE-A-HELD" : "B-AFTER-A"};
        assertThat(r[0]).doesNotStartWith("SQLSTATE:40P01");
        assertThat(r[1]).doesNotStartWith("SQLSTATE:40P01");
        return r;
    }

    // ------------------------------------------------------------------ 시나리오

    @Test
    @DisplayName("AC-C01·C02 : 같은 계층 A형(C형) 생성 두 건 — 둘째는 프로젝트 행을 기다린 뒤 계획 불일치 409. 테이블·기록·GENERATE 버전은 한 번만 (각 3회)")
    void sameGenerationTwice() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (String strategy : List.of(A, C)) {
            for (int i = 0; i < 3; i++) {
                M m = model("c01-" + strategy + i);
                String token = token(m, strategy);
                String[] r = during(() -> generate(m, token, strategy), () -> generate(m, token, strategy));
                System.out.println("[AC-C01/02 " + strategy + "] " + Arrays.toString(r));
                assertThat(r[0]).isEqualTo("OK");
                assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
                assertThat(r[2]).as("둘째는 프로젝트 행을 기다린다").isNotNull();
                assertThat(tables(m)).isEqualTo(strategy.equals(A) ? 1 : 2);
                assertThat(records(m)).isOne();
                assertThat(versions(m)).isOne();
            }
        }
    }

    @Test
    @DisplayName("AC-C06 (예전 번호 C03) : A형 생성 ↔ 분류 값 변경 — 생성 선행이면 변경은 모델 행을 기다린 뒤 성공(생성 뒤 변경은 진단), 변경 선행이면 생성은 NOWAIT 409·아무것도 만들지 않음 (각 3회)")
    void generationVersusClassificationChange() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            M m = model("c03a-" + i);
            String token = token(m, A);
            String[] r = during(() -> generate(m, token, A), () -> changeValue(m));
            System.out.println("[AC-C06 생성 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isIn("OK", "MODEL_CONFLICT");
            assertThat(r[2]).isNotNull();
            assertThat(records(m)).isOne();

            M n = model("c03b-" + i);
            String t2 = token(n, A);
            String[] s = during(() -> changeValue(n), () -> generate(n, t2, A));
            System.out.println("[AC-C06 변경 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(tables(n)).isZero();
            assertThat(records(n)).isZero();
            assertThat(versions(n)).isZero();
        }
    }

    @Test
    @DisplayName("AC-C04 (그룹 삭제) : C형 생성 ↔ 상속 그룹 삭제 — 생성 선행이면 삭제는 기다린 뒤 기록 때문에 거절(RELATION_EXISTS), 삭제 선행이면 생성은 409. 기록 없는 그룹·그룹 없는 기록 없음 (각 3회)")
    void generationVersusGroupDelete() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            M m = model("c04a-" + i);
            String token = token(m, C);
            String[] r = during(() -> generate(m, token, C), () -> deleteGroup(m));
            System.out.println("[AC-C04 생성 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("RELATION_EXISTS");
            assertThat(records(m)).isOne();
            assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where LGCL_SBTYP_GROUP_SN = ?", m.group())).isOne();

            M n = model("c04b-" + i);
            String t2 = token(n, C);
            String[] s = during(() -> deleteGroup(n), () -> generate(n, t2, C));
            System.out.println("[AC-C04 삭제 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(tables(n)).isZero();
            assertThat(records(n)).isZero();
        }
    }

    @Test
    @DisplayName("AC-X01 (예전 번호 C05, 추가 시나리오) : A형 생성 ↔ 서브타입만 B형으로 생성(혼합) — 둘째는 기다린 뒤 거절, 서브타입 자기 테이블 없음·전략 섞임 없음 (각 3회)")
    void generationVersusMixedStrategy() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            M m = model("c05-" + i);
            String token = token(m, A);
            PhysicalGenerationDto.Plan b = generation.preview(m.f().user, request(m, List.of(m.person()), null, null));
            String[] r = during(() -> generate(m, token, A), () -> generation.apply(m.f().user, request(m, List.of(m.person()), b.planToken(), null)));
            System.out.println("[AC-X01] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isIn("MODEL_CONFLICT", "INVALID_PHYSICAL_MODEL");
            assertThat(tables(m)).isOne();
            assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", m.person())).isZero();
        }
    }

    @Test
    @DisplayName("AC-X02 (예전 번호 C06, 추가 시나리오) : C형 같은 상속 일반 속성 매핑 등록 두 건 — 둘째는 매핑 행을 기다린 뒤 SAVED_DATA_EXISTS, 한 행만 (3회)")
    void sameInheritedAttributeMappingTwice() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            M m = model("c06-" + i);
            generate(m, token(m, C), C);
            long mapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, m.person());
            var row = jdbc.queryForMap("select SBTYP_ATRB_COL_MPNG_SN, LGCL_ATRB_SN, COL_SN from TB_SBTYP_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ? order by SBTYP_ATRB_COL_MPNG_SN"
                    + " fetch first 1 rows only", mapping);
            mappings.deleteSubtypeAttributeMapping(m.f().user, ((Number) row.get("SBTYP_ATRB_COL_MPNG_SN")).longValue());
            var request = new ModelMappingDto.SubtypeAttributeMapping(null, mapping, ((Number) row.get("LGCL_ATRB_SN")).longValue(), ((Number) row.get("COL_SN")).longValue());
            String[] r = during(() -> mappings.saveSubtypeAttributeMapping(m.f().user, request), () -> mappings.saveSubtypeAttributeMapping(m.f().user, request));
            System.out.println("[AC-X02] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("SAVED_DATA_EXISTS");
            assertThat(r[2]).isNotNull();
            assertThat(count("select count(*) from TB_SBTYP_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ? and LGCL_ATRB_SN = ?", mapping, row.get("LGCL_ATRB_SN"))).isOne();
        }
    }

    @Test
    @DisplayName("AC-X03 (예전 번호 C07, 추가 시나리오) : 변환 기록 삭제 두 건(관련 매핑을 모두 해제한 뒤) — 둘째는 모델 행을 기다린 뒤 없음(404 계열). 기록 0 (3회)")
    void sameConversionDeleteTwice() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            M m = model("c07-" + i);
            generate(m, token(m, A), A);
            long mapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, m.member());
            for (Long id : jdbc.queryForList("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mapping)) mappings.deleteAttributeMapping(m.f().user, id);
            mappings.deleteEntityMapping(m.f().user, mapping);
            String[] r = during(() -> mappings.deleteConversions(m.f().user, m.member()), () -> mappings.deleteConversions(m.f().user, m.member()));
            System.out.println("[AC-X03] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isNotEqualTo("OK").doesNotStartWith("SQLSTATE");
            assertThat(r[2]).isNotNull();
            assertThat(records(m)).isZero();
        }
    }

    @Test
    @DisplayName("AC-C15 (예전 번호 C08) : 변환 기록 삭제 ↔ 통합 엔터티의 속성 매핑 재등록 — 재등록 선행이면 삭제는 기다린 뒤 RELATION_EXISTS, 삭제 선행이면 재등록은 기다린 뒤 거절(기록 없는 통합 속성 매핑 없음) (각 3회)")
    void conversionDeleteVersusMergedMapping() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            for (boolean deleteFirst : List.of(false, true)) {
                M m = model("c08-" + deleteFirst + i);
                generate(m, token(m, A), A);
                long mapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, m.member());
                long integrated = m.f().table(m.member());
                // 서브타입 속성(생년월일) 매핑 하나만 남기고 해제 → 그것도 해제해 두고 재등록을 경합시킨다. 루트 매핑은 남긴다 (통합 속성 매핑의 부모)
                var merged = jdbc.queryForMap("select a.ATRB_COL_MPNG_SN, a.LGCL_ATRB_SN, a.COL_SN from TB_ATRB_COL_MPNG a join TB_LGCL_ATRB t on t.LGCL_ATRB_SN = a.LGCL_ATRB_SN"
                        + " where a.ENTT_TBL_MPNG_SN = ? and t.LGCL_ENTT_SN = ?", mapping, m.person());
                for (Long id : jdbc.queryForList("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mapping)) mappings.deleteAttributeMapping(m.f().user, id);
                mappings.deleteEntityMapping(m.f().user, mapping);
                long attribute = ((Number) merged.get("LGCL_ATRB_SN")).longValue(), column = ((Number) merged.get("COL_SN")).longValue();
                if (!deleteFirst) {
                    // 재등록 선행 : 루트 매핑을 다시 만들고(테이블 그대로) 통합 속성 매핑을 잡은 채 기록 삭제 — 삭제는 기다린 뒤 관련 매핑 때문에 거절
                    long root = mappings.saveEntityMapping(m.f().user, new ModelMappingDto.EntityMapping(null, m.member(), integrated)).id();
                    String[] r = during(() -> mappings.saveAttributeMapping(m.f().user, new ModelMappingDto.AttributeMapping(null, root, attribute, column)),
                            () -> mappings.deleteConversions(m.f().user, m.member()));
                    System.out.println("[AC-C15 재등록 선행] " + Arrays.toString(r));
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isEqualTo("RELATION_EXISTS");
                    assertThat(r[2]).isNotNull();
                    assertThat(records(m)).isOne();
                } else {
                    // 삭제 선행 : 매핑이 모두 해제된 상태에서 기록 삭제를 잡은 채 루트 재매핑 + 통합 속성 매핑 — 기록이 없어졌으므로 통합 속성 매핑은 거절
                    String[] r = during(() -> mappings.deleteConversions(m.f().user, m.member()), () -> {
                        long root = mappings.saveEntityMapping(m.f().user, new ModelMappingDto.EntityMapping(null, m.member(), integrated)).id();
                        return mappings.saveAttributeMapping(m.f().user, new ModelMappingDto.AttributeMapping(null, root, attribute, column));
                    });
                    System.out.println("[AC-C15 삭제 선행] " + Arrays.toString(r));
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isNotEqualTo("OK").doesNotStartWith("SQLSTATE");
                    assertThat(r[2]).isNotNull();
                    assertThat(records(m)).isZero();
                    assertThat(count("select count(*) from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", attribute)).as("기록 없는 통합 속성 매핑 없음").isZero();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 후속 보완 (2026-11-23) : AC-C03·C04(변경)·C05·C07~C14·C16~C18

    @Autowired com.lexorma.data.logical.service.LogicalModelService logical;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired com.lexorma.data.table.service.TableDesignService tableDesign;
    @Autowired com.lexorma.data.physical.service.PhysicalModelService physicalModel;
    @Autowired com.lexorma.data.column.service.ColumnManageService columnService;
    @Autowired com.lexorma.data.mapping.service.InheritanceForeignKeyCorrectionService inheritanceCorrection;

    Object saveGroup(M m, Boolean total, List<MemberInput> members, Long classification) {
        LogicalSubtypeDto.Group g = subtypes.selectGroup(m.f().user, m.group());
        return subtypes.saveGroup(m.f().user, new LogicalSubtypeDto.Save(m.group(), m.f().modelId, m.member(), g.groupName(), null, g.overlapping(),
                total == null ? g.total() : total, classification == null ? g.classificationAttributeId() : classification, members, g.modifiedAt()));
    }

    List<MemberInput> members(M m, long... extra) {
        List<MemberInput> list = new java.util.ArrayList<>(List.of(new MemberInput(m.person(), "P"), new MemberInput(m.corp(), "C")));
        for (int i = 0; i < extra.length; i++) list.add(new MemberInput(extra[i], "X" + i));
        return list;
    }

    com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier identifier(M m, long entity, boolean primary) {
        return logical.selectEntity(m.f().user, entity).identifiers().stream().filter(i -> Boolean.TRUE.equals(i.isPrimary()) == primary).findFirst().orElseThrow();
    }

    Object saveKey(M m, long entity, boolean primary, List<Long> attributes) {
        var i = identifier(m, entity, primary);
        return logical.saveIdentifier(m.f().user, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(i.id(), entity, i.identifierName(), primary, attributes, null, i.modifiedAt()));
    }

    Object deleteKey(M m, long entity, boolean primary) {
        logical.deleteIdentifier(m.f().user, identifier(m, entity, primary).id());
        return null;
    }

    int tablesNamed(M m, String name) {
        return count("select count(*) from TB_TBL where PRJ_SN = ? and TBL_NM = ?", m.f().user.getProjectId(), name);
    }

    /** 생성과 무관한 기존 물리 테이블 (테이블 등록 경로) */
    long existingTable(M m, String korName, String name) {
        tableDesign.saveTable(com.lexorma.data.table.domain.dto.TableInfoDto.builder().id(0L).tableKorName(korName).tableName(name).tableDescription(korName).isMasterTable("N").sortOrder(1)
                .build(), m.f().user);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) entityManager.flush();
        long id = jdbc.queryForObject("select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM = ?", Long.class, m.f().user.getProjectId(), name);
        // 등록 경로는 프로젝트 설정에 따라 승인 상태로 만든다 — 이름 변경 경합(AC-C12)을 위해 미승인으로 둔다 (승인된 테이블은 이름을 바꿀 수 없다 : 기존 규칙)
        jdbc.update("update TB_TBL set APRV_YN = false where TBL_SN = ?", id);
        return id;
    }

    Object renameTable(M m, long table, String name) {
        com.lexorma.data.table.domain.dto.TableInfoDto dto = new com.lexorma.data.table.domain.dto.TableInfoDto();
        dto.setId(table);
        dto.setTableName(name);
        dto.setTableDescription(name);
        dto.setIsMasterTable("N");
        dto.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", java.sql.Timestamp.class, table).toLocalDateTime());
        tableDesign.updateTable(dto, m.f().user);
        return null;
    }

    /** 두 방향 : forward = 생성 선행(A 생성을 잡은 채 B 작업), reverse = 작업 선행(작업을 잡은 채 생성). 각 방향의 결과를 검사기에 넘긴다 */
    interface Check {
        void accept(M m, String[] r) throws Exception;
    }

    void bothWays(String id, String strategy, java.util.function.Consumer<M> prepare, java.util.function.Function<M, Supplier<Object>> work, Check generationFirst, Check workFirst)
            throws Exception {
        bothWays(id, strategy, M::all, prepare, work, generationFirst, workFirst);
    }

    void bothWays(String id, String strategy, java.util.function.Function<M, List<Long>> entities, java.util.function.Consumer<M> prepare,
                  java.util.function.Function<M, Supplier<Object>> work, Check generationFirst, Check workFirst) throws Exception {
        for (int i = 0; i < 3; i++) {
            M m = model(id.toLowerCase() + "f" + i);
            prepare.accept(m);
            String token = token(m, entities.apply(m), strategy);
            String[] r = during(() -> generation.apply(m.f().user, request(m, entities.apply(m), token, strategy)), work.apply(m));
            System.out.println("[" + id + " 생성 선행 " + strategy + "] " + Arrays.toString(r));
            generationFirst.accept(m, r);

            M n = model(id.toLowerCase() + "w" + i);
            prepare.accept(n);
            String t2 = token(n, entities.apply(n), strategy);
            String[] s = during(work.apply(n), () -> generation.apply(n.f().user, request(n, entities.apply(n), t2, strategy)));
            System.out.println("[" + id + " 작업 선행 " + strategy + "] " + Arrays.toString(s));
            workFirst.accept(n, s);
        }
    }

    /** 논리 쓰기(모델 행 잠금) 선행 : 생성은 NOWAIT 409, 아무것도 만들지 않는다 */
    void refusedGeneration(M n, String[] s) {
        assertThat(s[0]).isEqualTo("OK");
        assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(s[3]).as("기다리지 않는다").isEqualTo("B-ENDED-WHILE-A-HELD");
        assertThat(tables(n)).isZero();
        assertThat(records(n)).isZero();
        assertThat(versions(n)).isZero();
    }

    /** 생성 선행 : 논리 쓰기는 모델 행을 기다린 뒤 진행 (결과 기대값), 생성 결과는 한 번만 */
    void generatedOnce(M m, String[] r, String expected, String strategy) {
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo(expected);
        assertThat(r[2]).as("뒤 작업은 잠금을 기다린다").isNotNull();
        assertThat(tables(m)).isEqualTo(strategy.equals(A) ? 1 : 2);
        assertThat(records(m)).isOne();
        assertThat(versions(m)).isOne();
    }

    @Test
    @DisplayName("AC-C03 : 같은 계층 A형·C형 동시 생성 — 둘째는 프로젝트 행을 기다린 뒤 거절(계획 불일치 또는 이미 생성), 전략 하나·테이블 한 벌만 (방향별 3회)")
    void singleVersusConcreteSameTree() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            for (String first : List.of(A, C)) {
                String second = first.equals(A) ? C : A;
                M m = model("c03n-" + first + i);
                String t1 = token(m, first), t2 = token(m, second);
                String[] r = during(() -> generate(m, t1, first), () -> generate(m, t2, second));
                System.out.println("[AC-C03 " + first + " 선행] " + Arrays.toString(r));
                assertThat(r[0]).isEqualTo("OK");
                assertThat(r[1]).isIn("MODEL_CONFLICT", "INVALID_PHYSICAL_MODEL");
                assertThat(r[2]).isNotNull();
                assertThat(tables(m)).isEqualTo(first.equals(A) ? 1 : 2);
                assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ? and CNVR_TYPE_CD = ?", m.f().user.getProjectId(), first)).isOne();
                assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where PRJ_SN = ? and CNVR_TYPE_CD = ?", m.f().user.getProjectId(), second)).isZero();
                assertThat(versions(m)).isOne();
            }
        }
    }

    @Test
    @DisplayName("AC-C04 (그룹 변경) : C형 생성 ↔ 상속 그룹 완전·부분 변경 — 생성 선행이면 변경은 모델 행을 기다린 뒤 성공(물리 자동 변경 없음, 진단 대상), 변경 선행이면 생성 NOWAIT 409 (방향별 3회)")
    void generationVersusGroupChange() throws Exception {
        Assumptions.assumeTrue(postgres());
        bothWays("AC-C04", C, m -> { }, m -> () -> saveGroup(m, false, members(m), null),
                (m, r) -> {
                    generatedOnce(m, r, "OK", C);
                    assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where LGCL_SBTYP_GROUP_SN = ? and TOTL_YN = false", m.group())).isOne();
                },
                (n, s) -> refusedGeneration(n, s));
    }

    @Test
    @DisplayName("AC-C05 : A형 생성 ↔ 상속 구성원 추가·삭제 — 생성 선행이면 구성원 변경은 기다린 뒤 성공(새 구성원 테이블·매핑 자동 생성 없음), 변경 선행이면 생성 409 (방향별 3회)")
    void generationVersusMemberChange() throws Exception {
        Assumptions.assumeTrue(postgres());
        long[] extra = new long[1];
        // 추가
        bothWays("AC-C05 추가", A, m -> extra[0] = entity(m.f(), "특수 회원"), m -> () -> saveGroup(m, null, members(m, extra[0]), null),
                (m, r) -> {
                    generatedOnce(m, r, "OK", A);
                    assertThat(count("select count(*) from TB_LGCL_SBTYP where LGCL_SBTYP_GROUP_SN = ?", m.group())).isEqualTo(3);
                    assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", extra[0])).isZero();
                },
                (n, s) -> refusedGeneration(n, s));
        // 삭제 (세 구성원 계층에서 하나를 뺀다 — 매핑 없는 구성원)
        bothWays("AC-C05 삭제", A, m -> java.util.stream.Stream.concat(m.all().stream(), java.util.stream.Stream.of(extra[0])).toList(), m -> {
                    extra[0] = entity(m.f(), "특수 회원");
                    saveGroup(m, null, members(m, extra[0]), null);
                }, m -> () -> saveGroup(m, null, members(m), null),
                (m, r) -> {
                    generatedOnce(m, r, "OK", A);
                    assertThat(count("select count(*) from TB_LGCL_SBTYP where LGCL_SBTYP_GROUP_SN = ?", m.group())).isEqualTo(2);
                },
                (n, s) -> refusedGeneration(n, s));
    }

    @Test
    @DisplayName("AC-C07 : A형 생성 ↔ 분류 속성 변경·삭제 — 변경은 양방향(생성 선행 : 기다린 뒤 성공, 변경 선행 : 생성 409). 분류 속성 삭제는 정책상 항상 거절이라 '삭제 선행' 경합은 만들 수 없다 — 생성 선행에서 기다린 뒤 거절, 단독 삭제 거절을 확인 (방향별 3회)")
    void generationVersusClassificationAttribute() throws Exception {
        Assumptions.assumeTrue(postgres());
        long[] other = new long[1];
        bothWays("AC-C07 변경", A, m -> other[0] = m.f().attr(m.member(), "회원 구분 코드", true, "MBR_SE_CD"), m -> () -> saveGroup(m, null, members(m), other[0]),
                (m, r) -> {
                    generatedOnce(m, r, "OK", A);
                    assertThat(count("select count(*) from TB_LGCL_SBTYP_GROUP where LGCL_SBTYP_GROUP_SN = ? and CLSF_ATRB_SN = ?", m.group(), other[0])).isOne();
                    assertThat(count("select count(*) from TB_SBTYP_GROUP_CNVR where LGCL_SBTYP_GROUP_SN = ?", m.group())).as("기록 자동 변경 없음").isOne();
                },
                (n, s) -> refusedGeneration(n, s));
        for (int i = 0; i < 3; i++) {
            M m = model("c07d-" + i);
            String token = token(m, A);
            String[] r = during(() -> generate(m, token, A), () -> {
                logical.deleteAttribute(m.f().user, m.type());
                return null;
            });
            System.out.println("[AC-C07 삭제 · 생성 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isNotEqualTo("OK").doesNotStartWith("SQLSTATE");
            assertThat(r[2]).isNotNull();
            assertThat(count("select count(*) from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", m.type())).isOne();
            M n = model("c07e-" + i);
            assertThat(outcome(() -> {
                logical.deleteAttribute(n.f().user, n.type());
                return null;
            })).as("정책상 동시 실행 불가 — 분류 속성 삭제는 단독으로도 거절").isNotEqualTo("OK");
        }
    }

    @Test
    @DisplayName("AC-C08 : A형·C형 생성 ↔ 루트 PK 변경·삭제 — 생성 선행이면 PK 변경은 기다린 뒤 성공(물리 PK 자동 변경 없음), PK 변경 선행이면 생성 409 (PK 없는 통합 테이블을 만들지 않는다) (방향별 3회)")
    void generationVersusRootKey() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (String strategy : List.of(A, C)) {
            bothWays("AC-C08 변경", strategy, m -> { }, m -> () -> saveKey(m, m.member(), true, List.of(m.memberNo(), m.memberName())),
                    (m, r) -> {
                        generatedOnce(m, r, "OK", strategy);
                        assertThat(count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ? and c.PK_YN = true and c.COL_NM = 'MBR_NM'",
                                m.f().user.getProjectId())).as("물리 PK 자동 변경 없음").isZero();
                    },
                    (n, s) -> refusedGeneration(n, s));
            bothWays("AC-C08 삭제", strategy, m -> { }, m -> () -> deleteKey(m, m.member(), true),
                    (m, r) -> {
                        assertThat(r[0]).isEqualTo("OK");
                        assertThat(r[1]).isIn("OK", "RELATION_EXISTS", "INVALID_LOGICAL_MODEL");
                        assertThat(r[2]).isNotNull();
                        assertThat(records(m)).isOne();
                        assertThat(count("select count(*) from TB_COL c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ? and c.PK_YN = true", m.f().user.getProjectId()))
                                .as("생성된 PK 는 그대로").isEqualTo(strategy.equals(A) ? 1 : 2);
                    },
                    (n, s) -> {
                        refusedGeneration(n, s);
                        assertThat(generation.preview(n.f().user, request(n, n.all(), null, strategy)).creatable()).as("PK 가 없어진 뒤에는 생성 불가 (AC-P02)").isFalse();
                    });
        }
    }

    @Test
    @DisplayName("AC-C09 : A형 생성 ↔ 자체 AK 변경·삭제 — 생성 선행이면 AK 변경은 기다린 뒤 성공(UNIQUE 자동 변경 없음), AK 변경 선행이면 생성 409 (방향별 3회)")
    void generationVersusAlternateKey() throws Exception {
        Assumptions.assumeTrue(postgres());
        String unique = "select count(*) from TB_TBL_CNSTRT c join TB_TBL t on t.TBL_SN = c.TBL_SN where t.PRJ_SN = ? and c.CNSTRT_TYPE_CD = 'UNIQUE'";
        bothWays("AC-C09 변경", A, m -> { }, m -> () -> saveKey(m, m.corp(), false, List.of(m.bizNo(), m.corpName())),
                (m, r) -> {
                    generatedOnce(m, r, "OK", A);
                    assertThat(count(unique, m.f().user.getProjectId())).isOne();
                    assertThat(count("select count(*) from TB_TBL_CNSTRT_COL k join TB_TBL_CNSTRT c on c.CNSTRT_SN = k.CNSTRT_SN join TB_TBL t on t.TBL_SN = c.TBL_SN"
                            + " where t.PRJ_SN = ? and c.CNSTRT_TYPE_CD = 'UNIQUE'", m.f().user.getProjectId())).as("UNIQUE 구성 자동 변경 없음").isOne();
                },
                (n, s) -> refusedGeneration(n, s));
        bothWays("AC-C09 삭제", A, m -> { }, m -> () -> deleteKey(m, m.corp(), false),
                (m, r) -> {
                    generatedOnce(m, r, "OK", A);
                    assertThat(count(unique, m.f().user.getProjectId())).as("UNIQUE 자동 삭제 없음").isOne();
                },
                (n, s) -> refusedGeneration(n, s));
    }

    @Test
    @DisplayName("AC-C10 : A형 생성 ↔ 구성원 엔터티 매핑 등록 — 생성 선행이면 매핑은 모델 행을 기다린 뒤 거절(통합 서브타입은 따로 매핑할 수 없다), 매핑 선행이면 생성 409 (방향별 3회)")
    void generationVersusEntityMapping() throws Exception {
        Assumptions.assumeTrue(postgres());
        long[] other = new long[1];
        bothWays("AC-C10", A, m -> other[0] = existingTable(m, "고객", "CUST"),
                m -> () -> mappings.saveEntityMapping(m.f().user, new ModelMappingDto.EntityMapping(null, m.person(), other[0])),
                (m, r) -> {
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isEqualTo("INVALID_MODEL_MAPPING");
                    assertThat(r[2]).isNotNull();
                    assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", m.person())).isZero();
                    assertThat(records(m)).isOne();
                },
                (n, s) -> {
                    assertThat(s[0]).isEqualTo("OK");
                    assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
                    assertThat(records(n)).isZero();
                    assertThat(count("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", n.person())).isOne();
                });
    }

    @Test
    @DisplayName("AC-C11 : A형 생성 ↔ 같은 물리명(MBR) 테이블 등록 — 어느 쪽이 먼저든 MBR 은 하나, 뒤 작업은 기다리거나 409·이름 충돌로 거절 (방향별 3회)")
    void generationVersusSameNameTable() throws Exception {
        Assumptions.assumeTrue(postgres());
        bothWays("AC-C11", A, m -> { }, m -> () -> existingTable(m, "회원", "MBR"),
                (m, r) -> {
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isIn("SAVED_DATA_EXISTS", "MODEL_CONFLICT");
                    assertThat(tablesNamed(m, "MBR")).isOne();
                    assertThat(records(m)).isOne();
                },
                (n, s) -> {
                    assertThat(s[0]).isEqualTo("OK");
                    assertThat(s[1]).isIn("MODEL_CONFLICT", "INVALID_PHYSICAL_MODEL");
                    assertThat(tablesNamed(n, "MBR")).isOne();
                    assertThat(records(n)).isZero();
                    assertThat(versions(n)).isZero();
                });
    }

    @Test
    @DisplayName("AC-C12 : A형 생성 ↔ 다른 테이블을 생성 예정 물리명(MBR)으로 이름 변경 — 둘 다 프로젝트 행에서 직렬화, MBR 은 하나, 뒤 작업은 바뀐 상태를 보고 거절 (방향별 3회)")
    void generationVersusRename() throws Exception {
        Assumptions.assumeTrue(postgres());
        long[] other = new long[1];
        bothWays("AC-C12", A, m -> other[0] = existingTable(m, "고객", "CUST"), m -> () -> renameTable(m, other[0], "MBR"),
                (m, r) -> {
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isEqualTo("SAVED_DATA_EXISTS");
                    assertThat(r[2]).as("이름 변경은 프로젝트 행을 기다린다").isNotNull();
                    assertThat(tablesNamed(m, "MBR")).isOne();
                    assertThat(tablesNamed(m, "CUST")).isOne();
                },
                (n, s) -> {
                    assertThat(s[0]).isEqualTo("OK");
                    assertThat(s[1]).isIn("MODEL_CONFLICT", "INVALID_PHYSICAL_MODEL");
                    assertThat(s[2]).as("생성은 프로젝트 행을 기다린다").isNotNull();
                    assertThat(tablesNamed(n, "MBR")).isOne();
                    assertThat(records(n)).isZero();
                });
    }

    @Test
    @DisplayName("AC-C13 : A형 생성(기존 부모 UNIQUE 를 참조하는 FK) ↔ 그 UNIQUE 삭제 — 생성 선행이면 삭제는 부모 테이블 행을 기다린 뒤 성공(FK 는 남고 FK_KEY_INVALID 진단 — DEC-09), 삭제 선행이면 생성 NOWAIT 409 (방향별 3회)")
    void generationVersusReferencedUnique() throws Exception {
        Assumptions.assumeTrue(postgres());
        long[] unique = new long[1];
        long[] branchTable = new long[1];
        bothWays("AC-C13", A, m -> {
                    // 지점(PK 지점 아이디, AK 지점 코드 값) 을 먼저 B형으로 만들어 UNIQUE 를 둔다. 회원.지점 코드 → 지점.지점 코드 값 (AK 참조)
                    long branch = entity(m.f(), "지점");
                    m.f().pk(branch, m.f().attr(branch, "지점 아이디", true, "BRNCH_ID"));
                    long code = m.f().attr(branch, "지점 코드 값", true, "BRNCH_CD_VL");
                    logical.saveIdentifier(m.f().user, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(null, branch, "지점 코드 식별자", false, List.of(code), null));
                    var b = generation.preview(m.f().user, new PhysicalGenerationDto.Request(List.of(branch), null, null));
                    generation.apply(m.f().user, new PhysicalGenerationDto.Request(List.of(branch), null, b.planToken()));
                    branchTable[0] = m.f().table(branch);
                    unique[0] = jdbc.queryForObject("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Long.class, branchTable[0]);
                    long fk = m.f().attr(m.member(), "지점 코드", false, "BRNCH_CD_VL");
                    m.f().pairs(m.f().rel("지점 소속", branch, m.member(), false, 0), new long[]{code, fk});
                }, m -> () -> {
                    physicalModel.delete(m.f().user, com.lexorma.data.physical.service.PhysicalModelService.Kind.CONSTRAINT, unique[0]);
                    return null;
                },
                (m, r) -> {
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isEqualTo("OK");
                    assertThat(r[2]).as("삭제는 부모 테이블 행을 기다린다").isNotNull();
                    assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", m.f().table(m.member()), branchTable[0])).as("FK 는 남는다").isOne();
                    assertThat(count("select count(*) from TB_TBL_CNSTRT where CNSTRT_SN = ?", unique[0])).isZero();
                    assertThat(records(m)).isOne();
                },
                (n, s) -> {
                    assertThat(s[0]).isEqualTo("OK");
                    assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
                    assertThat(s[3]).isEqualTo("B-ENDED-WHILE-A-HELD");
                    assertThat(records(n)).isZero();
                    assertThat(count("select count(*) from TB_TBL where PRJ_SN = ?", n.f().user.getProjectId())).as("지점 테이블만").isOne();
                });
    }

    @Test
    @DisplayName("AC-C14 : A형 생성 ↔ 같은 프로젝트의 다른 B형 계층 상속 FK 보정 — 둘 다 프로젝트 배타라 직렬화(뒤 작업이 프로젝트 행을 기다린다), 교착 없이 둘 다 성공 (방향별 3회)")
    void generationVersusInheritanceCorrection() throws Exception {
        Assumptions.assumeTrue(postgres());
        long[] sub = new long[1];
        java.util.function.Function<M, Supplier<Object>> correct = m -> () -> {
            var plan = inheritanceCorrection.preview(m.f().user, new com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Request(m.f().modelId, List.of(sub[0]), null));
            return inheritanceCorrection.apply(m.f().user, new com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Request(m.f().modelId, List.of(sub[0]), plan.planToken()));
        };
        bothWays("AC-C14", A, m -> {
                    // B형 계층 : 당사자(PK) → 조직 · 사람 — 생성 뒤 조직 → 당사자 상속 FK 를 지워 보정 대상으로
                    long party = entity(m.f(), "당사자");
                    m.f().pk(party, m.f().attr(party, "당사자 번호", true, "PTY_NO"));
                    long org = entity(m.f(), "조직");
                    long person = entity(m.f(), "사람");
                    subtypes.saveGroup(m.f().user, new LogicalSubtypeDto.Save(null, m.f().modelId, party, "당사자 구분", null, true, false, null,
                            List.of(new MemberInput(org, null), new MemberInput(person, null)), null));
                    List<Long> b = List.of(party, org, person);
                    var plan = generation.preview(m.f().user, new PhysicalGenerationDto.Request(b, null, null));
                    generation.apply(m.f().user, new PhysicalGenerationDto.Request(b, null, plan.planToken()));
                    long orgTable = m.f().table(org), partyTable = m.f().table(party);
                    for (Long rel : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, orgTable, partyTable)) {
                        jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
                        jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);
                    }
                    jdbc.update("update TB_COL set RFRNC_TBL_NM = null where TBL_SN = ? and PK_YN = true", orgTable);
                    sub[0] = org;
                }, correct,
                (m, r) -> {
                    assertThat(r[0]).isEqualTo("OK");
                    assertThat(r[1]).isEqualTo("OK");
                    assertThat(r[2]).as("보정은 프로젝트 행을 기다린다").isNotNull();
                    assertThat(records(m)).isOne();
                    assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", m.f().table(sub[0]))).isOne();
                },
                (n, s) -> {
                    assertThat(s[0]).isEqualTo("OK");
                    assertThat(s[1]).isEqualTo("OK");
                    assertThat(s[2]).as("생성은 프로젝트 행을 기다린다").isNotNull();
                    assertThat(records(n)).isOne();
                    assertThat(count("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ?", n.f().table(sub[0]))).isOne();
                });
    }

    @Test
    @DisplayName("AC-C16 : 변환 기록 삭제 ↔ 관련 물리 객체 변경 — 통합 테이블 이름 변경은 양방향 교착 없이 둘 다 성공, 분류 컬럼 삭제는 기록이 보이는 동안 거절 (분류 컬럼 삭제 선행은 정책상 항상 거절이라 경합 불가) (방향별 3회)")
    void conversionDeleteVersusPhysicalChange() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            for (boolean deleteFirst : List.of(true, false)) {
                M m = model("c16-" + deleteFirst + i);
                generate(m, token(m, A), A);
                long table = m.f().table(m.member());
                releaseMappings(m);
                Supplier<Object> delete = () -> mappings.deleteConversions(m.f().user, m.member());
                Supplier<Object> rename = () -> renameTable(m, table, "MBR_NEW");
                String[] r = deleteFirst ? during(delete, rename) : during(rename, delete);
                System.out.println("[AC-C16 이름 변경 · " + (deleteFirst ? "삭제 선행" : "이름 변경 선행") + "] " + Arrays.toString(r));
                assertThat(r[0]).isEqualTo("OK");
                assertThat(r[1]).isEqualTo("OK");
                assertThat(records(m)).isZero();
                assertThat(tablesNamed(m, "MBR_NEW")).isOne();
            }
            M m = model("c16c-" + i);
            generate(m, token(m, A), A);
            long column = jdbc.queryForObject("select CLSF_COL_SN from TB_SBTYP_GROUP_CNVR where LGCL_SBTYP_GROUP_SN = ?", Long.class, m.group());
            releaseMappings(m);
            String[] r = during(() -> mappings.deleteConversions(m.f().user, m.member()), () -> {
                com.lexorma.data.column.domain.dto.ColumnInfoDto dto = new com.lexorma.data.column.domain.dto.ColumnInfoDto();
                dto.setId(column);
                columnService.deleteColumn(m.f().user, List.of(dto));
                return null;
            });
            System.out.println("[AC-C16 분류 컬럼 삭제 · 기록 삭제 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isNotEqualTo("OK").doesNotStartWith("SQLSTATE");
            assertThat(records(m)).isZero();
            assertThat(count("select count(*) from TB_COL where COL_SN = ?", column)).isOne();
        }
    }

    void releaseMappings(M m) {
        long mapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, m.member());
        for (Long id : jdbc.queryForList("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN = ?", Long.class, mapping)) mappings.deleteAttributeMapping(m.f().user, id);
        mappings.deleteEntityMapping(m.f().user, mapping);
    }

    @Test
    @DisplayName("AC-C17 : A형 생성 ↔ 루트 엔터티 승인 — 생성 선행이면 승인은 모델 행을 기다린 뒤 성공(생성된 테이블은 미승인 그대로), 승인 선행이면 생성 409 (방향별 3회)")
    void generationVersusApproval() throws Exception {
        Assumptions.assumeTrue(postgres());
        bothWays("AC-C17", A, m -> { }, m -> () -> {
                    logical.updateEntityApproval(m.f().user, m.member(), true);
                    return null;
                },
                (m, r) -> {
                    generatedOnce(m, r, "OK", A);
                    assertThat(count("select count(*) from TB_TBL where PRJ_SN = ? and APRV_YN = true", m.f().user.getProjectId())).as("승인 우회 없음").isZero();
                    assertThat(count("select count(*) from TB_LGCL_ENTT where LGCL_ENTT_SN = ? and APRV_YN = true", m.member())).isOne();
                },
                (n, s) -> refusedGeneration(n, s));
    }

    @Test
    @DisplayName("AC-C18 : 독립 상속 계층 동시 생성 — 다른 프로젝트는 서로 기다리지 않고 둘 다 성공, 같은 프로젝트의 다른 계층은 프로젝트 행에서 직렬화(기존 DEC-10 계약)된 뒤 둘 다 성공 (3회)")
    void independentTrees() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            M a = model("c18a-" + i);
            M b = model("c18b-" + i);
            String ta = token(a, A), tb = token(b, C);
            String[] r = during(() -> generate(a, ta, A), () -> generate(b, tb, C));
            System.out.println("[AC-C18 다른 프로젝트] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("OK");
            assertThat(r[2]).as("기다리지 않는다").isNull();
            assertThat(r[3]).isEqualTo("B-ENDED-WHILE-A-HELD");

            // 같은 프로젝트·같은 모델의 두 번째 계층 (상품 → 실물·디지털)
            M m = model("c18c-" + i);
            long goods = entity(m.f(), "상품");
            m.f().pk(goods, m.f().attr(goods, "상품 번호", true, "GDS_NO"));
            long kind = m.f().attr(goods, "상품 유형 코드", true, "GDS_TY_CD");
            long real = entity(m.f(), "실물 상품");
            long digital = entity(m.f(), "디지털 상품");
            subtypes.saveGroup(m.f().user, new LogicalSubtypeDto.Save(null, m.f().modelId, goods, "상품 구분", null, false, true, kind,
                    List.of(new MemberInput(real, "R"), new MemberInput(digital, "D")), null));
            List<Long> second = List.of(goods, real, digital);
            String t1 = token(m, A);
            String t2 = generation.preview(m.f().user, new PhysicalGenerationDto.Request(second, null, null, null,
                    List.of(new PhysicalGenerationDto.StrategyChoice(goods, A)))).planToken();
            String[] s = during(() -> generate(m, t1, A), () -> generation.apply(m.f().user, new PhysicalGenerationDto.Request(second, null, t2, null,
                    List.of(new PhysicalGenerationDto.StrategyChoice(goods, A)))));
            System.out.println("[AC-C18 같은 프로젝트 다른 계층] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("OK");
            assertThat(s[2]).as("프로젝트 행에서 기다린다").isNotNull();
            assertThat(records(m)).isEqualTo(2);
            assertThat(tables(m)).isEqualTo(2);
        }
    }
}
