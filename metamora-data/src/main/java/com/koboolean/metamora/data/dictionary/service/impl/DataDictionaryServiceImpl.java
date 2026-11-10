package com.koboolean.metamora.data.dictionary.service.impl;

import com.koboolean.metamora.data.dictionary.domain.dto.StandardDomainDto;
import com.koboolean.metamora.data.dictionary.domain.dto.StandardTermDto;
import com.koboolean.metamora.data.dictionary.domain.dto.StandardWordDto;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardDomain;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardTerm;
import com.koboolean.metamora.data.dictionary.domain.entity.StandardWord;
import com.koboolean.metamora.data.dictionary.repository.StandardDomainRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardTermRepository;
import com.koboolean.metamora.data.dictionary.repository.StandardWordRepository;
import com.koboolean.metamora.data.dictionary.service.DataDictionaryService;
import com.koboolean.metamora.data.dictionary.service.StandardDomainService;
import com.koboolean.metamora.data.dictionary.service.StandardTermService;
import com.koboolean.metamora.data.dictionary.service.StandardWordService;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.util.AuthUtil;
import com.koboolean.metamora.common.utils.ExcelUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataDictionaryServiceImpl implements DataDictionaryService {

    private final StandardWordService standardWordService;
    private final StandardDomainService standardDomainService;
    private final StandardTermService standardTermService;

    @Override
    public Page<StandardTermDto> getStandardTermsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery) {
        return standardTermService.getStandardTermsData(pageable, accountDto, searchColumn, searchQuery);
    }

    @Override
    public Page<StandardWordDto> getStandardWordsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery) {
        return standardWordService.getStandardWordsData(pageable, accountDto, searchColumn, searchQuery);
    }

    @Override
    public Page<StandardDomainDto> getStandardDomainsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery) {
        return standardDomainService.getStandardDomainsData(pageable, accountDto, searchColumn, searchQuery);
    }

    @Override
    @Transactional
    public void uploadDictionaryExcelFile(MultipartFile file, AccountDto accountDto) throws IOException {

        Long projectId = accountDto.getProjectId();

        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        standardDomainService.setStandardDomain(file, projectId, isApprovalAvailable);
        standardWordService.setStandardWord(file, projectId, isApprovalAvailable);
        standardTermService.setStandardTerm(file, projectId, isApprovalAvailable);
    }

    @Override
    @Transactional
    public void approvalStandardDomains(List<StandardDomainDto> standardDomains, AccountDto accountDto, boolean isApprovalAvailable) {
        standardDomainService.approvalStandardDomains(standardDomains, accountDto, isApprovalAvailable);
    }

    @Override
    @Transactional
    public void approvalStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto, boolean isApprovalAvailable) {
        standardTermService.approvalStandardTerms(standardTerms, accountDto, isApprovalAvailable);
    }

    @Override
    public void approvalStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto, boolean isApprovalAvailable) {
        standardWordService.approvalStandardWords(standardWords, accountDto, isApprovalAvailable);
    }

    @Override
    @Transactional
    public void deleteDataDictionaryStandardDomains(List<StandardDomainDto> standardDomains, AccountDto accountDto) {
        standardDomainService.deleteDataDictionaryStandardDomains(standardDomains, accountDto);
    }

    @Override
    @Transactional
    public void deleteDataDictionaryStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto) {
        standardTermService.deleteDataDictionaryStandardTerms(standardTerms, accountDto);
    }

    @Override
    @Transactional
    public void deleteDataDictionaryStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto) {
        standardWordService.deleteDataDictionaryStandardWords(standardWords, accountDto);
    }

    @Override
    public Map<String, Object> getStandardTermList(String termNm, AccountDto accountDto) {
        Map<String, Object> result = new HashMap<>();

        Long projectId = accountDto.getProjectId();

        List<StandardWordDto> list = new ArrayList<>();

        // 단어 구분 : "_" 또는 공백 (사용자_이름 / 사용자 이름)
        String[] splitData = termNm.trim().split("[_\\s]+");

        for (int i = 0; i < splitData.length; i++) {
            StandardWord standardWord = standardWordService.commonStandardWordName(splitData[i], projectId);
            if (standardWord != null) {
                list.add(StandardWordDto.fromEntity(standardWord));
            }

            if(i == splitData.length - 1 && standardWord != null) {
                List<StandardDomain> standardDomains = standardDomainService.getStandardDomains(standardWord.getCommonStandardDomainCategory(), projectId);
                List<StandardDomainDto> standardDomainDtos = standardDomains.stream()
                        .map(StandardDomainDto::fromEntity)
                        .toList();

                result.put("standardDomains", standardDomainDtos);
            }

        }

        result.put("list", list);

        return result;
    }

    @Override
    public Map<String, Object> checkStandardTermDuplicate(String abbreviation, String domainName, AccountDto accountDto) {
        List<Map<String, String>> terms = standardTermService.findDuplicateTerms(abbreviation, domainName, accountDto.getProjectId()).stream()
                .map(term -> Map.of(
                        "commonStandardTermName", String.valueOf(term.getCommonStandardTermName()),
                        "isApprovalYn", Boolean.TRUE.equals(term.getIsApproval()) ? "Y" : "N"))
                .toList();

        return Map.of("duplicated", !terms.isEmpty(), "terms", terms);
    }

    @Override
    @Transactional
    public void insertStandardTerms(AccountDto accountDto, StandardTermDto standardTermDto) {
        String domainName = standardTermDto.getCommonStandardTermAbbreviation();

        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        standardTermService.saveStandardTerms(accountDto.getProjectId(), isApprovalAvailable, standardTermDto, domainName.split("_"));
    }

    @Override
    public void updateStandardTerms(AccountDto accountDto, StandardTermDto standardTermDto) {
        String domainName = standardTermDto.getCommonStandardTermAbbreviation();

        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        standardTermService.updateStandardTerms(accountDto.getProjectId(), isApprovalAvailable, standardTermDto, domainName.split("_"));
    }

    @Override
    public void insertStandardDomain(AccountDto accountDto, StandardDomainDto standardDomainDto) {

        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        standardDomainDto.setProjectId(accountDto.getProjectId());
        standardDomainDto.setIsApprovalYn(isApprovalAvailable ? "Y" : "N");
        StandardDomain standardDomain = StandardDomain.fromEntity(standardDomainDto);

        standardDomainService.insertStandardDomain(standardDomain);
    }

    @Override
    public void updateStandardDomain(AccountDto accountDto, StandardDomainDto standardDomainDto) {
        standardDomainDto.setProjectId(accountDto.getProjectId());

        standardDomainService.updateStandardDomain(standardDomainDto);
    }

    @Override
    public void updateStandardWords(AccountDto accountDto, StandardWordDto standardWordDto) {
        standardWordDto.setProjectId(accountDto.getProjectId());

        standardWordService.updateStandardWords(standardWordDto);
    }

    @Override
    public void insertStandardWord(AccountDto accountDto, StandardWordDto standardWordDto) {
        // 관리자의 경우 승인으로 저장, 아닐경우 관리자가 승인할 수 있도록 저장
        boolean isApprovalAvailable = AuthUtil.isApprovalAvailable();

        standardWordDto.setUseAbbreviation(isApprovalAvailable ? "Y" : "N");
        standardWordDto.setProjectId(accountDto.getProjectId());
        standardWordDto.setIsApprovalYn(isApprovalAvailable ? "Y" : "N");
        StandardWord standardWord = StandardWord.fromEntity(standardWordDto);

        standardWordService.insertStandardWords(standardWord);
    }
}
