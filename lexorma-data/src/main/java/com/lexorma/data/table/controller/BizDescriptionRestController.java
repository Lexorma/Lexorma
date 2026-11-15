package com.lexorma.data.table.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.service.BizDescriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업무 설명(TASK_EXPLN) 직접 수정 : 테이블·컬럼 상세, 물리 ERD 패널의 업무 설명 칸. 메뉴 버튼 biz-description-edit(USER) 와 같은 역할
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Business Description API", description = "테이블·컬럼 업무 설명 직접 작성·수정·삭제 (AI 없이)")
public class BizDescriptionRestController {

    private final BizDescriptionService service;

    @Operation(summary = "업무 설명 저장", description = "bizDescription 빈 문자열이면 지운다. base(화면이 읽은 값)와 지금 값이 다르면 409 BIZ_DESCRIPTION_CONFLICT")
    @PreAuthorize("hasRole('USER')")
    @PutMapping("/bizDescription")
    public ResponseEntity<BizDescriptionDto.Result> update(@AuthenticationPrincipal AccountDto account, @RequestBody BizDescriptionDto.Request request) {
        return ResponseEntity.ok(service.update(account, request));
    }
}
