package com.koboolean.metamora.system.systemLog.service;

import com.koboolean.metamora.core.logs.domain.dto.LogsDto;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


public interface SystemLogService {

    Page<LogsDto> getSystemLogData(Pageable pageable, AccountDto accountDto, String searchQuery, String searchColumn);
}
