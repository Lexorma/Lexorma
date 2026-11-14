package com.lexorma.core.network.repository;

import com.lexorma.core.network.domain.entity.ExternalConnectionRuleHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExternalConnectionRuleHistoryRepository extends JpaRepository<ExternalConnectionRuleHistory, Long> {

    Page<ExternalConnectionRuleHistory> findAllByOrderByIdDesc(Pageable pageable);

    /** 정책 변경 식별자 : 마지막 변경 이력 번호 (없으면 0) */
    @Query("select coalesce(max(h.id), 0) from ExternalConnectionRuleHistory h")
    long latestId();
}
