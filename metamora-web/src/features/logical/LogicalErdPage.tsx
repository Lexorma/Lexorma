import { memo, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Background, BaseEdge, Controls, EdgeLabelRenderer, getSmoothStepPath, Handle, Position, ReactFlow, ReactFlowProvider, useNodesState, useReactFlow,
  type Edge, type EdgeProps, type Node, type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import '../../styles/erd.css';
import '../../styles/logical.css';
import { CheckIcon, ScreenFullIcon, SyncIcon, UndoIcon, WorkflowIcon } from '@primer/octicons-react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { PageHeader } from '../../components/layout/PageHeader';
import { useAuth } from '../../hooks/useAuth';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
// 물리 ERD 와 같이 쓰는 것 : 자동 배치(ELK 워커), 노드 크기 규칙, 축소 시 행 생략, 스타일(erd.css). 데이터·레이아웃 저장은 따로다
import { cancelLayout, gridLayout, runLayout, type LayoutNode } from '../erd/layout';
import { HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight, type ErdLayout, type XY } from '../erd/model';
import { useSimpleNodes } from '../erd/TableNode';
import { useEntityDetail } from './entityDetail';
import { mappingPath } from '../mapping/model';
import { cardinalityText, participation, useLogicalGraph, useLogicalModels, type GraphEntity, type LogicalGraph, type LogicalRelationship } from './model';

type EntityNodeType = Node<{ entity: GraphEntity }, 'entity'>;
/** sourceSlot·targetSlot : 같은 연결점을 쓰는 관계 중 몇 번째인지 (참여 수 글자가 겹치지 않게 옆으로 민다) */
type RelationshipEdgeType = Edge<{ relationship: LogicalRelationship; selected: boolean; sourceSlot: number; targetSlot: number }, 'relationship'>;

/** 연결점 : 왼쪽(들어옴) / 오른쪽(나감) / 자기 참조는 오른쪽 위에서 나가 오른쪽 아래로 들어온다 */
const HANDLE = { in: 'in', out: 'out', selfOut: 'self-out', self: 'self' } as const;
const SLOT_WIDTH = 34;
/** 엔터티 사이 간격 : 양 끝 참여 수와 관계명이 들어갈 자리 */
const LAYER_GAP = 260;

/** 엔터티 노드 : 속성 앞에 식별자 표시(PI 주 식별자 / AI 보조 식별자), 뒤에 필수(*) */
const EntityNode = memo(function EntityNode({ data, selected }: NodeProps<EntityNodeType>) {
  const simple = useSimpleNodes();
  const { entity } = data;
  return (
    <div className={`erd-table${selected ? ' selected' : ''}`} style={{ width: NODE_WIDTH, height: nodeHeight(entity.attributes.length) }}>
      <header title={entity.name}>
        <strong className="logical-entity-name">{entity.name}</strong>
        <span>{entity.description || t('설명 없음')}{entity.isApproval ? '' : t(' · 승인 대기')}</span>
      </header>
      {!simple && entity.attributes.slice(0, MAX_ROWS).map((a) => (
        <div className="erd-column" key={a.id} title={[a.name, a.termName, a.domainName].filter(Boolean).join(' · ')}>
          <b>{a.primaryIdentifier ? 'PI' : a.identifier ? 'AI' : ''}</b>
          <span className="logical-attribute-name">{a.name}</span>
          <small>{a.domainName || a.dataType || ''}</small>
          <small>{a.isRequired || a.primaryIdentifier ? '*' : ''}</small>
        </div>
      ))}
      {!simple && entity.attributes.length > MAX_ROWS && <div className="erd-column-more">{t('외 {0}개 속성 · 상세 패널에서 확인', entity.attributes.length - MAX_ROWS)}</div>}
      <Handle type="target" id={HANDLE.in} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="source" id={HANDLE.out} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={false} />
      <Handle type="source" id={HANDLE.selfOut} position={Position.Right} style={{ top: 8 }} isConnectable={false} />
      <Handle type="target" id={HANDLE.self} position={Position.Right} style={{ top: HEADER_HEIGHT - 8 }} isConnectable={false} />
    </div>
  );
});

/**
 * 관계선 : 각 엔터티 옆에 **그 엔터티 쪽의 참여 수**를 적는다 (출발 쪽 = sourceMin..sourceMax, 도착 쪽 = targetMin..targetMax).
 * 실선 = 식별 관계, 점선 = 비식별 관계.
 */
function RelationshipEdge({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data }: EdgeProps<RelationshipEdgeType>) {
  const [path, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition });
  if (!data) return <BaseEdge id={id} path={path} style={style} />;
  const r = data.relationship;
  // 도착 연결점이 오른쪽이면(자기 참조) 글자를 오른쪽에, 보통은 엔터티 왼쪽 바깥에 둔다
  const targetRight = targetPosition === Position.Right;
  return (
    <>
      <BaseEdge id={id} path={path} style={style} />
      <EdgeLabelRenderer>
        <span className={`logical-edge-label${data.selected ? ' selected' : ''}`} data-end="source" style={{ transform: `translate(0, -100%) translate(${sourceX + 6 + data.sourceSlot * SLOT_WIDTH}px, ${sourceY - 2}px)` }}>
          {participation(r.sourceMin, r.sourceMax)}
        </span>
        <span className={`logical-edge-label${data.selected ? ' selected' : ''}`} data-end="target"
          style={{ transform: targetRight
            ? `translate(0, 0) translate(${targetX + 6 + data.targetSlot * SLOT_WIDTH}px, ${targetY + 2}px)`
            : `translate(-100%, -100%) translate(${targetX - 6 - data.targetSlot * SLOT_WIDTH}px, ${targetY - 2}px)` }}>
          {participation(r.targetMin, r.targetMax)}
        </span>
        {r.relationshipName && (
          <span className={`logical-edge-label logical-edge-name${data.selected ? ' selected' : ''}`} style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY + data.sourceSlot * 20}px)` }}>
            {r.relationshipName}
          </span>
        )}
      </EdgeLabelRenderer>
    </>
  );
}

const nodeTypes = { entity: EntityNode };
const edgeTypes = { relationship: RelationshipEdge };
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
        description={t('논리 모델의 엔터티·속성·관계를 조회합니다. 엔터티를 옮긴 배치는 [배치 저장] 을 눌러야 저장되며, 배치는 모델마다 따로 나에게만 보입니다. 편집은 논리 모델 관리에서 합니다.')} />
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

function ErdWorkspace({ modelId, focusEntity, onDirty, onManage, onLeave, modelSelect }: {
  modelId: number;
  focusEntity: string | null;
  onDirty: (dirty: boolean) => void;
  /** 논리 모델 관리로 이동 (entityId 를 주면 그 엔터티 상세를 연다). 메뉴 권한이 없으면 undefined */
  onManage?: (entityId?: number) => void;
  /** 다른 화면으로 이동 (저장하지 않은 배치가 있으면 먼저 확인한다) */
  onLeave: (path: string) => void;
  modelSelect: ReactNode;
}) {
  const { user, canView } = useAuth();
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

  const edges = useMemo<RelationshipEdgeType[]>(() => {
    const used = new Map<string, number>();
    const slot = (key: string) => {
      const index = used.get(key) ?? 0;
      used.set(key, index + 1);
      return index;
    };
    return (graph.data?.relationships ?? []).map((relationship) => {
    const self = relationship.sourceEntityId === relationship.targetEntityId;
    const sourceHandle = self ? HANDLE.selfOut : HANDLE.out;
    const targetHandle = self ? HANDLE.self : HANDLE.in;
    const picked = selectedRelationship === relationship.id;
    const highlighted = picked || selectedId === String(relationship.sourceEntityId) || selectedId === String(relationship.targetEntityId);
    return {
      id: `r${relationship.id}`,
      type: 'relationship',
      source: String(relationship.sourceEntityId),
      target: String(relationship.targetEntityId),
      sourceHandle,
      targetHandle,
      // 실선 = 식별, 점선 = 비식별
      // 선택한 엔터티에 걸린 관계(또는 선택한 관계)는 굵기와 색으로 함께 강조한다
      style: { stroke: highlighted ? 'var(--gh-accent)' : 'var(--gh-fg-muted)', strokeDasharray: relationship.isIdentifying ? undefined : '6 4', strokeWidth: highlighted ? 2.5 : 1.25 },
      zIndex: highlighted ? 1 : 0, // 강조한 선이 다른 선 위에 그려지게
      data: {
        relationship, selected: highlighted,
        sourceSlot: slot(`${relationship.sourceEntityId}:${sourceHandle}`), targetSlot: slot(`${relationship.targetEntityId}:${targetHandle}`),
      },
    };
    });
  }, [graph.data, selectedId, selectedRelationship]);

  const displayNodes = useMemo(() => nodes.map((n) => ({ ...n, selected: n.id === selectedId })), [nodes, selectedId]);

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

  const selected = graph.data?.entities.find((e) => String(e.id) === selectedId);
  const relationship = graph.data?.relationships.find((r) => r.id === selectedRelationship);
  const loading = layout.isFetching || graph.isFetching || arranging;
  const error = layout.error ?? graph.error;

  return (
    <>
      <div className="grid-header">
        <div className="search-container">{modelSelect}</div>
        <div className="button-container">
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
        {loading && <span className="erd-muted">{t('불러오는 중…')}</span>}
        {message && <span className="erd-muted">{message}</span>}
      </div>

      <div className="erd-workspace">
        <div className="erd-canvas" aria-label={t('논리 ERD')}>
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
            onEdgeClick={(_, edge) => { setSelectedRelationship(edge.data?.relationship.id ?? null); setSelectedId(null); }}
            onPaneClick={() => { setSelectedId(null); setSelectedRelationship(null); }}
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
          <select className="form-control" aria-label={t('엔터티로 이동')} value={selectedId ?? ''} onChange={(e) => e.target.value && focus(e.target.value)}>
            <option value="">{t('엔터티로 이동')}</option>
            {graph.data?.entities.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
          </select>
          {selected ? (
            <EntityPanel
              graph={graph.data!}
              entity={selected}
              onFocus={() => focus(String(selected.id))}
              onDetail={() => openEntityDetail(selected.id, { onChanged: () => void graph.refetch(), onNavigate: onLeave })}
              onManage={onManage ? () => onManage(selected.id) : undefined}
              onMapping={canView('model-mapping') ? () => onLeave(mappingPath(modelId, selected.id)) : undefined}
              onRelationship={(id) => { setSelectedRelationship(id); setSelectedId(null); }}
            />
          ) : relationship ? (
            <RelationshipPanel relationship={relationship} onEntity={focus} />
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

function EntityPanel({ graph, entity, onFocus, onDetail, onManage, onMapping, onRelationship }: {
  graph: LogicalGraph;
  entity: GraphEntity;
  onFocus: () => void;
  onDetail: () => void;
  onManage?: () => void;
  /** 논리·물리 매핑 화면으로 (메뉴 권한이 없으면 undefined) */
  onMapping?: () => void;
  onRelationship: (relationshipId: number) => void;
}) {
  const relationships = graph.relationships.filter((r) => r.sourceEntityId === entity.id || r.targetEntityId === entity.id);
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

      <h4>{t('속성 {0}개', entity.attributes.length)}</h4>
      <ul className="erd-list">
        {entity.attributes.map((a) => (
          <li key={a.id}>
            <strong>{a.primaryIdentifier ? 'PI ' : a.identifier ? 'AI ' : ''}{a.name}{a.isRequired || a.primaryIdentifier ? ' *' : ''}</strong>
            <small>{[a.termName?.replaceAll('_', ' '), a.domainName, a.dataType].filter(Boolean).join(' · ') || t('연결된 표준 용어가 없습니다.')}</small>
          </li>
        ))}
      </ul>

      <h4>{t('관계 {0}개', relationships.length)}</h4>
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

function RelationshipPanel({ relationship: r, onEntity }: { relationship: LogicalRelationship; onEntity: (entityId: string) => void }) {
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
      <ul className="erd-list">
        {side(t('출발 엔터티'), r.sourceEntityId, r.sourceEntityName, r.sourceMin, r.sourceMax)}
        {side(t('도착 엔터티'), r.targetEntityId, r.targetEntityName, r.targetMin, r.targetMax)}
      </ul>
    </>
  );
}
