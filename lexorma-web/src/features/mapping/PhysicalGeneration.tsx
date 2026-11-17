import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Label } from '../../components/grid/cells';
import { ApiError, api, enc } from '../../lib/api';
import { t } from '../../lib/i18n';
import type { Schema } from '../schema/schemas';

interface EntitySummary { id: number; entityName: string }
interface ColumnPlan { attributeId: number; attributeName: string; columnName: string; dataType: string; maxLength: number | null; precision: number | null; scale: number | null; pk: boolean; nullable: boolean; refTableName: string | null }
interface TablePlan { entityId: number; entityName: string; status: 'CREATE' | 'SKIP' | 'ERROR'; tableName: string | null; schemaName: string; columns: ColumnPlan[]; errors: string[]; warnings: string[] }
interface Plan { tables: TablePlan[]; creatable: boolean; planToken: string }

const typeText = (c: ColumnPlan) => c.maxLength != null ? `${c.dataType}(${c.maxLength})` : c.precision != null ? `${c.dataType}(${c.precision}${c.scale != null ? `,${c.scale}` : ''})` : c.dataType;

/**
 * 논리 → 물리 선택 생성 (DEC-10) : 엔터티 고르기 → 미리보기(테이블·컬럼·PK·FK, 막는 문제·경고) → 확인 → 미승인 테이블·컬럼과 매핑 생성.
 * 미리보기 뒤 모델이 바뀌면 서버가 409 로 거절한다(다시 미리보기). 이미 매핑된 엔터티는 건너뛴다 — 기존 테이블을 덮어쓰지 않는다
 */
export function PhysicalGeneration({ modelId, schemas, onDone }: { modelId: number; schemas: Schema[]; onDone: (tables: number) => void }) {
  const { data: entities = [] } = useQuery({ queryKey: ['logical-entities', modelId], queryFn: () => api<EntitySummary[]>(`/api/logical/model/${enc(modelId)}/entity`) });
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [schemaId, setSchemaId] = useState(String(schemas.find((s) => s.isDefault)?.id ?? ''));
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const body = (planToken?: string) => ({ entityIds: [...picked], schemaId: schemaId ? Number(schemaId) : null, planToken: planToken ?? null });

  const preview = async () => {
    setBusy(true); setError(null);
    try { setPlan(await api<Plan>('/api/modelMapping/generate/preview', { method: 'POST', body: body(), silent: true })); }
    catch (e) { setError(e instanceof ApiError ? e.message : t('미리보기를 만들지 못했습니다.')); }
    finally { setBusy(false); }
  };
  const apply = async () => {
    if (!plan) return;
    setBusy(true); setError(null);
    try { onDone((await api<{ tables: number }>('/api/modelMapping/generate', { method: 'POST', body: body(plan.planToken), silent: true })).tables); }
    catch (e) { setError(e instanceof ApiError ? e.message : t('물리 테이블을 만들지 못했습니다.')); setPlan(null); }
    finally { setBusy(false); }
  };
  const toggle = (id: number) => { setPlan(null); setPicked((prev) => { const next = new Set(prev); if (next.has(id)) next.delete(id); else next.add(id); return next; }); };

  return (
    <div className="term-relink">
      <p className="gh-muted">{t('고른 엔터티로 미승인 물리 테이블·컬럼을 만들고 매핑을 연결합니다. 테이블명은 표준 단어, 컬럼은 속성의 표준 용어·도메인을 따릅니다. 이미 매핑된 엔터티와 같은 이름의 테이블은 덮어쓰지 않습니다.')}</p>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
        <select className="form-control" style={{ maxWidth: 220 }} aria-label={t('스키마')} value={schemaId} onChange={(e) => { setSchemaId(e.target.value); setPlan(null); }}>
          {schemas.map((s) => <option key={s.id} value={s.id}>{s.schemaName}{s.isDefault ? t(' (기본)') : ''}</option>)}
        </select>
      </div>
      <ul style={{ maxHeight: 180, overflow: 'auto', listStyle: 'none', padding: 0, columns: 2 }} aria-label={t('엔터티')}>
        {entities.map((e) => (
          <li key={e.id}><label><input type="checkbox" checked={picked.has(e.id)} onChange={() => toggle(e.id)} /> {e.entityName}</label></li>
        ))}
      </ul>
      {plan && (
        <div style={{ overflowX: 'auto' }}>
          {plan.tables.map((tp) => (
            <section key={tp.entityId} style={{ marginBottom: 8 }}>
              <p><strong>{tp.entityName}</strong> → <span className="gh-mono">{tp.tableName ?? '-'}</span>{' '}
                <Label variant={tp.status === 'CREATE' ? 'success' : tp.status === 'SKIP' ? 'attention' : 'danger'}>{tp.status === 'CREATE' ? t('생성') : tp.status === 'SKIP' ? t('건너뜀') : t('오류')}</Label></p>
              {[...tp.errors, ...tp.warnings].length > 0 && <ul className="gh-muted">{[...tp.errors, ...tp.warnings].map((m) => <li key={m}>{m}</li>)}</ul>}
              {tp.columns.length > 0 && (
                <table className="term-relink-table">
                  <thead><tr><th>{t('속성')}</th><th>{t('컬럼')}</th><th>{t('데이터 타입')}</th><th>PK</th><th>NULL</th><th>{t('참조 테이블')}</th></tr></thead>
                  <tbody>{tp.columns.map((c) => (
                    <tr key={c.attributeId}><td>{c.attributeName}</td><td className="gh-mono">{c.columnName}</td><td className="gh-mono">{typeText(c)}</td><td>{c.pk ? 'Y' : ''}</td><td>{c.nullable ? 'Y' : 'N'}</td><td className="gh-mono">{c.refTableName ?? ''}</td></tr>
                  ))}</tbody>
                </table>
              )}
            </section>
          ))}
        </div>
      )}
      {error && <div className="ai-dict-callout is-error" role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        <button type="button" className="btn" disabled={busy || picked.size === 0} onClick={() => void preview()}>{t('미리보기')}</button>
        <button type="button" className="btn btn-primary" disabled={busy || !plan?.creatable} onClick={() => void apply()}>{t('물리 테이블 생성')}</button>
      </div>
    </div>
  );
}
