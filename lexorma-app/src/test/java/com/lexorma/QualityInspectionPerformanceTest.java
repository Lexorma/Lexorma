package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.service.InspectionHooks;
import com.lexorma.data.inspection.service.InspectionRunService;
import com.lexorma.data.schema.service.SchemaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장 품질 검사 기초 성능 측정 (AI-6 2차) — LEXORMA_PERF=true 일 때만 (일반 빌드에서는 건너뜀).
 * 규모(LEXORMA_PERF_INSPECTION_COLUMNS, 기본 1000,10000,100000) : 테이블 = 컬럼 / 50, 테이블마다 컬럼 50.
 * 모든 컬럼이 용어 미연결(결과 1건/컬럼), 모든 테이블이 비표준 단어(결과 1건/테이블)인 결과가 많은 조건이다. AI-6 3차부터 규칙 20종이라
 * 코멘트·업무 설명이 없는 테이블·컬럼마다 완전성 결과 2건이 더 나온다 (결과 ≈ 컬럼 × 3 + 테이블 × 3 — 2차의 16규칙 측정 컬럼 × 1 + 테이블 × 1 과 조건이 같고 건수가 다르다).
 * 실행 3번 : ① 처음(모두 신규) ② 같은 데이터(모두 유지) ③ 컬럼 10% 를 맞는 용어에 연결(10% 해결).
 * 실행마다 : 전체 시간(요청 → 확정), 단계별 시간(판정 묶음·기존 결과 읽기·대상 확인·계획·저장 단계, 트랜잭션 시간), SQL 수(pg_stat_statements 가 있으면),
 * 실행기 스레드 할당량, 힙 최대 점유(측정 구간의 힙 풀 peak — 할당 누적과 다르다), GC 횟수·시간. 결과는 표준 출력과 build/ai6-inspection-perf.txt.
 * <p>{@link #storage()} (2차 최종 보완, LEXORMA_PERF_STORAGE=true 일 때만) : 같은 조건(기본 컬럼 10만)으로 반복 실행하며 검사 테이블의 저장 공간을 시점별로 잰다 —
 * 본체·TOAST·인덱스·전체 크기(중복 합산 없이 나눠서), pgstattuple 의 실제 live/dead 튜플·빈 공간, pg_stat_user_tables 의 추정값·삽입/갱신/HOT 갱신·자동 VACUUM,
 * 근거 JSONB 크기. 일반 VACUUM 뒤 크기와 그 다음 실행의 공간 재사용을 본다 (VACUUM FULL·REINDEX·설정 변경 없음). 결과는 build/ai6-inspection-storage.txt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
@Import(QualityInspectionPerformanceTest.Phases.class)
class QualityInspectionPerformanceTest {

    @TestConfiguration
    static class Phases {
        static final Map<Long, Map<String, Long>> BY_RUN = new ConcurrentHashMap<>();
        static final Map<Long, CountDownLatch> DONE = new ConcurrentHashMap<>();

        @Bean
        InspectionHooks phaseHooks() {
            return new InspectionHooks() {
                @Override
                public void afterRun(long runId, Map<String, Long> phases) {
                    BY_RUN.put(runId, new LinkedHashMap<>(phases));
                    DONE.computeIfAbsent(runId, id -> new CountDownLatch(1)).countDown();
                }

                @Override
                public void beforeStore(long runId) {
                    histogram(runId, "판정 직후(저장 전)");
                }

                @Override
                public void beforeComplete(long runId) {
                    histogram(runId, "저장 끝(확정 직전)");
                }
            };
        }

        /** 프로파일 모드(LEXORMA_PERF_PROFILE=true) : 그 시점의 살아 있는 객체(전체 GC 뒤)의 유형별 상위 — 측정 시간이 달라지므로 일반 측정과 나눈다 */
        static final Map<Long, List<String>> HISTOGRAMS = new ConcurrentHashMap<>();

        static void histogram(long runId, String label) {
            if (!"true".equals(System.getenv("LEXORMA_PERF_PROFILE"))) return;
            try {
                javax.management.ObjectName name = new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
                String text = (String) ManagementFactory.getPlatformMBeanServer().invoke(name, "gcClassHistogram", new Object[]{new String[0]},
                        new String[]{String[].class.getName()});
                List<String> lines = text.lines().toList();
                String total = lines.stream().filter(l -> l.startsWith("Total")).findFirst().orElse("");
                List<String> out = new ArrayList<>();
                out.add("   [힙 히스토그램 " + label + "] " + total.trim());
                lines.stream().skip(2).limit(18).forEach(l -> out.add("      " + l.trim()));
                HISTOGRAMS.computeIfAbsent(runId, id -> new java.util.concurrent.CopyOnWriteArrayList<>()).addAll(out);
            } catch (Exception e) {
                HISTOGRAMS.computeIfAbsent(runId, id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add("   [힙 히스토그램 실패] " + e);
            }
        }
    }

    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired SchemaService schemaService;
    @Autowired InspectionRunService runService;
    @Autowired InspectionRepository inspectionRepository;
    @Autowired com.lexorma.data.inspection.service.InspectionQueryService queryService;
    @Autowired com.lexorma.home.service.DashboardService dashboardService;
    @Autowired TransactionTemplate tx;
    @Autowired com.lexorma.system.project.service.ProjectManageService projectManageService;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    void measure() throws Exception {
        // 저장 한 문장의 행 수 비교 (LEXORMA_PERF_STORE_CHUNK, 기본 = 운영 값) — 설정이 아니라 이 시험만 바꾼다
        String chunk = System.getenv("LEXORMA_PERF_STORE_CHUNK");
        if (chunk != null) org.springframework.test.util.ReflectionTestUtils.setField(inspectionRepository, "storeChunk", Integer.parseInt(chunk));
        String sizes = System.getenv().getOrDefault("LEXORMA_PERF_INSPECTION_COLUMNS", "1000,10000,100000");
        boolean statements = hasStatements();
        List<String> lines = new ArrayList<>();
        lines.add("AI-6 저장 품질 검사 성능 (규칙 20종) — PostgreSQL " + jdbc.queryForObject("show server_version", String.class) + ", JVM 최대 힙 "
                + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB, pg_stat_statements=" + statements + ", 저장 묶음 "
                + org.springframework.test.util.ReflectionTestUtils.getField(inspectionRepository, "storeChunk") + "행, JVM " + System.getProperty("java.vm.version")
                + ", GC " + ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList());
        for (String size : sizes.split(",")) {
            int columns = Integer.parseInt(size.trim());
            AccountDto login = project("ai6-perf-" + columns);
            long generated = System.nanoTime();
            generate(login.getProjectId(), columns);
            lines.add("");
            lines.add("== 컬럼 " + columns + " (테이블 " + columns / 50 + ") — 데이터 생성 " + (System.nanoTime() - generated) / 1_000_000 + "ms");
            lines.add(measured("① 신규", login, statements));
            lines.add(measured("② 재진단(유지)", login, statements));
            fix(login.getProjectId());
            lines.add(measured("③ 10% 해결", login, statements));
            // 반복 실행 뒤 메모리가 쌓이는지 (LEXORMA_PERF_EXTRA_RUNS, 기본 0) : 같은 데이터의 유지 실행을 더 돌린다
            int extra = Integer.parseInt(System.getenv().getOrDefault("LEXORMA_PERF_EXTRA_RUNS", "0"));
            for (int i = 1; i <= extra; i++) lines.add(measured("④ 반복 유지 " + i, login, statements));
            lines.add(queries(login));
            lines.add("   결과 행 " + jdbc.queryForObject("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", Long.class, login.getProjectId())
                    + ", 미해결 " + jdbc.queryForObject("select count(*) from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'OPEN'", Long.class, login.getProjectId())
                    + ", 결과 테이블 크기 " + jdbc.queryForObject("select pg_size_pretty(pg_total_relation_size('tb_insp_rslt'))", String.class));
        }
        String report = String.join("\n", lines);
        System.out.println(report);
        Files.writeString(Path.of("build", "ai6-inspection-perf.txt"), report + "\n", StandardCharsets.UTF_8);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "LEXORMA_PERF_STORAGE", matches = "true")
    void storage() throws Exception {
        int columns = Integer.parseInt(System.getenv().getOrDefault("LEXORMA_PERF_STORAGE_COLUMNS", "100000"));
        boolean statements = hasStatements();
        jdbc.execute("create extension if not exists pgstattuple");
        List<String> lines = new ArrayList<>();
        lines.add("AI-6 2차 최종 보완 저장 공간 분석 — PostgreSQL " + jdbc.queryForObject("show server_version", String.class) + ", JVM 최대 힙 "
                + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MB, autovacuum=" + jdbc.queryForObject("show autovacuum", String.class)
                + ", 컬럼 " + columns + " (테이블 " + columns / 50 + "), 검사 테이블은 이 측정 전용 빈 DB 기준");
        // 저장 구조 후보 (AI-6 FINAL, 격리된 측정 DB 에만) : 검사 테이블에 적용할 DDL — 예) drop index IX_INSP_RSLT_05; alter table TB_INSP_RSLT set (fillfactor = 90)
        String ddl = System.getenv().getOrDefault("LEXORMA_PERF_STORAGE_DDL", "");
        for (String statement : ddl.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement.trim());
        }
        lines.add("   저장 구조 DDL : " + (ddl.isBlank() ? "(없음 — V4 그대로)" : ddl) + ", 결과 테이블 옵션 "
                + jdbc.queryForObject("select coalesce(array_to_string(reloptions, ','), '(기본 fillfactor 100)') from pg_class where oid = 'tb_insp_rslt'::regclass", String.class)
                + ", 인덱스 " + jdbc.queryForList("select indexrelid::regclass::text from pg_index where indrelid = 'tb_insp_rslt'::regclass order by 1", String.class));
        // 실행 사이 간격 (LEXORMA_PERF_STORAGE_INTERVAL_SEC, 기본 0) : autovacuum(기본 naptime 60초)이 실제로 돌 시간을 준다
        int interval = Integer.parseInt(System.getenv().getOrDefault("LEXORMA_PERF_STORAGE_INTERVAL_SEC", "0"));
        AccountDto login = project("ai6-storage-" + columns);
        generate(login.getProjectId(), columns);
        lines.add(storageLine("0 검사 전"));
        for (int run = 1; run <= 10; run++) {
            if (run > 1 && interval > 0) Thread.sleep(interval * 1000L);
            String timing = measured("실행 " + run, login, statements);
            lines.add(timing);
            if (run == 1 || run == 2 || run == 3 || run == 5 || run == 10) {
                lines.add(storageLine(run + "회 뒤"));
            } else {
                lines.add("      " + autovacuum());
            }
        }
        fix(login.getProjectId());
        lines.add(measured("실행 11 (10% 해결)", login, statements));
        lines.add(storageLine("일부 해결 뒤"));
        jdbc.execute("vacuum (verbose false) TB_INSP_RSLT");
        jdbc.execute("vacuum (verbose false) TB_INSP_EXCN");
        jdbc.execute("vacuum (verbose false) TB_INSP_EXCN_RULE");
        jdbc.execute("vacuum (verbose false) TB_INSP_EXCN_RSLT");
        jdbc.execute("vacuum (verbose false) TB_INSP_RSLT_HSTRY");
        lines.add(storageLine("일반 VACUUM 뒤"));
        lines.add(measured("실행 12 (VACUUM 뒤, 같은 데이터)", login, statements));
        lines.add(storageLine("VACUUM 뒤 1회"));
        lines.add(measured("실행 13 (VACUUM 뒤 2회째)", login, statements));
        lines.add(storageLine("VACUUM 뒤 2회"));
        lines.add(queries(login));
        lines.add(plans(login.getProjectId()));
        lines.add(deleteProject(login));
        String report = String.join("\n", lines);
        System.out.println(report);
        Files.writeString(Path.of("build", "ai6-inspection-storage.txt"), report + "\n", StandardCharsets.UTF_8);
    }

    /**
     * 시점 1개 : 검사 결과 테이블(TB_INSP_RSLT)은 본체(main fork)·TOAST(테이블+그 인덱스)·FSM/VM·인덱스를 나눠 적고, 나머지 검사 테이블은 전체 크기만.
     * 통계 뷰는 백엔드가 쉴 때 늦게(최대 약 10초) 모이므로 12초 기다린 뒤 읽는다 — 정확한 값은 pgstattuple 과 count(*) 다.
     */
    private String storageLine(String label) throws InterruptedException {
        Thread.sleep(12_000);
        jdbc.execute("select pg_stat_clear_snapshot()");
        StringBuilder out = new StringBuilder("   [" + label + "]");
        Map<String, Object> size = jdbc.queryForMap("select pg_relation_size(c.oid) main, pg_table_size(c.oid) table_size, pg_indexes_size(c.oid) indexes,"
                + " pg_total_relation_size(c.oid) total, coalesce(pg_total_relation_size(nullif(c.reltoastrelid, 0)), 0) toast,"
                + " pg_relation_size(c.oid, 'fsm') + pg_relation_size(c.oid, 'vm') fsm_vm from pg_class c where c.oid = 'tb_insp_rslt'::regclass");
        out.append(" 결과 테이블 : 본체 ").append(mb(size.get("main"))).append(", TOAST ").append(mb(size.get("toast"))).append(", FSM+VM ").append(mb(size.get("fsm_vm")))
                .append(", 인덱스 ").append(mb(size.get("indexes"))).append(", 전체 ").append(mb(size.get("total")))
                .append(" (table_size ").append(mb(size.get("table_size"))).append(" = 본체+TOAST+FSM/VM)");
        Map<String, Object> exact = jdbc.queryForMap("select count(*) rows, count(*) filter (where STTS_CD = 'OPEN') open, count(*) filter (where STTS_CD = 'RESOLVED') resolved,"
                + " round(avg(pg_column_size(RSLT_EVDNC_CN))) evidence_avg, round(avg(pg_column_size(r.*))) row_avg from TB_INSP_RSLT r");
        out.append("\n      행(count) ").append(exact.get("rows")).append(" (OPEN ").append(exact.get("open")).append(", RESOLVED ").append(exact.get("resolved"))
                .append("), 근거 JSONB 평균 ").append(exact.get("evidence_avg")).append("B, 행 평균 ").append(exact.get("row_avg")).append("B");
        Map<String, Object> tuple = jdbc.queryForMap("select tuple_count, dead_tuple_count, pg_size_pretty(dead_tuple_len) dead_len, pg_size_pretty(free_space) free, free_percent"
                + " from pgstattuple('tb_insp_rslt')");
        out.append("\n      pgstattuple (실제) : live ").append(tuple.get("tuple_count")).append(", dead ").append(tuple.get("dead_tuple_count")).append(" (")
                .append(tuple.get("dead_len")).append("), 빈 공간 ").append(tuple.get("free")).append(" (").append(tuple.get("free_percent")).append("%)");
        // PostgreSQL 16+ 은 다른 페이지로 옮겨 간 갱신(n_tup_newpage_upd)도 센다 — 15 에는 없다
        boolean newpage = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from information_schema.columns where table_name = 'pg_stat_user_tables'"
                + " and column_name = 'n_tup_newpage_upd')", Boolean.class));
        Map<String, Object> stat = jdbc.queryForMap("select n_live_tup, n_dead_tup, n_tup_ins, n_tup_upd, n_tup_hot_upd, " + (newpage ? "n_tup_newpage_upd, " : "")
                + "n_tup_del, autovacuum_count, vacuum_count, autoanalyze_count, last_autovacuum,"
                + " round(100.0 * n_tup_hot_upd / nullif(n_tup_upd, 0), 1) hot_pct from pg_stat_user_tables where relid = 'tb_insp_rslt'::regclass");
        out.append("\n      pg_stat_user_tables (추정·누적) : ").append(stat);
        List<String> indexes = jdbc.queryForList("select i.relname || '=' || pg_size_pretty(pg_relation_size(i.oid)) from pg_index x join pg_class i on i.oid = x.indexrelid"
                + " where x.indrelid = 'tb_insp_rslt'::regclass order by i.relname", String.class);
        out.append("\n      인덱스별 : ").append(String.join(", ", indexes));
        List<String> others = jdbc.queryForList("select c.relname || ' ' || pg_size_pretty(pg_total_relation_size(c.oid)) || ' (행 ' || coalesce(s.n_live_tup, 0) || ', dead '"
                + " || coalesce(s.n_dead_tup, 0) || ')' from pg_class c left join pg_stat_user_tables s on s.relid = c.oid where c.relname in"
                + " ('tb_insp_excn', 'tb_insp_excn_rule', 'tb_insp_excn_rslt', 'tb_insp_rslt_hstry') order by c.relname", String.class);
        out.append("\n      다른 검사 테이블 (전체 크기) : ").append(String.join(", ", others));
        out.append(" — 정확한 행 수 : 실행 ").append(jdbc.queryForObject("select count(*) from TB_INSP_EXCN", Long.class))
                .append(", 실행 규칙 ").append(jdbc.queryForObject("select count(*) from TB_INSP_EXCN_RULE", Long.class))
                .append(", 실행 건수 ").append(jdbc.queryForObject("select count(*) from TB_INSP_EXCN_RSLT", Long.class))
                .append(", 이력 ").append(jdbc.queryForObject("select count(*) from TB_INSP_RSLT_HSTRY", Long.class));
        return out.toString();
    }

    /**
     * 실행 이력 누적과 프로젝트 삭제 (AI-6 FINAL 최종 보완 AI6-FC-05, LEXORMA_PERF_DELETION=true 일 때만). 다른 프로젝트(컬럼 10만·결과 30.6만)가 남아 있는 DB 에서
     * 삭제할 프로젝트(컬럼 10만)를 실제 검사 13회 하고, LEXORMA_PERF_DELETION_RUNS(기본 13)까지는 <b>시험용으로 만든</b> 지난 실행(COMPLETED, 실행 규칙 20행·변화 건수 120행 —
     * 실제 실행과 같은 모양, 결과 행이 참조하지 않는다)을 더한 뒤 실제 프로젝트 삭제 서비스로 지운다. 문장별 시간·FK 확인(참조 무결성 내부 문장, pg_stat_statements track=all)·
     * 결과 테이블 순차 스캔 수·다른 프로젝트 조회 지연·남은 행을 적는다. 저장 구조 비교는 LEXORMA_PERF_STORAGE_DDL (예 : 기존 구조 A 재현).
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "LEXORMA_PERF_DELETION", matches = "true")
    void deletion() throws Exception {
        int runs = Integer.parseInt(System.getenv().getOrDefault("LEXORMA_PERF_DELETION_RUNS", "13"));
        String ddl = System.getenv().getOrDefault("LEXORMA_PERF_STORAGE_DDL", "");
        for (String statement : ddl.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement.trim());
        }
        boolean statements = hasStatements();
        List<String> lines = new ArrayList<>();
        lines.add("AI-6 실행 이력 누적과 프로젝트 삭제 — PostgreSQL " + jdbc.queryForObject("show server_version", String.class) + ", 실행 " + runs + "회, 구조 DDL "
                + (ddl.isBlank() ? "(V4 그대로 = D)" : ddl) + ", 결과 테이블 옵션 "
                + jdbc.queryForObject("select coalesce(array_to_string(reloptions, ','), '(fillfactor 100)') from pg_class where oid = 'tb_insp_rslt'::regclass", String.class)
                + ", 인덱스 " + jdbc.queryForList("select indexrelid::regclass::text from pg_index where indrelid = 'tb_insp_rslt'::regclass order by 1", String.class)
                + ", track=" + jdbc.queryForObject("show pg_stat_statements.track", String.class));
        AccountDto other = project("ai6-del-other");
        generate(other.getProjectId(), 100_000);
        lines.add(measured("다른 프로젝트 검사 (남겨 둘 결과)", other, statements));
        AccountDto target = project("ai6-del-target");
        generate(target.getProjectId(), 100_000);
        int real = Math.min(13, runs);
        for (int i = 1; i <= real; i++) {
            String line = measured("삭제할 프로젝트 실제 검사 " + i, target, statements);
            if (i == 1 || i == real) lines.add(line);
        }
        long projectId = target.getProjectId();
        if (runs > real) {
            // 시험용 지난 실행 : 마지막 실제 실행과 같은 행 모양 (실행 1 + 그 실행의 실행 규칙·변화 건수 행을 그대로 복사), 상태 COMPLETED, 진행 표시 없음, 결과가 참조하지 않는다
            int extra = runs - real;
            long account = Long.parseLong(target.getId());
            long lastReal = jdbc.queryForObject("select max(INSP_EXCN_SN) from TB_INSP_EXCN where PRJ_SN = ?", Long.class, projectId);
            tx.executeWithoutResult(st -> {
                jdbc.update("insert into TB_INSP_EXCN(INSP_EXCN_SN, PRJ_SN, PRGRS_PRJ_SN, ACNT_SN, EXCN_STTS_CD, DMND_DT, BGNG_DT, CRTR_DT, END_DT, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                        + " select nextval('SQ_INSP_EXCN'), ?, null, ?, 'COMPLETED', now() - (g || ' day')::interval, now() - (g || ' day')::interval, now() - (g || ' day')::interval,"
                        + " now() - (g || ' day')::interval, now(), now(), 'perf', 'perf' from generate_series(1, ?) g", projectId, account, extra);
                jdbc.update("insert into TB_INSP_EXCN_RULE(INSP_EXCN_RULE_SN, INSP_EXCN_SN, RULE_CD, EXCN_STTS_CD, RSLT_CNT, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                        + " select nextval('SQ_INSP_EXCN_RULE'), e.INSP_EXCN_SN, r.RULE_CD, r.EXCN_STTS_CD, r.RSLT_CNT, now(), now(), 'perf', 'perf' from TB_INSP_EXCN e"
                        + " cross join TB_INSP_EXCN_RULE r where e.PRJ_SN = ? and e.RGTR_ID = 'perf' and r.INSP_EXCN_SN = ?", projectId, lastReal);
                jdbc.update("insert into TB_INSP_EXCN_RSLT(INSP_EXCN_RSLT_SN, INSP_EXCN_SN, RULE_CD, CHG_TYPE_CD, RSLT_CNT, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                        + " select nextval('SQ_INSP_EXCN_RSLT'), e.INSP_EXCN_SN, r.RULE_CD, r.CHG_TYPE_CD, r.RSLT_CNT, now(), now(), 'perf', 'perf' from TB_INSP_EXCN e"
                        + " cross join TB_INSP_EXCN_RSLT r where e.PRJ_SN = ? and e.RGTR_ID = 'perf' and r.INSP_EXCN_SN = ?", projectId, lastReal);
            });
            jdbc.execute("analyze TB_INSP_EXCN");
            jdbc.execute("analyze TB_INSP_EXCN_RULE");
            jdbc.execute("analyze TB_INSP_EXCN_RSLT");
        }
        jdbc.execute("analyze TB_INSP_RSLT");
        lines.add("   삭제 전 : 삭제할 프로젝트 실행 " + count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", projectId) + "건(실제 " + real + "·시험용 " + (runs - real)
                + "), 실행 규칙 " + count("select count(*) from TB_INSP_EXCN_RULE r join TB_INSP_EXCN e on e.INSP_EXCN_SN = r.INSP_EXCN_SN where e.PRJ_SN = ?", projectId)
                + ", 변화 건수 " + count("select count(*) from TB_INSP_EXCN_RSLT r join TB_INSP_EXCN e on e.INSP_EXCN_SN = r.INSP_EXCN_SN where e.PRJ_SN = ?", projectId)
                + ", 결과 " + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", projectId) + ", 이력 " + count("select count(*) from TB_INSP_RSLT_HSTRY where PRJ_SN = ?", projectId)
                + " / 다른 프로젝트 결과 " + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", other.getProjectId()));

        // 삭제 중 다른 프로젝트 조회 (다른 스레드·다른 연결)
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<Long> otherLatency = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger otherErrors = new java.util.concurrent.atomic.AtomicInteger();
        Thread reader = new Thread(() -> {
            var all = new com.lexorma.data.inspection.domain.dto.InspectionDto.Search(null, null, null, null, null, null, null, null, null, null, null);
            while (!stop.get()) {
                long begin = System.nanoTime();
                try {
                    queryService.summary(other.getProjectId());
                    queryService.findings(other.getProjectId(), all, org.springframework.data.domain.PageRequest.of(0, 10));
                } catch (RuntimeException e) {
                    otherErrors.incrementAndGet();
                }
                otherLatency.add((System.nanoTime() - begin) / 1_000_000);
            }
        });
        if (statements) jdbc.execute("select pg_stat_statements_reset()");
        long seqBefore = seqScans();
        var projectRow = projectRepository.findById(projectId).orElseThrow();
        projectManageService.updateProject(com.lexorma.core.project.domain.dto.ProjectDto.builder().id(projectId).projectName(projectRow.getProjectName())
                .projectManagerId(Long.valueOf(target.getId())).isActive(false).isAutoActive(true).isDicAbbrUsed(true).tablePrefix("TB_").build(), true);
        reader.start();
        Thread.sleep(300);
        long begin = System.nanoTime();
        projectManageService.deleteProject(projectId, target);
        long deleteMs = (System.nanoTime() - begin) / 1_000_000;
        Thread.sleep(300);
        stop.set(true);
        reader.join();
        Thread.sleep(1500); // 통계가 공유 메모리로 모이는 시간
        long seqAfter = seqScans();
        lines.add("   프로젝트 삭제 (실제 서비스, 한 트랜잭션 = 프로젝트 행 잠금 유지 시간) : " + deleteMs + "ms, 결과 테이블 순차 스캔 " + (seqAfter - seqBefore) + "회");
        if (statements) {
            String where = " from pg_stat_statements s join pg_database d on d.oid = s.dbid where d.datname = current_database()";
            jdbc.query("select calls, round(total_exec_time::numeric, 1) ms, rows, left(regexp_replace(query, '\\s+', ' ', 'g'), 110) q" + where
                    + " and (query ilike '%tb_insp%' or query ilike '%TB_INSP%') and query not ilike '%pg_stat%' order by total_exec_time desc limit 12", rs -> {
                lines.add("      " + rs.getLong(1) + "회 " + rs.getBigDecimal(2) + "ms 행 " + rs.getLong(3) + " : " + rs.getString(4));
            });
        }
        List<Long> sorted = new ArrayList<>(otherLatency);
        java.util.Collections.sort(sorted);
        lines.add("   삭제 중 다른 프로젝트 조회(요약+목록) " + sorted.size() + "회 : 중앙값 " + (sorted.isEmpty() ? "-" : sorted.get(sorted.size() / 2)) + "ms, 최대 "
                + (sorted.isEmpty() ? "-" : sorted.get(sorted.size() - 1)) + "ms, 오류 " + otherErrors.get());
        lines.add("   남은 행 (삭제한 프로젝트) : 결과 " + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", projectId) + ", 실행 "
                + count("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", projectId) + ", 이력 " + count("select count(*) from TB_INSP_RSLT_HSTRY where PRJ_SN = ?", projectId)
                + ", 실행 규칙·건수(고아) " + count("select (select count(*) from TB_INSP_EXCN_RULE r where not exists (select 1 from TB_INSP_EXCN e where e.INSP_EXCN_SN = r.INSP_EXCN_SN))"
                + " + (select count(*) from TB_INSP_EXCN_RSLT r where not exists (select 1 from TB_INSP_EXCN e where e.INSP_EXCN_SN = r.INSP_EXCN_SN)) where ? = ?", 1, 1)
                + " / 다른 프로젝트 결과 " + count("select count(*) from TB_INSP_RSLT where PRJ_SN = ?", other.getProjectId()));
        String report = String.join("\n", lines);
        System.out.println(report);
        Files.writeString(Path.of("build", "ai6-inspection-deletion.txt"), report + "\n", StandardCharsets.UTF_8);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long seqScans() {
        jdbc.execute("select pg_stat_clear_snapshot()");
        return jdbc.queryForObject("select seq_scan from pg_stat_user_tables where relid = 'tb_insp_rslt'::regclass", Long.class);
    }

    /** autovacuum 이 돌았는지 (실행 사이 시점) */
    private String autovacuum() {
        jdbc.execute("select pg_stat_clear_snapshot()");
        return "autovacuum " + jdbc.queryForMap("select autovacuum_count, last_autovacuum, n_dead_tup, n_tup_upd, n_tup_hot_upd from pg_stat_user_tables"
                + " where relid = 'tb_insp_rslt'::regclass");
    }

    /** 인덱스 후보 판단용 실행 계획 : 저장의 예외 건수 조회, 실행 행을 지울 때의 FK 확인(참조하는 쪽 조회 — PostgreSQL 이 내부에서 쓰는 모양) */
    private String plans(long projectId) {
        long run = jdbc.queryForObject("select max(INSP_EXCN_SN) from TB_INSP_EXCN where PRJ_SN = ?", Long.class, projectId);
        StringBuilder out = new StringBuilder("   실행 계획 :");
        for (String sql : List.of(
                "select RULE_CD, count(*) from TB_INSP_RSLT where PRJ_SN = " + projectId + " and LAST_INSP_EXCN_SN = " + run + " and STTS_CD = 'OPEN' and EXCL_YN = true group by RULE_CD",
                "select 1 from only TB_INSP_RSLT x where " + run + " = LAST_INSP_EXCN_SN and " + projectId + " = PRJ_SN for key share of x",
                "select 1 from only TB_INSP_RSLT x where " + run + " = FRST_INSP_EXCN_SN and " + projectId + " = PRJ_SN for key share of x")) {
            List<String> plan = jdbc.queryForList("explain (analyze, buffers, costs off, timing on, summary on) " + sql, String.class);
            out.append("\n      ").append(sql).append("\n         ").append(String.join("\n         ", plan));
        }
        return out.toString();
    }

    /** 실제 프로젝트 삭제 서비스 (비활성화 → 삭제) 시간과 남은 검사 행 */
    private String deleteProject(AccountDto login) {
        long projectId = login.getProjectId();
        var project = projectRepository.findById(projectId).orElseThrow();
        projectManageService.updateProject(com.lexorma.core.project.domain.dto.ProjectDto.builder().id(projectId).projectName(project.getProjectName())
                .projectManagerId(Long.valueOf(login.getId())).isActive(false).isAutoActive(true).isDicAbbrUsed(true).tablePrefix("TB_").build(), true);
        long begin = System.nanoTime();
        projectManageService.deleteProject(projectId, login);
        long ms = (System.nanoTime() - begin) / 1_000_000;
        long left = jdbc.queryForObject("select (select count(*) from TB_INSP_RSLT where PRJ_SN = ?) + (select count(*) from TB_INSP_EXCN where PRJ_SN = ?)"
                + " + (select count(*) from TB_INSP_RSLT_HSTRY where PRJ_SN = ?)", Long.class, projectId, projectId, projectId);
        return "   프로젝트 삭제 (실제 서비스, 컬럼·결과 포함) : " + ms + "ms, 남은 검사 행 " + left;
    }

    private static String mb(Object bytes) {
        return String.format("%.1fMB", ((Number) bytes).longValue() / 1024.0 / 1024.0);
    }

    /**
     * 조회 시간 (AI-6 3차 화면이 부르는 서비스 그대로, 각 5번의 중앙값) : 현재 요약(대시보드 카드와 같다), 결과 목록 기본 조건 첫 쪽·마지막 쪽, 필터(대상 구분 + 규칙 + 해결 포함),
     * 실행 이력 첫 쪽, 대시보드 실시간 통계. 규칙별 미해결 건수도 함께 적는다.
     */
    private String queries(AccountDto login) {
        long projectId = login.getProjectId();
        StringBuilder out = new StringBuilder("   조회(중앙값 ms) :");
        out.append(" 요약 ").append(median(() -> queryService.summary(projectId)));
        var all = new com.lexorma.data.inspection.domain.dto.InspectionDto.Search(null, null, null, null, null, null, null, null, null, null, null);
        long total = queryService.findings(projectId, all, org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements();
        out.append(", 목록 첫 쪽 ").append(median(() -> queryService.findings(projectId, all, org.springframework.data.domain.PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("severity")))));
        int last = (int) Math.max(0, (total - 1) / 10);
        out.append(", 목록 마지막 쪽(").append(last).append(") ").append(median(() -> queryService.findings(projectId, all, org.springframework.data.domain.PageRequest.of(last, 10,
                org.springframework.data.domain.Sort.by("severity")))));
        var filter = new com.lexorma.data.inspection.domain.dto.InspectionDto.Search(com.lexorma.data.dictionary.quality.StandardizationRule.COLUMN_COMMENT_MISSING, null, null,
                com.lexorma.data.inspection.domain.dto.InspectionDto.StatusFilter.ALL, null, null, null, null, "COL_0", "MODEL", null);
        out.append(", 필터(대상 구분·규칙·상태 전체·이름 검색) ").append(median(() -> queryService.findings(projectId, filter, org.springframework.data.domain.PageRequest.of(0, 10))));
        out.append(", 실행 이력 첫 쪽 ").append(median(() -> queryService.runs(projectId, org.springframework.data.domain.PageRequest.of(0, 10))));
        out.append(", 대시보드 실시간 통계 ").append(median(() -> dashboardService.selectStats(login)));
        out.append("\n   결과 목록 전체 ").append(total).append("건, 규칙별 미해결 : ");
        jdbc.query("select RULE_CD, count(*) from TB_INSP_RSLT where PRJ_SN = ? and STTS_CD = 'OPEN' group by RULE_CD order by RULE_CD", rs -> {
            out.append(rs.getString(1)).append('=').append(rs.getLong(2)).append(' ');
        }, projectId);
        return out.toString();
    }

    private static long median(Runnable work) {
        long[] times = new long[5];
        for (int i = 0; i < times.length; i++) {
            long begin = System.nanoTime();
            work.run();
            times[i] = (System.nanoTime() - begin) / 1_000_000;
        }
        java.util.Arrays.sort(times);
        return times[2];
    }

    private String measured(String label, AccountDto login, boolean statements) throws Exception {
        if (statements) jdbc.execute("select pg_stat_statements_reset()");
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
        System.gc();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        long gcCount = gcCount();
        long gcTime = gcTime();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long executorThread = executorThreadId();
        long allocated = executorThread < 0 ? 0 : threads.getThreadAllocatedBytes(executorThread);

        long walBefore = wal();
        GcWatch watch = GcWatch.start();
        long before = usedHeap();
        long begin = System.nanoTime();
        long runId = runService.request(login);
        CountDownLatch done = Phases.DONE.computeIfAbsent(runId, id -> new CountDownLatch(1));
        assertThat(done.await(30, TimeUnit.MINUTES)).as("실행 완료").isTrue();
        long wall = (System.nanoTime() - begin) / 1_000_000;
        assertThat(inspectionRepository.findRun(login.getProjectId(), runId).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);

        GcWatch.Result gc = watch.stop();
        long walMb = (wal() - walBefore) / 1024 / 1024;
        long thread = executorThread < 0 ? executorThreadId() : executorThread;
        long allocatedMb = thread < 0 ? -1 : (threads.getThreadAllocatedBytes(thread) - allocated) / 1024 / 1024;
        long peakMb = heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum() / 1024 / 1024;
        // 클라이언트가 보낸 문장과 FK 검사(참조 무결성 트리거가 내부에서 실행하는 SELECT 1 … FOR KEY SHARE)를 나눠 센다
        String where = " from pg_stat_statements s join pg_database d on d.oid = s.dbid where d.datname = current_database() and query not ilike '%pg_stat_statements%'";
        long sql = statements ? jdbc.queryForObject("select coalesce(sum(calls), 0)" + where + " and query not like 'SELECT 1 FROM ONLY %'", Long.class) : -1;
        long fkChecks = statements ? jdbc.queryForObject("select coalesce(sum(calls), 0)" + where + " and query like 'SELECT 1 FROM ONLY %'", Long.class) : -1;
        Map<String, Long> phases = Phases.BY_RUN.get(runId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("select CHG_TYPE_CD, sum(RSLT_CNT) from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ? group by CHG_TYPE_CD order by 1",
                rs -> {
                    counts.put(rs.getString(1), rs.getInt(2));
                }, runId);
        System.gc();
        long after = usedHeap();
        String result = "   " + label + " : 전체 " + wall + "ms, 단계 " + phases + ", 변화 " + counts + ", SQL " + sql + "건(+FK 검사 " + fkChecks + "), WAL " + walMb
                + "MB(서버 전체 — 이 DB 서버는 측정 전용), 실행기 할당 " + allocatedMb
                + "MB, 힙 최대 점유(풀 최대 합) " + peakMb + "MB, GC 직후 점유 최대 " + gc.maxAfterGcMb() + "MB (GC " + gc.count() + "회, 최장 정지 " + gc.maxPauseMs()
                + "ms), GC " + (gcCount() - gcCount) + "회/" + (gcTime() - gcTime) + "ms, 실행 전 사용 " + before / 1024 / 1024 + "MB → 실행 뒤 전체 GC 사용 "
                + after / 1024 / 1024 + "MB, RSS " + rssMb() + "MB";
        List<String> histograms = Phases.HISTOGRAMS.remove(runId);
        return histograms == null ? result : result + "\n" + String.join("\n", histograms);
    }

    private AccountDto project(String name) {
        Account admin = accountRepository.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true)
                .account(admin).tablePrefix("TB_").build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    /** 테이블 n/50 개 × 컬럼 50 (JDBC 일괄, 모델 이력 없음 — 측정 대상이 아니다). 용어 5개(약어 COL_001~005, 도메인 VARCHAR(10))만 만들고 아직 연결하지 않는다 */
    private void generate(long projectId, int columns) {
        long schemaId = tx.execute(s -> schemaService.resolveSchema(projectId, null).getId());
        tx.executeWithoutResult(s -> {
            jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) select nextval('SQ_TBL'), ?, ?, 'TB_PERF_' || lpad(g::text, 6, '0'), true"
                    + " from generate_series(1, ?) g", projectId, schemaId, columns / 50);
            jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, DATA_TYPE_NM, MAX_LEN, SORT_ORD, NULL_ALWNC_YN, APRV_YN)"
                    + " select nextval('SQ_COL'), t.PRJ_SN, t.TBL_SN, t.TBL_NM, 'COL_' || lpad(g::text, 3, '0'), 'VARCHAR', 10, g, true, true"
                    + " from TB_TBL t cross join generate_series(1, 50) g where t.PRJ_SN = ?", projectId);
            jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_NM, DATA_TYPE_NM, DATA_LEN, APRV_YN) values (nextval('SQ_STD_DMN'), ?, 1, '성능V10', 'VARCHAR', 10, true)",
                    projectId);
            jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN)"
                    + " select nextval('SQ_STD_TRM'), ?, 1, '성능_용어' || g, 'COL_' || lpad(g::text, 3, '0'), d.STD_DMN_SN, true"
                    + " from generate_series(1, 5) g cross join TB_STD_DMN d where d.PRJ_SN = ?", projectId, projectId);
        });
        jdbc.execute("analyze TB_TBL");
        jdbc.execute("analyze TB_COL");
    }

    /** 컬럼 10%(순서 1~5) 를 같은 약어의 용어에 연결 → 용어 미연결 해결 */
    private void fix(long projectId) {
        jdbc.update("update TB_COL c set STD_TRM_SN = t.STD_TRM_SN from TB_STD_TRM t where c.PRJ_SN = ? and t.PRJ_SN = c.PRJ_SN and t.TRM_ENG_ABBR_NM = c.COL_NM", projectId);
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

    private static long executorThreadId() {
        return Arrays.stream(ManagementFactory.getThreadMXBean().dumpAllThreads(false, false))
                .filter(t -> t.getThreadName().startsWith("quality-inspection-")).mapToLong(java.lang.management.ThreadInfo::getThreadId).findFirst().orElse(-1);
    }

    /** 측정 중 GC 알림 : GC 직후 힙 점유(살아 있는 객체 + 아직 거두지 않은 old 영역 — 상한)의 최대, 횟수, 최장 정지 */
    private static final class GcWatch implements javax.management.NotificationListener {
        record Result(long maxAfterGcMb, int count, long maxPauseMs) {
        }

        private final java.util.concurrent.atomic.AtomicLong maxAfter = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong maxPause = new java.util.concurrent.atomic.AtomicLong();

        static GcWatch start() {
            GcWatch watch = new GcWatch();
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                ((javax.management.NotificationEmitter) bean).addNotificationListener(watch, null, null);
            }
            return watch;
        }

        Result stop() {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                try {
                    ((javax.management.NotificationEmitter) bean).removeNotificationListener(this);
                } catch (javax.management.ListenerNotFoundException ignored) {
                    // 등록되지 않은 빈
                }
            }
            return new Result(maxAfter.get() / 1024 / 1024, count.get(), maxPause.get());
        }

        @Override
        public void handleNotification(javax.management.Notification notification, Object handback) {
            if (!com.sun.management.GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
            var info = com.sun.management.GarbageCollectionNotificationInfo.from((javax.management.openmbean.CompositeData) notification.getUserData());
            // G1 의 동시 표시(Concurrent) 주기는 정지가 아니다 — 정지 횟수·시간에서 뺀다
            if (!info.getGcName().contains("Concurrent")) {
                count.incrementAndGet();
                maxPause.accumulateAndGet(info.getGcInfo().getDuration(), Math::max);
            }
            long used = info.getGcInfo().getMemoryUsageAfterGc().entrySet().stream()
                    .filter(e -> ManagementFactory.getMemoryPoolMXBeans().stream().anyMatch(p -> p.getName().equals(e.getKey()) && p.getType() == MemoryType.HEAP))
                    .mapToLong(e -> e.getValue().getUsed()).sum();
            maxAfter.accumulateAndGet(used, Math::max);
        }
    }

    /** 서버의 현재 WAL 위치(LSN) — 차이 = 그사이 만든 WAL (측정 전용 서버라 다른 쓰기는 이 측정뿐이다. PostgreSQL 15·18 공통) */
    private long wal() {
        return jdbc.queryForObject("select pg_current_wal_lsn() - '0/0'::pg_lsn", java.math.BigDecimal.class).longValue();
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long rssMb() {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid())).start();
            return Long.parseLong(new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()) / 1024;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }
}
