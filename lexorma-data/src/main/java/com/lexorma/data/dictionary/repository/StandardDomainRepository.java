package com.lexorma.data.dictionary.repository;

import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Repository
public interface StandardDomainRepository extends JpaRepository<StandardDomain, Long> {
    Page<StandardDomain> findAllByProjectId(Long projectId, Pageable pageable);

    Page<StandardDomain> findByRevisionNumberContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByCommonStandardDomainGroupNameContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByCommonStandardDomainCategoryContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByCommonStandardDomainNameContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByCommonStandardDomainDescriptionContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByDataTypeContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByDataLengthContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByDataDecimalLengthContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByStorageFormatContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByDisplayFormatContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByUnitContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByAllowedValuesContainingAndProjectId(String keyword, Long projectId, Pageable pageable);
    Page<StandardDomain> findByIsApprovalAndProjectId(Boolean isApproval, Long projectId, Pageable pageable);
    Page<StandardDomain> findAllByIdAndProjectId(Long searchQuery, Long projectId, Pageable pageable);

    StandardDomain findByIdAndProjectId(Long id, Long projectId);

    /** 수정·승인이 잠근 행을 다시 읽어 비교하고 바꾼다 (행 배타 잠금, M8-C1 — 표준 용어 findForUpdate 와 같다) */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from StandardDomain d where d.id = :id and d.projectId = :projectId")
    java.util.Optional<StandardDomain> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("projectId") Long projectId);

    List<StandardDomain> findAllByCommonStandardDomainCategoryAndProjectId(String splitDatum, Long projectId);

    /**
     * 이름이 대소문자를 무시하고 같은 도메인 (용어의 도메인 참조, [DICT-2] 후속). 등록 중복 검사({@link #findNamesIgnoreCase})와 같은 조건이다.
     * 예전 데이터에는 여러 건이 있을 수 있다 — 단건 조회로 바꾸지 않는다
     */
    @Query("select d from StandardDomain d where d.projectId = :projectId and lower(d.commonStandardDomainName) = lower(:name) order by d.id")
    List<StandardDomain> findAllByNameIgnoreCase(@Param("projectId") Long projectId, @Param("name") String name);

    /** 대소문자를 무시하고 같은 이름인 도메인의 이름 (등록 중복 검사, [DICT-2]) */
    @Query("select d.commonStandardDomainName from StandardDomain d where d.projectId = :projectId and lower(d.commonStandardDomainName) = lower(:name)")
    List<String> findNamesIgnoreCase(@Param("projectId") Long projectId, @Param("name") String name);

    /** 프로젝트의 모든 도메인명 (엑셀 업로드의 대소문자 무시 중복 판단) */
    @Query("select d.commonStandardDomainName from StandardDomain d where d.projectId = :projectId and d.commonStandardDomainName is not null")
    List<String> findAllNamesByProjectId(@Param("projectId") Long projectId);

    /** 이름이 대소문자를 무시하고 같은 도메인이 둘 이상인 경우의 그 도메인들 (기존 데이터 진단, [DICT-2]) */
    @Query("select d from StandardDomain d where d.projectId = :projectId and lower(d.commonStandardDomainName) in ("
            + "select lower(x.commonStandardDomainName) from StandardDomain x where x.projectId = :projectId and x.commonStandardDomainName is not null"
            + " group by lower(x.commonStandardDomainName) having count(x) > 1) order by lower(d.commonStandardDomainName), d.id")
    List<StandardDomain> findDuplicateNames(@Param("projectId") Long projectId);

    /** 도메인 ID → 그 도메인을 쓰는 표준 용어 수 */
    @Query("select t.standardDomain.id, count(t) from StandardTerm t where t.projectId = :projectId and t.standardDomain.id in :domainIds group by t.standardDomain.id")
    List<Object[]> countTermsByDomain(@Param("projectId") Long projectId, @Param("domainIds") Collection<Long> domainIds);

    List<StandardDomain> findAllByCommonStandardDomainCategoryAndCommonStandardDomainCategoryAndProjectId(String commonStandardDomainCategory, String commonStandardDomainCategory1, Long projectId);

    List<StandardDomain> findAllByProjectIdAndCommonStandardDomainCategory(Long projectId, String commonStandardDomainCategory);

    List<StandardDomain> findAllByProjectId(Long projectId);


    /** 엑셀 업로드 : 단어의 도메인 분류가 등록되어 있는지 */
    boolean existsByProjectIdAndCommonStandardDomainCategory(Long projectId, String commonStandardDomainCategory);

    /** 엑셀 용어 업로드 : 프로젝트 도메인의 ID·이름 (대소문자 무시 키로 한 번에 묶는다, [DICT-2] 후속) */
    @Query("select d.id, d.commonStandardDomainName from StandardDomain d where d.projectId = :projectId and d.commonStandardDomainName is not null")
    List<Object[]> findIdAndNamesByProjectId(@Param("projectId") Long projectId);
}
