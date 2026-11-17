package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.ai.provider.domain.dto.AiProviderDto;
import com.lexorma.ai.provider.domain.dto.AiProviderDto.ProviderRequest;
import com.lexorma.ai.provider.service.AiDefaultProviderService;
import com.lexorma.ai.provider.service.AiProviderService;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.RuleRequest;
import com.lexorma.core.network.service.ExternalConnectionRuleService;
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.ai.dictionary.service.ProposalVersions;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.table.controller.TableDesignRestController;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.service.SourceImportService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * AI-5 후속 : 실제 원본 PostgreSQL → JDBC 수집(원본 카탈로그 최신화) → 원본 매핑·가져오기 → AI 설명 제안 → 원본 변경 → 다시 최신화 → 승인.
 * 원본 DB·수집·매핑·가져오기·모델 변경 이력·재검증·승인은 실제 경로이고, AI 서버만 가짜({@link ScriptedOllama})다 (응답 문구가 아니라 재검증이 대상).
 * 원본 DB 는 테스트마다 새로 만든다 ({@link SourceJdbcTestSupport}, LEXORMA_TEST_SOURCE_POSTGRES_URL 이 없으면 건너뛴다).
 * 속성은 {@link SourceJdbcSecurityIntegrationTest} 와 같게 둔다 : 같은 Spring 컨텍스트를 함께 써 테스트 JVM 의 힙을 늘리지 않는다 (H2 전체 실행에서 컨텍스트가 하나 더 생기면 힙이 부족했다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1",
                "lexorma.source.jdbc.query-timeout-seconds=5", "lexorma.source.connection-test.per-minute=1000"})
@ActiveProfiles("test")
class AiDictionarySourceRefreshIntegrationTest extends SourceJdbcTestSupport {

    private static final String MODEL = "gemma3:4b";
    private static final String[] SOURCE = {
            "create table public.tb_pay (pay_id bigint primary key, pay_amt integer not null, pay_memo varchar(100), pay_stts_cd varchar(10))",
            "comment on table public.tb_pay is '결제'",
            "comment on column public.tb_pay.pay_amt is '결제 금액(원)'",
            "comment on column public.tb_pay.pay_memo is '결제 메모'",
            "create table public.tb_ord (ord_id bigint primary key, ord_nm varchar(50))",
            "comment on column public.tb_ord.ord_nm is '주문 명'"};

    @Autowired SourceMappingService mapping;
    @Autowired SourceImportService imports;
    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
    @Autowired AiDictionaryQueryService queries;
    @Autowired AiProviderService providers;
    @Autowired AiDefaultProviderService defaults;
    @Autowired ExternalConnectionRuleService rules;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TableDesignService tableDesign;
    @Autowired TableDesignRestController tableApi;
    @Autowired ColumnManageService columnManage;

    private final List<AutoCloseable> servers = new ArrayList<>();
    private final List<Long> providerIds = new ArrayList<>();
    private final List<Long> ruleIds = new ArrayList<>();
    private ScriptedOllama ollama;
    private int drafted;

    @AfterEach
    void tearDownAi() throws Exception {
        defaults.clear();
        for (Long id : providerIds) providers.delete(id);
        for (Long id : ruleIds) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
    }

    /** 원본 DB·수집·기준 연결·스키마 매핑·가져오기(새 TABLE + 컬럼 + 원본 COMMENT)까지, 그리고 기본 AI Provider */
    private record World(SourceDb db, Env env, SchemaInfo schema, Map<String, TableInfo> tables) {
        ExecutionContext user() {
            return ExecutionContext.user(env.managerDto());
        }
    }

