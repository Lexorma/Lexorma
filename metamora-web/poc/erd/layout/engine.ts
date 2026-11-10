import ElkApi from 'elkjs/lib/elk-api.js';
import elkWorkerUrl from 'elkjs/lib/elk-worker.min.js?url';
import type { XY } from '../model';
import { dagreLayout, elkLayout, packIsolated, splitIsolated } from './algorithms';

/**
 * Layout Engine Adapter - 화면은 이 입력/출력 형식만 안다. 엔진을 바꿔도 ErdCanvas·TableNode·
 * 저장된 레이아웃 형식(노드 id → x, y)은 그대로다.
 * - ELK : 공식 워커(elk-worker.min.js)를 elk-api 로 부른다. elk.bundled 는 워커 안에서 쓰면 자기를 워커 본체로 인식해 생성자가 없다.
 * - Dagre : 자체 모듈 워커(dagre.worker.ts)
 * - mainThread=true : 비교용(워커 없이 메인 스레드에서 계산 → UI 멈춤 측정)
 */
export type EngineId = 'elk' | 'elk-ports' | 'dagre';

export const ENGINE_LABEL: Record<EngineId, string> = {
  elk: 'ELK layered',
  'elk-ports': 'ELK layered + 컬럼 포트',
  dagre: 'Dagre',
};

export interface LayoutPort {
  id: string;
  side: 'WEST' | 'EAST';
  /** 노드 위쪽 기준 연결점 y (컬럼 행 가운데) */
  y: number;
}

export interface LayoutNode {
  id: string;
  width: number;
  height: number;
  ports: LayoutPort[];
}

export interface LayoutEdge {
  id: string;
  source: string;
  target: string;
  sourcePort: string;
  targetPort: string;
}

export interface LayoutRequest {
  engine: EngineId;
  nodes: LayoutNode[];
  edges: LayoutEdge[];
}

export interface LayoutResult {
  positions: Record<string, XY>;
  /** 엔진 호출 시간 (워커: 워커 시작·데이터 복사 포함) */
  engineMs: number;
  /** 전처리·후처리 포함 전체 */
  wallMs: number;
}

let cancelCurrent: (() => void) | null = null;

/** 새 요청이 오면 진행 중인 워커 계산은 버린다 → 사용자는 기다리지 않고 다른 조작을 할 수 있다 */
export async function runLayout(request: LayoutRequest, mainThread = false): Promise<LayoutResult> {
  cancelCurrent?.();
  const started = performance.now();
  const { connected, isolated, linkEdges } = splitIsolated(request.nodes, request.edges);
  const e0 = performance.now();
  const positions = connected.length === 0 ? {}
    : mainThread ? await runOnMainThread(request.engine, connected, linkEdges)
      : await runInWorker(request.engine, connected, linkEdges);
  const engineMs = performance.now() - e0;
  packIsolated(positions, connected, isolated);
  return { positions, engineMs, wallMs: performance.now() - started };
}

function runInWorker(engine: EngineId, nodes: LayoutNode[], edges: LayoutEdge[]): Promise<Record<string, XY>> {
  return new Promise((resolve, reject) => {
    if (engine === 'dagre') {
      const worker = new Worker(new URL('./dagre.worker.ts', import.meta.url), { type: 'module' });
      cancelCurrent = () => {
        worker.terminate();
        reject(new Error('레이아웃 계산 취소'));
      };
      worker.onmessage = (event: MessageEvent<{ positions?: Record<string, XY>; error?: string }>) => {
        worker.terminate();
        cancelCurrent = null;
        if (event.data.error) reject(new Error(event.data.error));
        else resolve(event.data.positions!);
      };
      worker.onerror = (event) => {
        worker.terminate();
        reject(new Error(event.message));
      };
      worker.postMessage({ nodes, edges });
      return;
    }
    const elk = new ElkApi({ workerUrl: elkWorkerUrl });
    cancelCurrent = () => {
      elk.terminateWorker();
      reject(new Error('레이아웃 계산 취소'));
    };
    elkLayout(elk, nodes, edges, engine === 'elk-ports')
      .then(resolve, reject)
      .finally(() => {
        elk.terminateWorker();
        cancelCurrent = null;
      });
  });
}

async function runOnMainThread(engine: EngineId, nodes: LayoutNode[], edges: LayoutEdge[]) {
  if (engine === 'dagre') {
    const { default: dagre } = await import('@dagrejs/dagre');
    return dagreLayout(dagre, nodes, edges);
  }
  const { default: ElkBundled } = await import('elkjs/lib/elk.bundled.js');
  return elkLayout(new ElkBundled(), nodes, edges, engine === 'elk-ports');
}
