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
 * PostgreSQL 실행이 기준이다 (H2 는 같은 결과를 확인하지만 잠금 구현이 다르다 — 잠금 대기 한도 등).
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
}
