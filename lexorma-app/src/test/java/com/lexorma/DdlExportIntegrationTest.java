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
import com.lexorma.data.ddl.controller.DdlExportRestController;
import com.lexorma.data.ddl.domain.dto.DdlExportDto;
import com.lexorma.data.ddl.service.DdlExportService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnPair;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnRef;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Member;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import com.lexorma.data.view.domain.entity.ViewChangeRequest;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import com.lexorma.data.view.repository.ViewChangeRequestRepository;
import com.lexorma.data.view.service.PhysicalViewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M-11 통합 DDL Export (H2 모델 + PostgreSQL 실제 실행) : 실행 순서(SCHEMA → TABLE → INDEX → FK → VIEW), 순환·자기 참조·복합·고유 인덱스 참조 FK,
 * 키·인덱스 중복 없음, 고급 인덱스·VIEW 원문 보존, VIEW 참조 순서, 스키마 단위 범위 밖 참조 경고, 승인 기준 모드·변경 요청 미반영, DBMS 선택, 오류(컬럼 없음·VIEW 순환),
 * 프로젝트 격리·권한. PostgreSQL 실행은 LEXORMA_TEST_POSTGRES_URL 이 있을 때만 (임시 스키마를 만들고 지운다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DdlExportIntegrationTest {

    private static final Pattern CONSTRAINT = Pattern.compile("CONSTRAINT (\\S+) ");

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired PhysicalViewService viewService;
    @Autowired PhysicalViewRepository viewRepository;
    @Autowired ViewChangeRequestRepository viewRequestRepository;
    @Autowired DdlExportService exportService;
    @Autowired DdlExportRestController exportController;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("프로젝트 전체 : SCHEMA → TABLE → INDEX → FK → VIEW 순서, FK 는 모두 ALTER 로 뒤에(순환·자기 참조 포함), 키·인덱스는 한 번씩, VIEW 는 참조되는 것 먼저, 같은 모델이면 같은 DDL")
    void projectExportOrdersObjects() {
        AccountDto admin = loginAsAdmin("m11-project");
        Model model = buildModel(admin, "SX" + suffix());
        DdlExportDto result = exportService.export(admin, null, null, null, false);
        String ddl = result.ddl();

        assertThat(result.errors()).isEmpty();
        assertThat(result.scope()).isEqualTo("PROJECT");
        assertThat(result.dbType()).as("비우면 프로젝트 DBMS").isEqualTo("POSTGRESQL");
        assertThat(result.counts()).isEqualTo(new DdlExportDto.Counts(1, 6, 3, 0, 7, 5));
        assertThat(ddl).startsWith("-- ========================================\n-- Lexorma Integrated DDL Export\n-- DBMS: PostgreSQL\n-- Scope: PROJECT\n");
        assertThat(ddl).doesNotContainPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}").as("시각 등 비결정 값 없음");

        // 구역 순서와 문장 순서
        List<String> sections = List.of("-- 1. SCHEMAS", "-- 2. TABLES", "-- 3. INDEXES", "-- 4. FOREIGN KEYS", "-- 5. VIEWS");
        for (int i = 1; i < sections.size(); i++) assertThat(ddl.indexOf(sections.get(i - 1))).isLessThan(ddl.indexOf(sections.get(i)));
        assertThat(ddl).containsOnlyOnce("CREATE SCHEMA " + model.schemaName + ";");
        assertThat(ddl.lastIndexOf("CREATE TABLE")).isLessThan(ddl.indexOf("CREATE INDEX")).isLessThan(ddl.indexOf("CREATE UNIQUE INDEX"));
        assertThat(Math.max(ddl.lastIndexOf("CREATE INDEX"), ddl.lastIndexOf("CREATE UNIQUE INDEX"))).as("고유 인덱스를 참조하는 FK 가 있어 인덱스 먼저").isLessThan(ddl.indexOf("FOREIGN KEY"));
        assertThat(ddl.lastIndexOf("FOREIGN KEY")).isLessThan(ddl.indexOf("CREATE VIEW"));

        // FK : 부모·자식, 자기 참조, 서로 참조(순환), 복합, 고유 인덱스 참조, 스키마 간
        String x = model.schemaName;
        assertThat(ddl).contains(
                "ALTER TABLE TB_EMP ADD CONSTRAINT FK_TB_EMP_DEPT_ID FOREIGN KEY (DEPT_ID) REFERENCES TB_DEPT (DEPT_ID);",
                "ALTER TABLE TB_EMP ADD CONSTRAINT FK_TB_EMP_MGR_ID FOREIGN KEY (MGR_ID) REFERENCES TB_EMP (EMP_ID);",
                "ALTER TABLE " + x + ".TB_A ADD CONSTRAINT FK_A_B FOREIGN KEY (B_ID) REFERENCES " + x + ".TB_B (B_ID);",
                "ALTER TABLE " + x + ".TB_B ADD CONSTRAINT FK_B_A FOREIGN KEY (A_ID) REFERENCES " + x + ".TB_A (A_ID);",
                "ALTER TABLE TB_EMP_HIST ADD CONSTRAINT FK_HIST_PERIOD FOREIGN KEY (BASE_YR, BASE_MM) REFERENCES TB_PERIOD (BASE_YR, BASE_MM);",
                "ALTER TABLE TB_EMP_HIST ADD CONSTRAINT FK_HIST_EMP_KEY FOREIGN KEY (DEPT_ID, EMP_NM) REFERENCES TB_EMP (DEPT_ID, EMP_NM);",
                "ALTER TABLE " + x + ".TB_B ADD CONSTRAINT FK_TB_B_DEPT_ID FOREIGN KEY (DEPT_ID) REFERENCES TB_DEPT (DEPT_ID);");
        // 키·인덱스 중복 없음, 고급 인덱스 옵션 그대로
        assertThat(count(ddl, "PRIMARY KEY")).isEqualTo(6);
        List<String> names = constraintNames(ddl);
        assertThat(names).doesNotHaveDuplicates().contains("PK_TB_EMP", "UK_TB_DEPT_DEPT_NM", "CK_EMP_SAL");
        assertThat(ddl).containsOnlyOnce("CREATE INDEX IX_EMP_NM ON TB_EMP (EMP_NM);")
                .containsOnlyOnce("CREATE UNIQUE INDEX UX_EMP_DEPT_NM ON TB_EMP (DEPT_ID, EMP_NM);")
                .containsOnlyOnce("CREATE INDEX IX_EMP_LOWER_NM ON TB_EMP ((lower(EMP_NM)) DESC) INCLUDE (DEPT_ID) WHERE MGR_ID IS NOT NULL;")
                .contains("COMMENT ON TABLE TB_EMP IS '사원';", "COMMENT ON COLUMN TB_EMP.EMP_NM IS '사원 이름';");

        // VIEW : 참조되는 VIEW 먼저 (이름순이면 VW_ACTIVE_EMP 가 먼저지만 VW_EMP 를 참조한다), 원문 그대로
        assertThat(ddl.indexOf("CREATE VIEW VW_EMP AS")).isLessThan(ddl.indexOf("CREATE VIEW VW_ACTIVE_EMP AS"))
                .isLessThan(ddl.indexOf("CREATE VIEW VW_DEPT_EMP AS"));
        assertThat(ddl.indexOf("CREATE VIEW VW_ACTIVE_EMP AS")).isLessThan(ddl.indexOf("CREATE VIEW VW_EMP_SUMMARY AS"));
        assertThat(ddl).contains("CREATE VIEW VW_EMP_SUMMARY AS\n" + model.summarySql + ";\n", "CREATE VIEW " + x + ".VW_X AS\n");
        assertThat(result.warnings()).contains("VIEW 생성 순서는 등록된 참조 정보 기준입니다. 정의 SQL 이 실제로 참조하는 객체와 다르면 실행이 실패할 수 있습니다.")
                .noneMatch(w -> w.contains("스크립트 밖"));
        assertThat(result.pendingApprovals()).isEmpty();

        // 같은 모델이면 같은 DDL, 개별 테이블 DDL 은 예전 형식 그대로 (테이블 하나에 인덱스·FK·COMMENT)
        assertThat(exportService.export(admin, null, null, null, false).ddl()).isEqualTo(ddl);
        String single = tableDesignService.generateTableDdl(model.emp.getId(), admin, null).ddl();
        assertThat(single).startsWith("-- PostgreSQL DDL : TB_EMP (사원)\nCREATE TABLE TB_EMP (");
        assertThat(single.indexOf("CREATE INDEX IX_EMP_NM")).isLessThan(single.indexOf("FOREIGN KEY")).isLessThan(single.indexOf("COMMENT ON TABLE"));
        // 스키마명 포함 : 기본 스키마 객체에 붙인다
        assertThat(exportService.export(admin, null, null, "app", false).ddl()).contains("CREATE TABLE app.TB_EMP (", "REFERENCES app.TB_DEPT (DEPT_ID);", "CREATE VIEW app.VW_EMP AS")
                .contains("CREATE TABLE " + x + ".TB_A (");
    }

    @Test
    @DisplayName("스키마 단위 : 그 스키마의 객체만, 다른 스키마 테이블을 참조하는 FK·VIEW 는 넣되 경고한다 (자동으로 끌어오지 않는다)")
    void schemaExportWarnsOutsideReferences() {
        AccountDto admin = loginAsAdmin("m11-schema");
        Model model = buildModel(admin, "SX" + suffix());

        DdlExportDto x = exportService.export(admin, model.schemaId, null, null, false);
        assertThat(x.errors()).isEmpty();
        assertThat(x.scope()).isEqualTo("SCHEMA");
        assertThat(x.schemaName()).isEqualTo(model.schemaName);
        assertThat(x.counts()).isEqualTo(new DdlExportDto.Counts(1, 2, 0, 0, 3, 1));
        assertThat(x.ddl()).contains("CREATE TABLE " + model.schemaName + ".TB_A (", "CREATE TABLE " + model.schemaName + ".TB_B (")
                .doesNotContain("CREATE TABLE TB_EMP", "CREATE VIEW VW_EMP");
        assertThat(x.warnings()).contains(
                "이 스크립트 밖의 테이블을 참조하는 FK 가 있습니다 (대상 DB 에 먼저 있어야 합니다) : TB_B → TB_DEPT",
                "이 스크립트 밖의 테이블을 참조합니다 (대상 DB 에 먼저 있어야 합니다) : VW_X → TB_EMP");

        Long defaultSchemaId = schemaService.resolveSchema(admin.getProjectId(), null).getId();
        DdlExportDto base = exportService.export(admin, defaultSchemaId, null, null, false);
        assertThat(base.ddl()).doesNotContain("CREATE SCHEMA", model.schemaName + ".TB_A");
        assertThat(base.counts()).isEqualTo(new DdlExportDto.Counts(0, 4, 3, 0, 4, 4));
        assertThat(base.warnings()).noneMatch(w -> w.contains("스크립트 밖"));
    }

    @Test
    @DisplayName("승인 상태 : 기본은 승인 대기 항목을 포함하고 알리며, 승인 기준은 만들지 않는다. 승인 대기 변경 요청(VIEW·인덱스)은 어느 쪽이든 반영하지 않는다")
    void approvalModes() {
        AccountDto admin = loginAsAdmin("m11-approval");
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, null, "TB_P", null);
        column(table, "P_ID", "VARCHAR(20)", true, 1, null, null);
        ColumnInfo nm = column(table, "NM", "VARCHAR(20)", false, 2, null, null);
        PhysicalModelDto.Index index = physicalModelService.saveIndex(admin, index(table, "IX_P_NM", false, null, List.of(key(nm, null)), List.of()));
        ViewDto.SaveResult view = viewService.create(admin, new ViewDto.Save(null, "VW_P", null, null, "SELECT P_ID FROM TB_P", List.of(), List.of(tableRef(table))));
        inTx(() -> viewRequestRepository.save(ViewChangeRequest.builder().projectId(projectId).view(viewRepository.getReferenceById(view.id()))
                .beforeData("{}").afterData("{}").status(ViewChangeRequest.Status.REQUESTED).build()));

        DdlExportDto approved = exportService.export(admin, null, null, null, true);
        assertThat(approved.errors()).isEmpty();
        assertThat(approved.ddl()).contains("Model: APPROVED", "CREATE VIEW VW_P AS\nSELECT P_ID FROM TB_P;");
        assertThat(approved.warnings()).contains("승인 대기 중인 VIEW 변경 요청 1건은 반영하지 않았습니다 (확정된 정의 기준).");

        // 승인 대기 : 컬럼·인덱스·VIEW
        inTx(() -> {
            columnInfoRepository.findById(nm.getId()).orElseThrow().setIsApproval(false);
            indexRepository.findById(index.id()).orElseThrow().setIsApproval(false);
            viewRepository.findById(view.id()).orElseThrow().setIsApproval(false);
        });
        DdlExportDto design = exportService.export(admin, null, null, null, false);
        assertThat(design.errors()).isEmpty();
        assertThat(design.ddl()).contains("Model: DESIGN", "    NM   VARCHAR(20)", "CREATE INDEX IX_P_NM ON TB_P (NM);", "CREATE VIEW VW_P AS");
        assertThat(design.pendingApprovals()).containsExactly("컬럼 : TB_P.NM", "인덱스 : IX_P_NM", "VIEW : VW_P");

        DdlExportDto blocked = exportService.export(admin, null, null, null, true);
        assertThat(blocked.ddl()).as("불완전한 스크립트를 주지 않는다").isEmpty();
        assertThat(blocked.errors()).containsExactly("승인 기준 Export 는 범위 안에 승인 대기 항목이 없어야 합니다. 승인을 마치거나 승인 기준을 끄고 설계용 DDL 로 받아주세요.");
        assertThat(blocked.pendingApprovals()).isEqualTo(design.pendingApprovals());
    }

    @Test
    @DisplayName("DBMS 선택 : 프로젝트 DBMS 와 다르게 고를 수 있고, 만들 수 없는 옵션은 지우지 않고 주석+경고, Oracle 스키마는 만들지 않고 알린다. 잘린 이름의 충돌을 경고한다")
    void dbmsSelection() {
        AccountDto admin = loginAsAdmin("m11-dbms");
        Model model = buildModel(admin, "SX" + suffix());
        String ux = model.schemaName;

        DdlExportDto mysql = exportService.export(admin, null, "mysql", null, false);
        assertThat(mysql.dbType()).isEqualTo("MYSQL");
        assertThat(mysql.counts().indexes()).isEqualTo(2);
        assertThat(mysql.counts().commentedIndexes()).isEqualTo(1);
        assertThat(mysql.ddl()).contains("-- CREATE INDEX IX_EMP_LOWER_NM ON TB_EMP ((lower(EMP_NM)) DESC) INCLUDE (DEPT_ID) WHERE MGR_ID IS NOT NULL;", "CREATE SCHEMA " + ux + ";", "ENGINE=InnoDB")
                .doesNotContain("COMMENT ON");
        assertThat(mysql.warnings()).anyMatch(w -> w.startsWith("TB_EMP · IX_EMP_LOWER_NM : ")).contains("MySQL 에서는 통합 DDL 의 실제 실행을 검증하지 않았습니다.");

        DdlExportDto oracle = exportService.export(admin, null, "ORACLE", null, false);
        assertThat(oracle.ddl()).contains("-- " + ux + " : Oracle 의 스키마는 사용자(계정)라 이 스크립트에서 만들지 않습니다.").doesNotContain("CREATE SCHEMA");
        assertThat(oracle.counts().schemas()).isZero();
        assertThat(oracle.warnings()).contains("Oracle 은 스키마가 사용자(계정)라 CREATE SCHEMA 를 넣지 않았습니다. 대상 스키마 사용자를 먼저 만들어야 합니다.");

        // Oracle 30자 : 앞 30자가 같은 인덱스 이름 둘
        ColumnInfo cd = column(model.emp, "EMP_CD", "VARCHAR(20)", false, 9, null, null);
        physicalModelService.saveIndex(admin, index(model.emp, "IX_EMP_VERY_LONG_INDEX_NAME_AAA_1", false, null, List.of(key(cd, null)), List.of()));
        ColumnInfo cd2 = column(model.emp, "EMP_CD2", "VARCHAR(20)", false, 10, null, null);
        physicalModelService.saveIndex(admin, index(model.emp, "IX_EMP_VERY_LONG_INDEX_NAME_AAA_2", false, null, List.of(key(cd, null), key(cd2, null)), List.of()));
        assertThat(exportService.export(admin, null, "ORACLE", null, false).warnings())
                .contains("같은 스키마 안에서 이름이 겹치는 객체가 있어 실행이 실패할 수 있습니다 (길이 제한으로 자른 이름 포함) : IX_EMP_VERY_LONG_INDEX_NAME_AA");
        assertThat(exportService.export(admin, null, null, null, false).warnings()).noneMatch(w -> w.startsWith("같은 스키마 안에서"));

        assertInvalid(() -> exportService.export(admin, null, "SQLITE", null, false), ErrorCode.INVALID_PHYSICAL_MODEL);
        assertInvalid(() -> exportService.export(admin, null, null, "bad-name", false), ErrorCode.INVALID_SCHEMA_NAME);
    }

    @Test
    @DisplayName("오류 : 컬럼 없는 테이블·순서를 정할 수 없는 VIEW 순환이 있으면 DDL 을 주지 않는다. 빈 프로젝트는 머리말만")
    void errorsAndEmptyProject() {
        AccountDto empty = loginAsAdmin("m11-empty");
        DdlExportDto none = exportService.export(empty, null, null, null, false);
        assertThat(none.errors()).isEmpty();
        assertThat(none.counts()).isEqualTo(new DdlExportDto.Counts(0, 0, 0, 0, 0, 0));
        assertThat(none.ddl()).startsWith("-- ====").doesNotContain("-- 1.", "-- 2.", "CREATE");
        assertThat(none.warnings()).contains("내보낼 테이블·VIEW 가 없습니다.");

        AccountDto admin = loginAsAdmin("m11-errors");
        long projectId = admin.getProjectId();
        TableInfo ok = table(projectId, null, "TB_OK", null);
        column(ok, "OK_ID", "VARCHAR(20)", true, 1, null, null);
        table(projectId, null, "TB_NO_COL", null);
        DdlExportDto noColumns = exportService.export(admin, null, null, null, false);
        assertThat(noColumns.ddl()).isEmpty();
        assertThat(noColumns.errors()).containsExactly("TB_NO_COL : 컬럼이 없어 CREATE TABLE 을 만들 수 없습니다.");

        AccountDto admin2 = loginAsAdmin("m11-cycle");
        ViewDto.SaveResult c1 = viewService.create(admin2, new ViewDto.Save(null, "VW_C1", null, null, "SELECT 1 AS N", List.of(), List.of()));
        ViewDto.SaveResult c2 = viewService.create(admin2, new ViewDto.Save(null, "VW_C2", null, null, "SELECT N FROM VW_C1", List.of(), List.of(viewRef(c1.id()))));
        // 등록 경로는 순환을 막으므로 저장소로 직접 만든다 (예전 데이터·직접 수정 대비)
        inTx(() -> {
            PhysicalView first = viewRepository.findById(c1.id()).orElseThrow();
            first.getReferences().add(PhysicalViewReference.builder().view(first).referenceType(PhysicalViewReference.Type.VIEW)
                    .referencedView(viewRepository.getReferenceById(c2.id())).build());
        });
        DdlExportDto cycle = exportService.export(admin2, null, null, null, false);
        assertThat(cycle.ddl()).isEmpty();
        assertThat(cycle.errors()).containsExactly("순환 참조가 있어 생성 순서를 정할 수 없는 VIEW 가 있습니다 : VW_C1, VW_C2");
        assertThat(cycle.warnings()).noneMatch(w -> w.startsWith("순환 참조"));
    }

    @Test
    @DisplayName("격리·권한 : 다른 프로젝트 객체·스키마는 보이지 않고, VIEWER 는 조회할 수 있으며 NOT_APPROVE 는 거절된다")
    void isolationAndAuthorization() {
        AccountDto admin = loginAsAdmin("m11-iso");
        Model model = buildModel(admin, "SX" + suffix());
        AccountDto other = loginAsAdmin("m11-iso-other");
        table(other.getProjectId(), null, "TB_OTHER", null);

        DdlExportDto otherExport = exportService.export(other, null, null, null, false);
        assertThat(otherExport.ddl()).doesNotContain("TB_EMP", "VW_EMP", model.schemaName);
        assertInvalid(() -> exportService.export(other, model.schemaId, null, null, false), ErrorCode.SCHEMA_IS_NOT_DEFINED);

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "555555", "ROLE_VIEWER");
        assertThat(exportController.export(viewer, null, null, null, false).getBody().ddl()).contains("CREATE TABLE TB_EMP (");
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "555556", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> exportController.export(notApproved, null, null, null, false)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("PostgreSQL 실제 실행 : 통합 DDL 을 빈 임시 스키마에서 한 번에 실행하면 테이블·제약·인덱스·FK·VIEW 가 생성 결과와 같게 만들어진다")
    void generatedDdlRunsOnPostgresql() {
        String url = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(url != null, "PostgreSQL 검증 DB 미지정");
        AccountDto admin = loginAsAdmin("m11-pg");
        String base = "m11_" + suffix().toLowerCase(Locale.ROOT);
        Model model = buildModel(admin, "SX" + suffix());
        String x = model.schemaName.toLowerCase(Locale.ROOT);
        DdlExportDto result = exportService.export(admin, null, "POSTGRESQL", base, false);
        assertThat(result.errors()).isEmpty();

        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, "lexorma", "lexorma", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create schema " + base);
        try {
            jdbc.execute("set search_path to " + base); // 정의 SQL 의 스키마 없는 이름 (기본 스키마 = 접속 계정의 스키마)
            jdbc.execute(result.ddl()); // 한 번에 (암묵적 트랜잭션 : 하나라도 실패하면 아무것도 남지 않는다)

            assertThat(jdbc.queryForList("select table_schema || '.' || table_name from information_schema.tables where table_schema in (?, ?) and table_type = 'BASE TABLE'",
                    String.class, base, x)).hasSize(result.counts().tables()).contains(base + ".tb_emp", x + ".tb_a");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema in (?, ?) and constraint_type = 'PRIMARY KEY'",
                    Integer.class, base, x)).isEqualTo(6);
            assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints where table_schema in (?, ?) and constraint_type = 'FOREIGN KEY'",
                    String.class, base, x)).hasSize(result.counts().foreignKeys())
                    .contains("fk_a_b", "fk_b_a", "fk_tb_emp_mgr_id", "fk_hist_period", "fk_hist_emp_key", "fk_tb_b_dept_id");
            assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints where table_schema = ? and constraint_type in ('UNIQUE', 'CHECK') and constraint_name not like '%_not_null'",
                    String.class, base)).contains("uk_tb_dept_dept_nm", "ck_emp_sal");
            assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = ? and indexname = 'ix_emp_lower_nm'", String.class, base))
                    .contains("lower((emp_nm)::text) DESC", "INCLUDE (dept_id)", "WHERE (mgr_id IS NOT NULL)");
            // 직접 만든 인덱스 = CREATE INDEX 수 (PK·UNIQUE 제약의 인덱스는 따로 만들지 않았다)
            assertThat(jdbc.queryForObject("select count(*) from pg_indexes i where i.schemaname in (?, ?) and not exists (select 1 from pg_constraint c where c.conname = i.indexname)",
                    Integer.class, base, x)).isEqualTo(result.counts().indexes());
            assertThat(jdbc.queryForList("select table_schema || '.' || table_name from information_schema.views where table_schema in (?, ?)", String.class, base, x))
                    .containsExactlyInAnyOrder(base + ".vw_emp", base + ".vw_active_emp", base + ".vw_emp_summary", base + ".vw_dept_emp", x + ".vw_x");
            // VIEW 참조 관계가 DB 에서도 성립한다 (VW_EMP_SUMMARY → VW_ACTIVE_EMP → VW_EMP)
            assertThat(jdbc.queryForList("select distinct v.relname from pg_depend d join pg_rewrite r on r.oid = d.objid join pg_class v on v.oid = r.ev_class "
                    + "join pg_class t on t.oid = d.refobjid where t.relname = 'vw_active_emp' and v.relname <> t.relname", String.class)).containsExactly("vw_emp_summary");
            jdbc.update("insert into tb_dept(dept_id, dept_nm) values ('D1', '개발')");
            jdbc.update("insert into tb_emp(emp_id, emp_nm, dept_id, mgr_id, sal_amt) values ('E1', '김', 'D1', null, 1), ('E2', '이', 'D1', 'E1', 2)");
            assertThat(jdbc.queryForObject("select cnt from vw_emp_summary where dept_id = 'D1'", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select obj_description((? || '.tb_emp')::regclass, 'pg_class')", String.class, base)).isEqualTo("사원");
            assertThatThrownBy(() -> jdbc.update("insert into tb_emp(emp_id, emp_nm, dept_id, sal_amt) values ('E3', '박', 'NONE', 0)")).as("FK 동작").isNotNull();
        } finally {
            jdbc.execute("drop schema if exists " + x + " cascade");
            jdbc.execute("drop schema if exists " + base + " cascade");
            dataSource.destroy();
        }
    }

    // ------------------------------------------------------------------ 모델

    /** 기본 스키마 : TB_DEPT·TB_EMP·TB_PERIOD·TB_EMP_HIST + VIEW 4개, 스키마 X : TB_A·TB_B(서로 참조) + VW_X */
    private record Model(String schemaName, Long schemaId, TableInfo emp, String summarySql) {}

    private Model buildModel(AccountDto admin, String schemaName) {
        long projectId = admin.getProjectId();
        SchemaDto schema = schemaService.saveSchema(admin, new SchemaDto(null, schemaName, null, false, 0L, null, null, null, null));

        TableInfo dept = table(projectId, null, "TB_DEPT", "부서");
        column(dept, "DEPT_ID", "VARCHAR(20)", true, 1, null, null);
        ColumnInfo deptNm = column(dept, "DEPT_NM", "VARCHAR(100)", false, 2, null, null);
        inTx(() -> columnInfoRepository.findById(deptNm.getId()).orElseThrow().setIsUnique(true));

        TableInfo emp = table(projectId, null, "TB_EMP", "사원");
        column(emp, "EMP_ID", "VARCHAR(20)", true, 1, null, null);
        ColumnInfo empNm = column(emp, "EMP_NM", "VARCHAR(100)", false, 2, null, "사원 이름");
        ColumnInfo empDept = column(emp, "DEPT_ID", "VARCHAR(20)", false, 3, "TB_DEPT", null);
        ColumnInfo mgr = column(emp, "MGR_ID", "VARCHAR(20)", false, 4, "TB_EMP", null);
        column(emp, "SAL_AMT", "NUMERIC(10)", false, 5, null, null);
        physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, emp.getId(), "CK_EMP_SAL", "CHECK", "SAL_AMT >= 0", List.of(), null));
        physicalModelService.saveIndex(admin, index(emp, "IX_EMP_NM", false, null, List.of(key(empNm, null)), List.of()));
        physicalModelService.saveIndex(admin, index(emp, "UX_EMP_DEPT_NM", true, null, List.of(key(empDept, null), key(empNm, null)), List.of()));
        physicalModelService.saveIndex(admin, index(emp, "IX_EMP_LOWER_NM", false, "MGR_ID IS NOT NULL",
                List.of(new Member(null, null, "lower(EMP_NM)", "DESC", null)), List.of(new ColumnRef(empDept.getId(), null))));

        TableInfo period = table(projectId, null, "TB_PERIOD", "기간");
        ColumnInfo yr = column(period, "BASE_YR", "VARCHAR(4)", true, 1, null, null);
        ColumnInfo mm = column(period, "BASE_MM", "VARCHAR(2)", true, 2, null, null);

        TableInfo hist = table(projectId, null, "TB_EMP_HIST", "사원 이력");
        column(hist, "HIST_ID", "VARCHAR(20)", true, 1, null, null);
        ColumnInfo histYr = column(hist, "BASE_YR", "VARCHAR(4)", false, 2, null, null);
        ColumnInfo histMm = column(hist, "BASE_MM", "VARCHAR(2)", false, 3, null, null);
        ColumnInfo histDept = column(hist, "DEPT_ID", "VARCHAR(20)", false, 4, null, null);
        ColumnInfo histNm = column(hist, "EMP_NM", "VARCHAR(100)", false, 5, null, null);
        relation(admin, "FK_HIST_PERIOD", hist, period, List.of(histYr, histMm), List.of(yr, mm));
        relation(admin, "FK_HIST_EMP_KEY", hist, emp, List.of(histDept, histNm), List.of(empDept, empNm)); // 고유 인덱스 UX_EMP_DEPT_NM 를 참조

        TableInfo a = table(projectId, schema.id(), "TB_A", "A");
        ColumnInfo aId = column(a, "A_ID", "VARCHAR(20)", true, 1, null, null);
        ColumnInfo aB = column(a, "B_ID", "VARCHAR(20)", false, 2, null, null);
        TableInfo b = table(projectId, schema.id(), "TB_B", "B");
        ColumnInfo bId = column(b, "B_ID", "VARCHAR(20)", true, 1, null, null);
        ColumnInfo bA = column(b, "A_ID", "VARCHAR(20)", false, 2, null, null);
        column(b, "DEPT_ID", "VARCHAR(20)", false, 3, "TB_DEPT", null); // 스키마 간 FK
        relation(admin, "FK_A_B", a, b, List.of(aB), List.of(bId));
        relation(admin, "FK_B_A", b, a, List.of(bA), List.of(aId)); // 순환

        ViewDto.SaveResult vwEmp = viewService.create(admin, view(null, "VW_EMP", "사원 VIEW", "SELECT EMP_ID, EMP_NM, DEPT_ID, MGR_ID FROM TB_EMP", tableRef(emp)));
        ViewDto.SaveResult active = viewService.create(admin, view(null, "VW_ACTIVE_EMP", null,
                "SELECT EMP_ID, EMP_NM, DEPT_ID\n  FROM VW_EMP -- 관리자 있는 사원\n WHERE MGR_ID IS NOT NULL", viewRef(vwEmp.id())));
        String summarySql = "SELECT DEPT_ID, count(*) AS CNT /* 부서별 */ FROM VW_ACTIVE_EMP GROUP BY DEPT_ID";
        viewService.create(admin, view(null, "VW_EMP_SUMMARY", null, summarySql, viewRef(active.id())));
        viewService.create(admin, new ViewDto.Save(null, "VW_DEPT_EMP", null, null, "SELECT d.DEPT_NM, e.EMP_NM FROM TB_DEPT d JOIN VW_EMP e ON e.DEPT_ID = d.DEPT_ID",
                List.of(), List.of(tableRef(dept), viewRef(vwEmp.id()))));
        viewService.create(admin, new ViewDto.Save(schema.id(), "VW_X", null, null,
                "SELECT a.A_ID FROM " + schemaName + ".TB_A a WHERE EXISTS (SELECT 1 FROM TB_EMP e WHERE e.EMP_ID = a.A_ID)", List.of(), List.of(tableRef(a), tableRef(emp))));
        return new Model(schemaName, schema.id(), emp, summarySql);
    }

    private void relation(AccountDto admin, String name, TableInfo source, TableInfo target, List<ColumnInfo> sources, List<ColumnInfo> targets) {
        List<ColumnPair> pairs = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) pairs.add(new ColumnPair(sources.get(i).getId(), null, targets.get(i).getId(), null));
        physicalModelService.saveRelation(admin, new PhysicalModelDto.Relation(null, name, "FK", null, source.getId(), null, target.getId(), null, pairs, null));
    }

    private static ViewDto.Save view(Long schemaId, String name, String korName, String sql, ViewDto.Reference reference) {
        return new ViewDto.Save(schemaId, name, korName, null, sql, List.of(), List.of(reference));
    }

    private static ViewDto.Reference tableRef(TableInfo table) {
        return new ViewDto.Reference("TABLE", table.getId(), null, null, null);
    }

    private static ViewDto.Reference viewRef(Long viewId) {
        return new ViewDto.Reference("VIEW", null, viewId, null, null);
    }

    private static PhysicalModelDto.Index index(TableInfo table, String name, boolean unique, String condition, List<Member> members, List<ColumnRef> includes) {
        return new PhysicalModelDto.Index(null, table.getId(), name, null, unique, null, null, null, members, includes, condition);
    }

    private static Member key(ColumnInfo column, String direction) {
        return new Member(column.getId(), null, null, direction, null);
    }

    // ------------------------------------------------------------------ 보조

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) count++;
        return count;
    }

    private static List<String> constraintNames(String ddl) {
        List<String> names = new ArrayList<>();
        Matcher matcher = CONSTRAINT.matcher(ddl);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
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

    private TableInfo table(long projectId, Long schemaId, String name, String korName) {
        return inTx(() -> {
            SchemaInfo schema = schemaService.resolveSchema(projectId, schemaId);
            return tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).tableKorName(korName).isApproval(true).schema(schema).build());
        });
    }

    private ColumnInfo column(TableInfo table, String name, String type, boolean pk, int order, String refTable, String desc) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnName(name).dataType(type).isPk(pk).sortOrder(order)
                .refTableName(refTable).columnDesc(desc).isApproval(true).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }

    private void inTx(Runnable runnable) {
        tx.executeWithoutResult(s -> runnable.run());
    }
}
