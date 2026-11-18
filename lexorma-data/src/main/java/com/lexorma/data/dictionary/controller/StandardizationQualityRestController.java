package com.lexorma.data.dictionary.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto;
import com.lexorma.data.dictionary.domain.dto.IdentifierQualityDto;
import com.lexorma.data.dictionary.domain.dto.StandardizationQualityDto;
import com.lexorma.data.dictionary.domain.dto.TermQualityDto;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.service.ColumnNameSuggestionService;
import com.lexorma.data.dictionary.service.IdentifierQualityService;
import com.lexorma.data.dictionary.service.StandardizationQualityService;
import com.lexorma.data.dictionary.service.TermQualityService;
import com.lexorma.data.table.ddl.DbIdentifierRules;
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

/** 표준화 품질 진단 (조회 전용). 사용 현황·물리 ERD 와 같은 권한(VIEWER)이고, 목록은 그리드 조회 규격(page·size·sort·searchColumn·searchQuery)을 따른다 */
@RestController
@RequestMapping("/api/standardizationQuality")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Standardization Quality API", description = "표준화 품질 진단 : 컬럼(표준 용어 미연결·컬럼명 불일치·도메인 정의 불일치), 테이블명, 표준 용어 중복·유사어, 컬럼명 개선 후보, DBMS 식별자")
public class StandardizationQualityRestController {

    private final StandardizationQualityService standardizationQualityService;
    private final TermQualityService termQualityService;
    private final ColumnNameSuggestionService columnNameSuggestionService;
    private final IdentifierQualityService identifierQualityService;

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

    @Operation(summary = "모델 진단 요약", description = "컬럼 진단 요약(기존 요약과 같은 값) + 테이블명 진단 건수 + 심각도별 건수")
    @GetMapping("/model/summary")
    public ResponseEntity<StandardizationQualityDto.ModelSummary> selectModelSummary(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(standardizationQualityService.selectModelSummary(accountDto));
    }

    @Operation(summary = "모델 진단 목록", description = "컬럼·테이블 진단. 검색 : tableName / columnName, 조건 : type(규칙 코드), severity, targetType, schemaId, isApproval")
    @GetMapping("/model/data")
    public ResponseEntity<Map<String, Object>> selectModelIssues(@AuthenticationPrincipal AccountDto accountDto,
                                                                 @RequestParam(value = "page") int page,
                                                                 @RequestParam(value = "size") int size,
                                                                 @RequestParam(value = "sort", required = false) String sort,
                                                                 @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                 @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                 @RequestParam(value = "type", required = false) StandardizationRule type,
                                                                 @RequestParam(value = "severity", required = false) StandardizationRule.Severity severity,
                                                                 @RequestParam(value = "targetType", required = false) StandardizationRule.TargetType targetType,
                                                                 @RequestParam(value = "schemaId", required = false) Long schemaId,
                                                                 @RequestParam(value = "isApproval", required = false) Boolean isApproval) {
        return PageableUtil.getGridPageableMap(standardizationQualityService.selectModelIssues(accountDto,
                new StandardizationQualityDto.ModelSearch(type, severity, targetType, schemaId, isApproval, searchColumn, searchQuery),
                PageableUtil.getGridPageable(page, size, sort)));
    }

    @Operation(summary = "표준 용어 진단 요약", description = "전체 용어 수, 한 그룹 이상에 속한 용어 수, 규칙별 그룹 수, 심각도별 그룹 수")
    @GetMapping("/terms/summary")
    public ResponseEntity<TermQualityDto.Summary> selectTermSummary(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(termQualityService.selectSummary(accountDto));
    }

    @Operation(summary = "표준 용어 진단 목록", description = "중복·유사어 그룹. 검색 : termName / abbreviation, 조건 : type(규칙 코드), severity")
    @GetMapping("/terms/data")
    public ResponseEntity<Map<String, Object>> selectTermGroups(@AuthenticationPrincipal AccountDto accountDto,
                                                                @RequestParam(value = "page") int page,
                                                                @RequestParam(value = "size") int size,
                                                                @RequestParam(value = "sort", required = false) String sort,
                                                                @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                @RequestParam(value = "type", required = false) StandardizationRule type,
                                                                @RequestParam(value = "severity", required = false) StandardizationRule.Severity severity) {
        return PageableUtil.getGridPageableMap(termQualityService.selectGroups(accountDto,
                new TermQualityDto.Search(type, severity, searchColumn, searchQuery), PageableUtil.getGridPageable(page, size, sort)));
    }

    @Operation(summary = "DBMS 식별자 진단 요약", description = "프로젝트 DBMS 기준 스키마·테이블·컬럼 이름 길이·문자, DDL 제약조건명 잘림·충돌 (M-7 보강 1차). 기존 요약·모델 진단과 따로 집계한다")
    @GetMapping("/identifiers/summary")
    public ResponseEntity<IdentifierQualityDto.Summary> selectIdentifierSummary(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(identifierQualityService.selectSummary(accountDto));
    }

    @Operation(summary = "DBMS 식별자 진단 목록", description = "검색 : tableName / name, 조건 : type(규칙 코드), severity, kind(대상 종류), schemaId")
    @GetMapping("/identifiers/data")
    public ResponseEntity<Map<String, Object>> selectIdentifierIssues(@AuthenticationPrincipal AccountDto accountDto,
                                                                      @RequestParam(value = "page") int page,
                                                                      @RequestParam(value = "size") int size,
                                                                      @RequestParam(value = "sort", required = false) String sort,
                                                                      @RequestParam(value = "searchColumn", required = false) String searchColumn,
                                                                      @RequestParam(value = "searchQuery", required = false) String searchQuery,
                                                                      @RequestParam(value = "type", required = false) StandardizationRule type,
                                                                      @RequestParam(value = "severity", required = false) StandardizationRule.Severity severity,
                                                                      @RequestParam(value = "kind", required = false) DbIdentifierRules.Kind kind,
                                                                      @RequestParam(value = "schemaId", required = false) Long schemaId) {
        return PageableUtil.getGridPageableMap(identifierQualityService.selectIssues(accountDto,
                new IdentifierQualityDto.Search(type, severity, kind, schemaId, searchColumn, searchQuery), PageableUtil.getGridPageable(page, size, sort)));
    }

    @Operation(summary = "컬럼명 개선 후보", description = "표준 용어 미연결·컬럼명 불일치 컬럼의 후보 최대 5건 (점수 순, 추천만 — 아무것도 바꾸지 않는다). 다른 프로젝트의 컬럼이면 오류")
    @GetMapping("/columns/{columnId}/suggestions")
    public ResponseEntity<ColumnSuggestionDto.Suggestions> selectColumnSuggestions(@AuthenticationPrincipal AccountDto accountDto,
                                                                                   @PathVariable(value = "columnId") Long columnId) {
        return ResponseEntity.ok(columnNameSuggestionService.suggest(accountDto, columnId));
    }
}
