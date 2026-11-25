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
 * M8-C4a (docs/completed/modeling-concurrency-m8.md 16장) : 여러 테이블을 바꾸는 요청의 잠금 순서(ID 오름차순)와 삭제 ↔ 제약조건 등록 경합.
 * 교착 재현은 A 가 첫 테이블만 잠근 채 B 를 시작하고, B 가 기다리기 시작하면 A 가 둘째 테이블을 잠그는 3단계 실행으로 만든다 (시간 대기 없음 — DB 세션 정보로 확인).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC4IntegrationTest {

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
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("테이블 승인 [A, B] ↔ [B, A] : A 가 첫 테이블을 잡은 사이 들어온 반대 순서 승인은 기다렸다가 끝나고 교착이 없다 (예전 요청 순서 잠금은 40P01)")
    void tableApprovalOppositeOrder() throws Exception {
        AccountDto admin = login("c4a1");
        TableInfo a = table(admin.getProjectId(), "TB_C4_A", false);
        TableInfo b = table(admin.getProjectId(), "TB_C4_B", false);
        String outcome = interleave3(admin,
                () -> tableDesign.updateTableIsApproval(true, admin, List.of(dto(a))),
                () -> tableDesign.updateTableIsApproval(true, admin, List.of(dto(b), dto(a))),
                () -> tableDesign.updateTableIsApproval(true, admin, List.of(dto(b))));
        assertThat(outcome).as("B (반대 순서) 결과").isIn("OK", "MODEL_CONFLICT");
        assertThat(inTx(() -> tableRepository.findAllById(List.of(a.getId(), b.getId())).stream().allMatch(TableInfo::getIsApproval))).isTrue();
    }

    @Test
    @DisplayName("테이블 승인 [A] 를 잡은 사이 들어온 삭제 [B, A] : 삭제가 A 부터 기다리므로 교착이 없고, 승인된 A 를 보고 거절 (전체 롤백 — B 도 그대로)")
    void tableApprovalVsDeleteOppositeOrder() throws Exception {
        AccountDto admin = login("c4a2");
        TableInfo a = table(admin.getProjectId(), "TB_C4_C", false);
        TableInfo b = table(admin.getProjectId(), "TB_C4_D", false);
        TableInfoDto delA = forDelete(admin, a.getId());
        TableInfoDto delB = forDelete(admin, b.getId());
        String outcome = interleave3(admin,
                () -> tableDesign.updateTableIsApproval(true, admin, List.of(dto(a))),
                () -> tableDesign.deleteTable(List.of(delB, delA), admin),
                () -> tableDesign.updateTableIsApproval(true, admin, List.of(dto(b))));
        assertThat(outcome).isIn("APPROVED_DATA_CANNOT_BE_DELETED", "MODEL_CONFLICT");
        assertThat(inTx(() -> tableRepository.findAllById(List.of(a.getId(), b.getId())).size())).isEqualTo(2);
    }

    @Test
    @DisplayName("일괄 승인 : 중복 ID 는 한 번만 바뀌고, 다른 프로젝트 ID 가 섞이면 대상 없음으로 전체 롤백, 하나라도 오래되면 409 전체 롤백")
    void tableApprovalBatchRules() {
        AccountDto admin = login("c4a3");
        TableInfo a = table(admin.getProjectId(), "TB_C4_E", false);
        TableInfo b = table(admin.getProjectId(), "TB_C4_F", false);
        tableDesign.updateTableIsApproval(true, admin, List.of(dto(b), dto(a), dto(a)));
        assertThat(inTx(() -> tableRepository.findAllById(List.of(a.getId(), b.getId())).stream().allMatch(TableInfo::getIsApproval))).isTrue();

        AccountDto other = login("c4a3-other");
        TableInfo foreign = table(other.getProjectId(), "TB_C4_G", false);
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        assertCode(() -> tableDesign.updateTableIsApproval(false, admin, List.of(dto(a), dto(foreign))), "TABLE_IS_NOT_DEFINED");
        assertThat(inTx(() -> tableRepository.findById(a.getId()).orElseThrow().getIsApproval())).isTrue();

        TableInfoDto staleB = dto(b);
        tx.executeWithoutResult(s -> tableRepository.findById(b.getId()).orElseThrow().setTableDescription("다른 사용자"));
        assertCode(() -> tableDesign.updateTableIsApproval(false, admin, List.of(dto(a), staleB)), "MODEL_CONFLICT");
        assertThat(inTx(() -> tableRepository.findById(a.getId()).orElseThrow().getIsApproval())).isTrue();
    }

    @Test
    @DisplayName("테이블 삭제가 행을 잡은 동안의 CHECK·UNIQUE 제약조건 등록은 기다렸다가 H2·PostgreSQL 모두 대상 없음 400 (예전 H2 는 500) — 고아 제약조건 없음")
    void deleteVsConstraintRegistration() throws Exception {
        AccountDto admin = login("c4a4");
        TableInfo empty = table(admin.getProjectId(), "TB_C4_EMPTY", false);
        String check = interleave(admin,
                () -> tableDesign.deleteTable(List.of(forDelete(admin, empty.getId())), admin),
                () -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, empty.getId(), null, "CHECK", "1 = 1", List.of(), null)));
        assertThat(check).isEqualTo("TABLE_IS_NOT_DEFINED");

        TableInfo withColumn = table(admin.getProjectId(), "TB_C4_UQ", false);
        ColumnInfo col = column(withColumn, "UQ_CD", 1);
        // 컬럼이 있는 테이블은 지울 수 없으므로, 컬럼 삭제(테이블 행을 잡는다) 중 그 컬럼의 UNIQUE 등록 : 대상 컬럼 없음 400 (500 아님)
        ColumnInfoDto seen = inTx(() -> ColumnInfoDto.fromEntity(columnRepository.findById(col.getId()).orElseThrow()));
        String unique = interleave(admin,
                () -> columns.deleteColumn(admin, List.of(seen)),
                () -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, withColumn.getId(), null, "UNIQUE", null,
                        List.of(new PhysicalModelDto.ColumnRef(col.getId(), col.getColumnName())), null)));
        assertThat(unique).isEqualTo("INVALID_PHYSICAL_MODEL"); // 지워진 컬럼 = 기존 "이 테이블의 컬럼이 아님" 400
        assertThat(inTx(() -> jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where TBL_SN in (?, ?)", Integer.class, empty.getId(), withColumn.getId()))).isZero();

        // 정상 등록·다른 테이블 동시 등록은 그대로
        TableInfo t1 = table(admin.getProjectId(), "TB_C4_OK1", false);
        TableInfo t2 = table(admin.getProjectId(), "TB_C4_OK2", false);
        String parallel = interleave(admin,
                () -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, t1.getId(), null, "CHECK", "1 = 1", List.of(), null)),
                () -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, t2.getId(), null, "CHECK", "2 = 2", List.of(), null)));
        assertThat(parallel).isEqualTo("OK");
        assertCode(() -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, 999_999_999L, null, "CHECK", "1 = 1", List.of(), null)), "TABLE_IS_NOT_DEFINED");
    }

    private TableInfoDto dto(TableInfo t) {
        return inTx(() -> TableInfoDto.fromEntity(tableRepository.findById(t.getId()).orElseThrow()));
    }

    private TableInfoDto forDelete(AccountDto admin, Long id) {
        TableInfoDto d = inTx(() -> TableInfoDto.fromEntity(tableRepository.findById(id).orElseThrow()));
        d.setComposition(tableDesign.selectTableDeletions(List.of(id), admin).getFirst().composition());
        return d;
    }

    private static void assertCode(Runnable call, String code) {
        assertThat(outcome(call)).isEqualTo(code);
    }

    /**
     * 3단계 : 한 트랜잭션에서 a1 을 실행(첫 잠금) → 다른 스레드에서 b 를 시작해 끝나거나 잠금을 기다릴 때까지 확인 → 같은 트랜잭션에서 a2 (둘째 잠금) → 커밋.
     * 잠금 순서가 반대면 a2 와 b 가 서로 기다려 교착(PostgreSQL 40P01, H2 시간 초과)이 난다. 결과 = b 의 결과 (A 쪽 실패는 예외로 드러난다)
     */
    private String interleave3(AccountDto account, Runnable a1, Runnable b, Runnable a2) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = tx.execute(s -> {
                a1.run();
                Future<String> f = pool.submit(() -> {
                    SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), String.valueOf(account.getId()), "ROLE_ADMIN");
                    try {
                        return outcome(b);
                    } finally {
                        SecurityTestSupport.clear();
                    }
                });
                awaitDoneOrLockWait(f);
                a2.run();
                return f;
            });
            return other.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
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
