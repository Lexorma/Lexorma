package com.koboolean.metamora.design.excel.service;

import com.koboolean.metamora.design.excel.domain.dto.ExcelDto;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

public interface ExcelService {
    ResponseEntity<Resource> getExcelFile(String templateName, AccountDto accountDto) throws IOException;

    ResponseEntity<Resource> getDesignExcelFile(ExcelDto excelDto, AccountDto accountDto) throws IOException;

    ResponseEntity<Resource> getTestExcelFile(ExcelDto excelDto, AccountDto accountDto) throws IOException;
}
