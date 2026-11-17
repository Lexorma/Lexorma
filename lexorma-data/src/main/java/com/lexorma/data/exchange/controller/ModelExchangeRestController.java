package com.lexorma.data.exchange.controller;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.logs.policy.ProtectedLogBody;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.service.ModelExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 모델 Export·Import·복사 (DEC-22, docs/completed/model-exchange-dec22.md). Export·대상 목록은 VIEWER, 계획·확정은 USER + 대상 프로젝트의 관리자·ADMIN (서비스가 확인).
 * 파일 전체가 요청·응답 본문이라 요청 로그에 본문을 남기지 않는다. 요청 본문은 {@link #MAX_BYTES} 까지.
 */
@RestController
@RequestMapping("/api/modelExchange")
@RequiredArgsConstructor
@ProtectedLogBody
@PreAuthorize("hasRole('VIEWER')")
public class ModelExchangeRestController {

    static final long MAX_BYTES = 30L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ModelExchangeService service;

    @GetMapping("/export")
    public ResponseEntity<ModelExchangeDto.Document> export(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.export(accountDto));
    }

    @GetMapping("/targets")
    public ResponseEntity<List<ModelExchangeDto.TargetProject>> targets(@AuthenticationPrincipal AccountDto accountDto) {
        return ResponseEntity.ok(service.targets(accountDto));
    }

    @PostMapping("/plan")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ModelExchangeDto.Plan> plan(@AuthenticationPrincipal AccountDto accountDto, HttpServletRequest http) {
        return ResponseEntity.ok(service.plan(accountDto, read(http, ModelExchangeDto.PlanRequest.class)));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ModelExchangeDto.Result> apply(@AuthenticationPrincipal AccountDto accountDto, HttpServletRequest http) {
        return ResponseEntity.ok(service.apply(accountDto, read(http, ModelExchangeDto.ApplyRequest.class)));
    }

    /** 본문을 한도까지만 읽는다 (길이를 알리지 않은 요청도 한도를 넘으면 그 자리에서 멈춘다). JSON 이 아니거나 모양이 다르면 400 */
    private static <T> T read(HttpServletRequest http, Class<T> type) {
        if (http.getContentLengthLong() > MAX_BYTES) throw tooLarge();
        try (InputStream in = http.getInputStream()) {
            byte[] body = in.readNBytes((int) MAX_BYTES + 1);
            if (body.length > MAX_BYTES) throw tooLarge();
            return JSON.readValue(body, type);
        } catch (IOException | JacksonException e) {
            throw new CustomException(ErrorCode.MODEL_EXCHANGE_INVALID, "모델 파일을 읽을 수 없습니다.");
        }
    }

    private static CustomException tooLarge() {
        return new CustomException(ErrorCode.MODEL_EXCHANGE_INVALID, "파일이 너무 큽니다 (최대 30MB).");
    }
}
