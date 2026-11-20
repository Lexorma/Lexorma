import { memo } from 'react';
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, Handle, Position, type Edge, type EdgeProps, type Node, type NodeProps } from '@xyflow/react';
import { t } from '../../lib/i18n';
import { diffClass, HEADER_HEIGHT, MAX_ROWS, NODE_WIDTH, nodeHeight, type NodeDiff, type XY } from '../erd/model';
import { DiffBadge, DiffMark, useSimpleNodes } from '../erd/TableNode';
import { centered, endShift, relationLanes, type Lane } from '../erd/relationLanes';
import { EndMarkerDefs } from '../erd/endMarkers';
import { participation, type GraphEntity, type GraphSubtypeGroup, type LogicalRelationship } from './model';
import { completeness, disjointness, disjointSymbol } from './subtype';

/** 논리 ERD 의 노드·관계선 (논리 ERD 화면과 버전 비교 ERD 가 같이 쓴다) */

/** connectable : 편집 모드에서만 연결점을 끌어 관계 등록을 시작할 수 있다. diff : 버전 비교 ERD 의 변경 표시 */
export type EntityNodeType = Node<{ entity: GraphEntity; connectable?: boolean; diff?: NodeDiff }, 'entity'>;
/** sourceSlot·targetSlot : 같은 연결점을 쓰는 관계 중 몇 번째인지 (참여 수 글자가 겹치지 않게 옆으로 민다) */
/** lane : 같은 두 엔터티(자기 참조는 한 엔터티) 사이의 관계 중 몇 번째인지·모두 몇 개인지 — 관계 ID 순 (M-6 후속 2차, erd/relationLanes) */
/** tag : 관계명 자리에 함께 적는 말 (버전 비교 ERD 의 추가·삭제·수정) */
export type RelationshipEdgeType = Edge<{ relationship: LogicalRelationship; selected: boolean; sourceSlot: number; targetSlot: number; lane: Lane; tag?: string }, 'relationship'>;

/** 연결점 : 왼쪽(들어옴) / 오른쪽(나감) / 자기 참조는 오른쪽 위에서 나가 오른쪽 아래로 들어온다. 상속선(M-6 후속 5차)은 노드 가운데의 보이지 않는 점 — 선이 노드 밑으로 들어가 테두리에서 시작해 보인다 */
const HANDLE = { in: 'in', out: 'out', selfOut: 'self-out', self: 'self', inheritOut: 'inherit-out', inheritIn: 'inherit-in' } as const;
const CENTER_HANDLE = { top: '50%', left: '50%', opacity: 0, pointerEvents: 'none' } as const;

/** 노드의 행 수 : 상속 주 식별자 + 직접 속성 (높이 계산 — 배치·포커스·내보내기가 같은 값을 쓴다) */
export const entityRows = (entity: GraphEntity) => entity.attributes.length + (entity.inheritedKey?.length ?? 0);
const SLOT_WIDTH = 34;
/** 평행 관계선 : 끝점은 헤더 안에서 대칭으로 벌리고(간격 8, 연결점 기준 ±20 — 헤더 높이 52 안), 꺾이는 세로 구간과 돌아 나가는 폭도 차선마다 달리한다 */
const LANE_END_SPACING = 8;
const LANE_END_LIMIT = 20;
const LANE_CENTER_SPACING = 18;
const LANE_OFFSET = 20;
const LANE_OFFSET_STEP = 10;
/** 자기 참조 고리 : 관계마다 바깥으로 14씩 더 크게 (안쪽 고리부터 ID 순) */
const SELF_LOOP_STEP = 14;
/** 엔터티 사이 간격 : 양 끝 참여 수와 관계명이 들어갈 자리 */
export const LAYER_GAP = 260;

