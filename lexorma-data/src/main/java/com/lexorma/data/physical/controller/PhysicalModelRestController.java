package com.lexorma.data.physical.controller;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/physical")
@RequiredArgsConstructor
@Tag(name = "Physical Model API", description = "테이블 관계(복합 FK)·인덱스·제약조건 API")
public class PhysicalModelRestController {

    private final PhysicalModelService physicalModelService;

    @Operation(summary = "테이블 물리 모델 조회", description = "스키마, 나가는/들어오는 관계, 인덱스, 제약조건, 관계로 확정하지 못한 참조 테이블명")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/table/{tableId}")
    public ResponseEntity<PhysicalModelDto.TableModel> selectTableModel(@AuthenticationPrincipal AccountDto accountDto,
                                                                        @PathVariable(name = "tableId") Long tableId) {
        return ResponseEntity.ok(physicalModelService.selectTableModel(accountDto, tableId));
    }

    @Operation(summary = "관계 등록", description = "원본 테이블 컬럼 → 대상 테이블 컬럼 쌍(순서대로, 복합 FK 가능). relationType FK(기본)/LOGICAL")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/relation")
    public ResponseEntity<PhysicalModelDto.Relation> saveRelation(@AuthenticationPrincipal AccountDto accountDto,
                                                                  @RequestBody PhysicalModelDto.Relation relation) {
        return ResponseEntity.ok(physicalModelService.saveRelation(accountDto, relation));
    }

    @Operation(summary = "관계 FK 규칙 변경", description = "FK 의 ON DELETE · ON UPDATE 규칙 (NO_ACTION · RESTRICT · CASCADE · SET_NULL · SET_DEFAULT). 참조 컬럼 관계도 바꿀 수 있다. 승인된 관계는 승인 취소 후")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/relation/{id}/rules")
    public ResponseEntity<PhysicalModelDto.Relation> updateRelationRules(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                                         @RequestBody PhysicalModelDto.RelationRules rules) {
        return ResponseEntity.ok(physicalModelService.updateRelationRules(accountDto, id, rules));
    }

    @Operation(summary = "인덱스 등록", description = "컬럼 순서대로 (복합 인덱스 가능), isUnique 로 고유 인덱스")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/index")
    public ResponseEntity<PhysicalModelDto.Index> saveIndex(@AuthenticationPrincipal AccountDto accountDto,
                                                            @RequestBody PhysicalModelDto.Index index) {
        return ResponseEntity.ok(physicalModelService.saveIndex(accountDto, index));
    }

    @Operation(summary = "인덱스 수정", description = "미승인 인덱스는 바로 반영. 승인된 인덱스는 변경 요청(승인 대기)을 만들고 승인 권한자의 요청은 즉시 승인된다. "
            + "컬럼의 인덱스 여부로 만든 인덱스(indexSource COLUMN)는 컬럼 관리에서 바꾼다")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/index/{id}")
    public ResponseEntity<PhysicalModelDto.IndexSaveResult> updateIndex(@AuthenticationPrincipal AccountDto accountDto,
                                                                        @PathVariable(name = "id") Long id,
                                                                        @RequestBody PhysicalModelDto.Index index) {
        return ResponseEntity.ok(physicalModelService.updateIndex(accountDto, id, index));
    }

    @Operation(summary = "인덱스 변경 요청 승인/반려", description = "approve true = 승인(요청한 정의 반영), false = 반려(승인된 정의 유지). 승인 권한자만")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/index/request/{requestId}/{approve}")
    public ResponseEntity<PhysicalModelDto.ChangeRequest> processIndexChangeRequest(@AuthenticationPrincipal AccountDto accountDto,
                                                                                    @PathVariable(name = "requestId") Long requestId,
                                                                                    @PathVariable(name = "approve") boolean approve) {
        return ResponseEntity.ok(physicalModelService.processIndexChangeRequest(accountDto, requestId, approve));
    }

