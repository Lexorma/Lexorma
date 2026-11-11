import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Background, Controls, ReactFlow, ReactFlowProvider, useNodesState, useReactFlow } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../styles/erd.css';
import { NoticeMarkdown } from '../notice/NoticeMarkdown';
import { useColumnBulkEditor } from '../column/ColumnBulkEditor';
import { TableDdlView } from '../table/TableDdl';
import { useTableDetail } from '../table/tableDetail';
import { ColumnRecommendationNote } from '../mapping/MappingRecommendation';
import { DICTIONARY_PATHS } from '../dictionary/columns';
import { ArrowLeftIcon, CheckIcon, GitMergeIcon, ScreenFullIcon, SyncIcon, UndoIcon, WorkflowIcon } from '@primer/octicons-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { cancelLayout, gridLayout, runLayout, type LayoutEdge, type LayoutNode } from './layout';
import { columnHandle, TABLE_HANDLE, TableNode, useSimpleNodes } from './TableNode';
import {
  columnsByTable, columnType, MAX_ROWS, NODE_WIDTH, nodeHeight,
  type ErdColumn, type ErdGraph, type ErdLayout, type ErdRelation, type RelationEdge, type Schema, type TableNode as TableNodeType, type XY,
} from './model';
import { t, tx } from '../../lib/i18n';

const PAGE_SIZE = 100;
const nodeTypes = { table: TableNode };

interface SearchScope {
  kind: 'search';
  schemaId: string;
  keyword: string;
  isApproval: string;
  isMasterTable: string;
  page: number;
}

type Scope = SearchScope | { kind: 'neighbors'; tableId: number; tableName: string; depth: number; back: SearchScope };

const INITIAL_SCOPE: SearchScope = { kind: 'search', schemaId: '', keyword: '', isApproval: '', isMasterTable: '', page: 0 };

function graphUrl(scope: Scope) {
  if (scope.kind === 'neighbors') return `/api/erd/neighbors/${scope.tableId}?depth=${scope.depth}`;
  const params = new URLSearchParams({ page: String(scope.page), size: String(PAGE_SIZE) });
  if (scope.schemaId) params.set('schemaId', scope.schemaId);
  if (scope.keyword) params.set('keyword', scope.keyword);
  if (scope.isApproval) params.set('isApproval', scope.isApproval);
  if (scope.isMasterTable) params.set('isMasterTable', scope.isMasterTable);
  return `/api/erd?${params}`;
}

/** 관계선 색 : 출처 구분 (AI_APPROVED·IMPORTED 도 원본 DB 의 실제 FK 를 뜻하지 않는다) */
const SOURCE_COLOR: Record<ErdRelation['relationSource'], string> = {
  USER: 'var(--gh-accent)',
  REF_COLUMN: 'var(--gh-fg-muted)',
  IMPORTED: 'var(--gh-success)',
  AI_APPROVED: 'var(--gh-done)',
};
const SOURCE_LABEL: Record<ErdRelation['relationSource'], string> = {
  USER: t('사용자 정의'),
  REF_COLUMN: t('참조 테이블명'),
  IMPORTED: t('가져옴'),
  AI_APPROVED: t('AI 추정 승인'),
};

const toLayoutNode = (n: TableNodeType): LayoutNode => ({ id: n.id, width: NODE_WIDTH, height: nodeHeight(n.data.columns.length) });

/** 물리 ERD : 저장된 물리 모델 조회 전용 (테이블·컬럼·관계는 바꾸지 않는다) + 내 배치 저장 */
export default function PhysicalErdPage() {
  const { user } = useAuth();
  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('물리 ERD')}
        description={t('스키마·검색·선택 테이블의 이웃 단위로 물리 모델을 조회합니다. 테이블을 옮긴 배치는 [배치 저장] 을 눌러야 저장되며, 배치는 나에게만 보입니다.')} />
      <ReactFlowProvider key={user?.projectId}>
        <ErdWorkspace />
      </ReactFlowProvider>
    </>
  );
}

