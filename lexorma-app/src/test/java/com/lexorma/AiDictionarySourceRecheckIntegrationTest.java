package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.core.ai.domain.dto.AiProviderDto;
import com.lexorma.core.ai.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.core.ai.service.AiDefaultProviderService;
import com.lexorma.core.ai.service.AiProviderService;
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
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.data.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.data.ai.dictionary.service.ProposalVersions;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AI-5 승인 시 원본 카탈로그 재검증 : 대상 테이블이 원본 테이블과 매핑되어 있으면 생성 때의 원본 값(반영 순서·테이블 COMMENT·근거 컬럼의 타입·NULL·COMMENT)과
 * 지금 값을 비교한다. 원본 카탈로그 읽기 계약({@link SourceCatalogReader})만 가짜로 바꿔 최신화를 흉내 낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiDictionarySourceRecheckIntegrationTest {

    private static final String MODEL = "gemma3:4b";
    private static final long CONNECTION = 7_000_001L;
    private static final long SOURCE_TABLE = 7_000_010L;

    @MockitoBean SourceCatalogReader sourceCatalog;
    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaInfoRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SourceTableMappingRepository mappingRepository;
    @Autowired TransactionTemplate tx;

    private final List<AutoCloseable> servers = new ArrayList<>();
    private Long providerId;
    private Long ruleId;
    private Account owner;
    private Project project;
    private TableInfo table;
    private ColumnInfo target;
    private ScriptedOllama ollama;

    private record Source(int order, String tableComment, String type, String comment, String otherType) {
    }

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = tx.execute(s -> accountRepository.save(Account.builder().username("ai5s-" + UUID.randomUUID().toString().substring(0, 8)).name("o").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_USER")))).projectMember(new ArrayList<>()).build()));
        project = projectRepository.save(Project.builder().projectName("ai5s-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        tx.executeWithoutResult(s -> {
            SchemaInfo schema = schemaInfoRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName(SchemaInfo.DEFAULT_NAME).schemaDescription("기본").isDefault(true).build());
            table = tableInfoRepository.save(TableInfo.builder().projectId(project.getId()).schema(schema).tableName("TB_PAY").tableKorName("결제").isApproval(false).isMasterTable(false).build());
            target = columnInfoRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_PAY").columnName("PAY_AMT").columnDesc("결제 금액")
                    .dataType("INTEGER").sortOrder(1).isApproval(false).build());
            columnInfoRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_PAY").columnName("PAY_MEMO").columnDesc("메모")
                    .dataType("VARCHAR").sortOrder(2).isApproval(false).build());
            mappingRepository.save(SourceTableMapping.builder().projectId(project.getId()).connectionId(CONNECTION).sourceSchemaId(1L).sourceTableId(SOURCE_TABLE)
                    .pathKey("public").compareName("tb_pay").creationCycle(1).reflectedOrder(1).tableId(table.getId()).excluded(false).build());
        });
        ollama = new ScriptedOllama();
        servers.add(ollama);
        ollama.show(List.of("completion", "tools"), 32768);
        providerId = providers.create(new ProviderRequest("AI-5S " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), MODEL, true, null)).id();
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5 원본 재검증")).id();
        defaults.set(new AiProviderDto.DefaultProviderRequest(providerId));
        when(sourceCatalog.compareName(any(), any(), anyString())).thenAnswer(inv -> ((String) inv.getArgument(2)).toLowerCase(Locale.ROOT));
    }

    @AfterEach
    void tearDown() throws Exception {
        defaults.clear();
        providers.delete(providerId);
        rules.delete(ruleId);
        for (AutoCloseable server : servers) server.close();
    }

    @Test
    @DisplayName("원본 재검증 : 최신화로 근거 컬럼의 원본 COMMENT·타입이 바뀌거나 원본 테이블이 없어지면 REVIEW_REQUIRED, 반영 순서만 바뀌었거나 근거가 아닌 컬럼만 바뀌면 승인")
    void sourceChanges() {
        // 반영 순서만 바뀌고 값은 같다 → 승인
        source(new Source(1, "결제", "integer", "payment amount", "text"));
        AiDescriptionProposal same = generate();
        source(new Source(2, "결제", "integer", "payment amount", "text"));
        assertThat(reviews.approve(owner(), same.getId(), req(same)).outcome()).isEqualTo("APPROVED");

        // 근거가 아닌 컬럼(PAY_MEMO)의 원본만 바뀌었다 → 승인 (업무 설명이 바뀌었으므로 다시 만든다)
        AiDescriptionProposal other = regenerate();
        source(new Source(3, "결제", "integer", "payment amount", "varchar(100)"));
        assertThat(reviews.approve(owner(), other.getId(), req(other)).outcome()).isEqualTo("APPROVED");

        // 근거 컬럼의 원본 COMMENT 변경 → REVIEW_REQUIRED
        AiDescriptionProposal comment = regenerate();
        source(new Source(4, "결제", "integer", "amount incl. VAT", "varchar(100)"));
        AiDictionaryDto.ReviewResult result = reviews.approve(owner(), comment.getId(), req(comment));
        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.changes()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("SOURCE_CHANGED");
            assertThat(c.fields()).containsExactly("PAY_AMT:comment");
        });

        // 근거 컬럼의 원본 타입 변경 (INTEGER → DECIMAL)
        AiDescriptionProposal type = regenerate();
        source(new Source(5, "결제", "numeric", "amount incl. VAT", "varchar(100)"));
        assertThat(reviews.approve(owner(), type.getId(), req(type)).changes()).extracting(ProposalVersions.Change::fields).containsExactly(List.of("PAY_AMT:definition"));

        // 원본 테이블이 없어졌다
        AiDescriptionProposal missing = regenerate();
        when(sourceCatalog.latest(eq(project.getId()), eq(CONNECTION))).thenReturn(Optional.of(new SourceCatalogReader.Snapshot(connection(), 6, 1L, LocalDateTime.now(), List.of(), List.of())));
        assertThat(reviews.approve(owner(), missing.getId(), req(missing)).changes()).extracting(ProposalVersions.Change::kind).containsExactly("SOURCE_TABLE_MISSING");
        assertThat(columnInfoRepository.findById(target.getId()).orElseThrow().getBizDescription()).as("앞서 승인한 업무 설명은 그대로").isEqualTo("결제 금액 (2)");
    }

    private int count = 0;

    private AiDescriptionProposal generate() {
        count++;
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", target.getId(), "결제 금액 (" + count + ")", "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", target.getId(), "name", "PAY_AMT")), null, null, List.of()));
        return drafts.generate(owner(), table.getId(), target.getId(), true, null).proposal();
    }

    private AiDescriptionProposal regenerate() {
        return generate();
    }

    private void source(Source s) {
        SourceCatalogReader.Connection connection = connection();
        SourceCatalogReader.Table sourceTable = new SourceCatalogReader.Table(SOURCE_TABLE, 1L, "public", "tb_pay", "tb_pay", "TABLE", s.tableComment(), 1);
        when(sourceCatalog.latest(eq(project.getId()), eq(CONNECTION))).thenReturn(Optional.of(new SourceCatalogReader.Snapshot(connection, s.order(), 1L, LocalDateTime.now(),
                List.of(), List.of(sourceTable))));
        SourceCatalogReader.TableDetail detail = new SourceCatalogReader.TableDetail(SOURCE_TABLE, List.of(
                new SourceCatalogReader.Column("pay_amt", "pay_amt", 1, s.type(), null, null, null, true, null, s.comment()),
                new SourceCatalogReader.Column("pay_memo", "pay_memo", 2, s.otherType(), null, null, null, true, null, "memo")), List.of(), List.of(), List.of());
        when(sourceCatalog.tableDetails(eq(project.getId()), eq(CONNECTION), anyInt(), anyCollection())).thenReturn(Map.of(SOURCE_TABLE, detail));
    }

    private static SourceCatalogReader.Connection connection() {
        return new SourceCatalogReader.Connection(CONNECTION, "src", "POSTGRESQL", "DBMS_DEFAULT", true, true, "public");
    }

    private ExecutionContext owner() {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(owner.getId())).projectId(project.getId()).build());
    }

    private static AiDictionaryDto.ReviewRequest req(AiDescriptionProposal p) {
        return new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null);
    }

}
