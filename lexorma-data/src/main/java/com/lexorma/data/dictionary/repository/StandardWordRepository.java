package com.lexorma.data.dictionary.repository;

import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Repository
public interface StandardWordRepository extends JpaRepository<StandardWord, Long> {
    Page<StandardWord> findAllByProjectId(Long projectId, Pageable pageable);
    List<StandardWord> findAllByProjectId(Long projectId);
    
    Page<StandardWord> findByRevisionNumberContainingAndProjectId(int revisionNumber, Long projectId, Pageable pageable);
    Page<StandardWord> findByCommonStandardWordNameContainingAndProjectId(String commonStandardWordName, Long projectId, Pageable pageable);
    Page<StandardWord> findByCommonStandardWordAbbreviationContainingIgnoreCaseAndProjectId(String commonStandardWordAbbreviation, Long projectId, Pageable pageable);
    Page<StandardWord> findByCommonStandardWordEnglishNameContainingAndProjectId(String commonStandardWordEnglishName, Long projectId, Pageable pageable);
    Page<StandardWord> findByCommonStandardWordDescriptionContainingAndProjectId(String commonStandardWordDescription, Long projectId, Pageable pageable);
    Page<StandardWord> findByIsFormatWordAndProjectId(boolean isFormatWord, Long projectId, Pageable pageable);
    Page<StandardWord> findByCommonStandardDomainCategoryContainingAndProjectId(String commonStandardDomainCategory, Long projectId, Pageable pageable);
    Page<StandardWord> findBySynonymListContainingAndProjectId(String synonym, Long projectId, Pageable pageable);
    Page<StandardWord> findByRestrictedWordsContainingAndProjectId(String restrictedWord, Long projectId, Pageable pageable);
    Page<StandardWord> findByIsApprovalAndProjectId(Boolean isApproval, Long projectId, Pageable pageable);
    Page<StandardWord> findAllByIdAndProjectId(Long searchQuery, Long projectId, Pageable pageable);

    StandardWord findByIdAndProjectId(Long id, Long projectId);

    /** 수정·승인이 잠근 행을 다시 읽어 비교하고 바꾼다 (행 배타 잠금, M8-C1 — 표준 용어 findForUpdate 와 같다) */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from StandardWord w where w.id = :id and w.projectId = :projectId")
    java.util.Optional<StandardWord> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id, @org.springframework.data.repository.query.Param("projectId") Long projectId);

    StandardWord findByCommonStandardWordAbbreviationAndProjectId(String commonStandardWordAbbreviation, Long projectId);

    StandardWord findBycommonStandardWordName(String commonStandardWordName);

    StandardWord findByCommonStandardWordNameAndProjectId(String s, Long projectId);

    List<StandardWord> findAllByProjectIdAndCommonStandardWordName(Long projectId, String commonStandardWordName);

    List<StandardWord> findAllByCommonStandardWordAbbreviationAndProjectId(String s, Long projectId);


    /** 엑셀 업로드 : 청크의 단어명을 한 번에 조회 (중복 확인) */
    List<StandardWord> findAllByProjectIdAndCommonStandardWordNameIn(Long projectId, Collection<String> commonStandardWordNames);

    /** 엑셀 업로드 : 청크의 영문약어를 한 번에 조회 (용어 ↔ 단어 매핑) */
    List<StandardWord> findAllByProjectIdAndCommonStandardWordAbbreviationIn(Long projectId, Collection<String> commonStandardWordAbbreviations);
}
