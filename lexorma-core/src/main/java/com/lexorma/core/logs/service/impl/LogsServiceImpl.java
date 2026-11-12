package com.lexorma.core.logs.service.impl;

import com.lexorma.core.logs.domain.entity.Logs;
import com.lexorma.core.logs.repository.LogsRepository;
import com.lexorma.core.logs.service.LogsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LogsServiceImpl implements LogsService {

    private final LogsRepository logsRepository;

    @Override
    @Transactional
    public void saveLogs(Logs log) {
        logsRepository.save(log);
    }
}
