package com.lexorma.data.project;

import com.lexorma.core.project.domain.event.ProjectDeletedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 프로젝트 영구 삭제 : 이 모듈이 가진 프로젝트 데이터를 모두 지운다 (데이터 사전, 물리·논리 모델·VIEW, 매핑·원본 매핑, ERD 배치, 변경 이력, 모델 버전).
 * AI·MCP 데이터는 lexorma-ai 가 같은 이벤트로 지운다 (모델 테이블로 가는 FK 가 없어 순서가 필요 없다).
 * <p>
 * 프로젝트 삭제 서비스가 프로젝트 행을 지우기 전에 발행하는 이벤트를 같은 스레드·같은 트랜잭션에서 받는다 ({@link EventListener} 는 동기 실행) :
 * 여기서 실패하면 예외가 그대로 올라가 프로젝트 삭제 전체가 롤백되고, 프로젝트 삭제가 롤백되면 여기서 지운 것도 되돌아간다.
 * 행 수가 많을 수 있어 엔티티를 읽지 않고 SQL 로 지운다. 그래서 변경 이력에는 남지 않는다 (이력도 함께 지운다).
 * 순서는 FK 를 따른다 : 참조하는 쪽 → 참조되는 쪽. 프로젝트 데이터 테이블을 더하면 여기에도 넣는다 (ProjectDeletionIntegrationTest 가 남은 행을 찾는다).
 */
@Component
public class ProjectDataCleanup {

    private static final Logger log = LoggerFactory.getLogger(ProjectDataCleanup.class);

    private static final String ENTITIES = "(select LGCL_ENTT_SN from TB_LGCL_ENTT where PRJ_SN = :projectId)";
    private static final String TERMS = "(select STD_TRM_SN from TB_STD_TRM where PRJ_SN = :projectId)";
    private static final String WORDS = "(select STD_WORD_SN from TB_STD_WORD where PRJ_SN = :projectId)";

