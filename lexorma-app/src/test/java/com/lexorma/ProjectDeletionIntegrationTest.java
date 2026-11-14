package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec;
import com.lexorma.source.catalog.domain.dto.SourceCatalogSpec.Name;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.domain.enums.DatabaseNameType;
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.catalog.service.SourceCatalogWriter;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.service.LogsService;
import com.lexorma.core.project.domain.dto.ProjectDto;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.source.collection.domain.dto.SourceCollectionSpec;
import com.lexorma.source.collection.service.SourceCollectionService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Attribute;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Entity;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Identifier;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Model;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.Relationship;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.AttributeMapping;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto.EntityMapping;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.physical.controller.PhysicalModelRestController;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.ColumnPair;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto.Relation;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.version.domain.dto.ModelVersionDto.Create;
import com.lexorma.data.version.service.ModelVersionService;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.service.PhysicalViewService;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.operation.board.domain.entity.Board;
import com.lexorma.operation.notice.repository.BoardCategoryRepository;
import com.lexorma.operation.notice.repository.BoardRepository;
import com.lexorma.system.project.service.ProjectManageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로젝트 영구 삭제 (H2 / PostgreSQL) : 프로젝트에 딸린 데이터가 DB 에 한 행도 남지 않고, 다른 프로젝트와 공통 데이터는 그대로이며,
 * 중간에 실패하면 전부 되돌아가는지 본다. 조회 API 가 아니라 테이블의 행 수를 직접 센다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ProjectDeletionIntegrationTest {

    /** 프로젝트와 무관한 공통 데이터 : 프로젝트를 지워도 건드리지 않는다 */
    // 외부 연결 보안(SEC-1) 규칙·변경 이력·연결 감사는 시스템 단위라 프로젝트를 지워도 남는다
    private static final Set<String> GLOBAL_TABLES = Set.of("TB_ACNT", "TB_ROLE", "TB_ACNT_ROLE", "TB_PST_CTGRY", "TB_EXTRL_CNTN_RULE", "TB_EXTRL_CNTN_RULE_HSTRY",
            "TB_EXTRL_CNTN_HSTRY");

    /**
     * 프로젝트 ID 컬럼(PRJ_SN)이 있는 테이블. 테이블을 더하면 이 목록과 삭제 처리(ProjectDataCleanup 등), 아래 준비 데이터에 함께 넣는다 :
     * 목록이 실제 스키마와 다르면 실패하고, 준비 데이터가 없는 테이블이 있어도 실패한다.
     */
    private static final Set<String> PROJECT_TABLES = Set.of("TB_PRJ", "TB_PRJ_MBR", "TB_LOG", "TB_PST", "TB_STD_DMN", "TB_STD_WORD", "TB_STD_TRM", "TB_SCHM", "TB_TBL", "TB_COL",
            "TB_CMN_COL", "TB_TBL_REL", "TB_TBL_IDX", "TB_IDX_CHG_DMND", "TB_TBL_CNSTRT", "TB_ERD_LYT", "TB_LGCL_MDL", "TB_LGCL_ENTT", "TB_LGCL_REL", "TB_LGCL_ERD_LYT", "TB_ENTT_TBL_MPNG",
            "TB_MDL_CHG_HSTRY", "TB_MDL_VER", "TB_SRC_CNTN", "TB_SRC_SCHM", "TB_SRC_TBL", "TB_SRC_COL", "TB_SRC_CNSTRT", "TB_SRC_IDX", "TB_SRC_REL",
            "TB_SRC_CLCT_EXCN", "TB_SRC_CLCT_TRGT", "TB_SRC_SCHM_VER", "TB_SRC_TBL_VER", "TB_SRC_CHG_HSTRY", "TB_VIEW", "TB_VIEW_CHG_DMND",
            "TB_PRJ_SRC_CNTN", "TB_SRC_SCHM_MPNG", "TB_SRC_TBL_MPNG", "TB_SRC_IMPRT_EXCN");

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired BoardCategoryRepository boardCategoryRepository;
    @Autowired SchemaService schemaService;
    @Autowired LogicalModelService logical;
    @Autowired ModelMappingService mappings;
    @Autowired PhysicalModelRestController physical;
    @Autowired ModelVersionService versionService;
    @Autowired PhysicalViewService viewService;
    @Autowired SourceConnectionRepository sourceConnectionRepository;
    @Autowired SourceCatalogWriter catalogWriter;
    @Autowired SourceCollectionService collections;
    @Autowired SourceMappingService sourceMappingService;
    @Autowired LogsService logsService;
    @Autowired ProjectManageService projectManageService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("영구 삭제 : 모든 테이블에 데이터가 있는 프로젝트를 지우면 그 프로젝트의 행이 하나도 남지 않고, 다른 프로젝트·공통 데이터의 행 수는 그대로다")
    void deletesEverythingOfTheProjectAndNothingElse() {
        assertThat(tablesWith("PRJ_SN")).as("PRJ_SN 컬럼이 있는 테이블 (더했다면 삭제 처리와 이 테스트에 넣는다)").isEqualTo(new TreeSet<>(PROJECT_TABLES));

        // 남아야 하는 프로젝트를 먼저 채우고 전체 행 수를 적어 둔다
        AccountDto keep = loginAsAdmin("del-keep");
        fill(keep);
        Map<String, Integer> baseline = rowCounts();

        AccountDto target = loginAsAdmin("del-target");
        long targetId = target.getProjectId();
        fill(target);
        // 두 프로젝트에 함께 등록한 공지 : 프로젝트마다 행이 따로 생긴다
        Long sharedOfKeep = notice(keep.getProjectId(), List.of(keep.getProjectId(), targetId));
        notice(targetId, List.of(keep.getProjectId(), targetId));

        // 지울 프로젝트는 공통 데이터가 아닌 모든 테이블에 행이 있다 (준비 데이터가 빠진 테이블이 없다)
        Map<String, Integer> filled = rowCounts();
        filled.forEach((table, count) -> {
            if (!GLOBAL_TABLES.contains(table)) assertThat(count).as("%s 에 지울 프로젝트의 행이 있어야 한다", table).isGreaterThan(baseline.get(table));
        });

        // 비활성화는 아무것도 지우지 않는다
        projectManageService.updateProject(ProjectDto.builder().id(targetId).projectName("del-target").projectManagerId(Long.valueOf(target.getId()))
                .isActive(false).isAutoActive(true).isDicAbbrUsed(true).tablePrefix("TB_").build(), true);
        assertThat(rowCounts()).isEqualTo(filled);

        projectManageService.deleteProject(targetId, target);

        // 1) PRJ_SN 이 있는 모든 테이블에 그 프로젝트의 행이 없다
        for (String table : PROJECT_TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, targetId)).as(table).isZero();
        }
        // 2) PRJ_SN 이 없는 하위 테이블까지 : 전체 행 수가 지울 프로젝트를 만들기 전과 같다 (남은 프로젝트에 더한 공지 1건과 읽은 사용자 1건만 늘었다)
        Map<String, Integer> expected = new TreeMap<>(baseline);
        expected.merge("TB_PST", 1, Integer::sum);
        expected.merge("TB_PST_ACNT", 1, Integer::sum);
        assertThat(rowCounts()).isEqualTo(expected);
        // 3) 남은 프로젝트의 공지는 그대로이고, 대상 목록에서만 지워진 프로젝트가 빠졌다
        Board shared = boardRepository.findById(sharedOfKeep).orElseThrow();
        assertThat(shared.getProjectIds()).containsExactly(keep.getProjectId());
        assertThat(shared.getTitle()).isEqualTo("함께 등록한 공지");

        // 지워진 프로젝트 : 다시 지울 수 없고, 접근할 수 없고, 그 프로젝트로는 아무것도 저장되지 않는다
        assertThatThrownBy(() -> projectManageService.deleteProject(targetId, target)).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DATA_CANNOT_BE_DELETED));
        assertThat(projectRepository.isAccessibleProject(target.getUsername(), targetId)).isFalse();
        assertThatThrownBy(() -> versionService.create(target, new Create("after-delete", null))).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
        assertThatThrownBy(() -> logical.saveModel(target, new Model(null, "after-delete", null))).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
        assertThatThrownBy(() -> wordRepository.save(StandardWord.builder().projectId(targetId).commonStandardWordName("고아").isApproval(true).build()))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
        assertThat(rowCounts()).isEqualTo(expected);
    }

    @Test
    @DisplayName("삭제 조건·롤백 : 관리자가 아니거나 멤버가 더 있으면 지울 수 없고, 삭제 도중 실패하면 프로젝트와 데이터가 모두 그대로다")
    void conditionsAndRollback() {
        AccountDto owner = loginAsAdmin("del-rollback");
        long projectId = owner.getProjectId();
        Filled filled = fill(owner);
        AccountDto other = loginAsAdmin("del-rollback-other");
        TableInfo foreign = table(other.getProjectId(), "TB_FOREIGN");
        Map<String, Integer> before = rowCounts();

        // 기존 조건 그대로 : 프로젝트 관리자만, 관리자 외 멤버가 없을 때만, 기본 프로젝트는 안 된다
        AccountDto stranger = SecurityTestSupport.loginAs(projectRepository, projectId, "987654", "ROLE_ADMIN");
        assertThatThrownBy(() -> projectManageService.deleteProject(projectId, stranger)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> projectManageService.deleteProject(0L, owner)).isInstanceOf(CustomException.class);
        jdbc.update("insert into TB_PRJ_MBR(PRJ_MBR_SN, ACNT_SN, PRJ_SN, ACTVTN_YN) values(?, ?, ?, true)", -projectId, Long.valueOf(owner.getId()), projectId);
        assertThatThrownBy(() -> projectManageService.deleteProject(projectId, owner)).isInstanceOf(CustomException.class);
        jdbc.update("delete from TB_PRJ_MBR where PRJ_MBR_SN = ?", -projectId);
        assertThat(rowCounts()).isEqualTo(before);

        // 삭제 도중 실패 : 다른 프로젝트의 컬럼이 이 프로젝트의 테이블을 가리키게 해 두면(있어서는 안 되는 데이터) 테이블을 지우는 단계에서 FK 로 막힌다
        // → 앞 단계에서 지운 매핑·논리 모델·컬럼까지 모두 되돌아가고 프로젝트도 남는다
        Long blocker = tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(other.getProjectId()).tableName(foreign.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(foreign.getId())).columnName("BLOCK").dataType("VARCHAR").maxLength(10).isApproval(false).build())).getId();
        jdbc.update("update TB_COL set TBL_SN = ? where COL_SN = ?", filled.order.getId(), blocker);
        Map<String, Integer> blocked = rowCounts();
        assertThatThrownBy(() -> projectManageService.deleteProject(projectId, owner)).isInstanceOf(RuntimeException.class);
        assertThat(rowCounts()).isEqualTo(blocked);
        assertThat(projectRepository.findById(projectId)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_VER where PRJ_SN = ?", Integer.class, projectId)).isEqualTo(1);

        // 막던 데이터를 치우면 지워진다
        jdbc.update("update TB_COL set TBL_SN = ? where COL_SN = ?", foreign.getId(), blocker);
        projectManageService.deleteProject(projectId, owner);
        for (String table : PROJECT_TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, projectId)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from TB_COL where COL_SN = ?", Integer.class, blocker)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시성 : 같은 프로젝트를 동시에 지우면 한 번만 지워지고, 삭제와 겹친 등록은 실패해 주인 없는 데이터가 남지 않는다")
    void concurrency() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            // 1) 동시 삭제 4건
            AccountDto twice = loginAsAdmin("del-twice");
            fill(twice);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        projectManageService.deleteProject(twice.getProjectId(), twice);
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int deleted = 0;
            for (Future<Boolean> result : results) if (result.get(30, TimeUnit.SECONDS)) deleted++;
            assertThat(deleted).isEqualTo(1);
            for (String table : PROJECT_TABLES) {
                assertThat(jdbc.queryForObject("select count(*) from " + table + " where PRJ_SN = ?", Integer.class, twice.getProjectId())).as(table).isZero();
            }

            // 2) 등록 트랜잭션이 저장은 했지만 아직 커밋하지 않은 사이에 프로젝트가 지워진다 → 등록의 커밋이 실패한다
            AccountDto racing = loginAsAdmin("del-racing");
            long projectId = racing.getProjectId();
            CountDownLatch inserted = new CountDownLatch(1);
            CountDownLatch projectGone = new CountDownLatch(1);
            Future<Throwable> writer = pool.submit(() -> {
                try {
                    tx.executeWithoutResult(s -> {
                        // 데이터 사전의 단어는 부모 테이블이 없어 FK 로는 막히지 않는 등록이다
                        wordRepository.saveAndFlush(StandardWord.builder().projectId(projectId).commonStandardWordName("경합").isApproval(true).build());
                        inserted.countDown();
                        try {
                            projectGone.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
            projectManageService.deleteProject(projectId, racing);
            projectGone.countDown();
            assertThat(writer.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROJECT_NOT_FOUND));
            assertThat(jdbc.queryForObject("select count(*) from TB_STD_WORD where PRJ_SN = ?", Integer.class, projectId)).isZero();
            assertThat(projectRepository.findById(projectId)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 준비

    private record Filled(TableInfo order) {
    }

    /** 프로젝트의 모든 테이블에 행을 만든다 : 서비스로 만들 수 있는 것은 서비스로(변경 이력이 함께 남는다), 나머지는 직접 넣는다 */
    private Filled fill(AccountDto admin) {
        long projectId = admin.getProjectId();
        long accountId = Long.parseLong(admin.getId());
        long base = -(projectId * 100); // 시퀀스와 겹치지 않는 음수 ID

        // 멤버 (프로젝트 관리자 한 명 : 삭제할 수 있는 상태)
        jdbc.update("insert into TB_PRJ_MBR(PRJ_MBR_SN, ACNT_SN, PRJ_SN, ACTVTN_YN) values(?, ?, ?, true)", base - 1, accountId, projectId);

        // 데이터 사전 : 도메인, 단어(이음동의어·금칙어), 용어(이음동의어, 용어-단어), 공통 컬럼
        jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_NM) values(?, ?, 1, '명V10')", base - 1, projectId);
        jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM) values(?, ?, 1, '주문', 'ORD')", base - 1, projectId);
        jdbc.update("insert into TB_STD_WORD_SYNM(STD_WORD_SN, SYNM_NM) values(?, '오더')", base - 1);
        jdbc.update("insert into TB_STD_WORD_PRHBT(STD_WORD_SN, PRHBT_WORD_NM) values(?, '발주')", base - 1);
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_ENG_ABBR_NM, STD_DMN_SN) values(?, ?, 1, '주문', 'ORD', ?)", base - 1, projectId, base - 1);
        jdbc.update("insert into TB_STD_TRM_SYNM(STD_TRM_SN, SYNM_NM) values(?, '오더')", base - 1);
        jdbc.update("insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD) values(?, ?, ?, 1)", base - 1, base - 1, base - 1);
        jdbc.update("insert into TB_CMN_COL(CMN_COL_SN, PRJ_SN, STD_TRM_SN) values(?, ?, ?)", base - 1, projectId, base - 1);

        // 물리 모델 : 테이블 2, 컬럼 5, 복합 FK, 인덱스, UNIQUE, CHECK
        TableInfo code = table(projectId, "TB_CODE");
        ColumnInfo grp = column(projectId, code, "GRP_CD", true);
        ColumnInfo cd = column(projectId, code, "CD", true);
        TableInfo order = table(projectId, "TB_ORD");
        ColumnInfo orderNo = column(projectId, order, "ORD_NO", true);
        ColumnInfo statGrp = column(projectId, order, "STAT_GRP_CD", false);
        ColumnInfo stat = column(projectId, order, "STAT_CD", false);
        physical.saveRelation(admin, new Relation(null, null, "FK", null, order.getId(), null, code.getId(), null,
                List.of(new ColumnPair(statGrp.getId(), null, grp.getId(), null), new ColumnPair(stat.getId(), null, cd.getId(), null)), null));
        PhysicalModelDto.Index index = physical.saveIndex(admin, new PhysicalModelDto.Index(null, order.getId(), null, null, false, List.of(new PhysicalModelDto.ColumnRef(stat.getId(), null)), null)).getBody();
        // 승인된 인덱스의 수정 → 변경 요청 (TB_IDX_CHG_DMND, 승인 권한자라 바로 승인)
        physical.updateIndex(admin, index.id(), new PhysicalModelDto.Index(null, null, "IX_ORD_STAT", null, false, List.of(new PhysicalModelDto.ColumnRef(stat.getId(), null)), null));
        physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), null, "UNIQUE", null, List.of(new PhysicalModelDto.ColumnRef(statGrp.getId(), null), new PhysicalModelDto.ColumnRef(stat.getId(), null)), null));
        physical.saveConstraint(admin, new PhysicalModelDto.Constraint(null, order.getId(), "CK_ORD_STAT", "CHECK", "STAT_CD <> ''", null, null));
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", base - 1, orderNo.getId());

        // VIEW (M-15) : 테이블을 참조하는 VIEW, 그 VIEW 를 참조하는 VIEW (출력 컬럼·참조 객체), 승인된 VIEW 의 수정 → 변경 요청 (승인 권한자라 바로 승인)
        ViewDto.Save orderView = new ViewDto.Save(null, "VW_ORD", null, null, "SELECT ORD_NO FROM TB_ORD",
                List.of(new ViewDto.Column("ORD_NO", null, null, null)), List.of(new ViewDto.Reference("TABLE", order.getId(), null, null, null)));
        Long orderViewId = viewService.create(admin, orderView).id();
        viewService.create(admin, new ViewDto.Save(null, "VW_ORD_ALL", null, null, "SELECT * FROM VW_ORD", List.of(),
                List.of(new ViewDto.Reference("VIEW", null, orderViewId, null, null), new ViewDto.Reference("TABLE", null, null, "EXT", "TB_OUTSIDE"))));
        viewService.update(admin, orderViewId, new ViewDto.Save(null, "VW_ORD", "주문 VIEW", null, orderView.definition(), orderView.columns(), orderView.references()));

        // 논리 모델 : 모델, 엔터티 2, 속성, 식별자, 관계 + 매핑
        Model model = logical.saveModel(admin, new Model(null, "주문 모델", null));
        Entity orderEntity = logical.saveEntity(admin, new Entity(null, model.id(), "주문", null, null, null, null));
        Entity itemEntity = logical.saveEntity(admin, new Entity(null, model.id(), "주문 상품", null, null, null, null));
        Attribute orderNoAttribute = logical.saveAttribute(admin, new Attribute(null, orderEntity.id(), "주문 번호", null, null, true, null, null, null, null, null));
        logical.saveIdentifier(admin, new Identifier(null, orderEntity.id(), "주문 식별자", true, List.of(orderNoAttribute.id()), null));
        logical.saveRelationship(admin, new Relationship(null, model.id(), null, null, orderEntity.id(), null, itemEntity.id(), null, false, 1, 1, 0, null, null, null));
        jdbc.update("update TB_LGCL_ATRB set STD_TRM_SN = ? where LGCL_ATRB_SN = ?", base - 1, orderNoAttribute.id());
        EntityMapping entityMapping = mappings.saveEntityMapping(admin, new EntityMapping(null, orderEntity.id(), order.getId()));
        mappings.saveAttributeMapping(admin, new AttributeMapping(null, entityMapping.id(), orderNoAttribute.id(), orderNo.getId()));

        // ERD 배치 (사용자별)
        jdbc.update("insert into TB_ERD_LYT(ERD_LYT_SN, PRJ_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO) values(?, ?, ?, '{}', 0, 0, 1, 0)", base - 1, projectId, accountId);
        jdbc.update("insert into TB_LGCL_ERD_LYT(LGCL_ERD_LYT_SN, PRJ_SN, LGCL_MDL_SN, ACNT_SN, NODE_PSTN_CN, SCRN_X_CRDNT, SCRN_Y_CRDNT, SCRN_MGNF, VER_NO) values(?, ?, ?, ?, '{}', 0, 0, 1, 0)",
                base - 1, projectId, model.id(), accountId);

        // 공지(읽은 사용자, 첨부 파일 정보), 요청 로그, 모델 버전
        Long noticeId = notice(projectId, List.of(projectId));
        jdbc.update("insert into TB_ATCH_FILE(FILE_SN, PST_SN, FILE_NM, ORGNL_FILE_NM, FILE_EXTN_NM, FILE_PATH_NM) values(?, ?, 'a', 'a.txt', 'txt', '/tmp/a')", base - 1, noticeId);
        Logs log = Logs.builder().logUrl("/api/test").method("GET").ip("127.0.0.1").projectId(projectId).username(admin.getUsername()).statusCode(200).build();
        log.setTimestamp();
        logsService.saveLogs(log);
        versionService.create(admin, new Create("v1", null));

        // 원본 카탈로그 (AI-1b, lexorma-source) : 연결, 스키마, 테이블 2, 컬럼, PK, 인덱스, FK
        SourceConnection connection = tx.execute(s -> sourceConnectionRepository.save(SourceConnection.builder().projectId(projectId).name("운영 DB")
                .dbType(DbType.POSTGRESQL).host("db.internal").port(5432).databaseName("app").databaseNameType(DatabaseNameType.DATABASE).username("reader")
                .identifierComparison(IdentifierComparison.DBMS_DEFAULT).enabled(true).build()));
        SourceSchema sourceSchema = catalogWriter.saveSchema(projectId, connection.getId(), Name.of("app"), Name.of("public"));
        SourceTable users = catalogWriter.saveTable(projectId, sourceSchema.getId(), new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, "사용자"));
        catalogWriter.saveColumn(projectId, users.getId(), new SourceCatalogSpec.Column(Name.of("id"), 1, "bigint", null, null, null, false, null, null));
        SourceTable orders = catalogWriter.saveTable(projectId, sourceSchema.getId(), new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null));
        catalogWriter.saveColumn(projectId, orders.getId(), new SourceCatalogSpec.Column(Name.of("user_id"), 1, "bigint", null, null, null, false, null, null));
        catalogWriter.replaceConstraints(projectId, users.getId(), List.of(new SourceCatalogSpec.Constraint("users_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))));
        catalogWriter.replaceIndexes(projectId, orders.getId(), List.of(new SourceCatalogSpec.Index("ix_orders_user", "btree", false, List.of(Name.of("user_id")))));
        catalogWriter.replaceRelations(projectId, orders.getId(), List.of(new SourceCatalogSpec.Relation("fk_orders_user", null, null, Name.of("users"), "CASCADE", null,
                List.of(new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id"))))));
        // 수집 실행·대상·스키마/테이블 버전·변경 이력 (AI-1c) : 처음 수집(기준 버전)과 COMMENT 를 바꾼 수집(변경 이력). 위와 같은 구성을 수집한다
        ExecutionContext context = ExecutionContext.user(admin);
        for (String comment : List.of("사용자", "바뀐 사용자")) {
            String jobId = collections.start(context, connection.getId(), SourceCollectionSpec.Request.full()).jobId();
            collections.apply(context, jobId, new SourceCollectionSpec.Result(List.of(SourceCollectionSpec.Schema.completed(Name.of("app"), Name.of("public"), List.of(
                    new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("users"), SourceTableType.TABLE, comment),
                            List.of(new SourceCatalogSpec.Column(Name.of("id"), 1, "bigint", null, null, null, false, null, null)),
                            List.of(new SourceCatalogSpec.Constraint("users_pkey", SourceConstraintType.PRIMARY_KEY, null, List.of(Name.of("id")))), List.of(), List.of()),
                    new SourceCollectionSpec.Table(new SourceCatalogSpec.Table(Name.of("orders"), SourceTableType.TABLE, null),
                            List.of(new SourceCatalogSpec.Column(Name.of("user_id"), 1, "bigint", null, null, null, false, null, null)), List.of(),
                            List.of(new SourceCatalogSpec.Index("ix_orders_user", "btree", false, List.of(Name.of("user_id")))),
                            List.of(new SourceCatalogSpec.Relation("fk_orders_user", null, null, Name.of("users"), "CASCADE", null,
                                    List.of(new SourceCatalogSpec.ColumnPair(Name.of("user_id"), Name.of("id")))))))))));
        }
        // 원본 매핑 (M-4c) : 기준 연결, 스키마 매핑(public → 기본 스키마), TABLE 매핑(users → TB_ORD)
        sourceMappingService.setBase(admin, new SourceMappingDto.BaseRequest(connection.getId(), null));
        sourceMappingService.mapSchema(admin, new SourceMappingDto.SchemaRequest(sourceSchema.getId(), order.getSchema().getId()));
        sourceMappingService.mapTable(admin, new SourceMappingDto.TableRequest(users.getId(), order.getId()));
        // 원본 가져오기 실행 이력 (M-4e)
        jdbc.update("insert into TB_SRC_IMPRT_EXCN(SRC_IMPRT_EXCN_SN, PRJ_SN, SRC_CNTN_SN, RFLT_ORD, CHG_GROUP_ID) values (?, ?, ?, 1, ?)",
                900_000_000L + projectId, projectId, connection.getId(), "deletion-" + projectId);
        return new Filled(order);
    }

    private Long notice(long projectId, List<Long> targets) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        return tx.execute(s -> boardRepository.save(Board.builder().projectId(projectId).boardCategory(boardCategoryRepository.findAll().get(0))
                .title(targets.size() > 1 ? "함께 등록한 공지" : "공지").content("내용").username("admin").deleteYn('N').updatedTime(LocalDateTime.now())
                .accounts(new HashSet<>(List.of(admin))).projectIds(new ArrayList<>(targets)).build())).getId();
    }

    /** 모든 TB_ 테이블의 행 수 */
    private Map<String, Integer> rowCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = current_schema", String.class)) {
            String name = table.toUpperCase(Locale.ROOT);
            if (name.startsWith("TB_")) counts.put(name, jdbc.queryForObject("select count(*) from " + name, Integer.class));
        }
        assertThat(counts).hasSizeGreaterThan(35);
        return counts;
    }

    private Set<String> tablesWith(String column) {
        Set<String> tables = new TreeSet<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.columns where table_schema = current_schema and upper(column_name) = ?", String.class, column)) {
            if (table.toUpperCase(Locale.ROOT).startsWith("TB_")) tables.add(table.toUpperCase(Locale.ROOT));
        }
        return tables;
    }

    private AccountDto loginAsAdmin(String projectName) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(projectName).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(false).isMasterTable(false)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, boolean pk) {
        return tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(tableInfoRepository.getReferenceById(table.getId()))
                .columnName(name).dataType("VARCHAR").maxLength(10).isPk(pk).isApproval(false).build()));
    }
}
