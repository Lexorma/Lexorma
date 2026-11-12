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
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.service.SourceCatalogWriter;
import com.lexorma.source.collection.controller.SourceCollectionRestController;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec.Namespace;
import com.lexorma.source.collection.domain.enums.TargetResult;
import com.lexorma.source.collection.service.SourceCollectionInstance;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.collection.service.SourceSnapshotService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.system.project.service.ProjectManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-1c (H2 / PostgreSQL, lexorma-source) : 원본 카탈로그 수집 실행·테이블 단위 버전(Copy-on-Write)·스키마 버전·객체별 변경 이력·과거 상태 재구성·
 * 전체/부분 수집·실패 시 보존·멱등성·동시성(연결 삭제·프로젝트 삭제·중복 수집·한 건 단위 경로).
 * 원본 DB 에 접속하지 않는다 : 수집 도구(AI-2) 대신 수집 결과(SourceCollectionSpec)를 직접 만들어 넘긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceCollectionIntegrationTest {

    private static final List<String> CATALOG_TABLES = List.of("TB_SRC_SCHM", "TB_SRC_TBL", "TB_SRC_COL", "TB_SRC_CNSTRT", "TB_SRC_CNSTRT_COL", "TB_SRC_IDX",
            "TB_SRC_IDX_COL", "TB_SRC_REL", "TB_SRC_REL_COL");
    private static final List<String> HISTORY_TABLES = List.of("TB_SRC_CLCT_EXCN", "TB_SRC_CLCT_TRGT", "TB_SRC_SCHM_VER", "TB_SRC_TBL_VER", "TB_SRC_CHG_HSTRY");

    @Autowired SourceCollectionService collections;
    @Autowired SourceSnapshotService snapshots;
    @Autowired SourceCollectionRestController collectionApi;
    @Autowired SourceCollectionInstance instance;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SourceCatalogWriter writer;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ProjectManageService projectManageService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 최초 수집 · 반복 수집

    @Test
    @DisplayName("최초 FULL 수집 : 스키마(빈 스키마 포함)·테이블마다 BASE 버전, 변경 이력 없음. 같은 결과를 다시 수집하면 실행만 남고 버전·이력·현재 카탈로그 UPDATE 가 없다")
    void firstAndRepeatedCollection() {
        Env e = env("ai1c-base");
        SourceCollectionDto.Execution first = collect(e, SourceCollectionSpec.Request.full(), base());
        assertThat(first).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::reflectedOrder, SourceCollectionDto.Execution::collectionType,
                SourceCollectionDto.Execution::tableCount, SourceCollectionDto.Execution::columnCount, SourceCollectionDto.Execution::changeCount)
                .containsExactly("COMPLETED", 1, "FULL", 3, 10, 0);
        assertThat(count("TB_SRC_SCHM_VER", e.connection)).isEqualTo(3);
        assertThat(jdbc.queryForList("select CHG_TYPE_CD from TB_SRC_TBL_VER where SRC_CNTN_SN = ?", String.class, e.connection)).hasSize(3).containsOnly("BASE");
        assertThat(jdbc.queryForList("select CHG_TYPE_CD from TB_SRC_SCHM_VER where SRC_CNTN_SN = ?", String.class, e.connection)).containsOnly("BASE");
        assertThat(count("TB_SRC_CHG_HSTRY", e.connection)).isZero();
        assertThat(first.versionCount()).isEqualTo(6);
        // 현재 카탈로그 : 해시가 채워지고, 대상마다 결과가 COMPLETED
        assertThat(jdbc.queryForList("select HASH_VL from TB_SRC_TBL where SRC_CNTN_SN = ?", String.class, e.connection)).allMatch(hash -> hash.startsWith("v1:"));
        assertThat(jdbc.queryForList("select c.HASH_VL from TB_SRC_COL c join TB_SRC_TBL t on t.SRC_TBL_SN = c.SRC_TBL_SN where t.SRC_CNTN_SN = ?", String.class, e.connection))
                .hasSize(10).allMatch(hash -> hash.startsWith("v1:"));
        assertThat(snapshots.execution(e.managerDto, first.id()).targets()).extracting(SourceCollectionDto.Target::result).containsOnly("COMPLETED").hasSize(3);
        // 빈 스키마도 과거 목록에 있다
        assertThat(snapshots.catalogAt(e.managerDto, e.connection, 1, null).schemas()).extracting(SourceCollectionDto.SchemaState::schemaName)
                .containsExactly("audit", "public", "sales");

        // 같은 결과 다시 : 실행(반영 순서 2)만 남는다
        Map<String, List<Map<String, Object>>> before = catalogRows(e.connection);
        SourceCollectionDto.Execution again = collect(e, SourceCollectionSpec.Request.full(), base());
        assertThat(again).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::reflectedOrder, SourceCollectionDto.Execution::versionCount,
                SourceCollectionDto.Execution::changeCount).containsExactly("COMPLETED", 2, 0, 0);
        assertThat(count("TB_SRC_TBL_VER", e.connection) + count("TB_SRC_SCHM_VER", e.connection)).isEqualTo(6);
        assertThat(catalogRows(e.connection)).as("현재 카탈로그의 모든 행(수정 일시 포함)이 그대로").isEqualTo(before);
        // 반영 순서 2 의 상태 = 1 의 상태 (버전은 1 의 것), 마지막 확인은 2
        assertThat(snapshots.tablesAt(e.managerDto, e.connection, schemaId(e, "public"), 2, null)).extracting(SourceCollectionDto.TableState::versionOrder,
                SourceCollectionDto.TableState::lastConfirmedOrder).containsOnly(org.assertj.core.groups.Tuple.tuple(1, 2));
    }

    // ------------------------------------------------------------------ 변경 감지

    @Test
    @DisplayName("변경 감지 : 바뀐 테이블만 새 버전이 생기고 객체마다 추가·수정·삭제 이력(바뀐 항목, 전후)이 남는다. 테이블 삭제는 EXST_YN=false + 삭제 표시 버전. 과거 시점은 그때 그대로 재구성된다")
    void changeDetection() {
        Env e = env("ai1c-chg");
        collect(e, SourceCollectionSpec.Request.full(), base());
        Long users = tableId(e, "public", "users");
        Object usersAt1 = snapshots.tableAt(e.managerDto, users, 1, null).content();

        // 바꾼다 : users(컬럼 추가·수정·삭제·순서·COMMENT, UNIQUE 추가, 복합 인덱스 구성 변경, 테이블 COMMENT), orders(복합 FK 구성 변경), 테이블 하나 추가, 하나 삭제
        Map<String, List<SourceCollectionSpec.Table>> next = new LinkedHashMap<>();
        next.put("public", List.of(
                new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "사용자 (바뀜)"),
                        List.of(column("tenant_id", 1, "bigint", false), column("id", 2, "bigint", false),
                                new SourceCatalogSpec.Column(Name.of("email"), 3, "character varying(500)", 500, null, null, true, null, "이메일 (바뀜)"),
                                column("created_at", 4, "timestamp", false), column("nickname", 5, "text", true)),
                        List.of(pk("users_pkey", "tenant_id", "id"), new SourceCatalogSpec.Constraint("users_email_key", SourceConstraintType.UNIQUE, null, List.of(Name.of("email")))),
                        List.of(new SourceCatalogSpec.Index("ix_users_tenant_email", "btree", false, List.of(Name.of("email"), Name.of("tenant_id")))), List.of()),
                orders(List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")), new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id")),
                        new SourceCatalogSpec.ColumnPair(Name.of("status"), Name.of("id"))), "RESTRICT"),
                simple("payments", "id")));
        next.put("sales", List.of());
        next.put("audit", List.of());
        SourceCollectionDto.Execution second = collect(e, SourceCollectionSpec.Request.full(), result(next));
        assertThat(second.reflectedOrder()).isEqualTo(2);

        // 버전 : users·orders MODIFIED, payments ADDED, sales.v_orders REMOVED (sales 의 뷰를 지웠다). 스키마는 그대로라 스키마 버전 없음
        assertThat(versionTypes(e.connection, 2)).containsExactlyInAnyOrderEntriesOf(Map.of("users", "MODIFIED", "orders", "MODIFIED", "payments", "ADDED", "v_orders", "REMOVED"));
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_SCHM_VER where SRC_CNTN_SN = ? and RFLT_ORD = 2", Integer.class, e.connection)).isZero();

        // 이력 : 객체별
        Map<String, String> changes = changes(snapshots.changes(e.managerDto, second.id(), 0, 200).getContent());
        assertThat(changes).containsEntry("TABLE:users:MODIFIED", "[comment]")
                .containsEntry("COLUMN:email:MODIFIED", "[dataType, maxLength, comment]")
                .containsEntry("COLUMN:created_at:MODIFIED", "[ordinal]")
                .containsEntry("COLUMN:nickname:ADDED", "[]")
                .containsEntry("COLUMN:name:REMOVED", "[]")
                .containsEntry("CONSTRAINT:users_email_key:ADDED", "[]")
                .containsEntry("INDEX:ix_users_tenant_email:MODIFIED", "[columns]")
                .containsEntry("RELATION:fk_orders_users:MODIFIED", "[updateRule, columns]")
                .containsEntry("TABLE:payments:ADDED", "[]")
                .containsEntry("TABLE:v_orders:REMOVED", "[]")
                .hasSize(10);
        SourceCollectionDto.Change email = snapshots.changes(e.managerDto, second.id(), 0, 200).getContent().stream()
                .filter(change -> "email".equals(change.targetName())).findFirst().orElseThrow();
        assertThat(((Map<?, ?>) email.before()).get("dataType")).isEqualTo("character varying(320)");
        assertThat(((Map<?, ?>) email.after()).get("dataType")).isEqualTo("character varying(500)");

        // 현재 카탈로그 : 삭제된 뷰와 그 컬럼은 EXST_YN = false, 지운 컬럼도 false, 하위 객체는 지금 구성만
        Long view = tableId(e, "sales", "v_orders");
        assertThat(jdbc.queryForObject("select EXST_YN from TB_SRC_TBL where SRC_TBL_SN = ?", Boolean.class, view)).isFalse();
        assertThat(jdbc.queryForList("select EXST_YN from TB_SRC_COL where SRC_TBL_SN = ?", Boolean.class, view)).containsOnly(false);
        assertThat(jdbc.queryForObject("select EXST_YN from TB_SRC_COL where SRC_TBL_SN = ? and CMPR_NM = 'name'", Boolean.class, users)).isFalse();
        assertThat(jdbc.queryForList("select CNSTRT_NM from TB_SRC_CNSTRT where SRC_TBL_SN = ? order by CNSTRT_NM", String.class, users)).containsExactly("users_email_key", "users_pkey");
        assertThat(jdbc.queryForList("select c.CMPR_NM from TB_SRC_IDX_COL ic join TB_SRC_IDX i on i.SRC_IDX_SN = ic.SRC_IDX_SN join TB_SRC_COL c on c.SRC_COL_SN = ic.SRC_COL_SN"
                + " where i.SRC_TBL_SN = ? order by ic.COL_ORD", String.class, users)).containsExactly("email", "tenant_id");

        // 과거 상태 : 1 은 그때 그대로, 2 는 지금
        assertThat(snapshots.tableAt(e.managerDto, users, 1, null).content()).isEqualTo(usersAt1);
        assertThat(columnsOf(snapshots.tableAt(e.managerDto, users, 1, null).content())).containsExactly("tenant_id", "id", "email", "name", "created_at");
        assertThat(columnsOf(snapshots.tableAt(e.managerDto, users, 2, null).content())).containsExactly("tenant_id", "id", "email", "created_at", "nickname");
        assertThat(names(snapshots.tablesAt(e.managerDto, e.connection, schemaId(e, "sales"), 1, null))).containsExactly("v_orders");
        assertThat(names(snapshots.tablesAt(e.managerDto, e.connection, schemaId(e, "sales"), 2, null))).isEmpty();
        assertThat(names(snapshots.tablesAt(e.managerDto, e.connection, schemaId(e, "public"), 2, null))).containsExactly("orders", "payments", "users");
        assertThatThrownBy(() -> snapshots.tableAt(e.managerDto, view, 2, null)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        Long payments = tableId(e, "public", "payments");
        assertThatThrownBy(() -> snapshots.tableAt(e.managerDto, payments, 1, null)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        // 시각으로도 묻는다 : 두 번째 반영 직전 = 1
        LocalDateTime firstAt = snapshots.execution(e.managerDto, second.id()).execution().reflectedAt().minusNanos(1000);
        assertThat(snapshots.catalogAt(e.managerDto, e.connection, null, firstAt).reflectedOrder()).isEqualTo(1);
        // 여러 실행에 걸친 테이블 이력·버전
        assertThat(snapshots.tableVersions(e.managerDto, users)).extracting(SourceCollectionDto.TableVersion::reflectedOrder, SourceCollectionDto.TableVersion::changeType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "BASE"), org.assertj.core.groups.Tuple.tuple(2, "MODIFIED"));
        assertThat(snapshots.tableChanges(e.managerDto, users)).hasSize(7).extracting(SourceCollectionDto.Change::reflectedOrder).containsOnly(2);
    }

    // ------------------------------------------------------------------ 스키마 · 부분 수집 · 재등장

    @Test
    @DisplayName("스키마 버전·부분 수집 : 스키마 삭제는 FULL 에서만 판정(삭제 표시 버전), 부분 수집은 범위 밖을 건드리지 않고, 없는 스키마 요청은 실패. 같은 이름으로 다시 생긴 테이블은 생성 차수가 늘어난다")
    void schemasPartialAndReappearance() {
        Env e = env("ai1c-scm");
        collect(e, SourceCollectionSpec.Request.full(), base());
        Long orders = tableId(e, "public", "orders");

        // PARTIAL(sales) : sales 만 비교한다. 결과에 public 이 없어도 public 은 그대로, sales 의 뷰가 빠졌으니 sales 만 바뀐다
        Map<String, List<Map<String, Object>>> publicBefore = catalogRows(e.connection);
        SourceCollectionDto.Execution partial = collect(e, SourceCollectionSpec.Request.partial(ns("sales")), result(Map.of("sales", List.of(simple("refunds", "id")))));
        assertThat(versionTypes(e.connection, partial.reflectedOrder())).containsExactlyInAnyOrderEntriesOf(Map.of("refunds", "ADDED", "v_orders", "REMOVED"));
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = 'public' and t.EXST_YN = true",
                Integer.class, e.connection)).isEqualTo(2);
        assertThat(publicRows(catalogRows(e.connection), e)).isEqualTo(publicRows(publicBefore, e));
        assertThat(snapshots.execution(e.managerDto, partial.id()).targets()).extracting(SourceCollectionDto.Target::schemaName, SourceCollectionDto.Target::result)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("sales", "COMPLETED"));
        // 마지막 확인 : public 은 1, sales 는 2
        assertThat(snapshots.catalogAt(e.managerDto, e.connection, 2, null).schemas()).extracting(SourceCollectionDto.SchemaState::schemaName,
                SourceCollectionDto.SchemaState::lastConfirmedOrder).contains(org.assertj.core.groups.Tuple.tuple("public", 1), org.assertj.core.groups.Tuple.tuple("sales", 2));

        // 없는 스키마를 부분 수집 : NOT_FOUND → 실행 FAILED, 아무것도 바뀌지 않는다 (스키마를 지우지 않는다)
        Map<String, List<Map<String, Object>>> beforeMissing = catalogRows(e.connection);
        SourceCollectionDto.Execution missing = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.partial(ns("sales"), ns("ghost")));
        assertThatThrownBy(() -> collections.apply(e.manager(), missing.jobId(), result(Map.of("sales", List.of(simple("refunds", "id"))))))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_INCOMPLETE));
        assertThat(snapshots.execution(e.managerDto, missing.id())).satisfies(detail -> {
            assertThat(detail.execution().status()).isEqualTo("FAILED");
            assertThat(detail.execution().reflectedOrder()).isNull();
            assertThat(detail.targets()).extracting(SourceCollectionDto.Target::schemaName, SourceCollectionDto.Target::result)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("ghost", "NOT_FOUND"), org.assertj.core.groups.Tuple.tuple("sales", "COMPLETED"));
        });
        assertThat(catalogRows(e.connection)).isEqualTo(beforeMissing);

        // FULL 에서 audit 스키마가 사라지고 orders 테이블도 사라진다 → 스키마·테이블 삭제 표시
        Map<String, List<SourceCollectionSpec.Table>> withoutAudit = new LinkedHashMap<>();
        withoutAudit.put("public", List.of(users()));
        withoutAudit.put("sales", List.of(simple("refunds", "id")));
        SourceCollectionDto.Execution removed = collect(e, SourceCollectionSpec.Request.full(), result(withoutAudit));
        assertThat(jdbc.queryForObject("select CHG_TYPE_CD from TB_SRC_SCHM_VER where SRC_CNTN_SN = ? and RFLT_ORD = ?", String.class, e.connection, removed.reflectedOrder()))
                .isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("select EXST_YN from TB_SRC_SCHM where SRC_SCHM_SN = ?", Boolean.class, schemaId(e, "audit"))).isFalse();
        assertThat(snapshots.catalogAt(e.managerDto, e.connection, removed.reflectedOrder(), null).schemas()).extracting(SourceCollectionDto.SchemaState::schemaName)
                .containsExactly("public", "sales");
        assertThat(snapshots.catalogAt(e.managerDto, e.connection, 1, null).schemas()).extracting(SourceCollectionDto.SchemaState::schemaName)
                .containsExactly("audit", "public", "sales");

        // orders 가 같은 이름으로 다시 생긴다 : 같은 행을 되살리고 생성 차수 2, 이력은 REMOVED → ADDED 로 이어진다. audit 도 다시 생긴다
        SourceCollectionDto.Execution back = collect(e, SourceCollectionSpec.Request.full(), base());
        assertThat(tableId(e, "public", "orders")).isEqualTo(orders);
        assertThat(jdbc.queryForObject("select CRT_CYCL from TB_SRC_TBL where SRC_TBL_SN = ?", Integer.class, orders)).isEqualTo(2);
        assertThat(snapshots.tableVersions(e.managerDto, orders)).extracting(SourceCollectionDto.TableVersion::changeType, SourceCollectionDto.TableVersion::creationCycle)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("BASE", 1), org.assertj.core.groups.Tuple.tuple("REMOVED", 1), org.assertj.core.groups.Tuple.tuple("ADDED", 2));
        assertThat(snapshots.tableChanges(e.managerDto, orders)).extracting(SourceCollectionDto.Change::changeType, SourceCollectionDto.Change::creationCycle)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("REMOVED", 1), org.assertj.core.groups.Tuple.tuple("ADDED", 2));
        assertThat(jdbc.queryForList("select CHG_TYPE_CD || ':' || CRT_CYCL from TB_SRC_SCHM_VER where SRC_SCHM_SN = ? order by RFLT_ORD", String.class, schemaId(e, "audit")))
                .containsExactly("BASE:1", "REMOVED:1", "ADDED:2");
        // 과거 시점 : 사라졌던 때는 없다
        assertThatThrownBy(() -> snapshots.tableAt(e.managerDto, orders, removed.reflectedOrder(), null)).isInstanceOf(CustomException.class);
        assertThat(snapshots.tableAt(e.managerDto, orders, back.reflectedOrder(), null).creationCycle()).isEqualTo(2);
    }

    @Test
    @DisplayName("원본 관계 : 다른 스키마의 테이블을 참조하는 FK 는 참조 대상이 나중에(부분 수집으로) 들어오면 이어진다. 버전에 남은 관측 결과(원본 이름)는 바뀌지 않는다")
    void relationRelink() {
        Env e = env("ai1c-rel");
        // 처음에는 sales 만 : sales.orders → public.users (아직 없음)
        SourceCollectionSpec.Table salesOrders = new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null),
                List.of(column("id", 1, "bigint", false), column("user_id", 2, "bigint", false)), List.of(), List.of(),
                List.of(new SourceCatalogSpec.Relation("fk_sales_user", null, Name.of("public"), Name.of("users"), "CASCADE", null,
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))))));
        collect(e, SourceCollectionSpec.Request.partial(ns("sales")), result(Map.of("sales", List.of(salesOrders))));
        Long orders = tableId(e, "sales", "orders");
        String sql = "select RFRNC_SRC_TBL_SN from TB_SRC_REL where SRC_TBL_SN = ?";
        assertThat(jdbc.queryForObject(sql, Long.class, orders)).isNull();
        String versionBefore = jdbc.queryForObject("select TBL_CN from TB_SRC_TBL_VER where SRC_TBL_SN = ?", String.class, orders);

        // public 을 부분 수집 : users 가 들어오면 이전 관계가 이어진다 (컬럼까지)
        collect(e, SourceCollectionSpec.Request.partial(ns("public")), result(Map.of("public", List.of(simple("users", "id")))));
        Long users = tableId(e, "public", "users");
        assertThat(jdbc.queryForObject(sql, Long.class, orders)).isEqualTo(users);
        assertThat(jdbc.queryForObject("select rc.RFRNC_SRC_COL_SN from TB_SRC_REL_COL rc join TB_SRC_REL r on r.SRC_REL_SN = rc.SRC_REL_SN where r.SRC_TBL_SN = ?", Long.class, orders))
                .isEqualTo(jdbc.queryForObject("select SRC_COL_SN from TB_SRC_COL where SRC_TBL_SN = ? and CMPR_NM = 'id'", Long.class, users));
        // sales.orders 는 다시 수집하지 않았다 : 버전도 이력도 그대로 (원본에서 확인한 관계는 이름뿐이다)
        assertThat(jdbc.queryForList("select TBL_CN from TB_SRC_TBL_VER where SRC_TBL_SN = ?", String.class, orders)).containsExactly(versionBefore);
        assertThat(versionBefore).contains("\"referencedSchema\":\"public\"", "\"referencedTable\":\"users\"").doesNotContain("SRC_TBL_SN");
        // 같은 스키마 안의 복합 FK 는 처음부터 이어진다
        Env f = env("ai1c-rel2");
        collect(f, SourceCollectionSpec.Request.full(), base());
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_REL_COL rc join TB_SRC_REL r on r.SRC_REL_SN = rc.SRC_REL_SN where r.SRC_CNTN_SN = ? and rc.RFRNC_SRC_COL_SN is not null",
                Integer.class, f.connection)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 실패 · 원자성 · 멱등성

    @Test
    @DisplayName("실패와 원자성 : 일부 스키마가 실패하면 실행 전체를 반영하지 않고, 반영 도중 오류는 현재 카탈로그·버전·이력까지 모두 되돌린다. 같은 작업을 다시 보내도 두 번 반영되지 않는다")
    void failureAndIdempotency() {
        Env e = env("ai1c-fail");
        SourceCollectionDto.Execution first = collect(e, SourceCollectionSpec.Request.full(), base());
        Map<String, List<Map<String, Object>>> before = catalogRows(e.connection);
        Map<String, Integer> history = historyCounts(e.connection);

        // 같은 작업 다시 : 이미 반영 → 그대로 돌려주고 아무것도 바꾸지 않는다
        SourceCollectionDto.Execution replay = collections.apply(e.manager(), first.jobId(), changed());
        assertThat(replay).extracting(SourceCollectionDto.Execution::id, SourceCollectionDto.Execution::reflectedOrder).containsExactly(first.id(), 1);
        assertThat(historyCounts(e.connection)).isEqualTo(history);

        // audit 실패 : 전체 보류
        SourceCollectionDto.Execution failed = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        List<SourceCollectionSpec.Schema> schemas = new ArrayList<>(changed().schemas());
        schemas.removeIf(schema -> "audit".equals(schema.schema().value()));
        schemas.add(new SourceCollectionSpec.Schema(Name.of("app"), Name.of("audit"), TargetResult.FAILED, "QUERY_TIMEOUT password=secret", null));
        assertThatThrownBy(() -> collections.apply(e.manager(), failed.jobId(), new SourceCollectionSpec.Result(schemas)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_INCOMPLETE));
        SourceCollectionDto.ExecutionDetail failedDetail = snapshots.execution(e.managerDto, failed.id());
        assertThat(failedDetail.execution()).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode, SourceCollectionDto.Execution::reflectedOrder)
                .containsExactly("FAILED", "SOURCE_COLLECTION_INCOMPLETE", null);
        // 도구가 준 오류 코드는 정해진 모양만 남긴다 (값을 섞어 보내도 남지 않는다)
        assertThat(failedDetail.targets()).extracting(SourceCollectionDto.Target::schemaName, SourceCollectionDto.Target::result, SourceCollectionDto.Target::errorCode)
                .contains(org.assertj.core.groups.Tuple.tuple("audit", "FAILED", "UNKNOWN"), org.assertj.core.groups.Tuple.tuple("public", "COMPLETED", null));
        assertThat(catalogRows(e.connection)).isEqualTo(before);
        assertThat(historyCounts(e.connection)).containsEntry("TB_SRC_TBL_VER", history.get("TB_SRC_TBL_VER")).containsEntry("TB_SRC_CHG_HSTRY", history.get("TB_SRC_CHG_HSTRY"));
        // 끝난 작업은 다시 반영할 수 없다 (새 작업으로 다시 실행한다)
        assertThatThrownBy(() -> collections.apply(e.manager(), failed.jobId(), changed())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_RUNNING));

        // 반영 도중 오류 : 다음 반영 순서의 users 버전 자리를 미리 막아 두면 버전 INSERT 에서 실패한다 → 앞에서 고친 현재 카탈로그·이력까지 되돌아간다
        Long users = tableId(e, "public", "users");
        jdbc.update("insert into TB_SRC_TBL_VER(SRC_TBL_VER_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, SRC_TBL_SN, SRC_CLCT_EXCN_SN, RFLT_ORD, PATH_CN, CMPR_NM, CRT_CYCL, CHG_TYPE_CD,"
                        + " HASH_VL, FRMT_VER_NO, TBL_CN) values(?, ?, ?, ?, ?, ?, 2, 'x', 'users', 1, 'MODIFIED', 'x', 1, '{}')",
                -e.connection, e.project.getId(), e.connection, schemaId(e, "public"), users, first.id());
        SourceCollectionDto.Execution broken = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        assertThatThrownBy(() -> collections.apply(e.manager(), broken.jobId(), changed())).isInstanceOf(RuntimeException.class).isNotInstanceOf(CustomException.class);
        // 예상하지 못한 오류는 종류만 남긴다 (SQL·값이 섞인 메시지를 남기지 않는다)
        assertThat(snapshots.execution(e.managerDto, broken.id()).execution()).satisfies(rolledBack -> {
            assertThat(rolledBack.status()).isEqualTo("FAILED");
            assertThat(rolledBack.errorCode()).isEqualTo("UNEXPECTED_ERROR");
            assertThat(rolledBack.errorMessage()).matches("[A-Za-z]+Exception").doesNotContain("users");
        });
        assertThat(catalogRows(e.connection)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_VER where SRC_CNTN_SN = ? and RFLT_ORD = 2", Integer.class, e.connection)).isEqualTo(1);
        assertThat(count("TB_SRC_CHG_HSTRY", e.connection)).isZero();
        jdbc.update("delete from TB_SRC_TBL_VER where SRC_TBL_VER_SN = ?", -e.connection);

        // 새 작업은 정상 반영 (반영 순서는 반영된 실행에만 붙어 2)
        SourceCollectionDto.Execution ok = collect(e, SourceCollectionSpec.Request.full(), changed());
        assertThat(ok.reflectedOrder()).isEqualTo(2);
        assertThat(count("TB_SRC_CHG_HSTRY", e.connection)).isPositive();
        // 실행 목록 : 최근 것부터, 실패·성공이 모두 남는다
        assertThat(snapshots.executions(e.managerDto, e.connection, 0, 50).getContent()).extracting(SourceCollectionDto.Execution::status)
                .containsExactly("COMPLETED", "FAILED", "FAILED", "COMPLETED");

        // 결과를 만들지 못한 수집(도구 오류) : 오류 코드는 정해진 모양만 남고, 진행 중 표시가 풀려 다음 수집을 등록할 수 있다
        SourceCollectionDto.Execution toolError = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        assertThat(collections.fail(e.manager(), toolError.jobId(), "CONNECT_TIMEOUT")).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode)
                .containsExactly("FAILED", "CONNECT_TIMEOUT");
        SourceCollectionDto.Execution badCode = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        assertThat(collections.fail(e.manager(), badCode.jobId(), "jdbc:postgresql://db.internal/app?password=x").errorCode()).isEqualTo("UNKNOWN");
        assertThat(catalogRows(e.connection)).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CLCT_EXCN where PRGRS_CNTN_SN = ?", Integer.class, e.connection)).isZero();
    }

    // ------------------------------------------------------------------ 중복 수집 · 멈춘 실행

    @Test
    @DisplayName("같은 연결의 중복 수집 : 진행 중이면 거절(동시에 등록해도 하나만). 멈춘 실행은 이 서버에서 돌지 않거나 다른 서버의 살아 있음 확인이 오래됐을 때만 정리한다")
    void duplicateAndAbandoned() throws Exception {
        Env e = env("ai1c-dup");
        SourceCollectionDto.Execution running = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        assertThatThrownBy(() -> collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full()))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_IN_PROGRESS));
        // 이 서버에서 돌고 있는 실행은 정리하지 않는다
        assertThat(collections.recoverAbandoned(e.manager(), e.connection)).isFalse();
        assertThat(collections.heartbeat(e.manager(), running.jobId())).isTrue();
        collections.cancel(e.manager(), running.jobId());
        assertThat(snapshots.execution(e.managerDto, running.id()).execution().status()).isEqualTo("CANCELLED");

        // 동시에 등록 : 앞의 등록이 커밋하지 않은 동안 뒤의 등록은 잠금에서 기다리고(실제 경합), 앞의 것이 커밋하면 진행 중이라 거절된다
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<Object> first = pool.submit(() -> tx.execute(s -> {
                SourceCollectionDto.Execution started = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
                registered.countDown();
                await(release);
                return started;
            }));
            assertThat(registered.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Object> second = pool.submit(call(() -> collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full())));
            assertThat(awaitLockWait(second)).containsIgnoringCase(postgres() ? "TB_SRC_CNTN" : "TB_PRJ");
            release.countDown();
            SourceCollectionDto.Execution winner = (SourceCollectionDto.Execution) first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_IN_PROGRESS));
            assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = ? and EXCN_STTS_CD = 'RUNNING'", Integer.class, e.connection)).isEqualTo(1);

            // 다른 서버가 맡은 실행 : 살아 있음 확인이 최근이면 정리하지 않는다
            jdbc.update("update TB_SRC_CLCT_EXCN set EXCN_SRVR_ID = 'other-host/1234', LAST_IDNTY_DT = ? where SRC_CLCT_EXCN_SN = ?", Timestamp.valueOf(LocalDateTime.now()), winner.id());
            assertThatThrownBy(() -> collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full()))
                    .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_IN_PROGRESS));
            // 다른 서버가 멈췄다(재시작 전 서버 포함) : 오래된 실행은 새 등록이 정리한다
            jdbc.update("update TB_SRC_CLCT_EXCN set LAST_IDNTY_DT = ? where SRC_CLCT_EXCN_SN = ?", Timestamp.valueOf(LocalDateTime.now().minusHours(1)), winner.id());
            SourceCollectionDto.Execution next = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
            assertThat(snapshots.execution(e.managerDto, winner.id()).execution()).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode)
                    .containsExactly("FAILED", "SOURCE_COLLECTION_ABANDONED");
            // 정리 ↔ 살아 있음 확인 경합 : 다른 서버의 오래된 실행을 정리하려는 순간, 그 서버의 살아 있음 확인(UPDATE)이 커밋 전이다
            // → 정리는 그 행에서 기다렸다가 바뀐 확인 시각을 보고 정리하지 않는다 (조건부 UPDATE)
            LocalDateTime stale = LocalDateTime.now().minusHours(1);
            jdbc.update("update TB_SRC_CLCT_EXCN set EXCN_SRVR_ID = 'other-host/5678', LAST_IDNTY_DT = ? where SRC_CLCT_EXCN_SN = ?", Timestamp.valueOf(stale), next.id());
            CountDownLatch touched = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<Object> heartbeat = pool.submit(() -> tx.execute(s -> {
                jdbc.update("update TB_SRC_CLCT_EXCN set LAST_IDNTY_DT = ? where SRC_CLCT_EXCN_SN = ?", Timestamp.valueOf(LocalDateTime.now()), next.id());
                touched.countDown();
                await(commit);
                return "touched";
            }));
            assertThat(touched.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Object> recover = pool.submit(call(() -> collections.recoverAbandoned(e.manager(), e.connection)));
            assertThat(awaitLockWait(recover)).containsIgnoringCase("TB_SRC_CLCT_EXCN");
            commit.countDown();
            assertThat(heartbeat.get(30, TimeUnit.SECONDS)).isEqualTo("touched");
            assertThat(recover.get(30, TimeUnit.SECONDS)).isEqualTo(false);
            assertThat(jdbc.queryForObject("select EXCN_STTS_CD from TB_SRC_CLCT_EXCN where SRC_CLCT_EXCN_SN = ?", String.class, next.id())).isEqualTo("RUNNING");
            jdbc.update("update TB_SRC_CLCT_EXCN set EXCN_SRVR_ID = ? where SRC_CLCT_EXCN_SN = ?", instance.serverId(), next.id());

            // 이 서버의 실행인데 지금 돌고 있지 않다 (작업 스레드가 사라졌다) : 바로 정리한다
            collections.cancel(e.manager(), next.jobId());
            Long orphan = insertRunning(e, instance.serverId(), LocalDateTime.now(), next.id());
            assertThat(collections.recoverAbandoned(e.manager(), e.connection)).isTrue();
            assertThat(jdbc.queryForObject("select EXCN_STTS_CD from TB_SRC_CLCT_EXCN where SRC_CLCT_EXCN_SN = ?", String.class, orphan)).isEqualTo("FAILED");
            // 진행 중인 실행이 남아 있지 않다 (DB 의 UNIQUE 가 같은 연결의 진행 중 실행을 하나로 묶는다)
            assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CLCT_EXCN where PRGRS_CNTN_SN = ?", Integer.class, e.connection)).isZero();
            assertThatThrownBy(() -> jdbc.update("update TB_SRC_CLCT_EXCN set PRGRS_CNTN_SN = ? where SRC_CLCT_EXCN_SN in (?, ?)", e.connection, winner.id(), orphan))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 연결 삭제 · 프로젝트 삭제 · 한 건 단위 경로와의 동시성

    @Test
    @DisplayName("연결 삭제 ↔ 반영 : 반영이 먼저 끝나면 삭제가 그 결과까지 지우고, 삭제가 먼저면 반영은 연결이 없어 실패한다 (남는 행 없음, 서로 기다리다 멈추지 않는다)")
    void connectionDeletionRace() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 반영이 먼저 : 반영 트랜잭션이 커밋하지 않은 동안 삭제는 기다린다
            Env a = env("ai1c-cdel1");
            SourceCollectionDto.Execution run = collections.start(a.manager(), a.connection, SourceCollectionSpec.Request.full());
            CountDownLatch applied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<Object> apply = pool.submit(() -> tx.execute(s -> {
                SecurityTestSupport.loginAs(projectRepository, a.project.getId(), String.valueOf(a.managerAccount.getId()), "ROLE_DBA");
                SourceCollectionDto.Execution done = collections.apply(a.manager(), run.jobId(), base());
                applied.countDown();
                await(release);
                return done;
            }));
            assertThat(applied.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Object> delete = pool.submit(call(() -> {
                SecurityTestSupport.loginAs(projectRepository, a.project.getId(), String.valueOf(a.managerAccount.getId()), "ROLE_DBA");
                return connectionApi.delete(a.managerDto, a.connection).getBody();
            }));
            // 삭제는 프로젝트를 공유 잠금으로 잡은 뒤 반영이 잡은 연결 행에서 기다린다 (H2 는 공유 잠금이 배타라 프로젝트 행에서)
            assertThat(awaitLockWait(delete)).containsIgnoringCase(postgres() ? "TB_SRC_CNTN" : "TB_PRJ");
            release.countDown();
            assertThat(apply.get(30, TimeUnit.SECONDS)).isInstanceOf(SourceCollectionDto.Execution.class);
            assertThat(delete.get(30, TimeUnit.SECONDS)).isInstanceOf(Map.class);
            assertNothingLeft(a.connection);

            // 삭제가 먼저 : 삭제 트랜잭션이 커밋하지 않은 동안 반영은 기다리고, 커밋 뒤에는 연결이 없다
            Env b = env("ai1c-cdel2");
            SourceCollectionDto.Execution run2 = collections.start(b.manager(), b.connection, SourceCollectionSpec.Request.full());
            CountDownLatch deleted = new CountDownLatch(1);
            CountDownLatch release2 = new CountDownLatch(1);
            Future<Object> delete2 = pool.submit(() -> tx.execute(s -> {
                SecurityTestSupport.loginAs(projectRepository, b.project.getId(), String.valueOf(b.managerAccount.getId()), "ROLE_DBA");
                Object body = connectionApi.delete(b.managerDto, b.connection).getBody();
                deleted.countDown();
                await(release2);
                return body;
            }));
            assertThat(deleted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Object> apply2 = pool.submit(call(() -> collections.apply(b.manager(), run2.jobId(), base())));
            assertThat(awaitLockWait(apply2)).containsIgnoringCase(postgres() ? "TB_SRC_CNTN" : "TB_PRJ");
            release2.countDown();
            assertThat(delete2.get(30, TimeUnit.SECONDS)).isInstanceOf(Map.class);
            assertThat(apply2.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
            assertNothingLeft(b.connection);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("프로젝트 삭제 ↔ 반영·한 건 단위 경로 : 잠금 순서(프로젝트 → 연결)가 같아 교착 없이 직렬화된다. 반영이 먼저면 삭제가 다 지우고, 삭제가 먼저면 반영은 PROJECT_NOT_FOUND")
    void projectDeletionRace() throws Exception {
        Account admin = admin();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (String first : List.of("apply", "delete", "writer")) {
                Project project = soleProject("ai1c-pdel-" + first, admin);
                AccountDto owner = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
                Long connection = connectionApi.create(owner, request("연결", "POSTGRESQL", "app")).getBody().id();
                ExecutionContext context = ExecutionContext.user(owner);
                SourceCollectionDto.Execution run = collections.start(context, connection, SourceCollectionSpec.Request.full());
                CountDownLatch inside = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                Callable<Object> deletion = () -> {
                    projectManageService.deleteProject(project.getId(), owner);
                    return "deleted";
                };
                Future<Object> holder;
                Future<Object> other;
                if (first.equals("delete")) {
                    holder = pool.submit(() -> tx.execute(s -> {
                        projectManageService.deleteProject(project.getId(), owner);
                        inside.countDown();
                        await(release);
                        return "deleted";
                    }));
                    assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();
                    other = pool.submit(call(() -> collections.apply(context, run.jobId(), base())));
                } else {
                    holder = pool.submit(() -> tx.execute(s -> {
                        Object result;
                        if (first.equals("apply")) {
                            result = collections.apply(context, run.jobId(), base());
                        } else {
                            // AI-1b 의 한 건 단위 경로 : 예전에는 연결을 먼저 잠그고 커밋 때 프로젝트를 잠가 프로젝트 삭제와 서로를 기다릴 수 있었다
                            SourceSchema schema = writer.saveSchema(project.getId(), connection, Name.of("app"), Name.of("public"));
                            result = writer.saveTable(project.getId(), schema.getId(), new SourceCatalogSpec.Table(Name.of("t"), SourceTableType.TABLE, null));
                        }
                        inside.countDown();
                        await(release);
                        return result;
                    }));
                    assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();
                    other = pool.submit(call(deletion));
                }
                // 뒤의 트랜잭션은 첫 잠금인 프로젝트 행에서 기다린다 : 앞의 것이 연결보다 프로젝트를 먼저 잡았다는 뜻이다 (Writer 포함)
                assertThat(awaitLockWait(other)).as(first).containsIgnoringCase("TB_PRJ");
                release.countDown();
                Object holderResult = holder.get(30, TimeUnit.SECONDS);
                Object otherResult = other.get(30, TimeUnit.SECONDS);
                if (first.equals("delete")) {
                    assertThat(otherResult).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_NOT_FOUND));
                } else {
                    assertThat(holderResult).isNotInstanceOf(Throwable.class);
                    assertThat(otherResult).isEqualTo("deleted");
                }
                assertThat(projectRepository.findById(project.getId())).isEmpty();
                for (String table : concat(CATALOG_TABLES.stream().filter(t -> !t.endsWith("_COL") || t.equals("TB_SRC_COL")).toList(), HISTORY_TABLES)) {
                    assertThat(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, project.getId())).as(first + " " + table).isZero();
                }
                assertNothingLeft(connection);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("반영 중 프로젝트 비활성화·권한 회수 (경합) : 바꾸는 트랜잭션이 프로젝트 행을 잡고 있으면 반영은 프로젝트 잠금에서 기다렸다가, 커밋된 상태로 다시 확인해 거절된다 (남는 변경 없음)")
    void deactivationAndRevocationRace() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (String change : List.of("deactivate", "revoke")) {
                Env e = env("ai1c-race-" + change);
                SourceCollectionDto.Execution first = collect(e, SourceCollectionSpec.Request.full(), base());
                Map<String, List<Map<String, Object>>> before = catalogRows(e.connection);
                Map<String, Integer> history = historyCounts(e.connection);
                SourceCollectionDto.Execution run = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
                CountDownLatch changed = new CountDownLatch(1);
                CountDownLatch commit = new CountDownLatch(1);
                Future<Object> holder = pool.submit(() -> tx.execute(s -> {
                    Project project = projectRepository.findById(e.project.getId()).orElseThrow();
                    if (change.equals("deactivate")) project.setIsActive(false);
                    else project.setAccount(accountRepository.findById(e.memberAccount.getId()).orElseThrow());
                    projectRepository.flush();
                    changed.countDown();
                    await(commit);
                    return change;
                }));
                assertThat(changed.await(30, TimeUnit.SECONDS)).isTrue();
                Future<Object> apply = pool.submit(call(() -> collections.apply(e.manager(), run.jobId(), changed())));
                assertThat(awaitLockWait(apply)).containsIgnoringCase("TB_PRJ");
                commit.countDown();
                assertThat(holder.get(30, TimeUnit.SECONDS)).isEqualTo(change);
                assertThat(apply.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(CustomException.class,
                        code(change.equals("deactivate") ? ErrorCode.PROJECT_ACCESS_DENIED : ErrorCode.EXECUTION_PERMISSION_DENIED));
                assertThat(jdbc.queryForObject("select EXCN_STTS_CD from TB_SRC_CLCT_EXCN where SRC_CLCT_EXCN_SN = ?", String.class, run.id())).isEqualTo("FAILED");
                assertThat(catalogRows(e.connection)).isEqualTo(before);
                assertThat(historyCounts(e.connection)).isEqualTo(history);
                assertThat(first.reflectedOrder()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("수집 이력의 삭제 정합성 : 연결·프로젝트 삭제가 도중에 실패하면 수집 실행·대상·버전·이력까지 모두 되돌아가고, 성공하면 남지 않는다. 비활성화는 이력을 지우지 않고 다른 프로젝트·연결은 그대로다")
    void historyDeletionRollback() {
        // 연결 삭제 : 같은 프로젝트의 다른 연결이 이 연결의 컬럼을 가리키는 (있어서는 안 되는) 행 → 컬럼을 지우는 단계에서 실패
        Env e = env("ai1c-hdel");
        collect(e, SourceCollectionSpec.Request.full(), base());
        collect(e, SourceCollectionSpec.Request.full(), changed());
        Long other = connectionApi.create(e.managerDto, request("다른 DB", "POSTGRESQL", "other")).getBody().id();
        collect(new Env(e.project, other, e.managerAccount, e.memberAccount, e.viewerAccount, e.managerDto, e.memberDto, e.viewerDto), SourceCollectionSpec.Request.full(), base());
        Map<String, Integer> mine = allCounts(e.connection);
        Map<String, Integer> theirs = allCounts(other);
        assertThat(mine.values()).allMatch(count -> count > 0);
        Long strayRelation = jdbc.queryForObject("select min(SRC_REL_SN) from TB_SRC_REL where SRC_CNTN_SN = ?", Long.class, other);
        Long strayColumn = jdbc.queryForObject("select min(c.SRC_COL_SN) from TB_SRC_COL c join TB_SRC_TBL t on t.SRC_TBL_SN = c.SRC_TBL_SN where t.SRC_CNTN_SN = ?", Long.class, other);
        Long myColumn = jdbc.queryForObject("select min(c.SRC_COL_SN) from TB_SRC_COL c join TB_SRC_TBL t on t.SRC_TBL_SN = c.SRC_TBL_SN where t.SRC_CNTN_SN = ?", Long.class, e.connection);
        jdbc.update("insert into TB_SRC_REL_COL(SRC_REL_COL_SN, SRC_REL_SN, SRC_COL_SN, RFRNC_SRC_COL_SN, RFRNC_COL_NM, COL_ORD) values(-9301, ?, ?, ?, 'id', 9)", strayRelation, strayColumn, myColumn);
        SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.managerAccount.getId()), "ROLE_DBA");
        assertThatThrownBy(() -> connectionApi.delete(e.managerDto, e.connection)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(allCounts(e.connection)).as("앞 단계에서 지운 수집 이력까지 되돌아갔다").isEqualTo(mine);
        jdbc.update("delete from TB_SRC_REL_COL where SRC_REL_COL_SN = -9301");
        connectionApi.delete(e.managerDto, e.connection);
        assertNothingLeft(e.connection);
        assertThat(allCounts(other)).isEqualTo(theirs);

        // 프로젝트 : 비활성화는 아무것도 지우지 않는다. 영구 삭제가 도중에 실패하면 전부 되돌아가고, 성공하면 남지 않는다 (다른 프로젝트는 그대로)
        Account admin = admin();
        Project target = soleProject("ai1c-hdel-prj", admin);
        AccountDto owner = SecurityTestSupport.loginAs(projectRepository, target.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        Long gone = connectionApi.create(owner, request("지울 연결", "POSTGRESQL", "gone")).getBody().id();
        ExecutionContext context = ExecutionContext.user(owner);
        for (SourceCollectionSpec.Result result : List.of(base(), changed())) {
            collections.apply(context, collections.start(context, gone, SourceCollectionSpec.Request.full()).jobId(), result);
        }
        Map<String, Integer> goneBefore = allCounts(gone);
        tx.executeWithoutResult(s -> projectRepository.findById(target.getId()).orElseThrow().setIsActive(false));
        assertThat(allCounts(gone)).isEqualTo(goneBefore);
        tx.executeWithoutResult(s -> projectRepository.findById(target.getId()).orElseThrow().setIsActive(true));
        String block = "update TB_SRC_CNSTRT_COL set SRC_COL_SN = ? where COL_ORD = 1 and SRC_CNSTRT_SN = (select min(SRC_CNSTRT_SN) from TB_SRC_CNSTRT where PRJ_SN = ?)";
        Long goneColumn = jdbc.queryForObject("select min(c.SRC_COL_SN) from TB_SRC_COL c join TB_SRC_TBL t on t.SRC_TBL_SN = c.SRC_TBL_SN where t.SRC_CNTN_SN = ?", Long.class, gone);
        Long theirColumn = jdbc.queryForObject("select c.SRC_COL_SN from TB_SRC_CNSTRT_COL c where c.COL_ORD = 1 and c.SRC_CNSTRT_SN = (select min(SRC_CNSTRT_SN) from TB_SRC_CNSTRT where PRJ_SN = ?)",
                Long.class, e.project.getId());
        assertThat(jdbc.update(block, goneColumn, e.project.getId())).isEqualTo(1);
        assertThatThrownBy(() -> projectManageService.deleteProject(target.getId(), owner)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(projectRepository.findById(target.getId())).isPresent();
        assertThat(allCounts(gone)).isEqualTo(goneBefore);
        jdbc.update(block, theirColumn, e.project.getId());
        Map<String, Integer> otherProject = allCounts(other);
        projectManageService.deleteProject(target.getId(), owner);
        assertNothingLeft(gone);
        for (String table : HISTORY_TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, target.getId())).as(table).isZero();
        }
        assertThat(allCounts(other)).isEqualTo(otherProject);
    }

    // ------------------------------------------------------------------ 권한 · 격리

    @Test
    @DisplayName("권한·격리 : 수집은 프로젝트 관리자·ADMIN(또는 정기 배치)만, 실행 중 권한 회수·프로젝트 비활성화는 반영 전에 막힌다. 다른 프로젝트·다른 요청자는 실행·이력·과거 상태를 찾을 수 없다")
    void accessAndIsolation() {
        Env e = env("ai1c-acl");
        // VIEWER·관리자 아닌 USER 는 등록할 수 없다
        assertThatThrownBy(() -> collections.start(ExecutionContext.user(e.viewerDto), e.connection, SourceCollectionSpec.Request.full()))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> collections.start(ExecutionContext.user(e.memberDto), e.connection, SourceCollectionSpec.Request.full()))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        // 정기 배치 : 작업 ID 로 같은 실행을 다시 찾는다 (등록을 다시 보내면 같은 실행)
        ExecutionContext nightly = ExecutionContext.scheduled(e.project.getId(), "nightly-" + UUID.randomUUID());
        SourceCollectionDto.Execution scheduled = collections.start(nightly, e.connection, SourceCollectionSpec.Request.full());
        assertThat(collections.start(nightly, e.connection, SourceCollectionSpec.Request.full()).id()).isEqualTo(scheduled.id());
        // 다른 요청자·다른 작업은 그 실행을 반영할 수 없다
        assertThatThrownBy(() -> collections.apply(e.manager(), scheduled.jobId(), base())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        assertThatThrownBy(() -> collections.apply(ExecutionContext.scheduled(e.project.getId(), "other"), scheduled.jobId(), base()))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        assertThat(collections.apply(nightly, scheduled.jobId(), base()).status()).isEqualTo("COMPLETED");

        // 실행 중 권한 회수 : 관리자에서 내려가면(다른 사람이 관리자가 된다) 반영 직전 확인에서 막힌다
        SourceCollectionDto.Execution revoked = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        tx.executeWithoutResult(s -> projectRepository.findById(e.project.getId()).orElseThrow().setAccount(accountRepository.findById(e.memberAccount.getId()).orElseThrow()));
        Map<String, List<Map<String, Object>>> before = catalogRows(e.connection);
        assertThatThrownBy(() -> collections.apply(e.manager(), revoked.jobId(), changed())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThat(snapshots.execution(e.managerDto, revoked.id()).execution()).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode)
                .containsExactly("FAILED", "EXECUTION_PERMISSION_DENIED");
        tx.executeWithoutResult(s -> projectRepository.findById(e.project.getId()).orElseThrow().setAccount(accountRepository.findById(e.managerAccount.getId()).orElseThrow()));
        // 실행 중 프로젝트 비활성화
        SourceCollectionDto.Execution inactive = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        tx.executeWithoutResult(s -> projectRepository.findById(e.project.getId()).orElseThrow().setIsActive(false));
        assertThatThrownBy(() -> collections.apply(e.manager(), inactive.jobId(), changed())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(snapshots.execution(e.managerDto, inactive.id()).execution().status()).isEqualTo("FAILED");
        assertThat(catalogRows(e.connection)).isEqualTo(before);
        tx.executeWithoutResult(s -> projectRepository.findById(e.project.getId()).orElseThrow().setIsActive(true));

        // 다른 프로젝트 : 실행·이력·과거 상태를 찾을 수 없다. 다른 프로젝트의 작업 ID 로 반영할 수 없다
        Env other = env("ai1c-acl2");
        Long table = tableId(e, "public", "users");
        assertThatThrownBy(() -> snapshots.execution(other.managerDto, scheduled.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        assertThatThrownBy(() -> snapshots.changes(other.managerDto, scheduled.id(), 0, 10)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        assertThatThrownBy(() -> snapshots.executions(other.managerDto, e.connection, 0, 10)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> snapshots.catalogAt(other.managerDto, e.connection, null, null)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> snapshots.tableAt(other.managerDto, table, null, null)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> snapshots.tableVersions(other.managerDto, table)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        SourceCollectionDto.Execution mine = collections.start(e.manager(), e.connection, SourceCollectionSpec.Request.full());
        assertThatThrownBy(() -> collections.apply(other.manager(), mine.jobId(), base())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        assertThatThrownBy(() -> collections.start(other.manager(), e.connection, SourceCollectionSpec.Request.full()))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        collections.cancel(e.manager(), mine.jobId());

        // 다른 연결의 버전을 섞을 수 없다 (실행·버전은 같은 연결끼리만 : 복합 FK)
        assertThatThrownBy(() -> jdbc.update("insert into TB_SRC_TBL_VER(SRC_TBL_VER_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, SRC_TBL_SN, SRC_CLCT_EXCN_SN, RFLT_ORD, PATH_CN, CMPR_NM,"
                        + " CRT_CYCL, CHG_TYPE_CD, HASH_VL, FRMT_VER_NO, TBL_CN) values(-9201, ?, ?, ?, ?, ?, 99, 'x', 'x', 1, 'BASE', 'x', 1, '{}')",
                e.project.getId(), other.connection, schemaId(e, "public"), table, scheduled.id())).isInstanceOf(DataIntegrityViolationException.class);

        // 조회 API : VIEWER 이상, 로그인한 프로젝트만
        SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.viewerAccount.getId()), "ROLE_VIEWER");
        assertThat(collectionApi.execution(e.viewerDto, scheduled.id()).getBody().execution().status()).isEqualTo("COMPLETED");
        SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.viewerAccount.getId()), "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> collectionApi.execution(e.viewerDto, scheduled.id())).isInstanceOf(AccessDeniedException.class);
        // 실행 행에 접속 정보·비밀번호가 없다
        assertThat(jdbc.queryForList("select coalesce(ERR_CN, '') || coalesce(ERR_CD, '') from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = ?", String.class, e.connection))
                .noneMatch(text -> text.contains("password") || text.contains("secret") || text.contains("db.internal"));
    }

    @Test
    @DisplayName("한 건 단위 경로(AI-1b SourceCatalogWriter)로 고친 테이블은 해시가 지워져 다음 수집이 현재 행을 다시 비교해 복구한다. 원본은 그대로이므로 버전·이력은 없다")
    void writerInvalidatesHash() {
        Env e = env("ai1c-wr");
        collect(e, SourceCollectionSpec.Request.full(), base());
        Long users = tableId(e, "public", "users");
        writer.saveTable(e.project.getId(), schemaId(e, "public"), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "손으로 고친 COMMENT"));
        assertThat(jdbc.queryForObject("select HASH_VL from TB_SRC_TBL where SRC_TBL_SN = ?", String.class, users)).isNull();
        SourceCollectionDto.Execution next = collect(e, SourceCollectionSpec.Request.full(), base());
        // 수집 결과가 원래 COMMENT 이므로 현재 행만 되돌아간다. 원본 변경은 마지막 버전과 비교하므로 버전·이력이 없다
        assertThat(jdbc.queryForObject("select ANTN_CN from TB_SRC_TBL where SRC_TBL_SN = ?", String.class, users)).isEqualTo("사용자");
        assertThat(jdbc.queryForObject("select HASH_VL from TB_SRC_TBL where SRC_TBL_SN = ?", String.class, users)).startsWith("v1:");
        assertThat(next).extracting(SourceCollectionDto.Execution::versionCount, SourceCollectionDto.Execution::changeCount).containsExactly(0, 0);
    }

    // ------------------------------------------------------------------ 수집 결과 예제

    /** public(users·orders), sales(v_orders 뷰), audit(빈 스키마) : 테이블 3, 컬럼 10 */
    private static SourceCollectionSpec.Result base() {
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        schemas.put("public", List.of(users(), orders(List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")),
                new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))), null)));
        schemas.put("sales", List.of(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("v_orders"), SourceTableType.VIEW, "주문 뷰"),
                List.of(column("id", 1, "bigint", true)), List.of(), List.of(), List.of())));
        schemas.put("audit", List.of());
        return result(schemas);
    }

    /** base 에서 users 의 COMMENT 만 바꾼 것 */
    private static SourceCollectionSpec.Result changed() {
        Map<String, List<SourceCollectionSpec.Table>> schemas = new LinkedHashMap<>();
        SourceCollectionSpec.Table users = users();
        schemas.put("public", List.of(new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "바뀐 COMMENT"), users.columns(),
                users.constraints(), users.indexes(), users.relations()), orders(List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")),
                new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))), null)));
        schemas.put("sales", base().schemas().get(1).tables());
        schemas.put("audit", List.of());
        return result(schemas);
    }

    private static SourceCollectionSpec.Table users() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "사용자"),
                List.of(column("tenant_id", 1, "bigint", false), column("id", 2, "bigint", false),
                        new SourceCatalogSpec.Column(Name.of("email"), 3, "character varying(320)", 320, null, null, true, null, "이메일"),
                        new SourceCatalogSpec.Column(Name.of("name"), 4, "numeric(12,2)", null, new BigDecimal("12"), 2, true, "0", null),
                        column("created_at", 5, "timestamp", false)),
                List.of(pk("users_pkey", "tenant_id", "id")),
                List.of(new SourceCatalogSpec.Index("ix_users_tenant_email", "btree", false, List.of(Name.of("tenant_id"), Name.of("email")))), List.of());
    }

    private static SourceCollectionSpec.Table orders(List<SourceCatalogSpec.ColumnPair> fk, String updateRule) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null),
                List.of(column("tenant_id", 1, "bigint", false), column("id", 2, "bigint", false), column("user_id", 3, "bigint", false), column("status", 4, "bigint", true)),
                List.of(pk("orders_pkey", "tenant_id", "id")), List.of(),
                List.of(new SourceCatalogSpec.Relation("fk_orders_users", null, null, Name.of("users"), "CASCADE", updateRule, fk)));
    }

    private static SourceCollectionSpec.Table simple(String name, String column) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), SourceTableType.TABLE, null), List.of(column(column, 1, "bigint", false)),
                List.of(pk(name + "_pkey", column)), List.of(), List.of());
    }

    private static SourceCollectionSpec.Result result(Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(Name.of("app"), Name.of(schema), tables)));
        return new SourceCollectionSpec.Result(result);
    }

    private static SourceCatalogSpec.Column column(String name, int ordinal, String type, boolean nullable) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, type, null, null, null, nullable, null, null);
    }

    private static SourceCatalogSpec.Constraint pk(String name, String... columns) {
        return new SourceCatalogSpec.Constraint(name, SourceConstraintType.PRIMARY_KEY, null, java.util.Arrays.stream(columns).map(Name::of).toList());
    }

    private static Namespace ns(String schema) {
        return new Namespace(Name.of("app"), Name.of(schema));
    }

    // ------------------------------------------------------------------ 준비 · 확인

    private record Env(Project project, Long connection, Account managerAccount, Account memberAccount, Account viewerAccount, AccountDto managerDto, AccountDto memberDto,
                       AccountDto viewerDto) {
        ExecutionContext manager() {
            return ExecutionContext.user(managerDto);
        }
    }

    /** 프로젝트 하나 : 관리자(DBA 역할의 프로젝트 관리자), 멤버(USER), 조회 사용자(VIEWER), PostgreSQL 연결 하나 */
    private Env env(String name) {
        Account manager = account("mgr", "ROLE_DBA");
        Account member = account("usr", "ROLE_USER");
        Account viewer = account("vwr", "ROLE_VIEWER");
        Project project = projectRepository.save(Project.builder().projectName(unique(name)).isActive(true).account(manager).dbType(DbType.POSTGRESQL).build());
        for (Account account : List.of(manager, member, viewer)) projectMemberRepository.save(ProjectMember.builder().project(project).account(account).isActive(true).build());
        AccountDto managerDto = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA");
        Long connection = connectionApi.create(managerDto, request("운영 DB", "POSTGRESQL", "app")).getBody().id();
        return new Env(project, connection, manager, member, viewer, managerDto, dto(member, project), dto(viewer, project));
    }

    private SourceCollectionDto.Execution collect(Env e, SourceCollectionSpec.Request request, SourceCollectionSpec.Result result) {
        SourceCollectionDto.Execution started = collections.start(e.manager(), e.connection, request);
        return collections.apply(e.manager(), started.jobId(), result);
    }

    private static AccountDto dto(Account account, Project project) {
        return AccountDto.builder().id(String.valueOf(account.getId())).username(account.getUsername()).name(account.getName()).projectId(project.getId()).build();
    }

    private Project soleProject(String projectName, Account manager) {
        Project project = projectRepository.save(Project.builder().projectName(unique(projectName)).isActive(true).account(manager).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(manager).isActive(true).build());
        return project;
    }

    private Account admin() {
        return accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
    }

    private Account account(String prefix, String role) {
        String username = unique(prefix);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private static SourceConnectionDto.Request request(String name, String dbType, String databaseName) {
        return new SourceConnectionDto.Request(name, dbType, "db.internal", null, databaseName, null, "reader", null, null, null, null, null);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Long insertRunning(Env e, String serverId, LocalDateTime at, Long afterId) {
        long id = -(afterId + 1000);
        jdbc.update("insert into TB_SRC_CLCT_EXCN(SRC_CLCT_EXCN_SN, PRJ_SN, SRC_CNTN_SN, JOB_ID, EXCN_TYPE_CD, ACNT_SN, CLCT_TYPE_CD, EXCN_STTS_CD, PRGRS_CNTN_SN, EXCN_SRVR_ID,"
                        + " BGNG_DT, LAST_IDNTY_DT) values(?, ?, ?, ?, 'USER', ?, 'FULL', 'RUNNING', ?, ?, ?, ?)",
                id, e.project.getId(), e.connection, UUID.randomUUID().toString(), e.managerAccount.getId(), e.connection, serverId, Timestamp.valueOf(at), Timestamp.valueOf(at));
        return id;
    }

    private Long schemaId(Env e, String schema) {
        return jdbc.queryForObject("select SRC_SCHM_SN from TB_SRC_SCHM where SRC_CNTN_SN = ? and SCHM_NM = ?", Long.class, e.connection, schema);
    }

    private Long tableId(Env e, String schema, String table) {
        return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = ? and t.CMPR_NM = ?",
                Long.class, e.connection, schema, table);
    }

    private int count(String table, Long connection) {
        return jdbc.queryForObject("select count(*) from " + table + " where SRC_CNTN_SN = ?", Integer.class, connection);
    }

    /** 연결의 수집 이력 5개 테이블과 현재 카탈로그 행 수 */
    private Map<String, Integer> allCounts(Long connection) {
        Map<String, Integer> counts = new TreeMap<>(historyCounts(connection));
        counts.put("TB_SRC_CLCT_EXCN", count("TB_SRC_CLCT_EXCN", connection));
        counts.put("TB_SRC_CLCT_TRGT", jdbc.queryForObject("select count(*) from TB_SRC_CLCT_TRGT t join TB_SRC_CLCT_EXCN e on e.SRC_CLCT_EXCN_SN = t.SRC_CLCT_EXCN_SN"
                + " where e.SRC_CNTN_SN = ?", Integer.class, connection));
        catalogRows(connection).forEach((table, rows) -> counts.put(table, rows.size()));
        return counts;
    }

    private Map<String, Integer> historyCounts(Long connection) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String table : List.of("TB_SRC_SCHM_VER", "TB_SRC_TBL_VER", "TB_SRC_CHG_HSTRY")) counts.put(table, count(table, connection));
        return counts;
    }

    /** 이 반영 순서에서 생긴 테이블 버전 : 테이블 비교 명 → 변경 유형 */
    private Map<String, String> versionTypes(Long connection, int order) {
        Map<String, String> types = new TreeMap<>();
        jdbc.query("select CMPR_NM, CHG_TYPE_CD from TB_SRC_TBL_VER where SRC_CNTN_SN = ? and RFLT_ORD = ?", rs -> {
            types.put(rs.getString(1), rs.getString(2));
        }, connection, order);
        return types;
    }

    /** 대상유형:이름:변경유형 → 바뀐 항목 */
    private static Map<String, String> changes(List<SourceCollectionDto.Change> changes) {
        Map<String, String> result = new TreeMap<>();
        for (SourceCollectionDto.Change change : changes) result.put(change.targetType() + ":" + change.targetName() + ":" + change.changeType(), change.changedFields().toString());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> columnsOf(Object content) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) content).get("columns")).stream().map(column -> (String) column.get("compareName")).toList();
    }

    private static List<String> names(List<SourceCollectionDto.TableState> tables) {
        return tables.stream().map(SourceCollectionDto.TableState::name).toList();
    }

    /** 연결의 현재 카탈로그 9개 테이블의 모든 행 (수정 일시·해시 포함) */
    private Map<String, List<Map<String, Object>>> catalogRows(Long connection) {
        String tables = "(select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = " + connection + ")";
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("TB_SRC_SCHM", "select * from TB_SRC_SCHM where SRC_CNTN_SN = " + connection + " order by SRC_SCHM_SN");
        queries.put("TB_SRC_TBL", "select * from TB_SRC_TBL where SRC_CNTN_SN = " + connection + " order by SRC_TBL_SN");
        queries.put("TB_SRC_COL", "select * from TB_SRC_COL where SRC_TBL_SN in " + tables + " order by SRC_COL_SN");
        queries.put("TB_SRC_CNSTRT", "select * from TB_SRC_CNSTRT where SRC_TBL_SN in " + tables + " order by SRC_CNSTRT_SN");
        queries.put("TB_SRC_CNSTRT_COL", "select * from TB_SRC_CNSTRT_COL where SRC_CNSTRT_SN in (select SRC_CNSTRT_SN from TB_SRC_CNSTRT where SRC_TBL_SN in " + tables + ") order by 1");
        queries.put("TB_SRC_IDX", "select * from TB_SRC_IDX where SRC_TBL_SN in " + tables + " order by SRC_IDX_SN");
        queries.put("TB_SRC_IDX_COL", "select * from TB_SRC_IDX_COL where SRC_IDX_SN in (select SRC_IDX_SN from TB_SRC_IDX where SRC_TBL_SN in " + tables + ") order by 1");
        queries.put("TB_SRC_REL", "select * from TB_SRC_REL where SRC_CNTN_SN = " + connection + " order by SRC_REL_SN");
        queries.put("TB_SRC_REL_COL", "select * from TB_SRC_REL_COL where SRC_REL_SN in (select SRC_REL_SN from TB_SRC_REL where SRC_CNTN_SN = " + connection + ") order by 1");
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        queries.forEach((table, sql) -> rows.put(table, jdbc.queryForList(sql)));
        return rows;
    }

    /** public 스키마의 테이블 행 (부분 수집이 범위 밖을 건드리지 않았는지) */
    private List<Map<String, Object>> publicRows(Map<String, List<Map<String, Object>>> rows, Env e) {
        Long publicId = schemaId(e, "public");
        return rows.get("TB_SRC_TBL").stream().filter(row -> publicId.equals(((Number) value(row, "SRC_SCHM_SN")).longValue())).toList();
    }

    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(column)) return entry.getValue();
        return null;
    }

    private void assertNothingLeft(Long connection) {
        for (String table : List.of("TB_SRC_CLCT_EXCN", "TB_SRC_SCHM_VER", "TB_SRC_TBL_VER", "TB_SRC_CHG_HSTRY", "TB_SRC_SCHM", "TB_SRC_TBL", "TB_SRC_REL")) {
            assertThat(count(table, connection)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CNTN where SRC_CNTN_SN = ?", Integer.class, connection)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CLCT_TRGT t where not exists (select 1 from TB_SRC_CLCT_EXCN e where e.SRC_CLCT_EXCN_SN = t.SRC_CLCT_EXCN_SN)",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_COL c where not exists (select 1 from TB_SRC_TBL t where t.SRC_TBL_SN = c.SRC_TBL_SN)", Integer.class)).isZero();
    }

    /**
     * waiter 가 다른 트랜잭션의 잠금을 기다리기 시작할 때까지 기다린다 (DB 가 알려 주는 잠금 대기 : PostgreSQL pg_stat_activity, H2 SESSIONS.BLOCKER_ID).
     * 기다리는 문장을 돌려준다 : 어느 행(테이블)의 잠금에서 막혔는지로 잠금 순서를 확인한다. 잠금을 기다리지 않고 끝나면 실패한다 (경합이 일어나지 않았다)
     */
    private String awaitLockWait(Future<?> waiter) throws InterruptedException {
        String sql = postgres()
                ? "select query from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select EXECUTING_STATEMENT from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            assertThat(waiter.isDone()).as("잠금을 기다리지 않고 끝났다 (경합이 일어나지 않았다)").isFalse();
            List<String> waiting = jdbc.queryForList(sql, String.class);
            if (!waiting.isEmpty()) return String.join("\n", waiting);
            Thread.sleep(5);
        }
        throw new AssertionError("잠금 대기가 생기지 않았다");
    }

    private boolean postgres() {
        return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres")));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 예외도 결과로 돌려준다 (다른 스레드의 결과를 비교하기 쉽게) */
    private static Callable<Object> call(Callable<Object> body) {
        return () -> {
            try {
                return body.call();
            } catch (Throwable e) {
                return e;
            }
        };
    }

    private static Consumer<CustomException> code(ErrorCode expected) {
        return e -> assertThat(e.getErrorCode()).isEqualTo(expected);
    }
}
