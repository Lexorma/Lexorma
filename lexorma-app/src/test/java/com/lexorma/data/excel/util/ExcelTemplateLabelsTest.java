package com.lexorma.data.excel.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엑셀 템플릿 영어 표기 : 템플릿(static/excel)이 lexorma-app 리소스에 있어 app 모듈에서 검증한다.
 */
class ExcelTemplateLabelsTest {

    private static final Pattern KOREAN = Pattern.compile("[가-힣]");

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"template", "tableTemplate", "columnTemplate"})
    @DisplayName("영어 요청 : 시트 이름과 셀에 한국어가 남지 않고, 셀 위치(열 순서)는 그대로다")
    void englishTemplateHasNoKorean(String templateName) throws IOException {
        request("en");
        byte[] localized;
        try (InputStream in = template(templateName)) {
            localized = ExcelTemplateLabels.localizedTemplate(in);
        }

        try (InputStream in = template(templateName); Workbook original = new XSSFWorkbook(in);
             Workbook english = new XSSFWorkbook(new ByteArrayInputStream(localized))) {
            assertThat(english.getNumberOfSheets()).isEqualTo(original.getNumberOfSheets());
            for (int i = 0; i < english.getNumberOfSheets(); i++) {
                assertThat(english.getSheetName(i)).as("시트 이름").doesNotContainPattern(KOREAN);
                assertThat(strings(english.getSheetAt(i))).as("한국어 셀").noneMatch(value -> KOREAN.matcher(value).find());
                assertThat(cellRefs(english.getSheetAt(i))).as("셀 위치").isEqualTo(cellRefs(original.getSheetAt(i)));
            }
        }
    }

    @Test
    @DisplayName("한국어 요청(또는 요청 없음) : 템플릿을 그대로 둔다")
    void koreanTemplateUnchanged() throws IOException {
        request("ko");
        try (InputStream in = template("template"); Workbook workbook = new XSSFWorkbook(in)) {
            ExcelTemplateLabels.localize(workbook);
            assertThat(workbook.getSheetName(0)).isEqualTo("공통표준용어");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(2).getStringCellValue()).isEqualTo("공통표준용어명");
        }
    }

    private static void request(String language) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, language);
        request.addPreferredLocale(Locale.forLanguageTag(language));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static InputStream template(String name) {
        InputStream in = ExcelTemplateLabelsTest.class.getClassLoader().getResourceAsStream("static/excel/" + name + ".xlsx");
        assertThat(in).as(name).isNotNull();
        return in;
    }

    private static List<String> strings(Sheet sheet) {
        List<String> values = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING) values.add(cell.getStringCellValue());
            }
        }
        return values;
    }

    private static List<String> cellRefs(Sheet sheet) {
        List<String> refs = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) refs.add(cell.getAddress() + ":" + cell.getCellType());
        }
        return refs;
    }
}
