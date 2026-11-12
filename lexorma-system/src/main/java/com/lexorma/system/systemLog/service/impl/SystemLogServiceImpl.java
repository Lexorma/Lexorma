package com.lexorma.system.systemLog.service.impl;

import com.lexorma.core.logs.domain.dto.LogsDto;
import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.repository.LogsRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.system.systemLog.service.SystemLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class SystemLogServiceImpl implements SystemLogService {

    private final LogsRepository logsRepository;

    @Override
    public Page<LogsDto> getSystemLogData(Pageable pageable, AccountDto accountDto, String searchQuery, String searchColumn) {

        Long projectId = accountDto.getProjectId();

        if (projectId == null) {
            throw new IllegalArgumentException("projectId 값이 null입니다!");
        }

        if (searchQuery == null || searchQuery.isBlank()) {
            return logsRepository.findAllByProjectId(projectId, pageable)
                    .map(LogsDto::fromEntity);
        }

        Page<Logs> searchResult = switch (searchColumn) {
            case "logUrl" -> logsRepository.findByProjectIdAndLogUrlContaining(projectId, searchQuery, pageable);
            case "method" -> logsRepository.findByProjectIdAndMethodContaining(projectId, searchQuery, pageable);
            case "ip" -> logsRepository.findByProjectIdAndIpContaining(projectId, searchQuery, pageable);
            case "username" -> logsRepository.findByProjectIdAndUsernameContaining(projectId, searchQuery, pageable);
            case "statusCode" -> logsRepository.findByProjectIdAndStatusCodeContaining(projectId, searchQuery, pageable);
            case "requestBody" -> logsRepository.findByProjectIdAndRequestBodyContaining(projectId, searchQuery, pageable);
            case "responseBody" -> logsRepository.findByProjectIdAndResponseBodyContaining(projectId, searchQuery, pageable);
            case "errorMessage" -> logsRepository.findByProjectIdAndErrorMessageContaining(projectId, searchQuery, pageable);
            default -> logsRepository.findAllByProjectId(projectId, pageable); // fallback
        };

        return searchResult.map(LogsDto::fromEntity);
    }
}
