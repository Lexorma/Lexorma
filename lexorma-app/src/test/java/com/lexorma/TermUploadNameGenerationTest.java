package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.dictionary.service.StandardTermService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 데이터 사전 엑셀 업로드 : 표준용어명이 영문약어명과 맞지 않을 때 (실제 업로드 경로)
 * - 영문약어명의 표준 단어로 용어명을 만들고, 표준용어설명 끝에 생성 근거를 남긴다. (업로드는 취소하지 않는다)
 * - 약어에 해당하는 표준 단어가 없으면 기존처럼 업로드 전체를 취소한다.
 *
 * 공통표준용어 템플릿(static/excel/dataPortal.xlsx)의 단어/도메인 시트는 그대로 쓰고, 용어 시트만 시험용 행으로 바꿔 올린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TermUploadNameGenerationTest {

    // 용어 시트 열 (StandardTermService.setStandardTerm 헤더 순서)
    private static final int COL_NAME = 2;
    private static final int COL_DESCRIPTION = 3;
    private static final int COL_ABBREVIATION = 4;
    private static final int COL_SYNONYMS = 11;

    @Autowired DataDictionaryService dataDictionaryService;
    @Autowired StandardTermRepository standardTermRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("용어명이 영문약어명과 맞지 않으면 표준 단어로 용어명을 만들고 설명에 근거를 남긴다 (맞는 행은 그대로)")
    void generateTermNames() throws IOException {
        // 템플릿 용어 GIS_자료_여부 / GIS_DATA_YN , SMS_사용_여부 / SMS_USE_YN , HTML_사용_여부 / HTML_USE_YN 을 바탕으로
        // (용어명·영문약어명·도메인이 모두 같으면 중복으로 건너뛰므로 행마다 다른 용어를 쓴다)
        MockMultipartFile file = templateWithTerms(List.of(
                new TermRow("GIS_자료_여부", "그대로 두는 행", "GIS_DATA_YN"),          // 맞음 → 그대로
                new TermRow("SMS사용여부", "구분자 없는 용어명", "SMS_USE_YN"),          // 구분자 없음 → 생성
                new TermRow("HTML 사용 여부 플래그", "", "HTML_USE_YN")                 // 단어 수 다름 + 설명 없음 → 생성
        ));
        AccountDto login = loginToNewProject("term-upload-generate", true);

        dataDictionaryService.uploadDictionaryExcelFile(file, login);

        Map<String, StandardTerm> byDescriptionStart = standardTermRepository.findAllByProjectId(login.getProjectId()).stream()
                .collect(Collectors.toMap(term -> term.getCommonStandardTermDescription() == null ? "" : term.getCommonStandardTermDescription().split("\n")[0],
                        Function.identity(), (a, b) -> a));
        assertThat(byDescriptionStart).hasSize(3);

        StandardTerm kept = byDescriptionStart.get("그대로 두는 행");
        assertThat(kept.getCommonStandardTermName()).isEqualTo("GIS_자료_여부");
        assertThat(kept.getCommonStandardTermDescription()).as("맞는 행은 설명을 바꾸지 않는다")
                .doesNotContain(StandardTermService.GENERATED_NOTE_PREFIX);

        StandardTerm noSeparator = byDescriptionStart.get("구분자 없는 용어명");
        assertThat(noSeparator.getCommonStandardTermName()).isEqualTo("SMS_사용_여부");
        assertThat(noSeparator.getCommonStandardTermDescription())
                .startsWith("구분자 없는 용어명\n" + StandardTermService.GENERATED_NOTE_PREFIX)
                .contains("영문약어명(SMS_USE_YN)")
                .contains("원본 표준용어명: SMS사용여부");

        StandardTerm wordCount = byDescriptionStart.get(StandardTermService.GENERATED_NOTE_PREFIX
                + " 영문약어명(HTML_USE_YN)의 표준 단어로 표준용어명을 생성했습니다. (원본 표준용어명: HTML 사용 여부 플래그)");
        assertThat(wordCount).as("설명이 없으면 생성 근거만 들어간다").isNotNull();
        assertThat(wordCount.getCommonStandardTermName()).isEqualTo("HTML_사용_여부");
        assertThat(wordCount.getCommonStandardTermAbbreviation()).isEqualTo("HTML_USE_YN");
    }

    @Test
    @DisplayName("영문약어에 해당하는 표준 단어가 없으면 기존처럼 업로드 전체를 취소한다 (NOT_FOUND_WORD_DATA)")
    void unknownAbbreviationCancelsUpload() throws IOException {
        MockMultipartFile file = templateWithTerms(List.of(
                new TermRow("GIS_자료_여부", "정상 행", "GIS_DATA_YN"),
                new TermRow("없는 용어", "", "NOT_EXISTS_ABBR_QQ")
        ));
        AccountDto login = loginToNewProject("term-upload-cancel", true);

        assertThatThrownBy(() -> dataDictionaryService.uploadDictionaryExcelFile(file, login))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND_WORD_DATA);
        assertThat(standardTermRepository.findAllByProjectId(login.getProjectId())).as("용어는 하나도 저장되지 않는다").isEmpty();
    }

    @Test
    @DisplayName("데이터 사전 약어를 쓰지 않는 프로젝트는 용어 영문명을 단어 영문명으로 만든다 (엑셀의 영문약어명 대신)")
    void englishTermNameWhenDicAbbrNotUsed() throws IOException {
        // 템플릿 단어 : SMS(영문명 여러 단어 → 약어) / 발송(Sending) / 여부(Yes or No → 약어)
        MockMultipartFile file = templateWithTerms(List.of(new TermRow("SMS_발송_여부", "약어 미사용", "SMS_SNDNG_YN")));
        AccountDto login = loginToNewProject("term-upload-english", false);

        dataDictionaryService.uploadDictionaryExcelFile(file, login);

        StandardTerm term = standardTermRepository.findAllByProjectId(login.getProjectId()).getFirst();
        assertThat(term.getCommonStandardTermName()).isEqualTo("SMS_발송_여부");
        assertThat(term.getCommonStandardTermAbbreviation()).isEqualTo("SMS_SENDING_YN");
    }

    @Test
    @DisplayName("[DICT-1] 이음동의어·금칙어 빈 셀은 빈 목록 (예전에는 빈 문자열 한 건), 값이 있는 셀은 기존 규칙대로 ',' 로 나눈다 — 템플릿 열 구조 그대로")
    void emptyListCells() throws IOException {
        MockMultipartFile file = templateWithTerms(List.of(
                new TermRow("GIS_자료_여부", "동의어 빈 셀", "GIS_DATA_YN", ""),
                new TermRow("SMS_사용_여부", "동의어 공백 셀", "SMS_USE_YN", "  "),
                new TermRow("HTML_사용_여부", "동의어 두 개", "HTML_USE_YN", "웹문서사용여부,HTML여부")
        ));
        AccountDto login = loginToNewProject("term-upload-synonyms", true);

        dataDictionaryService.uploadDictionaryExcelFile(file, login);

        Map<String, StandardTerm> byDescription = standardTermRepository.findAllByProjectId(login.getProjectId()).stream()
                .collect(Collectors.toMap(StandardTerm::getCommonStandardTermDescription, Function.identity()));
        assertThat(synonyms(byDescription.get("동의어 빈 셀"))).isEmpty();
        assertThat(synonyms(byDescription.get("동의어 공백 셀"))).isEmpty();
        assertThat(synonyms(byDescription.get("동의어 두 개"))).containsExactlyInAnyOrder("웹문서사용여부", "HTML여부");
        // 단어 시트(템플릿 그대로) : 빈 셀이 빈 문자열 행으로 남지 않는다
        for (String table : List.of("TB_STD_WORD_SYNM", "TB_STD_WORD_PRHBT")) {
            String column = table.endsWith("SYNM") ? "SYNM_NM" : "PRHBT_WORD_NM";
            assertThat(jdbcTemplate.queryForObject("select count(*) from " + table + " l join TB_STD_WORD w on w.STD_WORD_SN = l.STD_WORD_SN"
                    + " where w.PRJ_SN = ? and trim(l." + column + ") = ''", Integer.class, login.getProjectId())).as(table).isZero();
        }
    }

    @Test
    @DisplayName("[DICT-2] 용어 시트가 쓰는 도메인명이 프로젝트에 여럿(예전 데이터)이면 임의로 고르지 않고 409 AMBIGUOUS_STANDARD_DOMAIN, 업로드 전체 롤백")
    void ambiguousDomainCancelsUpload() throws IOException {
        MockMultipartFile file = templateWithTerms(List.of(new TermRow("GIS_자료_여부", "도메인 여럿", "GIS_DATA_YN")));
        String domainName = templateTermDomain("GIS_DATA_YN");
        AccountDto login = loginToNewProject("term-upload-ambiguous-domain", true);
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_CLSF_NM, DMN_NM, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, 'VARCHAR', 1, 0, true, current_timestamp, current_timestamp, 'test', 'test')",
                    877_000_000L + login.getProjectId() * 10 + i, login.getProjectId(), templateDomainCategory(domainName), domainName);
        }

        assertThatThrownBy(() -> dataDictionaryService.uploadDictionaryExcelFile(file, login))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        assertThat(standardTermRepository.findAllByProjectId(login.getProjectId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from TB_STD_DMN where PRJ_SN = ?", Integer.class, login.getProjectId()))
                .as("도메인 시트도 롤백 — 넣어 둔 두 건만").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from TB_STD_WORD where PRJ_SN = ?", Integer.class, login.getProjectId())).isZero();
    }

    @Test
    @DisplayName("[DICT-2] 도메인 시트 : 프로젝트에 대소문자만 다른 같은 이름이 있으면 그 행은 건너뛴다 (기존 '이미 등록된 도메인은 건너뜀' 을 대소문자 무시로)")
    void domainSheetSkipsCaseInsensitiveDuplicates() throws IOException {
        MockMultipartFile file = templateWithTerms(List.of(new TermRow("GIS_자료_여부", "대소문자", "GIS_DATA_YN")));
        String used = templateTermDomain("GIS_DATA_YN");
        String target = templateDomainNames().stream().filter(name -> !name.equals(name.toLowerCase(java.util.Locale.ROOT)) && !name.equals(used))
                .findFirst().orElseThrow(() -> new AssertionError("템플릿에 영문 대문자가 있는 도메인명이 있어야 한다"));
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        AccountDto login = loginToNewProject("domain-upload-case", true);
        jdbcTemplate.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_CLSF_NM, DMN_NM, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, 'VARCHAR', 1, 0, true, current_timestamp, current_timestamp, 'test', 'test')",
                877_500_000L + login.getProjectId(), login.getProjectId(), templateDomainCategory(target), lower);

        dataDictionaryService.uploadDictionaryExcelFile(file, login);

        assertThat(jdbcTemplate.queryForList("select DMN_NM from TB_STD_DMN where PRJ_SN = ? and lower(DMN_NM) = ?", String.class, login.getProjectId(), lower))
                .as(target + " 행은 건너뜀").containsExactly(lower);
        assertThat(jdbcTemplate.queryForObject("select count(*) from TB_STD_DMN where PRJ_SN = ?", Integer.class, login.getProjectId())).isEqualTo(templateDomainNames().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT)).distinct().count());
    }

    @Test
    @DisplayName("[DICT-2 후속] 용어 시트의 도메인명은 대소문자를 무시하고 찾는다 : 프로젝트에 대소문자만 다른 도메인 하나 → 그 도메인에 연결, 대소문자만 다른 둘 → 409 + 업로드 전체 롤백")
    void termSheetDomainIgnoresCase() throws IOException {
        String domainName = templateTermDomain("GIS_DATA_YN");
        String lower = domainName.toLowerCase(java.util.Locale.ROOT);
        String upper = domainName.toUpperCase(java.util.Locale.ROOT);
        assertThat(lower).as("템플릿 도메인명에 영문이 있어야 대소문자를 바꿀 수 있다").isNotEqualTo(upper);
        String category = templateDomainCategory(domainName);

        AccountDto single = loginToNewProject("term-upload-domain-case", true);
        long id = 877_700_000L + single.getProjectId();
        jdbcTemplate.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_CLSF_NM, DMN_NM, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, 'VARCHAR', 1, 0, true, current_timestamp, current_timestamp, 'test', 'test')", id, single.getProjectId(), category, lower);
        dataDictionaryService.uploadDictionaryExcelFile(templateWithTerms(List.of(new TermRow("GIS_자료_여부", "대소문자 다른 도메인", "GIS_DATA_YN", ""))), single);
        StandardTerm linked = standardTermRepository.findAllByProjectId(single.getProjectId()).getFirst();
        assertThat(linked.getStandardDomain().getId()).as(domainName + " → " + lower).isEqualTo(id);
        assertThat(jdbcTemplate.queryForObject("select DMN_NM from TB_STD_DMN where STD_DMN_SN = ?", String.class, id)).as("도메인명 그대로").isEqualTo(lower);

        AccountDto ambiguous = loginToNewProject("term-upload-domain-case-ambiguous", true);
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_CLSF_NM, DMN_NM, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, 'VARCHAR', 1, 0, true, current_timestamp, current_timestamp, 'test', 'test')",
                    877_800_000L + ambiguous.getProjectId() * 10 + i, ambiguous.getProjectId(), category, i == 0 ? lower : upper);
        }
        MockMultipartFile file = templateWithTerms(List.of(new TermRow("SMS_사용_여부", "정상 행", "SMS_USE_YN", ""), new TermRow("GIS_자료_여부", "모호한 도메인", "GIS_DATA_YN", "")));
        assertThatThrownBy(() -> dataDictionaryService.uploadDictionaryExcelFile(file, ambiguous))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN);
        assertThat(standardTermRepository.findAllByProjectId(ambiguous.getProjectId())).as("앞 행도 남지 않는다").isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from TB_STD_DMN where PRJ_SN = ?", Integer.class, ambiguous.getProjectId())).isEqualTo(2);
    }

    /** 템플릿 용어 시트에서 그 영문약어 행의 도메인명 (열 5) */
    private static String templateTermDomain(String abbreviation) throws IOException {
        try (InputStream in = TermUploadNameGenerationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx");
             Workbook workbook = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter();
            for (Row row : workbook.getSheetAt(0)) {
                if (abbreviation.equals(formatter.formatCellValue(row.getCell(COL_ABBREVIATION)))) return formatter.formatCellValue(row.getCell(5));
            }
            throw new AssertionError("템플릿에 " + abbreviation + " 행이 있어야 한다");
        }
    }

    /** 템플릿 도메인 시트에서 그 도메인의 분류 (열 3) — 단어 시트가 분류 등록 여부를 확인하므로 미리 넣는 도메인도 같은 분류로 */
    private static String templateDomainCategory(String domainName) throws IOException {
        try (InputStream in = TermUploadNameGenerationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx");
             Workbook workbook = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter();
            for (Row row : workbook.getSheetAt(2)) {
                if (domainName.equals(formatter.formatCellValue(row.getCell(4)))) return formatter.formatCellValue(row.getCell(3));
            }
            throw new AssertionError("템플릿 도메인 시트에 " + domainName + " 이 있어야 한다");
        }
    }

    /** 템플릿 도메인 시트(2)의 도메인명 (열 4, 머리글 제외, 빈 값 제외) */
    private static List<String> templateDomainNames() throws IOException {
        try (InputStream in = TermUploadNameGenerationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx");
             Workbook workbook = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter();
            List<String> names = new java.util.ArrayList<>();
            for (Row row : workbook.getSheetAt(2)) {
                if (row.getRowNum() == 0) continue;
                String name = formatter.formatCellValue(row.getCell(4));
                if (!name.isEmpty()) names.add(name);
            }
            return names;
        }
    }

    private List<String> synonyms(StandardTerm term) {
        return jdbcTemplate.queryForList("select SYNM_NM from TB_STD_TRM_SYNM where STD_TRM_SN = ?", String.class, term.getId());
    }

    // ------------------------------------------------------------------ 보조

    private record TermRow(String name, String description, String abbreviation, String synonyms) {
        TermRow(String name, String description, String abbreviation) {
            this(name, description, abbreviation, null);
        }
    }

    private AccountDto loginToNewProject(String projectName, boolean isDicAbbrUsed) {
        Account admin = accountRepository.findAll().stream()
                .filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder()
                .projectName(projectName).isActive(true).isDicAbbrUsed(isDicAbbrUsed).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    /**
     * 템플릿에서 용어 시트(0)만 시험용 행으로 바꾼다. 다른 열(도메인명/허용값 등)은 템플릿의 같은 약어 행을 복사해 쓴다.
     * (템플릿에 없는 약어면 GIS_DATA_YN 행의 나머지 열을 쓴다)
     */
    private static MockMultipartFile templateWithTerms(List<TermRow> rows) throws IOException {
        try (InputStream in = TermUploadNameGenerationTest.class.getClassLoader().getResourceAsStream("static/excel/dataPortal.xlsx");
             Workbook workbook = new XSSFWorkbook(in)) {
            Sheet terms = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            // 약어 → 템플릿 행 값 (도메인명 등 나머지 열 복사용)
            Map<String, List<String>> templateByAbbreviation = new java.util.HashMap<>();
            for (int r = 1; r <= terms.getLastRowNum(); r++) {
                Row row = terms.getRow(r);
                if (row == null) continue;
                List<String> values = new java.util.ArrayList<>();
                for (int c = 0; c < 12; c++) values.add(formatter.formatCellValue(row.getCell(c)));
                templateByAbbreviation.putIfAbsent(values.get(COL_ABBREVIATION), values);
            }

            for (int r = terms.getLastRowNum(); r >= 1; r--) {
                Row row = terms.getRow(r);
                if (row != null) terms.removeRow(row);
            }

            for (int i = 0; i < rows.size(); i++) {
                TermRow termRow = rows.get(i);
                List<String> source = templateByAbbreviation.getOrDefault(termRow.abbreviation(), templateByAbbreviation.get("GIS_DATA_YN"));
                assertThat(source).as("템플릿에 GIS_DATA_YN 행이 있어야 한다").isNotNull();

                Row row = terms.createRow(i + 1);
                for (int c = 0; c < source.size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(source.get(c));
                }
                row.getCell(COL_NAME).setCellValue(termRow.name());
                row.getCell(COL_DESCRIPTION).setCellValue(termRow.description());
                row.getCell(COL_ABBREVIATION).setCellValue(termRow.abbreviation());
                if (termRow.synonyms() != null) row.getCell(COL_SYNONYMS).setCellValue(termRow.synonyms());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "dataPortal-term-test.xlsx", null, out.toByteArray());
        }
    }
}
