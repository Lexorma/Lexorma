package com.lexorma.core.security.conf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;

import java.util.Comparator;
import java.util.List;

/**
 * 계정당 동시 로그인 수 제한 (나중 로그인 우선, 8차). Spring Security 의 {@link ConcurrentSessionControlAuthenticationStrategy} 를 대신한다
 * ({@code SecurityConfig} 의 ObjectPostProcessor) — 나머지 세션 처리(세션 ID 변경·등록·만료 세션 안내 필터)는 그대로다.
 * <ul>
 *   <li>로그인하는 지금 세션은 만료시키지 않는다. 기본 전략은 같은 계정의 유효 세션이 한도보다 많으면 "남은 수 − 한도 + 1" 개를 오래된 순으로 만료시키는데,
 *       지금 세션도 그 목록에 들어 있으면(이미 로그인한 브라우저의 재로그인) 자기 자신까지 만료시켜 로그인 응답은 200 인데 새 세션이 인증되지 않았다.
 *       유효 세션이 여럿 남는 것은 동시 로그인 경쟁 때문이다 (서로 커밋 전의 세션을 보지 못한다)</li>
 *   <li>지금 세션 몫 1 을 빼고 남길 수 있는 만큼만 남기고(한도 1 이면 다른 세션 모두) 오래된 순으로 만료시킨다</li>
 *   <li>같은 세션을 동시에 만료시키는 다른 로그인이 먼저 만료 표시를 저장했으면(세션 속성 중복 키) 이미 만료된 것이라 넘어간다 — 예전에는 로그인 500</li>
 * </ul>
 */
public class LatestLoginSessionControlStrategy extends ConcurrentSessionControlAuthenticationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(LatestLoginSessionControlStrategy.class);

    private final SessionRegistry sessionRegistry;

    public LatestLoginSessionControlStrategy(SessionRegistry sessionRegistry, int maximumSessions) {
        super(sessionRegistry);
        this.sessionRegistry = sessionRegistry;
        setMaximumSessions(maximumSessions);
    }

    @Override
    public void onAuthentication(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        int allowed = getMaximumSessionsForThisUser(authentication);
        if (allowed == -1) return;
        HttpSession session = request.getSession(false);
        String current = session == null ? null : session.getId();
        List<SessionInformation> others = sessionRegistry.getAllSessions(authentication.getPrincipal(), false).stream()
                .filter(info -> !info.getSessionId().equals(current))
                .sorted(Comparator.comparing(SessionInformation::getLastRequest))
                .toList();
        int expire = others.size() - (allowed - 1);
        for (SessionInformation info : others.subList(0, Math.max(0, expire))) {
            try {
                info.expireNow();
            } catch (DataAccessException e) {
                // 동시에 로그인한 다른 요청이 같은 세션에 만료 표시를 먼저 저장했다
                logger.debug("session already expired by a concurrent login");
            }
        }
    }
}
