package com.lexorma.data.dictionary.service;

import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.repository.DictionaryUsageRepository;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.utils.ExcelUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StandardDomainService {

    private final StandardDomainRepository standardDomainRepository;
    private final ProjectRepository projectRepository;
    private final DictionaryUsageRepository usageRepository;
    private final EntityManager entityManager;
    private static final int FIRST_ROW = 1;

    public Page<StandardDomainDto> getStandardDomainsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery) {
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            Page<StandardDomain> allByProjectId = standardDomainRepository.findAllByProjectId(accountDto.getProjectId(), pageable);
            return allByProjectId.map(StandardDomainDto::fromEntity);
        }

        Page<StandardDomain> searchResult = switch (searchColumn) {
            case "id" -> standardDomainRepository.findAllByIdAndProjectId(Long.parseLong(searchQuery.replaceAll("[^0-9]", "")), Long.parseLong(accountDto.getProjectId().toString()), pageable);
            case "revisionNumber" ->
                    standardDomainRepository.findByRevisionNumberContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardDomainGroupName" ->
                    standardDomainRepository.findByCommonStandardDomainGroupNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardDomainCategory" ->
                    standardDomainRepository.findByCommonStandardDomainCategoryContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardDomainName" ->
                    standardDomainRepository.findByCommonStandardDomainNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardDomainDescription" ->
                    standardDomainRepository.findByCommonStandardDomainDescriptionContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "dataType" ->
                    standardDomainRepository.findByDataTypeContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "dataLength" ->
                    standardDomainRepository.findByDataLengthContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "dataDecimalLength" ->
                    standardDomainRepository.findByDataDecimalLengthContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "storageFormat" ->
                    standardDomainRepository.findByStorageFormatContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "displayFormat" ->
                    standardDomainRepository.findByDisplayFormatContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "unit" ->
                    standardDomainRepository.findByUnitContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "allowedValues" ->
                    standardDomainRepository.findByAllowedValuesContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "isApproval" ->
                    standardDomainRepository.findByIsApprovalAndProjectId(searchQuery.equals("Y"), accountDto.getProjectId(), pageable);
            default ->
                    standardDomainRepository.findByCommonStandardDomainNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
        };

        return searchResult.map(StandardDomainDto::fromEntity);
    }

    @Transactional
    public void approvalStandardDomains(List<StandardDomainDto> standardDomains, AccountDto accountDto, boolean isApprovalAvailable) {

        Long projectId = accountDto.getProjectId();

        standardDomains.forEach(standardDomain -> {
            StandardDomain domain = standardDomainRepository.findByIdAndProjectId(standardDomain.getId(), projectId);
            if(domain != null){
                // 승인/미승인 상태로 변경한다.
                domain.setIsApproval(isApprovalAvailable);
            }
        });
    }

    @Transactional
    public void deleteDataDictionaryStandardDomains(List<StandardDomainDto> standardDomains, AccountDto accountDto) {
        standardDomains.forEach(domainDto -> {
            StandardDomain domain = standardDomainRepository.findByIdAndProjectId(domainDto.getId(), accountDto.getProjectId());

            if (domain != null) {
                // 1. 승인된 도메인 삭제 불가
                if (Boolean.TRUE.equals(domain.getIsApproval())) {
                    throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED,
                            "승인된 도메인은 삭제할 수 없습니다: {0}", domain.getCommonStandardDomainName());
                }

                // 2. 표준용어에서 참조 중인 경우 삭제 불가
                if (domain.getStandardTerms() != null && !domain.getStandardTerms().isEmpty()) {
                    throw new CustomException(ErrorCode.RELATION_EXISTS,
                            "표준용어에서 사용 중인 도메인은 삭제할 수 없습니다: {0}", domain.getCommonStandardDomainName());
                }

                // 3. 삭제 수행
                standardDomainRepository.delete(domain);
            }
        });
    }

    public void setStandardDomain(MultipartFile file, Long projectId, boolean isApprovalAvailable) throws IOException {
        List<String> standardDomainHeaders = List.of(
                "id",
                "revisionNumber",
                "standardDomainGroupName",
                "standardDomainCategoryName",
                "standardDomainName",
                "standardDomainDescription",
                "dataType",
                "dataLength",
                "dataDecimalLength",
                "storageFormat",
                "displayFormat",
                "unit",
                "allowedValues"
        );

        // 같은 프로젝트의 도메인 등록끼리 줄을 세운다 (화면 등록과 같은 잠금, [DICT-2]). 이미 있는 이름(대소문자 무시)과 파일 안에서 앞에 나온 이름은 건너뛴다
        lockProject(projectId);
        Set<String> knownDomainNames = standardDomainRepository.findAllNamesByProjectId(projectId).stream()
                .map(StandardDomainService::nameKey)
                .collect(Collectors.toCollection(HashSet::new));

        // 청크(500행) 단위로 모아 JDBC 배치로 저장하고, 저장 후 영속성 컨텍스트를 비운다
        ExcelUtils.readExcelRowsInChunks(file, 2, standardDomainHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {
            List<StandardDomain> standardDomains = new ArrayList<>();

            for (Map<String, String> standardDomainEntry : rows) {
                StandardDomain standardDomain = StandardDomain.builder()
                        .revisionNumber(standardDomainEntry.get("revisionNumber") == null || standardDomainEntry.get("revisionNumber").isEmpty() ? 0 : Integer.parseInt(standardDomainEntry.get("revisionNumber").replaceAll("[^0-9]", "")))
                        .commonStandardDomainGroupName(standardDomainEntry.get("standardDomainGroupName"))
                        .commonStandardDomainCategory(standardDomainEntry.get("standardDomainCategoryName"))
                        .commonStandardDomainName(standardDomainEntry.get("standardDomainName"))
                        .commonStandardDomainDescription(standardDomainEntry.get("standardDomainDescription"))
                        .dataType(standardDomainEntry.get("dataType"))
                        .dataLength(standardDomainEntry.get("dataLength").equals("-") || standardDomainEntry.get("dataLength").isEmpty()  ? 0 : Integer.parseInt(standardDomainEntry.get("dataLength")))
                        .dataDecimalLength(standardDomainEntry.get("dataDecimalLength").equals("-")  || standardDomainEntry.get("dataDecimalLength").isEmpty() ? 0 : Integer.parseInt(standardDomainEntry.get("dataDecimalLength")))
                        .storageFormat(standardDomainEntry.get("storageFormat"))
                        .displayFormat(standardDomainEntry.get("displayFormat"))
                        .unit(standardDomainEntry.get("unit"))
                        .allowedValues(standardDomainEntry.get("allowedValues"))
                        .projectId(projectId)
                        .isApproval(isApprovalAvailable)
                        .build();

                String domainName = standardDomain.getCommonStandardDomainName();
                // 이미 등록된 도메인 / 파일 안에서 앞에 나온 도메인은 건너뛴다 (대소문자 무시)
                if (domainName != null && !domainName.isEmpty() && knownDomainNames.add(nameKey(domainName))) {
                    standardDomains.add(standardDomain);
                }
            }

            standardDomainRepository.saveAll(standardDomains);
            entityManager.flush();
            entityManager.clear();
        });
    }

    public List<StandardDomain> getStandardDomains(String commonStandardDomainCategory, Long projectId) {
        return standardDomainRepository.findAllByCommonStandardDomainCategoryAndProjectId(commonStandardDomainCategory, projectId);
    }

    /**
     * 이름이 대소문자를 무시하고 같은 도메인 하나 (없으면 null — 호출하는 쪽의 NOT_FOUND_DOMAIN_DATA). 등록 중복 검사와 같은 비교다 ([DICT-2] 후속).
     * 예전 데이터에 같은 이름이 여럿 있으면 409 AMBIGUOUS_STANDARD_DOMAIN — 정확히 같은 이름·승인된 것·첫 행 어느 것도 고르지 않는다
     */
    public StandardDomain findByCommonStandardDomainNameAndProjectId(String domainName, Long projectId) {
        if (domainName == null) return null;
        List<StandardDomain> found = standardDomainRepository.findAllByNameIgnoreCase(projectId, domainName);
        if (found.size() > 1) {
            throw new CustomException(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN, "같은 이름의 도메인 : {0}", domainName);
        }
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * 도메인 등록 : 같은 프로젝트에 대소문자를 무시하고 같은 이름이 있으면 400 DUPLICATE_STANDARD_DOMAIN_NAME ([DICT-2]).
     * 검사와 저장 사이에 다른 등록이 끼지 않도록 프로젝트 행을 먼저 배타 잠금으로 잡는다 (생성·가져오기와 같은 잠금 — 같은 프로젝트의 도메인 등록끼리 직렬화)
     */
    @Transactional
    public void insertStandardDomain(StandardDomain standardDomain) {
        lockProject(standardDomain.getProjectId());
        List<String> same = standardDomainRepository.findNamesIgnoreCase(standardDomain.getProjectId(), standardDomain.getCommonStandardDomainName());
        if (!same.isEmpty()) {
            throw new CustomException(ErrorCode.DUPLICATE_STANDARD_DOMAIN_NAME, "이미 등록된 도메인 : {0}", same.getFirst());
        }
        standardDomainRepository.save(standardDomain);
    }

    /** 대소문자를 무시한 도메인명 비교 키 (앞뒤 공백·유니코드 정규화는 하지 않는다 — 기존 저장 규칙 그대로) */
    public static String nameKey(String domainName) {
        return domainName.toLowerCase(Locale.ROOT);
    }

    private void lockProject(Long projectId) {
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 기존 데이터 진단 : 현재 프로젝트에서 이름이 대소문자를 무시하고 같은 도메인 묶음 (읽기 전용 — 지우거나 합치지 않는다, [DICT-2])
     */
    @Transactional(readOnly = true)
    public List<DuplicateGroup> findDuplicateGroups(Long projectId) {
        List<StandardDomain> domains = standardDomainRepository.findDuplicateNames(projectId);
        Map<Long, Long> termsByDomain = new HashMap<>();
        Map<Long, Long> columnsByDomain = new HashMap<>();
        if (!domains.isEmpty()) {
            List<Long> ids = domains.stream().map(StandardDomain::getId).toList();
            standardDomainRepository.countTermsByDomain(projectId, ids).forEach(row -> termsByDomain.put((Long) row[0], (Long) row[1]));
            columnsByDomain.putAll(usageRepository.countColumnsByDomain(projectId, ids));
        }
        Map<String, List<DuplicateMember>> groups = new LinkedHashMap<>();
        for (StandardDomain domain : domains) {
            groups.computeIfAbsent(nameKey(domain.getCommonStandardDomainName()), key -> new ArrayList<>())
                    .add(new DuplicateMember(domain.getId(), domain.getCommonStandardDomainName(), Boolean.TRUE.equals(domain.getIsApproval()),
                            termsByDomain.getOrDefault(domain.getId(), 0L), columnsByDomain.getOrDefault(domain.getId(), 0L),
                            domain.audit().created(), domain.audit().updated()));
        }
        return groups.entrySet().stream().map(e -> new DuplicateGroup(e.getKey(), e.getValue().size(), e.getValue())).toList();
    }

    public record DuplicateGroup(String normalizedName, int count, List<DuplicateMember> members) {}

    public record DuplicateMember(Long domainId, String domainName, boolean isApproval, long termCount, long columnCount,
                                  java.time.LocalDateTime registeredAt, java.time.LocalDateTime modifiedAt) {}

    @Transactional
    public void updateStandardDomain(StandardDomainDto standardDomainDto) {
        // 수정 화면·API 는 도메인명을 바꾸지 않는다 (이름 충돌이 생길 수 없다) — 이름은 등록 때만 정해진다
        StandardDomain standardDomain = standardDomainRepository.findByIdAndProjectId(standardDomainDto.getId(), standardDomainDto.getProjectId());
        if (standardDomain == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_DOMAIN_DATA);
        }

        standardDomain.setRevisionNumber(standardDomainDto.getRevisionNumber());
        standardDomain.setCommonStandardDomainDescription(standardDomainDto.getCommonStandardDomainDescription());
        standardDomain.setDataType(standardDomainDto.getDataType());
        standardDomain.setDataLength(standardDomainDto.getDataLength());
        standardDomain.setDataDecimalLength(standardDomainDto.getDataDecimalLength());
        standardDomain.setUnit(standardDomainDto.getUnit());
    }
}
