import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Background, Controls, ReactFlow, ReactFlowProvider, useNodesState, useReactFlow } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../styles/erd.css';
import '../../styles/logical.css';
import { CheckIcon, PlusIcon, ScreenFullIcon, SyncIcon, UndoIcon, WorkflowIcon } from '@primer/octicons-react';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t, tc } from '../../lib/i18n';
// 물리 ERD 와 같이 쓰는 것 : 자동 배치(ELK 워커), 노드 크기 규칙, 축소 시 행 생략, 스타일(erd.css). 데이터·레이아웃 저장은 따로다
import { cancelLayout, gridLayout, runLayout, type LayoutNode } from '../erd/layout';
import { NODE_WIDTH, nodeHeight, type ErdLayout, type XY } from '../erd/model';
import { ErdModeToggle, findFreePosition, type ErdMode } from '../erd/editing';
import { AttributeForm, EntityForm, IdentifierForm, useEntityDetail, type EntityDetailTab } from './entityDetail';
import { edgeTypes, EndMarkers, LAYER_GAP, nodeTypes, relationshipEdges, type EntityNodeType, type RelationshipEdgeType } from './erdNodes';
import { relationLabel, RelationshipForm } from './relationshipForm';
import { mappingPath } from '../mapping/model';
import { cardinalityText, participation, useLogicalGraph, useLogicalModels, type GraphAttribute, type GraphEntity, type LogicalEntity, type LogicalGraph, type LogicalIdentifier, type LogicalRelationship } from './model';

