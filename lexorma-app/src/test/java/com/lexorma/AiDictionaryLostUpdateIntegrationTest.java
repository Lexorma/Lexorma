package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
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
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-5 업무 설명(TASK_EXPLN) 유실 방지 : 테이블·컬럼은 바뀐 컬럼만 UPDATE 한다(@DynamicUpdate). 그래서 행을 잠그지 않고 읽은 다른 모델 쓰기가 AI 제안 승인보다 늦게
 * 커밋해도 업무 설명을 읽은 때의 값(또는 null)으로 되돌리지 않는다. 같은 업무 설명을 두 쪽이 고치는 충돌은 따로 막는다 (BIZ_DESCRIPTION_CONFLICT, 컬럼 일괄 편집은 행 잠금 후 비교).
 * 두 트랜잭션을 실제로 엇갈려 실행한다 (H2 · PostgreSQL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiDictionaryLostUpdateIntegrationTest {

    private static final String MODEL = "gemma3:4b";

    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
    @Autowired AiDescriptionProposalRepository proposals;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired ColumnManageService columnManage;
    @Autowired TableDesignService tableDesign;
    @Autowired TransactionTemplate tx;

    private ScriptedOllama ollama;
    private Long providerId;
    private Long ruleId;
    private Account owner;
    private Project project;
    private TableInfo table;
    private ColumnInfo amount;
    private ColumnInfo memo;
    private int drafted;

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = tx.execute(s -> accountRepository.save(Account.builder().username("ai5l-" + UUID.randomUUID().toString().substring(0, 8)).name("o").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_USER")))).projectMember(new ArrayList<>()).build()));
        project = projectRepository.save(Project.builder().projectName("ai5l-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        tx.executeWithoutResult(s -> {
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            // 컬럼 일괄 편집은 승인된 테이블에서만 된다
            table = tableRepository.save(TableInfo.builder().projectId(project.getId()).schema(schema).tableName("TB_PAY").tableKorName("결제").tableDescription("결제 테이블")
                    .isApproval(true).isMasterTable(false).sortOrder(1).build());
            amount = columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_PAY").columnName("PAY_AMT").columnDesc("결제 금액")
                    .dataType("INTEGER").sortOrder(1).isApproval(false).isPk(false).isNullable(true).build());
            memo = columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_PAY").columnName("PAY_MEMO").columnDesc("결제 메모")
                    .dataType("VARCHAR").maxLength(100).sortOrder(2).isApproval(false).isPk(false).isNullable(true).build());
        });
        ollama = new ScriptedOllama();
        ollama.show(List.of("completion", "tools"), 32768);
        providerId = providers.create(new ProviderRequest("AI-5L " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), MODEL, true, null)).id();
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5 업무 설명 유실 방지")).id();
        defaults.set(new AiProviderDto.DefaultProviderRequest(providerId));
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityTestSupport.clear();
        defaults.clear();
        providers.delete(providerId);
        rules.delete(ruleId);
        ollama.close();
    }

    // ------------------------------------------------------------------ 실제 UPDATE SQL

    @Test
    @DisplayName("UPDATE SQL : 업무 설명을 바꾸지 않은 테이블·컬럼 저장에는 TASK_EXPLN 이 없고, AI 제안 승인은 TASK_EXPLN(과 감사 칼럼)만 쓴다")
    void updateStatements() {
        SqlCapture.start();
        edit(amount.getId(), c -> c.setColumnDesc("결제 금액(원)"));
        List<String> columnUpdate = SqlCapture.stop("TB_COL");
        SqlCapture.start();
        tx.executeWithoutResult(s -> tableRepository.findById(table.getId()).orElseThrow().setTableDescription("결제 내역 테이블"));
        List<String> tableUpdate = SqlCapture.stop("TB_TBL");
        AiDescriptionProposal p = propose(amount);
        SqlCapture.start();
        assertThat(approve(p).outcome()).isEqualTo("APPROVED");
        List<String> approval = SqlCapture.stop("TB_COL");
        System.out.println("UPDATE-SQL column=" + columnUpdate + "\nUPDATE-SQL table=" + tableUpdate + "\nUPDATE-SQL approval=" + approval);

        assertThat(columnUpdate).singleElement().satisfies(sql -> assertThat(sql.toUpperCase()).contains("COL_EXPLN").doesNotContain("TASK_EXPLN", "APRV_YN", "DATA_TYPE"));
        assertThat(tableUpdate).singleElement().satisfies(sql -> assertThat(sql.toUpperCase()).contains("TBL_EXPLN").doesNotContain("TASK_EXPLN", "APRV_YN", "TBL_KORN_NM"));
        assertThat(approval).singleElement().satisfies(sql -> assertThat(sql.toUpperCase()).contains("TASK_EXPLN").doesNotContain("COL_EXPLN", "APRV_YN", "SRC_ANTN_CN"));
    }

    // ------------------------------------------------------------------ 서로 다른 필드 : AI 승인 ↔ 다른 모델 쓰기

    @Test
    @DisplayName("컬럼 : 일괄 편집(잠그지 않고 읽음)이 AI 승인보다 늦게 커밋해도 승인된 업무 설명이 남고 편집 내용도 남는다. 순서가 반대여도 같다")
    void columnBulkSaveAfterApproval() throws Exception {
        AiDescriptionProposal p = propose(memo);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch approved = new CountDownLatch(1);
        CompletableFuture<ColumnBulkSaveDto.Result> writer = CompletableFuture.supplyAsync(() -> {
            AccountDto user = login();
            return tx.execute(s -> {
                // 일괄 편집이 컬럼을 읽은 뒤(잠그지 않음) 승인이 커밋되고, 그 뒤에 편집이 쓰고 커밋한다 : 서비스는 이 트랜잭션에 참여해 먼저 읽어 둔 컬럼(승인 전 값)을 그대로 쓴다
                tableRepository.findById(table.getId()).orElseThrow().getColumns().forEach(ColumnInfo::getBizDescription);
                read.countDown();
                await(approved);
                return columnManage.saveColumnsBulk(user, bulk(memo, "결제 메모(고객 입력)", null, null));
            });
        });
        assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(approve(p).outcome()).isEqualTo("APPROVED");
        String confirmed = column(memo).getBizDescription();
        approved.countDown();
        assertThat(writer.get(10, TimeUnit.SECONDS).errors()).isNullOrEmpty();

        ColumnInfo after = column(memo);
        assertThat(after.getBizDescription()).as("승인된 업무 설명").isEqualTo(confirmed).isNotNull();
        assertThat(after.getColumnDesc()).as("편집 내용").isEqualTo("결제 메모(고객 입력)");
        assertThat(proposals.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(AiDescriptionProposal.ReviewStatus.APPROVED);

        // 반대 순서 : 편집이 먼저 커밋 → 그 뒤 승인 (편집이 업무 설명을 바꾸지 않았으므로 근거 변경만 본다)
        AiDescriptionProposal next = propose(amount);
        login();
        assertThat(columnManage.saveColumnsBulk(login(), bulk(column(amount), "결제 금액(원)", null, null)).errors()).isNullOrEmpty();
        AiDictionaryDto.ReviewResult result = approve(next);
        assertThat(result.outcome()).as("근거 컬럼의 설명이 바뀌었으므로 재검토").isEqualTo("REVIEW_REQUIRED");
        assertThat(column(amount).getColumnDesc()).isEqualTo("결제 금액(원)");
    }

    @Test
    @DisplayName("테이블 : 잠그지 않고 읽은 테이블 쓰기가 AI 승인보다 늦게 커밋해도 승인된 업무 설명이 남는다")
    void tableWriteAfterApproval() throws Exception {
        AiDescriptionProposal p = proposeTable();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch approved = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            TableInfo stale = tableRepository.findById(table.getId()).orElseThrow();
            stale.setTableDescription("결제 내역 테이블");
            stale.setSortOrder(7);
            read.countDown();
            await(approved);
        }));
        assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(approve(p).outcome()).isEqualTo("APPROVED");
        String confirmed = tableRepository.findById(table.getId()).orElseThrow().getBizDescription();
        approved.countDown();
        writer.get(10, TimeUnit.SECONDS);

        TableInfo after = tableRepository.findById(table.getId()).orElseThrow();
        assertThat(after.getBizDescription()).isEqualTo(confirmed).isNotNull();
        assertThat(after.getTableDescription()).isEqualTo("결제 내역 테이블");
        assertThat(after.getSortOrder()).isEqualTo(7);
        assertThat(after.getIsApproval()).as("객체 승인 여부 그대로").isTrue();
        assertThat(proposals.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(AiDescriptionProposal.ReviewStatus.APPROVED);
    }

    // ------------------------------------------------------------------ 같은 필드 : 업무 설명을 두 쪽이 고친다

    @Test
    @DisplayName("같은 업무 설명 : 승인 뒤 화면을 연 때의 값으로 고치면 409, 컬럼 일괄 편집 두 개가 동시에 같은 업무 설명을 고치면 뒤의 것은 행 잠금 뒤 비교에서 막힌다")
    void sameFieldConflicts() throws Exception {
        // 테이블 : 두 사용자가 같은 값(없음)을 보고 연다 → 앞 사람이 저장 → 뒷사람은 409
        tx.executeWithoutResult(s -> tableRepository.findById(table.getId()).orElseThrow().setIsApproval(false));
        AccountDto user = login();
        tableDesign.updateTable(tableEdit("첫 번째 사용자의 설명", ""), user);
        assertThatThrownBy(() -> tableDesign.updateTable(tableEdit("두 번째 사용자의 설명", ""), user))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        assertThat(tableRepository.findById(table.getId()).orElseThrow().getBizDescription()).isEqualTo("첫 번째 사용자의 설명");
        tx.executeWithoutResult(s -> tableRepository.findById(table.getId()).orElseThrow().setIsApproval(true));

        // AI 승인 뒤, 승인 전 값을 기준으로 한 직접 수정 → 409
        AiDescriptionProposal p = propose(memo);
        assertThat(approve(p).outcome()).isEqualTo("APPROVED");
        String confirmed = column(memo).getBizDescription();
        ColumnBulkSaveDto.Result stale = columnManage.saveColumnsBulk(login(), bulk(column(memo), null, "사람이 쓴 메모", ""));
        assertThat(stale.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("업무 설명"));
        assertThat(column(memo).getBizDescription()).isEqualTo(confirmed);

        // 동시에 두 일괄 편집 : A 가 행을 잠그고 바꾼 뒤 커밋 전 대기, B 는 같은 기준 값으로 시도 → A 커밋 뒤 B 는 바뀐 값을 보고 막힌다
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<ColumnBulkSaveDto.Result> a = CompletableFuture.supplyAsync(() -> {
            AccountDto u = login();
            return tx.execute(s -> {
                ColumnBulkSaveDto.Result r = columnManage.saveColumnsBulk(u, bulk(column(memo), null, "A 의 메모", confirmed));
                aLocked.countDown();
                await(release);
                return r;
            });
        });
        assertThat(aLocked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ColumnBulkSaveDto.Result> b = CompletableFuture.supplyAsync(() -> columnManage.saveColumnsBulk(login(), bulk(column(memo), null, "B 의 메모", confirmed)));
        Thread.sleep(500);
        assertThat(b).as("B 는 A 의 행 잠금을 기다린다").isNotDone();
        release.countDown();
        assertThat(a.get(10, TimeUnit.SECONDS).errors()).isNullOrEmpty();
        assertThat(b.get(10, TimeUnit.SECONDS).errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("업무 설명"));
        assertThat(column(memo).getBizDescription()).isEqualTo("A 의 메모");
    }

    // ------------------------------------------------------------------ 준비

    private AccountDto login() {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_USER");
    }

    private ExecutionContext user() {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(owner.getId())).projectId(project.getId()).build());
    }

    private AiDescriptionProposal propose(ColumnInfo c) {
        drafted++;
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), c.getColumnName() + " 업무 설명 " + drafted, "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", c.getId(), "name", c.getColumnName())), null, null, List.of()));
        return drafts.generate(user(), table.getId(), c.getId(), true, null).proposal();
    }

    private AiDescriptionProposal proposeTable() {
        drafted++;
        ollama.then(AiDictionaryDraftIntegrationTest.draft("TABLE", table.getId(), "결제 테이블 업무 설명 " + drafted, "INFERRED",
                List.of(Map.of("type", "TABLE", "id", table.getId(), "name", "TB_PAY")), null, null, List.of()));
        return drafts.generate(user(), table.getId(), null, true, null).proposal();
    }

    private AiDictionaryDto.ReviewResult approve(AiDescriptionProposal p) {
        return reviews.approve(user(), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null));
    }

    private ColumnInfo column(ColumnInfo c) {
        return columnRepository.findById(c.getId()).orElseThrow();
    }

    private void edit(Long columnId, Consumer<ColumnInfo> change) {
        tx.executeWithoutResult(s -> change.accept(columnRepository.findById(columnId).orElseThrow()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("기다림 초과");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private TableInfoDto tableEdit(String biz, String base) {
        return TableInfoDto.builder().id(table.getId()).tableName("TB_PAY").tableDescription("결제 테이블").bizDescription(biz).bizDescriptionBase(base)
                .isMasterTable("N").sortOrder(1).build();
    }

    /** 일괄 편집 한 행 : 컬럼 설명은 columnDesc(null 이면 지금 값), 업무 설명은 biz/base (null 이면 보내지 않음) */
    private static ColumnBulkSaveDto bulk(ColumnInfo c, String columnDesc, String biz, String base) {
        ColumnBulkSaveDto.Row row = ColumnBulkSaveDto.Row.builder().key("r1").id(c.getId()).columnDesc(columnDesc == null ? c.getColumnDesc() : columnDesc)
                .bizDescription(biz).bizDescriptionBase(base).dataType(c.getDataType()).maxLength(c.getMaxLength()).isPk("N").isNullable("Y").isRequired("N")
                .isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
        return ColumnBulkSaveDto.builder().tableInfoId(c.getTableInfo().getId()).rows(List.of(row)).build();
    }
}
