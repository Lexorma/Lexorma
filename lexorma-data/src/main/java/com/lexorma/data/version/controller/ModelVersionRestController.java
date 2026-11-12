package com.lexorma.data.version.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.version.domain.dto.ModelVersionDto;
import com.lexorma.data.version.service.ModelVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.util.Map;

/**
 * 모델 버전 (M-9 3차). 만들기와 조회만 있다 : 저장된 버전을 고치거나 지우는 API 는 두지 않는다.
 */
@RestController
@RequestMapping("/api/modelVersion")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Model Version API", description = "모델 버전(특정 시점의 프로젝트 모델 전체 구조) 생성·조회·비교 API")
public class ModelVersionRestController {

    private final ModelVersionService versionService;

    @Operation(summary = "버전 목록", description = "로그인한 프로젝트의 버전을 최신 순으로 조회한다. 모델 내용은 포함하지 않고 대상 종류별 객체 수(counts)만 준다.")
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> search(@AuthenticationPrincipal AccountDto accountDto,
                                                      @RequestParam(value = "page") int page,
                                                      @RequestParam(value = "size") int size) {
        return PageableUtil.getGridPageableMap(versionService.search(accountDto, page, size));
    }

    @Operation(summary = "버전 생성", description = "현재 프로젝트의 모델 전체(스키마, 물리·논리 모델, 논리·물리 매핑)를 새 버전으로 저장한다. 프로젝트 안에서 버전명은 하나뿐이다.")
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ModelVersionDto.Summary> create(@AuthenticationPrincipal AccountDto accountDto, @RequestBody ModelVersionDto.Create request) {
        return ResponseEntity.ok(versionService.create(accountDto, request));
    }

    @Operation(summary = "버전 비교", description = "기준 버전(baseId)과 대상 버전(targetId)을 비교한다. targetId 를 주지 않으면 현재 모델과 비교한다. "
            + "객체는 내부 ID 로 짝지어 ADDED·REMOVED·MODIFIED 로 판정하고, 달라진 객체만 변경 전후 상태와 함께 준다.")
    @GetMapping("/compare")
    public ResponseEntity<ModelVersionDto.Comparison> compare(@AuthenticationPrincipal AccountDto accountDto,
                                                              @RequestParam(value = "baseId") Long baseId,
                                                              @RequestParam(value = "targetId", required = false) Long targetId) {
        return ResponseEntity.ok(versionService.compare(accountDto, baseId, targetId));
    }

    @Operation(summary = "버전 비교 ERD", description = "버전 비교 결과를 ERD 로 그릴 범위. kind = PHYSICAL(테이블·컬럼·관계) 또는 LOGICAL(엔터티·속성·관계). "
            + "판정은 버전 비교와 같고, 모델 전체가 아니라 달라진 노드와 관계로 이어진 이웃만 limit(기본 100, 최대 300)개까지 준다. "
            + "focusType·focusId 로 객체를 지정하면 그 객체가 속한 노드와 이웃부터 채운다. 조회만 하며 버전도 현재 모델도 바꾸지 않는다.")
    @GetMapping("/compare/erd")
    public ResponseEntity<ModelVersionDto.ErdComparison> compareErd(@AuthenticationPrincipal AccountDto accountDto,
                                                                    @RequestParam(value = "baseId") Long baseId,
                                                                    @RequestParam(value = "targetId", required = false) Long targetId,
                                                                    @RequestParam(value = "kind") String kind,
                                                                    @RequestParam(value = "focusType", required = false) String focusType,
                                                                    @RequestParam(value = "focusId", required = false) Long focusId,
                                                                    @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(versionService.compareErd(accountDto, baseId, targetId, kind, focusType, focusId, limit));
    }

    @Operation(summary = "버전 상세", description = "버전명·설명·생성자·생성 일시·대상 종류별 객체 수.")
    @GetMapping("/{id}")
    public ResponseEntity<ModelVersionDto.Summary> detail(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(versionService.detail(accountDto, id));
    }

    @Operation(summary = "버전의 모델 내용", description = "버전을 만든 시점의 모델 전체 : 대상 종류 → [{id, name, state}]. 지금의 모델을 다시 읽지 않고 저장된 내용을 그대로 준다.")
    @GetMapping("/{id}/snapshot")
    public ResponseEntity<ModelVersionDto.Snapshot> snapshot(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(versionService.snapshot(accountDto, id));
    }
}
