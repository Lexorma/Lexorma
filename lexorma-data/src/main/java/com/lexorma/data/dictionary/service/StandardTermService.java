package com.lexorma.data.dictionary.service;

import com.lexorma.common.jpa.ModelBase;

import com.lexorma.data.dictionary.domain.dto.DictionaryLists;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardTermWordMapping;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.quality.StandardNames;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.logical.repository.LogicalAttributeRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.utils.ExcelUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StandardTermService {

    private final StandardTermRepository standardTermRepository;

    private final StandardDomainService standardDomainService;
    private final StandardWordService standardWordService;
    private final StandardWordRepository standardWordRepository;
    private final StandardDomainRepository standardDomainRepository;
    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;
    private final LogicalAttributeRepository logicalAttributeRepository;
    private final DictionaryRowLocks rowLocks;

    private static final int FIRST_ROW = 1;

    public Page<StandardTermDto> getStandardTermsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery) {
        // DTO에 펼쳐진 도메인 필드를 실제 엔티티 연관관계 경로로 정렬한다.
        Sort sort = Sort.by(pageable.getSort().stream().map(order -> order.withProperty(switch (order.getProperty()) {
            case "commonStandardDomainName", "allowedValues", "storageFormat", "displayFormat" ->
                    "standardDomain." + order.getProperty();
            default -> order.getProperty();
        })).toList());
        pageable = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort);

        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            // 검색어가 없을 경우 전체 조회
            Page<StandardTerm> allByProjectId = standardTermRepository.findAllByProjectId(accountDto.getProjectId(), pageable);
            return allByProjectId.map(StandardTermDto::fromEntity);
        }

        Page<StandardTerm> searchResult = switch (searchColumn) {
            case "id" -> standardTermRepository.findAllByIdAndProjectId(Long.parseLong(searchQuery.replaceAll("[^0-9]", "")), Long.parseLong(accountDto.getProjectId().toString()), pageable);
            case "revisionNumber" ->
                    standardTermRepository.findByRevisionNumberAndProjectId(Integer.parseInt(searchQuery.replaceAll("[^0-9]", "")), accountDto.getProjectId(), pageable);
            case "commonStandardTermName" ->
                    standardTermRepository.findByCommonStandardTermNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardTermDescription" ->
                    standardTermRepository.findByCommonStandardTermDescriptionContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardTermAbbreviation" ->
                    standardTermRepository.findByCommonStandardTermAbbreviationContainingIgnoreCaseAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardDomainName" ->
                    standardTermRepository.findByStandardDomain_CommonStandardDomainNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "allowedValues" ->
                    standardTermRepository.findByStandardDomain_AllowedValuesContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "storageFormat" ->
                    standardTermRepository.findByStandardDomain_StorageFormatContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "displayFormat" ->
                    standardTermRepository.findByStandardDomain_DisplayFormatContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "administrativeStandardCodeName" ->
                    standardTermRepository.findByAdministrativeStandardCodeNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "responsibleOrganization" ->
                    standardTermRepository.findByResponsibleOrganizationContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "synonyms" ->
                    standardTermRepository.findBySynonymListContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "isApproval" ->
                    standardTermRepository.findByIsApprovalAndProjectId(searchQuery.equals("Y"), accountDto.getProjectId(), pageable);
            default ->
                    standardTermRepository.findByCommonStandardTermNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
        };

        return searchResult.map(StandardTermDto::fromEntity);
    }

    @Transactional
    public void approvalStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto, boolean isApprovalAvailable) {
        Long projectId = accountDto.getProjectId();

        // 행을 잠그고 바꾼다 : 같은 용어의 수정과 겹치면 수정이 끝난 뒤 그 내용을 읽어 승인 여부만 바꾼다 (잠그지 않으면 수정 전 내용으로 덮어쓴다).
        // 여러 건은 ID 순서로 잠가 서로 기다리다 막히지 않게 한다 (M-5 7차)
        Map<Long, LocalDateTime> bases = new HashMap<>();
        standardTerms.stream().filter(term -> term.getId() != null).forEach(term -> bases.putIfAbsent(term.getId(), term.getModifiedAt()));
        bases.keySet().stream().sorted().forEach(id ->
                standardTermRepository.findForUpdate(id, projectId).ifPresent(term -> {
                    if (Boolean.TRUE.equals(term.getIsApproval()) == isApprovalAvailable) return; // 이미 그 상태 : 바꾸는 것이 없다
                    // 승인자가 본 내용 그대로일 때만 바꾼다 (M-5 8차) : 화면을 연 뒤 고쳐졌으면 409 — 하나라도 다르면 전체를 되돌린다 (한 트랜잭션)
                    verifyBase(term, bases.get(id), isApprovalAvailable
                            ? "화면을 연 뒤 표준 용어 {0} 이(가) 변경되어 승인하지 않았습니다. 최신 정보를 확인한 후 다시 승인해 주세요."
                            : "화면을 연 뒤 표준 용어 {0} 이(가) 변경되어 승인을 취소하지 않았습니다. 최신 정보를 확인한 후 다시 승인 취소해 주세요.");
                    // 승인/미승인 상태로 변경한다.
                    term.setIsApproval(isApprovalAvailable);
                }));
    }

    /**
     * 용어 삭제 (M8-C4c-1 C4c-D02) : 도메인 삭제와 같은 잠금 (프로젝트 행 배타 → 행 NOWAIT) → 다시 읽기 → 승인된 용어는 예전처럼 건너뛴다 → 기준값 409 →
     * 논리 속성 참조(기존)·물리 컬럼 참조 검사. 물리 컬럼 참조는 예전에는 검사하지 않아 DB FK 위반 500 이었다 — 같은 참조 보호 오류(RELATION_EXISTS)로 알린다
     */
    @Transactional
    public void deleteDataDictionaryStandardTerms(List<StandardTermDto> standardTerms, AccountDto accountDto) {
        rowLocks.lockForDeletion(accountDto.getProjectId(), DictionaryRowLocks.Target.TERM, standardTerms.stream().map(StandardTermDto::getId).toList());
        standardTerms.forEach(standardDomain -> {
            StandardTerm byIdAndProjectId = standardTermRepository.findByIdAndProjectId(standardDomain.getId(), accountDto.getProjectId());
            if (byIdAndProjectId != null) entityManager.refresh(byIdAndProjectId);
            if(byIdAndProjectId != null && !byIdAndProjectId.getIsApproval()){
                verifyBase(byIdAndProjectId, standardDomain.getModifiedAt(),
                        "화면을 연 뒤 다른 사용자가 표준 용어 {0} 을(를) 변경해 삭제하지 않았습니다. 최신 정보를 확인한 후 다시 삭제해 주세요.");
                // 논리 속성이 참조하는 용어는 지울 수 없다 (TB_LGCL_ATRB.STD_TRM_SN FK)
                if (logicalAttributeRepository.existsByStandardTerm_Id(standardDomain.getId())) {
                    throw new CustomException(ErrorCode.RELATION_EXISTS, "논리 속성에서 사용 중인 표준 용어입니다 : {0}", byIdAndProjectId.getCommonStandardTermName());
                }
                // 물리 컬럼이 참조하는 용어도 지울 수 없다 (TB_COL.STD_TRM_SN FK)
                if (byIdAndProjectId.getColumnInfos() != null && !byIdAndProjectId.getColumnInfos().isEmpty()) {
                    throw new CustomException(ErrorCode.RELATION_EXISTS, "물리 컬럼에서 사용 중인 표준 용어입니다 : {0}", byIdAndProjectId.getCommonStandardTermName());
                }
                standardTermRepository.deleteById(standardDomain.getId());
            }
        });
    }

    public void setStandardTerm(MultipartFile file, Long projectId, boolean isApprovalAvailable) throws IOException {
        List<String> standardTermHeaders = List.of(
                "id",
                "revisionNumber",
                "standardTermName",
                "standardTermDescription",
                "standardTermAbbreviation",
                "standardDomainName",
                "allowedValues",
                "storageFormat",
                "representationFormat",
                "administrativeStandardCodeName",
                "responsibleOrganization",
                "synonyms"
        );
        // 업로드 중에는 단어/도메인이 바뀌지 않으므로 조회 결과를 캐시한다. (영문약어 → 단어 목록, 대소문자 무시 도메인명 → 도메인 ID)
        Map<String, List<StandardWord>> wordsByAbbreviation = new HashMap<>();
        Map<String, Long> domainIdByName = new HashMap<>();

        // 프로젝트의 데이터 사전 약어 사용 여부 : 사용하면 엑셀의 영문약어명을 그대로, 아니면 단어 영문명으로 용어 영문명을 만든다
        boolean isDicAbbrUsed = projectRepository.findById(projectId)
                .map(project -> Boolean.TRUE.equals(project.getIsDicAbbrUsed()))
                .orElse(true);

        // 중복 기준 : 화면 등록·수정과 같은 키 — 영문약어(대소문자 무시) + 도메인 ID (M-5 5차). 중복 행은 예전처럼 건너뛴다 (오류 아님).
        // 도메인만 다른 행은 다른 키라 등록된다. 업로드는 도메인 시트 단계에서 잡은 프로젝트 행 배타 잠금 안에서 돈다 (화면 등록과 직렬화)
        Set<String> uploadedTermKeys = new HashSet<>();

        // 청크(500행) 단위 : 단어/도메인은 청크당 IN 조회, 저장은 JDBC 배치(용어 + 매핑 + 이음동의어), 저장 후 영속성 컨텍스트 비우기
        ExcelUtils.readExcelRowsInChunks(file, 0, standardTermHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {
            List<Map<String, String>> termRows = rows.stream()
                    .filter(row -> row.get("standardTermName") != null && !row.get("standardTermName").isEmpty())
                    .toList();

            loadUploadReferences(projectId, termRows, wordsByAbbreviation, domainIdByName);

            List<StandardTerm> built = new ArrayList<>();
            for (Map<String, String> standardTermEntry : termRows) {
                built.add(buildUploadedTerm(projectId, isApprovalAvailable, isDicAbbrUsed, standardTermEntry, wordsByAbbreviation, domainIdByName));
            }

            // 이미 등록된 용어 / 파일 안에서 앞에 나온 같은 키의 용어는 건너뛴다
            Set<String> abbreviationKeys = built.stream().map(term -> abbreviationKey(term.getCommonStandardTermAbbreviation())).collect(Collectors.toSet());
            Set<String> existingTermKeys = abbreviationKeys.isEmpty() ? Set.of()
                    : standardTermRepository.findAbbreviationDomainsByKeys(projectId, abbreviationKeys).stream()
                        .map(row -> duplicateKey((String) row[0], (Long) row[1]))
                        .collect(Collectors.toSet());
            List<StandardTerm> standardTerms = built.stream()
                    .filter(term -> !existingTermKeys.contains(termKey(term)) && uploadedTermKeys.add(termKey(term)))
                    .toList();

            standardTermRepository.saveAll(standardTerms);
            entityManager.flush();
            entityManager.clear(); // 캐시한 단어는 detach 되지만 ID 로 참조(getReference)하므로 문제없다
        });
    }

    /**
     * 청크의 용어가 쓰는 단어(영문약어)/도메인 중 아직 캐시에 없는 것을 IN 조회 한 번씩으로 불러온다.
     */
    private void loadUploadReferences(Long projectId, List<Map<String, String>> termRows,
                                      Map<String, List<StandardWord>> wordsByAbbreviation, Map<String, Long> domainIdByName) {
        Set<String> abbreviations = termRows.stream()
                .flatMap(row -> Arrays.stream(row.get("standardTermAbbreviation").split("_")))
                .filter(abbreviation -> !wordsByAbbreviation.containsKey(abbreviation))
                .collect(Collectors.toSet());

        if (!abbreviations.isEmpty()) {
            abbreviations.forEach(abbreviation -> wordsByAbbreviation.put(abbreviation, new ArrayList<>()));
            standardWordRepository.findAllByProjectIdAndCommonStandardWordAbbreviationIn(projectId, abbreviations)
                    .forEach(word -> wordsByAbbreviation.get(word.getCommonStandardWordAbbreviation()).add(word));
        }

        // 도메인 : 처음 한 번 프로젝트 도메인 전체를 대소문자 무시 키로 묶는다 (도메인 시트를 먼저 올린 뒤라 새 도메인도 들어 있다).
        // 키가 같은 도메인이 여럿이면(예전 데이터) 임의로 고르지 않고 표시만 해 두었다가 그 이름을 쓰는 행에서 거절한다 ([DICT-2] 후속)
        if (domainIdByName.isEmpty()) {
            domainIdByName.put(DOMAINS_LOADED, 0L);
            for (Object[] row : standardDomainRepository.findIdAndNamesByProjectId(projectId)) {
                domainIdByName.merge(StandardDomainService.nameKey((String) row[1]), (Long) row[0], (a, b) -> AMBIGUOUS_DOMAIN);
            }
        }
    }

    /**
     * 엑셀 한 행으로 표준용어를 만든다. (단어 매핑 / 도메인은 조회 없이 ID 참조로 연결)
     * 검증 순서와 오류는 기존과 같다 : 단어 매핑(NOT_FOUND_WORD_DATA) → 도메인(NOT_FOUND_DOMAIN_DATA)
     */
    /** 업로드 도메인 캐시에서 "같은 이름(대소문자 무시)이 여럿" 표시 (실제 ID 는 양수) */
    private static final long AMBIGUOUS_DOMAIN = -1L;
    /** 업로드 도메인 캐시를 채웠다는 표시 (도메인명 키와 겹치지 않는 값 — 키는 소문자다) */
    private static final String DOMAINS_LOADED = "\u0000LOADED";

    private static String termKey(StandardTerm term) {
        return duplicateKey(term.getCommonStandardTermAbbreviation(), term.getStandardDomain() == null ? null : term.getStandardDomain().getId());
    }

    /**
     * 표준 용어 중복 키 (M-5 5차, 등록·수정·엑셀 공통) : 영문약어 대소문자 무시 + 도메인 ID. 앞뒤 공백·'_'·유니코드 정규화는 하지 않는다.
     * DB 조회는 같은 조건을 {@code lower()} 로 쓴다 ({@link StandardTermRepository#findDuplicates})
     */
    static String duplicateKey(String abbreviation, Long domainId) {
        return abbreviationKey(abbreviation) + "|" + domainId;
    }

    private static String abbreviationKey(String abbreviation) {
        return abbreviation == null ? "" : abbreviation.toLowerCase(Locale.ROOT);
    }

    private StandardTerm buildUploadedTerm(Long projectId, boolean isApprovalAvailable, boolean isDicAbbrUsed, Map<String, String> standardTermEntry,
                                           Map<String, List<StandardWord>> wordsByAbbreviation, Map<String, Long> domainIdByName) {
        String abbreviation = standardTermEntry.get("standardTermAbbreviation");

        // 용어명이 영문약어명과 맞지 않으면 영문약어명의 표준 단어로 용어명을 다시 만들고, 설명에 그 사실을 남긴다.
        ResolvedTermName resolved = resolveUploadedTermName(standardTermEntry.get("standardTermName"), abbreviation, wordsByAbbreviation);
        String standardTermName = resolved.termName();
        String description = resolved.generated()
                ? appendGeneratedNote(standardTermEntry.get("standardTermDescription"), abbreviation, standardTermEntry.get("standardTermName"))
                : standardTermEntry.get("standardTermDescription");

        List<StandardTermWordMapping> mappings = new ArrayList<>();
        for (int i = 0; i < resolved.words().size(); i++) {
            StandardTermWordMapping mapping = new StandardTermWordMapping();
            mapping.setStandardWord(entityManager.getReference(StandardWord.class, resolved.words().get(i).getId()));
            mapping.setOrderIndex(i);
            mappings.add(mapping);
        }

        String domainName = standardTermEntry.get("standardDomainName");
        Long domainId = domainName == null ? null : domainIdByName.get(StandardDomainService.nameKey(domainName));
        if (domainId == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_DOMAIN_DATA);
        }
        if (domainId == AMBIGUOUS_DOMAIN) {
            throw new CustomException(ErrorCode.AMBIGUOUS_STANDARD_DOMAIN, "같은 이름의 도메인 : {0}", domainName);
        }

        StandardTerm standardTerm = StandardTerm.builder()
                .revisionNumber(standardTermEntry.get("revisionNumber") == null || standardTermEntry.get("revisionNumber").isEmpty() ? 0 : Integer.parseInt(standardTermEntry.get("revisionNumber").replaceAll("[^0-9]", "")))
                .commonStandardTermName(standardTermName)
                .commonStandardTermDescription(description)
                .termWordMappings(mappings)
                .commonStandardTermAbbreviation(isDicAbbrUsed ? abbreviation : toEnglishTermName(resolved.words()))
                .administrativeStandardCodeName(standardTermEntry.get("administrativeStandardCodeName"))
                .responsibleOrganization(standardTermEntry.get("responsibleOrganization"))
                .synonymList(DictionaryLists.split(standardTermEntry.get("synonyms")))
                .projectId(projectId)
                .isApproval(isApprovalAvailable)
                .standardDomain(entityManager.getReference(StandardDomain.class, domainId))
                .build();

        mappings.forEach(mapping -> mapping.setStandardTerm(standardTerm));
        return standardTerm;
    }

    /**
     * 데이터 사전 약어를 쓰지 않는 프로젝트의 용어 영문명 : 단어 영문명을 '_' 로 잇는다. (예: 사용_내역_관리 → USE_HISTORY_MANAGEMENT)
     * 영문명이 여러 단어인 단어(약어 사용 단어, 예: IP = Internet Protocol)나 영문명이 없는 단어는 약어를 쓴다
     * → 용어명과 단어 수가 같게 유지된다.
     */
    public static String toEnglishTermName(List<StandardWord> words) {
        return words.stream()
                .map(word -> {
                    String englishName = word.getCommonStandardWordEnglishName() == null ? "" : word.getCommonStandardWordEnglishName().trim();
                    boolean useAbbreviation = Boolean.TRUE.equals(word.getUseAbbreviation()) || englishName.isEmpty() || englishName.contains(" ");
                    return useAbbreviation ? word.getCommonStandardWordAbbreviation() : englishName;
                })
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining("_"));
    }

    private void getStandardTermDomain(Long projectId, String[] split, List<StandardTermWordMapping> mappings, String standardTermName) {
        String[] nameWords = standardTermName.split("_", -1);

        for (int i = 0; i < split.length; i++) {
            String s = split[i];
            List<StandardWord> words = standardWordService.findByCommonStandardWordAbbreviation(s, projectId);

            // 같은 순번의 단어명과 정확히 같은 단어를 쓴다. (사용자_이름 / USER_NM → 사용자=USER, 이름=NM)
            StandardWord word = findWord(words, nameWords[i]);

            if (word != null) {
                // 일단 매핑 객체는 나중에 StandardTerm set
                StandardTermWordMapping mapping = new StandardTermWordMapping();
                mapping.setStandardWord(word);
                mapping.setOrderIndex(i); // 순서 저장

                mappings.add(mapping);
            }else{
                throw new CustomException(ErrorCode.NOT_FOUND_WORD_DATA, "누락된 문자열 : {0}", s);
            }
        }
    }

    /**
     * 표준용어명과 영문약어명은 '_' 로 나눈 단어 수가 같아야 한다. (빈 단어 / 공백 구분 불가)
     * 예) 사용자_이름 / USER_NM  (O),  사용자이름 / USER_NM  (X),  사용자 이름 / USER_NM  (X)
     */
    public static void validateWordCount(String termName, String abbreviation) {
        String name = termName == null ? "" : termName.trim();
        String abbr = abbreviation == null ? "" : abbreviation.trim();

        String[] nameWords = name.split("_", -1);
        String[] abbrWords = abbr.split("_", -1);

        boolean hasBlank = Arrays.stream(nameWords).anyMatch(String::isBlank) || Arrays.stream(abbrWords).anyMatch(String::isBlank)
                || name.chars().anyMatch(Character::isWhitespace);

        if (name.isEmpty() || abbr.isEmpty() || hasBlank || nameWords.length != abbrWords.length) {
            throw new CustomException(ErrorCode.TERM_WORD_COUNT_MISMATCH, termName + " / " + abbreviation);
        }
    }

    /**
     * 업로드 용어명 결정 결과
     * @param termName  저장할 표준용어명 ('_' 구분)
     * @param words     영문약어 순서대로 매핑할 표준 단어
     * @param generated 영문약어명 기준으로 용어명을 새로 만들었는지 (원본 용어명이 맞지 않음)
     */
    public record ResolvedTermName(String termName, List<StandardWord> words, boolean generated) {}

    public static final String GENERATED_NOTE_PREFIX = "[영문약어명 기준 자동 생성]";

    /**
     * 엑셀 업로드 용어명 결정
     * - 용어명과 영문약어명의 단어 수가 같고 같은 순번 단어가 모두 표준 단어와 정확히 일치하면 그대로 쓴다.
     * - 아니면(단어 수 다름, 공백/구분자 없음, 단어명 불일치) 영문약어명의 각 약어에 해당하는 표준 단어로 용어명을 만든다.
     *   같은 약어의 단어가 여러 개면 원본 용어명에 들어 있는 단어(긴 것 우선) → 승인된 단어 → 첫 단어 순으로 고른다.
     * - 영문약어명이 비었거나(빈 단어 포함) 약어에 해당하는 표준 단어가 없으면 기존과 같이 업로드를 취소한다.
     */
    public static ResolvedTermName resolveUploadedTermName(String termName, String abbreviation, Map<String, List<StandardWord>> wordsByAbbreviation) {
        String abbr = abbreviation == null ? "" : abbreviation.trim();
        String[] abbrWords = abbr.split("_", -1);
        if (abbr.isEmpty() || Arrays.stream(abbrWords).anyMatch(String::isBlank)) {
            throw new CustomException(ErrorCode.TERM_WORD_COUNT_MISMATCH, termName + " / " + abbreviation);
        }

        String name = termName == null ? "" : termName.trim();
        String[] nameWords = name.split("_", -1);

        // 1) 원본 용어명이 영문약어명과 정확히 맞는 경우
        if (nameWords.length == abbrWords.length && name.chars().noneMatch(Character::isWhitespace)) {
            List<StandardWord> exact = new ArrayList<>();
            for (int i = 0; i < abbrWords.length; i++) {
                StandardWord word = findWord(wordsByAbbreviation.getOrDefault(abbrWords[i], List.of()), nameWords[i]);
                if (word == null) break;
                exact.add(word);
            }
            if (exact.size() == abbrWords.length) {
                return new ResolvedTermName(name, exact, false);
            }
        }

        // 2) 영문약어명의 표준 단어로 용어명을 만든다.
        String compactName = StandardNames.compact(name);
        List<StandardWord> words = new ArrayList<>();
        for (String part : abbrWords) {
            List<StandardWord> candidates = wordsByAbbreviation.getOrDefault(part, List.of());
            if (candidates.isEmpty()) {
                throw new CustomException(ErrorCode.NOT_FOUND_WORD_DATA, "누락된 문자열 : {0}", part);
            }
            words.add(pickWord(candidates, compactName));
        }

        String generatedName = words.stream().map(StandardWord::getCommonStandardWordName).collect(Collectors.joining("_"));
        return new ResolvedTermName(generatedName, words, !generatedName.equals(name));
    }

    private static StandardWord pickWord(List<StandardWord> candidates, String compactName) {
        return candidates.stream()
                .filter(word -> word.getCommonStandardWordName() != null && !compactName.isEmpty()
                        && compactName.contains(StandardNames.compact(word.getCommonStandardWordName())))
                .max(Comparator.comparingInt(word -> word.getCommonStandardWordName().length()))
                .or(() -> candidates.stream().filter(word -> Boolean.TRUE.equals(word.getIsApproval())).findFirst())
                .orElse(candidates.get(0));
    }

    /**
     * 자동 생성한 용어의 설명 끝에 생성 근거를 남긴다. (예: 원본 설명 + [영문약어명 기준 자동 생성] USER_NM … 원본 표준용어명: 사용자이름)
     */
    public static String appendGeneratedNote(String description, String abbreviation, String originalTermName) {
        String original = originalTermName == null || originalTermName.isBlank() ? "-" : originalTermName.trim();
        String note = GENERATED_NOTE_PREFIX + " 영문약어명(" + abbreviation.trim() + ")의 표준 단어로 표준용어명을 생성했습니다. (원본 표준용어명: " + original + ")";
        if (note.length() > DESCRIPTION_MAX_LENGTH) note = note.substring(0, DESCRIPTION_MAX_LENGTH);
        if (description == null || description.isBlank()) return note;

        // 설명 컬럼(1000자)을 넘지 않도록 원본 설명을 줄인다. (생성 근거는 항상 남긴다)
        String body = description.trim();
        int room = DESCRIPTION_MAX_LENGTH - note.length() - 1;
        if (room <= 0) return note;
        if (body.length() > room) body = body.substring(0, room);
        return body + "\n" + note;
    }

    private static final int DESCRIPTION_MAX_LENGTH = 1000; // standard_term.common_standard_term_description

    /** {@link ModelBase#verify} 와 같은 비교(1µs 미만 차이는 같음)·같은 409 MODEL_CONFLICT. 문구는 공통 문구 대신 행위별(수정·승인·승인 취소) 표준 용어 안내만 (M-7 후속) */
    private static void verifyBase(StandardTerm locked, java.time.LocalDateTime base, String message) {
        try {
            ModelBase.verify(locked, base, locked.getCommonStandardTermName());
        } catch (CustomException conflict) {
            throw CustomException.withMessage(ErrorCode.MODEL_CONFLICT, message, locked.getCommonStandardTermName());
        }
    }

    private void lockProject(Long projectId) {
        projectRepository.findByIdForUpdate(projectId).orElseThrow(() -> new CustomException(ErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 같은 영문약어의 단어 중 이름이 정확히 같은 단어 (없으면 null)
     */
    private static StandardWord findWord(List<StandardWord> candidates, String wordName) {
        return candidates.stream()
                .filter(candidate -> Objects.equals(candidate.getCommonStandardWordName(), wordName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 중복 확인 API(화면의 등록 전 확인) : 도메인명으로 찾은 도메인(대소문자 무시, [DICT-2])에 영문약어(대소문자 무시)가 같은 용어 (M-5 5차).
     * 도메인이 없으면 빈 목록, 같은 이름이 여럿이면 409 AMBIGUOUS_STANDARD_DOMAIN — 등록과 같은 판정
     */
    public List<StandardTerm> findDuplicateTerms(String abbreviation, String domainName, Long projectId) {
        StandardDomain domain = standardDomainService.findByCommonStandardDomainNameAndProjectId(domainName, projectId);
        return domain == null || abbreviation == null ? List.of() : standardTermRepository.findDuplicates(projectId, abbreviation, domain.getId());
    }

    /**
     * 등록. 검사 순서는 예전과 같다 : 단어 수(400) → 중복(400 DUPLICATE_TERM_DATA) → 단어(400) → 도메인(없음 400 / 모호 409).
     * 중복은 같은 프로젝트에서 영문약어(대소문자 무시) + 실제 도메인 ID 가 같은 용어다 (M-5 5차 — 예전에는 약어·도메인명 정확 일치라
     * 'code' 처럼 도메인명 대소문자만 다르면 같은 도메인의 중복 용어가 생겼다). 도메인 이름이 모호하면 중복을 판단하지 않고 도메인 단계에서 409 를 낸다.
     * 검사와 저장 사이에 다른 등록이 끼지 않도록 프로젝트 행을 먼저 배타 잠금으로 잡는다 (도메인 등록·엑셀 업로드와 같은 잠금)
     */
    public void saveStandardTerms(Long projectId, boolean isApprovalAvailable, StandardTermDto standardTermDto, String[] split) {

        validateWordCount(standardTermDto.getCommonStandardTermName(), standardTermDto.getCommonStandardTermAbbreviation());

        lockProject(projectId);
        List<StandardDomain> domains = standardTermDto.getCommonStandardDomainName() == null ? List.of()
                : standardDomainRepository.findAllByNameIgnoreCase(projectId, standardTermDto.getCommonStandardDomainName());
        if (domains.size() == 1
                && !standardTermRepository.findDuplicates(projectId, standardTermDto.getCommonStandardTermAbbreviation(), domains.getFirst().getId()).isEmpty()) {
            throw new CustomException(ErrorCode.DUPLICATE_TERM_DATA);
        }

        List<StandardTermWordMapping> mappings = new ArrayList<>();

        getStandardTermDomain(projectId, split, mappings,standardTermDto.getCommonStandardTermName());

        StandardTerm standardTerm = getStandardTerm(projectId, isApprovalAvailable, standardTermDto, mappings);

        for (StandardTermWordMapping mapping : mappings) {
            mapping.setStandardTerm(standardTerm);
        }

        standardTermRepository.save(standardTerm);
    }

    private StandardTerm getStandardTerm(Long projectId, boolean isApprovalAvailable, StandardTermDto dto, List<StandardTermWordMapping> mappings) {
        StandardDomain standardDomain = standardDomainService.findByCommonStandardDomainNameAndProjectId(
                dto.getCommonStandardDomainName(), projectId
        );

        if (standardDomain == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_DOMAIN_DATA,
                    "도메인을 찾을 수 없습니다: {0}", dto.getCommonStandardDomainName());
        }

        return StandardTerm.builder()
                .revisionNumber(dto.getRevisionNumber())
                .commonStandardTermName(dto.getCommonStandardTermName())
                .commonStandardTermDescription(dto.getCommonStandardTermDescription())
                .termWordMappings(mappings)
                .commonStandardTermAbbreviation(dto.getCommonStandardTermAbbreviation())
                .administrativeStandardCodeName(dto.getAdministrativeStandardCodeName())
                .responsibleOrganization(dto.getResponsibleOrganization())
                .synonymList(DictionaryLists.split(dto.getSynonyms()))
                .projectId(projectId)
                .isApproval(isApprovalAvailable)
                .standardDomain(standardDomain) // 연관관계 설정
                .build();
    }

    @Transactional
    public void updateStandardTerms(Long projectId, StandardTermDto dto, String[] split) {
        // 행을 잠그고 승인 여부를 본다 : 승인·승인 취소와 겹치면 그쪽이 끝난 뒤의 상태로 판단한다 (M-5 7차)
        StandardTerm existingTerm = standardTermRepository.findForUpdate(dto.getId(), projectId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_TERM_DATA));
        // 승인된 용어는 고칠 수 없다 — 승인 취소 → 수정 → 재승인 (화면과 같은 규칙, 테이블·컬럼·논리 모델과 같은 오류)
        if (Boolean.TRUE.equals(existingTerm.getIsApproval())) {
            throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        }
        // 화면을 연 뒤 다른 사용자가 고쳤으면 409 (M-5 8차, DEC-08 과 같은 계약 — 잠근 행의 MDFCN_DT 와 비교). HTTP 요청은 기준값이 필수다(컨트롤러)
        verifyBase(existingTerm, dto.getModifiedAt(), "화면을 연 뒤 다른 사용자가 표준 용어 {0} 을(를) 먼저 변경해 저장하지 않았습니다. 입력한 내용은 그대로 두었습니다. 최신 정보를 확인한 후 다시 수정해 주세요. 창을 닫고 다시 불러오면 입력한 내용은 사라지니 필요하면 먼저 복사해 두세요.");
        // 영문약어를 보내지 않으면(null) 저장된 값을 그대로 둔다 (단어 약어 조합으로 바꾸지 않는다)
        if (dto.getCommonStandardTermAbbreviation() == null) {
            dto.setCommonStandardTermAbbreviation(existingTerm.getCommonStandardTermAbbreviation());
        }

        if (!Objects.equals(existingTerm.getCommonStandardTermName(), dto.getCommonStandardTermName())
                || !Objects.equals(existingTerm.getCommonStandardTermAbbreviation(), dto.getCommonStandardTermAbbreviation())) {
            validateWordCount(dto.getCommonStandardTermName(), dto.getCommonStandardTermAbbreviation());
        }

        StandardDomain standardDomain = standardDomainService.findByCommonStandardDomainNameAndProjectId(
                dto.getCommonStandardDomainName(), projectId
        );

        if (standardDomain == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_DOMAIN_DATA,
                    "도메인을 찾을 수 없습니다: {0}", dto.getCommonStandardDomainName());
        }

        // 중복 키(영문약어 대소문자 무시 + 도메인 ID)를 바꾸는 수정만 다른 용어와 겹치는지 본다 (M-5 5차). 키가 그대로인 수정
        // (설명 등, 약어 대소문자만 바꾸기, 이미 중복인 예전 데이터의 수정)은 예전처럼 검사하지 않는다. 자기 자신은 빼고, 승인 여부와 무관하다
        Long currentDomainId = existingTerm.getStandardDomain() == null ? null : existingTerm.getStandardDomain().getId();
        if (!duplicateKey(existingTerm.getCommonStandardTermAbbreviation(), currentDomainId).equals(duplicateKey(dto.getCommonStandardTermAbbreviation(), standardDomain.getId()))) {
            lockProject(projectId);
            if (standardTermRepository.findDuplicates(projectId, dto.getCommonStandardTermAbbreviation(), standardDomain.getId()).stream()
                    .anyMatch(other -> !other.getId().equals(existingTerm.getId()))) {
                throw new CustomException(ErrorCode.DUPLICATE_TERM_DATA);
            }
        }

        existingTerm.setRevisionNumber(dto.getRevisionNumber());
        existingTerm.setCommonStandardTermName(dto.getCommonStandardTermName());
        existingTerm.setCommonStandardTermDescription(dto.getCommonStandardTermDescription());
        existingTerm.setCommonStandardTermAbbreviation(dto.getCommonStandardTermAbbreviation());
        existingTerm.setAdministrativeStandardCodeName(dto.getAdministrativeStandardCodeName());
        existingTerm.setResponsibleOrganization(dto.getResponsibleOrganization());
        // 이음동의어 : 화면은 항상 보낸다(빈 값 = 비우기). 필드가 없으면(null) 기존 목록을 그대로 둔다 — 지우지 않는다 ([DICT-1])
        if (dto.getSynonyms() != null) existingTerm.setSynonymList(DictionaryLists.split(dto.getSynonyms()));
        // 승인 여부는 바꾸지 않는다 : 승인·승인 취소 API 만 바꾼다 (예전에는 승인 권한자가 고치면 승인, 그 밖에는 미승인으로 다시 정했다 — M-5 7차)
        existingTerm.setStandardDomain(standardDomain); // 연관관계 주입
        // 이음동의어만 바뀌어도 기준값(MDFCN_DT)이 움직이게 한다 : 행 자신의 값이 그대로면 UPDATE 가 나가지 않는다 (M-5 8차)
        existingTerm.markModified();

        // 단어 매핑도 갱신 필요 시 추가
    }
}
