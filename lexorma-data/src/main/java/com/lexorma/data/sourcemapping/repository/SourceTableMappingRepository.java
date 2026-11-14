package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceTableMappingRepository extends JpaRepository<SourceTableMapping, Long> {

    List<SourceTableMapping> findAllByProjectIdAndConnectionId(Long projectId, Long connectionId);

    Optional<SourceTableMapping> findByIdAndProjectId(Long id, Long projectId);

    Optional<SourceTableMapping> findByProjectIdAndConnectionIdAndSourceTableId(Long projectId, Long connectionId, Long sourceTableId);

    Optional<SourceTableMapping> findByProjectIdAndConnectionIdAndTableId(Long projectId, Long connectionId, Long tableId);

    List<SourceTableMapping> findAllByProjectIdAndConnectionIdAndSourceSchemaId(Long projectId, Long connectionId, Long sourceSchemaId);

    /** 연결별 TABLE 매핑 수 (매핑 예외는 세지 않는다) */
    @Query("select m.connectionId, count(m) from SourceTableMapping m where m.projectId = :projectId and m.excluded = false group by m.connectionId")
    List<Object[]> countByConnection(@Param("projectId") Long projectId);

    /** 프로젝트의 모델 테이블 요약 (ID, 이름, 스키마 ID, 한글명, 업무 설명, 원본 COMMENT 칸) : 후보·상태 계산·화면 표시에 한 번에 읽는다 */
    @Query("select t.id, t.tableName, t.schema.id, t.tableKorName, t.tableDescription, t.sourceComment from TableInfo t where t.projectId = :projectId")
    List<Object[]> modelTables(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from SourceTableMapping m where m.projectId = :projectId and m.connectionId = :connectionId")
    int deleteByConnection(@Param("projectId") Long projectId, @Param("connectionId") Long connectionId);
}
