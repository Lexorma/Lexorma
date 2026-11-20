package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.domain.dto.ErdDto.RelationScope;
import com.lexorma.data.erd.service.ErdService;
import com.lexorma.data.erd.service.PhysicalCardinality;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Member;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-2 개선 2차 (docs/completed/physical-erd-m2-improvement.md 21장) : 테이블 찾기, 범위 밖 관계 수(INNER)와 테이블별 관계 목록,
 * 예전 응답(ALL) 호환, 이웃 탐색 결과 유지, 저장된 PK·UNIQUE·인덱스로 판단한 카디널리티, 프로젝트 격리.
 * 기본은 H2. PostgreSQL 은 SPRING_DATASOURCE_URL 로 빈 격리 DB 를 지정해 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ErdM2ImprovementIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired ErdService erdService;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate tx;

    private static final ErdDto.Search ALL = new ErdDto.Search(null, null, null, null);

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 허브 HUB(PK HUB_SN) ← 자식 C0..C(n-1) (단일 FK), CHILD 는 HUB 를 두 FK 로(평행), 복합 FK·자기 참조 */
    private record Model(AccountDto admin, TableInfo hub, TableInfo parent, TableInfo child, List<TableInfo> spokes) {
    }

    private Model model(String name, int spokes) {
        AccountDto admin = loginAsAdmin(name);
        long p = admin.getProjectId();
        TableInfo hub = table(p, "TB_HUB", null);
        ColumnInfo hubSn = column(p, hub, "HUB_SN", true, false, 1);
        TableInfo parent = table(p, "TB_PARENT", null);
        ColumnInfo p1 = column(p, parent, "P1", true, false, 1);
        ColumnInfo p2 = column(p, parent, "P2", true, false, 2);
        TableInfo child = table(p, "TB_CHILD", null);
        ColumnInfo c1 = column(p, child, "C1", true, false, 1);
        ColumnInfo c2 = column(p, child, "C2", false, false, 2);
        ColumnInfo hubA = column(p, child, "HUB_A", false, true, 3);
        ColumnInfo hubB = column(p, child, "HUB_B", false, false, 4);
        ColumnInfo up = column(p, child, "UP_C1", false, true, 5);
        relation(admin, child, parent, "FK", c1, p1, c2, p2); // 복합 FK (C1 PK + C2) → 부분 식별
        relation(admin, child, hub, "FK", hubA, hubSn);       // 평행 FK 1
        relation(admin, child, hub, "FK", hubB, hubSn);       // 평행 FK 2
        relation(admin, child, child, "FK", up, c1);          // 자기 참조
        List<TableInfo> list = new ArrayList<>();
        for (int i = 0; i < spokes; i++) {
            TableInfo spoke = table(p, "TB_SPOKE_%03d".formatted(i), null);
            relation(admin, spoke, hub, "FK", column(p, spoke, "HUB_SN", false, false, 1), hubSn);
            list.add(spoke);
        }
        return new Model(admin, hub, parent, child, list);
    }

    @Test
    @DisplayName("범위 밖 관계 : INNER 는 양 끝이 범위 안인 관계만 주고 테이블별 범위 밖 관계 수를 FK 단위로 센다 — ALL 은 예전 그대로")
    void outsideRelations() {
        Model m = model("m2i-outside", 30);
        // 범위 = HUB·PARENT·CHILD (검색어 없이 "TB_" 이름순 첫 3개 : TB_CHILD, TB_HUB, TB_PARENT)
        ErdDto.Search search = new ErdDto.Search(null, "TB_", null, null);
        ErdDto.Graph legacy = erdService.selectGraph(m.admin(), search, 0, 3);
        ErdDto.Graph inner = erdService.selectGraph(m.admin(), search, 0, 3, RelationScope.INNER);
        assertThat(names(inner)).containsExactly("TB_CHILD", "TB_HUB", "TB_PARENT");

        // 예전 응답 : 범위 밖 상대(SPOKE 30개)와의 관계까지 전부, 범위 밖 수·카디널리티는 주지 않는다
        assertThat(legacy.relations()).hasSize(4 + 30);
        assertThat(legacy.outsideRelations()).isNull();
        assertThat(legacy.cardinalities()).isEmpty();
        // INNER : 양 끝이 범위 안인 4개 (복합 FK 는 쌍 2개를 그대로), 범위 밖 수는 HUB 만 30
        assertThat(inner.relations()).hasSize(4).allMatch(r -> Set.of(m.hub().getId(), m.parent().getId(), m.child().getId()).containsAll(List.of(r.sourceTableId(), r.targetTableId())));
        assertThat(inner.relations()).filteredOn(r -> r.targetTableId().equals(m.parent().getId())).singleElement()
                .satisfies(r -> assertThat(r.columns()).extracting(PhysicalModelDto.ColumnPair::sourceColumnName).containsExactly("C1", "C2"));
        assertThat(inner.outsideRelations()).containsExactly(Map.entry(m.hub().getId(), 30L));
        assertThat(legacy.fkColumnIds()).isNull();
        assertThat(inner.fkColumnIds()).as("CHILD 의 FK 원본 컬럼 C1·C2·HUB_A·HUB_B·UP_C1").hasSize(5);
        // SPOKE 만 보이면 상대(HUB)가 범위 밖이어도 FK 컬럼은 알려 준다
        ErdDto.Graph onlySpokes = erdService.selectGraph(m.admin(), new ErdDto.Search(null, "TB_SPOKE_001", null, null), 0, 100, RelationScope.INNER);
        assertThat(onlySpokes.relations()).isEmpty();
        assertThat(onlySpokes.fkColumnIds()).hasSize(1);
        assertThat(inner.cardinalities()).hasSize(4);
        // 테이블·컬럼은 같다
        assertThat(inner.tables()).isEqualTo(legacy.tables());
        assertThat(inner.columns()).isEqualTo(legacy.columns());

        // 범위 = SPOKE 일부 + CHILD : 복합 FK 는 컬럼 쌍 수와 무관하게 1, 같은 상대와의 FK 둘은 각각 센다
        ErdDto.Graph spokes = erdService.selectGraph(m.admin(), new ErdDto.Search(null, "TB_SPOKE_00", null, null), 0, 100, RelationScope.INNER);
        assertThat(spokes.tables()).hasSize(10);
        assertThat(spokes.relations()).isEmpty();
        assertThat(spokes.outsideRelations()).hasSize(10).allSatisfy((id, count) -> assertThat(count).isEqualTo(1L));
        ErdDto.Graph childOnly = erdService.selectGraph(m.admin(), new ErdDto.Search(null, "TB_CHILD", null, null), 0, 100, RelationScope.INNER);
        assertThat(childOnly.relations()).as("자기 참조는 범위 안").hasSize(1);
        assertThat(childOnly.outsideRelations()).containsExactly(Map.entry(m.child().getId(), 3L));
    }

    @Test
    @DisplayName("INNER 조회도 쿼리 수는 범위 크기와 무관하다 (count·테이블·컬럼·관계 2·관계 끝 수 2·FK 컬럼·UNIQUE 키 2), ALL 은 예전 4번")
    void statementCount() {
        Model m = model("m2i-statements", 40);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            for (int size : new int[]{3, 43}) {
                statistics.clear();
                erdService.selectGraph(m.admin(), ALL, 0, size, RelationScope.INNER);
                assertThat(statistics.getPrepareStatementCount()).as("INNER size " + size).isEqualTo(10);
                assertThat(statistics.getEntityLoadCount()).isZero();
                statistics.clear();
                erdService.selectGraph(m.admin(), ALL, 0, size);
                assertThat(statistics.getPrepareStatementCount()).as("ALL size " + size).isEqualTo(4);
            }
            statistics.clear();
            erdService.selectTableRelations(m.admin(), m.hub().getId(), 0, 100);
            assertThat(statistics.getPrepareStatementCount()).as("관계 목록 : 테이블 확인·수·ID 페이지·쌍·테이블·컬럼 값·UNIQUE 2").isLessThanOrEqualTo(9);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    @DisplayName("이웃 탐색 : INNER 로 바꿔도 탐색 결과(테이블·잘림)는 같고, 화면에 그릴 관계도 같다")
    void neighborsUnchanged() {
        Model m = model("m2i-neighbors", 12);
        for (int depth = 1; depth <= 3; depth++) {
            ErdDto.Graph legacy = erdService.selectNeighbors(m.admin(), m.spokes().get(0).getId(), depth);
            ErdDto.Graph inner = erdService.selectNeighbors(m.admin(), m.spokes().get(0).getId(), depth, RelationScope.INNER);
            assertThat(inner.tables()).isEqualTo(legacy.tables());
            assertThat(inner.truncated()).isEqualTo(legacy.truncated());
            Set<Long> ids = new HashSet<>(inner.tables().stream().map(ErdDto.Table::id).toList());
            assertThat(inner.relations()).isEqualTo(legacy.relations().stream().filter(r -> ids.contains(r.sourceTableId()) && ids.contains(r.targetTableId())).toList());
        }
        // 1단계 : SPOKE_000 ↔ HUB, 2단계 : HUB 의 모든 이웃
        assertThat(names(erdService.selectNeighbors(m.admin(), m.spokes().get(0).getId(), 1, RelationScope.INNER))).containsExactlyInAnyOrder("TB_SPOKE_000", "TB_HUB");
        assertThat(erdService.selectNeighbors(m.admin(), m.spokes().get(0).getId(), 2, RelationScope.INNER).tables()).hasSize(12 + 2);
    }

    @Test
    @DisplayName("테이블 관계 목록 : 관계 ID 순 페이지, 복합 FK 쌍 전부, 상대 테이블·카디널리티, 크기 1~100 밖·음수 페이지 400, 다른 프로젝트 테이블 거절")
    void tableRelations() {
        Model m = model("m2i-relations", 25);
        ErdDto.RelationPage first = erdService.selectTableRelations(m.admin(), m.hub().getId(), 0, 10);
        assertThat(first.totalCount()).isEqualTo(25 + 2);
        assertThat(first.items()).hasSize(10);
        assertThat(first.items()).extracting(PhysicalModelDto.Relation::id).isSorted();
        ErdDto.RelationPage last = erdService.selectTableRelations(m.admin(), m.hub().getId(), 2, 10);
        assertThat(last.items()).hasSize(7);
        assertThat(erdService.selectTableRelations(m.admin(), m.hub().getId(), 3, 10).items()).isEmpty();
        Set<Long> all = new HashSet<>();
        for (int page = 0; page < 3; page++) erdService.selectTableRelations(m.admin(), m.hub().getId(), page, 10).items().forEach(r -> all.add(r.id()));
        assertThat(all).as("페이지가 겹치거나 빠지지 않는다").hasSize(27);
        assertThat(first.tables()).containsKey(m.hub().getId());
        assertThat(first.items()).allSatisfy(r -> {
            assertThat(first.tables()).containsKeys(r.sourceTableId(), r.targetTableId());
            assertThat(first.cardinalities()).containsKey(r.id());
        });

        ErdDto.RelationPage child = erdService.selectTableRelations(m.admin(), m.child().getId(), 0, 100);
        assertThat(child.totalCount()).as("자기 참조 1개").isEqualTo(4);
        PhysicalModelDto.Relation composite = child.items().stream().filter(r -> r.targetTableId().equals(m.parent().getId())).findFirst().orElseThrow();
        assertThat(composite.columns()).extracting(PhysicalModelDto.ColumnPair::targetColumnName).containsExactly("P1", "P2");
        // C1 은 PK(NOT NULL), C2 는 NULL 허용 → 부모 최소는 판단하지 않는다. 자식 PK (C1) ⊂ FK → 자식 최대 1, FK 일부만 PK → 부분 식별
        assertThat(child.cardinalities().get(composite.id())).isEqualTo(new ErdDto.Cardinality(null, "1", "1", "PARTIAL", List.of(PhysicalCardinality.NULLABLE_MIXED)));

        for (int[] bad : new int[][]{{0, 0}, {0, 101}, {-1, 10}, {Integer.MAX_VALUE, 100}}) {
            assertThatThrownBy(() -> erdService.selectTableRelations(m.admin(), m.hub().getId(), bad[0], bad[1])).as(Arrays.toString(bad))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PAGE_REQUEST));
        }
        AccountDto other = loginAsAdmin("m2i-relations-other");
        assertThatThrownBy(() -> erdService.selectTableRelations(other, m.hub().getId(), 0, 10))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TABLE_IS_NOT_DEFINED));
    }

    @Test
    @DisplayName("테이블 찾기 : 물리명·논리명·스키마명 부분 일치, 스키마 조건, 같은 이름은 스키마로 구분, 최대 50개 + 전체 수, LIKE 특수문자는 글자 그대로, 프로젝트 격리")
    void finder() {
        AccountDto admin = loginAsAdmin("m2i-finder");
        long p = admin.getProjectId();
        SchemaDto sales = schema(admin, "SALES");
        for (int i = 0; i < 60; i++) table(p, "TB_FIND_%02d".formatted(i), null);
        table(p, "TB_SAME", null);
        TableInfo same = table(p, "TB_SAME", sales.id());
        TableInfo percent = table(p, "TB_100%_RATE", null);
        TableInfo underscore = table(p, "TBXUNDER", null);
        inTx(() -> {
            TableInfo t = tableInfoRepository.findById(underscore.getId()).orElseThrow();
            t.setTableKorName("주문 내역");
            return t;
        });

        ErdDto.TableHits hits = erdService.findTables(admin, new ErdDto.Search(null, "find", null, null), ErdService.MAX_FINDER);
        assertThat(hits.totalCount()).isEqualTo(60);
        assertThat(hits.items()).hasSize(50).extracting(ErdDto.TableHit::tableName).isSorted().startsWith("TB_FIND_00");

        ErdDto.TableHits twins = erdService.findTables(admin, new ErdDto.Search(null, "TB_SAME", null, null), 20);
        assertThat(twins.items()).extracting(h -> h.schemaName() + "." + h.tableName()).containsExactly("DEFAULT.TB_SAME", "SALES.TB_SAME");
        assertThat(erdService.findTables(admin, new ErdDto.Search(sales.id(), null, null, null), 20).items()).extracting(ErdDto.TableHit::id).containsExactly(same.getId());
        assertThat(erdService.findTables(admin, new ErdDto.Search(null, "sales", null, null), 20).items()).as("스키마명").extracting(ErdDto.TableHit::id).containsExactly(same.getId());
        assertThat(erdService.findTables(admin, new ErdDto.Search(null, "주문", null, null), 20).items()).as("논리명").extracting(ErdDto.TableHit::id).containsExactly(underscore.getId());
        // % · _ 는 와일드카드가 아니다
        assertThat(erdService.findTables(admin, new ErdDto.Search(null, "%", null, null), 20).items()).extracting(ErdDto.TableHit::id).containsExactly(percent.getId());
        assertThat(erdService.findTables(admin, new ErdDto.Search(null, "B_1", null, null), 20).items()).extracting(ErdDto.TableHit::id).containsExactly(percent.getId());
        assertThat(erdService.findTables(admin, new ErdDto.Search(null, "TB_", null, null), 50).totalCount()).as("TBXUNDER 는 _ 가 아니다").isEqualTo(63);
        // 범위 조회와 같은 조건 : 같은 검색어의 범위 조회 결과와 같은 순서
        assertThat(hits.items().stream().map(ErdDto.TableHit::id).toList())
                .isEqualTo(erdService.selectGraph(admin, new ErdDto.Search(null, "find", null, null), 0, 50).tables().stream().map(ErdDto.Table::id).toList());

        for (int size : new int[]{0, 51}) {
            assertThatThrownBy(() -> erdService.findTables(admin, ALL, size))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_ERD_REQUEST));
        }
        assertThatThrownBy(() -> erdService.findTables(admin, new ErdDto.Search(null, "x".repeat(101), null, null), 20)).isInstanceOf(CustomException.class);
        AccountDto other = loginAsAdmin("m2i-finder-other");
        assertThat(erdService.findTables(other, new ErdDto.Search(null, "find", null, null), 50).totalCount()).isZero();
        assertThatThrownBy(() -> erdService.findTables(other, new ErdDto.Search(sales.id(), null, null, null), 20))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_IS_NOT_DEFINED));
    }

    @Test
    @DisplayName("카디널리티 : 저장된 PK·UNIQUE 제약·고유 인덱스로 판단 — 조건부·표현식 고유 인덱스와 일반 인덱스는 근거가 아니고, 등록 뒤 부모 PK 가 바뀐 무효 FK 는 판단하지 않는다")
    void cardinalityFromConstraints() {
        AccountDto admin = loginAsAdmin("m2i-cardinality");
        long p = admin.getProjectId();
        TableInfo parent = table(p, "TB_P", null);
        ColumnInfo pk = column(p, parent, "P_SN", true, false, 1);
        ColumnInfo pk2 = column(p, parent, "P_NO", false, false, 2);
        String[] names = {"TB_C_UQ", "TB_C_PARTIAL", "TB_C_EXPR", "TB_C_PLAIN", "TB_C_UNIQUE_INDEX", "TB_C_INCLUDE"};
        Map<String, Long> relationOf = new HashMap<>();
        Map<String, ColumnInfo> fkColumn = new HashMap<>();
        for (String name : names) {
            TableInfo child = table(p, name, null);
            column(p, child, "C_SN", true, false, 1);
            ColumnInfo fk = column(p, child, "P_SN", false, false, 2);
            fkColumn.put(name, fk);
            relation(admin, child, parent, "FK", fk, pk);
            switch (name) {
                case "TB_C_UQ" -> physicalModelService.saveConstraint(admin, new PhysicalModelDto.Constraint(null, child.getId(), "UK_C_UQ", "UNIQUE", null,
                        List.of(new PhysicalModelDto.ColumnRef(fk.getId(), null)), null));
                case "TB_C_PARTIAL" -> physicalModelService.saveIndex(admin, index(child, "UX_C_PARTIAL", true, "P_SN > 0", List.of(key(fk)), List.of()));
                case "TB_C_EXPR" -> physicalModelService.saveIndex(admin, index(child, "UX_C_EXPR", true, null,
                        List.of(key(fk), new Member(null, null, "ABS(P_SN)", null, null)), List.of()));
                case "TB_C_PLAIN" -> physicalModelService.saveIndex(admin, index(child, "IX_C_PLAIN", false, null, List.of(key(fk)), List.of()));
                case "TB_C_UNIQUE_INDEX" -> physicalModelService.saveIndex(admin, index(child, "UX_C_UNIQUE", true, null, List.of(key(fk)), List.of()));
                case "TB_C_INCLUDE" -> physicalModelService.saveIndex(admin, index(child, "UX_C_INCLUDE", true, null, List.of(key(fk)),
                        List.of(new PhysicalModelDto.ColumnRef(column(p, child, "C_NM", false, false, 3).getId(), null))));
                default -> {
                }
            }
        }
        ErdDto.Graph graph = erdService.selectGraph(admin, ALL, 0, 100, RelationScope.INNER);
        graph.relations().forEach(r -> relationOf.put(r.sourceTableName(), r.id()));
        Map<String, String> childMax = new LinkedHashMap<>();
        for (String name : names) childMax.put(name, graph.cardinalities().get(relationOf.get(name)).childMax());
        assertThat(childMax).containsExactly(Map.entry("TB_C_UQ", "1"), Map.entry("TB_C_PARTIAL", "N"), Map.entry("TB_C_EXPR", "N"),
                Map.entry("TB_C_PLAIN", "N"), Map.entry("TB_C_UNIQUE_INDEX", "1"), Map.entry("TB_C_INCLUDE", "1"));
        assertThat(graph.cardinalities().get(relationOf.get("TB_C_PLAIN"))).as("NULL 허용 FK, 부모 PK 참조")
                .isEqualTo(new ErdDto.Cardinality("0", "1", "N", "NON_IDENTIFYING", List.of()));

        // 등록 뒤 부모 PK 가 (P_SN, P_NO) 로 바뀌면 단일 컬럼 FK 는 키를 가리키지 않는다 (모델 구조 진단의 무효 FK 와 같은 조건)
        jdbcTemplate.update("update TB_COL set PK_YN = true where COL_SN = ?", pk2.getId());
        ErdDto.Cardinality invalid = erdService.selectGraph(admin, ALL, 0, 100, RelationScope.INNER).cardinalities().get(relationOf.get("TB_C_PLAIN"));
        assertThat(invalid.parentMax()).isNull();
        assertThat(invalid.reasons()).containsExactly(PhysicalCardinality.INVALID_REFERENCE);
        // 조회는 저장하지 않는다 : FK 정의·컬럼 값 그대로
        assertThat(jdbcTemplate.queryForObject("select count(*) from TB_TBL_REL where PRJ_SN = ?", Long.class, p)).isEqualTo(names.length);
        assertThat(jdbcTemplate.queryForObject("select NULL_ALWNC_YN from TB_COL where COL_SN = ?", Boolean.class, fkColumn.get("TB_C_PLAIN").getId())).isTrue();
    }

    // ------------------------------------------------------------------ 준비

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private SchemaDto schema(AccountDto admin, String name) {
        return schemaService.saveSchema(admin, new SchemaDto(null, name, null, null));
    }

    private TableInfo table(long projectId, String name, Long schemaId) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, boolean unique, int sortOrder) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("NUMBER").isPk(pk).isUnique(unique).isNullable(!pk).sortOrder(sortOrder).isApproval(false).build()));
    }

    /** columns : 원본 컬럼, 대상 컬럼, 원본 컬럼, 대상 컬럼 ... */
    private void relation(AccountDto admin, TableInfo source, TableInfo target, String type, ColumnInfo... columns) {
        List<PhysicalModelDto.ColumnPair> pairs = new ArrayList<>();
        for (int i = 0; i < columns.length; i += 2) {
            pairs.add(new PhysicalModelDto.ColumnPair(columns[i].getId(), null, columns[i + 1].getId(), null));
        }
        physicalModelService.saveRelation(admin, new PhysicalModelDto.Relation(null, null, type, null,
                source.getId(), null, target.getId(), null, pairs, null));
    }

    private static PhysicalModelDto.Index index(TableInfo table, String name, boolean unique, String condition, List<Member> members, List<PhysicalModelDto.ColumnRef> includes) {
        return new PhysicalModelDto.Index(null, table.getId(), name, null, unique, null, null, null, members, includes, condition);
    }

    private static Member key(ColumnInfo column) {
        return new Member(column.getId(), null, null, null, null);
    }

    private static List<String> names(ErdDto.Graph graph) {
        return graph.tables().stream().map(ErdDto.Table::tableName).toList();
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }
}
