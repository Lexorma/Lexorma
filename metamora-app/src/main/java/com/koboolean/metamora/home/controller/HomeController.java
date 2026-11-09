package com.koboolean.metamora.home.controller;

import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.home.domain.dto.DashboardDto;
import com.koboolean.metamora.home.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "Dashboard API", description = "대시보드 (승인 대기 건수 / 최근 활동 / 공지사항)")
public class HomeController {

    private final DashboardService dashboardService;

    @Operation(summary = "대시보드 데이터", description = "현재 프로젝트의 승인 대기 건수, 최근 변경 이력, 공지사항")
    @PreAuthorize("hasRole('NOT_APPROVE')")
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardDto> dashboard(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(dashboardService.selectDashboardData(accountDto));
    }
}
