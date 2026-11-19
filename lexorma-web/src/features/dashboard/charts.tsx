import { useCountUp } from '../../hooks/useCountUp';
import { t } from '../../lib/i18n';

/**
 * 대시보드 차트 (SVG, 라이브러리 없음). 색은 테마 변수(--gh-*)라 라이트·다크를 따라가고, 값은 모두 글자로도 보인다(색만으로 구분하지 않는다).
 * 모션 (UI-1) : 처음 그릴 때만 CSS keyframes 로 그려지고(motion.css 대시보드 절), 값이 바뀌면 길이만 전환한다 — 다시 그리기·테마 변경에는 처음으로 돌아가지 않는다.
 */

const fmt = (n: number) => n.toLocaleString();
const pct = (part: number, whole: number) => (whole > 0 ? (part / whole) * 100 : 0);
export const percentText = (part: number, whole: number) => (whole > 0 ? `${pct(part, whole).toFixed(1)}%` : '-');

/** 도넛 : 한 값의 비율. whole 이 0 이면 회색 고리와 "대상 없음" */
export function Donut({ part, whole, label, color = 'var(--gh-success)' }: { part: number; whole: number; label: string; color?: string }) {
  const r = 52;
  const ratio = whole > 0 ? part / whole : 0;
  const center = whole > 0 ? percentText(part, whole) : t('대상 없음');
  return (
    <svg className="dash-donut" viewBox="0 0 140 140" role="img" aria-label={`${label} ${center}`}>
      <title>{`${label} : ${fmt(part)} / ${fmt(whole)} (${center})`}</title>
      <circle cx="70" cy="70" r={r} fill="none" stroke="var(--gh-neutral-muted)" strokeWidth="16" />
      {whole > 0 && part > 0 && (
        // pathLength 100 : 처음 그리는 모션(0 → 비율)과 값 변경 전환이 원 둘레와 무관하게 같은 단위 (motion.css)
        <circle className="dash-donut-arc" cx="70" cy="70" r={r} fill="none" stroke={color} strokeWidth="16" pathLength={100} strokeDasharray={`${ratio * 100} 100`}
                transform="rotate(-90 70 70)" strokeLinecap={ratio >= 1 ? 'butt' : 'round'} />
      )}
      <text x="70" y="72" textAnchor="middle" dominantBaseline="middle" className={`dash-donut-value${whole > 0 ? '' : ' is-empty'}`}>{center}</text>
    </svg>
  );
}

export interface Segment { key: string; label: string; value: number; color: string }

/** 가로 누적 막대 한 줄 (항목별 비율). total 이 0 이면 빈 막대 */
export function StackedBar({ segments, label }: { segments: Segment[]; label: string }) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  const summary = segments.map((s) => `${s.label} ${fmt(s.value)}`).join(', ');
  return (
    <div className="dash-stack" role="img" aria-label={`${label} : ${summary}`} title={`${label} : ${summary}`}>
      {total === 0 ? <span className="dash-stack-empty" /> : segments.filter((s) => s.value > 0).map((s) => (
        <span key={s.key} className="dash-stack-seg" style={{ width: `${pct(s.value, total)}%`, background: s.color }} />
      ))}
    </div>
  );
}

/** 범례 */
export function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <ul className="dash-legend">
      {items.map((item) => (
        <li key={item.label}><span className="dash-legend-swatch" style={{ background: item.color }} aria-hidden="true" />{item.label}</li>
      ))}
    </ul>
  );
}

/** 가로 막대 한 줄 : 값 / 기준(max) 길이. 기준이 0 이면 빈 막대 */
export function Bar({ value, max, color = 'var(--gh-accent)', label }: { value: number; max: number; color?: string; label: string }) {
  return (
    <div className="dash-bar" role="img" aria-label={`${label} ${fmt(value)}`} title={`${label} : ${fmt(value)}`}>
      {max > 0 && value > 0 && <span className="dash-bar-fill" style={{ width: `${Math.max(pct(value, max), 1.5)}%`, background: color }} />}
    </div>
  );
}

/**
 * 세어 올라가는 숫자 (UI-1) : 처음 보일 때만 0 → 값 (useCountUp). 보조 기술에는 늘 실제 값만(중간 값은 aria-hidden),
 * 값이 바뀌면 다시 세지 않고 새 값을 잠깐 강조한다.
 */
export function CountUp({ value }: { value: number }) {
  const { shown, changed } = useCountUp(value);
  return (
    <>
      <span key={changed} className={changed > 0 ? 'motion-highlight' : undefined} aria-hidden="true">{fmt(shown)}</span>
      <span className="visually-hidden">{fmt(value)}</span>
    </>
  );
}
