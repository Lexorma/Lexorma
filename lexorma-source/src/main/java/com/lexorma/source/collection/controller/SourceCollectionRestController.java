package com.lexorma.source.collection.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.service.SourceSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 원본 카탈로그 수집 실행·변경 이력·과거 상태 조회 (VIEWER 이상, 로그인한 프로젝트의 것만, AI-1c). 읽기만 한다 :
 * 수집 결과를 받는 API 는 없다 (수집은 서버 안의 수집 경로가 한다). 과거 상태는 Lexorma 에 저장된 버전으로 재구성한 것이고 원본 DB 를 그 시점으로 되돌린 것이 아니다.
 */
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Source Collection API", description = "원본 카탈로그 수집 실행·변경 이력·과거 상태 조회 (Lexorma 에 저장된 것)")
public class SourceCollectionRestController {

    private final SourceSnapshotService snapshotService;

    @Operation(summary = "수집 실행 목록", description = "최근 시작한 것부터. 쪽 단위 (size 최대 200).")
    @GetMapping("/connections/{id}/collections")
    public ResponseEntity<Map<String, Object>> executions(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                          @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "50") int size) {
        return PageableUtil.getGridPageableMap(snapshotService.executions(accountDto, id, page, size));
    }

    @Operation(summary = "수집 실행 상세", description = "실행 상태·건수와 대상(스키마)별 결과.")
    @GetMapping("/collections/{executionId}")
    public ResponseEntity<SourceCollectionDto.ExecutionDetail> execution(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "executionId") Long executionId) {
        return ResponseEntity.ok(snapshotService.execution(accountDto, executionId));
    }

    @Operation(summary = "수집 실행의 변경 이력", description = "객체별 추가·수정·삭제와 바뀐 항목, 변경 전후. 쪽 단위 (size 최대 200).")
    @GetMapping("/collections/{executionId}/changes")
    public ResponseEntity<Map<String, Object>> changes(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "executionId") Long executionId,
                                                       @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "50") int size) {
        return PageableUtil.getGridPageableMap(snapshotService.changes(accountDto, executionId, page, size));
    }

    @Operation(summary = "과거 시점의 스키마 목록", description = "order = 반영 순서, at = 시각 (그 시각까지 반영된 마지막 수집). 둘 다 없으면 마지막 반영.")
    @GetMapping("/connections/{id}/snapshot")
    public ResponseEntity<SourceCollectionDto.CatalogAt> catalogAt(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                                   @RequestParam(value = "order", required = false) Integer order,
                                                                   @RequestParam(value = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(snapshotService.catalogAt(accountDto, id, order, at));
    }

    @Operation(summary = "과거 시점의 스키마의 테이블 목록", description = "order·at 은 스키마 목록과 같다.")
    @GetMapping("/connections/{id}/snapshot/schemas/{schemaId}/tables")
    public ResponseEntity<List<SourceCollectionDto.TableState>> tablesAt(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                                         @PathVariable(name = "schemaId") Long schemaId,
                                                                         @RequestParam(value = "order", required = false) Integer order,
                                                                         @RequestParam(value = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(snapshotService.tablesAt(accountDto, id, schemaId, order, at));
    }

    @Operation(summary = "과거 시점의 테이블", description = "컬럼·제약조건·인덱스·관계를 담은 정규화한 내용. 그때 없던 테이블이면 404.")
    @GetMapping("/tables/{tableId}/snapshot")
    public ResponseEntity<SourceCollectionDto.TableSnapshot> tableAt(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "tableId") Long tableId,
                                                                     @RequestParam(value = "order", required = false) Integer order,
                                                                     @RequestParam(value = "at", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(snapshotService.tableAt(accountDto, tableId, order, at));
    }

    @Operation(summary = "테이블의 버전 목록", description = "바뀐 수집에서만 생긴 버전 (BASE·ADDED·MODIFIED·REMOVED).")
    @GetMapping("/tables/{tableId}/versions")
    public ResponseEntity<List<SourceCollectionDto.TableVersion>> tableVersions(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "tableId") Long tableId) {
        return ResponseEntity.ok(snapshotService.tableVersions(accountDto, tableId));
    }

    @Operation(summary = "테이블의 변경 이력", description = "여러 수집에 걸친 그 테이블과 하위 객체의 변경.")
    @GetMapping("/tables/{tableId}/changes")
    public ResponseEntity<List<SourceCollectionDto.Change>> tableChanges(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "tableId") Long tableId) {
        return ResponseEntity.ok(snapshotService.tableChanges(accountDto, tableId));
    }
}