    private World world() throws Exception {
        SourceDb db = source(SOURCE);
        Env e = env(db);
        SchemaInfo schema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(e.project().getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build()));
        mapping.setBase(e.managerDto(), new SourceMappingDto.BaseRequest(e.connection(), null));
        collect(e, "FULL");
        Long publicSchema = mapping.overview(e.managerDto()).schemas().stream().filter(r -> "public".equals(r.sourceSchemaName())).findFirst().orElseThrow().sourceSchemaId();
        mapping.mapSchema(e.managerDto(), new SourceMappingDto.SchemaRequest(publicSchema, schema.getId()));
        Map<String, List<String>> selection = new LinkedHashMap<>();
        selection.put("tb_pay", List.of("TABLE:CREATE", "TABLE:COMMENT", "COLUMN:pay_id:ADD", "COLUMN:pay_amt:ADD", "COLUMN:pay_memo:ADD", "COLUMN:pay_stts_cd:ADD",
                "COLUMN:pay_amt:COMMENT", "COLUMN:pay_memo:COMMENT", "PRIMARY_KEY:tb_pay_pkey"));
        selection.put("tb_ord", List.of("TABLE:CREATE", "COLUMN:ord_id:ADD", "COLUMN:ord_nm:ADD", "COLUMN:ord_nm:COMMENT", "PRIMARY_KEY:tb_ord_pkey"));
        importSelected(e, selection);
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        for (TableInfo t : tableRepository.findAll()) if (e.project().getId().equals(t.getProjectId())) tables.put(t.getTableName(), t);

