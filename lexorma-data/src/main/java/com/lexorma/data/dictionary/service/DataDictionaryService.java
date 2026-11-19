package com.lexorma.data.dictionary.service;

import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.core.logs.domain.dto.LogsDto;
import com.lexorma.core.security.domain.dto.AccountDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface DataDictionaryService {
    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> TERM_SORT_FIELDS = java.util.Set.of("id", "revisionNumber", "commonStandardTermName", "commonStandardTermDescription", "commonStandardTermAbbreviation", "commonStandardDomainName", "allowedValues", "storageFormat", "displayFormat", "administrativeStandardCodeName", "responsibleOrganization");

    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> WORD_SORT_FIELDS = java.util.Set.of("id", "revisionNumber", "commonStandardWordName", "commonStandardWordAbbreviation", "commonStandardWordEnglishName", "commonStandardWordDescription", "commonStandardDomainCategory");

    /** 정렬할 수 있는 화면 필드 = 화면(columns.ts)에 정렬 가능으로 정의한 열(숨긴 열 포함 — SmokeTest.gridSortAndSearch 계약) + 기본 정렬 열 (PageableUtil 허용 목록 — 공통 페이징 안정성 개선) */
    java.util.Set<String> DOMAIN_SORT_FIELDS = java.util.Set.of("id", "revisionNumber", "commonStandardDomainGroupName", "commonStandardDomainCategory", "commonStandardDomainName", "commonStandardDomainDescription", "dataType", "dataLength", "dataDecimalLength", "storageFormat", "displayFormat", "unit", "allowedValues");


    Page<StandardTermDto> getStandardTermsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery);
    Page<StandardWordDto> getStandardWordsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery);
    Page<StandardDomainDto> getStandardDomainsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery);

    void uploadDictionaryExcelFile(MultipartFile file, AccountDto accountDto) throws IOException;

    void approvalStandardDomains(List<StandardDomainDto> standardDomains, AccountDto accountDto, boolean isApprovalAvailable);

    void approvalStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto, boolean isApprovalAvailable);

    void approvalStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto, boolean isApprovalAvailable);

    void deleteDataDictionaryStandardDomains(List<StandardDomainDto> standardDomains, AccountDto accountDto);
    void deleteDataDictionaryStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto);
    void deleteDataDictionaryStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto);

    Map<String, Object> getStandardTermList(String termNm, AccountDto accountDto);

    /**
     * 같은 영문약어명 + 도메인의 표준용어가 이미 있는지 확인한다. (등록 화면 실시간 중복 확인)
     * @return duplicated(중복 여부), terms(중복된 용어명/승인여부 목록)
     */
    Map<String, Object> checkStandardTermDuplicate(String abbreviation, String domainName, AccountDto accountDto);

    void insertStandardTerms(AccountDto accountDto, StandardTermDto standardTermDto);
    void updateStandardTerms(AccountDto accountDto, StandardTermDto standardTermDto);

    void insertStandardDomain(AccountDto accountDto, StandardDomainDto standardDomainDto);
    void updateStandardDomain(AccountDto accountDto, StandardDomainDto standardDomainDto);

    void updateStandardWords(AccountDto accountDto, StandardWordDto standardWordDto);
    void insertStandardWord(AccountDto accountDto, StandardWordDto standardWordDto);
}
