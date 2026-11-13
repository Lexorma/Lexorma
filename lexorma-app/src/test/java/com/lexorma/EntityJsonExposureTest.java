package com.lexorma;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.physical.domain.entity.IndexChangeRequest;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-14 안정화 : 엔티티(BaseEntity 상속)가 API 응답 JSON 으로 나가지 않고, 감사 컬럼이 엔티티의 JSON 프로퍼티가 되지 않는다.
 * BaseEntity 의 감사 접근자는 protected 이고, 화면에 필요한 등록·수정 정보는 DTO 로만 옮긴다.
 */
class EntityJsonExposureTest {

    private static final Set<String> AUDIT = Set.of("created", "updated", "createdBy", "updatedBy", "audit");

    @Test
    @DisplayName("REST 핸들러의 반환 타입(중첩 DTO·레코드·컬렉션·Map 값 포함)에 엔티티가 없다")
    void noEntityInResponses() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> found = new TreeSet<>();
        int handlers = 0;
        for (var bean : scanner.findCandidateComponents("com.lexorma")) {
            Class<?> controller = ClassUtils.forName(bean.getBeanClassName(), getClass().getClassLoader());
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                handlers++;
                walk(method.getGenericReturnType(), new HashSet<>(), controller.getSimpleName() + "." + method.getName(), found);
            }
        }
        assertThat(handlers).isGreaterThan(100);
        assertThat(found).as("엔티티가 응답에 들어가는 경로").isEmpty();
    }

    @Test
    @DisplayName("엔티티를 직렬화해도 감사 컬럼은 프로퍼티가 아니다 (TableIndex·IndexChangeRequest·SchemaInfo)")
    void auditIsNotAProperty() {
        JsonMapper json = JsonMapper.builder().build();
        for (BaseEntity entity : new BaseEntity[]{TableIndex.builder().indexName("IX_A").isUnique(false).build(),
                IndexChangeRequest.builder().status(IndexChangeRequest.Status.REQUESTED).build(),
                SchemaInfo.builder().schemaName("S").build()}) {
            ReflectionTestUtils.setField(entity, "created", LocalDateTime.of(2026, 11, 13, 0, 0));
            ReflectionTestUtils.setField(entity, "createdBy", "admin");
            Set<String> keys = new HashSet<>();
            json.readTree(json.writeValueAsString(entity)).propertyNames().forEach(keys::add);
            assertThat(keys).as(entity.getClass().getSimpleName()).doesNotContainAnyElementsOf(AUDIT).isNotEmpty();
        }
        // DTO 로 옮기는 경로는 그대로 동작한다
        TableIndex index = TableIndex.builder().build();
        ReflectionTestUtils.setField(index, "createdBy", "admin");
        assertThat(index.audit().createdBy()).isEqualTo("admin");
    }

    /** 응답 타입 안의 클래스를 따라간다 : 제네릭 인자, 배열, 레코드 구성 요소·필드 (com.lexorma 타입만 펼친다) */
    private static void walk(Type type, Set<Type> seen, String path, Set<String> found) {
        if (type == null || !seen.add(type)) return;
        if (type instanceof ParameterizedType p) {
            walk(p.getRawType(), seen, path, found);
            for (Type arg : p.getActualTypeArguments()) walk(arg, seen, path, found);
        } else if (type instanceof GenericArrayType a) {
            walk(a.getGenericComponentType(), seen, path, found);
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) walk(bound, seen, path, found);
        } else if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walk(c.getComponentType(), seen, path, found);
            } else if (BaseEntity.class.isAssignableFrom(c)) {
                found.add(path + " → " + c.getSimpleName());
            } else if (c.getName().startsWith("com.lexorma.") && !c.isEnum()) {
                for (Field field : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) walk(field.getGenericType(), seen, path + "." + field.getName(), found);
                }
            }
        }
    }
}
