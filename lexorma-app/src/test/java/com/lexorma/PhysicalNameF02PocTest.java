package com.lexorma;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.repository.ColumnNameDbKeys;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnRow;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSource;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSummary;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.repository.PhysicalNameRecommendationQueryRepository;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-NAME-F02 기술 검증 (PoC — 테스트 전용, LEXORMA_PERF=true 일 때만, 운영 경로에 들어가지 않는다). docs/completed/physical-name-recommendation-m7.md 12.12.
 * 같은 11,000 속성 모델에서 비 ASCII 100 · 1,000 · 5,000 · 10,000 건으로 :
 * <ul>
 *   <li>P01 : 실제 목록과 같은 조인·상태 식으로 A 기존(바인딩 목록 not in + in) · A′ 같은 목록을 상수로 · B not in 제거 · C 목록 없음(비교 기준, 비 ASCII 행은 틀린 결과) 건수 조회 시간</li>
 *   <li>P02 : Java 전체 후보(DB 정렬로 전체 조회 → 운영 columnRow 판정 → 필터·페이지) · Java 배치(DB 순서로 1,000건씩)</li>
 *   <li>P03 : VALUES 파생 표 · 배열 바인딩 1개 (= ANY(?))</li>
 * </ul>
 * 모든 방식의 결과(행 ID·순서·건수·요약)를 운영 서비스(기존 방식) 결과와 비교한다. 결과는 build/reports/m7-name1-f02-poc.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class PhysicalNameF02PocTest {

    private static final String[] WORDS = {"고객", "주문", "직원", "부서", "팀", "공항", "항공편", "이력", "상품", "배송", "결제", "지점"};

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired PhysicalNameRecommendationQueryRepository repository;
    @Autowired ColumnNameDbKeys dbKeys;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private final List<String> out = new ArrayList<>();
    private static final Path REPORT = Path.of("build/reports/m7-name1-f02-poc.txt");
    /** P03 질의 시간 제한 (초, 0 = 없음) — 넘으면 Timeout */
    private int timeoutSeconds;

    /** 한 줄씩 바로 남긴다 (오래 걸리는 단계에서 중간 결과를 잃지 않게) */
    private void emit(String line) {
        out.add(line);
        System.out.println("F02|" + line);
        System.out.flush();
        try {
            Files.writeString(REPORT, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Timeout extends RuntimeException {
        Timeout(Throwable cause) {
            super(cause);
        }
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("F02 PoC : 기존 IN · Java 전체 · Java 배치 · VALUES · 배열 바인딩")
    void poc() throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.deleteIfExists(REPORT);
        emit("DB = PostgreSQL");
        GenerationFixture f = GenerationFixture.create(context, "f02poc-" + System.nanoTime(), com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < 220; i++) {
            long entity = f.entity(name(i));
            entities.add(entity);
            f.pk(entity, f.attr(entity, "키", true, "K1"));
            for (int a = 1; a < 50; a++) f.attr(entity, "속성" + a, false, String.format("C%02d", a));
        }
        for (int from = 0; from < entities.size(); from += 100) {
            List<Long> chunk = entities.subList(from, Math.min(entities.size(), from + 100));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chunk));
            generation.apply(f.user, new PhysicalGenerationDto.Request(chunk, null, plan.planToken()));
        }
        long projectId = f.user.getProjectId();
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM where PRJ_SN = ?", projectId);
        List<Map<String, Object>> mapped = jdbc.queryForList("select m.COL_SN, m.LGCL_ATRB_SN, a.STD_TRM_SN, t.TRM_ENG_ABBR_NM from TB_ATRB_COL_MPNG m join TB_LGCL_ATRB a on a.LGCL_ATRB_SN = m.LGCL_ATRB_SN"
                + " join TB_STD_TRM t on t.STD_TRM_SN = a.STD_TRM_SN where t.PRJ_SN = ? order by m.LGCL_ATRB_SN", projectId);
        // 정렬 확인용 혼합 이름 (ASCII 만 — 비 ASCII 수에 넣지 않는다) : 영문 대·소문자, 숫자, 밑줄, 특수문자, 같은 이름 여러 개. 끝쪽 300 컬럼에
        String[] mixed = {"abc", "ABC", "Abc", "123", "_x", "x-1", "#s", "zeta", "Zeta", "a b", "a_b", "SAME", "SAME", "same"};
        for (int i = 0; i < 300; i++) {
            Map<String, Object> row = mapped.get(mapped.size() - 1 - i);
            jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", mixed[i % mixed.length] + "_" + (i % 50) /* 같은 표(연속 50 컬럼) 안에서는 겹치지 않고, 표 사이에서는 같은 이름 = 정렬 동률 */, row.get("COL_SN"));
        }
        jdbc.execute("analyze");

        int done = 0;
        for (int target : new int[]{100, 1_000, 5_000, 10_000}) {
            for (; done < target; done++) {
                Map<String, Object> row = mapped.get(done);
                String abbreviation = (String) row.get("TRM_ENG_ABBR_NM");
                // 한글·비 ASCII 혼합 : 짝수 = 일치 (É ↔ é, 한글 포함), 홀수 = 추천 가능
                if (done % 2 == 0) {
                    jdbc.update("update TB_STD_TRM set TRM_ENG_ABBR_NM = ? where STD_TRM_SN = ?", abbreviation + "_É가" + done, row.get("STD_TRM_SN"));
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", (abbreviation + "_é가" + done).toLowerCase(), row.get("COL_SN"));
                } else {
                    jdbc.update("update TB_COL set COL_NM = ? where COL_SN = ?", (done % 4 == 1 ? "한" : "O_") + abbreviation + "_é" + done, row.get("COL_SN"));
                }
            }
            jdbc.execute("analyze");
            scale(f, target);
        }
    }

    // ------------------------------------------------------------------ 규모 1개

    private void scale(GenerationFixture f, int complex) throws Exception {
        AccountDto user = f.user;
        long projectId = user.getProjectId();
        emit("");
        emit("===== 비 ASCII " + complex);
        ColumnSearch model = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        // 덮어쓰기 (운영 서비스의 자바 판정 그대로)
        Method overrides = PhysicalNameRecommendationService.class.getDeclaredMethod("overrides", Long.class, ColumnSearch.class);
        overrides.setAccessible(true);
        PhysicalNameRecommendationQueryRepository.Overrides o = (PhysicalNameRecommendationQueryRepository.Overrides) overrides.invoke(recommendation, projectId, model);
        emit("덮어쓰기 ID " + o.complex().size() + " · 상태별 " + o.byStatus().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).collect(Collectors.joining(", ")));

        // ---------------- 기준 : 운영 서비스 (기존 방식) — 상태·정렬마다 전체 목록 한 번
        List<Sort> sorts = List.of(Sort.unsorted(), Sort.by(Sort.Direction.ASC, "currentPhysicalName"), Sort.by(Sort.Direction.DESC, "logicalName"));
        Map<String, List<Long>> reference = new LinkedHashMap<>();
        Map<String, Long> referenceTime = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            for (Sort sort : sorts) {
                long start = System.nanoTime();
                List<ColumnRow> rows = recommendation.selectColumnRows(user, new ColumnSearch(f.modelId, null, null, null, status, null, null), PageRequest.of(0, 20_000, sort)).getContent();
                referenceTime.put(status + "/" + sortName(sort), (System.nanoTime() - start) / 1_000_000);
                reference.put(status + "/" + sortName(sort), rows.stream().map(ColumnRow::logicalAttributeId).toList());
            }
        }
        emit("기존 방식 전체 목록(쪽 크기 20,000) ms : " + referenceTime);
        String existingFirst = time(1, 3, () -> recommendation.selectColumnRows(user, new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, null, null), PageRequest.of(0, 100)));
        String existingSummary = time(1, 3, () -> recommendation.selectColumnSummary(user, f.modelId));
        ColumnSummary summary = recommendation.selectColumnSummary(user, f.modelId);
        emit("기존 방식 AVAILABLE 첫 쪽 " + existingFirst + " · 요약 " + existingSummary + " · 요약 값 " + summary);

        // ---------------- P01 : 같은 조인·상태 식, 목록 구성만 바꿈 (건수 조회)
        p01(f, o);

        // ---------------- P02 : Java 전체 후보
        Method columnRow = PhysicalNameRecommendationService.class.getDeclaredMethod("columnRow", ColumnSource.class, List.class,
                com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Applicability.class);
        columnRow.setAccessible(true);
        for (Sort sort : sorts) {
            Memory memory = new Memory();
            long start = System.nanoTime();
            Map<Status, List<Long>> javaAll = javaAll(projectId, f.modelId, sort, columnRow);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            memory.end();
            for (Status status : Status.values()) {
                List<Long> want = reference.get(status + "/" + sortName(sort));
                if (want != null) assertThat(javaAll.get(status)).as("Java 전체 " + status + "/" + sortName(sort)).containsExactlyElementsOf(want);
            }
            emit("P02-A Java 전체 [" + sortName(sort) + "] 판정·필터 전체 " + elapsed + "ms · 결과 일치 · " + memory);
        }
        emit("P02-A Java 전체 [기본] 상태 필터 첫 쪽 반복 " + time(1, 5, () -> page(javaAll(projectId, f.modelId, Sort.unsorted(), columnRow).get(Status.AVAILABLE), 0, 100)));
        emit("P02-A Java 전체 요약(한 번에 네 건수) " + time(1, 5, () -> javaAll(projectId, f.modelId, Sort.unsorted(), columnRow).values().stream().mapToInt(List::size).sum()));
        // Java 정렬로 대신하면 DB 정렬과 같은가 (이 방식을 쓰지 않는 근거)
        emit("참고 : Java 문자열 정렬 ↔ DB 정렬 (현재 컬럼명 오름차순) 순서가 다른 자리 " + javaSortDifference(projectId, f.modelId, columnRow) + "개");

        // ---------------- P02-B : Java 배치 (OFFSET 1,000건씩, DB 순서)
        for (int batch : new int[]{1_000, 5_000}) {
            Memory memory = new Memory();
            long start = System.nanoTime();
            Map<Status, List<Long>> javaBatch = javaBatch(projectId, f.modelId, Sort.unsorted(), columnRow, batch);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            memory.end();
            for (Status status : Status.values()) {
                assertThat(javaBatch.get(status)).as("Java 배치 " + status).containsExactlyElementsOf(reference.get(status + "/기본"));
            }
            emit("P02-B Java 배치 " + batch + "건씩 (OFFSET, 매번 건수 조회 포함) 전체 " + elapsed + "ms · 결과 일치 · " + memory);
        }

        // ---------------- P03 : VALUES · 배열 바인딩 — 쪽·건수 SQL (운영과 같은 조인·정렬)
        List<String> usable = new ArrayList<>();
        for (String variant : List.of("VALUES", "ARRAY", "VALUES_UNION", "ARRAY_UNION")) {
            // 먼저 건수 1회를 시간 제한 안에서 — 넘으면 이 규모에서 이 방식은 목표(2초)와 거리가 멀어 정확성 검증을 생략한다
            timeoutSeconds = 60;
            try {
                long start = System.nanoTime();
                setCount(f, o, variant, Status.AVAILABLE);
                emit("P03 " + variant + " 건수 첫 실행 " + (System.nanoTime() - start) / 1_000_000 + "ms");
            } catch (Timeout e) {
                emit("P03 " + variant + " : 건수 1회가 " + timeoutSeconds + "초 제한 초과 → 이 규모의 정확성·시간 측정 생략");
                continue;
            } finally {
                timeoutSeconds = 0;
            }
            usable.add(variant);
            for (Sort sort : sorts) {
                for (Status status : Status.values()) {
                    List<Long> want = reference.get(status + "/" + sortName(sort));
                    if (want == null) continue;
                    // 첫 쪽·중간 쪽·마지막 쪽·빈 쪽, 쪽 크기 10·100·1,000, 모든 쪽 합
                    for (int size : new int[]{10, 100, 1_000}) {
                        int pages = (want.size() + size - 1) / size;
                        for (int pageNo : new int[]{0, pages / 2, Math.max(pages - 1, 0), pages + 1}) {
                            List<Long> got = setPage(f, o, variant, status, sort, pageNo, size);
                            assertThat(got).as(variant + " " + status + "/" + sortName(sort) + " 쪽 " + pageNo + "×" + size).containsExactlyElementsOf(page(want, pageNo, size));
                        }
                    }
                    assertThat(setCount(f, o, variant, status)).as(variant + " 건수 " + status).isEqualTo(want.size());
                    if (sort.isUnsorted()) {
                        List<Long> all = new ArrayList<>();
                        for (int p = 0; p * 1_000 < want.size(); p++) all.addAll(setPage(f, o, variant, status, sort, p, 1_000));
                        assertThat(all).as(variant + " 모든 쪽 " + status).containsExactlyElementsOf(want).doesNotHaveDuplicates();
                    }
                }
            }
            String first = time(1, 5, () -> setPage(f, o, variant, Status.AVAILABLE, Sort.unsorted(), 0, 100));
            String middle = time(1, 5, () -> setPage(f, o, variant, Status.AVAILABLE, Sort.unsorted(), 50, 100));
            String last = time(1, 5, () -> setPage(f, o, variant, Status.AVAILABLE, Sort.unsorted(), (reference.get("AVAILABLE/기본").size() - 1) / 100, 100));
            String count = time(1, 5, () -> setCount(f, o, variant, Status.AVAILABLE));
            String summaryTime = time(1, 5, () -> Arrays.stream(Status.values()).mapToLong(s -> setCount(f, o, variant, s)).sum());
            emit("P03 " + variant + " : 결과 일치 (" + 3 + " 정렬 × 4 상태 × 쪽 크기 10·100·1,000 × 첫·중간·마지막·빈 쪽, 건수, 모든 쪽 합) · AVAILABLE 첫 쪽 " + first + " · 중간 " + middle
                    + " · 마지막 " + last + " · 건수 " + count + " · 요약(건수 4) " + summaryTime + " · 바인딩 " + binds(variant, o));
        }
        // ---------------- 최종 형태 추정 : 쪽 + 건수 (운영 상태 필터 1요청 = 쪽 + 건수)
        for (String variant : usable) {
            emit("P03 " + variant + " 상태 필터 1요청(쪽 + 건수) " + time(1, 5, () -> {
                setPage(f, o, variant, Status.AVAILABLE, Sort.unsorted(), 0, 100);
                return setCount(f, o, variant, Status.AVAILABLE);
            }));
        }
    }

    // ------------------------------------------------------------------ P01

    private void p01(GenerationFixture f, PhysicalNameRecommendationQueryRepository.Overrides o) throws Exception {
        Set<Long> chosen = o.byStatus().getOrDefault(Status.AVAILABLE, Set.of());
        Map<String, String> where = new LinkedHashMap<>();
        where.put("A 기존 (바인딩 not in " + o.complex().size() + " + in " + chosen.size() + ")",
                "((" + notIn(o.complex(), false) + " and " + status(Status.AVAILABLE) + ") or " + in(chosen, false) + ")");
        where.put("A′ 같은 목록을 상수로", "((" + notIn(o.complex(), true) + " and " + status(Status.AVAILABLE) + ") or " + in(chosen, true) + ")");
        where.put("B not in 제거 (바인딩 in " + chosen.size() + ")", "((" + plain() + " and " + status(Status.AVAILABLE) + ") or " + in(chosen, false) + ")");
        where.put("C 목록 없음 (비교 기준)", "(" + plain() + " and " + status(Status.AVAILABLE) + ")");
        for (Map.Entry<String, String> e : where.entrySet()) {
            String sql = "select count(*) " + FROM + " where e1_0.prj_sn = ? and m1_0.lgcl_mdl_sn = ? and " + e.getValue();
            List<Object> args = new ArrayList<>(List.of(f.user.getProjectId(), f.modelId));
            if (e.getKey().startsWith("A 기존")) {
                args.addAll(o.complex());
                args.addAll(chosen);
            } else if (e.getKey().startsWith("B")) {
                args.addAll(chosen);
            }
            emit("P01 " + e.getKey() + " : 건수 " + count(sql, args) + " · " + time(1, 5, () -> count(sql, args)) + " · SQL " + sql.length() + "자");
        }
        // 목록 크기만 바꾼 단순 비교 : 11,000 행 표에 바인딩 IN n개 vs 상수 IN n개 vs 배열 1개
        List<Long> ids = jdbc.queryForList("select LGCL_ATRB_SN from TB_LGCL_ATRB a join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = a.LGCL_ENTT_SN where e.LGCL_MDL_SN = ? order by 1", Long.class, f.modelId);
        List<Long> list = ids.subList(0, Math.min(o.complex().size(), ids.size()));
        String filter = "select count(*) from TB_LGCL_ATRB a join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = a.LGCL_ENTT_SN where e.LGCL_MDL_SN = ? and (a.ATRB_NM = 'x' or ";
        emit("P01 단순 (11,000행, 목록 " + list.size() + ", OR 안의 IN) : 바인딩 " + time(1, 3, () -> count(filter + in(list, false).replace("la1_0.lgcl_atrb_sn", "a.LGCL_ATRB_SN") + ")", concat(f.modelId, list)))
                + " · 상수 " + time(1, 3, () -> count(filter + in(list, true).replace("la1_0.lgcl_atrb_sn", "a.LGCL_ATRB_SN") + ")", List.of(f.modelId)))
                + " · 배열 1개 " + time(1, 3, () -> arrayCount(filter + arrayIn("a.LGCL_ATRB_SN") + ")", f.modelId, list)));
    }

    // ------------------------------------------------------------------ P02

    /** DB 가 운영과 같은 정렬로 전체(상태 조건 없이)를 돌려주면 운영 columnRow 로 판정해 상태별로 나눈다 (순서 = DB 순서) */
    private Map<Status, List<Long>> javaAll(long projectId, Long modelId, Sort sort, Method columnRow) {
        List<ColumnSource> sources = repository.findColumnSources(projectId, new ColumnSearch(modelId, null, null, null, null, null, null), PageRequest.of(0, 1_000_000, sort)).getContent();
        return judge(sources, columnRow);
    }

    private Map<Status, List<Long>> javaBatch(long projectId, Long modelId, Sort sort, Method columnRow, int batch) {
        Map<Status, List<Long>> result = new LinkedHashMap<>();
        for (Status s : Status.values()) result.put(s, new ArrayList<>());
        for (int page = 0; ; page++) {
            var p = repository.findColumnSources(projectId, new ColumnSearch(modelId, null, null, null, null, null, null), PageRequest.of(page, batch, sort));
            judge(p.getContent(), columnRow).forEach((s, ids) -> result.get(s).addAll(ids));
            if (!p.hasNext()) break;
        }
        return result;
    }

    private Map<Status, List<Long>> judge(List<ColumnSource> sources, Method columnRow) {
        List<ColumnSource> named = sources.stream().filter(s -> s.tableId() != null && PhysicalNameRecommendationService.recommendColumnName(s.termId(), s.termAbbreviation()).available()).toList();
        List<ColumnNameDbKeys.Taken> taken = dbKeys.taken(named.stream().map(ColumnSource::tableId).collect(Collectors.toSet()),
                named.stream().map(s -> PhysicalNameRecommendationService.recommendColumnName(s.termId(), s.termAbbreviation()).name()).collect(Collectors.toSet()));
        Map<Status, List<Long>> result = new LinkedHashMap<>();
        for (Status s : Status.values()) result.put(s, new ArrayList<>());
        try {
            for (ColumnSource s : sources) {
                ColumnRow row = (ColumnRow) columnRow.invoke(null, s, taken, null);
                result.get(row.status()).add(s.attributeId());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private long javaSortDifference(long projectId, Long modelId, Method columnRow) {
        List<ColumnSource> db = repository.findColumnSources(projectId, new ColumnSearch(modelId, null, null, null, null, null, null),
                PageRequest.of(0, 1_000_000, Sort.by(Sort.Direction.ASC, "currentPhysicalName"))).getContent();
        List<ColumnSource> sorted = new ArrayList<>(db);
        sorted.sort(java.util.Comparator.comparing(ColumnSource::columnName, java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        long diff = 0;
        for (int i = 0; i < db.size(); i++) if (!java.util.Objects.equals(db.get(i).columnName(), sorted.get(i).columnName())) diff++;
        return diff;
    }

    // ------------------------------------------------------------------ P03

    private static final String FROM = "from tb_lgcl_atrb la1_0 join tb_lgcl_entt e1_0 on e1_0.lgcl_entt_sn=la1_0.lgcl_entt_sn join tb_lgcl_mdl m1_0 on m1_0.lgcl_mdl_sn=e1_0.lgcl_mdl_sn"
            + " left join tb_std_trm st1_0 on st1_0.std_trm_sn=la1_0.std_trm_sn left join tb_entt_tbl_mpng etm1_0 on etm1_0.lgcl_entt_sn=e1_0.lgcl_entt_sn"
            + " left join tb_atrb_col_mpng acm1_0 on acm1_0.lgcl_atrb_sn=la1_0.lgcl_atrb_sn left join tb_col c1_0 on c1_0.col_sn=acm1_0.col_sn"
            + " left join tb_tbl ti1_0 on ti1_0.tbl_sn=coalesce(c1_0.tbl_sn,etm1_0.tbl_sn) left join tb_schm s1_0 on s1_0.schm_sn=ti1_0.schm_sn";

    private List<Long> setPage(GenerationFixture f, PhysicalNameRecommendationQueryRepository.Overrides o, String variant, Status status, Sort sort, int page, int size) {
        Set<Long> chosen = o.byStatus().getOrDefault(status, Set.of());
        String base = " where e1_0.prj_sn = ? and m1_0.lgcl_mdl_sn = ? and ";
        String paging = " offset " + ((long) page * size) + " rows fetch first " + size + " rows only";
        String sql;
        if (variant.endsWith("_UNION")) {
            // OR 를 없앤 형태 : ASCII 행(SQL 판정) UNION ALL 덮어쓰기 행(ID 집합) — 두 집합은 겹치지 않는다 (덮어쓰기 = 비 ASCII 행)
            List<String[]> parts = orderParts(sort);
            StringBuilder cols = new StringBuilder("la1_0.lgcl_atrb_sn id");
            StringBuilder outer = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                cols.append(", ").append(parts.get(i)[0]).append(" o").append(i);
                outer.append(i == 0 ? "" : ", ").append("o").append(i).append(parts.get(i)[1]);
            }
            sql = "select id from (select " + cols + " " + FROM + base + plain() + " and " + status(status)
                    + " union all select " + cols + " " + FROM + base + setCondition(variant, chosen) + ") u order by " + outer + paging;
        } else {
            sql = "select la1_0.lgcl_atrb_sn " + FROM + base + "((" + plain() + " and " + status(status) + ") or " + setCondition(variant, chosen)
                    + ") order by " + orderBy(sort) + paging;
        }
        return query(sql, f, variant, chosen, rs -> rs.getLong(1));
    }

    private long setCount(GenerationFixture f, PhysicalNameRecommendationQueryRepository.Overrides o, String variant, Status status) {
        Set<Long> chosen = o.byStatus().getOrDefault(status, Set.of());
        String base = " where e1_0.prj_sn = ? and m1_0.lgcl_mdl_sn = ? and ";
        String sql = variant.endsWith("_UNION")
                ? "select (select count(*) " + FROM + base + plain() + " and " + status(status) + ") + (select count(*) " + FROM + base + setCondition(variant, chosen) + ")"
                : "select count(*) " + FROM + base + "((" + plain() + " and " + status(status) + ") or " + setCondition(variant, chosen) + ")";
        return query(sql, f, variant, chosen, rs -> rs.getLong(1)).get(0);
    }

    private String setCondition(String variant, Collection<Long> ids) {
        if (ids.isEmpty()) return "1=0";
        if (variant.startsWith("ARRAY")) return arrayIn("la1_0.lgcl_atrb_sn");
        StringBuilder values = new StringBuilder("la1_0.lgcl_atrb_sn in (select v.x from (values ");
        for (int i = 0; i < ids.size(); i++) values.append(i == 0 ? "" : ", ").append("(cast(? as bigint))");
        return values.append(") v(x))").toString();
    }

    private String arrayIn(String column) {
        return column + " = any(?)";
    }

    private interface Reader<T> {
        T read(ResultSet rs) throws java.sql.SQLException;
    }

    private <T> List<T> query(String sql, GenerationFixture f, String variant, Collection<Long> ids, Reader<T> reader) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (timeoutSeconds > 0) ps.setQueryTimeout(timeoutSeconds);
            int i = 1;
            for (int repeat = variant.endsWith("_UNION") ? 2 : 1; repeat > 0; repeat--) {
                ps.setLong(i++, f.user.getProjectId());
                ps.setLong(i++, f.modelId);
            }
            if (!ids.isEmpty()) {
                if (variant.startsWith("ARRAY")) ps.setArray(i, c.createArrayOf("bigint", ids.toArray()));
                else for (Long id : ids) ps.setLong(i++, id);
            }
            List<T> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(reader.read(rs));
            }
            return result;
        } catch (java.sql.SQLException e) {
            if (e instanceof java.sql.SQLTimeoutException || "57014".equals(e.getSQLState())) throw new Timeout(e);
            throw new IllegalStateException(e);
        }
    }

    private String binds(String variant, PhysicalNameRecommendationQueryRepository.Overrides o) {
        int max = o.byStatus().values().stream().mapToInt(Set::size).max().orElse(0);
        int base = variant.endsWith("_UNION") ? 4 : 2;
        return variant.startsWith("ARRAY") ? base + " + 배열 1" : (base + max) + " (최대)";
    }

    // ------------------------------------------------------------------ SQL 조각 (운영 식과 같은 뜻 — StandardNameSql · ColumnNameKeySql · 저장소의 상태 식)

    private String upper(String x) {
        return "upper((" + x + ") collate \"C\")";
    }

    private String plainOf(String x) {
        return "(" + x + " is null or " + x + " ~ '^[ -~]*$')";
    }

    private String plain() {
        return "(" + plainOf("st1_0.trm_eng_abbr_nm") + " and " + plainOf("c1_0.col_nm") + ")";
    }

    private String status(Status s) {
        String unavailable = "(st1_0.std_trm_sn is null or st1_0.trm_eng_abbr_nm is null or character_length(trim(st1_0.trm_eng_abbr_nm)) = 0)";
        String matched = "(c1_0.col_sn is not null and " + upper("trim(coalesce(c1_0.col_nm, ''))") + " = " + upper("trim(st1_0.trm_eng_abbr_nm)") + ")";
        String key = "(upper(trim(st1_0.trm_eng_abbr_nm) collate \"und-x-icu\") collate \"C\")";
        String conflict = "(ti1_0.tbl_sn is not null and exists (select 1 from TB_COL x_nm where x_nm.TBL_SN = ti1_0.tbl_sn and x_nm.COL_NM_CMPR_VL = " + key
                + " and (c1_0.col_sn is null or x_nm.COL_SN <> c1_0.col_sn)))";
        return switch (s) {
            case UNAVAILABLE -> unavailable;
            case ALREADY_MATCHED -> "(not " + unavailable + " and " + matched + ")";
            case CONFLICT -> "(not " + unavailable + " and not " + matched + " and " + conflict + ")";
            case AVAILABLE -> "(not " + unavailable + " and not " + matched + " and not " + conflict + ")";
        };
    }

    /** 운영 정렬 (PhysicalNameRecommendationQueryRepository.orders) : 요청 필드 → 엔터티명 · 엔터티 ID · 속성 순서(null 뒤) · 속성 ID */
    private static List<String[]> orderParts(Sort sort) {
        List<String[]> parts = new ArrayList<>();
        for (Sort.Order o : sort) {
            String column = switch (o.getProperty()) {
                case "entityName" -> "e1_0.entt_nm";
                case "logicalName" -> "la1_0.atrb_nm";
                case "currentPhysicalName" -> "c1_0.col_nm";
                case "recommendedPhysicalName" -> "st1_0.trm_eng_abbr_nm";
                default -> null;
            };
            if (column != null) parts.add(new String[]{column, o.isAscending() ? " asc" : " desc"});
        }
        parts.add(new String[]{"e1_0.entt_nm", " asc"});
        parts.add(new String[]{"e1_0.lgcl_entt_sn", " asc"});
        parts.add(new String[]{"la1_0.sort_ord", " asc nulls last"});
        parts.add(new String[]{"la1_0.lgcl_atrb_sn", " asc"});
        return parts;
    }

    private static String orderBy(Sort sort) {
        return orderParts(sort).stream().map(p -> p[0] + p[1]).collect(Collectors.joining(", "));
    }

    private static String sortName(Sort sort) {
        return sort.isUnsorted() ? "기본" : sort.toString().replace(": ", " ");
    }

    private static String in(Collection<Long> ids, boolean literal) {
        if (ids.isEmpty()) return "1=0";
        return "la1_0.lgcl_atrb_sn in (" + (literal ? ids.stream().map(String::valueOf).collect(Collectors.joining(", ")) : String.join(", ", java.util.Collections.nCopies(ids.size(), "?"))) + ")";
    }

    private static String notIn(Collection<Long> ids, boolean literal) {
        return ids.isEmpty() ? "1=1" : in(ids, literal).replace(" in (", " not in (");
    }

    private long count(String sql, List<Object> args) {
        return jdbc.queryForObject(sql, Long.class, args.toArray());
    }

    private long arrayCount(String sql, long modelId, List<Long> ids) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, modelId);
            ps.setArray(2, c.createArrayOf("bigint", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Object> concat(Object first, Collection<Long> rest) {
        List<Object> args = new ArrayList<>();
        args.add(first);
        args.addAll(rest);
        return args;
    }

    private static <T> List<T> page(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        return all.subList(from, Math.min(from + size, all.size()));
    }

    /** 처음 1회(첫 실행) 따로, 준비 warm 회 뒤 runs 회 : 첫 실행 / 중앙값 / 최댓값 ms */
    private static String time(int warm, int runs, Supplier<Object> work) {
        long t0 = System.nanoTime();
        work.get();
        long first = (System.nanoTime() - t0) / 1_000_000;
        for (int i = 1; i < warm; i++) work.get();
        long[] times = new long[Math.max(runs, 1)];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            work.get();
            times[i] = (System.nanoTime() - start) / 1_000_000;
        }
        if (runs == 0) return "첫 " + first + "ms";
        Arrays.sort(times);
        return "첫 " + first + " / 중앙 " + times[times.length / 2] + " / 최대 " + times[times.length - 1] + "ms";
    }

    /** 힙 (GC 뒤 기준 → 작업 뒤 사용량 · 그 사이 GC 횟수) */
    private static final class Memory {
        final long before;
        final long gcBefore;
        long after;
        long gcs;

        Memory() {
            System.gc();
            before = used();
            gcBefore = gcCount();
        }

        void end() {
            after = used();
            gcs = gcCount() - gcBefore;
        }

        private static long used() {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }

        private static long gcCount() {
            return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        }

        @Override
        public String toString() {
            return "힙 " + (before / 1_048_576) + " → " + (after / 1_048_576) + "MB · GC " + gcs + "회";
        }
    }

    private static String name(int i) {
        int n = WORDS.length;
        if (i < n) return WORDS[i];
        int j = i - n;
        if (j < n * (n - 1)) {
            int a = j / (n - 1);
            int b = j % (n - 1);
            return WORDS[a] + " " + WORDS[b >= a ? b + 1 : b];
        }
        j -= n * (n - 1);
        int a = j / ((n - 1) * (n - 2));
        int rest = j % ((n - 1) * (n - 2));
        List<String> others = new ArrayList<>(List.of(WORDS));
        String first = others.remove(a);
        String second = others.remove(rest / (n - 2));
        return first + " " + second + " " + others.get(rest % (n - 2));
    }
}
