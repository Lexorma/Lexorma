package com.lexorma.data.mcp.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.mcp.domain.dto.McpDto;
import com.lexorma.data.mcp.domain.entity.McpProjectSetting;
import com.lexorma.data.mcp.domain.entity.McpToken;
import com.lexorma.data.mcp.repository.McpCallHistoryRepository;
import com.lexorma.data.mcp.repository.McpProjectSettingRepository;
import com.lexorma.data.mcp.repository.McpTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * 내장 MCP 의 프로젝트 설정·토큰 관리 (DEC-31, docs/completed/embedded-mcp-dec31.md). 조회는 그 프로젝트 구성원(VIEWER), 설정·발급·폐기·토큰 목록은 지금의 DB 로
 * 프로젝트 관리자·ADMIN(멤버) 인지 다시 확인한다 ({@link ProjectAuthority#canManage}). 토큰 원문은 만들어 응답으로 한 번 돌려주고 해시만 저장한다 (로그에 남기지 않는다).
 */
@Service
@RequiredArgsConstructor
public class McpTokenService {

    public static final String ENDPOINT = "/mcp";
    public static final String TOKEN_PREFIX = "lxm_";
    public static final int MAX_DAYS = 365;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpTokenRepository tokenRepository;
    private final McpProjectSettingRepository settingRepository;
    private final McpCallHistoryRepository callRepository;
    private final ProjectAuthority projectAuthority;

    @Value("${lexorma.mcp.enabled:false}")
    private boolean serverEnabled;

    public boolean serverEnabled() {
        return serverEnabled;
    }

    @Transactional(readOnly = true)
    public McpDto.Settings settings(AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        boolean manage = canManage(accountDto);
        boolean enabled = settingRepository.findByProjectId(projectId).map(McpProjectSetting::getEnabled).orElse(false);
        List<McpDto.Token> tokens = manage ? tokenRepository.findAllByProjectIdOrderByIdDesc(projectId).stream().map(McpTokenService::token).toList() : List.of();
        List<McpDto.Call> calls = manage ? callRepository.findAllByProjectIdOrderByIdDesc(projectId, PageRequest.of(0, 20)).stream()
                .map(call -> new McpDto.Call(call.getToolName(), call.getResult().name(), call.getCalledAt())).toList() : List.of();
        return new McpDto.Settings(serverEnabled, enabled, manage, ENDPOINT, MAX_DAYS, tokens, calls);
    }

    @Transactional
    public McpDto.Settings setEnabled(AccountDto accountDto, McpDto.SettingRequest request) {
        requireManager(accountDto);
        if (request == null || request.enabled() == null) throw new CustomException(ErrorCode.MCP_REQUEST_INVALID, "사용 여부를 정해주세요.");
        Long projectId = accountDto.getProjectId();
        McpProjectSetting setting = settingRepository.findByProjectId(projectId)
                .orElseGet(() -> McpProjectSetting.builder().projectId(projectId).enabled(false).build());
        setting.setEnabled(request.enabled());
        settingRepository.save(setting);
        return settings(accountDto);
    }

    @Transactional
    public McpDto.Issued issue(AccountDto accountDto, McpDto.IssueRequest request) {
        requireManager(accountDto);
        String name = request == null || request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > 100) throw new CustomException(ErrorCode.MCP_REQUEST_INVALID, "토큰 이름은 1~100자로 정해주세요.");
        LocalDateTime now = LocalDateTime.now();
        if (request.expiresAt() == null || !request.expiresAt().isAfter(now) || request.expiresAt().isAfter(now.plusDays(MAX_DAYS))) {
            throw new CustomException(ErrorCode.MCP_REQUEST_INVALID, "만료 일시는 지금 이후 {0}일 안으로 정해주세요.", MAX_DAYS);
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String raw = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        McpToken token = tokenRepository.save(McpToken.builder().projectId(accountDto.getProjectId()).accountId(Long.valueOf(accountDto.getId())).name(name)
                .hash(hash(raw)).prefix(raw.substring(0, 12)).expiresAt(request.expiresAt()).build());
        return new McpDto.Issued(token(token), raw);
    }

    @Transactional
    public McpDto.Token revoke(AccountDto accountDto, Long tokenId) {
        requireManager(accountDto);
        McpToken token = tokenRepository.findByIdAndProjectId(tokenId, accountDto.getProjectId())
                .orElseThrow(() -> new CustomException(ErrorCode.MCP_REQUEST_INVALID, "토큰을 찾을 수 없습니다."));
        if (token.getRevokedAt() == null) token.setRevokedAt(LocalDateTime.now());
        return token(token);
    }

    private boolean canManage(AccountDto accountDto) {
        return accountDto.getId() != null && projectAuthority.canManage(accountDto.getProjectId(), Long.valueOf(accountDto.getId()));
    }

    private void requireManager(AccountDto accountDto) {
        if (!canManage(accountDto)) throw new CustomException(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    static McpDto.Token token(McpToken token) {
        LocalDateTime now = LocalDateTime.now();
        String status = token.getRevokedAt() != null ? "REVOKED" : !token.getExpiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE";
        return new McpDto.Token(token.getId(), token.getName(), token.getPrefix(), status, token.getExpiresAt(), token.getRevokedAt(), token.getLastUsedAt(),
                token.issuedBy(), token.issuedAt());
    }

    /** 토큰 원문의 해시 (저장·조회 키). 원문은 32바이트 난수라 소금 없이 SHA-256 으로 충분하다 */
    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
