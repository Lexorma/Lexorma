package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.home.controller.HomeController;
import com.lexorma.home.domain.dto.DashboardStatsDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 대시보드 통계 (2026-11-15 개편) : 정해 둔 데이터로 등록 수·승인 상태·표준 연결률·규칙 준수·품질·모델 구성을 하나씩 맞춰 보고,
 * 다른 프로젝트 데이터가 섞이지 않는지, 빈 프로젝트, 권한(NOT_APPROVE 거부·승인 처리 가능 여부), 데이터가 늘어도 SQL 문 수가 같은지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DashboardStatsIntegrationTest {

    @Autowired HomeController api;
    @Autowired StandardizationQualityService qualityService;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired TableRelationRepository relationRepository;
    @Autowired TableIndexRepository indexRepository;
    @Autowired TableConstraintRepository constraintRepository;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Account manager;
    private Account viewer;
    private Account user;
    private Account notApproved;
    private Project project;
    private Project other;
    private Project empty;
    private SchemaInfo main;

    @BeforeEach
    void setUp() {
        manager = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        user = account("ROLE_USER");
        notApproved = account("ROLE_NOT_APPROVE");
        project = project(manager, List.of(manager, viewer, user, notApproved));
        other = project(manager, List.of(manager));
        empty = project(manager, List.of(manager));
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            main = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName("DEFAULT").isDefault(true).build());
            schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName("ZZ_EMPTY").isDefault(false).build());
            // 단어 4 : 승인 2, 승인 대기 2 (false 1 · null 1)
            word(p, "주문", true);
            word(p, "번호", true);
            word(p, "명", false);
            word(p, "메모", null);
            StandardDomain code10 = domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("번호V10").commonStandardDomainCategory("번호")
                    .dataType("VARCHAR").dataLength(10).isApproval(true).build());
            domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("명V100").commonStandardDomainCategory("명").dataType("VARCHAR").dataLength(100).build());
            StandardTerm ordNo = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("주문_번호").commonStandardTermAbbreviation("ORD_NO")
                    .standardDomain(code10).isApproval(true).build());
            StandardTerm ordNm = termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("주문_명").commonStandardTermAbbreviation("ORD_NM").isApproval(false).build());

            // 테이블 4 : A(코멘트·업무 설명 있음, 승인) / B(코멘트 공백, 업무 설명 없음) / C(코멘트 없음) / D(컬럼 없음, 업무 설명 있음)
            TableInfo a = table(p, "TB_A", "주문", "주문 업무", true);
            TableInfo b = table(p, "TB_B", " ", null, false);
            TableInfo c = table(p, "TB_C", null, null, false);
            table(p, "TB_D", "빈 테이블", "업무", false);
            // 컬럼 6 : 승인 2
            column(a, "ORD_NO", "주문 번호", "VARCHAR", 10, ordNo, "주문을 구분하는 번호", true);   // 준수
            column(a, "ORD_NUM", "주문 번호", "VARCHAR", 10, ordNo, null, true);              // 컬럼명 불일치
            column(a, "ORD_NM", "주문 명", "VARCHAR", 100, ordNm, null, false);               // 미승인 용어 연결, 도메인 없음 → 준수
            column(c, "ORD_NO", "주문 번호", "VARCHAR", 20, ordNo, null, false);              // 도메인 정의 불일치 (길이)
            column(b, "MEMO", null, "TEXT", null, null, null, false);                          // 용어 미연결, 코멘트 없음
            column(b, "ETC", "  ", "TEXT", null, null, null, false);                           // 용어 미연결, 코멘트 공백
            // 관계 : FK A→C, LOGICAL A→A / 관계 없는 테이블 B·D
            relationRepository.save(TableRelation.builder().projectId(p).sourceTable(a).targetTable(c).relationName("FK_A_C").relationType(TableRelation.Type.FK)
                    .relationSource(TableRelation.Source.USER).isApproval(true).build());
            relationRepository.save(TableRelation.builder().projectId(p).sourceTable(a).targetTable(a).relationName("REL_A_A").relationType(TableRelation.Type.LOGICAL)
                    .relationSource(TableRelation.Source.USER).isApproval(true).build());
            indexRepository.save(TableIndex.builder().projectId(p).tableInfo(a).indexName("IX_A_01").indexType("BTREE").isUnique(false).isApproval(true).build());
            constraintRepository.save(TableConstraint.builder().projectId(p).tableInfo(a).constraintName("UX_A_01").constraintType(TableConstraint.Type.UNIQUE).isApproval(true).build());
            constraintRepository.save(TableConstraint.builder().projectId(p).tableInfo(c).constraintName("CK_C_01").constraintType(TableConstraint.Type.CHECK)
                    .checkExpression("ORD_NO IS NOT NULL").isApproval(true).build());

            // 다른 프로젝트 : 섞이면 숫자가 달라진다
            Long o = other.getId();
            SchemaInfo os = schemaRepository.save(SchemaInfo.builder().projectId(o).schemaName("DEFAULT").isDefault(true).build());
            word(o, "다른", false);
            TableInfo ot = tableRepository.save(TableInfo.builder().projectId(o).schema(os).tableName("TB_OTHER").isApproval(false).isMasterTable(false).build());
            columnRepository.save(ColumnInfo.builder().projectId(o).tableInfo(ot).tableName("TB_OTHER").columnName("X").dataType("TEXT").sortOrder(1).isApproval(false).build());
            relationRepository.save(TableRelation.builder().projectId(o).sourceTable(ot).targetTable(ot).relationName("R").relationType(TableRelation.Type.FK)
                    .relationSource(TableRelation.Source.USER).isApproval(false).build());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("등록 수·승인 상태(null 은 승인 대기)·관계(FK·논리)·인덱스·제약조건(UNIQUE·CHECK)·스키마별 테이블(빈 스키마 포함)·컬럼 수 구간·관계 없는 테이블")
    void countsAndModel() {
        DashboardStatsDto s = stats(viewer, project);
        assertThat(s.counts()).isEqualTo(new DashboardStatsDto.Counts(4, 2, 2, 2, 4, 6, 2, 1, 1, 1, 2, 1, 1));
        assertThat(s.approvals()).containsExactly(
                new DashboardStatsDto.Approval("WORD", 2, 2),
                new DashboardStatsDto.Approval("DOMAIN", 1, 1),
                new DashboardStatsDto.Approval("TERM", 1, 1),
                new DashboardStatsDto.Approval("TABLE", 1, 3),
                new DashboardStatsDto.Approval("COLUMN", 2, 4));
        // 전체 수 = 승인 + 승인 대기
        assertThat(s.approvals().get(3).approved() + s.approvals().get(3).pending()).isEqualTo(s.counts().tables());
        assertThat(s.approvals().get(4).approved() + s.approvals().get(4).pending()).isEqualTo(s.counts().columns());

        assertThat(s.model().schemas()).extracting(DashboardStatsDto.SchemaTables::schemaName, DashboardStatsDto.SchemaTables::tables)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DEFAULT", 4L), org.assertj.core.groups.Tuple.tuple("ZZ_EMPTY", 0L));
        assertThat(s.model().columnsPerTable()).extracting(DashboardStatsDto.ColumnBucket::label, DashboardStatsDto.ColumnBucket::tables).containsExactly(
                org.assertj.core.groups.Tuple.tuple("0", 1L), org.assertj.core.groups.Tuple.tuple("1-5", 3L), org.assertj.core.groups.Tuple.tuple("6-10", 0L),
                org.assertj.core.groups.Tuple.tuple("11-20", 0L), org.assertj.core.groups.Tuple.tuple("21-50", 0L), org.assertj.core.groups.Tuple.tuple("51+", 0L));
        assertThat(s.model().tablesWithoutRelation()).isEqualTo(2);
    }

    @Test
    @DisplayName("표준 연결률(용어 승인 무관)·승인된 용어 연결·규칙 준수(M-5 문제 없는 컬럼), 품질 유형별(M-5 와 같은 값)·코멘트/업무 설명 없는 테이블·컬럼(공백도 없음)")
    void standardAndQuality() {
        DashboardStatsDto s = stats(viewer, project);
        assertThat(s.standard()).isEqualTo(new DashboardStatsDto.Standard(6, 4, 3, 2));
        assertThat(s.quality()).isEqualTo(new DashboardStatsDto.Quality(4, 2, 1, 1, 2, 2, 2, 5));
        // 품질 진단 화면의 요약과 같은 계산
        StandardizationQualityDto.Summary diagnosis = qualityService.selectSummary(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER"));
        assertThat(s.quality().issueColumns()).isEqualTo(diagnosis.issueColumns());
        assertThat(s.quality().termNotLinked()).isEqualTo(diagnosis.termNotLinked());
        assertThat(s.standard().totalColumns()).isEqualTo(diagnosis.totalColumns());
        // 미연결 = 전체 − 연결
        assertThat(s.standard().totalColumns() - s.standard().linkedColumns()).isEqualTo(s.quality().termNotLinked());
    }

    @Test
    @DisplayName("프로젝트 격리 : 다른 프로젝트는 자기 데이터만, 빈 프로젝트는 모두 0 (전체 컬럼 0 — 화면은 '대상 없음')")
    void isolationAndEmpty() {
        DashboardStatsDto o = stats(manager, other);
        assertThat(o.counts()).isEqualTo(new DashboardStatsDto.Counts(1, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0));
        assertThat(o.approvals()).extracting(DashboardStatsDto.Approval::pending).containsExactly(1L, 0L, 0L, 1L, 1L);
        assertThat(o.standard()).isEqualTo(new DashboardStatsDto.Standard(1, 0, 0, 0));

        DashboardStatsDto e = stats(manager, empty);
        assertThat(e.counts()).isEqualTo(new DashboardStatsDto.Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(e.standard()).isEqualTo(new DashboardStatsDto.Standard(0, 0, 0, 0));
        assertThat(e.quality()).isEqualTo(new DashboardStatsDto.Quality(0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(e.model().schemas()).isEmpty();
        assertThat(e.model().columnsPerTable()).allSatisfy(b -> assertThat(b.tables()).isZero());
        assertThat(e.approvals()).allSatisfy(a -> assertThat(a.approved() + a.pending()).isZero());
    }

    @Test
    @DisplayName("권한 : 통계는 VIEWER 부터(NOT_APPROVE 거부, 공지 API 는 그대로 허용). 승인 처리 가능 = 승인 권한(프로젝트 관리자·MANAGER·ADMIN) + 사전은 DBA·모델은 USER")
    void permissions() {
        AccountDto waiting = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(notApproved.getId()), "ROLE_NOT_APPROVE");
        assertThatThrownBy(() -> api.stats(waiting)).isInstanceOf(AccessDeniedException.class);
        assertThat(api.dashboard(waiting).getBody().getNotices()).isNotNull();

        DashboardStatsDto asViewer = stats(viewer, project);
        assertThat(asViewer.canApproveDictionary()).isFalse();
        assertThat(asViewer.canApproveModel()).isFalse();
        // 관리자가 아닌 USER : 둘 다 불가
        DashboardStatsDto asUser = api.stats(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(user.getId()), "ROLE_USER")).getBody();
        assertThat(asUser.canApproveDictionary()).isFalse();
        assertThat(asUser.canApproveModel()).isFalse();
        // 프로젝트 관리자 USER : 모델만 (사전 승인은 DBA 역할도 필요)
        DashboardStatsDto asManager = api.stats(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_USER")).getBody();
        assertThat(asManager.canApproveDictionary()).isFalse();
        assertThat(asManager.canApproveModel()).isTrue();
        // 프로젝트 관리자 DBA : 둘 다 (DBA > USER)
        DashboardStatsDto asDba = api.stats(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(manager.getId()), "ROLE_DBA")).getBody();
        assertThat(asDba.canApproveDictionary()).isTrue();
        assertThat(asDba.canApproveModel()).isTrue();
        // 관리자가 아닌 DBA : 승인 권한이 없어 둘 다 불가
        DashboardStatsDto asOtherDba = api.stats(SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(user.getId()), "ROLE_DBA")).getBody();
        assertThat(asOtherDba.canApproveDictionary()).isFalse();
        assertThat(asOtherDba.canApproveModel()).isFalse();
    }

    @Test
    @DisplayName("집계 쿼리 : 테이블·컬럼이 늘어도 요청당 SQL 문 수가 같다 (엔티티를 하나씩 읽지 않는다)")
    void constantQueries() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        stats(viewer, project);
        statistics.clear();
        stats(viewer, project);
        long before = statistics.getPrepareStatementCount();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 40; i++) {
                TableInfo t = table(project.getId(), "TB_MORE_" + i, "추가", null, false);
                for (int c = 0; c < 3; c++) column(t, "C" + c, "컬럼", "TEXT", null, null, null, false);
            }
        });
        statistics.clear();
        DashboardStatsDto s = stats(viewer, project);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(before);
        assertThat(s.counts().tables()).isEqualTo(44);
        assertThat(s.counts().columns()).isEqualTo(126);
    }

    // ------------------------------------------------------------------ 준비

    private DashboardStatsDto stats(Account account, Project target) {
        String role = account == viewer ? "ROLE_VIEWER" : "ROLE_USER";
        return api.stats(SecurityTestSupport.loginAs(projectRepository, target.getId(), String.valueOf(account.getId()), role)).getBody();
    }

    private void word(Long projectId, String name, Boolean approved) {
        wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName(name + "-" + UUID.randomUUID().toString().substring(0, 4))
                .commonStandardWordAbbreviation("W" + UUID.randomUUID().toString().substring(0, 4)).isApproval(approved).build());
    }

    private TableInfo table(Long projectId, String name, String korName, String biz, boolean approved) {
        return tableRepository.save(TableInfo.builder().projectId(projectId).schema(main).tableName(name).tableKorName(korName).bizDescription(biz)
                .isApproval(approved).isMasterTable(false).build());
    }

    private void column(TableInfo t, String name, String desc, String type, Integer length, StandardTerm term, String biz, boolean approved) {
        columnRepository.save(ColumnInfo.builder().projectId(t.getProjectId()).tableInfo(t).tableName(t.getTableName()).columnName(name).columnDesc(desc)
                .dataType(type).maxLength(length).sortOrder(1).standardTerms(term).bizDescription(biz).isPk(false).isNullable(true).isApproval(approved).build());
    }

    private Project project(Account owner, List<Account> members) {
        Project p = projectRepository.save(Project.builder().projectName("dash-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner)
                .dbType(DbType.POSTGRESQL).build());
        for (Account a : members) projectMemberRepository.save(ProjectMember.builder().project(p).account(a).isActive(true).build());
        return p;
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("dash-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }
}
