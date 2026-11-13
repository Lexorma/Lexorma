import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Background, Controls, MarkerType, ReactFlow, ReactFlowProvider, useNodesState, useReactFlow } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../styles/erd.css';
import '../../styles/source.css';
import { Label, Mono } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { api, enc } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { t, tx } from '../../lib/i18n';
import { cancelLayout, gridLayout, runLayout } from '../erd/layout';
import { MAX_ROWS, NODE_WIDTH, nodeHeight } from '../erd/model';
import { useSimpleNodes } from '../erd/TableNode';
import type { Execution, PageResult, SourceConnection } from './model';
import { TableDetailView } from './SourceCatalogPage';
import { SOURCE_TABLE_HANDLE, sourceColumnHandle, SourceErdNode } from './SourceErdNode';
import { ConnectionPicker, NoConnection, orDash, useSelectedConnection, useSourceConnections } from './shared';
import {
  cachedLayout, matches, neighbors, rememberLayout, RENDER_LIMIT, searchKey, sourceNodeId, tableIdOf,
  type SourceEdge, type SourceErdGraph, type SourceErdRelation, type SourceErdTable, type SourceNode,
} from './sourceErd';

const nodeTypes = { sourceTable: SourceErdNode };
/** 스키마마다 탐색 목록에 보여 주는 최대 객체 수 (나머지는 검색으로 좁힌다) */
const EXPLORER_LIMIT = 200;
const EDGE_COLOR = 'var(--gh-fg-muted)';
const EDGE_HIGHLIGHT = 'var(--gh-accent)';

type Params = ReturnType<typeof useSelectedConnection>['searchParams'];
type SetParams = ReturnType<typeof useSelectedConnection>['setSearchParams'];

/**
 * 원본 DB ERD (M-4b) : 수집해 둔 원본 카탈로그(그 반영 순서의 테이블 버전)로 그리는 읽기 전용 ERD.
 * 물리 모델 ERD 와 데이터·배치·편집이 모두 따로다 (노드 모양·자동 배치 엔진만 같이 쓴다). 실제 FK 만 선으로 그리고, 원본 DB 에 접속하지 않는다.
 * 보기 범위 : 고른 스키마(들) 또는 한 객체에서 FK 를 따라간 이웃. 한 번에 그리는 수는 RENDER_LIMIT 까지다.
 */
export default function SourceErdPage() {
  const connections = useSourceConnections();
  const { selected, select, searchParams, setSearchParams } = useSelectedConnection(connections.data);
  return (
    <div className="erd-page">
      <PageHeader category={t('원본 DB 관리')} title={t('원본 DB ERD')}
        description={t('수집한 원본 카탈로그로 그린 읽기 전용 ERD 입니다. 원본 DB 에 정의된 FK 만 관계선으로 표시하며, Lexorma 물리 모델 ERD 와는 별개입니다. 노드를 옮겨도 아무것도 저장되지 않습니다.')} />
      {connections.isError ? <p role="alert">{t('연결 목록을 불러오지 못했습니다.')} {connections.error.message}</p>
        : connections.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
        : !selected ? <NoConnection />
        : (
          <ReactFlowProvider key={selected.id}>
            <ErdBrowser connections={connections.data} connection={selected} onSelectConnection={select} params={searchParams} setParams={setSearchParams} />
          </ReactFlowProvider>
        )}
    </div>
  );
}

