import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Background, Controls, ReactFlow, ReactFlowProvider, useReactFlow, type Edge, type Node, type NodeTypes, type ReactFlowInstance, type Viewport } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { ScreenFullIcon } from '@primer/octicons-react';
import '../../styles/erd.css';
import '../../styles/logical.css';
import { Label, Muted } from '../../components/grid/cells';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { relationEdges } from '../erd/edges';
import { cancelLayout, gridLayout, runLayout } from '../erd/layout';
import { DIFF_COLOR, DIFF_LABEL, DIFF_MARK, NODE_WIDTH, nodeHeight, type DiffKind, type XY } from '../erd/model';
import { TableNode, useSimpleNodes } from '../erd/TableNode';
import { edgeTypes, EndMarkers, LAYER_GAP, nodeTypes as logicalNodeTypes, relationshipEdges } from '../logical/erdNodes';
import { ChangeItem, changeKey } from './changeItem';
import {
  edgeEnds, erdKindOf, isChanged, layoutLinks, locate, nodeChange, rowChange, rowCount, sideModel,
  type ErdComparison, type ErdKind, type ErdObject, type Selection, type Side, type SideModel,
} from './erdDiff';
import { CHANGES, versionTypeLabel, type VersionChange, type VersionComparison } from './model';

/** 목록에서 고른 객체 (같은 객체를 다시 골라도 그 자리로 옮기도록 seq 를 둔다) */
export interface ErdFocus {
  targetType: string;
  targetId: number;
  seq: number;
}

const NODE_TYPES = { table: TableNode, ...logicalNodeTypes } as NodeTypes;
const KIND_LABELS: [ErdKind, string][] = [['PHYSICAL', t('물리 ERD')], ['LOGICAL', t('논리 ERD')]];
const edgeTag = (change: DiffKind | undefined) => (change ? `${DIFF_MARK[change]} ${DIFF_LABEL[change]}` : undefined);

const changedCount = (byType: VersionComparison['byType'], kind: ErdKind) =>
  Object.entries(byType).filter(([type]) => type.startsWith(`${kind}_`) && type !== 'LOGICAL_MODEL').reduce((sum, [, count]) => sum + count.ADDED + count.REMOVED + count.MODIFIED, 0);

/**
 * 버전 비교 결과의 ERD (M-10 ③) : 변경 전(기준 버전)과 변경 후(대상 버전 또는 현재 모델)를 나란히 그린다.
 * - 모델 전체가 아니라 달라진 테이블·엔터티와 관계로 이어진 이웃만 서버에서 받는다. 범위 밖의 객체는 목록에서 고르면 그 객체부터 다시 받는다
 * - 두 화면은 같은 배치(자동 배치, 저장하지 않는다)와 같은 보기 위치를 쓴다 : 같은 객체가 양쪽에서 같은 자리에 있다
 * - 조회만 한다 : 노드를 옮기거나 잇거나 지울 수 없고, 확대·축소·이동만 된다
 */
