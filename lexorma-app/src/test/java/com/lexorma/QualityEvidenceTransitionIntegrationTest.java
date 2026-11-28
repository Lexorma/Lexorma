package com.lexorma;

import com.lexorma.ai.answer.StructuredAnswer;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.ai.evidence.EvidenceVerifier;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.ai.tool.MetadataToolset;
import com.lexorma.ai.tool.QualityToolDefinitions;
import com.lexorma.ai.tool.ToolScope;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.service.InspectionExceptionService;
import com.lexorma.data.inspection.service.InspectionHooks;
import com.lexorma.data.inspection.service.InspectionJudge;
import com.lexorma.data.inspection.service.InspectionQueryService;
import com.lexorma.data.inspection.service.InspectionRuleSettingService;
import com.lexorma.data.inspection.service.InspectionRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-7 3차 — 2차에서 코드로만 확인한 품질 근거(QUALITY_FINDING · QUALITY_RUN) 버전 변경을 실제 상태 전이로 본다 (AI7-D09).
 * 근거는 실제 Tool 호출(MetadataToolset.run — AI·MCP 공통 경계)로 얻고, 상태는 운영 서비스 경로로만 바꾼다 : 모델 수정(JPA, 변경 이력) + 실제 재검사,
 * 예외 처리·해제 서비스, 실제 실행기 (중간 지점은 테스트 전용 {@link InspectionHooks} 로만 멈춘다).
 * 동적 상태(지금 규칙 사용 여부·대상 존재)는 저장 결과의 MDFCN_DT 로 보장되지 않는다 — 그것만 바뀌면 근거는 그대로 VERIFIED 이고 Tool 결과의 '…Now' 칸만 바뀐다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(QualityEvidenceTransitionIntegrationTest.Hooks.class)
class QualityEvidenceTransitionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    /** 실행기 판정 스냅숏 직후에 멈출 실행 (null = 멈추지 않는다) */
    private static final AtomicReference<CountDownLatch> PAUSE = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> PAUSED = new AtomicReference<>();
    /** 실패시킬 규칙 묶음 (null = 없음) */
    private static final AtomicReference<InspectionJudge.Group> FAIL_GROUP = new AtomicReference<>();

    @TestConfiguration
    static class Hooks {
        @Bean
        InspectionHooks inspectionHooks() {
            return new InspectionHooks() {
                @Override
                public void afterSnapshot(long runId) {
                    CountDownLatch pause = PAUSE.get();
                    if (pause == null) return;
                    PAUSED.get().countDown();
                    try {
                        if (!pause.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("시험 멈춤이 풀리지 않았다");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void beforeGroup(long runId, InspectionJudge.Group group) {
                    if (group == FAIL_GROUP.get()) throw new IllegalStateException("시험용 규칙 묶음 실패");
                }
            };
        }
    }

    @Autowired ApplicationContext beans;
    @Autowired MetadataToolset toolset;
    @Autowired EvidenceVerifier evidenceVerifier;
    @Autowired InspectionQueryService inspectionQueries;
    @Autowired InspectionExceptionService exceptions;
    @Autowired InspectionRuleSettingService ruleSettings;
    @Autowired InspectionRunService runs;
    @Autowired ProjectRepository projectRepository;
    @Autowired ColumnInfoRepository columns;
    @Autowired TransactionTemplate tx;

    private QualityToolFixture fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new QualityToolFixture(beans);
        PAUSE.set(null);
        PAUSED.set(null);
        FAIL_GROUP.set(null);
        SecurityTestSupport.clear();
    }

    @AfterEach
    void tearDown() {
        CountDownLatch pause = PAUSE.getAndSet(null);
        if (pause != null) pause.countDown();
        FAIL_GROUP.set(null);
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("결과 해결 → 재발 → 예외 처리 → 예외 해제 : 단계마다 앞에서 받은 결과 근거는 MODIFIED, 새로 받은 근거는 VERIFIED (운영 서비스 경로만)")
    void findingTransitions() {
        QualityToolFixture.Data f = fixtures.create("ai7-3-transition");
        long columnId = f.columns().get(1).getId(); // COL_A : 설명 없음 → COLUMN_COMMENT_MISSING
        long finding = findingOf(f, StandardizationRule.COLUMN_COMMENT_MISSING, columnId, "OPEN");

        // 1. 해결 : 설명을 채우고(JPA — 변경 이력) 실제 재검사
        MetadataToolSession open = sessionSeeing(f, finding);
        setColumnComment(columnId, "회원 보조 칼럼");
        fixtures.runAndWait(f.admin());
        SecurityTestSupport.clear();
        assertThat(inspectionQueries.finding(f.projectId(), finding).finding().status().name()).isEqualTo("RESOLVED");
        assertThat(verify(open, finding)).isEqualTo("STALE/MODIFIED");

        // 2. 재발 : 설명을 다시 비우고 재검사 — 같은 결과 행이 REOPENED
        MetadataToolSession resolved = sessionSeeing(f, finding);
        assertThat(verify(resolved, finding)).isEqualTo("VERIFIED");
        setColumnComment(columnId, null);
        fixtures.runAndWait(f.admin());
        SecurityTestSupport.clear();
        InspectionDto.Finding reopened = inspectionQueries.finding(f.projectId(), finding).finding();
        assertThat(reopened.status().name()).isEqualTo("OPEN");
        assertThat(reopened.lastChange().name()).isEqualTo("REOPENED");
        assertThat(verify(resolved, finding)).isEqualTo("STALE/MODIFIED");

        // 3. 예외 처리 (서비스) : 버전 변경
        MetadataToolSession beforeExclusion = sessionSeeing(f, finding);
        SecurityTestSupport.loginAs(projectRepository, f.projectId(), f.admin().getId(), "ROLE_ADMIN");
        exceptions.exclude(f.admin(), finding, new InspectionDto.ExclusionRequest("시험 예외", reopened.version()));
        SecurityTestSupport.clear();
        assertThat(verify(beforeExclusion, finding)).isEqualTo("STALE/MODIFIED");

        // 4. 예외 해제 (서비스) : 버전 다시 변경
        MetadataToolSession excluded = sessionSeeing(f, finding);
        assertThat(verify(excluded, finding)).isEqualTo("VERIFIED");
        long version = inspectionQueries.finding(f.projectId(), finding).finding().version();
        SecurityTestSupport.loginAs(projectRepository, f.projectId(), f.admin().getId(), "ROLE_ADMIN");
        exceptions.unexclude(f.admin(), finding, new InspectionDto.ExclusionRequest("시험 해제", version));
        SecurityTestSupport.clear();
        assertThat(inspectionQueries.finding(f.projectId(), finding).finding().excluded()).isFalse();
        assertThat(verify(excluded, finding)).isEqualTo("STALE/MODIFIED");
    }

    @Test
    @DisplayName("실행 근거 : 진행 중(RUNNING, 규칙 행 RUNNING)에 본 실행은 끝난 뒤(규칙별 결과·집계 확정) MODIFIED — 정상 완료와 일부 규칙 실패 둘 다")
    void runTransitions() throws Exception {
        QualityToolFixture.Data f = fixtures.create("ai7-3-run");
        for (InspectionJudge.Group failing : new InspectionJudge.Group[]{null, InspectionJudge.Group.TERM}) {
            FAIL_GROUP.set(failing);
            CountDownLatch pause = new CountDownLatch(1);
            CountDownLatch paused = new CountDownLatch(1);
            PAUSED.set(paused);
            PAUSE.set(pause);
            SecurityTestSupport.loginAs(projectRepository, f.projectId(), f.admin().getId(), "ROLE_ADMIN");
            long runId = runs.request(f.admin());
            SecurityTestSupport.clear();
            assertThat(paused.await(30, TimeUnit.SECONDS)).as("실행기가 판정 스냅숏 직후에 멈춘다").isTrue();

            MetadataToolSession watching = session(f);
            JsonNode during = JSON.readTree(toolset.run(QualityToolDefinitions.OVERVIEW, Map.of("runId", runId), watching));
            assertThat(during.path("run").path("status").asString()).isEqualTo("RUNNING");
            JsonNode ruleRows = during.path("rules");
            assertThat(ruleRows.size()).isEqualTo(20);
            ruleRows.forEach(r -> assertThat(r.path("status").asString()).as(r.toString()).isEqualTo("RUNNING"));
            assertThat(verifyRun(watching, runId)).isEqualTo("VERIFIED");

            PAUSE.set(null);
            pause.countDown();
            waitDone(f, runId);
            JsonNode after = JSON.readTree(toolset.run(QualityToolDefinitions.OVERVIEW, Map.of("runId", runId), session(f)));
            assertThat(after.path("run").path("status").asString()).isEqualTo("COMPLETED");
            if (failing == null) {
                assertThat(after.path("run").path("partialFailure").asBoolean(false)).isFalse();
                after.path("rules").forEach(r -> assertThat(r.path("status").asString()).isEqualTo("COMPLETED"));
            } else {
                assertThat(after.path("run").path("partialFailure").asBoolean()).isTrue();
                assertThat(after.path("rules").toString()).contains("FAILED");
            }
            assertThat(verifyRun(watching, runId)).as("규칙별 결과·집계가 확정되면 앞의 실행 근거는 바뀐 것으로").isEqualTo("STALE/MODIFIED");
        }
    }

    @Test
    @DisplayName("동적 상태는 버전 밖 : 규칙을 끄거나 대상을 지워도(재검사 전) 저장 결과 근거는 VERIFIED, Tool 결과의 ruleActiveNow·targetExistsNow 만 바뀐다")
    void dynamicStateIsNotVersioned() {
        QualityToolFixture.Data f = fixtures.create("ai7-3-dynamic");
        long columnId = f.columns().get(2).getId(); // COL_B
        long finding = findingOf(f, StandardizationRule.COLUMN_COMMENT_MISSING, columnId, "OPEN");
        MetadataToolSession seen = sessionSeeing(f, finding);
        JsonNode before = detail(f, finding);
        assertThat(before.path("targetExistsNow").asBoolean()).isTrue();
        assertThat(before.path("finding").path("ruleActiveNow").asBoolean()).isTrue();

        // 규칙 끄기 (규칙 설정 서비스) — 결과 행은 고치지 않는다
        SecurityTestSupport.loginAs(projectRepository, f.projectId(), f.admin().getId(), "ROLE_ADMIN");
        ruleSettings.update(f.admin(), new InspectionDto.RuleSettingsUpdate(List.of(new InspectionDto.RuleChange(StandardizationRule.COLUMN_COMMENT_MISSING, false, true))));
        SecurityTestSupport.clear();
        assertThat(detail(f, finding).path("finding").path("ruleActiveNow").asBoolean()).isFalse();
        assertThat(verify(seen, finding)).as("규칙 사용 여부는 저장 결과 버전에 들지 않는다").isEqualTo("VERIFIED");

        // 대상 삭제 (재검사 전) — 결과는 아직 OPEN, 대상 존재만 바뀐다
        tx.executeWithoutResult(s -> columns.deleteById(columnId));
        JsonNode gone = detail(f, finding);
        assertThat(gone.path("targetExistsNow").asBoolean()).isFalse();
        assertThat(gone.path("finding").path("status").asString()).isEqualTo("OPEN");
        assertThat(verify(seen, finding)).as("대상 존재는 저장 결과 버전에 들지 않는다").isEqualTo("VERIFIED");
        // 모델이 바뀐 것은 요약의 변경 감지로만 알린다 (최신 보장 아님)
        JsonNode overview = JSON.readTree(toolset.run(QualityToolDefinitions.OVERVIEW, Map.of(), session(f)));
        assertThat(overview.path("freshness").path("guarantee").asString()).isEqualTo("NOT_GUARANTEED");
        assertThat(overview.path("freshness").toString()).contains("CHANGE_DETECTED");
    }

    // ------------------------------------------------------------------ 보조

    private long findingOf(QualityToolFixture.Data f, StandardizationRule rule, long targetId, String status) {
        return inspectionQueries.findings(f.projectId(), new InspectionDto.Search(rule, null, null, InspectionDto.StatusFilter.valueOf(status), null, null, null, null, null,
                        null, null), PageRequest.of(0, 100)).getContent().stream()
                .filter(x -> x.targetId() == targetId).findFirst().orElseThrow().id();
    }

    /** 실제 Tool 호출로 그 결과를 본 세션 (상세 Tool 이 근거를 등록한다) */
    private MetadataToolSession sessionSeeing(QualityToolFixture.Data f, long findingId) {
        MetadataToolSession session = session(f);
        String raw = toolset.run(QualityToolDefinitions.DETAIL, Map.of("findingId", findingId), session);
        assertThat(raw).doesNotStartWith("{\"error\"");
        assertThat(session.seen(EvidenceType.QUALITY_FINDING, findingId)).isNotNull();
        return session;
    }

    private JsonNode detail(QualityToolFixture.Data f, long findingId) {
        return JSON.readTree(toolset.run(QualityToolDefinitions.DETAIL, Map.of("findingId", findingId), session(f)));
    }

    private MetadataToolSession session(QualityToolFixture.Data f) {
        return new MetadataToolSession(f.viewerContext(), "ai7-3-" + UUID.randomUUID(), MetadataToolset.TIMEOUT_SECONDS * 1000L, ToolScope.QUALITY);
    }

    private String verify(MetadataToolSession session, long findingId) {
        return status(evidenceVerifier.verify(session, List.of(new StructuredAnswer.Evidence(EvidenceType.QUALITY_FINDING, findingId,
                "COLUMN_COMMENT_MISSING", null, null, null))).evidence().getFirst());
    }

    private String verifyRun(MetadataToolSession session, long runId) {
        return status(evidenceVerifier.verify(session, List.of(new StructuredAnswer.Evidence(EvidenceType.QUALITY_RUN, runId, "RUN-" + runId, null, null, null)))
                .evidence().getFirst());
    }

    private static String status(EvidenceVerifier.Result result) {
        return result.status().name() + (result.reason() == null ? "" : "/" + result.reason().name());
    }

    private void setColumnComment(long columnId, String comment) {
        tx.executeWithoutResult(s -> columns.findById(columnId).orElseThrow().setColumnDesc(comment));
    }

    private void waitDone(QualityToolFixture.Data f, long runId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (inspectionQueries.runSnapshots(List.of(runId)).isEmpty()
                || JSON.readTree(toolset.run(QualityToolDefinitions.OVERVIEW, Map.of("runId", runId), session(f))).path("run").path("status").asString().matches("PENDING|RUNNING")) {
            if (System.nanoTime() > deadline) throw new AssertionError("실행 " + runId + " 이 끝나지 않았다");
            Thread.sleep(20);
        }
    }
}
