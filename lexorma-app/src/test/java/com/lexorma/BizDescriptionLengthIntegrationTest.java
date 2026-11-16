package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
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
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.controller.BizDescriptionRestController;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.BizDescriptionService;
import com.lexorma.data.table.service.BizDescriptions;
import com.lexorma.data.table.service.TableDesignService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업무 설명 길이 상한 4,000자 (AI-1d 후속) : 모든 저장 경로가 같은 기준({@link BizDescriptions}) — 앞뒤 공백을 뺀 값의 String.length (UTF-16 단위, 화면 maxLength 와 같음).
 * 넘으면 저장 전에 거부하고 자르지 않는다. 예전에 들어간 4,000자 초과 값은 업무 설명을 바꾸지 않는 저장을 막지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BizDescriptionLengthIntegrationTest {

    private static final int MAX = BizDescriptions.MAX;
    private static final String KO = "업무 설명은 최대 4,000자까지 입력할 수 있습니다.";
    private static final String EN = "Business description must not exceed 4,000 characters.";

    @Autowired ColumnManageService columnManage;
    @Autowired TableDesignService tableDesign;
    @Autowired BizDescriptionService bizDescriptions;
    @Autowired BizDescriptionRestController bizDescriptionApi;
    @Autowired com.lexorma.data.ai.dictionary.service.AiDescriptionReviewService reviews;
    @Autowired com.lexorma.common.exception.GlobalExceptionHandler exceptionHandler;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired StandardWordRepository wordRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private Account owner;
    private Account viewer;
    private Project project;
    private TableInfo approved;
    private TableInfo draft;
    private ColumnInfo account;
    private ColumnInfo locked;

    @BeforeEach
    void setUp() {
        owner = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        project = projectRepository.save(Project.builder().projectName("ai1dl-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        for (Account a : List.of(owner, viewer)) projectMemberRepository.save(ProjectMember.builder().project(project).account(a).isActive(true).build());
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            wordRepository.save(StandardWord.builder().projectId(p).commonStandardWordName("문의").commonStandardWordAbbreviation("INQ").isApproval(true).build());
            wordRepository.save(StandardWord.builder().projectId(p).commonStandardWordName("이력").commonStandardWordAbbreviation("HIST").isApproval(true).build());
            StandardDomain text = domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("명V100").commonStandardDomainCategory("명")
                    .dataType("VARCHAR").dataLength(100).isApproval(true).build());
            for (String abbreviation : List.of("LGN_ACNT", "EML_ADDR", "TELNO", "NICK_NM", "ADDR")) {
                termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("용어_" + abbreviation).commonStandardTermAbbreviation(abbreviation)
                        .standardDomain(text).isApproval(true).build());
            }
            approved = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_MBR").tableKorName("회원").tableDescription("회원 테이블")
                    .bizDescription("서비스에 가입한 회원").isApproval(true).isMasterTable(false).build());
            draft = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_DRAFT").tableKorName("초안").tableDescription("초안 테이블")
                    .isApproval(false).isMasterTable(false).sortOrder(1).build());
            account = columnRepository.save(ColumnInfo.builder().projectId(p).tableInfo(approved).tableName("TB_MBR").columnName("LGN_ACNT").columnDesc("로그인 계정")
                    .dataType("VARCHAR").maxLength(100).sortOrder(1).bizDescription("사용자의 로그인 계정").isPk(false).isNullable(true).isApproval(false)
                    .standardTerms(term("LGN_ACNT")).build());
            locked = columnRepository.save(ColumnInfo.builder().projectId(p).tableInfo(approved).tableName("TB_MBR").columnName("TELNO").columnDesc("전화번호")
                    .dataType("VARCHAR").maxLength(100).sortOrder(2).bizDescription("승인된 컬럼 설명").isPk(false).isNullable(true).isApproval(true)
                    .standardTerms(term("TELNO")).build());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("오류 문구 : 전용 코드 BIZ_DESCRIPTION_TOO_LONG — 한국어·영어 문구, 단건 응답 본문, 일괄 편집 행·엑셀 대상 표시, AI 제안 승인. 일반 INVALID_PHYSICAL_MODEL 문구는 그대로")
    void errorMessages() throws IOException {
        AccountDto user = login();
        String over = "a".repeat(MAX + 1);
        // 단건 API 응답 본문 (공통 예외 처리) : 한국어
        CustomException direct = catchCustom(() -> bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), account.getId(), over, "사용자의 로그인 계정")));
        var body = exceptionHandler.handleCustomException(direct);
        assertThat(body.getStatusCode().value()).isEqualTo(400);
        assertThat(body.getBody()).extracting("code", "message").containsExactly("BIZ_DESCRIPTION_TOO_LONG", KO);
        // AI 제안 승인 (고친 문구) : 같은 코드·문구, 제안 조회 전에 거부
        // 승인자 = 이 프로젝트의 관리자(owner)
        CustomException ai = catchCustom(() -> reviews.approve(com.lexorma.core.execution.ExecutionContext.user(login()), Long.MAX_VALUE,
                new com.lexorma.data.ai.dictionary.domain.dto.AiDictionaryDto.ReviewRequest(0L, over, null)));
        assertThat(ai.getErrorCode()).isEqualTo(ErrorCode.BIZ_DESCRIPTION_TOO_LONG);
        assertThat(ai.getCustomMessage()).isEqualTo(KO);

        // 영어
        english();
        assertThat(catchCustom(() -> bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), account.getId(), over, "사용자의 로그인 계정")))
                .getCustomMessage()).isEqualTo(EN);
        assertThat(columnManage.saveColumnsBulk(user, bulk(bulkRow(column("LGN_ACNT"), over, "사용자의 로그인 계정", "로그인 계정"))).errors())
                .singleElement().satisfies(e -> assertThat(e.message()).isEqualTo("LGN_ACNT : " + EN));
        assertThat(catchCustom(() -> columnManage.uploadColumnExcelFile(xlsx(columnRow("ADDR", over)), user)).getCustomMessage()).isEqualTo(EN + " \n[TB_MBR.ADDR]");
        assertThat(catchCustom(() -> tableDesign.uploadTableExcelFile(xlsx(new String[]{"TB_LONG", "x", "N", "1", over}), user)).getCustomMessage())
                .isEqualTo(EN + " \n[TB_LONG]");
        // 다른 INVALID_PHYSICAL_MODEL 문구는 바뀌지 않는다
        assertThat(ErrorCode.INVALID_PHYSICAL_MODEL.getMessage()).isEqualTo("The relation, index or constraint definition is invalid.");
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        assertThat(ErrorCode.INVALID_PHYSICAL_MODEL.getMessage()).isEqualTo("관계·인덱스·제약조건 정의가 올바르지 않습니다.");
        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo("사용자의 로그인 계정");
    }

    private static CustomException catchCustom(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).isInstanceOf(CustomException.class);
        return (CustomException) thrown;
    }

    private static void english() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Accept-Language", "en");
        request.addPreferredLocale(java.util.Locale.ENGLISH);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
    }

    // ------------------------------------------------------------------ 경계값 · 문자 종류

    @Test
    @DisplayName("길이 기준 : 앞뒤 공백을 뺀 String.length — 한글·영문 3,999·4,000 허용, 4,001 거부, 이모지는 2, 줄바꿈은 1, null·빈 값·공백만은 길이 검사 대상이 아니다")
    void lengthRule() {
        assertThat(BizDescriptions.tooLong("가".repeat(MAX - 1), null)).isFalse();
        assertThat(BizDescriptions.tooLong("가".repeat(MAX), null)).isFalse();
        assertThat(BizDescriptions.tooLong("가".repeat(MAX + 1), null)).isTrue();
        assertThat(BizDescriptions.tooLong("a".repeat(MAX + 1), null)).isTrue();
        assertThat(BizDescriptions.tooLong("😀".repeat(MAX / 2), null)).as("이모지 2,000개 = 4,000").isFalse();
        assertThat(BizDescriptions.tooLong("😀".repeat(MAX / 2) + "a", null)).isTrue();
        assertThat(BizDescriptions.tooLong("가".repeat(1999) + "\n" + "가".repeat(2000), null)).as("가운데 줄바꿈은 1").isFalse();
        assertThat(BizDescriptions.tooLong("가".repeat(2000) + "\n" + "가".repeat(2000), null)).isTrue();
        assertThat(BizDescriptions.tooLong("  \n" + "가".repeat(MAX) + "\n  ", null)).as("앞뒤 공백·줄바꿈은 세지 않는다").isFalse();
        assertThat(BizDescriptions.tooLong(null, null)).isFalse();
        assertThat(BizDescriptions.tooLong("", "x".repeat(MAX + 10))).isFalse();
        assertThat(BizDescriptions.tooLong("   ", null)).isFalse();
        // 예전에 들어간 긴 값과 같은 값은 거부하지 않는다 (앞뒤 공백만 다른 것도 같은 값)
        String old = "x".repeat(MAX + 10);
        assertThat(BizDescriptions.tooLong(" " + old + " ", old)).isFalse();
        assertThat(BizDescriptions.tooLong(old + "y", old)).isTrue();
    }

    @Test
    @DisplayName("직접 편집 · 테이블 등록·수정 · 컬럼 단건 등록 : 4,000 저장, 4,001 은 BIZ_DESCRIPTION_TOO_LONG 으로 거부되고 아무것도 바뀌지 않는다")
    void singlePaths() {
        AccountDto user = login();
        String ok = "가".repeat(MAX - 2) + "😀";
        String over = "가".repeat(MAX - 1) + "😀";

        // 직접 편집 (컬럼·테이블)
        bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), account.getId(), ok, "사용자의 로그인 계정"));
        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo(ok);
        rejected(() -> bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), null, over, "서비스에 가입한 회원")));
        assertThat(table("TB_MBR").getBizDescription()).isEqualTo("서비스에 가입한 회원");

        // 테이블 등록
        rejected(() -> tableDesign.saveTable(newTable("문의_이력", over), user));
        assertThat(tableRepository.findAllByProjectIdAndTableNameIn(project.getId(), Set.of("INQ_HIST"))).isEmpty();
        tableDesign.saveTable(newTable("문의_이력", "가".repeat(MAX)), user);
        assertThat(table("INQ_HIST").getBizDescription()).hasSize(MAX);

        // 테이블 수정 : 초과 거부 → 설명·이름 등 다른 칸도 그대로 (트랜잭션 전체 롤백)
        rejected(() -> tableDesign.updateTable(update(draft, "초안 (수정)", over, ""), user));
        assertThat(table("TB_DRAFT")).satisfies(t -> {
            assertThat(t.getBizDescription()).isNull();
            assertThat(t.getTableDescription()).isEqualTo("초안 테이블");
        });
        tableDesign.updateTable(update(draft, "초안 (수정)", ok, ""), user);
        assertThat(table("TB_DRAFT").getBizDescription()).isEqualTo(ok);

        // 컬럼 단건 등록
        rejected(() -> columnManage.insertColumn(user, single("EML_ADDR", over)));
        assertThat(columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), approved.getId(), "EML_ADDR")).isEmpty();
        columnManage.insertColumn(user, single("EML_ADDR", "  " + "a".repeat(MAX) + "\n"));
        assertThat(column("EML_ADDR").getBizDescription()).hasSize(MAX);
    }

    @Test
    @DisplayName("컬럼 일괄 편집 : 초과 행은 행 오류(컬럼명 포함)로 돌려주고 요청 전체를 저장하지 않는다 — 같은 요청의 다른 행·다른 칸도 그대로. 4,000 은 저장")
    void bulkEdit() {
        AccountDto user = login();
        String over = "a".repeat(MAX + 1);
        ColumnInfo current = column("LGN_ACNT");
        ColumnBulkSaveDto.Row existing = bulkRow(current, over, "사용자의 로그인 계정", "로그인 계정 (수정)");
        ColumnBulkSaveDto.Row created = ColumnBulkSaveDto.Row.builder().key("new-1").termId(term("NICK_NM").getId()).columnDesc("별명").bizDescription("별명 설명")
                .dataType("VARCHAR").maxLength(50).isPk("N").isNullable("Y").isRequired("N").isUnique("N").isIndex("N").isMasterData("N").isSensitive("N").isEncrypted("N").build();
        ColumnBulkSaveDto.Result result = columnManage.saveColumnsBulk(user, bulk(existing, created, bulkRow(column("TELNO"), "x", "승인된 컬럼 설명", "전화번호")));
        assertThat(result.result()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.key()).isEqualTo(String.valueOf(current.getId()));
            assertThat(e.message()).isEqualTo("LGN_ACNT : " + KO);
        });
        assertThat(column("LGN_ACNT")).satisfies(c -> {
            assertThat(c.getBizDescription()).isEqualTo("사용자의 로그인 계정");
            assertThat(c.getColumnDesc()).isEqualTo("로그인 계정");
        });
        assertThat(columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), approved.getId(), "NICK_NM")).isEmpty();

        // 신규 행 초과
        created.setBizDescription(over);
        existing.setBizDescription("a".repeat(MAX));
        result = columnManage.saveColumnsBulk(user, bulk(existing, created));
        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.key()).isEqualTo("new-1"));

        // 4,000 저장 + 다른 칸 함께. 승인된 컬럼은 예전처럼 요청 값과 무관하게 그대로 (승인 정책)
        created.setBizDescription("가".repeat(MAX));
        result = columnManage.saveColumnsBulk(user, bulk(existing, created, bulkRow(column("TELNO"), "x".repeat(MAX + 1), "승인된 컬럼 설명", "전화번호")));
        assertThat(result.errors()).isNullOrEmpty();
        assertThat(column("LGN_ACNT")).satisfies(c -> {
            assertThat(c.getBizDescription()).hasSize(MAX);
            assertThat(c.getColumnDesc()).isEqualTo("로그인 계정 (수정)");
        });
        assertThat(column("NICK_NM").getBizDescription()).hasSize(MAX);
        assertThat(column("TELNO").getBizDescription()).isEqualTo("승인된 컬럼 설명");
    }

    @Test
    @DisplayName("엑셀 업로드 : 초과 셀이 있으면 오류(테이블·컬럼명 포함)로 업로드 전체를 되돌린다 — 앞 행도 저장되지 않는다. 4,000 은 저장")
    void excelUpload() throws IOException {
        AccountDto user = login();
        String over = "가".repeat(MAX + 1);
        assertThatThrownBy(() -> columnManage.uploadColumnExcelFile(xlsx(columnRow("EML_ADDR", "앞 행"), columnRow("ADDR", over)), user))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZ_DESCRIPTION_TOO_LONG);
                    assertThat(((CustomException) e).getCustomMessage()).isEqualTo(KO + " \n[TB_MBR.ADDR]");
                });
        assertThat(columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), approved.getId(), "EML_ADDR")).isEmpty();
        columnManage.uploadColumnExcelFile(xlsx(columnRow("EML_ADDR", "가".repeat(MAX)), columnRow("ADDR", "😀".repeat(MAX / 2))), user);
        assertThat(column("EML_ADDR").getBizDescription()).hasSize(MAX);
        assertThat(column("ADDR").getBizDescription()).hasSize(MAX);

        assertThatThrownBy(() -> tableDesign.uploadTableExcelFile(xlsx(new String[]{"TB_FIRST", "앞 행", "N", "1", "짧은 설명"},
                new String[]{"TB_LONG", "긴 설명", "N", "2", over}), user))
                .isInstanceOf(CustomException.class).satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZ_DESCRIPTION_TOO_LONG);
                    assertThat(((CustomException) e).getCustomMessage()).isEqualTo(KO + " \n[TB_LONG]");
                });
        assertThat(tableRepository.findAllByProjectIdAndTableNameIn(project.getId(), Set.of("TB_FIRST", "TB_LONG"))).isEmpty();
        // 건너뛰는 이미 있는 테이블 행은 보지 않는다 (업무 설명을 쓰지 않으므로)
        tableDesign.uploadTableExcelFile(xlsx(new String[]{"TB_MBR", "x", "N", "1", over}, new String[]{"TB_LONG", "긴 설명", "N", "2", "가".repeat(MAX)}), user);
        assertThat(table("TB_LONG").getBizDescription()).hasSize(MAX);
        assertThat(table("TB_MBR").getBizDescription()).isEqualTo("서비스에 가입한 회원");
    }

    @Test
    @DisplayName("예전에 들어간 4,000자 초과 값 : 자르지 않고, 업무 설명을 바꾸지 않는 저장(다른 칸 수정·같은 값)은 막지 않는다. 다른 초과 값으로 바꾸는 것은 거부, 줄이는 것은 허용")
    void legacyOverLimitData() {
        AccountDto user = login();
        String legacy = "옛".repeat(MAX + 500);
        jdbc.update("update TB_COL set TASK_EXPLN = ? where COL_SN = ?", legacy, account.getId());
        jdbc.update("update TB_TBL set TASK_EXPLN = ? where TBL_SN = ?", legacy, draft.getId());

        // 컬럼 일괄 편집 : 업무 설명 없이 다른 칸만 / 같은 값
        assertThat(columnManage.saveColumnsBulk(user, bulk(bulkRow(column("LGN_ACNT"), null, null, "로그인 계정 (수정)"))).errors()).isNullOrEmpty();
        assertThat(columnManage.saveColumnsBulk(user, bulk(bulkRow(column("LGN_ACNT"), " " + legacy + " ", legacy, "로그인 계정 (수정)"))).errors()).isNullOrEmpty();
        assertThat(column("LGN_ACNT")).satisfies(c -> {
            assertThat(c.getBizDescription()).isEqualTo(legacy);
            assertThat(c.getColumnDesc()).isEqualTo("로그인 계정 (수정)");
        });
        // 테이블 수정 : 업무 설명을 보내지 않음 → 그대로
        tableDesign.updateTable(update(draft, "초안 (수정)", null, null), user);
        assertThat(table("TB_DRAFT")).satisfies(t -> {
            assertThat(t.getBizDescription()).isEqualTo(legacy);
            assertThat(t.getTableDescription()).isEqualTo("초안 (수정)");
        });
        // 직접 편집 : 같은 값은 그대로, 더 긴 다른 값은 거부, 줄이면 저장
        bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), account.getId(), legacy, legacy));
        rejected(() -> bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), account.getId(), legacy + "추가", legacy)));
        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo(legacy);
        bizDescriptions.update(user, new BizDescriptionDto.Request(approved.getId(), account.getId(), legacy.substring(0, MAX), legacy));
        assertThat(column("LGN_ACNT").getBizDescription()).hasSize(MAX);
    }

    @Test
    @DisplayName("권한은 그대로 : VIEWER 는 길이와 무관하게 직접 편집할 수 없다")
    void viewerStillDenied() {
        AccountDto view = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> bizDescriptionApi.update(view, new BizDescriptionDto.Request(approved.getId(), account.getId(), "짧음", "사용자의 로그인 계정")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bizDescriptionApi.update(view, new BizDescriptionDto.Request(approved.getId(), account.getId(), "a".repeat(MAX + 1), "사용자의 로그인 계정")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo("사용자의 로그인 계정");
    }

    // ------------------------------------------------------------------ 준비

    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.BIZ_DESCRIPTION_TOO_LONG);
                    assertThat(((CustomException) e).getCustomMessage()).as("전용 문구만 (일반 물리 모델 문구 없음)").isEqualTo(KO);
                });
    }

    private TableInfoDto newTable(String korName, String biz) {
        return TableInfoDto.builder().id(0L).tableKorName(korName).tableDescription("문의 이력").bizDescription(biz).isMasterTable("N").sortOrder(1).build();
    }

    private TableInfoDto update(TableInfo t, String description, String biz, String base) {
        return TableInfoDto.builder().id(t.getId()).tableKorName("").tableName(t.getTableName()).tableDescription(description)
                .bizDescription(biz).bizDescriptionBase(base).isMasterTable("N").sortOrder(1).build();
    }

    private ColumnInfoDto single(String abbreviation, String biz) {
        return ColumnInfoDto.builder().id(0L).tableInfoId(approved.getId()).termId(term(abbreviation).getId()).tableName("TB_MBR").columnName(abbreviation).dataType("VARCHAR")
                .columnDesc("설명 " + abbreviation).maxLength(100).bizDescription(biz).isPk("N").isNullable("Y").isMasterData("N").isRequired("N").isSensitive("N")
                .isUnique("N").isIndex("N").isEncrypted("N").build();
    }

    private static ColumnBulkSaveDto.Row bulkRow(ColumnInfo c, String biz, String base, String columnDesc) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).columnDesc(columnDesc).bizDescription(biz).bizDescriptionBase(base)
                .dataType(c.getDataType()).maxLength(c.getMaxLength()).precision(c.getPrecision()).scale(c.getScale()).defaultValue(c.getDefaultValue())
                .refTableName(c.getRefTableName()).example(c.getExample()).isPk("N").isNullable("Y").isRequired("N").isUnique("N").isIndex("N").isMasterData("N")
                .isSensitive("N").isEncrypted("N").build();
    }

    private ColumnBulkSaveDto bulk(ColumnBulkSaveDto.Row... rows) {
        return ColumnBulkSaveDto.builder().tableInfoId(approved.getId()).rows(List.of(rows)).build();
    }

    /** 컬럼 업로드 21열 행 : A~T 고정, U 업무 설명 */
    private static String[] columnRow(String columnName, String biz) {
        return new String[]{"TB_MBR", columnName, "설명 " + columnName, "VARCHAR", "100", "0", "0", "Y", "", "1",
                "N", "", "N", "N", "N", "N", "N", "N", "", "", biz};
    }

    private static MockMultipartFile xlsx(String[]... rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("header");
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i + 1);
                for (int c = 0; c < rows[i].length; c++) row.createCell(c).setCellValue(rows[i][c]);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private StandardTerm term(String abbreviation) {
        return termRepository.findAllByProjectIdAndCommonStandardTermAbbreviationIn(project.getId(), Set.of(abbreviation)).get(0);
    }

    private ColumnInfo column(String name) {
        return columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), approved.getId(), name).get(0);
    }

    private TableInfo table(String name) {
        return tableRepository.findAllByProjectIdAndTableNameIn(project.getId(), Set.of(name)).get(0);
    }

    private AccountDto login() {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_USER");
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai1dl-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }
}
