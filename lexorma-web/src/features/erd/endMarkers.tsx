/**
 * 관계선 끝 표기 (Crow's Foot) SVG 정의 — 논리 ERD(참여 수)와 물리 ERD(제약조건으로 판단한 카디널리티, M-2 개선 2차)가 같이 쓴다.
 * 최소 : 0 = ○, 1 = | / 최대 : 1 = |, 여러 건 = 까치발. max- 로 시작하는 표기는 최소를 판단하지 않은 끝(최대만 그린다).
 * 선과 같은 색(context-stroke)으로 그리고, 시작 쪽은 뒤집어 쓴다 (auto-start-reverse). 어느 끝에 어떤 표기를 붙일지는 각 ERD 가 정한다
 */
export type EndKind = 'one' | 'zero-one' | 'one-many' | 'zero-many' | 'max-one' | 'max-many';

export function EndMarkerDefs({ prefix, kinds, className }: { prefix: string; kinds: readonly EndKind[]; className: string }) {
  return (
    <svg className={className} aria-hidden>
      <defs>
        {kinds.map((kind) => {
          const many = kind.endsWith('many');
          return (
            <marker key={kind} id={`${prefix}-${kind}`} viewBox="0 0 24 16" markerWidth="24" markerHeight="16" refX="24" refY="8" markerUnits="userSpaceOnUse" orient="auto-start-reverse">
              <g fill="none" stroke="context-stroke" strokeWidth="1.5" strokeDasharray="none">
                {many ? <path d="M12 8 L24 2 M12 8 L24 14" /> : <path d="M19 2 V14" />}
                {kind.startsWith('zero') && <circle cx={many ? 7 : 10} cy="8" r="3.5" fill="var(--gh-canvas)" />}
                {(kind === 'one' || kind === 'one-many') && <path d={many ? 'M9 2 V14' : 'M14 2 V14'} />}
              </g>
            </marker>
          );
        })}
      </defs>
    </svg>
  );
}
