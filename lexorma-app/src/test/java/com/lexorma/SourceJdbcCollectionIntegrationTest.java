package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.source.catalog.domain.dto.SourceCatalogDto;
import com.lexorma.source.catalog.service.SourceCatalogService;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.connection.jdbc.SourceGuardedSocketFactory;
import com.lexorma.source.connection.jdbc.SourceJdbcSettings;
import com.lexorma.source.tool.SourceMetadataTool;
import com.lexorma.source.tool.SourceToolContext;
import com.lexorma.source.tool.SourceToolExecutor;
import com.lexorma.source.tool.SourceToolSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * AI-2b : 실제 PostgreSQL(원본) → JDBC 수집 도구 7개 → AI-1c 적재·버전·이력. 원본 DB 는 테스트마다 새로 만든다 ({@link SourceJdbcTestSupport}).
 * 테스트 전용으로 루프백(127.0.0.0/8, ::1)을 허용한다 (운영 기본값은 막혀 있다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1",
                "lexorma.source.allowed-networks=127.0.0.0/8,::1/128", "lexorma.source.jdbc.query-timeout-seconds=3",
                "lexorma.source.collection.heartbeat-interval=PT0.2S", "lexorma.source.connection-test.per-minute=1000"})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SourceJdbcCollectionIntegrationTest extends SourceJdbcTestSupport {

    /** 지원 대상 객체와 경계 사례 : 복합 PK·UNIQUE·CHECK·복합/다른 스키마 FK·표현식/부분/DESC/INCLUDE 인덱스·생성 컬럼·Identity·한글/따옴표 식별자·같은 이름의 다른 스키마 테이블 */
    private static final String[] MODEL = {
            "create schema app",
            "create schema audit",
            "create schema empty_one",
            """
            create table app.customer (
              id bigint generated always as identity primary key,
              email varchar(100) not null,
              name text,
              balance numeric(10,2) not null default 0,
              code char(4),
              active boolean default true,
              full_name text generated always as (name || '!') stored,
              created_at timestamp without time zone default now(),
              constraint ck_customer_balance check (balance >= 0))""",
            "comment on table app.customer is '고객'",
            "comment on column app.customer.email is '이메일 주소'",
            "create table app.\"Customer\" (id int primary key)",
            "create table app.\"고객정보\" (\"고객번호\" int primary key, \"이름\" varchar(30))",
            """
            create table app.orders (
              customer_id bigint not null, order_no int not null, line_no int not null, amount numeric(12,3), serial_id serial,
              constraint pk_orders primary key (customer_id, order_no),
              constraint uk_orders_line unique (order_no, line_no),
              constraint fk_orders_customer foreign key (customer_id) references app.customer (id) on delete cascade on update restrict)""",
            "create table audit.customer (id int primary key, note text)",
            "create table audit.order_log (customer_id bigint, order_no int, logged_at timestamptz,"
                    + " constraint fk_log_orders foreign key (customer_id, order_no) references app.orders (customer_id, order_no))",
            "create index ix_customer_email_lower on app.customer (lower(email))",
            "create index ix_customer_active on app.customer (created_at desc) where active = true",
            "create index ix_customer_mixed on app.customer (name, lower(email) desc nulls last) include (code)",
            "create index ix_orders_amount on app.orders (amount nulls first)",
            "create view app.v_customer as select id, email from app.customer",
            "comment on view app.v_customer is '고객 뷰'",
            "create materialized view app.mv_customer as select id from app.customer",
            "create table app.events (id int, at date not null) partition by range (at)",
            "create table app.events_2026 partition of app.events for values from ('2026-01-01') to ('2027-01-01')",
            "create sequence app.seq_free",
            "create temporary table tmp_ignored (id int)"};

    @Autowired SourceCatalogService catalog;
    @Autowired SourceToolExecutor tools;

    // ------------------------------------------------------------------ 수집 내용

    @Test
    @DisplayName("FULL 최초 수집 : 비시스템 스키마 전체, 테이블·파티션 부모·뷰만(파티션 자식·구체화 뷰·시퀀스·임시 테이블 제외), 원본 식별자 그대로. 같은 내용 재수집은 버전·이력 0건")
    void fullCollection() throws Exception {
        SourceDb db = source(MODEL);
        Env e = env(db);
        SourceCollectionDto.Execution first = collect(e, "FULL");
        assertThat(first).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::reflectedOrder, SourceCollectionDto.Execution::collectionType,
                SourceCollectionDto.Execution::executionType, SourceCollectionDto.Execution::changeCount).containsExactly("COMPLETED", 1, "FULL", "ASYNC", 0);
        // public(기본 스키마)·빈 스키마도 대상이다. pg_catalog·information_schema·pg_toast·pg_temp 는 없다
        assertThat(snapshots.execution(e.managerDto(), first.id()).targets()).extracting(SourceCollectionDto.Target::schemaName, SourceCollectionDto.Target::result,
                SourceCollectionDto.Target::tableCount).containsExactlyInAnyOrder(tuple("app", "COMPLETED", 6), tuple("audit", "COMPLETED", 2),
                tuple("empty_one", "COMPLETED", 0), tuple("public", "COMPLETED", 0));
        assertThat(tables(e, "app")).containsExactlyInAnyOrder("Customer:TABLE", "customer:TABLE", "events:TABLE", "orders:TABLE", "v_customer:VIEW", "고객정보:TABLE");
        assertThat(tables(e, "audit")).containsExactlyInAnyOrder("customer:TABLE", "order_log:TABLE");
        assertThat(first.tableCount()).isEqualTo(8);
        assertThat(first.versionCount()).as("스키마 4 + 테이블 8 의 BASE").isEqualTo(12);

        // 컬럼 : 원본 타입 표현, 길이·정밀도·소수 자릿수, NULL, 기본값·Identity·생성 컬럼, COMMENT
        SourceCatalogDto.TableDetail customer = detail(e, "app", "customer");
        assertThat(customer.table().comment()).isEqualTo("고객");
        assertThat(customer.columns()).extracting(SourceCatalogDto.Column::name, SourceCatalogDto.Column::ordinal, SourceCatalogDto.Column::dataType,
                SourceCatalogDto.Column::maxLength, SourceCatalogDto.Column::nullable).containsExactly(
                tuple("id", 1, "bigint", null, false), tuple("email", 2, "character varying(100)", 100, false), tuple("name", 3, "text", null, true),
                tuple("balance", 4, "numeric(10,2)", null, false), tuple("code", 5, "character(4)", 4, true), tuple("active", 6, "boolean", null, true),
                tuple("full_name", 7, "text", null, true), tuple("created_at", 8, "timestamp without time zone", null, true));
        Map<String, SourceCatalogDto.Column> columns = byName(customer.columns());
        assertThat(columns.get("balance").precision()).isEqualByComparingTo("10");
        assertThat(columns.get("balance").scale()).isEqualTo(2);
        assertThat(columns.get("balance").defaultValue()).isEqualTo("0");
        assertThat(columns.get("id").defaultValue()).isEqualTo("GENERATED ALWAYS AS IDENTITY");
        assertThat(columns.get("full_name").defaultValue()).startsWith("GENERATED ALWAYS AS (").endsWith(") STORED").contains("name");
        assertThat(columns.get("created_at").defaultValue()).isEqualTo("now()");
        assertThat(columns.get("email").comment()).isEqualTo("이메일 주소");
        assertThat(byName(detail(e, "app", "orders").columns()).get("serial_id").defaultValue()).isEqualTo("nextval('app.orders_serial_id_seq'::regclass)");

        // 제약조건 : PK·복합 PK·복합 UNIQUE·CHECK 식 (NOT NULL 은 컬럼의 nullable 로만)
        assertThat(customer.constraints()).extracting(SourceCatalogDto.Constraint::name, SourceCatalogDto.Constraint::type, SourceCatalogDto.Constraint::columns)
                .containsExactlyInAnyOrder(tuple("customer_pkey", "PRIMARY_KEY", List.of("id")), tuple("ck_customer_balance", "CHECK", List.of("balance")));
        assertThat(customer.constraints()).filteredOn(c -> c.type().equals("CHECK")).singleElement().extracting(SourceCatalogDto.Constraint::checkExpression)
                .isEqualTo("(balance >= (0)::numeric)");
        SourceCatalogDto.TableDetail orders = detail(e, "app", "orders");
        assertThat(orders.constraints()).extracting(SourceCatalogDto.Constraint::name, SourceCatalogDto.Constraint::type, SourceCatalogDto.Constraint::columns)
                .containsExactlyInAnyOrder(tuple("pk_orders", "PRIMARY_KEY", List.of("customer_id", "order_no")), tuple("uk_orders_line", "UNIQUE", List.of("order_no", "line_no")));

        // 인덱스 : 표현식·부분·DESC·NULLS·INCLUDE·혼합, 방식 (PK·UNIQUE 의 인덱스도 원본에 있는 인덱스다)
        Map<String, SourceCatalogDto.Index> indexes = new java.util.HashMap<>();
        customer.indexes().forEach(index -> indexes.put(index.name(), index));
        assertThat(indexes).containsOnlyKeys("customer_pkey", "ix_customer_email_lower", "ix_customer_active", "ix_customer_mixed");
        assertThat(indexes.get("ix_customer_email_lower")).extracting(SourceCatalogDto.Index::indexType, SourceCatalogDto.Index::unique, SourceCatalogDto.Index::columns,
                SourceCatalogDto.Index::predicate).containsExactly("btree", false, List.of(), null);
        assertThat(indexes.get("ix_customer_email_lower").members()).containsExactly(new SourceCatalogDto.IndexMember(null, "lower((email)::text)", null));
        assertThat(indexes.get("ix_customer_active").members()).containsExactly(new SourceCatalogDto.IndexMember("created_at", null, "DESC"));
        assertThat(indexes.get("ix_customer_active").predicate()).isEqualTo("(active = true)");
        assertThat(indexes.get("ix_customer_mixed").members()).containsExactly(new SourceCatalogDto.IndexMember("name", null, null),
                new SourceCatalogDto.IndexMember(null, "lower((email)::text)", "DESC NULLS LAST"), new SourceCatalogDto.IndexMember("code", null, "INCLUDE"));
        assertThat(orders.indexes()).filteredOn(index -> index.name().equals("ix_orders_amount")).singleElement().extracting(SourceCatalogDto.Index::members)
                .isEqualTo(List.of(new SourceCatalogDto.IndexMember("amount", null, "NULLS FIRST")));

        // 관계 : 규칙, 복합 FK, 다른 스키마 참조 (참조 대상이 이어져 있다)
        assertThat(orders.relations()).singleElement().satisfies(fk -> {
            assertThat(fk).extracting(SourceCatalogDto.Relation::name, SourceCatalogDto.Relation::referencedSchemaName, SourceCatalogDto.Relation::referencedTableName,
                    SourceCatalogDto.Relation::deleteRule, SourceCatalogDto.Relation::updateRule).containsExactly("fk_orders_customer", "app", "customer", "CASCADE", "RESTRICT");
            assertThat(fk.referencedTableId()).isEqualTo(customer.table().id());
        });
        SourceCatalogDto.TableDetail log = detail(e, "audit", "order_log");
        assertThat(log.relations()).singleElement().satisfies(fk -> {
            assertThat(fk).extracting(SourceCatalogDto.Relation::referencedSchemaName, SourceCatalogDto.Relation::referencedTableName, SourceCatalogDto.Relation::deleteRule)
                    .containsExactly("app", "orders", "NO ACTION");
            assertThat(fk.columns()).extracting(SourceCatalogDto.ColumnPair::columnName, SourceCatalogDto.ColumnPair::referencedColumnName)
                    .containsExactly(tuple("customer_id", "customer_id"), tuple("order_no", "order_no"));
            assertThat(fk.referencedTableId()).isEqualTo(orders.table().id());
        });
        // 한글·따옴표 식별자 : 카탈로그 이름 그대로 (접지 않는다), 같은 이름의 다른 스키마 테이블은 다른 객체
        assertThat(detail(e, "app", "고객정보").columns()).extracting(SourceCatalogDto.Column::name).containsExactly("고객번호", "이름");
        assertThat(detail(e, "app", "Customer").table().id()).isNotEqualTo(customer.table().id()).isNotEqualTo(detail(e, "audit", "customer").table().id());
        assertThat(detail(e, "app", "v_customer")).satisfies(view -> {
            assertThat(view.table().comment()).isEqualTo("고객 뷰");
            assertThat(view.columns()).extracting(SourceCatalogDto.Column::name).containsExactly("id", "email");
        });

        // 같은 내용 다시 : 실행만 남고 버전·이력 0건, 현재 카탈로그 그대로
        int rows = catalogRows(e.connection());
        SourceCollectionDto.Execution again = collect(e, "FULL");
        assertThat(again).extracting(SourceCollectionDto.Execution::reflectedOrder, SourceCollectionDto.Execution::versionCount, SourceCollectionDto.Execution::changeCount)
                .containsExactly(2, 0, 0);
        assertThat(catalogRows(e.connection())).isEqualTo(rows);
        assertThat(collectorSessions(db)).as("수집이 끝나면 원본 연결이 닫혀 있다").isZero();
    }

    @Test
    @DisplayName("변경 감지 : 테이블·컬럼 추가, 타입·COMMENT·인덱스·FK 변경, 테이블 삭제 → 바뀐 테이블만 새 버전·이력. 삭제 뒤 같은 이름이 다시 생기면 생성 차수 + 1. 과거 버전은 그대로")
    void changeDetection() throws Exception {
        SourceDb db = source(MODEL);
        Env e = env(db);
        collect(e, "FULL");
        Long customerId = detail(e, "app", "customer").table().id();
        Object before = snapshots.tableAt(e.managerDto(), customerId, 1, null).content();

        db.jdbc().execute("create table app.added (id int primary key)");
        db.jdbc().execute("alter table app.customer add column phone varchar(20)");
        db.jdbc().execute("alter table app.customer alter column code type varchar(8)");
        db.jdbc().execute("comment on column app.customer.email is '메일'");
        db.jdbc().execute("drop index app.ix_customer_active");
        db.jdbc().execute("create index ix_customer_active on app.customer (created_at) where active");
        db.jdbc().execute("alter table app.orders drop constraint fk_orders_customer");
        db.jdbc().execute("alter table app.orders add constraint fk_orders_customer foreign key (customer_id) references app.customer (id) on delete set null");
        db.jdbc().execute("drop table audit.customer");
        SourceCollectionDto.Execution changed = collect(e, "FULL");
        assertThat(changed.reflectedOrder()).isEqualTo(2);
        List<SourceCollectionDto.Change> history = snapshots.changes(e.managerDto(), changed.id(), 0, 200).getContent();
        assertThat(history).extracting(SourceCollectionDto.Change::targetType, SourceCollectionDto.Change::changeType, SourceCollectionDto.Change::targetName)
                .contains(tuple("TABLE", "ADDED", "added"), tuple("TABLE", "REMOVED", "customer"), tuple("COLUMN", "ADDED", "phone"), tuple("COLUMN", "MODIFIED", "code"),
                        tuple("COLUMN", "MODIFIED", "email"));
        assertThat(history).extracting(SourceCollectionDto.Change::targetType).contains("INDEX", "RELATION");
        // 바뀐 테이블 : app.customer, app.orders, app.added(생김), audit.customer(사라짐). 나머지는 버전이 없다
        assertThat(jdbc.queryForList("select t.CMPR_NM || ':' || v.CHG_TYPE_CD from TB_SRC_TBL_VER v join TB_SRC_TBL t on t.SRC_TBL_SN = v.SRC_TBL_SN"
                + " join TB_SRC_CLCT_EXCN x on x.SRC_CLCT_EXCN_SN = v.SRC_CLCT_EXCN_SN where x.SRC_CLCT_EXCN_SN = ?", String.class, changed.id()))
                .containsExactlyInAnyOrder("customer:MODIFIED", "orders:MODIFIED", "added:ADDED", "customer:REMOVED");
        assertThat(snapshots.tableAt(e.managerDto(), customerId, 1, null).content()).as("과거 버전은 그대로").isEqualTo(before);
        assertThat(byName(detail(e, "app", "customer").columns()).get("code").dataType()).isEqualTo("character varying(8)");

        // 삭제된 이름이 다시 생긴다 : 새 생명주기
        db.jdbc().execute("create table audit.customer (id int primary key, note text)");
        collect(e, "FULL");
        assertThat(jdbc.queryForObject("select t.CRT_CYCL from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = 'audit'"
                + " and t.CMPR_NM = 'customer'", Integer.class, e.connection())).isEqualTo(2);
    }

    @Test
    @DisplayName("PARTIAL : 지정한 스키마만 다시 읽고 범위 밖은 그대로. 없는 스키마는 NOT_FOUND 로 실행 전체가 FAILED (현재 카탈로그 변경 0건). 다른 스키마를 참조하는 FK 는 이름을 남긴다")
    void partialCollection() throws Exception {
        SourceDb db = source(MODEL);
        Env e = env(db);
        collect(e, "FULL");
        db.jdbc().execute("drop table audit.order_log");
        db.jdbc().execute("create table app.only_app (id int)");
        SourceCollectionDto.Execution partial = collect(e, "PARTIAL", "app");
        assertThat(tables(e, "app")).contains("only_app:TABLE");
        assertThat(tables(e, "audit")).as("범위 밖 스키마의 객체는 원본에서 지워졌어도 그대로").contains("order_log:TABLE");
        assertThat(snapshots.execution(e.managerDto(), partial.id()).targets()).extracting(SourceCollectionDto.Target::schemaName).containsExactly("app");

        int rows = catalogRows(e.connection());
        SourceCollectionDto.Execution missing = await(e, refresh(e, e.connection(), "PARTIAL", "app", "no_such").id());
        assertThat(missing).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode).containsExactly("FAILED", "SOURCE_COLLECTION_INCOMPLETE");
        assertThat(snapshots.execution(e.managerDto(), missing.id()).targets()).extracting(SourceCollectionDto.Target::schemaName, SourceCollectionDto.Target::result)
                .contains(tuple("no_such", "NOT_FOUND"));
        assertThat(catalogRows(e.connection())).isEqualTo(rows);
        // 대소문자도 원본 그대로 비교한다 (App ≠ app)
        assertThat(await(e, refresh(e, e.connection(), "PARTIAL", "App").id()).status()).isEqualTo("FAILED");

        // 빈 스키마는 테이블 0개로 완료
        assertThat(collect(e, "PARTIAL", "empty_one").tableCount()).isZero();

        // PARTIAL 로 audit 만 읽으면 app 을 참조하는 FK 의 대상은 기존 카탈로그에서 찾는다 (관계를 지어내거나 지우지 않는다)
        db.jdbc().execute("create table audit.order_log (customer_id bigint, order_no int, constraint fk_log_orders foreign key (customer_id, order_no)"
                + " references app.orders (customer_id, order_no))");
        collect(e, "PARTIAL", "audit");
        assertThat(detail(e, "audit", "order_log").relations()).singleElement().extracting(SourceCatalogDto.Relation::referencedTableName,
                SourceCatalogDto.Relation::referencedTableId).containsExactly("orders", detail(e, "app", "orders").table().id());
    }

    // ------------------------------------------------------------------ 세션 · 보안

    @Test
    @DisplayName("JDBC 세션 : 도구 여러 개가 연결 하나·읽기 전용 REPEATABLE READ 트랜잭션 하나를 쓴다. 쓰기는 원본이 거절하고, 끝나면 연결이 닫힌다. 조회 제한 시간이 실제로 적용된다")
    void readOnlySession() throws Exception {
        SourceDb db = source("create table public.t (id int)");
        Env e = env(db);
        ExecutionContext context = ExecutionContext.user(e.managerDto());
        AtomicReference<SourceToolSession> used = new AtomicReference<>();
        List<String> observed = tools.session(context, e.connection(), session -> {
            used.set(session);
            List<String> first = session.run(ProbeTool.NAME, "tx");
            List<String> second = session.run(ProbeTool.NAME, "tx");
            assertThat(first.get(3)).as("같은 트랜잭션 (같은 시작 시각·같은 백엔드)").isEqualTo(second.get(3));
            assertThat(first.get(2)).isEqualTo(second.get(2));
            assertThatThrownBy(() -> session.run(ProbeTool.NAME, "write")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_METADATA_FAILED));
            return first;
        });
        assertThat(observed.subList(0, 2)).containsExactly("on", "repeatable read");
        assertThat(used.get().statementCount()).isEqualTo(3);
        assertThat(used.get().isClosed()).isTrue();
        assertThat(db.jdbc().queryForObject("select count(*) from pg_class where relname = 'written'", Integer.class)).isZero();
        assertThat(collectorSessions(db)).isZero();

        // 조회 제한 시간 (테스트 설정 3초) : 원본 서버가 조회를 취소한다
        long started = System.nanoTime();
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, context, e.connection(), "sleep")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_QUERY_TIMEOUT));
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);

        // 다른 스레드에서 멈춘다 (취소·전체 제한 시간과 같은 경로) : 실행 중인 조회가 바로 끝나고 연결이 닫힌다
        CompletableFuture<Object> sleeping = CompletableFuture.supplyAsync(() -> tools.session(context, e.connection(), session -> {
            used.set(session);
            return session.run(ProbeTool.NAME, "sleep");
        }));
        awaitActive(db);
        used.get().abort(ErrorCode.SOURCE_CANCELLED);
        assertThatThrownBy(() -> sleeping.get(5, TimeUnit.SECONDS)).cause().isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CANCELLED));
        awaitCollectorGone(db);
    }

    @Test
    @DisplayName("연결 테스트 : 제품·버전·메타데이터 조회 가능·TLS 상태(검증 안 함을 보안 연결로 표시하지 않는다). 인증 실패·TLS 실패·권한 부족은 코드로 알리고 비밀번호는 응답·로그에 없다")
    void connectionTest(CapturedOutput output) throws Exception {
        SourceDb db = source();
        Env e = env(db);
        SourceConnectionDto.TestResult ok = connectionApi.test(e.managerDto(), e.connection()).getBody();
        assertThat(ok).extracting(SourceConnectionDto.TestResult::connected, SourceConnectionDto.TestResult::metadataReadable, SourceConnectionDto.TestResult::productName,
                SourceConnectionDto.TestResult::errorCode).containsExactly(true, true, "PostgreSQL", null);
        assertThat(ok.productVersion()).isNotBlank();
        assertThat(ok.tls()).extracting(t -> t.mode(), t -> t.certificateVerified(), t -> t.hostnameVerified()).containsExactly("prefer", false, false);

        // 저장 전 테스트 : 틀린 비밀번호 → 접속은 실패, 코드만
        String wrong = "Wr0ng-Secret-" + System.nanoTime();
        SourceConnectionDto.Request draft = new SourceConnectionDto.Request(null, "POSTGRESQL", db.host(), db.port(), db.name(), null, USER, wrong, null, null, null, null);
        SourceConnectionDto.TestResult auth = connectionApi.testDraft(e.managerDto(), null, draft).getBody();
        assertThat(auth).extracting(SourceConnectionDto.TestResult::connected, SourceConnectionDto.TestResult::errorCode).containsExactly(false, "SOURCE_AUTH_FAILED");
        assertThat(auth.toString()).doesNotContain(wrong).doesNotContain("jdbc:");
        // 수정 중 비밀번호를 비우면 저장된 것을 쓴다 — 접속 대상이 같을 때만 (다른 호스트로 저장된 비밀번호를 보내지 않는다)
        SourceConnectionDto.Request same = new SourceConnectionDto.Request(null, "POSTGRESQL", db.host(), db.port(), db.name(), null, USER, null, null, null, null, null);
        assertThat(connectionApi.testDraft(e.managerDto(), e.connection(), same).getBody().connected()).isTrue();
        SourceConnectionDto.Request moved = new SourceConnectionDto.Request(null, "POSTGRESQL", "127.0.0.2", db.port(), db.name(), null, USER, null, null, null, null, null);
        assertThatThrownBy(() -> connectionApi.testDraft(e.managerDto(), e.connection(), moved)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CONNECTION));
        // TLS 를 요구했는데 서버가 지원하지 않는다
        SourceConnectionDto.Request tls = new SourceConnectionDto.Request(null, "POSTGRESQL", db.host(), db.port(), db.name(), null, USER, PASSWORD,
                Map.of("sslmode", "require"), null, null, null);
        assertThat(connectionApi.testDraft(e.managerDto(), null, tls).getBody().errorCode()).isEqualTo("SOURCE_TLS_FAILED");
        // 카탈로그를 읽을 수 없는 계정 (이 원본 DB 에서만 pg_namespace 권한을 회수한다)
        String role = "ai2b_reader_" + System.nanoTime();
        admin().execute("create role " + role + " login password 'reader-pw'");
        try {
            db.jdbc().execute("revoke select on pg_catalog.pg_namespace from public");
            SourceConnectionDto.Request reader = new SourceConnectionDto.Request(null, "POSTGRESQL", db.host(), db.port(), db.name(), null, role, "reader-pw", null, null, null, null);
            assertThat(connectionApi.testDraft(e.managerDto(), null, reader).getBody()).extracting(SourceConnectionDto.TestResult::connected,
                    SourceConnectionDto.TestResult::metadataReadable).containsExactly(true, false);
            Long readerConnection = connect(e.managerDto(), db, role, "reader-pw", null);
            SourceCollectionDto.Execution denied = awaitQuietly(e, refresh(e, readerConnection, "FULL").id());
            assertThat(denied).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode).containsExactly("FAILED", "SOURCE_PERMISSION_DENIED");
            assertThat(catalogRows(readerConnection)).isZero();
        } finally {
            admin().execute("drop database if exists " + db.name() + " with (force)");
            admin().execute("drop role if exists " + role);
        }
        assertThat(output.getAll()).doesNotContain(wrong).doesNotContain("reader-pw");
    }

    @Test
    @DisplayName("접속 대상 정책 (SEC-1) : 링크 로컬(메타데이터)·규칙 없는 사설 주소는 접속하지 않는다. 드라이버의 실제 소켓도 판정 결과에 고정된다 (판정이 없거나 DNS 가 다른 주소를 주면 막힌다)")
    void addressPolicy() throws Exception {
        Env e = env(null);
        for (String host : List.of("169.254.169.254", "10.255.255.1", "0.0.0.0")) {
            SourceConnectionDto.Request request = new SourceConnectionDto.Request(null, "POSTGRESQL", host, 5432, "app", null, USER, "x", null, null, null, null);
            assertThat(connectionApi.testDraft(e.managerDto(), null, request).getBody()).as(host).extracting(SourceConnectionDto.TestResult::connected,
                    SourceConnectionDto.TestResult::errorCode).containsExactly(false, "SOURCE_HOST_NOT_ALLOWED");
        }
        // 드라이버가 실제로 쓰는 소켓 : 판정 결과 없이 직접 접속하면 소켓이 막는다
        java.net.URI uri = java.net.URI.create(ADMIN_URL.substring("jdbc:".length()));
        String url = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + "/postgres";
        Properties properties = SourceJdbcSettings.connectionProperties(DbType.POSTGRESQL, USER, PASSWORD, 5, 5, null);
        assertThat(properties.getProperty("socketFactory")).isEqualTo(SourceGuardedSocketFactory.class.getName());
        assertThatThrownBy(() -> java.sql.DriverManager.getConnection(url, properties))
                .isInstanceOf(SQLException.class).hasRootCauseInstanceOf(com.lexorma.core.network.PinnedSocket.BlockedConnectionException.class);
        java.net.InetAddress address = java.net.InetAddress.getByName(uri.getHost());
        // 판정 뒤 DNS 가 다른 주소를 준 상황 : 판정한 주소(127.0.0.2)가 아니면 연결하지 않는다
        Properties rebound = SourceJdbcSettings.connectionProperties(DbType.POSTGRESQL, USER, PASSWORD, 5, 5, new com.lexorma.core.network.ConnectionPin(
                uri.getHost(), uri.getPort(), List.of(java.net.InetAddress.getByName("127.0.0.2")), com.lexorma.core.network.NetworkAddresses.Cidr.parse("127.0.0.0/8"), List.of()));
        assertThatThrownBy(() -> java.sql.DriverManager.getConnection(url, rebound))
                .isInstanceOf(SQLException.class).hasRootCauseInstanceOf(com.lexorma.core.network.PinnedSocket.BlockedConnectionException.class);
        Properties pinned = SourceJdbcSettings.connectionProperties(DbType.POSTGRESQL, USER, PASSWORD, 5, 5, new com.lexorma.core.network.ConnectionPin(
                uri.getHost(), uri.getPort(), List.of(address), com.lexorma.core.network.NetworkAddresses.Cidr.parse(address.getHostAddress() + "/32"), List.of()));
        try (Connection connection = java.sql.DriverManager.getConnection(url, pinned)) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }

    @Test
    @DisplayName("권한 : VIEWER 는 최신화·연결 테스트를 못 하고, 프로젝트 관리자가 아닌 USER 도 서비스가 막는다. 다른 사람의 수집은 취소할 수 없다")
    void permissions() throws Exception {
        SourceDb db = source();
        Env e = env(db);
        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, e.project().getId(), String.valueOf(account("vw", "ROLE_VIEWER").getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> collectionApi.refresh(viewer, e.connection(), new SourceCollectionDto.RefreshRequest("FULL", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> connectionApi.test(viewer, e.connection())).isInstanceOf(AccessDeniedException.class);
        var userAccount = account("us", "ROLE_USER");
        projectMemberRepository.save(com.lexorma.core.project.domain.entity.ProjectMember.builder().project(e.project()).account(userAccount).isActive(true).build());
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, e.project().getId(), String.valueOf(userAccount.getId()), "ROLE_USER");
        assertThatThrownBy(() -> collectionApi.refresh(user, e.connection(), new SourceCollectionDto.RefreshRequest("FULL", null)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> connectionApi.test(user, e.connection())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> connectionApi.testDraft(user, null, new SourceConnectionDto.Request(null, "POSTGRESQL", db.host(), db.port(), db.name(), null, USER, PASSWORD,
                null, null, null, null))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> collectionApi.refresh(e.managerDto(), e.connection(), new SourceCollectionDto.RefreshRequest("SOME", null)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CATALOG));
        SecurityTestSupport.loginAs(projectRepository, e.project().getId(), String.valueOf(e.manager().getId()), "ROLE_DBA");
        Long done = collect(e, "FULL").id();
        assertThatThrownBy(() -> collectionApi.cancel(e.managerDto(), done)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_NOT_RUNNING));
    }

    // ------------------------------------------------------------------ 진행 중 사건 (원본 조회를 잠금으로 붙잡아 둔다)

    @Test
    @DisplayName("진행 중 : 중복 요청은 409, 취소하면 원본 조회가 멈추고 CANCELLED (반영 없음). 조회 제한 시간·백엔드 종료는 FAILED 이고 현재 카탈로그는 그대로다")
    void inFlight() throws Exception {
        SourceDb db = source(MODEL);
        Env e = env(db);
        collect(e, "FULL");
        int rows = catalogRows(e.connection());
        db.jdbc().execute("create table app.never_applied (id int)");

        // 취소
        Connection lock = lockCatalog(db, "pg_description");
        Long running = refresh(e, e.connection(), "FULL").id();
        awaitCollectorWaiting(db);
        assertThatThrownBy(() -> refresh(e, e.connection(), "FULL")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_COLLECTION_IN_PROGRESS));
        assertThat(collectionApi.cancel(e.managerDto(), running).getBody().status()).isEqualTo("CANCELLED");
        awaitCollectorGone(db);
        lock.rollback();
        assertThat(await(e, running)).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::reflectedOrder).containsExactly("CANCELLED", null);
        assertThat(catalogRows(e.connection())).isEqualTo(rows);

        // 조회 제한 시간 (3초)
        lock = lockCatalog(db, "pg_description");
        SourceCollectionDto.Execution timedOut = await(e, refresh(e, e.connection(), "FULL").id());
        lock.rollback();
        assertThat(timedOut).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode).containsExactly("FAILED", "SOURCE_QUERY_TIMEOUT");

        // 수집 도중 원본이 연결을 끊는다
        lock = lockCatalog(db, "pg_description");
        Long terminated = refresh(e, e.connection(), "FULL").id();
        int pid = awaitCollectorWaiting(db);
        admin().queryForObject("select pg_terminate_backend(?)", Boolean.class, pid);
        assertThat(await(e, terminated)).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode).containsExactly("FAILED", "SOURCE_CONNECT_FAILED");
        lock.rollback();
        assertThat(catalogRows(e.connection())).isEqualTo(rows);
        assertThat(tables(e, "app")).doesNotContain("never_applied:TABLE");

        // 수집 도중 권한 회수 : 반영 직전 재검증에서 막힌다
        lock = lockCatalog(db, "pg_description");
        Long revoked = refresh(e, e.connection(), "FULL").id();
        awaitCollectorWaiting(db);
        var other = account("other", "ROLE_DBA");
        tx.executeWithoutResult(s -> projectRepository.findById(e.project().getId()).orElseThrow().setAccount(other));
        lock.rollback();
        assertThat(awaitQuietly(e, revoked)).extracting(SourceCollectionDto.Execution::status, SourceCollectionDto.Execution::errorCode)
                .containsExactly("FAILED", "EXECUTION_PERMISSION_DENIED");
        assertThat(catalogRows(e.connection())).isEqualTo(rows);
        tx.executeWithoutResult(s -> projectRepository.findById(e.project().getId()).orElseThrow().setAccount(e.manager()));

        // 수집 도중 연결 삭제 : 실행·카탈로그가 함께 지워지고, 작업은 조용히 끝난다 (원본 연결도 닫힌다)
        lock = lockCatalog(db, "pg_description");
        refresh(e, e.connection(), "FULL");
        awaitCollectorWaiting(db);
        connectionApi.delete(e.managerDto(), e.connection());
        awaitCollectorGone(db);
        lock.rollback();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = ?", Integer.class, e.connection())).isZero();
        assertThat(catalogRows(e.connection())).isZero();
    }

    // ------------------------------------------------------------------ 성능 (opt-in)

    @Test
    @DisplayName("성능 (LEXORMA_PERF=true) : 100/1,000/3,000 테이블 × 20 컬럼의 최초·변경 없는 재수집 시간과 원본 조회 수")
    void performance() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("LEXORMA_PERF")), "LEXORMA_PERF=true 일 때만");
        for (int tables : List.of(100, 1000, 3000)) {
            SourceDb db = source("create schema perf");
            db.jdbc().execute("""
                    do $$ begin for i in 1..%d loop
                      execute format('create table perf.t%%s (id bigint primary key, %s, ref bigint references perf.t%%s(id))', i, (select string_agg('c' || c || ' varchar(50)', ', ')
                        from generate_series(1, 18) c), greatest(i - 1, 1));
                      execute format('create index ix_t%%s on perf.t%%s (c1, c2)', i, i);
                      if i %% 200 = 0 then commit; end if;
                    end loop; end $$""".formatted(tables, "%s"));
            Env e = env(db);
            Runtime runtime = Runtime.getRuntime();
            System.gc();
            long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
            long started = System.nanoTime();
            SourceCollectionDto.Execution first = collect(e, "PARTIAL", "perf");
            long firstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
            started = System.nanoTime();
            SourceCollectionDto.Execution again = collect(e, "PARTIAL", "perf");
            long againMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf("[AI-2b perf] tables=%d columns=%d first=%dms (versions=%d) again=%dms (versions=%d) heapDeltaMB=%d%n", first.tableCount(), first.columnCount(),
                    firstMs, first.versionCount(), againMs, again.versionCount(), (memoryAfter - memoryBefore) / 1024 / 1024);
            assertThat(first.tableCount()).isEqualTo(tables);
            assertThat(again.versionCount()).isZero();
            admin().execute("drop database if exists " + db.name() + " with (force)");
        }
    }

    // ------------------------------------------------------------------ 도움

    private SourceCollectionDto.Execution awaitQuietly(Env e, Long id) throws InterruptedException {
        SecurityTestSupport.loginAs(projectRepository, e.project().getId(), String.valueOf(e.manager().getId()), "ROLE_DBA");
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            String status = jdbc.queryForObject("select EXCN_STTS_CD from TB_SRC_CLCT_EXCN where SRC_CLCT_EXCN_SN = ?", String.class, id);
            if (!"RUNNING".equals(status)) {
                return new SourceCollectionDto.Execution(id, null, null, null, null, status, null, null, null, null, null, null, null, null,
                        jdbc.queryForObject("select ERR_CD from TB_SRC_CLCT_EXCN where SRC_CLCT_EXCN_SN = ?", String.class, id), null);
            }
            Thread.sleep(25);
        }
        throw new AssertionError("수집이 끝나지 않았습니다 : " + id);
    }

    private void awaitActive(SourceDb db) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            if (admin().queryForObject("select count(*) from pg_stat_activity where datname = ? and application_name = ? and state = 'active'", Integer.class,
                    db.name(), APPLICATION) > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("원본 조회가 시작되지 않았습니다");
    }

    private List<String> tables(Env e, String schema) {
        return jdbc.queryForList("select t.TBL_NM || ':' || t.TBL_TYPE_CD from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN"
                + " where t.SRC_CNTN_SN = ? and s.SCHM_NM = ? and t.EXST_YN = true", String.class, e.connection(), schema);
    }

    private SourceCatalogDto.TableDetail detail(Env e, String schema, String table) {
        Long id = jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = ?"
                + " and t.CMPR_NM = ?", Long.class, e.connection(), schema, table);
        return catalog.table(e.managerDto(), id);
    }

    private static Map<String, SourceCatalogDto.Column> byName(List<SourceCatalogDto.Column> columns) {
        Map<String, SourceCatalogDto.Column> map = new java.util.LinkedHashMap<>();
        columns.forEach(column -> map.put(column.name(), column));
        return map;
    }

    private static Consumer<CustomException> code(ErrorCode expected) {
        return e -> assertThat(e.getErrorCode()).isEqualTo(expected);
    }

    /** 테스트 도구 : 세션의 트랜잭션 상태를 읽고(tx), 쓰기를 시도하고(write), 오래 걸리는 조회를 보낸다(sleep) */
    static class ProbeTool implements SourceMetadataTool<String, List<String>> {

        static final String NAME = "test.jdbc.probe";

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public boolean supports(DbType dbType) {
            return dbType == DbType.POSTGRESQL;
        }

        @Override
        public Class<String> inputType() {
            return String.class;
        }

        @Override
        public List<String> execute(SourceToolContext context, String input) {
            return switch (input) {
                case "tx" -> context.query("select current_setting('transaction_read_only'), current_setting('transaction_isolation'), pg_backend_pid()::text, now()::text",
                        null, row -> List.of(row.getString(1), row.getString(2), row.getString(3), row.getString(4))).get(0);
                case "write" -> context.query("create table public.written (id int) ", null, row -> List.of("written")).stream().findFirst().orElse(List.of());
                default -> context.query("select pg_sleep(30)::text", null, row -> String.valueOf(row.getString(1)));
            };
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeTool jdbcProbeTool() {
            return new ProbeTool();
        }
    }
}
