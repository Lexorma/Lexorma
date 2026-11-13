package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.entity.TableIndex;
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

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-14 1차 안정화 : 컬럼 인덱스 여부(IDX_YN) ↔ 사용자 인덱스(일반·UNIQUE) ↔ 고유값(UNQ_YN)·PK 의 중복 판정 시나리오 A~K.
 * (L : V3 이관 전후 DDL 은 IndexMigrationV3Test 와 DDL 골든 파일 테스트) — 결과 표는 docs/index-management-m14.md 9장.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class IndexDuplicatePolicyIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignService tableDesignService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("A·B·C : IDX_YN 컬럼 → COLUMN 일반 인덱스 1개. 같은 컬럼 사용자 일반 인덱스는 거절, UNIQUE 는 받아서 승인되면 대신한다 (M-14 2차 방안 C)")
    void flagFirst() {
        AccountDto admin = loginAsAdmin("dup-abc");
        TableInfo t = table(admin, "TB_ABC");
        ColumnInfo c = column(admin, t, "C_CD", false, false, true);

        assertThat(indexes(t)).containsExactly("COLUMN:IX_TB_ABC_C_CD:N:false"); // A
        assertThat(ddl(admin, t)).containsOnlyOnce("CREATE INDEX IX_TB_ABC_C_CD ON TB_ABC (C_CD);");
        assertError(() -> physicalModelService.saveIndex(admin, request(t, "IX_MY", false, c)), ErrorCode.INVALID_PHYSICAL_MODEL); // B : 컬럼 설정과 같다
        assertThat(indexes(t)).hasSize(1);
        physicalModelService.saveIndex(admin, request(t, "UX_MY", true, c)); // C : 승인 권한자 등록 = 승인 → 컬럼 인덱스를 대신한다
        assertThat(indexes(t)).containsExactly("USER:UX_MY:Y:true");
    }

    @Test
    @DisplayName("C 역순 : 승인된 사용자 UNIQUE 가 먼저 있으면 IDX_YN 을 켜도 COLUMN 일반 인덱스를 만들지 않는다 (순서와 관계없이 같은 결과)")
    void uniqueFirst() {
        AccountDto admin = loginAsAdmin("dup-c2");
        TableInfo t = table(admin, "TB_C2");
        ColumnInfo c = column(admin, t, "C_CD", false, false, false);
        physicalModelService.saveIndex(admin, request(t, "UX_C2", true, c));
        setIndexFlag(c, true);

        assertThat(indexes(t)).containsExactly("USER:UX_C2:Y:true");
        assertThat(ddl(admin, t)).containsOnlyOnce("CREATE UNIQUE INDEX UX_C2 ON TB_C2 (C_CD);").doesNotContain("IX_TB_C2_C_CD");
    }

    @Test
    @DisplayName("D·E·F : IDX_YN 아님 + UNIQUE 는 허용, 고유값(UNQ_YN) 컬럼·PK 와 같은 구성은 거절. PK 복합 일반 인덱스는 허용(기존 동작)")
    void uniqueAndPk() {
        AccountDto admin = loginAsAdmin("dup-def");
        TableInfo t = table(admin, "TB_DEF");
        ColumnInfo id = column(admin, t, "ID", true, false, false);
        ColumnInfo seq = column(admin, t, "SEQ", true, false, false);
        ColumnInfo d = column(admin, t, "D_CD", false, false, false);
        ColumnInfo e = column(admin, t, "E_CD", false, true, false);

        physicalModelService.saveIndex(admin, request(t, "UX_D", true, d)); // D
        assertError(() -> physicalModelService.saveIndex(admin, request(t, "UX_E", true, e)), ErrorCode.INVALID_PHYSICAL_MODEL); // E : UNIQUE 제약과 같다
        assertError(() -> physicalModelService.saveIndex(admin, request(t, "IX_E", false, e)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertError(() -> physicalModelService.saveIndex(admin, request(t, "IX_ID", false, id)), ErrorCode.INVALID_PHYSICAL_MODEL); // F : PK 컬럼
        assertError(() -> physicalModelService.saveIndex(admin, request(t, "UX_PK", true, id, seq)), ErrorCode.INVALID_PHYSICAL_MODEL);
        physicalModelService.saveIndex(admin, request(t, "IX_PK", false, id, seq));

        assertThat(indexes(t)).containsExactlyInAnyOrder("USER:UX_D:Y:true", "USER:IX_PK:Y:false");
        assertThat(ddl(admin, t)).contains("CONSTRAINT UK_TB_DEF_E_CD UNIQUE (E_CD)").contains("CREATE UNIQUE INDEX UX_D ON TB_DEF (D_CD);")
                .doesNotContain("UX_E").doesNotContain("IX_E");
    }

    @Test
    @DisplayName("G·H : 단일 COLUMN 인덱스와 복합 인덱스는 공존, 같은 컬럼 구성은 유형이 달라도 거절")
    void compositeAndType() {
        AccountDto admin = loginAsAdmin("dup-gh");
        TableInfo t = table(admin, "TB_GH");
        ColumnInfo g = column(admin, t, "G_CD", false, false, true);
        ColumnInfo h = column(admin, t, "H_CD", false, false, false);
        physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, t.getId(), "IX_GH", "BTREE", false, refs(g, h), null));

        assertThat(indexes(t)).containsExactlyInAnyOrder("COLUMN:IX_TB_GH_G_CD:N:false", "USER:IX_GH:Y:false"); // G
        assertError(() -> physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, t.getId(), "IX_GH_HASH", "HASH", false, refs(g, h), null)),
                ErrorCode.SAVED_DATA_EXISTS); // H
        assertThat(ddl(admin, t)).contains("CREATE INDEX IX_TB_GH_G_CD ON TB_GH (G_CD);").contains("CREATE INDEX IX_GH ON TB_GH USING btree (G_CD, H_CD);");
    }

    @Test
    @DisplayName("I : 같은 컬럼의 사용자 일반 인덱스가 IDX_YN 을 대신하다가 그 인덱스를 지우면 COLUMN 인덱스가 바로 다시 생긴다 (이력 AUTO_SYNC)")
    void userIndexDeleted() {
        AccountDto admin = loginAsAdmin("dup-i");
        TableInfo t = table(admin, "TB_I");
        ColumnInfo c = column(admin, t, "C_CD", false, false, false);
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "999999", "ROLE_USER");
        PhysicalModelDto.Index mine = physicalModelService.saveIndex(user, request(t, "IX_MINE", false, c)); // 미승인
        setIndexFlag(c, true);
        assertThat(indexes(t)).containsExactly("USER:IX_MINE:N:false");

        physicalModelService.delete(user, PhysicalModelService.Kind.INDEX, mine.id());

        assertThat(indexes(t)).containsExactly("COLUMN:IX_TB_I_C_CD:N:false");
        assertThat(ddl(admin, t)).containsOnlyOnce("CREATE INDEX IX_TB_I_C_CD ON TB_I (C_CD);");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_INDEX' and CHG_TYPE_CD = 'CREATE' and CHG_SRC_CD = 'AUTO_SYNC'",
                Integer.class, admin.getProjectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("J : 대신하던 사용자 인덱스의 변경 요청은 승인 전·반려 때 그대로, 승인·직접 수정으로 구성이 바뀌면 COLUMN 인덱스가 생긴다")
    void userIndexChanged() {
        AccountDto admin = loginAsAdmin("dup-j");
        TableInfo t = table(admin, "TB_J");
        ColumnInfo c = column(admin, t, "C_CD", false, false, false);
        ColumnInfo d = column(admin, t, "D_CD", false, false, false);
        PhysicalModelDto.Index approved = physicalModelService.saveIndex(admin, request(t, "IX_J", false, c));
        setIndexFlag(c, true);
        assertThat(indexes(t)).containsExactly("USER:IX_J:Y:false");

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "999999", "ROLE_USER");
        PhysicalModelDto.IndexSaveResult first = physicalModelService.updateIndex(user, approved.id(), request(t, "IX_J", false, c, d));
        assertThat(indexes(t)).as("승인 대기 : 승인된 정의 그대로").containsExactly("USER:IX_J:Y:false");
        login(admin, "ROLE_ADMIN");
        physicalModelService.processIndexChangeRequest(admin, first.request().id(), false);
        assertThat(indexes(t)).as("반려").containsExactly("USER:IX_J:Y:false");

        login(user, "ROLE_USER");
        PhysicalModelDto.IndexSaveResult second = physicalModelService.updateIndex(user, approved.id(), request(t, "IX_J", false, c, d));
        login(admin, "ROLE_ADMIN");
        physicalModelService.processIndexChangeRequest(admin, second.request().id(), true);
        assertThat(indexes(t)).as("승인 → 더는 같은 컬럼 하나가 아님").containsExactlyInAnyOrder("USER:IX_J:Y:false", "COLUMN:IX_TB_J_C_CD:N:false");

        // 미승인 인덱스 직접 수정도 같다 : IDX_YN 을 대신하던 단일 [d] 일반 인덱스를 [d, c] 로
        login(user, "ROLE_USER");
        PhysicalModelDto.Index plain = physicalModelService.saveIndex(user, request(t, "IX_D", false, d));
        setIndexFlag(d, true);
        assertThat(indexes(t)).contains("USER:IX_D:N:false").doesNotContain("COLUMN:IX_TB_J_D_CD:N:false");
        physicalModelService.updateIndex(user, plain.id(), request(t, "IX_D_C", false, d, c));
        assertThat(indexes(t)).containsExactlyInAnyOrder("USER:IX_J:Y:false", "COLUMN:IX_TB_J_C_CD:N:false", "COLUMN:IX_TB_J_D_CD:N:false", "USER:IX_D_C:N:false");
    }

    @Test
    @DisplayName("K : 테이블명·컬럼명이 바뀌면 COLUMN 인덱스 이름이 따라가고 ID 는 그대로다")
    void renames() {
        AccountDto admin = loginAsAdmin("dup-k");
        TableInfo t = table(admin, "TB_K");
        ColumnInfo c = column(admin, t, "C_CD", false, false, true);
        Long id = inTx(() -> indexRepository.findAllByProjectIdAndTableInfo_Id(admin.getProjectId(), t.getId()).get(0).getId());

        inTx(() -> {
            tableInfoRepository.findById(t.getId()).orElseThrow().setTableName("TB_K2");
            columnInfoRepository.findById(c.getId()).orElseThrow().setColumnName("C2_CD");
        });

        assertThat(indexes(t)).containsExactly("COLUMN:IX_TB_K2_C2_CD:N:false");
        assertThat(inTx(() -> indexRepository.findAllByProjectIdAndTableInfo_Id(admin.getProjectId(), t.getId()).get(0).getId())).isEqualTo(id);
        assertThat(ddl(admin, t)).contains("CREATE INDEX IX_TB_K2_C2_CD ON TB_K2 (C2_CD);");
    }

    // ------------------------------------------------------------------ 보조

    /** "출처:이름:승인(Y/N):고유" */
    private List<String> indexes(TableInfo table) {
        return inTx(() -> indexRepository.findAllByProjectIdAndTableInfo_Id(table.getProjectId(), table.getId()).stream()
                .map(i -> i.getIndexSource() + ":" + i.getIndexName() + ":" + (Boolean.TRUE.equals(i.getIsApproval()) ? "Y" : "N") + ":" + i.getIsUnique())
                .toList());
    }

    private String ddl(AccountDto account, TableInfo table) {
        return tableDesignService.generateTableDdl(table.getId(), account, null).ddl();
    }

    private void setIndexFlag(ColumnInfo column, boolean value) {
        inTx(() -> columnInfoRepository.findById(column.getId()).orElseThrow().setIsIndex(value));
    }

    private void login(AccountDto account, String role) {
        SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), account.getId(), role);
    }

    private static PhysicalModelDto.Index request(TableInfo table, String name, boolean unique, ColumnInfo... columns) {
        return new PhysicalModelDto.Index(null, table.getId(), name, null, unique, refs(columns), null);
    }

    private static List<PhysicalModelDto.ColumnRef> refs(ColumnInfo... columns) {
        return Arrays.stream(columns).map(c -> new PhysicalModelDto.ColumnRef(c.getId(), null)).toList();
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
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

    /** 미승인 컬럼 (커밋 직전 동기화가 IDX_YN → COLUMN 인덱스를 맞춘다) */
    private ColumnInfo column(AccountDto account, TableInfo table, String name, boolean pk, boolean unique, boolean index) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(account.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).isUnique(unique).isIndex(index).isApproval(false).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
