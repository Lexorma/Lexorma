import { memo } from 'react';
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, Handle, Position, type Edge, type EdgeProps, type Node, type NodeProps } from '@xyflow/react';
import { t } from '../../lib/i18n';
import { diffClass, HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight, type NodeDiff } from '../erd/model';
import { DiffBadge, DiffMark, useSimpleNodes } from '../erd/TableNode';
import { participation, type GraphEntity, type LogicalRelationship } from './model';

/** 논리 ERD 의 노드·관계선 (논리 ERD 화면과 버전 비교 ERD 가 같이 쓴다) */

/** connectable : 편집 모드에서만 연결점을 끌어 관계 등록을 시작할 수 있다. diff : 버전 비교 ERD 의 변경 표시 */
export type EntityNodeType = Node<{ entity: GraphEntity; connectable?: boolean; diff?: NodeDiff }, 'entity'>;
/** sourceSlot·targetSlot : 같은 연결점을 쓰는 관계 중 몇 번째인지 (참여 수 글자가 겹치지 않게 옆으로 민다) */
/** parallel : 같은 두 엔터티 사이의 관계 중 몇 번째인지 (선이 겹쳐 고를 수 없게 되지 않도록 조금씩 띄운다) */
/** tag : 관계명 자리에 함께 적는 말 (버전 비교 ERD 의 추가·삭제·수정) */
export type RelationshipEdgeType = Edge<{ relationship: LogicalRelationship; selected: boolean; sourceSlot: number; targetSlot: number; parallel: number; tag?: string }, 'relationship'>;

/** 연결점 : 왼쪽(들어옴) / 오른쪽(나감) / 자기 참조는 오른쪽 위에서 나가 오른쪽 아래로 들어온다 */
const HANDLE = { in: 'in', out: 'out', selfOut: 'self-out', self: 'self' } as const;
const SLOT_WIDTH = 34;
/** 엔터티 사이 간격 : 양 끝 참여 수와 관계명이 들어갈 자리 */
export const LAYER_GAP = 260;

/** 엔터티 노드 : 속성 앞에 식별자 표시(PI 주 식별자 / AI 보조 식별자), 뒤에 필수(*) */
const EntityNode = memo(function EntityNode({ data, selected }: NodeProps<EntityNodeType>) {
  const simple = useSimpleNodes();
  const { entity, diff } = data;
  const connectable = !!data.connectable;
  return (
    <div className={`erd-table${selected ? ' selected' : ''}${diffClass(diff?.change)}`} style={{ width: NODE_WIDTH, height: nodeHeight(entity.attributes.length) }}>
      <header title={entity.name}>
        <DiffBadge diff={diff} />
        <strong className="logical-entity-name">{entity.name}</strong>
        <span>{diff?.caption ? `${diff.caption} · ` : ''}{entity.description || t('설명 없음')}{entity.isApproval ? '' : t(' · 승인 대기')}</span>
      </header>
      {!simple && entity.attributes.slice(0, MAX_ROWS).map((a) => (
        <div className={`erd-column${diffClass(diff?.rows.get(a.id))}`} key={a.id} title={[a.name, a.termName, a.domainName].filter(Boolean).join(' · ')}>
          <b>{a.primaryIdentifier ? 'PI' : a.identifier ? 'AI' : ''}</b>
          <span className="logical-attribute-name"><DiffMark change={diff?.rows.get(a.id)} />{a.name}</span>
          <small>{a.domainName || a.dataType || ''}</small>
          <small>{a.isRequired || a.primaryIdentifier ? '*' : ''}</small>
        </div>
      ))}
      {!simple && entity.attributes.length > MAX_ROWS && <div className="erd-column-more">{t('외 {0}개 속성 · 상세 패널에서 확인', entity.attributes.length - MAX_ROWS)}</div>}
      {/* 편집 모드 : 오른쪽 연결점(출발)을 끌어 다른 엔터티(또는 자기 자신)의 왼쪽 연결점(도착)에 놓으면 관계 등록 대화상자가 열린다 */}
      <Handle type="target" id={HANDLE.in} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={connectable} />
      <Handle type="source" id={HANDLE.out} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={connectable} />
      <Handle type="source" id={HANDLE.selfOut} position={Position.Right} style={{ top: 8 }} isConnectable={false} />
      <Handle type="target" id={HANDLE.self} position={Position.Right} style={{ top: HEADER_HEIGHT - 8 }} isConnectable={false} />
    </div>
  );
});

/** 관계선 끝 표기 (Crow's Foot) : 참여 수의 최소(0 = 선택 ○ / 1 이상 = 필수 |)와 최대(1 = | / 여러 건 = 까치발). 표시일 뿐 데이터의 뜻은 옆의 참여 수 글자와 같다 */
const END_MARKERS = ['one', 'zero-one', 'one-many', 'zero-many'] as const;
const endMarker = (min: number, max: number | null) => `logical-end-${max === 1 ? (min === 0 ? 'zero-one' : 'one') : (min === 0 ? 'zero-many' : 'one-many')}`;

