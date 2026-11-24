package com.lexorma.data.dictionary.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.dictionary.domain.dto.DictionaryUsageDto;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 표준 항목 사용 현황 (M-5 1차, 조회 전용) : 표준 용어·도메인을 쓰는 컬럼, 표준 단어를 쓰는 용어, 목록의 사용 건수.
 * 프로젝트는 항상 로그인 프로젝트이고, 요청의 표준 항목 ID 가 그 프로젝트 것인지 먼저 확인한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DictionaryUsageService {

    private final DictionaryUsageRepository usageRepository;
    private final StandardTermRepository standardTermRepository;
    private final StandardDomainRepository standardDomainRepository;
    private final StandardWordRepository standardWordRepository;
    private final jakarta.persistence.EntityManager entityManager;

    public Page<DictionaryUsageDto.ColumnUsage> selectTermColumns(AccountDto accountDto, Long termId, DictionaryUsageDto.ColumnSearch search, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        if (standardTermRepository.findByIdAndProjectId(termId, projectId) == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA);
        }
        return usageRepository.findColumnsByTerm(projectId, termId, search, pageable);
    }

    public Page<DictionaryUsageDto.ColumnUsage> selectDomainColumns(AccountDto accountDto, Long domainId, DictionaryUsageDto.ColumnSearch search, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        if (standardDomainRepository.findByIdAndProjectId(domainId, projectId) == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_DOMAIN_DATA);
        }
        return usageRepository.findColumnsByDomain(projectId, domainId, search, pageable);
    }

    public Page<DictionaryUsageDto.TermUsage> selectWordTerms(AccountDto accountDto, Long wordId, String searchColumn, String searchQuery, Pageable pageable) {
        Long projectId = accountDto.getProjectId();
        if (standardWordRepository.findByIdAndProjectId(wordId, projectId) == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_WORD_DATA, "표준 단어를 찾을 수 없습니다.");
        }
        return usageRepository.findTermsByWord(projectId, wordId, searchColumn, searchQuery, pageable);
    }

    /** 도메인 승인 취소 전 영향 범위 (M8-C4c-1 C4c-D05) — 조회 시점의 참고 값이다. 승인 취소는 이 값으로 거절하지 않는다 */
    public DictionaryUsageDto.DomainImpact domainImpact(AccountDto accountDto, Long domainId) {
        Long projectId = accountDto.getProjectId();
        var domain = standardDomainRepository.findByIdAndProjectId(domainId, projectId);
        if (domain == null) throw new com.lexorma.common.exception.CustomException(com.lexorma.common.exception.domain.ErrorCode.NOT_FOUND_DOMAIN_DATA);
        Object[] terms = entityManager.createQuery("select count(t), coalesce(sum(case when t.isApproval = true then 1 else 0 end), 0) from StandardTerm t"
                + " where t.projectId = :projectId and t.standardDomain.id = :domainId", Object[].class)
                .setParameter("projectId", projectId).setParameter("domainId", domainId).getSingleResult();
        Long attributes = entityManager.createQuery("select count(a) from LogicalAttribute a where a.standardTerm.standardDomain.id = :domainId and a.standardTerm.projectId = :projectId", Long.class)
                .setParameter("projectId", projectId).setParameter("domainId", domainId).getSingleResult();
        Object[] columns = entityManager.createQuery("select count(c), coalesce(sum(case when c.isApproval = true then 1 else 0 end), 0) from ColumnInfo c"
                + " where c.projectId = :projectId and c.standardTerms.standardDomain.id = :domainId", Object[].class)
                .setParameter("projectId", projectId).setParameter("domainId", domainId).getSingleResult();
        return new DictionaryUsageDto.DomainImpact(domain.getId(), domain.getCommonStandardDomainName(), domain.getIsApproval(),
                ((Number) terms[0]).longValue(), ((Number) terms[1]).longValue(), attributes, ((Number) columns[0]).longValue(), ((Number) columns[1]).longValue());
    }

    // ------------------------------------------------------------------ 목록의 사용 건수 (페이지당 조회 1번)

    /** 표준 용어 목록 : 용어를 쓰는 컬럼 수 */
    public void fillTermUsage(AccountDto accountDto, List<StandardTermDto> terms) {
        Map<Long, Long> counts = usageRepository.countColumnsByTerm(accountDto.getProjectId(), terms.stream().map(StandardTermDto::getId).toList());
        terms.forEach(term -> term.setUsageCount(counts.getOrDefault(term.getId(), 0L)));
    }

    /** 표준 도메인 목록 : 도메인의 용어를 쓰는 컬럼 수 */
    public void fillDomainUsage(AccountDto accountDto, List<StandardDomainDto> domains) {
        Map<Long, Long> counts = usageRepository.countColumnsByDomain(accountDto.getProjectId(), domains.stream().map(StandardDomainDto::getId).toList());
        domains.forEach(domain -> domain.setUsageCount(counts.getOrDefault(domain.getId(), 0L)));
    }

    /** 표준 단어 목록 : 단어를 쓰는 표준 용어 수 */
    public void fillWordUsage(AccountDto accountDto, List<StandardWordDto> words) {
        Map<Long, Long> counts = usageRepository.countTermsByWord(accountDto.getProjectId(), words.stream().map(StandardWordDto::getId).toList());
        words.forEach(word -> word.setUsageCount(counts.getOrDefault(word.getId(), 0L)));
    }
}
