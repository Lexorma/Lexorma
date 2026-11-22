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

    /**
     * 상속 PK 매핑을 담은 형식 6 파일 (M-7 5차) : 모델 1 + 루트 엔터티 1 + 루트 속성 1 + 주 식별자 1 + 그룹 1 + 구성원 행 n + 서브타입 엔터티 n + TABLE n + 컬럼 n + 엔터티 매핑 n
     * + 상속 PK 매핑 n (같은 루트 속성을 n 개 서브타입이 각자 다른 컬럼으로) + 빈 엔터티 filler = 5 + 6n + filler. keys false 면 상속 PK 매핑을 뺀다
     */
    private static ModelExchangeDto.Document subtypeKeyFile(int subtypes, int filler, boolean keys) {
        Map<String, List<Item>> model = new LinkedHashMap<>();
        model.put("LOGICAL_MODEL", List.of(new Item(MODEL, "모델", Map.of("modelName", "모델"))));
        List<Item> entities = new ArrayList<>();
        entities.add(new Item(1000L, "루트", Map.of("modelId", MODEL, "entityName", "루트")));
        List<Item> tables = new ArrayList<>();
        List<Item> columns = new ArrayList<>();
        List<Item> mappings = new ArrayList<>();
        List<Item> keyItems = new ArrayList<>();
        List<Map<String, Object>> members = new ArrayList<>();
        for (int i = 0; i < subtypes; i++) {
            long entity = 1001L + i;
            entities.add(new Item(entity, "S" + i, Map.of("modelId", MODEL, "entityName", "S" + i)));
            members.add(Map.of("subtypeEntityId", entity, "sortOrder", i + 1));
            tables.add(new Item(200_000L + i, "T" + i, Map.of("tableName", "T" + i)));
            columns.add(new Item(300_000L + i, "K", Map.of("tableInfoId", 200_000L + i, "columnName", "K")));
            mappings.add(new Item(400_000L + i, "M" + i, Map.of("entityId", entity, "tableId", 200_000L + i)));
            keyItems.add(new Item(500_000L + i, "K" + i, Map.of("mappingId", 400_000L + i, "attributeId", 1L, "columnId", 300_000L + i)));
        }
        for (int i = 0; i < filler; i++) entities.add(new Item(900_000L + i, "F" + i, Map.of("modelId", MODEL, "entityName", "F" + i)));
        model.put("LOGICAL_ENTITY", entities);
        model.put("LOGICAL_ATTRIBUTE", List.of(new Item(1L, "루트 번호", Map.of("entityId", 1000L, "attributeName", "루트 번호"))));
        Map<String, Object> identifier = new HashMap<>(Map.of("entityId", 1000L, "identifierName", "주", "isPrimary", true));
        identifier.put("attributes", List.of(Map.of("attributeId", 1L, "sortOrder", 1)));
        model.put("LOGICAL_IDENTIFIER", List.of(new Item(2L, "주", identifier)));
        Map<String, Object> group = new HashMap<>(Map.of("modelId", MODEL, "supertypeEntityId", 1000L, "groupName", "그룹", "overlapping", true, "total", false));
        group.put("subtypes", members);
        model.put("SUBTYPE_GROUP", List.of(new Item(3L, "그룹", group)));
        model.put("PHYSICAL_TABLE", tables);
        model.put("PHYSICAL_COLUMN", columns);
        model.put("TABLE_MAPPING", mappings);
        if (keys) model.put("SUBTYPE_KEY_MAPPING", keyItems);
        return new ModelExchangeDto.Document(ModelExchangeDto.FORMAT, 6, null, null, null, model, null);
    }

    @Test
    @DisplayName("상속 PK 매핑도 객체 수(50,000)에 들어간다 (M-7 5차) : 종류별 수의 합 + 그룹 구성원 행 = 전체. 49,999·50,000 통과, 50,001 거절, 상속 PK 매핑을 빼면 이내인 파일도 넣으면 초과해 거절. "
            + "같은 루트 속성을 8,000개 넘는 서브타입이 각자 쓴다")
    void subtypeKeysCountTowardObjectLimit() {
        int n = 8_332; // 5 + 6n = 49,997
        for (int[] c : new int[][]{{2, 49_999}, {3, 50_000}}) {
            ModelExchangeDto.Document file = subtypeKeyFile(n, c[0], true);
            assertThat(objects(file)).isEqualTo(c[1]);
            assertThatCode(() -> ModelExchangeService.validate(file)).as(c[1] + " 개").doesNotThrowAnyException();
        }
        ModelExchangeDto.Document over = subtypeKeyFile(n, 4, true);
        assertThat(objects(over)).isEqualTo(ModelExchangeService.MAX_OBJECTS + 1);
        rejected(over, "객체가 너무 많습니다");
        // 상속 PK 매핑을 빼면 이내 (41,669) — 넣으면 초과 (위 over)
        ModelExchangeDto.Document withoutKeys = subtypeKeyFile(n, 4, false);
        assertThat(objects(withoutKeys)).isEqualTo(ModelExchangeService.MAX_OBJECTS + 1 - n);
        assertThatCode(() -> ModelExchangeService.validate(withoutKeys)).doesNotThrowAnyException();
        // 객체 수 초과는 참조 검사보다 먼저 (상속 PK 매핑이 없는 컬럼을 가리켜도 수로 먼저 거절)
        ModelExchangeDto.Document overBroken = subtypeKeyFile(n, 4, true);
        overBroken.model().put("SUBTYPE_KEY_MAPPING", overBroken.model().get("SUBTYPE_KEY_MAPPING").stream()
                .map(k -> new Item(k.id(), k.name(), Map.of("mappingId", 400_000L, "attributeId", 1L, "columnId", 1L))).toList());
        rejected(overBroken, "객체가 너무 많습니다");
    }
}
