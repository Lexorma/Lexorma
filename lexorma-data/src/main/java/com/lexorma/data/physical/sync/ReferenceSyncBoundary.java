package com.lexorma.data.physical.sync;

import com.lexorma.data.physical.service.PhysicalModelService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 참조 관계(REF_COLUMN) 동기화의 공통 경계 : 쓰기 트랜잭션마다 커밋 직전에, 그 트랜잭션에서 바뀐 테이블의 참조 관계를 다시 맞춘다.
 * <ul>
 *   <li>바뀐 테이블은 {@link ReferenceSyncListener} 가 표시한다 (저장 경로가 따로 부르지 않아도 된다)</li>
 *   <li>같은 트랜잭션 안에서 실행하므로 동기화가 실패하면 저장도 함께 롤백된다</li>
 *   <li>서비스가 중간에 {@code syncReferenceRelations} 를 직접 부르면(엑셀 청크 등) 그 테이블은 표시에서 빠져 두 번 하지 않는다</li>
 * </ul>
 * 트랜잭션 시작 시점에 등록해 두어야 커밋 때 처음 일어나는 flush 의 변경도 놓치지 않는다 (엔티티 콜백은 flush 때 불린다).
 */
@Component
public class ReferenceSyncBoundary implements TransactionExecutionListener {

    /** 한 번에 동기화하는 테이블 수 (IN 조건·영속성 컨텍스트 크기 제한) */
    private static final int BATCH_SIZE = 500;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectProvider<PhysicalModelService> physicalModelService;
    private final ObjectProvider<com.lexorma.core.project.guard.ProjectWriteGuard> projectWriteGuard;

    public ReferenceSyncBoundary(ObjectProvider<PhysicalModelService> physicalModelService,
                                 ObjectProvider<com.lexorma.core.project.guard.ProjectWriteGuard> projectWriteGuard) {
        this.physicalModelService = physicalModelService;
        this.projectWriteGuard = projectWriteGuard;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null || transaction.isReadOnly() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new Pending());
    }

    /** 이 트랜잭션에서 바뀐 테이블로 표시한다 (트랜잭션 밖이거나 읽기 전용이면 아무것도 하지 않는다) */
    static void mark(Long projectId, Long tableId) {
        Pending pending = current();
        if (pending != null && projectId != null && tableId != null) {
            pending.tables.computeIfAbsent(projectId, key -> new LinkedHashSet<>()).add(tableId);
        }
    }

    /** 직접 동기화한 테이블은 커밋 직전 대상에서 뺀다 (H2 의 커밋 직전 재확인 대상에는 남긴다) */
    public static void synced(Long projectId, Collection<Long> tableIds) {
        Pending pending = current();
        if (pending == null) return;
        Set<Long> marked = pending.tables.get(projectId);
        if (marked != null) marked.removeAll(tableIds);
        if (projectId != null) pending.synced.computeIfAbsent(projectId, key -> new LinkedHashSet<>()).addAll(tableIds);
    }

    private static Pending current() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending) return pending;
        }
        return null;
    }

    private final class Pending implements TransactionSynchronization {

        /** 프로젝트 ID → 바뀐 테이블 ID */
        private final Map<Long, Set<Long>> tables = new LinkedHashMap<>();
        /** 프로젝트 ID → 이 트랜잭션에서 동기화한 테이블 ID (직접 부른 것 포함 — H2 재확인용) */
        private final Map<Long, Set<Long>> synced = new LinkedHashMap<>();
        private boolean rechecked;

        /** 모델 변경 이력(ModelChangeRecorder)보다 먼저 실행한다 : 여기서 바꾼 관계도 같은 트랜잭션의 이력에 남는다 */
        @Override
        public int getOrder() {
            return LOWEST_PRECEDENCE - 1;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (readOnly) return;
            // 동기화는 관계만 쓰므로 보통 한 번에 끝난다. 표시가 계속 생기는 경우를 막으려고 횟수를 제한한다
            for (int round = 0; round < 3; round++) {
                entityManager.flush(); // 아직 반영되지 않은 변경의 콜백을 먼저 받는다
                Map<Long, List<Long>> marked = new LinkedHashMap<>();
                tables.forEach((projectId, tableIds) -> {
                    if (!tableIds.isEmpty()) marked.put(projectId, new ArrayList<>(tableIds));
                });
                if (marked.isEmpty()) break;
                PhysicalModelService service = physicalModelService.getObject();
                marked.forEach((projectId, tableIds) -> {
                    for (int from = 0; from < tableIds.size(); from += BATCH_SIZE) {
                        service.syncReferenceRelations(projectId, tableIds.subList(from, Math.min(from + BATCH_SIZE, tableIds.size())));
                    }
                });
            }
            recheckOnH2();
        }

        /**
         * H2 전용 커밋 직전 재확인 (M8-C4b-R1) : PostgreSQL 은 동기화가 참조 대상 행을 FOR KEY SHARE 로 먼저 잡아 키를 바꾸는 쓰기와 직렬화되지만, H2 에는 KEY SHARE 가 없다
         * (같은 문장이 배타 잠금이라 양방향 참조 저장끼리 교착한다). 대신 여기서 프로젝트 행을 잡는다 — 원래 커밋 직전 ProjectWriteGuard 가 잡던 같은 잠금을 조금 앞당긴 것이고,
         * 키를 바꾸는 쓰기는 프로젝트 행을 배타로 먼저 잡으므로 진행 중이면 그 커밋을 기다린다. 잡은 뒤 이 트랜잭션이 동기화한 테이블을 최신 상태로 한 번 더 맞춘다
         * (보통 아무것도 바꾸지 않는다 — 그 사이 커밋된 이름 변경·PK 삭제가 있으면 예전 상태로 만든 관계를 고치거나 지운다). 커밋 단계에서만 잡으므로 일반 저장끼리의 동시성은 예전과 같다
         */
        private void recheckOnH2() {
            if (rechecked || synced.isEmpty()) return;
            if (!(entityManager.getEntityManagerFactory().unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class).getJdbcServices().getDialect()
                    instanceof org.hibernate.dialect.H2Dialect)) return;
            rechecked = true;
            PhysicalModelService service = physicalModelService.getObject();
            Map<Long, List<Long>> all = new LinkedHashMap<>();
            synced.forEach((projectId, tableIds) -> all.put(projectId, new ArrayList<>(tableIds)));
            all.forEach((projectId, tableIds) -> {
                projectWriteGuard.getObject().lock(projectId);
                for (int from = 0; from < tableIds.size(); from += BATCH_SIZE) {
                    service.syncReferenceRelations(projectId, tableIds.subList(from, Math.min(from + BATCH_SIZE, tableIds.size())));
                }
            });
            entityManager.flush();
        }
    }
}
