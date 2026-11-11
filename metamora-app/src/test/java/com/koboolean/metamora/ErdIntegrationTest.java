package com.koboolean.metamora;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.repository.ProjectRepository;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.repository.AccountRepository;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.column.repository.ColumnInfoRepository;
import com.koboolean.metamora.data.erd.controller.ErdRestController;
import com.koboolean.metamora.data.erd.domain.dto.ErdDto;
import com.koboolean.metamora.data.erd.service.ErdService;
import com.koboolean.metamora.data.physical.domain.dto.PhysicalModelDto;
import com.koboolean.metamora.data.physical.service.PhysicalModelService;
import com.koboolean.metamora.data.schema.domain.dto.SchemaDto;
import com.koboolean.metamora.data.schema.repository.SchemaInfoRepository;
import com.koboolean.metamora.data.schema.service.SchemaService;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
import com.koboolean.metamora.data.table.service.TableDesignService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-2 물리 ERD (실제 JPA 매핑·쿼리) : 일괄 조회(컬럼 순서·복합 FK·자기 참조·순환·관계 없음), 검색·필터·이웃 탐색,
 * 프로젝트 격리·권한, 레이아웃 저장·복원·충돌, 기본 스키마 동시 생성.
 * 기본은 H2. PostgreSQL 은 SPRING_DATASOURCE_URL / SPRING_DATASOURCE_DRIVER_CLASS_NAME 환경 변수로 빈 격리 DB 를 지정해 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ErdIntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaInfoRepository schemaInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired ErdService erdService;
    @Autowired ErdRestController erdRestController;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TableDesignService tableDesignService;
    @Autowired TransactionTemplate tx;

    private static final ErdDto.Search ALL = new ErdDto.Search(null, null, null, null);

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ 조회

    @Test
    @DisplayName("V0 예제 : META-MORA 프로젝트에 MetaMora 자체 테이블·컬럼·관계가 들어 있고 ERD·DDL 로 볼 수 있다")
    void seededExample() {
        AccountDto admin = SecurityTestSupport.loginAs(projectRepository, 0L, "0", "ROLE_ADMIN");
        assertThat(projectRepository.findById(0L).orElseThrow().getProjectName()).isEqualTo("META-MORA");

        ErdDto.Graph graph = erdService.selectGraph(admin, new ErdDto.Search(null, "TB_", null, null), 0, ErdService.MAX_TABLES);
        List<String> names = names(graph);
        assertThat(names).contains("TB_TBL", "TB_COL", "TB_TBL_REL", "TB_ERD_LYT", "TB_STD_TRM");
        assertThat(graph.columns().stream().filter(c -> c.tableId().equals(id(graph, "TB_TBL"))))
                .extracting(ErdDto.Column::columnName).startsWith("TBL_SN", "PRJ_SN", "SCHM_SN", "TBL_NM");
        assertThat(graph.relations()).anyMatch(r -> r.sourceTableName().equals("TB_COL") && r.targetTableName().equals("TB_TBL")
                && r.columns().get(0).sourceColumnName().equals("TBL_SN") && r.relationSource().equals("REF_COLUMN"));

        assertThat(tableDesignService.generateTableDdl(id(graph, "TB_COL"), admin, null).ddl())
                .contains("CREATE TABLE TB_COL (")
                .contains("FOREIGN KEY (TBL_SN) REFERENCES TB_TBL (TBL_SN)");

        // 데이터 사전 : 테이블 한글명을 승인된 표준 단어로 바꾸면 같은 테이블명이 나오고, 컬럼은 표준 용어에 연결돼 있다
        for (ErdDto.Table table : graph.tables()) {
            var name = tableDesignService.generateTableName(table.tableKorName(), admin);
            assertThat(name.valid()).as(table.tableKorName()).isTrue();
            assertThat(name.tableName()).isEqualTo(table.tableName());
        }
        assertThat(graph.columns()).allMatch(c -> c.termName() != null);
        assertThat(jdbcTemplate.queryForObject("select t.TRM_NM from TB_STD_TRM t join TB_COL c on c.STD_TRM_SN = t.STD_TRM_SN "
                + "where c.TBL_NM = 'TB_TBL' and c.COL_NM = 'REG_DT'", String.class)).isEqualTo("등록_일시");
        assertThat(jdbcTemplate.queryForObject("select count(*) from TB_STD_TRM t join TB_STD_TRM_WORD m on m.STD_TRM_SN = t.STD_TRM_SN "
                + "where t.TRM_NM = '테이블_한글_명'", Long.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("테이블·컬럼·관계를 일괄 조회한다 : 컬럼 순서, 복합 FK 쌍 순서, 자기 참조, 순환, 관계 없는 테이블, 쿼리 수 고정")
    void graph() {
        AccountDto admin = loginAsAdmin("m2-graph");
        long projectId = admin.getProjectId();
        TableInfo code = table(projectId, "TB_CODE", null);
        ColumnInfo grp = column(projectId, code, "GRP_CD", true, 1);
        ColumnInfo cd = column(projectId, code, "CD", true, 2);
        ColumnInfo parentCd = column(projectId, code, "PARENT_CD", false, 3);
        TableInfo order = table(projectId, "TB_ORDER", null);
        ColumnInfo statCd = column(projectId, order, "STAT_CD", false, 3);
        ColumnInfo orderNo = column(projectId, order, "ORDER_NO", true, 1);
        ColumnInfo statGrp = column(projectId, order, "STAT_GRP_CD", false, 2);
        table(projectId, "TB_ALONE", null);

        relation(admin, order, code, "FK", statGrp, grp, statCd, cd);   // 복합 FK
        relation(admin, code, code, "FK", parentCd, cd);                // 자기 참조
        relation(admin, code, order, "LOGICAL", grp, orderNo);          // 순환 (논리 관계)

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        ErdDto.Graph graph = erdService.selectGraph(admin, ALL, 0, 100);
        long statements = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);

        assertThat(statements).as("테이블 수와 무관 : count·테이블·컬럼·관계").isEqualTo(4);
        assertThat(graph.tables()).extracting(ErdDto.Table::tableName).containsExactly("TB_ALONE", "TB_CODE", "TB_ORDER");
        assertThat(graph.totalCount()).isEqualTo(3);
        assertThat(graph.truncated()).isFalse();
        assertThat(graph.columns().stream().filter(c -> c.tableId().equals(order.getId())))
                .extracting(ErdDto.Column::columnName).containsExactly("ORDER_NO", "STAT_GRP_CD", "STAT_CD");
        assertThat(graph.relations()).hasSize(3);

        PhysicalModelDto.Relation composite = graph.relations().stream()
                .filter(r -> r.sourceTableId().equals(order.getId()) && r.relationType().equals("FK")).findFirst().orElseThrow();
        assertThat(composite.columns()).extracting(PhysicalModelDto.ColumnPair::sourceColumnId).containsExactly(statGrp.getId(), statCd.getId());
        assertThat(composite.columns()).extracting(PhysicalModelDto.ColumnPair::targetColumnId).containsExactly(grp.getId(), cd.getId());
        assertThat(composite.relationSource()).isEqualTo("USER");
        assertThat(graph.relations()).anyMatch(r -> r.sourceTableId().equals(code.getId()) && r.targetTableId().equals(code.getId()));

        // 페이지 : 한도를 넘으면 truncated, 범위 테이블이 한쪽인 관계는 상대가 범위 밖이어도 포함
        ErdDto.Graph first = erdService.selectGraph(admin, ALL, 0, 2);
        assertThat(first.tables()).hasSize(2);
        assertThat(first.truncated()).isTrue();
        ErdDto.Graph second = erdService.selectGraph(admin, ALL, 1, 2);
        assertThat(second.tables()).extracting(ErdDto.Table::tableName).containsExactly("TB_ORDER");
        assertThat(second.truncated()).isFalse();
        assertThat(second.relations()).hasSize(2);
        assertThat(erdService.selectGraph(admin, ALL, 5, 2).tables()).isEmpty();
    }

    @Test
    @DisplayName("검색어(물리명·논리명·스키마명)·스키마·승인·마스터 조건과 N 단계 이웃 탐색")
    void searchAndNeighbors() {
        AccountDto admin = loginAsAdmin("m2-search");
        long projectId = admin.getProjectId();
        SchemaDto sales = schemaService.saveSchema(admin, new SchemaDto(null, "sales", null, null));
        TableInfo a = table(projectId, "TB_A", null);
        TableInfo b = table(projectId, "TB_B", sales.id());
        TableInfo c = table(projectId, "TB_C", null);
        TableInfo d = table(projectId, "TB_D", null);
        inTx(() -> {
            TableInfo master = tableInfoRepository.findById(d.getId()).orElseThrow();
            master.setTableKorName("고객_마스터");
            master.setIsMasterTable(true);
            master.setIsApproval(false);
            return master;
        });
        ColumnInfo aId = column(projectId, a, "ID", true, 1);
        ColumnInfo bId = column(projectId, b, "ID", true, 1);
        ColumnInfo bRef = column(projectId, b, "A_ID", false, 2);
        ColumnInfo cRef = column(projectId, c, "B_ID", false, 1);
        relation(admin, b, a, "FK", bRef, aId);  // A ← B ← C, D 는 연결 없음
        relation(admin, c, b, "FK", cRef, bId);

        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(null, "tb_b", null, null), 0, 100))).containsExactly("TB_B");
        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(null, "고객", null, null), 0, 100))).containsExactly("TB_D");
        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(null, "SALES", null, null), 0, 100))).containsExactly("TB_B");
        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(sales.id(), null, null, null), 0, 100))).containsExactly("TB_B");
        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(null, null, false, null), 0, 100))).containsExactly("TB_D");
        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(null, null, null, true), 0, 100))).containsExactly("TB_D");
        assertThat(names(erdService.selectGraph(admin, new ErdDto.Search(null, "100%_없음", null, null), 0, 100))).isEmpty();

        assertThat(names(erdService.selectNeighbors(admin, a.getId(), 1))).containsExactlyInAnyOrder("TB_A", "TB_B");
        assertThat(names(erdService.selectNeighbors(admin, a.getId(), 2))).containsExactlyInAnyOrder("TB_A", "TB_B", "TB_C");
        assertThat(names(erdService.selectNeighbors(admin, d.getId(), 3))).containsExactly("TB_D");

        assertThatThrownBy(() -> erdService.selectNeighbors(admin, a.getId(), ErdService.MAX_DEPTH + 1)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> erdService.selectGraph(admin, ALL, 0, ErdService.MAX_TABLES + 1)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> erdService.selectGraph(admin, new ErdDto.Search(null, "x".repeat(101), null, null), 0, 100))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("다른 프로젝트의 스키마·테이블·레이아웃은 ID 를 알아도 조회·저장할 수 없고, VIEWER 는 조회·내 배치 저장만 가능하다")
    void isolationAndRoles() {
        AccountDto a = loginAsAdmin("m2-iso-a");
        TableInfo tableA = table(a.getProjectId(), "TB_SECRET", null);
        Long schemaA = schemaService.resolveSchema(a.getProjectId(), null).getId();
        AccountDto b = loginAsAdmin("m2-iso-b");
        table(b.getProjectId(), "TB_MINE", null);

        assertThat(names(erdService.selectGraph(b, ALL, 0, 100))).containsExactly("TB_MINE");
        assertThatThrownBy(() -> erdService.selectGraph(b, new ErdDto.Search(schemaA, null, null, null), 0, 100))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SCHEMA_IS_NOT_DEFINED));
        assertThatThrownBy(() -> erdService.selectNeighbors(b, tableA.getId(), 1))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TABLE_IS_NOT_DEFINED));
        assertThatThrownBy(() -> erdService.saveLayout(b, layout(null, tableA.getId(), 1, 1)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TABLE_IS_NOT_DEFINED));

        // 같은 사용자라도 프로젝트마다 레이아웃이 따로다
        erdService.saveLayout(a, layout(null, tableA.getId(), 10, 20));
        assertThat(erdService.selectLayout(b).nodes()).isEmpty();

        AccountDto viewer = SecurityTestSupport.loginAs(projectRepository, a.getProjectId(), a.getId(), "ROLE_VIEWER");
        assertThat(erdRestController.selectGraph(viewer, null, null, null, null, 0, 100).getBody().tables()).hasSize(1);
        ErdDto.Layout current = erdRestController.selectLayout(viewer).getBody();
        assertThat(erdRestController.saveLayout(viewer, layout(current.version(), tableA.getId(), 30, 40)).getBody().version())
                .isEqualTo(current.version() + 1);

        AccountDto notApproved = SecurityTestSupport.loginAs(projectRepository, a.getProjectId(), a.getId(), "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> erdRestController.selectGraph(notApproved, null, null, null, null, 0, 100)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> erdRestController.selectLayout(notApproved)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> erdRestController.saveLayout(notApproved, layout(null, tableA.getId(), 1, 1))).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ 레이아웃

    @Test
    @DisplayName("레이아웃 : 저장·복원, 기존 좌표와 합치기, 삭제된 테이블 무시, 오래된 version 거절, 값 검증, 물리 모델 불변")
    void layout() {
        AccountDto admin = loginAsAdmin("m2-layout");
        long projectId = admin.getProjectId();
        TableInfo a = table(projectId, "TB_A", null);
        TableInfo b = table(projectId, "TB_B", null);
        LocalDateTime updatedBefore = updated(b);

        ErdDto.Layout empty = erdService.selectLayout(admin);
        assertThat(empty.version()).isNull();
        assertThat(empty.viewport()).isNull();

        ErdDto.Layout first = erdService.saveLayout(admin, layout(null, a.getId(), 100, 200));
        assertThat(first.version()).isZero();
        assertThat(erdService.selectLayout(admin)).isEqualTo(first);

        // 스키마 화면처럼 일부 테이블만 보내도 다른 테이블 좌표는 남는다
        ErdDto.Layout second = erdService.saveLayout(admin, new ErdDto.Layout(first.version(),
                Map.of(b.getId(), new ErdDto.Point(300, 400)), new ErdDto.Viewport(-50, 25, 0.75)));
        assertThat(second.version()).isEqualTo(1);
        assertThat(second.nodes()).containsOnlyKeys(a.getId(), b.getId());
        assertThat(erdService.selectLayout(admin).viewport()).isEqualTo(new ErdDto.Viewport(-50, 25, 0.75));

        assertThatThrownBy(() -> erdService.saveLayout(admin, layout(first.version(), a.getId(), 0, 0)))
                .as("다른 탭에서 먼저 저장")
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ERD_LAYOUT_CONFLICT));

        // 삭제된 테이블 : 조회에서 빠지고 다음 저장 때 정리된다
        tx.executeWithoutResult(s -> tableInfoRepository.deleteById(a.getId()));
        assertThat(erdService.selectLayout(admin).nodes()).containsOnlyKeys(b.getId());
        assertThat(erdService.saveLayout(admin, new ErdDto.Layout(second.version(), Map.of(), second.viewport())).nodes())
                .containsOnlyKeys(b.getId());

        long version = erdService.selectLayout(admin).version();
        assertThatThrownBy(() -> erdService.saveLayout(admin, layout(version, b.getId(), Double.NaN, 0))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> erdService.saveLayout(admin, layout(version, b.getId(), 1e8, 0))).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> erdService.saveLayout(admin, new ErdDto.Layout(version, Map.of(), new ErdDto.Viewport(0, 0, 10))))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> erdService.saveLayout(admin, new ErdDto.Layout(version, null, new ErdDto.Viewport(0, 0, 1))))
                .isInstanceOf(CustomException.class);

        // 다른 사용자는 자기 배치만 본다
        Account other = accountRepository.save(Account.builder().username("m2-other-" + UUID.randomUUID()).build());
        AccountDto otherDto = SecurityTestSupport.loginAs(projectRepository, projectId, String.valueOf(other.getId()), "ROLE_VIEWER");
        assertThat(erdService.selectLayout(otherDto).version()).isNull();

        assertThat(updated(b)).as("레이아웃 저장은 테이블을 바꾸지 않는다").isEqualTo(updatedBefore);
    }

    @Test
    @DisplayName("같은 사용자의 최초 레이아웃 저장이 동시에 와도 하나만 저장되고 나머지는 충돌로 거절된다")
    void concurrentFirstLayoutSave() throws Exception {
        AccountDto admin = loginAsAdmin("m2-layout-race");
        TableInfo a = table(admin.getProjectId(), "TB_A", null);
        List<String> results = concurrently(2, () -> {
            try {
                erdService.saveLayout(admin, layout(null, a.getId(), 1, 2));
                return "saved";
            } catch (CustomException e) {
                return e.getErrorCode().name();
            }
        });
        assertThat(results).containsExactlyInAnyOrder("saved", "ERD_LAYOUT_CONFLICT");
        assertThat(erdService.selectLayout(admin).version()).isZero();
    }

    // ------------------------------------------------------------------ M-1 선행 : 기본 스키마 동시 생성

    @Test
    @DisplayName("기본 스키마가 없는 프로젝트에 최초 요청이 동시에 와도 기본 스키마는 1개이고 모두 같은 ID 를 받는다")
    void concurrentDefaultSchema() throws Exception {
        AccountDto admin = loginAsAdmin("m2-default-schema");
        long projectId = admin.getProjectId();
        List<Long> ids = concurrently(8, () -> schemaService.resolveSchema(projectId, null).getId());
        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(schemaInfoRepository.findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(projectId)).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name, Long schemaId) {
        return inTx(() -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(schemaService.resolveSchema(projectId, schemaId)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk, int sortOrder) {
        return inTx(() -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR(10)").isPk(pk).isNullable(!pk).sortOrder(sortOrder).isApproval(false).build()));
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

    private static ErdDto.Layout layout(Long version, Long tableId, double x, double y) {
        return new ErdDto.Layout(version, Map.of(tableId, new ErdDto.Point(x, y)), new ErdDto.Viewport(0, 0, 1));
    }

    private static Long id(ErdDto.Graph graph, String tableName) {
        return graph.tables().stream().filter(t -> t.tableName().equals(tableName)).findFirst().orElseThrow().id();
    }

    private static List<String> names(ErdDto.Graph graph) {
        return graph.tables().stream().map(ErdDto.Table::tableName).toList();
    }

    private LocalDateTime updated(TableInfo table) {
        return jdbcTemplate.queryForObject("select MDFCN_DT from TB_TBL where TBL_SN = ?", LocalDateTime.class, table.getId());
    }

    /** 여러 스레드에서 동시에 시작 (각자 자기 트랜잭션) */
    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
