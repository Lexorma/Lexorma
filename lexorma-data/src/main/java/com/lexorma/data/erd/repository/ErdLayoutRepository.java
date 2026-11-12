package com.lexorma.data.erd.repository;

import com.lexorma.data.erd.domain.entity.ErdLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ErdLayoutRepository extends JpaRepository<ErdLayout, Long> {

    Optional<ErdLayout> findByProjectIdAndAccountId(Long projectId, Long accountId);
}
