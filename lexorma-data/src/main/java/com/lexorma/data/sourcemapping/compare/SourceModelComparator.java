package com.lexorma.data.sourcemapping.compare;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.source.SourceCatalogReader;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.sourcemapping.compare.PostgresCompareRules.AutoIncrement;
import com.lexorma.data.sourcemapping.compare.PostgresCompareRules.PgType;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeDiff;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.AttributeResult;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.CompareStatus;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.Note;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectDiff;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.ObjectKind;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.Warning;
import com.lexorma.data.sourcemapping.domain.dto.SourceCompareDto.WarningCode;
import com.lexorma.data.table.ddl.TableDdlGenerator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 매핑된 원본 TABLE ↔ 모델 TABLE 하나의 하위 객체 비교 (M-4d, docs/source-compare-m4d.md 4장). 저장하지 않는 순수 계산이다.
 * <ul>
 *   <li>짝짓기 : 이름(원본 DBMS 의 비교 이름 규칙, SourceCatalogReader.compareName)으로만 같은 객체로 본다. 이름이 다르면 구조가 같아도 같은 객체로 확정하지 않고
 *       원본에만·모델에만 있는 객체로 두며, 구조가 같으면 이름 변경 후보(RENAME_CANDIDATE)로 제안한다</li>
 *   <li>모델이 이름을 저장하지 않는 객체(PK, 컬럼의 고유값 표시로 만든 UNIQUE, 참조 테이블명 약식 입력의 FK)는 구조로 짝짓고 이름은 비교하지 않는다(NOT_COMPARED)</li>
 *   <li>객체 상태 : 다른 속성이 있으면 MODIFIED, 없고 판단하지 못한 속성이 있으면 UNKNOWN, 모두 같으면 SAME. NOT_COMPARED 는 상태에 넣지 않고 따로 보인다</li>
 * </ul>
 */
public final class SourceModelComparator {

    private SourceModelComparator() {
    }

    /** 비교에 필요한 바깥 정보 (이름 규칙, FK 대상 찾기) */
    public interface Context {

        /** 모델 이름(따옴표 없이 쓴 이름)을 원본 DBMS 의 비교 이름으로 */
        String fold(String modelName);

        /** 읽어 둔 모델 컬럼 (FK 대상 TABLE 의 컬럼 포함). 없으면 null */
        CompareModel.Column modelColumn(Long id);

        /** 모델 TABLE 의 표시 이름 (스키마.TABLE). 없으면 null */
        String modelTableName(Long tableId);

        /** 원본 FK 의 참조 대상을 모델 TABLE 로 : 참조 대상 원본 TABLE 이 유효하게 매핑되어 있으면 그 모델 TABLE */
        Target resolve(SourceCatalogReader.ForeignKey foreignKey);
    }

    /** @param resolved 참조 대상 원본 TABLE 이 유효하게 매핑되어 있는지 (아니면 modelTableId 는 null) */
    public record Target(Long modelTableId, boolean resolved) {
    }

    /** 이름 변경 후보 한 쌍 */
    public record Rename(ObjectKind kind, String sourceName, String modelName, List<String> basis, String signature, boolean ambiguous) {
    }

    public record Result(List<ObjectDiff> objects, List<Rename> renames, List<Warning> warnings) {

        /** 차이가 있는 객체 수 (SAME·UNKNOWN 제외) */
        public int differences() {
            return (int) objects.stream().filter(o -> o.status() != CompareStatus.SAME && o.status() != CompareStatus.UNKNOWN).count();
        }

        public int unknowns() {
            return (int) objects.stream().filter(o -> o.status() == CompareStatus.UNKNOWN).count();
        }

        /** TABLE 의 비교 상태 : 차이가 있으면 MODIFIED, 없고 판단하지 못한 것이 있으면 UNKNOWN, 아니면 SAME */
        public CompareStatus status() {
            return differences() > 0 ? CompareStatus.MODIFIED : unknowns() > 0 ? CompareStatus.UNKNOWN : CompareStatus.SAME;
        }
    }

    public static Result compare(SourceCatalogReader.TableDetail source, CompareModel.Table model, Context context) {
        return compare(source, null, null, model, context);
    }

    /**
     * @param sourceTableName    원본 TABLE 이름 (TABLE 객체의 표시용)
     * @param sourceTableComment 원본 TABLE 의 COMMENT : 모델 TABLE 의 원본 COMMENT 칸과 비교한다 (업무 설명과는 비교하지 않는다)
     */
    public static Result compare(SourceCatalogReader.TableDetail source, String sourceTableName, String sourceTableComment, CompareModel.Table model, Context context) {
        Run run = new Run(source, model, context);
        run.table(sourceTableName, sourceTableComment);
        run.columns();
        run.primaryKey();
        run.uniques();
        run.checks();
        run.foreignKeys();
        run.indexes();
        return new Result(List.copyOf(run.objects), List.copyOf(run.renames), List.copyOf(run.warnings));
    }

    // ------------------------------------------------------------------ 공통

    static AttributeDiff attribute(String name, Object source, Object model, AttributeResult result, Note note) {
        return new AttributeDiff(name, text(source), text(model), result, note);
    }

