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
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.service.SourceCatalogWriter;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원본 카탈로그 수집 반영 성능 (AI-1c). 기본 빌드에서는 돌지 않는다 : {@code LEXORMA_PERF=true ./gradlew :lexorma-app:test --tests '*SourceCollectionPerformanceTest'}.
 * 규모마다 최초 적재 · 변경 없는 재수집 · 1% 변경(컬럼 COMMENT 수정 + 테이블 1개 추가 + 1개 삭제)을 재고, 걸린 시간·SQL 문 수(Hibernate 통계)·
 * 그 스레드가 할당한 바이트·만든 버전·이력 수를 출력한다. 소규모에서는 AI-1b 의 한 건 단위 경로(SourceCatalogWriter)로 같은 내용을 적재한 시간도 잰다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class SourceCollectionPerformanceTest {

    @Autowired SourceCollectionService collections;
    @Autowired SourceCatalogWriter writer;
    @Autowired SourceConnectionRestController connectionApi;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("수집 반영 : 100/1,000 · 1,000/20,000 · 3,000/60,000 (테이블/컬럼)")
    void scales() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> report = new ArrayList<>();
        report.add(String.format("%-14s | %-10s | %9s | %9s | %7s | %7s | %10s", "규모", "단계", "시간(ms)", "SQL 문", "버전", "이력", "할당(MB)"));
        for (int[] scale : new int[][]{{100, 10}, {1000, 20}, {3000, 20}}) {
            int tables = scale[0];
            int perTable = scale[1];
            String label = tables + "/" + tables * perTable;
            Env env = env("perf-" + tables);
            report.add(run(stats, label, "최초 적재", env, () -> result(tables, perTable, 0, false)));
            report.add(run(stats, label, "변경 없음", env, () -> result(tables, perTable, 0, false)));
            report.add(run(stats, label, "1% 변경", env, () -> result(tables, perTable, Math.max(1, tables / 100), true)));
            assertThat(jdbc.queryForObject("select count(*) from TB_SRC_COL c join TB_SRC_TBL t on t.SRC_TBL_SN = c.SRC_TBL_SN where t.SRC_CNTN_SN = ? and c.EXST_YN = true",
                    Integer.class, env.connection)).isEqualTo(tables * perTable);
        }
        // 비교 : AI-1b 의 한 건 단위 경로로 소규모(100/1,000)를 처음 적재
        Env legacy = env("perf-writer");
        report.add(measure(stats, "100/1000", "Writer 적재", () -> {
            SourceSchema schema = writer.saveSchema(legacy.project.getId(), legacy.connection, Name.of("app"), Name.of("public"));
            for (SourceCollectionSpec.Table table : result(100, 10, 0, false).schemas().get(0).tables()) {
                SourceTable saved = writer.saveTable(legacy.project.getId(), schema.getId(), table.table());
                for (SourceCatalogSpec.Column column : table.columns()) writer.saveColumn(legacy.project.getId(), saved.getId(), column);
                writer.replaceConstraints(legacy.project.getId(), saved.getId(), table.constraints());
                writer.replaceIndexes(legacy.project.getId(), saved.getId(), table.indexes());
                writer.replaceRelations(legacy.project.getId(), saved.getId(), table.relations());
            }
            return null;
        }));
        System.out.println("\n[AI-1c 수집 반영 성능]\n" + String.join("\n", report) + "\n");
    }

    private String run(Statistics stats, String label, String step, Env env, Supplier<SourceCollectionSpec.Result> result) {
        SourceCollectionSpec.Result built = result.get();
        String jobId = collections.start(env.context(), env.connection, SourceCollectionSpec.Request.full()).jobId();
        SourceCollectionDto.Execution[] done = new SourceCollectionDto.Execution[1];
        Measured measured = measure(stats, () -> {
            done[0] = collections.apply(env.context(), jobId, built);
            return null;
        });
        assertThat(done[0].status()).isEqualTo("COMPLETED");
        return line(label, step, measured, String.valueOf(done[0].versionCount()), String.valueOf(done[0].changeCount()));
    }

    private static String measure(Statistics stats, String label, String step, Supplier<Object> body) {
        return line(label, step, measure(stats, body), "-", "-");
    }

    private record Measured(long millis, long statements, double megabytes) {
    }

    /** 걸린 시간, 그동안 준비한 SQL 문 수(Hibernate), 이 스레드가 할당한 바이트 */
    private static Measured measure(Statistics stats, Supplier<Object> body) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        stats.clear();
        long allocated = threads.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        body.get();
        long millis = (System.nanoTime() - started) / 1_000_000;
        return new Measured(millis, stats.getPrepareStatementCount(), (threads.getThreadAllocatedBytes(thread) - allocated) / 1024.0 / 1024.0);
    }

    private static String line(String label, String step, Measured measured, String versions, String changes) {
        return String.format("%-14s | %-10s | %9d | %9d | %7s | %7s | %10.1f", label, step, measured.millis(), measured.statements(), versions, changes, measured.megabytes());
    }

    /**
     * public 스키마에 테이블 n 개 (컬럼 perTable 개, PK 1, 2컬럼 인덱스 1, 앞 테이블로 가는 FK 1).
     * changed 개의 테이블은 컬럼 COMMENT 가 다르고, removeAndAdd 면 마지막 테이블을 빼고 새 테이블 하나를 더한다.
     */
    private static SourceCollectionSpec.Result result(int n, int perTable, int changed, boolean removeAndAdd) {
        List<SourceCollectionSpec.Table> tables = new ArrayList<>();
        int last = removeAndAdd ? n - 1 : n;
        for (int t = 0; t < last; t++) tables.add(table("t_" + t, t, perTable, t < changed));
        if (removeAndAdd) tables.add(table("t_new", 0, perTable, false));
        return new SourceCollectionSpec.Result(List.of(SourceCollectionSpec.Schema.completed(Name.of("app"), Name.of("public"), tables)));
    }

    private static SourceCollectionSpec.Table table(String name, int index, int perTable, boolean changed) {
        List<SourceCatalogSpec.Column> columns = new ArrayList<>();
        for (int c = 0; c < perTable; c++) {
            columns.add(new SourceCatalogSpec.Column(Name.of("c_" + c), c + 1, c == 0 ? "bigint" : "character varying(100)", c == 0 ? null : 100, null, null, c != 0, null,
                    "컬럼 " + c + (changed ? " (바뀜)" : "")));
        }
        List<SourceCatalogSpec.Relation> relations = index == 0 ? List.of() : List.of(new SourceCatalogSpec.Relation("fk_" + name, null, null, Name.of("t_" + (index - 1)), null, null,
                List.of(new SourceCatalogSpec.ColumnPair(Name.of("c_1"), Name.of("c_0")))));
        return new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of(name), SourceTableType.TABLE, "테이블 " + name),
                columns, List.of(new SourceCatalogSpec.Constraint(name + "_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("c_0")))),
                List.of(new SourceCatalogSpec.Index("ix_" + name, "btree", false, List.of(Name.of("c_1"), Name.of("c_2")))), relations);
    }

    private record Env(Project project, Long connection, AccountDto owner) {
        ExecutionContext context() {
            return ExecutionContext.user(owner);
        }
    }

    private Env env(String name) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin)
                .dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(admin).isActive(true).build());
        AccountDto owner = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        Long connection = connectionApi.create(owner, new SourceConnectionDto.Request("성능", "POSTGRESQL", "db.internal", null, "app", null, "reader", null, null, null, null, null))
                .getBody().id();
        return new Env(project, connection, owner);
    }
}