    private static final List<String> DELETES = List.of(
            // 저장 품질 검사 (AI-6 2차, V4) : 결과 이력 → 결과 → 실행 건수·규칙 → 실행 (복합 FK 순서), 규칙 설정은 FK 가 없다
            "delete from TB_INSP_RSLT_HSTRY where PRJ_SN = :projectId",
            "delete from TB_INSP_RSLT where PRJ_SN = :projectId",
            "delete from TB_INSP_EXCN_RSLT where INSP_EXCN_SN in (select INSP_EXCN_SN from TB_INSP_EXCN where PRJ_SN = :projectId)",
            "delete from TB_INSP_EXCN_RULE where INSP_EXCN_SN in (select INSP_EXCN_SN from TB_INSP_EXCN where PRJ_SN = :projectId)",
            "delete from TB_INSP_EXCN where PRJ_SN = :projectId",
            "delete from TB_INSP_RULE_STNG where PRJ_SN = :projectId",
            // 논리 ↔ 물리 매핑
            // 원본 매핑 (M-4c) : 모델 스키마·테이블로 가는 FK 가 있어 먼저 지운다
            // AI 설명 제안·평가·프로젝트 AI 설정·내장 MCP 의 프로젝트 데이터는 lexorma-ai 의 AiProjectDataCleanup 이 지운다 (8차 모듈 분리)
            "delete from TB_SRC_IMPRT_EXCN where PRJ_SN = :projectId",
            // 출처 컬럼 매핑·매핑 이력 (DEC-06, V4) : 컬럼 매핑은 테이블 매핑·모델 컬럼으로 가는 FK 가 있어 그보다 먼저, 이력은 FK 가 없다
            "delete from TB_SRC_COL_MPNG where PRJ_SN = :projectId",
            "delete from TB_SRC_MPNG_HSTRY where PRJ_SN = :projectId",
            "delete from TB_SRC_TBL_MPNG where PRJ_SN = :projectId",
            "delete from TB_SRC_SCHM_MPNG where PRJ_SN = :projectId",
            "delete from TB_PRJ_SRC_CNTN where PRJ_SN = :projectId",
            "delete from TB_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN in (select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where PRJ_SN = :projectId)",
            // 상속 PK 매핑 (M-7 3차, V4) : 엔터티 매핑·논리 속성·모델 컬럼으로 가는 FK 가 있어 그보다 먼저 (프로젝트 전체 정리 — 일반 편집은 매핑을 지우지 않는다)
            "delete from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN in (select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where PRJ_SN = :projectId)",
            // 상속 A·C형 (M-7 상속 A·C, V2) : C형 상속 일반 속성 매핑(엔터티 매핑·속성·컬럼 FK), 변환 기록(그룹·테이블·컬럼 FK) — 그보다 먼저
            "delete from TB_SBTYP_ATRB_COL_MPNG where ENTT_TBL_MPNG_SN in (select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where PRJ_SN = :projectId)",
            "delete from TB_SBTYP_GROUP_CNVR where PRJ_SN = :projectId",
            "delete from TB_ENTT_TBL_MPNG where PRJ_SN = :projectId",
            // 논리 모델 : 배치·관계 → 식별자 → 속성 → 엔터티 → 모델
            "delete from TB_LGCL_ERD_LYT where PRJ_SN = :projectId",
            // 주제 영역 (M-6 후속 4차) : 영역별 배치 → 소속 → 영역 (자기 참조 FK 라 상위를 먼저 비운다)
            "delete from TB_LGCL_SBJ_AREA_LYT where PRJ_SN = :projectId",
            "delete from TB_LGCL_SBJ_AREA_ENTT where PRJ_SN = :projectId",
            "update TB_LGCL_SBJ_AREA set UP_SBJ_AREA_SN = null where PRJ_SN = :projectId",
            "delete from TB_LGCL_SBJ_AREA where PRJ_SN = :projectId",
            // 서브타입 (M-6 후속 5차) : 구성원 → 그룹 (그룹이 엔터티·분류 속성을 참조하므로 속성·엔터티보다 먼저)
            "delete from TB_LGCL_SBTYP where PRJ_SN = :projectId",
            "delete from TB_LGCL_SBTYP_GROUP where PRJ_SN = :projectId",
            "delete from TB_LGCL_REL_ATRB where LGCL_REL_SN in (select LGCL_REL_SN from TB_LGCL_REL where PRJ_SN = :projectId)",
            "delete from TB_LGCL_REL where PRJ_SN = :projectId",
            "delete from TB_LGCL_IDNTFR_ATRB where LGCL_IDNTFR_SN in (select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN in " + ENTITIES + ")",
            "delete from TB_LGCL_IDNTFR where LGCL_ENTT_SN in " + ENTITIES,
            "delete from TB_LGCL_ATRB where LGCL_ENTT_SN in " + ENTITIES,
            "delete from TB_LGCL_ENTT where PRJ_SN = :projectId",
            "delete from TB_LGCL_MDL where PRJ_SN = :projectId",
            // 물리 모델 : 관계·인덱스(변경 요청 → 컬럼 → 인덱스)·제약조건 → 배치 → 컬럼 → 테이블 → 스키마
            "delete from TB_TBL_REL_COL where REL_SN in (select REL_SN from TB_TBL_REL where PRJ_SN = :projectId)",
            "delete from TB_TBL_REL where PRJ_SN = :projectId",
            "delete from TB_IDX_CHG_DMND where PRJ_SN = :projectId",
            "delete from TB_TBL_IDX_COL where IDX_SN in (select IDX_SN from TB_TBL_IDX where PRJ_SN = :projectId)",
            "delete from TB_TBL_IDX where PRJ_SN = :projectId",
            "delete from TB_TBL_CNSTRT_COL where CNSTRT_SN in (select CNSTRT_SN from TB_TBL_CNSTRT where PRJ_SN = :projectId)",
            "delete from TB_TBL_CNSTRT where PRJ_SN = :projectId",
            // VIEW (M-15) : 참조 객체가 테이블·VIEW 를, VIEW 가 스키마를 참조하므로 테이블·스키마보다 먼저
            "delete from TB_VIEW_CHG_DMND where PRJ_SN = :projectId",
            "delete from TB_VIEW_RFRNC where VIEW_SN in (select VIEW_SN from TB_VIEW where PRJ_SN = :projectId)",
            "delete from TB_VIEW_COL where VIEW_SN in (select VIEW_SN from TB_VIEW where PRJ_SN = :projectId)",
            "delete from TB_VIEW where PRJ_SN = :projectId",
            "delete from TB_ERD_LYT where PRJ_SN = :projectId",
            "delete from TB_COL where PRJ_SN = :projectId",
            "delete from TB_TBL where PRJ_SN = :projectId",
            "delete from TB_SCHM where PRJ_SN = :projectId",
            // 데이터 사전 : 컬럼·속성이 용어를 참조하므로 모델 다음에 지운다. 공통 컬럼 → 용어 → 단어 → 도메인
            "delete from TB_CMN_COL where PRJ_SN = :projectId",
            "delete from TB_STD_TRM_WORD where STD_TRM_SN in " + TERMS,
            "delete from TB_STD_TRM_SYNM where STD_TRM_SN in " + TERMS,
            "delete from TB_STD_TRM where PRJ_SN = :projectId",
            "delete from TB_STD_WORD_SYNM where STD_WORD_SN in " + WORDS,
            "delete from TB_STD_WORD_PRHBT where STD_WORD_SN in " + WORDS,
            "delete from TB_STD_WORD where PRJ_SN = :projectId",
            "delete from TB_STD_DMN where PRJ_SN = :projectId",
            // 변경 이력 · 모델 버전 (모델 테이블로 가는 FK 가 없다)
            "delete from TB_MDL_CHG_HSTRY where PRJ_SN = :projectId",
            "delete from TB_MDL_VER where PRJ_SN = :projectId");

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    public void onProjectDeleted(ProjectDeletedEvent event) {
        entityManager.flush();
        int rows = 0;
        for (String sql : DELETES) {
            rows += entityManager.createNativeQuery(sql).setParameter("projectId", event.projectId()).executeUpdate();
        }
        log.info("프로젝트 삭제 : projectId={}, 모델·데이터 사전·이력·버전 {}행 삭제", event.projectId(), rows);
    }
}
