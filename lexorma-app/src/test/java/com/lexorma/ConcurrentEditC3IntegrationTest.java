package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.repository.LogicalAttributeRepository;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.repository.AttributeColumnMappingRepository;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8-C3 (docs/completed/modeling-concurrency-m8.md 15장) 서버 쪽 : 컬럼 일괄 편집 기준값 범위 (M8-D04) — 바꾸지 않은 행(unchanged)은 값·순서·기준값을 보지 않는다.
 * 화면 복구 흐름은 프론트엔드라 브라우저로 확인한다 (문서 15장).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC3IntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired SchemaService schemaService;
    @Autowired ColumnManageService columns;
    @Autowired TableDesignService tableDesign;
    @Autowired LogicalModelService logical;
    @Autowired LogicalEntityRepository entities;
    @Autowired LogicalAttributeRepository attributes;
    @Autowired ModelMappingService mapping;
    @Autowired AttributeColumnMappingRepository attributeMappings;
    @Autowired PhysicalModelService physical;
    @Autowired TableConstraintRepository constraints;
    @Autowired com.lexorma.data.column.controller.ColumnManageRestController columnApi;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("M8-D04 : 다른 사용자가 고친 컬럼을 바꾸지 않은 행(unchanged)으로 보내면 409 가 아니고 그 값이 남는다 · 고친 행만 기준값 · 순서 그대로")
    void unchangedRowsAreNotCompared() {
        AccountDto admin = login("c3b1");
        Fixture f = fixture(admin);
        ColumnInfoDto a = columnDto(f.columnId);
        ColumnInfoDto b = columnDto(f.otherColumnId);
        ColumnInfoDto c = columnDto(f.thirdColumnId);
        tx.executeWithoutResult(s -> columnRepository.findById(f.otherColumnId).orElseThrow().setColumnDesc("다른 사용자 설명")); // 내 화면이 읽은 뒤
        ColumnBulkSaveDto.Row edited = bulkRow(a);
        edited.setColumnDesc("내 설명");
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId)
                .rows(List.of(edited, unchanged(b), unchanged(c))).build());
        assertThat(result.result()).as(String.valueOf(result.errors())).isTrue();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(inTx(() -> columnRepository.findById(f.columnId).orElseThrow().getColumnDesc())).isEqualTo("내 설명");
        assertThat(inTx(() -> columnRepository.findById(f.otherColumnId).orElseThrow().getColumnDesc())).isEqualTo("다른 사용자 설명");
        assertThat(inTx(() -> List.of(f.columnId, f.otherColumnId, f.thirdColumnId).stream().map(id -> columnRepository.findById(id).orElseThrow().getSortOrder()).toList()))
                .containsExactly(1, 2, 3);

        // 예전처럼 모든 행을 기준값과 함께 보내면(구조 변경이 있는 저장) 바뀐 행 때문에 409
        ColumnBulkSaveDto.Row staleB = bulkRow(b);
        assertCode(() -> columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId).rows(List.of(bulkRow(columnDto(f.columnId)), staleB)).build()), "MODEL_CONFLICT");
    }

    @Test
    @DisplayName("M8-D04 HTTP : 바꾸지 않은 행은 기준값 없이 받고, 고친 행은 여전히 기준값 필수(400) · 바꾸지 않은 행은 비어 있는 순서도 채우지 않는다")
    void httpBaseOnlyForChangedRows() {
        AccountDto admin = login("c3b2");
        Fixture f = fixture(admin);
        tx.executeWithoutResult(s -> columnRepository.findById(f.thirdColumnId).orElseThrow().setSortOrder(null));
        ColumnBulkSaveDto.Row edited = bulkRow(columnDto(f.columnId));
        edited.setColumnDesc("HTTP 설명");
        ColumnBulkSaveDto.Row noBase = bulkRow(columnDto(f.columnId));
        noBase.setBaseModifiedAt(null);
        assertCode(() -> columnApi.saveColumnsBulk(ColumnBulkSaveDto.builder().tableInfoId(f.tableId).rows(List.of(noBase)).build(), admin), "MODEL_BASE_REQUIRED");
        var response = columnApi.saveColumnsBulk(ColumnBulkSaveDto.builder().tableInfoId(f.tableId)
                .rows(List.of(edited, unchanged(columnDto(f.otherColumnId)), unchanged(columnDto(f.thirdColumnId)))).build(), admin).getBody();
        assertThat(response.result()).isTrue();
        assertThat(inTx(() -> columnRepository.findById(f.thirdColumnId).orElseThrow().getSortOrder())).isNull();
    }

    @Test
    @DisplayName("M8-D04 동시 : 다른 저장이 테이블 행을 잡은 동안 들어온 저장(그 컬럼은 바꾸지 않은 행)은 기다렸다가 성공하고 두 변경이 모두 남는다")
    void concurrentEditsOnDifferentColumns() throws Exception {
        AccountDto admin = login("c3b3");
        Fixture f = fixture(admin);
        ColumnInfoDto a = columnDto(f.columnId);
        ColumnInfoDto b = columnDto(f.otherColumnId);
        ColumnBulkSaveDto.Row theirs = bulkRow(b);
        theirs.setColumnDesc("A 가 고친 B");
        ColumnBulkSaveDto.Row mine = bulkRow(a);
        mine.setColumnDesc("B 가 고친 A");
        String outcome = interleave(admin,
                () -> require(columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId).rows(List.of(unchanged(a), theirs)).build())),
                () -> require(columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId).rows(List.of(mine, unchanged(b))).build())));
        assertThat(outcome).isEqualTo("OK");
        assertThat(inTx(() -> columnRepository.findById(f.columnId).orElseThrow().getColumnDesc())).isEqualTo("B 가 고친 A");
        assertThat(inTx(() -> columnRepository.findById(f.otherColumnId).orElseThrow().getColumnDesc())).isEqualTo("A 가 고친 B");
    }

    private static void require(ColumnBulkSaveDto.Result result) {
        if (!result.result()) throw new IllegalStateException(String.valueOf(result.errors()));
    }

    private static ColumnBulkSaveDto.Row unchanged(ColumnInfoDto c) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).unchanged(true).build();
    }

    private ColumnInfoDto columnDto(Long id) {
        return inTx(() -> ColumnInfoDto.fromEntity(columnRepository.findById(id).orElseThrow()));
    }

    private static void assertCode(Runnable call, String code) {
        assertThat(outcome(call)).isEqualTo(code);
    }

    // ------------------------------------------------------------------ 준비

    /** 논리 모델(엔터티 1 + 속성 1 + 주 식별자) ↔ 테이블(컬럼 3) 매핑, 속성 ↔ 첫 컬럼 매핑 */
    private record Fixture(Long modelId, Long entityId, Long attributeId, Long tableId, Long columnId, Long otherColumnId, Long thirdColumnId,
                           Long entityMappingId, Long attributeMappingId) {
    }

    private Fixture fixture(AccountDto admin) {
        var model = logical.saveModel(admin, new LogicalModelDto.Model(null, "c2-" + UUID.randomUUID().toString().substring(0, 6), null));
        var entity = logical.saveEntity(admin, new LogicalModelDto.Entity(null, model.id(), "고객", null, null, null, null));
        var attribute = logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, entity.id(), "고객번호", null, null, true, null, null, null, null, null));
        TableInfo table = table(admin.getProjectId(), "TB_CUST", true);
        ColumnInfo c1 = column(table, "CUST_NO", 1);
        ColumnInfo c2 = column(table, "CUST_ID", 2);
        ColumnInfo c3 = column(table, "CUST_CD", 3);
        tx.executeWithoutResult(s -> columnRepository.findAllById(List.of(c1.getId(), c2.getId(), c3.getId())).forEach(c -> c.setIsApproval(false)));
        var em = mapping.saveEntityMapping(admin, new ModelMappingDto.EntityMapping(null, entity.id(), table.getId()));
        var am = mapping.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(null, em.id(), attribute.id(), c1.getId()));
        return new Fixture(model.id(), entity.id(), attribute.id(), table.getId(), c1.getId(), c2.getId(), c3.getId(), em.id(), am.id());
    }










    private TableInfo table(long projectId, String name, boolean approved) {
        return inTx(() -> tableRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(TableInfo table, String name, int order) {
        return inTx(() -> columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableRepository.getReferenceById(table.getId())).columnName(name).columnDesc(name).sortOrder(order)
                .dataType("BIGINT").isPk(false).isNullable(true).isApproval(false).build()));
    }

    private static ColumnBulkSaveDto.Row bulkRow(ColumnInfoDto c) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).columnDesc(c.getColumnDesc()).dataType(c.getDataType()).maxLength(c.getMaxLength())
                .precision(c.getPrecision()).scale(c.getScale()).defaultValue(c.getDefaultValue()).identity(c.getIdentity()).refTableName(c.getRefTableName())
                .example(c.getExample()).isPk(c.getIsPk()).isNullable(c.getIsNullable()).isRequired(c.getIsRequired()).isUnique(c.getIsUnique()).isIndex(c.getIsIndex())
                .isMasterData(c.getIsMasterData()).isSensitive(c.getIsSensitive()).isEncrypted(c.getIsEncrypted()).baseModifiedAt(c.getModifiedAt()).build();
    }

    private AccountDto login(String prefix) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    // ------------------------------------------------------------------ 동시 실행 도구 (ConcurrentEditP1IntegrationTest 와 같은 방식)

    /** A 를 한 트랜잭션에서 실행하고, 커밋 전에 B 를 다른 스레드(다른 DB 연결)에서 실행한다. B 가 끝났거나 잠금을 기다리기 시작하면 A 를 커밋한다. 결과 = B 의 결과 */
    private String interleave(AccountDto account, Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = tx.execute(s -> {
                a.run();
                Future<String> f = pool.submit(() -> {
                    SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), String.valueOf(account.getId()), "ROLE_ADMIN");
                    try {
                        return outcome(b);
                    } finally {
                        SecurityTestSupport.clear();
                    }
                });
                awaitDoneOrLockWait(f);
                return f;
            });
            return other.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private void awaitDoneOrLockWait(Future<?> work) {
        String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (work.isDone()) return;
            if (jdbc.queryForObject(sql, Integer.class) > 0) return;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 진단 (M8-C4a) : 스레드·Hikari·DB 세션·AuthUtil 컨텍스트 — 시간 제한은 그대로 두고 원인을 남긴다
        throw new IllegalStateException("B 가 끝나지도, 잠금을 기다리지도 않았다"
                + ConcurrencyDiagnostics.describe("B 대기", started, jdbc, dataSource, projectRepository));
    }

    private boolean postgres() {
        try {
            return jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
        } catch (Exception e) {
            return false;
        }
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
