package com.lexorma.data.mapping.service;

import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion;

import java.util.*;

/**
 * 상속 계층의 물리 변환 전략 해석 (M-7 상속 A·C형, docs/completed/inheritance-strategy-ac-m7.md — D-AC-01·02·11·16). 순수 판정 : 계층(ID 투영)과 변환 기록 행만 본다.
 * <ul>
 *   <li>전략은 루트 계층 단위다 (D-AC-11). 계층의 그룹이 모두 같은 전략으로 기록돼 있으면 그 전략, 하나도 없으면 {@link Kind#NONE} (B형 또는 미생성 — 기록이 없다는 이유로
 *       B형 생성 완료로 보지 않는다), 일부만 있거나 전략·통합 테이블이 섞이면 {@link Kind#INCONSISTENT} (정상 구조로 보지 않는다)</li>
 *   <li>엔터티 문맥 : A형 루트 = 직접(통합 테이블), A형 루트가 아닌 구성원 = 통합(MERGED), C형 말단 = 직접, C형 루트·중간 = 분산(DISTRIBUTED)</li>
 * </ul>
 * 조회·저장을 하지 않는다 (서비스가 계층과 기록을 읽어 넘긴다).
 */
public final class InheritanceConversions {

    /** 계층 전략 */
    public enum Kind {
        /** 기록 없음 : B형 또는 아직 만들지 않은 계층 (상속에 참여하지 않는 엔터티도 여기) */
        NONE,
        SINGLE_TABLE,
        TABLE_PER_CONCRETE,
        /** 그룹 일부만 기록·전략 혼합·통합 테이블이 여럿 — 정상 구조가 아니다 */
        INCONSISTENT
    }

    /** 엔터티의 매핑 문맥 */
    public enum Role {
        /** 엔터티가 자기 테이블에 직접 매핑되는 문맥 (일반·B형·A형 루트·C형 말단) */
        DIRECT,
        /** A형 루트가 아닌 구성원 : 루트 통합 테이블에 통합된다 */
        MERGED,
        /** C형 루트·중간 엔터티 : 말단 테이블에 분산된다 (자기 테이블이 없다) */
        DISTRIBUTED,
        /** 계층 기록이 맞지 않는다 */
        INCONSISTENT
    }

    /** 변환 기록 1행 (ID 만) */
    public record Record(Long id, Long groupId, SubtypeGroupConversion.Strategy strategy, Long tableId, Long classificationColumnId) {
        public static Record of(SubtypeGroupConversion row) {
            return new Record(row.getId(), row.getGroup().getId(), row.getStrategy(), row.getTable() == null ? null : row.getTable().getId(),
                    row.getClassificationColumn() == null ? null : row.getClassificationColumn().getId());
        }
    }

    private final LogicalHierarchy hierarchy;
    private final Map<Long, Record> byGroup = new HashMap<>();
    private final Map<Long, Kind> kindByRoot = new HashMap<>();

    private InheritanceConversions(LogicalHierarchy hierarchy, Collection<Record> records) {
        this.hierarchy = hierarchy;
        records.forEach(r -> byGroup.put(r.groupId(), r));
    }

    public static InheritanceConversions of(LogicalHierarchy hierarchy, Collection<Record> records) {
        return new InheritanceConversions(hierarchy, records);
    }

    public static InheritanceConversions ofRows(LogicalHierarchy hierarchy, Collection<SubtypeGroupConversion> rows) {
        return new InheritanceConversions(hierarchy, rows.stream().map(Record::of).toList());
    }

    public static InheritanceConversions empty(LogicalHierarchy hierarchy) {
        return new InheritanceConversions(hierarchy, List.of());
    }

    public LogicalHierarchy hierarchy() {
        return hierarchy;
    }

    public Record record(Long groupId) {
        return byGroup.get(groupId);
    }

    public Collection<Record> records() {
        return Collections.unmodifiableCollection(byGroup.values());
    }

    /** 기록이 가리키는 그룹이 지금 계층에 없다 (그룹은 FK 로 남아 있어 실제로는 생기지 않는다 — 방어) */
    public boolean orphan(Record record) {
        return hierarchy.group(record.groupId()) == null;
    }

    /** 루트 계층의 그룹 (루트 → 후손 순서, 그룹 순서) */
    public List<LogicalHierarchy.Group> groupsOfTree(Long rootId) {
        List<LogicalHierarchy.Group> groups = new ArrayList<>();
        for (Long id : hierarchy.subtree(rootId)) groups.addAll(hierarchy.groupsOf(id));
        return groups;
    }

