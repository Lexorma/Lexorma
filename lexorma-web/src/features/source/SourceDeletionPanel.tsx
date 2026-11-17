import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label } from '../../components/grid/cells';
import { api } from '../../lib/api';
import { t } from '../../lib/i18n';

/**
 * 원본 삭제 후보 선택 반영 (DEC-04) : 원본에서 없어진 TABLE·컬럼을 삭제 후보로 보이고, 사용자가 고르고 계획을 확인한 미승인 모델 객체만 지운다. 자동 삭제·원본 DB 변경 없음.
 * 이름 변경 후보는 삭제 후보에 넣지 않는다 (위 이름 변경 매핑에서 확인). 수집 객체가 급감했으면(DEC-17) 확인을 따로 받는다. 확정 직전 프로젝트 전체 모델 버전이 남는다.
 */
type Kind = 'TABLE' | 'COLUMN';
type Blocker = 'APPROVED' | 'APPROVED_COLUMN' | 'LOGICAL_MAPPED' | 'VIEW_REFERENCE' | 'REFERENCED' | 'IN_USE' | 'NOT_FOUND';
interface Impact { columns: number; indexes: number; constraints: number; relations: number; bizDescriptions: number; termLinks: number; aiProposals: number }
interface Candidate {
  id: string; kind: Kind | null; tableMappingId: number | null; tableId: number | null; schemaName: string | null; tableName: string | null; columnId: number | null;
  columnName: string | null; sourceName: string | null; selectable: boolean; blockers: Blocker[]; details: string[]; impact: Impact | null;
}
interface Candidates { connectionId: number | null; reflectedOrder: number | null; dropSuspected: boolean; renameCandidates: number; items: Candidate[] }
interface Plan { connectionId: number; reflectedOrder: number; planToken: string; requestId: string; applicable: boolean; dropSuspected: boolean; items: Candidate[] }
interface Result { modelVersionName: string; tablesDeleted: number; columnsDeleted: number }

const BLOCKER: Record<Blocker, string> = {
  APPROVED: t('승인된 객체 (승인 취소 후)'),
  APPROVED_COLUMN: t('승인된 컬럼이 있음'),
  LOGICAL_MAPPED: t('논리 모델에 매핑됨'),
  VIEW_REFERENCE: t('VIEW 가 참조'),
  REFERENCED: t('다른 TABLE 의 관계가 참조'),
  IN_USE: t('관계·인덱스·제약조건이 사용'),
  NOT_FOUND: t('지금 후보에 없음'),
};
const KEY = ['source-rename', 'deletions'];

function impactText(c: Candidate) {
  const i = c.impact;
  if (!i) return '-';
  const parts = c.kind === 'TABLE'
    ? [t('컬럼 {0}', i.columns), t('인덱스 {0}', i.indexes), t('제약조건 {0}', i.constraints), t('관계 {0}', i.relations)]
    : i.relations ? [t('관계 {0}', i.relations)] : [];
  if (i.bizDescriptions) parts.push(t('업무 설명 {0}', i.bizDescriptions));
  if (i.termLinks) parts.push(t('표준 용어 연결 {0}', i.termLinks));
  if (i.aiProposals) parts.push(t('AI 제안 {0}', i.aiProposals));
  return parts.join(' · ') || '-';
}

