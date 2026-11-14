package com.lexorma.data.view.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.util.AuthUtil;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.ObjectNamespace;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.table.domain.dto.TableDdlDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.view.ddl.ViewDdlGenerator;
import com.lexorma.data.view.ddl.ViewDefinitionSql;
import com.lexorma.data.view.domain.dto.ViewDto;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewColumn;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;
import com.lexorma.data.view.domain.entity.ViewChangeRequest;
import com.lexorma.data.view.repository.PhysicalViewRepository;
import com.lexorma.data.view.repository.ViewChangeRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * VIEW 관리 (M-15). 물리 모델 인덱스 관리(M-14)와 같은 승인 규칙을 쓴다 :
 * <ul>
 *   <li>등록 : 승인 권한자(관리자·매니저·프로젝트 관리자)가 등록하면 바로 승인, 아니면 승인 대기</li>
 *   <li>미승인 VIEW 는 바로 수정. 승인된 VIEW 의 수정은 변경 요청(정의 전체의 전후 JSON)이 되고 확정 정의는 그대로다.
 *       승인 권한자의 요청은 즉시 반영된다. 승인 대기 요청이 있으면 새 요청·승인 취소를 받지 않는다</li>
 *   <li>요청 승인 : 등록과 같은 검증(이름 공간·참조 대상·순환)을 다시 거쳐 기본 정보·정의 SQL·출력 컬럼·참조 객체를 한 트랜잭션으로 반영한다 (실패하면 전부 롤백)</li>
 *   <li>삭제 : 미승인 VIEW 만, 다른 VIEW 가 참조하면 거절</li>
 * </ul>
 * 이름·참조를 바꾸는 쓰기는 프로젝트의 이름 공간 잠금({@link ObjectNamespace}) → VIEW 행 잠금 순서로 직렬화한다 (동시 등록·동시 승인의 이름 충돌과 순환 방지).
 * 정의 SQL 은 정적 검사만 하고 어디에서도 실행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PhysicalViewService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int TEXT_MAX = 255;
    private static final int DESCRIPTION_MAX = 1000;
    private static final int COLUMN_MAX = 1000;
    private static final int REFERENCE_MAX = 200;
    private static final int TARGET_LIMIT = 50;
    private static final int ERD_LIMIT = 300;
    private static final Set<String> SEARCH_COLUMNS = Set.of("viewName", "viewKorName", "viewDescription");
    /** 모델에 없는 객체의 이름 (따옴표 없이 쓰는 식별자, 한글 허용) */
    private static final Pattern EXTERNAL_NAME = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_$#]{0,254}$");
    /** 네 DBMS 에서 따옴표 없이 VIEW·컬럼 이름으로 쓸 수 없는 대표 예약어 */
    private static final Set<String> RESERVED = Set.of("ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK", "COLUMN", "CONSTRAINT",
            "CREATE", "CROSS", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXISTS",
            "FALSE", "FOR", "FOREIGN", "FROM", "FULL", "GRANT", "GROUP", "HAVING", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "LEFT",
            "LIKE", "LIMIT", "NOT", "NULL", "OF", "ON", "OR", "ORDER", "OUTER", "PRIMARY", "REFERENCES", "RIGHT", "ROW", "ROWNUM", "SELECT", "SESSION_USER",
            "SET", "TABLE", "THEN", "TO", "TRUE", "UNION", "UNIQUE", "UPDATE", "USER", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WITH");

    private final PhysicalViewRepository viewRepository;
    private final ViewChangeRequestRepository requestRepository;
    private final TableInfoRepository tableInfoRepository;
    private final ProjectRepository projectRepository;
    private final SchemaService schemaService;
    private final SchemaInfoRepository schemaInfoRepository;
    private final ObjectNamespace namespace;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page<ViewDto.Row> search(AccountDto accountDto, Pageable pageable, String searchColumn, String searchQuery, Long schemaId, String isApproval) {
        Long projectId = accountDto.getProjectId();
        Specification<PhysicalView> where = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (schemaId != null) predicates.add(builder.equal(root.get("schema").get("id"), schemaId));
            if (notBlank(isApproval)) {
                Predicate approved = builder.isTrue(root.get("isApproval"));
                predicates.add("Y".equalsIgnoreCase(isApproval) ? approved : builder.not(builder.coalesce(root.<Boolean>get("isApproval"), false)));
            }
            if (notBlank(searchQuery) && SEARCH_COLUMNS.contains(searchColumn)) {
                predicates.add(builder.like(builder.upper(builder.coalesce(root.<String>get(searchColumn), "")), "%" + searchQuery.trim().toUpperCase(Locale.ROOT) + "%"));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        // 화면 컬럼명 → 엔티티 경로 (개수·변경 요청은 정렬하지 않는다)
        Sort sort = Sort.by(pageable.getSort().stream()
                .filter(order -> !Set.of("columnCount", "referenceCount", "pendingRequest").contains(order.getProperty()))
                .map(order -> "schemaName".equals(order.getProperty()) ? order.withProperty("schema.schemaName") : order)
                .toList()).and(Sort.by("id"));
        Page<PhysicalView> page = viewRepository.findAll(where, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort));
        Set<Long> pending = page.isEmpty() ? Set.of()
                : requestRepository.findAllByView_IdInAndStatus(page.map(PhysicalView::getId).toList(), ViewChangeRequest.Status.REQUESTED).stream()
                    .map(r -> r.getView().getId()).collect(Collectors.toSet());
        return page.map(v -> {
            BaseEntity.Audit audit = v.audit();
            return new ViewDto.Row(v.getId(), v.getSchema().getId(), v.getSchema().getSchemaName(), v.getViewName(), v.getViewKorName(), v.getViewDescription(),
                    v.getColumns().size(), v.getReferences().size(), yn(v.getIsApproval()), yn(pending.contains(v.getId())),
                    audit.created(), audit.updated(), audit.createdBy(), audit.updatedBy());
        });
    }

    @Transactional(readOnly = true)
    public ViewDto.Detail detail(AccountDto accountDto, Long id) {
        PhysicalView view = viewRepository.findByIdAndProjectId(id, accountDto.getProjectId()).orElseThrow(PhysicalViewService::notFound);
        BaseEntity.Audit audit = view.audit();
        return new ViewDto.Detail(view.getId(), view.getIsApproval(), definitionOf(view), audit.created(), audit.updated(), audit.createdBy(), audit.updatedBy(),
                requestRepository.findAllByView_IdOrderByIdDesc(id).stream().map(PhysicalViewService::toDto).toList(),
                viewRepository.findNamesReferencingView(id));
    }

    /** 참조 객체 후보 : 이름·논리명 검색 (테이블·VIEW 각각 최대 50) */
    @Transactional(readOnly = true)
    public List<ViewDto.Target> targets(AccountDto accountDto, String keyword, String type) {
        Long projectId = accountDto.getProjectId();
        String like = "%" + (keyword == null ? "" : keyword.trim().toUpperCase(Locale.ROOT)) + "%";
        List<ViewDto.Target> result = new ArrayList<>();
        if (type == null || type.isBlank() || "TABLE".equalsIgnoreCase(type)) {
            entityManager.createQuery("select t.id, s.id, s.schemaName, t.tableName, t.tableKorName "
                            + "from TableInfo t join t.schema s where t.projectId = :projectId and (upper(t.tableName) like :like or upper(coalesce(t.tableKorName, '')) like :like) "
                            + "order by t.tableName, t.id", Object[].class)
                    .setParameter("projectId", projectId).setParameter("like", like).setMaxResults(TARGET_LIMIT).getResultList()
                    .forEach(row -> result.add(target("TABLE", row)));
        }
        if (type == null || type.isBlank() || "VIEW".equalsIgnoreCase(type)) {
            entityManager.createQuery("select v.id, s.id, s.schemaName, v.viewName, v.viewKorName "
                            + "from PhysicalView v join v.schema s where v.projectId = :projectId and (upper(v.viewName) like :like or upper(coalesce(v.viewKorName, '')) like :like) "
                            + "order by v.viewName, v.id", Object[].class)
                    .setParameter("projectId", projectId).setParameter("like", like).setMaxResults(TARGET_LIMIT).getResultList()
                    .forEach(row -> result.add(target("VIEW", row)));
        }
        return result;
    }

    private static ViewDto.Target target(String type, Object[] row) {
        return new ViewDto.Target(type, (Long) row[0], (Long) row[1], (String) row[2], (String) row[3], (String) row[4]);
    }

    /** 물리 ERD 의 VIEW 노드 : 스키마 조건, 이름순 최대 300 */
    @Transactional(readOnly = true)
    public List<ViewDto.ErdView> erdViews(AccountDto accountDto, Long schemaId) {
        Long projectId = accountDto.getProjectId();
        Specification<PhysicalView> where = (root, query, builder) -> schemaId == null
                ? builder.equal(root.get("projectId"), projectId)
                : builder.and(builder.equal(root.get("projectId"), projectId), builder.equal(root.get("schema").get("id"), schemaId));
        return viewRepository.findAll(where, PageRequest.of(0, ERD_LIMIT, Sort.by("viewName", "id"))).stream()
                .map(v -> {
                    ViewDto.Definition d = definitionOf(v);
                    return new ViewDto.ErdView(v.getId(), v.getSchema().getId(), v.getViewName(), v.getViewKorName(), v.getIsApproval(), d.columns(), d.references());
                }).toList();
    }

    // ------------------------------------------------------------------ DDL (실행하지 않는다)

    @Transactional(readOnly = true)
    public TableDdlDto ddl(AccountDto accountDto, Long id, String defaultSchemaName) {
        String schemaName = ddlSchemaName(defaultSchemaName);
        PhysicalView view = viewRepository.findByIdAndProjectId(id, accountDto.getProjectId()).orElseThrow(PhysicalViewService::notFound);
        DbType dbType = dbType(accountDto.getProjectId());
        TableDdlGenerator.Result result = ViewDdlGenerator.generate(dbType, view, schemaName);
        List<String> warnings = new ArrayList<>(result.warnings());
        if (requestRepository.existsByView_IdAndStatus(id, ViewChangeRequest.Status.REQUESTED)) {
            warnings.add(Messages.get("승인 대기 중인 변경 요청은 DDL 에 반영하지 않았습니다 (확정된 정의 기준)."));
        }
        List<String> pending = Boolean.TRUE.equals(view.getIsApproval()) ? List.of() : List.of(Messages.get("VIEW : {0}", view.getViewName()));
        return new TableDdlDto(dbType.name(), dbType.getLabel(), view.getViewName(), result.ddl(), warnings, pending);
    }

    /** 프로젝트(또는 스키마)의 VIEW 전체 DDL : 참조되는 VIEW 먼저 */
    @Transactional(readOnly = true)
    public TableDdlDto ddlAll(AccountDto accountDto, Long schemaId, String defaultSchemaName) {
        String schemaName = ddlSchemaName(defaultSchemaName);
        Long projectId = accountDto.getProjectId();
        if (schemaId != null) schemaService.resolveSchema(projectId, schemaId); // 다른 프로젝트 스키마 거절
        List<PhysicalView> views = viewRepository.findAll((root, query, builder) -> schemaId == null
                ? builder.equal(root.get("projectId"), projectId)
                : builder.and(builder.equal(root.get("projectId"), projectId), builder.equal(root.get("schema").get("id"), schemaId)));
        DbType dbType = dbType(projectId);
        TableDdlGenerator.Result result = ViewDdlGenerator.generateAll(dbType, views, schemaName);
        List<String> pending = views.stream().filter(v -> !Boolean.TRUE.equals(v.getIsApproval())).map(PhysicalView::getViewName).sorted().toList();
        return new TableDdlDto(dbType.name(), dbType.getLabel(), "VIEW", result.ddl(), result.warnings(),
                pending.isEmpty() ? List.of() : List.of(Messages.get("VIEW : {0}", String.join(", ", pending))));
    }

    /** 저장 전 미리보기 : 등록·수정과 같은 검증을 거친 정의로 DDL 을 만든다 (저장하지 않는다). selfId : 수정 중인 VIEW */
    @Transactional(readOnly = true)
    public TableDdlDto preview(AccountDto accountDto, Long selfId, ViewDto.Save request) {
        Long projectId = accountDto.getProjectId();
        if (selfId != null) viewRepository.findByIdAndProjectId(selfId, projectId).orElseThrow(PhysicalViewService::notFound);
        Spec spec = spec(projectId, request, selfId);
        PhysicalView draft = PhysicalView.builder().projectId(projectId).build();
        apply(draft, spec);
        DbType dbType = dbType(projectId);
        TableDdlGenerator.Result result = ViewDdlGenerator.generate(dbType, draft, null);
        return new TableDdlDto(dbType.name(), dbType.getLabel(), draft.getViewName(), result.ddl(), result.warnings(), List.of());
    }

    // ------------------------------------------------------------------ 등록·수정·승인·삭제

    @Transactional
    public ViewDto.SaveResult create(AccountDto accountDto, ViewDto.Save request) {
        Long projectId = accountDto.getProjectId();
        // 잠금 : VIEW 참조 그래프(기본 스키마 행) + 등록할 스키마의 이름 공간 (ObjectNamespace)
        namespace.lock(projectId, true, targetSchemaId(projectId, request));
        Spec spec = spec(projectId, request, null);
        PhysicalView view = PhysicalView.builder().projectId(projectId).isApproval(AuthUtil.isApprovalAvailable()).build();
        apply(view, spec);
        return new ViewDto.SaveResult(viewRepository.save(view).getId(), true, null);
    }

    @Transactional
    public ViewDto.SaveResult update(AccountDto accountDto, Long id, ViewDto.Save request) {
        Long projectId = accountDto.getProjectId();
        // 지금 스키마와 옮길 스키마의 이름 공간을 함께 잠근다 (VIEW 의 쓰기는 모두 참조 그래프 잠금으로 직렬화되므로 그사이 스키마가 바뀌지 않는다)
        PhysicalView current = viewRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalViewService::notFound);
        namespace.lock(projectId, true, current.getSchema().getId(), targetSchemaId(projectId, request));
        PhysicalView view = lockView(projectId, id);
        Spec spec = spec(projectId, request, id);
        if (!Boolean.TRUE.equals(view.getIsApproval())) {
            apply(view, spec);
            return new ViewDto.SaveResult(id, true, null);
        }
        if (requestRepository.existsByView_IdAndStatus(id, ViewChangeRequest.Status.REQUESTED)) {
            throw invalid("승인 대기 중인 변경 요청이 있습니다. 먼저 승인하거나 반려해주세요.");
        }
        ViewDto.Definition before = definitionOf(view);
        ViewDto.Definition after = spec.definition();
        if (sameDefinition(before, after)) throw invalid("바뀐 내용이 없습니다.");
        ViewChangeRequest changeRequest = requestRepository.save(ViewChangeRequest.builder()
                .projectId(projectId).view(view)
                .beforeData(JSON.writeValueAsString(before)).afterData(JSON.writeValueAsString(after))
                .status(ViewChangeRequest.Status.REQUESTED).build());
        boolean applied = AuthUtil.isApprovalAvailable(); // 승인 권한자가 등록하면 바로 승인되는 것과 같은 규칙
        if (applied) {
            apply(view, spec);
            changeRequest.setStatus(ViewChangeRequest.Status.APPROVED);
        }
        return new ViewDto.SaveResult(id, applied, toDto(changeRequest));
    }

    @Transactional
    public ViewDto.ChangeRequest processRequest(AccountDto accountDto, Long requestId, boolean approve) {
        if (!AuthUtil.isApprovalAvailable()) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        Long projectId = accountDto.getProjectId();
        ViewChangeRequest changeRequest = requestRepository.findByIdAndProjectId(requestId, projectId).orElseThrow(PhysicalViewService::notFound);
        ViewDto.Definition requested = read(changeRequest.getAfterData());
        namespace.lock(projectId, true, changeRequest.getView().getSchema().getId(), requested.schemaId());
        PhysicalView view = lockView(projectId, changeRequest.getView().getId());
        entityManager.refresh(changeRequest); // 잠금 전에 다른 요청 처리가 끝났을 수 있다
        if (changeRequest.getStatus() != ViewChangeRequest.Status.REQUESTED) throw invalid("이미 처리된 변경 요청입니다.");
        if (approve) {
            if (!sameDefinition(definitionOf(view), read(changeRequest.getBeforeData()))) {
                throw invalid("요청 뒤에 VIEW 가 바뀌었습니다. 요청을 반려하고 다시 요청해주세요.");
            }
            // 요청 뒤에 생긴 같은 이름의 테이블·VIEW, 지워진 참조 대상, 새로 생길 순환을 다시 확인한다.
            // 요청은 이름을 선점하지 않는다 (D-26) : 그사이 같은 이름이 확정되었으면 승인하지 않고 요청·확정 정의를 그대로 둔다 (예외로 트랜잭션 전체 롤백)
            ViewDto.Definition after = requested;
            String name = after.viewName() == null ? "" : after.viewName().toUpperCase(Locale.ROOT);
            Long schemaId = schemaService.resolveSchema(projectId, after.schemaId()).getId();
            if (!name.isEmpty() && namespace.conflict(schemaId, name, null, view.getId()) != null) {
                throw new CustomException(ErrorCode.SAVED_DATA_EXISTS,
                        "{0} : 요청 뒤에 같은 이름의 테이블·VIEW 가 등록되어 승인할 수 없습니다. 요청을 반려하고 다른 이름으로 다시 요청해주세요.", name);
            }
            apply(view, spec(projectId, after.toSave(), view.getId()));
        }
        changeRequest.setStatus(approve ? ViewChangeRequest.Status.APPROVED : ViewChangeRequest.Status.REJECTED);
        return toDto(changeRequest);
    }

    @Transactional
    public void updateApproval(AccountDto accountDto, Long id, boolean isApproval) {
        if (!AuthUtil.isApprovalAvailable()) throw new CustomException(ErrorCode.APPROVAL_PERMISSION_DENIED);
        PhysicalView view = lockView(accountDto.getProjectId(), id);
        // 승인 대기 요청은 승인된 정의를 기준으로 만든 것이라, 승인을 취소하면 요청 없이 직접 고칠 수 있게 되어 요청과 어긋난다
        if (!isApproval && requestRepository.existsByView_IdAndStatus(id, ViewChangeRequest.Status.REQUESTED)) {
            throw invalid("승인 대기 중인 변경 요청이 있습니다. 먼저 승인하거나 반려해주세요.");
        }
        view.setIsApproval(isApproval);
    }

    @Transactional
    public void delete(AccountDto accountDto, Long id) {
        Long projectId = accountDto.getProjectId();
        PhysicalView current = viewRepository.findByIdAndProjectId(id, projectId).orElseThrow(PhysicalViewService::notFound);
        namespace.lock(projectId, true, current.getSchema().getId()); // 이 VIEW 를 새로 참조하는 등록·승인과 직렬화
        PhysicalView view = lockView(projectId, id);
        if (Boolean.TRUE.equals(view.getIsApproval())) throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_DELETED);
        List<String> referencing = viewRepository.findNamesReferencingView(id);
        if (!referencing.isEmpty()) {
            throw new CustomException(ErrorCode.RELATION_EXISTS, "VIEW {0} 이(가) 이 VIEW 를 참조합니다. 그 VIEW 의 참조 객체에서 먼저 빼주세요.", String.join(", ", referencing));
        }
        viewRepository.delete(view); // 출력 컬럼·참조 객체·변경 요청은 함께 지워진다
    }

    // ------------------------------------------------------------------ 검증

    /** 검증을 마친 참조 객체 하나 (내부 : table 또는 view, 미확인 : 이름만) */
    private record RefSpec(PhysicalViewReference.Type type, TableInfo table, PhysicalView view, String schemaName, String objectName) {
        String key() {
            return referenceKey(type, table == null ? null : table.getId(), view == null ? null : view.getId(), schemaName, objectName);
        }

        ViewDto.Reference dto() {
            if (table != null) return new ViewDto.Reference(type.name(), table.getId(), null, table.getSchema() == null ? null : table.getSchema().getSchemaName(), table.getTableName());
            if (view != null) return new ViewDto.Reference(type.name(), null, view.getId(), view.getSchema().getSchemaName(), view.getViewName());
            return new ViewDto.Reference(type.name(), null, null, schemaName, objectName);
        }
    }

    /** 검증을 마친 VIEW 정의 (등록·수정·변경 요청 승인·미리보기가 같은 기준) */
    private record Spec(SchemaInfo schema, String name, String korName, String description, String sql,
                        List<ViewDto.Column> columns, List<RefSpec> references) {
        ViewDto.Definition definition() {
            return new ViewDto.Definition(schema.getId(), schema.getSchemaName(), name, korName, description, sql, columns,
                    references.stream().map(RefSpec::dto).toList());
        }
    }

    private Spec spec(Long projectId, ViewDto.Save request, Long selfId) {
        if (request == null) throw invalid("VIEW 정의가 없습니다.");
        SchemaInfo schema = schemaService.resolveSchema(projectId, request.schemaId());
        String name = identifier(request.viewName(), "VIEW명");
        ObjectNamespace.Kind conflict = namespace.conflict(schema.getId(), name, null, selfId);
        if (conflict == ObjectNamespace.Kind.TABLE) {
            throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, "{0} : 같은 이름의 테이블이 있습니다. 테이블과 VIEW 는 같은 이름을 쓸 수 없습니다.", name);
        }
        if (conflict == ObjectNamespace.Kind.VIEW) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, name);
        String korName = text(request.viewKorName(), "논리명", TEXT_MAX);
        String description = text(request.viewDescription(), "설명", DESCRIPTION_MAX);
        ViewDefinitionSql.check(dbType(projectId), request.definition());

        List<ViewDto.Column> columns = new ArrayList<>();
        Set<String> columnNames = new HashSet<>();
        List<ViewDto.Column> requestedColumns = request.columns() == null ? List.of() : request.columns();
        if (requestedColumns.size() > COLUMN_MAX) throw invalid("출력 컬럼은 {0}개까지 등록할 수 있습니다.", COLUMN_MAX);
        for (ViewDto.Column column : requestedColumns) {
            if (column == null) throw invalid("출력 컬럼 정보가 없습니다.");
            String columnName = identifier(column.columnName(), "출력 컬럼명");
            if (!columnNames.add(columnName)) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, Messages.get("출력 컬럼 {0}", columnName));
            columns.add(new ViewDto.Column(columnName, text(column.columnKorName(), "컬럼 논리명", TEXT_MAX),
                    text(column.dataType(), "데이터 타입", TEXT_MAX), text(column.columnDescription(), "컬럼 설명", TEXT_MAX)));
        }

        List<ViewDto.Reference> requestedReferences = request.references() == null ? List.of() : request.references();
        if (requestedReferences.size() > REFERENCE_MAX) throw invalid("참조 객체는 {0}개까지 등록할 수 있습니다.", REFERENCE_MAX);
        Map<String, RefSpec> references = new LinkedHashMap<>();
        for (ViewDto.Reference reference : requestedReferences) {
            RefSpec ref = reference(projectId, schema, reference, selfId);
            if (references.putIfAbsent(ref.key(), ref) != null) throw invalid("같은 참조 객체가 두 번 들어 있습니다 : {0}", label(ref));
        }
        List<RefSpec> refs = new ArrayList<>(references.values());
        if (selfId != null) rejectCycle(projectId, selfId, name, refs);
        return new Spec(schema, name, korName, description, request.definition(), columns, refs);
    }

    private RefSpec reference(Long projectId, SchemaInfo viewSchema, ViewDto.Reference reference, Long selfId) {
        if (reference == null) throw invalid("참조 객체 정보가 없습니다.");
        PhysicalViewReference.Type type;
        try {
            type = PhysicalViewReference.Type.valueOf(reference.referenceType() == null ? "" : reference.referenceType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("참조 객체 유형은 TABLE / VIEW 중 하나입니다.");
        }
        if (reference.tableId() != null && reference.viewId() != null) throw invalid("참조 객체는 테이블과 VIEW 중 하나만 지정합니다.");
        if (reference.tableId() != null) {
            if (type != PhysicalViewReference.Type.TABLE) throw invalid("참조 객체 유형이 대상과 맞지 않습니다.");
            TableInfo table = tableInfoRepository.findByIdAndProjectId(reference.tableId(), projectId);
            if (table == null) throw invalid("참조할 테이블을 찾을 수 없습니다. (#{0})", reference.tableId());
            return new RefSpec(type, table, null, null, null);
        }
        if (reference.viewId() != null) {
            if (type != PhysicalViewReference.Type.VIEW) throw invalid("참조 객체 유형이 대상과 맞지 않습니다.");
            if (reference.viewId().equals(selfId)) throw invalid("VIEW 는 자기 자신을 참조할 수 없습니다.");
            PhysicalView view = viewRepository.findByIdAndProjectId(reference.viewId(), projectId)
                    .orElseThrow(() -> invalid("참조할 VIEW 를 찾을 수 없습니다. (#{0})", reference.viewId()));
            return new RefSpec(type, null, view, null, null);
        }
        // 미확인 참조 : 모델에 있는 객체를 이름으로만 적지 않는다 (내부 참조로 연결해야 순서·영향 범위에 쓰인다)
        String objectName = externalName(reference.objectName(), "참조 객체명");
        if (objectName == null) throw invalid("참조 객체를 고르거나, 모델에 없는 객체면 이름을 입력해주세요.");
        String schemaName = externalName(reference.schemaName(), "참조 스키마명");
        // 스키마명이 없으면 이 VIEW 의 스키마, 있으면 그 이름의 모델 스키마 (없는 스키마면 모델 밖 객체)
        SchemaInfo target = schemaName == null ? viewSchema : schemaInfoRepository.findFirstByProjectIdAndSchemaNameIgnoreCase(projectId, schemaName).orElse(null);
        if (target != null && namespace.conflict(target.getId(), objectName, null, null) != null) {
            throw invalid("{0} 은(는) 모델에 있는 객체입니다. 목록에서 골라 내부 참조로 연결해주세요.", objectName);
        }
        return new RefSpec(type, null, null, schemaName, objectName);
    }

    /** 등록·수정할 스키마 (요청에 없으면 기본 스키마). 다른 프로젝트 스키마는 거절 */
    private Long targetSchemaId(Long projectId, ViewDto.Save request) {
        return schemaService.resolveSchema(projectId, request == null ? null : request.schemaId()).getId();
    }

    /**
     * VIEW 간 참조의 순환 검사 : 확정된(승인 대기 요청이 아닌) 참조 그래프에서 이 VIEW 의 나가는 참조를 새 것으로 바꿨을 때 자기 자신으로 돌아오는 길이 있으면 거절한다.
     * 이름 공간 잠금 안에서 부르므로 동시 승인이 서로의 결과를 본다.
     */
    private void rejectCycle(Long projectId, Long selfId, String selfName, List<RefSpec> references) {
        List<PhysicalView> targets = references.stream().map(RefSpec::view).filter(Objects::nonNull).toList();
        if (targets.isEmpty()) return;
        Map<Long, Set<Long>> edges = new HashMap<>();
        for (Object[] edge : viewRepository.findViewEdges(projectId)) {
            Long from = (Long) edge[0];
            if (!from.equals(selfId)) edges.computeIfAbsent(from, key -> new LinkedHashSet<>()).add((Long) edge[1]);
        }
        for (PhysicalView target : targets) {
            List<Long> path = pathTo(edges, target.getId(), selfId);
            if (path != null) {
                Map<Long, String> names = viewRepository.findAllByProjectIdAndIdIn(projectId, path).stream().collect(Collectors.toMap(PhysicalView::getId, PhysicalView::getViewName));
                String chain = selfName + " → " + path.stream().map(id -> id.equals(selfId) ? selfName : names.getOrDefault(id, "#" + id)).collect(Collectors.joining(" → "));
                throw invalid("순환 참조가 됩니다 : {0}", chain);
            }
        }
    }

    /** from 에서 to 까지의 경로 (from 포함, to 포함). 없으면 null */
    private static List<Long> pathTo(Map<Long, Set<Long>> edges, Long from, Long to) {
        Map<Long, Long> previous = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(from));
        Set<Long> seen = new HashSet<>(List.of(from));
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current.equals(to)) {
                List<Long> path = new ArrayList<>();
                for (Long at = to; at != null; at = previous.get(at)) path.add(0, at);
                return path;
            }
            for (Long next : edges.getOrDefault(current, Set.of())) {
                if (seen.add(next)) {
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** 정의를 VIEW 에 쓴다. 출력 컬럼은 순서 자리를 그대로 두고 내용만 바꾸고 ((VIEW, 순서) 고유 인덱스), 참조 객체는 대상이 같은 행을 남긴다 */
    private static void apply(PhysicalView view, Spec spec) {
        view.setSchema(spec.schema());
        view.setViewName(spec.name());
        view.setViewKorName(spec.korName());
        view.setViewDescription(spec.description());
        view.setDefinition(spec.sql());
        List<PhysicalViewColumn> rows = view.getColumns();
        for (int i = 0; i < spec.columns().size(); i++) {
            if (i >= rows.size()) rows.add(PhysicalViewColumn.builder().view(view).build());
            ViewDto.Column column = spec.columns().get(i);
            PhysicalViewColumn row = rows.get(i);
            row.setColumnName(column.columnName());
            row.setColumnKorName(column.columnKorName());
            row.setDataType(column.dataType());
            row.setColumnDescription(column.columnDescription());
            row.setColumnOrder(i + 1);
        }
        while (rows.size() > spec.columns().size()) rows.remove(rows.size() - 1);

        Map<String, RefSpec> wanted = new LinkedHashMap<>();
        spec.references().forEach(r -> wanted.put(r.key(), r));
        view.getReferences().removeIf(r -> !wanted.containsKey(key(r)));
        Set<String> kept = new HashSet<>();
        for (PhysicalViewReference row : view.getReferences()) {
            RefSpec ref = wanted.get(key(row));
            kept.add(ref.key());
            if (!row.isInternal()) { // 미확인 참조는 대소문자만 바꿨을 수 있다
                row.setSchemaName(ref.schemaName());
                row.setObjectName(ref.objectName());
            }
        }
        wanted.values().stream().filter(r -> !kept.contains(r.key())).forEach(r -> view.getReferences().add(PhysicalViewReference.builder()
                .view(view).referenceType(r.type()).referencedTable(r.table()).referencedView(r.view()).schemaName(r.schemaName()).objectName(r.objectName()).build()));
    }

    /** 같은 정의인가 : 기본 정보·정의 SQL(원문)·출력 컬럼(순서 포함)·참조 객체(순서 무관, 내부 참조는 ID 로) */
    private static boolean sameDefinition(ViewDto.Definition a, ViewDto.Definition b) {
        return Objects.equals(a.schemaId(), b.schemaId()) && Objects.equals(a.viewName(), b.viewName())
                && Objects.equals(a.viewKorName(), b.viewKorName()) && Objects.equals(a.viewDescription(), b.viewDescription())
                && Objects.equals(a.definition(), b.definition())
                && Objects.equals(a.columns() == null ? List.of() : a.columns(), b.columns() == null ? List.of() : b.columns())
                && referenceKeys(a).equals(referenceKeys(b));
    }

    private static Set<String> referenceKeys(ViewDto.Definition d) {
        return (d.references() == null ? List.<ViewDto.Reference>of() : d.references()).stream()
                .map(r -> referenceKey(type(r.referenceType()), r.tableId(), r.viewId(), r.schemaName(), r.objectName())).collect(Collectors.toSet());
    }

    private static String referenceKey(PhysicalViewReference.Type type, Long tableId, Long viewId, String schemaName, String objectName) {
        if (tableId != null) return "T" + tableId;
        if (viewId != null) return "V" + viewId;
        return "X" + type + ":" + (schemaName == null ? "" : schemaName.toUpperCase(Locale.ROOT)) + "." + (objectName == null ? "" : objectName.toUpperCase(Locale.ROOT));
    }

    private static String key(PhysicalViewReference r) {
        return referenceKey(r.getReferenceType(), r.getReferencedTable() == null ? null : r.getReferencedTable().getId(),
                r.getReferencedView() == null ? null : r.getReferencedView().getId(), r.getSchemaName(), r.getObjectName());
    }

    private static PhysicalViewReference.Type type(String code) {
        try {
            return PhysicalViewReference.Type.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            return PhysicalViewReference.Type.TABLE;
        }
    }

    private static String label(RefSpec ref) {
        ViewDto.Reference r = ref.dto();
        return r.schemaName() == null ? r.objectName() : r.schemaName() + "." + r.objectName();
    }

    static ViewDto.Definition definitionOf(PhysicalView view) {
        return new ViewDto.Definition(view.getSchema().getId(), view.getSchema().getSchemaName(), view.getViewName(), view.getViewKorName(), view.getViewDescription(),
                view.getDefinition(),
                view.getColumns().stream().map(c -> new ViewDto.Column(c.getColumnName(), c.getColumnKorName(), c.getDataType(), c.getColumnDescription())).toList(),
                view.getReferences().stream().map(r -> new RefSpec(r.getReferenceType(), r.getReferencedTable(), r.getReferencedView(), r.getSchemaName(), r.getObjectName()).dto()).toList());
    }

    /** VIEW 를 찾고 행을 잠근 뒤 최신 상태로 다시 읽는다 (잠그기 전에 다른 트랜잭션이 고쳤거나 지웠을 수 있다) */
    private PhysicalView lockView(Long projectId, Long id) {
        PhysicalView view = viewRepository.findForUpdate(id, projectId).orElseThrow(PhysicalViewService::notFound);
        try {
            entityManager.refresh(view);
        } catch (EntityNotFoundException e) {
            throw notFound();
        }
        return view;
    }

    /** VIEW·출력 컬럼 이름 : 따옴표 없는 식별자(스키마명과 같은 규칙, 63자), 대문자로 저장, 대표 예약어 거절 */
    private static String identifier(String value, String label) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw invalid("{0}을(를) 입력해주세요.", Messages.get(label));
        if (!SchemaInfo.NAME_PATTERN.matcher(name).matches()) {
            throw invalid("{0} {1} 은(는) 영문자·숫자·_·$ 로 된 63자 이내의 이름이어야 하고 영문자나 _ 로 시작해야 합니다.", Messages.get(label), name);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (RESERVED.contains(upper)) throw invalid("{0} {1} 은(는) SQL 예약어라 쓸 수 없습니다.", Messages.get(label), upper);
        return upper;
    }

    private static String externalName(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String name = value.trim();
        if (!EXTERNAL_NAME.matcher(name).matches()) throw invalid("{0} {1} 의 형식이 올바르지 않습니다. (공백·따옴표·마침표 없이 255자 이내)", Messages.get(label), name);
        return name;
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.length() > max) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get(label), max);
        return text;
    }

    private static String ddlSchemaName(String defaultSchemaName) {
        // 테이블 DDL 보기와 같은 기준 : 기본 스키마 객체에 붙일 스키마명은 식별자 형식만
        String schemaName = notBlank(defaultSchemaName) ? defaultSchemaName.trim() : null;
        if (schemaName != null && !SchemaInfo.NAME_PATTERN.matcher(schemaName).matches()) throw new CustomException(ErrorCode.INVALID_SCHEMA_NAME);
        return schemaName;
    }

    private static ViewDto.ChangeRequest toDto(ViewChangeRequest r) {
        boolean processed = r.getStatus() != ViewChangeRequest.Status.REQUESTED;
        BaseEntity.Audit audit = r.audit();
        return new ViewDto.ChangeRequest(r.getId(), r.getView().getId(), r.getStatus().name(), read(r.getBeforeData()), read(r.getAfterData()),
                audit.createdBy(), audit.created(), processed ? audit.updatedBy() : null, processed ? audit.updated() : null);
    }

    private static ViewDto.Definition read(String json) {
        return JSON.readValue(json, ViewDto.Definition.class);
    }

    private DbType dbType(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getDbType).map(DbType::orDefault).orElse(DbType.DEFAULT);
    }

    private static String yn(Boolean value) {
        return Boolean.TRUE.equals(value) ? "Y" : "N";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, message, args);
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "대상을 찾을 수 없습니다.");
    }
}
