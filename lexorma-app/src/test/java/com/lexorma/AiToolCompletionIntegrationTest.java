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
import com.lexorma.core.network.http.ExternalConnectionBlockedException;
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
import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.context.ServerContextAssembler;
import com.lexorma.ai.context.ServerContextAssembler.Intent;
import com.lexorma.ai.domain.dto.AiMetadataQueryDto;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.evidence.EvidenceVerifier.Overall;
import com.lexorma.ai.evidence.EvidenceVerifier.Status;
import com.lexorma.ai.service.AiMetadataQueryService;
import com.lexorma.ai.service.AiMetadataQueryService.FallbackReason;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.ai.tool.MetadataToolSession.Mode;
import com.lexorma.ai.tool.MetadataToolset;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
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
 * AI-4 잔여 기능 (H2 / PostgreSQL) : 유사 컬럼 Tool, 변경 이력 Tool, 토큰 예산, Tool Calling 미지원 모델의 서버 맥락 대체 흐름.
 * 가짜 Ollama(127.0.0.1)가 /api/show(모델 정보)와 /api/chat 을 돌려준다. 질의 전체 누적 상한은 20000 토큰으로 낮춰 둔다 (예산 초과 확인용).
 * <ul>
 *   <li>findSimilarColumns : 정해진 규칙의 점수·사유(물리명·표준 용어·논리명·이름 부분·단어 겹침·도메인), 20건 제한, 다른 프로젝트 제외, 권한 회수, 근거 검증</li>
 *   <li>getMetadataHistory : 테이블(하위 컬럼·관계 포함)·컬럼·관계 이력, 이력 없음, 삭제된 대상, 다른 프로젝트, 변경 전후 구분, 건수 제한, 예시 값·사용자 미전송, 근거(HISTORY)</li>
 *   <li>토큰 : 요청마다 num_ctx·num_predict(모델 컨텍스트 이하), 보내기 전 입력 추정 초과·질의 누적 초과·출력 한도 잘림 → AI_TOKEN_BUDGET_EXCEEDED, 실측과 추정의 구분</li>
 *   <li>대체 흐름 : 모델 정보(capabilities)·명시적 거절로만 판정, 관련 조회만, 해석 불가 시 조회 없음, 서버 조회 근거(SERVER_CONTEXT) 검증, 다른 프로젝트·권한 회수·SEC-1 차단·일반 오류</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3",
        "lexorma.ai.query.total-tokens=20000"})
