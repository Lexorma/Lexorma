package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto.MemberInput;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-6 최종 보완 — 논리 모델·주제 영역·슈퍼타입/서브타입 대규모 측정. 측정용이라 LEXORMA_PERF=true 일 때만 실행한다 (일반 빌드에서는 건너뜀).
 * <p>
 * 모델 (JDBC 로 넣는다 — 제품의 공식 지원 한계가 아니다) :
 * <ul>
 *   <li>규모 4가지 : 엔터티 100·1,000·3,000·10,000, 그룹 10·100·300·1,000, 상속 연결 = 그룹 × 3. 그룹 셋마다 루트 r ⊃ {m1,m2,m3}(배타·완전, 분류 속성 = r 의 첫 속성),
 *       m1 ⊃ {x1,x2,x3}(3단계), r ⊃ {y1,y2,y3}(같은 슈퍼타입의 두 번째 그룹, 중복 가능). 엔터티마다 속성 8개(이름 서로 다름), 서브타입이 아닌 엔터티만 주 식별자,
 *       관계 = 앞 → 다음 사슬 + 허브(첫 엔터티)에서 20번째마다, 주제 영역 10개에 엔터티를 i % 11 로 나눔(10 = 미분류)</li>
 *   <li>같은 규모의 상속 없는 모델 (3,000·10,000) — 그룹 조회가 더하는 비용 비교</li>
 *   <li>구성원 600개 그룹 하나, 구성원 1,001개 그룹 하나 (교환 파일 자식 목록 1,000행 공통 제한 확인)</li>
 * </ul>
 * 읽기는 준비 1번 뒤 10번, 쓰기는 같은 트랜잭션 안에서 실행하고 되돌린다(준비 1번 뒤 5번), 내보내기 3번, 가져오기 1번. 기록 : 중앙값·최소~최대(ms), Hibernate 준비 문장 수,
 * HQL 반환 행 수 + 엔터티 적재 수, 응답 JSON 크기, 호출 스레드 할당 바이트. 결과는 build/reports/m6-final-performance-&lt;db&gt;.txt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "LEXORMA_PERF", matches = "true")
