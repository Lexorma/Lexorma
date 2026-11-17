package com.lexorma.ai.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.ai.evidence.EvidenceCatalog;
import com.lexorma.ai.evidence.EvidenceType;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.column.service.SimilarColumnService;
import com.lexorma.data.history.domain.dto.ModelChangeHistoryDto;
import com.lexorma.data.history.domain.entity.ModelChangeHistory.TargetType;
import com.lexorma.data.history.service.ModelChangeHistoryService;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.dto.TableInfoDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.table.service.TableNameResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * AI-4 읽기 전용 메타데이터 Tool. Spring AI 의 {@link FunctionToolCallback} 으로 {@link #callbacks()} 에 적은 10개만 만들고, {@link MetadataToolset} 이 감싸 요청마다 등록한다
 * (Bean·메서드 자동 노출 없음. 입력은 record 라 컴파일 옵션 -parameters 와 상관없이 인자 이름이 고정된다).
 * <ul>
 *   <li>조회는 화면 API 와 같은 기존 조회 서비스로 한다 (VIEWER 가 화면에서 보는 범위와 같다). 쓰기·승인·SQL 실행·원본 DB·파일·외부 HTTP 경로는 없다</li>
 *   <li>프로젝트는 인자로 받지 않는다 : {@link MetadataToolSession#scope()} (서버가 정한 실행 컨텍스트)의 프로젝트만 조회한다.
 *       모델이 준 테이블 ID 가 다른 프로젝트 것이면 서비스의 프로젝트 조건 때문에 "없음" 이다</li>
 *   <li>결과는 필요한 필드만, 건수·문자열 길이를 잘라 돌려주고 잘랐으면 truncated 로 알린다. 컬럼의 예시 값(example)은 실제 데이터일 수 있어 보내지 않는다</li>
 *   <li>설명·COMMENT 는 사용자·원본 DB 가 쓴 데이터다. 결과에 그대로 담되 지시로 다루지 않는다 (시스템 프롬프트와 서버 범위 강제)</li>
 * </ul>
 * 트랜잭션·실행 컨텍스트 재검증·기록은 {@link MetadataToolset} 이 Tool 호출마다 한다.
 */
@Component
@RequiredArgsConstructor
public class MetadataTools {

    /** 검색 결과 기본·최대 건수 */
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;
    /** 검색어 최대 길이 */
    static final int KEYWORD_MAX = 100;
    /** 한 테이블에서 돌려줄 최대 컬럼 수 / 방향별 관계 수 / 인덱스·제약 수 */
    static final int MAX_COLUMNS = 100;
    static final int MAX_RELATIONS = 50;
    static final int MAX_INDEXES = 30;
    /** 설명 문자열 최대 길이 */
    static final int TEXT_MAX = 300;
    /** 이력 한 건에 보낼 최대 필드 수 */
    static final int HISTORY_FIELDS = 15;
    /** 이력에서 보내지 않는 필드 : 컬럼 예시 값(실제 데이터일 수 있다) */
    static final java.util.Set<String> HISTORY_HIDDEN = java.util.Set.of("example");

    private final SchemaInfoRepository schemaInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final TableDesignService tableDesignService;
    private final ColumnManageService columnManageService;
    private final PhysicalModelService physicalModelService;
    private final DataDictionaryService dataDictionaryService;
    private final EvidenceCatalog evidenceCatalog;
    private final SimilarColumnService similarColumnService;
    private final ModelChangeHistoryService modelChangeHistoryService;

    // ------------------------------------------------------------------ 결과 형식

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SchemaItem(Long schemaId, String schemaName, String description, boolean defaultSchema, Long tableCount) {
    }

    public record SchemaList(List<SchemaItem> schemas, boolean truncated) {
    }

    /**
     * @param match        이 테이블이 검색어와 맞은 방식 : EXACT(테이블명·코멘트가 검색어와 같다) · PARTIAL(테이블명·코멘트·설명이 검색어 전체를 포함) ·
     *                     TOKEN(검색어 전체는 없고 일부 단어만 맞았다 — 근사) · null(검색어 없이 목록)
     * @param matchedTerms TOKEN 일 때 실제로 맞은 단어
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableItem(Long tableId, String schemaName, String tableName, String tableComment, String description, String businessDescription,
                            String sourceComment, boolean approved, TableMatch match, List<String> matchedTerms) {
    }

    /** 테이블 하나가 검색어와 맞은 방식 ({@link TableItem#match()}) */
    public enum TableMatch { EXACT, PARTIAL, TOKEN }

    /**
     * 테이블 검색 결과 전체의 검색 방식 ({@link TableList#matchMode()}).
     * ALL = 검색어 없음(목록), EXACT = 결과가 모두 검색어와 같은 이름·코멘트, PARTIAL = 검색어 전체를 포함하는 부분 일치(같은 이름이 섞여 있을 수 있다 — 테이블별 match),
     * TOKEN_FALLBACK = 검색어 전체로는 없어 단어별로 다시 찾은 근사 결과, NONE = 결과 없음
     */
    public enum TableMatchMode { ALL, EXACT, PARTIAL, TOKEN_FALLBACK, NONE }

    /**
     * 기존 필드(keyword·tables·truncated)는 그대로 두고 검색 방식을 덧붙였다 (7차) : AI·MCP 클라이언트가 근사 결과를 정확한 결과로 오해하지 않도록.
     * @param exactMatch   matchMode 가 EXACT 일 때만 true
     * @param approximate  matchMode 가 TOKEN_FALLBACK 일 때만 true (검색어 전체를 포함하지 않는 테이블이다)
     * @param matchedTerms 결과를 낸 검색어 형태 (부분 일치 : 원래 검색어·공백을 _ 로 이은 형태 중 맞은 것, 근사 : 맞은 단어)
     */
    public record TableList(String keyword, List<TableItem> tables, boolean truncated, TableMatchMode matchMode, boolean exactMatch, boolean approximate,
                            List<String> matchedTerms, int resultCount) {

        static TableList of(String keyword, List<TableItem> tables, boolean truncated, TableMatchMode mode, List<String> matchedTerms) {
            TableMatchMode resolved = mode != TableMatchMode.ALL && tables.isEmpty() ? TableMatchMode.NONE : mode;
            return new TableList(keyword, tables, truncated, resolved, resolved == TableMatchMode.EXACT, resolved == TableMatchMode.TOKEN_FALLBACK,
                    resolved == TableMatchMode.NONE ? List.of() : matchedTerms, tables.size());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexItem(String indexName, String indexType, boolean unique, List<String> keys, String condition) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConstraintItem(String constraintName, String constraintType, List<String> columns, String checkExpression) {
    }

    public record TableDetail(TableItem table, int columnCount, boolean masterTable, List<IndexItem> indexes, List<ConstraintItem> constraints, boolean truncated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnItem(Long columnId, String columnName, String description, String businessDescription, String sourceComment, String dataType,
                             Integer length, String precision, Integer scale,
                             boolean primaryKey, boolean nullable, boolean unique, String defaultValue, String identity,
                             String referencedTable, String standardTerm, boolean sensitive, boolean approved) {
    }

    public record ColumnList(Long tableId, String tableName, int totalColumns, List<ColumnItem> columns, boolean truncated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationItem(Long relationId, String relationName, String kind, String origin, Long childTableId, String childTable, Long parentTableId, String parentTable,
                               List<String> columnPairs, String onDelete, String onUpdate, boolean approved) {
    }

    public record RelationList(Long tableId, String tableName, String note, List<RelationItem> outgoing, List<RelationItem> incoming,
                               List<String> referenceColumnsWithoutRelation, boolean truncated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TermItem(Long termId, String termName, String abbreviation, String domainName, String dataType, String description,
                           String allowedValues, String synonyms, boolean approved) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WordItem(Long wordId, String wordName, String abbreviation, String englishName, String description, String domainCategory,
                           boolean formatWord, String synonyms, String restrictedWords, boolean approved) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DomainItem(Long domainId, String domainName, String groupName, String category, String dataType, Integer dataLength, Integer decimalLength,
                             String unit, String allowedValues, String description, boolean approved) {
    }

    public record DictionaryList<T>(String keyword, List<T> items, boolean truncated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimilarReference(Long columnId, String columnName, String logicalName, List<String> standardTerms, List<String> standardDomains) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimilarColumnItem(Long columnId, String columnName, String logicalName, Long tableId, String tableName, String dataType, Integer length,
                                    String standardTerm, String standardDomain, int score, List<String> reasons) {
    }

    public record SimilarColumnList(SimilarReference reference, String scoring, List<SimilarColumnItem> columns, boolean truncated) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldChange(String field, String before, String after) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryItem(Long historyId, String changedAt, String targetType, Long targetId, String targetName, String action, String source,
                              List<FieldChange> changes, boolean moreChanges) {
    }

    public record HistoryList(String target, String note, List<HistoryItem> history, boolean truncated) {
    }

    // ------------------------------------------------------------------ 입력 (모델이 주는 JSON 인자. 프로젝트·계정은 없다)

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoInput() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableSearch(
            @ToolParam(description = "Part of a table name, table comment or description, e.g. 회원 or MEMBER. Empty lists the first tables", required = false) String keyword,
            @ToolParam(description = "Optional schema id from listSchemas", required = false) Long schemaId,
            @ToolParam(description = "Maximum number of tables to return (1-20, default 10)", required = false) Integer limit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableRef(
            @ToolParam(description = "Table id from searchTables (preferred)", required = false) Long tableId,
            @ToolParam(description = "Exact physical table name such as TB_MEMBER (optionally SCHEMA.TABLE), only when the id is unknown. For a Korean name or a description, call searchTables first and use the tableId", required = false) String tableName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeywordSearch(
            @ToolParam(description = "Part of a name, abbreviation or English name to search for") String keyword,
            @ToolParam(description = "Maximum number of results to return (1-20, default 10)", required = false) Integer limit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimilarColumnSearch(
            @ToolParam(description = "Column id from getTableColumns to compare with (preferred when known). That column itself is not returned", required = false) Long columnId,
            @ToolParam(description = "Without columnId: a physical column name such as MBR_SN, or a Korean logical name or standard term name such as 회원일련번호", required = false) String keyword,
            @ToolParam(description = "Maximum number of columns to return (1-20, default 10)", required = false) Integer limit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryRequest(
            @ToolParam(description = "TABLE, COLUMN or RELATION") String targetType,
            @ToolParam(description = "Id of the table, column or relation (tableId, columnId or relationId from the other tools)", required = false) Long targetId,
            @ToolParam(description = "Without targetId: the exact physical name recorded in the history (table name such as TB_MEMBER, TABLE.COLUMN for a column, relation name). Works for deleted objects. For a Korean name or a description, call searchTables first and use the tableId", required = false) String targetName,
            @ToolParam(description = "TABLE only: also include the changes of its columns and relations (default true)", required = false) Boolean includeChildren,
            @ToolParam(description = "Maximum number of changes to return, newest first (1-20, default 10)", required = false) Integer limit) {
    }

    // ------------------------------------------------------------------ Tool

    /**
     * 등록할 Tool (Spring AI {@link FunctionToolCallback} : 입력 record 의 JSON 스키마를 모델에게 보내고, 모델의 인자를 그 record 로 받는다).
     * {@link MetadataToolset} 이 감싸서 요청마다 등록한다
     */
    List<ToolCallback> callbacks() {
        return List.of(
                tool("listSchemas", "List the schemas of the current Lexorma project with their table counts. Takes no arguments.",
                        NoInput.class, (input, context) -> listSchemas(context)),
                tool("searchTables", "Search physical tables of the current project by table name, table comment (Korean name) or description. "
                        + "Returns table ids to use with the other table tools. matchMode tells how the tables matched: EXACT (every table name or comment "
                        + "equals the keyword), PARTIAL (name, comment or description contains the whole keyword), TOKEN_FALLBACK (nothing contained the whole "
                        + "keyword, so tables matching only some of its words are returned: approximate = true, they may be unrelated — check each table's "
                        + "matchedTerms and say so instead of treating them as the answer), NONE (no table), ALL (no keyword). Each table has match "
                        + "EXACT, PARTIAL or TOKEN.", TableSearch.class, this::searchTables),
                tool("getTableDetail", "Get one table of the current project: name, comment, description, schema, approval, indexes and "
                        + "constraints (PK, UNIQUE, CHECK). Give tableId (preferred) or the exact tableName.", TableRef.class, this::getTableDetail),
                tool("getTableColumns", "List the columns of one table of the current project: name, description, data type, length, primary key, "
                        + "nullable, unique, default, referenced table and standard term. Give tableId (preferred) or the exact tableName.", TableRef.class, this::getTableColumns),
                tool("getTableRelations", "List the relations registered in Lexorma for one table of the current project. kind FK is a registered "
                        + "foreign key; kind LOGICAL is a documented model relation without a database constraint. Relations not listed here are not "
                        + "registered. Give tableId (preferred) or the exact tableName.", TableRef.class, this::getTableRelations),
                tool("searchStandardTerms", "Search the standard terms (data dictionary) of the current project by term name or English abbreviation, "
                        + "e.g. 고객번호 or CUST_NO. Returns the term, its abbreviation (column name), domain and data type.", KeywordSearch.class, this::searchStandardTerms),
                tool("searchStandardWords", "Search the standard words of the current project by word name, English abbreviation or English name, "
                        + "e.g. 고객 or CUST.", KeywordSearch.class, this::searchStandardWords),
                tool("searchStandardDomains", "Search the standard domains of the current project by domain name or category, e.g. 번호 or 금액. "
                        + "Returns the data type, length and allowed values of each domain.", KeywordSearch.class, this::searchStandardDomains),
                tool("findSimilarColumns", "Find columns of the current project similar to one column (columnId) or to a name (physical name, Korean logical name "
                        + "or standard term). Lexorma scores them by fixed rules: same physical name, same standard term, same logical name, name parts, "
                        + "same standard domain. score and reasons come from these rules; do not invent other similarity scores.", SimilarColumnSearch.class, this::findSimilarColumns),
                tool("getMetadataHistory", "Get the recorded change history (newest first) of one physical table (optionally with its columns and relations), "
                        + "column or relation of the current project. Entries are past changes: before/after are values at that time, not the current metadata. "
                        + "Standard words, terms and domains have no change history.", HistoryRequest.class, this::getMetadataHistory));
    }

    private static <I, O> ToolCallback tool(String name, String description, Class<I> input, BiFunction<I, ToolContext, O> function) {
        return FunctionToolCallback.builder(name, (I in, ToolContext context) -> function.apply(in, context))
                .description(description)
                .inputType(input)
                .build();
    }

    SchemaList listSchemas(ToolContext toolContext) {
        // SchemaService.selectSchemas 는 기본 스키마가 없으면 만든다(쓰기) : Tool 은 같은 조회 쿼리만 쓴다
        List<SchemaDto> schemas = schemaInfoRepository.findSchemaList(scope(toolContext).getProjectId());
        List<SchemaItem> items = schemas.stream().limit(MAX_LIMIT)
                .map(s -> new SchemaItem(s.id(), s.schemaName(), text(s.schemaDescription()), Boolean.TRUE.equals(s.isDefault()), s.tableCount()))
                .toList();
        saw(toolContext, EvidenceType.SCHEMA, items.stream().map(SchemaItem::schemaId).toList());
        return new SchemaList(items, schemas.size() > MAX_LIMIT);
    }

    TableList searchTables(TableSearch input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        String query = keyword(input == null ? null : input.keyword(), false);
        Long schemaId = input == null ? null : input.schemaId();
        int max = limit(input == null ? null : input.limit());
        Pageable page = PageRequest.of(0, max + 1, Sort.by("tableName"));
        Map<Long, TableInfoDto> found = new LinkedHashMap<>();
        boolean more = false;
        if (query.isEmpty()) {
            more = collect(found, tableDesignService.selectTableInfoData(page, scope, null, null, schemaId), TableInfoDto::getId, max);
            List<TableItem> items = found.values().stream().limit(max).map(t -> table(t, null, null)).toList();
            return sawTables(toolContext, TableList.of(query, items, more || found.size() > max, TableMatchMode.ALL, List.of()));
        }
        List<String> matchedVariants = new ArrayList<>();
        for (String variant : variants(query)) {
            Map<Long, TableInfoDto> each = new LinkedHashMap<>();
            more |= searchTables(scope, variant, schemaId, page, max, each);
            if (!each.isEmpty()) matchedVariants.add(variant);
            each.forEach(found::putIfAbsent);
        }
        if (found.isEmpty()) {
            // 여러 단어("AI Provider")가 통째로 맞지 않으면 단어별로 찾아 많이 맞은 테이블부터 (모델이 설명의 표현과 다르게 묻는 경우) — 근사 결과로 표시한다
            Map<Long, List<String>> hits = new LinkedHashMap<>();
            List<String> matchedWords = new ArrayList<>();
            for (String word : query.split("[\\s_]+")) {
                if (word.length() < 2) continue;
                Map<Long, TableInfoDto> each = new LinkedHashMap<>();
                for (String variant : variants(word)) more |= searchTables(scope, variant, schemaId, page, max, each);
                if (!each.isEmpty() && !matchedWords.contains(word)) matchedWords.add(word);
                each.forEach((id, table) -> {
                    found.putIfAbsent(id, table);
                    List<String> words = hits.computeIfAbsent(id, k -> new ArrayList<>());
                    if (!words.contains(word)) words.add(word);
                });
            }
            List<TableInfoDto> ranked = found.values().stream()
                    .sorted(java.util.Comparator.comparing((TableInfoDto t) -> -hits.getOrDefault(t.getId(), List.of()).size()).thenComparing(TableInfoDto::getTableName))
                    .toList();
            List<TableItem> items = ranked.stream().limit(max).map(t -> table(t, TableMatch.TOKEN, hits.get(t.getId()))).toList();
            return sawTables(toolContext, TableList.of(query, items, more || ranked.size() > max, TableMatchMode.TOKEN_FALLBACK, matchedWords));
        }
        List<TableItem> items = found.values().stream().limit(max)
                .map(t -> table(t, exact(t, variants(query)) ? TableMatch.EXACT : TableMatch.PARTIAL, null)).toList();
        TableMatchMode mode = items.stream().allMatch(t -> t.match() == TableMatch.EXACT) ? TableMatchMode.EXACT : TableMatchMode.PARTIAL;
        return sawTables(toolContext, TableList.of(query, items, more || found.size() > max, mode, matchedVariants));
    }

    /** 테이블명(대소문자 무시)이나 테이블 코멘트가 검색어(또는 공백을 _ 로 이은 형태)와 같다. 스키마.테이블 형태도 같다고 본다 */
    private static boolean exact(TableInfoDto t, List<String> variants) {
        for (String variant : variants) {
            String v = variant.strip();
            if (v.equalsIgnoreCase(t.getTableName()) || v.equals(text(t.getTableKorName()))) return true;
            if (t.getSchemaName() != null && v.equalsIgnoreCase(t.getSchemaName() + "." + t.getTableName())) return true;
        }
        return false;
    }

    /** 테이블명(대문자)·테이블 코멘트·설명의 부분 일치 (기존 테이블 목록 조회와 같은 검색) — 검색어 한 형태. max 보다 많았는지 돌려준다 */
    private boolean searchTables(AccountDto scope, String variant, Long schemaId, Pageable page, int max, Map<Long, TableInfoDto> found) {
        boolean more = false;
        for (String column : List.of("tableName", "tableKorName", "tableDescription")) {
            String value = column.equals("tableName") ? variant.toUpperCase(Locale.ROOT) : variant;
            more |= collect(found, tableDesignService.selectTableInfoData(page, scope, column, value, schemaId), TableInfoDto::getId, max);
        }
        return more;
    }

    TableDetail getTableDetail(TableRef input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        TableInfoDto table = table(scope, input);
        PhysicalModelDto.TableModel model = physicalModelService.selectTableModel(scope, table.getId());
        int columns = columnManageService.selectTableColumn(scope, table.getId()).size();
        List<IndexItem> indexes = model.indexes().stream().limit(MAX_INDEXES).map(i -> new IndexItem(i.indexName(), i.indexType(), Boolean.TRUE.equals(i.isUnique()),
                i.keyMembers().stream().map(m -> m.expression() != null ? text(m.expression()) : m.columnName()).toList(), text(i.condition()))).toList();
        List<ConstraintItem> constraints = model.constraints().stream().limit(MAX_INDEXES).map(c -> new ConstraintItem(c.constraintName(), c.constraintType(),
                c.columns() == null ? List.of() : c.columns().stream().map(PhysicalModelDto.ColumnRef::columnName).toList(), text(c.checkExpression()))).toList();
        saw(toolContext, EvidenceType.TABLE, List.of(table.getId()));
        return new TableDetail(table(table), columns, "Y".equals(table.getIsMasterTable()), indexes, constraints,
                model.indexes().size() > MAX_INDEXES || model.constraints().size() > MAX_INDEXES);
    }

    ColumnList getTableColumns(TableRef input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        TableInfoDto table = table(scope, input);
        List<ColumnInfoDto> columns = columnManageService.selectTableColumn(scope, table.getId());
        List<ColumnItem> items = columns.stream().limit(MAX_COLUMNS).map(c -> new ColumnItem(c.getId(), c.getColumnName(), text(c.getColumnDesc()),
                text(c.getBizDescription()), text(c.getSourceComment()), c.getDataType(),
                c.getMaxLength(), c.getPrecision() == null ? null : c.getPrecision().toPlainString(), c.getScale(),
                yes(c.getIsPk()), !"N".equals(c.getIsNullable()), yes(c.getIsUnique()), text(c.getDefaultValue()), c.getIdentity(),
                c.getRefTableName(), c.getStandardTerms() == null ? null : c.getStandardTerms().getCommonStandardTermName(),
                yes(c.getIsSensitive()), yes(c.getIsApproval()))).toList();
        saw(toolContext, EvidenceType.TABLE, List.of(table.getId()));
        saw(toolContext, EvidenceType.COLUMN, items.stream().map(ColumnItem::columnId).toList());
        return new ColumnList(table.getId(), table.getTableName(), columns.size(), items, columns.size() > MAX_COLUMNS);
    }

    RelationList getTableRelations(TableRef input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        TableInfoDto table = table(scope, input);
        PhysicalModelDto.TableModel model = physicalModelService.selectTableModel(scope, table.getId());
        List<RelationItem> outgoing = model.outgoingRelations().stream().limit(MAX_RELATIONS).map(MetadataTools::relation).toList();
        List<RelationItem> incoming = model.incomingRelations().stream().limit(MAX_RELATIONS).map(MetadataTools::relation).toList();
        List<RelationItem> all = new ArrayList<>(outgoing);
        all.addAll(incoming);
        saw(toolContext, EvidenceType.RELATION, all.stream().map(RelationItem::relationId).toList());
        List<Long> tables = new ArrayList<>(List.of(table.getId()));
        all.forEach(r -> {
            tables.add(r.childTableId());
            tables.add(r.parentTableId());
        });
        saw(toolContext, EvidenceType.TABLE, tables);
        return new RelationList(table.getId(), table.getTableName(),
                "outgoing: this table is the child (it has the referencing columns); incoming: this table is the parent",
                outgoing,
                incoming,
                model.unresolvedReferences().stream().limit(MAX_RELATIONS).toList(),
                model.outgoingRelations().size() > MAX_RELATIONS || model.incomingRelations().size() > MAX_RELATIONS
                        || model.unresolvedReferences().size() > MAX_RELATIONS);
    }

    DictionaryList<TermItem> searchStandardTerms(KeywordSearch input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        String query = keyword(input == null ? null : input.keyword(), true);
        int max = limit(input.limit());
        Map<Long, StandardTermDto> found = new LinkedHashMap<>();
        boolean more = false;
        for (String value : variants(query)) {
            for (String column : List.of("commonStandardTermName", "commonStandardTermAbbreviation")) {
                more |= collect(found, dataDictionaryService.getStandardTermsData(page(max, "commonStandardTermName"), scope, column, value), StandardTermDto::getId, max);
            }
        }
        List<TermItem> items = found.values().stream().limit(max).map(t -> new TermItem(t.getId(), t.getCommonStandardTermName(), t.getCommonStandardTermAbbreviation(),
                t.getCommonStandardDomainName(), t.getDataType(), text(t.getCommonStandardTermDescription()), text(t.getAllowedValues()), text(t.getSynonyms()),
                yes(t.getIsApprovalYn()))).toList();
        saw(toolContext, EvidenceType.TERM, items.stream().map(TermItem::termId).toList());
        return new DictionaryList<>(query, items, more || found.size() > max);
    }

    DictionaryList<WordItem> searchStandardWords(KeywordSearch input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        String query = keyword(input == null ? null : input.keyword(), true);
        int max = limit(input.limit());
        Map<Long, StandardWordDto> found = new LinkedHashMap<>();
        boolean more = false;
        for (String value : variants(query)) {
            for (String column : List.of("commonStandardWordName", "commonStandardWordAbbreviation", "commonStandardWordEnglishName")) {
                more |= collect(found, dataDictionaryService.getStandardWordsData(page(max, "commonStandardWordName"), scope, column, value), StandardWordDto::getId, max);
            }
        }
        List<WordItem> items = found.values().stream().limit(max).map(w -> new WordItem(w.getId(), w.getCommonStandardWordName(), w.getCommonStandardWordAbbreviation(),
                w.getCommonStandardWordEnglishName(), text(w.getCommonStandardWordDescription()), w.getCommonStandardDomainCategory(), yes(w.getIsFormatWord()),
                text(w.getSynonyms()), text(w.getRestrictedWords()), yes(w.getIsApprovalYn()))).toList();
        saw(toolContext, EvidenceType.WORD, items.stream().map(WordItem::wordId).toList());
        return new DictionaryList<>(query, items, more || found.size() > max);
    }

    DictionaryList<DomainItem> searchStandardDomains(KeywordSearch input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        String query = keyword(input == null ? null : input.keyword(), true);
        int max = limit(input.limit());
        Map<Long, StandardDomainDto> found = new LinkedHashMap<>();
        boolean more = false;
        for (String value : variants(query)) {
            for (String column : List.of("commonStandardDomainName", "commonStandardDomainCategory")) {
                more |= collect(found, dataDictionaryService.getStandardDomainsData(page(max, "commonStandardDomainName"), scope, column, value), StandardDomainDto::getId, max);
            }
        }
        List<DomainItem> items = found.values().stream().limit(max).map(d -> new DomainItem(d.getId(), d.getCommonStandardDomainName(), d.getCommonStandardDomainGroupName(),
                d.getCommonStandardDomainCategory(), d.getDataType(), d.getDataLength(), d.getDataDecimalLength(), d.getUnit(), text(d.getAllowedValues()),
                text(d.getCommonStandardDomainDescription()), yes(d.getIsApprovalYn()))).toList();
        saw(toolContext, EvidenceType.DOMAIN, items.stream().map(DomainItem::domainId).toList());
        return new DictionaryList<>(query, items, more || found.size() > max);
    }

    SimilarColumnList findSimilarColumns(SimilarColumnSearch input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        Long columnId = input == null ? null : input.columnId();
        String query = columnId != null ? null : keyword(input == null ? null : input.keyword(), true);
        SimilarColumnService.Result result = similarColumnService.find(scope, columnId, query, limit(input == null ? null : input.limit()));
        SimilarColumnService.Reference ref = result.reference();
        List<SimilarColumnItem> items = result.matches().stream().map(m -> {
            var c = m.column();
            var term = c.getStandardTerms();
            return new SimilarColumnItem(c.getId(), c.getColumnName(), text(c.getColumnDesc()), c.getTableInfo() == null ? null : c.getTableInfo().getId(), c.getTableName(),
                    c.getDataType(), c.getMaxLength(), term == null ? null : term.getCommonStandardTermName(),
                    term == null || term.getStandardDomain() == null ? null : term.getStandardDomain().getCommonStandardDomainName(),
                    m.score(), m.reasons().stream().map(Enum::name).toList());
        }).toList();
        List<Long> columnIds = new ArrayList<>(items.stream().map(SimilarColumnItem::columnId).toList());
        if (ref.columnId() != null) columnIds.add(ref.columnId());
        saw(toolContext, EvidenceType.COLUMN, columnIds);
        saw(toolContext, EvidenceType.TABLE, items.stream().map(SimilarColumnItem::tableId).toList());
        return new SimilarColumnList(new SimilarReference(ref.columnId(), ref.columnName(), text(ref.logicalName()), ref.standardTerms(), ref.standardDomains()),
                "NAME_EXACT 100, SAME_TERM 90, LOGICAL_NAME_EXACT 85, NAME_PARTIAL 60, NAME_TOKENS 40-80, LOGICAL_NAME_PARTIAL 45, SAME_DOMAIN +10 (20 alone); max 100",
                items, result.truncated());
    }

    HistoryList getMetadataHistory(HistoryRequest input, ToolContext toolContext) {
        AccountDto scope = scope(toolContext);
        String type = input == null || input.targetType() == null ? "" : input.targetType().strip().toUpperCase(Locale.ROOT);
        Long id = input == null ? null : input.targetId();
        String name = input == null || input.targetName() == null || input.targetName().isBlank() ? null : keyword(input.targetName(), true);
        if (id == null && name == null) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "targetId 나 targetName 이 필요합니다.");
        List<ModelChangeHistoryService.TargetFilter> targets = new ArrayList<>();
        String label;
        switch (type) {
            case "TABLE" -> {
                // 지금 있는 테이블이면 그 컬럼·관계도 (현재 프로젝트에서만 찾는다. 없으면 이력의 기록으로만 찾는다 — 삭제된 테이블)
                TableInfoDto current = currentTable(scope, id, name);
                String tableName = current != null ? current.getTableName() : name;
                label = "TABLE " + (tableName != null ? tableName : "#" + id);
                targets.add(new ModelChangeHistoryService.TargetFilter(TargetType.PHYSICAL_TABLE, id == null ? (current == null ? null : List.of(current.getId())) : List.of(id), name, null));
                if (input.includeChildren() == null || input.includeChildren()) {
                    List<Long> columnIds = current == null ? List.of() : columnManageService.selectTableColumn(scope, current.getId()).stream().map(ColumnInfoDto::getId).toList();
                    targets.add(new ModelChangeHistoryService.TargetFilter(TargetType.PHYSICAL_COLUMN, columnIds, null, tableName == null ? null : tableName + "."));
                    if (current != null) {
                        PhysicalModelDto.TableModel model = physicalModelService.selectTableModel(scope, current.getId());
                        List<Long> relationIds = new ArrayList<>();
                        model.outgoingRelations().forEach(r -> relationIds.add(r.id()));
                        model.incomingRelations().forEach(r -> relationIds.add(r.id()));
                        targets.add(new ModelChangeHistoryService.TargetFilter(TargetType.PHYSICAL_RELATION, relationIds, null, null));
                    }
                }
            }
            case "COLUMN" -> {
                label = "COLUMN " + (name != null ? name : "#" + id);
                targets.add(new ModelChangeHistoryService.TargetFilter(TargetType.PHYSICAL_COLUMN, id == null ? null : List.of(id), name, null));
            }
            case "RELATION" -> {
                label = "RELATION " + (name != null ? name : "#" + id);
                targets.add(new ModelChangeHistoryService.TargetFilter(TargetType.PHYSICAL_RELATION, id == null ? null : List.of(id), name, null));
            }
            default -> throw new CustomException(ErrorCode.AI_QUERY_INVALID, "targetType 은 TABLE·COLUMN·RELATION 중 하나입니다.");
        }
        int max = limit(input.limit());
        Page<ModelChangeHistoryDto.Detail> page = modelChangeHistoryService.recent(scope, targets, max);
        // 이력이 없을 때 : 대상도 (지금 프로젝트에) 없으면 "변경 없음" 이 아니라 "대상 없음" — 모델이 한글명 등으로 잘못 찾고 이력이 없다고 답하지 않도록
        if (page.isEmpty() && !exists(scope, type, id, targets)) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED, "그 대상이 현재 프로젝트에 없고 기록된 이력도 없습니다. 이름은 물리명이어야 합니다 — 한글명·설명이면 searchTables 로 먼저 찾아 ID 를 쓰세요.");
        }
        List<HistoryItem> items = page.getContent().stream().map(MetadataTools::history).toList();
        saw(toolContext, EvidenceType.HISTORY, items.stream().map(HistoryItem::historyId).toList());
        return new HistoryList(label, "Past changes recorded by Lexorma, newest first. before/after are values at that time, not the current metadata; "
                + "use the other tools for the current state. No entries means no recorded change.", items, page.getTotalElements() > max);
    }

    /** 이력 대상이 지금 현재 프로젝트에 있는지 (테이블은 위에서 찾은 것, 컬럼·관계는 ID 로. 이름으로만 준 컬럼·관계는 이력이 없으면 없는 것으로 본다) */
    private boolean exists(AccountDto scope, String type, Long id, List<ModelChangeHistoryService.TargetFilter> targets) {
        if (type.equals("TABLE")) return targets.getFirst().ids() != null && !targets.getFirst().ids().isEmpty()
                && evidenceCatalog.load(EvidenceType.TABLE, targets.getFirst().ids()).values().stream().anyMatch(t -> scope.getProjectId().equals(t.projectId()));
        if (id == null) return false;
        EvidenceType evidence = type.equals("COLUMN") ? EvidenceType.COLUMN : EvidenceType.RELATION;
        return evidenceCatalog.load(evidence, List.of(id)).values().stream().anyMatch(t -> scope.getProjectId().equals(t.projectId()));
    }

    /** 지금 있는 테이블 (현재 프로젝트). 없으면 null — 이력은 삭제된 대상도 있으므로 오류로 보지 않는다 */
    private TableInfoDto currentTable(AccountDto scope, Long id, String name) {
        if (id != null) {
            return tableInfoRepository.findById(id).filter(t -> scope.getProjectId().equals(t.getProjectId())).map(TableInfoDto::fromEntity).orElse(null);
        }
        List<TableInfo> candidates = tableInfoRepository.findAllByProjectIdAndTableNameIn(scope.getProjectId(), List.of(TableNameResolver.tableNameOf(name)));
        TableNameResolver.Result result = TableNameResolver.resolve(name, null, candidates, null);
        return result.status() == TableNameResolver.Status.FOUND ? TableInfoDto.fromEntity(result.table()) : null;
    }

    /** 이력 한 건 : 바뀐 필드만 변경 전후 값으로 (생성·삭제는 그 시점 값). 사용자 ID 는 보내지 않는다. 예시 값(실제 데이터일 수 있음)은 빼고 보낸다 */
    private static HistoryItem history(ModelChangeHistoryDto.Detail detail) {
        ModelChangeHistoryDto.Summary s = detail.summary();
        List<FieldChange> changes = new ArrayList<>();
        JsonNode before = detail.before();
        JsonNode after = detail.after();
        List<String> fields = new ArrayList<>();
        if (before != null && after != null) fields.addAll(s.changedFields());
        else (after != null ? after : before).propertyNames().forEach(fields::add);
        for (String field : fields) {
            if (HISTORY_HIDDEN.contains(field)) continue;
            String b = before == null ? null : value(before.get(field));
            String a = after == null ? null : value(after.get(field));
            if (b == null && a == null) continue;
            changes.add(new FieldChange(field, b, a));
        }
        return new HistoryItem(s.id(), s.changedAt() == null ? null : s.changedAt().toString(), s.targetType().replace("PHYSICAL_", ""), s.targetId(), s.targetName(),
                s.action(), s.source(), changes.stream().limit(HISTORY_FIELDS).toList(), changes.size() > HISTORY_FIELDS);
    }

    private static String value(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isValueNode()) return text(node.asString());
        // 자식 행 배열 등 : 크기만
        return node.isArray() ? "[" + node.size() + " items]" : "{...}";
    }

    // ------------------------------------------------------------------ 공통

    /** Tool 이 돌려준 대상의 지금(=조회 시점) 스냅숏을 이 질의의 근거 후보로 남긴다 (종류별 한 번의 ID 조회) */
    private void saw(ToolContext toolContext, EvidenceType type, List<Long> ids) {
        MetadataToolSession.from(toolContext).saw(evidenceCatalog.load(type, ids).values());
    }

    private TableList sawTables(ToolContext toolContext, TableList list) {
        saw(toolContext, EvidenceType.TABLE, list.tables().stream().map(TableItem::tableId).toList());
        return list;
    }

    private static AccountDto scope(ToolContext toolContext) {
        return MetadataToolSession.from(toolContext).scope();
    }

    /**
     * tableId 가 있으면 그것, 없으면 테이블명(스키마명.테이블명 가능)을 다른 이름 기반 조회와 같은 규칙({@link TableNameResolver})으로 정한다.
     * 둘 다 현재 프로젝트 안에서만 찾는다
     */
    private TableInfoDto table(AccountDto scope, TableRef input) {
        if (input != null && input.tableId() != null) return tableDesignService.selectTable(input.tableId(), scope);
        String raw = keyword(input == null ? null : input.tableName(), true);
        List<TableInfo> candidates = tableInfoRepository.findAllByProjectIdAndTableNameIn(scope.getProjectId(), List.of(TableNameResolver.tableNameOf(raw)));
        TableNameResolver.Result result = TableNameResolver.resolve(raw, null, candidates, null);
        return switch (result.status()) {
            case FOUND -> TableInfoDto.fromEntity(result.table());
            case NOT_FOUND -> throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED, "그 이름의 물리 테이블이 없습니다. 한글명·설명이면 searchTables 로 먼저 찾아 tableId 를 쓰세요.");
            case AMBIGUOUS -> throw new CustomException(ErrorCode.AI_QUERY_INVALID, "같은 이름의 테이블이 여러 스키마에 있습니다. tableId 로 지정하세요 : {0}",
                    String.join(", ", result.candidates().stream().limit(MAX_LIMIT)
                            .map(t -> (t.getSchema() == null ? "" : t.getSchema().getSchemaName() + ".") + t.getTableName() + " (tableId " + t.getId() + ")").toList()));
        };
    }

    private static String keyword(String keyword, boolean required) {
        String value = keyword == null ? "" : keyword.strip();
        if (required && value.isEmpty()) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "검색어가 필요합니다.");
        if (value.length() > KEYWORD_MAX) throw new CustomException(ErrorCode.AI_QUERY_INVALID, "검색어는 {0}자까지 입력할 수 있습니다.", KEYWORD_MAX);
        return value;
    }

    /** 검색어와, 공백을 _ 로 바꾼 것 (표준 용어·단어명·테이블 코멘트는 단어를 _ 로 잇는다 : "AI 설정 일련번호" → "AI_설정_일련번호") */
    private static List<String> variants(String query) {
        String joined = query.strip().replaceAll("\\s+", "_");
        return joined.equals(query) ? List.of(query) : List.of(query, joined);
    }

    private static int limit(Integer limit) {
        if (limit == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static Pageable page(int max, String sort) {
        return PageRequest.of(0, max + 1, Sort.by(sort));
    }

    /** 검색 결과를 ID 로 합친다. 이 검색에 max 보다 많은 결과가 있었는지 돌려준다 */
    private static <T> boolean collect(Map<Long, T> found, Page<T> page, Function<T, Long> id, int max) {
        for (T item : page.getContent()) {
            if (found.size() > max) break;
            found.putIfAbsent(id.apply(item), item);
        }
        return page.getTotalElements() > max;
    }

    private static TableItem table(TableInfoDto t) {
        return table(t, null, null);
    }

    private static TableItem table(TableInfoDto t, TableMatch match, List<String> matchedTerms) {
        return new TableItem(t.getId(), t.getSchemaName(), t.getTableName(), text(t.getTableKorName()), text(t.getTableDescription()), text(t.getBizDescription()),
                text(t.getSourceComment()), yes(t.getIsApproval()), match, matchedTerms);
    }

    private static RelationItem relation(PhysicalModelDto.Relation r) {
        List<String> pairs = new ArrayList<>();
        if (r.columns() != null) r.columns().forEach(c -> pairs.add(c.sourceColumnName() + " -> " + c.targetColumnName()));
        boolean fk = "FK".equals(r.relationType());
        return new RelationItem(r.id(), r.relationName(), r.relationType(), r.relationSource(), r.sourceTableId(), r.sourceTableName(), r.targetTableId(), r.targetTableName(), pairs,
                fk ? r.deleteRule() : null, fk ? r.updateRule() : null, Boolean.TRUE.equals(r.isApproval()));
    }

    private static boolean yes(String yn) {
        return "Y".equals(yn);
    }

    /** 긴 설명은 잘라 보낸다 (끝에 … 표시) */
    static String text(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        return stripped.length() <= TEXT_MAX ? stripped : stripped.substring(0, TEXT_MAX) + "…";
    }
}
