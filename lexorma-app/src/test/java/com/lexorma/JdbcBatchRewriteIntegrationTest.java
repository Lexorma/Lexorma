package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC 배치 INSERT 재작성 (DB-1 3차 PG-D43 — docs/completed/postgresql-only-migration.md 51장) : {@code reWriteBatchedInserts} 는 URL 이 아니라
 * {@code spring.datasource.hikari.data-source-properties} 로 준다. 설정값이 있는지가 아니라 실제로 적용되는지를 본다.
 * <ul>
 *   <li>설정 전달 : 애플리케이션 풀의 연결 → 드라이버 QueryExecutor 의 재작성 켜짐</li>
 *   <li>DB 관측 : 문장 단위 트리거(FOR EACH STATEMENT)로 서버가 실제로 받은 INSERT 문장 수를 센다 — 켜짐이면 여러 행 INSERT(문장 수 &lt; 행 수), 같은 서버에 재작성을 끈
 *       연결이면 행마다 한 문장. 드라이버 결과 값도 다르다 (켜짐 = SUCCESS_NO_INFO)</li>
 *   <li>업무 경로 : 논리 → 물리 생성이 Hibernate 배치로 넣는 TB_COL 행 (시퀀스 ID — 배치 가능)도 행보다 적은 문장으로 들어간다</li>
 * </ul>
 * 트리거·함수·임시 테이블은 롤백하는 트랜잭션 안에서만 만든다 (테스트 DB 에 남지 않는다). 업무 SQL · 배치 크기 · ID 전략은 바꾸지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class JdbcBatchRewriteIntegrationTest {

    private static final int ROWS = 300;

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @PersistenceContext EntityManager em;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("설정 전달 : 애플리케이션 풀의 연결은 드라이버의 배치 INSERT 재작성이 켜져 있다 (URL 에는 없다)")
    void propertyReachesDriver() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).doesNotContainIgnoringCase("reWriteBatchedInserts");
            // 드라이버는 실행 시 의존성(runtimeOnly)이라 리플렉션으로 : BaseConnection.getQueryExecutor().isReWriteBatchedInsertsEnabled()
            Class<?> base = Class.forName("org.postgresql.core.BaseConnection");
            Object executor = base.getMethod("getQueryExecutor").invoke(connection.unwrap(base));
            Object enabled = Class.forName("org.postgresql.core.QueryExecutor").getMethod("isReWriteBatchedInsertsEnabled").invoke(executor);
            assertThat(enabled).isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("JDBC 배치 300행 : 켜짐(애플리케이션 풀) = 여러 행 INSERT 몇 문장 · 결과 SUCCESS_NO_INFO, 끔(같은 서버 별도 연결) = 300문장 · 결과 1 — 행 수·값은 같다")
    void rewriteObservedByServer() throws Exception {
        int[] on = batch(dataSource);
        Properties off = new Properties();
        off.setProperty("reWriteBatchedInserts", "false");
        DriverManagerDataSource plain = TestDatabaseGuard.directDataSource();
        plain.setConnectionProperties(off);
        int[] offResult = batch(plain);
        System.out.println("[PG-D43] JDBC 배치 " + ROWS + "행 : 켜짐 문장 " + on[0] + " · 끔 문장 " + offResult[0] + " · 결과 값 켜짐 " + on[1] + " / 끔 " + offResult[1]);
        assertThat(on[2]).isEqualTo(ROWS);
        assertThat(offResult[2]).isEqualTo(ROWS);
        assertThat(on[3]).as("넣은 값의 합").isEqualTo(offResult[3]);
        assertThat(on[0]).as("켜짐 : 서버가 받은 INSERT 문장 수").isPositive().isLessThan(ROWS / 10);
        assertThat(offResult[0]).as("끔 : 행마다 한 문장").isEqualTo(ROWS);
        assertThat(on[1]).as("켜짐 : 드라이버 결과 값 SUCCESS_NO_INFO").isEqualTo(Statement.SUCCESS_NO_INFO);
        assertThat(offResult[1]).isEqualTo(1);
    }

    @Test
    @DisplayName("업무 경로 : 논리 → 물리 생성(엔터티 3 · 속성 120)이 넣는 TB_COL 행은 Hibernate 배치 + 재작성으로 행보다 적은 문장에 들어간다")
    void hibernateBatchIsRewritten() {
        GenerationFixture f = GenerationFixture.create(context, "batch-rewrite-" + System.nanoTime(), DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long entity = f.entity(new String[]{"고객", "주문", "상품"}[i]);
            entities.add(entity);
            f.pk(entity, f.attr(entity, "키", true, "K1"));
            for (int a = 1; a < 40; a++) f.attr(entity, "속성" + a, false, String.format("C%02d", a));
        }
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(entities));
        int[] seen = tx.execute(status -> {
            jdbc.execute("create temp table batch_statement_count (n int) on commit drop");
            jdbc.execute("create function pg_temp.count_batch_statement() returns trigger language plpgsql as $$ begin insert into batch_statement_count values (1); return null; end $$");
            jdbc.execute("create trigger trg_batch_statement_count after insert on TB_COL for each statement execute function pg_temp.count_batch_statement()");
            generation.apply(f.user, new PhysicalGenerationDto.Request(entities, null, plan.planToken()));
            em.flush();
            int rows = jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Integer.class, f.user.getProjectId());
            int statements = jdbc.queryForObject("select count(*) from batch_statement_count", Integer.class);
            status.setRollbackOnly(); // 트리거·함수·임시 테이블과 생성 결과를 남기지 않는다
            return new int[]{rows, statements};
        });
        System.out.println("[PG-D43] 물리 생성 TB_COL " + seen[0] + "행 → INSERT 문장 " + seen[1]);
        assertThat(seen[0]).as("생성된 컬럼").isEqualTo(120);
        assertThat(seen[1]).as("서버가 받은 TB_COL INSERT 문장 수").isPositive().isLessThan(seen[0] / 4);
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgname = 'trg_batch_statement_count'", Integer.class)).as("트리거는 롤백으로 없어졌다").isZero();
    }

    /** 롤백하는 트랜잭션 안에서 임시 표에 ROWS 행을 배치로 넣는다 : [서버가 받은 INSERT 문장 수, 드라이버 결과 값(첫 행), 행 수, 값 합] */
    private static int[] batch(DataSource source) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("create temp table batch_rows (id int, name varchar(20)) on commit drop");
                ddl.execute("create temp table batch_statements (n int) on commit drop");
                ddl.execute("create function pg_temp.count_rows_statement() returns trigger language plpgsql as $$ begin insert into batch_statements values (1); return null; end $$");
                ddl.execute("create trigger trg_rows after insert on batch_rows for each statement execute function pg_temp.count_rows_statement()");
            }
            int[] results;
            try (PreparedStatement insert = connection.prepareStatement("insert into batch_rows (id, name) values (?, ?)")) {
                for (int i = 1; i <= ROWS; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, "r" + i);
                    insert.addBatch();
                }
                results = insert.executeBatch();
            }
            assertThat(Arrays.stream(results).distinct().count()).as("결과 값은 한 종류").isOne();
            try (Statement read = connection.createStatement()) {
                var statements = read.executeQuery("select count(*) from batch_statements");
                statements.next();
                int s = statements.getInt(1);
                var rows = read.executeQuery("select count(*), sum(id) from batch_rows");
                rows.next();
                int[] out = {s, results[0], rows.getInt(1), rows.getInt(2)};
                connection.rollback();
                return out;
            }
        }
    }
}
