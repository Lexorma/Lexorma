package com.lexorma;

import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.ColumnSearch;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Scope;
import com.lexorma.data.mapping.domain.dto.PhysicalNameRecommendationDto.Status;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import com.lexorma.data.mapping.service.PhysicalNameRecommendationService;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * M7-NAME-1 성능 측정 (측정용 — LEXORMA_PERF=true 일 때만) : A형 계층(통합 테이블) N개 + C형 계층(말단 복제) N개 + 일반 엔터티로 모델을 만들고,
 * 컬럼 일부를 비 ASCII·탭이 섞인 이름으로 바꿔 자바 판정 덮어쓰기(Overrides)가 생기게 한다. 직접 목록 첫 쪽·상태 필터·검색·정렬·요약·상속 범위 첫 쪽·정합성 목록의
 * 시간(5회 중앙값)과 Hibernate 준비 문장 수를 잰다. 결과는 build/reports/m7-name1-performance.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class PhysicalNameInheritedPerformanceTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired PhysicalNameRecommendationService recommendation;
    @Autowired ModelMappingQualityService quality;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("상속 A·C 계층과 비 ASCII 이름이 섞인 모델의 추천·정합성 조회 시간 (계층 10·40)")
    void measure() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("trees | direct rows | inherited rows | complex rows | direct p1 ms (stmts) | AVAILABLE p1 | ALREADY_MATCHED p1 | search p1 | sort p1 | summary | inherited p1 | inherited AVAILABLE | quality NAME");
        for (int trees : new int[]{10, 40}) {
            lines.add(run(trees));
            System.out.println("PERF|" + lines.get(lines.size() - 1));
        }
        Path out = Path.of("build/reports/m7-name1-performance.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    private String run(int trees) {
        GenerationFixture f = GenerationFixture.create(context, "name1perf-" + trees + "-" + System.nanoTime(), com.lexorma.core.project.domain.enums.DbType.POSTGRESQL);
        for (int t = 0; t < trees; t++) {
            boolean concrete = t % 2 == 1;
            String p = "T" + t;
            long root = entity(f, p, "R");
            long key = f.attr(root, "키", true, p + "_KEY");
            f.pk(root, key);
            long type = f.attr(root, "유형", true, p + "_TY_CD");
            for (int a = 0; a < 8; a++) f.attr(root, "루트속성" + a, false, p + "_R" + a);
            List<MemberInput> members = new ArrayList<>();
            List<Long> all = new ArrayList<>(List.of(root));
            for (int l = 0; l < 3; l++) {
                long leaf = entity(f, p, "L" + l);
                for (int a = 0; a < 4; a++) f.attr(leaf, "말단속성" + a, false, p + "_L" + l + "_" + a);
                members.add(new MemberInput(leaf, "V" + l));
                all.add(leaf);
            }
            subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, root, "구분 " + p, null, false, true, type, members, null));
            List<PhysicalGenerationDto.StrategyChoice> choice = List.of(new PhysicalGenerationDto.StrategyChoice(root, concrete ? "TABLE_PER_CONCRETE" : "SINGLE_TABLE"));
            PhysicalGenerationDto.Plan plan = generation.preview(f.user, new PhysicalGenerationDto.Request(all, null, null, null, choice));
            generation.apply(f.user, new PhysicalGenerationDto.Request(all, null, plan.planToken(), null, choice));
        }
        long projectId = f.user.getProjectId();
        // 모두 추천 가능 상태로 (O_ 접두어), 20 개마다 하나는 비 ASCII·탭 (자바 판정 덮어쓰기)
        jdbc.update("update TB_COL set COL_NM = 'O_' || COL_NM where PRJ_SN = ?", projectId);
        List<Long> ids = jdbc.queryForList("select COL_SN from TB_COL where PRJ_SN = ? order by COL_SN", Long.class, projectId);
        int complex = 0;
        for (int i = 0; i < ids.size(); i += 20, complex++) {
            jdbc.update("update TB_COL set COL_NM = COL_NM || (case when mod(COL_SN, 2) = 0 then 'ß' else chr(9) end) where COL_SN = ?", ids.get(i));
        }
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        ColumnSearch model = new ColumnSearch(f.modelId, null, null, null, null, null, null);
        var p1 = PageRequest.of(0, 100);
        String direct = measure(stats, () -> recommendation.selectColumnRows(f.user, model, p1).getTotalElements());
        long directRows = recommendation.selectColumnRows(f.user, model, p1).getTotalElements();
        String available = measure(stats, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, null, null), p1).getTotalElements());
        String matched = measure(stats, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.ALREADY_MATCHED, null, null), p1).getTotalElements());
        String search = measure(stats, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, null, "currentPhysicalName", "O_T1"), p1).getTotalElements());
        String sort = measure(stats, () -> recommendation.selectColumnRows(f.user, model, PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "currentPhysicalName"))).getTotalElements());
        String summary = measure(stats, () -> recommendation.selectColumnSummary(f.user, f.modelId).total());
        String inherited = measure(stats, () -> recommendation.selectColumnRows(f.user, model, p1, Scope.INHERITED).getTotalElements());
        long inheritedRows = recommendation.selectColumnRows(f.user, model, p1, Scope.INHERITED).getTotalElements();
        String inheritedAvailable = measure(stats, () -> recommendation.selectColumnRows(f.user, new ColumnSearch(f.modelId, null, null, null, Status.AVAILABLE, null, null), p1, Scope.INHERITED)
                .getTotalElements());
        String qualityName = measure(stats, () -> quality.selectRows(f.user, new ModelMappingQualityDto.Search(f.modelId, null, ModelMappingQualityDto.CheckType.NAME, null, null, null),
                p1).getTotalElements());
        return String.join(" | ", String.valueOf(trees), String.valueOf(directRows), String.valueOf(inheritedRows), String.valueOf(complex), direct, available, matched, search, sort, summary,
                inherited, inheritedAvailable, qualityName);
    }

    /** 엔터티명 = 표준 단어 하나 (테이블명 생성이 단어를 찾게 엔터티마다 단어를 등록한다) */
    private long entity(GenerationFixture f, String prefix, String name) {
        String word = "노드" + prefix + name;
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(),
                word, "ND_" + prefix + name, "ND_" + prefix + name, word);
        long id = f.entity(word);
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private static String measure(Statistics stats, Supplier<Object> work) {
        work.get(); // 준비 (캐시·JIT)
        long[] times = new long[5];
        long statements = 0;
        for (int i = 0; i < 5; i++) {
            stats.clear();
            long start = System.nanoTime();
            work.get();
            times[i] = (System.nanoTime() - start) / 1_000_000;
            statements = stats.getPrepareStatementCount();
        }
        Arrays.sort(times);
        return times[2] + " (" + statements + ")";
    }
}
