package com.lexorma.source.connection.service;

import com.lexorma.core.network.ConnectionType;
import com.lexorma.core.network.ExternalConnectionTargets;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 등록된 원본 DB 연결의 목적지 (SEC-1) : 관리자 화면이 "허용 규칙이 없는 등록 연결" 을 안내한다. 사용 중인 연결만, 접속 정보는 호스트·포트·이름만 (계정·비밀번호 없음).
 */
@Service
@RequiredArgsConstructor
public class SourceConnectionTargets implements ExternalConnectionTargets {

    private final SourceConnectionRepository connections;
    private final ProjectRepository projects;

    @Override
    @Transactional(readOnly = true)
    public List<Target> targets() {
        List<SourceConnection> enabled = connections.findAll().stream().filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .sorted(Comparator.comparing(SourceConnection::getId)).toList();
        Map<Long, String> names = projects.findAllById(enabled.stream().map(SourceConnection::getProjectId).distinct().toList()).stream()
                .collect(Collectors.toMap(Project::getId, Project::getProjectName, (a, b) -> a));
        return enabled.stream().map(c -> new Target(ConnectionType.JDBC, c.getHost(), c.getPort(), names.getOrDefault(c.getProjectId(), "#" + c.getProjectId()) + " · " + c.getName()))
                .toList();
    }
}
