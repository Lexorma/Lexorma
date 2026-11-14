package com.lexorma.core.network.repository;

import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.domain.entity.ExternalConnectionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalConnectionRuleRepository extends JpaRepository<ExternalConnectionRule, Long> {

    List<ExternalConnectionRule> findAllByConnectionTypeAndEnabledTrueOrderByIdAsc(ConnectionType connectionType);

    List<ExternalConnectionRule> findAllByOrderByIdAsc();

    List<ExternalConnectionRule> findAllByConnectionTypeAndAllowedAddressAndPort(ConnectionType connectionType, String allowedAddress, Integer port);
}
