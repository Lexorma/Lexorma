import { columnHandle, TABLE_HANDLE } from './TableNode';
import { childEnd, parentEnd, relationTooltip } from './cardinality';
import { relationLanes } from './relationLanes';
import { columnsByTable, MAX_ROWS, type Cardinality, type ErdColumn, type ErdRelation, type RelationEdge } from './model';

/** 관계선 하나의 모양 : 색, 강조(굵게·위에 그림), 선 위 글자 (버전 비교 ERD 의 추가·삭제·수정) */
export interface EdgeLook {
  stroke: string;
  highlighted: boolean;
  label?: string;
}

/**
 * 물리 ERD 의 관계선 (물리 ERD 화면과 버전 비교 ERD 가 같이 쓴다, 선 모양은 FkEdge) : FK 제약조건 하나 = 선 하나 (M-2 개선 2차 D-07 — 예전에는 컬럼 쌍마다 그렸다).
 * 대표 연결점 = 첫 컬럼 쌍 (columnOrder 순)의 컬럼 연결점, 그 컬럼이 안 보이면(축소·20개 밖) 테이블 연결점. 나머지 쌍은 관계 상세·툴팁에 있다. 점선 = 논리 관계.
 * 차선은 관계 ID 순이라 다시 조회해도 같은 선이 같은 자리에 온다. cardinalities 가 있으면 끝 기호를 붙인다 (버전 비교 ERD 는 주지 않는다 — 과거 버전을 지금 제약조건으로 판단하지 않는다)
 */
export function relationEdges(columns: ErdColumn[], relations: ErdRelation[], simple: boolean, look: (relation: ErdRelation) => EdgeLook,
  cardinalities?: Record<string, Cardinality>): RelationEdge[] {
  const rowIndex = new Map<number, number>();
  for (const group of columnsByTable(columns).values()) group.forEach((c, i) => rowIndex.set(c.id, i));
  const shown = (columnId: number) => !simple && (rowIndex.get(columnId) ?? MAX_ROWS) < MAX_ROWS;
  const sorted = [...relations].sort((a, b) => a.id - b.id);
  const lanes = relationLanes(sorted, (r) => r.id, (r) => [r.sourceTableId, r.targetTableId]);

  return sorted.map((relation) => {
    const self = relation.sourceTableId === relation.targetTableId;
    const { stroke, highlighted, label } = look(relation);
    const cardinality = cardinalities?.[String(relation.id)];
    const first = relation.columns[0];
    const columnEnds = !!first && shown(first.sourceColumnId) && shown(first.targetColumnId);
    const text = label ? { label, labelStyle: { fill: stroke, fontWeight: 600 }, labelBgStyle: { fill: 'var(--gh-canvas)' }, labelBgPadding: [4, 2] as [number, number], labelBgBorderRadius: 3 } : {};
    return {
      ...text,
      id: `r${relation.id}`,
      type: 'fk' as const,
      source: String(relation.sourceTableId),
      target: String(relation.targetTableId),
      sourceHandle: columnEnds ? columnHandle(first.sourceColumnId, 'out') : TABLE_HANDLE.out,
      targetHandle: columnEnds ? columnHandle(first.targetColumnId, self ? 'self' : 'in') : self ? TABLE_HANDLE.self : TABLE_HANDLE.in,
      style: { stroke, strokeDasharray: relation.relationType === 'LOGICAL' ? '6 4' : undefined, strokeWidth: highlighted ? 2.5 : 1.25 },
      zIndex: highlighted ? 1 : 0,
      markerStart: childEnd(cardinality),
      markerEnd: parentEnd(cardinality),
      data: { relation, lane: lanes.get(relation.id) ?? { index: 0, count: 1 }, tableEnds: !columnEnds, tooltip: relationTooltip(relation, cardinality) },
    };
  });
}