const toLayoutNode = (n: EntityNodeType): LayoutNode => ({ id: n.id, width: NODE_WIDTH, height: nodeHeight(n.data.entity.attributes.length) });

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
  const graph = useLogicalGraph(modelId, layout.isSuccess);

  useEffect(() => () => onDirty(false), []);

  // 조회 결과 → 노드. 저장된 위치가 없는 엔터티만 기존 배치 오른쪽에 자동 배치한다 (기존 위치는 덮어쓰지 않음)
  useEffect(() => {
    const data = graph.data;
    if (!data || !layout.data) return;
    if (appliedLayout.current !== layout.data) {
      appliedLayout.current = layout.data;
      savedVersion.current = layout.data.version;
      positions.current = new Map(Object.entries(layout.data.nodes));
      setDirty(false);
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
      focusRequest.current = null;
      const target = requested ? built.find((n) => n.id === requested) : undefined; // 없는 ID 는 조용히 무시한다
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
        body: { version: savedVersion.current, nodes: Object.fromEntries(nodes.map((n) => [n.id, n.position])), viewport: flow.getViewport() },
      });
      savedVersion.current = result.version;
      setDirty(false);
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
      setDirty(true);
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
    setDirty(true);
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
  const openRelationshipForm = (relationship?: LogicalRelationship, initial?: { sourceId?: number; targetId?: number }) => {
    const entities = graph.data?.entities ?? [];
    if (!entities.length) return;
    dialog.open({
      title: relationship ? t('관계 수정') : t('관계 등록'),
      className: 'dialog-wide',
      content: ({ close }: DialogControls) => (
        <RelationshipForm modelId={modelId} entities={entities} relationship={relationship} initial={initial}
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
  const loading = layout.isFetching || graph.isFetching || arranging;
  const error = layout.error ?? graph.error;

  return (
    <>
      <div className="grid-header">
        <div className="search-container">
          {modelSelect}
          {onMode && <ErdModeToggle mode={mode} onChange={onMode} />}
        </div>
        <div className="button-container">
          {editing && <button type="button" className="grd-add btn btn-primary" disabled={!graph.data} onClick={addEntity}><PlusIcon />{t('엔터티 추가')}</button>}
          <span className="erd-muted">{dirty ? t('저장하지 않은 배치가 있습니다') : t('저장된 배치와 같습니다')}</span>
          <button type="button" className="btn" disabled={loading} onClick={() => void graph.refetch()} title={t('논리 모델 변경 내용을 다시 불러옵니다')}><SyncIcon />{t('새로고침')}</button>
          <button type="button" className="btn" disabled={loading || !nodes.length} onClick={() => void arrange()}><WorkflowIcon />{t('자동 배치')}</button>
          <button type="button" className="btn" disabled={!nodes.length} onClick={() => void flow.fitView({ padding: 0.15, duration: 300 })}><ScreenFullIcon />{t('전체 보기')}</button>
          <button type="button" className="btn" disabled={loading || saving} onClick={reloadSaved}><UndoIcon />{t('저장된 배치 불러오기')}</button>
          <button type="button" className="grd-save btn btn-primary" disabled={loading || saving || !nodes.length} onClick={() => void save()}><CheckIcon />{saving ? t('저장 중…') : t('배치 저장')}</button>
          {onManage && <button type="button" className="btn" onClick={() => onManage()}>{t('논리 모델 관리')}</button>}
        </div>
      </div>

      <div className="erd-status" role="status">
        <span>{t('엔터티 {0}개', graph.data?.entities.length ?? 0)}</span>
        <span>{t('· 관계 {0}개', graph.data?.relationships.length ?? 0)}</span>
        {editing && <span className="erd-mode-note">{t('편집 모드 · 엔터티·속성 변경은 바로 저장됩니다. 배치는 [배치 저장] 을 눌러야 저장됩니다.')}</span>}
        {loading && <span className="erd-muted">{t('불러오는 중…')}</span>}
        {message && <span className="erd-muted">{message}</span>}
      </div>

      <div className="erd-workspace">
        <div className={`erd-canvas${editing ? ' erd-editing' : ''}`} ref={canvas} aria-label={t('논리 ERD')}>
          <EndMarkers />
          {error && <div className="erd-empty" role="alert">{t('ERD 를 불러오지 못했습니다.')} {error.message}</div>}
          {!error && !loading && graph.data && !graph.data.entities.length && <div className="erd-empty">{t('이 논리 모델에 등록된 엔터티가 없습니다.')}</div>}
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
              if (moved.length) setDirty(true);
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
            onlyRenderVisibleElements
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
          {selected ? (
            <EntityPanel
              graph={graph.data!}
              entity={selected}
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
              edit={editing ? { onEdit: () => openRelationshipForm(relationship), onDelete: () => removeRelationship(relationship) } : undefined} />
          ) : (
            <p className="erd-muted">{t('엔터티나 관계선을 누르면 상세를 볼 수 있습니다.')}</p>
          )}
          <div className="erd-legend">
            <span><i className="erd-line" style={{ borderTopColor: 'var(--gh-fg-muted)' }} />{t('식별 관계')}</span>
            <span><i className="erd-line dashed" style={{ borderTopColor: 'var(--gh-fg-muted)' }} />{t('비식별 관계')}</span>
            <span><b>PI</b> {t('주 식별자')}</span>
            <span><b>AI</b> {t('보조 식별자')}</span>
            <span><b>*</b> {t('필수 속성')}</span>
            <p className="erd-muted">{t('관계선 양 끝의 숫자는 그 엔터티 쪽의 참여 수입니다 (0 = 선택, 1 = 필수, N = 여러 건).')}</p>
          </div>
        </aside>
      </div>
    </>
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

function EntityPanel({ graph, entity, onFocus, onDetail, onManage, onMapping, onRelationship, edit, version }: {
  graph: LogicalGraph;
  entity: GraphEntity;
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
    : relationships.length ? t('관계에 사용 중인 엔터티는 삭제할 수 없습니다. 관계를 먼저 정리해주세요.') : '';
  return (
    <>
      <h3>{entity.name}</h3>
      <p><ApprovalLabel value={entity.isApproval} /></p>
      <p className="erd-muted">{entity.description || t('설명 없음')}</p>
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

      <h4>{t('관계 {0}개', relationships.length)}</h4>
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

function RelationshipPanel({ relationship: r, onEntity, onApproval, edit }: {
  relationship: LogicalRelationship;
  onEntity: (entityId: string) => void;
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
    </>
  );
}
