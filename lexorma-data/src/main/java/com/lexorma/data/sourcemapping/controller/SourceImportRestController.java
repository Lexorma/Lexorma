package com.lexorma.data.sourcemapping.controller;

import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.service.SourceImportService;
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

/**
 * 원본 메타데이터 선택 가져오기 (M-4e). 대상 조회·계획(미리보기)·이력은 VIEWER 이상 (읽기 전용, 계획도 저장하지 않는다).
 * 확정은 USER 이상 + 그 프로젝트의 관리자 또는 ADMIN (서비스가 지금의 DB 로 확인한다 — 매핑 변경과 같은 기준, 물리 모델 변경 역할 USER 보다 엄격하다).
 * 원본 DB 에 접속하지 않는다 (마지막 반영 수집만 읽는다). 요청·응답 본문(원본 식·대량 계획)은 요청 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/sourceImport")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@ProtectedLogBody
@Tag(name = "Source Import API", description = "원본 DB 메타데이터를 고른 항목만 물리 모델로 가져오기 (대상 → 계획 → 확정, M-4d 비교 기준)")
public class SourceImportRestController {

    private final SourceImportService service;

    @Operation(summary = "가져오기 대상", description = "원본 TABLE(key = S+원본 TABLE ID) 하나의 고를 수 있는 항목과 고를 수 없는 이유.")
    @GetMapping("/candidates")
    public ResponseEntity<SourceImportDto.TableCandidates> candidates(@AuthenticationPrincipal AccountDto accountDto, @RequestParam("key") String key,
                                                                      @RequestParam(value = "connectionId", required = false) Long connectionId,
                                                                      @RequestParam(value = "reflectedOrder", required = false) Integer reflectedOrder) {
        return ResponseEntity.ok(service.candidates(accountDto, connectionId, reflectedOrder, key));
    }

    @Operation(summary = "가져오기 계획 (미리보기)", description = "고른 항목의 변경 전·후, 적용 가능 여부, 건수, planToken·requestId. 저장하지 않는다.")
    @PostMapping("/plan")
    public ResponseEntity<SourceImportDto.Plan> plan(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceImportDto.Request request) {
        return ResponseEntity.ok(service.plan(accountDto, request));
    }

    @Operation(summary = "가져오기 확정", description = "미리보기의 planToken·requestId 로 확정. 잠금 뒤 다시 계획해 같을 때만 한 트랜잭션으로 반영 (다르면 409 SOURCE_COMPARE_STALE).")
    @PostMapping("/apply")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceImportDto.Result> apply(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceImportDto.Request request) {
        return ResponseEntity.ok(service.apply(accountDto, request));
    }

    @Operation(summary = "가져오기 이력", description = "이 프로젝트의 가져오기 실행 (최신 순).")
    @GetMapping("/executions")
    public ResponseEntity<SourceImportDto.ExecutionPage> executions(@AuthenticationPrincipal AccountDto accountDto,
                                                                    @RequestParam(value = "page", defaultValue = "0") int page,
                                                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(service.executions(accountDto, page, size));
    }

    @Operation(summary = "가져오기 이력 상세", description = "실행 정보와 그 변경 그룹의 모델 변경 이력 (객체·속성별 변경 전후).")
    @GetMapping("/executions/{id}")
    public ResponseEntity<SourceImportDto.ExecutionDetail> execution(@AuthenticationPrincipal AccountDto accountDto, @PathVariable("id") Long id) {
        return ResponseEntity.ok(service.execution(accountDto, id));
    }
}
