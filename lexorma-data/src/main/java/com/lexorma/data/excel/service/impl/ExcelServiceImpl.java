package com.lexorma.data.excel.service.impl;

import com.lexorma.data.excel.util.ExcelExportUtils;
import com.lexorma.data.excel.util.ExcelTemplateLabels;
import com.lexorma.data.excel.util.ExcelExportUtils.RowSource;
import com.lexorma.data.excel.util.PagedReader;

import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.excel.service.ExcelService;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.utils.ExcelUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class ExcelServiceImpl implements ExcelService {

    private final StandardWordRepository standardWordRepository;
    private final StandardTermRepository standardTermRepository;
    private final StandardDomainRepository standardDomainRepository;

    /** 페이지 단위 조회 후 영속성 컨텍스트를 비우기 위해 사용 */
    private final EntityManager entityManager;

    /** 페이지 사이 순서가 바뀌지 않도록 PK 기준으로 정렬한다 */
    private static final Sort PAGE_SORT = Sort.by("id");

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> getExcelFile(String templateName, AccountDto accountDto) throws IOException {

        Resource resource = null;

        if(templateName.equals("dictionary")){
            Long projectId = accountDto.getProjectId();

            // 승인된 데이터를 페이지 단위로 조회하며 바로 엑셀에 쓴다. (목록 전체를 메모리에 올리지 않는다)
            RowSource<StandardTermDto> terms = paged(
                    pageable -> standardTermRepository.findByIsApprovalAndProjectId(true, projectId, pageable), StandardTermDto::fromEntity);
            RowSource<StandardWordDto> words = paged(
                    pageable -> standardWordRepository.findByIsApprovalAndProjectId(true, projectId, pageable), StandardWordDto::fromEntity);
            RowSource<StandardDomainDto> domains = paged(
                    pageable -> standardDomainRepository.findByIsApprovalAndProjectId(true, projectId, pageable), StandardDomainDto::fromEntity);

            File file = ExcelExportUtils.loadAndCopyDictionaryExcelFile(templateName, terms, words, domains);
            resource = new InputStreamResource(openAndDeleteOnClose(file));
        }else if(templateName.equals("dataPortal")){
            // 공통표준용어 : 한국 공공데이터포털 자료 그대로 (영어 화면에서는 버튼을 숨긴다)
            resource = ExcelUtils.downloadExcelFile(templateName);
        }else{
            // 빈 템플릿 : 영어 요청이면 시트 이름·헤더·예시 값을 영어로 바꿔 내려보낸다
            resource = ExcelUtils.downloadExcelFile(templateName);
            if (Messages.isEnglish()) {
                try (InputStream template = resource.getInputStream()) {
                    resource = new ByteArrayResource(ExcelTemplateLabels.localizedTemplate(template));
                }
            }
        }
        String encodedFileName = java.net.URLEncoder.encode(templateName, "UTF-8").replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + ".xlsx\"")
                .body(resource);
    }

    /**
     * 페이지 단위로 조회해 DTO 로 바꿔 넘기는 소스. 한 페이지를 쓴 뒤 영속성 컨텍스트를 비운다.
     * (DTO 변환에 지연 로딩이 필요하므로 페이지를 다 쓴 다음에 비운다)
     */
    private <E, D> RowSource<D> paged(Function<Pageable, ? extends Page<E>> query, Function<E, D> toDto) {
        return action -> PagedReader.forEach(query, PAGE_SORT, entity -> action.accept(toDto.apply(entity)), entityManager::clear);
    }

    /**
     * 출력용 임시 파일을 연다. 다운로드가 끝나 스트림이 닫히면 임시 파일이 삭제된다.
     */
    private static InputStream openAndDeleteOnClose(File file) throws IOException {
        return Files.newInputStream(file.toPath(), StandardOpenOption.DELETE_ON_CLOSE);
    }
}
