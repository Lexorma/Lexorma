package com.lexorma.data.erd.service.impl;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.erd.domain.dto.ErdDto;
import com.lexorma.data.erd.domain.entity.ErdLayout;
import com.lexorma.data.erd.repository.ErdGraphRepository;
import com.lexorma.data.erd.repository.ErdLayoutRepository;
import com.lexorma.data.erd.service.ErdService;
import com.lexorma.data.erd.service.PhysicalCardinality;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ErdServiceImpl implements ErdService {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<Long, ErdDto.Point>> NODES_TYPE = new TypeReference<>() {
    };
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final double MAX_COORDINATE = 10_000_000;
    private static final double MIN_ZOOM = 0.02, MAX_ZOOM = 4;

    private final ErdGraphRepository erdGraphRepository;
    private final ErdLayoutRepository erdLayoutRepository;
    private final SchemaInfoRepository schemaInfoRepository;
    private final TableInfoRepository tableInfoRepository;

    @Override
    @Transactional(readOnly = true)
    public ErdDto.Graph selectGraph(AccountDto accountDto, ErdDto.Search search, int page, int size, ErdDto.RelationScope relationScope) {
        Long projectId = accountDto.getProjectId();
        if (page < 0 || size < 1 || size > MAX_TABLES) {
            throw invalid("한 번에 1~{0}개 테이블까지 조회할 수 있습니다.", MAX_TABLES);
        }
        validateSearch(projectId, search);

        long total = erdGraphRepository.countTables(projectId, search);
        long offset = (long) page * size;
        List<ErdDto.Table> tables = offset < total ? erdGraphRepository.findTables(projectId, search, offset, size) : List.of();
        return graph(projectId, tables, total, offset + tables.size() < total, relationScope);
    }

    @Override
    @Transactional(readOnly = true)
    public ErdDto.Graph selectNeighbors(AccountDto accountDto, Long tableId, int depth, ErdDto.RelationScope relationScope) {
        Long projectId = accountDto.getProjectId();
        if (depth < 1 || depth > MAX_DEPTH) {
            throw invalid("이웃 탐색은 1~{0}단계까지 가능합니다.", MAX_DEPTH);
        }
        if (tableId == null || tableInfoRepository.findByIdAndProjectId(tableId, projectId) == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }

        // 단계별 BFS : 단계마다 관계 쿼리 1번. 한도를 넘으면 그 단계에서 멈춘다
        Set<Long> found = new LinkedHashSet<>(List.of(tableId));
        Set<Long> frontier = Set.of(tableId);
        boolean truncated = false;
        for (int level = 0; level < depth && !frontier.isEmpty() && !truncated; level++) {
            Set<Long> next = new LinkedHashSet<>();
            for (Long id : erdGraphRepository.findLinkedTableIds(projectId, frontier)) {
                if (found.contains(id)) {
                    continue;
                }
                if (found.size() >= MAX_TABLES) {
                    truncated = true;
                    break;
                }
                found.add(id);
                next.add(id);
            }
            frontier = next;
        }
        List<ErdDto.Table> tables = erdGraphRepository.findTablesByIds(projectId, found);
        return graph(projectId, tables, tables.size(), truncated, relationScope);
    }

    @Override
    @Transactional(readOnly = true)
    public ErdDto.TableHits findTables(AccountDto accountDto, ErdDto.Search search, int size) {
        Long projectId = accountDto.getProjectId();
        if (size < 1 || size > MAX_FINDER) {
            throw invalid("테이블 찾기는 1~{0}개까지 조회할 수 있습니다.", MAX_FINDER);
        }
        validateSearch(projectId, search);
        ErdDto.Search condition = new ErdDto.Search(search.schemaId(), search.keyword(), null, null);
        return new ErdDto.TableHits(erdGraphRepository.findTableHits(projectId, condition, size), erdGraphRepository.countTables(projectId, condition));
    }

    @Override
    @Transactional(readOnly = true)
    public ErdDto.RelationPage selectTableRelations(AccountDto accountDto, Long tableId, int page, int size) {
        Long projectId = accountDto.getProjectId();
        // 목록 조회 정책 (list-query-policy) 과 같은 한도·오류 : 크기 1~100, 음수 페이지·int 를 넘는 offset 은 거절 (잘라 쓰지 않는다)
        if (page < 0 || size < 1 || size > MAX_RELATION_PAGE || (long) page * size > Integer.MAX_VALUE) {
            throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST);
        }
        if (tableId == null || tableInfoRepository.findByIdAndProjectId(tableId, projectId) == null) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        long total = erdGraphRepository.countTableRelations(projectId, tableId);
        long offset = (long) page * size;
        List<PhysicalModelDto.Relation> relations = offset < total ? erdGraphRepository.findTableRelations(projectId, tableId, offset, size) : List.of();
        Set<Long> tableIds = new LinkedHashSet<>(List.of(tableId));
        relations.forEach(r -> {
            tableIds.add(r.sourceTableId());
            tableIds.add(r.targetTableId());
        });
        Map<Long, ErdDto.Table> tables = new LinkedHashMap<>();
        erdGraphRepository.findTablesByIds(projectId, tableIds).forEach(t -> tables.put(t.id(), t));
        Map<Long, ErdDto.Cardinality> cardinalities = relations.isEmpty() ? Map.of()
                : PhysicalCardinality.judgeAll(relations, PhysicalCardinality.Context.of(
                        erdGraphRepository.findColumnFlags(projectId, tableIds), erdGraphRepository.findUniqueKeys(projectId, tableIds)));
        return new ErdDto.RelationPage(relations, cardinalities, tables, total);
    }

    @Override
    @Transactional(readOnly = true)
    public ErdDto.Layout selectLayout(AccountDto accountDto) {
        Long projectId = accountDto.getProjectId();
        return erdLayoutRepository.findByProjectIdAndAccountId(projectId, accountId(accountDto))
                .map(layout -> {
                    Map<Long, ErdDto.Point> nodes = readNodes(layout);
                    nodes.keySet().retainAll(erdGraphRepository.findExistingTableIds(projectId, nodes.keySet()));
                    return toDto(layout, nodes);
                })
                .orElseGet(() -> new ErdDto.Layout(null, Map.of(), null));
    }

    @Override
    @Transactional
    public ErdDto.Layout saveLayout(AccountDto accountDto, ErdDto.Layout request) {
        validate(request);
        Long projectId = accountDto.getProjectId();
        Long accountId = accountId(accountDto);

        ErdLayout layout = erdLayoutRepository.findByProjectIdAndAccountId(projectId, accountId)
                .orElseGet(() -> ErdLayout.builder().projectId(projectId).accountId(accountId).build());
        if (!Objects.equals(layout.getVersion(), request.version())) {
            throw new CustomException(ErrorCode.ERD_LAYOUT_CONFLICT);
        }

        // 저장된 좌표 + 요청 좌표 (요청 우선). 프로젝트에 없는 테이블(삭제됨)은 정리하고, 요청에 있으면 거절한다
        Map<Long, ErdDto.Point> nodes = layout.getId() == null ? new LinkedHashMap<>() : readNodes(layout);
        nodes.putAll(request.nodes());
        Set<Long> existing = erdGraphRepository.findExistingTableIds(projectId, nodes.keySet());
        if (!existing.containsAll(request.nodes().keySet())) {
            throw new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED);
        }
        nodes.keySet().retainAll(existing);
        if (nodes.size() > MAX_LAYOUT_NODES) {
            throw invalid("배치는 테이블 {0}개까지 저장할 수 있습니다.", MAX_LAYOUT_NODES);
        }

        layout.setNodePositions(OBJECT_MAPPER.writeValueAsString(nodes));
        layout.setViewportX(request.viewport().x());
        layout.setViewportY(request.viewport().y());
        layout.setViewportZoom(request.viewport().zoom());
        try {
            // 동시에 저장한 다른 탭 : 최초 저장은 (프로젝트, 사용자) UNIQUE, 이후는 @Version 으로 걸린다
            return toDto(erdLayoutRepository.saveAndFlush(layout), nodes);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ERD_LAYOUT_CONFLICT);
        }
    }

    /**
     * 테이블 → 컬럼·관계. ALL : 범위 테이블이 한쪽인 관계 전부 (예전 응답 그대로 — 범위 밖 수는 null).
     * INNER : 양 끝이 범위 안인 관계 + 범위 밖 관계 수 + 그 관계들의 카디널리티 (컬럼은 이미 조회했다 — 쿼리는 UNIQUE 키 2번만 더한다).
     * ALL 은 예전 쿼리 수·응답을 지키려고 카디널리티를 계산하지 않는다 (빈 맵)
     */
    private ErdDto.Graph graph(Long projectId, List<ErdDto.Table> tables, long total, boolean truncated, ErdDto.RelationScope relationScope) {
        List<Long> ids = tables.stream().map(ErdDto.Table::id).toList();
        List<ErdDto.Column> columns = erdGraphRepository.findColumns(projectId, ids);
        boolean inner = relationScope == ErdDto.RelationScope.INNER;
        List<PhysicalModelDto.Relation> relations = inner ? erdGraphRepository.findInnerRelations(projectId, ids) : erdGraphRepository.findRelations(projectId, ids);
        Set<Long> scope = new HashSet<>(ids);
        List<PhysicalModelDto.Relation> drawn = relations.stream().filter(r -> scope.contains(r.sourceTableId()) && scope.contains(r.targetTableId())).toList();
        Map<Long, ErdDto.Cardinality> cardinalities = Map.of();
        if (inner && !drawn.isEmpty()) {
            Set<Long> related = new HashSet<>();
            drawn.forEach(r -> {
                related.add(r.sourceTableId());
                related.add(r.targetTableId());
            });
            cardinalities = PhysicalCardinality.judgeAll(drawn, PhysicalCardinality.Context.of(
                    columns.stream().map(c -> new PhysicalCardinality.ColumnFlags(c.id(), c.tableId(), c.isNullable(), c.isPk(), c.isUnique(), c.isRequired())).toList(),
                    erdGraphRepository.findUniqueKeys(projectId, related)));
        }
        return new ErdDto.Graph(tables, columns, relations, total, truncated,
                inner ? outsideRelations(erdGraphRepository.countRelationEnds(projectId, ids), drawn) : null,
                inner ? erdGraphRepository.findFkColumnIds(projectId, ids) : null, cardinalities);
    }

    /** 범위 밖 관계 수 = 테이블의 관계 끝 수(DB 집계) − 양 끝이 범위 안인 관계의 끝 수. 0 인 테이블은 넣지 않는다 */
    private static Map<Long, Long> outsideRelations(Map<Long, Long> ends, List<PhysicalModelDto.Relation> drawn) {
        Map<Long, Long> outside = new HashMap<>(ends);
        drawn.forEach(r -> {
            outside.merge(r.sourceTableId(), -1L, Long::sum);
            outside.merge(r.targetTableId(), -1L, Long::sum);
        });
        outside.values().removeIf(count -> count <= 0);
        return outside;
    }

    private void validateSearch(Long projectId, ErdDto.Search search) {
        if (search.keyword() != null && search.keyword().length() > MAX_KEYWORD_LENGTH) {
            throw invalid("검색어는 {0}자까지 입력할 수 있습니다.", MAX_KEYWORD_LENGTH);
        }
        if (search.schemaId() != null && schemaInfoRepository.findByIdAndProjectId(search.schemaId(), projectId).isEmpty()) {
            throw new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED);
        }
    }

    private static void validate(ErdDto.Layout request) {
        if (request == null || request.nodes() == null || request.viewport() == null) {
            throw invalid("배치 정보가 없습니다.");
        }
        if (request.nodes().size() > MAX_LAYOUT_NODES) {
            throw invalid("배치는 테이블 {0}개까지 저장할 수 있습니다.", MAX_LAYOUT_NODES);
        }
        ErdDto.Viewport viewport = request.viewport();
        boolean valid = coordinate(viewport.x()) && coordinate(viewport.y())
                && Double.isFinite(viewport.zoom()) && viewport.zoom() >= MIN_ZOOM && viewport.zoom() <= MAX_ZOOM
                && request.nodes().entrySet().stream().allMatch(e -> e.getKey() != null && e.getValue() != null
                        && coordinate(e.getValue().x()) && coordinate(e.getValue().y()));
        if (!valid) {
            throw invalid("좌표 또는 확대 비율이 올바르지 않습니다.");
        }
    }

    private static boolean coordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }

    private static Map<Long, ErdDto.Point> readNodes(ErdLayout layout) {
        return new LinkedHashMap<>(OBJECT_MAPPER.readValue(layout.getNodePositions(), NODES_TYPE));
    }

    private static ErdDto.Layout toDto(ErdLayout layout, Map<Long, ErdDto.Point> nodes) {
        return new ErdDto.Layout(layout.getVersion(), nodes,
                new ErdDto.Viewport(layout.getViewportX(), layout.getViewportY(), layout.getViewportZoom()));
    }

    private static Long accountId(AccountDto accountDto) {
        return Long.valueOf(accountDto.getId());
    }

    private static CustomException invalid(String reason, Object... args) {
        return new CustomException(ErrorCode.INVALID_ERD_REQUEST, reason, args);
    }
}
