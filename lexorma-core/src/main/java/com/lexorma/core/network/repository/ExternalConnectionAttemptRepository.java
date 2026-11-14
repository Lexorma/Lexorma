package com.lexorma.core.network.repository;

import com.lexorma.core.network.domain.entity.ExternalConnectionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExternalConnectionAttemptRepository extends JpaRepository<ExternalConnectionAttempt, Long>, JpaSpecificationExecutor<ExternalConnectionAttempt> {
}