    /** 계층 안에 기록된 그룹이 하나라도 있는가 */
    public boolean hasRecords(Long rootId) {
        return groupsOfTree(rootId).stream().anyMatch(g -> byGroup.containsKey(g.id()));
    }

    /** 루트 계층의 전략 */
    public Kind kindOfRoot(Long rootId) {
        return kindByRoot.computeIfAbsent(rootId, this::computeKind);
    }

    private Kind computeKind(Long rootId) {
        List<LogicalHierarchy.Group> groups = groupsOfTree(rootId);
        if (groups.isEmpty()) return Kind.NONE;
        int recorded = 0;
        Set<SubtypeGroupConversion.Strategy> strategies = EnumSet.noneOf(SubtypeGroupConversion.Strategy.class);
        Set<Long> tables = new HashSet<>();
        for (LogicalHierarchy.Group group : groups) {
            Record record = byGroup.get(group.id());
            if (record == null) continue;
            recorded++;
            strategies.add(record.strategy());
            if (record.tableId() != null) tables.add(record.tableId());
        }
        if (recorded == 0) return Kind.NONE;
        if (recorded != groups.size() || strategies.size() != 1) return Kind.INCONSISTENT;
        SubtypeGroupConversion.Strategy strategy = strategies.iterator().next();
        if (strategy == SubtypeGroupConversion.Strategy.SINGLE_TABLE) return tables.size() == 1 ? Kind.SINGLE_TABLE : Kind.INCONSISTENT;
        return tables.isEmpty() ? Kind.TABLE_PER_CONCRETE : Kind.INCONSISTENT;
    }

    /** 엔터티가 속한 계층의 전략 (참여하지 않으면 NONE) */
    public Kind kind(Long entityId) {
        if (!hierarchy.participates(entityId) || hierarchy.cyclic(entityId)) return Kind.NONE;
        return kindOfRoot(hierarchy.root(entityId));
    }

    /** A·C 로 기록된(또는 기록이 맞지 않는) 계층에 속하는가 — B형·일반 규칙을 그대로 쓰면 안 되는 엔터티 */
    public boolean alternative(Long entityId) {
        return kind(entityId) != Kind.NONE;
    }

    /** 말단 : 상속에 참여하고 서브타입 그룹이 없다 */
    public boolean leaf(Long entityId) {
        return hierarchy.participates(entityId) && hierarchy.groupsOf(entityId).isEmpty();
    }

    /** 엔터티의 매핑 문맥 */
    public Role role(Long entityId) {
        Kind kind = kind(entityId);
        return switch (kind) {
            case NONE -> Role.DIRECT;
            case INCONSISTENT -> Role.INCONSISTENT;
            case SINGLE_TABLE -> hierarchy.parent(entityId) == null ? Role.DIRECT : Role.MERGED;
            case TABLE_PER_CONCRETE -> leaf(entityId) ? Role.DIRECT : Role.DISTRIBUTED;
        };
    }

    /** A형 계층의 통합 테이블 ID (A형이 아니면 null) */
    public Long integratedTable(Long entityId) {
        if (kind(entityId) != Kind.SINGLE_TABLE) return null;
        for (LogicalHierarchy.Group group : groupsOfTree(hierarchy.root(entityId))) {
            Record record = byGroup.get(group.id());
            if (record != null && record.tableId() != null) return record.tableId();
        }
        return null;
    }

    /** C형 : 엔터티 아래(자기 포함)의 말단 (분산 엔터티가 펼쳐지는 테이블들의 엔터티) */
    public List<Long> leavesUnder(Long entityId) {
        return hierarchy.subtree(entityId).stream().filter(this::leaf).toList();
    }

    /** A형 : 같은 계층 구성원인가 (루트 문맥에서 서브타입 속성 매핑 허용 판단 — 같은 프로젝트라는 이유만으로 허용하지 않는다) */
    public boolean sameSingleTableTree(Long rootEntityId, Long memberEntityId) {
        if (kind(rootEntityId) != Kind.SINGLE_TABLE || hierarchy.parent(rootEntityId) != null) return false;
        return hierarchy.participates(memberEntityId) && !hierarchy.cyclic(memberEntityId) && hierarchy.root(memberEntityId).equals(rootEntityId);
    }

    /** C형 : 말단 엔터티의 조상(루트·중간)인가 */
    public boolean concreteAncestor(Long leafEntityId, Long ancestorEntityId) {
        return kind(leafEntityId) == Kind.TABLE_PER_CONCRETE && leaf(leafEntityId) && hierarchy.ancestors(leafEntityId).contains(ancestorEntityId);
    }
}
