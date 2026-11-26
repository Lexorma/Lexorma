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
import com.lexorma.ai.answer.StructuredAnswerParser;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.evidence.EvidenceVerifier.Overall;
import com.lexorma.ai.evidence.EvidenceVerifier.Reason;
import com.lexorma.ai.evidence.EvidenceVerifier.Status;
import com.lexorma.ai.service.AiMetadataQueryService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.lexorma.MetadataToolIntegrationTest.answer;
import static com.lexorma.MetadataToolIntegrationTest.raw;
import static com.lexorma.MetadataToolIntegrationTest.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * AI-4 후속 : 구조화 응답(JSON Schema 검증·1회 재요청)과 근거 검증. 가짜 Ollama(127.0.0.1)가 Tool 호출과 최종 답변을 돌려준다.
 * 흐름 : 1단계 Tool 조회·초안 → 2단계 구조화(JSON) → 근거 검증.
 * <ul>
 *   <li>구조화 응답 : 스키마 위반(필드 누락·타입·허용하지 않은 근거 종류·정의하지 않은 필드·JSON 아님)은 한 번만 다시 요청하고 — Tool 을 다시 실행하지 않고
 *       이미 받은 결과를 넘긴다 — 그래도 틀리면 AI_STRUCTURED_OUTPUT_INVALID</li>
 *   <li>근거 검증 : 테이블·컬럼(소속)·관계(FK/LOGICAL)·용어·단어·도메인·스키마, 없는 ID, 다른 프로젝트, 이름 불일치, 조회하지 않은 근거, 조회 뒤 수정·삭제,
 *       버전 없음, 근거 없는 답변, 일부만 맞는 답변. 상태는 서버가 정하고 AI 가 넣은 상태 필드는 스키마가 거절한다</li>
 *   <li>권한 : 검증 직전 권한 회수 → 질의 실패, ASYNC·SCHEDULED 도 같은 프로젝트 범위로 검증</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiEvidenceIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final long EXAMPLE_PROJECT = 0L;
    private static final String MODEL = "qwen3:8b";

    @Autowired AiMetadataQueryService queries;
    @Autowired StructuredAnswerParser parser;
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
    @Autowired TableRelationRepository relationRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<Long> exampleMembers = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();

    private Account viewer;
    private Account ownerB;
    private Project projectB;
    private TableInfo tableB;
    private ColumnInfo columnB;
    private ScriptedOllama ollama;

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        viewer = account("ROLE_VIEWER");
        exampleMembers.add(member(EXAMPLE_PROJECT, viewer));
        ownerB = account("ROLE_USER");
        projectB = project(ownerB);
        tx.executeWithoutResult(s -> {
            SchemaInfo schema = schemaInfoRepository.save(SchemaInfo.builder().projectId(projectB.getId()).schemaName(SchemaInfo.DEFAULT_NAME)
                    .schemaDescription("기본 스키마").isDefault(true).build());
            tableB = tableInfoRepository.save(TableInfo.builder().projectId(projectB.getId()).schema(schema).tableName("TB_EVIDENCE_B").tableKorName("근거_B")
                    .tableDescription("근거 검증용").isApproval(false).isMasterTable(false).build());
            columnB = columnInfoRepository.save(ColumnInfo.builder().projectId(projectB.getId()).tableInfo(tableB).tableName("TB_EVIDENCE_B").columnName("EVD_SN")
                    .columnDesc("근거 일련번호").dataType("BIGINT").sortOrder(1).isApproval(false).build());
        });
        ollama = new ScriptedOllama();
        servers.add(ollama);
        AiProviderDto.Provider provider = providers.create(new ProviderRequest("AI-4E " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), MODEL, true, null));
        createdProviders.add(provider.id());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-4 근거 테스트")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider.id()));
        SecurityTestSupport.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        defaults.clear();
        for (Long id : createdProviders) providers.delete(id);
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        for (Long id : exampleMembers) projectMemberRepository.deleteById(id);
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 구조화 응답

    @Test
    @DisplayName("JSON Schema : 필수 필드·타입·근거 종류·정의하지 않은 필드·길이·개수·JSON 아님·뒤에 남는 글자를 거절하고, Spring AI 정리기로 코드 블록은 벗긴다")
    void schemaValidation() {
        assertThat(parser.parse("{\"answer\":\"ok\",\"evidence\":[]}").valid()).isTrue();
        assertThat(parser.parse("```json\n{\"answer\":\"ok\",\"evidence\":[{\"type\":\"TABLE\",\"id\":1,\"name\":\"TB_A\"}]}\n```").valid()).as("코드 블록").isTrue();
        assertThat(parser.parse("{\"answer\":\"**AI\\_STNG\\_SN**\",\"evidence\":[]}").value().answer()).as("Markdown 이스케이프 \\_").isEqualTo("**AI_STNG_SN**");
        assertThat(parser.parse("{\"answer\":\"ok\",\"evidence\":[{\"type\":\"COLUMN\",\"id\":2,\"name\":\"C\",\"tableId\":1},"
                + "{\"type\":\"RELATION\",\"id\":3,\"name\":\"FK\",\"kind\":\"FK\"}]}").valid()).isTrue();

        for (String bad : List.of(
                "그냥 문장입니다",
                "{\"answer\":\"ok\"}",
                "{\"evidence\":[]}",
                "{\"answer\":\"\",\"evidence\":[]}",
                "{\"answer\":1,\"evidence\":[]}",
                "{\"answer\":\"ok\",\"evidence\":{}}",
                "{\"answer\":\"ok\",\"evidence\":[{\"type\":\"PROJECT\",\"id\":1,\"name\":\"x\"}]}",
                "{\"answer\":\"ok\",\"evidence\":[{\"type\":\"TABLE\",\"id\":\"1\",\"name\":\"x\"}]}",
                "{\"answer\":\"ok\",\"evidence\":[{\"type\":\"TABLE\",\"id\":-1,\"name\":\"x\"}]}",
                "{\"answer\":\"ok\",\"evidence\":[{\"type\":\"TABLE\",\"id\":1}]}",
                "{\"answer\":\"ok\",\"evidence\":[{\"type\":\"TABLE\",\"id\":1,\"name\":\"x\",\"status\":\"VERIFIED\"}]}",
                "{\"answer\":\"ok\",\"evidence\":[],\"verification\":\"VERIFIED\"}",
                "{\"answer\":\"ok\",\"evidence\":[{\"type\":\"RELATION\",\"id\":1,\"name\":\"x\",\"kind\":\"GUESS\"}]}",
                "{\"answer\":\"" + "가".repeat(8001) + "\",\"evidence\":[]}",
                "{\"answer\":\"ok\",\"evidence\":[]} trailing",
                "{\"answer\":\"ok\",\"answer\":\"twice\",\"evidence\":[]}")) {
            StructuredAnswerParser.Result result = parser.parse(bad);
            assertThat(result.valid()).as(bad.length() > 80 ? bad.substring(0, 80) : bad).isFalse();
            assertThat(result.errors()).isNotEmpty();
        }
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 1; i <= 31; i++) many.add(Map.of("type", "TABLE", "id", i, "name", "T" + i));
        assertThat(parser.parse(JSON.writeValueAsString(Map.of("answer", "ok", "evidence", many))).valid()).as("근거 31개").isFalse();
    }

    @Test
    @DisplayName("재요청 : 형식이 틀리면 한 번만 다시 묻는다 — Tool 을 다시 실행하지 않고 이미 받은 결과·실패 사유를 넘기며 Tool 정의를 보내지 않는다. 두 번째도 틀리면 AI_STRUCTURED_OUTPUT_INVALID")
    void retryOnceThenFail() throws Exception {
        Long table = exampleTable("TB_AI_STNG");
        afterTools(toolCalls(MODEL, Map.of("getTableDetail", Map.of("tableId", table))))
                .then(raw(MODEL, "TB_AI_STNG 은 AI 설정 테이블입니다."))
                .then(answer(MODEL, "TB_AI_STNG 은 AI 설정 테이블입니다.", List.of(evidence("TABLE", table, "TB_AI_STNG"))));
        AiMetadataQueryDto.QueryResult result = queries.query(user(viewer, EXAMPLE_PROJECT), question("AI 설정 테이블?"));
        assertThat(result.structuredRetries()).isEqualTo(1);
        assertThat(result.tools()).extracting(c -> c.name()).containsExactly("getTableDetail");
        assertThat(result.verification().status()).isEqualTo(Overall.VERIFIED);
        assertThat(ollama.chats).as("Tool 라운드·초안·구조화·재요청").hasSize(4);
        for (int i : List.of(2, 3)) {
            JsonNode structuring = JSON.readTree(ollama.chats.get(i));
            assertThat(structuring.path("tools").isMissingNode() || structuring.path("tools").isEmpty()).as("구조화 단계에는 Tool 을 보내지 않는다").isTrue();
            assertThat(ollama.chats.get(i)).contains("[getTableDetail]").contains("TB_AI_STNG");
        }
        assertThat(ollama.chats.get(3)).contains("previous reply was rejected");

        // 두 번째도 틀리면 끝 (세 번 묻지 않는다)
        afterTools(toolCalls(MODEL, Map.of("getTableDetail", Map.of("tableId", table))))
                .then(raw(MODEL, "{\"answer\":\"x\"}"))
                .then(raw(MODEL, "{\"answer\":\"x\",\"evidence\":[],\"status\":\"VERIFIED\"}"))
                .then(answer(MODEL, "never asked"));
        int before = ollama.chats.size();
        assertThatThrownBy(() -> queries.query(user(viewer, EXAMPLE_PROJECT), question("AI 설정 테이블?")))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_STRUCTURED_OUTPUT_INVALID);
        assertThat(ollama.chats.size() - before).as("Tool 라운드 1 + 초안 1 + 구조화 1 + 재요청 1 (세 번째 구조화 요청 없음)").isEqualTo(4);
    }

    @Test
    @DisplayName("재요청에서 모델이 Tool 을 불러도 실행하지 않는다 — 그 응답에서 구조화가 바로 끝난다 (AI 공통 실행 안정성 3차 AES-D16 : 예전에는 거절을 돌려주고 모델을 다시 불렀다), 조회 없음")
    void retryCannotRunTools() {
        Long table = exampleTable("TB_AI_STNG");
        afterTools(toolCalls(MODEL, Map.of("getTableDetail", Map.of("tableId", table))))
                .then(raw(MODEL, "not json"))
                .then(toolCalls(MODEL, Map.of("getTableColumns", Map.of("tableId", table))))
                .then(answer(MODEL, "ok", List.of(evidence("TABLE", table, "TB_AI_STNG"))));
        int before = ollama.chats.size();
        assertThatThrownBy(() -> queries.query(user(viewer, EXAMPLE_PROJECT), question("AI 설정?")))
                .extracting("errorCode").isEqualTo(com.lexorma.common.exception.domain.ErrorCode.AI_STRUCTURED_OUTPUT_INVALID);
        assertThat(ollama.chats.size() - before).as("Tool 단계 2번 + 구조화 2번 (형식 오류 재요청 1번 — 그 응답의 Tool 요청에서 끝, 거절 결과로 다시 부르지 않는다)").isEqualTo(4);
    }

    // ------------------------------------------------------------------ 근거 검증

    @Test
    @DisplayName("근거 검증 : 실제로 조회한 테이블·컬럼(소속)·관계(FK)·용어·단어·도메인·스키마는 VERIFIED. 없는 ID·다른 프로젝트·이름 불일치·소속 불일치·관계 종류 불일치·조회하지 않은 근거는 각각 구분하고, 다른 프로젝트 행의 내용은 내지 않는다")
    void verifiesEvidenceAgainstToolResultsAndDb() {
        Long setting = exampleTable("TB_AI_STNG");
        Long provider = exampleTable("TB_AI_PRVDR");
        Long settingSn = exampleColumn(setting, "AI_STNG_SN");
        Long defaultSn = exampleColumn(setting, "BSC_AI_PRVDR_SN");
        Long fk = jdbc.queryForObject("select REL_SN from TB_TBL_REL where PRJ_SN = 0 and REL_NM = 'FK_AI_STNG_01'", Long.class);
        Long term = jdbc.queryForObject("select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = 'AI_설정_일련번호'", Long.class);
        Long word = jdbc.queryForObject("select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '설정'", Long.class);
        Long schema = jdbc.queryForObject("select SCHM_SN from TB_SCHM where PRJ_SN = 0 and BSC_YN = true", Long.class);
        Long domain = jdbc.queryForObject("select min(STD_DMN_SN) from TB_STD_DMN where PRJ_SN = 0 and DMN_NM like '%번호%'", Long.class);
        String domainName = jdbc.queryForObject("select DMN_NM from TB_STD_DMN where STD_DMN_SN = ?", String.class, domain);
        Long notRetrieved = exampleTable("TB_TBL");

        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put("getTableColumns", Map.of("tableId", setting));
        calls.put("getTableRelations", Map.of("tableId", setting));
        calls.put("searchStandardTerms", Map.of("keyword", "AI_설정_일련번호"));
        calls.put("searchStandardWords", Map.of("keyword", "설정"));
        calls.put("listSchemas", Map.of());
        calls.put("searchStandardDomains", Map.of("keyword", domainName));
        afterTools(toolCalls(MODEL, calls)).then(answer(MODEL, "TB_AI_STNG 의 BSC_AI_PRVDR_SN 은 FK_AI_STNG_01 로 TB_AI_PRVDR 을 참조합니다.", List.of(
                evidence("TABLE", setting, "tb_ai_stng"),
                evidence("TABLE", provider, "AI 제공자"),
                columnEvidence(settingSn, "AI_STNG_SN", setting),
                relationEvidence(fk, "FK_AI_STNG_01", "FK"),
                evidence("TERM", term, "AI_STNG_SN"),
                evidence("WORD", word, "STNG"),
                evidence("SCHEMA", schema, SchemaInfo.DEFAULT_NAME),
                evidence("DOMAIN", domain, domainName),
                // 실패 사례
                evidence("TABLE", 999_999_999L, "TB_NOWHERE"),
                evidence("TABLE", tableB.getId(), "TB_EVIDENCE_B"),
                evidence("COLUMN", columnB.getId(), "EVD_SN"),
                evidence("TABLE", setting, "TB_SOMETHING_ELSE"),
                columnEvidence(defaultSn, "BSC_AI_PRVDR_SN", provider),
                relationEvidence(fk, "FK_AI_STNG_01", "LOGICAL"),
                evidence("TABLE", notRetrieved, "TB_TBL"))));

        AiMetadataQueryDto.QueryResult result = queries.query(user(viewer, EXAMPLE_PROJECT), question("AI 설정과 제공자 관계?"));
        assertThat(result.evidence()).extracting(EvidenceVerifier.Result::type, EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(
                tuple(EvidenceType.TABLE, Status.VERIFIED, null),
                tuple(EvidenceType.TABLE, Status.VERIFIED, null),
                tuple(EvidenceType.COLUMN, Status.VERIFIED, null),
                tuple(EvidenceType.RELATION, Status.VERIFIED, null),
                tuple(EvidenceType.TERM, Status.VERIFIED, null),
                tuple(EvidenceType.WORD, Status.VERIFIED, null),
                tuple(EvidenceType.SCHEMA, Status.VERIFIED, null),
                tuple(EvidenceType.DOMAIN, Status.VERIFIED, null),
                tuple(EvidenceType.TABLE, Status.NOT_FOUND, null),
                tuple(EvidenceType.TABLE, Status.OUT_OF_SCOPE, null),
                tuple(EvidenceType.COLUMN, Status.OUT_OF_SCOPE, null),
                // 같은 대상(TABLE setting)을 다시 들면 처음 것만 본다 → 이름 불일치 사례는 다른 근거로 확인
                tuple(EvidenceType.COLUMN, Status.MISMATCH, Reason.PARENT),
                tuple(EvidenceType.TABLE, Status.UNVERIFIABLE, Reason.NOT_RETRIEVED));
        assertThat(result.verification()).extracting(EvidenceVerifier.Summary::status, EvidenceVerifier.Summary::total, EvidenceVerifier.Summary::verified)
                .containsExactly(Overall.PARTIAL, 13, 8);
        assertThat(JSON.writeValueAsString(result.evidence())).as("다른 프로젝트 행의 서버 내용(테이블 코멘트·설명)은 응답에 없다").doesNotContain("근거_B", "근거 검증용");

        // 이름 불일치·관계 종류 불일치 (같은 대상을 처음 드는 답변)
        afterTools(toolCalls(MODEL, Map.of("getTableRelations", Map.of("tableId", setting))))
                .then(answer(MODEL, "x", List.of(evidence("TABLE", setting, "TB_SOMETHING_ELSE"), relationEvidence(fk, "FK_AI_STNG_01", "LOGICAL"))));
        assertThat(queries.query(user(viewer, EXAMPLE_PROJECT), question("관계?")).evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason)
                .containsExactly(tuple(Status.MISMATCH, Reason.NAME), tuple(Status.MISMATCH, Reason.KIND));
    }

    @Test
    @DisplayName("근거 없는 답변 · Tool 없이 든 근거 : NO_EVIDENCE / UNVERIFIED (검증 완료로 표시하지 않는다)")
    void answersWithoutVerifiedEvidence() {
        ollama.then(answer(MODEL, "일반적인 설명입니다."));
        AiMetadataQueryDto.QueryResult plain = queries.query(user(viewer, EXAMPLE_PROJECT), question("데이터 표준화란?"));
        assertThat(plain.verification().status()).isEqualTo(Overall.NO_EVIDENCE);
        assertThat(plain.evidence()).isEmpty();

        Long table = exampleTable("TB_AI_STNG");
        ollama.then(answer(MODEL, "TB_AI_STNG 이 있습니다.", List.of(evidence("TABLE", table, "TB_AI_STNG"))));
        AiMetadataQueryDto.QueryResult guessed = queries.query(user(viewer, EXAMPLE_PROJECT), question("AI 설정 테이블?"));
        assertThat(guessed.tools()).isEmpty();
        assertThat(guessed.verification().status()).isEqualTo(Overall.UNVERIFIED);
        assertThat(guessed.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(tuple(Status.UNVERIFIABLE, Reason.NOT_RETRIEVED));
    }

    @Test
    @DisplayName("버전 : Tool 조회 뒤 최종 답변 전에 수정되면 STALE(MODIFIED), 지워지면 STALE(DELETED), 수정 일시가 없으면 UNVERIFIABLE(VERSION_UNKNOWN)")
    void detectsChangesAfterRetrieval() {
        ExecutionContext context = user(ownerB, projectB.getId());

        afterTools(toolCalls(MODEL, Map.of("getTableColumns", Map.of("tableId", tableB.getId()))))
                .then(answer(MODEL, "x", List.of(evidence("TABLE", tableB.getId(), "TB_EVIDENCE_B"), columnEvidence(columnB.getId(), "EVD_SN", tableB.getId()))),
                        () -> tx.executeWithoutResult(s -> columnInfoRepository.findById(columnB.getId()).orElseThrow().setColumnDesc("조회 뒤 바뀐 설명")));
        assertThat(queries.query(context, question("근거 테이블?")).evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason)
                .containsExactly(tuple(Status.VERIFIED, null), tuple(Status.STALE, Reason.MODIFIED));

        afterTools(toolCalls(MODEL, Map.of("getTableColumns", Map.of("tableId", tableB.getId()))))
                .then(answer(MODEL, "x", List.of(columnEvidence(columnB.getId(), "EVD_SN", tableB.getId()))),
                        () -> tx.executeWithoutResult(s -> columnInfoRepository.deleteById(columnB.getId())));
        AiMetadataQueryDto.QueryResult deleted = queries.query(context, question("근거 컬럼?"));
        assertThat(deleted.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(tuple(Status.STALE, Reason.DELETED));
        assertThat(deleted.verification().status()).isEqualTo(Overall.UNVERIFIED);

        jdbc.update("update TB_TBL set MDFCN_DT = null where TBL_SN = ?", tableB.getId());
        afterTools(toolCalls(MODEL, Map.of("getTableDetail", Map.of("tableId", tableB.getId()))))
                .then(answer(MODEL, "x", List.of(evidence("TABLE", tableB.getId(), "TB_EVIDENCE_B"))));
        assertThat(queries.query(context, question("근거 테이블?")).evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason)
                .containsExactly(tuple(Status.UNVERIFIABLE, Reason.VERSION_UNKNOWN));
    }

    @Test
    @DisplayName("권한·실행 컨텍스트 : 검증 직전 권한 회수 → 질의 실패, SCHEDULED·ASYNC 도 같은 프로젝트 범위로 검증 (다른 프로젝트 근거는 OUT_OF_SCOPE)")
    void verificationKeepsExecutionScope() {
        Long exampleSetting = exampleTable("TB_AI_STNG");

        ExecutionContext scheduled = ExecutionContext.scheduled(projectB.getId(), "nightly-" + UUID.randomUUID());
        afterTools(toolCalls(MODEL, Map.of("searchTables", Map.of())))
                .then(answer(MODEL, "x", List.of(evidence("TABLE", tableB.getId(), "TB_EVIDENCE_B"), evidence("TABLE", exampleSetting, "TB_AI_STNG"))));
        assertThat(queries.query(scheduled, question("테이블?")).evidence()).extracting(EvidenceVerifier.Result::status)
                .containsExactly(Status.VERIFIED, Status.OUT_OF_SCOPE);

        ExecutionContext async = ExecutionContext.async(EXAMPLE_PROJECT, viewer.getId(), "job-" + UUID.randomUUID());
        afterTools(toolCalls(MODEL, Map.of("getTableDetail", Map.of("tableId", exampleSetting))))
                .then(answer(MODEL, "x", List.of(evidence("TABLE", exampleSetting, "TB_AI_STNG"), evidence("TABLE", tableB.getId(), "TB_EVIDENCE_B"))));
        assertThat(queries.query(async, question("테이블?")).evidence()).extracting(EvidenceVerifier.Result::status)
                .containsExactly(Status.VERIFIED, Status.OUT_OF_SCOPE);

        // Tool 은 통과했지만 최종 답변 직전에 멤버십이 회수된다 → 근거 검증에서 질의 전체를 끝낸다
        afterTools(toolCalls(MODEL, Map.of("getTableDetail", Map.of("tableId", exampleSetting))))
                .then(answer(MODEL, "x", List.of(evidence("TABLE", exampleSetting, "TB_AI_STNG"))),
                        () -> tx.executeWithoutResult(s -> projectMemberRepository.findById(exampleMembers.getFirst()).orElseThrow().setIsActive(false)));
        assertThatThrownBy(() -> queries.query(user(viewer, EXAMPLE_PROJECT), question("테이블?")))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    // ------------------------------------------------------------------ 도움 함수

    /** Tool 호출 응답 다음에 1단계의 초안(자유 문장)을 이어 둔다. 그다음에 둔 응답이 2단계(구조화)의 응답이다 */
    private ScriptedOllama afterTools(String toolCallReply) {
        return ollama.then(toolCallReply).then(raw(MODEL, "초안 답변입니다."));
    }

    private static Map<String, Object> evidence(String type, Long id, String name) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("id", id);
        e.put("name", name);
        return e;
    }

    private static Map<String, Object> columnEvidence(Long id, String name, Long tableId) {
        Map<String, Object> e = evidence("COLUMN", id, name);
        e.put("tableId", tableId);
        return e;
    }

    private static Map<String, Object> relationEvidence(Long id, String name, String kind) {
        Map<String, Object> e = evidence("RELATION", id, name);
        e.put("kind", kind);
        return e;
    }

    private static AiMetadataQueryDto.QueryRequest question(String text) {
        return new AiMetadataQueryDto.QueryRequest(text);
    }

    private static ExecutionContext user(Account account, Long projectId) {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(account.getId())).projectId(projectId).build());
    }

    private Long exampleTable(String name) {
        return tableInfoRepository.findAllByTableNameAndProjectId(name, EXAMPLE_PROJECT).getFirst().getId();
    }

    private Long exampleColumn(Long tableId, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, tableId, name);
    }

    private Account account(String role) {
        String username = "ai4e-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(Account owner) {
        Project project = projectRepository.save(Project.builder().projectName("ai4e-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        return project;
    }

    private Long member(Long projectId, Account account) {
        return tx.execute(s -> projectMemberRepository.save(ProjectMember.builder().project(projectRepository.findById(projectId).orElseThrow())
                .account(account).isActive(true).build()).getId());
    }
}
