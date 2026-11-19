import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Background, Controls, ReactFlow, ReactFlowProvider, useNodesState, useReactFlow } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../styles/erd.css';
import '../../styles/logical.css';
import { ArrowLeftIcon, CheckIcon, DownloadIcon, GitMergeIcon, PlusIcon, ScreenFullIcon, SyncIcon, UndoIcon, WorkflowIcon } from '@primer/octicons-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api, enc } from '../../lib/api';
import { useErdExport, erdFileName } from '../erd/exportImage';
import { t, tc, tx } from '../../lib/i18n';
// 물리 ERD 와 같이 쓰는 것 : 자동 배치(ELK 워커), 노드 크기 규칙, 축소 시 행 생략, 스타일(erd.css). 데이터·레이아웃 저장은 따로다
import { cancelLayout, gridLayout, runLayout, type LayoutNode } from '../erd/layout';
import { NODE_WIDTH, nodeHeight, type ErdLayout, type XY } from '../erd/model';
import { ErdModeToggle, findFreePosition, type ErdMode } from '../erd/editing';
import { AttributeForm, EntityForm, IdentifierForm, useEntityDetail, type EntityDetailTab } from './entityDetail';
import { edgeTypes, EndMarkers, LAYER_GAP, nodeTypes, relationshipEdges, type EntityNodeType, type RelationshipEdgeType } from './erdNodes';
import { relationLabel, RelationshipForm } from './relationshipForm';
import { PairList, PairStatusLabel, PAIR_WARNINGS, RelationshipPairsEditor } from './relationshipPairs';
import { mappingPath } from '../mapping/model';
import { cardinalityText, graphQueryKey, participation, useLogicalModels, type EntityHits, type GraphAttribute, type GraphEntity, type LogicalEntity, type LogicalGraph, type LogicalGraphPart, type LogicalIdentifier, type LogicalRelationship } from './model';

const toLayoutNode = (n: EntityNodeType): LayoutNode => ({ id: n.id, width: NODE_WIDTH, height: nodeHeight(n.data.entity.attributes.length) });

/**
 * 조회 범위 (M-6 후속 1차, 물리 ERD 와 같은 방식) : 서버가 고른 엔터티만 준다 — 전체를 받아 화면에서 거르지 않는다.
 * 범위 조회 = 이름·설명 검색(비우면 모델 전체)을 이름순 PAGE_SIZE 개씩, 이웃 = 고른 엔터티에서 관계(출발·도착 모두)로 1~3단계
 */
interface SearchScope {
  kind: 'search';
  keyword: string;
  page: number;
}
type Scope = SearchScope | { kind: 'neighbors'; entityId: number; entityName: string; depth: number; back: SearchScope };
const PAGE_SIZE = 100;
/** 범례의 Crow's Foot 기호 (관계선 끝 표기와 같은 marker) */
const CROWS_FOOT: [string, string][] = [
  ['one', t('정확히 하나 (1)')], ['zero-one', t('없거나 하나 (0..1)')], ['one-many', t('하나 이상 (1..N)')], ['zero-many', t('없거나 여러 건 (0..N)')],
];
const INITIAL_SCOPE: SearchScope = { kind: 'search', keyword: '', page: 0 };

function partUrl(modelId: number, scope: Scope) {
  if (scope.kind === 'neighbors') return `/api/logical/model/${modelId}/erd/neighbors/${scope.entityId}?depth=${scope.depth}`;
  const params = new URLSearchParams({ page: String(scope.page), size: String(PAGE_SIZE) });
  if (scope.keyword) params.set('keyword', scope.keyword);
  return `/api/logical/model/${modelId}/erd/part?${params}`;
}

/** 논리 ERD : 논리 모델 1개의 엔터티·관계 조회 전용 (논리 모델은 바꾸지 않는다) + 내 배치 저장 (모델별·사용자별) */
export default function LogicalErdPage() {
  const { user, canView } = useAuth();
  const dialog = useDialog();
  const navigate = useNavigate();
  const { models, selected, select, isPending, isError } = useLogicalModels();
  // 관리 화면에서 넘어온 경우 (?modelId=&entityId=) : 그 엔터티를 한 번 선택·가운데로 옮긴다
  const [params, setParams] = useSearchParams();
  const [focusEntity] = useState(() => (/^\d+$/.test(params.get('entityId') ?? '') ? params.get('entityId') : null));
  useEffect(() => {
    if (params.has('entityId')) setParams(params.get('modelId') ? { modelId: params.get('modelId')! } : {}, { replace: true });
  }, []);
  const [dirty, setDirty] = useState(false);
  // 조회 / 편집 모드 (M-8) : 모델을 바꿔도 유지한다. 편집 모드는 논리 모델 변경 권한(USER 이상)이 있어야 들어간다
  const canEdit = canView('logical-model-edit');
  const [mode, setMode] = useState<ErdMode>('view');

  const changeModel = (modelId: number) => {
    if (dirty) dialog.confirm(t('저장하지 않은 배치가 있습니다. 저장하지 않고 이동하시겠습니까?'), () => select(modelId));
    else select(modelId);
  };
  const leave = (path: string) => {
    if (dirty) dialog.confirm(t('저장하지 않은 배치가 있습니다. 저장하지 않고 이동하시겠습니까?'), () => navigate(path));
    else navigate(path);
  };

  return (
    <>
      <PageHeader category={t('논리 모델 관리')} title={t('논리 ERD')}
        description={t('논리 모델의 엔터티·속성·관계를 조회하고, 편집 모드에서 엔터티·속성·식별자·관계를 추가·수정·삭제합니다(변경 권한 필요). 관계는 엔터티 오른쪽 연결점을 다른 엔터티의 왼쪽 연결점으로 끌어 등록할 수 있습니다. 엔터티를 옮긴 배치는 [배치 저장] 을 눌러야 저장되며, 배치는 모델마다 따로 나에게만 보입니다.')} />
      <div className="erd-page">
        {isError && <p role="alert">{t('논리 모델 목록을 불러오지 못했습니다.')}</p>}
        {!isPending && !isError && !selected && (
          <p className="logical-empty">
            {t('등록된 논리 모델이 없습니다.')}
            {canView('logical-model') && <> <button type="button" className="erd-license-link" onClick={() => navigate('/logicalModel')}>{t('논리 모델 관리로 이동')}</button></>}
          </p>
        )}
        {selected && (
          // 모델(과 프로젝트)이 바뀌면 화면 상태를 통째로 새로 만든다 : 배치·선택이 모델 사이에 섞이지 않는다
          <ReactFlowProvider key={`${user?.projectId}:${selected.id}`}>
            <ErdWorkspace
              modelId={selected.id}
              focusEntity={focusEntity}
              onDirty={setDirty}
              onManage={canView('logical-model') ? (entityId) => leave(`/logicalModel?modelId=${selected.id}${entityId != null ? `&entityId=${entityId}` : ''}`) : undefined}
              onLeave={leave}
              mode={canEdit ? mode : 'view'}
              onMode={canEdit ? setMode : undefined}
              modelSelect={(
                <select className="search-column-select" aria-label={t('논리 모델')} value={selected.id} onChange={(e) => changeModel(Number(e.target.value))}>
                  {models.map((m) => <option key={m.id} value={m.id}>{m.modelName}</option>)}
                </select>
              )}
            />
          </ReactFlowProvider>
        )}
      </div>
    </>
  );
}