function ErdBrowser({ connections, connection, onSelectConnection, params, setParams }: {
  connections: SourceConnection[];
  connection: SourceConnection;
  onSelectConnection: (id: number) => void;
  params: Params;
  setParams: SetParams;
}) {
  const flow = useReactFlow<SourceNode, SourceEdge>();
  const simple = useSimpleNodes();
  const order = Number(params.get('order')) || null;
  const focus = Number(params.get('focus')) || null;
  const depth = Math.min(3, Math.max(1, Number(params.get('depth')) || 1));
  const selectedTableId = Number(params.get('table')) || null;
  const schemaParam = params.get('schemas');
  const [query, setQuery] = useState('');
  const [selectedRelation, setSelectedRelation] = useState<string | null>(null);
  const [force, setForce] = useState(false);
  const [arranging, setArranging] = useState(false);
  const [message, setMessage] = useState('');
  /** [자동 배치] : 캐시를 비우고 다시 계산한다 */
  const [relayout, setRelayout] = useState(0);
  const [nodes, setNodes, onNodesChange] = useNodesState<SourceNode>([]);
  const centerRequest = useRef<number | null>(null);

  const update = (values: Record<string, string | number | null>) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    Object.entries(values).forEach(([key, value]) => (value == null || value === '' ? next.delete(key) : next.set(key, String(value))));
    return next;
  }, { replace: true });

  const reflected = useQuery({
    queryKey: ['source-reflected', connection.id],
    queryFn: () => api<PageResult<Execution>>(`/api/source/connections/${enc(connection.id)}/collections?page=0&size=200`, { silent: true }),
    select: (page) => page.data.filter((e) => e.status === 'COMPLETED' && e.reflectedOrder != null),
  });
  const graph = useQuery({
    queryKey: ['source-erd', connection.id, order],
    queryFn: () => api<SourceErdGraph>(`/api/source/connections/${enc(connection.id)}/snapshot/erd${order ? `?order=${enc(order)}` : ''}`, { silent: true }),
    staleTime: 60_000,
  });
  const data = graph.data;
  const maxOrder = Math.max(0, ...(reflected.data ?? []).map((e) => e.reflectedOrder ?? 0));
  const latest = order == null || order === maxOrder;

  const tablesById = useMemo(() => new Map((data?.tables ?? []).map((tb) => [tb.tableId, tb])), [data]);
  const schemaName = useMemo(() => new Map((data?.schemas ?? []).map((s) => [s.schemaId, s.schemaName])), [data]);
  // 고른 스키마 : 없으면 전체가 한도 안일 때 전체, 넘으면 객체가 있는 스키마를 순서대로 한도까지 (최소 하나 — 그 하나가 한도를 넘으면 안내)
  const selectedSchemas = useMemo(() => {
    const all = data?.schemas ?? [];
    if (schemaParam === 'all') return new Set(all.map((s) => s.schemaId));
    const picked = (schemaParam ?? '').split(',').map(Number).filter((id) => all.some((s) => s.schemaId === id));
    if (picked.length) return new Set(picked);
    if ((data?.tables.length ?? 0) <= RENDER_LIMIT) return new Set(all.map((s) => s.schemaId));
    const chosen = new Set<number>();
    let total = 0;
    for (const schema of all) {
      const size = schema.tableCount + schema.viewCount;
      if (!size) continue;
      if (chosen.size && total + size > RENDER_LIMIT) break;
      chosen.add(schema.schemaId);
      total += size;
    }
    return chosen.size ? chosen : new Set(all.slice(0, 1).map((s) => s.schemaId));
  }, [data, schemaParam]);

  // 보기 범위 : 객체 중심(FK 이웃, 스키마와 무관) 또는 고른 스키마
  const focusValid = focus != null && tablesById.has(focus);
  const scopeIds = useMemo(() => {
    if (!data) return [] as number[];
    if (focusValid) return [...neighbors(focus!, data.relations, depth)].filter((id) => tablesById.has(id));
    return data.tables.filter((tb) => selectedSchemas.has(tb.schemaId)).map((tb) => tb.tableId);
  }, [data, focusValid, focus, depth, selectedSchemas, tablesById]);
  const tooMany = scopeIds.length > RENDER_LIMIT && !force;
  const visibleIds = useMemo(() => new Set(tooMany ? [] : scopeIds), [scopeIds, tooMany]);
  const scopeKey = focusValid ? `focus:${focus}:${depth}` : `schemas:${[...selectedSchemas].sort((a, b) => a - b).join(',')}`;
  const layoutKey = `${connection.id}|${data?.reflectedOrder ?? '-'}|${scopeKey}`;

  // 양쪽 모두 보이는 FK 만 선으로. 나머지(필터로 숨김·수집 범위 밖)는 노드에 수로 알리고 상세 패널에 목록으로
  const shownRelations = useMemo(() => (data?.relations ?? []).filter((r) => r.targetTableId != null && visibleIds.has(r.sourceTableId) && visibleIds.has(r.targetTableId)),
    [data, visibleIds]);
  const hiddenCount = useMemo(() => {
    const count = new Map<number, number>();
    for (const r of data?.relations ?? []) {
      const sourceShown = visibleIds.has(r.sourceTableId);
      const targetShown = r.targetTableId != null && visibleIds.has(r.targetTableId);
      if (sourceShown && !targetShown) count.set(r.sourceTableId, (count.get(r.sourceTableId) ?? 0) + 1);
      if (!sourceShown && targetShown) count.set(r.targetTableId!, (count.get(r.targetTableId!) ?? 0) + 1);
    }
    return count;
  }, [data, visibleIds]);
  const hiddenTotal = useMemo(() => [...hiddenCount.values()].reduce((a, b) => a + b, 0), [hiddenCount]);

  // 버전을 바꿔 고른 객체가 그 시점에 없으면 선택을 푼다
  useEffect(() => {
    if (!data) return;
    if (selectedTableId && !tablesById.has(selectedTableId)) update({ table: null });
    if (focus && !tablesById.has(focus)) update({ focus: null });
    if (selectedRelation && !data.relations.some((r) => r.id === selectedRelation)) setSelectedRelation(null);
  }, [data]);

  // 노드 만들기 + 자동 배치 (배치 결과는 범위마다 캐시)
  useEffect(() => {
    if (!data) return;
    const visible = data.tables.filter((tb) => visibleIds.has(tb.tableId));
    const multiSchema = new Set(visible.map((tb) => tb.schemaId)).size > 1;
    const linked = new Map<number, Set<string>>();
    shownRelations.forEach((r) => r.columns.forEach((p) => {
      linked.set(r.sourceTableId, (linked.get(r.sourceTableId) ?? new Set()).add(p.columnKey));
      linked.set(r.targetTableId!, (linked.get(r.targetTableId!) ?? new Set()).add(p.referencedColumnKey));
    }));
    const build = (positions: Map<string, { x: number; y: number }>): SourceNode[] => visible.map((table) => ({
      id: sourceNodeId(table.tableId),
      type: 'sourceTable',
      position: positions.get(sourceNodeId(table.tableId)) ?? { x: 0, y: 0 },
      data: { table, schemaName: schemaName.get(table.schemaId) ?? '', showSchema: multiSchema, linkedColumns: linked.get(table.tableId) ?? new Set(),
        hiddenRelations: hiddenCount.get(table.tableId) ?? 0, related: false },
    }));
    let cancelled = false;
    const cached = cachedLayout(layoutKey);
    const fit = () => requestAnimationFrame(() => {
      const target = centerRequest.current;
      centerRequest.current = null;
      if (target != null && visibleIds.has(target)) center(target);
      else void flow.fitView({ padding: 0.15, maxZoom: 1 });
    });
    if (cached && visible.every((tb) => cached.has(sourceNodeId(tb.tableId)))) {
      setNodes(build(cached));
      fit();
      return;
    }
    if (!visible.length) {
      setNodes([]);
      return;
    }
    setArranging(true);
    setMessage('');
    const started = performance.now();
    const layoutNodes = visible.map((tb) => ({ id: sourceNodeId(tb.tableId), width: NODE_WIDTH, height: nodeHeight(tb.columns.length) }));
    const layoutEdges = shownRelations.map((r) => ({ source: sourceNodeId(r.sourceTableId), target: sourceNodeId(r.targetTableId!) }));
    (async () => {
      let placed: Record<string, { x: number; y: number }>;
      try {
        placed = await runLayout(layoutNodes, layoutEdges);
      } catch (e) {
        if (cancelled) return;
        setMessage(t('{0} - 격자로 배치했습니다.', (e as Error).message));
        placed = gridLayout(layoutNodes);
      }
      if (cancelled) return;
      performance.measure('source-erd-layout', { start: started, end: performance.now() });
      const positions = new Map(Object.entries(placed));
      rememberLayout(layoutKey, positions);
      setNodes(build(positions));
      setArranging(false);
      fit();
    })();
    return () => {
      cancelled = true;
      cancelLayout();
      setArranging(false);
    };
  }, [data, layoutKey, visibleIds, shownRelations, hiddenCount, relayout]);

  // 선택 표시 : 고른 테이블·관계와 그 양끝
  const related = useMemo(() => {
    const ids = new Set<number>();
    const relation = data?.relations.find((r) => r.id === selectedRelation);
    if (relation) {
      ids.add(relation.sourceTableId);
      if (relation.targetTableId != null) ids.add(relation.targetTableId);
    } else if (selectedTableId) {
      shownRelations.forEach((r) => {
        if (r.sourceTableId === selectedTableId) ids.add(r.targetTableId!);
        if (r.targetTableId === selectedTableId) ids.add(r.sourceTableId);
      });
    }
    return ids;
  }, [data, selectedRelation, selectedTableId, shownRelations]);
  const displayNodes = useMemo(() => nodes.map((n) => {
    const id = tableIdOf(n.id);
    const selected = id === selectedTableId;
    const isRelated = related.has(id) && !selected;
    return selected === !!n.selected && isRelated === n.data.related ? n : { ...n, selected, data: { ...n.data, related: isRelated } };
  }), [nodes, selectedTableId, related]);

  const edges = useMemo(() => {
    const rows = new Map<number, Map<string, number>>();
    const rowOf = (tableId: number, key: string) => {
      let map = rows.get(tableId);
      if (!map) {
        map = new Map((tablesById.get(tableId)?.columns ?? []).map((c, i) => [c.compareName, i]));
        rows.set(tableId, map);
      }
      return map.get(key) ?? MAX_ROWS;
    };
    return shownRelations.flatMap((relation): SourceEdge[] => {
      const self = relation.sourceTableId === relation.targetTableId;
      const highlighted = relation.id === selectedRelation || (!selectedRelation && (relation.sourceTableId === selectedTableId || relation.targetTableId === selectedTableId));
      const stroke = highlighted ? EDGE_HIGHLIGHT : EDGE_COLOR;
      const base = {
        source: sourceNodeId(relation.sourceTableId), target: sourceNodeId(relation.targetTableId!), type: 'smoothstep',
        style: { stroke, strokeWidth: highlighted ? 2.5 : 1.25 }, zIndex: highlighted ? 1 : 0,
        markerEnd: { type: MarkerType.ArrowClosed, color: stroke, width: 16, height: 16 }, data: { relation },
      };
      const pairs = relation.columns.map((p) => ({ from: rowOf(relation.sourceTableId, p.columnKey), to: rowOf(relation.targetTableId!, p.referencedColumnKey) }));
      if (simple || pairs.some((p) => p.from >= MAX_ROWS || p.to >= MAX_ROWS)) {
        return [{ ...base, id: relation.id, sourceHandle: SOURCE_TABLE_HANDLE.out, targetHandle: self ? SOURCE_TABLE_HANDLE.self : SOURCE_TABLE_HANDLE.in }];
      }
      // 복합 FK : 컬럼 쌍마다 선 (순서대로). 모두 같은 관계를 가리킨다
      return pairs.map((p, i) => ({ ...base, id: `${relation.id}#${i}`, sourceHandle: sourceColumnHandle(p.from, 'out'), targetHandle: sourceColumnHandle(p.to, self ? 'self' : 'in') }));
    });
  }, [shownRelations, tablesById, simple, selectedRelation, selectedTableId]);

  function center(tableId: number) {
    const node = flow.getNode(sourceNodeId(tableId));
    const table = tablesById.get(tableId);
    if (node && table) void flow.setCenter(node.position.x + NODE_WIDTH / 2, node.position.y + nodeHeight(table.columns.length) / 2, { zoom: 1, duration: 300 });
  }

  /** 객체로 이동 : 지금 그린 범위에 있으면 고르고 가운데로, 없으면 그 객체 중심(FK 이웃)으로 바꾼다 */
  const openTable = (tableId: number) => {
    setSelectedRelation(null);
    if (visibleIds.has(tableId)) {
      update({ table: tableId });
      center(tableId);
      return;
    }
    centerRequest.current = tableId;
    update({ table: tableId, focus: tableId });
  };

  const key = searchKey(query, data?.dbType ?? connection.dbType, data?.identifierComparison ?? connection.identifierComparison);
  const explorer = useMemo(() => (data?.schemas ?? []).map((schema) => {
    const schemaHit = !!key.text && matches(searchKey(schema.schemaName, data!.dbType, data!.identifierComparison).text, key);
    const items = data!.tables.filter((tb) => tb.schemaId === schema.schemaId && (schemaHit || matches(tb.compareName, key)));
    return { schema, items, schemaHit };
  }).filter((group) => !key.text || group.schemaHit || group.items.length), [data, key.text, key.exact]);

  const toggleSchema = (schemaId: number) => {
    const next = new Set(selectedSchemas);
    if (next.has(schemaId)) next.delete(schemaId);
    else next.add(schemaId);
    if (!next.size) return;
    setForce(false);
    update({ schemas: next.size === data?.schemas.length ? 'all' : [...next].join(','), focus: null });
  };

  const selectedTable = selectedTableId ? tablesById.get(selectedTableId) : undefined;
  const relation = data?.relations.find((r) => r.id === selectedRelation);
  const outsideRelations = selectedTable ? (data?.relations ?? []).filter((r) => (r.sourceTableId === selectedTable.tableId || r.targetTableId === selectedTable.tableId)
    && !(visibleIds.has(r.sourceTableId) && r.targetTableId != null && visibleIds.has(r.targetTableId))) : [];

  return (
    <>
      <div className="source-catalog-bar">
        <ConnectionPicker connections={connections} selected={connection} onSelect={onSelectConnection} />
        <div className="form-group source-picker">
          <label htmlFor="sourceErdOrder">{t('수집 시점')}</label>
          <select id="sourceErdOrder" className="form-control" value={order ?? ''} onChange={(e) => { setSelectedRelation(null); update({ order: e.target.value || null }); }}>
            <option value="">{t('마지막 반영 (현재)')}</option>
            {(reflected.data ?? []).map((e) => (
              <option key={e.id} value={e.reflectedOrder ?? ''}>{t('반영 순서 {0} · 수집 #{1} · {2}', e.reflectedOrder, e.id, formatDateTime(e.reflectedAt))}</option>
            ))}
          </select>
        </div>
      </div>

      {graph.isError ? <p role="alert">{t('원본 ERD 를 불러오지 못했습니다.')} {graph.error.message}</p>
        : graph.isPending ? <p role="status">{t('불러오는 중입니다.')}</p>
        : data!.reflectedOrder == null ? (
          <div className="source-empty" role="status">
            <p>{t('아직 반영된 수집이 없습니다.')}</p>
            <p className="gh-muted">{t('메타데이터 수집 화면에서 수집을 실행하세요. 완료된 수집만 여기에 보입니다.')}</p>
          </div>
        ) : (
          <>
            <div className="erd-status" role="status">
              <span>{t('반영 순서 {0} · 수집 #{1} · {2}', data!.reflectedOrder, data!.executionId, formatDateTime(data!.reflectedAt))}</span>
              {focusValid ? (
                <>
                  <span>{tx('{0} 기준 FK 이웃', <Mono>{`${schemaName.get(tablesById.get(focus!)!.schemaId)}.${tablesById.get(focus!)!.name}`}</Mono>)}</span>
                  <select className="form-control source-erd-depth" aria-label={t('이웃 단계')} value={depth} onChange={(e) => update({ depth: e.target.value })}>
                    {[1, 2, 3].map((d) => <option key={d} value={d}>{t('{0}단계', d)}</option>)}
                  </select>
                  <button type="button" className="btn btn-sm" onClick={() => update({ focus: null, depth: null })}>{t('스키마 보기로')}</button>
                </>
              ) : (
                <span>{t('스키마 {0}개', selectedSchemas.size)}</span>
              )}
              <span>{t('· 객체 {0}개 · FK {1}개', visibleIds.size, shownRelations.length)}</span>
              {hiddenTotal > 0 && <Label variant="attention" title={t('양쪽 테이블이 모두 화면에 있어야 선을 그립니다. 노드·상세 패널에서 목록을 볼 수 있습니다.')}>{t('화면 밖 FK {0}개', hiddenTotal)}</Label>}
              {(arranging) && <span className="erd-muted">{t('자동 배치 중…')}</span>}
              {message && <span className="erd-muted">{message}</span>}
              <span className="erd-pager">
                <button type="button" className="btn btn-sm" disabled={!nodes.length || arranging} onClick={() => { rememberLayout(layoutKey, new Map()); setRelayout((n) => n + 1); }}>{t('자동 배치')}</button>
                <button type="button" className="btn btn-sm" disabled={!nodes.length} onClick={() => void flow.fitView({ padding: 0.15, duration: 300 })}>{t('전체 보기')}</button>
              </span>
            </div>

            <div className="source-erd-workspace">
              <aside className="source-erd-explorer" aria-label={t('객체 탐색')}>
                <input type="search" className="form-control" aria-label={t('객체 검색')} placeholder={t('테이블·VIEW·스키마 검색')} value={query} onChange={(e) => setQuery(e.target.value)} />
                <p className="form-help">{t('원본 DB 의 이름 규칙으로 비교합니다. 따옴표로 감싸면 그대로 비교합니다.')}</p>
                <ul className="source-erd-tree">
                  {explorer.map(({ schema, items }) => (
                    <li key={schema.schemaId}>
                      <label className="source-erd-schema">
                        <input type="checkbox" checked={!focusValid && selectedSchemas.has(schema.schemaId)} onChange={() => toggleSchema(schema.schemaId)} />
                        <span className="gh-mono">{schema.schemaName}</span>
                        <span className="gh-muted">{t('테이블 {0} · VIEW {1}', schema.tableCount, schema.viewCount)}</span>
                      </label>
                      {(key.text || (!focusValid && selectedSchemas.has(schema.schemaId))) && (
                        <ul>
                          {items.slice(0, EXPLORER_LIMIT).map((tb) => (
                            <li key={tb.tableId}>
                              <button type="button" className={`source-erd-object${tb.tableId === selectedTableId ? ' is-active' : ''}`} onClick={() => openTable(tb.tableId)}>
                                <span className="gh-mono">{tb.name}</span>
                                {tb.type === 'VIEW' && <Label variant="done">VIEW</Label>}
                              </button>
                            </li>
                          ))}
                          {items.length > EXPLORER_LIMIT && <li className="gh-muted source-sub">{t('외 {0}개 · 검색으로 좁히세요', items.length - EXPLORER_LIMIT)}</li>}
                        </ul>
                      )}
                    </li>
                  ))}
                  {!explorer.length && <li className="gh-muted">{t('검색 결과가 없습니다.')}</li>}
                </ul>
              </aside>

              <div className="erd-canvas source-erd-canvas" aria-label={t('원본 DB ERD')}>
                {tooMany && (
                  <div className="erd-empty" role="status">
                    <p>{t('고른 범위의 객체가 {0}개로 한 번에 그리기에 많습니다 (한도 {1}개).', scopeIds.length, RENDER_LIMIT)}</p>
                    <p className="gh-muted">{t('스키마를 줄이거나, 왼쪽 목록에서 객체를 골라 FK 이웃만 보세요.')}</p>
                    <button type="button" className="btn btn-sm" onClick={() => setForce(true)}>{t('그래도 모두 그리기')}</button>
                  </div>
                )}
                {!tooMany && !visibleIds.size && <div className="erd-empty">{t('고른 범위에 테이블·VIEW 가 없습니다.')}</div>}
                <ReactFlow
                  nodes={displayNodes}
                  edges={edges}
                  nodeTypes={nodeTypes}
                  onNodesChange={onNodesChange}
                  onNodeDragStop={(_, __, dragged) => {
                    // 이 화면에서만 기억한다 (서버에 저장하지 않는다)
                    const positions = new Map(cachedLayout(layoutKey) ?? []);
                    dragged.forEach((n) => positions.set(n.id, n.position));
                    rememberLayout(layoutKey, positions);
                  }}
                  onNodeClick={(_, node) => { setSelectedRelation(null); update({ table: tableIdOf(node.id) }); }}
                  onEdgeClick={(_, edge) => edge.data && setSelectedRelation(edge.data.relation.id)}
                  onPaneClick={() => { setSelectedRelation(null); update({ table: null }); }}
                  nodesConnectable={false}
                  elementsSelectable={false}
                  deleteKeyCode={null}
                  onlyRenderVisibleElements
                  minZoom={0.05}
                  maxZoom={2}
                >
                  <Background />
                  <Controls showInteractive={false} />
                </ReactFlow>
              </div>
            </div>

            <section className="source-card source-erd-detail" aria-label={t('상세')}>
              {relation ? (
                <RelationDetail relation={relation} data={data!} onOpenTable={openTable} />
              ) : selectedTable ? (
                <>
                  {outsideRelations.length > 0 && (
                    <div className="source-erd-outside">
                      <h4>{t('화면 밖 FK ({0})', outsideRelations.length)}</h4>
                      <ul>
                        {outsideRelations.map((r) => (
                          <li key={r.id}>
                            <button type="button" className="source-link-button" onClick={() => setSelectedRelation(r.id)}>{r.name ?? '-'}</button>
                            <span className="gh-mono"> {endpoint(data!, r, 'source')} → {endpoint(data!, r, 'target')}</span>
                            {r.targetTableId == null && <span className="gh-muted"> · {t('수집 범위 밖')}</span>}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                  <TableDetailView key={`${selectedTable.tableId}-${latest ? 'latest' : order}`} tableId={selectedTable.tableId} order={latest ? null : order}
                    onOpenTable={openTable} onOpenOrder={(o) => update({ order: o })} />
                </>
              ) : (
                <p className="gh-muted">{t('테이블·VIEW 나 관계선을 고르면 상세가 보입니다.')}</p>
              )}
            </section>
          </>
        )}
    </>
  );
}

function endpoint(data: SourceErdGraph, relation: SourceErdRelation, side: 'source' | 'target') {
  const schemas = new Map(data.schemas.map((s) => [s.schemaId, s.schemaName]));
  const table = (id: number | null) => data.tables.find((tb) => tb.tableId === id);
  if (side === 'source') {
    const source = table(relation.sourceTableId);
    return `${schemas.get(source?.schemaId ?? -1) ?? ''}.${source?.name ?? ''}`;
  }
  return `${relation.targetSchemaName ? `${relation.targetSchemaName}.` : ''}${relation.targetTableName}`;
}

/** 관계(FK) 상세 : 제약조건명, 출발·참조 테이블(이동), 컬럼 쌍(복합 FK 순서), ON DELETE·ON UPDATE */
function RelationDetail({ relation, data, onOpenTable }: { relation: SourceErdRelation; data: SourceErdGraph; onOpenTable: (tableId: number) => void }) {
  const self = relation.sourceTableId === relation.targetTableId;
  const target: SourceErdTable | undefined = data.tables.find((tb) => tb.tableId === relation.targetTableId);
  return (
    <div className="source-detail">
      <div className="source-exec-title">
        <h3 className="gh-mono source-break">{relation.name ?? t('이름 없는 FK')}</h3>
        <Label variant="accent">FK</Label>
        {self && <Label>{t('자기 참조')}</Label>}
        {relation.columns.length > 1 && <Label>{t('복합 FK ({0}컬럼)', relation.columns.length)}</Label>}
      </div>
      <dl className="source-dl source-dl-grid">
        <dt>{t('출발 (참조하는 테이블)')}</dt>
        <dd><button type="button" className="source-link-button gh-mono" onClick={() => onOpenTable(relation.sourceTableId)}>{endpoint(data, relation, 'source')}</button></dd>
        <dt>{t('참조 대상')}</dt>
        <dd>
          {target ? <button type="button" className="source-link-button gh-mono" onClick={() => onOpenTable(target.tableId)}>{endpoint(data, relation, 'target')}</button>
            : <><span className="gh-mono">{endpoint(data, relation, 'target')}</span> <span className="gh-muted">{t('(수집 범위 밖 · 이 시점의 카탈로그에 없음)')}</span></>}
        </dd>
        <dt>ON DELETE</dt><dd className="gh-mono">{orDash(relation.deleteRule)}</dd>
        <dt>ON UPDATE</dt><dd className="gh-mono">{orDash(relation.updateRule)}</dd>
      </dl>
      <div className="source-table-wrap">
        <table className="source-table">
          <thead><tr><th className="text-center">#</th><th>{t('출발 컬럼')}</th><th>{t('참조 컬럼')}</th></tr></thead>
          <tbody>
            {relation.columns.map((p, i) => (
              <tr key={i}><td className="text-center">{i + 1}</td><td className="gh-mono source-break">{p.column}</td><td className="gh-mono source-break">{p.referencedColumn}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
