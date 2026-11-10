import type { Edge, Node } from '@xyflow/react';
import type { LayoutEdge, LayoutNode, LayoutPort } from './layout/engine';
import { HEADER_HEIGHT, NODE_WIDTH, ROW_HEIGHT, nodeHeight, visibleColumns, type ErdColumn, type ErdModel, type ErdTable, type RelationKind, type XY } from './model';

export type TableNodeData = {
  table: ErdTable;
  rows: ErdColumn[];
  fkIds: Set<string>;
  /** 관계선이 붙는 컬럼 - 이 컬럼에만 Handle 을 만든다 (DOM 수 절감) */
  linkedIds: Set<string>;
};
export type TableFlowNode = Node<TableNodeData, 'table'>;

export type RelationEdgeData = {
  kind: RelationKind;
  relationId: string;
  pairIndex: number;
  pairCount: number;
  self: boolean;
  sourceColumnId: string;
  targetColumnId: string;
};
export type RelationFlowEdge = Edge<RelationEdgeData, 'relation'>;

export const rowCenterY = (index: number) => HEADER_HEIGHT + index * ROW_HEIGHT + ROW_HEIGHT / 2 + 1;

/** 모델(전체 또는 tableIds 부분집합) → React Flow 노드·엣지 + 레이아웃 요청. 관계는 컬럼 쌍마다 엣지 1개 */
export function buildGraph(model: ErdModel, tableIds: Set<string> | null, keyOnly: boolean) {
  const fkIds = new Set(model.relations.flatMap((r) => r.columns.map((p) => p.sourceColumnId)));
  const linkedIds = new Set(model.relations.flatMap((r) => r.columns.flatMap((p) => [p.sourceColumnId, p.targetColumnId])));
  const tables = tableIds ? model.tables.filter((t) => tableIds.has(t.id)) : model.tables;
  const rowIndex = new Map<string, number>();

  const nodes: TableFlowNode[] = tables.map((table) => {
    const rows = visibleColumns(table, fkIds, keyOnly);
    rows.forEach((c, i) => rowIndex.set(c.id, i));
    return { id: table.id, type: 'table', position: { x: 0, y: 0 }, width: NODE_WIDTH, height: nodeHeight(rows.length), data: { table, rows, fkIds, linkedIds } };
  });

  const shown = new Set(tables.map((t) => t.id));
  const edges: RelationFlowEdge[] = [];
  const layoutEdges: LayoutEdge[] = [];
  const ports = new Map<string, Map<string, LayoutPort>>();
  const addPort = (nodeId: string, port: LayoutPort) => {
    if (!ports.has(nodeId)) ports.set(nodeId, new Map());
    ports.get(nodeId)!.set(port.id, port);
  };

  model.relations.forEach((r) => {
    if (!shown.has(r.sourceTableId) || !shown.has(r.targetTableId)) return;
    r.columns.forEach((p, i) => {
      const id = `${r.id}:${i}`;
      edges.push({
        id,
        type: 'relation',
        source: r.sourceTableId,
        target: r.targetTableId,
        sourceHandle: `${p.sourceColumnId}:R`,
        targetHandle: `${p.targetColumnId}:L`,
        data: { kind: r.kind, relationId: r.id, pairIndex: i, pairCount: r.columns.length, self: r.sourceTableId === r.targetTableId, sourceColumnId: p.sourceColumnId, targetColumnId: p.targetColumnId },
      });
      const sourcePort = { id: `${p.sourceColumnId}:E`, side: 'EAST' as const, y: rowCenterY(rowIndex.get(p.sourceColumnId)!) };
      const targetPort = { id: `${p.targetColumnId}:W`, side: 'WEST' as const, y: rowCenterY(rowIndex.get(p.targetColumnId)!) };
      addPort(r.sourceTableId, sourcePort);
      addPort(r.targetTableId, targetPort);
      layoutEdges.push({ id, source: r.sourceTableId, target: r.targetTableId, sourcePort: sourcePort.id, targetPort: targetPort.id });
    });
  });

  const layoutNodes: LayoutNode[] = nodes.map((n) => ({ id: n.id, width: n.width!, height: n.height!, ports: [...(ports.get(n.id)?.values() ?? [])] }));
  return { nodes, edges, layoutNodes, layoutEdges };
}

/** 두 테이블의 좌우 위치에 따라 관계선이 나가는 쪽을 고른다. 자기 참조는 오른쪽 → 오른쪽 */
export function withSides(edge: RelationFlowEdge, source: XY, target: XY): RelationFlowEdge {
  const d = edge.data!;
  const leftToRight = d.self || source.x <= target.x;
  const sourceHandle = `${d.sourceColumnId}:${leftToRight ? 'R' : 'L'}`;
  const targetHandle = `${d.targetColumnId}:${d.self || !leftToRight ? 'R' : 'L'}`;
  return edge.sourceHandle === sourceHandle && edge.targetHandle === targetHandle ? edge : { ...edge, sourceHandle, targetHandle };
}

/** depth 단계 이웃 테이블 (관련 테이블 펼치기) */
export function neighborIds(model: ErdModel, seedIds: string[], depth = 1) {
  const adjacent = new Map<string, Set<string>>();
  model.relations.forEach((r) => {
    if (!adjacent.has(r.sourceTableId)) adjacent.set(r.sourceTableId, new Set());
    if (!adjacent.has(r.targetTableId)) adjacent.set(r.targetTableId, new Set());
    adjacent.get(r.sourceTableId)!.add(r.targetTableId);
    adjacent.get(r.targetTableId)!.add(r.sourceTableId);
  });
  const result = new Set(seedIds);
  let frontier = seedIds;
  for (let i = 0; i < depth; i++) {
    const next: string[] = [];
    frontier.forEach((id) => adjacent.get(id)?.forEach((n) => {
      if (!result.has(n)) {
        result.add(n);
        next.push(n);
      }
    }));
    frontier = next;
  }
  return result;
}
