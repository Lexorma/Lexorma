package com.koboolean.metamora.data.table.controller;

import com.koboolean.metamora.data.table.domain.dto.TableDdlDto;
import com.koboolean.metamora.data.table.domain.dto.TableInfoDto;
import com.koboolean.metamora.data.table.domain.dto.TableNameDto;
import com.koboolean.metamora.data.table.service.TableDesignService;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.common.utils.PageableUtil;
import com.koboolean.metamora.common.i18n.Messages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "Table Design API", description = "테이블 설계 API")
public class TableDesignRestController {

    private final TableDesignService tableDesignService;

    @Operation(summary = "테이블 설계 데이터 조회", description = "테이블 설계 데이터를 조회합니다.")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/selectTable/data")
    public ResponseEntity<Map<String,Object>> selectTableInfoData(
            @Parameter(description = "사용자 인증 정보", hidden = true)
            @AuthenticationPrincipal AccountDto accountDto,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(value = "page") int page,
            @Parameter(description = "페이지 크기", example = "10")
            @RequestParam(value = "size") int size,
            @Parameter(description = "정렬 조건 (예: timestamp,desc;id,asc)", example = "id,desc")
            @RequestParam(required = false, value = "sort") String sort,
            @Parameter(description = "조회 용어명", example = "용어명1")
            @RequestParam(required = false, value = "searchQuery") String searchQuery,
            @Parameter(description = "조회컬럼 명", example = "id")
            @RequestParam(required = false, value = "searchColumn") String searchColumn,
            @Parameter(description = "스키마 ID (없으면 전체 스키마)")
            @RequestParam(required = false, value = "schemaId") Long schemaId
    ) {
        Pageable pageable = PageableUtil.getGridPageable(page, size, sort);
        Page<TableInfoDto> tableDesigns = tableDesignService.selectTableInfoData(pageable, accountDto, searchColumn, searchQuery, schemaId);
        return PageableUtil.getGridPageableMap(tableDesigns);
    }

    @Operation(summary = "테이블 1건 조회", description = "테이블 ID 로 테이블 정보를 조회합니다. (ERD 등 다른 화면에서 테이블 상세를 열 때)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/selectTable/detail/{tableId}")
    public ResponseEntity<TableInfoDto> selectTable(@PathVariable(value = "tableId") Long tableId,
                                                    @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(tableDesignService.selectTable(tableId, accountDto));
    }

    @Operation(summary = "테이블 DDL 생성", description = "테이블과 연결된 컬럼으로 프로젝트 대상 DB(시스템 관리 > 프로젝트 관리) 기준 DDL 을 생성합니다.")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/selectTable/ddl/{tableId}")
    public ResponseEntity<TableDdlDto> generateTableDdl(@PathVariable(value = "tableId") Long tableId,
                                                        @Parameter(description = "기본 스키마 테이블에 붙일 스키마명 (없으면 붙이지 않는다)", example = "public")
                                                        @RequestParam(required = false, value = "defaultSchemaName") String defaultSchemaName,
                                                        @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(tableDesignService.generateTableDdl(tableId, accountDto, defaultSchemaName));
    }

    @Operation(summary = "표준 단어 기준 테이블명 생성", description = "테이블 한글명(예: 사용_내역_관리)을 승인된 표준 단어의 영문약어로 변환합니다.")
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/selectTable/tableName")
    public ResponseEntity<TableNameDto> generateTableName(
            @Parameter(description = "테이블 한글명 (단어는 _ 또는 공백으로 구분)", example = "사용_내역_관리")
            @RequestParam(value = "tableKorName") String tableKorName,
            @AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(tableDesignService.generateTableName(tableKorName, accountDto));
    }

    @Operation(summary = "테이블 관리 테이블 등록", description = "테이블 관리 테이블 데이터를 등록합니다. 테이블명은 한글명(tableKorName)을 표준 단어로 변환해 생성합니다.")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/saveTable")
    public ResponseEntity<Map<String, Boolean>> saveTable(@RequestBody TableInfoDto tableInfoDto, @AuthenticationPrincipal AccountDto accountDto) {
        tableDesignService.saveTable(tableInfoDto, accountDto);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "테이블 관리 테이블 수정", description = "테이블 관리 테이블 데이터를 수정합니다.")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/saveTable")
    public ResponseEntity<Map<String, Boolean>> updateTable(@RequestBody TableInfoDto tableInfoDto, @AuthenticationPrincipal AccountDto accountDto) {
        tableDesignService.updateTable(tableInfoDto, accountDto);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "테이블 관리 테이블 승인/승인취소", description = "테이블 관리 테이블 데이터를  승인/승인취소합니다.")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/saveTable/{isApproval}")
    public ResponseEntity<Map<String, Boolean>> updateTableIsApproval(@PathVariable(value = "isApproval") boolean isApproval, @AuthenticationPrincipal AccountDto accountDto, @RequestBody List<TableInfoDto> tableInfoDtos) {
        tableDesignService.updateTableIsApproval(isApproval, accountDto, tableInfoDtos);

        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "테이블 관리 테이블 삭제", description = "테이블 관리 테이블 데이터를  삭제합니다.")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/deleteTable")
    public ResponseEntity<Map<String, Boolean>> deleteTable(@AuthenticationPrincipal AccountDto accountDto, @RequestBody List<TableInfoDto> tableInfoDtos) {
        tableDesignService.deleteTable(tableInfoDtos, accountDto);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "테이블 템플릿 엑셀 업로드", description = "테이블 템플릿 엑셀을 파싱하여 테이블에 저장한다.")
    @PreAuthorize("hasRole('USER')")
    @PostMapping(value = "/uploadTableExcelFile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadTableExcelFile(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal AccountDto accountDto) throws IOException {

        try {
            tableDesignService.uploadTableExcelFile(file, accountDto);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, Messages.get("파일을 읽을 수 없습니다."), e);
        }

        return ResponseEntity.ok(Map.of("result", "success"));
    }


}
