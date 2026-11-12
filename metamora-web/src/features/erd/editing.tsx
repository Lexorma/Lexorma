import { t } from '../../lib/i18n';
import type { XY } from './model';

/**
 * ERD 편집 기반 (M-8). 논리 ERD 가 쓰고, 물리 ERD 편집(M-8 3차)이 같은 것을 쓴다.
 * - 조회 모드 : 탐색·배치만. 편집 모드 : 모델 데이터 변경 버튼이 보인다 (변경 권한이 있어야 들어갈 수 있다).
 * - 모델 데이터는 기존 모델 API 로 바로 저장하고, 저장에 성공한 뒤 ERD 를 다시 조회한다 (화면 상태만 먼저 바꾸지 않는다).
 * - 배치(노드 위치)는 모델 데이터와 따로이며 [배치 저장] 을 눌러야 저장된다.
 */
export type ErdMode = 'view' | 'edit';

/** 조회 / 편집 모드 전환. 변경 권한이 없으면 그리지 않는다 (서버 API 가 같은 기준으로 다시 검사한다) */
export function ErdModeToggle({ mode, onChange, disabled }: { mode: ErdMode; onChange: (mode: ErdMode) => void; disabled?: boolean }) {
  const modes: [ErdMode, string][] = [['view', t('조회 모드')], ['edit', t('편집 모드')]];
  return (
    <div className="erd-mode" role="group" aria-label={t('ERD 모드')}>
      {modes.map(([id, label]) => (
        <button key={id} type="button" className={`btn${mode === id ? ' btn-primary' : ''}`} aria-pressed={mode === id} disabled={disabled} onClick={() => onChange(id)}>{label}</button>
      ))}
    </div>
  );
}

export interface Box extends XY {
  width: number;
  height: number;
}

const GAP = 40;
const overlaps = (a: Box, b: Box) => a.x < b.x + b.width + GAP && b.x < a.x + a.width + GAP && a.y < b.y + b.height + GAP && b.y < a.y + a.height + GAP;

/**
 * 새 노드의 위치 : 바라는 자리(보고 있는 화면 가운데)에서 시작해 기존 노드와 겹치지 않는 가장 가까운 자리를 찾는다.
 * 기존 노드는 옮기지 않는다 (새 노드 때문에 전체를 다시 배치하지 않는다). 찾지 못하면 기존 배치의 오른쪽 바깥에 둔다.
 */
export function findFreePosition(desired: XY, size: { width: number; height: number }, occupied: Box[]): XY {
  const free = (position: XY) => !occupied.some((box) => overlaps({ ...position, ...size }, box));
  if (free(desired)) return desired;
  const stepX = size.width + GAP;
  const stepY = Math.max(size.height, 80) + GAP;
  // 가까운 칸부터 바깥으로 (오른쪽·아래를 먼저 본다)
  for (let ring = 1; ring <= 8; ring++) {
    for (let dy = 0; dy <= ring; dy++) {
      for (const [sx, sy] of [[1, 1], [1, -1], [-1, 1], [-1, -1]]) {
        for (const dx of dy === ring ? Array.from({ length: ring + 1 }, (_, i) => i) : [ring]) {
          const candidate = { x: desired.x + sx * dx * stepX, y: desired.y + sy * dy * stepY };
          if (free(candidate)) return candidate;
        }
      }
    }
  }
  return { x: Math.max(...occupied.map((box) => box.x + box.width)) + GAP * 3, y: desired.y };
}
