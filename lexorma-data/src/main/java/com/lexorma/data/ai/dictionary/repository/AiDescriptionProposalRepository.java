package com.lexorma.data.ai.dictionary.repository;

import com.lexorma.data.ai.dictionary.domain.entity.AiDescriptionProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiDescriptionProposalRepository extends JpaRepository<AiDescriptionProposal, Long>, JpaSpecificationExecutor<AiDescriptionProposal> {

    Optional<AiDescriptionProposal> findByIdAndProjectId(Long id, Long projectId);

    /** 상태를 바꿀 때 : 행을 잠그고 읽는다 (같은 제안의 동시 처리를 줄 세운다. 버전 비교는 그 뒤에 한다) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AiDescriptionProposal p where p.id = :id and p.projectId = :projectId")
    Optional<AiDescriptionProposal> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 한 대상의 제안 (최근 차수부터). 컬럼 대상이 아니면 columnId 가 null 인 것만 */
    @Query("select p from AiDescriptionProposal p where p.projectId = :projectId and p.tableId = :tableId"
            + " and ((:columnId is null and p.columnId is null) or p.columnId = :columnId) order by p.cycle desc")
    List<AiDescriptionProposal> findTarget(@Param("projectId") Long projectId, @Param("tableId") Long tableId, @Param("columnId") Long columnId);

    /** 테이블과 그 컬럼의 제안 (화면 표시용) */
    List<AiDescriptionProposal> findAllByProjectIdAndTableIdOrderByCycleDesc(Long projectId, Long tableId);

    List<AiDescriptionProposal> findAllByProjectIdAndStatusIn(Long projectId, Collection<AiDescriptionProposal.ReviewStatus> statuses);

    List<AiDescriptionProposal> findAllByProjectId(Long projectId);
}
