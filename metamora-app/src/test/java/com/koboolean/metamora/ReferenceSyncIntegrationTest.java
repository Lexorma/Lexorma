package com.koboolean.metamora;

import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.column.service.ColumnManageService;
import com.koboolean.metamora.data.dictionary.service.DataDictionaryService;
import com.koboolean.metamora.data.erd.domain.dto.ErdDto;
import com.koboolean.metamora.data.erd.service.ErdService;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto;
import com.koboolean.metamora.data.physical.domain.entity.TableRelation;
import com.koboolean.metamora.data.physical.domain.entity.TableRelationColumn;
import com.koboolean.metamora.data.physical.repository.TableRelationRepository;
import com.koboolean.metamora.data.physical.service.PhysicalModelService;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.domain.dto.TableInfoDto;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import com.koboolean.metamora.data.table.service.TableDesignService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-1 후속 : 참조 관계(REF_COLUMN) 동기화 누락 방지.
 * 서비스가 syncReferenceRelations 를 부르지 않는 저장 경로(리포지토리로 직접 저장·수정 = 앞으로 추가될 경로)에서도
 * 커밋 직전 공통 경계가 약식 참조(참조 테이블명)와 관계를 맞추는지, 롤백·중복·다른 출처 보존·엑셀 청크 경계를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ReferenceSyncIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired TableRelationRepository relationRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired TableDesignService tableDesignService;
    @Autowired ColumnManageService columnManageService;
    @Autowired DataDictionaryService dataDictionaryService;
    @Autowired ErdService erdService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("동기화를 부르지 않는 저장 경로 : 참조 대상의 뒤늦은 생성, PK 변경, 참조 변경·삭제, 테이블명 변경 후에도 관계·DDL·ERD 가 약식 참조와 일치한다")
    void syncWithoutExplicitCall() {
        AccountDto admin = loginAsAdmin("sync-auto");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT");
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD");
        column(projectId, child, "CHILD_ID", true, null);

        // 정상 단일 PK 참조 (대소문자·공백 무시)
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, " tb_parent ");
        assertTarget(admin, ref, parentId);
        assertThat(derived(ref).getRelationName()).isEqualTo("FK_TB_CHILD_PARENT_ID");
        assertThat(derived(ref).getRelationSource()).isEqualTo(TableRelation.Source.REF_COLUMN);

        // 참조 대상 없음 → 관계 없이 입력값 보존, 대상 테이블·PK 가 뒤늦게 생기면 관계가 된다
        ColumnInfo late = column(projectId, child, "LATE_ID", false, "TB_LATE");
        assertUnresolved(admin, late, "TB_LATE");
        TableInfo lateTable = table(projectId, "TB_LATE");
        assertUnresolved(admin, late, "TB_LATE"); // PK 없음
        ColumnInfo lateId = column(projectId, lateTable, "LATE_ID", true, null);
        assertTarget(admin, late, lateId);

        // PK 변경 : 단일 → 복합(관계 정리, 입력값 보존) → 다른 단일 PK(대상 컬럼 교체) → PK 해제(정리) → 다시 PK
        ColumnInfo lateSeq = column(projectId, lateTable, "SEQ", true, null);
        assertUnresolved(admin, late, "TB_LATE");
        update(lateId, c -> c.setIsPk(false));
        assertTarget(admin, late, lateSeq);
        update(lateSeq, c -> c.setIsPk(false));
        assertUnresolved(admin, late, "TB_LATE");
        update(lateId, c -> c.setIsPk(true));
        assertTarget(admin, late, lateId);

        // 참조 컬럼 변경 : 다른 대상으로 → 삭제
        update(late, c -> c.setRefTableName("TB_PARENT"));
        assertTarget(admin, late, parentId);
        update(late, c -> c.setRefTableName(null));
        assertThat(derived(late)).isNull();
        assertThat(model(admin, child).unresolvedReferences()).isEmpty();

        // 자기 PK 참조는 관계가 아니다
        ColumnInfo self = update(parentId, c -> c.setRefTableName("TB_PARENT"));
        assertThat(derived(self)).isNull();
        update(parentId, c -> c.setRefTableName(null));

        // 참조 대상 테이블명만 바뀌면(약식 입력은 그대로) 다른 대상에 연결하지 않고 미해결로 둔다. 이름이 돌아오면 복구된다
        tx.executeWithoutResult(s -> tableInfoRepository.findById(parent.getId()).orElseThrow().setTableName("TB_PARENT_OLD"));
        assertUnresolved(admin, ref, " tb_parent ");
        tx.executeWithoutResult(s -> tableInfoRepository.findById(parent.getId()).orElseThrow().setTableName("TB_PARENT"));
        assertTarget(admin, ref, parentId);

        // 원본 컬럼명이 바뀌면 관계 이름도 따라간다 (DDL 의 FK 이름과 같은 규칙)
        update(ref, c -> c.setColumnName("PRNT_ID"));
        assertThat(derived(ref).getRelationName()).isEqualTo("FK_TB_CHILD_PRNT_ID");
        assertThat(ddl(admin, child)).contains("ADD CONSTRAINT FK_TB_CHILD_PRNT_ID FOREIGN KEY (PRNT_ID) REFERENCES TB_PARENT (PARENT_ID);");
    }

    @Test
    @DisplayName("서비스의 테이블명 변경 : 참조하던 약식 입력을 새 이름으로 바꾸고 관계·관계 이름·DDL 을 유지한다")
    void renameByService() {
        AccountDto admin = loginAsAdmin("sync-rename");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT");
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD");
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, "TB_PARENT");
        Long relationId = derived(ref).getId();

        tableDesignService.updateTable(TableInfoDto.builder().id(parent.getId()).tableName("TB_PARENT_V2").tableDescription("부모").isMasterTable("N").build(), admin);
        assertThat(columnInfoRepository.findById(ref.getId()).orElseThrow().getRefTableName()).isEqualTo("TB_PARENT_V2");
        assertTarget(admin, ref, parentId);
        assertThat(derived(ref).getId()).as("같은 관계를 유지한다").isEqualTo(relationId);
        assertThat(ddl(admin, child)).contains("REFERENCES TB_PARENT_V2 (PARENT_ID);");

        tableDesignService.updateTable(TableInfoDto.builder().id(child.getId()).tableName("TB_CHILD_V2").tableDescription("자식").isMasterTable("N").build(), admin);
        assertThat(derived(ref).getId()).isEqualTo(relationId);
        assertThat(derived(ref).getRelationName()).as("원본 테이블명을 따라간다").isEqualTo("FK_TB_CHILD_V2_PARENT_ID");
        assertThat(ddl(admin, child)).contains("ALTER TABLE TB_CHILD_V2 ADD CONSTRAINT FK_TB_CHILD_V2_PARENT_ID");
    }

    @Test
    @DisplayName("동기화를 반복해도 REF_COLUMN 관계는 하나이고, 사용자 정의·가져온·AI 승인 관계는 건드리지 않는다")
    void idempotentAndOtherSourcesKept() {
        AccountDto admin = loginAsAdmin("sync-sources");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT");
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD");
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, "TB_PARENT");
        ColumnInfo userColumn = column(projectId, child, "USER_REF", false, null);
        ColumnInfo importedColumn = column(projectId, child, "IMPORTED_REF", false, null);
        ColumnInfo aiColumn = column(projectId, child, "AI_REF", false, null);

        Long userRelation = physicalModelService.saveRelation(admin, new PhysicalModelDto.Relation(null, "FK_USER", "LOGICAL", null,
                child.getId(), null, parent.getId(), null, List.of(new PhysicalModelDto.ColumnPair(userColumn.getId(), null, parentId.getId(), null)), null)).id();
        Long imported = relation(projectId, child, parent, importedColumn, parentId, TableRelation.Source.IMPORTED);
        Long aiApproved = relation(projectId, child, parent, aiColumn, parentId, TableRelation.Source.AI_APPROVED);

        for (int i = 0; i < 3; i++) {
            tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(projectId, List.of(child.getId(), parent.getId())));
            update(ref, c -> c.setColumnDesc("설명 변경")); // 관계와 무관한 수정도 동기화 대상이 된다
        }
        // 대상 PK 가 복합이 되어 REF_COLUMN 이 정리될 때도 다른 출처는 그대로다
        ColumnInfo seq = column(projectId, parent, "SEQ", true, null);
        assertThat(derived(ref)).isNull();
        update(seq, c -> c.setIsPk(false));

        List<TableRelation> relations = tx.execute(s -> relationRepository.findAllByProjectIdAndSourceTable_Id(projectId, child.getId()).stream()
                .peek(r -> r.getColumns().size()).toList());
        assertThat(relations).extracting(TableRelation::getRelationSource).containsExactlyInAnyOrder(
                TableRelation.Source.REF_COLUMN, TableRelation.Source.USER, TableRelation.Source.IMPORTED, TableRelation.Source.AI_APPROVED);
        assertThat(relations).extracting(TableRelation::getId).contains(userRelation, imported, aiApproved);
        assertThat(relations).filteredOn(r -> r.getRelationSource() == TableRelation.Source.USER)
                .extracting(TableRelation::getRelationName, TableRelation::getRelationType).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FK_USER", TableRelation.Type.LOGICAL));
    }

    @Test
    @DisplayName("저장이나 동기화가 실패하면 컬럼·관계가 함께 롤백되고, 다시 시도하면 정상 저장된다")
    void rollback() {
        AccountDto admin = loginAsAdmin("sync-rollback");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT");
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD");
        long relationsBefore = relationRepository.count();

        // 저장 뒤 같은 트랜잭션에서 예외
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            columnInfoRepository.saveAndFlush(newColumn(projectId, child, "PARENT_ID", false, "TB_PARENT"));
            throw new IllegalStateException("저장 중 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(columnInfoRepository.findAllByTableInfo_IdIn(List.of(child.getId()))).isEmpty();
        assertThat(relationRepository.count()).isEqualTo(relationsBefore);

        // 커밋 직전 동기화가 실패 (관계 이름이 컬럼 길이 255 를 넘는다) → 컬럼 저장도 롤백
        String tooLong = "C".repeat(250);
        assertThatThrownBy(() -> column(projectId, child, tooLong, false, "TB_PARENT")).isInstanceOf(RuntimeException.class);
        assertThat(columnInfoRepository.findAllByTableInfo_IdIn(List.of(child.getId()))).as("부분 저장 없음").isEmpty();
        assertThat(relationRepository.count()).isEqualTo(relationsBefore);

        // 재시도
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, "TB_PARENT");
        assertTarget(admin, ref, parentId);
    }

    @Test
    @DisplayName("같은 컬럼의 동기화가 동시에 와도 REF_COLUMN 관계는 하나만 만들어진다")
    void concurrentSync() throws Exception {
        AccountDto admin = loginAsAdmin("sync-concurrent");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_PARENT");
        ColumnInfo parentId = column(projectId, parent, "PARENT_ID", true, null);
        TableInfo child = table(projectId, "TB_CHILD");
        ColumnInfo ref = column(projectId, child, "PARENT_ID", false, "TB_PARENT");
        relationRepository.deleteById(derived(ref).getId()); // 관계가 빠진 상태를 만든다

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    tx.executeWithoutResult(s -> physicalModelService.syncReferenceRelations(projectId, List.of(child.getId())));
                    return null;
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException e) {
                    // 늦은 쪽은 유니크 인덱스(UX_TBL_REL_01)에 걸려 롤백된다
                    StringBuilder messages = new StringBuilder();
                    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) messages.append(cause.getMessage()).append('\n');
                    assertThat(messages.toString().toLowerCase()).contains("ux_tbl_rel_01");
                }
            }
            assertThat(succeeded).isPositive();
        } finally {
            pool.shutdownNow();
        }
        assertThat(relationRepository.findAllByDerivedColumnIdIn(List.of(ref.getId()))).hasSize(1);
        assertTarget(admin, ref, parentId);
    }

    @Test
    @DisplayName("엑셀 컬럼 업로드 : 참조 대상 PK 가 다음 청크(500행)에 있어도 관계가 만들어지고, 다음 청크에서 복합 PK 가 되면 앞 청크의 관계가 정리된다")
    void excelChunkBoundary() throws IOException {
        AccountDto admin = loginAsAdmin("sync-excel");
        long projectId = admin.getProjectId();
        int children = 600;

        // 시트 순서는 실제 템플릿과 같다 : 0 용어, 1 단어, 2 도메인
        dataDictionaryService.uploadDictionaryExcelFile(file(workbook(
                rows(2, i -> new String[]{"", "1", "동기단어" + i + "_동기단어0", "용어 설명", "SYNCW" + i + "_SYNCW0", "동기도메인", "", "", "", "", "", ""}),
                rows(2, i -> new String[]{"", "1", "동기단어" + i, "SYNCW" + i, "Sync word " + i, "단어 설명", "N", i == 0 ? "동기분류" : "-", "", ""}),
                rows(1, i -> new String[]{"", "1", "문자", "동기분류", "동기도메인", "도메인 설명", "VARCHAR", "100", "0", "", "", "", ""}))), admin);

        List<String[]> tables = new ArrayList<>();
        tables.add(new String[]{"SYNC_P", "단일 PK 부모", "N", "0"});
        tables.add(new String[]{"SYNC_Q", "복합 PK 부모", "N", "0"});
        for (int i = 0; i < children; i++) tables.add(new String[]{"SYNC_C" + i, "자식 " + i, "N", String.valueOf(i)});
        tableDesignService.uploadTableExcelFile(file(workbook(tables)), admin);

        // 1번째 청크 : Q 의 PK 1개 + 자식 499개, 2번째 청크 : 자식 101개 + Q 의 두 번째 PK + P 의 PK
        List<String[]> columns = new ArrayList<>();
        columns.add(columnRow("SYNC_Q", "SYNCW0_SYNCW0", "", true));
        for (int i = 0; i < children; i++) columns.add(columnRow("SYNC_C" + i, "SYNCW0_SYNCW0", i % 2 == 0 ? "SYNC_P" : "sync_q", false));
        columns.add(columnRow("SYNC_Q", "SYNCW1_SYNCW0", "", true));
        columns.add(columnRow("SYNC_P", "SYNCW0_SYNCW0", "", true));
        columnManageService.uploadColumnExcelFile(file(workbook(columns)), admin);

        TableInfo p = tableInfoRepository.findByTableNameAndProjectId("SYNC_P", projectId);
        TableInfo q = tableInfoRepository.findByTableNameAndProjectId("SYNC_Q", projectId);
        assertThat(relationRepository.findAllByProjectIdAndTargetTable_Id(projectId, p.getId())).as("짝수 자식 → P (PK 는 마지막 행)").hasSize(children / 2)
                .allSatisfy(r -> assertThat(r.getRelationSource()).isEqualTo(TableRelation.Source.REF_COLUMN));
        assertThat(relationRepository.findAllByProjectIdAndTargetTable_Id(projectId, q.getId())).as("홀수 자식 → Q (복합 PK 라 관계 없음)").isEmpty();
        assertThat(columnInfoRepository.findAllReferencing(projectId, List.of("SYNC_Q"))).as("약식 입력은 보존").hasSize(children / 2);

        TableInfo evenChild = tableInfoRepository.findByTableNameAndProjectId("SYNC_C0", projectId);
        TableInfo oddChild = tableInfoRepository.findByTableNameAndProjectId("SYNC_C1", projectId);
        assertThat(ddl(admin, evenChild)).contains("REFERENCES SYNC_P (SYNCW0_SYNCW0);");
        assertThat(ddl(admin, oddChild)).doesNotContain("REFERENCES");
        assertThat(model(admin, oddChild).unresolvedReferences()).hasSize(1);
    }

    // ------------------------------------------------------------------ 확인

    /** 관계·관계 조회·DDL·ERD 가 모두 같은 대상을 가리킨다 */
    private void assertTarget(AccountDto admin, ColumnInfo column, ColumnInfo target) {
        TableRelation relation = derived(column);
        assertThat(relation).as(column.getColumnName() + " 의 관계").isNotNull();
        ColumnInfo current = columnInfoRepository.findById(column.getId()).orElseThrow();
        TableInfo source = tableInfoRepository.findById(relation.getSourceTable().getId()).orElseThrow();
        TableInfo targetTable = tableInfoRepository.findById(relation.getTargetTable().getId()).orElseThrow();
        assertThat(pair(relation)).isEqualTo(column.getId() + "->" + target.getId());
        assertThat(relationRepository.findAllByDerivedColumnIdIn(List.of(column.getId()))).hasSize(1);

        assertThat(ddl(admin, source)).as("DDL").contains("FOREIGN KEY (" + current.getColumnName() + ") REFERENCES " + targetTable.getTableName() + " (" + target.getColumnName() + ");");
        assertThat(model(admin, source).outgoingRelations()).filteredOn(r -> r.id().equals(relation.getId())).hasSize(1);
        assertThat(model(admin, source).unresolvedReferences()).noneMatch(u -> u.startsWith(current.getColumnName() + " "));
        assertThat(erdService.selectGraph(admin, new ErdDto.Search(null, source.getTableName(), null, null), 0, 100).relations())
                .as("ERD").filteredOn(r -> r.id().equals(relation.getId())).hasSize(1)
                .allSatisfy(r -> assertThat(r.relationSource()).isEqualTo("REF_COLUMN"));
    }

    /** 관계 없이 약식 입력만 남아 있고, DDL·ERD 에도 FK 가 없다 */
    private void assertUnresolved(AccountDto admin, ColumnInfo column, String refTableName) {
        assertThat(derived(column)).as(column.getColumnName() + " 의 관계").isNull();
        ColumnInfo current = columnInfoRepository.findById(column.getId()).orElseThrow();
        assertThat(current.getRefTableName()).as("약식 입력 보존").isEqualTo(refTableName);
        TableInfo source = tableInfoRepository.findById(current.getTableInfo().getId()).orElseThrow();
        assertThat(ddl(admin, source)).as("DDL").doesNotContain("FOREIGN KEY (" + current.getColumnName() + ")");
        assertThat(model(admin, source).unresolvedReferences()).anyMatch(u -> u.startsWith(current.getColumnName()));
    }

    private String ddl(AccountDto admin, TableInfo table) {
        return tableDesignService.generateTableDdl(table.getId(), admin, null).ddl();
    }

    private PhysicalModelDto.TableModel model(AccountDto admin, TableInfo table) {
        return physicalModelService.selectTableModel(admin, table.getId());
    }

    // ------------------------------------------------------------------ 보조 (동기화를 부르지 않는다)

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo newColumn(long projectId, TableInfo table, String name, boolean pk, String ref) {
        return ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).refTableName(ref).isApproval(false).build();
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, String ref) {
        return tx.execute(s -> columnInfoRepository.save(newColumn(projectId, table, name, pk, ref)));
    }

    private ColumnInfo update(ColumnInfo column, Consumer<ColumnInfo> change) {
        return tx.execute(s -> {
            ColumnInfo managed = columnInfoRepository.findById(column.getId()).orElseThrow();
            change.accept(managed);
            return managed;
        });
    }

    private Long relation(long projectId, TableInfo source, TableInfo target, ColumnInfo sourceColumn, ColumnInfo targetColumn, TableRelation.Source relationSource) {
        return tx.execute(s -> {
            TableRelation relation = TableRelation.builder().projectId(projectId)
                    .sourceTable(tableInfoRepository.getReferenceById(source.getId())).targetTable(tableInfoRepository.getReferenceById(target.getId()))
                    .relationName("FK_" + relationSource).relationType(TableRelation.Type.FK).relationSource(relationSource).isApproval(true).build();
            relation.getColumns().add(TableRelationColumn.builder().relation(relation)
                    .sourceColumn(columnInfoRepository.getReferenceById(sourceColumn.getId()))
                    .targetColumn(columnInfoRepository.getReferenceById(targetColumn.getId())).columnOrder(1).build());
            return relationRepository.save(relation).getId();
        });
    }

    private TableRelation derived(ColumnInfo column) {
        return relationRepository.findAllByDerivedColumnIdIn(List.of(column.getId())).stream().findFirst().orElse(null);
    }

    private String pair(TableRelation relation) {
        return tx.execute(s -> {
            var c = relationRepository.findById(relation.getId()).orElseThrow().getColumns().get(0);
            return c.getSourceColumn().getId() + "->" + c.getTargetColumn().getId();
        });
    }

    // ------------------------------------------------------------------ 엑셀

    private static String[] columnRow(String table, String column, String refTable, boolean pk) {
        return new String[]{table, column, "컬럼 설명", "VARCHAR", "100", "0", "0", pk ? "N" : "Y", "", "1", "N", refTable,
                "N", "N", pk ? "Y" : "N", "N", "N", "N", ""};
    }

    private static List<String[]> rows(int count, java.util.function.IntFunction<String[]> row) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(row.apply(i));
        return rows;
    }

    /** 헤더 1행 + 데이터 행으로 된 xlsx (시트마다 행 목록 하나) */
    @SafeVarargs
    private static byte[] workbook(List<String[]>... sheets) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (List<String[]> rows : sheets) {
                Sheet sheet = workbook.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("header");
                for (int i = 0; i < rows.size(); i++) {
                    Row row = sheet.createRow(i + 1);
                    String[] values = rows.get(i);
                    for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } finally {
            workbook.close();
        }
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "upload.xlsx", null, bytes);
    }
}
