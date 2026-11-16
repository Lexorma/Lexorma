package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
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
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.controller.BizDescriptionRestController;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.BizDescriptionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업무 설명 직접 편집(`BizDescriptionService`)의 트랜잭션 안전성 (AI-1d 후속).
 * <p>
 * 예전에는 테이블 행을 잠근 뒤 {@code refresh(table)} 로 다시 읽어, {@code TableInfo.columns}(cascade ALL)를 따라 같은 트랜잭션에서
 * 아직 flush 하지 않은 컬럼 변경까지 DB 값으로 되돌렸다. 이제 행 잠금은 그대로 두고 비교에 쓰는 업무 설명만 DB 에서 다시 읽는다.
 * 모든 결과는 트랜잭션이 끝난 뒤 JDBC 로 DB 를 다시 읽어 확인한다 (영속성 컨텍스트 값이 아니라).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BizDescriptionTransactionIntegrationTest {

    @Autowired BizDescriptionService bizDescriptions;
    @Autowired BizDescriptionRestController bizDescriptionApi;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private Account owner;
    private Account viewer;
    private Project project;
    private TableInfo table;
    private ColumnInfo account;
    private ColumnInfo email;

    @BeforeEach
    void setUp() {
        owner = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        project = projectRepository.save(Project.builder().projectName("ai1dt-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        for (Account a : List.of(owner, viewer)) projectMemberRepository.save(ProjectMember.builder().project(project).account(a).isActive(true).build());
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            table = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_MBR").tableKorName("회원").tableDescription("회원 테이블")
                    .bizDescription("서비스에 가입한 회원").isApproval(false).isMasterTable(false).build());
            account = column("LGN_ACNT", "로그인 계정", 1, "사용자의 로그인 계정");
            email = column("EML_ADDR", "이메일 주소", 2, "알림을 받는 주소");
        });
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("TX-01 같은 트랜잭션에서 컬럼 → 테이블 업무 설명 수정 : 커밋 뒤 DB 에 둘 다 남는다 (예전에는 테이블 refresh 가 컬럼 변경을 되돌렸다)")
    void columnThenTableInOneTransaction() {
        AccountDto u = login();
        tx.executeWithoutResult(s -> {
            bizDescriptions.update(u, request(account, "컬럼 새 설명", "사용자의 로그인 계정"));
            bizDescriptions.update(u, request(null, "테이블 새 설명", "서비스에 가입한 회원"));
        });
        assertThat(dbColumn(account)).isEqualTo("컬럼 새 설명");
        assertThat(dbTable()).isEqualTo("테이블 새 설명");
    }

    @Test
    @DisplayName("TX-02 같은 트랜잭션에서 테이블 → 컬럼 : 둘 다 남는다")
    void tableThenColumnInOneTransaction() {
        AccountDto u = login();
        tx.executeWithoutResult(s -> {
            bizDescriptions.update(u, request(null, "테이블 새 설명", "서비스에 가입한 회원"));
            bizDescriptions.update(u, request(account, "컬럼 새 설명", "사용자의 로그인 계정"));
        });
        assertThat(dbColumn(account)).isEqualTo("컬럼 새 설명");
        assertThat(dbTable()).isEqualTo("테이블 새 설명");
    }

    @Test
    @DisplayName("TX-03 서로 다른 트랜잭션에서 차례로 : 모두 저장, 이력 출처 USER")
    void separateTransactions() {
        AccountDto u = login();
        int before = jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ?", Integer.class, account.getId());
        bizDescriptions.update(u, request(account, "첫 번째", "사용자의 로그인 계정"));
        bizDescriptions.update(u, request(null, "테이블 첫 번째", "서비스에 가입한 회원"));
        bizDescriptions.update(u, request(account, "두 번째", "첫 번째"));
        assertThat(dbColumn(account)).isEqualTo("두 번째");
        assertThat(dbTable()).isEqualTo("테이블 첫 번째");
        List<String> sources = jdbc.queryForList("select CHG_SRC_CD from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ? order by MDL_CHG_HSTRY_SN", String.class, account.getId());
        assertThat(sources).hasSize(before + 2);
        assertThat(sources.subList(before, sources.size())).containsOnly("USER");
    }

    @Test
    @DisplayName("TX-04 같은 업무 설명 다시 저장 (앞뒤 공백만 다름 포함) : UPDATE·이력·수정 일시 변화 없음")
    void sameValueIsNoOp() {
        AccountDto u = login();
        int histories = historyCount();
        Timestamp columnModified = modified("TB_COL", "COL_SN", account.getId());
        Timestamp tableModified = modified("TB_TBL", "TBL_SN", table.getId());
        bizDescriptions.update(u, request(account, "  사용자의 로그인 계정 ", "사용자의 로그인 계정"));
        bizDescriptions.update(u, request(null, "서비스에 가입한 회원", "서비스에 가입한 회원"));
        assertThat(historyCount()).isEqualTo(histories);
        assertThat(modified("TB_COL", "COL_SN", account.getId())).isEqualTo(columnModified);
        assertThat(modified("TB_TBL", "TBL_SN", table.getId())).isEqualTo(tableModified);
        assertThat(dbColumn(account)).isEqualTo("사용자의 로그인 계정");
    }

    @Test
    @DisplayName("TX-05 컬럼 업무 설명 변경 + 다른 컬럼의 반영 전 수정이 있는 트랜잭션에서 테이블 업무 설명 수정 : 다른 컬럼의 값도 모두 남는다")
    void otherPendingColumnChangesSurvive() {
        AccountDto u = login();
        tx.executeWithoutResult(s -> {
            // 같은 테이블의 컬럼 목록을 읽고(영속성 컨텍스트에 올라옴) 다른 컬럼을 flush 없이 고친다
            TableInfo loaded = tableRepository.findById(table.getId()).orElseThrow();
            loaded.getColumns().stream().filter(c -> c.getId().equals(email.getId())).findFirst().orElseThrow().setColumnDesc("이메일 주소 (수정)");
            bizDescriptions.update(u, request(account, "컬럼 새 설명", "사용자의 로그인 계정"));
            bizDescriptions.update(u, request(null, "테이블 새 설명", "서비스에 가입한 회원"));
        });
        assertThat(jdbc.queryForObject("select COL_EXPLN from TB_COL where COL_SN = ?", String.class, email.getId())).isEqualTo("이메일 주소 (수정)");
        assertThat(dbColumn(email)).isEqualTo("알림을 받는 주소");
        assertThat(dbColumn(account)).isEqualTo("컬럼 새 설명");
        assertThat(dbTable()).isEqualTo("테이블 새 설명");
    }

    @Test
    @DisplayName("최신 값 재조회 : 비교 값은 영속성 컨텍스트가 아니라 DB 에서 읽는다 — 먼저 읽어 둔 엔터티가 그 사이 낡았으면 쓰지 않고 충돌, 같은 트랜잭션의 반영 전 변경은 최신 값으로 본다")
    void comparesAgainstDatabaseNotPersistenceContext() throws Exception {
        AccountDto u = login();
        // 이 트랜잭션이 테이블을 먼저 읽어 둔 뒤(값 X), 다른 트랜잭션이 Y 로 바꿔 커밋
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            TableInfo stale = tableRepository.findById(table.getId()).orElseThrow();
            assertThat(stale.getBizDescription()).isEqualTo("서비스에 가입한 회원");
            runElsewhere(() -> bizDescriptions.update(login(), request(null, "다른 사용자의 설명", "서비스에 가입한 회원")));
            // 화면이 X 를 읽고 Z 로 고치려 함 : DB 의 Y 와 다르므로 충돌 (엔터티의 X 로 판단했다면 통과했을 것)
            bizDescriptions.update(u, request(null, "Z", "서비스에 가입한 회원"));
        })).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        assertThat(dbTable()).isEqualTo("다른 사용자의 설명");

        // 낡은 엔터티 + DB 의 최신 값을 기준으로 엔터티가 가진 옛 값으로 되돌리려는 요청 : 더티 체킹이 바뀐 것으로 보지 않아 저장이 빠질 수 있으므로 충돌로 거부
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            TableInfo stale = tableRepository.findById(table.getId()).orElseThrow();
            runElsewhere(() -> bizDescriptions.update(login(), request(null, "세 번째 설명", "다른 사용자의 설명")));
            bizDescriptions.update(u, request(null, stale.getBizDescription(), "세 번째 설명"));
        })).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        assertThat(dbTable()).isEqualTo("세 번째 설명");

        // 낡은 엔터티 + 새 값 : 변경 이력의 변경 전 값이 낡은 엔터티 기준이 되므로 역시 충돌 (값은 다른 트랜잭션의 것 그대로)
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            tableRepository.findById(table.getId()).orElseThrow();
            runElsewhere(() -> bizDescriptions.update(login(), request(null, "네 번째 설명", "세 번째 설명")));
            bizDescriptions.update(u, request(null, "다섯 번째 설명", "네 번째 설명"));
        })).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        assertThat(dbTable()).isEqualTo("네 번째 설명");
        // 낡지 않은(먼저 읽었어도 그 사이 바뀌지 않은) 엔터티 : 정상 저장
        tx.executeWithoutResult(s -> {
            tableRepository.findById(table.getId()).orElseThrow();
            bizDescriptions.update(u, request(null, "다섯 번째 설명", "네 번째 설명"));
        });
        assertThat(dbTable()).isEqualTo("다섯 번째 설명");

        // 같은 트랜잭션의 반영 전 업무 설명 변경은 그 트랜잭션의 최신 값으로 본다 (자동 flush 뒤 DB 값)
        tx.executeWithoutResult(s -> {
            tableRepository.findById(table.getId()).orElseThrow().setBizDescription("반영 전 값");
            bizDescriptions.update(u, request(null, "그 다음 값", "반영 전 값"));
        });
        assertThat(dbTable()).isEqualTo("그 다음 값");
    }

    @Test
    @DisplayName("TX-06 다른 트랜잭션과 경합 : 앞 트랜잭션이 행을 잠근 동안 뒤 요청은 기다리고, 앞이 커밋한 뒤 같은 기준 값으로는 409 — 앞의 값이 남는다")
    void concurrentEditsKeepLockContract() throws Exception {
        for (ColumnInfo target : new ColumnInfo[]{account, null}) {
            String base = target == null ? dbTable() : dbColumn(target);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> a = CompletableFuture.runAsync(() -> {
                AccountDto u = login();
                tx.executeWithoutResult(s -> {
                    bizDescriptions.update(u, request(target, "A 의 설명", base));
                    locked.countDown();
                    await(release);
                });
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> b = CompletableFuture.runAsync(() -> bizDescriptions.update(login(), request(target, "B 의 설명", base)));
            Thread.sleep(500);
            assertThat(b).as("B 는 A 의 행 잠금을 기다린다").isNotDone();
            release.countDown();
            a.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> b.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(CustomException.class)
                    .cause().extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
            assertThat(target == null ? dbTable() : dbColumn(target)).isEqualTo("A 의 설명");
        }
    }

    @Test
    @DisplayName("TX-07 승인된 대상 : 업무 설명 직접 편집은 예전처럼 승인 여부와 무관하게 되고, 승인 상태는 바뀌지 않는다")
    void approvedTargetsKeepPolicy() {
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", table.getId());
        jdbc.update("update TB_COL set APRV_YN = true where COL_SN = ?", account.getId());
        AccountDto u = login();
        bizDescriptions.update(u, request(account, "승인된 컬럼 설명", "사용자의 로그인 계정"));
        bizDescriptions.update(u, request(null, "승인된 테이블 설명", "서비스에 가입한 회원"));
        assertThat(dbColumn(account)).isEqualTo("승인된 컬럼 설명");
        assertThat(dbTable()).isEqualTo("승인된 테이블 설명");
        assertThat(jdbc.queryForObject("select APRV_YN from TB_COL where COL_SN = ?", Boolean.class, account.getId())).isTrue();
        assertThat(jdbc.queryForObject("select APRV_YN from TB_TBL where TBL_SN = ?", Boolean.class, table.getId())).isTrue();
    }

    @Test
    @DisplayName("TX-08 권한 없는 사용자 : VIEWER 는 API 에서 거부, 다른 프로젝트의 대상은 찾을 수 없음 — 값 그대로")
    void unauthorizedRejected() {
        AccountDto view = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> bizDescriptionApi.update(view, request(account, "x", "사용자의 로그인 계정"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bizDescriptionApi.update(view, request(null, "x", "서비스에 가입한 회원"))).isInstanceOf(AccessDeniedException.class);
        AccountDto other = SecurityTestSupport.loginAs(projectRepository, 0L, "1", "ROLE_ADMIN");
        assertThatThrownBy(() -> bizDescriptions.update(other, request(account, "x", "사용자의 로그인 계정"))).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COLUMN_IS_NOT_DEFINED);
        assertThatThrownBy(() -> bizDescriptions.update(other, request(null, "x", "서비스에 가입한 회원"))).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TABLE_IS_NOT_DEFINED);
        assertThat(dbColumn(account)).isEqualTo("사용자의 로그인 계정");
        assertThat(dbTable()).isEqualTo("서비스에 가입한 회원");
    }

    // ------------------------------------------------------------------ 준비

    private BizDescriptionDto.Request request(ColumnInfo column, String value, String base) {
        return new BizDescriptionDto.Request(table.getId(), column == null ? null : column.getId(), value, base);
    }

    private String dbColumn(ColumnInfo column) {
        return jdbc.queryForObject("select TASK_EXPLN from TB_COL where COL_SN = ?", String.class, column.getId());
    }

    private String dbTable() {
        return jdbc.queryForObject("select TASK_EXPLN from TB_TBL where TBL_SN = ?", String.class, table.getId());
    }

    private Timestamp modified(String tableName, String key, Long id) {
        return jdbc.queryForObject("select MDFCN_DT from " + tableName + " where " + key + " = ?", Timestamp.class, id);
    }

    private int historyCount() {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, project.getId());
    }

    /** 다른 스레드(= 다른 트랜잭션)에서 실행하고 커밋까지 기다린다 */
    private static void runElsewhere(Runnable action) {
        try {
            CompletableFuture.runAsync(action).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ColumnInfo column(String name, String desc, int order, String biz) {
        return columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_MBR").columnName(name).columnDesc(desc)
                .dataType("VARCHAR").maxLength(100).sortOrder(order).bizDescription(biz).isPk(false).isNullable(true).isApproval(false).build());
    }

    private AccountDto login() {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_USER");
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai1dt-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
