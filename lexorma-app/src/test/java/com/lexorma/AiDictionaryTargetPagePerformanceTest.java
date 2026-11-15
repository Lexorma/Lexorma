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
import com.lexorma.data.ai.dictionary.repository.AiDescriptionProposalRepository;
import com.lexorma.data.ai.dictionary.service.AiDescriptionEvaluationService;
import com.lexorma.data.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * AI-5-PAGE 대상 테이블 목록 성능 측정 (LEXORMA_PERF=true 일 때만) : 30 · 500 · 1,000 · 3,000 테이블(테이블마다 컬럼 5개)에서 첫·중간·마지막 쪽, 검색(뒤쪽 테이블 1건),
 * 업무 설명 빠진 것만 거르기의 시간·SQL 문 수·요청당 할당을 잰다. 비교로 예전 방식(프로젝트 전체 테이블·집계·제안을 읽고 메모리에서 정렬·거르기·500 자르기)을 같은 데이터에서 다시 돌린다.
 * 품질 평가 화면(AI-5-EVAL)의 호출 흐름도 잰다 : 예전 테이블 드롭다운(쪽 없는 targets, 앞 500개)과 지금 흐름(쪽 목록 검색 → 고른 테이블의 평가 후보).
 * 결과는 표준 출력과 build/ai5-page-perf.txt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class AiDictionaryTargetPagePerformanceTest {

    @Autowired AiDictionaryQueryService queries;
    @Autowired AiDescriptionEvaluationService evaluation;
    @Autowired AiDescriptionProposalRepository proposals;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void measure() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add(String.format("%-6s | %-26s | %8s | %8s | %8s | %6s | %9s | %s", "tables", "case", "avg ms", "median", "max", "SQL", "alloc MB", "rows/total"));
        for (int n : new int[]{30, 500, 1000, 3000}) {
            Account viewer = account();
            Project project = projectRepository.save(Project.builder().projectName("ai5pp-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(viewer)
                    .dbType(DbType.POSTGRESQL).build());
            projectMemberRepository.save(ProjectMember.builder().project(project).account(viewer).isActive(true).build());
            long seeded = System.nanoTime();
            seed(project.getId(), n);
            report.add(String.format("%-6d | (준비 %d 테이블 · %d 컬럼 : %d ms)", n, n, n * 5, (System.nanoTime() - seeded) / 1_000_000));
            ExecutionContext ctx = ExecutionContext.user(AccountDto.builder().id(String.valueOf(viewer.getId())).projectId(project.getId()).build());
            int pages = (n + 29) / 30;
            String far = String.format("PT%05d", n - 2);
            report.add(repeat(stats, n, "첫 쪽", () -> queries.targetTables(ctx, false, null, 0, 30)));
            report.add(repeat(stats, n, "중간 쪽", () -> queries.targetTables(ctx, false, null, pages / 2, 30)));
            report.add(repeat(stats, n, "마지막 쪽", () -> queries.targetTables(ctx, false, null, pages - 1, 30)));
            report.add(repeat(stats, n, "검색 (뒤쪽 1건)", () -> queries.targetTables(ctx, false, far, 0, 30)));
            report.add(repeat(stats, n, "업무 설명 빠진 것만", () -> queries.targetTables(ctx, true, null, 0, 30)));
            report.add(repeat(stats, n, "예전 방식 (전체·메모리)", () -> legacy(project.getId(), null)));
            report.add(repeat(stats, n, "예전 방식 검색", () -> legacy(project.getId(), far)));
            Long farId = queries.targetTables(ctx, false, far, 0, 30).getContent().getFirst().tableId();
            report.add(repeat(stats, n, "평가: 예전 드롭다운 (500)", () -> queries.targets(ctx, null, false, null)));
            report.add(repeat(stats, n, "평가: 검색 → 후보", () -> {
                queries.targetTables(ctx, false, far, 0, 30);
                return evaluation.candidates(ctx, farId);
            }));
            report.add(repeat(stats, n, "평가: 고른 테이블 후보만", () -> evaluation.candidates(ctx, farId)));
        }
        String text = "\n[AI-5-PAGE 대상 테이블 목록 성능]\n" + String.join("\n", report) + "\n";
        System.out.println(text);
        Files.writeString(Path.of("build", "ai5-page-perf.txt"), text);
    }

    /** 예전 targets(tableId 없음) 의 데이터 접근을 그대로 : 전체 테이블 엔티티 + 프로젝트 전체 컬럼 집계 + 프로젝트 전체 제안 → 정렬·거르기·500 */
    private Object legacy(Long projectId, String keyword) {
        return tx.execute(s -> {
            String kw = keyword == null ? null : keyword.toUpperCase(Locale.ROOT);
            columnRepository.countBizDescriptionsByTable(projectId);
            proposals.findAllByProjectId(projectId);
            return tableRepository.findAll((root, q, cb) -> cb.equal(root.get("projectId"), projectId)).stream()
                    .sorted(Comparator.comparing(TableInfo::getTableName))
                    .filter(t -> kw == null || t.getTableName().toUpperCase(Locale.ROOT).contains(kw))
                    .limit(500).map(TableInfo::getId).toList();
        });
    }

    private void seed(Long projectId, int n) {
        tx.executeWithoutResult(s -> {
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(projectId).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            List<TableInfo> tables = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                tables.add(TableInfo.builder().projectId(projectId).schema(schema).tableName(String.format("PT%05d", i)).tableKorName("성능 " + i).tableDescription("성능")
                        .bizDescription(i % 3 == 0 ? "업무 설명" : null).isApproval(false).isMasterTable(false).build());
            }
            tableRepository.saveAll(tables);
            List<ColumnInfo> columns = new ArrayList<>();
            for (TableInfo t : tables) {
                for (int c = 0; c < 5; c++) {
                    columns.add(ColumnInfo.builder().projectId(projectId).tableInfo(t).tableName(t.getTableName()).columnName("C" + c).columnDesc("컬럼")
                            .dataType("BIGINT").sortOrder(c + 1).bizDescription(c == 0 ? "업무" : null).isPk(c == 0).isNullable(c != 0).isApproval(false).build());
                }
            }
            columnRepository.saveAll(columns);
        });
    }

    private Account account() {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai5pp-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName("ROLE_VIEWER")))).projectMember(new ArrayList<>()).build()));
    }

    /** 워밍업 1회 + 7회 : 평균·중앙·최대 시간, 평균 SQL 문 수·할당 */
    private static String repeat(Statistics stats, int n, String label, Supplier<Object> body) {
        body.get();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        List<Long> times = new ArrayList<>();
        long sql = 0;
        double allocated = 0;
        Object last = null;
        for (int i = 0; i < 7; i++) {
            stats.clear();
            long before = threads.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            last = body.get();
            times.add((System.nanoTime() - started) / 1_000);
            allocated += (threads.getThreadAllocatedBytes(thread) - before) / 1024.0 / 1024.0;
            sql += stats.getPrepareStatementCount();
        }
        List<Long> sorted = times.stream().sorted().toList();
        String size = last instanceof org.springframework.data.domain.Page<?> p ? p.getNumberOfElements() + "/" + p.getTotalElements()
                : last instanceof List<?> l ? l.size() + "/-" : "-";
        return String.format("%-6d | %-26s | %8.2f | %8.2f | %8.2f | %6d | %9.2f | %s", n, label, times.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0,
                sorted.get(3) / 1000.0, sorted.get(6) / 1000.0, sql / 7, allocated / 7, size);
    }
}
