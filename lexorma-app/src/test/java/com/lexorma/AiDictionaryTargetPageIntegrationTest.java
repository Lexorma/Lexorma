package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.ai.dictionary.domain.dto.AiDictionaryDto;
import com.lexorma.ai.dictionary.domain.dto.AiEvaluationDto;
import com.lexorma.ai.dictionary.service.AiDescriptionEvaluationService;
import com.lexorma.ai.dictionary.service.AiDescriptionJobService;
import com.lexorma.ai.dictionary.service.AiDictionaryQueryService;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 설명 제안 대상 테이블 목록의 서버 쪽 나누기 (AI-5-PAGE) : 500개 제한 없이 프로젝트 전체를 쪽(30개) 단위로 검색·거르기·정렬하고 전체 건수를 준다.
 * 쪽을 모두 이으면 빠짐·겹침이 없고(같은 이름은 ID 순), 검색은 예전 500개 밖의 테이블도 찾으며, 미리 보기는 여러 쪽에서 고른 대상 전체로 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiDictionaryTargetPageIntegrationTest {

    /** 1,051 + 다른 스키마의 같은 이름 1 → 1,052개 (마지막 쪽은 2개) */
    private static final int TABLES = 1051;

    @Autowired AiDictionaryQueryService queries;
    @Autowired AiDescriptionJobService jobs;
    @Autowired AiDescriptionEvaluationService evaluation;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Account viewer;
    private Account owner;
    private Account outsider;
    private Project project;
    private Project other;
    private List<Long> expectedOrder;
    private TableInfo described;
    private TableInfo partly;

    @BeforeEach
    void setUp() {
        owner = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        outsider = account("ROLE_USER");
        project = project(owner, List.of(owner, viewer));
        other = project(outsider, List.of(outsider));
        tx.executeWithoutResult(s -> {
            SchemaInfo main = schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            SchemaInfo archive = schemaRepository.save(SchemaInfo.builder().projectId(project.getId()).schemaName("archive").isDefault(false).build());
            List<TableInfo> list = new ArrayList<>();
            for (int i = 0; i < TABLES; i++) list.add(table(main, String.format("TB_T%04d", i), i % 7 == 0 ? "주문 " + i : "Order " + i, null));
            // LIKE 와일드카드 : 'B_A' 는 TB_A1 만, TBXA1 은 아니다
            list.set(0, table(main, "TB_A1", "와일드카드 대상", null));
            list.set(1, table(main, "TBXA1", "와일드카드 아님", null));
            tableRepository.saveAll(list);
            // 다른 스키마의 같은 이름 (정렬 동점 → ID 순)
            tableRepository.save(table(archive, "TB_T0500", "보관 주문", null));
            // 업무 설명 : 테이블·컬럼 모두 있음(onlyMissing 에서 빠짐) / 테이블만 있고 설명 없는 컬럼 있음(남음)
            described = tableRepository.save(table(main, "TB_DONE", "다 쓴 테이블", "업무 설명"));
            column(described, "DONE_ID", "다 씀");
            partly = tableRepository.save(table(main, "TB_PART", "일부 쓴 테이블", "업무 설명"));
            column(partly, "PART_ID", "다 씀");
            column(partly, "PART_NM", null);
            // 다른 프로젝트 : 같은 이름이 있어도 보이지 않는다
            SchemaInfo foreign = schemaRepository.save(SchemaInfo.builder().projectId(other.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            tableRepository.save(table(foreign, "TB_T0001", "다른 프로젝트", null));
            tableRepository.save(table(foreign, "TB_ZZ_FOREIGN", "다른 프로젝트", null));
        });
        // 기대 순서 : DB 의 이름 정렬(콜레이션은 DB 마다 다르다 — H2 는 코드 순, PostgreSQL 은 언어 규칙) → 같은 이름은 ID 순. 쪽을 이은 결과가 이것과 같아야 한다
        expectedOrder = tx.execute(s -> entityManagerFactory.createEntityManager().createQuery(
                "select t.id from TableInfo t where t.projectId = :p order by t.tableName, t.id", Long.class).setParameter("p", project.getId()).getResultList());
    }

    @Test
    @DisplayName("쪽 나누기 : 기본 30개, 전체 건수·쪽 수, 처음·중간·마지막 쪽, 모든 쪽을 이으면 이름 → ID 순으로 빠짐·겹침 없음 (500개 넘게), 쪽 크기·번호 범위")
    void pages() {
        int total = expectedOrder.size();
        assertThat(total).isEqualTo(TABLES + 3);
        Page<AiDictionaryDto.TargetRow> first = page(false, null, 0, 0);
        assertThat(first.getContent()).hasSize(30);
        assertThat(first.getTotalElements()).isEqualTo(total);
        assertThat(first.getTotalPages()).isEqualTo((total + 29) / 30);
        assertThat(page(false, null, 17, 30).getContent()).extracting(AiDictionaryDto.TargetRow::tableId).containsExactlyElementsOf(expectedOrder.subList(510, 540));
        Page<AiDictionaryDto.TargetRow> last = page(false, null, first.getTotalPages() - 1, 30);
        assertThat(last.getContent()).hasSize(total - (first.getTotalPages() - 1) * 30);

        List<Long> all = new ArrayList<>();
        for (int p = 0; p < first.getTotalPages(); p++) page(false, null, p, 30).getContent().forEach(r -> all.add(r.tableId()));
        assertThat(all).containsExactlyElementsOf(expectedOrder);
        assertThat(new HashSet<>(all)).hasSize(total);

        // 크기 : 0 이하 → 30, 상한 100. 음수 쪽 → 0, 범위 밖 → 빈 쪽 (건수는 그대로)
        assertThat(page(false, null, 0, -5).getContent()).hasSize(30);
        assertThat(page(false, null, 0, 1000).getContent()).hasSize(100);
        assertThat(page(false, null, -3, 30).getNumber()).isZero();
        Page<AiDictionaryDto.TargetRow> beyond = page(false, null, 9999, 30);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(total);
        // 한 쪽에 대한 SQL 문 수는 쪽 위치·테이블 수와 무관하게 일정하다 (실행 컨텍스트 재검증 + 조회 · 건수 · 컬럼 수 · 최근 제안)
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        page(false, null, 1, 30);
        long second = stats.getPrepareStatementCount();
        stats.clear();
        page(false, null, first.getTotalPages() - 2, 30);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(second).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("검색·거르기 : 예전 500개 밖 테이블도 찾음, 이름·테이블 코멘트(한글·대소문자 무시) 부분 일치, LIKE 문자는 글자 그대로, 공백만이면 전체, 업무 설명 빠진 것만, 조합, 0건")
    void searchAndFilter() {
        // 이름 순 1,000번째쯤 — 예전에는 앞 500개만 받아 화면 검색으로 찾을 수 없었다
        Page<AiDictionaryDto.TargetRow> far = page(false, "tb_t0999", 0, 30);
        assertThat(far.getContent()).extracting(AiDictionaryDto.TargetRow::name).containsExactly("TB_T0999");
        assertThat(expectedOrder.indexOf(far.getContent().getFirst().tableId())).isGreaterThan(500);
        // 테이블 코멘트 : 한글, 영문 대소문자 무시
        assertThat(page(false, "주문 98", 0, 30).getContent()).extracting(AiDictionaryDto.TargetRow::name).contains("TB_T0098", "TB_T0980");
        assertThat(page(false, "oRdEr 997", 0, 30).getContent()).extracting(AiDictionaryDto.TargetRow::name).containsExactly("TB_T0997");
        // 같은 이름 두 스키마 : 둘 다, ID 순
        assertThat(page(false, "TB_T0500", 0, 30).getContent()).hasSize(2);
        // '_' '%' 는 와일드카드가 아니다
        assertThat(page(false, "B_A", 0, 30).getContent()).extracting(AiDictionaryDto.TargetRow::name).containsExactly("TB_A1");
        assertThat(page(false, "%", 0, 30).getTotalElements()).isZero();
        // 공백만 → 전체, 결과 0건
        assertThat(page(false, "   ", 0, 30).getTotalElements()).isEqualTo(expectedOrder.size());
        assertThat(page(false, "없는이름", 0, 30).getTotalElements()).isZero();
        // 업무 설명 빠진 것만 : 다 쓴 테이블만 빠지고, 테이블 설명이 있어도 설명 없는 컬럼이 있으면 남는다
        Page<AiDictionaryDto.TargetRow> missing = page(true, null, 0, 30);
        assertThat(missing.getTotalElements()).isEqualTo(expectedOrder.size() - 1);
        assertThat(page(true, "TB_DONE", 0, 30).getTotalElements()).isZero();
        Page<AiDictionaryDto.TargetRow> part = page(true, "TB_PART", 0, 30);
        assertThat(part.getContent()).singleElement().satisfies(r -> {
            assertThat(r.hasBizDescription()).isTrue();
            assertThat(r.detail().columnCount()).isEqualTo(2);
            assertThat(r.detail().missingColumnCount()).isEqualTo(1);
        });
        // 쪽 없이 부르는 예전 목록(품질 평가)은 같은 순서로 앞 500개
        assertThat(queries.targets(user(viewer), null, false, null)).extracting(AiDictionaryDto.TargetRow::tableId).containsExactlyElementsOf(expectedOrder.subList(0, 500));
    }

    @Test
    @DisplayName("프로젝트·권한 : 다른 프로젝트의 테이블은 건수에도 없음, 멤버가 아니면 거부. 미리 보기는 여러 쪽에서 고른 대상 전체로 하고 다른 프로젝트 ID 가 섞이면 거절")
    void scopeAndPreview() {
        Set<String> names = new LinkedHashSet<>();
        Page<AiDictionaryDto.TargetRow> first = page(false, null, 0, 100);
        for (int p = 0; p < first.getTotalPages(); p++) page(false, null, p, 100).getContent().forEach(r -> names.add(r.name()));
        assertThat(names).doesNotContain("TB_ZZ_FOREIGN");
        assertThat(page(false, "FOREIGN", 0, 30).getTotalElements()).isZero();
        assertThat(queries.targetTables(user(outsider), false, null, 0, 30).getContent()).extracting(AiDictionaryDto.TargetRow::name).contains("TB_T0001", "TB_ZZ_FOREIGN");
        ExecutionContext stranger = ExecutionContext.user(AccountDto.builder().id(String.valueOf(outsider.getId())).projectId(project.getId()).build());
        assertThatThrownBy(() -> queries.targetTables(stranger, false, null, 0, 30)).isInstanceOf(CustomException.class);

        // 1쪽·2쪽·35쪽에서 고른 테이블 + 다른 프로젝트 테이블 ID → 미리 보기는 이 프로젝트의 세 개만
        Long a = page(false, null, 0, 30).getContent().get(3).tableId();
        Long b = page(false, null, 1, 30).getContent().get(7).tableId();
        Long c = page(false, null, 34, 30).getContent().get(0).tableId();
        Long foreign = tableRepository.findAll().stream().filter(t -> other.getId().equals(t.getProjectId())).findFirst().orElseThrow().getId();
        List<AiDictionaryDto.Target> picked = List.of(new AiDictionaryDto.Target(a, null), new AiDictionaryDto.Target(b, null), new AiDictionaryDto.Target(c, null),
                new AiDictionaryDto.Target(foreign, null));
        // 다른 프로젝트 ID 가 섞이면 서버가 거절한다 (클라이언트 선택을 믿지 않는다)
        assertThatThrownBy(() -> jobs.preview(user(owner), new AiDictionaryDto.JobRequest("SELECTED", null, true, picked, true, false)))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(com.lexorma.common.exception.domain.ErrorCode.TABLE_IS_NOT_DEFINED);
        AiDictionaryDto.JobPreview preview = jobs.preview(user(owner), new AiDictionaryDto.JobRequest("SELECTED", null, false, picked.subList(0, 3), true, false));
        assertThat(preview.items()).extracting(AiDictionaryDto.PreviewItem::tableId).containsExactlyInAnyOrder(a, b, c);
        assertThat(preview.create()).isEqualTo(3);
    }

    @Test
    @DisplayName("품질 평가 (AI-5-EVAL) : 예전 500개 밖 테이블을 쪽 목록 검색으로 찾아 평가 후보 조회 → 등록 → 상세까지 이어짐. 다른 프로젝트 테이블은 후보·등록 거부, VIEWER 는 등록 불가")
    void evaluationBeyondOldLimit() {
        // 평가 화면이 고르는 길 : /targets/tables 검색 → tableId
        Long far = expectedOrder.get(1000);
        String farName = tableRepository.findById(far).orElseThrow().getTableName();
        Page<AiDictionaryDto.TargetRow> found = page(false, farName, 0, 30);
        assertThat(found.getContent()).extracting(AiDictionaryDto.TargetRow::tableId).contains(far);
        assertThat(queries.targets(user(viewer), null, false, null)).extracting(AiDictionaryDto.TargetRow::tableId).doesNotContain(far);

        List<AiEvaluationDto.Candidate> candidates = evaluation.candidates(user(viewer), far);
        assertThat(candidates).first().satisfies(c -> {
            assertThat(c.tableId()).isEqualTo(far);
            assertThat(c.columnId()).isNull();
            assertThat(c.registered()).isFalse();
        });
        assertThatThrownBy(() -> evaluation.register(user(viewer), new AiEvaluationDto.RegisterRequest(List.of(new AiEvaluationDto.RegisterItem(far, null, List.of())))))
                .isInstanceOf(CustomException.class);
        AiEvaluationDto.RegisterResult registered = evaluation.register(user(owner), new AiEvaluationDto.RegisterRequest(List.of(new AiEvaluationDto.RegisterItem(far, null, List.of()))));
        assertThat(registered.created()).isEqualTo(1);
        AiEvaluationDto.TargetRow target = evaluation.list(user(viewer)).stream().filter(r -> far.equals(r.tableId())).findFirst().orElseThrow();
        assertThat(evaluation.detail(user(viewer), target.id()).target().targetName()).isEqualTo(farName);
        assertThat(evaluation.candidates(user(viewer), far).getFirst().registered()).isTrue();

        // 다른 프로젝트의 테이블 ID 를 직접 보내도 후보·등록 모두 거부, 등록된 것도 없다
        Long foreign = tableRepository.findAll().stream().filter(t -> other.getId().equals(t.getProjectId())).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> evaluation.candidates(user(owner), foreign)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> evaluation.register(user(owner), new AiEvaluationDto.RegisterRequest(List.of(new AiEvaluationDto.RegisterItem(foreign, null, List.of())))))
                .isInstanceOf(CustomException.class);
        assertThat(evaluation.list(user(owner))).extracting(AiEvaluationDto.TargetRow::tableId).containsExactly(far);
    }

    // ------------------------------------------------------------------ 준비

    private Page<AiDictionaryDto.TargetRow> page(boolean onlyMissing, String keyword, int page, int size) {
        return queries.targetTables(user(viewer), onlyMissing, keyword, page, size);
    }

    private TableInfo table(SchemaInfo schema, String name, String korName, String biz) {
        return TableInfo.builder().projectId(schema.getProjectId()).schema(schema).tableName(name).tableKorName(korName).tableDescription(korName)
                .bizDescription(biz).isApproval(false).isMasterTable(false).build();
    }

    private void column(TableInfo t, String name, String biz) {
        columnRepository.save(ColumnInfo.builder().projectId(t.getProjectId()).tableInfo(t).tableName(t.getTableName()).columnName(name).columnDesc(name)
                .dataType("BIGINT").sortOrder(1).bizDescription(biz).isPk(false).isNullable(true).isApproval(false).build());
    }

    private Project project(Account manager, List<Account> members) {
        Project p = projectRepository.save(Project.builder().projectName("ai5p-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(manager)
                .dbType(DbType.POSTGRESQL).build());
        for (Account a : members) projectMemberRepository.save(ProjectMember.builder().project(p).account(a).isActive(true).build());
        return p;
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai5p-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private ExecutionContext user(Account account) {
        Long projectId = account == outsider ? other.getId() : project.getId();
        return ExecutionContext.user(AccountDto.builder().id(String.valueOf(account.getId())).projectId(projectId).build());
    }
}
