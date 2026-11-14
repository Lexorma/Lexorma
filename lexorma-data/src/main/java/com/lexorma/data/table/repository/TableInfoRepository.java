package com.lexorma.data.table.repository;

import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TableInfoRepository extends JpaRepository<TableInfo, Long>, JpaSpecificationExecutor<TableInfo> {
    Page<TableInfo> findAllByProjectId(Long projectId, Pageable pageable);

    TableInfo findByIdAndProjectId(Long id, Long projectId);

    List<TableInfo> findAllByTableNameAndProjectId(String tableName, Long projectId);

    /** 스키마 이름 공간 확인 (이름은 대문자로 저장한다, UX_TBL_01) */
    List<TableInfo> findAllBySchema_IdAndTableName(Long schemaId, String tableName);

    /** 테이블 엑셀 업로드 : 그 스키마에서 청크의 테이블명을 한 번에 조회 */
    List<TableInfo> findAllBySchema_IdAndTableNameIn(Long schemaId, Collection<String> tableNames);

    Page<TableInfo> findAllByProjectIdAndTableNameLike(Long projectId, String tableName, Pageable pageable);

    Page<TableInfo> findAllByProjectIdAndTableDescriptionLike(Long projectId, String tableDescription, Pageable pageable);

    Page<TableInfo> findAllByProjectIdAndTableKorNameLike(Long projectId, String tableKorName, Pageable pageable);

    List<TableInfo> findAllByProjectIdAndTableNameLikeAndIsApproval(Long projectId, String tableName, Boolean isApproval);

    TableInfo findALlByProjectIdAndId(Long projectId, Long tableId);

    /** 이름 후보 조회 (모든 스키마) : 참조 테이블명·컬럼 엑셀의 테이블명은 {@code TableNameResolver} 로 스키마를 가린다 */
    List<TableInfo> findAllByProjectIdAndTableNameIn(Long projectId, Collection<String> tableNames);

    /** 테이블 행 잠금 : 논리·물리 매핑 등록과 테이블·컬럼 삭제를 직렬화한다 (M-7) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TableInfo t where t.id = :id and t.projectId = :projectId")
    Optional<TableInfo> findForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    /** 스키마 삭제 가능 여부 */
    boolean existsBySchema_Id(Long schemaId);
}
