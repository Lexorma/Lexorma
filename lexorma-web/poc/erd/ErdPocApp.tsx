import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import {
  Background, ConnectionMode, Controls, MiniMap, ReactFlow, ReactFlowProvider,
  useEdgesState, useNodesState, useReactFlow, type NodeTypes, type EdgeTypes, type Viewport,
} from '@xyflow/react';
import { ScreenFullIcon, SearchIcon, SyncIcon, MoonIcon, SunIcon, DownloadIcon, UploadIcon } from '@primer/octicons-react';
import { generateModel, modelStats } from './mockData';
import { RELATION_KIND_LABEL, type ErdLayoutState, type ErdModel, type XY } from './model';
import { buildGraph, neighborIds, rowCenterY, withSides, type RelationFlowEdge, type TableFlowNode } from './graph';
import { ENGINE_LABEL, runLayout, type EngineId } from './layout/engine';
import { ErdOptionsContext, RelationEdge, TableNode } from './TableNode';
import { animate, frameMeter, nextFrame, settled, type FrameStats } from './perf';

const nodeTypes: NodeTypes = { table: TableNode };
const edgeTypes: EdgeTypes = { relation: RelationEdge };
const SIZES = [300, 1000, 3000];
const SCHEMAS = ['cmn', 'crm', 'erp', 'hr', 'fin', 'log'];
const SIMPLIFY_ZOOM = 0.45;

type ViewMode = 'all' | 'schema' | 'neighbors';
interface View {
  mode: ViewMode;
  schema: string;
  focusId: string | null;
}
interface RunOptions {
  size: number;
  engine: EngineId;
  keyOnly: boolean;
  view: View;
  /** 비교 측정용: 레이아웃을 워커 없이 메인 스레드에서 계산 */
  mainThread?: boolean;
}
export interface LoadMetrics {
  size: number;
  engine: EngineId;
  nodes: number;
  edges: number;
  generateMs: number;
  buildMs: number;
  layoutEngineMs: number;
  layoutWallMs: number;
  /** 레이아웃 계산 중 메인 스레드 프레임 (UI 멈춤 여부) */
  layoutFrames: FrameStats;
  mainThread: boolean;
  renderMs: number;
  fitRenderMs: number;
  fitZoom: number;
  bbox: { width: number; height: number };
}

const round = (v: number) => Math.round(v);
const layoutKey = (size: number) => `lexorma-erd-poc-layout:${size}`;