/** 엔터티 노드 : 속성 앞에 식별자 표시(PI 주 식별자 / AI 보조 식별자), 뒤에 필수(*) */
const EntityNode = memo(function EntityNode({ data, selected }: NodeProps<EntityNodeType>) {
  const simple = useSimpleNodes();
  const { entity, diff } = data;
  const connectable = !!data.connectable;
  const inheritedKey = entity.inheritedKey ?? [];
  const rows = entityRows(entity);
  return (
    <div className={`erd-table${selected ? ' selected' : ''}${diffClass(diff?.change)}${entity.supertypeEntityId != null ? ' logical-subtype-node' : ''}`} style={{ width: NODE_WIDTH, height: nodeHeight(rows) }}>
      <header title={entity.name}>
        <DiffBadge diff={diff} />
        <strong className="logical-entity-name">{entity.name}</strong>
        <span>{diff?.caption ? `${diff.caption} · ` : ''}{entity.description || t('설명 없음')}{entity.isApproval ? '' : t(' · 승인 대기')}</span>
      </header>
      {/* 서브타입 : 상속 주 식별자를 맨 위에 "상속" 표시와 함께 (색만으로 구분하지 않는다). 다른 상속 속성은 상세에서 본다 */}
      {!simple && inheritedKey.slice(0, MAX_ROWS).map((k) => (
        <div className="erd-column logical-inherited-key" key={`k${k.attributeId}`} title={t('상속 주 식별자 · 정의 : {0}', k.definedEntityName ?? '')}>
          <b>PI</b>
          <span className="logical-attribute-name">{k.name}</span>
          <small className="logical-inherited-mark">{t('상속')}</small>
          <small>*</small>
        </div>
      ))}
      {!simple && entity.attributes.slice(0, Math.max(0, MAX_ROWS - inheritedKey.length)).map((a) => (
        <div className={`erd-column${diffClass(diff?.rows.get(a.id))}`} key={a.id} title={[a.name, a.termName, a.domainName].filter(Boolean).join(' · ')}>
          <b>{a.primaryIdentifier ? 'PI' : a.identifier ? 'AI' : ''}</b>
          <span className="logical-attribute-name"><DiffMark change={diff?.rows.get(a.id)} />{a.name}</span>
          <small>{a.domainName || a.dataType || ''}</small>
          <small>{a.isRequired || a.primaryIdentifier ? '*' : ''}</small>
        </div>
      ))}
      {!simple && rows > MAX_ROWS && <div className="erd-column-more">{t('외 {0}개 속성 · 상세 패널에서 확인', rows - MAX_ROWS)}</div>}
      {/* 편집 모드 : 오른쪽 연결점(출발)을 끌어 다른 엔터티(또는 자기 자신)의 왼쪽 연결점(도착)에 놓으면 관계 등록 대화상자가 열린다 */}
      <Handle type="target" id={HANDLE.in} position={Position.Left} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={connectable} />
      <Handle type="source" id={HANDLE.out} position={Position.Right} style={{ top: HEADER_HEIGHT / 2 }} isConnectable={connectable} />
      <Handle type="source" id={HANDLE.selfOut} position={Position.Right} style={{ top: 8 }} isConnectable={false} />
      <Handle type="target" id={HANDLE.self} position={Position.Right} style={{ top: HEADER_HEIGHT - 8 }} isConnectable={false} />
      <Handle type="source" id={HANDLE.inheritOut} position={Position.Top} style={CENTER_HANDLE} isConnectable={false} />
      <Handle type="target" id={HANDLE.inheritIn} position={Position.Top} style={CENTER_HANDLE} isConnectable={false} />
    </div>
  );
});

// ------------------------------------------------------------------ 슈퍼타입·서브타입 (M-6 후속 5차, EER 표기)

/** 그룹 원의 지름 */
const GROUP_SIZE = 28;
/** 그룹 원은 실제 엔터티가 아니다 : 화면에서만 계산하고 배치에 저장하지 않는다. ID 는 그룹 ID 로 만든다 (엔터티 ID 와 겹치지 않는 접두어) */
export const groupNodeId = (groupId: number) => `g${groupId}`;
export type SubtypeGroupNodeType = Node<{ group: GraphSubtypeGroup }, 'subtypeGroup'>;
export type InheritanceEdgeType = Edge<{ total: boolean; value?: string | null; toGroup: boolean }, 'inheritance'>;

/** 그룹 원 : 원 안의 D(배타) / O(중복 가능). 선택·삭제·관계 연결 대상이 아니다 */
const SubtypeGroupNode = memo(function SubtypeGroupNode({ data }: NodeProps<SubtypeGroupNodeType>) {
  const g = data.group;
  const label = t('서브타입 그룹 {0} · {1} · {2} · 서브타입 {3}개', g.groupName, disjointness(g.overlapping), completeness(g.total), g.subtypeCount);
  return (
    <div className="logical-subtype-group" role="img" aria-label={label} title={g.classificationAttributeName ? `${label} · ${t('분류 속성 {0}', g.classificationAttributeName)}` : label}
      style={{ width: GROUP_SIZE, height: GROUP_SIZE }}>
      {disjointSymbol(g.overlapping)}
      <Handle type="target" id={HANDLE.inheritIn} position={Position.Top} style={CENTER_HANDLE} isConnectable={false} />
      <Handle type="source" id={HANDLE.inheritOut} position={Position.Top} style={CENTER_HANDLE} isConnectable={false} />
    </div>
  );
});

