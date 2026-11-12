package com.lexorma.data.history.controller;

import com.lexorma.common.utils.PageableUtil;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.history.domain.dto.ModelChangeHistoryDto;
import com.lexorma.data.history.service.ModelChangeHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 모델 변경 이력 조회 (M-9 1차). 조회만 있다 : 이력은 모델 변경과 함께 서버가 남기며 수정·삭제·임의 등록 API 는 두지 않는다.
 */
@RestController
@RequestMapping("/api/modelHistory")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Model Change History API", description = "물리 모델(테이블·컬럼·관계·인덱스·제약조건) 변경 이력 조회 API")
public class ModelChangeHistoryRestController {

    private final ModelChangeHistoryService historyService;

    @Operation(summary = "변경 이력 목록", description = "로그인한 프로젝트의 이력을 최신 순(변경 일시, 이력 ID 내림차순)으로 조회한다. "
            + "targetType(PHYSICAL_TABLE·PHYSICAL_COLUMN·PHYSICAL_RELATION·PHYSICAL_INDEX·PHYSICAL_CONSTRAINT) + targetId 를 주면 객체별 이력이다 (삭제된 객체도 조회된다). "
            + "action(CREATE·UPDATE·DELETE·APPROVE·REVOKE), userId, from·to(yyyy-MM-dd, 종료일 포함), changeGroupId")
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> search(@AuthenticationPrincipal AccountDto accountDto,
                                                      @RequestParam(value = "page") int page,
                                                      @RequestParam(value = "size") int size,
                                                      @RequestParam(value = "targetType", required = false) String targetType,
                                                      @RequestParam(value = "targetId", required = false) Long targetId,
                                                      @RequestParam(value = "action", required = false) String action,
                                                      @RequestParam(value = "userId", required = false) String userId,
                                                      @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(value = "changeGroupId", required = false) String changeGroupId) {
        return PageableUtil.getGridPageableMap(historyService.search(accountDto,
                new ModelChangeHistoryService.Filter(targetType, targetId, action, userId, from, to, changeGroupId), page, size));
    }

    @Operation(summary = "변경 그룹의 이력", description = "한 작업(트랜잭션)에서 함께 바뀐 이력을 기록된 순서대로 조회한다.")
    @GetMapping("/group/{changeGroupId}")
    public ResponseEntity<List<ModelChangeHistoryDto.Summary>> group(@AuthenticationPrincipal AccountDto accountDto,
                                                                     @PathVariable(name = "changeGroupId") String changeGroupId) {
        return ResponseEntity.ok(historyService.group(accountDto, changeGroupId));
    }

    @Operation(summary = "변경 이력 상세", description = "변경 전후 상태(JSON)를 함께 조회한다. 생성은 before, 삭제는 after 가 null 이다.")
    @GetMapping("/{id}")
    public ResponseEntity<ModelChangeHistoryDto.Detail> detail(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(historyService.detail(accountDto, id));
    }
}
