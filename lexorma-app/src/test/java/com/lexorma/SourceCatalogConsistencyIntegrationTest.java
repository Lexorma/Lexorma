package com.lexorma;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.service.SourceCatalogWriter;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.collection.service.SourceSnapshotService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-1c 정합성 : 현재 카탈로그(9개 테이블)가 수집 반영 밖의 경로(AI-1b 의 한 건 단위 SourceCatalogWriter)로 바뀐 뒤에도
 * <b>원본 변경은 마지막 확정 버전과 수집 결과로</b> 판정하고(버전·이력), <b>현재 카탈로그는 수집 결과로 복구</b>한다(버전·이력 없음).
 * 시나리오 A(같은 값 수집), B(원래 값 수집), C(다른 값 수집), D(삭제·재등장)를 컬럼 타입·COMMENT·순서·제약조건·인덱스·FK·스키마에 대해 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceCatalogConsistencyIntegrationTest {

    @Autowired SourceCollectionService collections;
    @Autowired SourceSnapshotService snapshots;
    @Autowired SourceCatalogWriter writer;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("A : Writer 로 바꾼 값과 같은 값이 수집되면 (현재 카탈로그와 같아도) 마지막 버전과 다르므로 v2 와 v1 → v2 이력이 생긴다")
    void writerThenSameValue() {
        Env e = env("cons-a");
        collect(e, source("character varying(100)", 100));
        Long users = table(e, "users");
        writer.saveColumn(e.project.getId(), users, column("character varying(200)", 200));
        assertThat(jdbc.queryForObject("select HASH_VL from TB_SRC_TBL where SRC_TBL_SN = ?", String.class, users)).isNull();

        SourceCollectionDto.Execution second = collect(e, source("character varying(200)", 200));
        assertThat(second.versionCount()).isEqualTo(1);
        assertThat(versions(users)).containsExactly(Tuple.tuple(1, "BASE"), Tuple.tuple(2, "MODIFIED"));
        assertThat(changes(e, second)).containsExactly("COLUMN:email:MODIFIED:[dataType, maxLength]");
        Map<?, ?> change = changeOf(e, second);
        assertThat(((Map<?, ?>) change.get("before")).get("dataType")).isEqualTo("character varying(100)");
        assertThat(((Map<?, ?>) change.get("after")).get("dataType")).isEqualTo("character varying(200)");
        assertThat(emailAt(e, users, 1)).isEqualTo("character varying(100)");
        assertThat(emailAt(e, users, 2)).isEqualTo("character varying(200)");
        assertThat(currentEmail(users)).isEqualTo("character varying(200)");
        assertConsistent(e);
    }

    @Test
    @DisplayName("B : Writer 로 바꾼 뒤 원래 값이 수집되면 현재 카탈로그만 원래대로 복구하고 버전·이력은 만들지 않는다")
    void writerThenOriginalValue() {
        Env e = env("cons-b");
        collect(e, source("character varying(100)", 100));
        Long users = table(e, "users");
        writer.saveColumn(e.project.getId(), users, column("character varying(200)", 200));

        SourceCollectionDto.Execution second = collect(e, source("character varying(100)", 100));
        assertThat(second).extracting(SourceCollectionDto.Execution::versionCount, SourceCollectionDto.Execution::changeCount).containsExactly(0, 0);
        assertThat(versions(users)).containsExactly(Tuple.tuple(1, "BASE"));
        assertThat(currentEmail(users)).isEqualTo("character varying(100)");
        assertThat(jdbc.queryForObject("select MAX_LEN from TB_SRC_COL where SRC_TBL_SN = ? and CMPR_NM = 'email'", Integer.class, users)).isEqualTo(100);
        assertConsistent(e);
    }

    @Test
    @DisplayName("C : Writer 로 바꾼 뒤 다른 값이 수집되면 v2 가 생기고 이력은 v1(원본) → 수집 값이다 (Writer 의 임시 값이 이력에 들어가지 않는다)")
    void writerThenOtherValue() {
        Env e = env("cons-c");
        collect(e, source("character varying(100)", 100));
        Long users = table(e, "users");
        writer.saveColumn(e.project.getId(), users, column("character varying(200)", 200));

        SourceCollectionDto.Execution second = collect(e, source("character varying(300)", 300));
        assertThat(versions(users)).containsExactly(Tuple.tuple(1, "BASE"), Tuple.tuple(2, "MODIFIED"));
        Map<?, ?> change = changeOf(e, second);
        assertThat(((Map<?, ?>) change.get("before")).get("dataType")).isEqualTo("character varying(100)");
        assertThat(((Map<?, ?>) change.get("after")).get("dataType")).isEqualTo("character varying(300)");
        assertThat(currentEmail(users)).isEqualTo("character varying(300)");
        assertConsistent(e);
    }

    @Test
    @DisplayName("A·B (하위 객체) : Writer 로 COMMENT·컬럼 순서·제약조건·복합 인덱스·FK 를 바꾼 뒤 원래 구성이 오면 복구만, Writer 와 같은 구성이 오면 마지막 버전 대비 이력")
    void writerChildren() {
        // B : 원래 구성 → 복구만
        Env b = env("cons-ch-b");
        collect(b, source("character varying(100)", 100));
        Map<String, List<Map<String, Object>>> restoredBefore = rows(b);
        tamper(b);
        SourceCollectionDto.Execution restored = collect(b, source("character varying(100)", 100));
        assertThat(restored).extracting(SourceCollectionDto.Execution::versionCount, SourceCollectionDto.Execution::changeCount).containsExactly(0, 0);
        assertThat(structure(b)).isEqualTo(structure(restoredBefore));
        assertConsistent(b);

        // A : Writer 와 같은 구성이 원본에 생겼다 → 마지막 버전(v1) 대비 객체마다 이력
        Env a = env("cons-ch-a");
        collect(a, source("character varying(100)", 100));
        tamper(a);
        SourceCollectionDto.Execution changed = collect(a, tampered());
        assertThat(changes(a, changed)).containsExactlyInAnyOrder(
                "TABLE:users:MODIFIED:[comment]",
                "COLUMN:email:MODIFIED:[ordinal, comment]",
                "COLUMN:name:MODIFIED:[ordinal]",
                "CONSTRAINT:users_email_key:REMOVED:[]",
                "INDEX:ix_users_tenant_email:MODIFIED:[columns]",
                "RELATION:fk_orders_users:MODIFIED:[deleteRule]");
        assertConsistent(a);
    }

    @Test
    @DisplayName("D : Writer 로 고친 테이블이 FULL 에서 사라지면 삭제 표시는 원본의 마지막 상태로 한 번만, Writer 가 되살린 행이 다시 빠져도 중복되지 않고, 재등장하면 생성 차수가 늘어난다 (스키마도 같다)")
    void deleteAndReappear() {
        Env e = env("cons-d");
        collect(e, source("character varying(100)", 100));
        Long users = table(e, "users");
        Long audit = jdbc.queryForObject("select SRC_SCHM_SN from TB_SRC_SCHM where SRC_CNTN_SN = ? and SCHM_NM = 'audit'", Long.class, e.connection);
        writer.saveTable(e.project.getId(), schema(e, "public"), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "Writer 가 고친 COMMENT"));

        // users·audit 가 원본에서 사라졌다
        SourceCollectionDto.Execution removed = collect(e, withoutUsersAndAudit());
        assertThat(versions(users)).containsExactly(Tuple.tuple(1, "BASE"), Tuple.tuple(2, "REMOVED"));
        // 삭제 표시와 이력의 이전 값은 원본의 마지막 상태(v1) : Writer 의 COMMENT 가 아니다
        String tombstone = jdbc.queryForObject("select TBL_CN from TB_SRC_TBL_VER where SRC_TBL_SN = ? and RFLT_ORD = 2", String.class, users);
        assertThat(tombstone).isEqualTo(jdbc.queryForObject("select TBL_CN from TB_SRC_TBL_VER where SRC_TBL_SN = ? and RFLT_ORD = 1", String.class, users));
        assertThat(tombstone).doesNotContain("Writer 가 고친 COMMENT");
        assertThat(changes(e, removed)).containsExactlyInAnyOrder("TABLE:users:REMOVED:[]", "SCHEMA:app.audit:REMOVED:[]");

        // Writer 가 행을 되살렸다 (원본에는 여전히 없다) → 다시 빠져도 삭제 표시·이력이 두 번 생기지 않는다
        writer.saveTable(e.project.getId(), schema(e, "public"), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "사용자"));
        writer.saveSchema(e.project.getId(), e.connection, Name.of("app"), Name.of("audit"));
        SourceCollectionDto.Execution again = collect(e, withoutUsersAndAudit());
        assertThat(again).extracting(SourceCollectionDto.Execution::versionCount, SourceCollectionDto.Execution::changeCount).containsExactly(0, 0);
        assertThat(jdbc.queryForObject("select EXST_YN from TB_SRC_TBL where SRC_TBL_SN = ?", Boolean.class, users)).isFalse();
        assertThat(jdbc.queryForObject("select EXST_YN from TB_SRC_SCHM where SRC_SCHM_SN = ?", Boolean.class, audit)).isFalse();

        // Writer 가 다시 되살린 행이 있는 채로 원본에 재등장 : 새 생명주기 (생성 차수 2), 이력 ADDED 한 번
        writer.saveTable(e.project.getId(), schema(e, "public"), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "사용자"));
        writer.saveSchema(e.project.getId(), e.connection, Name.of("app"), Name.of("audit"));
        SourceCollectionDto.Execution back = collect(e, source("character varying(100)", 100));
        assertThat(versions(users)).containsExactly(Tuple.tuple(1, "BASE"), Tuple.tuple(2, "REMOVED"), Tuple.tuple(back.reflectedOrder(), "ADDED"));
        assertThat(jdbc.queryForList("select CRT_CYCL from TB_SRC_TBL_VER where SRC_TBL_SN = ? order by RFLT_ORD", Integer.class, users)).containsExactly(1, 1, 2);
        assertThat(jdbc.queryForObject("select CRT_CYCL from TB_SRC_TBL where SRC_TBL_SN = ?", Integer.class, users)).isEqualTo(2);
        assertThat(jdbc.queryForList("select CHG_TYPE_CD || ':' || CRT_CYCL from TB_SRC_SCHM_VER where SRC_SCHM_SN = ? order by RFLT_ORD", String.class, audit))
                .containsExactly("BASE:1", "REMOVED:1", "ADDED:2");
        assertThat(snapshots.tableChanges(e.managerDto, users)).extracting(SourceCollectionDto.Change::changeType, SourceCollectionDto.Change::creationCycle)
                .containsExactly(Tuple.tuple("REMOVED", 1), Tuple.tuple("ADDED", 2));
        // 과거 시점 : v1 은 그대로, 사라졌던 때는 없다
        assertThat(emailAt(e, users, 1)).isEqualTo("character varying(100)");
        assertThat(snapshots.catalogAt(e.managerDto, e.connection, removed.reflectedOrder(), null).schemas()).extracting(SourceCollectionDto.SchemaState::schemaName)
                .containsExactly("public");
        assertConsistent(e);
    }

    // ------------------------------------------------------------------ 수집 결과

    /** public.users(컬럼 4, PK, UNIQUE, 복합 인덱스), public.orders(→ users FK), audit(빈 스키마) */
    private static SourceCollectionSpec.Result source(String emailType, int emailLength) {
        return result(users("사용자", emailType, emailLength, 3, "이메일", 4, true, List.of("tenant_id", "email")), "CASCADE", true);
    }

    /** Writer 로 바꾼 구성 (tamper 와 같다) */
    private static SourceCollectionSpec.Result tampered() {
        return result(users("Writer COMMENT", "character varying(100)", 100, 4, "Writer 이메일", 3, false, List.of("email", "tenant_id")), "SET NULL", true);
    }

    private static SourceCollectionSpec.Result withoutUsersAndAudit() {
        return new SourceCollectionSpec.Result(List.of(SourceCollectionSpec.Schema.completed(Name.of("app"), Name.of("public"), List.of(orders("CASCADE", true)))));
    }

    private static SourceCollectionSpec.Result result(SourceCollectionSpec.Table users, String deleteRule, boolean withAudit) {
        List<SourceCollectionSpec.Schema> schemas = new ArrayList<>();
        schemas.add(SourceCollectionSpec.Schema.completed(Name.of("app"), Name.of("public"), List.of(users, orders(deleteRule, true))));
        if (withAudit) schemas.add(SourceCollectionSpec.Schema.completed(Name.of("app"), Name.of("audit"), List.of()));
        return new SourceCollectionSpec.Result(schemas);
    }

    private static SourceCollectionSpec.Table users(String comment, String emailType, int emailLength, int emailOrdinal, String emailComment, int nameOrdinal, boolean unique,
                                                    List<String> indexColumns) {
        List<SourceCatalogSpec.Constraint> constraints = new ArrayList<>(List.of(new SourceCatalogSpec.Constraint("users_pkey", SourceConstraintType.PRIMARY_KEY, null,
                List.of(Name.of("tenant_id"), Name.of("id")))));
        if (unique) constraints.add(new SourceCatalogSpec.Constraint("users_email_key", SourceConstraintType.UNIQUE, null, List.of(Name.of("email"))));
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, comment),
                List.of(plain("tenant_id", 1), plain("id", 2),
                        new SourceCatalogSpec.Column(Name.of("email"), emailOrdinal, emailType, emailLength, null, null, true, null, emailComment),
                        new SourceCatalogSpec.Column(Name.of("name"), nameOrdinal, "text", null, null, null, true, null, null)),
                constraints, List.of(new SourceCatalogSpec.Index("ix_users_tenant_email", "btree", false, indexColumns.stream().map(Name::of).toList())), List.of());
    }

    private static SourceCollectionSpec.Table orders(String deleteRule, boolean withFk) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null),
                List.of(plain("tenant_id", 1), plain("id", 2), plain("user_id", 3)), List.of(), List.of(),
                withFk ? List.of(new SourceCatalogSpec.Relation("fk_orders_users", null, null, Name.of("users"), deleteRule, null,
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")), new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id")))))
                        : List.of());
    }

    private static SourceCatalogSpec.Column plain(String name, int ordinal) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, "bigint", null, null, null, false, null, null);
    }

    private static SourceCatalogSpec.Column column(String type, int length) {
        return new SourceCatalogSpec.Column(Name.of("email"), 3, type, length, null, null, true, null, "이메일");
    }

    /** Writer 로 현재 카탈로그만 고친다 : 테이블 COMMENT, 컬럼 순서·COMMENT, UNIQUE 제거, 인덱스 구성 순서, FK 삭제 규칙 */
    private void tamper(Env e) {
        long projectId = e.project.getId();
        Long users = table(e, "users");
        Long orders = table(e, "orders");
        writer.saveTable(projectId, schema(e, "public"), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "Writer COMMENT"));
        writer.saveColumn(projectId, users, new SourceCatalogSpec.Column(Name.of("email"), 4, "character varying(100)", 100, null, null, true, null, "Writer 이메일"));
        writer.saveColumn(projectId, users, new SourceCatalogSpec.Column(Name.of("name"), 3, "text", null, null, null, true, null, null));
        writer.replaceConstraints(projectId, users, List.of(new SourceCatalogSpec.Constraint("users_pkey", SourceConstraintType.PRIMARY_KEY, null,
                List.of(Name.of("tenant_id"), Name.of("id")))));
        writer.replaceIndexes(projectId, users, List.of(new SourceCatalogSpec.Index("ix_users_tenant_email", "btree", false, List.of(Name.of("email"), Name.of("tenant_id")))));
        writer.replaceRelations(projectId, orders, List.of(new SourceCatalogSpec.Relation("fk_orders_users", null, null, Name.of("users"), "SET NULL", null,
                List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")), new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))))));
    }

    // ------------------------------------------------------------------ 확인

    /**
     * 현재 카탈로그와 마지막 확정 버전이 같다 : 원본에 있는 테이블마다 현재 해시 = 마지막 버전 해시(삭제 표시가 아닌),
     * 원본에서 사라진 테이블은 마지막 버전이 삭제 표시. 같은 결과를 한 번 더 수집하면 아무것도 바뀌지 않는다 (멱등).
     */
    private void assertConsistent(Env e) {
        List<Map<String, Object>> tables = jdbc.queryForList("select t.SRC_TBL_SN, t.EXST_YN, t.HASH_VL, (select v.HASH_VL from TB_SRC_TBL_VER v where v.SRC_TBL_SN = t.SRC_TBL_SN"
                + " and v.RFLT_ORD = (select max(v2.RFLT_ORD) from TB_SRC_TBL_VER v2 where v2.SRC_TBL_SN = t.SRC_TBL_SN)) as VER_HASH, (select v.CHG_TYPE_CD from TB_SRC_TBL_VER v"
                + " where v.SRC_TBL_SN = t.SRC_TBL_SN and v.RFLT_ORD = (select max(v2.RFLT_ORD) from TB_SRC_TBL_VER v2 where v2.SRC_TBL_SN = t.SRC_TBL_SN)) as VER_TYPE"
                + " from TB_SRC_TBL t where t.SRC_CNTN_SN = ?", e.connection);
        assertThat(tables).isNotEmpty();
        for (Map<String, Object> row : tables) {
            Map<String, Object> r = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            r.putAll(row);
            if (Boolean.TRUE.equals(r.get("EXST_YN"))) {
                assertThat(r.get("VER_TYPE")).as("원본에 있는 테이블의 마지막 버전").isNotEqualTo("REMOVED");
                assertThat(r.get("HASH_VL")).as("현재 해시 = 마지막 버전 해시").isEqualTo(r.get("VER_HASH"));
            } else {
                assertThat(r.get("VER_TYPE")).as("사라진 테이블의 마지막 버전").isEqualTo("REMOVED");
            }
        }
        List<Map<String, Object>> schemas = jdbc.queryForList("select s.EXST_YN, (select v.CHG_TYPE_CD from TB_SRC_SCHM_VER v where v.SRC_SCHM_SN = s.SRC_SCHM_SN and v.RFLT_ORD ="
                + " (select max(v2.RFLT_ORD) from TB_SRC_SCHM_VER v2 where v2.SRC_SCHM_SN = s.SRC_SCHM_SN)) as VER_TYPE from TB_SRC_SCHM s where s.SRC_CNTN_SN = ?", e.connection);
        for (Map<String, Object> row : schemas) {
            Map<String, Object> r = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            r.putAll(row);
            assertThat(Boolean.TRUE.equals(r.get("EXST_YN"))).as("스키마 존재 = 마지막 스키마 버전이 삭제 표시가 아님").isEqualTo(!"REMOVED".equals(r.get("VER_TYPE")));
        }
        // 마지막으로 반영한 결과를 다시 수집하면 버전·이력·현재 카탈로그 변경이 없다
        Map<String, List<Map<String, Object>>> before = rows(e);
        SourceCollectionDto.Execution again = collect(e, last.get(e.connection));
        assertThat(again).extracting(SourceCollectionDto.Execution::versionCount, SourceCollectionDto.Execution::changeCount).containsExactly(0, 0);
        assertThat(rows(e)).isEqualTo(before);
    }

    private final Map<Long, SourceCollectionSpec.Result> last = new LinkedHashMap<>();

    private record Env(Project project, Long connection, AccountDto managerDto) {
        ExecutionContext context() {
            return ExecutionContext.user(managerDto);
        }
    }

    private Env env(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        AccountDto owner = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        Long connection = connectionApi.create(owner, new SourceConnectionDto.Request("정합성", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null, null, null))
                .getBody().id();
        return new Env(project, connection, owner);
    }

    private SourceCollectionDto.Execution collect(Env e, SourceCollectionSpec.Result result) {
        String jobId = collections.start(e.context(), e.connection, SourceCollectionSpec.Request.full()).jobId();
        SourceCollectionDto.Execution done = collections.apply(e.context(), jobId, result);
        assertThat(done.status()).isEqualTo("COMPLETED");
        last.put(e.connection, result);
        return done;
    }

    private Long schema(Env e, String name) {
        return jdbc.queryForObject("select SRC_SCHM_SN from TB_SRC_SCHM where SRC_CNTN_SN = ? and SCHM_NM = ?", Long.class, e.connection, name);
    }

    private Long table(Env e, String name) {
        return jdbc.queryForObject("select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ? and CMPR_NM = ?", Long.class, e.connection, name);
    }

    private List<Tuple> versions(Long table) {
        return jdbc.query("select RFLT_ORD, CHG_TYPE_CD from TB_SRC_TBL_VER where SRC_TBL_SN = ? order by RFLT_ORD", (rs, i) -> Tuple.tuple(rs.getInt(1), rs.getString(2)), table);
    }

    private List<String> changes(Env e, SourceCollectionDto.Execution execution) {
        return snapshots.changes(e.managerDto, execution.id(), 0, 200).getContent().stream()
                .map(c -> c.targetType() + ":" + c.targetName() + ":" + c.changeType() + ":" + c.changedFields()).toList();
    }

    /** 실행의 이력이 하나뿐일 때 그 전후 */
    private Map<?, ?> changeOf(Env e, SourceCollectionDto.Execution execution) {
        List<SourceCollectionDto.Change> all = snapshots.changes(e.managerDto, execution.id(), 0, 200).getContent();
        assertThat(all).hasSize(1);
        return Map.of("before", all.get(0).before(), "after", all.get(0).after());
    }

    @SuppressWarnings("unchecked")
    private String emailAt(Env e, Long table, int order) {
        Map<String, Object> content = (Map<String, Object>) snapshots.tableAt(e.managerDto, table, order, null).content();
        return ((List<Map<String, Object>>) content.get("columns")).stream().filter(c -> "email".equals(c.get("compareName"))).map(c -> (String) c.get("dataType"))
                .findFirst().orElseThrow();
    }

    private String currentEmail(Long table) {
        return jdbc.queryForObject("select DATA_TYPE_NM from TB_SRC_COL where SRC_TBL_SN = ? and CMPR_NM = 'email'", String.class, table);
    }

    /** 현재 카탈로그의 행 (테이블·컬럼·제약조건·인덱스·관계) */
    private Map<String, List<Map<String, Object>>> rows(Env e) {
        String tables = "(select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = " + e.connection + ")";
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("schema", jdbc.queryForList("select * from TB_SRC_SCHM where SRC_CNTN_SN = " + e.connection + " order by SRC_SCHM_SN"));
        rows.put("table", jdbc.queryForList("select * from TB_SRC_TBL where SRC_CNTN_SN = " + e.connection + " order by SRC_TBL_SN"));
        rows.put("column", jdbc.queryForList("select * from TB_SRC_COL where SRC_TBL_SN in " + tables + " order by SRC_COL_SN"));
        rows.put("constraint", jdbc.queryForList("select x.CNSTRT_NM, x.CNSTRT_TYPE_CD, c.CMPR_NM, xc.COL_ORD from TB_SRC_CNSTRT x join TB_SRC_CNSTRT_COL xc on xc.SRC_CNSTRT_SN = x.SRC_CNSTRT_SN"
                + " join TB_SRC_COL c on c.SRC_COL_SN = xc.SRC_COL_SN where x.SRC_TBL_SN in " + tables + " order by 1, 4"));
        rows.put("index", jdbc.queryForList("select x.IDX_NM, c.CMPR_NM, xc.COL_ORD from TB_SRC_IDX x join TB_SRC_IDX_COL xc on xc.SRC_IDX_SN = x.SRC_IDX_SN"
                + " join TB_SRC_COL c on c.SRC_COL_SN = xc.SRC_COL_SN where x.SRC_TBL_SN in " + tables + " order by 1, 3"));
        rows.put("relation", jdbc.queryForList("select r.REL_NM, r.DEL_RULE_CD, r.RFRNC_SRC_TBL_SN, c.CMPR_NM, rc.RFRNC_SRC_COL_SN, rc.COL_ORD from TB_SRC_REL r"
                + " join TB_SRC_REL_COL rc on rc.SRC_REL_SN = r.SRC_REL_SN join TB_SRC_COL c on c.SRC_COL_SN = rc.SRC_COL_SN where r.SRC_CNTN_SN = " + e.connection + " order by 1, 6"));
        return rows;
    }

    /** 구성만 (일련번호·감사 컬럼·해시 제외) : 복구 전후 비교 */
    private static Map<String, List<Map<String, Object>>> structure(Map<String, List<Map<String, Object>>> rows) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        rows.forEach((kind, list) -> result.put(kind, list.stream().map(row -> {
            Map<String, Object> kept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            row.forEach((column, value) -> {
                String c = column.toUpperCase();
                if (!c.endsWith("_SN") && !c.endsWith("_DT") && !c.endsWith("_ID") && !c.equals("HASH_VL")) kept.put(c, value);
            });
            return (Map<String, Object>) kept;
        }).toList()));
        return result;
    }

    private Map<String, List<Map<String, Object>>> structure(Env e) {
        return structure(rows(e));
    }
}
