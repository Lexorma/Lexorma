package com.lexorma;

import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 상속 A·C 성능 측정 (docs/completed/inheritance-strategy-ac-m7.md 7장) — LEXORMA_PERF=true 일 때만. 판정 기준이 아니라 기록용이다.
 * 루트 1 + 서브타입 99 (속성 각 2개, Disjoint·Total, 분류 값 있음) 계층을 B·A·C 로 각각 미리보기·생성하고, 생성 뒤 매핑 목록·상태·정합성 요약 시간을 잰다 (각 3회 중 최솟값·최댓값).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class InheritanceStrategyPerformanceTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired ModelMappingService mappings;
    @Autowired ModelMappingQualityService quality;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    record Tree(GenerationFixture f, long root, List<Long> all) {
    }

    Tree tree(String name) {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "acperf-" + name);
        // 서브타입 이름은 표준 단어 조합이어야 테이블명이 정해진다 : 단어 'S0'..'S98' 을 등록한다
        long root = f.entity("고객");
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", root);
        f.pk(root, f.attr(root, "고객 번호", true, "CUST_NO"));
        long type = f.attr(root, "고객 유형 코드", true, "CUST_TY_CD");
        f.attr(root, "고객 이름", true, "CUST_NM");
        List<MemberInput> members = new ArrayList<>();
        List<Long> all = new ArrayList<>(List.of(root));
        for (int i = 0; i < 99; i++) {
            String word = "갑" + i, abbr = "S" + i;
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", GenerationFixture.nextId(), f.user.getProjectId(), word, abbr, abbr, word);
            long e = f.entity(word + " 고객");
            jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", e);
            f.attr(e, word + " 값 하나", false, abbr + "_A");
            f.attr(e, word + " 값 둘", false, abbr + "_B");
            members.add(new MemberInput(e, abbr));
            all.add(e);
        }
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, root, "고객 구분", null, false, true, type, members, null));
        return new Tree(f, root, all);
    }

    static PhysicalGenerationDto.Request request(Tree t, String token, String strategy) {
        return new PhysicalGenerationDto.Request(t.all(), null, token, null, strategy == null ? null : List.of(new PhysicalGenerationDto.StrategyChoice(t.root(), strategy)));
    }

    static long ms(Supplier<?> work) {
        long start = System.nanoTime();
        work.get();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void measure() {
        String db = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
        for (String strategy : new String[]{null, "SINGLE_TABLE", "TABLE_PER_CONCRETE"}) {
            List<Long> preview = new ArrayList<>(), apply = new ArrayList<>(), list = new ArrayList<>(), status = new ArrayList<>(), summary = new ArrayList<>();
            for (int run = 0; run < 3; run++) {
                Tree t = tree((strategy == null ? "B" : strategy) + run);
                PhysicalGenerationDto.Plan[] plan = new PhysicalGenerationDto.Plan[1];
                preview.add(ms(() -> plan[0] = generation.preview(t.f().user, request(t, null, strategy))));
                assertThat(plan[0].creatable()).as(String.valueOf(plan[0].tables().stream().flatMap(x -> x.errors().stream()).limit(3).toList())).isTrue();
                apply.add(ms(() -> generation.apply(t.f().user, request(t, plan[0].planToken(), strategy))));
                list.add(ms(() -> mappings.selectEntityRows(t.f().user, new ModelMappingDto.EntitySearch(t.f().modelId, null, null, null, null, null), PageRequest.of(0, 100))));
                status.add(ms(() -> mappings.selectStatus(t.f().user, t.f().modelId)));
                summary.add(ms(() -> quality.selectSummary(t.f().user, t.f().modelId)));
            }
            System.out.printf("[AC-PERF %s %s] preview %s ms · apply %s ms · mapping list %s ms · status %s ms · quality summary %s ms%n",
                    db, strategy == null ? "B" : strategy, preview, apply, list, status, summary);
        }
    }
}
