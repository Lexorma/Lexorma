package com.lexorma.core.security.repository;

import com.lexorma.core.security.domain.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 해당 역할이 실제로 부여된 활성 계정이 있는지 (복구 로그인을 할 수 있는 ADMIN 존재 확인) */
    boolean existsByIsActiveTrueAndUserRoles_RoleName(String roleName);
}
