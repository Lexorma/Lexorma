package com.lexorma;

import com.lexorma.ai.budget.TokenEstimator;
import com.lexorma.ai.tool.LogicalMetadataDto;
import com.lexorma.ai.tool.LogicalMetadataDto.EntityRef;
import com.lexorma.ai.tool.LogicalMetadataDto.EntitySearch;
import com.lexorma.ai.tool.LogicalMetadataDto.ModelRef;
import com.lexorma.ai.tool.LogicalMetadataService;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.Assignment;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto.AssignmentItem;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-35 D35-2 : 공통 조회의 응답 크기·조회 수·시간 (격리 데이터 — 운영·예제 데이터 아님). 결과는 '[D35-2 측정]' 줄로 남긴다 (테스트 보고서의 표준 출력).
 * 크기 = 기본 ObjectMapper 직렬화의 문자 수·UTF-8 바이트 수, 추정 토큰 = {@link TokenEstimator} (보수적 추정 — 실측 아님).
 * 단언 : 조회 수가 페이지 크기·모델 크기·상속 깊이와 무관한 상수 범위, 대표 응답이 결과 상한(24,000자) 안, 상한으로 자른 목록은 전체 수·complete·next 표시.
 * 최악 조합(유효 속성 100개 × 설명 300자 이상)의 크기는 단언하지 않고 기록만 한다 — 상한을 넘으면 실행 경계가 RESULT_TOO_LARGE 를 돌려준다 (계약 결정은 문서 11장)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LogicalMetadataSizePerformanceTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final int RESULT_MAX_CHARS = 24_000;

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalSubjectAreaService areas;
    @Autowired LogicalMetadataService metadata;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManagerFactory emf;

    private final List<String> lines = new ArrayList<>();

    @AfterEach
    void tearDown() {
        lines.forEach(System.out::println);
        SecurityTestSupport.clear();
    }

    private long entity(GenerationFixture f, String name, String description) {
        long id = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, name, description, null, null, null)).id();
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private long attr(GenerationFixture f, long entity, String name, String description, boolean required) {
        return logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, entity, name, description, "문자", required, null, null, null, null, null)).id();
    }

    private void group(GenerationFixture f, long supertype, String name, long... members) {
        List<MemberInput> list = new ArrayList<>();
        for (long m : members) list.add(new MemberInput(m, null));
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, supertype, name, null, false, false, null, list, null));
    }

    /** 실행 결과 크기와 조회 수 : 한 번 데운 뒤 5번 재서 중앙값 */
    private <T> Measured measure(String label, Supplier<T> call) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        long coldStarted = System.nanoTime();
        T first = call.get();
        long cold = (System.nanoTime() - coldStarted) / 1_000;
        long[] times = new long[5];
        long statements = 0;
        long serialize = 0;
        String json = null;
        for (int i = 0; i < times.length; i++) {
            stats.clear();
            long started = System.nanoTime();
            T result = call.get();
            times[i] = (System.nanoTime() - started) / 1_000;
            statements = stats.getPrepareStatementCount();
            long s = System.nanoTime();
            json = mapper.writeValueAsString(result);
            serialize = (System.nanoTime() - s) / 1_000;
        }
        java.util.Arrays.sort(times);
        Measured m = new Measured(label, json, json.length(), json.getBytes(StandardCharsets.UTF_8).length, TokenEstimator.estimate(json), statements, cold, times[2], serialize);
        lines.add("[D35-2 측정] " + label + " | chars=" + m.chars + " | utf8Bytes=" + m.bytes + " | estTokens=" + m.tokens + " | statements=" + m.statements
                + " | firstMicros=" + cold + " | medianMicros=" + m.median + " | serializeMicros=" + serialize);
        assertThat(first).isNotNull();
        return m;
    }

    private record Measured(String label, String json, int chars, int bytes, int tokens, long statements, long first, long median, long serialize) {
    }

    @Test
    @DisplayName("대규모 격리 모델 : 엔터티 150·주제 영역 130(3단계)·3단계 상속·유효 속성 100개 초과·관계 60개 엔터티·긴 설명 — 크기·조회 수·시간")
    void largeModel() {
        GenerationFixture f = GenerationFixture.create(context, "d35-perf-" + System.nanoTime(), DbType.POSTGRESQL);
        String longText = "업무 설명 ".repeat(80); // 480자 (설명 상한 300자로 잘린다)
        // 주제 영역 : 최상위 10 × 2단계 3 × 3단계 3 = 130
        List<Long> leafAreas = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long top = areas.save(f.user, new LogicalSubjectAreaDto.Save(null, f.modelId, null, "영역" + i, longText, null, null)).id();
            for (int j = 0; j < 3; j++) {
                long mid = areas.save(f.user, new LogicalSubjectAreaDto.Save(null, f.modelId, top, "영역" + i + "-" + j, null, null, null)).id();
                for (int k = 0; k < 3; k++) leafAreas.add(areas.save(f.user, new LogicalSubjectAreaDto.Save(null, f.modelId, mid, "영역" + i + "-" + j + "-" + k, null, null, null)).id());
            }
        }
        // 엔터티 150 (긴 설명), 엔터티마다 속성 3 — 140개를 말단 영역에 고루, 10개는 미분류
        List<Long> entities = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            long e = entity(f, "엔터티" + String.format("%03d", i), longText);
            for (int a = 0; a < 3; a++) attr(f, e, "속성" + a, null, a == 0);
            entities.add(e);
        }
        for (int i = 0; i < 140; i++) {
            areas.assign(f.user, f.modelId, new Assignment(JSON.numberNode(leafAreas.get(i % leafAreas.size())), List.of(new AssignmentItem(entities.get(i), JSON.nullNode()))));
        }
        // 3단계 상속 : 루트(속성 40 + 복합 PK 2) → 중간(30) → 말단(35) = 유효 105, 설명 480자. 형제 서브타입 4개
        long root = entity(f, "상속루트", longText);
        List<Long> rootKey = new ArrayList<>();
        for (int a = 0; a < 40; a++) {
            long id = attr(f, root, "루트속성" + a, longText, a < 2);
            if (a < 2) rootKey.add(id);
        }
        logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, root, "루트 PK", true, List.of(rootKey.get(1), rootKey.get(0)), null));
        long middle = entity(f, "상속중간", longText);
        for (int a = 0; a < 30; a++) attr(f, middle, "중간속성" + a, longText, false);
        long leaf = entity(f, "상속말단", longText);
        for (int a = 0; a < 35; a++) attr(f, leaf, "말단속성" + a, longText, false);
        long single = entity(f, "단일서브타입", null);
        attr(f, single, "단일속성", null, false);
        long sibling = entity(f, "형제서브타입", null);
        group(f, root, "루트 그룹", middle, single);
        group(f, middle, "중간 그룹", leaf, sibling);
        // 관계 60개인 엔터티 (허브 → 엔터티 60개)
        long hub = entity(f, "허브", null);
        attr(f, hub, "허브번호", null, true);
        for (int i = 0; i < 60; i++) f.rel("허브관계" + i, hub, entities.get(i), false, 1);
        // 속성 짝이 있는 관계 : 루트(복합 PK) → 엔터티000
        long child = entities.get(0);
        long c1 = attr(f, child, "루트참조1", null, true);
        long c2 = attr(f, child, "루트참조2", null, true);
        LogicalModelDto.Relationship keyed = f.rel("루트 참조", root, child, false, 1);
        f.pairs(keyed, new long[]{rootKey.get(1), c1}, new long[]{rootKey.get(0), c2});
        // 직접 서브타입 25개인 그룹 (목록 요약 20 + 나머지는 상세의 INHERITANCE)
        long many = entity(f, "다수부모", null);
        long[] manyChildren = new long[25];
        for (int i = 0; i < 25; i++) manyChildren[i] = entity(f, "다수서브" + String.format("%02d", i), null);
        group(f, many, "다수 그룹", manyChildren);
        Long project = f.user.getProjectId();
        SecurityTestSupport.clear(); // 이후 조회는 로그인 세션 없이 (MCP·비동기와 같은 조건)

        Measured models = measure("모델 목록", () -> metadata.models(project, null, null));
        Measured overview = measure("모델 개요 (영역 130 → 100 표시, 그룹 2)", () -> metadata.overview(project, new ModelRef(f.modelId, null)));
        Measured list20 = measure("엔터티 목록 20건 (모델 전체)", () -> metadata.searchEntities(project, new EntitySearch(f.modelId, null, null, null, null, null, 0, 20)));
        Measured list5 = measure("엔터티 목록 5건 (모델 전체)", () -> metadata.searchEntities(project, new EntitySearch(f.modelId, null, null, null, null, null, 0, 5)));
        Measured listLast = measure("엔터티 목록 20건 (마지막 페이지)", () -> metadata.searchEntities(project, new EntitySearch(f.modelId, null, null, null, null, null, 9, 20)));
        Long topArea = overviewTop(project, f.modelId);
        Measured listArea = measure("영역 엔터티 목록 (최상위 영역, 하위 포함)", () -> metadata.searchEntities(project,
                new EntitySearch(f.modelId, null, null, topArea, null, null, 0, 20)));
        Measured listArea5 = measure("영역 엔터티 목록 5건 (최상위 영역, 하위 포함)", () -> metadata.searchEntities(project,
                new EntitySearch(f.modelId, null, null, topArea, null, null, 0, 5)));
        Measured listUnclassified = measure("미분류 엔터티 목록", () -> metadata.searchEntities(project, new EntitySearch(f.modelId, null, null, null, null, true, 0, 20)));
        Measured listInheritance = measure("엔터티 목록 (상속 검색어)", () -> metadata.searchEntities(project, new EntitySearch(f.modelId, null, "상속", null, null, null, 0, 20)));
        Measured plain = measure("상세 : 상속 없는 엔터티 (속성 3, 긴 설명)", () -> metadata.entityDetail(project, new EntityRef(entities.get(5), null, null, null)));
        Measured singleLevel = measure("상세 : 단일 상속 (유효 속성 41)", () -> metadata.entityDetail(project, new EntityRef(single, null, null, null)));
        Measured rootDetail = measure("상세 : 루트 슈퍼타입 (복합 PK, 속성 40 × 설명 300자)", () -> metadata.entityDetail(project, new EntityRef(root, null, null, null)));
        Measured multi = measure("상세 기본 : 3단계 상속 (유효 속성 105 → 100 표시, 속성 설명 제외)", () -> metadata.entityDetail(project, new EntityRef(leaf, null, null, null)));
        Measured manyRelationships = measure("상세 : 관계 60 (→ 50 표시)", () -> metadata.entityDetail(project, new EntityRef(hub, null, null, null)));
        Measured keyedChild = measure("상세 : 속성 짝 관계 + 허브 관계", () -> metadata.entityDetail(project, new EntityRef(child, null, null, null)));
        // D35-3a : 속성 설명 포함·항목별 조회
        Measured multiDesc = measure("상세 ALL + 속성 설명 포함 : 3단계 상속 (속성 100 × 설명 300자)", () -> metadata.entityDetail(project,
                new EntityRef(leaf, null, null, null, null, true, null, null, null)));
        Measured attrDesc20 = measure("ATTRIBUTES + 설명 포함 limit 20 (첫 페이지)", () -> metadata.entityDetail(project,
                new EntityRef(leaf, null, null, null, "ATTRIBUTES", true, null, 0, 20)));
        Measured attrDesc100 = measure("ATTRIBUTES + 설명 포함 limit 100", () -> metadata.entityDetail(project,
                new EntityRef(leaf, null, null, null, "ATTRIBUTES", true, null, 0, 100)));
        Measured attr100 = measure("ATTRIBUTES 설명 제외 limit 100", () -> metadata.entityDetail(project, new EntityRef(leaf, null, null, null, "ATTRIBUTES", null, null, 0, 100)));
        Measured identifiers = measure("IDENTIFIERS (말단)", () -> metadata.entityDetail(project, new EntityRef(leaf, null, null, null, "IDENTIFIERS", null, null, null, null)));
        Measured relations50 = measure("RELATIONS limit 50 (허브)", () -> metadata.entityDetail(project, new EntityRef(hub, null, null, null, "RELATIONS", null, null, 0, 50)));
        Measured inheritanceMany = measure("INHERITANCE 그룹 하나 limit 20 (직접 서브타입 25)", () -> metadata.entityDetail(project,
                new EntityRef(many, null, null, null, "INHERITANCE", null, null, null, null)));
        Measured manyDetail = measure("상세 ALL : 직접 서브타입 25 (→ 20 표시)", () -> metadata.entityDetail(project, new EntityRef(many, null, null, null)));
        Measured overviewAreas = measure("개요 SUBJECT_AREAS limit 100 (설명 300자 영역 포함)", () -> metadata.overview(project,
                new LogicalMetadataDto.OverviewRequest(f.modelId, null, "SUBJECT_AREAS", 0, 100)));
        Measured listMany = measure("엔터티 목록 (다수부모 — 직접 서브타입 25 → 20)", () -> metadata.searchEntities(project, new EntitySearch(f.modelId, null, "다수부모", null, null, null, 0, 20)));

        // 조회 수 : 페이지 크기·페이지 위치와 무관 (엔터티마다 조회하지 않는다)
        // 같은 조건(행마다 소속 영역이 있는 범위)에서 5건·20건의 조회 수가 같다. 모델 전체 목록은 페이지에 소속 영역이 있는 행이 하나도 없으면 영역 트리(4번)를 읽지 않아
        // 더 적을 수 있다 (DB 마다 한글 정렬이 달라 첫 페이지 구성이 다르다 — PostgreSQL 측정에서 확인)
        assertThat(listArea.statements()).isEqualTo(listArea5.statements());
        // 모델 전체 목록은 페이지마다 소속 영역이 있는 행이 있느냐에 따라 영역 트리(4번)를 더 읽는다 — 상한만 본다
        for (Measured m : List.of(list5, list20, listLast, listUnclassified, listInheritance, listMany)) assertThat(m.statements()).as(m.label()).isLessThanOrEqualTo(20);
        assertThat(listArea.statements()).isLessThanOrEqualTo(20);
        // 상세 조회 수 : 상속 깊이·속성 수·관계 수와 무관한 상수 범위 (엔터티·속성·관계마다 조회하지 않는다)
        for (Measured m : List.of(plain, singleLevel, rootDetail, multi, manyRelationships, keyedChild)) assertThat(m.statements()).as(m.label()).isLessThanOrEqualTo(25);
        assertThat(models.statements()).isLessThanOrEqualTo(6);
        assertThat(overview.statements()).isLessThanOrEqualTo(12);

        // 대표 응답은 상한 안
        for (Measured m : List.of(models, overview, list20, listArea, listUnclassified, listInheritance, plain, singleLevel, rootDetail, multi, manyRelationships, keyedChild,
                attrDesc20, attr100, identifiers, relations50, inheritanceMany, manyDetail, overviewAreas, listMany)) {
            assertThat(m.chars()).as(m.label()).isLessThan(RESULT_MAX_CHARS);
        }
        // 자른 목록은 전체 수와 함께
        LogicalMetadataDto.ModelOverview o = metadata.overview(project, new ModelRef(f.modelId, null));
        assertThat(o.subjectAreasPaging().totalCount()).isEqualTo(130);
        assertThat(o.subjectAreas()).hasSize(100);
        assertThat(o.subjectAreasPaging().complete()).isFalse();
        LogicalMetadataDto.EntityDetail deep = metadata.entityDetail(project, new EntityRef(leaf, null, null, null));
        assertThat(deep.attributesPaging().totalCount()).isEqualTo(105);
        assertThat(deep.attributes()).hasSize(100);
        assertThat(deep.attributesPaging().hasMore()).isTrue();
        assertThat(deep.identifiers().getFirst().attributes()).extracting(LogicalMetadataDto.IdentifierPart::attributeId).containsExactly(rootKey.get(1), rootKey.get(0));
        LogicalMetadataDto.EntityDetail hubDetail = metadata.entityDetail(project, new EntityRef(hub, null, null, null));
        assertThat(hubDetail.relationshipsPaging().totalCount()).isEqualTo(60);
        assertThat(hubDetail.relationships()).hasSize(50);
        assertThat(hubDetail.relationshipsPaging().hasMore()).isTrue();
        LogicalMetadataDto.EntityList last = metadata.searchEntities(project, new EntitySearch(f.modelId, null, null, null, null, null, 9, 20));
        assertThat(last.paging().totalCount()).isEqualTo(182);
        assertThat(last.paging().hasMore()).isFalse();
        lines.add("[D35-2 측정] 상한 판정 : 설명 포함 ALL " + (multiDesc.chars() < RESULT_MAX_CHARS ? "상한 안" : "상한 초과 (실행 경계가 RESULT_TOO_LARGE → ATTRIBUTES 페이지로)")
                + ", 설명 포함 ATTRIBUTES limit 100 " + (attrDesc100.chars() < RESULT_MAX_CHARS ? "상한 안" : "상한 초과 (→ limit 20)"));

        // D35-3a : 표시 상한을 넘는 나머지를 next 로 빠짐·겹침 없이 받는다 (고정 데이터)
        List<Long> expectedAttributes = subtypes.selectInheritanceView(project, leaf).attributes().stream().map(LogicalSubtypeDto.EffectiveAttribute::attributeId).toList();
        assertThat(follow(deep.attributes().stream().map(LogicalMetadataDto.AttributeItem::attributeId).toList(), deep.attributesPaging(), project,
                d -> d.attributes().stream().map(LogicalMetadataDto.AttributeItem::attributeId).toList(), LogicalMetadataDto.EntityDetail::attributesPaging))
                .containsExactlyElementsOf(expectedAttributes);
        LogicalMetadataDto.EntityDetail first20 = metadata.entityDetail(project, new EntityRef(leaf, null, null, null, "ATTRIBUTES", true, null, 0, 20));
        assertThat(follow(first20.attributes().stream().map(LogicalMetadataDto.AttributeItem::attributeId).toList(), first20.attributesPaging(), project,
                d -> d.attributes().stream().map(LogicalMetadataDto.AttributeItem::attributeId).toList(), LogicalMetadataDto.EntityDetail::attributesPaging))
                .containsExactlyElementsOf(expectedAttributes);
        assertThat(follow(hubDetail.relationships().stream().map(LogicalMetadataDto.RelationshipItem::relationshipId).toList(), hubDetail.relationshipsPaging(), project,
                d -> d.relationships().stream().map(LogicalMetadataDto.RelationshipItem::relationshipId).toList(), LogicalMetadataDto.EntityDetail::relationshipsPaging))
                .hasSize(60).doesNotHaveDuplicates();
        LogicalMetadataDto.EntityItem manyRow = metadata.searchEntities(project, new EntitySearch(f.modelId, null, "다수부모", null, null, null, 0, 20)).entities().getFirst();
        LogicalMetadataDto.GroupSummary manyGroup = manyRow.inheritance().subtypeGroups().getFirst();
        assertThat(manyGroup.subtypes()).hasSize(20);
        assertThat(follow(manyGroup.subtypes().stream().map(LogicalMetadataDto.EntityName::entityId).toList(), manyGroup.subtypesPaging(), project,
                d -> d.inheritance().subtypeGroups().getFirst().subtypes().stream().map(LogicalMetadataDto.MemberItem::entityId).toList(),
                d -> d.inheritance().subtypeGroups().getFirst().subtypesPaging()))
                .containsExactly(java.util.Arrays.stream(manyChildren).boxed().toArray(Long[]::new));
        List<Long> areaIds = new ArrayList<>(o.subjectAreas().stream().map(LogicalMetadataDto.SubjectAreaItem::subjectAreaId).toList());
        LogicalMetadataDto.NextCall next = o.subjectAreasPaging().next();
        while (next != null) {
            LogicalMetadataDto.ModelOverview part = metadata.overview(project, new LogicalMetadataDto.OverviewRequest(next.modelId(), null, next.section(), next.page(), next.limit()));
            part.subjectAreas().forEach(a -> areaIds.add(a.subjectAreaId()));
            next = part.subjectAreasPaging().next();
        }
        assertThat(areaIds).hasSize(130).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("예제 모델 (테스트 DB 의 V1 예제 프로젝트 0, 읽기만) : 모델 목록·개요·엔터티 목록 20건·관계가 가장 많은 엔터티·속성이 가장 많은 엔터티 상세")
    void exampleModel() {
        Long project = 0L;
        Long model = jdbc.queryForObject("select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA'", Long.class);
        Long mostRelated = jdbc.queryForObject("select e.LGCL_ENTT_SN from TB_LGCL_ENTT e where e.LGCL_MDL_SN = ? order by (select count(*) from TB_LGCL_REL r"
                + " where r.ORGNL_ENTT_SN = e.LGCL_ENTT_SN or r.TRGT_ENTT_SN = e.LGCL_ENTT_SN) desc, e.LGCL_ENTT_SN fetch first 1 rows only", Long.class, model);
        Long mostAttributes = jdbc.queryForObject("select e.LGCL_ENTT_SN from TB_LGCL_ENTT e where e.LGCL_MDL_SN = ? order by (select count(*) from TB_LGCL_ATRB a"
                + " where a.LGCL_ENTT_SN = e.LGCL_ENTT_SN) desc, e.LGCL_ENTT_SN fetch first 1 rows only", Long.class, model);
        SecurityTestSupport.clear();
        Measured models = measure("예제 : 모델 목록", () -> metadata.models(project, null, null));
        Measured overview = measure("예제 : 모델 개요 (영역 0, 그룹 0)", () -> metadata.overview(project, new ModelRef(model, null)));
        Measured list = measure("예제 : 엔터티 목록 20건", () -> metadata.searchEntities(project, new EntitySearch(model, null, null, null, null, null, 0, 20)));
        Measured related = measure("예제 : 관계가 가장 많은 엔터티 상세", () -> metadata.entityDetail(project, new EntityRef(mostRelated, null, null, null)));
        Measured wide = measure("예제 : 속성이 가장 많은 엔터티 상세", () -> metadata.entityDetail(project, new EntityRef(mostAttributes, null, null, null)));
        lines.add("[D35-2 측정] 예제 : 관계 최다 엔터티 관계 수 = " + metadata.entityDetail(project, new EntityRef(mostRelated, null, null, null)).relationshipsPaging().totalCount()
                + ", 속성 최다 엔터티 속성 수 = " + metadata.entityDetail(project, new EntityRef(mostAttributes, null, null, null)).attributesPaging().totalCount());
        for (Measured m : List.of(models, overview, list, related, wide)) assertThat(m.chars()).as(m.label()).isLessThan(RESULT_MAX_CHARS);
    }

    /** next 를 따라 마지막까지 받아 모은다 (서버가 이어 읽지 않는다 — 호출하는 쪽이 next 로 부른다) */
    private List<Long> follow(List<Long> first, LogicalMetadataDto.Paging paging, Long project, java.util.function.Function<LogicalMetadataDto.EntityDetail, List<Long>> ids,
                              java.util.function.Function<LogicalMetadataDto.EntityDetail, LogicalMetadataDto.Paging> pagingOf) {
        List<Long> all = new ArrayList<>(first);
        LogicalMetadataDto.NextCall next = paging.next();
        int guard = 0;
        while (next != null && guard++ < 50) {
            assertThat(next.tool()).isEqualTo("getLogicalEntityDetail");
            LogicalMetadataDto.EntityDetail part = metadata.entityDetail(project, new EntityRef(next.entityId(), null, null, null, next.section(),
                    next.includeAttributeDescriptions(), next.subtypeGroupId(), next.page(), next.limit()));
            all.addAll(ids.apply(part));
            next = pagingOf.apply(part).next();
        }
        return all;
    }

    private Long overviewTop(Long project, Long modelId) {
        return metadata.overview(project, new ModelRef(modelId, null)).subjectAreas().stream().filter(a -> a.depth() == 1).findFirst().orElseThrow().subjectAreaId();
    }
}
