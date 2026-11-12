package com.lexorma.core.project.guard;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.project.domain.entity.Project;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 지워진 프로젝트에 데이터가 새로 생기지 않게 한다 (프로젝트 삭제와 동시에 들어온 등록).
 * <p>
 * 프로젝트 데이터 테이블은 프로젝트로 FK 를 걸지 않으므로, 삭제 트랜잭션이 데이터를 다 지운 뒤 커밋하는 사이에 다른 트랜잭션이 넣은 행은
 * 주인 없는 데이터로 남을 수 있다. 그래서 프로젝트 ID 를 가진 엔티티를 저장한 트랜잭션은 커밋 직전에 그 프로젝트 행을 공유 잠금으로 읽는다 :
 * <ul>
 *   <li>삭제가 진행 중이면(프로젝트 행 배타 잠금) 끝날 때까지 기다렸다가, 프로젝트가 없어졌으면 예외로 롤백한다</li>
 *   <li>먼저 커밋하면 삭제 쪽이 이 잠금이 풀린 뒤에 시작하므로 방금 넣은 행까지 지운다</li>
 * </ul>
 * 잠금은 커밋 직전에만 잡아 쓰기끼리 서로 기다리지 않는다. JPA 로 저장하는 경로는 자동이고, 네이티브·JDBC 저장은 {@link #guard} 를 직접 불러야 한다.
 */
@Component
public class ProjectWriteGuard implements TransactionExecutionListener, PostInsertEventListener {

    private static final String PROJECT_ID = "projectId";

    @PersistenceContext
    private EntityManager entityManager;

    private final EntityManagerFactory entityManagerFactory;

    public ProjectWriteGuard(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getEventListenerRegistry();
        registry.appendListeners(EventType.POST_INSERT, this);
    }

    /** 트랜잭션을 시작할 때 등록해 둔다 : 커밋 때 처음 일어나는 flush 의 저장도 놓치지 않는다 */
    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null || transaction.isReadOnly() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new Pending());
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        Pending pending = current();
        if (pending == null) return;
        EntityPersister persister = event.getPersister();
        String[] names = persister.getPropertyNames();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(PROJECT_ID) && event.getState()[i] instanceof Long projectId) pending.projectIds.add(projectId);
        }
    }

    /**
     * JPA 를 거치지 않는 저장(JDBC 배치·네이티브 SQL)에서 직접 부른다 : 이 트랜잭션이 그 프로젝트에 데이터를 넣었다고 알려,
     * 커밋 직전에 같은 확인(프로젝트 행 공유 잠금, 없으면 롤백)을 받게 한다. 쓰기 트랜잭션 안에서만 부를 수 있다.
     */
    public void guard(Long projectId) {
        Pending pending = current();
        if (pending == null) throw new IllegalStateException("쓰기 트랜잭션 안에서 불러야 합니다.");
        if (projectId != null) pending.projectIds.add(projectId);
    }

    /**
     * 프로젝트 행을 <b>지금</b> 공유 잠금으로 잡는다 (없으면 PROJECT_NOT_FOUND). 커밋 직전 확인 대상에도 넣는다.
     * 다른 행(원본 DB 연결 등)을 잠그고 쓰는 경로는 그 행보다 먼저 이것을 부른다 : 프로젝트 영구 삭제는 프로젝트 행을 배타 잠금으로 잡은 뒤 하위 행을 지우므로,
     * 하위 행을 먼저 잠그고 커밋 때 프로젝트를 잠그면 서로를 기다리게 된다 (잠금 순서 : 프로젝트 → 하위 행).
     */
    public void lock(Long projectId) {
        Pending pending = current();
        if (pending == null) throw new IllegalStateException("쓰기 트랜잭션 안에서 불러야 합니다.");
        if (projectId == null || !exists(projectId)) throw new CustomException(ErrorCode.PROJECT_NOT_FOUND);
        pending.projectIds.add(projectId);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private static Pending current() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending) return pending;
        }
        return null;
    }

    /**
     * 프로젝트 행을 공유 잠금으로 읽는다. 이 트랜잭션이 프로젝트 엔티티를 이미 읽어 둔 경우(권한 확인 등) 지워진 행을 잠그려 하면
     * null 이 아니라 예외가 난다 : 둘 다 "프로젝트가 없다" 로 본다.
     */
    private boolean exists(Long projectId) {
        try {
            return entityManager.find(Project.class, projectId, LockModeType.PESSIMISTIC_READ) != null;
        } catch (jakarta.persistence.OptimisticLockException | jakarta.persistence.EntityNotFoundException e) {
            return false;
        }
    }

    private final class Pending implements TransactionSynchronization {

        private final Set<Long> projectIds = new LinkedHashSet<>();
        private final Set<Long> checked = new HashSet<>();

        /** 다른 커밋 직전 작업(참조 관계 동기화, 변경 이력)이 저장을 끝낸 뒤에 확인한다 */
        @Override
        public int getOrder() {
            return LOWEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (readOnly) return;
            entityManager.flush(); // 아직 반영되지 않은 저장의 이벤트를 먼저 받는다
            for (Long projectId : Set.copyOf(projectIds)) {
                if (!checked.add(projectId)) continue;
                if (!exists(projectId)) throw new CustomException(ErrorCode.PROJECT_NOT_FOUND);
            }
        }
    }
}
