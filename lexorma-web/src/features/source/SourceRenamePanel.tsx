import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, type LabelVariant } from '../../components/grid/cells';
import { api, enc } from '../../lib/api';
import { baseQuery } from '../../lib/deletion';
import { runWithReview, type Snapshot } from '../../components/conflict/conflictActions';
import { formatDateTime } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Pager } from './shared';

/**
 * 원본 이름 변경 매핑 (DEC-06) : 원본에서 이름이 바뀐 TABLE·컬럼을 사용자가 확인해 기존 모델 객체와 잇는다. 후보는 제안일 뿐이고 [확정] 해야 저장된다.
 * 매핑만 저장하며 원본 DB·물리 모델(이름·승인 상태)은 바꾸지 않는다. 변경 요청은 화면이 본 기준(연결·반영 순서)을 보내 그 사이 새 수집이 반영됐으면 409 로 거절된다.
 */
type ColumnStatus = 'MAPPED' | 'SOURCE_MISSING' | 'MODEL_MISSING' | 'TABLE_INVALID';
type TargetType = 'TABLE' | 'COLUMN';
type ChangeType = 'CONFIRM' | 'CHANGE' | 'RENAME' | 'RELEASE';

interface TableCandidate {
  mappingId: number; tableId: number; schemaName: string | null; tableName: string; tableKorName: string | null; removedSourceTableName: string;
  sourceTableId: number; sourceSchemaName: string; sourceTableName: string; matchedColumns: number; sourceColumns: number; modelColumns: number; score: number; ambiguous: boolean;
}
interface ColumnCandidate {
  mappingId: number; sourceTableId: number; sourceTableName: string; tableId: number; tableName: string; sourceColumnName: string; columnId: number; columnName: string;
  approved: boolean; basis: string[]; signature: string; ambiguous: boolean;
}
interface RemovedTable { mappingId: number; tableId: number; tableName: string; removedSourceTableName: string; hasCandidate: boolean }
interface Candidates { connectionId: number | null; reflectedOrder: number | null; tables: TableCandidate[]; columns: ColumnCandidate[]; removedTables: RemovedTable[] }
interface ColumnMapping {
  id: number; tableMappingId: number; sourceTableId: number | null; sourceTableName: string | null; sourceColumnName: string; compareName: string; tableId: number | null;
  tableName: string | null; columnId: number | null; columnName: string | null; reflectedOrder: number; status: ColumnStatus; confirmedAt: string | null; confirmedBy: string | null;
  /** 해제 기준값 (M8-C4c-1) */
  modifiedAt: string | null;
}
interface History {
  id: number; tableMappingId: number; targetType: TargetType; changeType: ChangeType; before: Record<string, unknown> | null; after: Record<string, unknown> | null;
  changedAt: string; changedBy: string | null;
}
interface HistoryPage { rows: History[]; total: number }

const COLUMN_STATUS: Record<ColumnStatus, { label: string; variant: LabelVariant }> = {
  MAPPED: { label: t('매핑됨'), variant: 'success' },
  SOURCE_MISSING: { label: t('원본 없음'), variant: 'danger' },
  MODEL_MISSING: { label: t('모델 없음'), variant: 'danger' },
  TABLE_INVALID: { label: t('TABLE 매핑 무효'), variant: 'attention' },
};
const CHANGE: Record<ChangeType, string> = {
  CONFIRM: t('확정'),
  CHANGE: t('변경'),
  RENAME: t('이름 변경'),
  RELEASE: t('해제'),
};
const HISTORY_PAGE = 20;
const KEY = 'source-rename';

/** 이력 한 쪽의 요약 : 원본 이름 → 모델 이름 */
function describe(targetType: TargetType, value: Record<string, unknown> | null) {
  if (!value) return '-';
  const source = targetType === 'TABLE' ? value.sourceTableName : `${value.sourceTableName ?? '?'}.${value.sourceColumnName ?? '?'}`;
  const model = targetType === 'TABLE' ? value.tableName : `${value.tableName ?? '?'}.${value.columnName ?? '?'}`;
  return `${String(source ?? '?')} → ${String(model ?? '-')}`;
}