    /** 한쪽에만 있는 객체의 속성 : 비교 결과가 없다 (null) */
    static AttributeDiff value(String name, Object source, Object model) {
        return new AttributeDiff(name, text(source), text(model), null, null);
    }

    static AttributeResult equal(Object source, Object model) {
        return Objects.equals(source, model) ? AttributeResult.SAME : AttributeResult.DIFFERENT;
    }

    static CompareStatus status(List<AttributeDiff> attributes) {
        if (attributes.stream().anyMatch(a -> a.result() == AttributeResult.DIFFERENT)) return CompareStatus.MODIFIED;
        if (attributes.stream().anyMatch(a -> a.result() == AttributeResult.UNKNOWN)) return CompareStatus.UNKNOWN;
        return CompareStatus.SAME;
    }

    /** 식 비교 : 정규화해 같으면 SAME, 한쪽만 있으면 DIFFERENT, 둘 다 리터럴·키워드 하나면 DIFFERENT, 그 밖은 판단하지 않는다(UNKNOWN) */
    static AttributeResult expressions(String source, String model) {
        String s = PostgresCompareRules.expression(source);
        String m = PostgresCompareRules.expression(model);
        if (Objects.equals(s, m)) return AttributeResult.SAME;
        if (s == null || m == null) return AttributeResult.DIFFERENT;
        if (PostgresCompareRules.simple(s) && PostgresCompareRules.simple(m)) return AttributeResult.DIFFERENT;
        return AttributeResult.UNKNOWN;
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) return String.join(", ", list.stream().map(v -> v == null ? "?" : String.valueOf(v)).toList());
        return String.valueOf(value);
    }

    private static String ruleOrDefault(String rule) {
        return rule == null ? "NO ACTION" : rule;
    }

    private static final class Run {

        final SourceCatalogReader.TableDetail source;
        final CompareModel.Table model;
        final Context context;
        final List<ObjectDiff> objects = new ArrayList<>();
        final List<Rename> renames = new ArrayList<>();
        final List<Warning> warnings = new ArrayList<>();
        final Map<Long, CompareModel.Column> columnById = new LinkedHashMap<>();
        final Map<Long, String> folded = new LinkedHashMap<>();

        Run(SourceCatalogReader.TableDetail source, CompareModel.Table model, Context context) {
            this.source = source;
            this.model = model;
            this.context = context;
            for (CompareModel.Column column : model.columns()) {
                columnById.put(column.id(), column);
                folded.put(column.id(), context.fold(column.name()));
            }
        }

        // ------------------------------------------------------------------ TABLE

        /** TABLE 자체 : 원본 COMMENT (어느 쪽에든 값이 있을 때만) */
        void table(String sourceName, String sourceComment) {
            if (sourceComment == null && model.sourceComment() == null) return;
            List<AttributeDiff> attributes = List.of(attribute("comment", sourceComment, model.sourceComment(), equal(sourceComment, model.sourceComment()),
                    Note.SOURCE_COMMENT));
            objects.add(new ObjectDiff(ObjectKind.TABLE, sourceName, model.name(), status(attributes), attributes, List.of(), false));
        }

        // ------------------------------------------------------------------ 컬럼

        void columns() {
            List<SourceCatalogReader.Column> sources = source.columns().stream()
                    .sorted(Comparator.comparing(SourceCatalogReader.Column::ordinal, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
            List<CompareModel.Column> models = orderedModelColumns();
            Map<String, List<CompareModel.Column>> byName = new LinkedHashMap<>();
            for (CompareModel.Column column : models) byName.computeIfAbsent(folded.get(column.id()), k -> new ArrayList<>()).add(column);

            List<SourceCatalogReader.Column> pairedSource = new ArrayList<>();
            List<SourceCatalogReader.Column> onlySource = new ArrayList<>();
            Set<Long> pairedModel = new HashSet<>();
            Set<Long> conflicted = new HashSet<>();
            for (Map.Entry<String, List<CompareModel.Column>> entry : byName.entrySet()) {
                if (entry.getValue().size() < 2) continue;
                warnings.add(new Warning(WarningCode.DUPLICATE_MODEL_NAME, List.of(String.join(", ", entry.getValue().stream().map(CompareModel.Column::name).toList()))));
                entry.getValue().forEach(c -> conflicted.add(c.id()));
            }
            for (SourceCatalogReader.Column column : sources) {
                List<CompareModel.Column> same = byName.getOrDefault(column.compareName(), List.of());
                if (same.size() == 1) {
                    pairedSource.add(column);
                    pairedModel.add(same.get(0).id());
                } else if (same.size() > 1) {
                    objects.add(new ObjectDiff(ObjectKind.COLUMN, column.name(), String.join(", ", same.stream().map(CompareModel.Column::name).toList()), CompareStatus.CONFLICT,
                            sourceColumnValues(column), List.of(), true));
                } else {
                    onlySource.add(column);
                }
            }
            // 양쪽에 다 있는 컬럼 사이의 상대 순서 (원본 위치 번호의 빈 자리, 한쪽에만 있는 컬럼은 순서 비교에 넣지 않는다)
            List<Long> modelOrder = models.stream().map(CompareModel.Column::id).filter(pairedModel::contains).toList();
            for (int i = 0; i < pairedSource.size(); i++) {
                SourceCatalogReader.Column s = pairedSource.get(i);
                CompareModel.Column m = byName.get(s.compareName()).get(0);
                objects.add(column(s, m, i + 1, modelOrder.indexOf(m.id()) + 1));
            }
            List<CompareModel.Column> onlyModel = models.stream().filter(c -> !pairedModel.contains(c.id()) && !conflicted.contains(c.id())).toList();
            for (CompareModel.Column column : models) {
                if (conflicted.contains(column.id()) && sources.stream().noneMatch(s -> s.compareName().equals(folded.get(column.id())))) {
                    objects.add(new ObjectDiff(ObjectKind.COLUMN, null, column.name(), CompareStatus.CONFLICT, modelColumnValues(column), List.of(), true));
                }
            }
            candidates(ObjectKind.COLUMN, onlySource, onlyModel, (s, m) -> columnSignature(s).equals(columnSignature(m)),
                    SourceCatalogReader.Column::name, CompareModel.Column::name, this::columnSignature, List.of("dataType", "length", "precision", "scale", "nullable"),
                    this::sourceColumnValues, this::modelColumnValues);
        }

        List<CompareModel.Column> orderedModelColumns() {
            return model.columns().stream().sorted(Comparator.comparing(CompareModel.Column::sortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CompareModel.Column::id)).toList();
        }

        ObjectDiff column(SourceCatalogReader.Column s, CompareModel.Column m, int sourceRank, int modelRank) {
            List<AttributeDiff> attributes = new ArrayList<>();
            PgType st = PostgresCompareRules.type(s.dataType());
            String mapped = mappedType(m);
            PgType mt = PostgresCompareRules.type(mapped);
            typeWarnings(m, mapped, mt);
            AttributeResult typeResult;
            Note typeNote = null;
            if (st == null || mt == null) {
                typeResult = AttributeResult.UNKNOWN;
            } else if (st.known() && mt.known()) {
                typeResult = equal(st.baseDisplay(), mt.baseDisplay());
            } else {
                typeResult = st.display().equals(mt.display()) ? AttributeResult.SAME : AttributeResult.UNKNOWN;
                typeNote = Note.UNSUPPORTED_TYPE;
            }
            attributes.add(attribute("dataType", s.dataType(), modelTypeText(m, mapped), typeResult, typeNote));
            if (st != null && mt != null && st.known() && mt.known()) {
                if (st.length() != null || mt.length() != null) attributes.add(attribute("length", st.length(), mt.length(), equal(st.length(), mt.length()), null));
                if (st.precision() != null || mt.precision() != null) {
                    attributes.add(attribute("precision", st.precision(), mt.precision(), equal(st.precision(), mt.precision()), null));
                }
                if (st.scale() != null || mt.scale() != null) attributes.add(attribute("scale", st.scale(), mt.scale(), equal(st.scale(), mt.scale()), null));
            }
            boolean modelNullable = !TableDdlGenerator.isNotNull(columnInfo(m));
            attributes.add(s.nullable() == null ? attribute("nullable", null, nullText(modelNullable), AttributeResult.UNKNOWN, Note.NULLABLE_UNKNOWN)
                    : attribute("nullable", nullText(s.nullable()), nullText(modelNullable), equal(s.nullable(), modelNullable), null));

            AutoIncrement sourceAuto = sourceAuto(s);
            AutoIncrement modelAuto = modelAuto(m, mt);
            if (sourceAuto != null || modelAuto != null) attributes.add(attribute("autoIncrement", sourceAuto, modelAuto, equal(sourceAuto, modelAuto), null));
            String sourceDefault = PostgresCompareRules.identity(s.defaultValue()) != null ? null : s.defaultValue();
            String modelDefault = m.defaultValue() == null || m.defaultValue().isBlank() || PostgresCompareRules.identity(m.defaultValue()) != null ? null : m.defaultValue();
            if (sourceAuto == AutoIncrement.SEQUENCE && modelAuto == AutoIncrement.SEQUENCE && modelDefault == null && mt != null && mt.serial()) {
                attributes.add(attribute("default", sourceDefault, mapped, AttributeResult.SAME, Note.SERIAL_DEFAULT));
            } else if (sourceDefault != null || modelDefault != null) {
                String literal = modelDefault == null ? null : TableDdlGenerator.defaultLiteral(modelDefault);
                AttributeResult result = expressions(sourceDefault, literal);
                attributes.add(attribute("default", sourceDefault, literal, result, result == AttributeResult.UNKNOWN ? Note.EXPRESSION : null));
            }
            attributes.add(attribute("order", sourceRank, modelRank, equal(sourceRank, modelRank), Note.RELATIVE_ORDER));
            if (s.comment() != null || m.sourceComment() != null) {
                attributes.add(attribute("comment", s.comment(), m.sourceComment(), equal(s.comment(), m.sourceComment()), Note.SOURCE_COMMENT));
            }
            return new ObjectDiff(ObjectKind.COLUMN, s.name(), m.name(), status(attributes), attributes, List.of(), false);
        }

        void typeWarnings(CompareModel.Column m, String mapped, PgType mt) {
            if (m.dataType() == null || m.dataType().isBlank()) {
                warnings.add(new Warning(WarningCode.MODEL_TYPE_MISSING, List.of(m.name())));
                return;
            }
            PgType raw = PostgresCompareRules.type(m.dataType());
            if (raw != null && mt != null && !raw.baseDisplay().equals(mt.baseDisplay())) {
                warnings.add(new Warning(WarningCode.MODEL_TYPE_CONVERTED, List.of(m.name(), m.dataType(), mapped)));
            }
        }

        String modelTypeText(CompareModel.Column m, String mapped) {
            if (m.dataType() == null || m.dataType().isBlank()) return mapped;
            String raw = m.dataType().trim();
            return raw.equalsIgnoreCase(mapped) ? raw : raw + " → " + mapped;
        }

        String mappedType(CompareModel.Column m) {
            return TableDdlGenerator.mapType(DbType.POSTGRESQL, columnInfo(m), new ArrayList<>());
        }

        static ColumnInfo columnInfo(CompareModel.Column m) {
            return ColumnInfo.builder().columnName(m.name()).dataType(m.dataType()).maxLength(m.maxLength()).precision(m.precision()).scale(m.scale())
                    .isNullable(m.nullable()).isRequired(m.required()).isPk(m.pk()).isUnique(m.unique()).defaultValue(m.defaultValue()).build();
        }

        static String nullText(boolean nullable) {
            return nullable ? "NULL" : "NOT NULL";
        }

        static AutoIncrement sourceAuto(SourceCatalogReader.Column s) {
            AutoIncrement identity = PostgresCompareRules.identity(s.defaultValue());
            if (identity != null) return identity;
            return PostgresCompareRules.sequenceDefault(s.defaultValue()) ? AutoIncrement.SEQUENCE : null;
        }

        static AutoIncrement modelAuto(CompareModel.Column m, PgType mt) {
            AutoIncrement identity = PostgresCompareRules.identity(m.defaultValue());
            if (identity != null) return identity;
            if ((mt != null && mt.serial()) || PostgresCompareRules.sequenceDefault(m.defaultValue())) return AutoIncrement.SEQUENCE;
            return null;
        }

        /** 이름 변경 후보의 비교 기준 : 타입(길이·정밀도·소수 포함)과 NULL 허용 */
        String columnSignature(SourceCatalogReader.Column s) {
            PgType type = PostgresCompareRules.type(s.dataType());
            return (type == null ? "?" : type.display()) + " " + (s.nullable() == null ? "?" : nullText(s.nullable()));
        }

        String columnSignature(CompareModel.Column m) {
            PgType type = PostgresCompareRules.type(mappedType(m));
            return (type == null ? "?" : type.display()) + " " + nullText(!TableDdlGenerator.isNotNull(columnInfo(m)));
        }

        List<AttributeDiff> sourceColumnValues(SourceCatalogReader.Column s) {
            List<AttributeDiff> values = new ArrayList<>();
            values.add(value("dataType", s.dataType(), null));
            values.add(value("nullable", s.nullable() == null ? null : nullText(s.nullable()), null));
            if (s.defaultValue() != null) values.add(value("default", s.defaultValue(), null));
            if (s.comment() != null) values.add(value("comment", s.comment(), null));
            return values;
        }

        List<AttributeDiff> modelColumnValues(CompareModel.Column m) {
            String mapped = mappedType(m);
            List<AttributeDiff> values = new ArrayList<>();
            values.add(value("dataType", null, modelTypeText(m, mapped)));
            values.add(value("nullable", null, nullText(!TableDdlGenerator.isNotNull(columnInfo(m)))));
            if (m.defaultValue() != null && !m.defaultValue().isBlank()) values.add(value("default", null, TableDdlGenerator.defaultLiteral(m.defaultValue())));
            return values;
        }

        String columnName(Long id) {
            CompareModel.Column column = columnById.get(id);
            if (column == null) column = context.modelColumn(id);
            return column == null ? null : column.name();
        }

        String foldColumn(Long id) {
            String name = columnName(id);
            return name == null ? null : context.fold(name);
        }

        // ------------------------------------------------------------------ PK

        void primaryKey() {
            SourceCatalogReader.Constraint sourcePk = source.constraints().stream().filter(c -> "PRIMARY_KEY".equals(c.type())).findFirst().orElse(null);
            List<CompareModel.Column> pkColumns = orderedModelColumns().stream().filter(c -> Boolean.TRUE.equals(c.pk())).toList();
            List<String> modelColumns = pkColumns.stream().map(c -> folded.get(c.id())).toList();
            String modelName = "PK_" + model.name();
            if (sourcePk == null && pkColumns.isEmpty()) return;
            if (sourcePk == null) {
                objects.add(new ObjectDiff(ObjectKind.PRIMARY_KEY, null, modelName, CompareStatus.ADDED_IN_MODEL, List.of(value("columns", null, modelColumns)), List.of(), false));
                return;
            }
            if (pkColumns.isEmpty()) {
                objects.add(new ObjectDiff(ObjectKind.PRIMARY_KEY, sourcePk.name(), null, CompareStatus.ADDED_IN_SOURCE, List.of(value("columns", sourcePk.columns(), null)),
                        List.of(), false));
                return;
            }
            List<AttributeDiff> attributes = new ArrayList<>();
            attributes.add(attribute("columns", sourcePk.columns(), modelColumns, equal(sourcePk.columns(), modelColumns), null));
            attributes.add(generatedName(sourcePk.name(), modelName));
            objects.add(new ObjectDiff(ObjectKind.PRIMARY_KEY, sourcePk.name(), modelName, status(attributes), attributes, List.of(), false));
        }

        /** 모델이 DDL 을 만들 때 정하는 이름 : 같으면 SAME, 다르면 비교하지 않는다 */
        AttributeDiff generatedName(String sourceName, String modelName) {
            boolean same = Objects.equals(sourceName, context.fold(modelName));
            return attribute("name", sourceName, modelName, same ? AttributeResult.SAME : AttributeResult.NOT_COMPARED, same ? null : Note.GENERATED_NAME);
        }

        // ------------------------------------------------------------------ UNIQUE

        /** @param generated 컬럼의 고유값 표시로 만든 UNIQUE (모델에 이름이 없다) */
        record ModelKey(String name, boolean generated, List<String> columns) {
        }

        void uniques() {
            List<SourceCatalogReader.Constraint> sources = new ArrayList<>(source.constraints().stream().filter(c -> "UNIQUE".equals(c.type())).toList());
            List<ModelKey> models = new ArrayList<>();
            Set<Set<String>> seen = new HashSet<>();
            List<String> pk = orderedModelColumns().stream().filter(c -> Boolean.TRUE.equals(c.pk())).map(c -> folded.get(c.id())).toList();
            if (!pk.isEmpty()) seen.add(Set.copyOf(pk));
            // DDL 생성기와 같은 구성 : 고유값 표시 컬럼(PK 제외) → 이름 있는 UNIQUE (이미 있는 구성은 다시 만들지 않는다)
            for (CompareModel.Column column : orderedModelColumns()) {
                if (Boolean.TRUE.equals(column.unique()) && !Boolean.TRUE.equals(column.pk())) {
                    seen.add(Set.of(folded.get(column.id())));
                    models.add(new ModelKey("UK_" + model.name() + "_" + column.name(), true, List.of(folded.get(column.id()))));
                }
            }
            for (CompareModel.Constraint constraint : model.constraints()) {
                if (!"UNIQUE".equals(constraint.type())) continue;
                List<String> columns = constraint.columnIds().stream().map(this::foldColumn).toList();
                if (columns.contains(null) || !seen.add(new HashSet<>(columns))) continue;
                models.add(new ModelKey(constraint.name(), false, columns));
            }
            // 1. 이름으로
            for (ModelKey key : List.copyOf(models)) {
                SourceCatalogReader.Constraint match = sources.stream().filter(s -> s.name() != null && s.name().equals(context.fold(key.name()))).findFirst().orElse(null);
                if (match == null) continue;
                List<AttributeDiff> attributes = List.of(attribute("columns", match.columns(), key.columns(), equal(match.columns(), key.columns()), null));
                objects.add(new ObjectDiff(ObjectKind.UNIQUE, match.name(), key.name(), status(attributes), attributes, List.of(), false));
                sources.remove(match);
                models.remove(key);
            }
            // 2. 이름이 없는 모델 UNIQUE 는 구성으로
            for (ModelKey key : List.copyOf(models)) {
                if (!key.generated()) continue;
                SourceCatalogReader.Constraint match = sources.stream().filter(s -> s.columns().equals(key.columns())).findFirst().orElse(null);
                if (match == null) continue;
                List<AttributeDiff> attributes = List.of(attribute("columns", match.columns(), key.columns(), AttributeResult.SAME, null), generatedName(match.name(), key.name()));
                objects.add(new ObjectDiff(ObjectKind.UNIQUE, match.name(), key.name(), status(attributes), attributes, List.of(), false));
                sources.remove(match);
                models.remove(key);
            }
            // 3. 남은 것 : 같은 구성이면 이름 변경 후보
            candidates(ObjectKind.UNIQUE, sources, models, (s, m) -> !m.generated() && s.columns().equals(m.columns()), SourceCatalogReader.Constraint::name, ModelKey::name,
                    s -> String.join(", ", s.columns()), List.of("columns"), s -> List.of(value("columns", s.columns(), null)), m -> List.of(value("columns", null, m.columns())));
        }

        // ------------------------------------------------------------------ CHECK

        void checks() {
            List<SourceCatalogReader.Constraint> sources = new ArrayList<>(source.constraints().stream().filter(c -> "CHECK".equals(c.type())).toList());
            List<CompareModel.Constraint> models = new ArrayList<>(model.constraints().stream().filter(c -> "CHECK".equals(c.type())).toList());
            for (CompareModel.Constraint constraint : List.copyOf(models)) {
                SourceCatalogReader.Constraint match = sources.stream().filter(s -> s.name() != null && s.name().equals(context.fold(constraint.name()))).findFirst().orElse(null);
                if (match == null) continue;
                AttributeResult result = expressions(match.checkExpression(), constraint.checkExpression());
                List<AttributeDiff> attributes = List.of(attribute("expression", match.checkExpression(), constraint.checkExpression(), result,
                        result == AttributeResult.UNKNOWN ? Note.EXPRESSION : null));
                objects.add(new ObjectDiff(ObjectKind.CHECK, match.name(), constraint.name(), status(attributes), attributes, List.of(), false));
                sources.remove(match);
                models.remove(constraint);
            }
            candidates(ObjectKind.CHECK, sources, models,
                    (s, m) -> expressions(s.checkExpression(), m.checkExpression()) == AttributeResult.SAME, SourceCatalogReader.Constraint::name, CompareModel.Constraint::name,
                    s -> PostgresCompareRules.expression(s.checkExpression()), List.of("expression"),
                    s -> List.of(value("expression", s.checkExpression(), null)), m -> List.of(value("expression", null, m.checkExpression())));
        }

        // ------------------------------------------------------------------ FK

        /** 모델 FK 를 비교할 수 있는 값으로 */
        record ModelFk(CompareModel.Relation relation, List<String> columns, List<String> referencedColumns) {
        }

        void foreignKeys() {
            List<SourceCatalogReader.ForeignKey> sources = new ArrayList<>(source.foreignKeys());
            List<ModelFk> models = new ArrayList<>();
            for (CompareModel.Relation relation : model.relations()) {
                models.add(new ModelFk(relation, relation.columnIds().stream().map(this::foldColumn).toList(), relation.targetColumnIds().stream().map(this::foldColumn).toList()));
            }
            Map<SourceCatalogReader.ForeignKey, Target> targets = new LinkedHashMap<>();
            sources.forEach(fk -> targets.put(fk, context.resolve(fk)));
            // 1. 이름으로
            for (ModelFk fk : List.copyOf(models)) {
                String name = fk.relation().name();
                SourceCatalogReader.ForeignKey match = name == null ? null
                        : sources.stream().filter(s -> s.name() != null && s.name().equals(context.fold(name))).findFirst().orElse(null);
                if (match == null) continue;
                List<AttributeDiff> attributes = foreignKeyAttributes(match, targets.get(match), fk);
                objects.add(new ObjectDiff(ObjectKind.FOREIGN_KEY, match.name(), name, status(attributes), attributes, List.of(), false));
                sources.remove(match);
                models.remove(fk);
            }
            // 2. 이름을 모델이 만드는 FK(참조 테이블명 약식 입력)는 구성으로
            for (ModelFk fk : List.copyOf(models)) {
                if (!generatedFk(fk)) continue;
                SourceCatalogReader.ForeignKey match = sources.stream().filter(s -> sameForeignKey(s, targets.get(s), fk)).findFirst().orElse(null);
                if (match == null) continue;
                List<AttributeDiff> attributes = new ArrayList<>(foreignKeyAttributes(match, targets.get(match), fk));
                attributes.add(generatedName(match.name(), fk.relation().name() == null ? "FK" : fk.relation().name()));
                objects.add(new ObjectDiff(ObjectKind.FOREIGN_KEY, match.name(), fk.relation().name(), status(attributes), attributes, List.of(), false));
                sources.remove(match);
                models.remove(fk);
            }
            candidates(ObjectKind.FOREIGN_KEY, sources, models, (s, m) -> !generatedFk(m) && sameForeignKey(s, targets.get(s), m), SourceCatalogReader.ForeignKey::name,
                    m -> m.relation().name(), s -> String.join(", ", s.columns()) + " → " + s.referencedSchema() + "." + s.referencedTable(),
                    List.of("columns", "referencedTable", "referencedColumns"), this::sourceForeignKeyValues, this::modelForeignKeyValues);
        }

        static boolean generatedFk(ModelFk fk) {
            return fk.relation().name() == null || "REF_COLUMN".equals(fk.relation().source());
        }

        static boolean sameForeignKey(SourceCatalogReader.ForeignKey s, Target target, ModelFk m) {
            return target.resolved() && Objects.equals(target.modelTableId(), m.relation().targetTableId()) && s.columns().equals(m.columns())
                    && s.referencedColumns().equals(m.referencedColumns());
        }

        List<AttributeDiff> foreignKeyAttributes(SourceCatalogReader.ForeignKey s, Target target, ModelFk m) {
            List<AttributeDiff> attributes = new ArrayList<>();
            String sourceTarget = s.referencedSchema() + "." + s.referencedTable();
            String modelTarget = context.modelTableName(m.relation().targetTableId());
            attributes.add(target.resolved()
                    ? attribute("referencedTable", sourceTarget, modelTarget, equal(target.modelTableId(), m.relation().targetTableId()), null)
                    : attribute("referencedTable", sourceTarget, modelTarget, AttributeResult.UNKNOWN, Note.UNRESOLVED_TARGET));
            attributes.add(attribute("columns", s.columns(), m.columns(), equal(s.columns(), m.columns()), null));
            attributes.add(attribute("referencedColumns", s.referencedColumns(), m.referencedColumns(), equal(s.referencedColumns(), m.referencedColumns()), null));
            attributes.add(rule("deleteRule", s.deleteRule()));
            attributes.add(rule("updateRule", s.updateRule()));
            return attributes;
        }

        /** FK 규칙 : 모델에 칸이 없고 DDL 은 기본(NO ACTION) — 원본도 기본이면 같고, 아니면 판단할 수 없다 */
        static AttributeDiff rule(String name, String sourceRule) {
            String rule = ruleOrDefault(sourceRule);
            return "NO ACTION".equals(rule) ? attribute(name, rule, "NO ACTION", AttributeResult.SAME, Note.DEFAULT_RULE)
                    : attribute(name, rule, null, AttributeResult.UNKNOWN, Note.MODEL_HAS_NO_FIELD);
        }

        List<AttributeDiff> sourceForeignKeyValues(SourceCatalogReader.ForeignKey s) {
            return List.of(value("referencedTable", s.referencedSchema() + "." + s.referencedTable(), null), value("columns", s.columns(), null),
                    value("referencedColumns", s.referencedColumns(), null), value("deleteRule", ruleOrDefault(s.deleteRule()), null),
                    value("updateRule", ruleOrDefault(s.updateRule()), null));
        }

        List<AttributeDiff> modelForeignKeyValues(ModelFk m) {
            return List.of(value("referencedTable", null, context.modelTableName(m.relation().targetTableId())), value("columns", null, m.columns()),
                    value("referencedColumns", null, m.referencedColumns()));
        }

        // ------------------------------------------------------------------ 인덱스

        /** 인덱스 구성 요소 하나 : 컬럼(비교 이름) 또는 식, 정렬 (null = 기본) */
        record Member(String column, String expression, String sort) {
            String display() {
                return (column != null ? column : "(" + expression + ")") + (sort == null ? "" : " " + sort);
            }
        }

        record IndexValue(String name, String type, boolean unique, List<Member> keys, List<String> includes, String predicate) {
        }

        void indexes() {
            Set<String> constraintNames = new HashSet<>();
            source.constraints().stream().filter(c -> !"CHECK".equals(c.type()) && c.name() != null).forEach(c -> constraintNames.add(c.name()));
            List<IndexValue> sources = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (SourceCatalogReader.Index index : source.indexes()) {
                // PostgreSQL 의 PK·UNIQUE 제약조건은 같은 이름의 고유 인덱스를 만든다 : 제약조건으로 비교했으므로 인덱스로 다시 비교하지 않는다
                if (index.unique() && constraintNames.contains(index.name())) {
                    skipped.add(index.name());
                    continue;
                }
                sources.add(sourceIndex(index));
            }
            if (!skipped.isEmpty()) warnings.add(new Warning(WarningCode.CONSTRAINT_INDEX, List.of(String.join(", ", skipped))));
            List<IndexValue> models = new ArrayList<>(model.indexes().stream().map(this::modelIndex).toList());
            for (IndexValue modelIndex : List.copyOf(models)) {
                IndexValue match = sources.stream().filter(s -> s.name() != null && s.name().equals(context.fold(modelIndex.name()))).findFirst().orElse(null);
                if (match == null) continue;
                List<AttributeDiff> attributes = indexAttributes(match, modelIndex);
                objects.add(new ObjectDiff(ObjectKind.INDEX, match.name(), modelIndex.name(), status(attributes), attributes, List.of(), false));
                sources.remove(match);
                models.remove(modelIndex);
            }
            candidates(ObjectKind.INDEX, sources, models, (s, m) -> status(indexAttributes(s, m)) == CompareStatus.SAME, IndexValue::name, IndexValue::name,
                    s -> (s.unique() ? "UNIQUE " : "") + String.join(", ", s.keys().stream().map(Member::display).toList()), List.of("unique", "indexType", "keys", "include", "predicate"),
                    s -> indexValues(s, true), m -> indexValues(m, false));
        }

        IndexValue sourceIndex(SourceCatalogReader.Index index) {
            List<Member> keys = new ArrayList<>();
            List<String> includes = new ArrayList<>();
            for (int i = 0; i < index.columns().size(); i++) {
                String sort = index.sorts() == null ? null : index.sorts().get(i);
                if ("INCLUDE".equals(sort)) {
                    includes.add(index.columns().get(i));
                    continue;
                }
                keys.add(new Member(index.columns().get(i), index.expressions() == null ? null : index.expressions().get(i), sort));
            }
            return new IndexValue(index.name(), index.indexType() == null ? "BTREE" : index.indexType().toUpperCase(Locale.ROOT), index.unique(), keys, includes, index.predicate());
        }

        IndexValue modelIndex(CompareModel.Index index) {
            List<Member> keys = new ArrayList<>();
            List<String> includes = new ArrayList<>();
            for (CompareModel.IndexMember member : index.members()) {
                String column = member.columnId() == null ? null : foldColumn(member.columnId());
                if ("INCLUDE".equals(member.sort())) includes.add(column);
                else keys.add(new Member(column, member.columnId() == null ? member.expression() : null, member.sort()));
            }
            String type = index.indexType() == null || index.indexType().isBlank() ? "BTREE" : index.indexType().trim().toUpperCase(Locale.ROOT);
            return new IndexValue(index.name(), type, index.unique(), keys, includes, index.condition());
        }

        List<AttributeDiff> indexAttributes(IndexValue s, IndexValue m) {
            List<AttributeDiff> attributes = new ArrayList<>();
            attributes.add(attribute("unique", s.unique(), m.unique(), equal(s.unique(), m.unique()), null));
            attributes.add(attribute("indexType", s.type(), m.type(), equal(s.type(), m.type()), null));
            attributes.add(keys(s.keys(), m.keys()));
            if (!s.includes().isEmpty() || !m.includes().isEmpty()) attributes.add(attribute("include", s.includes(), m.includes(), equal(s.includes(), m.includes()), null));
            if (s.predicate() != null || m.predicate() != null) {
                AttributeResult result = expressions(s.predicate(), m.predicate());
                attributes.add(attribute("predicate", s.predicate(), m.predicate(), result, result == AttributeResult.UNKNOWN ? Note.EXPRESSION : null));
            }
            return attributes;
        }

        /** 검색 키 : 순서대로 컬럼은 이름·정렬, 식은 정규화한 식·정렬. 식만 다르면 판단하지 않는다 */
        static AttributeDiff keys(List<Member> s, List<Member> m) {
            AttributeResult result = AttributeResult.SAME;
            if (s.size() != m.size()) {
                result = AttributeResult.DIFFERENT;
            } else {
                for (int i = 0; i < s.size() && result != AttributeResult.DIFFERENT; i++) {
                    Member a = s.get(i);
                    Member b = m.get(i);
                    if (!Objects.equals(a.sort(), b.sort()) || (a.column() == null) != (b.column() == null)) {
                        result = AttributeResult.DIFFERENT;
                    } else if (a.column() != null) {
                        if (!a.column().equals(b.column())) result = AttributeResult.DIFFERENT;
                    } else if (expressions(a.expression(), b.expression()) != AttributeResult.SAME) {
                        result = AttributeResult.UNKNOWN;
                    }
                }
            }
            return attribute("keys", s.stream().map(Member::display).toList(), m.stream().map(Member::display).toList(), result,
                    result == AttributeResult.UNKNOWN ? Note.EXPRESSION : null);
        }

        static List<AttributeDiff> indexValues(IndexValue index, boolean source) {
            List<AttributeDiff> values = new ArrayList<>();
            values.add(source ? value("unique", index.unique(), null) : value("unique", null, index.unique()));
            values.add(source ? value("indexType", index.type(), null) : value("indexType", null, index.type()));
            List<String> keys = index.keys().stream().map(Member::display).toList();
            values.add(source ? value("keys", keys, null) : value("keys", null, keys));
            if (!index.includes().isEmpty()) values.add(source ? value("include", index.includes(), null) : value("include", null, index.includes()));
            if (index.predicate() != null) values.add(source ? value("predicate", index.predicate(), null) : value("predicate", null, index.predicate()));
            return values;
        }

        // ------------------------------------------------------------------ 한쪽에만 있는 객체와 이름 변경 후보

        /**
         * 짝을 찾지 못한 객체 : 맞은편에 구조가 같은 것이 있으면 RENAME_CANDIDATE (후보가 여럿이거나 맞은편에도 다른 후보가 있으면 ambiguous), 없으면 ADDED_IN_SOURCE·ADDED_IN_MODEL.
         * 후보는 제안일 뿐이라 짝으로 확정하지 않는다.
         */
        <S, M> void candidates(ObjectKind kind, List<S> sources, List<M> models, BiPredicate<S, M> match, Function<S, String> sourceName, Function<M, String> modelName,
                               Function<S, String> signature, List<String> basis, Function<S, List<AttributeDiff>> sourceValues, Function<M, List<AttributeDiff>> modelValues) {
            Map<S, List<M>> bySource = new LinkedHashMap<>();
            Map<M, List<S>> byModel = new LinkedHashMap<>();
            for (S s : sources) {
                for (M m : models) {
                    if (!match.test(s, m)) continue;
                    bySource.computeIfAbsent(s, k -> new ArrayList<>()).add(m);
                    byModel.computeIfAbsent(m, k -> new ArrayList<>()).add(s);
                }
            }
            for (S s : sources) {
                List<M> found = bySource.getOrDefault(s, List.of());
                boolean ambiguous = found.size() > 1 || found.stream().anyMatch(m -> byModel.get(m).size() > 1);
                objects.add(new ObjectDiff(kind, sourceName.apply(s), null, found.isEmpty() ? CompareStatus.ADDED_IN_SOURCE : CompareStatus.RENAME_CANDIDATE,
                        sourceValues.apply(s), found.stream().map(modelName).toList(), ambiguous));
                for (M m : found) {
                    boolean pairAmbiguous = found.size() > 1 || byModel.get(m).size() > 1;
                    renames.add(new Rename(kind, sourceName.apply(s), modelName.apply(m), basis, signature.apply(s), pairAmbiguous));
                }
            }
            for (M m : models) {
                List<S> found = byModel.getOrDefault(m, List.of());
                boolean ambiguous = found.size() > 1 || found.stream().anyMatch(s -> bySource.get(s).size() > 1);
                objects.add(new ObjectDiff(kind, null, modelName.apply(m), found.isEmpty() ? CompareStatus.ADDED_IN_MODEL : CompareStatus.RENAME_CANDIDATE,
                        modelValues.apply(m), found.stream().map(sourceName).toList(), ambiguous));
            }
        }
    }
}
