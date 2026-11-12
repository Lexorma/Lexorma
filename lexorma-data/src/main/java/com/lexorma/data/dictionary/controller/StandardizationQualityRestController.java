package com.lexorma.data.dictionary.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 표준화 품질 진단 (조회 전용). 사용 현황·물리 ERD 와 같은 권한(VIEWER)이고, 목록은 그리드 조회 규격(page·size·sort·searchColumn·searchQuery)을 따른다 */
@RestController
@RequestMapping("/api/standardizationQuality")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Standardization Quality API", description = "표준화 품질 진단 : 표준 용어 미연결·컬럼명 불일치·도메인 정의 불일치")
public class StandardizationQualityRestController {

    private final StandardizationQualityService standardizationQualityService;

    @Operation(summary = "진단 요약", description = "검사 대상 컬럼 수, 문제가 있는 컬럼 수, 진단 유형별 건수 (한 컬럼에 여러 문제가 있을 수 있다)")
    @GetMapping("/summary")
    public ResponseEntity<StandardizationQualityDto.Summary> selectSummary(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(standardizationQualityService.selectSummary(accountDto));
    }

    @Operation(summary = "진단 목록", description = "검색 : tableName / columnName, 조건 : type, schemaId, isApproval")
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> selectIssues(@AuthenticationPrincipal AccountDto accountDto,
                                                            @RequestParam(value = "page") int page,
                                                            @RequestParam(value = "size") int size,
                                                            @RequestParam(value = "sort", required = false) String sort,
                                                            @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                            @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                            @RequestParam(value = "type", required = false) StandardizationQualityDto.IssueType type,
                                                            @RequestParam(value = "schemaId", required = false) Long schemaId,
                                                            @RequestParam(value = "isApproval", required = false) Boolean isApproval) {
        return PageableUtil.getGridPageableMap(standardizationQualityService.selectIssues(accountDto,
                new StandardizationQualityDto.Search(type, schemaId, isApproval, searchColumn, searchQuery), PageableUtil.getGridPageable(page, size, sort)));
    }
}
