package com.lexorma.data.dictionary.service;

import com.lexorma.data.dictionary.domain.dto.DictionaryLists;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.utils.ExcelUtils;
import jakarta.persistence.EntityManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StandardWordService {

    private static final int FIRST_ROW = 1;
    private final StandardWordRepository standardWordRepository;
    private final StandardDomainRepository standardDomainRepository;
    private final EntityManager entityManager;

    public Page<StandardWordDto> getStandardWordsData(Pageable pageable, AccountDto accountDto, String searchColumn, String searchQuery) {
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            // 검색어가 없을 경우 전체 조회
            Page<StandardWord> allByProjectId = standardWordRepository.findAllByProjectId(accountDto.getProjectId(), pageable);
            return allByProjectId.map(StandardWordDto::fromEntity);
        }

        Page<StandardWord> searchResult = switch (searchColumn) {
            case "id" -> standardWordRepository.findAllByIdAndProjectId(Long.parseLong(searchQuery.replaceAll("[^0-9]", "")), Long.parseLong(accountDto.getProjectId().toString()), pageable);
            case "revisionNumber" ->
                    standardWordRepository.findByRevisionNumberContainingAndProjectId(Integer.parseInt(searchQuery.replaceAll("[^0-9]", "")), accountDto.getProjectId(), pageable);
            case "commonStandardWordName" ->
                    standardWordRepository.findByCommonStandardWordNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardWordAbbreviation" ->
                    standardWordRepository.findByCommonStandardWordAbbreviationContainingIgnoreCaseAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardWordEnglishName" ->
                    standardWordRepository.findByCommonStandardWordEnglishNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "commonStandardWordDescription" ->
                    standardWordRepository.findByCommonStandardWordDescriptionContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "isFormatWord" ->
                    standardWordRepository.findByIsFormatWordAndProjectId(searchQuery.equals("Y"), accountDto.getProjectId(), pageable);
            case "commonStandardDomainCategory" ->
                    standardWordRepository.findByCommonStandardDomainCategoryContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "synonyms" ->
                    standardWordRepository.findBySynonymListContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "restrictedWords" ->
                    standardWordRepository.findByRestrictedWordsContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
            case "isApproval" ->
                    standardWordRepository.findByIsApprovalAndProjectId(searchQuery.equals("Y"), accountDto.getProjectId(), pageable);
            default ->
                    standardWordRepository.findByCommonStandardWordNameContainingAndProjectId(searchQuery, accountDto.getProjectId(), pageable);
        };

        return searchResult.map(StandardWordDto::fromEntity);
    }

    @Transactional
    public void approvalStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto, boolean isApprovalAvailable) {
        Long projectId = accountDto.getProjectId();

        // 행을 잠그고(ID 순) 다시 읽은 뒤 승인 여부만 바꾼다 (M8-C1 D-02) : 잠그지 않으면 그 사이 커밋된 수정(설명·약어 사용·형식 단어 등)을 행 전체 UPDATE 로 되돌린다.
        // 승인자가 본 내용 그대로일 때만 바꾼다 (M8-D02) — 하나라도 다르면 409 로 전체를 되돌린다. 이미 그 상태면 바꾸는 것이 없다
        bases(standardWords, StandardWordDto::getId, StandardWordDto::getModifiedAt).forEach((id, base) ->
                standardWordRepository.findForUpdate(id, projectId).ifPresent(word -> {
                    if (Boolean.TRUE.equals(word.getIsApproval()) == isApprovalAvailable) return;
                    verifyBase(word, base, word.getCommonStandardWordName(), isApprovalAvailable
                            ? "화면을 연 뒤 표준 단어 {0} 이(가) 변경되어 승인하지 않았습니다. 최신 정보를 확인한 후 다시 승인해 주세요."
                            : "화면을 연 뒤 표준 단어 {0} 이(가) 변경되어 승인을 취소하지 않았습니다. 최신 정보를 확인한 후 다시 승인 취소해 주세요.");
                    word.setIsApproval(isApprovalAvailable);
                }));
    }

    @Transactional
    public void deleteDataDictionaryStandardWords(List<StandardWordDto> standardWords, AccountDto accountDto) {
        standardWords.forEach(standardDomain -> {
            StandardWord word = standardWordRepository.findByIdAndProjectId(standardDomain.getId(), accountDto.getProjectId());

            if (word != null) {
                if (word.getIsApproval()) {
                    throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED, word.getCommonStandardWordName());
                }

                if (word.getTermWordMappings() != null && !word.getTermWordMappings().isEmpty()) {
                    throw new CustomException(ErrorCode.RELATION_EXISTS, word.getCommonStandardWordName());
                }

                standardWordRepository.deleteById(word.getId());
            }
        });
    }

    public void setStandardWord(MultipartFile file, Long projectId, boolean isApprovalAvailable) throws IOException {
        List<String> standardWordHeaders = List.of(
                "id",
                "revisionNumber",
                "standardWordName",
                "standardWordAbbreviation",
                "standardWordEnglishName",
                "standardWordDescription",
                "isFormatWord",
                "standardDomainCategoryName",
                "synonyms",
                "prohibitedWords"
        );
        // 파일 안 중복 확인용 / 도메인 분류 등록 여부 캐시 (분류 수만큼만 조회)
        Set<String> uploadedWordNames = new HashSet<>();
        Map<String, Boolean> domainCategoryExists = new HashMap<>();

        // 청크(500행) 단위 : 중복 단어명은 청크당 IN 조회 한 번, 저장은 JDBC 배치, 저장 후 영속성 컨텍스트 비우기
        ExcelUtils.readExcelRowsInChunks(file, 1, standardWordHeaders, FIRST_ROW, ExcelUtils.UPLOAD_CHUNK_SIZE, rows -> {
            Set<String> chunkWordNames = rows.stream()
                    .map(row -> row.get("standardWordName"))
                    .filter(name -> name != null && !name.isEmpty())
                    .collect(Collectors.toSet());

            Set<String> existingWordNames = chunkWordNames.isEmpty() ? Set.of()
                    : standardWordRepository.findAllByProjectIdAndCommonStandardWordNameIn(projectId, chunkWordNames).stream()
                        .map(StandardWord::getCommonStandardWordName)
                        .collect(Collectors.toSet());

            List<StandardWord> standardWords = new ArrayList<>();

            for (Map<String, String> standardWordEntry : rows) {
                String wordName = standardWordEntry.get("standardWordName");
                if (wordName == null || wordName.isEmpty()) {
                    continue;
                }

                StandardWord standardWord = StandardWord.builder()
                        .revisionNumber(standardWordEntry.get("revisionNumber") == null || standardWordEntry.get("revisionNumber").isEmpty() ? 0 : Integer.parseInt(standardWordEntry.get("revisionNumber").replaceAll("[^0-9]", "")))
                        .commonStandardWordName(standardWordEntry.get("standardWordName"))
                        .commonStandardWordAbbreviation(standardWordEntry.get("standardWordAbbreviation"))
                        .commonStandardWordEnglishName(standardWordEntry.get("standardWordEnglishName"))
                        .commonStandardWordDescription(standardWordEntry.get("standardWordDescription"))
                        .isFormatWord(standardWordEntry.containsKey("isFormatWord"))
                        .commonStandardDomainCategory(standardWordEntry.get("standardDomainCategoryName"))
                        .synonymList(DictionaryLists.split(standardWordEntry.get("synonyms")))
                        .restrictedWords(DictionaryLists.split(standardWordEntry.get("prohibitedWords")))
                        .projectId(projectId)
                        .isApproval(isApprovalAvailable)
                        .useAbbreviation(standardWordEntry.get("standardWordEnglishName").split(" ").length > 1)
                        .build();

                // '_' 가 들어간 단어는 업로드 전체를 취소한다. 이미 등록된 단어 / 파일 안에서 앞에 나온 단어는 건너뛴다.
                validateNoUnderscore(wordName, standardWord.getCommonStandardWordAbbreviation());

                if (existingWordNames.contains(wordName) || !uploadedWordNames.add(wordName)) {
                    continue;
                }

                String domainCategory = standardWord.getCommonStandardDomainCategory();
                if (domainCategory != null && !domainCategory.isEmpty() && !domainCategory.equals("-")
                        && !domainCategoryExists.computeIfAbsent(domainCategory,
                            category -> standardDomainRepository.existsByProjectIdAndCommonStandardDomainCategory(projectId, category))) {
                    throw new CustomException(ErrorCode.DOMAIN_IS_NOT_DEFINED, domainCategory);
                }

                standardWords.add(standardWord);
            }

            standardWordRepository.saveAll(standardWords);
            entityManager.flush();
            entityManager.clear();
        });
    }

    public List<StandardWord> findByCommonStandardWordAbbreviation(String s, Long projectId) {
        return standardWordRepository.findAllByCommonStandardWordAbbreviationAndProjectId(s, projectId);
    }

    public StandardWord commonStandardWordName(String s, Long projectId) {
        return standardWordRepository.findByCommonStandardWordNameAndProjectId(s, projectId);
    }

    /**
     * 표준단어명/영문약어명에는 '_' 를 쓸 수 없다. '_' 는 표준용어에서 단어를 나누는 구분자다. (사용자_이름 / USER_NM)
     */
    public static void validateNoUnderscore(String wordName, String abbreviation) {
        if ((wordName != null && wordName.contains("_")) || (abbreviation != null && abbreviation.contains("_"))) {
            throw new CustomException(ErrorCode.WORD_UNDERSCORE_NOT_ALLOWED, wordName + " / " + abbreviation);
        }
    }

    @Transactional
    public void insertStandardWords(StandardWord standardWord) {
        validateNoUnderscore(standardWord.getCommonStandardWordName(), standardWord.getCommonStandardWordAbbreviation());

        List<StandardWord> nameIsDupl = standardWordRepository.findAllByProjectIdAndCommonStandardWordName(standardWord.getProjectId(), standardWord.getCommonStandardWordName());

        if(nameIsDupl.isEmpty()){
            domainIsNotDefinedCheck(standardWord);

            standardWordRepository.save(standardWord);
        }else{
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, standardWord.getCommonStandardWordName());
        }

    }

    /**
     * 도메인이 등록되어 있는경우, 도메인 정보를 조회해와 일치하는 도메인이 존재하지 않으면 Error
     * @param standardWord
     */
    private void domainIsNotDefinedCheck(StandardWord standardWord) {
        String domainCategory = standardWord.getCommonStandardDomainCategory();

        if(domainCategory != null && !domainCategory.isEmpty() && !domainCategory.equals("-")){
            List<StandardDomain> standardDomains = standardDomainRepository.findAllByProjectIdAndCommonStandardDomainCategory(standardWord.getProjectId(), domainCategory);

            if(standardDomains.isEmpty()){
                throw new CustomException(ErrorCode.DOMAIN_IS_NOT_DEFINED, domainCategory);
            }
        }
    }

    @Transactional
    public void updateStandardWords(StandardWordDto standardWordDto) {

        // 행을 잠그고 다시 읽는다 (M8-C1 D-02) : 같은 단어의 수정·승인과 겹치면 그쪽이 끝난 뒤의 상태로 판단한다. 단어의 승인 정책(승인된 단어 수정 여부)은 바꾸지 않았다
        StandardWord standardWord = standardWordDto.getId() == null ? null
                : standardWordRepository.findForUpdate(standardWordDto.getId(), standardWordDto.getProjectId()).orElse(null);
        if (standardWord == null) {
            throw new CustomException(ErrorCode.NOT_FOUND_WORD_DATA);
        }
        // 화면을 연 뒤 다른 사용자가 고쳤으면 409 (DEC-08 — 잠근 행의 MDFCN_DT 와 비교). HTTP 요청은 기준값이 필수다 (컨트롤러)
        verifyBase(standardWord, standardWordDto.getModifiedAt(), standardWord.getCommonStandardWordName(),
                "화면을 연 뒤 다른 사용자가 표준 단어 {0} 을(를) 먼저 변경해 저장하지 않았습니다. 입력한 내용은 그대로 두었습니다. 최신 정보를 확인한 후 다시 수정해 주세요. 창을 닫고 다시 불러오면 입력한 내용은 사라지니 필요하면 먼저 복사해 두세요.");
        requireYn(standardWordDto.getUseAbbreviation(), "useAbbreviation");
        requireYn(standardWordDto.getIsFormatWord(), "isFormatWord");

        standardWord.setUseAbbreviation(standardWordDto.getUseAbbreviation().equals("Y"));
        standardWord.setRevisionNumber(standardWordDto.getRevisionNumber());
        standardWord.setCommonStandardWordDescription(standardWordDto.getCommonStandardWordDescription());
        standardWord.setIsFormatWord(standardWordDto.getIsFormatWord().equals("Y"));
        // 금칙어 : 화면은 항상 보낸다(빈 값 = 비우기). 필드가 없으면(null) 기존 목록을 그대로 둔다 ([DICT-1])
        if (standardWordDto.getRestrictedWords() != null) standardWord.setRestrictedWords(DictionaryLists.split(standardWordDto.getRestrictedWords()));
        // 금칙어(별도 목록 테이블)만 바뀌어도 기준값(MDFCN_DT)이 움직이게 한다 : 행 자신의 값이 그대로면 UPDATE 가 나가지 않는다 (표준 용어 M-5 8차와 같다)
        standardWord.markModified();
    }

    /** Y/N 필수 항목 : 없으면 400 (예전에는 NullPointerException 으로 500 — [DICT-1]) */
    public static void requireYn(String value, String field) {
        if (value == null) {
            throw new CustomException(ErrorCode.INVALID_DICTIONARY_REQUEST, "필수 항목이 없습니다 : {0}", field);
        }
    }

    /** 잠근 행의 기준값 비교 (DEC-08 ModelBase) — 다르면 409, 상황 문구로 (표준 용어 verifyBase 와 같다) */
    private static void verifyBase(com.lexorma.common.jpa.BaseEntity locked, java.time.LocalDateTime base, String name, String message) {
        try {
            com.lexorma.common.jpa.ModelBase.verify(locked, base, name);
        } catch (CustomException conflict) {
            throw CustomException.withMessage(ErrorCode.MODEL_CONFLICT, message, name);
        }
    }

    /** 승인·승인 취소할 행 : ID 별 화면 기준값 (같은 ID 가 여러 번이면 처음 것) */
    private static <T> java.util.Map<Long, java.time.LocalDateTime> bases(List<T> rows, java.util.function.Function<T, Long> id, java.util.function.Function<T, java.time.LocalDateTime> base) {
        java.util.Map<Long, java.time.LocalDateTime> out = new java.util.TreeMap<>();
        if (rows != null) rows.stream().filter(r -> id.apply(r) != null).forEach(r -> out.putIfAbsent(id.apply(r), base.apply(r)));
        return out;
    }
}
