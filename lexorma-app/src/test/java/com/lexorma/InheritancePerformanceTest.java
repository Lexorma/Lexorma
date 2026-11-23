package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckGroup;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * M7-INH 전후 비교 측정 (docs/completed/inheritance-reference-key-m7.md 8장) — LEXORMA_PERF=true 일 때만. structure() 는 고치기 전 코드에서도 그대로 돌도록 공개 서비스 API 만 쓴다.
 * 모델 : 상속 계층 60개 × (루트 + 서브타입 2 + 그 아래 서브타입 2) = 300 엔터티 + 자식 60 (루트 PK 참조 관계 60·서브타입 상속 PK 참조 관계 60) = 360 엔터티.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class InheritancePerformanceTest {

    static final int HIERARCHIES = 60;

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired ModelMappingQualityService quality;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired PhysicalModelService physical;
    @Autowired ColumnManageService columns;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    record Model(GenerationFixture f, List<Long> mid, List<Long> all) {
    }

    Model build() {
        GenerationFixture f = GenerationFixture.create(context, "inhperf-" + System.nanoTime(), DbType.POSTGRESQL);
        long p = f.user.getProjectId();
        List<Long> all = new ArrayList<>();
        List<Long> mid = new ArrayList<>();
        for (int i = 0; i < HIERARCHIES; i++) {
            String[] names = {"R", "A", "B", "C", "D", "X"};
            long[] e = new long[names.length];
            for (int k = 0; k < names.length; k++) {
                String word = "W" + names[k] + i;
                jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                        + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), p, word, word, word, word);
                e[k] = f.entity(word);
                jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", e[k]);
                all.add(e[k]);
            }
            long key = f.attr(e[0], "키" + i, true, "K" + i);
            f.pk(e[0], key);
            f.attr(e[0], "코드" + i, true, "CD" + i);
            long xk = f.attr(e[5], "자식 키" + i, true, "XK" + i);
            f.pk(e[5], xk);
            long xr = f.attr(e[5], "루트 참조" + i, false, "XR" + i);
            long xa = f.attr(e[5], "서브 참조" + i, false, "XA" + i);
            group(f, e[0], "G" + i, e[1], e[2]);
            group(f, e[1], "H" + i, e[3], e[4]);
            f.pairs(f.rel("RR" + i, e[0], e[5], false, 0), new long[]{key, xr});
            f.pairs(f.rel("RA" + i, e[1], e[5], false, 0), new long[]{key, xa});
            mid.add(e[1]);
        }
        for (int from = 0; from < all.size(); from += 96) { // 생성은 한 번에 100개까지 — 계층(6개) 단위로 나눈다
            List<Long> batch = all.subList(from, Math.min(from + 96, all.size()));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(batch));
            generation.apply(f.user, f.request(batch, plan.planToken()));
        }
        return new Model(f, mid, all);
    }

    void group(GenerationFixture f, long sup, String name, long a, long b) {
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, sup, name, null, true, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(a, null), new LogicalSubtypeDto.MemberInput(b, null)), null));
    }

    static String measure(Statistics stats, String label, int warmup, int runs, Supplier<Object> work) {
        for (int i = 0; i < warmup; i++) work.get();
        long[] t = new long[runs];
        stats.clear();
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            work.get();
            t[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        long statements = stats.getPrepareStatementCount() / runs;
        Arrays.sort(t);
        return label + " median ms=" + t[runs / 2] + " min=" + t[0] + " max=" + t[runs - 1] + " statements/run=" + statements;
    }

    @Test
    @DisplayName("구조 정합성 조회(요약·전체·상속) · 컬럼 일괄 저장(참조 테이블명 동기화) · 사용자 관계 등록 — 전후 같은 방법")
    void structure() {
        Model m = build();
        GenerationFixture f = m.f();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> lines = new ArrayList<>();
        lines.add("entities=" + m.all().size() + " foreignKeys=" + f.foreignKeys());
        lines.add(measure(stats, "summary", 2, 7, () -> quality.selectSummary(f.user, f.modelId)));
        lines.add(measure(stats, "rows ALL", 2, 7, () -> quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.ALL), PageRequest.of(0, 100))));
        lines.add(measure(stats, "rows INHERITANCE", 2, 7, () -> quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.INHERITANCE), PageRequest.of(0, 100))));
        lines.add(measure(stats, "rows RELATIONSHIP", 2, 7, () -> quality.selectRows(f.user, new Search(f.modelId, null, null, null, null, null, CheckGroup.RELATIONSHIP), PageRequest.of(0, 100))));
        // 컬럼 일괄 저장 : 자식 테이블(승인) 의 참조 컬럼 설명만 바꾼다 — 동기화가 도는 일반 저장
        long table = f.table(m.all().get(5));
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", table);
        Map<String, Object> c = jdbc.queryForMap("select COL_SN, STD_TRM_SN, DATA_TYPE_NM, MAX_LEN from TB_COL where TBL_SN = ? and COL_NM like 'XR%'", table);
        int[] n = {0};
        lines.add(measure(stats, "bulk save (description, refTable column)", 3, 20, () -> {
            java.time.LocalDateTime base = jdbc.queryForObject("select MDFCN_DT from TB_COL where COL_SN = ?", java.time.LocalDateTime.class, c.get("COL_SN"));
            String ref = jdbc.queryForObject("select RFRNC_TBL_NM from TB_COL where COL_SN = ?", String.class, c.get("COL_SN"));
            ColumnBulkSaveDto request = new ColumnBulkSaveDto();
            request.setTableInfoId(table);
            request.setRows(new ArrayList<>(List.of(ColumnBulkSaveDto.Row.builder().key("c").id(((Number) c.get("COL_SN")).longValue()).termId(((Number) c.get("STD_TRM_SN")).longValue())
                    .columnDesc("설명 " + n[0]++).dataType((String) c.get("DATA_TYPE_NM")).maxLength(((Number) c.get("MAX_LEN")).intValue()).refTableName(ref).isPk("N").isNullable("Y")
                    .baseModifiedAt(base).build())));
            return columns.saveColumnsBulk(f.user, request);
        }));
        // 사용자 관계 등록 (자식 테이블 → 루트 테이블, 매번 새 컬럼 없이 등록·삭제)
        long root = f.table(m.all().get(0));
        long xk = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM like 'XK%'", Long.class, table);
        long rk = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and PK_YN = true", Long.class, root);
        lines.add(measure(stats, "USER relation register+delete", 3, 20, () -> {
            PhysicalModelDto.Relation r = physical.saveRelation(f.user, new PhysicalModelDto.Relation(null, null, "LOGICAL", null, table, null, root, null,
                    List.of(new PhysicalModelDto.ColumnPair(xk, null, rk, null)), null));
            jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", r.id());
            jdbc.update("delete from TB_TBL_REL where REL_SN = ?", r.id());
            return r;
        }));
        lines.forEach(l -> System.out.println("[INH-PERF] " + l));
    }

    @Test
    @DisplayName("상속 FK 보정 상태 100 · 미리보기 1/50/100 · 적용 100 (새 기능)")
    void correction() throws Exception {
        Model m = build();
        GenerationFixture f = m.f();
        Object service = context.getBean(Class.forName("com.lexorma.data.mapping.service.InheritanceForeignKeyCorrectionService"));
        Class<?> request = Class.forName("com.lexorma.data.mapping.domain.dto.InheritanceForeignKeyCorrectionDto$Request");
        java.lang.reflect.Constructor<?> ctor = request.getConstructors()[0];
        java.lang.reflect.Method preview = service.getClass().getMethod("preview", com.lexorma.core.security.domain.dto.AccountDto.class, request);
        java.lang.reflect.Method apply = service.getClass().getMethod("apply", com.lexorma.core.security.domain.dto.AccountDto.class, request);
        java.lang.reflect.Method status = service.getClass().getMethod("status", com.lexorma.core.security.domain.dto.AccountDto.class, Long.class, List.class);
        // 서브타입 A·B (루트 바로 아래) 의 상속 FK 를 지운다 : 120개 누락, 그중 100개를 고른다
        List<Long> targets = new ArrayList<>();
        for (int i = 0; i < m.all().size(); i += 6) {
            long rootTable = f.table(m.all().get(i));
            for (int k = 1; k <= 2; k++) {
                long sub = f.table(m.all().get(i + k));
                for (Long rel : jdbc.queryForList("select REL_SN from TB_TBL_REL where ORGNL_TBL_SN = ? and TRGT_TBL_SN = ?", Long.class, sub, rootTable)) {
                    jdbc.update("delete from TB_TBL_REL_COL where REL_SN = ?", rel);
                    jdbc.update("delete from TB_TBL_REL where REL_SN = ?", rel);
                }
                jdbc.update("update TB_COL set RFRNC_TBL_NM = null where TBL_SN = ? and PK_YN = true", sub);
                targets.add(m.all().get(i + k));
            }
        }
        List<Long> hundred = targets.subList(0, 100);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        List<String> lines = new ArrayList<>();
        lines.add(measure(stats, "status 100", 2, 5, () -> invoke(status, service, f.user, f.modelId, hundred)));
        for (int size : new int[]{1, 50, 100}) {
            List<Long> ids = hundred.subList(0, size);
            lines.add(measure(stats, "preview " + size, 2, 5, () -> invoke(preview, service, f.user, newInstance(ctor, f.modelId, ids, null))));
        }
        Object plan = invoke(preview, service, f.user, newInstance(ctor, f.modelId, hundred, null));
        String token = (String) plan.getClass().getMethod("planToken").invoke(plan);
        int versions = f.versions();
        stats.clear();
        long t0 = System.nanoTime();
        Object result = invoke(apply, service, f.user, newInstance(ctor, f.modelId, hundred, token));
        lines.add("apply 100 ms=" + (System.nanoTime() - t0) / 1_000_000 + " statements=" + stats.getPrepareStatementCount() + " result=" + result
                + " versions+" + (f.versions() - versions));
        lines.add(measure(stats, "summary after", 1, 5, () -> quality.selectSummary(f.user, f.modelId)));
        lines.forEach(l -> System.out.println("[INH-PERF] " + l));
    }

    static Object invoke(java.lang.reflect.Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
        }
    }

    static Object newInstance(java.lang.reflect.Constructor<?> ctor, Object... args) {
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
