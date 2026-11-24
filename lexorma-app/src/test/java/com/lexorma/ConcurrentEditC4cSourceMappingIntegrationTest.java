package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.controller.SourceMappingRestController;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto.Status;
import com.lexorma.data.sourcemapping.service.SourceCompareService;
import com.lexorma.data.sourcemapping.service.SourceImportService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec.Namespace;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.connection.service.SourceConnectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * M8-C4c-1 C4c-D03 (docs/ongoing/modeling-concurrency-m8.md 20장) : 원본 매핑 해제는 화면이 본 매핑 행의 수정 일시(modifiedAt)와 같을 때만 지운다 — 매핑 변경은 같은 행을
 * 고쳐 쓰므로 ID 만으로는 오래된 화면이 바뀐 최신 매핑을 지웠다. 스키마 해제는 함께 해제할 TABLE 매핑 구성(composition)도 본다. 매핑 해제와 매핑 예외는 계속 별개다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4cSourceMappingIntegrationTest {
    @Autowired SourceMappingService service;
    @Autowired SourceMappingRestController api;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private static String code(Runnable call) {
        return ConcurrentEditC4cSupport.outcome(call);
    }

    private Env ready(String name) {
        Env e = env(name);
        service.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, SourceCollectionSpec.Request.full(), catalog(true, true));
        mapAll(e);
        return e;
    }

    private int mappingRows(Long id) {
        return jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = ?", Integer.class, id);
    }

    @Test
    @DisplayName("TABLE 매핑을 본 뒤 다른 사용자가 다른 모델 테이블로 바꾸면 예전 화면의 해제는 409 · 최신 매핑 유지 → 다시 읽은 행이면 해제")
    void staleTableUnmap() {
        Env e = ready("c4c-s1");
        SourceMappingDto.TableRow seen = tables(e).get("public.tb_user");
        assertThat(seen.modifiedAt()).isNotNull();
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(seen.sourceTableId(), e.table("DEFAULT", "TB_SPARE").getId()));
        assertThat(code(() -> service.unmapTable(e.manager, seen.mappingId(), seen.modifiedAt()))).isEqualTo("MODEL_CONFLICT");
        SourceMappingDto.TableRow now = tables(e).get("public.tb_user");
        assertThat(now.mappingId()).isEqualTo(seen.mappingId());
        assertThat(now.tableName()).isEqualTo("TB_SPARE");
        service.unmapTable(e.manager, now.mappingId(), now.modifiedAt());
        assertThat(tables(e).get("public.tb_user").status()).isEqualTo(Status.UNMAPPED);
    }

    @Test
    @DisplayName("지우고 다시 만든 매핑은 새 ID — 예전 요청은 '찾을 수 없음' · 매핑 예외 행은 해제 대상이 아니다 (예외는 그대로)")
    void recreatedAndExclusion() {
        Env e = ready("c4c-s2");
        SourceMappingDto.TableRow seen = tables(e).get("public.tb_user");
        service.unmapTable(e.manager, seen.mappingId(), seen.modifiedAt());
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(seen.sourceTableId(), e.table("DEFAULT", "TB_USER").getId()));
        SourceMappingDto.TableRow again = tables(e).get("public.tb_user");
        assertThat(again.mappingId()).isNotEqualTo(seen.mappingId());
        assertThat(code(() -> service.unmapTable(e.manager, seen.mappingId(), seen.modifiedAt()))).isEqualTo("SOURCE_MAPPING_INVALID");
        assertThat(mappingRows(again.mappingId())).isEqualTo(1);

        SourceMappingDto.TableRow orders = tables(e).get("public.orders");
        service.unmapTable(e.manager, orders.mappingId(), orders.modifiedAt());
        SourceMappingDto.TableRow excluded = service.excludeTable(e.manager, new SourceMappingDto.ExclusionRequest(orders.sourceTableId()));
        assertThat(code(() -> service.unmapTable(e.manager, excluded.mappingId(), excluded.modifiedAt()))).isEqualTo("SOURCE_MAPPING_INVALID");
        assertThat(tables(e).get("public.orders").status()).isEqualTo(Status.EXCLUDED);
    }

    @Test
    @DisplayName("스키마 해제 화면을 연 뒤 같은 스키마에 TABLE 매핑이 더해지면 409 (본 적 없는 매핑을 함께 지우지 않는다) · 매핑 예외만 바뀐 것은 구성에 들지 않는다")
    void staleSchemaUnmap() {
        Env e = ready("c4c-s3");
        SourceMappingDto.TableRow orders = tables(e).get("public.orders");
        service.unmapTable(e.manager, orders.mappingId(), orders.modifiedAt());
        SourceMappingDto.SchemaRow seen = schemaRow(service.overview(e.manager), "public");
        assertThat(seen.modifiedAt()).isNotNull();
        assertThat(seen.composition()).startsWith("v1:");
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(orders.sourceTableId(), e.table("DEFAULT", "TB_ORDER_X").getId()));
        assertThat(code(() -> service.unmapSchema(e.manager, seen.sourceSchemaId(), seen.modifiedAt(), seen.composition()))).isEqualTo("MODEL_CONFLICT");
        assertThat(tables(e).get("public.orders").mappingId()).isNotNull();
        assertThat(schemaRow(service.overview(e.manager), "public").mappingId()).isNotNull();

        // 매핑 예외 추가는 해제 구성(예외 제외 TABLE 매핑)을 바꾸지 않는다
        SourceMappingDto.TableRow ordersNow = tables(e).get("public.orders");
        service.unmapTable(e.manager, ordersNow.mappingId(), ordersNow.modifiedAt());
        SourceMappingDto.SchemaRow seen2 = schemaRow(service.overview(e.manager), "public");
        service.excludeTable(e.manager, new SourceMappingDto.ExclusionRequest(ordersNow.sourceTableId()));
        assertThat(schemaRow(service.overview(e.manager), "public").composition()).isEqualTo(seen2.composition());
        service.unmapSchema(e.manager, seen2.sourceSchemaId(), seen2.modifiedAt(), seen2.composition());
        assertThat(schemaRow(service.overview(e.manager), "public").mappingId()).isNull();
    }

    @Test
    @DisplayName("HTTP : TABLE·스키마 해제는 modifiedAt 필수, 스키마는 composition 도 (400 MODEL_BASE_REQUIRED, 아무것도 지우지 않음) · 다른 프로젝트 매핑은 찾을 수 없음")
    void httpContractAndIsolation() {
        Env e = ready("c4c-s4");
        SourceMappingDto.TableRow seen = tables(e).get("public.tb_user");
        assertThatThrownBy(() -> api.unmapTable(e.manager, seen.mappingId(), null)).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        SourceMappingDto.SchemaRow schema = schemaRow(service.overview(e.manager), "public");
        assertThatThrownBy(() -> api.unmapSchema(e.manager, schema.sourceSchemaId(), schema.modifiedAt(), null)).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        assertThat(mappingRows(seen.mappingId())).isEqualTo(1);

        Env other = ready("c4c-s4o");
        assertThat(code(() -> service.unmapTable(other.manager, seen.mappingId(), seen.modifiedAt()))).isEqualTo("SOURCE_MAPPING_INVALID");
        assertThat(mappingRows(seen.mappingId())).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 : A 가 TABLE 매핑을 바꾸는 동안 B 가 예전 화면으로 해제 → B 는 기준 연결 잠금을 기다렸다가 409, A 의 매핑 유지")
    void unmapWhileRemapping() throws Exception {
        Env e = ready("c4c-s5");
        SourceMappingDto.TableRow seen = tables(e).get("public.tb_user");
        String[] r = new ConcurrentEditC4cSupport(tx, jdbc, dataSource, projectRepository).both(e.manager, "ROLE_DBA",
                () -> service.mapTable(e.manager, new SourceMappingDto.TableRequest(seen.sourceTableId(), e.table("DEFAULT", "TB_SPARE").getId())),
                () -> service.unmapTable(e.manager, seen.mappingId(), seen.modifiedAt()));
        ConcurrentEditC4cSupport.report("TABLE 매핑 변경 vs 해제", r);
        assertThat(r).containsExactly("OK", "MODEL_CONFLICT");
        assertThat(tables(e).get("public.tb_user").tableName()).isEqualTo("TB_SPARE");
    }
    private static SourceCollectionSpec.Table commented(String name, String comment) {
        SourceCollectionSpec.Table plain = table(name, SourceTableType.TABLE);
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), SourceTableType.TABLE, comment), plain.columns(), plain.constraints(),
                plain.indexes(), plain.relations());
    }

    private static Map<String, List<SourceCollectionSpec.Table>> catalog(boolean withPublicUser, boolean withArchive) {
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        List<SourceCollectionSpec.Table> publicTables = new ArrayList<>();
        if (withPublicUser) publicTables.add(table("tb_user", SourceTableType.TABLE));
        publicTables.add(table("orders", SourceTableType.TABLE));
        publicTables.add(table("v_user", SourceTableType.VIEW));
        schemas.put("public", publicTables);
        if (withArchive) schemas.put("archive", List.of(table("tb_user", SourceTableType.TABLE)));
        schemas.put("audit", List.of(table("log", SourceTableType.TABLE)));
        return schemas;
    }

    private static SourceCollectionSpec.Table table(String name, SourceTableType type, String... extraColumns) {
        List<SourceCatalogSpec.Column> columns = new ArrayList<>(List.of(new SourceCatalogSpec.Column(Name.of("id"), 1, "bigint", null, null, null, false, null, null)));
        for (String column : extraColumns) columns.add(new SourceCatalogSpec.Column(Name.of(column), columns.size() + 1, "text", null, null, null, true, null, null));
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), type, null),
                columns,
                type == SourceTableType.TABLE ? List.of(new SourceCatalogSpec.Constraint(name + "_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))) : List.of(),
                List.of(), List.of());
    }

    // ------------------------------------------------------------------ 준비 · 확인

    private final class Env {
        final Project project;
        final Account owner;
        final AccountDto manager;
        final Long connection;
        final SchemaInfo defaultSchema;
        final SchemaInfo archiveSchema;
        final Map<String, TableInfo> tables = new LinkedHashMap<>();

        Env(Project project, Account owner, AccountDto manager, Long connection, SchemaInfo defaultSchema, SchemaInfo archiveSchema) {
            this.project = project;
            this.owner = owner;
            this.manager = manager;
            this.connection = connection;
            this.defaultSchema = defaultSchema;
            this.archiveSchema = archiveSchema;
        }

        TableInfo table(String schema, String name) {
            return tables.get(schema + "." + name);
        }

        Long sourceTableId(String schema, String name) {
            return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = ? "
                    + "and t.TBL_NM = ?", Long.class, connection, schema, name);
        }
    }

    /** 프로젝트(관리자 = 소유자, DBA 역할) + 모델 스키마 DEFAULT·ARCHIVE + 모델 테이블 (DEFAULT : TB_USER·TB_ORDER_X·TB_SPARE, ARCHIVE : TB_USER) + PostgreSQL 연결 */
    private Env env(String name) {
        Account owner = account("own", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo defaultSchema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        SchemaInfo archive = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("ARCHIVE").isDefault(false).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        Env env = new Env(project, owner, manager, connection, defaultSchema, archive);
        for (String[] t : new String[][]{{"DEFAULT", "TB_USER"}, {"DEFAULT", "TB_ORDER_X"}, {"DEFAULT", "TB_SPARE"}, {"ARCHIVE", "TB_USER"}}) {
            SchemaInfo schema = t[0].equals("DEFAULT") ? defaultSchema : archive;
            env.tables.put(t[0] + "." + t[1], tx.execute(s -> tableRepository.save(TableInfo.builder().projectId(project.getId()).tableName(t[1]).tableKorName(t[1])
                    .tableDescription(t[1]).isMasterTable(false).sortOrder(1).isApproval(false).schema(schema).build())));
        }
        return env;
    }

    private void mapAll(Env e) {
        SourceMappingDto.Overview overview = service.overview(e.manager);
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(overview, "public").sourceSchemaId(), e.defaultSchema.getId()));
        service.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(schemaRow(overview, "archive").sourceSchemaId(), e.archiveSchema.getId()));
        Map<String, SourceMappingDto.TableRow> rows = tables(e);
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.tb_user").sourceTableId(), e.table("DEFAULT", "TB_USER").getId()));
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("public.orders").sourceTableId(), e.table("DEFAULT", "TB_ORDER_X").getId()));
        service.mapTable(e.manager, new SourceMappingDto.TableRequest(rows.get("archive.tb_user").sourceTableId(), e.table("ARCHIVE", "TB_USER").getId()));
    }

    private void collect(Env e, SourceCollectionSpec.Request request, Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        ExecutionContext context = ExecutionContext.user(e.manager);
        collections.apply(context, collections.start(context, e.connection, request).jobId(), new SourceCollectionSpec.Result(result));
    }

    private Map<String, SourceMappingDto.TableRow> tables(Env e) {
        Map<String, SourceMappingDto.TableRow> rows = new LinkedHashMap<>();
        service.tables(e.manager, null, null, null, 0, 500).rows().forEach(r -> rows.put(r.sourceSchemaName() + "." + r.sourceTableName(), r));
        return rows;
    }

    private static SourceMappingDto.SchemaRow schemaRow(SourceMappingDto.Overview overview, String name) {
        return overview.schemas().stream().filter(r -> name.equals(r.sourceSchemaName())).findFirst().orElseThrow();
    }

    private int count(String table, Env e) {
        return jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, e.project.getId());
    }

    /** 물리 모델의 지문 : 스키마·테이블·컬럼의 이름·소속·승인 상태와 모델 변경 이력 수 */
    private String modelFingerprint(Long projectId) {
        return String.join("|", jdbc.queryForList("select SCHM_SN || ':' || SCHM_NM from TB_SCHM where PRJ_SN = ? order by SCHM_SN", String.class, projectId))
                + "#" + String.join("|", jdbc.queryForList("select TBL_SN || ':' || SCHM_SN || ':' || TBL_NM || ':' || APRV_YN from TB_TBL where PRJ_SN = ? order by TBL_SN",
                String.class, projectId))
                + "#" + jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Integer.class, projectId)
                + "#" + jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, projectId);
    }

    private AccountDto login(Env e, Account account, String role) {
        return SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(account.getId()), role);
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

}
