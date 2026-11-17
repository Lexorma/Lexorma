package com.lexorma.data.excel.controller;

import com.lexorma.data.excel.service.ExcelService;
import com.lexorma.data.excel.service.ModelExcelExportService;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.i18n.Messages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.MalformedURLException;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Excel API", description = "Excel 관련 API")
@PreAuthorize("hasRole('USER')")
public class ExcelRestController {

    private final ExcelService excelService;
    private final ModelExcelExportService modelExport;

    @Operation(summary = "테이블 등록 데이터 엑셀 다운로드 (M-11)",
            description = "테이블 관리 화면의 지금 조회 조건(스키마·검색) 전체를 테이블 템플릿 형식(A~E, 업무 설명 E)으로 내려받는다. 승인·승인 대기 모두. F 열부터는 참고 열(다시 올려도 반영되지 않음)")
    @GetMapping("/exportExcel/tables")
    public ResponseEntity<Resource> exportTables(@AuthenticationPrincipal AccountDto accountDto,
                                                 @RequestParam(required = false, value = "searchColumn") String searchColumn,
                                                 @RequestParam(required = false, value = "searchQuery") String searchQuery,
                                                 @RequestParam(required = false, value = "schemaId") Long schemaId,
                                                 @RequestParam(required = false, value = "tableIds") java.util.List<Long> tableIds) throws IOException {
        return modelExport.tables(accountDto, searchColumn, searchQuery, schemaId, tableIds);
    }

    @Operation(summary = "컬럼 등록 데이터 엑셀 다운로드 (M-11)",
            description = "컬럼 관리 화면의 지금 조회 조건(검색·테이블) 전체를 컬럼 템플릿 형식(A~U : S 비움, T 데이터 예시, U 업무 설명)으로 내려받는다. 승인·승인 대기 모두. V 열부터는 참고 열(다시 올려도 반영되지 않음)")
    @GetMapping("/exportExcel/columns")
    public ResponseEntity<Resource> exportColumns(@AuthenticationPrincipal AccountDto accountDto,
                                                  @RequestParam(required = false, value = "searchColumn") String searchColumn,
                                                  @RequestParam(required = false, value = "searchQuery") String searchQuery,
                                                  @RequestParam(required = false, value = "tableId") Long tableId,
                                                  @RequestParam(required = false, value = "tableIds") java.util.List<Long> tableIds) throws IOException {
        return modelExport.columns(accountDto, searchColumn, searchQuery, tableId, tableIds);
    }

    @Operation(
            summary = "Excel 템플릿 다운로드",
            description = "주어진 파일명을 기반으로 Excel 템플릿을 다운로드합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "파일 다운로드 성공"),
            @ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음"),
            @ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/downloadTemplate/{templateName}")
    public ResponseEntity<Resource> downloadTemplate(@PathVariable(value = "templateName") String templateName, @AuthenticationPrincipal AccountDto accountDto){
        try {
            return excelService.getExcelFile(templateName, accountDto);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, Messages.get("파일 경로가 잘못되었습니다."), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, Messages.get("파일을 읽을 수 없습니다."), e);
        }
    }

}
