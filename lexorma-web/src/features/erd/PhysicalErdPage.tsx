import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Background, Controls, MarkerType, ReactFlow, ReactFlowProvider, useNodesState, useReactFlow, type Edge, type NodeChange } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../styles/erd.css';
import { NoticeMarkdown } from '../notice/NoticeMarkdown';
import { useColumnBulkEditor } from '../column/ColumnBulkEditor';
import { TableDdlView } from '../table/TableDdl';
import { indexManagePath } from '../index/model';
import { useTableDetail } from '../table/tableDetail';
import { TableForm } from '../table/TableForm';
import type { Schema as TableSchema } from '../schema/schemas';
import type { Row } from '../../components/grid/Grid';
import { ErdModeToggle, findFreePosition, type ErdMode } from './editing';
import { RelationForm, RelationPanel, TableKeys } from './physicalEditing';
import { ColumnRecommendationNote } from '../mapping/MappingRecommendation';
import { DICTIONARY_PATHS } from '../dictionary/columns';
import { ArrowLeftIcon, CheckIcon, GitMergeIcon, PlusIcon, ScreenFullIcon, SyncIcon, UndoIcon, WorkflowIcon } from '@primer/octicons-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { cancelLayout, gridLayout, runLayout, type LayoutEdge, type LayoutNode } from './layout';
import { relationEdges } from './edges';
import { TABLE_HANDLE, TableNode, useSimpleNodes } from './TableNode';
import { isViewNodeId, VIEW_HANDLE, ViewNode, viewNodeId, type ViewNodeType } from './ViewNode';
import { isInternal, referenceName, referenceTypeLabel, viewManagePath, type ErdView } from '../view/model';
import {
  columnsByTable, columnType, NODE_WIDTH, nodeHeight,
  type ErdColumn, type ErdGraph, type ErdLayout, type ErdRelation, type RelationEdge, type Schema, type TableNode as TableNodeType, type XY,
} from './model';
import { t, tx } from '../../lib/i18n';

const PAGE_SIZE = 100;
const nodeTypes = { table: TableNode, view: ViewNode };
/** VIEW 참조선 (M-15) : FK 가 아니다. 점선·보라색·화살표(참조되는 쪽)로 관계선과 구분한다 */
const VIEW_REF_COLOR = 'var(--gh-done)';
type ErdEdge = RelationEdge | Edge<{ viewId: number }>;

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

const toLayoutNode = (n: TableNodeType | ViewNodeType): LayoutNode => ({ id: n.id, width: NODE_WIDTH, height: nodeHeight(n.data.columns.length) });

/**
 * 물리 ERD : 저장된 물리 모델 조회 + 내 배치 저장. 편집 모드(M-8 3차, 물리 모델 변경 권한)에서는 테이블·컬럼·관계·인덱스·제약조건을 바꾼다 —
 * 저장은 테이블·컬럼 관리와 같은 API 이고, 성공한 뒤 ERD 를 다시 조회한다. 배치는 모델 데이터와 따로 [배치 저장] 으로 저장한다.
 */
export default function PhysicalErdPage() {
  const { user } = useAuth();
  return (
    <>
      <PageHeader category={t('물리 모델 관리')} title={t('물리 ERD')}
        description={t('스키마·검색·선택 테이블의 이웃 단위로 물리 모델을 조회하고, 편집 모드에서 테이블·컬럼·관계(FK)·인덱스·제약조건을 추가·수정·삭제합니다(변경 권한 필요). FK 는 테이블 오른쪽 연결점을 참조할 테이블의 왼쪽 연결점으로 끌어 등록할 수 있습니다. 테이블을 옮긴 배치는 [배치 저장] 을 눌러야 저장되며, 배치는 나에게만 보입니다.')} />
      <ReactFlowProvider key={user?.projectId}>
        <ErdWorkspace />
      </ReactFlowProvider>
    </>
  );
}

