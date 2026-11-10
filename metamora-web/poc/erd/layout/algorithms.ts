import type { ELK as ElkApi, ElkNode } from 'elkjs/lib/elk-api';
import type dagreType from '@dagrejs/dagre';
import type { XY } from '../model';
import type { LayoutEdge, LayoutNode } from './engine';

/** 엔진별 계산 함수 - 엔진 객체는 호출하는 쪽(워커 / 메인 스레드)이 넘긴다 */
export const NODE_GAP = 40;
export const LAYER_GAP = 100;

export async function elkLayout(elk: ElkApi, nodes: LayoutNode[], edges: LayoutEdge[], ports: boolean): Promise<Record<string, XY>> {
  const graph: ElkNode = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.spacing.nodeNode': String(NODE_GAP),
      'elk.layered.spacing.nodeNodeBetweenLayers': String(LAYER_GAP),
      'elk.separateConnectedComponents': 'true',
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: n.width,
      height: n.height,
      ...(ports && {
        layoutOptions: { 'elk.portConstraints': 'FIXED_POS' },
        ports: n.ports.map((p) => ({ id: p.id, x: p.side === 'EAST' ? n.width : 0, y: p.y, width: 1, height: 1, layoutOptions: { 'elk.port.side': p.side } })),
      }),
    })),
    edges: ports
      ? edges.map((e) => ({ id: e.id, sources: [e.sourcePort], targets: [e.targetPort] }))
      : uniquePairs(edges).map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };
  const result = await elk.layout(graph);
  return Object.fromEntries((result.children ?? []).map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]));
}

export function dagreLayout(dagre: typeof dagreType, nodes: LayoutNode[], edges: LayoutEdge[]): Record<string, XY> {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: 'LR', nodesep: NODE_GAP, ranksep: LAYER_GAP });
  g.setDefaultEdgeLabel(() => ({}));
  nodes.forEach((n) => g.setNode(n.id, { width: n.width, height: n.height }));
  uniquePairs(edges).forEach((e) => g.setEdge(e.source, e.target));
  dagre.layout(g);
  // dagre 는 노드 가운데 좌표를 준다
  return Object.fromEntries(nodes.map((n) => {
    const p = g.node(n.id);
    return [n.id, { x: p.x - n.width / 2, y: p.y - n.height / 2 }];
  }));
}

/** 복합 FK(컬럼 쌍 여러 개)는 테이블 단위 레이아웃에서 간선 1개 */
function uniquePairs(edges: LayoutEdge[]) {
  const seen = new Map<string, LayoutEdge>();
  edges.forEach((e) => seen.set(`${e.source}>${e.target}`, e));
  return [...seen.values()];
}

/**
 * 엔진 공통 전처리 (모든 엔진 동일 조건): 자기 참조는 배치에 영향이 없어 빼고,
 * 관계 없는 테이블은 엔진에 넘기지 않는다 (packIsolated 가 결과 아래쪽에 줄 단위로 채운다).
 */
export function splitIsolated(nodes: LayoutNode[], edges: LayoutEdge[]) {
  const linkEdges = edges.filter((e) => e.source !== e.target);
  const linked = new Set(linkEdges.flatMap((e) => [e.source, e.target]));
  return { connected: nodes.filter((n) => linked.has(n.id)), isolated: nodes.filter((n) => !linked.has(n.id)), linkEdges };
}

export function packIsolated(positions: Record<string, XY>, connected: LayoutNode[], isolated: LayoutNode[]) {
  let maxX = 0;
  let maxY = 0;
  connected.forEach((n) => {
    maxX = Math.max(maxX, positions[n.id].x + n.width);
    maxY = Math.max(maxY, positions[n.id].y + n.height);
  });
  const area = isolated.reduce((sum, n) => sum + (n.width + NODE_GAP) * (n.height + NODE_GAP), 0);
  const rowWidth = Math.max(maxX, Math.sqrt(area) * 1.6);
  let x = 0;
  let y = maxY + (connected.length ? LAYER_GAP : 0);
  let rowHeight = 0;
  isolated.forEach((n) => {
    if (x > 0 && x + n.width > rowWidth) {
      x = 0;
      y += rowHeight + NODE_GAP;
      rowHeight = 0;
    }
    positions[n.id] = { x, y };
    x += n.width + NODE_GAP;
    rowHeight = Math.max(rowHeight, n.height);
  });
  return positions;
}
