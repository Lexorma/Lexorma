import { useEffect, useRef, useState } from 'react';
import { useDialog } from '../../components/dialog/DialogProvider';
import { Label, Mono } from '../../components/grid/cells';
import { ApiError, api } from '../../lib/api';
import { t } from '../../lib/i18n';
import '../../styles/term-relink.css';
import '../../styles/ai-dictionary.css';

/**
 * 기존 물리 테이블 누락 FK 보정 (M7-FK-01, docs/completed/physical-fk-correction-m7.md) — 정합성 비교의 [관계] 범위에서 FK 누락 관계를 골라 미리보기 → 확인 → 적용.
 * 보정 가능 여부·사유는 서버만 판정한다 (화면은 /quality/foreignKey/status·preview 결과를 보여 줄 뿐). 기존 FK·컬럼은 바꾸지 않는다.
 */

/** ForeignKeyCorrectionDto.Code */
export type CorrectionCode =
  | 'NOT_MISSING' | 'INHERITANCE_LINK' | 'SINGLE_FK_HELD' | 'CHILD_TABLE_APPROVED' | 'CHILD_COLUMN_APPROVED' | 'REF_COLUMN_CONFLICT'
  | 'RELATION_EXISTS' | 'PARENT_KEY_INVALID' | 'TYPE_INCOMPATIBLE' | 'DUPLICATE_COLUMN' | 'SHARED_STRUCTURE' | 'NAME_CONFLICT';

export interface CorrectionIssue { code: CorrectionCode; reason: string }

export interface CorrectionPair {
  order: number; childColumnId: number | null; childColumnName: string | null; childDataType: string | null;
  parentColumnId: number | null; parentColumnName: string | null; parentDataType: string | null;
}

/** ForeignKeyCorrectionDto.Item */
export interface CorrectionItem {
  relationshipId: number; relationshipName: string | null; logicalValue: string | null;
  parentEntityId: number | null; parentEntityName: string | null; childEntityId: number | null; childEntityName: string | null;
  parentTableId: number | null; parentSchemaName: string | null; parentTableName: string | null;
  childTableId: number | null; childSchemaName: string | null; childTableName: string | null;
  diagnosisCode: string; representation: 'USER_RELATION' | 'REF_COLUMN' | null; selfReference: boolean; relationName: string | null; approved: boolean;
  pairs: CorrectionPair[]; correctable: boolean; blockers: CorrectionIssue[];
}

export interface CorrectionPlan { modelId: number; items: CorrectionItem[]; total: number; correctable: number; blocked: number; applicable: boolean; planToken: string }
export interface CorrectionResult { selected: number; created: number; relationIds: number[]; versionId: number; versionName: string }

/** 서버 ForeignKeyCorrectionDto.MAX_RELATIONSHIPS 와 같다 (서버가 다시 검사한다) */
export const CORRECTION_MAX = 100;

export const CORRECTION_CODE_LABELS: Record<CorrectionCode, string> = {
  NOT_MISSING: t('FK 누락 아님'),
  INHERITANCE_LINK: t('상속 FK'),
  SINGLE_FK_HELD: t('정책 보류 (단일 FK)'),
  CHILD_TABLE_APPROVED: t('승인된 자식 테이블'),
  CHILD_COLUMN_APPROVED: t('승인된 자식 컬럼'),
  REF_COLUMN_CONFLICT: t('참조 테이블명 충돌'),
  RELATION_EXISTS: t('같은 관계 있음'),
  PARENT_KEY_INVALID: t('참조 키 제한'),
  TYPE_INCOMPATIBLE: t('타입 비호환'),
  DUPLICATE_COLUMN: t('중복 컬럼'),
  SHARED_STRUCTURE: t('다른 관계와 같은 구성'),
  NAME_CONFLICT: t('FK 이름 충돌'),
};

const issueText = (issues: CorrectionIssue[]) => issues.map((i) => i.reason).join('\n');

/** 목록 칸 : 보정 가능 / 정책 보류 / 보정 불가(첫 사유) */
export function CorrectionLabel({ item }: { item: CorrectionItem | undefined }) {
  if (!item) return null;
  if (item.correctable) return <Label variant="success">{t('보정 가능')}</Label>;
  const first = item.blockers[0];
  const held = item.blockers.some((b) => b.code === 'SINGLE_FK_HELD');
  return (
    <span className="rename-cell" title={issueText(item.blockers)}>
      <Label variant={held ? 'attention' : 'danger'}>{held ? t('정책 보류') : t('보정 불가')}</Label>
      <span className="rename-cell-code">{first ? CORRECTION_CODE_LABELS[first.code] ?? first.code : ''}{item.blockers.length > 1 ? ` +${item.blockers.length - 1}` : ''}</span>
    </span>
  );
}

