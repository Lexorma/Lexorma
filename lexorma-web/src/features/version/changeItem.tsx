import type { ReactNode } from 'react';
import { Label, Muted } from '../../components/grid/cells';
import { DiffTable } from '../history/historyDialogs';
import { changedLabels, diffRows } from '../history/model';
import { CHANGES, versionTypeLabel, type VersionChange } from './model';

/** 달라진 객체 한 줄 (버전 비교 목록과 ERD 비교의 상세가 같이 쓴다) : 누르면 변경 전후를 편다. children 은 펼친 표 아래에 둔다 */
export function ChangeItem({ change, open, onToggle, children }: { change: VersionChange; open: boolean; onToggle: () => void; children?: ReactNode }) {
  const [, label, variant] = CHANGES.find(([value]) => value === change.change) ?? CHANGES[2];
  return (
    <li>
      <button type="button" className="history-group-item" aria-expanded={open} onClick={onToggle}>
        <Label variant={variant}>{label}</Label>
        <span>{versionTypeLabel(change.targetType)}</span>
        <span className="history-group-name">{change.name ?? `#${change.targetId}`}</span>
        {change.change === 'MODIFIED' && <Muted>{changedLabels(change.changedFields).join(', ')}</Muted>}
      </button>
      {open && <ChangeDiff change={change} />}
      {open && children}
    </li>
  );
}

function ChangeDiff({ change }: { change: VersionChange }) {
  const rows = diffRows({ summary: { targetType: change.targetType }, before: change.before, after: change.after });
  return <DiffTable rows={change.change === 'MODIFIED' ? rows.filter((row) => row.changed) : rows} hasBefore={!!change.before} hasAfter={!!change.after} />;
}

export const changeKey = (change: { targetType: string; targetId: number }) => `${change.targetType}:${change.targetId}`;
