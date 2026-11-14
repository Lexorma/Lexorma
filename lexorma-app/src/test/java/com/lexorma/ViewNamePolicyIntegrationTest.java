package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.service.PhysicalViewService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-15 안정화 : D-25 TABLE/VIEW 이름 유일성(2026-11-13 프로젝트 단위 → 스키마 단위, 모든 이름 경로), D-26 승인 대기 이름 비선점·승인 시 재검증,
 * 동시 승인·등록·이름 변경·삭제가 겹쳐도 확정 모델의 이름이 겹치지 않고 일부만 반영되지 않는다.
 * H2 와 PostgreSQL(SPRING_DATASOURCE_URL 을 주고 전체 테스트를 돌릴 때) 모두에서 같은 기대값이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ViewNamePolicyIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired PhysicalViewService viewService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("D-25 (2026-11-13 변경) : TABLE·VIEW 이름은 스키마 안에서 하나 (같은 스키마의 TABLE·VIEW 교차 포함), 다른 스키마에는 같은 이름이 된다 (등록·이동·엑셀 업로드)")
    void schemaScopedNamespace() throws Exception {
        AccountDto admin = loginAsAdmin("m15s-ns");
        long projectId = admin.getProjectId();
        SchemaDto a = schemaService.saveSchema(admin, new SchemaDto(null, "SCHEMA_A", null, false, 0L, null, null, null, null));
        SchemaDto b = schemaService.saveSchema(admin, new SchemaDto(null, "SCHEMA_B", null, false, 0L, null, null, null, null));
        table(projectId, a.id(), "TB_USER");
        // 다른 스키마 : SCHEMA_B.VW_USER, SCHEMA_A.VW_USER, SCHEMA_B 의 VIEW TB_USER 모두 된다
        ViewDto.SaveResult vwUserB = viewService.create(admin, new ViewDto.Save(b.id(), "VW_USER", null, null, "SELECT 1", List.of(), List.of()));
        ViewDto.SaveResult vwUserA = viewService.create(admin, new ViewDto.Save(a.id(), "vw_user", null, null, "SELECT 1", List.of(), List.of()));
        viewService.create(admin, new ViewDto.Save(b.id(), "TB_USER", null, null, "SELECT 1", List.of(), List.of()));
        // 같은 스키마 : VIEW ↔ VIEW, TABLE ↔ VIEW 는 안 된다
        assertInvalid(() -> viewService.create(admin, new ViewDto.Save(b.id(), "VW_USER", null, null, "SELECT 1", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        assertInvalid(() -> viewService.create(admin, new ViewDto.Save(a.id(), "TB_USER", null, null, "SELECT 1", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        // 스키마를 옮기는 수정 : 옮길 스키마에 같은 이름이 있으면 안 되고, 없으면 된다 (자기 이름은 그대로 둘 수 있다)
        viewService.updateApproval(admin, vwUserB.id(), false);
        assertInvalid(() -> viewService.update(admin, vwUserB.id(), new ViewDto.Save(a.id(), "VW_USER", null, null, "SELECT 1", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        viewService.update(admin, vwUserB.id(), new ViewDto.Save(null, "VW_USER", null, null, "SELECT 1", List.of(), List.of()));
        assertThat(jdbc.queryForObject("select SCHM_SN from TB_VIEW where VIEW_SN = ?", Long.class, vwUserB.id())).isEqualTo(schemaService.resolveSchema(projectId, null).getId());
        assertThat(vwUserA.id()).isNotNull();

        // 엑셀 업로드(기본 스키마) : 기본 스키마의 VIEW 가 쓰는 이름의 행은 건너뛰고, 다른 스키마에만 있는 이름은 등록된다
        tableDesignService.uploadTableExcelFile(excel(List.of(new String[]{"vw_user", "기본 스키마의 VIEW 와 같은 이름", "N", "1"},
                new String[]{"TB_USER", "SCHEMA_A 에만 있는 이름", "N", "2"}, new String[]{"TB_FROM_EXCEL", "엑셀 테이블", "N", "3"})), admin);
        assertThat(tableInfoRepository.findAllByTableNameAndProjectId("VW_USER", projectId)).isEmpty();
        assertThat(tableInfoRepository.findAllByTableNameAndProjectId("TB_USER", projectId)).hasSize(2);
        assertThat(tableInfoRepository.findAllByTableNameAndProjectId("TB_FROM_EXCEL", projectId)).hasSize(1);
        // 엑셀로 들어온 테이블 이름은 같은 스키마(기본)의 VIEW 가 쓸 수 없고, 다른 스키마에서는 쓸 수 있다
        assertInvalid(() -> viewService.create(admin, new ViewDto.Save(null, "TB_FROM_EXCEL", null, null, "SELECT 1", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        viewService.create(admin, new ViewDto.Save(a.id(), "TB_FROM_EXCEL", null, null, "SELECT 1", List.of(), List.of()));
    }

    @Test
    @DisplayName("D-26 : 변경 요청은 이름을 선점하지 않는다. 그 이름이 먼저 확정되면 승인이 거절되고 요청·확정 정의가 그대로이며, 반려 후 다른 이름으로 다시 요청할 수 있다")
    void pendingNameIsNotReserved() {
        AccountDto admin = loginAsAdmin("m15s-reserve");
        long projectId = admin.getProjectId();
        ViewDto.SaveResult user = viewService.create(admin, save("VW_USER", "SELECT 1"));
        AccountDto member = member(projectId);
        Long request = viewService.update(member, user.id(), save("VW_MEMBER", "SELECT 2")).request().id();

        // 요청이 있어도 그 이름으로 다른 VIEW·테이블을 등록할 수 있다 (막지 않는다)
        login(projectId, admin.getId());
        ViewDto.SaveResult taken = viewService.create(admin, save("VW_MEMBER", "SELECT 3"));
        assertThatThrownBy(() -> viewService.processRequest(admin, request, true)).isInstanceOf(CustomException.class)
                .hasMessageContaining("요청 뒤에 같은 이름")
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.SAVED_DATA_EXISTS));
        // 확정 정의·요청 상태·다른 VIEW 모두 그대로
        assertThat(viewService.detail(admin, user.id()).view()).satisfies(v -> {
            assertThat(v.viewName()).isEqualTo("VW_USER");
            assertThat(v.definition()).isEqualTo("SELECT 1");
        });
        assertThat(viewService.detail(admin, user.id()).requests()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("REQUESTED"));
        assertThat(viewService.detail(admin, taken.id()).view().definition()).isEqualTo("SELECT 3");

        // 재시도 : 반려 → 다른 이름으로 요청 → 승인
        viewService.processRequest(admin, request, false);
        Long retry = viewService.update(member(projectId), user.id(), save("VW_MEMBER2", "SELECT 2")).request().id();
        login(projectId, admin.getId());
        viewService.processRequest(admin, retry, true);
        assertThat(viewService.detail(admin, user.id()).view().viewName()).isEqualTo("VW_MEMBER2");
    }

    @Test
    @DisplayName("동시성 : 같은 이름으로의 변경 요청 둘의 동시 승인, 승인과 같은 이름의 VIEW 등록·테이블 이름 변경, 같은 요청의 동시 승인 — 확정 모델에 같은 이름이 둘 생기지 않는다")
    void concurrentApprovalsKeepNamesUnique() throws Exception {
        AccountDto admin = loginAsAdmin("m15s-race");
        long projectId = admin.getProjectId();
        String adminId = admin.getId();

        // 1) 서로 다른 VIEW 가 같은 이름으로 변경 요청 → 동시 승인
        Long p = viewService.create(admin, save("VW_P", "SELECT 1")).id();
        Long q = viewService.create(admin, save("VW_Q", "SELECT 1")).id();
        AccountDto member = member(projectId);
        Long requestP = viewService.update(member, p, save("VW_SAME", "SELECT 'p'")).request().id();
        Long requestQ = viewService.update(member, q, save("VW_SAME", "SELECT 'q'")).request().id();
        assertThat(race(List.of(
                () -> viewService.processRequest(login(projectId, adminId), requestP, true),
                () -> viewService.processRequest(login(projectId, adminId), requestQ, true)))).containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS");
        assertThat(count(projectId, "VW_SAME")).isEqualTo(1);
        // 실패한 쪽은 원래 이름·정의 그대로 (일부만 반영되지 않는다)
        List<String> rows = jdbc.queryForList("select VIEW_NM || ':' || VIEW_DFN_CN from TB_VIEW where PRJ_SN = ? order by VIEW_NM", String.class, projectId);
        assertThat(rows).isIn(List.of("VW_Q:SELECT 1", "VW_SAME:SELECT 'p'"), List.of("VW_P:SELECT 1", "VW_SAME:SELECT 'q'"));

        // 2) 변경 요청 승인 ↔ 같은 이름의 VIEW 등록 ↔ 같은 이름으로의 테이블 이름 변경
        Long r = viewService.create(login(projectId, adminId), save("VW_R", "SELECT 1")).id();
        TableInfo table = table(projectId, null, "TB_RENAME_ME");
        Long requestR = viewService.update(member(projectId), r, save("OBJ_X", "SELECT 'r'")).request().id();
        assertThat(race(List.of(
                () -> viewService.processRequest(login(projectId, adminId), requestR, true),
                () -> viewService.create(login(projectId, adminId), save("OBJ_X", "SELECT 'new'")),
                () -> {
                    tableDesignService.updateTable(TableInfoDto.builder().id(table.getId()).tableName("OBJ_X").tableDescription("x").isMasterTable("N").sortOrder(1).build(), login(projectId, adminId));
                    return null;
                }))).containsOnlyOnce("ok").filteredOn(result -> !result.equals("ok")).containsOnly("SAVED_DATA_EXISTS");
        assertThat(count(projectId, "OBJ_X") + tableInfoRepository.findAllByTableNameAndProjectId("OBJ_X", projectId).size()).isEqualTo(1);

        // 3) 같은 요청을 동시에 세 번 승인 → 한 번만 처리
        Long s = viewService.create(login(projectId, adminId), save("VW_S", "SELECT 1")).id();
        Long requestS = viewService.update(member(projectId), s, save("VW_S", "SELECT 2")).request().id();
        List<Callable<Object>> same = new ArrayList<>();
        for (int i = 0; i < 3; i++) same.add(() -> viewService.processRequest(login(projectId, adminId), requestS, true));
        assertThat(race(same)).containsExactlyInAnyOrder("ok", "INVALID_PHYSICAL_MODEL", "INVALID_PHYSICAL_MODEL");
        assertThat(jdbc.queryForObject("select DMND_STTS_CD from TB_VIEW_CHG_DMND where VIEW_CHG_DMND_SN = ?", String.class, requestS)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("동시성 : 참조를 더하는 승인과 그 참조 대상 VIEW 의 삭제가 겹쳐도 지워진 대상을 가리키는 참조가 남지 않는다")
    void approvalAndDeleteDoNotLeaveDanglingReference() throws Exception {
        AccountDto admin = loginAsAdmin("m15s-delete");
        long projectId = admin.getProjectId();
        String adminId = admin.getId();
        Long target = viewService.create(admin, save("VW_TARGET", "SELECT 1")).id();
        viewService.updateApproval(admin, target, false); // 미승인이라 지울 수 있다
        Long a = viewService.create(admin, save("VW_A", "SELECT 1")).id();
        Long request = viewService.update(member(projectId), a, new ViewDto.Save(null, "VW_A", null, null, "SELECT * FROM VW_TARGET", List.of(),
                List.of(new ViewDto.Reference("VIEW", null, target, null, null)))).request().id();

        List<String> results = race(List.of(
                () -> viewService.processRequest(login(projectId, adminId), request, true),
                () -> {
                    viewService.delete(login(projectId, adminId), target);
                    return null;
                }));
        // 둘 중 하나만 성공 : 승인이 먼저면 삭제가 참조 때문에 거절, 삭제가 먼저면 승인이 대상 없음으로 거절 (요청은 그대로)
        assertThat(results).containsOnlyOnce("ok");
        boolean targetExists = jdbc.queryForObject("select count(*) from TB_VIEW where VIEW_SN = ?", Integer.class, target) == 1;
        int references = jdbc.queryForObject("select count(*) from TB_VIEW_RFRNC where VIEW_SN = ?", Integer.class, a);
        assertThat(targetExists).isEqualTo(references == 1);
        assertThat(jdbc.queryForObject("select count(*) from TB_VIEW_RFRNC r where r.RFRNC_VIEW_SN is not null and not exists (select 1 from TB_VIEW v where v.VIEW_SN = r.RFRNC_VIEW_SN)", Integer.class)).isZero();
        // 승인된 VIEW 는 승인과 겹쳐도 지울 수 없다
        login(projectId, adminId);
        assertInvalid(() -> viewService.delete(admin, a), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
    }

    // ------------------------------------------------------------------ 보조

    private static ViewDto.Save save(String name, String sql) {
        return new ViewDto.Save(null, name, null, null, sql, List.of(), List.of());
    }

    private int count(long projectId, String viewName) {
        return jdbc.queryForObject("select count(*) from TB_VIEW where PRJ_SN = ? and VIEW_NM = ?", Integer.class, projectId, viewName);
    }

    /** 동시에 실행한 결과 : 성공 "ok", 실패는 ErrorCode 이름 */
    private static List<String> race(List<Callable<Object>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        call.call();
                        return "ok";
                    } catch (CustomException e) {
                        return e.getErrorCode().name();
                    } finally {
                        SecurityTestSupport.clear();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertInvalid(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
    }

    private AccountDto login(long projectId, String accountId) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, "ROLE_ADMIN");
    }

    /** 승인 권한이 없는 사용자 (프로젝트 관리자 아님, USER 역할) */
    private AccountDto member(long projectId) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, Long schemaId, String name) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    /** 테이블 업로드 템플릿 : 헤더 1행 + (테이블명, 설명, 마스터 여부, 정렬 순서) */
    private static MockMultipartFile excel(List<String[]> rows) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i + 1);
                for (int c = 0; c < rows.get(i).length; c++) row.createCell(c).setCellValue(rows.get(i)[c]);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "tables.xlsx", null, out.toByteArray());
        }
    }
}
