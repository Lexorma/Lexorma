import { useEffect, useRef } from 'react';

/**
 * 전체 선택 체크박스 : 보이는 항목이 모두 선택되면 체크, 일부만이면 중간 상태. 누르면 모두 선택하거나 모두 해제한다.
 * (일괄 처리 개수 제한은 서버가 다시 확인한다 — 일괄 검토·대량 생성·평가 대상 등록 모두 100개까지)
 */
export function SelectAll({ total, selected, onChange, label }: { total: number; selected: number; onChange: (all: boolean) => void; label: string }) {
  const ref = useRef<HTMLInputElement>(null);
  const all = total > 0 && selected >= total;
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = selected > 0 && !all;
  }, [selected, all]);
  return <input ref={ref} type="checkbox" aria-label={label} title={label} disabled={total === 0} checked={all} onChange={() => onChange(!all)} />;
}
