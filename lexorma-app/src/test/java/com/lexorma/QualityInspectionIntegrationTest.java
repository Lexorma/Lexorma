package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.inspection.domain.InspectionCodes;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.service.InspectionExceptionService;
import com.lexorma.data.inspection.service.InspectionHooks;
import com.lexorma.data.inspection.service.InspectionJudge;
import com.lexorma.data.inspection.service.InspectionQueryService;
import com.lexorma.data.inspection.service.InspectionRuleSettingService;
import com.lexorma.data.inspection.service.InspectionRunService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.system.project.service.ProjectManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장 품질 검사 (AI-6 2차) : 실제 실행 경로(요청 → 실행기 → 판정 스냅숏 → 저장)로 생명주기 L01~L18, 동시성 C01~C19(대기열 C03·C04·C19 는
 * {@link QualityInspectionQueueIntegrationTest}), 스냅숏 일관성을 확인한다. 응답뿐 아니라 DB 의 결과·이력·실행 건수를 직접 읽어 본다.
 * 경합은 sleep 이 아니라 실행기의 중간 지점({@link InspectionHooks})과 latch 로 고정하고, 잠금 대기는 PostgreSQL {@code pg_stat_activity} 로 확인한다
 * (M-8 원칙). 실행 완료를 기다리는 것만 짧은 간격으로 상태를 읽는다 (경합 판정에는 쓰지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(QualityInspectionIntegrationTest.Hooks.class)
class QualityInspectionIntegrationTest {

    /** 테스트가 끼워 넣는 실행 중간 지점 (기본은 아무것도 하지 않는다) */
    @TestConfiguration
    static class Hooks {
        static volatile LongConsumer afterSnapshot = id -> { };
        static volatile BiConsumer<Long, InspectionJudge.Group> beforeGroup = (id, group) -> { };
        static volatile BiConsumer<Long, InspectionJudge.Group> afterGroup = (id, group) -> { };
        static volatile LongConsumer beforeStore = id -> { };
        static volatile LongConsumer beforeComplete = id -> { };

        static void reset() {
            afterSnapshot = id -> { };
            beforeGroup = (id, group) -> { };
            afterGroup = (id, group) -> { };
            beforeStore = id -> { };
            beforeComplete = id -> { };
        }

        @Bean
        InspectionHooks inspectionHooks() {
            return new InspectionHooks() {
                @Override
                public void afterSnapshot(long runId) {
                    afterSnapshot.accept(runId);
                }

                @Override
                public void beforeGroup(long runId, InspectionJudge.Group group) {
                    beforeGroup.accept(runId, group);
                }

                @Override
                public void afterGroup(long runId, InspectionJudge.Group group, int items) {
                    afterGroup.accept(runId, group);
                }

                @Override
                public void beforeStore(long runId) {
                    beforeStore.accept(runId);
                }

                @Override
                public void beforeComplete(long runId) {
                    beforeComplete.accept(runId);
                }
            };
        }

        /** 판정 서비스처럼 @Transactional(readOnly) 경계 안에서 실제 SQL 오류를 낸다 — 바깥(검사 스냅숏) 트랜잭션이 rollback-only 가 되고 PostgreSQL 트랜잭션이 오류 상태가 된다 */
        @Bean
        FailingReader failingReader() {
            return new FailingReader();
        }
    }

    public static class FailingReader {
        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager entityManager;

        @org.springframework.transaction.annotation.Transactional(readOnly = true)
        public void fail() {
            entityManager.createNativeQuery("select 1 / 0").getSingleResult();
        }
    }

    @Autowired FailingReader failingReader;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired SchemaService schemaService;
    @Autowired InspectionRunService runService;
    @Autowired InspectionQueryService queryService;
    @Autowired InspectionExceptionService exceptionService;
    @Autowired InspectionRuleSettingService ruleSettingService;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired ProjectManageService projectManageService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        Hooks.reset();
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 생명주기

    @Test
    @DisplayName("L01~L05 : 신규 → 유지 → 해결(실제 개선) → 재발(이력) → 대상 삭제, 실행 건수·기준 일시·요약이 DB 와 같다")
    void basicLifecycle() {
        Fixture f = fixture("ai6-life");
        long first = runAndWait(f.admin());
        // L01 : 최초 진단 → NEW
        Row column = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        Row prefix = row(f.projectId(), StandardizationRule.TABLE_PREFIX_MISSING, f.badTable().getId());
        assertThat(column.status()).isEqualTo("OPEN");
        assertThat(column.lastChange()).isEqualTo("NEW");
        assertThat(column.firstRun()).isEqualTo(first).isEqualTo(column.lastRun());
        assertThat(prefix.status()).isEqualTo("OPEN");
        InspectionDto.Run run = queryService.run(f.projectId(), first);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.basisAt()).isNotNull().isBeforeOrEqualTo(run.endedAt());
        assertThat(run.rules()).hasSize(StandardizationRule.values().length)
                .allMatch(rule -> rule.status() == InspectionCodes.RuleRunStatus.COMPLETED && rule.resultCount() != null);
        assertThat(rule(run, StandardizationRule.TERM_NOT_LINKED).counts().get(RunChange.NEW)).isEqualTo(1);
        assertThat(run.totals().get(RunChange.NEW)).isEqualTo(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", f.projectId()));
        InspectionDto.Summary summary = queryService.summary(f.projectId());
        assertThat(summary.open()).isEqualTo(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'OPEN' and EXCL_YN = false", f.projectId()));
        assertThat(summary.lastRun().id()).isEqualTo(first);
        assertThat(summary.activeRun()).isNull();

        // L02 : 같은 데이터 재진단 → PERSISTING (같은 행, 최초 실행 그대로)
        long second = runAndWait(f.admin());
        Row persisting = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(persisting.id()).isEqualTo(column.id());
        assertThat(persisting.lastChange()).isEqualTo("PERSISTING");
        assertThat(persisting.firstRun()).isEqualTo(first);
        assertThat(persisting.lastRun()).isEqualTo(second);
        assertThat(rule(queryService.run(f.projectId(), second), StandardizationRule.TERM_NOT_LINKED).counts())
                .containsEntry(RunChange.PERSISTING, 1).containsEntry(RunChange.NEW, 0);
        assertThat(history(column.id())).isEmpty();

        // L03 : 개선(용어 연결) → RESOLVED (RULE_SATISFIED)
        linkTerm(f.column(), f.term());
        long third = runAndWait(f.admin());
        Row resolved = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.endReason()).isEqualTo("RULE_SATISFIED");
        assertThat(resolved.endedAt()).isNotNull();
        assertThat(rule(queryService.run(f.projectId(), third), StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.RULE_SATISFIED, 1);

        // L04 : 재발 → REOPENED, 직전 해결 사유·일시가 이력에
        linkTerm(f.column(), null);
        long fourth = runAndWait(f.admin());
        Row reopened = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(reopened.id()).isEqualTo(column.id());
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.lastChange()).isEqualTo("REOPENED");
        assertThat(reopened.endReason()).isNull();
        List<Map<String, Object>> reopenHistory = history(column.id());
        assertThat(reopenHistory).hasSize(1);
        assertThat(reopenHistory.getFirst()).containsEntry("chg_type_cd", "REOPEN").containsEntry("end_rsn_cd", "RULE_SATISFIED").containsEntry("insp_excn_sn", fourth);
        assertThat(rule(queryService.run(f.projectId(), fourth), StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.REOPENED, 1);

        // L05 : 대상 삭제 → RESOLVED (TARGET_DELETED), 실제 개선과 따로 센다
        tx.executeWithoutResult(s -> tableInfoRepository.deleteById(f.badTable().getId()));
        long fifth = runAndWait(f.admin());
        Row deleted = row(f.projectId(), StandardizationRule.TABLE_PREFIX_MISSING, f.badTable().getId());
        assertThat(deleted.status()).isEqualTo("RESOLVED");
        assertThat(deleted.endReason()).isEqualTo("TARGET_DELETED");
        assertThat(queryService.run(f.projectId(), fifth).totals().get(RunChange.TARGET_DELETED)).isGreaterThanOrEqualTo(1);
        InspectionDto.Summary after = queryService.summary(f.projectId());
        assertThat(after.targetDeleted()).isEqualTo(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and END_RSN_CD = 'TARGET_DELETED'", f.projectId()));
        assertThat(after.ruleSatisfied()).isEqualTo(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and END_RSN_CD = 'RULE_SATISFIED'", f.projectId()));
    }

    @Test
    @DisplayName("FINAL FP08·FP09 : 대상 이름을 바꿔도 같은 결과(표시 이름만 갱신), 지우고 같은 이름으로 다시 만들면 새 ID 라 새 결과 — 옛 결과는 대상 삭제로 해결")
    void fingerprintIdentityByTarget() {
        Fixture f = fixture("ai6-fp08");
        runAndWait(f.admin());
        Row before = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        exceptionService.exclude(f.admin(), before.id(), new InspectionDto.ExclusionRequest("이름 변경 확인", before.version()));

        // FP08 : 이름 변경 → 같은 행·같은 fingerprint, 표시 이름은 새 이름, 예외 유지
        jdbc.update("update TB_COL set COL_NM = 'MBR_NM_RENAMED' where COL_SN = ?", f.column().getId());
        long renamedRun = runAndWait(f.admin());
        Row renamed = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(renamed.id()).isEqualTo(before.id());
        assertThat(renamed.fingerprint()).isEqualTo(before.fingerprint());
        assertThat(renamed.lastChange()).isEqualTo("PERSISTING");
        assertThat(renamed.lastRun()).isEqualTo(renamedRun);
        assertThat(renamed.excluded()).isTrue();
        assertThat(jdbc.queryForObject("select TRGT_CN from TB_INSP_RSLT where INSP_RSLT_SN = ?", String.class, before.id())).endsWith("MBR_NM_RENAMED");

        // FP09 : 삭제 후 같은 이름으로 다시 만들기 → 옛 결과 TARGET_DELETED(예외 그대로), 새 컬럼은 새 결과(다른 fingerprint, 예외 아님)
        tx.executeWithoutResult(st -> columnInfoRepository.deleteById(f.column().getId()));
        ColumnInfo recreated = column(f.projectId(), f.table(), "MBR_NM_RENAMED", null);
        runAndWait(f.admin());
        Row old = row(before.id());
        assertThat(old.status()).isEqualTo("RESOLVED");
        assertThat(old.endReason()).isEqualTo("TARGET_DELETED");
        assertThat(old.excluded()).isTrue();
        Row fresh = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, recreated.getId());
        assertThat(fresh.id()).isNotEqualTo(before.id());
        assertThat(fresh.fingerprint()).isNotEqualTo(before.fingerprint());
        assertThat(fresh.lastChange()).isEqualTo("NEW");
        assertThat(fresh.excluded()).isFalse();
    }

    @Test
    @DisplayName("L06~L09 : 예외는 상태와 따로 — 재진단에도 유지(신규 아님), 실제 해결·재발에도 예외 유지, 해제해도 상태 그대로, 사유·버전 검사")
    void exceptions() {
        Fixture f = fixture("ai6-excl");
        runAndWait(f.admin());
        Row column = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());

        // 사유·버전 검사 (아무것도 바뀌지 않는다)
        assertCode(() -> exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("  ", column.version())), ErrorCode.INSPECTION_REASON_REQUIRED);
        assertCode(() -> exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("x".repeat(1001), column.version())),
                ErrorCode.INSPECTION_REASON_TOO_LONG);
        assertCode(() -> exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("사유", null)), ErrorCode.MODEL_BASE_REQUIRED);
        assertCode(() -> exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("사유", column.version() + 1)),
                ErrorCode.INSPECTION_RESULT_CONFLICT);
        assertThat(history(column.id())).isEmpty();

        InspectionDto.FindingDetail excluded = exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest(" 업무상 허용 ", column.version()));
        assertThat(excluded.finding().excluded()).isTrue();
        assertThat(excluded.finding().version()).isEqualTo(column.version() + 1);
        assertThat(excluded.history()).singleElement().satisfies(h -> {
            assertThat(h.type()).isEqualTo(InspectionCodes.HistoryType.EXCLUDE);
            assertThat(h.reason()).isEqualTo("업무상 허용");
        });
        // 기본 집계에서 빠지고 예외로 따로 센다
        assertThat(queryService.summary(f.projectId()).rules()).filteredOn(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED)
                .singleElement().satisfies(r -> {
                    assertThat(r.open()).isZero();
                    assertThat(r.openExcluded()).isEqualTo(1);
                });

        // L06 : 재진단 → 같은 결과 유지(신규 아님), 예외 유지, 실행 건수의 EXCLUDED
        long second = runAndWait(f.admin());
        Row persisting = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(persisting.id()).isEqualTo(column.id());
        assertThat(persisting.excluded()).isTrue();
        assertThat(persisting.lastChange()).isEqualTo("PERSISTING");
        assertThat(rule(queryService.run(f.projectId(), second), StandardizationRule.TERM_NOT_LINKED).counts())
                .containsEntry(RunChange.NEW, 0).containsEntry(RunChange.PERSISTING, 1).containsEntry(RunChange.EXCLUDED, 1);

        // L07 : 예외 문제가 실제로 해결 → RESOLVED, 예외·이력 유지
        linkTerm(f.column(), f.term());
        runAndWait(f.admin());
        Row resolved = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.excluded()).isTrue();
        assertThat(history(column.id())).extracting(h -> h.get("chg_type_cd")).containsExactly("EXCLUDE");
        // 해결된 결과는 예외로 처리할 수 없다 (해제는 할 수 있다)
        assertCode(() -> exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("사유", resolved.version())), ErrorCode.INSPECTION_RESULT_CONFLICT);

        // L08 : 예외 문제 재발 → REOPENED, 예외 유지
        linkTerm(f.column(), null);
        runAndWait(f.admin());
        Row reopened = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.excluded()).isTrue();
        assertThat(history(column.id())).extracting(h -> h.get("chg_type_cd")).containsExactly("EXCLUDE", "REOPEN");
        assertThat(history(column.id()).get(1)).containsEntry("excl_yn", true);

        // L09 : 예외 해제 → 실제 상태(OPEN) 그대로, 이력 추가
        InspectionDto.FindingDetail unexcluded = exceptionService.unexclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("다시 관리", reopened.version()));
        assertThat(unexcluded.finding().excluded()).isFalse();
        assertThat(unexcluded.finding().status()).isEqualTo(InspectionCodes.FindingStatus.OPEN);
        assertThat(unexcluded.finding().lastChange()).isEqualTo(InspectionCodes.LastChange.REOPENED);
        assertThat(history(column.id())).extracting(h -> h.get("chg_type_cd")).containsExactly("EXCLUDE", "REOPEN", "UNEXCLUDE");

        // 해결된 결과의 예외 처리 거절 : 해결 상태로 만들고 확인
        linkTerm(f.column(), f.term());
        runAndWait(f.admin());
        Row resolvedAgain = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        assertCode(() -> exceptionService.exclude(f.admin(), column.id(), new InspectionDto.ExclusionRequest("사유", resolvedAgain.version())),
                ErrorCode.INSPECTION_RESULT_NOT_OPEN);
    }

    @Test
    @DisplayName("L10~L12 : 규칙을 끄면 상태 유지·기본 집계 제외(따로 조회), 다시 켜도 실행 없음, 다음 정상 진단에서 실제 상태 재판정")
    void ruleSettings() {
        Fixture f = fixture("ai6-rule");
        runAndWait(f.admin());
        Row column = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        long runs = count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", f.projectId());

        // 변경 권한 : USER 이지만 프로젝트 관리자·ADMIN 이 아니면 거절. 조회는 누구나 (editable=false)
        Account user = account("ROLE_USER");
        member(f.project(), user);
        AccountDto userLogin = SecurityTestSupport.loginAs(projectRepository, f.projectId(), String.valueOf(user.getId()), "ROLE_USER");
        assertThat(ruleSettingService.settings(userLogin).editable()).isFalse();
        assertCode(() -> ruleSettingService.update(userLogin, disable(StandardizationRule.TERM_NOT_LINKED)), ErrorCode.EXECUTION_PERMISSION_DENIED);
        assertCode(() -> ruleSettingService.update(f.admin(), new InspectionDto.RuleSettingsUpdate(List.of(
                new InspectionDto.RuleChange(StandardizationRule.TERM_NOT_LINKED, false, true), new InspectionDto.RuleChange(StandardizationRule.TERM_NOT_LINKED, true, true)))),
                ErrorCode.INVALID_INSPECTION_RULE_SETTING);

        // L10 : 끈다 → 결과 상태 그대로, 기본 집계 제외, 끈 규칙으로 따로 조회
        InspectionDto.RuleSettings settings = ruleSettingService.update(f.admin(), disable(StandardizationRule.TERM_NOT_LINKED));
        assertThat(settings.rules()).filteredOn(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED).singleElement().satisfies(r -> assertThat(r.active()).isFalse());
        assertThat(count("select count(*) from TB_INSP_RULE_STNG where PRJ_SN = ?", f.projectId())).isEqualTo(1);
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("OPEN");
        InspectionDto.Summary summary = queryService.summary(f.projectId());
        assertThat(summary.inactiveOpen()).isEqualTo(1);
        assertThat(summary.rules()).filteredOn(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED).singleElement().satisfies(r -> assertThat(r.active()).isFalse());
        assertThat(findings(f.projectId(), InspectionDto.RuleScope.ACTIVE)).noneMatch(x -> x.id() == column.id());
        assertThat(findings(f.projectId(), InspectionDto.RuleScope.INACTIVE)).extracting(InspectionDto.Finding::id).containsExactly(column.id());

        // 끈 동안 고쳐도 끈 규칙은 판정하지 않는다 (해결하지 않는다)
        linkTerm(f.column(), f.term());
        long whileOff = runAndWait(f.admin());
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("OPEN");
        assertThat(queryService.run(f.projectId(), whileOff).rules()).noneMatch(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED);

        // L11 : 다시 켜도 실행하지 않는다 (기본값 = 행 삭제)
        long before = count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", f.projectId());
        ruleSettingService.update(f.admin(), new InspectionDto.RuleSettingsUpdate(List.of(new InspectionDto.RuleChange(StandardizationRule.TERM_NOT_LINKED, true, false))));
        assertThat(count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", f.projectId())).isEqualTo(before).isEqualTo(runs + 1);
        assertThat(count("select count(*) from TB_INSP_RULE_STNG where PRJ_SN = ?", f.projectId())).isZero();
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("OPEN");

        // L12 : 다음 정상 진단에서 실제 상태로
        runAndWait(f.admin());
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("L13·L14·C14 : 판정 중 실패·저장 중 실패 → 실행 FAILED, 이전 정상 결과 그대로(전체 롤백), 다시 실행하지 않는다")
    void failuresKeepPreviousResults() {
        Fixture f = fixture("ai6-fail");
        long ok = runAndWait(f.admin());
        Map<String, Object> before = snapshotOf(f.projectId());
        linkTerm(f.column(), f.term()); // 다음 실행이 성공했다면 해결됐을 변화

        // L13 : 판정 중 실패
        Hooks.afterSnapshot = id -> {
            throw new IllegalStateException("판정 실패 (테스트)");
        };
        long judgeFailed = runAndWait(f.admin());
        InspectionDto.Run failed = queryService.run(f.projectId(), judgeFailed);
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(InspectionCodes.ERR_FAILED);
        assertThat(failed.rules()).allMatch(r -> r.status() == InspectionCodes.RuleRunStatus.FAILED);
        assertThat(snapshotOf(f.projectId())).isEqualTo(before);

        // L14·C14 : 결과를 다 쓴 뒤 확정 직전 실패 → 전체 롤백
        Hooks.reset();
        Hooks.beforeComplete = id -> {
            throw new IllegalStateException("저장 실패 (테스트)");
        };
        long storeFailed = runAndWait(f.admin());
        assertThat(queryService.run(f.projectId(), storeFailed).status()).isEqualTo(RunStatus.FAILED);
        assertThat(queryService.run(f.projectId(), storeFailed).errorCode()).isEqualTo(InspectionCodes.ERR_STORE_FAILED);
        assertThat(snapshotOf(f.projectId())).isEqualTo(before);
        assertThat(count("select count(*) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ?", storeFailed)).isZero();
        assertThat(queryService.summary(f.projectId()).lastRun().id()).isEqualTo(ok);
        assertThat(queryService.summary(f.projectId()).latestRun().id()).isEqualTo(storeFailed);
        assertThat(count("select count(*) from TB_INSP_EXCN where PRJ_SN = ? and EXCN_STTS_CD in ('PENDING', 'RUNNING')", f.projectId())).isZero();

        // 실패 뒤 다음 요청은 정상
        Hooks.reset();
        runAndWait(f.admin());
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("L15~L17 : 용어 그룹 식별 — 구성원이 늘어도 같은 결과, 그룹 해체는 정상 해결, 같은 그룹 재발은 같은 fingerprint·예외 유지")
    void termGroups() {
        Fixture f = fixture("ai6-term");
        StandardTerm second = term(f.projectId(), "회원_명2", "MBR_NM", f.domain());
        runAndWait(f.admin());
        Row group = termGroup(f.projectId(), StandardizationRule.TERM_ABBR_DUPLICATE);
        assertThat(group.fingerprint()).startsWith("v4:");
        assertThat(targetIds(group.id())).containsExactlyInAnyOrder(f.term().getId(), second.getId());
        exceptionService.exclude(f.admin(), group.id(), new InspectionDto.ExclusionRequest("같은 약어 허용", group.version()));

        // L15 : 구성원 추가 → 같은 결과(같은 fingerprint), 근거의 구성원만 바뀐다
        StandardTerm third = term(f.projectId(), "회원_명3", "MBR_NM", f.domain());
        runAndWait(f.admin());
        Row grown = termGroup(f.projectId(), StandardizationRule.TERM_ABBR_DUPLICATE);
        assertThat(grown.id()).isEqualTo(group.id());
        assertThat(grown.fingerprint()).isEqualTo(group.fingerprint());
        assertThat(grown.lastChange()).isEqualTo("PERSISTING");
        assertThat(targetIds(group.id())).containsExactlyInAnyOrder(f.term().getId(), second.getId(), third.getId());

        // L16 : 그룹 해체 (구성원은 남아 있다) → 실제 해결 (대상 삭제 아님)
        abbreviation(second, "MBR_NM_B");
        abbreviation(third, "MBR_NM_C");
        runAndWait(f.admin());
        Row dissolved = row(group.id());
        assertThat(dissolved.status()).isEqualTo("RESOLVED");
        assertThat(dissolved.endReason()).isEqualTo("RULE_SATISFIED");

        // L17 : 같은 그룹 재발 → 같은 fingerprint, 예외 유지
        abbreviation(third, "MBR_NM");
        runAndWait(f.admin());
        Row again = termGroup(f.projectId(), StandardizationRule.TERM_ABBR_DUPLICATE);
        assertThat(again.id()).isEqualTo(group.id());
        assertThat(again.fingerprint()).isEqualTo(group.fingerprint());
        assertThat(again.status()).isEqualTo("OPEN");
        assertThat(again.lastChange()).isEqualTo("REOPENED");
        assertThat(again.excluded()).isTrue();
        assertThat(targetIds(group.id())).containsExactlyInAnyOrder(f.term().getId(), third.getId());
    }

    @Test
    @DisplayName("L18 : 규칙 묶음 하나를 판정하지 못하면 실행은 확정(RULE_FAILED)하되 그 규칙의 결과는 해결하지 않는다")
    void partialFailureDoesNotResolve() {
        Fixture f = fixture("ai6-part");
        StandardTerm second = term(f.projectId(), "회원_명2", "MBR_NM", f.domain());
        runAndWait(f.admin());
        Row group = termGroup(f.projectId(), StandardizationRule.TERM_ABBR_DUPLICATE);
        abbreviation(second, "MBR_NM_B");      // 용어 묶음이 판정됐다면 해결될 변화
        linkTerm(f.column(), f.term());        // 모델 묶음은 판정되어 해결된다

        Hooks.beforeGroup = (id, g) -> {
            if (g == InspectionJudge.Group.TERM) throw new IllegalStateException("용어 판정 실패 (테스트)");
        };
        long partial = runAndWait(f.admin());
        InspectionDto.Run run = queryService.run(f.projectId(), partial);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.errorCode()).isEqualTo(InspectionCodes.ERR_RULE_FAILED);
        assertThat(run.rules()).filteredOn(r -> InspectionJudge.group(r.rule()) == InspectionJudge.Group.TERM)
                .isNotEmpty().allMatch(r -> r.status() == InspectionCodes.RuleRunStatus.FAILED && r.resultCount() == null);
        assertThat(run.rules()).filteredOn(r -> InspectionJudge.group(r.rule()) != InspectionJudge.Group.TERM)
                .allMatch(r -> r.status() == InspectionCodes.RuleRunStatus.COMPLETED);
        Row kept = row(group.id());
        assertThat(kept.status()).isEqualTo("OPEN");
        assertThat(kept.lastRun()).isNotEqualTo(partial);
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("RESOLVED");

        Hooks.reset();
        runAndWait(f.admin());
        assertThat(row(group.id()).status()).isEqualTo("RESOLVED");
    }


    // ------------------------------------------------------------------ 부분 실패 (AI6-2-02, 2차 최종 보완 PF01~PF07)

    /** 묶음별 실패 방식 : 모델 = 판정 서비스 트랜잭션 경계 안의 SQL 오류(rollback-only), 식별자 = 같은 연결의 SQL 오류(트랜잭션 오류 상태), 용어 = 판정을 마친 뒤 결과를 넣기 전 예외 */
    private void failGroups(Set<InspectionJudge.Group> groups) {
        Hooks.beforeGroup = (id, g) -> {
            if (!groups.contains(g)) return;
            if (g == InspectionJudge.Group.MODEL) failingReader.fail();
            if (g == InspectionJudge.Group.IDENTIFIER) jdbc.queryForObject("select 1 / 0", Integer.class);
        };
        Hooks.afterGroup = (id, g) -> {
            if (groups.contains(g) && g == InspectionJudge.Group.TERM) throw new IllegalStateException("용어 판정 뒤 실패 (테스트)");
        };
    }

    record Pf(Fixture f, long baseline, Map<InspectionJudge.Group, Long> excludedIds) {
    }

    /** 세 묶음 모두 결과가 있는 프로젝트 : 기준 실행 → 묶음마다 결과 하나를 예외 처리 → 세 묶음이 모두 바뀌었을 변화(용어 연결·긴 테이블 이름 줄이기·그룹 해체) */
    private Pf partialFixture(String name) {
        Fixture f = fixture(name);
        StandardTerm second = term(f.projectId(), "회원_명2", "MBR_NM", f.domain());
        TableInfo longTable = table(f.projectId(), "TB_MBR_" + "L".repeat(60));
        long baseline = runAndWait(f.admin());
        InspectionDto.Run run = queryService.run(f.projectId(), baseline);
        // PF01 : 모든 활성 묶음 성공
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.errorCode()).isNull();
        assertThat(run.partialFailure()).isFalse();
        assertThat(run.failedRules()).isEmpty();
        assertThat(run.appliedRuleCount()).isEqualTo(StandardizationRule.values().length);
        Map<InspectionJudge.Group, Long> excluded = new java.util.EnumMap<>(InspectionJudge.Group.class);
        Row model = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        Row identifier = jdbc.queryForObject(ROW + " where PRJ_SN = ? and RULE_CD = 'IDENTIFIER_LENGTH_EXCEEDED' and TBL_SN = ? order by INSP_RSLT_SN limit 1",
                (rs, i) -> map(rs), f.projectId(), longTable.getId());
        Row term = termGroup(f.projectId(), StandardizationRule.TERM_ABBR_DUPLICATE);
        for (Map.Entry<InspectionJudge.Group, Row> e : Map.of(InspectionJudge.Group.MODEL, model, InspectionJudge.Group.IDENTIFIER, identifier,
                InspectionJudge.Group.TERM, term).entrySet()) {
            exceptionService.exclude(f.admin(), e.getValue().id(), new InspectionDto.ExclusionRequest("부분 실패 시험", e.getValue().version()));
            excluded.put(e.getKey(), e.getValue().id());
        }
        linkTerm(f.column(), f.term());
        jdbc.update("update TB_TBL set TBL_NM = 'TB_MBR_L' where TBL_SN = ?", longTable.getId());
        abbreviation(second, "MBR_NM_B");
        return new Pf(f, baseline, excluded);
    }

    /** 묶음별 결과 행 (규칙이 그 묶음인 것) — 비교용 전체 값 */
    private List<Map<String, Object>> groupRows(long projectId, InspectionJudge.Group group) {
        List<String> rules = java.util.Arrays.stream(StandardizationRule.values()).filter(r -> InspectionJudge.group(r) == group).map(Enum::name).toList();
        return jdbc.queryForList("select INSP_RSLT_SN, HASH_VL, STTS_CD, EXCL_YN, LAST_CHG_TYPE_CD, END_RSN_CD, END_DT, LAST_IDNTY_DT, LAST_INSP_EXCN_SN, VER_NO,"
                + " RSLT_EVDNC_CN::text from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = any(?) order by INSP_RSLT_SN", projectId, rules.toArray(String[]::new));
    }

    private void assertPartial(Pf pf, Set<InspectionJudge.Group> failing) {
        long projectId = pf.f().projectId();
        Map<InspectionJudge.Group, List<Map<String, Object>>> before = new java.util.EnumMap<>(InspectionJudge.Group.class);
        for (InspectionJudge.Group g : InspectionJudge.Group.values()) before.put(g, groupRows(projectId, g));
        List<Map<String, Object>> historyBefore = jdbc.queryForList("select * from TB_INSP_RSLT_HSTRY where PRJ_SN = ? order by 1", projectId);
        failGroups(failing);
        long partial = runAndWait(pf.f().admin());
        Hooks.reset();

        InspectionDto.Run run = queryService.run(projectId, partial);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.errorCode()).isEqualTo(InspectionCodes.ERR_RULE_FAILED);
        assertThat(run.partialFailure()).isTrue();
        assertThat(run.appliedRuleCount()).isEqualTo(StandardizationRule.values().length);
        assertThat(run.failedRules()).containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(StandardizationRule.values())
                .filter(r -> failing.contains(InspectionJudge.group(r))).toList());
        for (InspectionDto.RunRule rule : run.rules()) {
            boolean failed = failing.contains(InspectionJudge.group(rule.rule()));
            assertThat(rule.status()).as(rule.rule().name()).isEqualTo(failed ? InspectionCodes.RuleRunStatus.FAILED : InspectionCodes.RuleRunStatus.COMPLETED);
            if (failed) {
                assertThat(rule.resultCount()).isNull();
                assertThat(rule.counts().values()).as("실패한 규칙은 건수가 없다 (검사하지 않은 것) : " + rule.rule()).allMatch(c -> c == 0);
            }
        }
        assertThat(count("select count(*) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ? and RULE_CD = any(?)", partial,
                run.failedRules().stream().map(Enum::name).toArray(String[]::new))).isZero();
        for (InspectionJudge.Group g : InspectionJudge.Group.values()) {
            List<Map<String, Object>> after = groupRows(projectId, g);
            if (failing.contains(g)) {
                // 검사하지 않은 것 : 신규·유지·재발·해결·최종 실행·최종 확인 일시·예외·근거 어느 것도 바뀌지 않는다
                assertThat(after).as("실패한 묶음 " + g).isEqualTo(before.get(g));
            } else {
                assertThat(after).as("성공한 묶음 " + g).isNotEqualTo(before.get(g));
                assertThat(after).as("성공한 묶음의 결과는 이번 실행이 마지막으로 판정했다 " + g)
                        .filteredOn(r -> "OPEN".equals(r.get("stts_cd")) || partial == ((Number) r.get("last_insp_excn_sn")).longValue())
                        .allMatch(r -> ((Number) r.get("last_insp_excn_sn")).longValue() == partial);
            }
        }
        // 예외는 어느 묶음에서도 바뀌지 않고, 실패한 묶음의 결과에는 이력도 늘지 않는다 (재발 없음)
        pf.excludedIds().values().forEach(id -> assertThat(row(id).excluded()).isTrue());
        List<Map<String, Object>> historyAfter = jdbc.queryForList("select * from TB_INSP_RSLT_HSTRY where PRJ_SN = ? order by 1", projectId);
        assertThat(historyAfter).containsAll(historyBefore);
        assertThat(queryService.summary(projectId).lastRun().id()).isEqualTo(partial);
    }

    @Test
    @DisplayName("PF01~PF04 : 모든 묶음 성공 → COMPLETED, 모델·식별자·용어 묶음 하나 실패(실제 SQL 오류·rollback-only 포함) → COMPLETED + RULE_FAILED, 성공한 묶음만 반영")
    void partialFailureByGroup() {
        assertPartial(partialFixture("ai6-pf02"), Set.of(InspectionJudge.Group.MODEL));
        assertPartial(partialFixture("ai6-pf03"), Set.of(InspectionJudge.Group.IDENTIFIER));
        assertPartial(partialFixture("ai6-pf04"), Set.of(InspectionJudge.Group.TERM));
        // 두 묶음 실패 (SQL 오류 두 번 뒤에도 남은 묶음은 같은 스냅숏에서 판정된다)
        assertPartial(partialFixture("ai6-pf0x"), Set.of(InspectionJudge.Group.MODEL, InspectionJudge.Group.IDENTIFIER));
    }

    @Test
    @DisplayName("FINAL SP01~SP03·ST01 : 판정·저장 연결이 실제로 끊기면(세이브포인트 생성·되돌리기·해제 불가, 확정 직전 끊김) 실행은 FAILED 로 닫히고 결과는 그대로, 실행기는 다음 실행을 정상 처리")
    void connectionLossDuringRun() {
        // SP01 : 스냅숏을 잡은 직후 판정 연결을 끊는다 → 첫 묶음의 세이브포인트를 만들 수 없다
        Pf create = partialFixture("ai6-sp01");
        Map<String, Object> createBefore = snapshotOf(create.f().projectId());
        Hooks.afterSnapshot = id -> killCurrentConnection();
        assertFailedKeepingResults(create, createBefore, InspectionCodes.ERR_FAILED);

        // SP02 : 모델 묶음 판정 직전에 끊는다 → 묶음 실패 뒤 세이브포인트로 되돌릴 수 없다 → 부분 실패가 아니라 판정 실패
        Pf rollback = partialFixture("ai6-sp02");
        Map<String, Object> rollbackBefore = snapshotOf(rollback.f().projectId());
        Hooks.beforeGroup = (id, group) -> {
            if (group == InspectionJudge.Group.MODEL) killCurrentConnection();
        };
        assertFailedKeepingResults(rollback, rollbackBefore, InspectionCodes.ERR_FAILED);

        // SP03 : 모델 묶음 판정이 끝난 뒤 끊는다 → 세이브포인트 해제 실패 → 판정 실패 (끝까지 판정한 묶음의 결과도 쓰지 않는다)
        Pf release = partialFixture("ai6-sp03");
        Map<String, Object> releaseBefore = snapshotOf(release.f().projectId());
        Hooks.afterGroup = (id, group) -> {
            if (group == InspectionJudge.Group.MODEL) killCurrentConnection();
        };
        assertFailedKeepingResults(release, releaseBefore, InspectionCodes.ERR_FAILED);

        // ST01 : 저장을 모두 쓰고 확정하기 직전에 저장 연결이 끊긴다 → 커밋되지 않고 STORE_FAILED, 결과 그대로
        Pf store = partialFixture("ai6-st01");
        Map<String, Object> storeBefore = snapshotOf(store.f().projectId());
        Hooks.beforeComplete = id -> killCurrentConnection();
        assertFailedKeepingResults(store, storeBefore, InspectionCodes.ERR_STORE_FAILED);
    }

    @Test
    @DisplayName("FINAL ST02 : 같은 실행을 다시 반영하려 해도(끝난 실행의 재실행·재종료) 결과·실행 상태가 바뀌지 않는다")
    void sameRunCannotBeAppliedTwice() {
        Fixture f = fixture("ai6-st02");
        long first = runAndWait(f.admin());
        Map<String, Object> before = snapshotOf(f.projectId());
        // 실행기 진입점을 끝난 실행으로 다시 부른다 : PENDING 이 아니면 시작하지 않는다
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(runService, "run", first);
        // 끝난 실행을 실패·중단으로 닫으려 해도 바뀌지 않는다
        assertThat((Integer) tx.execute(st -> inspectionRepository.closeRun(first, RunStatus.FAILED, InspectionCodes.ERR_STORE_FAILED, null, null,
                java.time.LocalDateTime.now(), "test"))).isZero();
        assertThat(queryService.run(f.projectId(), first).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(snapshotOf(f.projectId())).isEqualTo(before);
    }

    /** 실행기 스레드의 지금 트랜잭션 연결을 다른 연결에서 끊는다 (PostgreSQL 이 실제로 연결을 닫는다) */
    private void killCurrentConnection() {
        Integer pid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        join(() -> assertThat(jdbc.queryForObject("select pg_terminate_backend(?, 10000)", Boolean.class, pid)).isTrue());
    }

    private void assertFailedKeepingResults(Pf pf, Map<String, Object> before, String errorCode) {
        long id = runAndWait(pf.f().admin());
        Hooks.reset();
        InspectionDto.Run run = queryService.run(pf.f().projectId(), id);
        assertThat(run.status()).as("실행이 PENDING/RUNNING 으로 남지 않는다").isEqualTo(RunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo(errorCode);
        assertThat(count("select count(*) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ?", id)).isZero();
        assertThat(snapshotOf(pf.f().projectId())).as("결과·이력·예외 그대로").isEqualTo(before);
        assertThat(queryService.summary(pf.f().projectId()).lastRun().id()).isEqualTo(pf.baseline());
        // 실행기·연결 풀이 회복되어 다음 실행은 정상 확정된다
        long next = runAndWait(pf.f().admin());
        assertThat(queryService.run(pf.f().projectId(), next).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(queryService.run(pf.f().projectId(), next).errorCode()).isNull();
    }

    @Test
    @DisplayName("PF05~PF07 : 모든 묶음 실패 → FAILED(ALL_RULES_FAILED)·결과 그대로, 부분 성공 뒤 저장 실패 → FAILED(STORE_FAILED)·전체 롤백, 부분 실패 뒤 정상 실행은 정상 판정")
    void totalFailureStoreFailureAndRecovery() {
        // PF05
        Pf all = partialFixture("ai6-pf05");
        Map<String, Object> before = snapshotOf(all.f().projectId());
        failGroups(EnumSet.allOf(InspectionJudge.Group.class));
        long failed = runAndWait(all.f().admin());
        Hooks.reset();
        InspectionDto.Run run = queryService.run(all.f().projectId(), failed);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo(InspectionCodes.ERR_ALL_RULES_FAILED);
        assertThat(run.partialFailure()).isFalse();
        assertThat(run.failedRules()).hasSize(StandardizationRule.values().length);
        assertThat(run.basisAt()).isNotNull();
        assertThat(count("select count(*) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ?", failed)).isZero();
        assertThat(snapshotOf(all.f().projectId())).isEqualTo(before);
        assertThat(queryService.summary(all.f().projectId()).lastRun().id()).isEqualTo(all.baseline());

        // PF06 : 용어 묶음 실패 + 나머지 판정 성공 뒤 저장 실패 → 전체 롤백
        Pf store = partialFixture("ai6-pf06");
        Map<String, Object> storeBefore = snapshotOf(store.f().projectId());
        failGroups(Set.of(InspectionJudge.Group.TERM));
        Hooks.beforeComplete = id -> {
            throw new IllegalStateException("저장 실패 (테스트)");
        };
        long storeFailed = runAndWait(store.f().admin());
        Hooks.reset();
        InspectionDto.Run storeRun = queryService.run(store.f().projectId(), storeFailed);
        assertThat(storeRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(storeRun.errorCode()).isEqualTo(InspectionCodes.ERR_STORE_FAILED);
        assertThat(storeRun.rules()).allMatch(r -> r.status() == InspectionCodes.RuleRunStatus.FAILED && r.resultCount() == null);
        assertThat(count("select count(*) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ?", storeFailed)).isZero();
        assertThat(snapshotOf(store.f().projectId())).isEqualTo(storeBefore);

        // PF07 : 부분 실패(용어) 뒤 다음 정상 실행 → 실패했던 규칙도 정상 판정 (해결·최종 실행 갱신)
        Pf recover = partialFixture("ai6-pf07");
        assertPartial(recover, Set.of(InspectionJudge.Group.TERM));
        long next = runAndWait(recover.f().admin());
        InspectionDto.Run nextRun = queryService.run(recover.f().projectId(), next);
        assertThat(nextRun.errorCode()).isNull();
        assertThat(nextRun.failedRules()).isEmpty();
        Row group = row(recover.excludedIds().get(InspectionJudge.Group.TERM));
        assertThat(group.status()).isEqualTo("RESOLVED");
        assertThat(group.endReason()).isEqualTo("RULE_SATISFIED");
        assertThat(group.lastRun()).isEqualTo(next);
        assertThat(group.excluded()).isTrue();
        assertThat(rule(nextRun, StandardizationRule.TERM_ABBR_DUPLICATE).counts()).containsEntry(RunChange.RULE_SATISFIED, 1);
    }

    @Test
    @DisplayName("부분 실패 경계 : 모든 규칙을 끄면 빈 검사(COMPLETED·적용 규칙 0), 켜 둔 묶음만 모두 실패하면 FAILED, 저장 직전 권한 상실·프로젝트 삭제·같은 실행의 중복 반영은 반영하지 않는다")
    void partialFailureBoundaries() {
        // 모든 규칙 비활성화 → 빈 검사 (실패가 아니다)
        Fixture empty = fixture("ai6-pf-empty");
        runAndWait(empty.admin());
        Map<String, Object> emptyBefore = snapshotOf(empty.projectId());
        ruleSettingService.update(empty.admin(), new InspectionDto.RuleSettingsUpdate(java.util.Arrays.stream(StandardizationRule.values())
                .map(r -> new InspectionDto.RuleChange(r, false, true)).toList()));
        long none = runAndWait(empty.admin());
        InspectionDto.Run noneRun = queryService.run(empty.projectId(), none);
        assertThat(noneRun.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(noneRun.errorCode()).isNull();
        assertThat(noneRun.appliedRuleCount()).isZero();
        assertThat(noneRun.rules()).isEmpty();
        assertThat(noneRun.failedRules()).isEmpty();
        assertThat(snapshotOf(empty.projectId())).isEqualTo(emptyBefore);

        // 일부 묶음만 켠 상태에서 켜 둔 묶음이 모두 실패 → FAILED (모든 규칙을 끈 빈 검사와 다르다)
        Fixture some = fixture("ai6-pf-some");
        runAndWait(some.admin());
        ruleSettingService.update(some.admin(), new InspectionDto.RuleSettingsUpdate(java.util.Arrays.stream(StandardizationRule.values())
                .filter(r -> InspectionJudge.group(r) != InspectionJudge.Group.TERM).map(r -> new InspectionDto.RuleChange(r, false, true)).toList()));
        Map<String, Object> someBefore = snapshotOf(some.projectId());
        failGroups(Set.of(InspectionJudge.Group.TERM));
        long someFailed = runAndWait(some.admin());
        Hooks.reset();
        InspectionDto.Run someRun = queryService.run(some.projectId(), someFailed);
        assertThat(someRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(someRun.errorCode()).isEqualTo(InspectionCodes.ERR_ALL_RULES_FAILED);
        assertThat(someRun.appliedRuleCount()).isEqualTo(6);
        assertThat(snapshotOf(some.projectId())).isEqualTo(someBefore);

        // 저장 직전 권한 상실 (요청한 USER 의 프로젝트 구성원 비활성) → FAILED, 반영 없음
        Fixture lost = fixture("ai6-pf-lost");
        Account user = account("ROLE_USER");
        member(lost.project(), user);
        AccountDto userLogin = SecurityTestSupport.loginAs(projectRepository, lost.projectId(), String.valueOf(user.getId()), "ROLE_USER");
        runAndWait(userLogin);
        Map<String, Object> lostBefore = snapshotOf(lost.projectId());
        linkTerm(lost.column(), lost.term());
        Hooks.beforeStore = id -> join(() -> jdbc.update("update TB_PRJ_MBR set ACTVTN_YN = false where PRJ_SN = ? and ACNT_SN = ?", lost.projectId(), user.getId()));
        long lostRun = runAndWait(userLogin);
        Hooks.reset();
        InspectionDto.Run lostResult = queryService.run(lost.projectId(), lostRun);
        assertThat(lostResult.status()).isEqualTo(RunStatus.FAILED);
        assertThat(lostResult.errorCode()).isNotNull().isNotEqualTo(InspectionCodes.ERR_STORE_FAILED).isNotEqualTo(InspectionCodes.ERR_FAILED);
        assertThat(snapshotOf(lost.projectId())).isEqualTo(lostBefore);

        // 저장 직전 프로젝트 삭제 → 실행·결과 행을 다시 만들지 않는다
        Fixture gone = fixture("ai6-pf-gone");
        runAndWait(gone.admin());
        Hooks.beforeStore = id -> join(() -> {
            SecurityTestSupport.loginAs(projectRepository, gone.projectId(), gone.admin().getId(), "ROLE_ADMIN");
            jdbc.update("update TB_PRJ set ACTVTN_YN = false where PRJ_SN = ?", gone.projectId());
            projectManageService.deleteProject(gone.projectId(), gone.admin());
            SecurityTestSupport.clear();
        });
        long goneRun = runService.request(gone.admin());
        waitUntil(() -> count("select count(*) from TB_INSP_EXCN where INSP_EXCN_SN = ? and EXCN_STTS_CD in ('PENDING', 'RUNNING')", goneRun) == 0, "실행 종료");
        Hooks.reset();
        for (String table : List.of("TB_INSP_RULE_STNG", "TB_INSP_EXCN", "TB_INSP_RSLT", "TB_INSP_RSLT_HSTRY")) {
            assertThat(count("select count(*) from " + table + " where PRJ_SN = ?", gone.projectId())).as(table).isZero();
        }
        assertThat(count("select count(*) from TB_INSP_EXCN_RULE where INSP_EXCN_SN = ?", goneRun)).isZero();
        assertThat(count("select count(*) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ?", goneRun)).isZero();

        // 같은 실행의 중복 반영 : 끝난 실행을 다시 돌려도 아무것도 하지 않는다 (시작은 PENDING 일 때만)
        Fixture twice = fixture("ai6-pf-twice");
        long done = runAndWait(twice.admin());
        linkTerm(twice.column(), twice.term());
        Map<String, Object> twiceBefore = snapshotOf(twice.projectId());
        InspectionDto.Run doneBefore = queryService.run(twice.projectId(), done);
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(runService);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(target, "run", done);
        assertThat(snapshotOf(twice.projectId())).isEqualTo(twiceBefore);
        assertThat(queryService.run(twice.projectId(), done)).isEqualTo(doneBefore);
    }

    @Test
    @DisplayName("AI6-2-04 : 실행의 EXCLUDED 는 저장 시점 값 — 판정 중 예외·해제는 저장 때 상태로, 실행 뒤 예외·해제는 과거 실행 건수를 바꾸지 않고 현재 요약만 바꾼다, 부분 실패 실행은 검사하지 않은 규칙의 예외를 세지 않는다")
    void exclusionCountsByTime() {
        Fixture f = fixture("ai6-exclcount");
        runAndWait(f.admin());
        long id = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).id();

        // 검사 시작 전 예외 → 그 실행에서 EXCLUDED 1
        exceptionService.exclude(f.admin(), id, new InspectionDto.ExclusionRequest("시작 전", row(id).version()));
        long r1 = runAndWait(f.admin());
        assertThat(rule(queryService.run(f.projectId(), r1), StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.EXCLUDED, 1);

        // 판정 중(스냅숏을 잡은 뒤) 해제 → 저장 시점에는 예외가 아니다 → EXCLUDED 0 (스냅숏 시점의 값을 쓰지 않는다)
        Hooks.afterSnapshot = run -> join(() -> exceptionService.unexclude(f.admin(), id, new InspectionDto.ExclusionRequest("판정 중 해제", row(id).version())));
        long r2 = runAndWait(f.admin());
        Hooks.reset();
        assertThat(rule(queryService.run(f.projectId(), r2), StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.EXCLUDED, 0)
                .containsEntry(RunChange.PERSISTING, 1);

        // 판정 중 예외 처리 → EXCLUDED 1
        Hooks.afterSnapshot = run -> join(() -> exceptionService.exclude(f.admin(), id, new InspectionDto.ExclusionRequest("판정 중 처리", row(id).version())));
        long r3 = runAndWait(f.admin());
        Hooks.reset();
        InspectionDto.Run r3Run = queryService.run(f.projectId(), r3);
        assertThat(rule(r3Run, StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.EXCLUDED, 1);

        // 실행 뒤 해제·재처리 : 과거 실행 건수는 그대로, 현재 요약은 바로 바뀐다
        exceptionService.unexclude(f.admin(), id, new InspectionDto.ExclusionRequest("실행 뒤 해제", row(id).version()));
        assertThat(queryService.run(f.projectId(), r3)).isEqualTo(r3Run);
        assertThat(queryService.summary(f.projectId()).rules()).filteredOn(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED).singleElement()
                .satisfies(r -> assertThat(r.openExcluded()).isZero());
        exceptionService.exclude(f.admin(), id, new InspectionDto.ExclusionRequest("실행 뒤 처리", row(id).version()));
        assertThat(queryService.run(f.projectId(), r3)).isEqualTo(r3Run);
        assertThat(queryService.run(f.projectId(), r1).rules()).filteredOn(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED).singleElement()
                .satisfies(r -> assertThat(r.counts()).containsEntry(RunChange.EXCLUDED, 1));
        assertThat(queryService.summary(f.projectId()).rules()).filteredOn(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED).singleElement()
                .satisfies(r -> assertThat(r.openExcluded()).isEqualTo(1));

        // 부분 실패 (모델 묶음 실패) : 예외인 TERM_NOT_LINKED 결과는 이번에 검사하지 않았다 → 건수 행이 없다
        failGroups(Set.of(InspectionJudge.Group.MODEL));
        long r4 = runAndWait(f.admin());
        Hooks.reset();
        InspectionDto.Run r4Run = queryService.run(f.projectId(), r4);
        assertThat(r4Run.partialFailure()).isTrue();
        assertThat(rule(r4Run, StandardizationRule.TERM_NOT_LINKED).counts().values()).allMatch(c -> c == 0);
        assertThat(r4Run.totals().get(RunChange.EXCLUDED)).isZero();
        assertThat(row(id).lastRun()).isEqualTo(r3);
    }

    @Test
    @DisplayName("AI6-2-06 : 그룹 일부 삭제·문제 유지 → 유지, 일부 삭제·문제 해소 → 실제 해결, 전체 삭제 → 대상 삭제, 구성원 전체 교체·같은 키 → 유지(같은 결과)")
    void targetDeletionMatrix() {
        Fixture f = fixture("ai6-delete");
        StandardTerm a1 = term(f.projectId(), "가_명1", "AAA_NM", f.domain());
        StandardTerm a2 = term(f.projectId(), "가_명2", "AAA_NM", f.domain());
        StandardTerm a3 = term(f.projectId(), "가_명3", "AAA_NM", f.domain());
        StandardTerm b1 = term(f.projectId(), "나_명1", "BBB_NM", f.domain());
        StandardTerm b2 = term(f.projectId(), "나_명2", "BBB_NM", f.domain());
        StandardTerm c1 = term(f.projectId(), "다_명1", "CCC_NM", f.domain());
        StandardTerm c2 = term(f.projectId(), "다_명2", "CCC_NM", f.domain());
        StandardTerm d1 = term(f.projectId(), "라_명1", "DDD_NM", f.domain());
        StandardTerm d2 = term(f.projectId(), "라_명2", "DDD_NM", f.domain());
        runAndWait(f.admin());
        Row a = duplicateGroup(f.projectId(), a1);
        Row b = duplicateGroup(f.projectId(), b1);
        Row c = duplicateGroup(f.projectId(), c1);
        Row d = duplicateGroup(f.projectId(), d1);

        deleteTerms(a3);                                                     // 일부 삭제, 문제 유지 (2개 남음)
        deleteTerms(b2);                                                     // 일부 삭제, 문제 해소 (1개 남음)
        deleteTerms(c1, c2);                                                 // 전체 삭제
        StandardTerm d3 = term(f.projectId(), "라_명3", "DDD_NM", f.domain()); // 전체 교체 : 새 구성원 둘 + 옛 구성원 삭제
        StandardTerm d4 = term(f.projectId(), "라_명4", "DDD_NM", f.domain());
        deleteTerms(d1, d2);
        long run = runAndWait(f.admin());

        assertThat(row(a.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.lastChange()).isEqualTo("PERSISTING");
        });
        assertThat(targetIds(a.id())).containsExactlyInAnyOrder(a1.getId(), a2.getId());
        assertThat(row(b.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("RESOLVED");
            assertThat(r.endReason()).isEqualTo("RULE_SATISFIED");
        });
        assertThat(row(c.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("RESOLVED");
            assertThat(r.endReason()).isEqualTo("TARGET_DELETED");
        });
        assertThat(row(d.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.lastChange()).isEqualTo("PERSISTING");
            assertThat(r.fingerprint()).isEqualTo(d.fingerprint());
        });
        assertThat(targetIds(d.id())).containsExactlyInAnyOrder(d3.getId(), d4.getId());
        // 실제 개선과 대상 삭제를 따로 센다 (대상 삭제는 개선에 넣지 않는다)
        assertThat(rule(queryService.run(f.projectId(), run), StandardizationRule.TERM_ABBR_DUPLICATE).counts())
                .containsEntry(RunChange.RULE_SATISFIED, 1).containsEntry(RunChange.TARGET_DELETED, 1).containsEntry(RunChange.PERSISTING, 2)
                .containsEntry(RunChange.NEW, 0);
        // 삭제된 대상의 표시 이름·근거는 남는다
        assertThat(jdbc.queryForObject("select TRGT_CN from TB_INSP_RSLT where INSP_RSLT_SN = ?", String.class, c.id())).isNotBlank();
        assertThat(targetIds(c.id())).containsExactlyInAnyOrder(c1.getId(), c2.getId());
    }

    private Row duplicateGroup(long projectId, StandardTerm member) {
        return jdbc.queryForObject(ROW + " where PRJ_SN = ? and RULE_CD = 'TERM_ABBR_DUPLICATE' and RSLT_EVDNC_CN -> 'targetIds' @> to_jsonb(?::bigint)",
                (rs, i) -> map(rs), projectId, member.getId());
    }

    private void deleteTerms(StandardTerm... terms) {
        tx.executeWithoutResult(s -> java.util.Arrays.stream(terms).forEach(t -> termRepository.deleteById(t.getId())));
    }

    // ------------------------------------------------------------------ 스냅숏 · 동시성

    @Test
    @DisplayName("스냅숏 일관성 · C05~C07 : 판정 중 표준·물리·논리 모델을 고쳐도 기다리지 않고, 결과는 스냅숏 시점 기준, 다음 진단에 반영")
    void snapshotConsistency() throws Exception {
        Fixture f = fixture("ai6-snap");
        runAndWait(f.admin());
        CountDownLatch edited = new CountDownLatch(1);
        List<Long> newColumn = new ArrayList<>();
        Hooks.afterSnapshot = id -> {
            // 다른 연결·트랜잭션에서 고치고 커밋한다 : 검사가 잠금을 잡지 않으므로 기다리지 않아야 한다
            CompletableFuture<Void> edit = CompletableFuture.runAsync(() -> {
                newColumn.add(column(f.projectId(), f.table(), "MBR_EXTRA", null).getId());          // C06 물리 모델
                linkTerm(f.column(), f.term());                                                   // C06 물리 모델 (해결될 변화)
                term(f.projectId(), "회원_명2", "MBR_NM", f.domain());                              // C05 표준 데이터 (그룹이 생길 변화)
                jdbc.update("insert into TB_LGCL_MDL(LGCL_MDL_SN, PRJ_SN, MDL_NM) values (nextval('SQ_LGCL_MDL'), ?, 'ai6-snapshot')", f.projectId()); // C07 논리 모델
                edited.countDown();
            });
            try {
                edit.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("검사 중 편집이 끝나지 않았다 (잠금 대기?)", e);
            }
        };
        long run = runAndWait(f.admin());
        assertThat(edited.getCount()).isZero();
        // 스냅숏 기준 : 새 컬럼은 없고, 고친 컬럼은 아직 미해결, 새 용어 그룹도 없다
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and TRGT_SN = ?", f.projectId(), newColumn.getFirst())).isZero();
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("OPEN");
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TERM_ABBR_DUPLICATE'", f.projectId())).isZero();
        InspectionDto.Run done = queryService.run(f.projectId(), run);
        assertThat(done.basisAt()).isBefore(done.endedAt());

        // 다음 진단은 바뀐 데이터를 본다
        Hooks.reset();
        runAndWait(f.admin());
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).status()).isEqualTo("RESOLVED");
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, newColumn.getFirst()).status()).isEqualTo("OPEN");
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TERM_ABBR_DUPLICATE' and STTS_CD = 'OPEN'", f.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("C01·C02 : 같은 프로젝트 동시 요청은 하나만(409), 다른 프로젝트는 따로 받아 차례로 끝난다. 권한 : VIEWER 는 실행 불가")
    void oneActiveRunPerProject() throws Exception {
        Fixture a = fixture("ai6-c01a");
        Fixture b = fixture("ai6-c01b");
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);
        Hooks.afterSnapshot = id -> {
            reached.countDown();
            await(hold);
        };
        long running = runService.request(a.admin());
        await(reached);

        // 같은 프로젝트 : 진행 중이면 409 (동시에 두 요청을 보내도 하나만 남는다 — UNIQUE)
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<Object>> both = List.of(concurrent(barrier, a), concurrent(barrier, a));
        for (CompletableFuture<Object> future : both) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(ErrorCode.INSPECTION_ALREADY_RUNNING);
        }
        assertThat(count("select count(*) from TB_INSP_EXCN where PRJ_SN = ? and PRGRS_PRJ_SN is not null", a.projectId())).isEqualTo(1);

        // 다른 프로젝트 : 받아서 대기(실행기 스레드 하나)
        Hooks.afterSnapshot = id -> { };
        long other = runService.request(b.admin());
        assertThat(inspectionRepository.findRun(b.projectId(), other).orElseThrow().status()).isEqualTo(RunStatus.PENDING);
        hold.countDown();
        waitDone(a.projectId(), running);
        waitDone(b.projectId(), other);
        assertThat(queryService.run(b.projectId(), other).status()).isEqualTo(RunStatus.COMPLETED);

        // 권한 : VIEWER 는 실행 요청을 받지 않는다 (DB 의 현재 역할로 확인)
        Account viewer = account("ROLE_VIEWER");
        member(a.project(), viewer);
        AccountDto viewerLogin = SecurityTestSupport.loginAs(projectRepository, a.projectId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertCode(() -> runService.request(viewerLogin), ErrorCode.EXECUTION_PERMISSION_DENIED);
        // 다른 프로젝트의 결과·실행은 보이지 않는다
        long finding = row(a.projectId(), StandardizationRule.TERM_NOT_LINKED, a.column().getId()).id();
        assertCode(() -> queryService.finding(b.projectId(), finding), ErrorCode.INSPECTION_RESULT_NOT_FOUND);
        assertCode(() -> queryService.run(b.projectId(), running), ErrorCode.INSPECTION_RUN_NOT_FOUND);
        assertCode(() -> exceptionService.exclude(b.admin(), finding, new InspectionDto.ExclusionRequest("x", 0L)), ErrorCode.INSPECTION_RESULT_NOT_FOUND);
    }

    @Test
    @DisplayName("C08 : 실행 중 규칙 설정을 바꿔도 그 실행은 시작 때 확정한 규칙으로 끝난다 (설정은 바로 저장)")
    void ruleSetFixedAtStart() {
        Fixture f = fixture("ai6-c08");
        Hooks.afterSnapshot = id -> join(() -> ruleSettingService.update(f.admin(), disable(StandardizationRule.TERM_NOT_LINKED)));
        long run = runAndWait(f.admin());
        assertThat(queryService.run(f.projectId(), run).rules()).anyMatch(r -> r.rule() == StandardizationRule.TERM_NOT_LINKED);
        assertThat(row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId()).lastRun()).isEqualTo(run);
        assertThat(count("select count(*) from TB_INSP_RULE_STNG where PRJ_SN = ? and RULE_CD = 'TERM_NOT_LINKED'", f.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("C09·C10 : 같은 결과의 동시 예외 처리·처리와 해제 경합 → 하나만 성공, 나머지 409, 이력은 성공한 것만")
    void concurrentExceptions() throws Exception {
        Fixture f = fixture("ai6-c09");
        runAndWait(f.admin());
        Row row = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<Object>> both = List.of(
                exclusion(barrier, () -> exceptionService.exclude(f.admin(), row.id(), new InspectionDto.ExclusionRequest("A", row.version()))),
                exclusion(barrier, () -> exceptionService.exclude(f.admin(), row.id(), new InspectionDto.ExclusionRequest("B", row.version()))));
        List<Object> results = List.of(both.get(0).get(30, TimeUnit.SECONDS), both.get(1).get(30, TimeUnit.SECONDS));
        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn(ErrorCode.INSPECTION_RESULT_CONFLICT::equals).hasSize(1);
        assertThat(history(row.id())).hasSize(1);

        Row excluded = row(row.id());
        CyclicBarrier race = new CyclicBarrier(2);
        List<CompletableFuture<Object>> mixed = List.of(
                exclusion(race, () -> exceptionService.unexclude(f.admin(), row.id(), new InspectionDto.ExclusionRequest("해제", excluded.version()))),
                exclusion(race, () -> exceptionService.unexclude(f.admin(), row.id(), new InspectionDto.ExclusionRequest("해제2", excluded.version()))));
        List<Object> mixedResults = List.of(mixed.get(0).get(30, TimeUnit.SECONDS), mixed.get(1).get(30, TimeUnit.SECONDS));
        assertThat(mixedResults).filteredOn("OK"::equals).hasSize(1);
        assertThat(row(row.id()).excluded()).isFalse();
        assertThat(row(row.id()).version()).isEqualTo(row.version() + 2);
        assertThat(history(row.id())).hasSize(2);
    }

    @Test
    @DisplayName("C11·C12 : 예외 처리·해제와 결과 저장 경합 — 저장이 행을 잡고 있으면 예외 처리가 기다렸다가 둘 다 반영, 먼저 커밋한 해제도 지워지지 않는다")
    void exceptionsVersusStore() throws Exception {
        Fixture f = fixture("ai6-c11");
        runAndWait(f.admin());
        Row row = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());

        // C11 : 저장 트랜잭션이 결과 행을 고친 뒤(잠금 보유) 예외 처리가 들어온다 → 행 잠금 대기(실제 PostgreSQL 대기 확인) → 저장 커밋 뒤 반영
        List<CompletableFuture<Object>> exclusion = new ArrayList<>();
        Hooks.beforeComplete = id -> {
            exclusion.add(CompletableFuture.supplyAsync(() -> call(() -> exceptionService.exclude(f.admin(), row.id(), new InspectionDto.ExclusionRequest("저장 중", row.version())))));
            waitUntil(() -> ConcurrencyDiagnostics.lockWaitCount(jdbc, "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                    + " and query like '%TB_INSP_RSLT%for update%'") >= 1, "예외 처리가 결과 행 잠금을 기다린다");
        };
        long store = runAndWait(f.admin());
        assertThat(exclusion.getFirst().get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        Row both = row(row.id());
        assertThat(both.excluded()).isTrue();
        assertThat(both.lastRun()).isEqualTo(store);
        assertThat(both.version()).isEqualTo(row.version() + 1);
        // 이 실행의 예외 건수는 저장 시점 기준 (예외 처리는 저장 커밋 뒤라 0)
        assertThat(rule(queryService.run(f.projectId(), store), StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.EXCLUDED, 0);

        // C12 : 판정이 끝난 뒤 저장 전에 예외 해제가 먼저 커밋 → 저장은 예외 칼럼을 쓰지 않으므로 해제가 남는다, 예외 건수도 저장 시점 값
        Hooks.reset();
        Hooks.beforeStore = id -> join(() -> exceptionService.unexclude(f.admin(), row.id(), new InspectionDto.ExclusionRequest("저장 전", both.version())));
        long after = runAndWait(f.admin());
        Row unexcluded = row(row.id());
        assertThat(unexcluded.excluded()).isFalse();
        assertThat(unexcluded.lastRun()).isEqualTo(after);
        assertThat(rule(queryService.run(f.projectId(), after), StandardizationRule.TERM_NOT_LINKED).counts()).containsEntry(RunChange.EXCLUDED, 0);
        assertThat(history(row.id())).extracting(h -> h.get("chg_type_cd")).containsExactly("EXCLUDE", "UNEXCLUDE");
    }

    @Test
    @DisplayName("C13 : 저장 전에 실행이 중단으로 닫혔으면(재시작 정리) 늦은 결과를 반영하지 않는다")
    void lateResultIgnored() {
        Fixture f = fixture("ai6-c13");
        long first = runAndWait(f.admin());
        Map<String, Object> before = snapshotOf(f.projectId());
        linkTerm(f.column(), f.term());
        Hooks.beforeStore = id -> join(() -> tx.executeWithoutResult(s -> inspectionRepository.closeRun(id, RunStatus.INTERRUPTED,
                InspectionCodes.ERR_SERVER_RESTARTED, null, null, java.time.LocalDateTime.now(), "SYSTEM")));
        long late = runAndWait(f.admin());
        assertThat(queryService.run(f.projectId(), late).status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(snapshotOf(f.projectId())).isEqualTo(before);
        assertThat(queryService.summary(f.projectId()).lastRun().id()).isEqualTo(first);
    }

    @Test
    @DisplayName("C15 : 실행 중 프로젝트를 지우면 검사는 실패하고 그 프로젝트의 검사 행이 남지 않는다")
    void projectDeletedDuringRun() {
        Fixture f = fixture("ai6-c15");
        runAndWait(f.admin());
        Hooks.afterSnapshot = id -> join(() -> {
            SecurityTestSupport.loginAs(projectRepository, f.projectId(), f.admin().getId(), "ROLE_ADMIN");
            jdbc.update("update TB_PRJ set ACTVTN_YN = false where PRJ_SN = ?", f.projectId());
            projectManageService.deleteProject(f.projectId(), f.admin());
            SecurityTestSupport.clear();
        });
        long run = runService.request(f.admin());
        waitUntil(() -> count("select count(*) from TB_INSP_EXCN where INSP_EXCN_SN = ? and EXCN_STTS_CD in ('PENDING', 'RUNNING')", run) == 0, "실행 종료");
        for (String table : List.of("TB_INSP_RULE_STNG", "TB_INSP_EXCN", "TB_INSP_RSLT", "TB_INSP_RSLT_HSTRY")) {
            assertThat(count("select count(*) from " + table + " where PRJ_SN = ?", f.projectId())).as(table).isZero();
        }
        assertThat(count("select count(*) from TB_INSP_EXCN_RULE where INSP_EXCN_SN = ?", run)).isZero();
    }

    @Test
    @DisplayName("C16~C18 : 끝나지 않은 실행(대기·진행)은 기동 때 INTERRUPTED 로 닫히고 결과는 그대로, 그 뒤 새 실행은 정상")
    void restartRecovery() {
        Fixture f = fixture("ai6-c16");
        long ok = runAndWait(f.admin());
        Map<String, Object> before = snapshotOf(f.projectId());
        // 이전 프로세스가 남긴 행 : 진행 중(규칙 확정됨) — C17. 대기 중은 같은 프로젝트에 하나뿐이라 다른 프로젝트로 — C16
        long running = tx.execute(s -> {
            long id = inspectionRepository.insertRun(f.projectId(), Long.parseLong(f.admin().getId()), java.time.LocalDateTime.now(), "admin");
            inspectionRepository.startRun(id, java.time.LocalDateTime.now(), "admin");
            inspectionRepository.insertRunRules(id, List.of(StandardizationRule.values()), java.time.LocalDateTime.now(), "admin");
            return id;
        });
        Fixture g = fixture("ai6-c16b");
        long pending = tx.execute(s -> inspectionRepository.insertRun(g.projectId(), Long.parseLong(g.admin().getId()), java.time.LocalDateTime.now(), "admin"));
        assertCode(() -> runService.request(f.admin()), ErrorCode.INSPECTION_ALREADY_RUNNING);

        runService.interruptLeftovers();
        InspectionDto.Run interrupted = queryService.run(f.projectId(), running);
        assertThat(interrupted.status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(interrupted.errorCode()).isEqualTo(InspectionCodes.ERR_SERVER_RESTARTED);
        assertThat(interrupted.endedAt()).isNotNull();
        assertThat(interrupted.rules()).allMatch(r -> r.status() == InspectionCodes.RuleRunStatus.FAILED);
        assertThat(queryService.run(g.projectId(), pending).status()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(snapshotOf(f.projectId())).isEqualTo(before);
        assertThat(queryService.summary(f.projectId()).lastRun().id()).isEqualTo(ok);

        // C18 : 재시작 뒤 새 실행
        long next = runAndWait(f.admin());
        assertThat(queryService.run(f.projectId(), next).status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("조회 API : 목록 조건(상태·예외·규칙·심각도·검색·정렬)과 상세 근거 JSON, 잘못된 정렬은 400")
    void queries() {
        Fixture f = fixture("ai6-query");
        runAndWait(f.admin());
        InspectionDto.Search all = new InspectionDto.Search(null, null, null, InspectionDto.StatusFilter.ALL, InspectionDto.ExclusionFilter.ALL,
                InspectionDto.RuleScope.ALL, null, null, null, null, null);
        long total = count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", f.projectId());
        assertThat(queryService.findings(f.projectId(), all, PageRequest.of(0, 100)).getTotalElements()).isEqualTo(total);
        var errors = queryService.findings(f.projectId(), new InspectionDto.Search(null, StandardizationRule.Severity.ERROR, null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 100));
        assertThat(errors.getContent()).isNotEmpty().allMatch(x -> x.severity() == StandardizationRule.Severity.ERROR);
        var search = queryService.findings(f.projectId(), new InspectionDto.Search(null, null, null, null, null, null, null, null, "tbl_x", null, null), PageRequest.of(0, 100));
        assertThat(search.getContent()).isNotEmpty().allMatch(x -> x.targetName().toLowerCase().contains("tbl_x"));
        var byTable = queryService.findings(f.projectId(), new InspectionDto.Search(null, null, null, null, null, null, null, f.table().getId(), null, null, null), PageRequest.of(0, 100));
        assertThat(byTable.getContent()).isNotEmpty().allMatch(x -> f.table().getId().equals(x.tableId()));

        Row column = row(f.projectId(), StandardizationRule.TERM_NOT_LINKED, f.column().getId());
        InspectionDto.FindingDetail detail = queryService.finding(f.projectId(), column.id());
        assertThat(detail.fingerprint()).startsWith("v4:");
        assertThat(detail.evidence().path("fingerprintVersion").asString()).isEqualTo("v4");
        assertThat(detail.evidence().path("targetIds").get(0).asLong()).isEqualTo(f.column().getId());
        assertThat(detail.evidence().path("details").path("columnName").asString()).isEqualTo("MBR_NM");
        assertThat(detail.finding().targetName()).endsWith("TB_MBR.MBR_NM");

        assertThatThrownBy(() -> com.lexorma.common.utils.PageableUtil.getGridPageable(0, 10, "password", com.lexorma.data.inspection.repository.InspectionQueryRepository.SORT_FIELDS))
                .isInstanceOf(CustomException.class);
        var sorted = queryService.findings(f.projectId(), all, com.lexorma.common.utils.PageableUtil.getGridPageable(0, 100, "targetName,asc",
                com.lexorma.data.inspection.repository.InspectionQueryRepository.SORT_FIELDS));
        List<String> names = sorted.getContent().stream().map(InspectionDto.Finding::targetName).toList();
        assertThat(names).isSortedAccordingTo(String::compareTo);
    }

    // ------------------------------------------------------------------ 준비

    record Fixture(Project project, AccountDto admin, StandardDomain domain, StandardTerm term, TableInfo table, ColumnInfo column, TableInfo badTable) {
        long projectId() {
            return project.getId();
        }
    }

    /** 접두어 TB_, 승인 단어 회원(MBR)·이름(NM), 용어 회원_이름(MBR_NM) — TB_MBR.MBR_NM 은 용어 미연결, TBL_X 는 접두어·단어 위반 */
    private Fixture fixture(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true).account(admin)
                .tablePrefix("TB_").build());
        member(project, admin);
        AccountDto login = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        StandardWord member = word(project.getId(), "회원", "MBR");
        StandardWord nameWord = word(project.getId(), "이름", "NM");
        StandardDomain domain = domainRepository.save(StandardDomain.builder().projectId(project.getId()).commonStandardDomainName("이름V10").dataType("VARCHAR")
                .dataLength(10).isApproval(true).build());
        StandardTerm term = term(project.getId(), "회원_이름", "MBR_NM", domain, member, nameWord);
        TableInfo table = table(project.getId(), "TB_MBR");
        ColumnInfo column = column(project.getId(), table, "MBR_NM", null);
        TableInfo bad = table(project.getId(), "TBL_X");
        return new Fixture(project, login, domain, term, table, column, bad);
    }

    private StandardWord word(long projectId, String name, String abbreviation) {
        return wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName(name).commonStandardWordAbbreviation(abbreviation)
                .isApproval(true).synonymList(new ArrayList<>()).restrictedWords(new ArrayList<>()).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain, StandardWord... words) {
        return tx.execute(s -> {
            StandardTerm term = StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                    .standardDomain(domain).isApproval(true).synonymList(new ArrayList<>()).termWordMappings(new ArrayList<>()).build();
            for (int i = 0; i < words.length; i++) {
                StandardTermWordMapping mapping = new StandardTermWordMapping();
                mapping.setStandardTerm(term);
                mapping.setStandardWord(wordRepository.getReferenceById(words[i].getId()));
                mapping.setOrderIndex(i + 1);
                term.getTermWordMappings().add(mapping);
            }
            return termRepository.save(term);
        });
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnName(name).dataType("VARCHAR").maxLength(10)
                .standardTerms(term).isApproval(true).build()));
    }

    private void linkTerm(ColumnInfo column, StandardTerm term) {
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", term == null ? null : term.getId(), column.getId());
    }

    private void abbreviation(StandardTerm term, String abbreviation) {
        jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", abbreviation, term.getId());
    }

    private Account account(String role) {
        String username = "ai6-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> accountRepository.save(Account.builder().username(username).name(username).password("{noop}x").isActive(true).isPasswdCheck(true)
                .userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private void member(Project project, Account account) {
        projectMemberRepository.save(ProjectMember.builder().project(project).account(account).isActive(true).build());
    }

    private static InspectionDto.RuleSettingsUpdate disable(StandardizationRule rule) {
        return new InspectionDto.RuleSettingsUpdate(List.of(new InspectionDto.RuleChange(rule, false, true)));
    }

    // ------------------------------------------------------------------ 실행 · 확인 보조

    private long runAndWait(AccountDto login) {
        long id = runService.request(login);
        waitDone(login.getProjectId(), id);
        return id;
    }

    /** 실행 완료 대기 : 상태를 짧은 간격으로 읽는다 (경합 판정에는 쓰지 않는다) */
    private void waitDone(long projectId, long runId) {
        waitUntil(() -> !inspectionRepository.findRun(projectId, runId).orElseThrow().status().active(), "검사 실행 " + runId + " 종료");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("시간 안에 끝나지 않았다 : " + what);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new AssertionError("latch 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** 다른 스레드(다른 연결·트랜잭션)에서 끝까지 실행한다 */
    private static void join(Runnable work) {
        try {
            CompletableFuture.runAsync(work).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletableFuture<Object> concurrent(CyclicBarrier barrier, Fixture f) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return call(() -> runService.request(f.admin()));
        });
    }

    private CompletableFuture<Object> exclusion(CyclicBarrier barrier, Runnable work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return call(work);
        });
    }

    private static Object call(Runnable work) {
        try {
            work.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode();
        }
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static InspectionDto.RunRule rule(InspectionDto.Run run, StandardizationRule rule) {
        return run.rules().stream().filter(r -> r.rule() == rule).findFirst().orElseThrow();
    }

    private List<InspectionDto.Finding> findings(long projectId, InspectionDto.RuleScope scope) {
        return queryService.findings(projectId, new InspectionDto.Search(null, null, null, null, null, scope, null, null, null, null, null), PageRequest.of(0, 100)).getContent();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    record Row(long id, String fingerprint, String status, boolean excluded, String lastChange, String endReason, Object endedAt, long firstRun, long lastRun,
               long version) {
    }

    private static final String ROW = "select INSP_RSLT_SN, HASH_VL, STTS_CD, EXCL_YN, LAST_CHG_TYPE_CD, END_RSN_CD, END_DT, FRST_INSP_EXCN_SN, LAST_INSP_EXCN_SN, VER_NO from TB_INSP_RSLT";

    private Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getObject(7), rs.getLong(8),
                rs.getLong(9), rs.getLong(10));
    }

    private Row row(long projectId, StandardizationRule rule, long targetId) {
        return jdbc.queryForObject(ROW + " where PRJ_SN = ? and RULE_CD = ? and TRGT_SN = ?", (rs, i) -> map(rs), projectId, rule.name(), targetId);
    }

    private Row row(long id) {
        return jdbc.queryForObject(ROW + " where INSP_RSLT_SN = ?", (rs, i) -> map(rs), id);
    }

    private Row termGroup(long projectId, StandardizationRule rule) {
        return jdbc.queryForObject(ROW + " where PRJ_SN = ? and RULE_CD = ?", (rs, i) -> map(rs), projectId, rule.name());
    }

    private List<Long> targetIds(long findingId) {
        return jdbc.queryForList("select jsonb_array_elements_text(RSLT_EVDNC_CN -> 'targetIds')::bigint from TB_INSP_RSLT where INSP_RSLT_SN = ?", Long.class, findingId);
    }

    private List<Map<String, Object>> history(long findingId) {
        return jdbc.queryForList("select CHG_TYPE_CD as chg_type_cd, END_RSN_CD as end_rsn_cd, INSP_EXCN_SN as insp_excn_sn, EXCL_YN as excl_yn, CHG_RSN_CN as chg_rsn_cn"
                + " from TB_INSP_RSLT_HSTRY where INSP_RSLT_SN = ? order by INSP_RSLT_HSTRY_SN", findingId);
    }

    /** 결과·이력 전체 (비교용) */
    private Map<String, Object> snapshotOf(long projectId) {
        return Map.of("results", jdbc.queryForList("select INSP_RSLT_SN, HASH_VL, STTS_CD, EXCL_YN, LAST_CHG_TYPE_CD, END_RSN_CD, END_DT, LAST_INSP_EXCN_SN, VER_NO,"
                        + " RSLT_EVDNC_CN::text from TB_INSP_RSLT where PRJ_SN = ? order by INSP_RSLT_SN", projectId),
                "history", jdbc.queryForList("select * from TB_INSP_RSLT_HSTRY where PRJ_SN = ? order by INSP_RSLT_HSTRY_SN", projectId));
    }
}