@ActiveProfiles("test")
class AiToolCompletionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final long EXAMPLE_PROJECT = 0L;
    private static final String MODEL = "gemma3:4b";

    @Autowired AiMetadataQueryService queries;
    @Autowired MetadataToolset toolset;
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
    @Autowired StandardDomainRepository domainRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdRules = new ArrayList<>();
    private final List<Long> createdProviders = new ArrayList<>();
    private final List<Long> members = new ArrayList<>();
    private final List<AutoCloseable> servers = new ArrayList<>();

    /** 프로젝트 C (viewer 는 VIEWER 멤버) : 유사 컬럼·이력용 데이터. 프로젝트 B : 다른 프로젝트 (viewer 는 멤버가 아니다) */
    private Account viewer;
    private Project projectC;
    private Project projectB;
    private Long viewerInC;
    private Map<String, ColumnInfo> columns;
    private Map<String, TableInfo> tables;
    private ColumnInfo otherProjectColumn;
    private TableInfo otherProjectTable;
    private StandardTerm memberTerm;

    @BeforeEach
    void setUp() {
        defaults.clear();
        viewer = account("ROLE_VIEWER");
        Account owner = account("ROLE_USER");
        projectC = project(owner);
        viewerInC = member(projectC.getId(), viewer);
        members.add(viewerInC);
        members.add(member(EXAMPLE_PROJECT, viewer));
        projectB = project(account("ROLE_USER"));
        tables = new LinkedHashMap<>();
        columns = new LinkedHashMap<>();
        tx.executeWithoutResult(s -> {
            Long c = projectC.getId();
            SchemaInfo schema = schemaInfoRepository.save(SchemaInfo.builder().projectId(c).schemaName(SchemaInfo.DEFAULT_NAME).schemaDescription("기본").isDefault(true).build());
            StandardDomain number = domainRepository.save(StandardDomain.builder().projectId(c).commonStandardDomainName("번호N").dataType("BIGINT").isApproval(true).build());
            memberTerm = termRepository.save(StandardTerm.builder().projectId(c).commonStandardTermName("회원_일련번호").commonStandardTermAbbreviation("MBR_SN")
                    .standardDomain(number).isApproval(true).build());
            StandardTerm orderTerm = termRepository.save(StandardTerm.builder().projectId(c).commonStandardTermName("주문_일련번호").commonStandardTermAbbreviation("ORD_SN")
                    .standardDomain(number).isApproval(true).build());
            for (String name : List.of("TB_MBR", "TB_ORD", "TB_PNT", "TB_ETC", "TB_BULK")) {
                tables.put(name, tableInfoRepository.save(TableInfo.builder().projectId(c).schema(schema).tableName(name).tableKorName(name + "_코멘트")
                        .isApproval(false).isMasterTable(false).build()));
            }
            column("TB_MBR", "MBR_SN", "회원 일련번호", memberTerm, null);
            column("TB_ORD", "MBR_SN", "주문 회원 일련번호", memberTerm, null);
            column("TB_ORD", "ORD_SN", "주문 일련번호", orderTerm, null);
            column("TB_PNT", "UPPR_MBR_SN", "상위 회원 일련번호", null, null);
            column("TB_ETC", "MEMBER_KEY", "회원 일련번호", null, null);
            column("TB_ETC", "SN_MBR", "거꾸로 쓴 이름", null, null);
            column("TB_ETC", "MBR_NM", "회원명", null, "SECRET-EXAMPLE-VALUE");
            relationRepository.save(relation(tables.get("TB_ORD"), tables.get("TB_MBR"), "FK_ORD_MBR", columns.get("TB_ORD.MBR_SN"), columns.get("TB_MBR.MBR_SN")));

            SchemaInfo schemaB = schemaInfoRepository.save(SchemaInfo.builder().projectId(projectB.getId()).schemaName(SchemaInfo.DEFAULT_NAME).schemaDescription("기본")
                    .isDefault(true).build());
            otherProjectTable = tableInfoRepository.save(TableInfo.builder().projectId(projectB.getId()).schema(schemaB).tableName("TB_MBR_B").tableKorName("다른_회원")
                    .isApproval(false).isMasterTable(false).build());
            otherProjectColumn = columnInfoRepository.save(ColumnInfo.builder().projectId(projectB.getId()).tableInfo(otherProjectTable).tableName("TB_MBR_B")
                    .columnName("MBR_SN").columnDesc("회원 일련번호").dataType("BIGINT").sortOrder(1).isApproval(false).build());
        });
        SecurityTestSupport.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        defaults.clear();
        for (Long id : createdProviders) providers.delete(id);
        for (Long id : createdRules) rules.delete(id);
        for (AutoCloseable server : servers) server.close();
        for (Long id : members) projectMemberRepository.findById(id).ifPresent(projectMemberRepository::delete);
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ findSimilarColumns

    @Test
    @DisplayName("findSimilarColumns : 정해진 규칙(물리명·표준 용어·논리명·이름 부분·단어 겹침·도메인)으로 점수·사유를 매기고 20건까지, 다른 프로젝트 컬럼은 없다. 권한이 회수되면 실행하지 않는다")
    void similarColumns() {
        MetadataToolSession session = session(ExecutionContext.user(principal(viewer, projectC.getId())));
        JsonNode byColumn = run("findSimilarColumns", Map.of("columnId", columns.get("TB_MBR.MBR_SN").getId(), "limit", 20), session);
        Map<String, JsonNode> found = byKey(byColumn.path("columns"));
        assertThat(found.keySet()).as(byColumn.toString()).contains("TB_ORD.MBR_SN", "TB_ETC.MEMBER_KEY", "TB_ETC.SN_MBR", "TB_PNT.UPPR_MBR_SN", "TB_ORD.ORD_SN");
        assertThat(byColumn.path("reference").path("columnName").asString()).isEqualTo("MBR_SN");
        assertThat(found).as("기준 컬럼 자신은 결과에 없다").doesNotContainKey("TB_MBR.MBR_SN");
        assertThat(found.get("TB_ORD.MBR_SN").path("score").asInt()).isEqualTo(100);
        assertThat(reasons(found.get("TB_ORD.MBR_SN"))).contains("NAME_EXACT", "SAME_TERM", "SAME_DOMAIN");
        assertThat(found.get("TB_ETC.MEMBER_KEY").path("score").asInt()).as("논리명이 같다").isEqualTo(85);
        assertThat(reasons(found.get("TB_ETC.MEMBER_KEY"))).containsExactly("LOGICAL_NAME_EXACT");
        assertThat(found.get("TB_ETC.SN_MBR").path("score").asInt()).as("단어가 모두 겹친다 (겹침률 1)").isEqualTo(80);
        assertThat(reasons(found.get("TB_ETC.SN_MBR"))).containsExactly("NAME_TOKENS");
        assertThat(found.get("TB_PNT.UPPR_MBR_SN").path("score").asInt()).isEqualTo(60);
        assertThat(reasons(found.get("TB_PNT.UPPR_MBR_SN"))).containsExactly("NAME_PARTIAL", "LOGICAL_NAME_PARTIAL");
        assertThat(found.get("TB_ORD.ORD_SN").path("score").asInt()).as("도메인만 같다").isEqualTo(20);
        assertThat(reasons(found.get("TB_ORD.ORD_SN"))).containsExactly("SAME_DOMAIN");
        assertThat(found).as("규칙에 맞지 않는 컬럼(MBR_NM)은 없다").doesNotContainKey("TB_ETC.MBR_NM");
        assertThat(byColumn.path("truncated").asBoolean()).isFalse();
        assertThat(byColumn.toString()).as("다른 프로젝트의 컬럼").doesNotContain("TB_MBR_B");
        List<Integer> scores = new ArrayList<>();
        byColumn.path("columns").forEach(c -> scores.add(c.path("score").asInt()));
        assertThat(scores).as("점수 순").containsExactly(100, 85, 80, 60, 20);

        // 최대 20건 : 비슷한 이름 25개를 더하면 잘렸다고 알린다
        tx.executeWithoutResult(s -> {
            for (int i = 1; i <= 25; i++) column("TB_BULK", "X" + i + "_MBR_SN", "대량 " + i, null, null);
        });
        JsonNode bulk = run("findSimilarColumns", Map.of("columnId", columns.get("TB_MBR.MBR_SN").getId(), "limit", 50), session);
        assertThat(bulk.path("columns").size()).as("최대 20건").isEqualTo(20);
        assertThat(bulk.path("truncated").asBoolean()).isTrue();

        // 검색어 : 물리명 → 그 약어의 표준 용어 / 한글 논리명(공백) → 용어명(_)
        JsonNode byName = run("findSimilarColumns", Map.of("keyword", "mbr_sn", "limit", 5), session);
        assertThat(byName.path("reference").path("standardTerms").get(0).asString()).isEqualTo("회원_일련번호");
        assertThat(byKey(byName.path("columns")).keySet()).contains("TB_MBR.MBR_SN", "TB_ORD.MBR_SN").hasSize(5);
        JsonNode byLogical = run("findSimilarColumns", Map.of("keyword", "회원 일련번호"), session);
        assertThat(byLogical.path("reference").path("columnName").asString()).as("용어의 약어가 기준 물리명").isEqualTo("MBR_SN");
        assertThat(reasons(byKey(byLogical.path("columns")).get("TB_ETC.MEMBER_KEY"))).contains("LOGICAL_NAME_EXACT");
        JsonNode unspaced = run("findSimilarColumns", Map.of("keyword", "회원일련번호"), session);
        assertThat(reasons(byKey(unspaced.path("columns")).get("TB_ETC.MEMBER_KEY"))).as("띄어쓰기 차이는 같게 본다").contains("LOGICAL_NAME_EXACT");

        // 다른 프로젝트의 컬럼 ID 는 없는 것과 같다
        JsonNode other = run("findSimilarColumns", Map.of("columnId", otherProjectColumn.getId()), session);
        assertThat(other.path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(session.calls()).extracting(MetadataToolSession.Call::status).last().isEqualTo(MetadataToolSession.Status.NOT_FOUND);

        // 권한 회수 : 실행하지 않는다
        projectMemberRepository.deleteById(viewerInC);
        int before = session.calls().size();
        assertThatThrownBy(() -> run("findSimilarColumns", Map.of("keyword", "MBR_SN"), session)).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED);
        assertThat(session.calls()).hasSize(before);
    }

    // ------------------------------------------------------------------ getMetadataHistory

    @Test
    @DisplayName("getMetadataHistory : 테이블(컬럼·관계 포함)·컬럼·관계의 이력을 최신 순으로, 바뀐 필드의 변경 전후만. 이력 없음·삭제된 대상·다른 프로젝트·건수 제한. 사용자 ID·예시 값은 보내지 않는다")
    void metadataHistory() throws IOException {
        TableInfo etc = tables.get("TB_ETC");
        ColumnInfo name = columns.get("TB_ETC.MBR_NM");
        tx.executeWithoutResult(s -> {
            TableInfo table = tableInfoRepository.findById(etc.getId()).orElseThrow();
            table.setTableKorName("기타_코멘트_변경");
        });
        tx.executeWithoutResult(s -> {
            ColumnInfo column = columnInfoRepository.findById(name.getId()).orElseThrow();
            column.setColumnDesc("회원 이름");
            column.setExample("SECRET-EXAMPLE-CHANGED");
        });
        ColumnInfo removed = columns.get("TB_ETC.SN_MBR");
        tx.executeWithoutResult(s -> columnInfoRepository.deleteById(removed.getId()));

        MetadataToolSession session = session(ExecutionContext.user(principal(viewer, projectC.getId())));
        JsonNode table = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", etc.getId(), "limit", 20), fresh());
        List<String> entries = new ArrayList<>();
        table.path("history").forEach(h -> entries.add(h.path("targetType").asString() + " " + h.path("targetName").asString() + " " + h.path("action").asString()));
        assertThat(entries).as("최신 순 : 컬럼 삭제 → 컬럼 수정 → 테이블 수정 → … 생성").startsWith("COLUMN TB_ETC.SN_MBR DELETE", "COLUMN TB_ETC.MBR_NM UPDATE", "TABLE TB_ETC UPDATE")
                .contains("TABLE TB_ETC CREATE", "COLUMN TB_ETC.MEMBER_KEY CREATE");
        JsonNode tableUpdate = find(table.path("history"), "TABLE TB_ETC UPDATE");
        assertThat(tableUpdate.path("changes")).hasSize(1);
        assertThat(tableUpdate.path("changes").get(0).path("field").asString()).isEqualTo("tableKorName");
        assertThat(tableUpdate.path("changes").get(0).path("before").asString()).isEqualTo("TB_ETC_코멘트");
        assertThat(tableUpdate.path("changes").get(0).path("after").asString()).isEqualTo("기타_코멘트_변경");
        assertThat(table.path("note").asString()).contains("not the current metadata");
        assertThat(table.toString()).as("예시 값·사용자 ID 는 보내지 않는다").doesNotContain("SECRET-EXAMPLE").doesNotContain("userId").doesNotContain("\"example\"");
        JsonNode columnUpdate = find(table.path("history"), "COLUMN TB_ETC.MBR_NM UPDATE");
        assertThat(columnUpdate.path("changes")).extracting(c -> c.path("field").asString()).containsExactly("columnDesc");

        // 하위 제외 / 컬럼 / 관계 / 삭제된 컬럼(이름으로)
        JsonNode only = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", etc.getId(), "includeChildren", false), fresh());
        only.path("history").forEach(h -> assertThat(h.path("targetType").asString()).isEqualTo("TABLE"));
        JsonNode column = run("getMetadataHistory", Map.of("targetType", "COLUMN", "targetId", name.getId()), fresh());
        assertThat(column.path("history")).extracting(h -> h.path("action").asString()).containsExactly("UPDATE", "CREATE");
        JsonNode relation = run("getMetadataHistory", Map.of("targetType", "RELATION", "targetName", "FK_ORD_MBR"), fresh());
        assertThat(relation.path("history")).extracting(h -> h.path("action").asString()).containsExactly("CREATE");
        JsonNode ordTable = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", tables.get("TB_ORD").getId(), "limit", 20), fresh());
        assertThat(ordTable.toString()).as("테이블 이력에 그 테이블의 관계도").contains("FK_ORD_MBR");
        JsonNode deleted = run("getMetadataHistory", Map.of("targetType", "COLUMN", "targetName", "TB_ETC.SN_MBR"), fresh());
        assertThat(deleted.path("history")).extracting(h -> h.path("action").asString()).containsExactly("DELETE", "CREATE");

        // 이력 없음 · 다른 프로젝트 · 건수 제한
        // 있는 대상인데 기록이 없다(예제 프로젝트의 시드는 SQL 로 넣어 이력이 없다) → 빈 목록 / 대상 자체가 없다 → "없음" (변경 없음과 구분)
        TableInfo seeded = tableInfoRepository.findAllByProjectIdAndTableNameIn(EXAMPLE_PROJECT, List.of("TB_AI_STNG")).getFirst();
        JsonNode noChange = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", seeded.getId()),
                session(ExecutionContext.user(principal(viewer, EXAMPLE_PROJECT))));
        assertThat(noChange.path("history")).isEmpty();
        assertThat(noChange.path("truncated").asBoolean()).isFalse();
        JsonNode none = run("getMetadataHistory", Map.of("targetType", "COLUMN", "targetId", Long.MAX_VALUE), fresh());
        assertThat(none.path("error").asString()).isEqualTo("NOT_FOUND");
        JsonNode korean = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetName", "기타 테이블"), fresh());
        assertThat(korean.path("error").asString()).as("한글명 : 대상 없음 + searchTables 안내").isEqualTo("NOT_FOUND");
        assertThat(korean.path("message").asString()).contains("searchTables");
        JsonNode other = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", otherProjectTable.getId()), fresh());
        assertThat(other.path("error").asString()).as("다른 프로젝트 테이블 : 없는 것과 같다").isEqualTo("NOT_FOUND");
        assertThat(other.toString()).doesNotContain("TB_MBR_B");
        JsonNode otherByName = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetName", "TB_MBR_B"), fresh());
        assertThat(otherByName.path("error").asString()).isEqualTo("NOT_FOUND");
        JsonNode limited = run("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", etc.getId(), "limit", 1), fresh());
        assertThat(limited.path("history")).hasSize(1);
        assertThat(limited.path("truncated").asBoolean()).isTrue();
        JsonNode bad = run("getMetadataHistory", Map.of("targetType", "TERM", "targetId", memberTerm.getId()), fresh());
        assertThat(bad.path("error").asString()).as("이력이 없는 종류(표준 용어)").isEqualTo("INVALID_ARGUMENT");

        // 근거 : 조회한 이력은 HISTORY 근거로 확인된다 (다른 프로젝트 이력 ID 는 범위 밖)
        Long historyId = tableUpdate.path("historyId").asLong();
        Long otherHistory = jdbc.queryForObject("select max(MDL_CHG_HSTRY_SN) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Long.class, projectB.getId());
        ScriptedOllama ollama = ready(List.of("completion", "tools"), 32768);
        ollama.then(toolCalls(MODEL, Map.of("getMetadataHistory", Map.of("targetType", "TABLE", "targetId", etc.getId()))))
                .then(raw(MODEL, "TB_ETC 의 코멘트가 바뀌었습니다."))
                .then(answer(MODEL, "TB_ETC 의 코멘트가 바뀌었습니다.", List.of(
                        Map.of("type", "HISTORY", "id", historyId, "name", "TB_ETC"),
                        Map.of("type", "HISTORY", "id", otherHistory == null ? 999_999_999L : otherHistory, "name", "TB_MBR_B"))));
        AiMetadataQueryDto.QueryResult result = queries.query(ExecutionContext.user(principal(viewer, projectC.getId())),
                new AiMetadataQueryDto.QueryRequest("TB_ETC 테이블 변경 이력"));
        assertThat(result.evidence()).extracting(EvidenceVerifier.Result::type, EvidenceVerifier.Result::status)
                .containsExactly(tuple(EvidenceType.HISTORY, Status.VERIFIED), tuple(EvidenceType.HISTORY, otherHistory == null ? Status.NOT_FOUND : Status.OUT_OF_SCOPE));
    }

    // ------------------------------------------------------------------ 토큰 예산

    @Test
    @DisplayName("토큰 예산 : 요청마다 num_ctx·num_predict 를 모델 컨텍스트 이하로 보내고, 사용량은 실측(AI 서버 값)과 추정을 나눈다. 입력 추정 초과·질의 누적 초과·출력 잘림은 AI_TOKEN_BUDGET_EXCEEDED")
    void tokenBudget() throws Exception {
        assertThat(TokenEstimator.estimate("abcdef")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("회원번호")).isEqualTo(4);
        assertThat(TokenEstimator.estimate(null)).isZero();
        ExecutionContext context = ExecutionContext.user(principal(viewer, EXAMPLE_PROJECT));

        ScriptedOllama ollama = ready(List.of("completion", "tools"), 32768);
        ollama.then(toolCalls(MODEL, Map.of("searchTables", Map.of("keyword", "AI 설정"))))
                .then(raw(MODEL, "TB_AI_STNG 입니다."))
                .then(answer(MODEL, "TB_AI_STNG 입니다."));
        AiMetadataQueryDto.QueryResult result = queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블?"));
        for (String chat : ollama.chats) {
            JsonNode options = JSON.readTree(chat).path("options");
            assertThat(options.path("num_ctx").asInt()).as("설정(16384)과 모델(32768) 중 작은 것").isEqualTo(16384);
            assertThat(options.path("num_predict").asInt()).isEqualTo(4096);
        }
        assertThat(ollama.shows).as("모델 정보는 질의당 한 번").hasSize(1);
        assertThat(result.usage().modelCalls()).as("Tool 라운드·초안·구조화 모두 센다").isEqualTo(3);
        assertThat(result.usage().promptTokens()).as("가짜 서버의 prompt_eval_count 합").isEqualTo(10 + 20 + 20);
        assertThat(result.usage().completionTokens()).isEqualTo(5 + 8 + 8);
        assertThat(result.usage().unmeasuredCalls()).isZero();
        assertThat(result.usage().estimatedInputTokens()).as("추정은 따로").isPositive();

        // 사용량을 알려 주지 않는 서버 : 0 이 아니라 실측 없음
        ollama.then(JSON.writeValueAsString(Map.of("model", MODEL, "message", Map.of("role", "assistant", "content", "일반 답변"), "done", true, "done_reason", "stop")));
        AiMetadataQueryDto.QueryResult unmeasured = queries.query(context, new AiMetadataQueryDto.QueryRequest("안녕"));
        assertThat(unmeasured.usage().promptTokens()).isNull();
        assertThat(unmeasured.usage().completionTokens()).isNull();
        assertThat(unmeasured.usage().unmeasuredCalls()).isEqualTo(1);

        // 출력이 한도에서 잘렸다
        ollama.then(JSON.writeValueAsString(Map.of("model", MODEL, "message", Map.of("role", "assistant", "content", "잘린 답"), "done", true, "done_reason", "length",
                "prompt_eval_count", 30, "eval_count", 4096)));
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("긴 답"))).extracting("errorCode").isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);

        // 질의 누적 : 첫 호출이 15000 토큰을 썼다 → 다음 호출(추정 입력 + 출력 한도 4096)이 20000 을 넘으므로 보내지 않는다
        int before = ollama.chats.size();
        ollama.then(JSON.writeValueAsString(Map.of("model", MODEL, "created_at", "2026-11-15T10:00:00Z", "message", Map.of("role", "assistant", "content", "",
                        "tool_calls", List.of(Map.of("function", Map.of("name", "listSchemas", "arguments", Map.of())))), "done", true, "done_reason", "stop",
                "prompt_eval_count", 15000, "eval_count", 5)))
                .then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("스키마?"))).extracting("errorCode").isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        assertThat(ollama.chats.size() - before).as("누적 상한을 넘는 요청은 보내지 않는다 (Tool 은 한 번 실행)").isEqualTo(1);

        // 모델 컨텍스트가 작다(2048) : 출력 512, 입력 1536 — Tool 정의만으로 넘으므로 아무것도 보내지 않는다 (잘라 보내지 않는다)
        ScriptedOllama small = ready(List.of("completion", "tools"), 2048);
        small.then(answer(MODEL, "보내지 않아야 하는 응답"));
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블?"))).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        assertThat(small.chats).isEmpty();
    }

    // ------------------------------------------------------------------ 대체 흐름

    @Test
    @DisplayName("서버 맥락 : 모델 정보가 Tool 미지원이면 Tool 없이, 서버가 질문에 맞는 최소 조회(테이블 검색 → 컬럼)만 하고 그 근거를 SERVER_CONTEXT 로 검증한다. 해석할 수 없는 질문은 조회하지 않는다")
    void serverContextFallback() throws Exception {
        assertThat(ServerContextAssembler.analyze("AI 설정 테이블의 컬럼을 알려줘")).satisfies(a -> {
            assertThat(a.intents()).containsExactly(Intent.COLUMNS);
            assertThat(a.keyword()).isEqualTo("AI 설정");
        });
        assertThat(ServerContextAssembler.analyze("회원일련번호와 유사한 컬럼을 찾아줘").intents()).containsExactlyInAnyOrder(Intent.SIMILAR, Intent.COLUMNS);
        assertThat(ServerContextAssembler.analyze("회원일련번호와 유사한 컬럼을 찾아줘").keyword()).isEqualTo("회원일련번호");
        assertThat(ServerContextAssembler.analyze("MBR_SN 과 비슷한 컬럼").identifier()).isEqualTo("MBR_SN");
        assertThat(ServerContextAssembler.analyze("AI 설정 테이블의 변경 이력").intents()).containsExactly(Intent.HISTORY);
        assertThat(ServerContextAssembler.analyze("Which columns does the AI provider table have?").keyword()).isEqualTo("AI provider");
        assertThat(ServerContextAssembler.analyze("뭐야?").understood()).isFalse();
        assertThat(ServerContextAssembler.analyze("DROP TABLE 해줘; SELECT * FROM TB_USER").keyword()).as("SQL 은 검색어일 뿐 실행되지 않는다").isNotNull();

        TableInfo setting = tableInfoRepository.findAllByProjectIdAndTableNameIn(EXAMPLE_PROJECT, List.of("TB_AI_STNG")).getFirst();
        List<ColumnInfo> settingColumns = columnInfoRepository.findAllByTableInfo_IdIn(List.of(setting.getId()));
        ColumnInfo firstColumn = settingColumns.getFirst();
        ScriptedOllama ollama = ready(List.of("completion"), 8192);
        ollama.then(answer(MODEL, "TB_AI_STNG 의 컬럼은 " + firstColumn.getColumnName() + " 등입니다.", List.of(
                Map.of("type", "TABLE", "id", setting.getId(), "name", "TB_AI_STNG"),
                Map.of("type", "COLUMN", "id", firstColumn.getId(), "name", firstColumn.getColumnName(), "tableId", setting.getId()),
                Map.of("type", "COLUMN", "id", otherProjectColumn.getId(), "name", "MBR_SN"))));
        AiMetadataQueryDto.QueryResult result = queries.query(ExecutionContext.user(principal(viewer, EXAMPLE_PROJECT)),
                new AiMetadataQueryDto.QueryRequest("AI 설정 테이블의 컬럼을 알려줘"));
        assertThat(result.mode()).isEqualTo(Mode.SERVER_CONTEXT);
        assertThat(result.fallback()).isEqualTo(FallbackReason.MODEL_CAPABILITIES);
        assertThat(result.tools()).as("관련 조회만 (관계·이력·사전 검색 없음)").extracting(MetadataToolSession.Call::name).containsExactly("searchTables", "getTableColumns");
        assertThat(ollama.chats).as("Tool 단계 없이 구조화 한 번").hasSize(1);
        JsonNode chat = JSON.readTree(ollama.chats.getFirst());
        assertThat(chat.path("tools").isMissingNode() || chat.path("tools").isEmpty()).as("모델에게 Tool 을 넘기지 않는다").isTrue();
        assertThat(chat.path("options").path("num_ctx").asInt()).as("모델 컨텍스트(8192) 이하").isEqualTo(8192);
        assertThat(ollama.chats.getFirst()).contains("TB_AI_STNG").contains(firstColumn.getColumnName()).doesNotContain("TB_MBR_B");
        assertThat(result.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::source).containsExactly(
                tuple(Status.VERIFIED, Mode.SERVER_CONTEXT), tuple(Status.VERIFIED, Mode.SERVER_CONTEXT), tuple(Status.OUT_OF_SCOPE, null));
        assertThat(result.verification().status()).isEqualTo(Overall.PARTIAL);

        // 해석할 수 없는 질문 : 조회하지 않고, 조회하지 않았다고 알린다
        ollama.then(answer(MODEL, "무엇을 찾을지 알 수 없습니다."));
        AiMetadataQueryDto.QueryResult unclear = queries.query(ExecutionContext.user(principal(viewer, EXAMPLE_PROJECT)), new AiMetadataQueryDto.QueryRequest("뭐야?"));
        assertThat(unclear.tools()).isEmpty();
        assertThat(unclear.verification().status()).isEqualTo(Overall.NO_EVIDENCE);
        assertThat(ollama.chats.getLast()).contains("could not tell which metadata");

        // 유사 컬럼·이력 의도 : 서버가 새 Tool 로 조회한다 (프로젝트 C)
        ollama.then(answer(MODEL, "비슷한 컬럼입니다."));
        AiMetadataQueryDto.QueryResult similar = queries.query(ExecutionContext.user(principal(viewer, projectC.getId())),
                new AiMetadataQueryDto.QueryRequest("MBR_SN 과 유사한 컬럼을 찾아줘"));
        assertThat(similar.tools()).extracting(MetadataToolSession.Call::name).first().isEqualTo("findSimilarColumns");
        assertThat(ollama.chats.getLast()).contains("TB_ORD").doesNotContain("TB_MBR_B");
        ollama.then(answer(MODEL, "변경 이력입니다."));
        AiMetadataQueryDto.QueryResult history = queries.query(ExecutionContext.user(principal(viewer, projectC.getId())),
                new AiMetadataQueryDto.QueryRequest("TB_ETC 테이블의 변경 이력을 알려줘"));
        assertThat(history.tools()).extracting(MetadataToolSession.Call::name).containsExactly("searchTables", "getMetadataHistory");

        // SCHEDULED 도 같은 흐름 (세션 없이 실행 컨텍스트의 프로젝트로만)
        ollama.then(answer(MODEL, "스키마 목록입니다."));
        assertThat(queries.query(ExecutionContext.scheduled(EXAMPLE_PROJECT, "nightly-ai4"), new AiMetadataQueryDto.QueryRequest("스키마 목록")).tools())
                .extracting(MetadataToolSession.Call::name).containsExactly("listSchemas");
    }

    @Test
    @DisplayName("미지원 판정·안전 : 모델 정보를 모르면 Tool Calling 을 시도하고 명시적 미지원 거절에만 서버 맥락. 일반 400·SEC-1 차단은 대체 흐름으로 우회하지 않는다. 다른 프로젝트·권한 회수·형식 오류 재요청")
    void fallbackDecisionAndSafety() throws Exception {
        ExecutionContext context = ExecutionContext.user(principal(viewer, EXAMPLE_PROJECT));
        // 모델 정보 없음(404) → Tool 을 넘긴 요청 → 서버가 미지원이라 거절 → 서버 맥락
        ScriptedOllama unknown = ready(null, null);
        unknown.show = null;
        unknown.then(400, "{\"error\":\"registry.ollama.ai/library/tiny:1b does not support tools\"}", 0).then(answer(MODEL, "없습니다."));
        AiMetadataQueryDto.QueryResult rejected = queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블"));
        assertThat(rejected.mode()).isEqualTo(Mode.SERVER_CONTEXT);
        assertThat(rejected.fallback()).isEqualTo(FallbackReason.TOOLS_REJECTED);
        assertThat(JSON.readTree(unknown.chats.getFirst()).path("tools").size()).as("처음에는 Tool 을 넘겼다").isEqualTo(MetadataToolset.NAMES.size());
        assertThat(JSON.readTree(unknown.chats.getLast()).path("tools").isMissingNode() || JSON.readTree(unknown.chats.getLast()).path("tools").isEmpty()).isTrue();

        // 일반 400 은 미지원이 아니다
        unknown.then(400, "{\"error\":\"invalid options\"}", 0);
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블"))).extracting("errorCode").isEqualTo(ErrorCode.AI_PROVIDER_CALL_FAILED);

        // capabilities 에 tools 가 있으면 Tool Calling 그대로
        ScriptedOllama tools = ready(List.of("completion", "tools"), 32768);
        tools.then(raw(MODEL, "일반 답"));
        assertThat(queries.query(context, new AiMetadataQueryDto.QueryRequest("안녕")).mode()).isEqualTo(Mode.TOOL_CALLING);

        // SEC-1 : 허용 규칙이 없는 Provider 는 모델 정보 조회부터 막히고, 대체 흐름으로 우회하지 않는다
        ScriptedOllama blocked = new ScriptedOllama();
        servers.add(blocked);
        blocked.show(List.of("completion"), 4096);
        AiProviderDto.Provider noRule = provider(blocked.url());
        defaults.set(new AiProviderDto.DefaultProviderRequest(noRule.id()));
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블"))).isInstanceOf(ExternalConnectionBlockedException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_CONNECTION_BLOCKED);
        assertThat(blocked.shows).isEmpty();
        assertThat(blocked.chats).isEmpty();

        // 다른 프로젝트 : 서버 맥락도 실행 컨텍스트의 프로젝트(B)만 — 예제 프로젝트의 테이블은 보이지 않는다
        ScriptedOllama noTools = ready(List.of("completion"), 8192);
        Account ownerB = account("ROLE_USER");
        members.add(member(projectB.getId(), ownerB));
        noTools.then(answer(MODEL, "없습니다."));
        AiMetadataQueryDto.QueryResult inB = queries.query(ExecutionContext.user(principal(ownerB, projectB.getId())), new AiMetadataQueryDto.QueryRequest("AI 설정 테이블의 컬럼"));
        assertThat(inB.mode()).isEqualTo(Mode.SERVER_CONTEXT);
        assertThat(noTools.chats.getLast()).doesNotContain("TB_AI_STNG");

        // 형식 오류 : 한 번만 다시 요청하고 그래도 틀리면 AI_STRUCTURED_OUTPUT_INVALID
        int before = noTools.chats.size();
        noTools.then(raw(MODEL, "그냥 문장")).then(raw(MODEL, "또 문장"));
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블"))).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_STRUCTURED_OUTPUT_INVALID);
        assertThat(noTools.chats.size() - before).isEqualTo(2);
        assertThat(noTools.chats.getLast()).contains("was rejected");

        // 권한 회수 : 모델이 답하는 동안 멤버에서 빠지면 근거 검증 전에 질의 전체가 실패한다
        Long membership = members.get(1);
        noTools.then(answer(MODEL, "있습니다."), () -> projectMemberRepository.deleteById(membership));
        assertThatThrownBy(() -> queries.query(context, new AiMetadataQueryDto.QueryRequest("AI 설정 테이블"))).isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    // ------------------------------------------------------------------ 준비

    private void column(String table, String name, String description, StandardTerm term, String example) {
        ColumnInfo column = columnInfoRepository.save(ColumnInfo.builder().projectId(projectC.getId()).tableInfo(tables.get(table)).tableName(table).columnName(name)
                .columnDesc(description).dataType("BIGINT").sortOrder(columns.size() + 1).standardTerms(term).example(example).isApproval(false).build());
        columns.put(table + "." + name, column);
    }

    private static TableRelation relation(TableInfo child, TableInfo parent, String name, ColumnInfo childColumn, ColumnInfo parentColumn) {
        TableRelation relation = TableRelation.builder().projectId(child.getProjectId()).sourceTable(child).targetTable(parent).relationName(name)
                .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
        relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(childColumn).targetColumn(parentColumn).columnOrder(1).build());
        return relation;
    }

    /** 질의 하나처럼 새 세션 (Tool 호출 상한은 세션마다) */
    private MetadataToolSession fresh() {
        return session(ExecutionContext.user(principal(viewer, projectC.getId())));
    }

    private JsonNode run(String tool, Map<String, Object> input, MetadataToolSession session) {
        return JSON.readTree(toolset.run(tool, input, session));
    }

    private static Map<String, JsonNode> byKey(JsonNode columns) {
        Map<String, JsonNode> found = new LinkedHashMap<>();
        columns.forEach(c -> found.put(c.path("tableName").asString() + "." + c.path("columnName").asString(), c));
        return found;
    }

    private static List<String> reasons(JsonNode column) {
        List<String> reasons = new ArrayList<>();
        column.path("reasons").forEach(r -> reasons.add(r.asString()));
        return reasons;
    }

    private static JsonNode find(JsonNode history, String key) {
        for (JsonNode h : history) {
            if (key.equals(h.path("targetType").asString() + " " + h.path("targetName").asString() + " " + h.path("action").asString())) return h;
        }
        throw new AssertionError(key + " 없음 : " + history);
    }

    private static MetadataToolSession session(ExecutionContext context) {
        return new MetadataToolSession(context, "test-" + UUID.randomUUID(), 60_000);
    }

    /** 가짜 Ollama + Provider + AI_HTTP 허용 규칙 + 시스템 기본 지정. capabilities 가 null 이면 /api/show 404 */
    private ScriptedOllama ready(List<String> capabilities, Integer contextLength) throws IOException {
        ScriptedOllama server = new ScriptedOllama();
        servers.add(server);
        if (capabilities != null) server.show(capabilities, contextLength);
        AiProviderDto.Provider provider = provider(server.url());
        createdRules.add(rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", server.port(), true, "AI-4 잔여 테스트")).id());
        defaults.set(new AiProviderDto.DefaultProviderRequest(provider.id()));
        return server;
    }

    private AiProviderDto.Provider provider(String url) {
        AiProviderDto.Provider provider = providers.create(new ProviderRequest("AI-4R " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", url, MODEL, true, null));
        createdProviders.add(provider.id());
        return provider;
    }

    private AccountDto principal(Account account, Long projectId) {
        return AccountDto.builder().id(String.valueOf(account.getId())).projectId(projectId).build();
    }

    private Account account(String role) {
        String username = "ai4r-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Project project(Account owner) {
        Project project = projectRepository.save(Project.builder().projectName("ai4r-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        return project;
    }

    private Long member(Long projectId, Account account) {
        return tx.execute(s -> projectMemberRepository.save(ProjectMember.builder().project(projectRepository.findById(projectId).orElseThrow())
                .account(account).isActive(true).build()).getId());
    }
}
