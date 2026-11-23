package com.lexorma.data.mapping.service;

import com.lexorma.common.i18n.Messages;
import com.lexorma.data.logical.service.LogicalHierarchy;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Source;

import java.util.*;

/**
 * 상속 A·C형의 속성 단위 정합성 (M-7 상속 A·C, D-AC-13 — docs/completed/inheritance-strategy-ac-m7.md). 순수 계산 : 조회 결과(진단 원본·매핑)를 받아 행을 만든다.
 * <ul>
 *   <li>A형 : 서브타입 속성을 루트 통합 테이블 문맥으로 진단한다 (속성 매핑은 루트 매핑 아래). 서브타입 필수 속성은 NULL 허용이 기대 구조 — MATCH + CONDITIONAL_REQUIRED_NOT_ENFORCED,
 *       NOT NULL 이면 STRATEGY_STRUCTURE_MISMATCH. 식별자는 루트 주 식별자로 본다</li>
 *   <li>C형 : 루트·중간(분산) 엔터티의 속성을 말단마다 그 말단 문맥의 복제 컬럼(상속 PK 매핑·상속 일반 속성 매핑)으로 진단한다 — 대표 말단 하나로 판단하지 않는다.
 *       복제 매핑이 없으면 그 말단의 UNMAPPED (INHERITED_ATTRIBUTE_MAPPING_MISSING). 분산 엔터티 자신은 [상속 구조] 1행 (NOT_COMPARABLE · INHERITANCE_DISTRIBUTED)</li>
 *   <li>[상속 구조] 행 (INHERITANCE) : 엔터티마다 1행 — A형 루트(통합 테이블·분류 컬럼), A형 구성원(통합), C형 말단(복제 매핑 완성), C형 분산, 맞지 않는 기록(STRATEGY_STRUCTURE_MISMATCH)</li>
 * </ul>
 * 상속 FK 는 A·C 에 없으므로 B형 상속 구조 판정(SubtypePlanContext)을 쓰지 않는다.
 */
final class StrategyQualityRows {

    private StrategyQualityRows() {
    }

    /** 복제·통합 문맥의 컬럼 하나 (상속 PK·상속 일반 속성 매핑) */
    record Copied(Long attributeId, ColumnInfo column) {
    }

    /** C형 말단 문맥 : 매핑과 그 문맥의 복제 컬럼 (원본 속성 → 컬럼) */
    record Leaf(Long entityId, String entityName, Long mappingId, Long schemaId, String schemaName, Long tableId, String tableName, Map<Long, ColumnInfo> copied) {
    }

