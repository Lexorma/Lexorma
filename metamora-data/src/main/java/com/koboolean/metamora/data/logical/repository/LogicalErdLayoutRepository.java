package com.koboolean.metamora.data.logical.repository;

import com.koboolean.metamora.data.logical.domain.entity.LogicalErdLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LogicalErdLayoutRepository extends JpaRepository<LogicalErdLayout, Long> {

    Optional<LogicalErdLayout> findByModelIdAndAccountId(Long modelId, Long accountId);

    /** 모델을 지울 때 모든 사용자의 배치를 함께 지운다 */
    void deleteByModelId(Long modelId);
}
