package com.lexorma.data.logical.repository;

import com.lexorma.data.logical.domain.entity.LogicalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LogicalEntityRepository extends JpaRepository<LogicalEntity, Long> {

    List<LogicalEntity> findAllByModel_IdOrderByEntityNameAscIdAsc(Long modelId);

    Optional<LogicalEntity> findByIdAndProjectId(Long id, Long projectId);

    @Query("select e.model.id from LogicalEntity e where e.id = :id and e.projectId = :projectId")
    Optional<Long> findModelId(@Param("id") Long id, @Param("projectId") Long projectId);

    boolean existsByModel_Id(Long modelId);

    long countByModel_Id(Long modelId);

    // ---- 논리 ERD 부분 조회 (M-6 후속 1차) : 검색 조건은 이름·설명(소문자 포함, '!' 이스케이프). 정렬은 전체 조회(findGraph)와 같은 이름 → ID
    String ERD_SEARCH = " from LogicalEntity e where e.model.id = :modelId"
            + " and (lower(e.entityName) like :pattern escape '!' or lower(coalesce(e.entityDescription, '')) like :pattern escape '!')";

    @Query("select e.id" + ERD_SEARCH + " order by e.entityName, e.id")
    List<Long> searchIds(@Param("modelId") Long modelId, @Param("pattern") String pattern, org.springframework.data.domain.Pageable pageable);

    @Query("select count(e)" + ERD_SEARCH)
    long countSearch(@Param("modelId") Long modelId, @Param("pattern") String pattern);

    /** 엔터티 찾기 목록 : ID·이름·승인 여부만 (속성을 읽지 않는다) */
    @Query("select e.id, e.entityName, e.isApproval" + ERD_SEARCH + " order by e.entityName, e.id")
    List<Object[]> searchHits(@Param("modelId") Long modelId, @Param("pattern") String pattern, org.springframework.data.domain.Pageable pageable);

    /** 고른 엔터티만 속성·표준 용어·도메인과 한 번에 (findGraph 와 같은 모양) */
    @Query("select distinct e from LogicalEntity e left join fetch e.attributes a left join fetch a.standardTerm t left join fetch t.standardDomain "
            + "where e.model.id = :modelId and e.id in :ids order by e.entityName, e.id")
    List<LogicalEntity> findGraphByIds(@Param("modelId") Long modelId, @Param("ids") java.util.Collection<Long> ids);

    @Query("select e.id from LogicalEntity e where e.model.id = :modelId")
    Set<Long> findIdsByModelId(@Param("modelId") Long modelId);

    boolean existsByModel_IdAndEntityNameIgnoreCaseAndIdNot(Long modelId, String entityName, Long id);

    /** 논리 ERD : 모델의 엔터티와 속성·표준 용어·도메인을 한 번에 */
    @Query("select distinct e from LogicalEntity e left join fetch e.attributes a left join fetch a.standardTerm t left join fetch t.standardDomain "
            + "where e.model.id = :modelId order by e.entityName, e.id")
    List<LogicalEntity> findGraph(@Param("modelId") Long modelId);

    /** 논리 → 물리 생성 : 고른 엔터티를 한 번에 (엔터티마다 따로 읽지 않는다 — M-7 보강 5차 측정) */
    @Query("select e from LogicalEntity e where e.id in :ids and e.projectId = :projectId")
    List<LogicalEntity> findAllByIds(@Param("ids") java.util.Collection<Long> ids, @Param("projectId") Long projectId);

    /** 이름으로 엔터티 찾기 (D-35 D35-2 : AI·MCP 상세 조회) : 대소문자 무시, (ID, 이름) — 같은 모델의 엔터티명은 대소문자 무시로 저장 때 중복이 막힌다 */
    @Query("select e.id, e.entityName from LogicalEntity e where e.model.id = :modelId and lower(e.entityName) = lower(:name) order by e.id")
    List<Object[]> findByNameIgnoreCase(@Param("modelId") Long modelId, @Param("name") String name);
}
