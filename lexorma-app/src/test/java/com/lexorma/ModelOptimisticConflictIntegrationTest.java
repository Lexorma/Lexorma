package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.service.PhysicalViewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모델 편집 낙관적 충돌 검사 (DEC-08 · DEC-13) : 화면이 읽은 수정 일시(baseModifiedAt / modifiedAt)와 잠근 행이 다르면 409 MODEL_CONFLICT 이고 아무것도 바뀌지 않는다.
 * 같은 값이면 저장되고, 기준값을 보내지 않으면 예전처럼 검사하지 않는다. 같은 기준값으로 동시에 저장하면 하나만 반영된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelOptimisticConflictIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnManageService columnManageService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired PhysicalViewService viewService;
    @Autowired TransactionTemplate tx;
    @Autowired com.lexorma.data.logical.service.LogicalModelService logical;
    @Autowired com.lexorma.data.physical.repository.TableRelationRepository relations;
    @Autowired com.lexorma.data.physical.controller.PhysicalModelRestController physicalApi;
    @Autowired com.lexorma.data.table.controller.TableDesignRestController tableApi;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("VIEW 정의 수정 (DEC-13) : 다른 사용자가 먼저 고친 뒤 예전 기준값으로 저장하면 409, 정의 SQL 은 먼저 저장한 값 그대로")
    void viewDefinition() {
        AccountDto admin = login("occ-view");
        ViewDto.SaveResult saved = viewService.create(admin, new ViewDto.Save(null, "VW_OCC", null, null, "SELECT 1 AS N", List.of(), List.of()));
        LocalDateTime base = viewService.detail(admin, saved.id()).updated();

        // 다른 사용자 : 같은 기준값으로 먼저 저장
        viewService.update(admin, saved.id(), new ViewDto.Save(null, "VW_OCC", null, null, "SELECT 2 AS N", List.of(), List.of(), base));
        // 나 : 예전 기준값으로 저장 → 409
        assertConflict(() -> viewService.update(admin, saved.id(), new ViewDto.Save(null, "VW_OCC", null, null, "SELECT 3 AS N", List.of(), List.of(), base)));
        assertThat(viewService.detail(admin, saved.id()).view().definition()).isEqualTo("SELECT 2 AS N");
        // 다시 불러온 기준값이면 저장된다
        LocalDateTime fresh = viewService.detail(admin, saved.id()).updated();
        viewService.update(admin, saved.id(), new ViewDto.Save(null, "VW_OCC", null, null, "SELECT 3 AS N", List.of(), List.of(), fresh));
        assertThat(viewService.detail(admin, saved.id()).view().definition()).isEqualTo("SELECT 3 AS N");
        // 기준값 없는 예전 요청은 검사하지 않는다 (하위 호환)
        viewService.update(admin, saved.id(), new ViewDto.Save(null, "VW_OCC", null, null, "SELECT 4 AS N", List.of(), List.of()));
        assertThat(viewService.detail(admin, saved.id()).view().definition()).isEqualTo("SELECT 4 AS N");
    }

    @Test
    @DisplayName("테이블 수정 : 예전 기준값 409 · 지금 값 성공 · 같은 기준값으로 동시에 두 저장이면 하나만 반영(다른 하나 409)")
    void tableConcurrent() throws Exception {
        AccountDto admin = login("occ-table");
        TableInfo table = table(admin.getProjectId(), "TB_OCC");
        LocalDateTime base = tableInfoRepository.findById(table.getId()).orElseThrow().modifiedAt();

        tx.executeWithoutResult(s -> tableInfoRepository.findById(table.getId()).orElseThrow().setTableDescription("다른 사용자 수정"));
        assertConflict(() -> tableDesignService.updateTable(update(table, "내 수정", base), admin));
        assertThat(tableInfoRepository.findById(table.getId()).orElseThrow().getTableDescription()).isEqualTo("다른 사용자 수정");

        LocalDateTime fresh = tableInfoRepository.findById(table.getId()).orElseThrow().modifiedAt();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (String text : List.of("동시 A", "동시 B")) {
            results.add(pool.submit(call(start, () -> {
                SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), admin.getId(), "ROLE_ADMIN");
                tableDesignService.updateTable(update(table, text, fresh), admin);
            })));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get());
        pool.shutdown();
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "MODEL_CONFLICT");
    }

    @Test
    @DisplayName("인덱스 수정 · 컬럼 일괄 저장 : 예전 기준값이면 409 이고 아무것도 저장되지 않는다")
    void indexAndColumns() {
        AccountDto admin = login("occ-index");
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "TB_OCC_IX");
        ColumnInfo a = column(projectId, table, "A_CD", true);
        ColumnInfo b = column(projectId, table, "B_CD", false);
        ColumnInfo c = column(projectId, table, "C_CD", false);
        PhysicalModelDto.Index saved = physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, table.getId(), "IX_OCC", "btree", false,
                List.of(new PhysicalModelDto.ColumnRef(b.getId(), null)), null));
        Long indexId = saved.id();
        LocalDateTime base = physicalModelService.selectIndex(admin, indexId).index().modifiedAt();
        assertThat(base).isNotNull();
        physicalModelService.updateIndex(admin, indexId, index(table, b, c, base, "IX_OCC2"));
        assertConflict(() -> physicalModelService.updateIndex(admin, indexId, index(table, c, b, base, "IX_OCC3")));
        assertThat(physicalModelService.selectIndex(admin, indexId).index().indexName()).isEqualTo("IX_OCC2");

        // 컬럼 : 화면이 읽은 뒤 다른 사용자가 고친 컬럼을 일괄 저장하면 409 (컬럼 일괄 저장은 승인된 테이블에서만)
        tx.executeWithoutResult(s -> tableInfoRepository.findById(table.getId()).orElseThrow().setIsApproval(true));
        LocalDateTime columnBase = columnInfoRepository.findById(b.getId()).orElseThrow().modifiedAt();
        tx.executeWithoutResult(s -> columnInfoRepository.findById(b.getId()).orElseThrow().setColumnDesc("다른 사용자"));
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(table.getId());
        request.setRows(List.of(ColumnBulkSaveDto.Row.builder().key("b").id(b.getId()).columnDesc("내 설명").dataType("VARCHAR").maxLength(10)
                .isPk("N").isNullable("Y").isRequired("N").isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N")
                .baseModifiedAt(columnBase).build()));
        assertConflict(() -> columnManageService.saveColumnsBulk(admin, request));
        assertThat(columnInfoRepository.findById(b.getId()).orElseThrow().getColumnDesc()).isEqualTo("다른 사용자");
        assertThat(a.getId()).isNotNull();
    }

    @Test
    @DisplayName("6차 : 구성(식별자 속성·인덱스 컬럼)만 바꾼 저장도 기준값을 바꾼다 — 예전 기준값으로 보낸 저장은 409 이고 먼저 바꾼 구성이 남는다")
    void compositionOnlyChangeMovesBase() {
        AccountDto admin = login("occ-compose");
        long projectId = admin.getProjectId();
        // 논리 식별자 : 이름·주 식별자 그대로, 구성 속성만 A → B
        var model = logical.saveModel(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Model(null, "occ-compose", null, null));
        var entity = logical.saveEntity(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity(null, model.id(), "OCC 엔터티", null, null, null, null));
        var first = logical.saveAttribute(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute(null, entity.id(), "속성A", null, null, true, null, null, null, null, null));
        var second = logical.saveAttribute(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute(null, entity.id(), "속성B", null, null, true, null, null, null, null, null));
        var identifier = logical.saveIdentifier(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(null, entity.id(), "OCC 식별자", true, List.of(first.id()), null));
        var moved = logical.saveIdentifier(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(identifier.id(), entity.id(), "OCC 식별자", true,
                List.of(second.id()), null, identifier.modifiedAt()));
        assertThat(moved.modifiedAt()).as("구성만 바뀌어도 기준값이 바뀐다").isAfter(identifier.modifiedAt());
        assertConflict(() -> logical.saveIdentifier(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(identifier.id(), entity.id(), "OCC 식별자 B", true,
                List.of(first.id()), null, identifier.modifiedAt())));
        var kept = tx.execute(x -> logical.saveIdentifier(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier(identifier.id(), entity.id(), "OCC 식별자", true,
                List.of(second.id()), null, moved.modifiedAt())));
        assertThat(kept.attributeIds()).containsExactly(second.id());
        assertThat(kept.modifiedAt()).as("바뀐 것이 없으면 기준값도 그대로").isCloseTo(moved.modifiedAt(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));

        // 물리 인덱스 : 이름·유형 그대로, 컬럼만 (B) → (B, C)
        TableInfo table = table(projectId, "TB_OCC_CMP");
        column(projectId, table, "A_CD", true);
        ColumnInfo b = column(projectId, table, "B_CD", false);
        ColumnInfo c = column(projectId, table, "C_CD", false);
        Long indexId = physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, table.getId(), "IX_OCC_CMP", "btree", false,
                List.of(new PhysicalModelDto.ColumnRef(b.getId(), null)), null)).id();
        LocalDateTime base = physicalModelService.selectIndex(admin, indexId).index().modifiedAt();
        physicalModelService.updateIndex(admin, indexId, index(table, b, c, base, "IX_OCC_CMP"));
        assertThat(physicalModelService.selectIndex(admin, indexId).index().modifiedAt()).isAfter(base);
        assertConflict(() -> physicalModelService.updateIndex(admin, indexId, index(table, c, b, base, "IX_OCC_CMP")));
        assertThat(physicalModelService.selectIndex(admin, indexId).index().columns()).extracting(PhysicalModelDto.ColumnRef::columnId).containsExactly(b.getId(), c.getId());
    }

    @Test
    @DisplayName("3차 : 스키마·논리 모델·관계 규칙도 예전 기준값이면 409, HTTP(컨트롤러) 수정 요청은 기준값이 없으면 400 MODEL_BASE_REQUIRED")
    void remainingPathsAndBaseRequired() {
        AccountDto admin = login("occ-rest");
        long projectId = admin.getProjectId();
        // 스키마
        com.lexorma.data.schema.domain.dto.SchemaDto schema = schemaService.saveSchema(admin, new com.lexorma.data.schema.domain.dto.SchemaDto(null, "occ_s", "처음", null));
        LocalDateTime schemaBase = schemaService.saveSchema(admin, new com.lexorma.data.schema.domain.dto.SchemaDto(schema.id(), "occ_s", "두 번째", null, null, null, schema.updated(), null, null)).updated();
        assertConflict(() -> schemaService.saveSchema(admin, new com.lexorma.data.schema.domain.dto.SchemaDto(schema.id(), "occ_s", "예전 기준", null, null, null, schema.updated(), null, null)));
        assertThat(schemaBase).isNotNull();
        // 논리 모델
        var model = logical.saveModel(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Model(null, "occ-model", null));
        logical.saveModel(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Model(model.id(), "occ-model", "수정", model.modifiedAt()));
        assertConflict(() -> logical.saveModel(admin, new com.lexorma.data.logical.domain.dto.LogicalModelDto.Model(model.id(), "occ-model", "예전", model.modifiedAt())));
        // 관계 규칙
        TableInfo parent = table(projectId, "TB_OCC_P");
        ColumnInfo pk = column(projectId, parent, "P_ID", true);
        TableInfo child = table(projectId, "TB_OCC_C");
        ColumnInfo fk = column(projectId, child, "P_ID", false);
        PhysicalModelDto.Relation relation = physicalModelService.saveRelation(admin, new PhysicalModelDto.Relation(null, "FK_OCC", null, null, child.getId(), null, parent.getId(), null,
                List.of(new PhysicalModelDto.ColumnPair(fk.getId(), null, pk.getId(), null)), null));
        // 관리자 등록은 바로 승인된다 — 승인을 풀어 규칙을 고칠 수 있게 한다
        tx.executeWithoutResult(x -> relations.findById(relation.id()).orElseThrow().setIsApproval(false));
        LocalDateTime relationBase = relations.findById(relation.id()).orElseThrow().modifiedAt();
        physicalModelService.updateRelationRules(admin, relation.id(), new PhysicalModelDto.RelationRules("CASCADE", null, relationBase));
        assertConflict(() -> physicalModelService.updateRelationRules(admin, relation.id(), new PhysicalModelDto.RelationRules("SET_NULL", null, relationBase)));
        // HTTP 경계 : 기준값 없는 수정은 400 (서비스 직접 호출 — 내부 작업 — 은 예외)
        assertThatThrownBy(() -> physicalApi.updateRelationRules(admin, relation.id(), new PhysicalModelDto.RelationRules("SET_NULL", null)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        assertThatThrownBy(() -> tableApi.updateTable(update(parent, "변경", null), admin))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
    }

    private static PhysicalModelDto.Index index(TableInfo table, ColumnInfo first, ColumnInfo second, LocalDateTime base, String name) {
        return new PhysicalModelDto.Index(null, table.getId(), name, "btree", false,
                List.of(new PhysicalModelDto.ColumnRef(first.getId(), null), new PhysicalModelDto.ColumnRef(second.getId(), null)), null, null, null, null, null, base);
    }

    private static TableInfoDto update(TableInfo table, String description, LocalDateTime base) {
        return TableInfoDto.builder().id(table.getId()).tableName(table.getTableName()).tableDescription(description).isMasterTable("N").sortOrder(1)
                .baseModifiedAt(base).build();
    }

    private static Callable<String> call(CountDownLatch start, Runnable body) {
        return () -> {
            start.await();
            try {
                body.run();
                return "OK";
            } catch (CustomException e) {
                return e.getErrorCode().name();
            } finally {
                SecurityTestSupport.clear();
            }
        };
    }

    private static void assertConflict(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MODEL_CONFLICT);
    }

    private AccountDto login(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).tableDescription("처음").isApproval(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnDesc(name).sortOrder(name.charAt(0) - 'A' + 1)
                .columnName(name).dataType("VARCHAR").maxLength(10).isPk(pk).isApproval(false).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
