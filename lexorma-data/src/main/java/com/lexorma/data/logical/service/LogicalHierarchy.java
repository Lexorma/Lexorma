package com.lexorma.data.logical.service;

import com.lexorma.data.logical.domain.entity.LogicalSubtype;
import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 논리 모델 하나의 슈퍼타입·서브타입 계층 (M-6 후속 5차) : 그룹(슈퍼타입 → 그룹)과 구성원(그룹 → 서브타입)의 ID 만 담은 불변 값.
 * 모델의 그룹·구성원을 한 번씩 읽어 메모리에서 만든다 (최대 3단계라 재귀 쿼리·계층 캐시를 쓰지 않는다). 연관은 FK 값만 읽는다 (프록시를 초기화하지 않는다).
 * <ul>
 *   <li>단계 : 루트(슈퍼타입이 없는 엔터티) = 1단계. 서브타입은 직접 슈퍼타입 + 1. 그룹 기호는 단계에 넣지 않는다</li>
 *   <li>{@link #with} : 그룹 하나를 바꾼(또는 지운·더한) 가정의 계층 — 저장 전에 순환·깊이·영향을 같은 규칙으로 판정한다</li>
 *   <li>순환이 있는 계층(잘못된 데이터)에서도 끝나도록 위로 올라가는 탐색은 방문한 엔터티를 기억한다</li>
 * </ul>
 */
public final class LogicalHierarchy {

    /** 최대 단계 (루트 = 1) */
    public static final int MAX_LEVEL = 3;

    /** 그룹 하나 : 슈퍼타입, 구성원(순서대로) */
    public record Group(Long id, Long supertypeId, List<Long> memberIds) {
    }

    private static final LogicalHierarchy EMPTY = new LogicalHierarchy(Map.of());

    private final Map<Long, Group> groups;
    /** 서브타입 엔터티 → 그룹 ID */
    private final Map<Long, Long> groupOfMember = new HashMap<>();
    /** 슈퍼타입 엔터티 → 그룹 ID (순서대로) */
    private final Map<Long, List<Long>> groupsOfSupertype = new HashMap<>();

    private LogicalHierarchy(Map<Long, Group> groups) {
        this.groups = groups;
        groups.values().forEach(group -> {
            groupsOfSupertype.computeIfAbsent(group.supertypeId(), id -> new ArrayList<>()).add(group.id());
            group.memberIds().forEach(member -> groupOfMember.put(member, group.id()));
        });
    }

    public static LogicalHierarchy empty() {
        return EMPTY;
    }

    /** 그룹은 정렬 순서·ID 순, 구성원은 그룹별 정렬 순서·ID 순으로 받는다 (리포지토리 조회 순서) */
    public static LogicalHierarchy of(Collection<LogicalSubtypeGroup> groupRows, Collection<LogicalSubtype> memberRows) {
        Map<Long, List<Long>> members = new HashMap<>();
        memberRows.forEach(m -> members.computeIfAbsent(m.getGroup().getId(), id -> new ArrayList<>()).add(m.getSubtypeEntity().getId()));
        Map<Long, Group> groups = new java.util.LinkedHashMap<>();
        groupRows.forEach(g -> groups.put(g.getId(), new Group(g.getId(), g.getSupertypeEntity().getId(), List.copyOf(members.getOrDefault(g.getId(), List.of())))));
        return new LogicalHierarchy(groups);
    }

    /**
     * 조회 경로용 : [그룹 ID, 슈퍼타입 ID] 와 [그룹 ID, 서브타입 ID] 행으로 만든다 (그룹·구성원 엔티티를 올리지 않는다 — M-6 최종 보완 측정).
     * 순서는 {@link #of} 와 같다 (그룹은 정렬 순서·ID, 구성원은 그룹별 정렬 순서·ID)
     */
    public static LogicalHierarchy ofLinks(Collection<Object[]> groupLinks, Collection<Object[]> memberLinks) {
        Map<Long, List<Long>> members = new HashMap<>();
        memberLinks.forEach(m -> members.computeIfAbsent((Long) m[0], id -> new ArrayList<>()).add((Long) m[1]));
        Map<Long, Group> groups = new java.util.LinkedHashMap<>();
        groupLinks.forEach(g -> groups.put((Long) g[0], new Group((Long) g[0], (Long) g[1], List.copyOf(members.getOrDefault((Long) g[0], List.of())))));
        return new LogicalHierarchy(groups);
    }

    /** 그룹 groupId(null = 새 그룹 — 키는 0)를 supertypeId·memberIds 로 바꾼 가정. supertypeId 가 null 이면 그 그룹을 지운 가정 */
    public LogicalHierarchy with(Long groupId, Long supertypeId, List<Long> memberIds) {
        Map<Long, Group> changed = new java.util.LinkedHashMap<>(groups);
        Long key = groupId == null ? 0L : groupId;
        if (supertypeId == null) changed.remove(key);
        else changed.put(key, new Group(key, supertypeId, List.copyOf(memberIds)));
        return new LogicalHierarchy(changed);
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    public Group group(Long groupId) {
        return groups.get(groupId);
    }

    public Collection<Group> groups() {
        return groups.values();
    }

    /** 엔터티가 서브타입으로 속한 그룹 (없으면 null) */
    public Group parentGroup(Long entityId) {
        Long groupId = groupOfMember.get(entityId);
        return groupId == null ? null : groups.get(groupId);
    }

    /** 직접 슈퍼타입 (없으면 null) */
    public Long parent(Long entityId) {
        Group group = parentGroup(entityId);
        return group == null ? null : group.supertypeId();
    }

    /** 엔터티가 슈퍼타입인 그룹 (순서대로) */
    public List<Group> groupsOf(Long supertypeId) {
        return groupsOfSupertype.getOrDefault(supertypeId, List.of()).stream().map(groups::get).toList();
    }

    /** 직접 서브타입 (그룹 순서 → 그룹 안 순서) */
    public List<Long> children(Long entityId) {
        List<Long> children = new ArrayList<>();
        groupsOf(entityId).forEach(group -> children.addAll(group.memberIds()));
        return children;
    }

    /** 상속 구조에 참여하는지 : 슈퍼타입이거나 서브타입 */
    public boolean participates(Long entityId) {
        return groupOfMember.containsKey(entityId) || groupsOfSupertype.containsKey(entityId);
    }

    /** 조상 (직접 슈퍼타입 → … → 루트). 순환이면 다시 만나는 곳에서 멈춘다 ({@link #cyclic}) */
    public List<Long> ancestors(Long entityId) {
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>(List.of(entityId));
        for (Long up = parent(entityId); up != null && seen.add(up); up = parent(up)) result.add(up);
        return result;
    }

    /** 위로 올라가다 자기 자신이나 이미 지난 엔터티를 다시 만나는지 */
    public boolean cyclic(Long entityId) {
        Set<Long> seen = new java.util.HashSet<>(List.of(entityId));
        for (Long up = parent(entityId); up != null; up = parent(up)) {
            if (!seen.add(up)) return true;
        }
        return false;
    }

    /** 루트 (조상이 없으면 자기 자신) */
    public Long root(Long entityId) {
        List<Long> ancestors = ancestors(entityId);
        return ancestors.isEmpty() ? entityId : ancestors.getLast();
    }

    /** 단계 : 루트 = 1 */
    public int level(Long entityId) {
        return ancestors(entityId).size() + 1;
    }

    /** 루트 → … → 자기 자신 (유효 속성을 쌓는 순서) */
    public List<Long> chain(Long entityId) {
        List<Long> chain = new ArrayList<>(ancestors(entityId).reversed());
        chain.add(entityId);
        return chain;
    }

    /** 후손 전체 (너비 우선, 자기 자신 제외). 순환이 있어도 한 번씩 */
    public List<Long> descendants(Long entityId) {
        Set<Long> found = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(entityId));
        while (!queue.isEmpty()) {
            for (Long child : children(queue.poll())) {
                if (!child.equals(entityId) && found.add(child)) queue.add(child);
            }
        }
        return new ArrayList<>(found);
    }

    /** 자기 자신 + 후손 */
    public List<Long> subtree(Long entityId) {
        List<Long> result = new ArrayList<>(List.of(entityId));
        result.addAll(descendants(entityId));
        return result;
    }

    /** 상속 구조에 참여하는 엔터티 전체 */
    public Set<Long> participants() {
        Set<Long> result = new LinkedHashSet<>(groupsOfSupertype.keySet());
        result.addAll(groupOfMember.keySet());
        return result;
    }

    /** 엔터티 → 직접 상속 연결 상대 (슈퍼타입·서브타입). 이웃 탐색·범위 밖 수 계산에 쓴다 */
    public List<Long> links(Long entityId) {
        List<Long> result = new ArrayList<>();
        Long parent = parent(entityId);
        if (parent != null) result.add(parent);
        result.addAll(children(entityId));
        return result;
    }

    /** 두 엔터티의 조상 사슬(루트 쪽)이 같은지 — 상속 변경이 유효 정의를 바꾸는지 판정 */
    public boolean sameChain(LogicalHierarchy other, Long entityId) {
        return Objects.equals(ancestors(entityId), other.ancestors(entityId));
    }
}