export function CorrectionIssues({ issues }: { issues: CorrectionIssue[] }) {
  if (issues.length === 0) return null;
  return (
    <ul className="rename-issues">
      {issues.map((issue, index) => (
        <li key={index}><Label variant={issue.code === 'SINGLE_FK_HELD' ? 'attention' : 'danger'}>{CORRECTION_CODE_LABELS[issue.code] ?? issue.code}</Label> {issue.reason}</li>
      ))}
    </ul>
  );
}

const tablePath = (schema: string | null, table: string | null) => `${schema ? `${schema}.` : ''}${table ?? '-'}`;

/** 정합성 상세의 [FK 보정] 판정 (FK 누락 관계만) */
export function CorrectionDetail({ item }: { item: CorrectionItem | undefined }) {
  if (!item) return null;
  return (
    <div className="rename-detail">
      <h4 className="mapping-subtitle">{t('누락 FK 보정')} <CorrectionLabel item={item} /></h4>
      {item.correctable
        ? <p className="form-help">{t('[관계] 범위 목록에서 이 관계를 골라 FK 보정 미리보기를 할 수 있습니다. 만들 FK : {0} (미승인)', item.relationName ?? '-')}</p>
        : <CorrectionIssues issues={item.blockers} />}
    </div>
  );
}

/**
 * 보정 미리보기 → 확인 → 적용 (팝업 본문). 열면 고른 관계로 미리보기를 한 번 부른다. 보정할 수 없는 관계가 하나라도 있으면 적용할 수 없다 (부분 적용 없음, D04) —
 * 사용자가 [보정 불가 항목 빼기]를 눌러야만 선택이 바뀐다. 409 면 계획을 버리고 목록을 새로 고치게 한 뒤 다시 미리보기를 받아야 한다 (자동 재시도 없음).
 */
