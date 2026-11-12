package com.lexorma.source.collection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextHolder;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.execution.ExecutionType;
import com.lexorma.core.execution.VerifiedExecution;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.source.catalog.domain.entity.SourceColumn;
import com.lexorma.source.catalog.domain.entity.SourceConstraint;
import com.lexorma.source.catalog.domain.entity.SourceConstraintColumn;
import com.lexorma.source.catalog.domain.entity.SourceIndex;
import com.lexorma.source.catalog.domain.entity.SourceIndexColumn;
import com.lexorma.source.catalog.domain.entity.SourceRelation;
import com.lexorma.source.catalog.domain.entity.SourceRelationColumn;
import com.lexorma.source.catalog.domain.entity.SourceSchema;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.domain.enums.SourceConstraintType;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.service.SourceIdentifiers;
import com.lexorma.source.catalog.service.SourceIdentifiers.Kind;
import com.lexorma.source.collection.domain.entity.SourceChangeHistory;
import com.lexorma.source.collection.domain.entity.SourceCollectionExecution;
import com.lexorma.source.collection.domain.entity.SourceCollectionTarget;
import com.lexorma.source.collection.domain.entity.SourceSchemaVersion;
import com.lexorma.source.collection.domain.entity.SourceTableVersion;
import com.lexorma.source.collection.domain.enums.ChangeTargetType;
import com.lexorma.source.collection.domain.enums.CollectionStatus;
import com.lexorma.source.collection.domain.enums.CollectionType;
import com.lexorma.source.collection.domain.enums.SourceChangeType;
import com.lexorma.source.collection.domain.enums.TargetResult;
import com.lexorma.source.collection.repository.SourceCollectionExecutionRepository;
import com.lexorma.source.collection.repository.SourceCollectionTargetRepository;
import com.lexorma.source.collection.repository.SourceSchemaVersionRepository;
import com.lexorma.source.collection.repository.SourceTableVersionRepository;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.jpa.HibernateHints;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 수집 결과를 원본 카탈로그에 반영한다 (AI-1c, 반영 트랜잭션). 현재 카탈로그·버전·변경 이력·실행 완료가 <b>한 트랜잭션</b>에서 확정된다 : 도중에 실패하면 모두 되돌아간다.
 * <ol>
 *   <li>잠금 : 프로젝트(공유) → 연결(배타). 프로젝트 영구 삭제·연결 삭제와 같은 순서라 서로를 기다리지 않는다. 그 뒤 실행 컨텍스트·연결 상태·실행 상태를 다시 확인한다</li>
 *   <li><b>원본 변경 감지 = 마지막 확정 버전 ↔ 수집 결과</b> : 버전·변경 이력은 이것으로만 만든다. 현재 카탈로그는 수집 밖의 경로(AI-1b 의 SourceCatalogWriter)로
 *       바뀌었을 수 있어 기준으로 쓰지 않는다 (변경 전 값도 마지막 버전의 것이다)</li>
 *   <li><b>현재 상태 복구 = 현재 카탈로그 ↔ 수집 결과</b> : 범위 안의 스키마·테이블을 한 번에 읽고(읽기 전용) 테이블 해시로 고른 뒤, 해시가 다르거나 믿을 수 없는 테이블만
 *       컬럼·하위 객체를 읽어 바뀐 칸만 고친다 (버전·이력을 만들지 않는다). 변경 없는 수집은 현재 카탈로그에 UPDATE 가 없다.
 *       사라진 스키마·테이블·컬럼은 EXST_YN = false, 하위 객체는 바뀐 종류만 통째로 바꾼다</li>
 *   <li>버전 : 처음 수집한 이름 공간은 BASE, 그 뒤로는 마지막 버전과 다를 때만 생김·바뀜·사라짐(삭제 표시 : 원본의 마지막 상태 = 마지막 버전 내용). 변경 이력은 BASE 가 아닌 것만.
 *       마지막 버전이 삭제 표시인데 다시 보이면 새 생명주기(생성 차수 + 1)다 (Writer 가 행을 되살려 둔 경우도 같다)</li>
 *   <li>참조 연결 : 참조되는 테이블이 이번에 들어왔으면 이전에 이어지지 못한 관계를 잇는다 (현재 카탈로그만 고친다 : 버전에 남은 당시의 관측은 그대로)</li>
 * </ol>
 * JPA 로 저장한다 : 읽은 행은 읽기 전용(스냅숏 없음)으로 두고 고칠 행만 바꿀 수 있게 돌려 flush 비용을 줄이며, 새 행은 배치 INSERT 로 쓴다 (hibernate.jdbc.batch_size).
 * projectId 를 가진 엔티티라 ProjectWriteGuard 의 커밋 직전 확인도 받는다.
 */
@Component
@RequiredArgsConstructor
class SourceCatalogLoader {

    private static final int CHUNK = 1000;
    private static final String SEPARATOR = "\u0000";

    private final ProjectWriteGuard projectWriteGuard;
    private final ExecutionContextVerifier verifier;
    private final SourceConnectionRepository connectionRepository;
    private final SourceCollectionExecutionRepository executionRepository;
    private final SourceCollectionTargetRepository targetRepository;
    private final SourceSchemaVersionRepository schemaVersionRepository;
    private final SourceTableVersionRepository tableVersionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** @param alreadyApplied 같은 작업이 이미 반영돼 있어 아무것도 하지 않았다 (재시도) */
    record Outcome(SourceCollectionExecution execution, boolean alreadyApplied) {
    }

