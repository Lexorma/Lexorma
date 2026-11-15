package com.lexorma.data.ai.dictionary.repository;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiDescriptionJobRepository extends JpaRepository<AiDescriptionJob, Long> {

    Optional<AiDescriptionJob> findByIdAndProjectId(Long id, Long projectId);

    List<AiDescriptionJob> findAllByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);

    List<AiDescriptionJob> findAllByProjectIdAndStatusIn(Long projectId, Collection<AiDescriptionJob.Status> statuses);

    List<AiDescriptionJob> findAllByStatusIn(Collection<AiDescriptionJob.Status> statuses);
}
