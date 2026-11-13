package com.lexorma.source.connection.controller;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.connection.service.SourceConnectionService;
import com.lexorma.source.connection.service.SourceConnectionTester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 프로젝트의 외부 DB 연결 설정. 로그인한 프로젝트의 것만 다룬다.
 * 조회는 VIEWER 이상. 등록·수정·삭제는 "그 프로젝트에 접근할 수 있고, 그 프로젝트의 관리자이거나 ADMIN" 이다 — 서비스가 지금의 DB 로 확인한다 (ProjectAuthority.canManage 와 같은 기준).
 * 컨트롤러의 역할 조건은 USER 이상으로 VIEWER(조회 전용)와 미승인 계정만 걸러 낸다 : 프로젝트 관리자는 DBA 역할이 없어도 자기 프로젝트의 연결을 관리한다.
 * 연결 테스트(AI-2b)만 원본 DB 에 접속한다 (설정 API 는 접속하지 않는다). 경로가 /api/source/** 라 요청·응답 본문(비밀번호 포함)은 요청 로그에 남지 않는다 (LogBodyPolicy).
 */
@RestController
@RequestMapping("/api/source/connections")
@RequiredArgsConstructor
@PreAuthorize("hasRole('VIEWER')")
@Tag(name = "Source Connection API", description = "프로젝트의 외부 DB 연결 설정과 연결 테스트. 연결 테스트만 원본 DB 에 접속한다.")
public class SourceConnectionRestController {

    private final SourceConnectionService connectionService;
    private final SourceConnectionTester connectionTester;

    @Operation(summary = "외부 DB 연결 목록", description = "로그인한 프로젝트의 연결.")
    @GetMapping
    public ResponseEntity<List<SourceConnectionDto.Response>> list(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(connectionService.list(accountDto));
    }

    @Operation(summary = "외부 DB 연결 상세")
    @GetMapping("/{id}")
    public ResponseEntity<SourceConnectionDto.Response> detail(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(connectionService.detail(accountDto, id));
    }

    @Operation(summary = "외부 DB 연결 등록", description = "그 프로젝트의 관리자 또는 (접근할 수 있는) ADMIN — DBA 역할은 필요 없다. DBMS·호스트·포트·데이터베이스명·계정을 받는다 (JDBC URL 은 받지 않는다). 비밀번호는 암호화해 저장하고 응답에 없다.")
    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceConnectionDto.Response> create(@AuthenticationPrincipal AccountDto accountDto, @RequestBody SourceConnectionDto.Request request) {
        return ResponseEntity.ok(connectionService.create(accountDto, request));
    }

    @Operation(summary = "외부 DB 연결 수정", description = "그 프로젝트의 관리자 또는 (접근할 수 있는) ADMIN — DBA 역할은 필요 없다. password 를 비워 두면 저장된 비밀번호를 그대로 둔다.")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceConnectionDto.Response> update(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id,
                                                               @RequestBody SourceConnectionDto.Request request) {
        return ResponseEntity.ok(connectionService.update(accountDto, id, request));
    }

    @Operation(summary = "외부 DB 연결 삭제", description = "그 프로젝트의 관리자 또는 (접근할 수 있는) ADMIN — DBA 역할은 필요 없다. 그 연결의 원본 카탈로그를 함께 지운다. 물리·논리 모델은 지우지 않는다.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(Map.of("result", true, "deletedCatalogRows", connectionService.delete(accountDto, id)));
    }

    @Operation(summary = "저장된 연결 테스트", description = "실제로 접속해 메타데이터를 읽을 수 있는지 본다. 그 프로젝트의 관리자 또는 ADMIN. 계정마다 1분에 10번. "
            + "접속·조회 실패는 200 의 errorCode 로 알린다.")
    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceConnectionDto.TestResult> test(@AuthenticationPrincipal AccountDto accountDto, @PathVariable(name = "id") Long id) {
        return ResponseEntity.ok(connectionTester.test(accountDto, id));
    }

    @Operation(summary = "저장 전 연결 테스트", description = "등록·수정 화면의 설정으로 접속해 본다 (저장하지 않는다). connectionId 는 수정 중인 연결 : "
            + "password 를 비워 두면 저장된 비밀번호를 쓴다 (DBMS·호스트·포트·데이터베이스·계정이 같을 때만).")
    @PostMapping("/test")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<SourceConnectionDto.TestResult> testDraft(@AuthenticationPrincipal AccountDto accountDto,
                                                                    @RequestParam(value = "connectionId", required = false) Long connectionId,
                                                                    @RequestBody SourceConnectionDto.Request request) {
        return ResponseEntity.ok(connectionTester.testDraft(accountDto, connectionId, request));
    }
}