export function VersionErd({ baseId, targetId, byType, focus }: { baseId: string; targetId: string | null; byType: VersionComparison['byType']; focus: ErdFocus | null }) {
  const [kind, setKind] = useState<ErdKind>(() => (focus && erdKindOfFocus(focus)) || (changedCount(byType, 'PHYSICAL') === 0 && changedCount(byType, 'LOGICAL') > 0 ? 'LOGICAL' : 'PHYSICAL'));
  // 서버에 "이 객체부터" 를 요청한 것. 범위 안에서 찾을 수 있는 객체는 다시 요청하지 않는다
  const [scope, setScope] = useState<ErdFocus | null>(null);
  const [pending, setPending] = useState<ErdFocus | null>(focus);
  const [selected, setSelected] = useState<Selection | null>(null);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [message, setMessage] = useState('');
  const [placed, setPlaced] = useState<{ data: ErdComparison; positions: Record<string, XY> } | null>(null);
  const flows = useRef<Partial<Record<Side, ReactFlowInstance>>>({});

  useEffect(() => {
    if (!focus) return;
    const next = erdKindOfFocus(focus);
    if (next) setKind(next);
    setPending(focus);
  }, [focus]);

  const erd = useQuery({
    queryKey: ['model-version-compare-erd', baseId, targetId, kind, scope?.targetType, scope?.targetId],
    queryFn: () => {
      const params = new URLSearchParams({ baseId, kind });
      if (targetId) params.set('targetId', targetId);
      if (scope) { params.set('focusType', scope.targetType); params.set('focusId', String(scope.targetId)); }
      return api<ErdComparison>(`/api/modelVersion/compare/erd?${params}`);
    },
    gcTime: 0,
  });
  const data = erd.data;

  // 자동 배치 : 변경 전후를 합친 그래프로 한 번 계산해 양쪽에 같이 쓴다. 결과는 어디에도 저장하지 않는다
  useEffect(() => {
    if (!data) return;
    let cancelled = false;
    const nodes = data.nodes.map((entry) => ({ id: String(entry.node.targetId), width: NODE_WIDTH, height: nodeHeight(rowCount(data.kind, entry)) }));
    void (async () => {
      let positions: Record<string, XY>;
      try {
        positions = await runLayout(nodes, layoutLinks(data), data.kind === 'LOGICAL' ? LAYER_GAP : undefined);
      } catch {
        if (cancelled) return;
        positions = gridLayout(nodes);
      }
      if (!cancelled) setPlaced({ data, positions });
    })();
    return () => {
      cancelled = true;
      cancelLayout();
    };
  }, [data]);
  const positions = placed && placed.data === data ? placed.positions : null;

  const models = useMemo(() => (data ? { before: sideModel(data, 'before'), after: sideModel(data, 'after') } : null), [data]);

  /** 두 화면을 같은 자리로 옮긴다 (노드 하나면 그 노드를 가운데에, 여럿이면 모두 보이게) */
  const show = (nodeIds: number[], duration = 300) => {
    if (!data || !positions) return;
    const entries = data.nodes.filter((entry) => nodeIds.includes(entry.node.targetId) && positions[String(entry.node.targetId)]);
    if (!entries.length) return;
    const boxes = entries.map((entry) => ({ ...positions[String(entry.node.targetId)], height: nodeHeight(rowCount(data.kind, entry)) }));
    const x = Math.min(...boxes.map((box) => box.x)), y = Math.min(...boxes.map((box) => box.y));
    const width = Math.max(...boxes.map((box) => box.x + NODE_WIDTH)) - x, height = Math.max(...boxes.map((box) => box.y + box.height)) - y;
    Object.values(flows.current).forEach((flow) => {
      if (entries.length === 1) void flow.setCenter(x + width / 2, y + height / 2, { zoom: 1, duration });
      else void flow.fitBounds({ x, y, width, height }, { padding: 0.15, duration });
    });
  };
  const showAll = (duration = 300) => show(data?.nodes.map((entry) => entry.node.targetId) ?? [], duration);

  // 배치가 끝나면 : 목록에서 고른 객체가 있으면 그 자리로, 없으면 전체가 보이게
  const shownData = useRef<ErdComparison | null>(null);
  useEffect(() => {
    if (!data || !positions) return;
    const first = shownData.current !== data;
    shownData.current = data;
    if (first) { setSelected(null); setOpenKey(null); }
    const frame = requestAnimationFrame(() => {
      if (!pending) {
        if (first) showAll(0);
        return;
      }
      if (erdKindOfFocus(pending) !== data.kind) return; // 종류를 바꾼 조회가 끝나면 다시 온다
      const hit = locate(data, pending.targetType, pending.targetId);
      if (!hit) {
        // 범위 밖 : 그 객체부터 다시 받는다. 다시 받아도 없으면 (ERD 에 그리지 않는 객체) 알린다
        if (scope?.targetType !== pending.targetType || scope?.targetId !== pending.targetId) {
          setScope(pending);
          return;
        }
        setMessage(t('이 객체는 ERD 에서 찾을 수 없습니다.'));
        if (first) showAll(0);
      } else {
        setMessage('');
        setSelected(hit.selection);
        setOpenKey(changeKey(pending));
        show(hit.nodeIds, first ? 0 : 300);
      }
      setPending(null);
    });
    return () => cancelAnimationFrame(frame);
  }, [data, positions, pending]);

  const select = (selection: Selection | null) => {
    setSelected(selection);
    setOpenKey(null);
  };
  /** 한쪽을 사용자가 움직이면 다른 쪽도 같은 위치로 */
  const syncFrom = (side: Side, viewport: Viewport) => {
    const other = flows.current[side === 'before' ? 'after' : 'before'];
    if (other) void other.setViewport(viewport);
  };

  const changedNodes = useMemo(() => (data?.nodes ?? []).flatMap((entry) => { const change = nodeChange(entry); return change ? [{ entry, change }] : []; }), [data]);
  const changedEdges = useMemo(() => (data?.edges ?? []).filter(isChanged), [data]);
  const sideName = (side: Side) => (side === 'before' ? data?.base.versionName : data?.target.versionName ?? t('현재 모델'));

  return (
    <div className="version-erd-view">
      <div className="version-erd-toolbar">
        <div className="erd-mode" role="group" aria-label={t('ERD 종류')}>
          {KIND_LABELS.map(([value, label]) => (
            <button key={value} type="button" className={`btn btn-sm${kind === value ? ' btn-primary' : ''}`} aria-pressed={kind === value}
              onClick={() => { setKind(value); setScope(null); setPending(null); setMessage(''); }}>{label} ({changedCount(byType, value)})</button>
          ))}
        </div>
        <span className="version-erd-legend" aria-label={t('범례')}>
          {CHANGES.map(([value, label, variant]) => <Label key={value} variant={variant}>{DIFF_MARK[value]} {label}</Label>)}
        </span>
        <Label>{t('읽기 전용')}</Label>
        <button type="button" className="btn btn-sm" disabled={!data?.nodes.length || !positions} onClick={() => showAll()}><ScreenFullIcon />{t('전체 보기')}</button>
        {data && data.nodes.length > 0 && <Muted>{data.truncated
          ? t('변경된 영역의 일부만 표시합니다 ({0}개). 나머지는 변경 목록에서 [ERD 에서 보기] 로 찾아갑니다.', data.nodes.length)
          : t('모델 전체가 아니라 변경된 영역만 표시합니다 ({0}개).', data.nodes.length)}</Muted>}
        {message && <span role="status">{message}</span>}
      </div>

      {erd.isError ? (
        <p className="gh-muted" role="alert">{t('ERD 비교를 불러오지 못했습니다.')}</p>
      ) : !data || !models ? (
        <p className="gh-muted">{t('불러오는 중입니다.')}</p>
      ) : data.nodes.length === 0 ? (
        <p className="gh-muted">{kind === 'PHYSICAL' ? t('두 모델 사이에 달라진 테이블·컬럼·관계가 없습니다.') : t('두 모델 사이에 달라진 엔터티·속성·관계가 없습니다.')}</p>
      ) : (
        <>
          <div className="version-erd">
            <ul className="version-erd-list" aria-label={t('달라진 객체')}>
              {changedNodes.map(({ entry, change }) => (
                <li key={`n${entry.node.targetId}`}>
                  <button type="button" className="history-group-item" aria-current={selected?.type === 'node' && selected.id === entry.node.targetId}
                    onClick={() => { select({ type: 'node', id: entry.node.targetId }); show([entry.node.targetId]); }}>
                    <b className={`version-erd-mark erd-diff-${change.toLowerCase()}`} title={DIFF_LABEL[change]}>{DIFF_MARK[change]}</b>
                    <span className="history-group-name" title={entry.node.name ?? undefined}>{entry.node.name ?? `#${entry.node.targetId}`}</span>
                  </button>
                </li>
              ))}
              {changedEdges.map((edge) => (
                <li key={`e${edge.targetId}`}>
                  <button type="button" className="history-group-item" aria-current={selected?.type === 'edge' && selected.id === edge.targetId}
                    onClick={() => { select({ type: 'edge', id: edge.targetId }); show(edgeEnds(data.kind, edge)); }}>
                    <b className={`version-erd-mark erd-diff-${edge.change.toLowerCase()}`} title={DIFF_LABEL[edge.change]}>{DIFF_MARK[edge.change]}</b>
                    <span className="history-group-name" title={edge.name ?? undefined}>{t('관계')} {edge.name ?? `#${edge.targetId}`}</span>
                  </button>
                </li>
              ))}
            </ul>
            {(['before', 'after'] as const).map((side) => (
              <section className="version-erd-pane" key={side} aria-label={side === 'before' ? t('변경 전') : t('변경 후')}>
                <h5>{side === 'before' ? t('변경 전') : t('변경 후')} <Muted>{sideName(side)}</Muted></h5>
                <div className="erd-canvas">
                  {models[side].nodes.length === 0 && <div className="erd-empty">{side === 'before' ? t('변경 전에는 없던 객체입니다.') : t('변경 후에는 없는 객체입니다.')}</div>}
                  <ReactFlowProvider>
                    <Pane model={models[side]} positions={positions} selected={selected} onSelect={select}
                      onReady={(flow) => { flows.current[side] = flow; }} onMoved={(viewport) => syncFrom(side, viewport)} />
                  </ReactFlowProvider>
                </div>
              </section>
            ))}
            {kind === 'LOGICAL' && <EndMarkers />}
          </div>
          <SelectionDetail data={data} selected={selected} openKey={openKey} onOpen={setOpenKey}
            onEdge={(edge) => { select({ type: 'edge', id: edge.targetId }); show(edgeEnds(data.kind, edge)); }} />
        </>
      )}
    </div>
  );
}

