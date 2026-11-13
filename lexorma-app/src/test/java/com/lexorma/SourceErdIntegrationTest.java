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
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.controller.SourceCollectionRestController;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec.Namespace;
import com.lexorma.source.collection.domain.dto.SourceErdDto;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 DB ERD (M-4b) : GET /api/source/connections/{id}/snapshot/erd 가 반영 순서 R 의 테이블 버전으로 테이블·VIEW·컬럼·실제 FK 를 한 번에 만드는지.
 * 수집 도구 대신 수집 결과(SourceCollectionSpec)를 직접 넘긴다 (원본 DB 에 접속하지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceErdIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired SourceCollectionService collections;
    @Autowired SourceCollectionRestController api;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("같은 이름의 테이블(public·archive), 복합 FK, 스키마 간 FK, 자기 참조 FK, 같은 테이블을 참조하는 여러 FK, 범위 밖 참조, VIEW 를 실제 FK 만으로 그린다")
    void graph() {
        Env e = env("m4b-graph");
        assertThat(erd(e, null).reflectedOrder()).isNull();
        collect(e, SourceCollectionSpec.Request.full(), result(first()));

        SourceErdDto.Graph g = erd(e, null);
        assertThat(g.reflectedOrder()).isEqualTo(1);
        assertThat(g.dbType()).isEqualTo("POSTGRESQL");
        assertThat(g.schemas()).extracting(SourceErdDto.Schema::schemaName).containsExactly("archive", "public");
        assertThat(g.schemas()).extracting(s -> s.tableCount() + "/" + s.viewCount()).containsExactly("2/0", "2/1");

        SourceErdDto.Table publicUser = table(g, "public", "tb_user");
        SourceErdDto.Table archiveUser = table(g, "archive", "tb_user");
        SourceErdDto.Table orders = table(g, "public", "orders");
        SourceErdDto.Table history = table(g, "archive", "order_hist");
        assertThat(publicUser.tableId()).isNotEqualTo(archiveUser.tableId());
        assertThat(publicUser.columns()).extracting(c -> c.name() + (c.pk() ? ":PK" : "") + (c.fk() ? ":FK" : ""))
                .containsExactly("tenant_id:PK:FK", "id:PK", "manager_id:FK", "email"); // tenant_id 는 자기 참조 FK 의 구성 컬럼이기도 하다
        assertThat(table(g, "public", "v_user").type()).isEqualTo("VIEW");

        // 복합 FK : 컬럼 순서 그대로, 같은 스키마의 tb_user (archive.tb_user 가 아니다)
        SourceErdDto.Relation composite = relation(g, "fk_orders_user");
        assertThat(composite.sourceTableId()).isEqualTo(orders.tableId());
        assertThat(composite.targetTableId()).isEqualTo(publicUser.tableId());
        assertThat(composite.columns()).extracting(p -> p.column() + ">" + p.referencedColumn()).containsExactly("tenant_id>tenant_id", "user_id>id");
        assertThat(composite.deleteRule()).isEqualTo("CASCADE");
        // 같은 테이블을 참조하는 두 번째 FK
        assertThat(relation(g, "fk_orders_approver").targetTableId()).isEqualTo(publicUser.tableId());
        // 자기 참조
        SourceErdDto.Relation self = relation(g, "fk_user_manager");
        assertThat(self.sourceTableId()).isEqualTo(self.targetTableId()).isEqualTo(publicUser.tableId());
        // 스키마 간 FK : archive.order_hist → public.orders, archive.order_hist → archive.tb_user
        assertThat(relation(g, "fk_hist_orders").sourceTableId()).isEqualTo(history.tableId());
        assertThat(relation(g, "fk_hist_orders").targetTableId()).isEqualTo(orders.tableId());
        assertThat(relation(g, "fk_hist_user").targetTableId()).isEqualTo(archiveUser.tableId());
        // 수집 범위 밖(billing 스키마) : 이름만 남고 대상 ID 는 없다
        SourceErdDto.Relation outside = relation(g, "fk_hist_invoice");
        assertThat(outside.targetTableId()).isNull();
        assertThat(outside.targetSchemaName() + "." + outside.targetTableName()).isEqualTo("billing.invoice");
        // 추정 관계 없음 : 원본에 정의된 FK 수 그대로
        assertThat(g.relations()).hasSize(6);
        assertThat(g.relations()).extracting(SourceErdDto.Relation::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("수집 시점 : 과거 반영 순서는 그때의 테이블·컬럼·FK 만, 실패·취소한 수집은 시점이 아니며, PARTIAL 뒤에도 범위 밖 객체는 앞 버전으로 전체가 유효하다")
    void versions() throws Exception {
        Env e = env("m4b-versions");
        collect(e, SourceCollectionSpec.Request.full(), result(first()));

        // 2 : orders 의 approver FK 를 지우고 컬럼을 더하고, archive.order_hist 를 지운다
        Map<String, List<SourceCollectionSpec.Table>> second = first();
        second.put("public", List.of(user(), orders(true, false), view()));
        second.put("archive", List.of(archiveUser()));
        collect(e, SourceCollectionSpec.Request.full(), result(second));

        // 실패·취소 : 반영하지 않는다 (마지막 반영은 그대로 2)
        SourceCollectionDto started = new SourceCollectionDto(collections.start(e.context(), e.connection, SourceCollectionSpec.Request.full()).jobId());
        collections.fail(e.context(), started.jobId, "SOURCE_CONNECT_FAILED");
        SourceCollectionDto cancelled = new SourceCollectionDto(collections.start(e.context(), e.connection, SourceCollectionSpec.Request.full()).jobId());
        collections.cancel(e.context(), cancelled.jobId);
        assertThat(erd(e, null).reflectedOrder()).isEqualTo(2);
        rejected(() -> erd(e, 3), ErrorCode.SOURCE_COLLECTION_NOT_FOUND);

        // 3 : PARTIAL(archive) 로 archive.tb_user 에 컬럼 추가. public 은 2 의 버전 그대로
        Map<String, List<SourceCollectionSpec.Table>> third = new LinkedHashMap<>();
        third.put("archive", List.of(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("tb_user"), SourceTableType.TABLE, null),
                List.of(column("tenant_id", 1, false), column("id", 2, false), column("moved_at", 3, true)), List.of(pk("archive_user_pkey", "tenant_id", "id")), List.of(), List.of())));
        collect(e, SourceCollectionSpec.Request.partial(new Namespace(null, Name.of("archive"))), result(third));

        SourceErdDto.Graph one = erd(e, 1);
        SourceErdDto.Graph two = erd(e, 2);
        SourceErdDto.Graph three = erd(e, null);
        assertThat(three.reflectedOrder()).isEqualTo(3);
        // 과거(1)에는 order_hist 와 approver FK 가 있고, 나중 컬럼(note)이 없다
        assertThat(names(one)).contains("archive.order_hist");
        assertThat(one.relations()).extracting(SourceErdDto.Relation::name).contains("fk_orders_approver", "fk_hist_orders");
        assertThat(table(one, "public", "orders").columns()).extracting(SourceErdDto.Column::name).doesNotContain("note");
        // 2 : 지운 테이블·FK 는 없고, 추가한 컬럼이 있다
        assertThat(names(two)).doesNotContain("archive.order_hist");
        assertThat(two.relations()).extracting(SourceErdDto.Relation::name).containsExactlyInAnyOrder("fk_orders_user", "fk_user_manager");
        assertThat(table(two, "public", "orders").columns()).extracting(SourceErdDto.Column::name).contains("note");
        assertThat(table(two, "archive", "tb_user").columns()).extracting(SourceErdDto.Column::name).doesNotContain("moved_at");
        // 3 (PARTIAL) : public 은 2 와 같고 archive 만 바뀌었다
        assertThat(names(three)).isEqualTo(names(two));
        assertThat(three.relations()).extracting(SourceErdDto.Relation::name).containsExactlyInAnyOrder("fk_orders_user", "fk_user_manager");
        assertThat(table(three, "archive", "tb_user").columns()).extracting(SourceErdDto.Column::name).contains("moved_at");
        assertThat(table(three, "public", "tb_user").tableId()).isEqualTo(table(one, "public", "tb_user").tableId());
    }

    @Test
    @DisplayName("권한 : VIEWER 는 ERD 를 볼 수 있고, 다른 프로젝트의 연결은 ID 를 알아도 볼 수 없다. 로그인하지 않으면 거절. 비밀정보는 응답에 없다")
    void access() throws Exception {
        Env e = env("m4b-access");
        collect(e, SourceCollectionSpec.Request.full(), result(first()));
        SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.viewer.getId()), "ROLE_VIEWER");
        SourceErdDto.Graph viewed = api.erd(e.viewerDto, e.connection, null).getBody();
        assertThat(viewed.tables()).hasSize(5);
        String json = JSON.writeValueAsString(viewed);
        assertThat(json).doesNotContain("password").doesNotContain("jdbc:").doesNotContain("db.internal");

        Env other = env("m4b-other");
        SecurityTestSupport.loginAs(projectRepository, other.project.getId(), String.valueOf(other.managerAccount.getId()), "ROLE_DBA");
        rejected(() -> api.erd(other.managerDto, e.connection, null), ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
        SecurityTestSupport.clear();
        assertThatThrownBy(() -> api.erd(e.viewerDto, e.connection, null)).isInstanceOf(Exception.class)
                .satisfies(ex -> assertThat(ex).isInstanceOfAny(AccessDeniedException.class, org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
    @DisplayName("[성능] 3,000 테이블 (스키마 10개, 테이블마다 컬럼 12개·FK 1~2개) 의 ERD 조회 시간과 응답 크기")
    void large() throws Exception {
        Env e = env("m4b-large");
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        for (int s = 0; s < 10; s++) {
            List<SourceCollectionSpec.Table> tables = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                List<SourceCatalogSpec.Column> columns = new ArrayList<>();
                for (int c = 1; c <= 12; c++) columns.add(column(c == 1 ? "id" : c == 2 ? "parent_id" : "col_" + c, c, c != 2));
                List<SourceCatalogSpec.Relation> fks = new ArrayList<>();
                if (i > 0) fks.add(new SourceCatalogSpec.Relation("fk_" + s + "_" + i, null, null, Name.of("t_" + (i - 1)), "NO ACTION", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("parent_id"), Name.of("id")))));
                if (s > 0 && i % 10 == 0) fks.add(new SourceCatalogSpec.Relation("fk_x_" + s + "_" + i, null, Name.of("s0"), Name.of("t_" + i), "NO ACTION", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("col_3"), Name.of("id")))));
                tables.add(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("t_" + i), SourceTableType.TABLE, "테이블 " + i), columns,
                        List.of(pk("t_" + i + "_pkey", "id")), List.of(), fks));
            }
            schemas.put("s" + s, tables);
        }
        long collectStart = System.nanoTime();
        collect(e, SourceCollectionSpec.Request.full(), result(schemas));
        long collected = System.nanoTime();
        erd(e, null); // 첫 조회 (JIT 준비)
        long start = System.nanoTime();
        SourceErdDto.Graph graph = erd(e, null);
        long elapsed = System.nanoTime() - start;
        int bytes = JSON.writeValueAsBytes(graph).length;
        System.out.printf("[M-4b perf] collect %d ms, erd %d ms, tables %d, relations %d, json %d bytes%n",
                (collected - collectStart) / 1_000_000, elapsed / 1_000_000, graph.tables().size(), graph.relations().size(), bytes);
        assertThat(graph.tables()).hasSize(3000);
        assertThat(graph.relations()).hasSize(10 * 299 + 9 * 30);
        assertThat(graph.relations()).allSatisfy(r -> assertThat(r.targetTableId()).isNotNull());
    }

    // ------------------------------------------------------------------ 예제

    /** public : tb_user(자기 참조), orders(복합 FK + 두 번째 FK 가 tb_user), v_user(VIEW). archive : tb_user(같은 이름), order_hist(스키마 간 FK 2개 + 범위 밖 FK) */
    private static Map<String, List<SourceCollectionSpec.Table>> first() {
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        schemas.put("public", List.of(user(), orders(true, true), view()));
        schemas.put("archive", List.of(archiveUser(), new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("order_hist"), SourceTableType.TABLE, "주문 이력"),
                List.of(column("tenant_id", 1, false), column("order_id", 2, false), column("user_id", 3, false), column("invoice_id", 4, true)),
                List.of(pk("order_hist_pkey", "tenant_id", "order_id")), List.of(),
                List.of(new SourceCatalogSpec.Relation("fk_hist_orders", null, Name.of("public"), Name.of("orders"), "NO ACTION", "NO ACTION",
                                List.of(pair("tenant_id", "tenant_id"), pair("order_id", "id"))),
                        new SourceCatalogSpec.Relation("fk_hist_user", null, null, Name.of("tb_user"), "NO ACTION", "NO ACTION",
                                List.of(pair("tenant_id", "tenant_id"), pair("user_id", "id"))),
                        new SourceCatalogSpec.Relation("fk_hist_invoice", null, Name.of("billing"), Name.of("invoice"), "NO ACTION", "NO ACTION",
                                List.of(pair("invoice_id", "id")))))));
        return schemas;
    }

    private static SourceCollectionSpec.Table user() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("tb_user"), SourceTableType.TABLE, "사용자"),
                List.of(column("tenant_id", 1, false), column("id", 2, false), column("manager_id", 3, true), column("email", 4, true)),
                List.of(pk("tb_user_pkey", "tenant_id", "id")), List.of(),
                List.of(new SourceCatalogSpec.Relation("fk_user_manager", null, null, Name.of("tb_user"), "SET NULL", "NO ACTION",
                        List.of(pair("tenant_id", "tenant_id"), pair("manager_id", "id")))));
    }

    private static SourceCollectionSpec.Table archiveUser() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("tb_user"), SourceTableType.TABLE, "보관 사용자"),
                List.of(column("tenant_id", 1, false), column("id", 2, false)), List.of(pk("archive_user_pkey", "tenant_id", "id")), List.of(), List.of());
    }

    /** approver : 같은 tb_user 를 참조하는 두 번째 FK. note : 두 번째 수집에서 더하는 컬럼 */
    private static SourceCollectionSpec.Table orders(boolean note, boolean approver) {
        List<SourceCatalogSpec.Column> columns = new ArrayList<>(List.of(column("tenant_id", 1, false), column("id", 2, false), column("user_id", 3, false),
                column("approver_id", 4, true)));
        if (note && !approver) columns.add(column("note", 5, true));
        List<SourceCatalogSpec.Relation> fks = new ArrayList<>(List.of(new SourceCatalogSpec.Relation("fk_orders_user", null, null, Name.of("tb_user"), "CASCADE", "NO ACTION",
                List.of(pair("tenant_id", "tenant_id"), pair("user_id", "id")))));
        if (approver) fks.add(new SourceCatalogSpec.Relation("fk_orders_approver", null, null, Name.of("tb_user"), "NO ACTION", "NO ACTION",
                List.of(pair("tenant_id", "tenant_id"), pair("approver_id", "id"))));
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null), columns, List.of(pk("orders_pkey", "tenant_id", "id")),
                List.of(), fks);
    }

    private static SourceCollectionSpec.Table view() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("v_user"), SourceTableType.VIEW, "사용자 뷰"),
                List.of(column("id", 1, true), column("email", 2, true)), List.of(), List.of(), List.of());
    }

    private static SourceCatalogSpec.ColumnPair pair(String column, String referenced) {
        return new SourceCatalogSpec.ColumnPair(Name.of(column), Name.of(referenced));
    }

    private static SourceCatalogSpec.Column column(String name, int ordinal, boolean nullable) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, "bigint", null, null, null, nullable, null, null);
    }

    private static SourceCatalogSpec.Constraint pk(String name, String... columns) {
        return new SourceCatalogSpec.Constraint(name, SourceConstraintType.PRIMARY_KEY, null, java.util.Arrays.stream(columns).map(Name::of).toList());
    }

    private static SourceCollectionSpec.Result result(Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        return new SourceCollectionSpec.Result(result);
    }

    // ------------------------------------------------------------------ 준비 · 확인

    private record SourceCollectionDto(String jobId) {
    }

    private record Env(Project project, Long connection, Account managerAccount, Account viewer, AccountDto managerDto, AccountDto viewerDto) {
        ExecutionContext context() {
            return ExecutionContext.user(managerDto);
        }
    }

    private Env env(String name) {
        Account manager = account("mgr", "ROLE_DBA");
        Account viewer = account("vwr", "ROLE_VIEWER");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(manager)
                .dbType(DbType.POSTGRESQL).build());
        for (Account account : List.of(manager, viewer)) projectMemberRepository.save(ProjectMember.builder().project(project).account(account).isActive(true).build());
        AccountDto managerDto = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA");
        Long connection = connectionApi.create(managerDto, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null, null,
                null)).getBody().id();
        AccountDto viewerDto = AccountDto.builder().id(String.valueOf(viewer.getId())).username(viewer.getUsername()).projectId(project.getId()).build();
        return new Env(project, connection, manager, viewer, managerDto, viewerDto);
    }

    private void collect(Env e, SourceCollectionSpec.Request request, SourceCollectionSpec.Result result) {
        collections.apply(e.context(), collections.start(e.context(), e.connection, request).jobId(), result);
    }

    private SourceErdDto.Graph erd(Env e, Integer order) {
        SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.managerAccount.getId()), "ROLE_DBA");
        return api.erd(e.managerDto, e.connection, order).getBody();
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private static SourceErdDto.Table table(SourceErdDto.Graph graph, String schema, String name) {
        Long schemaId = graph.schemas().stream().filter(s -> s.schemaName().equals(schema)).findFirst().orElseThrow().schemaId();
        return graph.tables().stream().filter(t -> t.schemaId().equals(schemaId) && t.name().equals(name)).findFirst().orElseThrow();
    }

    private static SourceErdDto.Relation relation(SourceErdDto.Graph graph, String name) {
        return graph.relations().stream().filter(r -> name.equals(r.name())).findFirst().orElseThrow();
    }

    private static List<String> names(SourceErdDto.Graph graph) {
        Map<Long, String> schemas = new LinkedHashMap<>();
        graph.schemas().forEach(s -> schemas.put(s.schemaId(), s.schemaName()));
        return graph.tables().stream().map(t -> schemas.get(t.schemaId()) + "." + t.name()).sorted().toList();
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }
}
