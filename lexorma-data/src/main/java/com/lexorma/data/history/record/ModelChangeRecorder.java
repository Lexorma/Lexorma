package com.lexorma.data.history.record;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.Action;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.Source;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifier;
import com.lexorma.data.logical.domain.entity.LogicalIdentifierAttribute;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.mapping.domain.entity.AttributeColumnMapping;
import com.lexorma.data.mapping.domain.entity.EntityTableMapping;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 물리 모델 변경 이력 기록 (M-9 1차).
 * <ul>
 *   <li>Hibernate 의 저장·수정·삭제 이벤트에서 변경 전후 값을 받는다. 화면·ERD·일괄 편집·엑셀·참조 관계 자동 동기화 어느 경로든
 *       엔티티가 실제로 쓰일 때만 불리므로, 저장 경로마다 기록을 부르지 않아도 빠지거나 겹치지 않는다</li>
 *   <li>쓰기 트랜잭션 하나가 변경 그룹 하나다. 모은 변경은 커밋 직전에 같은 트랜잭션으로 저장한다 :
 *       모델 변경이 롤백되면 이력도 없고, 이력 저장이 실패하면 모델 변경도 롤백된다</li>
 *   <li>객체마다 트랜잭션 시작 전 상태와 끝 상태를 비교해 달라진 것만 남긴다 (같은 값으로 다시 저장하면 이력이 없다)</li>
 * </ul>
 * JPQL·네이티브 일괄 UPDATE/DELETE 는 엔티티 이벤트가 없어 기록되지 않는다 (물리 모델에는 그런 경로가 없다. 만들면 여기도 손봐야 한다).
 */
