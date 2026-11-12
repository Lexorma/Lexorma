package com.lexorma.data.version.repository;

import com.lexorma.data.version.domain.entity.ModelVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

    Optional<ModelVersion> findByIdAndProjectId(Long id, Long projectId);

    boolean existsByProjectIdAndVersionName(Long projectId, String versionName);

    String ROW = "select v.id, v.versionName, v.description, v.formatVersion, v.counts, v.createdBy, v.createdAt from ModelVersion v where v.projectId = :projectId";

    /** 목록용 : 모델 내용(MDL_CN)은 읽지 않는다. 최신 순 (같은 시각은 나중에 만든 것부터) */
    @Query(value = ROW + " order by v.createdAt desc, v.id desc", countQuery = "select count(v) from ModelVersion v where v.projectId = :projectId")
    Page<Object[]> findRows(@Param("projectId") Long projectId, Pageable pageable);

    /** 상세용 : 다른 프로젝트의 버전은 없는 것과 같다 */
    @Query(ROW + " and v.id = :id")
    List<Object[]> findRow(@Param("projectId") Long projectId, @Param("id") Long id);
}
