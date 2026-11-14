package com.lexorma.data.excel.util;

import com.lexorma.common.i18n.Messages;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * 엑셀 템플릿(static/excel 의 template / tableTemplate / columnTemplate)의 시트 이름·헤더·예시 값 영어 표기.
 * <p>
 * 템플릿 파일은 한국어로 두고, 영어 요청({@link Messages#isEnglish()})이면 내려보내기 전에 셀 문자열을 바꾼다.
 * 업로드는 열 순서로만 읽으므로(헤더 문자열은 보지 않는다) 영어 템플릿도 그대로 올릴 수 있다.
 * 공통표준용어(dataPortal.xlsx)는 한국 공공데이터포털 자료라 바꾸지 않는다. (영어 화면에서는 다운로드 버튼을 숨긴다)
 * 템플릿에 한국어 셀을 추가하면 여기에 영어를 넣는다. (ExcelTemplateLabelsTest 가 누락을 검사한다)
 */
public final class ExcelTemplateLabels {

    private static final Pattern KOREAN = Pattern.compile("[가-힣]");

    static final Map<String, String> ENGLISH = Map.ofEntries(
            // template.xlsx (데이터사전) : 시트 이름
            entry("공통표준용어", "Standard terms"),
            entry("공통표준단어", "Standard words"),
            entry("공통표준도메인", "Standard domains"),
            // template.xlsx : 헤더
            entry("번호", "No."),
            entry("제정차수", "Revision"),
            entry("공통표준용어명", "Standard term name"),
            entry("공통표준용어설명", "Standard term description"),
            entry("공통표준용어영문약어명", "Standard term English abbreviation"),
            entry("공통표준도메인명", "Standard domain name"),
            entry("허용값", "Allowed values"),
            entry("저장 형식", "Storage format"),
            entry("표현 형식", "Display format"),
            entry("저장\n형식", "Storage\nformat"),
            entry("표현\n형식", "Display\nformat"),
            entry("행정표준코드명", "Administrative standard code name"),
            entry("소관기관명", "Responsible agency"),
            entry("용어 이음동의어 목록", "Term synonyms"),
            entry("공통표준단어명", "Standard word name"),
            entry("공통표준단어영문약어명", "Standard word English abbreviation"),
            entry("공통표준단어 영문명", "Standard word English name"),
            entry("공통표준단어 설명", "Standard word description"),
            entry("형식단어\n여부", "Format\nword"),
            entry("공통표준도메인분류명", "Standard domain category"),
            entry("이음동의어 목록", "Synonyms"),
            entry("금칙어 \n목록", "Forbidden\nwords"),
            entry("공통표준도메인그룹명", "Standard domain group"),
            entry("공통표준도메인설명", "Standard domain description"),
            entry("데이터타입", "Data type"),
            entry("데이터길이", "Data length"),
            entry("데이터소수점길이", "Decimal places"),
            entry("단위", "Unit"),
            // tableTemplate.xlsx
            entry("테이블명", "Table name"),
            entry("테이블설명", "Table description"),
            entry("마스터테이블여부", "Master table"),
            entry("정렬순서", "Sort order"),
            entry("예제 테이블입니다.", "This is an example table."),
            // columnTemplate.xlsx
            entry("컬럼명", "Column name"),
            entry("컬럼 설명", "Column description"),
            entry("데이터 타입", "Data type"),
            entry("최대 길이", "Max length"),
            entry("정밀도", "Precision"),
            entry("소수 자릿수", "Scale"),
            entry("NULL 허용 여부", "Nullable"),
            entry("기본값", "Default value"),
            entry("정렬 순서", "Sort order"),
            entry("마스터 데이터 여부", "Master data"),
            entry("참조 테이블명", "Reference table"),
            entry("필수 입력 여부", "Required"),
            entry("민감정보 여부", "Sensitive"),
            entry("PK 여부", "PK"),
            entry("고유값 여부", "Unique"),
            entry("인덱스 생성 여부", "Create index"),
            entry("암호화 필요 여부", "Needs encryption"),
            entry("엑셀 컬럼 헤더 표시명", "Excel column header label"),
            entry("데이터 예시", "Sample data"),
            entry("이메일", "Email"),
            entry("사용자ID", "User ID"));

    private ExcelTemplateLabels() {
    }

    /** 영어 요청이면 시트 이름과 한국어 문자열 셀을 영어로 바꾼다. (한국어 요청이면 그대로 둔다) */
    public static void localize(Workbook workbook) {
        if (!Messages.isEnglish()) return;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            workbook.setSheetName(i, english(sheet.getSheetName()));
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING) {
                        String value = cell.getStringCellValue();
                        if (KOREAN.matcher(value).find()) cell.setCellValue(english(value));
                    }
                }
            }
        }
    }

    /** 템플릿(헤더와 예시 행만 있는 작은 파일)을 요청 언어에 맞춰 바이트로 돌려준다. */
    public static byte[] localizedTemplate(InputStream template) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(template); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            localize(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static String english(String korean) {
        return ENGLISH.getOrDefault(korean, korean);
    }
}
