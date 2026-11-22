package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 컬럼 단건 등록(insertColumn) 성능 — 측정용이라 LEXORMA_PERF=true 일 때만 실행한다. 테이블 행 잠금(M-7 물리명 일괄 적용 2차 후속) 전후 비교용.
 * 단건 등록 지연(같은 테이블 200회 순차의 중앙값·p95), 같은 테이블 2스레드 × 100 / 다른 테이블 2스레드 × 100 의 총 시간.
 * 결과는 build/reports/column-insert-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class ColumnInsertPerformanceTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ColumnManageService columns;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("단건 등록 지연, 같은 테이블·다른 테이블 2스레드 동시 등록")
    void measure() throws Exception {
        GenerationFixture f = GenerationFixture.create(context, "cperf-" + System.nanoTime(), DbType.POSTGRESQL);
        long a = f.entity("고객");
        f.pk(a, f.attr(a, "고객 번호", true, "CUST_NO"));
        long b = f.entity("주문");
        f.pk(b, f.attr(b, "주문 번호", true, "ORD_NO"));
        long c = f.entity("상품");
        f.pk(c, f.attr(c, "상품 번호", true, "PRDT_NO"));
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(a, b, c)));
        generation.apply(f.user, f.request(List.of(a, b, c), plan.planToken()));
        long ta = f.table(a), tb = f.table(b), tc = f.table(c);
        long term = f.term("PERF_COL", "VARCHAR", 20);

        for (int i = 0; i < 20; i++) insert(f, tc, "W" + i, term); // 데우기
        long[] single = new long[200];
        for (int i = 0; i < single.length; i++) {
            long t0 = System.nanoTime();
            insert(f, ta, "S" + i, term);
            single[i] = (System.nanoTime() - t0) / 1_000;
        }
        Arrays.sort(single);
        long sameTable = parallel(f, tb, tb, "P", term);
        long otherTables = parallel(f, tb, tc, "Q", term);
        List<String> lines = new ArrayList<>();
        lines.add("single insert median µs | p95 µs | same table 2×100 ms | different tables 2×100 ms");
        lines.add(single[single.length / 2] + " | " + single[(int) (single.length * 0.95)] + " | " + sameTable + " | " + otherTables);
        System.out.println("PERF|" + lines.get(1));
        Path out = Path.of("build/reports/column-insert-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private long parallel(GenerationFixture f, long first, long second, String prefix, long term) throws Exception {
        SecurityContext login = SecurityContextHolder.getContext();
        long t0 = System.nanoTime();
        CompletableFuture<Void> x = CompletableFuture.runAsync(() -> run(login, () -> {
            for (int i = 0; i < 100; i++) insert(f, first, prefix + "A" + i, term);
        }));
        CompletableFuture<Void> y = CompletableFuture.runAsync(() -> run(login, () -> {
            for (int i = 0; i < 100; i++) insert(f, second, prefix + "B" + i, term);
        }));
        CompletableFuture.allOf(x, y).get();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static void run(SecurityContext login, Runnable r) {
        SecurityContextHolder.setContext(login);
        try {
            r.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void insert(GenerationFixture f, long table, String name, long term) {
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(table);
        dto.setTermId(term);
        dto.setColumnName(name);
        dto.setColumnDesc(name);
        dto.setDataType("VARCHAR");
        dto.setMaxLength(20);
        for (String yn : List.of("Pk", "MasterData", "Required", "Sensitive", "Unique", "Index", "Encrypted")) set(dto, yn, "N");
        dto.setIsNullable("Y");
        columns.insertColumn(f.user, dto);
    }

    private static void set(ColumnInfoDto dto, String field, String value) {
        try {
            ColumnInfoDto.class.getMethod("setIs" + field, String.class).invoke(dto, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
