package com.lexorma;

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
import com.lexorma.source.collection.controller.SourceCollectionRestController;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.service.SourceSnapshotService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AI-2b 원본 DB(PostgreSQL) 통합 테스트 공통 준비. 원본 역할의 데이터베이스를 테스트마다 새로 만들고(ai2b_…) 끝나면 지운다 — Lexorma 내부 DB(H2 또는 검증용 PostgreSQL)와 따로다.
 * <pre>
 * LEXORMA_TEST_SOURCE_POSTGRES_URL=jdbc:postgresql://localhost:15439/postgres   (데이터베이스를 만들 수 있는 계정, 없으면 건너뛴다)
 * LEXORMA_TEST_SOURCE_POSTGRES_USER / _PASSWORD                                 (기본 lexorma / lexorma)
 * </pre>
 */
abstract class SourceJdbcTestSupport {

    static final String ADMIN_URL = System.getenv("LEXORMA_TEST_SOURCE_POSTGRES_URL");
    static final String USER = System.getenv().getOrDefault("LEXORMA_TEST_SOURCE_POSTGRES_USER", "lexorma");
    static final String PASSWORD = System.getenv().getOrDefault("LEXORMA_TEST_SOURCE_POSTGRES_PASSWORD", "lexorma");
    static final String APPLICATION = "Lexorma metadata collector";

    @Autowired SourceCollectionRestController collectionApi;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SourceSnapshotService snapshots;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final List<SourceDb> databases = new ArrayList<>();
    private final List<Connection> lockHolders = new ArrayList<>();

    @BeforeEach
    void requireSource() {
        assumeTrue(ADMIN_URL != null, "원본 PostgreSQL 미지정 (LEXORMA_TEST_SOURCE_POSTGRES_URL)");
    }

    @AfterEach
    void dropSources() throws SQLException {
        SecurityTestSupport.clear();
        for (Connection holder : lockHolders) holder.close();
        lockHolders.clear();
        for (SourceDb db : databases) admin().execute("drop database if exists " + db.name + " with (force)");
        databases.clear();
    }

    /** 원본 역할의 데이터베이스 (이번 테스트 전용) */
    record SourceDb(String name, String host, int port, JdbcTemplate jdbc) {
        String url() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }

    SourceDb source(String... ddl) {
        String name = "ai2b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        admin().execute("create database " + name);
        URI uri = URI.create(ADMIN_URL.substring("jdbc:".length()));
        SourceDb db = new SourceDb(name, uri.getHost(), uri.getPort(), null);
        db = new SourceDb(name, db.host(), db.port(), new JdbcTemplate(new DriverManagerDataSource(db.url(), USER, PASSWORD)));
        databases.add(db);
        for (String statement : ddl) db.jdbc().execute(statement);
        return db;
    }

    static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(ADMIN_URL, USER, PASSWORD));
    }

    /** 원본의 시스템 카탈로그 하나를 배타 잠금으로 잡아 둔다 : 수집 조회가 그 카탈로그를 읽는 순간 기다린다 (원본 조회가 오래 걸리는 상황) */
    Connection lockCatalog(SourceDb db, String catalog) throws SQLException {
        Connection holder = DriverManager.getConnection(db.url(), USER, PASSWORD);
        lockHolders.add(holder);
        holder.setAutoCommit(false);
        holder.createStatement().execute("lock table pg_catalog." + catalog + " in access exclusive mode");
        return holder;
    }

    /** 수집 세션이 원본에서 잠금을 기다릴 때까지 (그 백엔드의 pid) */
    int awaitCollectorWaiting(SourceDb db) throws InterruptedException {
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until) {
            List<Integer> pids = admin().queryForList("select pid from pg_stat_activity where datname = ? and application_name = ? and wait_event_type = 'Lock'",
                    Integer.class, db.name, APPLICATION);
            if (!pids.isEmpty()) return pids.get(0);
            Thread.sleep(20);
        }
        throw new AssertionError("수집 세션이 잠금을 기다리지 않았습니다");
    }

    /** 수집 세션의 원본 연결이 모두 닫혔는지 */
    void awaitCollectorGone(SourceDb db) throws InterruptedException {
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until) {
            if (collectorSessions(db) == 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("수집 세션의 원본 연결이 남아 있습니다");
    }

    int collectorSessions(SourceDb db) {
        return admin().queryForObject("select count(*) from pg_stat_activity where datname = ? and application_name = ?", Integer.class, db.name, APPLICATION);
    }

    // ------------------------------------------------------------------ Lexorma 쪽 준비

    record Env(Project project, Account manager, AccountDto managerDto, Long connection) {
    }

    /** 프로젝트 하나와 그 관리자(DBA 역할), 원본 DB 연결 하나 */
    Env env(SourceDb db) {
        Account manager = account("mgr", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(unique("ai2b")).isActive(true).account(manager).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(manager).isActive(true).build());
        AccountDto managerDto = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA");
        Long connection = db == null ? null : connect(managerDto, db, USER, PASSWORD, null);
        return new Env(project, manager, managerDto, connection);
    }

    Long connect(AccountDto dto, SourceDb db, String user, String password, Map<String, String> options) {
        return connectionApi.create(dto, new SourceConnectionDto.Request(unique("src"), "POSTGRESQL", db.host(), db.port(), db.name(), null, user, password, options, null,
                null, null)).getBody().id();
    }

    Account account(String prefix, String role) {
        String username = unique(prefix);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    SourceCollectionDto.Execution refresh(Env e, Long connection, String type, String... schemas) {
        Long id = collectionApi.refresh(e.managerDto(), connection, new SourceCollectionDto.RefreshRequest(type, schemas.length == 0 ? null : List.of(schemas)))
                .getBody().executionId();
        return execution(e, id);
    }

    SourceCollectionDto.Execution execution(Env e, Long executionId) {
        return snapshots.execution(e.managerDto(), executionId).execution();
    }

    /** 수집이 끝날 때까지 (RUNNING 이 아닐 때까지) */
    SourceCollectionDto.Execution await(Env e, Long executionId) throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            SourceCollectionDto.Execution execution = execution(e, executionId);
            if (!"RUNNING".equals(execution.status()) && !"CREATED".equals(execution.status())) return execution;
            Thread.sleep(25);
        }
        throw new AssertionError("수집이 끝나지 않았습니다 : " + executionId);
    }

    SourceCollectionDto.Execution collect(Env e, String type, String... schemas) throws InterruptedException {
        SourceCollectionDto.Execution done = await(e, refresh(e, e.connection(), type, schemas).id());
        assertThat(done.errorCode()).as("수집 오류").isNull();
        return done;
    }

    int catalogRows(Long connection) {
        return jdbc.queryForObject("select count(*) from TB_SRC_TBL where SRC_CNTN_SN = ?", Integer.class, connection)
                + jdbc.queryForObject("select count(*) from TB_SRC_SCHM where SRC_CNTN_SN = ?", Integer.class, connection);
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