/** 관계선 끝 표기 정의. 선과 같은 색(context-stroke)으로 그리고, 시작 쪽은 뒤집어 쓴다 (auto-start-reverse) */
export function EndMarkers() {
  return (
    <svg className="logical-end-markers" aria-hidden>
      <defs>
        {END_MARKERS.map((kind) => (
          <marker key={kind} id={`logical-end-${kind}`} viewBox="0 0 24 16" markerWidth="24" markerHeight="16" refX="24" refY="8" markerUnits="userSpaceOnUse" orient="auto-start-reverse">
            <g fill="none" stroke="context-stroke" strokeWidth="1.5" strokeDasharray="none">
              {kind.endsWith('many') ? <path d="M12 8 L24 2 M12 8 L24 14" /> : <path d="M19 2 V14" />}
              {kind.startsWith('zero') ? <circle cx={kind.endsWith('many') ? 7 : 10} cy="8" r="3.5" fill="var(--gh-canvas)" /> : <path d={kind.endsWith('many') ? 'M9 2 V14' : 'M14 2 V14'} />}
            </g>
          </marker>
        ))}
      </defs>
    </svg>
  );
}

/**
 * 관계선 : 각 엔터티 옆에 **그 엔터티 쪽의 참여 수**를 적고(출발 쪽 = sourceMin..sourceMax, 도착 쪽 = targetMin..targetMax) 선 끝에 같은 뜻의 표기를 그린다.
 * 실선 = 식별 관계, 점선 = 비식별 관계. 같은 두 엔터티 사이의 관계는 parallel 만큼 띄워 그린다.
 */
function RelationshipEdge({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data, markerStart, markerEnd }: EdgeProps<RelationshipEdgeType>) {
  const shift = (data?.parallel ?? 0) * 16;
  const [path, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY: sourceY + shift, targetX, targetY: targetY + shift, sourcePosition, targetPosition });
  if (!data) return <BaseEdge id={id} path={path} style={style} />;
  const r = data.relationship;
  // 도착 연결점이 오른쪽이면(자기 참조) 글자를 오른쪽에, 보통은 엔터티 왼쪽 바깥에 둔다
  const targetRight = targetPosition === Position.Right;
  return (
    <>
      <BaseEdge id={id} path={path} style={style} markerStart={markerStart} markerEnd={markerEnd} interactionWidth={16} />
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
        {(r.relationshipName || data.tag) && (
          <span className={`logical-edge-label logical-edge-name${data.selected ? ' selected' : ''}`} style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY + data.sourceSlot * 20}px)`, ...(data.tag ? { borderColor: style?.stroke, color: style?.stroke, fontWeight: 600 } : {}) }}>
            {[data.tag, r.relationshipName].filter(Boolean).join(' ')}
          </span>
        )}
      </EdgeLabelRenderer>
    </>
  );
}

export const nodeTypes = { entity: EntityNode };
export const edgeTypes = { relationship: RelationshipEdge };

/**
 * 관계선 만들기 : 실선 = 식별, 점선 = 비식별. 선 끝 표기는 출발 쪽 = 출발 엔터티의 참여 수, 도착 쪽 = 도착 엔터티의 참여 수.
 * look : 관계마다 색, 강조(굵게·다른 선 위에 그림), 관계명 자리에 함께 적는 말
 */
export function relationshipEdges(relationships: LogicalRelationship[], look: (relationship: LogicalRelationship) => { stroke: string; highlighted: boolean; tag?: string }): RelationshipEdgeType[] {
  const used = new Map<string, number>();
  const slot = (key: string) => {
    const index = used.get(key) ?? 0;
    used.set(key, index + 1);
    return index;
  };
  const pair = (r: LogicalRelationship) => `pair:${Math.min(r.sourceEntityId, r.targetEntityId)}:${Math.max(r.sourceEntityId, r.targetEntityId)}`;
  return relationships.map((relationship) => {
    const self = relationship.sourceEntityId === relationship.targetEntityId;
    const sourceHandle = self ? HANDLE.selfOut : HANDLE.out;
    const targetHandle = self ? HANDLE.self : HANDLE.in;
    const { stroke, highlighted, tag } = look(relationship);
    return {
      id: `r${relationship.id}`,
      type: 'relationship',
      source: String(relationship.sourceEntityId),
      target: String(relationship.targetEntityId),
      sourceHandle,
      targetHandle,
      style: { stroke, strokeDasharray: relationship.isIdentifying ? undefined : '6 4', strokeWidth: highlighted ? 2.5 : 1.25 },
      zIndex: highlighted ? 1 : 0,
      markerStart: endMarker(relationship.sourceMin, relationship.sourceMax),
      markerEnd: endMarker(relationship.targetMin, relationship.targetMax),
      data: {
        relationship, selected: highlighted, tag, parallel: slot(pair(relationship)),
        sourceSlot: slot(`${relationship.sourceEntityId}:${sourceHandle}`), targetSlot: slot(`${relationship.targetEntityId}:${targetHandle}`),
      },
    };
  });
}
