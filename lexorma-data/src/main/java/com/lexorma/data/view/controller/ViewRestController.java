package com.lexorma.data.view.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.service.PhysicalViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** VIEW 관리 (M-15). 정의 SQL 은 어떤 DB 에서도 실행하지 않는다 (DDL 은 생성·조회만) */
@RestController
@RequestMapping("/api/physical/view")
@RequiredArgsConstructor
@Tag(name = "Physical View API", description = "VIEW 정의·출력 컬럼·참조 객체·변경 요청·DDL")
public class ViewRestController {

    private final PhysicalViewService viewService;

    @Operation(summary = "VIEW 목록 (Grid)", description = "schemaId·isApproval(Y/N) 조건, searchColumn viewName / viewKorName / viewDescription")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> search(@AuthenticationPrincipal AccountDto accountDto,
                                                      @RequestParam(value = "page") int page,
                                                      @RequestParam(value = "size") int size,
                                                      @RequestParam(required = false, value = "sort") String sort,
                                                      @RequestParam(required = false, value = "searchColumn") String searchColumn,
                                                      @RequestParam(required = false, value = "searchQuery") String searchQuery,
                                                      @RequestParam(required = false, value = "schemaId") Long schemaId,
                                                      @RequestParam(required = false, value = "isApproval") String isApproval) {
        return PageableUtil.getGridPageableMap(viewService.search(accountDto, PageableUtil.getGridPageable(page, size, sort), searchColumn, searchQuery, schemaId, isApproval));
    }

    @Operation(summary = "참조 객체 후보", description = "프로젝트의 테이블·VIEW (이름·논리명 검색, 종류별 최대 50). type TABLE / VIEW / 비우면 둘 다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/targets")
    public ResponseEntity<List<ViewDto.Target>> targets(@AuthenticationPrincipal AccountDto accountDto,
                                                        @RequestParam(required = false, value = "keyword") String keyword,
                                                        @RequestParam(required = false, value = "type") String type) {
        return ResponseEntity.ok(viewService.targets(accountDto, keyword, type));
    }

    @Operation(summary = "VIEW DDL 내보내기", description = "프로젝트(또는 스키마)의 CREATE VIEW 전체, 참조되는 VIEW 먼저. 실행하지 않는다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/ddl")
    public ResponseEntity<TableDdlDto> ddlAll(@AuthenticationPrincipal AccountDto accountDto,
                                              @RequestParam(required = false, value = "schemaId") Long schemaId,
                                              @RequestParam(required = false, value = "defaultSchemaName") String defaultSchemaName) {
        return ResponseEntity.ok(viewService.ddlAll(accountDto, schemaId, defaultSchemaName));
    }

    @Operation(summary = "DDL 미리보기 (저장 전)", description = "등록·수정과 같은 검증 후 CREATE VIEW 를 만든다. 저장·실행하지 않는다. viewId : 수정 중인 VIEW")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/ddl/preview")
    public ResponseEntity<TableDdlDto> preview(@AuthenticationPrincipal AccountDto accountDto,
                                               @RequestParam(required = false, value = "viewId") Long viewId,
                                               @RequestBody ViewDto.Save request) {
        return ResponseEntity.ok(viewService.preview(accountDto, viewId, request));
    }

    @Operation(summary = "VIEW 상세", description = "확정 정의(기본 정보·정의 SQL·출력 컬럼·참조 객체), 변경 요청 이력, 이 VIEW 를 참조하는 VIEW")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/{id}")
    public ResponseEntity<ViewDto.Detail> detail(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(viewService.detail(accountDto, id));
    }

    @Operation(summary = "VIEW DDL", description = "CREATE VIEW (확정 정의 기준, 프로젝트 대상 DBMS). 실행하지 않는다")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/{id}/ddl")
    public ResponseEntity<TableDdlDto> ddl(@AuthenticationPrincipal AccountDto accountDto,
                                           @PathVariable(name = "id") Long id,
                                           @RequestParam(required = false, value = "defaultSchemaName") String defaultSchemaName) {
        return ResponseEntity.ok(viewService.ddl(accountDto, id, defaultSchemaName));
    }

    @Operation(summary = "VIEW 등록", description = "승인 권한자가 등록하면 바로 승인")
    @PreAuthorize("hasRole('USER')")
    @PostMapping
    public ResponseEntity<ViewDto.SaveResult> create(@AuthenticationPrincipal AccountDto accountDto, @RequestBody ViewDto.Save request) {
        return ResponseEntity.ok(viewService.create(accountDto, request));
    }

    @Operation(summary = "VIEW 수정", description = "미승인 VIEW 는 바로 반영. 승인된 VIEW 는 변경 요청(정의 전체)을 만들고 승인 권한자의 요청은 즉시 승인된다")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/{id}")
    public ResponseEntity<ViewDto.SaveResult> update(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                     @RequestBody ViewDto.Save request) {
        return ResponseEntity.ok(viewService.update(accountDto, id, request));
    }

    @Operation(summary = "VIEW 삭제", description = "승인된 VIEW 와 다른 VIEW 가 참조하는 VIEW 는 삭제할 수 없다")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        viewService.delete(accountDto, id);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "VIEW 승인/승인 취소", description = "승인 권한(관리자·매니저·프로젝트 관리자)만. 승인 대기 변경 요청이 있으면 승인 취소 불가")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/{id}/approval/{isApproval}")
    public ResponseEntity<Map<String, Object>> approval(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                        @PathVariable(name = "isApproval") boolean isApproval) {
        viewService.updateApproval(accountDto, id, isApproval);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "VIEW 변경 요청 승인/반려", description = "approve true = 정의 전체를 한 번에 반영, false = 확정 정의 유지. 승인 권한자만")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/request/{requestId}/{approve}")
    public ResponseEntity<ViewDto.ChangeRequest> processRequest(@AuthenticationPrincipal AccountDto accountDto,
                                                                @PathVariable(name = "requestId") Long requestId,
                                                                @PathVariable(name = "approve") boolean approve) {
        return ResponseEntity.ok(viewService.processRequest(accountDto, requestId, approve));
    }
}