function ErdWorkspace() {
  const { user, canView } = useAuth();
  const dialog = useDialog();
  const navigate = useNavigate();
  const flow = useReactFlow<TableNodeType, RelationEdge>();
  const simple = useSimpleNodes();
  // 테이블 상세·컬럼 편집은 화면을 옮기지 않고 이 화면에서 연다 (테이블 관리와 같은 대화상자)
  const { openTableDetailById } = useTableDetail();
  const openColumnBulkEditor = useColumnBulkEditor();

  // 테이블·컬럼 관리에서 넘어온 경우 (?schemaId=&tableId=) : 그 스키마를 조회하고 해당 테이블을 선택·가운데로 옮긴다
  const [linked] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    return { schemaId: /^\d+$/.test(params.get('schemaId') ?? '') ? params.get('schemaId')! : '', tableId: /^\d+$/.test(params.get('tableId') ?? '') ? params.get('tableId') : null };
  });
  const focusRequest = useRef<string | null>(linked.tableId);
  const [, setSearchParams] = useSearchParams();
  useEffect(() => {
    // 한 번 쓴 파라미터는 지운다 (새로 고침·범위 변경 뒤에 다시 그 테이블로 끌려가지 않게)
    if (linked.schemaId || linked.tableId) setSearchParams({}, { replace: true });
  }, []);

  const [scope, setScope] = useState<Scope>({ ...INITIAL_SCOPE, schemaId: linked.schemaId });
  const [form, setForm] = useState<SearchScope>({ ...INITIAL_SCOPE, schemaId: linked.schemaId });
  const [nodes, setNodes, onNodesChange] = useNodesState<TableNodeType>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [onlyRelated, setOnlyRelated] = useState(false);
  const [depth, setDepth] = useState(1);
  const [arranging, setArranging] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');

  // 좌표 = 저장된 배치 + 이번 화면에서 바꾼 것. pending : 아직 저장하지 않은 좌표 (범위를 바꿔도 유지)
  const savedVersion = useRef<number | null>(null);
  const positions = useRef(new Map<string, XY>());
  const pending = useRef(new Map<string, XY>());
  // 저장된 위치가 없어 화면이 자동으로 놓은 테이블 : 저장할 때 함께 보내지만 사용자가 바꾼 것이 아니므로 세지 않는다
  const autoPlaced = useRef(new Set<string>());
  const [pendingCount, setPendingCount] = useState(0);
  const appliedLayout = useRef<ErdLayout | null>(null);
  const shownGraph = useRef<string | null>(null);

  const schemas = useQuery({ queryKey: ['schemas', user?.projectId], queryFn: () => api<Schema[]>('/api/schema') });
  const layout = useQuery({
    queryKey: ['erd-layout', user?.projectId],
    queryFn: () => api<ErdLayout>('/api/erd/layout', { silent: true }),
    staleTime: Infinity,
    // [저장된 배치 불러오기] : 서버 값이 그대로여도 새 객체로 받아야 아래 effect 가 화면의 배치를 되돌린다
    structuralSharing: false,
    gcTime: 0,
  });
  const graphKey = graphUrl(scope);
  const graph = useQuery({
    queryKey: ['erd', user?.projectId, graphKey],
    queryFn: ({ signal }) => api<ErdGraph>(graphKey, { signal, silent: true }),
    enabled: layout.isSuccess,
  });

  const schemaName = useMemo(() => new Map(schemas.data?.map((s) => [s.id, s.schemaName])), [schemas.data]);
  // 양쪽 테이블이 모두 조회 범위에 있는 관계만 선으로 그린다 (나머지는 상세 패널에)
  const shownRelations = useMemo(() => {
    const ids = new Set(graph.data?.tables.map((t) => t.id));
    return (graph.data?.relations ?? []).filter((r) => ids.has(r.sourceTableId) && ids.has(r.targetTableId));
  }, [graph.data]);

  const markPending = (moved: { id: string; position: XY }[], byUser = true) => {
    moved.forEach((n) => {
      const current = positions.current.get(n.id);
      if (current && current.x === n.position.x && current.y === n.position.y) return; // 제자리 (움직이지 않은 드래그·같은 결과의 자동 배치)
      positions.current.set(n.id, n.position);
      pending.current.set(n.id, n.position);
      if (byUser) autoPlaced.current.delete(n.id);
      else autoPlaced.current.add(n.id);
    });
    setPendingCount(pending.current.size - autoPlaced.current.size);
  };

  // 조회 결과 → 노드. 저장된 위치가 없는 테이블만 기존 배치 오른쪽에 자동 배치한다 (기존 위치는 덮어쓰지 않음)
  useEffect(() => {
    const data = graph.data;
    if (!data || !layout.data) return;
    if (appliedLayout.current !== layout.data) {
      appliedLayout.current = layout.data;
      savedVersion.current = layout.data.version;
      positions.current = new Map(Object.entries(layout.data.nodes));
      pending.current.clear();
      autoPlaced.current.clear();
      setPendingCount(0);
      shownGraph.current = null;
    }

    const columns = columnsByTable(data.columns);
    const fkColumns = new Set(data.relations.filter((r) => r.relationType === 'FK').flatMap((r) => r.columns.map((p) => p.sourceColumnId)));
    const linkedColumns = new Set(shownRelations.flatMap((r) => r.columns.flatMap((p) => [p.sourceColumnId, p.targetColumnId])));
    const built: TableNodeType[] = data.tables.map((table) => ({
      id: String(table.id),
      type: 'table',
      position: positions.current.get(String(table.id)) ?? { x: 0, y: 0 },
      data: { table, columns: columns.get(table.id) ?? [], fkColumns, linkedColumns },
    }));
    const missing = built.filter((n) => !positions.current.has(n.id));
    const moveViewport = shownGraph.current !== graphKey;
    let cancelled = false;

    (async () => {
      if (missing.length) {
        setArranging(true);
        const ids = new Set(missing.map((n) => n.id));
        const edges: LayoutEdge[] = data.relations
          .map((r) => ({ source: String(r.sourceTableId), target: String(r.targetTableId) }))
          .filter((e) => ids.has(e.source) && ids.has(e.target));
        let placed: Record<string, XY>;
        try {
          placed = await runLayout(missing.map(toLayoutNode), edges);
        } catch (e) {
          if (cancelled) return;
          setMessage(t('{0} - 격자로 배치했습니다.', (e as Error).message));
          placed = gridLayout(missing.map(toLayoutNode));
        } finally {
          if (!cancelled) setArranging(false);
        }
        if (cancelled) return;
        // 이미 배치된 모든 테이블(다른 스키마 포함)의 오른쪽에 놓아 겹치지 않게 한다
        const right = Math.max(-120, ...[...positions.current.values()].map((p) => p.x + NODE_WIDTH)) + 120;
        missing.forEach((n) => {
          n.position = { x: placed[n.id].x + right, y: placed[n.id].y };
        });
        markPending(missing, false);
      }
      if (cancelled) return;
      setNodes(built);
      setSelectedId((id) => (id && built.some((n) => n.id === id) ? id : scope.kind === 'neighbors' ? String(scope.tableId) : null));

      const requested = focusRequest.current;
      if (requested) {
        focusRequest.current = null;
        const target = built.find((n) => n.id === requested);
        if (target) {
          shownGraph.current = graphKey;
          setSelectedId(requested);
          requestAnimationFrame(() => void flow.setCenter(target.position.x + NODE_WIDTH / 2, target.position.y + nodeHeight(target.data.columns.length) / 2, { zoom: 1 }));
          return;
        }
        if (scope.kind === 'search') {
          // 이 페이지(100개)에 없는 테이블 : 그 테이블 기준 이웃 조회로 바꾼다
          setScope({ kind: 'neighbors', tableId: Number(requested), tableName: `#${requested}`, depth: 1, back: scope });
          return;
        }
      }
      if (!moveViewport) return;
      shownGraph.current = graphKey;
      requestAnimationFrame(() => {
        // 처음 열 때는 저장한 화면으로, 범위를 바꾸면 조회한 테이블이 다 보이게
        if (appliedLayout.current?.viewport && savedVersion.current != null && graphKey === graphUrl(INITIAL_SCOPE) && pending.current.size === 0) {
          void flow.setViewport(appliedLayout.current.viewport);
        } else {
          void flow.fitView({ padding: 0.15, maxZoom: 1 });
        }
      });
    })();
    return () => {
      cancelled = true;
      cancelLayout();
    };
  }, [graph.data, layout.data]);

  useEffect(() => {
    if (!pendingCount) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [pendingCount]);

  // 관계선 : 컬럼이 보이면 컬럼 쌍마다 컬럼 연결점에, 축소 상태·생략된 컬럼은 테이블 연결점에 (관계당 1개)
  const edges = useMemo<RelationEdge[]>(() => {
    if (!graph.data) return [];
    const rowIndex = new Map<number, number>();
    for (const group of columnsByTable(graph.data.columns).values()) group.forEach((c, i) => rowIndex.set(c.id, i));
    const shown = (columnId: number) => !simple && (rowIndex.get(columnId) ?? MAX_ROWS) < MAX_ROWS;

    return shownRelations.flatMap((relation) => {
        const self = relation.sourceTableId === relation.targetTableId;
        const highlighted = selectedId === String(relation.sourceTableId) || selectedId === String(relation.targetTableId);
        const style = {
          // 선택한 테이블에 걸린 관계는 굵기와 색으로 함께 강조한다 (논리 ERD 와 같다). 출처는 상세 패널의 관계 목록에 남는다
          stroke: highlighted ? 'var(--gh-accent)' : SOURCE_COLOR[relation.relationSource],
          strokeDasharray: relation.relationType === 'LOGICAL' ? '6 4' : undefined,
          strokeWidth: highlighted ? 2.5 : 1.25,
        };
        const base = { source: String(relation.sourceTableId), target: String(relation.targetTableId), type: 'smoothstep', style, zIndex: highlighted ? 1 : 0, data: { relation } };
        const pairs = relation.columns.filter((p) => shown(p.sourceColumnId) && shown(p.targetColumnId));
        const tableEdge = { ...base, id: `r${relation.id}`, sourceHandle: TABLE_HANDLE.out, targetHandle: self ? TABLE_HANDLE.self : TABLE_HANDLE.in };
        if (pairs.length < relation.columns.length) return [tableEdge];
        return pairs.map((p, i) => ({
          ...base,
          id: `r${relation.id}-${i}`,
          sourceHandle: columnHandle(p.sourceColumnId, 'out'),
          targetHandle: columnHandle(p.targetColumnId, self ? 'self' : 'in'),
        }));
      });
  }, [graph.data, shownRelations, simple, selectedId]);

  const related = useMemo(() => {
    if (!selectedId) return null;
    const ids = new Set([selectedId]);
    shownRelations.forEach((r) => {
      const source = String(r.sourceTableId), target = String(r.targetTableId);
      if (source === selectedId) ids.add(target);
      if (target === selectedId) ids.add(source);
    });
    return ids;
  }, [shownRelations, selectedId]);

  const displayNodes = useMemo(() => nodes.map((n) => ({
    ...n,
    selected: n.id === selectedId,
    hidden: onlyRelated && !!related && !related.has(n.id),
    className: related?.has(n.id) && n.id !== selectedId ? 'erd-related' : undefined,
  })), [nodes, selectedId, related, onlyRelated]);

  const focus = (id: string) => {
    setSelectedId(id);
    const node = flow.getNode(id);
    if (node) void flow.setCenter(node.position.x + NODE_WIDTH / 2, node.position.y + nodeHeight(node.data.columns.length) / 2, { zoom: 1, duration: 300 });
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    setScope({ ...form, keyword: form.keyword.trim(), page: 0 });
  };

  async function save() {
    setSaving(true);
    try {
      const result = await api<ErdLayout>('/api/erd/layout', {
        method: 'PUT',
        body: { version: savedVersion.current, nodes: Object.fromEntries(pending.current), viewport: flow.getViewport() },
      });
      savedVersion.current = result.version;
      pending.current.clear();
      autoPlaced.current.clear();
      setPendingCount(0);
      setMessage(t('배치를 저장했습니다.'));
    } catch {
      setMessage(t('배치를 저장하지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  }

  const reloadSaved = () => {
    const reload = () => {
      setMessage('');
      void layout.refetch();
    };
    if (pendingCount) dialog.confirm(t('저장하지 않은 배치를 버리고 저장된 배치를 불러오시겠습니까?'), reload);
    else reload();
  };

  async function arrange() {
    if (!nodes.length) return;
    setArranging(true);
    try {
      const placed = await runLayout(nodes.map(toLayoutNode), shownRelations
        .map((r) => ({ source: String(r.sourceTableId), target: String(r.targetTableId) })));
      // 현재 범위가 있던 자리(왼쪽 위)에서 다시 배치한다
      const left = Math.min(...nodes.map((n) => n.position.x));
      const top = Math.min(...nodes.map((n) => n.position.y));
      const moved = nodes.map((n) => ({ ...n, position: { x: placed[n.id].x + left, y: placed[n.id].y + top } }));
      markPending(moved);
      setNodes(moved);
      setMessage(t('자동 배치했습니다. [배치 저장] 을 눌러야 저장됩니다.'));
      requestAnimationFrame(() => void flow.fitView({ padding: 0.15, maxZoom: 1 }));
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setArranging(false);
    }
  }

  // 다른 화면으로 이동 : 저장하지 않은 배치가 있으면 먼저 확인한다
  const leave = (path: string) => {
    if (pendingCount) dialog.confirm(t('저장하지 않은 배치가 있습니다. 저장하지 않고 이동하시겠습니까?'), () => navigate(path));
    else navigate(path);
  };

  const openColumn = (column: ErdColumn, tableName: string, isFk: boolean) => {
    dialog.open({
      title: t('컬럼 상세 · {0}.{1}', tableName, column.columnName),
      content: ({ close }: DialogControls) => (
        <ColumnDetail column={column} tableName={tableName} isFk={isFk}
          onNavigate={(path) => { close(); leave(path); }}
          onDictionary={canView('standard-term') ? (tab, searchColumn, value) => {
            close();
            leave(`${DICTIONARY_PATHS[tab]}?searchColumn=${searchColumn}&searchQuery=${enc(value)}`);
          } : undefined} />
      ),
    });
  };

  const selected = graph.data?.tables.find((t) => String(t.id) === selectedId);
  const loading = layout.isFetching || graph.isFetching || arranging;
  const error = layout.error ?? graph.error;

  return (
    <div className="erd-page">
      {/* 다른 화면의 그리드 검색 줄과 같은 형태 : 왼쪽 조회 조건(입력 그룹) + 오른쪽 버튼 그룹 */}
      <div className="grid-header">
        <form className="search-container" onSubmit={search}>
          <select className="search-column-select" aria-label={t('스키마')} value={form.schemaId} onChange={(e) => setForm({ ...form, schemaId: e.target.value })}>
            <option value="">{t('전체 스키마')}</option>
            {schemas.data?.map((s) => <option key={s.id} value={s.id}>{s.schemaName}</option>)}
          </select>
          <select className="search-column-select" aria-label={t('승인 상태')} value={form.isApproval} onChange={(e) => setForm({ ...form, isApproval: e.target.value })}>
            <option value="">{t('승인 전체')}</option>
            <option value="true">{t('승인')}</option>
            <option value="false">{t('승인 대기')}</option>
          </select>
          <select className="search-column-select" aria-label={t('마스터 여부')} value={form.isMasterTable} onChange={(e) => setForm({ ...form, isMasterTable: e.target.value })}>
            <option value="">{t('마스터 전체')}</option>
            <option value="true">{t('마스터')}</option>
            <option value="false">{t('일반')}</option>
          </select>
          <input type="text" className="search-input search-input-text" aria-label={t('테이블 검색')} maxLength={100} placeholder={t('테이블명 / 논리명 / 스키마명')}
            value={form.keyword} onChange={(e) => setForm({ ...form, keyword: e.target.value })} />
          <button type="submit" className="grd-select">{t('조회')}</button>
        </form>

        <div className="button-container">
          <span className="erd-muted">{pendingCount ? t('저장하지 않은 배치 {0}개', pendingCount) : t('저장된 배치와 같습니다')}</span>
          <button type="button" className="btn" disabled={loading} onClick={() => void graph.refetch()} title={t('물리 모델 변경 내용을 다시 불러옵니다')}><SyncIcon />{t('새로고침')}</button>
          <button type="button" className="btn" disabled={loading || !nodes.length} onClick={() => void arrange()}><WorkflowIcon />{t('자동 배치')}</button>
          <button type="button" className="btn" disabled={!nodes.length} onClick={() => void flow.fitView({ padding: 0.15, duration: 300 })}><ScreenFullIcon />{t('전체 보기')}</button>
          <button type="button" className="btn" disabled={loading || saving} onClick={reloadSaved}><UndoIcon />{t('저장된 배치 불러오기')}</button>
          <button type="button" className="grd-save btn btn-primary" disabled={loading || saving || !nodes.length} onClick={() => void save()}><CheckIcon />{saving ? t('저장 중…') : t('배치 저장')}</button>
        </div>
      </div>

      <div className="erd-status" role="status">
        {scope.kind === 'neighbors' ? (
          <>
            <button type="button" className="btn btn-sm" onClick={() => setScope(scope.back)}><ArrowLeftIcon />{t('범위 조회로')}</button>
            <span>{tx('{0} 기준 {1}단계 이웃 · 테이블 {2}개', <Mono>{scope.tableName}</Mono>, scope.depth, graph.data?.tables.length ?? 0)}</span>
          </>
        ) : (
          <span>
            {scope.schemaId ? schemaName.get(Number(scope.schemaId)) : t('전체 스키마')}
            {scope.keyword && ` · "${scope.keyword}"`}
            {graph.data && t(' · 전체 {0}개 중 {1}', graph.data.totalCount, graph.data.tables.length ? `${scope.page * PAGE_SIZE + 1}–${scope.page * PAGE_SIZE + graph.data.tables.length}` : 0)}
          </span>
        )}
        <span>{t('· 관계 {0}개', shownRelations.length)}</span>
        {graph.data?.truncated && <Label variant="attention">{scope.kind === 'neighbors' ? t('한도(300개)까지만 표시') : t('일부만 표시 - 스키마·검색으로 범위를 줄이거나 다음 페이지로')}</Label>}
        {scope.kind === 'search' && (
          <span className="erd-pager">
            <button type="button" className="btn btn-sm" disabled={loading || scope.page === 0} onClick={() => setScope({ ...scope, page: scope.page - 1 })}>{t('이전')}</button>
            <button type="button" className="btn btn-sm" disabled={loading || !graph.data?.truncated} onClick={() => setScope({ ...scope, page: scope.page + 1 })}>{t('다음 {0}개', PAGE_SIZE)}</button>
          </span>
        )}
        {loading && <span className="erd-muted">{t('불러오는 중…')}</span>}
        {message && <span className="erd-muted">{message}</span>}
      </div>

      <div className="erd-workspace">
        <div className="erd-canvas" aria-label={t('물리 ERD')}>
          {error && <div className="erd-empty" role="alert">{t('ERD 를 불러오지 못했습니다.')} {error.message}</div>}
          {!error && !loading && graph.data && !graph.data.tables.length && (
            <div className="erd-empty">{scope.kind === 'search' && (scope.keyword || scope.isApproval || scope.isMasterTable) ? t('검색 결과가 없습니다.') : t('조회 범위에 등록된 테이블이 없습니다.')}</div>
          )}
          <ReactFlow
            nodes={displayNodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onNodeDragStop={(_, __, dragged) => markPending(dragged)}
            onNodeClick={(_, node) => setSelectedId(node.id)}
            onPaneClick={() => setSelectedId(null)}
            nodesDraggable={!arranging}
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

        <aside className="erd-detail">
          <select className="form-control" aria-label={t('테이블로 이동')} value={selectedId ?? ''} onChange={(e) => e.target.value && focus(e.target.value)}>
            <option value="">{t('테이블로 이동')}</option>
            {graph.data?.tables.map((t) => <option key={t.id} value={t.id}>{t.tableName}{t.tableKorName ? ` (${t.tableKorName})` : ''}</option>)}
          </select>
          {selected ? (
            <TableDetail
              graph={graph.data!}
              tableId={selected.id}
              schemaName={schemaName.get(selected.schemaId) ?? ''}
              depth={depth}
              onDepth={setDepth}
              onlyRelated={onlyRelated}
              onOnlyRelated={setOnlyRelated}
              onFocus={() => focus(String(selected.id))}
              onDdl={() => dialog.popup({ title: `DDL · ${selected.tableName}`, size: 'large', content: <TableDdlView tableId={selected.id} /> })}
              onTableDetail={canView('table-manage') ? () => void openTableDetailById(selected.id, { onChanged: () => void graph.refetch(), onNavigate: leave }) : undefined}
              onEditColumns={canView('column-manage')
                ? () => void openColumnBulkEditor({ id: selected.id, tableName: selected.tableName, tableKorName: selected.tableKorName ?? undefined }, { onSaved: () => void graph.refetch() })
                : undefined}
              onColumn={openColumn}
              onNeighbors={() => setScope({
                kind: 'neighbors', tableId: selected.id, tableName: selected.tableName, depth,
                back: scope.kind === 'neighbors' ? scope.back : scope,
              })}
            />
          ) : (
            <p className="erd-muted">{t('테이블을 누르면 컬럼과 관계를 볼 수 있습니다.')}</p>
          )}
          <div className="erd-legend">
            <span><i className="erd-line" />FK</span>
            <span><i className="erd-line dashed" />{t('논리 관계')}</span>
            {Object.entries(SOURCE_LABEL).map(([source, label]) => (
              <span key={source}><i className="erd-swatch" style={{ background: SOURCE_COLOR[source as ErdRelation['relationSource']] }} />{label}</span>
            ))}
            <p className="erd-muted">{t('MetaMora 에 정의한 관계입니다. 원본 DB 의 실제 FK 와 다를 수 있고, 카디널리티는 표시하지 않습니다.')}</p>
            <button type="button" className="erd-license-link erd-muted" onClick={() => dialog.open({
              title: t('오픈소스 고지'),
              className: 'dialog-wide',
              content: <OpenSourceNotices />,
            })}>{t('오픈소스 고지')}</button>
          </div>
        </aside>
      </div>
    </div>
  );
}

function OpenSourceNotices() {
  const notices = useQuery({
    queryKey: ['erd-open-source-notices'],
    queryFn: async () => {
      const [notice, license] = await Promise.all([
        api<string>('/jsons/licenses/THIRD_PARTY_NOTICES.md', { responseType: 'text' }),
        api<string>('/jsons/licenses/EPL-2.0.md', { responseType: 'text' }),
      ]);
      // 두 파일 모두 Markdown : 고지 뒤에 구분선을 두고 EPL-2.0 전문을 잇는다
      return `${notice}\n\n---\n\n${license}`;
    },
    staleTime: Infinity,
    retry: false,
  });

  if (notices.isPending) return <p role="status">{t('고지 내용을 불러오는 중…')}</p>;
  if (notices.isError) return <p role="alert">{t('고지 내용을 불러오지 못했습니다.')} <button type="button" className="erd-license-link" onClick={() => void notices.refetch()}>{t('다시 시도')}</button></p>;
  return <div className="erd-license-text"><NoticeMarkdown content={notices.data} /></div>;
}

/** 컬럼 상세 : 조회한 ERD 데이터로 보여 주고(VIEWER 도 가능), 표준 용어·도메인은 데이터 사전으로 이동할 수 있다 */
function ColumnDetail({ column, tableName, isFk, onDictionary, onNavigate }: {
  column: ErdColumn;
  tableName: string;
  isFk: boolean;
  /** 다른 화면으로 이동 (저장하지 않은 배치 확인 포함) */
  onNavigate: (path: string) => void;
  onDictionary?: (tab: 'standardTerms' | 'standardDomains', searchColumn: string, value: string) => void;
}) {
  const keys = [column.isPk ? 'PK' : '', isFk ? 'FK' : ''].filter(Boolean).join(' / ');
  const link = (value: string | null, tab: 'standardTerms' | 'standardDomains', searchColumn: string) => (value ? (
    <>
      {value}
      {onDictionary && <> <button type="button" className="erd-license-link" onClick={() => onDictionary(tab, searchColumn, value)}>{t('데이터 사전에서 보기')}</button></>}
    </>
  ) : null);
  const items: [string, ReactNode, boolean?][] = [
    [t('테이블명'), tableName, true],
    [t('컬럼명'), column.columnName, true],
    [t('논리명'), column.termName],
    [t('데이터 타입'), columnType(column), true],
    ['PK / FK', keys],
    [t('NULL 허용'), column.isNullable === false || column.isPk ? 'N' : 'Y'],
    [t('표준 용어'), link(column.termName, 'standardTerms', 'commonStandardTermName')],
    [t('표준 도메인'), link(column.domainName, 'standardDomains', 'commonStandardDomainName')],
    [t('설명'), column.columnDesc],
  ];
  return (
    <div>
      <div className="dict-detail-status">
        {column.isApproval ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>}
        {!column.termName && <span className="gh-muted">{t('연결된 표준 용어가 없습니다.')}</span>}
      </div>
      <dl className="dict-detail">
        {items.map(([label, value, mono]) => {
          const empty = value == null || value === '';
          return [
            <dt key={`t-${label}`}>{label}</dt>,
            <dd key={`d-${label}`} className={empty ? 'gh-muted' : mono ? 'gh-mono' : undefined}>{empty ? '-' : value}</dd>,
          ];
        })}
      </dl>
      <ColumnRecommendationNote columnId={column.id} onNavigate={onNavigate} />
    </div>
  );
}

function TableDetail({ graph, tableId, schemaName, depth, onDepth, onlyRelated, onOnlyRelated, onFocus, onNeighbors, onDdl, onTableDetail, onEditColumns, onColumn }: {
  graph: ErdGraph;
  tableId: number;
  schemaName: string;
  depth: number;
  onDepth: (depth: number) => void;
  onlyRelated: boolean;
  onOnlyRelated: (value: boolean) => void;
  onFocus: () => void;
  onNeighbors: () => void;
  onDdl: () => void;
  /** 테이블 상세 / 컬럼 편집 대화상자 (메뉴 권한이 없으면 undefined) */
  onTableDetail?: () => void;
  onEditColumns?: () => void;
  onColumn: (column: ErdColumn, tableName: string, isFk: boolean) => void;
}) {
  const table = graph.tables.find((t) => t.id === tableId)!;
  const columns = graph.columns.filter((c) => c.tableId === tableId);
  const relations = graph.relations.filter((r) => r.sourceTableId === tableId || r.targetTableId === tableId);
  const fkColumns = new Set(relations.filter((r) => r.relationType === 'FK' && r.sourceTableId === tableId).flatMap((r) => r.columns.map((p) => p.sourceColumnId)));
  const loaded = new Set(graph.tables.map((t) => t.id));

  return (
    <>
      <h3><Mono>{table.tableName}</Mono></h3>
      <p>{table.tableKorName || t('논리명 없음')} · {schemaName}</p>
      <p>
        {table.isApproval ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>}
        {table.isMasterTable && <> <Label variant="accent">{t('마스터')}</Label></>}
      </p>
      <p className="erd-muted">{table.tableDescription || t('설명 없음')}</p>

      <div className="erd-toolbar">
        <select className="form-control" aria-label={t('이웃 단계')} value={depth} onChange={(e) => onDepth(Number(e.target.value))}>
          {[1, 2, 3].map((n) => <option key={n} value={n}>{t('{0}단계', n)}</option>)}
        </select>
        <button type="button" className="btn btn-sm" onClick={onNeighbors}><GitMergeIcon />{t('이웃 탐색')}</button>
        <button type="button" className="btn btn-sm" onClick={onFocus}>{t('이동')}</button>
      </div>
      <label className="erd-check"><input type="checkbox" checked={onlyRelated} onChange={(e) => onOnlyRelated(e.target.checked)} /> {t('연결된 테이블만 보기')}</label>
      <div className="erd-toolbar erd-links">
        <button type="button" className="btn btn-sm" onClick={onDdl}>{t('DDL 보기')}</button>
        {onTableDetail && <button type="button" className="btn btn-sm" onClick={onTableDetail}>{t('테이블 상세')}</button>}
        {onEditColumns && (
          <button type="button" className="btn btn-sm" disabled={!table.isApproval} onClick={onEditColumns}
            title={table.isApproval ? t('표준용어로 컬럼을 한 번에 추가/수정/삭제합니다.') : t('승인된 테이블에만 컬럼을 등록할 수 있습니다.')}>
            {t('컬럼 편집')}
          </button>
        )}
      </div>

      <h4>{t('컬럼 {0}개', columns.length)}</h4>
      <ul className="erd-list">
        {columns.map((c) => (
          <li key={c.id}>
            <button type="button" className="erd-list-button" title={t('컬럼 상세 보기')} onClick={() => onColumn(c, table.tableName, fkColumns.has(c.id))}>
              <strong>{c.isPk ? 'PK ' : ''}{fkColumns.has(c.id) ? 'FK ' : ''}<Mono>{c.columnName}</Mono></strong>
              <small>{[c.termName || c.columnDesc, columnType(c), c.isNullable === false || c.isPk ? 'NOT NULL' : 'NULL'].filter(Boolean).join(' · ')}</small>
            </button>
          </li>
        ))}
      </ul>

      <h4>{t('관계 {0}개', relations.length)}</h4>
      {relations.length ? (
        <ul className="erd-list">
          {relations.map((r) => {
            const other = r.sourceTableId === tableId ? r.targetTableId : r.sourceTableId;
            return (
              <li key={r.id}>
                <strong><Mono>{r.sourceTableName}</Mono> → <Mono>{r.targetTableName}</Mono></strong>
                <small>
                  {r.relationName ?? ''} · {r.relationType === 'FK' ? 'FK' : t('논리')} · {SOURCE_LABEL[r.relationSource]} · {r.isApproval ? t('승인') : t('승인 대기')}
                  {!loaded.has(other) && t(' · 조회 범위 밖')}
                </small>
                {r.columns.map((p, i) => <small key={i}>{p.sourceColumnName} → {p.targetColumnName}</small>)}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="erd-muted">{t('관계가 없습니다.')}</p>
      )}
    </>
  );
}
