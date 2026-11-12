package com.lexorma.core.logs.repository;

import com.lexorma.core.logs.domain.entity.Logs;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogsRepository extends JpaRepository<Logs, Long> {

    Page<Logs> findAllByProjectId(Long projectId, Pageable pageable);

    // 동적 검색을 위한 메서드들
    Page<Logs> findByProjectIdAndLogUrlContaining(Long projectId, String logUrl, Pageable pageable);
    Page<Logs> findByProjectIdAndMethodContaining(Long projectId, String method, Pageable pageable);
    Page<Logs> findByProjectIdAndIpContaining(Long projectId, String ip, Pageable pageable);
    Page<Logs> findByProjectIdAndUsernameContaining(Long projectId, String username, Pageable pageable);
    // statusCode 는 숫자 컬럼이라 Containing(LIKE) 파생 쿼리를 쓸 수 없다 : 문자열로 바꿔 부분 일치로 찾는다.
    @Query("select l from Logs l where l.projectId = :projectId and cast(l.statusCode as string) like concat('%', :statusCode, '%')")
    Page<Logs> findByProjectIdAndStatusCodeContaining(@Param("projectId") Long projectId, @Param("statusCode") String statusCode, Pageable pageable);
    Page<Logs> findByProjectIdAndRequestBodyContaining(Long projectId, String requestBody, Pageable pageable);
    Page<Logs> findByProjectIdAndResponseBodyContaining(Long projectId, String responseBody, Pageable pageable);
    Page<Logs> findByProjectIdAndErrorMessageContaining(Long projectId, String errorMessage, Pageable pageable);

    /** 프로젝트 영구 삭제 : 그 프로젝트의 요청 로그 (프로젝트 안에서만 조회되는 데이터다) */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from Logs l where l.projectId = :projectId")
    int deleteAllByProjectId(@Param("projectId") Long projectId);
}