/** 상속선 : 슈퍼타입 → 그룹 원은 완전(Total)이면 이중선, 부분(Partial)이면 단선. 그룹 원 → 서브타입은 단선 (끝 기호 없음 — Crow's Foot 을 쓰지 않는다) */
function InheritanceEdge({ id, sourceX, sourceY, targetX, targetY, data }: EdgeProps<InheritanceEdgeType>) {
  const path = `M ${sourceX} ${sourceY} L ${targetX} ${targetY}`;
  const double = !!data?.toGroup && !!data.total;
  return (
    <>
      {double ? (
        <>
          <BaseEdge id={id} path={path} style={{ stroke: 'var(--logical-inherit)', strokeWidth: 6 }} interactionWidth={0} />
          <BaseEdge id={`${id}-inner`} path={path} style={{ stroke: 'var(--gh-canvas)', strokeWidth: 2 }} interactionWidth={0} />
        </>
      ) : (
        <BaseEdge id={id} path={path} style={{ stroke: 'var(--logical-inherit)', strokeWidth: 1.5 }} interactionWidth={0} />
      )}
      {data?.value && (
        <EdgeLabelRenderer>
          <span className="logical-edge-label logical-inherit-value" style={{ transform: `translate(-50%, -50%) translate(${(sourceX + targetX) / 2}px, ${(sourceY + targetY) / 2}px)` }}>{data.value}</span>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

/**
 * 범위 안 그룹 → 그룹 원 노드와 상속선. 원의 자리는 슈퍼타입 가운데와 (범위 안) 서브타입들의 가운데 사이 — 엔터티를 옮기면 다시 계산한다 (저장하지 않는다).
 * centers : 엔터티 노드 ID → 가운데 좌표 (화면에 없는 엔터티는 그리지 않는다)
 */
export function subtypeElements(groups: GraphSubtypeGroup[], centers: Map<string, XY>): { nodes: SubtypeGroupNodeType[]; edges: InheritanceEdgeType[] } {
  const nodes: SubtypeGroupNodeType[] = [];
  const edges: InheritanceEdgeType[] = [];
  const perSupertype = new Map<number, number>();
  for (const g of groups) {
    const superCenter = centers.get(String(g.supertypeEntityId));
    const members = g.subtypes.filter((m) => centers.has(String(m.entityId)));
    if (!superCenter || !members.length) continue;
    const mx = members.reduce((sum, m) => sum + centers.get(String(m.entityId))!.x, 0) / members.length;
    const my = members.reduce((sum, m) => sum + centers.get(String(m.entityId))!.y, 0) / members.length;
    // 같은 슈퍼타입의 그룹이 여럿이면 원이 겹치지 않게 순서대로 비켜 놓는다
    const index = perSupertype.get(g.supertypeEntityId) ?? 0;
    perSupertype.set(g.supertypeEntityId, index + 1);
    const id = groupNodeId(g.id);
    // 크기를 정해 둔다 : 화면에서 계산한 노드라 측정 결과를 상태에 넣지 않으므로, 정하지 않으면 React Flow 가 숨기고 선도 잇지 못한다
    nodes.push({ id, type: 'subtypeGroup', position: { x: (superCenter.x + mx) / 2 - GROUP_SIZE / 2, y: (superCenter.y + my) / 2 - GROUP_SIZE / 2 + index * (GROUP_SIZE + 12) },
      width: GROUP_SIZE, height: GROUP_SIZE, measured: { width: GROUP_SIZE, height: GROUP_SIZE }, data: { group: g },
      draggable: false, selectable: false, connectable: false, zIndex: 2 });
    edges.push({ id: `i${g.id}`, type: 'inheritance', source: String(g.supertypeEntityId), target: id, sourceHandle: HANDLE.inheritOut, targetHandle: HANDLE.inheritIn,
      data: { total: g.total, toGroup: true }, zIndex: -1, selectable: false, focusable: false });
    members.forEach((m) => edges.push({ id: `i${g.id}-${m.entityId}`, type: 'inheritance', source: id, target: String(m.entityId), sourceHandle: HANDLE.inheritOut,
      targetHandle: HANDLE.inheritIn, data: { total: g.total, toGroup: false, value: m.classificationValue }, zIndex: -1, selectable: false, focusable: false }));
  }
  return { nodes, edges };
}

/** 관계선 끝 표기 (Crow's Foot) : 참여 수의 최소(0 = 선택 ○ / 1 이상 = 필수 |)와 최대(1 = | / 여러 건 = 까치발). 표시일 뿐 데이터의 뜻은 옆의 참여 수 글자와 같다 */
const END_MARKERS = ['one', 'zero-one', 'one-many', 'zero-many'] as const;
const endMarker = (min: number, max: number | null) => `logical-end-${max === 1 ? (min === 0 ? 'zero-one' : 'one') : (min === 0 ? 'zero-many' : 'one-many')}`;

/** 관계선 끝 표기 정의 (erd/endMarkers 의 공용 정의, id = logical-end-…) */
export function EndMarkers() {
  return <EndMarkerDefs prefix="logical-end" kinds={END_MARKERS} className="logical-end-markers" />;
}

/**
 * 관계선 : 각 엔터티 옆에 **그 엔터티 쪽의 참여 수**를 적고(출발 쪽 = sourceMin..sourceMax, 도착 쪽 = targetMin..targetMax) 선 끝에 같은 뜻의 표기를 그린다.
 * 실선 = 식별 관계, 점선 = 비식별 관계. 같은 두 엔터티 사이의 관계는 차선마다 끝점·꺾이는 세로 구간·돌아 나가는 폭을 달리해 기호와 경로가 겹치지 않게 한다.
 * 자기 참조는 엔터티 오른쪽 바깥으로 도는 고리이며, 여러 개면 관계마다 고리를 더 크게 그린다 (경로는 노드 좌표에서 매번 계산 — 노드를 옮기면 따라온다)
 */
function RelationshipEdge({ id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data, markerStart, markerEnd }: EdgeProps<RelationshipEdgeType>) {
  const lane = data?.lane ?? { index: 0, count: 1 };
  const self = !!data && data.relationship.sourceEntityId === data.relationship.targetEntityId;
  const shift = self ? 0 : endShift(lane, LANE_END_SPACING, LANE_END_LIMIT);
  sourceY += shift;
  targetY += shift;
  const [path, labelX, labelY] = getSmoothStepPath(self
    ? { sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, offset: LANE_OFFSET + lane.index * SELF_LOOP_STEP }
    : { sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, offset: LANE_OFFSET + lane.index * LANE_OFFSET_STEP,
      centerX: (sourceX + targetX) / 2 + centered(lane) * LANE_CENTER_SPACING });
  if (!data) return <BaseEdge id={id} path={path} style={style} />;
  const r = data.relationship;
  // 도착 연결점이 오른쪽이면(자기 참조) 글자를 오른쪽에, 보통은 엔터티 왼쪽 바깥에 둔다
  const targetRight = targetPosition === Position.Right;
  return (
    <>
      {/* 고르는 영역 : 선 양쪽 6px (평행 차선 간격보다 좁게 — 옆 관계를 대신 고르지 않는다) */}
      <BaseEdge id={id} path={path} style={style} markerStart={markerStart} markerEnd={markerEnd} interactionWidth={12} />
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

export const nodeTypes = { entity: EntityNode, subtypeGroup: SubtypeGroupNode };
export const edgeTypes = { relationship: RelationshipEdge, inheritance: InheritanceEdge };

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
  // 관계 ID 순으로 차선을 한 번에 정한다 : 다시 조회·범위를 바꿔도 같은 묶음이면 같은 자리 (응답 순서에 기대지 않는다)
  const sorted = [...relationships].sort((a, b) => a.id - b.id);
  const lanes = relationLanes(sorted, (r) => r.id, (r) => [r.sourceEntityId, r.targetEntityId]);
  return sorted.map((relationship) => {
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
        relationship, selected: highlighted, tag, lane: lanes.get(relationship.id) ?? { index: 0, count: 1 },
        sourceSlot: slot(`${relationship.sourceEntityId}:${sourceHandle}`), targetSlot: slot(`${relationship.targetEntityId}:${targetHandle}`),
      },
    };
  });
}
