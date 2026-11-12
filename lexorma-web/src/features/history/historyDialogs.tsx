import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDialog, type DialogControls } from '../../components/dialog/DialogProvider';
import { DateTime, Label, Mono, Muted } from '../../components/grid/cells';
import { api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/history.css';
import { ACTION_VARIANT, actionLabel, diffRows, sourceLabel, targetTypeLabel, type DiffRow, type HistoryDetail, type HistorySummary } from './model';

interface DetailOptions {
  /** [이 객체의 이력] : 목록을 그 객체로 거른다 */
  onTarget?: (summary: HistorySummary) => void;
}

/** 변경 이력 상세 대화상자 : 변경 전후 비교 + 같은 작업(변경 그룹)의 다른 변경 */
export function useHistoryDialogs() {
  const dialog = useDialog();

  const openHistoryDetail = (id: number, options: DetailOptions = {}) => {
    dialog.open({
      title: t('변경 이력 상세'),
      className: 'dialog-wide',
      content: (controls: DialogControls) => <HistoryDetailView initialId={id} controls={controls} options={options} />,
    });
  };

  return { openHistoryDetail };
}

function HistoryDetailView({ initialId, controls, options }: { initialId: number; controls: DialogControls; options: DetailOptions }) {
  // 같은 그룹의 다른 변경을 누르면 대화상자 안에서 바꿔 본다
  const [id, setId] = useState(initialId);
  // 수정·승인은 달라진 항목만 먼저 보여 준다 (생성·삭제는 전체가 변경이다)
  const [showAll, setShowAll] = useState(false);

  const detail = useQuery({ queryKey: ['model-history', id], queryFn: () => api<HistoryDetail>(`/api/modelHistory/${enc(id)}`), gcTime: 0 });
  const groupId = detail.data?.summary.changeGroupId;
  const group = useQuery({
    queryKey: ['model-history-group', groupId],
    queryFn: () => api<HistorySummary[]>(`/api/modelHistory/group/${enc(groupId!)}`),
    enabled: !!groupId,
    gcTime: 0,
  });

  if (!detail.data) return <p className="gh-muted">{detail.isError ? t('변경 이력을 불러오지 못했습니다.') : t('변경 이력을 불러오는 중입니다.')}</p>;

  const { summary, before, after } = detail.data;
  const rows = diffRows(detail.data);
  const single = !before || !after; // 생성 = 변경 후만, 삭제 = 변경 전만
  const changedCount = rows.filter((row) => row.changed).length;
  const shown = single || showAll ? rows : rows.filter((row) => row.changed);
  const others = (group.data ?? []).filter((item) => item.id !== summary.id);

  return (
    <div className="history-detail">
      <dl className="dict-detail">
        <dt>{t('변경 대상')}</dt>
        <dd>{targetTypeLabel(summary.targetType)} · {summary.targetName ?? <Muted>-</Muted>} <Muted>(#{summary.targetId})</Muted></dd>
        <dt>{t('작업')}</dt>
        <dd><Label variant={ACTION_VARIANT[summary.action] ?? ''}>{actionLabel(summary.action)}</Label> <Muted>{sourceLabel(summary.source)}</Muted></dd>
        <dt>{t('변경 사용자')}</dt>
        <dd>{summary.userId ?? <Muted>-</Muted>}</dd>
        <dt>{t('변경 일시')}</dt>
        <dd><DateTime value={summary.changedAt} /></dd>
      </dl>

      <div className="history-diff-head">
        <h4>{single ? (after ? t('생성된 내용') : t('삭제 전 내용')) : t('변경 전후 ({0}개 항목 변경)', changedCount)}</h4>
        {!single && (
          <label className="history-toggle">
            <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} /> {t('전체 항목 보기')}
          </label>
        )}
      </div>
      <DiffTable rows={shown} hasBefore={!!before} hasAfter={!!after} />

      <div className="history-group">
        <h4>{t('같은 작업의 변경')} <Muted>{t('변경 그룹')} <Mono>{summary.changeGroupId.slice(0, 8)}</Mono> · {t('{0}건', group.data?.length ?? 1)}</Muted></h4>
        {others.length === 0 ? (
          <p className="gh-muted">{group.isPending ? t('변경 이력을 불러오는 중입니다.') : t('이 작업에서 바뀐 것은 이 항목뿐입니다.')}</p>
        ) : (
          <ol className="history-group-list">
            {(group.data ?? []).map((item) => (
              <li key={item.id}>
                <button type="button" className="history-group-item" aria-current={item.id === summary.id} disabled={item.id === summary.id}
                  onClick={() => { setShowAll(false); setId(item.id); }}>
                  <Label variant={ACTION_VARIANT[item.action] ?? ''}>{actionLabel(item.action)}</Label>
                  <span>{targetTypeLabel(item.targetType)}</span>
                  <span className="history-group-name">{item.targetName ?? `#${item.targetId}`}</span>
                  {item.source !== 'USER' && <Muted>{sourceLabel(item.source)}</Muted>}
                </button>
              </li>
            ))}
          </ol>
        )}
      </div>

      {options.onTarget && (
        <div className="gh-dialog-actions">
          <button type="button" className="btn" onClick={() => { controls.close(); options.onTarget?.(summary); }}>{t('이 객체의 이력')}</button>
        </div>
      )}
    </div>
  );
}

/** 변경 전후 비교 표 (모델 버전 화면도 같이 쓴다). 한쪽만 있으면 그쪽 열만 그린다 */
export function DiffTable({ rows, hasBefore, hasAfter }: { rows: DiffRow[]; hasBefore: boolean; hasAfter: boolean }) {
  const single = !hasBefore || !hasAfter;
  return (
    <div className="table-wrap">
      <table className="history-diff">
        <thead>
          <tr>
            <th>{t('항목')}</th>
            {hasBefore && <th>{t('변경 전')}</th>}
            {hasAfter && <th>{t('변경 후')}</th>}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={3} className="gh-muted">{t('달라진 항목이 없습니다.')}</td></tr>}
          {rows.map((row) => (
            <tr key={row.key} className={!single && row.changed ? 'is-changed' : undefined}>
              <th scope="row">{row.label}</th>
              {hasBefore && <td className={!single && row.changed ? 'history-before' : undefined}>{row.before.map((line, index) => <div key={index}>{line}</div>)}</td>}
              {hasAfter && <td className={!single && row.changed ? 'history-after' : undefined}>{row.after.map((line, index) => <div key={index}>{line}</div>)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
