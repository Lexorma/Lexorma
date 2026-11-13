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
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnRef;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Member;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
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
 * M-14 2차 : PostgreSQL 고급 인덱스 (표현식·WHERE·정렬·NULLS·INCLUDE) 의 API·검증·승인(변경 요청)·변경 이력·모델 버전 비교.
 * DDL 실행 검증은 AdvancedIndexPostgresDdlTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class IndexAdvancedIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ModelVersionService versionService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("등록·조회·DDL : 표현식 + 정렬·NULLS + INCLUDE + WHERE 가 저장되고 응답·목록·테이블 DDL·인덱스 DDL 에 그대로 나온다")
    void saveAndRender() {
        AccountDto admin = loginAsAdmin("adv-save");
        TableInfo t = table(admin, "TB_ADV");
        ColumnInfo email = column(admin, t, "EMAIL"), regDt = column(admin, t, "REG_DT"), nm = column(admin, t, "NM");

        PhysicalModelDto.Index saved = physicalModelService.saveIndex(admin, index(t, "IX_ADV", "btree", false, " ACTV_YN = true ",
                List.of(expr("lower(EMAIL)", null, null), key(regDt, "desc", "last")), List.of(ref(nm))));

        assertThat(saved.members()).extracting(Member::expression, Member::columnName, Member::direction, Member::nulls)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("lower(EMAIL)", null, "ASC", null), org.assertj.core.groups.Tuple.tuple(null, "REG_DT", "DESC", "LAST"));
        assertThat(saved.includeColumns()).extracting(ColumnRef::columnName).containsExactly("NM");
        assertThat(saved.condition()).isEqualTo("ACTV_YN = true");
        assertThat(saved.columns()).as("예전 응답 필드 : 키 중 컬럼만").extracting(ColumnRef::columnName).containsExactly("REG_DT");
        assertThat(jdbc.queryForList("select coalesce(c.EXPR_CN, '-') || ':' || coalesce(c.SORT_CD, '-') || ':' || c.COL_ORD from TB_TBL_IDX_COL c where c.IDX_SN = ? order by c.COL_ORD",
                String.class, saved.id())).containsExactly("lower(EMAIL):-:1", "-:DESC NULLS LAST:2", "-:INCLUDE:3");

        String expected = "CREATE INDEX IX_ADV ON TB_ADV USING btree ((lower(EMAIL)), REG_DT DESC NULLS LAST) INCLUDE (NM) WHERE ACTV_YN = true;";
        assertThat(tableDesignService.generateTableDdl(t.getId(), admin, null).ddl()).contains(expected);
        assertThat(physicalModelService.indexDdl(admin, saved.id(), null).ddl()).isEqualTo(expected + "\n");
        PhysicalModelDto.IndexRow row = physicalModelService.selectIndexes(admin, PageRequest.of(0, 10), null, null, null, t.getId(), null, null).getContent().get(0);
        assertThat(row.columnNames()).isEqualTo("(lower(EMAIL)), REG_DT DESC NULLS LAST INCLUDE (NM)");
        assertThat(row.condition()).isEqualTo("ACTV_YN = true");
        assertThat(physicalModelService.selectTableModel(admin, t.getId()).indexes()).singleElement().extracting(PhysicalModelDto.Index::condition).isEqualTo("ACTV_YN = true");

        // 예전 형태 요청(columns 만)은 그대로 동작한다
        PhysicalModelDto.Index plain = physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, t.getId(), null, null, false, List.of(ref(email)), null));
        assertThat(plain.members()).singleElement().extracting(Member::direction, Member::nulls).containsExactly("ASC", null);
        assertThat(physicalModelService.indexDdl(admin, plain.id(), null).ddl()).isEqualTo("CREATE INDEX IX_TB_ADV_EMAIL ON TB_ADV (EMAIL);\n");
    }

    @Test
    @DisplayName("검증 : 키 형식·중복, INCLUDE 중복·키와 겹침, 다른 테이블 컬럼, SQL 조각, 유형·DBMS 조합 — 지원하지 않으면 거절 (바꾸지 않음)")
    void validation() {
        AccountDto admin = loginAsAdmin("adv-valid");
        TableInfo t = table(admin, "TB_VAL");
        ColumnInfo a = column(admin, t, "A_CD"), b = column(admin, t, "B_CD");
        TableInfo other = table(admin, "TB_OTHER");
        ColumnInfo foreign = column(admin, other, "X_CD");

        invalid(index(t, null, null, false, null, List.of(new Member(a.getId(), null, "lower(A_CD)", null, null)), null));   // 컬럼 + 표현식
        invalid(index(t, null, null, false, null, List.of(new Member(null, null, "  ", null, null)), null));                 // 빈 표현식
        invalid(index(t, null, null, false, null, List.of(expr("lower(A_CD); drop table x", null, null)), null));            // 문장 구분자
        invalid(index(t, null, null, false, null, List.of(expr("lower(A_CD", null, null)), null));                           // 괄호 짝
        invalid(index(t, null, null, false, "A_CD = 'x", List.of(key(a, null, null)), null));                               // 따옴표 짝
        invalid(index(t, null, null, false, null, List.of(expr("lower(A_CD)", null, null), expr("lower(A_CD)", null, null)), null));
        invalid(index(t, null, null, false, null, List.of(key(a, null, null), key(a, "DESC", null)), null));                  // 키 컬럼 중복
        invalid(index(t, null, null, false, null, List.of(key(a, null, null)), List.of(ref(b), ref(b))));                   // INCLUDE 중복
        invalid(index(t, null, null, false, null, List.of(key(a, null, null)), List.of(ref(a))));                           // 키 = INCLUDE
        invalid(index(t, null, null, false, null, List.of(key(foreign, null, null)), null));                                // 다른 테이블
        invalid(index(t, null, null, false, null, List.of(key(a, "UP", null)), null));                                      // 정렬 값
        invalid(index(t, null, "GIN", false, null, List.of(key(a, "DESC", null)), null));                                   // 정렬 : BTREE 만
        invalid(index(t, null, "HASH", false, null, List.of(key(a, null, null)), List.of(ref(b))));                         // INCLUDE : BTREE·GIST·SPGIST
        invalid(index(t, null, "SPGIST", false, null, List.of(key(a, null, null), key(b, null, null)), null));              // 키 하나만
        assertThat(physicalModelService.selectIndexes(admin, PageRequest.of(0, 10), null, null, null, t.getId(), null, null).getTotalElements()).isZero();

        // 같은 키라도 조건·정렬이 다르면 다른 인덱스, 같은 정의는 중복
        physicalModelService.saveIndex(admin, index(t, "IX_1", null, false, null, List.of(key(a, null, null)), null));
        physicalModelService.saveIndex(admin, index(t, "IX_2", null, false, "B_CD IS NOT NULL", List.of(key(a, null, null)), null));
        physicalModelService.saveIndex(admin, index(t, "IX_3", null, false, null, List.of(key(a, "DESC", null)), null));
        assertError(() -> physicalModelService.saveIndex(admin, index(t, "IX_4", null, false, "B_CD IS NOT NULL", List.of(key(a, "ASC", "LAST")), null)),
                ErrorCode.SAVED_DATA_EXISTS); // ASC NULLS LAST = 기본

        // PostgreSQL 이 아니면 고급 속성 거절, 일반 인덱스는 그대로
        inTx(() -> projectRepository.findById(admin.getProjectId()).orElseThrow().setDbType(DbType.ORACLE));
        invalid(index(t, "IX_O1", null, false, null, List.of(expr("UPPER(A_CD)", null, null)), null));
        invalid(index(t, "IX_O2", null, false, "B_CD IS NOT NULL", List.of(key(b, null, null)), null));
        physicalModelService.saveIndex(admin, index(t, "IX_O3", null, false, null, List.of(key(b, null, null)), null));
        assertThat(tableDesignService.generateTableDdl(t.getId(), admin, null).warnings()).anyMatch(w -> w.startsWith("IX_2 : ")).anyMatch(w -> w.startsWith("IX_3 : "));
    }

    @Test
    @DisplayName("승인된 고급 인덱스 변경 요청 : 컬럼↔표현식·WHERE·정렬·NULLS·INCLUDE·순서 변경이 전후 정의에 담기고, 대기·반려 중에는 그대로, 승인하면 반영된다. 이력·버전 비교에 나온다")
    void changeRequestHistoryAndVersion() {
        AccountDto admin = loginAsAdmin("adv-request");
        TableInfo t = table(admin, "TB_REQ");
        ColumnInfo a = column(admin, t, "A_CD"), b = column(admin, t, "B_CD"), c = column(admin, t, "C_CD");
        PhysicalModelDto.Index approved = physicalModelService.saveIndex(admin, index(t, "IX_R", null, false, null, List.of(key(a, null, null), key(b, null, null)), null));
        PhysicalModelDto.Index untouched = physicalModelService.saveIndex(admin, index(t, "IX_U", null, false, null, List.of(key(c, null, null)), null));
        ModelVersionDto.Summary v1 = versionService.create(admin, new ModelVersionDto.Create("adv-v1", null));
        String ddlBefore = physicalModelService.indexDdl(admin, approved.id(), null).ddl();

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "999999", "ROLE_USER");
        PhysicalModelDto.Index after = index(t, "IX_R", null, false, "C_CD > 0", List.of(key(b, "DESC", "LAST"), expr("upper(A_CD)", null, "FIRST")), List.of(ref(c)));
        PhysicalModelDto.IndexSaveResult requested = physicalModelService.updateIndex(user, approved.id(), after);
        assertThat(requested.applied()).isFalse();
        assertThat(requested.request().before().members()).extracting(Member::columnName).containsExactly("A_CD", "B_CD");
        assertThat(requested.request().after().members()).extracting(Member::columnName, Member::expression, Member::direction, Member::nulls)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("B_CD", null, "DESC", "LAST"), org.assertj.core.groups.Tuple.tuple(null, "upper(A_CD)", "ASC", "FIRST"));
        assertThat(requested.request().after().includeColumns()).extracting(ColumnRef::columnName).containsExactly("C_CD");
        assertThat(requested.request().after().condition()).isEqualTo("C_CD > 0");
        assertThat(physicalModelService.indexDdl(admin, approved.id(), null).ddl()).as("대기 중 : 확정 DDL 그대로").isEqualTo(ddlBefore);

        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), admin.getId(), "ROLE_ADMIN");
        physicalModelService.processIndexChangeRequest(admin, requested.request().id(), false);
        assertThat(physicalModelService.indexDdl(admin, approved.id(), null).ddl()).as("반려").isEqualTo(ddlBefore);

        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "999999", "ROLE_USER");
        PhysicalModelDto.IndexSaveResult second = physicalModelService.updateIndex(user, approved.id(), after);
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), admin.getId(), "ROLE_ADMIN");
        physicalModelService.processIndexChangeRequest(admin, second.request().id(), true);
        assertThat(physicalModelService.indexDdl(admin, approved.id(), null).ddl())
                .isEqualTo("CREATE INDEX IX_R ON TB_REQ (B_CD DESC NULLS LAST, (upper(A_CD)) NULLS FIRST) INCLUDE (C_CD) WHERE C_CD > 0;\n");
        assertThat(physicalModelService.selectIndex(admin, approved.id()).index().isApproval()).isTrue();

        // 변경 이력 : UPDATE 한 건, 전후에 조건·표현식·정렬
        String afterJson = jdbc.queryForObject("select CHG_AFTR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_INDEX' and TRGT_SN = ? "
                + "and CHG_TYPE_CD = 'UPDATE' order by MDL_CHG_HSTRY_SN desc fetch first 1 rows only", String.class, admin.getProjectId(), approved.id());
        assertThat(afterJson).contains("\"condition\":\"C_CD > 0\"").contains("\"expression\":\"upper(A_CD)\"").contains("\"sort\":\"DESC NULLS LAST\"").contains("\"sort\":\"INCLUDE\"");

        // 모델 버전 비교 : 바뀐 인덱스만 MODIFIED (조건·구성), 고급 속성이 없는 다른 인덱스는 새 필드(null) 때문에 달라 보이지 않는다
        ModelVersionDto.Comparison comparison = versionService.compare(admin, v1.id(), null);
        List<ModelVersionDto.Change> indexChanges = comparison.changes().stream().filter(change -> change.targetType().equals("PHYSICAL_INDEX")).toList();
        assertThat(indexChanges).singleElement().satisfies(change -> {
            assertThat(change.targetId()).isEqualTo(approved.id());
            assertThat(change.change()).isEqualTo("MODIFIED");
            assertThat(change.changedFields()).contains("condition", "columns");
        });
        assertThat(untouched.id()).isNotNull();
    }

    // ------------------------------------------------------------------ 보조

    private void invalid(PhysicalModelDto.Index request) {
        assertError(() -> physicalModelService.saveIndex((AccountDto) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal(), request), ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
    }

    private static PhysicalModelDto.Index index(TableInfo table, String name, String type, boolean unique, String condition, List<Member> members, List<ColumnRef> includes) {
        return new PhysicalModelDto.Index(null, table.getId(), name, type, unique, null, null, null, members, includes, condition);
    }

    private static Member key(ColumnInfo column, String direction, String nulls) {
        return new Member(column.getId(), null, null, direction, nulls);
    }

    private static Member expr(String expression, String direction, String nulls) {
        return new Member(null, null, expression, direction, nulls);
    }

    private static ColumnRef ref(ColumnInfo column) {
        return new ColumnRef(column.getId(), null);
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

    private ColumnInfo column(AccountDto account, TableInfo table, String name) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(account.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(false).isApproval(false).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
