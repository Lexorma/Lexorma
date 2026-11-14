package com.lexorma.data.sourcemapping.repository;

import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.sourcemapping.compare.CompareModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 원본 비교(M-4d)에 쓰는 물리 모델 읽기. 종류마다 스칼라 JPQL 한 번(IN 목록 1000개씩)으로 읽는다 : 엔티티와 연관(표준 용어 등)을 올리지 않아 N+1 이 없다.
 * 읽기만 한다.
 */
@Repository
public class SourceCompareModelRepository {

    private static final int IN_CHUNK = 1000;

    @PersistenceContext
    private EntityManager entityManager;

    /** TABLE 들의 컬럼·UNIQUE/CHECK·인덱스·FK. FK 대상 TABLE 의 컬럼도 읽어 columns 에 넣는다 */
    public Loaded load(Long projectId, Collection<Long> tableIds) {
        Map<Long, CompareModel.Column> columns = new LinkedHashMap<>();
        Map<Long, List<CompareModel.Column>> columnsByTable = new HashMap<>();
        Map<Long, List<CompareModel.Constraint>> constraints = new HashMap<>();
        Map<Long, List<CompareModel.Index>> indexes = new HashMap<>();
        Map<Long, List<CompareModel.Relation>> relations = new HashMap<>();
        List<Long> ids = List.copyOf(new LinkedHashSet<>(tableIds));
        if (ids.isEmpty()) return new Loaded(Map.of(), columns);

        Map<Long, Object[]> tableHeads = new HashMap<>();
        chunks(ids, part -> {
            for (Object[] row : list("select t.id, t.tableName, t.sourceComment, t.isApproval from TableInfo t where t.projectId = :projectId and t.id in :ids", projectId, part)) {
                tableHeads.put((Long) row[0], row);
            }
            for (Object[] row : list("select c.id, t.id, c.columnName, c.dataType, c.maxLength, c.precision, c.scale, c.isNullable, c.isRequired, c.isPk, c.isUnique,"
                    + " c.defaultValue, c.sortOrder, c.sourceComment, c.isApproval from ColumnInfo c join c.tableInfo t where c.projectId = :projectId and t.id in :ids",
                    projectId, part)) {
                CompareModel.Column column = column(row);
                columns.put(column.id(), column);
                columnsByTable.computeIfAbsent(column.tableId(), k -> new ArrayList<>()).add(column);
            }
            Map<Long, Object[]> heads = new LinkedHashMap<>();
            Map<Long, List<Long>> members = new LinkedHashMap<>();
            for (Object[] row : list("select k.id, t.id, k.constraintName, k.constraintType, k.checkExpression, col.id from TableConstraint k join k.tableInfo t"
                    + " left join k.columns kc left join kc.column col where k.projectId = :projectId and t.id in :ids order by k.id, kc.columnOrder", projectId, part)) {
                heads.putIfAbsent((Long) row[0], row);
                List<Long> list = members.computeIfAbsent((Long) row[0], k -> new ArrayList<>());
                if (row[5] != null) list.add((Long) row[5]);
            }
            heads.forEach((id, row) -> constraints.computeIfAbsent((Long) row[1], k -> new ArrayList<>())
                    .add(new CompareModel.Constraint(id, (Long) row[1], (String) row[2], String.valueOf(row[3]), (String) row[4], List.copyOf(members.get(id)))));

            Map<Long, Object[]> indexHeads = new LinkedHashMap<>();
            Map<Long, List<CompareModel.IndexMember>> indexMembers = new LinkedHashMap<>();
            for (Object[] row : list("select i.id, t.id, i.indexName, i.indexType, i.isUnique, i.condition, col.id, ic.expression, ic.sort, ic.id from TableIndex i"
                    + " join i.tableInfo t left join i.columns ic left join ic.column col where i.projectId = :projectId and t.id in :ids order by i.id, ic.columnOrder", projectId, part)) {
                indexHeads.putIfAbsent((Long) row[0], row);
                List<CompareModel.IndexMember> list = indexMembers.computeIfAbsent((Long) row[0], k -> new ArrayList<>());
                if (row[9] != null) list.add(new CompareModel.IndexMember((Long) row[6], (String) row[7], (String) row[8]));
            }
            indexHeads.forEach((id, row) -> indexes.computeIfAbsent((Long) row[1], k -> new ArrayList<>())
                    .add(new CompareModel.Index(id, (Long) row[1], (String) row[2], (String) row[3], Boolean.TRUE.equals(row[4]), (String) row[5],
                            List.copyOf(indexMembers.get(id)))));

            Map<Long, Object[]> relationHeads = new LinkedHashMap<>();
            Map<Long, List<Long[]>> pairs = new LinkedHashMap<>();
            for (Object[] row : entityManager.createQuery("select r.id, s.id, tg.id, r.relationName, r.relationSource, sc.id, tc.id from TableRelation r join r.sourceTable s"
                            + " join r.targetTable tg left join r.columns rc left join rc.sourceColumn sc left join rc.targetColumn tc where r.projectId = :projectId"
                            + " and r.relationType = :type and s.id in :ids order by r.id, rc.columnOrder", Object[].class)
                    .setParameter("projectId", projectId).setParameter("type", TableRelation.Type.FK).setParameter("ids", part).getResultList()) {
                relationHeads.putIfAbsent((Long) row[0], row);
                List<Long[]> list = pairs.computeIfAbsent((Long) row[0], k -> new ArrayList<>());
                if (row[5] != null) list.add(new Long[]{(Long) row[5], (Long) row[6]});
            }
            relationHeads.forEach((id, row) -> relations.computeIfAbsent((Long) row[1], k -> new ArrayList<>())
                    .add(new CompareModel.Relation(id, (Long) row[1], (Long) row[2], (String) row[3], String.valueOf(row[4]),
                            pairs.get(id).stream().map(p -> p[0]).toList(), pairs.get(id).stream().map(p -> p[1]).toList())));
        });

        // FK 대상 컬럼 (다른 TABLE 의 컬럼)
        Set<Long> missing = new LinkedHashSet<>();
        relations.values().forEach(list -> list.forEach(r -> r.targetColumnIds().stream().filter(id -> id != null && !columns.containsKey(id)).forEach(missing::add)));
        if (!missing.isEmpty()) {
            chunks(List.copyOf(missing), part -> {
                for (Object[] row : entityManager.createQuery("select c.id, t.id, c.columnName, c.dataType, c.maxLength, c.precision, c.scale, c.isNullable, c.isRequired,"
                                + " c.isPk, c.isUnique, c.defaultValue, c.sortOrder, c.sourceComment, c.isApproval from ColumnInfo c join c.tableInfo t"
                                + " where c.projectId = :projectId and c.id in :ids", Object[].class)
                        .setParameter("projectId", projectId).setParameter("ids", part).getResultList()) {
                    CompareModel.Column column = column(row);
                    columns.put(column.id(), column);
                }
            });
        }

        Map<Long, CompareModel.Table> tables = new LinkedHashMap<>();
        for (Long id : ids) {
            Object[] head = tableHeads.get(id);
            if (head == null) continue; // 없는 TABLE·다른 프로젝트
            tables.put(id, new CompareModel.Table(id, (String) head[1], (String) head[2], Boolean.TRUE.equals(head[3]), List.copyOf(columnsByTable.getOrDefault(id, List.of())),
                    List.copyOf(constraints.getOrDefault(id, List.of())), List.copyOf(indexes.getOrDefault(id, List.of())), List.copyOf(relations.getOrDefault(id, List.of()))));
        }
        return new Loaded(tables, columns);
    }

