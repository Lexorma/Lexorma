package com.koboolean.metamora.data.erd.service.impl;

import com.koboolean.metamora.common.exception.CustomException;
import com.koboolean.metamora.common.exception.domain.ErrorCode;
import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.data.erd.domain.dto.ErdDto;
import com.koboolean.metamora.data.erd.domain.entity.ErdLayout;
import com.koboolean.metamora.data.erd.repository.ErdGraphRepository;
import com.koboolean.metamora.data.erd.repository.ErdLayoutRepository;
import com.koboolean.metamora.data.erd.service.ErdService;
import com.koboolean.metamora.data.schema.repository.SchemaInfoRepository;
import com.koboolean.metamora.data.table.repository.TableInfoRepository;
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
    public ErdDto.Graph selectGraph(AccountDto accountDto, ErdDto.Search search, int page, int size) {
        Long projectId = accountDto.getProjectId();
        if (page < 0 || size < 1 || size > MAX_TABLES) {
            throw invalid("한 번에 1~" + MAX_TABLES + "개 테이블까지 조회할 수 있습니다.");
        }
        if (search.keyword() != null && search.keyword().length() > MAX_KEYWORD_LENGTH) {
            throw invalid("검색어는 " + MAX_KEYWORD_LENGTH + "자까지 입력할 수 있습니다.");
        }
        if (search.schemaId() != null && schemaInfoRepository.findByIdAndProjectId(search.schemaId(), projectId).isEmpty()) {
            throw new CustomException(ErrorCode.SCHEMA_IS_NOT_DEFINED);
        }

        long total = erdGraphRepository.countTables(projectId, search);
        long offset = (long) page * size;
        List<ErdDto.Table> tables = offset < total ? erdGraphRepository.findTables(projectId, search, offset, size) : List.of();
        return graph(projectId, tables, total, offset + tables.size() < total);
    }

    @Override
    @Transactional(readOnly = true)
    public ErdDto.Graph selectNeighbors(AccountDto accountDto, Long tableId, int depth) {
        Long projectId = accountDto.getProjectId();
        if (depth < 1 || depth > MAX_DEPTH) {
            throw invalid("이웃 탐색은 1~" + MAX_DEPTH + "단계까지 가능합니다.");
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
        return graph(projectId, tables, tables.size(), truncated);
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
            throw invalid("배치는 테이블 " + MAX_LAYOUT_NODES + "개까지 저장할 수 있습니다.");
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

    private ErdDto.Graph graph(Long projectId, List<ErdDto.Table> tables, long total, boolean truncated) {
        List<Long> ids = tables.stream().map(ErdDto.Table::id).toList();
        return new ErdDto.Graph(tables, erdGraphRepository.findColumns(projectId, ids),
                erdGraphRepository.findRelations(projectId, ids), total, truncated);
    }

    private static void validate(ErdDto.Layout request) {
        if (request == null || request.nodes() == null || request.viewport() == null) {
            throw invalid("배치 정보가 없습니다.");
        }
        if (request.nodes().size() > MAX_LAYOUT_NODES) {
            throw invalid("배치는 테이블 " + MAX_LAYOUT_NODES + "개까지 저장할 수 있습니다.");
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

    private static CustomException invalid(String reason) {
        return new CustomException(ErrorCode.INVALID_ERD_REQUEST, reason);
    }
}
