package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Item;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Plan;
import com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto.Request;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.InheritanceForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-INH-03 · D-INH-12 PostgreSQL 동시성 (docs/completed/inheritance-reference-key-m7.md 7장). PostgreSQL 에서만 (H2 는 행 잠금 강도가 다르다 — 건너뛴다).
 * 독립 스레드·트랜잭션·연결로 먼저 시작한 작업 A 를 잠금을 쥔 채 멈춰 세우고(상속 FK 보정은 FK 생성 직전, 다른 작업은 서비스가 끝난 뒤 커밋 전 — 같은 트랜잭션),
 * 작업 B 를 실행해 B 가 실제로 잠금을 기다리는지(pg_stat_activity·pg_locks) 또는 기다리지 않고 409 인지 확인한 뒤 A 를 놓는다. sleep 으로 순서를 정하지 않는다. 시나리오마다 3번 반복한다.
 * 허용 결과 : 둘 다 성공 · 뒤 작업의 업무 거절 · NOWAIT·계획 불일치 409. 허용하지 않는 결과 : 40P01, 같은 FK 중복, 부분 적용.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class InheritanceConcurrencyIntegrationTest {

    static final Map<Thread, Boolean> ARMED = new ConcurrentHashMap<>();
    static volatile CountDownLatch paused;
    static volatile CountDownLatch release;

    @TestConfiguration
    static class Hooks {
        @Bean
        static BeanPostProcessor inheritancePause() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof PhysicalModelService)) return bean;
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), ClassUtils.getAllInterfaces(bean), (proxy, method, args) -> {
                        if (method.getName().equals("createGeneratedForeignKey") && ARMED.remove(Thread.currentThread()) != null) hold();
                        try {
                            return method.invoke(bean, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
                }
            };
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

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired InheritanceForeignKeyCorrectionService correction;
    @Autowired PhysicalModelService physical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired ColumnManageService columns;
    @Autowired TableDesignService tables;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        ARMED.clear();
        if (release != null) release.countDown();
        SecurityTestSupport.clear();
    }

    boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    // ------------------------------------------------------------------ 준비

    record Fx(GenerationFixture f, Map<String, Long> e) {
        long table(String entity) {
            return f.table(e.get(entity));
        }
    }

    Fx generated(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inhc-" + name);
        Map<String, Long> e = new HashMap<>();
        for (String n : List.of("당사자", "조직", "사람", "외국 조직", "기업")) {
            long id = f.entity(n);
            jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
            e.put(n, id);
        }
        f.pk(e.get("당사자"), f.attr(e.get("당사자"), "당사자 번호", true, "PTY_NO"));
        f.attr(e.get("조직"), "조직 명", false, "ORG_NM");
        save(f, e.get("당사자"), "당사자 구분", e.get("조직"), e.get("사람"));
        save(f, e.get("조직"), "조직 구분", e.get("외국 조직"), e.get("기업"));
        List<Long> chosen = new ArrayList<>(e.values());
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        Fx x = new Fx(f, e);
        drop(x, "조직", "당사자");
        return x;
    }

    void save(GenerationFixture f, long supertype, String name, long... members) {
        List<LogicalSubtypeDto.MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new LogicalSubtypeDto.MemberInput(m, null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, true, false, null, list, null));
    }

    void drop(Fx x, String child, String parent) {
        for (Long rel : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK'", Long.class, x.table(child), x.table(parent))) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);
        }
        jdbc.update("update TB_COL set RFRNC_TBL_NM = null where TBL_SN = ? and PK_YN = true", x.table(child));
    }

    long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    String tableName(long table) {
        return jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table);
    }

    /** 자식 → 부모 FK 관계 (출처:승인:쌍) */
    List<String> relations(long child, long parent) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select REL_SN, REL_SRC_CD, APRV_YN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ? and REL_TYPE_CD = 'FK' order by REL_SN",
                child, parent)) {
            List<String> pairs = jdbc.queryForList("select c.COL_NM || '>' || p.COL_NM from TB_TBL_REL_COL rc join TB_COL c on c.COL_SN = rc.ORGNL_COL_SN join TB_COL p on p.COL_SN = rc.TRGT_COL_SN"
                    + " where rc.REL_SN = ? order by rc.COL_ORD", String.class, ((Number) r.get("REL_SN")).longValue());
            out.add(r.get("REL_SRC_CD") + ":" + r.get("APRV_YN") + ":" + String.join(",", pairs));
        }
        return out;
    }

    int versions(Fx x, String prefix) {
        return jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ? and VER_NM like ?", Integer.class, x.f().user.getProjectId(), prefix + "%");
    }

    Object correct(Fx x) {
        Plan plan = correction.preview(x.f().user, new Request(x.f().modelId, List.of(x.e().get("조직")), null));
        return correction.apply(x.f().user, new Request(plan.modelId(), plan.items().stream().map(Item::subtypeEntityId).toList(), plan.planToken()));
    }

    /** 조직 테이블 상속 PK 컬럼(PTY_NO)의 참조 테이블명을 당사자 테이블로 (컬럼 일괄 저장) */
    Object setRefTable(Fx x) {
        long org = x.table("조직");
        Map<String, Object> c = jdbc.queryForMap("select COL_SN, STD_TRM_SN, DATA_TYPE_NM, MAX_LEN, MDFCN_DT from TB_COL where TBL_SN = ? and COL_NM = 'PTY_NO'", org);
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(org);
        request.setRows(new ArrayList<>(List.of(ColumnBulkSaveDto.Row.builder().key("pk").id(((Number) c.get("COL_SN")).longValue()).termId(((Number) c.get("STD_TRM_SN")).longValue())
                .columnDesc("당사자 번호").dataType((String) c.get("DATA_TYPE_NM")).maxLength(((Number) c.get("MAX_LEN")).intValue()).refTableName(tableName(x.table("당사자")))
                .isPk("Y").isNullable("N").baseModifiedAt(((java.sql.Timestamp) c.get("MDFCN_DT")).toLocalDateTime()).build())));
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(x.f().user, request);
        if (!result.result()) throw new IllegalStateException("FAIL:" + result.errors().stream().map(ColumnBulkSaveDto.RowError::message).toList());
        return result;
    }

    /** 컬럼 일괄 저장은 승인된 테이블에만 쓸 수 있다 (기존 규칙) — 경합 전에 준비로 승인해 둔다 */
    void approveTable(long table) {
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", table);
    }

    Object userRelation(Fx x) {
        long org = x.table("조직"), party = x.table("당사자");
        return physical.saveRelation(x.f().user, new PhysicalModelDto.Relation(null, null, "FK", null, org, null, party, null,
                List.of(new PhysicalModelDto.ColumnPair(column(org, "PTY_NO"), null, column(party, "PTY_NO"), null)), null));
    }

    // ------------------------------------------------------------------ 실행 도구

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

    CompletableFuture<String> async(SecurityContext login, boolean arm, Supplier<Object> work) {
        return CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            if (arm) ARMED.put(Thread.currentThread(), true);
            try {
                return outcome(work);
            } finally {
                ARMED.remove(Thread.currentThread());
                SecurityContextHolder.clearContext();
            }
        });
    }

    /** 다른 연결이 기다리는 잠금 (모드 · 문장), 없으면 null */
    String lockWait() {
        List<String> w = jdbc.queryForList("select l.mode || ' ' || l.locktype || ' :: ' || left(a.query, 200) from pg_locks l join pg_stat_activity a on a.pid = l.pid"
                + " where not l.granted and a.datname = current_database()", String.class);
        return w.isEmpty() ? null : String.join(" / ", w);
    }

    /** A 를 상속 FK 생성 직전에 멈추고 B 실행 : [A, B, B 가 기다린 잠금] */
    String[] duringCorrection(Fx x, Supplier<Object> b) throws Exception {
        return paused(true, () -> correct(x), b);
    }

    /** A 를 같은 트랜잭션 안에서 서비스가 끝난 뒤(커밋 전 — 잠금을 쥔 채) 멈추고 B 실행 */
    String[] duringTransaction(Supplier<Object> a, Supplier<Object> b) throws Exception {
        return paused(false, () -> tx.execute(status -> {
            Object result = a.get();
            hold();
            return result;
        }), b);
    }

    String[] paused(boolean arm, Supplier<Object> a, Supplier<Object> b) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        paused = new CountDownLatch(1);
        release = new CountDownLatch(1);
        CompletableFuture<String> first = async(login, arm, a);
        assertThat(paused.await(30, TimeUnit.SECONDS)).as("A 가 대기점에 오지 않았다 : " + (first.isDone() ? first.get() : "")).isTrue();
        CompletableFuture<String> second = async(login, false, b);
        String waited = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!second.isDone() && System.nanoTime() < until) {
            waited = lockWait();
            if (waited != null) break;
            LockSupport.parkNanos(20_000_000);
        }
        boolean secondDoneBeforeRelease = second.isDone();
        release.countDown();
        String[] result = {first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS), waited, secondDoneBeforeRelease ? "B-ENDED-WHILE-A-HELD" : "B-AFTER-A"};
        assertThat(result[0]).doesNotStartWith("SQLSTATE:40P01");
        assertThat(result[1]).doesNotStartWith("SQLSTATE:40P01");
        return result;
    }

    // ------------------------------------------------------------------ 시나리오

    @Test
    @DisplayName("INH-C01 : 같은 서브타입 상속 FK 보정 두 건 — 둘째는 프로젝트 행에서 기다린 뒤 계획 불일치 409, FK 1개·INHFIX 버전 1개 (3회)")
    void sameCorrectionTwice() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c01-" + i);
            Plan plan = correction.preview(x.f().user, new Request(x.f().modelId, List.of(x.e().get("조직")), null));
            String[] r = duringCorrection(x, () -> correction.apply(x.f().user, new Request(x.f().modelId, List.of(x.e().get("조직")), plan.planToken())));
            System.out.println("[INH-C01] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(r[2]).as("둘째는 프로젝트 행 잠금을 기다린다").isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
            assertThat(versions(x, "INHFIX-")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("INH-C02 : 상속 FK 보정 ↔ 같은 쌍의 일반 USER 관계 등록 — 보정 선행이면 등록이 자식 테이블 행에서 기다린 뒤 SAVED_DATA_EXISTS, 등록 선행이면 보정이 NOWAIT 409. FK 1개 (각 3회)")
    void correctionVersusUserRelation() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c02a-" + i);
            String[] r = duringCorrection(x, () -> userRelation(x));
            System.out.println("[INH-C02 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("SAVED_DATA_EXISTS");
            assertThat(r[2]).isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).hasSize(1);

            Fx y = generated("c02b-" + i);
            String[] s = duringTransaction(() -> userRelation(y), () -> correct(y));
            System.out.println("[INH-C02 등록 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(s[3]).as("보정은 기다리지 않는다 (NOWAIT)").isEqualTo("B-ENDED-WHILE-A-HELD");
            assertThat(relations(y.table("조직"), y.table("당사자"))).hasSize(1);
            assertThat(versions(y, "INHFIX-")).isZero();
        }
    }

    @Test
    @DisplayName("D-INH-12 36.2 : 상속 FK 보정 ↔ 같은 자식 테이블의 컬럼 일괄 저장(참조 테이블명) — 보정 선행이면 저장이 자식 테이블 행에서 기다린 뒤 업무 거절, 저장(잠금 보유) 선행이면 보정 NOWAIT 409. 중복 REF_COLUMN 없음 (각 3회)."
            + " 보정 대상 자식 테이블은 미승인이어야 하고 일괄 저장·엑셀 수정은 승인된 테이블에만 쓰므로 같은 컬럼에 둘 다 성공하는 순서는 앱 경로로 만들 수 없다 — 잠금 직렬화만 확인한다")
    void correctionVersusRefColumn() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c03a-" + i);
            String[] r = duringCorrection(x, () -> setRefTable(x));
            System.out.println("[INH-C03 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).startsWith("IllegalStateException:FAIL");
            assertThat(r[2]).as("저장은 자식 테이블 행을 기다린다").isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");

            Fx y = generated("c03b-" + i);
            String[] s = duringTransaction(() -> outcome(() -> setRefTable(y)), () -> correct(y));
            System.out.println("[INH-C03 저장 선행] " + Arrays.toString(s));
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(s[3]).isEqualTo("B-ENDED-WHILE-A-HELD");
            assertThat(relations(y.table("조직"), y.table("당사자"))).isEmpty();
        }
    }

    @Test
    @DisplayName("D-INH-12 36.1 : 일반 USER 관계 등록 ↔ 같은 쌍의 참조 테이블명 저장 — 어느 쪽이 먼저든 뒤 작업이 자식 테이블 행에서 기다린 뒤 거절, 같은 쌍 FK 1개 (각 3회)")
    void userRelationVersusRefColumn() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("u-a-" + i);
            approveTable(x.table("조직"));
            String[] r = duringTransaction(() -> userRelation(x), () -> setRefTable(x));
            System.out.println("[I-6 USER 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("SAVED_DATA_EXISTS");
            assertThat(r[2]).isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).hasSize(1);

            Fx y = generated("u-b-" + i);
            approveTable(y.table("조직"));
            String[] s = duringTransaction(() -> setRefTable(y), () -> userRelation(y));
            System.out.println("[I-6 REF_COLUMN 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("INVALID_PHYSICAL_MODEL"); // 참조 테이블명 컬럼은 관계 등록 불가 (기존 규칙)
            assertThat(s[2]).isNotNull();
            assertThat(relations(y.table("조직"), y.table("당사자"))).containsExactly("REF_COLUMN:true:PTY_NO>PTY_NO");
        }
    }

    @Test
    @DisplayName("INH-C03(그룹)·C08 : 상속 FK 보정 ↔ 상속 그룹 변경·자식 테이블 승인 — 보정 선행이면 뒤 작업이 기다린 뒤 성공, 그룹 변경·승인 선행이면 보정 409 (각 3회)")
    void correctionVersusGroupAndApproval() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c08a-" + i);
            String[] r = duringCorrection(x, () -> approve(x));
            System.out.println("[INH-C08 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("OK");
            assertThat(r[2]).isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");

            Fx y = generated("c08b-" + i);
            String[] s = duringTransaction(() -> approve(y), () -> correct(y));
            System.out.println("[INH-C08 승인 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(relations(y.table("조직"), y.table("당사자"))).isEmpty();

            Fx z = generated("c03g-" + i);
            String[] g = duringCorrection(z, () -> renameGroup(z));
            System.out.println("[INH-C03 보정 선행 · 그룹 변경] " + Arrays.toString(g));
            assertThat(g[0]).isEqualTo("OK");
            assertThat(g[1]).isEqualTo("OK");
            Fx w = generated("c03h-" + i);
            String[] h = duringTransaction(() -> renameGroup(w), () -> correct(w));
            System.out.println("[INH-C03 그룹 변경 선행] " + Arrays.toString(h));
            assertThat(h[0]).isEqualTo("OK");
            assertThat(h[1]).isEqualTo("MODEL_CONFLICT");
        }
    }

    Object approve(Fx x) {
        TableInfoDto dto = new TableInfoDto();
        dto.setId(x.table("조직"));
        tables.updateTableIsApproval(true, x.f().user, List.of(dto));
        return null;
    }

    Object renameGroup(Fx x) {
        Long id = jdbc.queryForObject("select LGCL_SBTYP_GROUP_SN from TB_LGCL_SBTYP_GROUP where LGCL_MDL_SN = ? and SBTYP_GROUP_NM = '당사자 구분'", Long.class, x.f().modelId);
        LogicalSubtypeDto.Group g = subtypes.selectGroup(x.f().user, id);
        return subtypes.saveGroup(x.f().user, new LogicalSubtypeDto.Save(id, x.f().modelId, g.supertypeEntityId(), "당사자 구분 2", null, true, false, null,
                g.subtypes().stream().map(m -> new LogicalSubtypeDto.MemberInput(m.entityId(), null)).toList(), g.modifiedAt()));
    }

    @Test
    @DisplayName("INH-C04~C06 : 상속 FK 보정 ↔ 자식 상속 PK 컬럼 삭제(구조 변경 경로) — 보정 선행이면 삭제가 프로젝트 행에서 기다린 뒤 업무 거절, 삭제(거절되었지만 잠금 보유) 선행이면 보정이 기다린 뒤 성공."
            + " 이 모델의 PK 컬럼은 매핑·관계에 쓰여 삭제가 항상 거절된다 (그 계약을 확인한다). 컬럼·FK 는 그대로, 부분 적용 없음 (각 3회)")
    void correctionVersusPkDelete() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c04a-" + i);
            String[] r = duringCorrection(x, () -> deleteOrgPk(x));
            System.out.println("[INH-C04 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("RELATION_EXISTS");
            assertThat(r[2]).as("삭제는 프로젝트 행을 기다린다").isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
            assertThat(column(x.table("조직"), "PTY_NO")).isPositive();

            Fx y = generated("c04b-" + i);
            String[] s = duringTransaction(() -> outcome(() -> deleteOrgPk(y)), () -> correct(y));
            System.out.println("[INH-C04 삭제 선행] " + Arrays.toString(s));
            assertThat(s[1]).isEqualTo("OK");
            assertThat(s[2]).as("보정은 프로젝트 행을 기다린다 (프로젝트 배타는 NOWAIT 가 아니다)").isNotNull();
            assertThat(relations(y.table("조직"), y.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
            assertThat(column(y.table("조직"), "PTY_NO")).isPositive();
        }
    }

    /** 조직 테이블의 상속 PK 컬럼 삭제 (단일 컬럼 삭제 — PK 삭제는 프로젝트 배타로 시작하는 구조 변경 경로). 상속 PK 매핑·관계에 쓰인 컬럼이라 업무 거절된다 */
    Object deleteOrgPk(Fx x) {
        com.lexorma.data.column.domain.dto.ColumnInfoDto dto = new com.lexorma.data.column.domain.dto.ColumnInfoDto();
        dto.setId(column(x.table("조직"), "PTY_NO"));
        columns.deleteColumn(x.f().user, List.of(dto));
        return null;
    }

    @Test
    @DisplayName("INH-C12 : 서로 다른 프로젝트의 상속 FK 보정·일반 컬럼 저장은 서로 기다리지 않고 둘 다 성공. 같은 프로젝트의 무관한 테이블 저장은 보정이 끝날 때까지 커밋을 기다린 뒤 성공 (구조 변경 경로의 프로젝트 배타 — 기존 계약) (3회)")
    void independentWork() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c12a-" + i);
            Fx other = generated("c12b-" + i);
            String[] r = duringCorrection(x, () -> correct(other));
            System.out.println("[INH-C12 다른 프로젝트] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("OK");
            assertThat(r[3]).isEqualTo("B-ENDED-WHILE-A-HELD");
            Fx y = generated("c12c-" + i);
            approveTable(y.table("사람"));
            String[] s = duringCorrection(y, () -> setRefTableOn(y, "사람"));
            System.out.println("[INH-C12 같은 프로젝트 무관 테이블] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("OK");
        }
    }

    /** 무관한 테이블(사람)의 첫 컬럼 설명을 바꾼다 (참조 테이블명은 지금 값 그대로 — 사람의 상속 PK 는 이미 당사자를 참조한다) */
    Object setRefTableOn(Fx x, String entity) {
        long table = x.table(entity);
        Map<String, Object> c = jdbc.queryForMap("select COL_SN, STD_TRM_SN, DATA_TYPE_NM, MAX_LEN, MDFCN_DT, PK_YN from TB_COL where TBL_SN = ? order by COL_SN fetch first 1 rows only", table);
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(table);
        request.setRows(new ArrayList<>(List.of(ColumnBulkSaveDto.Row.builder().key("c").id(((Number) c.get("COL_SN")).longValue()).termId(((Number) c.get("STD_TRM_SN")).longValue())
                .columnDesc("바꾼 설명").dataType((String) c.get("DATA_TYPE_NM")).maxLength(((Number) c.get("MAX_LEN")).intValue()).refTableName(tableName(x.table("당사자")))
                .isPk(Boolean.TRUE.equals(c.get("PK_YN")) ? "Y" : "N").isNullable("N").baseModifiedAt(((java.sql.Timestamp) c.get("MDFCN_DT")).toLocalDateTime()).build())));
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(x.f().user, request);
        if (!result.result()) throw new IllegalStateException("FAIL:" + result.errors().stream().map(ColumnBulkSaveDto.RowError::message).toList());
        return result;
    }

    @Test
    @DisplayName("H2·PostgreSQL : 위 시나리오의 준비 데이터 (보정 가능, 승인 전 자식 테이블의 일괄 저장은 업무 거절, 승인 뒤 참조 테이블명 저장은 REF_COLUMN 1개) — H2 에서도 돈다")
    void fixtureIsCorrectable() {
        Fx x = generated("fixture");
        assertThat(correction.status(x.f().user, x.f().modelId, List.of(x.e().get("조직"))).get(0).correctable()).isTrue();
        assertThat(outcome(() -> setRefTable(x))).startsWith("IllegalStateException:FAIL");
        approveTable(x.table("조직"));
        assertThat(outcome(() -> setRefTable(x))).isEqualTo("OK");
        assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("REF_COLUMN:true:PTY_NO>PTY_NO");
        assertThat(DbType.POSTGRESQL).isNotNull();
    }

    // ------------------------------------------------------------------ 후속 보완 (2026-11-23) : INH-C05(PK 변경)·C07·C09·C10·C11 실제 경합

    @Autowired com.lexorma.data.mapping.service.ModelMappingService mappingService;
    @Autowired com.lexorma.data.mapping.service.ForeignKeyCorrectionService fkCorrection;
    @Autowired com.lexorma.data.mapping.service.ModelMappingQualityService quality;
    @Autowired com.lexorma.data.logical.service.LogicalModelService logical;

    /** 조직 테이블에 새 PK 컬럼 단건 등록 (PK 집합을 바꾸는 키 변경 — 테이블 행을 먼저 잠근다, M7-D39) */
    Object addOrgPk(Fx x) {
        long org = x.table("조직");
        columns.insertColumn(x.f().user, com.lexorma.data.column.domain.dto.ColumnInfoDto.builder().id(0L).tableInfoId(org).termId(x.f().term("ORG_EXT_NO", "VARCHAR", 20))
                .tableName(tableName(org)).columnName("ORG_EXT_NO").dataType("VARCHAR").columnDesc("조직 추가 번호").maxLength(20).isPk("Y").isNullable("N").isMasterData("N")
                .isRequired("Y").isSensitive("N").isUnique("N").isIndex("N").isEncrypted("N").build());
        return null;
    }

    @Test
    @DisplayName("INH-C05 (PK 변경) : 상속 FK 보정 ↔ 자식 테이블 PK 컬럼 추가 — 보정 선행이면 추가는 자식 테이블 행을 기다린 뒤 진행, 추가 선행이면 보정 NOWAIT 409. FK 중복·부분 적용 없음 (각 3회)")
    void correctionVersusPkChange() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c05p-" + i);
            String[] r = duringCorrection(x, () -> addOrgPk(x));
            System.out.println("[INH-C05 보정 선행 · PK 추가] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).doesNotStartWith("SQLSTATE");
            assertThat(r[2]).as("PK 추가는 기다린다").isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");

            Fx y = generated("c05q-" + i);
            String[] s = duringTransaction(() -> addOrgPk(y), () -> correct(y));
            System.out.println("[INH-C05 PK 추가 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(relations(y.table("조직"), y.table("당사자"))).isEmpty();
        }
    }

    /** 조직의 상속 PK 매핑 해제 */
    Object deleteOrgKeyMapping(Fx x) {
        Long id = jdbc.queryForObject("select k.SBTYP_IDNTFR_COL_MPNG_SN from TB_SBTYP_IDNTFR_COL_MPNG k join TB_ENTT_TBL_MPNG m on m.ENTT_TBL_MPNG_SN = k.ENTT_TBL_MPNG_SN"
                + " where m.LGCL_ENTT_SN = ?", Long.class, x.e().get("조직"));
        mappingService.deleteSubtypeKeyMapping(x.f().user, id);
        return null;
    }

    @Test
    @DisplayName("INH-C07 : 상속 FK 보정 ↔ 자식의 상속 PK 매핑 해제 — 보정 선행이면 해제는 모델 행을 기다린 뒤 진행, 해제 선행이면 보정 409 (보정이 본 매핑이 커밋 전에 바뀌지 않는다) (각 3회)")
    void correctionVersusKeyMapping() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c07a-" + i);
            String[] r = duringCorrection(x, () -> deleteOrgKeyMapping(x));
            System.out.println("[INH-C07 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).doesNotStartWith("SQLSTATE");
            assertThat(r[2]).isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");

            Fx y = generated("c07b-" + i);
            String[] s = duringTransaction(() -> deleteOrgKeyMapping(y), () -> correct(y));
            System.out.println("[INH-C07 해제 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("MODEL_CONFLICT");
            assertThat(relations(y.table("조직"), y.table("당사자"))).isEmpty();
        }
    }

    Object renameParty(Fx x, String name) {
        long party = x.table("당사자");
        TableInfoDto dto = new TableInfoDto();
        dto.setId(party);
        dto.setTableName(name);
        dto.setTableDescription(name);
        dto.setIsMasterTable("N");
        dto.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", java.sql.Timestamp.class, party).toLocalDateTime());
        tables.updateTable(dto, x.f().user);
        return null;
    }

    @Test
    @DisplayName("INH-C09 : 상속 FK 보정 ↔ 부모 테이블 이름 변경 — 둘 다 프로젝트 배타라 직렬화(뒤 작업이 프로젝트 행을 기다린다), 교착 없음, FK 는 하나·이름 변경 반영 (각 3회)")
    void correctionVersusRename() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c09a-" + i);
            String[] r = duringCorrection(x, () -> renameParty(x, "PTY_NEW"));
            System.out.println("[INH-C09 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("OK");
            assertThat(r[2]).as("이름 변경은 프로젝트 행을 기다린다").isNotNull().containsIgnoringCase("tb_prj");
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
            assertThat(tableName(x.table("당사자"))).isEqualTo("PTY_NEW");

            Fx y = generated("c09b-" + i);
            String[] s = duringTransaction(() -> renameParty(y, "PTY_NEW"), () -> correct(y));
            System.out.println("[INH-C09 이름 변경 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isIn("OK", "MODEL_CONFLICT");
            assertThat(s[2]).as("보정은 프로젝트 행을 기다린다").isNotNull();
            assertThat(relations(y.table("조직"), y.table("당사자"))).hasSizeLessThanOrEqualTo(1);
            assertThat(tableName(y.table("당사자"))).isEqualTo("PTY_NEW");
        }
    }

    /** 같은 모델의 무관한 엔터티(고객)를 새로 물리 생성 */
    Object generateOther(Fx x, long entity) {
        var plan = generation.preview(x.f().user, x.f().request(List.of(entity)));
        return generation.apply(x.f().user, x.f().request(List.of(entity), plan.planToken()));
    }

    long otherEntity(Fx x) {
        long id = x.f().entity("고객");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        x.f().pk(id, x.f().attr(id, "고객 번호", true, "CUST_NO"));
        return id;
    }

    @Test
    @DisplayName("INH-C10 : 상속 FK 보정 ↔ 같은 모델 다른 엔터티의 신규 물리 생성 — 둘 다 프로젝트 배타라 직렬화, 교착 없이 둘 다 성공, 테이블·FK 중복 없음 (각 3회)")
    void correctionVersusGeneration() throws Exception {
        Assumptions.assumeTrue(postgres());
        for (int i = 0; i < 3; i++) {
            Fx x = generated("c10a-" + i);
            long cust = otherEntity(x);
            String[] r = duringCorrection(x, () -> generateOther(x, cust));
            System.out.println("[INH-C10 보정 선행] " + Arrays.toString(r));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("OK");
            assertThat(r[2]).isNotNull();
            assertThat(relations(x.table("조직"), x.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
            assertThat(jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Integer.class, cust)).isOne();

            Fx y = generated("c10b-" + i);
            long c2 = otherEntity(y);
            var plan = generation.preview(y.f().user, y.f().request(List.of(c2)));
            String[] s = duringTransaction(() -> generation.apply(y.f().user, y.f().request(List.of(c2), plan.planToken())), () -> correct(y));
            System.out.println("[INH-C10 생성 선행] " + Arrays.toString(s));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isEqualTo("OK");
            assertThat(s[2]).isNotNull();
            assertThat(relations(y.table("조직"), y.table("당사자"))).containsExactly("USER:false:PTY_NO>PTY_NO");
        }
    }

    /** INH-C11 준비 : 고객(PK 고객 번호, AK 고객 코드) → 주문(주문 고객 코드) 관계가 고객의 자체 AK 를 참조. 생성 뒤 FK 를 지워 누락 FK 보정 대상으로 */
    record Ak(GenerationFixture f, long parent, long child, long relationship, long unique) {
    }

    Ak akFixture(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "inhc-" + name);
        long cust = f.entity("고객");
        long ord = f.entity("주문");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN in (?, ?)", cust, ord);
        f.pk(cust, f.attr(cust, "고객 번호", true, "CUST_NO"));
        long code = f.attr(cust, "고객 코드", true, "CUST_CD");
        logical.saveIdentifier(f.user, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(null, cust, "고객 코드 식별자", false, List.of(code), null));
        f.pk(ord, f.attr(ord, "주문 번호", true, "ORD_NO"));
        long ordCust = f.attr(ord, "주문 고객 코드", false, "ORD_CUST_CD");
        var rel = f.rel("고객 주문", cust, ord, false, 0);
        f.pairs(rel, new long[]{code, ordCust});
        var plan = generation.preview(f.user, f.request(List.of(cust, ord)));
        generation.apply(f.user, f.request(List.of(cust, ord), plan.planToken()));
        long parentTable = f.table(cust), childTable = f.table(ord);
        for (Long r : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, childTable, parentTable)) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", r);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", r);
        }
        long unique = jdbc.queryForObject("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Long.class, parentTable);
        return new Ak(f, parentTable, childTable, rel.id(), unique);
    }

    Object correctAk(Ak a) {
        var plan = fkCorrection.preview(a.f().user, new com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Request(a.f().modelId, List.of(a.relationship()), null));
        return fkCorrection.apply(a.f().user, new com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto.Request(a.f().modelId, List.of(a.relationship()), plan.planToken()));
    }

    Object deleteUnique(Ak a) {
        physical.delete(a.f().user, PhysicalModelService.Kind.CONSTRAINT, a.unique());
        return null;
    }

    int fks(Ak a) {
        return jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Integer.class, a.child(), a.parent());
    }

    String relationshipStatus(Ak a) {
        return quality.selectRows(a.f().user, new com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search(a.f().modelId, null, null, null, null, null,
                        com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup.RELATIONSHIP), org.springframework.data.domain.PageRequest.of(0, 100)).getContent().stream()
                .filter(r -> r.structure().logicalId().equals(a.relationship())).map(r -> r.status() + "/" + r.structure().code()).findFirst().orElse("-");
    }

    @Test
    @DisplayName("INH-C11 : 자체 AK 를 참조하는 누락 FK 보정 ↔ 그 참조 UNIQUE 삭제 — 둘 중 하나가 반드시 실패한다고 가정하지 않는다. 보정 선행이면 삭제는 부모 테이블 행을 기다린 뒤 성공 → FK 는 남고 키 무효로 진단(DEC-09, 자동 삭제 없음), 삭제 선행이면 보정 NOWAIT 409·FK 없음 (각 3회)")
    void akCorrectionVersusUniqueDelete() throws Exception {
        Assumptions.assumeTrue(postgres());
        Ak probe = akFixture("c11-probe");
        assertThat(fkCorrection.status(probe.f().user, probe.f().modelId, List.of(probe.relationship())).get(0).correctable()).as("준비 : 보정 가능").isTrue();
        for (int i = 0; i < 3; i++) {
            Ak a = akFixture("c11a-" + i);
            String[] r = paused(true, () -> correctAk(a), () -> deleteUnique(a));
            System.out.println("[INH-C11 보정 선행] " + Arrays.toString(r) + " 진단=" + relationshipStatus(a));
            assertThat(r[0]).isEqualTo("OK");
            assertThat(r[1]).isEqualTo("OK");
            assertThat(r[2]).as("삭제는 부모 테이블 행을 기다린다").isNotNull();
            assertThat(fks(a)).isOne();
            assertThat(jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where CNSTRT_SN = ?", Integer.class, a.unique())).isZero();
            assertThat(relationshipStatus(a)).as("키가 사라진 FK 는 일치로 보지 않는다").doesNotStartWith("MATCH");

            Ak b = akFixture("c11b-" + i);
            String[] s = duringTransaction(() -> deleteUnique(b), () -> correctAk(b));
            System.out.println("[INH-C11 삭제 선행] " + Arrays.toString(s) + " 진단=" + relationshipStatus(b));
            assertThat(s[0]).isEqualTo("OK");
            assertThat(s[1]).isNotEqualTo("OK").doesNotStartWith("SQLSTATE");
            assertThat(fks(b)).isZero();
        }
    }
}
