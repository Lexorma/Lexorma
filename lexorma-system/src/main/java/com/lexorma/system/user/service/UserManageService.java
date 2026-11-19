package com.lexorma.system.user.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 시스템 관리 > 사용자 관리
 */
public interface UserManageService {
    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> USER_SORT_FIELDS = java.util.Set.of("id", "username", "name");


    List<String> getUserRoles();

    Page<AccountDto> selectUserData(Pageable pageable, AccountDto accountDto, String searchQuery, String searchColumn);

    void saveUser(List<AccountDto> accountDtos);

    void deleteUser(List<AccountDto> accountDtos);

    void saveUserPassword(AccountDto accountDto);
}
