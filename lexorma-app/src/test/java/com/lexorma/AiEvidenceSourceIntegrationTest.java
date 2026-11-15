package com.lexorma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.data.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 설명 제안 근거의 출처 (AI-5-QUAL-1) : 원본 COMMENT(SRC_ANTN_CN) · 업무 설명(TASK_EXPLN) · 컬럼 설명(COL_EXPLN, DDL COMMENT) · 표준 용어 정의가
 * 모델에 보내는 맥락과 제안 상세의 지금 값에서 저마다 제 칸으로 간다. 값이 같아도 칸(출처)은 바뀌지 않고, 없는 출처는 만들지 않으며, 모델이 쓴 설명은 원본 COMMENT 가 되지 않는다.
 * AI-5-EVAL 에서 TB_ACNT.ACNT_SN 제안이 컬럼 설명을 "(소스 DB COMMENT: …)" 로 적은 것은 이 경로가 아니라 모델의 문구였다 — 여기서는 서버가 넘기는 근거가 맞는지를 결정적으로 본다.
 * 모델 응답은 가짜 Ollama(ScriptedOllama)이고, 이 테스트는 모델에 실제로 보낸 요청 본문을 읽는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiEvidenceSourceIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDictionaryQueryService queries;
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
    @Autowired StandardTermRepository termRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private ScriptedOllama ollama;
    private Long providerId;
    private Long ruleId;
    private Account owner;
    private Project project;
    private TableInfo table;
    private final Map<String, ColumnInfo> columns = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = tx.execute(s -> accountRepository.save(Account.builder().username("src-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_USER")))).projectMember(new ArrayList<>()).build()));
        project = projectRepository.save(Project.builder().projectName("src-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            StandardTerm term = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("계정_구분_코드").commonStandardTermAbbreviation("ACNT_SE_CD")
                    .commonStandardTermDescription("용어 사전 정의 : 계정 종류").isApproval(true).build());
            table = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_ACNT").tableKorName("계정").isApproval(false).isMasterTable(false).build());
            // AI-5-EVAL 의 TB_ACNT.ACNT_SN : 컬럼 설명만 있고 원본 COMMENT·업무 설명은 없다
            column("ACNT_SN", "계정 일련번호", null, null, null, 1);
            column("A_SRC_ONLY", null, "계정 식별번호", null, null, 2);           // A : 원본 COMMENT 만
            column("B_BIZ_ONLY", null, null, "계정 식별번호", null, 3);           // B : 업무 설명만
            column("C_BOTH", null, "계정번호", "계정을 구분하는 내부 식별번호", null, 4); // C : 둘 다 (다름)
            column("D_SAME", null, "계정 식별번호", "계정 식별번호", null, 5);     // D : 둘 다 (같은 문자열)
            column("E_NONE", null, null, null, null, 6);                          // E : 둘 다 없음
            column("ACNT_SE_CD", null, null, null, term, 7);                      // 표준 용어 정의만
        });
        ollama = new ScriptedOllama();
        ollama.show(List.of("completion", "tools"), 32768);
        providerId = providers.create(new ProviderRequest("SRC " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), "gemma3:4b", true, null)).id();
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5-QUAL-1")).id();
        defaults.set(new AiProviderDto.DefaultProviderRequest(providerId));
    }

    @AfterEach
    void tearDown() {
        defaults.clear();
        providers.delete(providerId);
        rules.delete(ruleId);
        ollama.close();
    }

    @Test
    @DisplayName("모델에 보내는 컬럼 근거 : 원본 COMMENT·업무 설명·컬럼 설명이 각자 칸에 (A 원본만 · B 업무만 · C 둘 다 · D 같은 문자열 · E 없음 · ACNT_SN 컬럼 설명만), 없는 칸은 만들지 않는다")
    void columnSourcesInPrompt() throws Exception {
        Map<String, JsonNode> sent = columnsSent(generate("ACNT_SN"));

        assertSources(sent.get("ACNT_SN"), "계정 일련번호", null, null);
        assertSources(sent.get("A_SRC_ONLY"), null, "계정 식별번호", null);
        assertSources(sent.get("B_BIZ_ONLY"), null, null, "계정 식별번호");
        assertSources(sent.get("C_BOTH"), null, "계정번호", "계정을 구분하는 내부 식별번호");
        assertSources(sent.get("D_SAME"), null, "계정 식별번호", "계정 식별번호");
        assertSources(sent.get("E_NONE"), null, null, null);
        // 표준 용어 정의는 컬럼의 설명 칸이 아니라 용어 이름으로만 연결되고, 정의는 표준 용어 근거([3])의 description 으로 간다
        assertSources(sent.get("ACNT_SE_CD"), null, null, null);
        assertThat(sent.get("ACNT_SE_CD").get("standardTerm").asText()).isEqualTo("계정_구분_코드");
    }

    @Test
    @DisplayName("표준 용어 정의만 있는 컬럼 : 정의는 searchStandardTerms 결과의 description 으로만 가고, 원본 COMMENT·업무 설명 칸에는 없다")
    void termDefinitionStaysTermEvidence() throws Exception {
        String prompt = generate("ACNT_SE_CD");
        JsonNode terms = section(prompt, "searchStandardTerms");
        assertThat(terms.toString()).contains("용어 사전 정의 : 계정 종류");
        JsonNode column = columnsSent(prompt).get("ACNT_SE_CD");
        assertThat(column.toString()).doesNotContain("용어 사전 정의");
    }

    @Test
    @DisplayName("제안 상세의 지금 값 : 원본 COMMENT·업무 설명·설명이 저장된 칸 그대로 (화면은 이 칸으로 이름을 붙인다). 모델이 쓴 설명은 원본 COMMENT·업무 설명이 되지 않는다")
    void detailCurrentKeepsSources() {
        for (String name : List.of("ACNT_SN", "A_SRC_ONLY", "B_BIZ_ONLY", "C_BOTH", "D_SAME", "E_NONE")) {
            ColumnInfo c = columns.get(name);
            ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), "모델이 쓴 설명 " + name + " (소스 DB COMMENT: 지어낸 출처)", "INFERRED",
                    List.of(Map.of("type", "COLUMN", "id", c.getId(), "name", name)), null, null, List.of()));
            AiDictionaryDto.ProposalDetail detail = queries.detail(user(), drafts.generate(user(), table.getId(), c.getId(), true, null).proposal().getId());
            assertThat(detail.current().sourceComment()).as(name).isEqualTo(c.getSourceComment());
            assertThat(detail.current().bizDescription()).as(name).isEqualTo(c.getBizDescription());
            assertThat(detail.current().description()).as(name).isEqualTo(c.getColumnDesc());
            Map<String, Object> stored = jdbc.queryForMap("select SRC_ANTN_CN, TASK_EXPLN, COL_EXPLN from TB_COL where COL_SN = ?", c.getId());
            assertThat(stored.get("SRC_ANTN_CN")).as(name).isEqualTo(c.getSourceComment());
            assertThat(stored.get("TASK_EXPLN")).as(name).isEqualTo(c.getBizDescription());
            assertThat(stored.get("COL_EXPLN")).as(name).isEqualTo(c.getColumnDesc());
        }
    }

    @Test
    @DisplayName("상태 코드 정의 : 예제의 검토_상태_코드 정의를 쓰는 컬럼의 초안 맥락에 실제 검토 상태 8개가 모두 간다 (예제 정의는 복사만, 예제 프로젝트는 건드리지 않는다)")
    void statusDefinitionReachesPrompt() throws Exception {
        String example = jdbc.queryForObject("select TRM_EXPLN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '검토_상태_코드'", String.class);
        tx.executeWithoutResult(s -> {
            StandardTerm status = termRepository.save(StandardTerm.builder().projectId(project.getId()).commonStandardTermName("검토_상태_코드")
                    .commonStandardTermAbbreviation("RVW_STTS_CD").commonStandardTermDescription(example).isApproval(true).build());
            column("RVW_STTS_CD", "검토 상태 코드", null, null, status, 8);
        });
        String terms = section(generate("RVW_STTS_CD"), "searchStandardTerms").toString();
        for (AiDescriptionProposal.ReviewStatus value : AiDescriptionProposal.ReviewStatus.values()) assertThat(terms).contains(value.name());
    }

    // ------------------------------------------------------------------ 준비

    private void assertSources(JsonNode column, String description, String sourceComment, String businessDescription) {
        assertThat(column).as("근거에 컬럼이 있다").isNotNull();
        String name = column.get("columnName").asText();
        assertThat(text(column, "description")).as(name + " description(컬럼 설명)").isEqualTo(description);
        assertThat(text(column, "sourceComment")).as(name + " sourceComment(원본 COMMENT)").isEqualTo(sourceComment);
        assertThat(text(column, "businessDescription")).as(name + " businessDescription(업무 설명)").isEqualTo(businessDescription);
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** 대상 컬럼으로 초안을 만들고 모델에 보낸 마지막 요청의 사용자 메시지(맥락)를 돌려준다 */
    private String generate(String columnName) throws Exception {
        ColumnInfo c = columns.get(columnName);
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), columnName + " 설명", "INFERRED",
                List.of(Map.of("type", "COLUMN", "id", c.getId(), "name", columnName)), null, null, List.of()));
        drafts.generate(user(), table.getId(), c.getId(), true, null);
        JsonNode body = JSON.readTree(ollama.chats.getLast());
        StringBuilder content = new StringBuilder();
        for (JsonNode message : body.get("messages")) {
            if ("user".equals(message.get("role").asText())) content.append(message.get("content").asText()).append('\n');
        }
        return content.toString();
    }

    /** 맥락 안의 "[도구 {입력}] {결과}" 한 줄의 결과 JSON */
    private static JsonNode section(String prompt, String tool) throws Exception {
        for (String line : prompt.split("\n")) {
            if (line.startsWith("[" + tool + " ")) return JSON.readTree(line.substring(line.indexOf("] ") + 2));
        }
        throw new AssertionError(tool + " 결과가 맥락에 없다");
    }

    private static Map<String, JsonNode> columnsSent(String prompt) throws Exception {
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode c : section(prompt, "getTableColumns").get("columns")) byName.put(c.get("columnName").asText(), c);
        return byName;
    }

    private void column(String name, String desc, String sourceComment, String biz, StandardTerm term, int sort) {
        columns.put(name, columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_ACNT").columnName(name).columnDesc(desc)
                .sourceComment(sourceComment).bizDescription(biz).dataType("VARCHAR").maxLength(20).sortOrder(sort).standardTerms(term).isPk(sort == 1)
                .isNullable(sort != 1).isApproval(false).build()));
    }

    private ExecutionContext user() {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(owner.getId())).projectId(project.getId()).build());
    }
}
