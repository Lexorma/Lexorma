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
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.excel.controller.ExcelRestController;
import com.lexorma.data.excel.service.ExcelService;
import com.lexorma.data.excel.service.ModelExcelExportService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
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
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테이블·컬럼 등록 데이터 엑셀 다운로드 (M-11) : 지금 조회 조건 전체(페이지 무관), 승인·승인 대기 모두, 업로드 템플릿과 같은 열(테이블 A~E, 컬럼 A~U) + 뒤에 참고 열(조회 전용),
 * 한국어·영어 헤더, 값 그대로(줄바꿈·특수문자·수식처럼 보이는 문자열), 프로젝트 격리, USER 권한, 셀 한도 초과는 실패, DB 는 바꾸지 않음, 빈 프로젝트로 왕복.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelExcelExportIntegrationTest {

    private static final DataFormatter FORMAT = new DataFormatter();

    @Autowired ModelExcelExportService export;
    @Autowired ExcelRestController excelApi;
    @Autowired ExcelService excel;
    @Autowired TableDesignService tableDesign;
    @Autowired ColumnManageService columnManage;
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
    private Project other;
    private SchemaInfo main;
    private SchemaInfo archive;
    private TableInfo member;

    @BeforeEach
    void setUp() {
        owner = account("ROLE_USER");
        viewer = account("ROLE_VIEWER");
        project = project("m11-");
        other = project("m11o-");
        tx.executeWithoutResult(s -> {
            Long p = project.getId();
            main = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            archive = schemaRepository.save(SchemaInfo.builder().projectId(p).schemaName("ARCHIVE").isDefault(false).build());
            terms(p);
            member = tableRepository.save(TableInfo.builder().projectId(p).schema(main).tableName("TB_MBR").tableKorName("회원").tableDescription("회원 테이블")
                    .bizDescription("서비스에 가입한 회원\n탈퇴하면 지운다").sourceComment("members").isApproval(true).isMasterTable(true).sortOrder(1).build());
            TableInfo draft = tableRepository.save(TableInfo.builder().projectId(p).schema(main).tableName("TB_DRAFT").tableKorName("초안").tableDescription("초안 테이블")
                    .isApproval(false).isMasterTable(false).sortOrder(2).build());
            TableInfo history = tableRepository.save(TableInfo.builder().projectId(p).schema(main).tableName("TB_HIST").tableKorName("이력").tableDescription("이력")
                    .isApproval(true).isMasterTable(false).sortOrder(3).build());
            TableInfo archived = tableRepository.save(TableInfo.builder().projectId(p).schema(archive).tableName("TB_HIST").tableKorName("보관 이력").tableDescription("보관 이력")
                    .isApproval(false).isMasterTable(false).sortOrder(4).build());
            column(member, "LGN_ACNT", 1, c -> c.columnDesc("로그인 계정").dataType("VARCHAR").maxLength(100).precision(new BigDecimal("0")).scale(0).isPk(true).isNullable(false)
                    .isRequired(true).isUnique(true).isIndex(true).isMasterData(true).isSensitive(true).isEncrypted(true).defaultValue("'guest'")
                    .example("user@example.com").bizDescription("로그인에 쓰는 계정\n이메일 형식").isApproval(true));
            column(member, "EML_ADDR", 2, c -> c.columnDesc("이메일 \"주소\" & <태그>").dataType("VARCHAR").maxLength(200).precision(new BigDecimal("0")).scale(0)
                    .isNullable(true).example("=HYPERLINK(\"http://x\")").bizDescription(null).refTableName("TB_HIST").sourceComment("email").isApproval(false));
            column(member, "PNT_AMT", 3, c -> c.columnDesc("포인트 금액").dataType("NUMBER").maxLength(0).precision(new BigDecimal("12")).scale(2)
                    .isNullable(true).defaultValue("0").example("+1000").bizDescription("가".repeat(4000)).identity(null).isApproval(true));
            column(history, "HIST_SN", 1, c -> c.columnDesc("이력 번호").dataType("BIGINT").isPk(true).isNullable(false).identity(ColumnInfo.Identity.ALWAYS).isApproval(true));
            column(archived, "HIST_SN", 1, c -> c.columnDesc("보관 이력 번호").dataType("BIGINT").isPk(true).isNullable(false).isApproval(false));
            column(draft, "MEMO_CN", 1, c -> c.columnDesc("메모").dataType("VARCHAR").maxLength(4000).isNullable(true).bizDescription("  "));
            // 다른 프로젝트
            SchemaInfo otherSchema = schemaRepository.save(SchemaInfo.builder().projectId(other.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            TableInfo foreign = tableRepository.save(TableInfo.builder().projectId(other.getId()).schema(otherSchema).tableName("TB_MBR").tableKorName("다른")
                    .tableDescription("다른 프로젝트").isApproval(true).isMasterTable(false).build());
            columnRepository.save(ColumnInfo.builder().projectId(other.getId()).tableInfo(foreign).tableName("TB_MBR").columnName("OTHER_COL").dataType("VARCHAR")
                    .sortOrder(1).isApproval(true).build());
        });
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("테이블 : 템플릿 헤더 A~E 그대로 + 참고 열 F~I, 승인·승인 대기 모두, 스키마→테이블명 순, 여러 줄 업무 설명, 다른 프로젝트 제외")
    void exportTables() throws IOException {
        try (Workbook workbook = workbook(export.tables(login(), null, null, null))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(header(sheet)).containsExactlyElementsOf(concat(templateHeader("tableTemplate"),
                    List.of("스키마 (참고)", "테이블 코멘트 (참고)", "원본 COMMENT (참고)", "승인 여부 (참고)")));
            assertThat(rows(sheet)).containsExactly(
                    List.of("TB_HIST", "보관 이력", "N", "4", "", "ARCHIVE", "보관 이력", "", "N"),
                    List.of("TB_DRAFT", "초안 테이블", "N", "2", "", "DEFAULT", "초안", "", "N"),
                    List.of("TB_HIST", "이력", "N", "3", "", "DEFAULT", "이력", "", "Y"),
                    List.of("TB_MBR", "회원 테이블", "Y", "1", "서비스에 가입한 회원\n탈퇴하면 지운다", "DEFAULT", "회원", "members", "Y"));
        }
    }

    @Test
    @DisplayName("범위 = 화면의 조회 조건 전체 : 스키마·검색 열(테이블명·코멘트·설명)·컬럼의 테이블 ID, 페이지와 무관. 모르는 검색 열은 무시(목록과 같음)")
    void exportFollowsSearchConditions() throws IOException {
        AccountDto user = login();
        assertThat(firstColumn(export.tables(user, null, null, archive.getId()))).containsExactly("TB_HIST");
        assertThat(firstColumn(export.tables(user, "tableName", "HIST", null))).containsExactly("TB_HIST", "TB_HIST");
        assertThat(firstColumn(export.tables(user, "tableKorName", "회원", main.getId()))).containsExactly("TB_MBR");
        assertThat(firstColumn(export.tables(user, "unknown", "x", null))).hasSize(4);
        assertThat(firstColumn(export.columns(user, "columnName", "addr", null))).containsExactly("TB_MBR");
        assertThat(firstColumn(export.columns(user, null, null, member.getId()))).hasSize(3).containsOnly("TB_MBR");
        // 다른 프로젝트 테이블 ID → 없음
        Long foreignId = tableRepository.findAllByProjectIdAndTableNameIn(other.getId(), Set.of("TB_MBR")).get(0).getId();
        assertThat(firstColumn(export.columns(user, null, null, foreignId))).isEmpty();
        // 선택 테이블 Export (DEC-22) : 고른 테이블·그 컬럼만, 다른 프로젝트 ID 는 섞여도 나오지 않는다, 조회 조건과 함께 걸린다
        assertThat(firstColumn(export.tables(user, null, null, null, List.of(member.getId(), foreignId)))).containsExactly("TB_MBR");
        assertThat(firstColumn(export.columns(user, null, null, null, List.of(member.getId(), foreignId)))).hasSize(3).containsOnly("TB_MBR");
        assertThat(firstColumn(export.tables(user, null, null, archive.getId(), List.of(member.getId())))).as("스키마 조건과 함께").isEmpty();
        assertThatThrownBy(() -> export.tables(user, null, null, null, java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList()))
                .isInstanceOf(com.lexorma.common.exception.CustomException.class);
    }

    @Test
    @DisplayName("컬럼 : 템플릿 헤더 A~U 그대로(S 헤더 표시명은 비움, T 데이터 예시, U 업무 설명) + 참고 열 V~Y, 여러 스키마의 같은 테이블명은 스키마명.테이블명, 값은 그대로")
    void exportColumns() throws IOException {
        try (Workbook workbook = workbook(export.columns(login(), null, null, null))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> header = header(sheet);
            assertThat(header).containsExactlyElementsOf(concat(templateHeader("columnTemplate"),
                    List.of("스키마 (참고)", "IDENTITY (참고)", "원본 COMMENT (참고)", "승인 여부 (참고)")));
            assertThat(header.subList(18, 21)).containsExactly("엑셀 컬럼 헤더 표시명", "데이터 예시", "업무 설명");

            List<List<String>> rows = rows(sheet);
            assertThat(rows).extracting(r -> r.get(0) + "." + r.get(1)).containsExactly(
                    "TB_DRAFT.MEMO_CN", "DEFAULT.TB_HIST.HIST_SN", "ARCHIVE.TB_HIST.HIST_SN", "TB_MBR.LGN_ACNT", "TB_MBR.EML_ADDR", "TB_MBR.PNT_AMT");
            assertThat(rows.get(3)).containsExactly("TB_MBR", "LGN_ACNT", "로그인 계정", "VARCHAR", "100", "0", "0", "N", "'guest'", "1", "Y", "",
                    "Y", "Y", "Y", "Y", "Y", "Y", "", "user@example.com", "로그인에 쓰는 계정\n이메일 형식", "DEFAULT", "", "", "Y");
            assertThat(rows.get(4)).containsExactly("TB_MBR", "EML_ADDR", "이메일 \"주소\" & <태그>", "VARCHAR", "200", "0", "0", "Y", "", "2", "N", "TB_HIST",
                    "N", "N", "N", "N", "N", "N", "", "=HYPERLINK(\"http://x\")", "", "DEFAULT", "", "email", "N");
            assertThat(rows.get(5)).satisfies(r -> {
                assertThat(r.get(5)).isEqualTo("12");
                assertThat(r.get(6)).isEqualTo("2");
                assertThat(r.get(19)).isEqualTo("+1000");
                assertThat(r.get(20)).hasSize(4000);
            });
            assertThat(rows.get(1).get(22)).as("IDENTITY 참고 열").isEqualTo("ALWAYS");
            assertThat(rows.get(0).get(20)).as("공백만 저장된 업무 설명도 바꾸지 않는다").isEqualTo("  ");
            // 수식처럼 보이는 값도 문자열 셀 (계산되지 않는다)
            Cell formulaLike = sheet.getRow(5).getCell(19);
            assertThat(formulaLike.getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(4).getCellType()).as("숫자는 숫자 셀").isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    @DisplayName("영어 요청 : 헤더·시트 이름은 영어 템플릿과 같고 참고 열도 영어, 데이터(한국어 값)는 바꾸지 않는다")
    void englishHeaders() throws IOException {
        english();
        try (Workbook workbook = workbook(export.columns(login(), null, null, member.getId()));
             Workbook template = new XSSFWorkbook(new ByteArrayInputStream(templateBytes("columnTemplate")))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo(template.getSheetAt(0).getSheetName());
            assertThat(header(sheet)).containsExactlyElementsOf(concat(header(template.getSheetAt(0)),
                    List.of("Schema (reference)", "IDENTITY (reference)", "Source comment (reference)", "Approved (reference)")));
            assertThat(rows(sheet).get(0).get(2)).isEqualTo("로그인 계정");
        }
        try (Workbook workbook = workbook(export.tables(login(), null, null, null))) {
            assertThat(header(workbook.getSheetAt(0))).endsWith("Business description", "Schema (reference)", "Table comment (reference)",
                    "Source comment (reference)", "Approved (reference)");
        }
    }

    @Test
    @DisplayName("왕복 : 내려받은 파일을 빈 프로젝트에 그대로 올리면 업로드 양식이 담는 값이 같다 (참고 열은 반영되지 않음 — 승인 상태는 올린 사람의 승인 권한 기준)")
    void roundTrip() throws IOException {
        byte[] tables = bytes(export.tables(login(), null, null, main.getId()));
        byte[] columns = bytes(export.columns(login(), "tableName", "TB_MBR", null));

        Project target = project("m11t-");
        tx.executeWithoutResult(s -> {
            schemaRepository.save(SchemaInfo.builder().projectId(target.getId()).schemaName(SchemaInfo.DEFAULT_NAME).isDefault(true).build());
            terms(target.getId());
        });
        AccountDto targetUser = SecurityTestSupport.loginAs(projectRepository, target.getId(), String.valueOf(owner.getId()), "ROLE_USER");
        tableDesign.uploadTableExcelFile(file(tables), targetUser);
        columnManage.uploadColumnExcelFile(file(columns), targetUser);

        for (String name : List.of("TB_MBR", "TB_DRAFT", "TB_HIST")) {
            TableInfo source = tableIn(project, name, main);
            TableInfo copied = tableIn(target, name, null);
            assertThat(List.of(blank(copied.getTableDescription()), yes(copied.getIsMasterTable()), Objects.toString(copied.getSortOrder()), blank(copied.getBizDescription())))
                    .as(name).isEqualTo(List.of(blank(source.getTableDescription()), yes(source.getIsMasterTable()), Objects.toString(source.getSortOrder()), blank(source.getBizDescription())));
            // 승인 여부는 참고 열 — 반영되지 않고 올린 사람의 승인 권한을 따른다 (프로젝트 관리자 → 원본이 승인 대기인 TB_DRAFT 도 승인)
            assertThat(copied.getIsApproval()).as(name).isTrue();
        }
        for (String name : List.of("LGN_ACNT", "EML_ADDR", "PNT_AMT")) {
            ColumnInfo source = columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(project.getId(), member.getId(), name).get(0);
            ColumnInfo copied = columnRepository.findAllByProjectIdAndTableInfo_IdAndColumnName(target.getId(), tableIn(target, "TB_MBR", null).getId(), name).get(0);
            assertThat(uploadFields(copied)).as(name).isEqualTo(uploadFields(source));
        }
    }

    @Test
    @DisplayName("권한 USER : VIEWER 는 컨트롤러에서 거부 / 셀 한도(32,767자) 초과 값은 자르지 않고 대상을 밝혀 실패 / 내려받기는 DB 를 바꾸지 않는다")
    void permissionLimitAndReadOnly() throws IOException {
        AccountDto view = SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(viewer.getId()), "ROLE_VIEWER");
        assertThatThrownBy(() -> excelApi.exportTables(view, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> excelApi.exportColumns(view, null, null, null, null)).isInstanceOf(AccessDeniedException.class);

        AccountDto user = login();
        String modified = jdbc.queryForObject("select max(MDFCN_DT) from TB_COL where PRJ_SN = ?", String.class, project.getId());
        int histories = jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, project.getId());
        assertThat(excelApi.exportTables(user, null, null, null, null).getStatusCode().value()).isEqualTo(200);
        bytes(excelApi.exportColumns(user, null, null, null, null));
        assertThat(jdbc.queryForObject("select max(MDFCN_DT) from TB_COL where PRJ_SN = ?", String.class, project.getId())).isEqualTo(modified);
        assertThat(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, project.getId())).isEqualTo(histories);

        // 예전 데이터 등으로 셀 한도를 넘는 업무 설명 (화면·API 는 4,000자로 막지만 DB 칸은 TEXT)
        String huge = "x".repeat(32_768);
        jdbc.update("update TB_COL set TASK_EXPLN = ? where PRJ_SN = ? and COL_NM = 'MEMO_CN'", huge, project.getId());
        assertThatThrownBy(() -> export.columns(user, null, null, null)).isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CREATE_EXCEL_FAIL_EXCEPTION);
                    assertThat(((CustomException) e).getCustomMessage()).contains("TB_DRAFT.MEMO_CN").containsPattern("32,?767");
                });
        assertThat(jdbc.queryForObject("select TASK_EXPLN from TB_COL where PRJ_SN = ? and COL_NM = 'MEMO_CN'", String.class, project.getId())).hasSize(32_768);
    }

    @Test
    @DisplayName("대량 : 여러 쪽(500건 단위)에 걸친 1,200 컬럼이 빠짐·겹침 없이 테이블·정렬 순서대로")
    void largeExport() throws IOException {
        tx.executeWithoutResult(s -> {
            List<ColumnInfo> many = new ArrayList<>();
            for (int t = 0; t < 12; t++) {
                TableInfo table = tableRepository.save(TableInfo.builder().projectId(project.getId()).schema(main).tableName("TB_BULK_%02d".formatted(t))
                        .tableDescription("대량 " + t).isApproval(false).isMasterTable(false).build());
                for (int c = 0; c < 100; c++) {
                    many.add(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName(table.getTableName()).columnName("COL_%03d".formatted(c))
                            .columnDesc("컬럼 " + c).dataType("VARCHAR").maxLength(10).sortOrder(c + 1).bizDescription("설명 " + t + "-" + c).isApproval(false).build());
                }
            }
            columnRepository.saveAll(many);
        });
        long start = System.nanoTime();
        List<List<String>> rows;
        try (Workbook workbook = workbook(export.columns(login(), "tableName", "TB_BULK", null))) {
            rows = rows(workbook.getSheetAt(0));
        }
        System.out.printf("[M11-EXPORT] 1,200 컬럼 %d ms%n", (System.nanoTime() - start) / 1_000_000);
        assertThat(rows).hasSize(1200);
        List<String> expected = new ArrayList<>();
        for (int t = 0; t < 12; t++) for (int c = 0; c < 100; c++) expected.add("TB_BULK_%02d.COL_%03d".formatted(t, c));
        assertThat(rows).extracting(r -> r.get(0) + "." + r.get(1)).containsExactlyElementsOf(expected);
        assertThat(rows.get(1199).get(20)).isEqualTo("설명 11-99");
    }

    // ------------------------------------------------------------------ 준비

    private static List<Object> uploadFields(ColumnInfo c) {
        return java.util.Arrays.asList(blank(c.getColumnDesc()), c.getDataType(), c.getMaxLength(), c.getPrecision() == null ? null : c.getPrecision().stripTrailingZeros(),
                c.getScale(), yes(c.getIsNullable()), blank(c.getDefaultValue()), c.getSortOrder(), yes(c.getIsMasterData()), blank(c.getRefTableName()), yes(c.getIsRequired()),
                yes(c.getIsSensitive()), yes(c.getIsPk()), yes(c.getIsUnique()), yes(c.getIsIndex()), yes(c.getIsEncrypted()), blank(c.getExample()), blank(c.getBizDescription()));
    }

    /** 업로드는 Y/N 만 다룬다 : 비어 있는(null) 여부 값은 N */
    private static boolean yes(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private TableInfo tableIn(Project p, String name, SchemaInfo schema) {
        return tableRepository.findAllByProjectIdAndTableNameIn(p.getId(), Set.of(name)).stream()
                .filter(t -> schema == null || t.getSchema().getId().equals(schema.getId())).findFirst().orElseThrow();
    }

    private void terms(Long projectId) {
        StandardDomain text = domainRepository.save(StandardDomain.builder().projectId(projectId).commonStandardDomainName("명V100").commonStandardDomainCategory("명")
                .dataType("VARCHAR").dataLength(100).isApproval(true).build());
        for (String abbreviation : List.of("LGN_ACNT", "EML_ADDR", "PNT_AMT", "HIST_SN", "MEMO_CN")) {
            termRepository.save(StandardTerm.builder().projectId(projectId).commonStandardTermName("용어_" + abbreviation).commonStandardTermAbbreviation(abbreviation)
                    .standardDomain(text).isApproval(true).build());
        }
    }

    private void column(TableInfo table, String name, int order, Function<ColumnInfo.ColumnInfoBuilder, ColumnInfo.ColumnInfoBuilder> values) {
        StandardTerm term = termRepository.findAllByProjectIdAndCommonStandardTermAbbreviationIn(project.getId(), Set.of(name)).get(0);
        columnRepository.save(values.apply(ColumnInfo.builder().projectId(project.getId()).tableInfo(table).tableName(table.getTableName()).columnName(name)
                .sortOrder(order).standardTerms(term).isApproval(false)).build());
    }

    private Project project(String prefix) {
        Project p = projectRepository.save(Project.builder().projectName(prefix + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(owner).dbType(DbType.POSTGRESQL).build());
        for (Account a : List.of(owner, viewer)) projectMemberRepository.save(ProjectMember.builder().project(p).account(a).isActive(true).build());
        return p;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private List<String> templateHeader(String name) throws IOException {
        try (Workbook template = new XSSFWorkbook(new ByteArrayInputStream(templateBytes(name)))) {
            return header(template.getSheetAt(0));
        }
    }

    private byte[] templateBytes(String name) throws IOException {
        try (InputStream in = excel.getExcelFile(name, login()).getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static List<String> header(Sheet sheet) {
        List<String> values = new ArrayList<>();
        for (Cell cell : sheet.getRow(0)) values.add(cell.getStringCellValue());
        return values;
    }

    /** 데이터 행 (헤더 열 수만큼, 빈 셀은 "") */
    private static List<List<String>> rows(Sheet sheet) {
        int width = sheet.getRow(0).getLastCellNum();
        List<List<String>> rows = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            List<String> values = new ArrayList<>();
            for (int c = 0; c < width; c++) values.add(row.getCell(c) == null ? "" : FORMAT.formatCellValue(row.getCell(c)));
            rows.add(values);
        }
        return rows;
    }

    private List<String> firstColumn(ResponseEntity<Resource> response) throws IOException {
        try (Workbook workbook = workbook(response)) {
            return rows(workbook.getSheetAt(0)).stream().map(r -> r.get(0)).toList();
        }
    }

    private static Workbook workbook(ResponseEntity<Resource> response) throws IOException {
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        return new XSSFWorkbook(new ByteArrayInputStream(bytes(response)));
    }

    private static byte[] bytes(ResponseEntity<Resource> response) throws IOException {
        try (InputStream in = response.getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "export.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static void english() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en");
        request.addPreferredLocale(Locale.ENGLISH);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private AccountDto login() {
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(owner.getId()), "ROLE_USER");
    }

    private Account account(String role) {
        return tx.execute(s -> accountRepository.save(Account.builder().username("m11-" + UUID.randomUUID().toString().substring(0, 8)).name("u").password("{noop}x")
                .isActive(true).isPasswdCheck(true).userRoles(new HashSet<>(Set.of(roleRepository.findByRoleName(role)))).projectMember(new ArrayList<>()).build()));
    }
}