const erdKindOfFocus = (focus: ErdFocus) => erdKindOf(focus.targetType);

/** 한쪽 ERD : 기존 물리·논리 ERD 의 노드·관계선을 그대로 쓰고, 편집과 노드 이동은 모두 끈다 */
function Pane({ model, positions, selected, onSelect, onReady, onMoved }: {
  model: SideModel;
  positions: Record<string, XY> | null;
  selected: Selection | null;
  onSelect: (selection: Selection | null) => void;
  onReady: (flow: ReactFlowInstance) => void;
  onMoved: (viewport: Viewport) => void;
}) {
  const flow = useReactFlow();
  const simple = useSimpleNodes();
  useEffect(() => { onReady(flow as ReactFlowInstance); }, [flow]);

  const selectedNode = selected?.type === 'node' ? String(selected.id) : null;
  const nodes = useMemo<Node[]>(() => (positions ? model.nodes.filter((node) => positions[node.id]).map((node) => ({ ...node, position: positions[node.id], selected: node.id === selectedNode })) : []),
    [model, positions, selectedNode]);

  const edges = useMemo<Edge[]>(() => {
    if (!positions) return [];
    // 달라진 관계는 변경 색과 글자로, 달라지지 않은 관계는 고른 것만 굵게 (수정 색과 헷갈리지 않게 글자색으로) 그린다
    const look = (id: number, source: number, target: number) => {
      const change = model.edgeChange.get(id);
      const highlighted = (selected?.type === 'edge' && selected.id === id) || selectedNode === String(source) || selectedNode === String(target);
      return { change, highlighted: highlighted || !!change, stroke: change ? DIFF_COLOR[change] : highlighted ? 'var(--gh-fg)' : 'var(--gh-fg-muted)' };
    };
    if (model.kind === 'PHYSICAL') {
      return relationEdges(model.columns, model.relations, simple, (relation) => {
        const { change, ...rest } = look(relation.id, relation.sourceTableId, relation.targetTableId);
        return { ...rest, label: edgeTag(change) };
      });
    }
    return relationshipEdges(model.relationships, (relationship) => {
      const { change, ...rest } = look(relationship.id, relationship.sourceEntityId, relationship.targetEntityId);
      return { ...rest, tag: edgeTag(change) };
    });
  }, [model, positions, simple, selected, selectedNode]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={NODE_TYPES}
      edgeTypes={edgeTypes}
      onNodeClick={(_, node) => onSelect({ type: 'node', id: Number(node.id) })}
      onEdgeClick={(_, edge) => {
        const item = edge.data as { relation?: { id: number }; relationship?: { id: number } } | undefined;
        const id = item?.relation?.id ?? item?.relationship?.id;
        if (id != null) onSelect({ type: 'edge', id });
      }}
      onPaneClick={() => onSelect(null)}
      // 사용자가 움직인 것만 다른 쪽에 전한다 (프로그램이 옮긴 것은 event 가 없다 → 서로 되돌려 보내지 않는다)
      onMove={(event, viewport) => { if (event) onMoved(viewport); }}
      // 읽기 전용 : 노드 이동·연결·삭제 없음. 확대·축소·이동만 된다
      nodesDraggable={false}
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
  );
}

