package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import jakarta.persistence.EntityManagerFactory;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 컬럼명 UNIQUE (M7-U01, V5) 전후 비교용 측정 — LEXORMA_PERF=true 일 때만 실행. 고치기 전 코드에서도 그대로 돌도록 공개 서비스 API 만 쓴다.
 * 컬럼 300개 테이블에 : 단건 등록 200회(중앙값·p95 µs, 1회당 준비 문장 수), 일괄 저장 새 행 50개(5회 중앙값 ms), 일괄 저장 20개 삭제 + 같은 이름 20개 추가(5회 중앙값),
 * 엑셀 1,000행(테이블 10개 × 100행, 3회 중앙값). 결과는 build/reports/column-name-unique-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class ColumnNameUniquePerformanceTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ColumnManageService columns;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private GenerationFixture f;
    private long term;

    private long schemaId;
    private int tables;

    /** 컬럼 300개(직접 SQL 로 채운 승인 전 컬럼 299개 + PK 1개)를 가진 새 승인 테이블. 스키마는 처음 한 번 논리 → 물리 생성으로 만든 것을 쓴다 */
    private long table(String ignored) {
        if (schemaId == 0) {
            long entity = f.entity("고객");
            f.pk(entity, f.attr(entity, "고객 번호", true, "CUST_NO"));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(List.of(entity)));
            generation.apply(f.user, f.request(List.of(entity), plan.planToken()));
            schemaId = jdbc.queryForObject("select SCHM_SN from TB_TBL where TBL_SN = ?", Long.class, f.table(entity));
        }
        long table = GenerationFixture.nextId();
        String tableName = "PERF_T" + (++tables);
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, TBL_KORN_NM, APRV_YN, SORT_ORD, MDFCN_DT) values (?, ?, ?, ?, ?, true, ?, current_timestamp)",
                table, f.user.getProjectId(), schemaId, tableName, tableName, tables);
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{GenerationFixture.nextId(), f.user.getProjectId(), table, tableName, "PK_ID", 1, true});
        for (int i = 0; i < 299; i++) rows.add(new Object[]{GenerationFixture.nextId(), f.user.getProjectId(), table, tableName, "FILL_" + i, i + 2, false});
        jdbc.batchUpdate("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, SORT_ORD, PK_YN, APRV_YN, NULL_ALWNC_YN, MDFCN_DT)"
                + " values (?, ?, ?, ?, ?, 'fill', 'VARCHAR', 20, ?, ?, false, true, current_timestamp)", rows);
        return table;
    }

    @Test
    @DisplayName("단건 등록·일괄 저장·삭제 후 재등록·엑셀 업로드 (컬럼 300개 테이블)")
    void measure() throws Exception {
        f = GenerationFixture.create(context, "cuperf-" + System.nanoTime(), DbType.POSTGRESQL);
        term = f.term("PERF_COL", "VARCHAR", 20);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> lines = new ArrayList<>();

        long single = table("단건");
        for (int i = 0; i < 20; i++) insert(single, "W" + i);
        long[] times = new long[200];
        stats.clear();
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            insert(single, "S" + i);
            times[i] = (System.nanoTime() - t0) / 1_000;
        }
        long perInsert = stats.getPrepareStatementCount() / times.length;
        Arrays.sort(times);
        lines.add("insert median µs=" + times[times.length / 2] + " p95 µs=" + times[(int) (times.length * 0.95)] + " statements/insert=" + perInsert);

        long[] bulk = new long[5];
        long bulkStatements = 0;
        for (int r = 0; r < bulk.length; r++) {
            long t = table("일괄" + r);
            List<Long> terms = new ArrayList<>();
            for (int i = 0; i < 50; i++) terms.add(f.term("B" + r + "_" + i, "VARCHAR", 20));
            ColumnBulkSaveDto request = bulk(t, List.of(), terms);
            stats.clear();
            long t0 = System.nanoTime();
            ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(f.user, request);
            bulk[r] = (System.nanoTime() - t0) / 1_000_000;
            bulkStatements = stats.getPrepareStatementCount();
            if (!result.result()) throw new IllegalStateException(String.valueOf(result.errors()));
        }
        Arrays.sort(bulk);
        lines.add("bulk 50 new median ms=" + bulk[2] + " statements=" + bulkStatements);

        long[] readd = new long[5];
        long readdStatements = 0;
        for (int r = 0; r < readd.length; r++) {
            long t = table("재등록" + r);
            List<Long> deleted = jdbc.queryForList("select COL_SN from TB_COL where TBL_SN = ? and COL_NM like 'FILL\\_%' escape '\\' order by COL_SN fetch first 20 rows only", Long.class, t);
            List<String> names = jdbc.queryForList("select COL_NM from TB_COL where TBL_SN = ? and COL_NM like 'FILL\\_%' escape '\\' order by COL_SN fetch first 20 rows only", String.class, t);
            List<Long> terms = new ArrayList<>();
            for (String name : names) terms.add(f.term(name, "VARCHAR", 20));
            ColumnBulkSaveDto request = bulk(t, deleted, terms);
            stats.clear();
            long t0 = System.nanoTime();
            ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(f.user, request);
            readd[r] = (System.nanoTime() - t0) / 1_000_000;
            readdStatements = stats.getPrepareStatementCount();
            if (!result.result()) throw new IllegalStateException(String.valueOf(result.errors()));
        }
        Arrays.sort(readd);
        lines.add("bulk delete 20 + re-add 20 median ms=" + readd[2] + " statements=" + readdStatements);

        long[] excel = new long[3];
        long excelStatements = 0;
        for (int r = 0; r < excel.length; r++) {
            List<String[]> rows = new ArrayList<>();
            for (int t = 0; t < 10; t++) {
                long table = table("엑셀" + r + "_" + t);
                String tableName = jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table);
                for (int i = 0; i < 100; i++) rows.add(new String[]{tableName, "PERF_COL"});
            }
            // 같은 용어(PERF_COL)를 쓰면 같은 테이블 안에서 이름이 겹치므로 행마다 다른 용어 이름
            for (int i = 0; i < rows.size(); i++) {
                String name = "X" + r + "_" + i;
                f.term(name, "VARCHAR", 20);
                rows.get(i)[1] = name;
            }
            byte[] file = workbook(rows);
            stats.clear();
            long t0 = System.nanoTime();
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file), f.user);
            excel[r] = (System.nanoTime() - t0) / 1_000_000;
            excelStatements = stats.getPrepareStatementCount();
        }
        Arrays.sort(excel);
        lines.add("excel 1000 rows / 10 tables median ms=" + excel[1] + " statements=" + excelStatements);

        lines.forEach(line -> System.out.println("PERF|" + line));
        Path out = Path.of("build/reports/column-name-unique-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    /** 엑셀 업로드 (M7-U07 전후) : 행 수 100·500·501·1,000 (테이블 1), 2,000 (테이블 4, 청크 4), 겹치지 않는 두 1,000행 업로드 동시 — 3회 중앙값 ms · 준비 문장 수 · 업로드 스레드 할당 MB */
    @Test
    @DisplayName("엑셀 업로드 : 청크 경계·여러 테이블·동시 업로드")
    void excelChunks() throws Exception {
        f = GenerationFixture.create(context, "cxperf-" + System.nanoTime(), DbType.POSTGRESQL);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        List<String> lines = new ArrayList<>();
        int[][] shapes = {{100, 1}, {500, 1}, {501, 1}, {1000, 1}, {2000, 4}};
        for (int[] shape : shapes) {
            long[] ms = new long[3];
            long statements = 0;
            long alloc = 0;
            for (int r = 0; r < ms.length; r++) {
                byte[] file = excelFile(shape[0], shape[1], "E" + shape[0] + "_" + r + "_");
                stats.clear();
                long a0 = threads.getCurrentThreadAllocatedBytes();
                long t0 = System.nanoTime();
                columns.uploadColumnExcelFile(new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file), f.user);
                ms[r] = (System.nanoTime() - t0) / 1_000_000;
                alloc = (threads.getCurrentThreadAllocatedBytes() - a0) / (1024 * 1024);
                statements = stats.getPrepareStatementCount();
            }
            Arrays.sort(ms);
            lines.add("excel rows=" + shape[0] + " tables=" + shape[1] + " median ms=" + ms[1] + " statements=" + statements + " alloc MB=" + alloc);
        }
        long[] pair = new long[3];
        long[] single = new long[3];
        for (int r = 0; r < pair.length; r++) {
            byte[] one = excelFile(1000, 1, "P" + r + "A_");
            byte[] two = excelFile(1000, 1, "P" + r + "B_");
            byte[] alone = excelFile(1000, 1, "P" + r + "C_");
            long t0 = System.nanoTime();
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", alone), f.user);
            single[r] = (System.nanoTime() - t0) / 1_000_000;
            org.springframework.security.core.context.SecurityContext login = org.springframework.security.core.context.SecurityContextHolder.getContext();
            long t1 = System.nanoTime();
            java.util.concurrent.CompletableFuture<Void> x = java.util.concurrent.CompletableFuture.runAsync(() -> uploadAs(login, one));
            java.util.concurrent.CompletableFuture<Void> y = java.util.concurrent.CompletableFuture.runAsync(() -> uploadAs(login, two));
            java.util.concurrent.CompletableFuture.allOf(x, y).get();
            pair[r] = (System.nanoTime() - t1) / 1_000_000;
        }
        Arrays.sort(pair);
        Arrays.sort(single);
        lines.add("excel 1000 rows alone median ms=" + single[1] + " | two non-overlapping 1000-row uploads at once median ms=" + pair[1]);
        lines.forEach(line -> System.out.println("PERF|" + line));
        Path out = Path.of("build/reports/column-name-unique-excel-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private void uploadAs(org.springframework.security.core.context.SecurityContext login, byte[] file) {
        org.springframework.security.core.context.SecurityContextHolder.setContext(login);
        try {
            columns.uploadColumnExcelFile(new MockMultipartFile("file", "u.xlsx", "application/octet-stream", file), f.user);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /** rows 행을 tables 개의 새 테이블에 고르게 (테이블 순서대로 이어서 — 청크마다 다른 테이블), 이름마다 용어 */
    private byte[] excelFile(int rows, int tableCount, String prefix) throws Exception {
        List<String> names = new ArrayList<>();
        for (int t = 0; t < tableCount; t++) names.add(jdbc.queryForObject("select TBL_NM from TB_TBL where TBL_SN = ?", String.class, table("x")));
        long domain = jdbc.queryForObject("select STD_DMN_SN from TB_STD_TRM where STD_TRM_SN = ?", Long.class, f.term(prefix + "D", "VARCHAR", 20));
        List<Object[]> terms = new ArrayList<>();
        List<String[]> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            String column = prefix + i;
            terms.add(new Object[]{GenerationFixture.nextId(), f.user.getProjectId(), column + "_T", column, column, domain});
            data.add(new String[]{names.get(i * tableCount / rows), column});
        }
        jdbc.batchUpdate("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", terms);
        return workbook(data);
    }

    private ColumnBulkSaveDto bulk(long table, List<Long> deleted, List<Long> terms) {
        ColumnBulkSaveDto request = new ColumnBulkSaveDto();
        request.setTableInfoId(table);
        request.setDeletedIds(new ArrayList<>(deleted));
        List<ColumnBulkSaveDto.Row> rows = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            ColumnBulkSaveDto.Row row = new ColumnBulkSaveDto.Row();
            row.setKey("r" + i);
            row.setTermId(terms.get(i));
            row.setColumnDesc("설명");
            row.setDataType("VARCHAR");
            row.setMaxLength(20);
            row.setIsPk("N");
            row.setIsNullable("Y");
            row.setIsRequired("N");
            row.setIsUnique("N");
            row.setIsIndex("N");
            row.setIsMasterData("N");
            row.setIsSensitive("N");
            row.setIsEncrypted("N");
            rows.add(row);
        }
        request.setRows(rows);
        return request;
    }

    private static byte[] workbook(List<String[]> rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            int r = 1;
            for (String[] v : rows) {
                List<String> values = List.of(v[0], v[1], "설명", "VARCHAR", "20", "0", "0", "Y", "", "9", "N", "", "N", "N", "N", "N", "N", "N");
                Row row = sheet.createRow(r++);
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void insert(long table, String name) {
        ColumnInfoDto dto = new ColumnInfoDto();
        dto.setTableInfoId(table);
        dto.setTermId(term);
        dto.setColumnName(name);
        dto.setColumnDesc(name);
        dto.setDataType("VARCHAR");
        dto.setMaxLength(20);
        dto.setIsPk("N");
        dto.setIsNullable("Y");
        dto.setIsMasterData("N");
        dto.setIsRequired("N");
        dto.setIsSensitive("N");
        dto.setIsUnique("N");
        dto.setIsIndex("N");
        dto.setIsEncrypted("N");
        columns.insertColumn(f.user, dto);
    }
}
