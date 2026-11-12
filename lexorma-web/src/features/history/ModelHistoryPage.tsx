import { useMemo, useState } from 'react';
import { DateTime, Label, Mono, Muted, User } from '../../components/grid/cells';
import { Grid } from '../../components/grid/Grid';
import { PageHeader } from '../../components/layout/PageHeader';
import { t } from '../../lib/i18n';
import { modelHistoryColumns } from './columns';
import '../../styles/history.css';
import { useHistoryDialogs } from './historyDialogs';
import { ACTION_VARIANT, ACTIONS, actionLabel, changedLabels, sourceLabel, TARGET_TYPES, targetTypeLabel } from './model';

interface Filters {
  targetType: string;
  targetId: string;
  action: string;
  userId: string;
  from: string;
  to: string;
  changeGroupId: string;
}

const EMPTY: Filters = { targetType: '', targetId: '', action: '', userId: '', from: '', to: '', changeGroupId: '' };

/**
 * 모델 변경 이력 (M-9) : 물리·논리 모델과 매핑이 언제·누가·어떻게 바뀌었는지 조회한다. 조회만 한다 (이력은 고치거나 지울 수 없다).
 * 다른 화면에서 ?targetType=…&targetId=… 로 넘어오면 그 객체의 이력으로 연다 (삭제된 객체도 조회된다).
 */
export function ModelHistoryPage() {
  const { openHistoryDetail } = useHistoryDialogs();
  const [filters, setFilters] = useState<Filters>(() => {
    const query = new URLSearchParams(window.location.search);
    return { ...EMPTY, targetType: query.get('targetType') ?? '', targetId: query.get('targetId') ?? '', changeGroupId: query.get('changeGroupId') ?? '' };
  });
  // 글자를 치는 동안에는 조회하지 않고 [조회]·Enter 로 반영한다 (선택 상자·날짜는 바로 반영)
  const [userInput, setUserInput] = useState('');
  const set = (patch: Partial<Filters>) => setFilters((current) => ({ ...current, ...patch }));

  // 값이 있는 조건만 보낸다. params 가 바뀌면 그리드가 첫 페이지부터 다시 조회한다
  const params = useMemo(() => Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== '')), [filters]);
  const filtered = Object.keys(params).length > 0;

  return (
    <>
      <PageHeader category={t('데이터 품질 관리')} title={t('모델 변경 이력')}
        description={t('물리 모델·논리 모델·논리·물리 매핑의 변경 내역입니다. 행을 누르면 변경 전후와 같은 작업에서 함께 바뀐 항목을 볼 수 있습니다.')} />

      <div className="history-filters">
        <select className="search-column-select" aria-label={t('대상 유형')} value={filters.targetType} onChange={(e) => set({ targetType: e.target.value, targetId: '' })}>
          <option value="">{t('전체 대상')}</option>
          {TARGET_TYPES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <select className="search-column-select" aria-label={t('작업')} value={filters.action} onChange={(e) => set({ action: e.target.value })}>
          <option value="">{t('전체 작업')}</option>
          {ACTIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <input type="date" className="search-input history-date" aria-label={t('시작일')} value={filters.from} max={filters.to || undefined} onChange={(e) => set({ from: e.target.value })} />
        <span className="gh-muted">~</span>
        <input type="date" className="search-input history-date" aria-label={t('종료일')} value={filters.to} min={filters.from || undefined} onChange={(e) => set({ to: e.target.value })} />
        <input type="text" className="search-input history-user" placeholder={t('변경 사용자 아이디')} aria-label={t('변경 사용자')} value={userInput}
          onChange={(e) => setUserInput(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') set({ userId: userInput.trim() }); }} />
        <button type="button" className="grd-select" onClick={() => set({ userId: userInput.trim() })}>{t('조회')}</button>
        {filtered && <button type="button" className="btn" onClick={() => { setUserInput(''); setFilters(EMPTY); }}>{t('조건 초기화')}</button>}
      </div>
      {(filters.targetId || filters.changeGroupId) && (
        <div className="history-chips">
          {filters.targetId && (
            <span className="gh-label gh-label-accent">
              {t('객체별 이력')} : {targetTypeLabel(filters.targetType)} #{filters.targetId}
              <button type="button" className="history-chip-clear" aria-label={t('객체 조건 해제')} onClick={() => set({ targetId: '' })}>×</button>
            </span>
          )}
          {filters.changeGroupId && (
            <span className="gh-label gh-label-accent">
              {t('변경 그룹')} : <Mono>{filters.changeGroupId.slice(0, 8)}</Mono>
              <button type="button" className="history-chip-clear" aria-label={t('변경 그룹 조건 해제')} onClick={() => set({ changeGroupId: '' })}>×</button>
            </span>
          )}
        </div>
      )}

      <div id="gridContainer">
        <Grid
          id="modelHistoryGrid"
          columns={modelHistoryColumns}
          dataUrl="/api/modelHistory"
          params={params}
          emptyText={filtered ? t('조건에 맞는 변경 이력이 없습니다.') : t('아직 변경 이력이 없습니다. 모델을 변경하면 여기에 남습니다.')}
          onRowClick={(row) => openHistoryDetail(row.id, { onTarget: (summary) => set({ targetType: summary.targetType, targetId: String(summary.targetId), changeGroupId: '' }) })}
          renderCell={(field, row) => {
            switch (field) {
              case 'changedAt':
                return <DateTime value={row.changedAt} />;
              case 'userId':
                return <User name={row.userId} />;
              case 'targetType':
                return targetTypeLabel(row.targetType);
              case 'targetName':
                return row.targetName ? <span title={`#${row.targetId}`}>{row.targetName}</span> : <Muted>#{row.targetId}</Muted>;
              case 'action':
                return <Label variant={ACTION_VARIANT[row.action] ?? ''}>{actionLabel(row.action)}</Label>;
              case 'changedFields':
                return row.changedFields?.length ? changedLabels(row.changedFields).join(', ') : <Muted>-</Muted>;
              case 'source':
                return row.source === 'USER' ? <Muted>{sourceLabel(row.source)}</Muted> : <Label variant="attention">{sourceLabel(row.source)}</Label>;
              case 'changeGroupId':
                return (
                  <button type="button" className="btn btn-sm" title={t('같은 작업의 변경만 보기')}
                    onClick={(e) => { e.stopPropagation(); set({ changeGroupId: row.changeGroupId }); }}>
                    <Mono>{String(row.changeGroupId).slice(0, 8)}</Mono>
                  </button>
                );
              default:
                return undefined;
            }
          }}
        />
      </div>
    </>
  );
}