/** 고른 테이블·엔터티·관계의 달라진 내용 : 버전 비교 목록과 같은 줄·같은 변경 전후 표 */
function SelectionDetail({ data, selected, openKey, onOpen, onEdge }: {
  data: ErdComparison;
  selected: Selection | null;
  openKey: string | null;
  onOpen: (key: string | null) => void;
  onEdge: (edge: ErdObject) => void;
}) {
  if (!selected) return <p className="gh-muted version-erd-detail">{t('테이블이나 관계선을 누르면 달라진 내용을 볼 수 있습니다.')}</p>;

  let title: string;
  let changes: ErdObject[];
  let related: ErdObject[] = [];
  if (selected.type === 'edge') {
    const edge = data.edges.find((item) => item.targetId === selected.id);
    if (!edge) return null;
    title = `${versionTypeLabel(edge.targetType)} · ${edge.name ?? `#${edge.targetId}`}`;
    changes = [edge];
  } else {
    const entry = data.nodes.find((item) => item.node.targetId === selected.id);
    if (!entry) return null;
    title = `${versionTypeLabel(entry.node.targetType)} · ${entry.node.name ?? `#${entry.node.targetId}`}`;
    changes = [entry.node, ...entry.items.filter(rowChange), ...entry.extras];
    related = data.edges.filter((edge) => isChanged(edge) && edgeEnds(data.kind, edge).includes(selected.id));
  }
  const shown = changes.filter(isChanged) as VersionChange[];

  return (
    <div className="version-erd-detail">
      <div className="history-diff-head">
        <h4>{title} <Muted>{t('달라진 항목 {0}건', shown.length + related.length)}</Muted></h4>
      </div>
      {shown.length + related.length === 0 ? (
        <p className="gh-muted">{t('달라진 것이 없습니다. 달라진 객체와 관계로 이어져 함께 표시합니다.')}</p>
      ) : (
        <ol className="history-group-list version-list">
          {shown.map((change) => (
            <ChangeItem key={changeKey(change)} change={change} open={openKey === changeKey(change) || shown.length + related.length === 1}
              onToggle={() => onOpen(openKey === changeKey(change) ? null : changeKey(change))} />
          ))}
          {related.map((edge) => (
            <li key={changeKey(edge)}>
              <button type="button" className="history-group-item" onClick={() => onEdge(edge)}>
                <Label variant={CHANGES.find(([value]) => value === edge.change)?.[2] ?? 'accent'}>{DIFF_LABEL[edge.change as DiffKind]}</Label>
                <span>{versionTypeLabel(edge.targetType)}</span>
                <span className="history-group-name">{edge.name ?? `#${edge.targetId}`}</span>
                <Muted>{t('관계 보기')}</Muted>
              </button>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
