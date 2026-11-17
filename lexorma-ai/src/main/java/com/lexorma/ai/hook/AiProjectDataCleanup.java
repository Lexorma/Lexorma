package com.lexorma.ai.hook;

import com.lexorma.core.project.domain.event.ProjectDeletedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 프로젝트 영구 삭제 : 이 모듈(AI·MCP)이 가진 프로젝트 데이터를 지운다 — AI 설명 제안·이력·일괄 생성 실행, 품질 평가 대상·판정·판정 이력,
 * 프로젝트 AI Provider 지정, 내장 MCP 설정·토큰·호출 이력. 8차 모듈 분리 전에는 lexorma-data 의 ProjectDataCleanup 이 지웠다.
 * <p>
 * 프로젝트 삭제 서비스가 프로젝트 행을 지우기 전에 발행하는 이벤트를 같은 스레드·같은 트랜잭션에서 받는다 ({@link EventListener} 는 동기 실행) :
 * 실패하면 프로젝트 삭제 전체가 롤백된다. 이 테이블들은 모델 테이블로 가는 FK 가 없어(프로젝트·AI Provider 로만) 데이터 모듈의 정리와 순서가 필요 없다.
 * 순서는 FK 를 따른다 : 참조하는 쪽 → 참조되는 쪽. AI·MCP 프로젝트 데이터 테이블을 더하면 여기에도 넣는다 (ProjectDeletionIntegrationTest 가 남은 행을 찾는다).
 */
@Component
public class AiProjectDataCleanup {

    private static final Logger log = LoggerFactory.getLogger(AiProjectDataCleanup.class);

    private static final List<String> DELETES = List.of(
            // AI 설명 품질 평가 판정 이력 (AI-5, DEC-27)
            "delete from TB_AI_EXPLN_EVL_HSTRY where PRJ_SN = :projectId",
            // 내장 MCP (DEC-31) : 호출 이력·토큰·프로젝트 설정 (FK_MCP_TKN_01·FK_PRJ_MCP_STNG_01 → TB_PRJ)
            "delete from TB_MCP_CALL_HSTRY where PRJ_SN = :projectId",
            "delete from TB_MCP_TKN where PRJ_SN = :projectId",
            "delete from TB_PRJ_MCP_STNG where PRJ_SN = :projectId",
            // 프로젝트 AI Provider 지정 (DEC-18, FK_PRJ_AI_STNG_01 → TB_PRJ)
            "delete from TB_PRJ_AI_STNG where PRJ_SN = :projectId",
            // AI 설명 품질 평가·제안 (AI-5) : 모델 테이블에 FK 가 없다
            "delete from TB_AI_EXPLN_EVL where PRJ_SN = :projectId",
            "delete from TB_AI_EXPLN_EVL_TRGT where PRJ_SN = :projectId",
            "delete from TB_AI_EXPLN_SGSTN_HSTRY where PRJ_SN = :projectId",
            "delete from TB_AI_EXPLN_SGSTN where PRJ_SN = :projectId",
            "delete from TB_AI_EXPLN_CRT_EXCN where PRJ_SN = :projectId");

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    public void onProjectDeleted(ProjectDeletedEvent event) {
        entityManager.flush();
        int rows = 0;
        for (String sql : DELETES) {
            rows += entityManager.createNativeQuery(sql).setParameter("projectId", event.projectId()).executeUpdate();
        }
        log.info("프로젝트 삭제 : projectId={}, AI·MCP 데이터 {}행 삭제", event.projectId(), rows);
    }
}
