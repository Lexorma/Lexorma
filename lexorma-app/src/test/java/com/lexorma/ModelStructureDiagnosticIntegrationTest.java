package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticDto;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticDto.IssueType;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 물리 모델 구조 진단 (DEC-09 무효 FK · DEC-12 중복 인덱스) : DDL 이 FK 를 만들지 않는 조건과 같은 판정, 수정은 막지 않는다, 예제 프로젝트는 오류 없음 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelStructureDiagnosticIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired ModelStructureDiagnosticService diagnostics;
    @Autowired TransactionTemplate tx;

    private final java.util.concurrent.atomic.AtomicInteger order = new java.util.concurrent.atomic.AtomicInteger();

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("무효 FK : 참조 테이블 없음·PK 없음·복합 PK, 등록 뒤 대상 키가 바뀐 관계는 오류 — 정상 참조는 나오지 않고, 무효가 된 뒤에도 수정은 된다")
    void invalidForeignKeys() {
        AccountDto admin = login("diag-fk");
        long projectId = admin.getProjectId();
        TableInfo parent = table(projectId, "TB_P");
        column(projectId, parent, "P_ID", true, null);
        TableInfo composite = table(projectId, "TB_CP");
        ColumnInfo a = column(projectId, composite, "A", true, null);
        ColumnInfo b = column(projectId, composite, "B", true, null);
        TableInfo noPk = table(projectId, "TB_NOPK");
        column(projectId, noPk, "V", false, null);
        TableInfo child = table(projectId, "TB_C");
        column(projectId, child, "P_ID", false, "TB_P");
        column(projectId, child, "GONE_ID", false, "TB_NONE");
        column(projectId, child, "CP_ID", false, "TB_CP");
        column(projectId, child, "V", false, "tb_nopk");
        ColumnInfo x = column(projectId, child, "X", false, null);
        ColumnInfo y = column(projectId, child, "Y", false, null);
        physicalModelService.saveRelation(admin, new PhysicalModelDto.Relation(null, "FK_C_CP", null, null, child.getId(), null, composite.getId(), null,
                List.of(new PhysicalModelDto.ColumnPair(x.getId(), null, a.getId(), null), new PhysicalModelDto.ColumnPair(y.getId(), null, b.getId(), null)), null));
        assertThat(diagnostics.diagnose(admin, null).issues()).noneMatch(i -> i.type() == IssueType.FK_RELATION_NOT_KEY);

        // 등록 뒤 대상 PK 가 바뀌어 관계가 키를 가리키지 않게 된다 (자동으로 지우지 않는다)
        tx.executeWithoutResult(s -> columnInfoRepository.findById(b.getId()).orElseThrow().setIsPk(false));
        ModelStructureDiagnosticDto.Result result = diagnostics.diagnose(admin, null);
        assertThat(result.issues()).filteredOn(i -> i.severity() == ModelStructureDiagnosticDto.Severity.ERROR)
                .extracting(ModelStructureDiagnosticDto.Issue::type, ModelStructureDiagnosticDto.Issue::objectName)
                .containsExactlyInAnyOrder(
                        tuple(IssueType.FK_TARGET_NOT_FOUND, "GONE_ID"),
                        tuple(IssueType.FK_TARGET_NO_PK, "V"),
                        tuple(IssueType.FK_RELATION_NOT_KEY, "FK_C_CP"));
        assertThat(result.summary().errors()).isEqualTo(3);
        assertThat(result.issues()).as("정상 참조(P_ID)·이제 단일 PK 가 된 TB_CP 참조는 오류가 아니다").noneMatch(i -> "P_ID".equals(i.objectName()) || "CP_ID".equals(i.objectName()));

        // 복합 PK 로 되돌리면 CP_ID 참조가 오류
        tx.executeWithoutResult(s -> columnInfoRepository.findById(b.getId()).orElseThrow().setIsPk(true));
        assertThat(diagnostics.diagnose(admin, null).issues()).anyMatch(i -> i.type() == IssueType.FK_TARGET_COMPOSITE_PK && "CP_ID".equals(i.objectName()));
        // 무효 FK 가 있어도 모델 수정은 막지 않는다
        tx.executeWithoutResult(s -> columnInfoRepository.findById(x.getId()).orElseThrow().setColumnDesc("수정 가능"));
        assertThat(columnInfoRepository.findById(x.getId()).orElseThrow().getColumnDesc()).isEqualTo("수정 가능");
    }

    @Test
    @DisplayName("중복 인덱스 (경고) : PK 와 같은 일반 복합 인덱스(허용), 다른 인덱스의 앞부분, 조건이 있는 인덱스는 앞부분 비교에서 뺀다")
    void duplicateIndexes() {
        AccountDto admin = login("diag-index");
        long projectId = admin.getProjectId();
        TableInfo table = table(projectId, "TB_IX");
        ColumnInfo k1 = column(projectId, table, "K1", true, null);
        ColumnInfo k2 = column(projectId, table, "K2", true, null);
        ColumnInfo c1 = column(projectId, table, "C1", false, null);
        ColumnInfo c2 = column(projectId, table, "C2", false, null);
        index(admin, table, "IX_SAME_PK", null, k1, k2);
        index(admin, table, "IX_C1", null, c1);
        index(admin, table, "IX_C1_C2", null, c1, c2);
        index(admin, table, "IX_C1_PARTIAL", "C2 IS NOT NULL", c1);

        assertThat(diagnostics.diagnose(admin, null).issues())
                .extracting(ModelStructureDiagnosticDto.Issue::type, ModelStructureDiagnosticDto.Issue::objectName, ModelStructureDiagnosticDto.Issue::related)
                .containsExactlyInAnyOrder(
                        tuple(IssueType.INDEX_SAME_AS_PK, "IX_SAME_PK", "PK"),
                        tuple(IssueType.INDEX_PREFIX_OF_OTHER, "IX_C1", "IX_C1_C2"));
    }

    @Test
    @DisplayName("예제 프로젝트(LEXORMA) : 무효 FK 가 없다")
    void exampleProjectHasNoErrors() {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        AccountDto user = SecurityTestSupport.loginAs(projectRepository, 0L, String.valueOf(admin.getId()), "ROLE_ADMIN");
        ModelStructureDiagnosticDto.Result result = diagnostics.diagnose(user, null);
        assertThat(result.summary().tables()).isPositive();
        assertThat(result.issues()).filteredOn(i -> i.severity() == ModelStructureDiagnosticDto.Severity.ERROR).isEmpty();
    }

    private void index(AccountDto admin, TableInfo table, String name, String condition, ColumnInfo... columns) {
        physicalModelService.saveIndex(admin, new PhysicalModelDto.Index(null, table.getId(), name, "btree", false,
                java.util.Arrays.stream(columns).map(c -> new PhysicalModelDto.ColumnRef(c.getId(), null)).toList(), null, null, null, null, condition));
    }

    private AccountDto login(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, String ref) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).refTableName(ref).isApproval(false).sortOrder(order.incrementAndGet()).build()));
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