    /**
     * 진단 원본을 A·C 계층과 그 밖으로 나눠 진단한다. primary : 엔터티 → 주 식별자 속성 (A·C 계층 엔터티는 루트의 것으로 바꿔 넣는다), leaves : C형 말단 문맥,
     * distributedSources : C형 분산 엔터티의 진단 원본 (검색 조건과 무관하게 읽은 것), match : 복제 행의 검색 조건
     */
    static List<Row> rows(DbType db, InheritanceConversions c, List<Source> sources, Map<Long, Set<Long>> primary, boolean entityRows, Map<Long, Leaf> leaves,
                          List<Source> distributedSources, java.util.function.Predicate<Row> match) {
        Map<Long, Set<Long>> effective = new HashMap<>(primary);
        for (Source s : sources) {
            if (c.kind(s.entityId()) == InheritanceConversions.Kind.NONE) continue;
            Set<Long> rootKey = primary.get(c.hierarchy().root(s.entityId()));
            if (rootKey == null) effective.remove(s.entityId());
            else effective.put(s.entityId(), rootKey);
        }
        // A형 루트 문맥 (매핑·테이블) : 루트의 진단 원본에서
        Map<Long, Source> rootContext = new HashMap<>();
        for (Source s : sources) {
            if (c.kind(s.entityId()) == InheritanceConversions.Kind.SINGLE_TABLE && c.hierarchy().parent(s.entityId()) == null) rootContext.putIfAbsent(s.entityId(), s);
        }
        List<Source> adjusted = new ArrayList<>();
        Set<Long> merged = new HashSet<>();
        for (Source s : sources) {
            InheritanceConversions.Role role = c.role(s.entityId());
            if (role == InheritanceConversions.Role.DISTRIBUTED) continue; // 분산 엔터티의 속성은 말단 문맥에서 (아래)
            if (role == InheritanceConversions.Role.MERGED) {
                merged.add(s.entityId());
                Source root = rootContext.get(c.hierarchy().root(s.entityId()));
                if (root != null && root.mappingId() != null) {
                    s = new Source(s.modelId(), s.modelName(), s.entityId(), s.entityName(), root.mappingId(), root.schemaId(), root.schemaName(), root.tableId(), root.tableName(),
                            s.attributeId(), s.attributeName(), s.attributeRequired(), s.attributeTermId(), s.attributeTermName(), s.attributeTermAbbreviation(), s.domainId(),
                            s.domainName(), s.domainDataType(), s.domainLength(), s.domainDecimalLength(), s.attributeMappingId(), s.columnId(), s.columnName(), s.columnDataType(),
                            s.columnMaxLength(), s.columnPrecision(), s.columnScale(), s.columnNullable(), s.columnRequired(), s.columnPk(), s.columnTermId(), s.columnTermName());
                }
            }
            adjusted.add(s);
        }
        List<Row> rows = new ArrayList<>();
        // AC-P03 : 생성 뒤 A형 계층에서 빠진 엔터티의 속성이 루트 통합 매핑 아래 그대로 매핑돼 있다 (엔터티 자신의 매핑은 없다) — 미매핑이 아니라 유효하지 않은 기존 매핑
        Map<String, Source> staleMerged = new HashMap<>();
        for (Source s : adjusted) {
            if (s.attributeId() != null && s.mappingId() == null && s.attributeMappingId() != null && c.kind(s.entityId()) == InheritanceConversions.Kind.NONE) {
                staleMerged.put(s.entityId() + ":" + s.attributeId(), s);
            }
        }
        for (Row row : ModelMappingQualityService.diagnose(db, adjusted, effective, entityRows)) {
            Source stale = row.type() == CheckType.MAPPING && row.attributeId() != null ? staleMerged.get(row.entityId() + ":" + row.attributeId()) : null;
            if (stale != null) {
                rows.add(row.withStrategy(CheckStatus.MISMATCH, Messages.get("엔터티 {0} 은(는) 지금 A형 계층의 구성원이 아닌데, 속성 {1} 이(가) 다른 엔터티의 매핑(통합 테이블) 아래 컬럼 {2} 에 그대로 매핑되어 있습니다. 매핑 관리에서 해제할 수 있습니다.",
                        stale.entityName(), stale.attributeName(), stale.columnName()), "STRATEGY_STRUCTURE_MISMATCH", List.of()));
                continue;
            }
            rows.add(merged.contains(row.entityId()) ? mergedRow(row, adjusted) : row);
        }
        // C형 복제 행 : 분산 엔터티 D 의 속성 × D 아래 말단 L
        for (Source s : distributedSources) {
            if (c.role(s.entityId()) != InheritanceConversions.Role.DISTRIBUTED || s.attributeId() == null) continue;
            for (Long leafId : c.leavesUnder(s.entityId())) {
                Leaf leaf = leaves.get(leafId);
                if (leaf == null) continue; // 말단이 매핑되지 않았다 : 말단의 [상속 구조] 행이 UNMAPPED 를 낸다
                ColumnInfo column = leaf.copied().get(s.attributeId());
                Source copy = new Source(s.modelId(), s.modelName(), leaf.entityId(), leaf.entityName(), leaf.mappingId(), leaf.schemaId(), leaf.schemaName(), leaf.tableId(),
                        leaf.tableName(), s.attributeId(), s.attributeName(), s.attributeRequired(), s.attributeTermId(), s.attributeTermName(), s.attributeTermAbbreviation(),
                        s.domainId(), s.domainName(), s.domainDataType(), s.domainLength(), s.domainDecimalLength(), column == null ? null : -1L,
                        column == null ? null : column.getId(), column == null ? null : column.getColumnName(), column == null ? null : column.getDataType(),
                        column == null ? null : column.getMaxLength(), column == null ? null : column.getPrecision(), column == null ? null : column.getScale(),
                        column == null ? null : column.getIsNullable(), column == null ? null : column.getIsRequired(), column == null ? null : column.getIsPk(),
                        column == null || column.getStandardTerms() == null ? null : column.getStandardTerms().getId(),
                        column == null || column.getStandardTerms() == null ? null : column.getStandardTerms().getCommonStandardTermName());
                for (Row row : ModelMappingQualityService.diagnose(db, List.of(copy), Map.of(leaf.entityId(), effective.getOrDefault(s.entityId(), Set.of())), entityRows)) {
                    Row result = column == null
                            ? row.withStrategy(CheckStatus.UNMAPPED, Messages.get("말단 {0} 테이블에 조상 {1} 의 속성 {2} 을(를) 복제한 컬럼의 매핑(상속 PK·상속 일반 속성 매핑)이 없습니다.",
                            leaf.entityName(), s.entityName(), s.attributeName()), "INHERITED_ATTRIBUTE_MAPPING_MISSING", List.of())
                            : row.withStrategy(row.status(), row.reason(), "INHERITED_ATTRIBUTE", List.of());
                    if (match.test(result)) rows.add(result);
                }
            }
        }
        return rows;
    }

