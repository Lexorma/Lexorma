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
 * M8-C2 삭제·승인·매핑 동시성 (docs/completed/modeling-concurrency-m8.md 14장). "재현 n" 은 수정 전 코드로 실제 결함을 재현했던 시나리오다
 * (H2 · PostgreSQL 모두 7건 실패, 재현 6 은 수정 전에도 안전 — 문서 기록) — 지금은 같은 실행 순서에서 409·대기·대상 없음으로 끝난다.
 * 불변식 : 화면이 본 뒤 바뀐 대상을 지우거나 바꾸거나 승인하는 요청은 성공하면 안 된다 (조용한 손실·본 적 없는 내용 승인 없음).
 * 동시 시나리오는 ConcurrentEditP1IntegrationTest 와 같은 방식 (A 의 트랜잭션 안에서 B 를 다른 연결로 실행, B 가 끝났거나 잠금을 기다리는 것을 DB 세션 정보로 확인 — 시간 대기 없음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditC2IntegrationTest {

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
    @Autowired com.lexorma.data.logical.service.LogicalSubjectAreaService subjectAreas;
    @Autowired com.lexorma.data.schema.repository.SchemaInfoRepository schemaRepository;
    @Autowired com.lexorma.data.logical.controller.LogicalModelRestController logicalApi;
    @Autowired com.lexorma.data.table.controller.TableDesignRestController tableApi;
    @Autowired com.lexorma.data.column.controller.ColumnManageRestController columnApi;
    @Autowired com.lexorma.data.physical.controller.PhysicalModelRestController physicalApi;
    @Autowired com.lexorma.data.schema.controller.SchemaRestController schemaApi;
    @Autowired com.lexorma.data.view.controller.ViewRestController viewApi;
    @Autowired com.lexorma.data.mapping.controller.ModelMappingRestController mappingApi;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 삭제 (M8-D01)

    @Test
    @DisplayName("재현 1 → 고침 : 조회 뒤 다른 사용자가 고친 속성의 삭제는 409 (속성 그대로) · 지금 기준값이면 삭제 · HTTP 기준값 없음 400")
    void staleAttributeDelete() {
        AccountDto admin = login("c2r1");
        Fixture f = fixture(admin);
        Long free = logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, f.entityId, "자유", null, null, false, null, null, null, null, null)).id();
        LogicalModelDto.Attribute seen = attribute(free);
        logical.saveAttribute(admin, edited(seen, "다른 사용자 설명"));
        int history = historyRows(admin);
        assertCode(() -> logical.deleteAttribute(admin, free, seen.modifiedAt()), "MODEL_CONFLICT");
        assertThat(inTx(() -> attributes.findById(free).orElseThrow().getAttributeDescription())).isEqualTo("다른 사용자 설명");
        assertThat(historyRows(admin)).as("409 는 이력을 남기지 않는다 (전체 롤백)").isEqualTo(history);
        assertCode(() -> logicalApi.deleteAttribute(admin, free, null), "MODEL_BASE_REQUIRED");
        logical.deleteAttribute(admin, free, attribute(free).modifiedAt());
        assertThat(inTx(() -> attributes.findById(free))).isEmpty();
        // 이미 지운 대상 : 대상 없음 (500 아님)
        assertCode(() -> logical.deleteAttribute(admin, free, seen.modifiedAt()), "LOGICAL_MODEL_IS_NOT_DEFINED");
    }

    @Test
    @DisplayName("재현 2 → 고침 : 엔터티 삭제 확인 뒤(동시에) 더해진 속성이 있으면 구성 기준값이 달라 409 — 엔터티·새 속성 그대로")
    void entityDeleteWithConcurrentAttribute() throws Exception {
        AccountDto admin = login("c2r2");
        Fixture f = fixture(admin);
        Long bare = unapprovedEntity(admin, f.modelId, "빈엔터티");
        var seen = logical.selectEntityDeletion(admin, bare);
        String b = interleave(admin,
                () -> logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, bare, "새속성", null, null, false, null, null, null, null, null)),
                () -> logical.deleteEntity(admin, bare, seen.modifiedAt(), seen.composition()));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> entities.findById(bare).orElseThrow().getAttributes().size())).isEqualTo(1);
    }

    @Test
    @DisplayName("엔터티 삭제 : 확인 화면의 구성으로 속성·식별자와 함께 삭제 · 속성을 고치거나 식별자를 더하면 409 · 기준값·구성 없는 HTTP 400 · 다른 프로젝트는 대상 없음")
    void entityDeleteComposition() {
        AccountDto admin = login("c2e1");
        Fixture f = fixture(admin);
        Long id = unapprovedEntity(admin, f.modelId, "삭제대상");
        Long a = logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, id, "번호", null, null, true, null, null, null, null, null)).id();
        var before = logical.selectEntityDeletion(admin, id);
        assertThat(before.cascade()).extracting(c -> c.kind() + "=" + c.count()).containsExactly("attributes=1", "identifiers=0");
        logical.saveIdentifier(admin, new LogicalModelDto.Identifier(null, id, "PK", true, List.of(a), null));
        assertCode(() -> logical.deleteEntity(admin, id, before.modifiedAt(), before.composition()), "MODEL_CONFLICT");
        var withKey = logical.selectEntityDeletion(admin, id);
        logical.saveAttribute(admin, edited(attribute(a), "고친 설명"));
        assertCode(() -> logical.deleteEntity(admin, id, withKey.modifiedAt(), withKey.composition()), "MODEL_CONFLICT");
        assertCode(() -> logicalApi.deleteEntity(admin, id, withKey.modifiedAt(), null), "MODEL_BASE_REQUIRED");
        assertCode(() -> logicalApi.deleteEntity(admin, id, null, withKey.composition()), "MODEL_BASE_REQUIRED");
        AccountDto other = login("c2e1-other");
        var now = logical.selectEntityDeletion(admin, id);
        assertCode(() -> logical.deleteEntity(other, id, now.modifiedAt(), now.composition()), "LOGICAL_MODEL_IS_NOT_DEFINED");
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        logical.deleteEntity(admin, id, now.modifiedAt(), now.composition());
        assertThat(inTx(() -> entities.findById(id))).isEmpty();
        assertThat(inTx(() -> attributes.findById(a))).isEmpty();
    }

    @Test
    @DisplayName("삭제 중 삽입 방지 : 엔터티 삭제가 모델 행을 잡은 동안 들어온 속성 추가는 기다렸다가 대상 없음 — 고아 속성 없음")
    void insertDuringEntityDelete() throws Exception {
        AccountDto admin = login("c2e2");
        Fixture f = fixture(admin);
        Long id = unapprovedEntity(admin, f.modelId, "지울엔터티");
        var seen = logical.selectEntityDeletion(admin, id);
        String b = interleave(admin,
                () -> logical.deleteEntity(admin, id, seen.modifiedAt(), seen.composition()),
                () -> logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, id, "늦은속성", null, null, false, null, null, null, null, null)));
        assertThat(b).isEqualTo("LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(inTx(() -> jdbc.queryForObject("select count(*) from TB_LGCL_ATRB where LGCL_ENTT_SN = ?", Integer.class, id))).isZero();
    }

    @Test
    @DisplayName("모델 삭제 : 확인 뒤 주제 영역이 더해지면 409 · 같은 구성이면 삭제 · 모델 수정 ↔ 삭제 겹침은 409/대상 없음 (500 아님)")
    void modelDelete() throws Exception {
        AccountDto admin = login("c2m1");
        var model = logical.saveModel(admin, new LogicalModelDto.Model(null, "지울모델", null));
        var seen = logical.selectModelDeletion(admin, model.id());
        subjectAreas.save(admin, new LogicalSubjectAreaDto.Save(null, model.id(), null, "영역", null, null, null));
        assertCode(() -> logical.deleteModel(admin, model.id(), seen.modifiedAt(), seen.composition()), "MODEL_CONFLICT");
        assertCode(() -> logicalApi.deleteModel(admin, model.id(), seen.modifiedAt(), null), "MODEL_BASE_REQUIRED");
        var now = logical.selectModelDeletion(admin, model.id());
        assertThat(now.cascade()).extracting(c -> c.kind() + "=" + c.count()).containsExactly("subjectAreas=1");
        // 삭제가 모델 행을 잡은 동안의 모델 수정 : 프로젝트 행을 배타로 쥔 뒤라 기다리지 않고 409 (예전에는 0행 UPDATE 로 500)
        var current = logical.selectModel(admin, model.id());
        String b = interleave(admin,
                () -> logical.deleteModel(admin, model.id(), now.modifiedAt(), now.composition()),
                () -> logical.saveModel(admin, new LogicalModelDto.Model(model.id(), "새이름", null, current.modifiedAt())));
        assertThat(b).isIn("MODEL_CONFLICT", "LOGICAL_MODEL_IS_NOT_DEFINED");
        assertThat(inTx(() -> jdbc.queryForObject("select count(*) from TB_LGCL_MDL where LGCL_MDL_SN = ?", Integer.class, model.id()))).isZero();
        assertCode(() -> logical.saveModel(admin, new LogicalModelDto.Model(model.id(), "새이름", null, current.modifiedAt())), "LOGICAL_MODEL_IS_NOT_DEFINED");
    }

    @Test
    @DisplayName("재현 7 → 고침 : 속성 수정 중 들어온 삭제(수정 전 기준값)는 기다렸다가 409 — 고친 속성 그대로")
    void attributeEditVsDelete() throws Exception {
        AccountDto admin = login("c2r7");
        Fixture f = fixture(admin);
        Long free = logical.saveAttribute(admin, new LogicalModelDto.Attribute(null, f.entityId, "자유", null, null, false, null, null, null, null, null)).id();
        LogicalModelDto.Attribute seen = attribute(free);
        String b = interleave(admin,
                () -> logical.saveAttribute(admin, edited(seen, "A 수정")),
                () -> logical.deleteAttribute(admin, free, seen.modifiedAt()));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> attributes.findById(free).orElseThrow().getAttributeDescription())).isEqualTo("A 수정");
    }

    @Test
    @DisplayName("재현 8 → 고침 : 테이블 삭제 확인 뒤(동시에) 더해진 CHECK 가 있으면 409 · 삭제가 테이블 행을 잡은 동안의 제약조건 등록은 대상 없음")
    void tableDeleteWithConcurrentConstraint() throws Exception {
        AccountDto admin = login("c2r8");
        TableInfo empty = table(admin.getProjectId(), "TB_EMPTY", false);
        TableInfoDto seen = forDelete(admin, empty.getId());
        String b = interleave(admin,
                () -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, empty.getId(), null, "CHECK", "1 = 1", List.of(), null)),
                () -> tableDesign.deleteTable(List.of(seen), admin));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> constraints.findAllByProjectIdAndTableInfo_Id(admin.getProjectId(), empty.getId()).size())).isEqualTo(1);

        TableInfoDto now = forDelete(admin, empty.getId());
        String late = interleave(admin,
                () -> tableDesign.deleteTable(List.of(now), admin),
                () -> physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, empty.getId(), null, "CHECK", "2 = 2", List.of(), null)));
        // H2·PostgreSQL 모두 기다린 잠금 조회가 지워진 행을 돌려주지 않아 대상 없음 400 (M8-C4a 확인 — C2 때 H2 의 500 은 구성 계산이 자식을 엔티티로 읽던 결함 때문이었다, 16.3)
        assertThat(late).isEqualTo("TABLE_IS_NOT_DEFINED");
        assertThat(inTx(() -> tableRepository.findById(empty.getId()))).isEmpty();
        assertThat(inTx(() -> jdbc.queryForObject("select count(*) from TB_TBL_CNSTRT where TBL_SN = ?", Integer.class, empty.getId()))).isZero();
    }

    @Test
    @DisplayName("테이블 삭제 : 여러 테이블 중 하나라도 오래되면 409 전체 롤백 · 행마다 기준값·구성 없는 HTTP 400")
    void tableDeleteBatch() {
        AccountDto admin = login("c2t1");
        TableInfo t1 = table(admin.getProjectId(), "TB_ONE", false);
        TableInfo t2 = table(admin.getProjectId(), "TB_TWO", false);
        TableInfoDto d1 = forDelete(admin, t1.getId());
        TableInfoDto d2 = forDelete(admin, t2.getId());
        tx.executeWithoutResult(s -> tableRepository.findById(t2.getId()).orElseThrow().setTableDescription("다른 사용자"));
        assertCode(() -> tableDesign.deleteTable(List.of(d1, d2), admin), "MODEL_CONFLICT");
        assertThat(inTx(() -> tableRepository.findById(t1.getId()))).isPresent();
        TableInfoDto noBase = forDelete(admin, t1.getId());
        noBase.setModifiedAt(null);
        assertCode(() -> tableApi.deleteTable(admin, List.of(noBase)), "MODEL_BASE_REQUIRED");
        TableInfoDto noComposition = forDelete(admin, t1.getId());
        noComposition.setComposition(null);
        assertCode(() -> tableApi.deleteTable(admin, List.of(noComposition)), "MODEL_BASE_REQUIRED");
        tableDesign.deleteTable(List.of(forDelete(admin, t1.getId()), forDelete(admin, t2.getId())), admin);
        assertThat(inTx(() -> tableRepository.findAllById(List.of(t1.getId(), t2.getId())))).isEmpty();
    }

    @Test
    @DisplayName("컬럼 삭제 : 오래된 기준값 409 · 일괄 저장의 삭제도 기준값 (없으면 HTTP 400, 다르면 409)")
    void columnDelete() {
        AccountDto admin = login("c2c1");
        Fixture f = fixture(admin);
        ColumnInfoDto seen = columnDto(f.thirdColumnId);
        tx.executeWithoutResult(s -> columnRepository.findById(f.thirdColumnId).orElseThrow().setColumnDesc("다른 사용자"));
        assertCode(() -> columns.deleteColumn(admin, List.of(seen)), "MODEL_CONFLICT");
        ColumnInfoDto noBase = columnDto(f.thirdColumnId);
        noBase.setModifiedAt(null);
        assertCode(() -> columnApi.deleteColumn(admin, List.of(noBase)), "MODEL_BASE_REQUIRED");

        ColumnBulkSaveDto stale = ColumnBulkSaveDto.builder().tableInfoId(f.tableId).deletedIds(List.of(f.thirdColumnId))
                .deletedBaseModifiedAt(java.util.Map.of(f.thirdColumnId, seen.getModifiedAt())).build();
        assertCode(() -> columns.saveColumnsBulk(admin, stale), "MODEL_CONFLICT");
        assertCode(() -> columnApi.saveColumnsBulk(ColumnBulkSaveDto.builder().tableInfoId(f.tableId).deletedIds(List.of(f.thirdColumnId)).build(), admin), "MODEL_BASE_REQUIRED");
        assertThat(inTx(() -> columnRepository.findById(f.thirdColumnId))).isPresent();
        ColumnBulkSaveDto.Result ok = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId).deletedIds(List.of(f.thirdColumnId))
                .deletedBaseModifiedAt(java.util.Map.of(f.thirdColumnId, columnDto(f.thirdColumnId).getModifiedAt())).build());
        assertThat(ok.result()).isTrue();
        assertThat(inTx(() -> columnRepository.findById(f.thirdColumnId))).isEmpty();
    }

    @Test
    @DisplayName("관계·인덱스·제약조건·VIEW·스키마 삭제 : 오래된 기준값 409 · 지금 기준값이면 삭제 · HTTP 기준값 없음 400")
    void physicalObjectDeletes() {
        AccountDto admin = login("c2p1");
        TableInfo t = table(admin.getProjectId(), "TB_CK", true);
        var ck = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, t.getId(), null, "CHECK", "1 = 1", List.of(), null));
        tx.executeWithoutResult(s -> constraints.findById(ck.id()).orElseThrow().setIsApproval(false));
        var seenCk = inTx(() -> constraints.findById(ck.id()).orElseThrow().modifiedAt());
        tx.executeWithoutResult(s -> constraints.findById(ck.id()).orElseThrow().setCheckExpression("2 = 2"));
        assertCode(() -> physical.delete(admin, PhysicalModelService.Kind.CONSTRAINT, ck.id(), seenCk), "MODEL_CONFLICT");
        assertCode(() -> physicalApi.delete(admin, "constraint", ck.id(), null), "MODEL_BASE_REQUIRED");
        physical.delete(admin, PhysicalModelService.Kind.CONSTRAINT, ck.id(), inTx(() -> constraints.findById(ck.id()).orElseThrow().modifiedAt()));
        assertThat(inTx(() -> constraints.findById(ck.id()))).isEmpty();

        SchemaInfo schema = inTx(() -> schemaRepository.save(SchemaInfo.builder().projectId(admin.getProjectId()).schemaName("OLD_S").isDefault(false).build()));
        var seenSchema = inTx(() -> schemaRepository.findById(schema.getId()).orElseThrow().modifiedAt());
        tx.executeWithoutResult(s -> schemaRepository.findById(schema.getId()).orElseThrow().setSchemaDescription("다른 사용자"));
        assertCode(() -> schemaService.deleteSchema(admin, schema.getId(), seenSchema), "MODEL_CONFLICT");
        assertCode(() -> schemaApi.deleteSchema(admin, schema.getId(), null), "MODEL_BASE_REQUIRED");
        schemaService.deleteSchema(admin, schema.getId(), inTx(() -> schemaRepository.findById(schema.getId()).orElseThrow().modifiedAt()));
        assertThat(inTx(() -> schemaRepository.findById(schema.getId()))).isEmpty();

        assertCode(() -> viewApi.delete(admin, 1L, null), "MODEL_BASE_REQUIRED");
    }

    // ------------------------------------------------------------------ 승인 (M8-D02)

    @Test
    @DisplayName("재현 3 → 고침 : 컬럼 승인이 행을 잡은 동안의 수정은 기다렸다가 승인된 상태를 보고 거절 · 수정 뒤의 오래된 승인은 409")
    void columnApprovalVsEdit() throws Exception {
        AccountDto admin = login("c2r3");
        Fixture f = fixture(admin);
        ColumnInfoDto seen = columnDto(f.columnId);
        ColumnBulkSaveDto.Row row = bulkRow(seen);
        row.setColumnDesc("B 설명");
        String b = interleave(admin,
                () -> columns.updateColumnApproval(admin, List.of(seen), "true"),
                () -> {
                    ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId).rows(List.of(row)).build());
                    if (!result.result()) throw new IllegalStateException(String.valueOf(result.errors()));
                });
        ColumnInfo saved = inTx(() -> columnRepository.findById(f.columnId).orElseThrow());
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(saved.getIsApproval()).isTrue();
        assertThat(saved.getColumnDesc()).isEqualTo(seen.getColumnDesc());

        // 반대 순서 : 수정이 먼저 행을 잡고, 그 사이 들어온 승인(수정 전 기준값)은 기다렸다가 409
        ColumnInfoDto other = columnDto(f.otherColumnId);
        ColumnBulkSaveDto.Row edit = bulkRow(other);
        edit.setColumnDesc("A 설명");
        String approval = interleave(admin,
                () -> {
                    ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(f.tableId).rows(List.of(edit)).build());
                    if (!result.result()) throw new IllegalStateException(String.valueOf(result.errors()));
                },
                () -> columns.updateColumnApproval(admin, List.of(other), "true"));
        assertThat(approval).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> columnRepository.findById(f.otherColumnId).orElseThrow().getIsApproval())).isFalse();
    }

    @Test
    @DisplayName("일괄 승인 : 하나라도 오래되면 409 전체 롤백 (테이블·컬럼) · 승인 권한이 없으면 기준값보다 권한 오류 · 이미 그 상태는 그대로")
    void batchApproval() {
        AccountDto admin = login("c2a1");
        TableInfo t1 = table(admin.getProjectId(), "TB_A1", false);
        TableInfo t2 = table(admin.getProjectId(), "TB_A2", false);
        TableInfoDto s1 = tableDto(t1.getId());
        TableInfoDto s2 = tableDto(t2.getId());
        tx.executeWithoutResult(s -> tableRepository.findById(t2.getId()).orElseThrow().setTableDescription("다른 사용자"));
        assertCode(() -> tableDesign.updateTableIsApproval(true, admin, List.of(s1, s2)), "MODEL_CONFLICT");
        assertThat(inTx(() -> tableRepository.findById(t1.getId()).orElseThrow().getIsApproval())).isFalse();
        TableInfoDto noBase = tableDto(t1.getId());
        noBase.setModifiedAt(null);
        assertCode(() -> tableApi.updateTableIsApproval(true, admin, List.of(noBase)), "MODEL_BASE_REQUIRED");
        tableDesign.updateTableIsApproval(true, admin, List.of(tableDto(t1.getId()), tableDto(t2.getId())));
        assertThat(inTx(() -> tableRepository.findAllById(List.of(t1.getId(), t2.getId())).stream().allMatch(TableInfo::getIsApproval))).isTrue();
        tableDesign.updateTableIsApproval(true, admin, List.of(s1)); // 이미 승인 : 예전 기준값이어도 바꾸는 것이 없다

        Fixture f = fixture(admin);
        ColumnInfoDto c1 = columnDto(f.columnId);
        ColumnInfoDto c2 = columnDto(f.otherColumnId);
        tx.executeWithoutResult(s -> columnRepository.findById(f.otherColumnId).orElseThrow().setColumnDesc("다른 사용자"));
        assertCode(() -> columns.updateColumnApproval(admin, List.of(c1, c2), "true"), "MODEL_CONFLICT");
        assertThat(inTx(() -> columnRepository.findById(f.columnId).orElseThrow().getIsApproval())).isFalse();

        AccountDto user = SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), "990003", "ROLE_USER");
        assertCode(() -> columnApi.updateColumnApproval(user, "true", List.of(ColumnInfoDto.builder().id(f.columnId).build())), "APPROVAL_PERMISSION_DENIED");
        assertCode(() -> tableApi.updateTableIsApproval(true, user, List.of(noBase)), "APPROVAL_PERMISSION_DENIED");
        assertCode(() -> logicalApi.updateEntityApproval(user, f.entityId, true, null, null), "APPROVAL_PERMISSION_DENIED");
        assertCode(() -> physicalApi.updateApproval(user, "constraint", 1L, true, null), "APPROVAL_PERMISSION_DENIED");
    }

    @Test
    @DisplayName("엔터티 승인 : 엔터티 수정이 모델 행을 잡은 동안의 승인(수정 전 기준값)은 기다렸다가 409 · HTTP 기준값 없음 400 · 지금 기준값이면 승인")
    void entityApprovalVsEdit() throws Exception {
        AccountDto admin = login("c2a2");
        Fixture f = fixture(admin);
        Long id = unapprovedEntity(admin, f.modelId, "승인대상");
        var seen = inTx(() -> LogicalModelDto.Entity.detail(entities.findById(id).orElseThrow()));
        String b = interleave(admin,
                () -> logical.saveEntity(admin, new LogicalModelDto.Entity(id, f.modelId, "승인대상", "고친 설명", null, null, null, seen.modifiedAt(), null)),
                () -> logical.updateEntityApproval(admin, id, true, seen.modifiedAt()));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> entities.findById(id).orElseThrow().getIsApproval())).isFalse();
        assertCode(() -> logicalApi.updateEntityApproval(admin, id, true, null, null), "MODEL_BASE_REQUIRED");
        logical.updateEntityApproval(admin, id, true, inTx(() -> entities.findById(id).orElseThrow().modifiedAt()));
        assertThat(inTx(() -> entities.findById(id).orElseThrow().getIsApproval())).isTrue();
    }

    @Test
    @DisplayName("제약조건 승인 ↔ 삭제 : 승인이 행을 잡은 동안의 삭제는 기다렸다가 승인된 상태를 보고 거절 — 승인된 제약조건이 지워지지 않는다 (X-03)")
    void constraintApprovalVsDelete() throws Exception {
        AccountDto admin = login("c2a3");
        TableInfo t = table(admin.getProjectId(), "TB_CK2", true);
        var ck = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, t.getId(), null, "CHECK", "1 = 1", List.of(), null));
        tx.executeWithoutResult(s -> constraints.findById(ck.id()).orElseThrow().setIsApproval(false));
        var seen = inTx(() -> constraints.findById(ck.id()).orElseThrow().modifiedAt());
        String b = interleave(admin,
                () -> physical.updateApproval(admin, PhysicalModelService.Kind.CONSTRAINT, ck.id(), true, seen),
                () -> physical.delete(admin, PhysicalModelService.Kind.CONSTRAINT, ck.id(), seen));
        assertThat(b).isIn("APPROVED_DATA_CANNOT_BE_DELETED", "MODEL_CONFLICT");
        assertThat(inTx(() -> constraints.findById(ck.id()).orElseThrow().getIsApproval())).isTrue();
    }

    // ------------------------------------------------------------------ 매핑 (M8-C2-M01)

    @Test
    @DisplayName("재현 4 → 고침 : 매핑 변경 중 들어온 해제(변경 전 기준값)는 기다렸다가 409 — 바뀐 매핑 그대로")
    void mappingChangeVsDelete() throws Exception {
        AccountDto admin = login("c2r4");
        Fixture f = fixture(admin);
        var seen = attributeMappingBase(f.attributeMappingId);
        String b = interleave(admin,
                () -> mapping.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(f.attributeMappingId, f.entityMappingId, f.attributeId, f.otherColumnId, seen)),
                () -> mapping.deleteAttributeMapping(admin, f.attributeMappingId, seen));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> attributeMappings.findById(f.attributeMappingId).orElseThrow().getColumn().getId())).isEqualTo(f.otherColumnId);
    }

    @Test
    @DisplayName("재현 5 → 고침 : 오래된 화면의 매핑 변경은 409 (다른 사용자의 변경 그대로) · 변경·해제 HTTP 기준값 없음 400 · 지금 기준값이면 변경·해제")
    void staleMappingChange() {
        AccountDto admin = login("c2r5");
        Fixture f = fixture(admin);
        var seen = attributeMappingBase(f.attributeMappingId);
        mapping.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(f.attributeMappingId, f.entityMappingId, f.attributeId, f.otherColumnId, seen));
        assertCode(() -> mapping.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(f.attributeMappingId, f.entityMappingId, f.attributeId, f.thirdColumnId, seen)),
                "MODEL_CONFLICT");
        assertThat(inTx(() -> attributeMappings.findById(f.attributeMappingId).orElseThrow().getColumn().getId())).isEqualTo(f.otherColumnId);
        assertCode(() -> mappingApi.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(f.attributeMappingId, f.entityMappingId, f.attributeId, f.thirdColumnId)),
                "MODEL_BASE_REQUIRED");
        assertCode(() -> mappingApi.deleteAttributeMapping(admin, f.attributeMappingId, null), "MODEL_BASE_REQUIRED");
        assertCode(() -> mappingApi.deleteEntityMapping(admin, f.entityMappingId, null), "MODEL_BASE_REQUIRED");
        assertCode(() -> mappingApi.saveEntityMapping(admin, new ModelMappingDto.EntityMapping(f.entityMappingId, f.entityId, f.tableId)), "MODEL_BASE_REQUIRED");
        assertCode(() -> mappingApi.deleteConversions(admin, f.entityId, null), "MODEL_BASE_REQUIRED");
        // 목록 응답이 기준값을 싣는다 (상세 속성 행 · 엔터티 행)
        var detail = mapping.selectEntityMapping(admin, f.entityMappingId);
        assertThat(detail.mapping().mappingModifiedAt()).isNotNull();
        var row = detail.attributes().stream().filter(a -> f.attributeMappingId.equals(a.mappingId())).findFirst().orElseThrow();
        assertThat(row.modifiedAt()).isNotNull();
        mapping.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(f.attributeMappingId, f.entityMappingId, f.attributeId, f.thirdColumnId, row.modifiedAt()));
        mapping.deleteAttributeMapping(admin, f.attributeMappingId, attributeMappingBase(f.attributeMappingId));
        assertThat(inTx(() -> attributeMappings.findById(f.attributeMappingId))).isEmpty();
        // 엔터티 매핑 : 오래된 기준값 해제 409, 지금 기준값 해제
        var entityBase = mapping.selectEntityMapping(admin, f.entityMappingId).mapping().mappingModifiedAt();
        TableInfo other = table(admin.getProjectId(), "TB_OTHER", true);
        mapping.saveEntityMapping(admin, new ModelMappingDto.EntityMapping(f.entityMappingId, f.entityId, other.getId(), entityBase));
        assertCode(() -> mapping.deleteEntityMapping(admin, f.entityMappingId, entityBase), "MODEL_CONFLICT");
        mapping.deleteEntityMapping(admin, f.entityMappingId, mapping.selectEntityMapping(admin, f.entityMappingId).mapping().mappingModifiedAt());
    }

    @Test
    @DisplayName("재현 6 (안전 확인) : 매핑 해제와 다른 사용자의 재매핑 — 해제 뒤 새 매핑은 새 ID 라 예전 화면이 지우지 못한다")
    void remapAfterDelete() throws Exception {
        AccountDto admin = login("c2r6");
        Fixture f = fixture(admin);
        var seen = attributeMappingBase(f.attributeMappingId);
        String b = interleave(admin,
                () -> mapping.deleteAttributeMapping(admin, f.attributeMappingId, seen),
                () -> mapping.saveAttributeMapping(admin, new ModelMappingDto.AttributeMapping(null, f.entityMappingId, f.attributeId, f.otherColumnId)));
        assertThat(b).isEqualTo("OK");
        assertCode(() -> mapping.deleteAttributeMapping(admin, f.attributeMappingId, seen), "MODEL_MAPPING_IS_NOT_DEFINED");
        assertThat(inTx(() -> attributeMappings.findByAttribute_Id(f.attributeId).orElseThrow().getColumn().getId())).isEqualTo(f.otherColumnId);
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

    private Long unapprovedEntity(AccountDto admin, Long modelId, String name) {
        Long id = logical.saveEntity(admin, new LogicalModelDto.Entity(null, modelId, name, null, null, null, null)).id();
        tx.executeWithoutResult(s -> entities.findById(id).orElseThrow().setIsApproval(false)); // 관리자가 만들면 승인 상태
        return id;
    }

    private java.time.LocalDateTime attributeMappingBase(Long id) {
        return inTx(() -> attributeMappings.findById(id).orElseThrow().modifiedAt());
    }

    private ColumnInfoDto columnDto(Long id) {
        return inTx(() -> ColumnInfoDto.fromEntity(columnRepository.findById(id).orElseThrow()));
    }

    private TableInfoDto tableDto(Long id) {
        return inTx(() -> TableInfoDto.fromEntity(tableRepository.findById(id).orElseThrow()));
    }

    /** 삭제 요청 행 : 목록 행(modifiedAt) + 삭제 확인의 구성 기준값 */
    private TableInfoDto forDelete(AccountDto admin, Long id) {
        TableInfoDto dto = tableDto(id);
        dto.setComposition(tableDesign.selectTableDeletions(List.of(id), admin).getFirst().composition());
        return dto;
    }

    private int historyRows(AccountDto admin) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, admin.getProjectId());
    }

    private static void assertCode(Runnable call, String code) {
        assertThat(outcome(call)).isEqualTo(code);
    }

    private LogicalModelDto.Attribute attribute(Long id) {
        return inTx(() -> LogicalModelDto.Attribute.fromEntity(attributes.findById(id).orElseThrow()));
    }

    private static LogicalModelDto.Attribute edited(LogicalModelDto.Attribute a, String description) {
        return new LogicalModelDto.Attribute(a.id(), a.entityId(), a.attributeName(), description, a.dataType(), a.isRequired(), a.sortOrder(), a.termId(),
                a.termName(), a.domainName(), a.domainDataType(), a.modifiedAt());
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
