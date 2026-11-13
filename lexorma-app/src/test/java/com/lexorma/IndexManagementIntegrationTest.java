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
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-14 1차 인덱스 관리 (H2, 실제 JPA·동기화·이력) : 컬럼 인덱스 여부 → COLUMN 인덱스(D-19), 미승인 직접 수정·승인된 인덱스 변경 요청(D-20),
 * 유형 검증(D-21), 목록·상세·DDL, 프로젝트 격리
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class IndexManagementIntegrationTest {

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

    // ------------------------------------------------------------------ D-19 : 컬럼 인덱스 여부 → COLUMN 인덱스

    @Test
    @DisplayName("컬럼의 인덱스 여부는 COLUMN 인덱스 한 개로 동기화되고, DDL·목록·ERD 조회가 같은 행을 본다")
    void columnFlagBecomesIndex() {
        AccountDto admin = loginAsAdmin("m14-flag");
        long projectId = admin.getProjectId();
        TableInfo t = table(projectId, "TB_ORD");
        column(projectId, t, "ORD_NO", true, false);
        ColumnInfo dt = column(projectId, t, "ORD_DT", false, true);
        ColumnInfo pkIndexed = column(projectId, t, "ORD_SN", true, true); // PK 는 이미 인덱스가 있다 : 만들지 않는다 (예전 DDL 과 같다)

        TableIndex index = columnIndex(dt);
        assertThat(index).isNotNull();
        assertThat(index.getIndexName()).isEqualTo("IX_TB_ORD_ORD_DT");
        assertThat(index.getIsApproval()).as("컬럼의 승인 상태를 따른다").isFalse();
        assertThat(columnIndex(pkIndexed)).isNull();
        assertThat(ddl(admin, t)).containsOnlyOnce("CREATE INDEX IX_TB_ORD_ORD_DT ON TB_ORD (ORD_DT);");
        assertThat(physicalModelService.selectTableModel(admin, t.getId()).indexes())
                .singleElement().extracting(PhysicalModelDto.Index::indexSource).isEqualTo("COLUMN");

        // 직접 수정·삭제·승인 불가 (컬럼에서 바꾼다), 같은 구성의 사용자 인덱스도 거절
        assertInvalid(() -> physicalModelService.updateIndex(admin, index.getId(), request(t, "IX_X", null, false, dt)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.delete(admin, PhysicalModelService.Kind.INDEX, index.getId()), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.updateApproval(admin, PhysicalModelService.Kind.INDEX, index.getId(), true), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.saveIndex(admin, request(t, null, null, false, dt)), ErrorCode.INVALID_PHYSICAL_MODEL);

        // 컬럼 승인 → 인덱스도 승인, 인덱스 여부 해제 → 인덱스 삭제
        columnManageService.updateColumnApproval(admin, List.of(ColumnInfoDto.builder().id(dt.getId()).build()), "true");
        assertThat(columnIndex(dt).getIsApproval()).isTrue();
        inTx(() -> columnInfoRepository.findById(dt.getId()).orElseThrow().setIsIndex(false));
        assertThat(columnIndex(dt)).isNull();
        assertThat(ddl(admin, t)).doesNotContain("CREATE INDEX");

        // 테이블명 변경 → 이름이 따라간다, 컬럼 삭제 → 인덱스가 함께 지워진다 (사용 중 오류가 아니다)
        ColumnInfo memo = column(projectId, t, "MEMO", false, true);
        inTx(() -> tableInfoRepository.findById(t.getId()).orElseThrow().setTableName("TB_ORDER"));
        assertThat(columnIndex(memo).getIndexName()).isEqualTo("IX_TB_ORDER_MEMO");
        columnManageService.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(memo.getId()).build()));
        assertThat(columnIndex(memo)).isNull();

        // 동기화가 만든 인덱스 변경은 AUTO_SYNC 이력
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_INDEX' and TRGT_SN = ? and CHG_SRC_CD = 'AUTO_SYNC'",
                Integer.class, index.getId())).isPositive();
    }

    @Test
    @DisplayName("같은 컬럼 하나의 사용자 일반 인덱스가 있으면 컬럼 인덱스를 만들지 않는다 (DDL 중복 없음)")
    void userIndexCoversColumnFlag() {
        AccountDto admin = loginAsAdmin("m14-cover");
        long projectId = admin.getProjectId();
        TableInfo t = table(projectId, "TB_CVR");
        column(projectId, t, "ID", true, false);
        ColumnInfo code = column(projectId, t, "CD", false, false);
        physicalModelService.saveIndex(admin, request(t, "IX_MY_CD", null, false, code));

        inTx(() -> columnInfoRepository.findById(code.getId()).orElseThrow().setIsIndex(true));
        assertThat(columnIndex(code)).isNull();
        assertThat(ddl(admin, t)).containsOnlyOnce("CREATE INDEX").contains("IX_MY_CD");
    }

    // ------------------------------------------------------------------ D-20 : 수정 · 변경 요청

    @Test
    @DisplayName("미승인 인덱스는 바로 수정되고 내부 ID·이력 1건이 유지된다. 컬럼 순서 변경이 DDL 에 반영된다")
    void updateUnapproved() {
        AccountDto admin = loginAsAdmin("m14-edit");
        long projectId = admin.getProjectId();
        TableInfo t = table(projectId, "TB_EDT");
        column(projectId, t, "ID", true, false);
        ColumnInfo a = column(projectId, t, "A_CD", false, false);
        ColumnInfo b = column(projectId, t, "B_CD", false, false);
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "999999", "ROLE_USER");
        PhysicalModelDto.Index saved = physicalModelService.saveIndex(user, request(t, null, null, false, a, b));
        assertThat(saved.isApproval()).isFalse();

        PhysicalModelDto.IndexSaveResult result = physicalModelService.updateIndex(user, saved.id(), request(t, "IX_EDT_BA", "btree", false, b, a));
        assertThat(result.applied()).isTrue();
        assertThat(result.request()).isNull();
        assertThat(result.index().id()).isEqualTo(saved.id());
        assertThat(result.index().indexType()).isEqualTo("BTREE");
        assertThat(result.index().columns()).extracting(PhysicalModelDto.ColumnRef::columnName).containsExactly("B_CD", "A_CD");
        assertThat(ddl(admin, t)).contains("CREATE INDEX IX_EDT_BA ON TB_EDT USING btree (B_CD, A_CD);");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_INDEX' and TRGT_SN = ? and CHG_TYPE_CD = 'UPDATE'",
                Integer.class, saved.id())).isEqualTo(1);

        // 검증 : 다른 테이블의 컬럼, 중복 컬럼, PK 구성, 다른 인덱스와 같은 이름·구성, 테이블 변경
        TableInfo other = table(projectId, "TB_OTHER");
        ColumnInfo foreign = column(projectId, other, "X_CD", false, false);
        physicalModelService.saveIndex(user, request(t, "IX_EDT_A", null, false, a));
        assertInvalid(() -> physicalModelService.updateIndex(user, saved.id(), request(t, null, null, false, a, foreign)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.updateIndex(user, saved.id(), request(t, null, null, false, a, a)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.updateIndex(user, saved.id(), request(t, "ix_edt_a", null, false, b)), ErrorCode.SAVED_DATA_EXISTS);
        assertInvalid(() -> physicalModelService.updateIndex(user, saved.id(), request(t, null, null, false, a)), ErrorCode.SAVED_DATA_EXISTS);
        assertInvalid(() -> physicalModelService.updateIndex(user, saved.id(), request(other, null, null, false, foreign)), ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    @Test
    @DisplayName("승인된 인덱스 : 변경 요청 → 승인 전에는 그대로, 중복 요청·승인 취소 거절, 승인하면 반영, 반려하면 유지")
    void changeRequestFlow() {
        AccountDto admin = loginAsAdmin("m14-request");
        long projectId = admin.getProjectId();
        TableInfo t = table(projectId, "TB_REQ");
        column(projectId, t, "ID", true, false);
        ColumnInfo a = column(projectId, t, "A_CD", false, false);
        ColumnInfo b = column(projectId, t, "B_CD", false, false);
        PhysicalModelDto.Index approved = physicalModelService.saveIndex(admin, request(t, "IX_REQ", null, false, a));
        assertThat(approved.isApproval()).as("승인 권한자가 등록하면 바로 승인").isTrue();

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, projectId, "999999", "ROLE_USER");
        PhysicalModelDto.IndexSaveResult requested = physicalModelService.updateIndex(user, approved.id(), request(t, "IX_REQ2", null, false, a, b));
        assertThat(requested.applied()).isFalse();
        assertThat(requested.request().status()).isEqualTo("REQUESTED");
        assertThat(requested.request().before().indexName()).isEqualTo("IX_REQ");
        assertThat(requested.request().after().columns()).extracting(PhysicalModelDto.ColumnRef::columnName).containsExactly("A_CD", "B_CD");
        assertThat(ddl(admin, t)).as("승인 전에는 승인된 정의").contains("CREATE INDEX IX_REQ ON TB_REQ (A_CD);").doesNotContain("IX_REQ2");

        assertInvalid(() -> physicalModelService.updateIndex(user, approved.id(), request(t, "IX_REQ3", null, false, b)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.delete(user, PhysicalModelService.Kind.INDEX, approved.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        assertInvalid(() -> physicalModelService.processIndexChangeRequest(user, requested.request().id(), true), ErrorCode.APPROVAL_PERMISSION_DENIED);
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertInvalid(() -> physicalModelService.updateApproval(admin, PhysicalModelService.Kind.INDEX, approved.id(), false), ErrorCode.INVALID_PHYSICAL_MODEL);

        // 목록 : 승인 대기 표시
        PhysicalModelDto.IndexRow row = physicalModelService.selectIndexes(admin, PageRequest.of(0, 10), null, null, null, t.getId(), null, null).getContent().get(0);
        assertThat(row.pendingRequest()).isEqualTo("Y");

        PhysicalModelDto.ChangeRequest done = physicalModelService.processIndexChangeRequest(admin, requested.request().id(), true);
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.processedBy()).isNotNull();
        assertThat(ddl(admin, t)).contains("CREATE INDEX IX_REQ2 ON TB_REQ (A_CD, B_CD);");
        PhysicalModelDto.IndexDetail detail = physicalModelService.selectIndex(admin, approved.id());
        assertThat(detail.index().isApproval()).as("반영 뒤에도 승인 상태").isTrue();
        assertThat(detail.index().id()).isEqualTo(approved.id());
        assertInvalid(() -> physicalModelService.processIndexChangeRequest(admin, requested.request().id(), false), ErrorCode.INVALID_PHYSICAL_MODEL);

        // 반려 : 승인된 정의 유지, 이력 보존
        SecurityTestSupport.loginAs(projectRepository, projectId, "999999", "ROLE_USER");
        PhysicalModelDto.IndexSaveResult second = physicalModelService.updateIndex(user, approved.id(), request(t, "IX_REQ_REJECT", null, false, b));
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertThat(physicalModelService.processIndexChangeRequest(admin, second.request().id(), false).status()).isEqualTo("REJECTED");
        assertThat(ddl(admin, t)).contains("IX_REQ2").doesNotContain("IX_REQ_REJECT");
        assertThat(physicalModelService.selectIndex(admin, approved.id()).requests()).extracting(PhysicalModelDto.ChangeRequest::status)
                .containsExactly("REJECTED", "APPROVED");

        // 승인 권한자의 수정은 요청 + 즉시 승인
        PhysicalModelDto.IndexSaveResult direct = physicalModelService.updateIndex(admin, approved.id(), request(t, "IX_REQ_ADMIN", null, false, b, a));
        assertThat(direct.applied()).isTrue();
        assertThat(direct.request().status()).isEqualTo("APPROVED");
        assertInvalid(() -> physicalModelService.updateIndex(admin, approved.id(), request(t, "IX_REQ_ADMIN", null, false, b, a)), ErrorCode.INVALID_PHYSICAL_MODEL); // 바뀐 내용 없음
    }

    // ------------------------------------------------------------------ D-21 · 목록 · 격리

    @Test
    @DisplayName("유형은 프로젝트 DBMS 에서 만들 수 있어야 한다 (지원하지 않으면 거절, 기본 유형으로 바꾸지 않는다)")
    void indexTypeValidation() {
        AccountDto admin = loginAsAdmin("m14-type");
        long projectId = admin.getProjectId();
        TableInfo t = table(projectId, "TB_TYP");
        column(projectId, t, "ID", true, false);
        ColumnInfo a = column(projectId, t, "A_CD", false, false);

        PhysicalModelDto.Index gin = physicalModelService.saveIndex(admin, request(t, null, "gin", false, a));
        assertThat(gin.indexType()).isEqualTo("GIN");
        assertThat(physicalModelService.indexDdl(admin, gin.id(), "public").ddl()).isEqualTo("CREATE INDEX IX_TB_TYP_A_CD ON public.TB_TYP USING gin (A_CD);\n");
        assertInvalid(() -> physicalModelService.updateIndex(admin, gin.id(), request(t, null, "BITMAP", false, a)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.updateIndex(admin, gin.id(), request(t, null, "GIN", true, a)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(physicalModelService.indexTypes(admin).types()).contains("GIN", "BRIN").doesNotContain("BITMAP");

        // 프로젝트 DBMS 를 바꾼 뒤의 예전 값 : DDL 에 주석 + 경고
        inTx(() -> projectRepository.findById(projectId).orElseThrow().setDbType(DbType.ORACLE));
        var ddl = physicalModelService.indexDdl(admin, gin.id(), null);
        assertThat(ddl.ddl()).startsWith("-- CREATE INDEX");
        assertThat(ddl.warnings()).hasSize(1);
        assertThat(ddl.dbType()).isEqualTo("ORACLE");
    }

    @Test
    @DisplayName("목록 : 프로젝트 범위, 스키마·테이블·승인·출처 조건, 이름·테이블·컬럼 검색. 다른 프로젝트 인덱스 ID 는 찾을 수 없다")
    void listAndIsolation() {
        AccountDto a = loginAsAdmin("m14-list-a");
        TableInfo t = table(a.getProjectId(), "TB_LST");
        column(a.getProjectId(), t, "ID", true, false);
        ColumnInfo cd = column(a.getProjectId(), t, "LST_CD", false, false);
        ColumnInfo dt = column(a.getProjectId(), t, "LST_DT", false, true);
        PhysicalModelDto.Index mine = physicalModelService.saveIndex(a, request(t, "IX_LIST_CD", null, true, cd));

        var all = physicalModelService.selectIndexes(a, PageRequest.of(0, 10), null, null, null, null, null, null);
        assertThat(all.getTotalElements()).isEqualTo(2);
        assertThat(physicalModelService.selectIndexes(a, PageRequest.of(0, 10), null, null, null, null, null, "COLUMN").getContent())
                .singleElement().extracting(PhysicalModelDto.IndexRow::columnNames).isEqualTo("LST_DT");
        assertThat(physicalModelService.selectIndexes(a, PageRequest.of(0, 10), null, null, null, null, "Y", null).getContent())
                .singleElement().extracting(PhysicalModelDto.IndexRow::indexName).isEqualTo("IX_LIST_CD");
        assertThat(physicalModelService.selectIndexes(a, PageRequest.of(0, 10), "columnNames", "lst_cd", null, null, null, null).getTotalElements()).isEqualTo(1);
        assertThat(physicalModelService.selectIndexes(a, PageRequest.of(0, 10), "tableName", "tb_lst", t.getSchema().getId(), t.getId(), null, null).getTotalElements()).isEqualTo(2);
        assertThat(physicalModelService.selectIndexes(a, PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("schemaName")), null, null, null, null, null, null)
                .getContent().get(0).schemaName()).isEqualTo("DEFAULT");
        assertThat(dt).isNotNull();

        AccountDto b = loginAsAdmin("m14-list-b");
        assertThat(physicalModelService.selectIndexes(b, PageRequest.of(0, 10), null, null, null, null, null, null).getTotalElements()).isZero();
        assertInvalid(() -> physicalModelService.selectIndex(b, mine.id()), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.updateIndex(b, mine.id(), request(t, null, null, false, cd)), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> physicalModelService.indexDdl(b, mine.id(), null), ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    // ------------------------------------------------------------------ 보조

    // ------------------------------------------------------------------ V5 : LEXORMA 예제 인덱스

    @Test
    @DisplayName("V5 예제 인덱스 : 화면이 보여 주는 DDL 이 Lexorma 의 실제 create index 문과 같다 (이름·테이블·UNIQUE·컬럼·순서)")
    void seededIndexDdlMatchesRealDefinition() {
        AccountDto admin = SecurityTestSupport.loginAsAdmin(0L);
        var declared = LexormaIndexSeedV5Test.declaredIndexes("h2");
        // 다른 테스트가 프로젝트 0 에 만든 인덱스와 섞이지 않게 초기 등록 행만 본다
        List<Long> ids = jdbc.queryForList("select IDX_SN from TB_TBL_IDX where PRJ_SN = 0 and IDX_SRC_CD = 'USER' and RGTR_ID = 'SYSTEM'", Long.class);
        assertThat(ids).hasSize(declared.size()).hasSize(83);
        for (Long id : ids) {
            PhysicalModelDto.IndexDetail detail = physicalModelService.selectIndex(admin, id);
            String[] definition = declared.get(detail.index().indexName()).split("\\|"); // 테이블|UNIQUE 여부|컬럼,컬럼
            var ddl = physicalModelService.indexDdl(admin, id, null);
            // 정규화는 공백·대소문자뿐
            assertThat(ddl.ddl().replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT)).as(detail.index().indexName())
                    .isEqualTo("CREATE" + (definition[1].equals("UNIQUE") ? "UNIQUE" : "") + "INDEX" + detail.index().indexName() + "ON" + definition[0] + "(" + definition[2] + ");");
            assertThat(ddl.warnings()).isEmpty();
            assertThat(ddl.pendingApprovals()).isEmpty();
            assertThat(detail.createdBy()).as("시스템 초기 등록은 등록자로 구분한다").isEqualTo("SYSTEM");
            assertThat(detail.requests()).isEmpty();
        }
    }

    private String ddl(AccountDto account, TableInfo table) {
        return tableDesignService.generateTableDdl(table.getId(), account, null).ddl();
    }

    private TableIndex columnIndex(ColumnInfo column) {
        return inTx(() -> indexRepository.findAll().stream()
                .filter(i -> i.getIndexSource() == TableIndex.Source.COLUMN && column.getId().equals(i.getDerivedColumnId()))
                .findFirst().orElse(null));
    }

    private static PhysicalModelDto.Index request(TableInfo table, String name, String type, boolean unique, ColumnInfo... columns) {
        return new PhysicalModelDto.Index(null, table.getId(), name, type, unique,
                java.util.Arrays.stream(columns).map(c -> new PhysicalModelDto.ColumnRef(c.getId(), null)).toList(), null);
    }

    private static void assertInvalid(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    /** 미승인 컬럼 (저장 트랜잭션의 커밋 직전 동기화가 COLUMN 인덱스를 만든다) */
    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, boolean index) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).isIndex(index).isApproval(false).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
