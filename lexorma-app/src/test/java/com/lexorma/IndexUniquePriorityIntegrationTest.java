package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-14 2차 Part A : D-19 방안 C (UNIQUE 우선). "대신한다" = 같은 컬럼 하나의 승인된 사용자 UNIQUE 인덱스.
 * 컬럼 인덱스는 그 UNIQUE 의 승인 처리에서만 지워지고, 동기화만으로는 지워지지 않는다 (V3 이전 공존 데이터 보존).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class IndexUniquePriorityIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnManageService columnManageService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("생성 순서 독립 : UNIQUE → IDX_YN 과 IDX_YN(승인된 컬럼 인덱스) → UNIQUE 요청 → 승인이 같은 결과. 승인 대기 중에는 확정 정의·DDL 유지")
    void orderIndependent() {
        AccountDto admin = loginAsAdmin("uq-order");
        // 경로 A
        TableInfo a = table(admin, "TB_UQA");
        ColumnInfo ac = column(admin, a, "C_CD", false, true);
        physicalModelService.saveIndex(admin, request(a, "UX_UQ", true, ac));
        setIndexFlag(ac, true);
        // 경로 B : 승인된 컬럼(→ 승인된 컬럼 인덱스) 다음에 승인 권한 없는 USER 의 UNIQUE 등록
        TableInfo b = table(admin, "TB_UQB");
        ColumnInfo bc = column(admin, b, "C_CD", true, true);
        assertThat(indexes(b)).containsExactly("COLUMN:IX_TB_UQB_C_CD:Y:false");
        AccountDto user = userOf(admin);
        PhysicalModelDto.Index pending = physicalModelService.saveIndex(user, request(b, "UX_UQ", true, bc));

        assertThat(indexes(b)).as("승인 대기 : 승인된 컬럼 인덱스는 그대로").containsExactlyInAnyOrder("COLUMN:IX_TB_UQB_C_CD:Y:false", "USER:UX_UQ:N:true");
        assertThat(ddl(admin, b)).contains("CREATE INDEX IX_TB_UQB_C_CD").contains("CREATE UNIQUE INDEX UX_UQ");
        login(admin, "ROLE_ADMIN");
        assertThat(physicalModelService.selectIndex(admin, pending.id()).replacedColumnIndexes()).containsExactly("IX_TB_UQB_C_CD");

        physicalModelService.updateApproval(admin, PhysicalModelService.Kind.INDEX, pending.id(), true);

        assertThat(indexes(b)).containsExactly("USER:UX_UQ:Y:true").isEqualTo(indexes(a));
        assertThat(ddl(admin, b).replace("TB_UQB", "T")).isEqualTo(ddl(admin, a).replace("TB_UQA", "T"));
        // 처리 주체·같은 변경 그룹 : 승인(APPROVE)과 컬럼 인덱스 삭제(DELETE, AUTO_SYNC)
        List<String> rows = jdbc.queryForList("select CHG_TYPE_CD || ':' || CHG_SRC_CD || ':' || USER_ID || ':' || CHG_GROUP_ID from TB_MDL_CHG_HSTRY "
                + "where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_INDEX' order by MDL_CHG_HSTRY_SN desc fetch first 2 rows only", String.class, admin.getProjectId());
        assertThat(rows).anyMatch(r -> r.startsWith("APPROVE:USER:")).anyMatch(r -> r.startsWith("DELETE:AUTO_SYNC:"));
        assertThat(rows.stream().map(r -> r.substring(r.lastIndexOf(':') + 1)).distinct()).hasSize(1);
        assertThat(rows.stream().map(r -> r.split(":")[2]).distinct()).singleElement().isNotEqualTo("null"); // 승인한 사람
    }

    @Test
    @DisplayName("변경 요청으로 UNIQUE 가 될 때 : 대기·반려 중에는 컬럼 인덱스 유지, 승인하면 대신한다")
    void changeRequestToUnique() {
        AccountDto admin = loginAsAdmin("uq-request");
        TableInfo t = table(admin, "TB_UQR");
        ColumnInfo c = column(admin, t, "C_CD", true, true);
        ColumnInfo d = column(admin, t, "D_CD", true, false);
        PhysicalModelDto.Index approved = physicalModelService.saveIndex(admin, request(t, "UX_CD", true, c, d));
        AccountDto user = userOf(admin);

        PhysicalModelDto.IndexSaveResult first = physicalModelService.updateIndex(user, approved.id(), request(t, "UX_C", true, c));
        assertThat(indexes(t)).as("대기").containsExactlyInAnyOrder("COLUMN:IX_TB_UQR_C_CD:Y:false", "USER:UX_CD:Y:true");
        login(admin, "ROLE_ADMIN");
        physicalModelService.processIndexChangeRequest(admin, first.request().id(), false);
        assertThat(indexes(t)).as("반려").containsExactlyInAnyOrder("COLUMN:IX_TB_UQR_C_CD:Y:false", "USER:UX_CD:Y:true");

        login(user, "ROLE_USER");
        PhysicalModelDto.IndexSaveResult second = physicalModelService.updateIndex(user, approved.id(), request(t, "UX_C", true, c));
        login(admin, "ROLE_ADMIN");
        physicalModelService.processIndexChangeRequest(admin, second.request().id(), true);
        assertThat(indexes(t)).as("승인").containsExactly("USER:UX_C:Y:true");
    }

    @Test
    @DisplayName("복원 : UNIQUE 승인 취소·복합 인덱스로 변경하면 컬럼 인덱스가 다시 생기고, 미승인 UNIQUE 삭제는 영향이 없다")
    void restore() {
        AccountDto admin = loginAsAdmin("uq-restore");
        TableInfo t = table(admin, "TB_UQS");
        ColumnInfo c = column(admin, t, "C_CD", false, true);
        ColumnInfo d = column(admin, t, "D_CD", false, false);
        PhysicalModelDto.Index ux = physicalModelService.saveIndex(admin, request(t, "UX_C", true, c));
        assertThat(indexes(t)).containsExactly("USER:UX_C:Y:true");

        physicalModelService.updateApproval(admin, PhysicalModelService.Kind.INDEX, ux.id(), false);
        assertThat(indexes(t)).as("승인 취소 → 대신하지 않는다").containsExactlyInAnyOrder("USER:UX_C:N:true", "COLUMN:IX_TB_UQS_C_CD:N:false");
        physicalModelService.delete(admin, PhysicalModelService.Kind.INDEX, ux.id());
        assertThat(indexes(t)).containsExactly("COLUMN:IX_TB_UQS_C_CD:N:false");

        PhysicalModelDto.Index again = physicalModelService.saveIndex(admin, request(t, "UX_C", true, c));
        assertThat(indexes(t)).containsExactly("USER:UX_C:Y:true");
        physicalModelService.updateIndex(admin, again.id(), request(t, "UX_CD", true, c, d)); // 승인 권한자 : 요청 + 즉시 반영
        assertThat(indexes(t)).as("복합 UNIQUE 는 대신하지 않는다").containsExactlyInAnyOrder("USER:UX_CD:Y:true", "COLUMN:IX_TB_UQS_C_CD:N:false");
    }

    @Test
    @DisplayName("기존 공존(승인된 UNIQUE + 컬럼 인덱스, V3 이관 데이터)은 테이블·컬럼 저장과 반복 동기화로 지워지지 않고 상세에서 식별된다")
    void legacyCoexistencePreserved() {
        AccountDto admin = loginAsAdmin("uq-legacy");
        TableInfo t = table(admin, "TB_UQL");
        ColumnInfo c = column(admin, t, "C_CD", true, true);
        ColumnInfo other = column(admin, t, "X_CD", false, false);
        // V3 이전 데이터처럼 서비스를 거치지 않고 승인된 UNIQUE 를 넣는다
        jdbc.update("insert into TB_TBL_IDX(IDX_SN, PRJ_SN, TBL_SN, IDX_NM, UNQ_YN, APRV_YN, IDX_SRC_CD) values(?, ?, ?, 'UX_OLD', true, true, 'USER')", 990001L, admin.getProjectId(), t.getId());
        jdbc.update("insert into TB_TBL_IDX_COL(IDX_COL_SN, IDX_SN, COL_SN, COL_ORD) values(990001, 990001, ?, 1)", c.getId());
        List<String> before = indexes(t);
        List<String> ddlBefore = indexDdl(admin, t);
        assertThat(before).containsExactlyInAnyOrder("COLUMN:IX_TB_UQL_C_CD:Y:false", "USER:UX_OLD:Y:true");
        int history = historyCount(admin);

        inTx(() -> tableInfoRepository.findById(t.getId()).orElseThrow().setTableDescription("저장"));
        inTx(() -> columnInfoRepository.findById(other.getId()).orElseThrow().setColumnDesc("저장"));
        sync(admin, t);
        sync(admin, t);

        assertThat(indexes(t)).isEqualTo(before);
        assertThat(indexDdl(admin, t)).isEqualTo(ddlBefore).hasSize(2);
        assertThat(historyCount(admin)).as("멱등 : 인덱스 이력 없음").isEqualTo(history);
        assertThat(physicalModelService.selectIndex(admin, 990001L).replacedColumnIndexes()).containsExactly("IX_TB_UQL_C_CD");
        columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(other.getId()).build()));
        assertThat(indexes(t)).isEqualTo(before);
    }

    @Test
    @DisplayName("동시 요청 : 같은 변경 요청을 동시에 승인·반려하면 하나만 처리되고 나머지는 '이미 처리' 로 거절된다")
    void concurrentProcessing() throws Exception {
        AccountDto admin = loginAsAdmin("uq-concurrent");
        TableInfo t = table(admin, "TB_UQC");
        ColumnInfo c = column(admin, t, "C_CD", true, true);
        ColumnInfo d = column(admin, t, "D_CD", true, false);
        PhysicalModelDto.Index approved = physicalModelService.saveIndex(admin, request(t, "UX_CD", true, c, d));
        PhysicalModelDto.IndexSaveResult requested = physicalModelService.updateIndex(userOf(admin), approved.id(), request(t, "UX_C", true, c));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            boolean approve = i % 2 == 0;
            results.add(pool.submit(() -> {
                start.await();
                SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), admin.getId(), "ROLE_ADMIN");
                try {
                    return physicalModelService.processIndexChangeRequest(admin, requested.request().id(), approve).status();
                } catch (CustomException e) {
                    return "REFUSED";
                }
            }));
        }
        start.countDown();
        List<String> statuses = new ArrayList<>();
        for (Future<String> result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(Collections.frequency(statuses, "REFUSED")).isEqualTo(3);
        String done = statuses.stream().filter(s -> !s.equals("REFUSED")).findFirst().orElseThrow();
        login(admin, "ROLE_ADMIN");
        assertThat(physicalModelService.selectIndex(admin, approved.id()).requests()).singleElement().extracting(PhysicalModelDto.ChangeRequest::status).isEqualTo(done);
        assertThat(indexes(t)).isEqualTo(done.equals("APPROVED") ? List.of("USER:UX_C:Y:true")
                : List.of("COLUMN:IX_TB_UQC_C_CD:Y:false", "USER:UX_CD:Y:true"));
    }

    @Test
    @DisplayName("다른 프로젝트에서는 인덱스 승인·변경 요청 처리를 할 수 없다")
    void isolation() {
        AccountDto a = loginAsAdmin("uq-iso-a");
        TableInfo t = table(a, "TB_UQI");
        ColumnInfo c = column(a, t, "C_CD", false, true);
        AccountDto user = userOf(a);
        PhysicalModelDto.Index pending = physicalModelService.saveIndex(user, request(t, "UX_C", true, c));
        AccountDto b = loginAsAdmin("uq-iso-b");
        assertThatThrownBy(() -> physicalModelService.updateApproval(b, PhysicalModelService.Kind.INDEX, pending.id(), true))
                .isInstanceOf(CustomException.class).satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_PHYSICAL_MODEL));
        assertThat(indexes(t)).containsExactlyInAnyOrder("USER:UX_C:N:true", "COLUMN:IX_TB_UQI_C_CD:N:false");
    }

    // ------------------------------------------------------------------ 보조

    private List<String> indexes(TableInfo table) {
        return inTx(() -> indexRepository.findAllByProjectIdAndTableInfo_Id(table.getProjectId(), table.getId()).stream()
                .map(i -> i.getIndexSource() + ":" + i.getIndexName() + ":" + (Boolean.TRUE.equals(i.getIsApproval()) ? "Y" : "N") + ":" + i.getIsUnique())
                .sorted().toList());
    }

    private int historyCount(AccountDto account) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_INDEX'", Integer.class, account.getProjectId());
    }

    private String ddl(AccountDto account, TableInfo table) {
        return tableDesignService.generateTableDdl(table.getId(), account, null).ddl();
    }

    private List<String> indexDdl(AccountDto account, TableInfo table) {
        return ddl(account, table).lines().filter(line -> line.contains(" INDEX ")).toList();
    }

    private void sync(AccountDto account, TableInfo table) {
        tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(account.getProjectId(), List.of(table.getId())));
    }

    private void setIndexFlag(ColumnInfo column, boolean value) {
        inTx(() -> columnInfoRepository.findById(column.getId()).orElseThrow().setIsIndex(value));
    }

    private AccountDto userOf(AccountDto admin) {
        return SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "999999", "ROLE_USER");
    }

    private void login(AccountDto account, String role) {
        SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), account.getId(), role);
    }

    private static PhysicalModelDto.Index request(TableInfo table, String name, boolean unique, ColumnInfo... columns) {
        return new PhysicalModelDto.Index(null, table.getId(), name, null, unique,
                Arrays.stream(columns).map(c -> new PhysicalModelDto.ColumnRef(c.getId(), null)).toList(), null);
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(AccountDto account, String name) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(account.getProjectId()).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(account.getProjectId(), null)).build()));
    }

    /** approved : 컬럼 승인 상태 (= 컬럼 인덱스의 승인 상태) */
    private ColumnInfo column(AccountDto account, TableInfo table, String name, boolean approved, boolean index) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(account.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(false).isIndex(index).isApproval(approved).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
