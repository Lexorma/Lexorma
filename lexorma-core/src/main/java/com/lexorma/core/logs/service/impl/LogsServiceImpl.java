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

    /** 프로젝트 영구 삭제 : 프로젝트 삭제와 같은 트랜잭션에서 그 프로젝트의 요청 로그를 지운다 */
    @org.springframework.context.event.EventListener
    public void onProjectDeleted(com.lexorma.core.project.domain.event.ProjectDeletedEvent event) {
        logsRepository.deleteAllByProjectId(event.projectId());
    }
}
