import { useEffect, useState } from 'react';
import { ApiError, api } from '../../lib/api';
import { t } from '../../lib/i18n';
import { termLabel, type Term } from './terms';
import '../../styles/ai-dictionary.css';
import '../../styles/term-relink.css';

/** 일괄 연결 대상 컬럼 (컬럼 관리 목록의 행) */
export interface BulkTermColumn {
  id: number;
  tableName?: string;
  columnName?: string;
  isApproval?: string;
  standardTerms?: { id: number; commonStandardTermName?: string; commonStandardTermAbbreviation?: string } | null;
}

interface Resolved { input: string; status: 'MATCHED' | 'AMBIGUOUS' | 'NOT_FOUND'; terms?: Term[] }
type Plan = { column: BulkTermColumn; term: Term | null; outcome: 'LINK' | 'CHANGE' | 'SAME' | 'NOT_FOUND' | 'AMBIGUOUS' | 'APPROVED' };

/**
 * 표준 용어 일괄 연결·변경 (DEC-26) : 고른 컬럼마다 컬럼명과 영문 약어가 같은 표준 용어를 찾아 계획을 보이고, 확인하면 한 번에 반영한다.
 * 서버가 컬럼 ID 순서로 잠그고 하나라도 거부되면(승인된 컬럼·그 사이 바뀐 연결) 아무것도 바꾸지 않는다. 같은 이름의 용어가 없거나 여럿이면 빼고 알린다.
 */
export function BulkTermLink({ columns, onCancel, onDone }: { columns: BulkTermColumn[]; onCancel: () => void; onDone: (changed: number) => void }) {
  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const names = columns.map((c) => String(c.columnName ?? ''));
    api<{ items: Resolved[] }>('/api/selectColumn/term/resolve', { method: 'POST', body: names, silent: true }).then((res) => {
      setPlans(columns.map((column, i) => {
        if (column.isApproval === 'Y') return { column, term: null, outcome: 'APPROVED' };
        const found = res.items[i];
        // 영문 약어가 컬럼명과 같은 용어만 자동으로 고른다 (용어명으로만 맞은 것은 빼고 알린다)
        const exact = (found?.terms ?? []).filter((term) => String(term.commonStandardTermAbbreviation ?? '').toUpperCase() === String(column.columnName ?? '').toUpperCase());
        if (exact.length === 0) return { column, term: null, outcome: 'NOT_FOUND' };
        if (exact.length > 1) return { column, term: null, outcome: 'AMBIGUOUS' };
        const term = exact[0];
        if (column.standardTerms?.id === term.id) return { column, term, outcome: 'SAME' };
        return { column, term, outcome: column.standardTerms ? 'CHANGE' : 'LINK' };
      }));
    }, (e) => setError(e instanceof ApiError ? e.message : t('표준 용어를 찾지 못했습니다.')));
  }, [columns]);

  const apply = (plans ?? []).filter((p) => p.outcome === 'LINK' || p.outcome === 'CHANGE');
  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const items = apply.map((p) => ({ columnId: p.column.id, termId: p.term!.id, baseTermId: p.column.standardTerms?.id ?? null }));
      const results = await api<{ changed: boolean }[]>('/api/saveColumn/terms/bulk', { method: 'PUT', body: { items }, silent: true });
      onDone(results.filter((r) => r.changed).length);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('표준 용어를 바꾸지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  const LABEL: Record<Plan['outcome'], string> = {
    LINK: t('새로 연결'), CHANGE: t('연결 변경'), SAME: t('이미 같은 용어'), NOT_FOUND: t('같은 영문 약어의 용어 없음'), AMBIGUOUS: t('같은 영문 약어의 용어가 여럿'), APPROVED: t('승인된 컬럼 (제외)'),
  };
  return (
    <div className="term-relink">
      <p className="gh-muted">{t('고른 컬럼마다 컬럼명과 영문 약어가 같은 표준 용어를 연결합니다. 연결만 바꾸고 컬럼명·타입·설명·업무 설명은 그대로입니다. 하나라도 거부되면 아무것도 바꾸지 않습니다.')}</p>
      {!plans && !error && <p className="gh-muted">{t('표준 용어를 찾는 중…')}</p>}
      {plans && (
        // 좁은 화면 : 대화상자 안에서 표만 가로로 스크롤한다 (마지막 열이 잘리지 않게)
        <div style={{ overflowX: 'auto' }}>
        <table className="term-relink-table">
          <thead><tr><th>{t('컬럼')}</th><th>{t('현재 표준 용어')}</th><th>{t('새 연결')}</th><th>{t('처리 방식')}</th></tr></thead>
          <tbody>
            {plans.map((p) => (
              <tr key={p.column.id}>
                <td className="gh-mono">{p.column.tableName}.{p.column.columnName}</td>
                <td>{p.column.standardTerms ? termLabel(p.column.standardTerms) : <span className="gh-muted">{t('연결 없음')}</span>}</td>
                <td>{p.term ? termLabel(p.term) : '-'}</td>
                <td><span className={`gh-label ${p.outcome === 'LINK' || p.outcome === 'CHANGE' ? 'gh-label-accent' : ''}`}>{LABEL[p.outcome]}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
      {error && <div className="ai-dict-callout is-error" role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        <button type="button" className="btn" onClick={onCancel} disabled={saving}>{t('취소')}</button>
        <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={saving || apply.length === 0}>
          {saving ? t('저장 중…') : t('{0}개 컬럼에 반영', apply.length)}
        </button>
      </div>
    </div>
  );
}
