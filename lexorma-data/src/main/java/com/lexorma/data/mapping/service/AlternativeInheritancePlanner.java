package com.lexorma.data.mapping.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.service.ColumnNameKey;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifier;
import com.lexorma.data.logical.domain.entity.LogicalSubtype;
import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.data.logical.service.LogicalRelationshipPairs;
import com.lexorma.data.logical.service.ReferenceKeys;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.domain.entity.SubtypeGroupConversion;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.table.ddl.DbIdentifierRules;
import com.lexorma.data.table.domain.dto.TableNameDto;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 상속 A형(SINGLE_TABLE)·C형(TABLE_PER_CONCRETE) 생성 계획 (M-7 상속 A·C, docs/completed/inheritance-strategy-ac-m7.md — D-AC-01~17).
 * {@link PhysicalGenerationService} 가 요청 단위로 하나 만들어 쓴다 (저장하지 않는다 — 계획만, 반영은 생성기의 공통 적용 단계).
 * <ul>
 *   <li>단위 = 독립 루트 상속 계층 (D-AC-03·11). 계층 전체(루트·중간·말단)를 같은 요청에 골라야 하고, 계층 전체가 미매핑이어야 한다 (D-AC-12)</li>
 *   <li>A형 : 루트 테이블 하나, 계층 구성원의 직접 속성을 한 번씩 (논리 속성 ID 기준), 루트 PK, 구성원 자체 AK → UNIQUE, 루트 외 필수 속성은 NULL 허용(경고),
 *       분류 속성·값 필수(Disjoint 만), 상속 FK 없음</li>
 *   <li>C형 : 말단마다 테이블 = 루트 PK(상속 PK 매핑) + 조상 일반 속성 복제(서브타입 속성 컬럼 매핑) + 자체 속성, 논리 필수 여부 그대로, 계층 AK 전체 → 말단 UNIQUE,
 *       모든 그룹 Disjoint·Total, 루트·중간 테이블 없음, 상속 FK 없음</li>
 * </ul>
 * 이름 충돌은 오류다 — 접미사·이름 변경·속성 병합·이름 기준 매핑으로 자동 해결하지 않는다 (D-AC-04).
 */
final class AlternativeInheritancePlanner {

    static final String TABLE_PER_TYPE = "TABLE_PER_TYPE";
    static final String SINGLE_TABLE = SubtypeGroupConversion.Strategy.SINGLE_TABLE.name();
    static final String TABLE_PER_CONCRETE = SubtypeGroupConversion.Strategy.TABLE_PER_CONCRETE.name();

