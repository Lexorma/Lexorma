package com.lexorma.data.excel.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnListFilter;
import com.lexorma.data.excel.util.ExcelExportUtils;
import com.lexorma.data.excel.util.ExcelExportUtils.RowWriter;
import com.lexorma.data.excel.util.PagedReader;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableListFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 테이블·컬럼 등록 데이터 엑셀 다운로드 (M-11). 2026-11-16 사용자 결정 :
 * <ul>
 *   <li>범위 : 화면의 지금 조회 조건(스키마·검색 열/검색어, 컬럼은 테이블 ID) 전체 — 페이지와 무관, 조건이 없으면 프로젝트 전체. 목록과 같은 조건({@link TableListFilter}·{@link ColumnListFilter})</li>
 *   <li>승인·승인 대기 모두. 권한 USER (컨트롤러). DB 는 읽기만 한다</li>
 *   <li>열 : 업로드 템플릿과 같은 위치·헤더(테이블 A~E, 컬럼 A~U — S 엑셀 컬럼 헤더 표시명은 담을 속성이 없어 비운다, T 데이터 예시, U 업무 설명).
 *       그 뒤 참고 열은 조회 전용이고 다시 올려도 반영되지 않는다 (업로드는 앞의 열만 읽는다)</li>
 *   <li>값은 바꾸지 않는다 (줄바꿈 그대로, 수식처럼 보이는 문자열도 문자열 셀이라 계산되지 않는다). 셀 한도(32,767자)를 넘는 값은 자르지 않고 대상을 밝혀 실패</li>
 * </ul>
 * 대량 : {@link PagedReader} 로 500건씩 읽어 SXSSF 로 쓰고 쪽마다 영속성 컨텍스트를 비운다 (데이터 사전 다운로드와 같은 방식).
 */
@Service
@RequiredArgsConstructor
public class ModelExcelExportService {

    /** 테이블 참고 열 (F~) */
    static final List<String> TABLE_REFERENCES = List.of("스키마 (참고)", "테이블 코멘트 (참고)", "원본 COMMENT (참고)", "승인 여부 (참고)");
    /** 컬럼 참고 열 (V~) */
    static final List<String> COLUMN_REFERENCES = List.of("스키마 (참고)", "IDENTITY (참고)", "원본 COMMENT (참고)", "승인 여부 (참고)");

    private final TableInfoRepository tableRepository;
    private final ColumnInfoRepository columnRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> tables(AccountDto account, String searchColumn, String searchQuery, Long schemaId) throws IOException {
        return tables(account, searchColumn, searchQuery, schemaId, null);
    }

    /** tableIds : 고른 테이블만 (DEC-22 선택 테이블 Export, 최대 {@value #SELECTED_MAX}). 비면 조회 조건 전체 */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> tables(AccountDto account, String searchColumn, String searchQuery, Long schemaId, List<Long> tableIds) throws IOException {
        Specification<TableInfo> where = TableListFilter.of(account.getProjectId(), searchColumn, searchQuery, schemaId).and(fetchTableSchema()).and(selectedTables(tableIds, false));
        Sort sort = Sort.by("schema.schemaName", "tableName", "id");
        File file = ExcelExportUtils.exportModel("tableTemplate", "tables", TABLE_REFERENCES, rows ->
                PagedReader.forEach(pageable -> tableRepository.findAll(where, pageable), sort, table -> writeTable(rows, table), entityManager::clear));
        return response(file, "tables");
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> columns(AccountDto account, String searchColumn, String searchQuery, Long tableId) throws IOException {
        return columns(account, searchColumn, searchQuery, tableId, null);
    }

    /** tableIds : 고른 테이블의 컬럼만 (DEC-22). 비면 조회 조건 전체 */
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> columns(AccountDto account, String searchColumn, String searchQuery, Long tableId, List<Long> tableIds) throws IOException {
        Long projectId = account.getProjectId();
        Specification<ColumnInfo> where = ColumnListFilter.of(projectId, searchColumn, searchQuery, tableId).and(fetchColumnTable()).and(selectedTables(tableIds, true));
        // 여러 스키마에 같은 이름이 있는 테이블은 업로드처럼 "스키마명.테이블명" 으로 적는다 (TableNameResolver)
        Set<String> duplicatedNames = new HashSet<>(tableRepository.findDuplicatedTableNames(projectId));
        Sort sort = Sort.by("tableName", "tableInfo.id", "sortOrder", "id");
        File file = ExcelExportUtils.exportModel("columnTemplate", "columns", COLUMN_REFERENCES, rows ->
                PagedReader.forEach(pageable -> columnRepository.findAll(where, pageable), sort, column -> writeColumn(rows, column, duplicatedNames), entityManager::clear));
        return response(file, "columns");
    }

    static final int SELECTED_MAX = 500;

    /** 고른 테이블 조건 (프로젝트 조건은 앞의 필터가 이미 건다 — 다른 프로젝트 ID 는 결과에 나오지 않는다) */
    private static <T> Specification<T> selectedTables(List<Long> tableIds, boolean column) {
        if (tableIds == null || tableIds.isEmpty()) return (root, query, cb) -> null;
        if (tableIds.size() > SELECTED_MAX) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "한 번에 {0}개 테이블까지 고를 수 있습니다.", SELECTED_MAX);
        return (root, query, cb) -> (column ? root.get("tableInfo").get("id") : root.get("id")).in(tableIds);
    }

