package com.lexorma.ai.hook;

import com.lexorma.data.sourcemapping.service.RelatedRecordCounter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** 원본 삭제 반영(DEC-04) 영향의 AI 설명 제안 수 : 데이터 모듈의 계약을 이 모듈이 구현한다 (8차 모듈 분리 전에는 SourceDeletionService 가 직접 셌다) */
@Component
public class AiProposalRecordCounter implements RelatedRecordCounter {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Counts count(Long projectId, Collection<Long> tableIds) {
        Map<Long, Integer> byTable = new HashMap<>();
        Map<Long, Integer> byColumn = new HashMap<>();
        if (tableIds.isEmpty()) return new Counts(byTable, byColumn);
        for (Object[] row : entityManager.createQuery("select p.tableId, p.columnId from AiDescriptionProposal p where p.projectId = :p and p.tableId in :ids", Object[].class)
                .setParameter("p", projectId).setParameter("ids", tableIds).getResultList()) {
            byTable.merge((Long) row[0], 1, Integer::sum);
            if (row[1] != null) byColumn.merge((Long) row[1], 1, Integer::sum);
        }
        return new Counts(byTable, byColumn);
    }
}