    /** A형 구성원 행 : 필수 여부는 통합 테이블 기준 (D-AC-05), 다른 진단은 그대로 */
    private static Row mergedRow(Row row, List<Source> sources) {
        if (row.type() != CheckType.REQUIRED) return row.withStrategy(row.status(), row.reason(), "INHERITANCE_MERGED", List.of());
        Source s = sources.stream().filter(x -> Objects.equals(x.attributeId(), row.attributeId()) && Objects.equals(x.entityId(), row.entityId())).findFirst().orElse(null);
        if (s == null) return row;
        boolean logical = Boolean.TRUE.equals(s.attributeRequired());
        boolean notNull = Boolean.TRUE.equals(s.columnPk()) || Boolean.TRUE.equals(s.columnRequired()) || Boolean.FALSE.equals(s.columnNullable());
        if (!logical) return row.withStrategy(row.status(), row.reason(), "INHERITANCE_MERGED", List.of());
        if (notNull) {
            return row.withStrategy(CheckStatus.MISMATCH, Messages.get("A형 통합 테이블에서는 서브타입의 필수 속성 컬럼이 NULL 을 허용해야 합니다 (다른 유형의 행에는 값이 없습니다)."),
                    "STRATEGY_STRUCTURE_MISMATCH", List.of());
        }
        return row.withStrategy(CheckStatus.MATCH, Messages.get("논리적으로 필수지만 A형 통합 테이블에서는 NULL 을 허용합니다 (유형 안의 필수 조건은 DB 에서 강제되지 않습니다)."),
                "INHERITANCE_MERGED", List.of("CONDITIONAL_REQUIRED_NOT_ENFORCED"));
    }

