package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.mapping.domain.dto.ForeignKeyCorrectionDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.service.ForeignKeyCorrectionService;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M7-FK-02 관계 동기화 교착 회귀 (docs/completed/physical-fk-correction-m7.md). PostgreSQL 에서만 (H2 는 FK 검사가 KEY SHARE 를 잡지 않는다).
 * 별도 스레드·트랜잭션·연결로, 테스트 전용 대기점(관계 동기화 안 — 쓰기 flush 뒤, 또는 엑셀 두 번째 청크 동기화 직전)에서 한쪽을 멈춰 세운 채 다른 쪽을 실행하고,
 * 다른 쪽이 실제로 잠금을 기다리는지는 pg_stat_activity 로 확인한다 (sleep 으로 순서를 정하지 않는다).
 * 고치기 전 구현(HEAD c20f2e2)은 S4·S6b 에서 SQLSTATE 40P01 이 났다. 고친 뒤 : S4 = 둘째가 프로젝트 행에서 기다렸다가 둘 다 성공, S6b = 물리명 적용이 기다리지 않고 409.
 * D11 (M7-FK-D11, 기록 14장) : PK 컬럼 삭제 + 상대 참조 추가 일괄 저장 둘은 D11 수정 전 40P01 3/3 이었다 — 지금은 PK 삭제가 구조 변경 경로(프로젝트 배타 + NOWAIT)라 둘째가 프로젝트 행에서 기다린다.
 * 'H2·PostgreSQL' 이 붙은 테스트는 H2 에서도 돈다 (분류·409 계약·업무 오류·판정 재검증).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RelationSyncDeadlockIntegrationTest {

    enum Pause { AFTER_SYNC_FLUSH, SECOND_SYNC, AFTER_PK_CHECK, BEFORE_FK_CREATE }

    static final Map<Thread, Pause> ARMED = new ConcurrentHashMap<>();
    static final ThreadLocal<Boolean> IN_SYNC = ThreadLocal.withInitial(() -> false);
    static final ThreadLocal<AtomicInteger> SYNC_CALLS = ThreadLocal.withInitial(AtomicInteger::new);
    static volatile CountDownLatch paused;
    static volatile CountDownLatch release;

    @TestConfiguration
    static class Hooks {
        @Bean
        static BeanPostProcessor syncPause() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof PhysicalModelService) {
                        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), ClassUtils.getAllInterfaces(bean), (proxy, method, args) -> {
                            if (method.getName().equals("createGeneratedForeignKey") && ARMED.get(Thread.currentThread()) == Pause.BEFORE_FK_CREATE) {
                                ARMED.remove(Thread.currentThread());
                                hold();
                            }
                            boolean sync = method.getName().equals("syncReferenceRelations");
                            if (sync && ARMED.get(Thread.currentThread()) == Pause.SECOND_SYNC && SYNC_CALLS.get().incrementAndGet() == 2) {
                                ARMED.remove(Thread.currentThread());
                                hold();
                            }
                            if (sync) IN_SYNC.set(true);
                            try {
                                return method.invoke(bean, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            } finally {
                                if (sync) IN_SYNC.set(false);
                            }
                        });
                    }
                    if (bean instanceof ColumnInfoRepository) {
                        // D11 : 잠그기 전 PK 삭제 판정(findPkColumnIds) 바로 뒤에서 멈춘다 — 그 사이 다른 트랜잭션이 그 컬럼을 PK 로 바꾸게 한다
                        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), ClassUtils.getAllInterfaces(bean), (proxy, method, args) -> {
                            try {
                                return method.invoke(bean, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            } finally {
                                if (method.getName().equals("findPkColumnIds") && ARMED.get(Thread.currentThread()) == Pause.AFTER_PK_CHECK) {
                                    ARMED.remove(Thread.currentThread());
                                    hold();
                                }
                            }
                        });
                    }
                    if (bean instanceof TableInfoRepository) {
                        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), ClassUtils.getAllInterfaces(bean), (proxy, method, args) -> {
                            if (method.getName().equals("findAllById") && IN_SYNC.get() && ARMED.get(Thread.currentThread()) == Pause.AFTER_SYNC_FLUSH) {
                                ARMED.remove(Thread.currentThread());
                                hold();
                            }
                            try {
                                return method.invoke(bean, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
                    }
                    return bean;
                }
            };
        }

        private static void hold() {
            paused.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Autowired javax.sql.DataSource dataSource;
    @Autowired ApplicationContext context;
    @Autowired TableDesignService tables;
    @Autowired ColumnManageService columns;
    @Autowired PhysicalNameRenameService rename;
    @Autowired PhysicalGenerationService generation;
    @Autowired ForeignKeyCorrectionService correction;
    @Autowired SchemaService schemas;
    @Autowired ModelMappingService mappings;
    @Autowired LogicalModelService logical;
    @Autowired PhysicalModelService physical;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        ARMED.clear();
        if (release != null) release.countDown();
        SecurityTestSupport.clear();
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    /** 결과 : OK · 오류 코드 · SQLSTATE */
    private static String outcome(Supplier<Object> work) {
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

    private CompletableFuture<String> async(SecurityContext login, Pause pause, Supplier<Object> work) {
        return CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            if (pause != null) ARMED.put(Thread.currentThread(), pause);
            SYNC_CALLS.get().set(0);
            try {
                return outcome(work);
            } finally {
                ARMED.remove(Thread.currentThread());
                SecurityContextHolder.clearContext();
            }
        });
    }

    /** 이 DB 의 다른 연결이 잠금을 기다리기 시작할 때까지 (최대 10초) — 기다리는 문장 */
    private String waitForLockWait() {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            List<String> w = jdbc.queryForList("select left(query, 600) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()", String.class);
            if (!w.isEmpty()) return String.join(" / ", w);
            LockSupport.parkNanos(20_000_000);
        }
        return null;
    }

    /** 대기점에 멈춘 첫 작업 A 와 두 번째 작업 B : B 가 끝나거나 잠금을 기다리기 시작하면 A 를 놓는다. [A 결과, B 결과, B 가 기다린 문장(없으면 null)] */
    private String lastDiagnostics = "";

    private String[] pausedThen(Pause pause, Supplier<Object> a, Supplier<Object> b) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        paused = new CountDownLatch(1);
        release = new CountDownLatch(1);
        CompletableFuture<String> first = async(login, pause, a);
        assertThat(paused.await(30, TimeUnit.SECONDS)).as("A 가 대기점에 오지 않았다 : " + (first.isDone() ? first.get() : "")).isTrue();
        CompletableFuture<String> second = async(login, null, b);
        String waited = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!second.isDone() && System.nanoTime() < until) {
            waited = waitForLockWaitOnce();
            if (waited != null) break;
            LockSupport.parkNanos(20_000_000);
        }
        // 진단 (M8-C4a) : B 가 잠금 대기도 완료도 하지 않은 채 10초가 지나면 A 를 놓기 전 상태를 남긴다 (단언 설명에 붙는다)
        lastDiagnostics = waited == null && !second.isDone()
                ? ConcurrencyDiagnostics.describe("S 시나리오 B 대기", until - TimeUnit.SECONDS.toNanos(10), jdbc, dataSource, null) : "";
        release.countDown();
        return new String[]{first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS), waited};
    }

    private String waitForLockWaitOnce() {
        List<String> w = jdbc.queryForList("select left(query, 600) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()", String.class);
        return w.isEmpty() ? null : String.join(" / ", w);
    }

    // ------------------------------------------------------------------ S4 : 테이블 이름 변경 ↔ 테이블 이름 변경

    private record S4(GenerationFixture f, long t1, long t2) {
    }

    /** 스키마 S1·S2 에 테이블 TA·TB (PK), 서로 상대의 새 이름(S2.TB_NEW / S1.TA_NEW)을 미리 참조하는 컬럼 */
    private S4 s4(String tag) {
        GenerationFixture f = GenerationFixture.create(context, "s4-" + tag + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long p = f.user.getProjectId();
        tx.executeWithoutResult(s -> schemas.resolveSchema(p, null));
        long term = f.term("S4_" + tag.toUpperCase(), "VARCHAR", 20);
        long s1 = GenerationFixture.nextId(), s2 = GenerationFixture.nextId(), t1 = GenerationFixture.nextId(), t2 = GenerationFixture.nextId();
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S1', false)", s1, p);
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S2', false)", s2, p);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, ?, ?, 'TA', false, timestamp '2026-11-01 10:00:00')", t1, p, s1);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, ?, ?, 'TB', false, timestamp '2026-11-01 10:00:00')", t2, p, s2);
        String col = "insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, DATA_TYPE_NM, PK_YN, NULL_ALWNC_YN, APRV_YN, STD_TRM_SN, SORT_ORD, RFRNC_TBL_NM) values (?, ?, ?, ?, ?, 'BIGINT', ?, ?, false, ?, ?, ?)";
        jdbc.update(col, GenerationFixture.nextId(), p, t1, "TA", "TA_SN", true, false, term, 1, null);
        jdbc.update(col, GenerationFixture.nextId(), p, t2, "TB", "TB_SN", true, false, term, 1, null);
        jdbc.update(col, GenerationFixture.nextId(), p, t1, "TA", "REF_NEW_B", false, true, term, 2, "S2.TB_NEW");
        jdbc.update(col, GenerationFixture.nextId(), p, t2, "TB", "REF_NEW_A", false, true, term, 2, "S1.TA_NEW");
        return new S4(f, t1, t2);
    }

    private Object renameTable(GenerationFixture f, long table, String newName) {
        TableInfoDto dto = new TableInfoDto();
        dto.setId(table);
        dto.setTableName(newName);
        dto.setTableDescription(newName);
        dto.setIsMasterTable("N");
        dto.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", java.sql.Timestamp.class, table).toLocalDateTime());
        tables.updateTable(dto, f.user);
        return null;
    }

    private int relations(long source, long target) {
        return jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Integer.class, source, target);
    }

    @Test
    @DisplayName("S4 : 서로 다른 스키마의 테이블 이름 변경 둘 (서로의 새 이름을 미리 참조) — 교착(40P01) 없이 둘째가 프로젝트 행에서 기다렸다가 둘 다 성공하고 관계가 둘 다 생긴다 (3회)")
    void s4TwoRenames() throws Exception {
        assumeTrue(postgres());
        for (int round = 0; round < 3; round++) {
            S4 x = s4("r" + round);
            String[] r = pausedThen(Pause.AFTER_SYNC_FLUSH, () -> renameTable(x.f(), x.t1(), "TA_NEW"), () -> renameTable(x.f(), x.t2(), "TB_NEW"));
            System.out.println("[M7-FK-02] S4 round " + round + " : " + Arrays.toString(r));
            assertThat(r[0]).as("A (round " + round + ")").isEqualTo("OK");
            assertThat(r[1]).as("B (round " + round + ")").isEqualTo("OK");
            assertThat(r[2]).as("B 는 A 가 끝날 때까지 프로젝트 행에서 기다린다 (행 잠금 대기가 아니다)" + lastDiagnostics).isNotNull().containsIgnoringCase("tb_prj ");
            assertThat(jdbc.queryForList("select TBL_NM from TB_TBL where TBL_SN in (?, ?) order by TBL_SN", String.class, x.t1(), x.t2())).containsExactly("TA_NEW", "TB_NEW");
            assertThat(relations(x.t1(), x.t2())).isEqualTo(1);
            assertThat(relations(x.t2(), x.t1())).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ S6b : 물리명 일괄 적용 ↔ 501행 엑셀 업로드

    private record S6(GenerationFixture f, long renamed, long other, String recommended, PhysicalNameRenameDto.Plan preview, long domain) {
    }

    /** 생성한 테이블(고객)의 이름을 O_ 접두어로 바꿔 둔다 (추천명 = 원래 이름) — 엑셀은 다른 테이블(주문)에 그 테이블을 참조하는 컬럼을 넣는다 */
    private S6 s6(String tag) {
        GenerationFixture f = GenerationFixture.create(context, "s6-" + tag + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order)));
        generation.apply(f.user, f.request(List.of(customer, order), plan.planToken()));
        long table = f.table(customer);
        String recommended = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table);
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where TBL_SN = ?", table);
        jdbc.update("update TB_COL set TBL_NM = 'O_' || TBL_NM where TBL_SN = ?", table);
        PhysicalNameRenameDto.Plan preview = rename.preview(f.user, new PhysicalNameRenameDto.Request(
                List.of(new PhysicalNameRenameDto.Item(PhysicalNameRenameDto.TargetType.TABLE, table, recommended, null)), null));
        assertThat(preview.applicable()).as(String.valueOf(preview.items())).isTrue();
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, f.term("S6_DOMAIN", "VARCHAR", 20));
        return new S6(f, table, f.table(order), recommended, preview, domain);
    }

    /** 501행 : 첫 행 = 이름을 바꿀 테이블을 참조하는 컬럼(첫 청크), 나머지 500행 = 일반 컬럼 (두 번째 청크까지) */
    private byte[] excel(S6 x, String prefix) {
        String other = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.other());
        String target = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.renamed());
        List<String> names = new ArrayList<>();
        names.add(prefix + "REF");
        for (int i = 0; i < 500; i++) names.add(prefix + i);
        List<Object[]> terms = new ArrayList<>();
        for (String n : names) terms.add(new Object[]{GenerationFixture.nextId(), x.f().user.getProjectId(), n + "_" + System.nanoTime(), n, n, x.domain()});
        jdbc.batchUpdate("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", terms);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            for (int r = 0; r < names.size(); r++) {
                List<String> values = Arrays.asList(other, names.get(r), "설명", "VARCHAR", "20", "0", "0", "Y", "", "9", "N", r == 0 ? target : "", "N", "N", "N", "N", "N", "N");
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object upload(GenerationFixture f, byte[] file) {
        try {
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file), f.user);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    private Object applyRename(S6 x) {
        var item = x.preview().items().get(0);
        rename.apply(x.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(PhysicalNameRenameDto.TargetType.TABLE, x.renamed(), x.recommended(),
                item.modifiedAt())), x.preview().planToken()));
        return null;
    }

    @Test
    @DisplayName("S6b : 엑셀(501행)이 첫 청크에서 참조 관계를 만든(KEY SHARE) 뒤 멈춘 사이 물리명 일괄 적용 — 적용은 기다리지 않고 409, 엑셀은 끝까지 성공, 이름·관계 그대로 (3회)")
    void s6bRenameApplyVersusExcel() throws Exception {
        assumeTrue(postgres());
        for (int round = 0; round < 3; round++) {
            S6 x = s6("r" + round);
            String prefix = "XS" + round + "_";
            byte[] file = excel(x, prefix);
            String[] r = pausedThen(Pause.SECOND_SYNC, () -> upload(x.f(), file), () -> applyRename(x));
            System.out.println("[M7-FK-02] S6b round " + round + " : " + Arrays.toString(r));
            assertThat(r[0]).as("엑셀 (round " + round + ")").isEqualTo("OK");
            assertThat(r[1]).as("물리명 적용 (round " + round + ")").isEqualTo("MODEL_CONFLICT");
            assertThat(r[2]).as("적용은 KEY SHARE 를 기다리지 않는다").isNull();
            assertThat(jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.renamed())).isEqualTo("O_" + x.recommended());
            assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM like ?", Integer.class, x.other(), prefix + "%")).isEqualTo(501);
            assertThat(relations(x.other(), x.renamed())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ? and VER_NM like 'RENAME-%'", Integer.class, x.f().user.getProjectId())).isZero();
            // 엑셀이 끝난 뒤에는 같은 계획으로 적용된다 (엑셀은 이름을 바꿀 테이블을 바꾸지 않았다)
            assertThat(outcome(() -> applyRename(x))).isEqualTo("OK");
            assertThat(jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.renamed())).isEqualTo(x.recommended());
        }
    }

    @Test
    @DisplayName("테이블 이름 변경 ↔ 엑셀 업로드 : 엑셀이 그 테이블을 참조하는 관계를 만든 채 멈춘 사이 이름 변경은 기다리지 않고 409, 엑셀은 성공")
    void tableRenameVersusExcel() throws Exception {
        assumeTrue(postgres());
        S6 x = s6("tr");
        byte[] file = excel(x, "XT_");
        String[] r = pausedThen(Pause.SECOND_SYNC, () -> upload(x.f(), file), () -> renameTable(x.f(), x.renamed(), x.recommended()));
        System.out.println("[M7-FK-02] 테이블 이름 변경 ↔ 엑셀 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(r[2]).isNull();
        assertThat(jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.renamed())).isEqualTo("O_" + x.recommended());
    }

    @Test
    @DisplayName("스키마 이동 ↔ 엑셀 업로드 : 엑셀이 그 테이블을 참조하는 관계를 만든 채 멈춘 사이 다른 스키마로 옮기기(UX_TBL_01 키 변경)는 기다리지 않고 409, 엑셀은 성공")
    void schemaMoveVersusExcel() throws Exception {
        assumeTrue(postgres());
        S6 x = s6("mv");
        long target = GenerationFixture.nextId();
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S_MOVE', false)", target, x.f().user.getProjectId());
        byte[] file = excel(x, "XM_");
        String current = "O_" + x.recommended();
        String[] r = pausedThen(Pause.SECOND_SYNC, () -> upload(x.f(), file), () -> {
            TableInfoDto dto = new TableInfoDto();
            dto.setId(x.renamed());
            dto.setTableName(current);
            dto.setTableDescription(current);
            dto.setIsMasterTable("N");
            dto.setSchemaId(target);
            dto.setBaseModifiedAt(jdbc.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", java.sql.Timestamp.class, x.renamed()).toLocalDateTime());
            tables.updateTable(dto, x.f().user);
            return null;
        });
        System.out.println("[M7-FK-02] 스키마 이동 ↔ 엑셀 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(r[2]).isNull();
        assertThat(jdbc.queryForObject("select SCHM_SN from TB_TBL where TBL_SN = ?", Long.class, x.renamed())).isNotEqualTo(target);
    }

    // ------------------------------------------------------------------ 누락 FK 보정의 동시성

    private record Fk(GenerationFixture f, long dept, long team, long relationship, long k1, long fa1) {
    }

    /** 부서(복합 키) → 팀 FK 누락 (생성 뒤 지움) */
    private Fk fk(String tag) {
        GenerationFixture f = GenerationFixture.create(context, "fkcc-" + tag + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long dept = f.entity("부서");
        long k1 = f.attr(dept, "키1", true, "K1");
        long k2 = f.attr(dept, "키2", true, "K2");
        f.pk(dept, k1, k2);
        long team = f.entity("팀");
        long teamNo = f.attr(team, "팀 번호", true, "TEAM_NO");
        long fa1 = f.attr(team, "부서 키1", true, "FA1");
        long fa2 = f.attr(team, "부서 키2", true, "FA2");
        f.pk(team, teamNo);
        LogicalModelDto.Relationship rel = f.rel("소속", dept, team, false, 0);
        f.pairs(rel, new long[]{k1, fa1}, new long[]{k2, fa2});
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(dept, team)));
        generation.apply(f.user, f.request(List.of(dept, team), plan.planToken()));
        for (Long id : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ?", Long.class, f.table(team))) {
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", id);
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", id);
        }
        return new Fk(f, f.table(dept), f.table(team), rel.id(), k1, fa1);
    }

    private Object correct(Fk x, ForeignKeyCorrectionDto.Plan plan) {
        correction.apply(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.relationship()), plan.planToken()));
        return null;
    }

    private ForeignKeyCorrectionDto.Plan plan(Fk x) {
        return correction.preview(x.f().user, new ForeignKeyCorrectionDto.Request(x.f().modelId, List.of(x.relationship()), null));
    }

    /** 부모(부서)를 참조하는 컬럼 1행 + 일반 500행을 자식이 아닌 다른 테이블에 올리는 엑셀 — 부모 테이블 행에 KEY SHARE 를 잡은 채 멈춘다 */
    private byte[] excelReferencing(Fk x, String tableName, String refTable, String prefix) {
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, x.f().term("FC_DOMAIN", "VARCHAR", 20));
        List<String> names = new ArrayList<>();
        names.add(prefix + "REF");
        for (int i = 0; i < 500; i++) names.add(prefix + i);
        List<Object[]> terms = new ArrayList<>();
        for (String n : names) terms.add(new Object[]{GenerationFixture.nextId(), x.f().user.getProjectId(), n + "_" + System.nanoTime(), n, n, domain});
        jdbc.batchUpdate("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", terms);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            for (int r = 0; r < names.size(); r++) {
                List<String> values = Arrays.asList(tableName, names.get(r), "설명", "VARCHAR", "20", "0", "0", "Y", "", "9", "N", r == 0 && refTable != null ? refTable : "", "N", "N", "N", "N", "N", "N");
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String name(long table) {
        return jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table);
    }

    @Test
    @DisplayName("FK 보정 ↔ 500행 초과 엑셀 : 엑셀이 보정 대상 테이블을 참조(KEY SHARE)한 채 멈춰도 보정은 기다리지 않고 성공, 엑셀도 성공 (교착 없음). 엑셀이 자식 테이블에 올리는 중이면 보정은 409")
    void correctionVersusExcel() throws Exception {
        assumeTrue(postgres());
        Fk x = fk("xl");
        // 엑셀 : 부서를 참조하는 컬럼을 다른 테이블(새로 만든 TB_FC_OTHER)에
        long other = GenerationFixture.nextId();
        long schema = jdbc.queryForObject("select SCHM_SN from TB_TBL where TBL_SN = ?", Long.class, x.team());
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, ?, ?, 'TB_FC_OTHER', false)", other, x.f().user.getProjectId(), schema);
        // 엑셀 : 자식 테이블(팀, 단일 PK)을 참조하는 컬럼을 다른 테이블(새로 만든 TB_FC_OTHER)에 — 보정이 잠글 팀 테이블 행에 KEY SHARE
        byte[] file = excelReferencing(x, "TB_FC_OTHER", name(x.team()), "FO_");
        ForeignKeyCorrectionDto.Plan plan = plan(x);
        assertThat(plan.applicable()).isTrue();
        String[] r = pausedThen(Pause.SECOND_SYNC, () -> upload(x.f(), file), () -> correct(x, plan));
        System.out.println("[M7-FK-02] FK 보정 ↔ 엑셀(부모 참조) : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("OK");
        assertThat(r[2]).as("보정은 행 잠금을 기다리지 않는다").isNull();
        assertThat(relations(x.team(), x.dept())).isEqualTo(1);
        assertThat(relations(other, x.team())).isEqualTo(1);

        // 엑셀이 자식 테이블(팀)에 올리는 중 : 보정은 자식 테이블 행을 바로 잡지 못해 409
        Fk y = fk("xl2");
        byte[] file2 = excelReferencing(y, name(y.team()), null, "FT_");
        ForeignKeyCorrectionDto.Plan plan2 = plan(y);
        String[] r2 = pausedThen(Pause.SECOND_SYNC, () -> upload(y.f(), file2), () -> correct(y, plan2));
        System.out.println("[M7-FK-02] FK 보정 ↔ 엑셀(자식 테이블) : " + Arrays.toString(r2));
        assertThat(r2[0]).isEqualTo("OK");
        assertThat(r2[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(relations(y.team(), y.dept())).isZero();
    }

    @Test
    @DisplayName("FK 보정 ↔ 테이블 이름 변경·같은 보정 : 프로젝트 행에서 직렬화 — 교착 없이 뒤의 것이 바뀐 계획을 보고 409, FK 는 하나")
    void correctionVersusRenameAndItself() throws Exception {
        assumeTrue(postgres());
        Fk x = fk("rn");
        ForeignKeyCorrectionDto.Plan plan = plan(x);
        // 이름 변경(자식 테이블)이 동기화 안에서 멈춘 사이 보정 : 보정은 프로젝트 행에서 기다리고, 이름이 바뀐 계획이라 409
        String[] r = pausedThen(Pause.AFTER_SYNC_FLUSH, () -> renameTable(x.f(), x.team(), "TEAM_RENAMED"), () -> correct(x, plan));
        System.out.println("[M7-FK-02] 이름 변경 ↔ FK 보정 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(r[2]).isNotNull().containsIgnoringCase("tb_prj ");
        // 같은 보정 둘 : 하나만 만든다
        ForeignKeyCorrectionDto.Plan fresh = plan(x);
        SecurityContext login = SecurityContextHolder.getContext();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(async(login, null, () -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return correct(x, fresh);
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> run : runs) results.add(run.get(60, TimeUnit.SECONDS));
        System.out.println("[M7-FK-02] 같은 FK 보정 둘 : " + results);
        assertThat(results).containsExactlyInAnyOrder("OK", "MODEL_CONFLICT");
        assertThat(relations(x.team(), x.dept())).isEqualTo(1);
    }

    @Test
    @DisplayName("이름 변경 없는 일반 저장은 그대로 : 테이블 설명 수정은 프로젝트 행을 먼저 잡지 않는다 (엑셀이 멈춘 사이에도 바로 끝난다)")
    void descriptionOnlyUpdateUnchanged() throws Exception {
        assumeTrue(postgres());
        S6 x = s6("desc");
        byte[] file = excel(x, "XD_");
        String[] r = pausedThen(Pause.SECOND_SYNC, () -> upload(x.f(), file), () -> renameTable(x.f(), x.renamed(), "O_" + x.recommended()));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("OK");
        assertThat(r[2]).isNull();
    }


    // ------------------------------------------------------------------ D11 : 컬럼 일괄 저장끼리의 PK 컬럼 삭제 교차 (M7-FK-D11)

    private record Bulk(GenerationFixture f, long ta, long tb, long taPk, long tbPk, long taX, String taName, String tbName, long term) {
    }

    /** 승인된 테이블 TA·TB (같은 스키마). 각각 미승인 PK 컬럼(…_SN)과 미승인 일반 컬럼 X. 새 참조 컬럼용 표준용어는 부를 때 만든다 */
    private Bulk bulk(String tag) {
        GenerationFixture f = GenerationFixture.create(context, "d11-" + tag + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long p = f.user.getProjectId();
        long schema = GenerationFixture.nextId(), ta = GenerationFixture.nextId(), tb = GenerationFixture.nextId();
        String taName = "TA_" + tag.toUpperCase(), tbName = "TB_" + tag.toUpperCase();
        long term = f.term("D11_" + tag.toUpperCase(), "BIGINT", 0);
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S1', false)", schema, p);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, ?, ?, ?, true, timestamp '2026-11-01 10:00:00')", ta, p, schema, taName);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN, MDFCN_DT) values (?, ?, ?, ?, true, timestamp '2026-11-01 10:00:00')", tb, p, schema, tbName);
        String col = "insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, PK_YN, NULL_ALWNC_YN, APRV_YN, STD_TRM_SN, SORT_ORD) values (?, ?, ?, ?, ?, '설명', 'BIGINT', ?, ?, false, ?, ?)";
        long taPk = GenerationFixture.nextId(), tbPk = GenerationFixture.nextId(), taX = GenerationFixture.nextId();
        jdbc.update(col, taPk, p, ta, taName, "TA_SN", true, false, term, 1);
        jdbc.update(col, tbPk, p, tb, tbName, "TB_SN", true, false, term, 1);
        jdbc.update(col, taX, p, ta, taName, "TA_X", false, true, term, 2);
        jdbc.update(col, GenerationFixture.nextId(), p, tb, tbName, "TB_X", false, true, term, 2);
        return new Bulk(f, ta, tb, taPk, tbPk, taX, taName, tbName, term);
    }

    private static ColumnBulkSaveDto.Row newRow(long term, String refTable) {
        return ColumnBulkSaveDto.Row.builder().key("new").termId(term).columnDesc("참조").dataType("BIGINT").refTableName(refTable).isPk("N").isNullable("Y").build();
    }

    /** 일괄 저장 : 지울 컬럼 + 새 컬럼(용어 약어 = 컬럼명, 참조 테이블명). 실패 결과(행 오류)는 FAIL:메시지 로 */
    private Object bulkSave(GenerationFixture f, long table, List<Long> deleted, List<ColumnBulkSaveDto.Row> rows) {
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(table);
        request.setDeletedIds(new ArrayList<>(deleted));
        request.setRows(new ArrayList<>(rows));
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(f.user, request);
        if (!result.result()) throw new IllegalStateException("FAIL:" + result.errors().stream().map(ColumnBulkSaveDto.RowError::message).toList());
        return null;
    }

    private long newTerm(Bulk x, String abbreviation) {
        return x.f().term(abbreviation, "BIGINT", 0);
    }

    private int pkCount(long table) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and PK_YN = true", Integer.class, table);
    }

    private int columnCount(long table, String name) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM = ?", Integer.class, table, name);
    }

    @Test
    @DisplayName("D11 : 일괄 저장 A(TA PK 삭제 + TB 참조 추가) ↔ B(TB PK 삭제 + TA 참조 추가) — 교착(40P01) 없이 B 는 프로젝트 행에서 기다렸다가 진행, 둘 다 성공, 잘못된 FK 없음 (3회)")
    void d11CrossPkDeletingBulkSaves() throws Exception {
        assumeTrue(postgres());
        for (int round = 0; round < 3; round++) {
            Bulk x = bulk("x" + round);
            long refB = newTerm(x, "RB_X" + round), refA = newTerm(x, "RA_X" + round);
            String[] r = pausedThen(Pause.AFTER_SYNC_FLUSH,
                    () -> bulkSave(x.f(), x.ta(), List.of(x.taPk()), List.of(newRow(refB, x.tbName()))),
                    () -> bulkSave(x.f(), x.tb(), List.of(x.tbPk()), List.of(newRow(refA, x.taName()))));
            System.out.println("[M7-FK-D11] PK 삭제 교차 round " + round + " : " + Arrays.toString(r));
            assertThat(r[0]).as("A (round " + round + ")").isEqualTo("OK");
            assertThat(r[1]).as("B (round " + round + ")").isEqualTo("OK");
            assertThat(r[2]).as("B 는 A 가 끝날 때까지 프로젝트 행에서 기다린다 (행 잠금을 쥔 채 기다리지 않는다)").isNotNull().containsIgnoringCase("tb_prj ");
            assertThat(pkCount(x.ta())).isZero();
            assertThat(pkCount(x.tb())).isZero();
            assertThat(columnCount(x.ta(), "RB_X" + round)).isEqualTo(1);
            assertThat(columnCount(x.tb(), "RA_X" + round)).isEqualTo(1);
            // B 가 TB PK 를 지우면서 A 가 만든 REF_COLUMN 관계도 지웠고, B 의 참조는 PK 가 없는 TA 라 관계가 없다
            assertThat(relations(x.ta(), x.tb())).isZero();
            assertThat(relations(x.tb(), x.ta())).isZero();
        }
    }

    @Test
    @DisplayName("D11 : PK 삭제 일괄 저장(A, 멈춤) ↔ 그 PK 를 참조하는 컬럼을 추가하는 일반 일괄 저장(B) — 교착 없음, B 는 지워진 PK 를 참조할 수 없어 FK 위반으로 전체 롤백, 잘못된 관계 없음")
    void d11PkDeleteVersusReferencingSave() throws Exception {
        assumeTrue(postgres());
        Bulk x = bulk("rf");
        long refA = newTerm(x, "RA_RF");
        String[] r = pausedThen(Pause.AFTER_SYNC_FLUSH,
                () -> bulkSave(x.f(), x.ta(), List.of(x.taPk()), List.of()),
                () -> bulkSave(x.f(), x.tb(), List.of(), List.of(newRow(refA, x.taName()))));
        System.out.println("[M7-FK-D11] PK 삭제 ↔ 참조 추가 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).as("B 는 커밋된 삭제를 보고 FK 위반으로 끝난다 (예전과 같은 결과 — 교착이 아니다)").isEqualTo("SQLSTATE:23503");
        assertThat(r[2]).as("B 는 지우는 중인 PK 컬럼 행(KEY SHARE)에서 기다렸다").isNotNull();
        assertThat(pkCount(x.ta())).isZero();
        assertThat(columnCount(x.tb(), "RA_RF")).as("B 전체 롤백").isZero();
        assertThat(relations(x.tb(), x.ta())).isZero();
    }

    @Test
    @DisplayName("D11 : PK 삭제 일괄 저장(A, 멈춤) ↔ 다른 테이블의 일반 일괄 저장(B) — B 는 커밋 때 프로젝트 행에서 기다렸다가 성공 (구조 변경 동안의 비용). 일반 저장끼리는 기다리지 않는다")
    void d11StructureVersusOrdinaryAndOrdinaryStayConcurrent() throws Exception {
        assumeTrue(postgres());
        Bulk x = bulk("in");
        long plainA = newTerm(x, "PA_IN"), plainB = newTerm(x, "PB_IN"), plainC = newTerm(x, "PC_IN"), plainD = newTerm(x, "PD_IN");
        String[] r = pausedThen(Pause.AFTER_SYNC_FLUSH,
                () -> bulkSave(x.f(), x.ta(), List.of(x.taPk()), List.of()),
                () -> bulkSave(x.f(), x.tb(), List.of(), List.of(newRow(plainA, null))));
        System.out.println("[M7-FK-D11] 구조 변경 ↔ 일반 저장 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("OK");
        assertThat(r[2]).as("일반 저장은 커밋 직전 프로젝트 공유 잠금에서만 기다린다").isNotNull().containsIgnoringCase("tb_prj");

        // 일반 저장끼리 (서로 다른 테이블, 삭제·PK 없음) : 프로젝트 단위로 직렬화되지 않는다 — 기다림 없이 둘 다 성공
        String[] o = pausedThen(Pause.AFTER_SYNC_FLUSH,
                () -> bulkSave(x.f(), x.ta(), List.of(), List.of(newRow(plainB, x.tbName()))),
                () -> bulkSave(x.f(), x.tb(), List.of(), List.of(newRow(plainC, null))));
        System.out.println("[M7-FK-D11] 일반 저장 ↔ 일반 저장 : " + Arrays.toString(o));
        assertThat(o[0]).isEqualTo("OK");
        assertThat(o[1]).isEqualTo("OK");
        assertThat(o[2]).as("일반 저장끼리는 잠금을 기다리지 않는다").isNull();

        // PK 가 아닌 컬럼 삭제는 일반 저장이다 : 다른 테이블 저장과 기다림 없이 함께 진행
        String[] n = pausedThen(Pause.AFTER_SYNC_FLUSH,
                () -> bulkSave(x.f(), x.ta(), List.of(x.taX()), List.of()),
                () -> bulkSave(x.f(), x.tb(), List.of(), List.of(newRow(plainD, null))));
        System.out.println("[M7-FK-D11] 일반 컬럼 삭제 ↔ 일반 저장 : " + Arrays.toString(n));
        assertThat(n[0]).isEqualTo("OK");
        assertThat(n[1]).isEqualTo("OK");
        assertThat(n[2]).isNull();
    }

    @Test
    @DisplayName("D11 : 엑셀(501행)이 첫 청크에서 TA PK 를 참조(KEY SHARE)한 채 멈춘 사이 TA PK 삭제 일괄 저장 — 기다리지 않고 409, 엑셀 성공, PK 그대로")
    void d11PkDeleteVersusExcel() throws Exception {
        assumeTrue(postgres());
        S6 x = s6("pk");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.renamed());
        long pk = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and PK_YN = true", Long.class, x.renamed());
        byte[] file = excel(x, "XP_");
        String[] r = pausedThen(Pause.SECOND_SYNC, () -> upload(x.f(), file), () -> bulkSave(x.f(), x.renamed(), List.of(pk), List.of()));
        System.out.println("[M7-FK-D11] PK 삭제 ↔ 엑셀 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("MODEL_CONFLICT");
        assertThat(r[2]).as("PK 삭제는 KEY SHARE 를 기다리지 않는다").isNull();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where COL_SN = ?", Integer.class, pk)).isEqualTo(1);
        assertThat(relations(x.other(), x.renamed())).isEqualTo(1);
    }

    @Test
    @DisplayName("D11 : 컬럼 단건 삭제(PK, 멈춤) ↔ 그 PK 를 참조하는 일괄 저장 — 교착 없음, 참조 쪽 FK 위반 롤백. 테이블 행이 바쁘면 PK 삭제는 409")
    void d11SingleDeleteOfPk() throws Exception {
        assumeTrue(postgres());
        Bulk x = bulk("sd");
        long refA = newTerm(x, "RA_SD");
        String[] r = pausedThen(Pause.AFTER_SYNC_FLUSH,
                () -> deleteColumns(x.f(), x.taPk()),
                () -> bulkSave(x.f(), x.tb(), List.of(), List.of(newRow(refA, x.taName()))));
        System.out.println("[M7-FK-D11] 단건 PK 삭제 ↔ 참조 추가 : " + Arrays.toString(r));
        assertThat(r[0]).isEqualTo("OK");
        assertThat(r[1]).isEqualTo("SQLSTATE:23503");
        assertThat(relations(x.tb(), x.ta())).isZero();
        assertThat(columnCount(x.tb(), "RA_SD")).isZero();
    }

    private Object deleteColumns(GenerationFixture f, long... ids) {
        List<ColumnInfoDto> dtos = new ArrayList<>();
        for (long id : ids) {
            ColumnInfoDto dto = new ColumnInfoDto();
            dto.setId(id);
            dtos.add(dto);
        }
        columns.deleteColumn(f.user, dtos);
        return null;
    }

    /**
     * 다른 트랜잭션이 테이블 행을 쥔 동안 work 를 실행한다 (H2·PostgreSQL 공통). 구조 변경은 기다리지 않으므로 그 결과를 돌려준다.
     * work 가 잠금을 기다리기 시작하면(PostgreSQL pg_stat_activity, H2 INFORMATION_SCHEMA.SESSIONS.BLOCKER_ID) 행을 놓고 "WAITED:" + 결과
     */
    private String whileTableHeld(long table, Supplier<Object> work) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("select TBL_SN from TB_TBL where TBL_SN = ? for update", Long.class, table);
            held.countDown();
            try {
                done.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<String> run = async(SecurityContextHolder.getContext(), null, work);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waited = false;
            while (!run.isDone() && System.nanoTime() < until) {
                if (blockedSessions() > 0) {
                    waited = true;
                    break;
                }
                LockSupport.parkNanos(20_000_000);
            }
            done.countDown();
            String result = run.get(30, TimeUnit.SECONDS);
            return waited ? "WAITED:" + result : result;
        } finally {
            done.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    private int blockedSessions() {
        if (postgres()) {
            return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()", Integer.class);
        }
        return jdbc.queryForObject("select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null", Integer.class);
    }

    @Test
    @DisplayName("D11 (H2·PostgreSQL) : 테이블 행이 바쁘면 PK 를 지우는 일괄 저장·단건 삭제는 기다리지 않고 409 (아무것도 지우지 않음), 일반 저장은 예전처럼 기다렸다가 성공")
    void d11NoWaitContract() throws Exception {
        Bulk x = bulk("nw");
        assertThat(whileTableHeld(x.ta(), () -> bulkSave(x.f(), x.ta(), List.of(x.taPk()), List.of()))).isEqualTo("MODEL_CONFLICT");
        assertThat(whileTableHeld(x.ta(), () -> deleteColumns(x.f(), x.taPk()))).isEqualTo("MODEL_CONFLICT");
        assertThat(pkCount(x.ta())).isEqualTo(1);
        long plain = newTerm(x, "PL_NW");
        assertThat(whileTableHeld(x.ta(), () -> bulkSave(x.f(), x.ta(), List.of(), List.of(newRow(plain, null))))).isEqualTo("WAITED:OK");
        // 잠금 해제 뒤 같은 요청은 정상 처리된다
        assertThat(outcome(() -> bulkSave(x.f(), x.ta(), List.of(x.taPk()), List.of()))).isEqualTo("OK");
        assertThat(pkCount(x.ta())).isZero();
    }

    @Test
    @DisplayName("D11 (H2·PostgreSQL) : 경합이 없으면 기존 업무 오류 그대로 — 승인된 PK 컬럼 삭제는 행 오류, 관계에 쓰인 PK 는 RELATION_EXISTS, 구조 변경 판정이 업무 검사를 건너뛰지 않는다")
    void d11BusinessErrorsUnchanged() {
        Bulk x = bulk("be");
        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", x.tbPk());
        assertThat(outcome(() -> bulkSave(x.f(), x.tb(), List.of(x.tbPk()), List.of()))).startsWith("IllegalStateException:FAIL:").contains("TB_SN");
        assertThat(outcome(() -> deleteColumns(x.f(), x.tbPk()))).isEqualTo("APPROVED_DATA_CANNOT_BE_DELETED");
        assertThat(pkCount(x.tb())).isEqualTo(1);
        // TA PK 를 쓰는 USER 관계 (TB.TB_X → TA.TA_SN)
        long rel = GenerationFixture.nextId();
        long tbX = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = 'TB_X'", Long.class, x.tb());
        jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, APRV_YN) values (?, ?, ?, ?, 'FK_USER_BE', 'FK', 'USER', false)",
                rel, x.f().user.getProjectId(), x.tb(), x.ta());
        jdbc.update("insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values (?, ?, ?, ?, 1)", GenerationFixture.nextId(), rel, tbX, x.taPk());
        assertThat(outcome(() -> bulkSave(x.f(), x.ta(), List.of(x.taPk()), List.of()))).isEqualTo("RELATION_EXISTS");
        assertThat(outcome(() -> deleteColumns(x.f(), x.taPk()))).isEqualTo("RELATION_EXISTS");
        assertThat(pkCount(x.ta())).isEqualTo(1);
    }

    @Test
    @DisplayName("D11 (H2·PostgreSQL) : 잠그기 전에는 PK 가 아니던 삭제 대상이 잠근 뒤 PK 면(그 사이 다른 저장이 PK 로 바꿈) 프로젝트 배타를 뒤늦게 잡지 않고 409 — 아무것도 지우지 않는다")
    void d11StaleClassification() throws Exception {
        Bulk x = bulk("st");
        SecurityContext login = SecurityContextHolder.getContext();
        for (String path : List.of("bulk", "delete")) {
            paused = new CountDownLatch(1);
            release = new CountDownLatch(1);
            CompletableFuture<String> a = async(login, Pause.AFTER_PK_CHECK,
                    () -> path.equals("bulk") ? bulkSave(x.f(), x.ta(), List.of(x.taX()), List.of()) : deleteColumns(x.f(), x.taX()));
            assertThat(paused.await(30, TimeUnit.SECONDS)).as(path + " 가 판정 뒤에 멈추지 않았다").isTrue();
            // 그 사이 TA_X 를 PK 로 (일반 일괄 저장, 커밋)
            jdbc.update("update TB_COL set PK_YN = true, NULL_ALWNC_YN = false where COL_SN = ?", x.taX());
            release.countDown();
            assertThat(a.get(60, TimeUnit.SECONDS)).as(path).isEqualTo("MODEL_CONFLICT");
            assertThat(jdbc.queryForObject("select count(*) from TB_COL where COL_SN = ?", Integer.class, x.taX())).isEqualTo(1);
            jdbc.update("update TB_COL set PK_YN = false, NULL_ALWNC_YN = true where COL_SN = ?", x.taX());
        }
    }


    // ------------------------------------------------------------------ FK 보정 적용 중 동시 변경 (토큰 재검증 뒤 ~ 커밋 사이, TOCTOU)

    private long colId(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    /** FK 보정(A)이 다시 계획·토큰 비교·직전 버전 저장까지 마치고 FK 를 만들기 직전에 멈춘 사이 B 를 실행한다. B 는 A 가 끝날 때까지 기다리거나 409 여야 한다 */
    private String[] duringCorrection(Fk x, Supplier<Object> b) throws Exception {
        ForeignKeyCorrectionDto.Plan plan = plan(x);
        assertThat(plan.applicable()).isTrue();
        return pausedThen(Pause.BEFORE_FK_CREATE, () -> correct(x, plan), b);
    }

    @Test
    @DisplayName("FK 보정 적용 중 동시 변경 : 같은 FK 관계 등록·속성 매핑 해제·관계 속성 짝 변경·자식 테이블 승인·부모 PK 삭제는 보정이 끝날 때까지 기다리거나 409 — 보정이 본 상태가 커밋 전에 바뀌지 않는다")
    void correctionVersusConcurrentWrites() throws Exception {
        assumeTrue(postgres());
        // 1) 같은 FK 를 관계 등록으로 (자식 테이블 행을 먼저 잠근다 — 기다린 뒤 보정이 만든 FK 를 보고 중복 거절)
        Fk a = fk("tw1");
        String[] r1 = duringCorrection(a, () -> physical.saveRelation(a.f().user, new PhysicalModelDto.Relation(null, null, "FK", null, a.team(), null, a.dept(), null,
                List.of(new PhysicalModelDto.ColumnPair(colId(a.team(), "FA1"), null, colId(a.dept(), "K1"), null),
                        new PhysicalModelDto.ColumnPair(colId(a.team(), "FA2"), null, colId(a.dept(), "K2"), null)), null)));
        System.out.println("[M7-FK] 보정 ↔ 같은 FK 관계 등록 : " + Arrays.toString(r1));
        assertThat(r1[0]).isEqualTo("OK");
        assertThat(r1[1]).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(r1[2]).as("관계 등록은 자식 테이블 행에서 기다린다").isNotNull();
        assertThat(relations(a.team(), a.dept())).as("FK 는 하나").isEqualTo(1);

        // 2) 속성 매핑 해제 (논리 모델 행에서 기다린다)
        Fk b = fk("tw2");
        long mapping = jdbc.queryForObject("select ATRB_COL_MPNG_SN from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", Long.class, b.fa1());
        String[] r2 = duringCorrection(b, () -> {
            mappings.deleteAttributeMapping(b.f().user, mapping, null);
            return null;
        });
        System.out.println("[M7-FK] 보정 ↔ 속성 매핑 해제 : " + Arrays.toString(r2));
        assertThat(r2[0]).isEqualTo("OK");
        assertThat(r2[1]).isEqualTo("OK");
        assertThat(r2[2]).as("매핑 해제는 보정이 끝날 때까지 기다린다").isNotNull();

        // 3) 관계 속성 짝 변경 (논리 모델 행)
        Fk c = fk("tw3");
        String[] r3 = duringCorrection(c, () -> {
            var current = logical.selectRelationshipPairs(c.f().user, c.relationship());
            logical.saveRelationshipPairs(c.f().user, c.relationship(), new LogicalModelDto.PairSave(current.modifiedAt(), List.of()));
            return null;
        });
        System.out.println("[M7-FK] 보정 ↔ 관계 속성 짝 변경 : " + Arrays.toString(r3));
        assertThat(r3[0]).isEqualTo("OK");
        assertThat(r3[2]).as("짝 변경은 보정이 끝날 때까지 기다린다").isNotNull();

        // 4) 자식 테이블 승인 (테이블 행)
        Fk d = fk("tw4");
        String[] r4 = duringCorrection(d, () -> {
            TableInfoDto dto = new TableInfoDto();
            dto.setId(d.team());
            tables.updateTableIsApproval(true, d.f().user, List.of(dto));
            return null;
        });
        System.out.println("[M7-FK] 보정 ↔ 자식 테이블 승인 : " + Arrays.toString(r4));
        assertThat(r4[0]).isEqualTo("OK");
        assertThat(r4[1]).isEqualTo("OK");
        assertThat(r4[2]).isNotNull();
        assertThat(jdbc.queryForObject("select APRV_YN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Boolean.class, d.team(), d.dept())).as("보정 FK 는 미승인 그대로").isFalse();

        // 5) 부모 PK 컬럼 삭제 (구조 변경 — 프로젝트 행에서 기다린 뒤 업무 검사 : 매핑된 컬럼)
        Fk e = fk("tw5");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", e.dept());
        long k1 = colId(e.dept(), "K1");
        String[] r5 = duringCorrection(e, () -> bulkSave(e.f(), e.dept(), List.of(k1), List.of()));
        System.out.println("[M7-FK] 보정 ↔ 부모 PK 삭제 : " + Arrays.toString(r5));
        assertThat(r5[0]).isEqualTo("OK");
        assertThat(r5[1]).isIn("RELATION_EXISTS", "MODEL_CONFLICT");
        assertThat(r5[2]).isNotNull().containsIgnoringCase("tb_prj");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where COL_SN = ?", Integer.class, k1)).isEqualTo(1);
    }

}
