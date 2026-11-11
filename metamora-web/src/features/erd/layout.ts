import ElkApi from 'elkjs/lib/elk-api.js';
import elkWorkerUrl from 'elkjs/lib/elk-worker.min.js?url';
import type { XY } from './model';
import { t } from '../../lib/i18n';

/**
 * 레이아웃 어댑터 (M-0 PoC engine.ts 의 ELK 경로) : 화면은 이 입출력만 안다. 엔진을 바꿔도 노드·저장 형식(id → x, y)은 그대로다.
 * - 공식 ELK 워커(elk-worker.min.js)에서 계산해 메인 스레드를 막지 않는다
 * - 새 요청이 오면 진행 중인 계산은 버린다
 * - 관계 없는 테이블은 ELK 에 넣지 않고 아래쪽에 격자로 놓는다 (허브 테이블 때문에 세로로 길어지는 것 완화)
 */
export interface LayoutNode {
  id: string;
  width: number;
  height: number;
}

export interface LayoutEdge {
  source: string;
  target: string;
}

const NODE_GAP = 50;
const LAYER_GAP = 110;
const TIMEOUT_MS = 30_000;

let cancelCurrent: (() => void) | null = null;

export function cancelLayout() {
  cancelCurrent?.();
}

/** layerGap : 관계로 이어진 노드 사이 간격 (논리 ERD 는 관계선 양 끝에 참여 수를 적어 더 넓게 쓴다) */
export async function runLayout(nodes: LayoutNode[], edges: LayoutEdge[], layerGap = LAYER_GAP): Promise<Record<string, XY>> {
  cancelCurrent?.();
  const links = edges.filter((e) => e.source !== e.target);
  const linked = new Set(links.flatMap((e) => [e.source, e.target]));
  const connected = nodes.filter((n) => linked.has(n.id));
  const isolated = nodes.filter((n) => !linked.has(n.id));

  const positions = connected.length ? await elkInWorker(connected, links, layerGap) : {};
  packIsolated(positions, connected, isolated);
  return positions;
}

function elkInWorker(nodes: LayoutNode[], edges: LayoutEdge[], layerGap: number): Promise<Record<string, XY>> {
  return new Promise((resolve, reject) => {
    const elk = new ElkApi({ workerUrl: elkWorkerUrl });
    const stop = (message: string) => {
      elk.terminateWorker();
      reject(new Error(message));
    };
    const timer = setTimeout(() => stop(t('자동 배치 시간이 초과되었습니다. 조회 범위를 줄여주세요.')), TIMEOUT_MS);
    cancelCurrent = () => stop(t('자동 배치 취소'));
    const pairs = new Map(edges.map((e) => [`${e.source}>${e.target}`, e]));
    elk.layout({
      id: 'root',
      layoutOptions: {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.spacing.nodeNode': String(NODE_GAP),
        'elk.layered.spacing.nodeNodeBetweenLayers': String(layerGap),
        'elk.separateConnectedComponents': 'true',
      },
      children: nodes.map((n) => ({ id: n.id, width: n.width, height: n.height })),
      edges: [...pairs.values()].map((e, i) => ({ id: `e${i}`, sources: [e.source], targets: [e.target] })),
    })
      .then((result) => resolve(Object.fromEntries((result.children ?? []).map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]))), reject)
      .finally(() => {
        clearTimeout(timer);
        elk.terminateWorker();
        cancelCurrent = null;
      });
  });
}

function packIsolated(positions: Record<string, XY>, connected: LayoutNode[], isolated: LayoutNode[]) {
  let y = connected.length ? Math.max(...connected.map((n) => positions[n.id].y + n.height)) + NODE_GAP * 2 : 0;
  let x = 0;
  let rowHeight = 0;
  const rowWidth = Math.max(1, Math.ceil(Math.sqrt(isolated.length))) * ((isolated[0]?.width ?? 0) + NODE_GAP);
  for (const n of isolated) {
    if (x > 0 && x + n.width > rowWidth) {
      x = 0;
      y += rowHeight + NODE_GAP;
      rowHeight = 0;
    }
    positions[n.id] = { x, y };
    x += n.width + NODE_GAP;
    rowHeight = Math.max(rowHeight, n.height);
  }
}

/** ELK 실패·시간 초과 시 대체 : 격자 배치 */
export function gridLayout(nodes: LayoutNode[]): Record<string, XY> {
  const positions: Record<string, XY> = {};
  packIsolated(positions, [], nodes);
  return positions;
}
