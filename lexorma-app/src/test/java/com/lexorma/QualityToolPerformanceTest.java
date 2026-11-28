package com.lexorma;

import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.tool.MetadataToolSession;
import com.lexorma.ai.tool.MetadataToolset;
import com.lexorma.ai.tool.QualityToolDefinitions;
import com.lexorma.ai.tool.ToolScope;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.service.InspectionExceptionService;
import com.lexorma.data.inspection.service.InspectionRuleSettingService;
import com.lexorma.data.inspection.service.InspectionRunService;
import com.lexorma.data.schema.service.SchemaService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-7 FINAL 품질 Tool 조회 성능 (LEXORMA_PERF=true 일 때만 — 일반 빌드에서는 건너뜀). 화면 API 가 아니라 AI·MCP 가 쓰는 {@link MetadataToolset#run} 경계
 * (VIEWER 재확인 · 읽기 전용 REPEATABLE READ · 결과 직렬화 · 24,000자 검사) 를 잰다.
 * <p>데이터 : AI-6 성능 시험과 같은 생성 방식(JDBC 일괄 — 테이블 = 컬럼/50, 테이블마다 컬럼 50, 모두 용어 미연결·코멘트·업무 설명 없음) + 같은 단어 구성 용어 200개
 * (큰 그룹) + 이름이 긴 테이블 5개(긴 근거). 검사는 <b>실제 실행기</b>로 돌리고, 상태 변화도 실행기·예외 서비스·규칙 설정 서비스로 만든다. 다만 해결·대상 삭제·재발을
 * 만들기 위한 모델 변경은 JDBC 일괄 UPDATE/DELETE 다 (모델 변경 이력 없음 — 측정 대상이 아니다).
 * <p>규모 : LEXORMA_PERF_TOOL_COLUMNS (기본 1000,10000,100000). 결과는 표준 출력과 build/ai7-tool-perf.txt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class QualityToolPerformanceTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int REPEAT = Integer.parseInt(System.getenv().getOrDefault("LEXORMA_PERF_TOOL_REPEAT", "15"));
    private static final int GROUP_TERMS = 200;

    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired SchemaService schemaService;
    @Autowired InspectionRunService runService;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired InspectionExceptionService exceptions;
    @Autowired InspectionRuleSettingService ruleSettings;
    @Autowired MetadataToolset toolset;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private boolean statements;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    void measure() throws Exception {
        statements = hasStatements();
        List<String> lines = new ArrayList<>();
        lines.add("AI-7 품질 Tool 조회 성능 — PostgreSQL " + jdbc.queryForObject("show server_version", String.class) + ", JVM 최대 힙 "
                + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB, pg_stat_statements=" + statements + ", 반복 " + REPEAT + "회 (첫 호출 따로), JVM "
                + System.getProperty("java.vm.version") + ", autovacuum=" + jdbc.queryForObject("show autovacuum", String.class));
        AccountDto small = null;
        for (String size : System.getenv().getOrDefault("LEXORMA_PERF_TOOL_COLUMNS", "1000,10000,100000").split(",")) {
            int columns = Integer.parseInt(size.trim());
            AccountDto login = project("ai7-perf-" + columns);
            if (small == null) small = login;
            long begin = System.nanoTime();
            generate(login.getProjectId(), columns);
            lines.add("");
            lines.add("== 컬럼 " + columns + " (테이블 " + columns / 50 + " + 긴 이름 5, 그룹 용어 " + GROUP_TERMS + ") — 데이터 생성 " + ms(begin) + "ms");
            lines.addAll(states(login, columns));
            lines.addAll(tools(login, small));
            lines.addAll(freshness(login));
            lines.addAll(afterRepeatedRuns(login));
            lines.addAll(concurrency(login, small));
        }
        String report = String.join("\n", lines);
        System.out.println(report);
        Files.writeString(Path.of("build", "ai7-tool-perf.txt"), report + "\n", StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 상태 구성 (실제 실행기·서비스)

    private long run1;
    private long run2;
    private long run3;

    private List<String> states(AccountDto login, int columns) {
        long pid = login.getProjectId();
        List<String> out = new ArrayList<>();
        long t = System.nanoTime();
        run1 = run(login);                                                         // ① 모두 신규
        out.add("   실행 ① 신규 " + ms(t) + "ms");
        // ② 실제 해결 (컬럼 10% 용어 연결) + 대상 삭제 (컬럼 1% 삭제) + 나머지 유지
        jdbc.update("update TB_COL c set STD_TRM_SN = t.STD_TRM_SN from TB_STD_TRM t where c.PRJ_SN = ? and t.PRJ_SN = c.PRJ_SN and t.TRM_ENG_ABBR_NM = c.COL_NM", pid);
        jdbc.update("delete from TB_COL where PRJ_SN = ? and COL_NM = 'COL_050' and TBL_SN in (select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_NM like 'TB_PERF_%'"
                + " order by TBL_SN limit greatest(1, ? / 5000))", pid, pid, columns);
        t = System.nanoTime();
        run2 = run(login);
        out.add("   실행 ② 해결·삭제 " + ms(t) + "ms");
        // ③ 재발 : 해결한 것의 절반(순서 1~2)을 다시 끊는다
        jdbc.update("update TB_COL set STD_TRM_SN = null where PRJ_SN = ? and COL_NM in ('COL_001', 'COL_002')", pid);
        t = System.nanoTime();
        run3 = run(login);
        out.add("   실행 ③ 재발 " + ms(t) + "ms");
        // 예외 50건 적용 · 그 중 10건 해제 · 한 결과는 처리/해제 6번 (이력 12) — 예외 서비스
        List<long[]> open = jdbc.query("select INSP_RSLT_SN, VER_NO from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'OPEN' and RULE_CD = 'TABLE_COMMENT_MISSING' order by 1 limit 50",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, pid);
        SecurityTestSupport.loginAs(projectRepository, pid, login.getId(), "ROLE_ADMIN");
        for (long[] f : open) exceptions.exclude(login, f[0], new InspectionDto.ExclusionRequest("성능 시험 예외", f[1]));
        for (long[] f : open.subList(0, Math.min(10, open.size()))) {
            exceptions.unexclude(login, f[0], new InspectionDto.ExclusionRequest("성능 시험 해제", version(f[0])));
        }
        long busy = open.getLast()[0];
        for (int i = 0; i < 6; i++) {
            exceptions.unexclude(login, busy, new InspectionDto.ExclusionRequest("반복 해제 " + i, version(busy)));
            exceptions.exclude(login, busy, new InspectionDto.ExclusionRequest("반복 예외 " + i + " " + "사유".repeat(150), version(busy)));
        }
        // 비활성 규칙 (규칙 설정 서비스)
        ruleSettings.update(login, new InspectionDto.RuleSettingsUpdate(List.of(new InspectionDto.RuleChange(StandardizationRule.COLUMN_BIZ_DESCRIPTION_MISSING, false, true))));
        SecurityTestSupport.clear();
        out.add("   결과 " + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", pid) + "건 (OPEN "
                + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'OPEN'", pid) + ", 해결 "
                + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'RESOLVED'", pid) + " — 실제 해결 "
                + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and END_RSN_CD = 'RULE_SATISFIED'", pid) + " · 대상 삭제 "
                + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and END_RSN_CD = 'TARGET_DELETED'", pid) + ", 재발 "
                + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and LAST_CHG_TYPE_CD = 'REOPENED'", pid) + ", 예외 "
                + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and EXCL_YN", pid) + "), 이력 "
                + count("select count(*) from TB_INSP_RSLT_HSTRY where PRJ_SN = ?", pid) + "행, 실행 " + count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", pid)
                + ", 꺼진 규칙 COLUMN_BIZ_DESCRIPTION_MISSING, 결과 테이블 " + jdbc.queryForObject("select pg_size_pretty(pg_total_relation_size('tb_insp_rslt'))", String.class));
        return out;
    }

    private long version(long findingId) {
        return jdbc.queryForObject("select VER_NO from TB_INSP_RSLT where INSP_RSLT_SN = ?", Long.class, findingId);
    }

    // ------------------------------------------------------------------ Tool 별

    private List<String> tools(AccountDto login, AccountDto other) {
        long pid = login.getProjectId();
        List<String> out = new ArrayList<>();
        out.add("   [Tool 별 — 첫 호출 / 중앙값 / p95 / 최대 ms · 응답 문자 · 추정 토큰 · 호출당 SQL · 호출당 할당 KB · 반환/전체]");
        long total = count("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'OPEN' and not EXCL_YN and RULE_CD <> 'COLUMN_BIZ_DESCRIPTION_MISSING'", pid);
        long lastPage20 = Math.min(100_000, Math.max(0, (total - 1) / 20));
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("요약 기본", Map.of());
        cases.put("요약 runId(오래된 ①)", Map.of("runId", run1));
        cases.put("요약 runId(최근 ③)", Map.of("runId", run3));
        cases.put("요약 ruleCode", Map.of("ruleCode", "TERM_NOT_LINKED"));
        cases.put("검색 기본 10", Map.of());
        cases.put("검색 20", Map.of("limit", 20));
        cases.put("검색 마지막 쪽(20건, page " + lastPage20 + ")", Map.of("limit", 20, "page", lastPage20));
        cases.put("검색 규칙", Map.of("rule", "COLUMN_COMMENT_MISSING"));
        cases.put("검색 그룹 TERM", Map.of("group", "TERM"));
        cases.put("검색 심각도 ERROR", Map.of("severity", "ERROR"));
        cases.put("검색 대상 TABLE", Map.of("targetType", "TABLE"));
        cases.put("검색 RESOLVED", Map.of("status", "RESOLVED"));
        cases.put("검색 상태 ALL", Map.of("status", "ALL", "limit", 20));
        cases.put("검색 예외만", Map.of("exclusion", "EXCLUDED"));
        cases.put("검색 예외 포함", Map.of("exclusion", "ALL"));
        cases.put("검색 꺼진 규칙", Map.of("ruleScope", "INACTIVE"));
        cases.put("검색 검색어", Map.of("keyword", "COL_025"));
        cases.put("검색 복합", Map.of("rule", "COLUMN_COMMENT_MISSING", "status", "ALL", "keyword", "TB_PERF_0001", "severity", "WARNING"));
        cases.put("검색 endReason 삭제", Map.of("endReason", "TARGET_DELETED"));
        cases.put("검색 FIRST_SEEN ①(신규 많음)", Map.of("runId", run1, "runRelation", "FIRST_SEEN"));
        cases.put("검색 FIRST_SEEN ③(신규 없음)", Map.of("runId", run3, "runRelation", "FIRST_SEEN"));
        cases.put("검색 RESOLVED_IN ②(해결 많음)", Map.of("runId", run2, "runRelation", "RESOLVED_IN"));
        cases.put("검색 RESOLVED_IN ①(해결 없음)", Map.of("runId", run1, "runRelation", "RESOLVED_IN"));
        cases.put("검색 결과 없음 (0건 · 밖에도 없음 NOT_EXISTS)", Map.of("keyword", "NO_SUCH_TARGET_XYZ"));
        // FINAL-CLOSE (AI7-D44) : 0 건일 때만 기본값만 넓힌 EXISTS 1 문장
        cases.put("0건 · 기본 필터 밖 있음 EXISTS", Map.of("rule", "TERM_NOT_LINKED", "keyword", "COL_003"));
        cases.put("0건 · 명시 OPEN (밖 NOT_EXISTS)", Map.of("rule", "TERM_NOT_LINKED", "keyword", "COL_003", "status", "OPEN"));
        cases.put("0건 · 기본값 없음 NOT_CHECKED", Map.of("keyword", "NO_SUCH_TARGET_XYZ", "status", "ALL", "exclusion", "ALL", "ruleScope", "ALL"));
        for (Map.Entry<String, Map<String, Object>> c : cases.entrySet()) {
            String tool = c.getKey().startsWith("요약") ? QualityToolDefinitions.OVERVIEW : QualityToolDefinitions.SEARCH;
            out.add(measureLine(c.getKey(), login, tool, c.getValue()));
        }
        if (other != null && other.getProjectId() != pid) {
            long otherRun = count("select max(INSP_EXCN_SN) from TB_INSP_EXCN where PRJ_SN = ?", other.getProjectId());
            out.add(measureLine("검색 다른 프로젝트 실행 ID", login, QualityToolDefinitions.SEARCH, Map.of("runId", otherRun, "runRelation", "FIRST_SEEN")));
        }
        Map<String, Long> details = new LinkedHashMap<>();
        details.put("상세 일반(용어 미연결)", first("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TERM_NOT_LINKED' and STTS_CD = 'OPEN' order by 1 limit 1", pid));
        details.put("상세 큰 그룹(용어 " + GROUP_TERMS + ")", first("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TERM_SAME_WORDS' order by 1 limit 1", pid));
        details.put("상세 긴 근거(긴 테이블명 단어)", first("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = 'TABLE_WORD_NOT_STANDARD' and TRGT_CN like '%LONGWORD%' order by 1 limit 1", pid));
        details.put("상세 이력 많음(예외 반복)", first("select INSP_RSLT_SN from TB_INSP_RSLT_HSTRY where PRJ_SN = ? group by INSP_RSLT_SN order by count(*) desc limit 1", pid));
        details.put("상세 실제 해결", first("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ? and END_RSN_CD = 'RULE_SATISFIED' order by 1 limit 1", pid));
        details.put("상세 대상 삭제", first("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ? and END_RSN_CD = 'TARGET_DELETED' order by 1 limit 1", pid));
        details.put("상세 예외", first("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ? and EXCL_YN order by 1 limit 1", pid));
        details.forEach((label, id) -> out.add(measureLine(label, login, QualityToolDefinitions.DETAIL, Map.of("findingId", id))));
        out.add(plans(pid));
        return out;
    }

    private String measureLine(String label, AccountDto login, String tool, Map<String, Object> input) {
        // 첫 호출 (이 조건으로 처음)
        long begin = System.nanoTime();
        String firstResult = call(login, tool, input);
        long firstMs = ms(begin);
        if (statements) jdbc.execute("select pg_stat_statements_reset()");
        long allocBefore = allocated();
        long[] times = new long[REPEAT];
        String result = firstResult;
        for (int i = 0; i < REPEAT; i++) {
            long t = System.nanoTime();
            result = call(login, tool, input);
            times[i] = (System.nanoTime() - t) / 1_000;
        }
        long allocKb = (allocated() - allocBefore) / 1024 / REPEAT;
        double sql = statements ? sqlCalls() / (double) REPEAT : -1;
        Arrays.sort(times);
        String error = result.startsWith("{\"error\"") ? " 오류 " + JSON.readTree(result).path("error").asString() : "";
        String counts = "";
        JsonNode node = error.isEmpty() ? JSON.readTree(result) : null;
        if (node != null && node.has("totalCount")) counts = " · " + node.path("returned").asInt() + "/" + node.path("totalCount").asLong();
        if (node != null && node.has("evidenceLimits")) counts += " · 근거 제한 " + node.path("evidenceLimits");
        if (node != null && node.has("history")) counts += " · 이력 " + node.path("history").path("returned").asInt() + "/" + node.path("history").path("total").asInt();
        if (node != null && node.has("outsideDefaultFilters")) counts += " · 밖 " + node.path("outsideDefaultFilters").path("status").asString();
        return String.format("   %-34s %6d / %6.1f / %6.1f / %6.1f ms · %6d자 · %5d · SQL %4.1f · %6dKB%s%s", label, firstMs, times[REPEAT / 2] / 1000.0,
                times[(int) Math.ceil(REPEAT * 0.95) - 1] / 1000.0, times[REPEAT - 1] / 1000.0, result.length(), TokenEstimator.estimate(result), sql, allocKb, counts, error);
    }

    private String call(AccountDto login, String tool, Map<String, Object> input) {
        MetadataToolSession session = new MetadataToolSession(ExecutionContext.user(login), "perf-" + UUID.randomUUID(), MetadataToolset.TIMEOUT_SECONDS * 1000L,
                ToolScope.QUALITY);
        return toolset.run(tool, input, session);
    }

    /** RUN 관계 조건의 실행 계획 (FIRST_SEEN = IX_INSP_RSLT_04, RESOLVED_IN = 인덱스 없는 LAST_INSP_EXCN_SN) */
    private String plans(long pid) {
        StringBuilder out = new StringBuilder("   실행 계획 (count — Tool 의 전체 건수 조회와 같은 조건) :");
        for (String sql : List.of(
                "select count(*) from TB_INSP_RSLT where PRJ_SN = " + pid + " and STTS_CD = 'OPEN' and EXCL_YN = false and FRST_INSP_EXCN_SN = " + run1,
                "select count(*) from TB_INSP_RSLT where PRJ_SN = " + pid + " and STTS_CD = 'RESOLVED' and EXCL_YN = false and LAST_INSP_EXCN_SN = " + run2,
                "select count(*) from TB_INSP_RSLT where PRJ_SN = " + pid + " and STTS_CD = 'RESOLVED' and EXCL_YN = false and LAST_INSP_EXCN_SN = " + run1)) {
            List<String> plan = jdbc.queryForList("explain (analyze, buffers, costs off, timing on, summary on) " + sql, String.class);
            out.append("\n      ").append(sql).append("\n         ").append(String.join("\n         ", plan));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 최신성 감지

    private List<String> freshness(AccountDto login) {
        long pid = login.getProjectId();
        List<String> out = new ArrayList<>();
        out.add(measureLine("요약(변경 감지 — 검사 뒤 변경 없음)", login, QualityToolDefinitions.OVERVIEW, Map.of()));
        // 검사 뒤 대량 변경 (JDBC, 측정용) : 모델 변경 이력 5,000행 · 사전 용어 전체 수정 · 프로젝트 행 수정
        jdbc.update("insert into TB_MDL_CHG_HSTRY(MDL_CHG_HSTRY_SN, PRJ_SN, CHG_GROUP_ID, TRGT_TYPE_CD, TRGT_SN, TRGT_NM, CHG_TYPE_CD, CHG_SRC_CD, USER_ID, CHG_DT)"
                + " select nextval('SQ_MDL_CHG_HSTRY'), ?, 'perf', 'COLUMN', g, 'X', 'UPDATE', 'USER', 'perf', now() from generate_series(1, 5000) g", pid);
        jdbc.update("update TB_STD_TRM set MDFCN_DT = now() where PRJ_SN = ?", pid);
        jdbc.update("update TB_PRJ set MDFCN_DT = now() where PRJ_SN = ?", pid);
        String line = measureLine("요약(변경 감지 — 대량 변경 뒤)", login, QualityToolDefinitions.OVERVIEW, Map.of());
        JsonNode changes = JSON.readTree(call(login, QualityToolDefinitions.OVERVIEW, Map.of())).path("freshness").path("changes");
        out.add(line + " · " + changes.toString().replaceAll("\"notCovered\":\\[[^]]*],?", "").replaceAll("\"coverage\":\"[A-Z_]+\",?", ""));
        return out;
    }

    // ------------------------------------------------------------------ 반복 검사 직후 · 일반 유지보수 뒤

    private List<String> afterRepeatedRuns(AccountDto login) {
        long pid = login.getProjectId();
        List<String> out = new ArrayList<>();
        out.add("   [반복 검사 직후 · VACUUM 뒤] " + autovacuum());
        for (int i = 4; i <= 5; i++) {
            long t = System.nanoTime();
            run(login);
            out.add("   실행 ④~⑤ 유지 #" + i + " " + ms(t) + "ms → " + autovacuum());
        }
        out.add(measureLine("직후 요약 기본", login, QualityToolDefinitions.OVERVIEW, Map.of()));
        out.add(measureLine("직후 검색 기본", login, QualityToolDefinitions.SEARCH, Map.of()));
        out.add(measureLine("직후 검색 RESOLVED_IN ②", login, QualityToolDefinitions.SEARCH, Map.of("runId", run2, "runRelation", "RESOLVED_IN")));
        // 일반 유지보수 (격리 시험에서 수동 실행 — 운영 코드에는 없다)
        long t = System.nanoTime();
        jdbc.execute("vacuum (analyze) tb_insp_rslt");
        out.add("   수동 VACUUM (ANALYZE) tb_insp_rslt " + ms(t) + "ms → " + autovacuum());
        out.add(measureLine("VACUUM 뒤 요약 기본", login, QualityToolDefinitions.OVERVIEW, Map.of()));
        out.add(measureLine("VACUUM 뒤 검색 기본", login, QualityToolDefinitions.SEARCH, Map.of()));
        out.add(measureLine("VACUUM 뒤 검색 RESOLVED_IN ②", login, QualityToolDefinitions.SEARCH, Map.of("runId", run2, "runRelation", "RESOLVED_IN")));
        return out;
    }

    // ------------------------------------------------------------------ 동시 조회

    private List<String> concurrency(AccountDto login, AccountDto small) throws Exception {
        long pid = login.getProjectId();
        List<String> out = new ArrayList<>();
        Set<Long> mine = new HashSet<>(jdbc.queryForList("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ?", Long.class, pid));
        Set<Long> theirs = new HashSet<>(jdbc.queryForList("select INSP_RSLT_SN from TB_INSP_RSLT where PRJ_SN = ?", Long.class, small.getProjectId()));
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger mixed = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        int threads = 12;
        int calls = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        // 검사 실행 중에 조회 (실제 실행기)
        SecurityTestSupport.loginAs(projectRepository, pid, login.getId(), "ROLE_ADMIN");
        long running = runService.request(login);
        SecurityTestSupport.clear();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            final int worker = w;
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < calls; i++) {
                    boolean other = (worker + i) % 3 == 0 && small.getProjectId() != pid;
                    AccountDto who = other ? small : login;
                    long t = System.nanoTime();
                    try {
                        String result;
                        if (worker % 4 == 3) {
                            // METADATA 범위 Tool (기존) 과 섞는다
                            MetadataToolSession session = new MetadataToolSession(ExecutionContext.user(who), "perf-m-" + UUID.randomUUID(),
                                    MetadataToolset.TIMEOUT_SECONDS * 1000L, ToolScope.METADATA);
                            result = toolset.run("searchTables", Map.of("keyword", "TB_PERF"), session);
                        } else {
                            result = call(who, i % 2 == 0 ? QualityToolDefinitions.SEARCH : QualityToolDefinitions.OVERVIEW, i % 2 == 0 ? Map.of("limit", 20) : Map.of());
                            if (i % 2 == 0) {
                                for (JsonNode f : JSON.readTree(result).path("findings")) {
                                    long id = f.path("findingId").asLong();
                                    if (!(other ? theirs : mine).contains(id)) mixed.incrementAndGet();
                                }
                            }
                        }
                        if (result.startsWith("{\"error\"")) errors.incrementAndGet();
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                    latencies.add((System.nanoTime() - t) / 1_000_000);
                    maxActive.accumulateAndGet(hikari.getHikariPoolMXBean().getActiveConnections(), Math::max);
                }
                return null;
            }));
        }
        long lockWaits = 0;
        long begin = System.nanoTime();
        go.countDown();
        while (futures.stream().anyMatch(f -> !f.isDone())) {
            lockWaits = Math.max(lockWaits, count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database() and ? > 0", 1));
            Thread.sleep(20);
        }
        for (var f : futures) f.get();
        pool.shutdown();
        long wall = ms(begin);
        waitDone(login, running);
        List<Long> sorted = latencies.stream().sorted().toList();
        out.add("   [동시 조회] 스레드 " + threads + " × " + calls + " 호출 (품질 요약·검색 20건 / METADATA searchTables, 1/3 은 다른 프로젝트), 검사 실행 #" + running
                + " 진행 중 시작 — 전체 " + wall + "ms, 중앙값 " + sorted.get(sorted.size() / 2) + "ms, p95 " + sorted.get((int) (sorted.size() * 0.95) - 1)
                + "ms, 최대 " + sorted.getLast() + "ms, 오류 " + errors.get() + ", 다른 프로젝트 결과 혼입 " + mixed.get() + ", 최대 활성 연결 " + maxActive.get() + "/"
                + hikari.getMaximumPoolSize() + ", 잠금 대기 관측 최대 " + lockWaits + ", 실행 상태 "
                + inspectionRepository.findRun(pid, running).orElseThrow().status());
        assertThat(mixed.get()).isZero();
        return out;
    }

    // ------------------------------------------------------------------ 보조

    private long run(AccountDto login) {
        SecurityTestSupport.loginAs(projectRepository, login.getProjectId(), login.getId(), "ROLE_ADMIN");
        long id = runService.request(login);
        SecurityTestSupport.clear();
        waitDone(login, id);
        assertThat(inspectionRepository.findRun(login.getProjectId(), id).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        return id;
    }

    private void waitDone(AccountDto login, long id) {
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        while (inspectionRepository.findRun(login.getProjectId(), id).orElseThrow().status().active()) {
            if (System.nanoTime() > deadline) throw new AssertionError("실행 " + id + " 이 끝나지 않았다");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private AccountDto project(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true)
                .account(admin).tablePrefix("TB_").build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        return AccountDto.builder().id(String.valueOf(admin.getId())).projectId(project.getId()).build();
    }

    /** AI-6 성능 시험과 같은 테이블·컬럼·용어 5개 + 같은 단어 구성 용어 200개 + 이름이 긴 테이블 5개 (JDBC 일괄, 모델 이력 없음) */
    private void generate(long pid, int columns) {
        long schemaId = tx.execute(s -> schemaService.resolveSchema(pid, null).getId());
        tx.executeWithoutResult(s -> {
            jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) select nextval('SQ_TBL'), ?, ?, 'TB_PERF_' || lpad(g::text, 6, '0'), true"
                    + " from generate_series(1, ?) g", pid, schemaId, columns / 50);
            jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, DATA_TYPE_NM, MAX_LEN, SORT_ORD, NULL_ALWNC_YN, APRV_YN)"
                    + " select nextval('SQ_COL'), t.PRJ_SN, t.TBL_SN, t.TBL_NM, 'COL_' || lpad(g::text, 3, '0'), 'VARCHAR', 10, g, true, true"
                    + " from TB_TBL t cross join generate_series(1, 50) g where t.PRJ_SN = ?", pid);
            jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) select nextval('SQ_TBL'), ?, ?, 'TB_' || repeat('LONGWORD_', 20) || g, true"
                    + " from generate_series(1, 5) g", pid, schemaId);
            jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_NM, DATA_TYPE_NM, DATA_LEN, APRV_YN) values (nextval('SQ_STD_DMN'), ?, 1, '성능V10', 'VARCHAR', 10, true)",
                    pid);
            jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN)"
                    + " select nextval('SQ_STD_TRM'), ?, 1, '성능_용어' || g, 'COL_' || lpad(g::text, 3, '0'), d.STD_DMN_SN, true"
                    + " from generate_series(1, 5) g cross join TB_STD_DMN d where d.PRJ_SN = ?", pid, pid);
            // 큰 그룹 : 단어 2개로 된 용어 200개 (TERM_SAME_WORDS 한 묶음)
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, APRV_YN) values (nextval('SQ_STD_WORD'), ?, 1, '성능', 'PRF', true),"
                    + " (nextval('SQ_STD_WORD'), ?, 1, '그룹', 'GRP', true)", pid, pid);
            jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN)"
                    + " select nextval('SQ_STD_TRM'), ?, 1, '성능그룹' || g, 'PRF_GRP_' || g, d.STD_DMN_SN, true from generate_series(1, ?) g cross join TB_STD_DMN d where d.PRJ_SN = ?",
                    pid, GROUP_TERMS, pid);
            jdbc.update("insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD)"
                    + " select nextval('SQ_STD_TRM_WORD'), t.STD_TRM_SN, w.STD_WORD_SN, case when w.WORD_ENG_ABBR_NM = 'PRF' then 1 else 2 end"
                    + " from TB_STD_TRM t cross join TB_STD_WORD w where t.PRJ_SN = ? and w.PRJ_SN = ? and t.TRM_NM like '성능그룹%'", pid, pid);
        });
        jdbc.execute("analyze TB_TBL");
        jdbc.execute("analyze TB_COL");
        jdbc.execute("analyze TB_STD_TRM");
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private long first(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static long ms(long begin) {
        return (System.nanoTime() - begin) / 1_000_000;
    }

    private static long allocated() {
        // 살아 있는 모든 스레드의 누적 할당 합 (조용한 시험 JVM — 측정 구간 차이만 쓴다)
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return Arrays.stream(threads.getThreadAllocatedBytes(threads.getAllThreadIds())).filter(v -> v > 0).sum();
    }

    private String autovacuum() {
        jdbc.execute("select pg_stat_clear_snapshot()");
        return "autovacuum " + jdbc.queryForMap("select autovacuum_count, last_autovacuum, autoanalyze_count, n_dead_tup, n_live_tup from pg_stat_user_tables"
                + " where relid = 'tb_insp_rslt'::regclass");
    }

    private long sqlCalls() {
        String where = " from pg_stat_statements s join pg_database d on d.oid = s.dbid where d.datname = current_database() and query not ilike '%pg_stat_statements%'";
        return jdbc.queryForObject("select coalesce(sum(calls), 0)" + where, Long.class);
    }

    private boolean hasStatements() {
        try {
            jdbc.execute("create extension if not exists pg_stat_statements");
            jdbc.queryForObject("select count(*) from pg_stat_statements", Long.class);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