    /** 모델 스키마마다 VIEW 이름 (TABLE·VIEW 는 스키마 단위로 이름 공간을 나눠 쓴다, D-25) */
    public Map<Long, List<String>> viewNames(Long projectId) {
        Map<Long, List<String>> names = new HashMap<>();
        for (Object[] row : entityManager.createQuery("select s.id, v.viewName from PhysicalView v join v.schema s where v.projectId = :projectId", Object[].class)
                .setParameter("projectId", projectId).getResultList()) {
            names.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        return names;
    }

    /** @param columns 읽은 모든 컬럼 (FK 대상 컬럼 포함) */
    public record Loaded(Map<Long, CompareModel.Table> tables, Map<Long, CompareModel.Column> columns) {
    }

    private List<Object[]> list(String jpql, Long projectId, List<Long> ids) {
        return entityManager.createQuery(jpql, Object[].class).setParameter("projectId", projectId).setParameter("ids", ids).getResultList();
    }

    private static void chunks(List<Long> ids, Consumer<List<Long>> action) {
        for (int from = 0; from < ids.size(); from += IN_CHUNK) action.accept(ids.subList(from, Math.min(from + IN_CHUNK, ids.size())));
    }

    private static CompareModel.Column column(Object[] row) {
        return new CompareModel.Column((Long) row[0], (Long) row[1], (String) row[2], (String) row[3], (Integer) row[4], (BigDecimal) row[5], (Integer) row[6],
                (Boolean) row[7], (Boolean) row[8], (Boolean) row[9], (Boolean) row[10], (String) row[11], (Integer) row[12], (String) row[13], Boolean.TRUE.equals(row[14]));
    }
}
