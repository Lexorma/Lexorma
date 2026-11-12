package com.lexorma.data.excel.util;

import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.common.i18n.Messages;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 데이터사전 엑셀 템플릿에 데이터를 채워 출력한다.
 * (공통 엑셀 유틸은 common 의 ExcelUtils 사용)
 *
 * 메모리에 전체를 올리지 않도록 SXSSFWorkbook(스트리밍 쓰기)으로 데이터 행을 쓴다.
 * - static/excel 의 출력용 템플릿은 헤더(1행)만 둔다. SXSSF 는 템플릿에 이미 있는 행 위치에 쓸 수 없다.
 * - template.xlsx 는 공통표준용어 파일(dataPortal.xlsx)과 같은 형식(시트/헤더/열 서식/헤더 고정/필터)이고,
 *   데이터 셀은 템플릿의 열 서식(글꼴/정렬)에 얇은 테두리를 더해 쓴다.
 * - 반환한 임시 파일은 호출한 쪽에서 다 내려보낸 뒤 지워야 한다. (예: Files.newInputStream(path, DELETE_ON_CLOSE))
 */
public class ExcelExportUtils {

    /** 메모리에 유지할 행 수 (나머지는 임시 파일로 내보낸다) */
    private static final int STREAMING_WINDOW_SIZE = 100;

    /** 데이터는 헤더 다음 줄부터 */
    private static final int FIRST_DATA_ROW = 1;

    /**
     * 출력할 데이터를 한 건씩 넘겨주는 소스. 목록 전체를 들고 있지 않아도 된다.
     * - DB : PagedReader 로 페이지 단위 조회 (ExcelServiceImpl)
     * - 이미 가진 목록 : list::forEach
     */
    @FunctionalInterface
    public interface RowSource<T> {
        void forEach(Consumer<? super T> action);
    }

    /** 시트에 쓸 다음 행 번호 + 열별 데이터 셀 서식 (템플릿 열 서식 + 얇은 테두리) */
    private static final class SheetWriter {
        private final Sheet sheet;
        private final Map<Integer, CellStyle> styles = new HashMap<>();
        private int next = FIRST_DATA_ROW;

        SheetWriter(Sheet sheet) {
            this.sheet = sheet;
        }

        /** 값 : 숫자(Number)는 숫자 셀, 그 외는 문자열 셀 (null 은 빈 문자열) */
        void write(Object... values) {
            Row row = sheet.createRow(next++);
            for (int i = 0; i < values.length; i++) {
                Cell cell = row.createCell(i);
                cell.setCellStyle(styles.computeIfAbsent(i, this::dataStyle));
                if (values[i] instanceof Number number) cell.setCellValue(number.doubleValue());
                else cell.setCellValue(values[i] != null ? values[i].toString() : "");
            }
        }

        private CellStyle dataStyle(int column) {
            Workbook workbook = sheet.getWorkbook();
            CellStyle style = workbook.createCellStyle();
            CellStyle columnStyle = sheet.getColumnStyle(column);
            if (columnStyle != null) style.cloneStyleFrom(columnStyle);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            return style;
        }
    }

    /** 번호 : 숫자 셀 (없거나 0 이면 빈 값) */
    private static Long idOrNull(Long id) {
        return id != null && id != 0 ? id : null;
    }

    /** 제정차수 : 공통표준용어 파일처럼 'N차' (0 이면 빈 값, 업로드는 숫자만 읽는다) */
    private static String revision(int revisionNumber) {
        return revisionNumber != 0 ? revisionNumber + "차" : "";
    }

    public static File loadAndCopyDictionaryExcelFile(String fileName, RowSource<StandardTermDto> terms,
                                                      RowSource<StandardWordDto> words, RowSource<StandardDomainDto> domains) throws IOException {
        return export("template", fileName, workbook -> {
            // 표준용어
            SheetWriter termSheet = new SheetWriter(workbook.getSheetAt(0));
            terms.forEach(dto ->
                termSheet.write(
                        idOrNull(dto.getId()),
                        revision(dto.getRevisionNumber()),
                        dto.getCommonStandardTermName(),
                        dto.getCommonStandardTermDescription(),
                        dto.getCommonStandardTermAbbreviation(),
                        dto.getCommonStandardDomainName(),
                        dto.getAllowedValues(),
                        dto.getStorageFormat(),
                        dto.getDisplayFormat(),
                        dto.getAdministrativeStandardCodeName(),
                        dto.getResponsibleOrganization(),
                        dto.getSynonyms()));

            // 표준단어
            SheetWriter wordSheet = new SheetWriter(workbook.getSheetAt(1));
            words.forEach(dto ->
                wordSheet.write(
                        idOrNull(dto.getId()),
                        revision(dto.getRevisionNumber()),
                        dto.getCommonStandardWordName(),
                        dto.getCommonStandardWordAbbreviation(),
                        dto.getCommonStandardWordEnglishName(),
                        dto.getCommonStandardWordDescription(),
                        dto.getIsFormatWord(),
                        dto.getCommonStandardDomainCategory(),
                        dto.getSynonyms(),
                        dto.getRestrictedWords()));

            // 표준도메인
            SheetWriter domainSheet = new SheetWriter(workbook.getSheetAt(2));
            domains.forEach(dto ->
                domainSheet.write(
                        idOrNull(dto.getId()),
                        revision(dto.getRevisionNumber()),
                        dto.getCommonStandardDomainGroupName(),
                        dto.getCommonStandardDomainCategory(),
                        dto.getCommonStandardDomainName(),
                        dto.getCommonStandardDomainDescription(),
                        dto.getDataType(),
                        dto.getDataLength(),
                        dto.getDataDecimalLength(),
                        dto.getStorageFormat(),
                        dto.getDisplayFormat(),
                        dto.getUnit(),
                        dto.getAllowedValues()));
        });
    }

    /**
     * 템플릿(헤더만 있는 작은 파일)을 열고 SXSSF 로 데이터 행을 써서 임시 파일로 저장한다.
     *
     * @param templateName static/excel/{templateName}.xlsx
     * @param fileName     오류 메시지용 이름
     * @param writer       데이터 행 쓰기
     */
    private static File export(String templateName, String fileName, Consumer<SXSSFWorkbook> writer) throws IOException {
        File outFile = null;

        try (InputStream template = ExcelExportUtils.class.getClassLoader().getResourceAsStream("static/excel/" + templateName + ".xlsx")) {
            if (template == null) {
                throw new FileNotFoundException(Messages.get("파일을 찾을 수 없습니다: {0}", fileName));
            }

            SXSSFWorkbook workbook = new SXSSFWorkbook(new XSSFWorkbook(template), STREAMING_WINDOW_SIZE);
            try {
                writer.accept(workbook);

                outFile = File.createTempFile("excel-export-", ".xlsx");
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                    workbook.write(out);
                }
                return outFile;
            } finally {
                workbook.close(); // POI 5.x : 스트리밍 임시 파일도 함께 삭제
            }
        } catch (IOException | RuntimeException e) {
            if (outFile != null) Files.deleteIfExists(outFile.toPath());
            throw e;
        }
    }
}