export function SourceRenamePanel({ canManage, onChanged }: { canManage: boolean; onChanged: () => void }) {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const [historyPage, setHistoryPage] = useState(0);
  const candidates = useQuery({ queryKey: [KEY, 'candidates'], queryFn: () => api<Candidates>('/api/sourceMapping/renames', { silent: true }) });
  const mappings = useQuery({ queryKey: [KEY, 'columns'], queryFn: () => api<ColumnMapping[]>('/api/sourceMapping/renames/columns', { silent: true }) });
  const history = useQuery({
    queryKey: [KEY, 'history', historyPage],
    queryFn: () => api<HistoryPage>(`/api/sourceMapping/renames/history?page=${historyPage}&size=${HISTORY_PAGE}`, { silent: true }),
  });
  const data = candidates.data;

  const reload = () => {
    void queryClient.invalidateQueries({ queryKey: [KEY] });
    onChanged();
  };
  const basis = { connectionId: data?.connectionId ?? null, reflectedOrder: data?.reflectedOrder ?? null };

  const confirmTable = (c: TableCandidate) => {
    dialog.confirm(t('모델 TABLE {0} 의 매핑을 사라진 원본 {1} 에서 새 원본 {2} 로 옮기시겠습니까? 모델 TABLE 의 이름·승인 상태는 바뀌지 않습니다.',
      c.tableName, c.removedSourceTableName, `${c.sourceSchemaName}.${c.sourceTableName}`), async () => {
      await api('/api/sourceMapping/renames/table', { method: 'PUT', body: { ...basis, mappingId: c.mappingId, sourceTableId: c.sourceTableId } });
      reload();
    });
  };
  const confirmColumn = (c: ColumnCandidate) => {
    dialog.confirm(t('원본 컬럼 {0} 을(를) 모델 컬럼 {1} 과(와) 같은 컬럼으로 확정하시겠습니까? 모델 컬럼의 이름·승인 상태는 바뀌지 않습니다.',
      `${c.sourceTableName}.${c.sourceColumnName}`, `${c.tableName}.${c.columnName}`), async () => {
      // 후보는 원본에만 있는 컬럼 = 기존 컬럼 매핑 없음 (신규 등록, M8-FINAL) — 그 사이 다른 사용자가 확정했으면 409 : 공통 알림 뒤 후보를 다시 읽는다 (자동으로 다시 보내지 않는다)
      try {
        await api('/api/sourceMapping/renames/columns', { method: 'PUT', body: { ...basis, tableMappingId: c.mappingId, sourceColumnName: c.sourceColumnName, columnId: c.columnId, mappingId: null, modifiedAt: null } });
      } catch {
        reload();
        return;
      }
      reload();
    });
  };
  const release = (m: ColumnMapping) => {
    dialog.confirm(t('컬럼 매핑 {0} ↔ {1} 을(를) 해제하시겠습니까? 다시 이름으로 짝짓습니다.', m.sourceColumnName, m.columnName ?? '-'), async () => {
      // 화면이 본 매핑 행 그대로일 때만 해제 (M8-C4c-1). 409 (같은 원본 컬럼을 다른 모델 컬럼으로 다시 확정 등) : 확인한 매핑 ↔ 최신 매핑을 보여 주고
      // 사용자가 다시 해제를 고른다 (M8-C4c-2 — 자동 재시도 없음). 매핑이 없어졌으면 다시 해제할 수 없다
      const snapshot = (c: ColumnMapping | null): Snapshot => ({
        modifiedAt: c?.modifiedAt ?? null,
        fields: [
          { key: 'source', label: t('원본 컬럼'), value: c ? `${c.sourceTableName}.${c.sourceColumnName}` : t('해제됨') },
          { key: 'model', label: t('모델 컬럼'), value: c ? `${c.tableName ?? '?'}.${c.columnName ?? '?'}` : t('해제됨') },
        ],
      });
      let latest: ColumnMapping | null = m;
      try {
        const ok = await runWithReview({
          dialog, title: t('컬럼 매핑 해제'), intro: t('확인한 뒤 이 매핑이 바뀌어 해제하지 않았습니다.'),
          seen: snapshot(m),
          load: async () => {
            latest = (await api<ColumnMapping[]>('/api/sourceMapping/renames/columns', { silent: true })).find((c) => c.id === m.id) ?? null;
            return snapshot(latest);
          },
          act: async (state) => { await api(`/api/sourceMapping/renames/columns/${enc(m.id)}${baseQuery(state.modifiedAt)}`, { method: 'DELETE', conflict: 'handled' }); },
          block: () => (latest == null ? t('다른 사용자가 이미 이 매핑을 해제했습니다.') : undefined),
          note: () => t('최신 매핑을 확인한 뒤 해제할지 다시 결정해 주세요.'),
          retryLabel: t('최신 매핑 해제'), onClose: reload,
        });
        if (ok) reload();
      } catch { /* 잠금 충돌 등 : 공통 알림 */ }
    });
  };

  return (
    <section className="source-card" aria-label={t('이름 변경 매핑')}>
      <h3>{t('이름 변경 매핑')}</h3>
      <p className="gh-muted">{t('원본에서 이름이 바뀐 TABLE·컬럼을 기존 모델 객체와 잇습니다. 후보는 컬럼 이름·구조가 겹치는 정도로 제안만 하며, 확정해야 저장됩니다. 확정한 컬럼은 비교·가져오기에서 같은 컬럼으로 봅니다.')}</p>
      {candidates.isError ? <p role="alert">{t('이름 변경 후보를 불러오지 못했습니다.')} {candidates.error.message}</p>
        : !data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : (
          <>
            <h4>{t('TABLE 이름 변경 후보')}</h4>
            {data.tables.length === 0 ? <p className="gh-muted">{t('원본에서 사라진 매핑의 이름 변경 후보가 없습니다.')}</p> : (
              <div className="source-table-wrap">
                <table className="source-table">
                  <thead><tr><th>{t('모델 TABLE')}</th><th>{t('사라진 원본')}</th><th>{t('새 원본 후보')}</th><th>{t('판단 근거')}</th>{canManage && <th />}</tr></thead>
                  <tbody>
                    {data.tables.map((c) => (
                      <tr key={`${c.mappingId}-${c.sourceTableId}`}>
                        <td><span className="gh-mono">{c.schemaName}.{c.tableName}</span>{c.tableKorName && <div className="gh-muted source-sub">{c.tableKorName}</div>}</td>
                        <td className="gh-mono">{c.removedSourceTableName}</td>
                        <td><span className="gh-mono">{c.sourceSchemaName}.{c.sourceTableName}</span>{c.ambiguous && <div><Label variant="attention">{t('후보 여럿')}</Label></div>}</td>
                        <td className="gh-muted">{t('같은 이름 컬럼 {0}개 (원본 {1} · 모델 {2}, {3}%)', c.matchedColumns, c.sourceColumns, c.modelColumns, Math.round(c.score * 100))}</td>
                        {canManage && <td><button type="button" className="btn btn-sm btn-primary" onClick={() => confirmTable(c)}>{t('이름 변경 확정')}</button></td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {data.removedTables.some((r) => !r.hasCandidate) && (
              <p className="gh-muted source-sub">
                {t('이름 변경 후보가 없는 원본 없음 TABLE (삭제 후보) : ')}
                {data.removedTables.filter((r) => !r.hasCandidate).map((r) => `${r.tableName} (${r.removedSourceTableName})`).join(', ')}
              </p>
            )}

            <h4>{t('컬럼 이름 변경 후보')}</h4>
            {data.columns.length === 0 ? <p className="gh-muted">{t('매핑된 TABLE 에 컬럼 이름 변경 후보가 없습니다.')}</p> : (
              <div className="source-table-wrap">
                <table className="source-table">
                  <thead><tr><th>{t('원본 컬럼')}</th><th>{t('모델 컬럼')}</th><th>{t('판단 근거')}</th>{canManage && <th />}</tr></thead>
                  <tbody>
                    {data.columns.map((c) => (
                      <tr key={`${c.mappingId}-${c.sourceColumnName}-${c.columnId}`}>
                        <td className="gh-mono">{c.sourceTableName}.{c.sourceColumnName}</td>
                        <td>
                          <span className="gh-mono">{c.tableName}.{c.columnName}</span>
                          {c.approved && <div><Label variant="done">{t('승인')}</Label></div>}
                        </td>
                        <td className="gh-muted">{c.signature}{c.ambiguous && <div><Label variant="attention">{t('후보 여럿')}</Label></div>}</td>
                        {canManage && <td><button type="button" className="btn btn-sm btn-primary" onClick={() => confirmColumn(c)}>{t('매핑 확정')}</button></td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}

      <h4>{t('확정된 컬럼 매핑')}</h4>
      {mappings.isError ? <p role="alert">{mappings.error.message}</p>
        : !mappings.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : mappings.data.length === 0 ? <p className="gh-muted">{t('확정된 컬럼 매핑이 없습니다.')}</p> : (
          <div className="source-table-wrap">
            <table className="source-table">
              <thead><tr><th>{t('원본 컬럼')}</th><th>{t('모델 컬럼')}</th><th>{t('상태')}</th><th>{t('확정')}</th>{canManage && <th />}</tr></thead>
              <tbody>
                {mappings.data.map((m) => (
                  <tr key={m.id}>
                    <td className="gh-mono">{m.sourceTableName ?? '-'}.{m.sourceColumnName}</td>
                    <td className="gh-mono">{m.tableName ?? '-'}.{m.columnName ?? '-'}</td>
                    <td><Label variant={COLUMN_STATUS[m.status].variant} dot>{COLUMN_STATUS[m.status].label}</Label></td>
                    <td className="gh-muted">{formatDateTime(m.confirmedAt)} · {m.confirmedBy ?? '-'}</td>
                    {canManage && <td><button type="button" className="btn btn-sm" onClick={() => release(m)}>{t('해제')}</button></td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

      <h4>{t('매핑 변경 이력')}</h4>
      {history.isError ? <p role="alert">{history.error.message}</p>
        : !history.data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : history.data.rows.length === 0 ? <p className="gh-muted">{t('매핑 변경 이력이 없습니다.')}</p> : (
          <>
            <div className="source-table-wrap">
              <table className="source-table">
                <thead><tr><th>{t('일시')}</th><th>{t('대상')}</th><th>{t('변경')}</th><th>{t('변경 전')}</th><th>{t('변경 후')}</th><th>{t('사용자')}</th></tr></thead>
                <tbody>
                  {history.data.rows.map((h) => (
                    <tr key={h.id}>
                      <td className="gh-muted">{formatDateTime(h.changedAt)}</td>
                      <td>{h.targetType === 'TABLE' ? 'TABLE' : t('컬럼')}</td>
                      <td>{CHANGE[h.changeType]}</td>
                      <td className="gh-mono">{describe(h.targetType, h.before)}</td>
                      <td className="gh-mono">{describe(h.targetType, h.after)}</td>
                      <td className="gh-muted">{h.changedBy ?? '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={historyPage} size={HISTORY_PAGE} total={history.data.total} onPage={setHistoryPage} />
          </>
        )}
    </section>
  );
}
