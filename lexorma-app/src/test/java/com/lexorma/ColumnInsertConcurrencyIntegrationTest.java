package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Item;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Plan;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.PlanItem;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.Request;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyCode;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ApplyIssue;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRenameService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 물리명 추천 일괄 적용 2차 후속 (2026-11-21) : 컬럼 등록과 물리명 변경·컬럼 등록끼리의 이름 경쟁. 컬럼 단건 등록(insertColumn)·엑셀 업로드는 이제
 * 컬럼을 더할 테이블 행을 먼저 잠그고(기다림) 그 뒤 최신 상태로 이름 중복을 본다. 물리명 일괄 적용은 프로젝트 배타 뒤 그 행을 NOWAIT 로 잡는다.
 * 서로 다른 DB 연결의 두 트랜잭션을 실제로 겹친다 — 첫째는 작업을 마치고 커밋 전에 잠금을 쥔 채 멈추고, 그 사이 둘째를 보낸다 (SubtypeGenerationConcurrencyIntegrationTest 와 같은 방식).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ColumnInsertConcurrencyIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRenameService rename;
    @Autowired ColumnManageService columns;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 고객(O_CUST : O_CUST_NO PK, O_CUST_NM — 추천 CUST_NM) · 주문(O_ORD) */
    private record Fx(GenerationFixture f, long cust, long ord, long custNm, long custNmTerm) {
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "cic-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        f.pk(customer, f.attr(customer, "고객 번호", true, "CUST_NO"));
        f.attr(customer, "고객 명", false, "CUST_NM");
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(customer, order)));
        generation.apply(f.user, f.request(List.of(customer, order), plan.planToken()));
        long projectId = f.user.getProjectId();
        jdbc.update("update TB_TBL set TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM, TBL_NM = 'O_' || TBL_NM where PRJ_SN = ?", projectId);
        long cust = f.table(customer);
        long custNm = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = 'O_CUST_NM'", Long.class, cust);
        long term = jdbc.queryForObject("select STD_TRM_SN from TB_COL where COL_SN = ?", Long.class, custNm);
        return new Fx(f, cust, f.table(order), custNm, term);
    }

    private Object insert(Fx x, long table, String name, long term) {
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(table);
        dto.setTermId(term);
        dto.setColumnName(name);
        dto.setColumnDesc(name);
        dto.setDataType("VARCHAR");
        dto.setMaxLength(20);
        dto.setIsPk("N");
        dto.setIsNullable("Y");
        dto.setIsMasterData("N");
        dto.setIsRequired("N");
        dto.setIsSensitive("N");
        dto.setIsUnique("N");
        dto.setIsIndex("N");
        dto.setIsEncrypted("N");
        columns.insertColumn(x.f().user, dto);
        return null;
    }

    private Plan renamePlan(Fx x) {
        Plan plan = rename.preview(x.f().user, new Request(List.of(new Item(TargetType.COLUMN, x.custNm(), "CUST_NM", null)), null));
        assertThat(plan.applicable()).as(plan.items().toString()).isTrue();
        return plan;
    }

    private Object apply(Fx x, Plan plan) {
        PlanItem p = plan.items().get(0);
        return rename.apply(x.f().user, new Request(List.of(new Item(TargetType.COLUMN, x.custNm(), "CUST_NM", p.modifiedAt())), plan.planToken()));
    }

    private int named(long table, String name) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and upper(COL_NM) = ?", Integer.class, table, name);
    }

    private int columnsOf(long table) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, table);
    }

    private int history(Fx x) {
        return x.f().count("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", x.f().user.getProjectId());
    }

    // ------------------------------------------------------------------ 동시 실행 도구

    private record Outcome(boolean secondWaited, String second, String first) {
    }

    /**
     * 첫째를 트랜잭션 안에서 실행하고 커밋 전에 잠금을 쥔 채 멈춘다(latch). 그 사이 둘째를 다른 스레드·연결로 실행한다.
     * 500ms 안에 둘째가 끝나지 않으면 잠금을 기다린 것이다. 첫째를 커밋한 뒤 둘째의 결과를 받는다 (모두 15초 제한 — 교착·무한 대기면 실패)
     */
    private Outcome ordered(Supplier<Object> first, Supplier<Object> second) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> {
            SecurityContextHolder.setContext(login);
            try {
                return tx.execute(status -> {
                    String result;
                    try {
                        first.get();
                        result = "OK";
                    } catch (CustomException e) {
                        status.setRollbackOnly();
                        result = e.getErrorCode().name();
                    } catch (RuntimeException e) {
                        status.setRollbackOnly();
                        result = e.toString();
                    }
                    firstDone.countDown();
                    try {
                        release.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return result;
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        try {
            assertThat(firstDone.await(15, TimeUnit.SECONDS)).as("첫째가 작업을 마쳤다").isTrue();
            CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(login);
                try {
                    second.get();
                    return "OK";
                } catch (CustomException e) {
                    return e.getErrorCode().name();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            boolean waited;
            try {
                waiter.get(500, TimeUnit.MILLISECONDS);
                waited = false;
            } catch (java.util.concurrent.TimeoutException e) {
                waited = true;
            }
            release.countDown();
            String firstResult = holder.get(15, TimeUnit.SECONDS);
            return new Outcome(waited, waiter.get(15, TimeUnit.SECONDS), firstResult);
        } finally {
            release.countDown(); // 실패해도 첫째 트랜잭션을 끝낸다 (연결 정리)
        }
    }

    // ------------------------------------------------------------------ 등록 ↔ 물리명 변경

    @Test
    @DisplayName("T-01 컬럼 등록이 먼저 테이블 행을 쥐면 같은 이름으로 바꾸는 일괄 적용은 기다리지 않고 409 — 커밋 뒤 이름은 하나, 다시 미리보기는 이름 충돌")
    void insertThenRename() throws Exception {
        Fx x = fixture("t01");
        Plan plan = renamePlan(x);
        int versions = x.f().versions();
        Outcome o = ordered(() -> insert(x, x.cust(), "CUST_NM", x.custNmTerm()), () -> apply(x, plan));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).as("적용은 NOWAIT").isFalse();
        assertThat(o.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(named(x.cust(), "CUST_NM")).isOne();
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, x.custNm())).isEqualTo("O_CUST_NM");
        assertThat(x.f().versions()).isEqualTo(versions);
        assertThat(rename.preview(x.f().user, new Request(List.of(new Item(TargetType.COLUMN, x.custNm(), "CUST_NM", null)), null)).items().get(0)
                .applicability().blockers()).extracting(ApplyIssue::code).containsExactly(ApplyCode.NAME_CONFLICT);
    }

    @Test
    @DisplayName("T-02 물리명 변경이 먼저면 같은 이름의 컬럼 등록은 테이블 행을 기다린 뒤 최신 상태로 중복을 보고 거절 (SAVED_DATA_EXISTS) — 이름은 하나")
    void renameThenInsert() throws Exception {
        Fx x = fixture("t02");
        Plan plan = renamePlan(x);
        int before = columnsOf(x.cust());
        Outcome o = ordered(() -> apply(x, plan), () -> insert(x, x.cust(), "CUST_NM", x.custNmTerm()));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).as("등록은 테이블 행을 기다린다").isTrue();
        assertThat(o.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(named(x.cust(), "CUST_NM")).isOne();
        assertThat(columnsOf(x.cust())).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 등록 ↔ 등록

    @Test
    @DisplayName("T-03 같은 테이블에 같은 이름을 동시에 등록하면 둘째는 기다린 뒤 거절 — 컬럼 하나만 늘고 이름은 하나")
    void insertTwice() throws Exception {
        Fx x = fixture("t03");
        int before = columnsOf(x.cust());
        long term = x.f().term("CUST_ADDR", "VARCHAR", 20);
        Outcome o = ordered(() -> insert(x, x.cust(), "CUST_ADDR", term), () -> insert(x, x.cust(), "CUST_ADDR", term));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).isTrue();
        assertThat(o.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(columnsOf(x.cust())).isEqualTo(before + 1);
        assertThat(named(x.cust(), "CUST_ADDR")).isOne();
    }

    @Test
    @DisplayName("T-04 서로 다른 테이블에 동시에 등록하면 기다리지 않는다 (테이블 단위 잠금 — 같은 이름이라도 다른 테이블은 허용)")
    void differentTables() throws Exception {
        Fx x = fixture("t04");
        long term = x.f().term("ETC_CD", "VARCHAR", 20);
        Outcome o = ordered(() -> insert(x, x.cust(), "ETC_CD", term), () -> insert(x, x.ord(), "ETC_CD", term));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).as("다른 테이블은 서로 기다리지 않는다").isFalse();
        assertThat(o.second()).isEqualTo("OK");
        assertThat(named(x.cust(), "ETC_CD")).isOne();
        assertThat(named(x.ord(), "ETC_CD")).isOne();
    }

    @Test
    @DisplayName("T-05 중복으로 거절된 등록은 아무것도 남기지 않는다 (컬럼·이력 그대로)")
    void rejectedInsertLeavesNothing() {
        Fx x = fixture("t05");
        long term = x.f().term("CUST_ADDR", "VARCHAR", 20);
        insert(x, x.cust(), "CUST_ADDR", term);
        int before = columnsOf(x.cust());
        int historyBefore = history(x);
        String result;
        try {
            insert(x, x.cust(), "CUST_ADDR", term);
            result = "OK";
        } catch (CustomException e) {
            result = e.getErrorCode().name();
        }
        assertThat(result).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(columnsOf(x.cust())).isEqualTo(before);
        assertThat(history(x)).isEqualTo(historyBefore);
    }

    // ------------------------------------------------------------------ 엑셀 업로드 ↔ 물리명 변경

    private Object upload(Fx x, String tableName, String columnName) {
        List<String> values = new ArrayList<>(List.of(tableName, columnName, "설명 " + columnName, "VARCHAR", "20", "0", "0", "Y", "", "9",
                "N", "", "N", "N", "N", "N", "N", "N"));
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            Row row = sheet.createRow(1);
            for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            workbook.write(out);
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()),
                    x.f().user);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    @Test
    @DisplayName("엑셀 업로드도 같은 계약 : 업로드가 먼저면 일괄 적용 409, 일괄 적용이 먼저면 업로드는 기다린 뒤 거절 — 이름은 하나")
    void uploadAndRename() throws Exception {
        Fx a = fixture("xl-a");
        Plan planA = renamePlan(a);
        Outcome first = ordered(() -> upload(a, "O_CUST", "CUST_NM"), () -> apply(a, planA));
        assertThat(first.first()).isEqualTo("OK");
        assertThat(first.secondWaited()).isFalse();
        assertThat(first.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(named(a.cust(), "CUST_NM")).isOne();

        Fx b = fixture("xl-b");
        Plan planB = renamePlan(b);
        Outcome second = ordered(() -> apply(b, planB), () -> upload(b, "O_CUST", "CUST_NM"));
        assertThat(second.first()).isEqualTo("OK");
        assertThat(second.secondWaited()).isTrue();
        assertThat(second.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(named(b.cust(), "CUST_NM")).isOne();
    }

    // ------------------------------------------------------------------ M-7 컬럼명 UNIQUE (M7-U01) : 대소문자·공백만 다른 이름의 경쟁

    /** 두 작업을 각자 스레드·트랜잭션에서 같은 순간에 시작한다 (시작 신호 하나 — 순서를 sleep 으로 정하지 않는다). 결과 = OK 또는 오류 코드 (15초 제한 — 교착이면 실패) */
    private List<String> together(Supplier<Object> a, Supplier<Object> b) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> runs = new ArrayList<>();
        for (Supplier<Object> work : List.of(a, b)) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(login);
                try {
                    ready.countDown();
                    start.await(15, TimeUnit.SECONDS);
                    work.get();
                    return "OK";
                } catch (CustomException e) {
                    return e.getErrorCode().name();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "INTERRUPTED";
                } catch (RuntimeException e) {
                    return e.toString();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> run : runs) results.add(run.get(15, TimeUnit.SECONDS));
        return results;
    }

    private int sameKey(long table, String name) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM_CMPR_VL = ?", Integer.class, table,
                com.lexorma.data.column.service.ColumnNameKey.of(name));
    }

    @Test
    @DisplayName("U-01 같은 테이블에 CUSTOMER_ID · customer_id 를 같은 순간에 등록 : 하나만 성공, 다른 하나는 SAVED_DATA_EXISTS — DB 에는 하나 (5회 반복)")
    void caseVariantsAtOnce() throws Exception {
        Fx x = fixture("u01");
        long term = x.f().term("CUSTOMER_ID", "VARCHAR", 20);
        for (int i = 0; i < 5; i++) {
            String upper = "CUSTOMER_ID" + i;
            String lower = " customer_id" + i;
            List<String> results = together(() -> insert(x, x.cust(), upper, term), () -> insert(x, x.cust(), lower, term));
            assertThat(results).as("반복 " + i).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
            assertThat(sameKey(x.cust(), upper)).isOne();
        }
    }

    @Test
    @DisplayName("U-02 먼저 등록이 테이블 행을 쥐면 대소문자만 다른 등록은 기다린 뒤 거절, 다른 이름은 기다린 뒤 저장, 다른 테이블의 같은 이름은 기다리지 않고 저장")
    void orderedCaseVariants() throws Exception {
        Fx x = fixture("u02");
        long term = x.f().term("CUST_ADDR", "VARCHAR", 20);
        Outcome dup = ordered(() -> insert(x, x.cust(), "CUST_ADDR", term), () -> insert(x, x.cust(), "cust_addr", term));
        assertThat(dup.first()).isEqualTo("OK");
        assertThat(dup.secondWaited()).isTrue();
        assertThat(dup.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(sameKey(x.cust(), "CUST_ADDR")).isOne();

        Outcome other = ordered(() -> insert(x, x.cust(), "CUST_ADDR2", term), () -> insert(x, x.cust(), "CUST_ADDR3", term));
        assertThat(other.secondWaited()).as("같은 테이블은 이름이 달라도 차례로").isTrue();
        assertThat(List.of(other.first(), other.second())).containsExactly("OK", "OK");

        Outcome tables = ordered(() -> insert(x, x.cust(), "CUST_TEL", term), () -> insert(x, x.ord(), "cust_tel", term));
        assertThat(tables.secondWaited()).isFalse();
        assertThat(List.of(tables.first(), tables.second())).containsExactly("OK", "OK");
    }

    @Test
    @DisplayName("U-03 물리명 일괄 적용 ↔ 대소문자만 다른 등록 : 적용이 먼저면 등록은 기다린 뒤 거절, 등록이 먼저면 적용은 기다리지 않고 409 — 이름은 하나, 거절된 쪽은 아무것도 남기지 않는다")
    void renameAndCaseVariant() throws Exception {
        Fx a = fixture("u03a");
        Plan planA = renamePlan(a);
        int before = columnsOf(a.cust());
        Outcome renameFirst = ordered(() -> apply(a, planA), () -> insert(a, a.cust(), "cust_nm", a.custNmTerm()));
        assertThat(renameFirst.first()).isEqualTo("OK");
        assertThat(renameFirst.secondWaited()).isTrue();
        assertThat(renameFirst.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(sameKey(a.cust(), "CUST_NM")).isOne();
        assertThat(columnsOf(a.cust())).isEqualTo(before);

        Fx b = fixture("u03b");
        Plan planB = renamePlan(b);
        int versions = b.f().versions();
        Outcome insertFirst = ordered(() -> insert(b, b.cust(), " cust_nm ", b.custNmTerm()), () -> apply(b, planB));
        assertThat(insertFirst.first()).isEqualTo("OK");
        assertThat(insertFirst.secondWaited()).isFalse();
        assertThat(insertFirst.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(sameKey(b.cust(), "CUST_NM")).isOne();
        assertThat(jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, b.custNm())).isEqualTo("O_CUST_NM");
        assertThat(b.f().versions()).as("거절된 적용은 모델 버전을 남기지 않는다").isEqualTo(versions);
        assertThat(rename.preview(b.f().user, new Request(List.of(new Item(TargetType.COLUMN, b.custNm(), "CUST_NM", null)), null)).items().get(0)
                .applicability().blockers()).extracting(ApplyIssue::code).containsExactly(ApplyCode.NAME_CONFLICT);
    }

    private Object bulkAdd(Fx x, long term) {
        com.lexorma.data.column.domain.dto.ColumnBulkSaveDto request = new com.lexorma.data.column.domain.dto.ColumnBulkSaveDto();
        request.setTableInfoId(x.cust());
        com.lexorma.data.column.domain.dto.ColumnBulkSaveDto.Row row = new com.lexorma.data.column.domain.dto.ColumnBulkSaveDto.Row();
        row.setKey("r1");
        row.setTermId(term);
        row.setColumnDesc("설명");
        row.setDataType("VARCHAR");
        row.setMaxLength(20);
        for (java.util.function.Consumer<String> flag : List.<java.util.function.Consumer<String>>of(row::setIsPk, row::setIsRequired, row::setIsUnique, row::setIsIndex,
                row::setIsMasterData, row::setIsSensitive, row::setIsEncrypted)) flag.accept("N");
        row.setIsNullable("Y");
        request.setRows(new ArrayList<>(List.of(row)));
        com.lexorma.data.column.domain.dto.ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(x.f().user, request);
        if (!result.result()) throw new CustomException(com.lexorma.common.exception.domain.ErrorCode.SAVED_DATA_EXISTS, String.valueOf(result.errors()));
        return null;
    }

    @Test
    @DisplayName("U-04 일괄 저장 ↔ 단건 등록 (같은 테이블, 대소문자만 다른 이름) : 둘째는 테이블 행을 기다린 뒤 최신 상태로 거절 — 어느 쪽이 먼저든 이름은 하나")
    void bulkAndInsert() throws Exception {
        Fx x = fixture("u04");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.cust());
        long upper = x.f().term("CUST_MEMO", "VARCHAR", 20);
        Outcome bulkFirst = ordered(() -> bulkAdd(x, upper), () -> insert(x, x.cust(), "cust_memo", upper));
        assertThat(bulkFirst.first()).isEqualTo("OK");
        assertThat(bulkFirst.secondWaited()).isTrue();
        assertThat(bulkFirst.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(sameKey(x.cust(), "CUST_MEMO")).isOne();

        long lower = x.f().term("cust_note", "VARCHAR", 20);
        Outcome insertFirst = ordered(() -> insert(x, x.cust(), "CUST_NOTE", lower), () -> bulkAdd(x, lower));
        assertThat(insertFirst.first()).isEqualTo("OK");
        assertThat(insertFirst.secondWaited()).isTrue();
        assertThat(insertFirst.second()).as("일괄 저장은 행 오류로 아무것도 저장하지 않는다").isEqualTo("SAVED_DATA_EXISTS");
        assertThat(sameKey(x.cust(), "CUST_NOTE")).isOne();
    }

    private Object uploadRows(Fx x, String[]... rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] v : rows) {
                List<String> values = List.of(v[0], v[1], "설명 " + v[1], "VARCHAR", "20", "0", "0", "Y", "", "9", "N", "", "N", "N", "N", "N", "N", "N");
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            workbook.write(out);
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()),
                    x.f().user);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    @Test
    @DisplayName("U-05 두 테이블에 걸친 엑셀 업로드 둘을 반대 행 순서로 동시에 : 테이블 행을 ID 순으로 잠가 교착 없이 둘 다 끝나고, 같은 비교 이름이 겹치면 하나만 성공 (단건 등록과 경쟁해도 같다)")
    void uploadsAcrossTables() throws Exception {
        Fx x = fixture("u05");
        // 엑셀은 컬럼명 = 표준 용어 약어로 용어를 찾는다
        for (String abbreviation : List.of("CUST_ETC", "ORD_ETC", "CUST_ETC2", "ORD_ETC2", "CUST_X", "ORD_X", "ORD_Y", "CUST_Z")) x.f().term(abbreviation, "VARCHAR", 20);
        List<String> distinct = together(() -> uploadRows(x, new String[]{"O_ORD", "ORD_ETC"}, new String[]{"O_CUST", "CUST_ETC"}),
                () -> uploadRows(x, new String[]{"O_CUST", "CUST_ETC2"}, new String[]{"O_ORD", "ORD_ETC2"}));
        assertThat(distinct).containsExactly("OK", "OK");
        List<String> clash = together(() -> uploadRows(x, new String[]{"O_ORD", "ORD_X"}, new String[]{"O_CUST", "CUST_X"}),
                () -> uploadRows(x, new String[]{"O_CUST", "cust_x"}, new String[]{"O_ORD", "ORD_Y"}));
        assertThat(clash).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
        assertThat(sameKey(x.cust(), "CUST_X")).isOne();
        assertThat(sameKey(x.ord(), "ORD_X") + sameKey(x.ord(), "ORD_Y")).as("실패한 업로드는 다른 테이블 행도 남기지 않는다").isOne();
        List<String> withInsert = together(() -> uploadRows(x, new String[]{"O_CUST", "CUST_Z"}), () -> insert(x, x.cust(), "cust_z", x.custNmTerm()));
        assertThat(withInsert).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
        assertThat(sameKey(x.cust(), "CUST_Z")).isOne();
    }

    @Test
    @DisplayName("U-07 (M7-U06) 확장 대문자로만 같은 이름을 같은 순간에 : A STRAßE ↔ STRASSE · B ﬁ ↔ FI 는 같은 테이블에서 하나만 성공(SAVED_DATA_EXISTS), C 다른 테이블은 둘 다 성공, "
            + "D 엑셀 업로드 ↔ 단건 등록도 하나만 성공하고 실패한 업로드는 아무것도 남기지 않는다 (각 3회)")
    void unicodeVariantsAtOnce() throws Exception {
        Fx x = fixture("u07");
        long term = x.f().term("UNI_NM", "VARCHAR", 20);
        for (int i = 0; i < 3; i++) {
            String n = String.valueOf(i);
            assertThat(together(() -> insert(x, x.cust(), "STRAßE" + n, term), () -> insert(x, x.cust(), "STRASSE" + n, term)))
                    .as("A " + i).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
            assertThat(sameKey(x.cust(), "STRASSE" + n)).isOne();
            assertThat(together(() -> insert(x, x.cust(), "ﬁ_" + n, term), () -> insert(x, x.cust(), "FI_" + n, term)))
                    .as("B " + i).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
            assertThat(sameKey(x.cust(), "FI_" + n)).isOne();
            assertThat(together(() -> insert(x, x.cust(), "STRAßE_T" + n, term), () -> insert(x, x.ord(), "strasse_t" + n, term)))
                    .as("C " + i).containsExactly("OK", "OK");
        }
        for (int i = 0; i < 3; i++) {
            String sharp = "STRAßE_E" + i;
            String n = String.valueOf(i);
            x.f().term(sharp, "VARCHAR", 20);
            x.f().term("ORD_UNI" + i, "VARCHAR", 20);
            int ordBefore = columnsOf(x.ord());
            List<String> d = together(() -> uploadRows(x, new String[]{"O_ORD", "ORD_UNI" + n}, new String[]{"O_CUST", sharp}),
                    () -> insert(x, x.cust(), "strasse_e" + n, term));
            assertThat(d).as("D " + i).containsExactlyInAnyOrder("OK", "SAVED_DATA_EXISTS");
            assertThat(sameKey(x.cust(), sharp)).isOne();
            if (d.get(0).equals("SAVED_DATA_EXISTS")) assertThat(columnsOf(x.ord())).as("실패한 업로드는 다른 테이블 행도 남기지 않는다").isEqualTo(ordBefore);
        }
    }

    @Test
    @DisplayName("U-06 (M7-U07) 엑셀 업로드 ↔ 일괄 저장·컬럼 삭제 : 업로드가 테이블 행을 쥔 동안 둘째는 기다린다 — 일괄 저장은 최신 상태로 대소문자만 다른 이름을 거절, 삭제는 끝난 뒤 성공")
    void uploadAndBulkOrDelete() throws Exception {
        Fx x = fixture("u06");
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.cust());
        // 용어는 소문자 약어 하나 : 엑셀의 'CUST_Q' 는 대소문자 무시로 이 용어를 찾고 (M7-NAME-1 — 대문자 용어를 함께 두면 같은 약어 두 개라 모호성 오류), 일괄 저장은 'cust_q' 를 더하려 한다
        long lower = x.f().term("cust_q", "VARCHAR", 20);
        long term = lower;
        Outcome bulk = ordered(() -> uploadRows(x, new String[]{"O_CUST", "CUST_Q"}), () -> bulkAdd(x, lower));
        assertThat(bulk.first()).isEqualTo("OK");
        assertThat(bulk.secondWaited()).isTrue();
        assertThat(bulk.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(sameKey(x.cust(), "CUST_Q")).isOne();

        x.f().term("CUST_R", "VARCHAR", 20);
        long victim = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = 'CUST_Q'", Long.class, x.cust());
        jdbc.update("update TB_COL set APRV_YN = false where COL_SN = ?", victim);
        com.lexorma.data.column.domain.dto.ColumnInfoDto target = new com.lexorma.data.column.domain.dto.ColumnInfoDto();
        target.setId(victim);
        Outcome delete = ordered(() -> uploadRows(x, new String[]{"O_CUST", "CUST_R"}), () -> {
            columns.deleteColumn(x.f().user, List.of(target));
            return null;
        });
        assertThat(delete.first()).isEqualTo("OK");
        assertThat(delete.secondWaited()).as("삭제는 테이블 행을 기다린다").isTrue();
        assertThat(delete.second()).isEqualTo("OK");
        assertThat(sameKey(x.cust(), "CUST_Q")).isZero();
        assertThat(sameKey(x.cust(), "CUST_R")).isOne();
        assertThat(term).isPositive();
    }
}
