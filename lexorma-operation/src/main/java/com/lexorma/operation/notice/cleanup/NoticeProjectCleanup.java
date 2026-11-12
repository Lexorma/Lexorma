package com.lexorma.operation.notice.cleanup;

import com.lexorma.core.project.domain.event.ProjectDeletedEvent;
import com.lexorma.operation.notice.repository.BoardRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 영구 삭제 : 그 프로젝트의 공지(읽은 사용자·첨부 파일 정보 포함)를 지운다. 프로젝트 삭제와 같은 트랜잭션에서 실행된다.
 * 여러 프로젝트에 함께 등록한 공지는 프로젝트마다 행이 따로 있으므로 다른 프로젝트의 공지는 남고, 그 공지의 대상 목록에서만 지워진 프로젝트를 뺀다.
 */
@Component
@RequiredArgsConstructor
public class NoticeProjectCleanup {

    private static final String POSTS = "(select PST_SN from TB_PST where PRJ_SN = :projectId)";

    private final BoardRepository boardRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    public void onProjectDeleted(ProjectDeletedEvent event) {
        Long projectId = event.projectId();
        entityManager.flush();
        for (String sql : new String[]{"delete from TB_PST_ACNT where PST_SN in " + POSTS, "delete from TB_ATCH_FILE where PST_SN in " + POSTS, "delete from TB_PST where PRJ_SN = :projectId"}) {
            entityManager.createNativeQuery(sql).setParameter("projectId", projectId).executeUpdate();
        }
        // 대상 목록은 배열 컬럼이라 DB 마다 문법이 다르다 → 엔티티로 고친다 (공지는 수가 적다)
        boardRepository.findAllByProjectIdNot(projectId).forEach(board -> {
            if (board.getProjectIds() != null && board.getProjectIds().contains(projectId)) board.getProjectIds().remove(projectId);
        });
    }
}
