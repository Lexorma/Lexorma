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
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.domain.dto.AiEvaluationDto;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluation;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.Category;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionEvaluationTarget.MeaningStatus;
import com.lexorma.ai.dictionary.domain.entity.AiDescriptionProposal;
import com.lexorma.ai.dictionary.repository.AiDescriptionEvaluationRepository;
import com.lexorma.ai.dictionary.service.AiDescriptionDraftService;
import com.lexorma.ai.dictionary.service.AiDescriptionEvaluationService;
import com.lexorma.ai.dictionary.service.AiDescriptionReviewService;
import com.lexorma.ai.dictionary.service.AiDictionaryQueryService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.byLessThan;

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
    @Autowired AiDictionaryQueryService queries;
    @Autowired AiDescriptionEvaluationRepository judgmentRepository;
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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
                ErrorCode.AI_EVALUATION_MEANING_LOCKED);
    }

    @Test
    @DisplayName("판정 : 실제 의미를 확인하지 않은 대상은 판정 불가(평가 불가 포함), 다른 대상의 제안 불가, 검토자당 하나(버전으로 고침), 화면이 본 대상 버전만. 실제 의미는 AI 요청에 들어가지 않는다")
    void judgments() {
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow amountTarget = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), amountTarget.id(), new AiEvaluationDto.TargetUpdate(amountTarget.version(), null, MEANING, "CONFIRMED"));
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        assertThat(ollama.chats).isNotEmpty().noneMatch(body -> body.contains(MEANING));

        AiEvaluationDto.TargetRow statusTarget = target("TB_PAY.PAY_STTS_CD");
        AiDescriptionProposal sp = propose(status, List.of(evidence(status)));
        rejected(() -> judge(statusTarget.id(), sp, "CORRECT", false, "APPROPRIATE", null, null), ErrorCode.AI_EVALUATION_INVALID);
        rejected(() -> judge(statusTarget.id(), sp, "NOT_EVALUABLE", false, "MISSED", null, null), ErrorCode.AI_EVALUATION_INVALID);
        evaluations.updateTarget(user(owner), statusTarget.id(), new AiEvaluationDto.TargetUpdate(statusTarget.version(), null, null, "UNCONFIRMABLE"));
        rejected(() -> judge(statusTarget.id(), sp, "NOT_EVALUABLE", false, "NOT_APPLICABLE", null, null), ErrorCode.AI_EVALUATION_INVALID);
        rejected(() -> judge(amountTarget.id(), sp, "CORRECT", false, "APPROPRIATE", null, null), ErrorCode.AI_EVALUATION_INVALID);
        // 화면이 본 대상 버전이 아니면 (그 사이 기준 데이터가 바뀜) 저장하지 않는다
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(amountTarget.id(), p.getId(), null, amountTarget.version(), "CORRECT", false, null,
                "APPROPRIATE", "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null)), ErrorCode.AI_EVALUATION_CONFLICT);
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(amountTarget.id(), p.getId(), null, null, "CORRECT", false, null,
                "APPROPRIATE", "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null)), ErrorCode.AI_EVALUATION_CONFLICT);
        // 필수 항목(정확성·불확실성·추천·근거 없는 주장)이 비면 저장하지 않는다
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(amountTarget.id(), p.getId(), null, targetVersion(amountTarget.id()), null, false, null,
                "APPROPRIATE", "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null)), ErrorCode.AI_EVALUATION_INVALID);
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(amountTarget.id(), p.getId(), null, targetVersion(amountTarget.id()), "CORRECT", false, null,
                null, "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null)), ErrorCode.AI_EVALUATION_INVALID);
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(amountTarget.id(), p.getId(), null, targetVersion(amountTarget.id()), "CORRECT", false, null,
                "APPROPRIATE", null, "NO_RECOMMENDATION", null, null, null)), ErrorCode.AI_EVALUATION_INVALID);
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(amountTarget.id(), p.getId(), null, targetVersion(amountTarget.id()), "CORRECT", null, null,
                "APPROPRIATE", "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null)), ErrorCode.AI_EVALUATION_INVALID);

        AiEvaluationDto.JudgmentRow j = judge(amountTarget.id(), p, "PARTIAL", true, "MISSED", 4, 12);
        assertThat(j.unfoundedClaim()).as("고른 값 그대로 (있음)").isTrue();
        rejected(() -> evaluations.judge(user(owner), request(amountTarget.id(), p, null, "CORRECT")), ErrorCode.AI_EVALUATION_CONFLICT);
        AiEvaluationDto.JudgmentRow fixed = evaluations.judge(user(owner), request(amountTarget.id(), p, j.version(), "CORRECT"));
        assertThat(fixed.id()).isEqualTo(j.id());
        assertThat(fixed.judgment().name()).isEqualTo("CORRECT");
        assertThat(fixed.unfoundedClaim()).as("고른 값 그대로 (없음)").isFalse();
        assertThat(evaluations.detail(user(owner), amountTarget.id()).judgments()).singleElement().satisfies(x -> assertThat(x.unfoundedClaim()).isFalse());
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

        // 모델 A : 금액 맞음(판정 뒤 승인), 일시 틀림 + 존재하지 않는 컬럼을 근거로 듦(반려) · 두 번째 검토자는 평가 불가, 상태 코드는 확인 불가라 판정 없음
        Account second = member("ROLE_USER");
        AiDescriptionProposal a1 = propose(amount, List.of(evidence(amount)));
        judge(a.id(), a1, "CORRECT", false, "APPROPRIATE", 2, 10);
        approve(a1);
        AiDescriptionProposal d1 = propose(paidAt, List.of(evidence(paidAt), Map.of("type", "COLUMN", "id", 987_654_321L, "name", "NO_SUCH_COL")));
        reviews.reject(user(owner), d1.getId(), new AiDictionaryDto.ReviewRequest(d1.getVersion(), null, "틀림"));
        AiDescriptionProposal s1 = propose(status, List.of(evidence(status)));
        judge(d.id(), d1, "INCORRECT", true, "MISSED", 5, 8);
        evaluations.judge(user(second), new AiEvaluationDto.JudgmentRequest(d.id(), d1.getId(), null, targetVersion(d.id()), "NOT_EVALUABLE", false, null, "NOT_APPLICABLE",
                "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null));
        rejected(() -> judge(s.id(), s1, "NOT_EVALUABLE", false, "NOT_APPLICABLE", null, null), ErrorCode.AI_EVALUATION_INVALID);
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
        assertThat(modelA.proposals()).isEqualTo(2);
        assertThat(modelA.reviewers()).isEqualTo(2);
        assertThat(modelA.accuracy()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.notEvaluable()).isEqualTo(1);
        assertThat(modelA.unfounded()).isEqualTo(new AiEvaluationDto.Rate(1 / 3.0, 1, 3));
        assertThat(modelA.fabricated()).as("서버 근거 검증 : 없는 컬럼을 든 제안").isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.uncertainty()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.missedUnknown()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.approvalRate()).isEqualTo(new AiEvaluationDto.Rate(0.5, 1, 2));
        assertThat(modelA.timeBase()).isEqualTo(2);
        assertThat(modelA.medianReviewMinutes()).isEqualTo(3.5);
        assertThat(modelA.medianWritingMinutes()).isEqualTo(9.0);
        // 같은 입력 조건 (DEC-29) : a1 승인이 같은 테이블(TB_PAY)의 업무 설명을 바꾼 뒤 만든 d1 의 판정 2개는 입력 맥락이 달라 빠진다 (전에는 대상 자신만 봐서 3)
        assertThat(modelA.sameMetadata()).isEqualTo(1);
        AiEvaluationDto.ModelResult modelB = m.models().stream().filter(x -> x.model().equals("qwen-test:1b")).findFirst().orElseThrow();
        assertThat(modelB.accuracy()).isEqualTo(new AiEvaluationDto.Rate(0.0, 0, 1));
        assertThat(modelB.partial()).isEqualTo(new AiEvaluationDto.Rate(1.0, 1, 1));
        assertThat(modelB.excessiveUnknown()).isEqualTo(new AiEvaluationDto.Rate(1.0, 1, 1));
        assertThat(modelB.sameMetadata()).as("등록 뒤 바뀐 메타데이터로 만든 제안").isZero();
        assertThat(m.note()).contains("사람의 판정");
    }

    @Test
    @DisplayName("판정 기준 고정 (F1) : 판정 전에는 의미를 고칠 수 있고, 판정 뒤에는 의미 문구·확인 상태를 바꿀 수 없다(같은 값 저장·범주 수정은 된다). 다른 대상은 영향 없음")
    void meaningLockedAfterJudgment() {
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        AiEvaluationDto.TargetDetail first = evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, "결제 금액", "CONFIRMED"));
        // 판정 전 : 의미를 고칠 수 있다
        AiEvaluationDto.TargetDetail edited = evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(first.target().version(), null, MEANING, "CONFIRMED"));
        assertThat(edited.actualMeaning()).isEqualTo(MEANING);

        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        judge(a.id(), p, "CORRECT", false, "APPROPRIATE", null, null);
        long version = targetVersion(a.id());
        rejected(() -> evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(version, null, "다른 의미", "CONFIRMED")), ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        rejected(() -> evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(version, null, " ", null)), ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        rejected(() -> evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(version, null, null, "PENDING")), ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        rejected(() -> evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(version, null, null, "UNCONFIRMABLE")), ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        // 같은 값(앞뒤 공백 차이 포함) 저장과 범주 수정은 된다 — 의미·확인한 사람은 그대로
        AiEvaluationDto.TargetDetail same = evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(version, List.of("AMOUNT", "CLEAR"), " " + MEANING + " ", "CONFIRMED"));
        assertThat(same.actualMeaning()).isEqualTo(MEANING);
        assertThat(same.target().categories()).containsExactlyInAnyOrder(Category.AMOUNT, Category.CLEAR);
        // 확인 시각은 그대로 : 앞 응답은 메모리 값(OS 시계 정밀도 — Linux·Windows 는 마이크로초보다 촘촘), 뒤 응답은 DB(timestamp(6))에서 읽은 값이다.
        // DB·드라이버가 마이크로초로 자르든 반올림하든 차이는 1마이크로초 미만이다
        assertThat(same.meaningReviewedAt()).isCloseTo(edited.meaningReviewedAt(), byLessThan(1, ChronoUnit.MICROS));
        assertThat(evaluations.detail(user(owner), a.id()).judgments()).singleElement().extracting(AiEvaluationDto.JudgmentRow::judgment).hasToString("CORRECT");

        // 판정이 없는 다른 대상은 그대로 고칠 수 있다
        AiEvaluationDto.TargetRow s = target("TB_PAY.PAY_STTS_CD");
        AiEvaluationDto.TargetDetail other = evaluations.updateTarget(user(owner), s.id(), new AiEvaluationDto.TargetUpdate(s.version(), null, "결제 상태", "CONFIRMED"));
        assertThat(evaluations.updateTarget(user(owner), s.id(), new AiEvaluationDto.TargetUpdate(other.target().version(), null, null, "UNCONFIRMABLE")).target().meaningStatus())
                .isEqualTo(MeaningStatus.UNCONFIRMABLE);
    }

    @Test
    @DisplayName("판정 변경 이력 (DEC-27) : 처음 판정·수정을 추가만 하는 이력에 판정자·전후 값·사유·시각으로 남긴다. 값이 바뀐 수정은 사유 필수, 같은 값은 이력 없음, 지표는 최종 판정만 센다")
    void judgmentHistory() {
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, "결제 금액", "CONFIRMED"));
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));

        AiEvaluationDto.JudgmentRow first = evaluations.judge(user(owner), request(a.id(), p, null, "CORRECT"));
        // 값이 바뀌는 수정 : 사유 없으면 거부, 판정과 이력은 그대로
        rejected(() -> evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), first.version(), targetVersion(a.id()), "INCORRECT", false,
                "통화 단위", "APPROPRIATE", "APPROPRIATE", "NOT_EVALUABLE", "부가세 언급 필요", 3, 9, "  ")), ErrorCode.AI_EVALUATION_INVALID);
        assertThat(evaluations.judgmentHistory(user(owner), first.id())).hasSize(1);
        // 같은 값으로 다시 저장 : 바뀐 것이 없어 이력·버전 그대로 (사유 없어도 됨)
        AiEvaluationDto.JudgmentRow same = evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), first.version(), targetVersion(a.id()), "CORRECT",
                false, "통화 단위", "APPROPRIATE", "APPROPRIATE", "NOT_EVALUABLE", "부가세 언급 필요", 3, 9));
        assertThat(same.version()).isEqualTo(first.version());
        AiEvaluationDto.JudgmentRow changed = evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), first.version(), targetVersion(a.id()), "INCORRECT",
                false, "통화 단위", "APPROPRIATE", "APPROPRIATE", "NOT_EVALUABLE", "부가세 언급 필요", 3, 9, "금액 단위를 다시 확인"));

        List<AiEvaluationDto.JudgmentHistoryRow> history = evaluations.judgmentHistory(user(viewer), first.id());
        assertThat(history).extracting(AiEvaluationDto.JudgmentHistoryRow::changeType).containsExactly("INSERT", "UPDATE");
        assertThat(history.get(0).before()).isNull();
        assertThat(history.get(0).after()).containsEntry("judgment", "CORRECT");
        assertThat(history.get(1)).satisfies(h -> {
            assertThat(h.before()).containsEntry("judgment", "CORRECT");
            assertThat(h.after()).containsEntry("judgment", "INCORRECT");
            assertThat(h.reason()).isEqualTo("금액 단위를 다시 확인");
            assertThat(h.reviewerId()).isEqualTo(changed.reviewerId());
            assertThat(h.changedAt()).isNotNull();
        });
        // append-only : DB 에 두 행, 판정은 한 행 — 지표는 판정(최종 값)만 센다
        assertThat(jdbc.queryForObject("select count(*) from TB_AI_EXPLN_EVL_HSTRY where AI_EXPLN_EVL_SN = ?", Integer.class, first.id())).isEqualTo(2);
        AiEvaluationDto.Metrics m = evaluations.metrics(user(owner));
        assertThat(m.models()).singleElement().satisfies(r -> assertThat(r.judgments()).isEqualTo(1));
        // 다른 프로젝트의 판정 ID 는 찾을 수 없다
        Account other = account("ROLE_USER");
        Project otherProject = projectRepository.save(Project.builder().projectName("ai5e-h-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(other).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(otherProject).account(other).isActive(true).build());
        ExecutionContext outsider = ExecutionContext.user(AccountDto.builder().id(String.valueOf(other.getId())).projectId(otherProject.getId()).build());
        rejected(() -> evaluations.judgmentHistory(outsider, first.id()), ErrorCode.AI_EVALUATION_NOT_FOUND);
    }

    @Test
    @DisplayName("평가 입력 맥락 (DEC-29 보완) : 등록 뒤 같은 테이블 컬럼에 연결된 표준 용어(정의)가 바뀐 다음 만든 제안도 '입력 맥락 변경' — 용어는 그대로면 같은 맥락")
    void termContext() {
        Long projectId = status.getProjectId();
        long termId = 880_000_000L + projectId;
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, '결제_상태_코드', '결제 상태.', 'PAY_STTS_CD', true, ?, ?, 'test', 'test')", termId, projectId,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)), java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)));
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", termId, status.getId());
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, "결제 금액", "CONFIRMED"));
        propose(amount, List.of(evidence(amount)));
        assertThat(evaluations.detail(user(owner), a.id()).proposals()).singleElement().satisfies(x -> assertThat(x.sameContext()).isTrue());

        // 같은 테이블 다른 컬럼(PAY_STTS_CD)의 표준 용어 정의를 고친다 — 모델 변경 이력은 없지만 AI 입력(용어 정의)이 바뀐다
        jdbc.update("update TB_STD_TRM set TRM_EXPLN = '결제 상태 (평가 중 수정).', MDFCN_DT = ? where STD_TRM_SN = ?", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()), termId);
        AiDescriptionProposal after = propose(amount, List.of(evidence(amount)));
        assertThat(evaluations.detail(user(owner), a.id()).proposals()).filteredOn(x -> x.id().equals(after.getId())).singleElement()
                .satisfies(x -> {
                    assertThat(x.sameMetadata()).isTrue();
                    assertThat(x.sameContext()).isFalse();
                });
        jdbc.update("update TB_COL set STD_TRM_SN = null where COL_SN = ?", status.getId());
        jdbc.update("delete from TB_STD_TRM where STD_TRM_SN = ?", termId);
    }

    @Test
    @DisplayName("평가 입력 맥락 (DEC-29) : 등록 뒤 같은 테이블의 다른 컬럼(업무 설명 포함)이 바뀐 다음 만든 제안은 '입력 맥락 변경' — 같은 입력 조건 판정 수에서 빠진다. 편집 자체는 막지 않는다")
    void inputContext() {
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, "결제 금액", "CONFIRMED"));
        AiDescriptionProposal unchanged = propose(amount, List.of(evidence(amount)));
        assertThat(evaluations.detail(user(owner), a.id()).proposals()).singleElement().satisfies(x -> assertThat(x.sameContext()).isTrue());

        // 같은 테이블의 다른 컬럼 업무 설명을 고친다 (평가 기간 운영 규칙 위반을 기능이 잡는다) — 편집은 그대로 된다
        tx.executeWithoutResult(x -> columnRepository.findById(status.getId()).orElseThrow().setBizDescription("결제 상태 (평가 중 수정)"));
        AiDescriptionProposal changed = propose(amount, List.of(evidence(amount)));
        assertThat(evaluations.detail(user(owner), a.id()).proposals())
                .filteredOn(x -> x.id().equals(changed.getId())).singleElement()
                .satisfies(x -> {
                    assertThat(x.sameMetadata()).as("대상 자신은 그대로").isTrue();
                    assertThat(x.sameContext()).isFalse();
                });
        evaluations.judge(user(owner), request(a.id(), changed, null, "CORRECT"));
        assertThat(evaluations.metrics(user(owner)).models()).singleElement().satisfies(r -> {
            assertThat(r.judgments()).isEqualTo(1);
            assertThat(r.sameMetadata()).as("같은 입력 조건 판정 수").isZero();
        });
        assertThat(unchanged.getId()).isNotNull();
    }

    @Test
    @DisplayName("일괄 생성 실패 사유 (DEC-32) : 오류 코드의 사용자 문구(요청 언어), 등록된 문구가 없는 코드는 일반 문구")
    void jobErrorMessages() {
        assertThat(com.lexorma.ai.dictionary.service.AiDescriptionJobService.errorMessage("AI_PROVIDER_CALL_FAILED"))
                .isEqualTo(com.lexorma.common.i18n.Messages.error("AI_PROVIDER_CALL_FAILED")).isNotEqualTo("AI_PROVIDER_CALL_FAILED");
        assertThat(com.lexorma.ai.dictionary.service.AiDescriptionJobService.errorMessage("FAILED")).isEqualTo("이 대상의 설명 초안을 만들지 못했습니다.");
    }

    @Test
    @DisplayName("동시성 (F1) : 의미 수정과 판정이 겹치면 대상 행 잠금으로 줄 서고, 늦은 쪽이 거부된다 — 판정은 판정자가 본 의미로만 남는다")
    void concurrentMeaningAndJudgment() throws Exception {
        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, "결제 금액", "CONFIRMED"));
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));

        // 1) 의미 수정이 먼저 잠금 : 판정은 기다렸다가, 수정 전 버전을 본 요청이라 충돌로 거부된다
        long seen = targetVersion(a.id());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> update = CompletableFuture.runAsync(() -> tx.executeWithoutResult(x -> {
            evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(seen, null, MEANING, "CONFIRMED"));
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AiEvaluationDto.JudgmentRow> judge = CompletableFuture.supplyAsync(() -> evaluations.judge(user(owner),
                new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), null, seen, "CORRECT", false, null, "APPROPRIATE", "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null)));
        Thread.sleep(500);
        assertThat(judge).as("판정은 대상 행 잠금을 기다린다").isNotDone();
        release.countDown();
        update.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> judge.get(10, TimeUnit.SECONDS)).cause().isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_EVALUATION_CONFLICT);
        assertThat(evaluations.detail(user(owner), a.id()).judgments()).isEmpty();

        // 2) 판정이 먼저 잠금 : 의미 수정은 기다렸다가, 판정이 생겼으므로 거부된다
        long now = targetVersion(a.id());
        CountDownLatch judged = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        CompletableFuture<Void> judging = CompletableFuture.runAsync(() -> tx.executeWithoutResult(x -> {
            evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), null, now, "CORRECT", false, null, "APPROPRIATE", "NO_RECOMMENDATION",
                    "NO_RECOMMENDATION", null, null, null));
            judged.countDown();
            await(release2);
        }));
        assertThat(judged.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AiEvaluationDto.TargetDetail> change = CompletableFuture.supplyAsync(() ->
                evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(now, null, "또 다른 의미", "CONFIRMED")));
        Thread.sleep(500);
        assertThat(change).as("의미 수정은 대상 행 잠금을 기다린다").isNotDone();
        release2.countDown();
        judging.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> change.get(10, TimeUnit.SECONDS)).cause().isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        AiEvaluationDto.TargetDetail after = evaluations.detail(user(owner), a.id());
        assertThat(after.actualMeaning()).isEqualTo(MEANING);
        assertThat(after.judgments()).hasSize(1);
    }

    @Test
    @DisplayName("블라인드 (F2) : 실제 의미 확인 전에는 평가 상세가 AI 제안·판정·AI 승인 업무 설명을 보내지 않고 메타데이터만, 확인 뒤에는 보인다")
    void blindBeforeConfirmed() {
        // 등록 전에 AI 제안을 승인해 업무 설명이 AI 에서 온 값이 된 컬럼
        AiDescriptionProposal approved = propose(paidAt, List.of(evidence(paidAt)));
        approve(approved);
        tx.executeWithoutResult(x -> columnRepository.findById(status.getId()).orElseThrow().setSourceComment("결제 상태 (원본)"));
        evaluations.register(user(owner), register(paidAt, status));
        AiDescriptionProposal p = propose(status, List.of(evidence(status)));
        AiDescriptionProposal again = propose(paidAt, List.of(evidence(paidAt)));

        AiEvaluationDto.TargetRow s = target("TB_PAY.PAY_STTS_CD");
        AiEvaluationDto.TargetDetail blind = evaluations.detail(user(viewer), s.id());
        assertThat(blind.blind()).isTrue();
        assertThat(blind.proposals()).isEmpty();
        assertThat(blind.judgments()).isEmpty();
        assertThat(blind.target().proposalCount()).isEqualTo(1);
        assertThat(blind.metadata()).extracting(AiEvaluationDto.Metadata::exists, AiEvaluationDto.Metadata::tableName, AiEvaluationDto.Metadata::columnName,
                AiEvaluationDto.Metadata::comment, AiEvaluationDto.Metadata::sourceComment).containsExactly(true, "TB_PAY", "PAY_STTS_CD", "결제 상태 코드", "결제 상태 (원본)");
        AiEvaluationDto.TargetRow d = target("TB_PAY.PAY_DT");
        AiEvaluationDto.TargetDetail blindAi = evaluations.detail(user(owner), d.id());
        assertThat(blindAi.metadata().bizDescriptionFromAi()).isTrue();
        assertThat(blindAi.metadata().bizDescriptionHidden()).isTrue();
        assertThat(blindAi.metadata().bizDescription()).as("AI 승인으로 들어간 업무 설명은 확인 전에 보내지 않는다").isNull();
        // 확인 불가도 확인 전과 같이 가린다
        AiEvaluationDto.TargetDetail unconfirmable = evaluations.updateTarget(user(owner), s.id(), new AiEvaluationDto.TargetUpdate(s.version(), null, null, "UNCONFIRMABLE"));
        assertThat(unconfirmable.blind()).isTrue();
        assertThat(unconfirmable.proposals()).isEmpty();

        // 일반 제안 상세도 같은 판단으로 가린다 (AI-5-EVAL 2차) — 승인 제한 사유는 보인다
        AiDictionaryDto.ProposalDetail general = queries.detail(user(owner), p.getId());
        assertThat(general.proposal().blind()).isTrue();
        assertThat(general.proposal().proposedDescription()).isNull();
        assertThat(general.evaluationHold()).isEqualTo("MEANING_UNCONFIRMABLE");

        // 확인 뒤 : 제안이 보인다
        AiEvaluationDto.TargetDetail confirmed = evaluations.updateTarget(user(owner), d.id(), new AiEvaluationDto.TargetUpdate(d.version(), null, "결제 승인 일시", "CONFIRMED"));
        assertThat(confirmed.blind()).isFalse();
        assertThat(confirmed.proposals()).extracting(AiEvaluationDto.ProposalItem::id).contains(approved.getId(), again.getId());
        assertThat(confirmed.proposals()).filteredOn(x -> x.id().equals(again.getId())).singleElement()
                .satisfies(x -> {
                    assertThat(x.proposedDescription()).isEqualTo("PAY_DT 설명");
                    assertThat(x.approvalHold()).isEqualTo("NOT_JUDGED");
                });
        assertThat(confirmed.metadata().bizDescription()).isEqualTo("PAY_DT 설명");
        assertThat(confirmed.metadata().bizDescriptionFromAi()).isTrue();
        assertThat(confirmed.metadata().bizDescriptionHidden()).isFalse();
    }

    @Test
    @DisplayName("블라인드 (2차) : 일반 제안 목록·검색·상세·이력·생성 응답·검토 응답도 확인 전 평가 대상의 AI 내용(과 AI 승인 업무 설명)을 비운다. 확인 뒤·평가 대상 아닌 제안은 그대로, 다른 프로젝트는 볼 수 없다")
    void generalReviewBlind() {
        // 등록 전에 AI 제안을 승인해 업무 설명이 AI 에서 온 값이 된 컬럼, 평가 대상이 아닌 컬럼의 제안
        AiDescriptionProposal approved = propose(paidAt, List.of(evidence(paidAt)));
        approve(approved);
        AiDescriptionProposal general = propose(status, List.of(evidence(status)));
        evaluations.register(user(owner), register(amount, paidAt));
        ollama.then(AiDictionaryDraftIntegrationTest.draft("COLUMN", amount.getId(), "PAY_AMT 설명", "INFERRED", List.of(evidence(amount)), null, null, List.of())
                .replace("\"model\":\"gemma3:4b\"", "\"model\":\"" + model + "\""));
        AiDescriptionDraftService.Result generated = drafts.generate(user(owner), table.getId(), amount.getId(), true, null);
        // 생성 응답
        assertThat(queries.generated(generated).proposal()).satisfies(x -> {
            assertThat(x.blind()).isTrue();
            assertThat(x.proposedDescription()).isNull();
        });
        AiDescriptionProposal p = generated.proposal();
        AiDescriptionProposal again = propose(paidAt, List.of(evidence(paidAt)));

        // 목록 : 평가 대상(미확인)의 제안만 비운다
        List<AiDictionaryDto.ProposalSummary> rows = queries.list(user(viewer), null, null, table.getId(), null, null, 0, 100).getContent();
        assertThat(rows).filteredOn(x -> x.id().equals(p.getId())).singleElement().satisfies(x -> {
            assertThat(x.blind()).isTrue();
            assertThat(x).extracting(AiDictionaryDto.ProposalSummary::proposedDescription, AiDictionaryDto.ProposalSummary::confirmedDescription,
                    AiDictionaryDto.ProposalSummary::certainty).containsOnlyNulls();
            assertThat(x.evidenceTotal()).isZero();
            assertThat(x.status()).isEqualTo(AiDescriptionProposal.ReviewStatus.PROPOSED);
            assertThat(x.targetName()).isEqualTo("TB_PAY.PAY_AMT");
        });
        assertThat(rows).filteredOn(x -> x.id().equals(approved.getId())).singleElement().satisfies(x -> {
            assertThat(x.blind()).isTrue();
            assertThat(x.confirmedDescription()).isNull();
        });
        assertThat(rows).filteredOn(x -> x.id().equals(general.getId())).singleElement().satisfies(x -> {
            assertThat(x.blind()).isFalse();
            assertThat(x.proposedDescription()).isEqualTo("PAY_STTS_CD 설명");
        });
        // 검색 조건으로 찾아도 같다
        assertThat(queries.list(user(viewer), "OPEN", "COLUMN", null, "pay_amt", null, 0, 20).getContent()).isNotEmpty()
                .allSatisfy(x -> assertThat(x.proposedDescription()).isNull());

        // 상세 : 제안·근거·추천·질문을 보내지 않고 대상 메타데이터·상태·승인 제한 사유만
        AiDictionaryDto.ProposalDetail d = queries.detail(user(viewer), p.getId());
        assertThat(d.proposal().blind()).isTrue();
        assertThat(d.detail()).isNull();
        assertThat(d.evidence()).isNull();
        assertThat(d.evaluationHold()).isEqualTo("MEANING_PENDING");
        assertThat(d.current().name()).isEqualTo("TB_PAY.PAY_AMT");
        assertThat(d.current().description()).isEqualTo("결제 금액");
        // AI 승인 업무 설명 : 현재 값·생성 당시 값·이력의 확정 문구를 비운다
        AiDictionaryDto.ProposalDetail da = queries.detail(user(viewer), approved.getId());
        assertThat(da.bizDescriptionHidden()).isTrue();
        assertThat(da.current().bizDescription()).isNull();
        assertThat(da.history()).isNotEmpty().allSatisfy(h -> assertThat(h.confirmedDescription()).isNull());
        AiDictionaryDto.ProposalDetail dg = queries.detail(user(viewer), again.getId());
        assertThat(dg.bizDescriptionHidden()).isTrue();
        assertThat(dg.baseBizDescription()).isNull();
        assertThat(dg.proposal().replacesBizDescription()).isFalse();
        // 평가 대상이 아닌 제안은 그대로
        AiDictionaryDto.ProposalDetail dn = queries.detail(user(viewer), general.getId());
        assertThat(dn.proposal().blind()).isFalse();
        assertThat(dn.detail()).isNotNull();
        assertThat(dn.evidence()).isNotNull();

        // 검토 응답 (보류)
        AiDictionaryDto.ReviewResult held = reviews.hold(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, "평가 진행 중"));
        assertThat(held.proposal().blind()).isTrue();
        assertThat(held.proposal().proposedDescription()).isNull();

        // 다른 프로젝트에서는 제안 자체가 없다
        Account other = account("ROLE_USER");
        Project otherProject = projectRepository.save(Project.builder().projectName("ai5e-o-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(other).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(otherProject).account(other).isActive(true).build());
        ExecutionContext outsider = ExecutionContext.user(AccountDto.builder().id(String.valueOf(other.getId())).projectId(otherProject.getId()).build());
        rejected(() -> queries.detail(outsider, p.getId()), ErrorCode.AI_PROPOSAL_NOT_FOUND);
        assertThat(queries.list(outsider, null, null, null, null, null, 0, 100).getContent()).isEmpty();

        // 확인 뒤 : 기존 규칙대로 보인다. 확인 불가는 계속 가린다
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, MEANING, "CONFIRMED"));
        AiDictionaryDto.ProposalDetail open = queries.detail(user(viewer), p.getId());
        assertThat(open.proposal().blind()).isFalse();
        assertThat(open.proposal().proposedDescription()).isEqualTo("PAY_AMT 설명");
        assertThat(open.proposal().certainty()).isNotNull();
        assertThat(open.detail()).isNotNull();
        assertThat(open.evaluationHold()).isEqualTo("NOT_JUDGED");
        AiEvaluationDto.TargetRow pd = target("TB_PAY.PAY_DT");
        evaluations.updateTarget(user(owner), pd.id(), new AiEvaluationDto.TargetUpdate(pd.version(), null, null, "UNCONFIRMABLE"));
        assertThat(queries.detail(user(viewer), again.getId()).proposal().blind()).isTrue();
    }

    @Test
    @DisplayName("승인 제한 (F4) : 평가 대상의 제안은 실제 의미 확인 + 정확성 판정(맞음·일부·틀림) 뒤에만 승인(일괄·직접 호출 포함), 평가 불가·확인 불가는 계속 제한, 일반 제안·반려는 그대로, 승인해도 기준 데이터는 그대로")
    void approvalNeedsCompletedEvaluation() {
        // 평가 대상이 아닌 일반 제안 : 기존 그대로 승인
        AiDescriptionProposal general = propose(paidAt, List.of(evidence(paidAt)));
        assertThat(queries.detail(user(owner), general.getId()).evaluationHold()).isNull();
        approve(general);

        evaluations.register(user(owner), register(amount, status));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        // 실제 의미 미확인 : 승인 API 직접 호출·일괄 승인 모두 막힌다. 상태·업무 설명은 그대로
        assertThat(queries.detail(user(owner), p.getId()).evaluationHold()).isEqualTo("MEANING_PENDING");
        rejected(() -> reviews.approve(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null)), ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        assertThat(reviews.bulk(user(owner), new AiDictionaryDto.BulkReviewRequest("APPROVE", List.of(new AiDictionaryDto.BulkItem(p.getId(), p.getVersion())), null)))
                .singleElement().satisfies(r -> {
                    assertThat(r.outcome()).isEqualTo("FAILED");
                    assertThat(r.errorCode()).isEqualTo("AI_PROPOSAL_EVALUATION_PENDING");
                });
        assertThat(queries.detail(user(owner), p.getId()).proposal().status()).isEqualTo(AiDescriptionProposal.ReviewStatus.PROPOSED);
        assertThat(columnRepository.findById(amount.getId()).orElseThrow().getBizDescription()).isNull();

        // 확인했지만 판정 없음 → 제한, 평가 불가 판정 → 여전히 제한
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, MEANING, "CONFIRMED"));
        assertThat(queries.detail(user(owner), p.getId()).evaluationHold()).isEqualTo("NOT_JUDGED");
        rejected(() -> approve(p), ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        AiEvaluationDto.JudgmentRow notEvaluable = judge(a.id(), p, "NOT_EVALUABLE", false, "NOT_APPLICABLE", null, null);
        rejected(() -> approve(p), ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        // 승인 권한 규칙은 그대로 (VIEWER 는 판정과 무관하게 승인할 수 없다)
        rejected(() -> reviews.approve(user(viewer), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null)), null);

        // 정확성 판정 뒤 : 승인된다. 실제 의미·등록 시점 버전·판정·같은 메타데이터 여부는 그대로 (소급 변경 없음)
        evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), notEvaluable.version(), targetVersion(a.id()), "PARTIAL", false, null, "APPROPRIATE",
                "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null, "다시 보니 일부 맞음"));
        AiEvaluationDto.TargetDetail before = evaluations.detail(user(owner), a.id());
        assertThat(before.proposals()).singleElement().satisfies(x -> {
            assertThat(x.approvalHold()).isNull();
            assertThat(x.sameMetadata()).isTrue();
        });
        approve(p);
        assertThat(columnRepository.findById(amount.getId()).orElseThrow().getBizDescription()).isEqualTo("PAY_AMT 설명");
        AiEvaluationDto.TargetDetail after = evaluations.detail(user(owner), a.id());
        assertThat(after.actualMeaning()).isEqualTo(MEANING);
        assertThat(after.target().version()).isEqualTo(before.target().version());
        assertThat(after.judgments()).isEqualTo(before.judgments());
        assertThat(after.proposals()).singleElement().satisfies(x -> {
            assertThat(x.status()).isEqualTo(AiDescriptionProposal.ReviewStatus.APPROVED);
            assertThat(x.sameMetadata()).isTrue();
        });

        // 등록 뒤 메타데이터가 바뀐 제안(sameMetadata=false)도 판정을 마치고 기존 재검증을 통과하면 승인된다 (sameMetadata 는 지표의 비교 조건일 뿐)
        tx.executeWithoutResult(x -> columnRepository.findById(amount.getId()).orElseThrow().setColumnDesc("결제 금액(원)"));
        AiDescriptionProposal later = propose(columnRepository.findById(amount.getId()).orElseThrow(), List.of(evidence(amount)));
        judge(a.id(), later, "CORRECT", false, "APPROPRIATE", null, null);
        assertThat(evaluations.detail(user(owner), a.id()).proposals()).filteredOn(x -> x.id().equals(later.getId())).singleElement()
                .satisfies(x -> assertThat(x.sameMetadata()).isFalse());
        approve(later);

        // 확인 불가 대상 : 판정할 수 없으므로 승인 제한이 풀리지 않는다. 블라인드라 반려도 막고(DEC-29), 보류·종결은 사유와 함께 된다 — 종결은 '평가 확인 불가' 로 따로 남긴다
        AiEvaluationDto.TargetRow s = target("TB_PAY.PAY_STTS_CD");
        evaluations.updateTarget(user(owner), s.id(), new AiEvaluationDto.TargetUpdate(s.version(), null, null, "UNCONFIRMABLE"));
        AiDescriptionProposal sp = propose(status, List.of(evidence(status)));
        rejected(() -> approve(sp), ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        assertThat(reviews.hold(user(owner), sp.getId(), new AiDictionaryDto.ReviewRequest(sp.getVersion(), null, "평가 진행 중")).outcome()).isEqualTo("ON_HOLD");
        AiDictionaryDto.ProposalDetail held = queries.detail(user(owner), sp.getId());
        assertThat(held.evaluationHold()).isEqualTo("MEANING_UNCONFIRMABLE");
        rejected(() -> reviews.reject(user(owner), sp.getId(), new AiDictionaryDto.ReviewRequest(held.proposal().version(), null, "평가 중")),
                ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        assertThat(reviews.close(user(owner), sp.getId(), new AiDictionaryDto.ReviewRequest(held.proposal().version(), null, "실제 의미 확인 불가")).outcome())
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForList("select PRCS_TYPE_CD from TB_AI_EXPLN_SGSTN_HSTRY where AI_EXPLN_SGSTN_SN = ? order by AI_EXPLN_SGSTN_HSTRY_SN", String.class, sp.getId()))
                .endsWith("CLOSE_UNCONFIRMABLE");
        assertThat(queries.detail(user(owner), sp.getId()).evaluationHold()).as("끝난 제안은 승인할 일이 없다").isNull();
    }

    @Test
    @DisplayName("동시성 (F4) : 판정 저장과 승인이 겹치면 평가 대상 행 잠금으로 줄 선다 — 승인은 커밋된 판정으로만 판단한다")
    void concurrentJudgmentAndApproval() throws Exception {
        evaluations.register(user(owner), register(amount));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, MEANING, "CONFIRMED"));
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        AiEvaluationDto.JudgmentRow correct = judge(a.id(), p, "CORRECT", false, "APPROPRIATE", null, null);

        // 판정을 '평가 불가'로 고치는 트랜잭션이 잠금을 쥔 동안 승인은 기다리고, 커밋 뒤 미완료로 보고 거부한다
        long version = targetVersion(a.id());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> downgrade = CompletableFuture.runAsync(() -> tx.executeWithoutResult(x -> {
            evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(a.id(), p.getId(), correct.version(), version, "NOT_EVALUABLE", false, null, "NOT_APPLICABLE",
                    "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, null, null, "평가할 수 없음으로 바꿈"));
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AiDictionaryDto.ReviewResult> approval = CompletableFuture.supplyAsync(() ->
                reviews.approve(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null)));
        Thread.sleep(500);
        assertThat(approval).as("승인은 평가 대상 행 잠금을 기다린다").isNotDone();
        release.countDown();
        downgrade.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> approval.get(10, TimeUnit.SECONDS)).cause().isInstanceOf(CustomException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        assertThat(columnRepository.findById(amount.getId()).orElseThrow().getBizDescription()).isNull();
    }

    @Test
    @DisplayName("동시성 (2차) : 평가 대상 등록과 승인(직접·일괄)은 모델 대상 행 잠금으로 줄 선다 — 등록이 먼저면 승인 거부, 승인이 먼저면 승인 유지 후 등록. 같은 대상 동시 등록은 하나만")
    void concurrentRegisterAndApproval() throws Exception {
        // 1) 등록이 먼저 잠금 : 직접 승인은 기다렸다가 평가 대상을 보고 거부된다
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> registering = CompletableFuture.runAsync(() -> tx.executeWithoutResult(x -> {
            evaluations.register(user(owner), register(amount));
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AiDictionaryDto.ReviewResult> approval = CompletableFuture.supplyAsync(() ->
                reviews.approve(user(owner), p.getId(), new AiDictionaryDto.ReviewRequest(p.getVersion(), null, null)));
        Thread.sleep(500);
        assertThat(approval).as("승인은 등록이 쥔 컬럼 행을 기다린다").isNotDone();
        release.countDown();
        registering.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> approval.get(10, TimeUnit.SECONDS)).cause().isInstanceOf(CustomException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        assertThat(columnRepository.findById(amount.getId()).orElseThrow().getBizDescription()).isNull();

        // 2) 일괄 승인도 같다
        AiDescriptionProposal sp = propose(status, List.of(evidence(status)));
        CountDownLatch locked2 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        CompletableFuture<Void> registering2 = CompletableFuture.runAsync(() -> tx.executeWithoutResult(x -> {
            evaluations.register(user(owner), register(status));
            locked2.countDown();
            await(release2);
        }));
        assertThat(locked2.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<AiDictionaryDto.BulkReviewItem>> bulk = CompletableFuture.supplyAsync(() -> reviews.bulk(user(owner),
                new AiDictionaryDto.BulkReviewRequest("APPROVE", List.of(new AiDictionaryDto.BulkItem(sp.getId(), sp.getVersion())), null)));
        Thread.sleep(500);
        assertThat(bulk).as("일괄 승인도 기다린다").isNotDone();
        release2.countDown();
        registering2.get(10, TimeUnit.SECONDS);
        assertThat(bulk.get(10, TimeUnit.SECONDS)).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo("FAILED");
            assertThat(r.errorCode()).isEqualTo("AI_PROPOSAL_EVALUATION_PENDING");
        });

        // 3) 승인이 먼저 잠금 : 등록은 기다렸다가 승인 커밋 뒤 들어간다. 이미 된 승인은 그대로 (기존 정책 : 승인된 제안의 대상도 등록할 수 있다)
        AiDescriptionProposal dp = propose(paidAt, List.of(evidence(paidAt)));
        CountDownLatch locked3 = new CountDownLatch(1);
        CountDownLatch release3 = new CountDownLatch(1);
        CompletableFuture<Void> approving = CompletableFuture.runAsync(() -> tx.executeWithoutResult(x -> {
            reviews.approve(user(owner), dp.getId(), new AiDictionaryDto.ReviewRequest(dp.getVersion(), null, null));
            locked3.countDown();
            await(release3);
        }));
        assertThat(locked3.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AiEvaluationDto.RegisterResult> late = CompletableFuture.supplyAsync(() -> evaluations.register(user(owner), register(paidAt)));
        Thread.sleep(500);
        assertThat(late).as("등록은 승인이 쥔 컬럼 행을 기다린다").isNotDone();
        release3.countDown();
        approving.get(10, TimeUnit.SECONDS);
        assertThat(late.get(10, TimeUnit.SECONDS)).isEqualTo(new AiEvaluationDto.RegisterResult(1, 0));
        assertThat(queries.detail(user(owner), dp.getId()).proposal().status()).isEqualTo(AiDescriptionProposal.ReviewStatus.APPROVED);
        assertThat(columnRepository.findById(paidAt.getId()).orElseThrow().getBizDescription()).isEqualTo("PAY_DT 설명");
        assertThat(evaluations.detail(user(owner), target("TB_PAY.PAY_DT").id()).metadata().bizDescriptionFromAi()).isTrue();

        // 4) 같은 테이블 대상을 동시에 등록 : 늦은 쪽은 테이블 행을 기다렸다가 이미 등록된 것을 보고 건너뛴다
        AiEvaluationDto.RegisterRequest tableItem = new AiEvaluationDto.RegisterRequest(List.of(new AiEvaluationDto.RegisterItem(table.getId(), null, null)));
        CountDownLatch locked4 = new CountDownLatch(1);
        CountDownLatch release4 = new CountDownLatch(1);
        CompletableFuture<AiEvaluationDto.RegisterResult> first = CompletableFuture.supplyAsync(() -> tx.execute(x -> {
            AiEvaluationDto.RegisterResult r = evaluations.register(user(owner), tableItem);
            locked4.countDown();
            await(release4);
            return r;
        }));
        assertThat(locked4.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<AiEvaluationDto.RegisterResult> second = CompletableFuture.supplyAsync(() -> evaluations.register(user(owner), tableItem));
        Thread.sleep(500);
        assertThat(second).as("같은 대상 등록은 기다린다").isNotDone();
        release4.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(new AiEvaluationDto.RegisterResult(1, 0));
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(new AiEvaluationDto.RegisterResult(0, 1));
        assertThat(evaluations.list(user(owner))).filteredOn(t -> t.targetName().equals("TB_PAY")).hasSize(1);
    }

    @Test
    @DisplayName("기존 데이터 호환 (2차) : 예전 규칙으로 미확인 대상에 저장된 '평가할 수 없음' 판정은 그대로 남아 평가 불가로 세고, 그 대상의 의미·상태는 고정되며 블라인드·승인 제한은 유지된다 (자동 보정 없음)")
    void legacyNotEvaluableOnPendingTarget() {
        evaluations.register(user(owner), register(amount));
        AiEvaluationDto.TargetRow a = target("TB_PAY.PAY_AMT");
        AiDescriptionProposal p = propose(amount, List.of(evidence(amount)));
        // 지금 API 로는 만들 수 없는 행 : 예전 규칙(미확인 대상 판정 허용)의 데이터를 직접 넣는다
        tx.executeWithoutResult(x -> judgmentRepository.save(AiDescriptionEvaluation.builder().projectId(project.getId()).targetId(a.id()).proposalId(p.getId())
                .model(p.getModel()).promptVersion(p.getPromptVersion()).judgment(AiDescriptionEvaluation.Judgment.NOT_EVALUABLE).unfoundedClaim(false)
                .uncertainty(AiDescriptionEvaluation.UncertaintyJudgment.NOT_APPLICABLE).termRecommendation(AiDescriptionEvaluation.RecommendationJudgment.NO_RECOMMENDATION)
                .domainRecommendation(AiDescriptionEvaluation.RecommendationJudgment.NO_RECOMMENDATION).reviewerId(String.valueOf(owner.getUsername())).reviewedAt(java.time.LocalDateTime.now()).build()));

        AiEvaluationDto.TargetDetail d = evaluations.detail(user(owner), a.id());
        assertThat(d.blind()).isTrue();
        assertThat(d.judgments()).as("블라인드라 판정 내용도 보내지 않는다").isEmpty();
        assertThat(d.target().judgmentCount()).isEqualTo(1);
        rejected(() -> evaluations.updateTarget(user(owner), a.id(), new AiEvaluationDto.TargetUpdate(a.version(), null, MEANING, "CONFIRMED")),
                ErrorCode.AI_EVALUATION_MEANING_LOCKED);
        AiEvaluationDto.Metrics m = evaluations.metrics(user(owner));
        assertThat(m.dataset().pending()).isEqualTo(1);
        assertThat(m.models()).singleElement().satisfies(x -> {
            assertThat(x.notEvaluable()).isEqualTo(1);
            assertThat(x.accuracy().base()).as("확인하지 않은 대상은 정확성 분모에 들지 않는다").isZero();
        });
        rejected(() -> approve(p), ErrorCode.AI_PROPOSAL_EVALUATION_PENDING);
        assertThat(queries.detail(user(owner), p.getId()).proposal().blind()).isTrue();
        assertThat(judgmentRepository.findAllByProjectIdAndProposalId(project.getId(), p.getId())).as("저장된 판정은 바꾸지 않는다").singleElement()
                .satisfies(j -> assertThat(j.getJudgment()).isEqualTo(AiDescriptionEvaluation.Judgment.NOT_EVALUABLE));
    }

    // ------------------------------------------------------------------ 준비

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai5e-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private Account member(String role) {
        Account a = account(role);
        projectMemberRepository.save(ProjectMember.builder().project(project).account(a).isActive(true).build());
        return a;
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
        return evaluations.judge(user(owner), new AiEvaluationDto.JudgmentRequest(targetId, p.getId(), null, targetVersion(targetId), judgment, unfounded, null, uncertainty,
                "NO_RECOMMENDATION", "NO_RECOMMENDATION", null, review, writing));
    }

    private AiEvaluationDto.JudgmentRequest request(Long targetId, AiDescriptionProposal p, Long version, String judgment) {
        return new AiEvaluationDto.JudgmentRequest(targetId, p.getId(), version, targetVersion(targetId), judgment, false, "통화 단위", "APPROPRIATE", "APPROPRIATE", "NOT_EVALUABLE",
                "부가세 언급 필요", 3, 9, version == null ? null : "판정 수정");
    }

    /** 화면이 본 대상 버전 (지금 값) */
    private long targetVersion(Long targetId) {
        return evaluations.detail(user(owner), targetId).target().version();
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("기다림 초과");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** code 가 null 이면 오류 종류와 상관없이 거부만 본다 (권한) */
    private static void rejected(Executable call, ErrorCode code) {
        var thrown = assertThatThrownBy(call::execute);
        if (code != null) thrown.isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(code);
    }
}
