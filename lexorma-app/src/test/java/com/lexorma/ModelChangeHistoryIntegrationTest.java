package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.controller.ColumnManageRestController;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.history.controller.ModelChangeHistoryRestController;
import com.lexorma.data.history.domain.dto.ModelChangeHistoryDto;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.Action;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.Source;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.physical.controller.PhysicalModelRestController;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnPair;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Relation;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.controller.TableDesignRestController;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * M-9 1차 (H2 / PostgreSQL) : 물리 모델 변경 이력.
 * 화면이 부르는 컨트롤러·서비스로 모델을 바꾸고, 실제로 달라진 객체만·같은 작업은 같은 그룹으로·같은 트랜잭션에 이력이 남는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelChangeHistoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired ModelChangeHistoryRepository historyRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignRestController tables;
    @Autowired ColumnManageRestController columns;
    @Autowired PhysicalModelRestController physical;
    @Autowired ModelChangeHistoryRestController history;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 테이블

    @Test
    @DisplayName("테이블 : 생성·수정·승인·승인취소·삭제가 전후 상태와 함께 남고, 같은 값으로 다시 저장하거나 거절된 요청은 이력을 남기지 않는다")
    void tableLifecycle() {
        AccountDto admin = loginAsAdmin("m9-table");
        long projectId = admin.getProjectId();

        TableInfo table = table(projectId, "TB_ORDER", false);
        ModelChangeHistory created = only(projectId);
        assertThat(created).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getTargetName, ModelChangeHistory::getAction,
                        ModelChangeHistory::getSource, ModelChangeHistory::getUserId)
                .containsExactly(TargetType.PHYSICAL_TABLE, table.getId(), "TB_ORDER", Action.CREATE, Source.USER, admin.getUsername());
        assertThat(created.getBeforeData()).isNull();
        assertThat(json(created.getAfterData()).get("tableName").asString()).isEqualTo("TB_ORDER");
        assertThat(json(created.getAfterData()).get("isApproval").asBoolean()).isFalse();
        assertThat(created.getChangedAt()).isNotNull();

        // 설명만 변경 → UPDATE 1건, 전후 값은 실제 저장된 값
        long mark = mark();
        tables.updateTable(update(table, "TB_ORDER", "주문"), admin);
        ModelChangeHistory updated = onlySince(projectId, mark);
        assertThat(updated.getAction()).isEqualTo(Action.UPDATE);
        assertThat(json(updated.getBeforeData()).get("tableDescription").isNull()).isTrue();
        assertThat(json(updated.getAfterData()).get("tableDescription").asString()).isEqualTo("주문");
        assertThat(changedFields(admin, updated)).containsExactly("tableDescription");

        // 같은 값으로 다시 저장 (두 번) → 이력 없음
        mark = mark();
        tables.updateTable(update(table, "TB_ORDER", "주문"), admin);
        tables.updateTable(update(table, "TB_ORDER", "주문"), admin);
        assertThat(since(projectId, mark)).isEmpty();

        // 승인 : 권한 없는 사용자는 거절되고 이력이 없다
        AccountDto user = login(projectId, "930001", "ROLE_USER");
        mark = mark();
        rejected(() -> tables.updateTableIsApproval(true, user, List.of(dto(table))), ErrorCode.APPROVAL_PERMISSION_DENIED);
        assertThat(since(projectId, mark)).isEmpty();

        // 승인 → APPROVE (UPDATE 와 겹치지 않는다), 이미 승인된 것을 다시 승인 → 이력 없음, 승인취소 → REVOKE
        relogin(admin);
        tables.updateTableIsApproval(true, admin, List.of(dto(table)));
        ModelChangeHistory approved = onlySince(projectId, mark);
        assertThat(approved.getAction()).isEqualTo(Action.APPROVE);
        assertThat(json(approved.getBeforeData()).get("isApproval").asBoolean()).isFalse();
        assertThat(json(approved.getAfterData()).get("isApproval").asBoolean()).isTrue();
        mark = mark();
        tables.updateTableIsApproval(true, admin, List.of(dto(table)));
        assertThat(since(projectId, mark)).isEmpty();

        // 승인된 테이블 수정은 거절 → 이력 없음
        rejected(() -> tables.updateTable(update(table, "TB_ORDER", "바뀜"), admin), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        assertThat(since(projectId, mark)).isEmpty();

        tables.updateTableIsApproval(false, admin, List.of(dto(table)));
        assertThat(onlySince(projectId, mark).getAction()).isEqualTo(Action.REVOKE);

        // 삭제 : 컬럼이 있는 테이블은 삭제가 거절된다 → 이력 없음. 컬럼을 지운 뒤 테이블을 지운다 (요청이 둘이라 그룹도 둘)
        ColumnInfo column = column(projectId, table, "ORDER_NO", true, null);
        long beforeDelete = mark();
        assertThatThrownBy(() -> tables.deleteTable(admin, List.of(dto(table)))).isInstanceOf(CustomException.class);
        assertThat(since(projectId, beforeDelete)).isEmpty();
        columns.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(column.getId()).build()));
        tables.deleteTable(admin, List.of(dto(table)));
        assertThat(tableInfoRepository.findById(table.getId())).isEmpty();
        List<ModelChangeHistory> deleted = since(projectId, beforeDelete);
        assertThat(deleted).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getAction)
                .containsExactly(tuple(TargetType.PHYSICAL_COLUMN, column.getId(), Action.DELETE), tuple(TargetType.PHYSICAL_TABLE, table.getId(), Action.DELETE));
        assertThat(deleted.get(0).getChangeGroupId()).isNotEqualTo(deleted.get(1).getChangeGroupId());
        assertThat(json(deleted.get(0).getBeforeData()).get("columnName").asString()).isEqualTo("ORDER_NO");
        // 삭제 전 상태가 남고, 삭제된 뒤에도 객체별 이력이 조회된다
        ModelChangeHistory tableDeleted = deleted.get(1);
        assertThat(tableDeleted.getAfterData()).isNull();
        assertThat(json(tableDeleted.getBeforeData()).get("tableDescription").asString()).isEqualTo("주문");

        List<ModelChangeHistoryDto.Summary> ofTable = search(admin, "PHYSICAL_TABLE", table.getId(), null, null, null, null, null, 0, 100);
        assertThat(ofTable).extracting(ModelChangeHistoryDto.Summary::action).containsExactly("DELETE", "REVOKE", "APPROVE", "UPDATE", "CREATE"); // 최신 순
    }

    @Test
    @DisplayName("테이블명 변경 : 테이블·소속 컬럼·그 테이블을 참조하던 컬럼·자동으로 맞춘 관계가 한 그룹에 남고, 자동 동기화 관계는 AUTO_SYNC 다")
    void renameCascadesInOneGroup() {
        AccountDto admin = loginAsAdmin("m9-rename");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT", false);
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD", false);
        column(projectId, child, "CHILD_ID", true, null);

        // 참조 테이블명 지정 → 커밋 직전 자동 동기화가 REF_COLUMN 관계를 만든다 : 컬럼 변경과 같은 그룹, 출처 AUTO_SYNC
        long mark = mark();
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, "TB_PARENT");
        List<ModelChangeHistory> made = since(projectId, mark);
        assertThat(made).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getAction, ModelChangeHistory::getSource)
                .containsExactly(tuple(TargetType.PHYSICAL_COLUMN, Action.CREATE, Source.USER), tuple(TargetType.PHYSICAL_RELATION, Action.CREATE, Source.AUTO_SYNC));
        assertThat(made).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(made.getFirst().getChangeGroupId());
        JsonNode relation = json(made.get(1).getAfterData());
        assertThat(relation.get("derivedColumnId").asLong()).isEqualTo(ref.getId()); // 원인 컬럼
        assertThat(relation.get("columns").get(0).get("sourceColumnId").asLong()).isEqualTo(ref.getId());
        assertThat(relation.get("columns").get(0).get("targetColumnId").asLong()).isEqualTo(parentId.getId());
        long relationId = made.get(1).getTargetId();

        // 다시 동기화해도 달라진 것이 없으면 이력이 없다
        mark = mark();
        tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(projectId, List.of(parent.getId(), child.getId())));
        assertThat(since(projectId, mark)).isEmpty();

        // 원본 테이블명 변경 → 테이블 UPDATE + 소속 컬럼 2개(테이블명) + 관계(이름이 따라간다, AUTO_SYNC), 모두 같은 그룹
        tables.updateTable(update(child, "TB_KID", null), admin);
        List<ModelChangeHistory> renamed = since(projectId, mark);
        assertThat(renamed).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(renamed.getFirst().getChangeGroupId());
        assertThat(renamed).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getAction, ModelChangeHistory::getSource)
                .containsExactlyInAnyOrder(tuple(TargetType.PHYSICAL_TABLE, Action.UPDATE, Source.USER),
                        tuple(TargetType.PHYSICAL_COLUMN, Action.UPDATE, Source.USER), tuple(TargetType.PHYSICAL_COLUMN, Action.UPDATE, Source.USER),
                        tuple(TargetType.PHYSICAL_RELATION, Action.UPDATE, Source.AUTO_SYNC));
        ModelChangeHistory relationRenamed = renamed.stream().filter(h -> h.getTargetType() == TargetType.PHYSICAL_RELATION).findFirst().orElseThrow();
        assertThat(relationRenamed.getTargetId()).isEqualTo(relationId);
        assertThat(json(relationRenamed.getBeforeData()).get("relationName").asString()).isEqualTo("FK_TB_CHILD_PARENT_ID");
        assertThat(json(relationRenamed.getAfterData()).get("relationName").asString()).isEqualTo("FK_TB_KID_PARENT_ID");
        assertThat(json(relationRenamed.getAfterData()).get("columns")).isEqualTo(json(relationRenamed.getBeforeData()).get("columns")); // 컬럼 구성은 그대로

        // 대상 테이블명 변경 → 참조하던 컬럼의 참조 테이블명이 따라간다 (다른 테이블의 컬럼도 같은 그룹)
        mark = mark();
        tables.updateTable(update(parent, "TB_MOTHER", null), admin);
        List<ModelChangeHistory> targetRenamed = since(projectId, mark);
        assertThat(targetRenamed).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(targetRenamed.getFirst().getChangeGroupId());
        ModelChangeHistory refChanged = targetRenamed.stream().filter(h -> h.getTargetId().equals(ref.getId()) && h.getTargetType() == TargetType.PHYSICAL_COLUMN).findFirst().orElseThrow();
        assertThat(json(refChanged.getBeforeData()).get("refTableName").asString()).isEqualTo("TB_PARENT");
        assertThat(json(refChanged.getAfterData()).get("refTableName").asString()).isEqualTo("TB_MOTHER");
        // 관계는 대상 테이블 ID·컬럼·이름이 그대로라 이력이 없다
        assertThat(targetRenamed).noneMatch(h -> h.getTargetType() == TargetType.PHYSICAL_RELATION);

        // 서로 다른 요청은 서로 다른 그룹
        assertThat(targetRenamed.getFirst().getChangeGroupId()).isNotEqualTo(renamed.getFirst().getChangeGroupId());
    }

    // ------------------------------------------------------------------ 컬럼

    @Test
    @DisplayName("컬럼 일괄 저장 : 20개 중 실제로 달라진 2개만, 순서를 바꾸면 순서가 바뀐 컬럼만 남는다. 검증에 걸린 요청·롤백된 트랜잭션은 이력이 없다")
    void bulkColumnsRecordOnlyRealChanges() {
        AccountDto admin = loginAsAdmin("m9-bulk");
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "TB_BULK", true);
        List<StandardTerm> terms = IntStream.rangeClosed(1, 21).mapToObj(i -> termRepository.save(StandardTerm.builder().projectId(projectId)
                .commonStandardTermName("항목" + i).commonStandardTermAbbreviation("ITEM_" + i).isApproval(true).build())).toList();
        // 승인 권한이 없는 사용자 : 등록한 컬럼이 승인 대기라 계속 고칠 수 있다
        AccountDto user = login(projectId, "930002", "ROLE_USER");

        long mark = mark();
        List<ColumnBulkSaveDto.Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("new-" + i, null, terms.get(i).getId(), "설명 " + i, "VARCHAR", 10 + i));
        assertThat(columns.saveColumnsBulk(bulk(table, rows, List.of()), user).getBody().created()).isEqualTo(20);
        List<ModelChangeHistory> created = since(projectId, mark);
        assertThat(created).hasSize(20).allMatch(h -> h.getAction() == Action.CREATE && h.getTargetType() == TargetType.PHYSICAL_COLUMN && h.getBeforeData() == null);
        assertThat(created).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(created.getFirst().getChangeGroupId());
        assertThat(created).extracting(ModelChangeHistory::getUserId).containsOnly(user.getUsername());
        assertThat(created.getFirst().getTargetName()).isEqualTo("TB_BULK.ITEM_1");

        List<ColumnInfo> saved = columnInfoRepository.findAll().stream().filter(c -> projectId == c.getProjectId())
                .sorted(Comparator.comparing(ColumnInfo::getSortOrder)).toList();
        List<ColumnBulkSaveDto.Row> same = new ArrayList<>();
        for (int i = 0; i < 20; i++) same.add(row(String.valueOf(saved.get(i).getId()), saved.get(i).getId(), null, "설명 " + i, "VARCHAR", 10 + i));

        // 그대로 다시 저장 (두 번) → 이력 없음
        mark = mark();
        columns.saveColumnsBulk(bulk(table, same, List.of()), user);
        columns.saveColumnsBulk(bulk(table, same, List.of()), user);
        assertThat(since(projectId, mark)).isEmpty();

        // 20개를 보내고 2개만 바꾼다 (설명, 타입) → UPDATE 2건
        same.get(3).setColumnDesc("바뀐 설명");
        same.get(7).setDataType("NUMERIC");
        columns.saveColumnsBulk(bulk(table, same, List.of()), user);
        List<ModelChangeHistory> updated = since(projectId, mark);
        assertThat(updated).extracting(ModelChangeHistory::getTargetId, ModelChangeHistory::getAction)
                .containsExactlyInAnyOrder(tuple(saved.get(3).getId(), Action.UPDATE), tuple(saved.get(7).getId(), Action.UPDATE));
        ModelChangeHistory typeChanged = updated.stream().filter(h -> h.getTargetId().equals(saved.get(7).getId())).findFirst().orElseThrow();
        assertThat(json(typeChanged.getBeforeData()).get("dataType").asString()).isEqualTo("VARCHAR");
        assertThat(json(typeChanged.getAfterData()).get("dataType").asString()).isEqualTo("NUMERIC");
        assertThat(changedFields(admin, typeChanged)).containsExactly("dataType");

        // 순서 변경 : 앞의 두 컬럼만 자리를 바꾼다 → 그 둘만, 달라진 필드는 정렬 순서뿐
        relogin(user);
        mark = mark();
        ColumnBulkSaveDto.Row first = same.remove(0);
        same.add(1, first);
        columns.saveColumnsBulk(bulk(table, same, List.of()), user);
        List<ModelChangeHistory> reordered = since(projectId, mark);
        assertThat(reordered).extracting(ModelChangeHistory::getTargetId).containsExactlyInAnyOrder(saved.get(0).getId(), saved.get(1).getId());
        assertThat(reordered).allSatisfy(h -> assertThat(changedFields(admin, h)).containsExactly("sortOrder"));

        // 삭제 + 추가를 한 번에 → DELETE 1 + CREATE 1, 같은 그룹. 순서가 밀린 컬럼이 없도록 마지막 컬럼을 지우고 끝에 붙인다
        relogin(user);
        mark = mark();
        ColumnBulkSaveDto.Row last = same.remove(19);
        same.add(row("new-x", null, terms.get(20).getId(), "새 컬럼", "VARCHAR", 5));
        ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(bulk(table, same, List.of(last.getId())), user).getBody();
        assertThat(result.created()).isEqualTo(1);
        List<ModelChangeHistory> replaced = since(projectId, mark);
        assertThat(replaced).extracting(ModelChangeHistory::getAction).containsExactlyInAnyOrder(Action.DELETE, Action.CREATE);
        assertThat(replaced).extracting(ModelChangeHistory::getChangeGroupId).containsOnly(replaced.getFirst().getChangeGroupId());

        // 검증에 걸린 일괄 저장(전체 거절) → 이력 없음
        mark = mark();
        same.get(2).setColumnDesc("저장되지 않는다");
        same.get(5).setDataType(" ");
        assertThat(columns.saveColumnsBulk(bulk(table, same, List.of()), user).getBody().result()).isFalse();
        assertThat(since(projectId, mark)).isEmpty();

        // 트랜잭션이 롤백되면 그 안의 변경은 이력에도 없다
        Long columnId = saved.get(10).getId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            columnInfoRepository.findById(columnId).orElseThrow().setColumnDesc("롤백된다");
            columnInfoRepository.flush();
            throw new IllegalStateException("실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(since(projectId, mark)).isEmpty();
        assertThat(columnInfoRepository.findById(columnId).orElseThrow().getColumnDesc()).isEqualTo("설명 10");

        // 승인·승인취소 (컬럼) : 실제로 바뀐 것만
        relogin(admin);
        ColumnInfoDto target = ColumnInfoDto.builder().id(columnId).build();
        columns.updateColumnApproval(admin, "true", List.of(target));
        columns.updateColumnApproval(admin, "true", List.of(target));
        columns.updateColumnApproval(admin, "false", List.of(target));
        assertThat(since(projectId, mark)).extracting(ModelChangeHistory::getAction).containsExactly(Action.APPROVE, Action.REVOKE);
    }

    @Test
    @DisplayName("이력 저장이 실패하면 모델 변경도 함께 롤백된다")
    void historyFailureRollsBackModelChange() {
        AccountDto admin = loginAsAdmin("m9-fail");
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "TB_SAFE", false);
        long count = historyRepository.count();

        // 이 이름의 이력만 저장되지 않게 막아 둔다
        jdbc.execute("alter table TB_MDL_CHG_HSTRY add constraint CK_MDL_CHG_HSTRY_TEST check (TRGT_NM <> 'TB_BROKEN')");
        try {
            assertThatThrownBy(() -> tables.updateTable(update(table, "TB_BROKEN", null), admin)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("alter table TB_MDL_CHG_HSTRY drop constraint CK_MDL_CHG_HSTRY_TEST");
        }
        assertThat(tableInfoRepository.findById(table.getId()).orElseThrow().getTableName()).isEqualTo("TB_SAFE");
        assertThat(historyRepository.count()).isEqualTo(count);

        // 막힌 것이 풀리면 같은 요청이 저장되고 이력도 남는다
        tables.updateTable(update(table, "TB_BROKEN", null), admin);
        assertThat(tableInfoRepository.findById(table.getId()).orElseThrow().getTableName()).isEqualTo("TB_BROKEN");
        assertThat(historyRepository.count()).isEqualTo(count + 1);
    }

    // ------------------------------------------------------------------ 관계·인덱스·제약조건

    @Test
    @DisplayName("관계(FK)·인덱스·제약조건 : 생성·승인·승인취소·삭제가 컬럼 구성과 함께 남고, 검증에 걸린 FK 는 이력이 없다")
    void relationsIndexesConstraints() {
        AccountDto admin = loginAsAdmin("m9-keys");
        long projectId = admin.getProjectId();
        TableInfo code = table(projectId, "TB_CODE", true);
        ColumnInfo grp = column(projectId, code, "GRP_CD", true, null);
        ColumnInfo cd = column(projectId, code, "CD", true, null);
        TableInfo order = table(projectId, "TB_ORD", true);
        ColumnInfo orderNo = column(projectId, order, "ORD_NO", true, null);
        ColumnInfo statGrp = column(projectId, order, "STAT_GRP_CD", false, null);
        ColumnInfo stat = column(projectId, order, "STAT_CD", false, null);

        // 복합 FK 생성 : 컬럼 쌍이 순서대로 남는다. 승인 권한자가 만들어 바로 승인 상태
        long mark = mark();
        Relation fk = physical.saveRelation(admin, relation("FK", order, code, pair(statGrp, grp), pair(stat, cd))).getBody();
        ModelChangeHistory created = onlySince(projectId, mark);
        assertThat(created).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getAction, ModelChangeHistory::getSource, ModelChangeHistory::getTargetName)
                .containsExactly(TargetType.PHYSICAL_RELATION, fk.id(), Action.CREATE, Source.USER, "FK_TB_ORD_STAT_GRP_CD");
        JsonNode after = json(created.getAfterData());
        assertThat(after.get("sourceTableId").asLong()).isEqualTo(order.getId());
        assertThat(after.get("targetTableId").asLong()).isEqualTo(code.getId());
        assertThat(after.get("relationType").asString()).isEqualTo("FK");
        assertThat(after.get("columns")).hasSize(2);
        assertThat(after.get("columns").get(0).get("sourceColumnId").asLong()).isEqualTo(statGrp.getId());
        assertThat(after.get("columns").get(1).get("targetColumnId").asLong()).isEqualTo(cd.getId());

        // 검증 실패(대상 키의 일부만 참조, 중복 등록) → 이력 없음
        mark = mark();
        assertThatThrownBy(() -> physical.saveRelation(admin, relation("FK", order, code, pair(stat, cd)))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> physical.saveRelation(admin, relation("FK", order, code, pair(statGrp, grp), pair(stat, cd)))).isInstanceOf(CustomException.class);
        // 승인된 관계는 삭제할 수 없다 → 이력 없음
        assertThatThrownBy(() -> physical.delete(admin, "relation", fk.id())).isInstanceOf(CustomException.class);
        assertThat(since(projectId, mark)).isEmpty();

        // 승인취소 → REVOKE, 삭제 → DELETE (삭제 전 컬럼 쌍이 남는다)
        physical.updateApproval(admin, "relation", fk.id(), false);
        physical.updateApproval(admin, "relation", fk.id(), false); // 그대로 → 이력 없음
        physical.delete(admin, "relation", fk.id());
        List<ModelChangeHistory> removed = since(projectId, mark);
        assertThat(removed).extracting(ModelChangeHistory::getAction).containsExactly(Action.REVOKE, Action.DELETE);
        assertThat(removed.get(1).getAfterData()).isNull();
        assertThat(json(removed.get(1).getBeforeData()).get("columns")).isEqualTo(after.get("columns"));
        assertThat(json(removed.get(1).getBeforeData()).get("isApproval").asBoolean()).isFalse();

        // 삭제 후 다시 등록 = 새 객체의 CREATE (수정 API 는 없다)
        mark = mark();
        Relation again = physical.saveRelation(admin, relation("LOGICAL", order, code, pair(stat, cd))).getBody();
        assertThat(onlySince(projectId, mark)).extracting(ModelChangeHistory::getTargetId, ModelChangeHistory::getAction).containsExactly(again.id(), Action.CREATE);

        // 인덱스 : 생성(컬럼 순서 포함)·승인취소·삭제
        mark = mark();
        PhysicalModelDto.Index index = physical.saveIndex(admin, new PhysicalModelDto.Index(null, order.getId(), null, null, false, List.of(ref(stat), ref(statGrp)), null)).getBody();
        ModelChangeHistory indexCreated = onlySince(projectId, mark);
        assertThat(indexCreated).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getAction)
                .containsExactly(TargetType.PHYSICAL_INDEX, index.id(), Action.CREATE);
        assertThat(json(indexCreated.getAfterData()).get("isUnique").asBoolean()).isFalse();
        assertThat(json(indexCreated.getAfterData()).get("columns").get(0).get("columnId").asLong()).isEqualTo(stat.getId());
        assertThat(json(indexCreated.getAfterData()).get("columns").get(1).get("columnOrder").asInt()).isEqualTo(2);
        mark = mark();
        physical.updateApproval(admin, "index", index.id(), false);
        physical.delete(admin, "index", index.id());
        assertThat(since(projectId, mark)).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getAction)
                .containsExactly(tuple(TargetType.PHYSICAL_INDEX, Action.REVOKE), tuple(TargetType.PHYSICAL_INDEX, Action.DELETE));

        // 제약조건 : UNIQUE(컬럼 구성)·CHECK(조건식) 생성, 승인취소·승인, 삭제
        mark = mark();
        PhysicalModelDto.Constraint unique = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), null, "UNIQUE", null, List.of(ref(statGrp), ref(stat)), null)).getBody();
        PhysicalModelDto.Constraint check = physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), "CK_ORD_STAT", "CHECK", "STAT_CD <> ''", null, null)).getBody();
        List<ModelChangeHistory> constraints = since(projectId, mark);
        assertThat(constraints).extracting(ModelChangeHistory::getTargetType, ModelChangeHistory::getTargetId, ModelChangeHistory::getAction)
                .containsExactly(tuple(TargetType.PHYSICAL_CONSTRAINT, unique.id(), Action.CREATE), tuple(TargetType.PHYSICAL_CONSTRAINT, check.id(), Action.CREATE));
        assertThat(json(constraints.get(0).getAfterData()).get("columns")).hasSize(2);
        assertThat(json(constraints.get(1).getAfterData()).get("checkExpression").asString()).isEqualTo("STAT_CD <> ''");
        assertThat(json(constraints.get(1).getAfterData()).get("columns")).isEmpty();
        mark = mark();
        physical.updateApproval(admin, "constraint", check.id(), false);
        physical.updateApproval(admin, "constraint", check.id(), true);
        physical.updateApproval(admin, "constraint", unique.id(), false);
        physical.delete(admin, "constraint", unique.id());
        assertThat(since(projectId, mark)).extracting(ModelChangeHistory::getAction).containsExactly(Action.REVOKE, Action.APPROVE, Action.REVOKE, Action.DELETE);

        // PK 변경은 컬럼의 속성 변경으로 한 번만 남는다 (제약조건 이력과 겹치지 않는다)
        mark = mark();
        tx.executeWithoutResult(s -> columnInfoRepository.findById(orderNo.getId()).orElseThrow().setIsPk(false));
        ModelChangeHistory pkChanged = onlySince(projectId, mark);
        assertThat(pkChanged.getTargetType()).isEqualTo(TargetType.PHYSICAL_COLUMN);
        assertThat(changedFields(admin, pkChanged)).containsExactly("isPk");
    }

    // ------------------------------------------------------------------ 조회·권한

    @Test
    @DisplayName("조회 : 조건(대상·작업·사용자·기간·그룹)과 페이징·정렬, 변경 그룹, 상세. VIEWER 부터 조회하고 다른 프로젝트의 이력은 ID 를 알아도 볼 수 없다")
    void queryAndIsolation() {
        AccountDto admin = loginAsAdmin("m9-query");
        long projectId = admin.getProjectId();
        TableInfo a = table(projectId, "TB_A", false);
        TableInfo b = table(projectId, "TB_B", false);
        tables.updateTable(update(a, "TB_A", "설명 A"), admin);
        tables.updateTableIsApproval(true, admin, List.of(dto(a), dto(b))); // 한 요청으로 두 테이블 승인
        AccountDto user = login(projectId, "930003", "ROLE_USER");
        rejected(() -> tables.updateTable(update(b, "TB_B", "사용자가 수정"), user), ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE); // 승인된 테이블 → 거절, 이력 없음
        relogin(admin);
        tables.updateTableIsApproval(false, admin, List.of(dto(b)));
        relogin(user);
        tables.updateTable(update(b, "TB_B", "사용자가 수정"), user);

        relogin(admin);
        List<ModelChangeHistoryDto.Summary> all = search(admin, null, null, null, null, null, null, null, 0, 100);
        assertThat(all).extracting(ModelChangeHistoryDto.Summary::action).containsExactly("UPDATE", "REVOKE", "APPROVE", "APPROVE", "UPDATE", "CREATE", "CREATE");
        assertThat(all).extracting(ModelChangeHistoryDto.Summary::id).isSortedAccordingTo(Comparator.reverseOrder()); // 같은 시각이어도 순서가 흔들리지 않는다
        assertThat(all).allMatch(s -> s.projectId() == projectId);

        // 조건
        assertThat(search(admin, "physical_table", a.getId(), null, null, null, null, null, 0, 100)).extracting(ModelChangeHistoryDto.Summary::action)
                .containsExactly("APPROVE", "UPDATE", "CREATE");
        assertThat(search(admin, null, null, "APPROVE", null, null, null, null, 0, 100)).hasSize(2);
        assertThat(search(admin, null, null, null, user.getUsername(), null, null, null, 0, 100)).extracting(ModelChangeHistoryDto.Summary::targetId).containsExactly(b.getId());
        assertThat(search(admin, "PHYSICAL_COLUMN", null, null, null, null, null, null, 0, 100)).isEmpty();
        LocalDate today = LocalDate.now();
        assertThat(search(admin, null, null, null, null, today.minusDays(1), today, null, 0, 100)).hasSize(7);
        assertThat(search(admin, null, null, null, null, today.plusDays(1), null, null, 0, 100)).isEmpty();
        assertThat(search(admin, null, null, null, null, null, today.minusDays(1), null, 0, 100)).isEmpty();
        rejected(() -> search(admin, "NO_SUCH_TYPE", null, null, null, null, null, null, 0, 100), ErrorCode.INVALID_PHYSICAL_MODEL);

        // 페이징 : 전체 건수는 그대로, 쪽마다 이어진다
        Map<String, Object> page = history.search(admin, 1, 3, null, null, null, null, null, null, null).getBody();
        assertThat(page).containsEntry("recordsTotal", 7L);
        assertThat(summaries(page)).extracting(ModelChangeHistoryDto.Summary::id).containsExactly(all.get(3).id(), all.get(4).id(), all.get(5).id());

        // 변경 그룹 : 한 요청으로 승인한 두 테이블이 한 그룹, 기록된 순서대로
        String groupId = all.get(2).changeGroupId();
        assertThat(all.get(3).changeGroupId()).isEqualTo(groupId);
        List<ModelChangeHistoryDto.Summary> group = history.group(admin, groupId).getBody();
        assertThat(group).extracting(ModelChangeHistoryDto.Summary::targetId, ModelChangeHistoryDto.Summary::action)
                .containsExactly(tuple(a.getId(), "APPROVE"), tuple(b.getId(), "APPROVE"));
        assertThat(search(admin, null, null, null, null, null, null, groupId, 0, 100)).hasSize(2);
        assertThat(all.stream().map(ModelChangeHistoryDto.Summary::changeGroupId).distinct()).hasSize(6); // 나머지 요청은 저마다 다른 그룹

        // 상세 : 변경 전후 JSON
        ModelChangeHistoryDto.Detail detail = history.detail(admin, all.get(4).id()).getBody();
        assertThat(detail.summary().changedFields()).containsExactly("tableDescription");
        assertThat(detail.before().get("tableDescription").isNull()).isTrue();
        assertThat(detail.after().get("tableDescription").asString()).isEqualTo("설명 A");
        assertThat(history.detail(admin, all.get(6).id()).getBody().before()).isNull();

        // 지금은 없는 필드가 들어 있는 예전 이력도 그대로 조회된다
        jdbc.update("update TB_MDL_CHG_HSTRY set CHG_AFTR_CN = ? where MDL_CHG_HSTRY_SN = ?", "{\"tableName\":\"TB_A\",\"removedField\":1}", all.get(4).id());
        assertThat(history.detail(admin, all.get(4).id()).getBody().after().get("removedField").asInt()).isEqualTo(1);
        assertThat(search(admin, "PHYSICAL_TABLE", a.getId(), "UPDATE", null, null, null, null, 0, 10).getFirst().changedFields()).contains("removedField", "tableDescription");

        // 조회 권한 : VIEWER 부터. 미승인 사용자는 거절
        AccountDto viewer = login(projectId, "930004", "ROLE_VIEWER");
        assertThat(search(viewer, null, null, null, null, null, null, null, 0, 100)).hasSize(7);
        assertThat(history.detail(viewer, all.get(0).id()).getBody().summary().id()).isEqualTo(all.get(0).id());
        AccountDto notApproved = login(projectId, "930005", "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> history.search(notApproved, 0, 10, null, null, null, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> history.detail(notApproved, all.get(0).id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> history.group(notApproved, groupId)).isInstanceOf(AccessDeniedException.class);

        // 다른 프로젝트 : 이력 ID·대상 ID·변경 그룹 ID 를 알아도 나오지 않는다 (삭제된 대상이어도 이력의 프로젝트로 판단한다)
        AccountDto outsider = loginAsAdmin("m9-query-other");
        assertThat(search(outsider, null, null, null, null, null, null, null, 0, 100)).isEmpty();
        assertThat(search(outsider, "PHYSICAL_TABLE", a.getId(), null, null, null, null, null, 0, 100)).isEmpty();
        assertThat(search(outsider, null, null, null, null, null, null, groupId, 0, 100)).isEmpty();
        assertThat(history.group(outsider, groupId).getBody()).isEmpty();
        rejected(() -> history.detail(outsider, all.get(0).id()), ErrorCode.INVALID_PHYSICAL_MODEL);
    }

    // ------------------------------------------------------------------ 보조

    private long mark() {
        return historyRepository.findAll().stream().mapToLong(ModelChangeHistory::getId).max().orElse(0L);
    }

    /** 표시(mark) 뒤에 이 프로젝트에 기록된 이력, 기록된 순서대로 */
    private List<ModelChangeHistory> since(long projectId, long mark) {
        return historyRepository.findAll().stream().filter(h -> h.getProjectId() == projectId && h.getId() > mark)
                .sorted(Comparator.comparing(ModelChangeHistory::getId)).toList();
    }

    private ModelChangeHistory onlySince(long projectId, long mark) {
        List<ModelChangeHistory> found = since(projectId, mark);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private ModelChangeHistory only(long projectId) {
        return onlySince(projectId, 0);
    }

    private static JsonNode json(String value) {
        return JSON.readTree(value);
    }

    private List<String> changedFields(AccountDto account, ModelChangeHistory row) {
        return history.detail(account, row.getId()).getBody().summary().changedFields();
    }

    private List<ModelChangeHistoryDto.Summary> search(AccountDto account, String targetType, Long targetId, String action, String userId,
                                                       LocalDate from, LocalDate to, String groupId, int page, int size) {
        return summaries(history.search(account, page, size, targetType, targetId, action, userId, from, to, groupId).getBody());
    }

    @SuppressWarnings("unchecked")
    private static List<ModelChangeHistoryDto.Summary> summaries(Map<String, Object> body) {
        return (List<ModelChangeHistoryDto.Summary>) body.get("data");
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static TableInfoDto update(TableInfo table, String tableName, String description) {
        return TableInfoDto.builder().id(table.getId()).tableName(tableName).tableDescription(description).isMasterTable("N").build();
    }

    private static TableInfoDto dto(TableInfo table) {
        return TableInfoDto.builder().id(table.getId()).build();
    }

    private static ColumnBulkSaveDto bulk(TableInfo table, List<ColumnBulkSaveDto.Row> rows, List<Long> deletedIds) {
        return ColumnBulkSaveDto.builder().tableInfoId(table.getId()).rows(new ArrayList<>(rows)).deletedIds(deletedIds).build();
    }

    private static ColumnBulkSaveDto.Row row(String key, Long id, Long termId, String description, String dataType, int maxLength) {
        return ColumnBulkSaveDto.Row.builder().key(key).id(id).termId(termId).columnDesc(description).dataType(dataType).maxLength(maxLength).isNullable("Y").build();
    }

    private static Relation relation(String type, TableInfo source, TableInfo target, ColumnPair... pairs) {
        return new Relation(null, null, type, null, source.getId(), null, target.getId(), null, List.of(pairs), null);
    }

    private static ColumnPair pair(ColumnInfo source, ColumnInfo target) {
        return new ColumnPair(source.getId(), null, target.getId(), null);
    }

    private static PhysicalModelDto.ColumnRef ref(ColumnInfo column) {
        return new PhysicalModelDto.ColumnRef(column.getId(), null);
    }

    private AccountDto login(long projectId, String accountId, String role) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, role);
    }

    /** 다른 사용자로 바꿨던 로그인 상태를 되돌린다 (권한은 SecurityContext 에서 읽는다) */
    private void relogin(AccountDto account) {
        boolean admin = accountRepository.findAll().stream().anyMatch(a -> String.valueOf(a.getId()).equals(account.getId()) && "admin".equals(a.getUsername()));
        SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), account.getId(), admin ? "ROLE_ADMIN" : "ROLE_USER");
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, boolean approved) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved).isMasterTable(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, String ref) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR").maxLength(10).isPk(pk).refTableName(ref).isApproval(false).build()));
    }
}