    private static void writeTable(RowWriter rows, TableInfo t) {
        String target = t.getTableName();
        rows.write(
                // 업로드 열 A~E (tableTemplate)
                text(t.getTableName(), target), text(t.getTableDescription(), target), yn(t.getIsMasterTable()), t.getSortOrder(), text(t.getBizDescription(), target),
                // 참고 열
                t.getSchema() == null ? null : t.getSchema().getSchemaName(), text(t.getTableKorName(), target), text(t.getSourceComment(), target), yn(t.getIsApproval()));
    }

    private static void writeColumn(RowWriter rows, ColumnInfo c, Set<String> duplicatedNames) {
        TableInfo table = c.getTableInfo();
        String schemaName = table == null || table.getSchema() == null ? null : table.getSchema().getSchemaName();
        String tableName = table == null ? c.getTableName() : table.getTableName();
        String written = schemaName != null && tableName != null && duplicatedNames.contains(tableName) ? schemaName + "." + tableName : tableName;
        String target = tableName + "." + c.getColumnName();
        rows.write(
                // 업로드 열 A~U (columnTemplate)
                written, c.getColumnName(), text(c.getColumnDesc(), target), c.getDataType(), c.getMaxLength(), number(c.getPrecision()), c.getScale(),
                yn(c.getIsNullable()), text(c.getDefaultValue(), target), c.getSortOrder(), yn(c.getIsMasterData()), c.getRefTableName(),
                yn(c.getIsRequired()), yn(c.getIsSensitive()), yn(c.getIsPk()), yn(c.getIsUnique()), yn(c.getIsIndex()), yn(c.getIsEncrypted()),
                null, // S 엑셀 컬럼 헤더 표시명 : 담는 속성이 없다
                text(c.getExample(), target), text(c.getBizDescription(), target),
                // 참고 열
                schemaName, c.getIdentity() == null ? null : c.getIdentity().name(), text(c.getSourceComment(), target), yn(c.getIsApproval()));
    }

    /** 엑셀 셀 한도를 넘는 값은 자르지 않고 대상을 밝혀 실패한다 */
    private static String text(String value, String target) {
        if (value != null && value.length() > ExcelExportUtils.MAX_CELL_TEXT) {
            throw new CustomException(ErrorCode.CREATE_EXCEL_FAIL_EXCEPTION, "{0} : 엑셀 셀에 담을 수 있는 길이({1}자)를 넘는 값이 있어 내려받을 수 없습니다.",
                    target, ExcelExportUtils.MAX_CELL_TEXT);
        }
        return value;
    }

    /** 업로드와 같은 Y / N (값이 없으면 N — 업로드도 빈 칸을 N 으로 본다) */
    private static String yn(Boolean value) {
        return Boolean.TRUE.equals(value) ? "Y" : "N";
    }

    /** 정밀도 : 숫자 셀 (소수점 없는 값) */
    private static Number number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().scale() <= 0 ? value.longValue() : value;
    }

    /** 스키마를 함께 읽는다 (쪽마다 테이블 수만큼 조회하지 않도록). 건수 쿼리에는 붙이지 않는다 */
    private static Specification<TableInfo> fetchTableSchema() {
        return (root, query, builder) -> {
            if (!isCount(query.getResultType())) root.fetch("schema", JoinType.LEFT);
            return null;
        };
    }

    private static Specification<ColumnInfo> fetchColumnTable() {
        return (root, query, builder) -> {
            if (!isCount(query.getResultType())) {
                root.fetch("tableInfo", JoinType.LEFT).fetch("schema", JoinType.LEFT);
                root.fetch("standardTerms", JoinType.LEFT);
            }
            return null;
        };
    }

    private static boolean isCount(Class<?> resultType) {
        return resultType == Long.class || resultType == long.class;
    }

    private static ResponseEntity<Resource> response(File file, String name) throws IOException {
        InputStream in = Files.newInputStream(file.toPath(), StandardOpenOption.DELETE_ON_CLOSE);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".xlsx\"")
                .body(new InputStreamResource(in));
    }
}
