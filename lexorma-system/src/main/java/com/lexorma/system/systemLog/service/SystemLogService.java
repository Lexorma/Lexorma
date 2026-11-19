package com.lexorma.system.systemLog.service;

import com.lexorma.core.logs.domain.dto.LogsDto;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


public interface SystemLogService {
    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> LOG_SORT_FIELDS = java.util.Set.of("id", "logUrl", "method", "ip", "username", "statusCode", "timestamp", "roleName", "requestBody", "responseBody", "errorMessage", "userAgent");


    Page<LogsDto> getSystemLogData(Pageable pageable, AccountDto accountDto, String searchQuery, String searchColumn);
}
