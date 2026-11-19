/**
 * 같은 두 노드 사이의 관계선 차선 (M-6 후속 2차) — 화면 표현만 하는 순수 계산이라 논리·물리 ERD 가 같이 쓸 수 있다 (지금은 논리 ERD 만 쓴다).
 * 묶음 = 방향과 무관한 두 노드 쌍 (자기 참조는 노드 하나). 묶음 안 순서 = 관계 ID 오름차순이라 다시 조회해도 같은 선이 같은 자리에 온다.
 */
export interface Lane {
  /** 묶음 안 순서 (0 부터) */
  index: number;
  /** 묶음의 관계 수 */
  count: number;
}

/** 관계마다 차선을 한 번에 계산한다 (관계 수만큼 한 번 훑는다) */
export function relationLanes<T>(items: T[], id: (item: T) => number, ends: (item: T) => [number, number]): Map<number, Lane> {
  const groups = new Map<string, number[]>();
  for (const item of items) {
    const [a, b] = ends(item);
    const key = `${Math.min(a, b)}:${Math.max(a, b)}`;
    const group = groups.get(key);
    if (group) group.push(id(item));
    else groups.set(key, [id(item)]);
  }
  const lanes = new Map<number, Lane>();
  for (const group of groups.values()) {
    group.sort((x, y) => x - y);
    group.forEach((relationId, index) => lanes.set(relationId, { index, count: group.length }));
  }
  return lanes;
}

/** 가운데를 0 으로 한 대칭 위치 : 1개 → 0, 2개 → -0.5·0.5, 3개 → -1·0·1 … */
export const centered = (lane: Lane) => lane.index - (lane.count - 1) / 2;

/** 끝점 세로 이동 : 대칭으로 spacing 씩, 전체 폭은 ±limit 안 (관계가 많으면 간격을 줄여 연결점 영역을 벗어나지 않는다) */
export function endShift(lane: Lane, spacing: number, limit: number) {
  if (lane.count < 2) return 0;
  const step = Math.min(spacing, (limit * 2) / (lane.count - 1));
  return centered(lane) * step;
}
