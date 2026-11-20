package com.lexorma.data.exchange.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto;
import com.lexorma.data.exchange.domain.dto.ModelExchangeDto.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모델 교환 파일 검사의 크기 제한 (D-53, M-6 최종 정책) : 서브타입 그룹의 구성원 목록만 자식 목록 1,000행 제한에서 빼고, 대신 구성원 행을 전체 객체 수(50,000)에 더한다.
 * 그 밖의 자식 목록은 1,000행 그대로. 검사 계층만 보므로 DB 데이터를 만들지 않는다 (실제 왕복은 LogicalSubtypeIntegrationTest.exchangeLargeGroups)
 */
class ModelExchangeLimitsTest {

    private static final long MODEL = 1L;

    /** 논리 모델 하나 + 엔터티 n 개(번호 1000 + i) + 슈퍼타입 = 엔터티 1000, 구성원 = groups 의 크기만큼 차례로 */
    private static ModelExchangeDto.Document subtypeFile(int entities, int... groupSizes) {
        Map<String, List<Item>> model = new LinkedHashMap<>();
        model.put("LOGICAL_MODEL", List.of(new Item(MODEL, "모델", Map.of("modelName", "모델"))));
        List<Item> entityItems = new ArrayList<>();
        for (int i = 0; i < entities; i++) entityItems.add(new Item(1000L + i, "E" + i, Map.of("modelId", MODEL, "entityName", "E" + i)));
        model.put("LOGICAL_ENTITY", entityItems);
        List<Item> groups = new ArrayList<>();
        int next = 1;
        for (int g = 0; g < groupSizes.length; g++) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (int m = 0; m < groupSizes[g]; m++) members.add(Map.of("subtypeEntityId", 1000L + next++, "sortOrder", m + 1));
            Map<String, Object> state = new HashMap<>(Map.of("modelId", MODEL, "supertypeEntityId", 1000L, "groupName", "그룹 " + g, "overlapping", true, "total", false));
            state.put("subtypes", members);
            groups.add(new Item(9000L + g, "그룹 " + g, state));
        }
        model.put("SUBTYPE_GROUP", groups);
        return new ModelExchangeDto.Document(ModelExchangeDto.FORMAT, 5, null, null, null, model, null);
    }

    private static int objects(ModelExchangeDto.Document document) {
        int total = document.model().values().stream().mapToInt(List::size).sum();
        for (Item g : document.model().getOrDefault("SUBTYPE_GROUP", List.of())) total += ((List<?>) g.state().get("subtypes")).size();
        return total;
    }

    private static void rejected(ModelExchangeDto.Document document, String messagePart) {
        assertThatThrownBy(() -> ModelExchangeService.validate(document)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_EXCHANGE_INVALID);
            assertThat(e.getCustomMessage()).contains(messagePart);
        });
    }

    @Test
    @DisplayName("구성원 목록은 1,000행을 넘어도 된다 : 1,000·1,001·2,000·5,000개 그룹 통과, 1개 그룹은 최소 구성원으로 거절")
    void memberListNotLimitedTo1000() {
        for (int size : new int[]{2, 999, 1000, 1001, 2000, 5000}) {
            ModelExchangeDto.Document document = subtypeFile(size + 1, size);
            assertThatCode(() -> ModelExchangeService.validate(document)).as("구성원 " + size).doesNotThrowAnyException();
        }
        rejected(subtypeFile(2, 1), "개 이상이어야");
    }

    @Test
    @DisplayName("구성원 행은 전체 객체 수에 들어간다 : 여러 그룹에 나눠도 합계, 정확히 50,000 은 통과·50,001 은 거절, 구성원 검사(참조) 전에 센다")
    void membersCountTowardObjectLimit() {
        // 모델 1 + 엔터티 E + 그룹 2 + 구성원 (E - 1) = 2E + 2
        ModelExchangeDto.Document atLimit = subtypeFile(24_999, 12_000, 12_998);
        assertThat(objects(atLimit)).isEqualTo(ModelExchangeService.MAX_OBJECTS);
        assertThatCode(() -> ModelExchangeService.validate(atLimit)).doesNotThrowAnyException();

        // 한 행 더 : 구성원이 가리키는 엔터티가 없어도(참조 오류) 객체 수 초과가 먼저다 — 비싼 검사·DB 쓰기 전에 거절
        ModelExchangeDto.Document over = subtypeFile(24_999, 12_000, 12_999);
        assertThat(objects(over)).isEqualTo(ModelExchangeService.MAX_OBJECTS + 1);
        rejected(over, "객체가 너무 많습니다");
        // 그룹을 여럿으로 나눠도 같다 (그룹 1,000개 × 49)
        int[] split = new int[1_000];
        java.util.Arrays.fill(split, 49);
        rejected(subtypeFile(10, split), "객체가 너무 많습니다");
        // 구성원만 50,000 을 넘는 한 그룹
        rejected(subtypeFile(10, 60_000), "객체가 너무 많습니다");
    }

    @Test
    @DisplayName("그 밖의 자식 목록은 1,000행 그대로, 구성원 행 안에 목록을 넣는 중첩·중복 구성원은 거절")
    void otherChildListsStayLimited() {
        // 관계 속성 짝 1,001행 (예외는 서브타입 구성원만)
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < ModelExchangeService.MAX_CHILD_ROWS + 1; i++) pairs.add(Map.of("parentAttributeId", (long) i, "childAttributeId", (long) i));
        Map<String, List<Item>> model = new LinkedHashMap<>();
        model.put("LOGICAL_RELATIONSHIP", List.of(new Item(1L, "관계", new HashMap<>(Map.of("attributes", pairs)))));
        rejected(new ModelExchangeDto.Document(ModelExchangeDto.FORMAT, 5, null, null, null, model, null), "LOGICAL_RELATIONSHIP.attributes");
        // 다른 종류의 같은 이름 필드는 예외가 아니다
        Map<String, List<Item>> other = new LinkedHashMap<>();
        other.put("LOGICAL_RELATIONSHIP", List.of(new Item(1L, "관계", new HashMap<>(Map.of("subtypes", pairs)))));
        rejected(new ModelExchangeDto.Document(ModelExchangeDto.FORMAT, 5, null, null, null, other, null), "LOGICAL_RELATIONSHIP.subtypes");

        // 구성원 행 안의 목록 (중첩)
        ModelExchangeDto.Document nested = subtypeFile(1_200, 1_100);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) nested.model().get("SUBTYPE_GROUP").getFirst().state().get("subtypes");
        members.set(0, Map.of("subtypeEntityId", 1001L, "sortOrder", 1, "nested", List.of(Map.of("x", 1))));
        rejected(nested, "SUBTYPE_GROUP.subtypes");
        // 구성원 목록이 아닌 값
        ModelExchangeDto.Document notList = subtypeFile(3, 2);
        notList.model().get("SUBTYPE_GROUP").getFirst().state().put("subtypes", List.of("문자열"));
        rejected(notList, "SUBTYPE_GROUP.subtypes");
        // 같은 엔터티를 두 번 (단일 상속 위반으로 거절)
        ModelExchangeDto.Document duplicated = subtypeFile(1_200, 1_100);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) duplicated.model().get("SUBTYPE_GROUP").getFirst().state().get("subtypes");
        rows.set(1_099, Map.of("subtypeEntityId", 1001L, "sortOrder", 1_100));
        rejected(duplicated, "단일 상속");
    }

    @Test
    @DisplayName("D-17 : 파일의 식별자 구성 속성은 그 식별자 엔터티의 속성이어야 한다 — 서브타입 보조 식별자의 상속 속성·혼합·다른 엔터티 속성·없는 속성은 파일 전체 거절")
    void identifierAttributesBelongToEntity() {
        ModelExchangeDto.Document file = subtypeFile(3, 2); // 슈퍼타입 1000, 서브타입 1001·1002
        List<Item> attributes = new ArrayList<>(List.of(
                new Item(1L, "슈퍼 속성", Map.of("entityId", 1000L, "attributeName", "슈퍼 속성")),
                new Item(2L, "서브 속성", Map.of("entityId", 1001L, "attributeName", "서브 속성")),
                new Item(3L, "형제 속성", Map.of("entityId", 1002L, "attributeName", "형제 속성"))));
        file.model().put("LOGICAL_ATTRIBUTE", attributes);
        java.util.function.Function<List<Long>, ModelExchangeDto.Document> withAk = (ids) -> {
            List<Map<String, Object>> members = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) members.add(Map.of("attributeId", ids.get(i), "sortOrder", i + 1));
            Map<String, Object> state = new HashMap<>(Map.of("entityId", 1001L, "identifierName", "보조", "isPrimary", false));
            state.put("attributes", members);
            Map<String, List<Item>> model = new LinkedHashMap<>(file.model());
            model.put("LOGICAL_IDENTIFIER", List.of(new Item(50L, "보조", state)));
            return new ModelExchangeDto.Document(ModelExchangeDto.FORMAT, 5, null, null, null, model, null);
        };
        assertThatCode(() -> ModelExchangeService.validate(withAk.apply(List.of(2L)))).as("직접 속성").doesNotThrowAnyException();
        rejected(withAk.apply(List.of(1L)), "같은 엔터티의 속성");      // 상속 속성
        rejected(withAk.apply(List.of(2L, 1L)), "같은 엔터티의 속성");  // 직접 + 상속
        rejected(withAk.apply(List.of(3L)), "같은 엔터티의 속성");      // 형제 엔터티 속성
        rejected(withAk.apply(List.of(99L)), "같은 엔터티의 속성");     // 파일에 없는 속성
    }
}
