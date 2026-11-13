package com.lexorma.source.collection.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.service.SourceCatalogRules;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import com.lexorma.source.collection.domain.enums.CollectionType;
import com.lexorma.source.collection.service.SourceRefreshService;
import com.lexorma.source.collection.service.SourceSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 원본 카탈로그 수집 실행·변경 이력·과거 상태 조회 (VIEWER 이상, 로그인한 프로젝트의 것만, AI-1c). 과거 상태는 Lexorma 에 저장된 버전으로 재구성한 것이고 원본 DB 를 그 시점으로 되돌린 것이 아니다.
 * 최신화 요청·취소 (AI-2b) 는 USER 이상 + 그 프로젝트의 관리자 또는 ADMIN (서비스가 지금의 DB 로 확인한다). 수집 결과를 받는 API 는 없다 : 서버가 원본 DB 를 직접 읽는다.
 */
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Source Collection API", description = "원본 카탈로그 수집 실행·변경 이력·과거 상태 조회 (Lexorma 에 저장된 것)")
public class SourceCollectionRestController {

    private final SourceSnapshotService snapshotService;
    private final SourceRefreshService refreshService;

    @Operation(summary = "수집 실행 목록", description = "최근 시작한 것부터. 쪽 단위 (size 최대 200).")
    @GetMapping("/connections/{id}/collections")
    public ResponseEntity<Map<String, Object>> executions(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                          @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "50") int size) {
        return PageableUtil.getGridPageableMap(snapshotService.executions(accountDto, id, page, size));
    }

    @Operation(summary = "수동 최신화 (원본 DB 수집)", description = "type = FULL(접속한 DB 의 시스템이 아닌 스키마 전체) | PARTIAL(schemas 의 스키마만). "
            + "비동기로 돌고 202 와 실행 ID 를 돌려준다. 진행 상태는 수집 실행 상세로 본다. 같은 연결의 수집이 진행 중이면 409.")
    @PostMapping("/connections/{id}/collections")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceRefreshService.Accepted> refresh(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                                 @RequestBody SourceCollectionDto.RefreshRequest request) {
        CollectionType type;
        try {
            type = CollectionType.valueOf(request == null || request.type() == null ? "" : request.type().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw SourceCatalogRules.invalid("수집 범위가 없습니다.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(refreshService.refresh(accountDto, id, type, request.schemas()));
    }

    @Operation(summary = "수집 취소", description = "진행 중인 수집을 CANCELLED 로 남기고 원본 조회를 멈춘다. 반영하지 않는다. 수집을 요청한 사람만.")
    @PostMapping("/collections/{executionId}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceCollectionDto.Execution> cancel(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "executionId") Long executionId) {
        return ResponseEntity.ok(refreshService.cancel(accountDto, executionId));
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