export function ForeignKeyPreview({ modelId, ids, onRemove, onStale, onDone, onClose }: {
  modelId: number;
  ids: number[];
  onRemove: (ids: number[]) => void;
  onStale: () => void;
  onDone: (result: CorrectionResult) => void;
  onClose: () => void;
}) {
  const dialog = useDialog();
  const [current, setCurrent] = useState(ids);
  const [plan, setPlan] = useState<CorrectionPlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const sequence = useRef(0);
  const applying = useRef(false);

  const preview = async (targets: number[]) => {
    const mine = ++sequence.current;
    setBusy(true); setError(null); setPlan(null);
    try {
      const next = await api<CorrectionPlan>('/api/modelMapping/quality/foreignKey/preview', { method: 'POST', silent: true, body: { modelId, relationshipIds: targets, planToken: null } });
      if (mine === sequence.current) { setPlan(next); setStale(false); }
    } catch (e) {
      if (mine === sequence.current) setError(e instanceof ApiError ? e.message : t('미리보기를 만들지 못했습니다.'));
    } finally {
      if (mine === sequence.current) setBusy(false);
    }
  };

  useEffect(() => { void preview(ids); }, []); // eslint-disable-line react-hooks/exhaustive-deps -- 처음 열 때 한 번

  const removeBlocked = () => {
    if (!plan) return;
    const blocked = plan.items.filter((i) => !i.correctable).map((i) => i.relationshipId);
    const rest = current.filter((id) => !blocked.includes(id));
    onRemove(blocked);
    setCurrent(rest);
    if (rest.length > 0) void preview(rest);
    else { setPlan(null); setError(null); }
  };

  const apply = async () => {
    if (!plan || !plan.applicable || applying.current) return;
    applying.current = true;
    const mine = ++sequence.current;
    setBusy(true); setError(null);
    try {
      const result = await api<CorrectionResult>('/api/modelMapping/quality/foreignKey/apply', {
        method: 'POST', silent: true, body: { modelId, relationshipIds: plan.items.map((i) => i.relationshipId), planToken: plan.planToken },
      });
      onDone(result);
    } catch (e) {
      setPlan(null);
      if (e instanceof ApiError && e.status === 409) {
        setStale(true);
        setError(t('다른 사용자가 모델을 변경했거나 다른 작업이 같은 테이블을 바꾸는 중입니다. 목록을 새로 고친 뒤 다시 미리보기를 진행해 주세요.'));
        onStale();
      } else {
        setError(e instanceof ApiError ? e.message : t('FK 를 보정하지 못했습니다.'));
      }
    } finally {
      applying.current = false;
      if (mine === sequence.current) setBusy(false);
    }
  };

  const confirmApply = () => {
    if (!plan || !plan.applicable || busy) return;
    dialog.confirm([
      t('논리 관계 {0}개의 누락 FK 를 미승인 상태로 만듭니다.', plan.total),
      t('만들기 직전 상태를 모델 버전(FKFIX-…)으로 남기며, 하나라도 실패하면 전부 되돌립니다. 기존 FK·컬럼·PK·매핑은 바꾸지 않습니다.'),
    ].join('\n'), () => void apply());
  };

  return (
    <div className="term-relink rename-preview" aria-busy={busy}>
      <p className="gh-muted">{t('고른 논리 관계에 대응하는 물리 FK 를 새로 만듭니다. 기존 FK 를 고치거나 지우지 않고, 컬럼·타입·PK·UNIQUE 도 바꾸지 않습니다. 만들 FK 구성과 보정 가능 여부는 서버가 지금 상태로 다시 판정합니다.')}</p>
      {busy && !plan && <p className="gh-muted" role="status">{t('미리보기를 만드는 중입니다.')}</p>}
      {plan && (
        <>
          <p className="gh-muted" role="status">{t('선택 {0}개 · 보정 가능 {1}개 · 보정 불가 {2}개', plan.total, plan.correctable, plan.blocked)}</p>
          {!plan.applicable && (
            <div className="ai-dict-callout is-warn" role="status">
              {t('보정할 수 없는 관계가 있어 적용할 수 없습니다 (부분 적용은 하지 않습니다). 그 관계를 선택에서 빼고 다시 미리보기해주세요.')}
            </div>
          )}
          <div className="rename-preview-scroll">
            <table className="term-relink-table rename-preview-table">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '20%' }}>{t('논리 관계')}</th>
                  <th scope="col" style={{ width: '22%' }}>{t('자식 → 부모 테이블')}</th>
                  <th scope="col" style={{ width: '22%' }}>{t('FK 컬럼 (순서대로)')}</th>
                  <th scope="col" style={{ width: '14%' }}>{t('만들 FK')}</th>
                  <th scope="col">{t('판정')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.items.map((item) => (
                  <tr key={item.relationshipId}>
                    <td>{item.logicalValue ?? item.relationshipName ?? item.relationshipId}</td>
                    <td><Mono>{tablePath(item.childSchemaName, item.childTableName)}</Mono> → <Mono>{tablePath(item.parentSchemaName, item.parentTableName)}</Mono>
                      {item.selfReference && <div className="gh-muted">{t('자기 참조')}</div>}</td>
                    <td>
                      {item.pairs.length === 0 ? <span className="gh-muted">-</span> : (
                        <ol className="fk-pairs">
                          {item.pairs.map((p) => <li key={p.order}><Mono>{p.childColumnName ?? '-'}</Mono> → <Mono>{p.parentColumnName ?? '-'}</Mono></li>)}
                        </ol>
                      )}
                    </td>
                    <td>{item.relationName ? <Mono>{item.relationName}</Mono> : <span className="gh-muted">-</span>}
                      {item.correctable && <div className="gh-muted">{t('미승인 관계')}</div>}</td>
                    <td>{item.correctable ? <Label variant="success">{t('보정 가능')}</Label> : <CorrectionIssues issues={item.blockers} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {error && <div className={`ai-dict-callout ${stale ? 'is-warn' : 'is-error'}`} role="alert">{error}</div>}
      <div className="gh-dialog-actions">
        <button type="button" className="btn" disabled={busy} onClick={onClose}>{t('닫기')}</button>
        {plan && !plan.applicable && <button type="button" className="btn" disabled={busy} onClick={removeBlocked}>{t('보정 불가 항목을 선택에서 빼고 다시 미리보기')}</button>}
        {(stale || (!plan && error)) && current.length > 0 && <button type="button" className="btn" disabled={busy} onClick={() => void preview(current)}>{t('다시 미리보기')}</button>}
        <button type="button" className={`btn btn-primary${busy && plan ? ' is-busy' : ''}`} disabled={busy || !plan || !plan.applicable} aria-busy={busy}
          onClick={confirmApply} title={!plan ? t('미리보기를 먼저 확인해주세요.') : !plan.applicable ? t('보정할 수 없는 관계가 있습니다.') : undefined}>
          <span className="motion-busy-label">{t('FK 보정 적용')}</span>
          {busy && plan && <span className="motion-spinner" aria-hidden="true" />}
        </button>
      </div>
    </div>
  );
}

export function correctionResultMessage(result: CorrectionResult) {
  return t('논리 관계 {0}개 중 FK {1}개를 미승인 상태로 만들었습니다. 만들기 직전 모델 버전 {2} 을(를) 남겼습니다.', result.selected, result.created, result.versionName);
}
