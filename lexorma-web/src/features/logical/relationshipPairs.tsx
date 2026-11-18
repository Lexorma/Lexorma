import { useEffect, useState } from 'react';
import { Label, type LabelVariant } from '../../components/grid/cells';
import { ApiError, api } from '../../lib/api';
import { t } from '../../lib/i18n';
import type { PairState, PairStatus, PairWarning, RelationshipPairs } from './model';
import '../../styles/logical.css';

const STATUS: Record<PairStatus, { label: string; variant: LabelVariant }> = {
  NONE: { label: t('속성 짝 없음'), variant: '' },
  PARTIAL: { label: t('속성 짝 일부'), variant: 'attention' },
  COMPLETE: { label: t('속성 짝 완성'), variant: 'success' },
  PARENT_KEY_MISMATCH: { label: t('부모 식별자 불일치'), variant: 'danger' },
  PARENT_KEY_MISSING: { label: t('부모 주 식별자 없음'), variant: 'danger' },
};

export const PAIR_WARNINGS: Record<PairWarning, string> = {
  ORDER_DIFFERS: t('짝 순서가 부모 주 식별자 순서와 다릅니다.'),
  SELF_KEY_REUSED: t('자기 참조인데 자식 속성으로 부모 키 속성 자신을 골랐습니다. 상위 항목을 가리키는 별도 속성인지 확인해주세요.'),
  IDENTIFYING_CHILD_NOT_KEY: t('식별 관계인데 자식 속성이 자식 엔터티의 주 식별자에 없습니다.'),
  REQUIRED_CHILD_NULLABLE: t('부모가 필수인 관계인데 자식 속성이 필수가 아닙니다.'),
};

/** 관계 속성 짝 상태 라벨 (목록·ERD·편집 화면 공용). 짝 정보가 없으면(예전 응답) 아무것도 그리지 않는다 */
export function PairStatusLabel({ state }: { state?: PairState | null }) {
  if (!state) return null;
  const item = STATUS[state.status];
  return <Label variant={item.variant} title={state.warnings.map((w) => PAIR_WARNINGS[w]).join('\n') || undefined}>{item.label}{state.warnings.length ? ' !' : ''}</Label>;
}

/** 짝 목록 요약 : "부모 → 자식" 을 순서대로 */
export function PairList({ state }: { state?: PairState | null }) {
  if (!state || state.pairs.length === 0) return <span className="gh-muted">{t('지정된 속성 짝이 없습니다.')}</span>;
  return (
    <ol className="logical-pair-list">
      {state.pairs.map((p) => <li key={p.sortOrder}><span>{p.parentAttributeName}</span> → <span>{p.childAttributeName}</span></li>)}
    </ol>
  );
}

interface Row { parentId: number; childId: number | null }

/**
 * 관계 속성 짝 편집 (M-7 보강 3차, P1) : 부모(출발) 주 식별자 속성마다 자식(도착) 속성을 고르고 순서를 정한다. 저장은 관계 단위 일괄 교체.
 * 행 = 저장된 짝(저장 순서) + 아직 짝이 없는 부모 식별자 속성(식별자 순서). 자식 속성을 고르지 않은 행은 저장하지 않는다 (일부만 이으면 '일부' 로 보인다).
 * 서버가 다시 검사한다 (부모 식별자·자식 엔터티·중복, 기준값이 다르면 409). readOnly 면 보기만
 */
