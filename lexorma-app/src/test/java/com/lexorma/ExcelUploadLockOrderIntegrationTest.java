package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.apache.poi.ss.usermodel.Row;
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

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-U07 엑셀 업로드 잠금 순서 (docs/completed/column-name-uniqueness-m7.md 11장) : 업로드 전체가 한 트랜잭션이라 청크마다 ID 순으로 잠가도
 * 청크 사이에서는 순서가 뒤집힐 수 있었다 (업로드 A : 청크 1 = T1, 청크 2 = T2 / 업로드 B : 청크 1 = T2, 청크 2 = T1).
 * 테이블 행 잠금 호출(TableInfoRepository.findForUpdate)에 테스트 전용 대기점을 걸어, 각 업로드가 '두 번째 테이블' 을 잠그려는 순간 서로를 기다리게 한다 —
 * 순서가 뒤집히는 구현(고치기 전)이면 두 트랜잭션이 상대의 잠금을 기다리게 되어 PostgreSQL 이 교착(40P01)으로 하나를 끊었다. 파일 전체의 테이블을 먼저 ID 순으로
 * 잠그는 구현이면 둘 다 T1 → T2 순서라 한쪽이 T1 에서 기다릴 뿐 교착이 없다 (잠금 순서를 기록해 확인한다). sleep 으로 순서를 정하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ExcelUploadLockOrderIntegrationTest {

    /** 테스트 전용 대기점 : 스레드별로 '이 테이블을 잠그기 전에 대기점에서 상대를 기다린다' */
    static final Map<Thread, Long> WAIT_AT = new ConcurrentHashMap<>();
    static final Map<Thread, Boolean> TIMED_OUT = new ConcurrentHashMap<>();
    /** 업로드 스레드별 테이블 행 잠금 순서 (findForUpdate 의 테이블 ID) */
    static final Map<String, List<Long>> LOCK_LOG = new ConcurrentHashMap<>();
    static final ThreadLocal<String> LABEL = new ThreadLocal<>();
    static volatile CyclicBarrier barrier;

    @TestConfiguration
    static class LockHook {
        @Bean
        static BeanPostProcessor tableLockHook() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof TableInfoRepository)) return bean;
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), bean.getClass().getInterfaces(), (proxy, method, args) -> {
                        if (method.getName().equals("findForUpdate") && args != null && args.length == 2) {
                            String label = LABEL.get();
                            if (label != null) LOCK_LOG.computeIfAbsent(label, k -> Collections.synchronizedList(new ArrayList<>())).add((Long) args[0]);
                            Long target = WAIT_AT.get(Thread.currentThread());
                            if (target != null && target.equals(args[0]) && barrier != null) {
                                WAIT_AT.remove(Thread.currentThread());
                                try {
                                    barrier.await(2, TimeUnit.SECONDS);
                                } catch (TimeoutException | java.util.concurrent.BrokenBarrierException e) {
                                    TIMED_OUT.put(Thread.currentThread(), true);
                                }
                            }
                        }
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

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ColumnManageService columns;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
        WAIT_AT.clear();
        TIMED_OUT.clear();
        LOCK_LOG.clear();
        barrier = null;
    }

    private record Fx(GenerationFixture f, long t1, long t2, String n1, String n2, long domain) {
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "xlo-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order)));
        generation.apply(f.user, f.request(List.of(customer, order), plan.planToken()));
        long a = f.table(customer);
        long b = f.table(order);
        long t1 = Math.min(a, b);
        long t2 = Math.max(a, b);
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, f.term("XL_DOMAIN", "VARCHAR", 20));
        return new Fx(f, t1, t2, tableName(t1), tableName(t2), domain);
    }

    private String tableName(long table) {
        return jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table);
    }

    /** 엑셀은 컬럼명 = 표준 용어 약어로 용어를 찾는다 : 이름마다 용어 한 개 */
    private void terms(Fx x, List<String> names) {
        List<Object[]> rows = new ArrayList<>();
        for (String n : names) rows.add(new Object[]{GenerationFixture.nextId(), x.f().user.getProjectId(), n + "_" + System.nanoTime(), n, n, x.domain()});
        jdbc.batchUpdate("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", rows);
    }

    /** (테이블명, 컬럼명) 행들 */
    private static List<String[]> rows(String table, String prefix, int count) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new String[]{table, prefix + i});
        return rows;
    }

    private static byte[] workbook(List<String[]> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] v : rows) {
                List<String> values = Arrays.asList(v[0], v[1], "설명", "VARCHAR", "20", "0", "0", "Y", "", "9", "N", "", "N", "N", "N", "N", "N", "N");
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object upload(Fx x, byte[] file) {
        try {
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file), x.f().user);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    /** 결과 : OK · 오류 코드 · 잠금 오류(SQLState) */
    private static String outcome(Supplier<Object> work) {
        try {
            work.get();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException s) return "SQLSTATE:" + s.getSQLState();
            }
            return e.getClass().getSimpleName();
        }
    }

    /** 두 작업을 같은 순간에 시작 (각자 스레드·트랜잭션). waitAtA/B : 그 스레드가 이 테이블을 잠그기 직전 대기점에서 상대를 기다린다 */
    private List<String> together(Supplier<Object> a, Long waitAtA, Supplier<Object> b, Long waitAtB) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        barrier = new CyclicBarrier(2);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> runs = new ArrayList<>();
        List<Object[]> works = new ArrayList<>();
        works.add(new Object[]{a, waitAtA});
        works.add(new Object[]{b, waitAtB});
        for (int i = 0; i < works.size(); i++) {
            Object[] w = works.get(i);
            String label = i == 0 ? "A" : "B";
            @SuppressWarnings("unchecked") Supplier<Object> work = (Supplier<Object>) w[0];
            Long waitAt = (Long) w[1];
            runs.add(CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(login);
                LABEL.set(label);
                if (waitAt != null) WAIT_AT.put(Thread.currentThread(), waitAt);
                try {
                    start.await(15, TimeUnit.SECONDS);
                    return outcome(work);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "INTERRUPTED";
                } finally {
                    LABEL.remove();
                    WAIT_AT.remove(Thread.currentThread());
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> run : runs) results.add(run.get(60, TimeUnit.SECONDS));
        return results;
    }

    private int count(long table, String like) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM like ?", Integer.class, table, like);
    }

    @Test
    @DisplayName("A. 청크가 둘인 업로드 두 개가 두 테이블을 반대 순서로 : 각자 두 번째 테이블 잠금 직전에 서로를 기다리게 해도 교착 없이 둘 다 끝나고, 둘 다 ID 순으로 잠그며, 데이터는 빠짐·중복 없음 (3회 반복)")
    void multiChunkOppositeOrder() throws Exception {
        for (int round = 0; round < 3; round++) {
            Fx x = fixture("a" + round);
            String pa = "XA" + round + "_";
            String pb = "XB" + round + "_";
            List<String[]> fileA = new ArrayList<>(rows(x.n1(), pa, 500)); // 청크 1 = T1
            fileA.addAll(rows(x.n2(), pa, 20));                           // 청크 2 = T2
            List<String[]> fileB = new ArrayList<>(rows(x.n2(), pb, 500)); // 청크 1 = T2
            fileB.addAll(rows(x.n1(), pb, 20));                           // 청크 2 = T1
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                names.add(pa + i);
                names.add(pb + i);
            }
            terms(x, names);
            byte[] a = workbook(fileA);
            byte[] b = workbook(fileB);
            List<String> results = together(() -> upload(x, a), x.t2(), () -> upload(x, b), x.t1());
            assertThat(results).as("반복 " + round + " (교착이면 SQLSTATE:40P01)").containsExactly("OK", "OK");
            // 두 업로드 모두 업로드 전체의 테이블을 ID 순(T1 → T2)으로 잠근다 — 청크 순서(B 는 T2 가 먼저)와 무관
            assertThat(LOCK_LOG.get("A")).as("업로드 A 잠금 순서").containsExactly(x.t1(), x.t2());
            assertThat(LOCK_LOG.get("B")).as("업로드 B 잠금 순서").containsExactly(x.t1(), x.t2());
            LOCK_LOG.clear();
            assertThat(count(x.t1(), pa + "%")).isEqualTo(500);
            assertThat(count(x.t2(), pa + "%")).isEqualTo(20);
            assertThat(count(x.t2(), pb + "%")).isEqualTo(500);
            assertThat(count(x.t1(), pb + "%")).isEqualTo(20);
            TIMED_OUT.clear();
        }
    }

    @Test
    @DisplayName("B·F. 청크가 둘인 업로드 둘이 같은 테이블에 대소문자만 다른 이름 : 하나만 성공, 다른 하나는 SAVED_DATA_EXISTS 로 전체 롤백 (첫 청크도 남지 않음) / 뒤 청크의 빈 컬럼명은 전체 롤백")
    void sameNameAndLateFailure() throws Exception {
        Fx x = fixture("b");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 500; i++) names.add("YA_" + i);
        for (int i = 0; i < 500; i++) names.add("YB_" + i);
        names.add("DUP_X");
        terms(x, names);
        List<String[]> fileA = new ArrayList<>(rows(x.n1(), "YA_", 500));
        fileA.add(new String[]{x.n2(), "DUP_X"});
        List<String[]> fileB = new ArrayList<>(rows(x.n1(), "YB_", 500));
        fileB.add(new String[]{x.n2(), "dup_x"});
        byte[] a = workbook(fileA);
        byte[] b = workbook(fileB);
        List<String> results = together(() -> upload(x, a), null, () -> upload(x, b), null);
        assertThat(results).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
        assertThat(count(x.t1(), "YA\\_%") + count(x.t1(), "YB\\_%")).as("실패한 업로드의 첫 청크 500행도 남지 않는다").isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM_CMPR_VL = 'DUP_X'", Integer.class, x.t2())).isOne();

        // F : 첫 청크 정상, 뒤 청크에 빈 컬럼명 → 입력 오류, 전체 롤백
        List<String> zNames = new ArrayList<>();
        for (int i = 0; i < 500; i++) zNames.add("ZZ_" + i);
        terms(x, zNames);
        List<String[]> fileF = new ArrayList<>(rows(x.n1(), "ZZ_", 500));
        fileF.add(new String[]{x.n2(), "  "});
        int before = jdbc.queryForObject("select count(*) from TB_COL where TBL_SN in (?, ?)", Integer.class, x.t1(), x.t2());
        assertThat(outcome(() -> upload(x, workbook(fileF)))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN in (?, ?)", Integer.class, x.t1(), x.t2())).isEqualTo(before);
    }

    @Test
    @DisplayName("C·D. 겹치지 않는 테이블·다른 프로젝트의 업로드는 서로 기다리지 않고 둘 다 끝난다 (대기점 없이 동시에)")
    void independentUploads() throws Exception {
        Fx x = fixture("c");
        Fx y = fixture("d");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 520; i++) names.add("C_" + i);
        terms(x, names);
        terms(y, names);
        List<String[]> fileX1 = rows(x.n1(), "C_", 520);
        List<String[]> fileX2 = rows(x.n2(), "C_", 520);
        List<String[]> fileY = rows(y.n1(), "C_", 520);
        byte[] x1 = workbook(fileX1);
        byte[] x2 = workbook(fileX2);
        byte[] y1 = workbook(fileY);
        long t0 = System.nanoTime();
        assertThat(together(() -> upload(x, x1), null, () -> upload(x, x2), null)).containsExactly("OK", "OK");
        assertThat(together(() -> upload(x, workbook(Collections.singletonList(new String[]{x.n1(), "C_519"}))), null, () -> upload(y, y1), null))
                .as("같은 이름이라도 다른 프로젝트·이미 있는 행은 그 프로젝트 기준").containsExactlyInAnyOrder("SAVED_DATA_EXISTS", "OK");
        assertThat(count(y.t1(), "C\\_%")).isEqualTo(520);
        assertThat(count(x.t1(), "C\\_%")).isEqualTo(520);
        assertThat(count(x.t2(), "C\\_%")).isEqualTo(520);
        assertThat((System.nanoTime() - t0) / 1_000_000).as("교착·잠금 대기 제한 없이 끝난다").isLessThan(60_000);
    }
}