function ErdPoc() {
  const rf = useReactFlow<TableFlowNode, RelationFlowEdge>();
  const [nodes, setNodes, onNodesChange] = useNodesState<TableFlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<RelationFlowEdge>([]);
  const [options, setOptions] = useState<RunOptions>({ size: 300, engine: 'elk', keyOnly: false, view: { mode: 'all', schema: 'cmn', focusId: null } });
  const [simplify, setSimplify] = useState(true);
  const [virtualize, setVirtualize] = useState(true);
  const [minimap, setMinimap] = useState(false);
  const [onlySelectedEdges, setOnlySelectedEdges] = useState(false);
  const [theme, setTheme] = useState(() => document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState('');
  const [metrics, setMetrics] = useState<LoadMetrics | null>(null);
  const [message, setMessage] = useState('');
  const wrapper = useRef<HTMLDivElement>(null);
  const models = useRef(new Map<number, ErdModel>());
  const savedLayout = useRef<ErdLayoutState | null>(null);
  const [model, setModel] = useState<ErdModel>(() => generateModel(300));

  const getModel = useCallback((size: number) => {
    if (!models.current.has(size)) models.current.set(size, generateModel(size));
    return models.current.get(size)!;
  }, []);

  /** 데이터 → 레이아웃(워커) → 렌더링 → 전체 맞춤. 단계별 시간을 잰다 */
  const load = useCallback(async (opts: RunOptions): Promise<LoadMetrics> => {
    const t0 = performance.now();
    const cached = models.current.has(opts.size);
    const m = getModel(opts.size);
    const generateMs = cached ? 0 : performance.now() - t0;
    const { view } = opts;
    const tableIds = view.mode === 'all' ? null
      : view.mode === 'schema' ? new Set(m.tables.filter((t) => t.schema === view.schema).map((t) => t.id))
        : neighborIds(m, view.focusId ? [view.focusId] : [], 1);
    const t1 = performance.now();
    const g = buildGraph(m, tableIds, opts.keyOnly);
    const buildMs = performance.now() - t1;

    setBusy(`${ENGINE_LABEL[opts.engine]} 자동 레이아웃 계산 중… (테이블 ${g.nodes.length}개, 워커 실행 - 화면은 계속 조작 가능)`);
    const meter = frameMeter();
    const layout = await runLayout({ engine: opts.engine, nodes: g.layoutNodes, edges: g.layoutEdges }, opts.mainThread).finally(() => setBusy(''));
    await nextFrame();
    const layoutFrames = meter.stop();
    const pos = layout.positions;
    const placed = g.nodes.map((n) => ({ ...n, position: pos[n.id] }));
    const placedEdges = g.edges.map((e) => withSides(e, pos[e.source], pos[e.target]));

    // 이전 그래프를 먼저 비워 렌더링 측정이 새 그래프만 재도록 한다
    flushSync(() => {
      setNodes([]);
      setEdges([]);
    });
    await nextFrame();
    const r0 = performance.now();
    setModel(m);
    setOptions(opts);
    setSelectedId(null);
    setNodes(placed);
    setEdges(placedEdges);
    await rf.setViewport({ x: 20, y: 20, zoom: 1 });
    const rendered = await settled(wrapper.current!, placedEdges.length > 0);
    const f0 = performance.now();
    await rf.fitView({ padding: 0.05 });
    const fitted = await settled(wrapper.current!, placedEdges.length > 0);

    let width = 0;
    let height = 0;
    placed.forEach((n) => {
      width = Math.max(width, n.position.x + n.width!);
      height = Math.max(height, n.position.y + n.height!);
    });
    const result: LoadMetrics = {
      size: opts.size,
      engine: opts.engine,
      nodes: placed.length,
      edges: placedEdges.length,
      generateMs: round(generateMs),
      buildMs: round(buildMs),
      layoutEngineMs: round(layout.engineMs),
      layoutWallMs: round(layout.wallMs),
      layoutFrames,
      mainThread: !!opts.mainThread,
      renderMs: round(rendered.at - r0),
      fitRenderMs: round(Math.max(0, fitted.at - f0)),
      fitZoom: +rf.getViewport().zoom.toFixed(3),
      bbox: { width: round(width), height: round(height) },
    };
    setMetrics(result);
    return result;
  }, [getModel, rf, setNodes, setEdges]);

  useEffect(() => {
    void load(options);
    // 첫 화면만 자동 로드
  }, []);

  const reload = (patch: Partial<RunOptions>) => {
    const next = { ...options, ...patch };
    setOptions(next);
    load(next).catch((e) => setMessage(String(e)));
  };

  // 선택 테이블의 관계선 강조 / 그 관계만 표시
  useEffect(() => {
    setEdges((es) => es.map((e) => {
      const active = !!selectedId && (e.source === selectedId || e.target === selectedId);
      const className = active ? 'is-active' : undefined;
      const hidden = onlySelectedEdges && !!selectedId && !active;
      return e.className === className && !!e.hidden === hidden ? e : { ...e, className, hidden };
    }));
  }, [selectedId, onlySelectedEdges, setEdges]);

  const positionsOf = (list: TableFlowNode[]) => Object.fromEntries(list.map((n) => [n.id, n.position])) as Record<string, XY>;

  const resideEdges = useCallback((positions: Record<string, XY>, only?: Set<string>) => {
    setEdges((es) => es.map((e) => (!only || only.has(e.source) || only.has(e.target)) && positions[e.source] && positions[e.target]
      ? withSides(e, positions[e.source], positions[e.target]) : e));
  }, [setEdges]);

  const focusTable = useCallback(async (id: string) => {
    if (!rf.getNode(id)) {
      // 현재 부분 화면에 없으면 그 테이블 + 이웃만 불러온다
      await load({ ...options, view: { ...options.view, mode: 'neighbors', focusId: id } });
    }
    setNodes((ns) => ns.map((n) => (n.selected ?? false) === (n.id === id) ? n : { ...n, selected: n.id === id }));
    setSelectedId(id);
    await rf.fitView({ nodes: [{ id }], duration: 300, maxZoom: 1, minZoom: 0.6 });
  }, [rf, load, options, setNodes]);

  const saveLayout = useCallback(() => {
    const state: ErdLayoutState = { nodes: positionsOf(rf.getNodes()), viewport: rf.getViewport() };
    savedLayout.current = state;
    try {
      localStorage.setItem(layoutKey(options.size), JSON.stringify(state));
    } catch {
      // 저장소 차단 시 메모리에만 둔다
    }
    setMessage(`레이아웃 저장: 노드 ${Object.keys(state.nodes).length}개, zoom ${state.viewport.zoom.toFixed(2)}`);
    return state;
  }, [rf, options.size]);

  /** 저장된 위치가 없는 테이블(새 테이블)은 현재 자동 배치 위치를 유지한다 */
  const restoreLayout = useCallback(async (state?: ErdLayoutState | null) => {
    let s = state ?? savedLayout.current;
    if (!s) {
      try {
        const raw = localStorage.getItem(layoutKey(options.size));
        s = raw ? JSON.parse(raw) : null;
      } catch {
        s = null;
      }
    }
    if (!s) {
      setMessage('저장된 레이아웃이 없습니다.');
      return false;
    }
    const saved = s;
    const next = rf.getNodes().map((n) => saved.nodes[n.id] ? { ...n, position: saved.nodes[n.id] } : n);
    setNodes(next);
    resideEdges(positionsOf(next));
    await rf.setViewport(saved.viewport);
    setMessage('레이아웃 복원 완료');
    return true;
  }, [rf, options.size, setNodes, resideEdges]);

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    document.documentElement.setAttribute('data-bs-theme', next);
    setTheme(next);
  };

  // ---------- 자동 측정용 API (scripts/erd-poc-measure 에서 호출) ----------
  const api = useRef({});
  api.current = {
    run: async (o: Partial<RunOptions> & { simplify?: boolean; virtualize?: boolean }) => {
      flushSync(() => {
        if (o.simplify !== undefined) setSimplify(o.simplify);
        if (o.virtualize !== undefined) setVirtualize(o.virtualize);
      });
      return load({ ...options, view: { mode: 'all', schema: 'cmn', focusId: null }, mainThread: false, ...o });
    },
    stats: () => modelStats(getModel(options.size)),
    /** 개요(전체 맞춤) 상태에서 화면 가운데를 기준으로 3배 확대 후 복귀 */
    zoomOverview: async (): Promise<FrameStats> => {
      await rf.fitView({ padding: 0.05 });
      const v0 = rf.getViewport();
      return animate(2000, (p) => void rf.setViewport(zoomAround(v0, v0.zoom * (1 + 2 * Math.sin(Math.PI * p)))));
    },
    /** 상세(zoom 1) → 0.25 → 1 : 단순 표시 기준(0.45)을 넘나든다 */
    zoomCross: async (): Promise<FrameStats> => {
      await focusDetail();
      const v0 = rf.getViewport();
      return animate(2000, (p) => void rf.setViewport(zoomAround(v0, 1 - 0.75 * Math.sin(Math.PI * p))));
    },
    /** 상세(zoom 1)에서 2초 동안 대각선 이동 */
    pan: async (): Promise<FrameStats> => {
      await focusDetail();
      const v0 = rf.getViewport();
      return animate(2000, (p) => void rf.setViewport({ x: v0.x - 2400 * p, y: v0.y - 1200 * p, zoom: 1 }));
    },
    /** 드래그 대상(관계가 가장 많은 허브 t0)의 헤더 화면 좌표 */
    dragTarget: async () => {
      await focusDetail();
      const el = wrapper.current!.querySelector('.react-flow__node[data-id="t0"] .erd-table__header')!.getBoundingClientRect();
      return { x: el.x + el.width / 2, y: el.y + el.height / 2 };
    },
    meterStart: () => {
      (window as unknown as { __meter: ReturnType<typeof frameMeter> }).__meter = frameMeter();
    },
    meterStop: () => (window as unknown as { __meter: ReturnType<typeof frameMeter> }).__meter.stop(),
    /** 모든 엣지가 자기 컬럼 행에 붙었는지 (Handle 존재 + 위치 = 컬럼 행 가운데) */
    edgeCheck: () => {
      let ok = 0;
      const bad: string[] = [];
      rf.getEdges().forEach((e) => {
        const check = (nodeId: string, handleId: string, columnId: string) => {
          const node = rf.getInternalNode(nodeId);
          const h = node?.internals.handleBounds?.source?.find((b) => b.id === handleId);
          const index = node?.data.rows.findIndex((c) => c.id === columnId) ?? -1;
          return !!h && index >= 0 && Math.abs(h.y + h.height / 2 - rowCenterY(index)) < 1.5;
        };
        if (check(e.source, e.sourceHandle!, e.data!.sourceColumnId) && check(e.target, e.targetHandle!, e.data!.targetColumnId)) ok++;
        else bad.push(e.id);
      });
      const rendered = wrapper.current!.querySelectorAll('.react-flow__edge').length;
      return { edges: rf.getEdges().length, ok, bad: bad.slice(0, 20), rendered };
    },
    /** 노드 이동 → 저장 → 자동 레이아웃으로 흐트러뜨림 → 복원 → 위치·뷰포트 일치 여부 */
    layoutRoundTrip: async () => {
      const moved = rf.getNodes().map((n, i) => i < 5 ? { ...n, position: { x: n.position.x + 333, y: n.position.y - 111 } } : n);
      setNodes(moved);
      await rf.setViewport({ x: -123, y: 45, zoom: 0.77 });
      await new Promise((r) => setTimeout(r, 50));
      const saved = saveLayout();
      await load(options);
      await restoreLayout(saved);
      await new Promise((r) => setTimeout(r, 50));
      const now = positionsOf(rf.getNodes());
      const v = rf.getViewport();
      const mismatched = Object.entries(saved.nodes).filter(([id, p]) => now[id]?.x !== p.x || now[id]?.y !== p.y).length;
      return { nodes: Object.keys(saved.nodes).length, mismatched, viewportEqual: v.x === saved.viewport.x && v.y === saved.viewport.y && v.zoom === saved.viewport.zoom };
    },
    restoreFromStorage: () => restoreLayout(null),
    savedViewport: () => rf.getViewport(),
    focus: (id: string) => focusTable(id),
    setTheme: (t: string) => {
      document.documentElement.setAttribute('data-theme', t);
      setTheme(t);
    },
    view: (v: View) => load({ ...options, view: v }),
  };
  useEffect(() => {
    (window as unknown as { __erdPoc: unknown }).__erdPoc = new Proxy({}, { get: (_, k) => (api.current as Record<string, unknown>)[k as string] });
  }, []);

  async function focusDetail() {
    await rf.setCenter(rf.getNode('t0')!.position.x + 120, rf.getNode('t0')!.position.y + 100, { zoom: 1 });
    await new Promise((r) => setTimeout(r, 100));
  }
  function zoomAround(v0: Viewport, zoom: number): Viewport {
    const { width, height } = wrapper.current!.getBoundingClientRect();
    const cx = width / 2;
    const cy = height / 2;
    return { x: cx - ((cx - v0.x) / v0.zoom) * zoom, y: cy - ((cy - v0.y) / v0.zoom) * zoom, zoom };
  }

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? model.tables.filter((t) => t.name.includes(q)).slice(0, 30) : [];
  }, [model, query]);

  const selected = selectedId ? model.tables.find((t) => t.id === selectedId) : undefined;
  const related = selected ? model.relations.filter((r) => r.sourceTableId === selected.id || r.targetTableId === selected.id) : [];
  const fkIds = useMemo(() => new Set(model.relations.flatMap((r) => r.columns.map((p) => p.sourceColumnId))), [model]);
  const erdOptions = useMemo(() => ({ simplifyBelowZoom: simplify ? SIMPLIFY_ZOOM : 0 }), [simplify]);
  const tableName = (id: string) => model.tables.find((t) => t.id === id)?.name ?? id;

  return (
    <div className="erd-poc">
      <div className="erd-toolbar">
        <strong className="erd-toolbar__title">ERD PoC (M-0)</strong>
        <label>규모
          <select value={options.size} onChange={(e) => reload({ size: +e.target.value, view: { ...options.view, mode: 'all' } })}>
            {SIZES.map((s) => <option key={s} value={s}>{s.toLocaleString()} 테이블</option>)}
          </select>
        </label>
        <label>레이아웃
          <select value={options.engine} onChange={(e) => reload({ engine: e.target.value as EngineId })}>
            {(Object.keys(ENGINE_LABEL) as EngineId[]).map((k) => <option key={k} value={k}>{ENGINE_LABEL[k]}</option>)}
          </select>
        </label>
        <label>보기
          <select value={options.view.mode} onChange={(e) => reload({ view: { ...options.view, mode: e.target.value as ViewMode, focusId: options.view.focusId ?? selectedId } })}>
            <option value="all">전체</option>
            <option value="schema">스키마 단위</option>
            <option value="neighbors" disabled={!options.view.focusId && !selectedId}>선택 테이블 + 이웃</option>
          </select>
        </label>
        {options.view.mode === 'schema' && (
          <select value={options.view.schema} onChange={(e) => reload({ view: { ...options.view, schema: e.target.value } })}>
            {SCHEMAS.map((s) => <option key={s}>{s}</option>)}
          </select>
        )}
        <button type="button" className="gh-btn" onClick={() => reload({})} disabled={!!busy}><SyncIcon size={14} />자동 레이아웃</button>
        <button type="button" className="gh-btn" onClick={() => rf.fitView({ padding: 0.05, duration: 300 })}><ScreenFullIcon size={14} />전체 맞춤</button>
        <button type="button" className="gh-btn" onClick={saveLayout}><DownloadIcon size={14} />배치 저장</button>
        <button type="button" className="gh-btn" onClick={() => restoreLayout()}><UploadIcon size={14} />배치 복원</button>
        <button type="button" className="gh-btn" onClick={toggleTheme} aria-label="테마 전환">{theme === 'dark' ? <SunIcon size={14} /> : <MoonIcon size={14} />}</button>
        <span className="erd-toolbar__checks">
          <label><input type="checkbox" checked={options.keyOnly} onChange={(e) => reload({ keyOnly: e.target.checked })} />키 컬럼만</label>
          <label><input type="checkbox" checked={simplify} onChange={(e) => setSimplify(e.target.checked)} />축소 시 단순 표시</label>
          <label><input type="checkbox" checked={virtualize} onChange={(e) => setVirtualize(e.target.checked)} />화면 밖 생략</label>
          <label><input type="checkbox" checked={onlySelectedEdges} onChange={(e) => setOnlySelectedEdges(e.target.checked)} />선택 관계만</label>
          <label><input type="checkbox" checked={minimap} onChange={(e) => setMinimap(e.target.checked)} />미니맵</label>
        </span>
      </div>

      <div className="erd-body">
        <aside className="erd-side">
          <div className="erd-search">
            <SearchIcon size={14} />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="테이블 검색" aria-label="테이블 검색" />
          </div>
          {matches.length > 0 && (
            <ul className="erd-search__result">
              {matches.map((t) => <li key={t.id}><button type="button" onClick={() => focusTable(t.id)}>{t.name}</button></li>)}
            </ul>
          )}
          {selected ? (
            <div className="erd-detail">
              <h3>{selected.schema}.{selected.name}</h3>
              <table>
                <tbody>
                  {selected.columns.map((c) => (
                    <tr key={c.id}><td>{c.pk ? 'PK' : fkIds.has(c.id) ? 'FK' : ''}</td><td>{c.name}</td><td>{c.dataType}</td><td>{c.nullable ? 'NULL' : 'NN'}</td></tr>
                  ))}
                </tbody>
              </table>
              <h4>관계 {related.length}개</h4>
              <ul className="erd-detail__rel">
                {related.slice(0, 50).map((r) => (
                  <li key={r.id}>
                    <span className={`gh-label erd-kind--${r.kind.toLowerCase().replace('_', '-')}`}>{RELATION_KIND_LABEL[r.kind]}</span>{' '}
                    {r.sourceTableId === r.targetTableId ? '자기 참조' : r.sourceTableId === selected.id ? `→ ${tableName(r.targetTableId)}` : `← ${tableName(r.sourceTableId)}`}
                    {r.columns.length > 1 && ` (복합 ${r.columns.length}컬럼)`}
                  </li>
                ))}
              </ul>
              <button type="button" className="gh-btn" onClick={() => reload({ view: { ...options.view, mode: 'neighbors', focusId: selected.id } })}>이 테이블 + 이웃만 보기</button>
            </div>
          ) : <p className="gh-muted">테이블을 선택하면 컬럼·관계가 표시됩니다.</p>}
          <div className="erd-legend">
            {Object.entries(RELATION_KIND_LABEL).map(([k, label]) => <span key={k} className={`erd-legend__item erd-kind--${k.toLowerCase().replace('_', '-')}`}>{label}</span>)}
          </div>
          {metrics && (
            <dl className="erd-metrics">
              <dt>테이블 / 관계선</dt><dd>{metrics.nodes} / {metrics.edges}</dd>
              <dt>레이아웃 (엔진 / 전체)</dt><dd>{metrics.layoutEngineMs} / {metrics.layoutWallMs} ms</dd>
              <dt>레이아웃 중 최장 프레임</dt><dd>{metrics.layoutFrames.maxMs} ms</dd>
              <dt>초기 렌더링</dt><dd>{metrics.renderMs} ms</dd>
              <dt>전체 맞춤 렌더링</dt><dd>{metrics.fitRenderMs} ms</dd>
            </dl>
          )}
          {message && <p className="gh-muted">{message}</p>}
        </aside>

        <div ref={wrapper} className={`erd-canvas${selectedId ? ' has-selection' : ''}`}>
          {busy && <div className="erd-busy">{busy}</div>}
          <ErdOptionsContext.Provider value={erdOptions}>
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onSelectionChange={({ nodes: sel }) => setSelectedId(sel[0]?.id ?? null)}
              onNodeDragStop={(_, __, dragged) => resideEdges(positionsOf(rf.getNodes()), new Set(dragged.map((n) => n.id)))}
              connectionMode={ConnectionMode.Loose}
              nodesConnectable={false}
              onlyRenderVisibleElements={virtualize}
              colorMode={theme === 'dark' ? 'dark' : 'light'}
              minZoom={0.02}
              maxZoom={2}
            >
              <Background gap={24} />
              <Controls showInteractive={false} />
              {minimap && <MiniMap pannable zoomable />}
            </ReactFlow>
          </ErdOptionsContext.Provider>
        </div>
      </div>
    </div>
  );
}

export function ErdPocApp() {
  return (
    <ReactFlowProvider>
      <ErdPoc />
    </ReactFlowProvider>
  );
}
