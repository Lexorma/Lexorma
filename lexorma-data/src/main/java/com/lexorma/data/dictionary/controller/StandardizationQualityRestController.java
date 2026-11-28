package com.lexorma.data.dictionary.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.ColumnSuggestionDto;
import com.lexorma.data.dictionary.service.ColumnNameSuggestionService;
import com.lexorma.data.dictionary.service.StandardDomainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 표준화 품질 진단의 보조 조회 (VIEWER). 규칙 판정 결과(모델·용어·식별자 진단)는 AI-6 3차부터 저장 품질 검사({@code /api/qualityInspection/**})로만 본다 —
 * 조회할 때마다 계산하던 요약·목록 API(summary·data·model·terms·identifiers)는 화면과 함께 지웠다 (AI6-3-02·03). 남은 것은 저장 검사로 대체되지 않는 두 기능이다 :
 * 표준 도메인 이름 중복 보고([DICT-2], 규칙이 아니다)와 컬럼 1개의 컬럼명 개선 후보(추천만).
 */
@RestController
@RequestMapping("/api/standardizationQuality")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Standardization Quality API", description = "표준화 품질 진단 보조 : 표준 도메인 이름 중복 보고, 컬럼명 개선 후보 (규칙 판정 결과는 Quality Inspection API)")
public class StandardizationQualityRestController {

    private final ColumnNameSuggestionService columnNameSuggestionService;
    private final StandardDomainService standardDomainService;

    @Operation(summary = "표준 도메인 이름 중복", description = "로그인 프로젝트에서 이름이 대소문자를 무시하고 같은 도메인 묶음 (예전 데이터 진단, [DICT-2]). 조회만 한다 — 지우거나 합치지 않는다")
    @GetMapping("/domains/duplicates")
    public ResponseEntity<Map<String, Object>> selectDomainDuplicates(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(Map.of("groups", standardDomainService.findDuplicateGroups(accountDto.getProjectId())));
    }

    @Operation(summary = "컬럼명 개선 후보", description = "표준 용어 미연결·컬럼명 불일치 컬럼의 후보 최대 5건 (점수 순, 추천만 — 아무것도 바꾸지 않는다). 다른 프로젝트의 컬럼이면 오류")
    @GetMapping("/columns/{columnId}/suggestions")
    public ResponseEntity<ColumnSuggestionDto.Suggestions> selectColumnSuggestions(@AuthenticationPrincipal AccountDto accountDto,
                                                                                   @PathVariable(value = "columnId") Long columnId) {
        return ResponseEntity.ok(columnNameSuggestionService.suggest(accountDto, columnId));
    }
}
