package com.lexorma;

import com.lexorma.MetadataToolIntegrationTest.ScriptedOllama;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
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
import com.lexorma.data.ai.dictionary.domain.dto.AiEvaluationDto;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.Category;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.MeaningStatus;
import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.data.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.data.ai.dictionary.service.AiDescriptionEvaluationService;
import com.lexorma.data.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 설명 품질 평가 (AI-5 후속) : 기준 데이터(평가 대상·사람이 확인한 실제 의미)와 사람의 판정을 저장하고 모델·프롬프트 버전별 지표를 낸다.
 * 여기의 판정 값은 기능 검증용 테스트 데이터다 (품질 평가 결과가 아니다). AI 서버는 가짜.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"lexorma.ai.test-timeout-seconds=1", "lexorma.external.http.response-timeout-seconds=3"})
@ActiveProfiles("test")
class AiDictionaryEvaluationIntegrationTest {

    private static final String MEANING = "고객이 실제로 결제한 금액, 부가세 포함, 원화";

    @Autowired AiDescriptionEvaluationService evaluations;
    @Autowired AiDescriptionDraftService drafts;
    @Autowired AiDescriptionReviewService reviews;
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
    @Autowired TransactionTemplate tx;

    private ScriptedOllama ollama;
    private final List<Long> providerIds = new ArrayList<>();
    private Long ruleId;
    private Account owner;
    private Account viewer;
    private Project project;
    private TableInfo table;
    private ColumnInfo amount;
    private ColumnInfo status;
    private ColumnInfo paidAt;

