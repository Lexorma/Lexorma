package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRenameDto.TargetType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-NAME-1 동시성 (docs/completed/physical-name-recommendation-m7.md 12장, 작업 지시 11장) : 실제 독립 연결·트랜잭션 두 개.
 * 잠금 순서·NOWAIT 정책은 바꾸지 않았다 — 일괄 적용은 프로젝트 행 배타(기다림) → 모델·스키마·테이블·컬럼 NOWAIT(바쁘면 409), 엑셀 업로드는 테이블 행(기다림).
 * 교착(40P01)·부분 적용·중복 컬럼명·승인 우회가 없어야 한다. 모든 대기는 15초 제한 (넘으면 실패).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PhysicalNameConcurrencyIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired PhysicalNameRenameService rename;
    @Autowired ColumnManageService columns;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** A형 통합 테이블 : 회원(PK MBR_NO, 회원 이름 MBR_NM) → 개인(생년월일 BRDT) · 기업(사업자 번호 BRNO). 생성 뒤 컬럼명을 O_ 로 바꿔 추천 가능 상태로 둔다 */
    record Fx(GenerationFixture f, long table, long nameColumn, long birthColumn, long bizColumn, long nameAttribute) {
    }

    Fx fixture(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "name1c-" + name);
        for (String[] w : new String[][]{{"회원", "MBR"}, {"개인", "PSN"}, {"기업", "CORP"}}) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), w[0], w[1], w[1], w[0]);
        }
        long member = f.entity("회원");
        long memberNo = f.attr(member, "회원 번호", true, "MBR_NO");
        f.pk(member, memberNo);
        long memberName = f.attr(member, "회원 이름", false, "MBR_NM");
        long type = f.attr(member, "회원 유형 코드", true, "MBR_TY_CD");
        long person = f.entity("개인 회원");
        long birth = f.attr(person, "생년월일", false, "BRDT");
        long corp = f.entity("기업 회원");
        long bizNo = f.attr(corp, "사업자 번호", false, "BRNO");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_MDL_SN = ?", f.modelId);
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, member, "회원 구분", null, false, true, type,
                List.of(new MemberInput(person, "P"), new MemberInput(corp, "C")), null));
        List<PhysicalGenerationDto.StrategyChoice> a = List.of(new PhysicalGenerationDto.StrategyChoice(member, "SINGLE_TABLE"));
        List<Long> all = List.of(member, person, corp);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(all, null, null, null, a));
        generation.apply(f.user, new PhysicalGenerationDto.Request(all, null, plan.planToken(), null, a));
        long table = f.table(member);
        long nameColumn = column(memberName);
        long birthColumn = column(birth);
        long bizColumn = column(bizNo);
        for (long c : List.of(nameColumn, birthColumn, bizColumn)) jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM where COL_SN = ?", c);
        return new Fx(f, table, nameColumn, birthColumn, bizColumn, memberName);
    }

    long column(long attribute) {
        return jdbc.queryForObject("select COL_SN from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", Long.class, attribute);
    }

    String name(long column) {
        return jdbc.queryForObject("select COL_NM from TB_COL where COL_SN = ?", String.class, column);
    }

    int sameKey(long table, String name) {
        return jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM_CMPR_VL = ?", Integer.class, table,
                com.lexorma.data.column.service.ColumnNameKey.of(name));
    }

    /** 미리보기 → 그 계획으로 적용하는 작업 (미리보기는 지금, 적용은 작업을 부를 때) */
    Supplier<Object> applyLater(Fx x, long column, String newName) {
        PhysicalNameRenameDto.Plan plan = rename.preview(x.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, column, newName, null)), null));
        assertThat(plan.applicable()).as(String.valueOf(plan.items())).isTrue();
        return () -> rename.apply(x.f().user, new PhysicalNameRenameDto.Request(List.of(new PhysicalNameRenameDto.Item(TargetType.COLUMN, column, newName,
                plan.items().get(0).modifiedAt())), plan.planToken()));
    }

    @Test
    @DisplayName("C-1 추천 조회(미리보기) 뒤 다른 트랜잭션이 그 컬럼명을 바꾸면 적용은 409 — 다른 쪽 이름 그대로, RENAME 버전 없음")
    void staleAfterOtherRename() {
        Fx x = fixture("c1");
        Supplier<Object> stale = applyLater(x, x.birthColumn(), "BRDT");
        jdbc.update("update TB_COL set COL_NM = 'BIRTH_X', MDFCN_DT = current_timestamp where COL_SN = ?", x.birthColumn());
        assertThat(code(stale)).isEqualTo("MODEL_CONFLICT");
        assertThat(name(x.birthColumn())).isEqualTo("BIRTH_X");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ? and VER_NM like 'RENAME-%'", Integer.class, x.f().user.getProjectId())).isZero();
    }

    @Test
    @DisplayName("C-2·C-4 같은 통합 테이블의 두 적용이 같은 새 이름 (A형 서브타입 컬럼 ↔ 루트 컬럼) : 둘째는 프로젝트 행을 기다린 뒤 409 — 이름은 하나, 교착 없음 (3회)")
    void sameNewNameInIntegratedTable() throws Exception {
        for (int i = 0; i < 3; i++) {
            Fx x = fixture("c2-" + i);
            // 루트 속성(회원 이름)의 용어 약어를 BRDT 로 : 두 컬럼 모두 BRDT 를 추천받는다 (한 요청에 함께 고르면 추천끼리 겹침, 따로 적용하면 경합)
            jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = 'BRDT' where STD_TRM_SN = (select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?)", x.nameAttribute());
            jdbc.update("update TB_COL set STD_TRM_SN = (select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?) where COL_SN = ?", x.nameAttribute(), x.nameColumn());
            Supplier<Object> sub = applyLater(x, x.birthColumn(), "BRDT");
            Supplier<Object> root = applyLater(x, x.nameColumn(), "BRDT");
            Outcome o = i % 2 == 0 ? ordered(sub, root) : ordered(root, sub);
            assertThat(o.first()).as("첫째 " + i).isEqualTo("OK");
            assertThat(o.secondWaited()).as("둘째는 프로젝트 행을 기다린다").isTrue();
            assertThat(o.second()).as("둘째 " + i).isEqualTo("MODEL_CONFLICT");
            assertThat(sameKey(x.table(), "BRDT")).isOne();
        }
    }

    @Test
    @DisplayName("C-3 같은 테이블의 두 적용이 서로 다른 이름 : 둘째는 기다린 뒤 그대로 성공 (계획이 바뀌지 않았다) — 두 이름 모두 반영")
    void differentNamesSameTable() throws Exception {
        Fx x = fixture("c3");
        Outcome o = ordered(applyLater(x, x.birthColumn(), "BRDT"), applyLater(x, x.bizColumn(), "BRNO"));
        assertThat(o.first()).isEqualTo("OK");
        assertThat(o.secondWaited()).isTrue();
        assertThat(o.second()).isEqualTo("OK");
        assertThat(List.of(name(x.birthColumn()), name(x.bizColumn()))).containsExactly("BRDT", "BRNO");
    }

    @Test
    @DisplayName("C-5 엑셀 등록 ↔ 추천 적용 : 업로드가 테이블 행을 쥐면 적용은 기다리지 않고 409, 적용이 먼저면 업로드는 기다린 뒤 같은 이름을 SAVED_DATA_EXISTS 로 거절 (전체 롤백)")
    void excelAndApply() throws Exception {
        Fx x = fixture("c5");
        String table = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, x.table());
        x.f().term("MEMO_CN", "VARCHAR", 20);
        // 업로드 먼저 : 적용은 프로젝트 행을 잡은 뒤 테이블 행을 NOWAIT 로 잡지 못해 409 (기다리지 않는다)
        Outcome uploadFirst = ordered(() -> upload(x, table, "MEMO_CN"), applyLater(x, x.birthColumn(), "BRDT"));
        assertThat(uploadFirst.first()).isEqualTo("OK");
        assertThat(uploadFirst.secondWaited()).as("적용은 테이블 행을 기다리지 않는다").isFalse();
        assertThat(uploadFirst.second()).isEqualTo("MODEL_CONFLICT");
        assertThat(name(x.birthColumn())).isEqualTo("O_BRDT");
        assertThat(sameKey(x.table(), "MEMO_CN")).isOne();

        // 적용 먼저 : 업로드는 테이블 행을 기다리고, 적용이 만든 이름과 대소문자만 다른 brdt 를 거절한다 (업로드 전체 롤백)
        Supplier<Object> again = applyLater(x, x.birthColumn(), "BRDT");
        int before = jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, x.table());
        Outcome applyFirst = ordered(again, () -> upload(x, table, "brdt"));
        assertThat(applyFirst.first()).isEqualTo("OK");
        assertThat(applyFirst.secondWaited()).isTrue();
        assertThat(applyFirst.second()).isEqualTo("SAVED_DATA_EXISTS");
        assertThat(sameKey(x.table(), "BRDT")).isOne();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, x.table())).isEqualTo(before);
    }

    @Test
    @DisplayName("C-6 승인 상태 변경 ↔ 추천 적용 : 미리보기 뒤 컬럼이 승인되면 그 계획의 적용은 409 (승인 우회 없음), 동시에 돌려도 교착·예외 없이 OK 또는 409 뿐")
    void approvalAndApply() throws Exception {
        Fx x = fixture("c6");
        Supplier<Object> stale = applyLater(x, x.birthColumn(), "BRDT");
        ColumnInfoDto target = new ColumnInfoDto();
        target.setId(x.birthColumn());
        columns.updateColumnApproval(x.f().user, List.of(target), "true");
        assertThat(code(stale)).isEqualTo("MODEL_CONFLICT");
        assertThat(name(x.birthColumn())).isEqualTo("O_BRDT");
        columns.updateColumnApproval(x.f().user, List.of(target), "false");

        for (int i = 0; i < 3; i++) {
            jdbc.update("update TB_COL set COL_NM = 'O_BRDT', APRV_YN = false where COL_SN = ?", x.birthColumn());
            Supplier<Object> apply = applyLater(x, x.birthColumn(), "BRDT");
            List<String> results = together(apply, () -> {
                columns.updateColumnApproval(x.f().user, List.of(target), "true");
                return null;
            });
            assertThat(results.get(1)).isEqualTo("OK");
            assertThat(results.get(0)).isIn("OK", "MODEL_CONFLICT");
            // 적용이 성공했다면 그 시점에는 미승인이었다 (승인은 그 뒤에 커밋) — 이름은 하나
            assertThat(sameKey(x.table(), "BRDT") + sameKey(x.table(), "O_BRDT")).isOne();
        }
    }

    // ------------------------------------------------------------------ 도구

    private Object upload(Fx x, String table, String column) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            List<String> values = List.of(table, column, "설명", "VARCHAR", "20", "0", "0", "Y", "", "9", "N", "", "N", "N", "N", "N", "N", "N");
            Row row = sheet.createRow(1);
            for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            workbook.write(out);
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "c.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray()), x.f().user);
            return null;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String code(Supplier<Object> work) {
        try {
            work.get();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        }
    }

    private record Outcome(boolean secondWaited, String second, String first) {
    }

    /** 첫째를 트랜잭션 안에서 실행하고 커밋 전에 잠금을 쥔 채 멈춘다. 그 사이 둘째를 다른 스레드·연결로 실행한다 (500ms 안에 안 끝나면 기다린 것) — ColumnInsertConcurrencyIntegrationTest 와 같은 방식 */
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
            assertThat(firstDone.await(15, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.setContext(login);
                try {
                    return code(second);
                } catch (RuntimeException e) {
                    return e.toString();
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
            release.countDown();
        }
    }

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
                    return code(work);
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
}
