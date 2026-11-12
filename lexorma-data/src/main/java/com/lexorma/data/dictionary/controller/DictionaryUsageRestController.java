package com.lexorma.data.dictionary.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.DictionaryUsageDto;
import com.lexorma.data.dictionary.service.DictionaryUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 표준 항목 사용 현황 (조회 전용). 데이터 사전 목록과 같은 권한(VIEWER)이고, 그리드 조회 규격(page·size·sort·searchColumn·searchQuery)을 따른다 */
@RestController
@RequestMapping("/api/dictionaryUsage")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Dictionary Usage API", description = "표준 단어·용어·도메인 사용 현황 조회")
public class DictionaryUsageRestController {

    private final DictionaryUsageService dictionaryUsageService;

    @Operation(summary = "표준 용어 사용 컬럼", description = "표준 용어를 참조하는 컬럼. 검색 : tableName / columnName, 조건 : schemaId, isApproval")
    @GetMapping("/term/{termId}/data")
    public ResponseEntity<Map<String, Object>> selectTermColumns(@AuthenticationPrincipal AccountDto accountDto,
                                                                 @PathVariable(name = "termId") Long termId,
                                                                 @RequestParam(value = "page") int page,
                                                                 @RequestParam(value = "size") int size,
                                                                 @RequestParam(value = "sort", required = false) String sort,
                                                                 @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                 @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                 @RequestParam(value = "schemaId", required = false) Long schemaId,
                                                                 @RequestParam(value = "isApproval", required = false) Boolean isApproval) {
        return PageableUtil.getGridPageableMap(dictionaryUsageService.selectTermColumns(accountDto, termId,
                new DictionaryUsageDto.ColumnSearch(schemaId, isApproval, searchColumn, searchQuery), PageableUtil.getGridPageable(page, size, sort)));
    }

    @Operation(summary = "표준 도메인 사용 컬럼", description = "표준 도메인에 속한 표준 용어를 참조하는 컬럼 (컬럼은 도메인을 직접 참조하지 않는다)")
    @GetMapping("/domain/{domainId}/data")
    public ResponseEntity<Map<String, Object>> selectDomainColumns(@AuthenticationPrincipal AccountDto accountDto,
                                                                   @PathVariable(name = "domainId") Long domainId,
                                                                   @RequestParam(value = "page") int page,
                                                                   @RequestParam(value = "size") int size,
                                                                   @RequestParam(value = "sort", required = false) String sort,
                                                                   @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                   @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                   @RequestParam(value = "schemaId", required = false) Long schemaId,
                                                                   @RequestParam(value = "isApproval", required = false) Boolean isApproval) {
        return PageableUtil.getGridPageableMap(dictionaryUsageService.selectDomainColumns(accountDto, domainId,
                new DictionaryUsageDto.ColumnSearch(schemaId, isApproval, searchColumn, searchQuery), PageableUtil.getGridPageable(page, size, sort)));
    }

    @Operation(summary = "표준 단어 사용 용어", description = "표준 단어로 만든 표준 용어와 용어별 사용 컬럼 수. 검색 : termName / termAbbreviation")
    @GetMapping("/word/{wordId}/data")
    public ResponseEntity<Map<String, Object>> selectWordTerms(@AuthenticationPrincipal AccountDto accountDto,
                                                               @PathVariable(name = "wordId") Long wordId,
                                                               @RequestParam(value = "page") int page,
                                                               @RequestParam(value = "size") int size,
                                                               @RequestParam(value = "sort", required = false) String sort,
                                                               @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                               @RequestParam(value = "searchQuery", required = false) String searchQuery) {
        return PageableUtil.getGridPageableMap(dictionaryUsageService.selectWordTerms(accountDto, wordId, searchColumn, searchQuery,
                PageableUtil.getGridPageable(page, size, sort)));
    }
}
