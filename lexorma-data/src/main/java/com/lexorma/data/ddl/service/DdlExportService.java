package com.lexorma.data.ddl.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.ddl.domain.dto.DdlExportDto;
import com.lexorma.data.physical.domain.entity.IndexChangeRequest;
import com.lexorma.data.physical.domain.entity.TableIndex;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.IndexChangeRequestRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import com.lexorma.data.view.ddl.ViewDdlGenerator;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.ViewChangeRequest;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import com.lexorma.data.view.repository.ViewChangeRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 통합 DDL Export (M-11). 프로젝트 전체 또는 스키마 하나의 물리 모델을 한 스크립트로 만든다. <b>생성만 하고 어떤 DB 에서도 실행하지 않는다.</b>
 * <p>
 * 문장은 기존 생성기가 만든다 : 테이블·인덱스·FK·COMMENT 는 {@link TableDdlGenerator#parts}, VIEW 는 {@link ViewDdlGenerator#generateAll}.
 * 이 서비스는 범위를 고르고, 같은 종류의 문장끼리 모아 실행 순서대로 놓고, 범위 밖 참조·승인 대기·이름 충돌을 알린다.
 * <ol>
 *   <li>SCHEMA : 범위 안의 기본 스키마가 아닌 스키마 (기본 스키마 = 접속 계정의 스키마라 만들지 않는다)</li>
 *   <li>TABLE : CREATE TABLE (PK·UNIQUE·CHECK 포함) 과 그 테이블의 COMMENT. 스키마(기본 먼저) → 테이블명 순</li>
 *   <li>INDEX : 모든 테이블을 만든 뒤. FK 가 고유 인덱스를 참조할 수 있어 FK 보다 먼저 (개별 테이블 DDL 과 같은 순서)</li>
 *   <li>FOREIGN KEY : 모든 테이블·인덱스 뒤 ALTER TABLE. 순환 FK·자기 참조도 순서와 관계없이 실행된다</li>
 *   <li>VIEW : 등록된 참조 정보 기준으로 참조되는 VIEW 먼저 (정의 SQL 을 해석하지 않는다)</li>
 * </ol>
 * 승인 상태 (M-11 결정) : 기본은 개별 DDL 과 같은 설계용 DDL (승인 대기 행 포함, 목록으로 알림). 승인 기준 모드는 범위 안에 승인 대기 항목이 있으면 만들지 않는다.
 * 승인 대기 중인 변경 요청(VIEW·인덱스)은 어느 모드든 반영하지 않는다 (확정된 정의 기준).
 */
@Service
@RequiredArgsConstructor
public class DdlExportService {

    private static final Pattern CONSTRAINT_NAME = Pattern.compile("CONSTRAINT (\\S+) (?:PRIMARY KEY|UNIQUE|CHECK|FOREIGN KEY)");
    private static final Pattern INDEX_NAME = Pattern.compile("^CREATE (?:\\S+ )?INDEX (\\S+) ON ");
    private static final String RULE = "-- ========================================\n";

    private final ProjectRepository projectRepository;
    private final SchemaService schemaService;
    private final SchemaInfoRepository schemaInfoRepository;
    private final TableInfoRepository tableInfoRepository;
    private final TableDesignService tableDesignService;
    private final PhysicalModelService physicalModelService;
    private final IndexChangeRequestRepository indexRequestRepository;
    private final PhysicalViewRepository viewRepository;
    private final ViewChangeRequestRepository viewRequestRepository;

    /**
     * @param schemaId          스키마 단위 (null 이면 프로젝트 전체). 다른 프로젝트 스키마는 거절
     * @param dbTypeName        대상 DBMS (비우면 프로젝트 설정)
     * @param defaultSchemaName 기본 스키마 객체에 붙일 스키마명 (개별 DDL 의 "스키마명 포함"과 같다)
     * @param approvedOnly      승인 기준 모드
     */
    @Transactional(readOnly = true)
    public DdlExportDto export(AccountDto accountDto, Long schemaId, String dbTypeName, String defaultSchemaName, boolean approvedOnly) {
        Long projectId = accountDto.getProjectId();
        String schemaPrefix = notBlank(defaultSchemaName) ? defaultSchemaName.trim() : null;
        if (schemaPrefix != null && !SchemaInfo.NAME_PATTERN.matcher(schemaPrefix).matches()) throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME);
        DbType db = dbType(projectId, dbTypeName);
        SchemaInfo scopeSchema = schemaId == null ? null : schemaService.resolveSchema(projectId, schemaId);

        List<SchemaInfo> schemas = (scopeSchema == null ? schemaInfoRepository.findAllByProjectIdOrderByIsDefaultDescSchemaNameAsc(projectId) : List.of(scopeSchema));
        Map<Long, Integer> schemaOrder = new HashMap<>();
        schemas.forEach(s -> schemaOrder.put(s.getId(), schemaOrder.size()));
        Comparator<SchemaInfo> bySchema = Comparator.comparing(s -> s == null ? -1 : schemaOrder.getOrDefault(s.getId(), Integer.MAX_VALUE));

        List<TableInfo> tables = tableInfoRepository.findAll((root, query, builder) -> scopeSchema == null
                        ? builder.equal(root.get("projectId"), projectId)
                        : builder.and(builder.equal(root.get("projectId"), projectId), builder.equal(root.get("schema").get("id"), scopeSchema.getId())))
                .stream().sorted(Comparator.comparing(TableInfo::getSchema, bySchema).thenComparing(TableInfo::getTableName)).toList();
        List<PhysicalView> views = viewRepository.findAll((root, query, builder) -> scopeSchema == null
                ? builder.equal(root.get("projectId"), projectId)
                : builder.and(builder.equal(root.get("projectId"), projectId), builder.equal(root.get("schema").get("id"), scopeSchema.getId())));

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Pending pending = new Pending();
        Set<Long> tableIds = tables.stream().map(TableInfo::getId).collect(Collectors.toSet());
        prefixCollision(schemaPrefix, schemas, tables, views, warnings, errors);

        // 1. SCHEMA
        List<String> schemaStatements = new ArrayList<>();
        for (SchemaInfo schema : schemas) {
            if (Boolean.TRUE.equals(schema.getIsDefault())) continue;
            if (db == DbType.ORACLE) {
                schemaStatements.add("-- " + Messages.get("{0} : Oracle 의 스키마는 사용자(계정)라 이 스크립트에서 만들지 않습니다.", schema.getSchemaName()));
            } else {
                schemaStatements.add("CREATE SCHEMA " + schema.getSchemaName() + ";");
            }
        }
        if (db == DbType.ORACLE && !schemaStatements.isEmpty()) {
            warnings.add(Messages.get("Oracle 은 스키마가 사용자(계정)라 CREATE SCHEMA 를 넣지 않았습니다. 대상 스키마 사용자를 먼저 만들어야 합니다."));
        }

        // 2~4. TABLE · INDEX · FOREIGN KEY
        List<String> tableStatements = new ArrayList<>();
        List<String> indexStatements = new ArrayList<>();
        List<String> foreignKeyStatements = new ArrayList<>();
        Set<String> outsideForeignKeys = new TreeSet<>();
        Map<Long, List<String>> relationNames = new LinkedHashMap<>(); // 스키마 → 테이블·VIEW·인덱스 이름 (PostgreSQL 은 키 제약 이름도)
        Map<Long, List<String>> constraintNames = new LinkedHashMap<>(); // 스키마 → 제약 이름 (Oracle)
        int commentedIndexes = 0;
        for (TableInfo table : tables) {
            // 참조 테이블명은 테이블마다 자기 스키마 기준으로 정한다 (개별 DDL 과 같은 TableNameResolver 규칙)
            TableDesignService.ReferencedKeys keys = tableDesignService.referencedKeys(projectId, table);
            Map<String, List<ColumnInfo>> refPkColumns = keys.pkColumns();
            TableDdlGenerator.Extras extras = physicalModelService.ddlExtras(table).withAmbiguousRefTables(keys.ambiguous());
            pending.add(table, extras);
            TableDdlGenerator.Parts parts = TableDdlGenerator.parts(db, table, table.getColumns(), refPkColumns, extras, schemaPrefix);
            parts.warnings().forEach(w -> warnings.add(table.getTableName() + " · " + w));
            if (!parts.hasColumns()) {
                errors.add(Messages.get("{0} : 컬럼이 없어 CREATE TABLE 을 만들 수 없습니다.", table.getTableName()));
                continue;
            }
            Long schemaKey = schemaKey(table.getSchema());
            relationNames.computeIfAbsent(schemaKey, k -> new ArrayList<>()).add(table.getTableName());

            StringBuilder statement = new StringBuilder(parts.header()).append(parts.createTable());
            if (!parts.comments().isEmpty()) statement.append(String.join("\n", parts.comments())).append('\n');
            tableStatements.add(statement.toString());
            Matcher constraint = CONSTRAINT_NAME.matcher(parts.createTable());
            while (constraint.find()) {
                constraintNames.computeIfAbsent(schemaKey, k -> new ArrayList<>()).add(constraint.group(1));
                if (db == DbType.POSTGRESQL && !constraint.group(0).endsWith("CHECK")) relationNames.get(schemaKey).add(constraint.group(1)); // PK·UNIQUE 는 같은 이름의 인덱스가 생긴다
            }

            for (String index : parts.indexes()) {
                indexStatements.add(index);
                Matcher name = INDEX_NAME.matcher(index);
                if (name.find()) relationNames.get(schemaKey).add(name.group(1));
                else commentedIndexes++;
            }
            for (String foreignKey : parts.foreignKeys()) {
                foreignKeyStatements.add(foreignKey);
                Matcher name = CONSTRAINT_NAME.matcher(foreignKey);
                if (name.find()) constraintNames.computeIfAbsent(schemaKey, k -> new ArrayList<>()).add(name.group(1));
            }
            outsideForeignKeys.addAll(outsideReferences(table, extras, refPkColumns, tableIds));
        }
        if (!outsideForeignKeys.isEmpty()) {
            warnings.add(Messages.get("이 스크립트 밖의 테이블을 참조하는 FK 가 있습니다 (대상 DB 에 먼저 있어야 합니다) : {0}", String.join(", ", outsideForeignKeys)));
        }

        // 5. VIEW
        // 순환 : 생성기는 경고하고 끝에 내지만, 통합 Export 는 순서를 정할 수 없으면 만들지 않는다 (같은 문구를 오류로 옮긴다)
        List<String> cyclic = ViewDdlGenerator.cyclicViews(views);
        String cycleMessage = cyclic.isEmpty() ? null : Messages.get("순환 참조가 있어 생성 순서를 정할 수 없는 VIEW 가 있습니다 : {0}", String.join(", ", cyclic));
        if (cycleMessage != null) errors.add(cycleMessage);
        TableDdlGenerator.Result viewResult = ViewDdlGenerator.generateAll(db, views, schemaPrefix, tableIds);
        viewResult.warnings().stream().filter(w -> !w.equals(cycleMessage)).forEach(warnings::add);
        views.forEach(v -> relationNames.computeIfAbsent(schemaKey(v.getSchema()), k -> new ArrayList<>()).add(v.getViewName()));
        if (!views.isEmpty()) warnings.add(Messages.get("VIEW 생성 순서는 등록된 참조 정보 기준입니다. 정의 SQL 이 실제로 참조하는 객체와 다르면 실행이 실패할 수 있습니다."));
        pending.addViews(views);

        // 승인 대기 중인 변경 요청 : 반영하지 않는다
        long viewRequests = views.isEmpty() ? 0 : viewRequestRepository.findAllByView_IdInAndStatus(views.stream().map(PhysicalView::getId).toList(), ViewChangeRequest.Status.REQUESTED).size();
        if (viewRequests > 0) warnings.add(Messages.get("승인 대기 중인 VIEW 변경 요청 {0}건은 반영하지 않았습니다 (확정된 정의 기준).", viewRequests));
        if (!pending.indexIds.isEmpty()) {
            long indexRequests = indexRequestRepository.findAllByTableIndex_IdInAndStatus(pending.indexIds, IndexChangeRequest.Status.REQUESTED).size();
            if (indexRequests > 0) warnings.add(Messages.get("승인 대기 중인 인덱스 변경 요청 {0}건은 반영하지 않았습니다 (확정된 정의 기준).", indexRequests));
        }

        nameCollisions(db, relationNames, constraintNames, warnings);
        if (tables.isEmpty() && views.isEmpty()) warnings.add(Messages.get("내보낼 테이블·VIEW 가 없습니다."));
        if (db != DbType.POSTGRESQL) warnings.add(Messages.get("{0} 에서는 통합 DDL 의 실제 실행을 검증하지 않았습니다.", db.getLabel()));

        List<String> pendingApprovals = pending.lines();
        if (approvedOnly && !pendingApprovals.isEmpty()) {
            errors.add(0, Messages.get("승인 기준 Export 는 범위 안에 승인 대기 항목이 없어야 합니다. 승인을 마치거나 승인 기준을 끄고 설계용 DDL 로 받아주세요."));
        }

        String ddl = "";
        if (errors.isEmpty()) {
            StringBuilder sql = new StringBuilder(RULE)
                    .append("-- Lexorma Integrated DDL Export\n")
                    .append("-- DBMS: ").append(db.getLabel()).append('\n')
                    .append("-- Scope: ").append(scopeSchema == null ? "PROJECT" : "SCHEMA " + scopeSchema.getSchemaName()).append('\n')
                    .append("-- Model: ").append(approvedOnly ? "APPROVED" : "DESIGN (" + Messages.get("승인 대기 항목 포함") + ")").append('\n')
                    .append(RULE);
            section(sql, "1. SCHEMAS", schemaStatements, "\n");
            section(sql, "2. TABLES", tableStatements.stream().map(String::stripTrailing).toList(), "\n\n");
            section(sql, "3. INDEXES", indexStatements, "\n");
            section(sql, "4. FOREIGN KEYS", foreignKeyStatements, "\n");
            if (!views.isEmpty()) section(sql, "5. VIEWS", List.of(viewResult.ddl().stripTrailing()), "\n");
            ddl = sql.toString();
        }
        DdlExportDto.Counts counts = new DdlExportDto.Counts(
                (int) schemaStatements.stream().filter(s -> !s.startsWith("--")).count(), tableStatements.size(),
                indexStatements.size() - commentedIndexes, commentedIndexes, foreignKeyStatements.size(), views.size());
        return new DdlExportDto(db.name(), db.getLabel(), scopeSchema == null ? "PROJECT" : "SCHEMA", scopeSchema == null ? null : scopeSchema.getId(),
                scopeSchema == null ? null : scopeSchema.getSchemaName(), approvedOnly, ddl, counts, warnings, errors, pendingApprovals);
    }

    /**
     * "스키마명 포함" 의 이름이 기본이 아닌 스키마의 이름과 같으면 두 모델 스키마가 실제 DB 의 한 스키마로 합쳐진다 (2026-11-13, 스키마 단위 이름).
     * 그 둘에 같은 이름의 테이블·VIEW 가 있으면 실행할 수 없거나 뜻이 바뀌는 스크립트라 만들지 않고, 겹치는 이름이 없으면 알리기만 한다.
     */
    private static void prefixCollision(String schemaPrefix, List<SchemaInfo> schemas, List<TableInfo> tables, List<PhysicalView> views,
                                        List<String> warnings, List<String> errors) {
        if (schemaPrefix == null) return;
        SchemaInfo same = schemas.stream().filter(sc -> !Boolean.TRUE.equals(sc.getIsDefault()) && sc.getSchemaName().equalsIgnoreCase(schemaPrefix)).findFirst().orElse(null);
        if (same == null) return;
        Set<String> inDefault = new TreeSet<>();
        Set<String> inSame = new TreeSet<>();
        java.util.function.BiConsumer<SchemaInfo, String> collect = (schema, name) -> {
            if (Boolean.TRUE.equals(schema.getIsDefault())) inDefault.add(name);
            else if (schema.getId().equals(same.getId())) inSame.add(name);
        };
        tables.forEach(t -> collect.accept(t.getSchema(), t.getTableName()));
        views.forEach(v -> collect.accept(v.getSchema(), v.getViewName()));
        inDefault.retainAll(inSame);
        if (!inDefault.isEmpty()) {
            errors.add(Messages.get("스키마명 포함 {0} 이(가) 스키마 {1} 와(과) 같아 같은 이름의 객체가 겹칩니다 : {2}. 다른 스키마명을 쓰거나 비워주세요.",
                    schemaPrefix, same.getSchemaName(), String.join(", ", inDefault)));
        } else {
            warnings.add(Messages.get("스키마명 포함 {0} 이(가) 스키마 {1} 와(과) 같아 기본 스키마의 객체가 그 스키마에 함께 만들어집니다.", schemaPrefix, same.getSchemaName()));
        }
    }

    private static void section(StringBuilder sql, String title, List<String> statements, String separator) {
        if (statements.isEmpty()) return;
        sql.append('\n').append("-- ").append(title).append('\n').append(String.join(separator, statements)).append('\n');
    }

    /** FK 대상이 이 스크립트 밖의 테이블인 것 (스키마 단위 Export) : "테이블 → 대상" */
    private static List<String> outsideReferences(TableInfo table, TableDdlGenerator.Extras extras, Map<String, List<ColumnInfo>> refPkColumns, Set<Long> tableIds) {
        List<String> outside = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            if (!notBlank(column.getRefTableName())) continue;
            List<ColumnInfo> pk = refPkColumns.get(column.getRefTableName().trim().toUpperCase(Locale.ROOT));
            if (pk == null || pk.size() != 1 || pk.get(0).getTableInfo() == null) continue; // FK 를 만들지 않은 경우는 생성기가 경고한다
            TableInfo target = pk.get(0).getTableInfo();
            if (!tableIds.contains(target.getId())) outside.add(table.getTableName() + " → " + target.getTableName());
        }
        for (TableRelation relation : extras.relations()) {
            if (!tableIds.contains(relation.getTargetTable().getId())) outside.add(table.getTableName() + " → " + relation.getTargetTable().getTableName());
        }
        return outside;
    }

    /**
     * 같은 스키마 안에서 이름이 겹치는 객체 (이름을 DBMS 길이 제한으로 자른 뒤). 실행하면 뒤의 문장이 실패한다.
     * PostgreSQL·Oracle : 테이블·VIEW·인덱스가 한 이름 공간 (PostgreSQL 은 PK·UNIQUE 제약이 같은 이름의 인덱스를 만든다). Oracle : 제약 이름도 스키마 단위.
     * MySQL·MariaDB : 인덱스·제약 이름은 테이블 단위라 보지 않는다 (테이블·VIEW 이름은 등록 때 프로젝트 단위로 막는다).
     */
    private static void nameCollisions(DbType db, Map<Long, List<String>> relationNames, Map<Long, List<String>> constraintNames, List<String> warnings) {
        if (db == DbType.MYSQL || db == DbType.MARIADB) return;
        Set<String> duplicated = new TreeSet<>();
        List<Map<Long, List<String>>> namespaces = db == DbType.ORACLE ? List.of(relationNames, constraintNames) : List.of(relationNames);
        for (Map<Long, List<String>> namespace : namespaces) {
            for (List<String> names : namespace.values()) {
                Set<String> seen = new HashSet<>();
                names.stream().map(n -> n.toUpperCase(Locale.ROOT)).filter(n -> !seen.add(n)).forEach(duplicated::add);
            }
        }
        if (!duplicated.isEmpty()) {
            warnings.add(Messages.get("같은 스키마 안에서 이름이 겹치는 객체가 있어 실행이 실패할 수 있습니다 (길이 제한으로 자른 이름 포함) : {0}", String.join(", ", duplicated)));
        }
    }

    private static Long schemaKey(SchemaInfo schema) {
        return schema == null ? 0L : schema.getId();
    }

    private DbType dbType(Long projectId, String dbTypeName) {
        if (notBlank(dbTypeName)) {
            return Arrays.stream(DbType.values()).filter(t -> t.name().equalsIgnoreCase(dbTypeName.trim())).findFirst()
                    .orElseThrow(() -> new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "지원하지 않는 DBMS 입니다 : {0}", dbTypeName.trim()));
        }
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 범위 안의 승인 대기 항목 (개별 DDL 의 pendingApprovals 와 같은 종류, 테이블명을 붙인다) */
    private static final class Pending {
        private final List<String> tables = new ArrayList<>();
        private final List<String> columns = new ArrayList<>();
        private final List<String> relations = new ArrayList<>();
        private final List<String> indexes = new ArrayList<>();
        private final List<String> constraints = new ArrayList<>();
        private final List<String> views = new ArrayList<>();
        private final List<Long> indexIds = new ArrayList<>();

        void add(TableInfo table, TableDdlGenerator.Extras extras) {
            String name = table.getTableName();
            if (!Boolean.TRUE.equals(table.getIsApproval())) tables.add(name);
            table.getColumns().stream()
                    .sorted(Comparator.comparing(ColumnInfo::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                    .filter(c -> !Boolean.TRUE.equals(c.getIsApproval())).forEach(c -> columns.add(name + "." + c.getColumnName()));
            extras.relations().stream().filter(r -> !Boolean.TRUE.equals(r.getIsApproval())).forEach(r -> relations.add(r.getRelationName()));
            extras.indexes().stream().filter(i -> !Boolean.TRUE.equals(i.getIsApproval())).forEach(i -> indexes.add(i.getIndexName()));
            extras.constraints().stream().filter(c -> !Boolean.TRUE.equals(c.getIsApproval())).forEach(c -> constraints.add(c.getConstraintName()));
            extras.indexes().stream().map(TableIndex::getId).forEach(indexIds::add);
        }

        void addViews(List<PhysicalView> all) {
            all.stream().filter(v -> !Boolean.TRUE.equals(v.getIsApproval())).map(PhysicalView::getViewName).sorted().forEach(views::add);
        }

        List<String> lines() {
            List<String> lines = new ArrayList<>();
            line(lines, "테이블 : {0}", tables);
            line(lines, "컬럼 : {0}", columns);
            line(lines, "관계(FK) : {0}", relations);
            line(lines, "인덱스 : {0}", indexes);
            line(lines, "제약조건 : {0}", constraints);
            line(lines, "VIEW : {0}", views);
            return lines;
        }

        private static void line(List<String> lines, String message, List<String> names) {
            if (!names.isEmpty()) lines.add(Messages.get(message, String.join(", ", names)));
        }
    }
}
