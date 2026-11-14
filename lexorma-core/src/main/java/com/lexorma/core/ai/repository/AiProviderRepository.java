package com.lexorma.core.ai.repository;

import com.lexorma.core.ai.domain.entity.AiProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    List<AiProvider> findAllByOrderByIdAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /** Provider 행을 배타로 잡고 다시 읽는다 (기본 Provider 지정 : 그 사이의 비활성화·수정과 차례를 지킨다) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AiProvider p where p.id = :id")
    Optional<AiProvider> lockById(@Param("id") Long id);
}
