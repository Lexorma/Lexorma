/** PoC 측정 도구 (requestAnimationFrame 간격 + Long Task) - 운영 코드에는 넣지 않는다 */
export interface FrameStats {
  frames: number;
  avgMs: number;
  p95Ms: number;
  maxMs: number;
  fps: number;
  longTasks: number;
  longTaskMs: number;
}

const round = (v: number) => Math.round(v * 10) / 10;

export function frameMeter() {
  const deltas: number[] = [];
  let longTasks = 0;
  let longTaskMs = 0;
  const observer = new PerformanceObserver((list) => list.getEntries().forEach((e) => {
    longTasks++;
    longTaskMs += e.duration;
  }));
  try {
    observer.observe({ type: 'longtask' });
  } catch {
    // longtask 미지원 브라우저
  }
  let last = performance.now();
  let running = true;
  let raf = 0;
  const tick = (t: number) => {
    deltas.push(t - last);
    last = t;
    if (running) raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
  return {
    stop(): FrameStats {
      running = false;
      cancelAnimationFrame(raf);
      // 아직 콜백으로 전달되지 않은 Long Task 도 센다
      observer.takeRecords().forEach((e) => {
        longTasks++;
        longTaskMs += e.duration;
      });
      observer.disconnect();
      const sorted = [...deltas].sort((a, b) => a - b);
      const total = deltas.reduce((s, d) => s + d, 0);
      return {
        frames: deltas.length,
        avgMs: round(total / Math.max(1, deltas.length)),
        p95Ms: round(sorted[Math.floor(sorted.length * 0.95)] ?? 0),
        maxMs: round(sorted[sorted.length - 1] ?? 0),
        fps: round((deltas.length * 1000) / Math.max(1, total)),
        longTasks,
        longTaskMs: round(longTaskMs),
      };
    },
  };
}

export const nextFrame = () => new Promise<number>((resolve) => requestAnimationFrame(resolve));

/** durationMs 동안 매 프레임 step(진행률 0~1)을 호출하고 프레임 통계를 돌려준다 */
export async function animate(durationMs: number, step: (progress: number) => void) {
  const meter = frameMeter();
  const start = performance.now();
  for (;;) {
    const p = Math.min(1, (performance.now() - start) / durationMs);
    step(p);
    if (p >= 1) break;
    await nextFrame();
  }
  await nextFrame();
  await nextFrame();
  return meter.stop();
}

/**
 * 렌더링 안정화 대기: 노드·엣지 DOM 수가 마지막으로 바뀐 시각을 돌려준다 (3프레임 연속 같으면 안정).
 * 엣지는 노드 측정(ResizeObserver) 다음 프레임에 그려지므로 expectEdges 면 엣지가 생길 때까지 기다린다.
 */
export async function settled(root: Element, expectEdges: boolean, timeoutMs = 120_000) {
  const start = performance.now();
  let lastKey = '';
  let changedAt = start;
  let stable = 0;
  for (;;) {
    await nextFrame();
    // rAF 시각(프레임 시작)이 아니라 콜백 실행 시각 → 앞선 긴 작업(렌더링)이 끝난 뒤의 시각
    const t = performance.now();
    const n = root.querySelectorAll('.react-flow__node').length;
    const e = root.querySelectorAll('.react-flow__edge').length;
    const key = `${n}/${e}`;
    if (key !== lastKey) {
      lastKey = key;
      changedAt = t;
      stable = 0;
    } else if (n > 0 && (!expectEdges || e > 0)) {
      stable++;
    }
    if (stable >= 3 || t - start > timeoutMs) return { at: changedAt, nodes: n, edges: e };
  }
}
