package com.koboolean.metamora.core.security.repository;

import com.koboolean.metamora.core.security.domain.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Role findByRoleName(String roleNotApprove);
}
