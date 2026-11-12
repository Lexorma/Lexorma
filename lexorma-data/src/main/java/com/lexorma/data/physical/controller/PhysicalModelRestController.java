package com.lexorma.data.physical.controller;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
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

    @Operation(summary = "인덱스 등록", description = "컬럼 순서대로 (복합 인덱스 가능), isUnique 로 고유 인덱스")
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/index")
    public ResponseEntity<PhysicalModelDto.Index> saveIndex(@AuthenticationPrincipal AccountDto accountDto,
                                                            @RequestBody PhysicalModelDto.Index index) {
        return ResponseEntity.ok(physicalModelService.saveIndex(accountDto, index));
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
