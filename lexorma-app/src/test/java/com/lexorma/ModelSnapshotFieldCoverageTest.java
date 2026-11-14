package com.lexorma;

import com.lexorma.data.history.record.ModelChangeRecorder;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 변경 이력·모델 버전에 담는 필드가 엔티티의 영속 속성을 빠짐없이 다루는지 확인한다 (M-9).
 * 엔티티에 속성을 더하면 이 테스트가 실패한다 → ModelChangeRecorder 의 필드 목록에 넣거나(이력과 버전에 담긴다),
 * 담지 않을 이유가 있으면 아래 EXCLUDED 에 사유와 함께 적는다. 어느 쪽에도 없는 속성은 허용하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ModelSnapshotFieldCoverageTest {

    private static final String ID = "내부 ID : 객체의 id 로 따로 담는다";
    private static final String PROJECT = "프로젝트 : 버전·이력 자체가 프로젝트 단위다";
    private static final String PARENT = "부모 : 이 행은 부모 객체의 배열(columns·attributes)로 담긴다";
    private static final String INVERSE = "반대쪽 연관(mappedBy) : 자식 객체가 자기 쪽에서 담는다";

    /** 모든 대상에서 빼는 속성 : 모델 구조가 아니라 운영 정보다 (BaseEntity 감사 컬럼) */
    private static final Map<String, String> AUDIT = Map.of(
            "created", "등록 일시", "updated", "수정 일시", "createdBy", "등록자", "updatedBy", "수정자");

    /** 엔티티별로 담지 않는 속성 → 사유. 여기 적은 속성이 엔티티에 없거나 필드 목록에도 있으면 실패한다 (낡은 예외를 남기지 않는다) */
    private static final Map<String, Map<String, String>> EXCLUDED = Map.ofEntries(
            Map.entry("SchemaInfo", Map.of("id", ID, "projectId", PROJECT)),
            Map.entry("TableInfo", Map.of("id", ID, "projectId", PROJECT, "columns", INVERSE)),
            Map.entry("ColumnInfo", Map.of("id", ID, "projectId", PROJECT)),
            Map.entry("TableRelation", Map.of("id", ID, "projectId", PROJECT, "columns", INVERSE)),
            Map.entry("TableRelationColumn", Map.of("id", ID, "relation", PARENT)),
            Map.entry("TableIndex", Map.of("id", ID, "projectId", PROJECT, "columns", INVERSE)),
            Map.entry("TableIndexColumn", Map.of("id", ID, "tableIndex", PARENT)),
            Map.entry("TableConstraint", Map.of("id", ID, "projectId", PROJECT, "columns", INVERSE)),
            Map.entry("TableConstraintColumn", Map.of("id", ID, "tableConstraint", PARENT)),
            Map.entry("PhysicalView", Map.of("id", ID, "projectId", PROJECT, "columns", INVERSE, "references", INVERSE)),
            Map.entry("PhysicalViewColumn", Map.of("id", ID, "view", PARENT)),
            Map.entry("PhysicalViewReference", Map.of("id", ID, "view", PARENT)),
            Map.entry("LogicalModel", Map.of("id", ID, "projectId", PROJECT)),
            Map.entry("LogicalEntity", Map.of("id", ID, "projectId", PROJECT, "attributes", INVERSE, "identifiers", INVERSE)),
            Map.entry("LogicalAttribute", Map.of("id", ID)),
            Map.entry("LogicalIdentifier", Map.of("id", ID, "attributes", INVERSE)),
            Map.entry("LogicalIdentifierAttribute", Map.of("id", ID, "identifier", PARENT)),
            Map.entry("LogicalRelationship", Map.of("id", ID, "projectId", PROJECT)),
            Map.entry("EntityTableMapping", Map.of("id", ID, "projectId", PROJECT)),
            Map.entry("AttributeColumnMapping", Map.of("id", ID)));

    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("이력·버전 대상 엔티티의 모든 영속 속성은 담는 필드이거나, 사유를 적은 제외 속성이다")
    void everyPersistentAttributeIsRecordedOrExcludedWithReason() {
        Map<Class<?>, List<String>> recorded = ModelChangeRecorder.recordedFields();
        // 스키마 1 + 이력 대상 13 (VIEW 포함) + 자식 행 6 (VIEW 의 출력 컬럼·참조 객체 포함)
        assertThat(recorded).hasSize(20);
        assertThat(recorded.keySet()).extracting(Class::getSimpleName).containsExactlyInAnyOrderElementsOf(EXCLUDED.keySet());

        Map<String, Set<String>> missing = new TreeMap<>();   // 엔티티에는 있는데 어디에도 없는 속성
        Map<String, Set<String>> stale = new TreeMap<>();     // 목록·예외에는 있는데 엔티티에 없거나 양쪽에 다 있는 속성
        recorded.forEach((entityClass, fields) -> {
            String name = entityClass.getSimpleName();
            Set<String> attributes = entityManagerFactory.getMetamodel().entity(entityClass).getAttributes().stream().map(Attribute::getName).collect(Collectors.toCollection(TreeSet::new));
            Map<String, String> excluded = EXCLUDED.get(name);
            for (String attribute : attributes) {
                if (!fields.contains(attribute) && !excluded.containsKey(attribute) && !AUDIT.containsKey(attribute)) missing.computeIfAbsent(name, key -> new TreeSet<>()).add(attribute);
            }
            for (String field : fields) {
                if (!attributes.contains(field)) stale.computeIfAbsent(name, key -> new TreeSet<>()).add(field + " (담는 필드인데 엔티티에 없음)");
            }
            excluded.forEach((attribute, reason) -> {
                assertThat(reason).as("%s.%s 의 제외 사유", name, attribute).isNotBlank();
                if (!attributes.contains(attribute)) stale.computeIfAbsent(name, key -> new TreeSet<>()).add(attribute + " (제외 속성인데 엔티티에 없음)");
                if (fields.contains(attribute)) stale.computeIfAbsent(name, key -> new TreeSet<>()).add(attribute + " (담는 필드이면서 제외 속성)");
            });
        });

        assertThat(missing).as("ModelChangeRecorder 의 필드 목록에 넣거나, 담지 않을 사유를 EXCLUDED 에 적어야 하는 속성").isEmpty();
        assertThat(stale).as("엔티티와 맞지 않는 필드 목록·제외 속성").isEmpty();
    }
}
