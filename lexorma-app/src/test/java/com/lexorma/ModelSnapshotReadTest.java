package com.lexorma;

import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.history.record.ModelChangeRecorder;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableConstraintColumn;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableIndexColumn;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.domain.entity.TableRelationColumn;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewColumn;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델 스냅샷 읽기 (모델 버전·가져오기 직전 버전의 내용) : 엔티티를 올리지 않는 스칼라 조회가 이력과 같은 규칙의 값을 만든다.
 * 2026-11-14 최적화 때 엔티티로 읽던 이전 구현과 JSON 이 같음을 A/B 로 확인했고(예제 프로젝트 + 아래 객체, H2·PostgreSQL), 여기서는 그 값을 고정한다.
 * 이전 구현은 영속성 컨텍스트에 초기화되지 않은 프록시가 있으면 그 객체의 값을 null 로 읽었다 : 프록시가 있는 상태로 읽어 회귀를 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelSnapshotReadTest {

    @Autowired ModelChangeRecorder recorder;
    @PersistenceContext EntityManager em;

    @Test
    @Transactional
    @DisplayName("스냅샷 읽기 : 연관은 ID·이름, BigDecimal 정규화, enum 이름, null FK 도 키가 있다, 자식 행(인덱스 식·정렬·INCLUDE, 제약조건·관계 컬럼, VIEW 출력·참조)은 순서대로, 프록시가 있어도 값을 읽는다")
    void values() {
        List<TableInfo> tables = em.createQuery("select t from TableInfo t where t.projectId = 0 order by t.id", TableInfo.class).setMaxResults(2).getResultList();
        TableInfo a = tables.get(0);
        TableInfo b = tables.get(1);
        List<ColumnInfo> columns = new ArrayList<>(a.getColumns());
        ColumnInfo c1 = columns.get(0);
        ColumnInfo c2 = columns.get(1);
        c2.setPrecision(new BigDecimal("10.00"));
        c2.setSourceComment("원본");
        c2.setStandardTerms(null);
        TableConstraint unique = TableConstraint.builder().projectId(0L).tableInfo(a).constraintName("UX_SNAP_TEST").constraintType(TableConstraint.Type.UNIQUE)
                .isApproval(false).columns(new ArrayList<>()).build();
        unique.getColumns().add(TableConstraintColumn.builder().tableConstraint(unique).column(c2).columnOrder(2).build());
        unique.getColumns().add(TableConstraintColumn.builder().tableConstraint(unique).column(c1).columnOrder(1).build());
        em.persist(unique);
        TableIndex index = TableIndex.builder().projectId(0L).tableInfo(a).indexName("IX_SNAP_TEST").isUnique(true).condition("A > 0")
                .indexSource(TableIndex.Source.USER).isApproval(false).columns(new ArrayList<>()).build();
        index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(c1).sort("DESC NULLS LAST").columnOrder(1).build());
        index.getColumns().add(TableIndexColumn.builder().tableIndex(index).expression("lower(x)").columnOrder(2).build());
        index.getColumns().add(TableIndexColumn.builder().tableIndex(index).column(c2).sort("INCLUDE").columnOrder(3).build());
        em.persist(index);
        TableRelation relation = TableRelation.builder().projectId(0L).sourceTable(a).targetTable(b).relationName("FK_SNAP_TEST").relationType(TableRelation.Type.FK)
                .relationSource(TableRelation.Source.IMPORTED).isApproval(false).columns(new ArrayList<>()).build();
        relation.getColumns().add(TableRelationColumn.builder().relation(relation).sourceColumn(c1).targetColumn(b.getColumns().get(0)).columnOrder(1).build());
        em.persist(relation);
        PhysicalView view = PhysicalView.builder().projectId(0L).schema(a.getSchema()).viewName("V_SNAP_TEST").viewKorName("뷰").definition("select 1").isApproval(false).build();
        view.getColumns().add(PhysicalViewColumn.builder().view(view).columnName("X").columnOrder(1).build());
        view.getReferences().add(PhysicalViewReference.builder().view(view).referenceType(PhysicalViewReference.Type.TABLE).referencedTable(a).objectName(a.getTableName()).build());
        em.persist(view);
        em.flush(); // 영속성 컨텍스트는 그대로 둔다 (스키마는 a.getSchema() 의 프록시로 올라와 있다)

        Map<String, List<ModelChangeRecorder.ObjectState>> state = recorder.currentState(0L);
        Map<String, Object> schema = state.get(ModelChangeRecorder.SCHEMA).getFirst().state();
        assertThat(schema).containsEntry("schemaName", "DEFAULT").containsEntry("isDefault", true);
        Map<String, Object> column = find(state, "PHYSICAL_COLUMN", c2.getId());
        assertThat(column.get("precision")).isEqualTo(new BigDecimal("10"));
        assertThat(column).containsEntry("sourceComment", "원본").containsEntry("standardTermsId", null).containsEntry("standardTermsName", null)
                .containsEntry("tableInfoId", a.getId()).containsEntry("tableInfoName", a.getTableName());
        assertThat(find(state, "PHYSICAL_TABLE", a.getId())).containsEntry("schemaName", "DEFAULT");
        Map<String, Object> idx = find(state, "PHYSICAL_INDEX", index.getId());
        assertThat(idx).containsEntry("indexSource", "USER").containsEntry("condition", "A > 0");
        assertThat(members(idx, "columns")).extracting(m -> m.get("columnId") + "|" + m.get("expression") + "|" + m.get("sort") + "|" + m.get("columnOrder"))
                .containsExactly(c1.getId() + "|null|DESC NULLS LAST|1", "null|lower(x)|null|2", c2.getId() + "|null|INCLUDE|3");
        assertThat(members(find(state, "PHYSICAL_CONSTRAINT", unique.getId()), "columns")).extracting(m -> m.get("columnId")).containsExactly(c1.getId(), c2.getId());
        Map<String, Object> rel = find(state, "PHYSICAL_RELATION", relation.getId());
        assertThat(rel).containsEntry("relationSource", "IMPORTED").containsEntry("targetTableName", b.getTableName());
        assertThat(members(rel, "columns")).extracting(m -> m.get("sourceColumnName") + "→" + m.get("targetColumnName"))
                .containsExactly(c1.getColumnName() + "→" + b.getColumns().get(0).getColumnName());
        Map<String, Object> v = find(state, "PHYSICAL_VIEW", view.getId());
        assertThat(members(v, "columns")).extracting(m -> m.get("columnName")).containsExactly("X");
        assertThat(members(v, "references")).extracting(m -> m.get("referenceType") + "|" + m.get("referencedTableName") + "|" + m.get("referencedViewId"))
                .containsExactly("TABLE|" + a.getTableName() + "|null");
        // 이름 : 컬럼은 TABLE.컬럼
        assertThat(state.get("PHYSICAL_COLUMN").stream().filter(o -> o.id().equals(c2.getId())).findFirst().orElseThrow().name())
                .isEqualTo(a.getTableName() + "." + c2.getColumnName());
    }

    private static Map<String, Object> find(Map<String, List<ModelChangeRecorder.ObjectState>> state, String type, Long id) {
        return state.get(type).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow().state();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> members(Map<String, Object> state, String key) {
        return (List<Map<String, Object>>) state.get(key);
    }
}
