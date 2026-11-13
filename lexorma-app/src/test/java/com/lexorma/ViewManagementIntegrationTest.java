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
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import com.lexorma.data.view.controller.ViewRestController;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.service.PhysicalViewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

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
 * M-15 VIEW 관리 (H2, 실제 JPA·이력·버전) : 독립 모델·정의 SQL 원문 보존·정적 검사(D-23), 출력 컬럼, 참조 객체·순환·삭제 제한(D-24),
 * TABLE/VIEW 이름 공간, 승인·변경 요청의 원자적 반영, 동시성, 이력·모델 버전, DDL, 프로젝트 격리·권한
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ViewManagementIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired PhysicalViewService viewService;
    @Autowired ViewRestController viewController;
    @Autowired ModelVersionService versionService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 등록·조회·DDL (D-22, D-23)

    @Test
    @DisplayName("VIEW 는 TABLE 과 따로 저장되고, 정의 SQL 원문(대소문자·줄바꿈·주석)이 그대로 남으며 DDL 은 그 원문에 CREATE VIEW 만 붙인다")
    void createKeepsDefinitionAndBuildsDdl() {
        AccountDto admin = loginAsAdmin("m15-create");
        long projectId = admin.getProjectId();
        TableInfo user = table(projectId, "TB_USER");
        String sql = "-- 활성 사용자\nselect u.USER_ID,\n       u.USER_NM /* 이름 */\n  from TB_USER u\n where u.MEMO <> 'a;b -- c'\n;\n";
        ViewDto.SaveResult saved = viewService.create(admin, save("vw_active_user", "활성 사용자", sql,
                List.of(column("user_id", "사용자 아이디", "VARCHAR(20)"), column("USER_NM", null, null)), List.of(tableRef(user))));

        ViewDto.Detail detail = viewService.detail(admin, saved.id());
        assertThat(detail.isApproval()).as("승인 권한자가 등록하면 바로 승인").isTrue();
        assertThat(detail.view().viewName()).isEqualTo("VW_ACTIVE_USER");
        assertThat(detail.view().definition()).isEqualTo(sql);
        assertThat(detail.view().columns()).extracting(ViewDto.Column::columnName).containsExactly("USER_ID", "USER_NM");
        assertThat(detail.view().columns().get(1).dataType()).as("타입은 비워 둘 수 있다").isNull();
        assertThat(detail.view().references()).singleElement().satisfies(r -> {
            assertThat(r.tableId()).isEqualTo(user.getId());
            assertThat(r.objectName()).isEqualTo("TB_USER");
        });
        assertThat(tableInfoRepository.findAllByTableNameAndProjectId("VW_ACTIVE_USER", projectId)).as("TABLE 로 저장되지 않는다").isEmpty();

        String ddl = viewService.ddl(admin, saved.id(), null).ddl();
        assertThat(ddl).isEqualTo("CREATE VIEW VW_ACTIVE_USER AS\n-- 활성 사용자\nselect u.USER_ID,\n       u.USER_NM /* 이름 */\n  from TB_USER u\n where u.MEMO <> 'a;b -- c';\n"
                + "COMMENT ON VIEW VW_ACTIVE_USER IS '활성 사용자';\n");
        assertThat(viewService.ddl(admin, saved.id(), "app").ddl()).startsWith("CREATE VIEW app.VW_ACTIVE_USER AS");

        // 원문이 -- 주석으로 끝나면 ; 가 주석에 묻히지 않게 다음 줄에 둔다
        ViewDto.SaveResult commented = viewService.create(admin, save("VW_COMMENTED", null, "select 1 as ONE -- 끝", List.of(), List.of()));
        assertThat(viewService.ddl(admin, commented.id(), null).ddl()).isEqualTo("CREATE VIEW VW_COMMENTED AS\nselect 1 as ONE -- 끝\n;\n");

        // 다른 스키마의 VIEW 는 스키마명.이름, 목록 행
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "SALES", null, false, 0L, null, null, null, null));
        ViewDto.SaveResult other = viewService.create(admin, new ViewDto.Save(sales.id(), "VW_SALES", null, null, "WITH x AS (SELECT 1 AS N) SELECT N FROM x", List.of(), List.of()));
        assertThat(viewService.ddl(admin, other.id(), null).ddl()).startsWith("CREATE VIEW SALES.VW_SALES AS\nWITH x AS");
        var page = viewService.search(admin, PageRequest.of(0, 10), null, null, null, null);
        assertThat(page.getContent()).extracting(ViewDto.Row::viewName).containsExactlyInAnyOrder("VW_ACTIVE_USER", "VW_COMMENTED", "VW_SALES");
        assertThat(viewService.search(admin, PageRequest.of(0, 10), "viewKorName", "활성", null, null).getContent())
                .singleElement().satisfies(row -> {
                    assertThat(row.columnCount()).isEqualTo(2);
                    assertThat(row.referenceCount()).isEqualTo(1);
                    assertThat(row.isApproval()).isEqualTo("Y");
                });
        assertThat(viewService.search(admin, PageRequest.of(0, 10), null, null, sales.id(), null).getContent()).extracting(ViewDto.Row::viewName).containsExactly("VW_SALES");

        // 스키마 삭제 : VIEW 가 있으면 막는다
        assertInvalid(() -> schemaService.deleteSchema(admin, sales.id()), ErrorCode.RELATION_EXISTS);
    }

    @Test
    @DisplayName("정의 SQL 정적 검사 : 빈 값·CREATE VIEW 전체·여러 문장·조회가 아닌 명령·닫히지 않은 따옴표·괄호를 거절하고, 문자열·주석 안의 ; 와 명령어는 허용한다")
    void definitionRules() {
        AccountDto admin = loginAsAdmin("m15-sql");
        List<String> rejected = List.of("", "   ", "CREATE VIEW VW_X AS SELECT 1", "SELECT 1; SELECT 2", "SELECT 1; DROP TABLE TB_USER",
                "DELETE FROM TB_USER", "WITH d AS (DELETE FROM TB_USER RETURNING *) SELECT * FROM d", "SELECT * INTO TB_COPY FROM TB_USER",
                "SELECT 'abc", "SELECT (1", "SELECT 1) ", "/* 닫히지 않음 SELECT 1", "UPDATE TB_USER SET A = 1", "VALUES (1)", "SELECT 1; 'x'");
        for (String sql : rejected) {
            assertThatThrownBy(() -> viewService.create(admin, save("VW_BAD", null, sql, List.of(), List.of())))
                    .as(sql).isInstanceOf(CustomException.class);
        }
        // 허용 : 끝의 ; (여러 개도 끝에만), 문자열·주석·따옴표 식별자 안의 키워드와 ;, 점 뒤의 이름
        for (String sql : List.of("SELECT 1;", "SELECT 1;;  -- 끝", "SELECT 'DELETE; DROP' AS \"UPDATE\" FROM t /* INSERT; */", "(SELECT t.update_dt FROM t)",
                "select $$; drop$$ as x", "SELECT REPLACE(NM, 'a', 'b') FROM t")) {
            ViewDto.SaveResult ok = viewService.create(admin, save("VW_OK_" + Math.abs(sql.hashCode()), null, sql, List.of(), List.of()));
            assertThat(viewService.detail(admin, ok.id()).view().definition()).isEqualTo(sql);
        }
        // 이름 규칙 : 식별자 형식·예약어
        for (String name : List.of("1VW", "VW-X", "VW X", "SELECT", "view", "가나다")) {
            assertThatThrownBy(() -> viewService.create(admin, save(name, null, "SELECT 1", List.of(), List.of()))).as(name).isInstanceOf(CustomException.class);
        }
        // MySQL : 백슬래시 이스케이프 문자열을 닫힌 것으로 본다
        setDbType(admin.getProjectId(), DbType.MYSQL);
        ViewDto.SaveResult mysql = viewService.create(admin, save("VW_MYSQL", "MySQL 뷰", "SELECT 'it\\'s' AS T", List.of(), List.of()));
        var ddl = viewService.ddl(admin, mysql.id(), null);
        assertThat(ddl.ddl()).isEqualTo("CREATE VIEW VW_MYSQL AS\nSELECT 'it\\'s' AS T;\n");
        assertThat(ddl.warnings()).as("MySQL VIEW 에는 COMMENT 가 없다").anyMatch(w -> w.contains("COMMENT"));
        setDbType(admin.getProjectId(), DbType.ORACLE);
        assertThat(viewService.ddl(admin, mysql.id(), null).ddl()).as("Oracle 기준으로는 닫히지 않은 따옴표 : 원문 그대로 + 경고")
                .startsWith("CREATE VIEW VW_MYSQL AS\nSELECT 'it\\'s' AS T\n;\nCOMMENT ON TABLE VW_MYSQL IS 'MySQL 뷰';");
    }

    // ------------------------------------------------------------------ TABLE/VIEW 이름 공간

    @Test
    @DisplayName("TABLE 과 VIEW 는 프로젝트 안에서 이름을 함께 쓴다 : 등록·이름 변경 양쪽에서 대소문자 무관하게 겹치지 않는다")
    void sharedNamespace() {
        AccountDto admin = loginAsAdmin("m15-name");
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "TB_ORDER");
        tableUnapproved(table);
        assertInvalid(() -> viewService.create(admin, save("tb_order", null, "SELECT 1", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);

        ViewDto.SaveResult view = viewService.create(admin, save("VW_ORDER", null, "SELECT 1", List.of(), List.of()));
        assertInvalid(() -> viewService.create(admin, save("Vw_Order", null, "SELECT 2", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        // 테이블 이름을 VIEW 이름으로 바꿀 수 없고, VIEW 이름을 테이블 이름으로 바꿀 수 없다
        assertInvalid(() -> tableDesignService.updateTable(TableInfoDto.builder().id(table.getId()).tableName("vw_order").tableDescription("x").isMasterTable("N").sortOrder(1).build(), admin),
                ErrorCode.SAVED_DATA_EXISTS);
        assertInvalid(() -> viewService.update(admin, view.id(), save("TB_ORDER", null, "SELECT 1", List.of(), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        // 자기 이름은 그대로 저장할 수 있다
        viewService.updateApproval(admin, view.id(), false);
        viewService.update(admin, view.id(), save("VW_ORDER", "주문", "SELECT 1", List.of(), List.of()));
        // 다른 프로젝트는 같은 이름을 쓸 수 있다
        AccountDto other = loginAsAdmin("m15-name-other");
        viewService.create(other, save("TB_ORDER", null, "SELECT 1", List.of(), List.of()));
    }

    @Test
    @DisplayName("동시성 : 같은 이름의 VIEW 동시 등록, VIEW 등록과 같은 이름으로의 테이블 이름 변경이 겹쳐도 하나만 성공한다")
    void concurrentNames() throws Exception {
        AccountDto admin = loginAsAdmin("m15-race");
        long projectId = admin.getProjectId();
        String adminId = admin.getId();
        TableInfo table = table(projectId, "TB_RACE_SRC");
        tableUnapproved(table);

        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) calls.add(() -> viewService.create(login(projectId, adminId), save("VW_RACE", null, "SELECT 1", List.of(), List.of())));
        assertThat(race(calls)).as("성공 1, 나머지는 이름 중복").containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS", "SAVED_DATA_EXISTS");

        List<String> mixed = race(List.of(
                () -> viewService.create(login(projectId, adminId), save("TB_RACE", null, "SELECT 1", List.of(), List.of())),
                () -> {
                    tableDesignService.updateTable(TableInfoDto.builder().id(table.getId()).tableName("TB_RACE").tableDescription("x").isMasterTable("N").sortOrder(1).build(), login(projectId, adminId));
                    return null;
                }));
        assertThat(mixed).containsExactlyInAnyOrder("ok", "SAVED_DATA_EXISTS");
        int tables = tableInfoRepository.findAllByTableNameAndProjectId("TB_RACE", projectId).size();
        int views = jdbc.queryForObject("select count(*) from TB_VIEW where PRJ_SN = ? and VIEW_NM = 'TB_RACE'", Integer.class, projectId);
        assertThat(tables + views).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 출력 컬럼·참조 객체 (D-24)

    @Test
    @DisplayName("출력 컬럼 : 이름 중복 거절, 순서 변경·삭제가 반영되고 정의 SQL 과 서로 바꾸지 않는다")
    void outputColumns() {
        AccountDto admin = loginAsAdmin("m15-col");
        assertInvalid(() -> viewService.create(admin, save("VW_DUP", null, "SELECT 1", List.of(column("A", null, null), column("a", null, null)), List.of())), ErrorCode.SAVED_DATA_EXISTS);
        ViewDto.SaveResult view = viewService.create(admin, save("VW_COLS", null, "SELECT A, B, C FROM T", List.of(column("A", null, "INT"), column("B", null, null), column("C", null, null)), List.of()));
        viewService.updateApproval(admin, view.id(), false);
        viewService.update(admin, view.id(), save("VW_COLS", null, "SELECT A, B, C FROM T", List.of(column("C", "씨", null), column("A", null, "BIGINT")), List.of()));
        ViewDto.Definition after = viewService.detail(admin, view.id()).view();
        assertThat(after.columns()).extracting(ViewDto.Column::columnName).containsExactly("C", "A");
        assertThat(after.columns()).extracting(ViewDto.Column::dataType).containsExactly(null, "BIGINT");
        assertThat(after.definition()).as("출력 컬럼을 바꿔도 SQL 은 그대로").isEqualTo("SELECT A, B, C FROM T");
        assertThat(jdbc.queryForList("select COL_ORD from TB_VIEW_COL where VIEW_SN = ? order by COL_ORD", Integer.class, view.id())).containsExactly(1, 2);
    }

    @Test
    @DisplayName("참조 객체 : 같은·다른 스키마의 TABLE·VIEW, 미확인 객체, 자기 참조·순환·중복·다른 프로젝트 거절, 참조된 TABLE·VIEW 는 삭제할 수 없다")
    void references() {
        AccountDto admin = loginAsAdmin("m15-ref");
        long projectId = admin.getProjectId();
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "SALES", null, false, 0L, null, null, null, null));
        TableInfo user = table(projectId, "TB_USER");
        TableInfo salesTable = inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName("TB_SALE").isApproval(false)
                .schema(schemaService.resolveSchema(projectId, sales.id())).build()));

        ViewDto.SaveResult a = viewService.create(admin, save("VW_A", null, "SELECT 1", List.of(), List.of(tableRef(user), tableRef(salesTable),
                new ViewDto.Reference("TABLE", null, null, "ext", "TB_LEGACY"))));
        ViewDto.SaveResult b = viewService.create(admin, new ViewDto.Save(sales.id(), "VW_B", null, null, "SELECT 1", List.of(), List.of(viewRef(a.id()))));
        ViewDto.SaveResult c = viewService.create(admin, save("VW_C", null, "SELECT 1", List.of(), List.of(viewRef(b.id()))));
        assertThat(viewService.detail(admin, a.id()).view().references()).extracting(ViewDto.Reference::objectName).containsExactlyInAnyOrder("TB_USER", "TB_SALE", "TB_LEGACY");
        assertThat(viewService.detail(admin, a.id()).referencedBy()).containsExactly("VW_B");

        // 자기 참조, 순환 (A → C 를 더하면 A → C → B → A), 중복, 미확인 참조인데 모델에 있는 이름, 형식이 틀린 이름
        assertInvalid(() -> viewService.update(admin, a.id(), save("VW_A", null, "SELECT 1", List.of(), List.of(viewRef(a.id())))), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThatThrownBy(() -> viewService.update(admin, a.id(), save("VW_A", null, "SELECT 1", List.of(), List.of(viewRef(c.id())))))
                .isInstanceOf(CustomException.class).hasMessageContaining("VW_A → VW_C → VW_B → VW_A");
        assertInvalid(() -> viewService.create(admin, save("VW_D", null, "SELECT 1", List.of(), List.of(tableRef(user), tableRef(user)))), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.create(admin, save("VW_D", null, "SELECT 1", List.of(), List.of(new ViewDto.Reference("TABLE", null, null, null, "tb_user")))), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.create(admin, save("VW_D", null, "SELECT 1", List.of(), List.of(new ViewDto.Reference("TABLE", null, null, null, "a.b")))), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.create(admin, save("VW_D", null, "SELECT 1", List.of(), List.of(new ViewDto.Reference("VIEW", user.getId(), null, null, null)))), ErrorCode.INVALID_PHYSICAL_MODEL);
        // 다른 스키마에 같은 이름이 없으면 미확인 참조로 둘 수 있다 (모델의 TB_USER 는 기본 스키마)
        viewService.create(admin, save("VW_D", null, "SELECT 1", List.of(), List.of(new ViewDto.Reference("TABLE", null, null, "OTHER", "TB_USER"))));

        // 다른 프로젝트의 테이블·VIEW 는 ID 로도 참조할 수 없다
        AccountDto other = loginAsAdmin("m15-ref-other");
        TableInfo foreign = table(other.getProjectId(), "TB_FOREIGN");
        ViewDto.SaveResult foreignView = viewService.create(other, save("VW_FOREIGN", null, "SELECT 1", List.of(), List.of()));
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertInvalid(() -> viewService.create(admin, save("VW_E", null, "SELECT 1", List.of(), List.of(tableRef(foreign)))), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.create(admin, save("VW_E", null, "SELECT 1", List.of(), List.of(viewRef(foreignView.id())))), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(viewService.targets(admin, "FOREIGN", null)).isEmpty();
        assertThat(viewService.targets(admin, "tb_", "TABLE")).extracting(ViewDto.Target::name).contains("TB_USER", "TB_SALE");

        // 삭제 : 참조된 테이블·VIEW 는 막는다 (자동으로 정리하지 않는다), 승인된 VIEW 도 막는다
        tableUnapproved(salesTable);
        assertInvalid(() -> tableDesignService.deleteTable(List.of(TableInfoDto.builder().id(salesTable.getId()).build()), admin), ErrorCode.RELATION_EXISTS);
        viewService.updateApproval(admin, b.id(), false);
        assertInvalid(() -> viewService.delete(admin, b.id()), ErrorCode.RELATION_EXISTS);
        assertInvalid(() -> viewService.delete(admin, c.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        viewService.updateApproval(admin, c.id(), false);
        viewService.delete(admin, c.id());
        viewService.delete(admin, b.id());
        assertThat(jdbc.queryForObject("select count(*) from TB_VIEW_RFRNC where VIEW_SN in (?, ?)", Integer.class, b.id(), c.id())).isZero();

        // DDL 내보내기 : 참조되는 VIEW 먼저 (이름순이 아니라), 미확인 참조 경고
        ViewDto.SaveResult z = viewService.create(admin, save("VW_Z", null, "SELECT 1", List.of(), List.of()));
        viewService.create(admin, save("VW_AA", null, "SELECT * FROM VW_Z", List.of(), List.of(viewRef(z.id()))));
        var all = viewService.ddlAll(admin, null, null);
        assertThat(all.ddl().indexOf("CREATE VIEW VW_Z ")).isLessThan(all.ddl().indexOf("CREATE VIEW VW_AA "));
        assertThat(all.warnings()).anyMatch(w -> w.contains("ext.TB_LEGACY"));
        assertThat(viewService.ddlAll(admin, sales.id(), null).ddl()).doesNotContain("VW_A ");
    }

    // ------------------------------------------------------------------ 승인·변경 요청

    @Test
    @DisplayName("승인된 VIEW 의 수정은 변경 요청 : 대기 중에는 확정 정의·DDL 그대로, 승인하면 이름·SQL·컬럼·참조가 한 번에 반영, 반려하면 그대로")
    void changeRequestAppliesWholeDefinition() {
        AccountDto admin = loginAsAdmin("m15-approval");
        long projectId = admin.getProjectId();
        TableInfo user = table(projectId, "TB_USER");
        TableInfo dept = table(projectId, "TB_DEPT");
        AccountDto member = SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");

        // 승인 권한이 없는 사용자 : 등록은 승인 대기, 미승인 VIEW 는 바로 수정, 승인할 수 없다
        ViewDto.SaveResult view = viewService.create(member, save("VW_USER", null, "SELECT USER_ID FROM TB_USER", List.of(column("USER_ID", null, null)), List.of(tableRef(user))));
        assertThat(viewService.detail(member, view.id()).isApproval()).isFalse();
        assertThat(viewService.update(member, view.id(), save("VW_USER", "사용자", "SELECT USER_ID FROM TB_USER", List.of(column("USER_ID", null, null)), List.of(tableRef(user)))).applied()).isTrue();
        assertInvalid(() -> viewService.updateApproval(member, view.id(), true), ErrorCode.APPROVAL_PERMISSION_DENIED);

        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        viewService.updateApproval(admin, view.id(), true);
        String confirmedDdl = viewService.ddl(admin, view.id(), null).ddl();

        SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");
        ViewDto.Save requested = save("VW_USER_DEPT", "사용자 부서", "SELECT u.USER_ID, d.DEPT_NM\nFROM TB_USER u JOIN TB_DEPT d ON d.DEPT_ID = u.DEPT_ID",
                List.of(column("USER_ID", null, null), column("DEPT_NM", "부서명", "VARCHAR(100)")), List.of(tableRef(user), tableRef(dept)));
        ViewDto.SaveResult result = viewService.update(member, view.id(), requested);
        assertThat(result.applied()).isFalse();
        assertThat(result.request().status()).isEqualTo("REQUESTED");
        assertThat(viewService.detail(member, view.id()).view().viewName()).as("대기 중에는 확정 정의 그대로").isEqualTo("VW_USER");
        assertThat(viewService.ddl(member, view.id(), null).ddl()).isEqualTo(confirmedDdl);
        assertThat(viewService.ddl(member, view.id(), null).warnings()).anyMatch(w -> w.contains("변경 요청"));
        assertInvalid(() -> viewService.update(member, view.id(), requested), ErrorCode.INVALID_PHYSICAL_MODEL); // 대기 요청이 있다
        assertInvalid(() -> viewService.processRequest(member, result.request().id(), true), ErrorCode.APPROVAL_PERMISSION_DENIED);
        assertInvalid(() -> viewService.delete(member, view.id()), ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);

        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertInvalid(() -> viewService.updateApproval(admin, view.id(), false), ErrorCode.INVALID_PHYSICAL_MODEL); // 대기 요청이 있으면 승인 취소 불가
        viewService.processRequest(admin, result.request().id(), true);
        ViewDto.Detail approved = viewService.detail(admin, view.id());
        assertThat(approved.isApproval()).isTrue();
        assertThat(approved.view().viewName()).isEqualTo("VW_USER_DEPT");
        assertThat(approved.view().definition()).isEqualTo(requested.definition());
        assertThat(approved.view().columns()).extracting(ViewDto.Column::columnName).containsExactly("USER_ID", "DEPT_NM");
        assertThat(approved.view().references()).extracting(ViewDto.Reference::objectName).containsExactlyInAnyOrder("TB_USER", "TB_DEPT");
        assertThat(approved.requests()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("APPROVED");
            assertThat(r.requestedBy()).isEqualTo(member.getName());
            assertThat(r.processedBy()).isEqualTo(admin.getName());
            assertThat(r.before().viewName()).isEqualTo("VW_USER");
        });
        assertInvalid(() -> viewService.processRequest(admin, result.request().id(), false), ErrorCode.INVALID_PHYSICAL_MODEL); // 이미 처리됨

        // 반려 : 확정 정의 그대로
        SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");
        ViewDto.SaveResult second = viewService.update(member, view.id(), save("VW_USER_DEPT", "사용자 부서", "SELECT 1", List.of(), List.of()));
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        viewService.processRequest(admin, second.request().id(), false);
        assertThat(viewService.detail(admin, view.id()).view().definition()).isEqualTo(requested.definition());

        // 승인할 때 다시 검증 : 요청 뒤에 같은 이름의 테이블이 생겼으면 승인이 거절되고 아무것도 바뀌지 않는다 (원자적)
        SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");
        ViewDto.SaveResult third = viewService.update(member, view.id(), save("TB_LATE", null, "SELECT 2", List.of(column("X", null, null)), List.of(tableRef(dept))));
        table(projectId, "TB_LATE");
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertInvalid(() -> viewService.processRequest(admin, third.request().id(), true), ErrorCode.SAVED_DATA_EXISTS);
        ViewDto.Detail unchanged = viewService.detail(admin, view.id());
        assertThat(unchanged.view()).isEqualTo(approved.view());
        assertThat(unchanged.requests().get(0).status()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForList("select COL_NM from TB_VIEW_COL where VIEW_SN = ? order by COL_ORD", String.class, view.id())).containsExactly("USER_ID", "DEPT_NM");

        // 승인 권한자의 수정은 즉시 반영 (요청 이력은 APPROVED 로 남는다) — 먼저 남은 요청을 반려
        viewService.processRequest(admin, third.request().id(), false);
        ViewDto.SaveResult direct = viewService.update(admin, view.id(), save("VW_USER_DEPT", "사용자 부서", "SELECT 3", List.of(), List.of()));
        assertThat(direct.applied()).isTrue();
        assertThat(direct.request().status()).isEqualTo("APPROVED");
        assertInvalid(() -> viewService.update(admin, view.id(), save("VW_USER_DEPT", "사용자 부서", "SELECT 3", List.of(), List.of())), ErrorCode.INVALID_PHYSICAL_MODEL); // 바뀐 내용 없음
    }

    @Test
    @DisplayName("동시 승인 : 서로를 참조하게 하는 두 변경 요청을 함께 승인해도 하나만 반영되어 순환이 생기지 않는다")
    void concurrentApprovalsDoNotCreateCycle() throws Exception {
        AccountDto admin = loginAsAdmin("m15-cycle");
        long projectId = admin.getProjectId();
        String adminId = admin.getId();
        ViewDto.SaveResult a = viewService.create(admin, save("VW_P", null, "SELECT 1", List.of(), List.of()));
        ViewDto.SaveResult b = viewService.create(admin, save("VW_Q", null, "SELECT 1", List.of(), List.of()));
        AccountDto member = SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");
        Long requestA = viewService.update(member, a.id(), save("VW_P", null, "SELECT * FROM VW_Q", List.of(), List.of(viewRef(b.id())))).request().id();
        Long requestB = viewService.update(member, b.id(), save("VW_Q", null, "SELECT * FROM VW_P", List.of(), List.of(viewRef(a.id())))).request().id();

        List<String> results = race(List.of(
                () -> viewService.processRequest(login(projectId, adminId), requestA, true),
                () -> viewService.processRequest(login(projectId, adminId), requestB, true)));
        assertThat(results).containsExactlyInAnyOrder("ok", "INVALID_PHYSICAL_MODEL");
        assertThat(jdbc.queryForObject("select count(*) from TB_VIEW_RFRNC r join TB_VIEW v on v.VIEW_SN = r.VIEW_SN where v.PRJ_SN = ? and r.RFRNC_VIEW_SN is not null",
                Integer.class, projectId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 이력·모델 버전

    @Test
    @DisplayName("변경 이력 : VIEW 생성·수정·승인이 PHYSICAL_VIEW 로 남고 출력 컬럼·참조 객체는 VIEW 이력의 배열이다. 모델 버전 비교에 VIEW 의 추가·SQL·컬럼·참조 변경이 나타난다")
    void historyAndVersion() {
        AccountDto admin = loginAsAdmin("m15-history");
        long projectId = admin.getProjectId();
        TableInfo user = table(projectId, "TB_USER");
        ModelVersionDto.Summary empty = versionService.create(admin, new ModelVersionDto.Create("v-empty", null));

        ViewDto.SaveResult view = viewService.create(admin, save("VW_H", null, "SELECT 1", List.of(column("A", null, null)), List.of(tableRef(user))));
        ModelVersionDto.Summary first = versionService.create(admin, new ModelVersionDto.Create("v-first", null));
        viewService.update(admin, view.id(), save("VW_H", null, "SELECT 2", List.of(column("A", null, null), column("B", null, null)), List.of()));
        viewService.updateApproval(admin, view.id(), false);

        List<String> actions = jdbc.queryForList("select CHG_TYPE_CD from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_VIEW' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN",
                String.class, projectId, view.id());
        assertThat(actions).containsExactly("CREATE", "UPDATE", "REVOKE");
        String created = jdbc.queryForObject("select CHG_AFTR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_VIEW' and CHG_TYPE_CD = 'CREATE'", String.class, projectId);
        assertThat(created).contains("\"definition\":\"SELECT 1\"", "\"columns\":[", "\"columnName\":\"A\"", "\"references\":[", "\"referencedTableName\":\"TB_USER\"");
        String updated = jdbc.queryForObject("select CHG_BFR_CN from TB_MDL_CHG_HSTRY where PRJ_SN = ? and TRGT_TYPE_CD = 'PHYSICAL_VIEW' and CHG_TYPE_CD = 'UPDATE'", String.class, projectId);
        assertThat(updated).as("변경 전 = 트랜잭션 전 구성").contains("\"definition\":\"SELECT 1\"", "TB_USER").doesNotContain("\"columnName\":\"B\"");

        ModelVersionDto.Comparison added = versionService.compare(admin, empty.id(), first.id());
        assertThat(added.changes()).filteredOn(c -> c.targetType().equals("PHYSICAL_VIEW")).singleElement().satisfies(c -> assertThat(c.change()).isEqualTo("ADDED"));
        ModelVersionDto.Comparison modified = versionService.compare(admin, first.id(), null);
        assertThat(modified.changes()).filteredOn(c -> c.targetType().equals("PHYSICAL_VIEW")).singleElement()
                .satisfies(c -> assertThat(c.changedFields()).containsExactlyInAnyOrder("definition", "columns", "references", "isApproval"));
        assertThat(modified.changes()).as("VIEW 만 바뀌었다").allMatch(c -> c.targetType().equals("PHYSICAL_VIEW"));

        // 예전 형식(VIEW 가 없는 FORMAT 3)의 버전과 비교 : 테이블은 그대로, VIEW 만 추가로 보인다
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var snapshot = (tools.jackson.databind.node.ObjectNode) json.readTree(jdbc.queryForObject("select MDL_CN from TB_MDL_VER where MDL_VER_SN = ?", String.class, first.id()));
        assertThat(snapshot.remove("PHYSICAL_VIEW")).isNotNull();
        jdbc.update("update TB_MDL_VER set MDL_CN = ?, FRMT_VER_NO = 3 where MDL_VER_SN = ?", json.writeValueAsString(snapshot), first.id());
        ModelVersionDto.Comparison legacy = versionService.compare(admin, first.id(), null);
        assertThat(legacy.changes()).singleElement().satisfies(c -> {
            assertThat(c.targetType()).isEqualTo("PHYSICAL_VIEW");
            assertThat(c.change()).isEqualTo("ADDED");
        });
        assertThat(legacy.byType().get("PHYSICAL_TABLE").get("UNCHANGED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 격리·권한·ERD

    @Test
    @DisplayName("프로젝트 격리 : 다른 프로젝트의 VIEW ID·변경 요청 ID 로는 조회·수정·승인·삭제·DDL 이 모두 거절된다. VIEWER 는 변경 API 가 막힌다")
    void isolationAndRoles() {
        AccountDto admin = loginAsAdmin("m15-iso");
        long projectId = admin.getProjectId();
        ViewDto.SaveResult view = viewService.create(admin, save("VW_ISO", null, "SELECT 1", List.of(), List.of()));
        AccountDto member = SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_USER");
        Long requestId = viewService.update(member, view.id(), save("VW_ISO", null, "SELECT 2", List.of(), List.of())).request().id();

        AccountDto stranger = loginAsAdmin("m15-iso-other");
        assertInvalid(() -> viewService.detail(stranger, view.id()), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.ddl(stranger, view.id(), null), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.update(stranger, view.id(), save("VW_ISO", null, "SELECT 3", List.of(), List.of())), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.updateApproval(stranger, view.id(), false), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.processRequest(stranger, requestId, true), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> viewService.delete(stranger, view.id()), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertThat(viewService.search(stranger, PageRequest.of(0, 10), null, null, null, null).getContent()).isEmpty();
        assertThat(viewService.erdViews(stranger, null)).isEmpty();

        // VIEWER : 조회는 되고 변경 API 는 역할에서 막힌다
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, projectId, "555555", "ROLE_VIEWER");
        assertThat(viewController.detail(viewer, view.id()).getBody().view().viewName()).isEqualTo("VW_ISO");
        assertThatThrownBy(() -> viewController.create(viewer, save("VW_V", null, "SELECT 1", List.of(), List.of()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> viewController.delete(viewer, view.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> viewController.processRequest(viewer, requestId, true)).isInstanceOf(AccessDeniedException.class);

        // ERD : 출력 컬럼·참조 객체를 담은 읽기 전용 노드
        SecurityTestSupport.loginAs(projectRepository, projectId, admin.getId(), "ROLE_ADMIN");
        assertThat(viewService.erdViews(admin, null)).singleElement().satisfies(v -> assertThat(v.viewName()).isEqualTo("VW_ISO"));
    }

    // ------------------------------------------------------------------ 보조

    private static ViewDto.Save save(String name, String korName, String sql, List<ViewDto.Column> columns, List<ViewDto.Reference> references) {
        return new ViewDto.Save(null, name, korName, null, sql, columns, references);
    }

    private static ViewDto.Column column(String name, String korName, String type) {
        return new ViewDto.Column(name, korName, type, null);
    }

    private static ViewDto.Reference tableRef(TableInfo table) {
        return new ViewDto.Reference("TABLE", table.getId(), null, null, null);
    }

    private static ViewDto.Reference viewRef(Long viewId) {
        return new ViewDto.Reference("VIEW", null, viewId, null, null);
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

    private AccountDto login(long projectId, String accountId) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, "ROLE_ADMIN");
    }

    private static void assertInvalid(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(code));
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).dbType(DbType.POSTGRESQL).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private void setDbType(long projectId, DbType dbType) {
        inTx(() -> projectRepository.findById(projectId).orElseThrow().setDbType(dbType));
    }

    private TableInfo table(long projectId, String name) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private void tableUnapproved(TableInfo table) {
        inTx(() -> tableInfoRepository.findById(table.getId()).orElseThrow().setIsApproval(false));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