        ollama = new ScriptedOllama();
        servers.add(ollama);
        ollama.show(List.of("completion", "tools"), 32768);
        Long provider = providers.create(new ProviderRequest("AI-5R " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), MODEL, true, null)).id();
        providerIds.add(provider);
        ruleIds.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5 원본 최신화 재검증")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider));
        return new World(db, e, schema, tables);
    }

    private void importSelected(Env e, Map<String, List<String>> selection) {
        List<SourceImportDto.TableSelection> tables = new ArrayList<>();
        selection.forEach((table, items) -> tables.add(new SourceImportDto.TableSelection("S" + sourceTableId(e, table), null, items)));
        SourceImportDto.Plan plan = imports.plan(e.managerDto(), new SourceImportDto.Request(e.connection(), null, tables, null, null));
        assertThat(plan.tables()).flatExtracting(SourceImportDto.PlanTable::items).filteredOn(i -> !i.selectable()).as("가져올 수 없는 항목").isEmpty();
        imports.apply(e.managerDto(), new SourceImportDto.Request(plan.connectionId(), plan.reflectedOrder(), tables, plan.planToken(), plan.requestId()));
    }

    private Long sourceTableId(Env e, String name) {
        return jdbc.queryForObject("select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = ? and TBL_NM = ?", Long.class, e.connection(), name);
    }

    private ColumnInfo column(World w, String table, String column) {
        return columnRepository.findAllByTableInfo_IdIn(List.of(w.tables().get(table).getId())).stream().filter(c -> c.getColumnName().equals(column)).findFirst().orElseThrow();
    }

    /** 컬럼 대상 제안 (근거 = 그 컬럼). 앞 제안이 검토 대기면 대체한다 */
    private AiDescriptionProposal propose(World w, String table, String column) {
        ColumnInfo c = column(w, table, column);
        drafted++;
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), column + " 설명 " + drafted, "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", c.getId(), "name", column)), null, null, List.of()));
        return drafts.generate(w.user(), w.tables().get(table).getId(), c.getId(), true, null).proposal();
    }

    private AiDictionaryDto.ReviewResult approve(World w, AiDescriptionProposal p) {
        return reviews.approve(w.user(), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null));
    }

    private void alterSource(World w, String... ddl) throws Exception {
        for (String statement : ddl) w.db().jdbc().execute(statement);
        collect(w.env(), "FULL");
    }

    private void editModel(Long columnId, Consumer<ColumnInfo> change) {
        tx.executeWithoutResult(s -> change.accept(columnRepository.findById(columnId).orElseThrow()));
    }

    private String biz(World w, String table, String column) {
        return column(w, table, column).getBizDescription();
    }

    // ------------------------------------------------------------------ 3.1 원본 COMMENT 변경

    @Test
    @DisplayName("원본 COMMENT : 근거 컬럼의 원본 COMMENT 가 바뀐 뒤 다시 최신화하면 승인이 REVIEW_REQUIRED 가 되고 변경 전후가 보인다. 확정 업무 설명·원본 COMMENT 칸·설명·승인 여부는 그대로")
    void sourceCommentChange() throws Exception {
        World w = world();
        ColumnInfo amount = column(w, "TB_PAY", "PAY_AMT");
        assertThat(amount.getSourceComment()).as("가져오기로 원본 COMMENT 칸에 들어갔다").isEqualTo("결제 금액(원)");
        // 먼저 하나를 승인해 확정 업무 설명을 만든다
        AiDescriptionProposal first = propose(w, "TB_PAY", "PAY_AMT");
        ProposalVersions.Base base = ProposalVersions.read(jdbc.queryForObject("select CRTR_VER_CN from TB_AI_EXPLN_SGSTN where AI_EXPLN_SGSTN_SN = ?", String.class, first.getId()));
        assertThat(base.sources()).as("매핑된 원본 테이블의 기준").singleElement().satisfies(s -> {
            assertThat(s.tableComment()).isEqualTo("결제");
            assertThat(s.columns()).singleElement().satisfies(c -> assertThat(c.comment()).isEqualTo("결제 금액(원)"));
        });
        assertThat(approve(w, first).outcome()).isEqualTo("APPROVED");
        String confirmed = biz(w, "TB_PAY", "PAY_AMT");

        AiDescriptionProposal next = propose(w, "TB_PAY", "PAY_AMT");
        alterSource(w, "comment on column public.tb_pay.pay_amt is '결제 금액(부가세 포함)'");
        AiDictionaryDto.ProposalDetail preview = queries.detail(w.user(), next.getId());
        assertThat(preview.changes()).extracting(ProposalVersions.Change::kind, ProposalVersions.Change::fields)
                .containsExactly(tuple("SOURCE_CHANGED", List.of("PAY_AMT:comment")));
        assertThat(preview.changes().getFirst().diffs()).as("변경 전후의 근거").containsExactly(new ProposalVersions.Diff("PAY_AMT:comment", "결제 금액(원)", "결제 금액(부가세 포함)"));
        AiDictionaryDto.ReviewResult result = approve(w, next);
        assertThat(result.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.proposal().status()).isEqualTo(AiDescriptionProposal.ReviewStatus.REVIEW_REQUIRED);

        ColumnInfo after = column(w, "TB_PAY", "PAY_AMT");
        assertThat(after.getBizDescription()).isEqualTo(confirmed);
        assertThat(after.getSourceComment()).as("최신화는 모델의 원본 COMMENT 칸을 바꾸지 않는다 (가져오기만)").isEqualTo("결제 금액(원)");
        assertThat(after.getIsApproval()).isEqualTo(amount.getIsApproval());
        assertThat(after.getColumnDesc()).isEqualTo(amount.getColumnDesc());
    }

    // ------------------------------------------------------------------ 3.2 원본 컬럼 속성 변경

    @Test
    @DisplayName("원본 컬럼 속성 : 근거 컬럼의 타입·길이·NULL 허용이 바뀌면 REVIEW_REQUIRED (정의 변경 전후), 근거가 아닌 컬럼의 같은 변경은 승인. 테이블 대상은 테이블 COMMENT·근거 컬럼만 본다")
    void sourceColumnAttributes() throws Exception {
        World w = world();
        // 근거가 아닌 컬럼(PAY_STTS_CD)의 타입·길이·NULL 변경 → 승인
        AiDescriptionProposal unrelated = propose(w, "TB_PAY", "PAY_AMT");
        alterSource(w, "alter table public.tb_pay alter column pay_stts_cd type varchar(30)", "alter table public.tb_pay alter column pay_stts_cd set not null");
        assertThat(approve(w, unrelated).outcome()).isEqualTo("APPROVED");

        // 타입 (integer → bigint)
        AiDescriptionProposal type = propose(w, "TB_PAY", "PAY_AMT");
        alterSource(w, "alter table public.tb_pay alter column pay_amt type bigint");
        AiDictionaryDto.ReviewResult typed = approve(w, type);
        assertThat(typed.outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(typed.changes()).singleElement().satisfies(c -> {
            assertThat(c.fields()).containsExactly("PAY_AMT:definition");
            assertThat(c.diffs()).containsExactly(new ProposalVersions.Diff("PAY_AMT:definition", "integer · NOT NULL", "bigint · NOT NULL"));
        });
        // 길이 (varchar(100) → varchar(200))
        AiDescriptionProposal length = propose(w, "TB_PAY", "PAY_MEMO");
        alterSource(w, "alter table public.tb_pay alter column pay_memo type varchar(200)");
        assertThat(approve(w, length).changes()).flatExtracting(ProposalVersions.Change::diffs)
                .containsExactly(new ProposalVersions.Diff("PAY_MEMO:definition", "character varying(100) · length 100 · NULL", "character varying(200) · length 200 · NULL"));
        // NULL 허용 (NOT NULL → NULL)
        AiDescriptionProposal nullable = propose(w, "TB_PAY", "PAY_AMT");
        alterSource(w, "alter table public.tb_pay alter column pay_amt drop not null");
        assertThat(approve(w, nullable).changes()).flatExtracting(ProposalVersions.Change::diffs)
                .containsExactly(new ProposalVersions.Diff("PAY_AMT:definition", "bigint · NOT NULL", "bigint · NULL"));

        // 테이블 대상 (근거 = 테이블 + PAY_AMT) : 근거가 아닌 컬럼 변경은 승인, 테이블 COMMENT 변경은 재검토
        TableInfo pay = w.tables().get("TB_PAY");
        AiDescriptionProposal table = proposeTable(w, pay, "PAY_AMT");
        alterSource(w, "alter table public.tb_pay alter column pay_stts_cd type varchar(40)");
        assertThat(approve(w, table).outcome()).isEqualTo("APPROVED");
        AiDescriptionProposal tableComment = proposeTable(w, pay, "PAY_AMT");
        alterSource(w, "comment on table public.tb_pay is '결제 내역'");
        assertThat(approve(w, tableComment).changes()).flatExtracting(ProposalVersions.Change::diffs)
                .containsExactly(new ProposalVersions.Diff("tableComment", "결제", "결제 내역"));
    }

    // ------------------------------------------------------------------ 3.3 무관한 변경

    @Test
    @DisplayName("무관한 변경 : 근거가 아닌 모델 컬럼 수정, 대상의 정렬 순서·승인 여부 변경, 원본 최신화(값 같음)만 있으면 승인. 대상의 모델 타입이 바뀌면 MODIFIED (변경 전후)")
    void unrelatedChanges() throws Exception {
        World w = world();
        ColumnInfo amount = column(w, "TB_PAY", "PAY_AMT");
        ColumnInfo memo = column(w, "TB_PAY", "PAY_MEMO");
        AiDescriptionProposal p = propose(w, "TB_PAY", "PAY_AMT");
        editModel(memo.getId(), c -> c.setMaxLength(500));
        editModel(amount.getId(), c -> c.setSortOrder(99));
        editModel(amount.getId(), c -> c.setIsApproval(true));
        editModel(amount.getId(), c -> c.setIsApproval(false));
        collect(w.env(), "FULL"); // 원본은 그대로 다시 최신화 (반영 순서가 늘지 않는다 : 바뀐 것이 없다)
        assertThat(queries.detail(w.user(), p.getId()).changes()).isEmpty();
        assertThat(approve(w, p).outcome()).isEqualTo("APPROVED");
        assertThat(column(w, "TB_PAY", "PAY_AMT").getIsApproval()).as("AI 제안 승인은 객체 승인 여부를 바꾸지 않는다").isFalse();

        AiDescriptionProposal typed = propose(w, "TB_PAY", "PAY_AMT");
        editModel(amount.getId(), c -> c.setDataType("BIGINT"));
        assertThat(approve(w, typed).changes()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("MODIFIED");
            assertThat(c.fields()).containsExactly("dataType");
            assertThat(c.diffs()).containsExactly(new ProposalVersions.Diff("dataType", amount.getDataType(), "BIGINT"));
        });
    }

    // ------------------------------------------------------------------ 3.4 원본 매핑·대상 변경

    @Test
    @DisplayName("매핑·대상 : 원본 매핑이 없는 모델은 원본이 바뀌어도 승인(모델만 본다), 원본 컬럼 삭제·원본 테이블 삭제·대상 삭제는 REVIEW_REQUIRED, 원본 연결이 없어져 확인할 수 없으면 REVIEW_REQUIRED")
    void mappingAndTargets() throws Exception {
        World w = world();
        // 원본 매핑이 없는 물리 모델
        ColumnInfo local = tx.execute(s -> {
            TableInfo t = tableRepository.save(TableInfo.builder().projectId(w.env().project().getId()).schema(w.schema()).tableName("TB_LOCAL").tableKorName("로컬")
                    .isApproval(false).isMasterTable(false).build());
            return columnRepository.save(ColumnInfo.builder().projectId(t.getProjectId()).tableInfo(t).tableName("TB_LOCAL").columnName("PAY_AMT").columnDesc("로컬 금액")
                    .dataType("INTEGER").sortOrder(1).isApproval(false).build());
        });
        w.tables().put("TB_LOCAL", tableRepository.findById(local.getTableInfo().getId()).orElseThrow());
        AiDescriptionProposal unmapped = propose(w, "TB_LOCAL", "PAY_AMT");
        assertThat(base(unmapped).sources()).as("매핑이 없으면 원본 기준도 없다").isEmpty();
        alterSource(w, "comment on column public.tb_pay.pay_amt is '같은 이름의 원본 컬럼 변경'");
        assertThat(approve(w, unmapped).outcome()).isEqualTo("APPROVED");

        // 원본 컬럼 삭제
        AiDescriptionProposal dropped = propose(w, "TB_PAY", "PAY_MEMO");
        alterSource(w, "alter table public.tb_pay drop column pay_memo");
        assertThat(approve(w, dropped).changes()).extracting(ProposalVersions.Change::kind, ProposalVersions.Change::fields)
                .containsExactly(tuple("SOURCE_CHANGED", List.of("PAY_MEMO:missing")));

        // 원본 테이블 삭제
        AiDescriptionProposal order = propose(w, "TB_ORD", "ORD_NM");
        alterSource(w, "drop table public.tb_ord");
        assertThat(approve(w, order).changes()).extracting(ProposalVersions.Change::kind).containsExactly("SOURCE_TABLE_MISSING");

        // 대상 삭제 (모델)
        AiDescriptionProposal gone = propose(w, "TB_LOCAL", "PAY_AMT");
        tx.executeWithoutResult(s -> {
            ColumnInfo c = columnRepository.findById(local.getId()).orElseThrow();
            c.getTableInfo().getColumns().remove(c);
            columnRepository.delete(c);
        });
        assertThat(approve(w, gone).changes()).extracting(ProposalVersions.Change::kind).containsExactly("TARGET_DELETED");

        // 원본 연결 삭제 : 매핑과 원본 카탈로그가 정리되어 원본 값을 확인할 수 없다 → 안전하게 재검토
        AiDescriptionProposal unverifiable = propose(w, "TB_PAY", "PAY_AMT");
        connectionApi.delete(w.env().managerDto(), w.env().connection());
        assertThat(approve(w, unverifiable).changes()).extracting(ProposalVersions.Change::kind).containsExactly("SOURCE_TABLE_MISSING");
    }

    // ------------------------------------------------------------------ 3.5 업무 설명 보호

    @Test
    @DisplayName("업무 설명 보호 : 최신화·다시 가져오기는 확정 업무 설명을 바꾸지 않고(원본 COMMENT 칸만), 재확인은 승인 설명을 표시만 한다. 직접 수정은 화면이 읽은 뒤 바뀐 업무 설명을 덮어쓰지 않고 권한을 확인한다")
    void bizDescriptionProtection() throws Exception {
        World w = world();
        ColumnInfo amount = column(w, "TB_PAY", "PAY_AMT");
        AiDescriptionProposal approved = propose(w, "TB_PAY", "PAY_AMT");
        assertThat(approve(w, approved).outcome()).isEqualTo("APPROVED");
        String confirmed = biz(w, "TB_PAY", "PAY_AMT");
        AiDescriptionProposal pending = propose(w, "TB_PAY", "PAY_AMT");

        // 원본 COMMENT 변경 → 최신화 → 다시 가져오기 (COMMENT 만)
        alterSource(w, "comment on column public.tb_pay.pay_amt is '결제 금액(부가세 포함)'");
        AiDictionaryDto.RecheckResult recheck = reviews.recheck(w.user());
        assertThat(recheck.reviewRequired()).isEqualTo(1);
        assertThat(recheck.recheckFlagged()).isEqualTo(1);
        AiDictionaryDto.ProposalDetail flagged = queries.detail(w.user(), approved.getId());
        assertThat(flagged.proposal().status()).isEqualTo(AiDescriptionProposal.ReviewStatus.APPROVED);
        assertThat(flagged.proposal().recheckNeeded()).isTrue();
        importSelected(w.env(), Map.of("tb_pay", List.of("COLUMN:pay_amt:COMMENT")));
        ColumnInfo reimported = column(w, "TB_PAY", "PAY_AMT");
        assertThat(reimported.getSourceComment()).isEqualTo("결제 금액(부가세 포함)");
        assertThat(reimported.getBizDescription()).isEqualTo(confirmed);
        assertThat(reimported.getColumnDesc()).isEqualTo(amount.getColumnDesc());
        assertThat(reimported.getIsApproval()).isEqualTo(amount.getIsApproval());
        assertThat(queries.detail(w.user(), pending.getId()).changes()).extracting(ProposalVersions.Change::kind).contains("SOURCE_CHANGED", "MODIFIED");

        // 테이블 직접 수정 : 화면을 연 뒤 AI 제안 승인이 업무 설명을 바꿨다
        TableInfo ord = w.tables().get("TB_ORD");
        String opened = tableRepository.findById(ord.getId()).orElseThrow().getBizDescription();
        AiDescriptionProposal tableProposal = proposeTable(w, ord, "ORD_NM");
        assertThat(approve(w, tableProposal).outcome()).isEqualTo("APPROVED");
        String approvedTable = tableRepository.findById(ord.getId()).orElseThrow().getBizDescription();
        // 업무 설명을 고치지 않고 저장 (보내지 않음) → 그대로
        tableDesign.updateTable(tableEdit(ord, null, null), w.env().managerDto());
        assertThat(tableRepository.findById(ord.getId()).orElseThrow().getBizDescription()).isEqualTo(approvedTable);
        // 읽은 값을 기준으로 고쳐 저장 → 충돌, 그대로
        assertThatThrownBy(() -> tableDesign.updateTable(tableEdit(ord, "사람이 쓴 설명", opened == null ? "" : opened), w.env().managerDto()))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        assertThat(tableRepository.findById(ord.getId()).orElseThrow().getBizDescription()).isEqualTo(approvedTable);
        // 지금 값을 기준으로 → 저장된다
        tableDesign.updateTable(tableEdit(ord, "사람이 쓴 설명", approvedTable), w.env().managerDto());
        assertThat(tableRepository.findById(ord.getId()).orElseThrow().getBizDescription()).isEqualTo("사람이 쓴 설명");
        // 권한 : VIEWER 는 수정할 수 없다
        var viewer = account("viewer", "ROLE_VIEWER");
        tx.executeWithoutResult(s -> projectMemberRepository.save(com.lexorma.core.project.domain.entity.ProjectMember.builder().project(w.env().project()).account(viewer).isActive(true).build()));
        AccountDtoHolder.viewer = SecurityTestSupport.loginAs(projectRepository, w.env().project().getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> tableApi.updateTable(tableEdit(ord, "열람자", null), AccountDtoHolder.viewer))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        SecurityTestSupport.loginAs(projectRepository, w.env().project().getId(), String.valueOf(w.env().manager().getId()), "ROLE_DBA");

        // 컬럼 일괄 편집 : 승인된 테이블, 화면을 연 뒤 승인으로 바뀐 업무 설명
        TableInfo pay = w.tables().get("TB_PAY");
        tx.executeWithoutResult(s -> tableRepository.findById(pay.getId()).orElseThrow().setIsApproval(true));
        ColumnInfo memo = column(w, "TB_PAY", "PAY_MEMO");
        AiDescriptionProposal memoProposal = propose(w, "TB_PAY", "PAY_MEMO");
        assertThat(approve(w, memoProposal).outcome()).isEqualTo("APPROVED");
        String approvedMemo = biz(w, "TB_PAY", "PAY_MEMO");
        ColumnBulkSaveDto.Result conflict = columnManage.saveColumnsBulk(w.env().managerDto(), bulk(pay, memo, "사람이 쓴 메모", ""));
        assertThat(conflict.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("업무 설명"));
        assertThat(biz(w, "TB_PAY", "PAY_MEMO")).isEqualTo(approvedMemo);
        assertThat(columnManage.saveColumnsBulk(w.env().managerDto(), bulk(pay, memo, null, null)).errors()).isNullOrEmpty();
        assertThat(biz(w, "TB_PAY", "PAY_MEMO")).as("업무 설명을 보내지 않으면 그대로").isEqualTo(approvedMemo);
    }

    private static final class AccountDtoHolder {
        static com.lexorma.core.security.domain.dto.AccountDto viewer;
    }

    private static TableInfoDto tableEdit(TableInfo t, String biz, String base) {
        return TableInfoDto.builder().id(t.getId()).tableName(t.getTableName()).tableDescription("주문 테이블").bizDescription(biz).bizDescriptionBase(base)
                .isMasterTable("N").sortOrder(1).build();
    }

    private static ColumnBulkSaveDto bulk(TableInfo t, ColumnInfo c, String biz, String base) {
        ColumnBulkSaveDto.Row row = ColumnBulkSaveDto.Row.builder().key("r1").id(c.getId()).columnDesc(c.getColumnDesc() == null ? "메모" : c.getColumnDesc())
                .bizDescription(biz).bizDescriptionBase(base).dataType(c.getDataType()).maxLength(c.getMaxLength()).isPk("N").isNullable("Y").isRequired("N")
                .isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
        return ColumnBulkSaveDto.builder().tableInfoId(t.getId()).rows(List.of(row)).build();
    }

    private AiDescriptionProposal proposeTable(World w, TableInfo t, String evidenceColumn) {
        ColumnInfo c = column(w, t.getTableName(), evidenceColumn);
        drafted++;
        ollama.then(AiDictionaryDraftIntegrationTest.draft("TABLE", t.getId(), t.getTableName() + " 설명 " + drafted, "INFERRED",
                List.of(Map.of("type", "TABLE", "id", t.getId(), "name", t.getTableName()), Map.of("type", "COLUMN", "id", c.getId(), "name", evidenceColumn)), null, null, List.of()));
        return drafts.generate(w.user(), t.getId(), null, true, null).proposal();
    }

    private ProposalVersions.Base base(AiDescriptionProposal p) {
        return ProposalVersions.read(jdbc.queryForObject("select CRTR_VER_CN from TB_AI_EXPLN_SGSTN where AI_EXPLN_SGSTN_SN = ?", String.class, p.getId()));
    }
}
