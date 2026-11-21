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
import com.lexorma.data.column.controller.ColumnManageRestController;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.excel.service.ExcelService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.controller.TableDesignRestController;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.BizDescriptionService;
import com.lexorma.data.table.service.TableDesignService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업무 설명(TASK_EXPLN)의 엑셀 업로드·템플릿과 컬럼 등록 경로 (AI-1d).
 * <ul>
 *   <li>업로드는 등록(INSERT)만 한다 : 비거나 공백만 있는 셀·열이 없는 예전 파일은 NULL, 이미 있는 컬럼은 업로드 전체를 막고 이미 있는 테이블은 건너뛴다 — 어느 쪽도 기존 업무 설명을 바꾸지 않는다</li>
 *   <li>컬럼 템플릿 : S열(엑셀 컬럼 헤더 표시명)은 저장하지 않고 T열(데이터 예시)이 example, U열이 업무 설명. 테이블 템플릿 : E열이 업무 설명</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BizDescriptionExcelIntegrationTest {

    @Autowired ColumnManageService columnManage;
    @Autowired ColumnManageRestController columnApi;
    @Autowired TableDesignService tableDesign;
    @Autowired TableDesignRestController tableApi;
    @Autowired BizDescriptionService bizDescriptions;
    @Autowired ExcelService excel;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired StandardDomainRepository domainRepository;
    @Autowired StandardTermRepository termRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private Account owner;
    private Account viewer;
    private Project project;
    private TableInfo table;
    private ColumnInfo account;

    @BeforeEach
    void setUp() {
        owner = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        project = projectRepository.save(Project.builder().projectName("ai1d-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        for (Account a : List.of(owner, viewer)) projectMemberRepository.save(ProjectMember.builder().project(project).account(a).isActive(true).build());
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            SchemaInfo schema = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            StandardDomain text = domainRepository.save(StandardDomain.builder().projectId(p).commonStandardDomainName("명V100").commonStandardDomainCategory("명")
                    .dataType("VARCHAR").dataLength(100).isApproval(true).build());
            for (String abbreviation : List.of("LGN_ACNT", "EML_ADDR", "TELNO", "NICK_NM", "MEMO_CN", "ZIP_CD", "ADDR")) {
                termRepository.save(StandardTerm.builder().projectId(p).commonStandardTermName("용어_" + abbreviation).commonStandardTermAbbreviation(abbreviation)
                        .standardDomain(text).isApproval(true).build());
            }
            table = tableRepository.save(TableInfo.builder().projectId(p).schema(schema).tableName("TB_MBR").tableKorName("회원").tableDescription("회원 테이블")
                    .bizDescription("서비스에 가입한 회원").isApproval(true).isMasterTable(false).build());
            account = columnRepository.save(ColumnInfo.builder().projectId(p).tableInfo(table).tableName("TB_MBR").columnName("LGN_ACNT").columnDesc("로그인 계정")
                    .dataType("VARCHAR").maxLength(100).sortOrder(1).bizDescription("사용자의 로그인 계정").isPk(false).isNullable(true).isApproval(false)
                    .standardTerms(termRepository.findAllByProjectIdAndCommonStandardTermAbbreviationIn(p, Set.of("LGN_ACNT")).get(0)).build());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ 컬럼 업로드

    @Test
    @DisplayName("컬럼 업로드 형식 : 19열(S까지) · 20열(현재 템플릿, T 데이터 예시) · 21열(U 업무 설명) — S 는 저장하지 않고 T 가 example, U 가 업무 설명. 여러 줄 보존, 빈 셀·공백만은 NULL")
    void columnUploadFormats() throws IOException {
        AccountDto user = login();
        // 19열 : 예전 업로드가 읽던 범위 (S 를 example 로 읽었다) → 이제 S 는 저장하지 않는다, 업무 설명 열 없음
        columnManage.uploadColumnExcelFile(xlsx(columnRow("EML_ADDR", "헤더 표시명")), user);
        // 20열 : 지금까지 배포한 템플릿 (S 헤더 표시명, T 데이터 예시)
        columnManage.uploadColumnExcelFile(xlsx(columnRow("TELNO", "전화번호", "010-0000-0000")), user);
        // 21열 : 업무 설명 — 여러 줄, 앞뒤 공백, 공백만, 빈 셀
        columnManage.uploadColumnExcelFile(xlsx(
                columnRow("NICK_NM", "별명", "홍길동", "  화면에 보이는 이름\n로그인에는 쓰지 않는다  "),
                columnRow("MEMO_CN", "", "", "   "),
                columnRow("ZIP_CD", "", "12345", "")), user);

        assertThat(column("EML_ADDR")).satisfies(c -> {
            assertThat(c.getExample()).as("S열은 저장하지 않는다").isNullOrEmpty();
            assertThat(c.getBizDescription()).isNull();
        });
        assertThat(column("TELNO")).satisfies(c -> {
            assertThat(c.getExample()).as("T열 데이터 예시").isEqualTo("010-0000-0000");
            assertThat(c.getBizDescription()).isNull();
        });
        assertThat(column("NICK_NM").getBizDescription()).as("여러 줄 보존, 앞뒤 공백만 뺀다").isEqualTo("화면에 보이는 이름\n로그인에는 쓰지 않는다");
        assertThat(column("NICK_NM").getExample()).isEqualTo("홍길동");
        assertThat(column("MEMO_CN").getBizDescription()).as("공백만 = 빈 셀").isNull();
        assertThat(column("ZIP_CD").getBizDescription()).isNull();
        // 업무 설명은 컬럼 설명(DDL COMMENT)과 따로 저장된다
        assertThat(column("NICK_NM").getColumnDesc()).isEqualTo("설명 NICK_NM");
        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo("사용자의 로그인 계정");
    }

    @Test
    @DisplayName("이미 있는 컬럼이 든 업로드 : 업무 설명이 있든 비었든 예전처럼 전체 취소, 기존 업무 설명·같은 파일의 새 컬럼 모두 그대로")
    void existingColumnUploadIsRejected() throws IOException {
        AccountDto user = login();
        for (String biz : List.of("서비스에 로그인하는 사용자 계정", "")) {
            assertThatThrownBy(() -> columnManage.uploadColumnExcelFile(xlsx(columnRow("ADDR", "", "", "새 컬럼"), columnRow("LGN_ACNT", "", "", biz)), user))
                    .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.SAVED_DATA_EXISTS);
            assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo("사용자의 로그인 계정");
            assertThat(columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), table.getId(), "ADDR")).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 테이블 업로드

    @Test
    @DisplayName("테이블 업로드 : 4열(예전) → NULL, 5열 E 업무 설명(여러 줄) 저장, 공백만 → NULL, 이미 있는 테이블은 건너뛰어 업무 설명·설명 그대로")
    void tableUpload() throws IOException {
        AccountDto user = login();
        tableDesign.uploadTableExcelFile(xlsx(new String[]{"TB_OLD", "예전 형식", "N", "1"}), user);
        tableDesign.uploadTableExcelFile(xlsx(
                new String[]{"TB_NEW", "새 형식", "N", "2", "회원이 남긴 문의\n답변 전까지 보관"},
                new String[]{"TB_BLANK", "공백만", "N", "3", "  "},
                new String[]{"TB_MBR", "덮어쓰기 시도", "Y", "9", "덮어쓰기 시도"}), user);

        assertThat(table("TB_OLD").getBizDescription()).isNull();
        assertThat(table("TB_NEW")).satisfies(t -> {
            assertThat(t.getBizDescription()).isEqualTo("회원이 남긴 문의\n답변 전까지 보관");
            assertThat(t.getTableDescription()).as("업무 설명은 테이블 설명과 따로").isEqualTo("새 형식");
        });
        assertThat(table("TB_BLANK").getBizDescription()).isNull();
        assertThat(table("TB_MBR")).satisfies(t -> {
            assertThat(t.getBizDescription()).isEqualTo("서비스에 가입한 회원");
            assertThat(t.getTableDescription()).isEqualTo("회원 테이블");
        });
    }

    // ------------------------------------------------------------------ 템플릿

    @ParameterizedTest
    @ValueSource(strings = {"ko", "en"})
    @DisplayName("내려받은 템플릿(한국어·영어)의 예시 행을 그대로 올리면 : 컬럼 T열 → example, U열 → 업무 설명, 테이블 E열 → 업무 설명")
    void templateRoundTrip(String language) throws IOException {
        AccountDto user = login();
        request(language);
        boolean english = language.equals("en");

        byte[] columnTemplate = template("columnTemplate");
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(columnTemplate))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getLastCellNum()).as("A~U 21열").isEqualTo((short) 21);
            assertThat(header.getCell(19).getStringCellValue()).isEqualTo(english ? "Sample data" : "데이터 예시");
            assertThat(header.getCell(20).getStringCellValue()).isEqualTo(english ? "Business description" : "업무 설명");
        }
        columnManage.uploadColumnExcelFile(file(fill(columnTemplate, "TB_MBR", "EML_ADDR")), user);
        assertThat(column("EML_ADDR")).satisfies(c -> {
            assertThat(c.getExample()).isEqualTo("test@test.com");
            assertThat(c.getBizDescription()).isEqualTo(english ? "Email address the user signs in with and receives notifications at."
                    : "사용자가 로그인하고 알림을 받는 이메일 주소입니다.");
        });

        byte[] tableTemplate = template("tableTemplate");
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(tableTemplate))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(4).getStringCellValue()).isEqualTo(english ? "Business description" : "업무 설명");
        }
        tableDesign.uploadTableExcelFile(file(fill(tableTemplate, "TB_TPL_" + language.toUpperCase())), user);
        assertThat(table("TB_TPL_" + language.toUpperCase()).getBizDescription())
                .isEqualTo(english ? "Business description of the example table." : "예제 테이블의 업무 설명입니다.");
    }

    // ------------------------------------------------------------------ 화면 등록 경로 · 권한 · DDL · 동시 편집

    @Test
    @DisplayName("컬럼 등록 화면(단건) · 일괄 편집 : 등록 시 저장·생략은 NULL, 같은 값은 변경 없음(이력 없음), 업무 설명 없는 수정은 그대로, 바꾸면 반영")
    void registrationPaths() {
        AccountDto user = login();
        columnManage.insertColumn(user, single("EML_ADDR", "  알림을 받는 주소\n둘째 줄 "));
        columnManage.insertColumn(user, single("TELNO", null));
        assertThat(column("EML_ADDR").getBizDescription()).isEqualTo("알림을 받는 주소\n둘째 줄");
        assertThat(column("TELNO").getBizDescription()).isNull();

        Long id = column("LGN_ACNT").getId();
        // 업무 설명 없이 다른 칸만 수정 : 업무 설명 그대로
        columnManage.saveColumnsBulk(user, bulk(column("LGN_ACNT"), null, null, "로그인 계정 (수정)"));
        assertThat(column("LGN_ACNT")).satisfies(c -> {
            assertThat(c.getColumnDesc()).isEqualTo("로그인 계정 (수정)");
            assertThat(c.getBizDescription()).isEqualTo("사용자의 로그인 계정");
        });
        // 같은 값 (앞뒤 공백만 다름) : 바뀐 것 없음, 이력 없음
        int histories = historyCount(id);
        ColumnBulkSaveDto.Result same = columnManage.saveColumnsBulk(user, bulk(column("LGN_ACNT"), " 사용자의 로그인 계정 ", "사용자의 로그인 계정", "로그인 계정 (수정)"));
        assertThat(same.errors()).isNullOrEmpty();
        assertThat(same.updated()).isZero();
        assertThat(historyCount(id)).isEqualTo(histories);
        // 바꾸기
        columnManage.saveColumnsBulk(user, bulk(column("LGN_ACNT"), "서비스에 로그인하는 사용자 계정", "사용자의 로그인 계정", "로그인 계정 (수정)"));
        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo("서비스에 로그인하는 사용자 계정");
    }

    @Test
    @DisplayName("권한 : VIEWER 는 컬럼·테이블 엑셀 업로드와 컬럼 등록·일괄 편집을 할 수 없다 (API)")
    void viewerCannotWrite() throws IOException {
        AccountDto view = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        MockMultipartFile columns = xlsx(columnRow("EML_ADDR", "", "", "VIEWER 가 쓴 설명"));
        MockMultipartFile tables = xlsx(new String[]{"TB_VIEWER", "x", "N", "1", "VIEWER 가 쓴 설명"});
        assertThatThrownBy(() -> columnApi.uploadTableExcelFile(columns, view)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tableApi.uploadTableExcelFile(tables, view)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> columnApi.insertColumn(view, single("EML_ADDR", "x"))).isInstanceOf(AccessDeniedException.class);
        assertThat(columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), table.getId(), "EML_ADDR")).isEmpty();
        assertThat(tableRepository.findAllByProjectIdAndTableNameIn(project.getId(), Set.of("TB_VIEWER"))).isEmpty();
    }

    @Test
    @DisplayName("DDL COMMENT 는 그대로 : 컬럼 설명·테이블 코멘트만 나가고 업로드한 업무 설명은 나가지 않는다 (DEC-03 미정)")
    void ddlCommentUnchanged() throws IOException {
        AccountDto user = login();
        columnManage.uploadColumnExcelFile(xlsx(columnRow("EML_ADDR", "", "", "업무설명은DDL에없다")), user);
        String ddl = tableDesign.generateTableDdl(table.getId(), user, null).ddl();
        assertThat(ddl).contains("설명 EML_ADDR").contains("로그인 계정").doesNotContain("업무설명은DDL에없다").doesNotContain("사용자의 로그인 계정").doesNotContain("서비스에 가입한 회원");
    }

    @Test
    @DisplayName("동시 편집 : 직접 편집이 커밋 전일 때 같은 테이블에 업로드(새 컬럼 + 이미 있는 테이블 행) — 업로드는 테이블 행을 기다렸다가(컬럼 등록의 공통 잠금) 이어서 반영되고, 커밋된 직접 편집 값이 남는다")
    void concurrentDirectEditAndUpload() throws Exception {
        CountDownLatch edited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> editor = CompletableFuture.runAsync(() -> {
            AccountDto u = login();
            tx.executeWithoutResult(s -> {
                // 테이블 먼저 : 테이블 refresh 는 컬럼 목록으로 이어져(cascade) 같은 트랜잭션의 아직 flush 하지 않은 컬럼 변경을 되돌린다 (API 는 요청마다 따로라 해당 없음)
                bizDescriptions.update(u, new BizDescriptionDto.Request(table.getId(), null, "직접 고친 테이블 설명", "서비스에 가입한 회원"));
                bizDescriptions.update(u, new BizDescriptionDto.Request(table.getId(), account.getId(), "직접 고친 설명", "사용자의 로그인 계정"));
                edited.countDown();
                await(release);
            });
        });
        assertThat(edited.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> upload = CompletableFuture.runAsync(() -> {
            AccountDto u = login();
            try {
                columnManage.uploadColumnExcelFile(xlsx(columnRow("ADDR", "", "", "업로드한 새 컬럼")), u);
                tableDesign.uploadTableExcelFile(xlsx(new String[]{"TB_MBR", "x", "N", "1", ""}, new String[]{"TB_CONC", "x", "N", "1", "업로드한 새 테이블"}), u);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        // M-7 물리명 일괄 적용 2차 후속 (2026-11-21) : 컬럼 업로드는 컬럼을 더할 테이블 행을 먼저 잠근다 — 직접 편집이 테이블 행을 쥔 동안에는 기다린다
        // (예전에는 PK 가 아닌 컬럼이면 기다리지 않았다). 직접 편집이 커밋되면 이어서 끝난다 (교착 없음, 잃는 값 없음)
        boolean waited;
        try {
            upload.get(500, TimeUnit.MILLISECONDS);
            waited = false;
        } catch (java.util.concurrent.TimeoutException e) {
            waited = true;
        } finally {
            release.countDown();
        }
        assertThat(waited).as("업로드는 직접 편집이 쥔 테이블 행을 기다린다").isTrue();
        editor.get(10, TimeUnit.SECONDS);
        upload.get(10, TimeUnit.SECONDS);

        assertThat(column("LGN_ACNT").getBizDescription()).isEqualTo("직접 고친 설명");
        assertThat(table("TB_MBR").getBizDescription()).isEqualTo("직접 고친 테이블 설명");
        assertThat(column("ADDR").getBizDescription()).isEqualTo("업로드한 새 컬럼");
        assertThat(table("TB_CONC").getBizDescription()).isEqualTo("업로드한 새 테이블");

        // 반대 순서 : 업로드 뒤 화면을 연 값(base)으로 직접 편집 — 충돌 없음
        bizDescriptions.update(login(), new BizDescriptionDto.Request(table.getId(), column("ADDR").getId(), "업로드 뒤 고친 설명", "업로드한 새 컬럼"));
        assertThat(column("ADDR").getBizDescription()).isEqualTo("업로드 뒤 고친 설명");
    }

    // ------------------------------------------------------------------ 준비

    /** 컬럼 업로드 행 : A~R 은 고정 값, S 헤더 표시명 · T 데이터 예시 · U 업무 설명은 준 만큼만 쓴다 (19·20·21열 파일) */
    private static String[] columnRow(String columnName, String... tail) {
        List<String> values = new ArrayList<>(List.of("TB_MBR", columnName, "설명 " + columnName, "VARCHAR", "100", "0", "0", "Y", "", "1",
                "N", "", "N", "N", "N", "N", "N", "N"));
        values.addAll(List.of(tail));
        return values.toArray(String[]::new);
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
            return file(out.toByteArray());
        }
    }

    /** 템플릿의 예시 행(2행)에 테이블명(과 컬럼명)만 바꿔 쓴다 */
    private static byte[] fill(byte[] template, String tableName, String... columnName) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row row = workbook.getSheetAt(0).getRow(1);
            row.getCell(0).setCellValue(tableName);
            if (columnName.length > 0) row.getCell(1).setCellValue(columnName[0]);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] template(String name) throws IOException {
        try (InputStream in = excel.getExcelFile(name, login()).getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "upload.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static void request(String language) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, language);
        request.addPreferredLocale(Locale.forLanguageTag(language));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ColumnInfoDto single(String abbreviation, String biz) {
        StandardTerm term = termRepository.findAllByProjectIdAndCommonStandardTermAbbreviationIn(project.getId(), Set.of(abbreviation)).get(0);
        return ColumnInfoDto.builder().id(0L).tableInfoId(table.getId()).termId(term.getId()).tableName("TB_MBR").columnName(abbreviation).dataType("VARCHAR")
                .columnDesc("설명 " + abbreviation).maxLength(100).bizDescription(biz).isPk("N").isNullable("Y").isMasterData("N").isRequired("N").isSensitive("N")
                .isUnique("N").isIndex("N").isEncrypted("N").build();
    }

    private ColumnBulkSaveDto bulk(ColumnInfo c, String biz, String base, String columnDesc) {
        ColumnBulkSaveDto.Row row = ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).columnDesc(columnDesc).bizDescription(biz).bizDescriptionBase(base)
                .dataType(c.getDataType()).maxLength(c.getMaxLength()).precision(c.getPrecision()).scale(c.getScale()).defaultValue(c.getDefaultValue())
                .refTableName(c.getRefTableName()).example(c.getExample()).isPk("N").isNullable("Y").isRequired("N").isUnique("N").isIndex("N").isMasterData("N")
                .isSensitive("N").isEncrypted("N").build();
        return ColumnBulkSaveDto.builder().tableInfoId(table.getId()).rows(List.of(row)).build();
    }

    private int historyCount(Long columnId) {
        return jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where TRGT_TYPE_CD = 'PHYSICAL_COLUMN' and TRGT_SN = ?", Integer.class, columnId);
    }

    private ColumnInfo column(String name) {
        return columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), table.getId(), name).get(0);
    }

    private TableInfo table(String name) {
        return tableRepository.findAllByProjectIdAndTableNameIn(project.getId(), Set.of(name)).get(0);
    }

    private AccountDto login() {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_USER");
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("ai1d-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