function ErdWorkspace({ modelId, focusEntity, onDirty, onManage, onLeave, mode, onMode, modelSelect }: {
  modelId: number;
  focusEntity: string | null;
  onDirty: (dirty: boolean) => void;
  /** 논리 모델 관리로 이동 (entityId 를 주면 그 엔터티 상세를 연다). 메뉴 권한이 없으면 undefined */
  onManage?: (entityId?: number) => void;
  /** 다른 화면으로 이동 (저장하지 않은 배치가 있으면 먼저 확인한다) */
  onLeave: (path: string) => void;
  mode: ErdMode;
  /** 모드 전환 (변경 권한이 없으면 undefined — 조회 모드만) */
  onMode?: (mode: ErdMode) => void;
  modelSelect: ReactNode;
}) {
  const { user, canView, approvalAvailable } = useAuth();
  const editing = mode === 'edit';
  const canvas = useRef<HTMLDivElement>(null);
  const dialog = useDialog();
  const flow = useReactFlow<EntityNodeType, RelationshipEdgeType>();
  const erdExport = useErdExport(canvas, () => (flow.getNodes().length ? flow.getNodesBounds(flow.getNodes()) : null), () => erdFileName(t('논리 ERD'), String(modelId)),
    () => flow.getNodes().map((n) => n.data.entity.attributes.length));
  const openEntityDetail = useEntityDetail();
  const [nodes, setNodes, onNodesChange] = useNodesState<EntityNodeType>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedRelationship, setSelectedRelationship] = useState<number | null>(null);
  const [arranging, setArranging] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [dirty, setDirtyState] = useState(false);
  const setDirty = (value: boolean) => {
    setDirtyState(value);
    onDirty(value);
  };

  const savedVersion = useRef<number | null>(null);
  const positions = useRef(new Map<string, XY>());
  const appliedLayout = useRef<ErdLayout | null>(null);
  const focusRequest = useRef(focusEntity);
  const shown = useRef(false);

  const layoutUrl = `/api/logical/model/${modelId}/erd/layout`;
  const layout = useQuery({
    queryKey: ['logical-erd-layout', user?.projectId, modelId],
    queryFn: () => api<ErdLayout>(layoutUrl, { silent: true }),
    staleTime: Infinity,
    // [저장된 배치 불러오기] : 서버 값이 그대로여도 새 객체로 받아야 아래 effect 가 화면의 배치를 되돌린다
    structuralSharing: false,
    gcTime: 0,
  });
  const [scope, setScope] = useState<Scope>(INITIAL_SCOPE);
  const [keyword, setKeyword] = useState('');
  const [depth, setDepth] = useState(1);
  const graphUrl = partUrl(modelId, scope);
  // 모델의 ERD 키(graphQueryKey) 아래에 둔다 : 그 키를 무효화하면 이 조회도 다시 받는다
  const graph = useQuery({
    queryKey: [...graphQueryKey(user?.projectId, modelId), 'part', graphUrl],
    queryFn: ({ signal }) => api<LogicalGraphPart>(graphUrl, { signal, silent: true }),
    enabled: layout.isSuccess,
  });
  // 저장하지 않은 배치 : 범위를 바꿔도 잃지 않도록 화면 밖 엔터티의 옮긴 좌표도 기억했다가 [배치 저장] 때 함께 보낸다 (서버는 기존 좌표에 합친다)
  const pending = useRef(new Set<string>());
  const [pendingCount, setPendingCount] = useState(0);
  const markPending = (ids: string[]) => {
    ids.forEach((id) => pending.current.add(id));
    setPendingCount(pending.current.size);
    setDirty(pending.current.size > 0);
  };
  const clearPending = () => {
    pending.current.clear();
    setPendingCount(0);
    setDirty(false);
  };
  const neighborsOf = (entityId: number, entityName: string, nextDepth = depth) => {
    focusRequest.current = String(entityId);
    setScope({ kind: 'neighbors', entityId, entityName, depth: nextDepth, back: scope.kind === 'neighbors' ? scope.back : scope });
  };
  const search = (event: FormEvent) => {
    event.preventDefault();
    setScope({ kind: 'search', keyword: keyword.trim(), page: 0 });
  };

  useEffect(() => () => onDirty(false), []);

  // 조회 결과 → 노드. 저장된 위치가 없는 엔터티만 기존 배치 오른쪽에 자동 배치한다 (기존 위치는 덮어쓰지 않음)
  useEffect(() => {
    const data = graph.data;
    if (!data || !layout.data) return;
    if (appliedLayout.current !== layout.data) {
      appliedLayout.current = layout.data;
      savedVersion.current = layout.data.version;
      positions.current = new Map(Object.entries(layout.data.nodes));
      clearPending();
      shown.current = false;
    }
    const built: EntityNodeType[] = data.entities.map((entity) => ({
      id: String(entity.id), type: 'entity', position: positions.current.get(String(entity.id)) ?? { x: 0, y: 0 }, data: { entity },
    }));
    const missing = built.filter((n) => !positions.current.has(n.id));
    let cancelled = false;

    (async () => {
      if (missing.length) {
        setArranging(true);
        const ids = new Set(missing.map((n) => n.id));
        const edges = data.relationships.map((r) => ({ source: String(r.sourceEntityId), target: String(r.targetEntityId) })).filter((e) => ids.has(e.source) && ids.has(e.target));
        let placed: Record<string, XY>;
        try {
          placed = await runLayout(missing.map(toLayoutNode), edges, LAYER_GAP);
        } catch (e) {
          if (cancelled) return;
          setMessage(t('{0} - 격자로 배치했습니다.', (e as Error).message));
          placed = gridLayout(missing.map(toLayoutNode));
        } finally {
          if (!cancelled) setArranging(false);
        }
        if (cancelled) return;
        const right = Math.max(-120, ...[...positions.current.values()].map((p) => p.x + NODE_WIDTH)) + 120;
        missing.forEach((n) => {
          n.position = { x: placed[n.id].x + right, y: placed[n.id].y };
          positions.current.set(n.id, n.position);
        });
      }
      if (cancelled) return;
      setNodes(built);
      setSelectedId((id) => (id && built.some((n) => n.id === id) ? id : null));
      setSelectedRelationship((id) => (id != null && data.relationships.some((r) => r.id === id) ? id : null));

      const requested = focusRequest.current;
      const target = requested ? built.find((n) => n.id === requested) : undefined;
      // 다른 화면에서 넘어온 엔터티가 지금 범위에 없으면 그 엔터티의 이웃을 불러와 보여 준다 (다른 모델·프로젝트 ID 는 서버가 거절한다)
      if (requested && !target && scope.kind === 'search') {
        setScope({ kind: 'neighbors', entityId: Number(requested), entityName: `#${requested}`, depth: 1, back: scope });
        return;
      }
      focusRequest.current = null;
      if (target) {
        shown.current = true;
        setSelectedId(target.id);
        requestAnimationFrame(() => void flow.setCenter(target.position.x + NODE_WIDTH / 2, target.position.y + nodeHeight(target.data.entity.attributes.length) / 2, { zoom: 1 }));
        return;
      }
      if (shown.current) return; // 새로고침·편집 뒤에는 보던 화면을 유지한다
      shown.current = true;
      requestAnimationFrame(() => {
        if (appliedLayout.current?.viewport && savedVersion.current != null) void flow.setViewport(appliedLayout.current.viewport);
        else void flow.fitView({ padding: 0.15, maxZoom: 1 });
      });
    })();
    return () => {
      cancelled = true;
      cancelLayout();
    };
  }, [graph.data, layout.data]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  // 선택한 엔터티에 걸린 관계(또는 선택한 관계)는 굵기와 색으로 함께 강조한다
  const edges = useMemo<RelationshipEdgeType[]>(() => relationshipEdges(graph.data?.relationships ?? [], (relationship) => {
    const highlighted = selectedRelationship === relationship.id || selectedId === String(relationship.sourceEntityId) || selectedId === String(relationship.targetEntityId);
    return { highlighted, stroke: highlighted ? 'var(--gh-accent)' : 'var(--gh-fg-muted)' };
  }), [graph.data, selectedId, selectedRelationship]);

  const displayNodes = useMemo(() => nodes.map((n) => ({ ...n, selected: n.id === selectedId, data: { ...n.data, connectable: editing } })), [nodes, selectedId, editing]);

  const focus = (id: string) => {
    setSelectedId(id);
    setSelectedRelationship(null);
    const node = flow.getNode(id);
    if (node) void flow.setCenter(node.position.x + NODE_WIDTH / 2, node.position.y + nodeHeight(node.data.entity.attributes.length) / 2, { zoom: 1, duration: 300 });
  };

  async function save() {
    setSaving(true);
    try {
      const result = await api<ErdLayout>(layoutUrl, {
        method: 'PUT',
        // 화면의 엔터티 + 범위를 바꾸기 전에 옮겨 둔 엔터티. 저장하지 않은 범위 밖 엔터티의 좌표는 서버에 있는 그대로 둔다
        body: {
          version: savedVersion.current,
          nodes: Object.fromEntries([...nodes.map((n) => [n.id, n.position] as const),
            ...[...pending.current].filter((id) => positions.current.has(id)).map((id) => [id, positions.current.get(id)!] as const)]),
          viewport: flow.getViewport(),
        },
      });
      savedVersion.current = result.version;
      clearPending();
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
    if (dirty) dialog.confirm(t('저장하지 않은 배치를 버리고 저장된 배치를 불러오시겠습니까?'), reload);
    else reload();
  };

  async function arrange() {
    if (!nodes.length) return;
    setArranging(true);
    try {
      const placed = await runLayout(nodes.map(toLayoutNode), (graph.data?.relationships ?? []).map((r) => ({ source: String(r.sourceEntityId), target: String(r.targetEntityId) })), LAYER_GAP);
      const moved = nodes.map((n) => ({ ...n, position: placed[n.id] }));
      moved.forEach((n) => positions.current.set(n.id, n.position));
      setNodes(moved);
      markPending(moved.map((n) => n.id));
      setMessage(t('자동 배치했습니다. [배치 저장] 을 눌러야 저장됩니다.'));
      requestAnimationFrame(() => void flow.fitView({ padding: 0.15, maxZoom: 1 }));
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setArranging(false);
    }
  }

  // ------------------------------------------------------------------ 편집 (M-8 1차 : 엔터티·속성)
  // 모델 데이터는 논리 모델 관리와 같은 API 로 바로 저장하고, 성공한 뒤 ERD 를 다시 조회한다 (실패하면 공통 알림만 뜨고 화면은 그대로다).
  // 배치는 건드리지 않는다 — 다시 조회해도 기존 노드의 위치는 positions 에서 그대로 온다.
  const refresh = () => graph.refetch();

  const openDetail = (entityId: number, initialTab?: EntityDetailTab) =>
    openEntityDetail(entityId, { onChanged: () => void refresh(), onNavigate: onLeave, readOnly: !editing, initialTab });

  /** 새 엔터티 : 보고 있는 화면 가운데에서 기존 노드와 겹치지 않는 자리에 두고 선택·포커스한다. 위치는 [배치 저장] 을 눌러야 저장된다 */
  const placeNewEntity = async (entityId: number) => {
    const id = String(entityId);
    const rect = canvas.current?.getBoundingClientRect();
    const center = rect ? flow.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }) : { x: 0, y: 0 };
    const size = { width: NODE_WIDTH, height: nodeHeight(0) };
    const position = findFreePosition({ x: Math.round(center.x - size.width / 2), y: Math.round(center.y - size.height / 2) }, size,
      nodes.map((n) => ({ ...n.position, width: NODE_WIDTH, height: nodeHeight(n.data.entity.attributes.length) })));
    positions.current.set(id, position);
    focusRequest.current = id;
    markPending([id]);
    setMessage(t('엔터티를 추가했습니다. 새 엔터티의 위치는 [배치 저장] 을 눌러야 저장됩니다.'));
    await refresh();
  };

  const addEntity = () => {
    dialog.open({
      title: t('엔터티 등록'),
      content: ({ close }: DialogControls) => <EntityForm modelId={modelId} onSaved={(entityId) => { close(); void placeNewEntity(entityId); }} />,
    });
  };

  const removeEntity = (entity: GraphEntity) => {
    dialog.confirm(t('{0} 엔터티를 삭제하시겠습니까? 속성과 식별자도 함께 삭제됩니다.', entity.name), async () => {
      // 삭제할 수 없으면(관계·매핑·승인) 서버가 거절하고 사유를 알린다 → 노드를 지우지 않는다
      await api(`/api/logical/entity/${entity.id}`, { method: 'DELETE' });
      positions.current.delete(String(entity.id));
      pending.current.delete(String(entity.id));
      setPendingCount(pending.current.size);
      setDirty(pending.current.size > 0);
      setSelectedId(null);
      setMessage(t('엔터티를 삭제했습니다.'));
      await refresh();
    });
  };

  const openAttributeForm = (entity: GraphEntity, attribute?: GraphAttribute) => {
    const open = (detail?: LogicalEntity) => dialog.open({
      title: attribute ? t('속성 수정') : t('속성 추가'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <AttributeForm entityId={entity.id} attribute={detail?.attributes.find((a) => a.id === attribute?.id)} onSaved={() => { close(); void refresh(); }} />
      ),
    });
    // 수정은 설명까지 포함한 최신 값을 받아 연다 (ERD 조회에는 설명이 없다)
    if (attribute) void api<LogicalEntity>(`/api/logical/entity/${entity.id}`).then(open);
    else open();
  };

  const removeAttribute = (attribute: GraphAttribute) => {
    dialog.confirm(t('{0} 속성을 삭제하시겠습니까?', attribute.name), async () => {
      await api(`/api/logical/attribute/${attribute.id}`, { method: 'DELETE' });
      await refresh();
    });
  };

  /** 순서 변경 : 엔터티의 속성 ID 전체를 새 순서로 보낸다 (엔터티 상세와 같은 API) */
  const moveAttribute = async (entity: GraphEntity, index: number, delta: number) => {
    const ids = entity.attributes.map((a) => a.id);
    [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
    await api(`/api/logical/entity/${entity.id}/attribute/order`, { method: 'PUT', body: ids });
    await refresh();
  };

  // ------------------------------------------------------------------ 편집 (M-8 2차 : 관계·식별자)
  // 연결점을 잇는 것은 관계 등록의 시작일 뿐이다. 화면에 선을 추가하지 않고 관계 폼을 열며, 저장에 성공해 다시 조회한 뒤에야 선이 생긴다.
  // 관계 속성 짝 (M-7 보강 3차) : 편집 모드 + 승인 전 관계만 바꾼다. 저장하면 ERD 를 다시 읽는다
  const openPairs = (relationship: LogicalRelationship) => {
    dialog.open({
      title: t('관계 속성 짝 — {0}', relationLabel(relationship)),
      className: 'dialog-wide',
      content: () => <RelationshipPairsEditor relationshipId={relationship.id} readOnly={!editing || !!relationship.isApproval} onSaved={() => void refresh()} />,
    });
  };
  const openRelationshipForm = (relationship?: LogicalRelationship, initial?: { sourceId?: number; targetId?: number }) => {
    if (!graph.data?.entities.length) return;
    // 연결점·선택한 엔터티는 지금 범위 안이다. 상대 엔터티는 범위 밖일 수 있어 대화상자에서 모델 안을 검색해 고른다 (모델 전체 목록을 받지 않는다)
    const option = (id?: number) => {
      const entity = id == null ? undefined : graph.data?.entities.find((e) => e.id === id);
      return entity ? { id: entity.id, name: entity.name } : undefined;
    };
    dialog.open({
      title: relationship ? t('관계 수정') : t('관계 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <RelationshipForm modelId={modelId} relationship={relationship} initial={{ source: option(initial?.sourceId), target: option(initial?.targetId) }}
          onSaved={(saved) => {
            close();
            setSelectedRelationship(saved.id);
            setSelectedId(null);
            void refresh();
          }} />
      ),
    });
  };

  const removeRelationship = (relationship: LogicalRelationship) => {
    dialog.confirm(t('{0} 관계를 삭제하시겠습니까? (출발 : {1}, 도착 : {2}) 엔터티·속성·식별자는 바뀌지 않습니다.', relationLabel(relationship), relationship.sourceEntityName, relationship.targetEntityName), async () => {
      await api(`/api/logical/relationship/${relationship.id}`, { method: 'DELETE' });
      setSelectedRelationship(null);
      setMessage(t('관계를 삭제했습니다.'));
      await refresh();
    });
  };

  const toggleRelationshipApproval = (relationship: LogicalRelationship) => {
    const approved = !!relationship.isApproval;
    dialog.confirm(approved ? t('이 관계를 승인취소하시겠습니까?') : t('이 관계를 승인하시겠습니까?'), async () => {
      await api(`/api/logical/relationship/${relationship.id}/approval/${!approved}`, { method: 'PATCH' });
      await refresh();
    });
  };

  const openIdentifierForm = (detail: LogicalEntity, identifier?: LogicalIdentifier) => {
    if (!detail.attributes.length) return dialog.alert(t('속성을 먼저 등록해주세요.'));
    dialog.open({
      title: identifier ? t('식별자 수정') : t('식별자 추가'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => <IdentifierForm entity={detail} identifier={identifier} onSaved={() => { close(); void refresh(); }} />,
    });
  };

  const removeIdentifier = (identifier: LogicalIdentifier) => {
    dialog.confirm(t('{0} 식별자를 삭제하시겠습니까?', identifier.identifierName), async () => {
      await api(`/api/logical/identifier/${identifier.id}`, { method: 'DELETE' });
      await refresh();
    });
  };

  const selected = graph.data?.entities.find((e) => String(e.id) === selectedId);
  const relationship = graph.data?.relationships.find((r) => r.id === selectedRelationship);
  const outside = graph.data?.outsideRelationships ?? {};
  const outsideTotal = Object.values(outside).reduce((sum, n) => sum + n, 0);
  const scopeName = scope.kind === 'neighbors' ? graph.data?.entities.find((e) => e.id === scope.entityId)?.name ?? scope.entityName : '';
  const loading = layout.isFetching || graph.isFetching || arranging;
  const error = layout.error ?? graph.error;

  return (
    <>
      <div className="grid-header">
        <form className="search-container" onSubmit={search}>
          {modelSelect}
          <input type="text" className="search-input search-input-text" aria-label={t('엔터티 검색')} maxLength={100} placeholder={t('엔터티명 / 설명')}
            value={keyword} onChange={(e) => setKeyword(e.target.value)} />
          <button type="submit" className="grd-select">{t('조회')}</button>
          {onMode && <ErdModeToggle mode={mode} onChange={onMode} />}
        </form>
        <div className="button-container">
          {editing && <button type="button" className="grd-add btn btn-primary" disabled={!graph.data} onClick={addEntity}><PlusIcon />{t('엔터티 추가')}</button>}
          <span className="erd-muted">{pendingCount ? t('저장하지 않은 배치 {0}개', pendingCount) : t('저장된 배치와 같습니다')}</span>
          <button type="button" className="btn" disabled={loading} onClick={() => void graph.refetch()} title={t('논리 모델 변경 내용을 다시 불러옵니다')}><SyncIcon />{t('새로고침')}</button>
          <button type="button" className="btn" disabled={loading || !nodes.length} onClick={() => void arrange()}><WorkflowIcon />{t('자동 배치')}</button>
          <button type="button" className="btn" disabled={!nodes.length} onClick={() => void flow.fitView({ padding: 0.15, duration: 300 })}><ScreenFullIcon />{t('전체 보기')}</button>
          <button type="button" className="btn" disabled={!nodes.length || erdExport.exporting} onClick={() => void erdExport.run('png')} title={t('지금 배치 그대로 ERD 전체를 PNG 이미지로 내려받습니다')}><DownloadIcon />PNG</button>
          <button type="button" className="btn" disabled={!nodes.length || erdExport.exporting} onClick={() => void erdExport.run('svg')} title={t('지금 배치 그대로 ERD 전체를 SVG 이미지로 내려받습니다')}><DownloadIcon />SVG</button>
          <button type="button" className="btn" disabled={loading || saving} onClick={reloadSaved}><UndoIcon />{t('저장된 배치 불러오기')}</button>
          <button type="button" className="grd-save btn btn-primary" disabled={loading || saving || !nodes.length} onClick={() => void save()}><CheckIcon />{saving ? t('저장 중…') : t('배치 저장')}</button>
          {onManage && <button type="button" className="btn" onClick={() => onManage()}>{t('논리 모델 관리')}</button>}
        </div>
      </div>

      <div className="erd-status" role="status">
        {scope.kind === 'neighbors' ? (
          <>
            <button type="button" className="btn btn-sm" onClick={() => setScope(scope.back)}><ArrowLeftIcon />{t('범위 조회로')}</button>
            <span>{tx('{0} 기준 {1}단계 이웃 · 엔터티 {2}개', <strong>{scopeName}</strong>, scope.depth, graph.data?.entities.length ?? 0)}</span>
          </>
        ) : (
          <span>
            {scope.keyword ? `"${scope.keyword}"` : t('모델 전체')}
            {graph.data && t(' · 전체 {0}개 중 {1}', graph.data.totalCount, graph.data.entities.length ? `${scope.page * PAGE_SIZE + 1}–${scope.page * PAGE_SIZE + graph.data.entities.length}` : 0)}
          </span>
        )}
        <span>{t('· 관계 {0}개', graph.data?.relationships.length ?? 0)}</span>
        {outsideTotal > 0 && <span className="erd-muted">{t('· 범위 밖 관계 {0}개', outsideTotal)}</span>}
        {graph.data && <span className="erd-muted">{t('(모델 엔터티 {0}개)', graph.data.modelEntityCount)}</span>}
        {graph.data?.truncated && <Label variant="attention">{scope.kind === 'neighbors' ? t('한도(300개)까지만 표시') : t('일부만 표시 - 검색으로 범위를 줄이거나 다음 페이지로')}</Label>}
        {scope.kind === 'search' && (graph.data?.truncated || scope.page > 0) && (
          <span className="erd-pager">
            <button type="button" className="btn btn-sm" disabled={loading || scope.page === 0} onClick={() => setScope({ ...scope, page: scope.page - 1 })}>{t('이전')}</button>
            <button type="button" className="btn btn-sm" disabled={loading || !graph.data?.truncated} onClick={() => setScope({ ...scope, page: scope.page + 1 })}>{t('다음 {0}개', PAGE_SIZE)}</button>
          </span>
        )}
        {editing && <span className="erd-mode-note">{t('편집 모드 · 엔터티·속성 변경은 바로 저장됩니다. 배치는 [배치 저장] 을 눌러야 저장됩니다.')}</span>}
        {loading && <span className="erd-muted">{t('불러오는 중…')}</span>}
        {message && <span className="erd-muted">{message}</span>}
      </div>

      <div className="erd-workspace">
        <div className={`erd-canvas${editing ? ' erd-editing' : ''}`} ref={canvas} aria-label={t('논리 ERD')}>
          <EndMarkers />
          {error && <div className="erd-empty" role="alert">{t('ERD 를 불러오지 못했습니다.')} {error.message}</div>}
          {!error && !loading && graph.data && !graph.data.entities.length && (
            <div className="erd-empty">{graph.data.modelEntityCount === 0 ? t('이 논리 모델에 등록된 엔터티가 없습니다.') : t('검색 결과가 없습니다.')}</div>
          )}
          {!error && !loading && scope.kind === 'neighbors' && graph.data?.entities.length === 1 && !graph.data.relationships.length && !outsideTotal && (
            <div className="erd-empty erd-empty-note">{t('선택한 엔터티에 연결된 관계가 없습니다.')}</div>
          )}
          <ReactFlow
            nodes={displayNodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            onNodesChange={onNodesChange}
            onNodeDragStop={(_, __, dragged) => {
              const moved = dragged.filter((n) => {
                const before = positions.current.get(n.id);
                return !before || before.x !== n.position.x || before.y !== n.position.y;
              });
              moved.forEach((n) => positions.current.set(n.id, n.position));
              if (moved.length) markPending(moved.map((n) => n.id));
            }}
            onNodeClick={(_, node) => { setSelectedId(node.id); setSelectedRelationship(null); }}
            onNodeDoubleClick={(_, node) => openDetail(node.data.entity.id)}
            onEdgeClick={(_, edge) => { setSelectedRelationship(edge.data?.relationship.id ?? null); setSelectedId(null); }}
            onPaneClick={() => { setSelectedId(null); setSelectedRelationship(null); }}
            nodesDraggable={!arranging}
            // 편집 모드에서만 연결할 수 있다. 연결은 선을 만들지 않고 관계 등록 대화상자를 연다 (취소하거나 저장에 실패하면 아무것도 생기지 않는다)
            nodesConnectable={editing}
            onConnect={(connection) => openRelationshipForm(undefined, { sourceId: Number(connection.source), targetId: Number(connection.target) })}
            elementsSelectable={false}
            deleteKeyCode={null}
            onlyRenderVisibleElements={!erdExport.exporting}
            minZoom={0.05}
            maxZoom={2}
          >
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>

        <aside className="erd-detail">
          <select className="form-control" aria-label={t('엔터티로 이동')} value={selectedId ?? ''} onChange={(e) => e.target.value && focus(e.target.value)}>
            <option value="">{t('엔터티로 이동')}</option>
            {graph.data?.entities.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
          </select>
          <EntityFinder modelId={modelId} onPick={(hit) => {
            if (graph.data?.entities.some((e) => e.id === hit.id)) focus(String(hit.id));
            else neighborsOf(hit.id, hit.name);
          }} />
          {selected ? (
            <EntityPanel
              graph={graph.data!}
              entity={selected}
              outsideCount={outside[String(selected.id)] ?? 0}
              depth={depth}
              onDepth={setDepth}
              onNeighbors={() => neighborsOf(selected.id, selected.name)}
              onFocus={() => focus(String(selected.id))}
              onDetail={() => openDetail(selected.id)}
              version={graph.dataUpdatedAt}
              edit={editing ? {
                onAddRelationship: () => openRelationshipForm(undefined, { sourceId: selected.id }),
                onAddIdentifier: (detail) => openIdentifierForm(detail),
                onEditIdentifier: openIdentifierForm,
                onDeleteIdentifier: removeIdentifier,
                // 엔터티 상세와 같은 기준 (서버도 다시 검사한다) : 엔터티 자체는 승인 전에만, 속성은 승인된 엔터티면 승인 권한자만
                entityEditable: !selected.isApproval,
                attributeEditable: !selected.isApproval || approvalAvailable,
                onEditEntity: () => openDetail(selected.id, 'basic'),
                onDeleteEntity: () => removeEntity(selected),
                onAddAttribute: () => openAttributeForm(selected),
                onEditAttribute: (attribute) => openAttributeForm(selected, attribute),
                onDeleteAttribute: removeAttribute,
                onMoveAttribute: (index, delta) => void moveAttribute(selected, index, delta),
              } : undefined}
              onManage={onManage ? () => onManage(selected.id) : undefined}
              onMapping={canView('model-mapping') ? () => onLeave(mappingPath(modelId, selected.id)) : undefined}
              onRelationship={(id) => { setSelectedRelationship(id); setSelectedId(null); }}
            />
          ) : relationship ? (
            <RelationshipPanel relationship={relationship} onEntity={focus}
              onApproval={approvalAvailable ? () => toggleRelationshipApproval(relationship) : undefined}
              edit={editing ? { onEdit: () => openRelationshipForm(relationship), onDelete: () => removeRelationship(relationship) } : undefined}
              onPairs={() => openPairs(relationship)} />
          ) : (
            <p className="erd-muted">{t('엔터티나 관계선을 누르면 상세를 볼 수 있습니다.')}</p>
          )}
          <div className="erd-legend">
            <span><i className="erd-line" style={{ borderTopColor: 'var(--gh-fg-muted)' }} />{t('식별 관계')}</span>
            <span><i className="erd-line dashed" style={{ borderTopColor: 'var(--gh-fg-muted)' }} />{t('비식별 관계')}</span>
            {/* Crow's Foot (M-6 후속 2차) : 관계선 끝과 같은 기호. 그 엔터티 쪽의 참여 수 = 최소(○ 선택 / | 필수)와 최대(| 하나 / 까치발 여러 건) */}
            {CROWS_FOOT.map(([kind, label]) => (
              <span key={kind} className="logical-legend-end">
                <svg width="40" height="16" aria-hidden><line x1="2" y1="8" x2="38" y2="8" stroke="var(--gh-fg-muted)" strokeWidth="1.25" markerEnd={`url(#logical-end-${kind})`} /></svg>
                {label}
              </span>
            ))}
            <span><b>PI</b> {t('주 식별자')}</span>
            <span><b>AI</b> {t('보조 식별자')}</span>
            <span><b>*</b> {t('필수 속성')}</span>
            <p className="erd-muted">{t('관계선 양 끝의 숫자는 그 엔터티 쪽의 참여 수입니다 (0 = 선택, 1 = 필수, N = 여러 건).')}</p>
            <p className="erd-muted">{t('최대가 2 이상인 참여 수(예 0..5)는 까치발로 그리고 정확한 수는 옆 숫자로 봅니다.')}</p>
          </div>
        </aside>
      </div>
    </>
  );
}

/**
 * 엔터티 찾기 (M-6 후속 1차) : 지금 ERD 범위와 무관하게 서버에서 이름·설명으로 찾는다 (최대 20개 + 전체 수).
 * 입력을 잠깐 멈췄을 때만 묻고, 검색어마다 따로 캐시해 늦게 온 이전 응답이 지금 결과를 덮지 않는다
 */
function EntityFinder({ modelId, onPick }: { modelId: number; onPick: (hit: EntityHits['items'][number]) => void }) {
  const [input, setInput] = useState('');
  const [keyword, setKeyword] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(input.trim()), 300);
    return () => clearTimeout(timer);
  }, [input]);
  const hits = useQuery({
    queryKey: ['logical-erd-finder', modelId, keyword],
    queryFn: ({ signal }) => api<EntityHits>(`/api/logical/model/${modelId}/erd/entities?size=20&keyword=${enc(keyword)}`, { signal, silent: true }),
    enabled: keyword.length > 0,
    staleTime: 30_000,
  });
  return (
    <div className="erd-finder">
      <input type="search" className="form-control" aria-label={t('엔터티 찾기')} maxLength={100} placeholder={t('엔터티 찾기 (ERD 밖의 엔터티도)')}
        value={input} onChange={(e) => setInput(e.target.value)} />
      {keyword && (
        hits.isError ? <p className="erd-muted" role="alert">{t('엔터티를 찾지 못했습니다.')}</p>
          : !hits.data ? <p className="erd-muted">{t('불러오는 중…')}</p>
            : hits.data.items.length === 0 ? <p className="erd-muted">{t('찾는 엔터티가 없습니다.')}</p> : (
              <>
                <p className="erd-muted">{t('찾은 엔터티 {0}개 중 {1}개', hits.data.totalCount, hits.data.items.length)}</p>
                <ul className="erd-list erd-finder-list">
                  {hits.data.items.map((hit) => (
                    <li key={hit.id}><button type="button" className="erd-list-button" onClick={() => onPick(hit)}>{hit.name}</button></li>
                  ))}
                </ul>
              </>
            )
      )}
    </div>
  );
}

