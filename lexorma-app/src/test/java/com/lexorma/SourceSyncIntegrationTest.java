package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
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
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceDeletionDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceDeletionDto.Blocker;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeResult;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto.ColumnStatus;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory.ChangeType;
import com.lexorma.data.sourcemapping.domain.entity.SourceMappingHistory.TargetType;
import com.lexorma.data.sourcemapping.service.SourceCompareService;
import com.lexorma.data.sourcemapping.service.SourceDeletionService;
import com.lexorma.data.sourcemapping.service.SourceImportService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.sourcemapping.service.SourceRenameService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원본 동기화. 제약조건·인덱스 교체 (DEC-05). 원본 COMMENT 명시적 비우기 (DEC-07). 원본 삭제 후보 선택 반영 (DEC-04) : 후보(이름 변경 후보 제외)·고를 수 없는 이유(승인·참조·사용 중)·영향, 계획 → 확정(planToken·409), 직전 모델 버전·매핑 해제 이력,
 * DEC-17 급감 경고 확인, 권한·격리. 원본 이름 변경 매핑 (DEC-06, V4) : TABLE 이름 변경 후보·확정(같은 매핑 행을 새 원본으로), 컬럼 이름 변경 후보·확정·변경·해제, 확정된 컬럼 매핑을 비교·인덱스 구성·가져오기가
 * 같은 컬럼으로 쓰는지, 상태(원본 없음·모델 없음·TABLE 무효), 충돌·중복 매핑 거절, 기준 시점 409, 매핑 이력(append-only), TABLE 매핑 해제 시 컬럼 매핑 연쇄 삭제,
 * 권한·프로젝트 격리, 모델·원본 카탈로그 무변경. 원본 DB 에 접속하지 않는다 (수집 결과를 직접 넘긴다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SourceSyncIntegrationTest {

    @Autowired SourceRenameService service;
    @Autowired com.lexorma.data.sourcemapping.controller.SourceRenameRestController renameApi;
    @Autowired SourceDeletionService deletion;
    @Autowired TableRelationRepository relationRepository;
    @Autowired TableConstraintRepository constraintRepository;
    @Autowired SourceCompareService compare;
    @Autowired SourceMappingService mapping;
    @Autowired SourceImportService importService;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("TABLE 이름 변경 후보 → 확정(같은 매핑 행) → 컬럼 이름 변경 확정 → 비교·인덱스가 같은 컬럼으로 본다 → 가져오기가 매핑된 모델 컬럼을 고친다 → 해제·연쇄 삭제·이력")
    void renameFlow() {
        Env e = prepared("rename");
        Long customerMapping = mappingId(e, "customer");
        collect(e, renamed(30));
        Integer order = compare.summary(e.manager).reflectedOrder();
        String modelBefore = modelFingerprint(e);

        // 후보 : 사라진 customer 매핑 ↔ client (컬럼 이름 4개 중 2개 겹침 = 0.5). 새로 생긴 brand_new 는 겹치는 컬럼이 없어 후보가 아니다
        SourceRenameDto.Candidates candidates = service.candidates(e.manager);
        assertThat(candidates.tables()).extracting(SourceRenameDto.TableCandidate::sourceTableName).containsExactly("client");
        SourceRenameDto.TableCandidate table = candidates.tables().get(0);
        assertThat(table.mappingId()).isEqualTo(customerMapping);
        assertThat(table.removedSourceTableName()).isEqualTo("customer");
        assertThat(table.matchedColumns()).isEqualTo(2);
        assertThat(table.ambiguous()).isFalse();
        assertThat(candidates.removedTables()).singleElement().satisfies(r -> assertThat(r.hasCandidate()).isTrue());

        // 기준 시점이 다르면 409, 아직 원본이 있는 매핑(orders)은 옮길 수 없다
        rejected(() -> service.confirmTable(e.manager, new SourceRenameDto.TableRenameRequest(e.connection, order - 1, customerMapping, table.sourceTableId())),
                ErrorCode.SOURCE_COMPARE_STALE);
        rejected(() -> service.confirmTable(e.manager, new SourceRenameDto.TableRenameRequest(e.connection, order, mappingId(e, "orders"), table.sourceTableId())),
                ErrorCode.SOURCE_MAPPING_INVALID);

        service.confirmTable(e.manager, new SourceRenameDto.TableRenameRequest(e.connection, order, customerMapping, table.sourceTableId()));
        SourceMappingDto.TableRow row = mapping.tables(e.manager, null, null, "client", 0, 50).rows().get(0);
        assertThat(row.mappingId()).isEqualTo(customerMapping);
        assertThat(row.status()).isEqualTo(SourceMappingDto.Status.MAPPED);
        assertThat(row.tableName()).isEqualTo("CUSTOMER");
        assertThat(service.candidates(e.manager).removedTables()).isEmpty();
        assertThat(modelFingerprint(e)).isEqualTo(modelBefore);

        // 컬럼 : full_name ↔ CUST_NAME 후보. 이미 이름으로 짝지어진 EMAIL 은 거절, 이름이 같은 것도 거절
        SourceRenameDto.ColumnCandidate column = service.candidates(e.manager).columns().stream().filter(c -> c.sourceColumnName().equals("full_name")).findFirst()
                .orElseThrow();
        assertThat(column.columnName()).isEqualTo("CUST_NAME");
        Long custName = column.columnId();
        Long email = columnId(e, "CUSTOMER", "EMAIL");
        rejected(() -> service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, order, customerMapping, "full_name", email)),
                ErrorCode.SOURCE_MAPPING_CONFLICT);
        rejected(() -> service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, order, customerMapping, "email", email)),
                ErrorCode.SOURCE_MAPPING_INVALID);
        rejected(() -> service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, order, customerMapping, "no_such", custName)),
                ErrorCode.SOURCE_MAPPING_INVALID);

        List<SourceRenameDto.ColumnMapping> mapped = service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, order, customerMapping, "full_name", custName));
        assertThat(mapped).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo(ColumnStatus.MAPPED);
            assertThat(m.columnName()).isEqualTo("CUST_NAME");
            assertThat(m.sourceColumnName()).isEqualTo("full_name");
        });
        // 같은 모델 컬럼을 다른 원본 컬럼에 또 매핑할 수 없다
        rejected(() -> service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, order, customerMapping, "nickname", custName)),
                ErrorCode.SOURCE_MAPPING_CONFLICT);

        // 비교 : full_name 은 CUST_NAME 과 같은 컬럼(이름 차이는 상태에 넣지 않음), 인덱스 구성도 같다, 이름 변경 후보가 남지 않는다
        SourceCompareDto.TableDetail detail = compare.table(e.manager, null, null, "S" + table.sourceTableId(), null);
        SourceCompareDto.ObjectDiff fullName = object(detail, ObjectKind.COLUMN, "full_name");
        assertThat(fullName.modelName()).isEqualTo("CUST_NAME");
        assertThat(fullName.status()).isEqualTo(CompareStatus.SAME);
        assertThat(fullName.attributes()).anySatisfy(a -> {
            assertThat(a.attribute()).isEqualTo("name");
            assertThat(a.result()).isEqualTo(AttributeResult.NOT_COMPARED);
            assertThat(a.note()).isEqualTo(SourceCompareDto.Note.RENAME_MAPPED);
        });
        assertThat(object(detail, ObjectKind.INDEX, "ix_customer_name").status()).isEqualTo(CompareStatus.SAME);
        assertThat(detail.renameCandidates()).noneMatch(r -> r.kind() == ObjectKind.COLUMN && "full_name".equals(r.sourceName()));
        assertThat(modelFingerprint(e)).isEqualTo(modelBefore);

        // 가져오기 : 원본 full_name 길이가 바뀌면 매핑된 CUST_NAME 의 타입(길이 포함, 항목 COLUMN:full_name:TYPE)을 고르게 된다 (새 컬럼 추가 항목이 없다)
        collect(e, renamed(40));
        Integer next = compare.summary(e.manager).reflectedOrder();
        SourceImportDto.TableCandidates importable = importService.candidates(e.manager, e.connection, next, "S" + table.sourceTableId());
        assertThat(importable.items()).noneMatch(i -> i.kind() == ObjectKind.COLUMN && "full_name".equals(i.sourceName()) && i.action() == SourceImportDto.Action.ADD);
        SourceImportDto.Item length = importable.items().stream().filter(i -> i.kind() == ObjectKind.COLUMN && "full_name".equals(i.sourceName()) && "dataType".equals(i.attribute()))
                .findFirst().orElseThrow(() -> new AssertionError(importable.items()));
        assertThat(length.modelName()).isEqualTo("CUST_NAME");
        assertThat(length.selectable()).isTrue();
        SourceImportDto.Plan plan = importService.plan(e.manager, request(e, next, table.sourceTableId(), List.of(length.id()), null, null));
        importService.apply(e.manager, request(e, next, table.sourceTableId(), List.of(length.id()), plan.planToken(), plan.requestId()));
        assertThat(jdbc.queryForObject("select MAX_LEN from TB_COL where COL_SN = ?", Integer.class, custName)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ?", Integer.class, e.table("CUSTOMER").getId())).isEqualTo(4);

        // 해제 → 다시 이름 변경 후보, TABLE 매핑 해제 → 컬럼 매핑도 지워진다
        SourceRenameDto.ColumnMapping seenColumn = service.columnMappings(e.manager, customerMapping).get(0);
        Long columnMappingId = seenColumn.id();
        // 화면이 본 뒤 그 매핑 행이 바뀌었으면(수정 일시) 해제하지 않는다 (M8-C4c-1 C4c-D03) — HTTP 는 modifiedAt 필수
        assertThat(seenColumn.modifiedAt()).isNotNull();
        assertThatThrownBy(() -> renameApi.releaseColumn(e.manager, columnMappingId, null)).extracting("errorCode").isEqualTo(ErrorCode.MODEL_BASE_REQUIRED);
        rejected(() -> service.releaseColumn(e.manager, columnMappingId, seenColumn.modifiedAt().minusSeconds(1)), ErrorCode.MODEL_CONFLICT);
        assertThat(service.columnMappings(e.manager, customerMapping)).hasSize(1);
        service.releaseColumn(e.manager, columnMappingId, seenColumn.modifiedAt());
        assertThat(service.columnMappings(e.manager, customerMapping)).isEmpty();
        assertThat(compare.table(e.manager, null, null, "S" + table.sourceTableId(), "COLUMN").renameCandidates())
                .anyMatch(r -> "full_name".equals(r.sourceName()) && "CUST_NAME".equals(r.modelName()));
        service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, next, customerMapping, "full_name", custName));
        mapping.unmapTable(e.manager, customerMapping);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_COL_MPNG where SRC_TBL_MPNG_SN = ?", Integer.class, customerMapping)).isZero();

        // 이력 (최근 것부터) : TABLE 해제 · 컬럼 해제(연쇄) · 컬럼 확정 · 컬럼 해제 · 컬럼 확정 · TABLE 이름 변경 · TABLE 확정
        List<SourceRenameDto.History> history = service.history(e.manager, customerMapping, 0, 50).rows();
        assertThat(history).extracting(h -> h.targetType() + ":" + h.changeType()).containsExactly(
                "TABLE:RELEASE", "COLUMN:RELEASE", "COLUMN:CONFIRM", "COLUMN:RELEASE", "COLUMN:CONFIRM", "TABLE:RENAME", "TABLE:CONFIRM");
        SourceRenameDto.History rename = history.stream().filter(h -> h.changeType() == ChangeType.RENAME).findFirst().orElseThrow();
        assertThat(rename.before()).containsEntry("sourceTableName", "customer").containsEntry("tableName", "CUSTOMER");
        assertThat(rename.after()).containsEntry("sourceTableName", "client");
        assertThat(rename.changedBy()).isNotBlank();
        assertThat(history.stream().filter(h -> h.targetType() == TargetType.COLUMN && h.changeType() == ChangeType.CONFIRM).findFirst().orElseThrow().after())
                .containsEntry("sourceColumnName", "full_name").containsEntry("columnName", "CUST_NAME");
    }

    @Test
    @DisplayName("컬럼 매핑 상태(원본 없음·모델 없음), 프로젝트 관리자만 변경, 다른 프로젝트의 매핑·이력은 보이지도 바뀌지도 않는다")
    void statesPermissionsAndIsolation() {
        Env e = prepared("rename-state");
        Long customerMapping = mappingId(e, "customer");
        collect(e, renamed(30));
        Integer order = compare.summary(e.manager).reflectedOrder();
        Long client = e.sourceTableId("client");
        service.confirmTable(e.manager, new SourceRenameDto.TableRenameRequest(e.connection, order, customerMapping, client));
        Long custName = columnId(e, "CUSTOMER", "CUST_NAME");
        service.confirmColumn(e.manager, new SourceRenameDto.ColumnRequest(e.connection, order, customerMapping, "full_name", custName));

        // 다른 프로젝트 : 후보·매핑·이력이 비어 있고, 이 프로젝트의 매핑 ID 로 바꿀 수 없다
        Env other = prepared("rename-other");
        assertThat(service.columnMappings(other.manager, null)).isEmpty();
        assertThat(service.history(other.manager, customerMapping, 0, 50).rows()).isEmpty();
        Integer otherOrder = compare.summary(other.manager).reflectedOrder();
        rejected(() -> service.confirmColumn(other.manager, new SourceRenameDto.ColumnRequest(other.connection, otherOrder, customerMapping, "full_name", custName)),
                ErrorCode.SOURCE_MAPPING_INVALID);
        Long columnMappingId = service.columnMappings(e.manager, null).get(0).id();
        rejected(() -> service.releaseColumn(other.manager, columnMappingId), ErrorCode.SOURCE_MAPPING_INVALID);

        // 프로젝트 관리자가 아닌 구성원은 변경할 수 없다 (조회는 된다)
        Account member = account("mem", "ROLE_USER");
        tx.executeWithoutResult(s -> projectMemberRepository.save(ProjectMember.builder().project(projectRepository.findById(e.project.getId()).orElseThrow())
                .account(accountRepository.findById(member.getId()).orElseThrow()).isActive(true).build()));
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(member.getId()), "ROLE_USER");
        assertThat(service.columnMappings(user, null)).hasSize(1);
        assertThatThrownBy(() -> service.releaseColumn(user, columnMappingId)).isInstanceOf(CustomException.class);
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.ownerId), "ROLE_DBA");

        // 원본에서 full_name 이 사라지면 원본 없음 (이름으로 다른 컬럼과 저절로 잇지 않는다)
        collect(e, Map.of("public", List.of(client(30, false), orders(), brandNew())));
        assertThat(service.columnMappings(manager, null)).singleElement().satisfies(m -> assertThat(m.status()).isEqualTo(ColumnStatus.SOURCE_MISSING));
        // 모델 컬럼을 지우면 모델 없음 (FK SET NULL, 행은 남는다)
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from TB_TBL_IDX_COL where COL_SN = ?", custName);
            jdbc.update("delete from TB_COL where COL_SN = ?", custName);
        });
        assertThat(service.columnMappings(manager, null)).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo(ColumnStatus.MODEL_MISSING);
            assertThat(m.columnId()).isNull();
        });
    }

    @Test
    @DisplayName("DEC-04 : 삭제 후보(이름 변경 후보 제외)·고를 수 없는 이유·영향 → 계획 → 확정(직전 버전·이력·매핑 해제), 바뀐 계획 409, 다시 적용 거절, 권한·격리")
    void deletionFlow() {
        Env e = prepared("delete");
        tx.executeWithoutResult(s -> {
            TableInfo orders = e.table("ORDERS");
            column(orders, "LEGACY", "VARCHAR", 10, true, false, 3);
            ColumnInfo approved = column(orders, "OLD_APPROVED", "VARCHAR", 10, true, false, 4);
            approved.setIsApproval(true);
            columnRepository.save(approved);
            ColumnInfo used = column(orders, "USED_COL", "INTEGER", null, true, false, 5);
            TableIndex index = TableIndex.builder().projectId(orders.getProjectId()).tableInfo(orders).indexName("IX_ORDERS_USED").isUnique(false)
                    .indexSource(TableIndex.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
            index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(used).columnOrder(1).build());
            indexRepository.save(index);
            for (String name : List.of("OLD_TBL", "ARCH_TBL", "REF_TBL")) {
                TableInfo t = table(e, name);
                column(t, "ID", "INTEGER", null, false, true, 1);
                if (name.equals("ARCH_TBL")) {
                    t.setIsApproval(true);
                    tableRepository.save(t);
                }
            }
            ColumnInfo custId = columnRepository.findAll().stream().filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(orders.getId())
                    && c.getColumnName().equals("CUST_ID")).findFirst().orElseThrow();
            ColumnInfo refId = columnRepository.findAll().stream().filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(e.table("REF_TBL").getId()))
                    .findFirst().orElseThrow();
            TableRelation relation = TableRelation.builder().projectId(orders.getProjectId()).sourceTable(orders).targetTable(e.table("REF_TBL")).relationName("FK_ORDERS_REF")
                    .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
            relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(custId).targetColumn(refId).columnOrder(1).build());
            relationRepository.save(relation);
        });
        jdbc.update("update TB_TBL set TASK_EXPLN = '지울 테이블' where TBL_SN = ?", e.table("OLD_TBL").getId());
        Map<String, List<SourceCollectionSpec.Table>> withExtra = new LinkedHashMap<>(original());
        List<SourceCollectionSpec.Table> pub = new ArrayList<>(withExtra.get("public"));
        for (String name : List.of("old_tbl", "arch_tbl", "ref_tbl")) pub.add(single(name));
        withExtra.put("public", pub);
        collect(e, withExtra);
        for (String name : List.of("old_tbl", "arch_tbl", "ref_tbl")) {
            mapping.mapTable(e.manager, new SourceMappingDto.TableRequest(e.sourceTableId(name), e.table(name.toUpperCase()).getId()));
        }
        // 원본 2차 : customer → client (이름 변경 후보), old_tbl·arch_tbl·ref_tbl 이 사라짐
        collect(e, renamed(30));

        SourceDeletionDto.Candidates candidates = deletion.candidates(e.manager);
        assertThat(candidates.dropSuspected()).isFalse();
        assertThat(candidates.renameCandidates()).isPositive();
        Map<String, SourceDeletionDto.Candidate> items = new LinkedHashMap<>();
        candidates.items().forEach(i -> items.put(i.kind() == SourceDeletionDto.Kind.TABLE ? i.tableName() : i.tableName() + "." + i.columnName(), i));
        assertThat(items).doesNotContainKey("CUSTOMER");
        assertThat(items).containsKeys("OLD_TBL", "ARCH_TBL", "REF_TBL", "ORDERS.LEGACY", "ORDERS.OLD_APPROVED", "ORDERS.USED_COL");
        assertThat(items.get("OLD_TBL").selectable()).isTrue();
        assertThat(items.get("OLD_TBL").sourceName()).isEqualTo("old_tbl");
        assertThat(items.get("OLD_TBL").impact().columns()).isEqualTo(1);
        assertThat(items.get("OLD_TBL").impact().bizDescriptions()).isEqualTo(1);
        assertThat(items.get("ARCH_TBL").blockers()).containsExactly(Blocker.APPROVED);
        assertThat(items.get("REF_TBL").blockers()).containsExactly(Blocker.REFERENCED);
        assertThat(items.get("REF_TBL").details()).anyMatch(d -> d.contains("FK_ORDERS_REF"));
        assertThat(items.get("ORDERS.LEGACY").selectable()).isTrue();
        assertThat(items.get("ORDERS.OLD_APPROVED").blockers()).containsExactly(Blocker.APPROVED);
        assertThat(items.get("ORDERS.USED_COL").blockers()).containsExactly(Blocker.IN_USE);
        assertThat(items.get("ORDERS.USED_COL").details()).contains("IX_ORDERS_USED");

        Integer order = compare.summary(e.manager).reflectedOrder();
        List<String> chosen = List.of(items.get("OLD_TBL").id(), items.get("ORDERS.LEGACY").id());
        SourceDeletionDto.Plan blocked = deletion.plan(e.manager, new SourceDeletionDto.Request(e.connection, order, List.of(items.get("ARCH_TBL").id()), null, null, null));
        assertThat(blocked.applicable()).isFalse();
        SourceDeletionDto.Plan plan = deletion.plan(e.manager, new SourceDeletionDto.Request(e.connection, order, chosen, null, null, null));
        assertThat(plan.applicable()).isTrue();
        // 다른 프로젝트 · 관리자 아닌 구성원
        Env other = prepared("delete-other");
        assertThat(deletion.candidates(other.manager).items()).noneMatch(i -> i.tableId().equals(e.table("OLD_TBL").getId()));
        Account member = account("mem", "ROLE_USER");
        tx.executeWithoutResult(s -> projectMemberRepository.save(ProjectMember.builder().project(projectRepository.findById(e.project.getId()).orElseThrow())
                .account(accountRepository.findById(member.getId()).orElseThrow()).isActive(true).build()));
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(member.getId()), "ROLE_USER");
        assertThatThrownBy(() -> deletion.plan(user, new SourceDeletionDto.Request(e.connection, order, chosen, null, null, null))).isInstanceOf(CustomException.class);
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, e.project.getId(), String.valueOf(e.ownerId), "ROLE_DBA");

        // 계획 뒤 모델이 바뀌면(영향이 달라짐) 409, 모델은 그대로
        jdbc.update("update TB_COL set TASK_EXPLN = '바뀜' where TBL_SN = ? and COL_NM = 'ID'", e.table("OLD_TBL").getId());
        rejected(() -> deletion.apply(manager, new SourceDeletionDto.Request(e.connection, order, chosen, null, plan.planToken(), plan.requestId())), ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where TBL_SN = ?", Integer.class, e.table("OLD_TBL").getId())).isOne();

        SourceDeletionDto.Plan fresh = deletion.plan(manager, new SourceDeletionDto.Request(e.connection, order, chosen, null, null, null));
        SourceDeletionDto.Result result = deletion.apply(manager, new SourceDeletionDto.Request(e.connection, order, chosen, null, fresh.planToken(), fresh.requestId()));
        assertThat(result.tablesDeleted()).isOne();
        assertThat(result.columnsDeleted()).isEqualTo(2);
        assertThat(result.modelVersionName()).isEqualTo("DELETE-" + fresh.requestId());
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where TBL_SN = ?", Integer.class, e.table("OLD_TBL").getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM = 'LEGACY'", Integer.class, e.table("ORDERS").getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM in ('OLD_APPROVED', 'USED_COL')", Integer.class, e.table("ORDERS").getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where TBL_SN = ?", Integer.class, e.table("ARCH_TBL").getId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_TBL_MPNG where SRC_CNTN_SN = ? and CMPR_NM = 'old_tbl'", Integer.class, e.connection)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ? and CHG_TYPE_CD = 'DELETE'", Integer.class,
                e.project.getId(), fresh.requestId())).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_MPNG_HSTRY where PRJ_SN = ? and CHG_TYPE_CD = 'RELEASE' and TRGT_TYPE_CD = 'TABLE'", Integer.class,
                e.project.getId())).isOne();
        // 같은 요청은 다시 적용되지 않는다
        rejected(() -> deletion.apply(manager, new SourceDeletionDto.Request(e.connection, order, List.of(items.get("ORDERS.LEGACY").id()), null, fresh.planToken(), fresh.requestId())),
                ErrorCode.SOURCE_COMPARE_STALE);
    }

    @Test
    @DisplayName("DEC-04 + DEC-17 : 기준 수집이 직전 정상 수집의 절반 미만이면 급감 확인 없이는 지우지 않는다")
    void deletionNeedsDropAcknowledge() {
        Env e = prepared("delete-drop");
        tx.executeWithoutResult(s -> column(e.table("ORDERS"), "LEGACY", "VARCHAR", 10, true, false, 3));
        collect(e, Map.of("public", List.of(orders())));
        SourceDeletionDto.Candidates candidates = deletion.candidates(e.manager);
        assertThat(candidates.dropSuspected()).isTrue();
        String legacy = candidates.items().stream().filter(i -> "LEGACY".equals(i.columnName())).findFirst().orElseThrow().id();
        Integer order = candidates.reflectedOrder();
        SourceDeletionDto.Plan plan = deletion.plan(e.manager, new SourceDeletionDto.Request(e.connection, order, List.of(legacy), null, null, null));
        assertThat(plan.dropSuspected()).isTrue();
        rejected(() -> deletion.apply(e.manager, new SourceDeletionDto.Request(e.connection, order, List.of(legacy), false, plan.planToken(), plan.requestId())),
                ErrorCode.SOURCE_MAPPING_INVALID);
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM = 'LEGACY'", Integer.class, e.table("ORDERS").getId())).isOne();
        deletion.apply(e.manager, new SourceDeletionDto.Request(e.connection, order, List.of(legacy), true, plan.planToken(), plan.requestId()));
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where TBL_SN = ? and COL_NM = 'LEGACY'", Integer.class, e.table("ORDERS").getId())).isZero();
    }

    @Test
    @DisplayName("DEC-07 : 원본 COMMENT 가 지워져도 자동으로 비우지 않고(EMPTY_COMMENT), 명시적 비우기 항목만 반영한다. 승인 컬럼은 거절, 업무 설명·DDL 설명은 그대로")
    void commentClear() {
        Env e = prepared("comment-clear");
        Long ordersId = e.table("ORDERS").getId();
        jdbc.update("update TB_TBL set SRC_ANTN_CN = '주문', TASK_EXPLN = '주문 업무' where TBL_SN = ?", ordersId);
        jdbc.update("update TB_COL set SRC_ANTN_CN = '고객 번호', COL_EXPLN = '고객 번호 설명', TASK_EXPLN = '고객 업무' where TBL_SN = ? and COL_NM = 'CUST_ID'", ordersId);
        jdbc.update("update TB_COL set SRC_ANTN_CN = '번호', APRV_YN = true where TBL_SN = ? and COL_NM = 'ID'", ordersId);
        Integer order = compare.summary(e.manager).reflectedOrder();
        String key = "S" + e.sourceTableId("orders");
        Map<String, SourceImportDto.Item> items = new LinkedHashMap<>();
        importService.candidates(e.manager, e.connection, order, key).items().forEach(i -> items.put(i.id(), i));
        assertThat(items.get("COLUMN:cust_id:COMMENT").selectable()).isFalse();
        assertThat(items.get("COLUMN:cust_id:COMMENT").reason()).isEqualTo(SourceImportDto.Reason.EMPTY_COMMENT);
        SourceImportDto.Item clear = items.get("COLUMN:cust_id:COMMENT_CLEAR");
        assertThat(clear.action()).isEqualTo(SourceImportDto.Action.CLEAR);
        assertThat(clear.selectable()).isTrue();
        assertThat(clear.before()).isEqualTo("고객 번호");
        assertThat(clear.after()).isNull();
        assertThat(items.get("COLUMN:id:COMMENT_CLEAR").selectable()).isFalse();
        assertThat(items.get("COLUMN:id:COMMENT_CLEAR").reason()).isEqualTo(SourceImportDto.Reason.APPROVED);
        assertThat(items.get("TABLE:COMMENT_CLEAR").selectable()).isTrue();

        // 비우기를 고르지 않은 가져오기는 원본 COMMENT 칸을 그대로 둔다
        List<String> chosen = List.of("COLUMN:cust_id:COMMENT_CLEAR", "TABLE:COMMENT_CLEAR");
        SourceImportDto.Plan plan = importService.plan(e.manager, request(e, order, e.sourceTableId("orders"), chosen, null, null));
        assertThat(plan.applicable()).isTrue();
        importService.apply(e.manager, request(e, order, e.sourceTableId("orders"), chosen, plan.planToken(), plan.requestId()));
        assertThat(jdbc.queryForObject("select SRC_ANTN_CN from TB_COL where TBL_SN = ? and COL_NM = 'CUST_ID'", String.class, ordersId)).isNull();
        assertThat(jdbc.queryForObject("select SRC_ANTN_CN from TB_TBL where TBL_SN = ?", String.class, ordersId)).isNull();
        assertThat(jdbc.queryForObject("select SRC_ANTN_CN from TB_COL where TBL_SN = ? and COL_NM = 'ID'", String.class, ordersId)).isEqualTo("번호");
        assertThat(jdbc.queryForObject("select TASK_EXPLN from TB_TBL where TBL_SN = ?", String.class, ordersId)).isEqualTo("주문 업무");
        assertThat(jdbc.queryForObject("select COL_EXPLN || '|' || TASK_EXPLN from TB_COL where TBL_SN = ? and COL_NM = 'CUST_ID'", String.class, ordersId))
                .isEqualTo("고객 번호 설명|고객 업무");
        // 이미 비웠으면 더 이상 항목이 없다
        assertThat(importService.candidates(e.manager, e.connection, order, key).items()).noneMatch(i -> i.id().equals("COLUMN:cust_id:COMMENT_CLEAR"));
    }

    @Test
    @DisplayName("DEC-05 : 구성이 바뀐 인덱스·PK 는 교체 항목으로만 고르고(미승인만), 반영하면 기존 객체를 지우고 원본 구성으로 다시 만든다. 참조되는 PK·승인 객체는 거절")
    void replaceConstraintsAndIndexes() {
        Env e = prepared("replace");
        // 원본 : customer 의 PK 가 (id, email), 인덱스 ix_customer_name 이 email 로 바뀜
        SourceCollectionSpec.Table customer = new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("customer"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, false), col("cust_name", 2, "character varying(30)", 30, true), col("email", 3, "character varying(50)", 50, false),
                        col("nick", 4, "character varying(30)", 30, true)),
                List.of(new SourceCatalogSpec.Constraint("customer_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id"), Name.of("email")))),
                List.of(index("customer_pkey", true, "id"), index("ix_customer_name", false, "email")), List.of());
        collect(e, Map.of("public", List.of(customer, orders())));
        Integer order = compare.summary(e.manager).reflectedOrder();
        Long customerTable = e.table("CUSTOMER").getId();
        String key = "S" + e.sourceTableId("customer");
        Map<String, SourceImportDto.Item> items = new LinkedHashMap<>();
        importService.candidates(e.manager, e.connection, order, key).items().forEach(i -> items.put(i.id(), i));
        assertThat(items.get("INDEX:ix_customer_name").selectable()).isFalse();
        assertThat(items.get("INDEX:ix_customer_name").reason()).isEqualTo(SourceImportDto.Reason.REPLACE_REQUIRED);
        SourceImportDto.Item replaceIndex = items.get("INDEX:ix_customer_name:REPLACE");
        assertThat(replaceIndex.action()).isEqualTo(SourceImportDto.Action.REPLACE);
        assertThat(replaceIndex.selectable()).isTrue();
        assertThat(replaceIndex.modelName()).isEqualTo("IX_CUSTOMER_NAME");
        assertThat(items.get("PRIMARY_KEY:customer_pkey:REPLACE").selectable()).isTrue();

        // 다른 TABLE 의 관계가 CUSTOMER 를 참조하면 PK 교체 불가, 승인된 인덱스는 교체 불가
        Long relationId = tx.execute(s -> {
            TableInfo orders = tableRepository.findById(e.table("ORDERS").getId()).orElseThrow();
            ColumnInfo custId = columnRepository.findById(columnId(e, "ORDERS", "CUST_ID")).orElseThrow();
            ColumnInfo id = columnRepository.findById(columnId(e, "CUSTOMER", "ID")).orElseThrow();
            TableRelation relation = TableRelation.builder().projectId(orders.getProjectId()).sourceTable(orders).targetTable(tableRepository.findById(customerTable).orElseThrow())
                    .relationName("FK_ORDERS_CUSTOMER").relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
            relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(custId).targetColumn(id).columnOrder(1).build());
            return relationRepository.save(relation).getId();
        });
        jdbc.update("update TB_TBL_IDX set APRV_YN = true where TBL_SN = ? and IDX_NM = 'IX_CUSTOMER_NAME'", customerTable);
        items.clear();
        importService.candidates(e.manager, e.connection, order, key).items().forEach(i -> items.put(i.id(), i));
        assertThat(items.get("PRIMARY_KEY:customer_pkey:REPLACE").reason()).isEqualTo(SourceImportDto.Reason.REFERENCED);
        assertThat(items.get("INDEX:ix_customer_name:REPLACE").reason()).isEqualTo(SourceImportDto.Reason.APPROVED);
        jdbc.update("update TB_TBL_IDX set APRV_YN = false where TBL_SN = ? and IDX_NM = 'IX_CUSTOMER_NAME'", customerTable);
        tx.executeWithoutResult(s -> relationRepository.deleteById(relationId));

        // 반영 : 인덱스는 같은 이름으로 email 구성, PK 는 ID·EMAIL (EMAIL 은 NOT NULL)
        List<String> chosen = List.of("INDEX:ix_customer_name:REPLACE", "PRIMARY_KEY:customer_pkey:REPLACE");
        SourceImportDto.Plan plan = importService.plan(e.manager, request(e, order, e.sourceTableId("customer"), chosen, null, null));
        assertThat(plan.applicable()).isTrue();
        importService.apply(e.manager, request(e, order, e.sourceTableId("customer"), chosen, plan.planToken(), plan.requestId()));
        assertThat(jdbc.queryForList("select c.COL_NM from TB_TBL_IDX i join TB_TBL_IDX_COL x on x.IDX_SN = i.IDX_SN join TB_COL c on c.COL_SN = x.COL_SN "
                + "where i.TBL_SN = ? and i.IDX_NM = 'IX_CUSTOMER_NAME' order by x.COL_ORD", String.class, customerTable)).containsExactly("EMAIL");
        assertThat(jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and PK_YN = true order by SORT_ORD", String.class, customerTable)).containsExactly("ID", "EMAIL");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ? and TRGT_TYPE_CD = 'PHYSICAL_INDEX' and CHG_TYPE_CD = 'DELETE'",
                Integer.class, e.project.getId(), plan.requestId())).isOne();
        SourceCompareDto.TableDetail detail = compare.table(e.manager, null, null, key, null);
        assertThat(object(detail, ObjectKind.INDEX, "ix_customer_name").status()).isEqualTo(CompareStatus.SAME);
        assertThat(detail.objects().stream().filter(o -> o.kind() == ObjectKind.PRIMARY_KEY).findFirst().orElseThrow().status()).isEqualTo(CompareStatus.SAME);
    }

    @Test
    @DisplayName("DEC-05 보완 : UNIQUE(구성)·CHECK(식)·FK(삭제 규칙) 교체, 판단 불가 CHECK 는 교체 대상 아님, 승인 제약 거부, 계획 뒤 모델 변경 409·무변경")
    void replaceUniqueCheckForeignKey() {
        Env e = prepared("replace2");
        // 모델 : UK_CUSTOMER_EMAIL(EMAIL), CK_CUSTOMER_FLAG(true), CK_CUSTOMER_ID((id > 0)), FK_ORDERS_CUSTOMER(CUST_ID → CUSTOMER.ID, 규칙 없음)
        tx.executeWithoutResult(s -> {
            TableInfo customer = tableRepository.findById(e.table("CUSTOMER").getId()).orElseThrow();
            ColumnInfo email = columnRepository.findById(columnId(e, "CUSTOMER", "EMAIL")).orElseThrow();
            TableConstraint unique = TableConstraint.builder().projectId(customer.getProjectId()).tableInfo(customer).constraintName("UK_CUSTOMER_EMAIL")
                    .constraintType(TableConstraint.Type.UNIQUE).isApproval(false).columns(new ArrayList<>()).build();
            unique.getColumns().add(TableConstraintColumn.builder().tableConstraint(unique).column(email).columnOrder(1).build());
            constraintRepository.save(unique);
            constraintRepository.save(TableConstraint.builder().projectId(customer.getProjectId()).tableInfo(customer).constraintName("CK_CUSTOMER_FLAG")
                    .constraintType(TableConstraint.Type.CHECK).checkExpression("true").isApproval(false).columns(new ArrayList<>()).build());
            constraintRepository.save(TableConstraint.builder().projectId(customer.getProjectId()).tableInfo(customer).constraintName("CK_CUSTOMER_ID")
                    .constraintType(TableConstraint.Type.CHECK).checkExpression("(id > 0)").isApproval(false).columns(new ArrayList<>()).build());
            TableInfo orders = tableRepository.findById(e.table("ORDERS").getId()).orElseThrow();
            TableRelation fk = TableRelation.builder().projectId(orders.getProjectId()).sourceTable(orders).targetTable(customer).relationName("FK_ORDERS_CUSTOMER")
                    .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
            fk.getColumns().add(TableRelationColumn.builder().relation(fk).sourceColumn(columnRepository.findById(columnId(e, "ORDERS", "CUST_ID")).orElseThrow())
                    .targetColumn(columnRepository.findById(columnId(e, "CUSTOMER", "ID")).orElseThrow()).columnOrder(1).build());
            relationRepository.save(fk);
        });
        // 원본 : uk 구성 (email, nick), ck_customer_flag false, ck_customer_id (id >= 1) (판단 불가), fk ON DELETE CASCADE
        SourceCollectionSpec.Table customer = new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("customer"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, false), col("cust_name", 2, "character varying(30)", 30, true), col("email", 3, "character varying(50)", 50, true),
                        col("nick", 4, "character varying(30)", 30, true)),
                List.of(new SourceCatalogSpec.Constraint("customer_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id"))),
                        new SourceCatalogSpec.Constraint("uk_customer_email", SourceConstraintType.UNIQUE, null, List.of(Name.of("email"), Name.of("nick"))),
                        new SourceCatalogSpec.Constraint("ck_customer_flag", SourceConstraintType.CHECK, "false", List.of()),
                        new SourceCatalogSpec.Constraint("ck_customer_id", SourceConstraintType.CHECK, "(id >= 1)", List.of(Name.of("id")))),
                List.of(index("customer_pkey", true, "id"), index("ix_customer_name", false, "cust_name")), List.of());
        SourceCollectionSpec.Table orders = new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "bigint", null, false), col("cust_id", 2, "integer", null, true)),
                List.of(new SourceCatalogSpec.Constraint("orders_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))),
                List.of(index("orders_pkey", true, "id")),
                List.of(new SourceCatalogSpec.Relation("fk_orders_customer", null, null, Name.of("customer"), "CASCADE", "NO ACTION",
                        List.of(new SourceCatalogSpec.ColumnPair(Name.of("cust_id"), Name.of("id"))))));
        collect(e, Map.of("public", List.of(customer, orders)));
        Integer order = compare.summary(e.manager).reflectedOrder();
        String customerKey = "S" + e.sourceTableId("customer");
        String ordersKey = "S" + e.sourceTableId("orders");
        Map<String, SourceImportDto.Item> items = new LinkedHashMap<>();
        importService.candidates(e.manager, e.connection, order, customerKey).items().forEach(i -> items.put(i.id(), i));
        importService.candidates(e.manager, e.connection, order, ordersKey).items().forEach(i -> items.put(i.id(), i));
        assertThat(items.get("UNIQUE:uk_customer_email").reason()).isEqualTo(SourceImportDto.Reason.REPLACE_REQUIRED);
        assertThat(items.get("UNIQUE:uk_customer_email:REPLACE")).satisfies(i -> assertThat(i.selectable()).as(String.valueOf(i.reason())).isTrue());
        assertThat(items.get("CHECK:ck_customer_flag:REPLACE")).satisfies(i -> assertThat(i.selectable()).as(String.valueOf(i.reason())).isTrue());
        assertThat(items).as("식 차이를 판단하지 못한 CHECK 는 교체하지 않는다").doesNotContainKey("CHECK:ck_customer_id:REPLACE");
        // 판단 불가(UNKNOWN) : 고를 수 없고 수동 검토용으로 원본·지금 모델의 정의를 함께 준다 (7차). 골라서 보내도 계획에 들어가지 않는다
        assertThat(items.get("CHECK:ck_customer_id")).satisfies(i -> {
            assertThat(i.reason()).isEqualTo(SourceImportDto.Reason.UNKNOWN);
            assertThat(i.selectable()).isFalse();
            assertThat(i.source()).contains("id >= 1");
            assertThat(i.before()).contains("id > 0");
        });
        SourceImportDto.Plan unknownPlan = importService.plan(e.manager, request(e, order, e.sourceTableId("customer"), List.of("CHECK:ck_customer_id"), null, null));
        assertThat(unknownPlan.applicable()).as("판단 불가 항목만으로는 적용할 수 없다").isFalse();
        assertThat(items.get("FOREIGN_KEY:fk_orders_customer:REPLACE")).satisfies(i -> assertThat(i.selectable()).as(String.valueOf(i.reason())).isTrue());

        // 승인된 UNIQUE 는 교체할 수 없다
        jdbc.update("update TB_TBL_CNSTRT set APRV_YN = true where TBL_SN = ? and CNSTRT_NM = 'UK_CUSTOMER_EMAIL'", e.table("CUSTOMER").getId());
        assertThat(importService.candidates(e.manager, e.connection, order, customerKey).items()).filteredOn(i -> i.id().equals("UNIQUE:uk_customer_email:REPLACE"))
                .singleElement().satisfies(i -> assertThat(i.reason()).isEqualTo(SourceImportDto.Reason.APPROVED));
        jdbc.update("update TB_TBL_CNSTRT set APRV_YN = false where TBL_SN = ? and CNSTRT_NM = 'UK_CUSTOMER_EMAIL'", e.table("CUSTOMER").getId());

        // 계획 뒤 모델이 바뀌면 409, 아무것도 바뀌지 않는다 (직전 버전도 없다)
        List<String> chosen = List.of("UNIQUE:uk_customer_email:REPLACE", "CHECK:ck_customer_flag:REPLACE");
        SourceImportDto.Plan stale = importService.plan(e.manager, request(e, order, e.sourceTableId("customer"), chosen, null, null));
        assertThat(stale.applicable()).isTrue();
        tx.executeWithoutResult(s -> constraintRepository.findAll().stream().filter(c -> "CK_CUSTOMER_FLAG".equals(c.getConstraintName())
                && c.getProjectId().equals(e.project.getId())).forEach(c -> c.setCheckExpression("null")));
        rejected(() -> importService.apply(e.manager, request(e, order, e.sourceTableId("customer"), chosen, stale.planToken(), stale.requestId())),
                ErrorCode.SOURCE_COMPARE_STALE);
        assertThat(jdbc.queryForList("select c.COL_NM from TB_TBL_CNSTRT k join TB_TBL_CNSTRT_COL x on x.CNSTRT_SN = k.CNSTRT_SN join TB_COL c on c.COL_SN = x.COL_SN "
                + "where k.TBL_SN = ? and k.CNSTRT_NM = 'UK_CUSTOMER_EMAIL' order by x.COL_ORD", String.class, e.table("CUSTOMER").getId())).containsExactly("EMAIL");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, e.project.getId())).isZero();
        tx.executeWithoutResult(s -> constraintRepository.findAll().stream().filter(c -> "CK_CUSTOMER_FLAG".equals(c.getConstraintName())
                && c.getProjectId().equals(e.project.getId())).forEach(c -> c.setCheckExpression("true")));

        // 반영 : UNIQUE 는 같은 이름으로 (EMAIL, NICK), CHECK 는 false, FK 는 ON DELETE CASCADE
        SourceImportDto.Plan plan = importService.plan(e.manager, request(e, order, e.sourceTableId("customer"), chosen, null, null));
        importService.apply(e.manager, request(e, order, e.sourceTableId("customer"), chosen, plan.planToken(), plan.requestId()));
        List<String> fkItems = List.of("FOREIGN_KEY:fk_orders_customer:REPLACE");
        SourceImportDto.Plan fkPlan = importService.plan(e.manager, request(e, order, e.sourceTableId("orders"), fkItems, null, null));
        importService.apply(e.manager, request(e, order, e.sourceTableId("orders"), fkItems, fkPlan.planToken(), fkPlan.requestId()));
        Long customerTable = e.table("CUSTOMER").getId();
        assertThat(jdbc.queryForList("select c.COL_NM from TB_TBL_CNSTRT k join TB_TBL_CNSTRT_COL x on x.CNSTRT_SN = k.CNSTRT_SN join TB_COL c on c.COL_SN = x.COL_SN "
                + "where k.TBL_SN = ? and k.CNSTRT_NM = 'UK_CUSTOMER_EMAIL' order by x.COL_ORD", String.class, customerTable)).containsExactly("EMAIL", "NICK");
        assertThat(jdbc.queryForObject("select INSP_EXPR_CN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_NM = 'CK_CUSTOMER_FLAG'", String.class, customerTable)).isEqualTo("false");
        assertThat(jdbc.queryForObject("select INSP_EXPR_CN from TB_TBL_CNSTRT where TBL_SN = ? and CNSTRT_NM = 'CK_CUSTOMER_ID'", String.class, customerTable)).isEqualTo("(id > 0)");
        assertThat(jdbc.queryForObject("select DEL_RULE_CD from TB_TBL_REL where ORGNL_TBL_SN = ? and upper(REL_NM) = 'FK_ORDERS_CUSTOMER'", String.class, e.table("ORDERS").getId()))
                .isEqualTo("CASCADE");
        assertThat(jdbc.queryForObject("select count(*) from TB_TBL_REL where ORGNL_TBL_SN = ? and upper(REL_NM) = 'FK_ORDERS_CUSTOMER'", Integer.class, e.table("ORDERS").getId())).isOne();
        SourceCompareDto.TableDetail after = compare.table(e.manager, null, null, customerKey, null);
        assertThat(object(after, ObjectKind.UNIQUE, "uk_customer_email").status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(after, ObjectKind.CHECK, "ck_customer_flag").status()).isEqualTo(CompareStatus.SAME);
        assertThat(object(compare.table(e.manager, null, null, ordersKey, null), ObjectKind.FOREIGN_KEY, "fk_orders_customer").status()).isEqualTo(CompareStatus.SAME);
    }

    @Test
    @DisplayName("DEC-05 : 한 계획의 교체 두 개(UNIQUE → INDEX) 중 뒤의 것이 DB 에서 실패하면 전부 되돌린다 — 기존 제약조건·인덱스, 직전 모델 버전·변경 이력·가져오기 기록 없음. 원인을 없애면 같은 항목을 다시 반영할 수 있다")
    void replaceRollsBackWhenLaterItemFails() {
        Env e = prepared("replace-rollback");
        Long customerTable = e.table("CUSTOMER").getId();
        tx.executeWithoutResult(s -> {
            TableInfo customer = tableRepository.findById(customerTable).orElseThrow();
            TableConstraint unique = TableConstraint.builder().projectId(customer.getProjectId()).tableInfo(customer).constraintName("UK_CUSTOMER_EMAIL")
                    .constraintType(TableConstraint.Type.UNIQUE).isApproval(false).columns(new ArrayList<>()).build();
            unique.getColumns().add(TableConstraintColumn.builder().tableConstraint(unique).column(columnRepository.findById(columnId(e, "CUSTOMER", "EMAIL")).orElseThrow())
                    .columnOrder(1).build());
            constraintRepository.save(unique);
        });
        // 원본 : UNIQUE 구성 (email, nick), 인덱스 ix_customer_name 이 email 로 바뀜 → 두 교체 항목
        SourceCollectionSpec.Table customer = new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("customer"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, false), col("cust_name", 2, "character varying(30)", 30, true), col("email", 3, "character varying(50)", 50, true),
                        col("nick", 4, "character varying(30)", 30, true)),
                List.of(new SourceCatalogSpec.Constraint("customer_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id"))),
                        new SourceCatalogSpec.Constraint("uk_customer_email", SourceConstraintType.UNIQUE, null, List.of(Name.of("email"), Name.of("nick")))),
                List.of(index("customer_pkey", true, "id"), index("ix_customer_name", false, "email")), List.of());
        collect(e, Map.of("public", List.of(customer, orders())));
        Integer order = compare.summary(e.manager).reflectedOrder();
        Long source = e.sourceTableId("customer");
        List<String> chosen = List.of("UNIQUE:uk_customer_email:REPLACE", "INDEX:ix_customer_name:REPLACE");
        SourceImportDto.Plan plan = importService.plan(e.manager, request(e, order, source, chosen, null, null));
        assertThat(plan.applicable()).isTrue();
        int history = jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, e.project.getId());
        int mappingHistory = jdbc.queryForObject("select count(*) from TB_SRC_MPNG_HSTRY where PRJ_SN = ?", Integer.class, e.project.getId());

        // 테스트 DB 에만 거는 임시 CHECK : 이 TABLE 에 지금 있는 것 말고 새로 넣는 IX_CUSTOMER_NAME 행은 거절 (UNIQUE 교체는 이미 지나간 뒤). H2·PostgreSQL 같은 문장.
        // 새 행의 번호로 가르지 않는다 — Hibernate 가 미리 받아 둔 시퀀스 묶음에 따라 기존 번호보다 작을 수 있다
        Long existingIndex = jdbc.queryForObject("select IDX_SN from TB_TBL_IDX where TBL_SN = ? and IDX_NM = 'IX_CUSTOMER_NAME'", Long.class, customerTable);
        jdbc.execute("alter table TB_TBL_IDX add constraint CK_TEST_FAIL_INDEX check (IDX_NM <> 'IX_CUSTOMER_NAME' or TBL_SN <> " + customerTable
                + " or IDX_SN = " + existingIndex + ")");
        try {
            assertThatThrownBy(() -> importService.apply(e.manager, request(e, order, source, chosen, plan.planToken(), plan.requestId())))
                    .satisfies(ex -> assertThat(org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(ex).toLowerCase()).as("주입한 실패로 멈췄다").contains("ck_test_fail_index"));
        } finally {
            jdbc.execute("alter table TB_TBL_IDX drop constraint CK_TEST_FAIL_INDEX");
        }
        String uniqueColumns = "select c.COL_NM from TB_TBL_CNSTRT k join TB_TBL_CNSTRT_COL x on x.CNSTRT_SN = k.CNSTRT_SN join TB_COL c on c.COL_SN = x.COL_SN "
                + "where k.TBL_SN = ? and k.CNSTRT_NM = 'UK_CUSTOMER_EMAIL' order by x.COL_ORD";
        String indexColumns = "select c.COL_NM from TB_TBL_IDX i join TB_TBL_IDX_COL x on x.IDX_SN = i.IDX_SN join TB_COL c on c.COL_SN = x.COL_SN "
                + "where i.TBL_SN = ? and i.IDX_NM = 'IX_CUSTOMER_NAME' order by x.COL_ORD";
        assertThat(jdbc.queryForList(uniqueColumns, String.class, customerTable)).as("먼저 지나간 UNIQUE 교체도 되돌린다").containsExactly("EMAIL");
        assertThat(jdbc.queryForList(indexColumns, String.class, customerTable)).as("지운 인덱스가 돌아온다").containsExactly("CUST_NAME");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, e.project.getId())).as("직전 모델 버전도 남지 않는다").isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, e.project.getId())).as("변경 이력 일부 기록 없음").isEqualTo(history);
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_IMPRT_EXCN where CHG_GROUP_ID = ?", Integer.class, plan.requestId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from TB_SRC_MPNG_HSTRY where PRJ_SN = ?", Integer.class, e.project.getId())).isEqualTo(mappingHistory);

        // 원인을 없애면 다시 계획해 반영할 수 있다
        SourceImportDto.Plan again = importService.plan(e.manager, request(e, order, source, chosen, null, null));
        importService.apply(e.manager, request(e, order, source, chosen, again.planToken(), again.requestId()));
        assertThat(jdbc.queryForList(uniqueColumns, String.class, customerTable)).containsExactly("EMAIL", "NICK");
        assertThat(jdbc.queryForList(indexColumns, String.class, customerTable)).containsExactly("EMAIL");
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, e.project.getId())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_GROUP_ID = ?", Integer.class, e.project.getId(), again.requestId())).isPositive();
    }

    private static SourceCollectionSpec.Table single(String name) {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), SourceTableType.TABLE, null), List.of(col("id", 1, "integer", null, false)),
                List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------------ 원본 · 모델

    /** 원본 1차 : customer(id, cust_name, email, nick) + 인덱스 ix_customer_name(cust_name), orders(id, cust_id) */
    private static Map<String, List<SourceCollectionSpec.Table>> original() {
        SourceCollectionSpec.Table customer = new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("customer"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "integer", null, false), col("cust_name", 2, "character varying(30)", 30, true), col("email", 3, "character varying(50)", 50, true),
                        col("nick", 4, "character varying(30)", 30, true)),
                List.of(new SourceCatalogSpec.Constraint("customer_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))),
                List.of(index("customer_pkey", true, "id"), index("ix_customer_name", false, "cust_name")), List.of());
        return Map.of("public", List.of(customer, orders()));
    }

    /** 원본 2차 : customer → client, cust_name → full_name(길이 length), nick → nickname. brand_new 가 새로 생김 */
    private static Map<String, List<SourceCollectionSpec.Table>> renamed(int length) {
        return Map.of("public", List.of(client(length, true), orders(), brandNew()));
    }

    private static SourceCollectionSpec.Table client(int length, boolean withFullName) {
        List<SourceCatalogSpec.Column> columns = new ArrayList<>(List.of(col("id", 1, "integer", null, false)));
        if (withFullName) columns.add(col("full_name", 2, "character varying(" + length + ")", length, true));
        columns.add(col("email", 3, "character varying(50)", 50, true));
        columns.add(col("nickname", 4, "character varying(30)", 30, true));
        List<SourceCatalogSpec.Index> indexes = new ArrayList<>(List.of(index("client_pkey", true, "id")));
        if (withFullName) indexes.add(index("ix_customer_name", false, "full_name"));
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("client"), SourceTableType.TABLE, null), columns,
                List.of(new SourceCatalogSpec.Constraint("client_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))), indexes, List.of());
    }

    private static SourceCollectionSpec.Table orders() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null),
                List.of(col("id", 1, "bigint", null, false), col("cust_id", 2, "integer", null, true)),
                List.of(new SourceCatalogSpec.Constraint("orders_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))),
                List.of(index("orders_pkey", true, "id")), List.of());
    }

    private static SourceCollectionSpec.Table brandNew() {
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("brand_new"), SourceTableType.TABLE, null),
                List.of(col("x", 1, "integer", null, true)), List.of(), List.of(), List.of());
    }

    private static SourceCatalogSpec.Column col(String name, int ordinal, String type, Integer length, boolean nullable) {
        return new SourceCatalogSpec.Column(Name.of(name), ordinal, type, length, null, null, nullable, null, null);
    }

    private static SourceCatalogSpec.Index index(String name, boolean unique, String column) {
        return new SourceCatalogSpec.Index(name, "btree", unique, List.of(new SourceCatalogSpec.IndexMember(Name.of(column), null, null)), null);
    }

    /** 모델 : DEFAULT 에 CUSTOMER(ID, CUST_NAME, EMAIL, NICK + IX_CUSTOMER_NAME)·ORDERS(ID, CUST_ID). 스키마·TABLE 매핑 확정까지 */
    private Env prepared(String name) {
        Env e = env(name);
        mapping.setBase(e.manager, new SourceMappingDto.BaseRequest(e.connection, null));
        collect(e, original());
        tx.executeWithoutResult(s -> {
            TableInfo customer = table(e, "CUSTOMER");
            column(customer, "ID", "INTEGER", null, false, true, 1);
            ColumnInfo custName = column(customer, "CUST_NAME", "VARCHAR", 30, true, false, 2);
            column(customer, "EMAIL", "VARCHAR", 50, true, false, 3);
            column(customer, "NICK", "VARCHAR", 30, true, false, 4);
            TableIndex index = TableIndex.builder().projectId(customer.getProjectId()).tableInfo(customer).indexName("IX_CUSTOMER_NAME").isUnique(false)
                    .indexSource(TableIndex.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
            index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(custName).columnOrder(1).build());
            indexRepository.save(index);
            TableInfo orders = table(e, "ORDERS");
            column(orders, "ID", "BIGINT", null, false, true, 1);
            column(orders, "CUST_ID", "INTEGER", null, true, false, 2);
        });
        Long publicSchema = mapping.overview(e.manager).schemas().stream().filter(r -> r.sourceSchemaName().equals("public")).findFirst().orElseThrow().sourceSchemaId();
        mapping.mapSchema(e.manager, new SourceMappingDto.SchemaRequest(publicSchema, e.defaultSchema.getId()));
        mapping.mapTable(e.manager, new SourceMappingDto.TableRequest(e.sourceTableId("customer"), e.table("CUSTOMER").getId()));
        mapping.mapTable(e.manager, new SourceMappingDto.TableRequest(e.sourceTableId("orders"), e.table("ORDERS").getId()));
        return e;
    }

    private TableInfo table(Env e, String name) {
        TableInfo table = tableRepository.save(TableInfo.builder().projectId(e.project.getId()).tableName(name).isMasterTable(false).sortOrder(1).isApproval(false)
                .schema(schemaRepository.findById(e.defaultSchema.getId()).orElseThrow()).build());
        e.tables.put(name, table);
        return table;
    }

    private ColumnInfo column(TableInfo table, String name, String type, Integer length, boolean nullable, boolean pk, int order) {
        return columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableInfo(table).tableName(table.getTableName()).columnName(name).dataType(type)
                .maxLength(length).isNullable(nullable).isRequired(false).isPk(pk).isUnique(false).isIndex(false).sortOrder(order).isApproval(false).isMasterData(false)
                .isSensitive(false).isEncrypted(false).build());
    }

    // ------------------------------------------------------------------ 준비 · 확인

    private final class Env {
        final Project project;
        final AccountDto manager;
        final Long ownerId;
        final Long connection;
        final SchemaInfo defaultSchema;
        final Map<String, TableInfo> tables = new LinkedHashMap<>();

        Env(Project project, AccountDto manager, Long ownerId, Long connection, SchemaInfo defaultSchema) {
            this.project = project;
            this.manager = manager;
            this.ownerId = ownerId;
            this.connection = connection;
            this.defaultSchema = defaultSchema;
        }

        TableInfo table(String name) {
            return tables.get(name);
        }

        Long sourceTableId(String name) {
            return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = 'public' "
                    + "and t.TBL_NM = ?", Long.class, connection, name);
        }
    }

    private Env env(String name) {
        Account owner = account("own", "ROLE_DBA");
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo defaultSchema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        return new Env(project, manager, owner.getId(), connection, defaultSchema);
    }

    private void collect(Env e, Map<String, List<SourceCollectionSpec.Table>> schemas) {
        List<SourceCollectionSpec.Schema> result = new ArrayList<>();
        schemas.forEach((schema, tables) -> result.add(SourceCollectionSpec.Schema.completed(null, Name.of(schema), tables)));
        ExecutionContext context = ExecutionContext.user(e.manager);
        collections.apply(context, collections.start(context, e.connection, SourceCollectionSpec.Request.full()).jobId(), new SourceCollectionSpec.Result(result));
    }

    private Long mappingId(Env e, String sourceTable) {
        return jdbc.queryForObject("select SRC_TBL_MPNG_SN from TB_SRC_TBL_MPNG where SRC_CNTN_SN = ? and CMPR_NM = ?", Long.class, e.connection, sourceTable);
    }

    private Long columnId(Env e, String table, String column) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, e.table(table).getId(), column);
    }

    private static SourceImportDto.Request request(Env e, Integer order, Long sourceTableId, List<String> items, String token, String requestId) {
        return new SourceImportDto.Request(e.connection, order, List.of(new SourceImportDto.TableSelection("S" + sourceTableId, null, items)), token, requestId);
    }

    private static SourceCompareDto.ObjectDiff object(SourceCompareDto.TableDetail detail, ObjectKind kind, String sourceName) {
        return detail.objects().stream().filter(o -> o.kind() == kind && sourceName.equals(o.sourceName())).findFirst()
                .orElseThrow(() -> new AssertionError(kind + " " + sourceName + " 없음 : " + detail.objects()));
    }

    /** 모델 TABLE·컬럼·인덱스의 이름·승인 상태 : 이름 변경 매핑이 모델을 바꾸지 않는지 */
    private String modelFingerprint(Env e) {
        Long pid = e.project.getId();
        return String.join("|", jdbc.queryForList("select TBL_NM || ':' || APRV_YN from TB_TBL where PRJ_SN = ? order by TBL_SN", String.class, pid)) + "#"
                + String.join("|", jdbc.queryForList("select COL_NM || ':' || APRV_YN || ':' || coalesce(cast(MAX_LEN as varchar(20)), '') from TB_COL where PRJ_SN = ? order by COL_SN",
                String.class, pid)) + "#" + jdbc.queryForObject("select count(*) from TB_TBL_IDX where PRJ_SN = ?", Integer.class, pid);
    }

    private Account account(String prefix, String role) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }
}
