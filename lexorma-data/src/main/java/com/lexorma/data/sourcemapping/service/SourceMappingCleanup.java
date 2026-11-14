package com.lexorma.data.sourcemapping.service;

import com.lexorma.core.source.SourceConnectionDeletedEvent;
import com.lexorma.data.sourcemapping.repository.ProjectSourceConnectionRepository;
import com.lexorma.data.sourcemapping.repository.SourceSchemaMappingRepository;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 원본 연결 삭제 (M-4c) : 그 연결의 매핑과 기준 연결 지정을 지운다. 연결 삭제 서비스가 카탈로그를 지우기 전에 발행하는 이벤트를
 * 같은 스레드·같은 트랜잭션에서 받는다 ({@link EventListener} 는 동기) : 여기서 실패하면 연결 삭제 전체가 롤백되고, 연결 삭제가 롤백되면 여기서 지운 것도 되돌아간다.
 * 동시에 들어온 매핑 저장은 연결 행 잠금으로 이 삭제와 차례가 정해진다 (SourceMappingService : 저장 전에 연결 행 공유 잠금, 삭제는 배타 잠금 뒤 이 정리).
 * 그래서 지운 연결을 가리키는 매핑이 남지 않는다 (재시도할 작업이 없다).
 */
@Component
@RequiredArgsConstructor
public class SourceMappingCleanup {

    private static final Logger log = LoggerFactory.getLogger(SourceMappingCleanup.class);

    private final ProjectSourceConnectionRepository baseRepository;
    private final SourceSchemaMappingRepository schemaMappingRepository;
    private final SourceTableMappingRepository tableMappingRepository;

    @EventListener
    public void onConnectionDeleted(SourceConnectionDeletedEvent event) {
        int tables = tableMappingRepository.deleteByConnection(event.projectId(), event.connectionId());
        int schemas = schemaMappingRepository.deleteByConnection(event.projectId(), event.connectionId());
        int base = baseRepository.deleteByConnection(event.projectId(), event.connectionId());
        log.info("원본 연결 삭제 : projectId={}, connectionId={}, TABLE 매핑 {}건·스키마 매핑 {}건·기준 연결 {}건 삭제", event.projectId(), event.connectionId(), tables, schemas, base);
    }
}
