package com.lexorma.source.cleanup;

import com.lexorma.core.project.domain.event.ProjectDeletedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 원본 카탈로그 삭제 (연결 하나 / 프로젝트 전체). 행이 많을 수 있어 엔티티를 읽지 않고 SQL 로 지운다.
 * 순서는 FK 를 따른다 : 변경 이력 → 테이블·스키마 버전 → 수집 대상 → 수집 실행 (AI-1c) → 관계 → 인덱스 → 제약조건 → 컬럼 → 테이블 → 스키마 (→ 연결).
 * 수집 이력·스냅샷도 연결과 함께 지운다 (U-25 : 보존 기간 없음).
 * <p>
 * <b>프로젝트 영구 삭제</b>는 기존 방식 그대로 이벤트로 받는다 : 삭제 서비스(lexorma-system)가 프로젝트 행을 지우기 전에 발행하는
 * {@link ProjectDeletedEvent} 를 같은 스레드·같은 트랜잭션에서 처리한다 ({@link EventListener} 는 동기 실행).
 * 여기서 실패하면 프로젝트 삭제 전체가 롤백되고, 프로젝트 삭제가 롤백되면 여기서 지운 것도 되돌아간다.
 * 이벤트가 core 에 있어 system·data 가 이 모듈을 몰라도 되고, 원본 카탈로그는 다른 모듈의 테이블과 FK 로 이어져 있지 않아 모듈 사이의 순서가 필요 없다.
 * <p>
 * 물리·논리 모델과 데이터 사전은 건드리지 않는다. 테이블을 더하면 두 목록에 넣는다 (ProjectDeletionIntegrationTest 와 SourceCatalogIntegrationTest 가 남은 행을 찾는다).
 */
// @Repository : SQL 실행 오류(FK 위반 등)를 Spring 의 DataAccessException 으로 바꿔 받는다
@Repository
public class SourceCatalogCleanup {

    private static final String TABLES_OF_CONNECTION = "(select SRC_TBL_SN from TB_SRC_TBL where SRC_CNTN_SN = :id)";

    /** 연결 하나의 카탈로그 (연결 행은 남긴다) */
    private static final List<String> BY_CONNECTION = List.of(
            "delete from TB_SRC_CHG_HSTRY where SRC_CNTN_SN = :id",
            "delete from TB_SRC_TBL_VER where SRC_CNTN_SN = :id",
            "delete from TB_SRC_SCHM_VER where SRC_CNTN_SN = :id",
            "delete from TB_SRC_CLCT_TRGT where SRC_CLCT_EXCN_SN in (select SRC_CLCT_EXCN_SN from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = :id)",
            "delete from TB_SRC_CLCT_EXCN where SRC_CNTN_SN = :id",
            "delete from TB_SRC_REL_COL where SRC_REL_SN in (select SRC_REL_SN from TB_SRC_REL where SRC_CNTN_SN = :id)",
            "delete from TB_SRC_REL where SRC_CNTN_SN = :id",
            "delete from TB_SRC_IDX_COL where SRC_IDX_SN in (select SRC_IDX_SN from TB_SRC_IDX where SRC_TBL_SN in " + TABLES_OF_CONNECTION + ")",
            "delete from TB_SRC_IDX where SRC_TBL_SN in " + TABLES_OF_CONNECTION,
            "delete from TB_SRC_CNSTRT_COL where SRC_CNSTRT_SN in (select SRC_CNSTRT_SN from TB_SRC_CNSTRT where SRC_TBL_SN in " + TABLES_OF_CONNECTION + ")",
            "delete from TB_SRC_CNSTRT where SRC_TBL_SN in " + TABLES_OF_CONNECTION,
            "delete from TB_SRC_COL where SRC_TBL_SN in " + TABLES_OF_CONNECTION,
            "delete from TB_SRC_TBL where SRC_CNTN_SN = :id",
            "delete from TB_SRC_SCHM where SRC_CNTN_SN = :id");

    /** 프로젝트의 카탈로그와 연결 전부 */
    private static final List<String> BY_PROJECT = List.of(
            "delete from TB_SRC_CHG_HSTRY where PRJ_SN = :projectId",
            "delete from TB_SRC_TBL_VER where PRJ_SN = :projectId",
            "delete from TB_SRC_SCHM_VER where PRJ_SN = :projectId",
            "delete from TB_SRC_CLCT_TRGT where PRJ_SN = :projectId",
            "delete from TB_SRC_CLCT_EXCN where PRJ_SN = :projectId",
            "delete from TB_SRC_REL_COL where SRC_REL_SN in (select SRC_REL_SN from TB_SRC_REL where PRJ_SN = :projectId)",
            "delete from TB_SRC_REL where PRJ_SN = :projectId",
            "delete from TB_SRC_IDX_COL where SRC_IDX_SN in (select SRC_IDX_SN from TB_SRC_IDX where PRJ_SN = :projectId)",
            "delete from TB_SRC_IDX where PRJ_SN = :projectId",
            "delete from TB_SRC_CNSTRT_COL where SRC_CNSTRT_SN in (select SRC_CNSTRT_SN from TB_SRC_CNSTRT where PRJ_SN = :projectId)",
            "delete from TB_SRC_CNSTRT where PRJ_SN = :projectId",
            "delete from TB_SRC_COL where PRJ_SN = :projectId",
            "delete from TB_SRC_TBL where PRJ_SN = :projectId",
            "delete from TB_SRC_SCHM where PRJ_SN = :projectId",
            "delete from TB_SRC_CNTN where PRJ_SN = :projectId");

    private static final Logger log = LoggerFactory.getLogger(SourceCatalogCleanup.class);

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    public void onProjectDeleted(ProjectDeletedEvent event) {
        entityManager.flush();
        int rows = 0;
        for (String sql : BY_PROJECT) {
            rows += entityManager.createNativeQuery(sql).setParameter("projectId", event.projectId()).executeUpdate();
        }
        entityManager.clear();
        log.info("프로젝트 원본 카탈로그 삭제 : projectId={}, rows={}", event.projectId(), rows);
    }

    /** 연결의 카탈로그를 모두 지운다. 지운 행 수를 돌려준다 */
    public int deleteCatalog(Long connectionId) {
        entityManager.flush();
        int rows = 0;
        for (String sql : statements()) {
            rows += entityManager.createNativeQuery(sql).setParameter("id", connectionId).executeUpdate();
        }
        // SQL 로 지운 행이 영속성 컨텍스트에 남아 있지 않게 한다
        entityManager.clear();
        return rows;
    }

    /** 테스트에서 삭제 도중의 실패를 흉내 낼 수 있게 목록을 메서드로 둔다 */
    List<String> statements() {
        return new ArrayList<>(BY_CONNECTION);
    }
}
