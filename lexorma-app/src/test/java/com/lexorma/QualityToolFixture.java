package com.lexorma;

import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.inspection.repository.InspectionRepository;
import com.lexorma.data.inspection.service.InspectionRunService;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * AI-7 품질 진단 Tool 시험 데이터 : 새 프로젝트에 실제 모델·사전을 만들고 <b>실제 저장 검사</b>(InspectionRunService)를 돌린다.
 * 실행 1 → (테이블 코멘트 채움 · 컬럼 1개 삭제) → 실행 2 — 실제 해결(RULE_SATISFIED)·대상 삭제(TARGET_DELETED)가 생긴다.
 * 큰 그룹 : 같은 단어 구성의 표준 용어 {@value #GROUP_TERMS}개 (TERM_SAME_WORDS 구성원 > 20). 실패·중단 같은 실행 상태는 {@link #insertRun} 으로만 (JDBC, 제약 그대로).
 */
final class QualityToolFixture {

    static final int GROUP_TERMS = 25;
    static final int EXTRA_COLUMNS = 7;

    record Data(Project project, AccountDto admin, Account viewer, Long viewerMemberId, TableInfo table, TableInfo badTable, List<ColumnInfo> columns,
                ColumnInfo deletedColumn, long run1, long run2) {

        long projectId() {
            return project.getId();
        }

        ExecutionContext viewerContext() {
            return ExecutionContext.user(AccountDto.builder().id(String.valueOf(viewer.getId())).projectId(project.getId()).build());
        }
    }

    private final ApplicationContext beans;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    QualityToolFixture(ApplicationContext beans) {
        this.beans = beans;
        this.tx = beans.getBean(TransactionTemplate.class);
        this.jdbc = beans.getBean(JdbcTemplate.class);
    }

    /** 실제 검사 2회까지 */
    Data create(String name) {
        ProjectRepository projects = beans.getBean(ProjectRepository.class);
        AccountRepository accounts = beans.getBean(AccountRepository.class);
        Account admin = accounts.findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projects.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true).account(admin)
                .tablePrefix("TB_").build());
        member(project, admin);
        AccountDto login = SecurityTestSupport.loginAs(projects, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        Account viewer = account("ROLE_VIEWER");
        Long viewerMember = member(project, viewer);

        long pid = project.getId();
        StandardWord member = word(pid, "회원", "MBR");
        StandardWord nameWord = word(pid, "이름", "NM");
        StandardWord number = word(pid, "번호", "NO");
        StandardDomain domain = beans.getBean(StandardDomainRepository.class).save(StandardDomain.builder().projectId(pid).commonStandardDomainName("이름V10")
                .dataType("VARCHAR").dataLength(10).isApproval(true).build());
        StandardTerm term = term(pid, "회원_이름", "MBR_NM", domain, member, nameWord);
        // 같은 단어 구성(회원 + 번호) 용어 25개 → TERM_SAME_WORDS 한 그룹, 구성원 25
        for (int i = 0; i < GROUP_TERMS; i++) term(pid, "회원번호" + i, "MBR_NO" + i, domain, i % 2 == 0 ? member : number, i % 2 == 0 ? number : member);
        TableInfo table = table(pid, "TB_MBR");
        List<ColumnInfo> columns = new ArrayList<>();
        columns.add(column(pid, table, "MBR_NM", term));
        for (int i = 0; i < EXTRA_COLUMNS; i++) columns.add(column(pid, table, "COL_" + (char) ('A' + i), null));
        TableInfo bad = table(pid, "TBL_X");

        long run1 = runAndWait(login);
        // 실제 해결 : 테이블 코멘트를 채운다 / 대상 삭제 : 마지막 컬럼을 지운다
        tx.executeWithoutResult(s -> {
            TableInfo t = beans.getBean(TableInfoRepository.class).findById(table.getId()).orElseThrow();
            t.setTableKorName("회원");
        });
        ColumnInfo deleted = columns.getLast();
        tx.executeWithoutResult(s -> beans.getBean(ColumnInfoRepository.class).deleteById(deleted.getId()));
        long run2 = runAndWait(login);
        SecurityTestSupport.clear();
        return new Data(project, login, viewer, viewerMember, table, bad, columns, deleted, run1, run2);
    }

    long runAndWait(AccountDto login) {
        SecurityTestSupport.loginAs(beans.getBean(ProjectRepository.class), login.getProjectId(), login.getId(), "ROLE_ADMIN");
        long id = beans.getBean(InspectionRunService.class).request(login);
        InspectionRepository repository = beans.getBean(InspectionRepository.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (repository.findRun(login.getProjectId(), id).orElseThrow().status().active()) {
            if (System.nanoTime() > deadline) throw new AssertionError("검사 실행 " + id + " 이 끝나지 않았다");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        return id;
    }

    /**
     * 실행 상태 시험용 실행 행 (JDBC — V4 제약 그대로). 진행 중(PENDING·RUNNING)이면 PRGRS_PRJ_SN 을 채우고 END_DT 를 비운다.
     * ruleStatuses : 규칙 코드 → RUNNING·COMPLETED·FAILED (적용 규칙)
     */
    long insertRun(long projectId, String status, String errorCode, java.time.LocalDateTime requested, java.time.LocalDateTime started,
                   java.time.LocalDateTime basis, java.time.LocalDateTime ended, java.util.Map<String, String> ruleStatuses) {
        long id = jdbc.queryForObject("select nextval('SQ_INSP_EXCN')", Long.class);
        boolean active = status.equals("PENDING") || status.equals("RUNNING");
        jdbc.update("insert into TB_INSP_EXCN(INSP_EXCN_SN, PRJ_SN, PRGRS_PRJ_SN, ACNT_SN, EXCN_STTS_CD, DMND_DT, BGNG_DT, CRTR_DT, END_DT, ERR_CD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                        + " values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, now(), now(), 'test', 'test')",
                id, projectId, active ? projectId : null, status, ts(requested), ts(started), ts(basis), active ? null : ts(ended == null ? requested : ended), errorCode);
        ruleStatuses.forEach((rule, ruleStatus) -> jdbc.update("insert into TB_INSP_EXCN_RULE(INSP_EXCN_RULE_SN, INSP_EXCN_SN, RULE_CD, EXCN_STTS_CD, RSLT_CNT, REG_DT, MDFCN_DT,"
                + " RGTR_ID, MDFR_ID) values (nextval('SQ_INSP_EXCN_RULE'), ?, ?, ?, ?, now(), now(), 'test', 'test')", id, rule, ruleStatus,
                ruleStatus.equals("COMPLETED") ? 0 : null));
        return id;
    }

    private static java.sql.Timestamp ts(java.time.LocalDateTime time) {
        return time == null ? null : java.sql.Timestamp.valueOf(time);
    }

    /** 결과 행 없이 실행만 있는 새 프로젝트 (실행 상태 시험) */
    Data emptyProject(String name) {
        ProjectRepository projects = beans.getBean(ProjectRepository.class);
        Account admin = beans.getBean(AccountRepository.class).findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projects.save(Project.builder().projectName(name + "-" + UUID.randomUUID().toString().substring(0, 6)).isActive(true).account(admin)
                .tablePrefix("TB_").build());
        member(project, admin);
        AccountDto login = AccountDto.builder().id(String.valueOf(admin.getId())).projectId(project.getId()).build();
        Account viewer = account("ROLE_VIEWER");
        Long viewerMember = member(project, viewer);
        return new Data(project, login, viewer, viewerMember, null, null, List.of(), null, 0, 0);
    }

    Account account(String role) {
        String username = "ai7-" + UUID.randomUUID().toString().substring(0, 8);
        return tx.execute(s -> beans.getBean(AccountRepository.class).save(Account.builder().username(username).name(username).password("{noop}x").isActive(true)
                .isPasswdCheck(true).userRoles(new HashSet<>(Set.of(beans.getBean(RoleRepository.class).findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    Long member(Project project, Account account) {
        return tx.execute(s -> beans.getBean(ProjectMemberRepository.class).save(ProjectMember.builder()
                .project(beans.getBean(ProjectRepository.class).findById(project.getId()).orElseThrow()).account(account).isActive(true).build()).getId());
    }

    private StandardWord word(long projectId, String name, String abbreviation) {
        return beans.getBean(StandardWordRepository.class).save(StandardWord.builder().projectId(projectId).commonStandardWordName(name)
                .commonStandardWordAbbreviation(abbreviation).isApproval(true).synonymList(new ArrayList<>()).restrictedWords(new ArrayList<>()).build());
    }

    private StandardTerm term(long projectId, String name, String abbreviation, StandardDomain domain, StandardWord... words) {
        StandardWordRepository wordRepository = beans.getBean(StandardWordRepository.class);
        return tx.execute(s -> {
            StandardTerm term = StandardTerm.builder().projectId(projectId).commonStandardTermName(name).commonStandardTermAbbreviation(abbreviation)
                    .standardDomain(domain).isApproval(true).synonymList(new ArrayList<>()).termWordMappings(new ArrayList<>()).build();
            for (int i = 0; i < words.length; i++) {
                StandardTermWordMapping mapping = new StandardTermWordMapping();
                mapping.setStandardTerm(term);
                mapping.setStandardWord(wordRepository.getReferenceById(words[i].getId()));
                mapping.setOrderIndex(i + 1);
                term.getTermWordMappings().add(mapping);
            }
            return beans.getBean(StandardTermRepository.class).save(term);
        });
    }

    private TableInfo table(long projectId, String name) {
        return tx.execute(s -> beans.getBean(TableInfoRepository.class).save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(true)
                .schema(beans.getBean(SchemaService.class).resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(long projectId, TableInfo table, String name, StandardTerm term) {
        return tx.execute(s -> beans.getBean(ColumnInfoRepository.class).save(ColumnInfo.builder().projectId(projectId).tableName(table.getTableName())
                .tableInfo(beans.getBean(TableInfoRepository.class).getReferenceById(table.getId())).columnName(name).dataType("VARCHAR").maxLength(10)
                .standardTerms(term).isApproval(true).build()));
    }
}