    @BeforeEach
    void setUp() throws IOException {
        defaults.clear();
        owner = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        project = projectRepository.save(Project.builder().projectName("ai5e-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(viewer).isActive(true).build());
        tx.executeWithoutResult(s -> {
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            table = tableRepository.save(TableInfo.builder().projectId(project.getId()).schema(schema).tableName("TB_PAY").tableKorName("결제").tableDescription("결제")
                    .isApproval(false).isMasterTable(false).build());
            amount = column("PAY_AMT", "결제 금액", "INTEGER", 1);
            status = column("PAY_STTS_CD", "결제 상태 코드", "VARCHAR", 2);
            paidAt = column("PAY_DT", "결제 일시", "TIMESTAMP", 3);
        });
        ollama = new ScriptedOllama();
        ollama.show(List.of("completion", "tools"), 32768);
        ruleId = rules.create(new RuleRequest("AI_HTTP", "127.0.0.1", "127.0.0.1/32", ollama.port(), true, "AI-5 품질 평가")).id();
        useModel("gemma3:4b");
    }

    @AfterEach
    void tearDown() throws Exception {
        defaults.clear();
        for (Long id : providerIds) providers.delete(id);
        rules.delete(ruleId);
        ollama.close();
    }

    @Test
    @DisplayName("기준 데이터 : 후보 범주(규칙), 등록·중복 건너뜀, 실제 의미 확인(확인 필수 값·버전 충돌), 권한(VIEWER 작성 불가), 다른 프로젝트 대상 없음, 판정 있는 대상 삭제 불가")
    void dataset() {
        List<AiEvaluationDto.Candidate> candidates = evaluations.candidates(user(owner), table.getId());
        assertThat(candidate(candidates, "TB_PAY.PAY_STTS_CD").categories()).contains(Category.STATUS_CODE, Category.NO_SOURCE_COMMENT, Category.NAME_ONLY);
        assertThat(candidate(candidates, "TB_PAY.PAY_AMT").categories()).contains(Category.AMOUNT);
        assertThat(candidate(candidates, "TB_PAY.PAY_DT").categories()).contains(Category.DATE_TIME);

        AiEvaluationDto.RegisterResult first = evaluations.register(user(owner), register(amount, status));
        assertThat(first).isEqualTo(new AiEvaluationDto.RegisterResult(2, 0));
        assertThat(evaluations.register(user(owner), register(amount, paidAt))).isEqualTo(new AiEvaluationDto.RegisterResult(1, 1));
        assertThat(evaluations.candidates(user(owner), table.getId())).filteredOn(AiEvaluationDto.Candidate::registered).hasSize(3);
        rejected(() -> evaluations.register(user(viewer), register(status)), null);

        AiEvaluationDto.TargetRow target = target("TB_PAY.PAY_AMT");
        assertThat(target.meaningStatus()).isEqualTo(MeaningStatus.PENDING);
        rejected(() -> evaluations.updateTarget(user(owner), target.id(), new AiEvaluationDto.TargetUpdate(target.version(), null, " ", "CONFIRMED")), ErrorCode.AI_EVALUATION_INVALID);
        AiEvaluationDto.TargetDetail confirmed = evaluations.updateTarget(user(owner), target.id(), new AiEvaluationDto.TargetUpdate(target.version(), null, MEANING, "CONFIRMED"));
        assertThat(confirmed.actualMeaning()).isEqualTo(MEANING);
        assertThat(confirmed.target().meaningStatus()).isEqualTo(MeaningStatus.CONFIRMED);
        assertThat(confirmed.meaningReviewerId()).isEqualTo(String.valueOf(owner.getUsername()));
        rejected(() -> evaluations.updateTarget(user(owner), target.id(), new AiEvaluationDto.TargetUpdate(target.version(), null, "다른 의미", "CONFIRMED")), ErrorCode.AI_EVALUATION_CONFLICT);
        rejected(() -> evaluations.updateTarget(user(viewer), target.id(), new AiEvaluationDto.TargetUpdate(confirmed.target().version(), null, "x", "CONFIRMED")), null);
        assertThat(evaluations.list(user(viewer))).hasSize(3);

        // 다른 프로젝트에서는 보이지 않는다
        Account other = account("ROLE_USER");
        Project otherProject = projectRepository.save(Project.builder().projectName("ai5e-o-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(other).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(otherProject).account(other).isActive(true).build());
        ExecutionContext outsider = ExecutionContext.user(AccountDto.builder().id(String.valueOf(other.getId())).projectId(otherProject.getId()).build());
        rejected(() -> evaluations.detail(outsider, target.id()), ErrorCode.AI_EVALUATION_NOT_FOUND);
        assertThat(evaluations.list(outsider)).isEmpty();

        // 판정이 없으면 지울 수 있고, 있으면 지울 수 없다
        evaluations.deleteTarget(user(owner), target("TB_PAY.PAY_DT").id());
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        judge(target("TB_PAY.PAY_AMT").id(), p, "CORRECT", false, "APPROPRIATE", 3, 10);
        rejected(() -> evaluations.deleteTarget(user(owner), target("TB_PAY.PAY_AMT").id()), ErrorCode.AI_EVALUATION_INVALID);
        // 정확성 판정이 있는 대상의 의미를 확인 불가로 바꿀 수 없다 (기준을 잃는다)
        AiEvaluationDto.TargetRow withJudgment = target("TB_PAY.PAY_AMT");
        rejected(() -> evaluations.updateTarget(user(owner), withJudgment.id(), new AiEvaluationDto.TargetUpdate(withJudgment.version(), null, null, "UNCONFIRMABLE")),
                ErrorCode.AI_EVALUATION_INVALID);
    }

    @Test
    @DisplayName("판정 : 실제 의미를 확인하지 않은 대상은 정확성 판정 불가(평가 불가만), 다른 대상의 제안 불가, 검토자당 하나(버전으로 고침). 실제 의미는 AI 요청에 들어가지 않는다")
    void judgments() {
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow amountTarget = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), amountTarget.id(), new AiEvaluationDto.TargetUpdate(amountTarget.version(), null, MEANING, "CONFIRMED"));
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        assertThat(ollama.chats).isNotEmpty().noneMatch(body -> body.contains(MEANING));

        AiEvaluationDto.TargetRow statusTarget = target("TB_PAY.PAY_STTS_CD");
        AiDescriptionProposal sp = propose(status, List.of(evidence(status)));
        rejected(() -> judge(statusTarget.id(), sp, "CORRECT", false, "APPROPRIATE", null, null), ErrorCode.AI_EVALUATION_INVALID);
        assertThat(judge(statusTarget.id(), sp, "NOT_EVALUABLE", false, "MISSED", null, null).judgment().name()).isEqualTo("NOT_EVALUABLE");
        rejected(() -> judge(statusTarget.id(), p, "NOT_EVALUABLE", false, "APPROPRIATE", null, null), ErrorCode.AI_EVALUATION_INVALID);

        AiEvaluationDto.JudgmentRow j = judge(amountTarget.id(), p, "PARTIAL", true, "MISSED", 4, 12);
        rejected(() -> evaluations.judge(user(owner), request(amountTarget.id(), p, null, "CORRECT")), ErrorCode.AI_EVALUATION_CONFLICT);
        AiEvaluationDto.JudgmentRow fixed = evaluations.judge(user(owner), request(amountTarget.id(), p, j.version(), "CORRECT"));
        assertThat(fixed.id()).isEqualTo(j.id());
        assertThat(fixed.judgment().name()).isEqualTo("CORRECT");
        rejected(() -> judge(amountTarget.id(), p, "WRONG_VALUE", false, "APPROPRIATE", null, null), ErrorCode.AI_EVALUATION_INVALID);
        rejected(() -> evaluations.judge(user(viewer), request(amountTarget.id(), p, null, "CORRECT")), null);
        assertThat(evaluations.detail(user(owner), amountTarget.id()).judgments()).hasSize(1);
    }

    @Test
    @DisplayName("지표 : 모델·프롬프트 버전별, 정확성 분모는 실제 의미를 확인한 대상만(평가 불가는 따로), 허구 객체는 서버 근거 검증, 같은 메타데이터 여부, 승인·수정 비율, 시간 절감. 판정이 없으면 평가 안 됨")
    void metrics() {
        evaluations.register(user(owner), register(amount, status, paidAt));
        AiEvaluationDto.Metrics empty = evaluations.metrics(user(owner));
        assertThat(empty.evaluated()).isFalse();
        assertThat(empty.models()).isEmpty();
        assertThat(empty.dataset().targets()).isEqualTo(3);
        assertThat(empty.dataset().pending()).isEqualTo(3);

        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        AiEvaluationDto.TargetRow s = target("TB_PAY.PAY_STTS_CD");
        AiEvaluationDto.TargetRow d = target("TB_PAY.PAY_DT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, MEANING, "CONFIRMED"));
        evaluations.updateTarget(user(owner), d.id(), new AiEvaluationDto.TargetUpdate(d.version(), null, "결제 승인 일시", "CONFIRMED"));
        evaluations.updateTarget(user(owner), s.id(), new AiEvaluationDto.TargetUpdate(s.version(), null, null, "UNCONFIRMABLE"));

        // 모델 A : 금액 맞음(승인), 일시 틀림 + 존재하지 않는 컬럼을 근거로 듦(반려), 상태 코드 평가 불가
        AiDescriptionProposal a1 = propose(amount, List.of(evidence(amount)));
        approve(a1);
        AiDescriptionProposal d1 = propose(paidAt, List.of(evidence(paidAt), Map.of("type", "COLUMN", "id", 987_654_321L, "name", "NO_SUCH_COL")));
        reviews.reject(user(owner), d1.getId(), new AiDictionaryDto.ReviewRequest(d1.getVersion(), null, "틀림"));
        AiDescriptionProposal s1 = propose(status, List.of(evidence(status)));
        judge(a.id(), a1, "CORRECT", false, "APPROPRIATE", 2, 10);
        judge(d.id(), d1, "INCORRECT", true, "MISSED", 5, 8);
        judge(s.id(), s1, "NOT_EVALUABLE", false, "NOT_APPLICABLE", null, null);
        // 모델 B : 금액 일부 맞음 (등록 뒤 대상이 바뀐 메타데이터로 생성)
        useModel("qwen-test:1b");
        tx.executeWithoutResult(x -> columnRepository.findById(amount.getId()).orElseThrow().setColumnDesc("결제 금액(원)"));
        AiDescriptionProposal a2 = propose(columnRepository.findById(amount.getId()).orElseThrow(), List.of(evidence(amount)));
        judge(a.id(), a2, "PARTIAL", false, "EXCESSIVE", 6, 10);

        AiEvaluationDto.Metrics m = evaluations.metrics(user(viewer));
        assertThat(m.evaluated()).isTrue();
        assertThat(m.dataset()).extracting(AiEvaluationDto.Dataset::confirmed, AiEvaluationDto.Dataset::unconfirmable, AiEvaluationDto.Dataset::pending).containsExactly(2, 1, 0);
        AiEvaluationDto.ModelResult modelA = m.models().stream().filter(x -> x.model().equals("gemma3:4b")).findFirst().orElseThrow();
        assertThat(modelA.judgments()).isEqualTo(3);
        assertThat(modelA.accuracy()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.notEvaluable()).isEqualTo(1);
        assertThat(modelA.unfounded()).isEqualTo(new AiEvaluationDto.Rate(1 / 3.0, 1, 3));
        assertThat(modelA.fabricated()).as("서버 근거 검증 : 없는 컬럼을 든 제안").isEqualTo(new AiEvaluationDto.Rate(1 / 3.0, 1, 3));
        assertThat(modelA.uncertainty()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.missedUnknown()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.approvalRate()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.timeBase()).isEqualTo(2);
        assertThat(modelA.medianReviewMinutes()).isEqualTo(3.5);
        assertThat(modelA.medianWritingMinutes()).isEqualTo(9.0);
        assertThat(modelA.sameMetadata()).isEqualTo(3);
        AiEvaluationDto.ModelResult modelB = m.models().stream().filter(x -> x.model().equals("qwen-test:1b")).findFirst().orElseThrow();
        assertThat(modelB.accuracy()).isEqualTo(new AiEvaluationDto.Rate(0.0, 0, 1));
        assertThat(modelB.partial()).isEqualTo(new AiEvaluationDto.Rate(1.0, 1, 1));
        assertThat(modelB.excessiveUnknown()).isEqualTo(new AiEvaluationDto.Rate(1.0, 1, 1));
        assertThat(modelB.sameMetadata()).as("등록 뒤 바뀐 메타데이터로 만든 제안").isZero();
        assertThat(m.note()).contains("사람의 판정");
    }

    // ------------------------------------------------------------------ 준비

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai5e-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private ColumnInfo column(String name, String desc, String type, int order) {
        return columnRepository.save(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName("TB_PAY").columnName(name).columnDesc(desc)
                .dataType(type).sortOrder(order).isApproval(false).isPk(false).isNullable(true).build());
    }

    private String model;

    private void useModel(String model) {
        this.model = model;
        Long id = providers.create(new ProviderRequest("AI-5E " + UUID.randomUUID().toString().substring(0, 8), "OLLAMA", ollama.url(), model, true, null)).id();
        providerIds.add(id);
        defaults.set(new AiProviderDto.DefaultProviderRequest(id));
    }

    private ExecutionContext user(Account account) {
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(account.getId())).projectId(project.getId()).build());
    }

    private static Map<String, Object> evidence(ColumnInfo c) {
        return Map.of("type", "COLUMN", "id", c.getId(), "name", c.getColumnName());
    }

    private AiDescriptionProposal propose(ColumnInfo c, List<Map<String, Object>> evidence) {
        // 가짜 응답의 모델 이름을 지금 기본 Provider 의 모델로 (제안은 AI 서버가 알린 모델 이름을 남긴다)
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", c.getId(), c.getColumnName() + " 설명", "INFERRED", evidence, null, null, List.of())
                .replace("\"model\":\"gemma3:4b\"", "\"model\":\"" + model + "\""));
        return drafts.generate(user(owner), table.getId(), c.getId(), true, null).proposal();
    }

