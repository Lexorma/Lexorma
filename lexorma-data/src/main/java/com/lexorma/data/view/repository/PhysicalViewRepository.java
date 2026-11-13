package com.lexorma.data.view.repository;

import com.lexorma.data.view.domain.entity.PhysicalView;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PhysicalViewRepository extends JpaRepository<PhysicalView, Long>, JpaSpecificationExecutor<PhysicalView> {

    Optional<PhysicalView> findByIdAndProjectId(Long id, Long projectId);

    List<PhysicalView> findAllByProjectIdAndIdIn(Long projectId, Collection<Long> ids);

    /** VIEW 행 잠금 : 같은 VIEW 의 수정·승인·변경 요청 처리·삭제를 직렬화한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PhysicalView v where v.id = :id and v.projectId = :projectId")
    Optional<PhysicalView> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 스키마 이름 공간 확인 (이름은 대문자로 저장한다, UX_VIEW_01) */
    Optional<PhysicalView> findFirstBySchema_IdAndViewName(Long schemaId, String viewName);

    /** 테이블 엑셀 업로드 : 그 스키마에서 청크의 이름 중 VIEW 가 쓰는 이름 */
    @Query("select v.viewName from PhysicalView v where v.schema.id = :schemaId and v.viewName in :names")
    List<String> findNamesIn(@Param("schemaId") Long schemaId, @Param("names") Collection<String> names);

    boolean existsBySchema_Id(Long schemaId);

    /** 프로젝트 VIEW 의 내부 VIEW 참조 (참조하는 VIEW ID, 참조되는 VIEW ID) : 순환 검사·DDL 순서 */
    @Query("select r.view.id, r.referencedView.id from PhysicalViewReference r where r.view.projectId = :projectId and r.referencedView is not null")
    List<Object[]> findViewEdges(@Param("projectId") Long projectId);

    /** 이 테이블을 참조하는 VIEW 이름 (테이블 삭제 확인) */
    @Query("select distinct r.view.viewName from PhysicalViewReference r where r.referencedTable.id in :tableIds order by r.view.viewName")
    List<String> findNamesReferencingTables(@Param("tableIds") Collection<Long> tableIds);

    /** 이 VIEW 를 참조하는 다른 VIEW 이름 (VIEW 삭제 확인) */
    @Query("select distinct r.view.viewName from PhysicalViewReference r where r.referencedView.id = :viewId and r.view.id <> :viewId order by r.view.viewName")
    List<String> findNamesReferencingView(@Param("viewId") Long viewId);
}
