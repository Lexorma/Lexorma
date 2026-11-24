package com.lexorma;

import com.lexorma.ai.answer.StructuredAnswer;
import com.lexorma.ai.answer.StructuredAnswerParser;
import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.tool.LogicalMetadataDto;
import com.lexorma.ai.tool.LogicalMetadataService;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.ai.tool.MetadataToolset;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
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
 * D-35 D35-3b : 내부 AI 의 논리 모델 Tool 3개 — 실제 실행 경계({@link MetadataToolset}, Spring AI {@code FunctionToolCallback} 결과 변환)를 지난다.
 * 등록·입력 스키마, 공통 서비스와 같은 결과, 역할·멤버십·프로젝트 격리, 호출 횟수 상한, 결과 상한(RESULT_TOO_LARGE·안내), 돌려준 범위와 근거 기록 일치,
 * 근거 검증(논리 유형 7개·상속 속성/식별자·다른 프로젝트·없는 ID·돌려주지 않은 대상), 답변 스키마. MCP 는 꺼진 상태(테스트 기본)에서 돈다.
 * 실제 AI Provider 는 쓰지 않는다 (D35-FINAL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalMetadataToolIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired MetadataToolset toolset;
    @Autowired LogicalMetadataService metadata;
    @Autowired EvidenceVerifier verifier;
    @Autowired StructuredAnswerParser parser;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired Environment environment;

    private final List<String> lines = new ArrayList<>();

    @AfterEach
    void tearDown() {
        lines.forEach(System.out::println);
        SecurityTestSupport.clear();
    }

    private Long account(String role) {
        return tx.execute(s -> accounts.save(Account.builder().username("d35-" + UUID.randomUUID().toString().substring(0, 8)).name("d35").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roles.findByRoleName(role)))).projectMember(new ArrayList<>()).build())).getId();
    }

    private void member(Long project, Long account) {
        tx.executeWithoutResult(s -> members.save(ProjectMember.builder().project(projects.findById(project).orElseThrow()).account(accounts.findById(account).orElseThrow())
                .isActive(true).build()));
    }

    private static MetadataToolSession session(Long project, Long account) {
        return new MetadataToolSession(ExecutionContext.async(project, account, "d35-test"), "d35-" + UUID.randomUUID(), MetadataToolset.TIMEOUT_SECONDS * 1000L);
    }

    private JsonNode run(String tool, Map<String, Object> input, MetadataToolSession session) {
        return mapper.readTree(toolset.run(tool, input, session));
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private static StructuredAnswer.Evidence claim(EvidenceType type, Long id, String name) {
        return new StructuredAnswer.Evidence(type, id, name, null, null);
    }

    // ------------------------------------------------------------------ 등록 · 입력 스키마 · 스키마 크기

    @Test
    @DisplayName("등록 : 내부 AI Tool 10 → 13 (논리 3개 추가, 기존 이름 그대로), 입력에 프로젝트·계정 없음, 입력 칸, Tool 정의 크기 증가량")
    void registration() {
        assertThat(MetadataToolset.NAMES).hasSize(13).contains("getLogicalModelOverview", "searchLogicalEntities", "getLogicalEntityDetail", "listSchemas", "getMetadataHistory");
        Map<String, Set<String>> properties = new LinkedHashMap<>();
        int before = 0, added = 0;
        for (ToolCallback callback : toolset.callbacks()) {
            var definition = callback.getToolDefinition();
            int size = definition.name().length() + definition.description().length() + definition.inputSchema().length();
            if (definition.name().contains("Logical")) {
                added += size;
                Set<String> names = new HashSet<>();
                mapper.readTree(definition.inputSchema()).path("properties").propertyNames().forEach(names::add);
                properties.put(definition.name(), names);
                lines.add("[D35-3 측정] Tool 정의 " + definition.name() + " | chars=" + size + " | estTokens=" + TokenEstimator.estimate(definition.description() + definition.inputSchema()));
            } else {
                before += size;
            }
        }
        lines.add("[D35-3 측정] 내부 AI Tool 정의 합계 (이름+설명+입력 스키마) : 기존 10개 " + before + "자 → 13개 " + (before + added) + "자 (+" + added + ")");
        assertThat(properties.get("getLogicalModelOverview")).containsExactlyInAnyOrder("modelId", "modelName", "section", "page", "limit");
        assertThat(properties.get("searchLogicalEntities")).containsExactlyInAnyOrder("modelId", "modelName", "keyword", "subjectAreaId", "includeChildren", "unclassifiedOnly",
                "page", "limit");
        assertThat(properties.get("getLogicalEntityDetail")).containsExactlyInAnyOrder("entityId", "modelId", "modelName", "entityName", "section",
                "includeAttributeDescriptions", "subtypeGroupId", "page", "limit");
        properties.values().forEach(p -> assertThat(p).doesNotContain("projectId", "accountId", "userId"));
        // MCP 를 켜지 않은 테스트 기본 설정에서도 내부 AI Tool 이 동작한다 (아래 테스트 모두 MCP 꺼짐)
        assertThat(environment.getProperty("lexorma.mcp.enabled", Boolean.class, false)).isFalse();
    }

    // ------------------------------------------------------------------ 실행 경계 : 공통 서비스와 같은 결과 · 실제 결과 크기

    @Test
    @DisplayName("실행 : 세 Tool 의 실제 결과 문자열 = 공통 서비스 결과 (같은 구현), 결과 크기(문자·UTF-8)·추정 토큰, 상속 속성·상속 주 식별자 포함")
    void sameResultAsService() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-tool", true);
        Long admin = Long.valueOf(x.f.user.getId());
        SecurityTestSupport.clear(); // 내부 AI 실행 경계는 로그인 세션을 쓰지 않는다 (실행 컨텍스트만)
        MetadataToolSession session = session(x.project(), admin);
        record Case(String label, String tool, Map<String, Object> input, Object expected) {
        }
        List<Case> cases = List.of(
                new Case("모델 목록", "getLogicalModelOverview", args(), metadata.models(x.project(), null, null)),
                new Case("모델 개요", "getLogicalModelOverview", args("modelId", x.f.modelId), metadata.overview(x.project(), new LogicalMetadataDto.ModelRef(x.f.modelId, null))),
                new Case("엔터티 검색 (영역 하위 포함)", "searchLogicalEntities", args("modelId", x.f.modelId, "subjectAreaId", x.top),
                        metadata.searchEntities(x.project(), new LogicalMetadataDto.EntitySearch(x.f.modelId, null, null, x.top, null, null, null, null))),
                new Case("상세 기본 (3단계 서브타입)", "getLogicalEntityDetail", args("entityId", x.person), metadata.entityDetail(x.project(), new LogicalMetadataDto.EntityRef(x.person, null, null, null))),
                new Case("상세 기본 (대형 말단, 속성 100 표시)", "getLogicalEntityDetail", args("entityId", x.bigLeaf),
                        metadata.entityDetail(x.project(), new LogicalMetadataDto.EntityRef(x.bigLeaf, null, null, null))),
                new Case("ATTRIBUTES + 설명 limit 20", "getLogicalEntityDetail", args("entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "limit", 20),
                        metadata.entityDetail(x.project(), new LogicalMetadataDto.EntityRef(x.bigLeaf, null, null, null, "ATTRIBUTES", true, null, null, 20))));
        for (Case c : cases) {
            String result = toolset.run(c.tool(), c.input(), session);
            assertThat(mapper.readTree(result)).as(c.label()).isEqualTo(mapper.readTree(mapper.writeValueAsString(c.expected())));
            assertThat(result.length()).as(c.label()).isLessThanOrEqualTo(24_000);
            lines.add("[D35-3 측정] 내부 AI Tool 결과 " + c.label() + " | chars=" + result.length() + " | dtoChars=" + mapper.writeValueAsString(c.expected()).length()
                    + " | utf8Bytes=" + result.getBytes(StandardCharsets.UTF_8).length + " | estTokens=" + TokenEstimator.estimate(result));
        }
        assertThat(session.calls()).extracting(MetadataToolSession.Call::status).containsOnly(MetadataToolSession.Status.OK);
        JsonNode person = run("getLogicalEntityDetail", args("entityId", x.person), session(x.project(), admin));
        List<String> names = new ArrayList<>();
        person.path("attributes").forEach(a -> names.add(a.path("name").asString()));
        assertThat(names).containsExactly("당사자번호", "당사자구분", "등록일자", "고객등급", "고객이메일", "생년월일");
        assertThat(person.path("identifiers").get(0).path("kind").asString()).isEqualTo("INHERITED_PRIMARY");
        assertThat(person.path("identifiers").get(0).path("definedEntityId").asLong()).isEqualTo(x.party);
    }

    // ------------------------------------------------------------------ 결과 상한

    @Test
    @DisplayName("결과 상한 : 설명 포함 ALL(약 5만 자)은 RESULT_TOO_LARGE + 대체 안내, 근거 기록 없음 — 안내대로 ATTRIBUTES 페이지로 105개 모두 받는다 (서버가 이어 읽지 않음)")
    void resultTooLarge() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-large", true);
        Long admin = Long.valueOf(x.f.user.getId());
        MetadataToolSession session = session(x.project(), admin);
        String raw = toolset.run("getLogicalEntityDetail", args("entityId", x.bigLeaf, "includeAttributeDescriptions", true), session);
        JsonNode error = mapper.readTree(raw);
        assertThat(error.path("error").asString()).isEqualTo("RESULT_TOO_LARGE");
        assertThat(error.path("tool").asString()).isEqualTo("getLogicalEntityDetail");
        assertThat(error.path("resultChars").asInt()).isGreaterThan(24_000);
        assertThat(error.path("hint").asString()).contains("section", "ATTRIBUTES", "limit", "includeAttributeDescriptions");
        assertThat(raw.length()).isLessThan(1_000);
        lines.add("[D35-3 측정] RESULT_TOO_LARGE 오류 | chars=" + raw.length() + " | 원래 결과 chars=" + error.path("resultChars").asInt());
        assertThat(session.calls()).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(MetadataToolSession.Status.FAILED));
        // 돌려주지 않은 결과의 대상은 근거가 아니다
        assertThat(session.seen(EvidenceType.LOGICAL_ENTITY, x.bigLeaf)).isNull();
        assertThat(session.seen(EvidenceType.LOGICAL_ATTRIBUTE, x.bigLeafAttributes.getFirst())).isNull();
        // 안내대로 : ATTRIBUTES + 설명 limit 20 → next 를 따라 6번 (질의당 8번 안), 순서 그대로 105개
        List<Long> collected = new ArrayList<>();
        Map<String, Object> next = args("entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "limit", 20);
        while (next != null) {
            JsonNode part = run("getLogicalEntityDetail", next, session);
            assertThat(part.has("error")).isFalse();
            part.path("attributes").forEach(a -> collected.add(a.path("attributeId").asLong()));
            JsonNode n = part.path("attributesPaging").path("next");
            next = n.isMissingNode() ? null : args("entityId", n.path("entityId").asLong(), "section", n.path("section").asString(), "includeAttributeDescriptions",
                    n.path("includeAttributeDescriptions").asBoolean(), "page", n.path("page").asInt(), "limit", n.path("limit").asInt());
        }
        assertThat(collected).containsExactlyElementsOf(x.bigLeafAttributes);
        assertThat(session.calls()).hasSize(7);
        // 질의당 8번 상한 : 8번째까지 되고 9번째는 거절 (기존 상한 그대로 — 서버가 대신 반복하지 않는다)
        run("getLogicalModelOverview", args(), session);
        assertThatThrownBy(() -> run("getLogicalModelOverview", args(), session)).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TOOL_CALL_LIMIT_EXCEEDED));
    }

    // ------------------------------------------------------------------ 권한 · 격리

    @Test
    @DisplayName("권한·격리 : VIEWER·USER 멤버·프로젝트 관리자·ADMIN 은 조회, 비멤버·승인 대기(NOT_APPROVE)·멤버십 해제는 거절, 다른 프로젝트 모델·엔터티·영역·그룹·이름은 '없음'")
    void authorization() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-auth", false);
        LogicalD35Fixture other = LogicalD35Fixture.create(context, "d35-auth-other", false);
        Long viewer = account("ROLE_VIEWER");
        Long user = account("ROLE_USER");
        Long manager = account("ROLE_USER");
        Long stranger = account("ROLE_VIEWER");
        Long pending = account("ROLE_NOT_APPROVE");
        member(x.project(), viewer);
        member(x.project(), user);
        member(x.project(), manager);
        member(x.project(), pending);
        jdbc.update("update TB_PRJ set MNGR_ACNT_SN = ? where PRJ_SN = ?", manager, x.project());
        Long admin = Long.valueOf(x.f.user.getId());
        for (Long account : List.of(viewer, user, manager, admin)) {
            JsonNode detail = run("getLogicalEntityDetail", args("entityId", x.person), session(x.project(), account));
            assertThat(detail.path("entityId").asLong()).isEqualTo(x.person);
        }
        for (Long account : List.of(stranger, pending)) {
            assertThatThrownBy(() -> run("getLogicalEntityDetail", args("entityId", x.person), session(x.project(), account))).isInstanceOf(CustomException.class);
        }
        // 멤버십 해제 뒤 다음 호출부터 거절 (호출마다 재검증)
        MetadataToolSession live = session(x.project(), viewer);
        assertThat(run("getLogicalModelOverview", args(), live).has("models")).isTrue();
        jdbc.update("update TB_PRJ_MBR set ACTVTN_YN = false where PRJ_SN = ? and ACNT_SN = ?", x.project(), viewer);
        assertThatThrownBy(() -> run("getLogicalModelOverview", args(), live)).isInstanceOf(CustomException.class);

        // 다른 프로젝트 대상 : 오류 결과(NOT_FOUND) — 내용·존재를 드러내지 않는다
        MetadataToolSession s = session(x.project(), admin);
        String otherModelName = other.f.user.getProjectId() == null ? "" : jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, other.f.modelId);
        for (Map<String, Object> call : List.of(args("entityId", other.person), args("modelId", other.f.modelId, "entityName", "개인고객"))) {
            assertThat(run("getLogicalEntityDetail", call, s).path("error").asString()).isEqualTo("NOT_FOUND");
        }
        assertThat(run("getLogicalModelOverview", args("modelId", other.f.modelId), s).path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(run("getLogicalModelOverview", args("modelName", otherModelName), s).path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(run("searchLogicalEntities", args("modelId", x.f.modelId, "subjectAreaId", other.top), s).path("error").asString()).isEqualTo("NOT_FOUND");
        assertThat(run("getLogicalEntityDetail", args("entityId", x.party, "section", "INHERITANCE", "subtypeGroupId", other.partyGroup), s).path("error").asString())
                .isEqualTo("NOT_FOUND");
        // 잘못된 입력 : INVALID_ARGUMENT (실행 기록에도 그렇게). 질의당 8번 상한이 있어 세션을 새로 연다
        assertThat(s.calls()).extracting(MetadataToolSession.Call::status).contains(MetadataToolSession.Status.NOT_FOUND);
        s = session(x.project(), admin);
        assertThat(run("getLogicalEntityDetail", args("entityId", x.person, "section", "COLUMNS"), s).path("error").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(run("getLogicalEntityDetail", args("entityId", x.person, "entityName", "법인고객"), s).path("error").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(run("getLogicalModelOverview", args("section", "SUBJECT_AREAS"), s).path("error").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(s.calls()).extracting(MetadataToolSession.Call::status).containsOnly(MetadataToolSession.Status.INVALID_ARGUMENT);
        assertThat(s.seen(EvidenceType.LOGICAL_ENTITY, other.person)).isNull();
    }

    // ------------------------------------------------------------------ 근거

    @Test
    @DisplayName("근거 : 논리 7종이 실제로 돌려준 것만 VERIFIED (상속 속성·상속 주 식별자는 원본 행 — 정의 엔터티 소속), 다음 페이지·다른 프로젝트·없는 ID 는 거절, 물리 근거 그대로")
    void evidence() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-evidence", true);
        LogicalD35Fixture other = LogicalD35Fixture.create(context, "d35-evidence-other", false);
        Long admin = Long.valueOf(x.f.user.getId());
        MetadataToolSession session = session(x.project(), admin);
        JsonNode overview = run("getLogicalModelOverview", args("modelId", x.f.modelId), session);
        JsonNode person = run("getLogicalEntityDetail", args("entityId", x.person), session);
        JsonNode big = run("getLogicalEntityDetail", args("entityId", x.bigLeaf), session); // 속성 100 / 105
        assertThat(big.path("attributes")).hasSize(100);
        String modelName = overview.path("modelName").asString();
        Long notReturned = x.bigLeafAttributes.get(104);
        List<StructuredAnswer.Evidence> claims = List.of(
                claim(EvidenceType.LOGICAL_MODEL, x.f.modelId, modelName),
                claim(EvidenceType.SUBJECT_AREA, x.mid, "개인 고객 관리"),
                claim(EvidenceType.LOGICAL_ENTITY, x.person, "개인고객"),
                claim(EvidenceType.LOGICAL_ENTITY, x.party, "당사자"),                       // 상속 경로에서 돌려준 조상
                claim(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호"),              // 개인고객에서 본 상속 속성 = 당사자의 원본 행
                claim(EvidenceType.LOGICAL_ATTRIBUTE, x.birth, "생년월일"),                  // 직접 정의 속성
                claim(EvidenceType.LOGICAL_IDENTIFIER, x.partyPk, "당사자 PK"),              // 상속 주 식별자
                claim(EvidenceType.LOGICAL_RELATIONSHIP, x.relationship, "고객 주문"),        // 상속 관계
                claim(EvidenceType.SUBTYPE_GROUP, x.customerGroup, "고객 유형"),
                claim(EvidenceType.LOGICAL_ATTRIBUTE, notReturned, "말단속성34"),            // 다음 페이지 (돌려주지 않음)
                claim(EvidenceType.LOGICAL_ENTITY, other.person, "개인고객"),                // 다른 프로젝트
                claim(EvidenceType.LOGICAL_ATTRIBUTE, 999_999_999L, "없는 속성"),            // 없는 ID
                claim(EvidenceType.LOGICAL_ATTRIBUTE, x.bizNo, "사업자번호"),                // 이 질의에서 조회하지 않은 엔터티의 속성
                claim(EvidenceType.LOGICAL_ENTITY, x.person, "법인고객"));                    // 이름이 다르다
        EvidenceVerifier.Verification result = verifier.verify(session, claims);
        Map<String, EvidenceVerifier.Status> statuses = new LinkedHashMap<>();
        result.evidence().forEach(r -> statuses.put(r.type() + ":" + r.id() + ":" + r.name(), r.status()));
        assertThat(result.evidence()).extracting(EvidenceVerifier.Result::status).containsExactly(
                EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED,
                EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.VERIFIED,
                EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Status.OUT_OF_SCOPE, EvidenceVerifier.Status.NOT_FOUND,
                EvidenceVerifier.Status.UNVERIFIABLE);
        // 같은 대상을 다른 이름으로 들면 처음 것만 본다 (기존 규칙) — 이름 불일치는 따로 확인
        EvidenceVerifier.Verification mismatch = verifier.verify(session, List.of(claim(EvidenceType.LOGICAL_ENTITY, x.person, "법인고객")));
        assertThat(mismatch.evidence()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(EvidenceVerifier.Status.MISMATCH));
        // 상속 속성의 근거 기록 : 조회한 서브타입이 아니라 정의한 엔터티 소속 (서브타입 직접 정의로 기록하지 않는다)
        assertThat(session.seen(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo).parentId()).isEqualTo(x.party);
        assertThat(session.seen(EvidenceType.LOGICAL_IDENTIFIER, x.partyPk).parentId()).isEqualTo(x.party);
        // 조회 뒤 수정 → STALE
        jdbc.update("update TB_LGCL_ATRB set MDFCN_DT = MDFCN_DT + interval '1' second where LGCL_ATRB_SN = ?", x.birth);
        assertThat(verifier.verify(session, List.of(claim(EvidenceType.LOGICAL_ATTRIBUTE, x.birth, "생년월일"))).evidence().getFirst().status())
                .isEqualTo(EvidenceVerifier.Status.STALE);
        // 물리 근거는 예전 그대로 (예제 프로젝트 0 의 테이블)
        MetadataToolSession physical = session(0L, admin);
        JsonNode tables = run("searchTables", args("keyword", "TB_LGCL_ENTT"), physical);
        long tableId = tables.path("tables").get(0).path("tableId").asLong();
        assertThat(verifier.verify(physical, List.of(new StructuredAnswer.Evidence(EvidenceType.TABLE, tableId, "TB_LGCL_ENTT", null, null))).evidence().getFirst().status())
                .isEqualTo(EvidenceVerifier.Status.VERIFIED);
    }

    // ------------------------------------------------------------------ D35-FINAL

    @Test
    @DisplayName("D35-FINAL 설명 근거 기록 : 설명 제외 조회는 기록 없음 → 설명 포함 조회로 누적(등록 여부) → 실패한 호출이 앞의 기록을 지우지 않음 → 버전이 바뀌면 그 기록으로 인정하지 않음")
    void descriptionRecords() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-final-desc", true);
        Long admin = Long.valueOf(x.f.user.getId());
        MetadataToolSession session = session(x.project(), admin);
        run("getLogicalEntityDetail", args("entityId", x.party, "section", "ATTRIBUTES"), session);
        assertThat(session.seen(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo)).isNotNull();
        assertThat(session.described(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo)).as("설명을 뺀 응답은 설명 조회가 아니다").isNull();
        run("getLogicalEntityDetail", args("entityId", x.party, "section", "ATTRIBUTES", "includeAttributeDescriptions", true), session);
        assertThat(session.described(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo).registered()).isTrue();
        assertThat(session.described(EvidenceType.LOGICAL_ATTRIBUTE, x.partyKind).registered()).as("설명 없음을 확인한 것").isFalse();
        // 실패한 호출(설명 포함 ALL — 상한 초과)은 앞의 기록을 지우지 않고, 자기 기록도 남기지 않는다
        assertThat(run("getLogicalEntityDetail", args("entityId", x.bigLeaf, "includeAttributeDescriptions", true), session).path("error").asString()).isEqualTo("RESULT_TOO_LARGE");
        assertThat(session.described(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo)).isNotNull();
        assertThat(session.described(EvidenceType.LOGICAL_ATTRIBUTE, x.bigLeafAttributes.getFirst())).isNull();
        assertThat(session.seen(EvidenceType.LOGICAL_ENTITY, x.bigLeaf)).isNull();
        // 같은 대상이 성공·실패 두 호출에 모두 있어도 앞의 성공 근거는 남는다 (모델 — 첫 호출에 있었다)
        assertThat(session.seen(EvidenceType.LOGICAL_MODEL, x.f.modelId)).isNotNull();
        // 일반 오류(잘못된 입력·없음)도 근거를 남기지 않는다
        run("getLogicalEntityDetail", args("entityId", x.person, "section", "NOPE"), session);
        run("getLogicalEntityDetail", args("entityId", -5L), session);
        assertThat(session.seen(EvidenceType.LOGICAL_ENTITY, x.person)).isNull();
        // 근거 검증 : 설명 사용 표시
        EvidenceVerifier.Verification v = verifier.verify(session, List.of(
                new StructuredAnswer.Evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", null, null, true),
                new StructuredAnswer.Evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyKind, "당사자구분", null, null, true),
                new StructuredAnswer.Evidence(EvidenceType.LOGICAL_ENTITY, x.party, "당사자", null, null, true)));
        assertThat(v.evidence()).extracting(EvidenceVerifier.Result::status, EvidenceVerifier.Result::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.VERIFIED, null),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.MISMATCH, EvidenceVerifier.Reason.DESCRIPTION_NOT_REGISTERED),
                org.assertj.core.groups.Tuple.tuple(EvidenceVerifier.Status.UNVERIFIABLE, EvidenceVerifier.Reason.DESCRIPTION_NOT_RETRIEVED)); // 설명 사용 표시는 속성만 확인할 수 있다 — 그 밖은 확인 불가
        // 버전 : 설명을 본 뒤 속성이 바뀌면 STALE (예전 버전의 설명 기록으로 인정하지 않는다)
        jdbc.update("update TB_LGCL_ATRB set MDFCN_DT = MDFCN_DT + interval '1' second where LGCL_ATRB_SN = ?", x.partyNo);
        assertThat(verifier.verify(session, List.of(new StructuredAnswer.Evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", null, null, true)))
                .evidence().getFirst().status()).isEqualTo(EvidenceVerifier.Status.STALE);
    }

    @Test
    @DisplayName("D35-FINAL 참조 근거 : IDENTIFIERS·RELATIONS 응답에 실린 정의 엔터티·구성 속성·관계 양 끝·상속 출처·속성 짝도 근거로 인정 (실제 모델 검증에서 찾은 빈틈)")
    void referencedEvidence() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-final-ref", false);
        Long admin = Long.valueOf(x.f.user.getId());
        MetadataToolSession session = session(x.project(), admin);
        run("getLogicalEntityDetail", args("entityId", x.person, "section", "IDENTIFIERS"), session);
        run("getLogicalEntityDetail", args("entityId", x.customer, "section", "RELATIONS"), session);
        EvidenceVerifier.Verification v = verifier.verify(session, List.of(
                claim(EvidenceType.LOGICAL_ENTITY, x.party, "당사자"),            // 상속 주 식별자의 정의 엔터티
                claim(EvidenceType.LOGICAL_ATTRIBUTE, x.partyKind, "당사자구분"), // 식별자 구성 속성
                claim(EvidenceType.LOGICAL_ENTITY, x.order, "주문"),              // 관계 도착 엔터티
                claim(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호")));  // 관계 속성 짝의 부모 속성
        assertThat(v.evidence()).extracting(EvidenceVerifier.Result::status).containsOnly(EvidenceVerifier.Status.VERIFIED);
        assertThat(session.described(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo)).as("참조로 실린 속성은 설명 조회가 아니다").isNull();
    }

    @Test
    @DisplayName("D35-FINAL 상속 문맥 : 상속 그룹이 바뀌어도 속성 행의 버전은 그대로라 속성 근거는 VERIFIED (상속 문맥 변경은 개별 근거로 검출하지 않는다 — 한계 확인)")
    void inheritanceContextVersion() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-final-ctx", false);
        Long admin = Long.valueOf(x.f.user.getId());
        MetadataToolSession session = session(x.project(), admin);
        run("getLogicalEntityDetail", args("entityId", x.corp), session); // 법인고객 : 고객 → 당사자 상속
        // 그룹에서 법인고객을 빼기 위해 그룹을 지운다 (구성원 2개 이상 규칙) — 상속 문맥 변경
        var group = context.getBean(com.lexorma.data.logical.service.LogicalSubtypeService.class);
        SecurityTestSupport.loginAs(projects, x.project(), x.f.user.getId(), "ROLE_ADMIN");
        group.deleteGroup(x.f.user, x.customerGroup, group.selectGroup(x.f.user, x.customerGroup).modifiedAt());
        SecurityTestSupport.clear();
        EvidenceVerifier.Verification v = verifier.verify(session, List.of(
                new StructuredAnswer.Evidence(EvidenceType.LOGICAL_ATTRIBUTE, x.partyNo, "당사자번호", null, null, null),
                new StructuredAnswer.Evidence(EvidenceType.SUBTYPE_GROUP, x.customerGroup, "고객 유형", null, null, null),
                new StructuredAnswer.Evidence(EvidenceType.LOGICAL_ENTITY, x.corp, "법인고객", null, null, null)));
        assertThat(v.evidence()).extracting(EvidenceVerifier.Result::status).containsExactly(EvidenceVerifier.Status.VERIFIED, EvidenceVerifier.Status.STALE,
                EvidenceVerifier.Status.VERIFIED);
        lines.add("[D35-FINAL 확인] 그룹 삭제 뒤 : 속성 근거 VERIFIED(행 버전 그대로) · 그룹 근거 STALE(DELETED) · 엔터티 근거 VERIFIED — 상속 문맥 변경은 그룹 근거만 드러낸다");
    }

    @Test
    @DisplayName("D35-FINAL 크기 : 설명 300자 주제 영역 130개 개요 ALL → RESULT_TOO_LARGE, SUBJECT_AREAS limit 로 줄여 next 로 모두. 설명 포함 ATTRIBUTES 의 limit 별 실제 결과 크기")
    void largeSizes() {
        LogicalD35Fixture x = LogicalD35Fixture.create(context, "d35-final-size", true);
        Long admin = Long.valueOf(x.f.user.getId());
        var areas = context.getBean(com.lexorma.data.logical.service.LogicalSubjectAreaService.class);
        SecurityTestSupport.loginAs(projects, x.project(), x.f.user.getId(), "ROLE_ADMIN");
        String text = "가".repeat(1000); // 영역 설명 최대 길이 (Tool 은 300자로 자른다)
        for (int i = 0; i < 10; i++) {
            long top = areas.save(x.f.user, new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Save(null, x.f.modelId, null, "긴영역" + i, text, null, null)).id();
            for (int j = 0; j < 3; j++) {
                long mid = areas.save(x.f.user, new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Save(null, x.f.modelId, top, "긴영역" + i + "-" + j, text, null, null)).id();
                for (int k = 0; k < 3; k++) areas.save(x.f.user, new com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Save(null, x.f.modelId, mid, "긴영역" + i + "-" + j + "-" + k, text, null, null));
            }
        }
        SecurityTestSupport.clear();
        MetadataToolSession session = session(x.project(), admin);
        String all = toolset.run("getLogicalModelOverview", args("modelId", x.f.modelId), session);
        JsonNode allNode = mapper.readTree(all);
        lines.add("[D35-FINAL 측정] 개요 ALL (영역 133 · 설명 300자) | " + (allNode.has("error") ? allNode.path("error").asString() + " resultChars=" + allNode.path("resultChars").asInt() : "chars=" + all.length()));
        assertThat(allNode.path("error").asString()).isEqualTo("RESULT_TOO_LARGE");
        assertThat(allNode.path("hint").asString()).contains("SUBJECT_AREAS");
        for (int limit : new int[]{20, 40, 60, 100}) {
            String part = toolset.run("getLogicalModelOverview", args("modelId", x.f.modelId, "section", "SUBJECT_AREAS", "limit", limit), session(x.project(), admin));
            JsonNode node = mapper.readTree(part);
            lines.add("[D35-FINAL 측정] 개요 SUBJECT_AREAS limit " + limit + " | " + (node.has("error") ? "RESULT_TOO_LARGE resultChars=" + node.path("resultChars").asInt() : "chars=" + part.length()));
        }
        // limit 40 으로 next 를 따라 133개 모두 (같은 Tool 4번 안)
        List<Long> ids = new ArrayList<>();
        Map<String, Object> next = args("modelId", x.f.modelId, "section", "SUBJECT_AREAS", "limit", 40);
        int calls = 0;
        while (next != null) {
            JsonNode part = run("getLogicalModelOverview", next, session);
            calls++;
            assertThat(part.has("error")).isFalse();
            part.path("subjectAreas").forEach(a -> ids.add(a.path("subjectAreaId").asLong()));
            JsonNode n = part.path("subjectAreasPaging").path("next");
            next = n.isMissingNode() ? null : args("modelId", n.path("modelId").asLong(), "section", n.path("section").asString(), "page", n.path("page").asInt(), "limit",
                    n.path("limit").asInt());
        }
        assertThat(ids).hasSize(133).doesNotHaveDuplicates();
        assertThat(calls).isLessThanOrEqualTo(4);
        for (int limit : new int[]{20, 40, 45, 46, 50}) {
            String part = toolset.run("getLogicalEntityDetail", args("entityId", x.bigLeaf, "section", "ATTRIBUTES", "includeAttributeDescriptions", true, "limit", limit),
                    session(x.project(), admin));
            JsonNode node = mapper.readTree(part);
            lines.add("[D35-FINAL 측정] 설명 포함 ATTRIBUTES limit " + limit + " | " + (node.has("error") ? "RESULT_TOO_LARGE resultChars=" + node.path("resultChars").asInt()
                    : "chars=" + part.length() + " estTokens=" + TokenEstimator.estimate(part)));
        }
    }

    @Test
    @DisplayName("D35-FINAL RESULT_TOO_LARGE 호환 : 기존 물리 Tool(getTableColumns, 컬럼 100 × 긴 설명)도 같은 오류 코드·메시지 + tool·resultChars·maxChars (hint 는 논리 Tool 만), 결과를 자르지 않는다")
    void physicalTooLarge() {
        GenerationFixture f = GenerationFixture.create(context, "d35-final-phys-" + System.nanoTime(), com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        f.member();
        long schema = 970_000_000L + f.user.getProjectId();
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'public', true)", schema, f.user.getProjectId());
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, TBL_KORN_NM, MSTR_TBL_YN, APRV_YN, SORT_ORD) values (?, ?, ?, 'TB_BIG', '큰 테이블', false, false, 1)",
                schema, f.user.getProjectId(), schema);
        for (int i = 0; i < 100; i++) {
            jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, COL_NM, COL_EXPLN, TASK_EXPLN, DATA_TYPE_NM, SORT_ORD, APRV_YN) values (?, ?, ?, ?, ?, ?, 'VARCHAR', ?, false)",
                    schema * 1000 + i, f.user.getProjectId(), schema, "COL_" + i, "가".repeat(250), "나".repeat(300), i);
        }
        MetadataToolSession session = session(f.user.getProjectId(), Long.valueOf(f.user.getId()));
        JsonNode error = run("getTableColumns", args("tableId", schema), session);
        lines.add("[D35-FINAL 측정] 물리 getTableColumns (컬럼 100 × 설명 250자 + 업무 설명 300자) | " + error);
        assertThat(error.path("error").asString()).isEqualTo("RESULT_TOO_LARGE");
        assertThat(error.path("message").asString()).isNotBlank();
        assertThat(error.path("tool").asString()).isEqualTo("getTableColumns");
        assertThat(error.path("resultChars").asInt()).isGreaterThan(24_000);
        assertThat(error.path("maxChars").asInt()).isEqualTo(24_000);
        assertThat(error.has("hint")).isFalse();
        assertThat(session.seen(EvidenceType.TABLE, schema)).isNull();
    }

    @Test
    @DisplayName("D35-FINAL 토큰 구성 : 시스템 지시·Tool 정의 13개·구조화 지시+답변 스키마의 크기와 추정 토큰 (기본 예산 컨텍스트 16,384 / 출력 4,096 / 입력 12,288)")
    void tokenComponents() throws Exception {
        java.lang.reflect.Method system = com.lexorma.ai.service.AiMetadataQueryService.class.getDeclaredMethod("systemPrompt", String.class);
        java.lang.reflect.Method structuring = com.lexorma.ai.service.AiMetadataQueryService.class.getDeclaredMethod("structuringPrompt", String.class);
        system.setAccessible(true);
        structuring.setAccessible(true);
        String systemText = (String) system.invoke(null, "Korean");
        String structuringText = structuring.invoke(null, "Korean") + "\n\n" + parser.format();
        int tools = 0;
        int toolTokens = 0;
        for (ToolCallback callback : toolset.callbacks()) {
            var d = callback.getToolDefinition();
            tools += d.name().length() + d.description().length() + d.inputSchema().length();
            toolTokens += 8 + TokenEstimator.estimate(d.name()) + TokenEstimator.estimate(d.description()) + TokenEstimator.estimate(d.inputSchema());
        }
        lines.add("[D35-FINAL 측정] Tool 단계 시스템 지시 | chars=" + systemText.length() + " | estTokens=" + TokenEstimator.estimate(systemText));
        lines.add("[D35-FINAL 측정] Tool 정의 13개 | chars=" + tools + " | estTokens(TokenMeter 규칙)=" + toolTokens);
        lines.add("[D35-FINAL 측정] 구조화 지시 + 답변 형식(스키마) | chars=" + structuringText.length() + " | estTokens=" + TokenEstimator.estimate(structuringText));
        lines.add("[D35-FINAL 측정] Tool 단계 첫 요청의 고정 부분 (질문 제외) estTokens ≈ " + (TokenEstimator.estimate(systemText) + 8 + toolTokens) + " / 입력 상한 12,288");
        assertThat(toolset.callbacks()).hasSize(13);
    }

    @Test
    @DisplayName("답변 스키마 : 논리 근거 유형 7개를 받고, 모르는 유형은 거절 (기존 물리·사전 유형 그대로)")
    void answerSchema() {
        for (String type : List.of("LOGICAL_MODEL", "SUBJECT_AREA", "LOGICAL_ENTITY", "LOGICAL_ATTRIBUTE", "LOGICAL_IDENTIFIER", "LOGICAL_RELATIONSHIP", "SUBTYPE_GROUP",
                "TABLE", "COLUMN", "HISTORY")) {
            StructuredAnswerParser.Result r = parser.parse("{\"answer\":\"답\",\"evidence\":[{\"type\":\"" + type + "\",\"id\":1,\"name\":\"x\"}]}");
            assertThat(r.valid()).as(type).isTrue();
            assertThat(r.value().evidence().getFirst().type()).isEqualTo(EvidenceType.valueOf(type));
        }
        assertThat(parser.parse("{\"answer\":\"답\",\"evidence\":[{\"type\":\"ENTITY\",\"id\":1,\"name\":\"x\"}]}").valid()).isFalse();
    }
}
