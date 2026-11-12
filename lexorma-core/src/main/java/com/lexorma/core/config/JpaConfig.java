package com.lexorma.core.config;

import com.lexorma.core.execution.ExecutionContextHolder;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.security.domain.dto.AccountDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@Configuration
@RequiredArgsConstructor
public class JpaConfig {

    private static final Logger logger = LoggerFactory.getLogger(JpaConfig.class);

    // createBy, updateBy에 session에 존재하는 name으로 사용하기
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            // 로그인 세션이 없는 실행(비동기·정기 배치)은 실행 컨텍스트의 요청자를 남긴다. 세션이 있으면 지금까지와 같다
            if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AccountDto)) {
                return ExecutionContextHolder.current().map(VerifiedExecution::auditor);
            }

            // 세션 정보에서 ID를 가져온 후 userId를 Set
            return Optional.ofNullable(((AccountDto) authentication.getPrincipal()).getName());
        };
    }
}