    private void approve(AiDescriptionProposal p) {
        assertThat(reviews.approve(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null)).outcome()).isEqualTo("APPROVED");
    }

    private AiEvaluationDto.JudgmentRow judge(Long targetId, AiDescriptionProposal p, String judgment, boolean unfounded, String uncertainty, Integer review, Integer writing) {
        return evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(targetId, p.getId(), null, judgment, unfounded, null, uncertainty, "NO_RECOMMENDATION",
                "NO_RECOMMENDATION", null, review, writing));
    }

    private static AiEvaluationDto.JudgmentRequest request(Long targetId, AiDescriptionProposal p, Long version, String judgment) {
        return new AiEvaluationDto.JudgmentRequest(targetId, p.getId(), version, judgment, false, "통화 단위", "APPROPRIATE", "APPROPRIATE", "NOT_EVALUABLE", "부가세 언급 필요", 3, 9);
    }

    private static AiEvaluationDto.RegisterRequest register(ColumnInfo... cols) {
        return new AiEvaluationDto.RegisterRequest(java.util.Arrays.stream(cols).map(c -> new AiEvaluationDto.RegisterItem(c.getTableInfo().getId(), c.getId(), null)).toList());
    }

    private AiEvaluationDto.TargetRow target(String name) {
        return evaluations.list(user(owner)).stream().filter(t -> t.targetName().equals(name)).findFirst().orElseThrow();
    }

    private static AiEvaluationDto.Candidate candidate(List<AiEvaluationDto.Candidate> rows, String name) {
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    /** code 가 null 이면 오류 종류와 상관없이 거부만 본다 (권한) */
    private static void rejected(Executable call, ErrorCode code) {
        var thrown = assertThatThrownBy(call::execute);
        if (code != null) thrown.isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(code);
    }
}
