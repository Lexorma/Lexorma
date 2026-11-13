package com.lexorma.data.erd.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.service.ErdService;
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

/** 물리 ERD : 조회 전용 + 내 레이아웃. 레이아웃은 개인 화면 설정이라 VIEWER 도 저장할 수 있다 (물리 모델은 바뀌지 않음) */
@RestController
@RequestMapping("/api/erd")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Physical ERD API", description = "물리 ERD 조회 (테이블·컬럼·관계 일괄) / 내 레이아웃 저장")
public class ErdRestController {

    private final ErdService erdService;
    private final PhysicalViewService viewService;

    @Operation(summary = "ERD 테이블 범위 조회", description = "스키마·검색어(물리명/논리명/스키마명)·승인·마스터 조건, 페이지당 최대 300 테이블. 범위 테이블이 한쪽인 관계 포함")
    @GetMapping
    public ResponseEntity<ErdDto.Graph> selectGraph(@AuthenticationPrincipal AccountDto accountDto,
                                                    @RequestParam(value = "schemaId", required = false) Long schemaId,
                                                    @RequestParam(value = "keyword", required = false) String keyword,
                                                    @RequestParam(value = "isApproval", required = false) Boolean isApproval,
                                                    @RequestParam(value = "isMasterTable", required = false) Boolean isMasterTable,
                                                    @RequestParam(value = "page", defaultValue = "0") int page,
                                                    @RequestParam(value = "size", defaultValue = "100") int size) {
        return ResponseEntity.ok(erdService.selectGraph(accountDto, new ErdDto.Search(schemaId, keyword, isApproval, isMasterTable), page, size));
    }

    @Operation(summary = "ERD 이웃 테이블 조회", description = "선택 테이블에서 관계로 1~3단계 이어진 테이블 (최대 300)")
    @GetMapping("/neighbors/{tableId}")
    public ResponseEntity<ErdDto.Graph> selectNeighbors(@AuthenticationPrincipal AccountDto accountDto,
                                                        @PathVariable(name = "tableId") Long tableId,
                                                        @RequestParam(value = "depth", defaultValue = "1") int depth) {
        return ResponseEntity.ok(erdService.selectNeighbors(accountDto, tableId, depth));
    }

    @Operation(summary = "ERD VIEW 노드 조회", description = "스키마 조건의 VIEW (이름순 최대 300) : 출력 컬럼·참조 객체 포함, 읽기 전용 (M-15). VIEW 참조는 FK 관계가 아니다")
    @GetMapping("/views")
    public ResponseEntity<List<ViewDto.ErdView>> selectViews(@AuthenticationPrincipal AccountDto accountDto,
                                                             @RequestParam(value = "schemaId", required = false) Long schemaId) {
        return ResponseEntity.ok(viewService.erdViews(accountDto, schemaId));
    }

    @Operation(summary = "내 ERD 레이아웃 조회", description = "저장한 적 없으면 version·viewport 가 null")
    @GetMapping("/layout")
    public ResponseEntity<ErdDto.Layout> selectLayout(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(erdService.selectLayout(accountDto));
    }

    @Operation(summary = "내 ERD 레이아웃 저장", description = "nodes 는 기존 좌표에 합친다. 조회한 version 이 아니면 409 ERD_LAYOUT_CONFLICT")
    @PutMapping("/layout")
    public ResponseEntity<ErdDto.Layout> saveLayout(@AuthenticationPrincipal AccountDto accountDto,
                                                    @RequestBody ErdDto.Layout layout) {
        return ResponseEntity.ok(erdService.saveLayout(accountDto, layout));
    }
}