    /**
     * [상속 구조] 행 : A·C·맞지 않는 계층의 엔터티마다 1행. rootContext : 엔터티 → (매핑 ID, 테이블 ID, 이름) 문맥 (진단 원본의 첫 행), classificationColumns : 그룹 → 분류 속성 매핑 컬럼 ID,
     * expected : C형 말단 → 기대하는 복제 속성 수 (루트 PK + 조상 일반 속성), names : 엔터티 이름
     */
    static List<Row> inheritanceRows(InheritanceConversions c, Map<Long, Source> context, Map<Long, Long> classificationColumns, Map<Long, Integer> expected,
                                     Map<Long, Leaf> leaves, Map<Long, ConversionBasis.Result> basis, Set<Long> tablesWithPk, Long modelId,
                                     String modelName) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Long, Source> entry : context.entrySet()) {
            Long id = entry.getKey();
            Source s = entry.getValue();
            InheritanceConversions.Kind kind = c.kind(id);
            if (kind == InheritanceConversions.Kind.NONE) continue;
            InheritanceConversions.Role role = c.role(id);
            CheckStatus status;
            String code;
            String reason;
            List<String> notes = new ArrayList<>();
            String logical = kind == InheritanceConversions.Kind.SINGLE_TABLE ? Messages.get("A형(단일 테이블 통합)")
                    : kind == InheritanceConversions.Kind.TABLE_PER_CONCRETE ? Messages.get("C형(말단 서브타입별 테이블)") : Messages.get("맞지 않는 변환 기록");
            String physical = s.tableName() == null ? "-" : s.tableName();
            if (kind == InheritanceConversions.Kind.INCONSISTENT) {
                status = CheckStatus.MISMATCH;
                code = "STRATEGY_STRUCTURE_MISMATCH";
                reason = Messages.get("상속 계층의 그룹 변환 기록이 일부만 있거나 전략·통합 테이블이 섞여 있습니다. 변환 기록과 매핑을 확인해주세요.");
            } else if (role == InheritanceConversions.Role.MERGED) {
                Source root = context.get(c.hierarchy().root(id));
                boolean valid = root != null && root.mappingId() != null && root.tableId().equals(c.integratedTable(id)) && s.mappingId() == null;
                status = valid ? CheckStatus.MATCH : s.mappingId() != null ? CheckStatus.MISMATCH : CheckStatus.UNMAPPED;
                code = valid ? "INHERITANCE_MERGED" : s.mappingId() != null ? "STRATEGY_STRUCTURE_MISMATCH" : "INTEGRATED_TABLE_UNMAPPED";
                reason = valid ? Messages.get("A형 : 루트 통합 테이블 {0} 에 통합되었습니다.", root.tableName())
                        : s.mappingId() != null ? Messages.get("A형 통합 계층의 서브타입이 따로 테이블에 매핑되어 있습니다.")
                        : Messages.get("A형 통합 테이블에 루트 엔터티가 매핑되어 있지 않습니다.");
                physical = root == null || root.tableName() == null ? "-" : root.tableName();
                if (valid) notes.add("SUBTYPE_SCOPE_NOT_ENFORCED");
            } else if (role == InheritanceConversions.Role.DISTRIBUTED) {
                List<Long> under = c.leavesUnder(id);
                boolean valid = s.mappingId() == null && !under.isEmpty() && under.stream().allMatch(leaves::containsKey);
                status = valid ? CheckStatus.NOT_COMPARABLE : s.mappingId() != null ? CheckStatus.MISMATCH : CheckStatus.UNMAPPED;
                code = valid ? "INHERITANCE_DISTRIBUTED" : s.mappingId() != null ? "STRATEGY_STRUCTURE_MISMATCH" : "CONCRETE_TABLE_UNMAPPED";
                reason = valid ? Messages.get("C형 : 자기 테이블 없이 말단 테이블 {0} 에 분산되었습니다. 테이블 사이의 PK·보조 식별자 유일성은 DB 에서 보장되지 않습니다.",
                        String.join(", ", under.stream().map(l -> leaves.get(l).tableName()).toList()))
                        : s.mappingId() != null ? Messages.get("C형 분산 엔터티가 테이블에 매핑되어 있습니다.")
                        : Messages.get("C형 말단 엔터티 중 테이블에 매핑되지 않은 것이 있습니다.");
                physical = String.join(", ", under.stream().filter(leaves::containsKey).map(l -> leaves.get(l).tableName()).toList());
                if (valid) notes.add("GLOBAL_KEY_NOT_ENFORCED");
            } else if (kind == InheritanceConversions.Kind.SINGLE_TABLE) {
                // A형 루트 : 통합 테이블에 매핑, 그룹마다 분류 컬럼이 분류 속성의 매핑 컬럼
                Long table = c.integratedTable(id);
                List<String> problems = new ArrayList<>();
                if (s.mappingId() == null) problems.add(Messages.get("루트 엔터티가 통합 테이블에 매핑되어 있지 않습니다."));
                else if (!s.tableId().equals(table)) problems.add(Messages.get("루트 엔터티가 변환 기록의 통합 테이블이 아닌 테이블에 매핑되어 있습니다."));
                // AC-P02 : PK 없는 통합 테이블 (정책 확정 전에 만들었거나 PK 를 지운 경우) — 자동으로 고치지 않고 불일치로 보인다 (매핑된 컬럼 중 PK 가 없으면)
                else if (!tablesWithPk.contains(table)) problems.add(Messages.get("A형 통합 테이블 {0} 에 PK 가 없습니다. 루트 주 식별자를 정하고 PK 를 직접 정리해주세요 (자동으로 고치지 않습니다).", s.tableName()));
                for (var group : c.groupsOfTree(id)) {
                    InheritanceConversions.Record record = c.record(group.id());
                    if (record == null || !Objects.equals(record.classificationColumnId(), classificationColumns.get(group.id()))) {
                        problems.add(Messages.get("그룹의 분류 컬럼이 분류 속성의 매핑 컬럼과 다릅니다 (그룹 ID {0}).", group.id()));
                    }
                }
                status = problems.isEmpty() ? CheckStatus.MATCH : s.mappingId() == null ? CheckStatus.UNMAPPED : CheckStatus.MISMATCH;
                code = problems.isEmpty() ? "INHERITANCE_MERGED" : s.mappingId() == null ? "INTEGRATED_TABLE_UNMAPPED" : "STRATEGY_STRUCTURE_MISMATCH";
                reason = problems.isEmpty() ? Messages.get("A형 통합 테이블 : 계층 {0}개 엔터티, 분류 컬럼 {1}개 (분류 값 범위·완전성·유형별 필수는 DB 에서 강제되지 않습니다).",
                        c.hierarchy().subtree(id).size(), c.groupsOfTree(id).size()) : String.join(" ", problems);
                if (problems.isEmpty()) notes.addAll(List.of("CLASSIFICATION_VALUE_NOT_ENFORCED", "SUBTYPE_SCOPE_NOT_ENFORCED"));
            } else {
                // C형 말단 : 루트 PK·조상 일반 속성의 복제 매핑이 모두 있다
                Leaf leaf = leaves.get(id);
                int want = expected.getOrDefault(id, 0);
                int have = leaf == null ? 0 : leaf.copied().size();
                status = leaf == null ? CheckStatus.UNMAPPED : have >= want ? CheckStatus.MATCH : CheckStatus.UNMAPPED;
                code = leaf == null ? "CONCRETE_TABLE_UNMAPPED" : have >= want ? "INHERITANCE_CONCRETE" : "INHERITED_ATTRIBUTE_MAPPING_MISSING";
                reason = leaf == null ? Messages.get("C형 말단 엔터티가 테이블에 매핑되어 있지 않습니다.")
                        : Messages.get("C형 말단 테이블 : 상속 PK·조상 속성 {0}개 중 {1}개 연결 (다른 말단 테이블과의 PK·보조 식별자 유일성은 DB 에서 보장되지 않습니다).", want, Math.min(have, want));
                if (leaf != null && have >= want) notes.add("GLOBAL_KEY_NOT_ENFORCED");
            }
            // AC-P03 : 이 엔터티가 슈퍼타입인 그룹이 변환 뒤 바뀌었으면 (구성원·배타·완전, A형은 분류 속성·값) 구조가 맞아 보여도 일치로 두지 않는다. 물리 구조는 자동으로 바꾸지 않는다
            if (kind != InheritanceConversions.Kind.INCONSISTENT) {
                List<String> drift = new ArrayList<>();
                boolean unknown = false;
                for (LogicalHierarchy.Group group : c.hierarchy().groupsOf(id)) {
                    ConversionBasis.Result b = basis.get(group.id());
                    if (b == null) continue;
                    if (b.unknown()) unknown = true;
                    else drift.addAll(b.changes());
                }
                if (!drift.isEmpty()) {
                    String text = String.join(" ", drift) + " " + Messages.get("기존 물리 테이블·컬럼·매핑·변환 기록은 자동으로 바뀌지 않았습니다.");
                    if (status == CheckStatus.MATCH || status == CheckStatus.NOT_COMPARABLE) {
                        status = CheckStatus.MISMATCH;
                        code = "CONVERSION_BASIS_CHANGED";
                        reason = text;
                        notes.clear();
                    } else {
                        reason = reason + " " + text;
                    }
                } else if (unknown) {
                    // 비교 기준(변환 당시 그룹 이력)이 없다 : 구조 판정은 그대로, 그룹 변경 여부는 비교하지 않았다고 밝힌다 (추측해 일치로 보지 않는다)
                    reason = reason + " " + Messages.get("변환 당시 그룹 상태 이력이 없어 생성 이후 그룹 구성원·분류 값 변경 여부는 비교하지 않았습니다.");
                }
            }
            rows.add(new Row(CheckType.INHERITANCE + ":" + id + ":", modelId, modelName, s.mappingId(), id, s.entityName(), null, null, s.schemaId(), s.schemaName(), s.tableId(),
                    s.tableName(), null, null, CheckType.INHERITANCE, status, logical, physical, reason, true, null, code, notes));
        }
        return rows;
    }

}
