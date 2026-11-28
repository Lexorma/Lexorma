package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.quality.StandardizationRule;
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
import com.lexorma.home.repository.DashboardRepositoryCustom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완전성 4규칙의 저장 검사 (AI-6 3차, CP15~CP25 — 판정 자체는 lexorma-data CompletenessRulesTest) : 실제 실행 경로(요청 → 실행기 → 판정 스냅숏 → 저장)로
 * 생명주기·이름 변경·삭제 후 재생성·규칙 끄기·예외·프로젝트 격리·모델 묶음 실패를 보고, DB 에 저장된 공백류 값과 대시보드 실시간 수치(SQL 판정)가 같은 기준인지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(CompletenessInspectionIntegrationTest.Hooks.class)
class CompletenessInspectionIntegrationTest {

    @TestConfiguration
    static class Hooks {
        static volatile BiConsumer<Long, InspectionJudge.Group> beforeGroup = (id, group) -> { };

        @Bean
        InspectionHooks completenessHooks() {
            return new InspectionHooks() {
                @Override
                public void beforeGroup(long runId, InspectionJudge.Group group) {
                    beforeGroup.accept(runId, group);
                }
            };
        }
    }

    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired InspectionRunService runService;
    @Autowired InspectionQueryService queryService;
    @Autowired InspectionExceptionService exceptionService;
    @Autowired InspectionRuleSettingService ruleSettingService;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired DashboardRepositoryCustom dashboardRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        Hooks.beforeGroup = (id, group) -> { };
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("CP15~CP19 : 같은 누락 재검사 → 유지, 등록 → 해결(실제 개선), 다시 비움 → 재발, 이름 변경 → 같은 결과 유지, 대상 삭제 → 해결(대상 삭제)")
    void lifecycle() {
        F f = fixture("cp-life");
        long first = runAndWait(f.login());
        Row tableComment = row(f.projectId(), StandardizationRule.TABLE_COMMENT_MISSING, f.table().getId());
        Row columnBiz = row(f.projectId(), StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING, f.column().getId());
        assertThat(tableComment.lastChange()).isEqualTo("NEW");
        assertThat(rule(queryService.run(f.projectId(), first), StandardizationRule.TABLE_COMMENT_MISSING).counts()).containsEntry(RunChange.NEW, 1);

        // CP15 : 같은 문제 재검사 → 유지
        runAndWait(f.login());
        assertThat(row(tableComment.id()).lastChange()).isEqualTo("PERSISTING");

        // CP16 : 누락 항목 등록 → 해결 (실제 개선)
        jdbc.update("update TB_TBL set TBL_KORN_NM = '고객' where TBL_SN = ?", f.table().getId());
        jdbc.update("update TB_COL set TASK_EXPLN = '고객을 구분하는 번호' where COL_SN = ?", f.column().getId());
        runAndWait(f.login());
        assertThat(row(tableComment.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("RESOLVED");
            assertThat(r.endReason()).isEqualTo("RULE_SATISFIED");
        });
        assertThat(row(columnBiz.id()).status()).isEqualTo("RESOLVED");

        // CP17 : 등록한 항목을 다시 비우면 (공백만) 같은 결과가 재발
        jdbc.update("update TB_TBL set TBL_KORN_NM = ' \t ' where TBL_SN = ?", f.table().getId());
        long reopened = runAndWait(f.login());
        assertThat(row(tableComment.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.lastChange()).isEqualTo("REOPENED");
        });
        assertThat(rule(queryService.run(f.projectId(), reopened), StandardizationRule.TABLE_COMMENT_MISSING).counts()).containsEntry(RunChange.REOPENED, 1);

        // CP19 : 이름 변경 → 같은 fingerprint·같은 행, 표시 이름만 바뀐다
        jdbc.update("update TB_TBL set TBL_NM = 'TB_RENAMED' where TBL_SN = ?", f.table().getId());
        runAndWait(f.login());
        Row renamed = row(tableComment.id());
        assertThat(renamed.fingerprint()).isEqualTo(tableComment.fingerprint());
        assertThat(renamed.lastChange()).isEqualTo("PERSISTING");
        assertThat(jdbc.queryForObject("select TRGT_CN from TB_INSP_RSLT where INSP_RSLT_SN = ?", String.class, tableComment.id())).contains("TB_RENAMED");

