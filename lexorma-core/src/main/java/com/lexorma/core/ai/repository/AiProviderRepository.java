package com.lexorma.core.ai.repository;

import com.lexorma.core.ai.domain.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    List<AiProvider> findAllByOrderByIdAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