@Component
public class ModelChangeRecorder implements TransactionExecutionListener, PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(ModelChangeRecorder.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH_SIZE = 500;
    private static final String APPROVAL = "isApproval";

    /**
     * 이력에 남기는 필드 : 여기 적은 것만 (엔티티를 통째로 직렬화하지 않는다).
     * 연관 객체는 ID 를 "<필드>Id" 로, 이름을 알 수 있는 대상이면 그 시점의 이름을 "<필드>Name" 으로 함께 남긴다.
     * projectVia : 프로젝트 ID 가 없는 엔티티가 프로젝트를 물려받는 부모 연관 (없으면 자기 projectId)
     */
    private record Tracked(TargetType type, String nameField, String projectVia, List<String> fields) {}

    /** 부모 객체의 일부로 기록하는 행 (관계의 컬럼 쌍, 인덱스·제약조건의 컬럼, 식별자의 구성 속성) : 부모 이력의 listKey 배열에 순서대로 들어간다 */
    private record Child(Class<?> parent, String parentProperty, String listKey, String orderField, List<String> fields) {}

    /** 이름 스냅샷 자리 : 커밋 직전에 한꺼번에 조회해 이름으로 바꾼다 (이벤트 안에서 프록시를 초기화하지 않는다) */
    private record Ref(Class<?> type, Object id) {}

    private static final Map<Class<?>, Tracked> TRACKED = Map.ofEntries(
            Map.entry(TableInfo.class, new Tracked(TargetType.PHYSICAL_TABLE, "tableName", null,
                    List.of("tableName", "tableKorName", "tableDescription", "isMasterTable", "sortOrder", APPROVAL, "schema"))),
            // PK·고유값·인덱스 여부는 컬럼 속성이므로 컬럼 이력에만 남는다
            Map.entry(ColumnInfo.class, new Tracked(TargetType.PHYSICAL_COLUMN, "columnName", null,
                    List.of("tableInfo", "tableName", "columnName", "columnDesc", "dataType", "maxLength", "precision", "scale", "isNullable", "defaultValue",
                            "sortOrder", "isMasterData", "refTableName", "isRequired", "isSensitive", "isPk", "isUnique", "isIndex", "isEncrypted", "example",
                            APPROVAL, "standardTerms"))),
            Map.entry(TableRelation.class, new Tracked(TargetType.PHYSICAL_RELATION, "relationName", null,
                    List.of("sourceTable", "targetTable", "relationName", "relationType", "relationSource", "derivedColumnId", APPROVAL))),
            Map.entry(TableIndex.class, new Tracked(TargetType.PHYSICAL_INDEX, "indexName", null,
                    List.of("tableInfo", "indexName", "indexType", "isUnique", "condition", "indexSource", "derivedColumnId", APPROVAL))),
            Map.entry(TableConstraint.class, new Tracked(TargetType.PHYSICAL_CONSTRAINT, "constraintName", null,
                    List.of("tableInfo", "constraintName", "constraintType", "checkExpression", APPROVAL))),
            // 논리 모델 (M-9 2차)
            Map.entry(LogicalModel.class, new Tracked(TargetType.LOGICAL_MODEL, "modelName", null,
                    List.of("modelName", "modelDescription"))),
            Map.entry(LogicalEntity.class, new Tracked(TargetType.LOGICAL_ENTITY, "entityName", null,
                    List.of("model", "entityName", "entityDescription", APPROVAL))),
            Map.entry(LogicalAttribute.class, new Tracked(TargetType.LOGICAL_ATTRIBUTE, "attributeName", "entity",
                    List.of("entity", "attributeName", "attributeDescription", "dataType", "isRequired", "sortOrder", "standardTerm"))),
            Map.entry(LogicalIdentifier.class, new Tracked(TargetType.LOGICAL_IDENTIFIER, "identifierName", "entity",
                    List.of("entity", "identifierName", "isPrimary"))),
            Map.entry(LogicalRelationship.class, new Tracked(TargetType.LOGICAL_RELATIONSHIP, "relationshipName", null,
                    List.of("model", "sourceEntity", "targetEntity", "relationshipName", "relationshipDescription", "isIdentifying",
                            "sourceMin", "sourceMax", "targetMin", "targetMax", APPROVAL))),
            // 논리 ↔ 물리 매핑 (M-9 2차) : 대상을 바꾸면 UPDATE, 해제하면 DELETE
            Map.entry(EntityTableMapping.class, new Tracked(TargetType.TABLE_MAPPING, null, null,
                    List.of("entity", "table"))),
            Map.entry(AttributeColumnMapping.class, new Tracked(TargetType.COLUMN_MAPPING, null, "mapping",
                    List.of("mapping", "attribute", "column"))));

    private static final Map<Class<?>, Child> CHILDREN = Map.of(
            TableRelationColumn.class, new Child(TableRelation.class, "relation", "columns", "columnOrder", List.of("sourceColumn", "targetColumn", "columnOrder")),
            TableIndexColumn.class, new Child(TableIndex.class, "tableIndex", "columns", "columnOrder", List.of("column", "expression", "sort", "columnOrder")),
            TableConstraintColumn.class, new Child(TableConstraint.class, "tableConstraint", "columns", "columnOrder", List.of("column", "columnOrder")),
            LogicalIdentifierAttribute.class, new Child(LogicalIdentifier.class, "identifier", "attributes", "sortOrder", List.of("attribute", "sortOrder")));

    /** 이름 스냅샷을 남기는 연관 대상 → 이름 속성. 여기 없는 대상(매핑 등)은 ID 만 남는다 */
    private static final Map<Class<?>, String> NAME_FIELDS = Map.of(
            TableInfo.class, "tableName", ColumnInfo.class, "columnName", SchemaInfo.class, "schemaName", StandardTerm.class, "commonStandardTermName",
            LogicalModel.class, "modelName", LogicalEntity.class, "entityName", LogicalAttribute.class, "attributeName");

    @PersistenceContext
    private EntityManager entityManager;

    private final EntityManagerFactory entityManagerFactory;

    public ModelChangeRecorder(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getEventListenerRegistry();
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    /** 트랜잭션을 시작할 때 등록해 둔다 : 커밋 때 처음 일어나는 flush 의 변경도 놓치지 않는다 */
    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null || transaction.isReadOnly() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new Pending());
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        collect(event.getId(), event.getPersister(), null, event.getState(), true);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        collect(event.getId(), event.getPersister(), event.getOldState(), event.getState(), false);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        collect(event.getId(), event.getPersister(), event.getDeletedState(), null, false);
    }

    /** 이 트랜잭션의 변경으로 모아 둔다 (트랜잭션 밖·읽기 전용이면 아무것도 하지 않는다). 저장은 커밋 직전에 한다 */
    private void collect(Object id, EntityPersister persister, Object[] oldState, Object[] newState, boolean inserted) {
        Class<?> entityClass = persister.getMappedClass();
        Tracked tracked = TRACKED.get(entityClass);
        Child child = tracked == null ? CHILDREN.get(entityClass) : null;
        if (tracked == null && child == null) return;
        Pending pending = current();
        if (pending == null) return;
        Object[] state = newState != null ? newState : oldState;

        if (child != null) {
            // 부모가 이 트랜잭션 전에 갖고 있던 행의 처음 값만 기억한다 (새로 생긴 행은 null)
            Entry parent = pending.entry(child.parent(), (Long) identifier(state[index(persister, child.parentProperty())]));
            if (!parent.childBefore.containsKey((Long) id)) {
                parent.childBefore.put((Long) id, oldState == null ? null : snapshot(persister, oldState, child.fields()));
            }
            return;
        }

        Entry entry = pending.entry(entityClass, (Long) id);
        if (tracked.projectVia() == null) entry.projectId = (Long) state[index(persister, "projectId")];
        if (inserted) {
            entry.created = true;
        } else if (entry.before == null && !entry.created && !entry.beforeUnknown) {
            // 같은 트랜잭션에서 여러 번 flush 되어도 처음 값이 "변경 전" 이다
            if (oldState == null) entry.beforeUnknown = true;
            else entry.before = snapshot(persister, oldState, tracked.fields());
        }
        if (newState == null) entry.deleted = true;
        else entry.after = snapshot(persister, newState, tracked.fields());
    }

    /** 허용한 필드만 꺼낸다. 값은 비교·JSON 에 쓸 수 있는 단순한 형태로 바꾼다 */
    private Map<String, Object> snapshot(EntityPersister persister, Object[] state, List<String> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : fields) {
            int i = index(persister, field);
            Object value = state[i];
            if (persister.getPropertyTypes()[i].isEntityType()) {
                Object id = value == null ? null : identifier(value);
                map.put(field + "Id", id);
                Class<?> target = persister.getPropertyTypes()[i].getReturnedClass();
                if (NAME_FIELDS.containsKey(target)) map.put(field + "Name", id == null ? null : new Ref(target, id));
            } else if (value instanceof BigDecimal decimal) {
                map.put(field, new BigDecimal(decimal.stripTrailingZeros().toPlainString())); // 10 과 10.00 은 같은 값
            } else if (value instanceof Enum<?> code) {
                map.put(field, code.name());
            } else {
                map.put(field, value);
            }
        }
        return map;
    }

    /** 이름 자리(Ref)를 대상 종류별 조회로 이름으로 바꾼다. 조회되지 않는 대상은 missing 이 주는 이름, 그래도 없으면 null (ID 만 남는다) */
    private void fillNames(List<Map<String, Object>> maps, java.util.function.BiFunction<Class<?>, Object, String> missing) {
        Map<Class<?>, Set<Object>> wanted = new LinkedHashMap<>();
        maps.forEach(map -> map.values().forEach(value -> {
            if (value instanceof Ref ref) wanted.computeIfAbsent(ref.type(), key -> new java.util.LinkedHashSet<>()).add(ref.id());
        }));
        Map<Ref, String> names = new java.util.HashMap<>();
        wanted.forEach((type, idSet) -> {
            List<Object> ids = new ArrayList<>(idSet);
            String field = NAME_FIELDS.get(type);
            for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
                entityManager.createQuery("select e.id, e." + field + " from " + type.getSimpleName() + " e where e.id in :ids", Object[].class)
                        .setParameter("ids", ids.subList(from, Math.min(from + BATCH_SIZE, ids.size())))
                        .getResultList()
                        .forEach(row -> names.put(new Ref(type, row[0]), (String) row[1]));
            }
            for (Object id : ids) {
                if (!names.containsKey(new Ref(type, id))) names.put(new Ref(type, id), missing.apply(type, id));
            }
        });
        maps.forEach(map -> map.replaceAll((key, value) -> value instanceof Ref ref ? names.get(ref) : value));
    }

    /**
     * 이력·버전에 담는 엔티티 → 필드 (스키마와 자식 행 포함). 엔티티에 속성을 더하고 여기에 넣지 않으면 이력에도 버전에도 빠지므로,
     * 테스트(ModelSnapshotFieldCoverageTest)가 엔티티의 영속 속성과 이 목록을 비교한다.
     */
    public static Map<Class<?>, List<String>> recordedFields() {
        Map<Class<?>, List<String>> result = new LinkedHashMap<>();
        result.put(SchemaInfo.class, SCHEMA_FIELDS.fields());
        TRACKED.forEach((entityClass, tracked) -> result.put(entityClass, tracked.fields()));
        CHILDREN.forEach((entityClass, child) -> result.put(entityClass, child.fields()));
        return result;
    }

    /** 모델 버전(M-9 3차)에 담는 객체 하나 : 이력의 "변경 후" 와 같은 필드·같은 모양 */
    public record ObjectState(Long id, String name, Map<String, Object> state) {}

    /** 스키마는 이력 대상이 아니지만 모델 구조의 일부라 스냅샷에는 담는다 */
    public static final String SCHEMA = "SCHEMA";
    private static final Tracked SCHEMA_FIELDS = new Tracked(null, "schemaName", null, List.of("schemaName", "schemaDescription", "isDefault"));

    /**
     * 프로젝트의 현재 모델 전체 : 대상 종류 → 객체 (ID 순). 이력과 같은 필드 목록을 쓰므로 이력에 남는 값과 버전에 담기는 값이 같다.
     * 호출한 트랜잭션에서 읽는다 (일관된 시점은 호출하는 쪽이 트랜잭션 격리로 정한다).
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<ObjectState>> currentState(Long projectId) {
        Map<Class<?>, Tracked> targets = new LinkedHashMap<>();
        targets.put(SchemaInfo.class, SCHEMA_FIELDS);
        TRACKED.entrySet().stream().sorted(Comparator.comparing((Map.Entry<Class<?>, Tracked> entry) -> entry.getValue().type())).forEach(entry -> targets.put(entry.getKey(), entry.getValue()));

        Map<String, List<ObjectState>> result = new LinkedHashMap<>();
        List<Map<String, Object>> maps = new ArrayList<>();
        targets.forEach((entityClass, tracked) -> {
            EntityPersister persister = persister(entityClass);
            String owner = tracked.projectVia() == null ? "e.projectId" : "e." + tracked.projectVia() + ".projectId";
            List<?> rows = entityManager.createQuery("select e from " + entityClass.getSimpleName() + " e where " + owner + " = :projectId order by e.id", entityClass)
                    .setParameter("projectId", projectId).setHint("org.hibernate.readOnly", true).getResultList();
            Map<Long, Map<String, Object>> states = new LinkedHashMap<>();
            rows.forEach(row -> states.put((Long) identifier(row), snapshot(persister, persister.getValues(row), tracked.fields())));

            // 자식 행(관계의 컬럼 쌍, 인덱스·제약조건의 컬럼, 식별자의 구성 속성)은 부모의 배열로 넣는다
            CHILDREN.forEach((childClass, child) -> {
                if (child.parent() != entityClass) return;
                states.values().forEach(state -> state.put(child.listKey(), new ArrayList<Map<String, Object>>()));
                EntityPersister childPersister = persister(childClass);
                List<Long> parentIds = new ArrayList<>(states.keySet());
                for (int from = 0; from < parentIds.size(); from += BATCH_SIZE) {
                    entityManager.createQuery("select c from " + childClass.getSimpleName() + " c where c." + child.parentProperty() + ".id in :ids order by c." + child.orderField() + ", c.id", childClass)
                            .setParameter("ids", parentIds.subList(from, Math.min(from + BATCH_SIZE, parentIds.size()))).setHint("org.hibernate.readOnly", true)
                            .getResultList()
                            .forEach(row -> {
                                Object[] values = childPersister.getValues(row);
                                Map<String, Object> value = snapshot(childPersister, values, child.fields());
                                maps.add(value);
                                ((List<Map<String, Object>>) states.get((Long) identifier(values[index(childPersister, child.parentProperty())])).get(child.listKey())).add(value);
                            });
                }
            });
            maps.addAll(states.values());
            List<ObjectState> objects = new ArrayList<>();
            states.forEach((id, state) -> objects.add(new ObjectState(id, null, state)));
            result.put(tracked.type() == null ? SCHEMA : tracked.type().name(), objects);
        });

        fillNames(maps, (type, id) -> null);
        targets.forEach((entityClass, tracked) -> result.computeIfPresent(tracked.type() == null ? SCHEMA : tracked.type().name(), (code, objects) -> objects.stream()
                .map(object -> new ObjectState(object.id(), tracked.type() == null ? (String) object.state().get(tracked.nameField()) : Pending.targetName(tracked, object.state()), object.state())).toList()));
        return result;
    }

    private static int index(EntityPersister persister, String property) {
        String[] names = persister.getPropertyNames();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(property)) return i;
        }
        throw new IllegalStateException(persister.getEntityName() + " 에 " + property + " 속성이 없습니다.");
    }

    /** 프록시를 초기화하지 않고 ID 만 읽는다 */
    private Object identifier(Object entity) {
        return entityManagerFactory.getPersistenceUnitUtil().getIdentifier(entity);
    }

    private EntityPersister persister(Class<?> entityClass) {
        return entityManagerFactory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel().getEntityDescriptor(entityClass);
    }

    private static Pending current() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending) return pending;
        }
        return null;
    }

    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AccountDto account ? account.getUsername() : null;
    }

    /** 객체 하나의 이 트랜잭션 변경 */
    private static final class Entry {
        final Class<?> entityClass;
        final Long id;
        Long projectId;
        boolean created;
        boolean deleted;
        /** 수정 이벤트가 이전 값을 주지 않은 경우 (비교할 수 없으므로 UPDATE 로 남긴다) */
        boolean beforeUnknown;
        Map<String, Object> before;
        Map<String, Object> after;
        /** 자식 행 ID → 이 트랜잭션 전의 값 (없던 행은 null) */
        final Map<Long, Map<String, Object>> childBefore = new LinkedHashMap<>();

        Entry(Class<?> entityClass, Long id) {
            this.entityClass = entityClass;
            this.id = id;
        }
    }

    /** 트랜잭션 하나 = 변경 그룹 하나. 트랜잭션에 묶여 있어 끝나면 함께 사라진다 (요청 사이에 남지 않는다) */
    private final class Pending implements TransactionSynchronization {

        private final Map<String, Entry> entries = new LinkedHashMap<>();

        Entry entry(Class<?> entityClass, Long id) {
            return entries.computeIfAbsent(entityClass.getSimpleName() + ":" + id, key -> new Entry(entityClass, id));
        }

        /** 참조 관계 자동 동기화(ReferenceSyncBoundary)보다 뒤에 실행한다 : 동기화가 바꾼 관계도 같은 그룹에 남는다 */
        @Override
        public int getOrder() {
            return LOWEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (readOnly) return;
            entityManager.flush(); // 아직 반영되지 않은 변경의 이벤트를 먼저 받는다
            if (entries.isEmpty()) return;
            long started = System.nanoTime();

            // 1) 객체마다 변경 전·후 상태를 만든다
            Map<String, Map<Long, Map<Long, Map<String, Object>>>> children = currentChildren();
            List<Prepared> prepared = new ArrayList<>();
            for (Entry entry : List.copyOf(entries.values())) {
                if (entry.created && entry.deleted) continue; // 이 트랜잭션 안에서 만들고 지웠다 : 남은 변경이 없다
                Tracked tracked = TRACKED.get(entry.entityClass);
                Child child = CHILDREN.values().stream().filter(c -> c.parent() == entry.entityClass).findFirst().orElse(null);

                if (entry.before == null && entry.after == null && !entry.deleted) {
                    // 자식 행만 바뀌었다 : 부모의 값은 그대로이므로 현재 값을 전후에 같이 쓴다
                    Object parent = entityManager.find(entry.entityClass, entry.id);
                    if (parent == null) continue;
                    EntityPersister persister = persister(entry.entityClass);
                    Object[] state = persister.getValues(parent);
                    if (tracked.projectVia() == null) entry.projectId = (Long) state[index(persister, "projectId")];
                    entry.before = snapshot(persister, state, tracked.fields());
                    entry.after = snapshot(persister, state, tracked.fields());
                }
                Map<Long, Map<String, Object>> rows = children.getOrDefault(entry.entityClass.getSimpleName(), Map.of()).getOrDefault(entry.id, Map.of());
                Map<String, Object> before = entry.created || entry.before == null ? null : withChildren(entry.before, child, child == null ? null : rowsBefore(entry, rows, child));
                Map<String, Object> after = entry.deleted ? null : withChildren(entry.after, child, child == null ? null : new ArrayList<>(rows.values()));
                prepared.add(new Prepared(entry, tracked, before, after));
            }

            // 2) 연관 대상의 이름을 한꺼번에 채운다 (기록 시점의 이름. 전후가 같은 대상이면 같은 이름이라 변경으로 보이지 않는다)
            resolveNames(prepared);

            // 3) 실제로 달라진 것만 저장한다
            String groupId = UUID.randomUUID().toString();
            String userId = currentUserId();
            LocalDateTime now = LocalDateTime.now();
            int saved = 0;
            for (Prepared item : prepared) {
                Entry entry = item.entry();
                Map<String, Object> before = item.before();
                Map<String, Object> after = item.after();
                Map<String, Object> shown = after != null ? after : before;

                Long projectId = projectId(entry, item.tracked(), shown);
                if (projectId == null) continue; // 프로젝트를 알 수 없으면 조회 권한을 정할 수 없다

                Action action = entry.created ? Action.CREATE : entry.deleted ? Action.DELETE : entry.beforeUnknown ? Action.UPDATE : action(before, after);
                if (action == null) continue; // 실제로 달라진 값이 없다

                String name = shown == null ? null : targetName(item.tracked(), shown);
                if (name != null && name.length() > 255) name = name.substring(0, 255); // 이름 길이 때문에 모델 저장이 실패하지 않게 한다
                // 참조 테이블명 → 관계, 컬럼의 인덱스 여부 → 인덱스 : 저장 경로가 아니라 동기화가 만든 변경
                boolean derived = shown != null && (TableRelation.Source.REF_COLUMN.name().equals(shown.get("relationSource"))
                        || TableIndex.Source.COLUMN.name().equals(shown.get("indexSource")));

                entityManager.persist(ModelChangeHistory.builder()
                        .projectId(projectId)
                        .changeGroupId(groupId)
                        .targetType(item.tracked().type())
                        .targetId(entry.id)
                        .targetName(name)
                        .action(action)
                        .beforeData(before == null ? null : JSON.writeValueAsString(before))
                        .afterData(after == null ? null : JSON.writeValueAsString(after))
                        .source(derived ? Source.AUTO_SYNC : Source.USER)
                        .userId(userId)
                        .changedAt(now)
                        .build());
                saved++;
            }
            Map<String, Entry> seen = Map.copyOf(entries);
            entries.clear();
            entityManager.flush(); // 이력 저장이 실패하면 여기서 예외가 나고 트랜잭션 전체가 롤백된다
            if (log.isDebugEnabled()) {
                log.debug("모델 변경 이력 : 변경 객체 {}개 중 {}건 저장, {} ms", seen.size(), saved, (System.nanoTime() - started) / 1_000_000);
            }
        }

        /** 남아 있는 부모(관계·인덱스·제약조건·식별자)의 현재 자식 행 : 부모 엔티티 이름 → 부모 ID → 자식 행 ID → 값 (순서대로) */
        private Map<String, Map<Long, Map<Long, Map<String, Object>>>> currentChildren() {
            Map<String, Map<Long, Map<Long, Map<String, Object>>>> result = new LinkedHashMap<>();
            CHILDREN.forEach((childClass, child) -> {
                List<Long> parentIds = entries.values().stream()
                        .filter(entry -> entry.entityClass == child.parent() && !entry.deleted).map(entry -> entry.id).toList();
                if (parentIds.isEmpty()) return;
                EntityPersister persister = persister(childClass);
                Map<Long, Map<Long, Map<String, Object>>> byParent = result.computeIfAbsent(child.parent().getSimpleName(), key -> new LinkedHashMap<>());
                for (int from = 0; from < parentIds.size(); from += BATCH_SIZE) {
                    entityManager.createQuery("select c from " + childClass.getSimpleName() + " c where c." + child.parentProperty() + ".id in :ids order by c." + child.orderField() + ", c.id", childClass)
                            .setParameter("ids", parentIds.subList(from, Math.min(from + BATCH_SIZE, parentIds.size())))
                            .getResultList()
                            .forEach(row -> {
                                Object[] state = persister.getValues(row);
                                Long parentId = (Long) identifier(state[index(persister, child.parentProperty())]);
                                byParent.computeIfAbsent(parentId, key -> new LinkedHashMap<>()).put((Long) identifier(row), snapshot(persister, state, child.fields()));
                            });
                }
            });
            return result;
        }

        /** 지금의 자식 행에서 이 트랜잭션의 자식 행 변경을 되돌려 변경 전 구성을 만든다 */
        private List<Map<String, Object>> rowsBefore(Entry entry, Map<Long, Map<String, Object>> current, Child child) {
            List<Map<String, Object>> result = new ArrayList<>();
            current.forEach((childId, value) -> {
                if (!entry.childBefore.containsKey(childId)) result.add(new LinkedHashMap<>(value)); // 그대로인 행
                else if (entry.childBefore.get(childId) != null) result.add(entry.childBefore.get(childId)); // 바뀐 행의 이전 값 (null = 새로 생긴 행)
            });
            entry.childBefore.forEach((childId, value) -> {
                if (!current.containsKey(childId) && value != null) result.add(value); // 지워진 행
            });
            result.sort(Comparator.comparing(row -> (Integer) row.get(child.orderField()), Comparator.nullsLast(Comparator.naturalOrder())));
            return result;
        }

        private Map<String, Object> withChildren(Map<String, Object> values, Child child, List<Map<String, Object>> rows) {
            if (child == null) return values;
            Map<String, Object> result = new LinkedHashMap<>(values);
            result.put(child.listKey(), rows);
            return result;
        }

        /** 이름 자리(Ref)를 대상 종류별 조회 한 번으로 이름으로 바꾼다. 이 트랜잭션에서 지워진 대상은 지워지기 전 이름, 그래도 없으면 null (ID 만 남는다) */
        @SuppressWarnings("unchecked")
        private void resolveNames(List<Prepared> prepared) {
            List<Map<String, Object>> maps = new ArrayList<>();
            for (Prepared item : prepared) {
                for (Map<String, Object> state : new Map[]{item.before(), item.after()}) {
                    if (state == null) continue;
                    maps.add(state);
                    state.values().forEach(value -> {
                        if (value instanceof List<?> rows) rows.forEach(row -> maps.add((Map<String, Object>) row));
                    });
                }
            }
            // 이 트랜잭션에서 지워진 대상은 지워지기 전 이름
            fillNames(maps, (type, id) -> {
                Entry gone = entries.get(type.getSimpleName() + ":" + id);
                return gone != null && gone.before != null ? (String) gone.before.get(NAME_FIELDS.get(type)) : null;
            });
        }

        /** 프로젝트 ID 가 없는 엔티티(속성·식별자·속성 매핑)는 부모에서 물려받는다 : 같은 트랜잭션에서 바뀐 부모 → 없으면 저장된 부모 */
        private Long projectId(Entry entry, Tracked tracked, Map<String, Object> shown) {
            if (tracked.projectVia() == null || shown == null) return entry.projectId;
            EntityPersister persister = persister(entry.entityClass);
            Class<?> parentClass = persister.getPropertyTypes()[index(persister, tracked.projectVia())].getReturnedClass();
            Object parentId = shown.get(tracked.projectVia() + "Id");
            if (parentId == null) return null;
            Entry parent = entries.get(parentClass.getSimpleName() + ":" + parentId);
            if (parent != null && parent.projectId != null) return parent.projectId;
            Object found = entityManager.find(parentClass, parentId);
            if (found == null) return null;
            EntityPersister parentPersister = persister(parentClass);
            return (Long) parentPersister.getValues(found)[index(parentPersister, "projectId")];
        }

        /** 목록에서 알아볼 이름 (삭제된 대상도 이 이름으로 보인다) */
        private static String targetName(Tracked tracked, Map<String, Object> shown) {
            return switch (tracked.type()) {
                case PHYSICAL_COLUMN -> joined(".", shown.get("tableName"), shown.get("columnName"));
                case LOGICAL_ATTRIBUTE -> joined(".", shown.get("entityName"), shown.get("attributeName"));
                case LOGICAL_IDENTIFIER -> joined(".", shown.get("entityName"), shown.get("identifierName"));
                case LOGICAL_RELATIONSHIP -> shown.get("relationshipName") != null ? (String) shown.get("relationshipName")
                        : joined(" → ", shown.get("sourceEntityName"), shown.get("targetEntityName"));
                case TABLE_MAPPING -> joined(" ↔ ", shown.get("entityName"), shown.get("tableName"));
                case COLUMN_MAPPING -> joined(" ↔ ", shown.get("attributeName"), shown.get("columnName"));
                default -> (String) shown.get(tracked.nameField());
            };
        }

        private static String joined(String separator, Object first, Object second) {
            if (first == null) return second == null ? null : String.valueOf(second);
            return second == null ? String.valueOf(first) : first + separator + second;
        }

        /** 달라진 값이 없으면 null. 승인 여부만 달라졌으면 APPROVE·REVOKE, 그 외에는 UPDATE */
        private Action action(Map<String, Object> before, Map<String, Object> after) {
            if (Objects.equals(before, after)) return null;
            Set<String> changed = new java.util.LinkedHashSet<>();
            after.forEach((key, value) -> {
                if (!Objects.equals(value, before.get(key))) changed.add(key);
            });
            if (changed.equals(Set.of(APPROVAL))) return Boolean.TRUE.equals(after.get(APPROVAL)) ? Action.APPROVE : Action.REVOKE;
            return Action.UPDATE;
        }
    }

    /** 저장 직전의 이력 1건 후보 */
    private record Prepared(Entry entry, Tracked tracked, Map<String, Object> before, Map<String, Object> after) {}
}