function ErdWorkspace() {
  const { user, canView, approvalAvailable } = useAuth();
  // 조회 / 편집 모드 (M-8) : 편집 모드는 물리 모델 변경 권한(USER 이상)이 있어야 들어간다. 서버 API 가 같은 기준으로 다시 검사한다
  const canEdit = canView('physical-model-edit');
  const [mode, setMode] = useState<ErdMode>('view');
  const editing = canEdit && mode === 'edit';
  const canvas = useRef<HTMLDivElement>(null);
  const [selectedRelation, setSelectedRelation] = useState<number | null>(null);
  const dialog = useDialog();
  const navigate = useNavigate();
  const flow = useReactFlow<TableNodeType | ViewNodeType, ErdEdge>();
  const simple = useSimpleNodes();
  // 테이블 상세·컬럼 편집은 화면을 옮기지 않고 이 화면에서 연다 (테이블 관리와 같은 대화상자)
  const { openTableDetailById } = useTableDetail();
  const openColumnBulkEditor = useColumnBulkEditor();

  // 테이블·컬럼 관리에서 넘어온 경우 (?schemaId=&tableId=) : 그 스키마를 조회하고 해당 테이블을 선택·가운데로 옮긴다
  const [linked] = useState(() => {
    const params = new URLSearchParams(window.location.search);
    return {
      schemaId: /^\d+$/.test(params.get('schemaId') ?? '') ? params.get('schemaId')! : '',
      tableId: /^\d+$/.test(params.get('tableId') ?? '') ? params.get('tableId') : null,
      viewId: /^\d+$/.test(params.get('viewId') ?? '') ? params.get('viewId') : null,
    };
  });
  const focusRequest = useRef<string | null>(linked.tableId);
  // VIEW 관리에서 넘어온 경우 (?viewId=) : VIEW 노드가 그려진 뒤 그 노드를 선택·가운데로
  const viewFocusRequest = useRef<string | null>(linked.viewId ? viewNodeId(Number(linked.viewId)) : null);
  const [, setSearchParams] = useSearchParams();
  useEffect(() => {
    // 한 번 쓴 파라미터는 지운다 (새로 고침·범위 변경 뒤에 다시 그 테이블로 끌려가지 않게)
    if (linked.schemaId || linked.tableId || linked.viewId) setSearchParams({}, { replace: true });
  }, []);

  const [scope, setScope] = useState<Scope>({ ...INITIAL_SCOPE, schemaId: linked.schemaId });
  const [form, setForm] = useState<SearchScope>({ ...INITIAL_SCOPE, schemaId: linked.schemaId });
  const [nodes, setNodes, onNodesChange] = useNodesState<TableNodeType>([]);
  // VIEW 노드 (M-15) : 테이블 노드와 따로 둔다 (배치 저장 대상이 아니다). 위치는 이 화면에서만 기억한다
  const [viewNodes, setViewNodes, onViewNodesChange] = useNodesState<ViewNodeType>([]);
  const viewPositions = useRef(new Map<string, XY>());
  const [showViews, setShowViews] = useState(true);
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

  // VIEW : 범위 조회는 같은 스키마 조건, 이웃 조회는 전체에서 화면의 테이블·VIEW 를 참조하는 것만 (아래 shownViews)
  const viewScope = scope.kind === 'search' ? scope.schemaId : '';
  const views = useQuery({
    queryKey: ['erd-views', user?.projectId, viewScope],
    queryFn: () => api<ErdView[]>(`/api/erd/views${viewScope ? `?schemaId=${enc(viewScope)}` : ''}`, { silent: true }),
    enabled: showViews && layout.isSuccess,
  });
  const shownViews = useMemo(() => {
    if (!showViews || !views.data || !graph.data) return [];
    if (scope.kind === 'search') return views.data;
    const tableIds = new Set(graph.data.tables.map((t) => t.id));
    return views.data.filter((v) => v.references.some((r) => r.tableId != null && tableIds.has(r.tableId)));
  }, [showViews, views.data, graph.data, scope.kind]);

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
    // 테이블명은 스키마 안에서만 하나다 : 같은 이름이 여러 스키마에서 보이면 노드에 스키마명을 붙여 구분한다
    const nameCount = new Map<string, number>();
    data.tables.forEach((table) => nameCount.set(table.tableName, (nameCount.get(table.tableName) ?? 0) + 1));
    const built: TableNodeType[] = data.tables.map((table) => ({
      id: String(table.id),
      type: 'table',
      position: positions.current.get(String(table.id)) ?? { x: 0, y: 0 },
      data: { table, columns: columns.get(table.id) ?? [], fkColumns, linkedColumns,
        schemaPrefix: (nameCount.get(table.tableName) ?? 0) > 1 ? schemaName.get(table.schemaId) : undefined },
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
      setSelectedId((id) => (id && (isViewNodeId(id) || built.some((n) => n.id === id)) ? id : scope.kind === 'neighbors' && !focusRequest.current ? String(scope.tableId) : null));
      setSelectedRelation((id) => (id != null && data.relations.some((r) => r.id === id) ? id : null));

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

  // VIEW 노드 : 테이블 배치가 끝난 뒤 그린다. 처음 보는 VIEW 는 지금 테이블들의 오른쪽에 세로로 쌓는다 (기존 VIEW 위치는 유지)
  useEffect(() => {
    if (!shownViews.length) {
      setViewNodes([]);
      return;
    }
    // 테이블 노드가 놓인 뒤에 자리를 정한다 (테이블이 아직 그려지지 않았으면 기다린다)
    if (graph.data?.tables.length && !nodes.length) return;
    const right = Math.max(-200, ...nodes.map((n) => n.position.x + NODE_WIDTH), ...[...viewPositions.current.values()].map((p) => p.x - 200)) + 200;
    let top = Math.min(0, ...nodes.map((n) => n.position.y));
    const built: ViewNodeType[] = shownViews.map((view) => {
      const id = viewNodeId(view.id);
      let position = viewPositions.current.get(id);
      if (!position) {
        position = { x: right, y: top };
        viewPositions.current.set(id, position);
      }
      top = Math.max(top, position.y) + nodeHeight(view.columns.length) + 40;
      return { id, type: 'view', position, data: { view, columns: view.columns } };
    });
    setViewNodes(built);
    const requested = viewFocusRequest.current;
    const target = requested ? built.find((n) => n.id === requested) : undefined;
    if (target) {
      viewFocusRequest.current = null;
      setSelectedId(target.id);
      // 처음 열 때 화면 맞춤(전체 보기)이 끝난 뒤에 옮긴다
      setTimeout(() => void flow.setCenter(target.position.x + NODE_WIDTH / 2, target.position.y + nodeHeight(target.data.columns.length) / 2, { zoom: 1 }), 300);
    }
  }, [shownViews, nodes.length]);

  useEffect(() => {
    if (!pendingCount) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [pendingCount]);

  // 관계선 : 선택한 테이블에 걸린 관계는 굵기와 색으로 함께 강조한다 (논리 ERD 와 같다). 출처는 상세 패널의 관계 목록에 남는다
  const edges = useMemo<ErdEdge[]>(() => {
    if (!graph.data) return [];
    const relations: ErdEdge[] = relationEdges(graph.data.columns, shownRelations, simple, (relation) => {
      const highlighted = selectedRelation === relation.id || selectedId === String(relation.sourceTableId) || selectedId === String(relation.targetTableId);
      return { highlighted, stroke: highlighted ? 'var(--gh-accent)' : SOURCE_COLOR[relation.relationSource] };
    });
    // VIEW → 참조 객체 (내부 참조이고 상대가 화면에 있을 때만. 미확인 참조는 선이 없다)
    const tableIds = new Set(graph.data.tables.map((t) => t.id));
    const viewIds = new Set(shownViews.map((v) => v.id));
    const references: ErdEdge[] = shownViews.flatMap((view) => view.references.flatMap((r) => {
      const target = r.tableId != null && tableIds.has(r.tableId) ? String(r.tableId) : r.viewId != null && viewIds.has(r.viewId) ? viewNodeId(r.viewId) : null;
      if (!target) return [];
      const highlighted = selectedId === viewNodeId(view.id) || selectedId === target;
      return [{
        id: `vr${view.id}-${target}`, source: viewNodeId(view.id), sourceHandle: VIEW_HANDLE.out, target,
        targetHandle: r.tableId != null ? TABLE_HANDLE.self : VIEW_HANDLE.in, type: 'smoothstep', zIndex: highlighted ? 1 : 0,
        style: { stroke: VIEW_REF_COLOR, strokeDasharray: '2 4', strokeWidth: highlighted ? 2.5 : 1.5 },
        markerEnd: { type: MarkerType.ArrowClosed, color: VIEW_REF_COLOR }, data: { viewId: view.id },
      }];
    }));
    return [...relations, ...references];
  }, [graph.data, shownRelations, shownViews, simple, selectedId, selectedRelation]);

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

  const displayNodes = useMemo(() => [...nodes.map((n) => ({
    ...n,
    selected: n.id === selectedId,
    hidden: onlyRelated && !!related && !related.has(n.id),
    className: related?.has(n.id) && n.id !== selectedId ? 'erd-related' : undefined,
  })), ...viewNodes.map((n) => ({ ...n, selected: n.id === selectedId, hidden: onlyRelated && !!related }))], [nodes, viewNodes, selectedId, related, onlyRelated]);

  // VIEW 노드의 변경(드래그)은 VIEW 노드 상태로, 나머지는 테이블 노드 상태로
  const onAllNodesChange = (changes: NodeChange<TableNodeType | ViewNodeType>[]) => {
    const isView = (change: NodeChange<TableNodeType | ViewNodeType>) => 'id' in change && isViewNodeId(change.id);
    onNodesChange(changes.filter((change) => !isView(change)) as NodeChange<TableNodeType>[]);
    onViewNodesChange(changes.filter(isView) as NodeChange<ViewNodeType>[]);
  };

  const focus = (id: string) => {
    setSelectedId(id);
    setSelectedRelation(null);
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
      viewPositions.current.clear(); // VIEW 는 새 테이블 배치의 오른쪽에 다시 놓는다
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

  // ------------------------------------------------------------------ 편집 (M-8 3차)
  // 모델 데이터는 테이블·컬럼 관리와 같은 API 로 바로 저장하고, 성공한 뒤 ERD 를 다시 조회한다 (실패하면 공통 알림만 뜨고 화면은 그대로다).
  // 다시 조회해도 기존 노드의 위치는 positions 에서 그대로 온다. 배치 API 는 부르지 않는다.
  const refresh = () => graph.refetch();
  const schemaLabel = (schemaId: number) => schemaName.get(schemaId) ?? '';

  /**
   * 새 테이블 : 보고 있는 화면 가운데에서 기존 노드(범위 밖에 저장된 위치 포함)와 겹치지 않는 자리에 두고 저장하지 않은 배치로 표시한다.
   * 지금 조회 범위(스키마·검색·페이지)에 들어오면 선택·포커스하고, 범위 밖이면 범위를 조용히 바꾸지 않고 물어본다.
   */
  const placeNewTable = async (tableName: string, schemaId?: number | null) => {
    // 같은 이름이 다른 스키마에도 있을 수 있어 등록한 스키마로 좁힌다
    const found = await api<{ data: Row[] }>(`/api/selectTable/data?page=0&size=20&sort=tableName,asc&searchColumn=tableName&searchQuery=${enc(tableName)}${schemaId ? `&schemaId=${enc(schemaId)}` : ''}`);
    const created = found.data.find((row) => row.tableName === tableName && (!schemaId || row.schemaId === schemaId));
    if (!created) return void refresh();
    const id = String(created.id);
    const rect = canvas.current?.getBoundingClientRect();
    const center = rect ? flow.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }) : { x: 0, y: 0 };
    const size = { width: NODE_WIDTH, height: nodeHeight(0) };
    const heights = new Map(nodes.map((n) => [n.id, nodeHeight(n.data.columns.length)]));
    const occupied = [...positions.current.entries()].map(([nodeId, position]) => ({ ...position, width: NODE_WIDTH, height: heights.get(nodeId) ?? nodeHeight(8) }));
    markPending([{ id, position: findFreePosition({ x: Math.round(center.x - size.width / 2), y: Math.round(center.y - size.height / 2) }, size, occupied) }]);
    const result = await refresh();
    if (result.data?.tables.some((table) => String(table.id) === id)) {
      setMessage(t('테이블을 추가했습니다. 새 테이블의 위치는 [배치 저장] 을 눌러야 저장됩니다.'));
      setSelectedRelation(null);
      setSelectedId(id);
      requestAnimationFrame(() => {
        const node = flow.getNode(id);
        if (node) void flow.setCenter(node.position.x + NODE_WIDTH / 2, node.position.y + nodeHeight(0) / 2, { zoom: 1, duration: 300 });
      });
      return;
    }
    dialog.confirm(t('{0} 테이블을 등록했습니다. 지금 조회 범위(스키마·검색 조건·페이지)에는 포함되지 않아 화면에 보이지 않습니다. 이 테이블 기준 이웃 조회로 바꿔서 보시겠습니까?', tableName), () => {
      focusRequest.current = id;
      setScope({ kind: 'neighbors', tableId: created.id, tableName, depth: 1, back: scope.kind === 'neighbors' ? scope.back : scope });
    });
  };

  const addTable = () => {
    dialog.open({
      title: t('테이블 등록'),
      content: ({ close }: DialogControls) => (
        <TableForm type="C" schemas={(schemas.data ?? []) as unknown as TableSchema[]} onSaved={(saved) => { close(); void placeNewTable(saved.tableName, saved.schemaId); }} />
      ),
    });
  };

  const editTable = async (tableId: number) => {
    const row = await api<Row>(`/api/selectTable/detail/${enc(tableId)}`);
    dialog.open({
      title: t('테이블 수정'),
      // 수정은 스키마를 표시만 하므로 스키마 목록이 필요 없다 (테이블 상세와 같다)
      content: ({ close }: DialogControls) => <TableForm type="U" row={row} schemas={[]} onSaved={() => { close(); void refresh(); }} />,
    });
  };

  const removeTable = (table: { id: number; schemaId: number; tableName: string }) => {
    dialog.confirm(t('{0}.{1} 테이블을 삭제하시겠습니까? 컬럼·관계·매핑이 남아 있으면 삭제되지 않습니다.', schemaLabel(table.schemaId), table.tableName), async () => {
      // 삭제할 수 없으면(컬럼·승인·논리 엔터티 매핑) 서버가 거절하고 사유를 알린다 → 노드를 지우지 않는다
      const response = await api<{ result: boolean }>('/api/deleteTable', { method: 'DELETE', body: [{ id: table.id }] });
      if (!response.result) return;
      setSelectedId(null);
      setMessage(t('테이블을 삭제했습니다.'));
      await refresh();
    });
  };

  const openKeys = (table: ErdGraph['tables'][number]) => {
    dialog.open({
      title: t('인덱스·제약조건 · {0}', table.tableName),
      className: 'dialog-xl',
      content: ({ close }: DialogControls) => (
        <TableKeys table={table} columns={(graph.data?.columns ?? []).filter((c) => c.tableId === table.id)} editable={editing} approvalAvailable={approvalAvailable}
          onChanged={() => void refresh()}
          onIndexManage={canView('index-manage') ? () => { close(); leave(indexManagePath(table.id)); } : undefined} />
      ),
    });
  };

  // 연결점을 잇는 것은 FK 등록의 시작일 뿐이다. 선을 추가하지 않고 등록 대화상자를 열며, 저장에 성공해 다시 조회한 뒤에야 선이 생긴다.
  const openRelationForm = (initial: { sourceTableId?: number; targetTableId?: number }) => {
    if (!graph.data?.tables.length) return;
    dialog.open({
      title: t('외래키 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <RelationForm tables={graph.data!.tables} columns={graph.data!.columns} schemaName={schemaLabel} initial={initial}
          onSaved={(saved) => {
            close();
            setSelectedId(null);
            setSelectedRelation(saved.id);
            void refresh();
          }} />
      ),
    });
  };

  const removeRelation = (relation: ErdRelation) => {
    const pairs = relation.columns.map((p) => `${p.sourceColumnName} → ${p.targetColumnName}`).join(', ');
    dialog.confirm(t('{0} 관계를 삭제하시겠습니까? ({1} → {2} : {3}) 테이블과 컬럼은 바뀌지 않습니다.', relation.relationName ?? '', relation.sourceTableName, relation.targetTableName, pairs), async () => {
      await api(`/api/physical/relation/${relation.id}`, { method: 'DELETE' });
      setSelectedRelation(null);
      setMessage(t('관계를 삭제했습니다.'));
      await refresh();
    });
  };

  const toggleRelationApproval = (relation: ErdRelation) => {
    const approved = !!relation.isApproval;
    dialog.confirm(approved ? t('이 관계를 승인취소하시겠습니까?') : t('이 관계를 승인하시겠습니까?'), async () => {
      await api(`/api/physical/relation/${relation.id}/approval/${!approved}`, { method: 'PATCH' });
      await refresh();
    });
  };

  const selected = graph.data?.tables.find((t) => String(t.id) === selectedId);
  const selectedView = shownViews.find((v) => viewNodeId(v.id) === selectedId);
  const relation = graph.data?.relations.find((r) => r.id === selectedRelation);
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
          {canEdit && <ErdModeToggle mode={mode} onChange={setMode} />}
          {editing && <button type="button" className="grd-add btn btn-primary" disabled={!graph.data} onClick={addTable}><PlusIcon />{t('테이블 추가')}</button>}
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
        <label className="erd-view-toggle">
          <input type="checkbox" checked={showViews} onChange={(e) => { setShowViews(e.target.checked); if (!e.target.checked && selectedView) setSelectedId(null); }} />
          {showViews ? t('VIEW 표시 ({0})', shownViews.length) : t('VIEW 표시')}
        </label>
        {graph.data?.truncated && <Label variant="attention">{scope.kind === 'neighbors' ? t('한도(300개)까지만 표시') : t('일부만 표시 - 스키마·검색으로 범위를 줄이거나 다음 페이지로')}</Label>}
        {scope.kind === 'search' && (
          <span className="erd-pager">
            <button type="button" className="btn btn-sm" disabled={loading || scope.page === 0} onClick={() => setScope({ ...scope, page: scope.page - 1 })}>{t('이전')}</button>
            <button type="button" className="btn btn-sm" disabled={loading || !graph.data?.truncated} onClick={() => setScope({ ...scope, page: scope.page + 1 })}>{t('다음 {0}개', PAGE_SIZE)}</button>
          </span>
        )}
        {editing && <span className="erd-mode-note">{t('편집 모드 · 테이블·컬럼·관계 변경은 바로 저장됩니다. 배치는 [배치 저장] 을 눌러야 저장됩니다.')}</span>}
        {loading && <span className="erd-muted">{t('불러오는 중…')}</span>}
        {message && <span className="erd-muted">{message}</span>}
      </div>

      <div className="erd-workspace">
        <div className={`erd-canvas${editing ? ' erd-editing' : ''}`} ref={canvas} aria-label={t('물리 ERD')}>
          {error && <div className="erd-empty" role="alert">{t('ERD 를 불러오지 못했습니다.')} {error.message}</div>}
          {!error && !loading && graph.data && !graph.data.tables.length && (
            <div className="erd-empty">{scope.kind === 'search' && (scope.keyword || scope.isApproval || scope.isMasterTable) ? t('검색 결과가 없습니다.') : t('조회 범위에 등록된 테이블이 없습니다.')}</div>
          )}
          <ReactFlow
            nodes={displayNodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onAllNodesChange}
            onNodeDragStop={(_, __, dragged) => {
              // 테이블만 배치 저장 대상이다. VIEW 노드는 이 화면에서만 위치를 기억한다
              dragged.filter((n) => isViewNodeId(n.id)).forEach((n) => viewPositions.current.set(n.id, n.position));
              markPending(dragged.filter((n) => !isViewNodeId(n.id)));
            }}
            onNodeClick={(_, node) => { setSelectedId(node.id); setSelectedRelation(null); }}
            onEdgeClick={(_, edge) => {
              if (edge.data && 'viewId' in edge.data) {
                setSelectedRelation(null);
                setSelectedId(viewNodeId(edge.data.viewId));
                return;
              }
              setSelectedRelation(edge.data && 'relation' in edge.data ? edge.data.relation.id : null);
              setSelectedId(null);
            }}
            onPaneClick={() => { setSelectedId(null); setSelectedRelation(null); }}
            nodesDraggable={!arranging}
            // 편집 모드에서만 연결할 수 있다. 연결은 선을 만들지 않고 FK 등록 대화상자를 연다 (취소하거나 저장에 실패하면 아무것도 생기지 않는다)
            nodesConnectable={editing}
            onConnect={(connection) => !isViewNodeId(connection.source) && !isViewNodeId(connection.target)
              && openRelationForm({ sourceTableId: Number(connection.source), targetTableId: Number(connection.target) })}
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
              onTableDetail={canView('table-manage') ? () => void openTableDetailById(selected.id, { onChanged: () => void graph.refetch(), onNavigate: leave, readOnly: !editing }) : undefined}
              onKeys={() => openKeys(selected)}
              onRelation={(id) => { setSelectedRelation(id); setSelectedId(null); }}
              edit={editing ? {
                onEditTable: () => void editTable(selected.id),
                onDeleteTable: () => removeTable(selected),
                onAddRelation: () => openRelationForm({ sourceTableId: selected.id }),
              } : undefined}
              onEditColumns={editing && canView('column-manage')
                ? () => void openColumnBulkEditor({ id: selected.id, tableName: selected.tableName, tableKorName: selected.tableKorName ?? undefined }, { onSaved: () => void graph.refetch() })
                : undefined}
              onColumn={openColumn}
              onNeighbors={() => setScope({
                kind: 'neighbors', tableId: selected.id, tableName: selected.tableName, depth,
                back: scope.kind === 'neighbors' ? scope.back : scope,
              })}
            />
          ) : selectedView ? (
            <ViewPanel view={selectedView} schemaName={schemaName.get(selectedView.schemaId) ?? ''}
              onFocus={(id) => focus(id)}
              visible={(id) => !!flow.getNode(id)}
              onDdl={() => dialog.popup({ title: `DDL · ${selectedView.viewName}`, size: 'large', content: <TableDdlView ddlUrl={`/api/physical/view/${enc(selectedView.id)}/ddl`} /> })}
              onManage={canView('view-manage') ? () => leave(viewManagePath(selectedView.id)) : undefined} />
          ) : relation ? (
            <RelationPanel relation={relation} onTable={(tableId) => focus(String(tableId))}
              outOfScope={!graph.data!.tables.some((t) => t.id === relation.sourceTableId) || !graph.data!.tables.some((t) => t.id === relation.targetTableId)}
              onApproval={approvalAvailable ? () => toggleRelationApproval(relation) : undefined}
              onDelete={editing ? () => removeRelation(relation) : undefined} />
          ) : (
            <p className="erd-muted">{t('테이블이나 관계선을 누르면 상세를 볼 수 있습니다.')}</p>
          )}
          <div className="erd-legend">
            <span><i className="erd-line" />FK</span>
            <span><i className="erd-line dashed" />{t('논리 관계')}</span>
            <span><i className="erd-line view-ref" />{t('VIEW 참조 (FK 아님, 직접 관리)')}</span>
            {Object.entries(SOURCE_LABEL).map(([source, label]) => (
              <span key={source}><i className="erd-swatch" style={{ background: SOURCE_COLOR[source as ErdRelation['relationSource']] }} />{label}</span>
            ))}
            <p className="erd-muted">{t('Lexorma 에 정의한 관계입니다. 원본 DB 의 실제 FK 와 다를 수 있고, 카디널리티는 표시하지 않습니다.')}</p>
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

/** 선택한 VIEW (M-15) : 읽기 전용. 정의·출력 컬럼·참조 객체 수정은 VIEW 관리에서 한다 */
function ViewPanel({ view, schemaName, onFocus, visible, onDdl, onManage }: {
  view: ErdView;
  schemaName: string;
  onFocus: (nodeId: string) => void;
  visible: (nodeId: string) => boolean;
  onDdl: () => void;
  onManage?: () => void;
}) {
  return (
    <div className="erd-view-panel">
      <h3><Mono>{schemaName ? `${schemaName}.` : ''}{view.viewName}</Mono> <Label variant="done">VIEW</Label></h3>
      <p className="erd-muted">{view.viewKorName || t('논리명 없음')}{view.isApproval ? '' : t(' · 승인 대기')}</p>
      <h4>{t('출력 컬럼 ({0})', view.columns.length)}</h4>
      {view.columns.length === 0 ? <p className="erd-muted">{t('출력 컬럼이 없습니다.')}</p> : (
        <ol className="erd-view-list">{view.columns.map((c, i) => <li key={i}><Mono>{c.columnName}</Mono>{c.dataType ? ` · ${c.dataType}` : ''}</li>)}</ol>
      )}
      <h4>{t('참조 객체 ({0})', view.references.length)}</h4>
      {view.references.length === 0 ? <p className="erd-muted">{t('참조 객체가 없습니다.')}</p> : (
        <ul className="erd-view-list">
          {view.references.map((r, i) => {
            const nodeId = r.tableId != null ? String(r.tableId) : r.viewId != null ? viewNodeId(r.viewId) : null;
            return (
              <li key={i}>
                [{referenceTypeLabel(r.referenceType)}] <Mono>{referenceName(r)}</Mono>{' '}
                {!isInternal(r) ? <Label variant="attention">{t('미확인')}</Label>
                  : nodeId && visible(nodeId) ? <button type="button" className="btn btn-sm" onClick={() => onFocus(nodeId)}>{t('이동')}</button>
                  : <span className="erd-muted">{t('(조회 범위 밖)')}</span>}
              </li>
            );
          })}
        </ul>
      )}
      <p className="erd-muted">{t('참조 객체는 직접 관리하는 정보로, FK 관계가 아니며 정의 SQL 에서 확인한 것이 아닙니다. VIEW 는 ERD 에서 편집하지 않습니다.')}</p>
      <div className="erd-detail-actions">
        <button type="button" className="btn btn-sm" onClick={() => onFocus(viewNodeId(view.id))}>{t('가운데로')}</button>
        <button type="button" className="btn btn-sm" onClick={onDdl}>{t('DDL 보기')}</button>
        {onManage && <button type="button" className="btn btn-sm btn-primary" onClick={onManage}>{t('VIEW 관리에서 보기')}</button>}
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

function TableDetail({ graph, tableId, schemaName, depth, onDepth, onlyRelated, onOnlyRelated, onFocus, onNeighbors, onDdl, onTableDetail, onEditColumns, onColumn, onKeys, onRelation, edit }: {
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
  /** 테이블 상세 / 컬럼 편집 대화상자 (메뉴 권한이 없으면 undefined). 컬럼 편집은 편집 모드에서만 */
  onTableDetail?: () => void;
  onEditColumns?: () => void;
  /** 인덱스·제약조건 (조회 모드에서는 조회만) */
  onKeys: () => void;
  /** 관계 목록에서 관계를 고른다 (관계 패널로) */
  onRelation: (relationId: number) => void;
  /** 편집 모드의 테이블 동작 */
  edit?: { onEditTable: () => void; onDeleteTable: () => void; onAddRelation: () => void };
  onColumn: (column: ErdColumn, tableName: string, isFk: boolean) => void;
}) {
  const table = graph.tables.find((t) => t.id === tableId)!;
  const columns = graph.columns.filter((c) => c.tableId === tableId);
  const relations = graph.relations.filter((r) => r.sourceTableId === tableId || r.targetTableId === tableId);
  const fkColumns = new Set(relations.filter((r) => r.relationType === 'FK' && r.sourceTableId === tableId).flatMap((r) => r.columns.map((p) => p.sourceColumnId)));
  const loaded = new Set(graph.tables.map((t) => t.id));
  // 테이블 관리와 같은 삭제 조건 (서버가 다시 검사한다. 논리 엔터티 매핑은 서버 오류로 알린다)
  const deleteBlocked = !edit ? '' : table.isApproval ? t('승인된 테이블은 삭제할 수 없습니다. 승인취소 후에 삭제해주세요.')
    : columns.length ? t('컬럼이 있는 테이블은 삭제할 수 없습니다. 컬럼을 먼저 삭제해주세요.') : '';

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
        <button type="button" className="btn btn-sm" onClick={onKeys}>{t('인덱스·제약조건')}</button>
        {onEditColumns && (
          <button type="button" className="btn btn-sm" disabled={!table.isApproval} onClick={onEditColumns}
            title={table.isApproval ? t('표준용어로 컬럼을 한 번에 추가/수정/삭제합니다.') : t('승인된 테이블에만 컬럼을 등록할 수 있습니다.')}>
            {t('컬럼 편집')}
          </button>
        )}
      </div>

      {edit && (
        <div className="erd-toolbar erd-links erd-edit-actions">
          <button type="button" className="btn btn-sm" disabled={!!table.isApproval} title={table.isApproval ? t('승인된 테이블은 수정할 수 없습니다.') : undefined} onClick={edit.onEditTable}>{t('테이블 수정')}</button>
          <button type="button" className="btn btn-sm btn-primary" onClick={edit.onAddRelation}>{t('FK 추가')}</button>
          <button type="button" className="btn btn-sm btn-danger" disabled={!!deleteBlocked} title={deleteBlocked || undefined} onClick={edit.onDeleteTable}>{t('테이블 삭제')}</button>
        </div>
      )}

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
                <button type="button" className="erd-list-button" title={t('관계 상세 보기')} onClick={() => onRelation(r.id)}>
                  <strong><Mono>{r.sourceTableName}</Mono> → <Mono>{r.targetTableName}</Mono></strong>
                  <small>
                    {r.relationName ?? ''} · {r.relationType === 'FK' ? 'FK' : t('논리')} · {SOURCE_LABEL[r.relationSource]} · {r.isApproval ? t('승인') : t('승인 대기')}
                    {!loaded.has(other) && t(' · 조회 범위 밖')}
                  </small>
                  {r.columns.map((p, i) => <small key={i}>{p.sourceColumnName} → {p.targetColumnName}</small>)}
                </button>
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
