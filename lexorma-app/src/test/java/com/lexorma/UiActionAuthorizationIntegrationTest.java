package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.login.controller.AuthRestController;
import com.lexorma.core.project.domain.dto.ProjectDto;
import com.lexorma.core.project.domain.dto.ProjectMemberDto;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.controller.ColumnManageRestController;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.controller.DataDictionaryRestController;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.operation.board.domain.dto.BoardDto;
import com.lexorma.operation.board.domain.entity.Board;
import com.lexorma.operation.notice.controller.NoticeRestController;
import com.lexorma.operation.notice.repository.BoardRepository;
import com.lexorma.system.project.controller.ProjectManageRestController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 권한별 UI 액션 점검 (H2 / PostgreSQL) : 화면이 버튼을 숨기는 것과 별개로 서버가 지키는 것.
 * ① 모든 API 가 역할을 선언하고, 변경 API 는 USER 이상이다 (조회 권한이 바꿀 수 있는 API 가 새로 생기지 않게 한다)
 * ② 프로젝트 관리·공지 변경은 그 프로젝트의 관리자만 (역할만으로 다른 프로젝트를 건드리지 못한다)
 * ③ /api/auth/me 의 approvalAvailable 은 변경 API 를 쓸 수 있는 역할일 때만 true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class UiActionAuthorizationIntegrationTest {

    /** 역할 선언 없이 열어 둔 API (로그인 상태 조회) */
    private static final Set<String> NO_ROLE = Set.of("GET /api/auth/me", "GET /api/auth/csrf");

    /**
     * USER 미만이 호출하는 변경 API : 로그인 전 절차, 자기 계정, 사용자별 ERD 배치(모델을 바꾸지 않는다),
     * 복구 세션 전용 API (RECOVERY 는 역할 계층 밖 : 시스템 ADMIN 의 복구 로그인만 받는다 — ProjectRecoveryLoginTest)
     */
    private static final Set<String> BELOW_USER_MUTATIONS = Set.of(
            "POST /api/auth/signup", "POST /api/login/projects", "POST /api/login/password",
            "POST /api/updatePwd", "POST /api/updateName",
            "PUT /api/erd/layout", "PUT /api/logical/model/{modelId}/erd/layout",
            "POST /api/recovery/projects/{projectId}/activate",
            // 원본 가져오기 계획 (M-4e) : 읽기 전용 미리보기 (고른 항목을 본문으로 받아 POST, 아무것도 저장하지 않는다). 확정(apply)은 USER + 프로젝트 관리자
            "POST /api/sourceImport/plan");

    private static final Pattern HAS_ROLE = Pattern.compile("^hasRole\\('([A-Z_]+)'\\)$");

    @Autowired ApplicationContext context;
    @Autowired RoleHierarchy roleHierarchy;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired ProjectManageRestController projects;
    @Autowired NoticeRestController notices;
    @Autowired AuthRestController auth;
    @Autowired DataDictionaryRestController dictionary;
    @Autowired ColumnManageRestController columns;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired ColumnInfoRepository columnInfoRepository;
    @Autowired SchemaService schemaService;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ ① 역할 선언

    @Test
    @DisplayName("모든 API 는 역할을 선언하고, 변경 API(POST·PUT·PATCH·DELETE)는 예외 목록 외에는 USER 이상이다")
    void everyApiDeclaresRole() {
        List<String> undeclared = new ArrayList<>();
        List<String> openMutations = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();

        for (Object bean : context.getBeansWithAnnotation(RestController.class).values()) {
            Class<?> type = AopUtils.getTargetClass(bean);
            if (!type.getName().startsWith("com.lexorma")) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];

            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                PreAuthorize rule = method.isAnnotationPresent(PreAuthorize.class) ? method.getAnnotation(PreAuthorize.class) : type.getAnnotation(PreAuthorize.class);

                for (RequestMethod http : mapping.method()) {
                    for (String path : mapping.path().length == 0 ? new String[]{""} : mapping.path()) {
                        String api = http + " " + prefix + path;
                        if (!api.contains(" /api/")) continue; // 화면(index.html) 전달은 데이터를 주지 않는다
                        seen.add(api);
                        if (rule == null) {
                            if (!NO_ROLE.contains(api)) undeclared.add(api);
                            continue;
                        }
                        Matcher role = HAS_ROLE.matcher(rule.value());
                        assertThat(role.matches()).as("%s 의 권한 표현식 %s", api, rule.value()).isTrue();
                        if (http != RequestMethod.GET && !atLeastUser(role.group(1)) && !BELOW_USER_MUTATIONS.contains(api)) {
                            openMutations.add(api + " (" + role.group(1) + ")");
                        }
                    }
                }
            }
        }

        assertThat(undeclared).as("@PreAuthorize 가 없는 API (로그인한 모든 사용자에게 열린다)").isEmpty();
        assertThat(openMutations).as("USER 미만이 호출할 수 있는 변경 API").isEmpty();
        // 예외 목록이 실제 API 와 어긋나면(경로 변경 등) 알 수 있게 한다
        assertThat(seen).containsAll(NO_ROLE).containsAll(BELOW_USER_MUTATIONS);
    }

    private boolean atLeastUser(String role) {
        return roleHierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_" + role))).stream()
                .map(GrantedAuthority::getAuthority).anyMatch("ROLE_USER"::equals);
    }

    // ------------------------------------------------------------------ ② 프로젝트 관리자 범위

    @Test
    @DisplayName("프로젝트 관리 API 는 그 프로젝트의 관리자만 : DBA·ADMIN 역할이어도 다른 사람의 프로젝트를 조회·수정하거나 멤버를 바꿀 수 없다")
    void projectManageOnlyByManager() {
        Account admin = admin();
        Project project = project("auth-project", admin);
        ProjectMember member = projectMemberRepository.findAllByProject_Id(project.getId()).getFirst();
        List<ProjectMemberDto> members = List.of(ProjectMemberDto.builder().id(member.getId()).accountId(admin.getId()).build());
        ProjectDto takeover = ProjectDto.builder().id(project.getId()).projectName("TAKEN").projectManagerId(admin.getId())
                .isActive(false).isAutoActive(true).isDicAbbrUsed(false).dbType("POSTGRESQL").build();

        for (String role : List.of("ROLE_DBA", "ROLE_MANAGER", "ROLE_ADMIN")) {
            AccountDto outsider = login(project.getId(), "990001", role);
            denied(() -> projects.getProjectData(0, 10, null, null, null, project.getId(), outsider));
            denied(() -> projects.getEditProjectData(project.getId(), outsider));
            denied(() -> projects.updateProject(takeover, false, outsider));
            denied(() -> projects.selectProjectMember(project.getId(), outsider));
            denied(() -> projects.saveProjectMember(Map.of("accountId", String.valueOf(admin.getId())), project.getId(), outsider));
            denied(() -> projects.saveActiveProject(false, members, outsider));
            denied(() -> projects.deleteProjectMember(members, outsider));
        }
        Project kept = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(kept.getProjectName()).isEqualTo("auth-project");
        assertThat(kept.getIsActive()).isTrue();
        assertThat(projectMemberRepository.findById(member.getId()).orElseThrow().getIsActive()).isTrue();

        // 역할이 모자라면 관리자 본인이어도 호출할 수 없다 (기존 @PreAuthorize)
        AccountDto lowRole = login(project.getId(), String.valueOf(admin.getId()), "ROLE_USER");
        denied(() -> projects.getEditProjectData(project.getId(), lowRole));

        // 관리자 본인(DBA 이상)은 그대로 쓴다 (조회 쪽은 SmokeTest·ProjectAccessRevokeTest 가 HTTP 로 확인한다)
        AccountDto manager = login(project.getId(), String.valueOf(admin.getId()), "ROLE_DBA");
        projects.saveActiveProject(true, members, manager);
    }

    @Test
    @DisplayName("공지 등록·수정·삭제는 현재 프로젝트의 관리자만, 대상도 자신이 관리하는 프로젝트만. 다른 프로젝트의 공지는 ID 를 알아도 읽거나 바꿀 수 없다")
    void noticeOnlyByProjectManager() {
        Account admin = admin();
        Project mine = project("auth-notice", admin);
        Project foreign = projectRepository.save(Project.builder().projectName("auth-notice-foreign").isActive(true).account(admin).dbType(DbType.POSTGRESQL).build()); // 멤버가 아니라 '관리 중'이 아니다

        AccountDto manager = login(mine.getId(), String.valueOf(admin.getId()), "ROLE_MANAGER");
        notices.insertNotice(manager, BoardDto.builder().title("공지").content("내용").projectIds(List.of(mine.getId())).build());
        Board board = boardRepository.findAllByProjectIdAndDeleteYn(mine.getId(), 'N', Pageable.unpaged()).getContent().getFirst();

        // 관리하지 않는 프로젝트를 대상으로 넣을 수 없다
        denied(() -> notices.insertNotice(manager, BoardDto.builder().title("침범").content("내용").projectIds(List.of(mine.getId(), foreign.getId())).build()));
        assertThat(boardRepository.findAllByProjectIdAndDeleteYn(foreign.getId(), 'N', Pageable.unpaged())).isEmpty();

        // MANAGER·ADMIN 역할이어도 이 프로젝트의 관리자가 아니면 쓸 수 없다
        for (String role : List.of("ROLE_MANAGER", "ROLE_ADMIN")) {
            AccountDto outsider = login(mine.getId(), "990002", role);
            denied(() -> notices.insertNotice(outsider, BoardDto.builder().title("침범").content("내용").projectIds(List.of(mine.getId())).build()));
            denied(() -> notices.updateNotice(outsider, BoardDto.builder().id(board.getId()).title("변조").content("내용").projectIds(List.of(mine.getId())).build()));
            denied(() -> notices.deleteNotice(List.of(BoardDto.builder().id(board.getId()).build()), outsider));
        }
        // 프로젝트 관리자여도 역할이 MANAGER 미만이면 쓸 수 없다 (기존 @PreAuthorize) — 화면도 이때 버튼을 숨긴다
        AccountDto lowRole = login(mine.getId(), String.valueOf(admin.getId()), "ROLE_USER");
        denied(() -> notices.insertNotice(lowRole, BoardDto.builder().title("공지").content("내용").projectIds(List.of(mine.getId())).build()));

        // 다른 프로젝트에 로그인한 사용자 : 공지 ID 를 알아도 내용이 나오지 않고, 수정도 되지 않는다
        Project other = project("auth-notice-other", admin);
        AccountDto otherManager = login(other.getId(), String.valueOf(admin.getId()), "ROLE_MANAGER");
        denied(() -> notices.getNoticeDetail(board.getId(), otherManager));
        denied(() -> notices.getNoticeView(board.getId(), otherManager));
        denied(() -> notices.updateNotice(otherManager, BoardDto.builder().id(board.getId()).title("변조").content("내용").projectIds(List.of(other.getId())).build()));

        Board kept = boardRepository.findById(board.getId()).orElseThrow();
        assertThat(kept.getTitle()).isEqualTo("공지");
        assertThat(kept.getDeleteYn()).isEqualTo('N');

        // 관리자 본인은 읽고 수정한다
        AccountDto again = login(mine.getId(), String.valueOf(admin.getId()), "ROLE_MANAGER");
        assertThat(notices.getNoticeView(board.getId(), again).getBody().getTitle()).isEqualTo("공지");
        notices.updateNotice(again, BoardDto.builder().id(board.getId()).title("공지 수정").content("내용").projectIds(List.of(mine.getId())).build());
        assertThat(boardRepository.findById(board.getId()).orElseThrow().getTitle()).isEqualTo("공지 수정");
    }

    // ------------------------------------------------------------------ 데이터 사전 승인 · 컬럼 조회

    @Test
    @DisplayName("데이터 사전 승인·승인취소 : DBA 이상이면서 승인 권한(ADMIN·MANAGER·프로젝트 관리자)이 있어야 한다. DBA 역할만으로는 API 를 직접 불러도 승인되지 않는다")
    void dictionaryApprovalNeedsApprover() {
        Account admin = admin();
        Project project = project("auth-dictionary", admin);
        long projectId = project.getId();
        Long domainId = domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName("명V10").dataType("VARCHAR").dataLength(10).isApproval(false).build()).getId();
        Long wordId = wordRepository.save(StandardWord.builder().projectId(projectId).commonStandardWordName("회원").commonStandardWordAbbreviation("MBR").isApproval(false).build()).getId();
        Long termId = termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName("회원").commonStandardTermAbbreviation("MBR").isApproval(false).build()).getId();
        List<StandardDomainDto> domains = List.of(StandardDomainDto.builder().id(domainId).build());
        List<StandardWordDto> words = List.of(StandardWordDto.builder().id(wordId).build());
        List<StandardTermDto> terms = List.of(StandardTermDto.builder().id(termId).build());
        String adminId = String.valueOf(admin.getId());

        // 승인 권한이 없는 DBA·USER, 역할이 모자란 프로젝트 관리자(USER·VIEWER) : 승인도 승인취소도 할 수 없다
        approve(login(projectId, "990003", "ROLE_DBA"), domains, words, terms, true, ErrorCode.APPROVAL_PERMISSION_DENIED);
        approve(login(projectId, "990003", "ROLE_USER"), domains, words, terms, true, null);
        approve(login(projectId, adminId, "ROLE_USER"), domains, words, terms, true, null);
        approve(login(projectId, adminId, "ROLE_VIEWER"), domains, words, terms, true, null);
        assertThat(approvals(domainId, wordId, termId)).containsExactly(false, false, false);

        // 다른 프로젝트의 승인 권한자 : ID 를 알아도 바뀌지 않는다
        Project other = project("auth-dictionary-other", admin);
        approve(login(other.getId(), "990004", "ROLE_ADMIN"), domains, words, terms, true, ErrorCode.TABLE_IS_NOT_DEFINED /* 거절 없이 건너뛴다 */);
        assertThat(approvals(domainId, wordId, termId)).containsExactly(false, false, false);

        // 승인 권한자 : MANAGER·ADMIN 역할, DBA 인 프로젝트 관리자
        for (String[] who : new String[][]{{"990005", "ROLE_MANAGER"}, {"990006", "ROLE_ADMIN"}, {adminId, "ROLE_DBA"}}) {
            approve(login(projectId, who[0], who[1]), domains, words, terms, true, ErrorCode.TABLE_IS_NOT_DEFINED /* 통과 */);
            assertThat(approvals(domainId, wordId, termId)).as(who[1]).containsExactly(true, true, true);

            // 승인취소도 같은 기준 : 권한 없는 DBA 는 거절, 승인 권한자는 된다
            approve(login(projectId, "990003", "ROLE_DBA"), domains, words, terms, false, ErrorCode.APPROVAL_PERMISSION_DENIED);
            assertThat(approvals(domainId, wordId, termId)).containsExactly(true, true, true);
            approve(login(projectId, who[0], who[1]), domains, words, terms, false, ErrorCode.TABLE_IS_NOT_DEFINED /* 통과 */);
            assertThat(approvals(domainId, wordId, termId)).as(who[1]).containsExactly(false, false, false);
        }
    }

    @Test
    @DisplayName("컬럼 조회(목록·테이블의 컬럼)는 VIEWER 부터, 로그인한 프로젝트의 것만. 등록·삭제·일괄 저장·순서·승인은 그대로 USER 이상이다")
    void viewerReadsColumns() {
        Account admin = admin();
        Project project = project("auth-column", admin);
        long projectId = project.getId();
        TableInfo table = tx.execute(s -> tableInfoRepository.save(TableInfo.builder().projectId(projectId).tableName("TB_AUTH").isApproval(true)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
        ColumnInfo column = tx.execute(s -> columnInfoRepository.save(ColumnInfo.builder().projectId(projectId).tableName("TB_AUTH")
                .tableInfo(tableInfoRepository.getReferenceById(table.getId())).columnName("AUTH_ID").dataType("BIGINT").isPk(true).isApproval(true).build()));

        AccountDto viewer = login(projectId, "990007", "ROLE_VIEWER");
        assertThat(columnNames(tx.execute(s -> columns.selectTableColumn(viewer, table.getId()).getBody()))).containsExactly("AUTH_ID");
        assertThat(tx.<Map<String, Object>>execute(s -> columns.selectTableColumnData(viewer, 0, 10, null, null, null, table.getId()).getBody())).containsEntry("recordsTotal", 1L);

        // 변경 API 와 등록 폼용 조회는 그대로 USER 이상
        ColumnInfoDto dto = ColumnInfoDto.builder().id(column.getId()).build();
        denied(() -> columns.insertColumn(viewer, dto));
        denied(() -> columns.deleteColumn(viewer, List.of(dto)));
        denied(() -> columns.updateColumnApproval(viewer, "false", List.of(dto)));
        denied(() -> columns.updateSortOrder(List.of(dto), viewer));
        denied(() -> columns.saveColumnsBulk(new ColumnBulkSaveDto(), viewer));
        denied(() -> columns.selectColumnDetail(column.getId(), viewer)); // 이름과 달리 순서 변경 편집기용 조회다
        denied(() -> columns.selectTable(viewer, "TB"));
        denied(() -> columns.selectTermData(viewer, "termName", "회원"));
        assertThat(columnInfoRepository.findById(column.getId()).orElseThrow().getIsApproval()).isTrue();

        // 미승인 사용자는 조회도 할 수 없다
        AccountDto notApproved = login(projectId, "990008", "ROLE_NOT_APPROVE");
        denied(() -> columns.selectTableColumn(notApproved, table.getId()));
        denied(() -> columns.selectTableColumnData(notApproved, 0, 10, null, null, null, table.getId()));

        // 다른 프로젝트 : ID 를 알아도 나오지 않는다 (예전에는 NullPointerException)
        Project other = project("auth-column-other", admin);
        AccountDto outsider = login(other.getId(), "990009", "ROLE_VIEWER");
        rejected(() -> columns.selectTableColumn(outsider, table.getId()), ErrorCode.TABLE_IS_NOT_DEFINED);
        rejected(() -> columns.selectColumnDetail(column.getId(), login(other.getId(), "990009", "ROLE_USER")), ErrorCode.TABLE_IS_NOT_DEFINED);
        assertThat(tx.<Map<String, Object>>execute(s -> columns.selectTableColumnData(outsider, 0, 10, null, null, null, table.getId()).getBody())).containsEntry("recordsTotal", 0L);
    }

    // ------------------------------------------------------------------ ③ 화면에 내려주는 권한

    @Test
    @DisplayName("/api/auth/me : 프로젝트 관리자여도 역할이 VIEWER 이면 승인 버튼(approvalAvailable)과 변경 버튼(…-edit)이 꺼진다")
    @SuppressWarnings("unchecked")
    void meActionFlags() {
        Account admin = admin();
        Project project = project("auth-me", admin);

        Map<String, Object> viewer = auth.me(loginAsAdmin(project.getId(), admin, "ROLE_VIEWER"), new MockHttpServletRequest()).getBody();
        assertThat(viewer).containsEntry("authenticated", true).containsEntry("approvalAvailable", false);
        Map<String, Boolean> viewerMenus = (Map<String, Boolean>) viewer.get("menus");
        assertThat(viewerMenus).containsEntry("table-manage", true).containsEntry("standard-term", true);
        assertThat(viewerMenus).containsEntry("physical-model-edit", false).containsEntry("dictionary-edit", false)
                .containsEntry("dictionary-approve", false).containsEntry("logical-model-edit", false)
                .containsEntry("model-mapping-edit", false).containsEntry("notice-edit", false);

        // USER 인 프로젝트 관리자 : 변경·승인(테이블 등)은 되지만, 데이터 사전 승인(DBA)·공지 변경(MANAGER)은 역할이 모자란다
        Map<String, Object> user = auth.me(loginAsAdmin(project.getId(), admin, "ROLE_USER"), new MockHttpServletRequest()).getBody();
        assertThat(user).containsEntry("approvalAvailable", true);
        assertThat((Map<String, Boolean>) user.get("menus")).containsEntry("physical-model-edit", true).containsEntry("dictionary-edit", true)
                .containsEntry("dictionary-approve", false).containsEntry("notice-edit", false);

        Map<String, Object> manager = auth.me(loginAsAdmin(project.getId(), admin, "ROLE_MANAGER"), new MockHttpServletRequest()).getBody();
        assertThat(manager).containsEntry("approvalAvailable", true);
        assertThat((Map<String, Boolean>) manager.get("menus")).containsEntry("dictionary-approve", true).containsEntry("notice-edit", true);
    }

    // ------------------------------------------------------------------ 보조

    /** 세 종류의 승인 API 를 불러 모두 같은 결과인지 본다. expected 가 null 이면 역할 부족(403), TABLE_IS_NOT_DEFINED 는 "거절 없이 통과" 표시 */
    private void approve(AccountDto account, List<StandardDomainDto> domains, List<StandardWordDto> words, List<StandardTermDto> terms, boolean approval, ErrorCode expected) {
        List<Runnable> calls = List.of(
                () -> dictionary.approvalStandardDomains(domains, account, approval),
                () -> dictionary.approvalStandardWords(words, account, approval),
                () -> dictionary.approvalStandardTerms(terms, account, approval));
        for (Runnable call : calls) {
            if (expected == null) denied(call);
            else if (expected == ErrorCode.TABLE_IS_NOT_DEFINED) call.run();
            else rejected(call, expected);
        }
    }

    private List<Boolean> approvals(Long domainId, Long wordId, Long termId) {
        return List.of(domainRepository.findById(domainId).orElseThrow().getIsApproval(),
                wordRepository.findById(wordId).orElseThrow().getIsApproval(),
                termRepository.findById(termId).orElseThrow().getIsApproval());
    }

    @SuppressWarnings("unchecked")
    private static List<String> columnNames(Map<String, Object> body) {
        return ((List<ColumnInfoDto>) body.get("columnInfos")).stream().map(ColumnInfoDto::getColumnName).toList();
    }

    private static void rejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(expected);
    }

    private static void denied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
    }

    private Account admin() {
        return accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
    }

    /** owner 가 관리자이자 승인된 멤버인 프로젝트 ('관리 중인 프로젝트' 조건) */
    private Project project(String name, Account owner) {
        Project project = projectRepository.save(Project.builder().projectName(name).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        projectMemberRepository.save(ProjectMember.builder().project(project).account(owner).isActive(true).build());
        return project;
    }

    private AccountDto login(long projectId, String accountId, String role) {
        return SecurityTestSupport.loginAs(projectRepository, projectId, accountId, role);
    }

    /** 실제 계정(admin)의 아이디로 로그인한다 : /api/auth/me 는 아이디로 프로젝트 접근 권한을 다시 확인한다 */
    private AccountDto loginAsAdmin(long projectId, Account admin, String... roles) {
        AccountDto accountDto = AccountDto.builder().id(String.valueOf(admin.getId())).username(admin.getUsername()).projectId(projectId).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                accountDto, null, Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
        return accountDto;
    }
}
