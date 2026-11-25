package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.ColumnNameSuggestionService;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.dictionary.service.TermQualityService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * M-5 잔여 진단 성능 측정 (LEXORMA_PERF=true 일 때만). 세 데이터 규모에서 기존 컬럼 진단(요약·목록)과 신규 진단(모델 요약·목록, 용어 요약·목록, 컬럼 개선 후보)의
 * 시간(워밍업 1회 + 7회의 평균·중앙·최대), 요청당 SQL 문 수(Hibernate PrepareStatementCount), 요청당 할당(현재 스레드), 결과 건수를 잰다.
 * <ul>
 * <li>C10K : 테이블 200 × 컬럼 50 = 컬럼 10,000, 표준 단어 1,000 · 용어 1,000</li>
 * <li>C100K : 테이블 2,000 × 컬럼 50 = 컬럼 100,000, 단어 1,000 · 용어 1,000</li>
 * <li>T10K : 표준 단어 1,000 · 용어 10,000 (2단어 매핑, 50개마다 이음동의어), 테이블 20 × 컬럼 50</li>
 * </ul>
 * 컬럼은 5개 중 1개가 용어 미연결, 10개 중 1개가 컬럼명 불일치, 나머지는 용어 약어 그대로이고, 길이가 도메인과 맞지 않아 모든 컬럼에 도메인 불일치가 난다.
 * 테이블은 10개 중 1개가 접두어 없음이고, 이름 끝의 순번 약어 때문에 모든 테이블에 비표준 단어 약어가 난다 — 결과가 가장 많은(최악) 조건이다.
 * 결과는 표준 출력과 build/m5-quality-perf.txt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class StandardizationQualityPerformanceTest {

    private static final int WORDS = 1_000;
    private static final int COLUMNS_PER_TABLE = 50;

    @Autowired StandardizationQualityService quality;
    @Autowired TermQualityService termQuality;
    @Autowired ColumnNameSuggestionService suggestions;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    void measure() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add("DB : PostgreSQL (테스트 DB — LEXORMA_TEST_POSTGRES_URL)" + " · JVM max heap " + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB");
        report.add(String.format("%-6s | %-30s | %8s | %8s | %8s | %5s | %9s | %s", "data", "case", "avg ms", "median", "max", "SQL", "alloc MB", "rows/total"));
        measure(stats, report, "C10K", 200, 1_000);
        measure(stats, report, "C100K", 2_000, 1_000);
        measure(stats, report, "T10K", 20, 10_000);
        report.forEach(System.out::println);
        Files.write(Path.of("build/m5-quality-perf.txt"), report);
    }

    private void measure(Statistics stats, List<String> report, String label, int tables, int terms) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName("m5perf-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin)
                .dbType(DbType.POSTGRESQL).tablePrefix("TB_").build());
        AccountDto account = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        long started = System.nanoTime();
        Long unlinkedColumnId = seed(project.getId(), tables, terms);
        report.add(String.format("%-6s | (준비 : 테이블 %,d · 컬럼 %,d · 단어 %,d · 용어 %,d — %,d ms)", label, tables, tables * COLUMNS_PER_TABLE, WORDS, terms,
                (System.nanoTime() - started) / 1_000_000));

        var page = PageRequest.of(0, 20);
        report.add(repeat(stats, label, "기존 컬럼 요약 /summary", () -> quality.selectSummary(account)));
        report.add(repeat(stats, label, "기존 컬럼 목록 /data", () -> quality.selectIssues(account, new StandardizationQualityDto.Search(null, null, null, null, null), page)));
        report.add(repeat(stats, label, "모델 요약 /model/summary", () -> quality.selectModelSummary(account)));
        report.add(repeat(stats, label, "모델 목록 /model/data", () -> quality.selectModelIssues(account,
                new StandardizationQualityDto.ModelSearch(null, null, null, null, null, null, null), page)));
        report.add(repeat(stats, label, "용어 요약 /terms/summary", () -> termQuality.selectSummary(account)));
        report.add(repeat(stats, label, "용어 목록 /terms/data", () -> termQuality.selectGroups(account, new TermQualityDto.Search(null, null, null, null), page)));
        report.add(repeat(stats, label, "개선 후보 /columns/{id}", () -> suggestions.suggest(account, unlinkedColumnId).candidates()));
        SecurityTestSupport.clear();
    }

    /** 단어 → 도메인·용어 → 스키마·테이블·컬럼. 컬럼은 테이블 100개씩 나눠 저장한다 (영속성 컨텍스트를 비운다). 개선 후보를 잴 미연결 컬럼 ID 를 돌려준다 */
    private Long seed(long projectId, int tableCount, int termCount) {
        List<Long> wordIds = tx.execute(s -> {
            List<StandardWord> words = new ArrayList<>();
            for (int i = 0; i < WORDS; i++) {
                words.add(StandardWord.builder().projectId(projectId).commonStandardWordName("단어" + i).commonStandardWordAbbreviation("W" + i)
                        .isApproval(i % 10 != 0).synonymList(new ArrayList<>(i % 100 == 1 ? List.of("단어" + (i + 1)) : List.of()))
                        .restrictedWords(new ArrayList<>(i % 100 == 2 ? List.of("단어" + (i + 3)) : List.of())).build());
            }
            return wordRepository.saveAll(words).stream().map(StandardWord::getId).toList();
        });
        List<long[]> termRows = new ArrayList<>(); // [termId, a, b]
        tx.executeWithoutResult(s -> {
            List<StandardDomain> domains = new ArrayList<>();
            for (int d = 0; d < 5; d++) {
                domains.add(domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName("명V" + (d + 1) * 10)
                        .dataType("VARCHAR").dataLength((d + 1) * 10).isApproval(true).build()));
            }
            List<StandardTerm> terms = new ArrayList<>();
            for (int i = 0; i < termCount; i++) {
                int a = i % WORDS;
                int b = (i / WORDS * 7 + i * 3 + 1) % WORDS;
                StandardTerm term = StandardTerm.builder().projectId(projectId).commonStandardTermName("단어" + a + "_단어" + b).commonStandardTermAbbreviation("W" + a + "_W" + b)
                        .standardDomain(domains.get(i % 5)).isApproval(true).termWordMappings(new ArrayList<>())
                        .synonymList(new ArrayList<>(i % 50 == 0 ? List.of("단어" + b + "_단어" + a) : List.of())).build();
                for (int[] word : new int[][]{{a, 1}, {b, 2}}) {
                    StandardTermWordMapping mapping = new StandardTermWordMapping();
                    mapping.setStandardTerm(term);
                    mapping.setStandardWord(entityManager.getReference(StandardWord.class, wordIds.get(word[0])));
                    mapping.setOrderIndex(word[1]);
                    term.getTermWordMappings().add(mapping);
                }
                terms.add(term);
            }
            List<StandardTerm> saved = termRepository.saveAll(terms);
            for (int i = 0; i < saved.size(); i++) {
                termRows.add(new long[]{saved.get(i).getId(), i % WORDS, (i / WORDS * 7 + i * 3 + 1) % WORDS});
            }
        });

        Long schemaId = tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(projectId).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build()).getId());
        Long unlinked = null;
        for (int from = 0; from < tableCount; from += 100) {
            int start = from;
            Long first = tx.execute(s -> {
                Long firstUnlinked = null;
                SchemaInfo schema = entityManager.getReference(SchemaInfo.class, schemaId);
                for (int i = start; i < Math.min(start + 100, tableCount); i++) {
                    long[] tableTerm = termRows.get(i % termRows.size());
                    String name = i % 10 == 0 ? "MBR_W" + tableTerm[1] : i % 10 == 5 ? "TB_X" + i + "_W" + tableTerm[2] : "TB_W" + tableTerm[1] + "_W" + tableTerm[2] + "_" + i % 7;
                    TableInfo table = tableRepository.save(TableInfo.builder().projectId(projectId).schema(schema).tableName(name + "_" + i).tableKorName("성능 " + i)
                            .isApproval(true).isMasterTable(false).build());
                    List<ColumnInfo> columns = new ArrayList<>();
                    for (int c = 0; c < COLUMNS_PER_TABLE; c++) {
                        long[] row = termRows.get((i * COLUMNS_PER_TABLE + c) % termRows.size());
                        boolean noTerm = c % 5 == 0;
                        String abbreviation = "W" + row[1] + "_W" + row[2];
                        String columnName = c % 10 == 3 ? abbreviation + "_X" : abbreviation;
                        // 같은 테이블 안에서 이름이 겹치지 않게 순번을 붙인 컬럼은 미연결 쪽에만 둔다
                        columns.add(ColumnInfo.builder().projectId(projectId).tableInfo(table).tableName(table.getTableName())
                                .columnName(noTerm ? columnName + "_" + c : columnName).columnDesc("단어" + row[1] + " 단어" + row[2])
                                .dataType("VARCHAR").maxLength(c % 7 == 0 ? 999 : 10 * ((int) (row[0] % 5) + 1)).sortOrder(c + 1)
                                .standardTerms(noTerm ? null : entityManager.getReference(StandardTerm.class, row[0]))
                                .isPk(c == 0).isNullable(c != 0).isApproval(true).build());
                    }
                    List<ColumnInfo> saved = columnRepository.saveAll(columns);
                    if (firstUnlinked == null) firstUnlinked = saved.get(0).getId();
                }
                return firstUnlinked;
            });
            if (unlinked == null) unlinked = first;
            entityManager.clear();
        }
        return unlinked;
    }

    /** 워밍업 1회 + 7회 : 평균·중앙·최대 시간, 평균 SQL 문 수·할당 */
    private static String repeat(Statistics stats, String data, String label, Supplier<Object> body) {
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
        String size = last instanceof Page<?> p ? p.getNumberOfElements() + "/" + p.getTotalElements()
                : last instanceof List<?> l ? l.size() + "/-" : last == null ? "-" : summary(last);
        return String.format("%-6s | %-30s | %8.2f | %8.2f | %8.2f | %5d | %9.2f | %s", data, label, times.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0,
                sorted.get(3) / 1000.0, sorted.get(6) / 1000.0, sql / 7, allocated / 7, size);
    }

    private static String summary(Object value) {
        if (value instanceof StandardizationQualityDto.Summary s) return "문제 컬럼 " + s.issueColumns() + "/" + s.totalColumns();
        if (value instanceof StandardizationQualityDto.ModelSummary s) return "문제 컬럼 " + s.columns().issueColumns() + " · 문제 테이블 " + s.issueTables() + "/" + s.totalTables()
                + " · 오류 " + s.errors() + " 주의 " + s.warnings() + " 참고 " + s.infos();
        if (value instanceof TermQualityDto.Summary s) return "그룹 " + s.groups() + " · 진단된 용어 " + s.issueTerms() + "/" + s.totalTerms();
        return value.getClass().getSimpleName();
    }
}
