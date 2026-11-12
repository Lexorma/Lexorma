package com.lexorma.data.dictionary.service.impl;

import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.dictionary.service.StandardDomainService;
import com.lexorma.data.dictionary.service.StandardTermService;
import com.lexorma.data.dictionary.service.StandardWordService;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.common.utils.ExcelUtils;
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
        requireApprover();
        standardDomainService.approvalStandardDomains(standardDomains, accountDto, isApprovalAvailable);
    }

    @Override
    @Transactional
    public void approvalStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto, boolean isApprovalAvailable) {
        requireApprover();
        standardTermService.approvalStandardTerms(standardTerms, accountDto, isApprovalAvailable);
    }

    @Override
    public void approvalStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto, boolean isApprovalAvailable) {
        requireApprover();
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

    /**
     * 승인·승인취소는 승인 권한(관리자·매니저·프로젝트 관리자)만 : 테이블·컬럼·논리 모델의 승인과 같은 기준.
     * 역할(DBA 이상, 컨트롤러의 @PreAuthorize)만으로는 승인할 수 없다 (화면은 버튼을 숨기지만 API 직접 호출도 막는다)
     */
    private static void requireApprover() {
        if (!AuthUtil.isApprovalAvailable()) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
    }
}