    /** 쓰기 트랜잭션 안에서 부른다 */
    Outcome apply(ExecutionContext context, Long executionId, PreparedCollection prepared, LocalDateTime now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("쓰기 트랜잭션 안에서 불러야 합니다.");
        Long projectId = context.projectId();
        projectWriteGuard.lock(projectId);
        SourceConnection connection = connectionRepository.findForUpdate(prepared.connectionId(), projectId)
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND));
        VerifiedExecution verified = verify(verifier, context);
        if (!Boolean.TRUE.equals(connection.getEnabled())) throw new CustomException(ErrorCode.SOURCE_TOOL_NOT_AVAILABLE, "사용 중지된 연결입니다.");
        if (connection.getDbType() != prepared.dbType() || connection.getIdentifierComparison() != prepared.comparison()) {
            throw new CustomException(ErrorCode.INVALID_SOURCE_CATALOG, "수집을 시작한 뒤 연결의 DB 유형이나 식별자 비교 규칙이 바뀌었습니다.");
        }
        SourceCollectionExecution execution = executionRepository.findById(executionId)
                .filter(found -> found.getProjectId().equals(projectId) && found.getConnectionId().equals(connection.getId()))
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_FOUND));
        if (execution.getStatus() == CollectionStatus.COMPLETED) return new Outcome(execution, true);
        if (execution.getStatus() != CollectionStatus.RUNNING) throw new CustomException(ErrorCode.SOURCE_COLLECTION_NOT_RUNNING);
        if (!prepared.complete()) throw SourceCollectionPreparer.incomplete(prepared.incompleteReason());

        try (ExecutionContextHolder.Scope ignored = ExecutionContextHolder.open(verified)) {
            new Run(projectId, connection, execution, prepared, now).execute();
        }
        return new Outcome(execution, false);
    }

    /** 사람의 요청(사용자·비동기)은 지금도 그 프로젝트의 관리자이거나 ADMIN 이어야 한다. 정기 배치는 프로젝트 상태만 본다 */
    static VerifiedExecution verify(ExecutionContextVerifier verifier, ExecutionContext context) {
        return context.type() == ExecutionType.SCHEDULED ? verifier.verify(context) : verifier.verifyProjectManager(context);
    }

    /** 반영 한 번의 상태 */
    private final class Run {

        private final Long projectId;
        private final SourceConnection connection;
        private final SourceCollectionExecution execution;
        private final PreparedCollection prepared;
        private final LocalDateTime now;
        private final DbType dbType;
        private final int order;
        private final Session session;

        private final Map<String, SourceSchema> schemas = new HashMap<>();
        /** 비교 경로 + 비교 명 → 테이블 (읽은 엔티티·새 엔티티) 또는 ID (범위 밖) : 관계의 참조 대상을 찾는다 */
        private final Map<String, Object> tableIndex = new HashMap<>();
        /** 컬럼을 읽은 테이블 → 비교 명 → 컬럼 */
        private final Map<Long, Map<String, SourceColumn>> columnsByTable = new HashMap<>();
        private final List<Work> works = new ArrayList<>();
        private int versions;
        private int changes;

        Run(Long projectId, SourceConnection connection, SourceCollectionExecution execution, PreparedCollection prepared, LocalDateTime now) {
            this.projectId = projectId;
            this.connection = connection;
            this.execution = execution;
            this.prepared = prepared;
            this.now = now;
            this.dbType = connection.getDbType();
            Integer max = executionRepository.maxReflectedOrder(connection.getId());
            this.order = max == null ? 1 : max + 1;
            this.session = entityManager.unwrap(Session.class);
        }

        void execute() {
            boolean priorFull = executionRepository.existsByConnectionIdAndCollectionTypeAndStatus(connection.getId(), CollectionType.FULL, CollectionStatus.COMPLETED);
            Set<String> observed = new HashSet<>(targetRepository.observedPaths(connection.getId()));
            // 스키마마다 마지막 버전 (스키마는 많지 않고 내용도 작다)
            Map<Long, SourceSchemaVersion> latestSchemas = new HashMap<>();
            for (SourceSchemaVersion version : schemaVersionRepository.at(connection.getId(), Integer.MAX_VALUE)) latestSchemas.put(version.getSchemaId(), version);
            for (SourceSchema schema : entityManager.createQuery("select s from SourceSchema s where s.connection.id = :c", SourceSchema.class)
                    .setParameter("c", connection.getId()).getResultList()) {
                schemas.put(schema.getPathKey(), schema);
            }

            // 1) 스키마 : 범위 안의 것만. FULL 은 결과에 없는 스키마를 사라진 것으로 본다
            Map<String, SchemaWork> schemaWorks = new LinkedHashMap<>();
            for (PreparedCollection.Schema schema : prepared.schemas()) {
                boolean baseline = !priorFull && !observed.contains(schema.pathKey());
                schemaWorks.put(schema.pathKey(), schema(schema, baseline, latestSchemas));
            }
            List<SourceSchema> removedSchemas = new ArrayList<>();
            if (execution.getCollectionType() == CollectionType.FULL) {
                for (SourceSchema schema : schemas.values()) {
                    if (Boolean.TRUE.equals(schema.getPresent()) && !schemaWorks.containsKey(schema.getPathKey())) removedSchemas.add(schema);
                }
            }

            // 2) 테이블 : 범위 안의 것을 한 번에 (읽기 전용), 테이블 해시로 고른다
            List<Long> scopeSchemaIds = new ArrayList<>();
            schemaWorks.values().stream().filter(work -> !work.created).forEach(work -> scopeSchemaIds.add(work.schema.getId()));
            removedSchemas.forEach(schema -> scopeSchemaIds.add(schema.getId()));
            Map<Long, Map<String, SourceTable>> tables = new HashMap<>();
            for (SourceTable table : readOnly("select t from SourceTable t where t.connectionId = :c and t.schema.id in :ids", SourceTable.class, scopeSchemaIds)) {
                tables.computeIfAbsent(table.getSchema().getId(), id -> new HashMap<>()).put(table.getCompareName(), table);
            }
            Map<Long, Latest> latest = new HashMap<>();
            if (!scopeSchemaIds.isEmpty()) {
                for (Object[] row : tableVersionRepository.latest(connection.getId(), scopeSchemaIds)) {
                    latest.put((Long) row[0], new Latest((Long) row[1], (String) row[2], (SourceChangeType) row[3], (Integer) row[4]));
                }
            }
            indexTables(tables);

            for (SchemaWork schemaWork : schemaWorks.values()) {
                Map<String, SourceTable> existing = new HashMap<>(tables.getOrDefault(schemaWork.schema.getId(), Map.of()));
                for (PreparedCollection.Table table : schemaWork.prepared.tables()) {
                    SourceTable row = existing.remove(table.compareName());
                    WorkKind kind = row == null ? WorkKind.NEW : !Boolean.TRUE.equals(row.getPresent()) ? WorkKind.REVIVED
                            : SourceCatalogCanonical.current(row.getContentHash()) && row.getContentHash().equals(table.hash()) ? WorkKind.UNCHANGED : WorkKind.SUSPECT;
                    works.add(new Work(schemaWork, table, row, kind, row == null ? null : latest.get(row.getId())));
                }
                for (SourceTable row : existing.values()) {
                    if (Boolean.TRUE.equals(row.getPresent())) works.add(new Work(schemaWork, null, row, WorkKind.REMOVED, latest.get(row.getId())));
                }
            }
            for (SourceSchema schema : removedSchemas) {
                SchemaWork schemaWork = new SchemaWork(schema, null, false, false);
                for (SourceTable row : tables.getOrDefault(schema.getId(), Map.of()).values()) {
                    if (Boolean.TRUE.equals(row.getPresent())) works.add(new Work(schemaWork, null, row, WorkKind.REMOVED, latest.get(row.getId())));
                }
            }
            for (Work work : works) work.source = sourceChange(work);
            loadLatestContents();

            // 3) 바뀌었을 수 있는 테이블만 하위 객체까지 읽어 지금 상태를 만든다
            loadDetails();
            for (Work work : works) {
                if (work.kind == WorkKind.SUSPECT) work.kind = work.current.equals(work.prepared.canonical()) ? WorkKind.HASH_ONLY : WorkKind.MODIFIED;
            }

            // 4) 반영 : 하위 객체 삭제 → 테이블·컬럼 → 하위 객체 추가 → 버전·이력
            for (Work work : works) removeChildren(work);
            entityManager.flush();
            for (Work work : works) writeTable(work);
            for (Work work : works) addChildren(work);
            for (Work work : works) record(work);
            for (SourceSchema schema : removedSchemas) removeSchema(schema, latestSchemas.get(schema.getId()));
            entityManager.flush();
            relink();
            finish();
        }

        // ------------------------------------------------------------------ 스키마

        /**
         * 스키마 : 원본 변경은 마지막 스키마 버전과 비교하고(버전·이력), 현재 행은 수집 결과로 맞춘다.
         * 마지막 버전이 삭제 표시면 다시 생긴 것이다 (현재 행이 사라짐 표시든, Writer 가 되살려 둔 것이든 생성 차수 + 1)
         */
        private SchemaWork schema(PreparedCollection.Schema prepared, boolean baseline, Map<Long, SourceSchemaVersion> latestSchemas) {
            SourceCatalogCanonical.Schema canonical = prepared.canonical();
            SourceSchema schema = schemas.get(prepared.pathKey());
            SourceSchemaVersion latest = schema == null ? null : latestSchemas.get(schema.getId());
            boolean created = schema == null;
            boolean reappeared = !created && (!Boolean.TRUE.equals(schema.getPresent()) || latest != null && latest.getChangeType() == SourceChangeType.REMOVED);
            if (created) {
                schema = SourceSchema.builder().projectId(projectId).connection(connection).catalogName(canonical.catalogName()).schemaName(canonical.schemaName())
                        .pathKey(canonical.pathKey()).present(true).creationCycle(1).build();
                entityManager.persist(schema);
                schemas.put(schema.getPathKey(), schema);
            } else {
                if (reappeared) schema.setCreationCycle(schema.getCreationCycle() + 1);
                setIfChanged(schema.getPresent(), Boolean.TRUE, schema::setPresent);
                setIfChanged(schema.getCatalogName(), canonical.catalogName(), schema::setCatalogName);
                setIfChanged(schema.getSchemaName(), canonical.schemaName(), schema::setSchemaName);
            }
            SourceChangeType type;
            String before = null;
            List<String> fields = List.of();
            if (baseline) type = SourceChangeType.BASE;
            else if (latest == null || latest.getChangeType() == SourceChangeType.REMOVED) type = SourceChangeType.ADDED;
            else if (latest.getFormatVersion() == SourceCatalogCanonical.FORMAT && latest.getContentHash().equals(prepared.hash())) type = null;
            else {
                type = SourceChangeType.MODIFIED;
                before = latest.getContent();
                fields = SourceCatalogCanonical.changedFields(map(before), map(prepared.json()));
            }
            if (type != null) {
                schemaVersion(schema, type, prepared.json(), prepared.hash());
                if (type != SourceChangeType.BASE) {
                    history(ChangeTargetType.SCHEMA, schema.getId(), null, schema.getPathKey(), display(canonical.catalogName(), canonical.schemaName()), schema.getCreationCycle(),
                            type, fields, before, prepared.json());
                }
            }
            return new SchemaWork(schema, prepared, baseline, created);
        }

        /** FULL 에서 사라진 스키마 : 마지막 버전이 원본에 있던 것일 때만 삭제 표시(마지막 버전 내용)·이력. Writer 가 되살려 둔 행은 표시만 지운다 */
        private void removeSchema(SourceSchema schema, SourceSchemaVersion latest) {
            schema.setPresent(false);
            if (latest == null || latest.getChangeType() == SourceChangeType.REMOVED) return;
            schemaVersion(schema, SourceChangeType.REMOVED, latest.getContent(), latest.getContentHash());
            history(ChangeTargetType.SCHEMA, schema.getId(), null, schema.getPathKey(), display(schema.getCatalogName(), schema.getSchemaName()), schema.getCreationCycle(),
                    SourceChangeType.REMOVED, List.of(), latest.getContent(), null);
        }

        /**
         * 테이블의 원본 변경 : 마지막 확정 버전과 수집 결과로만 판정한다 (현재 카탈로그는 보지 않는다).
         * 처음 수집한 이름 공간 → BASE, 버전이 없거나 마지막이 삭제 표시 → ADDED, 해시가 같다 → 없음, 다르다 → MODIFIED, 수집 결과에 없다 → 마지막이 원본에 있던 것이면 REMOVED
         */
        private SourceChangeType sourceChange(Work work) {
            Latest latest = work.latest;
            if (work.prepared == null) return latest != null && latest.type() != SourceChangeType.REMOVED ? SourceChangeType.REMOVED : null;
            if (work.schema.baseline) return SourceChangeType.BASE;
            if (latest == null || latest.type() == SourceChangeType.REMOVED) return SourceChangeType.ADDED;
            if (latest.format() == SourceCatalogCanonical.FORMAT && latest.hash().equals(work.prepared.hash())) return null;
            return SourceChangeType.MODIFIED;
        }

        /** 바뀌거나 사라진 테이블만 마지막 버전의 내용을 읽는다 (변경 전 값과 삭제 표시의 내용) */
        private void loadLatestContents() {
            Map<Long, Work> byVersion = new HashMap<>();
            for (Work work : works) {
                if (work.source == SourceChangeType.MODIFIED || work.source == SourceChangeType.REMOVED) byVersion.put(work.latest.versionId(), work);
            }
            List<Long> ids = new ArrayList<>(byVersion.keySet());
            for (int from = 0; from < ids.size(); from += CHUNK) {
                for (Object[] row : tableVersionRepository.contents(ids.subList(from, Math.min(ids.size(), from + CHUNK)))) byVersion.get((Long) row[0]).latestContent = (String) row[1];
            }
        }

        // ------------------------------------------------------------------ 지금 상태 읽기

        private void indexTables(Map<Long, Map<String, SourceTable>> loaded) {
            for (Object[] row : entityManager.createQuery("select t.id, s.pathKey, t.compareName from SourceTable t join t.schema s where t.connectionId = :c", Object[].class)
                    .setParameter("c", connection.getId()).getResultList()) {
                tableIndex.put(row[1] + SEPARATOR + row[2], row[0]);
            }
            for (Map<String, SourceTable> bySchema : loaded.values()) {
                for (SourceTable table : bySchema.values()) tableIndex.put(table.getSchema().getPathKey() + SEPARATOR + table.getCompareName(), table);
            }
        }

        private void loadDetails() {
            Map<Long, Work> byTable = new HashMap<>();
            for (Work work : works) {
                if (work.kind == WorkKind.REVIVED || work.kind == WorkKind.SUSPECT || work.kind == WorkKind.REMOVED) byTable.put(work.row.getId(), work);
            }
            if (byTable.isEmpty()) return;
            List<Long> ids = new ArrayList<>(byTable.keySet());
            for (SourceColumn column : readOnly("select c from SourceColumn c where c.sourceTable.id in :ids", SourceColumn.class, ids)) {
                columnsByTable.computeIfAbsent(column.getSourceTable().getId(), id -> new HashMap<>()).put(column.getCompareName(), column);
            }
            for (SourceConstraint constraint : readOnly("select distinct x from SourceConstraint x left join fetch x.columns where x.sourceTable.id in :ids", SourceConstraint.class, ids)) {
                byTable.get(constraint.getSourceTable().getId()).constraints.add(constraint);
            }
            for (SourceIndex index : readOnly("select distinct x from SourceIndex x left join fetch x.columns where x.sourceTable.id in :ids", SourceIndex.class, ids)) {
                byTable.get(index.getSourceTable().getId()).indexes.add(index);
            }
            for (SourceRelation relation : readOnly("select distinct x from SourceRelation x left join fetch x.columns where x.sourceTable.id in :ids", SourceRelation.class, ids)) {
                byTable.get(relation.getSourceTable().getId()).relations.add(relation);
            }
            for (Work work : byTable.values()) work.current = canonical(work);
        }

        /** 현재 행으로 만든 정규화 표현 (원본에 있는 컬럼만) : 수집 결과로 만든 것과 같은 규칙 */
        private SourceCatalogCanonical.Table canonical(Work work) {
            SourceTable row = work.row;
            List<SourceCatalogCanonical.Column> columns = new ArrayList<>();
            for (SourceColumn column : columnsByTable.getOrDefault(row.getId(), Map.of()).values()) {
                if (Boolean.TRUE.equals(column.getPresent())) columns.add(canonical(column));
            }
            List<SourceCatalogCanonical.Constraint> constraints = new ArrayList<>();
            for (SourceConstraint constraint : work.constraints) {
                List<String> members = constraint.getColumns().stream().sorted(java.util.Comparator.comparing(SourceConstraintColumn::getColumnOrder))
                        .map(member -> member.getSourceColumn().getCompareName()).toList();
                String check = constraint.getType() == SourceConstraintType.CHECK ? constraint.getCheckExpression() : null;
                constraints.add(new SourceCatalogCanonical.Constraint(SourceCatalogCanonical.constraintKey(dbType, constraint.getType().name(), constraint.getName(), members, check),
                        constraint.getName(), constraint.getType().name(), check, members));
            }
            List<SourceCatalogCanonical.Index> indexes = new ArrayList<>();
            for (SourceIndex index : work.indexes) {
                List<String> members = index.getColumns().stream().sorted(java.util.Comparator.comparing(SourceIndexColumn::getColumnOrder))
                        .map(member -> member.getSourceColumn().getCompareName()).toList();
                boolean unique = Boolean.TRUE.equals(index.getUniqueIndex());
                indexes.add(new SourceCatalogCanonical.Index(SourceCatalogCanonical.indexKey(dbType, index.getName(), unique, members), index.getName(), index.getIndexType(),
                        unique, members));
            }
            List<SourceCatalogCanonical.Relation> relations = new ArrayList<>();
            for (SourceRelation relation : work.relations) {
                List<SourceCatalogCanonical.ColumnPair> pairs = relation.getColumns().stream().sorted(java.util.Comparator.comparing(SourceRelationColumn::getColumnOrder))
                        .map(pair -> new SourceCatalogCanonical.ColumnPair(pair.getSourceColumn().getCompareName(), pair.getReferencedColumnName())).toList();
                relations.add(new SourceCatalogCanonical.Relation(SourceCatalogCanonical.relationKey(dbType, relation.getName(), relation.getReferencedCatalogName(),
                        relation.getReferencedSchemaName(), relation.getReferencedTableName(), pairs), relation.getName(), relation.getReferencedCatalogName(),
                        relation.getReferencedSchemaName(), relation.getReferencedTableName(), relation.getDeleteRule(), relation.getUpdateRule(), pairs));
            }
            return SourceCatalogCanonical.Table.of(row.getSchema().getPathKey(), row.getName(), row.getCompareName(), row.getQuoted(), row.getType().name(), row.getComment(),
                    columns, SourceCatalogCanonical.unique(constraints), SourceCatalogCanonical.unique(indexes), SourceCatalogCanonical.unique(relations));
        }

        private SourceCatalogCanonical.Column canonical(SourceColumn column) {
            return new SourceCatalogCanonical.Column(column.getName(), column.getCompareName(), column.getQuoted(), column.getOrdinal(), column.getDataType(),
                    column.getMaxLength(), SourceCatalogCanonical.precision(column.getPrecision()), column.getScale(), column.getNullable(), column.getDefaultValue(),
                    column.getComment());
        }

        // ------------------------------------------------------------------ 반영

        /** 하위 객체는 종류별로 통째로 바꾼다 : 바뀐 종류만 지운다. 사라진 테이블은 모두 지운다 (지난 내용은 버전에 남는다) */
        private void removeChildren(Work work) {
            switch (work.kind) {
                case REMOVED, REVIVED -> {
                    work.constraints.forEach(entityManager::remove);
                    work.indexes.forEach(entityManager::remove);
                    work.relations.forEach(entityManager::remove);
                    work.replaceConstraints = work.replaceIndexes = work.replaceRelations = work.kind == WorkKind.REVIVED;
                }
                case MODIFIED -> {
                    work.replaceConstraints = !work.current.constraints().equals(work.prepared.canonical().constraints());
                    work.replaceIndexes = !work.current.indexes().equals(work.prepared.canonical().indexes());
                    work.replaceRelations = !work.current.relations().equals(work.prepared.canonical().relations());
                    if (work.replaceConstraints) work.constraints.forEach(entityManager::remove);
                    if (work.replaceIndexes) work.indexes.forEach(entityManager::remove);
                    if (work.replaceRelations) work.relations.forEach(entityManager::remove);
                }
                case NEW -> work.replaceConstraints = work.replaceIndexes = work.replaceRelations = true;
                default -> {
                }
            }
        }

        private void writeTable(Work work) {
            SourceCatalogCanonical.Table spec = work.prepared == null ? null : work.prepared.canonical();
            switch (work.kind) {
                case NEW -> {
                    work.row = SourceTable.builder().projectId(projectId).connectionId(connection.getId()).schema(work.schema.schema).name(spec.name())
                            .compareName(spec.compareName()).quoted(spec.quoted()).type(SourceTableType.valueOf(spec.type())).comment(spec.comment())
                            .contentHash(work.prepared.hash()).present(true).creationCycle(1).build();
                    entityManager.persist(work.row);
                    tableIndex.put(work.schema.schema.getPathKey() + SEPARATOR + spec.compareName(), work.row);
                    writeColumns(work, Map.of());
                }
                case REVIVED, MODIFIED, HASH_ONLY -> {
                    writable(work.row);
                    // 다시 생긴 테이블 : 현재 행이 사라짐 표시였거나, 마지막 버전이 삭제 표시인데 Writer 가 행을 되살려 둔 경우
                    if (work.kind == WorkKind.REVIVED || work.latest != null && work.latest.type() == SourceChangeType.REMOVED) {
                        work.row.setCreationCycle(work.row.getCreationCycle() + 1);
                    }
                    setIfChanged(work.row.getPresent(), Boolean.TRUE, work.row::setPresent);
                    setIfChanged(work.row.getName(), spec.name(), work.row::setName);
                    setIfChanged(work.row.getQuoted(), spec.quoted(), work.row::setQuoted);
                    setIfChanged(work.row.getType(), SourceTableType.valueOf(spec.type()), work.row::setType);
                    setIfChanged(work.row.getComment(), spec.comment(), work.row::setComment);
                    work.row.setContentHash(work.prepared.hash());
                    writeColumns(work, columnsByTable.getOrDefault(work.row.getId(), Map.of()));
                }
                case REMOVED -> {
                    writable(work.row);
                    work.row.setPresent(false);
                    for (SourceColumn column : columnsByTable.getOrDefault(work.row.getId(), Map.of()).values()) {
                        if (Boolean.TRUE.equals(column.getPresent())) {
                            writable(column);
                            column.setPresent(false);
                        }
                    }
                }
                default -> {
                }
            }
        }

        /** 컬럼 : 바뀐 것만 고치고, 없던 것은 만들고(사라졌던 같은 이름은 되살리고), 사라진 것은 EXST_YN = false */
        private void writeColumns(Work work, Map<String, SourceColumn> existing) {
            Map<String, SourceColumn> columns = new HashMap<>(existing);
            Set<String> seen = new HashSet<>();
            for (SourceCatalogCanonical.Column spec : work.prepared.canonical().columns()) {
                seen.add(spec.compareName());
                String hash = work.prepared.columnHashes().get(spec.compareName());
                SourceColumn column = columns.get(spec.compareName());
                if (column == null) {
                    column = SourceColumn.builder().projectId(projectId).sourceTable(work.row).compareName(spec.compareName()).build();
                    apply(column, spec, hash);
                    entityManager.persist(column);
                    columns.put(spec.compareName(), column);
                } else if (!Boolean.TRUE.equals(column.getPresent()) || !canonical(column).equals(spec) || !Objects.equals(column.getContentHash(), hash)) {
                    writable(column);
                    apply(column, spec, hash);
                }
            }
            for (Map.Entry<String, SourceColumn> entry : columns.entrySet()) {
                if (!seen.contains(entry.getKey()) && Boolean.TRUE.equals(entry.getValue().getPresent())) {
                    writable(entry.getValue());
                    entry.getValue().setPresent(false);
                }
            }
            columnsByTable.put(work.row.getId(), columns);
        }

        private void apply(SourceColumn column, SourceCatalogCanonical.Column spec, String hash) {
            setIfChanged(column.getName(), spec.name(), column::setName);
            setIfChanged(column.getQuoted(), spec.quoted(), column::setQuoted);
            setIfChanged(column.getOrdinal(), spec.ordinal(), column::setOrdinal);
            setIfChanged(column.getDataType(), spec.dataType(), column::setDataType);
            setIfChanged(column.getMaxLength(), spec.maxLength(), column::setMaxLength);
            if (!Objects.equals(SourceCatalogCanonical.precision(column.getPrecision()), spec.precision())) {
                column.setPrecision(spec.precision() == null ? null : new BigDecimal(spec.precision()));
            }
            setIfChanged(column.getScale(), spec.scale(), column::setScale);
            setIfChanged(column.getNullable(), spec.nullable(), column::setNullable);
            setIfChanged(column.getDefaultValue(), spec.defaultValue(), column::setDefaultValue);
            setIfChanged(column.getComment(), spec.comment(), column::setComment);
            column.setContentHash(hash);
            column.setPresent(true);
        }

        private void addChildren(Work work) {
            if (work.prepared == null || !(work.replaceConstraints || work.replaceIndexes || work.replaceRelations)) return;
            SourceCatalogCanonical.Table spec = work.prepared.canonical();
            Map<String, SourceColumn> columns = columnsByTable.get(work.row.getId());
            if (work.replaceConstraints) {
                for (SourceCatalogCanonical.Constraint item : spec.constraints()) {
                    SourceConstraint constraint = SourceConstraint.builder().projectId(projectId).sourceTable(work.row).name(item.name())
                            .type(SourceConstraintType.valueOf(item.type())).checkExpression(item.checkExpression()).build();
                    for (int i = 0; i < item.columns().size(); i++) {
                        constraint.getColumns().add(SourceConstraintColumn.builder().sourceConstraint(constraint).sourceColumn(columns.get(item.columns().get(i))).columnOrder(i + 1).build());
                    }
                    entityManager.persist(constraint);
                }
            }
            if (work.replaceIndexes) {
                for (SourceCatalogCanonical.Index item : spec.indexes()) {
                    SourceIndex index = SourceIndex.builder().projectId(projectId).sourceTable(work.row).name(item.name()).indexType(item.indexType()).uniqueIndex(item.unique()).build();
                    for (int i = 0; i < item.columns().size(); i++) {
                        index.getColumns().add(SourceIndexColumn.builder().sourceIndex(index).sourceColumn(columns.get(item.columns().get(i))).columnOrder(i + 1).build());
                    }
                    entityManager.persist(index);
                }
            }
            if (work.replaceRelations) {
                for (SourceCatalogCanonical.Relation item : spec.relations()) {
                    PreparedCollection.RelationTarget target = work.prepared.relationTargets().get(item.key());
                    SourceTable referenced = table(target.pathKey(), target.tableCompareName());
                    SourceRelation relation = SourceRelation.builder().projectId(projectId).connectionId(connection.getId()).sourceTable(work.row).referencedTable(referenced)
                            .referencedCatalogName(item.referencedCatalog()).referencedSchemaName(item.referencedSchema()).referencedTableName(item.referencedTable())
                            .name(item.name()).deleteRule(item.deleteRule()).updateRule(item.updateRule()).build();
                    for (int i = 0; i < item.columns().size(); i++) {
                        SourceCatalogCanonical.ColumnPair pair = item.columns().get(i);
                        relation.getColumns().add(SourceRelationColumn.builder().relation(relation).sourceColumn(columns.get(pair.column()))
                                .referencedColumn(referenced == null ? null : column(referenced, target.referencedColumns().get(i)))
                                .referencedColumnName(pair.referencedColumn()).columnOrder(i + 1).build());
                    }
                    entityManager.persist(relation);
                }
            }
        }

        // ------------------------------------------------------------------ 버전 · 변경 이력

        /** 버전·이력은 원본 변경(work.source)으로만 만든다 : 현재 카탈로그를 복구했는지(work.kind)와 상관없다 */
        private void record(Work work) {
            if (work.source == null) return;
            switch (work.source) {
                case BASE -> tableVersion(work, SourceChangeType.BASE, work.prepared.json(), work.prepared.hash());
                case ADDED -> {
                    tableVersion(work, SourceChangeType.ADDED, work.prepared.json(), work.prepared.hash());
                    history(ChangeTargetType.TABLE, work, tableKey(work), work.row.getName(), SourceChangeType.ADDED, List.of(), null, work.prepared.json());
                }
                case MODIFIED -> modified(work);
                case REMOVED -> {
                    // 삭제 표시 = 원본의 마지막 상태 (마지막 버전 내용 그대로)
                    tableVersion(work, SourceChangeType.REMOVED, work.latestContent, work.latest.hash());
                    history(ChangeTargetType.TABLE, work, tableKey(work), work.row.getName(), SourceChangeType.REMOVED, List.of(), work.latestContent, null);
                }
            }
        }

        /** 바뀐 테이블 : 버전 1행 + 마지막 버전 대비 바뀐 객체마다 이력 */
        @SuppressWarnings("unchecked")
        private void modified(Work work) {
            Map<String, Object> before = map(work.latestContent);
            Map<String, Object> after = map(work.prepared.json());
            tableVersion(work, SourceChangeType.MODIFIED, work.prepared.json(), work.prepared.hash());
            Map<String, Object> ownBefore = own(before);
            Map<String, Object> ownAfter = own(after);
            List<String> fields = SourceCatalogCanonical.changedFields(ownBefore, ownAfter);
            if (!fields.isEmpty()) {
                history(ChangeTargetType.TABLE, work, tableKey(work), (String) ownAfter.get("name"), SourceChangeType.MODIFIED, fields, SourceCatalogCanonical.json(ownBefore),
                        SourceCatalogCanonical.json(ownAfter));
            }
            diff(work, ChangeTargetType.COLUMN, items(before, "columns", "compareName"), items(after, "columns", "compareName"));
            diff(work, ChangeTargetType.CONSTRAINT, items(before, "constraints", "key"), items(after, "constraints", "key"));
            diff(work, ChangeTargetType.INDEX, items(before, "indexes", "key"), items(after, "indexes", "key"));
            diff(work, ChangeTargetType.RELATION, items(before, "relations", "key"), items(after, "relations", "key"));
        }

        private void diff(Work work, ChangeTargetType type, Map<String, Map<String, Object>> before, Map<String, Map<String, Object>> after) {
            diff(work, type, before, after, item -> item, item -> (String) item.get("name"));
        }

        private <T> void diff(Work work, ChangeTargetType type, Map<String, T> before, Map<String, T> after, Function<T, Map<String, Object>> json, Function<T, String> name) {
            for (Map.Entry<String, T> entry : after.entrySet()) {
                T old = before.get(entry.getKey());
                String key = tableKey(work) + "#" + type + ":" + entry.getKey();
                if (old == null) {
                    history(type, work, key, name.apply(entry.getValue()), SourceChangeType.ADDED, List.of(), null, SourceCatalogCanonical.json(json.apply(entry.getValue())));
                } else {
                    List<String> fields = SourceCatalogCanonical.changedFields(json.apply(old), json.apply(entry.getValue()));
                    if (!fields.isEmpty()) {
                        history(type, work, key, name.apply(entry.getValue()), SourceChangeType.MODIFIED, fields, SourceCatalogCanonical.json(json.apply(old)),
                                SourceCatalogCanonical.json(json.apply(entry.getValue())));
                    }
                }
            }
            for (Map.Entry<String, T> entry : before.entrySet()) {
                if (after.containsKey(entry.getKey())) continue;
                history(type, work, tableKey(work) + "#" + type + ":" + entry.getKey(), name.apply(entry.getValue()), SourceChangeType.REMOVED, List.of(),
                        SourceCatalogCanonical.json(json.apply(entry.getValue())), null);
            }
        }

        private void tableVersion(Work work, SourceChangeType type, String json, String hash) {
            entityManager.persist(SourceTableVersion.builder().projectId(projectId).connectionId(connection.getId()).schemaId(work.schema.schema.getId())
                    .tableId(work.row.getId()).executionId(execution.getId()).reflectedOrder(order).pathKey(work.schema.schema.getPathKey())
                    .compareName(work.row.getCompareName()).creationCycle(work.row.getCreationCycle()).changeType(type).contentHash(hash)
                    .formatVersion(SourceCatalogCanonical.FORMAT).content(json).build());
            versions++;
        }

        private void schemaVersion(SourceSchema schema, SourceChangeType type, String json, String hash) {
            entityManager.persist(SourceSchemaVersion.builder().projectId(projectId).connectionId(connection.getId()).schemaId(schema.getId()).executionId(execution.getId())
                    .reflectedOrder(order).pathKey(schema.getPathKey()).creationCycle(schema.getCreationCycle()).changeType(type).contentHash(hash)
                    .formatVersion(SourceCatalogCanonical.FORMAT).content(json).build());
            versions++;
        }

        private void history(ChangeTargetType type, Work work, String key, String name, SourceChangeType change, List<String> fields, String before, String after) {
            history(type, work.schema.schema.getId(), work.row.getId(), key, name, work.row.getCreationCycle(), change, fields, before, after);
        }

        private void history(ChangeTargetType type, Long schemaId, Long tableId, String key, String name, Integer cycle, SourceChangeType change, List<String> fields,
                             String before, String after) {
            entityManager.persist(SourceChangeHistory.builder().projectId(projectId).connectionId(connection.getId()).executionId(execution.getId()).reflectedOrder(order)
                    .schemaId(schemaId).tableId(tableId).targetType(type).targetKey(limitKey(key)).targetName(name).creationCycle(cycle).changeType(change)
                    .changedFields(fields.isEmpty() ? null : SourceCatalogCanonical.json(fields)).before(before).after(after).build());
            changes++;
        }

        private String tableKey(Work work) {
            return work.schema.schema.getPathKey() + "/" + work.row.getCompareName();
        }

        // ------------------------------------------------------------------ 참조 연결 · 마무리

        /**
         * 참조되는 테이블·컬럼이 카탈로그에 없어 이어지지 못한 관계를 다시 잇는다 (참조 대상이 나중에·다른 부분 수집으로 들어온 경우).
         * 저장된 이름(원본이 준 그대로)을 따옴표 여부를 모르는 이름으로 비교한다 : 카탈로그가 돌려준 이름은 이미 접힌 이름이다.
         * 현재 카탈로그의 내부 참조만 바꾼다 — 버전 JSON(원본에서 확인한 관계)은 건드리지 않는다.
         */
        private void relink() {
            List<SourceRelation> unresolved = entityManager.createQuery("select distinct r from SourceRelation r left join fetch r.columns rc where r.connectionId = :c"
                    + " and (r.referencedTable is null or exists (select x.id from SourceRelationColumn x where x.relation = r and x.referencedColumn is null))", SourceRelation.class)
                    .setParameter("c", connection.getId()).getResultList();
            for (SourceRelation relation : unresolved) {
                writable(relation);
                SourceTable referenced = relation.getReferencedTable();
                if (referenced == null) {
                    String path = SourceIdentifiers.pathKey(compare(Kind.NAMESPACE, relation.getReferencedCatalogName()), compare(Kind.NAMESPACE, relation.getReferencedSchemaName()));
                    referenced = table(path, compare(Kind.TABLE, relation.getReferencedTableName()));
                    if (referenced == null) continue;
                    relation.setReferencedTable(referenced);
                }
                for (SourceRelationColumn pair : relation.getColumns()) {
                    if (pair.getReferencedColumn() != null) continue;
                    writable(pair);
                    pair.setReferencedColumn(column(referenced, compare(Kind.COLUMN, pair.getReferencedColumnName())));
                }
            }
        }

        private void finish() {
            execution.setStatus(CollectionStatus.COMPLETED);
            execution.setReflectedOrder(order);
            execution.setReflectedAt(now);
            execution.setEndedAt(now);
            execution.setLastIdentifiedAt(now);
            execution.setProgressConnectionId(null);
            execution.setTableCount(prepared.tableCount());
            execution.setColumnCount(prepared.columnCount());
            execution.setVersionCount(versions);
            execution.setChangeCount(changes);
            execution.setErrorCode(null);
            execution.setErrorMessage(null);
            Map<String, SourceCollectionTarget> targets = new HashMap<>();
            for (SourceCollectionTarget target : targetRepository.findAllByExecutionIdOrderByPathKeyAsc(execution.getId())) targets.put(target.getPathKey(), target);
            for (PreparedCollection.Target result : prepared.targets()) {
                SourceCollectionTarget target = targets.get(result.pathKey());
                boolean created = target == null;
                if (created) target = SourceCollectionTarget.builder().projectId(projectId).executionId(execution.getId()).pathKey(result.pathKey()).build();
                target.setCatalogName(result.catalogName());
                target.setSchemaName(result.schemaName());
                target.setSchemaId(schemas.get(result.pathKey()).getId());
                target.setResult(TargetResult.COMPLETED);
                target.setTableCount(result.tableCount());
                target.setColumnCount(result.columnCount());
                target.setErrorCode(null);
                // INSERT 는 persist 때의 값으로 나간다 : 값을 다 채운 뒤에 persist 한다
                if (created) entityManager.persist(target);
            }
        }

        // ------------------------------------------------------------------ 도움

        private SourceTable table(String pathKey, String compareName) {
            Object found = tableIndex.get(pathKey + SEPARATOR + compareName);
            if (found instanceof SourceTable table) return table;
            if (found instanceof Long id) return entityManager.getReference(SourceTable.class, id);
            return null;
        }

        /** 참조되는 컬럼 : 이번에 읽거나 만든 테이블은 그 컬럼에서, 아니면 그 테이블의 컬럼을 한 번 읽어 둔다 */
        private SourceColumn column(SourceTable table, String compareName) {
            Map<String, SourceColumn> columns = columnsByTable.get(table.getId());
            if (columns == null) {
                columns = new HashMap<>();
                for (SourceColumn column : readOnly("select c from SourceColumn c where c.sourceTable.id in :ids", SourceColumn.class, List.of(table.getId()))) {
                    columns.put(column.getCompareName(), column);
                }
                columnsByTable.put(table.getId(), columns);
            }
            return columns.get(compareName);
        }

        private String compare(Kind kind, String name) {
            return name == null ? null : SourceIdentifiers.compareName(dbType, connection.getIdentifierComparison(), kind, name, null);
        }

        /** 읽기 전용으로 읽는다 (영속성 컨텍스트가 스냅숏을 두지 않는다 : 수만 행을 읽어도 flush 가 그 행들을 비교하지 않는다). ID 목록은 나눠 묻는다 */
        private <T> List<T> readOnly(String jpql, Class<T> type, Collection<Long> ids) {
            List<T> result = new ArrayList<>();
            if (ids.isEmpty()) return result;
            List<Long> all = new ArrayList<>(ids);
            for (int from = 0; from < all.size(); from += CHUNK) {
                TypedQuery<T> query = entityManager.createQuery(jpql, type).setParameter("ids", all.subList(from, Math.min(all.size(), from + CHUNK)))
                        .setHint(HibernateHints.HINT_READ_ONLY, true);
                if (jpql.contains(":c")) query.setParameter("c", connection.getId());
                result.addAll(query.getResultList());
            }
            return result;
        }

        /** 읽기 전용으로 읽은 행을 고치기 전에 부른다 (그때의 상태를 스냅숏으로 잡아 바뀐 칸만 UPDATE 한다) */
        private void writable(Object entity) {
            if (session.contains(entity) && session.isReadOnly(entity)) session.setReadOnly(entity, false);
        }
    }

    private static <T> void setIfChanged(T current, T value, java.util.function.Consumer<T> setter) {
        if (!Objects.equals(current, value)) setter.accept(value);
    }

    /** 대상 식별자 칸(2000자)에 맞춘다 : 넘으면 뒤쪽을 해시로 바꾼다 (같은 객체는 같은 값) */
    private static String limitKey(String key) {
        if (key.length() <= 2000) return key;
        return key.substring(0, 1900) + "~" + SourceCatalogCanonical.hash(key);
    }

    private static String display(String catalog, String schema) {
        return catalog == null ? schema : schema == null ? catalog : catalog + "." + schema;
    }

    private enum WorkKind {
        /** 새 테이블 */
        NEW,
        /** 사라졌다가 같은 이름으로 다시 생겼다 (같은 행을 되살리고 생성 차수를 올린다) */
        REVIVED,
        /** 해시가 다르거나 믿을 수 없다 (규칙이 바뀌었거나 한 건 단위 경로가 고쳤다) : 하위 객체까지 비교한다 */
        SUSPECT,
        /** 해시가 같다 : 읽지도 고치지도 않는다 */
        UNCHANGED,
        /** 비교해 보니 같다 : 해시만 지금 규칙으로 고친다 (버전·이력 없음) */
        HASH_ONLY,
        MODIFIED,
        REMOVED
    }

    /** 테이블의 마지막 확정 버전 요약 */
    private record Latest(Long versionId, String hash, SourceChangeType type, Integer format) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String json) {
        return (Map<String, Object>) SourceCatalogCanonical.read(json);
    }

    /** 테이블 자체의 항목 (하위 객체 제외) */
    private static Map<String, Object> own(Map<String, Object> table) {
        Map<String, Object> own = new LinkedHashMap<>();
        for (String field : List.of("name", "compareName", "quoted", "type", "comment")) own.put(field, table.get(field));
        return own;
    }

    /** 하위 객체 목록을 비교 키로 */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> items(Map<String, Object> table, String list, String key) {
        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        for (Map<String, Object> item : (List<Map<String, Object>>) table.getOrDefault(list, List.of())) items.put((String) item.get(key), item);
        return items;
    }

    private record SchemaWork(SourceSchema schema, PreparedCollection.Schema prepared, boolean baseline, boolean created) {
    }

    private static final class Work {
        private final SchemaWork schema;
        private final PreparedCollection.Table prepared;
        /** 마지막 확정 버전의 요약 (없으면 null) */
        private final Latest latest;
        /** 원본 변경 (null = 없음) : 버전·이력을 정한다 */
        private SourceChangeType source;
        /** 마지막 버전의 내용 : 바뀌었거나 사라진 테이블만 읽는다 */
        private String latestContent;
        private SourceTable row;
        private WorkKind kind;
        private SourceCatalogCanonical.Table current;
        private final List<SourceConstraint> constraints = new ArrayList<>();
        private final List<SourceIndex> indexes = new ArrayList<>();
        private final List<SourceRelation> relations = new ArrayList<>();
        private boolean replaceConstraints;
        private boolean replaceIndexes;
        private boolean replaceRelations;

        Work(SchemaWork schema, PreparedCollection.Table prepared, SourceTable row, WorkKind kind, Latest latest) {
            this.schema = schema;
            this.prepared = prepared;
            this.row = row;
            this.kind = kind;
            this.latest = latest;
        }
    }
}