export function RelationshipPairsEditor({ relationshipId, readOnly, onSaved }: { relationshipId: number; readOnly?: boolean; onSaved?: (saved: RelationshipPairs) => void }) {
  const [data, setData] = useState<RelationshipPairs | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  const load = (next: RelationshipPairs) => {
    setData(next);
    const paired = next.state.pairs.map((p) => ({ parentId: p.parentAttributeId, childId: p.childAttributeId }));
    const rest = next.parentCandidates.filter((c) => !paired.some((r) => r.parentId === c.attributeId)).map((c) => ({ parentId: c.attributeId, childId: null }));
    setRows([...paired, ...rest]);
  };
  useEffect(() => {
    api<RelationshipPairs>(`/api/logical/relationship/${relationshipId}/attributes`).then(load).catch((e) => setError(e instanceof ApiError ? e.message : t('속성 짝을 불러오지 못했습니다.')));
  }, [relationshipId]);

  if (error && !data) return <div className="ai-dict-callout is-error" role="alert">{error}</div>;
  if (!data) return <p className="gh-muted">{t('불러오는 중…')}</p>;

  const name = (id: number | null, list: RelationshipPairs['childCandidates']) => list.find((c) => c.attributeId === id)?.attributeName ?? '-';
  const chosen = rows.filter((r) => r.childId != null);
  const duplicates = new Set(chosen.map((r) => r.childId).filter((id, i, all) => all.indexOf(id) !== i));
  const self = data.parentEntityId === data.childEntityId;
  const missingChild = rows.filter((r) => r.childId == null).length;
  const move = (index: number, delta: number) => setRows((prev) => {
    const next = [...prev];
    const [row] = next.splice(index, 1);
    next.splice(index + delta, 0, row);
    return next;
  });
  const setChild = (index: number, value: string) => { setSaved(false); setRows((prev) => prev.map((r, i) => (i === index ? { ...r, childId: value ? Number(value) : null } : r))); };
  const save = async () => {
    setBusy(true); setError(null); setSaved(false);
    try {
      const result = await api<RelationshipPairs>(`/api/logical/relationship/${relationshipId}/attributes`, {
        method: 'PUT', silent: true,
        body: { modifiedAt: data.modifiedAt, pairs: chosen.map((r) => ({ parentAttributeId: r.parentId, childAttributeId: r.childId })) },
      });
      load(result); setSaved(true); onSaved?.(result);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('속성 짝을 저장하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="logical-pairs">
      <p className="logical-pairs-head">
        <strong>{data.parentEntityName}</strong> ({t('출발 · 부모')}) → <strong>{data.childEntityName}</strong> ({t('도착 · 자식')}){' '}
        <PairStatusLabel state={data.state} />
        {self && <> <Label>{t('자기 참조')}</Label></>}
      </p>
      {data.parentCandidates.length === 0 ? (
        <div className="ai-dict-callout is-warn" role="status">{t('부모 엔터티에 주 식별자가 없어 속성 짝을 지정할 수 없습니다. 먼저 주 식별자를 정해주세요.')}</div>
      ) : (
        <div className="logical-pairs-scroll">
          <table className="term-relink-table logical-pairs-table">
            <thead><tr><th>{t('순서')}</th><th>{t('부모 식별자 속성')}</th><th>{t('자식 속성')}</th>{!readOnly && <th>{t('순서 변경')}</th>}</tr></thead>
            <tbody>
              {rows.map((row, index) => {
                const parent = data.parentCandidates.find((c) => c.attributeId === row.parentId);
                const dup = row.childId != null && duplicates.has(row.childId);
                return (
                  <tr key={row.parentId} className={dup ? 'logical-pairs-dup' : undefined}>
                    <td>{index + 1}</td>
                    <td>{parent ? parent.attributeName : <span className="logical-pairs-error">{t('부모 식별자에 없는 속성')}</span>}{parent?.keyOrder != null && <span className="gh-muted"> (PK {parent.keyOrder})</span>}</td>
                    <td>
                      {readOnly ? name(row.childId, data.childCandidates) : (
                        <select className="form-control" aria-label={t('자식 속성')} value={row.childId ?? ''} onChange={(e) => setChild(index, e.target.value)}>
                          <option value="">{t('선택 안 함')}</option>
                          {data.childCandidates.map((c) => <option key={c.attributeId} value={c.attributeId}>{c.attributeName}{c.primary ? ' (PK)' : ''}</option>)}
                        </select>
                      )}
                      {dup && <div className="logical-pairs-error">{t('같은 자식 속성을 두 번 고를 수 없습니다.')}</div>}
                      {self && row.childId === row.parentId && <div className="gh-muted">{t('부모 키 속성 자신입니다. 상위 항목을 가리키는 별도 속성인지 확인해주세요.')}</div>}
                    </td>
                    {!readOnly && (
                      <td className="logical-pairs-move">
                        <button type="button" className="btn btn-sm" aria-label={t('위로')} disabled={index === 0} onClick={() => move(index, -1)}>↑</button>
                        <button type="button" className="btn btn-sm" aria-label={t('아래로')} disabled={index === rows.length - 1} onClick={() => move(index, 1)}>↓</button>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {data.state.warnings.length > 0 && <ul className="logical-pairs-warnings">{data.state.warnings.map((w) => <li key={w}>{PAIR_WARNINGS[w]}</li>)}</ul>}
      {!readOnly && data.parentCandidates.length > 0 && missingChild > 0 && (
        <p className="gh-muted">{t('자식 속성을 고르지 않은 부모 식별자 속성이 {0}개 있습니다. 일부만 이으면 FK 근거로 완성되지 않습니다.', missingChild)}</p>
      )}
      <p className="gh-muted">{t('속성 짝은 논리 관계에 저장되고, 짝이 완성된 관계는 논리 → 물리 생성 때 FK 의 근거가 됩니다. 이미 만든 물리 테이블은 바꾸지 않습니다.')}</p>
      {error && <div className="ai-dict-callout is-error" role="alert">{error}</div>}
      {saved && <div className="ai-dict-callout is-ok" role="status">{t('속성 짝을 저장했습니다.')}</div>}
      {!readOnly && (
        <div className="gh-dialog-actions">
          <button type="button" className="btn btn-primary" disabled={busy || duplicates.size > 0 || data.parentCandidates.length === 0} onClick={() => void save()}>{t('속성 짝 저장')}</button>
        </div>
      )}
    </div>
  );
}
