package com.lexorma.core.security.repository;

import com.lexorma.core.security.domain.entity.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Role findByRoleName(String roleNotApprove);

    /** 역할 행을 배타적으로 잠근다 (최초 관리자 계정 만들기·회원가입이 "계정 없음" 확인을 한 번에 하나씩 하도록) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.roleName = :roleName")
    Role findByRoleNameForUpdate(@Param("roleName") String roleName);
}