class LogicalM6FinalPerformanceTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int ATTRIBUTES = 8;
    private static final AtomicLong IDS = new AtomicLong(5_000_000_000_000L + (System.currentTimeMillis() % 1_000L) * 10_000_000_000L);

    @Autowired ApplicationContext context;
    @Autowired LogicalModelService logical;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired LogicalSubjectAreaService areas;
    @Autowired ModelVersionService versions;
    @Autowired ModelExchangeService exchange;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate tx;

    private final List<String> lines = new ArrayList<>();
    private String db;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 측정한 모델 하나 */
    private record Seeded(GenerationFixture f, long[] entities, long[] groups, long root, long level2, long level3, long subtypeParentRelationship) {
    }

    @Test
    @DisplayName("규모별 논리 모델·주제 영역·상속 조회와 쓰기, 상속 없는 모델 비교, 큰 그룹")
    void measure() throws IOException {
        db = "postgresql";
        lines.add("db | model | call | median ms | min~max ms | statements | HQL rows | entity loads | JSON KB | alloc MB | note");
        int[][] sizes = {{100, 10}, {1000, 100}, {3000, 300}, {10000, 1000}};
        for (int[] size : sizes) inherited(size[0], size[1]);
        for (int size : new int[]{3000, 10000}) plain(size);
        bigGroup(600, true);
        bigGroup(1001, false);
        Runtime runtime = Runtime.getRuntime();
        lines.add(db + " | heap | used MB after all | " + (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024) + " | max " + runtime.maxMemory() / (1024 * 1024) + " MB");
        lines.forEach(line -> System.out.println("PERF|" + line));
        Path out = Path.of("build/reports/m6-final-performance-" + db + ".txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);
    }

    // ------------------------------------------------------------------ 모델별 측정

    private void inherited(int entityCount, int groupCount) {
        Seeded s = seed("상속 " + entityCount, entityCount, groupCount, true, 0);
        String model = "E" + entityCount + "/G" + groupCount;
        GenerationFixture f = s.f;
        reads(model, s);
        long groupA = s.groups[0];
        LogicalSubtypeDto.Group a = subtypes.selectGroup(f.user, groupA);
        read(model, "그룹 상세 (구성원 3)", () -> subtypes.selectGroup(f.user, groupA));
        write(model, "그룹 수정 (설명만, 되돌림)", () -> save(f, a, a.subtypes().stream().map(m -> new MemberInput(m.entityId(), m.classificationValue())).toList(), "설명 " + System.nanoTime()));
        // m1 을 빼면 하위 그룹(m1 ⊃ x)의 상속 분류 속성이 무효가 되어 거절된다 — 영향 검사 전체(하위 트리·참조)의 비용을 잰다
        write(model, "상속 해제 영향 (3단계 하위를 가진 m1 빼기 → 참조 무효로 거절, 되돌림)", () -> {
            try {
                return save(f, a, a.subtypes().stream().skip(1).map(m -> new MemberInput(m.entityId(), m.classificationValue())).toList(), null);
            } catch (com.lexorma.common.exception.CustomException e) {
                return e.getErrorCode().name();
            }
        });
        write(model, "상속 해제 (하위 없는 m3 빼기, 되돌림)", () -> save(f, a, a.subtypes().stream().limit(2).map(m -> new MemberInput(m.entityId(), m.classificationValue())).toList(), null));
        write(model, "승인 영향 (루트 승인, 되돌림)", () -> {
            logical.updateEntityApproval(f.user, s.root, true, null);
            return null;
        });
        AccountDto member = f.member();
        write(model, "모델 버전 스냅샷 (되돌림)", () -> versions.create(member, new ModelVersionDto.Create("perf-" + System.nanoTime(), null)));
        exchange(model, s, member);
    }

    private void plain(int entityCount) {
        Seeded s = seed("상속 없음 " + entityCount, entityCount, 0, false, 0);
        String model = "E" + entityCount + "/상속 없음";
        GenerationFixture f = s.f;
        read(model, "모델 요약", () -> logical.selectSummary(f.user, f.modelId));
        read(model, "엔터티 목록 100", () -> logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null));
        read(model, "관계 목록 100", () -> logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, null));
        read(model, "ERD 부분 100", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 100));
        read(model, "ERD 부분 300", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 300));
        for (int depth = 1; depth <= 3; depth++) {
            int d = depth;
            read(model, "이웃 " + d + "단계 (허브)", () -> logical.selectNeighbors(f.user, f.modelId, s.entities[0], d));
        }
        read(model, "엔터티 상속 정보 (상속 없음)", () -> subtypes.selectInheritance(f.user, s.entities[5]));
    }

    private void reads(String model, Seeded s) {
        GenerationFixture f = s.f;
        read(model, "모델 요약", () -> logical.selectSummary(f.user, f.modelId));
        read(model, "엔터티 목록 100", () -> logical.selectEntityRows(f.user, f.modelId, null, 0, 100, null));
        read(model, "관계 목록 100", () -> logical.selectRelationshipRows(f.user, f.modelId, null, null, 0, 100, null));
        read(model, "주제 영역 트리", () -> areas.selectTree(f.user, f.modelId));
        read(model, "서브타입 그룹 목록 100", () -> subtypes.selectGroupRows(f.user, f.modelId, null, 0, 100, null));
        read(model, "유효 속성 (3단계)", () -> subtypes.selectEffectiveAttributes(f.user, s.level3));
        read(model, "상속 정보 = 유효 식별자·상속 관계 (3단계)", () -> subtypes.selectInheritance(f.user, s.level3));
        read(model, "관계 속성 짝 후보 (부모 = 서브타입)", () -> logical.selectRelationshipPairs(f.user, s.subtypeParentRelationship));
        read(model, "ERD 부분 100", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 100));
        read(model, "ERD 부분 300", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 300));
        for (int depth = 1; depth <= 3; depth++) {
            int d = depth;
            read(model, "이웃 " + d + "단계 (허브)", () -> logical.selectNeighbors(f.user, f.modelId, s.entities[0], d));
        }
        read(model, "이웃 2단계 (루트 슈퍼타입)", () -> logical.selectNeighbors(f.user, f.modelId, s.root, 2));
    }

    private void bigGroup(int members, boolean full) {
        int entityCount = members + 400;
        Seeded s = seed("큰 그룹 " + members, entityCount, 0, false, members + 1); // 엔터티 1 ~ members+1 은 주 식별자 없음 (서브타입 후보)
        GenerationFixture f = s.f;
        String model = "E" + entityCount + "/그룹 구성원 " + members;
        List<MemberInput> inputs = new ArrayList<>();
        for (int i = 1; i <= members; i++) inputs.add(new MemberInput(s.entities[i], "V" + i));
        long start = System.nanoTime();
        LogicalSubtypeDto.Group big = subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, s.entities[0], "큰 그룹", null, false, false, null, inputs, null));
        lines.add(db + " | " + model + " | 그룹 등록 (실제 저장 1번) | " + (System.nanoTime() - start) / 1_000_000 + " | - | - | - | - | - | - | API");
        if (full) {
            read(model, "그룹 상세 (구성원 " + members + ")", () -> subtypes.selectGroup(f.user, big.id()));
            read(model, "서브타입 그룹 목록 100", () -> subtypes.selectGroupRows(f.user, f.modelId, null, 0, 100, null));
            LogicalSubtypeDto.Group now = subtypes.selectGroup(f.user, big.id());
            write(model, "그룹 수정 (구성원 전체 그대로 + 설명, 되돌림)", () -> save(f, now, inputs, "설명 " + System.nanoTime()));
            List<MemberInput> grown = new ArrayList<>(inputs);
            grown.add(new MemberInput(s.entities[members + 1], "V+"));
            write(model, "그룹 수정 (구성원 1개 추가, 되돌림)", () -> save(f, now, grown, null));
            read(model, "ERD 부분 (슈퍼타입만 — 범위 밖 상속 수)", () -> logical.selectGraphPart(f.user, f.modelId, "엔터티 00000", 0, 100));
            read(model, "ERD 부분 300 (그룹 일부)", () -> logical.selectGraphPart(f.user, f.modelId, null, 0, 300));
            read(model, "이웃 1단계 (슈퍼타입, 300 한도)", () -> logical.selectNeighbors(f.user, f.modelId, s.entities[0], 1));
            read(model, "상속 정보 (구성원 하나)", () -> subtypes.selectInheritance(f.user, s.entities[members / 2]));
            read(model, "유효 속성 (구성원 하나)", () -> subtypes.selectEffectiveAttributes(f.user, s.entities[members / 2]));
            exchange(model, s, f.member());
        } else {
            // 교환 파일의 자식 목록은 1,000행까지 (기존 공통 제한) — 구성원 1,001개 그룹은 내보낼 수 있지만 가져오기에서 거절되는지 기록한다
            ModelExchangeDto.Document document = exchange.export(f.member());
            GenerationFixture target = GenerationFixture.create(context, "perf-big-import-" + System.nanoTime(), DbType.POSTGRESQL);
            AccountDto targetMember = target.member();
            String note;
            try {
                exchange.plan(targetMember, new ModelExchangeDto.PlanRequest(targetMember.getProjectId(), document, selection(f)));
                note = "가져오기 계획 성공";
            } catch (com.lexorma.common.exception.CustomException e) {
                note = "가져오기 거절 : " + e.getErrorCode() + " " + e.getCustomMessage();
            }
            lines.add(db + " | " + model + " | 교환 형식 5 가져오기 (구성원 " + members + ") | - | - | - | - | - | - | - | " + note);
        }
    }

    private void exchange(String model, Seeded s, AccountDto member) {
        GenerationFixture f = s.f;
        ModelExchangeDto.Document[] document = new ModelExchangeDto.Document[1];
        measure(model, "내보내기 형식 5", 3, false, () -> document[0] = exchange.export(member), "");
        int objects = document[0].model().values().stream().mapToInt(List::size).sum();
        if (objects > 50_000) {
            lines.add(db + " | " + model + " | 가져오기 형식 5 | - | - | - | - | - | - | - | 건너뜀 : 파일 객체 " + objects + "개 > 기존 제한 50,000 (제한은 완화하지 않음)");
            return;
        }
        GenerationFixture target = GenerationFixture.create(context, "perf-import-" + System.nanoTime(), DbType.POSTGRESQL);
        AccountDto targetMember = target.member();
        ModelExchangeDto.Plan[] plan = new ModelExchangeDto.Plan[1];
        measure(model, "가져오기 계획 형식 5 (객체 " + objects + ")", 1, false,
                () -> plan[0] = exchange.plan(targetMember, new ModelExchangeDto.PlanRequest(targetMember.getProjectId(), document[0], selection(f))), "");
        measure(model, "가져오기 반영 형식 5 (객체 " + objects + ")", 1, false,
                () -> exchange.apply(targetMember, new ModelExchangeDto.ApplyRequest(targetMember.getProjectId(), document[0], selection(f), plan[0].planToken(), plan[0].requestId())), "");
        assertThat(jdbc.queryForObject("select count(*) from TB_LGCL_SBTYP where PRJ_SN = ?", Integer.class, targetMember.getProjectId()))
                .isEqualTo(jdbc.queryForObject("select count(*) from TB_LGCL_SBTYP where LGCL_MDL_SN = ?", Integer.class, f.modelId));
        SecurityTestSupport.loginAs(context.getBean(com.lexorma.core.project.repository.ProjectRepository.class), f.user.getProjectId(), f.user.getId(), "ROLE_ADMIN");
    }

    private ModelExchangeDto.Selection selection(GenerationFixture f) {
        String name = jdbc.queryForObject("select MDL_NM from TB_LGCL_MDL where LGCL_MDL_SN = ?", String.class, f.modelId);
        return new ModelExchangeDto.Selection(List.of(), List.of(name), List.of());
    }

    private LogicalSubtypeDto.Group save(GenerationFixture f, LogicalSubtypeDto.Group group, List<MemberInput> members, String description) {
        return subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(group.id(), f.modelId, group.supertypeEntityId(), group.groupName(),
                description == null ? group.groupDescription() : description, group.overlapping(), group.total(), group.classificationAttributeId(), members, null));
    }

    // ------------------------------------------------------------------ 측정

    private void read(String model, String call, Supplier<Object> action) {
        measure(model, call, 10, false, action, "");
    }

    private void write(String model, String call, Supplier<Object> action) {
        measure(model, call, 5, true, action, "되돌림");
    }

    private void measure(String model, String call, int runs, boolean rollback, Supplier<Object> action, String note) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        Supplier<Object> run = rollback ? () -> tx.execute(status -> {
            Object result = action.get();
            status.setRollbackOnly();
            return result;
        }) : action;
        if (runs > 1) run.get(); // 준비
        long[] ms = new long[runs];
        long statements = 0, rows = 0, loads = 0, allocated = 0;
        Object result = null;
        for (int i = 0; i < runs; i++) {
            stats.clear();
            long before = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            result = run.get();
            ms[i] = (System.nanoTime() - start) / 1_000_000;
            allocated += threads.getThreadAllocatedBytes(thread) - before;
            statements += stats.getPrepareStatementCount();
            loads += stats.getEntityLoadCount();
            for (String query : stats.getQueries()) rows += stats.getQueryStatistics(query).getExecutionRowCount();
        }
        long[] sorted = ms.clone();
        Arrays.sort(sorted);
        int json = result == null ? 0 : JSON.writeValueAsBytes(result).length;
        lines.add(db + " | " + model + " | " + call + " | " + sorted[runs / 2] + " | " + sorted[0] + "~" + sorted[runs - 1] + " | " + statements / runs + " | " + rows / runs
                + " | " + loads / runs + " | " + json / 1024 + " | " + allocated / runs / (1024 * 1024) + " | " + note);
    }

    // ------------------------------------------------------------------ 데이터

    /**
     * withGroups = false : 그룹 없음 (큰 그룹은 호출한 쪽이 서비스로 만든다 — keyless = 주 식별자를 두지 않을 엔터티 1~keyless).
     * 엔터티 ID 순서 = 이름 순서 ("엔터티 00000" …)
     */
    private Seeded seed(String name, int entityCount, int groupCount, boolean withGroups, int keyless) {
        GenerationFixture f = GenerationFixture.create(context, "perf-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long projectId = f.user.getProjectId();
        long term = f.term("PERF_ATTR", "VARCHAR", 20);
        int n = entityCount;
        long base = IDS.getAndAdd(20L * n + 10L * Math.max(groupCount, 1) + 1_000);
        long attrBase = base + n, idBase = base + 9L * n, idAttrBase = base + 10L * n, relBase = base + 11L * n, areaBase = base + 13L * n, memberOfAreaBase = base + 13L * n + 20;
        long groupBase = base + 15L * n, subtypeBase = groupBase + Math.max(groupCount, 1);
        long[] entities = new long[n];
        for (int i = 0; i < n; i++) entities[i] = base + i;

        // 그룹 구조 : 그룹 셋마다 엔터티 10개 (r, m1~m3, x1~x3, y1~y3)
        boolean[] subtype = new boolean[n]; // 주 식별자 없는 엔터티 (서브타입, 큰 그룹의 후보)
        for (int i = 1; i <= keyless && i < n; i++) subtype[i] = true;
        List<Object[]> groups = new ArrayList<>(), members = new ArrayList<>();
        long[] groupIds = new long[groupCount];
        for (int g = 0; g < groupCount; g++) {
            int t = (g / 3) * 10;
            int kind = g % 3;
            int supertype = kind == 1 ? t + 1 : t;
            int first = kind == 0 ? t + 1 : kind == 1 ? t + 4 : t + 7;
            groupIds[g] = groupBase + g;
            long classification = attrBase + (long) t * ATTRIBUTES; // 루트의 첫 속성 (2단계 그룹에서는 상속 분류 속성)
            groups.add(new Object[]{groupIds[g], projectId, f.modelId, entities[supertype], "그룹 " + g, kind == 2, kind == 0, kind == 2 ? null : classification, kind + 1});
            for (int m = 0; m < 3; m++) {
                subtype[first + m] = true;
                members.add(new Object[]{subtypeBase + 3L * g + m, projectId, f.modelId, groupIds[g], entities[first + m], kind == 2 ? null : "C" + m, m + 1});
            }
        }
        List<Object[]> entityRows = new ArrayList<>(), attributes = new ArrayList<>(), identifiers = new ArrayList<>(), identifierAttributes = new ArrayList<>(), relationships = new ArrayList<>();
        List<Object[]> areaRows = new ArrayList<>(), areaMembers = new ArrayList<>();
        for (int a = 0; a < 10; a++) areaRows.add(new Object[]{areaBase + a, projectId, f.modelId, "영역 " + a, a + 1});
        for (int i = 0; i < n; i++) {
            entityRows.add(new Object[]{entities[i], projectId, f.modelId, "엔터티 " + String.format("%05d", i), "측정 엔터티 " + i});
            for (int a = 0; a < ATTRIBUTES; a++) attributes.add(new Object[]{attrBase + (long) i * ATTRIBUTES + a, entities[i], "속성 " + i + "-" + a, a + 1, term});
            if (!subtype[i]) {
                identifiers.add(new Object[]{idBase + i, entities[i], "식별자", true});
                identifierAttributes.add(new Object[]{idAttrBase + i, idBase + i, attrBase + (long) i * ATTRIBUTES, 1});
            }
            if (i > 0) relationships.add(new Object[]{relBase + i, projectId, f.modelId, entities[i - 1], entities[i], "사슬 " + i});
            if (i > 0 && i % 20 == 0) relationships.add(new Object[]{relBase + n + i, projectId, f.modelId, entities[0], entities[i], "허브 " + i});
            if (i % 11 != 10) areaMembers.add(new Object[]{memberOfAreaBase + i, projectId, f.modelId, areaBase + i % 11, entities[i]});
        }
        jdbc.batchUpdate("insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, ENTT_EXPLN, APRV_YN, REG_DT, MDFCN_DT) values (?, ?, ?, ?, ?, false, current_timestamp, current_timestamp)", entityRows);
        jdbc.batchUpdate("insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, SORT_ORD, STD_TRM_SN) values (?, ?, ?, ?, ?)", attributes);
        jdbc.batchUpdate("insert into TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN) values (?, ?, ?, ?)", identifiers);
        jdbc.batchUpdate("insert into TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values (?, ?, ?, ?)", identifierAttributes);
        jdbc.batchUpdate("insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, IDNTF_YN, ORGNL_MIN_CNT, TRGT_MIN_CNT, REG_DT, MDFCN_DT)"
                + " values (?, ?, ?, ?, ?, ?, false, 1, 0, current_timestamp, current_timestamp)", relationships);
        jdbc.batchUpdate("insert into TB_LGCL_SBJ_AREA(LGCL_SBJ_AREA_SN, PRJ_SN, LGCL_MDL_SN, SBJ_AREA_NM, SORT_ORD, REG_DT, MDFCN_DT) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)", areaRows);
        jdbc.batchUpdate("insert into TB_LGCL_SBJ_AREA_ENTT(LGCL_SBJ_AREA_ENTT_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBJ_AREA_SN, LGCL_ENTT_SN, REG_DT, MDFCN_DT) values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)", areaMembers);
        if (withGroups && groupCount > 0) {
            jdbc.batchUpdate("insert into TB_LGCL_SBTYP_GROUP(LGCL_SBTYP_GROUP_SN, PRJ_SN, LGCL_MDL_SN, SPRTYP_ENTT_SN, SBTYP_GROUP_NM, DPCN_PSBLTY_YN, TOTL_YN, CLSF_ATRB_SN, SORT_ORD, REG_DT, MDFCN_DT)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)", groups);
            jdbc.batchUpdate("insert into TB_LGCL_SBTYP(LGCL_SBTYP_SN, PRJ_SN, LGCL_MDL_SN, LGCL_SBTYP_GROUP_SN, SBTYP_ENTT_SN, CLSF_VL, SORT_ORD, REG_DT, MDFCN_DT)"
                    + " values (?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)", members);
        }
        // 관계 m1 → m2 (부모 = 서브타입 : 짝 후보는 루트의 상속 주 식별자) — 사슬의 i = 2 관계
        return new Seeded(f, entities, groupIds, entities[0], entities[1], entities[Math.min(4, n - 1)], relBase + 2);
    }
}
