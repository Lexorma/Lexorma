package com.lexorma.data.dictionary.repository;

import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
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
public interface StandardTermRepository extends JpaRepository<StandardTerm, Long> {

    Page<StandardTerm> findAllByProjectId(Long projectId, Pageable pageable);
    List<StandardTerm> findAllByProjectId(Long projectId);
    
    Page<StandardTerm> findAllByIdAndProjectId(Long searchQuery, Long projectId, Pageable pageable);

    Page<StandardTerm> findByRevisionNumberAndProjectId(int revisionNumber, Long projectId, Pageable pageable);
    Page<StandardTerm> findByCommonStandardTermNameContainingAndProjectId(String name, Long projectId, Pageable pageable);
    Page<StandardTerm> findByCommonStandardTermDescriptionContainingAndProjectId(String desc, Long projectId, Pageable pageable);
    Page<StandardTerm> findByCommonStandardTermAbbreviationContainingIgnoreCaseAndProjectId(String abbr, Long projectId, Pageable pageable);
    Page<StandardTerm> findByStandardDomain_CommonStandardDomainNameContainingAndProjectId(String domainName, Long projectId, Pageable pageable);
    Page<StandardTerm> findByStandardDomain_StorageFormatContainingAndProjectId(String storageFormat, Long projectId, Pageable pageable);
    Page<StandardTerm> findByAdministrativeStandardCodeNameContainingAndProjectId(String code, Long projectId, Pageable pageable);
    Page<StandardTerm> findByResponsibleOrganizationContainingAndProjectId(String org, Long projectId, Pageable pageable);
    Page<StandardTerm> findBySynonymListContainingAndProjectId(String synonym, Long projectId, Pageable pageable);
    Page<StandardTerm> findByIsApprovalAndProjectId(Boolean isApproval, Long projectId, Pageable pageable);
    Page<StandardTerm> findByStandardDomain_AllowedValuesContainingAndProjectId(String searchQuery, Long projectId, Pageable pageable);
    Page<StandardTerm> findByStandardDomain_DisplayFormatContainingAndProjectId(String searchQuery, Long projectId, Pageable pageable);

    // 단건 조회
    StandardTerm findByIdAndProjectId(Long id, Long projectId);

    /**
     * 중복 기준 (M-5 5차) : 같은 프로젝트 + 영문약어 대소문자 무시 + 실제 도메인 ID. 앞뒤 공백·'_' 등 다른 정규화는 하지 않는다.
     * 승인·미승인 모두 포함한다 (등록 중복 검사의 기존 범위)
     */
    @Query("select t from StandardTerm t where t.projectId = :projectId and t.standardDomain.id = :domainId"
            + " and lower(t.commonStandardTermAbbreviation) = lower(:abbreviation) order by t.id")
    List<StandardTerm> findDuplicates(@Param("projectId") Long projectId, @Param("abbreviation") String abbreviation, @Param("domainId") Long domainId);

    /** 엑셀 업로드의 중복 키 : 약어(소문자 비교 키 목록)에 해당하는 기존 용어의 [영문약어, 도메인 ID] */
    @Query("select t.commonStandardTermAbbreviation, t.standardDomain.id from StandardTerm t where t.projectId = :projectId"
            + " and lower(t.commonStandardTermAbbreviation) in :abbreviationKeys")
    List<Object[]> findAbbreviationDomainsByKeys(@Param("projectId") Long projectId, @Param("abbreviationKeys") Collection<String> abbreviationKeys);

    /** 표준 용어 진단 상세 : 등록·수정 일시 [ID, 등록, 수정] (한 페이지의 용어만) */
    @Query("select t.id, t.created, t.updated from StandardTerm t where t.projectId = :projectId and t.id in :ids")
    List<Object[]> findAuditByIds(@Param("projectId") Long projectId, @Param("ids") Collection<Long> ids);


    List<StandardTerm> findAllByProjectIdAndCommonStandardTermAbbreviationContainingIgnoreCase(Long projectId, String commonStandardTermAbbreviation);

    List<StandardTerm> findAllByProjectIdAndCommonStandardTermNameContaining(Long projectId, String commonStandardTermName);

    List<StandardTerm> findALlByProjectIdAndCommonStandardTermName(Long projectId, String commonStandardTermName);

    List<StandardTerm> findALlByProjectIdAndCommonStandardTermAbbreviation(Long projectId, String commonStandardTermAbbreviation);


    /** 엑셀 업로드 : 청크의 영문약어(컬럼명)를 한 번에 조회 */
    List<StandardTerm> findAllByProjectIdAndCommonStandardTermAbbreviationIn(Long projectId, Collection<String> commonStandardTermAbbreviations);

    // 컬럼 일괄 편집 붙여넣기 : 용어명 여러 개를 한 번에 찾는다
    List<StandardTerm> findAllByProjectIdAndCommonStandardTermNameIn(Long projectId, Collection<String> commonStandardTermNames);
}
