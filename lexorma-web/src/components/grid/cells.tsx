import type { ReactNode } from 'react';
import { formatDateTimeSeconds, initial } from '../../lib/format';
import { t } from '../../lib/i18n';

/**
 * 그리드 셀 꾸미기 (GitHub Primer 스타일, 예전 js/common/grid_cells.js)
 * 값은 모두 텍스트로 넣으므로 HTML 이 해석되지 않는다.
 */

export type LabelVariant = 'accent' | 'success' | 'attention' | 'danger' | 'done' | '';

export function Label({ children, variant = '', dot = false, title }: { children: ReactNode; variant?: LabelVariant; dot?: boolean; title?: string }) {
  const className = ['gh-label', variant ? `gh-label-${variant}` : '', dot ? 'gh-label-dot' : ''].filter(Boolean).join(' ');
  return (
    <span className={className} title={title}>
      {children}
    </span>
  );
}

export function Muted({ children }: { children: ReactNode }) {
  return <span className="gh-muted">{children}</span>;
}

export function Mono({ children }: { children: ReactNode }) {
  return <span className="gh-mono">{children ?? ''}</span>;
}

/** 이니셜 아바타 + 이름 */
export function User({ name }: { name: unknown }) {
  const value = String(name ?? '').trim();
  if (!value) return <Muted>-</Muted>;
  return (
    <span className="gh-user">
      <span className="gh-avatar">{initial(value)}</span>
      <span>{value}</span>
    </span>
  );
}

/** yyyy-MM-dd HH:mm:ss */
export function DateTime({ value }: { value: unknown }) {
  const text = formatDateTimeSeconds(value);
  if (!text) return <Muted>-</Muted>;
  return (
    <time className="gh-muted" dateTime={String(value)}>
      {text}
    </time>
  );
}

/** 승인 여부(Y/N) → "승인" / "승인 대기" */
export function Approval({ value }: { value: unknown }) {
  if (value === 'Y') return <Label variant="success" dot>{t('승인')}</Label>;
  if (value === 'N') return <Label variant="attention" dot>{t('승인 대기')}</Label>;
  return null;
}

/** Y 는 파란 라벨, N 은 흐린 글자 */
export function YN({ value, text = 'Y' }: { value: unknown; text?: string }) {
  if (value === 'Y') return <Label variant="accent">{text}</Label>;
  if (value === 'N') return <Muted>N</Muted>;
  return null;
}

/**
 * 표 처음 조회 중 스켈레톤 줄 (UI-1, 공용 그리드와 같은 모양) : 실제 행 높이의 회색 덩어리 rows 줄. 상태 문구는 보조 기술에만(role=status),
 * 덩어리는 감춘다. 다시 조회할 때는 쓰지 말고 앞 결과를 둔다 (motion.css .is-refreshing)
 */
export function SkeletonRows({ columns, rows = 5, label }: { columns: number; rows?: number; label: string }) {
  return (
    <>
      {Array.from({ length: rows }, (_, row) => (
        <tr key={row} className="skeleton-row" aria-hidden={row > 0 || undefined}>
          {Array.from({ length: columns }, (_, index) => (
            <td key={index}>
              {row === 0 && index === 0 && <span className="visually-hidden" role="status">{label}</span>}
              <span className="skeleton" style={{ width: `${55 + ((row * 17 + index * 23) % 40)}%` }} aria-hidden="true" />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
