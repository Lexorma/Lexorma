package com.lexorma.core.security.repository;

import com.lexorma.core.security.domain.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Role findByRoleName(String roleNotApprove);
}