export function SourceDeletionPanel({ canManage, onChanged }: { canManage: boolean; onChanged: () => void }) {
  const dialog = useDialog();
  const queryClient = useQueryClient();
  const candidates = useQuery({ queryKey: KEY, queryFn: () => api<Candidates>('/api/sourceMapping/deletions', { silent: true }) });
  const [chosen, setChosen] = useState<string[]>([]);
  const [acknowledge, setAcknowledge] = useState(false);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(false);
  const data = candidates.data;

  const toggle = (id: string) => {
    setPlan(null);
    setChosen((list) => list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);
  };
  const preview = async () => {
    if (!data) return;
    setBusy(true);
    try {
      setPlan(await api<Plan>('/api/sourceMapping/deletions/plan', { method: 'POST', body: { connectionId: data.connectionId, reflectedOrder: data.reflectedOrder, items: chosen } }));
    } catch { /* 공통 알림 */ } finally {
      setBusy(false);
    }
  };
  const apply = () => {
    if (!plan) return;
    dialog.confirm(t('고른 {0}개 항목을 물리 모델에서 지우시겠습니까? 원본 DB 는 바뀌지 않고, 지우기 직전 모델 버전이 남습니다.', plan.items.length), async () => {
      const result = await api<Result>('/api/sourceMapping/deletions/apply', {
        method: 'POST',
        body: { connectionId: plan.connectionId, reflectedOrder: plan.reflectedOrder, items: plan.items.map((i) => i.id), acknowledgeDrop: acknowledge, planToken: plan.planToken, requestId: plan.requestId },
      });
      setPlan(null);
      setChosen([]);
      dialog.alert(t('TABLE {0}개 · 컬럼 {1}개를 지웠습니다. 직전 상태는 모델 버전 {2} 에 있습니다.', result.tablesDeleted, result.columnsDeleted, result.modelVersionName));
      void queryClient.invalidateQueries({ queryKey: ['source-rename'] });
      onChanged();
    });
  };

  return (
    <section className="source-card" aria-label={t('원본 삭제 반영')}>
      <h3>{t('원본 삭제 반영')}</h3>
      <p className="gh-muted">{t('원본에서 없어진 TABLE·컬럼을 삭제 후보로 보입니다. 고르고 계획을 확인한 미승인 객체만 물리 모델에서 지웁니다. 이름 변경 후보는 여기에 없습니다 (위 이름 변경 매핑에서 확인).')}</p>
      {candidates.isError ? <p role="alert">{t('삭제 후보를 불러오지 못했습니다.')} {candidates.error.message}</p>
        : !data ? <p role="status">{t('불러오는 중입니다.')}</p>
        : (
          <>
            {data.dropSuspected && (
              <div className="source-warn" role="alert">
                {t('기준 수집의 객체 수가 직전 정상 수집의 절반 미만입니다. 수집 범위·권한 문제일 수 있으니 수집 결과를 먼저 확인하세요.')}
                {canManage && (
                  <label className="source-sub"><input type="checkbox" checked={acknowledge} onChange={(e) => setAcknowledge(e.target.checked)} /> {t('급감을 확인했고 이 수집 기준으로 지웁니다')}</label>
                )}
              </div>
            )}
            {data.renameCandidates > 0 && <p className="gh-muted source-sub">{t('삭제 후보에서 뺀 이름 변경 후보 {0}건', data.renameCandidates)}</p>}
            {data.items.length === 0 ? <p className="gh-muted">{t('삭제 후보가 없습니다.')}</p> : (
              <div className="source-table-wrap">
                <table className="source-table">
                  <thead><tr>{canManage && <th />}<th>{t('대상')}</th><th>{t('사라진 원본')}</th><th>{t('함께 사라지는 것')}</th><th>{t('지울 수 없는 이유')}</th></tr></thead>
                  <tbody>
                    {data.items.map((c) => (
                      <tr key={c.id}>
                        {canManage && <td><input type="checkbox" aria-label={c.id} disabled={!c.selectable} checked={chosen.includes(c.id)} onChange={() => toggle(c.id)} /></td>}
                        <td>
                          <Label>{c.kind === 'TABLE' ? 'TABLE' : t('컬럼')}</Label>{' '}
                          <span className="gh-mono">{c.schemaName}.{c.tableName}{c.columnName ? `.${c.columnName}` : ''}</span>
                        </td>
                        <td className="gh-mono">{c.sourceName ?? '-'}</td>
                        <td className="gh-muted">{impactText(c)}</td>
                        <td>
                          {c.blockers.length === 0 ? <span className="gh-muted">-</span>
                            : <span className="source-labels">{c.blockers.map((b) => <Label key={b} variant="danger">{BLOCKER[b]}</Label>)}</span>}
                          {c.details.length > 0 && <div className="gh-muted source-sub">{c.details.join(', ')}</div>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {canManage && data.items.length > 0 && (
              <div className="source-actions">
                <button type="button" className="btn" disabled={busy || chosen.length === 0} onClick={() => void preview()}>{t('삭제 계획 미리보기')}</button>
                {plan && (
                  <button type="button" className="btn btn-danger" disabled={!plan.applicable || (plan.dropSuspected && !acknowledge)} onClick={apply}>{t('삭제 확정')}</button>
                )}
              </div>
            )}
            {plan && (
              <p className={plan.applicable ? 'gh-muted' : 'source-warn'} role="status">
                {plan.applicable ? t('계획 : {0}개 항목을 지웁니다. 확정하면 서버가 잠근 뒤 같은 계획인지 다시 확인합니다.', plan.items.length)
                  : t('지울 수 없는 항목이 있습니다. 미리보기에서 확인해주세요.')}
              </p>
            )}
          </>
        )}
    </section>
  );
}
