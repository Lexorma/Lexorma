package com.lexorma.data.sourcemapping.controller;

import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceDeletionDto;
import com.lexorma.data.sourcemapping.service.SourceDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원본 삭제 후보 선택 반영 (DEC-04). 후보 조회는 VIEWER 이상, 계획·확정은 USER 이상 + 그 프로젝트의 관리자 또는 ADMIN (서비스가 지금의 DB 로 확인한다).
 * 원본 DB 는 바꾸지 않고, 사용자가 고른 미승인 모델 객체만 지운다. 요청 로그 본문은 남기지 않는다.
 */
@RestController
@RequestMapping("/api/sourceMapping/deletions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@ProtectedLogBody
@Tag(name = "Source Deletion API", description = "원본에서 없어진 객체의 삭제 후보와 선택 삭제 (DEC-04)")
public class SourceDeletionRestController {

    private final SourceDeletionService service;

    @Operation(summary = "삭제 후보", description = "원본에서 사라진 TABLE 매핑의 모델 TABLE(이름 변경 후보 없음), 매핑된 TABLE 에서 모델에만 있는 컬럼. 고를 수 없는 이유·영향 포함.")
    @GetMapping
    public ResponseEntity<SourceDeletionDto.Candidates> candidates(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(service.candidates(accountDto));
    }

    @Operation(summary = "삭제 계획 미리보기", description = "고른 항목의 영향과 적용 가능 여부, planToken (읽기 전용).")
    @PostMapping("/plan")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceDeletionDto.Plan> plan(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceDeletionDto.Request request) {
        return ResponseEntity.ok(service.plan(accountDto, request));
    }

    @Operation(summary = "삭제 확정", description = "잠금 뒤 다시 계획해 planToken 이 같을 때만 지운다 (직전 모델 버전 DELETE-요청 ID). 급감 경고가 있으면 acknowledgeDrop=true 가 필요하다.")
    @PostMapping("/apply")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceDeletionDto.Result> apply(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceDeletionDto.Request request) {
        return ResponseEntity.ok(service.apply(accountDto, request));
    }
}
