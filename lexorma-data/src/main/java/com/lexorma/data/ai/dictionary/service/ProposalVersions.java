package com.lexorma.data.ai.dictionary.service;

import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.data.ai.evidence.EvidenceCatalog;
import com.lexorma.data.ai.evidence.EvidenceType;
import com.lexorma.data.ai.tool.MetadataToolSession;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.sourcemapping.domain.entity.SourceTableMapping;
import com.lexorma.data.sourcemapping.repository.SourceTableMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * AI 설명 제안의 기준 버전 (AI-5) : 생성할 때 대상과 <b>실제로 쓴(검증된) 근거</b>의 버전을 남기고, 승인·재확인 때 지금과 비교해 제안에 영향을 주는 변경만 골라낸다.
 * <ul>
 *   <li>물리 모델(테이블·컬럼·관계) : 수정 일시(MDFCN_DT) + 그 객체의 모델 변경 이력 마지막 ID. 수정 일시가 다르면 그 뒤의 변경 이력에서 바뀐 필드를 보고,
 *       업무 설명·승인 여부·정렬 순서만 바뀌었으면 영향 없음으로 본다. 수정 일시가 바뀌었는데 이력이 없으면(직접 DB 수정 등) 알 수 없으므로 영향 있음으로 본다</li>
 *   <li>표준 용어·단어·도메인·스키마 : 변경 이력이 없어 수정 일시만 본다 (바뀌었으면 영향 있음 — 안전한 쪽)</li>
 *   <li>변경 이력 근거(HISTORY) : 추가만 하는 기록이라 있는지만 본다</li>
 *   <li>원본 카탈로그 : 대상 테이블이 원본 테이블과 매핑되어 있으면 반영 순서와 대상(컬럼이면 그 컬럼, 테이블이면 테이블 COMMENT 와 근거로 쓴 컬럼)의
 *       원본 값(타입·길이·NULL·COMMENT)을 남긴다. 반영 순서가 같으면 그대로, 다르면 값을 비교하고, 원본 테이블을 찾을 수 없으면 영향 있음</li>
 * </ul>
 * 근거와 무관한 객체(근거로 들지 않은 다른 컬럼·테이블)의 변경은 보지 않는다 — 테이블 전체·프로젝트 전체 버전으로 판단하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ProposalVersions {

    private static final JsonMapper JSON = JsonMapper.shared();
    /** 바뀌어도 설명의 근거에 영향이 없는 필드 (업무 설명 자체는 따로 비교한다) */
    static final Set<String> IGNORED_FIELDS = Set.of("bizDescription", "isApproval", "sortOrder");

    /**
     * @param version   수정 일시 (없으면 null)
     * @param historyId 그 객체의 모델 변경 이력 마지막 ID (물리 객체만, 없으면 null)
     */
    public record Ref(EvidenceType type, Long id, String name, LocalDateTime version, Long historyId) {
    }

    public record SourceColumn(String name, String dataType, Integer maxLength, String precision, Integer scale, Boolean nullable, String comment) {
    }

    public record SourceRef(Long connectionId, Long sourceTableId, Integer reflectedOrder, String tableComment, List<SourceColumn> columns) {
    }

    /** 생성 시점의 기준 : 대상, 그때의 업무 설명, 근거 객체, 원본 카탈로그 값 */
    public record Base(Ref target, String bizDescription, List<Ref> objects, List<SourceRef> sources, LocalDateTime capturedAt) {
    }

    /**
     * 영향을 주는 변경 하나
     *
     * @param kind   TARGET_DELETED · OBJECT_DELETED · MODIFIED · MODIFIED_UNKNOWN · BIZ_DESCRIPTION_CHANGED · SOURCE_CHANGED · SOURCE_TABLE_MISSING
     * @param fields 바뀐 필드 (알 때만)
     * @param diffs  바뀐 필드의 생성 때 값 → 지금 값 (알 때만 : 원본은 기준 버전과 지금 카탈로그, 물리 모델은 그 뒤의 변경 이력, 업무 설명은 기준과 지금 값)
     */
    public record Change(String kind, EvidenceType type, Long id, String name, List<String> fields, List<Diff> diffs) {
        public Change(String kind, EvidenceType type, Long id, String name, List<String> fields) {
            this(kind, type, id, name, fields, List.of());
        }
    }

    /** 바뀐 값 하나 (없던 값·없어진 값은 null) */
    public record Diff(String field, String before, String after) {
    }

    private final EvidenceCatalog catalog;
    private final ModelChangeHistoryRepository histories;
    private final SourceTableMappingRepository sourceMappings;
    private final SourceCatalogReader sourceCatalog;

    /**
     * 생성 시점의 기준을 만든다 (읽기 전용 트랜잭션 안에서). 버전은 Tool 이 메타데이터를 돌려준 시점의 것(세션 스냅숏)을 쓴다
     *
     * @param target       대상 (TABLE·COLUMN)
     * @param evidence     검증된 근거 (type:id)
     * @param columnNames  원본 비교에 쓸 모델 컬럼 이름 (컬럼 대상이면 그 컬럼, 테이블 대상이면 근거로 쓴 컬럼)
     */
    public Base capture(MetadataToolSession session, EvidenceType targetType, Long targetId, String targetName, String bizDescription,
                        Collection<EvidenceRef> evidence, Long tableId, Collection<String> columnNames) {
        Long projectId = session.context().projectId();
        Ref target = ref(session, projectId, targetType, targetId, targetName);
        Map<String, Ref> objects = new LinkedHashMap<>();
        for (EvidenceRef e : evidence) {
            if (e.type() == targetType && Objects.equals(e.id(), targetId)) continue;
            objects.putIfAbsent(e.type() + ":" + e.id(), ref(session, projectId, e.type(), e.id(), e.name()));
        }
        return new Base(target, bizDescription, List.copyOf(objects.values()), sources(projectId, tableId, columnNames), LocalDateTime.now());
    }

    /** 근거 하나 (종류·ID·이름) */
    public record EvidenceRef(EvidenceType type, Long id, String name) {
    }

    private Ref ref(MetadataToolSession session, Long projectId, EvidenceType type, Long id, String name) {
        EvidenceCatalog.Snapshot seen = session.seen(type, id);
        LocalDateTime version = seen != null ? seen.version() : catalog.load(type, List.of(id)).values().stream().findFirst().map(EvidenceCatalog.Snapshot::version).orElse(null);
        ModelChangeHistory.TargetType physical = physical(type);
        Long historyId = physical == null ? null : histories.lastId(projectId, physical, id);
        return new Ref(type, id, name, version, historyId);
    }

    /** 지금과 비교해 영향을 주는 변경 (읽기 전용 트랜잭션 안에서). 대상의 업무 설명은 currentBizDescription 으로 따로 넘긴다 (잠근 행의 값) */
    public List<Change> changes(Long projectId, Base base, String currentBizDescription, boolean checkBizDescription) {
        List<Change> changes = new ArrayList<>();
        Ref target = base.target();
        Optional<EvidenceCatalog.Snapshot> now = current(projectId, target);
        if (now.isEmpty()) {
            changes.add(new Change("TARGET_DELETED", target.type(), target.id(), target.name(), List.of()));
            return changes;
        }
        if (checkBizDescription && !Objects.equals(blank(base.bizDescription()), blank(currentBizDescription))) {
            changes.add(new Change("BIZ_DESCRIPTION_CHANGED", target.type(), target.id(), target.name(), List.of("bizDescription"),
                    List.of(new Diff("bizDescription", blank(base.bizDescription()), blank(currentBizDescription)))));
        }
        modified(projectId, target, now.get(), base.capturedAt()).ifPresent(changes::add);
        for (Ref object : base.objects()) {
            Optional<EvidenceCatalog.Snapshot> current = current(projectId, object);
            if (current.isEmpty()) {
                changes.add(new Change("OBJECT_DELETED", object.type(), object.id(), object.name(), List.of()));
                continue;
            }
            modified(projectId, object, current.get(), base.capturedAt()).ifPresent(changes::add);
        }
        changes.addAll(sourceChanges(projectId, base));
        return changes;
    }

    private Optional<EvidenceCatalog.Snapshot> current(Long projectId, Ref ref) {
        return catalog.load(ref.type(), List.of(ref.id())).values().stream().filter(s -> projectId.equals(s.projectId())).findFirst();
    }

    private Optional<Change> modified(Long projectId, Ref ref, EvidenceCatalog.Snapshot now, LocalDateTime since) {
        if (ref.type() == EvidenceType.HISTORY || Objects.equals(ref.version(), now.version())) return Optional.empty();
        ModelChangeHistory.TargetType physical = physical(ref.type());
        if (physical == null) return Optional.of(new Change("MODIFIED_UNKNOWN", ref.type(), ref.id(), ref.name(), List.of()));
        List<ModelChangeHistory> after = histories.changesAfter(projectId, physical, ref.id(), ref.historyId() == null ? 0L : ref.historyId(),
                since == null ? LocalDateTime.MIN : since);
        if (after.isEmpty()) return Optional.of(new Change("MODIFIED_UNKNOWN", ref.type(), ref.id(), ref.name(), List.of()));
        Set<String> fields = new LinkedHashSet<>();
        // 필드마다 처음 바뀌기 전 값과 마지막 값 (이력은 오래된 것부터)
        Map<String, String> first = new LinkedHashMap<>();
        Map<String, String> last = new LinkedHashMap<>();
        for (ModelChangeHistory h : after) {
            if (h.getAction() == ModelChangeHistory.Action.DELETE) fields.add("DELETE");
            JsonNode before = h.getBeforeData() == null ? null : JSON.readTree(h.getBeforeData());
            JsonNode afterData = h.getAfterData() == null ? null : JSON.readTree(h.getAfterData());
            for (String field : changedFields(h)) {
                fields.add(field);
                if (before != null) first.putIfAbsent(field, value(before.get(field)));
                if (afterData != null) last.put(field, value(afterData.get(field)));
            }
        }
        fields.removeAll(IGNORED_FIELDS);
        List<Diff> diffs = fields.stream().filter(f -> first.containsKey(f) || last.containsKey(f)).map(f -> new Diff(f, first.get(f), last.get(f))).toList();
        return fields.isEmpty() ? Optional.empty() : Optional.of(new Change("MODIFIED", ref.type(), ref.id(), ref.name(), List.copyOf(fields), diffs));
    }

    private static Set<String> changedFields(ModelChangeHistory h) {
        Set<String> fields = new LinkedHashSet<>();
        JsonNode before = h.getBeforeData() == null ? null : JSON.readTree(h.getBeforeData());
        JsonNode after = h.getAfterData() == null ? null : JSON.readTree(h.getAfterData());
        if (before == null || after == null) {
            fields.add(h.getAction().name());
            return fields;
        }
        after.propertyNames().forEach(name -> {
            if (!Objects.equals(after.get(name), before.get(name))) fields.add(name);
        });
        before.propertyNames().forEach(name -> {
            if (!after.has(name)) fields.add(name);
        });
        return fields;
    }

    // ------------------------------------------------------------------ 원본 카탈로그

    private List<SourceRef> sources(Long projectId, Long tableId, Collection<String> columnNames) {
        List<SourceRef> refs = new ArrayList<>();
        for (SourceTableMapping mapping : sourceMappings.findAllByProjectIdAndTableId(projectId, tableId)) {
            if (mapping.isExclusion()) continue;
            source(projectId, mapping.getConnectionId(), mapping.getSourceTableId(), columnNames).ifPresent(refs::add);
        }
        return refs;
    }

    /** 원본 테이블의 지금(마지막 반영) 값. 연결·테이블을 찾을 수 없으면 비어 있다 */
    private Optional<SourceRef> source(Long projectId, Long connectionId, Long sourceTableId, Collection<String> columnNames) {
        Optional<SourceCatalogReader.Snapshot> latest = sourceCatalog.latest(projectId, connectionId);
        if (latest.isEmpty() || latest.get().reflectedOrder() == null) return Optional.empty();
        SourceCatalogReader.Snapshot snapshot = latest.get();
        SourceCatalogReader.Table table = snapshot.tables().stream().filter(t -> t.id().equals(sourceTableId)).findFirst().orElse(null);
        if (table == null) return Optional.empty();
        SourceCatalogReader.TableDetail detail = sourceCatalog.tableDetails(projectId, connectionId, snapshot.reflectedOrder(), List.of(sourceTableId)).get(sourceTableId);
        List<SourceColumn> columns = new ArrayList<>();
        if (detail != null) {
            for (String name : columnNames) {
                String compare = sourceCatalog.compareName(snapshot.connection(), SourceCatalogReader.NameKind.COLUMN, name);
                detail.columns().stream().filter(c -> c.compareName().equals(compare)).findFirst()
                        .ifPresent(c -> columns.add(new SourceColumn(name, c.dataType(), c.maxLength(), c.precision(), c.scale(), c.nullable(), c.comment())));
            }
        }
        return Optional.of(new SourceRef(connectionId, sourceTableId, snapshot.reflectedOrder(), table.comment(), columns));
    }

    private List<Change> sourceChanges(Long projectId, Base base) {
        List<Change> changes = new ArrayList<>();
        for (SourceRef was : base.sources()) {
            List<String> names = was.columns().stream().map(SourceColumn::name).toList();
            Optional<SourceRef> now = source(projectId, was.connectionId(), was.sourceTableId(), names);
            if (now.isEmpty()) {
                changes.add(new Change("SOURCE_TABLE_MISSING", EvidenceType.TABLE, base.target().type() == EvidenceType.TABLE ? base.target().id() : null, base.target().name(), List.of()));
                continue;
            }
            if (Objects.equals(was.reflectedOrder(), now.get().reflectedOrder())) continue;
            List<String> fields = new ArrayList<>();
            List<Diff> diffs = new ArrayList<>();
            if (!Objects.equals(blank(was.tableComment()), blank(now.get().tableComment()))) {
                fields.add("tableComment");
                diffs.add(new Diff("tableComment", blank(was.tableComment()), blank(now.get().tableComment())));
            }
            Map<String, SourceColumn> current = new LinkedHashMap<>();
            now.get().columns().forEach(c -> current.put(c.name(), c));
            for (SourceColumn column : was.columns()) {
                SourceColumn c = current.get(column.name());
                if (c == null) {
                    fields.add(column.name() + ":missing");
                    diffs.add(new Diff(column.name() + ":definition", definition(column), null));
                    continue;
                }
                if (column.equals(c)) continue;
                boolean commentChanged = !Objects.equals(blank(column.comment()), blank(c.comment()));
                // 같은 컬럼의 COMMENT 와 정의가 함께 바뀌면 COMMENT 를 이름으로 두고 (예전과 같다) 값은 둘 다 남긴다
                fields.add(column.name() + (commentChanged ? ":comment" : ":definition"));
                if (commentChanged) diffs.add(new Diff(column.name() + ":comment", blank(column.comment()), blank(c.comment())));
                if (!definition(column).equals(definition(c))) diffs.add(new Diff(column.name() + ":definition", definition(column), definition(c)));
            }
            if (!fields.isEmpty()) changes.add(new Change("SOURCE_CHANGED", base.target().type(), base.target().id(), base.target().name(), fields, diffs));
        }
        return changes;
    }

    /** 원본 컬럼 정의 (COMMENT 제외) : 타입 · 길이/정밀도,소수 · NULL 허용 */
    private static String definition(SourceColumn c) {
        StringBuilder text = new StringBuilder(String.valueOf(c.dataType()));
        if (c.maxLength() != null) text.append(" · length ").append(c.maxLength());
        if (c.precision() != null) text.append(" · precision ").append(c.precision()).append(c.scale() == null ? "" : "," + c.scale());
        text.append(Boolean.FALSE.equals(c.nullable()) ? " · NOT NULL" : " · NULL");
        return text.toString();
    }

    private static String value(JsonNode node) {
        return node == null || node.isNull() ? null : node.isValueNode() ? node.asString() : node.toString();
    }

    private static ModelChangeHistory.TargetType physical(EvidenceType type) {
        return switch (type) {
            case TABLE -> ModelChangeHistory.TargetType.PHYSICAL_TABLE;
            case COLUMN -> ModelChangeHistory.TargetType.PHYSICAL_COLUMN;
            case RELATION -> ModelChangeHistory.TargetType.PHYSICAL_RELATION;
            default -> null;
        };
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String write(Base base) {
        return JSON.writeValueAsString(base);
    }

    public static Base read(String json) {
        return JSON.readValue(json, Base.class);
    }
}
