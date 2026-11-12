package com.lexorma.data.excel.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

public interface ExcelService {
    ResponseEntity<Resource> getExcelFile(String templateName, AccountDto accountDto) throws IOException;
}
