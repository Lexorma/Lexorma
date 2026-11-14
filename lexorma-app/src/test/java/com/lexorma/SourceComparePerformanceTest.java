package com.lexorma;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import com.lexorma.data.sourcemapping.service.SourceCompareService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.source.connection.controller.SourceConnectionRestController;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [성능] 원본 매핑 상태 계산(M-4c)과 원본·모델 비교(M-4d) : 300 · 1,000 · 3,000 TABLE (TABLE 마다 컬럼 10개, PK, 인덱스 1, 앞 TABLE 로 가는 FK 1).
 * 모든 TABLE 을 매핑하고 10% 는 컬럼 길이를 다르게 둔다. LEXORMA_PERF=true 일 때만 돈다. 서비스 호출 단위로 잰다 (HTTP·JSON 직렬화 시간 제외, 응답 크기는 따로 잰다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class SourceComparePerformanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int COLUMNS = 10;

    @Autowired SourceCompareService compare;
    @Autowired com.lexorma.data.sourcemapping.service.SourceImportService importService;
    @Autowired SourceMappingService mapping;
    @Autowired SourceCatalogReader reader;
    @Autowired SourceCollectionService collections;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired TableRelationRepository relationRepository;
    @Autowired SourceTableMappingRepository tableMappingRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("[성능] 매핑 상태 계산·비교 요약·목록·필터·쪽·상세·이름 변경 후보 : 300 / 1,000 / 3,000 TABLE")
    void scales() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add(String.format("%-6s | %-28s | %9s | %7s | %10s | %10s", "TABLE", "호출", "시간(ms)", "SQL 문", "할당(MB)", "응답(KB)"));
        for (int n : new int[]{300, 1000, 3000}) {
            long prepare = System.nanoTime();
            Env e = prepare(n);
            report.add(String.format("%-6d | %-28s | %9d | %7s | %10s | %10s", n, "(준비 : 수집·모델·매핑)", (System.nanoTime() - prepare) / 1_000_000, "-", "-", "-"));
            SourceCompareDto.Summary summary = compare.summary(e.manager);
            assertThat(summary.comparedTables()).isEqualTo(n);
            assertThat(summary.differentTables()).isEqualTo(n / 10);
            Long connection = summary.base().id();
            Integer order = summary.reflectedOrder();
            String key = compare.tables(e.manager, connection, order, null, "MODIFIED", null, 0, 1).rows().get(0).key();
            report.add(line(stats, n, "M-4c 매핑 현황", () -> mapping.overview(e.manager)));
            report.add(line(stats, n, "M-4c TABLE 목록 (50)", () -> mapping.tables(e.manager, null, null, null, 0, 50)));
            report.add(line(stats, n, "M-4d 요약", () -> compare.summary(e.manager)));
            report.add(line(stats, n, "M-4d 목록 첫 쪽 (50)", () -> compare.tables(e.manager, connection, order, null, null, null, 0, 50)));
            report.add(line(stats, n, "M-4d 목록 마지막 쪽 (50)", () -> compare.tables(e.manager, connection, order, null, null, null, n / 50, 50)));
            report.add(line(stats, n, "M-4d 필터 다름+검색", () -> compare.tables(e.manager, connection, order, null, "MODIFIED", "t_1", 0, 50)));
            report.add(line(stats, n, "M-4d TABLE 상세", () -> compare.table(e.manager, connection, order, key, null)));
            report.add(line(stats, n, "M-4d 상세 컬럼만", () -> compare.table(e.manager, connection, order, key, "COLUMN")));
            report.add(line(stats, n, "M-4d 이름 변경 후보 (50)", () -> compare.renames(e.manager, connection, order, null, null, 0, 50)));
        }
        String text = "\n[M-4c·M-4d 원본 매핑·비교 성능]\n" + String.join("\n", report) + "\n";
        System.out.println(text);
        Files.writeString(Path.of("build", "source-compare-perf.txt"), text);
    }

    @Test
    @DisplayName("[성능] 가져오기 (M-4e) : 대상 조회, 1·10·50 TABLE 계획과 확정 (컬럼 타입 변경), 300 / 3,000 TABLE 프로젝트")
    void importScales() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add(String.format("%-6s | %-28s | %9s | %7s | %10s | %10s", "TABLE", "호출", "시간(ms)", "SQL 문", "할당(MB)", "응답(KB)"));
        for (int n : new int[]{300, 3000}) {
            Env e = prepare(n);
            SourceCompareDto.Summary summary = compare.summary(e.manager);
            Long connection = summary.base().id();
            Integer order = summary.reflectedOrder();
            List<String> keys = compare.tables(e.manager, connection, order, null, "MODIFIED", null, 0, 100).rows().stream().map(SourceCompareDto.TableRow::key).toList();
            report.add(line(stats, n, "가져오기 대상 (TABLE 1)", () -> importService.candidates(e.manager, connection, order, keys.get(0))));
            for (int count : new int[]{1, 10, Math.min(50, keys.size())}) {
                List<com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection> tables = keys.subList(0, count).stream()
                        .map(k -> new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection(k, null, List.of("COLUMN:c_3:TYPE"))).toList();
                var request = new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, null, null);
                report.add(line(stats, n, "계획 (TABLE " + count + ")", () -> importService.plan(e.manager, request)));
            }
            // 확정 : 한 번씩만 (반영하면 차이가 없어진다). 50 → 10 → 1 TABLE 순서로 서로 다른 TABLE
            // 300 TABLE 은 차이 있는 TABLE 이 30개라 확정은 서로 다른 TABLE 로 19·10·1
            int from = 0;
            for (int count : keys.size() >= 61 ? new int[]{50, 10, 1} : new int[]{keys.size() - 11, 10, 1}) {
                List<com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection> tables = keys.subList(from, from + count).stream()
                        .map(k -> new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection(k, null, List.of("COLUMN:c_3:TYPE"))).toList();
                from += count;
                var plan = importService.plan(e.manager, new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, null, null));
                var confirm = new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, plan.planToken(), plan.requestId());
                report.add(once(stats, n, "확정 (TABLE " + count + ")", () -> importService.apply(e.manager, confirm)));
            }
        }
        String text = "\n[M-4e 가져오기 성능]\n" + String.join("\n", report) + "\n";
        System.out.println(text);
        Files.writeString(Path.of("build", "source-import-perf.txt"), text);
    }

    @Autowired com.lexorma.data.history.record.ModelChangeRecorder recorder;
    @Autowired com.lexorma.data.version.service.ModelVersionService versionService;

    @Test
    @DisplayName("[성능] 모델 버전 스냅샷 : 구간별(스냅샷 읽기·JSON·버전 만들기·가져오기 확정) 시간·SQL·할당·GC·유지 Heap, 300 / 1,000 / 3,000 TABLE")
    void snapshotScales() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add(String.format("%-6s | %-22s | %8s | %8s | %8s | %6s | %9s | %7s | %8s", "TABLE", "구간", "평균ms", "중앙ms", "최대ms", "SQL", "할당MB", "GC ms", "유지MB"));
        for (int n : new int[]{300, 1000, 3000}) {
            Env e = prepare(n);
            Long pid = e.project().getId();
            report.add(repeat(stats, n, "스냅샷 읽기", () -> tx.execute(s -> recorder.currentState(pid))));
            Object state = tx.execute(s -> recorder.currentState(pid));
            report.add(repeat(stats, n, "JSON 직렬화", () -> JSON.writeValueAsString(state)));
            int[] seq = {0};
            report.add(repeat(stats, n, "버전 만들기(사용자)", () -> versionService.create(e.manager(),
                    new com.lexorma.data.version.domain.dto.ModelVersionDto.Create("perf-" + n + "-" + (seq[0]++), null))));
            SourceCompareDto.Summary summary = compare.summary(e.manager());
            Long connection = summary.base().id();
            Integer order = summary.reflectedOrder();
            List<String> keys = compare.tables(e.manager(), connection, order, null, "MODIFIED", null, 0, 100).rows().stream().map(SourceCompareDto.TableRow::key).toList();
            int[] next = {0};
            report.add(repeat(stats, n, "가져오기 확정(TABLE 1)", () -> {
                var tables = List.of(new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection(keys.get(next[0]++), null, List.of("COLUMN:c_3:TYPE")));
                var plan = importService.plan(e.manager(), new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, null, null));
                return importService.apply(e.manager(), new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, plan.planToken(), plan.requestId()));
            }));
        }
        String text = "\n[모델 버전 스냅샷 성능]\n" + String.join("\n", report) + "\n";
        System.out.println(text);
        Files.writeString(Path.of("build", "snapshot-perf-" + System.getProperty("lexorma.perf.label", "run") + ".txt"), text);
    }

    @Test
    @DisplayName("[성능] 재가져오기 (M-4f) : 처음 가져와 기준값을 만든 뒤 원본을 다시 수집하고 비교·대상·계획·확정·버전 만들기, 300 / 1,000 / 3,000 TABLE")
    void reimportScales() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add(String.format("%-6s | %-22s | %8s | %8s | %8s | %6s | %9s | %7s | %8s", "TABLE", "구간", "평균ms", "중앙ms", "최대ms", "SQL", "할당MB", "GC ms", "유지MB"));
        for (int n : new int[]{300, 1000, 3000}) {
            Env e = prepare(n);
            SourceCompareDto.Summary first = compare.summary(e.manager());
            Long connection = first.base().id();
            List<String> keys = compare.tables(e.manager(), connection, first.reflectedOrder(), null, "MODIFIED", null, 0, 500).rows().stream()
                    .map(SourceCompareDto.TableRow::key).toList();
            // 처음 가져오기 : 차이 있는 TABLE 의 c_3 타입 (50개씩) → 기준값(실행 이력 + IMPORT 이력)이 생긴다
            for (int from = 0; from < keys.size(); from += 50) {
                var tables = keys.subList(from, Math.min(from + 50, keys.size())).stream()
                        .map(k -> new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection(k, null, List.of("COLUMN:c_3:TYPE"))).toList();
                var plan = importService.plan(e.manager(), new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, first.reflectedOrder(), tables, null, null));
                importService.apply(e.manager(), new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, first.reflectedOrder(), tables, plan.planToken(),
                        plan.requestId()));
            }
            // 원본 재수집 : 같은 TABLE 의 c_3 길이가 50 → 120 (원본만 바뀐 재가져오기 대상)
            List<SourceCollectionSpec.Table> changed = new ArrayList<>();
            for (int i = 0; i < n; i++) changed.add(sourceTable(i, i % 10 == 0 ? 120 : 50));
            ExecutionContext context = ExecutionContext.user(e.manager());
            collections.apply(context, collections.start(context, connection, SourceCollectionSpec.Request.full()).jobId(),
                    new SourceCollectionSpec.Result(List.of(SourceCollectionSpec.Schema.completed(null, Name.of("public"), changed))));
            Integer order = compare.summary(e.manager()).reflectedOrder();
            report.add(repeat(stats, n, "비교 조회(요약+목록)", () -> List.of(compare.summary(e.manager()),
                    compare.tables(e.manager(), connection, order, null, "MODIFIED", null, 0, 100))));
            report.add(repeat(stats, n, "대상 조회(TABLE 1)", () -> importService.candidates(e.manager(), connection, order, keys.get(0))));
            for (int count : new int[]{1, Math.min(50, keys.size())}) {
                var tables = keys.subList(0, count).stream()
                        .map(k -> new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection(k, null, List.of("COLUMN:c_3:TYPE"))).toList();
                var request = new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, null, null);
                report.add(repeat(stats, n, "미리보기(TABLE " + count + ")", () -> importService.plan(e.manager(), request)));
            }
            int[] next = {0};
            report.add(repeat(stats, n, "재가져오기 확정(TABLE 1)", () -> {
                var tables = List.of(new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.TableSelection(keys.get(next[0]++), null, List.of("COLUMN:c_3:TYPE")));
                var plan = importService.plan(e.manager(), new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, null, null));
                if (plan.tables().getFirst().items().getFirst().judgement() != com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Judgement.SOURCE_CHANGED) {
                    throw new IllegalStateException("재가져오기 판정이 원본 변경이 아니다 : " + plan.tables().getFirst().items().getFirst());
                }
                return importService.apply(e.manager(), new com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Request(connection, order, tables, plan.planToken(), plan.requestId()));
            }));
            int[] seq = {0};
            report.add(repeat(stats, n, "버전 만들기(사용자)", () -> versionService.create(e.manager(),
                    new com.lexorma.data.version.domain.dto.ModelVersionDto.Create("reimport-" + n + "-" + (seq[0]++), null))));
        }
        String text = "\n[M-4f 재가져오기 성능]\n" + String.join("\n", report) + "\n";
        System.out.println(text);
        Files.writeString(Path.of("build", "reimport-perf.txt"), text);
    }

    /** 워밍업 1회 + 5회 : 평균·중앙·최대 시간, 그 5회의 평균 SQL·할당·GC 시간, 마지막 호출 뒤 GC 해서 남은 Heap 증가분 */
    private static String repeat(Statistics stats, int n, String label, Supplier<Object> body) {
        body.get();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        List<Long> times = new ArrayList<>();
        long sql = 0;
        double allocated = 0;
        long gcMillis = 0;
        for (int i = 0; i < 5; i++) {
            long gcBefore = gcTime();
            stats.clear();
            long before = threads.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            body.get();
            times.add((System.nanoTime() - started) / 1_000_000);
            allocated += (threads.getThreadAllocatedBytes(thread) - before) / 1024.0 / 1024.0;
            sql += stats.getPrepareStatementCount();
            gcMillis += gcTime() - gcBefore;
        }
        System.gc();
        long heapBefore = usedHeap();
        Object kept = body.get();
        System.gc();
        double retained = (usedHeap() - heapBefore) / 1024.0 / 1024.0;
        java.util.Objects.requireNonNull(kept);
        List<Long> sorted = times.stream().sorted().toList();
        return String.format("%-6d | %-22s | %8.0f | %8d | %8d | %6d | %9.1f | %7d | %8.1f", n, label, times.stream().mapToLong(Long::longValue).average().orElse(0),
                sorted.get(2), sorted.get(4), sql / 5, allocated / 5, gcMillis / 5, retained);
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionTime).sum();
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** 한 번만 잰다 (확정처럼 두 번 할 수 없는 호출) */
    private static String once(Statistics stats, int n, String label, Supplier<Object> body) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        stats.clear();
        long allocated = threads.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        Object result = body.get();
        long millis = (System.nanoTime() - started) / 1_000_000;
        double megabytes = (threads.getThreadAllocatedBytes(thread) - allocated) / 1024.0 / 1024.0;
        return String.format("%-6d | %-28s | %9d | %7d | %10.1f | %10.1f", n, label, millis, stats.getPrepareStatementCount(), megabytes,
                JSON.writeValueAsBytes(result).length / 1024.0);
    }

    /** 첫 호출(JIT·캐시 준비)을 버리고 3회 중 가장 빠른 값. SQL 문 수는 Hibernate 가 준비한 문장 수 */
    private static String line(Statistics stats, int n, String label, Supplier<Object> body) throws Exception {
        body.get();
        long best = Long.MAX_VALUE;
        long statements = 0;
        double megabytes = 0;
        Object result = null;
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 3; i++) {
            stats.clear();
            long allocated = threads.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            result = body.get();
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (millis < best) {
                best = millis;
                statements = stats.getPrepareStatementCount();
                megabytes = (threads.getThreadAllocatedBytes(thread) - allocated) / 1024.0 / 1024.0;
            }
        }
        int bytes = JSON.writeValueAsBytes(result).length;
        return String.format("%-6d | %-28s | %9d | %7d | %10.1f | %10.1f", n, label, best, statements, megabytes, bytes / 1024.0);
    }

    private record Env(Project project, AccountDto manager, Long connection, SchemaInfo schema) {
    }

    private Env prepare(int n) {
        Account owner = tx.execute(s -> accountRepository.save(Account.builder().username("perf-" + UUID.randomUUID().toString().substring(0, 8)).name("perf").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_DBA")))).projectMember(new ArrayList<>()).build()));
        Project project = projectRepository.save(Project.builder().projectName("m4d-perf-" + n + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        AccountDto manager = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_DBA");
        SchemaInfo schema = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("DEFAULT").isDefault(true).build()));
        Long connection = connectionApi.create(manager, new SourceConnectionDto.Request("원본", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null,
                null, null)).getBody().id();
        mapping.setBase(manager, new SourceMappingDto.BaseRequest(connection, null));

        List<SourceCollectionSpec.Table> tables = new ArrayList<>();
        for (int i = 0; i < n; i++) tables.add(sourceTable(i));
        ExecutionContext context = ExecutionContext.user(manager);
        collections.apply(context, collections.start(context, connection, SourceCollectionSpec.Request.full()).jobId(),
                new SourceCollectionSpec.Result(List.of(SourceCollectionSpec.Schema.completed(null, Name.of("public"), tables))));

        // 모델 : 같은 TABLE 들 (10% 는 c_3 길이가 다르다)
        Map<String, Long> modelIds = new HashMap<>();
        for (int from = 0; from < n; from += 500) {
            int start = from;
            tx.executeWithoutResult(s -> {
                SchemaInfo managed = schemaRepository.findById(schema.getId()).orElseThrow();
                for (int i = start; i < Math.min(start + 500, n); i++) {
                    TableInfo table = tableRepository.save(TableInfo.builder().projectId(project.getId()).tableName("T_" + i).tableKorName("테이블 " + i).tableDescription("-")
                            .isMasterTable(false).sortOrder(i).isApproval(false).schema(managed).build());
                    modelIds.put("t_" + i, table.getId());
                    List<ColumnInfo> columns = new ArrayList<>();
                    for (int c = 1; c <= COLUMNS; c++) {
                        String name = c == 1 ? "ID" : c == 2 ? "PARENT_ID" : "C_" + c;
                        String type = c <= 2 ? "BIGINT" : "VARCHAR";
                        Integer length = c <= 2 ? null : (c == 3 && i % 10 == 0 ? 99 : 50);
                        columns.add(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName(table.getTableName()).columnName(name).dataType(type).maxLength(length)
                                .isNullable(c != 1).isRequired(false).isPk(c == 1).isUnique(false).isIndex(false).sortOrder(c).isApproval(false).isMasterData(false)
                                .isSensitive(false).isEncrypted(false).build());
                    }
                    columnRepository.saveAll(columns);
                    TableIndex index = TableIndex.builder().projectId(project.getId()).tableInfo(table).indexName("IX_T_" + i + "_PARENT").isUnique(false)
                            .indexSource(TableIndex.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
                    index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(columns.get(1)).columnOrder(1).build());
                    indexRepository.save(index);
                    if (i > 0) {
                        TableInfo parent = tableRepository.getReferenceById(modelIds.get("t_" + (i - 1)));
                        ColumnInfo parentId = columnRepository.findAllByTableInfo_IdInAndIsPkTrue(List.of(parent.getId())).get(0);
                        TableRelation relation = TableRelation.builder().projectId(project.getId()).sourceTable(table).targetTable(parent).relationName("FK_T_" + i)
                                .relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
                        relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(columns.get(1)).targetColumn(parentId).columnOrder(1).build());
                        relationRepository.save(relation);
                    }
                }
            });
        }
        // 매핑 : 스키마는 서비스로, TABLE 은 측정 준비용으로 한 번에 저장 (서비스의 확정은 건마다 상태를 계산한다)
        SourceMappingDto.Overview overview = mapping.overview(manager);
        mapping.mapSchema(manager, new SourceMappingDto.SchemaRequest(overview.schemas().get(0).sourceSchemaId(), schema.getId()));
        SourceCatalogReader.Snapshot snapshot = reader.latest(project.getId(), connection).orElseThrow();
        tx.executeWithoutResult(s -> tableMappingRepository.saveAll(snapshot.tables().stream().map(t -> SourceTableMapping.builder().projectId(project.getId())
                .connectionId(connection).sourceTableId(t.id()).sourceSchemaId(t.schemaId()).pathKey(t.pathKey()).compareName(t.compareName()).creationCycle(t.creationCycle())
                .reflectedOrder(snapshot.reflectedOrder()).tableId(modelIds.get(t.name())).build()).toList()));
        return new Env(project, manager, connection, schema);
    }

    private static SourceCollectionSpec.Table sourceTable(int i) {
        return sourceTable(i, 50);
    }

    /** @param c3Length c_3 컬럼 길이 (재가져오기 측정에서 원본만 바꾼다) */
    private static SourceCollectionSpec.Table sourceTable(int i, int c3Length) {
        List<SourceCatalogSpec.Column> columns = new ArrayList<>();
        for (int c = 1; c <= COLUMNS; c++) {
            String name = c == 1 ? "id" : c == 2 ? "parent_id" : "c_" + c;
            int length = c == 3 ? c3Length : 50;
            columns.add(new SourceCatalogSpec.Column(Name.of(name), c, c <= 2 ? "bigint" : "character varying(" + length + ")", c <= 2 ? null : length, null, null, c != 1, null,
                    null));
        }
        List<SourceCatalogSpec.Relation> fks = i == 0 ? List.of() : List.of(new SourceCatalogSpec.Relation("fk_t_" + i, null, Name.of("public"), Name.of("t_" + (i - 1)),
                "NO ACTION", "NO ACTION", List.of(new SourceCatalogSpec.ColumnPair(Name.of("parent_id"), Name.of("id")))));
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("t_" + i), SourceTableType.TABLE, null), columns,
                List.of(new SourceCatalogSpec.Constraint("t_" + i + "_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))),
                List.of(new SourceCatalogSpec.Index("t_" + i + "_pkey", "btree", true, List.of(new SourceCatalogSpec.IndexMember(Name.of("id"), null, null)), null),
                        new SourceCatalogSpec.Index("ix_t_" + i + "_parent", "btree", false, List.of(new SourceCatalogSpec.IndexMember(Name.of("parent_id"), null, null)), null)),
                fks);
    }
}