    @Operation(summary = "인덱스 목록 (Grid)", description = "프로젝트의 인덱스. schemaId·tableId·isApproval(Y/N)·indexSource(USER/COLUMN) 조건, searchColumn indexName / tableName / columnNames")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/index/data")
    public ResponseEntity<Map<String, Object>> selectIndexes(@AuthenticationPrincipal AccountDto accountDto,
                                                             @RequestParam(value = "page") int page,
                                                             @RequestParam(value = "size") int size,
                                                             @RequestParam(required = false, value = "sort") String sort,
                                                             @RequestParam(required = false, value = "searchColumn") String searchColumn,
                                                             @RequestParam(required = false, value = "searchQuery") String searchQuery,
                                                             @RequestParam(required = false, value = "schemaId") Long schemaId,
                                                             @RequestParam(required = false, value = "tableId") Long tableId,
                                                             @RequestParam(required = false, value = "isApproval") String isApproval,
                                                             @RequestParam(required = false, value = "indexSource") String indexSource) {
        return PageableUtil.getGridPageableMap(physicalModelService.selectIndexes(accountDto, PageableUtil.getGridPageable(page, size, sort),
                searchColumn, searchQuery, schemaId, tableId, isApproval, indexSource));
    }

    @Operation(summary = "인덱스 유형", description = "프로젝트 대상 DBMS 에서 DDL 로 낼 수 있는 인덱스 유형")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/index/types")
    public ResponseEntity<PhysicalModelDto.IndexTypes> indexTypes(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(physicalModelService.indexTypes(accountDto));
    }

    @Operation(summary = "인덱스 상세", description = "인덱스 정의·소속 스키마·테이블·변경 요청 이력")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/index/{id}")
    public ResponseEntity<PhysicalModelDto.IndexDetail> selectIndex(@AuthenticationPrincipal AccountDto accountDto,
                                                                   @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(physicalModelService.selectIndex(accountDto, id));
    }

    @Operation(summary = "인덱스 DDL 미리보기", description = "CREATE INDEX 문 하나 (프로젝트 대상 DBMS 문법, 실행하지 않는다)")
    @PreAuthorize("hasRole('VIEWER')")
    @GetMapping("/index/{id}/ddl")
    public ResponseEntity<TableDdlDto> indexDdl(@AuthenticationPrincipal AccountDto accountDto,
                                                @PathVariable(name = "id") Long id,
                                                @RequestParam(required = false, value = "defaultSchemaName") String defaultSchemaName) {
        return ResponseEntity.ok(physicalModelService.indexDdl(accountDto, id, defaultSchemaName));
    }

    @Operation(summary = "제약조건 등록", description = "UNIQUE(컬럼, 복합 가능) / CHECK(조건식)")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/constraint")
    public ResponseEntity<PhysicalModelDto.Constraint> saveConstraint(@AuthenticationPrincipal AccountDto accountDto,
                                                                      @RequestBody PhysicalModelDto.Constraint constraint) {
        return ResponseEntity.ok(physicalModelService.saveConstraint(accountDto, constraint));
    }

    @Operation(summary = "관계·인덱스·제약조건 삭제", description = "kind : relation / index / constraint. 승인된 항목은 삭제할 수 없다")
    @PreAuthorize("hasRole('USER')")
    @DeleteMapping("/{kind}/{id}")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AccountDto accountDto,
                                                      @PathVariable(name = "kind") String kind,
                                                      @PathVariable(name = "id") Long id) {
        physicalModelService.delete(accountDto, toKind(kind), id);
        return ResponseEntity.ok(Map.of("result", true));
    }

    @Operation(summary = "관계·인덱스·제약조건 승인/승인 취소", description = "승인 권한(관리자·매니저·프로젝트 관리자)만 가능")
    @PreAuthorize("hasRole('USER')")
    @PatchMapping("/{kind}/{id}/approval/{isApproval}")
    public ResponseEntity<Map<String, Object>> updateApproval(@AuthenticationPrincipal AccountDto accountDto,
                                                              @PathVariable(name = "kind") String kind,
                                                              @PathVariable(name = "id") Long id,
                                                              @PathVariable(name = "isApproval") boolean isApproval) {
        physicalModelService.updateApproval(accountDto, toKind(kind), id, isApproval);
        return ResponseEntity.ok(Map.of("result", true));
    }

    private static PhysicalModelService.Kind toKind(String kind) {
        try {
            return PhysicalModelService.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "relation / index / constraint 중 하나입니다.");
        }
    }
}