    private static final Set<String> CHARACTER = Set.of("VARCHAR", "VARCHAR2", "CHAR", "NCHAR", "NVARCHAR", "NVARCHAR2", "CHARACTER", "CHARACTER VARYING", "TEXT");
    private static final Map<String, BigInteger[]> INTEGER = Map.of(
            "TINYINT", new BigInteger[]{BigInteger.valueOf(-128), BigInteger.valueOf(127)},
            "SMALLINT", new BigInteger[]{BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE)},
            "INTEGER", new BigInteger[]{BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)},
            "INT", new BigInteger[]{BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)},
            "BIGINT", new BigInteger[]{BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)});
    private static final Set<String> DECIMAL = Set.of("NUMBER", "NUMERIC", "DECIMAL");
    private static final Pattern NUMBER_TEXT = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    /** 계층 하나 : 루트, 전략, 계층 전체(루트 + 후손, 너비 우선), 말단 */
    record Tree(Long rootId, String strategy, List<Long> members, List<Long> leaves) {
        boolean single() {
            return SINGLE_TABLE.equals(strategy);
        }
    }

    /** 계층의 계획 결과 */
    record TreePlan(Tree tree, Map<Long, PhysicalGenerationDto.TablePlan> tables, List<PhysicalGenerationDto.UniquePlan> uniques,
                    List<PhysicalGenerationDto.ConversionPlan> conversions, List<PhysicalGenerationDto.ConstraintWarning> warnings, List<String> errors,
                    List<String> warningCodes, List<PhysicalGenerationDto.EntityRef> missing) {
    }

    private final LogicalHierarchy hierarchy;
    private final DbType db;
    private final SchemaInfo schema;
    private final Function<Long, LogicalEntity> entityOf;
    private final Map<Long, LogicalSubtypeGroup> groups;
    private final Map<Long, List<LogicalSubtype>> members;
    private final List<Tree> trees;
    private final Map<Long, Tree> treeOf = new HashMap<>();

    private AlternativeInheritancePlanner(LogicalHierarchy hierarchy, DbType db, SchemaInfo schema, Function<Long, LogicalEntity> entityOf,
                                          Map<Long, LogicalSubtypeGroup> groups, Map<Long, List<LogicalSubtype>> members, List<Tree> trees) {
        this.hierarchy = hierarchy;
        this.db = db;
        this.schema = schema;
        this.entityOf = entityOf;
        this.groups = groups;
        this.members = members;
        this.trees = trees;
        trees.forEach(t -> t.members().forEach(id -> treeOf.put(id, t)));
    }

    /**
     * 요청의 전략 해석 (D-AC-15). 생략·TABLE_PER_TYPE 은 B형(기존 계약). 루트가 아닌 엔터티·일반 엔터티·고르지 않은 엔터티·같은 루트 두 번·모르는 값은 400.
     * 반환 : 루트 ID → A·C 전략 (B형은 넣지 않는다)
     */
    static Map<Long, String> requested(List<PhysicalGenerationDto.StrategyChoice> choices, Set<Long> chosenIds, LogicalHierarchy hierarchy) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (choices == null) return result;
        Set<Long> seen = new HashSet<>();
        for (PhysicalGenerationDto.StrategyChoice choice : choices) {
            if (choice == null || choice.rootEntityId() == null) throw PhysicalGenerationService.invalid("전략을 고른 상속 계층의 루트 엔터티가 비어 있습니다.");
            String strategy = choice.strategy() == null ? TABLE_PER_TYPE : choice.strategy().trim().toUpperCase(Locale.ROOT);
            if (!Set.of(TABLE_PER_TYPE, SINGLE_TABLE, TABLE_PER_CONCRETE).contains(strategy)) {
                throw PhysicalGenerationService.invalid("지원하지 않는 상속 변환 전략입니다 : {0}", choice.strategy());
            }
            Long root = choice.rootEntityId();
            if (!seen.add(root)) throw PhysicalGenerationService.invalid("같은 상속 계층의 전략을 두 번 지정했습니다. (엔터티 ID {0})", root);
            if (!chosenIds.contains(root)) throw PhysicalGenerationService.invalid("전략을 고른 루트 엔터티를 생성 대상에서 찾을 수 없습니다. (엔터티 ID {0})", root);
            if (!hierarchy.participates(root) || hierarchy.parent(root) != null || hierarchy.groupsOf(root).isEmpty()) {
                throw PhysicalGenerationService.invalid("상속 변환 전략은 상속 계층의 루트 엔터티에만 지정할 수 있습니다. (엔터티 ID {0})", root);
            }
            if (!TABLE_PER_TYPE.equals(strategy)) result.put(root, strategy);
        }
        return result;
    }

    static AlternativeInheritancePlanner create(Map<Long, String> requested, LogicalHierarchy hierarchy, DbType db, SchemaInfo schema, Function<Long, LogicalEntity> entityOf,
                                                Map<Long, LogicalSubtypeGroup> groups, Map<Long, List<LogicalSubtype>> members) {
        List<Tree> trees = new ArrayList<>();
        requested.forEach((root, strategy) -> {
            List<Long> tree = hierarchy.subtree(root);
            List<Long> leaves = tree.stream().filter(id -> hierarchy.groupsOf(id).isEmpty() && !id.equals(root)).toList();
            trees.add(new Tree(root, strategy, tree, leaves));
        });
        return new AlternativeInheritancePlanner(hierarchy, db, schema, entityOf, groups, members, trees);
    }

    /** 계획에 쓸 그룹 ID (요청 계층의 그룹 전체) */
    static Set<Long> groupIds(Map<Long, String> requested, LogicalHierarchy hierarchy) {
        Set<Long> ids = new TreeSet<>();
        requested.keySet().forEach(root -> hierarchy.subtree(root).forEach(id -> hierarchy.groupsOf(id).forEach(g -> ids.add(g.id()))));
        return ids;
    }

    List<Tree> trees() {
        return trees;
    }

    boolean isEmpty() {
        return trees.isEmpty();
    }

    /** 요청 A·C 계층의 구성원인가 */
    boolean member(Long entityId) {
        return treeOf.containsKey(entityId);
    }

    Tree tree(Long entityId) {
        return treeOf.get(entityId);
    }

    /**
     * 엔터티가 놓이는 물리 테이블의 엔터티 문맥 : A형 구성원 → 루트, C형 말단 → 자기, C형 루트·중간 → null (테이블 없음). 요청 계층이 아니면 자기 자신
     */
    Long tableEntity(Long entityId) {
        Tree tree = treeOf.get(entityId);
        if (tree == null) return entityId;
        if (tree.single()) return tree.rootId();
        return tree.leaves().contains(entityId) ? entityId : null;
    }

    /** 엔터티가 자식인 관계의 FK 를 만들 자식 문맥 (C형 루트·중간 → 그 아래 말단마다, D-AC-17) */
    List<Long> childContexts(Long entityId) {
        Tree tree = treeOf.get(entityId);
        if (tree == null || tree.single() || tree.leaves().contains(entityId)) return List.of(entityId);
        return hierarchy.subtree(entityId).stream().filter(tree.leaves()::contains).toList();
    }

    // ------------------------------------------------------------------ 이름

    /**
     * 계층의 테이블명 (D-AC-04 : 표준 단어 규칙 그대로 — 자동 접미사 없음). A형은 루트 엔터티명 하나를 계층 구성원 모두에, C형은 말단마다. 만들 수 없으면 null
     */
    void names(Function<String, TableNameDto> generate, Map<Long, String> tableNameByEntity, Map<Long, TableNameDto> generated) {
        for (Tree tree : trees) {
            if (tree.single()) {
                TableNameDto name = generate.apply(entityOf.apply(tree.rootId()).getEntityName());
                generated.put(tree.rootId(), name);
                if (name.valid()) tree.members().forEach(id -> tableNameByEntity.put(id, name.tableName().toUpperCase(Locale.ROOT)));
            } else {
                for (Long leaf : tree.leaves()) {
                    TableNameDto name = generate.apply(entityOf.apply(leaf).getEntityName());
                    generated.put(leaf, name);
                    if (name.valid()) tableNameByEntity.put(leaf, name.tableName().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    // ------------------------------------------------------------------ 관계 문맥 (D-AC-09·10·17)

    /**
     * 부모 엔터티를 참조하는 FK 를 전략상 만들 수 없는 사유 (만들 수 있으면 null). kindOfExisting : 요청 계층이 아닌 엔터티의 기록된 전략.
     * A형 루트가 아닌 엔터티 → SUBTYPE_SCOPE_NOT_ENFORCED (통합 테이블 FK 는 다른 유형 행도 참조할 수 있다), C형 루트·중간 → SUPERTYPE_DISTRIBUTED, 기록이 맞지 않음 → STRATEGY_STRUCTURE_MISMATCH
     */
    String parentExclusion(Long parentId, InheritanceConversions existing) {
        Tree tree = treeOf.get(parentId);
        if (tree != null) {
            if (tree.single()) return tree.rootId().equals(parentId) ? null : "SUBTYPE_SCOPE_NOT_ENFORCED";
            return tree.leaves().contains(parentId) ? null : "SUPERTYPE_DISTRIBUTED";
        }
        return exclusion(parentId, existing);
    }

    static String exclusion(Long parentId, InheritanceConversions existing) {
        return switch (existing.role(parentId)) {
            case MERGED -> "SUBTYPE_SCOPE_NOT_ENFORCED";
            case DISTRIBUTED -> "SUPERTYPE_DISTRIBUTED";
            case INCONSISTENT -> "STRATEGY_STRUCTURE_MISMATCH";
            case DIRECT -> null;
        };
    }

    // ------------------------------------------------------------------ 계층 계획

    /**
     * 계층마다 테이블·컬럼·UNIQUE·변환 기록 계획. chosenIds : 고른 엔터티, excludedRequested : 제외 요청, fkChildAttributes : 만들 FK(READY)의 자식 속성 (문맥 엔터티 → 속성),
     * refTables : 단일 FK 의 참조 테이블명 (문맥 엔터티:속성 → 테이블명), existingUse : 계층 엔터티의 기존 매핑·속성 매핑·기록 여부 (D-AC-12), nameError : 테이블명 오류 판정
     */
    List<TreePlan> plan(Set<Long> chosenIds, Set<Long> excludedRequested, Map<Long, Set<Long>> fkChildAttributes, Map<String, String> refTables,
                        Map<Long, String> tableNameByEntity, Map<Long, TableNameDto> generated, Function<Long, List<String>> existingUse,
                        Function<String, String> nameConflict, Map<String, Integer> nameUse, StringBuilder canonical) {
        List<TreePlan> result = new ArrayList<>();
        for (Tree tree : trees) result.add(tree.single() ? single(tree, chosenIds, excludedRequested, fkChildAttributes, refTables, tableNameByEntity, generated,
                existingUse, nameConflict, nameUse, canonical)
                : concrete(tree, chosenIds, excludedRequested, fkChildAttributes, refTables, tableNameByEntity, generated, existingUse, nameConflict, nameUse, canonical));
        return result;
    }

    /** 계층 공통 검사 : 선택 범위 (D-AC-03), 기존 매핑·기록 (D-AC-12), 순환 */
    private List<String> treeErrors(Tree tree, Set<Long> chosenIds, Function<Long, List<String>> existingUse, List<PhysicalGenerationDto.EntityRef> missing) {
        List<String> errors = new ArrayList<>();
        for (Long id : tree.members()) {
            if (!chosenIds.contains(id)) missing.add(new PhysicalGenerationDto.EntityRef(id, entityOf.apply(id).getEntityName()));
            if (hierarchy.cyclic(id)) {
                errors.add(Messages.get("상속 계층에 순환이 있어 {0} 을(를) 만들 수 없습니다.", entityOf.apply(id).getEntityName()));
                break;
            }
        }
        if (!missing.isEmpty()) {
            errors.add(Messages.get("상속 계층 {0} 을(를) {1} 로 만들려면 계층 전체를 함께 골라야 합니다. 고르지 않은 엔터티 : {2}", entityOf.apply(tree.rootId()).getEntityName(),
                    strategyLabel(tree.strategy()), String.join(", ", missing.stream().map(PhysicalGenerationDto.EntityRef::entityName).toList())));
        }
        errors.addAll(existingUse.apply(tree.rootId()));
        return errors;
    }

    private TreePlan single(Tree tree, Set<Long> chosenIds, Set<Long> excludedRequested, Map<Long, Set<Long>> fkChildAttributes, Map<String, String> refTables,
                            Map<Long, String> tableNameByEntity, Map<Long, TableNameDto> generated, Function<Long, List<String>> existingUse,
                            Function<String, String> nameConflict, Map<String, Integer> nameUse, StringBuilder canonical) {
        Long rootId = tree.rootId();
        LogicalEntity root = entityOf.apply(rootId);
        List<PhysicalGenerationDto.EntityRef> missing = new ArrayList<>();
        List<String> errors = treeErrors(tree, chosenIds, existingUse, missing);
        List<String> warnings = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        List<PhysicalGenerationDto.ConstraintWarning> constraintWarnings = new ArrayList<>();
        String tableName = tableNameByEntity.get(rootId);
        tableNameErrors(rootId, tableName, generated, nameConflict, nameUse, errors);

        // 분류 속성 (D-AC-06) : 그룹마다 — 막는 조건 전부 오류, 자동 생성·변경 없음
        Set<Long> classificationAttributes = new LinkedHashSet<>();
        Map<Long, Long> classificationByGroup = new LinkedHashMap<>();
        List<LogicalHierarchy.Group> treeGroups = groupsOf(tree);
        // AC-P01 : 분류 속성 ID 기준 공유 판정 (같은 컬럼명·같은 값만으로 다른 속성을 같다고 보지 않는다)
        Map<Long, List<String>> classificationUse = new LinkedHashMap<>();
        treeGroups.forEach(g -> {
            LogicalSubtypeGroup row = groups.get(g.id());
            if (row != null && row.getClassificationAttribute() != null) {
                classificationUse.computeIfAbsent(row.getClassificationAttribute().getId(), k -> new ArrayList<>())
                        .add(row.getGroupName() + "(" + entityOf.apply(g.supertypeId()).getEntityName() + ")");
            }
        });
        Set<Long> sharedReported = new HashSet<>();
        for (LogicalHierarchy.Group g : treeGroups) {
            LogicalSubtypeGroup row = groups.get(g.id());
            String supertype = entityOf.apply(g.supertypeId()).getEntityName();
            canonicalGroup(canonical, row);
            if (row == null) {
                errors.add(Messages.get("상속 그룹을 찾을 수 없습니다. (ID {0})", g.id()));
                continue;
            }
            if (Boolean.TRUE.equals(row.getOverlapping())) {
                errors.add(Messages.get("그룹 {0}({1}) 은(는) 중복(Overlapping) 상속이라 A형(단일 테이블 통합)으로 만들 수 없습니다. A형은 배타(Disjoint) 상속만 지원합니다.", row.getGroupName(), supertype));
            }
            LogicalAttribute classification = row.getClassificationAttribute();
            if (classification == null) {
                errors.add(Messages.get("그룹 {0}({1}) 에 분류 속성이 없어 A형으로 만들 수 없습니다. 논리 모델의 상속 그룹에서 분류 속성과 분류 값을 먼저 지정해주세요.", row.getGroupName(), supertype));
                continue;
            }
            if (!hierarchy.chain(g.supertypeId()).contains(classification.getEntity().getId())) {
                errors.add(Messages.get("그룹 {0}({1}) 의 분류 속성 {2} 이(가) 슈퍼타입의 유효 속성이 아닙니다.", row.getGroupName(), supertype, classification.getAttributeName()));
                continue;
            }
            List<String> sharing = classificationUse.getOrDefault(classification.getId(), List.of());
            if (sharing.size() > 1) {
                // AC-P01 (2026-11-23 확정) : 여러 그룹이 같은 분류 속성을 쓰면 A형 생성 차단 — 다단계도 같다. 속성·값은 자동으로 바꾸지 않는다
                if (sharedReported.add(classification.getId())) {
                    errors.add(Messages.get("분류 속성 {0} 을(를) 상속 계층의 여러 그룹({1})이 함께 씁니다. A형은 그룹마다 서로 다른 분류 속성이 있어야 합니다. 그룹마다 다른 분류 속성을 지정해주세요.",
                            classification.getAttributeName(), String.join(", ", sharing)));
                }
                continue;
            }
            classificationAttributes.add(classification.getId());
            classificationByGroup.put(g.id(), classification.getId());
            errors.addAll(classificationValueErrors(row, supertype, classification));
            if (!Boolean.TRUE.equals(classification.getIsRequired())) {
                warnings.add(Messages.get("그룹 {0} 의 분류 속성 {1} 이(가) 필수가 아니라 분류 컬럼이 NULL 을 허용합니다 (논리 필수 여부는 바꾸지 않습니다).", row.getGroupName(), classification.getAttributeName()));
                codes.add("CLASSIFICATION_NULLABLE");
            }
            constraintWarnings.add(new PhysicalGenerationDto.ConstraintWarning(row.getId(), row.getGroupName(), g.supertypeId(), supertype, "CLASSIFICATION_VALUE", "NOT_ENFORCED",
                    Messages.get("그룹 {0}({1}) 의 분류 값 범위는 DB 제약으로 강제되지 않습니다 (분류 값 CHECK 를 만들지 않습니다).", row.getGroupName(), supertype)));
            if (Boolean.TRUE.equals(row.getTotal())) {
                constraintWarnings.add(new PhysicalGenerationDto.ConstraintWarning(row.getId(), row.getGroupName(), g.supertypeId(), supertype, "TOTAL", "NOT_ENFORCED",
                        Messages.get("그룹 {0}({1}) 의 완전(Total) 조건은 통합 테이블에서 강제되지 않습니다 : 분류 값이 비어 있는 행이 있을 수 있습니다.", row.getGroupName(), supertype)));
            }
            constraintWarnings.add(new PhysicalGenerationDto.ConstraintWarning(row.getId(), row.getGroupName(), g.supertypeId(), supertype, "SUBTYPE_SCOPE", "NOT_ENFORCED",
                    Messages.get("그룹 {0}({1}) 의 서브타입 속성이 해당 유형의 행에만 쓰인다는 조건은 DB 에서 강제되지 않습니다.", row.getGroupName(), supertype)));
        }

        // 컬럼 (D-AC-04·05) : 루트 → 후손(너비 우선) 순서, 엔터티마다 주 식별자 → 속성 순서. 루트 외 필수 속성은 NULL 허용
        Set<Long> primary = PhysicalGenerationService.primaryAttributeIds(root);
        // AC-P02 (2026-11-23 확정) : A형 통합 테이블은 루트 PK 가 반드시 있어야 한다 — 없거나 구성이 잘못되면 생성 차단 (예전 : 경고만)
        errors.addAll(rootKeyErrors(root, SINGLE_TABLE));
        Map<Long, Set<Long>> alternateAttributes = new HashMap<>();
        tree.members().forEach(id -> {
            Set<Long> set = new HashSet<>();
            LogicalRelationshipPairs.alternateKeys(entityOf.apply(id)).forEach(k -> set.addAll(k.attributeIds()));
            alternateAttributes.put(id, set);
        });
        Set<Long> fkAttributes = new HashSet<>();
        tree.members().forEach(id -> fkAttributes.addAll(fkChildAttributes.getOrDefault(id, Set.of())));
        Map<Long, List<PhysicalGenerationDto.AttributeChoice>> choices = new HashMap<>();
        Set<Long> excluded = new HashSet<>();
        for (Long id : tree.members()) {
            List<PhysicalGenerationDto.AttributeChoice> list = new ArrayList<>();
            for (LogicalAttribute attribute : PhysicalGenerationService.columnOrder(entityOf.apply(id))) {
                boolean requested = excludedRequested.contains(attribute.getId());
                boolean isPrimary = id.equals(rootId) && primary.contains(attribute.getId());
                boolean keep = isPrimary || fkAttributes.contains(attribute.getId()) || alternateAttributes.get(id).contains(attribute.getId())
                        || classificationAttributes.contains(attribute.getId());
                if (requested && keep) warnings.add(Messages.get("속성 {0} 은(는) 주 식별자·FK·보조 식별자·분류 속성이라 제외할 수 없어 포함했습니다.", attribute.getAttributeName()));
                boolean out = requested && !keep;
                if (out) excluded.add(attribute.getId());
                list.add(new PhysicalGenerationDto.AttributeChoice(attribute.getId(), attribute.getAttributeName(), isPrimary, out, requested, fkAttributes.contains(attribute.getId())));
            }
            choices.put(id, list);
        }
        canonical.append("ax").append(excluded.stream().sorted().toList());
        List<PhysicalGenerationDto.ColumnPlan> columnPlans = new ArrayList<>();
        Set<String> columnNames = new HashSet<>();
        for (Long id : tree.members()) {
            LogicalEntity entity = entityOf.apply(id);
            canonical.append('e').append(id).append('@').append(entity.modifiedAt());
            for (LogicalAttribute attribute : PhysicalGenerationService.columnOrder(entity)) {
                if (excluded.contains(attribute.getId())) continue;
                StandardTerm term = attribute.getStandardTerm();
                canonical.append('a').append(attribute.getId()).append('@').append(attribute.modifiedAt());
                if (term != null) canonical.append('t').append(term.getId()).append('@').append(term.modifiedAt());
                PhysicalGenerationService.ColumnSpec spec = PhysicalGenerationService.columnSpec(attribute);
                if (spec.error() != null) {
                    errors.add(spec.error());
                    continue;
                }
                if (!columnNames.add(ColumnNameKey.of(spec.columnName()))) {
                    errors.add(Messages.get("통합 테이블에서 서로 다른 속성이 같은 컬럼명({0})이 됩니다 ({1}). 이름을 바꾸거나 합치지 않습니다 — 표준 용어를 먼저 정리해주세요.",
                            spec.columnName(), entity.getEntityName() + "." + attribute.getAttributeName()));
                    continue;
                }
                boolean pk = id.equals(rootId) && primary.contains(attribute.getId());
                boolean required = Boolean.TRUE.equals(attribute.getIsRequired());
                boolean nullable = !pk && (!id.equals(rootId) || !required);
                if (!id.equals(rootId) && required) {
                    warnings.add(Messages.get("속성 {0} 은(는) 논리적으로 필수지만 통합 테이블에서는 NULL 을 허용합니다 (다른 유형의 행에는 값이 없다 — 유형 안의 필수 조건은 DB 에서 강제되지 않습니다).",
                            entity.getEntityName() + "." + attribute.getAttributeName()));
                    if (!codes.contains("CONDITIONAL_REQUIRED_NOT_ENFORCED")) codes.add("CONDITIONAL_REQUIRED_NOT_ENFORCED");
                }
                if (!Boolean.TRUE.equals(term.getIsApproval())) warnings.add(Messages.get("속성 {0} 의 표준 용어가 승인 전입니다.", attribute.getAttributeName()));
                columnPlans.add(new PhysicalGenerationDto.ColumnPlan(attribute.getId(), attribute.getAttributeName(), term.getId(), spec.columnName(), spec.dataType(),
                        spec.maxLength(), spec.precision(), spec.scale(), pk, nullable, refTables.get(rootId + ":" + attribute.getId()), term.getIsApproval(), null,
                        id.equals(rootId) ? "DIRECT" : "MERGED", id, entity.getEntityName(), required || pk, classificationAttributes.contains(attribute.getId())));
            }
        }
        // 분류 속성이 만들 컬럼에 있어야 한다 (D-AC-06 9.1)
        Map<Long, PhysicalGenerationDto.ColumnPlan> byAttribute = new HashMap<>();
        columnPlans.forEach(c -> byAttribute.put(c.attributeId(), c));
        List<PhysicalGenerationDto.ConversionPlan> conversions = new ArrayList<>();
        for (LogicalHierarchy.Group g : treeGroups) {
            LogicalSubtypeGroup row = groups.get(g.id());
            if (row == null) continue;
            Long attributeId = classificationByGroup.get(g.id());
            PhysicalGenerationDto.ColumnPlan column = attributeId == null ? null : byAttribute.get(attributeId);
            if (attributeId != null && column == null) {
                errors.add(Messages.get("그룹 {0} 의 분류 속성 {1} 의 컬럼을 만들 수 없어 A형으로 만들 수 없습니다.", row.getGroupName(), row.getClassificationAttribute().getAttributeName()));
            }
            conversions.add(new PhysicalGenerationDto.ConversionPlan(row.getId(), row.getGroupName(), g.supertypeId(), entityOf.apply(g.supertypeId()).getEntityName(), rootId,
                    SINGLE_TABLE, tableName, attributeId, row.getClassificationAttribute() == null ? null : row.getClassificationAttribute().getAttributeName(),
                    column == null ? null : column.columnName(), values(row)));
        }

        // UNIQUE (D-AC-08) : 구성원 자체 AK 를 통합 테이블에 — 같은 컬럼 집합은 하나만 (M7-AK-D05), PK 와 같으면 만들지 않는다
        List<PhysicalGenerationDto.UniquePlan> uniques = new ArrayList<>();
        Map<Set<Long>, ReferenceKeys.Key> seen = new HashMap<>();
        for (Long id : tree.members()) {
            List<String> extra = id.equals(rootId) ? List.of() : List.of("SUBTYPE_SCOPE_NOT_ENFORCED");
            uniques.addAll(uniquePlans(rootId, entityOf.apply(id), tableName, LogicalRelationshipPairs.alternateKeys(entityOf.apply(id)), primary, byAttribute, seen, extra, canonical));
        }

        // 테이블 계획 : 루트 = CREATE / ERROR (계층 오류는 루트에), 나머지 = MERGED
        Map<Long, PhysicalGenerationDto.TablePlan> tables = new LinkedHashMap<>();
        String status = errors.isEmpty() ? "CREATE" : "ERROR";
        List<PhysicalGenerationDto.EntityRef> tableEntities = tree.members().stream().map(id -> new PhysicalGenerationDto.EntityRef(id, entityOf.apply(id).getEntityName())).toList();
        tables.put(rootId, new PhysicalGenerationDto.TablePlan(rootId, root.getEntityName(), status, tableName, schema.getSchemaName(), columnPlans, errors, warnings,
                choices.get(rootId), inheritance(tree, rootId, "DIRECT", tableEntities, List.of())));
        for (Long id : tree.members()) {
            if (id.equals(rootId)) continue;
            tables.put(id, new PhysicalGenerationDto.TablePlan(id, entityOf.apply(id).getEntityName(), "MERGED", tableName, schema.getSchemaName(), List.of(), List.of(),
                    List.of(Messages.get("A형 : 루트 통합 테이블 {0} 에 들어갑니다 (자기 테이블을 만들지 않습니다).", tableName == null ? "-" : tableName)), choices.get(id),
                    inheritance(tree, id, "MERGED", List.of(), List.of())));
        }
        canonical.append("A").append(rootId).append(status).append(tableName).append(columnPlans).append(errors.size()).append(';');
        conversions.forEach(c -> canonical.append("cv").append(c.groupId()).append(c.strategy()).append(c.classificationAttributeId()).append(c.classificationColumnName())
                .append(c.values()).append(';'));
        return new TreePlan(tree, tables, uniques, conversions, constraintWarnings, errors, codes, missing);
    }

    private TreePlan concrete(Tree tree, Set<Long> chosenIds, Set<Long> excludedRequested, Map<Long, Set<Long>> fkChildAttributes, Map<String, String> refTables,
                              Map<Long, String> tableNameByEntity, Map<Long, TableNameDto> generated, Function<Long, List<String>> existingUse,
                              Function<String, String> nameConflict, Map<String, Integer> nameUse, StringBuilder canonical) {
        Long rootId = tree.rootId();
        LogicalEntity root = entityOf.apply(rootId);
        List<PhysicalGenerationDto.EntityRef> missing = new ArrayList<>();
        List<String> errors = treeErrors(tree, chosenIds, existingUse, missing);
        List<String> codes = new ArrayList<>(List.of("GLOBAL_KEY_NOT_ENFORCED"));
        List<PhysicalGenerationDto.ConstraintWarning> constraintWarnings = new ArrayList<>();
        List<PhysicalGenerationDto.ConversionPlan> conversions = new ArrayList<>();
        for (LogicalHierarchy.Group g : groupsOf(tree)) {
            LogicalSubtypeGroup row = groups.get(g.id());
            canonicalGroup(canonical, row);
            if (row == null) {
                errors.add(Messages.get("상속 그룹을 찾을 수 없습니다. (ID {0})", g.id()));
                continue;
            }
            String supertype = entityOf.apply(g.supertypeId()).getEntityName();
            // D-AC-01 4.2 : 계층의 모든 그룹이 Disjoint · Total (Total 을 추상 엔터티로 해석하지 않는다 — C형 선택의 생성 가능 조건일 뿐)
            if (Boolean.TRUE.equals(row.getOverlapping())) {
                errors.add(Messages.get("그룹 {0}({1}) 은(는) 중복(Overlapping) 상속이라 C형(말단 서브타입별 테이블)으로 만들 수 없습니다 (같은 행이 여러 테이블에 복제됩니다).", row.getGroupName(), supertype));
            }
            if (!Boolean.TRUE.equals(row.getTotal())) {
                errors.add(Messages.get("그룹 {0}({1}) 은(는) 부분(Partial) 상속이라 C형으로 만들 수 없습니다 (슈퍼타입만의 행을 담을 테이블이 없습니다).", row.getGroupName(), supertype));
            }
            conversions.add(new PhysicalGenerationDto.ConversionPlan(row.getId(), row.getGroupName(), g.supertypeId(), supertype, rootId, TABLE_PER_CONCRETE, null, null, null, null,
                    List.of()));
        }
        Set<Long> primary = PhysicalGenerationService.primaryAttributeIds(root);
        List<LogicalAttribute> rootKey = PhysicalGenerationService.columnOrder(root).stream().filter(a -> primary.contains(a.getId())).toList();
        errors.addAll(rootKeyErrors(root, TABLE_PER_CONCRETE));
        // 제외 : 조상 속성을 제외하면 모든 말단에서 빠진다. 주 식별자·FK·보조 식별자 속성은 제외할 수 없다
        Set<Long> keep = new HashSet<>(primary);
        tree.members().forEach(id -> LogicalRelationshipPairs.alternateKeys(entityOf.apply(id)).forEach(k -> keep.addAll(k.attributeIds())));
        fkChildAttributes.forEach((ctx, set) -> { if (tree.members().contains(ctx)) keep.addAll(set); });
        Map<Long, List<PhysicalGenerationDto.AttributeChoice>> choices = new HashMap<>();
        Set<Long> excluded = new HashSet<>();
        List<String> treeWarnings = new ArrayList<>();
        for (Long id : tree.members()) {
            List<PhysicalGenerationDto.AttributeChoice> list = new ArrayList<>();
            for (LogicalAttribute attribute : PhysicalGenerationService.columnOrder(entityOf.apply(id))) {
                boolean requested = excludedRequested.contains(attribute.getId());
                boolean kept = keep.contains(attribute.getId());
                if (requested && kept) treeWarnings.add(Messages.get("속성 {0} 은(는) 주 식별자·FK·보조 식별자 속성이라 제외할 수 없어 포함했습니다.", attribute.getAttributeName()));
                boolean out = requested && !kept;
                if (out) excluded.add(attribute.getId());
                list.add(new PhysicalGenerationDto.AttributeChoice(attribute.getId(), attribute.getAttributeName(), primary.contains(attribute.getId()), out, requested,
                        keep.contains(attribute.getId()) && !primary.contains(attribute.getId())));
            }
            choices.put(id, list);
        }
        canonical.append("cx").append(excluded.stream().sorted().toList());
        tree.members().forEach(id -> canonical.append('e').append(id).append('@').append(entityOf.apply(id).modifiedAt()));

        Map<Long, PhysicalGenerationDto.TablePlan> tables = new LinkedHashMap<>();
        List<PhysicalGenerationDto.UniquePlan> uniques = new ArrayList<>();
        List<PhysicalGenerationDto.EntityRef> leafRefs = tree.leaves().stream().map(id -> new PhysicalGenerationDto.EntityRef(id, entityOf.apply(id).getEntityName())).toList();
        for (Long leaf : tree.leaves()) {
            LogicalEntity entity = entityOf.apply(leaf);
            List<String> leafErrors = new ArrayList<>();
            List<String> leafWarnings = new ArrayList<>(treeWarnings);
            String tableName = tableNameByEntity.get(leaf);
            tableNameErrors(leaf, tableName, generated, nameConflict, nameUse, leafErrors);
            List<PhysicalGenerationDto.ColumnPlan> columnPlans = new ArrayList<>();
            Set<String> columnNames = new HashSet<>();
            // 루트 PK (상속 PK — 루트 식별자 순서) → 조상 일반 속성 (루트 → 직계 부모) → 자체 속성
            for (Long owner : hierarchy.chain(leaf)) {
                LogicalEntity ownerEntity = entityOf.apply(owner);
                List<LogicalAttribute> attributes = owner.equals(rootId)
                        ? concat(rootKey, PhysicalGenerationService.columnOrder(ownerEntity).stream().filter(a -> !primary.contains(a.getId())).toList())
                        : PhysicalGenerationService.columnOrder(ownerEntity);
                for (LogicalAttribute attribute : attributes) {
                    if (excluded.contains(attribute.getId())) continue;
                    StandardTerm term = attribute.getStandardTerm();
                    canonical.append('a').append(attribute.getId()).append('@').append(attribute.modifiedAt());
                    if (term != null) canonical.append('t').append(term.getId()).append('@').append(term.modifiedAt());
                    PhysicalGenerationService.ColumnSpec spec = PhysicalGenerationService.columnSpec(attribute);
                    if (spec.error() != null) {
                        leafErrors.add(spec.error());
                        continue;
                    }
                    if (!columnNames.add(ColumnNameKey.of(spec.columnName()))) {
                        leafErrors.add(Messages.get("말단 테이블 {0} 에서 서로 다른 속성이 같은 컬럼명({1})이 됩니다 ({2}). 이름을 바꾸거나 합치지 않습니다 — 표준 용어를 먼저 정리해주세요.",
                                tableName == null ? entity.getEntityName() : tableName, spec.columnName(), ownerEntity.getEntityName() + "." + attribute.getAttributeName()));
                        continue;
                    }
                    boolean pk = primary.contains(attribute.getId());
                    boolean required = Boolean.TRUE.equals(attribute.getIsRequired());
                    String kind = owner.equals(leaf) ? "DIRECT" : pk ? "INHERITED_KEY" : "INHERITED_ATTRIBUTE";
                    if (!Boolean.TRUE.equals(term.getIsApproval())) leafWarnings.add(Messages.get("속성 {0} 의 표준 용어가 승인 전입니다.", attribute.getAttributeName()));
                    columnPlans.add(new PhysicalGenerationDto.ColumnPlan(attribute.getId(), attribute.getAttributeName(), term.getId(), spec.columnName(), spec.dataType(),
                            spec.maxLength(), spec.precision(), spec.scale(), pk, !pk && !required, refTables.get(leaf + ":" + attribute.getId()), term.getIsApproval(),
                            pk ? rootId : null, kind, owner, ownerEntity.getEntityName(), required || pk, false));
                }
            }
            if (!primary.isEmpty()) leafWarnings.add(Messages.get("말단 테이블 사이의 PK 유일성은 보장되지 않습니다 : 같은 {0} 값이 다른 말단 테이블에도 들어갈 수 있습니다.",
                    String.join(", ", rootKey.stream().map(LogicalAttribute::getAttributeName).toList())));
            Map<Long, PhysicalGenerationDto.ColumnPlan> byAttribute = new HashMap<>();
            columnPlans.forEach(c -> byAttribute.put(c.attributeId(), c));
            Map<Set<Long>, ReferenceKeys.Key> seen = new HashMap<>();
            for (Long owner : hierarchy.chain(leaf)) {
                List<String> extra = owner.equals(leaf) ? List.of() : List.of("GLOBAL_KEY_NOT_ENFORCED");
                uniques.addAll(uniquePlans(leaf, entityOf.apply(owner), tableName, LogicalRelationshipPairs.alternateKeys(entityOf.apply(owner)), primary, byAttribute, seen, extra,
                        canonical));
            }
            String status = leafErrors.isEmpty() && errors.isEmpty() ? "CREATE" : "ERROR";
            canonical.append("C").append(leaf).append(status).append(tableName).append(columnPlans).append(leafErrors.size()).append(';');
            tables.put(leaf, new PhysicalGenerationDto.TablePlan(leaf, entity.getEntityName(), status, tableName, schema.getSchemaName(), columnPlans, leafErrors, leafWarnings,
                    choices.get(leaf), inheritance(tree, leaf, "DIRECT", List.of(new PhysicalGenerationDto.EntityRef(leaf, entity.getEntityName())), List.of())));
        }
        for (Long id : tree.members()) {
            if (tree.leaves().contains(id)) continue;
            boolean isRoot = id.equals(rootId);
            List<PhysicalGenerationDto.EntityRef> under = hierarchy.subtree(id).stream().filter(tree.leaves()::contains)
                    .map(l -> new PhysicalGenerationDto.EntityRef(l, entityOf.apply(l).getEntityName())).toList();
            String status = isRoot && !errors.isEmpty() ? "ERROR" : "DISTRIBUTED";
            tables.put(id, new PhysicalGenerationDto.TablePlan(id, entityOf.apply(id).getEntityName(), status, null, schema.getSchemaName(), List.of(),
                    isRoot ? errors : List.of(),
                    List.of(Messages.get("C형 : 자기 테이블을 만들지 않고 말단 테이블 {0} 에 속성을 복제합니다.", String.join(", ", under.stream().map(PhysicalGenerationDto.EntityRef::entityName).toList()))),
                    choices.get(id), inheritance(tree, id, "DISTRIBUTED", under, List.of())));
        }
        constraintWarnings.add(new PhysicalGenerationDto.ConstraintWarning(null, null, rootId, root.getEntityName(), "GLOBAL_KEY", "NOT_ENFORCED",
                Messages.get("C형 계층 {0} : 서로 다른 말단 테이블 사이의 PK·상속 보조 식별자 유일성은 DB 에서 보장되지 않습니다 (말단 테이블 안에서만 보장).", root.getEntityName())));
        canonical.append("C").append(rootId).append(errors.size()).append(';');
        conversions.forEach(c -> canonical.append("cv").append(c.groupId()).append(c.strategy()).append(';'));
        return new TreePlan(tree, tables, uniques, conversions, constraintWarnings, errors, codes, missing);
    }

    // ------------------------------------------------------------------ 보조

    private List<LogicalHierarchy.Group> groupsOf(Tree tree) {
        List<LogicalHierarchy.Group> result = new ArrayList<>();
        tree.members().forEach(id -> result.addAll(hierarchy.groupsOf(id)));
        return result;
    }

    private void canonicalGroup(StringBuilder canonical, LogicalSubtypeGroup row) {
        if (row == null) return;
        canonical.append("ag").append(row.getId()).append('s').append(row.getSupertypeEntity().getId()).append('o').append(row.getOverlapping()).append('t').append(row.getTotal())
                .append('c').append(row.getClassificationAttribute() == null ? null : row.getClassificationAttribute().getId()).append('@').append(row.modifiedAt());
        members.getOrDefault(row.getId(), List.of()).forEach(m -> canonical.append('m').append(m.getSubtypeEntity().getId()).append('=').append(m.getClassificationValue())
                .append('@').append(m.modifiedAt()));
        canonical.append(';');
    }

    /**
     * 루트 주 식별자 검사 (AC-P02) : A형·C형 모두 생성 차단 조건. 주 식별자 있음, 구성 속성 1개 이상, 모든 구성 속성이 루트 자신의 속성, 같은 속성 중복 없음.
     * 논리 저장 경로(LogicalModelService.saveIdentifier)도 같은 조건을 막지만, 교환 가져오기 등 다른 경로로 들어온 데이터도 생성 단계에서 다시 본다.
     * B형(PhysicalGenerationService) 의 '주 식별자가 없어 PK 없이 만듭니다' 경고 정책은 바꾸지 않는다.
     */
    static List<String> rootKeyErrors(LogicalEntity root, String strategy) {
        List<String> errors = new ArrayList<>();
        LogicalIdentifier identifier = root.getIdentifiers().stream().filter(i -> Boolean.TRUE.equals(i.getIsPrimary())).findFirst().orElse(null);
        if (identifier == null) {
            errors.add(SINGLE_TABLE.equals(strategy)
                    ? Messages.get("루트 엔터티 {0} 에 주 식별자가 없어 A형 통합 테이블의 PK 를 만들 수 없습니다. 논리 모델에서 루트의 주 식별자를 먼저 지정해주세요.", root.getEntityName())
                    : Messages.get("루트 엔터티 {0} 에 주 식별자가 없어 말단 테이블의 PK 를 만들 수 없습니다.", root.getEntityName()));
            return errors;
        }
        if (identifier.getAttributes().isEmpty()) {
            errors.add(Messages.get("루트 엔터티 {0} 의 주 식별자 {1} 에 구성 속성이 없어 PK 를 만들 수 없습니다.", root.getEntityName(), identifier.getIdentifierName()));
            return errors;
        }
        Set<Long> own = new HashSet<>();
        root.getAttributes().forEach(a -> own.add(a.getId()));
        Set<Long> seen = new HashSet<>();
        for (var item : identifier.getAttributes()) {
            LogicalAttribute attribute = item.getAttribute();
            if (attribute == null || !own.contains(attribute.getId())) {
                errors.add(Messages.get("루트 엔터티 {0} 의 주 식별자 {1} 에 루트에 속하지 않는 속성 {2} 이(가) 들어 있어 PK 를 만들 수 없습니다.", root.getEntityName(), identifier.getIdentifierName(),
                        attribute == null ? "?" : attribute.getAttributeName()));
            } else if (!seen.add(attribute.getId())) {
                errors.add(Messages.get("루트 엔터티 {0} 의 주 식별자 {1} 에 같은 속성 {2} 이(가) 두 번 들어 있어 PK 를 만들 수 없습니다.", root.getEntityName(), identifier.getIdentifierName(),
                        attribute.getAttributeName()));
            }
        }
        return errors;
    }

    private List<PhysicalGenerationDto.ClassificationValue> values(LogicalSubtypeGroup row) {
        return members.getOrDefault(row.getId(), List.of()).stream()
                .map(m -> new PhysicalGenerationDto.ClassificationValue(m.getSubtypeEntity().getId(), m.getSubtypeEntity().getEntityName(), m.getClassificationValue())).toList();
    }

    /**
     * 분류 값 검사 (D-AC-06 9.1) : 모든 구성원의 값이 있고, 분류 속성 타입(표준 용어 도메인)에 맞고, 타입 의미로 비교해 중복이 없어야 한다.
     * 문자형 = 길이, 정수형 = 정수·범위, NUMBER·NUMERIC·DECIMAL = 정밀도·소수 자릿수. 판단할 수 없는 타입은 통과시키지 않는다
     */
    private List<String> classificationValueErrors(LogicalSubtypeGroup row, String supertype, LogicalAttribute classification) {
        List<String> errors = new ArrayList<>();
        PhysicalGenerationService.ColumnSpec spec = PhysicalGenerationService.columnSpec(classification);
        if (spec.error() != null) {
            errors.add(Messages.get("그룹 {0} 의 분류 속성 {1} 의 타입을 정할 수 없습니다 : {2}", row.getGroupName(), classification.getAttributeName(), spec.error()));
            return errors;
        }
        String type = spec.dataType().toUpperCase(Locale.ROOT);
        Map<String, String> normalized = new HashMap<>();
        for (LogicalSubtype member : members.getOrDefault(row.getId(), List.of())) {
            String name = member.getSubtypeEntity().getEntityName();
            String value = member.getClassificationValue();
            if (value == null || value.isBlank()) {
                errors.add(Messages.get("그룹 {0}({1}) 의 서브타입 {2} 에 분류 값이 없어 A형으로 만들 수 없습니다.", row.getGroupName(), supertype, name));
                continue;
            }
            String key;
            if (CHARACTER.contains(type)) {
                if (spec.maxLength() != null && value.codePointCount(0, value.length()) > spec.maxLength()) {
                    errors.add(Messages.get("서브타입 {0} 의 분류 값 {1} 이(가) 분류 속성 {2} 의 길이({3})를 넘습니다.", name, value, classification.getAttributeName(), spec.maxLength()));
                    continue;
                }
                key = value;
            } else if (INTEGER.containsKey(type) || DECIMAL.contains(type)) {
                if (!NUMBER_TEXT.matcher(value).matches()) {
                    errors.add(Messages.get("서브타입 {0} 의 분류 값 {1} 이(가) 숫자형 분류 속성 {2} 의 값이 아닙니다.", name, value, classification.getAttributeName()));
                    continue;
                }
                BigDecimal number = new BigDecimal(value).stripTrailingZeros();
                if (number.signum() == 0) number = BigDecimal.ZERO;
                if (INTEGER.containsKey(type)) {
                    BigInteger[] range = INTEGER.get(type);
                    if (number.scale() > 0 || number.toBigInteger().compareTo(range[0]) < 0 || number.toBigInteger().compareTo(range[1]) > 0) {
                        errors.add(Messages.get("서브타입 {0} 의 분류 값 {1} 이(가) 분류 속성 {2} 의 타입({3})에 맞지 않습니다.", name, value, classification.getAttributeName(), type));
                        continue;
                    }
                } else {
                    Integer precision = spec.precision();
                    int scale = spec.scale() == null ? 0 : spec.scale();
                    int fraction = Math.max(number.scale(), 0);
                    int integerDigits = number.precision() - number.scale();
                    if (precision != null && (fraction > scale || Math.max(integerDigits, 0) > precision - scale)) {
                        errors.add(Messages.get("서브타입 {0} 의 분류 값 {1} 이(가) 분류 속성 {2} 의 정밀도({3},{4})에 맞지 않습니다.", name, value, classification.getAttributeName(), precision, scale));
                        continue;
                    }
                }
                key = number.toPlainString();
            } else {
                errors.add(Messages.get("분류 속성 {0} 의 타입({1})은 A형 분류 값 검사를 지원하지 않습니다 (문자형·정수형·NUMBER/NUMERIC/DECIMAL 만). 다른 분류 속성을 지정해주세요.",
                        classification.getAttributeName(), type));
                return errors;
            }
            String other = normalized.putIfAbsent(key, name);
            if (other != null) {
                errors.add(Messages.get("그룹 {0}({1}) 의 서브타입 {2}·{3} 의 분류 값이 같은 값({4})입니다. 분류 값은 그룹 안에서 달라야 합니다.", row.getGroupName(), supertype, other, name, value));
            }
        }
        return errors;
    }

    private void tableNameErrors(Long entityId, String tableName, Map<Long, TableNameDto> generated, Function<String, String> nameConflict, Map<String, Integer> nameUse,
                                 List<String> errors) {
        LogicalEntity entity = entityOf.apply(entityId);
        if (tableName == null) {
            errors.add(Messages.get("엔터티명 {0} 으로 표준 단어 기준 테이블명을 만들 수 없습니다. 표준 단어를 먼저 등록해주세요.", entity.getEntityName()));
            return;
        }
        if (nameUse.getOrDefault(tableName, 0) > 1) errors.add(Messages.get("고른 엔터티 중 같은 테이블명({0})이 되는 것이 있습니다.", tableName));
        else {
            String conflict = nameConflict.apply(tableName);
            if (conflict != null) errors.add(conflict);
        }
    }

    /**
     * 보조 식별자 → UNIQUE (M7-AK-01 규칙 그대로 : 식별자 ID 순, 같은 컬럼 집합은 대표 하나, PK 와 같으면 만들지 않음, NULL 허용 구성은 경고만).
     * contextEntityId : 테이블 문맥 (A형 = 루트, C형 = 말단), owner : AK 를 정의한 엔터티, extra : 전략 경고 (SUBTYPE_SCOPE_NOT_ENFORCED · GLOBAL_KEY_NOT_ENFORCED)
     */
    private List<PhysicalGenerationDto.UniquePlan> uniquePlans(Long contextEntityId, LogicalEntity owner, String tableName, List<ReferenceKeys.Key> alternates, Set<Long> primary,
                                                               Map<Long, PhysicalGenerationDto.ColumnPlan> byAttribute, Map<Set<Long>, ReferenceKeys.Key> seen, List<String> extra,
                                                               StringBuilder canonical) {
        List<PhysicalGenerationDto.UniquePlan> plans = new ArrayList<>();
        for (ReferenceKeys.Key key : alternates) {
            Set<Long> set = new HashSet<>(key.attributeIds());
            List<PhysicalGenerationDto.ColumnPlan> columns = key.attributeIds().stream().map(byAttribute::get).toList();
            List<String> names = columns.stream().map(c -> c == null ? null : c.columnName()).toList();
            String status = "CREATE";
            String reason = null;
            ReferenceKeys.Key duplicateOf = null;
            List<String> warnings = new ArrayList<>();
            String message;
            if (columns.contains(null) || tableName == null) {
                status = "SKIP";
                reason = "COLUMN_UNAVAILABLE";
                message = Messages.get("보조 식별자 {0} 의 구성 컬럼을 만들 수 없어 UNIQUE 를 만들지 않습니다 (테이블의 오류를 먼저 고쳐주세요).", key.identifierName());
            } else if (set.equals(primary)) {
                status = "SKIP";
                reason = "PRIMARY_KEY_DUPLICATE";
                message = Messages.get("보조 식별자 {0} 의 구성이 주 식별자(PK)와 같아 UNIQUE 를 따로 만들지 않습니다.", key.identifierName());
            } else if (seen.containsKey(set)) {
                status = "SKIP";
                reason = "DUPLICATE_ALTERNATE_KEY";
                duplicateOf = seen.get(set);
                message = Messages.get("보조 식별자 {0} 의 구성이 보조 식별자 {1} 과(와) 같아 UNIQUE 하나만 만듭니다 ({1} 기준).", key.identifierName(), duplicateOf.identifierName());
            } else {
                seen.put(set, key);
                boolean nullable = columns.stream().anyMatch(PhysicalGenerationDto.ColumnPlan::nullable);
                if (nullable) warnings.add("NULLABLE_COLUMNS");
                if (nullable && columns.size() > 1 && db == DbType.ORACLE) warnings.add("ORACLE_COMPOSITE_NULL");
                warnings.addAll(extra);
                message = extra.contains("SUBTYPE_SCOPE_NOT_ENFORCED")
                        ? Messages.get("{0} 의 보조 식별자 {1} 을(를) 통합 테이블 전체의 UNIQUE 로 만듭니다 (미승인 — 유형 안의 적용 범위는 DB 에서 강제되지 않습니다).", owner.getEntityName(), key.identifierName())
                        : extra.contains("GLOBAL_KEY_NOT_ENFORCED")
                        ? Messages.get("상속받은 보조 식별자 {1}({0}) 을(를) 이 말단 테이블의 UNIQUE 로 만듭니다 (미승인 — 다른 말단 테이블과의 유일성은 보장되지 않습니다).", owner.getEntityName(), key.identifierName())
                        : Messages.get("보조 식별자 {0} 을(를) UNIQUE 로 만듭니다 (미승인).", key.identifierName());
            }
            String name = "CREATE".equals(status) ? DbIdentifierRules.uniqueName(tableName, names) : null;
            canonical.append("au").append(contextEntityId).append(':').append(key.identifierId()).append(key.attributeIds()).append(status).append(reason).append(name)
                    .append(warnings).append(';');
            plans.add(new PhysicalGenerationDto.UniquePlan(contextEntityId, owner.getEntityName(), key.identifierId(), key.identifierName(), tableName, name,
                    names.stream().filter(Objects::nonNull).toList(), status, reason, duplicateOf == null ? null : duplicateOf.identifierId(),
                    duplicateOf == null ? null : duplicateOf.identifierName(), warnings, message));
        }
        return plans;
    }

    private PhysicalGenerationDto.InheritancePlan inheritance(Tree tree, Long id, String physicalRole, List<PhysicalGenerationDto.EntityRef> tableEntities, List<String> reasons) {
        Long parentId = hierarchy.parent(id);
        LogicalHierarchy.Group group = hierarchy.parentGroup(id);
        LogicalSubtypeGroup row = group == null ? null : groups.get(group.id());
        String role = parentId == null ? "ROOT" : hierarchy.groupsOf(id).isEmpty() ? "SUBTYPE" : "INTERMEDIATE";
        return new PhysicalGenerationDto.InheritancePlan(tree.strategy(), role, hierarchy.level(id), parentId, parentId == null ? null : entityOf.apply(parentId).getEntityName(),
                tree.rootId(), entityOf.apply(tree.rootId()).getEntityName(), group == null ? null : group.id(), row == null ? null : row.getGroupName(), reasons,
                physicalRole, tableEntities);
    }

    static String strategyLabel(String strategy) {
        return SINGLE_TABLE.equals(strategy) ? Messages.get("A형(단일 테이블 통합)") : TABLE_PER_CONCRETE.equals(strategy) ? Messages.get("C형(말단 서브타입별 테이블)")
                : Messages.get("B형(슈퍼타입·서브타입 개별 테이블)");
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
