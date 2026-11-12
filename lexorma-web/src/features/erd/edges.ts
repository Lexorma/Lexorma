import { columnHandle, TABLE_HANDLE } from './TableNode';
import { columnsByTable, MAX_ROWS, type ErdColumn, type ErdRelation, type RelationEdge } from './model';

/** 관계선 하나의 모양 : 색, 강조(굵게·위에 그림), 선 위 글자 (버전 비교 ERD 의 추가·삭제·수정) */
export interface EdgeLook {
  stroke: string;
  highlighted: boolean;
  label?: string;
}

/**
 * 물리 ERD 의 관계선 (물리 ERD 화면과 버전 비교 ERD 가 같이 쓴다) :
 * 컬럼이 보이면 컬럼 쌍마다 컬럼 연결점에, 축소 상태·생략된 컬럼은 테이블 연결점에 (관계당 1개). 점선 = 논리 관계
 */
export function relationEdges(columns: ErdColumn[], relations: ErdRelation[], simple: boolean, look: (relation: ErdRelation) => EdgeLook): RelationEdge[] {
  const rowIndex = new Map<number, number>();
  for (const group of columnsByTable(columns).values()) group.forEach((c, i) => rowIndex.set(c.id, i));
  const shown = (columnId: number) => !simple && (rowIndex.get(columnId) ?? MAX_ROWS) < MAX_ROWS;

  // 같은 두 테이블 사이의 관계가 테이블 연결점으로 모이면 선이 완전히 겹쳐 하나만 고를 수 있다 → 꺾이는 위치를 관계마다 달리한다
  const parallel = new Map<string, number>();
  const stepPosition = (relation: ErdRelation) => {
    const key = `${Math.min(relation.sourceTableId, relation.targetTableId)}:${Math.max(relation.sourceTableId, relation.targetTableId)}`;
    const index = parallel.get(key) ?? 0;
    parallel.set(key, index + 1);
    // 0.5(가운데) → 0.35 → 0.65 → 0.2 → 0.8 …  (0.1 ~ 0.9 안에서)
    const shift = Math.ceil(index / 2) * 0.15 * (index % 2 === 1 ? -1 : 1);
    return Math.min(0.9, Math.max(0.1, 0.5 + shift));
  };

  return relations.flatMap((relation) => {
    const self = relation.sourceTableId === relation.targetTableId;
    const { stroke, highlighted, label } = look(relation);
    const style = {
      stroke,
      strokeDasharray: relation.relationType === 'LOGICAL' ? '6 4' : undefined,
      strokeWidth: highlighted ? 2.5 : 1.25,
    };
    const text = label ? { label, labelStyle: { fill: stroke, fontWeight: 600 }, labelBgStyle: { fill: 'var(--gh-canvas)' }, labelBgPadding: [4, 2] as [number, number], labelBgBorderRadius: 3 } : {};
    const base = { source: String(relation.sourceTableId), target: String(relation.targetTableId), type: 'smoothstep', style, zIndex: highlighted ? 1 : 0, data: { relation } };
    const pairs = relation.columns.filter((p) => shown(p.sourceColumnId) && shown(p.targetColumnId));
    const tableEdge = { ...base, ...text, id: `r${relation.id}`, sourceHandle: TABLE_HANDLE.out, targetHandle: self ? TABLE_HANDLE.self : TABLE_HANDLE.in };
    if (pairs.length < relation.columns.length) return [{ ...tableEdge, pathOptions: { stepPosition: stepPosition(relation) } }];
    return pairs.map((p, i) => ({
      ...base,
      ...(i === 0 ? text : {}),
      id: `r${relation.id}-${i}`,
      sourceHandle: columnHandle(p.sourceColumnId, 'out'),
      targetHandle: columnHandle(p.targetColumnId, self ? 'self' : 'in'),
    }));
  });
}
