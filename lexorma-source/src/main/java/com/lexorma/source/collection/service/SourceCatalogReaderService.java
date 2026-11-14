package com.lexorma.source.collection.service;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.source.catalog.service.SourceIdentifiers;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.entity.SourceSchemaVersion;
import com.lexorma.source.collection.domain.entity.SourceTableVersion;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.SourceChangeType;
import com.lexorma.source.collection.repository.SourceCollectionExecutionRepository;
import com.lexorma.source.collection.repository.SourceSchemaVersionRepository;
import com.lexorma.source.collection.repository.SourceTableVersionRepository;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.tool.SourceToolRegistry;
import com.lexorma.source.tool.SourceTools;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * core 의 읽기 전용 원본 카탈로그 계약 구현 (M-4c). 과거 상태 재구성(SourceSnapshotService)과 같은 규칙 :
 * 마지막 반영(COMPLETED) 수집의 반영 순서 R 에서 스키마·테이블마다 R 이하의 마지막 버전만 읽고 삭제 표시(REMOVED)는 뺀다. 최신 카탈로그 행을 섞지 않는다.
 * 식별자는 버전 행이 아니라 원본 객체 행(TB_SRC_SCHM·TB_SRC_TBL)의 일련번호와 생성 차수다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SourceCatalogReaderService implements SourceCatalogReader {

    private final SourceConnectionRepository connectionRepository;
    private final SourceCollectionExecutionRepository executionRepository;
    private final SourceSchemaVersionRepository schemaVersionRepository;
    private final SourceTableVersionRepository tableVersionRepository;
    private final SourceToolRegistry registry;

    @Override
    public Optional<Connection> connection(Long projectId, Long connectionId) {
        if (projectId == null || connectionId == null) return Optional.empty();
        return connectionRepository.findByIdAndProjectId(connectionId, projectId).map(this::toContract);
    }

    @Override
    public List<Connection> connections(Long projectId) {
        return connectionRepository.findAllByProjectIdOrderByNameAscIdAsc(projectId).stream().map(this::toContract).toList();
    }

    @Override
    public Optional<Snapshot> latest(Long projectId, Long connectionId) {
        Optional<SourceConnection> found = projectId == null || connectionId == null ? Optional.empty() : connectionRepository.findByIdAndProjectId(connectionId, projectId);
        if (found.isEmpty()) return Optional.empty();
        Connection connection = toContract(found.get());
        SourceCollectionExecution reflected = executionRepository.findFirstByConnectionIdAndStatusAndReflectedAtLessThanEqualOrderByReflectedOrderDesc(connectionId,
                CollectionStatus.COMPLETED, LocalDateTime.now().plusYears(100)).orElse(null);
        if (reflected == null) return Optional.of(new Snapshot(connection, null, null, null, List.of(), List.of()));
        int at = reflected.getReflectedOrder();

        List<Schema> schemas = new ArrayList<>();
        for (SourceSchemaVersion version : schemaVersionRepository.at(connectionId, at)) {
            if (version.getChangeType() == SourceChangeType.REMOVED) continue;
            Map<?, ?> content = (Map<?, ?>) SourceCatalogCanonical.read(version.getContent());
            schemas.add(new Schema(version.getSchemaId(), version.getPathKey(), (String) content.get("catalog"), (String) content.get("schema"), schemaOf(version.getPathKey()),
                    version.getCreationCycle()));
        }
        schemas.sort(Comparator.comparing(Schema::catalogName, Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                .thenComparing(Schema::schemaName, Comparator.nullsFirst(Comparator.<String>naturalOrder())));
        Set<Long> schemaIds = schemas.stream().map(Schema::id).collect(Collectors.toSet());
        List<Table> tables = new ArrayList<>();
        for (SourceTableVersion version : tableVersionRepository.at(connectionId, at)) {
            if (version.getChangeType() == SourceChangeType.REMOVED || !schemaIds.contains(version.getSchemaId())) continue;
            Map<?, ?> content = (Map<?, ?>) SourceCatalogCanonical.read(version.getContent());
            tables.add(new Table(version.getTableId(), version.getSchemaId(), version.getPathKey(), (String) content.get("name"), version.getCompareName(),
                    (String) content.get("type"), (String) content.get("comment"), version.getCreationCycle()));
        }
        return Optional.of(new Snapshot(connection, at, reflected.getId(), reflected.getReflectedAt(), List.copyOf(schemas), List.copyOf(tables)));
    }

    @Override
    public String compareName(Connection connection, NameKind kind, String unquotedName) {
        if (unquotedName == null) return null;
        return SourceIdentifiers.compareName(DbType.valueOf(connection.dbType()), IdentifierComparison.valueOf(connection.identifierComparison()),
                kind == NameKind.SCHEMA ? Kind.NAMESPACE : Kind.TABLE, unquotedName, false);
    }

    private Connection toContract(SourceConnection connection) {
        // 기본 스키마 : PostgreSQL 의 public. 다른 DBMS 는 계정·데이터베이스마다 달라 정하지 않는다 (DBMS 확대 단계)
        String defaultSchema = connection.getDbType() == DbType.POSTGRESQL
                ? SourceIdentifiers.compareName(connection.getDbType(), connection.getIdentifierComparison(), Kind.NAMESPACE, "public", false) : null;
        return new Connection(connection.getId(), connection.getName(), connection.getDbType().name(), connection.getIdentifierComparison().name(),
                Boolean.TRUE.equals(connection.getEnabled()), registry.find(SourceTools.TABLE_DISCOVERY, connection.getDbType()).isPresent(), defaultSchema);
    }

    /** 비교 경로({@code 길이:카탈로그/길이:스키마})의 스키마 부분 */
    static String schemaOf(String pathKey) {
        int slash = pathKey.indexOf('/', pathKey.indexOf(':') + 1 + Integer.parseInt(pathKey.substring(0, pathKey.indexOf(':'))));
        String rest = pathKey.substring(slash + 1);
        int colon = rest.indexOf(':');
        int length = Integer.parseInt(rest.substring(0, colon));
        return rest.substring(colon + 1, colon + 1 + length);
    }
}