        // CP18 : 대상 삭제 → 해결 (대상 삭제), 실제 개선과 따로 센다
        tx.executeWithoutResult(s -> columnInfoRepository.deleteById(f.column().getId()));
        Row columnComment = row(f.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING, f.column().getId());
        long deleted = runAndWait(f.login());
        assertThat(row(columnComment.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("RESOLVED");
            assertThat(r.endReason()).isEqualTo("TARGET_DELETED");
        });
        assertThat(rule(queryService.run(f.projectId(), deleted), StandardizationRule.COLUMN_COMMENT_MISSING).counts())
                .containsEntry(RunChange.TARGET_DELETED, 1).containsEntry(RunChange.RULE_SATISFIED, 0);
    }

    @Test
    @DisplayName("CP20~CP22 : 삭제 후 재생성(새 ID)은 새 결과, 규칙 끄기·켜기는 결과 그대로·재검사 필요 표시, 예외는 해결·재발에도 유지(신규로 다시 만들지 않음)")
    void recreateRuleAndException() {
        F f = fixture("cp-recreate");
        runAndWait(f.login());
        Row old = row(f.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING, f.column().getId());

        // CP20 : 같은 이름으로 다시 만든 컬럼(새 ID)은 다른 대상 → 새 결과, 옛 결과는 대상 삭제
        tx.executeWithoutResult(s -> columnInfoRepository.deleteById(f.column().getId()));
        ColumnInfo again = column(f.projectId(), f.table(), "CUST_NO");
        runAndWait(f.login());
        Row recreated = row(f.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING, again.getId());
        assertThat(recreated.id()).isNotEqualTo(old.id());
        assertThat(recreated.fingerprint()).isNotEqualTo(old.fingerprint());
        assertThat(recreated.lastChange()).isEqualTo("NEW");
        assertThat(row(old.id()).endReason()).isEqualTo("TARGET_DELETED");

        // CP21 : 끄면 결과 그대로 · 기본 목록 제외, 켜도 실행하지 않고 재검사 필요, 다음 검사에서 다시 판정
        ruleSettingService.update(f.login(), new InspectionDto.RuleSettingsUpdate(List.of(new InspectionDto.RuleChange(StandardizationRule.COLUMN_COMMENT_MISSING, false, true))));
        long off = runAndWait(f.login());
        assertThat(row(recreated.id()).lastRun()).as("끈 규칙의 결과는 이번 검사가 건드리지 않는다").isNotEqualTo(off);
        assertThat(findings(f.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING, InspectionDto.RuleScope.ACTIVE)).isEmpty();
        assertThat(findings(f.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING, InspectionDto.RuleScope.INACTIVE)).hasSize(1);
        long runs = count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", f.projectId());
        InspectionDto.RuleSettings on = ruleSettingService.update(f.login(), new InspectionDto.RuleSettingsUpdate(List.of(
                new InspectionDto.RuleChange(StandardizationRule.COLUMN_COMMENT_MISSING, true, false))));
        assertThat(count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", f.projectId())).as("켜도 실행하지 않는다").isEqualTo(runs);
        assertThat(on.rules()).filteredOn(r -> r.rule() == StandardizationRule.COLUMN_COMMENT_MISSING).singleElement()
                .satisfies(r -> assertThat(r.recheckNeeded()).isTrue());
        long next = runAndWait(f.login());
        assertThat(row(recreated.id()).lastRun()).isEqualTo(next);
        assertThat(ruleSettingService.settings(f.login()).rules()).filteredOn(r -> r.rule() == StandardizationRule.COLUMN_COMMENT_MISSING).singleElement()
                .satisfies(r -> {
                    assertThat(r.recheckNeeded()).isFalse();
                    assertThat(r.lastInspectedRunId()).isEqualTo(next);
                });

        // CP22 : 예외 → 해결 → 재발 : 같은 결과, 예외 유지
        Row target = row(f.projectId(), StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING, f.table().getId());
        exceptionService.exclude(f.login(), target.id(), new InspectionDto.ExclusionRequest("업무 설명은 나중에", target.version()));
        jdbc.update("update TB_TBL set TASK_EXPLN = '고객 정보' where TBL_SN = ?", f.table().getId());
        runAndWait(f.login());
        assertThat(row(target.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("RESOLVED");
            assertThat(r.excluded()).isTrue();
        });
        jdbc.update("update TB_TBL set TASK_EXPLN = null where TBL_SN = ?", f.table().getId());
        runAndWait(f.login());
        assertThat(row(target.id())).satisfies(r -> {
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.lastChange()).isEqualTo("REOPENED");
            assertThat(r.excluded()).isTrue();
        });
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TABLE_BIZ_DESCRIPTION_MISSING'", f.projectId())).isEqualTo(1);
    }

    @Test
    @DisplayName("CP23~CP25 : 다른 프로젝트 데이터 격리, DB 에 저장한 탭·개행 값과 대시보드 실시간 수치(SQL)가 같은 기준, 모델 묶음 실패 시 완전성 결과 보존")
    void isolationWhitespaceAndPartialFailure() {
        F a = fixture("cp-iso-a");
        F b = fixture("cp-iso-b");
        // CP24 : 탭·개행·VT·FF·CR·스페이스만 있는 값 = 누락 (자바 판정), 대시보드의 SQL 판정도 같다
        jdbc.update("update TB_TBL set TBL_KORN_NM = ?, TASK_EXPLN = ? where TBL_SN = ?", "\t\n", "\r\n \u000B\f", a.table().getId());
        jdbc.update("update TB_COL set COL_EXPLN = ?, TASK_EXPLN = ? where COL_SN = ?", " \t ", "설명", a.column().getId());
        ColumnInfo nbsp = column(a.projectId(), a.table(), "NBSP_COL");
        jdbc.update("update TB_COL set COL_EXPLN = ?, TASK_EXPLN = ? where COL_SN = ?", " ", "　", nbsp.getId());
        long run = runAndWait(a.login());
        assertThat(queryService.run(a.projectId(), run).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(rows(a.projectId(), StandardizationRule.TABLE_COMMENT_MISSING)).isEqualTo(1);
        assertThat(rows(a.projectId(), StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING)).isEqualTo(1);
        assertThat(rows(a.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING)).as("공백류만 있는 컬럼 1개 (NBSP 는 내용)").isEqualTo(1);
        assertThat(rows(a.projectId(), StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING)).as("전각 공백은 내용").isZero();
        long[] dashboard = dashboardRepository.countMissingDescriptions(a.projectId());
        assertThat(dashboard).containsExactly(rows(a.projectId(), StandardizationRule.TABLE_COMMENT_MISSING), rows(a.projectId(), StandardizationRule.COLUMN_COMMENT_MISSING),
                rows(a.projectId(), StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING), rows(a.projectId(), StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING));

        // CP23 : B 프로젝트는 검사하지 않았다 — A 의 검사가 B 를 판정하거나 B 의 결과를 만들지 않는다
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", b.projectId())).isZero();
        long bRun = runAndWait(b.login());
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and TBL_SN = ?", b.projectId(), a.table().getId())).isZero();
        assertThat(count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and LAST_INSP_EXCN_SN = ?", a.projectId(), bRun)).isZero();

        // CP25 : 모델 묶음(완전성 포함)이 실패하면 완전성 결과는 그대로 (검사하지 않은 것)
        Map<String, Object> before = jdbc.queryForMap("select string_agg(INSP_RSLT_SN || ':' || STTS_CD || ':' || LAST_INSP_EXCN_SN || ':' || LAST_IDNTY_DT, ',' order by INSP_RSLT_SN) v"
                + " from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD like '%\\_MISSING' escape '\\'", a.projectId());
        jdbc.update("update TB_TBL set TBL_KORN_NM = '채움', TASK_EXPLN = '채움' where TBL_SN = ?", a.table().getId());
        Hooks.beforeGroup = (id, group) -> {
            if (group == InspectionJudge.Group.MODEL) throw new IllegalStateException("모델 판정 실패 (테스트)");
        };
        long partial = runAndWait(a.login());
        Hooks.beforeGroup = (id, group) -> { };
        InspectionDto.Run partialRun = queryService.run(a.projectId(), partial);
        assertThat(partialRun.partialFailure()).isTrue();
        assertThat(partialRun.failedRules()).contains(StandardizationRule.TABLE_COMMENT_MISSING, StandardizationRule.COLUMN_COMMENT_MISSING,
                StandardizationRule.TABLE_BIZ_DESCRIPTION_MISSING, StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING);
        assertThat(jdbc.queryForMap("select string_agg(INSP_RSLT_SN || ':' || STTS_CD || ':' || LAST_INSP_EXCN_SN || ':' || LAST_IDNTY_DT, ',' order by INSP_RSLT_SN) v"
                + " from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD like '%\\_MISSING' escape '\\'", a.projectId())).isEqualTo(before);
        // 다음 정상 검사에서 해결
        runAndWait(a.login());
        assertThat(jdbc.queryForObject("select STTS_CD from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TABLE_COMMENT_MISSING' and TRGT_SN = ?", String.class,
                a.projectId(), a.table().getId())).isEqualTo("RESOLVED");
    }

    // ------------------------------------------------------------------ 준비 · 보조

    record F(Project project, AccountDto login, TableInfo table, ColumnInfo column) {
        long projectId() {
            return project.getId();
        }
    }

    /** 코멘트·업무 설명이 모두 없는 테이블 1개 + 컬럼 1개 (접두어 없음) */
    private F fixture(String name) {
        Account admin = accountRepository.findAll().stream().filter(x -> "admin".equals(x.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true).account(admin).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        AccountDto login = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        TableInfo table = tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(project.getId()).tableName("TB_CUST").isApproval(true)
                .schema(schemaService.resolveSchema(project.getId(), null)).build()));
        return new F(project, login, table, column(project.getId(), table, "CUST_NO"));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnName(name).dataType("VARCHAR").maxLength(10).isApproval(true).build()));
    }

    private long runAndWait(AccountDto login) {
        long id = runService.request(login);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (inspectionRepository.findRun(login.getProjectId(), id).orElseThrow().status().active()) {
            if (System.nanoTime() > deadline) throw new AssertionError("검사 실행 " + id + " 이 끝나지 않았다");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        return id;
    }

    record Row(long id, String fingerprint, String status, boolean excluded, String lastChange, String endReason, long lastRun, long version) {
    }

    private static final String ROW = "select INSP_RSLT_SN, HASH_VL, STTS_CD, EXCL_YN, LAST_CHG_TYPE_CD, END_RSN_CD, LAST_INSP_EXCN_SN, VER_NO from TB_INSP_RSLT";

    private Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8));
    }

    private Row row(long projectId, StandardizationRule rule, long targetId) {
        return jdbc.queryForObject(ROW + " where PRJ_SN = ? and RULE_CD = ? and TRGT_SN = ?", (rs, i) -> map(rs), projectId, rule.name(), targetId);
    }

    private Row row(long id) {
        return jdbc.queryForObject(ROW + " where INSP_RSLT_SN = ?", (rs, i) -> map(rs), id);
    }

    private long rows(long projectId, StandardizationRule rule) {
        return count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = ? and STTS_CD = 'OPEN'", projectId, rule.name());
    }

    private List<InspectionDto.Finding> findings(long projectId, StandardizationRule rule, InspectionDto.RuleScope scope) {
        return queryService.findings(projectId, new InspectionDto.Search(rule, null, null, null, null, scope, null, null, null, null, null),
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
    }

    private static InspectionDto.RunRule rule(InspectionDto.Run run, StandardizationRule rule) {
        return run.rules().stream().filter(r -> r.rule() == rule).findFirst().orElseThrow();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
