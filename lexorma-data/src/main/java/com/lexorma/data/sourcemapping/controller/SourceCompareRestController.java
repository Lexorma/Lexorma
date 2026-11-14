package com.lexorma.data.sourcemapping.controller;

import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto;
import com.lexorma.data.sourcemapping.service.SourceCompareService;
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

/**
 * 원본 ↔ 물리 모델 비교 (M-4d). <b>조회만 있다</b> (VIEWER 이상, 로그인한 프로젝트) : 비교 결과는 실행 명령이 아니라 읽기 전용 분석이고 저장하지 않는다.
 * 원본 DB 에 접속하지 않고 마지막 반영 수집을 읽으며, 물리 모델·매핑을 바꾸지 않는다. 매핑 자체는 /api/sourceMapping 에서 확정한다.
 * connectionId·reflectedOrder 는 요약에서 받은 비교 기준이다 : 보내면 기준이 바뀐 경우 SOURCE_COMPARE_STALE(409).
 * 응답(원본 DEFAULT·CHECK 식, 대량 목록)은 요청 로그 본문에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/sourceCompare")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@ProtectedLogBody
@Tag(name = "Source Compare API", description = "원본 DB(마지막 반영 수집)와 물리 모델의 차이 (M-4c 매핑 기준, 읽기 전용)")
public class SourceCompareRestController {

    private final SourceCompareService service;

    @Operation(summary = "비교 요약", description = "기준 연결, 마지막 반영 시점, TABLE 비교 상태·이유별 건수 (비교 가능·차이·미매핑·모델에만·충돌·재확인).")
    @GetMapping
    public ResponseEntity<SourceCompareDto.Summary> summary(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(service.summary(accountDto));
    }

    @Operation(summary = "TABLE 비교 목록", description = "원본 TABLE 과 모델에만 있는 TABLE 의 비교 상태·차이 수. status·sourceSchemaId·keyword 로 거르고 쪽 단위 (size 최대 500).")
    @GetMapping("/tables")
    public ResponseEntity<SourceCompareDto.TablePage> tables(@AuthenticationPrincipal AccountDto accountDto,
                                                             @RequestParam(value = "connectionId", required = false) Long connectionId,
                                                             @RequestParam(value = "reflectedOrder", required = false) Integer reflectedOrder,
                                                             @RequestParam(value = "sourceSchemaId", required = false) Long sourceSchemaId,
                                                             @RequestParam(value = "status", required = false) String status,
                                                             @RequestParam(value = "keyword", required = false) String keyword,
                                                             @RequestParam(value = "page", defaultValue = "0") int page,
                                                             @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(service.tables(accountDto, connectionId, reflectedOrder, sourceSchemaId, status, keyword, page, size));
    }

    @Operation(summary = "TABLE 상세 비교", description = "key(S+원본 TABLE ID 또는 M+모델 TABLE ID)의 컬럼·PK·UNIQUE·CHECK·FK·인덱스 차이와 이름 변경 후보. kind 로 종류를 고른다 (쉼표).")
    @GetMapping("/table")
    public ResponseEntity<SourceCompareDto.TableDetail> table(@AuthenticationPrincipal AccountDto accountDto, @RequestParam("key") String key,
                                                              @RequestParam(value = "connectionId", required = false) Long connectionId,
                                                              @RequestParam(value = "reflectedOrder", required = false) Integer reflectedOrder,
                                                              @RequestParam(value = "kind", required = false) String kind) {
        return ResponseEntity.ok(service.table(accountDto, connectionId, reflectedOrder, key, kind));
    }

    @Operation(summary = "이름 변경 후보", description = "비교한 TABLE 의 원본에만·모델에만 있는 객체 중 구조가 같은 짝 (제안일 뿐, 저장·확정하지 않는다). kind·keyword 로 거른다.")
    @GetMapping("/renameCandidates")
    public ResponseEntity<SourceCompareDto.RenamePage> renameCandidates(@AuthenticationPrincipal AccountDto accountDto,
                                                                        @RequestParam(value = "connectionId", required = false) Long connectionId,
                                                                        @RequestParam(value = "reflectedOrder", required = false) Integer reflectedOrder,
                                                                        @RequestParam(value = "kind", required = false) String kind,
                                                                        @RequestParam(value = "keyword", required = false) String keyword,
                                                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                                                        @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(service.renames(accountDto, connectionId, reflectedOrder, kind, keyword, page, size));
    }
}
