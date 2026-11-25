package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.UniqueCorrectionDto;
import com.lexorma.data.mapping.service.ForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.UniqueCorrectionService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-AK-D15 동시성 (docs/completed/alternate-key-unique-m7.md 동시성 장).
 * 일반 UNIQUE 등록·제약조건 삭제는 테이블 행을 잡고(대기), UNIQUE 보정·FK 보정 반영은 프로젝트 행 배타 뒤 테이블 행을 NOWAIT 로 잡는다 (바쁘면 409).
 * 한쪽 트랜잭션을 열어 둔 채(커밋 전) 다른 쪽을 실행해 순서를 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AlternateKeyConcurrencyIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired UniqueCorrectionService uniqueCorrection;
    @Autowired ForeignKeyCorrectionService fkCorrection;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired LogicalModelService logical;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        SecurityTestSupport.clear();
    }


    private record Fx(GenerationFixture f, long customer, long order, long akEmail, long relEmail) {
    }

    /** 고객(PK, 보조 식별자 AK_EMAIL = 이메일) → 주문(주문 고객 이메일) : 생성으로 UNIQUE + 보조 식별자 참조 FK 를 만든다 */
    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "akc-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        long no = f.attr(customer, "고객 번호", true, "CUST_NO");
        long email = f.attr(customer, "이메일", true, "EMAIL");
        f.pk(customer, no);
        long ak = logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, customer, "AK_EMAIL", false, List.of(email), null)).id();
        long order = f.entity("주문");
        long orderNo = f.attr(order, "주문 번호", true, "ORD_NO");
        long orderEmail = f.attr(order, "주문 고객 이메일", false, "ORD_EMAIL");
        f.pk(order, orderNo);
        LogicalModelDto.Relationship rel = f.rel("이메일 주문", customer, order, false, 0);
        f.pairs(rel, new long[]{email, orderEmail});
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order)));
        generation.apply(f.user, f.request(List.of(customer, order), plan.planToken()));
        return new Fx(f, f.table(customer), f.table(order), ak, rel.id());
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private int uniqueCount(long table) {
        return jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Integer.class, table);
    }

    private long uniqueId(long table) {
        return jdbc.queryForObject("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Long.class, table);
    }

    private void dropUniquesAndRelations(Fx x) {
        for (Long id : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, x.order(), x.customer())) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", id);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", id);
        }
        for (Long id : jdbc.queryForList("select CNSTRT_SN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_TYPE_CD = 'UNIQUE'", Long.class, x.customer())) {
            jdbc.update("delete from TB_TBL_CNSTRT_COL where CNSTRT_SN = ?", id);
            jdbc.update("delete from TB_TBL_CNSTRT where CNSTRT_SN = ?", id);
        }
    }

    private PhysicalModelDto.Constraint unique(Fx x) {
        long col = column(x.customer(), "EMAIL");
        return new PhysicalModelDto.Constraint(null, x.customer(), null, "UNIQUE", null, List.of(new PhysicalModelDto.ColumnRef(col, "EMAIL")), null);
    }

    /** 작업 스레드에도 테스트를 시작한 스레드의 로그인을 넘긴다 */
    private org.springframework.security.core.context.SecurityContext login() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext();
    }

    private static Callable<String> as(org.springframework.security.core.context.SecurityContext login, Callable<String> work) {
        return () -> {
            org.springframework.security.core.context.SecurityContextHolder.setContext(login);
            try {
                return work.call();
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        };
    }

    private static String result(Callable<?> work) {
        try {
            work.call();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    /**
     * first 를 트랜잭션 안에서 실행한 뒤 커밋하지 않고 멈춘다 → second 를 실행 → second 가 끝났는지(waitMs 안에) 기록 → first 커밋 → second 결과.
     * 반환 : [first 결과, second 결과, second 가 first 커밋 전에 끝났는지]
     */
    private String[] holdThen(Callable<?> first, Callable<?> second, long waitMs) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        org.springframework.security.core.context.SecurityContext login = login();
        Future<String> a = pool.submit(as(login, () -> result(() -> tx.execute(s -> {
            try {
                first.call();
                held.countDown();
                release.await(30, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                held.countDown();
                throw e;
            } catch (Exception e) {
                held.countDown();
                throw new IllegalStateException(e);
            }
            return null;
        }))));
        assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
        Future<String> b = pool.submit(as(login, () -> result(second)));
        String earlyDone;
        try {
            b.get(waitMs, TimeUnit.MILLISECONDS);
            earlyDone = "DONE_BEFORE_COMMIT";
        } catch (java.util.concurrent.TimeoutException e) {
            earlyDone = "WAITED";
        }
        release.countDown();
        return new String[]{a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS), earlyDone};
    }

    @Test
    @DisplayName("AK-C01 (TOCTOU 회귀) : 같은 컬럼 집합의 UNIQUE 등록 둘 — 둘째가 테이블 행에서 기다렸다가 SAVED_DATA_EXISTS, UNIQUE 는 1개")
    void twoSaveConstraints() throws Exception {
        Fx x = fixture("c01");
        dropUniquesAndRelations(x);
        String[] r = holdThen(() -> physicalModelService.saveConstraint(x.f().user, unique(x)), () -> physicalModelService.saveConstraint(x.f().user, unique(x)), 1500);
        System.out.println("[M7-AK] C01 : " + String.join(" / ", r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[2]).as("둘째는 첫째 커밋까지 기다린다").isEqualTo("WAITED");
        assertThat(r[1]).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(uniqueCount(x.customer())).isEqualTo(1);
    }

    @Test
    @DisplayName("AK-C02 : UNIQUE 등록이 커밋 전인 동안 같은 테이블의 UNIQUE 보정 반영은 기다리지 않고 409 — 커밋 뒤 보정은 막힌다 (같은 구성 있음), UNIQUE 1개")
    void saveConstraintThenUniqueCorrection() throws Exception {
        Fx x = fixture("c02");
        dropUniquesAndRelations(x);
        UniqueCorrectionDto.Plan plan = uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.akEmail()), null));
        assertThat(plan.applicable()).isTrue();
        String[] r = holdThen(() -> physicalModelService.saveConstraint(x.f().user, unique(x)),
                () -> uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.akEmail()), plan.planToken())), 5000);
        System.out.println("[M7-AK] C02 : " + String.join(" / ", r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(r[2]).as("NOWAIT — 기다리지 않는다").isEqualTo("DONE_BEFORE_COMMIT");
        assertThat(uniqueCount(x.customer())).isEqualTo(1);
        assertThat(uniqueCorrection.status(x.f().user, x.f().modelId, List.of(x.akEmail())).get(0).correctable()).isFalse();
    }

    @Test
    @DisplayName("AK-C03 : UNIQUE 보정과 UNIQUE 보정 동시 — 하나만 만든다 (다른 쪽은 409 또는 이미 일치로 400)")
    void twoUniqueCorrections() throws Exception {
        Fx x = fixture("c03");
        dropUniquesAndRelations(x);
        UniqueCorrectionDto.Plan plan = uniqueCorrection.preview(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.akEmail()), null));
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<String> work = () -> {
            start.await(10, TimeUnit.SECONDS);
            return result(() -> uniqueCorrection.apply(x.f().user, new UniqueCorrectionDto.Request(x.f().modelId, List.of(x.akEmail()), plan.planToken())));
        };
        Future<String> a = pool.submit(as(login(), work));
        Future<String> b = pool.submit(as(login(), work));
        List<String> results = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        System.out.println("[M7-AK] C03 : " + results);
        assertThat(results).containsOnlyOnce("OK");
        assertThat(results).anyMatch(s -> s.equals("MODEL_CONFLICT") || s.equals("INVALID_PHYSICAL_MODEL"));
        assertThat(uniqueCount(x.customer())).isEqualTo(1);
    }

    @Test
    @DisplayName("AK-C04 (TOCTOU 회귀) : 부모 UNIQUE 삭제가 커밋 전인 동안 그 UNIQUE 를 참조하는 FK 보정은 409 — 커밋 뒤에는 PARENT_UNIQUE_MISSING 으로 막혀 만드는 시점에 무효인 FK 가 생기지 않는다")
    void deleteUniqueThenForeignKeyCorrection() throws Exception {
        Fx x = fixture("c04");
        for (Long id : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, x.order(), x.customer())) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", id);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", id);
        }
        ForeignKeyCorrectionDto.Plan plan = fkCorrection.preview(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.relEmail()), null));
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        long uk = uniqueId(x.customer());
        String[] r = holdThen(() -> { physicalModelService.delete(x.f().user, PhysicalModelService.Kind.CONSTRAINT, uk, null); return null; },
                () -> fkCorrection.apply(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.relEmail()), plan.planToken())), 5000);
        System.out.println("[M7-AK] C04 : " + String.join(" / ", r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Integer.class, x.order(), x.customer())).isZero();
        ForeignKeyCorrectionDto.Item after = fkCorrection.status(x.f().user, x.f().modelId, List.of(x.relEmail())).get(0);
        assertThat(after.blockers()).extracting(ForeignKeyCorrectionDto.Issue::code).contains(ForeignKeyCorrectionDto.Code.PARENT_UNIQUE_MISSING);
    }

    @Test
    @DisplayName("AK-C05 : 부모 UNIQUE 삭제가 커밋 전인 동안 같은 테이블의 UNIQUE 등록은 테이블 행에서 기다렸다가 성공 (교착 없음)")
    void deleteThenSave() throws Exception {
        Fx x = fixture("c05");
        for (Long id : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, x.order(), x.customer())) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", id);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", id);
        }
        long uk = uniqueId(x.customer());
        String[] r = holdThen(() -> { physicalModelService.delete(x.f().user, PhysicalModelService.Kind.CONSTRAINT, uk, null); return null; },
                () -> physicalModelService.saveConstraint(x.f().user, unique(x)), 1500);
        System.out.println("[M7-AK] C05 : " + String.join(" / ", r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[2]).isEqualTo("WAITED");
        assertThat(r[1]).isEqualTo("OK");
        assertThat(uniqueCount(x.customer())).isEqualTo(1);
    }
}
