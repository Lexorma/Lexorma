package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.repository.ModelVersionRepository;
import com.lexorma.data.version.service.ModelVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델 버전 만들기의 시점·일관성 (T-09, docs/source-import-m4e.md 12장). 가져오기 확정과 같은 모양(프로젝트 배타 잠금 → 모델 변경 → 커밋 지연)의 트랜잭션과 경합시킨다.
 * 사용자 버전은 READ COMMITTED + 프로젝트 배타 잠금 : 잠금을 얻은 뒤의 최신 커밋 상태. PostgreSQL 결과가 기준이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelVersionConcurrencyIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired SchemaService schemaService;
    @Autowired ModelVersionService versionService;
    @Autowired ModelVersionRepository versionRepository;
    @Autowired ModelChangeRecorder recorder;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("T09-01·02 : 경합이 없으면 현재 모델(변경 0), 가져오기가 커밋된 뒤 만든 버전에는 그 변경이 있다")
    void sequential() {
        AccountDto admin = loginAsAdmin("t09-seq");
        TableInfo table = table(admin.getProjectId(), "TB_T09");
        ModelVersionDto.Summary v1 = versionService.create(admin, new ModelVersionDto.Create("v1", null));
        assertThat(versionService.compare(admin, v1.id(), null).totals()).containsEntry("ADDED", 0).containsEntry("REMOVED", 0).containsEntry("MODIFIED", 0);
        importLike(admin.getProjectId(), table.getId(), null, null);
        String v2 = snapshot(versionService.create(admin, new ModelVersionDto.Create("v2", null)));
        assertThat(v2).contains("IMPORTED_COL").contains("가져오기 이후");
    }

    @Test
    @DisplayName("T09-03 : 가져오기가 커밋 전이면 버전 만들기는 프로젝트 잠금을 기다리고, 잠금을 얻은 뒤의 최신 상태(가져오기 뒤)를 온전하게 저장한다 (5회)")
    void waitsForImport() throws Exception {
        for (int round = 1; round <= 5; round++) {
            AccountDto admin = loginAsAdmin("t09-wait-" + round);
            TableInfo table = table(admin.getProjectId(), "TB_T09");
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> importer = pool.submit(() -> importLike(admin.getProjectId(), table.getId(), held, release));
                await(held);
                Future<ModelVersionDto.Summary> version = pool.submit(() -> versionService.create(admin, new ModelVersionDto.Create("사용자 버전", null)));
                assertThat(awaitLockWait(version)).as("버전 만들기가 프로젝트 잠금을 기다린다").isTrue();
                release.countDown();
                importer.get(30, TimeUnit.SECONDS);
                String snapshot = snapshot(version.get(30, TimeUnit.SECONDS));
                assertThat(snapshot).as("round " + round + " : 가져오기 뒤 상태 (새 컬럼·TABLE 설명 모두)").contains("IMPORTED_COL").contains("가져오기 이후");
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("T09-05·06 : 같은 프로젝트 동시 버전 만들기는 둘 다 같은 내용(같은 이름이면 하나만), 다른 프로젝트의 버전 만들기는 잠금을 기다리지 않는다")
    void concurrentVersions() throws Exception {
        AccountDto admin = loginAsAdmin("t09-same");
        table(admin.getProjectId(), "TB_T09");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> a = pool.submit(caught(() -> { await(start); return versionService.create(admin, new ModelVersionDto.Create("동시 A", null)); }));
            Future<Object> b = pool.submit(caught(() -> { await(start); return versionService.create(admin, new ModelVersionDto.Create("동시 B", null)); }));
            start.countDown();
            ModelVersionDto.Summary va = (ModelVersionDto.Summary) a.get(30, TimeUnit.SECONDS);
            ModelVersionDto.Summary vb = (ModelVersionDto.Summary) b.get(30, TimeUnit.SECONDS);
            assertThat(snapshot(va)).isEqualTo(snapshot(vb));

            CountDownLatch again = new CountDownLatch(1);
            Future<Object> c = pool.submit(caught(() -> { await(again); return versionService.create(admin, new ModelVersionDto.Create("같은 이름", null)); }));
            Future<Object> d = pool.submit(caught(() -> { await(again); return versionService.create(admin, new ModelVersionDto.Create("같은 이름", null)); }));
            again.countDown();
            List<Object> results = List.of(c.get(30, TimeUnit.SECONDS), d.get(30, TimeUnit.SECONDS));
            assertThat(results).filteredOn(r -> r instanceof ModelVersionDto.Summary).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof CustomException ex && ex.getErrorCode() == ErrorCode.SAVED_DATA_EXISTS).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        // 다른 프로젝트 : 한 프로젝트의 가져오기가 커밋 전이어도 끝난다
        AccountDto first = loginAsAdmin("t09-p1");
        TableInfo firstTable = table(first.getProjectId(), "TB_T09");
        AccountDto second = loginAsAdmin("t09-p2");
        table(second.getProjectId(), "TB_T09");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> importer = other.submit(() -> importLike(first.getProjectId(), firstTable.getId(), held, release));
            await(held);
            assertThat(versionService.create(second, new ModelVersionDto.Create("다른 프로젝트", null))).isNotNull();
            release.countDown();
            importer.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    @Test
    @DisplayName("일관성 근거 : 프로젝트를 배타로 잡은 동안 다른 트랜잭션의 모델 수정(수정만 해도)은 커밋하지 못한다 — 변경 이력 저장이 커밋 직전에 프로젝트 공유 잠금을 잡는다. 두 번 읽어도 같다")
    void projectLockFreezesModel() throws Exception {
        AccountDto admin = loginAsAdmin("t09-freeze");
        long projectId = admin.getProjectId();
        table(projectId, "TB_ONE");
        TableInfo two = table(projectId, "TB_TWO");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Object> editor;
        try {
            editor = tx.execute(s -> {
                projectRepository.findByIdForUpdate(projectId).orElseThrow();
                String first = recorder.currentState(projectId).toString();
                Future<Object> edit = pool.submit(caught(() -> tx.execute(x -> {
                    tableRepository.findById(two.getId()).orElseThrow().setTableDescription("다른 사용자 수정");
                    return "committed";
                })));
                boolean blocked = awaitLockWait(edit);
                assertThat(blocked).as("수정만 하는 저장도 프로젝트 잠금에 막힌다").isTrue();
                assertThat(recorder.currentState(projectId).toString()).isEqualTo(first);
                return edit;
            });
            Object result = editor.get(30, TimeUnit.SECONDS);
            // PostgreSQL : 잠금이 풀린 뒤 커밋된다. H2 : 잠금 대기 시간이 지나 실패할 수 있다
            if (postgres()) assertThat(result).isEqualTo("committed");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 가져오기 확정과 같은 모양 : 프로젝트 배타 잠금 → 컬럼 추가·TABLE 설명 변경 → (held 를 알리고 release 까지 커밋 지연) → 커밋 */
    private void importLike(long projectId, Long tableId, CountDownLatch held, CountDownLatch release) {
        tx.executeWithoutResult(s -> {
            projectRepository.findByIdForUpdate(projectId).orElseThrow();
            TableInfo t = tableRepository.findById(tableId).orElseThrow();
            t.setTableDescription("가져오기 이후");
            columnRepository.save(ColumnInfo.builder().projectId(projectId).tableName(t.getTableName()).tableInfo(t).columnName("IMPORTED_COL")
                    .dataType("VARCHAR").maxLength(10).isPk(false).isApproval(false).build());
            columnRepository.flush();
            if (held != null) {
                held.countDown();
                await(release);
            }
        });
    }

    /** 작업이 잠금을 기다리기 시작했는지 (끝나 버렸으면 false) */
    private boolean awaitLockWait(Future<?> work) {
        String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (work.isDone()) return false;
            if (jdbc.queryForObject(sql, Integer.class) > 0) return true;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean postgres() {
        try {
            return jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
        } catch (Exception e) {
            return false;
        }
    }

    private String snapshot(ModelVersionDto.Summary summary) {
        return versionRepository.findById(summary.id()).orElseThrow().getSnapshot();
    }

    private static Callable<Object> caught(Callable<Object> call) {
        return () -> {
            try {
                return call.call();
            } catch (Exception e) {
                return e;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("기다리는 신호가 오지 않았다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        TableInfo table = tx.execute(s -> tableRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false).isMasterTable(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
        tx.executeWithoutResult(s -> columnRepository.save(ColumnInfo.builder().projectId(projectId).tableName(name).tableInfo(tableRepository.getReferenceById(table.getId()))
                .columnName("ID").dataType("VARCHAR").maxLength(10).isPk(false).isApproval(false).build()));
        return table;
    }
}
