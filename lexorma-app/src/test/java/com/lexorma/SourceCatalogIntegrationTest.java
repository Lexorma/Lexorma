package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.catalog.domain.dto.SourceCatalogDto;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.catalog.domain.entity.SourceColumn;
import org.springframework.dao.DataAccessException;
import com.lexorma.core.project.domain.event.ProjectDeletedEvent;
import com.lexorma.source.cleanup.SourceCatalogCleanup;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.catalog.service.SourceCatalogWriter;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.system.project.service.ProjectManageService;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.crypto.SecretScope;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.source.catalog.controller.SourceCatalogRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto.PasswordStatus;
import com.lexorma.source.tool.SourceMetadataTool;
import com.lexorma.source.tool.SourceToolContext;
import com.lexorma.source.tool.SourceToolExecutor;
import com.lexorma.source.tool.SourceToolRegistry;
import com.lexorma.source.tool.SourceTools;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-1b (H2 / PostgreSQL, lexorma-source) : 프로젝트의 외부 DB 연결 설정, 내부 도구 실행 계약, 원본 메타데이터 카탈로그.
 * 화면이 부르는 컨트롤러로 권한·격리를 보고, 카탈로그는 수집 대신 SourceCatalogWriter 로 적재한다 (원본 DB 에 접속하지 않는다 : 테스트 도구는 접속하지 않는다).
 * 비밀번호 암호화를 보기 위해 이 클래스의 컨텍스트에만 암호화 키를 준다 (키가 없을 때의 동작은 ExecutionContextIntegrationTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1"})
@ActiveProfiles("test")
class SourceCatalogIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Src!Pw-" + UUID.randomUUID();

    @Autowired SourceConnectionRestController api;
    @Autowired SourceCatalogRestController catalog;
    @Autowired SourceToolExecutor tools;
    @Autowired SourceToolRegistry registry;
    @Autowired SecretCipher secretCipher;
    @Autowired SourceCatalogWriter writer;
    @Autowired SourceCatalogCleanup cleanup;
    @Autowired SourceConnectionRepository connectionRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired ProjectManageService projectManageService;
    @Autowired ProjectWriteGuard projectWriteGuard;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired ModelChangeHistoryRepository historyRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 외부 DB 연결 설정

    @Test
    @DisplayName("연결 설정 : DBMS 별 필수 항목·형식을 검증하고 JDBC URL·임의 옵션은 받지 않는다. 비밀번호는 암호화해 저장하고 응답에 없으며, 비워 둔 수정은 그대로 둔다")
    void connectionSettings() throws Exception {
        Actors a = actors("ai1b-set");
        AccountDto manager = login(a.project, a.manager, "ROLE_DBA");

        SourceConnectionDto.Response pg = api.create(manager, new SourceConnectionDto.Request(" 운영 DB ", "postgresql", " db.internal ", null, " app ", null, " reader ", PASSWORD,
                Map.of("SSLMODE", "Require"), null, " 운영 ", null)).getBody();
        assertThat(pg).extracting(SourceConnectionDto.Response::name, SourceConnectionDto.Response::dbType, SourceConnectionDto.Response::host, SourceConnectionDto.Response::port,
                SourceConnectionDto.Response::databaseName, SourceConnectionDto.Response::databaseNameType, SourceConnectionDto.Response::username,
                SourceConnectionDto.Response::passwordConfigured, SourceConnectionDto.Response::passwordStatus, SourceConnectionDto.Response::options,
                SourceConnectionDto.Response::identifierComparison, SourceConnectionDto.Response::description, SourceConnectionDto.Response::enabled,
                SourceConnectionDto.Response::driverAvailable, SourceConnectionDto.Response::schemaCount)
                .containsExactly("운영 DB", "POSTGRESQL", "db.internal", 5432, "app", "DATABASE", "reader", true, PasswordStatus.CONFIGURED, Map.of("sslmode", "require"),
                        "DBMS_DEFAULT", "운영", true, true, 0L);
        // 응답 어디에도 비밀번호·암호문이 없다. 요청의 toString 에도 없다
        assertThat(JSON.writeValueAsString(pg)).doesNotContain(PASSWORD).doesNotContain("v1:t1:");
        assertThat(JSON.writeValueAsString(api.list(manager).getBody())).doesNotContain(PASSWORD).doesNotContain("v1:t1:");
        assertThat(new SourceConnectionDto.Request("n", "MYSQL", "h", null, "d", null, "u", PASSWORD, null, null, null, null).toString()).doesNotContain(PASSWORD).contains("****");
        // DB 에는 암호문만 있다. JDBC URL 을 담는 컬럼이 없다
        String stored = passwordColumn(pg.id());
        assertThat(stored).startsWith("v1:t1:").doesNotContain(PASSWORD);
        assertThat(secretCipher.decrypt(stored, SecretScope.ofProject("source-connection", a.project.getId(), pg.id(), "password"))).isEqualTo(PASSWORD);
        assertThat(columns("TB_SRC_CNTN")).noneMatch(column -> column.contains("URL")).noneMatch(column -> column.contains("MCP"));
        assertThat(jdbc.queryForObject("select CNTN_OPTN_CN from TB_SRC_CNTN where SRC_CNTN_SN = ?", String.class, pg.id())).isEqualTo("{\"sslmode\":\"require\"}");
        assertThat(jdbc.queryForMap("select RGTR_ID, REG_DT from TB_SRC_CNTN where SRC_CNTN_SN = ?", pg.id()).values()).doesNotContainNull();

        // 비밀번호를 비워 둔 수정 : 암호문 그대로. 옵션을 주지 않으면 옵션도 그대로
        SourceConnectionDto.Response moved = api.update(manager, pg.id(), new SourceConnectionDto.Request("운영 DB", null, "db2.internal", 6432, "app", null, "reader", "", null, null, null, null)).getBody();
        assertThat(moved).extracting(SourceConnectionDto.Response::host, SourceConnectionDto.Response::port, SourceConnectionDto.Response::options).containsExactly("db2.internal", 6432, Map.of("sslmode", "require"));
        assertThat(passwordColumn(pg.id())).isEqualTo(stored);
        // 비밀번호를 준 수정 : 다시 암호화한다 (앞뒤 공백도 값이다)
        api.update(manager, pg.id(), new SourceConnectionDto.Request("운영 DB", null, "db2.internal", null, "app", null, "reader", " " + PASSWORD + " ", Map.of(), null, null, null));
        assertThat(passwordColumn(pg.id())).startsWith("v1:t1:").isNotEqualTo(stored);
        assertThat(secretCipher.decrypt(passwordColumn(pg.id()), SecretScope.ofProject("source-connection", a.project.getId(), pg.id(), "password"))).isEqualTo(" " + PASSWORD + " ");
        assertThat(api.detail(manager, pg.id()).getBody().options()).isEmpty();

        // 다른 연결·다른 프로젝트의 암호문을 옮겨 오면 읽히지 않는다 → 재등록 필요. 다시 등록하면 읽힌다
        SourceConnectionDto.Response other = api.create(manager, request("다른 DB", "MYSQL", "shop")).getBody();
        jdbc.update("update TB_SRC_CNTN set CNTN_PSWD_CN = ? where SRC_CNTN_SN = ?", passwordColumn(pg.id()), other.id());
        assertThat(api.detail(manager, other.id()).getBody().passwordStatus()).isEqualTo(PasswordStatus.REREGISTER_REQUIRED);
        jdbc.update("update TB_SRC_CNTN set CNTN_PSWD_CN = 'v1:lost:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' where SRC_CNTN_SN = ?", other.id());
        assertThat(api.detail(manager, other.id()).getBody().passwordStatus()).isEqualTo(PasswordStatus.REREGISTER_REQUIRED);
        api.update(manager, other.id(), new SourceConnectionDto.Request("다른 DB", null, "db.internal", null, "shop", null, "reader", "again", null, null, null, null));
        assertThat(api.detail(manager, other.id()).getBody().passwordStatus()).isEqualTo(PasswordStatus.CONFIGURED);
        // 비밀번호 없이도 등록할 수 있다 (비밀번호 없는 계정)
        assertThat(api.create(manager, new SourceConnectionDto.Request("무암호", "MARIADB", "db.internal", null, "crm", null, "reader", null, null, null, null, null)).getBody())
                .extracting(SourceConnectionDto.Response::passwordConfigured, SourceConnectionDto.Response::passwordStatus, SourceConnectionDto.Response::port).containsExactly(false, PasswordStatus.NONE, 3306);

        // DBMS 별 : Oracle 은 서비스명(기본)·SID, 그 밖에는 데이터베이스명. 포트 기본값. MySQL·MariaDB 만 식별자 비교 규칙을 고른다
        SourceConnectionDto.Response ora = api.create(manager, new SourceConnectionDto.Request("ORA", "ORACLE", "10.20.30.40", null, "ORCLPDB1", null, "HR_READER", PASSWORD, null, null, null, null)).getBody();
        assertThat(ora).extracting(SourceConnectionDto.Response::port, SourceConnectionDto.Response::databaseNameType).containsExactly(1521, "SERVICE_NAME");
        assertThat(api.create(manager, new SourceConnectionDto.Request("ORA-SID", "ORACLE", "::1", 1522, "ORCL", "sid", "HR", null, null, null, null, null)).getBody())
                .extracting(SourceConnectionDto.Response::host, SourceConnectionDto.Response::databaseNameType).containsExactly("::1", "SID");
        assertThat(api.create(manager, new SourceConnectionDto.Request("MY-CI", "MYSQL", "[fd00::5]", null, "shop", null, "reader", null, Map.of("sslMode", "required"), "CASE_INSENSITIVE", null, false)).getBody())
                .extracting(SourceConnectionDto.Response::host, SourceConnectionDto.Response::options, SourceConnectionDto.Response::identifierComparison, SourceConnectionDto.Response::enabled)
                .containsExactly("fd00::5", Map.of("sslMode", "REQUIRED"), "CASE_INSENSITIVE", false);

        // 검증 : 거절된 요청은 아무것도 남기지 않는다
        long count = connectionCount(a.project.getId());
        for (SourceConnectionDto.Request bad : List.of(
                new SourceConnectionDto.Request("운영 db", "POSTGRESQL", "db.internal", null, "app", null, "u", null, null, null, null, null),           // 이름 중복 (대소문자 무시)
                new SourceConnectionDto.Request(" ", "POSTGRESQL", "db.internal", null, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x".repeat(101), "POSTGRESQL", "db.internal", null, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", null, "db.internal", null, "app", null, "u", null, null, null, null, null),                           // DB 유형 없음
                new SourceConnectionDto.Request("x", "DB2", "db.internal", null, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", null, null, "app", null, "u", null, null, null, null, null),                           // 호스트 없음
                new SourceConnectionDto.Request("x", "POSTGRESQL", "jdbc:postgresql://db/app", null, "app", null, "u", null, null, null, null, null),     // JDBC URL 은 받지 않는다
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal:5432", null, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "user:pw@db.internal", null, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db internal", null, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", 0, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", 70000, "app", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, null, null, "u", null, null, null, null, null),                   // 데이터베이스명 없음
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app?user=x", null, "u", null, null, null, null, null),           // URL 구분 문자
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app/other", null, "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app", "SID", "u", null, null, null, null, null),                 // SID 는 Oracle 만
                new SourceConnectionDto.Request("x", "ORACLE", "db.internal", null, "ORCL", "DATABASE", "u", null, null, null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app", null, null, null, null, null, null, null),                 // 계정 없음
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app", null, "u", null, Map.of("socketFactory", "x"), null, null, null), // 허용하지 않는 옵션
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app", null, "u", null, Map.of("password", "x"), null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app", null, "u", null, Map.of("sslmode", "off"), null, null, null),
                new SourceConnectionDto.Request("x", "MYSQL", "db.internal", null, "app", null, "u", null, Map.of("allowLoadLocalInfile", "true"), null, null, null),
                new SourceConnectionDto.Request("x", "ORACLE", "db.internal", null, "ORCL", null, "u", null, Map.of("sslmode", "require"), null, null, null),
                new SourceConnectionDto.Request("x", "POSTGRESQL", "db.internal", null, "app", null, "u", null, null, "CASE_INSENSITIVE", null, null),     // PostgreSQL 은 규칙을 고르지 않는다
                new SourceConnectionDto.Request("x", "ORACLE", "db.internal", null, "ORCL", null, "u", null, null, "CASE_SENSITIVE", null, null),
                new SourceConnectionDto.Request("x", "MYSQL", "db.internal", null, "app", null, "u", null, null, "LOWER", null, null),
                new SourceConnectionDto.Request("x", "MYSQL", "db.internal", null, "app", null, "u", "p".repeat(1001), null, null, null, null),
                new SourceConnectionDto.Request("x", "MYSQL", "db.internal", null, "app", null, "u", null, null, null, "x".repeat(1001), null))) {
            assertThatThrownBy(() -> api.create(manager, bad)).as(bad.toString()).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CONNECTION));
        }
        assertThat(connectionCount(a.project.getId())).isEqualTo(count);
        // 오류 응답에 비밀번호가 들어가지 않는다
        Throwable failed = org.assertj.core.api.Assertions.catchThrowable(() -> api.create(manager, new SourceConnectionDto.Request("x", "POSTGRESQL", "bad host", null, "app", null, "u", PASSWORD, null, null, null, null)));
        assertThat(failed.getMessage() + ((CustomException) failed).getCustomMessage()).doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("외부 DB 연결 권한·격리 : 등록·수정·삭제는 프로젝트 관리자·(접근할 수 있는) ADMIN 만, 다른 프로젝트의 연결은 보이지 않고, 비활성 프로젝트는 바꿀 수 없다")
    void connectionAccess() {
        Actors a = actors("ai1b-conn");
        Actors b = actors("ai1b-conn-other");

        AccountDto manager = login(a.project, a.manager, "ROLE_DBA");
        SourceConnectionDto.Response prod = api.create(manager, request("운영 DB", "POSTGRESQL", "app")).getBody();
        SourceConnectionDto.Response legacy = api.create(manager, request("레거시", "MYSQL", "legacy")).getBody();
        assertThat(api.list(manager).getBody()).extracting(SourceConnectionDto.Response::id).containsExactlyInAnyOrder(prod.id(), legacy.id());

        // 다른 프로젝트 : 같은 이름·같은 주소를 쓸 수 있고, 서로의 연결은 목록·상세·수정·삭제·카탈로그 어디에서도 없는 것과 같다
        AccountDto otherManager = login(b.project, b.manager, "ROLE_DBA");
        SourceConnectionDto.Response theirs = api.create(otherManager, request("운영 DB", "POSTGRESQL", "app")).getBody();
        assertThat(api.list(otherManager).getBody()).extracting(SourceConnectionDto.Response::id).containsExactly(theirs.id());
        assertThatThrownBy(() -> api.detail(otherManager, prod.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> api.update(otherManager, prod.id(), request("탈취", "POSTGRESQL", "x"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> api.delete(otherManager, prod.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> catalog.schemas(otherManager, prod.id(), 0, 10)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> api.detail(otherManager, 999_999_999L)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        // 다른 프로젝트의 관리자가 이 프로젝트 ID 를 세션에 넣어 와도 (멤버가 아니다) 변경할 수 없다
        AccountDto forged = AccountDto.builder().id(String.valueOf(b.manager.getId())).username(b.manager.getUsername()).projectId(a.project.getId()).build();
        assertThatThrownBy(() -> api.create(forged, request("우회", "POSTGRESQL", "x"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));

        // 역할 : 변경 = 프로젝트에 접근할 수 있고 (프로젝트 관리자 또는 ADMIN). 프로젝트 관리자는 DBA 역할이 없어도(USER) 된다
        SourceConnectionDto.Request request = request("권한 시험", "POSTGRESQL", "auth");
        // USER + 프로젝트 관리자 : 등록·수정·삭제
        AccountDto userManager = login(a.project, a.manager, "ROLE_USER");
        SourceConnectionDto.Response byUserManager = api.create(userManager, request).getBody();
        assertThat(api.update(userManager, byUserManager.id(), request("권한 시험2", "POSTGRESQL", "auth")).getBody().name()).isEqualTo("권한 시험2");
        api.delete(userManager, byUserManager.id());
        // VIEWER + 프로젝트 관리자 : VIEWER 는 조회 전용 역할이라 변경하지 못한다
        AccountDto viewerManager = login(a.project, a.manager, "ROLE_VIEWER");
        assertThatThrownBy(() -> api.create(viewerManager, request)).isInstanceOf(AccessDeniedException.class);
        // DBA + 프로젝트 관리자 : 그대로 된다
        AccountDto dbaManager = login(a.project, a.manager, "ROLE_DBA");
        api.delete(dbaManager, api.create(dbaManager, request).getBody().id());
        // 프로젝트 관리자가 아닌 VIEWER·USER·DBA : 조회만
        AccountDto viewer = login(a.project, a.viewer, "ROLE_VIEWER");
        assertThat(api.list(viewer).getBody()).hasSize(2);
        assertThat(api.detail(viewer, prod.id()).getBody().id()).isEqualTo(prod.id());
        assertThatThrownBy(() -> api.create(viewer, request)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> api.update(viewer, prod.id(), request)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> api.delete(viewer, prod.id())).isInstanceOf(AccessDeniedException.class);
        AccountDto user = login(a.project, a.member, "ROLE_USER");
        assertThat(api.list(user).getBody()).hasSize(2);
        assertThatThrownBy(() -> api.create(user, request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> api.update(user, prod.id(), request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> api.delete(user, prod.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        AccountDto dba = login(a.project, a.member, "ROLE_DBA");
        assertThatThrownBy(() -> api.create(dba, request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> api.update(dba, prod.id(), request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> api.delete(dba, prod.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        // 미승인 계정 : 조회도 변경도 못 한다
        AccountDto pending = login(a.project, a.viewer, "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> api.list(pending)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> api.create(pending, request)).isInstanceOf(AccessDeniedException.class);
        // ADMIN 은 관리자가 아니어도 (멤버로 접근할 수 있는 프로젝트라면) 변경할 수 있다
        AccountDto admin = login(a.project, a.admin, "ROLE_ADMIN");
        SourceConnectionDto.Response byAdmin = api.create(admin, request).getBody();
        api.delete(admin, byAdmin.id());
        // 멤버가 아닌 ADMIN : 그 프로젝트에 접근할 수 없으므로 변경할 수 없다
        Account outsiderAdmin = account("adm", "ROLE_ADMIN");
        AccountDto strangerAdmin = login(a.project, outsiderAdmin, "ROLE_ADMIN");
        assertThatThrownBy(() -> api.create(strangerAdmin, request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThatThrownBy(() -> api.update(strangerAdmin, prod.id(), request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThatThrownBy(() -> api.delete(strangerAdmin, prod.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(api.list(manager).getBody()).extracting(SourceConnectionDto.Response::id).containsExactlyInAnyOrder(prod.id(), legacy.id());

        // 프로젝트 비활성화 : 데이터는 남고 변경은 막힌다
        tx.executeWithoutResult(s -> projectRepository.findById(a.project.getId()).orElseThrow().setIsActive(false));
        AccountDto inactive = login(a.project, a.manager, "ROLE_DBA");
        assertThatThrownBy(() -> api.create(inactive, request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThatThrownBy(() -> api.update(inactive, prod.id(), request)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThatThrownBy(() -> api.delete(inactive, prod.id())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        assertThat(connectionCount(a.project.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("내부 도구 실행 : 실행기만 컨텍스트를 만들고, 실행 직전에 프로젝트·요청자·연결·호스트 정책·DBMS 를 다시 확인한다. 접속 정보는 검증한 항목으로 만든 것이고 LLM 없이 실행된다")
    void toolExecution() {
        Actors a = actors("ai1b-tool");
        Actors b = actors("ai1b-tool-other");
        AccountDto manager = login(a.project, a.manager, "ROLE_DBA");
        Long pg = api.create(manager, new SourceConnectionDto.Request("PG", "POSTGRESQL", "db.internal", 6432, "app", null, "reader", PASSWORD, Map.of("sslmode", "verify-full"), null, null, null)).getBody().id();
        Long ora = api.create(manager, new SourceConnectionDto.Request("ORA", "ORACLE", "ora.internal", null, "ORCL", "SID", "hr", null, null, null, null, null)).getBody().id();
        Long off = api.create(manager, new SourceConnectionDto.Request("OFF", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null, null, false)).getBody().id();
        Long theirs = api.create(login(b.project, b.manager, "ROLE_DBA"), request("PG", "POSTGRESQL", "app")).getBody().id();

        // 사용자 요청 (프로젝트 관리자) : 도구가 받는 것은 실행기가 확인·조립한 컨텍스트다
        ProbeTool.last = null;
        String result = tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), pg, "ping");
        assertThat(result).isEqualTo("pong:ping");
        SourceToolContext context = ProbeTool.last;
        assertThat(context.projectId()).isEqualTo(a.project.getId());
        assertThat(context.connectionId()).isEqualTo(pg);
        assertThat(context.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(context.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(context.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:6432/app?sslmode=verify-full").doesNotContain("reader").doesNotContain(PASSWORD);
        assertThat(context.username()).isEqualTo("reader");
        assertThat(context.password()).isEqualTo(PASSWORD);
        assertThat(context.execution().manager()).isTrue();
        assertThat(context.toString()).doesNotContain(PASSWORD).doesNotContain("reader");
        // 비동기 작업(세션 없음)·정기 배치도 같은 실행기로 돈다
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThat((String) tools.execute(ProbeTool.NAME, ExecutionContext.async(a.project.getId(), a.admin.getId(), "job-1"), pg, "async")).isEqualTo("pong:async");
        assertThat((String) tools.execute(ProbeTool.NAME, ExecutionContext.scheduled(a.project.getId(), "nightly"), pg, "batch")).isEqualTo("pong:batch");
        assertThat(ProbeTool.last.execution().username()).isNull();

        // 미지원 DBMS (이 도구는 PostgreSQL 만), 없는 도구, 맞지 않는 입력 : 실행하지 않는다
        ProbeTool.last = null;
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), ora, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE));
        assertThatThrownBy(() -> tools.execute(SourceTools.TABLE_DISCOVERY, ExecutionContext.user(dto(a.manager, a.project)), pg, null)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE));
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), pg, 42)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE));
        assertThat(registry.names(DbType.POSTGRESQL)).containsExactly(ProbeTool.NAME);
        assertThat(registry.names(DbType.ORACLE)).isEmpty();
        // 사용 중지된 연결, 다른 프로젝트의 연결, 없는 연결
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), off, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE));
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), theirs, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), null, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        // 권한 : 프로젝트 관리자·ADMIN 이 아닌 사람, 멤버가 아닌 사람(프로젝트 ID 를 바꿔 넣어도), 지워진 연결
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.viewer, a.project)), pg, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.EXECUTION_PERMISSION_DENIED));
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(b.manager, a.project)), pg, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        // 비활성 프로젝트 : 배치도 멈춘다
        tx.executeWithoutResult(s -> projectRepository.findById(a.project.getId()).orElseThrow().setIsActive(false));
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.scheduled(a.project.getId(), "nightly"), pg, "x")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_ACCESS_DENIED));
        tx.executeWithoutResult(s -> projectRepository.findById(a.project.getId()).orElseThrow().setIsActive(true));
        assertThat(ProbeTool.last).as("확인에 실패한 실행은 도구까지 가지 않는다").isNull();

        // 저장된 비밀번호를 읽을 수 없으면 도구가 비밀번호를 꺼내는 순간에 실패한다 (평문도 엉뚱한 값도 나오지 않는다)
        jdbc.update("update TB_SRC_CNTN set CNTN_PSWD_CN = 'v1:lost:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' where SRC_CNTN_SN = ?", pg);
        assertThatThrownBy(() -> tools.execute(ProbeTool.NAME, ExecutionContext.user(dto(a.manager, a.project)), pg, "password")).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SECRET_KEY_NOT_FOUND));
    }

    /** 테스트용 도구 : 받은 컨텍스트를 기억하고 입력을 그대로 돌려준다. "password" 를 받으면 비밀번호를 꺼낸다 */
    static class ProbeTool implements SourceMetadataTool<String, String> {

        static final String NAME = "test.probe";
        static volatile SourceToolContext last;

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
        public String execute(SourceToolContext context, String input) {
            last = context;
            if ("password".equals(input)) context.password();
            return "pong:" + input;
        }
    }

    @TestConfiguration
    static class ToolConfiguration {
        @Bean
        ProbeTool probeTool() {
            return new ProbeTool();
        }
    }

    // ------------------------------------------------------------------ 원본 카탈로그

    @Test
    @DisplayName("원본 카탈로그 : 연결·스키마가 다르면 같은 이름도 다른 객체다. DBMS 별 식별자 규칙, 테이블·뷰, 컬럼 순서, 복합 PK·FK(다른 스키마)·인덱스·UNIQUE·CHECK, 원본 COMMENT 를 담고 물리 모델은 건드리지 않는다")
    void catalog() {
        Actors a = actors("ai1b-cat");
        long projectId = a.project.getId();
        AccountDto manager = login(a.project, a.manager, "ROLE_DBA");
        Long pgA = api.create(manager, request("PG-A", "POSTGRESQL", "app")).getBody().id();
        Long pgB = api.create(manager, request("PG-B", "POSTGRESQL", "app")).getBody().id();

        // 물리 모델에 같은 이름의 테이블이 있다 : 업무 설명은 사용자가 관리한다
        TableInfo physical = tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName("users").tableDescription("회원 업무 설명")
                .isApproval(false).isMasterTable(false).schema(schemaService.resolveSchema(projectId, null)).build()));
        long historyBefore = historyRepository.count();

        // 연결이 다르면 같은 public.users 도 다른 객체다
        SourceSchema publicA = writer.saveSchema(projectId, pgA, Name.of("app"), Name.of("public"));
        SourceSchema publicB = writer.saveSchema(projectId, pgB, Name.of("app"), Name.of("public"));
        SourceTable usersA = writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "원본 COMMENT : 사용자"));
        SourceTable usersB = writer.saveTable(projectId, publicB.getId(), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "B 의 사용자"));
        assertThat(publicA.getId()).isNotEqualTo(publicB.getId());
        assertThat(usersA.getId()).isNotEqualTo(usersB.getId());
        // 스키마가 다르면 같은 이름도 다른 객체다. 같은 이름 공간을 다시 저장하면 같은 행이다
        SourceSchema salesA = writer.saveSchema(projectId, pgA, Name.of("app"), Name.of("sales"));
        SourceTable ordersPublic = writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null));
        SourceTable ordersSales = writer.saveTable(projectId, salesA.getId(), new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null));
        assertThat(ordersPublic.getId()).isNotEqualTo(ordersSales.getId());
        assertThat(writer.saveSchema(projectId, pgA, Name.of("app"), Name.of("public")).getId()).isEqualTo(publicA.getId());
        // 카탈로그만 / 스키마만 있는 이름 공간 : 값이 뒤바뀐 조합과 섞이지 않는다
        assertThat(writer.saveSchema(projectId, pgA, Name.of("app"), null).getId()).isNotEqualTo(writer.saveSchema(projectId, pgA, null, Name.of("app")).getId());
        assertThatThrownBy(() -> writer.saveSchema(projectId, pgA, null, null)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CATALOG));

        // PostgreSQL 식별자 : 따옴표 식별자 "Users" 는 users 와 다른 객체, 따옴표 없는 USERS 는 users 와 같은 객체 (원본 이름은 준 그대로)
        SourceTable quoted = writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(new Name("Users", true), SourceTableType.TABLE, null));
        assertThat(quoted.getId()).isNotEqualTo(usersA.getId());
        SourceTable folded = writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(new Name("USERS", false), SourceTableType.TABLE, "원본 COMMENT : 사용자"));
        assertThat(folded.getId()).isEqualTo(usersA.getId());
        assertThat(tableRow(usersA.getId())).containsEntry("TBL_NM", "USERS").containsEntry("CMPR_NM", "users").containsEntry("QUOT_YN", false);
        assertThat(tableRow(quoted.getId())).containsEntry("TBL_NM", "Users").containsEntry("CMPR_NM", "Users").containsEntry("QUOT_YN", true);
        writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "원본 COMMENT : 사용자"));
        assertThat(tableRow(usersA.getId())).containsEntry("TBL_NM", "users");
        assertThat(tableRow(usersA.getId()).get("QUOT_YN")).isNull();

        // Oracle : 따옴표 없는 emp 는 EMP 와 같은 객체, 따옴표 식별자 "emp" 는 다른 객체
        Long ora = api.create(manager, request("ORA", "ORACLE", "ORCL")).getBody().id();
        SourceSchema hr = writer.saveSchema(projectId, ora, null, Name.of("HR"));
        SourceTable emp = writer.saveTable(projectId, hr.getId(), new SourceCatalogSpec.Table(Name.of("EMP"), SourceTableType.TABLE, null));
        assertThat(writer.saveTable(projectId, hr.getId(), new SourceCatalogSpec.Table(new Name("emp", false), SourceTableType.TABLE, null)).getId()).isEqualTo(emp.getId());
        assertThat(writer.saveTable(projectId, hr.getId(), new SourceCatalogSpec.Table(new Name("emp", true), SourceTableType.TABLE, null)).getId()).isNotEqualTo(emp.getId());
        assertThat(writer.saveSchema(projectId, ora, null, new Name("hr", false)).getId()).isEqualTo(hr.getId());

        // MySQL·MariaDB : 서버 설정을 모르면 Orders 와 orders 를 합치지 않는다. 컬럼명은 항상 대소문자를 구분하지 않는다. 구분하지 않는다고 정한 연결은 합친다
        Long my = api.create(manager, request("MY", "MYSQL", "shop")).getBody().id();
        SourceSchema shop = writer.saveSchema(projectId, my, Name.of("shop"), null);
        SourceTable upper = writer.saveTable(projectId, shop.getId(), new SourceCatalogSpec.Table(Name.of("Orders"), SourceTableType.TABLE, null));
        assertThat(writer.saveTable(projectId, shop.getId(), new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null)).getId()).isNotEqualTo(upper.getId());
        SourceColumn idColumn = writer.saveColumn(projectId, upper.getId(), column("ID", 1, "bigint", false));
        assertThat(writer.saveColumn(projectId, upper.getId(), column("id", 1, "bigint", false)).getId()).isEqualTo(idColumn.getId());
        Long maria = api.create(manager, new SourceConnectionDto.Request("MARIA", "MARIADB", "db.internal", null, "crm", null, "reader", null, null, "CASE_INSENSITIVE", null, null)).getBody().id();
        SourceSchema crm = writer.saveSchema(projectId, maria, Name.of("CRM"), null);
        assertThat(writer.saveSchema(projectId, maria, Name.of("crm"), null).getId()).isEqualTo(crm.getId());
        SourceTable customer = writer.saveTable(projectId, crm.getId(), new SourceCatalogSpec.Table(Name.of("Customer"), SourceTableType.TABLE, null));
        assertThat(writer.saveTable(projectId, crm.getId(), new SourceCatalogSpec.Table(Name.of("CUSTOMER"), SourceTableType.TABLE, null)).getId()).isEqualTo(customer.getId());
        // 카탈로그가 있는 연결은 DB 유형·비교 규칙을 바꿀 수 없다 (비교용 이름이 달라진다). 이름·설명은 바꿀 수 있다
        assertThatThrownBy(() -> api.update(manager, maria, new SourceConnectionDto.Request("MARIA", "MARIADB", "db.internal", null, "crm", null, "reader", null, null, "CASE_SENSITIVE", null, null))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CONNECTION));
        assertThatThrownBy(() -> api.update(manager, maria, new SourceConnectionDto.Request("MARIA", "MYSQL", "db.internal", null, "crm", null, "reader", null, null, null, null, null))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CONNECTION));
        assertThat(api.update(manager, maria, new SourceConnectionDto.Request("MARIA CRM", null, "db2.internal", null, "crm", null, "reader", null, null, null, "고객", null)).getBody().schemaCount()).isEqualTo(1);

        // 컬럼 : 넣은 순서와 상관없이 원본 순서대로. 타입 표현·길이·정밀도·NULL·기본값(긴 표현식)·COMMENT 를 그대로 둔다
        String longDefault = "nextval('public.users_id_seq'::regclass) /* " + "x".repeat(600) + " */";
        writer.saveColumn(projectId, usersA.getId(), new SourceCatalogSpec.Column(Name.of("email"), 3, "character varying(320)", 320, null, null, true, null, "이메일 주소"));
        writer.saveColumn(projectId, usersA.getId(), new SourceCatalogSpec.Column(Name.of("tenant_id"), 1, "bigint", null, new BigDecimal("64"), 0, false, null, null));
        writer.saveColumn(projectId, usersA.getId(), new SourceCatalogSpec.Column(Name.of("id"), 2, "bigint", null, new BigDecimal("64"), 0, false, longDefault, "사용자 ID"));
        writer.saveColumn(projectId, usersA.getId(), new SourceCatalogSpec.Column(Name.of("balance"), 4, "numeric(12,2)", null, new BigDecimal("12"), 2, false, "0", null));
        writer.saveColumn(projectId, usersA.getId(), new SourceCatalogSpec.Column(Name.of("tags"), 5, "text[]", null, null, null, true, "'{}'::text[]", null));
        for (int i = 0; i < 2; i++) {
            SourceTable table = i == 0 ? ordersPublic : ordersSales;
            writer.saveColumn(projectId, table.getId(), column("tenant_id", 1, "bigint", false));
            writer.saveColumn(projectId, table.getId(), column("id", 2, "bigint", false));
            writer.saveColumn(projectId, table.getId(), column("user_id", 3, "bigint", true));
            writer.saveColumn(projectId, table.getId(), column("status", 4, "character varying(20)", false));
        }
        // 복합 PK·UNIQUE·CHECK(1,000자를 넘는 조건식), 복합 인덱스, 복합 FK(같은 스키마 / 다른 스키마 / 카탈로그에 없는 테이블)
        String check = "status in (" + "'S" + "0".repeat(1100) + "', 'DONE')";
        writer.replaceConstraints(projectId, usersA.getId(), List.of(
                new SourceCatalogSpec.Constraint("users_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("tenant_id"), Name.of("id"))),
                new SourceCatalogSpec.Constraint("users_email_key", SourceConstraintType.UNIQUE, "무시되는 값", List.of(Name.of("email")))));
        writer.replaceConstraints(projectId, ordersSales.getId(), List.of(
                new SourceCatalogSpec.Constraint("orders_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("tenant_id"), Name.of("id"))),
                new SourceCatalogSpec.Constraint("orders_status_check", SourceConstraintType.CHECK, check, null)));
        writer.replaceIndexes(projectId, ordersSales.getId(), List.of(
                new SourceCatalogSpec.Index("ix_orders_user", "btree", false, List.of(Name.of("user_id"), Name.of("status"))),
                new SourceCatalogSpec.Index("ux_orders_status_id", "btree", true, List.of(Name.of("status"), Name.of("id")))));
        writer.replaceRelations(projectId, ordersSales.getId(), List.of(
                // 다른 스키마(public)의 테이블을 참조하는 복합 FK
                new SourceCatalogSpec.Relation("fk_orders_user", null, Name.of("public"), Name.of("users"), "CASCADE", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")), new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id")))),
                // 수집 범위 밖의 테이블 : 이어지지 않고 이름만 남는다
                new SourceCatalogSpec.Relation("fk_orders_region", null, Name.of("ref"), Name.of("regions"), null, null,
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("status"), Name.of("code"))))));
        writer.replaceRelations(projectId, ordersPublic.getId(), List.of(
                new SourceCatalogSpec.Relation("fk_orders_self", null, null, Name.of("orders"), "SET NULL", null,
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("tenant_id"), Name.of("tenant_id")), new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))))));
        // 뷰 : 객체와 컬럼만
        SourceTable view = writer.saveTable(projectId, salesA.getId(), new SourceCatalogSpec.Table(Name.of("v_open_orders"), SourceTableType.VIEW, "미완료 주문"));
        writer.saveColumn(projectId, view.getId(), column("id", 1, "bigint", true));

        // 조회 (VIEWER) : 목록은 쪽 단위, 유형·이름 조건
        AccountDto viewer = login(a.project, a.viewer, "ROLE_VIEWER");
        assertThat(page(catalog.schemas(viewer, pgA, 0, 50).getBody(), SourceCatalogDto.Schema.class)).extracting(SourceCatalogDto.Schema::catalogName, SourceCatalogDto.Schema::schemaName, SourceCatalogDto.Schema::tableCount)
                .contains(org.assertj.core.groups.Tuple.tuple("app", "public", 3L), org.assertj.core.groups.Tuple.tuple("app", "sales", 2L));
        assertThat(catalog.schemas(viewer, pgA, 0, 1).getBody()).containsEntry("recordsTotal", 4L).extractingByKey("data").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(page(catalog.tables(viewer, salesA.getId(), null, null, 0, 1000).getBody(), SourceCatalogDto.Table.class)).extracting(SourceCatalogDto.Table::name, SourceCatalogDto.Table::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("orders", "TABLE"), org.assertj.core.groups.Tuple.tuple("v_open_orders", "VIEW"));
        assertThat(page(catalog.tables(viewer, salesA.getId(), "view", null, 0, 50).getBody(), SourceCatalogDto.Table.class)).extracting(SourceCatalogDto.Table::name).containsExactly("v_open_orders");
        assertThat(page(catalog.tables(viewer, salesA.getId(), "TABLE", "ORD", 0, 50).getBody(), SourceCatalogDto.Table.class)).extracting(SourceCatalogDto.Table::name).containsExactly("orders");
        assertThat(page(catalog.tables(viewer, salesA.getId(), null, "open", 0, 50).getBody(), SourceCatalogDto.Table.class)).extracting(SourceCatalogDto.Table::comment).containsExactly("미완료 주문");

        SourceCatalogDto.TableDetail users = catalog.table(viewer, usersA.getId()).getBody();
        assertThat(users.table().comment()).isEqualTo("원본 COMMENT : 사용자");
        assertThat(users.schema().schemaName()).isEqualTo("public");
        assertThat(users.columns()).extracting(SourceCatalogDto.Column::name).containsExactly("tenant_id", "id", "email", "balance", "tags");
        SourceCatalogDto.Column id = users.columns().get(1);
        assertThat(id).extracting(SourceCatalogDto.Column::dataType, SourceCatalogDto.Column::nullable, SourceCatalogDto.Column::defaultValue, SourceCatalogDto.Column::comment, SourceCatalogDto.Column::scale)
                .containsExactly("bigint", false, longDefault, "사용자 ID", 0);
        assertThat(id.precision()).isEqualByComparingTo("64");
        assertThat(users.columns().get(2)).extracting(SourceCatalogDto.Column::dataType, SourceCatalogDto.Column::maxLength, SourceCatalogDto.Column::nullable).containsExactly("character varying(320)", 320, true);
        assertThat(users.columns().get(3)).extracting(SourceCatalogDto.Column::dataType, SourceCatalogDto.Column::scale).containsExactly("numeric(12,2)", 2);
        assertThat(users.columns().get(4).dataType()).isEqualTo("text[]");
        assertThat(users.constraints()).extracting(SourceCatalogDto.Constraint::name, SourceCatalogDto.Constraint::type, SourceCatalogDto.Constraint::columns, SourceCatalogDto.Constraint::checkExpression)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("users_pkey", "PRIMARY_KEY", List.of("tenant_id", "id"), null),
                        org.assertj.core.groups.Tuple.tuple("users_email_key", "UNIQUE", List.of("email"), null));
        // 다른 스키마의 테이블이 이 테이블을 참조한다
        assertThat(users.referencedBy()).extracting(SourceCatalogDto.Relation::name, SourceCatalogDto.Relation::tableId).containsExactly(org.assertj.core.groups.Tuple.tuple("fk_orders_user", ordersSales.getId()));

        SourceCatalogDto.TableDetail orders = catalog.table(viewer, ordersSales.getId()).getBody();
        assertThat(orders.constraints()).filteredOn(c -> c.type().equals("CHECK")).singleElement().satisfies(c -> {
            assertThat(c.checkExpression()).isEqualTo(check).hasSizeGreaterThan(1000);
            assertThat(c.columns()).isEmpty();
        });
        assertThat(orders.indexes()).extracting(SourceCatalogDto.Index::name, SourceCatalogDto.Index::indexType, SourceCatalogDto.Index::unique, SourceCatalogDto.Index::columns)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ix_orders_user", "btree", false, List.of("user_id", "status")),
                        org.assertj.core.groups.Tuple.tuple("ux_orders_status_id", "btree", true, List.of("status", "id")));
        SourceCatalogDto.Relation toUsers = orders.relations().get(0);
        assertThat(toUsers).extracting(SourceCatalogDto.Relation::name, SourceCatalogDto.Relation::referencedTableId, SourceCatalogDto.Relation::referencedSchemaName,
                SourceCatalogDto.Relation::referencedTableName, SourceCatalogDto.Relation::deleteRule, SourceCatalogDto.Relation::updateRule)
                .containsExactly("fk_orders_user", usersA.getId(), "public", "users", "CASCADE", "NO ACTION");
        assertThat(toUsers.columns()).extracting(SourceCatalogDto.ColumnPair::columnName, SourceCatalogDto.ColumnPair::referencedColumnName, pair -> pair.referencedColumnId() != null)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("tenant_id", "tenant_id", true), org.assertj.core.groups.Tuple.tuple("user_id", "id", true));
        SourceCatalogDto.Relation toRegions = orders.relations().get(1);
        assertThat(toRegions).extracting(SourceCatalogDto.Relation::referencedTableId, SourceCatalogDto.Relation::referencedSchemaName, SourceCatalogDto.Relation::referencedTableName).containsExactly(null, "ref", "regions");
        assertThat(toRegions.columns()).extracting(SourceCatalogDto.ColumnPair::referencedColumnId, SourceCatalogDto.ColumnPair::referencedColumnName).containsExactly(org.assertj.core.groups.Tuple.tuple(null, "code"));
        // 같은 스키마의 자기 참조 : 같은 이름의 sales.orders 가 아니라 public.orders 로 이어진다
        assertThat(catalog.table(viewer, ordersPublic.getId()).getBody().relations()).singleElement().extracting(SourceCatalogDto.Relation::referencedTableId, SourceCatalogDto.Relation::referencedSchemaName).containsExactly(ordersPublic.getId(), "public");
        assertThat(catalog.table(viewer, view.getId()).getBody()).satisfies(detail -> {
            assertThat(detail.table().type()).isEqualTo("VIEW");
            assertThat(detail.columns()).hasSize(1);
        });

        // 통째로 바꾸기 : 다시 저장하면 지금 구성만 남는다. 없는 컬럼·다른 테이블의 컬럼은 받지 않는다
        writer.replaceIndexes(projectId, ordersSales.getId(), List.of(new SourceCatalogSpec.Index("ix_orders_user", "btree", false, List.of(Name.of("user_id")))));
        assertThat(catalog.table(viewer, ordersSales.getId()).getBody().indexes()).singleElement().extracting(SourceCatalogDto.Index::columns).isEqualTo(List.of("user_id"));
        assertThatThrownBy(() -> writer.replaceIndexes(projectId, ordersSales.getId(), List.of(new SourceCatalogSpec.Index("bad", null, false, List.of(Name.of("email"))))))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CATALOG));
        assertThat(catalog.table(viewer, ordersSales.getId()).getBody().indexes()).as("실패한 교체는 이전 구성을 지우지 않는다").hasSize(1);
        assertThatThrownBy(() -> writer.replaceConstraints(projectId, usersA.getId(), List.of(new SourceCatalogSpec.Constraint("pk", SourceConstraintType.PRIMARY_KEY, null, List.of()))))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CATALOG));
        assertThatThrownBy(() -> writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(Name.of("x".repeat(256)), SourceTableType.TABLE, null)))
                .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.INVALID_SOURCE_CATALOG));

        // 원본 COMMENT 가 바뀌어도 물리 모델의 업무 설명은 그대로이고, 모델 변경 이력도 생기지 않는다 (원본 카탈로그는 사용자의 모델이 아니다)
        writer.saveTable(projectId, publicA.getId(), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "바뀐 원본 COMMENT"));
        assertThat(catalog.table(viewer, usersA.getId()).getBody().table().comment()).isEqualTo("바뀐 원본 COMMENT");
        assertThat(jdbc.queryForObject("select TBL_EXPLN from TB_TBL where TBL_SN = ?", String.class, physical.getId())).isEqualTo("회원 업무 설명");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ?", Integer.class, projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where PRJ_SN = ?", Integer.class, projectId)).isZero();
        assertThat(historyRepository.count()).isEqualTo(historyBefore);

        // 격리 : 다른 프로젝트에서는 ID 를 알아도 볼 수 없고, 쓰는 쪽도 그 프로젝트의 것만 찾는다
        Actors b = actors("ai1b-cat-other");
        AccountDto outsider = login(b.project, b.manager, "ROLE_DBA");
        assertThatThrownBy(() -> catalog.table(outsider, usersA.getId())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> catalog.tables(outsider, publicA.getId(), null, null, 0, 10)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> catalog.schemas(outsider, pgA, 0, 10)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        long other = b.project.getId();
        assertThatThrownBy(() -> writer.saveSchema(other, pgA, null, Name.of("x"))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        assertThatThrownBy(() -> writer.saveTable(other, publicA.getId(), new SourceCatalogSpec.Table(Name.of("x"), SourceTableType.TABLE, null))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> writer.saveColumn(other, usersA.getId(), column("x", 1, "int", true))).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> writer.replaceRelations(other, usersA.getId(), List.of())).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        // 미승인 사용자는 조회할 수 없다
        AccountDto pending = login(a.project, a.viewer, "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> catalog.table(pending, usersA.getId())).isInstanceOf(AccessDeniedException.class);

        // DB 가 막는 것 : 다른 연결의 테이블을 잇는 관계, 같은 스키마의 같은 비교용 이름, 같은 연결의 같은 경로
        assertThatThrownBy(() -> jdbc.update("insert into TB_SRC_REL(SRC_REL_SN, PRJ_SN, SRC_CNTN_SN, SRC_TBL_SN, RFRNC_SRC_TBL_SN, RFRNC_TBL_NM) values(-9001, ?, ?, ?, ?, 'users')",
                projectId, pgA, usersA.getId(), usersB.getId())).as("다른 연결의 테이블을 참조하는 관계").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into TB_SRC_TBL(SRC_TBL_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, TBL_NM, CMPR_NM, TBL_TYPE_CD, EXST_YN) values(-9002, ?, ?, ?, 'x', 'x', 'TABLE', true)",
                projectId, pgB, publicA.getId())).as("다른 연결의 스키마에 넣는 테이블").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into TB_SRC_TBL(SRC_TBL_SN, PRJ_SN, SRC_CNTN_SN, SRC_SCHM_SN, TBL_NM, CMPR_NM, TBL_TYPE_CD, EXST_YN) values(-9003, ?, ?, ?, 'USERS', 'users', 'VIEW', true)",
                projectId, pgA, publicA.getId())).as("같은 스키마의 같은 비교용 이름 (테이블과 뷰는 이름 공간이 같다)").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into TB_SRC_SCHM(SRC_SCHM_SN, PRJ_SN, SRC_CNTN_SN, SCHM_NM, PATH_CN, EXST_YN) values(-9004, ?, ?, 'public', ?, true)",
                projectId, pgA, publicA.getPathKey())).as("같은 연결의 같은 경로").isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ 삭제 정합성

    @Test
    @DisplayName("삭제 : 연결을 지우면 그 연결의 카탈로그만 지워진다 (다른 연결·물리 모델은 그대로). 도중에 실패하면 전부 되돌아간다")
    void deleteConnection() {
        Actors a = actors("ai1b-del");
        long projectId = a.project.getId();
        AccountDto manager = login(a.project, a.manager, "ROLE_DBA");
        Long first = api.create(manager, request("첫째", "POSTGRESQL", "one")).getBody().id();
        Long second = api.create(manager, request("둘째", "POSTGRESQL", "two")).getBody().id();
        SourceColumn firstUserId = load(projectId, first);
        load(projectId, second);
        TableInfo physical = tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName("users").isApproval(false).isMasterTable(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
        Map<String, Integer> before = catalogCounts(first);
        assertThat(before.values()).as("연결의 모든 카탈로그 테이블에 행이 있다").allMatch(count -> count > 0);
        Map<String, Integer> secondBefore = catalogCounts(second);

        // 삭제 도중 실패 : 둘째 연결의 관계 컬럼이 첫째 연결의 컬럼을 가리키는 (있어서는 안 되는) 행 → 컬럼을 지우는 단계에서 FK 로 실패한다
        Long strayRelation = jdbc.queryForObject("select min(SRC_REL_SN) from TB_SRC_REL where SRC_CNTN_SN = ?", Long.class, second);
        Long strayColumn = jdbc.queryForObject("select min(c.SRC_COL_SN) from TB_SRC_COL c join TB_SRC_TBL t on t.SRC_TBL_SN = c.SRC_TBL_SN where t.SRC_CNTN_SN = ?", Long.class, second);
        jdbc.update("insert into TB_SRC_REL_COL(SRC_REL_COL_SN, SRC_REL_SN, SRC_COL_SN, RFRNC_SRC_COL_SN, RFRNC_COL_NM, COL_ORD) values(-9100, ?, ?, ?, 'id', 9)", strayRelation, strayColumn, firstUserId.getId());
        assertThatThrownBy(() -> api.delete(manager, first)).isInstanceOf(DataIntegrityViolationException.class);
        // 앞 단계에서 지운 관계·인덱스·제약조건까지 모두 되돌아갔다
        assertThat(catalogCounts(first)).isEqualTo(before);
        assertThat(api.detail(manager, first).getBody().schemaCount()).isEqualTo(1);
        jdbc.update("delete from TB_SRC_REL_COL where SRC_REL_COL_SN = -9100");

        // 삭제 : 그 연결의 카탈로그가 모두 지워지고 지운 행 수를 알려 준다
        Map<String, Object> result = api.delete(manager, first).getBody();
        assertThat(result).containsEntry("result", true);
        assertThat((Integer) result.get("deletedCatalogRows")).isEqualTo(before.values().stream().mapToInt(Integer::intValue).sum());
        assertThat(catalogCounts(first).values()).allMatch(count -> count == 0);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CNTN where SRC_CNTN_SN = ?", Integer.class, first)).isZero();
        assertThatThrownBy(() -> api.detail(manager, first)).isInstanceOfSatisfying(CustomException.class, code(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        // 다른 연결의 카탈로그, 물리 모델은 그대로다
        assertThat(catalogCounts(second)).isEqualTo(secondBefore);
        assertThat(tableInfoRepository.findById(physical.getId())).isPresent();
        // 주인 없는 행이 없다
        assertThat(orphans()).isZero();
    }

    @Test
    @DisplayName("프로젝트 영구 삭제·비활성화 : 영구 삭제는 (lexorma-source 의 이벤트 리스너가) 연결과 카탈로그를 모두 지우고 다른 프로젝트는 남긴다. 삭제와 겹친 등록(JPA·JDBC)은 커밋되지 않아 주인 없는 데이터가 남지 않는다")
    void projectDeletion() throws Exception {
        Actors keep = actors("ai1b-keep");
        login(keep.project, keep.manager, "ROLE_DBA");
        Long kept = api.create(keep.managerDto(), request("남는 연결", "POSTGRESQL", "keep")).getBody().id();
        SourceColumn keptUserId = load(keep.project.getId(), kept);
        Map<String, Integer> keptBefore = catalogCounts(kept);

        Account admin = admin();
        Project target = soleProject("ai1b-gone", admin);
        AccountDto owner = login(target, admin, "ROLE_ADMIN");
        Long gone = api.create(owner, request("지워질 연결", "POSTGRESQL", "gone")).getBody().id();
        SourceColumn goneUserId = load(target.getId(), gone);
        Map<String, Integer> goneBefore = catalogCounts(gone);

        // 비활성화 : 아무것도 지우지 않는다
        tx.executeWithoutResult(s -> projectRepository.findById(target.getId()).orElseThrow().setIsActive(false));
        assertThat(catalogCounts(gone)).isEqualTo(goneBefore);
        tx.executeWithoutResult(s -> projectRepository.findById(target.getId()).orElseThrow().setIsActive(true));

        // 원본 카탈로그 정리가 실패하면 프로젝트 삭제 전체가 롤백된다 : 다른 프로젝트의 제약조건 컬럼이 이 프로젝트의 원본 컬럼을 가리키게 해 두면
        // (있어서는 안 되는 데이터) 리스너가 TB_SRC_COL 을 지우는 단계에서 FK 로 막힌다 → 앞 단계에서 지운 관계·인덱스·제약조건, 다른 모듈이 지운 데이터,
        // 프로젝트와 멤버가 모두 그대로다 (리스너가 도는 순서와 상관없다)
        long word = -(target.getId() * 100) - 77;
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM) values(?, ?, 1, '원본', 'SRC')", word, target.getId());
        String block = "update TB_SRC_CNSTRT_COL set SRC_COL_SN = ? where SRC_CNSTRT_SN in (select SRC_CNSTRT_SN from TB_SRC_CNSTRT where PRJ_SN = ?)";
        assertThat(jdbc.update(block, goneUserId.getId(), keep.project.getId())).isEqualTo(1);
        Map<String, Integer> keptBlocked = catalogCounts(kept);
        assertThatThrownBy(() -> projectManageService.deleteProject(target.getId(), owner)).isInstanceOf(DataAccessException.class);
        assertThat(projectRepository.findById(target.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from TB_PRJ_MBR where PRJ_SN = ?", Integer.class, target.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CNTN where PRJ_SN = ?", Integer.class, target.getId())).isEqualTo(1);
        assertThat(catalogCounts(gone)).isEqualTo(goneBefore);
        assertThat(catalogCounts(kept)).isEqualTo(keptBlocked);
        assertThat(jdbc.queryForObject("select count(*) from TB_STD_WORD where STD_WORD_SN = ?", Integer.class, word)).isEqualTo(1);
        jdbc.update(block, keptUserId.getId(), keep.project.getId());

        // 영구 삭제
        projectManageService.deleteProject(target.getId(), owner);
        for (String table : List.of("TB_SRC_CNTN", "TB_SRC_SCHM", "TB_SRC_TBL", "TB_SRC_COL", "TB_SRC_CNSTRT", "TB_SRC_IDX", "TB_SRC_REL")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, target.getId())).as(table).isZero();
        }
        assertThat(catalogCounts(gone).values()).allMatch(count -> count == 0);
        assertThat(catalogCounts(kept)).isEqualTo(keptBefore);
        assertThat(jdbc.queryForObject("select count(*) from TB_STD_WORD where STD_WORD_SN = ?", Integer.class, word)).isZero();
        assertThat(orphans()).isZero();
        // 같은 삭제 이벤트를 한 번 더 처리해도 지울 것이 없고 다른 프로젝트는 그대로다 (프로젝트 ID 로만 지운다)
        tx.executeWithoutResult(s -> cleanup.onProjectDeleted(new ProjectDeletedEvent(target.getId())));
        assertThat(catalogCounts(kept)).isEqualTo(keptBefore);
        assertThat(connectionCount(keep.project.getId())).isEqualTo(1);

        // 동시성 : 등록 트랜잭션이 저장은 했지만 아직 커밋하지 않은 사이에 프로젝트가 지워진다 → 등록의 커밋이 실패한다
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (boolean jdbcInsert : new boolean[]{false, true}) {
                Project racing = soleProject("ai1b-race", admin);
                AccountDto racer = login(racing, admin, "ROLE_ADMIN");
                long projectId = racing.getId();
                CountDownLatch inserted = new CountDownLatch(1);
                CountDownLatch projectGone = new CountDownLatch(1);
                Future<Throwable> registration = pool.submit(() -> {
                    try {
                        tx.executeWithoutResult(s -> {
                            if (jdbcInsert) {
                                // JDBC 로 직접 넣는 적재 : ProjectWriteGuard 가 저장을 보지 못하므로 직접 알린다
                                jdbc.update("insert into TB_SRC_CNTN(SRC_CNTN_SN, PRJ_SN, CNTN_NM, DB_TYPE_CD, HOST_NM, PORT_NO, DB_NM, DB_NM_TYPE_CD, CNTN_USER_NM, IDNTFR_CMPR_CD, ACTVTN_YN)"
                                        + " values(?, ?, 'jdbc', 'POSTGRESQL', 'db.internal', 5432, 'app', 'DATABASE', 'reader', 'DBMS_DEFAULT', true)", -projectId, projectId);
                                projectWriteGuard.guard(projectId);
                            } else {
                                SecurityTestSupport.loginAs(projectRepository, projectId, String.valueOf(admin.getId()), "ROLE_ADMIN");
                                api.create(racer, request("경합", "POSTGRESQL", "race"));
                            }
                            inserted.countDown();
                            try {
                                projectGone.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                            }
                        });
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                });
                assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
                projectManageService.deleteProject(projectId, racer);
                projectGone.countDown();
                assertThat(registration.get(30, TimeUnit.SECONDS)).as(jdbcInsert ? "JDBC 적재" : "JPA 등록")
                        .isInstanceOfSatisfying(CustomException.class, code(ErrorCode.PROJECT_NOT_FOUND));
                assertThat(jdbc.queryForObject("select count(*) from TB_SRC_CNTN where PRJ_SN = ?", Integer.class, projectId)).isZero();
                assertThat(projectRepository.findById(projectId)).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
        // 쓰기 트랜잭션 밖에서는 직접 알릴 수 없다 (보호 없이 적재하는 실수를 막는다)
        assertThatThrownBy(() -> projectWriteGuard.guard(keep.project.getId())).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ 준비

    private record Actors(Project project, Account admin, Account manager, Account member, Account viewer) {
        AccountDto adminDto() {
            return dto(admin, project);
        }

        AccountDto managerDto() {
            return dto(manager, project);
        }
    }

    private static AccountDto dto(Account account, Project project) {
        return AccountDto.builder().id(String.valueOf(account.getId())).username(account.getUsername()).name(account.getName()).projectId(project.getId()).build();
    }

    /** 프로젝트 하나와 사람들 : 관리자(프로젝트의 관리자 계정), 멤버, 조회 사용자, 그리고 멤버로 들어온 시스템 ADMIN */
    private Actors actors(String projectName) {
        Account admin = admin();
        Account manager = account("mgr", "ROLE_DBA");
        Account member = account("usr", "ROLE_USER");
        Account viewer = account("vwr", "ROLE_VIEWER");
        Project project = projectRepository.save(Project.builder().projectName(unique(projectName)).isActive(true).account(manager).dbType(DbType.POSTGRESQL).build());
        for (Account account : List.of(manager, member, viewer, admin)) projectMemberRepository.save(ProjectMember.builder().project(project).account(account).isActive(true).build());
        return new Actors(project, admin, manager, member, viewer);
    }

    /** 관리자 한 명만 멤버인 프로젝트 (영구 삭제할 수 있는 상태) */
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

    /** 세션의 역할을 정해 로그인한다 (컨트롤러의 @PreAuthorize 가 보는 값). 서비스의 재검증은 DB 의 역할·멤버·관리자를 본다 */
    private AccountDto login(Project project, Account account, String role) {
        // DB 의 역할도 같게 맞춘다 : 세션과 DB 가 다른 경우는 ExecutionContextIntegrationTest 가 본다
        tx.executeWithoutResult(s -> {
            Account found = accountRepository.findById(account.getId()).orElseThrow();
            if (found.getUserRoles().stream().noneMatch(r -> r.getRoleName().equals(role))) {
                found.getUserRoles().clear();
                found.getUserRoles().add(roleRepository.findByRoleName(role));
            }
        });
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(account.getId()), role);
    }

    /** 비밀번호 없는 연결 요청 (DBMS 기본 포트) */
    private static SourceConnectionDto.Request request(String name, String dbType, String databaseName) {
        return new SourceConnectionDto.Request(name, dbType, "db.internal", null, databaseName, null, "reader", null, null, null, null, null);
    }

    private long connectionCount(Long projectId) {
        return jdbc.queryForObject("select count(*) from TB_SRC_CNTN where PRJ_SN = ?", Long.class, projectId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static SourceCatalogSpec.Column column(String name, int ordinal, String dataType, boolean nullable) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, dataType, null, null, null, nullable, null, null);
    }

    /** 연결 하나에 카탈로그의 모든 종류를 채운다 : 스키마, 테이블 2, 컬럼, PK, 인덱스, FK. users.id 컬럼을 돌려준다 */
    private SourceColumn load(long projectId, Long connectionId) {
        SourceSchema schema = writer.saveSchema(projectId, connectionId, Name.of("app"), Name.of("public"));
        SourceTable users = writer.saveTable(projectId, schema.getId(), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "사용자"));
        SourceColumn userId = writer.saveColumn(projectId, users.getId(), column("id", 1, "bigint", false));
        SourceTable orders = writer.saveTable(projectId, schema.getId(), new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null));
        writer.saveColumn(projectId, orders.getId(), column("id", 1, "bigint", false));
        writer.saveColumn(projectId, orders.getId(), column("user_id", 2, "bigint", true));
        writer.replaceConstraints(projectId, users.getId(), List.of(new SourceCatalogSpec.Constraint("users_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))));
        writer.replaceIndexes(projectId, orders.getId(), List.of(new SourceCatalogSpec.Index("ix_orders_user", "btree", false, List.of(Name.of("user_id")))));
        writer.replaceRelations(projectId, orders.getId(), List.of(new SourceCatalogSpec.Relation("fk_orders_user", null, null, Name.of("users"), "CASCADE", null,
                List.of(new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))))));
        return userId;
    }

    /** 연결에 딸린 카탈로그 테이블별 행 수 */
    private Map<String, Integer> catalogCounts(Long connectionId) {
        String tables = "(select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ?)";
        Map<String, String> queries = new java.util.LinkedHashMap<>();
        queries.put("TB_SRC_SCHM", "select count(*) from TB_SRC_SCHM where SRC_CNTN_SN = ?");
        queries.put("TB_SRC_TBL", "select count(*) from TB_SRC_TBL where SRC_CNTN_SN = ?");
        queries.put("TB_SRC_COL", "select count(*) from TB_SRC_COL where SRC_TBL_SN in " + tables);
        queries.put("TB_SRC_CNSTRT", "select count(*) from TB_SRC_CNSTRT where SRC_TBL_SN in " + tables);
        queries.put("TB_SRC_CNSTRT_COL", "select count(*) from TB_SRC_CNSTRT_COL where SRC_CNSTRT_SN in (select SRC_CNSTRT_SN from TB_SRC_CNSTRT where SRC_TBL_SN in " + tables + ")");
        queries.put("TB_SRC_IDX", "select count(*) from TB_SRC_IDX where SRC_TBL_SN in " + tables);
        queries.put("TB_SRC_IDX_COL", "select count(*) from TB_SRC_IDX_COL where SRC_IDX_SN in (select SRC_IDX_SN from TB_SRC_IDX where SRC_TBL_SN in " + tables + ")");
        queries.put("TB_SRC_REL", "select count(*) from TB_SRC_REL where SRC_CNTN_SN = ?");
        queries.put("TB_SRC_REL_COL", "select count(*) from TB_SRC_REL_COL where SRC_REL_SN in (select SRC_REL_SN from TB_SRC_REL where SRC_CNTN_SN = ?)");
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        queries.forEach((table, sql) -> counts.put(table, jdbc.queryForObject(sql, Integer.class, connectionId)));
        return counts;
    }

    /** 부모가 없는 카탈로그 행 수 (프로젝트 없는 연결, 연결 없는 스키마·테이블 …) */
    private int orphans() {
        int count = 0;
        for (String sql : List.of(
                "select count(*) from TB_SRC_CNTN c where not exists (select 1 from TB_PRJ p where p.PRJ_SN = c.PRJ_SN)",
                "select count(*) from TB_SRC_SCHM s where not exists (select 1 from TB_SRC_CNTN c where c.SRC_CNTN_SN = s.SRC_CNTN_SN and c.PRJ_SN = s.PRJ_SN)",
                "select count(*) from TB_SRC_TBL t where not exists (select 1 from TB_SRC_SCHM s where s.SRC_SCHM_SN = t.SRC_SCHM_SN and s.PRJ_SN = t.PRJ_SN)",
                "select count(*) from TB_SRC_COL c where not exists (select 1 from TB_SRC_TBL t where t.SRC_TBL_SN = c.SRC_TBL_SN and t.PRJ_SN = c.PRJ_SN)",
                "select count(*) from TB_SRC_CNSTRT x where not exists (select 1 from TB_SRC_TBL t where t.SRC_TBL_SN = x.SRC_TBL_SN and t.PRJ_SN = x.PRJ_SN)",
                "select count(*) from TB_SRC_IDX x where not exists (select 1 from TB_SRC_TBL t where t.SRC_TBL_SN = x.SRC_TBL_SN and t.PRJ_SN = x.PRJ_SN)",
                "select count(*) from TB_SRC_REL x where not exists (select 1 from TB_SRC_TBL t where t.SRC_TBL_SN = x.SRC_TBL_SN and t.PRJ_SN = x.PRJ_SN)")) {
            count += jdbc.queryForObject(sql, Integer.class);
        }
        return count;
    }

    private String passwordColumn(Long connectionId) {
        return jdbc.queryForObject("select CNTN_PSWD_CN from TB_SRC_CNTN where SRC_CNTN_SN = ?", String.class, connectionId);
    }

    private Map<String, Object> tableRow(Long tableId) {
        Map<String, Object> row = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        row.putAll(jdbc.queryForMap("select TBL_NM, CMPR_NM, QUOT_YN from TB_SRC_TBL where SRC_TBL_SN = ?", tableId));
        return row;
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("select upper(column_name) from information_schema.columns where table_schema = current_schema and upper(table_name) = ?", String.class, table);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> page(Map<String, Object> body, Class<T> type) {
        return (List<T>) body.get("data");
    }

    private static Consumer<CustomException> code(ErrorCode expected) {
        return e -> assertThat(e.getErrorCode()).isEqualTo(expected);
    }
}
