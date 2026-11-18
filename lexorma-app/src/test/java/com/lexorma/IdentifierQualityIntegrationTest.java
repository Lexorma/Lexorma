package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.controller.StandardizationQualityRestController;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Issue;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Search;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto.Summary;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ModelIssue;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ModelSearch;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto.ModelSummary;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.dictionary.quality.StandardizationRule.TargetType;
import com.lexorma.data.dictionary.service.IdentifierQualityService;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.ddl.DbIdentifierCheck;
import com.lexorma.data.table.ddl.DbIdentifierRules.Kind;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DBMS 식별자 진단 (M-7 보강 1차 — M-5 D3, H2 / PostgreSQL, 실제 JPA 쿼리) : 프로젝트 DBMS 기준 판정, 요약 = 목록, 조건, fingerprint, 프로젝트 격리, 권한,
 * 조회 전후 데이터 불변, 기존 컬럼 요약(대시보드)·모델 진단에 식별자 결과가 섞이지 않는지. 판정 규칙의 세부 사례는 lexorma-data 의 DbIdentifierRulesTest·DbIdentifierCheckTest.
 * AI Provider 를 등록하지 않은 상태로 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class IdentifierQualityIntegrationTest {

    private static final Pageable PAGE = PageRequest.of(0, 100);
    private static final Search ALL = new Search(null, null, null, null, null, null);

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired SchemaService schemaService;
    @Autowired IdentifierQualityService identifierService;
    @Autowired StandardizationQualityService qualityService;
    @Autowired StandardizationQualityRestController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("Oracle 프로젝트 : 스키마·테이블·컬럼 길이(30바이트), 권장 형식, PK 이름 잘림·충돌, UNIQUE 자동 이름 — 요약 = 목록, 조건, fingerprint")
    void oracle() {
        AccountDto admin = loginAsAdmin("ident-oracle", DbType.ORACLE);
        long projectId = admin.getProjectId();
        SchemaInfo sales = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(projectId).schemaName("SALES_" + "S".repeat(25)).isDefault(false).build()));
        TableInfo a = table(projectId, "TB_" + "X".repeat(24) + "A", null);       // 28자 : PK_ 를 붙이면 31자 → 30자에서 B 와 같은 이름
        TableInfo b = table(projectId, "TB_" + "X".repeat(24) + "B", null);
        TableInfo korean = table(projectId, "고객", null);                         // Oracle 은 받지만 권장 형식이 아니다
        TableInfo inSales = table(projectId, "ORDERS", sales);
        column(projectId, a, "ID", true, false);
        column(projectId, b, "ID", true, false);
        column(projectId, korean, "CUSTOMER_IDENTIFIER_NUMBER_XYZ1", false, false); // 31자
        column(projectId, inSales, "EMAIL_ADDRESS_FOR_NOTIFICATION", false, true);   // UK_ORDERS_EMAIL… 잘림

        Map<String, Object> before = jdbc.queryForMap("select count(*) c, max(MDFCN_DT) m from TB_COL where PRJ_SN = ?", projectId);
        Summary summary = identifierService.selectSummary(admin);
        assertThat(summary.dbType()).isEqualTo(DbType.ORACLE);
        assertThat(summary.lengthBasis()).contains("30");
        assertThat(summary.totalSchemas()).isEqualTo(1);
        assertThat(summary.totalTables()).isEqualTo(4);
        assertThat(summary.totalColumns()).isEqualTo(4);
        assertThat(summary.rules()).containsEntry(StandardizationRule.IDENTIFIER_LENGTH_EXCEEDED, 2L)
                .containsEntry(StandardizationRule.IDENTIFIER_NOT_RECOMMENDED, 1L)
                .containsEntry(StandardizationRule.CONSTRAINT_NAME_TRUNCATED, 3L)
                .containsEntry(StandardizationRule.CONSTRAINT_NAME_COLLISION, 2L)
                .containsEntry(StandardizationRule.IDENTIFIER_INVALID_CHARACTER, 0L)
                .hasSize(5);
        assertThat(summary.errors()).isEqualTo(4);
        assertThat(summary.warnings()).isEqualTo(4);

        List<Issue> issues = identifierService.selectIssues(admin, ALL, PAGE).getContent();
        assertThat(issues).hasSize(8);
        assertThat(issues).extracting(Issue::type, Issue::kind, Issue::tableId, Issue::columnId).contains(
                tuple(StandardizationRule.IDENTIFIER_LENGTH_EXCEEDED, Kind.SCHEMA, null, null),
                tuple(StandardizationRule.IDENTIFIER_NOT_RECOMMENDED, Kind.TABLE, korean.getId(), null),
                tuple(StandardizationRule.CONSTRAINT_NAME_COLLISION, Kind.PRIMARY_KEY, a.getId(), null),
                tuple(StandardizationRule.CONSTRAINT_NAME_COLLISION, Kind.PRIMARY_KEY, b.getId(), null));
        Issue collision = issues.stream().filter(i -> i.type() == StandardizationRule.CONSTRAINT_NAME_COLLISION && a.getId().equals(i.tableId())).findFirst().orElseThrow();
        assertThat(collision.severity()).isEqualTo(Severity.ERROR);
        assertThat(collision.ddlName()).hasSize(30);
        assertThat(collision.related()).singleElement().satisfies(r -> assertThat(r).contains(b.getTableName()));
        assertThat(collision.basis()).contains("Oracle");
        assertThat(collision.fingerprint()).startsWith("v1:");
        Issue length = issues.stream().filter(i -> i.type() == StandardizationRule.IDENTIFIER_LENGTH_EXCEEDED && i.kind() == Kind.COLUMN).findFirst().orElseThrow();
        assertThat(length.length()).isEqualTo(31);
        assertThat(length.maxLength()).isEqualTo(30);
        assertThat(length.tableName()).isEqualTo("고객");
        assertThat(identifierService.selectIssues(admin, ALL, PAGE).getContent()).extracting(Issue::fingerprint)
                .as("같은 데이터면 같은 fingerprint").containsExactlyElementsOf(issues.stream().map(Issue::fingerprint).toList());
        assertThat(issues).extracting(Issue::id).doesNotHaveDuplicates();

        // 조건 : 유형·심각도·대상 종류·스키마·검색
        assertThat(identifierService.selectIssues(admin, new Search(StandardizationRule.CONSTRAINT_NAME_TRUNCATED, null, null, null, null, null), PAGE).getTotalElements()).isEqualTo(3);
        assertThat(identifierService.selectIssues(admin, new Search(null, Severity.ERROR, null, null, null, null), PAGE).getTotalElements()).isEqualTo(summary.errors());
        assertThat(identifierService.selectIssues(admin, new Search(null, null, Kind.UNIQUE, null, null, null), PAGE).getContent())
                .singleElement().satisfies(i -> assertThat(i.type()).isEqualTo(StandardizationRule.CONSTRAINT_NAME_TRUNCATED));
        assertThat(identifierService.selectIssues(admin, new Search(null, null, null, sales.getId(), null, null), PAGE).getContent())
                .extracting(Issue::kind).containsExactlyInAnyOrder(Kind.SCHEMA, Kind.UNIQUE);
        assertThat(identifierService.selectIssues(admin, new Search(null, null, null, null, "tableName", "고객"), PAGE).getTotalElements()).isEqualTo(2);

        // 조회는 아무것도 바꾸지 않는다
        assertThat(jdbc.queryForMap("select count(*) c, max(MDFCN_DT) m from TB_COL where PRJ_SN = ?", projectId)).isEqualTo(before);

        // 같은 모델을 PostgreSQL 로 보면 길이·잘림·충돌이 없고 권장 형식만 남는다 (프로젝트 DBMS 반영)
        jdbc.update("update TB_PRJ set DB_TYPE_CD = 'POSTGRESQL' where PRJ_SN = ?", projectId);
        assertThat(identifierService.selectIssues(admin, ALL, PAGE).getContent()).extracting(Issue::type).containsExactly(StandardizationRule.IDENTIFIER_NOT_RECOMMENDED);
    }

    @Test
    @DisplayName("PostgreSQL : 자동 PK 이름과 같은 스키마의 인덱스 이름이 같으면 충돌. 저장된 이름끼리의 그대로 중복은 알리지 않는다")
    void postgresqlCollision() {
        AccountDto admin = loginAsAdmin("ident-pg", DbType.POSTGRESQL);
        long projectId = admin.getProjectId();
        TableInfo cust = table(projectId, "CUST", null);
        TableInfo ord = table(projectId, "ORD", null);
        column(projectId, cust, "CUST_ID", true, false);
        index(projectId, ord, "PK_CUST");
        index(projectId, ord, "IX_SAME");
        index(projectId, cust, "IX_SAME");
        assertThat(identifierService.selectIssues(admin, ALL, PAGE).getContent()).singleElement().satisfies(i -> {
            assertThat(i.type()).isEqualTo(StandardizationRule.CONSTRAINT_NAME_COLLISION);
            assertThat(i.kind()).isEqualTo(Kind.PRIMARY_KEY);
            assertThat(i.tableId()).isEqualTo(cust.getId());
            assertThat(i.related()).singleElement().satisfies(r -> assertThat(r).startsWith("PK_CUST").contains("ORD"));
        });
    }

    @Test
    @DisplayName("기존 진단과 분리 : 컬럼 요약(대시보드)·모델 진단 요약·목록에 식별자 규칙이 없고 값이 그대로다. 프로젝트 격리")
    void separatedFromExistingDiagnosis() {
        AccountDto admin = loginAsAdmin("ident-separate", DbType.ORACLE);
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "T_" + "L".repeat(40), null);
        column(projectId, table, "C_" + "L".repeat(40), true, false);

        assertThat(identifierService.selectSummary(admin).errors()).isPositive();
        ModelSummary model = qualityService.selectModelSummary(admin);
        List<ModelIssue> modelIssues = qualityService.selectModelIssues(admin, new ModelSearch(null, null, null, null, null, null, null), PAGE).getContent();
        assertThat(modelIssues).extracting(ModelIssue::targetType).doesNotContain(TargetType.IDENTIFIER);
        assertThat(modelIssues).extracting(ModelIssue::type).noneMatch(rule -> rule.targetType() == TargetType.IDENTIFIER);
        assertThat(model.errors() + model.warnings() + model.infos()).isEqualTo(modelIssues.size());
        assertThat(model.columns()).isEqualTo(qualityService.selectSummary(admin));
        // 컬럼 1개 : 용어 미연결 1건만 (식별자 문제는 기존 요약의 문제 컬럼 수에 들어가지 않는다)
        assertThat(model.columns().totalColumns()).isEqualTo(1);
        assertThat(model.columns().issueColumns()).isEqualTo(1);
        assertThat(model.columns().termNotLinked()).isEqualTo(1);
        // 모델 진단 type 에 식별자 규칙을 주면 결과가 없다 (식별자 결과는 /identifiers 에서만)
        assertThat(qualityService.selectModelIssues(admin, new ModelSearch(StandardizationRule.IDENTIFIER_LENGTH_EXCEEDED, null, null, null, null, null, null), PAGE)
                .getTotalElements()).isZero();

        AccountDto other = loginAsAdmin("ident-separate-other", DbType.ORACLE);
        Summary empty = identifierService.selectSummary(other);
        assertThat(empty.totalTables()).isZero();
        assertThat(empty.errors() + empty.warnings()).isZero();
        assertThat(identifierService.selectIssues(other, ALL, PAGE).getContent()).isEmpty();
    }

    @Test
    @DisplayName("권한 : VIEWER 부터 조회, 승인 전 사용자는 거절. 잘못된 조건 값은 컨트롤러 바인딩에서 거절(400 — SmokeTest)")
    void authorization() {
        Project project = projectRepository.save(Project.builder().projectName("ident-role").isActive(true).account(admin()).build());
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, project.getId(), "900", "ROLE_VIEWER");
        assertThat(controller.selectIdentifierSummary(viewer).getBody()).isNotNull();
        assertThat(controller.selectIdentifierIssues(viewer, 0, 10, null, null, null, null, null, null, null).getStatusCode().value()).isEqualTo(200);
        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, project.getId(), "901", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> controller.selectIdentifierSummary(notApproved)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.selectIdentifierIssues(notApproved, 0, 10, null, null, null, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * LEXORMA 예제(프로젝트 0)를 새로 마이그레이션한 빈 DB 에서 판정한다 (StandardizationQualityRulesIntegrationTest.example 과 같은 방식 — 같은 DB 를 쓰는 다른 테스트가
     * 프로젝트 0 에 테이블을 더한다). 예제의 대상 DB(PostgreSQL)에서는 0건이어야 한다. 다른 DBMS 의 결과는 판정 기준 차이를 보여 주는 값이라 고정한다 (예제를 고치지 않는다)
     */
    @ParameterizedTest(name = "LEXORMA 예제 {0} : 프로젝트 DBMS(PostgreSQL)·MySQL·MariaDB 기준 0건, Oracle 기준 FK 자동 이름 잘림 7건")
    @ValueSource(strings = {"h2", "postgresql"})
    void example(String vendor) {
        String postgresUrl = System.getenv("LEXORMA_TEST_POSTGRES_URL");
        assumeTrue(vendor.equals("h2") || postgresUrl != null, "PostgreSQL 검증 DB 미지정");
        String schema = "m7_ident_" + UUID.randomUUID().toString().replace("-", "");
        String url = vendor.equals("h2") ? "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1" : postgresUrl;
        var dataSource = new DriverManagerDataSource(url, "lexorma", "lexorma");
        Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration/" + vendor).cleanDisabled(false).load();
        try {
            flyway.migrate();
            var example = new JdbcTemplate(dataSource);
            String q = "\"" + schema + "\".";
            assertThat(example.queryForObject("select DB_TYPE_CD from " + q + "TB_PRJ where PRJ_SN = 0", String.class)).isEqualTo("POSTGRESQL");
            var schemas = example.query("select SCHM_SN, SCHM_NM, BSC_YN from " + q + "TB_SCHM where PRJ_SN = 0",
                    (rs, i) -> new DbIdentifierCheck.Schema(rs.getLong(1), rs.getString(2), rs.getBoolean(3)));
            var tables = example.query("select TBL_SN, SCHM_SN, TBL_NM from " + q + "TB_TBL where PRJ_SN = 0",
                    (rs, i) -> new DbIdentifierCheck.Table(rs.getLong(1), rs.getLong(2), rs.getString(3)));
            var references = new java.util.HashSet<>(example.queryForList("select DRVN_COL_SN from " + q + "TB_TBL_REL where PRJ_SN = 0 and REL_SRC_CD = 'REF_COLUMN'"
                    + " and REL_TYPE_CD = 'FK' and DRVN_COL_SN is not null", Long.class));
            var columns = example.query("select COL_SN, TBL_SN, COL_NM, PK_YN, UNQ_YN from " + q + "TB_COL where PRJ_SN = 0 and TBL_SN is not null",
                    (rs, i) -> new DbIdentifierCheck.Column(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), references.contains(rs.getLong(1))));
            List<DbIdentifierCheck.StoredName> stored = new java.util.ArrayList<>();
            stored.addAll(example.query("select CNSTRT_SN, TBL_SN, CNSTRT_NM, CNSTRT_TYPE_CD from " + q + "TB_TBL_CNSTRT where PRJ_SN = 0",
                    (rs, i) -> new DbIdentifierCheck.StoredName("CHECK".equals(rs.getString(4)) ? Kind.CHECK : Kind.UNIQUE, rs.getLong(1), rs.getLong(2), rs.getString(3))));
            stored.addAll(example.query("select IDX_SN, TBL_SN, IDX_NM from " + q + "TB_TBL_IDX where PRJ_SN = 0",
                    (rs, i) -> new DbIdentifierCheck.StoredName(Kind.INDEX, rs.getLong(1), rs.getLong(2), rs.getString(3))));
            stored.addAll(example.query("select REL_SN, ORGNL_TBL_SN, REL_NM from " + q + "TB_TBL_REL where PRJ_SN = 0 and REL_SRC_CD <> 'REF_COLUMN' and REL_TYPE_CD = 'FK'",
                    (rs, i) -> new DbIdentifierCheck.StoredName(Kind.FOREIGN_KEY, rs.getLong(1), rs.getLong(2), rs.getString(3))));
            assertThat(tables).hasSizeGreaterThan(50);
            var model = new DbIdentifierCheck.Model(schemas, tables, columns, stored);
            assertThat(DbIdentifierCheck.check(DbType.POSTGRESQL, model)).isEmpty();
            assertThat(DbIdentifierCheck.check(DbType.MYSQL, model)).isEmpty();
            assertThat(DbIdentifierCheck.check(DbType.MARIADB, model)).isEmpty();
            // Oracle 30자 기준이면 참조 테이블명 FK 의 자동 이름(FK_테이블_컬럼) 7개가 잘린다 — 잘린 이름끼리 겹치지는 않는다
            assertThat(DbIdentifierCheck.check(DbType.ORACLE, model)).hasSize(7)
                    .allSatisfy(f -> assertThat(f.issue().code()).isEqualTo(com.lexorma.data.table.ddl.DbIdentifierRules.Code.CONSTRAINT_NAME_TRUNCATED))
                    .allSatisfy(f -> assertThat(f.issue().kind()).isEqualTo(Kind.FOREIGN_KEY));
        } finally {
            flyway.clean();
        }
    }

    // ------------------------------------------------------------------ 보조

    private Account admin() {
        return accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
    }

    private AccountDto loginAsAdmin(String projectName, DbType dbType) {
        Account admin = admin();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).dbType(dbType).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, SchemaInfo schema) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schema != null ? schema : schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, boolean unique) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnName(name).dataType("VARCHAR").maxLength(10)
                .isPk(pk).isUnique(unique).isApproval(true).sortOrder(1).build()));
    }

    private void index(long projectId, TableInfo table, String name) {
        tx.executeWithoutResult(s -> indexRepository.save(TableIndex.builder().projectId(projectId).tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .indexName(name).isUnique(false).isApproval(true).build()));
    }
}