function ApprovalLabel({ value }: { value: boolean | null }) {
  return value ? <Label variant="success" dot>{t('승인')}</Label> : <Label variant="attention" dot>{t('승인 대기')}</Label>;
}

/** 편집 모드의 엔터티 패널 동작. 조회 모드에서는 주지 않는다 */
interface EntityEdit {
  entityEditable: boolean;
  attributeEditable: boolean;
  onEditEntity: () => void;
  onDeleteEntity: () => void;
  onAddAttribute: () => void;
  onEditAttribute: (attribute: GraphAttribute) => void;
  onDeleteAttribute: (attribute: GraphAttribute) => void;
  onMoveAttribute: (index: number, delta: number) => void;
  onAddRelationship: () => void;
  onAddIdentifier: (detail: LogicalEntity) => void;
  onEditIdentifier: (detail: LogicalEntity, identifier: LogicalIdentifier) => void;
  onDeleteIdentifier: (identifier: LogicalIdentifier) => void;
}

function EntityPanel({ graph, entity, outsideCount, depth, onDepth, onNeighbors, onFocus, onDetail, onManage, onMapping, onRelationship, edit, version }: {
  graph: LogicalGraph;
  entity: GraphEntity;
  /** 상대 엔터티가 조회 범위 밖이라 그리지 않은 관계 수 */
  outsideCount: number;
  depth: number;
  onDepth: (depth: number) => void;
  onNeighbors: () => void;
  onFocus: () => void;
  onDetail: () => void;
  onManage?: () => void;
  /** 논리·물리 매핑 화면으로 (메뉴 권한이 없으면 undefined) */
  onMapping?: () => void;
  onRelationship: (relationshipId: number) => void;
  edit?: EntityEdit;
  /** ERD 조회 시각 : 바뀌면 식별자 목록도 다시 받는다 */
  version: number;
}) {
  const relationships = graph.relationships.filter((r) => r.sourceEntityId === entity.id || r.targetEntityId === entity.id);
  // 식별자(이름·주/보조·구성 속성 순서)는 ERD 조회에 없어 엔터티 상세와 같은 API 로 받는다
  const detail = useQuery({
    queryKey: ['logical-erd-entity', entity.id, version],
    queryFn: () => api<LogicalEntity>(`/api/logical/entity/${entity.id}`, { silent: true }),
    gcTime: 0,
  });
  const identifiers = detail.data?.identifiers ?? [];
  const approvedNote = t('승인된 엔터티는 승인취소 후에 변경할 수 있습니다.');
  const deleteBlocked = !edit ? '' : !edit.entityEditable ? approvedNote
    : relationships.length || outsideCount ? t('관계에 사용 중인 엔터티는 삭제할 수 없습니다. 관계를 먼저 정리해주세요.') : '';
  return (
    <>
      <h3>{entity.name}</h3>
      <p><ApprovalLabel value={entity.isApproval} /></p>
      <p className="erd-muted">{entity.description || t('설명 없음')}</p>
      <div className="erd-toolbar">
        <select className="form-control" aria-label={t('이웃 단계')} value={depth} onChange={(e) => onDepth(Number(e.target.value))}>
          {[1, 2, 3].map((n) => <option key={n} value={n}>{t('{0}단계', n)}</option>)}
        </select>
        <button type="button" className="btn btn-sm" onClick={onNeighbors}><GitMergeIcon />{t('이웃 탐색')}</button>
      </div>
      <div className="erd-toolbar erd-links">
        <button type="button" className="btn btn-sm" onClick={onFocus}>{t('이동')}</button>
        <button type="button" className="btn btn-sm" onClick={onDetail}>{t('엔터티 상세')}</button>
        {onManage && <button type="button" className="btn btn-sm" onClick={onManage}>{t('관리 화면에서 보기')}</button>}
        {onMapping && <button type="button" className="btn btn-sm" onClick={onMapping}>{t('매핑 관리')}</button>}
      </div>
      {edit && (
        <div className="erd-toolbar erd-links erd-edit-actions">
          <button type="button" className="btn btn-sm" onClick={edit.onEditEntity} title={edit.entityEditable ? undefined : approvedNote}>{t('엔터티 수정')}</button>
          <button type="button" className="btn btn-sm btn-primary" disabled={!edit.attributeEditable} title={edit.attributeEditable ? undefined : approvedNote} onClick={edit.onAddAttribute}>{t('속성 추가')}</button>
          <button type="button" className="btn btn-sm btn-danger" disabled={!!deleteBlocked} title={deleteBlocked || undefined} onClick={edit.onDeleteEntity}>{t('엔터티 삭제')}</button>
        </div>
      )}

      <h4>{t('속성 {0}개', entity.attributes.length)}</h4>
      <ul className="erd-list">
        {entity.attributes.map((a, index) => (
          <li key={a.id}>
            <strong>{a.primaryIdentifier ? 'PI ' : a.identifier ? 'AI ' : ''}{a.name}{a.isRequired || a.primaryIdentifier ? ' *' : ''}</strong>
            <small>{[a.termName?.replaceAll('_', ' '), a.domainName, a.dataType].filter(Boolean).join(' · ') || t('연결된 표준 용어가 없습니다.')}</small>
            {edit?.attributeEditable && (
              <span className="erd-attribute-actions">
                <button type="button" className="btn btn-sm" aria-label={t('위로')} title={t('위로')} disabled={index === 0} onClick={() => edit.onMoveAttribute(index, -1)}>↑</button>
                <button type="button" className="btn btn-sm" aria-label={t('아래로')} title={t('아래로')} disabled={index === entity.attributes.length - 1} onClick={() => edit.onMoveAttribute(index, 1)}>↓</button>
                <button type="button" className="btn btn-sm" onClick={() => edit.onEditAttribute(a)}>{t('수정')}</button>
                <button type="button" className="btn btn-sm btn-danger" disabled={a.identifier} onClick={() => edit.onDeleteAttribute(a)}
                  title={a.identifier ? t('식별자에 포함된 속성은 삭제할 수 없습니다. 식별자에서 먼저 빼주세요.') : undefined}>{t('삭제')}</button>
              </span>
            )}
          </li>
        ))}
      </ul>

      <h4>{detail.data ? t('식별자 {0}개', identifiers.length) : t('식별자')}</h4>
      {edit?.attributeEditable && detail.data && (
        <div className="erd-toolbar erd-links"><button type="button" className="btn btn-sm" onClick={() => edit.onAddIdentifier(detail.data)}>{t('식별자 추가')}</button></div>
      )}
      {detail.isError ? <p className="erd-muted" role="alert">{t('식별자를 불러오지 못했습니다.')}</p> : !detail.data ? <p className="erd-muted">{t('불러오는 중…')}</p> : identifiers.length ? (
        <ul className="erd-list">
          {identifiers.map((identifier) => (
            <li key={identifier.id}>
              <strong>{identifier.isPrimary ? 'PI ' : 'AI '}{identifier.identifierName}</strong>
              <small>{identifier.isPrimary ? t('주 식별자') : t('보조 식별자')} · {identifier.attributeNames.join(' + ')}{identifier.attributeIds.length > 1 ? ` · ${t('복합')}` : ''}</small>
              {edit?.attributeEditable && (
                <span className="erd-attribute-actions">
                  <button type="button" className="btn btn-sm" onClick={() => edit.onEditIdentifier(detail.data, identifier)}>{t('수정')}</button>
                  <button type="button" className="btn btn-sm btn-danger" onClick={() => edit.onDeleteIdentifier(identifier)}>{t('삭제')}</button>
                </span>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="erd-muted">{t('등록된 식별자가 없습니다.')}</p>
      )}

      <h4>{t('관계 {0}개', relationships.length + outsideCount)}</h4>
      {outsideCount > 0 && <p className="erd-muted">{t('이 중 {0}개는 상대 엔터티가 조회 범위 밖이라 그리지 않았습니다. [이웃 탐색] 으로 볼 수 있습니다.', outsideCount)}</p>}
      {edit && <div className="erd-toolbar erd-links"><button type="button" className="btn btn-sm" onClick={edit.onAddRelationship}>{t('관계 추가')}</button></div>}
      {relationships.length ? (
        <ul className="erd-list">
          {relationships.map((r) => (
            <li key={r.id}>
              <button type="button" className="erd-list-button" title={t('관계 상세 보기')} onClick={() => onRelationship(r.id)}>
                <strong>{r.sourceEntityName} → {r.targetEntityName}</strong>
                <small>{[r.relationshipName, cardinalityText(r), r.isIdentifying ? t('식별') : t('비식별'), r.isApproval ? t('승인') : t('승인 대기')].filter(Boolean).join(' · ')}</small>
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="erd-muted">{t('관계가 없습니다.')}</p>
      )}
    </>
  );
}

function RelationshipPanel({ relationship: r, onEntity, onApproval, edit, onPairs }: {
  relationship: LogicalRelationship;
  onEntity: (entityId: string) => void;
  /** 속성 짝 대화상자 (조회 모드는 보기만, 편집 모드 + 승인 전이면 편집) */
  onPairs: () => void;
  /** 승인·승인취소 (승인 권한자만). 편집이 아니라 승인 절차라 조회 모드에서도 보인다 */
  onApproval?: () => void;
  /** 편집 모드의 수정·삭제. 승인된 관계는 승인취소 후에 바꿀 수 있다 (서버도 같은 기준) */
  edit?: { onEdit: () => void; onDelete: () => void };
}) {
  const approvedNote = r.isApproval ? t('승인된 관계는 승인취소 후에 변경할 수 있습니다.') : undefined;
  const side = (label: string, entityId: number, name: string, min: number, max: number | null) => (
    <li>
      <button type="button" className="erd-list-button" title={t('엔터티로 이동')} onClick={() => onEntity(String(entityId))}>
        <strong>{label} : {name}</strong>
        <small>{t('참여 수')} {participation(min, max)} · {min === 0 ? t('선택') : t('필수')}{max == null ? t(' · 여러 건') : ''}</small>
      </button>
    </li>
  );
  return (
    <>
      <h3>{r.relationshipName || t('이름 없는 관계')}</h3>
      <p>
        <ApprovalLabel value={r.isApproval} /> <Label variant={r.isIdentifying ? 'accent' : ''}>{r.isIdentifying ? t('식별 관계') : t('비식별 관계')}</Label> <Label>{r.cardinality}</Label>
      </p>
      <p className="erd-muted">{r.relationshipDescription || t('설명 없음')}</p>
      {(edit || onApproval) && (
        <div className="erd-toolbar erd-links erd-edit-actions">
          {edit && <button type="button" className="btn btn-sm" disabled={!!r.isApproval} title={approvedNote} onClick={edit.onEdit}>{t('관계 수정')}</button>}
          {edit && <button type="button" className="btn btn-sm btn-danger" disabled={!!r.isApproval} title={approvedNote} onClick={edit.onDelete}>{t('관계 삭제')}</button>}
          {onApproval && <button type="button" className="btn btn-sm" onClick={onApproval}>{r.isApproval ? t('승인취소') : tc('버튼', '승인')}</button>}
        </div>
      )}
      <ul className="erd-list">
        {side(t('출발 엔터티'), r.sourceEntityId, r.sourceEntityName, r.sourceMin, r.sourceMax)}
        {side(t('도착 엔터티'), r.targetEntityId, r.targetEntityName, r.targetMin, r.targetMax)}
      </ul>
      <p className="erd-muted">{t('참여 수 {0} · 식별 여부는 관계에만 저장됩니다. 관계를 바꿔도 엔터티의 식별자는 자동으로 바뀌지 않습니다.', cardinalityText(r))}</p>
      <h4 className="erd-subtitle">{t('속성 짝')} <PairStatusLabel state={r.attributePairs} /></h4>
      <PairList state={r.attributePairs} />
      {r.attributePairs && r.attributePairs.warnings.length > 0 && <ul className="erd-muted">{r.attributePairs.warnings.map((w) => <li key={w}>{PAIR_WARNINGS[w]}</li>)}</ul>}
      {r.attributePairs?.status === 'NONE' && <p className="erd-muted">{t('속성 짝이 없는 관계는 물리 모델 생성에서 FK 를 만들지 않습니다.')}</p>}
      <div className="erd-toolbar erd-links">
        <button type="button" className="btn btn-sm" onClick={onPairs}>{edit && !r.isApproval ? t('속성 짝 편집') : t('속성 짝 보기')}</button>
      </div>
    </>
  );
}
